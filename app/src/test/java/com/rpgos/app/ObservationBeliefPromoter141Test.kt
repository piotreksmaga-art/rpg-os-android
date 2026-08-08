package com.rpgos.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationBeliefPromoter141Test {
    private val campaign = EntityUid("CAMPAIGN-observation")
    private val npc = EntityUid("NPC-observer")
    private val subject = EntityUid("SUBJECT-target")
    private val location = EntityUid("LOC-scene")

    @Test
    fun perceivedFactCreatesBeliefAndLedgerEntry() = runBlocking {
        val fact = fact("FACT-1", "present", 0.8)
        val repo = FakeRepo(mutableListOf(fact))
        val ledger = FakeLedger()
        val promoter = ObservationBeliefPromoter141(
            repository = repo,
            campaignUid = campaign,
            knowledgeStore = ledger,
            propagation = NpcKnowledgePropagation141 { EntityUid("BELIEF-1") },
            transmissionFactory = KnowledgeTransmissionFactory141 { EntityUid("KNOW-1") }
        )

        val result = promoter.promote(npc, 20, listOf(grant(fact)))

        assertEquals(1, result.createdBeliefs.size)
        val belief = result.createdBeliefs.single()
        assertEquals(TruthKind.BELIEF, belief.kind)
        assertEquals(npc, belief.holderUid)
        assertEquals(ProvenanceType.NPC_OBSERVATION, belief.provenance.type)
        assertEquals(fact.uid, belief.provenance.sourceUid)
        assertEquals(0.8, belief.provenance.confidence, 0.0)
        assertEquals(1, ledger.records.size)
        assertEquals(fact.uid, ledger.records.single().sourceTruthUid)
        assertEquals(belief.uid, ledger.records.single().resultingBeliefUid)
        assertEquals(KnowledgeChannel141.OBSERVATION, ledger.records.single().channel)
    }

    @Test
    fun identicalActiveBeliefIsNotDuplicated() = runBlocking {
        val fact = fact("FACT-1", "present", 0.8)
        val existing = CampaignTruth(
            uid = EntityUid("BELIEF-old"),
            kind = TruthKind.BELIEF,
            subjectUid = subject,
            predicate = fact.predicate,
            value = fact.value,
            holderUid = npc,
            validFromTurn = 10,
            provenance = ProvenanceRecord(ProvenanceType.NPC_OBSERVATION, fact.uid, 10, 0.8)
        )
        val repo = FakeRepo(mutableListOf(fact, existing))
        val ledger = FakeLedger()
        val promoter = ObservationBeliefPromoter141(repo, campaign, ledger)

        val result = promoter.promote(npc, 20, listOf(grant(fact)))

        assertTrue(result.createdBeliefs.isEmpty())
        assertEquals(setOf(fact.uid), result.skippedTruthUids)
        assertTrue(ledger.records.isEmpty())
    }

    @Test
    fun changedObservedValueCreatesNewBeliefWithoutDeletingHistory() = runBlocking {
        val oldBelief = CampaignTruth(
            uid = EntityUid("BELIEF-old"),
            kind = TruthKind.BELIEF,
            subjectUid = subject,
            predicate = "target.location",
            value = "A",
            holderUid = npc,
            validFromTurn = 5,
            provenance = ProvenanceRecord(ProvenanceType.NPC_OBSERVATION, EntityUid("FACT-old"), 5, 0.9)
        )
        val newFact = CampaignTruth(
            uid = EntityUid("FACT-new"),
            kind = TruthKind.FACT,
            subjectUid = subject,
            predicate = "target.location",
            value = "B",
            validFromTurn = 20,
            provenance = ProvenanceRecord(ProvenanceType.CAMPAIGN_EVENT, EntityUid("EVENT-new"), 20, 0.9)
        )
        val repo = FakeRepo(mutableListOf(oldBelief, newFact))
        val ledger = FakeLedger()
        val promoter = ObservationBeliefPromoter141(
            repo,
            campaign,
            ledger,
            propagation = NpcKnowledgePropagation141 { EntityUid("BELIEF-new") },
            transmissionFactory = KnowledgeTransmissionFactory141 { EntityUid("KNOW-new") }
        )

        val result = promoter.promote(npc, 20, listOf(grant(newFact)))

        assertEquals("B", result.createdBeliefs.single().value)
        assertTrue(repo.truths.any { it.uid == oldBelief.uid })
        assertTrue(repo.truths.any { it.uid == EntityUid("BELIEF-new") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun organizationGrantCannotMasqueradeAsObservation() = runBlocking {
        val fact = fact("FACT-1", "present", 1.0)
        val repo = FakeRepo(mutableListOf(fact))
        val promoter = ObservationBeliefPromoter141(repo, campaign, FakeLedger())
        val badGrant = grant(fact).copy(kind = NpcKnowledgeAccessPolicy141.GrantKind.ORGANIZATION_FACT)
        promoter.promote(npc, 20, listOf(badGrant))
        Unit
    }

    private fun fact(uid: String, value: String, confidence: Double) = CampaignTruth(
        uid = EntityUid(uid),
        kind = TruthKind.FACT,
        subjectUid = subject,
        predicate = "target.state",
        value = value,
        validFromTurn = 1,
        provenance = ProvenanceRecord(ProvenanceType.CAMPAIGN_EVENT, EntityUid("EVENT-$uid"), 1, confidence)
    )

    private fun grant(fact: CampaignTruth) = NpcKnowledgeAccessPolicy141.Grant(
        holderUid = npc,
        truthUid = fact.uid,
        subjectUid = requireNotNull(fact.subjectUid),
        predicate = fact.predicate,
        kind = NpcKnowledgeAccessPolicy141.GrantKind.OBSERVABLE_FACT,
        grantedByUid = location,
        validFromTurn = 20,
        validUntilTurn = 20
    )

    private class FakeLedger : KnowledgeTransmissionStore141 {
        val records = mutableListOf<KnowledgeTransmission141>()
        override suspend fun appendKnowledgeTransmission(record: KnowledgeTransmission141) { records += record }
        override suspend fun knowledgeTransmissionsForReceiver(
            campaignUid: EntityUid,
            receiverUid: EntityUid,
            beforeOrAtTurn: Long?,
            limit: Int
        ): List<KnowledgeTransmission141> = records.filter {
            it.campaignUid == campaignUid && it.receiverUid == receiverUid &&
                (beforeOrAtTurn == null || it.turnId <= beforeOrAtTurn)
        }.take(limit)
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
