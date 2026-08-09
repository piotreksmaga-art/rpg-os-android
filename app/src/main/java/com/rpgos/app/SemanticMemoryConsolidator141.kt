package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest

/**
 * Deterministic semantic consolidation over verified objective FACTs.
 *
 * The consolidator never asks the model to summarize campaign truth. It emits a
 * lossless structured rendering of an already accepted FACT and persists it
 * through [SemanticMemoryStore141], which records the exact source truth UID.
 * Only FACTs valid at [throughTurnId] are candidates. Historical/superseded
 * semantic memories remain durable but retrieval eligibility decides whether
 * they may enter a later ContextBundle.
 */
class SemanticMemoryConsolidator141(
    private val db: SQLiteDatabase,
    private val campaignUid: EntityUid,
    private val semanticStore: SemanticMemoryStore141
) {
    data class Result(
        val throughTurnId: Long,
        val scannedCandidates: Int,
        val createdMemories: Int
    )

    fun consolidate(
        throughTurnId: Long,
        factLimit: Int = DEFAULT_FACT_LIMIT
    ): Result {
        require(throughTurnId >= 0L) { "throughTurnId nie może być ujemny." }
        require(factLimit >= 1) { "factLimit musi być >= 1." }

        val candidates = readCandidates(throughTurnId, factLimit)
        var created = 0
        candidates.forEach { fact ->
            val memory = DurableMemoryRecord(
                memoryUid = memoryUidFor(fact.uid),
                campaignUid = campaignUid,
                kind = DurableMemoryKind.SEMANTIC,
                subjectUid = fact.subjectUid,
                text = renderFact(fact),
                importance = importanceFor(fact.provenance.type),
                createdTurn = requireNotNull(fact.validFromTurn) {
                    "Verified FACT ${fact.uid.value} nie ma validFromTurn."
                },
                tags = buildSet {
                    add(AUTO_TAG)
                    add("predicate:${fact.predicate}")
                    add("truth:${fact.uid.value}")
                    add("source_type:${fact.provenance.type.name.lowercase()}")
                }
            )
            semanticStore.writeFromVerifiedFact(memory, fact.uid)
            created++
        }

        return Result(
            throughTurnId = throughTurnId,
            scannedCandidates = candidates.size,
            createdMemories = created
        )
    }

    internal fun memoryUidFor(truthUid: EntityUid): EntityUid {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$ALGORITHM_VERSION|${campaignUid.value}|${truthUid.value}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)
        return EntityUid("MEM-SEM-$ALGORITHM_VERSION-$digest")
    }

    private fun readCandidates(throughTurnId: Long, limit: Int): List<CampaignTruth> {
        val out = mutableListOf<CampaignTruth>()
        db.rawQuery(
            """
            SELECT f.fact_id,f.subject_id,f.predicate,f.object_json,
                   f.valid_from_turn,f.valid_until_turn,f.source_type,f.source_id,
                   f.source_turn,f.confidence,f.canon_status,f.verified
            FROM gm_facts f
            WHERE f.campaign_id=?
              AND f.truth_kind='FACT'
              AND f.verified=1
              AND f.valid_from_turn<=?
              AND (f.valid_until_turn IS NULL OR f.valid_until_turn>=?)
              AND NOT EXISTS (
                  SELECT 1
                  FROM gm_memory_truth_links l
                  JOIN gm_memories m ON m.memory_id=l.memory_id
                  WHERE l.truth_id=f.fact_id
                    AND l.link_role='SOURCE'
                    AND m.campaign_id=f.campaign_id
                    AND m.memory_kind='SEMANTIC'
              )
            ORDER BY f.valid_from_turn ASC, f.fact_id ASC
            LIMIT ?
            """.trimIndent(),
            arrayOf(
                campaignUid.value,
                throughTurnId.toString(),
                throughTurnId.toString(),
                limit.toString()
            )
        ).use { c ->
            while (c.moveToNext()) {
                out += CampaignTruth(
                    uid = EntityUid(c.getString(0)),
                    kind = TruthKind.FACT,
                    subjectUid = if (c.isNull(1)) null else EntityUid(c.getString(1)),
                    predicate = c.getString(2),
                    value = c.getString(3),
                    validFromTurn = c.getLong(4),
                    validUntilTurn = if (c.isNull(5)) null else c.getLong(5),
                    provenance = ProvenanceRecord(
                        type = ProvenanceType.valueOf(c.getString(6)),
                        sourceUid = if (c.isNull(7)) null else EntityUid(c.getString(7)),
                        turnId = if (c.isNull(8)) null else c.getLong(8),
                        confidence = c.getDouble(9),
                        canonStatus = if (c.isNull(10)) null else c.getString(10),
                        verified = c.getInt(11) != 0
                    )
                )
            }
        }
        return out
    }

    private fun renderFact(fact: CampaignTruth): String = buildString {
        append(fact.subjectUid?.value ?: "CAMPAIGN")
        append(" | ")
        append(fact.predicate)
        append(" = ")
        append(fact.value)
    }

    private fun importanceFor(type: ProvenanceType): Double = when (type) {
        ProvenanceType.WORLD_CANON,
        ProvenanceType.PLAYER_STATE,
        ProvenanceType.CAMPAIGN_EVENT -> 0.90

        ProvenanceType.SYSTEM_SIMULATION,
        ProvenanceType.GM_INFERENCE -> 0.85

        ProvenanceType.IMPORTED_CONTENT -> 0.80

        // These provenance kinds normally belong to BELIEF, but keeping the
        // mapping total makes imported/legacy verified FACT rows deterministic.
        ProvenanceType.NPC_OBSERVATION,
        ProvenanceType.NPC_REPORT,
        ProvenanceType.NPC_RESEARCH,
        ProvenanceType.NPC_INFERENCE,
        ProvenanceType.ORGANIZATION_REPORT,
        ProvenanceType.PLAYER_CLAIM -> 0.75
    }

    companion object {
        private const val ALGORITHM_VERSION = "v1"
        private const val AUTO_TAG = "auto:semantic:v1"
        private const val DEFAULT_FACT_LIMIT = 200
    }
}
