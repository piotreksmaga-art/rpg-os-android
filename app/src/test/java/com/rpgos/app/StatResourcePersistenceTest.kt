package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatResourcePersistenceTest {
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        dbFile = File.createTempFile("rpgos-stat-resource-", ".db")
        if (dbFile.exists()) dbFile.delete()
    }

    @After
    fun tearDown() {
        dbFile.delete()
    }

    @Test
    fun migrationCreatesPhase4SchemaAndIsIdempotent() {
        open().use { db ->
            MigrationManager().ensureV4(db, "campaign-a")
            MigrationManager().ensureV4(db, "campaign-a")
            listOf("stat_definitions", "player_stats", "resource_definitions", "player_resources").forEach { table ->
                assertTrue(tableExists(db, table))
            }
            val count = db.rawQuery(
                "SELECT COUNT(*) FROM rpgos_schema_migrations WHERE migration_id='RPGOS-4.0-DYNAMIC-STATS-RESOURCES'",
                null
            ).use { c -> c.moveToFirst(); c.getInt(0) }
            assertEquals(1, count)
        }
    }

    @Test
    fun definitionsAndPlayerValuesPersistAcrossReopen() {
        open().use { db ->
            MigrationManager().ensureV4(db, "campaign-a")
            val store = StatResourceStore(db, "campaign-a")
            store.registerStatDefinitions("WORLD-A", listOf(stat("WORLD-A", "STAT-A", "focus")))
            store.registerResourceDefinitions("WORLD-A", listOf(resource("WORLD-A", "RES-A", "flux")))
            store.savePlayerStat(PlayerStat("campaign-a", "PLAYER-A", "STAT-A", 42.5, 3))
            store.savePlayerResource(PlayerResource("campaign-a", "PLAYER-A", "RES-A", 17.0, 4))
        }

        open().use { db ->
            val store = StatResourceStore(db, "campaign-a")
            assertEquals(42.5, store.playerStats("PLAYER-A").single().baseValue, 0.0)
            assertEquals(3L, store.playerStats("PLAYER-A").single().version)
            assertEquals(17.0, store.playerResources("PLAYER-A").single().currentValue, 0.0)
            assertEquals(4L, store.playerResources("PLAYER-A").single().version)
        }
    }

    @Test
    fun valuesAreIsolatedByCampaignAndCharacter() {
        open().use { db ->
            MigrationManager().ensureV4(db, "campaign-a")
            val definitions = StatResourceStore(db, "campaign-a")
            definitions.registerStatDefinitions("WORLD-A", listOf(stat("WORLD-A", "STAT-A", "focus")))
            definitions.registerResourceDefinitions("WORLD-A", listOf(resource("WORLD-A", "RES-A", "flux")))

            definitions.savePlayerStat(PlayerStat("campaign-a", "PLAYER-A", "STAT-A", 10.0))
            definitions.savePlayerStat(PlayerStat("campaign-a", "PLAYER-B", "STAT-A", 20.0))
            definitions.savePlayerResource(PlayerResource("campaign-a", "PLAYER-A", "RES-A", 30.0))

            MigrationManager().ensureV4(db, "campaign-b")
            val otherCampaign = StatResourceStore(db, "campaign-b")
            otherCampaign.savePlayerStat(PlayerStat("campaign-b", "PLAYER-A", "STAT-A", 99.0))
            otherCampaign.savePlayerResource(PlayerResource("campaign-b", "PLAYER-A", "RES-A", 77.0))

            assertEquals(10.0, definitions.playerStats("PLAYER-A").single().baseValue, 0.0)
            assertEquals(20.0, definitions.playerStats("PLAYER-B").single().baseValue, 0.0)
            assertEquals(30.0, definitions.playerResources("PLAYER-A").single().currentValue, 0.0)
            assertEquals(99.0, otherCampaign.playerStats("PLAYER-A").single().baseValue, 0.0)
            assertEquals(77.0, otherCampaign.playerResources("PLAYER-A").single().currentValue, 0.0)
        }
    }

    @Test
    fun migrationPreservesLegacyCharacterStatsUnchanged() {
        open().use { db ->
            db.execSQL("CREATE TABLE character_stats(entity_uid TEXT, stat_key TEXT, current_value REAL)")
            db.execSQL("INSERT INTO character_stats(entity_uid,stat_key,current_value) VALUES('PLAYER-A','legacy_metric',123.5)")

            MigrationManager().ensureV4(db, "campaign-a")

            db.rawQuery(
                "SELECT entity_uid,stat_key,current_value FROM character_stats",
                null
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("PLAYER-A", c.getString(0))
                assertEquals("legacy_metric", c.getString(1))
                assertEquals(123.5, c.getDouble(2), 0.0)
            }
        }
    }

    @Test
    fun sameKeyCanBelongToDifferentWorldPacksButUidCannotBeHijacked() {
        open().use { db ->
            MigrationManager().ensureV4(db, "campaign-a")
            val store = StatResourceStore(db, "campaign-a")
            store.registerStatDefinitions("WORLD-A", listOf(stat("WORLD-A", "STAT-A", "focus")))
            store.registerStatDefinitions("WORLD-B", listOf(stat("WORLD-B", "STAT-B", "focus")))
            assertEquals(1, store.statDefinitions("WORLD-A").size)
            assertEquals(1, store.statDefinitions("WORLD-B").size)

            try {
                store.registerStatDefinitions("WORLD-B", listOf(stat("WORLD-B", "STAT-A", "other")))
                fail("Expected World Pack UID ownership conflict")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    private fun stat(world: String, uid: String, key: String) = StatDefinition(
        statUid = uid,
        key = key,
        category = "generic",
        minValue = 0.0,
        worldPackUid = world
    )

    private fun resource(world: String, uid: String, key: String) = ResourceDefinition(
        resourceUid = uid,
        key = key,
        category = "generic",
        minValue = 0.0,
        worldPackUid = world
    )

    private fun open(): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(dbFile, null)

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(table)
        ).use { it.moveToFirst() }
}
