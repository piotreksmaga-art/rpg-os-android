package com.rpgos.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NpcKnowledgeSemanticTurnValidator141Test {

    private val validator = NpcKnowledgeSemanticTurnValidator141(
        object : GameMasterTurnValidator {
            override suspend fun validate(
                request: GameMasterTurnRequest,
                context: GameMasterContext,
                result: GameMasterTurnResult
            ) = GameMasterValidationReport()
        }
    )

    @Test
    fun acceptsInferenceResultBeliefOwnedByInferenceHolder() = runBlocking {
        val result = GameMasterTurnResult(
            narrative = "test",
            truthWrites = listOf(belief("result", "NPC-a")),
            npcKnowledgeWrites = NpcKnowledgeWrites141(
                inferences = listOf(
                    NpcInferenceWrite141(
                        holderId = "NPC-a",
                        resultingBelief = TruthRef141(truthKey = "result"),
                        premiseTruths = listOf(TruthRef141(durableUid = "FACT-old")),
                        confidence = 0.8
                    )
                )
            )
        )

        val report = validator.validate(request(), context(), result)
        assertTrue(report.issues.joinToString { it.code }, report.accepted)
    }

    @Test
    fun rejectsFactAsInferenceResult() = runBlocking {
        val result = GameMasterTurnResult(
            narrative = "test",
            truthWrites = listOf(
                TruthWrite(
                    kind = TruthKind.FACT,
                    subjectId = "TARGET",
                    predicate = "location",
                    value = "forest",
                    sourceType = ProvenanceType.SYSTEM_SIMULATION,
                    truthKey = "result"
                )
            ),
            npcKnowledgeWrites = NpcKnowledgeWrites141(
                inferences = listOf(
                    NpcInferenceWrite141(
                        holderId = "NPC-a",
                        resultingBelief = TruthRef141(truthKey = "result"),
                        premiseTruths = listOf(TruthRef141(durableUid = "FACT-old")),
                        confidence = 0.8
                    )
                )
            )
        )

        val report = validator.validate(request(), context(), result)
        assertFalse(report.accepted)
        assertTrue(report.issues.any { it.code == "NPC_KNOWLEDGE_REF_NOT_BELIEF" })
    }

    @Test
    fun rejectsBeliefOwnedByDifferentReceiver() = runBlocking {
        val result = GameMasterTurnResult(
            narrative = "test",
            truthWrites = listOf(belief("result", "NPC-other")),
            npcKnowledgeWrites = NpcKnowledgeWrites141(
                organizationTransmissions = listOf(
                    OrganizationKnowledgeWrite141(
                        organizationId = "ORG-1",
                        membershipId = "MEMBERSHIP-1",
                        publicationId = "PUBLICATION-1",
                        sourceTruth = TruthRef141(durableUid = "FACT-old"),
                        receiverId = "NPC-a",
                        resultingBelief = TruthRef141(truthKey = "result"),
                        confidence = 0.7
                    )
                )
            )
        )

        val report = validator.validate(request(), context(), result)
        assertFalse(report.accepted)
        assertTrue(report.issues.any { it.code == "NPC_KNOWLEDGE_HOLDER_MISMATCH" })
    }

    @Test
    fun rejectsFactInsideResolutionCompetingBeliefs() = runBlocking {
        val result = GameMasterTurnResult(
            narrative = "test",
            truthWrites = listOf(
                belief("belief-a", "NPC-a"),
                TruthWrite(
                    kind = TruthKind.FACT,
                    subjectId = "TARGET",
                    predicate = "location",
                    value = "forest",
                    sourceType = ProvenanceType.SYSTEM_SIMULATION,
                    truthKey = "fact-b"
                )
            ),
            npcKnowledgeWrites = NpcKnowledgeWrites141(
                resolutions = listOf(
                    NpcKnowledgeResolutionWrite141(
                        holderId = "NPC-a",
                        subjectId = "TARGET",
                        predicate = "location",
                        competingBeliefs = listOf(
                            TruthRef141(truthKey = "belief-a"),
                            TruthRef141(truthKey = "fact-b")
                        ),
                        winner = TruthRef141(truthKey = "belief-a"),
                        reason = NpcKnowledgeLifecycle141.ResolutionReason.STRONGER_PROVENANCE
                    )
                )
            )
        )

        val report = validator.validate(request(), context(), result)
        assertFalse(report.accepted)
        assertTrue(report.issues.any { it.code == "NPC_KNOWLEDGE_REF_NOT_BELIEF" })
    }

    private fun belief(key: String, holder: String) = TruthWrite(
        kind = TruthKind.BELIEF,
        subjectId = "TARGET",
        predicate = "location",
        value = "forest",
        holderId = holder,
        confidence = 0.8,
        sourceType = ProvenanceType.NPC_INFERENCE,
        sourceId = "FACT-old",
        knowledgeChannel = KnowledgeChannel141.INFERENCE,
        truthKey = key
    )

    private fun request() = GameMasterTurnRequest(
        campaignId = "CAMPAIGN-test",
        worldPackId = "WORLDPACK-test",
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
        relevantMemories = section("memories"),
        canonKnowledge = section("canon"),
        rules = section("rules"),
        recentNarrative = section("recent")
    )

    private fun section(title: String) = ContextSection(title, "{}", 1)
}
