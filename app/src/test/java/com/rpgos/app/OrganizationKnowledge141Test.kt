package com.rpgos.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizationKnowledge141Test {
    private val campaign = EntityUid("CAMPAIGN-org")
    private val npc = EntityUid("NPC-member")
    private val org = EntityUid("ORG-konoha")
    private val subject = EntityUid("SUBJECT-secret")

    @Test
    fun activeMembershipAndClearanceProducesOrganizationGrant() {
        val membership = membership(clearance = 2)
        val publication = publication(minimumClearance = 2)

        val result = OrganizationKnowledgeGrantResolver141().resolve(
            holderUid = npc,
            turnId = 20,
            memberships = listOf(membership),
            publications = listOf(publication)
        )

        assertEquals(1, result.grants.size)
        assertTrue(result.denied.isEmpty())
        val grant = result.grants.single()
        assertEquals(NpcKnowledgeAccessPolicy141.GrantKind.ORGANIZATION_FACT, grant.kind)
        assertEquals(org, grant.grantedByUid)
        assertEquals(20L, grant.validFromTurn)
        assertEquals(20L, grant.validUntilTurn)
    }

    @Test
    fun expiredMembershipIsDenied() {
        val expired = membership(clearance = 5).copy(validUntilTurn = 10)
        val result = OrganizationKnowledgeGrantResolver141().resolve(
            holderUid = npc,
            turnId = 20,
            memberships = listOf(expired),
            publications = listOf(publication(minimumClearance = 0))
        )

        assertTrue(result.grants.isEmpty())
        assertEquals(
            OrganizationKnowledgeGrantResolver141.DenialReason.MEMBERSHIP_NOT_ACTIVE,
            result.denied.single().reason
        )
    }

    @Test
    fun insufficientClearanceIsDenied() {
        val result = OrganizationKnowledgeGrantResolver141().resolve(
            holderUid = npc,
            turnId = 20,
            memberships = listOf(membership(clearance = 1)),
            publications = listOf(publication(minimumClearance = 3))
        )

        assertTrue(result.grants.isEmpty())
        assertEquals(
            OrganizationKnowledgeGrantResolver141.DenialReason.INSUFFICIENT_CLEARANCE,
            result.denied.single().reason
        )
    }

    @Test
    fun organizationGrantCreatesDurableBeliefAndDedicatedAuditRecord() = runBlocking {
        val source = fact()
        val repo = FakeRepo(mutableListOf(source))
        val store = FakeOrganizationStore()
        val membership = membership(clearance = 2)
        val publication = publication(minimumClearance = 1)
        val grant = OrganizationKnowledgeGrantResolver141().resolve(
            holderUid = npc,
            turnId = 20,
            memberships = listOf(membership),
            publications = listOf(publication)
        ).grants.single()
        val promoter = OrganizationBeliefPromoter141(
            repository = repo,
            campaignUid = campaign,
            organizationStore = store,
            beliefUidFactory = { EntityUid("BELIEF-org") },
            transmissionUidFactory = { EntityUid("ORGKNOW-1") }
        )

        val belief = promoter.promote(npc, 20, membership, publication, grant)

        assertEquals(EntityUid("BELIEF-org"), belief?.uid)
        assertEquals(npc, belief?.holderUid)
        assertEquals(source.uid, belief?.provenance?.sourceUid)
        assertTrue((belief?.provenance?.confidence ?: 2.0) <= source.provenance.confidence)
        assertEquals(1, store.records.size)
        assertEquals(membership.membershipUid, store.records.single().membershipUid)
        assertEquals(publication.publicationUid, store.records.single().publicationUid)
        assertEquals(org, store.records.single().organizationUid)
    }

    @Test
    fun identicalActiveBeliefIsNotDuplicated() = runBlocking {
        val source = fact()
        val existing = CampaignTruth(
            uid = EntityUid("BELIEF-old"),
            kind = TruthKind.BELIEF,
            subjectUid = subject,
            predicate = source.predicate,
            value = source.value,
            holderUid = npc,
            validFromTurn = 10,
            provenance = ProvenanceRecord(ProvenanceType.NPC_REPORT, source.uid, 10, 0.7)
        )
        val repo = FakeRepo(mutableListOf(source, existing))
        val store = FakeOrganizationStore()
        val membership = membership(clearance = 2)
        val publication = publication(minimumClearance = 1)
        val grant = OrganizationKnowledgeGrantResolver141().resolve(
            npc, 20, listOf(membership), listOf(publication)
        ).grants.single()

        val belief = OrganizationBeliefPromoter141(repo, campaign, store)
            .promote(npc, 20, membership, publication, grant)

        assertNull(belief)
        assertTrue(store.records.isEmpty())
        assertEquals(2, repo.truths.size)
    }

    private fun membership(clearance: Int) = OrganizationMembership141(
        membershipUid = EntityUid("MEMBERSHIP-1"),
        npcUid = npc,
        organizationUid = org,
        clearance = clearance,
        validFromTurn = 1
    )

    private fun publication(minimumClearance: Int) = OrganizationFactPublication141(
        publicationUid = EntityUid("PUBLICATION-1"),
        organizationUid = org,
        truthUid = EntityUid("FACT-org"),
        subjectUid = subject,
        predicate = "secret.location",
        minimumClearance = minimumClearance,
        validFromTurn = 1
    )

    private fun fact() = CampaignTruth(
        uid = EntityUid("FACT-org"),
        kind = TruthKind.FACT,
        subjectUid = subject,
        predicate = "secret.location",
        value = "vault-7",
        validFromTurn = 1,
        provenance = ProvenanceRecord(
            type = ProvenanceType.CAMPAIGN_EVENT,
            sourceUid = EntityUid("EVENT-secret"),
            turnId = 1,
            confidence = 0.95,
            verified = true
        )
    )

    private class FakeOrganizationStore : OrganizationKnowledgeStore141 {
        val records = mutableListOf<OrganizationKnowledgeTransmission141>()
        override suspend fun appendOrganizationKnowledge(record: OrganizationKnowledgeTransmission141) {
            records += record
        }
    }

    private class FakeRepo(val truths: MutableList<CampaignTruth>) : UnifiedCampaignRepository {
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
