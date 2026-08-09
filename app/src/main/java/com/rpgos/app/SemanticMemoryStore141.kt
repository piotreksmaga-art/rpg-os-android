package com.rpgos.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray

interface SemanticMemoryStore141 : SemanticMemoryProvenance141 {
    fun writeFromVerifiedFact(memory: DurableMemoryRecord, sourceTruthUid: EntityUid)
}

/**
 * Durable SEMANTIC memory writer.
 *
 * A semantic memory is derivative knowledge, never a new source of truth. It can
 * only be persisted from a verified FACT that belongs to this campaign and was
 * temporally valid when the memory was created. The memory row and its FACT
 * provenance link are written atomically in campaign.db.
 */
class SQLiteSemanticMemoryStore141(
    private val db: SQLiteDatabase,
    private val campaignUid: EntityUid
) : SemanticMemoryStore141 {
    private val provenance = SQLiteSemanticMemoryProvenance141(db, campaignUid)

    override fun sourceTruthUids(memoryUid: EntityUid): Set<EntityUid> =
        provenance.sourceTruthUids(memoryUid)

    override fun writeFromVerifiedFact(memory: DurableMemoryRecord, sourceTruthUid: EntityUid) {
        require(memory.campaignUid == campaignUid) { "Semantic memory należy do innej kampanii." }
        require(memory.kind == DurableMemoryKind.SEMANTIC) { "Writer przyjmuje wyłącznie SEMANTIC memory." }
        require(memory.text.isNotBlank()) { "Semantic memory nie może mieć pustej treści." }
        require(memory.importance in 0.0..1.0) { "importance musi mieścić się w zakresie 0..1." }
        require(memory.createdTurn >= 0L) { "createdTurn nie może być ujemny." }
        require(memory.sourceEventUids.isEmpty()) {
            "SEMANTIC memory korzysta z truth provenance; event provenance należy do EPISODIC memory."
        }

        val truth = readSourceFact(sourceTruthUid)
        require(truth.kind == TruthKind.FACT) { "SEMANTIC memory może pochodzić wyłącznie z FACT." }
        require(truth.provenance.verified) { "SEMANTIC memory wymaga verified FACT provenance." }
        val from = truth.validFromTurn ?: truth.provenance.turnId ?: 0L
        val until = truth.validUntilTurn
        require(memory.createdTurn >= from && (until == null || memory.createdTurn <= until)) {
            "Źródłowy FACT nie obowiązywał w createdTurn=${memory.createdTurn}."
        }

        val ownsTransaction = !db.inTransaction()
        if (ownsTransaction) db.beginTransaction()
        try {
            val existing = memoryRow(memory.memoryUid)
            if (existing == null) {
                db.insertOrThrow(
                    "gm_memories",
                    null,
                    ContentValues().apply {
                        put("memory_id", memory.memoryUid.value)
                        put("campaign_id", campaignUid.value)
                        put("memory_kind", DurableMemoryKind.SEMANTIC.name)
                        memory.subjectUid?.let { put("subject_id", it.value) }
                        put("text", memory.text)
                        put("importance", memory.importance)
                        put("confidence", truth.provenance.confidence)
                        put("first_turn", memory.createdTurn)
                        put("last_reinforced_turn", memory.createdTurn)
                        put("tags_json", JSONArray(memory.tags.sorted()).toString())
                        put("archived", 0)
                    }
                )
            } else {
                require(existing == memory) {
                    "SEMANTIC_MEMORY_UID_CONFLICT: ${memory.memoryUid.value} wskazuje inny rekord."
                }
            }

            db.insertWithOnConflict(
                "gm_memory_truth_links",
                null,
                ContentValues().apply {
                    put("memory_id", memory.memoryUid.value)
                    put("truth_id", sourceTruthUid.value)
                    put("link_role", "SOURCE")
                },
                SQLiteDatabase.CONFLICT_IGNORE
            )
            if (ownsTransaction) db.setTransactionSuccessful()
        } finally {
            if (ownsTransaction) db.endTransaction()
        }
    }

    private fun readSourceFact(uid: EntityUid): CampaignTruth {
        db.rawQuery(
            """
            SELECT fact_id, truth_kind, subject_id, predicate, object_json, holder_id,
                   valid_from_turn, valid_until_turn, source_type, source_id, source_turn,
                   confidence, canon_status, verified
            FROM gm_facts
            WHERE campaign_id=? AND fact_id=?
            LIMIT 1
            """.trimIndent(),
            arrayOf(campaignUid.value, uid.value)
        ).use { c ->
            require(c.moveToFirst()) { "Brak źródłowego FACT ${uid.value} w aktywnej kampanii." }
            return CampaignTruth(
                uid = EntityUid(c.getString(0)),
                kind = TruthKind.valueOf(c.getString(1)),
                subjectUid = if (c.isNull(2)) null else EntityUid(c.getString(2)),
                predicate = c.getString(3),
                value = c.getString(4),
                holderUid = if (c.isNull(5)) null else EntityUid(c.getString(5)),
                validFromTurn = c.getLong(6),
                validUntilTurn = if (c.isNull(7)) null else c.getLong(7),
                provenance = ProvenanceRecord(
                    type = ProvenanceType.valueOf(c.getString(8)),
                    sourceUid = if (c.isNull(9)) null else EntityUid(c.getString(9)),
                    turnId = if (c.isNull(10)) null else c.getLong(10),
                    confidence = c.getDouble(11),
                    canonStatus = if (c.isNull(12)) null else c.getString(12),
                    verified = c.getInt(13) != 0
                )
            )
        }
    }

    private fun memoryRow(uid: EntityUid): DurableMemoryRecord? {
        db.rawQuery(
            """
            SELECT memory_kind,subject_id,text,importance,first_turn,tags_json
            FROM gm_memories WHERE campaign_id=? AND memory_id=? LIMIT 1
            """.trimIndent(),
            arrayOf(campaignUid.value, uid.value)
        ).use { c ->
            if (!c.moveToFirst()) return null
            val tags = runCatching {
                val array = JSONArray(c.getString(5))
                buildSet { for (i in 0 until array.length()) add(array.getString(i)) }
            }.getOrDefault(emptySet())
            return DurableMemoryRecord(
                memoryUid = uid,
                campaignUid = campaignUid,
                kind = DurableMemoryKind.valueOf(c.getString(0)),
                subjectUid = if (c.isNull(1)) null else EntityUid(c.getString(1)),
                text = c.getString(2),
                importance = c.getDouble(3),
                createdTurn = c.getLong(4),
                tags = tags
            )
        }
    }
}

/** Fail-closed integrity boundary for semantic-memory provenance. */
class SemanticMemoryIntegrity141(
    private val db: SQLiteDatabase,
    private val campaignUid: EntityUid
) {
    data class Issue(val code: String, val count: Int)

    fun issues(): List<Issue> {
        if (!tableExists("gm_memory_truth_links")) return listOf(Issue("MEMORY_TRUTH_SCHEMA_MISSING", 1))
        val out = mutableListOf<Issue>()
        add(out, "SEMANTIC_MEMORY_WITHOUT_TRUTH", """
            SELECT COUNT(*) FROM gm_memories m
            LEFT JOIN gm_memory_truth_links l ON l.memory_id=m.memory_id AND l.link_role='SOURCE'
            WHERE m.campaign_id=? AND m.memory_kind='SEMANTIC' AND l.truth_id IS NULL
        """.trimIndent())
        add(out, "SEMANTIC_MEMORY_SOURCE_NOT_FACT", """
            SELECT COUNT(*) FROM gm_memory_truth_links l
            JOIN gm_memories m ON m.memory_id=l.memory_id
            LEFT JOIN gm_facts f ON f.fact_id=l.truth_id
            WHERE m.campaign_id=? AND m.memory_kind='SEMANTIC'
              AND (f.fact_id IS NULL OR f.campaign_id!=m.campaign_id OR f.truth_kind!='FACT')
        """.trimIndent())
        add(out, "SEMANTIC_MEMORY_SOURCE_UNVERIFIED", """
            SELECT COUNT(*) FROM gm_memory_truth_links l
            JOIN gm_memories m ON m.memory_id=l.memory_id
            JOIN gm_facts f ON f.fact_id=l.truth_id
            WHERE m.campaign_id=? AND m.memory_kind='SEMANTIC' AND f.verified!=1
        """.trimIndent())
        add(out, "SEMANTIC_MEMORY_SOURCE_NOT_VALID_AT_CREATION", """
            SELECT COUNT(*) FROM gm_memory_truth_links l
            JOIN gm_memories m ON m.memory_id=l.memory_id
            JOIN gm_facts f ON f.fact_id=l.truth_id
            WHERE m.campaign_id=? AND m.memory_kind='SEMANTIC'
              AND (f.valid_from_turn>m.first_turn OR
                   (f.valid_until_turn IS NOT NULL AND f.valid_until_turn<m.first_turn))
        """.trimIndent())
        return out
    }

    fun requireHealthy(boundary: String) {
        val found = issues()
        require(found.isEmpty()) {
            "$boundary semantic memory integrity blocked: " +
                found.joinToString { "${it.code}=${it.count}" }
        }
    }

    private fun add(out: MutableList<Issue>, code: String, sql: String) {
        val count = db.rawQuery(sql, arrayOf(campaignUid.value)).use { c ->
            c.moveToFirst(); c.getInt(0)
        }
        if (count > 0) out += Issue(code, count)
    }

    private fun tableExists(name: String): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
        arrayOf(name)
    ).use { it.moveToFirst() }
}
