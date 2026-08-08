package com.rpgos.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * SQLite implementation of the GM Engine campaign-side Source of Truth.
 *
 * One instance owns one campaign database. All writes performed through
 * [inTransaction] are atomic. The active worldpack is deliberately not mutated
 * here; canon remains a separate read-only source.
 */
class SQLiteUnifiedCampaignRepository(
    private val context: Context,
    private val campaignUid: EntityUid,
    private val worldPackUid: EntityUid
) : UnifiedCampaignRepository {
    private val helper = CampaignSourceOfTruthDb(context, campaignUid.value)
    private var transactionDepth = 0

    init {
        ensureMeta()
    }

    override suspend fun currentTurnId(campaignUid: EntityUid): Long {
        requireCampaign(campaignUid)
        helper.readableDatabase.rawQuery(
            "SELECT current_turn FROM campaign_meta WHERE campaign_id=?",
            arrayOf(campaignUid.value)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    override suspend fun writeTurn(turn: DurableTurnRecord) {
        requireCampaign(turn.campaignUid)
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put("turn_id", turn.turnUid.value)
            put("campaign_id", turn.campaignUid.value)
            put("turn_number", turn.turnId)
            put("chapter", turn.chapter)
            put("player_input", turn.playerInput)
            put("narrative", turn.narrative)
            put("status", turn.status.name)
            put("started_at", turn.startedAtEpochMs)
            turn.committedAtEpochMs?.let { put("committed_at", it) }
            turn.failureReason?.let { put("failure_reason", it) }
        }
        require(db.insertWithOnConflict("turns", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) {
            "Nie można zapisać tury ${turn.turnId}."
        }
        db.execSQL(
            """
            UPDATE campaign_meta
            SET current_turn=MAX(current_turn, ?),
                current_chapter=MAX(current_chapter, ?),
                updated_at=?
            WHERE campaign_id=?
            """.trimIndent(),
            arrayOf(turn.turnId, turn.chapter, System.currentTimeMillis(), campaignUid.value)
        )
    }

    override suspend fun getTruth(
        campaignUid: EntityUid,
        subjectUid: EntityUid,
        predicate: String,
        atTurnId: Long?
    ): List<CampaignTruth> {
        requireCampaign(campaignUid)
        val turn = atTurnId ?: currentTurnId(campaignUid)
        val result = mutableListOf<CampaignTruth>()
        helper.readableDatabase.rawQuery(
            """
            SELECT fact_id, truth_kind, subject_id, predicate, object_json, holder_id,
                   valid_from_turn, valid_until_turn, source_type, source_id,
                   confidence, canon_status, verified
            FROM facts
            WHERE campaign_id=? AND subject_id=? AND predicate=?
              AND valid_from_turn<=?
              AND (valid_until_turn IS NULL OR valid_until_turn>=?)
            ORDER BY confidence DESC, valid_from_turn DESC
            """.trimIndent(),
            arrayOf(campaignUid.value, subjectUid.value, predicate, turn.toString(), turn.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val sourceId = c.getString(9)?.let(::EntityUid)
                val holder = c.getString(5)?.let(::EntityUid)
                result += CampaignTruth(
                    uid = EntityUid(c.getString(0)),
                    kind = TruthKind.valueOf(c.getString(1)),
                    subjectUid = c.getString(2)?.let(::EntityUid),
                    predicate = c.getString(3),
                    value = c.getString(4),
                    holderUid = holder,
                    validFromTurn = c.getLong(6),
                    validUntilTurn = if (c.isNull(7)) null else c.getLong(7),
                    provenance = ProvenanceRecord(
                        type = ProvenanceType.valueOf(c.getString(8)),
                        sourceUid = sourceId,
                        turnId = c.getLong(6),
                        confidence = c.getDouble(10),
                        canonStatus = c.getString(11),
                        verified = c.getInt(12) != 0
                    )
                )
            }
        }
        return result
    }

    override suspend fun getActiveDivergences(campaignUid: EntityUid): List<CanonDivergence> {
        requireCampaign(campaignUid)
        val result = mutableListOf<CanonDivergence>()
        helper.readableDatabase.rawQuery(
            """
            SELECT divergence_id, canon_subject_id, canon_event_id, divergence_type,
                   description, caused_by_event_id, created_turn, active, resolved_turn
            FROM divergences
            WHERE campaign_id=? AND active=1
            ORDER BY created_turn ASC
            """.trimIndent(),
            arrayOf(campaignUid.value)
        ).use { c ->
            while (c.moveToNext()) {
                result += CanonDivergence(
                    uid = EntityUid(c.getString(0)),
                    canonSubjectUid = EntityUid(c.getString(1)),
                    canonEventUid = c.getString(2)?.let(::EntityUid),
                    divergenceType = c.getString(3),
                    description = c.getString(4),
                    causedByEventUid = c.getString(5)?.let(::EntityUid),
                    createdTurn = c.getLong(6),
                    active = c.getInt(7) != 0,
                    resolvedTurn = if (c.isNull(8)) null else c.getLong(8)
                )
            }
        }
        return result
    }

    override suspend fun writeDivergence(divergence: CanonDivergence) {
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put("divergence_id", divergence.uid.value)
            put("campaign_id", campaignUid.value)
            put("canon_subject_id", divergence.canonSubjectUid.value)
            divergence.canonEventUid?.let { put("canon_event_id", it.value) }
            put("divergence_type", divergence.divergenceType)
            put("description", divergence.description)
            divergence.causedByEventUid?.let { put("caused_by_event_id", it.value) }
            put("active", if (divergence.active) 1 else 0)
            put("created_turn", divergence.createdTurn)
            divergence.resolvedTurn?.let { put("resolved_turn", it) }
        }
        db.insertWithOnConflict("divergences", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override suspend fun appendEvent(event: DurableCampaignEvent) {
        requireCampaign(event.campaignUid)
        val db = helper.writableDatabase
        val turnUid = resolveTurnUid(db, event.turnId)
        val chapter = resolveTurnChapter(db, event.turnId)
        val values = ContentValues().apply {
            put("event_id", event.eventUid.value)
            put("campaign_id", event.campaignUid.value)
            put("turn_id", turnUid)
            put("turn_number", event.turnId)
            put("sequence", event.sequence)
            put("chapter", chapter)
            put("event_type", event.type.name)
            event.actorUid?.let { put("actor_id", it.value) }
            event.targetUid?.let { put("target_id", it.value) }
            put("description", event.description)
            put("payload_json", event.payloadJson)
            event.causeEventUid?.let { put("cause_event_id", it.value) }
            put("source_type", event.provenance.type.name)
            event.provenance.sourceUid?.let { put("source_id", it.value) }
            put("confidence", event.provenance.confidence)
            put("created_at", System.currentTimeMillis())
        }
        db.insertOrThrow("events", null, values)
    }

    override suspend fun applyMutation(mutation: DurableStateMutation) {
        requireCampaign(mutation.campaignUid)
        val db = helper.writableDatabase
        val current = readStateValue(db, mutation)
        if (mutation.oldValue != null) {
            require(current == mutation.oldValue) {
                "Konflikt Source of Truth dla ${mutation.entityUid}.${mutation.field}: " +
                    "oczekiwano '${mutation.oldValue}', baza zawiera '$current'."
            }
        }

        val history = ContentValues().apply {
            put("mutation_id", mutation.mutationUid.value)
            put("campaign_id", mutation.campaignUid.value)
            put("turn_number", mutation.turnId)
            put("entity_id", mutation.entityUid.value)
            put("field_key", mutation.field)
            put("operation", mutation.operation.name)
            mutation.oldValue?.let { put("old_value_json", it) }
            mutation.newValue?.let { put("new_value_json", it) }
            put("reason", mutation.reason)
            mutation.causedByEventUid?.let { put("caused_by_event_id", it.value) }
            put("created_at", System.currentTimeMillis())
        }
        db.insertOrThrow("state_mutations", null, history)

        if (mutation.operation == MutationOperation.REMOVE && mutation.newValue == null) {
            db.delete(
                "entity_state",
                "campaign_id=? AND entity_id=? AND field_key=?",
                arrayOf(campaignUid.value, mutation.entityUid.value, mutation.field)
            )
            return
        }

        val resolved = requireNotNull(mutation.newValue) {
            "Mutacja ${mutation.operation} wymaga resolved newValue."
        }
        val state = ContentValues().apply {
            put("campaign_id", campaignUid.value)
            put("entity_type", mutation.entityType)
            put("entity_id", mutation.entityUid.value)
            put("field_key", mutation.field)
            put("value_json", resolved)
            put("valid_from_turn", mutation.turnId)
            put("updated_at", System.currentTimeMillis())
            put("provenance_type", ProvenanceType.CAMPAIGN_EVENT.name)
            mutation.causedByEventUid?.let { put("provenance_id", it.value) }
        }
        db.insertWithOnConflict("entity_state", null, state, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override suspend fun writeTruth(truth: CampaignTruth) {
        val fromTurn = truth.validFromTurn ?: truth.provenance.turnId ?: currentTurnId(campaignUid)
        val values = ContentValues().apply {
            put("fact_id", truth.uid.value)
            put("campaign_id", campaignUid.value)
            truth.subjectUid?.let { put("subject_id", it.value) }
            put("predicate", truth.predicate)
            put("object_json", truth.value)
            put("truth_kind", truth.kind.name)
            truth.holderUid?.let { put("holder_id", it.value) }
            put("confidence", truth.provenance.confidence)
            put("valid_from_turn", fromTurn)
            truth.validUntilTurn?.let { put("valid_until_turn", it) }
            put("source_type", truth.provenance.type.name)
            truth.provenance.sourceUid?.let { put("source_id", it.value) }
            truth.provenance.canonStatus?.let { put("canon_status", it) }
            put("verified", if (truth.provenance.verified) 1 else 0)
            put("created_at", System.currentTimeMillis())
        }
        helper.writableDatabase.insertWithOnConflict("facts", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override suspend fun writeMemory(memory: DurableMemoryRecord) {
        requireCampaign(memory.campaignUid)
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put("memory_id", memory.memoryUid.value)
            put("campaign_id", campaignUid.value)
            put("memory_kind", memory.kind.name)
            memory.subjectUid?.let { put("subject_id", it.value) }
            put("text", memory.text)
            put("importance", memory.importance)
            put("confidence", 1.0)
            put("first_turn", memory.createdTurn)
            put("last_reinforced_turn", memory.createdTurn)
            put("tags_json", JSONArray(memory.tags.sorted()).toString())
            put("archived", 0)
        }
        db.insertWithOnConflict("memories", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        memory.sourceEventUids.forEach { eventUid ->
            db.insertWithOnConflict(
                "memory_event_links",
                null,
                ContentValues().apply {
                    put("memory_id", memory.memoryUid.value)
                    put("event_id", eventUid.value)
                },
                SQLiteDatabase.CONFLICT_IGNORE
            )
        }
    }

    override suspend fun writeChronicle(entry: DurableChronicleRecord) {
        requireCampaign(entry.campaignUid)
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put("chronicle_id", entry.entryUid.value)
            put("campaign_id", campaignUid.value)
            put("turn_id", resolveTurnUid(db, entry.turnId))
            put("chapter", entry.chapter)
            put("title", entry.title)
            put("summary", entry.summary)
            put("created_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict("chronicle_entries", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        entry.eventUids.forEach { eventUid ->
            db.insertWithOnConflict(
                "chronicle_event_links",
                null,
                ContentValues().apply {
                    put("chronicle_id", entry.entryUid.value)
                    put("event_id", eventUid.value)
                },
                SQLiteDatabase.CONFLICT_IGNORE
            )
        }
    }

    override suspend fun latestSnapshot(campaignUid: EntityUid): CampaignSnapshotRef? {
        requireCampaign(campaignUid)
        helper.readableDatabase.rawQuery(
            """
            SELECT snapshot_id, turn_number, event_sequence, created_at
            FROM snapshots WHERE campaign_id=?
            ORDER BY turn_number DESC LIMIT 1
            """.trimIndent(),
            arrayOf(campaignUid.value)
        ).use { c ->
            if (!c.moveToFirst()) return null
            return CampaignSnapshotRef(
                snapshotUid = EntityUid(c.getString(0)),
                campaignUid = campaignUid,
                throughTurnId = c.getLong(1),
                throughEventSequence = c.getLong(2),
                createdAtEpochMs = c.getLong(3)
            )
        }
    }

    override suspend fun createSnapshot(
        campaignUid: EntityUid,
        throughTurnId: Long
    ): CampaignSnapshotRef {
        requireCampaign(campaignUid)
        require(transactionDepth == 0) { "Snapshotu nie wolno tworzyć wewnątrz aktywnej transakcji." }
        val db = helper.writableDatabase
        val current = currentTurnId(campaignUid)
        require(throughTurnId in 0..current) { "Niepoprawny zakres snapshotu: $throughTurnId / $current" }

        db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
        val snapshotUid = EntityUid("SNAP-${UUID.randomUUID()}")
        val dir = File(context.filesDir, "rpgos/snapshots/${campaignUid.value}").apply { mkdirs() }
        val target = File(dir, "${snapshotUid.value}.db")
        File(db.path).copyTo(target, overwrite = true)
        val hash = sha256(target)
        val eventSequence = eventCountThrough(db, throughTurnId)
        val now = System.currentTimeMillis()

        db.insertOrThrow(
            "snapshots",
            null,
            ContentValues().apply {
                put("snapshot_id", snapshotUid.value)
                put("campaign_id", campaignUid.value)
                put("turn_number", throughTurnId)
                put("event_sequence", eventSequence)
                put("state_hash", hash)
                put("storage_path", target.absolutePath)
                put("created_at", now)
            }
        )
        db.execSQL(
            "UPDATE campaign_meta SET current_snapshot_id=?, updated_at=? WHERE campaign_id=?",
            arrayOf(snapshotUid.value, now, campaignUid.value)
        )
        return CampaignSnapshotRef(snapshotUid, campaignUid, throughTurnId, eventSequence, now)
    }

    override suspend fun <T> inTransaction(block: suspend UnifiedCampaignRepository.() -> T): T {
        check(transactionDepth == 0) { "Zagnieżdżone transakcje UnifiedCampaignRepository nie są obsługiwane." }
        val db = helper.writableDatabase
        db.beginTransaction()
        transactionDepth++
        return try {
            val result = block(this)
            db.setTransactionSuccessful()
            result
        } finally {
            transactionDepth--
            db.endTransaction()
        }
    }

    private fun ensureMeta() {
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        db.insertWithOnConflict(
            "campaign_meta",
            null,
            ContentValues().apply {
                put("campaign_id", campaignUid.value)
                put("world_pack_id", worldPackUid.value)
                put("engine_version_code", BuildConfig.VERSION_CODE)
                put("campaign_schema_version", CampaignSourceOfTruthDb.SCHEMA_VERSION)
                put("event_schema_version", CampaignSourceOfTruthDb.EVENT_SCHEMA_VERSION)
                put("memory_schema_version", CampaignSourceOfTruthDb.MEMORY_SCHEMA_VERSION)
                put("created_at", now)
                put("updated_at", now)
                put("current_turn", 0)
                put("current_chapter", 0)
            },
            SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    private fun requireCampaign(uid: EntityUid) {
        require(uid == campaignUid) {
            "Repozytorium ${campaignUid.value} nie może obsłużyć kampanii ${uid.value}."
        }
    }

    private fun resolveTurnUid(db: SQLiteDatabase, turnId: Long): String {
        db.rawQuery(
            "SELECT turn_id FROM turns WHERE campaign_id=? AND turn_number=?",
            arrayOf(campaignUid.value, turnId.toString())
        ).use { c ->
            require(c.moveToFirst()) { "Brak rekordu tury $turnId przed zapisem zależnych danych." }
            return c.getString(0)
        }
    }

    private fun resolveTurnChapter(db: SQLiteDatabase, turnId: Long): Long {
        db.rawQuery(
            "SELECT chapter FROM turns WHERE campaign_id=? AND turn_number=?",
            arrayOf(campaignUid.value, turnId.toString())
        ).use { c ->
            require(c.moveToFirst()) { "Brak rekordu tury $turnId." }
            return c.getLong(0)
        }
    }

    private fun readStateValue(db: SQLiteDatabase, mutation: DurableStateMutation): String? {
        db.rawQuery(
            "SELECT value_json FROM entity_state WHERE campaign_id=? AND entity_id=? AND field_key=?",
            arrayOf(campaignUid.value, mutation.entityUid.value, mutation.field)
        ).use { c -> return if (c.moveToFirst()) c.getString(0) else null }
    }

    private fun eventCountThrough(db: SQLiteDatabase, throughTurnId: Long): Long {
        db.rawQuery(
            "SELECT COUNT(*) FROM events WHERE campaign_id=? AND turn_number<=?",
            arrayOf(campaignUid.value, throughTurnId.toString())
        ).use { c ->
            c.moveToFirst()
            return c.getLong(0)
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                md.update(buffer, 0, count)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
