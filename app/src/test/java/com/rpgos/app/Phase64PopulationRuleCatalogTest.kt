package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase64PopulationRuleCatalogTest {
    private val principal = DomainRef("ACTOR", "LEADER")
    private val group = DomainRef("GROUP", "COHORT")
    private val unit = DomainRef("UNIT", "FORMATION")
    private val origin = DomainRef("PLACE", "ORIGIN")
    private val destination = DomainRef("PLACE", "DESTINATION")
    private val scope = BackgroundProcessEvaluationScope(TemporalScope("C1", "H1", 7, "digest"), "seed", "rules")
    private fun definition(operation: String) = Phase64PopulationRuleCatalog.definitions().single { it.operation == operation }
    private fun body(ref: DomainRef = group, population: AggregateMechanicalPopulation? = AggregateMechanicalPopulation(5, 3, 1, 1)) =
        MechanicalActorView("C1", ref, if (population == null) MechanicalActorKind.NPC else if (ref.kindUid == "UNIT") MechanicalActorKind.UNIT else MechanicalActorKind.GROUP,
            4, MechanicalStateMaterialization.FULL, mapOf("DEFENCE" to 10), listOf(MechanicalResource("HEALTH", 10, 10), MechanicalResource("STAMINA", 10, 20)),
            setOf("TRAVEL", Phase64PopulationRuleCatalog.REPRODUCTION_ABILITY), equipmentRefs = listOf(DomainRef("ITEM_INSTANCE", "EXISTING-ARMOR")),
            locationRef = origin, generationProvenanceUid = "ORIGINAL-BODY", aggregatePopulation = population)
    private fun snapshot(ref: DomainRef = group) = Phase64PopulationActivitySnapshot(body(ref), WorldPopulationManifest(ref, 5, phase63Hash("manifest")))
    private fun brain(): NpcBrainState {
        val initial = NpcBrainOwner.initialize("C1", principal, "seed")
        val motivation = initial.motivations.first()
        return initial.copy(goals = listOf(NpcGoal("GOAL", motivation.uid, "Care for the community", NpcWeight(5000), NpcGoalLifecycle.ACTIVE,
            NpcCauseRef(NpcCauseKind.INTRINSIC_MOTIVATION, motivation.uid))))
    }
    private fun record(vararg refs: DomainRef) = NpcKnownRecord("KNOWN", KnowledgeEpistemicState.BELIEVED, "Personally perceived subjects",
        "ACQUISITION", 1, refs.toSet())
    private fun actor(definition: BackgroundProcessDefinition) = body(principal, null).copy(
        executableAbilityUids = body(principal, null).executableAbilityUids + definition.parameters.getValue(Phase64ProcessActivation.ACTION_KEY))
    private fun options(definition: BackgroundProcessDefinition, snapshot: Phase64PopulationActivitySnapshot?, records: List<NpcKnownRecord> = listOf(record(group)),
        actor: MechanicalActorView = actor(definition)) = Phase64PopulationNpcOptions.options(brain(), records, actor, definition) { _, _ -> snapshot }
    private fun route(version: Long = 1) = WorldTravelPlan(origin, destination, listOf(WorldTopologyEdge("EDGE", version, origin, destination,
        ActionDuration(1000), mapOf("STAMINA" to 2), setOf("TRAVEL"), WorldTimeTick(0), null, "REGISTERED-ROUTE")))

    @Test fun neutralCoreRulesAreVersionedOptInAndDoNotStartWarsOrInventDisease() {
        val rules = Phase64PopulationRuleCatalog.definitions()
        assertEquals(setOf("AGE", "BIRTH", "DEATH", "MOBILIZE"), rules.map { it.operation }.toSet())
        assertTrue(rules.all { it.version == 2 && Phase64PopulationRuleCatalog.matches(it) })
        assertTrue(rules.all { it.parameters["activation_npc"] == "true" && it.parameters[Phase64ProcessActivation.PUBLIC_KEY] == "false" })
        assertTrue(rules.all { it.parameters["activation_policy_uid"]?.isNotBlank() == true })
        assertEquals("1", definition("BIRTH").parameters["max_birth_count"])
        assertEquals(Phase64PopulationRuleCatalog.WHOLE_ACTIVE, definition("MOBILIZE").parameters["count"])
        assertEquals(Phase64PopulationRuleCatalog.MORTALITY_POLICY, definition("DEATH").parameters["cause_rule_uid"])
        assertTrue(rules.none { it.operation in setOf("COMBAT", "EXPOSURE") })
        assertNotEquals(Phase64PopulationRuleCatalog.supplyResource("STAMINA").parameters[Phase64ProcessActivation.ACTION_KEY],
            Phase64PopulationRuleCatalog.supplyResource("HEALTH").parameters[Phase64ProcessActivation.ACTION_KEY])
    }

    @Test fun npcOptionRequiresMaterializedCapabilityProtectedSubjectAndActualLineageCapture() {
        val age = definition("AGE")
        val captured = snapshot()
        assertEquals(group, options(age, captured).single().target)
        assertTrue(options(age, captured, actor = actor(age).copy(executableAbilityUids = emptySet())).isEmpty())
        assertTrue(options(age, captured, records = emptyList()).isEmpty())
        assertTrue(options(age, null).isEmpty())
        assertTrue(options(age, captured.copy(manifest = null)).isEmpty())
        assertTrue(options(age, captured.copy(namedCount = 1)).isEmpty())
        assertTrue(options(age, captured.copy(ageElapsedMillis = Long.MAX_VALUE)).isEmpty())
        assertEquals(principal, brain().actor)
        assertEquals(group, captured.body.actor)
    }

    @Test fun birthNeedsAnExistingReproductiveAdultCohortAndDeathNeedsCurrentMechanicalMortality() {
        val captured = snapshot()
        assertEquals(1, options(definition("BIRTH"), captured).size)
        assertTrue(options(definition("BIRTH"), captured.copy(body = captured.body.copy(executableAbilityUids = setOf("TRAVEL")))).isEmpty())
        assertTrue(options(definition("BIRTH"), captured.copy(body = captured.body.copy(
            generationProvenanceUid = "${Phase64DemographyPreparation.NEWBORN_PROFILE}:EVENT"))).isEmpty())
        assertTrue(options(definition("DEATH"), captured).isEmpty())
        val mortallyDepleted = captured.copy(body = captured.body.copy(resources = listOf(MechanicalResource("HEALTH", 0, 10))))
        assertEquals(1, options(definition("DEATH"), mortallyDepleted).size)
        assertTrue(options(definition("DEATH"), mortallyDepleted.copy(body = mortallyDepleted.body.copy(conditions = listOf(MechanicalCondition("DEAD", 1))))).isEmpty())
    }

    @Test fun wholeMobilizationReservesExistingActiveAnonymousSlotsWithoutCreatingBodiesOrEquipment() {
        val definition = definition("MOBILIZE")
        val captured = snapshot(unit)
        assertEquals(1, options(definition, captured, listOf(record(unit))).size)
        assertTrue(options(definition, captured.copy(alreadyMobilized = true), listOf(record(unit))).isEmpty())
        val parameters = Phase64ProcessActivation.bind(definition, principal, unit) + mapOf("count" to "3", "p64_process_uid" to "PROCESS", "p64_logical_event_uid" to "EVENT")
        val prepared = Phase64DemographyPreparation.mobilize(captured.body, requireNotNull(captured.manifest), definition, parameters, scope, false)
        val commitment = prepared.changes.single() as FormationMobilizationChange
        assertEquals(unit, commitment.formation)
        assertEquals(3L, commitment.mobilizedCount)
        assertEquals(captured.body.stateVersion, commitment.expectedBodyVersion)
        assertEquals(listOf(WorldResourceClaim(unit, 3)), prepared.claims)
        assertEquals(listOf(DomainRef("ITEM_INSTANCE", "EXISTING-ARMOR")), captured.body.equipmentRefs)
        assertEquals(AggregateMechanicalPopulation(5, 3, 1, 1), captured.body.aggregatePopulation)
    }

    @Test fun fixedWholeCohortMovementKeepsNamedIdentitiesCasualtiesAndActualResourcePools() {
        val named = listOf(body(DomainRef("ACTOR", "NAMED1"), null), body(DomainRef("ACTOR", "NAMED2"), null))
        val captured = snapshot().copy(manifest = WorldPopulationManifest(group, 7, phase63Hash("manifest")), namedCount = 2,
            route = route(), namedMembers = named)
        val definition = Phase64PopulationRuleCatalog.cohortMovement(captured, route())
        assertEquals(1, options(definition, captured, listOf(record(group, destination))).size)
        assertTrue(options(definition, captured, listOf(record(destination))).isEmpty())
        assertTrue(options(definition, captured.copy(route = route(2)), listOf(record(group, destination))).isEmpty())
        val prepared = Phase64PopulationOwnerPreparation.movement(captured.body, definition, route(), route().fingerprint,
            WorldTimeTick(0), WorldTimeTick(1000), "EVENT", 6, named)
        assertEquals(BackgroundProcessStatus.COMPLETED, prepared.status)
        assertEquals(listOf(group) + named.map { it.actor }, prepared.changes.filterIsInstance<SpatialChange>().map { it.subject })
        assertEquals(-2L, prepared.changes.filterIsInstance<ResourceChange>().single().delta.units)
        assertTrue(prepared.changes.none { it is AggregatePopulationChange || it is PopulationCohortBirthChange || it is FormationMobilizationChange })
        assertEquals(1L, captured.body.aggregatePopulation!!.eliminatedCount)
        assertEquals(1L, captured.body.aggregatePopulation!!.woundedCount)
        assertEquals(10L, captured.body.resources.single { it.resourceUid == "STAMINA" }.current)
        val partial = Phase64PopulationOwnerPreparation.movement(captured.body, definition, route(), route().fingerprint,
            WorldTimeTick(0), WorldTimeTick(1000), "EVENT", 2, named)
        assertEquals(BackgroundProcessStatus.BLOCKED, partial.status)
        assertEquals("P64:PARTIAL_MIGRATION_OWNER_REQUIRED", partial.reasonUid)
    }

    @Test fun namedSelfMigrationAndSupplyUseExistingBodiesAndCapturedStockOnly() {
        val named = body(principal, null)
        val migration = Phase64PopulationRuleCatalog.namedMigration(named, route())
        assertEquals(1, options(migration, Phase64PopulationActivitySnapshot(named, route = route()), listOf(record(destination))).size)
        val supply = Phase64PopulationRuleCatalog.supplyResource("STAMINA", 3)
        val captured = snapshot(unit).copy(alreadyMobilized = true)
        assertEquals(1, options(supply, captured, listOf(record(unit))).size)
        assertTrue(options(supply, captured.copy(alreadyMobilized = false), listOf(record(unit))).isEmpty())
        assertTrue(options(supply, captured, listOf(record(unit)), actor(supply).copy(resources = listOf(MechanicalResource("STAMINA", 2, 20)))).isEmpty())
        assertTrue(options(supply, captured.copy(body = captured.body.copy(resources = listOf(MechanicalResource("STAMINA", 20, 20)))), listOf(record(unit))).isEmpty())
    }

    @Test fun exposureFactoryRequiresRegisteredPolicyAndRealContactWithoutDefaultEpidemic() {
        val exposure = Phase64PopulationRuleCatalog.exposure("REGISTERED-PATHOGEN", "REGISTERED-CONDITION", 3, 3, "COMMITTED-CONTACT", 2)
        val captured = snapshot()
        assertTrue(options(exposure, captured).isEmpty())
        assertEquals(1, options(exposure, captured.copy(contactEvidencePresent = true)).size)
        val saturated = captured.copy(contactEvidencePresent = true, body = captured.body.copy(aggregatePopulation =
            requireNotNull(captured.body.aggregatePopulation).copy(conditionCounts = mapOf("REGISTERED-CONDITION" to 3))))
        assertTrue(options(exposure, saturated).isEmpty())
        val prepared = Phase64PopulationOwnerPreparation.exposure(captured.body, exposure, "REGISTERED-CONDITION", 3, 3, "COMMITTED-CONTACT", "EVENT", 2)
        assertEquals(2L, (prepared.changes.single() as AggregatePopulationChange).conditionAffectedDelta)
        assertTrue("COMMITTED-CONTACT" in prepared.sourceUids)
    }

    @Test fun adapterAuthorizesPrincipalAgainstPopulationSubjectAndRoutesTheSubjectNotTheLeader() {
        val definition = Phase64PopulationRuleCatalog.cohortMovement(snapshot().copy(route = route()), route())
        val parameters = Phase64ProcessActivation.bind(definition, principal, destination)
        val process = BackgroundProcessInstance("PROCESS", definition.uid, 2, principal, 1, WorldTimeTick(0), WorldTimeTick(1000), parameters = parameters)
        var authorizedActor: DomainRef? = null
        var authorizedRefs: List<DomainRef> = emptyList()
        var routedActor: DomainRef? = null
        val reads = object : BackgroundWorldReadPort {
            override fun available(resource: DomainRef, staged: List<PlayerDomainChangePayload>): Long? = 10
            override fun exists(ref: DomainRef) = true
            override fun route(actor: DomainRef, destination: DomainRef, at: WorldTimeTick): String { routedActor = actor; return route().fingerprint }
            override fun authorizedRoute(principal: DomainRef, subject: DomainRef, destination: DomainRef, at: WorldTimeTick): String {
                assertEquals(this@Phase64PopulationRuleCatalogTest.principal, principal)
                routedActor = subject
                return route().fingerprint
            }
            override fun authorize(actor: DomainRef, purpose: String, refs: List<DomainRef>): Boolean { authorizedActor = actor; authorizedRefs = refs; return true }
            override fun prepareOwnedEffect(operation: String, actor: DomainRef, parameters: Map<String, String>, scope: BackgroundProcessEvaluationScope,
                staged: List<PlayerDomainChangePayload>) = WorldConsequencePlan(changes = listOf(SpatialChange(group, 0, destinationLocation = destination)))
        }
        val prepared = Phase64PopulationConflictsAdapter().evaluate(definition, process, scope, WorldTimeTick(1000), reads, emptyList())
        assertEquals(BackgroundProcessStatus.COMPLETED, prepared.status)
        assertEquals(principal, authorizedActor)
        assertEquals(setOf(group, destination), authorizedRefs.toSet())
        assertEquals(group, routedActor)
    }
}
