package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

const val PHASE11_MIGRATION_ID = "RPGOS-11.0-EQUIPMENT"

/** Additive Phase 11 physical Equipment schema. Inventory remains possession authority; Ownership is not introduced here. */
fun MigrationManager.ensureV11(saveDb: SQLiteDatabase, campaignId: String) {
    ensureV10(saveDb, campaignId)
    saveDb.beginTransaction()
    try {
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS equipment_slot_definitions(
                slot_uid TEXT PRIMARY KEY,
                world_pack_uid TEXT NOT NULL,
                slot_key TEXT NOT NULL,
                display_name TEXT NOT NULL,
                slot_group_uid TEXT,
                capacity INTEGER NOT NULL CHECK(capacity > 0),
                exclusive_group_uid TEXT,
                definition_status TEXT NOT NULL CHECK(definition_status IN ('ACTIVE','DEPRECATED')),
                definition_version INTEGER NOT NULL CHECK(definition_version >= 1),
                provenance TEXT NOT NULL,
                metadata_json TEXT,
                UNIQUE(world_pack_uid,slot_key))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS equipment_compatibility_rules(
                rule_uid TEXT PRIMARY KEY,
                world_pack_uid TEXT NOT NULL,
                item_definition_uid TEXT NOT NULL,
                exclusive_group_uid TEXT,
                rule_version INTEGER NOT NULL CHECK(rule_version >= 1),
                provenance TEXT NOT NULL,
                FOREIGN KEY(item_definition_uid) REFERENCES item_definitions_v2(item_definition_uid))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS equipment_rule_slots(
                rule_uid TEXT NOT NULL,
                slot_uid TEXT NOT NULL,
                PRIMARY KEY(rule_uid,slot_uid),
                FOREIGN KEY(rule_uid) REFERENCES equipment_compatibility_rules(rule_uid) ON DELETE CASCADE,
                FOREIGN KEY(slot_uid) REFERENCES equipment_slot_definitions(slot_uid))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS player_equipment(
                campaign_id TEXT NOT NULL,
                character_uid TEXT NOT NULL,
                equipment_entry_uid TEXT NOT NULL,
                item_instance_uid TEXT NOT NULL,
                compatibility_rule_uid TEXT NOT NULL,
                loadout_uid TEXT NOT NULL,
                entry_version INTEGER NOT NULL CHECK(entry_version >= 1),
                provenance TEXT NOT NULL,
                PRIMARY KEY(campaign_id,equipment_entry_uid),
                UNIQUE(campaign_id,item_instance_uid),
                FOREIGN KEY(campaign_id,item_instance_uid) REFERENCES item_instances(campaign_id,item_instance_uid),
                FOREIGN KEY(compatibility_rule_uid) REFERENCES equipment_compatibility_rules(rule_uid))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS player_equipment_slots(
                campaign_id TEXT NOT NULL,
                character_uid TEXT NOT NULL,
                equipment_entry_uid TEXT NOT NULL,
                slot_uid TEXT NOT NULL,
                PRIMARY KEY(campaign_id,equipment_entry_uid,slot_uid),
                FOREIGN KEY(campaign_id,equipment_entry_uid) REFERENCES player_equipment(campaign_id,equipment_entry_uid) ON DELETE CASCADE,
                FOREIGN KEY(slot_uid) REFERENCES equipment_slot_definitions(slot_uid))
        """.trimIndent())
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_equipment_slots_pack ON equipment_slot_definitions(world_pack_uid,slot_key,slot_uid)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_equipment_rules_item ON equipment_compatibility_rules(world_pack_uid,item_definition_uid,rule_uid)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_player_equipment_character ON player_equipment(campaign_id,character_uid,loadout_uid,equipment_entry_uid)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_player_equipment_instance ON player_equipment(campaign_id,item_instance_uid)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_player_equipment_slots_character ON player_equipment_slots(campaign_id,character_uid,slot_uid,equipment_entry_uid)")

        // Plain Phase-10 possession mutation must fail while an exact physical instance is equipped.
        // This prevents dangling Equipment even for callers that bypass InventoryStore and execute SQL directly.
        saveDb.execSQL("""
            CREATE TRIGGER IF NOT EXISTS trg_equipped_instance_inventory_delete_guard
            BEFORE DELETE ON player_inventory_unique
            WHEN EXISTS(SELECT 1 FROM player_equipment e WHERE e.campaign_id=OLD.campaign_id AND e.item_instance_uid=OLD.item_instance_uid)
            BEGIN SELECT RAISE(ABORT,'equipped item instance must be unequipped before inventory removal'); END
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TRIGGER IF NOT EXISTS trg_equipped_instance_inventory_transfer_guard
            BEFORE UPDATE OF campaign_id,character_uid,item_instance_uid ON player_inventory_unique
            WHEN EXISTS(SELECT 1 FROM player_equipment e WHERE e.campaign_id=OLD.campaign_id AND e.item_instance_uid=OLD.item_instance_uid)
            BEGIN SELECT RAISE(ABORT,'equipped item instance must be unequipped before inventory transfer'); END
        """.trimIndent())

        saveDb.execSQL("INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes) VALUES('$PHASE11_MIGRATION_ID',strftime('%s','now'),'Generic physical Equipment: World-Pack slot definitions, explicit item-slot compatibility, character-scoped exact ItemInstance loadout state and normalized multi-slot bindings; equipped unique possession cannot be removed/transferred until explicit unequip; no legacy inference and no Ownership semantics')")
        saveDb.setTransactionSuccessful()
    } finally {
        saveDb.endTransaction()
    }
}
