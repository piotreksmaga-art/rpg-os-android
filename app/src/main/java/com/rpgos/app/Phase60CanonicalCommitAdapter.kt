package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

/** Uses the ordinary resolver/admission pipeline; never accepts a raw SQL writer. */
internal fun interface TemporalProposalAdmissionPort {
    fun admit(result: TemporalExecutionResult, timeChange: TemporalStateChange): CanonicalCampaignMutationProposal?
}

/** Database-backed implementation of the coordinator's commit boundary. */
internal class Phase60CanonicalCommitAdapter(
    private val db: SQLiteDatabase,
    private val campaignUid: String,
    private val admission: TemporalProposalAdmissionPort,
    private val identity: (TemporalExecutionCheckpoint) -> TurnTransactionIdentity
) : TemporalCanonicalCommitPort {
    fun begin(processor: Phase60TimeProcessor, commandUid: String, nodes: List<TimedActionNode>): TemporalExecutionCheckpoint = CampaignRuntimeLifecycleLock.withTurn(campaignUid) {
        val scope = currentScope(campaignUid)
        val state = Phase60TemporalStateStore(db, campaignUid).read()
        processor.begin(scope, commandUid, state.time, nodes, state.deadlines, state.processStates)
    }
    override fun currentScope(campaignUid: String): TemporalScope = CampaignRuntimeLifecycleLock.withTurn(this.campaignUid) {
        require(campaignUid == this.campaignUid)
        TemporalScope(campaignUid, HistoryGenerationStore(db, campaignUid).current().value,
            TurnTransactionReceiptStore(db).lastValidCommit(campaignUid)?.commitOrder ?: 0L, AuthoritativeStateDigest.compute(db))
    }
    override fun committed(campaignUid: String, commandUid: String): Boolean = CampaignRuntimeLifecycleLock.withTurn(this.campaignUid) {
        require(campaignUid == this.campaignUid)
        TurnTransactionReceiptStore(db).committedCommand(campaignUid, commandUid) != null
    }
    override fun admitAndCommit(result: TemporalExecutionResult): TemporalCommitDecision = CampaignRuntimeLifecycleLock.withTurn(campaignUid) {
        val work = result.checkpoint
        if (!result.readyForAdmission || work.scope.campaignUid != campaignUid)
            return@withTurn TemporalCommitDecision.Rejected("P60:RESULT_NOT_ADMISSIBLE")
        if (committed(campaignUid, work.commandUid)) return@withTurn TemporalCommitDecision.AlreadyCommitted
        if (currentScope(campaignUid) != work.scope) return@withTurn TemporalCommitDecision.Rejected("P60:STALE_HISTORY")
        val current = Phase60TemporalStateStore(db, campaignUid).read()
        if (current.time != work.startedAt) return@withTurn TemporalCommitDecision.Rejected("P60:START_TIME_MISMATCH")
        if (work.initialOwnerStates != current.processStates.associateBy { it.ownerUid } || work.initialDeadlines != current.deadlines ||
            !work.ownerStates.keys.containsAll(work.initialOwnerStates.keys))
            return@withTurn TemporalCommitDecision.Rejected("P60:PROCESS_STATE_MISMATCH")
        if (current.deadlines.any { deadline ->
                if (deadline.due <= work.reached) deadline.uid !in work.evaluatedDeadlineUids
                else deadline !in work.deadlines
            }) return@withTurn TemporalCommitDecision.Rejected("P60:DEADLINE_DROPPED")
        val change = TemporalStateChange(campaignUid, current.version, current.time, work.reached,
            Phase60ProcessStateCodec.encode(work.ownerStates.values.toList()), Phase60DeadlineCodec.encode(work.deadlines))
        val proposal = admission.admit(result, change) ?: return@withTurn TemporalCommitDecision.Rejected("P60:CORE_ADMISSION_REJECTED")
        // Timing must be part of the SAME sealed proposal, not applied before/after its commit.
        if (proposal.campaignUid != campaignUid || proposal.playerChangeSet.sourceCommandUid != work.commandUid ||
            proposal.playerChangeSet.changes.mapNotNull { it.payload as? TemporalStateChange } != listOf(change))
            return@withTurn TemporalCommitDecision.Rejected("P60:TEMPORAL_CHANGESET_MISMATCH")
        val settlement = Phase60EffectSettlement.validate(result, proposal.playerChangeSet.changes.map { it.payload })
        if (settlement != null) return@withTurn TemporalCommitDecision.Rejected(settlement)
        val turnIdentity = identity(work)
        if (turnIdentity.campaignUid != campaignUid || turnIdentity.commandUid != work.commandUid)
            return@withTurn TemporalCommitDecision.Rejected("P60:TURN_IDENTITY_MISMATCH")
        when (TurnTransactionBoundary.create(db, turnIdentity, proposal).commit()) {
            is TurnExecutionResult.Committed -> TemporalCommitDecision.Committed
            is TurnExecutionResult.AlreadyCommitted -> TemporalCommitDecision.AlreadyCommitted
            else -> TemporalCommitDecision.Rejected("P60:COMMIT_NOT_COMPLETED")
        }
    }
}
