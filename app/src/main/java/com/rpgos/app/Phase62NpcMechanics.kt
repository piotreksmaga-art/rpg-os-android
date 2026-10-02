package com.rpgos.app

internal sealed interface NpcMechanicalResult {
    /** Speculative material only. The caller must settle timing and admit it with the player's turn. */
    data class Resolved(val effects:List<VerifiedMechanicsCommandEffect>,val changes:List<PlayerDomainChangePayload>,
                        val timing:AcceptedActionTiming,val authorization:NpcActionAuthorization):NpcMechanicalResult
    data class Unavailable(val reasonUid:String):NpcMechanicalResult
}

internal class NpcMechanicalActionApplication(
    private val resolver:MechanicsRuleResolver,
    private val currentScope:()->TemporalScope,
    private val travelRoutes:NpcTravelRoutePort,
    private val travelActors:NpcTravelActorReadPort
) {
    // Preserve the two-argument/trailing-lambda construction used by existing callers.
    constructor(resolver:MechanicsRuleResolver,currentScope:()->TemporalScope):
        this(resolver,currentScope,NpcTravelRoutePort.NONE,NpcTravelActorReadPort.NONE)

    fun resolve(projected:NpcContextResult.Ready,selected:NpcDecisionResult.Selected,
                stagedEffects:List<VerifiedMechanicsEffect> = emptyList(),cancellation:AiCancellationSignal=AiCancellationSignal.NONE):NpcMechanicalResult {
        fun fail(reason:String)=NpcMechanicalResult.Unavailable("P62:$reason")
        if(cancellation.isCancelled())return fail("CANCELLED")
        val context=projected.context;val option=selected.option;val authorization=selected.authorization
        if(context.computeFingerprint()!=context.contextFingerprint || !authorization.matches(context.scope,context.contextFingerprint,option))return fail("ACTION_AUTHORIZATION_MISMATCH")
        if(context.projectionFingerprint!=phase60Hash(projected.budget.canonicalPayload()))return fail("PROJECTION_CHANGED")
        if(currentScope()!=context.scope.temporal)return fail("STALE_SCOPE")
        val owner=option.mechanicsOwnerUid?:return fail("NON_MECHANICAL_OPTION")
        val effectKind=option.mechanicalEffectKindUid?:return fail("EFFECT_OWNER_REQUIRED")
        val oldPlan=projected.budget.candidate.plan
        if(oldPlan.campaignUid!=context.scope.temporal.campaignUid || oldPlan.audience.principal!=VisibilityPrincipalRef(context.brain.actor.kindUid,context.brain.actor.uid) ||
            oldPlan.purpose.purposeUid!=VisibilityPurposeKinds.WORLD_ACTOR_REASONING || oldPlan.steps.size!=1)return fail("PROJECTION_SCOPE")
        val target=option.target?:context.brain.actor
        val oldNode=oldPlan.intent.nodes.single()
        val reference=IntentReference("P62:TARGET",IntentReferenceKind.EXISTING_ENTITY,roleUid="TARGET",state=IntentReferenceState.RESOLVED_PROJECTED,
            resolvedProjectedRef=target,resolutionEvidenceUid=authorization.decisionUid)
        val node=IntentNode(oldNode.nodeUid,IntentForm.DIRECT_ACTION,SemanticAction(option.capabilityUid,rawPhrase=option.capabilityUid,attributes=option.parameters),
            participants=listOf(IntentParticipant("TARGET",referenceUid=reference.referenceUid)))
        val intent=oldPlan.intent.copy(rawInput=option.capabilityUid,nodes=listOf(node),references=listOf(reference),
            provenance=IntentInterpretationProvenance(IntentInterpretationSource.TRUSTED_REFERENCE_RESOLUTION,authorization.decisionUid,"1",context.contextFingerprint))
        val step=oldPlan.steps.single().copy(capabilityUid=option.capabilityUid,matchState=CapabilityMatchState.EXACT,
            executionKind=CapabilityExecutionKind.MECHANICS_PROPOSAL,sideEffectClass=CapabilitySideEffectClass.PROPOSED_WORLD_EFFECT,mechanicsOwnerUid=owner)
        val plan=oldPlan.copy(planUid=authorization.decisionUid,intent=intent,steps=listOf(step),atOrder=context.scope.temporal.baseCommitOrder)
        val core=projected.budget.candidate.core.copy(planUid=plan.planUid,intentHash=intent.canonicalFingerprint(),intentCanonicalPayload=intent.canonicalPayload(),
            planSemanticPayload=CanonicalContextPayloadCodec.plan(plan),capabilityUids=listOf(option.capabilityUid))
        val candidate=CanonicalContextCandidate(plan,core,projected.budget.includedSegments)
        val budget=SemanticContextBudgetManager().apply(candidate,ContextRuntimeProfile("P62:MECHANICS",context.maximumInputUnits,0,0,0))
        if(!budget.safeForAi)return fail("MECHANICS_CONTEXT_BUDGET")
        val request=MechanicsEffectRequest("P62:EFFECT:${authorization.decisionUid}",node.nodeUid,owner,effectKind,target,option.parameters)
        if(!authorization.authorizesMechanics(context.scope.temporal,plan,node,request))return fail("MECHANICAL_BINDING_MISMATCH")
        val resolutionContext=MechanicsResolutionContext(plan.campaignUid,plan,budget,stagedEffects,authorization)
        val verified=if(effectKind.substringAfterLast(':').uppercase()==NpcTravelAffordances.EFFECT_KIND) {
            // Fail closed until both current route and canonical actor readers are supplied.
            // Never fall back to generic LOCATION_TRANSITION, which cannot settle route costs.
            val actor=travelActors.actor(context.scope.temporal,context.brain.actor)?:return fail("TRAVEL_ACTOR_UNAVAILABLE")
            when(val result=NpcTravelMechanics.resolve(request,resolutionContext,actor,travelRoutes)) {
                is NpcTravelMechanicalResolution.Rejected -> return fail("MECHANICS:${result.reasonUid}")
                is NpcTravelMechanicalResolution.Resolved -> result.effects
            }
        } else {
            val effect=when(val result=resolver.resolve(request,resolutionContext)) {
                is MechanicsEffectResolution.Rejected -> return fail("MECHANICS:${result.reasonUid}")
                is MechanicsEffectResolution.Verified -> result.effect
            }
            if(effect.effectUid!=request.effectUid)return fail("MECHANICS_CORRELATION")
            listOf(effect)
        }
        if(verified.isEmpty() || verified.size>17 || verified.map{it.effectUid}.distinct().size!=verified.size ||
            verified.any{it.nodeUid!=node.nodeUid || it.mechanicsOwnerUid!=owner})return fail("MECHANICS_CORRELATION")
        val effects=verified.flatMap{canonicalMechanicsCommandEffects(it,target)?:return fail("MECHANICS_MATERIALIZATION")}
        val changes=effects.flatMap { effect -> when(val material=MechanicalEffectMaterializer.materialize(effect)) {
            is MechanicalEffectMaterializationResult.Rejected -> return fail("MATERIALIZATION:${material.reasonUid}")
            is MechanicalEffectMaterializationResult.Materialized -> material.changes.map{it.payload}
        } }
        if(cancellation.isCancelled())return fail("CANCELLED")
        if(currentScope()!=context.scope.temporal)return fail("STALE_SCOPE")
        val timings=Phase60DomainTiming.accepted(effects).values.distinct()
        if(timings.size>1)return fail("MECHANICAL_TIMING_CONFLICT")
        return NpcMechanicalResult.Resolved(effects,changes,timings.singleOrNull()?:option.timing,authorization)
    }
}
