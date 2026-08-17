package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest

const val TURN_TRANSACTION_RECEIPT_VERSION = 2

data class TurnCommitReceipt(
    val campaignUid: String,
    val turnUid: String,
    val commandUid: String,
    val transactionUid: String,
    val semanticFingerprint: String,
    val resultFingerprint: String,
    val commitOrder: Long,
    val receiptVersion: Int = TURN_TRANSACTION_RECEIPT_VERSION
)

enum class TurnRecoveryState { NOT_RECORDED, COMMITTED }

data class TurnRecoveryStatus(val state: TurnRecoveryState, val receipt: TurnCommitReceipt? = null)

sealed interface TurnExecutionResult<out T> {
    data class Committed<T>(val value: T, val receipt: TurnCommitReceipt) : TurnExecutionResult<T>
    data class AlreadyCommitted(val receipt: TurnCommitReceipt) : TurnExecutionResult<Nothing>
}

class TurnIdempotencyConflictException(val code: String) : IllegalStateException(code)

/**
 * Durable transaction receipt authority for Phase 28 idempotency and Phase 29 crash recovery.
 *
 * Only fully committed turns are durable here. There is deliberately no durable IN_PROGRESS row:
 * SQLite rollback/reopen semantics make an absent receipt mean that no post-upgrade committed turn
 * is recorded for that identity. commit_order is campaign-scoped, monotonic and allocated while the
 * same outer write transaction owns all authoritative effects; it is never inferred from time/UIDs.
 */
internal class TurnTransactionReceiptStore(private val db: SQLiteDatabase) {
    init { ensureSchema() }

    fun replay(identity: TurnTransactionIdentity, semanticFingerprint: String): TurnCommitReceipt? {
        require(semanticFingerprint.isNotBlank())
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
        return query(
            "SELECT campaign_uid,turn_uid,command_uid,transaction_uid,semantic_fingerprint,result_fingerprint,commit_order,receipt_version FROM turn_transaction_receipts WHERE campaign_uid=? AND commit_state='COMMITTED' ORDER BY commit_order DESC LIMIT 1",
            arrayOf(campaignUid)
        )
    }

    fun committedTransaction(transactionUid: String): TurnCommitReceipt? {
        require(transactionUid.isNotBlank())
        return byTransaction(transactionUid)
    }

    fun committedCommand(campaignUid: String, commandUid: String): TurnCommitReceipt? {
        require(campaignUid.isNotBlank()); require(commandUid.isNotBlank())
        return byCommand(campaignUid, commandUid)
    }

    fun appendCommitted(identity: TurnTransactionIdentity, semanticFingerprint: String): TurnCommitReceipt {
        require(db.inTransaction()) { "turn receipt must join active outer transaction" }
        replay(identity, semanticFingerprint)?.let { return it }
        val commitOrder = nextCommitOrder(identity.campaignUid)
        val receipt = TurnCommitReceipt(
            campaignUid = identity.campaignUid,
            turnUid = identity.turnUid,
            commandUid = identity.commandUid,
            transactionUid = identity.transactionUid,
            semanticFingerprint = semanticFingerprint,
            resultFingerprint = receiptFingerprint(identity, semanticFingerprint, commitOrder),
            commitOrder = commitOrder
        )
        db.execSQL(
            """INSERT INTO turn_transaction_receipts(
                transaction_uid,campaign_uid,turn_uid,command_uid,semantic_fingerprint,result_fingerprint,commit_order,receipt_version,commit_state
            ) VALUES(?,?,?,?,?,?,?,?,'COMMITTED')""".trimIndent(),
            arrayOf(
                receipt.transactionUid, receipt.campaignUid, receipt.turnUid, receipt.commandUid,
                receipt.semanticFingerprint, receipt.resultFingerprint, receipt.commitOrder, receipt.receiptVersion
            )
        )
        return receipt
    }

    private fun nextCommitOrder(campaignUid: String): Long = db.rawQuery(
        "SELECT COALESCE(MAX(commit_order),0)+1 FROM turn_transaction_receipts WHERE campaign_uid=? AND commit_state='COMMITTED'",
        arrayOf(campaignUid)
    ).use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }

    private fun byTransaction(transactionUid: String): TurnCommitReceipt? = query(
        "SELECT campaign_uid,turn_uid,command_uid,transaction_uid,semantic_fingerprint,result_fingerprint,commit_order,receipt_version FROM turn_transaction_receipts WHERE transaction_uid=? AND commit_state='COMMITTED'",
        arrayOf(transactionUid)
    )

    private fun byCommand(campaignUid: String, commandUid: String): TurnCommitReceipt? = query(
        "SELECT campaign_uid,turn_uid,command_uid,transaction_uid,semantic_fingerprint,result_fingerprint,commit_order,receipt_version FROM turn_transaction_receipts WHERE campaign_uid=? AND command_uid=? AND commit_state='COMMITTED'",
        arrayOf(campaignUid, commandUid)
    )

    private fun query(sql: String, args: Array<String>): TurnCommitReceipt? = db.rawQuery(sql, args).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        TurnCommitReceipt(
            campaignUid = cursor.getString(0),
            turnUid = cursor.getString(1),
            commandUid = cursor.getString(2),
            transactionUid = cursor.getString(3),
            semanticFingerprint = cursor.getString(4),
            resultFingerprint = cursor.getString(5),
            commitOrder = cursor.getLong(6),
            receiptVersion = cursor.getInt(7)
        )
    }

    private fun ensureSchema() {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS turn_transaction_receipts(
                transaction_uid TEXT PRIMARY KEY,
                campaign_uid TEXT NOT NULL,
                turn_uid TEXT NOT NULL,
                command_uid TEXT NOT NULL,
                semantic_fingerprint TEXT NOT NULL,
                result_fingerprint TEXT NOT NULL,
                commit_order INTEGER NOT NULL CHECK(commit_order > 0),
                receipt_version INTEGER NOT NULL CHECK(receipt_version IN (1,2)),
                commit_state TEXT NOT NULL CHECK(commit_state = 'COMMITTED'),
                UNIQUE(campaign_uid, command_uid),
                UNIQUE(campaign_uid, commit_order)
            )""".trimIndent()
        )
        if (!hasColumn("turn_transaction_receipts", "commit_order")) {
            // Existing Phase-28 rows are real committed receipts. Give only those proven receipts a
            // deterministic per-campaign order; no pre-Phase-28 history is synthesized.
            db.execSQL("ALTER TABLE turn_transaction_receipts ADD COLUMN commit_order INTEGER")
            val campaigns = mutableListOf<String>()
            db.rawQuery("SELECT DISTINCT campaign_uid FROM turn_transaction_receipts ORDER BY campaign_uid", null).use { c ->
                while (c.moveToNext()) campaigns += c.getString(0)
            }
            campaigns.forEach { campaign ->
                val txs = mutableListOf<String>()
                db.rawQuery("SELECT transaction_uid FROM turn_transaction_receipts WHERE campaign_uid=? ORDER BY rowid", arrayOf(campaign)).use { c ->
                    while (c.moveToNext()) txs += c.getString(0)
                }
                txs.forEachIndexed { index, tx ->
                    db.execSQL("UPDATE turn_transaction_receipts SET commit_order=? WHERE transaction_uid=?", arrayOf(index + 1L, tx))
                }
            }
        }
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_turn_receipts_campaign_order ON turn_transaction_receipts(campaign_uid,commit_order)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_turn_receipts_campaign ON turn_transaction_receipts(campaign_uid,transaction_uid)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_turn_receipts_command ON turn_transaction_receipts(campaign_uid,command_uid)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS rpgos_schema_migrations(
                migration_id TEXT PRIMARY KEY,
                applied_at INTEGER NOT NULL,
                notes TEXT
            )""".trimIndent()
        )
        db.execSQL(
            "INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes) VALUES('RPGOS-28.0-TURN-IDEMPOTENCY',strftime('%s','now'),'Adds append-only committed turn receipts prospectively; no legacy transaction history is fabricated')"
        )
        db.execSQL(
            "INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes) VALUES('RPGOS-29.0-CRASH-RECOVERY',strftime('%s','now'),'Adds deterministic campaign-scoped commit ordering to proven Phase-28+ committed receipts only')"
        )
    }

    private fun hasColumn(table: String, column: String): Boolean = db.rawQuery("PRAGMA table_info($table)", null).use { c ->
        val nameIndex = c.getColumnIndex("name")
        while (c.moveToNext()) if (c.getString(nameIndex) == column) return@use true
        false
    }

    private fun receiptFingerprint(identity: TurnTransactionIdentity, semanticFingerprint: String, commitOrder: Long): String = sha256(
        listOf(
            "RPGOS-TURN-RECEIPT-V2", identity.campaignUid, commitOrder.toString(), identity.turnUid,
            identity.commandUid, identity.transactionUid, semanticFingerprint
        ).joinToString("\u001f")
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun conflict(code: String): Nothing = throw TurnIdempotencyConflictException(code)

    companion object {
        const val CROSS_CAMPAIGN_TRANSACTION_UID = "RPGOS-TURN-IDEMPOTENCY:CROSS_CAMPAIGN_TRANSACTION_UID"
        const val TRANSACTION_IDENTITY_MISMATCH = "RPGOS-TURN-IDEMPOTENCY:TRANSACTION_IDENTITY_MISMATCH"
        const val SEMANTIC_FINGERPRINT_MISMATCH = "RPGOS-TURN-IDEMPOTENCY:SEMANTIC_FINGERPRINT_MISMATCH"
        const val COMMAND_SEMANTIC_FINGERPRINT_MISMATCH = "RPGOS-TURN-IDEMPOTENCY:COMMAND_SEMANTIC_FINGERPRINT_MISMATCH"
    }
}

/** Read-only recovery facade. It never writes gameplay state. */
class TurnRecoveryReader(private val db: SQLiteDatabase) {
    private val receipts = TurnTransactionReceiptStore(db)

    fun lastValidCommit(campaignUid: String): TurnCommitReceipt? = receipts.lastValidCommit(campaignUid)

    fun transaction(transactionUid: String): TurnRecoveryStatus = receipts.committedTransaction(transactionUid)?.let {
        TurnRecoveryStatus(TurnRecoveryState.COMMITTED, it)
    } ?: TurnRecoveryStatus(TurnRecoveryState.NOT_RECORDED)

    fun command(campaignUid: String, commandUid: String): TurnRecoveryStatus = receipts.committedCommand(campaignUid, commandUid)?.let {
        TurnRecoveryStatus(TurnRecoveryState.COMMITTED, it)
    } ?: TurnRecoveryStatus(TurnRecoveryState.NOT_RECORDED)
}

internal object TurnSemanticFingerprint {
    fun forProposal(proposal: CanonicalCampaignMutationProposal): String =
        PlayerChangeSetCodec.fingerprint(proposal.playerChangeSet)

    fun identityOnlyForInternalTest(identity: TurnTransactionIdentity): String {
        val value = listOf("RPGOS-TURN-INTERNAL-V1", identity.campaignUid, identity.turnUid, identity.commandUid, identity.transactionUid)
            .joinToString("\u001f")
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
