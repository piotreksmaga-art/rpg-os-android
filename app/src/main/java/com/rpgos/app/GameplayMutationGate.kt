package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

private data class ActiveGameplayMutation(val db: SQLiteDatabase, val campaignUid: String)
private val activeGameplayMutation = ThreadLocal<ActiveGameplayMutation?>()

/**
 * Authoritative gameplay writers call this before mutation. Legacy/admin databases which have not
 * entered Group-A transactional mode keep their historical contracts; schema-ready gameplay DBs
 * require the active canonical TurnTransaction on the same connection/campaign.
 */
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

/** Activation requires the private TurnTransaction seal and is used only by canonical commit. */
internal fun <T> withCanonicalGameplayMutationForTurn(
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
