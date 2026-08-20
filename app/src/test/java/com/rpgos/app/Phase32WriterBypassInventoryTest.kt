package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Modifier

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase32WriterBypassInventoryTest {
    private lateinit var file: File

    private enum class WriterReachability {
        CANONICAL_TURN,
        ADMINISTRATIVE,
        PRESENTATION_ONLY,
        READ_ONLY_NON_AUTHORITATIVE,
        PROTECTED_PROJECTED_READ,
        GAMEPLAY_UNREACHABLE
    }

    /**
     * Executable inventory for the complete application-facing CampaignRepository surface.
     * Adding a future method without deliberately classifying it fails this test.
     */
    private val repositoryEntryPoints: Map<String, WriterReachability> = mapOf(
        "bootstrap" to WriterReachability.ADMINISTRATIVE,
        "activeCampaignRef" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,
        "activePlayerRef" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,
        "setActivePlayer" to WriterReachability.ADMINISTRATIVE,
        "protectedReads" to WriterReachability.PROTECTED_PROJECTED_READ,
        "statDefinitions" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,
        "resourceDefinitions" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,
        "registerStatDefinitions" to WriterReachability.ADMINISTRATIVE,
        "registerResourceDefinitions" to WriterReachability.ADMINISTRATIVE,
        "activeCampaignDirName" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,
        "activeWorldPackDirName" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,
        "setActiveCampaign" to WriterReachability.ADMINISTRATIVE,
        "setActiveWorldPack" to WriterReachability.ADMINISTRATIVE,
        "createCampaign" to WriterReachability.ADMINISTRATIVE,
        "commitTurn" to WriterReachability.CANONICAL_TURN,
        "buildContext" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,
        "fullCharacterPanel" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,
        "status" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,
        "time" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,
        "chronicle" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,
        "truthRecords" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,
        "canonDivergences" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,
        "npcs" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,
        "npcDetail" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,
        "relationEdges" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,
        "economies" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,
        "wars" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,
        "relationships" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,
        "organizations" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,
        "politics" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,
        "syncCheck" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,
        "dbTables" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,
        "diagnostics" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,
        "worldRegions" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,
        "worldLocations" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,
        "activeWorldEvents" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,
        "techniqueBrowser" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,
        "missionBrowser" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,
        "visualLibrary" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,
        "addVisual" to WriterReachability.PRESENTATION_ONLY,
        "packageManager" to WriterReachability.ADMINISTRATIVE,
        "backups" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,
        "restoreBackup" to WriterReachability.ADMINISTRATIVE,
        "createSnapshot" to WriterReachability.ADMINISTRATIVE,
        "snapshots" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,
        "restoreLatestSnapshot" to WriterReachability.ADMINISTRATIVE,
        "finalizeChapter" to WriterReachability.ADMINISTRATIVE
    )

    @Before
    fun setUp() {
        file = File.createTempFile("g32-writer-inventory-", ".db").also { it.delete() }
    }

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun everyCampaignRepositoryEntryPointHasExactlyOneWriterReachabilityClass() {
        val actual = CampaignRepository::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .map { it.name }
            .toSet()

        assertEquals(
            "CampaignRepository entry point added/removed without G32 writer classification",
            repositoryEntryPoints.keys,
            actual
        )
        assertEquals(1, repositoryEntryPoints.values.count { it == WriterReachability.CANONICAL_TURN })
        assertEquals(WriterReachability.CANONICAL_TURN, repositoryEntryPoints.getValue("commitTurn"))
        assertEquals(WriterReachability.PRESENTATION_ONLY, repositoryEntryPoints.getValue("addVisual"))

        assertFalse(actual.contains("applyPatch"))
    }

    @Test
    fun repositoryExposesNoWritableCampaignDatabaseHandle() {
        val sqliteReturning = CampaignRepository::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && it.returnType == SQLiteDatabase::class.java }
            .map { it.name }
            .toSet()
        assertTrue("normal CampaignRepository must expose no raw SQLiteDatabase handle", sqliteReturning.isEmpty())
        val publicNames = CampaignRepository::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .map { it.name }
            .toSet()
        assertFalse(publicNames.contains("openWorldDb"))
        assertFalse(publicNames.contains("openCoreDb"))
        assertFalse(publicNames.contains("playerState"))
        assertFalse(publicNames.contains("playerStats"))
        assertFalse(publicNames.contains("playerResources"))
        assertEquals(WriterReachability.PROTECTED_PROJECTED_READ, repositoryEntryPoints.getValue("protectedReads"))
        val protectedReadsMethod = CampaignRepository::class.java.declaredMethods.single { it.name == "protectedReads" }
        assertEquals(ProtectedCampaignReadRepository::class.java, protectedReadsMethod.returnType)
    }

    @Test
    fun everyExistingAuthoritativePersistentTableIsMechanicallyGuardedAgainstRawSqlWriters() {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            Phase32ProductionReadyTestFixture.setup(db, "C1")
            RuntimeTruthLayerRegistry.validateCanonicalInventory()

            val existingAuthority = RuntimeTruthLayerRegistry.authoritativePersistentTables()
                .filter { tableExists(db, it) }
                .toSortedSet()
            assertTrue(existingAuthority.isNotEmpty())

            existingAuthority.forEach { table ->
                val family = RuntimeTruthLayerRegistry.requireClassifiedTable(table)
                assertTrue("guarded table lost authoritative classification: $table", family.isAuthoritative)
                listOf("insert", "update", "delete").forEach { op ->
                    assertTrue(
                        "authoritative raw-SQL writer can escape because trigger is missing: $table/$op",
                        triggerExists(db, "rpgos_guard_${table}_$op")
                    )
                }
            }

            val actualGuardedTables = db.rawQuery(
                "SELECT DISTINCT tbl_name FROM sqlite_master WHERE type='trigger' AND name LIKE 'rpgos_guard_%' ORDER BY tbl_name",
                null
            ).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
            assertEquals(existingAuthority, actualGuardedTables)

            assertTrue(runCatching { RuntimeTruthLayerRegistry.requireClassifiedTable("future_authoritative_writer_target") }.isFailure)
        }
    }

    @Test
    fun administrativeIdentityWriterWorksOutsideGameplayButCannotBecomeInTurnBypass() {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            Phase32ProductionReadyTestFixture.setup(db, "C1")
            db.execSQL("CREATE TABLE IF NOT EXISTS character_stats(entity_uid TEXT,stat_key TEXT,current_value REAL)")
            db.execSQL("INSERT INTO character_stats(entity_uid,stat_key,current_value) VALUES('P2','focus',1.0)")
            db.execSQL("INSERT INTO character_stats(entity_uid,stat_key,current_value) VALUES('P1','focus',1.0)")

            assertEquals("P2", ActivePlayerStore(db, "C1").set("P2").playerUid)
            assertEquals("P2", ActivePlayerStore(db, "C1").requireActive().playerUid)

            val proposal = GroupATransactionTestFixtures.admittedFinancialProposal(
                campaignUid = "C1",
                commandUid = "CMD-G32-WRITER-INVENTORY",
                amountMinor = 1L
            )
            val failure = runCatching {
                TurnTransactionBoundary.create(
                    db,
                    TurnTransactionIdentity(
                        "C1",
                        "TURN-G32-WRITER-INVENTORY",
                        "CMD-G32-WRITER-INVENTORY",
                        "TX-G32-WRITER-INVENTORY"
                    ),
                    proposal,
                    failureInjector = TurnFailureInjector { point ->
                        if (point == TurnFailurePoint.AFTER_FIRST_WRITE) {
                            ActivePlayerStore(db, "C1").set("P1")
                        }
                    }
                ).commit()
            }.exceptionOrNull()

            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("RPGOS-G32:GAMEPLAY_CANNOT_INVOKE_ADMIN_AUTHORITY"))
            assertEquals("P2", ActivePlayerStore(db, "C1").requireActive().playerUid)
            assertEquals(100L, FinancialStore(db, "C1").balance("A"))
            assertEquals(0L, count(db, "turn_transaction_receipts"))
            assertEquals(0L, count(db, "canonical_gameplay_events"))
            assertEquals(0L, count(db, "canonical_causal_relations"))
        }
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.moveToFirst() }

    private fun triggerExists(db: SQLiteDatabase, trigger: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='trigger' AND name=?", arrayOf(trigger)).use { it.moveToFirst() }

    private fun count(db: SQLiteDatabase, table: String): Long =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { c -> c.moveToFirst(); c.getLong(0) }
}
