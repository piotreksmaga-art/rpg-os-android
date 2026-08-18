package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun oldLegacyStatsAreVisibleThroughTypedReadWithoutCopyingTruth() {
        open().use { db ->
            db.execSQL("CREATE TABLE character_stats(entity_uid TEXT, stat_key TEXT, current_value REAL)")
            db.execSQL("INSERT INTO character_stats VALUES('PLAYER-A','focus',12.5)")
            db.execSQL("INSERT INTO character_stats VALUES('PLAYER-A','forgotten_mod_stat',77.25)")
            db.execSQL("INSERT INTO character_stats VALUES('PLAYER-B','focus',99.0)")

            MigrationManager().ensureV4(db, "campaign-a")
            MigrationManager().ensureV4(db, "campaign-a")

            val store = StatResourceStore(db, "campaign-a")
            val definitions = store.statDefinitions(LegacyStatResourceCompatibility.WORLD_PACK_UID)
            assertEquals(setOf("focus", "forgotten_mod_stat"), definitions.map { it.key }.toSet())
            assertEquals(
                mapOf("focus" to 12.5, "forgotten_mod_stat" to 77.25),
                statValuesByKey(store, "PLAYER-A")
            )
            assertEquals(mapOf("focus" to 99.0), statValuesByKey(store, "PLAYER-B"))

            assertEquals(0, count(db, "stat_definitions"))
            assertEquals(0, count(db, "player_stats"))
            assertEquals(3, count(db, "character_stats"))
        }
    }

    @Test
    fun legacyStatIdentityAndValuesAreStableAcrossReopen() {
        var expectedDefinitions: List<StatDefinition> = emptyList()
        var expectedValues: List<PlayerStat> = emptyList()
        open().use { db ->
            db.execSQL("CREATE TABLE character_stats(entity_uid TEXT, stat_key TEXT, current_value REAL)")
            db.execSQL("INSERT INTO character_stats VALUES('PLAYER-A','custom.long_term_metric',123.125)")
            MigrationManager().ensureV4(db, "campaign-a")
            val store = StatResourceStore(db, "campaign-a")
            expectedDefinitions = store.statDefinitions(LegacyStatResourceCompatibility.WORLD_PACK_UID)
            expectedValues = store.playerStats("PLAYER-A")
        }

        open().use { db ->
            MigrationManager().ensureV4(db, "campaign-a")
            val store = StatResourceStore(db, "campaign-a")
            assertEquals(expectedDefinitions, store.statDefinitions(LegacyStatResourceCompatibility.WORLD_PACK_UID))
            assertEquals(expectedValues, store.playerStats("PLAYER-A"))
            assertEquals(1, count(db, "character_stats"))
            assertEquals(0, count(db, "player_stats"))
        }
    }

    @Test
    fun activePlayerIdentitySelectsLegacyTypedStatsWithoutFirstRowFallback() {
        open().use { db ->
            db.execSQL("CREATE TABLE character_stats(entity_uid TEXT, stat_key TEXT, current_value REAL)")
            db.execSQL("INSERT INTO character_stats VALUES('PLAYER-A','focus',10.0)")
            db.execSQL("INSERT INTO character_stats VALUES('PLAYER-B','focus',20.0)")
            MigrationManager().ensureV4(db, "campaign-a")

            val activeStore = ActivePlayerStore(db, "campaign-a")
            assertNull(activeStore.active())
            GameplayRuntimeBootstrap.initialize(db, "campaign-a")
            activeStore.set("PLAYER-A")
            var activeUid = activeStore.requireActive().playerUid
            assertEquals(mapOf("focus" to 10.0), statValuesByKey(StatResourceStore(db, "campaign-a"), activeUid))

            activeStore.set("PLAYER-B")
            activeUid = activeStore.requireActive().playerUid
            assertEquals(mapOf("focus" to 20.0), statValuesByKey(StatResourceStore(db, "campaign-a"), activeUid))
        }
    }

    @Test
    fun semanticallySafeLegacyResourcesAreVisibleWithoutPromotingDerivedFields() {
        open().use { db ->
            db.execSQL(
                """
                CREATE TABLE character_status_snapshot(
                    entity_uid TEXT,
                    current_aether REAL,
                    max_aether REAL,
                    current_void_flux REAL,
                    void_flux_max REAL,
                    resource_echo_current REAL,
                    regeneration_aether REAL,
                    effective_guard REAL,
                    fatigue REAL,
                    current_day REAL
                )
                """.trimIndent()
            )
            db.execSQL("INSERT INTO character_status_snapshot VALUES('PLAYER-A',50,100,7,9,3,4,8,2,123)")
            db.execSQL("INSERT INTO character_status_snapshot VALUES('PLAYER-B',20,100,6,9,4,4,8,1,124)")
            MigrationManager().ensureV4(db, "campaign-a")

            val store = StatResourceStore(db, "campaign-a")
            val definitions = store.resourceDefinitions(LegacyStatResourceCompatibility.WORLD_PACK_UID)
            assertEquals(setOf("aether", "void_flux", "echo"), definitions.map { it.key }.toSet())
            assertTrue(definitions.all { it.minValue == null && it.maxValue == null })
            assertEquals(
                mapOf("aether" to 50.0, "void_flux" to 7.0, "echo" to 3.0),
                resourceValuesByKey(store, "PLAYER-A")
            )
            assertEquals(
                mapOf("aether" to 20.0, "void_flux" to 6.0, "echo" to 4.0),
                resourceValuesByKey(store, "PLAYER-B")
            )

            assertFalse(definitions.any { it.key.contains("max", true) })
            assertFalse(definitions.any { it.key.contains("regeneration", true) })
            assertFalse(definitions.any { it.key.contains("effective", true) })
            assertEquals(0, count(db, "resource_definitions"))
            assertEquals(0, count(db, "player_resources"))
            assertEquals(2, count(db, "character_status_snapshot"))
        }
    }

    @Test
    fun legacyResourceCompatibilityIsStableAcrossReopen() {
        var expectedDefinitions: List<ResourceDefinition> = emptyList()
        var expectedValues: List<PlayerResource> = emptyList()
        open().use { db ->
            db.execSQL("CREATE TABLE character_status_snapshot(entity_uid TEXT,current_custom_pool REAL,max_custom_pool REAL)")
            db.execSQL("INSERT INTO character_status_snapshot VALUES('PLAYER-A',33.75,99.0)")
            MigrationManager().ensureV4(db, "campaign-a")
            val store = StatResourceStore(db, "campaign-a")
            expectedDefinitions = store.resourceDefinitions(LegacyStatResourceCompatibility.WORLD_PACK_UID)
            expectedValues = store.playerResources("PLAYER-A")
        }

        open().use { db ->
            MigrationManager().ensureV4(db, "campaign-a")
            val store = StatResourceStore(db, "campaign-a")
            assertEquals(expectedDefinitions, store.resourceDefinitions(LegacyStatResourceCompatibility.WORLD_PACK_UID))
            assertEquals(expectedValues, store.playerResources("PLAYER-A"))
            assertEquals(0, count(db, "player_resources"))
        }
    }

    @Test
    fun unscopedLegacyResourceSnapshotNeverGuessesAPlayer() {
        open().use { db ->
            db.execSQL("CREATE TABLE character_status_snapshot(current_resource_flux REAL)")
            db.execSQL("INSERT INTO character_status_snapshot VALUES(17.0)")
            db.execSQL("CREATE TABLE character_stats(entity_uid TEXT, stat_key TEXT, current_value REAL)")
            db.execSQL("INSERT INTO character_stats VALUES('PLAYER-A','focus',1.0)")
            db.execSQL("INSERT INTO character_stats VALUES('PLAYER-B','focus',2.0)")
            MigrationManager().ensureV4(db, "campaign-a")
            val store = StatResourceStore(db, "campaign-a")

            assertNull(ActivePlayerStore(db, "campaign-a").active())
            assertTrue(store.playerResources("PLAYER-A").isEmpty())
            assertTrue(store.playerResources("PLAYER-B").isEmpty())

            GameplayRuntimeBootstrap.initialize(db, "campaign-a")
            ActivePlayerStore(db, "campaign-a").set("PLAYER-B")
            assertTrue(store.playerResources("PLAYER-A").isEmpty())
            assertEquals(mapOf("flux" to 17.0), resourceValuesByKey(store, "PLAYER-B"))
        }
    }

    @Test
    fun legacyCompatibilityIsIsolatedAcrossPhysicalCampaignDatabases() {
        val otherFile = File.createTempFile("rpgos-stat-resource-other-", ".db")
        if (otherFile.exists()) otherFile.delete()
        try {
            open().use { dbA ->
                dbA.execSQL("CREATE TABLE character_stats(entity_uid TEXT, stat_key TEXT, current_value REAL)")
                dbA.execSQL("INSERT INTO character_stats VALUES('PLAYER-X','focus',11.0)")
                dbA.execSQL("CREATE TABLE character_status_snapshot(entity_uid TEXT,current_resource_flux REAL)")
                dbA.execSQL("INSERT INTO character_status_snapshot VALUES('PLAYER-X',3.0)")
                MigrationManager().ensureV4(dbA, "campaign-a")
                val storeA = StatResourceStore(dbA, "campaign-a")
                assertEquals(mapOf("focus" to 11.0), statValuesByKey(storeA, "PLAYER-X"))
                assertEquals(mapOf("flux" to 3.0), resourceValuesByKey(storeA, "PLAYER-X"))
            }
            SQLiteDatabase.openOrCreateDatabase(otherFile, null).use { dbB ->
                dbB.execSQL("CREATE TABLE character_stats(entity_uid TEXT, stat_key TEXT, current_value REAL)")
                dbB.execSQL("INSERT INTO character_stats VALUES('PLAYER-X','focus',88.0)")
                dbB.execSQL("CREATE TABLE character_status_snapshot(entity_uid TEXT,current_resource_flux REAL)")
                dbB.execSQL("INSERT INTO character_status_snapshot VALUES('PLAYER-X',9.0)")
                MigrationManager().ensureV4(dbB, "campaign-b")
                val storeB = StatResourceStore(dbB, "campaign-b")
                assertEquals(mapOf("focus" to 88.0), statValuesByKey(storeB, "PLAYER-X"))
                assertEquals(mapOf("flux" to 9.0), resourceValuesByKey(storeB, "PLAYER-X"))
            }
        } finally {
            otherFile.delete()
        }
    }

    @Test
    fun conflictingDuplicateLegacyRowsFailLoudlyInsteadOfChoosingAValue() {
        open().use { db ->
            db.execSQL("CREATE TABLE character_stats(entity_uid TEXT, stat_key TEXT, current_value REAL)")
            db.execSQL("INSERT INTO character_stats VALUES('PLAYER-A','focus',10.0)")
            db.execSQL("INSERT INTO character_stats VALUES('PLAYER-A','focus',11.0)")
            MigrationManager().ensureV4(db, "campaign-a")
            expectIllegalArgument { StatResourceStore(db, "campaign-a").playerStats("PLAYER-A") }
            assertEquals(2, count(db, "character_stats"))
            assertEquals(0, count(db, "player_stats"))
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
    fun migrationPreservesLegacyStatsAndResourceLikeStateBytesSemanticallyUnchanged() {
        open().use { db ->
            db.execSQL("CREATE TABLE character_stats(entity_uid TEXT, stat_key TEXT, current_value REAL)")
            db.execSQL("INSERT INTO character_stats VALUES('PLAYER-A','legacy_metric',123.5)")
            db.execSQL("CREATE TABLE character_status_snapshot(entity_uid TEXT,current_custom REAL,max_custom REAL)")
            db.execSQL("INSERT INTO character_status_snapshot VALUES('PLAYER-A',88.25,100.0)")

            MigrationManager().ensureV4(db, "campaign-a")

            db.rawQuery("SELECT entity_uid,stat_key,current_value FROM character_stats", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("PLAYER-A", c.getString(0))
                assertEquals("legacy_metric", c.getString(1))
                assertEquals(123.5, c.getDouble(2), 0.0)
            }
            db.rawQuery("SELECT entity_uid,current_custom,max_custom FROM character_status_snapshot", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("PLAYER-A", c.getString(0))
                assertEquals(88.25, c.getDouble(1), 0.0)
                assertEquals(100.0, c.getDouble(2), 0.0)
            }
        }
    }

    @Test
    fun identicalRegistrationIsIdempotentButConflictingUidMetadataFailsLoudly() {
        open().use { db ->
            MigrationManager().ensureV4(db, "campaign-a")
            val store = StatResourceStore(db, "campaign-a")
            val statDefinition = stat("WORLD-A", "STAT-A", "focus", max = 100.0)
            val resourceDefinition = resource("WORLD-A", "RES-A", "flux", max = 100.0)
            store.registerStatDefinitions("WORLD-A", listOf(statDefinition))
            store.registerStatDefinitions("WORLD-A", listOf(statDefinition))
            store.registerResourceDefinitions("WORLD-A", listOf(resourceDefinition))
            store.registerResourceDefinitions("WORLD-A", listOf(resourceDefinition))
            assertEquals(1, store.statDefinitions("WORLD-A").size)
            assertEquals(1, store.resourceDefinitions("WORLD-A").size)

            expectIllegalArgument {
                store.registerStatDefinitions("WORLD-A", listOf(statDefinition.copy(category = "different")))
            }
            expectIllegalArgument {
                store.registerResourceDefinitions("WORLD-A", listOf(resourceDefinition.copy(category = "different")))
            }
            assertEquals(statDefinition, store.statDefinitions("WORLD-A").single())
            assertEquals(resourceDefinition, store.resourceDefinitions("WORLD-A").single())
        }
    }

    @Test
    fun sameKeyCanBelongToDifferentWorldPacksButUidAndPackLocalKeyCannotBeHijacked() {
        open().use { db ->
            MigrationManager().ensureV4(db, "campaign-a")
            val store = StatResourceStore(db, "campaign-a")
            store.registerStatDefinitions("WORLD-A", listOf(stat("WORLD-A", "STAT-A", "focus")))
            store.registerStatDefinitions("WORLD-B", listOf(stat("WORLD-B", "STAT-B", "focus")))
            store.registerResourceDefinitions("WORLD-A", listOf(resource("WORLD-A", "RES-A", "flux")))
            assertEquals(1, store.statDefinitions("WORLD-A").size)
            assertEquals(1, store.statDefinitions("WORLD-B").size)

            expectIllegalArgument {
                store.registerStatDefinitions("WORLD-B", listOf(stat("WORLD-B", "STAT-A", "other")))
            }
            expectIllegalArgument {
                store.registerStatDefinitions("WORLD-A", listOf(stat("WORLD-A", "STAT-C", "focus")))
            }
            expectIllegalArgument {
                store.registerResourceDefinitions("WORLD-A", listOf(resource("WORLD-A", "RES-C", "flux")))
            }
        }
    }

    @Test
    fun reservedLegacyNamespaceCannotBeHijackedByWorldPackRegistration() {
        open().use { db ->
            MigrationManager().ensureV4(db, "campaign-a")
            val store = StatResourceStore(db, "campaign-a")
            expectIllegalArgument {
                store.registerStatDefinitions(
                    LegacyStatResourceCompatibility.WORLD_PACK_UID,
                    listOf(stat(LegacyStatResourceCompatibility.WORLD_PACK_UID, "S", "focus"))
                )
            }
            expectIllegalArgument {
                store.registerResourceDefinitions(
                    LegacyStatResourceCompatibility.WORLD_PACK_UID,
                    listOf(resource(LegacyStatResourceCompatibility.WORLD_PACK_UID, "R", "flux"))
                )
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
            expectIllegalArgument { store.savePlayerStat(PlayerStat("campaign-a", "PLAYER-A", "STAT-X", -1.0)) }
            expectIllegalArgument { store.savePlayerStat(PlayerStat("campaign-a", "PLAYER-A", "STAT-X", 11.0)) }
            expectIllegalArgument { store.savePlayerResource(PlayerResource("campaign-a", "PLAYER-A", "RES-X", -1.0)) }
            expectIllegalArgument { store.savePlayerResource(PlayerResource("campaign-a", "PLAYER-A", "RES-X", 21.0)) }

            assertTrue(store.playerStats("PLAYER-A").isEmpty())
            assertTrue(store.playerResources("PLAYER-A").isEmpty())
        }
    }

    @Test
    fun moreThanOneThousandDefinitionsAndValuesAreNotTruncated() {
        open().use { db ->
            MigrationManager().ensureV4(db, "campaign-a")
            val store = StatResourceStore(db, "campaign-a")
            val size = 1005
            val stats = (0 until size).map { stat("WORLD-LARGE", "STAT-$it", "stat_$it", max = 2000.0) }
            val resources = (0 until size).map { resource("WORLD-LARGE", "RES-$it", "resource_$it", max = 2000.0) }
            store.registerStatDefinitions("WORLD-LARGE", stats)
            store.registerResourceDefinitions("WORLD-LARGE", resources)
            db.beginTransaction()
            try {
                stats.forEachIndexed { index, definition ->
                    store.savePlayerStat(PlayerStat("campaign-a", "PLAYER-A", definition.statUid, index.toDouble()))
                }
                resources.forEachIndexed { index, definition ->
                    store.savePlayerResource(PlayerResource("campaign-a", "PLAYER-A", definition.resourceUid, index.toDouble()))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }

            assertEquals(size, store.statDefinitions("WORLD-LARGE").size)
            assertEquals(size, store.resourceDefinitions("WORLD-LARGE").size)
            assertEquals(size, store.playerStats("PLAYER-A").size)
            assertEquals(size, store.playerResources("PLAYER-A").size)
        }
    }

    @Test
    fun moreThanOneThousandLegacyStatsAreNotTruncated() {
        open().use { db ->
            db.execSQL("CREATE TABLE character_stats(entity_uid TEXT, stat_key TEXT, current_value REAL)")
            db.beginTransaction()
            try {
                repeat(1005) { index ->
                    db.execSQL(
                        "INSERT INTO character_stats VALUES(?,?,?)",
                        arrayOf<Any?>("PLAYER-A", "legacy_$index", index.toDouble())
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            MigrationManager().ensureV4(db, "campaign-a")
            val store = StatResourceStore(db, "campaign-a")
            assertEquals(1005, store.statDefinitions(LegacyStatResourceCompatibility.WORLD_PACK_UID).size)
            assertEquals(1005, store.playerStats("PLAYER-A").size)
        }
    }

    @Test
    fun ensureV4DoesNotDuplicateExistingDynamicOrCompatibilityData() {
        open().use { db ->
            db.execSQL("CREATE TABLE character_stats(entity_uid TEXT, stat_key TEXT, current_value REAL)")
            db.execSQL("INSERT INTO character_stats VALUES('PLAYER-A','legacy_metric',7.0)")
            MigrationManager().ensureV4(db, "campaign-a")
            val store = StatResourceStore(db, "campaign-a")
            store.registerStatDefinitions("WORLD-A", listOf(stat("WORLD-A", "STAT-A", "focus", max = 100.0)))
            store.registerResourceDefinitions("WORLD-A", listOf(resource("WORLD-A", "RES-A", "flux", max = 100.0)))
            store.savePlayerStat(PlayerStat("campaign-a", "PLAYER-A", "STAT-A", 12.0))
            store.savePlayerResource(PlayerResource("campaign-a", "PLAYER-A", "RES-A", 34.0))

            val beforeLegacyStats = store.playerStats("PLAYER-A")
            MigrationManager().ensureV4(db, "campaign-a")
            MigrationManager().ensureV4(db, "campaign-a")

            assertEquals(1, count(db, "stat_definitions"))
            assertEquals(1, count(db, "resource_definitions"))
            assertEquals(1, count(db, "player_stats"))
            assertEquals(1, count(db, "player_resources"))
            assertEquals(1, count(db, "character_stats"))
            assertEquals(beforeLegacyStats, store.playerStats("PLAYER-A"))
        }
    }

    @Test
    fun integrityAndForeignKeyChecksPassForMixedLegacyAndDynamicState() {
        open().use { db ->
            db.execSQL("CREATE TABLE character_stats(entity_uid TEXT, stat_key TEXT, current_value REAL)")
            db.execSQL("INSERT INTO character_stats VALUES('PLAYER-A','legacy_metric',7.5)")
            db.execSQL("CREATE TABLE character_status_snapshot(entity_uid TEXT,current_resource_flux REAL)")
            db.execSQL("INSERT INTO character_status_snapshot VALUES('PLAYER-A',4.5)")
            MigrationManager().ensureV4(db, "campaign-a")
            val store = StatResourceStore(db, "campaign-a")
            store.registerStatDefinitions("WORLD-A", listOf(stat("WORLD-A", "STAT-A", "focus", max = 100.0)))
            store.registerResourceDefinitions("WORLD-A", listOf(resource("WORLD-A", "RES-A", "resource", max = 100.0)))
            store.savePlayerStat(PlayerStat("campaign-a", "PLAYER-A", "STAT-A", 10.0))
            store.savePlayerResource(PlayerResource("campaign-a", "PLAYER-A", "RES-A", 20.0))

            assertEquals("ok", scalarString(db, "PRAGMA integrity_check"))
            db.rawQuery("PRAGMA foreign_key_check", null).use { c -> assertFalse(c.moveToFirst()) }
            expectIllegalArgument { store.savePlayerStat(PlayerStat("campaign-a", "PLAYER-A", "MISSING", 1.0)) }
        }
    }

    private fun statValuesByKey(store: StatResourceStore, playerUid: String): Map<String, Double> {
        val definitions = store.statDefinitions().associateBy { it.statUid }
        return store.playerStats(playerUid).associate { value ->
            definitions.getValue(value.statUid).key to value.baseValue
        }
    }

    private fun resourceValuesByKey(store: StatResourceStore, playerUid: String): Map<String, Double> {
        val definitions = store.resourceDefinitions().associateBy { it.resourceUid }
        return store.playerResources(playerUid).associate { value ->
            definitions.getValue(value.resourceUid).key to value.currentValue
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
        }
    }

    private fun count(db: SQLiteDatabase, table: String, where: String? = null): Int =
        db.rawQuery("SELECT COUNT(*) FROM $table${where?.let { " WHERE $it" } ?: ""}", null).use { c ->
            c.moveToFirst()
            c.getInt(0)
        }

    private fun scalarString(db: SQLiteDatabase, sql: String): String =
        db.rawQuery(sql, null).use { c ->
            require(c.moveToFirst()) { "Expected scalar result for $sql" }
            c.getString(0)
        }

    private fun open(): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(dbFile, null)

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(table)
        ).use { it.moveToFirst() }
}
