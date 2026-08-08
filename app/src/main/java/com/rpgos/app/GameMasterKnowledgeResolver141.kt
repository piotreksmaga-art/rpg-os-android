package com.rpgos.app

import org.json.JSONObject
import java.util.Locale

/**
 * Resolves NPC knowledge from already durable truth.
 * The AI may point at a source, but it cannot manufacture a BELIEF directly.
 */
class GameMasterKnowledgeResolver141(
    private val repository: UnifiedCampaignRepository,
    private val campaignUid: EntityUid
) {
    suspend fun resolve(action: ProposedWorldAction, params: JSONObject): TruthWrite {
        val receiver = action.actorId?.takeIf { it.isNotBlank() }
            ?: params.optString("receiver_id").trim().takeIf { it.isNotEmpty() }
            ?: error("KNOWLEDGE_PROPAGATE wymaga actorId lub receiver_id.")
        val subjectId = params.optString("source_subject_id").trim().takeIf { it.isNotEmpty() }
            ?: action.targetId?.takeIf { it.isNotBlank() }
            ?: error("KNOWLEDGE_PROPAGATE wymaga source_subject_id lub targetId.")
        val predicate = params.optString("source_predicate").trim().takeIf { it.isNotEmpty() }
            ?: error("KNOWLEDGE_PROPAGATE wymaga source_predicate.")
        val channel = params.optString("channel").trim().uppercase(Locale.ROOT)
            .takeIf { it.isNotEmpty() }
            ?.let { raw ->
                runCatching { KnowledgeChannel141.valueOf(raw) }.getOrElse {
                    error("Nieznany kanał wiedzy: $raw")
                }
            }
            ?: error("KNOWLEDGE_PROPAGATE wymaga channel.")
        require(channel != KnowledgeChannel141.ORGANIZATION) {
            "Kanał ORGANIZATION wymaga trwałego membership/publication i dedykowanej akcji ORGANIZATION_KNOWLEDGE_PROPAGATE."
        }

        val currentTurn = repository.currentTurnId(campaignUid)
        val requestedTruthId = params.optString("source_truth_id").trim().takeIf { it.isNotEmpty() }
        val requestedHolderId = params.optString("source_holder_id").trim().takeIf { it.isNotEmpty() }
        val requestedValue = params.optString("source_value").trim().takeIf { it.isNotEmpty() }

        val candidates = repository.getTruth(
            campaignUid = campaignUid,
            subjectUid = EntityUid(subjectId),
            predicate = predicate,
            atTurnId = currentTurn
        ).filter { truth ->
            (requestedTruthId == null || truth.uid.value == requestedTruthId) &&
                (requestedHolderId == null || truth.holderUid?.value == requestedHolderId) &&
                (requestedValue == null || truth.value == requestedValue)
        }

        require(candidates.isNotEmpty()) {
            "Brak trwałego źródła wiedzy dla $subjectId.$predicate w turze $currentTurn."
        }
        require(candidates.size == 1) {
            "Źródło wiedzy jest niejednoznaczne (${candidates.size}); podaj source_truth_id."
        }

        val sourceNpcUid = params.optString("source_npc_id").trim().takeIf { it.isNotEmpty() }?.let(::EntityUid)
        val confidenceMultiplier = if (params.has("confidence_multiplier")) {
            params.optDouble("confidence_multiplier", 1.0)
        } else 1.0

        val propagated = NpcKnowledgePropagation141().propagate(
            KnowledgePropagationRequest141(
                receiverUid = EntityUid(receiver),
                sourceTruth = candidates.single(),
                channel = channel,
                turnId = currentTurn + 1L,
                sourceNpcUid = sourceNpcUid,
                confidenceMultiplier = confidenceMultiplier
            )
        )

        return TruthWrite(
            kind = TruthKind.BELIEF,
            subjectId = propagated.subjectUid?.value,
            predicate = propagated.predicate,
            value = propagated.value,
            holderId = propagated.holderUid?.value,
            confidence = propagated.provenance.confidence,
            sourceType = propagated.provenance.type,
            sourceId = propagated.provenance.sourceUid?.value,
            validFromTurn = propagated.validFromTurn,
            validUntilTurn = propagated.validUntilTurn,
            knowledgeChannel = channel,
            sourceNpcId = sourceNpcUid?.value
        )
    }
}
