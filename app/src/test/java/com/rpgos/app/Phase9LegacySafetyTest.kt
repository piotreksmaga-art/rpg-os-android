package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase9LegacySafetyTest {
    private lateinit var dbFile: File

    @Before fun setUp() {
        dbFile = File.createTempFile("rpgos-phase9-legacy-", ".db")
        dbFile.delete()
    }

    @After fun tearDown() { dbFile.delete() }

    @Test
    fun bareClanRaceBloodlineEvolutionAndFormEvidenceGrantsNothing() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL("CREATE TABLE character_status_snapshot(entity_uid TEXT PRIMARY KEY,clan_uid TEXT,race TEXT,bloodline TEXT,evolution_stage TEXT,form TEXT)")
            db.execSQL("INSERT INTO character_status_snapshot VALUES('P','clan-x','race-x','blood-x','stage-x','form-x')")
            MigrationManager().ensureV9(db, "C")
            val store = Phase9Store(db, "C")

            assertEquals(5, store.legacyEvidence("P").size)
            assertTrue(store.playerOrigins("P").isEmpty())
            assertTrue(store.playerInnateFeatures("P").isEmpty())
            assertTrue(store.evolutionStates("P").isEmpty())
            assertTrue(store.attainedStages("P").isEmpty())
            assertTrue(store.formUnlocks("P").isEmpty())
            assertTrue(store.activeForms("P").isEmpty())
        }
    }

    @Test
    fun ambiguousExplicitMappingsFailLoudInsteadOfGuessing() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL("CREATE TABLE character_status_snapshot(entity_uid TEXT PRIMARY KEY,race TEXT)")
            db.execSQL("INSERT INTO character_status_snapshot VALUES('P','same-label')")
            MigrationManager().ensureV9(db, "C")
            val store = Phase9Store(db, "C")
            store.registerOrigins("W", listOf(OriginDefinition("O", "W", "o", "Same", "generic", provenance = "pack")))
            store.registerInnateFeatures("W", listOf(InnateFeatureDefinition("F", "W", "f", "Same", "generic", provenance = "pack")))
            store.registerLegacyMappings("W", listOf(
                LegacyPhase9Mapping("W", "race", "same-label", LegacyPhase9TargetKind.ORIGIN, "O", provenance = "map-origin"),
                LegacyPhase9Mapping("W", "race", "same-label", LegacyPhase9TargetKind.INNATE_FEATURE, "F", provenance = "map-feature")
            ))

            assertFails { store.reconcileLegacy("P", "W") }
            assertTrue(store.playerOrigins("P").isEmpty())
            assertTrue(store.playerInnateFeatures("P").isEmpty())
        }
    }

    @Test
    fun mappingTargetDeletionFailsLoudAndLegacyBytesRemain() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL("CREATE TABLE character_status_snapshot(entity_uid TEXT PRIMARY KEY,race TEXT)")
            db.execSQL("INSERT INTO character_status_snapshot VALUES('P','legacy-race')")
            MigrationManager().ensureV9(db, "C")
            val store = Phase9Store(db, "C")
            store.registerOrigins("W", listOf(OriginDefinition("O", "W", "o", "Origin", "generic", provenance = "pack")))
            store.registerLegacyMappings("W", listOf(LegacyPhase9Mapping("W", "race", "legacy-race", LegacyPhase9TargetKind.ORIGIN, "O", provenance = "map")))
            db.delete("origin_definitions_v2", "origin_uid=?", arrayOf("O"))

            assertFails { store.reconcileLegacy("P", "W") }
            db.rawQuery("SELECT race FROM character_status_snapshot WHERE entity_uid='P'", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("legacy-race", cursor.getString(0))
            }
        }
    }

    @Test
    fun typedStatePlusUnmappedLegacyEvidenceRemainsExplicitlyUnresolved() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL("CREATE TABLE character_status_snapshot(entity_uid TEXT PRIMARY KEY,race TEXT)")
            db.execSQL("INSERT INTO character_status_snapshot VALUES('P','legacy-race')")
            MigrationManager().ensureV9(db, "C")
            val store = Phase9Store(db, "C")
            store.registerOrigins("W", listOf(OriginDefinition("O", "W", "o", "Typed Origin", "generic", provenance = "pack")))
            store.saveOrigin(PlayerOrigin("C", "P", "O", "PRIMARY", provenance = "typed"))

            val resolution = store.reconcileLegacy("P", "W").single()
            assertEquals("O", store.playerOrigins("P").single().originUid)
            assertTrue(!resolution.canonical)
            assertEquals("UNRESOLVED_NO_EXPLICIT_MAPPING", resolution.reason)
        }
    }

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: Throwable) { failed = true }
        assertTrue(failed)
    }
}
