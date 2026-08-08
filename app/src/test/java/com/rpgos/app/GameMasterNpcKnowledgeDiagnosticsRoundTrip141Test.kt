package com.rpgos.app

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GameMasterNpcKnowledgeDiagnosticsRoundTrip141Test {
    private lateinit var context: Context
    private lateinit var campaignDir: File
    private lateinit var store: LocalGameStore

    private val holder = EntityUid("NPC-diagnostics-roundtrip")
    private val subject = EntityUid("SUBJECT-diagnostics-roundtrip")
    private val sourceFactUid = EntityUid("FACT-source-diagnostics")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()

        campaignDir = File(context.filesDir, "rpgos/saves/GM141_Diagnostics_RoundTrip.campaign")
        campaignDir.deleteRecursively()
        campaignDir.mkdirs()

        store = LocalGameStore(context)
        store.setActiveCampaign(campaignDir.name)
        store.bootstrap()
        require(File(campaignDir, "campaign.db").isFile) { "Test bootstrap did not create campaign.db" }
    }

    @After
    fun tearDown() {
        runCatching { campaignDir.deleteRecursively() }
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun offlineNpcKnowledgeReportSurvivesRepositoryRestart() = runBlocking {
        val factory = GameMasterRepositoryFactory(context, store)

        data class Expected(
            val committedTurn: Long,
            val oldBeliefUid: EntityUid,
            val inferredBeliefUid: EntityUid,
            val replacementFactUid: EntityUid
        )

        val expected = factory.openActiveSession().use { active ->
            val initialTurn = active.repository.currentTurnId(active.campaignUid)
            active.repository.writeTruth(
                CampaignTruth(
                    uid = sourceFactUid,
                    kind = TruthKind.FACT,
                    subjectUid = subject,
                    predicate = "evidence",
                    value = "enemy-pattern",
                    validFromTurn = initialTurn,
                    provenance = ProvenanceRecord(
                        type = ProvenanceType.CAMPAIGN_EVENT,
                        sourceUid = null,
                        turnId = initialTurn,
                        confidence = 1.0,
                        verified = true
                    )
                )
            )

            val state = GameMasterStateRepository141(
                repository = active.repository,
                campaignUid = active.campaignUid,
                knowledgeStore = active.knowledgeStore,
                npcKnowledgeStores = active.npcKnowledgeStores
            )
            state.commitTurn(
                request = request(active),
                context = gmContext(active),
                result = GameMasterTurnResult(
                    narrative = "NPC aktualizuje wiedzę po nowych dowodach.",
                    truthWrites = listOf(
                        TruthWrite(
                            kind = TruthKind.BELIEF,
                            subjectId = subject.value,
                            predicate = "location",
                            value = "KONOHA",
                            holderId = holder.value,
                            confidence = 0.55,
                            sourceType = ProvenanceType.NPC_INFERENCE,
                            sourceId = sourceFactUid.value,
                            knowledgeChannel = KnowledgeChannel141.INFERENCE,
                            truthKey = "old-location-belief"
                        ),
                        TruthWrite(
                            kind = TruthKind.FACT,
                            subjectId = subject.value,
                            predicate = "location",
                            value = "KUMO",
                            confidence = 1.0,
                            sourceType = ProvenanceType.SYSTEM_SIMULATION,
                            truthKey = "replacement-location-fact"
                        ),
                        TruthWrite(
                            kind = TruthKind.BELIEF,
                            subjectId = subject.value,
                            predicate = "suspicion",
                            value = "enemy",
                            holderId = holder.value,
                            confidence = 0.80,
                            sourceType = ProvenanceType.NPC_INFERENCE,
                            sourceId = sourceFactUid.value,
                            knowledgeChannel = KnowledgeChannel141.INFERENCE,
                            truthKey = "inferred-suspicion-belief"
                        )
                    ),
                    npcKnowledgeWrites = NpcKnowledgeWrites141(
                        inferences = listOf(
                            NpcInferenceWrite141(
                                holderId = holder.value,
                                resultingBelief = TruthRef141(truthKey = "inferred-suspicion-belief"),
                                premiseTruths = listOf(TruthRef141(durableUid = sourceFactUid.value)),
                                confidence = 0.80
                            )
                        ),
                        retractions = listOf(
                            NpcBeliefRetractionWrite141(
                                holderId = holder.value,
                                retractedBelief = TruthRef141(truthKey = "old-location-belief"),
                                replacementTruth = TruthRef141(truthKey = "replacement-location-fact"),
                                reason = "direct evidence"
                            )
                        )
                    )
                )
            )

            val committedTurn = initialTurn + 1L
            val beliefs = active.repository.getBeliefs(
                active.campaignUid,
                holder,
                subject,
                committedTurn,
                20
            )
            val oldBelief = beliefs.single { it.predicate == "location" && it.value == "KONOHA" }
            val inferredBelief = beliefs.single { it.predicate == "suspicion" && it.value == "enemy" }
            val replacementFact = active.repository.getTruth(
                active.campaignUid,
                subject,
                "location",
                committedTurn
            ).single { it.kind == TruthKind.FACT && it.value == "KUMO" }

            Expected(
                committedTurn = committedTurn,
                oldBeliefUid = oldBelief.uid,
                inferredBeliefUid = inferredBelief.uid,
                replacementFactUid = replacementFact.uid
            )
        }

        // A completely new factory/service must reconstruct the same holder-scoped view from campaign.db.
        val report = GameMasterDiagnosticsService141(context, LocalGameStore(context))
            .npcKnowledgeReport(holderUid = holder, atTurnId = expected.committedTurn)

        assertTrue(report.contains("GM141 NPC KNOWLEDGE DIAGNOSTICS"))
        assertTrue(report.contains("holder=${holder.value}"))
        assertTrue(report.contains("activeBeliefs=1"))
        assertTrue(report.contains("${expected.inferredBeliefUid.value} suspicion=enemy"))
        assertTrue(report.contains("${expected.oldBeliefUid.value} status=RETRACTED"))
        assertTrue(report.contains("replacement=${expected.replacementFactUid.value}"))
        assertTrue(report.contains("status=OK"))
    }

    @Test
    fun failedNpcKnowledgeTransactionRollsBackAllLedgerWrites() = runBlocking {
        val factory = GameMasterRepositoryFactory(context, store)
        val campaignUid = factory.openActiveSession().use { active ->
            val stores = requireNotNull(active.npcKnowledgeStores)
            val tx = NpcKnowledgeTurnTransaction141(active.repository, stores)
            val turn = active.repository.currentTurnId(active.campaignUid) + 1L

            runCatching {
                tx.commit {
                    inferences.appendInference(
                        NpcInferenceLedgerEntry141(
                            inferenceUid = EntityUid("INFERENCE-rollback"),
                            campaignUid = active.campaignUid,
                            holderUid = holder,
                            resultingBeliefUid = EntityUid("BELIEF-rollback"),
                            premiseTruthUids = listOf(EntityUid("FACT-rollback")),
                            turnId = turn,
                            confidence = 0.5
                        )
                    )
                    retractions.appendRetraction(
                        NpcBeliefRetraction141(
                            retractionUid = EntityUid("RETRACTION-rollback"),
                            campaignUid = active.campaignUid,
                            holderUid = holder,
                            retractedBeliefUid = EntityUid("BELIEF-old-rollback"),
                            replacementTruthUid = EntityUid("FACT-new-rollback"),
                            turnId = turn,
                            reason = "forced rollback"
                        )
                    )
                    error("simulate failed turn")
                }
            }
            active.campaignUid
        }

        factory.openActiveSession().use { reopened ->
            val stores = requireNotNull(reopened.npcKnowledgeStores)
            assertEquals(campaignUid, reopened.campaignUid)
            assertEquals(
                null,
                stores.inferences.inferenceForBelief(
                    campaignUid = campaignUid,
                    holderUid = holder,
                    resultingBeliefUid = EntityUid("BELIEF-rollback")
                )
            )
            assertTrue(
                stores.retractions.retractionsForHolder(
                    campaignUid = campaignUid,
                    holderUid = holder,
                    beforeOrAtTurn = Long.MAX_VALUE
                ).none { it.retractionUid == EntityUid("RETRACTION-rollback") }
            )
        }
    }

    private fun request(active: ActiveGameMasterRepository) = GameMasterTurnRequest(
        campaignId = active.campaignUid.value,
        worldPackId = active.worldPackUid.value,
        playerAction = "test",
        currentChapter = 1L
    )

    private fun gmContext(active: ActiveGameMasterRepository) = GameMasterContext(
        campaignId = active.campaignUid.value,
        chapter = 1L,
        scene = section("scene"),
        playerState = section("player"),
        activeWorldState = section("world"),
        activeThreads = section("threads"),
        relevantMemories = section("memory"),
        canonKnowledge = section("canon"),
        rules = section("rules"),
        recentNarrative = section("recent")
    )

    private fun section(name: String) = ContextSection(name, "{}", 1)
}
