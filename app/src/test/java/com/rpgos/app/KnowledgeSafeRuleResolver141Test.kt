package com.rpgos.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeSafeRuleResolver141Test {
    @Test
    fun rejectsBeliefWithoutExplicitNpcKnowledgeChannel() = runBlocking {
        val delegate = object : GameMasterRuleResolver {
            override suspend fun resolve(
                request: GameMasterTurnRequest,
                context: GameMasterContext,
                proposal: GameMasterProposal
            ) = GameMasterTurnResult(
                narrative = "test",
                truthWrites = listOf(
                    TruthWrite(
                        kind = TruthKind.BELIEF,
                        subjectId = "SUBJECT-1",
                        predicate = "location",
                        value = "KUMO",
                        holderId = "NPC-A",
                        confidence = 1.0,
                        sourceType = ProvenanceType.SYSTEM_SIMULATION,
                        sourceId = null
                    )
                )
            )
        }

        val result = runCatching {
            KnowledgeSafeRuleResolver141(delegate).resolve(request(), context(), proposal())
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun acceptsBeliefWithObservationSource() = runBlocking {
        val delegate = object : GameMasterRuleResolver {
            override suspend fun resolve(
                request: GameMasterTurnRequest,
                context: GameMasterContext,
                proposal: GameMasterProposal
            ) = GameMasterTurnResult(
                narrative = "test",
                truthWrites = listOf(
                    TruthWrite(
                        kind = TruthKind.BELIEF,
                        subjectId = "SUBJECT-1",
                        predicate = "location",
                        value = "KUMO",
                        holderId = "NPC-A",
                        confidence = 0.95,
                        sourceType = ProvenanceType.NPC_OBSERVATION,
                        sourceId = "FACT-1"
                    )
                )
            )
        }

        val result = KnowledgeSafeRuleResolver141(delegate).resolve(request(), context(), proposal())
        assertTrue(result.truthWrites.single().kind == TruthKind.BELIEF)
    }

    private fun request() = GameMasterTurnRequest(
        campaignId = "CAMPAIGN-test",
        worldPackId = "WORLD-test",
        playerAction = "test",
        currentChapter = 1L
    )

    private fun context() = GameMasterContext(
        campaignId = "CAMPAIGN-test",
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

    private fun proposal() = GameMasterProposal(
        narrativeDraft = "test",
        diagnostics = GameMasterDiagnostics(0, 0, 0, 0, 0)
    )

    private fun section(name: String) = ContextSection(name, "{}", 1)
}
