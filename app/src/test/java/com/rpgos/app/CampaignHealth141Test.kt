package com.rpgos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class CampaignHealth141Test {
    private lateinit var context: Context
    private lateinit var campaignDir: File
    private lateinit var store: LocalGameStore

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
        GameMasterRepositoryFactory(context, store).openRuntimeSession().use { }
    }

    @After
    fun tearDown() {
        runCatching { campaignDir.deleteRecursively() }
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun healthyCampaignReportsHealthyThroughApplicationStore() {
        val report = store.campaignHealth()

        assertEquals(CampaignHealthState141.HEALTHY, report.state)
        assertTrue(report.canEnterRuntime)
        assertFalse(report.recoveryWasPending)
        assertTrue(report.errorCodes.isEmpty())
    }

    @Test
    fun invalidCampaignReportsBlockedWithIntegrityCodesThroughApplicationStore() = runBlocking {
        val subject = EntityUid("SUBJECT-health-blocked")
        val oldUid = EntityUid("FACT-health-old")
        val newUid = EntityUid("FACT-health-new")

        GameMasterRepositoryFactory(context, store).openActiveSession().use { active ->
            active.repository.writeTruth(
                CampaignTruth(
                    uid = oldUid,
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
                oldUid,
                CampaignTruth(
                    uid = newUid,
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
        }

        SQLiteDatabase.openDatabase(
            File(campaignDir, "campaign.db").absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE
        ).use { db ->
            db.execSQL(
                "UPDATE gm_truth_supersessions SET effective_turn=2 WHERE previous_truth_id=?",
                arrayOf(oldUid.value)
            )
        }

        val report = store.campaignHealth()

        assertEquals(CampaignHealthState141.BLOCKED, report.state)
        assertFalse(report.canEnterRuntime)
        assertEquals("CAMPAIGN_OPEN", report.errorBoundary)
        assertTrue("SUPERSESSION_PREVIOUS_WINDOW_MISMATCH" in report.errorCodes)
    }

    @Test
    fun pendingRestoreThatRecoversAndPassesIntegrityReportsRecoveredThroughApplicationStore() {
        val live = File(campaignDir, "campaign.db")
        store.openSaveDb().use { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS gm_health_probe(value TEXT NOT NULL)")
            db.execSQL("DELETE FROM gm_health_probe")
            db.execSQL("INSERT INTO gm_health_probe(value) VALUES('ORIGINAL')")
        }

        val safety = File(campaignDir, "backups/health_recovery_safety.db")
        SQLitePersistenceCopy141.copyLiveDatabase(
            source = live,
            target = safety,
            sourceBoundary = "TEST_HEALTH_LIVE",
            artifactBoundary = "TEST_HEALTH_SAFETY"
        )

        val incoming = File(campaignDir, "incoming_health.db")
        SQLitePersistenceCopy141.stageStandaloneDatabase(
            source = safety,
            staged = incoming,
            artifactBoundary = "TEST_HEALTH_INCOMING"
        )
        SQLiteDatabase.openDatabase(
            incoming.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE
        ).use { db ->
            db.execSQL("UPDATE gm_health_probe SET value='INCOMING'")
        }

        val staged = File(campaignDir, ".restore_health_staged.db")
        SQLitePersistenceCopy141.stageStandaloneDatabase(
            source = incoming,
            staged = staged,
            artifactBoundary = "TEST_HEALTH_STAGED"
        )
        RestoreRecovery141.begin(campaignDir, safety, hadLiveDatabase = true)
        SQLitePersistenceCopy141.replaceDatabaseWithStaged(staged, live)
        assertTrue(RestoreRecovery141.hasPendingRecovery(campaignDir))

        val report = store.campaignHealth()

        assertEquals(CampaignHealthState141.RECOVERED, report.state)
        assertTrue(report.canEnterRuntime)
        assertTrue(report.recoveryWasPending)
        assertFalse(RestoreRecovery141.hasPendingRecovery(campaignDir))
        store.openSaveDb().use { db ->
            db.rawQuery("SELECT value FROM gm_health_probe LIMIT 1", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("ORIGINAL", c.getString(0))
            }
        }
    }
}
