package com.rpgos.app

/** Initiation is an admitted action, not an administrative write or an inferred background
 * intention. Rule parameters are fixed; Core substitutes only the two authorized references. */
internal object Phase64ProcessActivation {
    const val ACTION_KEY="activation_action_uid"
    const val PUBLIC_KEY="activation_public"
    const val START_PROOF="P64:START:"
    const val CANCEL_PROOF="P64:CANCEL:"
    const val CANCEL_ACTION="CANCEL_BACKGROUND_PROCESS"
    const val OWN_ACQUISITION="@OWN_ACQUISITION_UID"
    fun bind(definition:BackgroundProcessDefinition,actor:DomainRef,target:DomainRef,message:String?=null,processUid:String?=null,sourceAcquisitionUid:String?=null):Map<String,String> =
        phase64BindProjectLabour(definition,actor,target,Phase64EconomyRuleCatalog.bind(definition,actor,target,definition.parameters.filterKeys { !it.startsWith("activation_") }.mapValues { (_,value)->when(value) {
            "@ACTOR_UID"->actor.uid;"@ACTOR_KIND"->actor.kindUid
            "@TARGET_UID"->target.uid;"@TARGET_KIND"->target.kindUid
            "@MESSAGE_LITERAL"->requireNotNull(message).also { require(Phase64NeutralCommunicationOwner.matchesDefinition(definition) && it.isNotBlank() && it.length<=2048) {"P64:MESSAGE_LITERAL_REQUIRED"} }
            "@PROCESS_UID"->requireNotNull(processUid).also { require(Phase64NeutralCommunicationOwner.matchesDefinition(definition)) }
            OWN_ACQUISITION->requireNotNull(sourceAcquisitionUid).also {
                require(definition.domain=="INFORMATION" && definition.operation=="REPORT" && actor.kindUid!="PLAYER" && it.isNotBlank() && it.length<=160) {"P64:OWN_SOURCE_REQUIRED"}
            }
            else->value
        } })).also { values->require(values.size<=58 && values.keys.none { it.startsWith("p64_") }) {"P64:RESERVED_ACTIVATION_PARAMETER"} }
    fun resolveCancellation(process:BackgroundProcessInstance,request:MechanicsEffectRequest,context:MechanicsResolutionContext,node:IntentNode,actor:DomainRef):MechanicsEffectResolution {
        if(actor!=process.actor || actor.kindUid!="PLAYER" || process.status !in setOf(BackgroundProcessStatus.ACTIVE,BackgroundProcessStatus.BLOCKED) ||
            request.targetProjectedRef!=DomainRef("WORLD_PROCESS",process.uid) || request.parameters.isNotEmpty() || context.npcAuthorization!=null ||
            request.mechanicsOwnerUid!="UNIVERSAL_ACTION" || request.effectKindUid!="INTERACTION" || node.semanticAction.canonicalActionUid!=CANCEL_ACTION ||
            context.plan.intent.nodes.count { it.modality==IntentModality.ATTEMPT_NOW }!=1)
            return MechanicsEffectResolution.Rejected("P64:CANCELLATION_NOT_AUTHORIZED")
        val input=phase63Hash("${context.plan.intent.canonicalFingerprint()}|${Phase64BackgroundCodec.process(process)}|${request.effectUid}")
        val fields=mapOf("track_uid" to "ACTION:P64_CANCEL:${process.uid}","magnitude" to "1","target_kind_uid" to actor.kindUid,"target_uid" to actor.uid,
            "p64_cancel_process" to process.uid,"p64_cancel_version" to process.version.toString(),
            "p60_core_timing_rule" to "P64:INITIATION_MS_V1","p60_core_timing_version" to "1","p60_core_duration_ms" to "1000","p60_core_effect_at_ms" to "1000")
        val output=phase63Hash(fields.toSortedMap().toString())
        return MechanicsEffectResolution.Verified(VerifiedMechanicsEffect(request.effectUid,request.nodeUid,"UNIVERSAL_ACTION","INTERACTION",fields,
            "$CANCEL_PROOF${phase63Hash(input+output)}",input,output))
    }
    fun cancel(scope:TemporalScope,process:BackgroundProcessInstance,effect:VerifiedMechanicsCommandEffect,at:WorldTimeTick):BackgroundProcessChange {
        require(effect.proofUid.startsWith(CANCEL_PROOF) && effect.mechanicsOwnerUid=="UNIVERSAL_ACTION" && effect.effectKindUid=="INTERACTION" && effect.magnitude==1L)
        require(effect.target==process.actor && effect.canonicalPayload["p64_cancel_process"]==process.uid && effect.canonicalPayload["p64_cancel_version"]==process.version.toString())
        // If completion falls inside the cancellation action, normal time execution wins.
        // Do not reverse an already evaluated result or claim to cancel it retroactively.
        require(at<process.due) {"P64:CANCELLATION_DEADLINE_REACHED"}
        return BackgroundProcessChange(scope.campaignUid,scope.historyGenerationUid,process.version,
            process.copy(version=Math.addExact(process.version,1),status=BackgroundProcessStatus.INTERRUPTED,reasonUid="P64:PLAYER_CANCELLED"),
            WorldProcessEvidence("P64:CANCEL-EVIDENCE:${phase63Hash(process.uid+effect.proofUid)}",process.uid,process.definitionUid,process.definitionVersion,listOf(effect.proofUid),at,"P64:PLAYER_CANCELLED"))
    }
    fun resolve(definition:BackgroundProcessDefinition,policy:String,request:MechanicsEffectRequest,
                context:MechanicsResolutionContext,node:IntentNode,actor:DomainRef):MechanicsEffectResolution {
        fun reject(reason:String)=MechanicsEffectResolution.Rejected(reason)
        val target=request.targetProjectedRef?:return reject("P64:ACTIVATION_TARGET_REQUIRED")
        val npc=context.npcAuthorization
        val usesOwnSource=OWN_ACQUISITION in definition.parameters.values
        if((if(npc==null)definition.parameters[PUBLIC_KEY]!="true" else definition.parameters["activation_npc"]!="true") || definition.parameters[ACTION_KEY]!=node.semanticAction.canonicalActionUid)
            return reject("P64:REGISTERED_ACTIVATION_REQUIRED")
        if(request.mechanicsOwnerUid!="UNIVERSAL_ACTION" || request.effectKindUid!="INTERACTION" ||
            (if(usesOwnSource)request.parameters.keys!=setOf("p64_source_acquisition_uid") else request.parameters.isNotEmpty()))
            return reject("P64:ACTIVATION_CONTRACT_MISMATCH")
        if((npc==null && actor.kindUid!="PLAYER" || npc!=null && (npc.scope.actor!=actor || actor.uid==npc.scope.activePlayerUid)) || context.plan.intent.nodes.count { it.modality==IntentModality.ATTEMPT_NOW }!=1)
            return reject("P64:SINGLE_PLAYER_INITIATION_REQUIRED")
        if(target!=actor && context.plan.intent.references.none { it.resolvedProjectedRef==target })return reject("P64:UNAUTHORIZED_ACTIVATION_TARGET")
        val hash=phase63Hash(Phase64BackgroundCodec.definition(definition).toString())
        val process="P64:PROCESS:${phase63Hash(listOf(context.campaignUid,context.plan.intent.canonicalFingerprint(),request.effectUid,definition.uid)
            .joinToString("") { "${it.length}:$it" })}"
        val message=if(Phase64NeutralCommunicationOwner.matchesDefinition(definition)) {
            node.participants.singleOrNull { it.roleUid=="MESSAGE" }?.literalValue?.takeIf { it.isNotBlank() && it.length<=2048 }
                ?:return reject("P64:MESSAGE_LITERAL_REQUIRED")
        } else null
        val ownSource=if(usesOwnSource) {
            val source=request.parameters["p64_source_acquisition_uid"] ?: return reject("P64:OWN_SOURCE_REQUIRED")
            if(npc==null || !npc.authorizesMechanics(npc.scope.temporal,context.plan,node,request))
                return reject("P64:OWN_SOURCE_REQUIRED")
            source
        } else null
        val bound=runCatching { bind(definition,actor,target,message,process,ownSource) }.getOrElse { return reject("P64:ACTIVATION_PARAMETERS_INVALID") }
        val input=phase63Hash("$policy|$hash|${context.plan.intent.canonicalFingerprint()}|$actor|$target|$process")
        val fields=mapOf("track_uid" to "ACTION:P64_START:$process","magnitude" to "1","target_kind_uid" to actor.kindUid,"target_uid" to actor.uid,
            "p64_start_rule" to definition.uid,"p64_start_version" to definition.version.toString(),"p64_start_fingerprint" to hash,
            "p64_start_process" to process,"p64_start_target_kind" to target.kindUid,"p64_start_target_uid" to target.uid,
            "p60_core_timing_rule" to "P64:INITIATION_MS_V1","p60_core_timing_version" to "1","p60_core_duration_ms" to "1000","p60_core_effect_at_ms" to "1000") +
            (npc?.let { mapOf("p64_start_decision_uid" to it.decisionUid) } ?: emptyMap())+
            (ownSource?.let { mapOf("p64_start_source_acquisition_uid" to it) } ?: emptyMap())+
            (message?.chunked(512)?.let { chunks->mapOf("p64_start_message_chunks" to chunks.size.toString())+chunks.mapIndexed { index,part->"p64_start_message_$index" to part }.toMap() }?:emptyMap())
        val output=phase63Hash(fields.toSortedMap().toString())
        return MechanicsEffectResolution.Verified(VerifiedMechanicsEffect(request.effectUid,request.nodeUid,"UNIVERSAL_ACTION","INTERACTION",fields,
            "$START_PROOF$hash:${phase63Hash(input+output)}"+(if(message==null && ownSource==null)"" else ":${parameterFingerprint(bound)}"),input,output))
    }
    fun start(scope:TemporalScope,commandUid:String,definition:BackgroundProcessDefinition,effect:VerifiedMechanicsCommandEffect,at:WorldTimeTick):BackgroundProcessChange {
        require(effect.proofUid.startsWith(START_PROOF) && effect.mechanicsOwnerUid=="UNIVERSAL_ACTION" && effect.effectKindUid=="INTERACTION" && effect.magnitude==1L)
        val p=effect.canonicalPayload
        val hash=phase63Hash(Phase64BackgroundCodec.definition(definition).toString())
        require(p["p64_start_rule"]==definition.uid && p["p64_start_version"]==definition.version.toString() && p["p64_start_fingerprint"]==hash)
        require(effect.proofUid.startsWith("$START_PROOF$hash:"))
        val uid=requireNotNull(p["p64_start_process"])
        require(p["track_uid"]=="ACTION:P64_START:$uid")
        require(effect.target.kindUid=="PLAYER" || definition.parameters["activation_npc"]=="true" &&
            p["p64_start_decision_uid"]?.startsWith("P62:DECISION:")==true &&
            p["source_actor_kind_uid"]==effect.target.kindUid && p["source_actor_uid"]==effect.target.uid) {"P64:NPC_INITIATION_AUTHORITY_REQUIRED"}
        val target=DomainRef(requireNotNull(p["p64_start_target_kind"]),requireNotNull(p["p64_start_target_uid"]))
        val message=if(Phase64NeutralCommunicationOwner.matchesDefinition(definition)) {
            val count=requireNotNull(p["p64_start_message_chunks"]?.toIntOrNull());require(count in 1..4)
            (0 until count).joinToString("") { requireNotNull(p["p64_start_message_$it"]) }
        } else null
        val ownSource=if(OWN_ACQUISITION in definition.parameters.values)requireNotNull(p["p64_start_source_acquisition_uid"]) else null
        val bound=bind(definition,effect.target,target,message,uid,ownSource)
        if(message!=null || ownSource!=null)require(effect.proofUid.endsWith(":${parameterFingerprint(bound)}")){"P64:BOUND_SOURCE_PROOF_MISMATCH"}
        val parameters=bound+mapOf("p64_start_command_uid" to commandUid,"p64_start_proof_uid" to effect.proofUid,
            "p64_initiator_kind_uid" to effect.target.kindUid,"p64_initiator_uid" to effect.target.uid,
            "p64_start_target_kind" to target.kindUid,"p64_start_target_uid" to target.uid)+
            (ownSource?.let { mapOf("p64_start_source_acquisition_uid" to it) } ?: emptyMap())
        val process=BackgroundProcessInstance(uid,definition.uid,definition.version,effect.target,1,at,
            WorldTimeTick(Math.addExact(at.milliseconds,definition.durationMillis)),parameters=parameters)
        return BackgroundProcessChange(scope.campaignUid,scope.historyGenerationUid,0,process,
            WorldProcessEvidence("P64:START-EVIDENCE:${phase63Hash(uid+effect.proofUid)}",uid,definition.uid,definition.version,listOfNotNull(effect.proofUid,ownSource),at))
    }
    internal fun parameterFingerprint(parameters:Map<String,String>)=phase63Hash(kotlinx.serialization.json.JsonObject(parameters.toSortedMap().mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }).toString())
}
