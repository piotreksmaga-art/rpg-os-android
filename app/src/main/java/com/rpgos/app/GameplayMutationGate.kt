package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

private data class ActiveGameplayMutation(val db: SQLiteDatabase, val campaignUid: String)
private val activeGameplayMutation = ThreadLocal<ActiveGameplayMutation?>()

internal object GameplayMutationDatabaseGuards {
    internal const val CONTEXT_TABLE_NAME = "rpgos_gameplay_mutation_context"
    private val authoritativeTables: List<String> get() = RuntimeTruthLayerRegistry.authoritativePersistentTables().toList()
    private val administrativeOnlyTables: List<String> get() = RuntimeTruthLayerRegistry.administrativeOnlyPersistentTables().toList()

    internal fun authoritativeTablesForCompatibility(): List<String> = authoritativeTables
    internal fun administrativeOnlyTablesForCompatibility(): List<String> = administrativeOnlyTables
    internal fun campaignColumnForCompatibility(db: SQLiteDatabase, table: String): String? = campaignColumn(db, table)

    fun ensureInstalled(db: SQLiteDatabase) {
        RuntimeTruthLayerRegistry.validateCanonicalInventory()
        db.execSQL("CREATE TABLE IF NOT EXISTS $CONTEXT_TABLE_NAME(campaign_uid TEXT PRIMARY KEY,capability_kind TEXT NOT NULL CHECK(capability_kind IN ('TURN','ADMIN')))")
        authoritativeTables.filter { tableExists(db, it) }.forEach { table ->
            val column = campaignColumn(db, table)
            createAuthorityGuard(db, table, column, "INSERT", "NEW")
            createAuthorityGuard(db, table, column, "UPDATE", "NEW")
            createAuthorityGuard(db, table, column, "DELETE", "OLD")
        }
        administrativeOnlyTables.filter { tableExists(db, it) }.forEach { table ->
            createAdministrativeOnlyGuard(db, table, "INSERT")
            createAdministrativeOnlyGuard(db, table, "UPDATE")
            createAdministrativeOnlyGuard(db, table, "DELETE")
        }
        installReceiptEvidenceGuards(db)
        installReplayEvidenceGuards(db)
    }

    private fun installReceiptEvidenceGuards(db: SQLiteDatabase) {
        if (!tableExists(db, "turn_transaction_receipts")) return
        db.execSQL("DROP TRIGGER IF EXISTS rpgos_turn_receipts_commit_insert")
        db.execSQL("DROP TRIGGER IF EXISTS rpgos_turn_receipts_no_update")
        db.execSQL("DROP TRIGGER IF EXISTS rpgos_turn_receipts_no_delete")
        val replayExists = tableExists(db, "canonical_turn_replay_payloads")
        val evidenceAlternative = if (replayExists) {
            """AND NOT EXISTS(
    SELECT 1 FROM canonical_turn_replay_payloads r
    WHERE r.transaction_uid=NEW.transaction_uid
      AND r.campaign_uid=NEW.campaign_uid
      AND r.turn_uid=NEW.turn_uid
      AND r.command_uid=NEW.command_uid
      AND r.commit_order=NEW.commit_order
      AND r.semantic_fingerprint=NEW.semantic_fingerprint
      AND r.required_event_count=NEW.required_event_count
      AND r.required_event_manifest_fingerprint=NEW.required_event_manifest_fingerprint
)"""
        } else ""
        db.execSQL("""CREATE TRIGGER rpgos_turn_receipts_commit_insert BEFORE INSERT ON turn_transaction_receipts
WHEN NOT EXISTS(
    SELECT 1 FROM $CONTEXT_TABLE_NAME
    WHERE campaign_uid=NEW.campaign_uid AND capability_kind='TURN'
) $evidenceAlternative
BEGIN SELECT RAISE(ABORT,'RPGOS-TURN-RECEIPT:COMMIT_EVIDENCE_REQUIRED'); END""".trimIndent())
        db.execSQL("CREATE TRIGGER rpgos_turn_receipts_no_update BEFORE UPDATE ON turn_transaction_receipts BEGIN SELECT RAISE(ABORT,'RPGOS-TURN-RECEIPT:APPEND_ONLY'); END")
        db.execSQL("CREATE TRIGGER rpgos_turn_receipts_no_delete BEFORE DELETE ON turn_transaction_receipts BEGIN SELECT RAISE(ABORT,'RPGOS-TURN-RECEIPT:APPEND_ONLY'); END")
    }

    private fun installReplayEvidenceGuards(db: SQLiteDatabase) {
        if (!tableExists(db, "canonical_turn_replay_payloads")) return
        db.execSQL("DROP TRIGGER IF EXISTS rpgos_replay_commit_insert")
        db.execSQL("DROP TRIGGER IF EXISTS rpgos_replay_no_update")
        db.execSQL("DROP TRIGGER IF EXISTS rpgos_replay_no_delete")
        db.execSQL("""CREATE TRIGGER rpgos_replay_commit_insert BEFORE INSERT ON canonical_turn_replay_payloads
WHEN NOT EXISTS(
    SELECT 1 FROM $CONTEXT_TABLE_NAME
    WHERE campaign_uid=NEW.campaign_uid AND capability_kind='TURN'
) AND (
    NEW.required_event_count <= 0 OR
    (SELECT COUNT(*) FROM canonical_gameplay_events e
      WHERE e.campaign_uid=NEW.campaign_uid AND e.transaction_uid=NEW.transaction_uid) <> NEW.required_event_count OR
    EXISTS(SELECT 1 FROM turn_transaction_receipts r WHERE r.transaction_uid=NEW.transaction_uid)
)
BEGIN SELECT RAISE(ABORT,'RPGOS-SNAPSHOT:REPLAY_COMMIT_EVIDENCE_REQUIRED'); END""".trimIndent())
        db.execSQL("CREATE TRIGGER rpgos_replay_no_update BEFORE UPDATE ON canonical_turn_replay_payloads BEGIN SELECT RAISE(ABORT,'RPGOS-SNAPSHOT:REPLAY_APPEND_ONLY'); END")
        db.execSQL("CREATE TRIGGER rpgos_replay_no_delete BEFORE DELETE ON canonical_turn_replay_payloads BEGIN SELECT RAISE(ABORT,'RPGOS-SNAPSHOT:REPLAY_APPEND_ONLY'); END")
    }

    fun isInstalled(db: SQLiteDatabase) = tableExists(db, CONTEXT_TABLE_NAME)

    fun isAdminActive(db: SQLiteDatabase, campaignUid: String): Boolean {
        if (!isInstalled(db)) return false
        return db.rawQuery(
            "SELECT 1 FROM $CONTEXT_TABLE_NAME WHERE campaign_uid=? AND capability_kind='ADMIN' LIMIT 1",
            arrayOf(campaignUid)
        ).use { it.moveToFirst() }
    }

    fun isTurnOrAdminActive(db: SQLiteDatabase, campaignUid: String): Boolean {
        if (!isInstalled(db)) return false
        return db.rawQuery(
            "SELECT 1 FROM $CONTEXT_TABLE_NAME WHERE campaign_uid=? AND capability_kind IN ('TURN','ADMIN') LIMIT 1",
            arrayOf(campaignUid)
        ).use { it.moveToFirst() }
    }

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

    private fun createAuthorityGuard(db: SQLiteDatabase, table: String, campaignColumn: String?, op: String, row: String) {
        val name = "rpgos_guard_${table}_${op.lowercase()}"
        val campaignPredicate = campaignColumn?.let { "campaign_uid=$row.$it AND " }.orEmpty()
        db.execSQL("DROP TRIGGER IF EXISTS $name")
        db.execSQL(
            """CREATE TRIGGER $name BEFORE $op ON $table
WHEN NOT EXISTS(SELECT 1 FROM $CONTEXT_TABLE_NAME WHERE ${campaignPredicate}capability_kind IN ('TURN','ADMIN'))
BEGIN SELECT RAISE(ABORT,'RPGOS-MUTATION-GATE:CANONICAL_TURN_TRANSACTION_REQUIRED'); END""".trimIndent()
        )
    }

    private fun createAdministrativeOnlyGuard(db: SQLiteDatabase, table: String, op: String) {
        val name = "rpgos_admin_guard_${table}_${op.lowercase()}"
        db.execSQL("DROP TRIGGER IF EXISTS $name")
        db.execSQL(
            """CREATE TRIGGER $name BEFORE $op ON $table
WHEN NOT EXISTS(SELECT 1 FROM $CONTEXT_TABLE_NAME WHERE capability_kind='ADMIN')
BEGIN SELECT RAISE(ABORT,'RPGOS-G32:MECHANICS_DEFINITION_REQUIRES_ADMIN'); END""".trimIndent()
        )
    }

    private fun campaignColumn(db: SQLiteDatabase, table: String): String? {
        val columns = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info($table)", null).use { c -> while (c.moveToNext()) columns += c.getString(1) }
        return when {
            "campaign_id" in columns -> "campaign_id"
            "campaign_uid" in columns -> "campaign_uid"
            else -> null
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

internal fun <T> withAdministrativeMutationAuthority(db: SQLiteDatabase, campaignUid: String, block: () -> T): T {
    requireAdministrativeRecoveryEntryPoint()
    require(GameplayMutationDatabaseGuards.isInstalled(db)) { "RPGOS-MUTATION-GATE:ADMIN_GUARDS_NOT_INSTALLED" }
    if (GameplayMutationDatabaseGuards.isAdminActive(db, campaignUid)) return block()
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
