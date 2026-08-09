package com.rpgos.app

import java.security.MessageDigest
import kotlin.math.max

/**
 * Deterministic first stage of the campaign Memory Engine.
 *
 * Working memory remains transient in the Context Bundle. This consolidator only
 * materializes durable EPISODIC memories from already committed Event Store
 * records. It never reads narrative prose as truth and never creates SEMANTIC
 * memory. Semantic promotion will use explicit truth provenance in a later stage.
 *
 * Memory IDs are derived from immutable event IDs plus algorithm version, making
 * retries after crashes idempotent without requiring a separate job ledger.
 */
class MemoryConsolidator141(
    private val repository: UnifiedCampaignRepository,
    private val campaignUid: EntityUid
) {
    data class Result(
        val throughTurnId: Long,
        val scannedEvents: Int,
        val eligibleEvents: Int,
        val createdMemories: Int,
        val skippedExisting: Int
    )

    suspend fun consolidateEpisodic(
        throughTurnId: Long? = null,
        eventLimit: Int = 200,
        minImportance: Double = DEFAULT_MIN_IMPORTANCE
    ): Result {
        require(eventLimit >= 1) { "eventLimit musi być >= 1." }
        require(minImportance in 0.0..1.0) { "minImportance musi mieścić się w 0..1." }

        val currentTurn = repository.currentTurnId(campaignUid)
        val resolvedTurn = throughTurnId ?: currentTurn
        require(resolvedTurn in 0L..currentTurn) {
            "Memory Consolidator nie może czytać przyszłej tury: $resolvedTurn / $currentTurn."
        }

        val events = repository.recentEvents(
            campaignUid = campaignUid,
            beforeOrAtTurn = resolvedTurn,
            limit = eventLimit
        )
            .filter { it.campaignUid == campaignUid && it.turnId <= resolvedTurn }
            .sortedWith(compareBy<DurableCampaignEvent> { it.turnId }.thenBy { it.sequence })

        val eligible = events.filter { event ->
            event.description.isNotBlank() && importanceFor(event.type) >= minImportance
        }

        val existingIds = repository.memories(
            campaignUid = campaignUid,
            kinds = setOf(DurableMemoryKind.EPISODIC),
            limit = max(eventLimit * 4, 512)
        ).asSequence().map { it.memoryUid }.toHashSet()

        var created = 0
        var skipped = 0
        eligible.forEach { event ->
            val memoryUid = memoryUidFor(event.eventUid)
            if (memoryUid in existingIds) {
                skipped++
                return@forEach
            }

            repository.writeMemory(
                DurableMemoryRecord(
                    memoryUid = memoryUid,
                    campaignUid = campaignUid,
                    kind = DurableMemoryKind.EPISODIC,
                    subjectUid = event.targetUid ?: event.actorUid,
                    text = event.description,
                    importance = importanceFor(event.type),
                    createdTurn = event.turnId,
                    sourceEventUids = setOf(event.eventUid),
                    tags = setOf(
                        AUTO_TAG,
                        "event_type:${event.type.name.lowercase()}"
                    )
                )
            )
            existingIds += memoryUid
            created++
        }

        return Result(
            throughTurnId = resolvedTurn,
            scannedEvents = events.size,
            eligibleEvents = eligible.size,
            createdMemories = created,
            skippedExisting = skipped
        )
    }

    internal fun memoryUidFor(eventUid: EntityUid): EntityUid {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$ALGORITHM_VERSION|${campaignUid.value}|${eventUid.value}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)
        return EntityUid("MEM-EP-$ALGORITHM_VERSION-$digest")
    }

    private fun importanceFor(type: CampaignEventType): Double = when (type) {
        CampaignEventType.NPC_KILLED,
        CampaignEventType.SKILL_LEARNED,
        CampaignEventType.MISSION_COMPLETED,
        CampaignEventType.SECRET_LEARNED,
        CampaignEventType.TIME_SKIP,
        CampaignEventType.WORLD_EVENT,
        CampaignEventType.POLITICAL_CHANGE,
        CampaignEventType.PROJECT_COMPLETED -> 0.90

        CampaignEventType.NPC_CREATED,
        CampaignEventType.RELATION_CHANGED,
        CampaignEventType.ITEM_GAINED,
        CampaignEventType.ITEM_LOST,
        CampaignEventType.MISSION_STARTED,
        CampaignEventType.LOCATION_DISCOVERED,
        CampaignEventType.PROJECT_STARTED -> 0.75

        CampaignEventType.NPC_MOVED,
        CampaignEventType.STAT_CHANGED -> 0.55

        CampaignEventType.CUSTOM -> 0.40
    }

    companion object {
        private const val ALGORITHM_VERSION = "v1"
        private const val AUTO_TAG = "auto:episodic:v1"
        private const val DEFAULT_MIN_IMPORTANCE = 0.65
    }
}
