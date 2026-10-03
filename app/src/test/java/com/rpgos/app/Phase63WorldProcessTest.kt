package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase63WorldProcessTest {
    private val scope=TemporalScope("C","HGEN-C",7,"HASH")
    private val job=MechanicalActorExpansion(DomainRef("ACTOR","A"),3,MechanicalStateMaterialization.FULL)
    private fun input(previous:TemporalOwnerState?=null)=TemporalOwnerInput(scope,WorldTimeTick(100),WorldTimeTick(100),emptyList(),emptyList(),previous)
    @Test fun requiredRefinementRunsOnceWithoutAdvancingTimeOrControllingPlayer() {
        val owner=Phase63WorldProcessOwner(scope,4,listOf(job))
        val first=owner.evaluate(input()) as TemporalOwnerResult.Evaluated
        assertEquals(listOf(job),(first.changes.single() as WorldSimulationChange).actorExpansions)
        assertTrue(first.mechanicalEffects.isEmpty());assertTrue(first.nextDeadlines.isEmpty());assertFalse(first.playerDecisionRequired)
        assertTrue((owner.evaluate(input(first.state)) as TemporalOwnerResult.Evaluated).changes.isEmpty())
        assertTrue(owner.evaluate(input().copy(scope=scope.copy(historyGenerationUid="HGEN-OTHER"))) is TemporalOwnerResult.Unsupported)
    }
    @Test fun capturedVersionAndTypedBatchesSurviveForegroundComposition() {
        val skeleton=CampaignWorldSkeleton.legacy("C",CampaignRuleSource(CampaignRuleSourceKind.CAMPAIGN_NATIVE,"C","1"),"Era",DomainRef("PLACE","START"))
        val foreground=WorldSimulationChange("C",HistoryGenerationUid("HGEN-C"),0,skeleton)
        val refinement=WorldSimulationChange("C",HistoryGenerationUid("HGEN-C"),0,null,actorExpansions=listOf(job))
        val combined=phase63PreparedWorldChain(listOf(foreground),listOf(refinement))
        assertEquals(listOf(0L,1L),combined.map { it.expectedVersion })
        assertEquals(refinement.copy(expectedVersion=1),combined.last())
        assertThrows(IllegalArgumentException::class.java) { phase63PreparedWorldChain(listOf(foreground),listOf(refinement.copy(expectedVersion=9))) }
        assertThrows(IllegalArgumentException::class.java) { Phase63WorldProcessOwner(scope,0,(0..256).map { job.copy(actor=DomainRef("ACTOR","A$it")) }) }
    }
    @Test fun boundedRefinementUsesPhase60SuspensionAndRetainsEveryVersionedBatch() {
        val jobs=(0 until 80).map { job.copy(actor=DomainRef("ACTOR","A$it")) }
        var yields=0
        val owner=Phase63WorldProcessOwner(scope,4,jobs,yieldWork={yields++})
        assertTrue(owner.evaluate(input()) is TemporalOwnerResult.EvaluationRequired)
        val pending=TemporalEvaluationRequest(Phase63WorldProcessOwner.OWNER,input(),"P63:REFINEMENT_WORK_REQUIRED")
        val result=owner.extension().evaluation!!.evaluate(pending) {false} as TemporalEvaluationResponse.Accepted
        val batches=result.result.changes.map { it as WorldSimulationChange }
        assertEquals(listOf(32,32,16),batches.map { it.actorExpansions.size })
        assertEquals(listOf(4L,5L,6L),batches.map { it.expectedVersion })
        assertEquals(jobs.toSet(),batches.flatMap { it.actorExpansions }.toSet())
        assertEquals(3,yields);assertTrue(result.result.nextDeadlines.isEmpty())
        assertTrue((owner.evaluate(input(result.result.state)) as TemporalOwnerResult.Evaluated).changes.isEmpty())
        val reversed=Phase63WorldProcessOwner(scope,4,jobs.asReversed(),yieldWork={})
        assertEquals(result,reversed.extension().evaluation!!.evaluate(pending) {false})
        var current=scope
        val stale=Phase63WorldProcessOwner(scope,4,jobs,{current},{current=scope.copy(historyGenerationUid="NEW")})
        assertEquals(TemporalEvaluationResponse.Unavailable("P63:STALE_PROCESS_SCOPE"),stale.extension().evaluation!!.evaluate(pending){false})
        var cancelled=false
        val aborted=Phase63WorldProcessOwner(scope,4,jobs,yieldWork={cancelled=true})
        assertEquals(TemporalEvaluationResponse.Unavailable("P60:CANCELLED"),aborted.extension().evaluation!!.evaluate(pending){cancelled})
    }
    @Test fun sharedClockKeepsAggregateDeadlineAndRefinementWithoutAnonymousAiCalls() {
        val aggregate=DomainRef("GROUP","G5000")
        val entry=ScheduledConditionExpiry("D",aggregate,"TIRED",setOf("APPLICATION"))
        val state=Phase60ScheduledConditions.schedule(CanonicalTemporalState(0,WorldTimeTick(100),emptyList(),emptyList()),entry,WorldTimeTick(200))
        val captured=TemporalReadSnapshot(scope,state)
        val actor=CommandActorRef("PLAYER","P")
        val request=ChatTurnRequest("R","C","T","CMD","TX",actor,"Oczekuję","pl-PL",
            VisibilityAudienceFactory.player("C"),PurposeContext("C",VisibilityPurposeKinds.GAMEPLAY_NARRATION),8)
        val interval=TimedActionNode("WAIT",PHASE60_FOREGROUND_OWNER,AcceptedActionTiming(ActionDuration(1000),"CORE-WAIT",1))
        val timing=ProductionTimeResult.Ready(TemporalStateChange("C",state.version,state.time,WorldTimeTick(1100),
            Phase60ProcessStateCodec.encode(state.processStates),Phase60DeadlineCodec.encode(emptyList())),
            Phase60ActionPlanner.schedule(state.time,listOf(interval)))
        val jobs=(0 until 80).map { job.copy(actor=DomainRef("ACTOR","A$it")) }
        val refinement=Phase63WorldProcessOwner(scope,4,jobs,yieldWork={}).extension()
        val expiry=Phase60ScheduledConditions.registered("C",mapOf(entry.deadlineUid to entry.applicationUids))
        var checkpoint:TemporalExecutionCheckpoint?=null
        val cache=object:TemporalCheckpointPort {
            override fun load(campaignUid:String,commandUid:String)=checkpoint
            override fun save(value:TemporalExecutionCheckpoint) { checkpoint=value }
            override fun remove(campaignUid:String,commandUid:String) { checkpoint=null }
        }
        val execution=Phase60ProductionExecution(cache,{scope},refinement.owners+expiry,externalEvaluation=refinement.evaluation)
        fun run()=execution.execute(request,timing,captured,emptyList()){false} as ProductionTemporalExecutionResult.Completed
        val first=run()
        assertEquals(WorldTimeTick(1100),first.change.proposedTime)
        assertTrue(Phase60DeadlineCodec.decode(first.change.deadlinesCanonical).isEmpty())
        assertEquals(listOf(32,32,16),first.worldChanges.map { it.actorExpansions.size })
        requireWorldSimulationChain(first.worldChanges)
        assertEquals(1,first.effects.size)
        assertEquals(aggregate,first.effects.single().target)
        assertEquals(first,run()) // speculative replay never repeats a canonical write
    }
    @Test fun tenThousandHierarchicalSlotsAreStableWithoutGeneratingActorsOrHistory() {
        val skeleton=CampaignWorldSkeleton.legacy("C",CampaignRuleSource(CampaignRuleSourceKind.CAMPAIGN_NATIVE,"C","1"),"Era",DomainRef("PLACE","START"))
        val slots=(0 until 10000).map { LatentWorldSlot("REGION-${it/1000}","OPEN_CATEGORY_${it%10}",WorldElementBaseKind.OBJECT,(it%1000).toLong()) }
        val forward=slots.associateWith { it.ref(skeleton) }
        assertEquals(10000,forward.values.toSet().size)
        assertEquals(forward,slots.asReversed().associateWith { it.ref(skeleton) })
        assertEquals(WorldSimulationLod.LOD0_AGGREGATE,WorldLodPolicy.level(WorldLodInterest(DomainRef("GROUP","G"))))
        assertEquals(WorldSimulationLod.LOD3_INDIVIDUAL,WorldLodPolicy.level(WorldLodInterest(DomainRef("ACTOR","A"),directInteraction=true)))
    }
}
