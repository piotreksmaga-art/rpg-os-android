package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

const val PHASE10_MIGRATION_ID = "RPGOS-10.0-INVENTORY"

fun MigrationManager.ensureV10(saveDb: SQLiteDatabase, campaignId: String) {
    ensureV9RequirementHotfix(saveDb, campaignId)
    saveDb.beginTransaction()
    try {
        saveDb.execSQL("""CREATE TABLE IF NOT EXISTS item_definitions_v2(item_definition_uid TEXT PRIMARY KEY,world_pack_uid TEXT NOT NULL,item_key TEXT NOT NULL,display_name TEXT NOT NULL,category TEXT,storage_policy TEXT NOT NULL CHECK(storage_policy IN ('STACKABLE','UNIQUE_INSTANCE')),definition_status TEXT NOT NULL CHECK(definition_status IN ('ACTIVE','DEPRECATED')),definition_version INTEGER NOT NULL CHECK(definition_version>=1),provenance TEXT NOT NULL,UNIQUE(world_pack_uid,item_key))""")
        saveDb.execSQL("""CREATE TABLE IF NOT EXISTS item_instances(campaign_id TEXT NOT NULL,item_instance_uid TEXT NOT NULL,item_definition_uid TEXT NOT NULL,instance_version INTEGER NOT NULL CHECK(instance_version>=1),provenance TEXT NOT NULL,PRIMARY KEY(campaign_id,item_instance_uid),FOREIGN KEY(item_definition_uid) REFERENCES item_definitions_v2(item_definition_uid))""")
        saveDb.execSQL("""CREATE TABLE IF NOT EXISTS player_inventory_stacks(campaign_id TEXT NOT NULL,character_uid TEXT NOT NULL,item_definition_uid TEXT NOT NULL,quantity INTEGER NOT NULL CHECK(quantity>0),entry_version INTEGER NOT NULL CHECK(entry_version>=1),provenance TEXT NOT NULL,PRIMARY KEY(campaign_id,character_uid,item_definition_uid),FOREIGN KEY(item_definition_uid) REFERENCES item_definitions_v2(item_definition_uid))""")
        saveDb.execSQL("""CREATE TABLE IF NOT EXISTS player_inventory_unique(campaign_id TEXT NOT NULL,character_uid TEXT NOT NULL,item_instance_uid TEXT NOT NULL,entry_version INTEGER NOT NULL CHECK(entry_version>=1),provenance TEXT NOT NULL,PRIMARY KEY(campaign_id,character_uid,item_instance_uid),UNIQUE(campaign_id,item_instance_uid),FOREIGN KEY(campaign_id,item_instance_uid) REFERENCES item_instances(campaign_id,item_instance_uid))""")
        saveDb.execSQL("""CREATE TABLE IF NOT EXISTS legacy_inventory_mappings(campaign_id TEXT NOT NULL,character_uid TEXT NOT NULL,legacy_evidence_uid TEXT NOT NULL,canonical_item_definition_uid TEXT NOT NULL,canonical_item_instance_uid TEXT,world_pack_uid TEXT NOT NULL,mapping_version INTEGER NOT NULL CHECK(mapping_version>=1),provenance TEXT NOT NULL,PRIMARY KEY(campaign_id,character_uid,legacy_evidence_uid),FOREIGN KEY(canonical_item_definition_uid) REFERENCES item_definitions_v2(item_definition_uid),FOREIGN KEY(campaign_id,canonical_item_instance_uid) REFERENCES item_instances(campaign_id,item_instance_uid))""")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_item_definitions_pack ON item_definitions_v2(world_pack_uid,item_key)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_item_instances_definition ON item_instances(campaign_id,item_definition_uid,item_instance_uid)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_inventory_stacks_player ON player_inventory_stacks(campaign_id,character_uid,item_definition_uid)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_inventory_unique_player ON player_inventory_unique(campaign_id,character_uid,item_instance_uid)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_legacy_inventory_target ON legacy_inventory_mappings(campaign_id,character_uid,canonical_item_definition_uid)")
        saveDb.execSQL("INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes) VALUES('$PHASE10_MIGRATION_ID',strftime('%s','now'),'Generic inventory: definitions, unique instances, stack possession and explicit lossless legacy mappings; no Equipment or Ownership semantics')")
        saveDb.setTransactionSuccessful()
    } finally { saveDb.endTransaction() }
}
