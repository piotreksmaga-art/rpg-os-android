package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase64DeadlineRecoveryTest {
    @Test fun committedInitiationDeadlineIsNotAddedAgainAfterReopen() {
        val scope=BackgroundProcessEvaluationScope(TemporalScope("C","G",1,"STATE"),"SEED","RULES")
        val rule=BackgroundProcessDefinition("R",1,"ECONOMY","TEST",10)
        val process=BackgroundProcessInstance("P",rule.uid,1,DomainRef("NPC","N"),1,WorldTimeTick(0),WorldTimeTick(10))
        var executions=0
        val reads=object:BackgroundWorldReadPort {
            override fun available(resource:DomainRef,staged:List<PlayerDomainChangePayload>):Long?=null
            override fun exists(ref:DomainRef)=true
            override fun route(actor:DomainRef,destination:DomainRef,at:WorldTimeTick):String?=null
            override fun authorize(actor:DomainRef,purpose:String,refs:List<DomainRef>)=true
            override fun prepareOwnedEffect(operation:String,actor:DomainRef,parameters:Map<String,String>,scope:BackgroundProcessEvaluationScope,staged:List<PlayerDomainChangePayload>)=WorldConsequencePlan()
        }
        val background=Phase64BackgroundProcessOwner(scope,listOf(process),mapOf((rule.uid to 1) to rule),{null},reads,
            listOf(object:BackgroundDomainAdapter {
                override val domains=setOf("ECONOMY")
                override fun evaluate(definition:BackgroundProcessDefinition,process:BackgroundProcessInstance,scope:BackgroundProcessEvaluationScope,at:WorldTimeTick,reads:BackgroundWorldReadPort,staged:List<PlayerDomainChangePayload>):WorldConsequencePlan {
                    executions++;return WorldConsequencePlan()
                }
            }))
        val foreground=object:WorldProcessOwnerPort {
            override val ownerUid="FOREGROUND"
            override fun evaluate(input:TemporalOwnerInput)=TemporalOwnerResult.Evaluated(TemporalOwnerState(ownerUid,1,"{}"))
        }
        val processor=Phase60TimeProcessor(listOf(foreground,background))
        val deadline=WorldProcessDeadline(Phase64BackgroundProcessOwner.deadline(process),background.ownerUid,process.due)
        val work=processor.begin(scope.temporal,"WAIT",WorldTimeTick(0),listOf(TimedActionNode("WAIT",foreground.ownerUid,
            AcceptedActionTiming(ActionDuration(10),"WAIT_RULE",1))),listOf(deadline))
        val result=processor.advance(work,scope.temporal)
        assertEquals(result.diagnostic,TemporalStopReason.COMPLETED,result.reason)
        assertEquals(1,executions)
        assertEquals(BackgroundProcessStatus.COMPLETED,result.checkpoint.candidateChanges.filterIsInstance<BackgroundProcessChange>().single().process.status)
        assertTrue(result.checkpoint.deadlines.isEmpty())
    }
}
