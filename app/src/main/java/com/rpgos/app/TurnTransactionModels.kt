package com.rpgos.app

enum class TurnTransactionStatus {
    PLANNED,
    VALIDATED,
    COMMITTED,
    ROLLED_BACK
}

data class DurableTurnRecord(
    val turnUid: EntityUid,
    val campaignUid: EntityUid,
    val turnId: Long,
    val chapter: Long,
    val playerInput: String,
    val narrative: String,
    val startedAtEpochMs: Long,
    val committedAtEpochMs: Long? = null,
    val status: TurnTransactionStatus = TurnTransactionStatus.VALIDATED,
    val failureReason: String? = null
)

enum class CampaignEventType {
    NPC_CREATED,
    NPC_KILLED,
    NPC_MOVED,
    RELATION_CHANGED,
    SKILL_LEARNED,
    STAT_CHANGED,
    ITEM_GAINED,
    ITEM_LOST,
    MISSION_STARTED,
    MISSION_COMPLETED,
    LOCATION_DISCOVERED,
    SECRET_LEARNED,
    TIME_SKIP,
    WORLD_EVENT,
    POLITICAL_CHANGE,
    PROJECT_STARTED,
    PROJECT_COMPLETED,
    CUSTOM
}

data class DurableCampaignEvent(
    val eventUid: EntityUid,
    val campaignUid: EntityUid,
    val turnId: Long,
    val sequence: Long,
    val type: CampaignEventType,
    val actorUid: EntityUid? = null,
    val targetUid: EntityUid? = null,
    val causeEventUid: EntityUid? = null,
    val description: String,
    val payloadJson: String = "{}",
    val provenance: ProvenanceRecord
)

data class DurableStateMutation(
    val mutationUid: EntityUid,
    val campaignUid: EntityUid,
    val turnId: Long,
    val entityUid: EntityUid,
    val entityType: String = "ENTITY",
    val field: String,
    val operation: MutationOperation,
    val oldValue: String? = null,
    val newValue: String? = null,
    val reason: String,
    val causedByEventUid: EntityUid? = null
)

enum class DurableMemoryKind {
    EPISODIC,
    SEMANTIC
}

data class DurableMemoryRecord(
    val memoryUid: EntityUid,
    val campaignUid: EntityUid,
    val kind: DurableMemoryKind,
    val subjectUid: EntityUid? = null,
    val text: String,
    val importance: Double,
    val createdTurn: Long,
    val sourceEventUids: Set<EntityUid> = emptySet(),
    val tags: Set<String> = emptySet()
) {
    init {
        require(importance in 0.0..1.0) { "importance musi mieścić się w zakresie 0..1." }
    }
}

data class DurableChronicleRecord(
    val entryUid: EntityUid,
    val campaignUid: EntityUid,
    val turnId: Long,
    val chapter: Long,
    val title: String,
    val summary: String,
    val eventUids: Set<EntityUid> = emptySet()
)

data class TurnCommitPlan(
    val turn: DurableTurnRecord,
    val events: List<DurableCampaignEvent> = emptyList(),
    val mutations: List<DurableStateMutation> = emptyList(),
    val truths: List<CampaignTruth> = emptyList(),
    val divergences: List<CanonDivergence> = emptyList(),
    val memories: List<DurableMemoryRecord> = emptyList(),
    val chronicleEntries: List<DurableChronicleRecord> = emptyList(),
    val status: TurnTransactionStatus = TurnTransactionStatus.PLANNED
) {
    val campaignUid: EntityUid get() = turn.campaignUid
    val turnId: Long get() = turn.turnId
}

/**
 * Commits every accepted consequence of one GM turn in a single repository
 * transaction. No narrative or canon divergence is canonical until this
 * transaction succeeds as a whole.
 */
class TurnTransactionCoordinator(
    private val repository: UnifiedCampaignRepository
) {
    suspend fun commit(plan: TurnCommitPlan): TurnCommitPlan {
        require(plan.turnId > 0) { "turnId musi być dodatni." }
        require(plan.turn.playerInput.isNotBlank()) { "Tura musi zawierać akcję gracza." }
        require(plan.turn.narrative.isNotBlank()) { "Tura musi zawierać narrację MG." }
        require(plan.events.all { it.campaignUid == plan.campaignUid && it.turnId == plan.turnId }) {
            "Wszystkie eventy muszą należeć do tej samej tury."
        }
        require(plan.mutations.all { it.campaignUid == plan.campaignUid && it.turnId == plan.turnId }) {
            "Wszystkie mutacje muszą należeć do tej samej tury."
        }
        require(plan.divergences.all { it.createdTurn <= plan.turnId }) {
            "Odchylenie od kanonu nie może pochodzić z przyszłej tury."
        }

        val committedAt = System.currentTimeMillis()
        val committedTurn = plan.turn.copy(
            status = TurnTransactionStatus.COMMITTED,
            committedAtEpochMs = committedAt,
            failureReason = null
        )

        repository.inTransaction {
            val expectedTurn = currentTurnId(plan.campaignUid) + 1L
            require(plan.turnId == expectedTurn) {
                "Niemonotoniczny zapis tury: otrzymano ${plan.turnId}, oczekiwano $expectedTurn."
            }
            writeTurn(committedTurn)
            plan.events.sortedBy { it.sequence }.forEach { appendEvent(it) }
            plan.mutations.forEach { applyMutation(it) }
            plan.truths.forEach { writeTruth(it) }
            plan.divergences.forEach { writeDivergence(it) }
            plan.memories.forEach { writeMemory(it) }
            plan.chronicleEntries.forEach { writeChronicle(it) }
        }

        return plan.copy(turn = committedTurn, status = TurnTransactionStatus.COMMITTED)
    }
}
