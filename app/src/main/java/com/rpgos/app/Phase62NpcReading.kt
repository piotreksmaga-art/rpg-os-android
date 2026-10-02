package com.rpgos.app

/** Published carrier content remains a claim. Reading never promotes it into campaign FACT. */
data class NpcReadingRule(val carrier:DomainRef,val accessPolicyUid:String,val claim:KnowledgeClaim,
    val sourceReliability:Double=0.5) {
    init { npcUid(carrier.kindUid);npcUid(carrier.uid);npcUid(accessPolicyUid)
        require(sourceReliability.isFinite() && sourceReliability in 0.0..1.0 && claim.valueCanonical.length<=2048) }
    internal val fingerprint get()=phase60Hash("P62:READING:1|$carrier|$accessPolicyUid|$claim|$sourceReliability")
}
fun interface NpcReadingAccessPort {
    /** Phase38 checks the exact holder, carrier and current order, not merely co-location. */
    fun accessible(campaignUid:String,actor:DomainRef,rule:NpcReadingRule):Boolean
    companion object { val NONE=NpcReadingAccessPort{_,_,_->false} }
}

internal object NpcReadingApplication {
    fun fields(campaign:String,actor:DomainRef,contract:NpcActivityContract):Map<String,String> {
        val r=requireNotNull(contract.reading)
        return mapOf("npc_reading_campaign" to campaign,"npc_reading_actor_kind" to actor.kindUid,"npc_reading_actor" to actor.uid,
            "npc_reading_contract" to contract.fingerprint,"npc_reading_definition" to NpcActivityContractCodec.encode(contract))
    }
    fun materialize(campaign:String,command:String,order:Long?,effects:List<VerifiedMechanicsCommandEffect>):NpcActionMemory.Draft {
        val changes=mutableListOf<PlayerDomainChange>();val events=mutableListOf<PlayerEventIntent>()
        effects.forEach { effect ->
            if(effect.effectKindUid!="INTERACTION")return@forEach
            val text=effect.canonicalPayload["npc_reading_definition"]?:return@forEach
            val contract=NpcActivityContractCodec.decode(text);val rule=requireNotNull(contract.reading)
            require(effect.mechanicsOwnerUid==NpcActivityMechanics.OWNER && effect.effectKindUid=="INTERACTION" &&
                effect.canonicalPayload["npc_reading_campaign"]==campaign &&
                effect.canonicalPayload["npc_reading_actor_kind"]==effect.target.kindUid && effect.canonicalPayload["npc_reading_actor"]==effect.target.uid &&
                effect.canonicalPayload["npc_reading_contract"]==contract.fingerprint && effect.canonicalPayload["npc_activity_contract"]==contract.fingerprint)
            val id=phase60Hash("P62:READING:1|$campaign|$command|${effect.effectUid}|${rule.fingerprint}")
            val carrier=KnowledgeCarrierRef(rule.carrier.kindUid,rule.carrier.uid,campaign)
            val payload=KnowledgeAcquisitionChange(rule.claim,
                KnowledgeAcquisitionSpec("P62:READ-ACQ:$id",KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER,effect.target.uid,campaign),
                    KnowledgeAcquisitionMethods.DOCUMENT,KnowledgeScope.PERSONAL,KnowledgeEpistemicState.BELIEVED,
                    KnowledgeQuality(rule.sourceReliability,1.0,1.0,rule.sourceReliability,1,order),carrier=carrier),
                listOf(KnowledgeEvidenceSpec("P62:READ-EVIDENCE:$id","P62:AUTHORIZED_CARRIER_READING",KnowledgeEvidencePolarity.SUPPORTS,
                    sourceCarrier=carrier,sourceRef=KnowledgeSourceRef.campaign(campaign,rule.carrier.kindUid,rule.carrier.uid))))
            val uid="P62:READ-CHANGE:$id"
            changes+=PlayerDomainChange.create(uid,PHASE37_KNOWLEDGE_CHANGE_KIND,payload,sourceRuleUid=effect.proofUid)
            events+=PlayerEventIntent.create("P62:READ-EVENT:$id",PlayerEventIntentKinds.DOMAIN_EFFECT,effect.target,listOf(effect.target),
                listOf(uid),DomainEffectEventIntentPayload(effect.target,"RPGOS-EFFECT:KNOWLEDGE_ACQUISITION"))
        }
        return NpcActionMemory.Draft(changes,events)
    }
}
