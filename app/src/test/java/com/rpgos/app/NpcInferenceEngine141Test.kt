package com.rpgos.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NpcInferenceEngine141Test {
    private val campaign = EntityUid("CAMPAIGN-inference")
    private val npc = EntityUid("NPC-inference")
    private val subject = EntityUid("SUBJECT-target")

    @Test(expected = IllegalArgumentException::class)
    fun hiddenPremiseIsRejected() {
        val visible = belief("BELIEF-visible", 0.9)
        val view = view(listOf(visible))
        NpcInferenceEngine141().infer(
            NpcInferenceEngine141.Request(
                view = view,
                subjectUid = subject,
                predicate = "target.intent",
                value = "hostile",
                premiseTruthUids = listOf(EntityUid("FACT-hidden"))
            )
        )
    }

    @Test
    fun confidenceIsBoundedByWeakestPremise() {
        val first = belief("BELIEF-a", 0.9)
        val second = belief("BELIEF-b", 0.5)
        val result = NpcInferenceEngine141 { EntityUid("BELIEF-result") }.infer(
            NpcInferenceEngine141.Request(
                view = view(listOf(first, second)),
                subjectUid = subject,
                predicate = "target.intent",
                value = "hostile",
                premiseTruthUids = listOf(first.uid, second.uid),
                confidenceMultiplier = 0.8
            )
        )

        assertEquals(0.4, result.belief.provenance.confidence, 0.000001)
        assertEquals(ProvenanceType.NPC_INFERENCE, result.belief.provenance.type)
        assertEquals(npc, result.belief.holderUid)
        assertEquals(listOf(first.uid, second.uid), result.premiseTruths.map { it.uid })
    }

    @Test
    fun promotionWritesBeliefAndFullPremiseLedger() = runBlocking {
        val first = belief("BELIEF-a", 0.9)
        val second = belief("BELIEF-b", 0.7)
        val repo = FakeRepo(mutableListOf(first, second))
        val store = FakeInferenceStore()
        val promoter = NpcInferencePromoter141(
            repository = repo,
            campaignUid = campaign,
            inferenceStore = store,
            engine = NpcInferenceEngine141 { EntityUid("BELIEF-result") },
            ledgerUidFactory = { EntityUid("INFER-1") }
        )

        val result = promoter.promote(
            NpcInferenceEngine141.Request(
                view = view(listOf(first, second)),
                subjectUid = subject,
                predicate = "target.intent",
                value = "hostile",
                premiseTruthUids = listOf(first.uid, second.uid)
            )
        )

        assertFalse(result.duplicate)
        assertEquals(EntityUid("BELIEF-result"), result.createdBelief?.uid)
        assertEquals(1, store.records.size)
        assertEquals(listOf(first.uid, second.uid), store.records.single().premiseTruthUids)
        assertTrue(repo.truths.any { it.uid == EntityUid("BELIEF-result") })
    }

    @Test
    fun duplicateActiveInferenceIsNotWrittenAgain() = runBlocking {
        val premise = belief("BELIEF-a", 0.9)
        val existing = CampaignTruth(
            uid = EntityUid("BELIEF-existing"),
            kind = TruthKind.BELIEF,
            subjectUid = subject,
            predicate = "target.intent",
            value = "hostile",
            holderUid = npc,
            validFromTurn = 10,
            provenance = ProvenanceRecord(ProvenanceType.NPC_INFERENCE, premise.uid, 10, 0.5)
        )
        val repo = FakeRepo(mutableListOf(premise, existing))
        val store = FakeInferenceStore()
        val promoter = NpcInferencePromoter141(repo, campaign, store)

        val result = promoter.promote(
            NpcInferenceEngine141.Request(
                view = view(listOf(premise, existing)),
                subjectUid = subject,
                predicate = "target.intent",
                value = "hostile",
                premiseTruthUids = listOf(premise.uid)
            )
        )

        assertTrue(result.duplicate)
        assertNull(result.createdBelief)
        assertTrue(store.records.isEmpty())
    }

    private fun belief(uid: String, confidence: Double) = CampaignTruth(
        uid = EntityUid(uid),
        kind = TruthKind.BELIEF,
        subjectUid = subject,
        predicate = "premise.$uid",
        value = "true",
        holderUid = npc,
        validFromTurn = 1,
        provenance = ProvenanceRecord(ProvenanceType.NPC_OBSERVATION, EntityUid("FACT-$uid"), 1, confidence)
    )

    private fun view(truths: List<CampaignTruth>) = NpcKnowledgeAccessPolicy141.View(
        holderUid = npc,
        atTurnId = 20,
        beliefs = truths.filter { it.kind == TruthKind.BELIEF },
        observableFacts = truths.filter { it.kind == TruthKind.FACT },
        organizationFacts = emptyList(),
        deniedGrants = emptyList()
    )

    private class FakeInferenceStore : NpcInferenceStore141 {
        val records = mutableListOf<NpcInferenceLedgerEntry141>()
        override suspend fun appendInference(record: NpcInferenceLedgerEntry141) { records += record }
    }

    private class FakeRepo(val truths: MutableList<CampaignTruth>) : UnifiedCampaignRepository {
        override suspend fun currentTurnId(campaignUid: EntityUid): Long = 20
        override suspend fun writeTurn(turn: DurableTurnRecord) = Unit
        override suspend fun getEntityState(campaignUid: EntityUid, entityUid: EntityUid, entityType: String?): List<CampaignStateField> = emptyList()
        override suspend fun getTruth(campaignUid: EntityUid, subjectUid: EntityUid, predicate: String, atTurnId: Long?): List<CampaignTruth> = truths.filter {
            it.subjectUid == subjectUid && it.predicate == predicate
        }
        override suspend fun getBeliefs(campaignUid: EntityUid, holderUid: EntityUid, subjectUid: EntityUid?, atTurnId: Long?, limit: Int): List<CampaignTruth> = truths.filter {
            it.kind == TruthKind.BELIEF && it.holderUid == holderUid &&
                (subjectUid == null || it.subjectUid == subjectUid) &&
                (it.validFromTurn == null || atTurnId == null || it.validFromTurn <= atTurnId) &&
                (it.validUntilTurn == null || atTurnId == null || it.validUntilTurn >= atTurnId)
        }.take(limit)
        override suspend fun recentEvents(campaignUid: EntityUid, beforeOrAtTurn: Long?, limit: Int): List<DurableCampaignEvent> = emptyList()
        override suspend fun memories(campaignUid: EntityUid, subjectUid: EntityUid?, kinds: Set<DurableMemoryKind>, limit: Int): List<DurableMemoryRecord> = emptyList()
        override suspend fun getActiveDivergences(campaignUid: EntityUid): List<CanonDivergence> = emptyList()
        override suspend fun writeDivergence(divergence: CanonDivergence) = Unit
        override suspend fun appendEvent(event: DurableCampaignEvent) = Unit
        override suspend fun applyMutation(mutation: DurableStateMutation) = Unit
        override suspend fun writeTruth(truth: CampaignTruth) { truths += truth }
        override suspend fun writeMemory(memory: DurableMemoryRecord) = Unit
        override suspend fun writeChronicle(entry: DurableChronicleRecord) = Unit
        override suspend fun latestSnapshot(campaignUid: EntityUid): CampaignSnapshotRef? = null
        override suspend fun createSnapshot(campaignUid: EntityUid, throughTurnId: Long): CampaignSnapshotRef = error("not used")
        override suspend fun <T> inTransaction(block: suspend UnifiedCampaignRepository.() -> T): T = block(this)
    }
}
