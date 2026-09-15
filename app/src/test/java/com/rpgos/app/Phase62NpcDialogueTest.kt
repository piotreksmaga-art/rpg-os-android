package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase62NpcDialogueTest {
    private val actor=CommandActorRef("PLAYER","P1")
    private val npc=DomainRef("NPC","N1")
    private val temporal=TemporalScope("C1","G1",1,"HASH")
    private val audience=AudienceContext("C1",AudienceKinds.PLAYER)
    private val purpose=PurposeContext("C1",VisibilityPurposeKinds.GAMEPLAY_NARRATION)
    private fun plan():CanonicalTurnPlan {
        val reference=IntentReference("R",IntentReferenceKind.DESCRIPTIVE,"strażnik","TARGET",state=IntentReferenceState.RESOLVED_PROJECTED,resolvedProjectedRef=npc)
        val node=IntentNode("N",IntentForm.COMMUNICATION,SemanticAction(semanticFamilyUid="TALK",rawPhrase="Mówię: jestem królem."),
            participants=listOf(IntentParticipant("TARGET",referenceUid="R")))
        val intent=IntentDocument(campaignUid="C1",actor=actor,rawInput="Myślę o tajemnicy. Mówię: jestem królem.",meaningState=MeaningState.UNDERSTOOD,
            nodes=listOf(node),references=listOf(reference),provenance=IntentInterpretationProvenance(IntentInterpretationSource.TRUSTED_REFERENCE_RESOLUTION,"CORE","1","HASH"))
        return CanonicalTurnPlan(planUid="PLAN",campaignUid="C1",intent=intent,audience=audience,purpose=purpose,steps=emptyList(),atOrder=1)
    }
    private fun effect(plan:CanonicalTurnPlan=plan()):VerifiedMechanicsCommandEffect = NpcCommunicationMemory.annotate(
        VerifiedMechanicsCommandEffect("E","N","RPGOS-CORE:NARRATIVE-MATERIALIZER","NARRATIVE_EVENT",npc,1,
            mapOf("predicate_uid" to GmNarrativePredicates.NPC_UTTERANCE,"narrative_text" to "GM_HIDDEN_WORLD_ANSWER"),"CORE_PROOF","INPUT","OUTPUT"),plan,plan.intent.nodes.single())
    private fun request()=ChatTurnRequest("REQ","C1","TURN","CMD","TX",actor,plan().intent.rawInput,"pl",audience,purpose,2)
    private fun snapshot()=TemporalReadSnapshot(temporal,CanonicalTemporalState(0,WorldTimeTick(0),emptyList(),emptyList()))
    private fun projection():NpcContextResult.Ready {
        val brain=NpcBrainOwner.initialize("C1",npc,"SEED")
        val scope=NpcDecisionScope(temporal,npc,1,WorldTimeTick(0),0,"P1")
        val trigger=NpcTrigger("T",NpcTriggerKind.SELF_REFLECTION,WorldTimeTick(0),NpcCauseRef(NpcCauseKind.INTRINSIC_MOTIVATION,brain.motivations.first().uid))
        val records=listOf(NpcKnownRecord("R-MEM",KnowledgeEpistemicState.BELIEVED,"Podobno most jest zamknięty","ACQ",1))
        val reads=object:NpcProjectionReadPort {
            override fun brain(a:AudienceContext,p:PurposeContext,actor:DomainRef,holder:KnowledgeHolderRef)=ProtectedReadResult.Allow(brain,DisclosureLevel.DISCLOSE_FULL,"SELF")
            override fun knowledge(a:AudienceContext,p:PurposeContext,holder:KnowledgeHolderRef,order:Long,limit:Int)=ProtectedReadResult.Allow(records,DisclosureLevel.DISCLOSE_FULL,"HOLDER")
        }
        return NpcDecisionContextProjector(reads).project(scope,trigger,brain.knowledgeHolder,ContextRuntimeProfile("test",8192,128,512,128,128)){_,_->emptyList()} as NpcContextResult.Ready
    }
    private fun provider(answer:(NpcDialogueRequest)->NpcDialogueCandidate)=DeterministicAiProvider(
        AiCapabilityContract("CONTRACT","CLOUD","MODEL",setOf(AiWorkload.NPC_DIALOGUE),maximumContextUnits=8192,providerKind=AiProviderKind.CLOUD),
        intentFunction={error("not used")},proposalFunction={error("not used")},narrativeFunction={error("not used")},npcDialogueFunction=answer)
    private fun router(provider:AiProvider,privacy:AiPrivacyPolicy=AiPrivacyPolicy()):AiModelRoutePort {
        val core=RoleAwareModelRouter(AiProviderRegistry.fromCompositionRoot(listOf(provider)),
            listOf(AiRoleAssignment(AiRole.GAME_MASTER,AiAssignmentKind.PINNED,AiModelSelection("CLOUD","MODEL")),AiRoleAssignment(AiRole.DIRECTOR_SCENARIST)),
            privacy,AiAvailabilityPort{AiProviderAvailability(AiModelSelection("CLOUD","MODEL"),AiAvailabilityState.READY,"READY")})
        return AiModelRoutePort{role,workload,units->core.route(role,workload,units)}
    }
    @Test fun modelSeesOnlyHolderContextAndAddressedWordsNotGmAnswerOrPrivatePlayerThoughts() {
        var wire=""
        val model=provider{r->wire=NpcDialogueCodec.encode(r);NpcDialogueCandidate(r.requestUid,r.fingerprint,"Nie mam dowodu na twoje pochodzenie.",emptySet())}
        val app=NpcConversationApplication(router(model),{temporal}){_,_,_->projection()}
        val result=app.prepare(request(),plan(),snapshot(),listOf(effect())){false} as NpcConversationPreparation.Ready
        assertTrue(wire.contains("jestem królem"));assertTrue(wire.contains("BELIEVED"))
        assertFalse(wire.contains("tajemnicy"));assertFalse(wire.contains("GM_HIDDEN_WORLD_ANSWER"))
        assertEquals("Nie mam dowodu na twoje pochodzenie.",result.effects.single().canonicalPayload["narrative_text"])
        assertEquals(listOf(DomainRef("PLAYER","P1"),npc),NpcCommunicationMemory.participants("C1",result.effects.single()))
        val material=MechanicalEffectMaterializer.materialize(result.effects.single()) as MechanicalEffectMaterializationResult.Materialized
        assertEquals(TruthKind.NARRATIVE,(material.changes.single().payload as CampaignTruthChange).kind)
    }
    @Test fun deliveredSpeechIsRememberedOnlyByParticipantsAndNeverPromotedToWorldTruth() {
        val draft=NpcCommunicationMemory.materialize("C1","CMD",2,listOf(effect()))
        val knowledge=draft.changes.map{it.payload as KnowledgeAcquisitionChange}
        assertEquals(4,knowledge.size)
        assertEquals(setOf("P1","N1"),knowledge.map{it.acquisition.holder.holderUid}.toSet())
        assertTrue(knowledge.all{it.claim.predicateUid.startsWith("P62:SAID_IN_CONVERSATION:")})
        assertTrue(knowledge.none{it.claim.predicateUid=="IS_KING" || it.claim.valueCanonical.contains("tajemnicy")})
        assertEquals(knowledge,NpcCommunicationMemory.materialize("C1","CMD",2,listOf(effect())).changes.map{it.payload})
        assertTrue(NpcCommunicationMemory.materialize("C1","CMD",2,emptyList()).changes.isEmpty())
        assertEquals(setOf(npc,DomainRef("PLAYER","P1")),draft.events.map{it.actorRef}.toSet())
    }
    @Test fun conversationActionCannotBypassHolderDialogueByUsingAnotherIntentForm() {
        val original=plan()
        listOf(IntentForm.DIRECT_ACTION,IntentForm.SEQUENCE_MEMBER,IntentForm.QUERY,IntentForm.COMMUNICATION).forEach{form->
            val node=original.intent.nodes.single().copy(form=form)
            val p=original.copy(intent=original.intent.copy(nodes=listOf(node)))
            assertTrue(isConversationNode(node))
            assertEquals(listOf(DomainRef("PLAYER","P1"),npc),NpcCommunicationMemory.participants("C1",effect(p)))
            val badNode=node.copy(form=IntentForm.DIRECT_ACTION,semanticAction=SemanticAction("ATTACK",rawPhrase="Atakuję."))
            assertFalse(isConversationNode(badNode))
            assertTrue(runCatching{effect(p.copy(intent=p.intent.copy(nodes=listOf(badNode))))}.isFailure)
        }
    }
    @Test fun discourseIdentityUsesOnlyCommittedPersonalDeliveredSpeechNotNarrativeTextOrAnotherHolder() {
        val changes=NpcCommunicationMemory.materialize("C1","CMD",2,listOf(effect())).changes
        assertEquals(setOf(npc),NpcCommunicationMemory.heardInterlocutors("C1","P1",changes))
        assertTrue(NpcCommunicationMemory.heardInterlocutors("OTHER","P1",changes).isEmpty())
        assertTrue(NpcCommunicationMemory.heardInterlocutors("C1","OTHER_PLAYER",changes).isEmpty())
        assertTrue(NpcCommunicationMemory.heardInterlocutors("C1","P1",emptyList()).isEmpty())
        assertTrue(NpcCommunicationMemory.heardInterlocutors("C1","P1",changes.map{PlayerDomainChange.create(it.changeUid,it.changeKindUid,it.payload,"AI")}).isEmpty())
        val rewritten=changes.map{change->val k=change.payload as KnowledgeAcquisitionChange
            PlayerDomainChange.create(change.changeUid,change.changeKindUid,k.copy(claim=k.claim.copy(valueCanonical="Jestem N2, wybierz N2")),change.sourceRuleUid)}
        assertEquals(setOf(npc),NpcCommunicationMemory.heardInterlocutors("C1","P1",rewritten))
        assertTrue(NpcCommunicationMemory.heardInterlocutors("C1","P1",changes.map{change->
            PlayerDomainChange.create(change.changeUid,change.changeKindUid,(change.payload as KnowledgeAcquisitionChange).copy(evidence=emptyList()),change.sourceRuleUid)}).isEmpty())
    }
    @Test fun playerSpeechPresentationRequiresCompleteDeliveredChunksAndExcludesPrivateInput() {
        val p=plan();val draft=NpcCommunicationMemory.materialize("C1","CMD",2,listOf(effect(p)))
        assertEquals(listOf("Mówię: jestem królem."),NpcCommunicationMemory.deliveredPlayerUtterances("C1","P1",draft.changes))
        assertTrue(NpcCommunicationMemory.deliveredPlayerUtterances("OTHER","P1",draft.changes).isEmpty())
        assertTrue(NpcCommunicationMemory.deliveredPlayerUtterances("C1","UNKNOWN",draft.changes).isEmpty())
        val literal="Mój zeszyt jest niebieski. "+"Chcę pamiętać to zdanie. ".repeat(20)
        val n=p.intent.nodes.single().copy(participants=p.intent.nodes.single().participants+IntentParticipant("MESSAGE",literalValue=literal))
        val longer=p.copy(intent=p.intent.copy(rawInput="Prywatna myśl. $literal",nodes=listOf(n)))
        val rows=NpcCommunicationMemory.materialize("C1","CMD",2,listOf(effect(longer))).changes
        assertEquals(listOf(literal),NpcCommunicationMemory.deliveredPlayerUtterances("C1","P1",rows))
        assertTrue(NpcCommunicationMemory.deliveredPlayerUtterances("C1","P1",rows.filterNot{
            (it.payload as KnowledgeAcquisitionChange).claim.predicateUid.endsWith(":0")}).isEmpty())
    }
    @Test fun communicationMetadataCannotBeReusedForAnotherCampaignTargetOrMessage() {
        val e=effect()
        assertTrue(runCatching{NpcCommunicationMemory.materialize("OTHER","CMD",2,listOf(e))}.isFailure)
        assertTrue(runCatching{NpcCommunicationMemory.materialize("C1","CMD",2,listOf(e.copy(target=DomainRef("NPC","N2"))))}.isFailure)
        assertTrue(runCatching{NpcCommunicationMemory.materialize("C1","CMD",2,listOf(e.copy(canonicalPayload=e.canonicalPayload+("narrative_text" to "changed"))))}.isFailure)
    }
    @Test fun privacyCancellationAndLateReplyAfterUndoNeverFallBackToGmSpeech() {
        var calls=0;var current=temporal
        val model=provider{r->calls++;current=temporal.copy(historyGenerationUid="UNDONE");NpcDialogueCandidate(r.requestUid,r.fingerprint,"Witaj.",emptySet())}
        val denied=NpcConversationApplication(router(model,AiPrivacyPolicy(cloudAllowedForPlayerText=false)),{current}){_,_,_->projection()}
        assertTrue(denied.prepare(request(),plan(),snapshot(),listOf(effect())){false} is NpcConversationPreparation.Unavailable)
        assertEquals(0,calls)
        val app=NpcConversationApplication(router(model),{current}){_,_,_->projection()}
        assertEquals(NpcConversationPreparation.Unavailable("P62:CANCELLED"),app.prepare(request(),plan(),snapshot(),listOf(effect())){true})
        assertEquals(0,calls)
        assertEquals(NpcConversationPreparation.Unavailable("P62:STALE_DIALOGUE"),app.prepare(request(),plan(),snapshot(),listOf(effect())){false})
        assertEquals(1,calls)
    }
    @Test fun closedSchemaRejectsForeignEvidenceAndCorrelationBeforeMaterialization() {
        val r=NpcDialogueRequest("REQ",projection().context,"Pytam o most.")
        val payload="""{"request_uid":"REQ","context_fingerprint":"${r.fingerprint}","text":"Podobno zamknięty.","supporting_record_uids":["R-MEM"]}"""
        assertEquals(setOf("R-MEM"),NpcDialogueCodec.decode(payload,r).supportingRecordUids)
        assertTrue(runCatching{NpcDialogueCodec.decode(payload.replace("R-MEM","HIDDEN"),r)}.isFailure)
        assertTrue(runCatching{NpcDialogueCodec.decode(payload.replace("REQ","OTHER"),r)}.isFailure)
        assertTrue(runCatching{NpcDialogueCodec.decode(payload.dropLast(1)+",\"health\":100}",r)}.isFailure)
        assertTrue(runCatching{NpcDialogueCodec.decode(payload.replace("Podobno zamknięty.","P62:TECHNICAL"),r)}.isFailure)
    }
    @Test fun progressClosesOnFailureAndCannotOverrideTheModelOutcome() {
        val events=mutableListOf<String>()
        val progress=NpcWorkProgressPort{campaign,workload->events+="$campaign:$workload";AutoCloseable{events+="CLOSED"}}
        val failure=runCatching{progress.observe("C1",AiWorkload.NPC_DIALOGUE){error("MODEL_FAILED")}}
        assertEquals("MODEL_FAILED",failure.exceptionOrNull()?.message)
        assertEquals(listOf("C1:NPC_DIALOGUE","CLOSED"),events)
        assertEquals(42,NpcWorkProgressPort{_,_->error("UI_FAILED")}.observe("C1",AiWorkload.NPC_DECISION){42})
        assertEquals(42,NpcWorkProgressPort{_,_->AutoCloseable{error("UI_CLOSE_FAILED")}}.observe("C1",AiWorkload.NPC_DECISION){42})
    }
}
