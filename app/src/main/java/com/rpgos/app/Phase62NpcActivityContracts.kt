package com.rpgos.app

/** A registered unit of activity, not a claim that its objective succeeded. For example training
 * records performed effort; skill awards still belong to progression, rest does not grant HP.
 * Additional domains register their own immutable contracts, not a verb-to-damage fallback. */
enum class NpcActivityEligibility { MATERIALIZED_CAPABILITY, CONSCIOUS_SELF }

/** Explicit self-resource restoration per COMPLETED action. This is a Core rule, not a model
 * estimate or a physiology default read from an absent recovery_profiles row. Resource ownership,
 * limits and persistence remain Phase50. It neither removes wounds nor awards skills/knowledge. */
data class NpcActivityResourceRecovery(val resourceUid:String,val maximumUnits:Long) {
    init { npcUid(resourceUid);require(maximumUnits>0) }
}

data class NpcActivityContract(val capabilityUid:String,val ruleUid:String,val version:Int,
    val duration:ActionDuration,val effortTrackUid:String,val effortUnits:Long=1,
    val traitPreferences:List<NpcTraitPreference> = emptyList(),val motivationalDomains:Map<String,NpcAffect> = emptyMap(),
    val valuePreferences:Map<String,NpcAffect> = emptyMap(),
    val eligibility:NpcActivityEligibility=NpcActivityEligibility.MATERIALIZED_CAPABILITY,
    val resourceRecovery:NpcActivityResourceRecovery?=null) {
    init { npcUid(capabilityUid);npcUid(ruleUid);npcUid(effortTrackUid)
        require(version>0 && duration.milliseconds in 1..86_400_000L && effortUnits>0)
        require(resourceRecovery?.resourceUid!="HEALTH" || eligibility==NpcActivityEligibility.MATERIALIZED_CAPABILITY) {
            "P62:HEALTH_RECOVERY_REQUIRES_MATERIALIZED_CAPABILITY"
        }
        require(traitPreferences.size<=16 && traitPreferences.map{it.traitUid}.distinct().size==traitPreferences.size && motivationalDomains.size<=16)
        require(valuePreferences.size<=16);(motivationalDomains.keys+valuePreferences.keys).forEach(::npcUid) }
    internal val fingerprint get()=phase60Hash("$capabilityUid|$ruleUid|$version|${duration.milliseconds}|$effortTrackUid|$effortUnits|${traitPreferences.sortedBy{it.traitUid}}|${motivationalDomains.toSortedMap()}"+
        (if(valuePreferences.isEmpty())"" else "|${valuePreferences.toSortedMap()}")+
        (if(eligibility==NpcActivityEligibility.MATERIALIZED_CAPABILITY)"" else "|eligibility=${eligibility.name}")+
        (resourceRecovery?.let{"|recovery=${it.resourceUid}|units=${it.maximumUnits}"}?:""))
}

fun interface NpcActivityContractPort {
    fun contract(campaignUid:String,capabilityUid:String):NpcActivityContract?
    /** Explicit Core rules for basic self activity, not learned techniques or world knowledge. */
    fun inherent(campaignUid:String):List<NpcActivityContract> = emptyList()
    companion object {
        val NONE=NpcActivityContractPort{_,_->null}
        // Explicit bounded attempts, not complete recovery, knowledge acquisition or promotion.
        val STANDARD=registered(listOf(
            NpcActivityContract("WAIT","P62:WAIT_QUANTUM",1,ActionDuration(1000),"ACTION:WAIT",
                traitPreferences=listOf(NpcTraitPreference("CAUTION",NpcWeight(8000),NpcWeight(2000))),
                eligibility=NpcActivityEligibility.CONSCIOUS_SELF),
            NpcActivityContract("REST","P62:REST_STAMINA",2,ActionDuration(360000),"ACTION:REST",
                motivationalDomains=mapOf("EXISTENCE" to NpcAffect(8000)),eligibility=NpcActivityEligibility.CONSCIOUS_SELF,
                resourceRecovery=NpcActivityResourceRecovery("STAMINA",1)),
            NpcActivityContract("TRAIN","P62:TRAINING_EFFORT",1,ActionDuration(60000),"TRAINING:GENERAL",
                traitPreferences=listOf(NpcTraitPreference("PERSISTENCE",NpcWeight(10000),NpcWeight(3000)),NpcTraitPreference("CURIOSITY",NpcWeight(10000),NpcWeight(3000))),
                motivationalDomains=mapOf("KNOWLEDGE" to NpcAffect(6000)),valuePreferences=mapOf("DISCIPLINE" to NpcAffect(5000))),
            NpcActivityContract("PRACTICE","P62:PRACTICE_EFFORT",1,ActionDuration(60000),"TRAINING:GENERAL",
                traitPreferences=listOf(NpcTraitPreference("PERSISTENCE",NpcWeight(10000),NpcWeight(6000))),
                motivationalDomains=mapOf("KNOWLEDGE" to NpcAffect(6000)),valuePreferences=mapOf("DISCIPLINE" to NpcAffect(5000)))
        ))
        fun registered(contracts:List<NpcActivityContract>):NpcActivityContractPort {
            require(contracts.map{it.capabilityUid}.distinct().size==contracts.size)
            val snapshot=contracts.map{it.copy(traitPreferences=it.traitPreferences.toList(),
                motivationalDomains=it.motivationalDomains.toMap(),valuePreferences=it.valuePreferences.toMap())}.associateBy{it.capabilityUid}
            return object:NpcActivityContractPort {
                override fun contract(campaignUid:String,capabilityUid:String)=snapshot[capabilityUid]
                override fun inherent(campaignUid:String)=snapshot.values.filter{it.eligibility==NpcActivityEligibility.CONSCIOUS_SELF}.sortedBy{it.capabilityUid}
            }
        }
    }
}

internal object NpcActivityMechanics {
    const val OWNER="NPC_REGISTERED_ACTIVITY"
    const val TIMING_RULE="P62:REGISTERED_ACTIVITY_MS_V1"
    fun available(actor:MechanicalActorView,contract:NpcActivityContract):Boolean =
        actor.materialization==MechanicalStateMaterialization.FULL && actor.kind in setOf(MechanicalActorKind.NPC,
            MechanicalActorKind.MONSTER,MechanicalActorKind.SUMMON,MechanicalActorKind.FORMER_PLAYER) &&
            (contract.eligibility==NpcActivityEligibility.CONSCIOUS_SELF || contract.capabilityUid in actor.executableAbilityUids) && actor.conditions.none{
                it.intensity>0 && it.conditionUid.uppercase() in setOf("DEAD","UNCONSCIOUS","INCAPACITATED")} &&
            actor.resources.none{it.resourceUid=="HEALTH" && it.current==0L} &&
            (contract.resourceRecovery==null || actor.resources.count{it.resourceUid==contract.resourceRecovery.resourceUid}==1)

    fun option(brain:NpcBrainState,goal:NpcGoal,evidence:NpcKnownRecord?,contract:NpcActivityContract)=NpcActionOption(
        "P62:OPTION:${phase60Hash("${brain.actor}|${goal.uid}|${contract.fingerprint}").take(32)}",contract.capabilityUid,brain.actor,
        AcceptedActionTiming(contract.duration,TIMING_RULE,contract.version),goal.uid,contract.traitPreferences.filter{it.traitUid in brain.personality},setOfNotNull(evidence?.uid),
        parameters=mapOf("contract_fingerprint" to contract.fingerprint),mechanicsOwnerUid=OWNER,mechanicalEffectKindUid="INTERACTION",
        motivationAlignment=brain.motivations.mapNotNull{m->contract.motivationalDomains[m.domainUid]?.let{m.uid to it}}.toMap(),
        valueAlignment=contract.valuePreferences.filterKeys{it in brain.values})

    fun resolve(request:MechanicsEffectRequest,context:MechanicsResolutionContext,actor:MechanicalActorView,
                contract:NpcActivityContract):MechanicsEffectResolution {
        fun fail(reason:String)=MechanicsEffectResolution.Rejected("P62:$reason")
        val authorization=context.npcAuthorization?:return fail("ACTIVITY_AUTHORIZATION_REQUIRED")
        val node=context.plan.intent.nodes.singleOrNull()?:return fail("ACTIVITY_SINGLE_NODE_REQUIRED")
        if(!authorization.authorizesMechanics(authorization.scope.temporal,context.plan,node,request) ||
            actor.campaignUid!=context.campaignUid || actor.actor!=authorization.scope.actor ||
            !available(actor,contract) || request.targetProjectedRef!=actor.actor ||
            request.mechanicsOwnerUid!=OWNER || request.effectKindUid!="INTERACTION" ||
            node.semanticAction.canonicalActionUid!=contract.capabilityUid ||
            request.parameters!=mapOf("contract_fingerprint" to contract.fingerprint))return fail("ACTIVITY_CONTRACT_CHANGED")
        val payload=mutableMapOf("track_uid" to contract.effortTrackUid,"magnitude" to contract.effortUnits.toString(),
            "target_kind_uid" to actor.actor.kindUid,"target_uid" to actor.actor.uid,
            "p60_core_timing_rule" to TIMING_RULE,"p60_core_timing_version" to contract.version.toString(),
            "p60_core_duration_ms" to contract.duration.milliseconds.toString(),"p60_core_effect_at_ms" to contract.duration.milliseconds.toString(),
            "npc_activity_rule_uid" to contract.ruleUid,"npc_activity_contract" to contract.fingerprint)
        contract.resourceRecovery?.let { rule ->
            val resource=actor.resources.single{it.resourceUid==rule.resourceUid}
            val gain=minOf(rule.maximumUnits,Math.subtractExact(resource.maximum,resource.current))
            if(gain>0) {
                // Reuse the existing mixed-effect expansion. Entry zero is the effort, entry one
                // the resource change; the top-level summary is not a third effect. Recomputed
                // at completion from the fresh staged actor, never persisted as a future reward.
                payload["resource_uid"]=rule.resourceUid
                payload["area_target_count"]="2"
                listOf("INTERACTION" to contract.effortUnits,"RESOURCE_DELTA" to gain).forEachIndexed { index,(kind,units) ->
                    payload["area_target_${index}_kind_uid"]=actor.actor.kindUid
                    payload["area_target_${index}_uid"]=actor.actor.uid
                    payload["area_target_${index}_effect_kind_uid"]=kind
                    payload["area_target_${index}_magnitude"]=units.toString()
                }
            }
        }
        val input=phase60Hash("${authorization.contextFingerprint}|${actor.stateVersion}|${contract.fingerprint}|${context.stagedEffects}")
        val output=phase60Hash(payload.toSortedMap().toString())
        return MechanicsEffectResolution.Verified(VerifiedMechanicsEffect(request.effectUid,request.nodeUid,OWNER,"INTERACTION",payload,
            "P62:ACTIVITY:${phase60Hash(input+output)}",input,output))
    }
}

/** Merely recording effort on an addressed NPC must not cancel the player's conversation.
 * Real physical changes still reach the ordinary Phase60 player decision boundary. */
internal fun npcRequiresForegroundDecision(effects:List<VerifiedMechanicsCommandEffect>,subjects:Set<DomainRef>):Boolean =
    effects.any{it.target in subjects && !(it.mechanicsOwnerUid==NpcActivityMechanics.OWNER && it.effectKindUid=="INTERACTION")}
