package com.rpgos.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NpcBeliefTimeline141Test {
    private val campaign = EntityUid("CAMPAIGN-timeline")
    private val npc = EntityUid("NPC-timeline")
    private val subject = EntityUid("SUBJECT-target")

    @Test
    fun timelineShowsBeliefReplacementAndRetractionTurn() = runBlocking {
        val oldBelief = belief("BELIEF-old", "KONOHA", 10)
        val replacement = CampaignTruth(
            uid = EntityUid("FACT-new"),
            kind = TruthKind.FACT,
            subjectUid = subject,
            predicate = "target.location",
            value = "KUMO",
            validFromTurn = 20,
            provenance = ProvenanceRecord(
                type = ProvenanceType.CAMPAIGN_EVENT,
                sourceUid = EntityUid("EVENT-new"),
                turnId = 20,
                confidence = 1.0,
                verified = true
            )
        )
        val newBelief = belief("BELIEF-new", "KUMO", 20)
        val repo = FakeRepo(listOf(oldBelief, replacement, newBelief))
        val store = FakeRetractions(
            listOf(
                NpcBeliefRetraction141(
                    retractionUid = EntityUid("RETRACT-1"),
                    campaignUid = campaign,
                    holderUid = npc,
                    retractedBeliefUid = oldBelief.uid,
                    replacementTruthUid = replacement.uid,
                    turnId = 20,
                    reason = "direct observation"
                )
            )
        )

        val result = NpcBeliefTimeline141(repo, campaign, store)
            .query(npc, subject, "target.location", atTurnId = 30)

        assertEquals(2, result.entries.size)
        assertEquals(NpcBeliefTimeline141.Status.RETRACTED, result.entries[0].status)
        assertEquals(20L, result.entries[0].endedTurn)
        assertEquals(replacement.uid, result.entries[0].replacementTruth?.uid)
        assertEquals(NpcBeliefTimeline141.Status.ACTIVE, result.entries[1].status)
        assertEquals("KUMO", result.entries[1].belief.value)
    }

    @Test
    fun historicalQueryBeforeRetractionStillShowsOldBeliefActive() = runBlocking {
        val oldBelief = belief("BELIEF-old", "KONOHA", 10)
        val repo = FakeRepo(listOf(oldBelief))
        val store = FakeRetractions(
            listOf(
                NpcBeliefRetraction141(
                    EntityUid("RETRACT-1"), campaign, npc, oldBelief.uid,
                    EntityUid("FACT-new"), 20, "later evidence"
                )
            )
        )

        val result = NpcBeliefTimeline141(repo, campaign, store)
            .query(npc, subject, "target.location", atTurnId = 15)

        assertEquals(1, result.entries.size)
        assertEquals(NpcBeliefTimeline141.Status.ACTIVE, result.entries.single().status)
        assertNull(result.entries.single().retraction)
    }

    @Test
    fun expiredBeliefIsMarkedExpiredWithoutRetraction() = runBlocking {
        val expired = belief("BELIEF-expired", "OLD", 3).copy(validUntilTurn = 12)
        val result = NpcBeliefTimeline141(FakeRepo(listOf(expired)), campaign, FakeRetractions())
            .query(npc, subject, "target.location", atTurnId = 30)

        assertEquals(NpcBeliefTimeline141.Status.EXPIRED, result.entries.single().status)
        assertEquals(12L, result.entries.single().endedTurn)
    }

    @Test
    fun predicateFilterKeepsIndependentKnowledgeThreadsSeparate() = runBlocking {
        val location = belief("BELIEF-loc", "KUMO", 10)
        val loyalty = belief("BELIEF-loyalty", "HOSTILE", 11).copy(predicate = "target.loyalty")
        val result = NpcBeliefTimeline141(FakeRepo(listOf(location, loyalty)), campaign, FakeRetractions())
            .query(npc, subject, "target.location", atTurnId = 30)

        assertEquals(listOf("BELIEF-loc"), result.entries.map { it.belief.uid.value })
    }

    private fun belief(uid: String, value: String, turn: Long) = CampaignTruth(
        uid = EntityUid(uid),
        kind = TruthKind.BELIEF,
        subjectUid = subject,
        predicate = "target.location",
        value = value,
        holderUid = npc,
        validFromTurn = turn,
        provenance = ProvenanceRecord(
            type = ProvenanceType.NPC_OBSERVATION,
            sourceUid = EntityUid("SOURCE-$uid"),
            turnId = turn,
            confidence = 0.9
        )
    )

    private class FakeRetractions(
        private val records: List<NpcBeliefRetraction141> = emptyList()
    ) : NpcBeliefRetractionStore141 {
        override suspend fun appendRetraction(record: NpcBeliefRetraction141) = Unit
        override suspend fun retractionsForHolder(
            campaignUid: EntityUid,
            holderUid: EntityUid,
            beforeOrAtTurn: Long
        ): List<NpcBeliefRetraction141> = records.filter {
            it.campaignUid == campaignUid && it.holderUid == holderUid && it.turnId <= beforeOrAtTurn
        }
    }

    private class FakeRepo(private val truths: List<CampaignTruth>) : UnifiedCampaignRepository {
        override suspend fun currentTurnId(campaignUid: EntityUid): Long = 30L
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
                (atTurnId == null || it.validFromTurn == null || it.validFromTurn <= atTurnId) &&
                (atTurnId == null || it.validUntilTurn == null || it.validUntilTurn >= atTurnId)
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
