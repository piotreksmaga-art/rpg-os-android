package com.rpgos.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NpcDecisionIsolation141Test {
    private val campaign = EntityUid("CAMPAIGN-isolation")
    private val npcA = EntityUid("NPC-A")
    private val npcB = EntityUid("NPC-B")
    private val subject = EntityUid("SUBJECT-secret")

    @Test
    fun gatewaySeesOnlyHolderScopedBeliefs() = runBlocking {
        val own = belief("BELIEF-A", npcA, "own")
        val foreign = belief("BELIEF-B", npcB, "foreign")
        val repo = FakeRepo(listOf(own, foreign))
        var sawForeign = false

        val service = NpcDecisionService141(repo, campaign) { context ->
            sawForeign = context.knowledge.accessibleTruths.any { it.uid == foreign.uid }
            NpcDecisionProposal141(
                intention = "WAIT",
                referencedTruthUids = setOf(own.uid)
            )
        }

        val result = service.decide(
            NpcDecisionRequest141(
                npcUid = npcA,
                turnId = 20,
                purpose = "choose next action"
            )
        )

        assertEquals("WAIT", result.intention)
        assertFalse(sawForeign)
    }

    @Test(expected = IllegalArgumentException::class)
    fun proposalCannotReferenceHiddenTruth() = runBlocking {
        val own = belief("BELIEF-A", npcA, "own")
        val hiddenFact = fact("FACT-HIDDEN", "hidden")
        val repo = FakeRepo(listOf(own, hiddenFact))
        val service = NpcDecisionService141(repo, campaign) {
            NpcDecisionProposal141(
                intention = "ACT_ON_SECRET",
                referencedTruthUids = setOf(hiddenFact.uid)
            )
        }

        service.decide(
            NpcDecisionRequest141(
                npcUid = npcA,
                turnId = 20,
                purpose = "choose next action"
            )
        )
        Unit
    }

    @Test
    fun explicitObservableFactMayBeReferenced() = runBlocking {
        val visibleFact = fact("FACT-VISIBLE", "visible")
        val repo = FakeRepo(listOf(visibleFact))
        val grant = NpcKnowledgeAccessPolicy141.Grant(
            holderUid = npcA,
            truthUid = visibleFact.uid,
            subjectUid = subject,
            predicate = visibleFact.predicate,
            kind = NpcKnowledgeAccessPolicy141.GrantKind.OBSERVABLE_FACT,
            validFromTurn = 20,
            validUntilTurn = 20
        )
        val service = NpcDecisionService141(repo, campaign) { context ->
            assertTrue(context.knowledge.canAccess(visibleFact.uid))
            NpcDecisionProposal141(
                intention = "RESPOND",
                dialogue = "Widzę to.",
                referencedTruthUids = setOf(visibleFact.uid)
            )
        }

        val result = service.decide(
            NpcDecisionRequest141(
                npcUid = npcA,
                turnId = 20,
                purpose = "dialogue",
                grants = listOf(grant)
            )
        )

        assertEquals("Widzę to.", result.dialogue)
    }

    private fun belief(uid: String, holder: EntityUid, value: String) = CampaignTruth(
        uid = EntityUid(uid),
        kind = TruthKind.BELIEF,
        subjectUid = subject,
        predicate = "secret.state",
        value = value,
        holderUid = holder,
        validFromTurn = 1,
        provenance = ProvenanceRecord(ProvenanceType.NPC_REPORT, EntityUid("EVENT-$uid"), 1, 0.8)
    )

    private fun fact(uid: String, value: String) = CampaignTruth(
        uid = EntityUid(uid),
        kind = TruthKind.FACT,
        subjectUid = subject,
        predicate = "secret.state",
        value = value,
        validFromTurn = 1,
        provenance = ProvenanceRecord(ProvenanceType.CAMPAIGN_EVENT, EntityUid("EVENT-$uid"), 1)
    )

    private class FakeRepo(private val truths: List<CampaignTruth>) : UnifiedCampaignRepository {
        override suspend fun currentTurnId(campaignUid: EntityUid): Long = 20
        override suspend fun writeTurn(turn: DurableTurnRecord) = Unit
        override suspend fun getEntityState(campaignUid: EntityUid, entityUid: EntityUid, entityType: String?): List<CampaignStateField> = emptyList()
        override suspend fun getTruth(campaignUid: EntityUid, subjectUid: EntityUid, predicate: String, atTurnId: Long?): List<CampaignTruth> = truths.filter {
            it.subjectUid == subjectUid && it.predicate == predicate &&
                (it.validFromTurn == null || atTurnId == null || it.validFromTurn <= atTurnId) &&
                (it.validUntilTurn == null || atTurnId == null || it.validUntilTurn >= atTurnId)
        }
        override suspend fun getBeliefs(campaignUid: EntityUid, holderUid: EntityUid, subjectUid: EntityUid?, atTurnId: Long?, limit: Int): List<CampaignTruth> = truths.filter {
            it.kind == TruthKind.BELIEF && it.holderUid == holderUid &&
                (subjectUid == null || it.subjectUid == subjectUid)
        }.take(limit)
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
