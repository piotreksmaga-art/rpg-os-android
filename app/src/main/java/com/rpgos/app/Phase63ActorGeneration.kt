package com.rpgos.app

/** Registered, versioned generic profile. Role-specific/exceptional abilities must be supplied
 * by a domain owner, not by WORLD_DRAFT prose or the active player's power. */
internal object Phase63ActorGeneration {
    const val RULE="RPGOS-P63:GENERIC-ACTOR:1"
    const val GROUP_RULE="RPGOS-P63:GENERIC-GROUP:1"
    fun groupCount(domainSeed:String):Long=1+phase63Hash("$domainSeed|POPULATION").take(15).toLong(16)%20
    fun groupSeed(actor:DomainRef,domainSeed:String,name:String):MechanicalActorSeed {
        require(actor.kindUid=="GROUP")
        val count=groupCount(domainSeed)
        return seed(actor,domainSeed,MechanicalStateMaterialization.FULL).let { base->base.copy(
            kind=MechanicalActorKind.GROUP,templateUid=GROUP_RULE,provenanceUid="$GROUP_RULE:$domainSeed",
            resources=base.resources.map { it.copy(current=Math.multiplyExact(it.current,count),maximum=Math.multiplyExact(it.maximum,count)) },
            aggregateName=name,aggregateCount=count) }
    }
    fun seed(actor:DomainRef,domainSeed:String,level:MechanicalStateMaterialization):MechanicalActorSeed {
        require(domainSeed.matches(Regex("[0-9a-f]{64}")))
        fun value(key:String,from:Long,through:Long)=from+phase63Hash("$domainSeed|$key").take(15).toLong(16)%(through-from+1)
        val body=level==MechanicalStateMaterialization.FULL
        return MechanicalActorSeed(actor,MechanicalActorKind.NPC,RULE,domainSeed,"$RULE:$domainSeed",
            if(body)mapOf("POWER" to value("POWER",30,80),"SKILL" to value("SKILL",30,80),"DEFENCE" to value("DEFENCE",30,80),
                "AGILITY" to value("AGILITY",30,80),"ARMOR" to value("ARMOR",0,30)) else emptyMap(),
            if(body)listOf(MechanicalResource("HEALTH",100,100),MechanicalResource("STAMINA",100,100)) else emptyList(),
            if(body)setOf("ATTACK","STRIKE","DEFEND","TRAVEL") else emptySet(),materialization=level)
    }
    fun forDraft(skeleton:CampaignWorldSkeleton,draft:WorldElementDraft,level:MechanicalStateMaterialization):MechanicalActorSeed? {
        if(draft.baseKind !in setOf(WorldElementBaseKind.ACTOR,WorldElementBaseKind.GROUP) || draft.campaignUid!=skeleton.campaignUid || draft.parentAnchorUid==null)return null
        val slot=LatentWorldSlot(draft.parentAnchorUid,draft.slotCategoryUid?:draft.categoryUid,draft.baseKind,draft.slotOrdinal)
        if(slot.ref(skeleton)!=draft.element || CoreLatentWorldRules.select(skeleton,slot,draft.topologyClassUid)==null)return null
        val domain=skeleton.domainSeed("MECHANICS",slot.canonicalKey)
        return if(draft.baseKind==WorldElementBaseKind.GROUP)groupSeed(draft.element,domain,draft.displayName) else seed(draft.element,domain,level)
    }
}
