package com.rpgos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Compatibility retrieval layer for GM Engine 141.
 *
 * Legacy ContextBuilder remains a useful reader of the mature Naruto campaign
 * schema. GM 141 enriches that bounded working set with the new durable event,
 * memory, belief, divergence and canonical working-state stores. New retrievers
 * can replace legacy sections incrementally without changing GameMasterEngine.
 */
class GameMasterContextRepository141(
    context: Context,
    private val store: LocalGameStore
) : GameMasterContextRepository {
    private val factory = GameMasterRepositoryFactory(context, store)

    override suspend fun buildContext(request: GameMasterTurnRequest): GameMasterContext {
        val legacy = store.buildContext(
            playerInput = request.playerAction,
            chapter = request.currentChapter.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        )

        factory.openActiveSession().use { session ->
            val repo = session.repository
            val turn = repo.currentTurnId(session.campaignUid)
            val divergences = repo.getActiveDivergences(session.campaignUid)

            val playerUid = resolvePlayerUid(legacy)
            val gmPlayerState = if (playerUid == null) emptyList()
            else repo.getEntityState(session.campaignUid, playerUid, "CHARACTER")
            val gmCampaignState = repo.getEntityState(
                session.campaignUid,
                session.campaignUid,
                "CAMPAIGN"
            )

            val relevantNpcUids = linkedSetOf<EntityUid>()
            legacy.relevantNpcs.forEach { row ->
                (row["character_uid"] as? String)?.takeIf { it.isNotBlank() }?.let { relevantNpcUids += EntityUid(it) }
            }
            legacy.npcKnowledge.forEach { row ->
                (row["holder_uid"] as? String)?.takeIf { it.isNotBlank() }?.let { relevantNpcUids += EntityUid(it) }
            }

            val retrieved = GameMasterRetriever141(repo, session.campaignUid).retrieve(
                playerAction = request.playerAction,
                atTurnId = turn,
                relevantNpcUids = relevantNpcUids,
                eventLimit = 36,
                memoryLimit = 36,
                beliefLimitPerNpc = 16
            )
            val events = retrieved.events
            val memories = retrieved.memories
            val beliefs = retrieved.beliefsByHolder.values.flatten()

            // Lineage is intentionally bounded independently from BELIEF text.
            // It explains how a relevant NPC learned something without exposing
            // the whole campaign-wide rumour graph to the model.
            val knowledgeLineage = linkedMapOf<EntityUid, List<KnowledgeTransmission141>>()
            relevantNpcUids.take(16).forEach { npcUid ->
                val rows = session.knowledgeStore.knowledgeTransmissionsForReceiver(
                    campaignUid = session.campaignUid,
                    receiverUid = npcUid,
                    beforeOrAtTurn = turn,
                    limit = 8
                )
                if (rows.isNotEmpty()) knowledgeLineage[npcUid] = rows
            }

            val budget = request.contextBudget
            val scene = section(
                "CURRENT_SCENE",
                JSONObject(legacy.scene).apply {
                    put("time_legacy", JSONObject(legacy.time))
                    put("gm141_campaign_state", JSONArray(gmCampaignState.map { stateJson(it) }))
                    put("player_action", request.playerAction)
                    put("gm141_turn", turn)
                }.toString(),
                priority = 100,
                limit = allocation(budget, 0.12, 14_000)
            )

            val playerState = section(
                "PLAYER_STATE",
                JSONObject().apply {
                    put("player_uid", playerUid?.value)
                    put("gm141_source_of_truth", JSONArray(gmPlayerState.map { stateJson(it) }))
                    put("legacy_status", JSONObject(legacy.playerStatus))
                    put("legacy_skills", JSONArray(legacy.playerSkills.map(::JSONObject)))
                    put("legacy_techniques", JSONArray(legacy.playerTechniques.map(::JSONObject)))
                    put("legacy_organizations", JSONArray(legacy.playerOrganizations.map(::JSONObject)))
                }.toString(),
                priority = 100,
                limit = allocation(budget, 0.16, budget.stateCharacters)
            )

            val activeWorld = section(
                "ACTIVE_WORLD_STATE",
                JSONObject().apply {
                    put("npcs", JSONArray(legacy.relevantNpcs.map(::JSONObject)))
                    put("npc_knowledge_legacy", JSONArray(legacy.npcKnowledge.map(::JSONObject)))
                    put("npc_beliefs_gm141", truthsJson(beliefs))
                    put("npc_belief_holders_gm141", JSONArray(retrieved.beliefsByHolder.keys.map { it.value }))
                    put(
                        "npc_knowledge_lineage_gm141",
                        JSONArray(
                            knowledgeLineage.flatMap { (holder, transmissions) ->
                                transmissions.map { transmissionJson(holder, it) }
                            }
                        )
                    )
                    put("missions", JSONArray(legacy.missions.map(::JSONObject)))
                    put("pressures", JSONArray(legacy.worldPressures.map(::JSONObject)))
                    put("active_world_events", JSONArray(legacy.activeWorldEvents.map(::JSONObject)))
                }.toString(),
                priority = 95,
                limit = allocation(budget, 0.14, 18_000)
            )

            val activeThreads = section(
                "ACTIVE_THREADS",
                JSONObject().apply {
                    put("legacy_threads", JSONArray(legacy.activeThreads.map(::JSONObject)))
                    put("canon_divergences", JSONArray(divergences.map { divergenceJson(it) }))
                }.toString(),
                priority = 92,
                limit = allocation(budget, 0.08, 12_000)
            )

            val relevantMemories = section(
                "RELEVANT_MEMORIES",
                JSONObject().apply {
                    put("gm141", JSONArray(memories.map { memoryJson(it) }))
                    put("legacy_long_term", JSONArray(legacy.retrievedLongTermMemory.map(::JSONObject)))
                    put("legacy_npc_memories", JSONArray(legacy.npcMemories.map(::JSONObject)))
                }.toString(),
                priority = 90,
                limit = allocation(budget, 0.18, budget.memoryCharacters)
            )

            val canon = section(
                "RELEVANT_CANON",
                JSONObject().apply {
                    put("constraints", JSONArray(legacy.canonConstraints.map(::JSONObject)))
                    put("canonical_npcs", JSONArray(legacy.relevantNpcs.map(::JSONObject)))
                    put("active_divergences", JSONArray(divergences.map { divergenceJson(it) }))
                }.toString(),
                priority = 98,
                limit = allocation(budget, 0.14, budget.worldKnowledgeCharacters)
            )

            val rules = section(
                "GM_INVARIANTS",
                """
                FACT, BELIEF and NARRATIVE are distinct. Narrative is never evidence by itself.
                A BELIEF belongs only to its holder and must not leak to another NPC without a valid information path.
                npc_knowledge_lineage_gm141 is the auditable path by which a holder acquired a BELIEF; do not invent missing transfers.
                OBSERVATION, REPORT and INFERENCE are different knowledge channels and may carry different confidence.
                Campaign Source of Truth and accepted divergences override the untouched canon baseline.
                GM141 campaign state is authoritative over legacy scene/time values when both are present.
                Do not retroactively remove established skills, achievements or facts without an explicit world event that causes the loss.
                Do not invent prior history. If an asserted past event is absent from durable state and retrieved history, treat it as unsupported.
                Use exact GM141 field keys from gm141_source_of_truth or gm141_campaign_state when proposing state changes.
                Return semantic proposed_actions only. Do not return SQL, table mutations or trusted StatePatch objects.
                Prose alone never changes canonical campaign state.
                """.trimIndent(),
                priority = 100,
                limit = allocation(budget, 0.08, budget.rulesCharacters)
            )

            val recentNarrative = section(
                "RECENT_HISTORY",
                JSONObject().apply {
                    put("legacy_chronicle", JSONArray(legacy.recentChronicle.map(::JSONObject)))
                    put("durable_events", JSONArray(events.map { eventJson(it) }))
                }.toString(),
                priority = 88,
                limit = allocation(budget, 0.10, budget.recentNarrativeCharacters)
            )

            return GameMasterContext(
                campaignId = session.campaignUid.value,
                chapter = request.currentChapter,
                scene = scene,
                playerState = playerState,
                activeWorldState = activeWorld,
                activeThreads = activeThreads,
                relevantMemories = relevantMemories,
                canonKnowledge = canon,
                rules = rules,
                recentNarrative = recentNarrative,
                provenance = listOf(
                    ContextSource("CAMPAIGN_DB", session.campaignUid.value, "active mutable state and durable GM history"),
                    ContextSource("WORLD_DB", session.worldPackUid.value, "canon and worldpack data"),
                    ContextSource("GM141_STATE", session.campaignUid.value, "canonical mutable working state"),
                    ContextSource("GM141_EVENT_STORE", session.campaignUid.value, "recent accepted events"),
                    ContextSource("GM141_MEMORY", session.campaignUid.value, "bounded temporal episodic and semantic memory"),
                    ContextSource("GM141_RETRIEVER", session.campaignUid.value, "query-ranked temporal retrieval with holder-scoped beliefs"),
                    ContextSource("GM141_KNOWLEDGE_LEDGER", session.campaignUid.value, "bounded auditable NPC information paths")
                )
            )
        }
    }

    private fun resolvePlayerUid(legacy: ContextBundle): EntityUid? {
        val legacyUid = (legacy.playerStatus["player_uid"] as? String)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::EntityUid)
        if (legacyUid != null) return legacyUid

        return runCatching {
            store.openSaveDb().use { db ->
                GameMasterSessionReader141(db).read()?.playerUid
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(::EntityUid)
            }
        }.getOrNull()
    }

    private fun allocation(budget: ContextBudget, fraction: Double, sectionMaximum: Int): Int =
        minOf((budget.maxCharacters * fraction).toInt().coerceAtLeast(256), sectionMaximum.coerceAtLeast(256))

    private fun section(title: String, content: String, priority: Int, limit: Int): ContextSection {
        val clipped = if (content.length <= limit) content else {
            content.take((limit - 64).coerceAtLeast(0)) + "\n[TRUNCATED_BY_CONTEXT_BUDGET]"
        }
        return ContextSection(title, clipped, priority, clipped.length)
    }

    private fun stateJson(state: CampaignStateField): JSONObject = JSONObject().apply {
        put("entity_type", state.entityType)
        put("entity_id", state.entityUid.value)
        put("field", state.field)
        put("value", state.value)
        put("valid_from_turn", state.validFromTurn)
        put("provenance_type", state.provenanceType?.name)
        put("provenance_id", state.provenanceUid?.value)
    }

    private fun truthsJson(items: List<CampaignTruth>): JSONArray = JSONArray(items.map { truth ->
        JSONObject().apply {
            put("id", truth.uid.value)
            put("kind", truth.kind.name)
            put("holder", truth.holderUid?.value)
            put("subject", truth.subjectUid?.value)
            put("predicate", truth.predicate)
            put("value", truth.value)
            put("valid_from_turn", truth.validFromTurn)
            put("valid_until_turn", truth.validUntilTurn)
            put("source", truth.provenance.type.name)
            put("source_id", truth.provenance.sourceUid?.value)
            put("source_turn", truth.provenance.turnId)
            put("confidence", truth.provenance.confidence)
        }
    })

    private fun transmissionJson(
        holder: EntityUid,
        transmission: KnowledgeTransmission141
    ): JSONObject = JSONObject().apply {
        put("holder", holder.value)
        put("transmission_id", transmission.transmissionUid.value)
        put("source_truth_id", transmission.sourceTruthUid.value)
        put("source_npc_id", transmission.sourceNpcUid?.value)
        put("receiver_id", transmission.receiverUid.value)
        put("resulting_belief_id", transmission.resultingBeliefUid.value)
        put("channel", transmission.channel.name)
        put("turn", transmission.turnId)
        put("confidence", transmission.confidence)
    }

    private fun eventJson(event: DurableCampaignEvent): JSONObject = JSONObject().apply {
        put("id", event.eventUid.value)
        put("turn", event.turnId)
        put("sequence", event.sequence)
        put("type", event.type.name)
        put("actor", event.actorUid?.value)
        put("target", event.targetUid?.value)
        put("cause_event", event.causeEventUid?.value)
        put("description", event.description)
        put("payload", event.payloadJson)
    }

    private fun memoryJson(memory: DurableMemoryRecord): JSONObject = JSONObject().apply {
        put("id", memory.memoryUid.value)
        put("kind", memory.kind.name)
        put("subject", memory.subjectUid?.value)
        put("text", memory.text)
        put("importance", memory.importance)
        put("created_turn", memory.createdTurn)
        put("source_events", JSONArray(memory.sourceEventUids.map { it.value }))
        put("tags", JSONArray(memory.tags.toList()))
    }

    private fun divergenceJson(divergence: CanonDivergence): JSONObject = JSONObject().apply {
        put("id", divergence.uid.value)
        put("canon_subject", divergence.canonSubjectUid.value)
        put("canon_event", divergence.canonEventUid?.value)
        put("type", divergence.divergenceType)
        put("description", divergence.description)
        put("caused_by_event", divergence.causedByEventUid?.value)
        put("created_turn", divergence.createdTurn)
        put("active", divergence.active)
    }
}
