package com.rpgos.app

/** Supplied by trusted domain owners after interpreting the authorized Phase44 plan. */
internal data class TemporalNodeBinding(
    val timingEvidence: ActionTimingEvidence,
    val temporalOwnerUid: String,
    val exclusiveSlots: Set<String> = emptySet(),
    /** null means the conditional branch is not yet resolved, NOT permission to execute it. */
    val conditionSatisfied: Boolean? = null,
    val skippedByCore:Boolean = false
)

internal sealed interface TemporalPlanBindingResult {
    data class Bound(val planUid: String, val nodes: List<TimedActionNode>) : TemporalPlanBindingResult
    data object OutOfWorld : TemporalPlanBindingResult
    data class NeedsDecision(val nodeUid: String, val reasonUid: String) : TemporalPlanBindingResult
}

/**
 * Adapts the real Phase44 graph, preserving explicit DURING versus ordering semantics.
 * It does not interpret arbitrary raw verbs or promote an AI duration into a mechanics rule.
 */
internal object Phase60PlanBinding {
    fun bind(plan: CanonicalTurnPlan, bindings: Map<String, TemporalNodeBinding>): TemporalPlanBindingResult {
        val active = plan.intent.activeNodes().associateBy { it.nodeUid }
        require(bindings.keys.all { it in active }) { "P60:FOREIGN_TIMING_NODE" }
        val selected = linkedMapOf<String, Pair<IntentNode, TemporalNodeBinding>>()
        val timings = linkedMapOf<String, AcceptedActionTiming>()
        for (step in plan.steps) {
            val node = active[step.nodeUid] ?: return TemporalPlanBindingResult.NeedsDecision(step.nodeUid, "P60:INACTIVE_PLAN_NODE")
            if (node.modality != IntentModality.ATTEMPT_NOW || node.form in setOf(IntentForm.CORRECTION, IntentForm.CANCELLATION)) continue
            if(bindings[node.nodeUid]?.skippedByCore==true)continue
            if (step.matchState !in setOf(CapabilityMatchState.EXACT, CapabilityMatchState.COMPOSED, CapabilityMatchState.GENERIC))
                return TemporalPlanBindingResult.NeedsDecision(node.nodeUid, "P60:CAPABILITY_NOT_ADMITTED")
            val binding = bindings[node.nodeUid] ?: return TemporalPlanBindingResult.NeedsDecision(node.nodeUid, "P60:TIMING_OWNER_REQUIRED")
            if (binding.timingEvidence.meaning == ActionTimeMeaning.OUT_OF_WORLD) continue
            if (node.conditions.isNotEmpty() || node.dependencies.any { it.kind in conditionalDependencies }) {
                if (binding.conditionSatisfied == null) return TemporalPlanBindingResult.NeedsDecision(node.nodeUid, "P60:CONDITION_PENDING")
                if (!binding.conditionSatisfied) continue
            }
            if (node.terminationConditionUid != null && binding.timingEvidence.authoritative == null)
                return TemporalPlanBindingResult.NeedsDecision(node.nodeUid, "P60:TERMINATION_OWNER_REQUIRED")
            when (val decision = Phase60TimingPolicy.resolve(binding.timingEvidence)) {
                is ActionTimingDecision.ClarificationRequired -> return TemporalPlanBindingResult.NeedsDecision(node.nodeUid, decision.reasonUid)
                ActionTimingDecision.OutOfWorld -> continue
                is ActionTimingDecision.Accepted -> timings[node.nodeUid] = decision.timing
            }
            require(binding.temporalOwnerUid.isNotBlank())
            selected[node.nodeUid] = node to binding
        }
        if (selected.isEmpty()) return TemporalPlanBindingResult.OutOfWorld
        val nodes = selected.values.map { (node, binding) ->
            val during = node.dependencies.filter { it.kind == IntentDependencyKind.DURING }
            if (during.size > 1) return TemporalPlanBindingResult.NeedsDecision(node.nodeUid, "P60:MULTIPLE_DURING_ANCHORS")
            val ordering = node.dependencies.filter { it.kind in orderedDependencies }
            val unsupported = node.dependencies.filter { it.kind !in orderedDependencies && it.kind != IntentDependencyKind.DURING && it.kind != IntentDependencyKind.PURPOSE_FOR }
            if (unsupported.isNotEmpty()) return TemporalPlanBindingResult.NeedsDecision(node.nodeUid, "P60:DEPENDENCY_REQUIRES_ADJUDICATION")
            if ((ordering + during).any { it.predecessorNodeUid !in selected })
                return TemporalPlanBindingResult.NeedsDecision(node.nodeUid, "P60:DEPENDENCY_NOT_EXECUTED")
            TimedActionNode(node.nodeUid, binding.temporalOwnerUid, timings.getValue(node.nodeUid), ordering.map { it.predecessorNodeUid }.toSet(),
                binding.exclusiveSlots.toSet(), during.singleOrNull()?.predecessorNodeUid)
        }
        // Force temporal validation before any speculative owner is called.
        try { Phase60ActionPlanner.schedule(WorldTimeTick(0), nodes) }
        catch (failure: IllegalArgumentException) { return TemporalPlanBindingResult.NeedsDecision("PLAN:${plan.planUid}", failure.message ?: "P60:INVALID_TEMPORAL_PLAN") }
        return TemporalPlanBindingResult.Bound(plan.planUid, nodes)
    }
    private val conditionalDependencies = setOf(IntentDependencyKind.AFTER_SUCCESS, IntentDependencyKind.AFTER_EVENT, IntentDependencyKind.REQUIRES_RESULT)
    private val orderedDependencies = conditionalDependencies + setOf(IntentDependencyKind.BEFORE, IntentDependencyKind.AFTER_ATTEMPT, IntentDependencyKind.AFTER_COMPLETION)
}
