package com.rpgos.app

/** Evidence of a delivered exchange, not evidence that its assertions are true. Only the exact
 * participants of the accepted conversation acquire this record; co-location is not perception. */
internal object NpcCommunicationMemory {
    private const val RULE="P62:DELIVERED_COMMUNICATION:1"
    const val PLAYER_UTTERANCE="P62:DELIVERED_PLAYER_UTTERANCE"
    data class HeardUtterance(val speaker:DomainRef,val text:String)
    /** Read only from the exact committed replay. Background process effects are otherwise
     * private; an acquisition delivered to this player is the authority to present speech. */
    fun heardNpcUtterances(campaign:String,playerUid:String,changes:List<PlayerDomainChange>):List<HeardUtterance> = changes.asSequence()
        .filter{it.sourceRuleUid==RULE}.mapNotNull{it.payload as? KnowledgeAcquisitionChange}
        .filter{it.acquisition.holder==KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER,playerUid,campaign) &&
            it.claim.subjectUid!=playerUid && it.acquisition.methodUid==KnowledgeAcquisitionMethods.DIRECT_COMMUNICATION &&
            it.acquisition.scope==KnowledgeScope.PERSONAL && it.claim.predicateUid.startsWith("P62:SAID_IN_CONVERSATION:")}
        .filter{change->change.evidence.any{it.evidenceKindUid=="P62:DELIVERED_UTTERANCE" &&
            it.polarity==KnowledgeEvidencePolarity.SUPPORTS &&
            it.sourceRef==KnowledgeSourceRef.campaign(campaign,change.claim.subjectKindUid,change.claim.subjectUid)}}
        .groupBy{DomainRef(it.claim.subjectKindUid,it.claim.subjectUid)}.mapNotNull{(speaker,parts)->
            val chunks=parts.map{it.claim.predicateUid.substringAfterLast(':').toIntOrNull() to it.claim.valueCanonical}.distinct()
            if(chunks.isEmpty() || chunks.size>9 || chunks.any{it.first==null} || chunks.map{it.first}.distinct().size!=chunks.size)return@mapNotNull null
            val ordered=chunks.sortedBy{it.first}
            if(ordered.map{it.first}!=ordered.indices.toList())return@mapNotNull null
            ordered.joinToString(""){it.second}.takeIf{it.isNotBlank() && it.length<=2048}?.let{HeardUtterance(speaker,it)}
        }.sortedWith(compareBy({it.speaker.kindUid},{it.speaker.uid},{it.text}))
    /** Exact delivered player speech, not the player's private input. It is presentation
     * evidence ("said X"), never proof of X. Missing/ambiguous chunks grant no quote exemption. */
    fun deliveredPlayerUtterances(campaign:String,playerUid:String,changes:List<PlayerDomainChange>):List<String> = changes.asSequence()
        .filter{it.sourceRuleUid==RULE}.mapNotNull{it.payload as? KnowledgeAcquisitionChange}
        .filter{it.claim.subjectUid==playerUid && it.acquisition.holder.campaignUid==campaign &&
            it.acquisition.holder.holderKindUid==KnowledgeHolderKinds.CHARACTER && it.acquisition.holder.holderUid!=playerUid &&
            it.acquisition.methodUid==KnowledgeAcquisitionMethods.DIRECT_COMMUNICATION && it.acquisition.scope==KnowledgeScope.PERSONAL &&
            it.claim.predicateUid.startsWith("P62:SAID_IN_CONVERSATION:")}
        .filter{change->change.evidence.any{it.evidenceKindUid=="P62:DELIVERED_UTTERANCE" &&
            it.polarity==KnowledgeEvidencePolarity.SUPPORTS &&
            it.sourceRef==KnowledgeSourceRef.campaign(campaign,change.claim.subjectKindUid,playerUid)}}
        .groupBy{it.acquisition.holder}.values.mapNotNull{parts->
            val chunks=parts.map{it.claim.predicateUid.substringAfterLast(':').toIntOrNull() to it.claim.valueCanonical}.distinct()
            if(chunks.isEmpty() || chunks.size>9 || chunks.any{it.first==null} || chunks.map{it.first}.distinct().size!=chunks.size)return@mapNotNull null
            val ordered=chunks.sortedBy{it.first}
            if(ordered.map{it.first}!=(ordered.indices).toList())return@mapNotNull null
            ordered.joinToString(""){it.second}.takeIf{it.isNotBlank() && it.length<=2048}
        }.distinct()
    /** Call only on an exact committed replay. Text, including assertions of identity inside
     * speech, is deliberately ignored; Phase37's delivered acquisition owns the participants. */
    fun heardInterlocutors(campaign:String,activePlayerUid:String,changes:List<PlayerDomainChange>):Set<DomainRef> = changes.asSequence()
        .filter{it.sourceRuleUid==RULE}.mapNotNull{it.payload as? KnowledgeAcquisitionChange}
        .filter{it.acquisition.holder==KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER,activePlayerUid,campaign) &&
            it.acquisition.methodUid==KnowledgeAcquisitionMethods.DIRECT_COMMUNICATION &&
            it.acquisition.scope==KnowledgeScope.PERSONAL && it.claim.subjectUid!=activePlayerUid &&
            it.claim.predicateUid.startsWith("P62:SAID_IN_CONVERSATION:")}
        .filter{change->change.evidence.any{it.evidenceKindUid=="P62:DELIVERED_UTTERANCE" &&
            it.polarity==KnowledgeEvidencePolarity.SUPPORTS &&
            it.sourceRef==KnowledgeSourceRef.campaign(campaign,change.claim.subjectKindUid,change.claim.subjectUid)}}
        .mapTo(linkedSetOf()){DomainRef(it.claim.subjectKindUid,it.claim.subjectUid)}
    fun annotate(effect:VerifiedMechanicsCommandEffect,plan:CanonicalTurnPlan,node:IntentNode,addressedNodes:List<IntentNode> = listOf(node)):VerifiedMechanicsCommandEffect {
        require(effect.effectKindUid=="NARRATIVE_EVENT" && effect.canonicalPayload["predicate_uid"]==GmNarrativePredicates.NPC_UTTERANCE)
        require(effect.target in projectedTargetRefs(plan.intent,node) && isConversationNode(node))
        // rawPhrase belongs to this addressed node, unlike the full turn which may also contain
        // private player thoughts or a separate conversation with somebody else.
        require(addressedNodes.isNotEmpty() && addressedNodes.all{it in plan.intent.nodes && effect.target in projectedTargetRefs(plan.intent,it) && isConversationNode(it)})
        val message=addressedNodes.joinToString("\n"){addressed->
            val messages=addressed.participants.filter{it.roleUid=="MESSAGE"}
            require(messages.size<=1 && messages.all{it.literalValue!=null}) { "P62:COMMUNICATION_MESSAGE_INVALID" }
            val literal=messages.singleOrNull()?.literalValue
            if(literal!=null){
                require(plan.intent.rawInput.contains(literal)){"P62:COMMUNICATION_MESSAGE_NOT_GROUNDED"}
                literal
            } else {
                // Compact legacy decoding may put the ENTIRE player's input in rawPhrase.
                // It cannot serve as proof of what this particular recipient was told.
                require(plan.intent.provenance.sourceUid!="LOCAL_COMPACT_INTENT") { "P62:COMMUNICATION_MESSAGE_REQUIRED" }
                addressed.semanticAction.rawPhrase
            }
        }
        require(message.isNotBlank() && message.length<=2048){"P62:COMMUNICATION_INPUT_LIMIT"}
        val fields=mapOf("communication_rule" to RULE,"communication_campaign" to plan.campaignUid,
            "communication_initiator_kind" to plan.intent.actor.actorKindUid,"communication_initiator_uid" to plan.intent.actor.actorUid)+
            chunks(message,512).mapIndexed{index,part->"communication_input_$index" to part}.toMap()
        return effect.copy(canonicalPayload=effect.canonicalPayload+fields+
            ("communication_fingerprint" to fingerprint(effect,fields)))
    }
    private fun fingerprint(effect:VerifiedMechanicsCommandEffect,fields:Map<String,String>)=phase60Hash(
        "$RULE|${effect.effectUid}|${effect.nodeUid}|${effect.target}|${effect.canonicalPayload["narrative_text"]}|"+
            fields.toSortedMap().entries.joinToString("|"){"${it.key.length}:${it.key}:${it.value.length}:${it.value}"})
    private fun fields(effect:VerifiedMechanicsCommandEffect)=effect.canonicalPayload.filterKeys{it.startsWith("communication_") && it!="communication_fingerprint"}
    internal fun replaceReply(effect:VerifiedMechanicsCommandEffect,authorizedFields:Map<String,String>):VerifiedMechanicsCommandEffect {
        val fields=authorizedFields.filterKeys{it!="communication_fingerprint"}
        require(fields["communication_rule"]==RULE)
        return effect.copy(canonicalPayload=effect.canonicalPayload+fields+("communication_fingerprint" to fingerprint(effect,fields)))
    }
    fun participants(campaign:String,effect:VerifiedMechanicsCommandEffect):List<DomainRef> {
        if(effect.canonicalPayload["communication_rule"]!=RULE)return emptyList()
        require(effect.effectKindUid=="NARRATIVE_EVENT" && effect.canonicalPayload["predicate_uid"]==GmNarrativePredicates.NPC_UTTERANCE)
        require(effect.canonicalPayload["communication_campaign"]==campaign &&
            effect.canonicalPayload["communication_fingerprint"]==fingerprint(effect,fields(effect))) { "P62:COMMUNICATION_SCOPE_OR_CONTENT_MISMATCH" }
        val sender=DomainRef(requireNotNull(effect.canonicalPayload["communication_initiator_kind"]),requireNotNull(effect.canonicalPayload["communication_initiator_uid"]))
        if(effect.canonicalPayload["communication_mode"]!=null) {
            require(effect.canonicalPayload["communication_mode"]=="NPC_INITIATED" && sender==effect.target)
            val recipient=DomainRef(requireNotNull(effect.canonicalPayload["communication_recipient_kind"]),requireNotNull(effect.canonicalPayload["communication_recipient_uid"]))
            require(recipient!=sender)
            return listOf(sender,recipient)
        }
        require(sender!=effect.target)
        return listOf(sender,effect.target)
    }
    internal fun initiated(effect:VerifiedMechanicsCommandEffect,context:NpcDecisionContextEnvelope,selected:NpcDecisionResult.Selected):VerifiedMechanicsCommandEffect {
        require(selected.authorization.matches(context.scope,context.contextFingerprint,selected.option) &&
            selected.option.mechanicsOwnerUid==NpcSpeechMechanics.OWNER && effect.target==context.brain.actor &&
            effect.effectKindUid=="NARRATIVE_EVENT" && effect.canonicalPayload["predicate_uid"]==GmNarrativePredicates.NPC_UTTERANCE)
        val recipient=requireNotNull(selected.option.target)
        require(recipient.uid==context.scope.activePlayerUid && recipient!=effect.target && context.records.any{recipient in it.subjectRefs})
        val fields=mapOf("communication_rule" to RULE,"communication_mode" to "NPC_INITIATED","communication_campaign" to context.scope.temporal.campaignUid,
            "communication_initiator_kind" to effect.target.kindUid,"communication_initiator_uid" to effect.target.uid,
            "communication_recipient_kind" to recipient.kindUid,"communication_recipient_uid" to recipient.uid,
            "communication_generation" to context.scope.temporal.historyGenerationUid,"communication_authorization" to selected.authorization.decisionUid)
        return effect.copy(canonicalPayload=effect.canonicalPayload+fields+("communication_fingerprint" to fingerprint(effect,fields)))
    }
    fun materialize(campaign:String,commandUid:String,order:Long?,effects:List<VerifiedMechanicsCommandEffect>):NpcActionMemory.Draft {
        val changes=mutableListOf<PlayerDomainChange>();val events=mutableListOf<PlayerEventIntent>()
        effects.forEach { effect ->
            val participants=participants(campaign,effect)
            if(participants.isEmpty())return@forEach
            val (player,npc)=participants
            val input=(0..4).mapNotNull{effect.canonicalPayload["communication_input_$it"]}.joinToString("")
            val messages=if(effect.canonicalPayload["communication_mode"]=="NPC_INITIATED")
                listOf(player to requireNotNull(effect.canonicalPayload["narrative_text"]))
            else listOf(player to input,npc to requireNotNull(effect.canonicalPayload["narrative_text"]))
            messages.forEach { (speaker,text) ->
                require(text.isNotBlank() && text.length<=2048)
                // Whole messages remain in narrative evidence. Bounded chunks make legal holder
                // recall possible even for a long sentence without changing what was said.
                chunks(text,240).forEachIndexed { ordinal,part -> participants.forEach { listener ->
                    val id=phase60Hash("$RULE|$campaign|$commandUid|${effect.effectUid}|$speaker|$listener|$ordinal")
                    val holder=KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER,listener.uid,campaign)
                    val claim=KnowledgeClaim("P62:COMM-CLAIM:$id",speaker.kindUid,speaker.uid,
                        "P62:SAID_IN_CONVERSATION:$ordinal",part,domainUid=KnowledgeDomains.WORLD_SPECIFIC)
                    val acquisition=KnowledgeAcquisitionChange(claim,KnowledgeAcquisitionSpec("P62:COMM-ACQ:$id",holder,
                        if(speaker==listener)KnowledgeAcquisitionMethods.DIRECT_OBSERVATION else KnowledgeAcquisitionMethods.DIRECT_COMMUNICATION,
                        KnowledgeScope.PERSONAL,KnowledgeEpistemicState.KNOWN,KnowledgeQuality(1.0,1.0,1.0,1.0,1,order)),
                        listOf(KnowledgeEvidenceSpec("P62:COMM-EVIDENCE:$id","P62:DELIVERED_UTTERANCE",KnowledgeEvidencePolarity.SUPPORTS,
                            sourceRef=KnowledgeSourceRef.campaign(campaign,speaker.kindUid,speaker.uid))))
                    val change=PlayerDomainChange.create("P62:COMM-MEMORY:$id",PHASE37_KNOWLEDGE_CHANGE_KIND,acquisition,sourceRuleUid=RULE)
                    changes+=change
                    val ref=DomainRef(holder.holderKindUid,holder.holderUid)
                    events+=PlayerEventIntent.create("P62:COMM-EVENT:$id",PlayerEventIntentKinds.DOMAIN_EFFECT,speaker,listOf(ref),listOf(change.changeUid),
                        DomainEffectEventIntentPayload(ref,"RPGOS-EFFECT:KNOWLEDGE_ACQUISITION"))
                } }
            }
        }
        return NpcActionMemory.Draft(changes,events)
    }
    private fun chunks(text:String,maximum:Int):List<String> = buildList {
        var start=0
        while(start<text.length){
            var end=(start+maximum).coerceAtMost(text.length)
            if(end<text.length && text[end-1].isHighSurrogate())end--
            add(text.substring(start,end));start=end
        }
    }
}
