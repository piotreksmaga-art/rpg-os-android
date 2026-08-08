package com.rpgos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Durable campaign Source of Truth for GM Engine 141.
 *
 * The schema intentionally separates current mutable state from immutable event
 * history and epistemic records (FACT/BELIEF/NARRATIVE). This database is the
 * canonical campaign-side persistence layer; world canon remains in world.db.
 */
class CampaignSourceOfTruthDb(
    context: Context,
    campaignId: String
) : SQLiteOpenHelper(
    context,
    "rpgos_campaign_${sanitizeId(campaignId)}.db",
    null,
    SCHEMA_VERSION
) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        createSchemaV1(db)
        db.execSQL("PRAGMA user_version=$SCHEMA_VERSION")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        var version = oldVersion
        if (version < 1) {
            createSchemaV1(db)
            version = 1
        }
        require(version == newVersion) {
            "Brak migracji Campaign Source of Truth: $version -> $newVersion"
        }
    }

    private fun createSchemaV1(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS campaign_meta (
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
            CREATE TABLE IF NOT EXISTS turns (
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
            CREATE TABLE IF NOT EXISTS entity_state (
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
            CREATE TABLE IF NOT EXISTS facts (
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
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS events (
                event_id TEXT PRIMARY KEY,
                campaign_id TEXT NOT NULL,
                turn_id TEXT NOT NULL,
                turn_number INTEGER NOT NULL,
                chapter INTEGER NOT NULL,
                event_type TEXT NOT NULL,
                actor_id TEXT,
                target_id TEXT,
                payload_json TEXT NOT NULL,
                cause_event_id TEXT,
                source_type TEXT NOT NULL,
                source_id TEXT,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(turn_id) REFERENCES turns(turn_id) ON DELETE CASCADE,
                FOREIGN KEY(cause_event_id) REFERENCES events(event_id)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS memories (
                memory_id TEXT PRIMARY KEY,
                campaign_id TEXT NOT NULL,
                memory_kind TEXT NOT NULL,
                subject_id TEXT,
                text TEXT NOT NULL,
                importance REAL NOT NULL,
                confidence REAL NOT NULL DEFAULT 1.0,
                first_turn INTEGER NOT NULL,
                last_reinforced_turn INTEGER NOT NULL,
                source_event_id TEXT,
                tags_json TEXT NOT NULL DEFAULT '[]',
                embedding_key TEXT,
                archived INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(source_event_id) REFERENCES events(event_id)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chronicle_entries (
                chronicle_id TEXT PRIMARY KEY,
                campaign_id TEXT NOT NULL,
                turn_id TEXT NOT NULL,
                chapter INTEGER NOT NULL,
                title TEXT NOT NULL,
                summary TEXT NOT NULL,
                participants_json TEXT NOT NULL DEFAULT '[]',
                locations_json TEXT NOT NULL DEFAULT '[]',
                created_at INTEGER NOT NULL,
                FOREIGN KEY(turn_id) REFERENCES turns(turn_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS divergences (
                divergence_id TEXT PRIMARY KEY,
                campaign_id TEXT NOT NULL,
                canon_subject_id TEXT,
                canon_event_id TEXT,
                divergence_type TEXT NOT NULL,
                description TEXT NOT NULL,
                caused_by_event_id TEXT,
                active INTEGER NOT NULL DEFAULT 1,
                created_turn INTEGER NOT NULL,
                resolved_turn INTEGER,
                FOREIGN KEY(caused_by_event_id) REFERENCES events(event_id)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS snapshots (
                snapshot_id TEXT PRIMARY KEY,
                campaign_id TEXT NOT NULL,
                turn_number INTEGER NOT NULL,
                chapter INTEGER NOT NULL,
                state_hash TEXT NOT NULL,
                storage_path TEXT NOT NULL,
                event_count INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                UNIQUE(campaign_id, turn_number)
            )
            """.trimIndent()
        )

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_campaign_turn ON events(campaign_id, turn_number)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_actor ON events(campaign_id, actor_id, turn_number)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_target ON events(campaign_id, target_id, turn_number)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_facts_subject ON facts(campaign_id, subject_id, predicate)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_facts_holder ON facts(campaign_id, holder_id, truth_kind)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_memories_subject ON memories(campaign_id, subject_id, archived, importance)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chronicle_chapter ON chronicle_entries(campaign_id, chapter)")
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val EVENT_SCHEMA_VERSION = 1
        const val MEMORY_SCHEMA_VERSION = 1

        private fun sanitizeId(value: String): String {
            val safe = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
            require(safe.isNotBlank() && safe != "." && safe != "..") {
                "Niepoprawny identyfikator kampanii."
            }
            return safe
        }
    }
}
