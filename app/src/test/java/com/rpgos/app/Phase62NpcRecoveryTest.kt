package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase62NpcRecoveryTest {
    companion object {
        // Persistence regressions reuse an actually resolved mixed-effect result, not a hand
        // written +resource payload. Scope labels keep separate test transactions distinct.
        internal fun resolvedDefaultRecoveryEffects(label:String)=Phase62NpcRecoveryTest().let { fixture ->
            fixture.scope=fixture.scope.copy(authoritativeFingerprint=phase60Hash(label))
            fixture.resolve().effects
        }
    }
    private val npc=DomainRef("NPC","N")
    private var scope=TemporalScope("C1","H",1,"STATE")
    private var rule=NpcActivityContractPort.STANDARD.contract("C1","REST")!!
    private var body=MechanicalActorView("C1",npc,MechanicalActorKind.NPC,1,MechanicalStateMaterialization.FULL,
        emptyMap(),listOf(MechanicalResource("STAMINA",98,100),MechanicalResource("HEALTH",80,100)),emptySet(),generationProvenanceUid="GEN")
    private var brain=NpcBrainOwner.initialize("C1",npc,"seed").let{it.copy(goals=listOf(NpcGoal("G",it.motivations.first().uid,
        "Odzyskać siły",NpcWeight(5000),NpcGoalLifecycle.ACTIVE,NpcCauseRef(NpcCauseKind.KNOWLEDGE_ACQUISITION,"A"))))}
    private val record=NpcKnownRecord("R",KnowledgeEpistemicState.KNOWN,"Potrzebuję odpoczynku.","A",1,setOf(npc))
    private var modelCalls=0
    private val provider=DeterministicAiProvider(AiCapabilityContract("TEST","TEST","TEST",setOf(AiWorkload.NPC_DECISION),maximumContextUnits=8192),
        intentFunction={error("unused")},proposalFunction={error("unused")},narrativeFunction={error("unused")},
        npcDecisionFunction={r->modelCalls++;NpcDecisionProposal(r.requestUid,r.context.contextFingerprint,listOf(NpcDecisionCandidate(r.context.options.single().uid)))})
    private val mechanics=NpcMechanicalActionApplication(MechanicsRuleResolver{r,c->
        NpcActivityMechanics.resolve(r,c,StagedMechanicalProjection.actor(body,c.stagedEffects),rule)
    },{scope})
    private fun input(at:Long,changes:List<PlayerDomainChangePayload> = emptyList())=
        TemporalOwnerInput(scope,WorldTimeTick(0),WorldTimeTick(at),emptyList(),emptyList(),null,changes)
    private fun project(input:TemporalOwnerInput=input(0),pending:NpcPendingAction?=null):NpcContextResult.Ready {
        val state=applyNpcBrainOverlay(brain,scope,input.stagedChanges.filterIsInstance<NpcBrainChange>())
        val reads=object:NpcProjectionReadPort {
            override fun brain(a:AudienceContext,p:PurposeContext,actor:DomainRef,h:KnowledgeHolderRef)=ProtectedReadResult.Allow(state,DisclosureLevel.DISCLOSE_FULL,"SELF")
            override fun knowledge(a:AudienceContext,p:PurposeContext,h:KnowledgeHolderRef,order:Long,limit:Int)=ProtectedReadResult.Allow(listOf(record),DisclosureLevel.DISCLOSE_FULL,"ACQUIRED")
        }
        val cause=pending?.let{p->state.plans.single{it.uid==p.planUid}.cause}?:NpcCauseRef(NpcCauseKind.KNOWLEDGE_ACQUISITION,"A")
        val trigger=NpcTrigger("T",if(pending==null)NpcTriggerKind.KNOWLEDGE_CHANGED else NpcTriggerKind.PLAN_BOUNDARY,input.through,cause)
        return NpcDecisionContextProjector(reads).project(NpcDecisionScope(scope,npc,state.revision,input.through,0,"P"),trigger,state.knowledgeHolder,
            ContextRuntimeProfile("TEST",8192,64,64,512)){b,_->
            if(NpcActivityMechanics.available(body,rule))listOf(NpcActivityMechanics.option(b,b.goals.single(),record,rule)) else emptyList()
        } as NpcContextResult.Ready
    }
    private fun choose(p:NpcContextResult.Ready)=NpcDecisionEngine().select(p.context,NpcDecisionProposal("D",p.context.contextFingerprint,
        listOf(NpcDecisionCandidate(p.context.options.single().uid))),p.context.scope) as NpcDecisionResult.Selected
    private fun resolve():NpcMechanicalResult.Resolved { val p=project();return mechanics.resolve(p,choose(p)) as NpcMechanicalResult.Resolved }
    private fun app()=NpcTimedActionApplication("CMD",NpcPhysicalContextPort{_,i,p->project(i,p)},
        AiModelRoutePort{_,_,_->AiRouteResult.Selected(provider,true,"TEST")},{scope},mechanics)
    private fun begin()=app().prepare(npc,input(0)){false} as NpcActionPreparation.Started
    private fun gains(result:NpcMechanicalResult.Resolved)=result.changes.filterIsInstance<ResourceChange>()

    @Test fun standardRestIsOneBoundedStaminaGainAndEffortWithExactCoreTime() {
        val result=resolve()
        assertEquals(360000L,result.timing.duration.milliseconds)
        assertEquals(2,result.timing.ruleVersion)
        assertEquals(listOf(ResourceChange(npc,"STAMINA",ExactLongDelta.of(1))),gains(result))
        assertEquals(listOf(MechanicalTrackChange(npc,"ACTION:REST",ExactLongDelta.of(1))),result.changes.filterIsInstance<MechanicalTrackChange>())
        assertEquals(2,result.effects.size)
        assertTrue(result.effects.all{Phase60DomainTiming.effectOffset(it,result.timing.duration)==360000L})
        assertEquals(98L,body.resources.first().current) // mechanics is still a pure candidate
        assertFalse(result.changes.any{it is WoundChange || it is KnowledgeAcquisitionChange || it is ConditionChange})
    }

    @Test fun fullResourceOnlyRecordsEffortAndMissingResourceCannotBeCreated() {
        body=body.copy(resources=listOf(MechanicalResource("STAMINA",100,100)))
        assertTrue(gains(resolve()).isEmpty())
        body=body.copy(resources=emptyList())
        assertFalse(NpcActivityMechanics.available(body,rule))
        assertTrue(project().context.options.isEmpty())
    }

    @Test fun healingNeedsAnExplicitRegisteredAndMaterializedCapabilityAndNeverRemovesWounds() {
        rule=NpcActivityContract("SELF_REGENERATION","TEST:REGENERATION",1,ActionDuration(60000),"ACTION:SELF_REGENERATION",
            resourceRecovery=NpcActivityResourceRecovery("HEALTH",50))
        assertFalse(NpcActivityMechanics.available(body,rule))
        body=body.copy(executableAbilityUids=setOf("SELF_REGENERATION"),conditions=listOf(MechanicalCondition("WOUND",4)))
        val result=resolve()
        assertEquals(listOf(ResourceChange(npc,"HEALTH",ExactLongDelta.of(20))),gains(result))
        assertFalse(result.changes.any{it is WoundChange || it is ConditionChange})
        assertThrows(IllegalArgumentException::class.java){rule.copy(eligibility=NpcActivityEligibility.CONSCIOUS_SELF)}
        assertNull(NpcActivityContractPort.STANDARD.contract("C1","SELF_REGENERATION"))
    }

    @Test fun preflightAndPrematureCompletionDoNotRestoreResources() {
        val started=begin()
        assertEquals(360000L,started.pending.due.milliseconds)
        assertEquals(98L,body.resources.first().current)
        assertTrue(started.changes.isNotEmpty())
        val early=app().complete(started.pending,input(359999,started.changes)){false}
        assertEquals("P62:ACTION_NOT_DUE",(early as NpcActionCompletion.Unavailable).reasonUid)
        assertEquals(1,modelCalls)
    }

    @Test fun reopenedPlanRecomputesGainAtCompletionWithoutAnotherAiCallOrClaimingGoalSuccess() {
        val started=begin()
        brain=NpcBrainCodec.decode(NpcBrainCodec.encode(applyNpcBrainOverlay(brain,scope,started.changes)))
        val pending=NpcActionProcess.decode(TemporalOwnerState(NpcActionProcess.OWNER,1,NpcActionProcess.encode(listOf(started.pending)))).single()
        body=body.copy(stateVersion=2,resources=listOf(MechanicalResource("STAMINA",100,100),MechanicalResource("HEALTH",80,100)))
        val done=app().complete(pending,input(360000)){false} as NpcActionCompletion.Finished
        assertFalse(done.interrupted)
        assertTrue(done.effects.none{it.effectKindUid=="RESOURCE_DELTA"}) // preflight's old +1 is discarded
        assertEquals(NpcGoalLifecycle.ACTIVE,NpcBrainCodec.decode(done.brainChange.stateCanonical).goals.single().lifecycle)
        assertEquals(NpcPlanLifecycle.COMPLETED,NpcBrainCodec.decode(done.brainChange.stateCanonical).plans.single().lifecycle)
        assertEquals(1,modelCalls)
    }

    @Test fun cancellationOrIncapacitationCannotPayOutAPendingRest() {
        val started=begin()
        val atDue=input(360000,started.changes)
        assertEquals("P62:CANCELLED",(app().complete(started.pending,atDue){true} as NpcActionCompletion.Unavailable).reasonUid)
        body=body.copy(conditions=listOf(MechanicalCondition("UNCONSCIOUS",1)))
        val done=app().complete(started.pending,atDue){false} as NpcActionCompletion.Finished
        assertTrue(done.interrupted);assertTrue(done.effects.isEmpty())
        assertEquals(1,modelCalls)
    }

    @Test fun ruleRevisionChangeInvalidatesOldPendingRestRatherThanApplyingNewRewards() {
        val started=begin()
        rule=rule.copy(resourceRecovery=NpcActivityResourceRecovery("STAMINA",10))
        val done=app().complete(started.pending,input(360000,started.changes)){false} as NpcActionCompletion.Finished
        assertTrue(done.interrupted);assertTrue(done.effects.isEmpty())
        assertEquals(1,modelCalls)
    }

    @Test fun recoveryMagnitudeResourceAndDurationAreBoundToAuthorization() {
        val p=project();val selected=choose(p);val original=rule
        for(changed in listOf(original.copy(resourceRecovery=NpcActivityResourceRecovery("STAMINA",10)),
            original.copy(duration=ActionDuration(1)),original.copy(resourceRecovery=NpcActivityResourceRecovery("ENERGY",1)))) {
            rule=changed
            assertTrue(mechanics.resolve(p,selected) is NpcMechanicalResult.Unavailable)
        }
        rule=original
        body=body.copy(campaignUid="FOREIGN")
        assertTrue(mechanics.resolve(p,selected) is NpcMechanicalResult.Unavailable)
    }

    @Test fun zeroHealthOrAnActivePlayerCannotUseNpcRecovery() {
        assertFalse(NpcActivityMechanics.available(body.copy(kind=MechanicalActorKind.ACTIVE_PLAYER),rule))
        assertFalse(NpcActivityMechanics.available(body.copy(resources=listOf(MechanicalResource("STAMINA",10,100),MechanicalResource("HEALTH",0,100))),rule))
        assertFalse(NpcActivityMechanics.available(body.copy(materialization=MechanicalStateMaterialization.PARTIAL),rule))
        assertThrows(IllegalArgumentException::class.java){NpcActivityResourceRecovery("STAMINA",0)}
    }
}
