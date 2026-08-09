package com.rpgos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
        val live = File(campaignDir, "campaign.db")
        store.openSaveDb().use { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS gm_restore_probe(value TEXT NOT NULL)")
            db.execSQL("DELETE FROM gm_restore_probe")
            db.execSQL("INSERT INTO gm_restore_probe(value) VALUES('ORIGINAL')")
        }

        val safety = File(campaignDir, "backups/recovery_safety.db")
        SQLitePersistenceCopy141.copyLiveDatabase(
            source = live,
            target = safety,
            sourceBoundary = "TEST_RECOVERY_LIVE",
            artifactBoundary = "TEST_RECOVERY_SAFETY"
        )

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
