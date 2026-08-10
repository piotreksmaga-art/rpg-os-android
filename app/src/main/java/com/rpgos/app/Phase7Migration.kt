package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

const val PHASE7_MIGRATION_ID = "RPGOS-7.0-SKILLS"

object CurrentSchema {
    fun ensure(saveDb: SQLiteDatabase, campaignId: String) {
        MigrationManager().ensureV11(saveDb, campaignId)
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
                FOREIGN KEY(skill_uid) REFERENCES skill_definitions_v2(skill_uid),
                FOREIGN KEY(domain_uid) REFERENCES progression_domain_definitions(domain_uid))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS player_skills_v2(
                campaign_id TEXT NOT NULL,
                character_uid TEXT NOT NULL,
                skill_uid TEXT NOT NULL,
                base_mastery REAL NOT NULL CHECK(base_mastery >= 0.0),
                progress_value REAL,
                progress_semantics_uid TEXT,
                entry_version INTEGER NOT NULL CHECK(entry_version >= 1),
                provenance TEXT NOT NULL,
                learned_chapter INTEGER,
                PRIMARY KEY(campaign_id,character_uid,skill_uid),
                FOREIGN KEY(skill_uid) REFERENCES skill_definitions_v2(skill_uid),
                CHECK(progress_value IS NULL OR progress_value >= 0.0),
                CHECK((progress_value IS NULL AND progress_semantics_uid IS NULL) OR (progress_value IS NOT NULL AND progress_semantics_uid IS NOT NULL)),
                CHECK(learned_chapter IS NULL OR learned_chapter >= 0))
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
                superseded_by_typed INTEGER NOT NULL DEFAULT 0 CHECK(superseded_by_typed IN (0,1)),
                PRIMARY KEY(campaign_id,character_uid,legacy_skill_uid),
                FOREIGN KEY(canonical_skill_uid) REFERENCES skill_definitions_v2(skill_uid))
        """.trimIndent())
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_skill_definitions_pack ON skill_definitions_v2(world_pack_uid,category,skill_key)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_player_skills_character ON player_skills_v2(campaign_id,character_uid,skill_uid)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_legacy_skill_target ON legacy_skill_mappings(campaign_id,character_uid,canonical_skill_uid)")
        saveDb.execSQL("INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes) VALUES('$PHASE7_MIGRATION_ID',strftime('%s','now'),'Adds generic World Pack SkillDefinition/PlayerSkill, explicit legacy reconciliation, and SKILL_EFFECTIVE as a generic Phase 5 target; legacy skill bytes remain untouched')")
        saveDb.setTransactionSuccessful()
    } finally { saveDb.endTransaction() }
}

private fun ensureSkillModifierTarget(db: SQLiteDatabase) {
    val sql = db.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='modifiers'", null).use { c ->
        if (c.moveToFirst()) c.getString(0).orEmpty() else ""
    }
    if (sql.contains("SKILL_EFFECTIVE")) return
    db.execSQL("DROP TABLE IF EXISTS modifiers_phase7_new")
    db.execSQL("""
        CREATE TABLE modifiers_phase7_new(
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
    db.execSQL("""INSERT INTO modifiers_phase7_new(modifier_uid,campaign_id,character_uid,target_definition_uid,target_kind,lifecycle,operation,modifier_value,priority,source_type,source_uid,source_active,valid_from,valid_until,active,provenance,version)
        SELECT modifier_uid,campaign_id,character_uid,target_definition_uid,target_kind,lifecycle,operation,modifier_value,priority,source_type,source_uid,source_active,valid_from,valid_until,active,provenance,version FROM modifiers""")
    db.execSQL("DROP TABLE modifiers")
    db.execSQL("ALTER TABLE modifiers_phase7_new RENAME TO modifiers")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_modifiers_character_target ON modifiers(campaign_id,character_uid,target_kind,target_definition_uid,active,source_active)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_modifiers_source ON modifiers(campaign_id,character_uid,source_type,source_uid)")
}
