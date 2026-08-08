package com.rpgos.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NpcKnowledgeLifecycleRuleResolver141Test {

    @Test
    fun inferenceBeliefGetsTruthKeyAndInferenceLedgerWrite() = runBlocking {
        val repository = FakeRepository(currentTurn = 5L)
        val resolver = resolver(
            repository,
            beliefWrite(
                value = "forest",
                sourceType = ProvenanceType.NPC_INFERENCE,
                channel = KnowledgeChannel141.INFERENCE,
                sourceId = "FACT-source",
                confidence = 0.7
            )
        )

        val result = resolver.resolve(request(), context(), proposal())
        val belief = result.truthWrites.single()

        assertNotNull(belief.truthKey)
        val inference = result.npcKnowledgeWrites.inferences.single()
        assertEquals("NPC-A", inference.holderId)
        assertEquals(belief.truthKey, inference.resultingBelief.truthKey)
        assertEquals("FACT-source", inference.premiseTruths.single().durableUid)
        assertEquals(0.7, inference.confidence, 0.0001)
    }

    @Test
    fun strongerNewObservationRetractsWeakerDurableInference() = runBlocking {
        val oldBelief = campaignBelief(
            uid = "BELIEF-old",
            value = "village",
            provenance = ProvenanceType.NPC_INFERENCE,
            confidence = 0.6,
            turn = 4L
        )
        val repository = FakeRepository(currentTurn = 5L, beliefs = listOf(oldBelief))
        val resolver = resolver(
            repository,
            beliefWrite(
                value = "forest",
                sourceType = ProvenanceType.NPC_OBSERVATION,
                channel = KnowledgeChannel141.OBSERVATION,
                sourceId = "FACT-seen",
                confidence = 0.9
            )
        )

        val result = resolver.resolve(request(), context(), proposal())
        val newBelief = result.truthWrites.single()
        val resolution = result.npcKnowledgeWrites.resolutions.single()
        val retraction = result.npcKnowledgeWrites.retractions.single()

        assertEquals(NpcKnowledgeLifecycle141.ResolutionReason.STRONGER_PROVENANCE, resolution.reason)
        assertEquals(newBelief.truthKey, resolution.winner?.truthKey)
        assertEquals("BELIEF-old", retraction.retractedBelief.durableUid)
        assertEquals(newBelief.truthKey, retraction.replacementTruth.truthKey)
    }

    @Test
    fun exactTieBetweenSameTurnReportsProducesNoRetraction() = runBlocking {
        val repository = FakeRepository(currentTurn = 5L)
        val first = beliefWrite(
            value = "village",
            sourceType = ProvenanceType.NPC_REPORT,
            channel = KnowledgeChannel141.REPORT,
            sourceId = "BELIEF-source-1",
            confidence = 0.8,
            sourceNpcId = "NPC-B"
        )
        val second = beliefWrite(
            value = "forest",
            sourceType = ProvenanceType.NPC_REPORT,
            channel = KnowledgeChannel141.REPORT,
            sourceId = "BELIEF-source-2",
            confidence = 0.8,
            sourceNpcId = "NPC-C"
        )
        val resolver = NpcKnowledgeLifecycleRuleResolver141(
            delegate = fixedDelegate(listOf(first, second)),
            repository = repository,
            campaignUid = EntityUid("CAMPAIGN-test")
        )

        val result = resolver.resolve(request(), context(), proposal())
        val resolution = result.npcKnowledgeWrites.resolutions.single()

        assertEquals(2, result.truthWrites.mapNotNull { it.truthKey }.distinct().size)
        assertEquals(NpcKnowledgeLifecycle141.ResolutionReason.UNRESOLVED_TIE, resolution.reason)
        assertNull(resolution.winner)
        assertTrue(resolution.supersededBeliefs.isEmpty())
        assertTrue(result.npcKnowledgeWrites.retractions.isEmpty())
    }

    @Test
    fun alreadyRetractedDurableBeliefDoesNotConflictAgain() = runBlocking {
        val oldBelief = campaignBelief(
            uid = "BELIEF-old",
            value = "village",
            provenance = ProvenanceType.NPC_INFERENCE,
            confidence = 0.6,
            turn = 4L
        )
        val repository = FakeRepository(currentTurn = 5L, beliefs = listOf(oldBelief))
        val retractionStore = FakeRetractionStore(
            listOf(
                NpcBeliefRetraction141(
                    retractionUid = EntityUid("RETRACT-old"),
                    campaignUid = EntityUid("CAMPAIGN-test"),
                    holderUid = EntityUid("NPC-A"),
                    retractedBeliefUid = EntityUid("BELIEF-old"),
                    replacementTruthUid = EntityUid("BELIEF-replacement"),
                    turnId = 5L,
                    reason = "already superseded"
                )
            )
        )
        val resolver = NpcKnowledgeLifecycleRuleResolver141(
            delegate = fixedDelegate(
                beliefWrite(
                    value = "forest",
                    sourceType = ProvenanceType.NPC_OBSERVATION,
                    channel = KnowledgeChannel141.OBSERVATION,
                    sourceId = "FACT-seen",
                    confidence = 0.9
                )
            ),
            repository = repository,
            campaignUid = EntityUid("CAMPAIGN-test"),
            retractionStore = retractionStore
        )

        val result = resolver.resolve(request(), context(), proposal())
        assertTrue(result.npcKnowledgeWrites.resolutions.isEmpty())
        assertTrue(result.npcKnowledgeWrites.retractions.isEmpty())
    }

    private fun resolver(repository: UnifiedCampaignRepository, belief: TruthWrite) =
        NpcKnowledgeLifecycleRuleResolver141(
            delegate = fixedDelegate(belief),
            repository = repository,
            campaignUid = EntityUid("CAMPAIGN-test")
        )

    private fun fixedDelegate(belief: TruthWrite) = fixedDelegate(listOf(belief))

    private fun fixedDelegate(beliefs: List<TruthWrite>) = object : GameMasterRuleResolver {
        override suspend fun resolve(
            request: GameMasterTurnRequest,
            context: GameMasterContext,
            proposal: GameMasterProposal
        ) = GameMasterTurnResult(narrative = "test", truthWrites = beliefs)
    }

    private fun beliefWrite(
        value: String,
        sourceType: ProvenanceType,
        channel: KnowledgeChannel141,
        sourceId: String,
        confidence: Double,
        sourceNpcId: String? = null,
        validFromTurn: Long? = null
    ) = TruthWrite(
        kind = TruthKind.BELIEF,
        subjectId = "TARGET",
        predicate = "location",
        value = value,
        holderId = "NPC-A",
        confidence = confidence,
        sourceType = sourceType,
        sourceId = sourceId,
        validFromTurn = validFromTurn,
        knowledgeChannel = channel,
        sourceNpcId = sourceNpcId
    )

    private fun campaignBelief(
        uid: String,
        value: String,
        provenance: ProvenanceType,
        confidence: Double,
        turn: Long
    ) = CampaignTruth(
        uid = EntityUid(uid),
        kind = TruthKind.BELIEF,
        subjectUid = EntityUid("TARGET"),
        predicate = "location",
        value = value,
        holderUid = EntityUid("NPC-A"),
        validFromTurn = turn,
        provenance = ProvenanceRecord(
            type = provenance,
            sourceUid = EntityUid("SOURCE-$uid"),
            turnId = turn,
            confidence = confidence
        )
    )

    private fun request() = GameMasterTurnRequest(
        campaignId = "CAMPAIGN-test",
        worldPackId = "WORLD-test",
        playerAction = "test",
        currentChapter = 1L
    )

    private fun context() = GameMasterContext(
        campaignId = "CAMPAIGN-test",
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

    private fun proposal() = GameMasterProposal(
        narrativeDraft = "test",
        diagnostics = GameMasterDiagnostics()
    )

    private fun section(name: String) = ContextSection(name, "{}", 1)

    private class FakeRetractionStore(
        private val records: List<NpcBeliefRetraction141>
    ) : NpcBeliefRetractionStore141 {
        override suspend fun appendRetraction(record: NpcBeliefRetraction141) = Unit
        override suspend fun retractionsForHolder(
            campaignUid: EntityUid,
            holderUid: EntityUid,
            beforeOrAtTurn: Long
        ) = records.filter {
            it.campaignUid == campaignUid && it.holderUid == holderUid && it.turnId <= beforeOrAtTurn
        }
    }

    private class FakeRepository(
        private var currentTurn: Long,
        private val beliefs: List<CampaignTruth> = emptyList()
    ) : UnifiedCampaignRepository {
        override suspend fun currentTurnId(campaignUid: EntityUid) = currentTurn
        override suspend fun writeTurn(turn: DurableTurnRecord) { currentTurn = turn.turnId }
        override suspend fun getEntityState(campaignUid: EntityUid, entityUid: EntityUid, entityType: String?) = emptyList<CampaignStateField>()
        override suspend fun getTruth(campaignUid: EntityUid, subjectUid: EntityUid, predicate: String, atTurnId: Long?) = emptyList<CampaignTruth>()
        override suspend fun getBeliefs(campaignUid: EntityUid, holderUid: EntityUid, subjectUid: EntityUid?, atTurnId: Long?, limit: Int) =
            beliefs.filter {
                it.holderUid == holderUid &&
                    (subjectUid == null || it.subjectUid == subjectUid) &&
                    (atTurnId == null || (it.validFromTurn ?: Long.MIN_VALUE) <= atTurnId)
            }.take(limit)
        override suspend fun recentEvents(campaignUid: EntityUid, beforeOrAtTurn: Long?, limit: Int) = emptyList<DurableCampaignEvent>()
        override suspend fun memories(campaignUid: EntityUid, subjectUid: EntityUid?, kinds: Set<DurableMemoryKind>, limit: Int) = emptyList<DurableMemoryRecord>()
        override suspend fun getActiveDivergences(campaignUid: EntityUid) = emptyList<CanonDivergence>()
        override suspend fun writeDivergence(divergence: CanonDivergence) = Unit
        override suspend fun appendEvent(event: DurableCampaignEvent) = Unit
        override suspend fun applyMutation(mutation: DurableStateMutation) = Unit
        override suspend fun writeTruth(truth: CampaignTruth) = Unit
        override suspend fun writeMemory(memory: DurableMemoryRecord) = Unit
        override suspend fun writeChronicle(entry: DurableChronicleRecord) = Unit
        override suspend fun latestSnapshot(campaignUid: EntityUid): CampaignSnapshotRef? = null
        override suspend fun createSnapshot(campaignUid: EntityUid, throughTurnId: Long) =
            CampaignSnapshotRef(EntityUid("SNAP-1"), campaignUid, throughTurnId, 0L, 0L)
        override suspend fun <T> inTransaction(block: suspend UnifiedCampaignRepository.() -> T): T = block(this)
    }
}
