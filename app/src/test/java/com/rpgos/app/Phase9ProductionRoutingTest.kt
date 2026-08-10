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
@Config(sdk = [34])
class Phase9ProductionRoutingTest {
    private lateinit var context: Context
    private lateinit var root: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
        root = File(context.filesDir, "rpgos")
        root.deleteRecursively()
        root.mkdirs()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
        root.deleteRecursively()
    }

    @Test
    fun campaignSwitchRoutesSelectedDatabaseThroughV9() {
        createV8Campaign(ActiveCampaignRef.DEFAULT_DIRECTORY, ActiveCampaignRef.DEFAULT_CAMPAIGN_ID)
        val other = createV8Campaign("Other.campaign", "other")
        val store = LocalGameStore(context)

        store.setActiveCampaign("Other.campaign")

        assertEquals("Other.campaign", store.activeCampaignDirName())
        assertV9(other)
    }

    @Test
    fun restoreRoutesRestoredDatabaseThroughV9() {
        val active = createV8Campaign(ActiveCampaignRef.DEFAULT_DIRECTORY, ActiveCampaignRef.DEFAULT_CAMPAIGN_ID)
        val backupDir = File(active.parentFile, "backups").apply { mkdirs() }
        val backup = File(backupDir, "legacy_v8.db")
        createV8Database(backup, ActiveCampaignRef.DEFAULT_CAMPAIGN_ID)
        val store = LocalGameStore(context)

        val safetyPath = store.restoreBackup(backup.absolutePath)

        assertTrue(File(safetyPath).isFile)
        assertV9(active)
    }

    private fun createV8Campaign(directoryName: String, campaignId: String): File {
        val dir = File(root, "saves/$directoryName").apply { mkdirs() }
        File(dir, "campaign.json").writeText("{\"id\":\"$campaignId\"}")
        val db = File(dir, "campaign.db")
        createV8Database(db, campaignId)
        return db
    }

    private fun createV8Database(file: File, campaignId: String) {
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            MigrationManager().ensureV8(db, campaignId)
        }
    }

    private fun assertV9(dbFile: File) {
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery(
                "SELECT COUNT(*) FROM rpgos_schema_migrations WHERE migration_id=?",
                arrayOf(PHASE9_MIGRATION_ID)
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            db.rawQuery(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='innate_feature_definitions'",
                null
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        }
    }
}
