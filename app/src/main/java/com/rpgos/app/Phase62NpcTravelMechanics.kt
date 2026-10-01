package com.rpgos.app

/** Read current canonical self state for the exact temporal scope; never infer a missing body. */
internal fun interface NpcTravelActorReadPort {
    fun actor(scope:TemporalScope,actor:DomainRef):MechanicalActorView?
    companion object { val NONE=NpcTravelActorReadPort { _,_->null } }
}

internal sealed interface NpcTravelMechanicalResolution {
    data class Resolved(val effects:List<VerifiedMechanicsEffect>):NpcTravelMechanicalResolution
    data class Rejected(val reasonUid:String):NpcTravelMechanicalResolution
}

/** Pure domain resolution. Preflight outputs are discarded; completion recomputes them.
 * Costs and arrival are admitted together through the existing Phase50 materializer/transaction.
 * This settlement policy charges on successful completion only; interruption charges nothing.
 * It does not model incremental route segments or declare an uncommitted effect a receipt. */
internal object NpcTravelMechanics {
    const val OWNER="UNIVERSAL_MOVEMENT"
    const val TIMING_RULE="P62:TRAVEL_ROUTE_MS_V1"

    fun timing(route:NpcTravelRouteContract)=AcceptedActionTiming(route.duration,TIMING_RULE,route.version)
    fun parameters(route:NpcTravelRouteContract)=mapOf(
        "route_uid" to route.routeUid,"route_version" to route.version.toString(),
        "route_fingerprint" to route.fingerprint,"destination_kind_uid" to route.destination.kindUid,
        "destination_uid" to route.destination.uid
    )

    fun available(actor:MechanicalActorView,route:NpcTravelRouteContract):Boolean =
        actor.campaignUid==route.campaignUid && actor.locationRef==route.origin &&
        actor.materialization==MechanicalStateMaterialization.FULL &&
        actor.kind in setOf(MechanicalActorKind.NPC,MechanicalActorKind.MONSTER,MechanicalActorKind.SUMMON,MechanicalActorKind.FORMER_PLAYER) &&
        route.mechanicsOwnerUid==OWNER &&
        (route.eligibility==NpcActivityEligibility.CONSCIOUS_SELF || route.capabilityUid in actor.executableAbilityUids) &&
        actor.conditions.none{it.intensity>0 && it.conditionUid.uppercase() in setOf("DEAD","UNCONSCIOUS","INCAPACITATED")} &&
        actor.resources.none{it.resourceUid=="HEALTH" && it.current==0L} &&
        route.resourceCosts.all{(uid,cost)->cost==0L || actor.resources.singleOrNull{it.resourceUid==uid}?.current?.let{it>=cost}==true}

    fun resolve(request:MechanicsEffectRequest,context:MechanicsResolutionContext,
                canonicalActor:MechanicalActorView,routes:NpcTravelRoutePort):NpcTravelMechanicalResolution {
        fun fail(reason:String)=NpcTravelMechanicalResolution.Rejected("P62:$reason")
        val authorization=context.npcAuthorization?:return fail("TRAVEL_AUTHORIZATION_REQUIRED")
        val node=context.plan.intent.nodes.singleOrNull()?:return fail("TRAVEL_SINGLE_NODE_REQUIRED")
        if(request.mechanicsOwnerUid!=OWNER || request.effectKindUid!=NpcTravelAffordances.EFFECT_KIND ||
            !authorization.authorizesMechanics(authorization.scope.temporal,context.plan,node,request))return fail("TRAVEL_AUTHORIZATION_MISMATCH")
        if(canonicalActor.campaignUid!=context.campaignUid || canonicalActor.actor!=authorization.scope.actor ||
            canonicalActor.actor.uid==authorization.scope.activePlayerUid)return fail("TRAVEL_ACTOR_SCOPE")
        // A cached origin cannot authorize travel after another movement in this same turn.
        // Precise staged routes require a later spatial-owner projection, not guessed coordinates.
        if(StagedMechanicalProjection.hasSpatialChange(setOf(canonicalActor.actor),context.stagedEffects))return fail("TRAVEL_ORIGIN_CHANGED_IN_TURN")
        val origin=canonicalActor.locationRef?:return fail("TRAVEL_ORIGIN_UNKNOWN")
        val current=routes.routes(context.campaignUid,canonicalActor.actor,origin)
        if(current.size>1024)return fail("TRAVEL_ROUTE_BUDGET")
        val route=current.singleOrNull{it.campaignUid==context.campaignUid && it.origin==origin &&
            it.routeUid==request.parameters["route_uid"] && it.version.toString()==request.parameters["route_version"]}
            ?.let{it.copy(resourceCosts=it.resourceCosts.toMap())}?:return fail("TRAVEL_ROUTE_UNAVAILABLE")
        if(request.parameters!=parameters(route) || request.targetProjectedRef!=route.destination ||
            node.semanticAction.canonicalActionUid!=route.capabilityUid)return fail("TRAVEL_CONTRACT_CHANGED")
        val actor=StagedMechanicalProjection.actor(canonicalActor,context.stagedEffects)
        if(!available(actor,route))return fail("TRAVEL_NOT_EXECUTABLE")
        val input=phase60Hash("${authorization.contextFingerprint}|${context.plan.intent.canonicalFingerprint()}|${actor}|${route.fingerprint}|${context.stagedEffects}")
        fun effect(uid:String,kind:String,magnitude:Long,fields:Map<String,String>):VerifiedMechanicsEffect {
            val payload=fields+mapOf(
                "target_kind_uid" to actor.actor.kindUid,"target_uid" to actor.actor.uid,"magnitude" to magnitude.toString(),
                "p60_core_timing_rule" to TIMING_RULE,"p60_core_timing_version" to route.version.toString(),
                "p60_core_duration_ms" to route.duration.milliseconds.toString(),"p60_core_effect_at_ms" to route.duration.milliseconds.toString(),
                "npc_travel_route_uid" to route.routeUid,"npc_travel_contract" to route.fingerprint,
                "npc_travel_rule_uid" to route.timingRuleUid,"npc_travel_origin_uid" to route.origin.uid,
                "npc_travel_settlement" to "COMPLETION_ONLY_V1"
            )
            val output=phase60Hash("$uid|$kind|${payload.toSortedMap()}")
            return VerifiedMechanicsEffect(uid,request.nodeUid,OWNER,kind,payload,"P62:TRAVEL:${phase60Hash(input+output)}",input,output)
        }
        val effects=route.resourceCosts.toSortedMap().filterValues{it>0}.map{(uid,cost)->
            effect("${request.effectUid}:COST:${phase60Hash(uid).take(16)}","RESOURCE_DELTA",Math.negateExact(cost),
                mapOf("resource_uid" to uid,"npc_travel_effect_role" to "COST"))
        }+effect(request.effectUid,NpcTravelAffordances.EFFECT_KIND,1,
            mapOf("destination_kind_uid" to route.destination.kindUid,"destination_uid" to route.destination.uid,"npc_travel_effect_role" to "ARRIVAL"))
        return NpcTravelMechanicalResolution.Resolved(effects)
    }
}
