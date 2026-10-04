package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase64NpcInitiationTest {
    private val temporal = TemporalScope("C1", "G1", 7, "STATE")
    private val actor = DomainRef("NPC", "N1")
    private val actionUid = "NPC_CONSUME_REGISTERED_MATERIAL"
    private val definition = BackgroundProcessDefinition("NPC_CONSUMPTION", 1, "ECONOMY",
        Phase64EconomyOperations.CONSUME, 60_000, parameters = mapOf(
            Phase64ProcessActivation.ACTION_KEY to actionUid, "activation_npc" to "true",
            "activation_policy_uid" to "REGISTERED_NPC_CONSUMPTION", "activation_routine" to "true",
            "inputOwnerKind" to "@ACTOR_KIND", "inputOwnerUid" to "@ACTOR_UID", "inputItemUids" to "MATERIAL"))
    private val genesis = NpcBrainOwner.initialize(temporal.campaignUid, actor, "SEED")
    private val cause = NpcCauseRef(NpcCauseKind.INTRINSIC_MOTIVATION, genesis.motivations.first().uid)
    private val brain = genesis.copy(revision = 2, goals = listOf(NpcGoal("GOAL", cause.uid, "Use the material",
        NpcWeight(7000), NpcGoalLifecycle.ACTIVE, cause)))
    private val body = MechanicalActorView(temporal.campaignUid, actor, MechanicalActorKind.NPC, 1,
        MechanicalStateMaterialization.FULL, mapOf("DEFENCE" to 10), listOf(MechanicalResource("HEALTH", 10, 10)),
        setOf(actionUid), generationProvenanceUid = "OWNER_SEED")

    private fun projected(): NpcContextResult.Ready {
        val reads = object : NpcProjectionReadPort {
            override fun brain(audience: AudienceContext, purpose: PurposeContext, actor: DomainRef, holder: KnowledgeHolderRef) =
                ProtectedReadResult.Allow(brain, DisclosureLevel.DISCLOSE_FULL, "OWN_BRAIN")
            override fun knowledge(audience: AudienceContext, purpose: PurposeContext, holder: KnowledgeHolderRef,
                order: Long, limit: Int): ProtectedReadResult<List<NpcKnownRecord>> = ProtectedReadResult.NoData
        }
        return NpcDecisionContextProjector(reads).project(NpcDecisionScope(temporal, actor, brain.revision,
            WorldTimeTick(0), 0, "P1"), NpcTrigger("SELF", NpcTriggerKind.SELF_REFLECTION, WorldTimeTick(0), cause),
            brain.knowledgeHolder, ContextRuntimeProfile("TEST", 8192, 64, 64, 512)) { current, records ->
            listOfNotNull(Phase64NpcInitiation.option(current, records, body, definition))
        } as NpcContextResult.Ready
    }

    private fun selected(projected: NpcContextResult.Ready) = NpcDecisionEngine().select(projected.context,
        NpcDecisionProposal("INITIATE", projected.context.contextFingerprint,
            listOf(NpcDecisionCandidate(projected.context.options.single().uid))), projected.context.scope) as NpcDecisionResult.Selected

    @Test fun registeredNpcActivityRequiresExistingCapabilityIdentityAndActiveGoal() {
        val option = requireNotNull(Phase64NpcInitiation.option(brain, emptyList(), body, definition))
        assertEquals(actionUid, option.capabilityUid)
        assertEquals(actor, option.target)
        assertEquals("GOAL", option.goalUid)
        assertEquals(AcceptedActionTiming(ActionDuration(1000), "P64:INITIATION_MS_V1", 1), option.timing)
        assertTrue(option.parameters.isEmpty())
        assertNull(Phase64NpcInitiation.option(brain, emptyList(), body.copy(executableAbilityUids = emptySet()), definition))
        assertNull(Phase64NpcInitiation.option(brain, emptyList(), body.copy(kind = MechanicalActorKind.ACTIVE_PLAYER), definition))
        assertNull(Phase64NpcInitiation.option(brain, emptyList(), body.copy(actor = DomainRef("NPC", "OTHER")), definition))
        assertNull(Phase64NpcInitiation.option(brain.copy(goals = brain.goals.map { it.copy(lifecycle = NpcGoalLifecycle.SUSPENDED) }),
            emptyList(), body, definition))
        assertNull(Phase64NpcInitiation.option(brain, emptyList(), body,
            definition.copy(parameters = definition.parameters + ("activation_npc" to "false"))))
    }

    @Test fun knowledgeBasedGoalRequiresItsActualAcquisitionInTheActorProjection() {
        val knowledgeCause = NpcCauseRef(NpcCauseKind.KNOWLEDGE_ACQUISITION, "ACQUISITION")
        val informed = brain.copy(goals = brain.goals.map { it.copy(cause = knowledgeCause) })
        assertNull(Phase64NpcInitiation.option(informed, emptyList(), body, definition))
        val record = NpcKnownRecord("KNOWN_MATERIAL", KnowledgeEpistemicState.BELIEVED, "I can use this material.",
            knowledgeCause.uid, 1, setOf(actor), temporal.baseCommitOrder)
        val option = requireNotNull(Phase64NpcInitiation.option(informed, listOf(record), body, definition))
        assertEquals(setOf(record.uid), option.supportingRecordUids)
        assertEquals(brain.goals.single().uid, option.goalUid)
    }

    @Test fun sealedNpcSelectionResolvesThroughMechanicsAndOnlyCompletedActorProofCanStart() {
        val projected = projected()
        val selected = selected(projected)
        var calls = 0
        val mechanics = NpcMechanicalActionApplication(MechanicsRuleResolver { request, context ->
            calls++
            assertSame(selected.authorization, context.npcAuthorization)
            val node = context.plan.intent.nodes.single()
            assertTrue(selected.authorization.authorizesMechanics(temporal, context.plan, node, request))
            Phase64ProcessActivation.resolve(definition, "REGISTERED_POLICY", request, context, node, actor)
        }, { temporal })
        val result = mechanics.resolve(projected, selected)
        assertTrue(result.toString(), result is NpcMechanicalResult.Resolved)
        val resolved = result as NpcMechanicalResult.Resolved
        assertEquals(1, calls)
        assertEquals(selected.authorization.decisionUid, resolved.effects.single().canonicalPayload["p64_start_decision_uid"])
        val preflight = resolved.effects.single()
        assertThrows(IllegalArgumentException::class.java) {
            Phase64ProcessActivation.start(temporal, "COMMAND", definition, preflight, WorldTimeTick(1000))
        }
        // These source fields are attached by the actual Phase62 completion owner, after a
        // fresh mechanics resolution. A discarded preflight cannot start a durable process.
        val completed = preflight.copy(canonicalPayload = preflight.canonicalPayload + mapOf(
            "source_actor_kind_uid" to actor.kindUid, "source_actor_uid" to actor.uid))
        val start = Phase64ProcessActivation.start(temporal, "COMMAND", definition, completed, WorldTimeTick(1000))
        assertEquals(actor, start.process.actor)
        assertEquals(WorldTimeTick(61_000), start.process.due)
        assertEquals(actor.uid, start.process.parameters["inputOwnerUid"])
        assertEquals(BackgroundProcessStatus.ACTIVE, start.process.status)
        assertTrue(start.consequenceFingerprints.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            Phase64ProcessActivation.start(temporal, "COMMAND", definition,
                completed.copy(canonicalPayload = completed.canonicalPayload + ("source_actor_uid" to "OTHER")), WorldTimeTick(1000))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Phase64ProcessActivation.start(temporal, "COMMAND", definition,
                completed.copy(canonicalPayload = completed.canonicalPayload - "p64_start_decision_uid"), WorldTimeTick(1000))
        }
        val registry = TypedPlayerChangeRegistry.core()
        assertEquals(start, registry.decodeWorkerPayload(registry.encodeWorkerPayload(start)))
    }

    @Test fun changedSelectionAndHistoryCannotReachNpcInitiationResolver() {
        val projected = projected()
        val selected = selected(projected)
        val mechanics = NpcMechanicalActionApplication(MechanicsRuleResolver { _, _ -> error("Unauthorized request reached initiation") }, { temporal })
        assertEquals(NpcMechanicalResult.Unavailable("P62:ACTION_AUTHORIZATION_MISMATCH"),
            mechanics.resolve(projected, selected.copy(option = selected.option.copy(target = DomainRef("NPC", "OTHER")))))
        val stale = NpcMechanicalActionApplication(MechanicsRuleResolver { _, _ -> error("Stale request reached initiation") },
            { temporal.copy(historyGenerationUid = "NEW_GENERATION") })
        assertEquals(NpcMechanicalResult.Unavailable("P62:STALE_SCOPE"), stale.resolve(projected, selected))
    }
}
