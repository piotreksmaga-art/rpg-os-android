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
class Phase32TruthTypeEndToEndTest {
    private lateinit var root: File
    private lateinit var saveFile: File
    private lateinit var worldFile: File

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "rpgos-g32-truth-e2e-${System.nanoTime()}")
        val campaignDir = File(root, "saves/C1.campaign").apply { mkdirs() }
        File(campaignDir, "campaign.json").writeText("{\"id\":\"C1\"}")
        saveFile = File(campaignDir, "campaign.db")
        worldFile = File(root, "world.db")
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun factBeliefNarrativeRemainTypedThroughEventReferencesCausalRelationsAndFinalProjections() {
        SQLiteDatabase.openOrCreateDatabase(saveFile, null).use { db ->
            Phase32ProductionReadyTestFixture.setup(db, "C1")
            val truthStore = CampaignTruthStore(db, "C1")
            withAdministrativeMutationAuthority(db, "C1") {
                truthStore.record(
                    kind = TruthKind.FACT,
                    predicate = "g32.fact",
                    objectValue = "fact-value",
                    subjectUid = "P1",
                    provenance = Provenance(ProvenanceSourceType.MANUAL_IMPORT, sourceId = "G32"),
                    truthUid = "TRUTH-G32-FACT"
                )
                truthStore.record(
                    kind = TruthKind.BELIEF,
                    predicate = "g32.belief",
                    objectValue = "belief-value",
                    subjectUid = "P1",
                    perspectiveUid = "P1",
                    provenance = Provenance(ProvenanceSourceType.MANUAL_IMPORT, sourceId = "G32"),
                    truthUid = "TRUTH-G32-BELIEF"
                )
                truthStore.record(
                    kind = TruthKind.NARRATIVE,
                    predicate = "g32.narrative",
                    subjectUid = "P1",
                    narrativeText = "narrative-value",
                    provenance = Provenance(ProvenanceSourceType.MANUAL_IMPORT, sourceId = "G32"),
                    truthUid = "TRUTH-G32-NARRATIVE"
                )
            }

            val expectedTypes = mapOf(
                "TRUTH-G32-FACT" to TruthKind.FACT,
                "TRUTH-G32-BELIEF" to TruthKind.BELIEF,
                "TRUTH-G32-NARRATIVE" to TruthKind.NARRATIVE
            )
            assertEquals(expectedTypes, truthStore.active().associate { it.truthUid to it.kind })

            val eventIdentity = TurnTransactionIdentity("C1", "TURN-G32-TRUTH-EVENTS", "CMD-G32-TRUTH-EVENTS", "TX-G32-TRUTH-EVENTS")
            val eventProposal = truthReferencingProposal("CMD-G32-TRUTH-EVENTS", expectedTypes.keys.toList())
            assertTrue(TurnTransactionBoundary.create(db, eventIdentity, eventProposal).commit() is TurnExecutionResult.Committed)

            val eventsByTruth = db.rawQuery(
                "SELECT subject_ref_uid,event_uid FROM canonical_gameplay_events WHERE campaign_uid='C1' AND transaction_uid=?",
                arrayOf(eventIdentity.transactionUid)
            ).use { c ->
                buildMap {
                    while (c.moveToNext()) put(c.getString(0), c.getString(1))
                }
            }
            assertEquals(expectedTypes.keys, eventsByTruth.keys)
            assertEquals(expectedTypes, truthStore.active().associate { it.truthUid to it.kind })

            val causalIdentity = TurnTransactionIdentity("C1", "TURN-G32-TRUTH-CAUSAL", "CMD-G32-TRUTH-CAUSAL", "TX-G32-TRUTH-CAUSAL")
            val relations = listOf(
                CanonicalCausalRelationIntent(
                    relationIntentUid = "G32-TRUTH-EVIDENCE",
                    relationClass = CausalRelationClass.EVIDENCE,
                    relationKindUid = CausalRelationKinds.EVIDENCE_FOR,
                    sourceEventUid = eventsByTruth.getValue("TRUTH-G32-FACT"),
                    targetEventUid = eventsByTruth.getValue("TRUTH-G32-BELIEF"),
                    evidenceEventUids = listOf(eventsByTruth.getValue("TRUTH-G32-FACT"))
                ),
                CanonicalCausalRelationIntent(
                    relationIntentUid = "G32-TRUTH-PROVENANCE",
                    relationClass = CausalRelationClass.PROVENANCE,
                    relationKindUid = CausalRelationKinds.PROVENANCE_OF,
                    sourceEventUid = eventsByTruth.getValue("TRUTH-G32-BELIEF"),
                    targetEventUid = eventsByTruth.getValue("TRUTH-G32-NARRATIVE"),
                    provenanceEventUids = listOf(eventsByTruth.getValue("TRUTH-G32-BELIEF"))
                ),
                CanonicalCausalRelationIntent(
                    relationIntentUid = "G32-TRUTH-NARRATIVE",
                    relationClass = CausalRelationClass.NARRATIVE,
                    relationKindUid = CausalRelationKinds.NARRATIVE_ASSOCIATION,
                    sourceEventUid = eventsByTruth.getValue("TRUTH-G32-NARRATIVE"),
                    targetEventUid = eventsByTruth.getValue("TRUTH-G32-FACT")
                )
            )
            assertTrue(
                TurnTransactionBoundary.create(
                    db,
                    causalIdentity,
                    GroupATransactionTestFixtures.admittedFinancialProposal(
                        campaignUid = "C1",
                        commandUid = "CMD-G32-TRUTH-CAUSAL",
                        amountMinor = 1L
                    ),
                    causalRelationIntents = relations
                ).commit() is TurnExecutionResult.Committed
            )

            val storedRelationTypes = db.rawQuery(
                "SELECT relation_class_uid,relation_kind_uid FROM canonical_causal_relations WHERE campaign_uid='C1' AND transaction_uid=?",
                arrayOf(causalIdentity.transactionUid)
            ).use { c ->
                buildSet {
                    while (c.moveToNext()) add(c.getString(0) to c.getString(1))
                }
            }
            assertEquals(
                setOf(
                    "EVIDENCE" to CausalRelationKinds.EVIDENCE_FOR,
                    "PROVENANCE" to CausalRelationKinds.PROVENANCE_OF,
                    "NARRATIVE" to CausalRelationKinds.NARRATIVE_ASSOCIATION
                ),
                storedRelationTypes
            )
            assertEquals(expectedTypes, truthStore.active().associate { it.truthUid to it.kind })

            SQLiteDatabase.openOrCreateDatabase(worldFile, null).use { world ->
                val contextTruth = ContextBuilder(db, world).build("inspect truth", 1).campaignTruth
                    .associate { it.getValue("truth_uid") as String to TruthKind.valueOf(it.getValue("truth_kind") as String) }
                assertEquals(expectedTypes, contextTruth)
            }

            val gmSnapshot = PlayerSnapshotBuilder.build(
                TruthProjectionReadSource(truthStore),
                "C1",
                "P1",
                PlayerSnapshotProfile.GM_CONTEXT
            )
            assertEquals(
                mapOf(
                    "TRUTH-G32-FACT" to PlayerTruthClass.FACT,
                    "TRUTH-G32-BELIEF" to PlayerTruthClass.BELIEF,
                    "TRUTH-G32-NARRATIVE" to PlayerTruthClass.NARRATIVE
                ),
                gmSnapshot.truthViews.associate { it.truthUid to it.truthClass }
            )

            val graph = CampaignCausalGraph(db, "C1")
            val firstEvent = eventsByTruth.getValue("TRUTH-G32-FACT")
            val secondEvent = eventsByTruth.getValue("TRUTH-G32-BELIEF")
            assertTrue(
                runCatching {
                    graph.validate(
                        listOf(
                            CanonicalCausalRelationIntent(
                                "G32-FORGED-TEMPORAL-CAUSE",
                                CausalRelationClass.CAUSAL,
                                CausalRelationKinds.BEFORE,
                                firstEvent,
                                secondEvent,
                                evidenceEventUids = listOf(firstEvent)
                            )
                        )
                    )
                }.isFailure
            )
            assertTrue(
                runCatching {
                    graph.validate(
                        listOf(
                            CanonicalCausalRelationIntent(
                                "G32-FORGED-RETRIEVAL-CAUSE",
                                CausalRelationClass.CAUSAL,
                                CausalRelationKinds.RETRIEVED_WITH,
                                firstEvent,
                                secondEvent,
                                evidenceEventUids = listOf(firstEvent)
                            )
                        )
                    )
                }.isFailure
            )
            assertEquals(expectedTypes, truthStore.active().associate { it.truthUid to it.kind })
        }
    }

    private fun truthReferencingProposal(commandUid: String, truthUids: List<String>): CanonicalCampaignMutationProposal {
        val actor = CommandActorRef("PLAYER", "P1")
        val command = PlayerCommand(
            commandUid = commandUid,
            campaignUid = "C1",
            actor = actor,
            commandKindUid = PlayerCommandKinds.TRANSFER_FUNDS,
            payload = TransferFundsCommandPayload("A", "B", 1L, "CUR"),
            provenance = CommandProvenance("G32-TRUTH-TYPE-E2E"),
            requestedEffectiveOrder = 10L
        )
        val refs = linkedSetOf(
            CampaignScopedDomainRef("C1", DomainRef("PLAYER", "P1")),
            CampaignScopedDomainRef("C1", DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "A")),
            CampaignScopedDomainRef("C1", DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "B")),
            CampaignScopedDomainRef("C1", DomainRef(PlayerResolutionReferenceKinds.CURRENCY, "CUR"))
        )
        truthUids.forEach { refs += CampaignScopedDomainRef("C1", DomainRef("CAMPAIGN_TRUTH", it)) }
        val context = PlayerResolutionContext.createUnboundGeneric("C1", actor, refs)
        val engine = PlayerDomainEngine(
            PlayerResolutionComponentRegistry.of(
                listOf(TruthReferencingFinancialComponent(truthUids.joinToString("\u001f")))
            )
        )
        return when (val admission = CampaignMutationBoundary.resolveAndAdmit("C1", engine, command, context)) {
            is CampaignMutationAdmission.Accepted -> admission.proposal
            is CampaignMutationAdmission.Rejected -> error("canonical admission rejected: ${admission.reasonUid}")
        }
    }

    private class TruthReferencingFinancialComponent(
        private val truthUidPayload: String
    ) : PlayerResolutionComponent<TransferFundsCommandPayload>(
        PlayerCommandKinds.TRANSFER_FUNDS,
        TransferFundsCommandPayload::class,
        "RPGOS-COMPONENT:G32-TRUTH-E2E",
        "1"
    ) {
        override fun resolve(
            command: PlayerCommand<TransferFundsCommandPayload>,
            context: PlayerResolutionContext
        ): PlayerResolutionComponentOutcome {
            val changeUid = "CHANGE-${command.commandUid}"
            val change = PlayerDomainChange.create(
                changeUid,
                PlayerChangeKinds.FINANCIAL,
                FinancialChange(
                    command.payload.fromAccountUid,
                    command.payload.toAccountUid,
                    command.payload.amountMinor,
                    command.payload.currencyUid,
                    "RPGOS-FIN-TYPE:TRANSFER"
                )
            )
            val events = truthUidPayload.split('\u001f').filter { it.isNotBlank() }.map { truthUid ->
                val truthRef = DomainRef("CAMPAIGN_TRUTH", truthUid)
                PlayerEventIntent.create(
                    eventIntentUid = "EVENT-$truthUid",
                    eventKindUid = PlayerEventIntentKinds.DOMAIN_EFFECT,
                    actorRef = DomainRef("PLAYER", "P1"),
                    targetRefs = listOf(truthRef),
                    causalChangeUids = listOf(changeUid),
                    payload = DomainEffectEventIntentPayload(truthRef, "RPGOS-EFFECT:TRUTH-REFERENCE")
                )
            }
            return PlayerResolutionComponentOutcome.Resolved(
                PlayerResolutionDraft.create(changes = listOf(change), eventIntents = events)
            )
        }
    }

    private class TruthProjectionReadSource(
        private val store: CampaignTruthStore
    ) : PlayerSnapshotReadSource {
        override fun truthViews(campaignUid: String, characterUid: String) = store.active().map {
            PlayerTruthView(
                truthUid = it.truthUid,
                truthClass = PlayerTruthClass.valueOf(it.kind.name),
                subjectUid = it.subjectUid ?: characterUid,
                canonicalValue = it.objectValue ?: it.narrativeText.orEmpty(),
                evidenceUid = it.provenance.createdEvent
            )
        }
        override fun identity(campaignUid: String, characterUid: String) = listOf(CharacterPanelIdentityV2("uid", characterUid))
        override fun stats(campaignUid: String, characterUid: String) = emptyList<CharacterPanelExactValueV2>()
        override fun resources(campaignUid: String, characterUid: String) = emptyList<CharacterPanelExactValueV2>()
        override fun skills(campaignUid: String, characterUid: String) = emptyList<CharacterPanelMasteryV2>()
        override fun techniques(campaignUid: String, characterUid: String) = emptyList<CharacterPanelMasteryV2>()
        override fun talent(campaignUid: String, characterUid: String) = emptyList<CharacterPanelProfileValueV2>()
        override fun potential(campaignUid: String, characterUid: String) = emptyList<CharacterPanelProfileValueV2>()
        override fun innateAndEvolution(campaignUid: String, characterUid: String) = emptyList<CharacterPanelInnateV2>()
        override fun inventory(campaignUid: String, characterUid: String) = emptyList<CharacterPanelInventoryV2>()
        override fun equipment(campaignUid: String, characterUid: String) = emptyList<CharacterPanelEquipmentV2>()
        override fun ownershipAndAssets(campaignUid: String, characterUid: String) = emptyList<CharacterPanelOwnershipV2>()
        override fun economy(campaignUid: String, characterUid: String) = emptyList<CharacterPanelEconomyV2>()
        override fun progression(campaignUid: String, characterUid: String) = emptyList<CharacterPanelProgressionV2>()
        override fun projects(campaignUid: String, characterUid: String) = emptyList<CharacterPanelProjectV2>()
        override fun relationships(campaignUid: String, characterUid: String) = emptyList<CharacterPanelRelationshipV2>()
        override fun goals(campaignUid: String, characterUid: String) = emptyList<CharacterPanelGoalV2>()
    }
}
