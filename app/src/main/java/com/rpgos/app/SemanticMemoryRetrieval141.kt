package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

/**
 * Temporal eligibility boundary for SEMANTIC memory retrieval.
 *
 * Semantic memory is derivative. It remains durable after its source fact is
 * superseded, but it is eligible for a context at [atTurnId] only while every
 * linked SOURCE truth is a verified FACT valid at that turn.
 */
interface SemanticMemoryTemporalEligibility141 {
    fun isEligible(memoryUid: EntityUid, atTurnId: Long): Boolean
}

class SQLiteSemanticMemoryTemporalEligibility141(
    private val db: SQLiteDatabase,
    private val campaignUid: EntityUid
) : SemanticMemoryTemporalEligibility141 {
    override fun isEligible(memoryUid: EntityUid, atTurnId: Long): Boolean {
        require(atTurnId >= 0L) { "atTurnId nie może być ujemny." }

        val sourceCount = db.rawQuery(
            """
            SELECT COUNT(*)
            FROM gm_memory_truth_links l
            JOIN gm_memories m ON m.memory_id=l.memory_id
            WHERE l.memory_id=? AND l.link_role='SOURCE'
              AND m.campaign_id=? AND m.memory_kind='SEMANTIC'
            """.trimIndent(),
            arrayOf(memoryUid.value, campaignUid.value)
        ).use { c -> c.moveToFirst(); c.getInt(0) }
        if (sourceCount <= 0) return false

        val invalidCount = db.rawQuery(
            """
            SELECT COUNT(*)
            FROM gm_memory_truth_links l
            JOIN gm_memories m ON m.memory_id=l.memory_id
            LEFT JOIN gm_facts f ON f.fact_id=l.truth_id
            WHERE l.memory_id=? AND l.link_role='SOURCE'
              AND m.campaign_id=? AND (
                f.fact_id IS NULL OR
                f.campaign_id!=? OR
                f.truth_kind!='FACT' OR
                f.verified!=1 OR
                f.valid_from_turn>? OR
                (f.valid_until_turn IS NOT NULL AND f.valid_until_turn<?)
              )
            """.trimIndent(),
            arrayOf(
                memoryUid.value,
                campaignUid.value,
                campaignUid.value,
                atTurnId.toString(),
                atTurnId.toString()
            )
        ).use { c -> c.moveToFirst(); c.getInt(0) }

        return invalidCount == 0
    }
}
