package com.rpgos.app

/** A Core route result, never an AI-supplied duration, cost or teleport destination. */
internal fun interface WorldTravelMechanicsPort {
    fun resolve(request:MechanicsEffectRequest,context:MechanicsResolutionContext):MechanicsEffectResolution
}

internal object WorldTravelMechanics {
    const val TIMING_RULE="P63:ROUTE_MS_V1"
    fun verified(request:MechanicsEffectRequest,context:MechanicsResolutionContext,actor:MechanicalActorView,
                 plan:WorldTravelPlan):MechanicsEffectResolution {
        fun reject(reason:String)=MechanicsEffectResolution.Rejected("P63:$reason")
        if(actor.campaignUid!=context.campaignUid || actor.locationRef?.uid!=plan.origin.uid)return reject("TRAVEL_ORIGIN_CHANGED")
        if(actor.conditions.any { it.intensity>0 && it.conditionUid in setOf("DEAD","UNCONSCIOUS","INCAPACITATED") } ||
            actor.resources.any { it.resourceUid=="HEALTH" && it.current==0L })return reject("TRAVEL_ACTOR_INCAPACITATED")
        if(!plan.edges.all { actor.executableAbilityUids.containsAll(it.requiredCapabilities) })return reject("TRAVEL_CAPABILITY_REQUIRED")
        if(!plan.resourceCosts.all { (uid,cost)->actor.resources.any { it.resourceUid==uid && it.current>=cost } })
            return reject("TRAVEL_RESOURCE_REQUIRED")
        if(StagedMechanicalProjection.hasSceneTransition(setOf(actor.actor),context.stagedEffects))return reject("TRAVEL_STAGED_ORIGIN_CHANGED")
        val duration=plan.duration.milliseconds
        val payload=buildMap {
            put("target_kind_uid",actor.actor.kindUid);put("target_uid",actor.actor.uid);put("magnitude","1")
            put("destination_kind_uid",plan.destination.kindUid);put("destination_uid",plan.destination.uid)
            put("p60_core_timing_rule",TIMING_RULE);put("p60_core_timing_version","1")
            put("p60_core_duration_ms",duration.toString());put("p60_core_effect_at_ms",duration.toString())
            put("p63_route_fingerprint",plan.fingerprint);put("p63_route_origin",plan.origin.uid)
            if(plan.resourceCosts.isNotEmpty()) {
                require(plan.resourceCosts.size<=32)
                put("area_target_count",(plan.resourceCosts.size+1).toString())
                val specifications=plan.resourceCosts.toSortedMap().map { (uid,cost)->Triple("RESOURCE_DELTA",Math.negateExact(cost),uid) }+
                    Triple("LOCATION_TRANSITION",1L,null)
                specifications.forEachIndexed { index,(kind,units,resource)->
                    put("area_target_${index}_kind_uid",actor.actor.kindUid);put("area_target_${index}_uid",actor.actor.uid)
                    put("area_target_${index}_magnitude",units.toString());put("area_target_${index}_effect_kind_uid",kind)
                    resource?.let { put("area_target_${index}_resource_uid",it) }
                }
            }
        }
        val input=phase63Hash("${context.plan.intent.canonicalFingerprint()}|${context.stagedEffects}|${actor}|${plan.fingerprint}")
        val output=phase63Hash(payload.toSortedMap().toString())
        return MechanicsEffectResolution.Verified(VerifiedMechanicsEffect(request.effectUid,request.nodeUid,"UNIVERSAL_MOVEMENT",
            "LOCATION_TRANSITION",payload,"P63:TRAVEL:${plan.fingerprint}:$output",input,output))
    }
}

internal object WorldRouteKnowledge {
    fun claimUid(edge:WorldTopologyEdge)="P63:ROUTE-CLAIM:${edge.fingerprint}"
    fun materialize(campaign:String,command:String,order:Long,actor:CommandActorRef,changes:List<WorldSimulationChange>):NpcActionMemory.Draft {
        val holder=KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER,actor.actorUid,campaign)
        val knowledge=changes.flatMap { it.edges }.filter { it.provenanceUid.startsWith("P63:LOCAL-CONNECTION:") }.map { edge->
            val id=phase63Hash("$campaign|$command|${edge.fingerprint}|${actor.actorUid}")
            val value=KnowledgeAcquisitionChange(KnowledgeClaim(claimUid(edge),"WORLD_ROUTE",edge.uid,"P63:OBSERVED_CONNECTION",
                edge.fingerprint,edge.destination.kindUid,edge.destination.uid,KnowledgeDomains.GEOGRAPHY),
                KnowledgeAcquisitionSpec("P63:ROUTE-ACQ:$id",holder,KnowledgeAcquisitionMethods.DIRECT_OBSERVATION,
                    KnowledgeScope.PERSONAL,KnowledgeEpistemicState.KNOWN,KnowledgeQuality(1.0,1.0,1.0,1.0,1,order)),
                listOf(KnowledgeEvidenceSpec("P63:ROUTE-EVIDENCE:$id","P63:REGISTERED_VISIBLE_LOCAL_CONNECTION",KnowledgeEvidencePolarity.SUPPORTS,
                    sourceRef=KnowledgeSourceRef.campaign(campaign,"WORLD_ROUTE",edge.uid))))
            PlayerDomainChange.create("P63:ROUTE-KNOWLEDGE:$id",PHASE37_KNOWLEDGE_CHANGE_KIND,value,sourceRuleUid=edge.provenanceUid)
        }
        val events=knowledge.map { change->PlayerEventIntent.create("EVENT:${change.changeUid}",PlayerEventIntentKinds.DOMAIN_EFFECT,
            DomainRef(actor.actorKindUid,actor.actorUid),listOf(DomainRef(holder.holderKindUid,holder.holderUid)),listOf(change.changeUid),
            DomainEffectEventIntentPayload(DomainRef(holder.holderKindUid,holder.holderUid),"RPGOS-EFFECT:KNOWLEDGE_ACQUISITION")) }
        return NpcActionMemory.Draft(knowledge,events)
    }
}
