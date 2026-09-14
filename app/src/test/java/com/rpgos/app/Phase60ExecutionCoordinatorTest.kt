package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase60ExecutionCoordinatorTest {
    private val scope = TemporalScope("campaign", "generation", 10, "digest")
    private class Checkpoints : TemporalCheckpointPort {
        var value: TemporalExecutionCheckpoint? = null
        override fun load(campaignUid: String, commandUid: String) = value
        override fun save(checkpoint: TemporalExecutionCheckpoint) { value = checkpoint }
        override fun remove(campaignUid: String, commandUid: String) { value = null }
    }
    @Test fun receiptAfterHostCrashPreventsASecondEvaluationAndCommit() {
        var evaluations = 0
        var commits = 0
        var hasReceipt = false
        val owner = object : WorldProcessOwnerPort {
            override val ownerUid = "owner"
            override fun evaluate(input: TemporalOwnerInput): TemporalOwnerResult {
                evaluations++
                return TemporalOwnerResult.Evaluated(TemporalOwnerState(ownerUid, 1, "state"))
            }
        }
        val canonical = object : TemporalCanonicalCommitPort {
            override fun currentScope(campaignUid: String) = scope
            override fun committed(campaignUid: String, commandUid: String) = hasReceipt
            override fun admitAndCommit(result: TemporalExecutionResult): TemporalCommitDecision {
                commits++
                hasReceipt = true
                throw IllegalStateException("simulated lost response after commit")
            }
        }
        val processor = Phase60TimeProcessor(listOf(owner))
        val initial = processor.begin(scope, "command", WorldTimeTick(0), listOf(TimedActionNode("action", "owner", AcceptedActionTiming(ActionDuration(100), "rule", 1))))
        val checkpoints = Checkpoints()
        val coordinator = Phase60ExecutionCoordinator(processor, checkpoints, canonical)
        assertTrue(runCatching { coordinator.step(initial) }.isFailure)
        val count = evaluations
        assertEquals(TemporalCoordinatorResult.Commit(TemporalCommitDecision.AlreadyCommitted), coordinator.step(initial))
        assertEquals(count, evaluations)
        assertEquals(1, commits)
        assertNull(checkpoints.value)
    }
    @Test fun decisionCheckpointCannotRunPastTheDecisionOnResume() {
        var evaluations = 0
        val owner = object : WorldProcessOwnerPort {
            override val ownerUid = "owner"
            override fun evaluate(input: TemporalOwnerInput): TemporalOwnerResult {
                evaluations++
                return TemporalOwnerResult.Evaluated(TemporalOwnerState(ownerUid, 1, "state"), playerDecisionRequired = input.deadlines.isNotEmpty())
            }
        }
        val processor = Phase60TimeProcessor(listOf(owner))
        val initial = processor.begin(scope, "command", WorldTimeTick(0), listOf(TimedActionNode("action", "owner", AcceptedActionTiming(ActionDuration(100), "rule", 1))),
            listOf(WorldProcessDeadline("decision", "owner", WorldTimeTick(20))))
        val first = processor.advance(initial, scope)
        val count = evaluations
        val resumed = processor.advance(first.checkpoint, scope)
        assertEquals(first, resumed)
        assertEquals(count, evaluations)
        assertEquals(20L, resumed.checkpoint.reached.milliseconds)
    }
}
