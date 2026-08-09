package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

/**
 * Memory schema v2: durable SEMANTIC memories can point back to exact FACT UIDs.
 *
 * This migration is additive and remains inside campaign.db. It deliberately
 * does not make semantic memories eligible for retrieval yet; point 15 of the
 * architecture will apply temporal truth validation before exposing them.
 */
object SemanticMemoryProvenanceSchema141 {
    const val MEMORY_SCHEMA_VERSION = 2
    const val MIGRATION_ID = "GM-141-MEMORY-TRUTH-PROVENANCE-V2"

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
                CREATE TABLE IF NOT EXISTS gm_memory_truth_links(
                    memory_id TEXT NOT NULL,
                    truth_id TEXT NOT NULL,
                    link_role TEXT NOT NULL DEFAULT 'SOURCE',
                    PRIMARY KEY(memory_id, truth_id, link_role),
                    FOREIGN KEY(memory_id) REFERENCES gm_memories(memory_id) ON DELETE CASCADE,
                    FOREIGN KEY(truth_id) REFERENCES gm_facts(fact_id) ON DELETE RESTRICT
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_gm_memory_truth_links_truth ON gm_memory_truth_links(truth_id)"
            )
            db.execSQL(
                """
                INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes)
                VALUES(?,?,?)
                """.trimIndent(),
                arrayOf(
                    MIGRATION_ID,
                    System.currentTimeMillis(),
                    "GM Engine 141 semantic memory -> verified FACT provenance links"
                )
            )
            db.execSQL(
                """
                UPDATE gm_campaign_meta
                SET memory_schema_version = CASE
                    WHEN memory_schema_version < ? THEN ? ELSE memory_schema_version END,
                    updated_at = ?
                """.trimIndent(),
                arrayOf(MEMORY_SCHEMA_VERSION, MEMORY_SCHEMA_VERSION, System.currentTimeMillis())
            )
            if (ownsTransaction) db.setTransactionSuccessful()
        } finally {
            if (ownsTransaction) db.endTransaction()
        }
    }
}

/** Read-only provenance capability used by future temporal semantic retrieval. */
interface SemanticMemoryProvenance141 {
    fun sourceTruthUids(memoryUid: EntityUid): Set<EntityUid>
}

class SQLiteSemanticMemoryProvenance141(
    private val db: SQLiteDatabase,
    private val campaignUid: EntityUid
) : SemanticMemoryProvenance141 {
    override fun sourceTruthUids(memoryUid: EntityUid): Set<EntityUid> {
        val out = linkedSetOf<EntityUid>()
        db.rawQuery(
            """
            SELECT l.truth_id
            FROM gm_memory_truth_links l
            JOIN gm_memories m ON m.memory_id=l.memory_id
            JOIN gm_facts f ON f.fact_id=l.truth_id
            WHERE l.memory_id=? AND m.campaign_id=? AND f.campaign_id=?
            ORDER BY l.truth_id
            """.trimIndent(),
            arrayOf(memoryUid.value, campaignUid.value, campaignUid.value)
        ).use { c ->
            while (c.moveToNext()) out += EntityUid(c.getString(0))
        }
        return out
    }
}
