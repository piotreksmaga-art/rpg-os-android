package com.rpgos.app

/** Executable, opt-in rules, not demographic events. Registration grants no capability,
 * access, inventory, contact or population. A legal NPC still needs its materialized action
 * and an activation_policy_uid grant for the exact registered definition. */
internal object Phase64PopulationRuleCatalog {
    const val SUBJECT_POLICY = "P64:POPULATION_SUBJECT_MAPPING:2"
    const val CAPTURED_MANIFEST = "@CAPTURED_SUBJECT_MANIFEST"
    const val WHOLE_ACTIVE = "@WHOLE_ACTIVE_ANONYMOUS"
    const val WHOLE_LIVING = "@WHOLE_LIVING_COHORT"
    const val MORTALITY_POLICY = "P64:MECHANICAL_HEALTH_DEPLETED:1"
    const val REPRODUCTION_ABILITY = "REPRODUCE"

    fun definitions(): List<BackgroundProcessDefinition> = listOf(
        targetRule("P64:CORE:POPULATION:AGE", "POPULATION", "AGE", "AGE_POPULATION", 60_000,
            mapOf("source_manifest_uid" to CAPTURED_MANIFEST)),
        targetRule("P64:CORE:POPULATION:BIRTH", "POPULATION", "BIRTH", "GROW_POPULATION_COHORT", 60_000,
            mapOf("source_manifest_uid" to CAPTURED_MANIFEST, "count" to "1", "max_birth_count" to "1",
                "cohort_rule_uid" to Phase64DemographyPreparation.NEWBORN_PROFILE, "cohort_rule_version" to "1",
                "required_subject_ability_uid" to REPRODUCTION_ABILITY)),
        targetRule("P64:CORE:POPULATION:DEATH", "POPULATION", "DEATH", "SETTLE_POPULATION_DEATH", 1000,
            mapOf("source_manifest_uid" to CAPTURED_MANIFEST, "count" to WHOLE_ACTIVE,
                "cause_rule_uid" to MORTALITY_POLICY, "mortality_policy_uid" to MORTALITY_POLICY)),
        targetRule("P64:CORE:CONFLICT:MOBILIZE", "CONFLICT", "MOBILIZE", "MOBILIZE_FORMATION", 1000,
            mapOf("source_manifest_uid" to CAPTURED_MANIFEST, "count" to WHOLE_ACTIVE,
                "formation_kind_uid" to "@TARGET_KIND", "formation_uid" to "@TARGET_UID")))

    /** One existing named body, never implicit control over its origin cohort. */
    fun namedMigration(body: MechanicalActorView, route: WorldTravelPlan): BackgroundProcessDefinition {
        require(body.aggregatePopulation == null && body.actor.kindUid in setOf("ACTOR", "NPC"))
        return movement(Phase64PopulationActivitySnapshot(body), route, "POPULATION", "MIGRATE")
    }

    /** A fixed real source and route. All living anonymous slots and named members move
     * together; casualties remain casualties. No anonymous split or new formation is implied. */
    fun cohortMovement(snapshot: Phase64PopulationActivitySnapshot, route: WorldTravelPlan,
        mobilizedFormation: Boolean = false): BackgroundProcessDefinition {
        require(lineageReason(snapshot) == null && snapshot.body.aggregatePopulation != null)
        if (mobilizedFormation) require(snapshot.body.actor.kindUid == "UNIT" && snapshot.alreadyMobilized)
        return movement(snapshot, route, if (mobilizedFormation) "CONFLICT" else "POPULATION", if (mobilizedFormation) "MOVE" else "MIGRATE")
    }

    private fun movement(snapshot: Phase64PopulationActivitySnapshot, route: WorldTravelPlan, domain: String, operation: String): BackgroundProcessDefinition {
        require(WorldTopologyAnchor.same(requireNotNull(snapshot.body.locationRef), route.origin) && route.duration.milliseconds > 0)
        val uid = "P64:CATALOG:$domain:$operation:${phase63Hash("${snapshot.body.campaignUid}|${snapshot.body.actor}|${route.fingerprint}").take(32)}"
        return targetRule(uid, domain, operation, if (operation == "MOVE") "MOVE_WHOLE_FORMATION" else "MIGRATE_WHOLE_COHORT",
            route.duration.milliseconds, mapOf("population_kind_uid" to snapshot.body.actor.kindUid, "population_uid" to snapshot.body.actor.uid,
                "source_manifest_uid" to (snapshot.manifest?.uid ?: CAPTURED_MANIFEST), "count" to WHOLE_LIVING,
                "destination_kind_uid" to "@TARGET_KIND", "destination_uid" to "@TARGET_UID",
                "registered_destination_kind_uid" to route.destination.kindUid, "registered_destination_uid" to route.destination.uid,
                "registered_route_fingerprint" to route.fingerprint))
    }

    /** The resource must already be registered and present on both captured bodies. */
    fun supplyResource(resourceUid: String, count: Long = 1): BackgroundProcessDefinition {
        npcUid(resourceUid); require(count > 0)
        return targetRule("P64:CATALOG:CONFLICT:SUPPLY:${phase63Hash("$resourceUid|$count").take(32)}", "CONFLICT", "SUPPLY", "SUPPLY_FORMATION", 1000,
            mapOf("supplier_kind_uid" to "@ACTOR_KIND", "supplier_uid" to "@ACTOR_UID", "resource_kind_uid" to "RESOURCE",
                "resource_uid" to resourceUid, "count" to count.toString()))
    }

    /** No default disease or fabricated contact. The registrar must resolve an existing
     * mechanical condition policy and committed contact before importing this definition. */
    fun exposure(pathogenRuleUid: String, conditionUid: String, minimumExposureUnits: Long, exposureUnits: Long,
        contactEventUid: String, affectedCount: Long = 1): BackgroundProcessDefinition {
        listOf(pathogenRuleUid, conditionUid, contactEventUid).forEach(::npcUid)
        require(minimumExposureUnits > 0 && exposureUnits >= minimumExposureUnits && affectedCount > 0)
        val hash = phase63Hash(listOf(pathogenRuleUid, conditionUid, minimumExposureUnits, exposureUnits, contactEventUid, affectedCount).joinToString("|"))
        return targetRule("P64:CATALOG:EPIDEMIC:EXPOSURE:${hash.take(32)}", "EPIDEMIC", "EXPOSURE", "SETTLE_REGISTERED_EXPOSURE", 1000,
            mapOf("target_kind_uid" to "@TARGET_KIND", "target_uid" to "@TARGET_UID", "pathogen_rule_uid" to pathogenRuleUid,
                "pathogen_condition_uid" to conditionUid, "minimum_exposure_units" to minimumExposureUnits.toString(),
                "exposure_units" to exposureUnits.toString(), "contact_event_uid" to contactEventUid, "affected_count" to affectedCount.toString()))
    }

    private fun targetRule(uid: String, domain: String, operation: String, action: String, duration: Long, fields: Map<String, String>) =
        BackgroundProcessDefinition(uid, 2, domain, operation, duration, parameters = mapOf(
            Phase64ProcessActivation.ACTION_KEY to (if (uid.startsWith("P64:CATALOG:")) "$action:${phase63Hash(uid).take(16)}" else action),
            "activation_npc" to "true", Phase64ProcessActivation.PUBLIC_KEY to "false",
            "activation_policy_uid" to "P64:POLICY:${phase63Hash(uid).take(32)}", "population_subject_policy_uid" to SUBJECT_POLICY,
            "population_kind_uid" to "@TARGET_KIND", "population_uid" to "@TARGET_UID") + fields)

    fun matches(definition: BackgroundProcessDefinition): Boolean = definition.version == 2 &&
        definition.parameters["population_subject_policy_uid"] == SUBJECT_POLICY &&
        "${definition.domain}_${definition.operation}" in OPERATIONS

    fun subject(definition: BackgroundProcessDefinition, parameters: Map<String, String>, principal: DomainRef): DomainRef? {
        if (!matches(definition)) return principal
        if (parameters["population_subject_policy_uid"] != SUBJECT_POLICY) return null
        val kind = parameters["population_kind_uid"]?.takeIf { it in BODY_KINDS } ?: return null
        val uid = parameters["population_uid"]?.takeIf { it.isNotBlank() && it.length <= 160 && !it.startsWith("@") } ?: return null
        return DomainRef(kind, uid)
    }

    fun lineageReason(snapshot: Phase64PopulationActivitySnapshot): String? {
        val population = snapshot.body.aggregatePopulation ?: return null
        val manifest = snapshot.manifest ?: return "P64:POPULATION_LINEAGE_REQUIRED"
        if (snapshot.namedCount !in 0..256 || manifest.aggregate != snapshot.body.actor ||
            manifest.originalCount - snapshot.namedCount != population.totalCount ||
            Math.addExact(Math.addExact(population.activeCount, population.woundedCount), population.eliminatedCount) != population.totalCount)
            return "P64:POPULATION_LINEAGE_CHANGED"
        return null
    }

    /** Preconditions are read-only and are repeated at completion against the staged view. */
    fun unavailable(definition: BackgroundProcessDefinition, parameters: Map<String, String>, principal: DomainRef,
        snapshot: Phase64PopulationActivitySnapshot): String? {
        if (!matches(definition)) return "P64:POPULATION_CATALOG_RULE_REQUIRED"
        val subject = subject(definition, parameters, principal) ?: return "P64:POPULATION_SUBJECT_REQUIRED"
        val body = snapshot.body
        if (subject != body.actor || body.actor.kindUid == "PLAYER" || body.kind == MechanicalActorKind.ACTIVE_PLAYER)
            return "P64:POPULATION_SUBJECT_CHANGED"
        lineageReason(snapshot)?.let { return it }
        val population = body.aggregatePopulation
        val living = population?.let { Math.addExact(it.activeCount, it.woundedCount) } ?: 1L
        if (living == 0L || body.conditions.any { it.conditionUid == "DEAD" && it.intensity > 0 }) return "P64:POPULATION_SUBJECT_DEAD"
        when (definition.operation) {
            "AGE" -> if (snapshot.ageElapsedMillis < 0 || runCatching { Math.addExact(snapshot.ageElapsedMillis, definition.durationMillis) }.isFailure)
                return "P64:AGING_OVERFLOW"
            "BIRTH" -> if (population == null || population.activeCount == 0L ||
                body.locationRef == null ||
                body.generationProvenanceUid.startsWith(Phase64DemographyPreparation.NEWBORN_PROFILE) ||
                definition.parameters["required_subject_ability_uid"] !in body.executableAbilityUids) return "P64:REPRODUCTIVE_COHORT_REQUIRED"
            "DEATH" -> if (definition.parameters["mortality_policy_uid"] != MORTALITY_POLICY ||
                body.resources.singleOrNull { it.resourceUid == "HEALTH" }?.current != 0L ||
                population?.activeCount == 0L) return "P64:MECHANICAL_MORTALITY_EVIDENCE_REQUIRED"
            "MOBILIZE", "SUPPLY", "MOVE" -> {
                if (body.actor.kindUid != "UNIT" || population == null || population.activeCount == 0L ||
                    body.conditions.any { it.intensity > 0 && it.conditionUid in setOf("INCAPACITATED", "UNCONSCIOUS") })
                    return "P64:EXISTING_ACTIVE_FORMATION_REQUIRED"
                if (definition.operation == "MOBILIZE" && snapshot.alreadyMobilized) return "P64:FORMATION_ALREADY_MOBILIZED"
                if (definition.operation in setOf("SUPPLY", "MOVE") && !snapshot.alreadyMobilized) return "P64:MOBILIZED_FORMATION_REQUIRED"
            }
        }
        if (definition.operation in setOf("MIGRATE", "MOVE")) {
            val route = snapshot.route ?: return "P64:KNOWN_ROUTE_REQUIRED"
            if (parameters["destination_kind_uid"] != definition.parameters["registered_destination_kind_uid"] ||
                parameters["destination_uid"] != definition.parameters["registered_destination_uid"] ||
                route.destination != DomainRef(parameters.getValue("destination_kind_uid"), parameters.getValue("destination_uid")) ||
                route.fingerprint != definition.parameters["registered_route_fingerprint"] || route.duration.milliseconds != definition.durationMillis)
                return "P64:REGISTERED_COHORT_ROUTE_CHANGED"
            if (!WorldTopologyAnchor.same(body.locationRef ?: return "P64:TRAVEL_ORIGIN_CHANGED", route.origin) ||
                snapshot.namedMembers.size.toLong() != snapshot.namedCount || snapshot.namedMembers.any {
                    it.campaignUid != body.campaignUid || it.aggregatePopulation != null ||
                        !WorldTopologyAnchor.same(it.locationRef ?: return@any true, route.origin) ||
                        it.conditions.any { c -> c.intensity > 0 && c.conditionUid in setOf("DEAD", "UNCONSCIOUS", "INCAPACITATED") }
                }) return "P64:NAMED_MEMBER_CAPTURE_REQUIRED"
            if (body.conditions.any { it.intensity > 0 && it.conditionUid in setOf("UNCONSCIOUS", "INCAPACITATED") } ||
                route.edges.any { !body.executableAbilityUids.containsAll(it.requiredCapabilities) }) return "P64:TRAVEL_CAPABILITY_REQUIRED"
            if (route.resourceCosts.any { (uid, cost) -> body.resources.singleOrNull { it.resourceUid == uid }?.current?.let { it >= cost } != true })
                return "P64:TRAVEL_RESOURCE_REQUIRED"
        }
        if (definition.operation == "EXPOSURE") {
            if (!snapshot.contactEvidencePresent) return "P64:CONTACT_EVIDENCE_REQUIRED"
            if (parameters["target_kind_uid"] != subject.kindUid || parameters["target_uid"] != subject.uid) return "P64:EXPOSURE_TARGET_REQUIRED"
            val condition = definition.parameters["pathogen_condition_uid"] ?: return "P64:PATHOGEN_MECHANICAL_RULE_REQUIRED"
            val affected = definition.parameters["affected_count"]?.toLongOrNull()?.takeIf { it > 0 } ?: return "P64:EXPOSURE_POPULATION_REQUIRED"
            if (population == null) {
                if (affected != 1L || body.conditions.any { it.conditionUid == condition && it.intensity > 0 }) return "P64:EXPOSURE_ALREADY_SETTLED"
            } else if (affected > living - (population.conditionCounts[condition] ?: 0L)) return "P64:EXPOSURE_POPULATION_REQUIRED"
        }
        return null
    }

    private val BODY_KINDS = setOf("ACTOR", "NPC", "GROUP", "UNIT")
    private val OPERATIONS = setOf("POPULATION_AGE", "POPULATION_BIRTH", "POPULATION_DEATH", "POPULATION_MIGRATE",
        "CONFLICT_MOBILIZE", "CONFLICT_SUPPLY", "CONFLICT_MOVE", "EPIDEMIC_EXPOSURE")
}

internal data class Phase64PopulationActivitySnapshot(val body: MechanicalActorView, val manifest: WorldPopulationManifest? = null,
    val namedCount: Long = 0, val alreadyMobilized: Boolean = false, val route: WorldTravelPlan? = null,
    val contactEvidencePresent: Boolean = false, val namedMembers: List<MechanicalActorView> = emptyList(), val ageElapsedMillis: Long = 0)

/** Capture is bounded to each protected candidate's exact subject/target, never a population
 * scan. The production caller also checks its existing capability and BACKGROUND_RULE grant. */
internal object Phase64PopulationNpcOptions {
    fun options(brain: NpcBrainState, records: List<NpcKnownRecord>, actor: MechanicalActorView, definition: BackgroundProcessDefinition,
        capture: (DomainRef, DomainRef) -> Phase64PopulationActivitySnapshot?): List<NpcActionOption> {
        if (!Phase64PopulationRuleCatalog.matches(definition)) return emptyList()
        return Phase64NpcInitiation.options(brain, records, actor, definition).filter { option ->
            val target = option.target ?: return@filter false
            val parameters = runCatching { Phase64ProcessActivation.bind(definition, actor.actor, target) }.getOrNull() ?: return@filter false
            val subject = Phase64PopulationRuleCatalog.subject(definition, parameters, actor.actor) ?: return@filter false
            if (subject != actor.actor && records.none { subject in it.subjectRefs }) return@filter false
            val snapshot = capture(subject, target) ?: return@filter false
            if (snapshot.body.campaignUid != actor.campaignUid || Phase64PopulationRuleCatalog.unavailable(definition, parameters, actor.actor, snapshot) != null)
                return@filter false
            if (definition.operation == "SUPPLY") {
                val count = parameters["count"]?.toLongOrNull() ?: return@filter false
                val uid = parameters["resource_uid"] ?: return@filter false
                val source = actor.resources.singleOrNull { it.resourceUid == uid } ?: return@filter false
                val destination = snapshot.body.resources.singleOrNull { it.resourceUid == uid } ?: return@filter false
                if (count <= 0 || source.current < count || destination.maximum - destination.current < count) return@filter false
            }
            true
        }
    }
}
