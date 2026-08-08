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
class GameMasterNpcKnowledgeTurnCommit141Test {
    private lateinit var context: Context
    private lateinit var campaignDir: File
    private lateinit var store: LocalGameStore

    private val holder = EntityUid("NPC-turn-atomic")
    private val subject = EntityUid("SUBJECT-turn-atomic")
    private val sourceFact = EntityUid("FACT-source-turn-atomic")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
        campaignDir = File(context.filesDir, "rpgos/saves/GM141_Turn_Atomic.campaign")
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
    fun structuredTurnResultCommitsBeliefAndInferenceTogether() = runBlocking {
        val factory = GameMasterRepositoryFactory(context, store)
        val (campaignUid, committedTurn) = factory.openActiveSession().use { session ->
            val initialTurn = session.repository.currentTurnId(session.campaignUid)
            session.repository.writeTruth(sourceTruth())
            val state = GameMasterStateRepository141(
                repository = session.repository,
                campaignUid = session.campaignUid,
                knowledgeStore = session.knowledgeStore,
                npcKnowledgeStores = session.npcKnowledgeStores
            )
            state.commitTurn(
                request = request(session),
                context = context(session),
                result = GameMasterTurnResult(
                    narrative = "NPC wyciąga wniosek z istniejącego faktu.",
                    truthWrites = listOf(
                        TruthWrite(
                            kind = TruthKind.BELIEF,
                            subjectId = subject.value,
                            predicate = "threat.level",
                            value = "high",
                            holderId = holder.value,
                            confidence = 0.8,
                            sourceType = ProvenanceType.NPC_INFERENCE,
                            sourceId = sourceFact.value,
                            knowledgeChannel = KnowledgeChannel141.INFERENCE,
                            truthKey = "belief-inferred"
                        )
                    ),
                    npcKnowledgeWrites = NpcKnowledgeWrites141(
                        inferences = listOf(
                            NpcInferenceWrite141(
                                holderId = holder.value,
                                resultingBelief = TruthRef141(truthKey = "belief-inferred"),
                                premiseTruths = listOf(TruthRef141(durableUid = sourceFact.value)),
                                confidence = 0.8
                            )
                        )
                    )
                )
            )
            session.campaignUid to (initialTurn + 1L)
        }

        factory.openActiveSession().use { reopened ->
            assertEquals(campaignUid, reopened.campaignUid)
            assertEquals(committedTurn, reopened.repository.currentTurnId(campaignUid))
            val beliefs = reopened.repository.getBeliefs(campaignUid, holder, subject, committedTurn, 20)
            assertEquals(1, beliefs.size)
            val belief = beliefs.single()
            assertEquals("high", belief.value)
            val inference = requireNotNull(reopened.npcKnowledgeStores).inferences.inferenceForBelief(
                campaignUid = campaignUid,
                holderUid = holder,
                resultingBeliefUid = belief.uid
            )
            assertNotNull(inference)
            assertEquals(listOf(sourceFact), inference!!.premiseTruthUids)
            assertEquals(0.8, inference.confidence, 0.0001)
        }
    }

    @Test
    fun ledgerFailureRollsBackTurnAndTruthWrites() = runBlocking {
        val factory = GameMasterRepositoryFactory(context, store)
        val retracted = EntityUid("BELIEF-retracted-atomic")
        val replacement = EntityUid("FACT-replacement-atomic")

        val (campaignUid, initialTurn) = factory.openActiveSession().use { session ->
            val initialTurn = session.repository.currentTurnId(session.campaignUid)
            session.repository.inTransaction {
                writeTruth(sourceTruth())
                writeTruth(
                    CampaignTruth(
                        uid = retracted,
                        kind = TruthKind.BELIEF,
                        subjectUid = subject,
                        predicate = "location",
                        value = "A",
                        holderUid = holder,
                        validFromTurn = 0,
                        provenance = ProvenanceRecord(ProvenanceType.NPC_INFERENCE, sourceFact, 0, 0.5)
                    )
                )
                writeTruth(
                    CampaignTruth(
                        uid = replacement,
                        kind = TruthKind.FACT,
                        subjectUid = subject,
                        predicate = "location",
                        value = "B",
                        validFromTurn = 0,
                        provenance = ProvenanceRecord(
                            type = ProvenanceType.CAMPAIGN_EVENT,
                            sourceUid = null,
                            turnId = 0,
                            confidence = 1.0,
                            verified = true
                        )
                    )
                )
            }

            val state = GameMasterStateRepository141(
                repository = session.repository,
                campaignUid = session.campaignUid,
                knowledgeStore = session.knowledgeStore,
                npcKnowledgeStores = session.npcKnowledgeStores
            )
            val duplicate = NpcBeliefRetractionWrite141(
                holderId = holder.value,
                retractedBelief = TruthRef141(durableUid = retracted.value),
                replacementTruth = TruthRef141(durableUid = replacement.value),
                reason = "direct evidence"
            )

            val failure = runCatching {
                state.commitTurn(
                    request = request(session),
                    context = context(session),
                    result = GameMasterTurnResult(
                        narrative = "Ta tura ma zostać wycofana.",
                        truthWrites = listOf(
                            TruthWrite(
                                kind = TruthKind.FACT,
                                subjectId = subject.value,
                                predicate = "rollback.marker",
                                value = "must-not-survive",
                                sourceType = ProvenanceType.SYSTEM_SIMULATION,
                                truthKey = "rollback-marker"
                            )
                        ),
                        npcKnowledgeWrites = NpcKnowledgeWrites141(
                            retractions = listOf(duplicate, duplicate)
                        )
                    )
                )
            }
            assertTrue(failure.isFailure)
            session.campaignUid to initialTurn
        }

        factory.openActiveSession().use { reopened ->
            assertEquals(initialTurn, reopened.repository.currentTurnId(campaignUid))
            assertTrue(
                reopened.repository.getTruth(campaignUid, subject, "rollback.marker", initialTurn + 100L).isEmpty()
            )
            assertTrue(
                requireNotNull(reopened.npcKnowledgeStores).retractions.retractionsForHolder(
                    campaignUid, holder, initialTurn + 100L
                ).isEmpty()
            )
        }
    }

    private fun sourceTruth() = CampaignTruth(
        uid = sourceFact,
        kind = TruthKind.FACT,
        subjectUid = subject,
        predicate = "evidence",
        value = "danger",
        validFromTurn = 0,
        provenance = ProvenanceRecord(
            type = ProvenanceType.CAMPAIGN_EVENT,
            sourceUid = null,
            turnId = 0,
            confidence = 1.0,
            verified = true
        )
    )

    private fun request(session: ActiveGameMasterRepository) = GameMasterTurnRequest(
        campaignId = session.campaignUid.value,
        worldPackId = session.worldPackUid.value,
        playerAction = "test",
        currentChapter = 1L
    )

    private fun context(session: ActiveGameMasterRepository) = GameMasterContext(
        campaignId = session.campaignUid.value,
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
