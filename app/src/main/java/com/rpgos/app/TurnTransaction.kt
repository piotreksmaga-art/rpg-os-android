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

    companion object {
        val NONE = TurnFailureInjector { }
    }
}

/**
 * Phase-27 outer SQLite transaction owner. It coordinates already-validated writes only; gameplay
 * rules remain in domain engines/stores. Participants receive the same database connection and may
 * join it, but they must not own an independent commit while this transaction is active.
 */
class TurnTransaction internal constructor(
    private val db: SQLiteDatabase,
    val identity: TurnTransactionIdentity,
    private val failureInjector: TurnFailureInjector = TurnFailureInjector.NONE
) {
    var state: TurnTransactionState = TurnTransactionState.VALIDATED
        private set

    fun <T> execute(block: TurnTransactionScope.() -> T): T {
        check(state == TurnTransactionState.VALIDATED) { "turn transaction can execute exactly once" }
        check(!db.inTransaction()) { "nested outer TurnTransaction is forbidden" }
        failureInjector.failIfRequested(TurnFailurePoint.BEFORE_FIRST_WRITE)
        db.beginTransaction()
        state = TurnTransactionState.IN_PROGRESS
        return try {
            val scope = TurnTransactionScope(db, identity, failureInjector)
            val result = scope.block()
            failureInjector.failIfRequested(TurnFailurePoint.BEFORE_COMMIT)
            db.setTransactionSuccessful()
            db.endTransaction()
            state = TurnTransactionState.COMMITTED
            result
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
    fun statResourceStore(): StatResourceStore = StatResourceStore(db, identity.campaignUid)
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
        return TurnTransaction(db, identity, failureInjector)
    }
}
