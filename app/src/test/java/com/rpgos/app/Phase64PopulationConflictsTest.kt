package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase64PopulationConflictsTest {
    private val adapter = Phase64PopulationConflictsAdapter()
    private val group = DomainRef("GROUP", "G1")
    private val formation = DomainRef("UNIT", "U1")
    private val person = DomainRef("ACTOR", "NAMED1")
    private val destination = DomainRef("PLACE", "P2")
    private val resource = DomainRef("ITEM", "FOOD1")
    private val scope = BackgroundProcessEvaluationScope(TemporalScope("C1", "H1", 7, "digest"), "seed", "rules")

    private data class CapturedPreparation(
        val operation: String,
        val actor: DomainRef,
        val parameters: Map<String, String>,
        val scope: BackgroundProcessEvaluationScope,
        val staged: List<PlayerDomainChangePayload>
    )

    private class Reads : BackgroundWorldReadPort {
        var authorized = true
        var denyWithStagedChanges = false
        var missing: DomainRef? = null
        var route: String? = "ACTOR-KNOWN-ROUTE"
        var quantity: Long? = 8
        var owned = WorldConsequencePlan(changes = listOf(ConditionChange(DomainRef("UNIT", "U1"), "WOUNDED", ConditionOperation.ADD)),
            sourceUids = listOf("OWNER-PROOF"))
        val preparations = mutableListOf<CapturedPreparation>()
        val authorizedRefs = mutableListOf<List<DomainRef>>()
        val authorizationStaged = mutableListOf<List<PlayerDomainChangePayload>>()
        val routedActors = mutableListOf<DomainRef>()
        val availabilityReads = mutableListOf<Pair<DomainRef, List<PlayerDomainChangePayload>>>()
        override fun available(resource: DomainRef, staged: List<PlayerDomainChangePayload>): Long? {
            availabilityReads += resource to staged
            return quantity
        }
        override fun exists(ref: DomainRef) = ref != missing
        override fun route(actor: DomainRef, destination: DomainRef, at: WorldTimeTick): String? {
            routedActors += actor
            return route
        }
        override fun authorize(actor: DomainRef, purpose: String, refs: List<DomainRef>): Boolean {
            authorizedRefs += refs
            return authorized
        }
        override fun authorize(actor: DomainRef, purpose: String, refs: List<DomainRef>, staged: List<PlayerDomainChangePayload>): Boolean {
            authorizationStaged += staged
            if (denyWithStagedChanges && staged.isNotEmpty()) return false
            return authorize(actor, purpose, refs)
        }
        override fun prepareOwnedEffect(operation: String, actor: DomainRef, parameters: Map<String, String>,
            scope: BackgroundProcessEvaluationScope, staged: List<PlayerDomainChangePayload>): WorldConsequencePlan {
            preparations += CapturedPreparation(operation, actor, parameters.toMap(), scope, staged)
            return owned
        }
    }

    private fun definition(domain: String, operation: String, parameters: Map<String, String>) =
        BackgroundProcessDefinition("RULE-$domain-$operation", 2, domain, operation, 1000, parameters = parameters)
    private fun process(definition: BackgroundProcessDefinition, actor: DomainRef = group) =
        BackgroundProcessInstance("PROCESS1", definition.uid, definition.version, actor, 1, WorldTimeTick(0), WorldTimeTick(1000))
    private fun evaluate(definition: BackgroundProcessDefinition, reads: Reads, actor: DomainRef = group,
        staged: List<PlayerDomainChangePayload> = emptyList(), currentScope: BackgroundProcessEvaluationScope = scope) =
        adapter.evaluate(definition, process(definition, actor), currentScope, WorldTimeTick(1000), reads, staged)
    private fun destinationParameters() = mapOf("destination_kind_uid" to destination.kindUid, "destination_uid" to destination.uid)

    @Test fun migrationUsesKnownSubjectRouteAndPreservesOwnerPayloadAndLineage() {
        val def = definition("POPULATION", "MIGRATE", destinationParameters() + mapOf("count" to "4", "route_uid" to "AI-ROUTE"))
        val reads = Reads()
        val transition = SpatialChange(group, 0, destinationLocation = destination)
        val staged = listOf<PlayerDomainChangePayload>(ConditionChange(group, "WOUNDED", ConditionOperation.ADD))
        reads.owned = WorldConsequencePlan(changes = listOf(transition), claims = listOf(WorldResourceClaim(group, 4)),
            sourceUids = listOf("UNCHANGED-P63-LINEAGE"))
        val result = evaluate(def, reads, staged = staged)
        assertEquals(listOf(transition), result.changes)
        assertEquals(reads.owned.claims, result.claims)
        assertTrue("UNCHANGED-P63-LINEAGE" in result.sourceUids)
        assertEquals(listOf(group), reads.routedActors)
        assertEquals(listOf(destination), reads.authorizedRefs.single())
        assertEquals("ACTOR-KNOWN-ROUTE", reads.preparations.single().parameters["route_uid"])
        assertSame(staged, reads.preparations.single().staged)
        assertSame(staged, reads.authorizationStaged.single())
        assertTrue(reads.availabilityReads.isEmpty())
    }

    @Test fun missingRouteAndDeniedAccessDoNotPrepareArrivalOrReadSupply() {
        val def = definition("CONFLICT", "MOVE", destinationParameters())
        val missingRoute = Reads().apply { route = null }
        assertEquals("P64:KNOWN_ROUTE_REQUIRED", evaluate(def, missingRoute, formation).reasonUid)
        assertTrue(missingRoute.preparations.isEmpty())
        val denied = Reads().apply { authorized = false }
        assertEquals("P64:PROCESS_NOT_AUTHORIZED", evaluate(def, denied, formation).reasonUid)
        assertTrue(denied.routedActors.isEmpty())
        assertTrue(denied.preparations.isEmpty())
        assertTrue(denied.availabilityReads.isEmpty())
    }

    @Test fun newCohortHasStableIndependentIdentityAndMissingOwnerStaysBlocked() {
        val def = definition("POPULATION", "BIRTH", mapOf("count" to "2", "source_manifest_uid" to "OLD-MANIFEST",
            "cohort_rule_uid" to "REGISTERED-COHORT", "cohort_rule_version" to "3", "cohort_uid" to "FORGED",
            "POWER" to "99999"))
        val reads = Reads().apply { owned = backgroundBlocked("P64:COHORT_OWNER_RULE_REQUIRED") }
        val blocked = evaluate(def, reads)
        assertEquals(reads.owned, blocked)
        assertTrue(blocked.changes.isEmpty())
        val params = reads.preparations.single().parameters
        assertEquals("OLD-MANIFEST", params["source_manifest_uid"])
        assertTrue(params.getValue("cohort_uid").startsWith("P64-COHORT-"))
        assertNotEquals("FORGED", params["cohort_uid"])
        assertFalse("POWER" in params)
        val repeated = Reads()
        evaluate(def, repeated, currentScope = scope.copy(temporal = scope.temporal.copy(historyGenerationUid = "H2", baseCommitOrder = 99)))
        assertEquals(params["cohort_uid"], repeated.preparations.single().parameters["cohort_uid"])
        assertEquals(params["p64_logical_event_uid"], repeated.preparations.single().parameters["p64_logical_event_uid"])
        assertEquals("H2", repeated.preparations.single().scope.temporal.historyGenerationUid)
    }

    @Test fun agingUsesRegisteredElapsedTimeAndDoesNotResizeExistingManifest() {
        val def = definition("POPULATION", "AGE", mapOf("source_manifest_uid" to "MANIFEST1", "age_increment_ms" to "99999"))
        val reads = Reads().apply { owned = backgroundBlocked("P64:DEMOGRAPHIC_STATE_REQUIRED") }
        assertEquals(reads.owned, evaluate(def, reads))
        val parameters = reads.preparations.single().parameters
        assertEquals("1000", parameters["age_increment_ms"])
        assertEquals("MANIFEST1", parameters["source_manifest_uid"])
        assertFalse("count" in parameters)
        val named = Reads()
        evaluate(definition("POPULATION", "AGE", emptyMap()), named, person)
        assertEquals(person, named.preparations.single().actor)
        assertFalse("source_manifest_uid" in named.preparations.single().parameters)
    }

    @Test fun namedDeathDelegatesMechanicsWithoutErasingIdentityOrProducingAnotherBody() {
        val def = definition("POPULATION", "DEATH", mapOf("count" to "1", "cause_rule_uid" to "REGISTERED-CAUSE"))
        val reads = Reads().apply { owned = WorldConsequencePlan(changes = listOf(ConditionChange(person, "DEAD", ConditionOperation.ADD))) }
        val result = evaluate(def, reads, person)
        assertEquals(reads.owned.changes, result.changes)
        assertEquals(person, reads.preparations.single().actor)
        assertEquals("1", reads.preparations.single().parameters["count"])
        assertTrue(result.changes.all { it is ConditionChange && it.subject == person })
        assertTrue(reads.availabilityReads.isEmpty())
    }

    @Test fun namedPersonCannotBeDuplicatedByBirthOrCountedAsSeveralCasualties() {
        val birth = definition("POPULATION", "BIRTH", mapOf("count" to "1", "source_manifest_uid" to "M1",
            "cohort_rule_uid" to "R1", "cohort_rule_version" to "1"))
        val reads = Reads()
        assertEquals("P64:AGGREGATE_POPULATION_REQUIRED", evaluate(birth, reads, person).reasonUid)
        val death = definition("POPULATION", "DEATH", mapOf("count" to "2", "cause_rule_uid" to "R1"))
        assertEquals("P64:NAMED_POPULATION_COUNT_MUST_BE_ONE", evaluate(death, reads, person).reasonUid)
        assertTrue(reads.preparations.isEmpty())
    }

    @Test fun mobilizationReferencesExistingFormationAndPreservesTypedPartitionOwnerResult() {
        val def = definition("CONFLICT", "MOBILIZE", mapOf("count" to "3", "source_manifest_uid" to "MANIFEST1",
            "formation_kind_uid" to formation.kindUid, "formation_uid" to formation.uid))
        val missing = Reads().apply { this.missing = formation }
        assertEquals("P64:PROCESS_REFERENCE_UNAVAILABLE", evaluate(def, missing).reasonUid)
        assertTrue(missing.preparations.isEmpty())
        val reads = Reads().apply { owned = backgroundBlocked("P64:POPULATION_PARTITION_RULE_REQUIRED") }
        assertEquals(reads.owned, evaluate(def, reads))
        assertEquals(listOf(formation), reads.authorizedRefs.single())
        assertEquals("MANIFEST1", reads.preparations.single().parameters["source_manifest_uid"])
        assertTrue(reads.availabilityReads.isEmpty())
    }

    @Test fun supplyChecksSpeculativeQuantityAndReservesActualOwnedItem() {
        val pool = DomainRef("RESOURCE", "STAMINA")
        val capacity = phase64MechanicalResource(group, pool.uid)
        val def = definition("CONFLICT", "SUPPLY", mapOf("supplier_kind_uid" to group.kindUid, "supplier_uid" to group.uid,
            "resource_kind_uid" to pool.kindUid, "resource_uid" to pool.uid, "count" to "5"))
        val staged = listOf<PlayerDomainChangePayload>(ResourceChange(group, pool.uid, ExactLongDelta.of(-2)))
        val reads = Reads().apply {
            quantity = 5
            owned = WorldConsequencePlan(changes = listOf(ResourceChange(group, pool.uid, ExactLongDelta.of(-5)),
                ResourceChange(formation, pool.uid, ExactLongDelta.of(5))), claims = listOf(WorldResourceClaim(capacity, 5)))
        }
        val result = evaluate(def, reads, formation, staged)
        assertEquals(listOf(WorldResourceClaim(capacity, 5)), result.claims)
        assertEquals(capacity, reads.availabilityReads.single().first)
        assertSame(staged, reads.availabilityReads.single().second)
        assertEquals(reads.owned.changes, result.changes)
        val shortage = Reads().apply { quantity = 4 }
        assertEquals("P64:SUPPLY_RESOURCE_REQUIRED", evaluate(def, shortage, formation, staged).reasonUid)
        assertTrue(shortage.preparations.isEmpty())
        val unknown = Reads().apply { quantity = null }
        assertEquals("P64:SUPPLY_AVAILABILITY_REQUIRED", evaluate(def, unknown, formation).reasonUid)
    }

    @Test fun combatCarriesExistingMechanicalProofAndDoesNotAcceptInventedStatistics() {
        val def = definition("CONFLICT", "COMBAT", mapOf("target_kind_uid" to formation.kindUid, "target_uid" to formation.uid,
            "ability_uid" to "ABILITY1", "decision_uid" to "P62-DECISION1", "POWER" to "99999", "casualties" to "100"))
        val effect = VerifiedMechanicsCommandEffect("FX1", "NODE1", "UNIVERSAL_COMBAT", "WOUND", formation, 2,
            mapOf("proof" to "UNCHANGED"), "P50-PROOF1", "INPUT1", "OUTPUT1")
        val reads = Reads().apply { owned = WorldConsequencePlan(effects = listOf(effect), sourceUids = listOf("P50-PROOF1")) }
        val result = evaluate(def, reads)
        assertEquals(listOf(effect), result.effects)
        assertTrue(result.changes.isEmpty())
        assertTrue("P50-PROOF1" in result.sourceUids)
        assertTrue("P62-DECISION1" in result.sourceUids)
        assertFalse("POWER" in reads.preparations.single().parameters)
        assertFalse("casualties" in reads.preparations.single().parameters)
        assertEquals("CONFLICT_COMBAT", reads.preparations.single().operation)
    }

    @Test fun exposureRequiresRegisteredContactAndMissingMechanicsCannotInventEpidemic() {
        val def = definition("EPIDEMIC", "EXPOSURE", mapOf("target_kind_uid" to person.kindUid, "target_uid" to person.uid,
            "pathogen_rule_uid" to "PATHOGEN1", "contact_event_uid" to "CONTACT1", "exposure_units" to "2"))
        val reads = Reads().apply { owned = backgroundBlocked("P64:PATHOGEN_MECHANICAL_RULE_REQUIRED") }
        assertEquals(reads.owned, evaluate(def, reads))
        assertEquals("CONTACT1", reads.preparations.single().parameters["contact_event_uid"])
        assertEquals("EPIDEMIC_EXPOSURE", reads.preparations.single().operation)
        val noContact = Reads()
        assertEquals("P64:PARAMETER_REQUIRED:contact_event_uid", evaluate(def.copy(parameters = def.parameters - "contact_event_uid"), noContact).reasonUid)
        assertTrue(noContact.preparations.isEmpty())
    }

    @Test fun malformedOrUnsupportedRulesNeverReachOwnerPreparation() {
        listOf("0", "-1", "9223372036854775808", "1.5", " ").forEach { count ->
            val reads = Reads()
            val def = definition("POPULATION", "MIGRATE", destinationParameters() + mapOf("count" to count))
            assertEquals("P64:INVALID_PARAMETER:count", evaluate(def, reads).reasonUid)
            assertTrue(reads.preparations.isEmpty())
        }
        val reads = Reads()
        assertEquals("P64:POPULATION_OPERATION_UNAVAILABLE", evaluate(definition("CONFLICT", "RANDOM_WAR", emptyMap()), reads).reasonUid)
        assertTrue(reads.preparations.isEmpty())
    }

    @Test fun earlyTerminalAndMismatchedProcessCannotConsumeAnything() {
        val def = definition("CONFLICT", "MOVE", destinationParameters())
        val initial = process(def)
        val reads = Reads()
        assertEquals("P64:PROCESS_NOT_DUE", adapter.evaluate(def, initial, scope, WorldTimeTick(999), reads, emptyList()).reasonUid)
        listOf(BackgroundProcessStatus.COMPLETED, BackgroundProcessStatus.INTERRUPTED, BackgroundProcessStatus.FAILED).forEach { status ->
            assertEquals("P64:PROCESS_NOT_ACTIVE", adapter.evaluate(def, initial.copy(status = status), scope, WorldTimeTick(1000), reads, emptyList()).reasonUid)
        }
        assertEquals("P64:PROCESS_RULE_MISMATCH", adapter.evaluate(def, initial.copy(definitionVersion = 1), scope, WorldTimeTick(1000), reads, emptyList()).reasonUid)
        assertTrue(reads.preparations.isEmpty())
        assertTrue(reads.authorizedRefs.isEmpty())
    }

    @Test fun retriesAndEvaluationBatchBoundariesKeepOneLogicalEventButDifferentEventsDoNotCollide() {
        val def = definition("POPULATION", "DEATH", mapOf("count" to "1", "cause_rule_uid" to "CAUSE1", "source_manifest_uid" to "M1",
            "p64_logical_event_uid" to "FORGED"))
        val initial = process(def)
        val reads = Reads()
        listOf(initial, initial.copy(status = BackgroundProcessStatus.BLOCKED, due = WorldTimeTick(1200)), initial.copy(version = 2)).forEach { candidate ->
            adapter.evaluate(def, candidate, scope, WorldTimeTick(1200), reads, emptyList())
        }
        val events = reads.preparations.map { it.parameters.getValue("p64_logical_event_uid") }
        assertEquals(1, events.distinct().size)
        assertNotEquals("FORGED", events.first())
        val another = Reads()
        adapter.evaluate(def, initial.copy(progressUnits = 1, due = WorldTimeTick(2000)), scope, WorldTimeTick(2000), another, emptyList())
        assertNotEquals(events.first(), another.preparations.single().parameters["p64_logical_event_uid"])
    }

    @Test fun ownerSuccessRequiresActualTypedOutcomeAndOwnerFailureIsNotRewritten() {
        val def = definition("CONFLICT", "MOVE", destinationParameters())
        val empty = Reads().apply { owned = WorldConsequencePlan() }
        assertEquals("P64:OWNED_RESULT_REQUIRED:CONFLICT_MOVE", evaluate(def, empty, formation).reasonUid)
        val interrupted = Reads().apply {
            owned = WorldConsequencePlan(status = BackgroundProcessStatus.INTERRUPTED, reasonUid = "ROUTE_CLOSED", sourceUids = listOf("CLOSURE1"))
        }
        assertEquals(interrupted.owned, evaluate(def, interrupted, formation))
    }

    private fun body(ref: DomainRef = group, population: AggregateMechanicalPopulation? = AggregateMechanicalPopulation(5, 3, 1, 1)) =
        MechanicalActorView("C1", ref, if (population == null) MechanicalActorKind.NPC else if (ref.kindUid == "UNIT") MechanicalActorKind.UNIT else MechanicalActorKind.GROUP,
            4, MechanicalStateMaterialization.FULL, mapOf("POWER" to 30, "DEFENCE" to 30, "SKILL" to 30, "AGILITY" to 30),
            listOf(MechanicalResource("STAMINA", 10, 20)), setOf("TRAVEL"), locationRef = DomainRef("PLACE", "P1"),
            generationProvenanceUid = "ORIGINAL-BODY", aggregatePopulation = population)

    @Test fun speculativeBodyRejectsIntegrityAndCombatTracksWithoutAuthoritativeRawState() {
        val original = body()
        val unsupported = listOf<PlayerDomainChangePayload>(
            EquipmentIntegrityChange(group, "ARMOR1", ExactLongDelta.of(2)),
            StructureIntegrityChange(group, damageDelta = ExactLongDelta.of(2)),
            EquipmentChange(group, "HAND", EquipmentOperation.EQUIP, "WEAPON1"),
            RuntimeChange(group, "RPGOS-MECHANICS:MOVEMENT", ExactLongDelta.of(1))
        ) + listOf("WOUND", "MORALE", "COHESION", "FORMATION").map { track ->
            MechanicalTrackChange(group, track, ExactLongDelta.of(-1))
        }
        unsupported.forEach { change ->
            assertNull(change.toString(), Phase64PopulationProductionReads.projectBody(original, listOf(change)))
        }
        val unrelated = listOf<PlayerDomainChangePayload>(EquipmentIntegrityChange(formation, "ARMOR1", ExactLongDelta.of(2)),
            MechanicalTrackChange(formation, "MORALE", ExactLongDelta.of(-1)))
        assertEquals(original, Phase64PopulationProductionReads.projectBody(original, unrelated))
        assertEquals(4L, original.stateVersion)
    }

    @Test fun speculativeBodyCannotReusePlayerResourcePoolAfterSeparateOwnerChange() {
        val player = DomainRef("PLAYER", "PLAYER1")
        val original = body(player, null).copy(kind = MechanicalActorKind.ACTIVE_PLAYER)
        assertNull(Phase64PopulationProductionReads.projectBody(original,
            listOf(ResourceChange(player, "STAMINA", ExactLongDelta.of(-2)))))
        assertEquals(original, Phase64PopulationProductionReads.projectBody(original,
            listOf(ResourceChange(person, "STAMINA", ExactLongDelta.of(-2)))))
        assertEquals(10L, original.resources.single().current)
    }

    @Test fun speculativeBodyConservesPoolsAndRestoresDefenceFromBaselineAfterClipping() {
        val original = body()
        val projected = Phase64PopulationProductionReads.projectBody(original, listOf(
            ResourceChange(group, "STAMINA", ExactLongDelta.of(-2)),
            SpatialChange(group, 0, destinationLocation = destination),
            WoundChange(group, ExactLongDelta.of(40)),
            WoundChange(group, ExactLongDelta.of(-25)),
            ConditionChange(group, "EXPOSED:PATHOGEN1", ConditionOperation.ADD),
            AggregatePopulationChange(group, eliminatedDelta = 1, woundedDelta = 1),
            MechanicalTrackChange(group, Phase64PopulationOwnerPreparation.AGE_TRACK, ExactLongDelta.of(1000))
        ))!!
        assertEquals(10L, projected.stateVersion)
        assertEquals(8L, projected.resources.single().current)
        assertEquals(destination, projected.locationRef)
        assertEquals(15L, projected.attributes["DEFENCE"])
        assertEquals(30L, projected.unwoundedDefence)
        assertEquals(listOf(MechanicalCondition("WOUND", 15), MechanicalCondition("EXPOSED:PATHOGEN1", 1)), projected.conditions)
        assertEquals(AggregateMechanicalPopulation(5, 1, 2, 2), projected.aggregatePopulation)
        assertEquals(4L, original.stateVersion)
        assertEquals(AggregateMechanicalPopulation(5, 3, 1, 1), original.aggregatePopulation)
    }

    @Test fun speculativeBodyPreservesConditionOwnerRowsAndFailsClosedOnInvalidOrOverflowedProjection() {
        val original = body().copy(conditions = listOf(MechanicalCondition("EXPOSED", 3)))
        val duplicated = Phase64PopulationProductionReads.projectBody(original,
            listOf(ConditionChange(group, "EXPOSED", ConditionOperation.ADD)))!!
        assertEquals(listOf(MechanicalCondition("EXPOSED", 3), MechanicalCondition("EXPOSED", 1)), duplicated.conditions)
        assertEquals(original.stateVersion, duplicated.stateVersion)
        assertTrue(Phase64PopulationProductionReads.projectBody(duplicated,
            listOf(ConditionChange(group, "EXPOSED", ConditionOperation.REMOVE)))!!.conditions.isEmpty())
        assertNull(Phase64PopulationProductionReads.projectBody(original,
            listOf(ConditionChange(group, "ABSENT", ConditionOperation.REMOVE))))
        assertNull(Phase64PopulationProductionReads.projectBody(original,
            listOf(ResourceChange(group, "STAMINA", ExactLongDelta.of(-11)))))
        assertNull(Phase64PopulationProductionReads.projectBody(original.copy(stateVersion = Long.MAX_VALUE),
            listOf(SpatialChange(group, 0, destinationLocation = destination))))
        val full = original.copy(resources = listOf(MechanicalResource("STAMINA", Long.MAX_VALUE, Long.MAX_VALUE)))
        assertNull(Phase64PopulationProductionReads.projectBody(full,
            listOf(ResourceChange(group, "STAMINA", ExactLongDelta.of(1)))))
    }

    @Test fun capturedAgingAccumulatesElapsedTimeAndDetectsOverflowWithoutInventingBirthDate() {
        val rule = definition("POPULATION", "AGE", emptyMap())
        val original = body(person, null)
        val staged = listOf<PlayerDomainChangePayload>(MechanicalTrackChange(person, Phase64PopulationOwnerPreparation.AGE_TRACK, ExactLongDelta.of(250)))
        val result = Phase64PopulationOwnerPreparation.age(original, rule, 500, "EVENT1", staged)
        assertEquals(listOf(MechanicalTrackChange(person, Phase64PopulationOwnerPreparation.AGE_TRACK, ExactLongDelta.of(1000))), result.changes)
        assertEquals("P64:AGING_OVERFLOW", Phase64PopulationOwnerPreparation.age(original, rule, Long.MAX_VALUE - 1, "EVENT1").reasonUid)
        assertEquals(4L, original.stateVersion)
        assertTrue(original.conditions.isEmpty())
    }

    @Test fun capturedDeathConsumesOnlyRemainingActiveAnonymousSlotsAndNeverTouchesNamedBodies() {
        val original = body()
        val rule = definition("POPULATION", "DEATH", emptyMap())
        val staged = listOf<PlayerDomainChangePayload>(AggregatePopulationChange(group, eliminatedDelta = 1, woundedDelta = 1))
        val result = Phase64PopulationOwnerPreparation.death(original, rule, 1, "EVENT1", staged)
        assertEquals(listOf(AggregatePopulationChange(group, eliminatedDelta = 1)), result.changes)
        assertEquals(listOf(WorldResourceClaim(group, 1)), result.claims)
        assertEquals("P64:ACTIVE_POPULATION_REQUIRED", Phase64PopulationOwnerPreparation.death(original, rule, 2, "EVENT2", staged).reasonUid)
        assertEquals(AggregateMechanicalPopulation(5, 3, 1, 1), original.aggregatePopulation)
        val named = body(person, null)
        val once = Phase64PopulationOwnerPreparation.death(named, rule, 1, "EVENT3")
        assertEquals("P64:DEATH_ALREADY_SETTLED", Phase64PopulationOwnerPreparation.death(named, rule, 1, "EVENT3", once.changes).reasonUid)
    }

    @Test fun capturedTravelMovesSameNamedMembersOnceAndConservesActualRouteResourceCost() {
        val original = body()
        val named = body(person, null)
        val rule = definition("POPULATION", "MIGRATE", emptyMap())
        val edge = WorldTopologyEdge("EDGE1", 1, original.locationRef!!, destination, ActionDuration(900), mapOf("STAMINA" to 2),
            setOf("TRAVEL"), WorldTimeTick(0), null, "TOPOLOGY-PROOF1")
        val route = WorldTravelPlan(original.locationRef!!, destination, listOf(edge))
        val result = Phase64PopulationOwnerPreparation.movement(original, rule, route, route.fingerprint, WorldTimeTick(0), WorldTimeTick(1000),
            "EVENT1", migratingCount = 5, namedMembers = listOf(named))
        assertEquals(listOf(ResourceChange(group, "STAMINA", ExactLongDelta.of(-2)), SpatialChange(group, 0, destinationLocation = destination),
            SpatialChange(person, 0, destinationLocation = destination)), result.changes)
        assertEquals(listOf(WorldResourceClaim(phase64MechanicalResource(group, "STAMINA"), 2)), result.claims)
        assertEquals("P64:KNOWN_ROUTE_REQUIRED", Phase64PopulationOwnerPreparation.movement(original, rule, route, "FOREIGN", WorldTimeTick(0), WorldTimeTick(1000), "EVENT2").reasonUid)
        assertEquals("P64:PARTIAL_MIGRATION_OWNER_REQUIRED", Phase64PopulationOwnerPreparation.movement(original, rule, route, route.fingerprint, WorldTimeTick(0), WorldTimeTick(1000),
            "EVENT2", migratingCount = 2).reasonUid)
        assertEquals("P64:TRAVEL_RESOURCE_REQUIRED", Phase64PopulationOwnerPreparation.movement(original, rule, route, route.fingerprint, WorldTimeTick(0), WorldTimeTick(1000),
            "EVENT2", staged = listOf(ResourceChange(group, "STAMINA", ExactLongDelta.of(-9)))).reasonUid)
        assertEquals("P1", original.locationRef?.uid)
        assertEquals("P1", named.locationRef?.uid)
    }

    @Test fun capturedExposureUsesExplicitConditionAndNeverCountsEliminatedPopulation() {
        val rule = definition("EPIDEMIC", "EXPOSURE", emptyMap())
        val named = body(person, null)
        val result = Phase64PopulationOwnerPreparation.exposure(named, rule, "EXPOSED:PATHOGEN1", 2, 2, "CONTACT1", "EVENT1")
        assertEquals(listOf(ConditionChange(person, "EXPOSED:PATHOGEN1", ConditionOperation.ADD)), result.changes)
        assertEquals("P64:EXPOSURE_ALREADY_SETTLED", Phase64PopulationOwnerPreparation.exposure(named, rule, "EXPOSED:PATHOGEN1", 2, 2,
            "CONTACT1", "EVENT1", staged = result.changes).reasonUid)
        val aggregate = body().copy(aggregatePopulation = AggregateMechanicalPopulation(5, 3, 1, 1, mapOf("EXPOSED:PATHOGEN1" to 2)))
        assertEquals("P64:EXPOSURE_POPULATION_REQUIRED", Phase64PopulationOwnerPreparation.exposure(aggregate, rule, "EXPOSED:PATHOGEN1", 2, 2,
            "CONTACT1", "EVENT2", affectedCount = 3).reasonUid)
        val coarse = Phase64PopulationOwnerPreparation.exposure(aggregate, rule, "EXPOSED:PATHOGEN1", 2, 2, "CONTACT1", "EVENT2", affectedCount = 2)
        assertEquals(listOf(AggregatePopulationChange(group, conditionUid = "EXPOSED:PATHOGEN1", conditionAffectedDelta = 2)), coarse.changes)
    }

    @Test fun birthCreatesFixedNewCohortWithNoAutomaticSkillsAndKeepsParentIdentity() {
        val original = body()
        val skeleton = CampaignWorldSkeleton.legacy("C1", CampaignRuleSource(CampaignRuleSourceKind.CAMPAIGN_NATIVE, "C1", "1"), "Era", original.locationRef!!)
        val manifest = WorldPopulationManifest(group, 6, skeleton.domainSeed("POPULATION", group.toString()))
        val rule = definition("POPULATION", "BIRTH", mapOf("cohort_rule_uid" to Phase64DemographyPreparation.NEWBORN_PROFILE,
            "cohort_rule_version" to "1", "max_birth_count" to "2"))
        val params = rule.parameters + mapOf("count" to "2", "p64_process_uid" to "PROCESS1", "p64_logical_event_uid" to "EVENT1")
        val result = Phase64DemographyPreparation.birth(original, manifest, skeleton, rule, params, scope)
        val birth = result.changes.filterIsInstance<PopulationCohortBirthChange>().single()
        assertEquals(2L, birth.cohort.originalCount)
        assertEquals(manifest.uid, birth.sourceManifestUid)
        assertNotEquals(manifest.aggregate, birth.cohort.aggregate)
        assertEquals(6L, manifest.originalCount)
        assertEquals(manifest.member(0), manifest.copy().member(0))
        val seed = Phase64DemographyPreparation.seed(birth, skeleton)
        assertEquals(2L, seed.aggregateCount)
        assertTrue(seed.abilities.isEmpty())
        assertTrue(seed.attributes.isEmpty())
        assertEquals(MechanicalStateMaterialization.PARTIAL, seed.materialization)
        assertEquals(birth, Phase64DemographyCodec.readBirth(Phase64DemographyCodec.birth(birth)))
        val rebranched = Phase64DemographyPreparation.birth(original, manifest, skeleton, rule, params,
            scope.copy(temporal = scope.temporal.copy(historyGenerationUid = "AFTER-UNDO"))).changes.filterIsInstance<PopulationCohortBirthChange>().single()
        assertEquals(birth.cohort, rebranched.cohort)
        assertEquals(seed, Phase64DemographyPreparation.seed(rebranched, skeleton))
        assertEquals("P64:COHORT_COUNT_POLICY_EXCEEDED", Phase64DemographyPreparation.birth(original, manifest, skeleton, rule, params + ("count" to "3"), scope).reasonUid)
    }

    @Test fun mobilizationCommitsWholeExistingUnitAndDoesNotDuplicateOrReclassifyItsMembers() {
        val original = body(formation)
        val manifest = WorldPopulationManifest(formation, 5, phase63Hash("FORMATION-SEED"))
        val rule = definition("CONFLICT", "MOBILIZE", emptyMap())
        val params = mapOf("count" to "3", "p64_process_uid" to "PROCESS1", "p64_logical_event_uid" to "EVENT1")
        val result = Phase64DemographyPreparation.mobilize(original, manifest, rule, params, scope, false)
        val commitment = result.changes.filterIsInstance<FormationMobilizationChange>().single()
        assertEquals(3L, commitment.mobilizedCount)
        assertEquals(formation, commitment.formation)
        assertEquals(listOf(WorldResourceClaim(formation, 3)), result.claims)
        assertEquals(commitment, Phase64DemographyCodec.readMobilization(Phase64DemographyCodec.mobilization(commitment)))
        assertEquals("P64:FORMATION_ALREADY_MOBILIZED", Phase64DemographyPreparation.mobilize(original, manifest, rule, params, scope, true).reasonUid)
        assertEquals("P64:PARTIAL_MOBILIZATION_OWNER_REQUIRED", Phase64DemographyPreparation.mobilize(original, manifest, rule, params + ("count" to "2"), scope, false).reasonUid)
        assertEquals(AggregateMechanicalPopulation(5, 3, 1, 1), original.aggregatePopulation)
        assertEquals(5L, manifest.originalCount)
    }

    @Test fun partialMigrationAndRecruitmentRequireSlotTransferOwnerAndProduceNoCandidates() {
        val original = body()
        val named = body(person, null)
        val manifest = WorldPopulationManifest(group, 6, phase63Hash("GROUP-SEED"))
        val edge = WorldTopologyEdge("EDGE1", 1, original.locationRef!!, destination, ActionDuration(900),
            mapOf("STAMINA" to 2), setOf("TRAVEL"), WorldTimeTick(0), null, "TOPOLOGY-PROOF1")
        val route = WorldTravelPlan(original.locationRef!!, destination, listOf(edge))
        val partial = Phase64PopulationOwnerPreparation.movement(original, definition("POPULATION", "MIGRATE", emptyMap()),
            route, route.fingerprint, WorldTimeTick(0), WorldTimeTick(1000), "EVENT1", migratingCount = 2, namedMembers = listOf(named))
        assertEquals("P64:PARTIAL_MIGRATION_OWNER_REQUIRED", partial.reasonUid)
        assertTrue(partial.changes.isEmpty())
        assertTrue(partial.claims.isEmpty())
        val recruitment = Phase64DemographyPreparation.mobilize(original, manifest, definition("CONFLICT", "MOBILIZE", emptyMap()),
            mapOf("count" to "2", "formation_kind_uid" to formation.kindUid, "formation_uid" to formation.uid,
                "p64_process_uid" to "PROCESS1", "p64_logical_event_uid" to "EVENT2"), scope, false)
        assertEquals("P64:WHOLE_EXISTING_FORMATION_REQUIRED", recruitment.reasonUid)
        assertTrue(recruitment.changes.isEmpty())
        assertTrue(recruitment.claims.isEmpty())
        assertEquals(10L, original.resources.single().current)
        assertEquals(group, manifest.aggregate)
        assertEquals(6L, manifest.originalCount)
        assertEquals(manifest.member(0), manifest.copy().member(0))
    }

    @Test fun mechanicalCapacityKeysPreserveHolderIdentityWithDelimiterContainingUids() {
        val holder = DomainRef("GROUP", "G1|:G2")
        val capacity = phase64MechanicalResource(holder, "POOL:|2")
        assertEquals(Phase64MechanicalResourceHolding(holder, "POOL:|2"), phase64MechanicalResourceHolding(capacity))
        assertNull(phase64MechanicalResourceHolding(DomainRef("MECHANICAL_RESOURCE", "4:BAD")))
        assertNotEquals(capacity, phase64MechanicalResource(DomainRef("GROUP", "G1"), "|:G2POOL:|2"))
    }

    @Test fun revokedStagedAuthorizationStopsLaterProcessBeforeOwnerReads() {
        val reads = Reads().apply { denyWithStagedChanges = true }
        val staged = listOf<PlayerDomainChangePayload>(ConditionChange(group, "ACCESS_REVOKED", ConditionOperation.ADD))
        val def = definition("CONFLICT", "MOVE", destinationParameters())
        assertEquals("P64:PROCESS_NOT_AUTHORIZED", evaluate(def, reads, staged = staged).reasonUid)
        assertSame(staged, reads.authorizationStaged.single())
        assertTrue(reads.routedActors.isEmpty())
        assertTrue(reads.preparations.isEmpty())
    }

    @Test fun travelDoesNotRestoreNamedMembersPreviousPlaceAfterStagedMoveOrUnknownCoordinates() {
        val original = body()
        val named = body(person, null)
        val rule = definition("CONFLICT", "MOVE", emptyMap())
        val edge = WorldTopologyEdge("EDGE1", 1, original.locationRef!!, destination, ActionDuration(900), emptyMap(),
            setOf("TRAVEL"), WorldTimeTick(0), null, "TOPOLOGY-PROOF1")
        val route = WorldTravelPlan(original.locationRef!!, destination, listOf(edge))
        val moved = Phase64PopulationOwnerPreparation.movement(original, rule, route, route.fingerprint, WorldTimeTick(0), WorldTimeTick(1000), "EVENT1",
            namedMembers = listOf(named), staged = listOf(SpatialChange(person, 0, destinationLocation = DomainRef("PLACE", "FOREIGN"))))
        assertEquals("P64:TRAVEL_ORIGIN_CHANGED", moved.reasonUid)
        val unknown = Phase64PopulationOwnerPreparation.movement(original, rule, route, route.fingerprint, WorldTimeTick(0), WorldTimeTick(1000), "EVENT2",
            namedMembers = listOf(named.copy(locationRef = null)), staged = listOf(SpatialChange(person, 50)))
        assertEquals("P64:TRAVEL_ORIGIN_CHANGED", unknown.reasonUid)
        assertTrue(moved.changes.isEmpty())
        assertTrue(unknown.changes.isEmpty())
    }

    private fun combatAuthorization(actor: DomainRef, target: DomainRef, contract: CombatAbilityContract,
        currentScope: BackgroundProcessEvaluationScope = scope): NpcActionAuthorization {
        val brain = NpcBrainOwner.initialize("C1", actor, "seed")
        val npcScope = NpcDecisionScope(currentScope.temporal, actor, brain.revision, WorldTimeTick(1000), 0, "ACTIVE-PC")
        val knowledge = NpcKnownRecord("KNOWN-TARGET", KnowledgeEpistemicState.KNOWN, "Perceived target", "ACQ1", 1, setOf(target))
        val trigger = NpcTrigger("TRIGGER1", NpcTriggerKind.PERCEIVED_ACTION, WorldTimeTick(1000), NpcCauseRef(NpcCauseKind.KNOWLEDGE_ACQUISITION, "ACQ1"))
        val option = NpcActionOption("OPTION1", contract.abilityUid, target, AcceptedActionTiming(ActionDuration(1000), "COMBAT-TIME", 1),
            null, emptyList(), setOf(knowledge.uid), resourceCosts = contract.resourceUid?.let { mapOf(it to contract.resourceCost) }.orEmpty(),
            parameters = mapOf("npc_ability_contract" to npcCombatContractFingerprint(contract)),
            mechanicsOwnerUid = "UNIVERSAL_COMBAT", mechanicalEffectKindUid = contract.effectKinds.first().name)
        return NpcActionAuthorization.issue(NpcDecisionContextEnvelope(npcScope, trigger, brain, listOf(knowledge), listOf(option), 4096), option)
    }

    private fun combatParameters(authorization: NpcActionAuthorization, target: DomainRef, abilityUid: String) = mapOf(
        "decision_uid" to authorization.decisionUid, "ability_uid" to abilityUid, "target_kind_uid" to target.kindUid, "target_uid" to target.uid,
        "p64_process_uid" to "COMBAT-PROCESS1", "p64_logical_event_uid" to "COMBAT-EVENT1")

    private fun combatCapture(actor: DomainRef, target: DomainRef) = Phase64CombatCapture(CombatSpatialState(mapOf(
        actor to CombatPosition.Exact(0, 0), target to CombatPosition.Exact(1000, 0))))

    private data class CombatCompletionFixture(val brain: NpcBrainState, val pending: NpcPendingAction,
        val finished: NpcBrainChange, val input: TemporalOwnerInput, val parameters: Map<String, String>)

    private fun completedCombatFixture(): CombatCompletionFixture {
        val contract = phase60Hash("REGISTERED-ATTACK-CONTRACT")
        val cause = NpcCauseRef(NpcCauseKind.ACCEPTED_ACTION, "START-COMMAND")
        val initial = NpcBrainOwner.initialize("C1", person, "seed")
        val goal = NpcGoal("COMBAT-GOAL", initial.motivations.first().uid, "Defend the group", NpcWeight(5000), NpcGoalLifecycle.ACTIVE, cause)
        val optionUid = "P62:OPTION:${phase60Hash("$person|${goal.uid}|ATTACK|$formation|$contract").take(32)}"
        val plan = NpcPlan("P62:DECISION:ORIGINAL-PLAN", goal.uid, optionUid, NpcPlanLifecycle.RUNNING,
            WorldTimeTick(0), WorldTimeTick(1000), cause)
        val brain = initial.copy(revision = 3, goals = listOf(goal), plans = listOf(plan))
        val completionCause = NpcCauseRef(NpcCauseKind.ACCEPTED_ACTION, "COMPLETE-COMMAND")
        val after = brain.copy(revision = 4, plans = listOf(plan.copy(lifecycle = NpcPlanLifecycle.COMPLETED,
            nextEvaluationAt = null, cause = completionCause)))
        val finished = NpcBrainChange("C1", person, "H1", brain.revision, NpcBrainCodec.fingerprint(brain), NpcBrainCodec.encode(after),
            NpcBrainRules.PLANNING.uid, NpcBrainRules.PLANNING.version, listOf(completionCause))
        val pending = NpcPendingAction(person, plan.uid, optionUid, WorldTimeTick(0), WorldTimeTick(1000), Phase60CombatTime.RULE, 1)
        val markers = mapOf("npc_plan_uid" to plan.uid, "npc_option_uid" to optionUid,
            "npc_started_at_ms" to "0", "npc_due_at_ms" to "1000", "npc_ability_uid" to "ATTACK",
            "npc_target_kind_uid" to formation.kindUid, "npc_target_uid" to formation.uid, "npc_ability_contract" to contract,
            "source_actor_kind_uid" to person.kindUid, "source_actor_uid" to person.uid)
        val proof = "P60:PROCESS:${phase60Hash("COMPLETION")}:RPGOS-P50-PROOF:${phase60Hash("IMPACT")}"
        val impact = VerifiedMechanicsCommandEffect("COMPLETED-IMPACT", "NPC-COMBAT-NODE", "UNIVERSAL_COMBAT", "WOUND", formation, 2,
            markers + mapOf("combat_proof_uid" to "ORIGINAL-COMBAT-PROOF"), proof, "INPUT1", "OUTPUT1")
        val debit = VerifiedMechanicsCommandEffect("COMPLETED-COST", "NPC-COMBAT-NODE", "UNIVERSAL_COMBAT", "RESOURCE_DELTA", person, -2,
            markers + mapOf("resource_uid" to "STAMINA"), "$proof:COST", "INPUT1", "OUTPUT-COST")
        val effects = listOf(impact, debit)
        val staged = listOf(finished) + effects.flatMap { effect ->
            (MechanicalEffectMaterializer.materialize(effect) as MechanicalEffectMaterializationResult.Materialized).changes.map { it.payload }
        }
        val input = TemporalOwnerInput(scope.temporal, WorldTimeTick(0), WorldTimeTick(1000), emptyList(), emptyList(), null,
            staged, effects, mapOf(NpcActionProcess.OWNER to TemporalOwnerState(NpcActionProcess.OWNER, 1, NpcActionProcess.encode(emptyList()))))
        val parameters = mapOf("decision_uid" to plan.uid, "ability_uid" to "ATTACK", "target_kind_uid" to formation.kindUid,
            "target_uid" to formation.uid, "p64_event_at_ms" to "1000", "p64_logical_event_uid" to "COMBAT-EVENT1")
        return CombatCompletionFixture(brain, pending, finished, input, parameters)
    }

    private fun acknowledgeCombat(fixture: CombatCompletionFixture, input: TemporalOwnerInput = fixture.input,
        parameters: Map<String, String> = fixture.parameters, currentScope: BackgroundProcessEvaluationScope = scope,
        brain: NpcBrainState = fixture.brain) = Phase64CombatCompletionPreparation.prepare(person,
        definition("CONFLICT", "COMBAT", emptyMap()), parameters, currentScope, brain, "ACTIVE-PC", input, input.stagedChanges)

    @Test fun durableCombatAcknowledgesOriginalPlanProofsWithoutReapplyingImpactOrCost() {
        val fixture = completedCombatFixture()
        val result = acknowledgeCombat(fixture)
        assertEquals(BackgroundProcessStatus.COMPLETED, result.status)
        assertTrue(result.changes.isEmpty())
        assertTrue(result.effects.isEmpty())
        assertTrue(result.claims.isEmpty())
        assertEquals(1L, result.progressUnits)
        assertEquals(listOf(Phase64BackgroundCodec.fingerprint(fixture.finished)) + fixture.input.stagedEffects.sortedBy { it.effectUid }.flatMap { effect ->
            (MechanicalEffectMaterializer.materialize(effect) as MechanicalEffectMaterializationResult.Materialized).changes.map { Phase64BackgroundCodec.fingerprint(it.payload) }
        }, result.existingConsequenceFingerprints)
        assertTrue(fixture.pending.planUid in result.sourceUids)
        assertTrue("ORIGINAL-COMBAT-PROOF" in result.sourceUids)
        assertTrue(fixture.input.stagedEffects.all { it.proofUid in result.sourceUids })
        assertEquals(NpcPlanLifecycle.RUNNING, fixture.brain.plans.single().lifecycle)
        val replay = acknowledgeCombat(fixture)
        assertEquals(result, replay)
        val reordered = acknowledgeCombat(fixture, fixture.input.copy(stagedEffects = fixture.input.stagedEffects.reversed()))
        assertEquals(result, reordered)
    }

    @Test fun durableCombatCannotAcknowledgePendingInterruptedOrOldTerminalPlan() {
        val fixture = completedCombatFixture()
        val retained = fixture.input.copy(peerStates = mapOf(NpcActionProcess.OWNER to TemporalOwnerState(NpcActionProcess.OWNER, 1,
            NpcActionProcess.encode(listOf(fixture.pending)))))
        assertEquals("P64:NPC_COMBAT_PLAN_NOT_CONSUMED", acknowledgeCombat(fixture, retained).reasonUid)
        val old = NpcBrainCodec.decode(fixture.finished.stateCanonical)
        assertEquals("P64:NPC_COMBAT_COMPLETION_REQUIRED", acknowledgeCombat(fixture,
            fixture.input.copy(stagedChanges = fixture.input.stagedChanges.drop(1)), brain = old).reasonUid)
        val state = NpcBrainCodec.decode(fixture.finished.stateCanonical)
        val interrupted = fixture.finished.copy(stateCanonical = NpcBrainCodec.encode(state.copy(plans = listOf(
            state.plans.single().copy(lifecycle = NpcPlanLifecycle.INTERRUPTED)))))
        assertEquals("P64:NPC_COMBAT_COMPLETION_REQUIRED", acknowledgeCombat(fixture,
            fixture.input.copy(stagedChanges = listOf(interrupted) + fixture.input.stagedChanges.drop(1))).reasonUid)
    }

    @Test fun durableCombatRequiresExactCurrentPrefixScopeAndSavedOptionBinding() {
        val fixture = completedCombatFixture()
        assertEquals("P64:NPC_COMBAT_COMPLETION_SCOPE", acknowledgeCombat(fixture,
            currentScope = scope.copy(temporal = scope.temporal.copy(historyGenerationUid = "AFTER-UNDO"))).reasonUid)
        assertEquals("P64:NPC_COMBAT_OPTION_BINDING", acknowledgeCombat(fixture,
            parameters = fixture.parameters + ("ability_uid" to "OTHER-ATTACK")).reasonUid)
        assertEquals("P64:NPC_COMBAT_COMPLETION_TIME", acknowledgeCombat(fixture,
            parameters = fixture.parameters + ("p64_event_at_ms" to "999")).reasonUid)
        val effect = fixture.input.stagedEffects.first()
        val forged = effect.copy(canonicalPayload = effect.canonicalPayload + ("npc_due_at_ms" to "999"))
        assertEquals("P64:NPC_COMBAT_EFFECT_BINDING", acknowledgeCombat(fixture,
            fixture.input.copy(stagedEffects = listOf(forged) + fixture.input.stagedEffects.drop(1))).reasonUid)
        val missingProof = effect.copy(canonicalPayload = effect.canonicalPayload - "combat_proof_uid")
        assertEquals("P64:NPC_COMBAT_EFFECT_BINDING", acknowledgeCombat(fixture,
            fixture.input.copy(stagedEffects = listOf(missingProof) + fixture.input.stagedEffects.drop(1))).reasonUid)
        assertEquals("P64:NPC_COMBAT_EFFECT_PREFIX_REQUIRED", acknowledgeCombat(fixture,
            fixture.input.copy(stagedChanges = listOf(fixture.finished))).reasonUid)
        assertEquals("P64:NPC_COMBAT_EFFECTS_REQUIRED", acknowledgeCombat(fixture,
            fixture.input.copy(stagedEffects = emptyList())).reasonUid)
    }

    @Test fun combatAdapterAcceptsOnlyOwnerBoundExistingConsequencesForEmptyAcknowledgment() {
        val fixture = completedCombatFixture()
        val rule = definition("CONFLICT", "COMBAT", fixture.parameters)
        val reads = Reads().apply { owned = acknowledgeCombat(fixture) }
        val result = evaluate(rule, reads, person)
        assertEquals(BackgroundProcessStatus.COMPLETED, result.status)
        assertTrue(result.changes.isEmpty())
        assertTrue(result.effects.isEmpty())
        assertEquals(reads.owned.existingConsequenceFingerprints, result.existingConsequenceFingerprints)
        reads.owned = reads.owned.copy(existingConsequenceFingerprints = emptyList())
        assertEquals("P64:OWNED_RESULT_REQUIRED:CONFLICT_COMBAT", evaluate(rule, reads, person).reasonUid)
    }

    @Test fun existingNpcAuthorizationBuildsPhase50RequestAndSettlesCapturedCostExactlyOnce() {
        val actor = body(person, null).copy(attributes = mapOf("POWER" to 1000, "SKILL" to 1000, "DEFENCE" to 30, "AGILITY" to 30),
            executableAbilityUids = setOf("ATTACK"))
        val target = body(DomainRef("ACTOR", "DEFENDER1"), null)
        val contract = CombatAbilityContract("ATTACK", resourceUid = "STAMINA", resourceCost = 2)
        val auth = combatAuthorization(actor.actor, target.actor, contract)
        val parameters = combatParameters(auth, target.actor, contract.abilityUid)
        val rule = definition("CONFLICT", "COMBAT", emptyMap())
        val prepared = Phase64CombatOwnerPreparation.prepare(actor, listOf(target), rule, parameters, scope, auth,
            CombatAbilityContractPort.registered(listOf(contract)), combatCapture(actor.actor, target.actor)) as Phase64CombatRequestPreparation.Prepared
        assertSame(auth, prepared.request.intent.npcControlAuthorization)
        assertEquals(VolitionalActionSource.NPC_DECISION_ENGINE, prepared.request.intent.source)
        val result = Phase64CombatOwnerPreparation.settle(prepared.request, rule, parameters, scope)
        assertEquals(BackgroundProcessStatus.COMPLETED, result.status)
        assertNull(result.reasonUid)
        assertEquals(listOf(ResourceChange(actor.actor, "STAMINA", ExactLongDelta.of(-2))), result.changes)
        assertEquals(listOf(WorldResourceClaim(phase64MechanicalResource(actor.actor, "STAMINA"), 2)), result.claims)
        assertTrue(result.effects.isNotEmpty())
        assertTrue(result.effects.all { it.target == target.actor && it.mechanicsOwnerUid == "UNIVERSAL_COMBAT" })
        assertTrue(auth.decisionUid in result.sourceUids)
        assertEquals(10L, actor.resources.single().current)
    }

    @Test fun combatRejectsStaleDecisionChangedContractAndActivePlayerWithoutResolving() {
        val actor = body(person, null).copy(executableAbilityUids = setOf("ATTACK"))
        val target = body(DomainRef("ACTOR", "DEFENDER1"), null)
        val contract = CombatAbilityContract("ATTACK", resourceUid = "STAMINA", resourceCost = 2)
        val auth = combatAuthorization(actor.actor, target.actor, contract)
        val parameters = combatParameters(auth, target.actor, contract.abilityUid)
        val rule = definition("CONFLICT", "COMBAT", emptyMap())
        fun prepare(currentActor: MechanicalActorView = actor, currentScope: BackgroundProcessEvaluationScope = scope,
            contracts: CombatAbilityContractPort = CombatAbilityContractPort.registered(listOf(contract))) =
            Phase64CombatOwnerPreparation.prepare(currentActor, listOf(target), rule, parameters, currentScope, auth, contracts, combatCapture(actor.actor, target.actor))
        assertEquals("P64:NPC_COMBAT_DECISION_CHANGED", (prepare(currentScope = scope.copy(temporal = scope.temporal.copy(historyGenerationUid = "AFTER-UNDO")))
            as Phase64CombatRequestPreparation.Unavailable).reasonUid)
        assertEquals("P64:COMBAT_CONTRACT_CHANGED", (prepare(contracts = CombatAbilityContractPort.registered(listOf(contract.copy(resourceCost = 3))))
            as Phase64CombatRequestPreparation.Unavailable).reasonUid)
        assertEquals("P64:ACTIVE_PLAYER_CONTROL_FORBIDDEN", (prepare(currentActor = actor.copy(kind = MechanicalActorKind.ACTIVE_PLAYER))
            as Phase64CombatRequestPreparation.Unavailable).reasonUid)
        assertEquals("P64:COMBAT_ACTOR_DEAD", (prepare(currentActor = actor.copy(conditions = listOf(MechanicalCondition("DEAD", 1))))
            as Phase64CombatRequestPreparation.Unavailable).reasonUid)
    }

    @Test fun missingCombatContractPositionsOrDuplicateBodyCannotManufactureCombat() {
        val actor = body(person, null).copy(executableAbilityUids = setOf("REGISTERED-BLAST"))
        val target = body(DomainRef("ACTOR", "DEFENDER1"), null)
        val contract = CombatAbilityContract("REGISTERED-BLAST")
        val auth = combatAuthorization(actor.actor, target.actor, contract)
        val parameters = combatParameters(auth, target.actor, contract.abilityUid)
        val rule = definition("CONFLICT", "COMBAT", emptyMap())
        fun prepare(targets: List<MechanicalActorView> = listOf(target), capture: Phase64CombatCapture = combatCapture(actor.actor, target.actor),
            contracts: CombatAbilityContractPort = CombatAbilityContractPort.registered(listOf(contract))) =
            Phase64CombatOwnerPreparation.prepare(actor, targets, rule, parameters, scope, auth, contracts, capture)
        assertEquals("P64:REGISTERED_COMBAT_CONTRACT_REQUIRED", (prepare(contracts = CombatAbilityContractPort.UNIVERSAL_FALLBACK)
            as Phase64CombatRequestPreparation.Unavailable).reasonUid)
        assertEquals("P64:COMBAT_SPATIAL_CAPTURE_REQUIRED", (prepare(capture = Phase64CombatCapture(CombatSpatialState(emptyMap())))
            as Phase64CombatRequestPreparation.Unavailable).reasonUid)
        assertEquals("P64:COMBAT_PARTICIPANT_CAPTURE_INVALID", (prepare(targets = listOf(target, target))
            as Phase64CombatRequestPreparation.Unavailable).reasonUid)
    }

    @Test fun combatCaptureOrderIsCanonicalAndBothSidesRemainExistingBodies() {
        val actor = body(person, null).copy(executableAbilityUids = setOf("REGISTERED-BLAST"))
        val target = body(DomainRef("ACTOR", "DEFENDER1"), null)
        val named = body(DomainRef("ACTOR", "NAMED-DEFENDER2"), null)
        val contract = CombatAbilityContract("REGISTERED-BLAST", areaRadiusMillimetres = 2000, maximumTargets = 2)
        val auth = combatAuthorization(actor.actor, target.actor, contract)
        val parameters = combatParameters(auth, target.actor, contract.abilityUid)
        val rule = definition("CONFLICT", "COMBAT", emptyMap())
        val positions = mapOf(actor.actor to CombatPosition.Exact(0, 0), target.actor to CombatPosition.Exact(1000, 0), named.actor to CombatPosition.Exact(1100, 0))
        val contracts = CombatAbilityContractPort.registered(listOf(contract))
        fun prepare(targets: List<MechanicalActorView>, spatial: Map<DomainRef, CombatPosition>) =
            Phase64CombatOwnerPreparation.prepare(actor, targets, rule, parameters, scope, auth, contracts,
                Phase64CombatCapture(CombatSpatialState(spatial))) as Phase64CombatRequestPreparation.Prepared
        val first = prepare(listOf(target, named), positions).request
        val reordered = prepare(listOf(named, target), positions.entries.reversed().associate { it.toPair() }).request
        assertEquals(first, reordered)
        assertEquals(setOf(actor.actor, target.actor, named.actor), first.snapshot.actors.map { it.actor }.toSet())
        assertEquals(first.snapshot.fingerprint, reordered.snapshot.fingerprint)
    }

    @Test fun woundedAnonymousPoolCannotBeEliminatedAgainThroughActiveOnlyOwner() {
        val wounded = body().copy(aggregatePopulation = AggregateMechanicalPopulation(5, 0, 4, 1))
        val result = Phase64PopulationOwnerPreparation.death(wounded, definition("POPULATION", "DEATH", emptyMap()), 1, "EVENT1")
        assertEquals("P64:ACTIVE_POPULATION_REQUIRED", result.reasonUid)
        assertTrue(result.changes.isEmpty())
        assertTrue(result.claims.isEmpty())
        assertEquals(AggregateMechanicalPopulation(5, 0, 4, 1), wounded.aggregatePopulation)
    }
}
