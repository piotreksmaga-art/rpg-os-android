package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

private data class ActiveGameplayMutation(val db: SQLiteDatabase, val campaignUid: String)
private val activeGameplayMutation = ThreadLocal<ActiveGameplayMutation?>()

internal object GameplayMutationDatabaseGuards {
    internal const val CONTEXT_TABLE_NAME = "rpgos_gameplay_mutation_context"
    private val authoritativeTables: List<String> get() = RuntimeTruthLayerRegistry.authoritativePersistentTables().toList()
    internal fun authoritativeTablesForCompatibility(): List<String> = authoritativeTables
    internal fun campaignColumnForCompatibility(db: SQLiteDatabase, table: String): String = campaignColumn(db, table)

    fun ensureInstalled(db: SQLiteDatabase) {
        RuntimeTruthLayerRegistry.validateCanonicalInventory()
        db.execSQL("CREATE TABLE IF NOT EXISTS $CONTEXT_TABLE_NAME(campaign_uid TEXT PRIMARY KEY,capability_kind TEXT NOT NULL CHECK(capability_kind IN ('TURN','ADMIN')))")
        authoritativeTables.filter { tableExists(db, it) }.forEach { table ->
            val column = campaignColumn(db, table)
            createGuard(db, table, column, "INSERT", "NEW")
            createGuard(db, table, column, "UPDATE", "NEW")
            createGuard(db, table, column, "DELETE", "OLD")
        }
        if (tableExists(db, "turn_transaction_receipts")) {
            db.execSQL("CREATE TRIGGER IF NOT EXISTS rpgos_turn_receipts_no_update BEFORE UPDATE ON turn_transaction_receipts BEGIN SELECT RAISE(ABORT,'RPGOS-TURN-RECEIPT:APPEND_ONLY'); END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS rpgos_turn_receipts_no_delete BEFORE DELETE ON turn_transaction_receipts BEGIN SELECT RAISE(ABORT,'RPGOS-TURN-RECEIPT:APPEND_ONLY'); END")
        }
    }

    fun isInstalled(db: SQLiteDatabase) = tableExists(db, CONTEXT_TABLE_NAME)

    fun enterTurn(db: SQLiteDatabase, campaignUid: String) {
        require(db.inTransaction()) { "gameplay capability requires outer transaction" }
        enter(db, campaignUid, "TURN")
        try {
            CampaignIntelligencePhase30Schema.enterWriter(db, campaignUid)
        } catch (f: Throwable) {
            leave(db, campaignUid, "TURN")
            throw f
        }
    }

    fun leaveTurn(db: SQLiteDatabase, campaignUid: String) {
        CampaignIntelligencePhase30Schema.leaveWriter(db, campaignUid)
        leave(db, campaignUid, "TURN")
    }

    fun enterAdmin(db: SQLiteDatabase, campaignUid: String) {
        require(db.inTransaction()) { "administrative capability requires outer transaction" }
        enter(db, campaignUid, "ADMIN")
        try {
            CampaignIntelligencePhase30Schema.enterWriter(db, campaignUid)
        } catch (f: Throwable) {
            leave(db, campaignUid, "ADMIN")
            throw f
        }
    }

    fun leaveAdmin(db: SQLiteDatabase, campaignUid: String) {
        CampaignIntelligencePhase30Schema.leaveWriter(db, campaignUid)
        leave(db, campaignUid, "ADMIN")
    }

    private fun enter(db: SQLiteDatabase, campaignUid: String, kind: String) {
        db.execSQL("INSERT INTO $CONTEXT_TABLE_NAME(campaign_uid,capability_kind) VALUES(?,?)", arrayOf(campaignUid, kind))
    }

    private fun leave(db: SQLiteDatabase, campaignUid: String, kind: String) {
        db.delete(CONTEXT_TABLE_NAME, "campaign_uid=? AND capability_kind=?", arrayOf(campaignUid, kind))
    }

    private fun createGuard(db: SQLiteDatabase, table: String, campaignColumn: String, op: String, row: String) {
        val name = "rpgos_guard_${table}_${op.lowercase()}"
        db.execSQL(
            """CREATE TRIGGER IF NOT EXISTS $name BEFORE $op ON $table
WHEN NOT EXISTS(SELECT 1 FROM $CONTEXT_TABLE_NAME WHERE campaign_uid=$row.$campaignColumn AND capability_kind IN ('TURN','ADMIN'))
BEGIN SELECT RAISE(ABORT,'RPGOS-MUTATION-GATE:CANONICAL_TURN_TRANSACTION_REQUIRED'); END""".trimIndent()
        )
    }

    private fun campaignColumn(db: SQLiteDatabase, table: String): String {
        val columns = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info($table)", null).use { c -> while (c.moveToNext()) columns += c.getString(1) }
        return when {
            "campaign_id" in columns -> "campaign_id"
            "campaign_uid" in columns -> "campaign_uid"
            else -> error("RPGOS-MUTATION-GATE:AUTHORITATIVE_TABLE_WITHOUT_CAMPAIGN_SCOPE:$table")
        }
    }

    private fun tableExists(db: SQLiteDatabase, name: String) =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1", arrayOf(name)).use { it.moveToFirst() }
}

internal fun requireCanonicalGameplayMutation(db: SQLiteDatabase, campaignUid: String) {
    if (!TurnTransactionReceiptSchema.isReady(db)) return
    val active = activeGameplayMutation.get()
    require(active != null && active.db === db && active.campaignUid == campaignUid) {
        "RPGOS-MUTATION-GATE:CANONICAL_TURN_TRANSACTION_REQUIRED"
    }
}

internal fun isCanonicalGameplayMutationActive(db: SQLiteDatabase, campaignUid: String): Boolean {
    val a = activeGameplayMutation.get()
    return a != null && a.db === db && a.campaignUid == campaignUid
}

/** File-level restore/repair entry points have no SQLite handle, but must still never nest under gameplay. */
internal fun requireAdministrativeRecoveryEntryPoint() {
    require(activeGameplayMutation.get() == null) { "RPGOS-G32:GAMEPLAY_CANNOT_INVOKE_ADMIN_AUTHORITY" }
}

internal fun <T> withCanonicalGameplayMutationForTurn(
    db: SQLiteDatabase,
    campaignUid: String,
    canonicalSeal: Any,
    block: () -> T
): T {
    require(TurnTransactionBoundary.acceptsCanonicalSeal(canonicalSeal)) { "RPGOS-MUTATION-GATE:INVALID_TURN_CAPABILITY" }
    RuntimeTruthLayerRegistry.requireGameplayCapability(RuntimeMutationCapability.CANONICAL_TURN)
    val previous = activeGameplayMutation.get()
    require(previous == null) { "RPGOS-MUTATION-GATE:NESTED_GAMEPLAY_CAPABILITY" }
    GameplayMutationDatabaseGuards.enterTurn(db, campaignUid)
    activeGameplayMutation.set(ActiveGameplayMutation(db, campaignUid))
    return try {
        block()
    } finally {
        activeGameplayMutation.set(previous)
        GameplayMutationDatabaseGuards.leaveTurn(db, campaignUid)
    }
}

/** Explicit non-gameplay authority for migration/install/recovery infrastructure. */
internal fun <T> withAdministrativeMutationAuthority(db: SQLiteDatabase, campaignUid: String, block: () -> T): T {
    requireAdministrativeRecoveryEntryPoint()
    require(GameplayMutationDatabaseGuards.isInstalled(db)) { "RPGOS-MUTATION-GATE:ADMIN_GUARDS_NOT_INSTALLED" }
    val owns = !db.inTransaction()
    if (owns) db.beginTransaction()
    GameplayMutationDatabaseGuards.enterAdmin(db, campaignUid)
    return try {
        val result = block()
        GameplayMutationDatabaseGuards.leaveAdmin(db, campaignUid)
        if (owns) db.setTransactionSuccessful()
        result
    } finally {
        runCatching { GameplayMutationDatabaseGuards.leaveAdmin(db, campaignUid) }
        if (owns && db.inTransaction()) db.endTransaction()
    }
}
