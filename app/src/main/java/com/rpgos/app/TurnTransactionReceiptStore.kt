package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest

const val TURN_TRANSACTION_RECEIPT_VERSION = 1

data class TurnCommitReceipt(
    val campaignUid: String,
    val turnUid: String,
    val commandUid: String,
    val transactionUid: String,
    val semanticFingerprint: String,
    val resultFingerprint: String,
    val receiptVersion: Int = TURN_TRANSACTION_RECEIPT_VERSION
)

sealed interface TurnExecutionResult<out T> {
    data class Committed<T>(val value: T, val receipt: TurnCommitReceipt) : TurnExecutionResult<T>
    data class AlreadyCommitted(val receipt: TurnCommitReceipt) : TurnExecutionResult<Nothing>
}

class TurnIdempotencyConflictException(val code: String) : IllegalStateException(code)

/**
 * Phase-28 durable idempotency registry / immutable commit receipt.
 *
 * Authority classification: APPEND-ONLY COMMIT EVIDENCE. It does not reconstruct current state and
 * is not a second domain ledger. A receipt row is inserted inside the same outer TurnTransaction as
 * authoritative effects, so a rollback cannot leave a false COMMITTED marker.
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

    fun appendCommitted(identity: TurnTransactionIdentity, semanticFingerprint: String): TurnCommitReceipt {
        require(db.inTransaction()) { "turn receipt must join active outer transaction" }
        replay(identity, semanticFingerprint)?.let { return it }
        val receipt = TurnCommitReceipt(
            campaignUid = identity.campaignUid,
            turnUid = identity.turnUid,
            commandUid = identity.commandUid,
            transactionUid = identity.transactionUid,
            semanticFingerprint = semanticFingerprint,
            resultFingerprint = receiptFingerprint(identity, semanticFingerprint)
        )
        db.execSQL(
            """INSERT INTO turn_transaction_receipts(
                transaction_uid,campaign_uid,turn_uid,command_uid,semantic_fingerprint,result_fingerprint,receipt_version,commit_state
            ) VALUES(?,?,?,?,?,?,?,'COMMITTED')""".trimIndent(),
            arrayOf(
                receipt.transactionUid, receipt.campaignUid, receipt.turnUid, receipt.commandUid,
                receipt.semanticFingerprint, receipt.resultFingerprint, receipt.receiptVersion
            )
        )
        return receipt
    }

    private fun byTransaction(transactionUid: String): TurnCommitReceipt? = query(
        "SELECT campaign_uid,turn_uid,command_uid,transaction_uid,semantic_fingerprint,result_fingerprint,receipt_version FROM turn_transaction_receipts WHERE transaction_uid=?",
        arrayOf(transactionUid)
    )

    private fun byCommand(campaignUid: String, commandUid: String): TurnCommitReceipt? = query(
        "SELECT campaign_uid,turn_uid,command_uid,transaction_uid,semantic_fingerprint,result_fingerprint,receipt_version FROM turn_transaction_receipts WHERE campaign_uid=? AND command_uid=?",
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
            receiptVersion = cursor.getInt(6)
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
                receipt_version INTEGER NOT NULL CHECK(receipt_version = 1),
                commit_state TEXT NOT NULL CHECK(commit_state = 'COMMITTED'),
                UNIQUE(campaign_uid, command_uid)
            )""".trimIndent()
        )
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
    }

    private fun receiptFingerprint(identity: TurnTransactionIdentity, semanticFingerprint: String): String = sha256(
        listOf(
            "RPGOS-TURN-RECEIPT-V1", identity.campaignUid, identity.turnUid,
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
