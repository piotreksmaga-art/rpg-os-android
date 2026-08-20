package com.rpgos.app

import android.os.Build
import android.database.sqlite.SQLiteDatabase
import java.util.function.UnaryOperator

private data class ActiveGameplayMutation(val db: SQLiteDatabase, val campaignUid: String)
private val activeGameplayMutation = ThreadLocal<ActiveGameplayMutation?>()

internal object GameplayMutationDatabaseGuards {
    internal const val CONTEXT_TABLE_NAME = "rpgos_gameplay_mutation_context"
    internal const val RUNTIME_TURN_FUNCTION = "rpgos_runtime_turn_authority"
    internal const val CANON_DIVERGENCE_RUNTIME_TURN_GUARD = "rpgos_canon_divergence_runtime_turn_insert"
    internal const val P37_RECORDED_WRITE_FUNCTION = "rpgos_p37_recorded_write_authority"
    private const val P37_GUARD_PREFIX = "rpgos_p37_recorded_"
    private val authoritativeTables: List<String> get() = RuntimeTruthLayerRegistry.authoritativePersistentTables().toList()
    private val administrativeOnlyTables: List<String> get() = RuntimeTruthLayerRegistry.administrativeOnlyPersistentTables().toList()

    internal fun authoritativeTablesForCompatibility(): List<String> = authoritativeTables
    internal fun administrativeOnlyTablesForCompatibility(): List<String> = administrativeOnlyTables
    internal fun campaignColumnForCompatibility(db: SQLiteDatabase, table: String): String? = campaignColumn(db, table)
    internal fun phase37RuntimeGuardNames(): Set<String> = buildSet {
        listOf(
            Phase37KnowledgeSchema.CLAIMS to "insert",
            Phase37KnowledgeSchema.ACQUISITIONS to "insert",
            Phase37KnowledgeSchema.EVIDENCE to "insert",
            Phase37KnowledgeSchema.STATES to "insert",
            Phase37KnowledgeSchema.STATES to "update"
        ).forEach { (table, operation) ->
            add(p37GuardName(table, operation))
            add(p37SealGuardName(table, operation))
        }
    }

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
        check(Phase37KnowledgeSchema.isReady(db)) { "RPGOS-P37:KNOWLEDGE_SCHEMA_NOT_READY" }
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
        installRuntimeTurnAuthorityGuards(db)
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

    /**
     * Persistent TURN/writer rows remain defense-in-depth and can be manufactured by a raw SQL
     * caller. The decisive Phase 35 RECORDED-divergence guard therefore consults connection/runtime
     * state that SQL cannot create. Upstream Event Store semantics are intentionally unchanged:
     * even a forged SQL Event is insufficient to authorize a durable RECORDED divergence.
     *
     * API 30+ uses a connection-local scalar function backed by the exact SQLiteDatabase +
     * ThreadLocal canonical turn capability. API 28-29 installs a default-deny divergence trigger;
     * that trigger is suspended only while a sealed canonical turn owns the outer transaction.
     */
    private fun installRuntimeTurnAuthorityGuards(db: SQLiteDatabase) {
        if (Build.VERSION.SDK_INT >= 30) {
            db.setCustomScalarFunction(RUNTIME_TURN_FUNCTION, UnaryOperator { campaignUid ->
                if (isCanonicalGameplayMutationActive(db, campaignUid)) "1" else "0"
            })
            db.setCustomScalarFunction(P37_RECORDED_WRITE_FUNCTION, UnaryOperator { token ->
                if (KnowledgeRecordedWriteAuthority.isAuthorized(db, token)) "1" else "0"
            })
        }
        installRuntimeTurnAuthorityTrigger(
            db,
            CANON_DIVERGENCE_RUNTIME_TURN_GUARD,
            Phase35CanonDivergenceSchema.TABLE,
            "NEW.provenance_status='RECORDED'"
        )
        installPhase37RecordedWriteAuthorityGuards(db)
    }

    private fun installRuntimeTurnAuthorityTrigger(
        db: SQLiteDatabase,
        triggerName: String,
        table: String,
        extraWhen: String?
    ) {
        if (!tableExists(db, table)) return
        db.execSQL("DROP TRIGGER IF EXISTS $triggerName")
        val authorityMissing = if (Build.VERSION.SDK_INT >= 30) {
            "$RUNTIME_TURN_FUNCTION(NEW.campaign_uid)<>'1'"
        } else {
            "1=1"
        }
        val whenClause = listOfNotNull(extraWhen, authorityMissing).joinToString(" AND ")
        db.execSQL(
            """CREATE TRIGGER $triggerName BEFORE INSERT ON $table
WHEN $whenClause
BEGIN SELECT RAISE(ABORT,'RPGOS-MUTATION-GATE:IN_MEMORY_TURN_AUTHORITY_REQUIRED'); END""".trimIndent()
        )
    }

    private fun suspendLegacyRuntimeTurnAuthorityGuards(db: SQLiteDatabase) {
        if (Build.VERSION.SDK_INT >= 30) return
        requireCanonicalGameplayMutation(db, activeGameplayMutation.get()?.campaignUid ?: error("RPGOS-MUTATION-GATE:NO_ACTIVE_TURN"))
        db.execSQL("DROP TRIGGER IF EXISTS $CANON_DIVERGENCE_RUNTIME_TURN_GUARD")
    }

    private fun restoreLegacyRuntimeTurnAuthorityGuards(db: SQLiteDatabase) {
        if (Build.VERSION.SDK_INT >= 30) return
        installRuntimeTurnAuthorityTrigger(
            db,
            CANON_DIVERGENCE_RUNTIME_TURN_GUARD,
            Phase35CanonDivergenceSchema.TABLE,
            "NEW.provenance_status='RECORDED'"
        )
    }

    private fun installPhase37RecordedWriteAuthorityGuards(db: SQLiteDatabase) {
        installPhase37RecordedWriteGuard(
            db, Phase37KnowledgeSchema.CLAIMS, "INSERT", "NEW",
            "'CLAIM:'||hex(NEW.campaign_uid)||':'||hex(NEW.claim_uid)||':'||hex(NEW.subject_kind_uid)||':'||hex(NEW.subject_uid)||':'||hex(NEW.predicate_uid)||':'||hex(NEW.value_canonical)||':'||hex(NEW.domain_uid)"
        )
        installPhase37RecordedWriteGuard(
            db, Phase37KnowledgeSchema.ACQUISITIONS, "INSERT", "NEW",
            "'ACQ:'||hex(NEW.campaign_uid)||':'||hex(NEW.acquisition_uid)||':'||hex(NEW.claim_uid)||':'||hex(NEW.holder_kind_uid)||':'||hex(NEW.holder_uid)||':'||hex(COALESCE(NEW.created_event_uid,''))||':'||hex(NEW.provenance_status)"
        )
        installPhase37RecordedWriteGuard(
            db, Phase37KnowledgeSchema.EVIDENCE, "INSERT", "NEW",
            "'EVID:'||hex(NEW.campaign_uid)||':'||hex(NEW.evidence_uid)||':'||hex(NEW.acquisition_uid)||':'||hex(NEW.claim_uid)||':'||hex(NEW.evidence_kind_uid)||':'||hex(NEW.polarity_uid)||':'||hex(COALESCE(NEW.source_event_uid,''))||':'||hex(COALESCE(NEW.source_acquisition_uid,''))"
        )
        val stateToken = "'STATE:'||hex(NEW.campaign_uid)||':'||hex(NEW.state_uid)||':'||hex(NEW.holder_kind_uid)||':'||hex(NEW.holder_uid)||':'||hex(NEW.claim_uid)||':'||hex(NEW.scope_uid)||':'||hex(NEW.role_uid)||':'||hex(NEW.epistemic_state_uid)||':'||hex(NEW.latest_acquisition_uid)"
        installPhase37RecordedWriteGuard(db, Phase37KnowledgeSchema.STATES, "INSERT", "NEW", stateToken)
        installPhase37RecordedWriteGuard(db, Phase37KnowledgeSchema.STATES, "UPDATE", "NEW", stateToken)
    }

    private fun installPhase37RecordedWriteGuard(
        db: SQLiteDatabase,
        table: String,
        operation: String,
        row: String,
        tokenExpression: String
    ) {
        if (!tableExists(db, table)) return
        val name = p37GuardName(table, operation.lowercase())
        db.execSQL("DROP TRIGGER IF EXISTS $name")
        val missing = if (Build.VERSION.SDK_INT >= 30) "$P37_RECORDED_WRITE_FUNCTION($tokenExpression)<>'1'" else "1=1"
        val sealName = p37SealGuardName(table, operation.lowercase())
        db.execSQL("DROP TRIGGER IF EXISTS $sealName")
        listOf(name, sealName).forEach { triggerName ->
            db.execSQL(
                """CREATE TRIGGER $triggerName BEFORE $operation ON $table
WHEN $missing
BEGIN SELECT RAISE(ABORT,'RPGOS-KNOWLEDGE:EXACT_RECORDED_AUTHORITY_REQUIRED'); END""".trimIndent()
            )
        }
    }

    internal fun suspendLegacyPhase37RecordedWriteGuards(db: SQLiteDatabase) {
        if (Build.VERSION.SDK_INT >= 30) return
        val campaignUid = activeGameplayMutation.get()?.campaignUid ?: error("RPGOS-MUTATION-GATE:NO_ACTIVE_TURN")
        requireCanonicalGameplayMutation(db, campaignUid)
        phase37RuntimeGuardNames().forEach { db.execSQL("DROP TRIGGER IF EXISTS $it") }
    }

    internal fun restoreLegacyPhase37RecordedWriteGuards(db: SQLiteDatabase) {
        if (Build.VERSION.SDK_INT >= 30) return
        installPhase37RecordedWriteAuthorityGuards(db)
    }

    private fun p37GuardName(table: String, operation: String) =
        P37_GUARD_PREFIX + table.removePrefix("world_actor_") + "_" + operation

    private fun p37SealGuardName(table: String, operation: String) =
        "rpgos_p37_schema_seal_" + table.removePrefix("world_actor_") + "_" + operation

    internal fun enterRuntimeTurnAuthority(db: SQLiteDatabase, campaignUid: String) {
        requireCanonicalGameplayMutation(db, campaignUid)
        suspendLegacyRuntimeTurnAuthorityGuards(db)
    }

    internal fun leaveRuntimeTurnAuthority(db: SQLiteDatabase) {
        restoreLegacyRuntimeTurnAuthorityGuards(db)
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
    var bufferStarted = false
    var knowledgeBufferStarted = false
    var runtimeAuthorityEntered = false
    return try {
        CanonDivergenceTurnBuffer.begin(db, campaignUid)
        bufferStarted = true
        KnowledgeTurnBuffer.begin(db, campaignUid)
        knowledgeBufferStarted = true
        GameplayMutationDatabaseGuards.enterRuntimeTurnAuthority(db, campaignUid)
        runtimeAuthorityEntered = true
        val result = block()
        CanonDivergenceTurnBuffer.flush(db, campaignUid)
        KnowledgeTurnBuffer.flush(db, campaignUid)
        result
    } finally {
        if (runtimeAuthorityEntered) GameplayMutationDatabaseGuards.leaveRuntimeTurnAuthority(db)
        if (knowledgeBufferStarted) KnowledgeTurnBuffer.clear()
        if (bufferStarted) CanonDivergenceTurnBuffer.clear()
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
