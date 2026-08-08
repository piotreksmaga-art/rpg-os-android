package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

/** Separate idempotent migration for auditable NPC information paths. */
object KnowledgeTransmissionSchema141 {
    const val MIGRATION_ID = "GM-141-KNOWLEDGE-TRANSMISSION-V1"

    fun ensure(db: SQLiteDatabase) {
        val ownsTransaction = !db.inTransaction()
        if (ownsTransaction) db.beginTransaction()
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS gm_knowledge_transmissions (
                    transmission_id TEXT PRIMARY KEY,
                    campaign_id TEXT NOT NULL,
                    source_truth_id TEXT NOT NULL,
                    source_npc_id TEXT,
                    receiver_id TEXT NOT NULL,
                    resulting_belief_id TEXT NOT NULL,
                    channel TEXT NOT NULL CHECK(channel IN ('OBSERVATION','REPORT','INFERENCE')),
                    turn_number INTEGER NOT NULL,
                    confidence REAL NOT NULL CHECK(confidence >= 0.0 AND confidence <= 1.0),
                    created_at INTEGER NOT NULL,
                    UNIQUE(campaign_id, resulting_belief_id),
                    FOREIGN KEY(source_truth_id) REFERENCES gm_facts(fact_id),
                    FOREIGN KEY(resulting_belief_id) REFERENCES gm_facts(fact_id)
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_gm_knowledge_receiver_turn ON gm_knowledge_transmissions(campaign_id, receiver_id, turn_number)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_gm_knowledge_source_truth ON gm_knowledge_transmissions(campaign_id, source_truth_id)"
            )
            db.execSQL(
                """
                INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes)
                VALUES(?,?,?)
                """.trimIndent(),
                arrayOf(
                    MIGRATION_ID,
                    System.currentTimeMillis(),
                    "Auditable NPC knowledge paths: source, sender, receiver, channel and resulting BELIEF"
                )
            )
            if (ownsTransaction) db.setTransactionSuccessful()
        } finally {
            if (ownsTransaction) db.endTransaction()
        }
    }
}
