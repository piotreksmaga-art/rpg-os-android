package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest

const val TURN_TRANSACTION_RECEIPT_VERSION = 3

data class TurnCommitReceipt(
    val campaignUid: String,
    val turnUid: String,
    val commandUid: String,
    val transactionUid: String,
    val semanticFingerprint: String,
    val resultFingerprint: String,
    val commitOrder: Long?,
    val requiredEventCount: Int? = null,
    val requiredEventManifestFingerprint: String? = null,
    val receiptVersion: Int = TURN_TRANSACTION_RECEIPT_VERSION
)

enum class TurnRecoveryState { NOT_RECORDED, COMMITTED }
data class TurnRecoveryStatus(val state: TurnRecoveryState, val receipt: TurnCommitReceipt? = null)

sealed interface TurnExecutionResult<out T> {
    data class Committed<T>(val value: T, val receipt: TurnCommitReceipt) : TurnExecutionResult<T>
    /** Identifies the original canonical commit; its transaction UID may differ from a later retry attempt. */
    data class AlreadyCommitted(val receipt: TurnCommitReceipt) : TurnExecutionResult<Nothing>
}

class TurnIdempotencyConflictException(val code: String) : IllegalStateException(code)

/** Explicit schema/migration boundary. Readers never call this object. */
internal object TurnTransactionReceiptSchema {
    const val MIGRATION_V28 = "RPGOS-28.0-TURN-IDEMPOTENCY"
    const val MIGRATION_V29 = "RPGOS-29.0-CRASH-RECOVERY"
    const val MIGRATION_V32_EVENT_MANIFEST = "RPGOS-32.1-RECEIPT-EVENT-MANIFEST"

    fun isReady(db: SQLiteDatabase): Boolean = tableExists(db, "turn_transaction_receipts") &&
        hasColumn(db, "turn_transaction_receipts", "commit_order") &&
        hasColumn(db, "turn_transaction_receipts", "required_event_count") &&
        hasColumn(db, "turn_transaction_receipts", "required_event_manifest_fingerprint")

    fun ensureReady(db: SQLiteDatabase) {
        val ownsTx = !db.inTransaction()
        if (ownsTx) db.beginTransaction()
        try {
            db.execSQL("""CREATE TABLE IF NOT EXISTS rpgos_schema_migrations(
                migration_id TEXT PRIMARY KEY, applied_at INTEGER NOT NULL, notes TEXT)""")
            when {
                !tableExists(db, "turn_transaction_receipts") -> createCurrentTable(db, "turn_transaction_receipts")
                !isReady(db) || hasLegacyReceiptVersionCheck(db) -> migrateReceiptTable(db)
            }
            createIndexes(db)
            db.execSQL("INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes) VALUES(?,strftime('%s','now'),?)",
                arrayOf(MIGRATION_V28, "Append-only committed turn receipts; prospective only"))
            db.execSQL("INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes) VALUES(?,strftime('%s','now'),?)",
                arrayOf(MIGRATION_V29, "Adds nullable prospective commit_order; existing V1 receipt order remains UNKNOWN"))
            db.execSQL("INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes) VALUES(?,strftime('%s','now'),?)",
                arrayOf(MIGRATION_V32_EVENT_MANIFEST, "V3 receipts bind deterministic required Event count/hash; V1/V2 remain NULL/UNKNOWN without fabricated history"))
            if (ownsTx) db.setTransactionSuccessful()
        } finally { if (ownsTx) db.endTransaction() }
    }

    private fun migrateReceiptTable(db: SQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS turn_transaction_receipts_v32_new")
        createCurrentTable(db, "turn_transaction_receipts_v32_new")
        val hasOrder = hasColumn(db, "turn_transaction_receipts", "commit_order")
        val hasCount = hasColumn(db, "turn_transaction_receipts", "required_event_count")
        val hasHash = hasColumn(db, "turn_transaction_receipts", "required_event_manifest_fingerprint")
        val orderExpr = if (hasOrder) "commit_order" else "NULL"
        val countExpr = if (hasCount) "required_event_count" else "NULL"
        val hashExpr = if (hasHash) "required_event_manifest_fingerprint" else "NULL"
        db.execSQL("""INSERT INTO turn_transaction_receipts_v32_new(
            transaction_uid,campaign_uid,turn_uid,command_uid,semantic_fingerprint,result_fingerprint,commit_order,
            required_event_count,required_event_manifest_fingerprint,receipt_version,commit_state)
            SELECT transaction_uid,campaign_uid,turn_uid,command_uid,semantic_fingerprint,result_fingerprint,$orderExpr,
            $countExpr,$hashExpr,receipt_version,commit_state FROM turn_transaction_receipts""".trimIndent())
        db.execSQL("DROP TABLE turn_transaction_receipts")
        db.execSQL("ALTER TABLE turn_transaction_receipts_v32_new RENAME TO turn_transaction_receipts")
    }

    private fun createCurrentTable(db: SQLiteDatabase, table: String) {
        db.execSQL("""CREATE TABLE $table(
            transaction_uid TEXT PRIMARY KEY,
            campaign_uid TEXT NOT NULL,
            turn_uid TEXT NOT NULL,
            command_uid TEXT NOT NULL,
            semantic_fingerprint TEXT NOT NULL,
            result_fingerprint TEXT NOT NULL,
            commit_order INTEGER NULL CHECK(commit_order IS NULL OR commit_order > 0),
            required_event_count INTEGER NULL CHECK(required_event_count IS NULL OR required_event_count >= 0),
            required_event_manifest_fingerprint TEXT NULL,
            receipt_version INTEGER NOT NULL CHECK(receipt_version IN (1,2,3)),
            commit_state TEXT NOT NULL CHECK(commit_state='COMMITTED'),
            UNIQUE(campaign_uid,command_uid),
            CHECK(receipt_version < 3 OR (commit_order IS NOT NULL AND required_event_count IS NOT NULL AND required_event_manifest_fingerprint IS NOT NULL)))""")
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_turn_receipts_campaign_order ON turn_transaction_receipts(campaign_uid,commit_order) WHERE commit_order IS NOT NULL")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_turn_receipts_campaign ON turn_transaction_receipts(campaign_uid,transaction_uid)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_turn_receipts_command ON turn_transaction_receipts(campaign_uid,command_uid)")
    }

    private fun hasLegacyReceiptVersionCheck(db: SQLiteDatabase): Boolean = db.rawQuery(
        "SELECT sql FROM sqlite_master WHERE type='table' AND name='turn_transaction_receipts'", null
    ).use { c ->
        if (!c.moveToFirst() || c.isNull(0)) false else {
            val sql = c.getString(0).replace(" ", "").lowercase()
            !sql.contains("receipt_versionin(1,2,3)")
        }
    }

    private fun tableExists(db: SQLiteDatabase, name: String): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1", arrayOf(name)
    ).use { it.moveToFirst() }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean = db.rawQuery(
        "PRAGMA table_info($table)", null
    ).use { c ->
        val idx = c.getColumnIndex("name")
        while (c.moveToNext()) if (c.getString(idx) == column) return@use true
        false
    }
}

/** APPEND-ONLY COMMIT EVIDENCE. Construction performs no DDL. */
internal class TurnTransactionReceiptStore(private val db: SQLiteDatabase) {
    fun replay(identity: TurnTransactionIdentity, semanticFingerprint: String): TurnCommitReceipt? {
        require(semanticFingerprint.isNotBlank())
        if (!TurnTransactionReceiptSchema.isReady(db)) return null
        byTransaction(identity.transactionUid)?.let { existing ->
            if (existing.campaignUid != identity.campaignUid) conflict(CROSS_CAMPAIGN_TRANSACTION_UID)
            if (existing.commandUid != identity.commandUid || existing.turnUid != identity.turnUid) conflict(TRANSACTION_IDENTITY_MISMATCH)
            if (existing.semanticFingerprint != semanticFingerprint) conflict(SEMANTIC_FINGERPRINT_MISMATCH)
            return existing
        }
        byCommand(identity.campaignUid, identity.commandUid)?.let { existing ->
            if (existing.semanticFingerprint != semanticFingerprint) conflict(COMMAND_SEMANTIC_FINGERPRINT_MISMATCH)
            return existing
        }
        return null
    }

    fun lastValidCommit(campaignUid: String): TurnCommitReceipt? {
        require(campaignUid.isNotBlank())
        if (!TurnTransactionReceiptSchema.isReady(db)) return null
        return query("""SELECT campaign_uid,turn_uid,command_uid,transaction_uid,semantic_fingerprint,result_fingerprint,commit_order,
            required_event_count,required_event_manifest_fingerprint,receipt_version
            FROM turn_transaction_receipts WHERE campaign_uid=? AND commit_state='COMMITTED' AND commit_order IS NOT NULL
            ORDER BY commit_order DESC LIMIT 1""", arrayOf(campaignUid))
    }

    fun committedTransaction(transactionUid: String): TurnCommitReceipt? = if (!TurnTransactionReceiptSchema.isReady(db)) null else byTransaction(transactionUid)
    fun committedCommand(campaignUid: String, commandUid: String): TurnCommitReceipt? = if (!TurnTransactionReceiptSchema.isReady(db)) null else byCommand(campaignUid, commandUid)

    /**
     * Reserve the single Phase29 transaction order inside the active outer SQLite transaction.
     * No row is written here; rollback therefore consumes no durable order.
     */
    fun reserveNextCommitOrder(campaignUid: String): Long {
        require(db.inTransaction()) { "commit order reservation must join active outer transaction" }
        require(campaignUid.isNotBlank())
        return db.rawQuery(
            "SELECT COALESCE(MAX(commit_order),0)+1 FROM turn_transaction_receipts WHERE campaign_uid=? AND commit_state='COMMITTED' AND commit_order IS NOT NULL",
            arrayOf(campaignUid)
        ).use { c -> c.moveToFirst(); c.getLong(0) }
    }

    fun appendCommitted(
        identity: TurnTransactionIdentity,
        semanticFingerprint: String,
        commitOrder: Long,
        eventManifest: RequiredEventManifestSummary
    ): TurnCommitReceipt {
        require(db.inTransaction()) { "turn receipt must join active outer transaction" }
        require(TurnTransactionReceiptSchema.isReady(db)) { "turn receipt schema must be initialized before commit" }
        require(commitOrder > 0L)
        replay(identity, semanticFingerprint)?.let { existing ->
            if (existing.receiptVersion >= 3 && (
                    existing.commitOrder != commitOrder ||
                    existing.requiredEventCount != eventManifest.requiredEventCount ||
                    existing.requiredEventManifestFingerprint != eventManifest.orderedManifestFingerprint
                )) conflict(EVENT_MANIFEST_BINDING_MISMATCH)
            return existing
        }
        val receipt = TurnCommitReceipt(
            identity.campaignUid, identity.turnUid, identity.commandUid, identity.transactionUid,
            semanticFingerprint,
            receiptFingerprint(identity, semanticFingerprint, commitOrder, eventManifest),
            commitOrder,
            eventManifest.requiredEventCount,
            eventManifest.orderedManifestFingerprint,
            TURN_TRANSACTION_RECEIPT_VERSION
        )
        db.execSQL("""INSERT INTO turn_transaction_receipts(
            transaction_uid,campaign_uid,turn_uid,command_uid,semantic_fingerprint,result_fingerprint,commit_order,
            required_event_count,required_event_manifest_fingerprint,receipt_version,commit_state)
            VALUES(?,?,?,?,?,?,?,?,?,?,'COMMITTED')""",
            arrayOf(receipt.transactionUid,receipt.campaignUid,receipt.turnUid,receipt.commandUid,receipt.semanticFingerprint,
                receipt.resultFingerprint,receipt.commitOrder,receipt.requiredEventCount,receipt.requiredEventManifestFingerprint,receipt.receiptVersion))
        return receipt
    }

    /** Compatibility overload for pre-Phase30/legacy-focused tests. New canonical gameplay must use V3 binding. */
    @Deprecated("New gameplay receipts must bind the required Event manifest")
    fun appendCommitted(identity: TurnTransactionIdentity, semanticFingerprint: String): TurnCommitReceipt {
        require(db.inTransaction())
        val order = reserveNextCommitOrder(identity.campaignUid)
        return appendCommitted(identity, semanticFingerprint, order, RequiredEventManifestSummary(0, sha256("EMPTY-LEGACY-COMPAT")))
    }

    private fun byTransaction(uid: String) = query("""SELECT campaign_uid,turn_uid,command_uid,transaction_uid,semantic_fingerprint,result_fingerprint,commit_order,
        required_event_count,required_event_manifest_fingerprint,receipt_version
        FROM turn_transaction_receipts WHERE transaction_uid=? AND commit_state='COMMITTED'""", arrayOf(uid))
    private fun byCommand(campaignUid: String, commandUid: String) = query("""SELECT campaign_uid,turn_uid,command_uid,transaction_uid,semantic_fingerprint,result_fingerprint,commit_order,
        required_event_count,required_event_manifest_fingerprint,receipt_version
        FROM turn_transaction_receipts WHERE campaign_uid=? AND command_uid=? AND commit_state='COMMITTED'""", arrayOf(campaignUid,commandUid))

    private fun query(sql: String, args: Array<String>): TurnCommitReceipt? = db.rawQuery(sql,args).use { c ->
        if (!c.moveToFirst()) null else TurnCommitReceipt(
            c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),
            if(c.isNull(6)) null else c.getLong(6),
            if(c.isNull(7)) null else c.getInt(7),
            if(c.isNull(8)) null else c.getString(8),
            c.getInt(9)
        )
    }

    private fun receiptFingerprint(
        identity: TurnTransactionIdentity,
        semantic: String,
        order: Long,
        eventManifest: RequiredEventManifestSummary
    ) = sha256(
        listOf(
            "RPGOS-TURN-RECEIPT-V3",identity.campaignUid,order.toString(),identity.turnUid,identity.commandUid,
            identity.transactionUid,semantic,eventManifest.requiredEventCount.toString(),eventManifest.orderedManifestFingerprint
        ).joinToString("\u001f")
    )

    private fun sha256(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    private fun conflict(code:String):Nothing=throw TurnIdempotencyConflictException(code)

    companion object {
        const val CROSS_CAMPAIGN_TRANSACTION_UID="RPGOS-TURN-IDEMPOTENCY:CROSS_CAMPAIGN_TRANSACTION_UID"
        const val TRANSACTION_IDENTITY_MISMATCH="RPGOS-TURN-IDEMPOTENCY:TRANSACTION_IDENTITY_MISMATCH"
        const val SEMANTIC_FINGERPRINT_MISMATCH="RPGOS-TURN-IDEMPOTENCY:SEMANTIC_FINGERPRINT_MISMATCH"
        const val COMMAND_SEMANTIC_FINGERPRINT_MISMATCH="RPGOS-TURN-IDEMPOTENCY:COMMAND_SEMANTIC_FINGERPRINT_MISMATCH"
        const val EVENT_MANIFEST_BINDING_MISMATCH="RPGOS-TURN-IDEMPOTENCY:EVENT_MANIFEST_BINDING_MISMATCH"
    }
}

/** Pure read facade: no schema creation, DDL or migration. */
class TurnRecoveryReader(private val db: SQLiteDatabase) {
    init {
        require(TurnTransactionReceiptSchema.isReady(db)) { "RPGOS-TURN-RECOVERY:SCHEMA_NOT_READY" }
    }
    private val receipts = TurnTransactionReceiptStore(db)
    fun lastValidCommit(campaignUid:String)=receipts.lastValidCommit(campaignUid)
    fun transaction(transactionUid:String)=receipts.committedTransaction(transactionUid)?.let{TurnRecoveryStatus(TurnRecoveryState.COMMITTED,it)}?:TurnRecoveryStatus(TurnRecoveryState.NOT_RECORDED)
    fun command(campaignUid:String,commandUid:String)=receipts.committedCommand(campaignUid,commandUid)?.let{TurnRecoveryStatus(TurnRecoveryState.COMMITTED,it)}?:TurnRecoveryStatus(TurnRecoveryState.NOT_RECORDED)
}

internal object TurnSemanticFingerprint {
    fun forProposal(proposal:CanonicalCampaignMutationProposal)=PlayerChangeSetCodec.fingerprint(proposal.playerChangeSet)
}
