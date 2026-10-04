package com.rpgos.app

internal fun combatAbilityIdentity(node:IntentNode,autonomous:Boolean):String? =
    (node.semanticAction.canonicalActionUid?:node.semanticAction.semanticFamilyUid)?.let{if(autonomous)it else it.uppercase()}

/** Contract changes invalidate pending choices, including changes to costs, range or effects. */
internal fun npcCombatContractFingerprint(contract:CombatAbilityContract):String=phase60Hash("P62:ABILITY:1|"+
    contract.copy(requiredEquipmentKinds=contract.requiredEquipmentKinds.toSortedSet(),targetKindUids=contract.targetKindUids.toSortedSet()).toString())

/** Only capabilities already materialized on the actor are offered. The shared domain contract
 * supplies costs/effect kinds; the model cannot manufacture an ability or an unperceived target. */
internal class NpcMechanicalAffordances(private val contracts:CombatAbilityContractPort,
                                      private val activities:NpcActivityContractPort=NpcActivityContractPort.STANDARD,
                                      private val travelRoutes:NpcTravelRoutePort=NpcTravelRoutePort.NONE,
                                      private val learningState:NpcLearningStatePort=NpcLearningStatePort.NONE,
                                      private val readingAccess:NpcReadingAccessPort=NpcReadingAccessPort.NONE,
                                      private val requirements:NpcActivityRequirementPort=NpcActivityRequirementPort.NONE,
                                      private val treatments:NpcTreatmentReadPort=NpcTreatmentReadPort.NONE,
                                      private val duties:NpcDutyAssignmentPort=NpcDutyAssignmentPort.NONE,
                                      private val atTime:WorldTimeTick=WorldTimeTick(0),private val starting:Boolean=true,
                                      private val background:NpcBackgroundActivityPort=NpcBackgroundActivityPort.NONE) {
    fun options(brain:NpcBrainState,records:List<NpcKnownRecord>,actor:MechanicalActorView?,communicationTarget:DomainRef?=null):List<NpcActionOption> {
        if(actor==null || actor.actor!=brain.actor || actor.campaignUid!=brain.campaignUid ||
            actor.kind !in setOf(MechanicalActorKind.NPC,MechanicalActorKind.MONSTER,MechanicalActorKind.SUMMON,MechanicalActorKind.FORMER_PLAYER))return emptyList()
        val goals=brain.goals.filter{it.lifecycle==NpcGoalLifecycle.ACTIVE}.sortedWith(compareByDescending<NpcGoal>{it.priority.basisPoints}.thenBy{it.uid})
        val targets=records.flatMap{it.subjectRefs}.distinct().filter{it!=brain.actor}.sortedWith(compareBy<DomainRef>{it.kindUid}.thenBy{it.uid})
        val inherent=activities.inherent(brain.campaignUid).also{rules->
            require(rules.size<=16 && rules.all{it.eligibility==NpcActivityEligibility.CONSCIOUS_SELF}) { "P62:INVALID_INHERENT_ACTIVITY" }
            require(rules.map{it.capabilityUid}.distinct().size==rules.size && rules.all{it in activities.forCapability(brain.campaignUid,it.capabilityUid)})
        }.map{it.capabilityUid}.toSet()
        // A legacy generic combatant need not learn WAIT to have a non-violent choice. This
        // changes no canonical capability list and offers no unregistered action or effect.
        val capabilities=(actor.executableAbilityUids+inherent).sortedWith(compareBy<String>{it !in inherent}.thenBy{it})
        return buildList {
            addAll(background.options(brain,records,actor,atTime).take(2))
            // Travel is projected from canonical current location plus holder-authorized destination knowledge.
            // It does not mutate location and does not prove arrival.
            addAll(NpcTravelAffordances.options(brain,records,actor,travelRoutes).take(2))
            if(size>=8)return@buildList
            for(goal in goals) {
                val evidence=records.singleOrNull{it.acquisitionUid==goal.cause.uid}
                if(evidence==null && goal.cause.kind!=NpcCauseKind.INTRINSIC_MOTIVATION)continue
                if(communicationTarget!=null && NpcSpeechMechanics.canSpeak(actor)) {
                    records.firstOrNull{communicationTarget in it.subjectRefs}?.let{heard->
                        add(NpcSpeechMechanics.option(brain,goal,communicationTarget,heard,evidence))
                    }
                    if(size==8)return@buildList
                }
                for(ability in capabilities) {
                    val registered=activities.forCapability(brain.campaignUid,ability)
                    if(registered.isNotEmpty()) {
                        for(activity in registered.sortedWith(compareBy<NpcActivityContract>{it.learning==null}.thenBy{it.ruleUid})) {
                            if(activity.capabilityUid!=ability || !NpcActivityMechanics.available(actor,activity))continue
                            if(activity.learning?.let{!NpcLearningApplication.admitted(it,learningState.state(brain.campaignUid,actor.actor,it))}==true)continue
                            if(activity.reading?.let{!readingAccess.accessible(brain.campaignUid,actor.actor,it)}==true)continue
                            if(!requirements.admitted(brain.campaignUid,actor.actor,activity.requirements))continue
                            if(activity.duty?.let{rule->atTime>rule.due || starting && atTime+activity.duration>rule.due || !duties.admitted(brain.campaignUid,actor.actor,rule,atTime)}==true)continue
                            if(activity.treatment!=null) {
                                for(patient in (listOf(actor.actor)+targets).distinct().take(8)) {
                                    val state=treatments.patient(brain.campaignUid,actor.actor,patient,activity.treatment,emptyList())?:continue
                                    if(!NpcTreatmentApplication.needed(state,activity.treatment))continue
                                    val option=NpcActivityMechanics.option(brain,goal,evidence,activity,patient)
                                    add(option.copy(supportingRecordUids=option.supportingRecordUids+setOfNotNull(records.firstOrNull{patient in it.subjectRefs}?.uid),
                                        worldResult=resultContract(activity,actor,state)))
                                    if(size==8)return@buildList
                                }
                                continue
                            }
                            add(NpcActivityMechanics.option(brain,goal,evidence,activity).copy(worldResult=resultContract(activity,actor,actor)))
                            if(size==8)return@buildList
                        }
                        continue
                    }
                    if(evidence==null)continue
                    // Non-combat capabilities need their own domain contract; never reinterpret
                    // READ, HEAL or TALK as damage just because a generic combat fallback exists.
                    val contract=contracts.npcContractFor(CombatAbilityContractQuery(brain.campaignUid,ability,ability,1,false))?:continue
                    if(contract.abilityUid!=ability)continue
                    val resource=contract.resourceUid?.let{uid->actor.resources.singleOrNull{it.resourceUid==uid}}
                    if(contract.resourceCost>0 && (resource==null || resource.current<contract.resourceCost))continue
                    // Keep room for a different capability; one attack family must not consume
                    // the entire mobile decision menu just because many targets were perceived.
                    for(target in targets.filter{contract.targetKindUids.isEmpty() || it.kindUid in contract.targetKindUids}.take(2)) {
                        if(contract.targetKindUids.isNotEmpty() && target.kindUid !in contract.targetKindUids)continue
                        val support=records.first{target in it.subjectRefs}
                        val contractFingerprint=npcCombatContractFingerprint(contract)
                        val id="P62:OPTION:${phase60Hash("${brain.actor}|${goal.uid}|$ability|$target|$contractFingerprint").take(32)}"
                        add(NpcActionOption(id,ability,target,AcceptedActionTiming(ActionDuration(1000),Phase60CombatTime.RULE,1),goal.uid,
                            emptyList(),setOf(evidence.uid,support.uid),
                            resourceCosts=contract.resourceUid?.let{mapOf(it to contract.resourceCost)}?:emptyMap(),
                            perceivedRisk=NpcWeight(5000),resourcePressure=NpcWeight(if(resource==null||resource.current==0L)0 else
                                java.math.BigInteger.valueOf(contract.resourceCost).multiply(java.math.BigInteger.valueOf(10000))
                                    .divide(java.math.BigInteger.valueOf(resource.current)).toInt().coerceIn(0,10000)),
                            mechanicsOwnerUid="UNIVERSAL_COMBAT",mechanicalEffectKindUid=contract.effectKinds.first().name,
                            parameters=mapOf("npc_ability_contract" to contractFingerprint)))
                        if(size==8)return@buildList
                    }
                }
            }
        }
    }
    private fun resultContract(c:NpcActivityContract,actor:MechanicalActorView,patient:MechanicalActorView):NpcWorldResultContract? {
        val criteria=buildList {
            addAll(c.resultCriteria.map{r->if(r.target==DomainRef("SELF","SELF"))r.copy(target=actor.actor) else r}.also{rules->
                require(rules.all{it.target==actor.actor || it.target==patient.actor}){"P62:RESULT_REQUIRES_DOMAIN_ACCESS"}})
            c.learning?.let { rule->
                val state=learningState.state(actor.campaignUid,actor.actor,rule)?:return null
                val kind=if(rule.targetKindUid==ProgressionTargetKinds.SKILL)NpcWorldResultKind.SKILL_PROGRESS else NpcWorldResultKind.TECHNIQUE_PROGRESS
                add(NpcWorldResultCriterion(kind,actor.actor,rule.targetUid,state.progress+rule.effortUnits))
            }
            c.reading?.let{add(NpcWorldResultCriterion(NpcWorldResultKind.KNOWLEDGE_ACQUIRED,actor.actor,it.claim.claimUid))}
            c.resourceRecovery?.let{r->actor.resources.singleOrNull{it.resourceUid==r.resourceUid}?.let {
                add(NpcWorldResultCriterion(NpcWorldResultKind.RESOURCE_AT_LEAST,actor.actor,r.resourceUid,it.maximum.toDouble()))
            }}
            c.treatment?.let{r->
                r.resourceRecovery.toSortedMap().forEach{(uid,_)->patient.resources.singleOrNull{it.resourceUid==uid}?.let{
                    if(it.current<it.maximum)add(NpcWorldResultCriterion(NpcWorldResultKind.RESOURCE_AT_LEAST,patient.actor,uid,it.maximum.toDouble()))
                }}
                if(r.woundHealingUnits>0 && patient.conditions.any{it.conditionUid=="WOUND" && it.intensity>0})add(NpcWorldResultCriterion(NpcWorldResultKind.WOUND_RESOLVED,patient.actor,"WOUND"))
                r.removedConditionUids.sorted().filter{uid->patient.conditions.any{it.conditionUid==uid && it.intensity>0}}.forEach{
                    add(NpcWorldResultCriterion(NpcWorldResultKind.CONDITION_REMOVED,patient.actor,it))}
                r.stabilizationConditionUid?.takeIf{uid->patient.conditions.any{it.conditionUid==uid && it.intensity>0}}?.let{
                    add(NpcWorldResultCriterion(NpcWorldResultKind.PATIENT_STABILIZED,patient.actor,it))}
            }
            c.duty?.let{add(NpcWorldResultCriterion(NpcWorldResultKind.DUTY_COMPLETED,actor.actor,it.completionTrack))}
        }.distinct()
        return if(criteria.isEmpty())null else NpcWorldResultContract(NpcActivityMechanics.ownerContract(c).resultOwnerUid,c.fingerprint,criteria)
    }
}
