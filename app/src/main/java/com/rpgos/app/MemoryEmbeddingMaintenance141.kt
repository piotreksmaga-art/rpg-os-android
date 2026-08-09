package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray

data class EmbeddingSpace141(
    val provider: String,
    val model: String,
    val dimensions: Int
) {
    init {
        require(provider.isNotBlank()) { "Embedding provider nie może być pusty." }
        require(model.isNotBlank()) { "Embedding model nie może być pusty." }
        require(dimensions > 0) { "Embedding dimensions musi być > 0." }
    }
}

fun interface BatchMemoryEmbeddingProvider141 {
    suspend fun embed(texts: List<String>, space: EmbeddingSpace141): List<EmbeddingVector141>
}

/**
 * Incremental derived-index maintenance for long campaigns.
 *
 * It never changes truth/memory records. Only rows missing in one explicit
 * provider/model/dimension space are refreshed. MemoryEmbeddingIndexSchema141
 * invalidates derived rows automatically when memory content changes, therefore
 * a changed memory becomes a missing row again without an unbounded stale scan.
 */
class MemoryEmbeddingMaintenance141(
    private val db: SQLiteDatabase,
    private val campaignUid: EntityUid,
    private val space: EmbeddingSpace141,
    private val provider: BatchMemoryEmbeddingProvider141
) {
    data class Result(
        val throughTurnId: Long,
        val candidates: Int,
        val indexed: Int
    )

    init {
        MemoryEmbeddingIndexSchema141.ensure(db)
    }

    suspend fun refresh(
        throughTurnId: Long,
        batchLimit: Int = 32
    ): Result {
        require(throughTurnId >= 0L) { "throughTurnId nie może być ujemny." }
        require(batchLimit in 1..256) { "batchLimit musi mieścić się w zakresie 1..256." }

        val candidates = readCandidates(throughTurnId, batchLimit)
        if (candidates.isEmpty()) return Result(throughTurnId, 0, 0)

        val vectors = provider.embed(
            candidates.map(::embeddingText),
            space
        )
        require(vectors.size == candidates.size) {
            "EMBEDDING_BATCH_SIZE_MISMATCH: provider zwrócił ${vectors.size}, oczekiwano ${candidates.size}."
        }
        vectors.forEachIndexed { index, vector ->
            require(
                vector.provider == space.provider &&
                    vector.model == space.model &&
                    vector.dimensions == space.dimensions
            ) {
                "EMBEDDING_SPACE_MISMATCH[$index]: ${vector.provider}/${vector.model}/${vector.dimensions} != " +
                    "${space.provider}/${space.model}/${space.dimensions}."
            }
        }

        val index = SQLiteMemoryEmbeddingIndex141(db, campaignUid)
        val ownsTransaction = !db.inTransaction()
        if (ownsTransaction) db.beginTransaction()
        try {
            candidates.zip(vectors).forEach { (memory, vector) -> index.upsert(memory, vector) }
            if (ownsTransaction) db.setTransactionSuccessful()
        } finally {
            if (ownsTransaction) db.endTransaction()
        }

        return Result(throughTurnId, candidates.size, candidates.size)
    }

    private fun readCandidates(throughTurnId: Long, limit: Int): List<DurableMemoryRecord> {
        val out = mutableListOf<DurableMemoryRecord>()
        db.rawQuery(
            """
            SELECT m.memory_id,m.memory_kind,m.subject_id,m.text,m.importance,m.first_turn,m.tags_json
            FROM gm_memories m
            LEFT JOIN gm_memory_embeddings e
              ON e.memory_id=m.memory_id
             AND e.campaign_id=m.campaign_id
             AND e.provider=? AND e.model=? AND e.dimensions=?
            WHERE m.campaign_id=? AND m.archived=0 AND m.first_turn<=?
              AND e.memory_id IS NULL
            ORDER BY m.first_turn,m.memory_id
            LIMIT ?
            """.trimIndent(),
            arrayOf(
                space.provider,
                space.model,
                space.dimensions.toString(),
                campaignUid.value,
                throughTurnId.toString(),
                limit.toString()
            )
        ).use { c ->
            while (c.moveToNext()) {
                out += DurableMemoryRecord(
                    memoryUid = EntityUid(c.getString(0)),
                    campaignUid = campaignUid,
                    kind = DurableMemoryKind.valueOf(c.getString(1)),
                    subjectUid = if (c.isNull(2)) null else EntityUid(c.getString(2)),
                    text = c.getString(3),
                    importance = c.getDouble(4),
                    createdTurn = c.getLong(5),
                    sourceEventUids = emptySet(),
                    tags = parseTags(c.getString(6))
                )
            }
        }
        return out
    }

    internal fun embeddingText(memory: DurableMemoryRecord): String = buildString {
        append(memory.kind.name).append('\n')
        memory.subjectUid?.let { append("subject:").append(it.value).append('\n') }
        append(memory.text)
        if (memory.tags.isNotEmpty()) {
            append('\n').append("tags:").append(memory.tags.sorted().joinToString(","))
        }
    }

    private fun parseTags(raw: String): Set<String> = runCatching {
        val array = JSONArray(raw)
        buildSet { for (i in 0 until array.length()) add(array.getString(i)) }
    }.getOrDefault(emptySet())
}
