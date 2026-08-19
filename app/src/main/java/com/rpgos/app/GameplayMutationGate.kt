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
        db.execSQL("DROP TABLE IF EXISTS ${CONTEXT_TABLE_NAME}_v32_rebuild")
        if (!contextSupportsCommitEvidence(db)) {
            db.execSQL("CREATE TABLE ${CONTEXT_TABLE_NAME}_v32_rebuild(campaign_uid TEXT PRIMARY KEY,capability_kind TEXT NOT NULL CHECK(capability_kind IN ('TURN','ADMIN','COMMIT_EVIDENCE')))")
            if (tableExists(db, CONTEXT_TABLE_NAME)) {
                db.execSQL("INSERT OR IGNORE INTO ${CONTEXT_TABLE_NAME}_v32_rebuild(campaign_uid,capability_kind) SELECT campaign_uid,capability_kind FROM $CONTEXT_TABLE_NAME WHERE capability_kind IN ('TURN','ADMIN')")
                db.execSQL("DROP TABLE $CONTEXT_TABLE_NAME")
            }
            db.execSQL("ALTER TABLE ${CONTEXT_TABLE_NAME}_v32_rebuild RENAME TO $CONTEXT_TABLE_NAME")
        } else {
            db.execSQL("CREATE TABLE IF NOT EXISTS $CONTEXT_TABLE_NAME(campaign_uid TEXT PRIMARY KEY,capability_kind TEXT NOT NULL CHECK(capability_kind IN ('TURN','ADMIN','COMMIT_EVIDENCE')))")
        }
        // Phase 35 guards are reinstalled here as well as at schema creation so already-current
        // databases receive post-audit protection without a Phase 36 schema-version change.
        Phase35CanonDivergenceSchema.ensureReady(db)
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
        db.execSQL("""CREATE TRIGGER rpgos_turn_receipts_commit_insert BEFORE INSERT ON turn_transaction_receipts
WHEN NOT EXISTS(
    SELECT 1 FROM $CONTEXT_TABLE_NAME
    WHERE campaign_uid=NEW.campaign_uid AND capability_kind='COMMIT_EVIDENCE'
)
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
    WHERE campaign_uid=NEW.campaign_uid AND capability_kind='COMMIT_EVIDENCE'
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

    fun isCommitEvidenceActive(db: SQLiteDatabase, campaignUid: String): Boolean {
        if (!isInstalled(db)) return false
        return db.rawQuery(
            "SELECT 1 FROM $CONTEXT_TABLE_NAME WHERE campaign_uid=? AND capability_kind='COMMIT_EVIDENCE' LIMIT 1",
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

    fun enterCommitEvidence(db: SQLiteDatabase, campaignUid: String) {
        require(db.inTransaction()) { "commit-evidence capability requires outer transaction" }
        enter(db, campaignUid, "COMMIT_EVIDENCE")
    }

    fun leaveCommitEvidence(db: SQLiteDatabase, campaignUid: String) {
        leave(db, campaignUid, "COMMIT_EVIDENCE")
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

    private fun contextSupportsCommitEvidence(db: SQLiteDatabase): Boolean {
        if (!tableExists(db, CONTEXT_TABLE_NAME)) return false
        return db.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name=?", arrayOf(CONTEXT_TABLE_NAME)).use { c ->
            c.moveToFirst() && !c.isNull(0) && c.getString(0).contains("COMMIT_EVIDENCE")
        }
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

internal fun requireCanonicalCommitEvidence(db: SQLiteDatabase, campaignUid: String) {
    if (!GameplayMutationDatabaseGuards.isInstalled(db)) return
    require(GameplayMutationDatabaseGuards.isCommitEvidenceActive(db, campaignUid)) {
        "RPGOS-TURN-RECEIPT:COMMIT_EVIDENCE_REQUIRED"
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
    CanonDivergenceTurnBuffer.begin(db, campaignUid)
    return try {
        val result = block()
        CanonDivergenceTurnBuffer.flush(db, campaignUid)
        result
    } finally {
        CanonDivergenceTurnBuffer.clear()
        activeGameplayMutation.set(previous)
        GameplayMutationDatabaseGuards.leaveTurn(db, campaignUid)
    }
}

internal fun <T> withCanonicalCommitEvidenceForTurn(
    db: SQLiteDatabase,
    campaignUid: String,
    canonicalSeal: Any,
    block: () -> T
): T {
    require(TurnTransactionBoundary.acceptsCanonicalSeal(canonicalSeal)) { "RPGOS-MUTATION-GATE:INVALID_COMMIT_EVIDENCE_CAPABILITY" }
    require(db.inTransaction()) { "RPGOS-MUTATION-GATE:COMMIT_EVIDENCE_REQUIRES_TRANSACTION" }
    require(activeGameplayMutation.get() == null) { "RPGOS-MUTATION-GATE:COMMIT_EVIDENCE_MUST_FOLLOW_DOMAIN_WRITES" }
    GameplayMutationDatabaseGuards.enterCommitEvidence(db, campaignUid)
    return try { block() } finally { GameplayMutationDatabaseGuards.leaveCommitEvidence(db, campaignUid) }
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
