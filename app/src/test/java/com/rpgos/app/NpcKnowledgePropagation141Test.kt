package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NpcKnowledgePropagation141Test {
    private val fact = CampaignTruth(
        uid = EntityUid("FACT-1"),
        kind = TruthKind.FACT,
        subjectUid = EntityUid("SUBJECT-1"),
        predicate = "location",
        value = "KUMO",
        provenance = ProvenanceRecord(
            type = ProvenanceType.CAMPAIGN_EVENT,
            sourceUid = EntityUid("EVENT-1"),
            turnId = 5L,
            confidence = 1.0,
            verified = true
        ),
        validFromTurn = 5L
    )

    @Test
    fun observationCreatesHolderScopedBeliefNeverFact() {
        val receiver = EntityUid("NPC-B")
        val result = NpcKnowledgePropagation141 { EntityUid("BELIEF-OBS") }.propagate(
            KnowledgePropagationRequest141(
                receiverUid = receiver,
                sourceTruth = fact,
                channel = KnowledgeChannel141.OBSERVATION,
                turnId = 6L
            )
        )

        assertEquals(TruthKind.BELIEF, result.kind)
        assertEquals(receiver, result.holderUid)
        assertEquals(ProvenanceType.NPC_OBSERVATION, result.provenance.type)
        assertEquals(fact.uid, result.provenance.sourceUid)
        assertTrue(result.provenance.confidence == 1.0)
        assertFalse(result.provenance.verified)
    }

    @Test
    fun reportCannotLeakAnotherNpcBelief() {
        val sourceNpc = EntityUid("NPC-A")
        val foreignBelief = fact.copy(
            uid = EntityUid("BELIEF-X"),
            kind = TruthKind.BELIEF,
            holderUid = EntityUid("NPC-C"),
            provenance = fact.provenance.copy(
                type = ProvenanceType.NPC_INFERENCE,
                confidence = 0.7,
                verified = false
            )
        )

        val attempt = runCatching {
            NpcKnowledgePropagation141().propagate(
                KnowledgePropagationRequest141(
                    receiverUid = EntityUid("NPC-B"),
                    sourceTruth = foreignBelief,
                    channel = KnowledgeChannel141.REPORT,
                    sourceNpcUid = sourceNpc,
                    turnId = 7L
                )
            )
        }
        assertTrue(attempt.isFailure)
    }

    @Test
    fun observationRejectsBeliefAsObjectiveSource() {
        val belief = fact.copy(
            uid = EntityUid("BELIEF-A"),
            kind = TruthKind.BELIEF,
            holderUid = EntityUid("NPC-A"),
            provenance = fact.provenance.copy(type = ProvenanceType.NPC_REPORT, confidence = 0.8)
        )

        val attempt = runCatching {
            NpcKnowledgePropagation141().propagate(
                KnowledgePropagationRequest141(
                    receiverUid = EntityUid("NPC-B"),
                    sourceTruth = belief,
                    channel = KnowledgeChannel141.OBSERVATION,
                    turnId = 7L
                )
            )
        }
        assertTrue(attempt.isFailure)
    }

    @Test
    fun reportAndInferenceReduceConfidence() {
        val sourceNpc = EntityUid("NPC-A")
        val sourceBelief = fact.copy(
            uid = EntityUid("BELIEF-A"),
            kind = TruthKind.BELIEF,
            holderUid = sourceNpc,
            provenance = fact.provenance.copy(type = ProvenanceType.NPC_OBSERVATION, confidence = 0.9)
        )
        val engine = NpcKnowledgePropagation141 { EntityUid("BELIEF-NEW") }

        val reported = engine.propagate(
            KnowledgePropagationRequest141(
                receiverUid = EntityUid("NPC-B"),
                sourceTruth = sourceBelief,
                channel = KnowledgeChannel141.REPORT,
                sourceNpcUid = sourceNpc,
                turnId = 8L
            )
        )
        val inferred = engine.propagate(
            KnowledgePropagationRequest141(
                receiverUid = EntityUid("NPC-B"),
                sourceTruth = fact,
                channel = KnowledgeChannel141.INFERENCE,
                turnId = 8L
            )
        )

        assertTrue(reported.provenance.confidence < sourceBelief.provenance.confidence)
        assertTrue(inferred.provenance.confidence < fact.provenance.confidence)
    }

    @Test
    fun propagationRejectsFutureKnowledge() {
        val future = fact.copy(validFromTurn = 20L)
        val attempt = runCatching {
            NpcKnowledgePropagation141().propagate(
                KnowledgePropagationRequest141(
                    receiverUid = EntityUid("NPC-B"),
                    sourceTruth = future,
                    channel = KnowledgeChannel141.OBSERVATION,
                    turnId = 10L
                )
            )
        }
        assertTrue(attempt.isFailure)
    }
}
