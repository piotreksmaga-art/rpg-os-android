package com.rpgos.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameMasterRetriever141Test {
    private val campaign = EntityUid("CAMPAIGN-retriever-test")
    private val npcA = EntityUid("NPC-A")
    private val npcB = EntityUid("NPC-B")

    @Test
    fun futureMemoriesAreRejected() = runBlocking {
        val repo = RetrievalFakeRepository(
            memoryRows = listOf(
                memory("past", 8L, 0.4),
                memory("future", 11L, 1.0)
            )
        )
        val result = GameMasterRetriever141(repo, campaign).retrieve(
            playerAction = "past future",
            atTurnId = 10L,
            relevantNpcUids = emptyList(),
            memoryLimit = 10
        )
        assertEquals(listOf("past"), result.memories.map { it.text })
    }

    @Test
    fun beliefsNeverLeakBetweenHolders() = runBlocking {
        val repo = RetrievalFakeRepository(
            beliefs = mapOf(
                npcA to listOf(belief(npcA, "sekret A"), belief(npcB, "leak B")),
                npcB to listOf(belief(npcB, "sekret B"))
            )
        )
        val result = GameMasterRetriever141(repo, campaign).retrieve(
            playerAction = "sekret",
            atTurnId = 10L,
            relevantNpcUids = listOf(npcA, npcB),
            beliefLimitPerNpc = 10
        )
        assertTrue(result.beliefsByHolder[npcA].orEmpty().all { it.holderUid == npcA })
        assertTrue(result.beliefsByHolder[npcB].orEmpty().all { it.holderUid == npcB })
        assertFalse(result.beliefsByHolder[npcA].orEmpty().any { it.value == "leak B" })
    }

    @Test
    fun retrievalHonorsHardResultLimits() = runBlocking {
        val repo = RetrievalFakeRepository(
            eventRows = (1L..80L).map { event("event-$it", it) },
            memoryRows = (1L..80L).map { memory("memory-$it", it, 0.5) },
            beliefs = mapOf(npcA to (1..40).map { belief(npcA, "belief-$it") })
        )
        val result = GameMasterRetriever141(repo, campaign).retrieve(
            playerAction = "event memory belief",
            atTurnId = 100L,
            relevantNpcUids = listOf(npcA),
            eventLimit = 7,
            memoryLimit = 5,
            beliefLimitPerNpc = 3
        )
        assertEquals(7, result.events.size)
        assertEquals(5, result.memories.size)
        assertEquals(3, result.beliefsByHolder[npcA].orEmpty().size)
    }

    private fun event(text: String, turn: Long) = DurableCampaignEvent(
        eventUid = EntityUid("EVENT-$turn"),
        campaignUid = campaign,
        turnId = turn,
        sequence = 0,
        type = DurableEventType.WORLD_EVENT,
        actorUid = null,
        targetUid = null,
        causeEventUid = null,
        description = text,
        payloadJson = "{}"
    )

    private fun memory(text: String, turn: Long, importance: Double) = DurableMemoryRecord(
        memoryUid = EntityUid("MEMORY-$text-$turn"),
        campaignUid = campaign,
        kind = DurableMemoryKind.EPISODIC,
        subjectUid = null,
        text = text,
        importance = importance,
        createdTurn = turn,
        sourceEventUids = emptyList(),
        tags = emptySet()
    )

    private fun belief(holder: EntityUid, value: String) = CampaignTruth(
        uid = EntityUid("BELIEF-${holder.value}-${value.hashCode()}"),
        campaignUid = campaign,
        kind = TruthKind.BELIEF,
        holderUid = holder,
        subjectUid = EntityUid("SUBJECT"),
        predicate = "knows",
        value = value,
        validFromTurn = 1L,
        validUntilTurn = null,
        provenance = Provenance(ProvenanceType.NPC_REPORT, null, 1L, 0.8)
    )

    private class RetrievalFakeRepository(
        private val eventRows: List<DurableCampaignEvent> = emptyList(),
        private val memoryRows: List<DurableMemoryRecord> = emptyList(),
        private val beliefs: Map<EntityUid, List<CampaignTruth>> = emptyMap()
    ) : UnifiedCampaignRepository {
        override suspend fun currentTurnId(campaignUid: EntityUid): Long = 0L
        override suspend fun writeTurn(turn: DurableTurnRecord) = Unit
        override suspend fun getEntityState(campaignUid: EntityUid, entityUid: EntityUid, entityType: String?): List<CampaignStateField> = emptyList()
        override suspend fun getTruth(campaignUid: EntityUid, subjectUid: EntityUid, predicate: String, atTurnId: Long?): List<CampaignTruth> = emptyList()
        override suspend fun getBeliefs(campaignUid: EntityUid, holderUid: EntityUid, subjectUid: EntityUid?, atTurnId: Long?, limit: Int): List<CampaignTruth> = beliefs[holderUid].orEmpty().take(limit)
        override suspend fun recentEvents(campaignUid: EntityUid, beforeOrAtTurn: Long?, limit: Int): List<DurableCampaignEvent> = eventRows.takeLast(limit)
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
