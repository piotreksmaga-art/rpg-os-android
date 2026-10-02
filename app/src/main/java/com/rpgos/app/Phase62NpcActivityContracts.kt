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
    val resourceRecovery:NpcActivityResourceRecovery?=null,
    val learning:NpcLearningRule?=null,val reading:NpcReadingRule?=null,
    val requirements:NpcActivityRequirements=NpcActivityRequirements(),val treatment:NpcTreatmentRule?=null,val duty:NpcDutyRule?=null,
    /** Optional World Pack predicates. They do not award mastery, techniques or inventory. */
    val resultCriteria:List<NpcWorldResultCriterion> = emptyList()) {
    init { npcUid(capabilityUid);npcUid(ruleUid);npcUid(effortTrackUid)
        require(version>0 && duration.milliseconds in 1..86_400_000L && effortUnits>0)
        require(resultCriteria.size<=8 && resultCriteria.distinct().size==resultCriteria.size)
        require(resourceRecovery?.resourceUid!="HEALTH" || eligibility==NpcActivityEligibility.MATERIALIZED_CAPABILITY) {
            "P62:HEALTH_RECOVERY_REQUIRES_MATERIALIZED_CAPABILITY"
        }
        require(treatment==null || eligibility==NpcActivityEligibility.MATERIALIZED_CAPABILITY && learning==null && reading==null && resourceRecovery==null)
        require(traitPreferences.size<=16 && traitPreferences.map{it.traitUid}.distinct().size==traitPreferences.size && motivationalDomains.size<=16)
        require(valuePreferences.size<=16);(motivationalDomains.keys+valuePreferences.keys).forEach(::npcUid) }
    internal val fingerprint get()=phase60Hash("$capabilityUid|$ruleUid|$version|${duration.milliseconds}|$effortTrackUid|$effortUnits|${traitPreferences.sortedBy{it.traitUid}}|${motivationalDomains.toSortedMap()}"+
        (if(valuePreferences.isEmpty())"" else "|${valuePreferences.toSortedMap()}")+
        (if(eligibility==NpcActivityEligibility.MATERIALIZED_CAPABILITY)"" else "|eligibility=${eligibility.name}")+
        (resourceRecovery?.let{"|recovery=${it.resourceUid}|units=${it.maximumUnits}"}?:"")+
        (learning?.let{"|learning=${it.fingerprint}"}?:"")+(reading?.let{"|reading=${it.fingerprint}"}?:"")+
        (if(requirements==NpcActivityRequirements())"" else "|requirements=${requirements.fingerprint}")+
        (treatment?.let{"|treatment=${it.fingerprint}"}?:"")+(duty?.let{"|duty=${it.fingerprint}"}?:"")+
        (if(resultCriteria.isEmpty())"" else "|results=${resultCriteria}"))
}

fun interface NpcActivityContractPort {
    fun contract(campaignUid:String,capabilityUid:String):NpcActivityContract?
    fun forCapability(campaignUid:String,capabilityUid:String):List<NpcActivityContract> = listOfNotNull(contract(campaignUid,capabilityUid))
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
    /** Reuse the lifecycle/result-owner contract introduced for travel. This adapter may
     * coordinate several domain changes, but the downstream Phase21/37/50 owners still
     * validate and commit them; the contract itself cannot award a result. */
    fun ownerContract(contract:NpcActivityContract)=NpcActivityOwnerContract(
        contractUid="P62:ACTIVITY_RESULT:${contract.ruleUid}",version=contract.version,
        lifecycleOwnerUid=NpcActionProcess.OWNER,resultOwnerUid=OWNER,
        evidenceKindUid="P62:ACTIVITY_DOMAIN_RECEIPT",
        resultPolicy=if(contract.learning!=null || contract.reading!=null || contract.treatment!=null || contract.duty!=null || contract.resourceRecovery!=null)
            NpcActivityResultPolicy.DOMAIN_RESULT_EVIDENCE else NpcActivityResultPolicy.ATTEMPT_EVIDENCE,
        allowedCanonicalChangeKindUids=buildSet {
            add(PlayerChangeKinds.MECHANICAL_TRACK)
            contract.learning?.let{add(if(it.targetKindUid==ProgressionTargetKinds.SKILL)PlayerChangeKinds.SKILL else PlayerChangeKinds.TECHNIQUE)}
            if(contract.reading!=null)add(PHASE37_KNOWLEDGE_CHANGE_KIND)
            if(contract.resourceRecovery!=null || contract.requirements.resourceCosts.isNotEmpty() || contract.treatment?.resourceRecovery?.isNotEmpty()==true)add(PlayerChangeKinds.RESOURCE)
            contract.treatment?.let{if(it.woundHealingUnits>0)add(PlayerChangeKinds.WOUND)
                if(it.removedConditionUids.isNotEmpty() || it.stabilizationConditionUid!=null)add(PlayerChangeKinds.CONDITION)}
        })
    fun available(actor:MechanicalActorView,contract:NpcActivityContract):Boolean =
        actor.materialization==MechanicalStateMaterialization.FULL && actor.kind in setOf(MechanicalActorKind.NPC,
            MechanicalActorKind.MONSTER,MechanicalActorKind.SUMMON,MechanicalActorKind.FORMER_PLAYER) &&
            (contract.eligibility==NpcActivityEligibility.CONSCIOUS_SELF || contract.capabilityUid in actor.executableAbilityUids) && actor.conditions.none{
                it.intensity>0 && it.conditionUid.uppercase() in setOf("DEAD","UNCONSCIOUS","INCAPACITATED")} &&
            actor.resources.none{it.resourceUid=="HEALTH" && it.current==0L} &&
            (contract.resourceRecovery==null || actor.resources.count{it.resourceUid==contract.resourceRecovery.resourceUid}==1) &&
            contract.requirements.resourceCosts.all{(uid,cost)->actor.resources.singleOrNull{it.resourceUid==uid}?.current?.let{it>=cost}==true}

    fun option(brain:NpcBrainState,goal:NpcGoal,evidence:NpcKnownRecord?,contract:NpcActivityContract,target:DomainRef=brain.actor)=NpcActionOption(
        "P62:OPTION:${phase60Hash("${brain.actor}|${goal.uid}|${contract.fingerprint}"+(if(target==brain.actor)"" else "|$target")).take(32)}",contract.capabilityUid,target,
        AcceptedActionTiming(contract.duration,TIMING_RULE,contract.version),goal.uid,contract.traitPreferences.filter{it.traitUid in brain.personality},setOfNotNull(evidence?.uid),
        parameters=mapOf("contract_fingerprint" to contract.fingerprint),mechanicsOwnerUid=OWNER,mechanicalEffectKindUid="INTERACTION",
        resourceCosts=contract.requirements.resourceCosts,
        motivationAlignment=brain.motivations.mapNotNull{m->contract.motivationalDomains[m.domainUid]?.let{m.uid to it}}.toMap(),
        valueAlignment=contract.valuePreferences.filterKeys{it in brain.values},
        roleAlignment=contract.duty?.let{mapOf(it.roleUid to NpcAffect(10000))}?:emptyMap())

    fun resolve(request:MechanicsEffectRequest,context:MechanicsResolutionContext,actor:MechanicalActorView,
                contract:NpcActivityContract,learningState:NpcLearningStatePort=NpcLearningStatePort.NONE,
                readingAccess:NpcReadingAccessPort=NpcReadingAccessPort.NONE,
                requirements:NpcActivityRequirementPort=NpcActivityRequirementPort.NONE,
                treatments:NpcTreatmentReadPort=NpcTreatmentReadPort.NONE,
                duties:NpcDutyAssignmentPort=NpcDutyAssignmentPort.NONE):MechanicsEffectResolution {
        fun fail(reason:String)=MechanicsEffectResolution.Rejected("P62:$reason")
        val authorization=context.npcAuthorization?:return fail("ACTIVITY_AUTHORIZATION_REQUIRED")
        val node=context.plan.intent.nodes.singleOrNull()?:return fail("ACTIVITY_SINGLE_NODE_REQUIRED")
        if(!authorization.authorizesMechanics(authorization.scope.temporal,context.plan,node,request) ||
            actor.campaignUid!=context.campaignUid || actor.actor!=authorization.scope.actor ||
            !available(actor,contract) || (contract.treatment==null && request.targetProjectedRef!=actor.actor) ||
            request.mechanicsOwnerUid!=OWNER || request.effectKindUid!="INTERACTION" ||
            node.semanticAction.canonicalActionUid!=contract.capabilityUid ||
            request.parameters!=mapOf("contract_fingerprint" to contract.fingerprint))return fail("ACTIVITY_CONTRACT_CHANGED")
        val learning=contract.learning?.let { rule ->
            val state=learningState.state(context.campaignUid,actor.actor,rule)
            if(!NpcLearningApplication.admitted(rule,state))return fail("LEARNING_REQUIREMENTS_CHANGED")
            requireNotNull(state)
        }
        if(contract.reading?.let{!readingAccess.accessible(context.campaignUid,actor.actor,it)}==true)return fail("READING_SOURCE_NOT_ACCESSIBLE")
        if(!requirements.admitted(context.campaignUid,actor.actor,contract.requirements))return fail("ACTIVITY_REQUIREMENTS_CHANGED")
        contract.duty?.let{rule->
            if(authorization.scope.atTime>rule.due || !duties.admitted(context.campaignUid,actor.actor,rule,authorization.scope.atTime))
                return fail("DUTY_ASSIGNMENT_CHANGED")
            if(context.stagedEffects.any{it.canonicalPayload["npc_duty_uid"]==rule.dutyUid && it.canonicalPayload["target_uid"]==actor.actor.uid})
                return fail("DUTY_ALREADY_COMPLETED_IN_TURN")
        }
        val treatment=contract.treatment?.let{rule->
            val target=request.targetProjectedRef?:return fail("TREATMENT_PATIENT_REQUIRED")
            val patient=treatments.patient(context.campaignUid,actor.actor,target,rule,context.stagedEffects)
                ?:return fail("TREATMENT_PATIENT_NOT_ACCESSIBLE")
            if(patient.campaignUid!=context.campaignUid || patient.actor!=target || patient.materialization!=MechanicalStateMaterialization.FULL)
                return fail("TREATMENT_PATIENT_STATE_MISMATCH")
            NpcTreatmentApplication.settle(actor,patient,rule)
        }
        val track=contract.learning?.let{"${contract.effortTrackUid}:${it.targetKindUid}:${it.targetUid}:${contract.ruleUid}"}
            ?:contract.reading?.let{"${contract.effortTrackUid}:${it.claim.claimUid}:${contract.ruleUid}"}?:contract.effortTrackUid
        val payload=mutableMapOf("track_uid" to track,"magnitude" to contract.effortUnits.toString(),
            "target_kind_uid" to actor.actor.kindUid,"target_uid" to actor.actor.uid,
            "p60_core_timing_rule" to TIMING_RULE,"p60_core_timing_version" to contract.version.toString(),
            "p60_core_duration_ms" to contract.duration.milliseconds.toString(),"p60_core_effect_at_ms" to contract.duration.milliseconds.toString(),
            "npc_activity_rule_uid" to contract.ruleUid,"npc_activity_contract" to contract.fingerprint,
            "npc_activity_owner_contract" to ownerContract(contract).fingerprint)
        learning?.let{payload.putAll(NpcLearningApplication.fields(context.campaignUid,actor.actor,contract,it))}
        contract.reading?.let{payload.putAll(NpcReadingApplication.fields(context.campaignUid,actor.actor,contract))}
        treatment?.let{
            payload["npc_treatment_contract"]=requireNotNull(contract.treatment).fingerprint
            payload["npc_treatment_outcome"]=it.outcome.name;payload["npc_treatment_reason"]=it.reasonUid
        }
        contract.duty?.let{rule->
            payload["track_uid"]=rule.completionTrack
            payload["npc_duty_uid"]=rule.dutyUid;payload["npc_duty_contract"]=rule.fingerprint
            payload["npc_duty_version"]=rule.version.toString();payload["npc_duty_deadline"]=rule.deadlineUid
        }
        val resourceDeltas=(if(treatment?.reasonUid=="P62:TREATMENT_NO_LONGER_NEEDED")emptyMap() else contract.requirements.resourceCosts)
            .mapValues{Math.negateExact(it.value)}.toMutableMap()
        contract.resourceRecovery?.let { rule ->
            val resource=actor.resources.single{it.resourceUid==rule.resourceUid}
            val gain=minOf(rule.maximumUnits,Math.subtractExact(resource.maximum,resource.current))
            if(gain>0) {
                // Reuse the existing mixed-effect expansion. Entry zero is the effort, entry one
                // the resource change; the top-level summary is not a third effect. Recomputed
                // at completion from the fresh staged actor, never persisted as a future reward.
                resourceDeltas[rule.resourceUid]=Math.addExact(resourceDeltas[rule.resourceUid]?:0,gain)
            }
        }
        val deltas=resourceDeltas.filterValues{it!=0L}.toSortedMap()
        // Costs and recovery of the same self resource form ONE owner change, not two conflicting
        // changes whose application order might reject an otherwise legal treatment.
        val rawImpacts=deltas.entries.map{(uid,units)->NpcTreatmentImpact(actor.actor,"RESOURCE_DELTA",units,mapOf("resource_uid" to uid))}+treatment?.impacts.orEmpty()
        val resourceImpacts=rawImpacts.filter{it.kind=="RESOURCE_DELTA"}.groupBy{it.target to it.fields.getValue("resource_uid")}
            .mapNotNull { (key,entries) ->
                val units=entries.fold(0L){sum,entry->Math.addExact(sum,entry.units)}
                if(units==0L)null else NpcTreatmentImpact(key.first,"RESOURCE_DELTA",units,mapOf("resource_uid" to key.second))
            }.sortedWith(compareBy<NpcTreatmentImpact>{it.target.kindUid}.thenBy{it.target.uid}.thenBy{it.fields.getValue("resource_uid")})
        val impacts=resourceImpacts+rawImpacts.filter{it.kind!="RESOURCE_DELTA"}
        val owner=ownerContract(contract)
        require(impacts.all{impact->when(impact.kind) {
            "RESOURCE_DELTA"->PlayerChangeKinds.RESOURCE
            "WOUND_HEALING"->PlayerChangeKinds.WOUND
            "CONDITION"->PlayerChangeKinds.CONDITION
            else->error("P62:ACTIVITY_RESULT_KIND_UNREGISTERED")
        } in owner.allowedCanonicalChangeKindUids}) { "P62:ACTIVITY_RESULT_OUTSIDE_OWNER_CONTRACT" }
        if(impacts.isNotEmpty()) {
            payload["area_target_count"]=(1+impacts.size).toString()
            payload["area_target_0_kind_uid"]=actor.actor.kindUid;payload["area_target_0_uid"]=actor.actor.uid
            payload["area_target_0_effect_kind_uid"]="INTERACTION";payload["area_target_0_magnitude"]=contract.effortUnits.toString()
            impacts.forEachIndexed { ordinal,impact->val i=ordinal+1
                payload["area_target_${i}_kind_uid"]=impact.target.kindUid;payload["area_target_${i}_uid"]=impact.target.uid
                payload["area_target_${i}_effect_kind_uid"]=impact.kind;payload["area_target_${i}_magnitude"]=impact.units.toString()
                impact.fields.forEach{(key,value)->payload["area_target_${i}_$key"]=value}
            }
        }
        val input=phase60Hash("${authorization.contextFingerprint}|${actor.stateVersion}|${contract.fingerprint}|${context.stagedEffects}")
        val output=phase60Hash(payload.toSortedMap().toString())
        return MechanicsEffectResolution.Verified(VerifiedMechanicsEffect(request.effectUid,request.nodeUid,OWNER,"INTERACTION",payload,
            "P62:ACTIVITY:${contract.fingerprint}:${phase60Hash(input+output)}",input,output))
    }
}

/** Merely recording effort on an addressed NPC must not cancel the player's conversation.
 * Real physical changes still reach the ordinary Phase60 player decision boundary. */
internal fun npcRequiresForegroundDecision(effects:List<VerifiedMechanicsCommandEffect>,subjects:Set<DomainRef>):Boolean =
    effects.any{it.target in subjects && !(it.mechanicsOwnerUid==NpcActivityMechanics.OWNER && it.effectKindUid=="INTERACTION")}
