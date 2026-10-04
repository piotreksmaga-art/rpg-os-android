package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase64CombatReceiptTest {
    private val actor = DomainRef("ACTOR", "COMBATANT1")
    private val target = DomainRef("UNIT", "FORMATION1")
    private val scope = BackgroundProcessEvaluationScope(TemporalScope("C1", "H1", 7, "digest"), "seed", "rules")
    private val rule = BackgroundProcessDefinition(Phase64CombatReceiptFactory.RULE_UID, Phase64CombatReceiptFactory.RULE_VERSION,
        "CONFLICT", "COMBAT", 60_000, parameters = mapOf(Phase64CombatReceiptFactory.OWNER_PARAMETER to NpcActionProcess.OWNER))

    private data class Fixture(val brain: NpcBrainState, val pending: NpcPendingAction, val finished: NpcBrainChange,
        val input: TemporalOwnerInput, val effects: List<VerifiedMechanicsCommandEffect>)

    private fun fixture(): Fixture {
        val contract = phase60Hash("REGISTERED-COMBAT-CONTRACT")
        val initial = NpcBrainOwner.initialize("C1", actor, "seed")
        val startedCause = NpcCauseRef(NpcCauseKind.ACCEPTED_ACTION, "START-COMMAND")
        val goal = NpcGoal("COMBAT-GOAL", initial.motivations.first().uid, "Defend the settlement", NpcWeight(5000),
            NpcGoalLifecycle.ACTIVE, startedCause)
        val option = "P62:OPTION:${phase60Hash("$actor|${goal.uid}|ATTACK|$target|$contract").take(32)}"
        val plan = NpcPlan("P62:DECISION:DURABLE-PLAN", goal.uid, option, NpcPlanLifecycle.RUNNING,
            WorldTimeTick(0), WorldTimeTick(1000), startedCause)
        val brain = initial.copy(revision = 3, goals = listOf(goal), plans = listOf(plan))
        val finishedCause = NpcCauseRef(NpcCauseKind.ACCEPTED_ACTION, "COMPLETE-COMMAND")
        val terminal = brain.copy(revision = 4, plans = listOf(plan.copy(lifecycle = NpcPlanLifecycle.COMPLETED,
            nextEvaluationAt = null, cause = finishedCause)))
        val finished = NpcBrainChange("C1", actor, "H1", 3, NpcBrainCodec.fingerprint(brain), NpcBrainCodec.encode(terminal),
            NpcBrainRules.PLANNING.uid, NpcBrainRules.PLANNING.version, listOf(finishedCause))
        val pending = NpcPendingAction(actor, plan.uid, option, WorldTimeTick(0), WorldTimeTick(1000), Phase60CombatTime.RULE, 1)
        val markers = mapOf("npc_plan_uid" to plan.uid, "npc_option_uid" to option, "npc_started_at_ms" to "0", "npc_due_at_ms" to "1000",
            "npc_ability_uid" to "ATTACK", "npc_target_kind_uid" to target.kindUid, "npc_target_uid" to target.uid,
            "npc_ability_contract" to contract, "source_actor_kind_uid" to actor.kindUid, "source_actor_uid" to actor.uid)
        val proof = "P60:PROCESS:${phase60Hash("ACTUAL-COMPLETION")}:RPGOS-P50-PROOF:${phase60Hash("ACTUAL-COMBAT")}"
        val impact = VerifiedMechanicsCommandEffect("NPC-IMPACT", "NPC-NODE", "UNIVERSAL_COMBAT", "WOUND", target, 2,
            markers + mapOf("combat_proof_uid" to "ACTUAL-COMBAT-PROOF"), proof, "INPUT", "OUTPUT")
        val debit = VerifiedMechanicsCommandEffect("NPC-COST", "NPC-NODE", "UNIVERSAL_COMBAT", "RESOURCE_DELTA", actor, -2,
            markers + mapOf("resource_uid" to "STAMINA"), "$proof:COST", "INPUT", "COST-OUTPUT")
        val effects = listOf(impact, debit)
        val changes = listOf(finished) + effects.flatMap { effect ->
            (MechanicalEffectMaterializer.materialize(effect) as MechanicalEffectMaterializationResult.Materialized).changes.map { it.payload }
        }
        val input = TemporalOwnerInput(scope.temporal, WorldTimeTick(0), WorldTimeTick(1000), emptyList(), emptyList(), null,
            changes, effects, mapOf(NpcActionProcess.OWNER to TemporalOwnerState(NpcActionProcess.OWNER, 1, NpcActionProcess.encode(emptyList()))))
        return Fixture(brain, pending, finished, input, effects)
    }

    private fun prepare(f: Fixture, command: String = "COMPLETE-COMMAND", activePlayer: String = "ACTIVE-PC",
        definition: BackgroundProcessDefinition = rule, currentScope: BackgroundProcessEvaluationScope = scope,
        brain: NpcBrainState = f.brain, input: TemporalOwnerInput = f.input,
        effects: List<VerifiedMechanicsCommandEffect> = f.effects) =
        Phase64CombatReceiptFactory.prepare(currentScope, command, activePlayer, definition, brain, input, effects)

    private fun unavailable(result: Phase64CombatReceiptPreparation) = (result as Phase64CombatReceiptPreparation.Unavailable).reasonUid

    @Test fun productionReceiptUsesRealOneSecondCompletionWithoutSixtySecondProcessOrNewEffects() {
        val f = fixture()
        val result = prepare(f) as Phase64CombatReceiptPreparation.Ready
        val receipt = result.change
        assertEquals(0L, receipt.expectedVersion)
        assertEquals(1L, receipt.process.version)
        assertEquals(BackgroundProcessStatus.COMPLETED, receipt.process.status)
        assertEquals(WorldTimeTick(0), receipt.process.startedAt)
        assertEquals(WorldTimeTick(1000), receipt.process.due)
        assertEquals(receipt.process.due, receipt.evidence.at)
        assertEquals(60_000L, rule.durationMillis)
        assertEquals(1L, receipt.process.progressUnits)
        assertTrue(receipt.deadlineAdds.isEmpty())
        assertTrue(receipt.deadlineRemovals.isEmpty())
        assertTrue(receipt.ownerDelegations.isEmpty())
        assertTrue(receipt.process.dependencyUids.isEmpty())
        assertEquals(3, receipt.consequenceFingerprints.size)
        assertTrue(Phase64BackgroundCodec.fingerprint(f.finished) in receipt.consequenceFingerprints)
        assertTrue(f.effects.all { it.proofUid in receipt.evidence.sourceUids })
        assertTrue("ACTUAL-COMBAT-PROOF" in receipt.evidence.sourceUids)
        assertEquals(NpcPlanLifecycle.RUNNING, f.brain.plans.single().lifecycle)
        assertEquals(receipt, (prepare(f) as Phase64CombatReceiptPreparation.Ready).change)
    }

    @Test fun receiptIdentityDependsOnOriginalPlanAndRuleInsteadOfRetryCommandOrIterationOrder() {
        val f = fixture()
        val first = (prepare(f) as Phase64CombatReceiptPreparation.Ready).change
        val retried = (prepare(f, command = "RETRY-COMMAND") as Phase64CombatReceiptPreparation.Ready).change
        assertEquals(first.process.uid, retried.process.uid)
        assertEquals(first.evidence.uid, retried.evidence.uid)
        assertEquals(first.process.parameters["p64_logical_event_uid"], retried.process.parameters["p64_logical_event_uid"])
        assertEquals("RETRY-COMMAND", retried.process.parameters["p64_completion_command_uid"])
        val reordered = (prepare(f, input = f.input.copy(stagedEffects = f.effects.reversed()), effects = f.effects.reversed())
            as Phase64CombatReceiptPreparation.Ready).change
        assertEquals(first, reordered)
        assertNotEquals(first.process.uid, Phase64CombatReceiptFactory.receiptUid("C2", f.pending.planUid))
        assertNotEquals(first.process.uid, Phase64CombatReceiptFactory.receiptUid("C1", "OTHER-PLAN"))
        assertNotEquals(first.process.uid, Phase64CombatReceiptFactory.receiptUid("C1", f.pending.planUid, ruleVersion = 3))
        assertNotEquals(Phase64CombatReceiptFactory.receiptUid("C|P", "PLAN"), Phase64CombatReceiptFactory.receiptUid("C", "P|PLAN"))
    }

    @Test fun onlyExplicitVersionTwoCompletionRuleCanCreateInitialTerminalReceipt() {
        val f = fixture()
        assertEquals("P64:COMBAT_RECEIPT_RULE_REQUIRED", unavailable(prepare(f, definition = rule.copy(version = 1))))
        assertEquals("P64:COMBAT_RECEIPT_RULE_REQUIRED", unavailable(prepare(f, definition = rule.copy(parameters = emptyMap()))))
        assertEquals("P64:COMBAT_RECEIPT_RULE_REQUIRED", unavailable(prepare(f, definition = rule.copy(uid = "IMPORTED-COMBAT"))))
        assertEquals("P64:COMBAT_RECEIPT_RULE_REQUIRED", unavailable(prepare(f,
            definition = rule.copy(parameters = rule.parameters + ("invented_owner" to "OTHER")))))
        assertEquals("P64:ACTIVE_PLAYER_CONTROL_FORBIDDEN", unavailable(prepare(f, activePlayer = actor.uid)))
        assertEquals("P64:COMBAT_RECEIPT_SCOPE", unavailable(prepare(f,
            currentScope = scope.copy(temporal = scope.temporal.copy(historyGenerationUid = "AFTER-UNDO")))))
    }

    @Test fun missingCostSubsetAndAlteredOriginalEffectsCannotProduceReceipt() {
        val f = fixture()
        assertEquals("P64:COMBAT_RECEIPT_EFFECTS_REQUIRED", unavailable(prepare(f, effects = emptyList())))
        assertEquals("P64:COMBAT_RECEIPT_EFFECT_PREFIX", unavailable(prepare(f, effects = listOf(f.effects.first()))))
        val altered = f.effects.first().copy(magnitude = 3)
        assertEquals("P64:COMBAT_RECEIPT_EFFECT_PREFIX", unavailable(prepare(f, effects = listOf(altered, f.effects.last()))))
        assertEquals("P64:NPC_COMBAT_EFFECT_PREFIX_REQUIRED", unavailable(prepare(f, input = f.input.copy(stagedChanges = listOf(f.finished)))))
        assertEquals("P64:COMBAT_RECEIPT_PLAN_REQUIRED", unavailable(prepare(f,
            effects = listOf(f.effects.first(), f.effects.last().copy(canonicalPayload = f.effects.last().canonicalPayload + ("npc_plan_uid" to "OTHER-PLAN"))))))
    }

    @Test fun pendingOrHistoricalCompletionCannotBeConvertedIntoNewReceipt() {
        val f = fixture()
        val pending = f.input.copy(peerStates = mapOf(NpcActionProcess.OWNER to TemporalOwnerState(NpcActionProcess.OWNER, 1,
            NpcActionProcess.encode(listOf(f.pending)))))
        assertEquals("P64:NPC_COMBAT_PLAN_NOT_CONSUMED", unavailable(prepare(f, input = pending)))
        val terminal = NpcBrainCodec.decode(f.finished.stateCanonical)
        assertEquals("P64:NPC_COMBAT_COMPLETION_REQUIRED", unavailable(prepare(f, brain = terminal,
            input = f.input.copy(stagedChanges = f.input.stagedChanges.drop(1)))))
        assertEquals("P64:COMBAT_RECEIPT_EFFECTS_REQUIRED", unavailable(prepare(f,
            effects = List(128) { index -> f.effects.first().copy(effectUid = "IMPACT-$index") })))
    }
}
