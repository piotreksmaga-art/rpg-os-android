package com.rpgos.app

import android.database.Cursor
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
class Phase32EvidenceNonAuthorityTest {
    private lateinit var file: File

    @Before
    fun setUp() {
        file = File.createTempFile("g32-evidence-nonauthority-", ".db").also { it.delete() }
    }

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun eventAndCausalProductionWritersLeaveRepresentativeDomainAuthorityByteSemanticsUnchanged() {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            Phase32ProductionReadyTestFixture.setup(db, "C1")
            seedRepresentativeAuthority(db)

            val guardedTables = listOf(
                "campaign_truth_records",
                "financial_accounts",
                "financial_ledger_transactions",
                "ownership_records",
                "ownership_operations",
                "player_stats",
                "player_resources",
                "player_inventory_stacks",
                "development_projects",
                "project_status_history"
            )
            guardedTables.forEach { table ->
                assertTrue("representative authority is empty: $table", rowCount(db, table) > 0L || table == "ownership_operations")
            }
            val before = guardedTables.associateWith { canonicalTableDump(db, it) }

            val identity = TurnTransactionIdentity(
                "C1", "TURN-G32-EVIDENCE-ONLY", "CMD-G32-EVIDENCE-ONLY", "TX-G32-EVIDENCE-ONLY"
            )
            val proposal = evidenceOnlySourceProposal("CMD-G32-EVIDENCE-ONLY")

            db.beginTransaction()
            try {
                GameplayMutationDatabaseGuards.enterTurn(db, "C1")
                try {
                    CampaignEventStore(db, "C1").appendRequired(identity, proposal.playerChangeSet)
                    val events = db.rawQuery(
                        "SELECT event_uid FROM canonical_gameplay_events WHERE campaign_uid='C1' AND transaction_uid=? ORDER BY event_intent_uid",
                        arrayOf(identity.transactionUid)
                    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
                    assertEquals(2, events.size)

                    CampaignCausalGraph(db, "C1").appendRequired(
                        identity,
                        listOf(
                            CanonicalCausalRelationIntent(
                                relationIntentUid = "REL-G32-EVIDENCE-ONLY",
                                relationClass = CausalRelationClass.PROVENANCE,
                                relationKindUid = CausalRelationKinds.PROVENANCE_OF,
                                sourceEventUid = events[0],
                                targetEventUid = events[1],
                                provenanceEventUids = listOf(events[0])
                            )
                        )
                    )
                } finally {
                    GameplayMutationDatabaseGuards.leaveTurn(db, "C1")
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }

            assertEquals(2L, rowCount(db, "canonical_gameplay_events"))
            assertEquals(1L, rowCount(db, "canonical_causal_relations"))
            assertEquals(before, guardedTables.associateWith { canonicalTableDump(db, it) })

            // After evidence writing ends, direct domain writes are still rejected by the same
            // registry-backed production guards. Evidence history does not confer authority.
            guardedTables.filter { RuntimeTruthLayerRegistry.requireClassifiedTable(it).isAuthoritative }.forEach { table ->
                val campaignColumn = GameplayMutationDatabaseGuards.campaignColumnForCompatibility(db, table)
                assertTrue(
                    "direct authoritative delete unexpectedly allowed after evidence append: $table",
                    runCatching { db.delete(table, "$campaignColumn=?", arrayOf("C1")) }.isFailure
                )
            }
            assertEquals(before, guardedTables.associateWith { canonicalTableDump(db, it) })
        }
    }

    private fun seedRepresentativeAuthority(db: SQLiteDatabase) {
        withAdministrativeMutationAuthority(db, "C1") {
            CampaignTruthStore(db, "C1").record(
                kind = TruthKind.FACT,
                predicate = "g32.non.authority",
                objectValue = "canonical",
                subjectUid = "P1",
                provenance = Provenance(ProvenanceSourceType.MANUAL_IMPORT, sourceId = "G32"),
                truthUid = "TRUTH-G32-NON-AUTHORITY"
            )

            val stats = StatResourceStore(db, "C1")
            stats.registerStatDefinitions(
                "G32-WP",
                listOf(StatDefinition("STAT-G32", "g32_stat", "CORE", minValue = 0.0, maxValue = 100.0, worldPackUid = "G32-WP"))
            )
            stats.registerResourceDefinitions(
                "G32-WP",
                listOf(ResourceDefinition("RES-G32", "g32_resource", "CORE", minValue = 0.0, maxValue = 100.0, worldPackUid = "G32-WP"))
            )
            stats.savePlayerStat(PlayerStat("C1", "P1", "STAT-G32", 12.0))
            stats.savePlayerResource(PlayerResource("C1", "P1", "RES-G32", 34.0))

            val inventory = InventoryStore(db, "C1")
            inventory.registerDefinitions(
                "G32-WP",
                listOf(
                    ItemDefinition(
                        itemDefinitionUid = "ITEMDEF-G32",
                        worldPackUid = "G32-WP",
                        key = "g32_item",
                        displayName = "G32 item",
                        category = "TEST",
                        storagePolicy = ItemStoragePolicy.STACKABLE,
                        provenance = "G32"
                    )
                )
            )
            inventory.addStack("P1", "ITEMDEF-G32", 2L, "G32")

            val asset = OwnedAssetRef("G32-ASSET-KIND", "G32-ASSET")
            val refs = OwnershipReferenceRegistry(db, "C1")
            refs.registerAssetKind(asset.assetKindUid, "G32")
            refs.registerAsset(asset, "G32")
            refs.registerOwner(OwnershipOwnerRef("CHARACTER", "P2"), "G32")
            OwnershipStore(db, "C1").acquire(
                OwnershipRecord(
                    campaignId = "C1",
                    ownershipRecordUid = "OWN-G32-NON-AUTHORITY",
                    owner = OwnershipOwnerRef("CHARACTER", "P1"),
                    asset = asset,
                    ownershipTypeUid = "OWNER",
                    share = OwnershipShare.full(),
                    validFrom = 1L,
                    provenance = "G32"
                )
            )

            DevelopmentProjectStore(db, "C1").createProject(
                DevelopmentProject(
                    campaignId = "C1",
                    projectUid = "PROJECT-G32",
                    projectTypeUid = PROJECT_TYPE_RESEARCH,
                    initiator = OwnershipOwnerRef("CHARACTER", "P1"),
                    beneficiary = OwnershipOwnerRef("CHARACTER", "P2"),
                    title = "G32 project",
                    objectiveSummary = "Evidence writers must not mutate this project",
                    targetDomainUid = "RESEARCH",
                    progressCapUnits = 10L,
                    createdOrder = 1L,
                    provenance = "G32"
                ),
                "PROJECT-G32-IDEA"
            )
        }
    }

    private fun evidenceOnlySourceProposal(commandUid: String): CanonicalCampaignMutationProposal {
        val actor = CommandActorRef("PLAYER", "P1")
        val command = PlayerCommand(
            commandUid = commandUid,
            campaignUid = "C1",
            actor = actor,
            commandKindUid = PlayerCommandKinds.TRANSFER_FUNDS,
            payload = TransferFundsCommandPayload("A", "B", 1L, "CUR"),
            provenance = CommandProvenance("G32-EVIDENCE-NON-AUTHORITY"),
            requestedEffectiveOrder = 10L
        )
        val refs = setOf(
            CampaignScopedDomainRef("C1", DomainRef("PLAYER", "P1")),
            CampaignScopedDomainRef("C1", DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "A")),
            CampaignScopedDomainRef("C1", DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "B")),
            CampaignScopedDomainRef("C1", DomainRef(PlayerResolutionReferenceKinds.CURRENCY, "CUR"))
        )
        val context = PlayerResolutionContext.createUnboundGeneric("C1", actor, refs)
        val engine = PlayerDomainEngine(
            PlayerResolutionComponentRegistry.of(listOf(EvidenceOnlyFinancialComponent()))
        )
        return when (val admitted = CampaignMutationBoundary.resolveAndAdmit("C1", engine, command, context)) {
            is CampaignMutationAdmission.Accepted -> admitted.proposal
            is CampaignMutationAdmission.Rejected -> error(admitted.reasonUid)
        }
    }

    private class EvidenceOnlyFinancialComponent : PlayerResolutionComponent<TransferFundsCommandPayload>(
        PlayerCommandKinds.TRANSFER_FUNDS,
        TransferFundsCommandPayload::class,
        "RPGOS-COMPONENT:G32-EVIDENCE-NON-AUTHORITY",
        "1"
    ) {
        override fun resolve(
            command: PlayerCommand<TransferFundsCommandPayload>,
            context: PlayerResolutionContext
        ): PlayerResolutionComponentOutcome {
            val changeUid = "CHANGE-${command.commandUid}"
            val subject = DomainRef("PLAYER", "P1")
            val change = PlayerDomainChange.create(
                changeUid,
                PlayerChangeKinds.FINANCIAL,
                FinancialChange("A", "B", 1L, "CUR", "RPGOS-FIN-TYPE:TRANSFER")
            )
            val events = listOf("ONE", "TWO").map { suffix ->
                PlayerEventIntent.create(
                    eventIntentUid = "EVENT-${command.commandUid}-$suffix",
                    eventKindUid = PlayerEventIntentKinds.DOMAIN_EFFECT,
                    actorRef = subject,
                    targetRefs = listOf(subject),
                    causalChangeUids = listOf(changeUid),
                    payload = DomainEffectEventIntentPayload(subject, "RPGOS-EFFECT:G32-EVIDENCE-$suffix")
                )
            }
            return PlayerResolutionComponentOutcome.Resolved(
                PlayerResolutionDraft.create(changes = listOf(change), eventIntents = events)
            )
        }
    }

    private fun canonicalTableDump(db: SQLiteDatabase, table: String): List<String> =
        db.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add((0 until c.columnCount).joinToString("\u001f") { index -> cursorValue(c, index) })
                }
            }
        }

    private fun cursorValue(c: Cursor, index: Int): String = when (c.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> "<NULL>"
        Cursor.FIELD_TYPE_BLOB -> c.getBlob(index).joinToString("") { "%02x".format(it) }
        else -> c.getString(index)
    }

    private fun rowCount(db: SQLiteDatabase, table: String): Long =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { c -> c.moveToFirst(); c.getLong(0) }
}
