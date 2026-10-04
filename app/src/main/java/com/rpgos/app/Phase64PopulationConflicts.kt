package com.rpgos.app

/** Pure scheduling adapter. Population counts, lineage, mechanical bodies and equipment
 * remain with their existing owners; no count is represented as a generic resource delta.
 * Specialized preparation must preserve named slots and use the logical event key for
 * casualty/partition idempotency. A missing owner or rule is an explicit blocked result. */
class Phase64PopulationConflictsAdapter : BackgroundDomainAdapter {
    override val domains = setOf("POPULATION", "CONFLICT", "EPIDEMIC")

    override fun evaluate(
        definition: BackgroundProcessDefinition,
        process: BackgroundProcessInstance,
        scope: BackgroundProcessEvaluationScope,
        at: WorldTimeTick,
        reads: BackgroundWorldReadPort,
        staged: List<PlayerDomainChangePayload>
    ): WorldConsequencePlan {
        if (definition.domain !in domains) return backgroundBlocked("P64:POPULATION_DOMAIN_REQUIRED")
        if (process.definitionUid != definition.uid || process.definitionVersion != definition.version)
            return backgroundBlocked("P64:PROCESS_RULE_MISMATCH")
        if (process.status !in setOf(BackgroundProcessStatus.ACTIVE, BackgroundProcessStatus.BLOCKED))
            return backgroundBlocked("P64:PROCESS_NOT_ACTIVE")
        if (at < process.due) return backgroundBlocked("P64:PROCESS_NOT_DUE")
        if (process.actor.kindUid !in BODY_KINDS) return backgroundBlocked("P64:MECHANICAL_BODY_REQUIRED")

        val values = backgroundParameters(definition, process)
        val request = try {
            request(definition, process, scope, values)
        } catch (invalid: InvalidParameter) {
            return backgroundBlocked(invalid.message!!)
        }
        if (!reads.authorize(process.actor, request.operation, request.refs, staged))
            return backgroundBlocked("P64:PROCESS_NOT_AUTHORIZED")
        if (!(listOf(process.actor) + request.refs).distinct().all(reads::exists))
            return backgroundBlocked("P64:PROCESS_REFERENCE_UNAVAILABLE")

        val parameters = request.parameters.toMutableMap()
        request.destination?.let { destination ->
            val routeActor = Phase64PopulationRuleCatalog.subject(definition, parameters, process.actor)
                ?: return backgroundBlocked("P64:POPULATION_SUBJECT_REQUIRED")
            val route = (if (Phase64PopulationRuleCatalog.matches(definition)) reads.authorizedRoute(process.actor, routeActor, destination, at)
                else reads.route(routeActor, destination, at))
                ?.takeIf(String::isNotBlank) ?: return backgroundBlocked("P64:KNOWN_ROUTE_REQUIRED")
            // This value comes from the subject-scoped topology owner, never from parameters.
            parameters["route_uid"] = route
        }
        request.claim?.let { claim ->
            val available = reads.available(claim.resource, staged)
                ?: return backgroundBlocked("P64:SUPPLY_AVAILABILITY_REQUIRED")
            if (available < claim.quantity) return backgroundBlocked("P64:SUPPLY_RESOURCE_REQUIRED")
        }

        val owned = reads.prepareOwnedEffect(request.operation, process.actor, parameters, scope, staged)
        if (owned.status !in setOf(BackgroundProcessStatus.ACTIVE, BackgroundProcessStatus.COMPLETED))
            return owned
        if (owned.changes.isEmpty() && owned.effects.isEmpty() &&
            !(request.operation == CONFLICT_COMBAT && owned.status == BackgroundProcessStatus.COMPLETED && owned.existingConsequenceFingerprints.isNotEmpty()))
            return backgroundBlocked("P64:OWNED_RESULT_REQUIRED:${request.operation}")
        // The owner can add mechanical capacity, work and supply claims. An identical claim
        // is the same reservation, not a second consumption of the same unit.
        return owned.copy(
            claims = (owned.claims + listOfNotNull(request.claim)).distinct(),
            sourceUids = (owned.sourceUids + definition.uid + process.uid +
                parameters.getValue("p64_logical_event_uid") + request.sourceUids).distinct()
        )
    }

    private data class Request(
        val operation: String,
        val parameters: Map<String, String>,
        val refs: List<DomainRef> = emptyList(),
        val destination: DomainRef? = null,
        val claim: WorldResourceClaim? = null,
        val sourceUids: List<String> = emptyList()
    )

    private fun request(
        definition: BackgroundProcessDefinition,
        process: BackgroundProcessInstance,
        scope: BackgroundProcessEvaluationScope,
        values: Map<String, String>
    ): Request {
        val operation = "${definition.domain}_${definition.operation}"
        val eventUid = "P64:EVENT:${phase63Hash(listOf(scope.temporal.campaignUid, scope.worldSeed,
            definition.uid, definition.version.toString(), process.uid, process.progressUnits.toString(),
            process.startedAt.milliseconds.toString()).joinToString("") { "${it.length}:$it" })}"
        val parameters = mutableMapOf(
            "p64_process_uid" to process.uid,
            "p64_process_version" to process.version.toString(),
            "p64_rule_uid" to definition.uid,
            "p64_rule_version" to definition.version.toString(),
            "p64_rule_fingerprint" to scope.ruleFingerprint,
            "p64_logical_event_uid" to eventUid,
            "p64_event_at_ms" to process.due.milliseconds.toString(),
            "p64_started_at_ms" to process.startedAt.milliseconds.toString(),
            "p64_duration_ms" to definition.durationMillis.toString()
        )
        fun field(key: String): String = identifier(values, key).also { parameters[key] = it }
        fun units(key: String): Long = positive(values, key).also { parameters[key] = it.toString() }
        fun ref(prefix: String, kinds: Set<String>): DomainRef {
            val kind = field("${prefix}_kind_uid")
            if (kind !in kinds) throw InvalidParameter("P64:INVALID_PARAMETER:${prefix}_kind_uid")
            return DomainRef(kind, field("${prefix}_uid"))
        }
        val catalog = Phase64PopulationRuleCatalog.matches(definition)
        val subject = if (catalog) {
            field("population_subject_policy_uid")
            field("population_kind_uid")
            field("population_uid")
            Phase64PopulationRuleCatalog.subject(definition, parameters, process.actor)
                ?: throw InvalidParameter("P64:POPULATION_SUBJECT_REQUIRED")
        } else process.actor
        fun aggregate() {
            if (subject.kindUid !in AGGREGATE_KINDS)
                throw InvalidParameter("P64:AGGREGATE_POPULATION_REQUIRED")
        }
        fun populationCount() {
            val value = values["count"]
            if (catalog && value in setOf(Phase64PopulationRuleCatalog.WHOLE_ACTIVE, Phase64PopulationRuleCatalog.WHOLE_LIVING) &&
                value == definition.parameters["count"]) {
                parameters["count"] = requireNotNull(value)
                return
            }
            val count = units("count")
            if (subject.kindUid !in AGGREGATE_KINDS && count != 1L)
                throw InvalidParameter("P64:NAMED_POPULATION_COUNT_MUST_BE_ONE")
        }
        return when (operation) {
            POPULATION_MIGRATE -> {
                populationCount()
                val destination = ref("destination", PLACE_KINDS)
                Request(operation, parameters, listOf(destination), destination)
            }
            POPULATION_AGE -> {
                val lineage = if (subject.kindUid in AGGREGATE_KINDS)
                    listOf(field("source_manifest_uid")) else emptyList()
                parameters["age_increment_ms"] = definition.durationMillis.toString()
                Request(operation, parameters, sourceUids = lineage)
            }
            POPULATION_BIRTH -> {
                aggregate()
                units("count")
                val lineage = field("source_manifest_uid")
                val rule = field("cohort_rule_uid")
                val version = units("cohort_rule_version")
                if (version > Int.MAX_VALUE) throw InvalidParameter("P64:INVALID_PARAMETER:cohort_rule_version")
                // New births get a new immutable cohort; existing manifests are never resized.
                parameters["cohort_uid"] = "P64-COHORT-${phase63Hash(eventUid).take(32).uppercase()}"
                Request(operation, parameters, sourceUids = listOf(lineage, rule))
            }
            POPULATION_DEATH -> {
                populationCount()
                val cause = field("cause_rule_uid")
                val lineage = if (subject.kindUid in AGGREGATE_KINDS)
                    listOf(field("source_manifest_uid")) else emptyList()
                Request(operation, parameters, sourceUids = lineage + cause)
            }
            CONFLICT_MOBILIZE -> {
                aggregate()
                populationCount()
                val lineage = field("source_manifest_uid")
                val formation = ref("formation", setOf("UNIT"))
                Request(operation, parameters, listOf(formation), sourceUids = listOf(lineage))
            }
            CONFLICT_SUPPLY -> {
                aggregate()
                val supplier = ref("supplier", BODY_KINDS + setOf("ORGANIZATION"))
                val resource = ref("resource", RESOURCE_KINDS)
                val count = units("count")
                if (resource.kindUid != "RESOURCE" && count != 1L)
                    throw InvalidParameter("P64:UNIQUE_SUPPLY_ITEM_COUNT_MUST_BE_ONE")
                val claim = if (resource.kindUid == "RESOURCE") phase64MechanicalResource(supplier, resource.uid)
                    else DomainRef("ITEM_INSTANCE", resource.uid)
                Request(operation, parameters, listOf(supplier, resource), claim = WorldResourceClaim(claim, count))
            }
            CONFLICT_MOVE -> {
                aggregate()
                val destination = ref("destination", PLACE_KINDS)
                Request(operation, parameters, listOf(destination), destination)
            }
            CONFLICT_COMBAT -> {
                val target = ref("target", BODY_KINDS)
                if (target == process.actor) throw InvalidParameter("P64:COMBAT_TARGET_MUST_DIFFER")
                val ability = field("ability_uid")
                val decision = field("decision_uid")
                Request(operation, parameters, listOf(target), sourceUids = listOf(ability, decision))
            }
            EPIDEMIC_EXPOSURE -> {
                val target = ref("target", BODY_KINDS)
                val pathogen = field("pathogen_rule_uid")
                val contact = field("contact_event_uid")
                units("exposure_units")
                Request(operation, parameters, listOf(target), sourceUids = listOf(pathogen, contact))
            }
            else -> throw InvalidParameter("P64:POPULATION_OPERATION_UNAVAILABLE")
        }.let { request -> if (catalog) request.copy(refs = (request.refs + subject).distinct()) else request }
    }

    private class InvalidParameter(reason: String) : IllegalArgumentException(reason)

    private fun identifier(values: Map<String, String>, key: String): String {
        val value = values[key] ?: throw InvalidParameter("P64:PARAMETER_REQUIRED:$key")
        if (value.isBlank() || value.length > 160) throw InvalidParameter("P64:INVALID_PARAMETER:$key")
        return value
    }

    private fun positive(values: Map<String, String>, key: String): Long {
        val value = identifier(values, key).toLongOrNull()
            ?: throw InvalidParameter("P64:INVALID_PARAMETER:$key")
        if (value <= 0) throw InvalidParameter("P64:INVALID_PARAMETER:$key")
        return value
    }

    companion object {
        const val POPULATION_MIGRATE = "POPULATION_MIGRATE"
        const val POPULATION_AGE = "POPULATION_AGE"
        const val POPULATION_BIRTH = "POPULATION_BIRTH"
        const val POPULATION_DEATH = "POPULATION_DEATH"
        const val CONFLICT_MOBILIZE = "CONFLICT_MOBILIZE"
        const val CONFLICT_SUPPLY = "CONFLICT_SUPPLY"
        const val CONFLICT_MOVE = "CONFLICT_MOVE"
        const val CONFLICT_COMBAT = "CONFLICT_COMBAT"
        const val EPIDEMIC_EXPOSURE = "EPIDEMIC_EXPOSURE"
        private val AGGREGATE_KINDS = setOf("GROUP", "UNIT")
        private val BODY_KINDS = AGGREGATE_KINDS + setOf("ACTOR", "PLAYER", "NPC")
        private val PLACE_KINDS = setOf("PLACE", "LOCATION")
        private val RESOURCE_KINDS = setOf("ITEM", "ITEM_INSTANCE", "INVENTORY_ITEM", "RESOURCE")
    }
}

/** Captured Core owner preparation for operations already expressible by Phase50/63.
 * Callers supply registered rule definitions and principal-authorized route/pathogen evidence;
 * these helpers produce candidates and never access storage or reset a population member. */
object Phase64PopulationOwnerPreparation {
    const val AGE_TRACK = "BIOLOGICAL_AGE_ELAPSED_MS"

    fun age(
        actor: MechanicalActorView,
        rule: BackgroundProcessDefinition,
        currentElapsedMillis: Long,
        eventUid: String,
        staged: List<PlayerDomainChangePayload> = emptyList()
    ): WorldConsequencePlan {
        if (rule.domain != "POPULATION" || rule.operation != "AGE") return backgroundBlocked("P64:AGING_RULE_REQUIRED")
        if (currentElapsedMillis < 0) return backgroundBlocked("P64:AGING_STATE_REQUIRED")
        if (dead(actor, staged)) return backgroundBlocked("P64:AGING_ACTOR_DEAD")
        val current = runCatching {
            staged.filterIsInstance<MechanicalTrackChange>().filter { it.subject == actor.actor && it.trackUid == AGE_TRACK }
                .fold(currentElapsedMillis) { value, change -> Math.addExact(value, change.delta.units) }
        }.getOrNull() ?: return backgroundBlocked("P64:AGING_OVERFLOW")
        if (current < 0 || runCatching { Math.addExact(current, rule.durationMillis) }.isFailure)
            return backgroundBlocked("P64:AGING_OVERFLOW")
        return WorldConsequencePlan(
            changes = listOf(MechanicalTrackChange(actor.actor, AGE_TRACK, ExactLongDelta.of(rule.durationMillis))),
            sourceUids = evidence(actor, rule, eventUid)
        )
    }

    /** Existing elimination payload moves active anonymous slots to eliminated. Named
     * members are separate mechanical bodies and never included in this remaining pool. */
    fun death(
        actor: MechanicalActorView,
        rule: BackgroundProcessDefinition,
        count: Long,
        eventUid: String,
        staged: List<PlayerDomainChangePayload> = emptyList()
    ): WorldConsequencePlan {
        if (rule.domain != "POPULATION" || rule.operation != "DEATH") return backgroundBlocked("P64:DEATH_RULE_REQUIRED")
        if (count <= 0) return backgroundBlocked("P64:DEATH_COUNT_REQUIRED")
        val population = actor.aggregatePopulation
        if (population == null) {
            if (count != 1L) return backgroundBlocked("P64:NAMED_POPULATION_COUNT_MUST_BE_ONE")
            if (dead(actor, staged)) return backgroundBlocked("P64:DEATH_ALREADY_SETTLED")
            return WorldConsequencePlan(
                changes = listOf(ConditionChange(actor.actor, "DEAD", ConditionOperation.ADD)),
                sourceUids = evidence(actor, rule, eventUid)
            )
        }
        val consumed = runCatching {
            staged.filterIsInstance<AggregatePopulationChange>().filter { it.subject == actor.actor }
                .fold(0L) { total, change -> Math.addExact(total, Math.addExact(change.eliminatedDelta, change.woundedDelta)) }
        }.getOrNull() ?: return backgroundBlocked("P64:POPULATION_ACCOUNTING_OVERFLOW")
        if (consumed > population.activeCount || count > population.activeCount - consumed)
            return backgroundBlocked("P64:ACTIVE_POPULATION_REQUIRED")
        return WorldConsequencePlan(
            changes = listOf(AggregatePopulationChange(actor.actor, eliminatedDelta = count)),
            claims = listOf(WorldResourceClaim(actor.actor, count)),
            sourceUids = evidence(actor, rule, eventUid)
        )
    }

    /** Completion-only travel moves the same bodies and settles the captured route costs.
     * Partial migration needs a real slot-transfer owner and is unavailable here. */
    fun movement(
        actor: MechanicalActorView,
        rule: BackgroundProcessDefinition,
        route: WorldTravelPlan,
        knownRouteFingerprint: String,
        startedAt: WorldTimeTick,
        at: WorldTimeTick,
        eventUid: String,
        migratingCount: Long? = null,
        namedMembers: List<MechanicalActorView> = emptyList(),
        staged: List<PlayerDomainChangePayload> = emptyList()
    ): WorldConsequencePlan {
        if (!((rule.domain == "POPULATION" && rule.operation == "MIGRATE") ||
                (rule.domain == "CONFLICT" && rule.operation == "MOVE")))
            return backgroundBlocked("P64:MOVEMENT_RULE_REQUIRED")
        if (knownRouteFingerprint != route.fingerprint) return backgroundBlocked("P64:KNOWN_ROUTE_REQUIRED")
        val completeAt = runCatching { startedAt + route.duration }.getOrNull()
            ?: return backgroundBlocked("P64:ROUTE_DURATION_OVERFLOW")
        if (at < completeAt) return backgroundBlocked("P64:ROUTE_DURATION_REQUIRED")
        if (route.edges.any { it.validFrom > startedAt || (it.validThrough != null && at >= it.validThrough) })
            return backgroundBlocked("P64:ROUTE_CLOSED")
        if (namedMembers.size > 256 || namedMembers.map { it.actor }.distinct().size != namedMembers.size ||
            (actor.aggregatePopulation == null && namedMembers.isNotEmpty()) ||
            namedMembers.any { it.actor == actor.actor || it.campaignUid != actor.campaignUid || it.aggregatePopulation != null })
            return backgroundBlocked("P64:NAMED_MEMBER_CAPTURE_INVALID")
        val bodies = listOf(actor) + namedMembers
        if (bodies.any { body ->
                val stagedDestination = staged.filterIsInstance<SpatialChange>().lastOrNull { it.subject == body.actor && it.destinationLocation != null }
                    ?.destinationLocation
                !WorldTopologyAnchor.same(stagedDestination ?: body.locationRef ?: return@any true, route.origin)
            }) return backgroundBlocked("P64:TRAVEL_ORIGIN_CHANGED")
        if (bodies.any { dead(it, staged) || it.conditions.any { c -> c.intensity > 0 && c.conditionUid in setOf("UNCONSCIOUS", "INCAPACITATED") } })
            return backgroundBlocked("P64:TRAVEL_ACTOR_INCAPACITATED")
        if (!route.edges.all { actor.executableAbilityUids.containsAll(it.requiredCapabilities) })
            return backgroundBlocked("P64:TRAVEL_CAPABILITY_REQUIRED")
        if (migratingCount != null) {
            val count = runCatching {
                val count = actor.aggregatePopulation?.let { population ->
                    val casualties = staged.filterIsInstance<AggregatePopulationChange>().filter { it.subject == actor.actor }
                        .fold(0L) { sum, change -> Math.addExact(sum, change.eliminatedDelta) }
                    Math.subtractExact(Math.addExact(population.activeCount, population.woundedCount), casualties)
                } ?: 1L
                Math.addExact(count, namedMembers.size.toLong())
            }.getOrNull() ?: return backgroundBlocked("P64:POPULATION_ACCOUNTING_OVERFLOW")
            if (migratingCount != count) return backgroundBlocked("P64:PARTIAL_MIGRATION_OWNER_REQUIRED")
        }
        val costs = mutableListOf<PlayerDomainChangePayload>()
        val claims = mutableListOf<WorldResourceClaim>()
        for ((uid, cost) in route.resourceCosts.toSortedMap()) {
            val owned = actor.resources.singleOrNull { it.resourceUid == uid }
                ?: return backgroundBlocked("P64:TRAVEL_RESOURCE_REQUIRED")
            val available = runCatching {
                staged.filterIsInstance<ResourceChange>().filter { it.subject == actor.actor && it.resourceUid == uid }
                    .fold(owned.current) { total, change -> Math.addExact(total, change.delta.units) }
            }.getOrNull() ?: return backgroundBlocked("P64:TRAVEL_RESOURCE_OVERFLOW")
            if (available < cost) return backgroundBlocked("P64:TRAVEL_RESOURCE_REQUIRED")
            costs += ResourceChange(actor.actor, uid, ExactLongDelta.of(Math.negateExact(cost)))
            claims += WorldResourceClaim(phase64MechanicalResource(actor.actor, uid), cost)
        }
        return WorldConsequencePlan(
            changes = costs + bodies.map { SpatialChange(it.actor, 0, destinationLocation = route.destination) },
            claims = claims,
            sourceUids = (evidence(actor, rule, eventUid) + route.edges.map { it.provenanceUid } + route.fingerprint +
                namedMembers.map { it.generationProvenanceUid }).distinct()
        )
    }

    /** No epidemiological random event is created: an existing contact and a registered
     * mechanical condition policy determine this one exposure. */
    fun exposure(
        actor: MechanicalActorView,
        rule: BackgroundProcessDefinition,
        registeredConditionUid: String,
        minimumExposureUnits: Long,
        exposureUnits: Long,
        contactEventUid: String,
        eventUid: String,
        affectedCount: Long = 1,
        staged: List<PlayerDomainChangePayload> = emptyList()
    ): WorldConsequencePlan {
        if (rule.domain != "EPIDEMIC" || rule.operation != "EXPOSURE" || registeredConditionUid.isBlank() || minimumExposureUnits <= 0)
            return backgroundBlocked("P64:PATHOGEN_MECHANICAL_RULE_REQUIRED")
        if (contactEventUid.isBlank()) return backgroundBlocked("P64:CONTACT_EVIDENCE_REQUIRED")
        if (exposureUnits < minimumExposureUnits) return backgroundBlocked("P64:EXPOSURE_THRESHOLD_NOT_REACHED")
        if (affectedCount <= 0 || dead(actor, staged)) return backgroundBlocked("P64:EXPOSURE_TARGET_UNAVAILABLE")
        val population = actor.aggregatePopulation
        val change: PlayerDomainChangePayload
        if (population == null) {
            if (affectedCount != 1L) return backgroundBlocked("P64:NAMED_POPULATION_COUNT_MUST_BE_ONE")
            val already = staged.filterIsInstance<ConditionChange>().lastOrNull { it.subject == actor.actor && it.conditionUid == registeredConditionUid }
            if (already?.operation == ConditionOperation.ADD || (already == null && actor.conditions.any { it.conditionUid == registeredConditionUid && it.intensity > 0 }))
                return backgroundBlocked("P64:EXPOSURE_ALREADY_SETTLED")
            change = ConditionChange(actor.actor, registeredConditionUid, ConditionOperation.ADD)
        } else {
            val prior = runCatching {
                staged.filterIsInstance<AggregatePopulationChange>().filter { it.subject == actor.actor && it.conditionUid == registeredConditionUid }
                    .fold(population.conditionCounts[registeredConditionUid] ?: 0L) { total, value -> Math.addExact(total, value.conditionAffectedDelta) }
            }.getOrNull() ?: return backgroundBlocked("P64:POPULATION_ACCOUNTING_OVERFLOW")
            val living = runCatching {
                staged.filterIsInstance<AggregatePopulationChange>().filter { it.subject == actor.actor }
                    .fold(Math.addExact(population.activeCount, population.woundedCount)) { total, value -> Math.subtractExact(total, value.eliminatedDelta) }
            }.getOrNull() ?: return backgroundBlocked("P64:POPULATION_ACCOUNTING_OVERFLOW")
            if (prior > living || affectedCount > living - prior)
                return backgroundBlocked("P64:EXPOSURE_POPULATION_REQUIRED")
            change = AggregatePopulationChange(actor.actor, conditionUid = registeredConditionUid, conditionAffectedDelta = affectedCount)
        }
        return WorldConsequencePlan(changes = listOf(change), sourceUids = evidence(actor, rule, eventUid) + contactEventUid)
    }

    private fun dead(actor: MechanicalActorView, staged: List<PlayerDomainChangePayload>): Boolean {
        actor.aggregatePopulation?.let { population ->
            if (population.activeCount == 0L && population.woundedCount == 0L) return true
        }
        val stagedDeath = staged.filterIsInstance<ConditionChange>().lastOrNull { it.subject == actor.actor && it.conditionUid == "DEAD" }
        return stagedDeath?.operation == ConditionOperation.ADD || (stagedDeath == null && actor.conditions.any { it.conditionUid == "DEAD" && it.intensity > 0 })
    }

    private fun evidence(actor: MechanicalActorView, rule: BackgroundProcessDefinition, eventUid: String): List<String> {
        require(eventUid.isNotBlank())
        return listOf(rule.uid, actor.generationProvenanceUid, eventUid)
    }
}
