package com.rpgos.app

/** Separate operations, bounded by an explicit domain rule. HEALTH is not a wound counter. */
data class NpcTreatmentRule(val resourceRecovery:Map<String,Long> = emptyMap(),val woundHealingUnits:Long=0,
    val removedConditionUids:Set<String> = emptySet(),val stabilizationConditionUid:String?=null,
    val maximumRangeMillimetres:Long=2000,val successAttributeUid:String?=null,val successThreshold:Long=0) {
    init {
        require(resourceRecovery.size<=4 && resourceRecovery.keys.all{it in setOf("HEALTH","STAMINA")} && resourceRecovery.values.all{it>0})
        require(woundHealingUnits>=0 && removedConditionUids.size<=8 && maximumRangeMillimetres in 0..100000 && successThreshold>=0)
        require(resourceRecovery.isNotEmpty() || woundHealingUnits>0 || removedConditionUids.isNotEmpty() || stabilizationConditionUid!=null)
        removedConditionUids.forEach(::npcUid);stabilizationConditionUid?.let(::npcUid);successAttributeUid?.let(::npcUid)
        require("WOUND" !in removedConditionUids && "DEAD" !in removedConditionUids && stabilizationConditionUid !in setOf("WOUND","DEAD"))
        require(successAttributeUid!=null || successThreshold==0L)
    }
    internal val fingerprint get()=phase60Hash("P62:TREATMENT:1|${resourceRecovery.toSortedMap()}|$woundHealingUnits|${removedConditionUids.sorted()}|$stabilizationConditionUid|$maximumRangeMillimetres|$successAttributeUid|$successThreshold")
}
fun interface NpcTreatmentReadPort {
    fun patient(campaignUid:String,healer:DomainRef,patient:DomainRef,rule:NpcTreatmentRule,staged:List<VerifiedMechanicsEffect>):MechanicalActorView?
    companion object { val NONE=NpcTreatmentReadPort{_,_,_,_,_->null} }
}

internal data class NpcTreatmentImpact(val target:DomainRef,val kind:String,val units:Long,val fields:Map<String,String> = emptyMap())
internal data class NpcTreatmentSettlement(val outcome:NpcActivityResolutionKind,val impacts:List<NpcTreatmentImpact>,val reasonUid:String)
internal object NpcTreatmentApplication {
    fun needed(patient:MechanicalActorView,rule:NpcTreatmentRule)=
        patient.conditions.none{it.conditionUid=="DEAD" && it.intensity>0} &&
        (rule.resourceRecovery.any{(uid,_)->patient.resources.singleOrNull{it.resourceUid==uid}?.let{it.current<it.maximum}==true} ||
            rule.woundHealingUnits>0 && patient.conditions.any{it.conditionUid=="WOUND" && it.intensity>0} ||
            patient.conditions.any{it.intensity>0 && (it.conditionUid in rule.removedConditionUids || it.conditionUid==rule.stabilizationConditionUid)})

    fun settle(healer:MechanicalActorView,patient:MechanicalActorView,rule:NpcTreatmentRule):NpcTreatmentSettlement {
        if(!needed(patient,rule))return NpcTreatmentSettlement(NpcActivityResolutionKind.SUCCEEDED,emptyList(),"P62:TREATMENT_NO_LONGER_NEEDED")
        if(rule.successAttributeUid?.let{(healer.attributes[it]?:-1)<rule.successThreshold}==true)
            return NpcTreatmentSettlement(NpcActivityResolutionKind.FAILED,emptyList(),"P62:TREATMENT_ATTEMPT_FAILED")
        var remaining=false
        val impacts=buildList {
            rule.resourceRecovery.toSortedMap().forEach{(uid,maximum)->
                val resource=patient.resources.singleOrNull{it.resourceUid==uid}?:return@forEach
                val deficit=Math.subtractExact(resource.maximum,resource.current)
                val gain=minOf(deficit,maximum)
                if(gain>0)add(NpcTreatmentImpact(patient.actor,"RESOURCE_DELTA",gain,mapOf("resource_uid" to uid)))
                if(deficit>gain)remaining=true
            }
            val wound=patient.conditions.singleOrNull{it.conditionUid=="WOUND"}?.intensity?:0L
            val healing=minOf(wound,rule.woundHealingUnits)
            if(healing>0)add(NpcTreatmentImpact(patient.actor,"WOUND_HEALING",healing,mapOf("expected_wound_units" to wound.toString())))
            if(rule.woundHealingUnits>0 && wound>healing)remaining=true
            patient.conditions.filter{it.intensity>0 && (it.conditionUid in rule.removedConditionUids || it.conditionUid==rule.stabilizationConditionUid)}
                .distinctBy{it.conditionUid}.sortedBy{it.conditionUid}.forEach{
                    add(NpcTreatmentImpact(patient.actor,"CONDITION",1,mapOf("condition_uid" to it.conditionUid,"operation" to "REMOVE")))
                }
        }
        return NpcTreatmentSettlement(if(remaining)NpcActivityResolutionKind.PARTIAL else NpcActivityResolutionKind.SUCCEEDED,
            impacts,if(remaining)"P62:TREATMENT_PARTIAL" else "P62:TREATMENT_SUCCEEDED")
    }
}
