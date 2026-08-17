package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

private data class ActiveGameplayMutation(val db: SQLiteDatabase, val campaignUid: String)
private val activeGameplayMutation = ThreadLocal<ActiveGameplayMutation?>()

internal object GameplayMutationDatabaseGuards {
    private const val CONTEXT_TABLE = "rpgos_gameplay_mutation_context"

    /** Existing domain authorities remain the owners of these tables. */
    private val authoritativeTables = listOf(
        "player_inventory_stacks",
        "player_inventory_unique",
        "item_instances",
        "financial_ledger_transactions",
        "ownership_records",
        "ownership_operations",
        "campaign_truth_records",
        "player_stats",
        "player_resources",
        "player_skills_v2",
        "player_techniques_v2",
        "player_equipment",
        "player_equipment_slots",
        "development_projects",
        "project_status_history",
        "project_requirements",
        "project_requirement_satisfactions",
        "project_milestone_definitions",
        "project_milestone_achievements",
        "project_work_records",
        "project_dependencies",
        "project_outcomes"
    )

    fun ensureInstalled(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $CONTEXT_TABLE(" +
                "campaign_uid TEXT PRIMARY KEY," +
                "capability_kind TEXT NOT NULL CHECK(capability_kind IN ('TURN','ADMIN')))"
        )
        authoritativeTables.filter { tableExists(db, it) }.forEach { table ->
            val campaignColumn = campaignColumn(db, table)
            createGuard(db, table, campaignColumn, "INSERT", "NEW")
            createGuard(db, table, campaignColumn, "UPDATE", "NEW")
            createGuard(db, table, campaignColumn, "DELETE", "OLD")
        }
    }

    fun isInstalled(db: SQLiteDatabase): Boolean = tableExists(db, CONTEXT_TABLE)

    fun enterTurn(db: SQLiteDatabase, campaignUid: String) {
        require(db.inTransaction()) { "gameplay capability requires outer transaction" }
        db.execSQL(
            "INSERT INTO $CONTEXT_TABLE(campaign_uid,capability_kind) VALUES(?,'TURN')",
            arrayOf(campaignUid)
        )
    }

    fun leaveTurn(db: SQLiteDatabase, campaignUid: String) {
        db.delete(CONTEXT_TABLE, "campaign_uid=? AND capability_kind='TURN'", arrayOf(campaignUid))
    }

    private fun createGuard(
        db: SQLiteDatabase,
        table: String,
        campaignColumn: String,
        op: String,
        row: String
    ) {
        val name = "rpgos_guard_${table}_${op.lowercase()}"
        db.execSQL(
            """CREATE TRIGGER IF NOT EXISTS $name BEFORE $op ON $table
               WHEN NOT EXISTS(
                   SELECT 1 FROM $CONTEXT_TABLE
                   WHERE campaign_uid=$row.$campaignColumn AND capability_kind IN ('TURN','ADMIN')
               )
               BEGIN
                   SELECT RAISE(ABORT,'RPGOS-MUTATION-GATE:CANONICAL_TURN_TRANSACTION_REQUIRED');
               END""".trimIndent()
        )
    }

    private fun campaignColumn(db: SQLiteDatabase, table: String): String {
        val columns = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            while (cursor.moveToNext()) columns += cursor.getString(1)
        }
        return when {
            "campaign_id" in columns -> "campaign_id"
            "campaign_uid" in columns -> "campaign_uid"
            else -> error("RPGOS-MUTATION-GATE:AUTHORITATIVE_TABLE_WITHOUT_CAMPAIGN_SCOPE:$table")
        }
    }

    private fun tableExists(db: SQLiteDatabase, name: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(name)
        ).use { it.moveToFirst() }
}

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
    GameplayMutationDatabaseGuards.enterTurn(db, campaignUid)
    activeGameplayMutation.set(ActiveGameplayMutation(db, campaignUid))
    return try {
        block()
    } finally {
        activeGameplayMutation.set(previous)
        GameplayMutationDatabaseGuards.leaveTurn(db, campaignUid)
    }
}
