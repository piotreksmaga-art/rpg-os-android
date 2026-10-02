package com.rpgos.app

import kotlinx.serialization.json.*

internal data class NpcWitnessPerceptionInput(val body:MechanicalActorView,val observerLocation:String?,val subjectLocation:String?,
    val observerPosition:CombatPosition.Exact,val subjectPosition:CombatPosition.Exact,val recognized:List<NpcKnownRecord>)

/** Core's explicit external-wound observation rule. The caller must establish an actual visual
 * capability, exact range, recognized subject and Phase38 target grant before issuing a token. */
internal class NpcLegalEffectObservation private constructor(val observer:DomainRef,val subject:DomainRef,val evidenceFingerprint:String) {
    companion object {
        const val POLICY="P62:VISIBLE_WOUND"
        const val CAPABILITY="PERCEIVE_VISUAL"
        const val CHANNEL="P62:VISUAL_WOUND"
        const val RANGE_MM=5000L
        fun project(authority:UniversalAccessAuthority,trusted:TrustedPrincipalContext,scope:TemporalScope,subject:DomainRef,
            effect:VerifiedMechanicsCommandEffect,input:NpcWitnessPerceptionInput):NpcLegalEffectObservation? {
            val observer=DomainRef(trusted.principal.kindUid,trusted.principal.uid)
            val body=input.body
            if(trusted.campaignUid!=scope.campaignUid || body.campaignUid!=scope.campaignUid || body.actor!=observer || observer==subject ||
                body.materialization!=MechanicalStateMaterialization.FULL || CAPABILITY !in body.executableAbilityUids ||
                body.conditions.any{it.intensity>0 && it.conditionUid in setOf("DEAD","UNCONSCIOUS","INCAPACITATED","BLIND")} ||
                body.resources.any{it.resourceUid=="HEALTH" && it.current==0L} || input.observerLocation==null || input.observerLocation!=input.subjectLocation ||
                !npcWithinInteractionRange(input.observerPosition,input.subjectPosition,RANGE_MM) ||
                input.recognized.none{subject in it.subjectRefs && it.sourceCommittedOrder<=scope.baseCommitOrder})return null
            if(!authority.authorize(trusted,AccessRequirement(POLICY,explicitGrantRequired=true,
                carrier=InformationCarrierRef(scope.campaignUid,subject.kindUid,subject.uid)),scope.baseCommitOrder).authorized)return null
            if(effect.target!=subject || effect.effectKindUid!="WOUND" || effect.mechanicsOwnerUid!="UNIVERSAL_COMBAT" || effect.magnitude<=0)return null
            val ref=PerceptionSignalRef(trusted.campaignUid,"P62:VISIBLE:${effect.effectUid}")
            val signal=Phase38PerceptionRuntimeAuthority.issueSignal(trusted.campaignUid,ref,CHANNEL,1.0,
                mapOf("visible_condition" to "WOUND"),PerceptionUncertainty(1.0,1.0,1.0),
                VisibilitySubjectRef(trusted.campaignUid,subject.kindUid,subject.uid))
            val capability=Phase38PerceptionRuntimeAuthority.issueCapability(trusted,PerceptionCapabilityRef(trusted.campaignUid,CAPABILITY),
                trusted.principal,setOf(CHANNEL),1.0,DisclosureLevel.SUMMARY)
            val context=PerceptionContext(trusted.campaignUid,trusted,listOf(capability),PerceptionWorldRules(POLICY,mapOf(CHANNEL to setOf(CHANNEL))))
            val resolver=PerceptionResolver();val perceived=resolver.evaluate(PerceptionRequest(context,signal))
            val policy=DisclosurePolicy(trusted.campaignUid,POLICY,DisclosureLevel.SUMMARY,
                mapOf("visible_condition" to PropertyDisclosureRule("visible_condition",mapOf(DisclosureLevel.SUMMARY to DisclosureValueProjection.Keep))))
            val disclosed=DisclosureResolver().resolve(perceived,resolver.recognize(context,perceived),resolver.interpret(context,perceived),policy,DisclosureLevel.SUMMARY)
            if(disclosed.decision.dataState!=ProjectionDataState.DISCLOSED || disclosed.payload!=mapOf("visible_condition" to "WOUND"))return null
            return NpcLegalEffectObservation(observer,subject,phase60Hash(
                "${scope.historyGenerationUid}|${scope.baseCommitOrder}|${input.observerPosition}|${input.subjectPosition}|${body.stateVersion}|${input.recognized.map{it.acquisitionUid}.sorted()}|$POLICY"))
        }
    }
}
internal object NpcWitnessObservation {
    const val PREFIX="npc_witness_"
    private const val RULE="P62:VISIBLE_WOUND:1"
    private fun fingerprint(e:VerifiedMechanicsCommandEffect,scope:TemporalScope,rows:String)=phase60Hash(
        "$RULE|${scope.campaignUid}|${scope.historyGenerationUid}|${scope.baseCommitOrder}|${e.effectUid}|${e.proofUid}|${e.target}|${e.magnitude}|$rows")
    fun annotate(scope:TemporalScope,effects:List<VerifiedMechanicsCommandEffect>,project:(VerifiedMechanicsCommandEffect)->List<NpcLegalEffectObservation>):List<VerifiedMechanicsCommandEffect> =
        effects.map { raw->
            val e=raw.copy(canonicalPayload=raw.canonicalPayload.filterKeys{!it.startsWith(PREFIX)})
            val legal=project(e).distinctBy{it.observer}.sortedWith(compareBy<NpcLegalEffectObservation>{it.observer.kindUid}.thenBy{it.observer.uid}).take(8)
            if(legal.isEmpty())return@map e
            val rows=JsonArray(legal.map{buildJsonObject{put("observer",NpcBrainCodec.ref(it.observer));put("subject",NpcBrainCodec.ref(it.subject));put("evidence",it.evidenceFingerprint)}}).toString()
            e.copy(canonicalPayload=e.canonicalPayload+mapOf("${PREFIX}rows" to rows,"${PREFIX}generation" to scope.historyGenerationUid,
                "${PREFIX}campaign" to scope.campaignUid,"${PREFIX}order" to scope.baseCommitOrder.toString(),"${PREFIX}proof" to fingerprint(e,scope,rows)))
        }
    fun recipients(campaign:String,e:VerifiedMechanicsCommandEffect):List<DomainRef> {
        val text=e.canonicalPayload["${PREFIX}rows"]?:return emptyList()
        require(text.length<=8192 && e.canonicalPayload["${PREFIX}campaign"]==campaign)
        val scope=TemporalScope(campaign,requireNotNull(e.canonicalPayload["${PREFIX}generation"]),requireNotNull(e.canonicalPayload["${PREFIX}order"]?.toLongOrNull()),"OBSERVATION")
        require(e.effectKindUid=="WOUND" && e.mechanicsOwnerUid=="UNIVERSAL_COMBAT" && e.magnitude>0 &&
            e.canonicalPayload["${PREFIX}proof"]==fingerprint(e,scope,text)){"P62:WITNESS_PROOF_MISMATCH"}
        return Json.parseToJsonElement(text).jsonArray.also{require(it.size in 1..8)}.map{row->
            val o=row.jsonObject;NpcBrainCodec.keys(o,"observer","subject","evidence")
            require(NpcBrainCodec.readRef(o.getValue("subject"))==e.target && NpcBrainCodec.text(o,"evidence").matches(Regex("[0-9a-f]{64}")))
            NpcBrainCodec.readRef(o.getValue("observer"))
        }.also{require(it.distinct().size==it.size && e.target !in it)}
    }
    fun materialize(campaign:String,command:String,order:Long?,effects:List<VerifiedMechanicsCommandEffect>):NpcActionMemory.Draft {
        val changes=mutableListOf<PlayerDomainChange>();val events=mutableListOf<PlayerEventIntent>()
        effects.forEach{e->recipients(campaign,e).forEach{observer->
            require(order==e.canonicalPayload["${PREFIX}order"]?.toLongOrNull()?.plus(1)){"P62:WITNESS_ORDER_MISMATCH"}
            val id=phase60Hash("$RULE|$campaign|$command|${e.effectUid}|$observer")
            val holder=KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER,observer.uid,campaign)
            val acquisition=KnowledgeAcquisitionChange(KnowledgeClaim("P62:WITNESS-CLAIM:$id",e.target.kindUid,e.target.uid,
                "P62:VISIBLE_WOUND","Zaobserwowałem widoczny uraz.",domainUid=KnowledgeDomains.WORLD_SPECIFIC),
                KnowledgeAcquisitionSpec("P62:WITNESS-ACQ:$id",holder,KnowledgeAcquisitionMethods.DIRECT_OBSERVATION,KnowledgeScope.PERSONAL,
                    KnowledgeEpistemicState.KNOWN,KnowledgeQuality(1.0,1.0,1.0,1.0,1,order)),
                listOf(KnowledgeEvidenceSpec("P62:WITNESS-EVIDENCE:$id",RULE,KnowledgeEvidencePolarity.SUPPORTS,
                    sourceRef=KnowledgeSourceRef.campaign(campaign,e.target.kindUid,e.target.uid))))
            val uid="P62:WITNESS-CHANGE:$id"
            changes+=PlayerDomainChange.create(uid,PHASE37_KNOWLEDGE_CHANGE_KIND,acquisition,sourceRuleUid=RULE)
            val ref=DomainRef(holder.holderKindUid,holder.holderUid)
            events+=PlayerEventIntent.create("P62:WITNESS-EVENT:$id",PlayerEventIntentKinds.DOMAIN_EFFECT,observer,listOf(ref),listOf(uid),
                DomainEffectEventIntentPayload(ref,"RPGOS-EFFECT:KNOWLEDGE_ACQUISITION"))
        }}
        return NpcActionMemory.Draft(changes,events)
    }
}
