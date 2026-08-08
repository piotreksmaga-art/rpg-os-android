package com.rpgos.app

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
class GameMasterNpcKnowledgeEngineE2E141Test {
    private lateinit var context: Context
    private lateinit var campaignDir: File
    private lateinit var store: LocalGameStore

    private val holder = EntityUid("NPC-engine-e2e")
    private val subject = EntityUid("SUBJECT-engine-e2e")
    private val oldBeliefUid = EntityUid("BELIEF-engine-old")
    private val observationFactUid = EntityUid("FACT-engine-observation")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
        campaignDir = File(context.filesDir, "rpgos/saves/GM141_Npc_Engine_E2E.campaign")
        campaignDir.deleteRecursively()
        campaignDir.mkdirs()
        store = LocalGameStore(context)
        store.setActiveCampaign(campaignDir.name)
        store.bootstrap()
        require(File(campaignDir, "campaign.db").isFile)
    }

    @After
    fun tearDown() {
        runCatching { campaignDir.deleteRecursively() }
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun engineLifecycleCommitRestartAndDiagnosticsStayConsistent() = runBlocking {
        val factory = GameMasterRepositoryFactory(context, store)

        data class Expected(
            val campaignUid: EntityUid,
            val committedTurn: Long,
            val newBeliefUid: EntityUid
        )

        val expected = factory.openActiveSession().use { active ->
            val initialTurn = active.repository.currentTurnId(active.campaignUid)
            active.repository.inTransaction {
                writeTruth(
                    CampaignTruth(
                        uid = oldBeliefUid,
                        kind = TruthKind.BELIEF,
                        subjectUid = subject,
                        predicate = "location",
                        value = "VILLAGE",
                        holderUid = holder,
                        validFromTurn = initialTurn,
                        provenance = ProvenanceRecord(
                            type = ProvenanceType.NPC_INFERENCE,
                            sourceUid = EntityUid("FACT-old-inference-source"),
                            turnId = initialTurn,
                            confidence = 0.45
                        )
                    )
                )
                writeTruth(
                    CampaignTruth(
                        uid = observationFactUid,
                        kind = TruthKind.FACT,
                        subjectUid = subject,
                        predicate = "location",
                        value = "FOREST",
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
            }

            val request = GameMasterTurnRequest(
                campaignId = active.campaignUid.value,
                worldPackId = active.worldPackUid.value,
                playerAction = "NPC obserwuje położenie celu.",
                currentChapter = 1L
            )
            val gmContext = gmContext(active)
            val contextRepository = object : GameMasterContextRepository {
                override suspend fun buildContext(request: GameMasterTurnRequest) = gmContext
            }
            val gateway = object : GameMasterModelGateway {
                override suspend fun generateProposal(
                    request: GameMasterTurnRequest,
                    context: GameMasterContext
                ) = GameMasterProposal(
                    narrativeDraft = "NPC widzi cel w lesie i aktualizuje swoją wiedzę.",
                    proposedActions = listOf(
                        ProposedWorldAction(
                            actionType = "KNOWLEDGE_PROPAGATE",
                            actorId = holder.value,
                            targetId = subject.value,
                            parametersJson = """{
                                "source_subject_id":"${subject.value}",
                                "source_predicate":"location",
                                "source_truth_id":"${observationFactUid.value}",
                                "channel":"OBSERVATION"
                            }""".trimIndent(),
                            reason = "Bezpośrednia obserwacja położenia celu."
                        )
                    )
                )
            }

            val baseResolver = GameMasterRuleResolver141(active.repository, active.campaignUid)
            val safeResolver = KnowledgeSafeRuleResolver141(baseResolver)
            val lifecycleResolver = NpcKnowledgeLifecycleRuleResolver141(
                delegate = safeResolver,
                repository = active.repository,
                campaignUid = active.campaignUid,
                retractionStore = requireNotNull(active.npcKnowledgeStores).retractions
            )
            val validator = NpcKnowledgeSemanticTurnValidator141(
                GameMasterTurnValidator141(active.repository, active.campaignUid)
            )
            val stateRepository = GameMasterStateRepository141(
                repository = active.repository,
                campaignUid = active.campaignUid,
                knowledgeStore = active.knowledgeStore,
                npcKnowledgeStores = active.npcKnowledgeStores
            )
            val engine = GameMasterEngine(
                contextRepository = contextRepository,
                modelGateway = gateway,
                ruleResolver = lifecycleResolver,
                validator = validator,
                stateRepository = stateRepository
            )

            val result = engine.play(request)
            val newWrite = result.truthWrites.single { it.kind == TruthKind.BELIEF }
            assertNotNull(newWrite.truthKey)
            assertEquals(ProvenanceType.NPC_OBSERVATION, newWrite.sourceType)
            assertEquals(1, result.npcKnowledgeWrites.resolutions.size)
            assertEquals(1, result.npcKnowledgeWrites.retractions.size)
            assertEquals(
                NpcKnowledgeLifecycle141.ResolutionReason.STRONGER_PROVENANCE,
                result.npcKnowledgeWrites.resolutions.single().reason
            )

            val committedTurn = initialTurn + 1L
            assertEquals(committedTurn, active.repository.currentTurnId(active.campaignUid))
            val beliefs = active.repository.getBeliefs(
                active.campaignUid,
                holder,
                subject,
                committedTurn,
                20
            )
            val newBelief = beliefs.single { it.value == "FOREST" }
            val retraction = requireNotNull(active.npcKnowledgeStores).retractions
                .retractionsForHolder(active.campaignUid, holder, committedTurn)
                .single { it.retractedBeliefUid == oldBeliefUid }
            assertEquals(newBelief.uid, retraction.replacementTruthUid)

            Expected(active.campaignUid, committedTurn, newBelief.uid)
        }

        // New repository objects simulate an app/repository restart and must reconstruct the same durable state.
        GameMasterRepositoryFactory(context, LocalGameStore(context)).openActiveSession().use { reopened ->
            assertEquals(expected.campaignUid, reopened.campaignUid)
            assertEquals(expected.committedTurn, reopened.repository.currentTurnId(reopened.campaignUid))
            val beliefs = reopened.repository.getBeliefs(
                reopened.campaignUid,
                holder,
                subject,
                expected.committedTurn,
                20
            )
            assertTrue(beliefs.any { it.uid == expected.newBeliefUid && it.value == "FOREST" })
            val retractions = requireNotNull(reopened.npcKnowledgeStores).retractions
                .retractionsForHolder(reopened.campaignUid, holder, expected.committedTurn)
            assertTrue(
                retractions.any {
                    it.retractedBeliefUid == oldBeliefUid && it.replacementTruthUid == expected.newBeliefUid
                }
            )
        }

        val diagnostics = GameMasterDiagnosticsService141(context, LocalGameStore(context))
        val offline = diagnostics.report()
        assertTrue(offline.contains("integrity=OK"))
        assertTrue(offline.contains("knowledgeIntegrity=OK"))
        assertTrue(offline.contains("npcLifecycleIntegrity=OK"))
        assertTrue(offline.contains("npcKnowledgePersistence=READY"))

        val npcReport = diagnostics.npcKnowledgeReport(holder, expected.committedTurn)
        assertTrue(npcReport.contains("status=OK"))
        assertTrue(npcReport.contains("activeBeliefs=1"))
        assertTrue(npcReport.contains("${expected.newBeliefUid.value} location=FOREST"))
        assertTrue(npcReport.contains("${oldBeliefUid.value} status=RETRACTED"))
        assertTrue(npcReport.contains("replacement=${expected.newBeliefUid.value}"))
    }

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
