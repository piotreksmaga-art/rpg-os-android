package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase62NpcAutonomousSpeechTest {
    private val npc=DomainRef("NPC","N")
    private val player=DomainRef("PLAYER","P")
    private var scope=TemporalScope("C","H",1,"STATE")
    private val record=NpcKnownRecord("R",KnowledgeEpistemicState.KNOWN,"Usłyszałem rozmówcę.","A",1,setOf(player))
    private var brain=NpcBrainOwner.initialize("C",npc,"seed").let{it.copy(goals=listOf(NpcGoal("G",it.motivations.first().uid,
        "Zaproponować wspólny spacer",NpcWeight(7000),NpcGoalLifecycle.ACTIVE,NpcCauseRef(NpcCauseKind.KNOWLEDGE_ACQUISITION,"A"))))}
    private var speaker=MechanicalActorView("C",npc,MechanicalActorKind.NPC,1,MechanicalStateMaterialization.FULL,emptyMap(),emptyList(),emptySet(),generationProvenanceUid="GEN")
    private var recipient=speaker.copy(actor=player,kind=MechanicalActorKind.ACTIVE_PLAYER)
    private var reachable=true
    private var modelCalls=0
    private var wire=""
    private var afterAnswer:()->Unit={}
    private val provider=DeterministicAiProvider(AiCapabilityContract("TEST","TEST","TEST",setOf(AiWorkload.NPC_DECISION,AiWorkload.NPC_DIALOGUE),maximumContextUnits=8192),
        intentFunction={error("unused")},proposalFunction={error("unused")},narrativeFunction={error("unused")},
        npcDecisionFunction={r->NpcDecisionProposal(r.requestUid,r.context.contextFingerprint,listOf(NpcDecisionCandidate(r.context.options.single().uid)))},
        npcDialogueFunction={r->modelCalls++;wire=NpcDialogueCodec.encode(r);afterAnswer();NpcDialogueCandidate(r.requestUid,r.fingerprint,"Chcesz pójść ze mną na spacer?",setOf("R"))})
    private val route=AiModelRoutePort{_,_,_->AiRouteResult.Selected(provider,true,"TEST")}
    private fun project(at:Long=0,changes:List<PlayerDomainChangePayload> = emptyList()):NpcContextResult.Ready {
        val state=applyNpcBrainOverlay(brain,scope,changes.filterIsInstance<NpcBrainChange>())
        val reads=object:NpcProjectionReadPort {
            override fun brain(a:AudienceContext,p:PurposeContext,actor:DomainRef,h:KnowledgeHolderRef)=ProtectedReadResult.Allow(state,DisclosureLevel.DISCLOSE_FULL,"SELF")
            override fun knowledge(a:AudienceContext,p:PurposeContext,h:KnowledgeHolderRef,order:Long,limit:Int)=ProtectedReadResult.Allow(listOf(record),DisclosureLevel.DISCLOSE_FULL,"ACQUIRED")
        }
        val trigger=NpcTrigger("T",NpcTriggerKind.KNOWLEDGE_CHANGED,WorldTimeTick(at),NpcCauseRef(NpcCauseKind.KNOWLEDGE_ACQUISITION,"A"))
        return NpcDecisionContextProjector(reads).project(NpcDecisionScope(scope,npc,state.revision,WorldTimeTick(at),0,"P"),trigger,state.knowledgeHolder,
            ContextRuntimeProfile("TEST",8192,64,64,512)){b,records->listOf(NpcSpeechMechanics.option(b,b.goals.single(),player,records.single(),records.single()))} as NpcContextResult.Ready
    }
    private fun choose(projected:NpcContextResult.Ready)=NpcDecisionEngine().select(projected.context,NpcDecisionProposal("D",projected.context.contextFingerprint,
        listOf(NpcDecisionCandidate(projected.context.options.single().uid))),projected.context.scope) as NpcDecisionResult.Selected
    private fun mechanics()=NpcMechanicalActionApplication(MechanicsRuleResolver{r,c->NpcSpeechMechanics.resolve(r,c,speaker,recipient,reachable)},{scope})
    private fun input(at:Long,changes:List<PlayerDomainChangePayload> = emptyList())=TemporalOwnerInput(scope,WorldTimeTick(0),WorldTimeTick(at),emptyList(),emptyList(),null,changes)
    private fun app()=NpcTimedActionApplication("CMD",NpcPhysicalContextPort{_,i,_->project(i.through.milliseconds,i.stagedChanges)},route,{scope},mechanics(),
        initiatedSpeech=NpcInitiatedSpeechApplication(route,{scope}))

    @Test fun preflightOnlyProvesTimeAndEffortWithoutSpeechOrMemory() {
        val p=project();val result=mechanics().resolve(p,choose(p)) as NpcMechanicalResult.Resolved
        assertEquals(30000L,result.timing.duration.milliseconds)
        assertEquals(NpcSpeechMechanics.TIMING_RULE,result.timing.ruleUid)
        assertEquals(npc,result.effects.single().target)
        assertEquals(listOf(MechanicalTrackChange(npc,"ACTION:SPEECH",ExactLongDelta.of(1))),result.changes)
        assertFalse(result.effects.single().canonicalPayload.containsKey("narrative_text"))
        assertTrue(NpcCommunicationMemory.materialize("C","CMD",2,result.effects).changes.isEmpty())
        assertEquals(0,modelCalls)
    }
    @Test fun initiativeFinishesAfterTimeAndOnlyExactParticipantsHearTheNpcNotAFabricatedPlayerReply() {
        val started=app().prepare(npc,input(0)){false} as NpcActionPreparation.Started
        assertEquals(0,modelCalls)
        assertEquals(30000L,started.pending.due.milliseconds)
        brain=applyNpcBrainOverlay(brain,scope,started.changes)
        scope=scope.copy(baseCommitOrder=2,authoritativeFingerprint="REOPENED")
        val finished=app().complete(started.pending,input(30000)){false} as NpcActionCompletion.Finished
        assertEquals(1,modelCalls);assertTrue(finished.playerDecisionRequired)
        assertEquals(2,finished.effects.size)
        assertTrue(wire.contains("\"mode\":\"INITIATE\""))
        assertTrue(wire.contains("\"received_message\":\"\""))
        assertTrue(wire.contains("Zaproponować wspólny spacer"))
        val speech=finished.effects.single{it.effectKindUid=="NARRATIVE_EVENT"}
        assertEquals(npc.uid,speech.canonicalPayload["source_actor_uid"])
        assertTrue(speech.proofUid.startsWith("P60:PROCESS:"))
        val material=MechanicalEffectMaterializer.materialize(speech) as MechanicalEffectMaterializationResult.Materialized
        assertEquals(TruthKind.NARRATIVE,(material.changes.single().payload as CampaignTruthChange).kind)
        val memory=NpcCommunicationMemory.materialize("C","CMD2",3,finished.effects)
        val rows=memory.changes.map{it.payload as KnowledgeAcquisitionChange}
        assertEquals(2,rows.size);assertTrue(rows.all{it.claim.subjectUid==npc.uid})
        assertEquals(setOf("P","N"),rows.map{it.acquisition.holder.holderUid}.toSet())
        assertEquals(listOf(NpcCommunicationMemory.HeardUtterance(npc,"Chcesz pójść ze mną na spacer?")),NpcCommunicationMemory.heardNpcUtterances("C","P",memory.changes))
        assertTrue(NpcCommunicationMemory.deliveredPlayerUtterances("C","P",memory.changes).isEmpty())
        assertTrue(NpcCommunicationMemory.heardNpcUtterances("C","OTHER",memory.changes).isEmpty())
        assertTrue(NpcCommunicationMemory.heardNpcUtterances("OTHER","P",memory.changes).isEmpty())
        assertEquals(setOf(npc),NpcCommunicationMemory.heardInterlocutors("C","P",memory.changes))
        assertTrue(runCatching{NpcCommunicationMemory.materialize("OTHER","CMD",3,finished.effects)}.isFailure)
        val complete=NpcBrainCodec.decode(finished.brainChange.stateCanonical)
        assertEquals(NpcPlanLifecycle.COMPLETED,complete.plans.single().lifecycle)
        assertEquals(NpcGoalLifecycle.ACTIVE,complete.goals.single().lifecycle)
    }
    @Test fun leavingOrLosingConsciousnessBeforeDeliveryInterruptsWithoutGeneratedSpeech() {
        val started=app().prepare(npc,input(0)){false} as NpcActionPreparation.Started
        brain=applyNpcBrainOverlay(brain,scope,started.changes)
        reachable=false
        val result=app().complete(started.pending,input(30000)){false} as NpcActionCompletion.Finished
        assertTrue(result.effects.isEmpty());assertEquals(0,modelCalls)
        assertEquals(NpcPlanLifecycle.INTERRUPTED,NpcBrainCodec.decode(result.brainChange.stateCanonical).plans.single().lifecycle)
    }
    @Test fun realDeliveryChecksCannotBeBypassedByAnOldSelectedOption() {
        val p=project();val chosen=choose(p)
        for(changed in listOf(speaker.copy(materialization=MechanicalStateMaterialization.PARTIAL),
            speaker.copy(kind=MechanicalActorKind.ACTIVE_PLAYER),speaker.copy(conditions=listOf(MechanicalCondition("SILENCED",1))),
            speaker.copy(resources=listOf(MechanicalResource("HEALTH",0,100))))) {
            speaker=changed;assertTrue(mechanics().resolve(p,chosen) is NpcMechanicalResult.Unavailable)
        }
        speaker=recipient.copy(actor=npc,kind=MechanicalActorKind.NPC)
        recipient=recipient.copy(conditions=listOf(MechanicalCondition("DEAF",1)))
        assertTrue(mechanics().resolve(p,chosen) is NpcMechanicalResult.Unavailable)
    }
    @Test fun cancellationStaleHistoryAndUnavailableHostCreateNoSpeech() {
        val p=project();val chosen=choose(p);val base=(mechanics().resolve(p,chosen) as NpcMechanicalResult.Resolved).effects.single()
        val app=NpcInitiatedSpeechApplication(route,{scope})
        assertEquals(NpcInitiatedSpeechResult.Unavailable("P62:CANCELLED"),app.deliver(p.context,chosen,base){true})
        assertEquals(0,modelCalls)
        val missing=NpcInitiatedSpeechApplication(AiModelRoutePort{_,_,_->AiRouteResult.Unavailable(listOf("HOST_GONE"))},{scope})
        assertTrue(missing.deliver(p.context,chosen,base){false} is NpcInitiatedSpeechResult.Unavailable)
        assertEquals(0,modelCalls)
        afterAnswer={scope=scope.copy(historyGenerationUid="UNDONE")}
        assertEquals(NpcInitiatedSpeechResult.Unavailable("P62:STALE_SCOPE"),app.deliver(p.context,chosen,base){false})
        assertEquals(1,modelCalls)
    }
    @Test fun reachIsBoundedSameLocationAndOverflowSafe() {
        assertTrue(NpcSpeechMechanics.inReach("L","L",0,0,3000,4000))
        assertFalse(NpcSpeechMechanics.inReach("L","L",0,0,3001,4000))
        assertFalse(NpcSpeechMechanics.inReach("L","OTHER",0,0,0,0))
        assertFalse(NpcSpeechMechanics.inReach("","",0,0,0,0))
        assertFalse(NpcSpeechMechanics.inReach("L","L",Long.MIN_VALUE,0,Long.MAX_VALUE,0))
        assertFalse(NpcSpeechMechanics.inReach("L","L",0,0,0,0,0,5001))
        assertFalse(NpcSpeechMechanics.inReach("L","L",0,0,0,0,Long.MIN_VALUE,Long.MAX_VALUE))
        assertTrue(NpcSpeechMechanics.inReach("L","L",0,0,0,0,0,5000))
    }
    @Test fun optionalHistoryCanShrinkButRecipientAndGoalEvidenceStayInMobilePrompt() {
        val original=project().context
        val context=NpcDecisionContextEnvelope(original.scope,original.trigger,original.brain,
            original.records+(0 until 30).map{NpcKnownRecord("MEM:$it",KnowledgeEpistemicState.OUTDATED,"Dawna notatka. ".repeat(30),"OLD:$it",1)},
            original.options,2048,original.projectionFingerprint,original.currentRoleUids)
        val selected=NpcDecisionEngine().select(context,NpcDecisionProposal("D",context.contextFingerprint,
            listOf(NpcDecisionCandidate(context.options.single().uid))),context.scope) as NpcDecisionResult.Selected
        val old=project();val effect=(mechanics().resolve(old,choose(old)) as NpcMechanicalResult.Resolved).effects.single()
            .copy(effectUid="P62:EFFECT:${selected.authorization.decisionUid}")
        val app=NpcInitiatedSpeechApplication(route,{scope})
        assertTrue(app.deliver(context,selected,effect){false} is NpcInitiatedSpeechResult.Delivered)
        assertTrue((wire.length+3)/4<=2048)
        assertTrue(wire.contains("\"uid\":\"R\""));assertTrue(wire.contains("Zaproponować wspólny spacer"))
        assertFalse(wire.contains("MEM:29"))
        assertEquals(31,context.records.size)
        assertEquals(NpcInitiatedSpeechResult.Unavailable("P62:SPEECH_AUTHORIZATION_MISMATCH"),
            app.deliver(context,selected,effect.copy(effectUid="OTHER_ACTION")){false})
    }
    @Test fun autonomousAffordanceNeedsKnownIdentityNotCoLocationAlone() {
        val port=NpcMechanicalAffordances(CombatAbilityContractPort.UNIVERSAL_FALLBACK)
        assertTrue(port.options(brain,listOf(record),speaker,player).any{it.mechanicsOwnerUid==NpcSpeechMechanics.OWNER})
        assertFalse(port.options(brain,listOf(record.copy(subjectRefs=emptySet())),speaker,player).any{it.mechanicsOwnerUid==NpcSpeechMechanics.OWNER})
        assertFalse(port.options(brain,listOf(record),speaker,DomainRef("PLAYER","UNKNOWN")).any{it.mechanicsOwnerUid==NpcSpeechMechanics.OWNER})
    }
}
