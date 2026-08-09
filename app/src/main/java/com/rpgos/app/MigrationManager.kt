package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

class MigrationManager {
    fun ensureV1(saveDb: SQLiteDatabase) {
        saveDb.execSQL(
            """
            CREATE TABLE IF NOT EXISTS rpgos_schema_migrations(
                migration_id TEXT PRIMARY KEY,
                applied_at INTEGER NOT NULL,
                notes TEXT
            )
            """.trimIndent()
        )
        VisualLibrary(saveDb).ensureSchema()
        saveDb.execSQL(
            "INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes) " +
                "VALUES('RPGOS-1.0',strftime('%s','now'),'Baseline migration')"
        )
    }

    fun ensureV2(saveDb: SQLiteDatabase) {
        ensureV1(saveDb)
        saveDb.beginTransaction()
        try {
            saveDb.execSQL(
                """
                CREATE TABLE IF NOT EXISTS campaign_truth_records(
                    truth_uid TEXT PRIMARY KEY,
                    campaign_id TEXT NOT NULL,
                    truth_kind TEXT NOT NULL CHECK(truth_kind IN ('FACT','BELIEF','NARRATIVE')),
                    subject_uid TEXT,
                    predicate TEXT NOT NULL,
                    object_value TEXT,
                    perspective_uid TEXT,
                    narrative_text TEXT,
                    source_type TEXT NOT NULL,
                    source_id TEXT,
                    created_turn INTEGER,
                    created_event TEXT,
                    confidence REAL NOT NULL DEFAULT 1.0 CHECK(confidence >= 0.0 AND confidence <= 1.0),
                    canon_status TEXT,
                    verified INTEGER NOT NULL DEFAULT 0 CHECK(verified IN (0,1)),
                    actor_uid TEXT,
                    method TEXT,
                    engine_version TEXT,
                    created_at INTEGER NOT NULL,
                    supersedes_truth_uid TEXT,
                    active INTEGER NOT NULL DEFAULT 1 CHECK(active IN (0,1)),
                    CHECK(truth_kind != 'BELIEF' OR perspective_uid IS NOT NULL),
                    CHECK(truth_kind != 'NARRATIVE' OR narrative_text IS NOT NULL),
                    CHECK(truth_kind = 'NARRATIVE' OR narrative_text IS NULL)
                )
                """.trimIndent()
            )
            saveDb.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_truth_campaign_kind_active " +
                    "ON campaign_truth_records(campaign_id,truth_kind,active,created_at DESC)"
            )
            saveDb.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_truth_subject " +
                    "ON campaign_truth_records(campaign_id,subject_uid,active)"
            )
            saveDb.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_truth_perspective " +
                    "ON campaign_truth_records(campaign_id,perspective_uid,truth_kind,active)"
            )
            saveDb.execSQL(
                "INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes) " +
                    "VALUES('RPGOS-2.0-TRUTH',strftime('%s','now')," +
                    "'Adds FACT/BELIEF/NARRATIVE truth records with provenance; no legacy facts are invented')"
            )
            saveDb.setTransactionSuccessful()
        } finally {
            saveDb.endTransaction()
        }
    }

    fun ensureV3(saveDb: SQLiteDatabase, campaignId: String) {
        ensureV2(saveDb)
        saveDb.beginTransaction()
        try {
            saveDb.execSQL(
                """
                CREATE TABLE IF NOT EXISTS active_player_ref(
                    campaign_id TEXT PRIMARY KEY,
                    player_uid TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            saveDb.execSQL(
                "INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes) " +
                    "VALUES('RPGOS-3.0-PLAYER-STATE',strftime('%s','now')," +
                    "'Adds authoritative active player identity; legacy player selection is seeded once and then persisted')"
            )
            saveDb.setTransactionSuccessful()
        } finally {
            saveDb.endTransaction()
        }
        ActivePlayerStore(saveDb, campaignId).seedFromLegacyIfMissing()
    }
}
