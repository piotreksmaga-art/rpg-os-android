package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.*

data class Phase64MechanicalResourceHolding(val holder: DomainRef, val resourceUid: String)

/** Capacity identifies one existing body's pool; length-prefixed identity is shared with
 * the inventory holding convention and remains unambiguous for imported identifier text. */
fun phase64MechanicalResource(holder: DomainRef, resourceUid: String): DomainRef =
    DomainRef("MECHANICAL_RESOURCE", phase64InventoryResource(holder, resourceUid).uid)

fun phase64MechanicalResourceHolding(resource: DomainRef): Phase64MechanicalResourceHolding? {
    if (resource.kindUid != "MECHANICAL_RESOURCE") return null
    val holding = phase64InventoryHolding(DomainRef("INVENTORY_HOLDING", resource.uid)) ?: return null
    return Phase64MechanicalResourceHolding(holding.holder, holding.itemInstanceUid)
}

/** The existing spatial/perception owners capture these facts for this actor's authorized
 * participants. A missing position is unavailable, never an invented common origin. */
internal data class Phase64CombatCapture(
    val spatialState: CombatSpatialState,
    val perception: List<CombatPerceptionEvidence> = emptyList(),
    val spatialFacts: Map<String, String> = emptyMap(),
    val timingFacts: Map<String, Long> = emptyMap()
)

internal sealed interface Phase64CombatRequestPreparation {
    data class Prepared(val request: UniversalCombatRequest) : Phase64CombatRequestPreparation
    data class Unavailable(val reasonUid: String) : Phase64CombatRequestPreparation
}

/** Core adapts an already selected Phase62 option to the single Phase50 combat pipeline.
 * It neither chooses an NPC action nor substitutes a generic contract for a missing rule. */
internal object Phase64CombatOwnerPreparation {
    fun prepare(
        actor: MechanicalActorView,
        targets: List<MechanicalActorView>,
        rule: BackgroundProcessDefinition,
        parameters: Map<String, String>,
        scope: BackgroundProcessEvaluationScope,
        authorization: NpcActionAuthorization?,
        contracts: CombatAbilityContractPort,
        capture: Phase64CombatCapture
    ): Phase64CombatRequestPreparation {
        fun unavailable(reason: String) = Phase64CombatRequestPreparation.Unavailable(reason)
        if (rule.domain != "CONFLICT" || rule.operation != "COMBAT") return unavailable("P64:COMBAT_RULE_REQUIRED")
        val auth = authorization ?: return unavailable("P64:NPC_COMBAT_DECISION_REQUIRED")
        if (actor.kind == MechanicalActorKind.ACTIVE_PLAYER || actor.actor.uid == auth.scope.activePlayerUid)
            return unavailable("P64:ACTIVE_PLAYER_CONTROL_FORBIDDEN")
        if (actor.conditions.any { it.conditionUid == "DEAD" && it.intensity > 0 })
            return unavailable("P64:COMBAT_ACTOR_DEAD")
        if (auth.scope.temporal != scope.temporal || auth.scope.actor != actor.actor || auth.decisionUid != parameters["decision_uid"] ||
            actor.campaignUid != scope.temporal.campaignUid)
            return unavailable("P64:NPC_COMBAT_DECISION_CHANGED")
        val target = reference(parameters, "target") ?: return unavailable("P64:COMBAT_TARGET_REQUIRED")
        val abilityUid = parameters["ability_uid"] ?: return unavailable("P64:COMBAT_ABILITY_REQUIRED")
        if (target == actor.actor || targets.isEmpty() || targets.size > 256 || targets.map { it.actor }.distinct().size != targets.size ||
            targets.any { it.actor == actor.actor || it.campaignUid != actor.campaignUid } || targets.none { it.actor == target })
            return unavailable("P64:COMBAT_PARTICIPANT_CAPTURE_INVALID")
        if (targets.any { body -> body.conditions.any { it.conditionUid == "DEAD" && it.intensity > 0 } })
            return unavailable("P64:COMBAT_TARGET_DEAD")
        val option = runCatching { Json.parseToJsonElement(auth.optionCanonical).jsonObject }.getOrNull()
            ?: return unavailable("P64:NPC_COMBAT_OPTION_CHANGED")
        if (option["target"]?.takeUnless { it == JsonNull }?.let(NpcBrainCodec::readRef) != target ||
            option["capability"]?.jsonPrimitive?.content != abilityUid ||
            option["mechanics_owner"]?.jsonPrimitive?.contentOrNull != "UNIVERSAL_COMBAT")
            return unavailable("P64:NPC_COMBAT_OPTION_CHANGED")
        if (abilityUid !in actor.executableAbilityUids) return unavailable("P64:COMBAT_CAPABILITY_REQUIRED")
        val query = CombatAbilityContractQuery(actor.campaignUid, abilityUid, abilityUid, targets.size, targets.any { it.aggregatePopulation != null })
        val ability = contracts.npcContractFor(query) ?: return unavailable("P64:REGISTERED_COMBAT_CONTRACT_REQUIRED")
        if (ability.resourceCost > 0 && ability.resourceUid == null) return unavailable("P64:COMBAT_RESOURCE_CONTRACT_REQUIRED")
        if (ability.abilityUid != abilityUid || (ability.areaRadiusMillimetres == null && targets.size != 1))
            return unavailable("P64:COMBAT_CONTRACT_CHANGED")
        val approvedParameters = option["parameters"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
        val approvedCosts = option["costs"]?.jsonObject?.mapValues { it.value.jsonPrimitive.long }.orEmpty()
        val costs = ability.resourceUid?.let { mapOf(it to ability.resourceCost) }.orEmpty()
        if (approvedParameters != mapOf("npc_ability_contract" to npcCombatContractFingerprint(ability)) || approvedCosts != costs ||
            option["mechanical_effect"]?.jsonPrimitive?.contentOrNull != ability.effectKinds.first().name)
            return unavailable("P64:COMBAT_CONTRACT_CHANGED")
        val participants = listOf(actor) + targets.sortedWith(compareBy<MechanicalActorView> { it.actor.kindUid }.thenBy { it.actor.uid })
        val refs = participants.map { it.actor }.toSet()
        if (!capture.spatialState.positions.keys.containsAll(refs)) return unavailable("P64:COMBAT_SPATIAL_CAPTURE_REQUIRED")
        if (capture.spatialState.positions.keys != refs || !refs.containsAll(capture.spatialState.orientationsMilliDegrees.keys) ||
            !refs.containsAll(capture.spatialState.velocitiesMillimetresPerTick.keys) ||
            capture.perception.any { it.observer !in refs || it.perceivedSubject !in refs || it.availableAtOrder > scope.temporal.baseCommitOrder })
            return unavailable("P64:COMBAT_PARTICIPANT_CAPTURE_INVALID")
        val event = parameters["p64_logical_event_uid"]?.takeIf(String::isNotBlank) ?: return unavailable("P64:LOGICAL_EVENT_REQUIRED")
        val process = parameters["p64_process_uid"]?.takeIf(String::isNotBlank) ?: return unavailable("P64:PROCESS_REQUIRED")
        val spatial = capture.spatialState.copy(
            positions = capture.spatialState.positions.toSortedMap(compareBy<DomainRef> { it.kindUid }.thenBy { it.uid }),
            orientationsMilliDegrees = capture.spatialState.orientationsMilliDegrees.toSortedMap(compareBy<DomainRef> { it.kindUid }.thenBy { it.uid }),
            velocitiesMillimetresPerTick = capture.spatialState.velocitiesMillimetresPerTick.toSortedMap(compareBy<DomainRef> { it.kindUid }.thenBy { it.uid })
        )
        val canonicalActors = participants.map(::canonicalBody)
        val perception = capture.perception.sortedWith(compareBy<CombatPerceptionEvidence> { it.observer.kindUid }.thenBy { it.observer.uid }
            .thenBy { it.perceivedSubject.kindUid }.thenBy { it.perceivedSubject.uid }.thenBy { it.evidenceUid })
        // History generation protects admission; it is deliberately not world/event identity.
        val fingerprint = phase63Hash(listOf("P64:COMBAT_CAPTURE:1", scope.worldSeed, rule.uid, rule.version, process, event,
            canonicalActors, npcCombatContractFingerprint(ability), spatial, perception, capture.spatialFacts.toSortedMap(), capture.timingFacts.toSortedMap()).joinToString("|"))
        val intent = CombatIntent("P64:COMBAT:$event", actor.campaignUid, actor.actor, target, abilityUid,
            VolitionalActionSource.NPC_DECISION_ENGINE, "DISABLE", scope.temporal.baseCommitOrder, auth)
        return Phase64CombatRequestPreparation.Prepared(UniversalCombatRequest(intent, ImmutableCombatSnapshot(
            "P64:SNAPSHOT:$fingerprint", actor.campaignUid, scope.temporal.baseCommitOrder, canonicalActors, perception,
            capture.spatialFacts.toSortedMap(), capture.timingFacts.toSortedMap(), fingerprint), ability, spatial))
    }

    /** Costs are settled by the existing resource owner once. Phase50 checks affordability
     * but does not create the attacking body's resource debit itself. */
    fun settle(request: UniversalCombatRequest, rule: BackgroundProcessDefinition, parameters: Map<String, String>,
        scope: BackgroundProcessEvaluationScope): WorldConsequencePlan {
        val auth = request.intent.npcControlAuthorization ?: return backgroundBlocked("P64:NPC_COMBAT_DECISION_REQUIRED")
        if (request.intent.source != VolitionalActionSource.NPC_DECISION_ENGINE || auth.scope.temporal != scope.temporal ||
            auth.scope.actor != request.intent.actor || auth.decisionUid != parameters["decision_uid"] ||
            auth.scope.activePlayerUid == request.intent.actor.uid)
            return backgroundBlocked("P64:NPC_COMBAT_DECISION_CHANGED")
        val actor = request.snapshot.actors.singleOrNull { it.actor == request.intent.actor }
            ?: return backgroundBlocked("P64:COMBAT_CAPTURE_CHANGED")
        val capture = Phase64CombatCapture(request.spatialState, request.snapshot.perception, request.snapshot.spatialFacts, request.snapshot.timingFacts)
        val prepared = prepare(actor, request.snapshot.actors.filter { it.actor != actor.actor }, rule, parameters, scope, auth,
            CombatAbilityContractPort.registered(listOf(request.ability)), capture)
        if (prepared is Phase64CombatRequestPreparation.Unavailable) return backgroundBlocked(prepared.reasonUid)
        prepared as Phase64CombatRequestPreparation.Prepared
        if (prepared.request != request) return backgroundBlocked("P64:COMBAT_CAPTURE_CHANGED")
        val result = UniversalCombatEngine().resolve(request)
        if (result is CombatResolution.Rejected) return backgroundBlocked("P64:COMBAT:${result.reasonUid}")
        result as CombatResolution.Resolved
        val changes = if (request.ability.resourceCost > 0) listOf(ResourceChange(actor.actor,
            request.ability.resourceUid ?: return backgroundBlocked("P64:COMBAT_RESOURCE_CONTRACT_REQUIRED"), ExactLongDelta.of(-request.ability.resourceCost))) else emptyList()
        val claims = if (request.ability.resourceCost > 0) listOf(WorldResourceClaim(
            phase64MechanicalResource(actor.actor, request.ability.resourceUid!!), request.ability.resourceCost)) else emptyList()
        val effects = result.effects.map { effect -> VerifiedMechanicsCommandEffect(effect.effectUid, parameters.getValue("p64_process_uid"),
            "UNIVERSAL_COMBAT", effect.kind.name, effect.target, effect.magnitude, effect.payload,
            result.evidence.proofUid, result.evidence.inputFingerprint, result.evidence.outputFingerprint) }
        return WorldConsequencePlan(changes = changes, effects = effects, claims = claims,
            sourceUids = listOf(rule.uid, auth.decisionUid, parameters.getValue("p64_logical_event_uid"), result.evidence.proofUid))
    }

    internal fun canonicalBody(body: MechanicalActorView) = body.copy(attributes = body.attributes.toSortedMap(),
        resources = body.resources.sortedBy { it.resourceUid }, executableAbilityUids = body.executableAbilityUids.toSortedSet(),
        traitUids = body.traitUids.toSortedSet(), resistanceBasisPoints = body.resistanceBasisPoints.toSortedMap(),
        equipmentRefs = body.equipmentRefs.sortedWith(compareBy<DomainRef> { it.kindUid }.thenBy { it.uid }),
        conditions = body.conditions.sortedBy { it.conditionUid },
        aggregatePopulation = body.aggregatePopulation?.let { it.copy(conditionCounts = it.conditionCounts.toSortedMap()) })

    private fun reference(parameters: Map<String, String>, prefix: String): DomainRef? {
        val kind = parameters["${prefix}_kind_uid"]?.takeIf(String::isNotBlank) ?: return null
        val uid = parameters["${prefix}_uid"]?.takeIf(String::isNotBlank) ?: return null
        return DomainRef(kind, uid)
    }
}

/** A background combat process acknowledges Phase62's already completed durable plan.
 * The original combat proofs and body changes are referenced, never resolved or spent again.
 * A past terminal brain row alone cannot manufacture an outcome for the current turn. */
internal object Phase64CombatCompletionPreparation {
    fun prepare(actor: DomainRef, rule: BackgroundProcessDefinition, parameters: Map<String, String>,
        scope: BackgroundProcessEvaluationScope, canonicalBrain: NpcBrainState, activePlayerUid: String,
        input: TemporalOwnerInput, staged: List<PlayerDomainChangePayload>): WorldConsequencePlan = try {
        prepareCaptured(actor, rule, parameters, scope, canonicalBrain, activePlayerUid, input, staged)
    } catch (_: IllegalArgumentException) {
        backgroundBlocked("P64:NPC_COMBAT_COMPLETION_BINDING")
    } catch (_: ArithmeticException) {
        backgroundBlocked("P64:NPC_COMBAT_COMPLETION_BINDING")
    }

    private fun prepareCaptured(actor: DomainRef, rule: BackgroundProcessDefinition, parameters: Map<String, String>,
        scope: BackgroundProcessEvaluationScope, canonicalBrain: NpcBrainState, activePlayerUid: String,
        input: TemporalOwnerInput, staged: List<PlayerDomainChangePayload>): WorldConsequencePlan {
        if (rule.domain != "CONFLICT" || rule.operation != "COMBAT") return backgroundBlocked("P64:COMBAT_RULE_REQUIRED")
        if (actor.kindUid == "PLAYER" || actor.uid == activePlayerUid) return backgroundBlocked("P64:ACTIVE_PLAYER_CONTROL_FORBIDDEN")
        if (input.scope != scope.temporal || canonicalBrain.campaignUid != scope.temporal.campaignUid || canonicalBrain.actor != actor ||
            input.through < input.from || parameters["p64_event_at_ms"]?.toLongOrNull()?.let { it <= input.through.milliseconds } != true)
            return backgroundBlocked("P64:NPC_COMBAT_COMPLETION_SCOPE")
        if (input.stagedChanges != staged) return backgroundBlocked("P64:NPC_COMBAT_COMPLETION_PREFIX")
        val planUid = parameters["decision_uid"]?.takeIf(String::isNotBlank) ?: return backgroundBlocked("P64:NPC_COMBAT_PLAN_REQUIRED")
        val eventUid = parameters["p64_logical_event_uid"]?.takeIf(String::isNotBlank) ?: return backgroundBlocked("P64:LOGICAL_EVENT_REQUIRED")
        val abilityUid = parameters["ability_uid"]?.takeIf(String::isNotBlank) ?: return backgroundBlocked("P64:COMBAT_ABILITY_REQUIRED")
        val targetKind = parameters["target_kind_uid"]?.takeIf(String::isNotBlank) ?: return backgroundBlocked("P64:COMBAT_TARGET_REQUIRED")
        val targetUid = parameters["target_uid"]?.takeIf(String::isNotBlank) ?: return backgroundBlocked("P64:COMBAT_TARGET_REQUIRED")
        val target = DomainRef(targetKind, targetUid)
        if (target == actor) return backgroundBlocked("P64:COMBAT_TARGET_MUST_DIFFER")
        val brains = staged.filterIsInstance<NpcBrainChange>()
        if (!validNpcBrainChains(brains) || brains.any { it.campaignUid != scope.temporal.campaignUid ||
                it.historyGenerationUid != scope.temporal.historyGenerationUid })
            return backgroundBlocked("P64:NPC_COMBAT_STAGED_BRAIN_CHANGED")
        var brain = canonicalBrain
        var completedPlan: NpcPlan? = null
        var completion: NpcBrainChange? = null
        for (change in brains.filter { it.actor == actor }) {
            val before = brain.plans.singleOrNull { it.uid == planUid }
            brain = applyNpcBrainOverlay(brain, scope.temporal, listOf(change))
            val after = brain.plans.singleOrNull { it.uid == planUid }
            if (before?.lifecycle == NpcPlanLifecycle.RUNNING && after?.lifecycle == NpcPlanLifecycle.COMPLETED) {
                if (completion != null || before.startedAt == null || before.nextEvaluationAt == null ||
                    before.actionUid != after.actionUid || before.startedAt != after.startedAt || before.goalUid != after.goalUid ||
                    after.nextEvaluationAt != null) return backgroundBlocked("P64:NPC_COMBAT_COMPLETION_BINDING")
                completedPlan = before
                completion = change
            }
        }
        val prior = completedPlan ?: return backgroundBlocked("P64:NPC_COMBAT_COMPLETION_REQUIRED")
        val finished = completion ?: return backgroundBlocked("P64:NPC_COMBAT_COMPLETION_REQUIRED")
        if (brain.plans.singleOrNull { it.uid == planUid }?.lifecycle != NpcPlanLifecycle.COMPLETED)
            return backgroundBlocked("P64:NPC_COMBAT_COMPLETION_BINDING")
        val pendingState = input.peerStates[NpcActionProcess.OWNER] ?: return backgroundBlocked("P64:NPC_COMBAT_PENDING_STATE_REQUIRED")
        if (NpcActionProcess.decode(pendingState).any { it.planUid == planUid })
            return backgroundBlocked("P64:NPC_COMBAT_PLAN_NOT_CONSUMED")
        val started = requireNotNull(prior.startedAt)
        val due = requireNotNull(prior.nextEvaluationAt)
        if (due <= started || due > input.through || parameters["p64_event_at_ms"]!!.toLong() < due.milliseconds)
            return backgroundBlocked("P64:NPC_COMBAT_COMPLETION_TIME")
        val effects = input.stagedEffects.filter { it.canonicalPayload["npc_plan_uid"] == planUid }
            .sortedBy { it.effectUid }
        if (effects.isEmpty() || effects.size > 256 || effects.map { it.effectUid }.distinct().size != effects.size ||
            effects.none { it.target == target }) return backgroundBlocked("P64:NPC_COMBAT_EFFECTS_REQUIRED")
        val contracts = effects.map { it.canonicalPayload["npc_ability_contract"] }.distinct()
        val contract = contracts.singleOrNull()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            ?: return backgroundBlocked("P64:NPC_COMBAT_CONTRACT_BINDING")
        val optionUid = "P62:OPTION:${phase60Hash("$actor|${prior.goalUid}|$abilityUid|$target|$contract").take(32)}"
        if (optionUid != prior.actionUid) return backgroundBlocked("P64:NPC_COMBAT_OPTION_BINDING")
        if (effects.any { effect ->
                val p = effect.canonicalPayload
                effect.mechanicsOwnerUid != "UNIVERSAL_COMBAT" || !effect.proofUid.startsWith("P60:PROCESS:") ||
                    (effect.target != actor && p["combat_proof_uid"].isNullOrBlank()) ||
                    (effect.target == actor && (effect.effectKindUid != "RESOURCE_DELTA" || effect.magnitude >= 0 || p["resource_uid"].isNullOrBlank())) ||
                    p["source_actor_kind_uid"] != actor.kindUid || p["source_actor_uid"] != actor.uid ||
                    p["npc_option_uid"] != prior.actionUid || p["npc_started_at_ms"] != started.milliseconds.toString() ||
                    p["npc_due_at_ms"] != due.milliseconds.toString() || p["npc_ability_uid"] != abilityUid ||
                    p["npc_target_kind_uid"] != target.kindUid || p["npc_target_uid"] != target.uid || p["npc_ability_contract"] != contract
            }) return backgroundBlocked("P64:NPC_COMBAT_EFFECT_BINDING")
        val materialized = effects.flatMap { effect ->
            val result = MechanicalEffectMaterializer.materialize(effect) as? MechanicalEffectMaterializationResult.Materialized
                ?: return backgroundBlocked("P64:NPC_COMBAT_EFFECT_MATERIALIZATION")
            result.changes.map { Phase64BackgroundCodec.fingerprint(it.payload) }
        }
        if (materialized.isEmpty()) return backgroundBlocked("P64:NPC_COMBAT_EFFECTS_REQUIRED")
        val available = staged.map(Phase64BackgroundCodec::fingerprint).groupingBy { it }.eachCount().toMutableMap()
        for (hash in materialized) {
            val count = available[hash] ?: return backgroundBlocked("P64:NPC_COMBAT_EFFECT_PREFIX_REQUIRED")
            if (count == 1) available.remove(hash) else available[hash] = count - 1
        }
        return WorldConsequencePlan(sourceUids = (listOf(rule.uid, eventUid, planUid, prior.actionUid, finished.ruleUid) +
            effects.map { it.proofUid } + effects.mapNotNull { it.canonicalPayload["combat_proof_uid"] }).distinct(),
            progressUnits = 1, existingConsequenceFingerprints = listOf(Phase64BackgroundCodec.fingerprint(finished)) + materialized)
    }
}

/** Core-only capture and speculative projection. No reader materializes bodies, registers
 * manifests, updates commitments, manufactures contacts or creates NPC decisions. */
internal object Phase64PopulationProductionReads {
    fun dispatch(
        db: SQLiteDatabase,
        campaign: String,
        operation: String,
        actor: DomainRef,
        parameters: Map<String, String>,
        scope: BackgroundProcessEvaluationScope,
        staged: List<PlayerDomainChangePayload>,
        route: WorldTravelPlan? = null,
        knownRouteFingerprint: String? = null,
        input: TemporalOwnerInput? = null
    ): WorldConsequencePlan {
        if (scope.temporal.campaignUid != campaign || HistoryGenerationStore(db, campaign).current().value != scope.temporal.historyGenerationUid)
            return backgroundBlocked("P64:STALE_HISTORY")
        val store = Phase64BackgroundStore(db, campaign)
        if (store.policy() != scope.ruleFingerprint) return backgroundBlocked("P64:RULE_SOURCE_CHANGED")
        val ruleUid = parameters["p64_rule_uid"] ?: return backgroundBlocked("P64:RULE_REQUIRED")
        val version = parameters["p64_rule_version"]?.toIntOrNull() ?: return backgroundBlocked("P64:RULE_REQUIRED")
        val definition = store.definition(ruleUid, version) ?: return backgroundBlocked("P64:RULE_REQUIRED")
        if (operation != "${definition.domain}_${definition.operation}") return backgroundBlocked("P64:OWNER_RULE_MISMATCH")
        if (operation == Phase64PopulationConflictsAdapter.CONFLICT_COMBAT) {
            val evaluation = input ?: return backgroundBlocked("P64:NPC_COMBAT_COMPLETION_INPUT_REQUIRED")
            val active = ActivePlayerStore(db, campaign).active() ?: return backgroundBlocked("P64:ACTIVE_PLAYER_OWNER_REQUIRED")
            val brain = NpcBrainStore(db, campaign).read(actor) ?: return backgroundBlocked("P64:NPC_COMBAT_BRAIN_REQUIRED")
            return Phase64CombatCompletionPreparation.prepare(actor, definition, parameters, scope, brain, active.playerUid, evaluation, staged)
        }
        val subject = Phase64PopulationRuleCatalog.subject(definition, parameters, actor)
            ?: return backgroundBlocked("P64:POPULATION_SUBJECT_REQUIRED")
        val body = captureBody(db, campaign, subject, staged) ?: return backgroundBlocked("P64:MECHANICAL_BODY_REQUIRED")
        val event = parameters["p64_logical_event_uid"] ?: return backgroundBlocked("P64:LOGICAL_EVENT_REQUIRED")
        val population = WorldPopulationStore(db, campaign)
        val resolved = parameters.toMutableMap()
        if (Phase64PopulationRuleCatalog.matches(definition)) {
            val snapshot = activitySnapshot(db, campaign, actor, definition, parameters, scope, staged, route)
                ?: return backgroundBlocked("P64:POPULATION_SUBJECT_CAPTURE_REQUIRED")
            Phase64PopulationRuleCatalog.unavailable(definition, parameters, actor, snapshot)?.let { return backgroundBlocked(it) }
            if (parameters["source_manifest_uid"] == Phase64PopulationRuleCatalog.CAPTURED_MANIFEST)
                snapshot.manifest?.let { resolved["source_manifest_uid"] = it.uid }
            when (parameters["count"]) {
                Phase64PopulationRuleCatalog.WHOLE_ACTIVE -> resolved["count"] = (body.aggregatePopulation?.activeCount ?: 1L).toString()
                Phase64PopulationRuleCatalog.WHOLE_LIVING -> resolved["count"] = (body.aggregatePopulation?.let {
                    Math.addExact(Math.addExact(it.activeCount, it.woundedCount), snapshot.namedCount)
                } ?: 1L).toString()
            }
        }
        return when (operation) {
            Phase64PopulationConflictsAdapter.POPULATION_AGE -> {
                if (body.aggregatePopulation != null && population.forAggregate(subject)?.uid != resolved["source_manifest_uid"])
                    return backgroundBlocked("P64:POPULATION_LINEAGE_REQUIRED")
                val current = db.rawQuery("SELECT current_value FROM mechanical_actor_tracks WHERE campaign_id=? AND entity_kind_uid=? AND entity_uid=? AND track_uid=?",
                    arrayOf(campaign, subject.kindUid, subject.uid, Phase64PopulationOwnerPreparation.AGE_TRACK)).use { if (it.moveToFirst()) it.getLong(0) else 0L }
                Phase64PopulationOwnerPreparation.age(body, definition, current, event, staged)
            }
            Phase64PopulationConflictsAdapter.POPULATION_DEATH -> {
                if (body.aggregatePopulation != null && population.forAggregate(subject)?.uid != resolved["source_manifest_uid"])
                    return backgroundBlocked("P64:POPULATION_LINEAGE_REQUIRED")
                if (parameters["cause_rule_uid"] != definition.parameters["cause_rule_uid"])
                    return backgroundBlocked("P64:DEATH_CAUSE_RULE_REQUIRED")
                val count = resolved["count"]?.toLongOrNull() ?: return backgroundBlocked("P64:DEATH_COUNT_REQUIRED")
                Phase64PopulationOwnerPreparation.death(body, definition, count, event)
            }
            Phase64PopulationConflictsAdapter.POPULATION_BIRTH -> {
                val manifest = resolved["source_manifest_uid"]?.let(population::manifest) ?: return backgroundBlocked("P64:COHORT_SOURCE_MANIFEST_REQUIRED")
                val skeleton = Phase63WorldStore(db, campaign).root()?.skeleton ?: return backgroundBlocked("P64:COHORT_WORLD_REQUIRED")
                Phase64DemographyPreparation.birth(body, manifest, skeleton, definition, resolved, scope)
            }
            Phase64PopulationConflictsAdapter.CONFLICT_MOBILIZE -> {
                if (parameters["formation_kind_uid"] != subject.kindUid || parameters["formation_uid"] != subject.uid)
                    return backgroundBlocked("P64:PARTIAL_MOBILIZATION_OWNER_REQUIRED")
                val manifest = resolved["source_manifest_uid"]?.let(population::manifest) ?: return backgroundBlocked("P64:FORMATION_MANIFEST_REQUIRED")
                Phase64DemographyPreparation.mobilize(body, manifest, definition, resolved, scope,
                    Phase64PopulationOwnerStore(db, campaign).mobilized(subject), staged)
            }
            Phase64PopulationConflictsAdapter.POPULATION_MIGRATE, Phase64PopulationConflictsAdapter.CONFLICT_MOVE -> {
                val plan = route ?: return backgroundBlocked("P64:KNOWN_ROUTE_REQUIRED")
                val proof = knownRouteFingerprint ?: return backgroundBlocked("P64:KNOWN_ROUTE_REQUIRED")
                val started = parameters["p64_started_at_ms"]?.toLongOrNull() ?: return backgroundBlocked("P64:PROCESS_START_REQUIRED")
                val at = parameters["p64_event_at_ms"]?.toLongOrNull() ?: return backgroundBlocked("P64:PROCESS_TIME_REQUIRED")
                val manifest = if (subject.kindUid in setOf("GROUP", "UNIT")) population.forAggregate(subject)
                    ?: return backgroundBlocked("P64:POPULATION_LINEAGE_REQUIRED") else null
                val memberRefs = if (manifest != null) population.namedMembers(subject) else emptyList()
                if (manifest != null && (body.aggregatePopulation == null ||
                        Math.subtractExact(manifest.originalCount, memberRefs.size.toLong()) != body.aggregatePopulation.totalCount))
                    return backgroundBlocked("P64:POPULATION_LINEAGE_CHANGED")
                val members = memberRefs.map { ref ->
                    captureBody(db, campaign, ref, staged) ?: return backgroundBlocked("P64:NAMED_MEMBER_BODY_REQUIRED")
                }
                val count = if (operation == Phase64PopulationConflictsAdapter.POPULATION_MIGRATE)
                    resolved["count"]?.toLongOrNull() ?: return backgroundBlocked("P64:MIGRATION_COUNT_REQUIRED") else null
                val result = Phase64PopulationOwnerPreparation.movement(body, definition, plan, proof, WorldTimeTick(started), WorldTimeTick(at), event,
                    count, members)
                result.copy(sourceUids = (result.sourceUids + listOfNotNull(manifest?.uid)).distinct())
            }
            Phase64PopulationConflictsAdapter.EPIDEMIC_EXPOSURE -> {
                if (parameters["pathogen_rule_uid"] != definition.parameters["pathogen_rule_uid"])
                    return backgroundBlocked("P64:PATHOGEN_MECHANICAL_RULE_REQUIRED")
                val target = reference(parameters, "target") ?: return backgroundBlocked("P64:EXPOSURE_TARGET_REQUIRED")
                val exposed = captureBody(db, campaign, target, staged) ?: return backgroundBlocked("P64:EXPOSURE_TARGET_UNAVAILABLE")
                val condition = definition.parameters["pathogen_condition_uid"] ?: return backgroundBlocked("P64:PATHOGEN_MECHANICAL_RULE_REQUIRED")
                val threshold = definition.parameters["minimum_exposure_units"]?.toLongOrNull() ?: return backgroundBlocked("P64:PATHOGEN_MECHANICAL_RULE_REQUIRED")
                val units = parameters["exposure_units"]?.toLongOrNull() ?: return backgroundBlocked("P64:EXPOSURE_UNITS_REQUIRED")
                val contact = parameters["contact_event_uid"] ?: return backgroundBlocked("P64:CONTACT_EVIDENCE_REQUIRED")
                if (definition.parameters["contact_event_uid"]?.let { it != contact } == true ||
                    !contactExists(db, campaign, contact, actor, target, scope.temporal.baseCommitOrder))
                    return backgroundBlocked("P64:CONTACT_EVIDENCE_REQUIRED")
                val affected = definition.parameters["affected_count"]?.toLongOrNull() ?: 1L
                Phase64PopulationOwnerPreparation.exposure(exposed, definition, condition, threshold, units, contact, event, affected)
            }
            Phase64PopulationConflictsAdapter.CONFLICT_SUPPLY -> supply(db, campaign, body, parameters, scope, staged, definition, event)
            else -> backgroundBlocked("P64:POPULATION_OPERATION_UNAVAILABLE")
        }
    }

    /** Bounded capture of the subject already present in the NPC's protected options. */
    fun activitySnapshot(db: SQLiteDatabase, campaign: String, principal: DomainRef, definition: BackgroundProcessDefinition,
        parameters: Map<String, String>, scope: BackgroundProcessEvaluationScope, staged: List<PlayerDomainChangePayload>,
        route: WorldTravelPlan? = null): Phase64PopulationActivitySnapshot? = runCatching {
        require(scope.temporal.campaignUid == campaign)
        val subject = Phase64PopulationRuleCatalog.subject(definition, parameters, principal) ?: return null
        val body = captureBody(db, campaign, subject, staged) ?: return null
        val populations = WorldPopulationStore(db, campaign)
        val manifest = if (body.aggregatePopulation != null) populations.forAggregate(subject) else null
        val members = if (body.aggregatePopulation != null) populations.namedMembers(subject) else emptyList()
        val namedBodies = if (definition.operation in setOf("MIGRATE", "MOVE")) members.map {
            captureBody(db, campaign, it, staged) ?: return null
        } else emptyList()
        val mobilized = Phase64PopulationOwnerStore(db, campaign).mobilized(subject) ||
            staged.filterIsInstance<FormationMobilizationChange>().any { it.formation == subject }
        val contact = if (definition.operation == "EXPOSURE") parameters["contact_event_uid"]?.let {
            contactExists(db, campaign, it, principal, subject, scope.temporal.baseCommitOrder)
        } == true else false
        val age = if (definition.operation == "AGE") db.rawQuery("SELECT current_value FROM mechanical_actor_tracks WHERE campaign_id=? AND entity_kind_uid=? AND entity_uid=? AND track_uid=?",
            arrayOf(campaign, subject.kindUid, subject.uid, Phase64PopulationOwnerPreparation.AGE_TRACK)).use { if (it.moveToFirst()) it.getLong(0) else 0L } else 0L
        Phase64PopulationActivitySnapshot(body, manifest, members.size.toLong(), mobilized, route, contact, namedBodies, age)
    }.getOrNull()

    /** Production seam. Authorization comes from the existing live Phase62 selection, and
     * targets are the bounded, principal-authorized Phase50/63 participant projection. */
    fun prepareCombatRequest(
        db: SQLiteDatabase,
        campaign: String,
        actor: DomainRef,
        parameters: Map<String, String>,
        scope: BackgroundProcessEvaluationScope,
        staged: List<PlayerDomainChangePayload>,
        authorization: NpcActionAuthorization?,
        contracts: CombatAbilityContractPort,
        targetRefs: List<DomainRef>,
        spatialRead: (List<MechanicalActorView>) -> Phase64CombatCapture?
    ): Phase64CombatRequestPreparation {
        fun unavailable(reason: String) = Phase64CombatRequestPreparation.Unavailable(reason)
        if (scope.temporal.campaignUid != campaign || HistoryGenerationStore(db, campaign).current().value != scope.temporal.historyGenerationUid)
            return unavailable("P64:STALE_HISTORY")
        val store = Phase64BackgroundStore(db, campaign)
        if (store.policy() != scope.ruleFingerprint) return unavailable("P64:RULE_SOURCE_CHANGED")
        val ruleUid = parameters["p64_rule_uid"] ?: return unavailable("P64:RULE_REQUIRED")
        val version = parameters["p64_rule_version"]?.toIntOrNull() ?: return unavailable("P64:RULE_REQUIRED")
        val rule = store.definition(ruleUid, version) ?: return unavailable("P64:RULE_REQUIRED")
        liveCombatDecisionReason(db, campaign, actor, parameters, scope, staged, authorization)?.let { return unavailable(it) }
        if (targetRefs.size !in 1..256 || actor in targetRefs || targetRefs.distinct().size != targetRefs.size)
            return unavailable("P64:COMBAT_PARTICIPANT_CAPTURE_INVALID")
        val refs = (listOf(actor) + targetRefs).toSet()
        // Persistence coordinates predate these changes; do not revive a departed body's
        // old position. An exact staged-position owner can extend this seam later.
        if (staged.filterIsInstance<SpatialChange>().any { it.subject in refs })
            return unavailable("P64:COMBAT_STAGED_POSITION_REQUIRED")
        val attacker = captureBody(db, campaign, actor, staged) ?: return unavailable("P64:MECHANICAL_BODY_REQUIRED")
        val targets = targetRefs.map { captureBody(db, campaign, it, staged) ?: return unavailable("P64:COMBAT_TARGET_BODY_REQUIRED") }
        val query = CombatAbilityContractQuery(campaign, parameters["ability_uid"].orEmpty(), parameters["ability_uid"].orEmpty(),
            targets.size, targets.any { it.aggregatePopulation != null })
        val ability = contracts.npcContractFor(query) ?: return unavailable("P64:REGISTERED_COMBAT_CONTRACT_REQUIRED")
        if (ability.areaRadiusMillimetres != null) {
            val population = WorldPopulationStore(db, campaign)
            if (targets.filter { it.aggregatePopulation != null }.any { !refs.containsAll(population.namedMembers(it.actor)) })
                return unavailable("P64:COMBAT_NAMED_MEMBER_CAPTURE_REQUIRED")
        }
        val capture = spatialRead(listOf(attacker) + targets) ?: return unavailable("P64:COMBAT_SPATIAL_CAPTURE_REQUIRED")
        return Phase64CombatOwnerPreparation.prepare(attacker, targets, rule, parameters, scope, authorization, contracts, capture)
    }

    private fun liveCombatDecisionReason(db: SQLiteDatabase, campaign: String, actor: DomainRef, parameters: Map<String, String>,
        scope: BackgroundProcessEvaluationScope, staged: List<PlayerDomainChangePayload>, authorization: NpcActionAuthorization?): String? {
        val auth = authorization ?: return "P64:NPC_COMBAT_DECISION_REQUIRED"
        if (auth.scope.temporal != scope.temporal || auth.scope.actor != actor || auth.decisionUid != parameters["decision_uid"])
            return "P64:NPC_COMBAT_DECISION_CHANGED"
        val active = ActivePlayerStore(db, campaign).active() ?: return "P64:ACTIVE_PLAYER_OWNER_REQUIRED"
        if (active.playerUid != auth.scope.activePlayerUid || actor.uid == active.playerUid)
            return "P64:ACTIVE_PLAYER_CONTROL_FORBIDDEN"
        if (parameters["p64_event_at_ms"]?.toLongOrNull() != auth.scope.atTime.milliseconds)
            return "P64:NPC_COMBAT_DECISION_TIME_CHANGED"
        val canonical = NpcBrainStore(db, campaign).read(actor) ?: return "P64:NPC_COMBAT_BRAIN_REQUIRED"
        val brain = runCatching { applyNpcBrainOverlay(canonical, scope.temporal, staged.filterIsInstance<NpcBrainChange>()) }.getOrNull()
            ?: return "P64:NPC_COMBAT_STAGED_BRAIN_CHANGED"
        if (brain.revision != auth.scope.brainRevision) return "P64:NPC_COMBAT_STAGED_BRAIN_CHANGED"
        return null
    }

    /** Versions match the existing appliers' body bumps. Conditions use the legacy condition
     * owner and therefore do not increment the mechanical body's version. Unsupported
     * projections are unavailable; persistence must never stand in for the changed body. */
    fun captureBody(db: SQLiteDatabase, campaign: String, ref: DomainRef, staged: List<PlayerDomainChangePayload>): MechanicalActorView? {
        val body = MechanicalActorStateStore(db, campaign).actor(ref) ?: return null
        if (body.aggregatePopulation != null) {
            val population = WorldPopulationStore(db, campaign)
            if (staged.filterIsInstance<WorldSimulationChange>().filter { it.campaignUid == campaign }.any { change ->
                    change.populationExtractions.any { extraction ->
                        val manifest = population.manifest(extraction.manifestUid)
                            ?: change.populationManifests.singleOrNull { it.uid == extraction.manifestUid }
                        manifest == null || manifest.aggregate == ref
                    }
                }) return null
        }
        val playerCosts=staged.filterIsInstance<ResourceChange>().filter { it.subject==ref && ref.kindUid=="PLAYER" }
        if(playerCosts.isEmpty())return projectBody(body, staged)
        // Player pools are owned by Phase4, not the NPC mechanical resource mirror.
        // Capture the exact affected registered pools before applying their staged deltas.
        return runCatching {
            val owner=StatResourceStore(db,campaign)
            val values=owner.playerResources(ref.uid).associateBy { it.resourceUid }
            val definitions=owner.resourceDefinitions().associateBy { it.resourceUid }
            val resources=body.resources.associateBy { it.resourceUid }.toMutableMap()
            playerCosts.map { it.resourceUid }.distinct().forEach { uid->
                val definition=requireNotNull(definitions[uid])
                val value=requireNotNull(values[uid])
                fun exact(n:Double)=java.math.BigDecimal.valueOf(n).longValueExact()
                val maximum=definition.maxValue?.let(::exact)?:resources[uid]?.maximum?:error("P64:PLAYER_POOL_LIMIT_REQUIRED")
                val current=exact(value.currentValue)
                val minimum=definition.minValue?.let(::exact)?:0L
                require(current in minimum..maximum)
                require(playerCosts.filter { it.resourceUid==uid }.fold(current) { amount,change->Math.addExact(amount,change.delta.units) } in minimum..maximum)
                resources[uid]=MechanicalResource(uid,current,maximum)
            }
            projectBodyChanges(body.copy(resources=resources.values.sortedBy { it.resourceUid }),staged,playerPoolsCaptured=true)
        }.getOrNull()
    }

    /** Pure seam for the same projection used by production capture. Component integrity
     * and combat tracks need their owner's raw values, which MechanicalActorView omits.
     * PLAYER resources belong to the separate player owner and cannot use this projection. */
    internal fun projectBody(base: MechanicalActorView, staged: List<PlayerDomainChangePayload>): MechanicalActorView? {
        return try {
            projectBodyChanges(base, staged)
        } catch (_: ArithmeticException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun projectBodyChanges(base: MechanicalActorView, staged: List<PlayerDomainChangePayload>,playerPoolsCaptured:Boolean=false): MechanicalActorView? {
        var body = base
        val ref = body.actor
        for (change in staged) {
            when (change) {
                is ResourceChange -> if (change.subject == ref) {
                    if (ref.kindUid.uppercase() == "PLAYER" && !playerPoolsCaptured) return null
                    val resource = body.resources.singleOrNull { it.resourceUid == change.resourceUid } ?: return null
                    val value = Math.addExact(resource.current, change.delta.units)
                    if (value !in 0..resource.maximum) return null
                    body = body.copy(resources = body.resources.map { if (it.resourceUid == resource.resourceUid) it.copy(current = value) else it },
                        stateVersion = if(playerPoolsCaptured)body.stateVersion else Math.addExact(body.stateVersion, 1))
                }
                is SpatialChange -> if (change.subject == ref) body = body.copy(locationRef = change.destinationLocation ?: body.locationRef, stateVersion = Math.addExact(body.stateVersion, 1))
                is MechanicalTrackChange -> if (change.subject == ref) {
                    if (change.trackUid in setOf("WOUND", "MORALE", "COHESION", "FORMATION")) return null
                    body = body.copy(stateVersion = Math.addExact(body.stateVersion, 1))
                }
                is EquipmentIntegrityChange -> if (change.subject == ref) return null
                is StructureIntegrityChange -> if (change.subject == ref) return null
                is EquipmentChange -> if (change.subject == ref) return null
                is RuntimeChange -> if (change.subject == ref) return null
                is WorldSimulationChange -> if (change.campaignUid == body.campaignUid && change.actorExpansions.any { it.actor == ref }) return null
                is WoundChange -> if (change.subject == ref) {
                    val wounds = body.conditions.filter { it.conditionUid == "WOUND" }
                    if (wounds.size > 1 || change.severityDelta.units == 0L) return null
                    val prior = wounds.singleOrNull()?.intensity ?: 0L
                    val amount = Math.addExact(prior, change.severityDelta.units)
                    if (amount < 0) return null
                    val conditions = body.conditions.filterNot { it.conditionUid == "WOUND" } + if (amount > 0) listOf(MechanicalCondition("WOUND", amount)) else emptyList()
                    val baseline = body.unwoundedDefence ?: if (prior == 0L) body.attributes["DEFENCE"] else return null
                    val attributes = body.attributes.toMutableMap()
                    if (baseline != null) attributes["DEFENCE"] = Math.subtractExact(baseline, amount).coerceAtLeast(0)
                    body = body.copy(conditions = conditions, attributes = attributes, unwoundedDefence = baseline,
                        stateVersion = Math.addExact(body.stateVersion, 1))
                }
                is ConditionChange -> if (change.subject == ref) {
                    if (change.conditionUid == "WOUND") return null
                    if (change.operation == ConditionOperation.REMOVE && body.conditions.none { it.conditionUid == change.conditionUid }) return null
                    body = body.copy(conditions = if (change.operation == ConditionOperation.ADD)
                        body.conditions + MechanicalCondition(change.conditionUid, 1)
                    else body.conditions.filterNot { it.conditionUid == change.conditionUid })
                }
                is AggregatePopulationChange -> if (change.subject == ref) {
                    val population = body.aggregatePopulation ?: return null
                    val consumed = Math.addExact(change.eliminatedDelta, change.woundedDelta)
                    if (consumed > population.activeCount) return null
                    val counts = population.conditionCounts.toMutableMap()
                    change.conditionUid?.let { uid ->
                        val value = Math.addExact(counts[uid] ?: 0L, change.conditionAffectedDelta)
                        if (value > population.totalCount) return null
                        counts[uid] = value
                    }
                    body = body.copy(aggregatePopulation = population.copy(activeCount = population.activeCount - consumed,
                        woundedCount = Math.addExact(population.woundedCount, change.woundedDelta), eliminatedCount = Math.addExact(population.eliminatedCount, change.eliminatedDelta),
                        conditionCounts = counts), stateVersion = Math.addExact(body.stateVersion, 1))
                }
                else -> Unit
            }
        }
        return body
    }

    private fun supply(db: SQLiteDatabase, campaign: String, recipient: MechanicalActorView, parameters: Map<String, String>,
        scope: BackgroundProcessEvaluationScope, staged: List<PlayerDomainChangePayload>, rule: BackgroundProcessDefinition, event: String): WorldConsequencePlan {
        val supplier = reference(parameters, "supplier") ?: return backgroundBlocked("P64:SUPPLIER_REQUIRED")
        if (supplier == recipient.actor) return backgroundBlocked("P64:SUPPLIER_MUST_DIFFER")
        val resource = reference(parameters, "resource") ?: return backgroundBlocked("P64:SUPPLY_RESOURCE_REQUIRED")
        val count = parameters["count"]?.toLongOrNull()?.takeIf { it > 0 } ?: return backgroundBlocked("P64:SUPPLY_COUNT_REQUIRED")
        if (resource.kindUid in setOf("ITEM", "ITEM_INSTANCE", "INVENTORY_ITEM")) {
            if (count != 1L) return backgroundBlocked("P64:UNIQUE_SUPPLY_ITEM_COUNT_MUST_BE_ONE")
            val held = InventoryStore(db, campaign).typedUnique(supplier.uid).singleOrNull { it.first.itemInstanceUid == resource.uid }
                ?: return backgroundBlocked("P64:SUPPLY_ITEM_CUSTODY_REQUIRED")
            val available = staged.filterIsInstance<InventoryChange>().filter { it.subject == supplier && it.itemInstanceUid == resource.uid }
                .fold(1L) { amount, change -> Math.addExact(amount, change.quantityDelta.units) }
            if (available != 1L || held.first.campaignId != scope.temporal.campaignUid) return backgroundBlocked("P64:SUPPLY_ITEM_CUSTODY_CHANGED")
            if (EquipmentStore(db, campaign).equipment(supplier.uid).any { it.equipment.itemInstanceUid == resource.uid })
                return backgroundBlocked("P64:SUPPLY_ITEM_EQUIPPED")
            return WorldConsequencePlan(changes = listOf(InventoryChange(supplier, resource.uid, ExactLongDelta.of(-1)),
                InventoryChange(recipient.actor, resource.uid, ExactLongDelta.of(1))), claims = listOf(WorldResourceClaim(DomainRef("ITEM_INSTANCE", resource.uid), 1)),
                sourceUids = listOf(rule.uid, event, resource.uid))
        }
        if (resource.kindUid != "RESOURCE") return backgroundBlocked("P64:SUPPLY_RESOURCE_OWNER_REQUIRED")
        val source = captureBody(db, campaign, supplier, staged) ?: return backgroundBlocked("P64:SUPPLIER_BODY_REQUIRED")
        val available = source.resources.singleOrNull { it.resourceUid == resource.uid } ?: return backgroundBlocked("P64:SUPPLY_SOURCE_RESOURCE_REQUIRED")
        val destination = recipient.resources.singleOrNull { it.resourceUid == resource.uid } ?: return backgroundBlocked("P64:SUPPLY_RECIPIENT_RESOURCE_REQUIRED")
        if (available.current < count || destination.maximum - destination.current < count) return backgroundBlocked("P64:SUPPLY_RESOURCE_CAPACITY_REQUIRED")
        return WorldConsequencePlan(changes = listOf(ResourceChange(supplier, resource.uid, ExactLongDelta.of(-count)),
            ResourceChange(recipient.actor, resource.uid, ExactLongDelta.of(count))), claims = listOf(WorldResourceClaim(phase64MechanicalResource(supplier, resource.uid), count)),
            sourceUids = listOf(rule.uid, event, resource.uid))
    }

    private fun reference(parameters: Map<String, String>, prefix: String): DomainRef? {
        val kind = parameters["${prefix}_kind_uid"]?.takeIf(String::isNotBlank) ?: return null
        val uid = parameters["${prefix}_uid"]?.takeIf(String::isNotBlank) ?: return null
        return DomainRef(kind, uid)
    }

    private fun contactExists(db: SQLiteDatabase, campaign: String, event: String, source: DomainRef, target: DomainRef, throughOrder: Long): Boolean {
        val table = CampaignIntelligencePhase30Schema.EVENT_TABLE
        if (!TurnTransactionReceiptSchema.isReady(db) ||
            !db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.moveToFirst() }) return false
        // source_actor_* identifies the outer player command, not the NPC who performed
        // this contact. Only the exact event actor/target pair in a committed turn is evidence.
        return db.rawQuery("""SELECT e.actor_ref_kind_uid,e.actor_ref_uid,e.target_refs_canonical FROM $table e
            JOIN turn_transaction_receipts r ON r.campaign_uid=e.campaign_uid AND r.transaction_uid=e.transaction_uid
                AND r.turn_uid=e.turn_uid AND r.command_uid=e.command_uid AND r.commit_order=e.committed_order
                AND r.commit_state='COMMITTED'
            WHERE e.campaign_uid=? AND e.event_uid=? AND e.committed_order<=?""".trimIndent(),
            arrayOf(campaign, event, throughOrder.toString())).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0) || cursor.isNull(1) ||
                cursor.getString(0) != source.kindUid || cursor.getString(1) != source.uid) return@use false
            val encoded = cursor.getString(2)
            var offset = 0
            fun field(): String? {
                val separator = encoded.indexOf(':', offset)
                if (separator <= offset) return null
                val length = encoded.substring(offset, separator).toIntOrNull()?.takeIf { it in 1..160 } ?: return null
                val end = separator + 1 + length
                if (end > encoded.length) return null
                val value = encoded.substring(separator + 1, end)
                offset = end
                return value
            }
            var count = 0
            var found = false
            while (offset < encoded.length && count++ < 256) {
                val kind = field() ?: return@use false
                val uid = field() ?: return@use false
                if (DomainRef(kind, uid) == target) found = true
                if (offset < encoded.length) {
                    if (encoded[offset] != ';') return@use false
                    offset++
                    if (offset == encoded.length) return@use false
                }
            }
            offset == encoded.length && found
        }
    }
}
