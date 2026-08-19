package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.security.MessageDigest

/**
 * Single Phase33/Phase36 definition of a snapshot that can actually serve as a recovery source.
 * A Phase36 material-migration safety snapshot must pass this exact predicate.
 */
internal object RecoverableSnapshotPolicy {
    private val recoveryKinds = setOf(
        SnapshotKind.AUTOMATIC,
        SnapshotKind.MANUAL_BACKUP,
        SnapshotKind.PRE_RESTORE,
        SnapshotKind.USER_PINNED
    )

    fun isRecoveryKind(kind: SnapshotKind): Boolean = kind in recoveryKinds

    fun requireRecoverable(db: SQLiteDatabase, campaignUid: String, snapshotUid: String): CampaignSnapshotDescriptor {
        require(campaignUid.isNotBlank())
        require(CampaignSnapshotSchema.isReady(db)) { "RPGOS-SNAPSHOT:SCHEMA_NOT_READY" }
        val descriptor = descriptor(db, campaignUid, snapshotUid)
            ?: error("RPGOS-SCHEMA:SAFETY_SNAPSHOT_NOT_FOUND")
        require(isRecoveryKind(descriptor.kind)) { "RPGOS-SNAPSHOT:NON_RECOVERY_SNAPSHOT_KIND:${descriptor.kind}" }
        require(descriptor.state == SnapshotPublicationState.VALID) { "RPGOS-SNAPSHOT:SNAPSHOT_NOT_VALID" }
        require(descriptor.schemaVersion == CampaignSnapshotSchema.VERSION) { "RPGOS-SNAPSHOT:UNSUPPORTED_SNAPSHOT_SCHEMA:${descriptor.schemaVersion}" }
        val payload = File(descriptor.payloadPath)
        require(payload.isFile && descriptor.payloadSha256 != null && sha256File(payload) == descriptor.payloadSha256) {
            "RPGOS-SNAPSHOT:PAYLOAD_INVALID"
        }

        SQLiteDatabase.openDatabase(payload.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { captured ->
            require(captured.isDatabaseIntegrityOk) { "RPGOS-SNAPSHOT:PAYLOAD_DB_INTEGRITY_FAILED" }
            require(Phase36SchemaCompatibilityFingerprint.compute(captured) == Phase36SchemaCompatibilityFingerprint.compute(db)) {
                "RPGOS-SNAPSHOT:SCHEMA_COMPATIBILITY_MISMATCH"
            }
            verifyAnchor(captured, campaignUid, descriptor)
        }
        verifyReplayInterval(db, campaignUid, descriptor.anchorCommitOrder)
        return descriptor
    }

    fun latestRecoverable(db: SQLiteDatabase, campaignUid: String): CampaignSnapshotDescriptor? {
        if (!CampaignSnapshotSchema.isReady(db)) return null
        return db.rawQuery(
            """SELECT snapshot_uid FROM ${CampaignSnapshotSchema.CATALOG}
               WHERE campaign_uid=? ORDER BY created_order DESC,snapshot_uid DESC""".trimIndent(),
            arrayOf(campaignUid)
        ).use { c ->
            while (c.moveToNext()) {
                val uid = c.getString(0)
                val candidate = runCatching { requireRecoverable(db, campaignUid, uid) }.getOrNull()
                if (candidate != null) return@use candidate
            }
            null
        }
    }

    private fun verifyAnchor(captured: SQLiteDatabase, campaignUid: String, descriptor: CampaignSnapshotDescriptor) {
        val receipt = TurnTransactionReceiptStore(captured).lastValidCommit(campaignUid)
        if (receipt == null || receipt.commitOrder == null) {
            require(descriptor.anchorCommitOrder == 0L && descriptor.anchorTransactionUid == null && descriptor.anchorTurnUid == null && descriptor.anchorEventUid == null) {
                "RPGOS-SNAPSHOT:ANCHOR_MISMATCH"
            }
            return
        }
        require(descriptor.anchorCommitOrder == receipt.commitOrder) { "RPGOS-SNAPSHOT:ANCHOR_COMMIT_MISMATCH" }
        require(descriptor.anchorTransactionUid == receipt.transactionUid) { "RPGOS-SNAPSHOT:ANCHOR_TRANSACTION_MISMATCH" }
        require(descriptor.anchorTurnUid == receipt.turnUid) { "RPGOS-SNAPSHOT:ANCHOR_TURN_MISMATCH" }
        val eventUid = lastEventUid(captured, campaignUid, receipt.transactionUid)
        require(descriptor.anchorEventUid == eventUid) { "RPGOS-SNAPSHOT:ANCHOR_EVENT_MISMATCH" }
    }

    private fun verifyReplayInterval(db: SQLiteDatabase, campaignUid: String, anchorCommitOrder: Long) {
        val last = TurnTransactionReceiptStore(db).lastValidCommit(campaignUid)?.commitOrder ?: 0L
        require(anchorCommitOrder <= last) { "RPGOS-SNAPSHOT:ANCHOR_AHEAD_OF_LIVE_STATE" }
        if (anchorCommitOrder == last) return
        require(CampaignSnapshotSchema.isReady(db)) { "RPGOS-SNAPSHOT:REPLAY_SCHEMA_NOT_READY" }
        val actual = db.rawQuery(
            "SELECT commit_order FROM ${CampaignSnapshotSchema.REPLAY} WHERE campaign_uid=? AND commit_order>? ORDER BY commit_order",
            arrayOf(campaignUid, anchorCommitOrder.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
        val expected = (anchorCommitOrder + 1..last).toList()
        require(actual == expected) { "RPGOS-SNAPSHOT:NON_REPLAYABLE_INTERVAL" }
    }

    private fun lastEventUid(db: SQLiteDatabase, campaignUid: String, transactionUid: String): String? {
        if (!tableExists(db, CampaignIntelligencePhase30Schema.EVENT_TABLE)) return null
        val hasOrdinal = hasColumn(db, CampaignIntelligencePhase30Schema.EVENT_TABLE, "event_ordinal")
        val order = if (hasOrdinal) "event_ordinal DESC,event_uid DESC" else "event_intent_uid DESC,event_uid DESC"
        return db.rawQuery(
            "SELECT event_uid FROM ${CampaignIntelligencePhase30Schema.EVENT_TABLE} WHERE campaign_uid=? AND transaction_uid=? ORDER BY $order LIMIT 1",
            arrayOf(campaignUid, transactionUid)
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    private fun descriptor(db: SQLiteDatabase, campaignUid: String, snapshotUid: String): CampaignSnapshotDescriptor? = db.rawQuery(
        """SELECT snapshot_kind,snapshot_schema_version,created_order,created_at_epoch_ms,anchor_commit_order,
            anchor_transaction_uid,anchor_turn_uid,anchor_event_uid,payload_path,payload_sha256,publication_state,pinned
            FROM ${CampaignSnapshotSchema.CATALOG} WHERE campaign_uid=? AND snapshot_uid=? LIMIT 1""".trimIndent(),
        arrayOf(campaignUid, snapshotUid)
    ).use { c ->
        if (!c.moveToFirst()) null else CampaignSnapshotDescriptor(
            snapshotUid = snapshotUid,
            campaignUid = campaignUid,
            kind = SnapshotKind.valueOf(c.getString(0)),
            schemaVersion = c.getInt(1),
            createdOrder = c.getLong(2),
            createdAtEpochMs = c.getLong(3),
            anchorCommitOrder = c.getLong(4),
            anchorTransactionUid = if (c.isNull(5)) null else c.getString(5),
            anchorTurnUid = if (c.isNull(6)) null else c.getString(6),
            anchorEventUid = if (c.isNull(7)) null else c.getString(7),
            payloadPath = c.getString(8),
            payloadSha256 = if (c.isNull(9)) null else c.getString(9),
            state = SnapshotPublicationState.valueOf(c.getString(10)),
            pinned = c.getInt(11) != 0
        )
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1", arrayOf(table)
    ).use { it.moveToFirst() }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean = db.rawQuery(
        "PRAGMA table_info(`$table`)" , null
    ).use { c ->
        val idx = c.getColumnIndex("name")
        while (c.moveToNext()) if (idx >= 0 && c.getString(idx) == column) return@use true
        false
    }

    private fun sha256File(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
