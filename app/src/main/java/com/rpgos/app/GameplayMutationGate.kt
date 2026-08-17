package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

/**
 * Group-A gameplay mutation gate.
 *
 * Legacy/admin databases that have not entered Group-A transactional mode retain their historical
 * write contracts. Once the transaction receipt schema is installed, authoritative gameplay writes
 * require the active canonical TurnTransaction on the same SQLite connection and campaign.
 */
private data class ActiveGameplayMutation(val db: SQLiteDatabase, val campaignUid: String)
private val activeGameplayMutation = ThreadLocal<ActiveGameplayMutation?>()

internal fun requireCanonicalGameplayMutation(db: SQLiteDatabase, campaignUid: String) {
    if (!TurnTransactionReceiptSchema.isReady(db)) return
    val active = activeGameplayMutation.get()
    require(active != null && active.db === db && active.campaignUid == campaignUid) {
        "RPGOS-MUTATION-GATE:CANONICAL_TURN_TRANSACTION_REQUIRED"
    }
}

internal fun isCanonicalGameplayMutationActive(db: SQLiteDatabase, campaignUid: String): Boolean {
    val active = activeGameplayMutation.get()
    return active != null && active.db === db && active.campaignUid == campaignUid
}

/** File-private activation: ordinary production callers cannot manufacture gameplay authority. */
internal inline fun <T> withCanonicalGameplayMutationForTurn(
    db: SQLiteDatabase,
    campaignUid: String,
    canonicalSeal: Any,
    block: () -> T
): T {
    require(TurnTransactionBoundary.acceptsCanonicalSeal(canonicalSeal)) {
        "RPGOS-MUTATION-GATE:INVALID_TURN_CAPABILITY"
    }
    val previous = activeGameplayMutation.get()
    require(previous == null) { "RPGOS-MUTATION-GATE:NESTED_GAMEPLAY_CAPABILITY" }
    activeGameplayMutation.set(ActiveGameplayMutation(db, campaignUid))
    return try { block() } finally { activeGameplayMutation.set(previous) }
}
