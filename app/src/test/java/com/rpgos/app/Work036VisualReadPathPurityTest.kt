package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Work036VisualReadPathPurityTest {
    @Test
    fun visualPresentationTableIsClassifiedAndOrdinaryAccessNeverMutatesSchema() {
        SQLiteDatabase.create(null).use { db ->
            CurrentSchema.ensure(db, "C1")
            GameplayRuntimeBootstrap.initialize(db, "C1")

            assertEquals("UI_STATE", RuntimeTruthLayerRegistry.requireClassifiedTable("campaign_visual_library").uid)
            RuntimePersistentTableInventory.requireComplete(db)

            val beforeRead = sqliteObjects(db)
            assertTrue(VisualLibrary(db).list().isEmpty())
            assertEquals(beforeRead, sqliteObjects(db))

            VisualLibrary(db).add("T", "IMAGE", "uri", null, null, null, null, null)
            assertEquals(beforeRead, sqliteObjects(db))
            assertEquals(1, VisualLibrary(db).list().size)
        }
    }

    @Test
    fun visualWriterHasPresentationCapabilityNotReadOnlyOrAuthority() {
        val read = RuntimePersistentWriterRegistry.requireContract("visualLibrary")
        val write = RuntimePersistentWriterRegistry.requireContract("addVisual")

        assertEquals(PersistentWriterCapability.READ_ONLY_NON_AUTHORITATIVE, read.capability)
        assertEquals(PersistentWriterCapability.PRESENTATION_ONLY, write.capability)
        assertEquals(setOf("UI_STATE"), write.targetFamilyUids)
    }

    private fun sqliteObjects(db: SQLiteDatabase): List<String> = db.rawQuery(
        "SELECT type||':'||name||':'||COALESCE(sql,'') FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type,name",
        null
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
}
