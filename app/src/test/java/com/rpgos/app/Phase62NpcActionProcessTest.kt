package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase62NpcActionProcessTest {
    private val actor=DomainRef("NPC","N1")
    private var scope=TemporalScope("C1","G1",7,"digest")
    private val record=NpcKnownRecord("R",KnowledgeEpistemicState.BELIEVED,"Chcę poprawić swoje umiejętności.","ACQ",1,setOf(actor),1)
    private var brain=NpcBrainOwner.initialize("C1",actor,"seed").let{base->base.copy(revision=2,
        goals=listOf(NpcGoal("GOAL",base.motivations.first().uid,"Ćwiczyć",NpcWeight(7000),NpcGoalLifecycle.ACTIVE,
            NpcCauseRef(NpcCauseKind.KNOWLEDGE_ACQUISITION,"ACQ"))))}
    private var providerCalls=0
    private var mechanicsCalls=0
    private var executable=true
    private var continuation:List<String> = emptyList()
    private var alternative:String?=null
    private var offerAlternative=false
    private var primaryExecutable=true
    private var maximumMechanicsCalls=Int.MAX_VALUE
    private var interruptPlayer=false
    private val provider=DeterministicAiProvider(AiCapabilityContract("TEST","TEST","TEST",setOf(AiWorkload.NPC_DECISION),maximumContextUnits=8192),
        intentFunction={error("unused")},proposalFunction={error("unused")},narrativeFunction={error("unused")},
        npcDecisionFunction={request->providerCalls++;NpcDecisionProposal(request.requestUid,request.context.contextFingerprint,
            listOf(NpcDecisionCandidate("OPTION",continuation,alternative)))})
    private val foreground=object:WorldProcessOwnerPort {
        override val ownerUid=PHASE60_FOREGROUND_OWNER
        override fun evaluate(input:TemporalOwnerInput)=TemporalOwnerResult.Evaluated(TemporalOwnerState(ownerUid,1,"READY"))
    }
    private fun context(actor:DomainRef,input:TemporalOwnerInput,pending:NpcPendingAction?):NpcContextResult {
        val staged=applyNpcBrainOverlay(brain,input.scope,input.stagedChanges.filterIsInstance<NpcBrainChange>())
        val reads=object:NpcProjectionReadPort {
            override fun brain(audience:AudienceContext,purpose:PurposeContext,actor:DomainRef,holder:KnowledgeHolderRef)=ProtectedReadResult.Allow(staged,DisclosureLevel.DISCLOSE_FULL,"SELF")
            override fun knowledge(audience:AudienceContext,purpose:PurposeContext,holder:KnowledgeHolderRef,order:Long,limit:Int)=ProtectedReadResult.Allow(listOf(record),DisclosureLevel.DISCLOSE_FULL,"ACQUIRED")
        }
        val trigger=NpcTrigger("TRIGGER",if(pending==null)NpcTriggerKind.KNOWLEDGE_CHANGED else NpcTriggerKind.PLAN_BOUNDARY,input.through,
            pending?.let{p->staged.plans.single{it.uid==p.planUid}.cause}?:NpcCauseRef(NpcCauseKind.KNOWLEDGE_ACQUISITION,"ACQ"))
        return NpcDecisionContextProjector(reads).project(NpcDecisionScope(scope,actor,staged.revision,input.through,0,"P1"),trigger,
            staged.knowledgeHolder,ContextRuntimeProfile("TEST",8192,64,64,512)) {_,_->listOf(
                NpcActionOption("OPTION","TRAIN",actor,AcceptedActionTiming(ActionDuration(1000),"CANDIDATE",1),"GOAL",emptyList(),setOf("R"),
                    mechanicsOwnerUid="CORE",mechanicalEffectKindUid="RESOURCE_DELTA",parameters=mapOf("resource_uid" to "ENERGY")))+
                if(offerAlternative)listOf(NpcActionOption("ALTERNATIVE","REST",actor,AcceptedActionTiming(ActionDuration(1000),"CANDIDATE",1),"GOAL",emptyList(),setOf("R"),
                    mechanicsOwnerUid="CORE",mechanicalEffectKindUid="RESOURCE_DELTA",parameters=mapOf("resource_uid" to "ENERGY","alternative" to "true"))) else emptyList() }
    }
    private fun extension(start:Long,command:String,participants:List<DomainRef> = listOf(actor))=NpcActionProcess(scope,WorldTimeTick(start),"P1",participants,
        NpcTimedActionApplication(command,NpcPhysicalContextPort(::context),AiModelRoutePort{_,_,_->AiRouteResult.Selected(provider,true,"TEST")},{scope},
            NpcMechanicalActionApplication(MechanicsRuleResolver{request,_ ->
                mechanicsCalls++
                if(!executable || mechanicsCalls>maximumMechanicsCalls || (!primaryExecutable && request.parameters["alternative"]!="true"))MechanicsEffectResolution.Rejected("NO_ENERGY") else MechanicsEffectResolution.Verified(VerifiedMechanicsEffect(
                    request.effectUid,request.nodeUid,"CORE","RESOURCE_DELTA",mapOf("resource_uid" to "ENERGY","magnitude" to "-2",
                        "p60_core_duration_ms" to "1200","p60_core_timing_rule" to Phase60CombatTime.RULE,"p60_core_effect_at_ms" to "1200"),"PROOF","INPUT","OUTPUT"))
            },{scope}),interruptsForeground={interruptPlayer})).extension()
    private fun run(extension:TemporalProcessExtension,start:Long,duration:Long,previous:TemporalOwnerState?=null,
                    deadlines:List<WorldProcessDeadline> = emptyList()):TemporalExecutionResult {
        val processor=Phase60TimeProcessor(listOf(foreground)+extension.owners.map{it.owner})
        val initial=processor.begin(scope,"CMD",WorldTimeTick(start),listOf(TimedActionNode("PLAYER_READ",PHASE60_FOREGROUND_OWNER,
            AcceptedActionTiming(ActionDuration(duration),"READ_RULE",1))),deadlines,listOfNotNull(previous))
        var result=processor.advance(initial,scope)
        val responses=linkedMapOf<String,TemporalOwnerResult.Evaluated>()
        while(result.reason==TemporalStopReason.OWNER_EVALUATION_REQUIRED || result.reason==TemporalStopReason.YIELDED) {
            assertTrue("bounded test execution",responses.size<10)
            result.pendingEvaluation?.let{request->
                val answer=extension.evaluation!!.evaluate(request){false}
                assertTrue(answer.toString(),answer is TemporalEvaluationResponse.Accepted)
                responses[request.fingerprint]=(answer as TemporalEvaluationResponse.Accepted).result
            }
            result=processor.advance(result.checkpoint,scope,evaluations=responses)
        }
        return result
    }
    @Test fun pendingActionSurvivesReopenAndOnlyFreshMechanicsCanCompleteIt() {
        val first=run(extension(0,"CMD1"),0,500)
        assertEquals(TemporalStopReason.COMPLETED,first.reason)
        assertEquals(1,providerCalls);assertEquals(1,mechanicsCalls)
        assertTrue(first.checkpoint.candidateEffects.isEmpty())
        assertTrue(first.checkpoint.candidateChanges.all{it is NpcBrainChange})
        val state=first.checkpoint.ownerStates.getValue(NpcActionProcess.OWNER)
        assertEquals(1200L,NpcActionProcess.decode(state).single().due.milliseconds) // Core timing wins.
        assertEquals(state,Phase60CheckpointCodec.decode(Phase60CheckpointCodec.encode(first.checkpoint)).ownerStates.getValue(NpcActionProcess.OWNER))
        brain=applyNpcBrainOverlay(brain,scope,first.checkpoint.candidateChanges.filterIsInstance<NpcBrainChange>())
        scope=scope.copy(baseCommitOrder=8,authoritativeFingerprint="next")
        val second=run(extension(500,"CMD2",emptyList()),500,2000,state,first.checkpoint.deadlines)
        assertEquals(TemporalStopReason.COMPLETED,second.reason)
        assertEquals(1,providerCalls) // Resume is not another AI choice.
        assertEquals(2,mechanicsCalls) // Future result was recomputed, not loaded from a cache.
        assertTrue(second.checkpoint.deadlines.isEmpty())
        assertTrue(NpcActionProcess.decode(second.checkpoint.ownerStates.getValue(NpcActionProcess.OWNER)).isEmpty())
        val finished=applyNpcBrainOverlay(brain,scope,second.checkpoint.candidateChanges.filterIsInstance<NpcBrainChange>())
        assertEquals(NpcPlanLifecycle.COMPLETED,finished.plans.single().lifecycle)
        assertEquals(NpcGoalLifecycle.ACTIVE,finished.goals.single().lifecycle)
        val effect=second.checkpoint.candidateEffects.single()
        assertEquals(actor.uid,effect.canonicalPayload["source_actor_uid"])
        val material=MechanicalEffectMaterializer.materialize(effect) as MechanicalEffectMaterializationResult.Materialized
        assertEquals(actor,material.eventIntents.single().actorRef)
        assertEquals(Phase60CheckpointCodec.encode(second.checkpoint),Phase60CheckpointCodec.encode(Phase60CheckpointCodec.decode(Phase60CheckpointCodec.encode(second.checkpoint))))
        assertEquals("P60:INTERVAL_EFFECT_DROPPED",Phase60EffectSettlement.validate(second,second.checkpoint.candidateChanges))
        assertNull(Phase60EffectSettlement.validate(second,second.checkpoint.candidateChanges+material.changes.map{it.payload}))
    }
    @Test fun lossOfCapabilityAfterStartInterruptsPlanWithoutSpendingOrInventingSuccess() {
        val first=run(extension(0,"CMD1"),0,500)
        brain=applyNpcBrainOverlay(brain,scope,first.checkpoint.candidateChanges.filterIsInstance<NpcBrainChange>())
        scope=scope.copy(baseCommitOrder=8,authoritativeFingerprint="next");executable=false
        val result=run(extension(500,"CMD2",emptyList()),500,2000,first.checkpoint.ownerStates.getValue(NpcActionProcess.OWNER),first.checkpoint.deadlines)
        assertEquals(TemporalStopReason.COMPLETED,result.reason)
        assertTrue(result.checkpoint.candidateEffects.isEmpty())
        assertEquals(NpcPlanLifecycle.INTERRUPTED,applyNpcBrainOverlay(brain,scope,result.checkpoint.candidateChanges.filterIsInstance<NpcBrainChange>()).plans.single().lifecycle)
        assertEquals(1,providerCalls)
    }
    @Test fun sameTurnStartAndCompletionUseConsecutiveBrainRevisions() {
        val result=run(extension(0,"CMD1"),0,5000)
        assertEquals(TemporalStopReason.COMPLETED,result.reason)
        val changes=result.checkpoint.candidateChanges.filterIsInstance<NpcBrainChange>()
        assertEquals(listOf(2L,3L),changes.map{it.expectedVersion})
        assertTrue(validNpcBrainChains(changes));assertEquals(1,result.checkpoint.candidateEffects.size)
        assertEquals(NpcPlanLifecycle.COMPLETED,applyNpcBrainOverlay(brain,scope,changes).plans.single().lifecycle)
    }
    @Test fun evaluationFingerprintBindsTheActualSpeculativePrefix() {
        val input=TemporalOwnerInput(scope,WorldTimeTick(0),WorldTimeTick(100),emptyList(),emptyList(),null)
        val original=TemporalEvaluationRequest("OWNER",input,"REASON").fingerprint
        val altered=TemporalEvaluationRequest("OWNER",input.copy(stagedChanges=listOf(ResourceChange(actor,"ENERGY",ExactLongDelta.of(-1)))),"REASON").fingerprint
        assertNotEquals(original,altered)
        assertTrue(runCatching{extension(0,"CMD1",listOf(DomainRef("NPC","P1")))}.isFailure)
    }
    @Test fun boundedSequenceSurvivesReopenAndRechecksEachStepWithoutAnotherModelCall() {
        continuation=listOf("OPTION","OPTION")
        val first=run(extension(0,"CMD1"),0,500)
        brain=applyNpcBrainOverlay(brain,scope,first.checkpoint.candidateChanges.filterIsInstance<NpcBrainChange>())
        assertEquals(2,brain.plans.single().nextActionUids.size)
        assertEquals(brain,NpcBrainCodec.decode(NpcBrainCodec.encode(brain)))
        scope=scope.copy(baseCommitOrder=8,authoritativeFingerprint="next")
        val second=run(extension(500,"CMD2",emptyList()),500,5000,
            first.checkpoint.ownerStates.getValue(NpcActionProcess.OWNER),first.checkpoint.deadlines)
        assertEquals(TemporalStopReason.COMPLETED,second.reason)
        assertEquals(1,providerCalls);assertEquals(6,mechanicsCalls)
        assertEquals(3,second.checkpoint.candidateEffects.size)
        val changes=second.checkpoint.candidateChanges.filterIsInstance<NpcBrainChange>()
        assertTrue(validNpcBrainChains(changes))
        val final=applyNpcBrainOverlay(brain,scope,changes)
        assertEquals(3,final.plans.size);assertTrue(final.plans.all{it.lifecycle==NpcPlanLifecycle.COMPLETED})
        assertEquals(setOf(0,1,2),final.plans.map{it.nextActionUids.size}.toSet())
        assertEquals(2,final.plans.count{it.previousPlanUid!=null})
        assertEquals(NpcGoalLifecycle.ACTIVE,final.goals.single().lifecycle)
        assertEquals(3,NpcActionMemory.materialize("C1","CMD2",9,second.checkpoint.candidateEffects,changes).changes.size)
    }
    @Test fun failedContinuationKeepsOnlyTheActuallyCompletedStepAndDoesNotReroute() {
        continuation=listOf("OPTION","OPTION");maximumMechanicsCalls=2
        val result=run(extension(0,"CMD1"),0,5000)
        assertEquals(TemporalStopReason.COMPLETED,result.reason)
        assertEquals(1,result.checkpoint.candidateEffects.size);assertEquals(1,providerCalls)
        assertEquals(3,mechanicsCalls) // first preflight, first delivery, rejected second preflight
        assertTrue(NpcActionProcess.decode(result.checkpoint.ownerStates.getValue(NpcActionProcess.OWNER)).isEmpty())
        val final=applyNpcBrainOverlay(brain,scope,result.checkpoint.candidateChanges.filterIsInstance<NpcBrainChange>())
        assertEquals(1,final.plans.size);assertEquals(NpcPlanLifecycle.COMPLETED,final.plans.single().lifecycle)
        assertEquals(NpcGoalLifecycle.ACTIVE,final.goals.single().lifecycle)
    }
    @Test fun playerDecisionBoundaryPreventsAutomaticContinuation() {
        continuation=listOf("OPTION","OPTION");interruptPlayer=true
        val result=run(extension(0,"CMD1"),0,5000)
        assertEquals(TemporalStopReason.PLAYER_DECISION,result.reason)
        assertEquals(1,result.checkpoint.candidateEffects.size);assertEquals(2,mechanicsCalls)
        assertTrue(NpcActionProcess.decode(result.checkpoint.ownerStates.getValue(NpcActionProcess.OWNER)).isEmpty())
    }
    @Test fun explicitAlternativeSurvivesReopenAndHasItsOwnTimeAfterFailedPrimary() {
        offerAlternative=true;alternative="ALTERNATIVE"
        val first=run(extension(0,"CMD1"),0,500)
        brain=applyNpcBrainOverlay(brain,scope,first.checkpoint.candidateChanges.filterIsInstance<NpcBrainChange>())
        assertEquals("ALTERNATIVE",brain.plans.single().onUnavailableOptionUid)
        assertEquals(brain,NpcBrainCodec.decode(NpcBrainCodec.encode(brain)))
        scope=scope.copy(baseCommitOrder=8,authoritativeFingerprint="reopened");primaryExecutable=false
        val second=run(extension(500,"CMD2",emptyList()),500,5000,first.checkpoint.ownerStates.getValue(NpcActionProcess.OWNER),first.checkpoint.deadlines)
        assertEquals(TemporalStopReason.COMPLETED,second.reason)
        assertEquals(1,providerCalls);assertEquals(4,mechanicsCalls)
        assertEquals(1,second.checkpoint.candidateEffects.size)
        val final=applyNpcBrainOverlay(brain,scope,second.checkpoint.candidateChanges.filterIsInstance<NpcBrainChange>())
        val primary=final.plans.single{it.actionUid=="OPTION"};val other=final.plans.single{it.actionUid=="ALTERNATIVE"}
        assertEquals(NpcPlanLifecycle.INTERRUPTED,primary.lifecycle)
        assertEquals(NpcPlanLifecycle.COMPLETED,other.lifecycle)
        assertEquals(primary.uid,other.previousPlanUid);assertEquals(WorldTimeTick(1200),other.startedAt)
        assertNull(other.onUnavailableOptionUid);assertTrue(other.nextActionUids.isEmpty())
        assertEquals(NpcGoalLifecycle.ACTIVE,final.goals.single().lifecycle)
        assertEquals(1,NpcActionMemory.materialize("C1","CMD2",9,second.checkpoint.candidateEffects,second.checkpoint.candidateChanges.filterIsInstance<NpcBrainChange>()).changes.size)
    }
    @Test fun unavailableAlternativeDoesNotRerouteOrLoop() {
        offerAlternative=true;alternative="ALTERNATIVE"
        val first=run(extension(0,"CMD1"),0,500)
        brain=applyNpcBrainOverlay(brain,scope,first.checkpoint.candidateChanges.filterIsInstance<NpcBrainChange>())
        primaryExecutable=false;executable=false
        val second=run(extension(500,"CMD2",emptyList()),500,5000,first.checkpoint.ownerStates.getValue(NpcActionProcess.OWNER),first.checkpoint.deadlines)
        assertEquals(1,providerCalls);assertEquals(3,mechanicsCalls)
        assertTrue(second.checkpoint.candidateEffects.isEmpty());assertTrue(second.checkpoint.deadlines.isEmpty())
        val final=applyNpcBrainOverlay(brain,scope,second.checkpoint.candidateChanges.filterIsInstance<NpcBrainChange>())
        assertEquals(1,final.plans.size);assertEquals(NpcPlanLifecycle.INTERRUPTED,final.plans.single().lifecycle)
    }
    @Test fun disappearingAlternativeIsNotRestoredFromSavedIntent() {
        offerAlternative=true;alternative="ALTERNATIVE"
        val first=run(extension(0,"CMD1"),0,500)
        brain=applyNpcBrainOverlay(brain,scope,first.checkpoint.candidateChanges.filterIsInstance<NpcBrainChange>())
        primaryExecutable=false;offerAlternative=false
        val second=run(extension(500,"CMD2",emptyList()),500,5000,first.checkpoint.ownerStates.getValue(NpcActionProcess.OWNER),first.checkpoint.deadlines)
        assertEquals(1,providerCalls);assertEquals(2,mechanicsCalls)
        assertTrue(second.checkpoint.candidateEffects.isEmpty());assertTrue(second.checkpoint.deadlines.isEmpty())
    }
    private fun finiteGoal() {
        val projected=context(actor,TemporalOwnerInput(scope,WorldTimeTick(0),WorldTimeTick(0),emptyList(),emptyList(),null),null) as NpcContextResult.Ready
        val objective=NpcExecutionGoals.fromOption(projected.context,"OPTION")
        brain=brain.copy(goals=listOf(brain.goals.single().copy(objective=NpcExecutionGoals.description(objective),executionObjective=objective)))
    }
    @Test fun onlyVerifiedExactExecutionCompletesTheFinitePersonalGoal() {
        finiteGoal();continuation=listOf("OPTION")
        val result=run(extension(0,"CMD"),0,5000)
        assertEquals(TemporalStopReason.COMPLETED,result.reason)
        assertEquals(1,result.checkpoint.candidateEffects.size);assertEquals(2,mechanicsCalls)
        val changes=result.checkpoint.candidateChanges.filterIsInstance<NpcBrainChange>()
        val after=applyNpcBrainOverlay(brain,scope,changes)
        assertEquals(NpcGoalLifecycle.ACHIEVED,after.goals.single().lifecycle)
        assertEquals(1,after.plans.size) // No unnecessary repeat after the finite goal is fulfilled.
        assertEquals(NpcBrainRules.EXECUTION_COMPLETION.uid,changes.last().ruleUid)
        assertEquals(after,NpcBrainCodec.decode(NpcBrainCodec.encode(after)))
        assertTrue(runCatching{NpcBrainOwner.validateTransition(brain,brain.copy(revision=brain.revision+1,
            goals=listOf(brain.goals.single().copy(lifecycle=NpcGoalLifecycle.ACHIEVED))),NpcBrainRules.PLANNING,listOf(brain.goals.single().cause))}.isFailure)
    }
    @Test fun failedPrimaryAndSuccessfulDifferentAlternativeDoNotCompleteTheOriginalCriterion() {
        offerAlternative=true;alternative="ALTERNATIVE";finiteGoal()
        val first=run(extension(0,"CMD1"),0,500)
        brain=applyNpcBrainOverlay(brain,scope,first.checkpoint.candidateChanges.filterIsInstance<NpcBrainChange>())
        primaryExecutable=false
        val second=run(extension(500,"CMD2",emptyList()),500,5000,first.checkpoint.ownerStates.getValue(NpcActionProcess.OWNER),first.checkpoint.deadlines)
        val after=applyNpcBrainOverlay(brain,scope,second.checkpoint.candidateChanges.filterIsInstance<NpcBrainChange>())
        assertEquals(1,second.checkpoint.candidateEffects.size)
        assertEquals(NpcGoalLifecycle.ACTIVE,after.goals.single().lifecycle)
    }
}
