package com.rpgos.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeLineageResolver141Test {
    private val campaign = EntityUid("CAMPAIGN-lineage")
    private val subject = EntityUid("SUBJECT-secret")

    @Test
    fun resolvesAtoBtoCBackToOriginalSource() = runBlocking {
        val a = truth("BELIEF-A", holder = "NPC-A", source = "EVENT-ORIGINAL", turn = 10)
        val b = truth("BELIEF-B", holder = "NPC-B", source = "BELIEF-A", turn = 11)
        val c = truth("BELIEF-C", holder = "NPC-C", source = "BELIEF-B", turn = 12)
        val repo = LineageFakeRepository(listOf(a, b, c))

        val result = KnowledgeLineageResolver141(repo, campaign).resolve(c)

        assertEquals(listOf("BELIEF-C", "BELIEF-B", "BELIEF-A"), result.chain.map { it.uid.value })
        assertEquals(EntityUid("EVENT-ORIGINAL"), result.terminalSourceUid)
        assertFalse(result.cycleDetected)
        assertFalse(result.truncated)
    }

    @Test
    fun detectsCycleWithoutLoopingForever() = runBlocking {
        val a = truth("BELIEF-A", holder = "NPC-A", source = "BELIEF-B", turn = 10)
        val b = truth("BELIEF-B", holder = "NPC-B", source = "BELIEF-A", turn = 10)
        val repo = LineageFakeRepository(listOf(a, b))

        val result = KnowledgeLineageResolver141(repo, campaign).resolve(a)

        assertTrue(result.cycleDetected)
        assertFalse(result.truncated)
        assertTrue(result.chain.size <= 3)
    }

    private fun truth(
        uid: String,
        holder: String,
        source: String,
        turn: Long
    ) = CampaignTruth(
        uid = EntityUid(uid),
        kind = TruthKind.BELIEF,
        subjectUid = subject,
        predicate = "secret.location",
        value = "KUMO",
        holderUid = EntityUid(holder),
        validFromTurn = turn,
        provenance = ProvenanceRecord(
            type = ProvenanceType.NPC_REPORT,
            sourceUid = EntityUid(source),
            turnId = turn,
            confidence = 0.8
        )
    )

    private class LineageFakeRepository(
        private val truths: List<CampaignTruth>
    ) : UnifiedCampaignRepository {
        override suspend fun currentTurnId(campaignUid: EntityUid): Long = 100L
        override suspend fun writeTurn(turn: DurableTurnRecord) = Unit
        override suspend fun getEntityState(campaignUid: EntityUid, entityUid: EntityUid, entityType: String?): List<CampaignStateField> = emptyList()
        override suspend fun getTruth(campaignUid: EntityUid, subjectUid: EntityUid, predicate: String, atTurnId: Long?): List<CampaignTruth> =
            truths.filter {
                it.subjectUid == subjectUid &&
                    it.predicate == predicate &&
                    (it.validFromTurn == null || atTurnId == null || it.validFromTurn <= atTurnId) &&
                    (it.validUntilTurn == null || atTurnId == null || it.validUntilTurn >= atTurnId)
            }
        override suspend fun getBeliefs(campaignUid: EntityUid, holderUid: EntityUid, subjectUid: EntityUid?, atTurnId: Long?, limit: Int): List<CampaignTruth> = emptyList()
        override suspend fun recentEvents(campaignUid: EntityUid, beforeOrAtTurn: Long?, limit: Int): List<DurableCampaignEvent> = emptyList()
        override suspend fun memories(campaignUid: EntityUid, subjectUid: EntityUid?, kinds: Set<DurableMemoryKind>, limit: Int): List<DurableMemoryRecord> = emptyList()
        override suspend fun getActiveDivergences(campaignUid: EntityUid): List<CanonDivergence> = emptyList()
        override suspend fun writeDivergence(divergence: CanonDivergence) = Unit
        override suspend fun appendEvent(event: DurableCampaignEvent) = Unit
        override suspend fun applyMutation(mutation: DurableStateMutation) = Unit
        override suspend fun writeTruth(truth: CampaignTruth) = Unit
        override suspend fun writeMemory(memory: DurableMemoryRecord) = Unit
        override suspend fun writeChronicle(entry: DurableChronicleRecord) = Unit
        override suspend fun latestSnapshot(campaignUid: EntityUid): CampaignSnapshotRef? = null
        override suspend fun createSnapshot(campaignUid: EntityUid, throughTurnId: Long): CampaignSnapshotRef = error("not used")
        override suspend fun <T> inTransaction(block: suspend UnifiedCampaignRepository.() -> T): T = block(this)
    }
}
