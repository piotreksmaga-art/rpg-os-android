package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

const val PHASE7_MIGRATION_ID = "RPGOS-7.0-SKILLS"

object CurrentSchema {
    fun ensure(saveDb: SQLiteDatabase, campaignId: String) {
        MigrationManager().ensureV13(saveDb, campaignId)
    }
}

/** Additive Phase 7 schema plus a lossless extension of the Phase 5 modifier target enum. */
fun MigrationManager.ensureV7(saveDb: SQLiteDatabase, campaignId: String) {
    ensureV6(saveDb, campaignId)
    saveDb.beginTransaction()
    try {
        ensureSkillModifierTarget(saveDb)
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS skill_definitions_v2(
                skill_uid TEXT PRIMARY KEY,
                world_pack_uid TEXT NOT NULL,
                skill_key TEXT NOT NULL,
                display_name TEXT NOT NULL,
                category TEXT NOT NULL,
                min_mastery REAL,
                max_mastery REAL,
                definition_status TEXT NOT NULL CHECK(definition_status IN ('ACTIVE','DEPRECATED')),
                definition_version INTEGER NOT NULL CHECK(definition_version >= 1),
                provenance TEXT NOT NULL,
                UNIQUE(world_pack_uid,skill_key),
                CHECK(min_mastery IS NULL OR min_mastery >= 0.0),
                CHECK(max_mastery IS NULL OR max_mastery >= 0.0),
                CHECK(min_mastery IS NULL OR max_mastery IS NULL OR min_mastery <= max_mastery))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS skill_definition_domains(
                skill_uid TEXT NOT NULL,
                domain_uid TEXT NOT NULL,
                PRIMARY KEY(skill_uid,domain_uid),
                FOREIGN KEY(skill_uid) REFERENCES skill_definitions_v2(skill_uid) ON DELETE CASCADE)
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS player_skills_v2(
                campaign_id TEXT NOT NULL,
                character_uid TEXT NOT NULL,
                skill_uid TEXT NOT NULL,
                mastery REAL NOT NULL,
                experience REAL NOT NULL DEFAULT 0.0,
                state_version INTEGER NOT NULL CHECK(state_version >= 1),
                provenance TEXT NOT NULL,
                PRIMARY KEY(campaign_id,character_uid,skill_uid),
                FOREIGN KEY(skill_uid) REFERENCES skill_definitions_v2(skill_uid),
                CHECK(mastery >= 0.0), CHECK(experience >= 0.0))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS skill_progression_ledger(
                campaign_id TEXT NOT NULL,
                entry_uid TEXT NOT NULL,
                character_uid TEXT NOT NULL,
                skill_uid TEXT NOT NULL,
                mastery_before REAL NOT NULL,
                mastery_after REAL NOT NULL,
                experience_delta REAL NOT NULL,
                source_type TEXT NOT NULL,
                source_uid TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                provenance TEXT NOT NULL,
                PRIMARY KEY(campaign_id,entry_uid),
                FOREIGN KEY(skill_uid) REFERENCES skill_definitions_v2(skill_uid),
                CHECK(mastery_before >= 0.0), CHECK(mastery_after >= mastery_before), CHECK(experience_delta >= 0.0))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS legacy_skill_mappings(
                campaign_id TEXT NOT NULL,
                character_uid TEXT NOT NULL,
                legacy_skill_uid TEXT NOT NULL,
                canonical_skill_uid TEXT NOT NULL,
                world_pack_uid TEXT NOT NULL,
                mapping_version INTEGER NOT NULL CHECK(mapping_version >= 1),
                provenance TEXT NOT NULL,
                PRIMARY KEY(campaign_id,character_uid,legacy_skill_uid),
                FOREIGN KEY(canonical_skill_uid) REFERENCES skill_definitions_v2(skill_uid))
        """.trimIndent())
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_skill_definitions_pack ON skill_definitions_v2(world_pack_uid,category,skill_key)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_player_skills_character ON player_skills_v2(campaign_id,character_uid,skill_uid)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_skill_ledger_character ON skill_progression_ledger(campaign_id,character_uid,skill_uid,created_at)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_legacy_skill_target ON legacy_skill_mappings(campaign_id,character_uid,canonical_skill_uid)")
        saveDb.execSQL("INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes) VALUES('$PHASE7_MIGRATION_ID',strftime('%s','now'),'Adds typed generic skills, player mastery/xp state and append-only progression evidence; legacy skills stay untouched')")
        saveDb.setTransactionSuccessful()
    } finally { saveDb.endTransaction() }
}

private fun ensureSkillModifierTarget(db: SQLiteDatabase) {
    val sql = db.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='modifiers'", null).use { c -> if(c.moveToFirst()) c.getString(0) else null } ?: return
    if (sql.contains("SKILL_EFFECTIVE")) return
    db.execSQL("ALTER TABLE modifiers RENAME TO modifiers_phase7_old")
    db.execSQL("""
        CREATE TABLE modifiers(
            modifier_uid TEXT NOT NULL,
            campaign_id TEXT NOT NULL,
            character_uid TEXT NOT NULL,
            target_definition_uid TEXT NOT NULL,
            target_kind TEXT NOT NULL CHECK(target_kind IN ('STAT_EFFECTIVE','RESOURCE_MAXIMUM','RESOURCE_REGENERATION','SKILL_EFFECTIVE')),
            lifecycle TEXT NOT NULL CHECK(lifecycle IN ('PERMANENT','EQUIPMENT','INJURY','TEMPORARY')),
            operation TEXT NOT NULL CHECK(operation IN ('ADD_FLAT','ADD_PERCENT','MULTIPLY','OVERRIDE','MIN_FLOOR','MAX_CAP')),
            modifier_value REAL NOT NULL,
            priority INTEGER NOT NULL DEFAULT 0,
            source_type TEXT NOT NULL,
            source_uid TEXT NOT NULL,
            source_active INTEGER NOT NULL DEFAULT 1 CHECK(source_active IN (0,1)),
            valid_from INTEGER,
            valid_until INTEGER,
            active INTEGER NOT NULL DEFAULT 1 CHECK(active IN (0,1)),
            provenance TEXT NOT NULL,
            version INTEGER NOT NULL DEFAULT 1 CHECK(version >= 1),
            PRIMARY KEY(campaign_id,modifier_uid),
            CHECK(valid_from IS NULL OR valid_until IS NULL OR valid_until >= valid_from))
    """.trimIndent())
    db.execSQL("INSERT INTO modifiers SELECT * FROM modifiers_phase7_old")
    db.execSQL("DROP TABLE modifiers_phase7_old")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_modifiers_character_target ON modifiers(campaign_id,character_uid,target_kind,target_definition_uid,active,source_active)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_modifiers_source ON modifiers(campaign_id,character_uid,source_type,source_uid)")
}
