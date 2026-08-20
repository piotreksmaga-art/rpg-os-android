package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase32ContextCanonicalDomainsTest {
    private lateinit var root: File
    private lateinit var saveFile: File
    private lateinit var worldFile: File

    @Before
    fun setUp() {
        root = File(
            System.getProperty("java.io.tmpdir"),
            "rpgos-g32-context-domains-${System.nanoTime()}/saves/C.campaign"
        )
        root.mkdirs()
        File(root, "campaign.json").writeText("{\"id\":\"C\"}")
        saveFile = File(root, "campaign.db")
        worldFile = File(root, "world.db")
    }

    @After
    fun tearDown() {
        root.parentFile?.parentFile?.deleteRecursively()
    }

    @Test
    fun productionContextReadsRepresentativeCanonicalDomainsAndCannotReverseWriteThem() {
        SQLiteDatabase.openOrCreateDatabase(saveFile, null).use { save ->
            SQLiteDatabase.openOrCreateDatabase(worldFile, null).use { world ->
                Phase32ProductionReadyTestFixture.setup(save, "C")

                val asset = OwnedAssetRef("G32-CONTEXT-ASSET-KIND", "G32-CONTEXT-ASSET")
                withAdministrativeMutationAuthority(save, "C") {
                    save.execSQL(
                        "INSERT OR REPLACE INTO active_player_ref(campaign_id,player_uid,updated_at) VALUES('C','P1',1)"
                    )
                    CampaignTruthStore(save, "C").record(
                        kind = TruthKind.FACT,
                        predicate = "g32.context.domain.fact",
                        objectValue = "canonical",
                        subjectUid = "P1",
                        provenance = Provenance(ProvenanceSourceType.MANUAL_IMPORT, sourceId = "G32-CONTEXT"),
                        truthUid = "TRUTH-G32-CONTEXT-DOMAINS"
                    )

                    val stats = StatResourceStore(save, "C")
                    stats.registerStatDefinitions(
                        "G32-CONTEXT-WP",
                        listOf(
                            StatDefinition(
                                "STAT-G32-CONTEXT",
                                "g32_context_stat",
                                "CORE",
                                minValue = 0.0,
                                maxValue = 100.0,
                                worldPackUid = "G32-CONTEXT-WP"
                            )
                        )
                    )
                    stats.registerResourceDefinitions(
                        "G32-CONTEXT-WP",
                        listOf(
                            ResourceDefinition(
                                "RES-G32-CONTEXT",
                                "g32_context_resource",
                                "CORE",
                                minValue = 0.0,
                                maxValue = 100.0,
                                worldPackUid = "G32-CONTEXT-WP"
                            )
                        )
                    )
                    stats.savePlayerStat(PlayerStat("C", "P1", "STAT-G32-CONTEXT", 12.0))
                    stats.savePlayerResource(PlayerResource("C", "P1", "RES-G32-CONTEXT", 34.0))

                    val inventory = InventoryStore(save, "C")
                    inventory.registerDefinitions(
                        "G32-CONTEXT-WP",
                        listOf(
                            ItemDefinition(
                                itemDefinitionUid = "ITEMDEF-G32-CONTEXT",
                                worldPackUid = "G32-CONTEXT-WP",
                                key = "g32_context_item",
                                displayName = "G32 Context Item",
                                category = "TEST",
                                storagePolicy = ItemStoragePolicy.STACKABLE,
                                provenance = "G32-CONTEXT"
                            )
                        )
                    )
                    inventory.addStack("P1", "ITEMDEF-G32-CONTEXT", 3L, "G32-CONTEXT")

                    val refs = OwnershipReferenceRegistry(save, "C")
                    refs.registerAssetKind(asset.assetKindUid, "G32-CONTEXT")
                    refs.registerAsset(asset, "G32-CONTEXT")
                    refs.registerOwner(OwnershipOwnerRef("CHARACTER", "P2"), "G32-CONTEXT")
                    OwnershipStore(save, "C").acquire(
                        OwnershipRecord(
                            campaignId = "C",
                            ownershipRecordUid = "OWN-G32-CONTEXT",
                            owner = OwnershipOwnerRef("CHARACTER", "P1"),
                            asset = asset,
                            ownershipTypeUid = "OWNER",
                            share = OwnershipShare.full(),
                            validFrom = 1L,
                            provenance = "G32-CONTEXT"
                        )
                    )

                    DevelopmentProjectStore(save, "C").createProject(
                        DevelopmentProject(
                            campaignId = "C",
                            projectUid = "PROJECT-G32-CONTEXT",
                            projectTypeUid = PROJECT_TYPE_RESEARCH,
                            initiator = OwnershipOwnerRef("CHARACTER", "P1"),
                            beneficiary = OwnershipOwnerRef("CHARACTER", "P2"),
                            title = "G32 context project",
                            objectiveSummary = "Canonical project projection",
                            targetDomainUid = "RESEARCH",
                            progressCapUnits = 10L,
                            createdOrder = 1L,
                            provenance = "G32-CONTEXT"
                        ),
                        "PROJECT-G32-CONTEXT-IDEA"
                    )
                }

                val truthBefore = CampaignTruthStore(save, "C").activeForContext()
                val statBefore = scalarDouble(save, "SELECT base_value FROM player_stats WHERE campaign_id='C' AND character_uid='P1' AND stat_uid='STAT-G32-CONTEXT'")
                val resourceBefore = scalarDouble(save, "SELECT current_value FROM player_resources WHERE campaign_id='C' AND character_uid='P1' AND resource_uid='RES-G32-CONTEXT'")
                val inventoryBefore = scalarLong(save, "SELECT quantity FROM player_inventory_stacks WHERE campaign_id='C' AND character_uid='P1' AND item_definition_uid='ITEMDEF-G32-CONTEXT'")
                val ownershipBefore = OwnershipStore(save, "C").history(asset)
                val projectBefore = DevelopmentProjectStore(save, "C").project("PROJECT-G32-CONTEXT")
                val ledgerBefore = tableCount(save, "financial_ledger_transactions")

                val bundle = run { Phase38LegacyContextFixtureSchema.ensure(save, world); ContextBuilder(save,world).build("inspect canonical domains",1,VisibilityAudienceFactory.diagnostic("C"),PurposeContext("C",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)) }
                assertTrue(bundle.campaignTruth.any { it["truth_uid"] == "TRUTH-G32-CONTEXT-DOMAINS" && it["object_value"] == "canonical" })
                assertTrue(bundle.playerState.isNotEmpty())
                assertEquals("P1", (bundle.playerState["active_player"] as Map<*, *>)["player_uid"])

                @Suppress("UNCHECKED_CAST")
                val finance = bundle.playerStatus["finance_ledger"] as Map<String, Any?>
                @Suppress("UNCHECKED_CAST")
                val accounts = finance["accounts"] as List<Map<String, Any?>>
                assertTrue(accounts.any { it["account_uid"] == "A" && it["balance_minor"] == 100L })
                assertTrue(bundle.playerInventory.any { it["item_definition_uid"] == "ITEMDEF-G32-CONTEXT" && it["quantity"] == 3L })

                @Suppress("UNCHECKED_CAST")
                val typedStats = bundle.playerStatus["typed_stats"] as List<Map<String, Any?>>
                @Suppress("UNCHECKED_CAST")
                val typedResources = bundle.playerStatus["typed_resources"] as List<Map<String, Any?>>
                @Suppress("UNCHECKED_CAST")
                val ownership = bundle.playerStatus["ownership"] as List<Map<String, Any?>>
                @Suppress("UNCHECKED_CAST")
                val projects = bundle.playerStatus["projects"] as List<Map<String, Any?>>
                assertTrue(typedStats.any { it["stat_uid"] == "STAT-G32-CONTEXT" && it["base_value"] == 12.0 })
                assertTrue(typedResources.any { it["resource_uid"] == "RES-G32-CONTEXT" && it["current_value"] == 34.0 })
                assertTrue(ownership.any { it["asset_uid"] == "G32-CONTEXT-ASSET" && it["owner_uid"] == "P1" })
                assertTrue(projects.any { it["project_uid"] == "PROJECT-G32-CONTEXT" && it["title"] == "G32 context project" })

                val contradictory = bundle.copy(
                    campaignTruth = listOf(mapOf("truth_uid" to "FORGED", "truth_kind" to "FACT", "object_value" to "forged", "created_at" to Long.MAX_VALUE)),
                    playerStatus = bundle.playerStatus + mapOf(
                        "typed_stats" to listOf(mapOf("stat_uid" to "STAT-G32-CONTEXT", "base_value" to Double.MAX_VALUE)),
                        "typed_resources" to listOf(mapOf("resource_uid" to "RES-G32-CONTEXT", "current_value" to Double.MAX_VALUE)),
                        "ownership" to listOf(mapOf("asset_uid" to "G32-CONTEXT-ASSET", "owner_uid" to "ATTACKER")),
                        "projects" to listOf(mapOf("project_uid" to "PROJECT-G32-CONTEXT", "title" to "FORGED")),
                        "finance_ledger" to mapOf("accounts" to listOf(mapOf("account_uid" to "A", "balance_minor" to Long.MAX_VALUE)))
                    ),
                    playerInventory = listOf(mapOf("item_definition_uid" to "ITEMDEF-G32-CONTEXT", "quantity" to Long.MAX_VALUE))
                )
                assertFalse(contradictory == bundle)

                SQLiteDatabase.create(null).use { core ->
                    val rejected = StatePatchEngine(save, SourceOfTruthRegistry(core)).apply(
                        StatePatch(
                            transactionId = "G32-CONTEXT-REVERSE-WRITE",
                            operations = listOf(
                                PatchOperation("update", "campaign_truth_records", mapOf("campaign_id" to "C"), mapOf("object_value" to "forged")),
                                PatchOperation("update", "player_stats", mapOf("campaign_id" to "C"), mapOf("base_value" to Double.MAX_VALUE)),
                                PatchOperation("update", "player_resources", mapOf("campaign_id" to "C"), mapOf("current_value" to Double.MAX_VALUE)),
                                PatchOperation("update", "player_inventory_stacks", mapOf("campaign_id" to "C"), mapOf("quantity" to Long.MAX_VALUE)),
                                PatchOperation("update", "ownership_records", mapOf("campaign_id" to "C"), mapOf("owner_uid" to "ATTACKER")),
                                PatchOperation("update", "development_projects", mapOf("campaign_id" to "C"), mapOf("title" to "FORGED")),
                                PatchOperation("update", "financial_account_balances", mapOf("campaign_id" to "C"), mapOf("balance_minor" to Long.MAX_VALUE))
                            )
                        )
                    )
                    assertFalse(rejected.success)
                    assertEquals(0, rejected.appliedOperations)
                }

                assertEquals(truthBefore, CampaignTruthStore(save, "C").activeForContext())
                assertEquals(statBefore, scalarDouble(save, "SELECT base_value FROM player_stats WHERE campaign_id='C' AND character_uid='P1' AND stat_uid='STAT-G32-CONTEXT'"), 0.0)
                assertEquals(resourceBefore, scalarDouble(save, "SELECT current_value FROM player_resources WHERE campaign_id='C' AND character_uid='P1' AND resource_uid='RES-G32-CONTEXT'"), 0.0)
                assertEquals(inventoryBefore, scalarLong(save, "SELECT quantity FROM player_inventory_stacks WHERE campaign_id='C' AND character_uid='P1' AND item_definition_uid='ITEMDEF-G32-CONTEXT'"))
                assertEquals(ownershipBefore, OwnershipStore(save, "C").history(asset))
                assertEquals(projectBefore, DevelopmentProjectStore(save, "C").project("PROJECT-G32-CONTEXT"))
                assertEquals(ledgerBefore, tableCount(save, "financial_ledger_transactions"))

                val rebuilt = run { Phase38LegacyContextFixtureSchema.ensure(save, world); ContextBuilder(save,world).build("rebuild canonical domains",2,VisibilityAudienceFactory.diagnostic("C"),PurposeContext("C",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)) }
                assertTrue(rebuilt.campaignTruth.any { it["truth_uid"] == "TRUTH-G32-CONTEXT-DOMAINS" && it["object_value"] == "canonical" })
                @Suppress("UNCHECKED_CAST")
                val rebuiltOwnership = rebuilt.playerStatus["ownership"] as List<Map<String, Any?>>
                assertTrue(rebuiltOwnership.any { it["asset_uid"] == "G32-CONTEXT-ASSET" && it["owner_uid"] == "P1" })
            }
        }
    }

    private fun scalarLong(db: SQLiteDatabase, sql: String): Long = db.rawQuery(sql, null).use { c -> c.moveToFirst(); c.getLong(0) }
    private fun scalarDouble(db: SQLiteDatabase, sql: String): Double = db.rawQuery(sql, null).use { c -> c.moveToFirst(); c.getDouble(0) }
    private fun tableCount(db: SQLiteDatabase, table: String): Long = scalarLong(db, "SELECT COUNT(*) FROM $table")
}
