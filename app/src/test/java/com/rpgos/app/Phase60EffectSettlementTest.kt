package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase60EffectSettlementTest {
    private val scope = TemporalScope("C", "G", 0, "H")
    private val action = ScheduledActionInterval(TimedActionNode("A", "O", AcceptedActionTiming(ActionDuration(10), "R", 1)), WorldTimeTick(0), WorldTimeTick(10))
    private fun result(changes: List<PlayerDomainChangePayload>, reason:TemporalStopReason = TemporalStopReason.PLAYER_DECISION) =
        TemporalExecutionResult(TemporalExecutionCheckpoint(scope,"CMD",WorldTimeTick(0),WorldTimeTick(3),listOf(action),emptyList(),
            candidateChanges=changes,terminalReason=reason),reason)
    private val partial = ResourceChange(DomainRef("PLAYER","P"),"ENERGY",ExactLongDelta.of(-2))
    private fun interval(from:Long, through:Long) = TemporalOwnerInput(scope,WorldTimeTick(from),WorldTimeTick(through),listOf(action),emptyList(),null)

    @Test fun interruptedWorkCannotReceiveFinalEffectOrDropItsCost() {
        assertEquals("P60:INTERVAL_EFFECT_DROPPED", Phase60EffectSettlement.validate(result(listOf(partial)),emptyList()))
        assertEquals("P60:UNEVALUATED_INTERRUPTION_EFFECT", Phase60EffectSettlement.validate(result(listOf(partial)),listOf(partial,partial)))
        assertNull(Phase60EffectSettlement.validate(result(listOf(partial)),listOf(partial)))
    }
    @Test fun identicalEffectsKeepTheirMultiplicity() {
        assertEquals("P60:INTERVAL_EFFECT_DROPPED",Phase60EffectSettlement.validate(result(listOf(partial,partial)),listOf(partial)))
        assertNull(Phase60EffectSettlement.validate(result(listOf(partial,partial)),listOf(partial,partial)))
    }
    @Test fun proportionalProgressIsIndependentOfSliceSizeForPositiveAndNegativeAmounts() {
        listOf(7L,-7L,Long.MAX_VALUE,Long.MIN_VALUE).forEach { total ->
            val rule = TemporalAccrualContract("R",1,total,TemporalAccrualPolicy.PROPORTIONAL)
            val split = Math.addExact(Phase60Accrual.intervalUnits(rule,interval(0,3),action),Phase60Accrual.intervalUnits(rule,interval(3,10),action))
            assertEquals(total,split)
            assertEquals(total,Phase60Accrual.intervalUnits(rule,interval(0,10),action))
        }
    }
    @Test fun completionRewardIsNotPaidAtInterruptionOrAgainAfterCompletion() {
        val rule = TemporalAccrualContract("R",1,7,TemporalAccrualPolicy.AT_COMPLETION)
        assertEquals(0L,Phase60Accrual.intervalUnits(rule,interval(0,3),action))
        assertEquals(7L,Phase60Accrual.intervalUnits(rule,interval(3,10),action))
        assertEquals(0L,Phase60Accrual.intervalUnits(rule,interval(10,20),action))
    }
}
