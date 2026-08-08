package com.rpgos.app

import org.json.JSONObject
import java.math.BigDecimal
import java.util.Locale

/**
 * Universe-neutral deterministic resolver for the first GM 141 runtime.
 *
 * The language model may propose semantic actions, but it never supplies a
 * trusted final database patch. This resolver reads current Source of Truth and
 * calculates the durable mutations/events that follow from the proposal.
 * World modules can later decorate or replace this resolver with Naruto,
 * Bleach, etc. mechanics without changing persistence contracts.
 */
class GameMasterRuleResolver141(
    private val repository: UnifiedCampaignRepository,
    private val campaignUid: EntityUid
) : GameMasterRuleResolver {

    override suspend fun resolve(
        request: GameMasterTurnRequest,
        context: GameMasterContext,
        proposal: GameMasterProposal
    ): GameMasterTurnResult {
        require(request.campaignId == campaignUid.value) { "Resolver działa dla innej kampanii." }
        require(context.campaignId == campaignUid.value) { "Resolver otrzymał kontekst innej kampanii." }

        val mutations = mutableListOf<GameStateMutation>()
        val truths = mutableListOf<TruthWrite>()
        val divergences = mutableListOf<DivergenceWrite>()
        val events = mutableListOf<WorldEventWrite>()
        val warnings = proposal.diagnostics.warnings.toMutableList()
        val usedEventKeys = linkedSetOf<String>()

        for ((index, action) in proposal.proposedActions.withIndex()) {
            val params = parseParams(action, index)
            when (normalizeAction(action.actionType)) {
                "EMIT_EVENT", "WORLD_EVENT" -> {
                    events += eventFrom(action, params, request.currentChapter, usedEventKeys, index)
                }

                "STATE_SET" -> {
                    val descriptor = stateDescriptor(action, params)
                    val current = currentValue(descriptor)
                    val next = requiredString(params, "value", action, index)
                    val event = optionalEvent(action, params, request.currentChapter, usedEventKeys, index)
                    event?.let { events += it }
                    mutations += GameStateMutation(
                        entityType = descriptor.entityType,
                        entityId = descriptor.entityId,
                        field = descriptor.field,
                        operation = MutationOperation.SET,
                        oldValue = current,
                        newValue = next,
                        reason = reason(action, params),
                        causedByEventKey = event?.eventKey
                    )
                }

                "STATE_INCREMENT", "STATE_DECREMENT" -> {
                    val descriptor = stateDescriptor(action, params)
                    val storedCurrent = currentValue(descriptor)
                    val current = (storedCurrent ?: "0").toBigDecimalOrNull()
                        ?: error("${descriptor.entityId}.${descriptor.field} nie jest liczbą: '$storedCurrent'.")
                    val magnitude = requiredDecimal(params, "amount", action, index).abs()
                    val delta = if (normalizeAction(action.actionType) == "STATE_DECREMENT") magnitude.negate() else magnitude
                    val next = current.add(delta)
                    val event = optionalEvent(action, params, request.currentChapter, usedEventKeys, index)
                    event?.let { events += it }
                    mutations += GameStateMutation(
                        entityType = descriptor.entityType,
                        entityId = descriptor.entityId,
                        field = descriptor.field,
                        operation = if (delta.signum() < 0) MutationOperation.DECREMENT else MutationOperation.INCREMENT,
                        oldValue = storedCurrent,
                        newValue = normalizeNumber(next),
                        reason = reason(action, params),
                        causedByEventKey = event?.eventKey
                    )
                }

                "STATE_REMOVE" -> {
                    val descriptor = stateDescriptor(action, params)
                    val current = currentValue(descriptor)
                    val event = optionalEvent(action, params, request.currentChapter, usedEventKeys, index)
                    event?.let { events += it }
                    mutations += GameStateMutation(
                        entityType = descriptor.entityType,
                        entityId = descriptor.entityId,
                        field = descriptor.field,
                        operation = MutationOperation.REMOVE,
                        oldValue = current,
                        newValue = null,
                        reason = reason(action, params),
                        causedByEventKey = event?.eventKey
                    )
                }

                "ASSERT_FACT" -> {
                    truths += truthFrom(action, params, TruthKind.FACT)
                }

                "ASSERT_BELIEF", "KNOWLEDGE_PROPAGATE" -> {
                    truths += GameMasterKnowledgeResolver141(repository, campaignUid).resolve(action, params)
                }

                "ASSERT_NARRATIVE" -> {
                    truths += truthFrom(action, params, TruthKind.NARRATIVE)
                }

                "CANON_DIVERGENCE" -> {
                    val event = optionalEvent(action, params, request.currentChapter, usedEventKeys, index)
                    event?.let { events += it }
                    divergences += DivergenceWrite(
                        canonSubjectId = requiredString(params, "canon_subject_id", action, index),
                        canonEventId = params.optNonBlank("canon_event_id"),
                        divergenceType = requiredString(params, "divergence_type", action, index),
                        description = requiredString(params, "description", action, index),
                        causedByEventKey = event?.eventKey ?: params.optNonBlank("caused_by_event_key")
                    )
                }

                else -> {
                    // Unknown actions never silently mutate state. Preserve them as
                    // auditable CUSTOM events so a future world module can teach
                    // the resolver their mechanics without losing the proposal.
                    val key = uniqueEventKey(
                        params.optNonBlank("event_key") ?: "unresolved-${index + 1}",
                        usedEventKeys
                    )
                    events += WorldEventWrite(
                        eventType = "CUSTOM",
                        eventKey = key,
                        description = action.reason.ifBlank {
                            "Nierozpoznana akcja MG: ${action.actionType}"
                        },
                        effectiveChapter = request.currentChapter,
                        actorId = action.actorId,
                        targetId = action.targetId,
                        payloadJson = JSONObject().apply {
                            put("unresolved_action_type", action.actionType)
                            put("parameters", params)
                        }.toString(),
                        visibility = EventVisibility.GM_ONLY
                    )
                    warnings += "UNRESOLVED_ACTION:${action.actionType}"
                }
            }
        }

        return GameMasterTurnResult(
            narrative = proposal.narrativeDraft,
            stateMutations = mutations,
            truthWrites = truths,
            divergenceWrites = divergences,
            memoryWrites = proposal.proposedMemories,
            chronicleEntries = proposal.proposedChronicleEntries,
            worldEvents = events,
            diagnostics = proposal.diagnostics.copy(warnings = warnings.distinct())
        )
    }

    private suspend fun currentValue(descriptor: StateDescriptor): String? =
        repository.getEntityState(
            campaignUid = campaignUid,
            entityUid = EntityUid(descriptor.entityId),
            entityType = descriptor.entityType
        ).firstOrNull { it.field == descriptor.field }?.value

    private fun stateDescriptor(action: ProposedWorldAction, params: JSONObject): StateDescriptor {
        val entityId = action.targetId?.takeIf { it.isNotBlank() }
            ?: params.optNonBlank("entity_id")
            ?: error("${action.actionType} wymaga targetId lub entity_id.")
        val entityType = params.optNonBlank("entity_type") ?: "ENTITY"
        val field = params.optNonBlank("field") ?: error("${action.actionType} wymaga field.")
        return StateDescriptor(entityType, entityId, field)
    }

    private fun eventFrom(
        action: ProposedWorldAction,
        params: JSONObject,
        currentChapter: Long,
        usedKeys: MutableSet<String>,
        index: Int
    ): WorldEventWrite {
        val key = uniqueEventKey(
            params.optNonBlank("event_key") ?: "event-${index + 1}",
            usedKeys
        )
        return WorldEventWrite(
            eventType = params.optNonBlank("event_type") ?: "CUSTOM",
            eventKey = key,
            description = params.optNonBlank("description") ?: action.reason.ifBlank { action.actionType },
            effectiveChapter = params.optLongOrNull("effective_chapter") ?: currentChapter,
            actorId = action.actorId ?: params.optNonBlank("actor_id"),
            targetId = action.targetId ?: params.optNonBlank("target_id"),
            causeEventKey = params.optNonBlank("cause_event_key"),
            payloadJson = params.optJSONObject("payload")?.toString() ?: "{}",
            visibility = params.optNonBlank("visibility")
                ?.let { raw -> runCatching { EventVisibility.valueOf(raw.uppercase(Locale.ROOT)) }.getOrNull() }
                ?: EventVisibility.WORLD_INTERNAL
        )
    }

    private fun optionalEvent(
        action: ProposedWorldAction,
        params: JSONObject,
        currentChapter: Long,
        usedKeys: MutableSet<String>,
        index: Int
    ): WorldEventWrite? {
        val eventKey = params.optNonBlank("event_key") ?: return null
        return eventFrom(
            action,
            JSONObject(params.toString()).apply { put("event_key", eventKey) },
            currentChapter,
            usedKeys,
            index
        )
    }

    private fun truthFrom(
        action: ProposedWorldAction,
        params: JSONObject,
        kind: TruthKind
    ): TruthWrite {
        val sourceType = params.optNonBlank("source_type")
            ?.let { raw -> runCatching { ProvenanceType.valueOf(raw.uppercase(Locale.ROOT)) }.getOrNull() }
            ?: ProvenanceType.SYSTEM_SIMULATION
        val confidence = params.optDouble("confidence", 1.0)
        require(confidence in 0.0..1.0) { "confidence musi mieścić się w zakresie 0..1." }
        val validFrom = params.optLongOrNull("valid_from_turn")
        val validUntil = params.optLongOrNull("valid_until_turn")
        return TruthWrite(
            kind = kind,
            subjectId = action.targetId ?: params.optNonBlank("subject_id"),
            predicate = params.optNonBlank("predicate") ?: error("${action.actionType} wymaga predicate."),
            value = params.optNonBlank("value") ?: error("${action.actionType} wymaga value."),
            holderId = if (kind == TruthKind.BELIEF) {
                action.actorId ?: params.optNonBlank("holder_id")
                    ?: error("ASSERT_BELIEF wymaga actorId lub holder_id.")
            } else params.optNonBlank("holder_id"),
            confidence = confidence,
            sourceType = sourceType,
            sourceId = params.optNonBlank("source_id"),
            validFromTurn = validFrom,
            validUntilTurn = validUntil
        )
    }

    private fun parseParams(action: ProposedWorldAction, index: Int): JSONObject =
        runCatching { JSONObject(action.parametersJson) }.getOrElse {
            error("Akcja #${index + 1} ${action.actionType} ma niepoprawny parametersJson: ${it.message}")
        }

    private fun reason(action: ProposedWorldAction, params: JSONObject): String =
        action.reason.ifBlank { params.optNonBlank("reason") ?: "Resolved by GM Engine 141" }

    private fun requiredString(
        params: JSONObject,
        key: String,
        action: ProposedWorldAction,
        index: Int
    ): String = params.optNonBlank(key)
        ?: error("Akcja #${index + 1} ${action.actionType} wymaga '$key'.")

    private fun requiredDecimal(
        params: JSONObject,
        key: String,
        action: ProposedWorldAction,
        index: Int
    ): BigDecimal = requiredString(params, key, action, index).toBigDecimalOrNull()
        ?: error("Akcja #${index + 1} ${action.actionType}: '$key' musi być liczbą.")

    private fun uniqueEventKey(raw: String, used: MutableSet<String>): String {
        val key = raw.trim()
        require(key.isNotBlank()) { "eventKey nie może być pusty." }
        require(used.add(key)) { "Duplikat eventKey w propozycji: $key" }
        return key
    }

    private fun normalizeAction(raw: String): String =
        raw.trim().uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]+"), "_").trim('_')

    private fun normalizeNumber(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

    private fun JSONObject.optNonBlank(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (!has(key) || isNull(key)) null else runCatching { getLong(key) }.getOrNull()

    private data class StateDescriptor(
        val entityType: String,
        val entityId: String,
        val field: String
    )
}
