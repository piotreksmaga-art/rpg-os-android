package com.rpgos.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NpcKnowledgeExplain141Test {
    private val campaign = EntityUid("CAMPAIGN-explain")
    private val npc = EntityUid("NPC-explain")
    private val subject = EntityUid("SUBJECT-explain")

    @Test
    fun explainsInferencePremisesAndRetractionReplacement() = runBlocking {
        val fact = CampaignTruth(
            uid = EntityUid("FACT-source"), kind = TruthKind.FACT, subjectUid = subject,
            predicate = "target.location", value = "KUMO", validFromTurn = 5,
            provenance = ProvenanceRecord(ProvenanceType.CAMPAIGN_EVENT, EntityUid("EVENT-1"), 5, 1.0)
        )
        val belief = CampaignTruth(
            uid = EntityUid("BELIEF-infer"), kind = TruthKind.BELIEF, subjectUid = subject,
            predicate = "target.location", value = "KONOHA", holderUid = npc, validFromTurn = 10,
            provenance = ProvenanceRecord(ProvenanceType.NPC_INFERENCE, fact.uid, 10, 0.6)
        )
        val replacement = CampaignTruth(
            uid = EntityUid("BELIEF-observed"), kind = TruthKind.BELIEF, subjectUid = subject,
            predicate = "target.location", value = "KUMO", holderUid = npc, validFromTurn = 20,
            provenance = ProvenanceRecord(ProvenanceType.NPC_OBSERVATION, fact.uid, 20, 1.0)
        )
        val repo = Repo(mutableListOf(fact, belief, replacement), 25)
        val retraction = NpcBeliefRetraction141(
            EntityUid("RETRACT-1"), campaign, npc, belief.uid, replacement.uid, 20, "direct evidence"
        )
        val retractions = Retractions(listOf(retraction))
        val inference = Inference(
            NpcInferenceLedgerEntry141(
                EntityUid("INFER-1"), campaign, npc, belief.uid,
                listOf(fact.uid, EntityUid("BELIEF-premise-2")), 10, 0.6
            )
        )

        val explained = NpcKnowledgeExplain141(repo, campaign, retractions, inference)
            .explain(npc, belief.uid, atTurnId = 25)

        assertEquals(NpcBeliefTimeline141.Status.RETRACTED, explained.status)
        assertEquals(20L, explained.endedTurn)
        assertEquals(replacement.uid, explained.replacementTruth?.uid)
        assertEquals(listOf(fact.uid, EntityUid("BELIEF-premise-2")), explained.inferencePremiseUids)
        assertEquals(listOf(belief.uid, fact.uid), explained.provenanceChain.map { it.uid })
        assertFalse(explained.isCurrent)
        assertFalse(explained.cycleDetected)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cannotExplainAnotherNpcsBelief() = runBlocking {
        val belief = CampaignTruth(
            uid = EntityUid("BELIEF-other"), kind = TruthKind.BELIEF, subjectUid = subject,
            predicate = "x", value = "y", holderUid = EntityUid("NPC-other"), validFromTurn = 1,
            provenance = ProvenanceRecord(ProvenanceType.NPC_REPORT, EntityUid("SRC"), 1, 0.7)
        )
        NpcKnowledgeExplain141(Repo(mutableListOf(belief), 5), campaign, Retractions(emptyList()))
            .explain(npc, belief.uid, 5)
        Unit
    }

    private class Inference(private val record: NpcInferenceLedgerEntry141) : NpcInferenceQueryStore141 {
        override suspend fun appendInference(record: NpcInferenceLedgerEntry141) = Unit
        override suspend fun inferenceForBelief(campaignUid: EntityUid, holderUid: EntityUid, resultingBeliefUid: EntityUid) =
            record.takeIf { it.campaignUid == campaignUid && it.holderUid == holderUid && it.resultingBeliefUid == resultingBeliefUid }
    }

    private class Retractions(private val rows: List<NpcBeliefRetraction141>) : NpcBeliefRetractionStore141 {
        override suspend fun appendRetraction(record: NpcBeliefRetraction141) = Unit
        override suspend fun retractionsForHolder(campaignUid: EntityUid, holderUid: EntityUid, beforeOrAtTurn: Long) =
            rows.filter { it.campaignUid == campaignUid && it.holderUid == holderUid && it.turnId <= beforeOrAtTurn }
    }

    private class Repo(private val truths: MutableList<CampaignTruth>, private val turn: Long) : UnifiedCampaignRepository {
        override suspend fun currentTurnId(campaignUid: EntityUid) = turn
        override suspend fun writeTurn(turn: DurableTurnRecord) = Unit
        override suspend fun getEntityState(campaignUid: EntityUid, entityUid: EntityUid, entityType: String?) = emptyList<CampaignStateField>()
        override suspend fun getTruth(campaignUid: EntityUid, subjectUid: EntityUid, predicate: String, atTurnId: Long?) = truths.filter {
            it.subjectUid == subjectUid && it.predicate == predicate && (atTurnId == null || it.validFromTurn == null || it.validFromTurn <= atTurnId)
        }
        override suspend fun getBeliefs(campaignUid: EntityUid, holderUid: EntityUid, subjectUid: EntityUid?, atTurnId: Long?, limit: Int) = truths.filter {
            it.kind == TruthKind.BELIEF && it.holderUid == holderUid && (subjectUid == null || it.subjectUid == subjectUid) &&
                (atTurnId == null || it.validFromTurn == null || it.validFromTurn <= atTurnId)
        }.take(limit)
        override suspend fun recentEvents(campaignUid: EntityUid, beforeOrAtTurn: Long?, limit: Int) = emptyList<DurableCampaignEvent>()
        override suspend fun memories(campaignUid: EntityUid, subjectUid: EntityUid?, kinds: Set<DurableMemoryKind>, limit: Int) = emptyList<DurableMemoryRecord>()
        override suspend fun getActiveDivergences(campaignUid: EntityUid) = emptyList<CanonDivergence>()
        override suspend fun writeDivergence(divergence: CanonDivergence) = Unit
        override suspend fun appendEvent(event: DurableCampaignEvent) = Unit
        override suspend fun applyMutation(mutation: DurableStateMutation) = Unit
        override suspend fun writeTruth(truth: CampaignTruth) { truths += truth }
        override suspend fun writeMemory(memory: DurableMemoryRecord) = Unit
        override suspend fun writeChronicle(entry: DurableChronicleRecord) = Unit
        override suspend fun latestSnapshot(campaignUid: EntityUid): CampaignSnapshotRef? = null
        override suspend fun createSnapshot(campaignUid: EntityUid, throughTurnId: Long): CampaignSnapshotRef = error("unused")
        override suspend fun <T> inTransaction(block: suspend UnifiedCampaignRepository.() -> T): T = block(this)
    }
}
