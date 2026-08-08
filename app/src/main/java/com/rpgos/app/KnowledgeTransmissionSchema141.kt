package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

/** Separate idempotent migrations for auditable NPC information paths. */
object KnowledgeTransmissionSchema141 {
    const val MIGRATION_ID = "GM-141-KNOWLEDGE-TRANSMISSION-V1"
    const val MIGRATION_V2_ID = "GM-141-KNOWLEDGE-TRANSMISSION-V2-RESEARCH"

    fun ensure(db: SQLiteDatabase) {
        val ownsTransaction = !db.inTransaction()
        if (ownsTransaction) db.beginTransaction()
        try {
            createCurrentTableIfMissing(db)
            recordMigration(db, MIGRATION_ID,
                "Auditable NPC knowledge paths: source, sender, receiver, channel and resulting BELIEF")
            migrateV2ResearchChannel(db)
            createIndexes(db)
            if (ownsTransaction) db.setTransactionSuccessful()
        } finally {
            if (ownsTransaction) db.endTransaction()
        }
    }

    private fun createCurrentTableIfMissing(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS gm_knowledge_transmissions (
                transmission_id TEXT PRIMARY KEY,
                campaign_id TEXT NOT NULL,
                source_truth_id TEXT NOT NULL,
                source_npc_id TEXT,
                receiver_id TEXT NOT NULL,
                resulting_belief_id TEXT NOT NULL,
                channel TEXT NOT NULL CHECK(channel IN ('OBSERVATION','REPORT','RESEARCH','INFERENCE')),
                turn_number INTEGER NOT NULL,
                confidence REAL NOT NULL CHECK(confidence >= 0.0 AND confidence <= 1.0),
                created_at INTEGER NOT NULL,
                UNIQUE(campaign_id, resulting_belief_id),
                FOREIGN KEY(source_truth_id) REFERENCES gm_facts(fact_id),
                FOREIGN KEY(resulting_belief_id) REFERENCES gm_facts(fact_id)
            )
            """.trimIndent()
        )
    }

    /**
     * V1 originally allowed OBSERVATION/REPORT/INFERENCE only. SQLite cannot
     * ALTER a CHECK constraint, therefore existing ledgers are rebuilt once.
     */
    private fun migrateV2ResearchChannel(db: SQLiteDatabase) {
        if (migrationApplied(db, MIGRATION_V2_ID)) return

        db.execSQL("DROP TABLE IF EXISTS gm_knowledge_transmissions_v2")
        db.execSQL(
            """
            CREATE TABLE gm_knowledge_transmissions_v2 (
                transmission_id TEXT PRIMARY KEY,
                campaign_id TEXT NOT NULL,
                source_truth_id TEXT NOT NULL,
                source_npc_id TEXT,
                receiver_id TEXT NOT NULL,
                resulting_belief_id TEXT NOT NULL,
                channel TEXT NOT NULL CHECK(channel IN ('OBSERVATION','REPORT','RESEARCH','INFERENCE')),
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
            """
            INSERT INTO gm_knowledge_transmissions_v2(
                transmission_id,campaign_id,source_truth_id,source_npc_id,
                receiver_id,resulting_belief_id,channel,turn_number,confidence,created_at
            )
            SELECT transmission_id,campaign_id,source_truth_id,source_npc_id,
                   receiver_id,resulting_belief_id,channel,turn_number,confidence,created_at
            FROM gm_knowledge_transmissions
            """.trimIndent()
        )
        db.execSQL("DROP TABLE gm_knowledge_transmissions")
        db.execSQL("ALTER TABLE gm_knowledge_transmissions_v2 RENAME TO gm_knowledge_transmissions")
        recordMigration(
            db,
            MIGRATION_V2_ID,
            "Knowledge transmission ledger supports explicit RESEARCH provenance channel"
        )
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_gm_knowledge_receiver_turn ON gm_knowledge_transmissions(campaign_id, receiver_id, turn_number)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_gm_knowledge_source_truth ON gm_knowledge_transmissions(campaign_id, source_truth_id)"
        )
    }

    private fun migrationApplied(db: SQLiteDatabase, id: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM rpgos_schema_migrations WHERE migration_id=? LIMIT 1",
            arrayOf(id)
        ).use { it.moveToFirst() }

    private fun recordMigration(db: SQLiteDatabase, id: String, notes: String) {
        db.execSQL(
            """
            INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes)
            VALUES(?,?,?)
            """.trimIndent(),
            arrayOf(id, System.currentTimeMillis(), notes)
        )
    }
}
