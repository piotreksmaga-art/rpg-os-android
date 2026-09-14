package com.rpgos.app

/** Rebuildable speculative work; implementations must not store this in the canonical database. */
internal interface TemporalCheckpointPort {
    fun load(campaignUid: String, commandUid: String): TemporalExecutionCheckpoint?
    fun save(checkpoint: TemporalExecutionCheckpoint)
    fun remove(campaignUid: String, commandUid: String)
}

/**
 * The adapter must check scope under the same lifecycle lock used by TurnTransaction, admit
 * through Core, and include time, domain effects and process states in ONE replayable changeset.
 * A receipt lookup precedes re-evaluation so a crash after commit cannot charge time twice.
 */
internal interface TemporalCanonicalCommitPort {
    fun currentScope(campaignUid: String): TemporalScope
    fun committed(campaignUid: String, commandUid: String): Boolean
    fun admitAndCommit(result: TemporalExecutionResult): TemporalCommitDecision
}

internal sealed interface TemporalCommitDecision {
    data object Committed : TemporalCommitDecision
    data object AlreadyCommitted : TemporalCommitDecision
    data class Rejected(val reasonUid: String) : TemporalCommitDecision
}

internal sealed interface TemporalCoordinatorResult {
    data class Pending(val progress: TemporalExecutionResult) : TemporalCoordinatorResult
    data class Stopped(val progress: TemporalExecutionResult) : TemporalCoordinatorResult
    data class Commit(val decision: TemporalCommitDecision) : TemporalCoordinatorResult
}

/** One cooperative slice per call; application coroutines choose when to yield and resume. */
internal class Phase60ExecutionCoordinator(
    private val processor: Phase60TimeProcessor,
    private val checkpoints: TemporalCheckpointPort,
    private val canonical: TemporalCanonicalCommitPort
) {
    fun step(initial: TemporalExecutionCheckpoint, cancelled: () -> Boolean = { false }): TemporalCoordinatorResult {
        val campaign = initial.scope.campaignUid
        val command = initial.commandUid
        if (canonical.committed(campaign, command)) {
            checkpoints.remove(campaign, command)
            return TemporalCoordinatorResult.Commit(TemporalCommitDecision.AlreadyCommitted)
        }
        val saved = checkpoints.load(campaign, command)
        require(saved == null || (saved.scope == initial.scope && saved.commandUid == command &&
            saved.startedAt == initial.startedAt && saved.schedule == initial.schedule &&
            saved.executionFingerprint == initial.executionFingerprint &&
            saved.initialDeadlines == initial.initialDeadlines && saved.initialOwnerStates == initial.initialOwnerStates)) { "P60:CHECKPOINT_IDENTITY_CONFLICT" }
        val result = processor.advance(saved ?: initial, canonical.currentScope(campaign), cancelled)
        if (result.reason == TemporalStopReason.YIELDED) {
            checkpoints.save(result.checkpoint)
            return TemporalCoordinatorResult.Pending(result)
        }
        if (!result.readyForAdmission) {
            checkpoints.remove(campaign, command)
            return TemporalCoordinatorResult.Stopped(result)
        }
        // Persist the terminal speculative result before admission. Recovery must not run owners
        // again after a player-decision boundary, even when the host dies before the receipt.
        checkpoints.save(result.checkpoint)
        if (cancelled()) {
            checkpoints.remove(campaign, command)
            return TemporalCoordinatorResult.Stopped(result.copy(reason = TemporalStopReason.CANCELLED))
        }
        val decision = canonical.admitAndCommit(result)
        if (decision !is TemporalCommitDecision.Rejected) checkpoints.remove(campaign, command)
        return TemporalCoordinatorResult.Commit(decision)
    }
}
