package com.rpgos.app

import java.math.BigInteger

enum class TemporalAccrualPolicy { AT_COMPLETION, PROPORTIONAL }

/** Chosen by the domain owner, never inferred from an arbitrary AI effect or action label. */
data class TemporalAccrualContract(val ruleUid: String, val ruleVersion: Int, val totalUnits: Long, val policy: TemporalAccrualPolicy) {
    init { require(ruleUid.isNotBlank() && ruleVersion > 0) }
}

/**
 * Cumulative integer differences make results independent of scheduler slice size. There is no
 * blanket scaling of combat, inventory, milestones or rewards: the owner must choose its rule.
 * This computes a candidate quantity only; normal domain admission still validates the effect.
 */
object Phase60Accrual {
    fun intervalUnits(contract: TemporalAccrualContract, input: TemporalOwnerInput, action: ScheduledActionInterval): Long {
        require(action in input.actions)
        require(input.previous == null || action.action.ownerUid == input.previous.ownerUid)
        require(input.from <= input.through)
        val duration = Math.subtractExact(action.end.milliseconds, action.start.milliseconds)
        require(duration >= 0)
        if (duration == 0L) {
            require(action.action.timing.instantaneous)
            return if (input.through == action.end) contract.totalUnits else 0L
        }
        fun elapsed(at: WorldTimeTick): Long = when {
            at <= action.start -> 0L
            at >= action.end -> duration
            else -> Math.subtractExact(at.milliseconds, action.start.milliseconds)
        }
        val before = elapsed(input.from)
        val after = elapsed(input.through)
        if (contract.policy == TemporalAccrualPolicy.AT_COMPLETION)
            return if (before < duration && after == duration) contract.totalUnits else 0L
        val total = BigInteger.valueOf(contract.totalUnits)
        val divisor = BigInteger.valueOf(duration)
        fun accrued(value: Long) = total.multiply(BigInteger.valueOf(value)).divide(divisor)
        return accrued(after).subtract(accrued(before)).longValueExact()
    }
}
