package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase60ProductionExecutionTest {
    private val actor=CommandActorRef("PLAYER","P1")
    private val subject=DomainRef("NPC","N1")
    private val scope=TemporalScope("C1","G1",0,"HASH")
    private val request=ChatTurnRequest("R","C1","T","CMD","TX",actor,"ćwiczę","pl",
        VisibilityAudienceFactory.player("C1"),PurposeContext("C1",VisibilityPurposeKinds.GAMEPLAY_NARRATION),1)
    private class Cache:TemporalCheckpointPort {
        var value:TemporalExecutionCheckpoint?=null
        override fun load(campaignUid:String,commandUid:String)=value
        override fun save(checkpoint:TemporalExecutionCheckpoint){value=checkpoint}
        override fun remove(campaignUid:String,commandUid:String){value=null}
    }
    private fun plan() : CanonicalTurnPlan {
        val node=IntentNode("A",IntentForm.DIRECT_ACTION,SemanticAction(rawPhrase="ćwiczę",semanticFamilyUid="TRAIN",
            attributes=mapOf("time_scope" to "WORLD","time_min_ms" to "1000","time_max_ms" to "1000")))
        val intent=IntentDocument(campaignUid="C1",actor=actor,rawInput="ćwiczę",meaningState=MeaningState.UNDERSTOOD,nodes=listOf(node),
            provenance=IntentInterpretationProvenance(IntentInterpretationSource.PLAYER_CLARIFICATION,"TEST","1","HASH"))
        return CanonicalTurnPlan(planUid="PLAN",campaignUid="C1",intent=intent,audience=request.audience,purpose=request.purpose,steps=listOf(
            CanonicalTurnPlanStep("STEP","A","CAP",CapabilityMatchState.EXACT,emptyList(),emptyList(),
                CapabilityExecutionKind.MECHANICS_PROPOSAL,CapabilitySideEffectClass.PROPOSED_WORLD_EFFECT,"CORE")))
    }
    private fun effect(uid:String="E",kind:String="TRAINING",units:Long=10)=VerifiedMechanicsCommandEffect(uid,"A","CORE",kind,subject,units,
        if(kind=="TRAINING")mapOf("track_uid" to "TRAINING:GENERAL") else mapOf("resource_uid" to "ENERGY"),"PROOF:$uid","INPUT","OUTPUT")
    private fun alarm()=RegisteredTemporalOwner("1",object:WorldProcessOwnerPort {
        override val ownerUid="ALARM"
        override fun evaluate(input:TemporalOwnerInput)=TemporalOwnerResult.Evaluated(TemporalOwnerState(ownerUid,1,"ready"),
            playerDecisionRequired=input.deadlines.isNotEmpty())
    })
    private fun snapshot(deadline:Boolean=true)=TemporalReadSnapshot(scope,CanonicalTemporalState(0,WorldTimeTick(0),emptyList(),
        if(deadline)listOf(WorldProcessDeadline("D","ALARM",WorldTimeTick(200))) else emptyList()))
    private fun execute(cache:Cache=Cache(),snapshot:TemporalReadSnapshot=snapshot(),effects:List<VerifiedMechanicsCommandEffect> = listOf(effect()),
                        policy:TemporalEffectPolicyPort=TemporalEffectPolicyPort.COMPLETION_ONLY,
                        owners:List<RegisteredTemporalOwner> = listOf(alarm()),cancelled:()->Boolean={false}):ProductionTemporalExecutionResult {
        val timing=Phase60ProductionTime.prepare(request,plan(),snapshot,processExecutionAvailable=true) as ProductionTimeResult.Ready
        return Phase60ProductionExecution(cache,{scope},owners,policy).execute(request,timing,snapshot,effects,cancelled)
    }
    @Test fun interruptionUsesElapsedProgressAndCostButNotCompletionReward() {
        val result=execute(effects=listOf(effect(),effect("C","RESOURCE_DELTA",-5),effect("REWARD",units=100)),
            policy=TemporalEffectPolicyPort { if(it.effectUid=="REWARD")TemporalAccrualPolicy.AT_COMPLETION else TemporalAccrualPolicy.PROPORTIONAL }) as ProductionTemporalExecutionResult.Completed
        assertEquals(WorldTimeTick(200),result.change.proposedTime)
        assertEquals("PLAYER_DECISION",result.change.stopReason)
        assertEquals(listOf(2L,-1L),result.effects.map{it.magnitude})
        assertTrue(Phase60ProcessStateCodec.decode(result.change.processStatesCanonical).none{it.ownerUid==PHASE60_FOREGROUND_OWNER})
        assertTrue(Phase60DeadlineCodec.decode(result.change.deadlinesCanonical).isEmpty())
        val report=Phase60ExecutionReport.decode(result.change.actionExecutionsCanonical,result.change.expectedTime,result.change.proposedTime).single()
        assertEquals(TemporalActionCompletion.INTERRUPTED,report.completion)
        assertEquals(200L,report.elapsed.milliseconds)
        assertEquals(result.change,phase60TimeChangeCodec().decode(phase60TimeChangeCodec().encode(result.change)))
        assertTrue(phase60PlayerExecutionSummary(result.change).contains("przerwana"))
        assertTrue(runCatching{result.change.copy(actionExecutionsCanonical=result.change.actionExecutionsCanonical.replace("INTERRUPTED","COMPLETED"))}.isFailure)
    }
    @Test fun defaultDiscreteRewardRequiresFinishingAndFullActionStillWorks() {
        assertTrue((execute() as ProductionTemporalExecutionResult.Completed).effects.isEmpty())
        val full=execute(snapshot=snapshot(false),owners=emptyList()) as ProductionTemporalExecutionResult.Completed
        assertEquals(listOf(effect()),full.effects)
        assertEquals("COMPLETED",full.change.stopReason)
        assertEquals(WorldTimeTick(1000),full.change.proposedTime)
    }
    @Test fun combatTimingUsesPhaseDurationsNotAbsoluteCommitOrderAndPreservesImpact() {
        fun schedule(base:Long)=CombatActionSchedule("ATTACK",listOf(
            CombatPhaseWindow(CombatActionPhase.DECLARE,base,base),
            CombatPhaseWindow(CombatActionPhase.EXECUTE,base,base+1),
            CombatPhaseWindow(CombatActionPhase.IMPACT,base+1,base+1),
            CombatPhaseWindow(CombatActionPhase.RECOVERY,base+1,base+3)))
        val metadata=Phase60CombatTime.metadata(schedule(100))
        assertEquals(metadata,Phase60CombatTime.metadata(schedule(90000)))
        val hit=effect("HIT","RESOURCE_DELTA",-10).copy(canonicalPayload=mapOf("resource_uid" to "ENERGY")+metadata)
        val read=snapshot(false)
        val timing=Phase60ProductionTime.prepare(request,plan(),read,authoritative=Phase60DomainTiming.accepted(listOf(hit))) as ProductionTimeResult.Ready
        assertEquals(WorldTimeTick(3000),timing.schedule.single().end)
        val work=Phase60TimeProcessor(emptyList()).begin(scope,"CMD",WorldTimeTick(0),timing.schedule.map{it.action})
        val policies=mapOf(hit.effectUid to TemporalAccrualPolicy.AT_COMPLETION)
        assertTrue(Phase60SegmentEffects.select(listOf(hit),work.copy(reached=WorldTimeTick(999)),policies).isEmpty())
        assertEquals(listOf(hit),Phase60SegmentEffects.select(listOf(hit),work.copy(reached=WorldTimeTick(1500)),policies))
    }
    @Test fun restartRecomputesCacheInsteadOfAdmittingForgedCachedEffects() {
        val cache=Cache()
        val first=execute(cache=cache)
        cache.value=cache.value!!.copy(candidateChanges=listOf(ResourceChange(subject,"ENERGY",ExactLongDelta.of(999))))
        assertEquals(first,execute(cache=cache))
        assertTrue(cache.value!!.candidateChanges.isEmpty())
    }
    @Test fun changedRulesCancellationAndMissingOwnerNeverProduceCommittableTime() {
        val cache=Cache();execute(cache=cache)
        assertEquals(ProductionTemporalExecutionResult.Rejected("P60:CHECKPOINT_SPECIFICATION_CHANGED"),execute(cache=cache,effects=listOf(effect(units=20))))
        assertNull(cache.value)
        assertEquals(ProductionTemporalExecutionResult.Rejected("P60:CANCELLED"),execute(cache=cache,cancelled={true}))
        assertTrue(execute(owners=emptyList()) is ProductionTemporalExecutionResult.Rejected)
    }
    @Test fun scalarSettlementCoalescesSameRowWithoutLosingSignedDeltaOrPrivacyMarker() {
        val foreground=effect("F","RESOURCE_DELTA",-5)
        val background=effect("B","RESOURCE_DELTA",2).copy(proofUid="P60:PROCESS:B")
        val result=phase60CoalesceEffects(listOf(foreground,background)).single()
        assertEquals(-3L,result.magnitude)
        assertTrue(result.proofUid.startsWith("P60:PROCESS:"))
        assertEquals(phase60CoalesceChanges(listOf(foreground,background).flatMap{
            (MechanicalEffectMaterializer.materialize(it) as MechanicalEffectMaterializationResult.Materialized).changes.map{c->c.payload}}),
            (MechanicalEffectMaterializer.materialize(result) as MechanicalEffectMaterializationResult.Materialized).changes.map{it.payload})
    }
    @Test fun conditionExpiryIsPinnedToApplicationAndDoesNotSignalHiddenUnrelatedActor() {
        val entry=ScheduledConditionExpiry("EXPIRE",subject,"TIRED",setOf("APPLICATION:1"))
        val base=snapshot(false).state
        val scheduled=Phase60ScheduledConditions.schedule(base,entry,WorldTimeTick(200))
        assertEquals(scheduled,Phase60ScheduledConditions.schedule(scheduled,entry,WorldTimeTick(200)))
        val input=TemporalOwnerInput(scope,WorldTimeTick(0),WorldTimeTick(200),emptyList(),scheduled.deadlines,scheduled.processStates.single())
        val output=Phase60ScheduledConditions.registered("C1",mapOf("EXPIRE" to entry.applicationUids)).owner.evaluate(input) as TemporalOwnerResult.Evaluated
        assertEquals(listOf(ConditionChange(subject,"TIRED",ConditionOperation.REMOVE)),output.changes)
        assertFalse(output.playerDecisionRequired)
        assertTrue(Phase60ScheduledConditions.registered("C1",mapOf("EXPIRE" to setOf("NEW_APPLICATION"))).owner.evaluate(input) is TemporalOwnerResult.Unsupported)
        assertTrue((Phase60ScheduledConditions.registered("C1",mapOf("EXPIRE" to entry.applicationUids),setOf(subject)).owner.evaluate(input) as TemporalOwnerResult.Evaluated).playerDecisionRequired)
    }
    private fun resolved(plan:CanonicalTurnPlan,effects:List<VerifiedMechanicsCommandEffect>):ResolvedGmProposal {
        val candidate=GmProposalCandidate(proposalUid="P",campaignUid="C1",planUid=plan.planUid,
            nodeProposals=listOf(GmNodeProposal("A","OK","wynik",actor,"TRAIN",listOf(subject),IntentModality.ATTEMPT_NOW,GmNodeOutcomeState.PROPOSED_SUCCESS)),
            mechanicsEffects=effects.map{MechanicsEffectRequest(it.effectUid,it.nodeUid,it.mechanicsOwnerUid,it.effectKindUid,it.target)},
            narrativeBlueprint=NarrativeBlueprint(listOf("RESULT"),stopPointUid="PLAYER_AGENCY"),providerUid="TEST",modelUid="M",intentFingerprint=plan.intent.canonicalFingerprint())
        return ResolvedGmProposal(candidate,effects.map{VerifiedMechanicsEffect(it.effectUid,it.nodeUid,it.mechanicsOwnerUid,it.effectKindUid,
            it.canonicalPayload+("magnitude" to it.magnitude.toString()),it.proofUid,it.deterministicInputFingerprint,it.deterministicOutputFingerprint)})
    }
    private fun assembler()=ProductionCanonicalMutationAssembler(productionMechanicsPlayerDomainEngine(),PlayerResolutionContextFactory { command->
        PlayerResolutionContext.createUnboundGeneric("C1",actor,(listOf(DomainRef("PLAYER","P1"),subject,DomainRef("CAMPAIGN","C1"),DomainRef("RESOURCE","ENERGY")))
            .map{CampaignScopedDomainRef("C1",it)}.toSet())
    })
    @Test fun ordinaryAssemblerCommitsOnlyInterruptedTrainingAndRetryDoesNotDuplicate() = SQLiteDatabase.create(null).use { db ->
        db.execSQL("CREATE TABLE campaign_calendar(id INTEGER PRIMARY KEY,absolute_day INTEGER,hour INTEGER,minute INTEGER)")
        db.execSQL("INSERT INTO campaign_calendar VALUES(1,0,0,0)")
        GroupATransactionTestFixtures.setupFinance(db)
        withAdministrativeMutationAuthority(db,"C1") {
            MechanicalActorStateStore(db,"C1").materializeIfMissing(MechanicalActorSeed(subject,MechanicalActorKind.NPC,"T","S","TEST",
                mapOf("POWER" to 10),listOf(MechanicalResource("ENERGY",100,100)),setOf("TRAIN")))
        }
        val cache=Cache()
        val wrapper=ProductionTemporalMutationAssembler(assembler(),{snapshot()},cache,processOwners={_,_->listOf(alarm())},
            effectPolicy=TemporalEffectPolicyPort{TemporalAccrualPolicy.PROPORTIONAL})
        val plan=plan()
        val proposal=wrapper.assemble(request,plan,resolved(plan,listOf(effect(),effect("C","RESOURCE_DELTA",-5))))
        assertNotNull(wrapper.lastAssemblyReasonUids().toString(),proposal)
        assertEquals(scope,wrapper.scopeFor(proposal!!))
        val identity=TurnTransactionIdentity("C1","T","CMD","TX")
        assertTrue(TurnTransactionBoundary.create(db,identity,proposal).commit() is TurnExecutionResult.Committed)
        assertEquals(WorldTimeTick(200),Phase60TemporalStateStore(db,"C1").read().time)
        assertEquals(2L,db.rawQuery("SELECT current_value FROM mechanical_actor_tracks WHERE track_uid='TRAINING:GENERAL'",null).use{it.moveToFirst();it.getLong(0)})
        assertTrue(TurnTransactionBoundary.create(db,identity,proposal).commit() is TurnExecutionResult.AlreadyCommitted)
        assertEquals(1L,Phase60TemporalStateStore(db,"C1").read().version)
        wrapper.committed("C1","CMD");assertNull(cache.value)
    }
    @Test fun ordinaryAssemblerRejectsStaleProposalAndReturnsTypedCancellation() {
        val wrapper=ProductionTemporalMutationAssembler(assembler(),{snapshot(false).copy(scope=scope.copy(baseCommitOrder=1))},Cache())
        assertNull(wrapper.assemble(request,plan(),resolved(plan(),listOf(effect()))))
        assertEquals(listOf("P60:STALE_TURN_CONTEXT"),wrapper.lastAssemblyReasonUids())
        assertNull(wrapper.assemble(request,plan(),resolved(plan(),listOf(effect()))){true})
        assertEquals(listOf("P60:CANCELLED"),wrapper.lastAssemblyReasonUids())
    }
}
