package com.rpgos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class RestoreRecovery141Test {
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
    }

    @After
    fun tearDown() {
        runCatching { campaignDir.deleteRecursively() }
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun nextOpenRecoversSafetyCopyAfterProcessDiesBetweenSwapAndCompletion() {
        val live = prepareLiveProbe("ORIGINAL")
        val safety = createSafety(live, "recovery_safety.db")
        val incoming = File(campaignDir, "incoming_recovery.db")
        SQLitePersistenceCopy141.stageStandaloneDatabase(
            source = safety,
            staged = incoming,
            artifactBoundary = "TEST_RECOVERY_INCOMING"
        )
        SQLiteDatabase.openDatabase(
            incoming.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE
        ).use { db ->
            db.execSQL("UPDATE gm_restore_probe SET value='INCOMING'")
        }
        assertTrue(GameMasterIntegrityGate141.checkFile(incoming).ok)

        val staged = File(campaignDir, ".restore_staged_crash.db")
        SQLitePersistenceCopy141.stageStandaloneDatabase(
            source = incoming,
            staged = staged,
            artifactBoundary = "TEST_RECOVERY_STAGED"
        )

        RestoreRecovery141.begin(campaignDir, safety, hadLiveDatabase = true)
        SQLitePersistenceCopy141.replaceDatabaseWithStaged(staged, live)

        // Simulate process death here: no RestoreManager catch and no complete().
        assertTrue(RestoreRecovery141.hasPendingRecovery(campaignDir))
        assertEquals("INCOMING", readProbeDirect(live))

        // A normal campaign open must recover the old healthy DB before exposing it.
        store.openSaveDb().use { db ->
            db.rawQuery("SELECT value FROM gm_restore_probe LIMIT 1", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("ORIGINAL", c.getString(0))
            }
        }

        assertFalse(RestoreRecovery141.hasPendingRecovery(campaignDir))
        assertTrue(GameMasterIntegrityGate141.checkFile(live).ok)
    }

    @Test
    fun recoveryMarkerRejectsSafetyOutsideCampaignBackups() {
        val live = prepareLiveProbe("ORIGINAL")
        val externalSafety = File(campaignDir.parentFile, "outside_safety.db")
        SQLitePersistenceCopy141.copyLiveDatabase(
            source = live,
            target = externalSafety,
            sourceBoundary = "TEST_RECOVERY_EXTERNAL_SOURCE",
            artifactBoundary = "TEST_RECOVERY_EXTERNAL_ARTIFACT"
        )

        val failure = runCatching {
            RestoreRecovery141.begin(campaignDir, externalSafety, hadLiveDatabase = true)
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(
            failure?.message.orEmpty(),
            failure?.message.orEmpty().contains("RESTORE_RECOVERY_SAFETY_OUTSIDE_BACKUPS")
        )
        assertFalse(RestoreRecovery141.hasPendingRecovery(campaignDir))
        externalSafety.delete()
    }

    @Test
    fun recoveryRefusesSafetyArtifactChangedAfterMarkerWasPersisted() {
        val live = prepareLiveProbe("ORIGINAL")
        val safety = createSafety(live, "hash_bound_safety.db")
        RestoreRecovery141.begin(campaignDir, safety, hadLiveDatabase = true)

        // This custom table is not part of logical GM141 audits, so the DB remains
        // structurally healthy. The recovery hash must still detect byte-content change.
        SQLiteDatabase.openDatabase(
            safety.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE
        ).use { db ->
            db.execSQL("UPDATE gm_restore_probe SET value='TAMPERED'")
        }
        assertTrue(GameMasterIntegrityGate141.checkFile(safety).ok)

        val failure = runCatching {
            RestoreRecovery141.recoverIfNeeded(campaignDir)
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(
            failure?.message.orEmpty(),
            failure?.message.orEmpty().contains("RESTORE_RECOVERY_SAFETY_HASH_MISMATCH")
        )
        assertTrue(RestoreRecovery141.hasPendingRecovery(campaignDir))
        assertEquals("ORIGINAL", readProbeDirect(live))
    }

    private fun prepareLiveProbe(value: String): File {
        val live = File(campaignDir, "campaign.db")
        store.openSaveDb().use { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS gm_restore_probe(value TEXT NOT NULL)")
            db.execSQL("DELETE FROM gm_restore_probe")
            db.execSQL("INSERT INTO gm_restore_probe(value) VALUES(?)", arrayOf(value))
        }
        return live
    }

    private fun createSafety(live: File, fileName: String): File {
        val safety = File(campaignDir, "backups/$fileName")
        SQLitePersistenceCopy141.copyLiveDatabase(
            source = live,
            target = safety,
            sourceBoundary = "TEST_RECOVERY_LIVE",
            artifactBoundary = "TEST_RECOVERY_SAFETY"
        )
        return safety
    }

    private fun readProbeDirect(database: File): String =
        SQLiteDatabase.openDatabase(
            database.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        ).use { db ->
            db.rawQuery("SELECT value FROM gm_restore_probe LIMIT 1", null).use { c ->
                assertTrue(c.moveToFirst())
                c.getString(0)
            }
        }
}
