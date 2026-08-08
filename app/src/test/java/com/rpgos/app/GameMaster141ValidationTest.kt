package com.rpgos.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameMaster141ValidationTest {
    private val campaignUid = EntityUid("CAMPAIGN-test")

    @Test
    fun validatorAcceptsConsistentMutationAndEvent() = runBlocking {
        val repo = FakeRepository(
            currentTurn = 4L,
            state = mapOf(StateKey("CHARACTER", "player", "hp") to "100")
        )
        val validator = GameMasterTurnValidator141(repo, campaignUid)
        val report = validator.validate(
            request(), context(),
            GameMasterTurnResult(
                narrative = "Skutek akcji.",
                worldEvents = listOf(
                    WorldEventWrite(
                        eventType = "STAT_CHANGED",
                        eventKey = "damage-1",
                        description = "Postać otrzymuje obrażenia.",
                        effectiveChapter = 3L
                    )
                ),
                stateMutations = listOf(
                    GameStateMutation(
                        entityType = "CHARACTER",
                        entityId = "player",
                        field = "hp",
                        operation = MutationOperation.DECREMENT,
                        oldValue = "100",
                        newValue = "90",
                        reason = "10 obrażeń",
                        causedByEventKey = "damage-1"
                    )
                )
            )
        )
        assertTrue(report.issues.joinToString { it.code }, report.accepted)
    }

    @Test
    fun validatorRejectsStaleOldValue() = runBlocking {
        val repo = FakeRepository(
            currentTurn = 4L,
            state = mapOf(StateKey("CHARACTER", "player", "hp") to "80")
        )
        val validator = GameMasterTurnValidator141(repo, campaignUid)
        val report = validator.validate(
            request(), context(),
            GameMasterTurnResult(
                narrative = "Skutek akcji.",
                stateMutations = listOf(
                    GameStateMutation(
                        entityType = "CHARACTER",
                        entityId = "player",
                        field = "hp",
                        operation = MutationOperation.SET,
                        oldValue = "100",
                        newValue = "90",
                        reason = "test"
                    )
                )
            )
        )
        assertFalse(report.accepted)
        assertTrue(report.issues.any { it.code == "STALE_OLD_VALUE" })
    }

    @Test
    fun validatorRejectsUnknownEventDependency() = runBlocking {
        val validator = GameMasterTurnValidator141(FakeRepository(), campaignUid)
        val report = validator.validate(
            request(), context(),
            GameMasterTurnResult(
                narrative = "Skutek akcji.",
                worldEvents = listOf(
                    WorldEventWrite(
                        eventType = "WORLD_EVENT",
                        eventKey = "effect",
                        description = "Efekt.",
                        effectiveChapter = 3L,
                        causeEventKey = "missing-cause"
                    )
                )
            )
        )
        assertFalse(report.accepted)
        assertTrue(report.issues.any { it.code == "UNKNOWN_EVENT_CAUSE" })
    }

    @Test
    fun validatorRejectsTwoMutationsOfSameField() = runBlocking {
        val repo = FakeRepository(
            state = mapOf(StateKey("CHARACTER", "player", "hp") to "100")
        )
        val mutation = GameStateMutation(
            entityType = "CHARACTER",
            entityId = "player",
            field = "hp",
            operation = MutationOperation.SET,
            oldValue = "100",
            newValue = "90",
            reason = "test"
        )
        val report = GameMasterTurnValidator141(repo, campaignUid).validate(
            request(), context(),
            GameMasterTurnResult(
                narrative = "Skutek akcji.",
                stateMutations = listOf(mutation, mutation.copy(newValue = "80"))
            )
        )
        assertFalse(report.accepted)
        assertTrue(report.issues.any { it.code == "DUPLICATE_FIELD_MUTATION" })
    }

    @Test
    fun resolverInitializesMissingNumericFieldWithoutFakeOldValue() = runBlocking {
        val resolver = GameMasterRuleResolver141(FakeRepository(), campaignUid)
        val result = resolver.resolve(
            request(), context(),
            GameMasterProposal(
                narrativeDraft = "Rozwój.",
                proposedActions = listOf(
                    ProposedWorldAction(
                        actionType = "STATE_INCREMENT",
                        targetId = "player",
                        parametersJson = """{"entity_type":"CHARACTER","field":"stat.focus","amount":"3"}""",
                        reason = "trening"
                    )
                ),
                diagnostics = GameMasterDiagnostics(0, 0, 0, 0, 0)
            )
        )
        val mutation = result.stateMutations.single()
        assertNull(mutation.oldValue)
        assertEquals("3", mutation.newValue)
    }

    @Test
    fun coordinatorRejectsNonMonotonicTurn() = runBlocking {
        val repo = FakeRepository(currentTurn = 7L)
        val coordinator = TurnTransactionCoordinator(repo)
        val result = runCatching {
            coordinator.commit(
                TurnCommitPlan(
                    turn = DurableTurnRecord(
                        turnUid = EntityUid("TURN-9"),
                        campaignUid = campaignUid,
                        turnId = 9L,
                        chapter = 3L,
                        playerInput = "test",
                        narrative = "test",
                        startedAtEpochMs = 1L
                    )
                )
            )
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("oczekiwano 8"))
    }

    private fun request() = GameMasterTurnRequest(
        campaignId = campaignUid.value,
        worldPackId = "WORLDPACK-test",
        playerAction = "Atakuję.",
        currentChapter = 3L
    )

    private fun context() = GameMasterContext(
        campaignId = campaignUid.value,
        chapter = 3L,
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

    private data class StateKey(val type: String, val entity: String, val field: String)

    private class FakeRepository(
        var currentTurn: Long = 0L,
        private val state: Map<StateKey, String> = emptyMap()
    ) : UnifiedCampaignRepository {
        override suspend fun currentTurnId(campaignUid: EntityUid): Long = currentTurn

        override suspend fun writeTurn(turn: DurableTurnRecord) {
            currentTurn = maxOf(currentTurn, turn.turnId)
        }

        override suspend fun getEntityState(
            campaignUid: EntityUid,
            entityUid: EntityUid,
            entityType: String?
        ): List<CampaignStateField> = state.mapNotNull { (key, value) ->
            if (key.entity != entityUid.value || (entityType != null && key.type != entityType)) null
            else CampaignStateField(
                entityType = key.type,
                entityUid = entityUid,
                field = key.field,
                value = value,
                validFromTurn = currentTurn,
                provenanceType = ProvenanceType.SYSTEM_SIMULATION,
                provenanceUid = null
            )
        }

        override suspend fun getTruth(
            campaignUid: EntityUid,
            subjectUid: EntityUid,
            predicate: String,
            atTurnId: Long?
        ): List<CampaignTruth> = emptyList()

        override suspend fun getBeliefs(
            campaignUid: EntityUid,
            holderUid: EntityUid,
            subjectUid: EntityUid?,
            atTurnId: Long?,
            limit: Int
        ): List<CampaignTruth> = emptyList()

        override suspend fun recentEvents(
            campaignUid: EntityUid,
            beforeOrAtTurn: Long?,
            limit: Int
        ): List<DurableCampaignEvent> = emptyList()

        override suspend fun memories(
            campaignUid: EntityUid,
            subjectUid: EntityUid?,
            kinds: Set<DurableMemoryKind>,
            limit: Int
        ): List<DurableMemoryRecord> = emptyList()

        override suspend fun getActiveDivergences(campaignUid: EntityUid): List<CanonDivergence> = emptyList()
        override suspend fun writeDivergence(divergence: CanonDivergence) = Unit
        override suspend fun appendEvent(event: DurableCampaignEvent) = Unit
        override suspend fun applyMutation(mutation: DurableStateMutation) = Unit
        override suspend fun writeTruth(truth: CampaignTruth) = Unit
        override suspend fun writeMemory(memory: DurableMemoryRecord) = Unit
        override suspend fun writeChronicle(entry: DurableChronicleRecord) = Unit
        override suspend fun latestSnapshot(campaignUid: EntityUid): CampaignSnapshotRef? = null

        override suspend fun createSnapshot(
            campaignUid: EntityUid,
            throughTurnId: Long
        ): CampaignSnapshotRef = error("not used")

        override suspend fun <T> inTransaction(block: suspend UnifiedCampaignRepository.() -> T): T = block(this)
    }
}
