package com.rpgos.app

/** One exact personal action, NOT a claim about its world outcome. Rich objectives such as
 * healing somebody or winning a battle require their respective domain's success contract. */
data class NpcExecutionObjective(val capabilityUid:String,val mechanicsOwnerUid:String,val effectKindUid:String,
    val target:DomainRef,val actionBinding:String,val version:Int=1) {
    init { npcUid(capabilityUid);npcUid(mechanicsOwnerUid);npcUid(effectKindUid);npcUid(target.kindUid);npcUid(target.uid)
        require(version==1 && actionBinding.matches(Regex("[0-9a-f]{64}"))) }
}
internal data class LocationReachedCriterion(val actor:DomainRef,val destination:DomainRef) {
    init { require(destination.kindUid in setOf("PLACE","LOCATION")){"P61:LOCATION_CRITERION_KIND"} }

    /** Goal success is post-commit only. A speculative SpatialChange candidate is insufficient. */
    fun provenBy(
        attempt:NpcActivityAttemptIdentity,
        route:NpcTravelRouteContract,
        evidence:NpcActivityResolutionEvidence
    ):Boolean {
        val owner=NpcTravelAffordances.ownerContract(route)
        return actor==attempt.actor && destination==route.destination &&
            attempt.campaignUid==route.campaignUid &&
            evidence.attemptFingerprint==attempt.fingerprint &&
            evidence.ownerUid==owner.resultOwnerUid &&
            evidence.evidenceKindUid==owner.evidenceKindUid &&
            evidence.resolutionKind==NpcActivityResolutionKind.SUCCEEDED &&
            evidence.canonicalEvidence.size==1 &&
            evidence.canonicalEvidence.single().changeKindUid==PlayerChangeKinds.SPATIAL
    }
}

internal object NpcExecutionGoals {
    private fun binding(actor:DomainRef,option:NpcActionOption)=phase60Hash("P61:EXECUTION_OBJECTIVE:1|"+listOf(actor,
        option.capabilityUid,option.mechanicsOwnerUid,option.mechanicalEffectKindUid,option.target?:actor,
        option.parameters.toSortedMap(),option.resourceCosts.toSortedMap(),option.timing.ruleUid,option.timing.ruleVersion).joinToString("|"))
    fun fromOption(context:NpcDecisionContextEnvelope,uid:String):NpcExecutionObjective {
        require(context.contextFingerprint==context.computeFingerprint())
        val option=context.options.singleOrNull{it.uid==uid}?:error("P61:OBJECTIVE_OPTION_NOT_AUTHORIZED")
        require(context.records.map{it.uid}.containsAll(option.supportingRecordUids))
        return NpcExecutionObjective(option.capabilityUid,requireNotNull(option.mechanicsOwnerUid),requireNotNull(option.mechanicalEffectKindUid),
            option.target?:context.brain.actor,binding(context.brain.actor,option))
    }
    // The model cannot label one WAIT as "save the village" and get that objective completed.
    fun description(objective:NpcExecutionObjective)="Wykonać raz ${objective.capabilityUid} dla ${objective.target.kindUid}:${objective.target.uid}."
    fun fulfilled(context:NpcDecisionContextEnvelope,plan:NpcPlan,selected:NpcDecisionResult.Selected,
                  result:NpcMechanicalResult.Resolved):NpcExecutionFulfillment? {
        val goal=context.brain.goals.singleOrNull{it.uid==plan.goalUid && it.lifecycle==NpcGoalLifecycle.ACTIVE}?:return null
        val objective=goal.executionObjective?:return null
        if(goal.objective!=description(objective) || objective.actionBinding!=binding(context.brain.actor,selected.option) ||
            selected.option.goalUid!=goal.uid || selected.option.uid!=plan.actionUid || result.authorization!=selected.authorization ||
            !selected.authorization.matches(context.scope,context.contextFingerprint,selected.option) || result.effects.isEmpty() ||
            result.effects.any{it.mechanicsOwnerUid!=objective.mechanicsOwnerUid})return null
        // Travel is special: mechanics here is still speculative. The plan may finish, but the
        // goal cannot be fulfilled until NpcTravelArrivalEvidence is issued from persisted receipt
        // + replay material after the common TurnTransaction commits.
        if(objective.effectKindUid.substringAfterLast(':').uppercase()==NpcTravelAffordances.EFFECT_KIND)return null
        return NpcExecutionFulfillment.issue(context,plan,goal)
    }
}
/** Core-only proof carried across application orchestration, never decoded from an AI response. */
internal class NpcExecutionFulfillment private constructor(private val fingerprint:String,private val planUid:String,val goalUid:String) {
    companion object { internal fun issue(context:NpcDecisionContextEnvelope,plan:NpcPlan,goal:NpcGoal)=
        NpcExecutionFulfillment(context.contextFingerprint,plan.uid,goal.uid) }
    fun matches(context:NpcDecisionContextEnvelope,plan:NpcPlan)=fingerprint==context.contextFingerprint &&
        context.computeFingerprint()==fingerprint && planUid==plan.uid && goalUid==plan.goalUid
}
