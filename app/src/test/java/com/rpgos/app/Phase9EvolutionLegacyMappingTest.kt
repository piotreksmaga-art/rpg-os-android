package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase9EvolutionLegacyMappingTest {
    private lateinit var dbFile: File

    @Before fun setUp() {
        dbFile = File.createTempFile("rpgos-phase9-evolution-map-", ".db")
        dbFile.delete()
    }

    @After fun tearDown() { dbFile.delete() }

    @Test
    fun explicitStageMappingMaterializesExactlyOneCurrentAndAttainedState() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL("CREATE TABLE character_status_snapshot(entity_uid TEXT PRIMARY KEY,evolution_stage TEXT)")
            db.execSQL("INSERT INTO character_status_snapshot VALUES('P','legacy-stage')")
            MigrationManager().ensureV9(db, "C")
            val store = Phase9Store(db, "C")
            store.registerEvolutionPaths("W", listOf(EvolutionPathDefinition("PATH", "W", "path", "Path", provenance = "pack")))
            store.registerEvolutionStages("W", listOf(EvolutionStageDefinition("STAGE", "PATH", "W", "stage", "Stage", provenance = "pack")))
            store.registerLegacyMappings("W", listOf(
                LegacyPhase9Mapping("W", "evolution_stage", "legacy-stage", LegacyPhase9TargetKind.EVOLUTION_STAGE, "STAGE", provenance = "explicit")
            ))

            store.applyLegacyMappings("P", "W")
            store.applyLegacyMappings("P", "W")

            assertEquals("STAGE", store.evolutionStates("P").single().currentStageUid)
            assertEquals(listOf("STAGE"), store.attainedStages("P").map { it.stageUid })
            db.rawQuery("SELECT evolution_stage FROM character_status_snapshot WHERE entity_uid='P'", null).use { cursor ->
                cursor.moveToFirst()
                assertEquals("legacy-stage", cursor.getString(0))
            }
        }
    }
}
