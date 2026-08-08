package com.rpgos.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NpcKnowledgeDiagnostics141Test {
    private val campaign = EntityUid("CAMPAIGN-diagnostics")
    private val npc = EntityUid("NPC-diagnostics")
    private val subject = EntityUid("SUBJECT-target")

    @Test
    fun reportSurfacesUnresolvedConflictWithoutInventingWinner() = runBlocking {
        val a = belief("BELIEF-A", "KONOHA")
        val b = belief("BELIEF-B", "KUMO")
        val repo = FakeRepo(listOf(a, b))
        val retractions = FakeRetractions()
        val policy = NpcKnowledgeAccessPolicy141(repo, campaign)
        val timeline = NpcBeliefTimeline141(repo, campaign, retractions)
        val explain = NpcKnowledgeExplain141(repo, campaign, retractions)
        val diagnostics = NpcKnowledgeDiagnostics141(policy, timeline, explain)

        val report = diagnostics.report(npc, atTurnId = 20)

        assertEquals(2, report.activeBeliefCount)
        assertEquals(1, report.unresolvedConflictCount)
        assertTrue(report.issues.any { it.code == "NPC_KNOWLEDGE_UNRESOLVED_CONFLICT" })
        assertTrue(report.ok)
    }

    @Test
    fun lineageCycleIsReportedAsError() = runBlocking {
        val a = belief("BELIEF-A", "KONOHA", sourceUid = EntityUid("BELIEF-B"))
        val b = belief("BELIEF-B", "KONOHA", sourceUid = EntityUid("BELIEF-A"))
        val repo = FakeRepo(listOf(a, b))
        val retractions = FakeRetractions()
        val diagnostics = NpcKnowledgeDiagnostics141(
            NpcKnowledgeAccessPolicy141(repo, campaign),
            NpcBeliefTimeline141(repo, campaign, retractions),
            NpcKnowledgeExplain141(repo, campaign, retractions)
        )

        val report = diagnostics.report(npc, atTurnId = 20)

        assertFalse(report.ok)
        assertTrue(report.issues.any { it.code == "NPC_KNOWLEDGE_LINEAGE_CYCLE" })
    }

    private fun belief(uid: String, value: String, sourceUid: EntityUid? = null) = CampaignTruth(
        uid = EntityUid(uid),
        kind = TruthKind.BELIEF,
        subjectUid = subject,
        predicate = "target.location",
        value = value,
        holderUid = npc,
        validFromTurn = 10,
        provenance = ProvenanceRecord(
            type = ProvenanceType.NPC_REPORT,
            sourceUid = sourceUid,
            turnId = 10,
            confidence = 0.7
        )
    )

    private class FakeRetractions : NpcBeliefRetractionStore141 {
        override suspend fun appendRetraction(record: NpcBeliefRetraction141) = Unit
        override suspend fun retractionsForHolder(
            campaignUid: EntityUid,
            holderUid: EntityUid,
            beforeOrAtTurn: Long
        ): List<NpcBeliefRetraction141> = emptyList()
    }

    private class FakeRepo(private val truths: List<CampaignTruth>) : UnifiedCampaignRepository {
        override suspend fun currentTurnId(campaignUid: EntityUid): Long = 20L
        override suspend fun writeTurn(turn: DurableTurnRecord) = Unit
        override suspend fun getEntityState(campaignUid: EntityUid, entityUid: EntityUid, entityType: String?): List<CampaignStateField> = emptyList()
        override suspend fun getTruth(campaignUid: EntityUid, subjectUid: EntityUid, predicate: String, atTurnId: Long?): List<CampaignTruth> =
            truths.filter { it.subjectUid == subjectUid && it.predicate == predicate }
        override suspend fun getBeliefs(campaignUid: EntityUid, holderUid: EntityUid, subjectUid: EntityUid?, atTurnId: Long?, limit: Int): List<CampaignTruth> =
            truths.filter { it.kind == TruthKind.BELIEF && it.holderUid == holderUid && (subjectUid == null || it.subjectUid == subjectUid) }.take(limit)
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
