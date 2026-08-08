package com.rpgos.app

import java.text.Normalizer
import java.util.Locale
import kotlin.math.max

/**
 * Deterministic bounded retrieval for long-running GM141 campaigns.
 *
 * Retrieval is performed only from accepted durable state. It never treats
 * narrative prose as objective truth and never mixes BELIEF records between
 * holders. All returned data is constrained to [atTurnId].
 */
class GameMasterRetriever141(
    private val repository: UnifiedCampaignRepository,
    private val campaignUid: EntityUid
) {
    data class Result(
        val events: List<DurableCampaignEvent>,
        val memories: List<DurableMemoryRecord>,
        val beliefsByHolder: Map<EntityUid, List<CampaignTruth>>
    )

    suspend fun retrieve(
        playerAction: String,
        atTurnId: Long,
        relevantNpcUids: Collection<EntityUid>,
        eventLimit: Int = 36,
        memoryLimit: Int = 36,
        beliefLimitPerNpc: Int = 16
    ): Result {
        require(atTurnId >= 0L) { "atTurnId nie może być ujemny." }
        require(eventLimit >= 0 && memoryLimit >= 0 && beliefLimitPerNpc >= 0)

        val queryTerms = terms(playerAction)

        val events = repository.recentEvents(
            campaignUid = campaignUid,
            beforeOrAtTurn = atTurnId,
            limit = max(eventLimit * 5, eventLimit)
        )
            .asSequence()
            .filter { it.turnId <= atTurnId }
            .sortedByDescending { eventScore(it, queryTerms, atTurnId) }
            .take(eventLimit)
            .toList()

        val memories = repository.memories(
            campaignUid = campaignUid,
            limit = max(memoryLimit * 6, memoryLimit)
        )
            .asSequence()
            .filter { it.createdTurn <= atTurnId }
            .sortedByDescending { memoryScore(it, queryTerms, atTurnId) }
            .take(memoryLimit)
            .toList()

        val beliefs = linkedMapOf<EntityUid, List<CampaignTruth>>()
        relevantNpcUids.distinct().take(16).forEach { holder ->
            val holderBeliefs = repository.getBeliefs(
                campaignUid = campaignUid,
                holderUid = holder,
                atTurnId = atTurnId,
                limit = max(beliefLimitPerNpc * 4, beliefLimitPerNpc)
            )
                .asSequence()
                .filter { truth ->
                    truth.kind == TruthKind.BELIEF &&
                        truth.holderUid == holder &&
                        (truth.validFromTurn == null || truth.validFromTurn <= atTurnId) &&
                        (truth.validUntilTurn == null || truth.validUntilTurn >= atTurnId)
                }
                .sortedByDescending { beliefScore(it, queryTerms, atTurnId) }
                .take(beliefLimitPerNpc)
                .toList()
            if (holderBeliefs.isNotEmpty()) beliefs[holder] = holderBeliefs
        }

        return Result(events, memories, beliefs)
    }

    private fun eventScore(
        event: DurableCampaignEvent,
        queryTerms: Set<String>,
        atTurnId: Long
    ): Double {
        val text = buildString {
            append(event.type.name).append(' ')
            append(event.description).append(' ')
            append(event.actorUid?.value.orEmpty()).append(' ')
            append(event.targetUid?.value.orEmpty())
        }
        return lexicalScore(text, queryTerms) * 8.0 + recencyScore(event.turnId, atTurnId) * 3.0
    }

    private fun memoryScore(
        memory: DurableMemoryRecord,
        queryTerms: Set<String>,
        atTurnId: Long
    ): Double =
        lexicalScore(
            memory.text + " " + memory.tags.joinToString(" ") + " " + memory.subjectUid?.value.orEmpty(),
            queryTerms
        ) * 8.0 + memory.importance * 5.0 + recencyScore(memory.createdTurn, atTurnId) * 2.0

    private fun beliefScore(
        truth: CampaignTruth,
        queryTerms: Set<String>,
        atTurnId: Long
    ): Double =
        lexicalScore(
            listOfNotNull(
                truth.subjectUid?.value,
                truth.predicate,
                truth.value,
                truth.provenance.type.name
            ).joinToString(" "),
            queryTerms
        ) * 8.0 + truth.provenance.confidence * 4.0 +
            recencyScore(truth.provenance.turnId ?: truth.validFromTurn ?: 0L, atTurnId)

    private fun lexicalScore(text: String, queryTerms: Set<String>): Double {
        if (queryTerms.isEmpty()) return 0.0
        val haystack = terms(text)
        if (haystack.isEmpty()) return 0.0
        val overlap = queryTerms.count { it in haystack }
        return overlap.toDouble() / queryTerms.size.toDouble()
    }

    private fun recencyScore(turn: Long, atTurnId: Long): Double {
        val distance = (atTurnId - turn).coerceAtLeast(0L)
        return 1.0 / (1.0 + distance.toDouble() / 25.0)
    }

    private fun terms(text: String): Set<String> {
        val normalized = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return TOKEN_REGEX.findAll(normalized)
            .map { it.value }
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .take(MAX_TERMS)
            .toSet()
    }

    companion object {
        private const val MAX_TERMS = 64
        private val TOKEN_REGEX = Regex("[a-z0-9_\\-]+")
        private val STOP_WORDS = setOf(
            "ale", "bez", "dla", "jest", "oraz", "przez", "sie", "ten", "tego", "tym",
            "with", "from", "that", "this", "the", "and", "for", "into"
        )
    }
}
