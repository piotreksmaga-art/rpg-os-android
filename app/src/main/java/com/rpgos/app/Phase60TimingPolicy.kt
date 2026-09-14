package com.rpgos.app

enum class ActionTimeMeaning { IN_WORLD, OUT_OF_WORLD }

data class ActionTimingEvidence(
    val meaning: ActionTimeMeaning,
    val authoritative: AcceptedActionTiming? = null,
    val requestedDuration: ActionDuration? = null,
    val estimatedMinimum: ActionDuration? = null,
    val estimatedMaximum: ActionDuration? = null,
    /** Set by Core from deadlines/resources, never accepted as an AI assurance. */
    val uncertaintyHasMaterialConsequences: Boolean = true
)

sealed interface ActionTimingDecision {
    data class Accepted(val timing: AcceptedActionTiming) : ActionTimingDecision
    data object OutOfWorld : ActionTimingDecision
    data class ClarificationRequired(val reasonUid: String) : ActionTimingDecision
}

/** Universal fallback for accepted intent semantics; does not parse a list of verbs. */
object Phase60TimingPolicy {
    fun resolve(evidence: ActionTimingEvidence): ActionTimingDecision {
        if (evidence.meaning == ActionTimeMeaning.OUT_OF_WORLD) return ActionTimingDecision.OutOfWorld
        evidence.authoritative?.let { return ActionTimingDecision.Accepted(it) }
        evidence.requestedDuration?.let {
            if (it.milliseconds == 0L) return ActionTimingDecision.ClarificationRequired("P60:ZERO_REQUIRES_MECHANICS_RULE")
            return ActionTimingDecision.Accepted(AcceptedActionTiming(it, "P60:REQUESTED_DURATION", 1))
        }
        val minimum = evidence.estimatedMinimum?.milliseconds
        val maximum = evidence.estimatedMaximum?.milliseconds
        if (minimum == null || maximum == null || minimum <= 0 || maximum < minimum)
            return ActionTimingDecision.ClarificationRequired("P60:DURATION_UNRESOLVED")
        if (evidence.uncertaintyHasMaterialConsequences)
            return ActionTimingDecision.ClarificationRequired("P60:CONSEQUENTIAL_ESTIMATE")
        // Overflow-safe midpoint; identical accepted evidence always yields the same result.
        val midpoint = minimum + (maximum - minimum) / 2
        return ActionTimingDecision.Accepted(AcceptedActionTiming(ActionDuration(midpoint), "P60:BOUNDED_ESTIMATE", 1))
    }
}

/** Millisecond remainder is retained even though the legacy UI displays only hours/minutes. */
data class WorldCalendarReading(val absoluteDay: Long, val hour: Int, val minute: Int, val millisecondOfMinute: Int = 0) {
    init { require(hour in 0..23 && minute in 0..59 && millisecondOfMinute in 0..59_999) }
    fun toTick(): WorldTimeTick = WorldTimeTick(Math.addExact(Math.multiplyExact(absoluteDay, 86_400_000),
        hour * 3_600_000L + minute * 60_000L + millisecondOfMinute))
    companion object {
        fun fromTick(tick: WorldTimeTick): WorldCalendarReading {
            val day = Math.floorDiv(tick.milliseconds, 86_400_000L)
            val inDay = Math.floorMod(tick.milliseconds, 86_400_000L)
            return WorldCalendarReading(day, (inDay / 3_600_000).toInt(), (inDay / 60_000 % 60).toInt(), (inDay % 60_000).toInt())
        }
    }
}
