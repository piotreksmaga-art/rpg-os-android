package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

enum class TurnTransactionState { PROPOSED, VALIDATED, IN_PROGRESS, COMMITTED, ROLLED_BACK }

data class TurnTransactionIdentity(
    val campaignUid: String,
    val turnUid: String,
    val commandUid: String,
    val transactionUid: String
) {
    init {
        require(campaignUid.isNotBlank())
        require(turnUid.isNotBlank())
        require(commandUid.isNotBlank())
        require(transactionUid.isNotBlank())
    }
}

enum class TurnFailurePoint { BEFORE_FIRST_WRITE, AFTER_FIRST_WRITE, AFTER_SECOND_DOMAIN_WRITE, BEFORE_COMMIT }

fun interface TurnFailureInjector {
    fun failIfRequested(point: TurnFailurePoint)
    companion object { val NONE = TurnFailureInjector { } }
}

/** Phase-27 outer transaction owner, extended in Phase 28 with durable transaction-level replay protection. */
class TurnTransaction internal constructor(
    private val db: SQLiteDatabase,
    val identity: TurnTransactionIdentity,
    private val failureInjector: TurnFailureInjector = TurnFailureInjector.NONE,
    private val semanticFingerprint: String = TurnSemanticFingerprint.identityOnlyForInternalTest(identity)
) {
    private val receiptStore = TurnTransactionReceiptStore(db)

    var state: TurnTransactionState = TurnTransactionState.VALIDATED
        private set

    fun <T> execute(block: TurnTransactionScope.() -> T): TurnExecutionResult<T> {
        check(state == TurnTransactionState.VALIDATED) { "turn transaction can execute exactly once" }
        check(!db.inTransaction()) { "nested outer TurnTransaction is forbidden" }

        receiptStore.replay(identity, semanticFingerprint)?.let { existing ->
            state = TurnTransactionState.COMMITTED
            return TurnExecutionResult.AlreadyCommitted(existing)
        }

        failureInjector.failIfRequested(TurnFailurePoint.BEFORE_FIRST_WRITE)
        db.beginTransaction()
        state = TurnTransactionState.IN_PROGRESS
        return try {
            // Re-check while holding the outer write transaction to close the admission/commit TOCTOU window.
            receiptStore.replay(identity, semanticFingerprint)?.let { existing ->
                db.setTransactionSuccessful()
                db.endTransaction()
                state = TurnTransactionState.COMMITTED
                return TurnExecutionResult.AlreadyCommitted(existing)
            }

            val result = TurnTransactionScope(db, identity, failureInjector).block()
            failureInjector.failIfRequested(TurnFailurePoint.BEFORE_COMMIT)
            val receipt = receiptStore.appendCommitted(identity, semanticFingerprint)
            db.setTransactionSuccessful()
            db.endTransaction()
            state = TurnTransactionState.COMMITTED
            TurnExecutionResult.Committed(result, receipt)
        } catch (failure: Throwable) {
            if (db.inTransaction()) db.endTransaction()
            state = TurnTransactionState.ROLLED_BACK
            throw failure
        }
    }
}

class TurnTransactionScope internal constructor(
    internal val db: SQLiteDatabase,
    val identity: TurnTransactionIdentity,
    private val failureInjector: TurnFailureInjector
) {
    private var authoritativeWrites = 0

    fun authoritativeWrite(block: (SQLiteDatabase) -> Unit) {
        require(db.inTransaction()) { "authoritative turn write requires active outer transaction" }
        block(db)
        authoritativeWrites += 1
        if (authoritativeWrites == 1) failureInjector.failIfRequested(TurnFailurePoint.AFTER_FIRST_WRITE)
        if (authoritativeWrites == 2) failureInjector.failIfRequested(TurnFailurePoint.AFTER_SECOND_DOMAIN_WRITE)
    }

    fun financialStore(): FinancialStore = FinancialStore(db, identity.campaignUid)
    fun ownershipStore(): OwnershipStore = OwnershipStore(db, identity.campaignUid)
    fun inventoryStore(): InventoryStore = InventoryStore(db, identity.campaignUid)
    internal fun statResourceStore(): StatResourceStore = StatResourceStore(db, identity.campaignUid)
    fun developmentProjectStore(): DevelopmentProjectStore = DevelopmentProjectStore(db, identity.campaignUid)
}

object TurnTransactionBoundary {
    const val CAMPAIGN_MISMATCH = "RPGOS-TURN-TRANSACTION:CAMPAIGN_MISMATCH"
    const val COMMAND_MISMATCH = "RPGOS-TURN-TRANSACTION:COMMAND_MISMATCH"

    fun create(
        db: SQLiteDatabase,
        identity: TurnTransactionIdentity,
        proposal: CanonicalCampaignMutationProposal,
        failureInjector: TurnFailureInjector = TurnFailureInjector.NONE
    ): TurnTransaction {
        require(identity.campaignUid == proposal.campaignUid) { CAMPAIGN_MISMATCH }
        require(identity.commandUid == proposal.playerChangeSet.sourceCommandUid) { COMMAND_MISMATCH }
        return TurnTransaction(
            db = db,
            identity = identity,
            failureInjector = failureInjector,
            semanticFingerprint = TurnSemanticFingerprint.forProposal(proposal)
        )
    }
}
