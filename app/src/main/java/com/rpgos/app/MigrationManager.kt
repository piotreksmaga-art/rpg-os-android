package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

class MigrationManager {
    fun ensureV1(saveDb:SQLiteDatabase){
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS rpgos_schema_migrations(
                migration_id TEXT PRIMARY KEY,
                applied_at INTEGER NOT NULL,
                notes TEXT
            )
        """.trimIndent())
        // Visual library schema is part of 1.0 migration baseline.
        VisualLibrary(saveDb).ensureSchema()
        saveDb.execSQL(
            "INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes) VALUES('RPGOS-1.0',strftime('%s','now'),'Baseline migration')"
        )
    }
}
