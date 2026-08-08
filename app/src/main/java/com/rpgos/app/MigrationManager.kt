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

        // Existing RPG OS baseline.
        VisualLibrary(saveDb).ensureSchema()
        saveDb.execSQL(
            """
            INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes)
            VALUES('RPGOS-1.0',strftime('%s','now'),'Baseline migration')
            """.trimIndent()
        )

        // GM Engine 141 extends the same campaign.db instead of creating a
        // second source of truth beside the existing save database.
        CampaignSourceOfTruthSchema.ensure(saveDb)
    }
}
