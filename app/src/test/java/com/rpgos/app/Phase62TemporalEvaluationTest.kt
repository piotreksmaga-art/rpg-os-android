package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase62TemporalEvaluationTest {
    private val scope=TemporalScope("C1","G1",1,"digest")
    private val node=TimedActionNode("WAIT",PHASE60_FOREGROUND_OWNER,AcceptedActionTiming(ActionDuration(1000),"RULE",1))
    private val owner=object:WorldProcessOwnerPort {
        override val ownerUid="NPC"
        override fun evaluate(input:TemporalOwnerInput):TemporalOwnerResult=if(input.deadlines.isEmpty())
            TemporalOwnerResult.Evaluated(input.previous?:TemporalOwnerState(ownerUid,1,"idle"))
        else TemporalOwnerResult.EvaluationRequired("P62:NPC_DECISION")
    }
    private val foreground=object:WorldProcessOwnerPort {
        override val ownerUid=PHASE60_FOREGROUND_OWNER
        override fun evaluate(input:TemporalOwnerInput)=TemporalOwnerResult.Evaluated(TemporalOwnerState(ownerUid,1,"work"))
    }
    private class Cache:TemporalCheckpointPort {
        var value:TemporalExecutionCheckpoint?=null
        override fun load(campaignUid:String,commandUid:String)=value
        override fun save(checkpoint:TemporalExecutionCheckpoint){value=checkpoint}
        override fun remove(campaignUid:String,commandUid:String){value=null}
    }
    @Test fun pureOwnerSuspendsWithoutAdvancingBoundaryOrPretendingPlayerChoice() {
        val processor=Phase60TimeProcessor(listOf(foreground,owner))
        val initial=processor.begin(scope,"CMD",WorldTimeTick(0),listOf(node),listOf(WorldProcessDeadline("D","NPC",WorldTimeTick(500))))
        val waiting=processor.advance(initial,scope)
        assertEquals(TemporalStopReason.OWNER_EVALUATION_REQUIRED,waiting.reason)
        assertFalse(waiting.readyForAdmission);assertEquals(WorldTimeTick(0),waiting.checkpoint.reached)
        assertTrue(waiting.checkpoint.candidateChanges.isEmpty());assertNull(waiting.checkpoint.terminalReason)
        val pending=waiting.pendingEvaluation!!
        assertEquals(WorldTimeTick(500),pending.input.through)
        val resumed=processor.advance(waiting.checkpoint,scope,evaluations=mapOf(pending.fingerprint to TemporalOwnerResult.Evaluated(TemporalOwnerState("NPC",1,"decided"))))
        assertEquals(TemporalStopReason.COMPLETED,resumed.reason)
        assertEquals("decided",resumed.checkpoint.ownerStates.getValue("NPC").canonicalValue)
    }
    @Test fun wrongAnswerCannotResolvePendingInputOrForgeOwner() {
        val processor=Phase60TimeProcessor(listOf(foreground,owner))
        val initial=processor.begin(scope,"CMD",WorldTimeTick(0),listOf(node),listOf(WorldProcessDeadline("D","NPC",WorldTimeTick(500))))
        val waiting=processor.advance(initial,scope);val pending=waiting.pendingEvaluation!!
        assertNotEquals(pending.fingerprint,TemporalEvaluationRequest("NPC",pending.input.copy(scope=scope.copy(historyGenerationUid="G2")),pending.reasonUid).fingerprint)
        val wrong=TemporalOwnerResult.Evaluated(TemporalOwnerState("OTHER",1,"forged"))
        assertEquals(TemporalStopReason.OWNER_EVALUATION_REQUIRED,processor.advance(waiting.checkpoint,scope,evaluations=mapOf("wrong" to wrong)).reason)
        assertEquals(TemporalStopReason.INVALID_OWNER_RESULT,processor.advance(waiting.checkpoint,scope,evaluations=mapOf(pending.fingerprint to wrong)).reason)
    }
    private fun run(evaluator:TemporalExternalEvaluationPort?,scopeNow:()->TemporalScope={scope},cancelled:()->Boolean={false}):ProductionTemporalExecutionResult {
        val request=ChatTurnRequest("REQ","C1","TURN","CMD","TX",CommandActorRef("PLAYER","P1"),"czekam","pl",
            VisibilityAudienceFactory.player("C1"),PurposeContext("C1",VisibilityPurposeKinds.GAMEPLAY_NARRATION),2)
        val snapshot=TemporalReadSnapshot(scope,CanonicalTemporalState(0,WorldTimeTick(0),emptyList(),listOf(WorldProcessDeadline("D","NPC",WorldTimeTick(500)))))
        val timing=ProductionTimeResult.Ready(TemporalStateChange("C1",0,WorldTimeTick(0),WorldTimeTick(1000),
            Phase60ProcessStateCodec.encode(emptyList()),Phase60DeadlineCodec.encode(emptyList())),Phase60ActionPlanner.schedule(WorldTimeTick(0),listOf(node)))
        return Phase60ProductionExecution(Cache(),scopeNow,listOf(RegisteredTemporalOwner("1",owner)),externalEvaluation=evaluator)
            .execute(request,timing,snapshot,emptyList(),cancelled)
    }
    @Test fun applicationEvaluatesExternallyAndResumesSameTurn() {
        var calls=0
        val result=run(TemporalExternalEvaluationPort{r,_->calls++;TemporalEvaluationResponse.Accepted(r.fingerprint,TemporalOwnerResult.Evaluated(TemporalOwnerState("NPC",1,"ready")))})
        assertTrue(result.toString(),result is ProductionTemporalExecutionResult.Completed)
        assertEquals(1,calls);assertEquals(WorldTimeTick(1000),(result as ProductionTemporalExecutionResult.Completed).change.proposedTime)
    }
    @Test fun failureAndUndoWhileModelRunsNeverAdmitPartialTime() {
        assertEquals(ProductionTemporalExecutionResult.Rejected("P62:EVALUATION_OWNER_MISSING"),run(null))
        assertEquals(ProductionTemporalExecutionResult.Rejected("P62:AI_OFFLINE"),run(TemporalExternalEvaluationPort{_,_->TemporalEvaluationResponse.Unavailable("P62:AI_OFFLINE")}))
        var current=scope
        val evaluator=TemporalExternalEvaluationPort{r,_->current=scope.copy(historyGenerationUid="G2")
            TemporalEvaluationResponse.Accepted(r.fingerprint,TemporalOwnerResult.Evaluated(TemporalOwnerState("NPC",1,"late")))}
        assertEquals(ProductionTemporalExecutionResult.Rejected("P60:STALE_HISTORY"),run(evaluator,{current}))
    }
}
