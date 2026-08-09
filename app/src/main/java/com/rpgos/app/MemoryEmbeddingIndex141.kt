package com.rpgos.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import java.security.MessageDigest
import kotlin.math.sqrt

data class EmbeddingVector141(
    val provider: String,
    val model: String,
    val values: List<Double>
) {
    init {
        require(provider.isNotBlank()) { "Embedding provider nie może być pusty." }
        require(model.isNotBlank()) { "Embedding model nie może być pusty." }
        require(values.isNotEmpty()) { "Embedding vector nie może być pusty." }
        require(values.all { it.isFinite() }) { "Embedding vector zawiera NaN/Infinity." }
    }

    val dimensions: Int get() = values.size
}

fun interface QueryEmbeddingProvider141 {
    suspend fun embed(text: String): EmbeddingVector141
}

internal object MemoryEmbeddingFingerprint141 {
    fun hash(memory: DurableMemoryRecord): String {
        val canonical = buildString {
            append(memory.kind.name).append('|')
            append(memory.subjectUid?.value.orEmpty()).append('|')
            append(memory.text).append('|')
            append(memory.tags.sorted().joinToString("\u001f"))
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

object MemoryEmbeddingIndexSchema141 {
    const val MIGRATION_ID = "GM-141-MEMORY-EMBEDDING-INDEX-V1"

    fun ensure(db: SQLiteDatabase) {
        val ownsTransaction = !db.inTransaction()
        if (ownsTransaction) db.beginTransaction()
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS rpgos_schema_migrations(
                    migration_id TEXT PRIMARY KEY,
                    applied_at INTEGER NOT NULL,
                    notes TEXT
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS gm_memory_embeddings(
                    memory_id TEXT NOT NULL,
                    campaign_id TEXT NOT NULL,
                    provider TEXT NOT NULL,
                    model TEXT NOT NULL,
                    dimensions INTEGER NOT NULL CHECK(dimensions > 0),
                    content_hash TEXT NOT NULL,
                    vector_json TEXT NOT NULL,
                    indexed_at INTEGER NOT NULL,
                    PRIMARY KEY(memory_id, provider, model, dimensions),
                    FOREIGN KEY(memory_id) REFERENCES gm_memories(memory_id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS idx_gm_memory_embeddings_space
                ON gm_memory_embeddings(campaign_id, provider, model, dimensions)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes)
                VALUES(?,?,?)
                """.trimIndent(),
                arrayOf(
                    MIGRATION_ID,
                    System.currentTimeMillis(),
                    "GM141 provider/model/dimension-versioned memory embedding index"
                )
            )
            if (ownsTransaction) db.setTransactionSuccessful()
        } finally {
            if (ownsTransaction) db.endTransaction()
        }
    }
}

class SQLiteMemoryEmbeddingIndex141(
    private val db: SQLiteDatabase,
    private val campaignUid: EntityUid
) {
    init {
        MemoryEmbeddingIndexSchema141.ensure(db)
    }

    fun upsert(memory: DurableMemoryRecord, vector: EmbeddingVector141) {
        require(memory.campaignUid == campaignUid) { "Embedding memory należy do innej kampanii." }
        require(memoryExists(memory.memoryUid)) { "Brak durable memory ${memory.memoryUid.value}." }

        db.insertWithOnConflict(
            "gm_memory_embeddings",
            null,
            ContentValues().apply {
                put("memory_id", memory.memoryUid.value)
                put("campaign_id", campaignUid.value)
                put("provider", vector.provider)
                put("model", vector.model)
                put("dimensions", vector.dimensions)
                put("content_hash", MemoryEmbeddingFingerprint141.hash(memory))
                put("vector_json", JSONArray(vector.values).toString())
                put("indexed_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun memoryExists(memoryUid: EntityUid): Boolean = db.rawQuery(
        "SELECT 1 FROM gm_memories WHERE campaign_id=? AND memory_id=? LIMIT 1",
        arrayOf(campaignUid.value, memoryUid.value)
    ).use { it.moveToFirst() }
}

/**
 * Exact cosine implementation for the first local index generation.
 *
 * This deliberately favors correctness/auditability over ANN complexity. A future
 * ANN implementation can replace only this candidate provider while keeping the
 * same HybridMemoryCandidateProvider141 trust boundary in GameMasterRetriever141.
 */
class SQLiteExactCosineMemoryCandidateProvider141(
    private val db: SQLiteDatabase,
    private val campaignUid: EntityUid,
    private val queryEmbeddingProvider: QueryEmbeddingProvider141,
    private val maxIndexedRows: Int = 20_000
) : HybridMemoryCandidateProvider141 {
    init {
        require(maxIndexedRows >= 1) { "maxIndexedRows musi być >= 1." }
        MemoryEmbeddingIndexSchema141.ensure(db)
    }

    override suspend fun candidates(
        campaignUid: EntityUid,
        query: String,
        atTurnId: Long,
        limit: Int
    ): List<HybridMemoryCandidate141> {
        require(campaignUid == this.campaignUid) { "Hybrid query należy do innej kampanii." }
        require(atTurnId >= 0L) { "atTurnId nie może być ujemny." }
        if (limit <= 0 || query.isBlank()) return emptyList()

        val queryVector = queryEmbeddingProvider.embed(query)
        val scored = mutableListOf<HybridMemoryCandidate141>()
        db.rawQuery(
            """
            SELECT e.memory_id,e.content_hash,e.vector_json,
                   m.memory_kind,m.subject_id,m.text,m.importance,m.first_turn,m.tags_json
            FROM gm_memory_embeddings e
            JOIN gm_memories m ON m.memory_id=e.memory_id
            WHERE e.campaign_id=? AND m.campaign_id=?
              AND e.provider=? AND e.model=? AND e.dimensions=?
              AND m.archived=0 AND m.first_turn<=?
            ORDER BY e.memory_id
            LIMIT ?
            """.trimIndent(),
            arrayOf(
                campaignUid.value,
                campaignUid.value,
                queryVector.provider,
                queryVector.model,
                queryVector.dimensions.toString(),
                atTurnId.toString(),
                maxIndexedRows.toString()
            )
        ).use { c ->
            while (c.moveToNext()) {
                val memory = DurableMemoryRecord(
                    memoryUid = EntityUid(c.getString(0)),
                    campaignUid = campaignUid,
                    kind = DurableMemoryKind.valueOf(c.getString(3)),
                    subjectUid = if (c.isNull(4)) null else EntityUid(c.getString(4)),
                    text = c.getString(5),
                    importance = c.getDouble(6),
                    createdTurn = c.getLong(7),
                    sourceEventUids = emptySet(),
                    tags = parseTags(c.getString(8))
                )
                // Stale embeddings never become candidates after memory content changes.
                if (c.getString(1) != MemoryEmbeddingFingerprint141.hash(memory)) continue
                val values = parseVector(c.getString(2))
                if (values.size != queryVector.dimensions) continue
                val similarity = ((cosine(queryVector.values, values) + 1.0) / 2.0).coerceIn(0.0, 1.0)
                scored += HybridMemoryCandidate141(memory, similarity)
            }
        }

        return scored
            .sortedByDescending { it.similarity }
            .take(limit)
    }

    private fun parseVector(raw: String): List<Double> = runCatching {
        val array = JSONArray(raw)
        List(array.length()) { index -> array.getDouble(index) }
    }.getOrDefault(emptyList())

    private fun parseTags(raw: String): Set<String> = runCatching {
        val array = JSONArray(raw)
        buildSet { for (i in 0 until array.length()) add(array.getString(i)) }
    }.getOrDefault(emptySet())

    private fun cosine(a: List<Double>, b: List<Double>): Double {
        if (a.size != b.size || a.isEmpty()) return -1.0
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA <= 0.0 || normB <= 0.0) return -1.0
        return dot / (sqrt(normA) * sqrt(normB))
    }
}
