package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

const val PHASE8_MIGRATION_ID = "RPGOS-8.0-TECHNIQUES"

/** Additive Phase 8 Technique schema and lossless extension of the generic Phase-5 target model. */
fun MigrationManager.ensureV8(saveDb: SQLiteDatabase, campaignId: String) {
    ensureV7(saveDb, campaignId)
    saveDb.beginTransaction()
    try {
        ensureTechniqueModifierTarget(saveDb)
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS technique_definitions_v2(
                technique_uid TEXT PRIMARY KEY,
                world_pack_uid TEXT NOT NULL,
                technique_key TEXT NOT NULL,
                display_name TEXT NOT NULL,
                category TEXT NOT NULL,
                min_mastery REAL,
                max_mastery REAL,
                definition_status TEXT NOT NULL CHECK(definition_status IN ('ACTIVE','DEPRECATED')),
                definition_version INTEGER NOT NULL CHECK(definition_version >= 1),
                provenance TEXT NOT NULL,
                UNIQUE(world_pack_uid,technique_key),
                CHECK(min_mastery IS NULL OR min_mastery >= 0.0),
                CHECK(max_mastery IS NULL OR max_mastery >= 0.0),
                CHECK(min_mastery IS NULL OR max_mastery IS NULL OR min_mastery <= max_mastery))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS technique_skill_requirements(
                technique_uid TEXT NOT NULL,
                skill_uid TEXT NOT NULL,
                requirement_phase TEXT NOT NULL CHECK(requirement_phase IN ('ACQUISITION','EXECUTION','BOTH')),
                mastery_basis TEXT NOT NULL CHECK(mastery_basis IN ('BASE','EFFECTIVE')),
                minimum_mastery REAL NOT NULL CHECK(minimum_mastery >= 0.0),
                requirement_version INTEGER NOT NULL CHECK(requirement_version >= 1),
                provenance TEXT NOT NULL,
                PRIMARY KEY(technique_uid,skill_uid,requirement_phase),
                FOREIGN KEY(technique_uid) REFERENCES technique_definitions_v2(technique_uid),
                FOREIGN KEY(skill_uid) REFERENCES skill_definitions_v2(skill_uid))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS technique_resource_costs(
                technique_uid TEXT NOT NULL,
                resource_uid TEXT NOT NULL,
                amount REAL NOT NULL CHECK(amount >= 0.0),
                cost_version INTEGER NOT NULL CHECK(cost_version >= 1),
                provenance TEXT NOT NULL,
                PRIMARY KEY(technique_uid,resource_uid),
                FOREIGN KEY(technique_uid) REFERENCES technique_definitions_v2(technique_uid),
                FOREIGN KEY(resource_uid) REFERENCES resource_definitions(resource_uid))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS player_techniques_v2(
                campaign_id TEXT NOT NULL,
                character_uid TEXT NOT NULL,
                technique_uid TEXT NOT NULL,
                base_mastery REAL NOT NULL CHECK(base_mastery >= 0.0),
                progress_value REAL,
                progress_semantics_uid TEXT,
                learned_chapter INTEGER,
                last_used_chapter INTEGER,
                usage_count INTEGER NOT NULL DEFAULT 0 CHECK(usage_count >= 0),
                success_count INTEGER NOT NULL DEFAULT 0 CHECK(success_count >= 0),
                failure_count INTEGER NOT NULL DEFAULT 0 CHECK(failure_count >= 0),
                is_equipped INTEGER NOT NULL DEFAULT 0 CHECK(is_equipped IN (0,1)),
                notes TEXT,
                entry_version INTEGER NOT NULL CHECK(entry_version >= 1),
                provenance TEXT NOT NULL,
                PRIMARY KEY(campaign_id,character_uid,technique_uid),
                FOREIGN KEY(technique_uid) REFERENCES technique_definitions_v2(technique_uid),
                CHECK(progress_value IS NULL OR progress_value >= 0.0),
                CHECK((progress_value IS NULL AND progress_semantics_uid IS NULL) OR (progress_value IS NOT NULL AND progress_semantics_uid IS NOT NULL)),
                CHECK(learned_chapter IS NULL OR learned_chapter >= 0),
                CHECK(last_used_chapter IS NULL OR last_used_chapter >= 0))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS legacy_technique_mappings(
                campaign_id TEXT NOT NULL,
                character_uid TEXT NOT NULL,
                legacy_technique_uid TEXT NOT NULL,
                canonical_technique_uid TEXT NOT NULL,
                world_pack_uid TEXT NOT NULL,
                mapping_version INTEGER NOT NULL CHECK(mapping_version >= 1),
                provenance TEXT NOT NULL,
                superseded_by_typed INTEGER NOT NULL DEFAULT 0 CHECK(superseded_by_typed IN (0,1)),
                PRIMARY KEY(campaign_id,character_uid,legacy_technique_uid),
                FOREIGN KEY(canonical_technique_uid) REFERENCES technique_definitions_v2(technique_uid))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS legacy_technique_resource_cost_mappings(
                campaign_id TEXT NOT NULL,
                character_uid TEXT NOT NULL,
                legacy_technique_uid TEXT NOT NULL,
                resource_uid TEXT NOT NULL,
                world_pack_uid TEXT NOT NULL,
                mapping_version INTEGER NOT NULL CHECK(mapping_version >= 1),
                provenance TEXT NOT NULL,
                PRIMARY KEY(campaign_id,character_uid,legacy_technique_uid),
                FOREIGN KEY(resource_uid) REFERENCES resource_definitions(resource_uid))
        """.trimIndent())
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_technique_definitions_pack ON technique_definitions_v2(world_pack_uid,category,technique_key)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_player_techniques_character ON player_techniques_v2(campaign_id,character_uid,technique_uid)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_legacy_technique_target ON legacy_technique_mappings(campaign_id,character_uid,canonical_technique_uid)")
        saveDb.execSQL("INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes) VALUES('$PHASE8_MIGRATION_ID',strftime('%s','now'),'Adds generic World Pack TechniqueDefinition/PlayerTechnique, explicit phased Skill requirements, legacy reconciliation/resource-cost mapping, and TECHNIQUE_EFFECTIVE; legacy Technique bytes and world reference index remain untouched')")
        saveDb.setTransactionSuccessful()
    } finally { saveDb.endTransaction() }
}

private fun ensureTechniqueModifierTarget(db: SQLiteDatabase) {
    val sql = db.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='modifiers'", null).use { c -> if (c.moveToFirst()) c.getString(0).orEmpty() else "" }
    if (sql.contains("TECHNIQUE_EFFECTIVE")) return
    db.execSQL("DROP TABLE IF EXISTS modifiers_phase8_new")
    db.execSQL("""
        CREATE TABLE modifiers_phase8_new(
            modifier_uid TEXT NOT NULL,campaign_id TEXT NOT NULL,character_uid TEXT NOT NULL,target_definition_uid TEXT NOT NULL,
            target_kind TEXT NOT NULL CHECK(target_kind IN ('STAT_EFFECTIVE','RESOURCE_MAXIMUM','RESOURCE_REGENERATION','SKILL_EFFECTIVE','TECHNIQUE_EFFECTIVE')),
            lifecycle TEXT NOT NULL CHECK(lifecycle IN ('PERMANENT','EQUIPMENT','INJURY','TEMPORARY')),
            operation TEXT NOT NULL CHECK(operation IN ('ADD_FLAT','ADD_PERCENT','MULTIPLY','OVERRIDE','MIN_FLOOR','MAX_CAP')),
            modifier_value REAL NOT NULL,priority INTEGER NOT NULL DEFAULT 0,source_type TEXT NOT NULL,source_uid TEXT NOT NULL,
            source_active INTEGER NOT NULL DEFAULT 1 CHECK(source_active IN (0,1)),valid_from INTEGER,valid_until INTEGER,
            active INTEGER NOT NULL DEFAULT 1 CHECK(active IN (0,1)),provenance TEXT NOT NULL,version INTEGER NOT NULL DEFAULT 1 CHECK(version >= 1),
            PRIMARY KEY(campaign_id,modifier_uid),CHECK(valid_from IS NULL OR valid_until IS NULL OR valid_until >= valid_from))
    """.trimIndent())
    db.execSQL("""INSERT INTO modifiers_phase8_new(modifier_uid,campaign_id,character_uid,target_definition_uid,target_kind,lifecycle,operation,modifier_value,priority,source_type,source_uid,source_active,valid_from,valid_until,active,provenance,version)
        SELECT modifier_uid,campaign_id,character_uid,target_definition_uid,target_kind,lifecycle,operation,modifier_value,priority,source_type,source_uid,source_active,valid_from,valid_until,active,provenance,version FROM modifiers""")
    db.execSQL("DROP TABLE modifiers")
    db.execSQL("ALTER TABLE modifiers_phase8_new RENAME TO modifiers")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_modifiers_character_target ON modifiers(campaign_id,character_uid,target_kind,target_definition_uid,active,source_active)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_modifiers_source ON modifiers(campaign_id,character_uid,source_type,source_uid)")
}
