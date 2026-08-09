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
            assertEquals(1, count(db, "rpgos_schema_migrations", "migration_id='RPGOS-4.0-DYNAMIC-STATS-RESOURCES'"))
        }
    }

    @Test
    fun definitionsAndPlayerValuesPersistAcrossReopen() {
        val expectedStat = stat("WORLD-A", "STAT-A", "focus", max = 100.0)
        val expectedResource = resource("WORLD-A", "RES-A", "flux", max = 100.0)
        open().use { db ->
            MigrationManager().ensureV4(db, "campaign-a")
            val store = StatResourceStore(db, "campaign-a")
            store.registerStatDefinitions("WORLD-A", listOf(expectedStat))
            store.registerResourceDefinitions("WORLD-A", listOf(expectedResource))
            store.savePlayerStat(PlayerStat("campaign-a", "PLAYER-A", "STAT-A", 42.5, 3))
            store.savePlayerResource(PlayerResource("campaign-a", "PLAYER-A", "RES-A", 17.0, 4))
        }

        open().use { db ->
            val store = StatResourceStore(db, "campaign-a")
            assertEquals(expectedStat, store.statDefinitions("WORLD-A").single())
            assertEquals(expectedResource, store.resourceDefinitions("WORLD-A").single())
            assertEquals(PlayerStat("campaign-a", "PLAYER-A", "STAT-A", 42.5, 3), store.playerStats("PLAYER-A").single())
            assertEquals(PlayerResource("campaign-a", "PLAYER-A", "RES-A", 17.0, 4), store.playerResources("PLAYER-A").single())
        }
    }

    @Test
    fun valuesAreIsolatedByCampaignAndCharacter() {
        open().use { db ->
            MigrationManager().ensureV4(db, "campaign-a")
            val definitions = StatResourceStore(db, "campaign-a")
            definitions.registerStatDefinitions("WORLD-A", listOf(stat("WORLD-A", "STAT-A", "focus", max = 100.0)))
            definitions.registerResourceDefinitions("WORLD-A", listOf(resource("WORLD-A", "RES-A", "flux", max = 100.0)))

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
    fun migrationPreservesLegacyStatsAndResourceLikeStateUnchanged() {
        open().use { db ->
            db.execSQL("CREATE TABLE character_stats(entity_uid TEXT, stat_key TEXT, current_value REAL)")
            db.execSQL("INSERT INTO character_stats(entity_uid,stat_key,current_value) VALUES('PLAYER-A','legacy_metric',123.5)")
            db.execSQL("CREATE TABLE character_status_snapshot(entity_uid TEXT, legacy_energy REAL, legacy_hp REAL)")
            db.execSQL("INSERT INTO character_status_snapshot(entity_uid,legacy_energy,legacy_hp) VALUES('PLAYER-A',88.25,17.5)")

            MigrationManager().ensureV4(db, "campaign-a")

            db.rawQuery("SELECT entity_uid,stat_key,current_value FROM character_stats", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("PLAYER-A", c.getString(0))
                assertEquals("legacy_metric", c.getString(1))
                assertEquals(123.5, c.getDouble(2), 0.0)
            }
            db.rawQuery("SELECT entity_uid,legacy_energy,legacy_hp FROM character_status_snapshot", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("PLAYER-A", c.getString(0))
                assertEquals(88.25, c.getDouble(1), 0.0)
                assertEquals(17.5, c.getDouble(2), 0.0)
            }
        }
    }

    @Test
    fun identicalRegistrationIsIdempotentButConflictingUidMetadataFailsLoudly() {
        open().use { db ->
            MigrationManager().ensureV4(db, "campaign-a")
            val store = StatResourceStore(db, "campaign-a")
            val definition = stat("WORLD-A", "STAT-A", "focus", max = 100.0)
            store.registerStatDefinitions("WORLD-A", listOf(definition))
            store.registerStatDefinitions("WORLD-A", listOf(definition))
            assertEquals(1, store.statDefinitions("WORLD-A").size)

            expectIllegalArgument {
                store.registerStatDefinitions("WORLD-A", listOf(definition.copy(category = "different")))
            }
            assertEquals(definition, store.statDefinitions("WORLD-A").single())
        }
    }

    @Test
    fun sameKeyCanBelongToDifferentWorldPacksButUidAndPackLocalKeyCannotBeHijacked() {
        open().use { db ->
            MigrationManager().ensureV4(db, "campaign-a")
            val store = StatResourceStore(db, "campaign-a")
            store.registerStatDefinitions("WORLD-A", listOf(stat("WORLD-A", "STAT-A", "focus")))
            store.registerStatDefinitions("WORLD-B", listOf(stat("WORLD-B", "STAT-B", "focus")))
            assertEquals(1, store.statDefinitions("WORLD-A").size)
            assertEquals(1, store.statDefinitions("WORLD-B").size)

            expectIllegalArgument {
                store.registerStatDefinitions("WORLD-B", listOf(stat("WORLD-B", "STAT-A", "other")))
            }
            expectIllegalArgument {
                store.registerStatDefinitions("WORLD-A", listOf(stat("WORLD-A", "STAT-C", "focus")))
            }
        }
    }

    @Test
    fun unknownDefinitionAndOutOfBoundsValuesAreRejectedWithoutSilentWrite() {
        open().use { db ->
            MigrationManager().ensureV4(db, "campaign-a")
            val store = StatResourceStore(db, "campaign-a")
            store.registerStatDefinitions("WORLD-X", listOf(stat("WORLD-X", "STAT-X", "custom_stat", max = 10.0)))
            store.registerResourceDefinitions("WORLD-X", listOf(resource("WORLD-X", "RES-X", "custom_resource", max = 20.0)))

            expectIllegalArgument { store.savePlayerStat(PlayerStat("campaign-a", "PLAYER-A", "MISSING", 1.0)) }
            expectIllegalArgument { store.savePlayerResource(PlayerResource("campaign-a", "PLAYER-A", "MISSING", 1.0)) }
            expectIllegalArgument { store.savePlayerStat(PlayerStat("campaign-a", "PLAYER-A", "STAT-X", 11.0)) }
            expectIllegalArgument { store.savePlayerResource(PlayerResource("campaign-a", "PLAYER-A", "RES-X", -1.0)) }
            expectIllegalArgument { store.savePlayerResource(PlayerResource("campaign-a", "PLAYER-A", "RES-X", 21.0)) }

            assertTrue(store.playerStats("PLAYER-A").isEmpty())
            assertTrue(store.playerResources("PLAYER-A").isEmpty())
        }
    }

    @Test
    fun moreThanOneHundredDefinitionsAndValuesAreNotTruncated() {
        open().use { db ->
            MigrationManager().ensureV4(db, "campaign-a")
            val store = StatResourceStore(db, "campaign-a")
            val stats = (0 until 120).map { stat("WORLD-LARGE", "STAT-$it", "stat_$it", max = 1000.0) }
            val resources = (0 until 120).map { resource("WORLD-LARGE", "RES-$it", "resource_$it", max = 1000.0) }
            store.registerStatDefinitions("WORLD-LARGE", stats)
            store.registerResourceDefinitions("WORLD-LARGE", resources)
            stats.forEachIndexed { index, definition ->
                store.savePlayerStat(PlayerStat("campaign-a", "PLAYER-A", definition.statUid, index.toDouble()))
            }
            resources.forEachIndexed { index, definition ->
                store.savePlayerResource(PlayerResource("campaign-a", "PLAYER-A", definition.resourceUid, index.toDouble()))
            }

            assertEquals(120, store.statDefinitions("WORLD-LARGE").size)
            assertEquals(120, store.resourceDefinitions("WORLD-LARGE").size)
            assertEquals(120, store.playerStats("PLAYER-A").size)
            assertEquals(120, store.playerResources("PLAYER-A").size)
        }
    }

    @Test
    fun ensureV4DoesNotDuplicateExistingDynamicData() {
        open().use { db ->
            MigrationManager().ensureV4(db, "campaign-a")
            val store = StatResourceStore(db, "campaign-a")
            store.registerStatDefinitions("WORLD-A", listOf(stat("WORLD-A", "STAT-A", "focus", max = 100.0)))
            store.registerResourceDefinitions("WORLD-A", listOf(resource("WORLD-A", "RES-A", "flux", max = 100.0)))
            store.savePlayerStat(PlayerStat("campaign-a", "PLAYER-A", "STAT-A", 12.0))
            store.savePlayerResource(PlayerResource("campaign-a", "PLAYER-A", "RES-A", 34.0))

            MigrationManager().ensureV4(db, "campaign-a")
            MigrationManager().ensureV4(db, "campaign-a")

            assertEquals(1, count(db, "stat_definitions"))
            assertEquals(1, count(db, "resource_definitions"))
            assertEquals(1, count(db, "player_stats"))
            assertEquals(1, count(db, "player_resources"))
            assertEquals(12.0, store.playerStats("PLAYER-A").single().baseValue, 0.0)
            assertEquals(34.0, store.playerResources("PLAYER-A").single().currentValue, 0.0)
        }
    }

    private fun stat(world: String, uid: String, key: String, max: Double? = null) = StatDefinition(
        statUid = uid,
        key = key,
        category = "generic",
        minValue = 0.0,
        maxValue = max,
        worldPackUid = world
    )

    private fun resource(world: String, uid: String, key: String, max: Double? = null) = ResourceDefinition(
        resourceUid = uid,
        key = key,
        category = "generic",
        minValue = 0.0,
        maxValue = max,
        worldPackUid = world
    )

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    private fun count(db: SQLiteDatabase, table: String, where: String? = null): Int =
        db.rawQuery("SELECT COUNT(*) FROM $table${where?.let { " WHERE $it" } ?: ""}", null).use { c ->
            c.moveToFirst()
            c.getInt(0)
        }

    private fun open(): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(dbFile, null)

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(table)
        ).use { it.moveToFirst() }
}
