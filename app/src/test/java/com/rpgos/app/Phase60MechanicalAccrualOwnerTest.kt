package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase60MechanicalAccrualOwnerTest {
    private val scope = TemporalScope("C1","G1",0,"H")
    private val subject = DomainRef("PLAYER","P1")
    private fun owner(policy:TemporalAccrualPolicy):Phase60MechanicalAccrualOwner {
        val approved = VerifiedMechanicsCommandEffect("E","TRAIN","CORE","TRAINING",subject,10,
            mapOf("track_uid" to "TRAINING:GENERAL"),"PROOF","INPUT","OUTPUT")
        val payload = (MechanicalEffectMaterializer.materialize(approved) as MechanicalEffectMaterializationResult.Materialized).changes.single().payload
        return Phase60MechanicalAccrualOwner("MECHANICS","C1",listOf(
            TemporalMechanicalAccrualBinding("PROGRESS","TRAIN",payload,TemporalAccrualContract("PROGRESS-RULE",1,10,policy)),
            TemporalMechanicalAccrualBinding("COST","TRAIN",ResourceChange(subject,"ENERGY",ExactLongDelta.of(-5)),
                TemporalAccrualContract("COST-RULE",1,-5,TemporalAccrualPolicy.PROPORTIONAL))))
    }
    private fun run(policy:TemporalAccrualPolicy, sliced:Boolean):TemporalExecutionResult {
        val alarm = object:WorldProcessOwnerPort {
            override val ownerUid="ALARM"
            override fun evaluate(input:TemporalOwnerInput)=TemporalOwnerResult.Evaluated(TemporalOwnerState(ownerUid,1,"ready"),
                playerDecisionRequired=input.deadlines.isNotEmpty())
        }
        val processor=Phase60TimeProcessor(listOf(owner(policy),alarm))
        val initial=processor.begin(scope,"CMD",WorldTimeTick(0),listOf(TimedActionNode("TRAIN","MECHANICS",
            AcceptedActionTiming(ActionDuration(1000),"DURATION-RULE",1))),listOf(WorldProcessDeadline("BELL","ALARM",WorldTimeTick(200))))
        var result=processor.advance(initial,scope,maxBoundaries=if(sliced)1 else 256)
        while(result.reason==TemporalStopReason.YIELDED) result=processor.advance(result.checkpoint,scope,maxBoundaries=1)
        return result
    }
    @Test fun actualTrainingTrackAndResourceCostAccrueOnlyUntilInterruption() {
        val result=run(TemporalAccrualPolicy.PROPORTIONAL,false)
        assertEquals(TemporalStopReason.PLAYER_DECISION,result.reason)
        assertEquals(WorldTimeTick(200),result.checkpoint.reached)
        assertEquals(2L,(result.checkpoint.candidateChanges.single{it is MechanicalTrackChange} as MechanicalTrackChange).delta.units)
        assertEquals(-1L,(result.checkpoint.candidateChanges.single{it is ResourceChange} as ResourceChange).delta.units)
        assertEquals(result.checkpoint,run(TemporalAccrualPolicy.PROPORTIONAL,true).checkpoint)
    }
    @Test fun completionOnlyTrainingDoesNotGrantProgressAtInterruption() {
        val result=run(TemporalAccrualPolicy.AT_COMPLETION,false)
        assertTrue(result.checkpoint.candidateChanges.none{it is MechanicalTrackChange})
        assertEquals(-1L,(result.checkpoint.candidateChanges.single() as ResourceChange).delta.units)
    }
    @Test fun ownerRejectsAnotherCampaignAndNonQuantifiedMechanics() {
        val input=TemporalOwnerInput(scope.copy(campaignUid="OTHER"),WorldTimeTick(0),WorldTimeTick(0),emptyList(),emptyList(),null)
        assertTrue(owner(TemporalAccrualPolicy.PROPORTIONAL).evaluate(input) is TemporalOwnerResult.Unsupported)
        assertTrue(runCatching{TemporalMechanicalAccrualBinding("E","A",SpatialChange(subject,1),
            TemporalAccrualContract("R",1,1,TemporalAccrualPolicy.PROPORTIONAL))}.isFailure)
    }
}
