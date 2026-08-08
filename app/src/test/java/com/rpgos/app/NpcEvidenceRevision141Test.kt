package com.rpgos.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NpcEvidenceRevision141Test {
    private val campaign = EntityUid("CAMPAIGN-revision")
    private val npc = EntityUid("NPC-A")
    private val subject = EntityUid("SUBJECT-X")

    @Test
    fun strongerObservationRetractsOlderInference() = runBlocking {
        val old = belief("BELIEF-old", "A", ProvenanceType.NPC_INFERENCE, 0.7, 10)
        val evidence = fact("FACT-new", "B", ProvenanceType.NPC_OBSERVATION, 0.9, 20)
        val store = FakeStore()
        val revision = NpcEvidenceRevision141(campaign, store) { EntityUid("RETRACT-1") }

        val result = revision.revise(view(listOf(old), listOf(evidence)), evidence.uid, "direct observation")

        assertEquals(1, result.created.size)
        assertEquals(old.uid, result.created.single().retractedBeliefUid)
        assertEquals(evidence.uid, result.created.single().replacementTruthUid)
        assertEquals(1, store.records.size)
    }

    @Test
    fun weakerInferenceCannotRetractObservation() = runBlocking {
        val old = belief("BELIEF-old", "A", ProvenanceType.NPC_OBSERVATION, 0.9, 10)
        val weak = belief("BELIEF-new", "B", ProvenanceType.NPC_INFERENCE, 0.95, 20)
        val store = FakeStore()
        val revision = NpcEvidenceRevision141(campaign, store)

        val result = revision.revise(view(listOf(old, weak)), weak.uid, "new inference")

        assertTrue(result.created.isEmpty())
        assertEquals(listOf(old.uid), result.unchangedBeliefUids)
    }

    @Test
    fun alreadyRetractedBeliefIsNotRetractedTwice() = runBlocking {
        val old = belief("BELIEF-old", "A", ProvenanceType.NPC_INFERENCE, 0.6, 5)
        val evidence = fact("FACT-new", "B", ProvenanceType.NPC_OBSERVATION, 1.0, 20)
        val store = FakeStore().apply {
            records += NpcBeliefRetraction141(EntityUid("RETRACT-old"), campaign, npc, old.uid, evidence.uid, 19, "prior")
        }
        val revision = NpcEvidenceRevision141(campaign, store)

        val result = revision.revise(view(listOf(old), listOf(evidence)), evidence.uid, "repeat")

        assertTrue(result.created.isEmpty())
        assertEquals(1, store.records.size)
    }

    private fun view(beliefs: List<CampaignTruth>, facts: List<CampaignTruth>) =
        NpcKnowledgeAccessPolicy141.View(
            holderUid = npc,
            atTurnId = 20,
            beliefs = beliefs,
            observableFacts = facts,
            organizationFacts = emptyList(),
            deniedGrants = emptyList(),
            unresolvedBeliefConflicts = emptyList()
        )

    private fun belief(uid: String, value: String, type: ProvenanceType, confidence: Double, turn: Long) = CampaignTruth(
        uid = EntityUid(uid),
        kind = TruthKind.BELIEF,
        subjectUid = subject,
        predicate = "location",
        value = value,
        holderUid = npc,
        validFromTurn = turn,
        provenance = ProvenanceRecord(type, EntityUid("SRC-$uid"), turn, confidence)
    )

    private fun fact(uid: String, value: String, type: ProvenanceType, confidence: Double, turn: Long) = CampaignTruth(
        uid = EntityUid(uid),
        kind = TruthKind.FACT,
        subjectUid = subject,
        predicate = "location",
        value = value,
        validFromTurn = turn,
        provenance = ProvenanceRecord(type, EntityUid("SRC-$uid"), turn, confidence)
    )

    private class FakeStore : NpcBeliefRetractionStore141 {
        val records = mutableListOf<NpcBeliefRetraction141>()
        override suspend fun appendRetraction(record: NpcBeliefRetraction141) { records += record }
        override suspend fun retractionsForHolder(campaignUid: EntityUid, holderUid: EntityUid, beforeOrAtTurn: Long): List<NpcBeliefRetraction141> =
            records.filter { it.campaignUid == campaignUid && it.holderUid == holderUid && it.turnId <= beforeOrAtTurn }
    }
}
