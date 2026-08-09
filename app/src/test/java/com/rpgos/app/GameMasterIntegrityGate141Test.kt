package com.rpgos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GameMasterIntegrityGate141Test {
    private lateinit var context: Context
    private lateinit var campaignDir: File
    private lateinit var store: LocalGameStore

    private val subject = EntityUid("SUBJECT-integrity-gate")
    private val previousUid = EntityUid("FACT-integrity-gate-old")
    private val replacementUid = EntityUid("FACT-integrity-gate-new")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
        campaignDir = File(context.filesDir, "rpgos/saves/Naruto_Default.campaign")
        campaignDir.deleteRecursively()
        campaignDir.mkdirs()
        store = LocalGameStore(context)
        store.setActiveCampaign(campaignDir.name)
        store.bootstrap()
    }

    @After
    fun tearDown() {
        runCatching { campaignDir.deleteRecursively() }
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun healthyCampaignCanBeBackedUp() = runBlocking {
        createHealthySupersession(withCommittedTurn = false)

        store.openSaveDb().use { db ->
            val report = GameMasterIntegrityGate141(db).check()
            assertTrue(report.issues.joinToString { it.code }, report.ok)
        }

        val backup = BackupManager(context).createBackup("integrity_gate_ok")
        assertTrue(backup.isFile)
        assertTrue(GameMasterIntegrityGate141.checkFile(backup).ok)
    }

    @Test
    fun corruptedSupersessionBlocksBackup() = runBlocking {
        createHealthySupersession(withCommittedTurn = false)
        corruptEffectiveTurn()

        val failure = runCatching {
            BackupManager(context).createBackup("should_not_exist")
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(
            failure?.message.orEmpty(),
            failure?.message.orEmpty().contains("GM141_INTEGRITY_GATE_FAILED[BACKUP_SOURCE]")
        )
        assertTrue(
            failure?.message.orEmpty(),
            failure?.message.orEmpty().contains("SUPERSESSION_PREVIOUS_WINDOW_MISMATCH")
        )
        val generated = File(campaignDir, "backups").listFiles()
            ?.filter { it.name.contains("should_not_exist") }
            .orEmpty()
        assertTrue(generated.isEmpty())
    }

    @Test
    fun corruptedRestoreSourceCannotReplaceHealthyLiveDatabase() = runBlocking {
        val campaignUid = createHealthySupersession(withCommittedTurn = false)
        val liveDb = File(campaignDir, "campaign.db")
        val incoming = File(campaignDir, "incoming_corrupted.db")
        liveDb.copyTo(incoming, overwrite = true)

        SQLiteDatabase.openDatabase(
            incoming.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE
        ).use { db ->
            db.execSQL(
                """
                UPDATE gm_truth_supersessions
                SET effective_turn=2
                WHERE campaign_id=? AND previous_truth_id=?
                """.trimIndent(),
                arrayOf(campaignUid.value, previousUid.value)
            )
        }

        assertFalse(GameMasterIntegrityGate141.checkFile(incoming).ok)
        assertTrue(GameMasterIntegrityGate141.checkFile(liveDb).ok)

        val failure = runCatching {
            RestoreManager(context).restoreBackup(campaignDir.name, incoming.absolutePath)
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(
            failure?.message.orEmpty(),
            failure?.message.orEmpty().contains("GM141_INTEGRITY_GATE_FAILED[RESTORE_SOURCE]")
        )

        // The live DB must still contain the original healthy lineage.
        assertTrue(GameMasterIntegrityGate141.checkFile(liveDb).ok)
        SQLiteDatabase.openDatabase(
            liveDb.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        ).use { db ->
            db.rawQuery(
                """
                SELECT effective_turn FROM gm_truth_supersessions
                WHERE campaign_id=? AND previous_truth_id=?
                """.trimIndent(),
                arrayOf(campaignUid.value, previousUid.value)
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1L, c.getLong(0))
            }
        }
    }

    @Test
    fun corruptedCampaignSkipsAutomaticSnapshotWithoutRollingBackTurn() = runBlocking {
        createHealthySupersession(withCommittedTurn = true)
        corruptEffectiveTurn()

        var checkpointFailure: Throwable? = null
        val created = GameMasterRepositoryFactory(context, store).openActiveSession().use { active ->
            GameMasterSnapshotPolicy141.maintain(
                repository = active.repository,
                campaignUid = active.campaignUid,
                integrityGate = {
                    store.openSaveDb().use { db ->
                        GameMasterIntegrityGate141(db).requireHealthy("SNAPSHOT_SOURCE")
                    }
                },
                onFailure = { checkpointFailure = it }
            )
        }

        assertNull(created)
        assertNotNull(checkpointFailure)
        assertTrue(
            checkpointFailure?.message.orEmpty(),
            checkpointFailure?.message.orEmpty().contains("GM141_INTEGRITY_GATE_FAILED[SNAPSHOT_SOURCE]")
        )

        store.openSaveDb().use { db ->
            val turnCount = db.rawQuery(
                "SELECT COUNT(*) FROM gm_turns WHERE turn_number=1",
                null
            ).use { c -> c.moveToFirst(); c.getLong(0) }
            val snapshotCount = db.rawQuery(
                "SELECT COUNT(*) FROM gm_snapshots",
                null
            ).use { c -> c.moveToFirst(); c.getLong(0) }
            assertEquals(1L, turnCount)
            assertEquals(0L, snapshotCount)
        }
    }

    private suspend fun createHealthySupersession(withCommittedTurn: Boolean): EntityUid =
        GameMasterRepositoryFactory(context, store).openActiveSession().use { active ->
            if (withCommittedTurn) {
                active.repository.writeTurn(
                    DurableTurnRecord(
                        turnUid = EntityUid("TURN-integrity-gate-1"),
                        campaignUid = active.campaignUid,
                        turnId = 1L,
                        chapter = 0L,
                        playerInput = "test",
                        narrative = "test",
                        startedAtEpochMs = 1L,
                        committedAtEpochMs = 2L,
                        status = TurnTransactionStatus.COMMITTED
                    )
                )
            }

            active.repository.writeTruth(
                CampaignTruth(
                    uid = previousUid,
                    kind = TruthKind.FACT,
                    subjectUid = subject,
                    predicate = "world.owner",
                    value = "OLD",
                    validFromTurn = 0L,
                    provenance = ProvenanceRecord(
                        type = ProvenanceType.IMPORTED_CONTENT,
                        sourceUid = null,
                        turnId = 0L,
                        confidence = 1.0,
                        verified = true
                    )
                )
            )
            requireNotNull(active.truthSupersessionStore).supersedeFact(
                previousUid,
                CampaignTruth(
                    uid = replacementUid,
                    kind = TruthKind.FACT,
                    subjectUid = subject,
                    predicate = "world.owner",
                    value = "NEW",
                    validFromTurn = null,
                    provenance = ProvenanceRecord(
                        type = ProvenanceType.IMPORTED_CONTENT,
                        sourceUid = null,
                        turnId = 1L,
                        confidence = 1.0,
                        verified = true
                    )
                ),
                effectiveTurn = 1L
            )
            active.campaignUid
        }

    private fun corruptEffectiveTurn() {
        store.openSaveDb().use { db ->
            db.execSQL(
                "UPDATE gm_truth_supersessions SET effective_turn=2 WHERE previous_truth_id=?",
                arrayOf(previousUid.value)
            )
        }
    }
}
