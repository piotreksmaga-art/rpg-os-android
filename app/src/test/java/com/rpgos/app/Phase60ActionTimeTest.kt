package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase60ActionTimeTest {
    private val scope = TemporalScope("campaign", "generation", 4, "digest")
    private fun node(uid: String, duration: Long, after: Set<String> = emptySet(), slots: Set<String> = emptySet()) =
        TimedActionNode(uid, "owner", AcceptedActionTiming(ActionDuration(duration), "rule", 1), after, slots)
    private fun owner(stop: Boolean = false) = object : WorldProcessOwnerPort {
        override val ownerUid = "owner"
        override fun evaluate(input: TemporalOwnerInput) = TemporalOwnerResult.Evaluated(
            TemporalOwnerState(ownerUid, 1, input.through.milliseconds.toString()), playerDecisionRequired = stop && input.deadlines.isNotEmpty())
    }
    @Test fun parallelAndSequentialActionsUseOneTimeline() {
        val schedule = Phase60ActionPlanner.schedule(WorldTimeTick(0), listOf(node("walk", 100), node("talk", 60), node("read", 40, setOf("walk"))))
        assertEquals(140L, schedule.maxOf { it.end.milliseconds })
        assertEquals(100L, schedule.single { it.action.uid == "read" }.start.milliseconds)
    }
    @Test fun exclusiveActionsAndCyclesAreRejected() {
        assertTrue(runCatching { Phase60ActionPlanner.schedule(WorldTimeTick(0), listOf(node("a", 20, slots = setOf("hands")), node("b", 20, slots = setOf("hands")))) }.isFailure)
        assertTrue(runCatching { Phase60ActionPlanner.schedule(WorldTimeTick(0), listOf(node("a", 20, setOf("b")), node("b", 20, setOf("a")))) }.isFailure)
    }
    @Test fun interruptionStopsAtDeadlineNotAtEndOfTraining() {
        val processor = Phase60TimeProcessor(listOf(owner(true)))
        val work = processor.begin(scope, "command", WorldTimeTick(0), listOf(node("training", 1000)), listOf(WorldProcessDeadline("bell", "owner", WorldTimeTick(200))))
        val result = processor.advance(work, scope)
        assertEquals(TemporalStopReason.PLAYER_DECISION, result.reason)
        assertEquals(200L, result.checkpoint.reached.milliseconds)
        assertTrue(result.readyForAdmission)
    }
    @Test fun yieldingAndResumeEqualSingleExecution() {
        val processor = Phase60TimeProcessor(listOf(owner()))
        val initial = processor.begin(scope, "command", WorldTimeTick(0), listOf(node("a", 100), node("b", 50)))
        var result = processor.advance(initial, scope, maxBoundaries = 1)
        assertEquals(TemporalStopReason.YIELDED, result.reason)
        while (result.reason == TemporalStopReason.YIELDED) result = processor.advance(result.checkpoint, scope, maxBoundaries = 1)
        assertEquals(processor.advance(initial, scope).checkpoint, result.checkpoint)
    }
    @Test fun staleGenerationAndCancellationCannotBeAdmitted() {
        val processor = Phase60TimeProcessor(listOf(owner()))
        val work = processor.begin(scope, "command", WorldTimeTick(0), listOf(node("a", 100)))
        assertEquals(TemporalStopReason.STALE_HISTORY, processor.advance(work, scope.copy(historyGenerationUid = "undo")).reason)
        assertFalse(processor.advance(work, scope, cancelled = { true }).readyForAdmission)
    }
    @Test fun missingOwnerDoesNotPretendTimePassed() {
        val processor = Phase60TimeProcessor(emptyList())
        val work = processor.begin(scope, "command", WorldTimeTick(0), listOf(node("a", 100)))
        val result = processor.advance(work, scope)
        assertEquals(TemporalStopReason.UNSUPPORTED_OWNER, result.reason)
        assertEquals(0L, result.checkpoint.reached.milliseconds)
    }
    @Test fun persistedBackgroundProcessCannotBeSkippedWhenItsOwnerIsMissing() {
        val processor = Phase60TimeProcessor(listOf(owner()))
        val work = processor.begin(scope,"command",WorldTimeTick(0),listOf(node("a",100)),
            ownerStates=listOf(TemporalOwnerState("missing-background-owner",1,"pending")))
        val result = processor.advance(work,scope)
        assertEquals(TemporalStopReason.UNSUPPORTED_OWNER,result.reason)
        assertEquals(work,result.checkpoint)
    }
    @Test fun reusedDeadlineCannotCreateAnInfiniteLoop() {
        val bad = object : WorldProcessOwnerPort {
            override val ownerUid = "owner"
            override fun evaluate(input: TemporalOwnerInput) = TemporalOwnerResult.Evaluated(TemporalOwnerState(ownerUid, 1, ""),
                nextDeadlines = if (input.deadlines.isEmpty()) emptyList() else listOf(WorldProcessDeadline("alarm", ownerUid, input.through + ActionDuration(1))))
        }
        val processor = Phase60TimeProcessor(listOf(bad))
        val work = processor.begin(scope, "command", WorldTimeTick(0), listOf(node("a", 100)), listOf(WorldProcessDeadline("alarm", "owner", WorldTimeTick(5))))
        assertEquals(TemporalStopReason.INVALID_OWNER_RESULT, processor.advance(work, scope).reason)
    }
    @Test fun calendarRoundTripPreservesNegativeDaysAndSubminutePrecision() {
        listOf(-86_400_001L, -1L, 0L, 86_400_001L).forEach { value ->
            assertEquals(value, WorldCalendarReading.fromTick(WorldTimeTick(value)).toTick().milliseconds)
        }
    }
    @Test fun uncertaintyAndMetaAreNotSilentZeroDuration() {
        assertEquals(ActionTimingDecision.OutOfWorld, Phase60TimingPolicy.resolve(ActionTimingEvidence(ActionTimeMeaning.OUT_OF_WORLD)))
        assertTrue(Phase60TimingPolicy.resolve(ActionTimingEvidence(ActionTimeMeaning.IN_WORLD)) is ActionTimingDecision.ClarificationRequired)
        val accepted = Phase60TimingPolicy.resolve(ActionTimingEvidence(ActionTimeMeaning.IN_WORLD, estimatedMinimum = ActionDuration(10), estimatedMaximum = ActionDuration(20), uncertaintyHasMaterialConsequences = false)) as ActionTimingDecision.Accepted
        assertEquals(15L, accepted.timing.duration.milliseconds)
    }
}
