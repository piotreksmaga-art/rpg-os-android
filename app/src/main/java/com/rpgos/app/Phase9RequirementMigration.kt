package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

const val PHASE9_REQUIREMENT_HOTFIX_MIGRATION_ID = "RPGOS-9.1-REQUIREMENT-GATES"

/**
 * Additive semantic hotfix for Phase 9. Existing V9 state and legacy bytes are untouched.
 * Nullable UID+version pairs mean the World Pack explicitly declares no requirement when both are null.
 */
fun MigrationManager.ensureV9RequirementHotfix(saveDb: SQLiteDatabase, campaignId: String) {
    ensureV9(saveDb, campaignId)
    saveDb.beginTransaction()
    try {
        addColumnIfMissing(saveDb, "evolution_transition_definitions", "requirement_rule_version", "INTEGER")
        addColumnIfMissing(saveDb, "form_definitions", "unlock_requirement_rule_uid", "TEXT")
        addColumnIfMissing(saveDb, "form_definitions", "unlock_requirement_rule_version", "INTEGER")
        addColumnIfMissing(saveDb, "form_definitions", "activation_rule_version", "INTEGER")

        // The accepted pre-hotfix Phase 9 schema stored rule UIDs without an explicit version.
        // Preserve that meaning deterministically as version 1 instead of dropping or guessing the binding.
        saveDb.execSQL("UPDATE evolution_transition_definitions SET requirement_rule_version=1 WHERE requirement_rule_uid IS NOT NULL AND requirement_rule_version IS NULL")
        saveDb.execSQL("UPDATE form_definitions SET activation_rule_version=1 WHERE activation_rule_uid IS NOT NULL AND activation_rule_version IS NULL")

        saveDb.execSQL(
            "INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes) VALUES('$PHASE9_REQUIREMENT_HOTFIX_MIGRATION_ID',strftime('%s','now'),'Adds explicit versioned UNLOCK, TRANSITION and ACTIVATION requirement bindings; pre-hotfix transition/activation rule UIDs retain deterministic version 1; no player state or legacy evidence is rewritten')"
        )
        saveDb.setTransactionSuccessful()
    } finally {
        saveDb.endTransaction()
    }
}

private fun addColumnIfMissing(db: SQLiteDatabase, table: String, column: String, declaration: String) {
    val exists = db.rawQuery("PRAGMA table_info($table)", null).use { c ->
        val nameIndex = c.getColumnIndex("name")
        var found = false
        while (c.moveToNext()) {
            if (nameIndex >= 0 && c.getString(nameIndex) == column) {
                found = true
                break
            }
        }
        found
    }
    if (!exists) db.execSQL("ALTER TABLE $table ADD COLUMN $column $declaration")
}
