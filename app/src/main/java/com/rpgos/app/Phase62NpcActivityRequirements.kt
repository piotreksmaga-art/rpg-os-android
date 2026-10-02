package com.rpgos.app

/** Costs and prerequisites are versioned with the owner contract, never supplied by AI. */
data class NpcActivityRequirements(val resourceCosts:Map<String,Long> = emptyMap(),
    val toolInstanceUids:Set<String> = emptySet(),val knowledgeClaimUids:Set<String> = emptySet(),val teacher:DomainRef?=null) {
    init {
        require(resourceCosts.size<=8 && resourceCosts.values.all{it>0} && toolInstanceUids.size<=8 && knowledgeClaimUids.size<=8)
        (resourceCosts.keys+toolInstanceUids+knowledgeClaimUids).forEach(::npcUid)
        teacher?.let{npcUid(it.kindUid);npcUid(it.uid)}
    }
    internal val fingerprint get()=phase60Hash("${resourceCosts.toSortedMap()}|${toolInstanceUids.sorted()}|${knowledgeClaimUids.sorted()}|$teacher")
    internal val needsExternalRead get()=toolInstanceUids.isNotEmpty() || knowledgeClaimUids.isNotEmpty() || teacher!=null
}
fun interface NpcActivityRequirementPort {
    fun admitted(campaignUid:String,actor:DomainRef,requirements:NpcActivityRequirements):Boolean
    companion object { val NONE=NpcActivityRequirementPort{_,_,r->!r.needsExternalRead} }
}

/** External owner reads are canonical. If that prerequisite is already being changed in this
 * transaction, omit the option instead of pretending the earlier read describes staged state. */
internal fun npcActivityPrerequisiteChanged(contract:NpcActivityContract,actor:DomainRef,changes:List<PlayerDomainChangePayload>):Boolean =
    changes.any { p->when(p) {
        is AccessAuthorityChange->p.principalKindUid==actor.kindUid && p.principalUid==actor.uid &&
            (contract.duty!=null || contract.reading!=null || contract.requirements.needsExternalRead || contract.treatment!=null)
        is InventoryChange->p.subject==actor && (contract.reading?.carrier?.uid==p.itemInstanceUid || p.itemInstanceUid in contract.requirements.toolInstanceUids)
        is SkillChange->p.subject==actor && contract.learning?.let{it.targetKindUid==ProgressionTargetKinds.SKILL && it.targetUid==p.skillUid}==true
        is TechniqueChange->p.subject==actor && contract.learning?.let{it.targetKindUid==ProgressionTargetKinds.TECHNIQUE && it.targetUid==p.techniqueUid}==true
        is SpatialChange->p.subject==contract.requirements.teacher || contract.treatment!=null && p.subject!=actor
        else->false
    }}

internal fun npcWithinInteractionRange(a:CombatPosition.Exact,b:CombatPosition.Exact,maximum:Long):Boolean {
    require(maximum>=0)
    fun square(x:Long,y:Long)=java.math.BigInteger.valueOf(x).subtract(java.math.BigInteger.valueOf(y)).pow(2)
    return square(a.xMillimetres,b.xMillimetres)+square(a.yMillimetres,b.yMillimetres)+square(a.zMillimetres,b.zMillimetres)<=
        java.math.BigInteger.valueOf(maximum).pow(2)
}
