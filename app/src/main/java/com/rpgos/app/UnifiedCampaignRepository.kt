package com.rpgos.app

/** Stable identifier used by every durable GM Engine entity. */
@JvmInline
value class EntityUid(val value: String) {
    init {
        require(value.isNotBlank()) { "UID nie może być pusty." }
    }

    override fun toString(): String = value
}

/** Distinguishes objective campaign truth from subjective knowledge and prose. */
enum class TruthKind {
    FACT,
    BELIEF,
    NARRATIVE
}

/** Where a durable fact or belief came from. */
enum class ProvenanceType {
    WORLD_CANON,
    CAMPAIGN_EVENT,
    PLAYER_STATE,
    NPC_OBSERVATION,
    NPC_REPORT,
    NPC_INFERENCE,
    PLAYER_CLAIM,
    SYSTEM_SIMULATION,
    GM_INFERENCE,
    IMPORTED_CONTENT
}

data class ProvenanceRecord(
    val type: ProvenanceType,
    val sourceUid: EntityUid?,
    val turnId: Long?,
    val confidence: Double = 1.0,
    val canonStatus: String? = null,
    val verified: Boolean = false
) {
    init {
        require(confidence in 0.0..1.0) { "confidence musi mieścić się w zakresie 0..1." }
    }
}

/** A time-bounded durable statement about the campaign. */
data class CampaignTruth(
    val uid: EntityUid,
    val kind: TruthKind,
    val subjectUid: EntityUid?,
    val predicate: String,
    val value: String,
    val validFromTurn: Long? = null,
    val validUntilTurn: Long? = null,
    val provenance: ProvenanceRecord
)

/** A canon baseline overridden by a campaign-specific divergence. */
data class CanonDivergence(
    val uid: EntityUid,
    val canonSubjectUid: EntityUid,
    val divergenceType: String,
    val description: String,
    val causedByEventUid: EntityUid?,
    val createdTurn: Long,
    val active: Boolean = true
)

data class CampaignSnapshotRef(
    val snapshotUid: EntityUid,
    val campaignUid: EntityUid,
    val throughTurnId: Long,
    val throughEventSequence: Long,
    val createdAtEpochMs: Long
)

/**
 * Single logical data boundary for the GM Engine.
 *
 * The physical implementation may use world.db, campaign.db, events.db,
 * memory.db and a vector index, but the rest of the engine never reaches into
 * those stores directly.
 */
interface UnifiedCampaignRepository {
    suspend fun currentTurnId(campaignUid: EntityUid): Long

    suspend fun getTruth(
        campaignUid: EntityUid,
        subjectUid: EntityUid,
        predicate: String,
        atTurnId: Long? = null
    ): List<CampaignTruth>

    suspend fun getActiveDivergences(campaignUid: EntityUid): List<CanonDivergence>

    suspend fun appendEvent(event: DurableCampaignEvent)

    suspend fun applyMutation(mutation: DurableStateMutation)

    suspend fun writeTruth(truth: CampaignTruth)

    suspend fun writeMemory(memory: DurableMemoryRecord)

    suspend fun writeChronicle(entry: DurableChronicleRecord)

    suspend fun latestSnapshot(campaignUid: EntityUid): CampaignSnapshotRef?

    suspend fun createSnapshot(campaignUid: EntityUid, throughTurnId: Long): CampaignSnapshotRef

    suspend fun <T> inTransaction(block: suspend UnifiedCampaignRepository.() -> T): T
}
