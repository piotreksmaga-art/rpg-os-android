package com.rpgos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
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
class UpdateBackupManager141Test {
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
    fun preUpdateBackupIncludesCommittedFramesStillInWalAndIsHealthy() {
        store.openSaveDb().use { db ->
            db.rawQuery("PRAGMA journal_mode=WAL", null).use { c -> assertTrue(c.moveToFirst()) }
            db.rawQuery("PRAGMA wal_autocheckpoint=0", null).use { c -> assertTrue(c.moveToFirst()) }
            db.execSQL("CREATE TABLE IF NOT EXISTS gm_pre_update_wal_probe(value TEXT NOT NULL)")
            db.execSQL("DELETE FROM gm_pre_update_wal_probe")
            db.execSQL("INSERT INTO gm_pre_update_wal_probe(value) VALUES('pre-update-from-wal')")

            val wal = File(db.path + "-wal")
            assertTrue("Test musi faktycznie pozostawić ramki WAL.", wal.isFile && wal.length() > 0L)

            val backup = UpdateBackupManager(context).createPreUpdateBackup()
            assertTrue(backup.isFile)
            assertEquals(File(campaignDir, "backups").canonicalPath, backup.parentFile?.canonicalPath)
            assertTrue(backup.name.endsWith("_pre_update.db"))
            assertTrue(GameMasterIntegrityGate141.checkFile(backup).ok)

            SQLiteDatabase.openDatabase(
                backup.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            ).use { copied ->
                copied.rawQuery("SELECT value FROM gm_pre_update_wal_probe LIMIT 1", null).use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals("pre-update-from-wal", c.getString(0))
                }
            }
        }
    }
}
