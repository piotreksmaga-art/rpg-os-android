package com.rpgos.app

internal fun combatAbilityIdentity(node:IntentNode,autonomous:Boolean):String? =
    (node.semanticAction.canonicalActionUid?:node.semanticAction.semanticFamilyUid)?.let{if(autonomous)it else it.uppercase()}

/** Contract changes invalidate pending choices, including changes to costs, range or effects. */
internal fun npcCombatContractFingerprint(contract:CombatAbilityContract):String=phase60Hash("P62:ABILITY:1|"+
    contract.copy(requiredEquipmentKinds=contract.requiredEquipmentKinds.toSortedSet(),targetKindUids=contract.targetKindUids.toSortedSet()).toString())

/** Only capabilities already materialized on the actor are offered. The shared domain contract
 * supplies costs/effect kinds; the model cannot manufacture an ability or an unperceived target. */
internal class NpcMechanicalAffordances(private val contracts:CombatAbilityContractPort,
                                      private val activities:NpcActivityContractPort=NpcActivityContractPort.STANDARD) {
    fun options(brain:NpcBrainState,records:List<NpcKnownRecord>,actor:MechanicalActorView?,communicationTarget:DomainRef?=null):List<NpcActionOption> {
        if(actor==null || actor.actor!=brain.actor || actor.campaignUid!=brain.campaignUid ||
            actor.kind !in setOf(MechanicalActorKind.NPC,MechanicalActorKind.MONSTER,MechanicalActorKind.SUMMON,MechanicalActorKind.FORMER_PLAYER))return emptyList()
        val goals=brain.goals.filter{it.lifecycle==NpcGoalLifecycle.ACTIVE}.sortedWith(compareByDescending<NpcGoal>{it.priority.basisPoints}.thenBy{it.uid})
        val targets=records.flatMap{it.subjectRefs}.distinct().filter{it!=brain.actor}.sortedWith(compareBy<DomainRef>{it.kindUid}.thenBy{it.uid})
        val inherent=activities.inherent(brain.campaignUid).also{rules->
            require(rules.size<=16 && rules.all{it.eligibility==NpcActivityEligibility.CONSCIOUS_SELF}) { "P62:INVALID_INHERENT_ACTIVITY" }
            require(rules.map{it.capabilityUid}.distinct().size==rules.size && rules.all{activities.contract(brain.campaignUid,it.capabilityUid)==it})
        }.map{it.capabilityUid}.toSet()
        // A legacy generic combatant need not learn WAIT to have a non-violent choice. This
        // changes no canonical capability list and offers no unregistered action or effect.
        val capabilities=(actor.executableAbilityUids+inherent).sortedWith(compareBy<String>{it !in inherent}.thenBy{it})
        return buildList {
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
                    val activity=activities.contract(brain.campaignUid,ability)
                    if(activity!=null && activity.capabilityUid==ability && NpcActivityMechanics.available(actor,activity)) {
                        add(NpcActivityMechanics.option(brain,goal,evidence,activity))
                        if(size==8)return@buildList
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
}
