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

        // Hotfix guards are deliberately recreated on every ensureV11() so already-migrated V11 databases
        // receive corrected authoritative invariants without needing a destructive schema rewrite.
        listOf(
            "trg_equipment_possession_guard",
            "trg_equipment_rule_exclusive_guard",
            "trg_equipment_slot_parent_scope_guard",
            "trg_equipment_slot_capacity_guard",
            "trg_equipment_slot_exclusive_guard",
            "trg_equipped_instance_inventory_delete_guard",
            "trg_equipped_instance_inventory_transfer_guard"
        ).forEach { trigger -> saveDb.execSQL("DROP TRIGGER IF EXISTS $trigger") }

        // Authoritative concurrent validation lives in SQLite write triggers. These checks execute inside
        // the same serialized write transaction as Equipment mutation, so stale application pre-reads
        // can never commit an invalid holder, capacity, or exclusive-group state.
        saveDb.execSQL("""
            CREATE TRIGGER trg_equipment_possession_guard
            BEFORE INSERT ON player_equipment
            WHEN NOT EXISTS(
                SELECT 1 FROM player_inventory_unique p
                WHERE p.campaign_id=NEW.campaign_id AND p.character_uid=NEW.character_uid AND p.item_instance_uid=NEW.item_instance_uid)
            BEGIN SELECT RAISE(ABORT,'equipment item instance must still be possessed by character'); END
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TRIGGER trg_equipment_rule_exclusive_guard
            BEFORE INSERT ON player_equipment
            WHEN EXISTS(
                SELECT 1 FROM equipment_compatibility_rules nr
                JOIN player_equipment e ON e.campaign_id=NEW.campaign_id AND e.character_uid=NEW.character_uid AND e.loadout_uid=NEW.loadout_uid
                JOIN equipment_compatibility_rules er ON er.rule_uid=e.compatibility_rule_uid
                WHERE nr.rule_uid=NEW.compatibility_rule_uid AND nr.exclusive_group_uid IS NOT NULL
                  AND er.world_pack_uid=nr.world_pack_uid AND er.exclusive_group_uid=nr.exclusive_group_uid)
              OR EXISTS(
                SELECT 1 FROM equipment_compatibility_rules nr
                JOIN player_equipment_slots s ON s.campaign_id=NEW.campaign_id AND s.character_uid=NEW.character_uid
                JOIN player_equipment e ON e.campaign_id=s.campaign_id AND e.equipment_entry_uid=s.equipment_entry_uid AND e.loadout_uid=NEW.loadout_uid
                JOIN equipment_slot_definitions d ON d.slot_uid=s.slot_uid
                WHERE nr.rule_uid=NEW.compatibility_rule_uid AND nr.exclusive_group_uid IS NOT NULL
                  AND d.world_pack_uid=nr.world_pack_uid AND d.exclusive_group_uid=nr.exclusive_group_uid)
            BEGIN SELECT RAISE(ABORT,'equipment exclusive-group conflict'); END
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TRIGGER trg_equipment_slot_parent_scope_guard
            BEFORE INSERT ON player_equipment_slots
            WHEN NOT EXISTS(
                SELECT 1 FROM player_equipment e
                WHERE e.campaign_id=NEW.campaign_id AND e.character_uid=NEW.character_uid AND e.equipment_entry_uid=NEW.equipment_entry_uid)
            BEGIN SELECT RAISE(ABORT,'equipment slot binding parent scope mismatch'); END
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TRIGGER trg_equipment_slot_capacity_guard
            BEFORE INSERT ON player_equipment_slots
            WHEN EXISTS(
                SELECT 1
                FROM player_equipment parent
                JOIN equipment_slot_definitions d ON d.slot_uid=NEW.slot_uid
                WHERE parent.campaign_id=NEW.campaign_id
                  AND parent.character_uid=NEW.character_uid
                  AND parent.equipment_entry_uid=NEW.equipment_entry_uid
                  AND (
                    SELECT COUNT(*)
                    FROM player_equipment_slots s
                    JOIN player_equipment e ON e.campaign_id=s.campaign_id AND e.equipment_entry_uid=s.equipment_entry_uid
                    WHERE s.campaign_id=NEW.campaign_id
                      AND s.character_uid=NEW.character_uid
                      AND s.slot_uid=NEW.slot_uid
                      AND e.loadout_uid=parent.loadout_uid
                  ) >= d.capacity)
            BEGIN SELECT RAISE(ABORT,'equipment slot capacity exhausted'); END
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TRIGGER trg_equipment_slot_exclusive_guard
            BEFORE INSERT ON player_equipment_slots
            WHEN EXISTS(
                SELECT 1 FROM equipment_slot_definitions nd
                JOIN player_equipment_slots s ON s.campaign_id=NEW.campaign_id AND s.character_uid=NEW.character_uid AND s.equipment_entry_uid<>NEW.equipment_entry_uid
                JOIN player_equipment e ON e.campaign_id=s.campaign_id AND e.equipment_entry_uid=s.equipment_entry_uid
                JOIN equipment_slot_definitions ed ON ed.slot_uid=s.slot_uid
                WHERE nd.slot_uid=NEW.slot_uid AND nd.exclusive_group_uid IS NOT NULL
                  AND e.loadout_uid=(SELECT loadout_uid FROM player_equipment WHERE campaign_id=NEW.campaign_id AND equipment_entry_uid=NEW.equipment_entry_uid)
                  AND ed.world_pack_uid=nd.world_pack_uid AND ed.exclusive_group_uid=nd.exclusive_group_uid)
              OR EXISTS(
                SELECT 1 FROM equipment_slot_definitions nd
                JOIN player_equipment e ON e.campaign_id=NEW.campaign_id AND e.character_uid=NEW.character_uid AND e.equipment_entry_uid<>NEW.equipment_entry_uid
                JOIN equipment_compatibility_rules er ON er.rule_uid=e.compatibility_rule_uid
                WHERE nd.slot_uid=NEW.slot_uid AND nd.exclusive_group_uid IS NOT NULL
                  AND e.loadout_uid=(SELECT loadout_uid FROM player_equipment WHERE campaign_id=NEW.campaign_id AND equipment_entry_uid=NEW.equipment_entry_uid)
                  AND er.world_pack_uid=nd.world_pack_uid AND er.exclusive_group_uid=nd.exclusive_group_uid)
            BEGIN SELECT RAISE(ABORT,'equipment exclusive-group conflict'); END
        """.trimIndent())

        saveDb.execSQL("""
            CREATE TRIGGER trg_equipped_instance_inventory_delete_guard
            BEFORE DELETE ON player_inventory_unique
            WHEN EXISTS(SELECT 1 FROM player_equipment e WHERE e.campaign_id=OLD.campaign_id AND e.item_instance_uid=OLD.item_instance_uid)
            BEGIN SELECT RAISE(ABORT,'equipped item instance must be unequipped before inventory removal'); END
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TRIGGER trg_equipped_instance_inventory_transfer_guard
            BEFORE UPDATE OF campaign_id,character_uid,item_instance_uid ON player_inventory_unique
            WHEN EXISTS(SELECT 1 FROM player_equipment e WHERE e.campaign_id=OLD.campaign_id AND e.item_instance_uid=OLD.item_instance_uid)
            BEGIN SELECT RAISE(ABORT,'equipped item instance must be unequipped before inventory transfer'); END
        """.trimIndent())

        saveDb.execSQL("INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes) VALUES('$PHASE11_MIGRATION_ID',strftime('%s','now'),'Generic physical Equipment: World-Pack slot definitions, explicit item-slot compatibility, character-scoped exact ItemInstance loadout state and normalized multi-slot bindings; authoritative SQLite write guards serialize possession, capacity and exclusive-group invariants; equipped unique possession cannot be removed/transferred until explicit unequip; no legacy inference and no Ownership semantics')")
        saveDb.setTransactionSuccessful()
    } finally {
        saveDb.endTransaction()
    }
}
