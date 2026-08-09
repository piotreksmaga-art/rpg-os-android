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
        runCatching { File(context.filesDir, "rpgos/saves/Integrity_Custom.campaign").deleteRecursively() }
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
    fun backupIncludesCommittedFramesStillInWal() = runBlocking {
        createHealthySupersession(withCommittedTurn = false)

        store.openSaveDb().use { db ->
            db.rawQuery("PRAGMA journal_mode=WAL", null).use { c ->
                assertTrue(c.moveToFirst())
            }
            db.rawQuery("PRAGMA wal_autocheckpoint=0", null).use { c ->
                assertTrue(c.moveToFirst())
            }
            db.execSQL("CREATE TABLE IF NOT EXISTS gm_integrity_wal_probe(value TEXT NOT NULL)")
            db.execSQL("DELETE FROM gm_integrity_wal_probe")
            db.execSQL("INSERT INTO gm_integrity_wal_probe(value) VALUES(?)", arrayOf("from-wal"))

            val wal = File(db.path + "-wal")
            assertTrue("Test musi faktycznie pozostawić ramki WAL.", wal.isFile && wal.length() > 0L)

            val backup = BackupManager(context).createBackup("wal_probe")
            assertTrue(backup.isFile)
            SQLiteDatabase.openDatabase(
                backup.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            ).use { copied ->
                copied.rawQuery("SELECT value FROM gm_integrity_wal_probe LIMIT 1", null).use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals("from-wal", c.getString(0))
                }
            }
            assertTrue(GameMasterIntegrityGate141.checkFile(backup).ok)
        }
    }

    @Test
    fun snapshotIncludesCommittedFramesStillInWalAndRegistersHealthyArtifact() = runBlocking {
        createHealthySupersession(withCommittedTurn = true)

        store.openSaveDb().use { db ->
            db.rawQuery("PRAGMA journal_mode=WAL", null).use { c ->
                assertTrue(c.moveToFirst())
            }
            db.rawQuery("PRAGMA wal_autocheckpoint=0", null).use { c ->
                assertTrue(c.moveToFirst())
            }
            db.execSQL("CREATE TABLE IF NOT EXISTS gm_integrity_wal_probe(value TEXT NOT NULL)")
            db.execSQL("DELETE FROM gm_integrity_wal_probe")
            db.execSQL("INSERT INTO gm_integrity_wal_probe(value) VALUES(?)", arrayOf("snapshot-from-wal"))

            val wal = File(db.path + "-wal")
            assertTrue("Test musi faktycznie pozostawić ramki WAL.", wal.isFile && wal.length() > 0L)

            val snapshot = GameMasterRepositoryFactory(context, store).openActiveSession().use { active ->
                active.repository.createSnapshot(active.campaignUid, 1L)
            }

            val snapshotPath = db.rawQuery(
                "SELECT storage_path FROM gm_snapshots WHERE snapshot_id=? LIMIT 1",
                arrayOf(snapshot.snapshotUid.value)
            ).use { c ->
                assertTrue(c.moveToFirst())
                c.getString(0)
            }
            val artifact = File(snapshotPath)
            assertTrue(artifact.isFile)
            assertTrue(GameMasterIntegrityGate141.checkFile(artifact).ok)

            SQLiteDatabase.openDatabase(
                artifact.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            ).use { copied ->
                copied.rawQuery("SELECT value FROM gm_integrity_wal_probe LIMIT 1", null).use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals("snapshot-from-wal", c.getString(0))
                }
            }
        }
    }

    @Test
    fun backupManagerFollowsActiveCampaignInsteadOfDefaultSave() = runBlocking {
        createHealthySupersession(withCommittedTurn = false)
        val custom = store.createCampaign("Integrity_Custom")
        assertEquals("Integrity_Custom.campaign", store.activeCampaignDirName())

        val backup = BackupManager(context).createBackup("active_campaign")

        assertTrue(backup.isFile)
        assertEquals(File(custom, "backups").canonicalPath, backup.parentFile?.canonicalPath)
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
        SQLitePersistenceCopy141.copyLiveDatabase(
            source = liveDb,
            target = incoming,
            sourceBoundary = "TEST_LIVE_SOURCE",
            artifactBoundary = "TEST_INCOMING_ARTIFACT"
        )

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

    @Test
    fun corruptedCampaignCannotBypassGateThroughDirectRepositorySnapshot() = runBlocking {
        createHealthySupersession(withCommittedTurn = true)
        corruptEffectiveTurn()

        val failure = GameMasterRepositoryFactory(context, store).openActiveSession().use { active ->
            runCatching {
                active.repository.createSnapshot(active.campaignUid, 1L)
            }.exceptionOrNull()
        }

        assertNotNull(failure)
        assertTrue(failure is GameMasterIntegrityGateException141)
        val gateFailure = failure as GameMasterIntegrityGateException141
        assertEquals("SNAPSHOT_SOURCE", gateFailure.boundary)
        assertTrue("SUPERSESSION_PREVIOUS_WINDOW_MISMATCH" in gateFailure.errorCodes)

        store.openSaveDb().use { db ->
            val snapshotCount = db.rawQuery(
                "SELECT COUNT(*) FROM gm_snapshots",
                null
            ).use { c -> c.moveToFirst(); c.getLong(0) }
            assertEquals(0L, snapshotCount)
        }
        assertTrue(
            File(campaignDir, "snapshots").listFiles()?.none { it.extension == "db" } != false
        )
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
