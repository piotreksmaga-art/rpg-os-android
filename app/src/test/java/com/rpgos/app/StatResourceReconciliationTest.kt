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
class StatResourceReconciliationTest {
    private lateinit var dbFile: File

    @Before fun setUp() {
        dbFile = File.createTempFile("rpgos-reconcile-", ".db")
        dbFile.delete()
    }

    @After fun tearDown() { dbFile.delete() }

    @Test fun legacyOnlyStatRemainsVisible() = open().use { db ->
        legacyStats(db, "PLAYER-A" to Pair("strength", 10.0))
        MigrationManager().ensureV4(db, "campaign-a")
        val store = StatResourceStore(db, "campaign-a")
        val stat = store.playerStats("PLAYER-A").single()
        assertEquals(LegacyCompatibilityIdentity.statUidForKey("strength"), stat.statUid)
        assertEquals(10.0, stat.baseValue, 0.0)
    }

    @Test fun typedOnlyStatRemainsVisible() = open().use { db ->
        MigrationManager().ensureV4(db, "campaign-a")
        val store = StatResourceStore(db, "campaign-a")
        val def = stat("WORLD-A", "WORLD-A-STRENGTH", "strength")
        store.registerStatDefinitions("WORLD-A", listOf(def))
        store.savePlayerStat(PlayerStat("campaign-a", "PLAYER-A", def.statUid, 12.0))
        assertEquals(PlayerStat("campaign-a", "PLAYER-A", def.statUid, 12.0), store.playerStats("PLAYER-A").single())
    }

    @Test fun mixedStatWithoutMappingFailsLoud() = open().use { db ->
        legacyStats(db, "PLAYER-A" to Pair("strength", 10.0))
        MigrationManager().ensureV4(db, "campaign-a")
        val store = StatResourceStore(db, "campaign-a")
        val def = stat("WORLD-A", "WORLD-A-STRENGTH", "strength")
        store.registerStatDefinitions("WORLD-A", listOf(def))
        store.savePlayerStat(PlayerStat("campaign-a", "PLAYER-A", def.statUid, 12.0))
        expectIllegalState { store.statDefinitions() }
        expectIllegalState { store.playerStats("PLAYER-A") }
    }

    @Test fun mappedStatUsesExactlyOneCanonicalTypedIdentityAndPreservesLegacyBytes() = open().use { db ->
        legacyStats(db, "PLAYER-A" to Pair("strength", 10.0), "PLAYER-A" to Pair("luck", 7.0))
        MigrationManager().ensureV4(db, "campaign-a")
        val store = StatResourceStore(db, "campaign-a")
        val def = stat("WORLD-A", "WORLD-A-STRENGTH", "strength")
        store.registerStatDefinitions("WORLD-A", listOf(def))
        store.savePlayerStat(PlayerStat("campaign-a", "PLAYER-A", def.statUid, 12.0))
        store.registerLegacyStatAlias(statAlias("campaign-a", "strength", def))

        val values = store.playerStats("PLAYER-A")
        assertEquals(2, values.size)
        assertEquals(1, values.count { it.statUid == def.statUid })
        assertEquals(12.0, values.single { it.statUid == def.statUid }.baseValue, 0.0)
        assertTrue(values.any { it.statUid == LegacyCompatibilityIdentity.statUidForKey("luck") })
        assertEquals(10.0, scalarDouble(db, "SELECT current_value FROM character_stats WHERE entity_uid='PLAYER-A' AND stat_key='strength'"), 0.0)
    }

    @Test fun mappedStatProjectsLegacyValueToCanonicalUidWhenTypedValueDoesNotYetExist() = open().use { db ->
        legacyStats(db, "PLAYER-A" to Pair("strength", 10.0))
        MigrationManager().ensureV4(db, "campaign-a")
        val store = StatResourceStore(db, "campaign-a")
        val def = stat("WORLD-A", "WORLD-A-STRENGTH", "strength")
        store.registerStatDefinitions("WORLD-A", listOf(def))
        store.registerLegacyStatAlias(statAlias("campaign-a", "strength", def))
        assertEquals(PlayerStat("campaign-a", "PLAYER-A", def.statUid, 10.0), store.playerStats("PLAYER-A").single())
    }

    @Test fun sameTextKeyAcrossWorldPacksIsNeverAutomaticallyMerged() = open().use { db ->
        legacyStats(db, "PLAYER-A" to Pair("strength", 10.0))
        MigrationManager().ensureV4(db, "campaign-a")
        val store = StatResourceStore(db, "campaign-a")
        val a = stat("WORLD-A", "A-STRENGTH", "strength")
        val b = stat("WORLD-B", "B-STRENGTH", "strength")
        store.registerStatDefinitions("WORLD-A", listOf(a))
        store.registerStatDefinitions("WORLD-B", listOf(b))
        expectIllegalState { store.statDefinitions() }
        store.registerLegacyStatAlias(statAlias("campaign-a", "strength", a))
        val definitions = store.statDefinitions()
        assertTrue(definitions.any { it.statUid == a.statUid })
        assertTrue(definitions.any { it.statUid == b.statUid })
        assertTrue(definitions.none { it.statUid == LegacyCompatibilityIdentity.statUidForKey("strength") })
    }

    @Test fun statAliasPersistsAcrossReopenAndEnsureV4IsIdempotent() {
        val def = stat("WORLD-A", "WORLD-A-STRENGTH", "strength")
        open().use { db ->
            legacyStats(db, "PLAYER-A" to Pair("strength", 10.0))
            MigrationManager().ensureV4(db, "campaign-a")
            val store = StatResourceStore(db, "campaign-a")
            store.registerStatDefinitions("WORLD-A", listOf(def))
            store.registerLegacyStatAlias(statAlias("campaign-a", "strength", def))
            MigrationManager().ensureV4(db, "campaign-a")
            MigrationManager().ensureV4(db, "campaign-a")
            assertEquals(1, count(db, "legacy_stat_aliases"))
        }
        open().use { db ->
            MigrationManager().ensureV4(db, "campaign-a")
            assertEquals(def.statUid, StatResourceStore(db, "campaign-a").playerStats("PLAYER-A").single().statUid)
            assertEquals(1, count(db, "legacy_stat_aliases"))
        }
    }

    @Test fun statAliasCannotBeHijackedOrRetargeted() = open().use { db ->
        legacyStats(db, "PLAYER-A" to Pair("strength", 10.0))
        MigrationManager().ensureV4(db, "campaign-a")
        val store = StatResourceStore(db, "campaign-a")
        val a = stat("WORLD-A", "A-STRENGTH", "strength")
        val b = stat("WORLD-B", "B-POWER", "power")
        store.registerStatDefinitions("WORLD-A", listOf(a))
        store.registerStatDefinitions("WORLD-B", listOf(b))
        expectIllegalArgument { store.registerLegacyStatAlias(statAlias("campaign-a", "strength", a).copy(worldPackUid = "WORLD-B")) }
        store.registerLegacyStatAlias(statAlias("campaign-a", "strength", a))
        expectIllegalArgument { store.registerLegacyStatAlias(statAlias("campaign-a", "strength", b)) }
        assertEquals(1, count(db, "legacy_stat_aliases"))
    }

    @Test fun legacyOnlyAndTypedOnlyResourceRemainVisible() = open().use { db ->
        legacyResources(db, "PLAYER-A", "current_resource_aether" to 30.0)
        MigrationManager().ensureV4(db, "campaign-a")
        val store = StatResourceStore(db, "campaign-a")
        assertEquals(LegacyCompatibilityIdentity.resourceUidForKey("aether"), store.playerResources("PLAYER-A").single().resourceUid)
        db.execSQL("DELETE FROM character_status_snapshot")
        val typed = resource("WORLD-A", "WORLD-A-FLUX", "flux")
        store.registerResourceDefinitions("WORLD-A", listOf(typed))
        store.savePlayerResource(PlayerResource("campaign-a", "PLAYER-A", typed.resourceUid, 11.0))
        assertEquals(typed.resourceUid, store.playerResources("PLAYER-A").single().resourceUid)
    }

    @Test fun resourceOnlyMixedAndMappedSemanticsMirrorStats() = open().use { db ->
        legacyResources(db, "PLAYER-A", "current_resource_aether" to 30.0, "max_aether" to 100.0)
        MigrationManager().ensureV4(db, "campaign-a")
        val store = StatResourceStore(db, "campaign-a")
        assertEquals(LegacyCompatibilityIdentity.resourceUidForKey("aether"), store.playerResources("PLAYER-A").single().resourceUid)
        val def = resource("WORLD-A", "WORLD-A-AETHER", "aether")
        store.registerResourceDefinitions("WORLD-A", listOf(def))
        expectIllegalState { store.playerResources("PLAYER-A") }
        store.registerLegacyResourceAlias(resourceAlias("campaign-a", "aether", def))
        val projected = store.playerResources("PLAYER-A").single()
        assertEquals(def.resourceUid, projected.resourceUid)
        assertEquals(30.0, projected.currentValue, 0.0)
        assertEquals(30.0, scalarDouble(db, "SELECT current_resource_aether FROM character_status_snapshot WHERE entity_uid='PLAYER-A'"), 0.0)
    }

    @Test fun typedResourceValueSupersedesMappedLegacyAndUnrelatedLegacyRemainsVisible() = open().use { db ->
        legacyResources(db, "PLAYER-A", "current_resource_aether" to 30.0, "max_aether" to 100.0, "current_resource_echo" to 8.0)
        MigrationManager().ensureV4(db, "campaign-a")
        val store = StatResourceStore(db, "campaign-a")
        val def = resource("WORLD-A", "WORLD-A-AETHER", "aether")
        store.registerResourceDefinitions("WORLD-A", listOf(def))
        store.savePlayerResource(PlayerResource("campaign-a", "PLAYER-A", def.resourceUid, 45.0))
        store.registerLegacyResourceAlias(resourceAlias("campaign-a", "aether", def))
        val values = store.playerResources("PLAYER-A")
        assertEquals(2, values.size)
        assertEquals(45.0, values.single { it.resourceUid == def.resourceUid }.currentValue, 0.0)
        assertTrue(values.any { it.resourceUid == LegacyCompatibilityIdentity.resourceUidForKey("echo") })
    }

    @Test fun sameResourceKeyAcrossWorldPacksIsNeverAutomaticallyMerged() = open().use { db ->
        legacyResources(db, "PLAYER-A", "current_resource_flux" to 7.0)
        MigrationManager().ensureV4(db, "campaign-a")
        val store = StatResourceStore(db, "campaign-a")
        val a = resource("WORLD-A", "A-FLUX", "flux")
        val b = resource("WORLD-B", "B-FLUX", "flux")
        store.registerResourceDefinitions("WORLD-A", listOf(a))
        store.registerResourceDefinitions("WORLD-B", listOf(b))
        expectIllegalState { store.resourceDefinitions() }
        store.registerLegacyResourceAlias(resourceAlias("campaign-a", "flux", a))
        val definitions = store.resourceDefinitions()
        assertTrue(definitions.any { it.resourceUid == a.resourceUid })
        assertTrue(definitions.any { it.resourceUid == b.resourceUid })
        assertTrue(definitions.none { it.resourceUid == LegacyCompatibilityIdentity.resourceUidForKey("flux") })
    }

    @Test fun resourceAliasPersistsAndCannotBeHijackedOrRetargeted() {
        val def = resource("WORLD-A", "WORLD-A-AETHER", "aether")
        open().use { db ->
            legacyResources(db, "PLAYER-A", "current_resource_aether" to 30.0)
            MigrationManager().ensureV4(db, "campaign-a")
            val store = StatResourceStore(db, "campaign-a")
            val other = resource("WORLD-B", "WORLD-B-ECHO", "echo")
            store.registerResourceDefinitions("WORLD-A", listOf(def))
            store.registerResourceDefinitions("WORLD-B", listOf(other))
            expectIllegalArgument { store.registerLegacyResourceAlias(resourceAlias("campaign-a", "aether", def).copy(worldPackUid = "WORLD-B")) }
            store.registerLegacyResourceAlias(resourceAlias("campaign-a", "aether", def))
            expectIllegalArgument { store.registerLegacyResourceAlias(resourceAlias("campaign-a", "aether", other)) }
            MigrationManager().ensureV4(db, "campaign-a")
            assertEquals(1, count(db, "legacy_resource_aliases"))
        }
        open().use { db ->
            MigrationManager().ensureV4(db, "campaign-a")
            assertEquals(def.resourceUid, StatResourceStore(db, "campaign-a").playerResources("PLAYER-A").single().resourceUid)
        }
    }

    @Test fun aliasesApplyToActiveAndNonActivePlayersWithoutChangingActivePlayerRef() = open().use { db ->
        legacyStats(db, "PLAYER-A" to Pair("strength", 10.0), "PLAYER-B" to Pair("strength", 20.0))
        MigrationManager().ensureV4(db, "campaign-a")
        val store = StatResourceStore(db, "campaign-a")
        val def = stat("WORLD-A", "WORLD-A-STRENGTH", "strength")
        store.registerStatDefinitions("WORLD-A", listOf(def))
        store.registerLegacyStatAlias(statAlias("campaign-a", "strength", def))
        GameplayRuntimeBootstrap.initialize(db, "campaign-a")
        ActivePlayerStore(db, "campaign-a").set("PLAYER-A")
        assertEquals(10.0, store.playerStats("PLAYER-A").single().baseValue, 0.0)
        assertEquals(20.0, store.playerStats("PLAYER-B").single().baseValue, 0.0)
        assertEquals("PLAYER-A", ActivePlayerStore(db, "campaign-a").active()!!.playerUid)
    }

    @Test fun aliasIsCampaignScoped() = open().use { db ->
        legacyStats(db, "PLAYER-A" to Pair("strength", 10.0))
        MigrationManager().ensureV4(db, "campaign-a")
        MigrationManager().ensureV4(db, "campaign-b")
        val aStore = StatResourceStore(db, "campaign-a")
        val bStore = StatResourceStore(db, "campaign-b")
        val def = stat("WORLD-A", "WORLD-A-STRENGTH", "strength")
        aStore.registerStatDefinitions("WORLD-A", listOf(def))
        aStore.registerLegacyStatAlias(statAlias("campaign-a", "strength", def))
        assertEquals(def.statUid, aStore.playerStats("PLAYER-A").single().statUid)
        expectIllegalState { bStore.playerStats("PLAYER-A") }
    }

    @Test fun thousandUnmappedLegacyValuesSurviveAlongsideOneMappedValue() = open().use { db ->
        db.execSQL("CREATE TABLE character_stats(entity_uid TEXT, stat_key TEXT, current_value REAL)")
        db.beginTransaction()
        try {
            for (i in 0 until 1000) db.execSQL("INSERT INTO character_stats VALUES('PLAYER-A',?,?)", arrayOf("legacy_$i", i.toDouble()))
            db.execSQL("INSERT INTO character_stats VALUES('PLAYER-A','strength',10.0)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        MigrationManager().ensureV4(db, "campaign-a")
        val store = StatResourceStore(db, "campaign-a")
        val def = stat("WORLD-A", "WORLD-A-STRENGTH", "strength", max = 2000.0)
        store.registerStatDefinitions("WORLD-A", listOf(def))
        store.registerLegacyStatAlias(statAlias("campaign-a", "strength", def))
        assertEquals(1001, store.playerStats("PLAYER-A").size)
        assertEquals(1000, store.playerStats("PLAYER-A").count { LegacyCompatibilityIdentity.isLegacyStatUid(it.statUid) })
    }

    @Test fun migrationAndAliasesPassSqliteIntegrityAndForeignKeyChecks() = open().use { db ->
        db.execSQL("PRAGMA foreign_keys=ON")
        legacyStats(db, "PLAYER-A" to Pair("strength", 10.0))
        legacyResources(db, "PLAYER-A", "current_resource_aether" to 30.0)
        MigrationManager().ensureV4(db, "campaign-a")
        val store = StatResourceStore(db, "campaign-a")
        val s = stat("WORLD-A", "S", "strength")
        val r = resource("WORLD-A", "R", "aether")
        store.registerStatDefinitions("WORLD-A", listOf(s))
        store.registerResourceDefinitions("WORLD-A", listOf(r))
        store.registerLegacyStatAlias(statAlias("campaign-a", "strength", s))
        store.registerLegacyResourceAlias(resourceAlias("campaign-a", "aether", r))
        assertEquals("ok", scalarString(db, "PRAGMA integrity_check"))
        assertEquals(0, queryRowCount(db, "PRAGMA foreign_key_check"))
    }

    private fun stat(world: String, uid: String, key: String, max: Double? = 100.0) = StatDefinition(uid, key, "generic", minValue = 0.0, maxValue = max, worldPackUid = world)
    private fun resource(world: String, uid: String, key: String, max: Double? = 100.0) = ResourceDefinition(uid, key, "generic", minValue = 0.0, maxValue = max, worldPackUid = world)
    private fun statAlias(campaign: String, key: String, def: StatDefinition) = LegacyStatAlias(campaign, LegacyCompatibilityIdentity.statUidForKey(key), def.statUid, def.worldPackUid, 1, "test-worldpack-mapping-v1")
    private fun resourceAlias(campaign: String, key: String, def: ResourceDefinition) = LegacyResourceAlias(campaign, LegacyCompatibilityIdentity.resourceUidForKey(key), def.resourceUid, def.worldPackUid, 1, "test-worldpack-mapping-v1")

    private fun legacyStats(db: SQLiteDatabase, vararg values: Pair<String, Pair<String, Double>>) {
        db.execSQL("CREATE TABLE IF NOT EXISTS character_stats(entity_uid TEXT, stat_key TEXT, current_value REAL)")
        values.forEach { (player, kv) -> db.execSQL("INSERT INTO character_stats VALUES(?,?,?)", arrayOf(player, kv.first, kv.second)) }
    }

    private fun legacyResources(db: SQLiteDatabase, player: String, vararg columns: Pair<String, Double>) {
        val defs = columns.joinToString(",") { "${it.first} REAL" }
        db.execSQL("CREATE TABLE character_status_snapshot(entity_uid TEXT,$defs)")
        val names = columns.joinToString(",") { it.first }
        val placeholders = List(columns.size + 1) { "?" }.joinToString(",")
        val args = (listOf<Any?>(player) + columns.map { it.second as Any? }).toTypedArray()
        db.execSQL("INSERT INTO character_status_snapshot(entity_uid,$names) VALUES($placeholders)", args)
    }

    private fun expectIllegalState(block: () -> Unit) {
        try { block(); fail("Expected IllegalStateException") } catch (_: IllegalStateException) {}
    }

    private fun expectIllegalArgument(block: () -> Unit) {
        try { block(); fail("Expected IllegalArgumentException") } catch (_: IllegalArgumentException) {}
    }

    private fun count(db: SQLiteDatabase, table: String): Int = db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }
    private fun scalarDouble(db: SQLiteDatabase, sql: String): Double = db.rawQuery(sql, null).use { it.moveToFirst(); it.getDouble(0) }
    private fun scalarString(db: SQLiteDatabase, sql: String): String = db.rawQuery(sql, null).use { it.moveToFirst(); it.getString(0) }
    private fun queryRowCount(db: SQLiteDatabase, sql: String): Int = db.rawQuery(sql, null).use { c -> var n = 0; while (c.moveToNext()) n++; n }
    private fun open(): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
}
