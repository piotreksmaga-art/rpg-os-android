package com.rpgos.app

/** Registered short, directed speech. It is not broadcasting and co-location is not knowledge:
 * identity must already be in the speaker's legal projection. Actual delivery also needs a
 * fresh, conscious recipient and the domain's bounded spatial check. */
internal object NpcSpeechMechanics {
    const val OWNER="NPC_DIRECTED_SPEECH"
    const val CAPABILITY="SPEAK_TO_KNOWN_ACTOR"
    const val TIMING_RULE="P62:SHORT_DIRECTED_SPEECH_MS_V1"
    const val DURATION_MS=30_000L
    private const val CONTRACT="P62:DIRECTED_SPEECH:1"
    fun inReach(speakerLocation:String,recipientLocation:String,x:Long,y:Long,otherX:Long,otherY:Long,z:Long=0,otherZ:Long=0):Boolean {
        if(speakerLocation.isBlank() || speakerLocation!=recipientLocation)return false
        val dx=java.math.BigInteger.valueOf(x).subtract(java.math.BigInteger.valueOf(otherX))
        val dy=java.math.BigInteger.valueOf(y).subtract(java.math.BigInteger.valueOf(otherY))
        val dz=java.math.BigInteger.valueOf(z).subtract(java.math.BigInteger.valueOf(otherZ))
        return dx.multiply(dx).add(dy.multiply(dy)).add(dz.multiply(dz))<=java.math.BigInteger.valueOf(25_000_000L)
    }
    fun conscious(actor:MechanicalActorView)=actor.materialization==MechanicalStateMaterialization.FULL &&
        actor.conditions.none{it.intensity>0 && it.conditionUid.uppercase() in setOf("DEAD","UNCONSCIOUS","INCAPACITATED")} &&
        actor.resources.none{it.resourceUid=="HEALTH" && it.current==0L}
    fun canSpeak(actor:MechanicalActorView)=conscious(actor) && actor.kind in setOf(MechanicalActorKind.NPC,MechanicalActorKind.MONSTER,
        MechanicalActorKind.SUMMON,MechanicalActorKind.FORMER_PLAYER) &&
        actor.conditions.none{it.intensity>0 && it.conditionUid.uppercase() in setOf("MUTE","MUTED","SILENCED","CANNOT_SPEAK")}
    fun option(brain:NpcBrainState,goal:NpcGoal,recipient:DomainRef,record:NpcKnownRecord,goalEvidence:NpcKnownRecord?):NpcActionOption {
        require(recipient in record.subjectRefs && recipient!=brain.actor)
        return NpcActionOption("P62:OPTION:${phase60Hash("$CONTRACT|${brain.actor}|${goal.uid}|$recipient").take(32)}",CAPABILITY,recipient,
            AcceptedActionTiming(ActionDuration(DURATION_MS),TIMING_RULE,1),goal.uid,
            listOf(NpcTraitPreference("SOCIABILITY",NpcWeight(10000),NpcWeight(3000))).filter{it.traitUid in brain.personality},
            setOfNotNull(record.uid,goalEvidence?.uid),parameters=mapOf("speech_contract" to CONTRACT),
            mechanicsOwnerUid=OWNER,mechanicalEffectKindUid="INTERACTION",
            motivationAlignment=brain.motivations.filter{it.domainUid=="RELATIONSHIPS"}.associate{it.uid to NpcAffect(6000)})
    }
    fun resolve(request:MechanicsEffectRequest,context:MechanicsResolutionContext,speaker:MechanicalActorView,
                recipient:MechanicalActorView,reachable:Boolean):MechanicsEffectResolution {
        fun fail(reason:String)=MechanicsEffectResolution.Rejected("P62:$reason")
        val auth=context.npcAuthorization?:return fail("SPEECH_AUTHORIZATION_REQUIRED")
        val node=context.plan.intent.nodes.singleOrNull()?:return fail("SPEECH_SINGLE_NODE_REQUIRED")
        if(!auth.authorizesMechanics(auth.scope.temporal,context.plan,node,request) ||
            speaker.actor!=auth.scope.actor || speaker.campaignUid!=context.campaignUid || recipient.campaignUid!=context.campaignUid ||
            recipient.actor.uid!=auth.scope.activePlayerUid || recipient.kind!=MechanicalActorKind.ACTIVE_PLAYER ||
            request.targetProjectedRef!=recipient.actor || request.mechanicsOwnerUid!=OWNER || request.effectKindUid!="INTERACTION" ||
            node.semanticAction.canonicalActionUid!=CAPABILITY || request.parameters!=mapOf("speech_contract" to CONTRACT))return fail("SPEECH_CONTRACT_MISMATCH")
        if(!canSpeak(speaker) || !conscious(recipient) || recipient.conditions.any{it.intensity>0 && it.conditionUid.uppercase() in setOf("DEAF","CANNOT_HEAR")} || !reachable)
            return fail("SPEECH_DELIVERY_UNAVAILABLE")
        // Preflight contains NO generated text or acquired knowledge. These appear only when
        // the action actually finishes and its fresh delivery check still succeeds.
        val payload=mapOf("track_uid" to "ACTION:SPEECH","magnitude" to "1","target_kind_uid" to speaker.actor.kindUid,"target_uid" to speaker.actor.uid,
            "p60_core_timing_rule" to TIMING_RULE,"p60_core_timing_version" to "1","p60_core_duration_ms" to DURATION_MS.toString(),
            "p60_core_effect_at_ms" to DURATION_MS.toString())
        val input=phase60Hash("${auth.contextFingerprint}|${speaker.stateVersion}|${recipient.stateVersion}|${context.stagedEffects}")
        return MechanicsEffectResolution.Verified(VerifiedMechanicsEffect(request.effectUid,request.nodeUid,OWNER,"INTERACTION",payload,
            "P62:SPEECH:${phase60Hash(input)}",input,phase60Hash(payload.toSortedMap().toString())))
    }
}

internal sealed interface NpcInitiatedSpeechResult {
    data class Delivered(val effect:VerifiedMechanicsCommandEffect):NpcInitiatedSpeechResult
    data class Unavailable(val reasonUid:String):NpcInitiatedSpeechResult
}
internal fun interface NpcInitiatedSpeechPort {
    fun deliver(context:NpcDecisionContextEnvelope,selected:NpcDecisionResult.Selected,effect:VerifiedMechanicsCommandEffect,cancelled:()->Boolean):NpcInitiatedSpeechResult
    companion object { val NONE=NpcInitiatedSpeechPort{_,_,_,_->NpcInitiatedSpeechResult.Unavailable("P62:SPEECH_PROVIDER_NOT_WIRED")} }
}
internal class NpcInitiatedSpeechApplication(private val route:AiModelRoutePort,private val current:()->TemporalScope,
    private val progress:NpcWorkProgressPort=NpcWorkProgressPort.NONE):NpcInitiatedSpeechPort {
    override fun deliver(context:NpcDecisionContextEnvelope,selected:NpcDecisionResult.Selected,effect:VerifiedMechanicsCommandEffect,cancelled:()->Boolean):NpcInitiatedSpeechResult {
        fun fail(reason:String)=NpcInitiatedSpeechResult.Unavailable("P62:$reason")
        if(cancelled())return fail("CANCELLED")
        if(current()!=context.scope.temporal)return fail("STALE_SCOPE")
        val option=selected.option;val recipient=option.target?:return fail("SPEECH_RECIPIENT_REQUIRED")
        if(!selected.authorization.matches(context.scope,context.contextFingerprint,option) || option.mechanicsOwnerUid!=NpcSpeechMechanics.OWNER ||
            recipient.uid!=context.scope.activePlayerUid || effect.mechanicsOwnerUid!=NpcSpeechMechanics.OWNER || effect.effectKindUid!="INTERACTION" ||
            effect.target!=context.brain.actor || effect.effectUid!="P62:EFFECT:${selected.authorization.decisionUid}" ||
            context.computeFingerprint()!=context.contextFingerprint)return fail("SPEECH_AUTHORIZATION_MISMATCH")
        val uid="P62:INITIATE:${phase60Hash(selected.authorization.decisionUid+context.contextFingerprint)}"
        var compact=context
        var request=NpcDialogueRequest(uid,compact,"",NpcDialogueMode.INITIATE,recipient,option.goalUid)
        // Optional recall may be omitted, not the authorized recipient/goal evidence. The
        // original action authorization remains bound to the full projected context.
        while(runCatching{NpcDialogueCodec.encode(request)}.isFailure) {
            val optional=compact.records.lastOrNull{it.uid !in option.supportingRecordUids}?:break
            compact=NpcDecisionContextEnvelope(context.scope,context.trigger,context.brain,compact.records.filter{it.uid!=optional.uid},emptyList(),
                context.maximumInputUnits,context.projectionFingerprint,context.currentRoleUids)
            request=NpcDialogueRequest(uid,compact,"",NpcDialogueMode.INITIATE,recipient,option.goalUid)
        }
        val payload=runCatching{NpcDialogueCodec.encode(request)}.getOrNull()?:return fail("SPEECH_INPUT_BUDGET")
        val provider=(route.route(AiRole.GAME_MASTER,AiWorkload.NPC_DIALOGUE,(payload.length+3)/4+512) as? AiRouteResult.Selected)?.provider
            ?:return fail("SPEECH_PROVIDER_UNAVAILABLE")
        val result=try { progress.observe(context.scope.temporal.campaignUid,AiWorkload.NPC_DIALOGUE){provider.speakNpc(request,AiCancellationSignal(cancelled))} }
            catch(_:Exception){return fail(if(cancelled())"CANCELLED" else "SPEECH_PROVIDER_FAILED")}
        if(cancelled())return fail("CANCELLED")
        if(current()!=context.scope.temporal)return fail("STALE_SCOPE")
        if(result is AiProviderResult.Failure)return fail("SPEECH_AI:${result.reasonUid}")
        val candidate=(result as AiProviderResult.Success).value
        if(runCatching{NpcDialogueCodec.validate(candidate,request)}.isFailure)return fail("SPEECH_RESPONSE_REJECTED")
        val spoken=effect.copy(effectUid="${effect.effectUid}:SPEECH",mechanicsOwnerUid="RPGOS-CORE:NARRATIVE-MATERIALIZER",effectKindUid="NARRATIVE_EVENT",
            canonicalPayload=effect.canonicalPayload.filterKeys{it.startsWith("p60_core_")}+mapOf("predicate_uid" to GmNarrativePredicates.NPC_UTTERANCE,
                "narrative_text" to candidate.text,"target_kind_uid" to effect.target.kindUid,"target_uid" to effect.target.uid,"magnitude" to "1"),
            proofUid="RPGOS-CORE:NPC-INITIATED:${request.fingerprint}",deterministicInputFingerprint=request.fingerprint,deterministicOutputFingerprint=phase60Hash(candidate.text))
        return NpcInitiatedSpeechResult.Delivered(NpcCommunicationMemory.initiated(spoken,context,selected))
    }
}
