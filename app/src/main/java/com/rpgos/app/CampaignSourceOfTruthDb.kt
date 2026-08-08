package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

/**
 * GM Engine 141 schema extension for the existing campaign.db.
 *
 * We deliberately do NOT create a parallel database. The current campaign.db
 * stays the physical source of campaign truth so existing UI, export, backup,
 * restore and future GM Engine reads all operate on the same file.
 *
 * This schema uses RPG OS' own migration registry instead of PRAGMA user_version
 * because campaign packs may have their own SQLite user_version lifecycle.
 */
object CampaignSourceOfTruthSchema {
    const val SCHEMA_VERSION = 1
    const val EVENT_SCHEMA_VERSION = 1
    const val MEMORY_SCHEMA_VERSION = 1
    const val MIGRATION_ID = "GM-141-SOURCE-OF-TRUTH-V1"

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
            createSchemaV1(db)
            db.execSQL(
                """
                INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes)
                VALUES(?,?,?)
                """.trimIndent(),
                arrayOf(
                    MIGRATION_ID,
                    System.currentTimeMillis(),
                    "GM Engine 141 durable turns, state, events, truth, memory, divergences and snapshots"
                )
            )
            if (ownsTransaction) db.setTransactionSuccessful()
        } finally {
            if (ownsTransaction) db.endTransaction()
        }
    }

    private fun createSchemaV1(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS gm_campaign_meta (
                campaign_id TEXT PRIMARY KEY,
                world_pack_id TEXT NOT NULL,
                engine_version_code INTEGER NOT NULL,
                campaign_schema_version INTEGER NOT NULL,
                event_schema_version INTEGER NOT NULL,
                memory_schema_version INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                current_turn INTEGER NOT NULL DEFAULT 0,
                current_chapter INTEGER NOT NULL DEFAULT 0,
                current_snapshot_id TEXT
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS gm_turns (
                turn_id TEXT PRIMARY KEY,
                campaign_id TEXT NOT NULL,
                turn_number INTEGER NOT NULL,
                chapter INTEGER NOT NULL,
                player_input TEXT NOT NULL,
                narrative TEXT,
                status TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                committed_at INTEGER,
                failure_reason TEXT,
                UNIQUE(campaign_id, turn_number)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS gm_entity_state (
                campaign_id TEXT NOT NULL,
                entity_type TEXT NOT NULL,
                entity_id TEXT NOT NULL,
                field_key TEXT NOT NULL,
                value_json TEXT NOT NULL,
                valid_from_turn INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                provenance_type TEXT,
                provenance_id TEXT,
                PRIMARY KEY(campaign_id, entity_type, entity_id, field_key)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS gm_state_mutations (
                mutation_id TEXT PRIMARY KEY,
                campaign_id TEXT NOT NULL,
                turn_number INTEGER NOT NULL,
                entity_type TEXT NOT NULL,
                entity_id TEXT NOT NULL,
                field_key TEXT NOT NULL,
                operation TEXT NOT NULL,
                old_value_json TEXT,
                new_value_json TEXT,
                reason TEXT NOT NULL,
                caused_by_event_id TEXT,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(caused_by_event_id) REFERENCES gm_events(event_id)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS gm_facts (
                fact_id TEXT PRIMARY KEY,
                campaign_id TEXT NOT NULL,
                subject_id TEXT,
                predicate TEXT NOT NULL,
                object_json TEXT NOT NULL,
                truth_kind TEXT NOT NULL CHECK(truth_kind IN ('FACT','BELIEF','NARRATIVE')),
                holder_id TEXT,
                confidence REAL NOT NULL DEFAULT 1.0,
                valid_from_turn INTEGER NOT NULL,
                valid_until_turn INTEGER,
                source_type TEXT NOT NULL,
                source_id TEXT,
                canon_status TEXT,
                verified INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                CHECK(truth_kind != 'BELIEF' OR holder_id IS NOT NULL)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS gm_events (
                event_id TEXT PRIMARY KEY,
                campaign_id TEXT NOT NULL,
                turn_id TEXT NOT NULL,
                turn_number INTEGER NOT NULL,
                sequence INTEGER NOT NULL,
                chapter INTEGER NOT NULL,
                event_type TEXT NOT NULL,
                actor_id TEXT,
                target_id TEXT,
                description TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                cause_event_id TEXT,
                source_type TEXT NOT NULL,
                source_id TEXT,
                confidence REAL NOT NULL DEFAULT 1.0,
                created_at INTEGER NOT NULL,
                UNIQUE(campaign_id, turn_number, sequence),
                FOREIGN KEY(turn_id) REFERENCES gm_turns(turn_id) ON DELETE CASCADE,
                FOREIGN KEY(cause_event_id) REFERENCES gm_events(event_id)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS gm_memories (
                memory_id TEXT PRIMARY KEY,
                campaign_id TEXT NOT NULL,
                memory_kind TEXT NOT NULL CHECK(memory_kind IN ('EPISODIC','SEMANTIC')),
                subject_id TEXT,
                text TEXT NOT NULL,
                importance REAL NOT NULL CHECK(importance >= 0.0 AND importance <= 1.0),
                confidence REAL NOT NULL DEFAULT 1.0,
                first_turn INTEGER NOT NULL,
                last_reinforced_turn INTEGER NOT NULL,
                tags_json TEXT NOT NULL DEFAULT '[]',
                embedding_key TEXT,
                archived INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS gm_memory_event_links (
                memory_id TEXT NOT NULL,
                event_id TEXT NOT NULL,
                PRIMARY KEY(memory_id, event_id),
                FOREIGN KEY(memory_id) REFERENCES gm_memories(memory_id) ON DELETE CASCADE,
                FOREIGN KEY(event_id) REFERENCES gm_events(event_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS gm_chronicle_entries (
                chronicle_id TEXT PRIMARY KEY,
                campaign_id TEXT NOT NULL,
                turn_id TEXT NOT NULL,
                chapter INTEGER NOT NULL,
                title TEXT NOT NULL,
                summary TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(turn_id) REFERENCES gm_turns(turn_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS gm_chronicle_event_links (
                chronicle_id TEXT NOT NULL,
                event_id TEXT NOT NULL,
                PRIMARY KEY(chronicle_id, event_id),
                FOREIGN KEY(chronicle_id) REFERENCES gm_chronicle_entries(chronicle_id) ON DELETE CASCADE,
                FOREIGN KEY(event_id) REFERENCES gm_events(event_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS gm_divergences (
                divergence_id TEXT PRIMARY KEY,
                campaign_id TEXT NOT NULL,
                canon_subject_id TEXT NOT NULL,
                canon_event_id TEXT,
                divergence_type TEXT NOT NULL,
                description TEXT NOT NULL,
                caused_by_event_id TEXT,
                active INTEGER NOT NULL DEFAULT 1,
                created_turn INTEGER NOT NULL,
                resolved_turn INTEGER,
                FOREIGN KEY(caused_by_event_id) REFERENCES gm_events(event_id)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS gm_snapshots (
                snapshot_id TEXT PRIMARY KEY,
                campaign_id TEXT NOT NULL,
                turn_number INTEGER NOT NULL,
                event_sequence INTEGER NOT NULL,
                state_hash TEXT NOT NULL,
                storage_path TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                UNIQUE(campaign_id, turn_number)
            )
            """.trimIndent()
        )

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_gm_turns_campaign_turn ON gm_turns(campaign_id, turn_number)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_gm_state_entity ON gm_entity_state(campaign_id, entity_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_gm_mutations_entity ON gm_state_mutations(campaign_id, entity_id, turn_number)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_gm_events_campaign_turn ON gm_events(campaign_id, turn_number, sequence)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_gm_events_actor ON gm_events(campaign_id, actor_id, turn_number)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_gm_events_target ON gm_events(campaign_id, target_id, turn_number)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_gm_facts_subject ON gm_facts(campaign_id, subject_id, predicate)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_gm_facts_holder ON gm_facts(campaign_id, holder_id, truth_kind)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_gm_facts_temporal ON gm_facts(campaign_id, valid_from_turn, valid_until_turn)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_gm_memories_subject ON gm_memories(campaign_id, subject_id, archived, importance)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_gm_chronicle_chapter ON gm_chronicle_entries(campaign_id, chapter)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_gm_divergence_subject ON gm_divergences(campaign_id, canon_subject_id, active)")
    }
}
