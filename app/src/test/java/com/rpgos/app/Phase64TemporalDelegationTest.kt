package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

/** The actual Phase64 receipt producer and Phase62 receiving validator participate in the
 * Phase60 processor. Fixtures select a legal option through Core; no model is invoked. */
class Phase64TemporalDelegationTest {
    private val temporal = TemporalScope("C1", "G1", 7, "STATE")
    private val scope = BackgroundProcessEvaluationScope(temporal, "SEED", "RULES")
    private val actor = DomainRef("NPC", "N1")
    private val rule = BackgroundProcessDefinition("ORG_REACTION", 1, "ORGANIZATION", "DECISION", 1000)
    private val process = BackgroundProcessInstance("REACTION_PROCESS", rule.uid, rule.version, actor, 1,
        WorldTimeTick(0), WorldTimeTick(1000))
    private val existing = NpcPendingAction(DomainRef("NPC", "N2"), "EXISTING_PLAN", "EXISTING_OPTION",
        WorldTimeTick(0), WorldTimeTick(6000), "EXISTING_RULE", 1)
    private val previous = TemporalOwnerState(NpcActionProcess.OWNER, 1, NpcActionProcess.encode(listOf(existing)))

    private fun started(at: WorldTimeTick): NpcActionPreparation.Started {
        val genesis = NpcBrainOwner.initialize(temporal.campaignUid, actor, "SEED")
        val cause = NpcCauseRef(NpcCauseKind.INTRINSIC_MOTIVATION, genesis.motivations.first().uid)
        val brain = genesis.copy(revision = 2, goals = listOf(NpcGoal("GOAL", cause.uid, "Prepare for duty",
            NpcWeight(7000), NpcGoalLifecycle.ACTIVE, cause)))
        NpcBrainOwner.validateTransition(genesis, brain, NpcBrainRules.PLANNING, listOf(cause))
        val option = NpcActionOption("LEGAL_REACTION", "WAIT", actor,
            AcceptedActionTiming(ActionDuration(1000), "REGISTERED_WAIT", 1), "GOAL", emptyList(), emptySet(), routine = true)
        val context = NpcDecisionContextEnvelope(NpcDecisionScope(temporal, actor, brain.revision, at, 0, "P1"),
            NpcTrigger("REACTION", NpcTriggerKind.SELF_REFLECTION, at, cause), brain, emptyList(), listOf(option), 8192)
        val selected = NpcDecisionEngine().select(context, NpcDecisionProposal("REACTION", context.contextFingerprint,
            listOf(NpcDecisionCandidate(option.uid))), context.scope)
        assertTrue(selected.toString(), selected is NpcDecisionResult.Selected)
        val change = NpcBrainDynamics.beginPlan(context, selected as NpcDecisionResult.Selected, "REACTION_COMMAND")
        val plan = NpcBrainCodec.decode(change.stateCanonical).plans.single()
        return NpcActionPreparation.Started(NpcPendingAction(actor, plan.uid, option.uid,
            requireNotNull(plan.startedAt), requireNotNull(plan.nextEvaluationAt), option.timing.ruleUid, option.timing.ruleVersion), listOf(change))
    }

    private class CapturedReads : BackgroundWorldReadPort {
        lateinit var input: TemporalOwnerInput
        override fun forEvaluation(input: TemporalOwnerInput) = this.also { it.input = input }
        override fun available(resource: DomainRef, staged: List<PlayerDomainChangePayload>): Long? = error("No resource claims in a delegation")
        override fun exists(ref: DomainRef) = error("Delegation fixture has no entity reads")
        override fun route(actor: DomainRef, destination: DomainRef, at: WorldTimeTick): String? = error("No route in a delegation")
        override fun authorize(actor: DomainRef, purpose: String, refs: List<DomainRef>) = error("Choice is sealed by Core")
        override fun prepareOwnedEffect(operation: String, actor: DomainRef, parameters: Map<String, String>,
            scope: BackgroundProcessEvaluationScope, staged: List<PlayerDomainChangePayload>): WorldConsequencePlan = error("No alternate owner")
    }

    private fun run(receiverActivePlayer: String = "P1",
                    mutate: (TemporalOwnerResult.Evaluated) -> TemporalOwnerResult.Evaluated = { it }): TemporalExecutionResult {
        val reads = CapturedReads()
        val adapter = object : BackgroundDomainAdapter {
            override val domains = setOf("ORGANIZATION")
            override fun evaluate(definition: BackgroundProcessDefinition, process: BackgroundProcessInstance,
                scope: BackgroundProcessEvaluationScope, at: WorldTimeTick, reads: BackgroundWorldReadPort,
                staged: List<PlayerDomainChangePayload>): WorldConsequencePlan {
                val input = (reads as CapturedReads).input
                val preparation = started(at)
                return WorldConsequencePlan(changes = preparation.changes,
                    sourceUids = preparation.changes.flatMap { it.causes }.map { it.uid },
                    ownerDelegations = listOf(NpcActionProcess.prepareDelegation(input, preparation, process.uid)))
            }
        }
        val background = Phase64BackgroundProcessOwner(scope, listOf(process), mapOf((rule.uid to rule.version) to rule),
            { null }, reads, listOf(adapter))
        val source = object : WorldProcessOwnerPort {
            override val ownerUid = background.ownerUid
            override fun evaluate(input: TemporalOwnerInput): TemporalOwnerResult {
                val result = background.evaluate(input)
                return if (result is TemporalOwnerResult.Evaluated && result.ownerDelegations.isNotEmpty()) mutate(result) else result
            }
        }
        val receiver = NpcActionProcess(temporal, WorldTimeTick(0), receiverActivePlayer, emptyList(), object : NpcTimedActionPort {
            override fun prepare(actor: DomainRef, input: TemporalOwnerInput, cancelled: () -> Boolean): NpcActionPreparation =
                error("A delegated choice must not trigger another decision")
            override fun complete(action: NpcPendingAction, input: TemporalOwnerInput, cancelled: () -> Boolean): NpcActionCompletion =
                error("Future NPC effects must not be paid before their deadline")
        }).extension().owners.single().owner
        val foreground = object : WorldProcessOwnerPort {
            override val ownerUid = PHASE60_FOREGROUND_OWNER
            override fun evaluate(input: TemporalOwnerInput) = TemporalOwnerResult.Evaluated(TemporalOwnerState(ownerUid, 1, "READY"))
        }
        val processor = Phase60TimeProcessor(listOf(source, receiver, foreground), nanoTime = { 0 })
        val checkpoint = processor.begin(temporal, "PLAYER_WAIT", WorldTimeTick(0), listOf(TimedActionNode(
            "PLAYER_WAIT", foreground.ownerUid, AcceptedActionTiming(ActionDuration(1500), "WAIT", 1))),
            listOf(WorldProcessDeadline(existing.deadlineUid, NpcActionProcess.OWNER, existing.due)), listOf(previous))
        return processor.advance(checkpoint, temporal)
    }

    @Test fun receivingOwnerPreservesExistingPlanAndAdmitsOnlyTheNewFutureDeadline() {
        val result = run()
        assertEquals(result.toString(), TemporalStopReason.COMPLETED, result.reason)
        assertTrue(result.readyForAdmission)
        assertTrue(result.checkpoint.candidateEffects.isEmpty())
        val pending = NpcActionProcess.decode(result.checkpoint.ownerStates.getValue(NpcActionProcess.OWNER))
        assertEquals(2, pending.size)
        assertTrue(existing in pending)
        val admitted = pending.single { it.actor == actor }
        assertEquals(WorldTimeTick(1000), admitted.startedAt)
        assertEquals(WorldTimeTick(2000), admitted.due)
        assertTrue(WorldProcessDeadline(admitted.deadlineUid, NpcActionProcess.OWNER, admitted.due) in result.checkpoint.deadlines)
        val receipt = result.checkpoint.candidateChanges.filterIsInstance<BackgroundProcessChange>().single()
        val brain = result.checkpoint.candidateChanges.filterIsInstance<NpcBrainChange>().single()
        assertEquals(BackgroundProcessStatus.COMPLETED, receipt.process.status)
        assertTrue(Phase64BackgroundCodec.fingerprint(brain) in receipt.consequenceFingerprints)
        assertEquals(NpcPlanLifecycle.RUNNING, NpcBrainCodec.decode(brain.stateCanonical).plans.single().lifecycle)
        val wire = Phase60CheckpointCodec.encode(result.checkpoint)
        assertEquals(wire, Phase60CheckpointCodec.encode(Phase60CheckpointCodec.decode(wire)))
    }

    @Test fun receivingValidatorRejectsMissingReceiptBrainBindingAndStalePeerState() {
        val mutations = listOf<Pair<String, (TemporalOwnerResult.Evaluated) -> TemporalOwnerResult.Evaluated>>(
            "MISSING_RECEIPT" to { output -> output.copy(changes = output.changes.filterNot { it is BackgroundProcessChange }) },
            "MISSING_BRAIN" to { output -> output.copy(changes = output.changes.filterNot { it is NpcBrainChange }) },
            "UNBOUND_BRAIN" to { output -> output.copy(changes = output.changes.map {
                if (it is BackgroundProcessChange) it.copy(consequenceFingerprints = emptyList()) else it
            }) },
            "STALE_HISTORY" to { output -> output.copy(changes = output.changes.map {
                if (it is NpcBrainChange) it.copy(historyGenerationUid = "OLD_GENERATION") else it
            }) },
            "STALE_PEER_STATE" to { output ->
                val before = output.ownerDelegations.single()
                output.copy(ownerDelegations = listOf(TemporalOwnerDelegation(before.sourceUid,
                    TemporalOwnerDelegation.fingerprint(null), before.proposed, before.deadlines)))
            })
        mutations.forEach { (name, mutation) ->
            val result = run(mutate = mutation)
            assertEquals(name, TemporalStopReason.INVALID_OWNER_RESULT, result.reason)
            assertEquals(name, "P60:DELEGATION_REJECTED", result.diagnostic)
            assertFalse(name, result.readyForAdmission)
            assertTrue(name, result.checkpoint.candidateChanges.isEmpty())
            assertTrue(name, result.checkpoint.candidateEffects.isEmpty())
            assertEquals(name, previous, result.checkpoint.ownerStates.getValue(NpcActionProcess.OWNER))
        }
    }

    @Test fun receivingOwnerRejectsActivePlayerAndDeadlineWithAnotherIdentity() {
        assertEquals(TemporalStopReason.INVALID_OWNER_RESULT, run(receiverActivePlayer = actor.uid).reason)
        val result = run { output ->
            val delegation = output.ownerDelegations.single()
            output.copy(ownerDelegations = listOf(TemporalOwnerDelegation(delegation.sourceUid, delegation.expectedStateFingerprint,
                delegation.proposed, delegation.deadlines.map { it.copy(uid = "FOREIGN_DEADLINE") })))
        }
        assertEquals(TemporalStopReason.INVALID_OWNER_RESULT, result.reason)
        assertEquals("P60:DELEGATION_REJECTED", result.diagnostic)
        assertTrue(result.checkpoint.candidateChanges.isEmpty())
    }

    @Test fun delegationFactoryRejectsDuplicateActorAndStartingBeforeTheBoundary() {
        val preparation = started(WorldTimeTick(1000))
        val occupied = TemporalOwnerState(NpcActionProcess.OWNER, 1, NpcActionProcess.encode(listOf(preparation.pending)))
        val input = TemporalOwnerInput(temporal, WorldTimeTick(0), WorldTimeTick(1000), emptyList(), emptyList(), null,
            peerStates = mapOf(NpcActionProcess.OWNER to occupied))
        assertThrows(IllegalArgumentException::class.java) { NpcActionProcess.prepareDelegation(input, preparation, process.uid) }
        assertThrows(IllegalArgumentException::class.java) {
            NpcActionProcess.prepareDelegation(input.copy(through = WorldTimeTick(1500), peerStates = emptyMap()), preparation, process.uid)
        }
    }
}
