package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase60PlanBindingTest {
    private fun node(uid: String, dependencies: List<IntentDependency> = emptyList(), modality: IntentModality = IntentModality.ATTEMPT_NOW) =
        IntentNode(uid, IntentForm.DIRECT_ACTION, SemanticAction(rawPhrase = uid), dependencies = dependencies, modality = modality)
    private fun plan(nodes: List<IntentNode>): CanonicalTurnPlan {
        val intent = IntentDocument(campaignUid = "C1", actor = CommandActorRef("PLAYER", "P1"), rawInput = "test", meaningState = MeaningState.UNDERSTOOD,
            nodes = nodes, provenance = IntentInterpretationProvenance(IntentInterpretationSource.PLAYER_CLARIFICATION, "test", "1", "hash"))
        return CanonicalTurnPlan(planUid = "plan", campaignUid = "C1", intent = intent, audience = VisibilityAudienceFactory.player("C1"),
            purpose = PurposeContext("C1", VisibilityPurposeKinds.GAMEPLAY_NARRATION), steps = nodes.map { node ->
                CanonicalTurnPlanStep("step:${node.nodeUid}", node.nodeUid, "cap", CapabilityMatchState.EXACT,
                    node.dependencies.map { it.predecessorNodeUid }, emptyList(), CapabilityExecutionKind.MECHANICS_PROPOSAL,
                    CapabilitySideEffectClass.PROPOSED_WORLD_EFFECT, "owner")
            })
    }
    private fun binding(duration: Long) = TemporalNodeBinding(ActionTimingEvidence(ActionTimeMeaning.IN_WORLD,
        authoritative = AcceptedActionTiming(ActionDuration(duration), "rule", 1)), "owner")
    @Test fun phase44DuringDependencyProducesConcurrentTiming() {
        val plan = plan(listOf(node("walk"), node("talk", listOf(IntentDependency("walk", IntentDependencyKind.DURING)))))
        val result = Phase60PlanBinding.bind(plan, mapOf("walk" to binding(100), "talk" to binding(40))) as TemporalPlanBindingResult.Bound
        assertEquals("walk", result.nodes.single { it.uid == "talk" }.during)
        assertEquals(100L, Phase60ActionPlanner.schedule(WorldTimeTick(0), result.nodes).maxOf { it.end.milliseconds })
    }
    @Test fun futurePlanDoesNotExecuteOrSpendWorldTime() {
        assertEquals(TemporalPlanBindingResult.OutOfWorld, Phase60PlanBinding.bind(plan(listOf(node("train", modality = IntentModality.PLAN_FUTURE))), emptyMap()))
    }
    @Test fun afterSuccessRequiresCoreDecisionRatherThanAssumingSuccess() {
        val plan = plan(listOf(node("attempt"), node("continue", listOf(IntentDependency("attempt", IntentDependencyKind.AFTER_SUCCESS)))))
        val result = Phase60PlanBinding.bind(plan, mapOf("attempt" to binding(100), "continue" to binding(40)))
        assertEquals(TemporalPlanBindingResult.NeedsDecision("continue", "P60:CONDITION_PENDING"), result)
    }

    private fun production(nodes:List<IntentNode>, deadlines:List<WorldProcessDeadline> = emptyList()):ProductionTimeResult {
        val plan = plan(nodes)
        val request = ChatTurnRequest("R", "C1", "T", "CMD", "TX", plan.intent.actor, "test", "pl", plan.audience, plan.purpose)
        return Phase60ProductionTime.prepare(request, plan, TemporalReadSnapshot(TemporalScope("C1", "G1", 0, "HASH"),
            CanonicalTemporalState(0, WorldTimeTick(0), emptyList(), deadlines)))
    }
    private fun timed(uid:String, millis:Long, dependencies:List<IntentDependency> = emptyList()) = node(uid,dependencies).let {
        it.copy(semanticAction = it.semanticAction.copy(attributes = mapOf("time_scope" to "WORLD", "time_min_ms" to "$millis", "time_max_ms" to "$millis")))
    }
    @Test fun productionTimingChargesOverlapOnceAndSequenceSeparately() {
        val walk = timed("walk", 1000)
        val talk = timed("talk", 500, listOf(IntentDependency("walk", IntentDependencyKind.DURING)))
        val read = timed("read", 2000, listOf(IntentDependency("walk", IntentDependencyKind.AFTER_COMPLETION)))
        val result = production(listOf(walk,talk,read)) as ProductionTimeResult.Ready
        assertEquals(3000L, result.change!!.proposedTime.milliseconds)
    }
    @Test fun productionNeverInventsMissingDurationOrCrossesUnownedDeadline() {
        assertTrue(production(listOf(node("anything"))) is ProductionTimeResult.Rejected)
        assertTrue(production(listOf(timed("anything", 1000)), listOf(WorldProcessDeadline("D", "unknown", WorldTimeTick(500)))) is ProductionTimeResult.Rejected)
    }
    @Test fun futureIntentionHasNoClockChange() {
        assertEquals(ProductionTimeResult.Ready(null), production(listOf(node("later", modality = IntentModality.PLAN_FUTURE))))
    }
    @Test fun coreBlockedNodeDoesNotChargeTimeAfterFailedAttempt() {
        val attempted=timed("attempt",1000)
        val blocked=timed("followup",5000,listOf(IntentDependency("attempt",IntentDependencyKind.AFTER_SUCCESS)))
        val plan=plan(listOf(attempted,blocked))
        val request=ChatTurnRequest("R","C1","T","CMD","TX",plan.intent.actor,"test","pl",plan.audience,plan.purpose)
        val result=Phase60ProductionTime.prepare(request,plan,TemporalReadSnapshot(TemporalScope("C1","G1",0,"HASH"),
            CanonicalTemporalState(0,WorldTimeTick(0),emptyList())),outcomes=mapOf("attempt" to GmNodeOutcomeState.PROPOSED_FAILURE,
                "followup" to GmNodeOutcomeState.BLOCKED_BY_PREREQUISITE)) as ProductionTimeResult.Ready
        assertEquals(listOf("attempt"),result.schedule.map{it.action.uid})
        assertEquals(WorldTimeTick(1000),result.change!!.proposedTime)
    }
}
