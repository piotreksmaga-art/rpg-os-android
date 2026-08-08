package com.rpgos.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NpcKnowledgeAccessPolicy141Test {
    private val campaign = EntityUid("CAMPAIGN-access")
    private val npcA = EntityUid("NPC-A")
    private val npcB = EntityUid("NPC-B")
    private val subject = EntityUid("SUBJECT-secret")

    @Test
    fun holderSeesOwnBeliefsButNotAnotherNpcsBeliefs() = runBlocking {
        val aBelief = belief("BELIEF-A", npcA, "KUMO")
        val bBelief = belief("BELIEF-B", npcB, "KONOHA")
        val repo = AccessFakeRepository(listOf(aBelief, bBelief))

        val view = NpcKnowledgeAccessPolicy141(repo, campaign).buildView(npcB, atTurnId = 20)

        assertEquals(listOf("BELIEF-B"), view.beliefs.map { it.uid.value })
        assertTrue(view.canAccess(EntityUid("BELIEF-B")))
        assertFalse(view.canAccess(EntityUid("BELIEF-A")))
    }

    @Test
    fun objectiveFactIsInvisibleWithoutExplicitGrant() = runBlocking {
        val fact = fact("FACT-LOCATION", "KUMO")
        val repo = AccessFakeRepository(listOf(fact))

        val view = NpcKnowledgeAccessPolicy141(repo, campaign).buildView(npcA, atTurnId = 20)

        assertFalse(view.canAccess(fact.uid))
        assertTrue(view.observableFacts.isEmpty())
        assertTrue(view.organizationFacts.isEmpty())
    }

    @Test
    fun explicitObservableAndOrganizationGrantsExposeOnlyTheirFacts() = runBlocking {
        val visible = fact("FACT-VISIBLE", "VISIBLE")
        val organization = fact("FACT-ORG", "CLASSIFIED")
        val hidden = fact("FACT-HIDDEN", "HIDDEN")
        val repo = AccessFakeRepository(listOf(visible, organization, hidden))
        val policy = NpcKnowledgeAccessPolicy141(repo, campaign)

        val view = policy.buildView(
            holderUid = npcA,
            atTurnId = 20,
            grants = listOf(
                grant(npcA, visible, NpcKnowledgeAccessPolicy141.GrantKind.OBSERVABLE_FACT),
                grant(npcA, organization, NpcKnowledgeAccessPolicy141.GrantKind.ORGANIZATION_FACT)
            )
        )

        assertEquals(listOf("FACT-VISIBLE"), view.observableFacts.map { it.uid.value })
        assertEquals(listOf("FACT-ORG"), view.organizationFacts.map { it.uid.value })
        assertTrue(view.canAccess(visible.uid))
        assertTrue(view.canAccess(organization.uid))
        assertFalse(view.canAccess(hidden.uid))
        assertTrue(view.deniedGrants.isEmpty())
    }

    @Test
    fun expiredGrantAndBeliefSourceGrantFailClosed() = runBlocking {
        val fact = fact("FACT-OLD", "OLD")
        val otherBelief = belief("BELIEF-A", npcA, "RUMOUR")
        val repo = AccessFakeRepository(listOf(fact, otherBelief))
        val policy = NpcKnowledgeAccessPolicy141(repo, campaign)

        val view = policy.buildView(
            holderUid = npcB,
            atTurnId = 20,
            grants = listOf(
                grant(
                    npcB,
                    fact,
                    NpcKnowledgeAccessPolicy141.GrantKind.ORGANIZATION_FACT,
                    validUntilTurn = 19
                ),
                grant(npcB, otherBelief, NpcKnowledgeAccessPolicy141.GrantKind.OBSERVABLE_FACT)
            )
        )

        assertFalse(view.canAccess(fact.uid))
        assertFalse(view.canAccess(otherBelief.uid))
        assertEquals(
            listOf(
                NpcKnowledgeAccessPolicy141.DenialReason.EXPIRED,
                NpcKnowledgeAccessPolicy141.DenialReason.SOURCE_NOT_FACT
            ),
            view.deniedGrants.map { it.reason }
        )
    }

    private fun fact(uid: String, value: String) = CampaignTruth(
        uid = EntityUid(uid),
        kind = TruthKind.FACT,
        subjectUid = subject,
        predicate = "secret.location",
        value = value,
        validFromTurn = 1,
        provenance = ProvenanceRecord(
            type = ProvenanceType.CAMPAIGN_EVENT,
            sourceUid = EntityUid("EVENT-$uid"),
            turnId = 1,
            confidence = 1.0,
            verified = true
        )
    )

    private fun belief(uid: String, holder: EntityUid, value: String) = CampaignTruth(
        uid = EntityUid(uid),
        kind = TruthKind.BELIEF,
        subjectUid = subject,
        predicate = "secret.location",
        value = value,
        holderUid = holder,
        validFromTurn = 2,
        provenance = ProvenanceRecord(
            type = ProvenanceType.NPC_REPORT,
            sourceUid = EntityUid("SOURCE-$uid"),
            turnId = 2,
            confidence = 0.7
        )
    )

    private fun grant(
        holder: EntityUid,
        truth: CampaignTruth,
        kind: NpcKnowledgeAccessPolicy141.GrantKind,
        validUntilTurn: Long? = null
    ) = NpcKnowledgeAccessPolicy141.Grant(
        holderUid = holder,
        truthUid = truth.uid,
        subjectUid = requireNotNull(truth.subjectUid),
        predicate = truth.predicate,
        kind = kind,
        grantedByUid = EntityUid("SYSTEM-GRANT"),
        validFromTurn = 1,
        validUntilTurn = validUntilTurn
    )

    private class AccessFakeRepository(
        private val truths: List<CampaignTruth>
    ) : UnifiedCampaignRepository {
        override suspend fun currentTurnId(campaignUid: EntityUid): Long = 20L
        override suspend fun writeTurn(turn: DurableTurnRecord) = Unit
        override suspend fun getEntityState(campaignUid: EntityUid, entityUid: EntityUid, entityType: String?): List<CampaignStateField> = emptyList()
        override suspend fun getTruth(campaignUid: EntityUid, subjectUid: EntityUid, predicate: String, atTurnId: Long?): List<CampaignTruth> =
            truths.filter {
                it.subjectUid == subjectUid &&
                    it.predicate == predicate &&
                    (it.validFromTurn == null || atTurnId == null || it.validFromTurn <= atTurnId) &&
                    (it.validUntilTurn == null || atTurnId == null || it.validUntilTurn >= atTurnId)
            }

        override suspend fun getBeliefs(campaignUid: EntityUid, holderUid: EntityUid, subjectUid: EntityUid?, atTurnId: Long?, limit: Int): List<CampaignTruth> =
            truths.asSequence()
                .filter { it.kind == TruthKind.BELIEF && it.holderUid == holderUid }
                .filter { subjectUid == null || it.subjectUid == subjectUid }
                .filter { it.validFromTurn == null || atTurnId == null || it.validFromTurn <= atTurnId }
                .filter { it.validUntilTurn == null || atTurnId == null || it.validUntilTurn >= atTurnId }
                .take(limit)
                .toList()

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
