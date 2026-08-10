package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

const val PHASE9_MIGRATION_ID = "RPGOS-9.0-INNATE-EVOLUTION"

/** Additive Phase 9 identity / innate / evolution / form schema. Legacy evidence is never rewritten. */
fun MigrationManager.ensureV9(saveDb: SQLiteDatabase, campaignId: String) {
    ensureV8(saveDb, campaignId)
    saveDb.beginTransaction()
    try {
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS origin_definitions_v2(
                origin_uid TEXT PRIMARY KEY,
                world_pack_uid TEXT NOT NULL,
                origin_key TEXT NOT NULL,
                display_name TEXT NOT NULL,
                origin_kind TEXT NOT NULL,
                definition_status TEXT NOT NULL CHECK(definition_status IN ('ACTIVE','DEPRECATED')),
                definition_version INTEGER NOT NULL CHECK(definition_version >= 1),
                provenance TEXT NOT NULL,
                UNIQUE(world_pack_uid,origin_key))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS player_origins_v2(
                campaign_id TEXT NOT NULL,
                character_uid TEXT NOT NULL,
                origin_uid TEXT NOT NULL,
                relationship_kind TEXT NOT NULL,
                entry_version INTEGER NOT NULL CHECK(entry_version >= 1),
                provenance TEXT NOT NULL,
                PRIMARY KEY(campaign_id,character_uid,origin_uid),
                FOREIGN KEY(origin_uid) REFERENCES origin_definitions_v2(origin_uid))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS innate_feature_definitions(
                feature_uid TEXT PRIMARY KEY,
                world_pack_uid TEXT NOT NULL,
                feature_key TEXT NOT NULL,
                display_name TEXT NOT NULL,
                feature_kind TEXT NOT NULL,
                category TEXT,
                definition_status TEXT NOT NULL CHECK(definition_status IN ('ACTIVE','DEPRECATED')),
                definition_version INTEGER NOT NULL CHECK(definition_version >= 1),
                provenance TEXT NOT NULL,
                UNIQUE(world_pack_uid,feature_key))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS player_innate_features(
                campaign_id TEXT NOT NULL,
                character_uid TEXT NOT NULL,
                feature_uid TEXT NOT NULL,
                acquired_chapter INTEGER,
                entry_version INTEGER NOT NULL CHECK(entry_version >= 1),
                provenance TEXT NOT NULL,
                PRIMARY KEY(campaign_id,character_uid,feature_uid),
                FOREIGN KEY(feature_uid) REFERENCES innate_feature_definitions(feature_uid),
                CHECK(acquired_chapter IS NULL OR acquired_chapter >= 0))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS evolution_path_definitions(
                path_uid TEXT PRIMARY KEY,
                world_pack_uid TEXT NOT NULL,
                path_key TEXT NOT NULL,
                display_name TEXT NOT NULL,
                definition_status TEXT NOT NULL CHECK(definition_status IN ('ACTIVE','DEPRECATED')),
                definition_version INTEGER NOT NULL CHECK(definition_version >= 1),
                provenance TEXT NOT NULL,
                UNIQUE(world_pack_uid,path_key))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS evolution_stage_definitions(
                stage_uid TEXT PRIMARY KEY,
                path_uid TEXT NOT NULL,
                world_pack_uid TEXT NOT NULL,
                stage_key TEXT NOT NULL,
                display_name TEXT NOT NULL,
                definition_status TEXT NOT NULL CHECK(definition_status IN ('ACTIVE','DEPRECATED')),
                definition_version INTEGER NOT NULL CHECK(definition_version >= 1),
                provenance TEXT NOT NULL,
                UNIQUE(path_uid,stage_key),
                FOREIGN KEY(path_uid) REFERENCES evolution_path_definitions(path_uid))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS evolution_transition_definitions(
                transition_uid TEXT PRIMARY KEY,
                world_pack_uid TEXT NOT NULL,
                source_stage_uid TEXT,
                target_stage_uid TEXT NOT NULL,
                requirement_rule_uid TEXT,
                reversible INTEGER NOT NULL DEFAULT 0 CHECK(reversible IN (0,1)),
                cross_path_allowed INTEGER NOT NULL DEFAULT 0 CHECK(cross_path_allowed IN (0,1)),
                transition_version INTEGER NOT NULL CHECK(transition_version >= 1),
                provenance TEXT NOT NULL,
                FOREIGN KEY(source_stage_uid) REFERENCES evolution_stage_definitions(stage_uid),
                FOREIGN KEY(target_stage_uid) REFERENCES evolution_stage_definitions(stage_uid))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS player_evolution_states(
                campaign_id TEXT NOT NULL,
                character_uid TEXT NOT NULL,
                path_uid TEXT NOT NULL,
                current_stage_uid TEXT,
                state_version INTEGER NOT NULL CHECK(state_version >= 1),
                provenance TEXT NOT NULL,
                PRIMARY KEY(campaign_id,character_uid,path_uid),
                FOREIGN KEY(path_uid) REFERENCES evolution_path_definitions(path_uid),
                FOREIGN KEY(current_stage_uid) REFERENCES evolution_stage_definitions(stage_uid))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS player_evolution_stages(
                campaign_id TEXT NOT NULL,
                character_uid TEXT NOT NULL,
                stage_uid TEXT NOT NULL,
                attained_via_transition_uid TEXT,
                attained_chapter INTEGER,
                entry_version INTEGER NOT NULL CHECK(entry_version >= 1),
                provenance TEXT NOT NULL,
                PRIMARY KEY(campaign_id,character_uid,stage_uid),
                FOREIGN KEY(stage_uid) REFERENCES evolution_stage_definitions(stage_uid),
                FOREIGN KEY(attained_via_transition_uid) REFERENCES evolution_transition_definitions(transition_uid),
                CHECK(attained_chapter IS NULL OR attained_chapter >= 0))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS form_definitions(
                form_uid TEXT PRIMARY KEY,
                world_pack_uid TEXT NOT NULL,
                form_key TEXT NOT NULL,
                display_name TEXT NOT NULL,
                source_feature_uid TEXT,
                source_stage_uid TEXT,
                exclusive_group_uid TEXT,
                activation_rule_uid TEXT,
                definition_status TEXT NOT NULL CHECK(definition_status IN ('ACTIVE','DEPRECATED')),
                definition_version INTEGER NOT NULL CHECK(definition_version >= 1),
                provenance TEXT NOT NULL,
                UNIQUE(world_pack_uid,form_key),
                FOREIGN KEY(source_feature_uid) REFERENCES innate_feature_definitions(feature_uid),
                FOREIGN KEY(source_stage_uid) REFERENCES evolution_stage_definitions(stage_uid))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS player_form_unlocks(
                campaign_id TEXT NOT NULL,
                character_uid TEXT NOT NULL,
                form_uid TEXT NOT NULL,
                entry_version INTEGER NOT NULL CHECK(entry_version >= 1),
                provenance TEXT NOT NULL,
                PRIMARY KEY(campaign_id,character_uid,form_uid),
                FOREIGN KEY(form_uid) REFERENCES form_definitions(form_uid))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS player_active_forms(
                campaign_id TEXT NOT NULL,
                character_uid TEXT NOT NULL,
                form_uid TEXT NOT NULL,
                activated_at INTEGER,
                state_version INTEGER NOT NULL CHECK(state_version >= 1),
                provenance TEXT NOT NULL,
                PRIMARY KEY(campaign_id,character_uid,form_uid),
                FOREIGN KEY(campaign_id,character_uid,form_uid)
                    REFERENCES player_form_unlocks(campaign_id,character_uid,form_uid))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS form_modifier_bindings(
                binding_uid TEXT PRIMARY KEY,
                world_pack_uid TEXT NOT NULL,
                form_uid TEXT NOT NULL,
                target_definition_uid TEXT NOT NULL,
                target_kind TEXT NOT NULL CHECK(target_kind IN ('STAT_EFFECTIVE','RESOURCE_MAXIMUM','RESOURCE_REGENERATION','SKILL_EFFECTIVE','TECHNIQUE_EFFECTIVE')),
                operation TEXT NOT NULL CHECK(operation IN ('ADD_FLAT','ADD_PERCENT','MULTIPLY','OVERRIDE','MIN_FLOOR','MAX_CAP')),
                modifier_value REAL NOT NULL,
                priority INTEGER NOT NULL DEFAULT 0,
                binding_version INTEGER NOT NULL CHECK(binding_version >= 1),
                provenance TEXT NOT NULL,
                FOREIGN KEY(form_uid) REFERENCES form_definitions(form_uid))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS legacy_phase9_mappings(
                world_pack_uid TEXT NOT NULL,
                evidence_field TEXT NOT NULL,
                evidence_value TEXT NOT NULL,
                target_kind TEXT NOT NULL CHECK(target_kind IN ('ORIGIN','INNATE_FEATURE','EVOLUTION_STAGE','FORM_UNLOCK')),
                target_uid TEXT NOT NULL,
                mapping_version INTEGER NOT NULL CHECK(mapping_version >= 1),
                provenance TEXT NOT NULL,
                PRIMARY KEY(world_pack_uid,evidence_field,evidence_value,target_kind))
        """.trimIndent())

        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_origin_definitions_pack ON origin_definitions_v2(world_pack_uid,origin_kind,origin_key)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_player_origins_character ON player_origins_v2(campaign_id,character_uid,origin_uid)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_innate_definitions_pack ON innate_feature_definitions(world_pack_uid,feature_kind,feature_key)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_player_innate_character ON player_innate_features(campaign_id,character_uid,feature_uid)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_evolution_stages_path ON evolution_stage_definitions(path_uid,stage_key)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_evolution_transitions_source ON evolution_transition_definitions(source_stage_uid,target_stage_uid)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_player_evolution_character ON player_evolution_states(campaign_id,character_uid,path_uid)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_player_evolution_history ON player_evolution_stages(campaign_id,character_uid,stage_uid)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_form_definitions_pack ON form_definitions(world_pack_uid,form_key)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_active_forms_character ON player_active_forms(campaign_id,character_uid,form_uid)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_form_modifier_source ON form_modifier_bindings(form_uid,binding_uid)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_phase9_mapping_target ON legacy_phase9_mappings(world_pack_uid,target_kind,target_uid)")
        saveDb.execSQL("INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes) VALUES('$PHASE9_MIGRATION_ID',strftime('%s','now'),'Adds generic World Pack origin/innate/evolution/form definitions, separated persistent unlock/current state, explicit transition graph, Phase-5 form modifier bindings, and explicit legacy evidence mappings; legacy bytes remain untouched')")
        saveDb.setTransactionSuccessful()
    } finally { saveDb.endTransaction() }
}
