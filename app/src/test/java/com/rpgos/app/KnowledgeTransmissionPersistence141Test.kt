package com.rpgos.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeTransmissionPersistence141Test {
    private val campaign = EntityUid("CAMPAIGN-knowledge-persistence")

    @Test
    fun beliefAndTransmissionShareResultingBeliefUid() = runBlocking {
        val repo = PersistenceFakeRepository()
        val ledger = RecordingKnowledgeStore(repo)
        val state = GameMasterStateRepository141(repo, campaign, ledger)

        state.commitTurn(
            request = request(),
            context = context(),
            result = GameMasterTurnResult(
                narrative = "NPC otrzymuje informację.",
                truthWrites = listOf(
                    TruthWrite(
                        kind = TruthKind.BELIEF,
                        subjectId = "SUBJECT-X",
                        predicate = "location",
                        value = "KUMO",
                        holderId = "NPC-B",
                        confidence = 0.8,
                        sourceType = ProvenanceType.NPC_REPORT,
                        sourceId = "BELIEF-SOURCE-A",
                        validFromTurn = 1L,
                        knowledgeChannel = KnowledgeChannel141.REPORT,
                        sourceNpcId = "NPC-A"
                    )
                )
            )
        )

        assertNotNull(repo.lastTruth)
        assertNotNull(ledger.last)
        val durableBelief = repo.lastTruth!!
        val transmission = ledger.last!!
        assertEquals(TruthKind.BELIEF, durableBelief.kind)
        assertEquals(durableBelief.uid, transmission.resultingBeliefUid)
        assertEquals(EntityUid("BELIEF-SOURCE-A"), transmission.sourceTruthUid)
        assertEquals(EntityUid("NPC-A"), transmission.sourceNpcUid)
        assertEquals(EntityUid("NPC-B"), transmission.receiverUid)
        assertEquals(KnowledgeChannel141.REPORT, transmission.channel)
        assertTrue(repo.transactionEntered)
        assertTrue(ledger.calledWhileRepositoryTransactionActive)
    }

    private fun request() = GameMasterTurnRequest(
        campaignId = campaign.value,
        worldPackId = "WORLD-test",
        playerAction = "NPC A przekazuje raport NPC B.",
        currentChapter = 1L
    )

    private fun context() = GameMasterContext(
        campaignId = campaign.value,
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

    private fun section(name: String) = ContextSection(name, "{}", 1)

    private class PersistenceFakeRepository : UnifiedCampaignRepository {
        var turn = 0L
        var transactionEntered = false
        var transactionActive = false
        var lastTruth: CampaignTruth? = null

        override suspend fun currentTurnId(campaignUid: EntityUid): Long = turn
        override suspend fun writeTurn(turn: DurableTurnRecord) { this.turn = turn.turnId }
        override suspend fun getEntityState(campaignUid: EntityUid, entityUid: EntityUid, entityType: String?): List<CampaignStateField> = emptyList()
        override suspend fun getTruth(campaignUid: EntityUid, subjectUid: EntityUid, predicate: String, atTurnId: Long?): List<CampaignTruth> = emptyList()
        override suspend fun getBeliefs(campaignUid: EntityUid, holderUid: EntityUid, subjectUid: EntityUid?, atTurnId: Long?, limit: Int): List<CampaignTruth> = emptyList()
        override suspend fun recentEvents(campaignUid: EntityUid, beforeOrAtTurn: Long?, limit: Int): List<DurableCampaignEvent> = emptyList()
        override suspend fun memories(campaignUid: EntityUid, subjectUid: EntityUid?, kinds: Set<DurableMemoryKind>, limit: Int): List<DurableMemoryRecord> = emptyList()
        override suspend fun getActiveDivergences(campaignUid: EntityUid): List<CanonDivergence> = emptyList()
        override suspend fun writeDivergence(divergence: CanonDivergence) = Unit
        override suspend fun appendEvent(event: DurableCampaignEvent) = Unit
        override suspend fun applyMutation(mutation: DurableStateMutation) = Unit
        override suspend fun writeTruth(truth: CampaignTruth) { lastTruth = truth }
        override suspend fun writeMemory(memory: DurableMemoryRecord) = Unit
        override suspend fun writeChronicle(entry: DurableChronicleRecord) = Unit
        override suspend fun latestSnapshot(campaignUid: EntityUid): CampaignSnapshotRef? = null
        override suspend fun createSnapshot(campaignUid: EntityUid, throughTurnId: Long): CampaignSnapshotRef = error("not used")

        override suspend fun <T> inTransaction(block: suspend UnifiedCampaignRepository.() -> T): T {
            transactionEntered = true
            transactionActive = true
            return try { block(this) } finally { transactionActive = false }
        }
    }

    private class RecordingKnowledgeStore(
        private val repo: PersistenceFakeRepository
    ) : KnowledgeTransmissionStore141 {
        var last: KnowledgeTransmission141? = null
        var calledWhileRepositoryTransactionActive = false

        override suspend fun appendKnowledgeTransmission(record: KnowledgeTransmission141) {
            calledWhileRepositoryTransactionActive = repo.transactionActive
            last = record
        }

        override suspend fun knowledgeTransmissionsForReceiver(
            campaignUid: EntityUid,
            receiverUid: EntityUid,
            beforeOrAtTurn: Long?,
            limit: Int
        ): List<KnowledgeTransmission141> = listOfNotNull(last)
    }
}
