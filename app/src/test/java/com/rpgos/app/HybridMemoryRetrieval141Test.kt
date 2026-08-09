package com.rpgos.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridMemoryRetrieval141Test {
    private val campaign = EntityUid("CAMPAIGN-hybrid-test")

    @Test
    fun hybridCandidateOutsideLexicalPoolCanBeRetrieved() = runBlocking {
        val candidate = memory("odlegla kuznia", 5L, DurableMemoryKind.EPISODIC)
        val result = GameMasterRetriever141(
            repository = FakeRepository(),
            campaignUid = campaign,
            hybridMemoryProvider = HybridMemoryCandidateProvider141 { _, _, _, _ ->
                listOf(HybridMemoryCandidate141(candidate, 0.95))
            }
        ).retrieve(
            playerAction = "miejsce wykuwania mieczy",
            atTurnId = 10L,
            relevantNpcUids = emptyList(),
            memoryLimit = 5
        )

        assertEquals(listOf(candidate.memoryUid), result.memories.map { it.memoryUid })
    }

    @Test
    fun hybridProviderCannotInjectFutureOrForeignCampaignRows() = runBlocking {
        val valid = memory("valid", 5L, DurableMemoryKind.EPISODIC)
        val future = memory("future", 11L, DurableMemoryKind.EPISODIC)
        val foreign = DurableMemoryRecord(
            memoryUid = EntityUid("MEM-foreign"),
            campaignUid = EntityUid("CAMPAIGN-other"),
            kind = DurableMemoryKind.EPISODIC,
            subjectUid = null,
            text = "foreign",
            importance = 1.0,
            createdTurn = 5L,
            sourceEventUids = emptySet(),
            tags = emptySet()
        )

        val result = GameMasterRetriever141(
            repository = FakeRepository(),
            campaignUid = campaign,
            hybridMemoryProvider = HybridMemoryCandidateProvider141 { _, _, _, _ ->
                listOf(
                    HybridMemoryCandidate141(valid, 0.5),
                    HybridMemoryCandidate141(future, 1.0),
                    HybridMemoryCandidate141(foreign, 1.0)
                )
            }
        ).retrieve("query", 10L, emptyList(), memoryLimit = 10)

        assertEquals(listOf(valid.memoryUid), result.memories.map { it.memoryUid })
    }

    @Test
    fun semanticCandidateStillRequiresTemporalFactEligibility() = runBlocking {
        val allowed = memory("allowed semantic", 5L, DurableMemoryKind.SEMANTIC)
        val blocked = memory("blocked semantic", 5L, DurableMemoryKind.SEMANTIC)
        val eligibility = object : SemanticMemoryTemporalEligibility141 {
            override fun isEligible(memoryUid: EntityUid, atTurnId: Long): Boolean =
                memoryUid == allowed.memoryUid
        }

        val result = GameMasterRetriever141(
            repository = FakeRepository(),
            campaignUid = campaign,
            semanticEligibility = eligibility,
            hybridMemoryProvider = HybridMemoryCandidateProvider141 { _, _, _, _ ->
                listOf(
                    HybridMemoryCandidate141(blocked, 1.0),
                    HybridMemoryCandidate141(allowed, 0.2)
                )
            }
        ).retrieve("semantic", 10L, emptyList(), memoryLimit = 10)

        assertTrue(result.memories.any { it.memoryUid == allowed.memoryUid })
        assertFalse(result.memories.any { it.memoryUid == blocked.memoryUid })
    }

    @Test
    fun providerFailureFallsBackToLexicalPool() = runBlocking {
        val lexical = memory("smoczy miecz", 6L, DurableMemoryKind.EPISODIC)
        val result = GameMasterRetriever141(
            repository = FakeRepository(memoryRows = listOf(lexical)),
            campaignUid = campaign,
            hybridMemoryProvider = HybridMemoryCandidateProvider141 { _, _, _, _ ->
                error("embedding provider unavailable")
            }
        ).retrieve("smoczy miecz", 10L, emptyList(), memoryLimit = 5)

        assertEquals(listOf(lexical.memoryUid), result.memories.map { it.memoryUid })
    }

    private fun memory(text: String, turn: Long, kind: DurableMemoryKind) = DurableMemoryRecord(
        memoryUid = EntityUid("MEM-${text.replace(' ', '-')}-${kind.name}"),
        campaignUid = campaign,
        kind = kind,
        subjectUid = null,
        text = text,
        importance = 0.8,
        createdTurn = turn,
        sourceEventUids = if (kind == DurableMemoryKind.EPISODIC) setOf(EntityUid("EVENT-$turn")) else emptySet(),
        tags = emptySet()
    )

    private class FakeRepository(
        private val memoryRows: List<DurableMemoryRecord> = emptyList()
    ) : UnifiedCampaignRepository {
        override suspend fun currentTurnId(campaignUid: EntityUid): Long = 0L
        override suspend fun writeTurn(turn: DurableTurnRecord) = Unit
        override suspend fun getEntityState(campaignUid: EntityUid, entityUid: EntityUid, entityType: String?): List<CampaignStateField> = emptyList()
        override suspend fun getTruth(campaignUid: EntityUid, subjectUid: EntityUid, predicate: String, atTurnId: Long?): List<CampaignTruth> = emptyList()
        override suspend fun getBeliefs(campaignUid: EntityUid, holderUid: EntityUid, subjectUid: EntityUid?, atTurnId: Long?, limit: Int): List<CampaignTruth> = emptyList()
        override suspend fun recentEvents(campaignUid: EntityUid, beforeOrAtTurn: Long?, limit: Int): List<DurableCampaignEvent> = emptyList()
        override suspend fun memories(campaignUid: EntityUid, subjectUid: EntityUid?, kinds: Set<DurableMemoryKind>, limit: Int): List<DurableMemoryRecord> = memoryRows.takeLast(limit)
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
