package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NpcKnowledgeLifecycle141Test {
    private val npc = EntityUid("NPC-A")
    private val subject = EntityUid("SUBJECT-X")
    private val lifecycle = NpcKnowledgeLifecycle141 { EntityUid("KRES-1") }

    @Test
    fun observationBeatsInferenceEvenWithLowerConfidence() {
        val observation = belief("B-OBS", "A", ProvenanceType.NPC_OBSERVATION, 0.60, 20)
        val inference = belief("B-INF", "B", ProvenanceType.NPC_INFERENCE, 0.95, 21)

        val result = lifecycle.resolve(npc, 21, listOf(observation, inference))

        assertEquals(listOf(observation.uid), result.effectiveBeliefs.map { it.uid })
        assertEquals(NpcKnowledgeLifecycle141.ResolutionReason.STRONGER_PROVENANCE, result.resolutions.single().reason)
        assertEquals(listOf(inference.uid), result.resolutions.single().supersededBeliefUids)
    }

    @Test
    fun strongerConfidenceWinsInsideSameProvenanceTier() {
        val weak = belief("B-1", "A", ProvenanceType.NPC_REPORT, 0.50, 20)
        val strong = belief("B-2", "B", ProvenanceType.NPC_REPORT, 0.80, 19)

        val result = lifecycle.resolve(npc, 20, listOf(weak, strong))

        assertEquals(strong.uid, result.effectiveBeliefs.single().uid)
        assertEquals(NpcKnowledgeLifecycle141.ResolutionReason.HIGHER_CONFIDENCE, result.resolutions.single().reason)
    }

    @Test
    fun newerEvidenceWinsWhenRankAndConfidenceAreEqual() {
        val old = belief("B-old", "A", ProvenanceType.NPC_REPORT, 0.80, 10)
        val newer = belief("B-new", "B", ProvenanceType.NPC_REPORT, 0.80, 20)

        val result = lifecycle.resolve(npc, 20, listOf(old, newer))

        assertEquals(newer.uid, result.effectiveBeliefs.single().uid)
        assertEquals(NpcKnowledgeLifecycle141.ResolutionReason.NEWER_EVIDENCE, result.resolutions.single().reason)
    }

    @Test
    fun exactTieRemainsExplicitlyUnresolved() {
        val a = belief("B-A", "A", ProvenanceType.NPC_REPORT, 0.80, 20)
        val b = belief("B-B", "B", ProvenanceType.NPC_REPORT, 0.80, 20)

        val result = lifecycle.resolve(npc, 20, listOf(a, b))

        assertEquals(1, result.unresolvedConflicts.size)
        assertEquals(2, result.effectiveBeliefs.size)
        assertEquals(NpcKnowledgeLifecycle141.ResolutionReason.UNRESOLVED_TIE, result.resolutions.single().reason)
    }

    @Test
    fun expiredBeliefDoesNotParticipateInConflict() {
        val expired = belief("B-old", "A", ProvenanceType.NPC_OBSERVATION, 1.0, 5, validUntil = 10)
        val active = belief("B-new", "B", ProvenanceType.NPC_REPORT, 0.60, 20)

        val result = lifecycle.resolve(npc, 20, listOf(expired, active))

        assertEquals(active.uid, result.effectiveBeliefs.single().uid)
        assertTrue(result.resolutions.isEmpty())
    }

    private fun belief(
        uid: String,
        value: String,
        provenance: ProvenanceType,
        confidence: Double,
        turn: Long,
        validUntil: Long? = null
    ) = CampaignTruth(
        uid = EntityUid(uid),
        kind = TruthKind.BELIEF,
        subjectUid = subject,
        predicate = "target.location",
        value = value,
        holderUid = npc,
        validFromTurn = turn,
        validUntilTurn = validUntil,
        provenance = ProvenanceRecord(provenance, EntityUid("SRC-$uid"), turn, confidence)
    )
}
