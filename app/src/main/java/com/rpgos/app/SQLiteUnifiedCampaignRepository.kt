package com.rpgos.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * SQLite implementation of the GM Engine campaign-side Source of Truth.
 *
 * This repository operates on the existing campaign.db opened by LocalGameStore.
 * It does not create a parallel database. The caller may transfer ownership of
 * the SQLiteDatabase to this repository with [ownsDatabase].
 */
class SQLiteUnifiedCampaignRepository(
    private val context: Context,
    private val db: SQLiteDatabase,
    private val campaignUid: EntityUid,
    private val worldPackUid: EntityUid,
    private val ownsDatabase: Boolean = false
) : UnifiedCampaignRepository, Closeable {
    private var transactionDepth = 0

    init {
        db.setForeignKeyConstraintsEnabled(true)
        CampaignSourceOfTruthSchema.ensure(db)
        ensureMeta()
    }

    override suspend fun currentTurnId(campaignUid: EntityUid): Long {
        requireCampaign(campaignUid)
        db.rawQuery(
            "SELECT current_turn FROM gm_campaign_meta WHERE campaign_id=?",
            arrayOf(campaignUid.value)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    override suspend fun writeTurn(turn: DurableTurnRecord) {
        requireCampaign(turn.campaignUid)
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
        require(db.insertWithOnConflict("gm_turns", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) {
            "Nie można zapisać tury ${turn.turnId}."
        }
        db.execSQL(
            """
            UPDATE gm_campaign_meta
            SET current_turn=MAX(current_turn, ?),
                current_chapter=MAX(current_chapter, ?),
                updated_at=?
            WHERE campaign_id=?
            """.trimIndent(),
            arrayOf(turn.turnId, turn.chapter, System.currentTimeMillis(), campaignUid.value)
        )
    }

    override suspend fun getEntityState(
        campaignUid: EntityUid,
        entityUid: EntityUid,
        entityType: String?
    ): List<CampaignStateField> {
        requireCampaign(campaignUid)
        val sql: String
        val args: Array<String>
        if (entityType == null) {
            sql = """
                SELECT entity_type, entity_id, field_key, value_json, valid_from_turn,
                       provenance_type, provenance_id
                FROM gm_entity_state
                WHERE campaign_id=? AND entity_id=?
                ORDER BY entity_type, field_key
            """.trimIndent()
            args = arrayOf(campaignUid.value, entityUid.value)
        } else {
            sql = """
                SELECT entity_type, entity_id, field_key, value_json, valid_from_turn,
                       provenance_type, provenance_id
                FROM gm_entity_state
                WHERE campaign_id=? AND entity_id=? AND entity_type=?
                ORDER BY field_key
            """.trimIndent()
            args = arrayOf(campaignUid.value, entityUid.value, entityType)
        }
        val out = mutableListOf<CampaignStateField>()
        db.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                out += CampaignStateField(
                    entityType = c.getString(0),
                    entityUid = EntityUid(c.getString(1)),
                    field = c.getString(2),
                    value = c.getString(3),
                    validFromTurn = c.getLong(4),
                    provenanceType = c.getString(5)?.let { runCatching { ProvenanceType.valueOf(it) }.getOrNull() },
                    provenanceUid = c.getString(6)?.let(::EntityUid)
                )
            }
        }
        return out
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
        db.rawQuery(
            """
            SELECT fact_id, truth_kind, subject_id, predicate, object_json, holder_id,
                   valid_from_turn, valid_until_turn, source_type, source_id, source_turn,
                   confidence, canon_status, verified
            FROM gm_facts
            WHERE campaign_id=? AND subject_id=? AND predicate=?
              AND valid_from_turn<=?
              AND (valid_until_turn IS NULL OR valid_until_turn>=?)
            ORDER BY confidence DESC, valid_from_turn DESC
            """.trimIndent(),
            arrayOf(campaignUid.value, subjectUid.value, predicate, turn.toString(), turn.toString())
        ).use { c ->
            while (c.moveToNext()) result += readTruth(c)
        }
        return result
    }

    override suspend fun getBeliefs(
        campaignUid: EntityUid,
        holderUid: EntityUid,
        subjectUid: EntityUid?,
        atTurnId: Long?,
        limit: Int
    ): List<CampaignTruth> {
        requireCampaign(campaignUid)
        require(limit in 1..1000) { "Niepoprawny limit beliefs: $limit" }
        val turn = atTurnId ?: currentTurnId(campaignUid)
        val subjectClause = if (subjectUid == null) "" else " AND subject_id=?"
        val args = mutableListOf(campaignUid.value, holderUid.value, turn.toString(), turn.toString())
        subjectUid?.let { args += it.value }
        args += limit.toString()
        val result = mutableListOf<CampaignTruth>()
        db.rawQuery(
            """
            SELECT fact_id, truth_kind, subject_id, predicate, object_json, holder_id,
                   valid_from_turn, valid_until_turn, source_type, source_id, source_turn,
                   confidence, canon_status, verified
            FROM gm_facts
            WHERE campaign_id=? AND truth_kind='BELIEF' AND holder_id=?
              AND valid_from_turn<=?
              AND (valid_until_turn IS NULL OR valid_until_turn>=?)
              $subjectClause
            ORDER BY confidence DESC, valid_from_turn DESC
            LIMIT ?
            """.trimIndent(),
            args.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) result += readTruth(c)
        }
        return result
    }

    override suspend fun recentEvents(
        campaignUid: EntityUid,
        beforeOrAtTurn: Long?,
        limit: Int
    ): List<DurableCampaignEvent> {
        requireCampaign(campaignUid)
        require(limit in 1..1000) { "Niepoprawny limit eventów: $limit" }
        val turn = beforeOrAtTurn ?: currentTurnId(campaignUid)
        val out = mutableListOf<DurableCampaignEvent>()
        db.rawQuery(
            """
            SELECT event_id, turn_number, sequence, event_type, actor_id, target_id,
                   cause_event_id, description, payload_json, source_type, source_id, confidence
            FROM gm_events
            WHERE campaign_id=? AND turn_number<=?
            ORDER BY turn_number DESC, sequence DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(campaignUid.value, turn.toString(), limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += DurableCampaignEvent(
                    eventUid = EntityUid(c.getString(0)),
                    campaignUid = campaignUid,
                    turnId = c.getLong(1),
                    sequence = c.getLong(2),
                    type = runCatching { CampaignEventType.valueOf(c.getString(3)) }.getOrDefault(CampaignEventType.CUSTOM),
                    actorUid = c.getString(4)?.let(::EntityUid),
                    targetUid = c.getString(5)?.let(::EntityUid),
                    causeEventUid = c.getString(6)?.let(::EntityUid),
                    description = c.getString(7),
                    payloadJson = c.getString(8),
                    provenance = ProvenanceRecord(
                        type = ProvenanceType.valueOf(c.getString(9)),
                        sourceUid = c.getString(10)?.let(::EntityUid),
                        turnId = c.getLong(1),
                        confidence = c.getDouble(11)
                    )
                )
            }
        }
        return out
    }

    override suspend fun memories(
        campaignUid: EntityUid,
        subjectUid: EntityUid?,
        kinds: Set<DurableMemoryKind>,
        limit: Int
    ): List<DurableMemoryRecord> {
        requireCampaign(campaignUid)
        require(limit in 1..1000) { "Niepoprawny limit pamięci: $limit" }
        if (kinds.isEmpty()) return emptyList()

        val kindPlaceholders = kinds.joinToString(",") { "?" }
        val subjectClause = if (subjectUid == null) "" else " AND subject_id=?"
        val args = mutableListOf(campaignUid.value)
        args += kinds.map { it.name }
        subjectUid?.let { args += it.value }
        args += limit.toString()

        val out = mutableListOf<DurableMemoryRecord>()
        db.rawQuery(
            """
            SELECT memory_id, memory_kind, subject_id, text, importance, first_turn, tags_json
            FROM gm_memories
            WHERE campaign_id=? AND archived=0 AND memory_kind IN ($kindPlaceholders)
              $subjectClause
            ORDER BY importance DESC, last_reinforced_turn DESC
            LIMIT ?
            """.trimIndent(),
            args.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) {
                val memoryUid = EntityUid(c.getString(0))
                out += DurableMemoryRecord(
                    memoryUid = memoryUid,
                    campaignUid = campaignUid,
                    kind = DurableMemoryKind.valueOf(c.getString(1)),
                    subjectUid = c.getString(2)?.let(::EntityUid),
                    text = c.getString(3),
                    importance = c.getDouble(4),
                    createdTurn = c.getLong(5),
                    sourceEventUids = memoryEventUids(memoryUid),
                    tags = jsonStringSet(c.getString(6))
                )
            }
        }
        return out
    }

    override suspend fun getActiveDivergences(campaignUid: EntityUid): List<CanonDivergence> {
        requireCampaign(campaignUid)
        val result = mutableListOf<CanonDivergence>()
        db.rawQuery(
            """
            SELECT divergence_id, canon_subject_id, canon_event_id, divergence_type,
                   description, caused_by_event_id, created_turn, active, resolved_turn
            FROM gm_divergences
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
        db.insertWithOnConflict("gm_divergences", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override suspend fun appendEvent(event: DurableCampaignEvent) {
        requireCampaign(event.campaignUid)
        val values = ContentValues().apply {
            put("event_id", event.eventUid.value)
            put("campaign_id", event.campaignUid.value)
            put("turn_id", resolveTurnUid(event.turnId))
            put("turn_number", event.turnId)
            put("sequence", event.sequence)
            put("chapter", resolveTurnChapter(event.turnId))
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
        db.insertOrThrow("gm_events", null, values)
    }

    override suspend fun applyMutation(mutation: DurableStateMutation) {
        requireCampaign(mutation.campaignUid)
        val current = readStateValue(mutation)
        if (mutation.oldValue != null) {
            require(current == mutation.oldValue) {
                "Konflikt Source of Truth dla ${mutation.entityUid}.${mutation.field}: " +
                    "oczekiwano '${mutation.oldValue}', baza zawiera '$current'."
            }
        }

        db.insertOrThrow(
            "gm_state_mutations",
            null,
            ContentValues().apply {
                put("mutation_id", mutation.mutationUid.value)
                put("campaign_id", mutation.campaignUid.value)
                put("turn_number", mutation.turnId)
                put("entity_type", mutation.entityType)
                put("entity_id", mutation.entityUid.value)
                put("field_key", mutation.field)
                put("operation", mutation.operation.name)
                mutation.oldValue?.let { put("old_value_json", it) }
                mutation.newValue?.let { put("new_value_json", it) }
                put("reason", mutation.reason)
                mutation.causedByEventUid?.let { put("caused_by_event_id", it.value) }
                put("created_at", System.currentTimeMillis())
            }
        )

        if (mutation.operation == MutationOperation.REMOVE && mutation.newValue == null) {
            db.delete(
                "gm_entity_state",
                "campaign_id=? AND entity_type=? AND entity_id=? AND field_key=?",
                arrayOf(campaignUid.value, mutation.entityType, mutation.entityUid.value, mutation.field)
            )
            return
        }

        val resolved = requireNotNull(mutation.newValue) {
            "Mutacja ${mutation.operation} wymaga resolved newValue."
        }
        db.insertWithOnConflict(
            "gm_entity_state",
            null,
            ContentValues().apply {
                put("campaign_id", campaignUid.value)
                put("entity_type", mutation.entityType)
                put("entity_id", mutation.entityUid.value)
                put("field_key", mutation.field)
                put("value_json", resolved)
                put("valid_from_turn", mutation.turnId)
                put("updated_at", System.currentTimeMillis())
                put("provenance_type", ProvenanceType.CAMPAIGN_EVENT.name)
                mutation.causedByEventUid?.let { put("provenance_id", it.value) }
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    override suspend fun writeTruth(truth: CampaignTruth) {
        val fromTurn = truth.validFromTurn ?: truth.provenance.turnId ?: currentTurnId(campaignUid)
        db.insertWithOnConflict(
            "gm_facts",
            null,
            ContentValues().apply {
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
                truth.provenance.turnId?.let { put("source_turn", it) }
                truth.provenance.canonStatus?.let { put("canon_status", it) }
                put("verified", if (truth.provenance.verified) 1 else 0)
                put("created_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    override suspend fun writeMemory(memory: DurableMemoryRecord) {
        requireCampaign(memory.campaignUid)
        db.insertWithOnConflict(
            "gm_memories",
            null,
            ContentValues().apply {
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
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
        memory.sourceEventUids.forEach { eventUid ->
            db.insertWithOnConflict(
                "gm_memory_event_links",
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
        db.insertWithOnConflict(
            "gm_chronicle_entries",
            null,
            ContentValues().apply {
                put("chronicle_id", entry.entryUid.value)
                put("campaign_id", campaignUid.value)
                put("turn_id", resolveTurnUid(entry.turnId))
                put("chapter", entry.chapter)
                put("title", entry.title)
                put("summary", entry.summary)
                put("created_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
        entry.eventUids.forEach { eventUid ->
            db.insertWithOnConflict(
                "gm_chronicle_event_links",
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
        db.rawQuery(
            """
            SELECT snapshot_id, turn_number, event_sequence, created_at
            FROM gm_snapshots WHERE campaign_id=?
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
        require(transactionDepth == 0 && !db.inTransaction()) {
            "Snapshotu nie wolno tworzyć wewnątrz aktywnej transakcji."
        }
        val current = currentTurnId(campaignUid)
        require(throughTurnId in 0..current) { "Niepoprawny zakres snapshotu: $throughTurnId / $current" }

        db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
        val snapshotUid = EntityUid("SNAP-${UUID.randomUUID()}")
        val campaignFile = File(db.path)
        val dir = File(campaignFile.parentFile, "snapshots").apply { mkdirs() }
        val target = File(dir, "${snapshotUid.value}.db")
        campaignFile.copyTo(target, overwrite = true)
        val hash = sha256(target)
        val eventSequence = eventCountThrough(throughTurnId)
        val now = System.currentTimeMillis()

        db.insertOrThrow(
            "gm_snapshots",
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
            "UPDATE gm_campaign_meta SET current_snapshot_id=?, updated_at=? WHERE campaign_id=?",
            arrayOf(snapshotUid.value, now, campaignUid.value)
        )
        return CampaignSnapshotRef(snapshotUid, campaignUid, throughTurnId, eventSequence, now)
    }

    override suspend fun <T> inTransaction(block: suspend UnifiedCampaignRepository.() -> T): T {
        check(transactionDepth == 0 && !db.inTransaction()) {
            "Zagnieżdżone transakcje UnifiedCampaignRepository nie są obsługiwane."
        }
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

    override fun close() {
        if (ownsDatabase && db.isOpen) db.close()
    }

    private fun readTruth(c: Cursor): CampaignTruth = CampaignTruth(
        uid = EntityUid(c.getString(0)),
        kind = TruthKind.valueOf(c.getString(1)),
        subjectUid = c.getString(2)?.let(::EntityUid),
        predicate = c.getString(3),
        value = c.getString(4),
        holderUid = c.getString(5)?.let(::EntityUid),
        validFromTurn = c.getLong(6),
        validUntilTurn = if (c.isNull(7)) null else c.getLong(7),
        provenance = ProvenanceRecord(
            type = ProvenanceType.valueOf(c.getString(8)),
            sourceUid = c.getString(9)?.let(::EntityUid),
            turnId = if (c.isNull(10)) null else c.getLong(10),
            confidence = c.getDouble(11),
            canonStatus = c.getString(12),
            verified = c.getInt(13) != 0
        )
    )

    private fun memoryEventUids(memoryUid: EntityUid): Set<EntityUid> {
        val out = linkedSetOf<EntityUid>()
        db.rawQuery(
            "SELECT event_id FROM gm_memory_event_links WHERE memory_id=? ORDER BY event_id",
            arrayOf(memoryUid.value)
        ).use { c -> while (c.moveToNext()) out += EntityUid(c.getString(0)) }
        return out
    }

    private fun jsonStringSet(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return runCatching {
            val array = JSONArray(raw)
            buildSet {
                for (i in 0 until array.length()) add(array.getString(i))
            }
        }.getOrElse { emptySet() }
    }

    private fun ensureMeta() {
        val now = System.currentTimeMillis()
        db.insertWithOnConflict(
            "gm_campaign_meta",
            null,
            ContentValues().apply {
                put("campaign_id", campaignUid.value)
                put("world_pack_id", worldPackUid.value)
                put("engine_version_code", BuildConfig.VERSION_CODE)
                put("campaign_schema_version", CampaignSourceOfTruthSchema.SCHEMA_VERSION)
                put("event_schema_version", CampaignSourceOfTruthSchema.EVENT_SCHEMA_VERSION)
                put("memory_schema_version", CampaignSourceOfTruthSchema.MEMORY_SCHEMA_VERSION)
                put("created_at", now)
                put("updated_at", now)
                put("current_turn", 0)
                put("current_chapter", 0)
            },
            SQLiteDatabase.CONFLICT_IGNORE
        )
        db.execSQL(
            """
            UPDATE gm_campaign_meta
            SET world_pack_id=?, engine_version_code=?,
                campaign_schema_version=?, event_schema_version=?, memory_schema_version=?, updated_at=?
            WHERE campaign_id=?
            """.trimIndent(),
            arrayOf(
                worldPackUid.value,
                BuildConfig.VERSION_CODE,
                CampaignSourceOfTruthSchema.SCHEMA_VERSION,
                CampaignSourceOfTruthSchema.EVENT_SCHEMA_VERSION,
                CampaignSourceOfTruthSchema.MEMORY_SCHEMA_VERSION,
                now,
                campaignUid.value
            )
        )
    }

    private fun requireCampaign(uid: EntityUid) {
        require(uid == campaignUid) {
            "Repozytorium ${campaignUid.value} nie może obsłużyć kampanii ${uid.value}."
        }
    }

    private fun resolveTurnUid(turnId: Long): String {
        db.rawQuery(
            "SELECT turn_id FROM gm_turns WHERE campaign_id=? AND turn_number=?",
            arrayOf(campaignUid.value, turnId.toString())
        ).use { c ->
            require(c.moveToFirst()) { "Brak rekordu tury $turnId przed zapisem zależnych danych." }
            return c.getString(0)
        }
    }

    private fun resolveTurnChapter(turnId: Long): Long {
        db.rawQuery(
            "SELECT chapter FROM gm_turns WHERE campaign_id=? AND turn_number=?",
            arrayOf(campaignUid.value, turnId.toString())
        ).use { c ->
            require(c.moveToFirst()) { "Brak rekordu tury $turnId." }
            return c.getLong(0)
        }
    }

    private fun readStateValue(mutation: DurableStateMutation): String? {
        db.rawQuery(
            """
            SELECT value_json FROM gm_entity_state
            WHERE campaign_id=? AND entity_type=? AND entity_id=? AND field_key=?
            """.trimIndent(),
            arrayOf(campaignUid.value, mutation.entityType, mutation.entityUid.value, mutation.field)
        ).use { c -> return if (c.moveToFirst()) c.getString(0) else null }
    }

    private fun eventCountThrough(throughTurnId: Long): Long {
        db.rawQuery(
            "SELECT COUNT(*) FROM gm_events WHERE campaign_id=? AND turn_number<=?",
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
