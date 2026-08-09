package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

/** Additive Phase 6 schema. Kept separate from ensureV4() to avoid rewriting accepted Phase 3/4/5 state. */
fun MigrationManager.ensureV6(saveDb: SQLiteDatabase, campaignId: String) {
    ensureV4(saveDb, campaignId)
    saveDb.beginTransaction()
    try {
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS progression_domain_definitions(
                domain_uid TEXT PRIMARY KEY,
                world_pack_uid TEXT NOT NULL,
                domain_key TEXT NOT NULL,
                display_name TEXT NOT NULL,
                category TEXT NOT NULL,
                parent_domain_uid TEXT,
                applies_to_talent INTEGER NOT NULL CHECK(applies_to_talent IN (0,1)),
                applies_to_potential INTEGER NOT NULL CHECK(applies_to_potential IN (0,1)),
                definition_version INTEGER NOT NULL CHECK(definition_version >= 1),
                provenance TEXT NOT NULL,
                UNIQUE(world_pack_uid,domain_key),
                FOREIGN KEY(parent_domain_uid) REFERENCES progression_domain_definitions(domain_uid),
                CHECK(applies_to_talent=1 OR applies_to_potential=1),
                CHECK(parent_domain_uid IS NULL OR parent_domain_uid<>domain_uid))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS talent_profile_entries(
                campaign_id TEXT NOT NULL,
                character_uid TEXT NOT NULL,
                domain_uid TEXT NOT NULL,
                base_value REAL NOT NULL CHECK(base_value >= 0.0),
                entry_version INTEGER NOT NULL CHECK(entry_version >= 1),
                provenance TEXT NOT NULL,
                PRIMARY KEY(campaign_id,character_uid,domain_uid),
                FOREIGN KEY(domain_uid) REFERENCES progression_domain_definitions(domain_uid))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS potential_profile_entries(
                campaign_id TEXT NOT NULL,
                character_uid TEXT NOT NULL,
                domain_uid TEXT NOT NULL,
                dimension_uid TEXT NOT NULL,
                base_value REAL NOT NULL CHECK(base_value >= 0.0),
                entry_version INTEGER NOT NULL CHECK(entry_version >= 1),
                provenance TEXT NOT NULL,
                PRIMARY KEY(campaign_id,character_uid,domain_uid,dimension_uid),
                FOREIGN KEY(domain_uid) REFERENCES progression_domain_definitions(domain_uid))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS legacy_progression_evidence(
                evidence_uid TEXT NOT NULL,
                campaign_id TEXT NOT NULL,
                character_uid TEXT NOT NULL,
                legacy_key TEXT NOT NULL,
                raw_value TEXT NOT NULL,
                source_type TEXT NOT NULL,
                source_uid TEXT NOT NULL,
                source_version INTEGER NOT NULL CHECK(source_version >= 1),
                provenance TEXT NOT NULL,
                PRIMARY KEY(campaign_id,evidence_uid))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS legacy_progression_mappings(
                campaign_id TEXT NOT NULL,
                evidence_uid TEXT NOT NULL,
                axis TEXT NOT NULL CHECK(axis IN ('TALENT','POTENTIAL')),
                domain_uid TEXT NOT NULL,
                dimension_uid TEXT,
                world_pack_uid TEXT NOT NULL,
                mapping_version INTEGER NOT NULL CHECK(mapping_version >= 1),
                provenance TEXT NOT NULL,
                PRIMARY KEY(campaign_id,evidence_uid),
                FOREIGN KEY(campaign_id,evidence_uid) REFERENCES legacy_progression_evidence(campaign_id,evidence_uid),
                FOREIGN KEY(domain_uid) REFERENCES progression_domain_definitions(domain_uid),
                CHECK((axis='TALENT' AND dimension_uid IS NULL) OR (axis='POTENTIAL' AND dimension_uid IS NOT NULL)))
        """.trimIndent())
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_progression_domains_pack ON progression_domain_definitions(world_pack_uid,category,domain_uid)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_talent_profile_character ON talent_profile_entries(campaign_id,character_uid,domain_uid)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_potential_profile_character ON potential_profile_entries(campaign_id,character_uid,domain_uid,dimension_uid)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_legacy_progression_character ON legacy_progression_evidence(campaign_id,character_uid,legacy_key)")
        saveDb.execSQL("INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes) VALUES('RPGOS-6.0-TALENT-POTENTIAL',strftime('%s','now'),'Adds independent authoritative Talent/Potential profiles and explicit legacy evidence mappings; no semantic guessing or progression side effects')")
        saveDb.setTransactionSuccessful()
    } finally { saveDb.endTransaction() }
}
