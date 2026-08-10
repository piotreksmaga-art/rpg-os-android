package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

class EquipmentStore(private val db: SQLiteDatabase, private val campaignId: String) {
    init {
        require(campaignId.isNotBlank()) { "campaignId must not be blank" }
        MigrationManager().ensureV11(db, campaignId)
    }

    fun registerSlots(worldPackUid: String, slots: List<EquipmentSlotDefinition>) {
        require(worldPackUid.isNotBlank()) { "worldPackUid must not be blank" }
        slots.forEach {
            EquipmentPolicy.validateSlot(it)
            require(it.worldPackUid == worldPackUid) { "Equipment slot belongs to another World Pack" }
        }
        require(slots.map { it.slotUid }.distinct().size == slots.size) { "Duplicate slot UID in registration batch" }
        db.beginTransaction()
        try {
            slots.sortedBy { it.slotUid }.forEach { slot ->
                require(!slotExists(slot.slotUid)) { "Duplicate equipment slot UID: ${slot.slotUid}" }
                require(!slotKeyExists(slot.worldPackUid, slot.key)) { "Duplicate equipment slot key in World Pack: ${slot.key}" }
                db.execSQL(
                    """INSERT INTO equipment_slot_definitions(slot_uid,world_pack_uid,slot_key,display_name,slot_group_uid,capacity,exclusive_group_uid,definition_status,definition_version,provenance,metadata_json)
                       VALUES(?,?,?,?,?,?,?,?,?,?,?)""".trimIndent(),
                    arrayOf<Any?>(slot.slotUid,slot.worldPackUid,slot.key,slot.displayName,slot.slotGroupUid,slot.capacity,slot.exclusiveGroupUid,slot.definitionStatus.name,slot.definitionVersion,slot.provenance,slot.metadataJson)
                )
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun slotDefinitions(worldPackUid: String? = null): List<EquipmentSlotDefinition> {
        val out = mutableListOf<EquipmentSlotDefinition>()
        val where = if (worldPackUid == null) "" else " WHERE world_pack_uid=?"
        val args = if (worldPackUid == null) null else arrayOf(worldPackUid)
        db.rawQuery("SELECT slot_uid,world_pack_uid,slot_key,display_name,slot_group_uid,capacity,exclusive_group_uid,definition_status,definition_version,provenance,metadata_json FROM equipment_slot_definitions$where ORDER BY world_pack_uid,slot_key,slot_uid", args).use { c ->
            while (c.moveToNext()) out += EquipmentSlotDefinition(
                slotUid=c.getString(0),worldPackUid=c.getString(1),key=c.getString(2),displayName=c.getString(3),
                slotGroupUid=if(c.isNull(4))null else c.getString(4),capacity=c.getInt(5),exclusiveGroupUid=if(c.isNull(6))null else c.getString(6),
                definitionStatus=EquipmentDefinitionStatus.valueOf(c.getString(7)),definitionVersion=c.getLong(8),provenance=c.getString(9),metadataJson=if(c.isNull(10))null else c.getString(10)
            )
        }
        return out
    }

    fun registerCompatibilityRules(worldPackUid: String, rules: List<EquipmentCompatibilityRule>) {
        require(worldPackUid.isNotBlank()) { "worldPackUid must not be blank" }
        rules.forEach { rule ->
            EquipmentPolicy.validateRule(rule)
            require(rule.worldPackUid == worldPackUid) { "Equipment rule belongs to another World Pack" }
            val item = requireItemDefinition(rule.itemDefinitionUid)
            require(item.worldPackUid == worldPackUid) { "Equipment rule cannot hijack an ItemDefinition from another World Pack" }
            rule.requiredSlotUids.sorted().forEach { uid ->
                val slot = requireSlot(uid)
                require(slot.worldPackUid == worldPackUid) { "Equipment rule cannot bind a slot from another World Pack" }
            }
        }
        require(rules.map { it.ruleUid }.distinct().size == rules.size) { "Duplicate equipment rule UID in registration batch" }
        db.beginTransaction()
        try {
            rules.sortedBy { it.ruleUid }.forEach { rule ->
                require(!ruleExists(rule.ruleUid)) { "Duplicate equipment compatibility rule UID: ${rule.ruleUid}" }
                db.execSQL("INSERT INTO equipment_compatibility_rules(rule_uid,world_pack_uid,item_definition_uid,exclusive_group_uid,rule_version,provenance) VALUES(?,?,?,?,?,?)",
                    arrayOf<Any?>(rule.ruleUid,rule.worldPackUid,rule.itemDefinitionUid,rule.exclusiveGroupUid,rule.ruleVersion,rule.provenance))
                rule.requiredSlotUids.sorted().forEach { slotUid ->
                    db.execSQL("INSERT INTO equipment_rule_slots(rule_uid,slot_uid) VALUES(?,?)", arrayOf(rule.ruleUid,slotUid))
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun compatibilityRules(worldPackUid: String? = null): List<EquipmentCompatibilityRule> {
        val out = mutableListOf<EquipmentCompatibilityRule>()
        val where = if (worldPackUid == null) "" else " WHERE world_pack_uid=?"
        val args = if (worldPackUid == null) null else arrayOf(worldPackUid)
        db.rawQuery("SELECT rule_uid,world_pack_uid,item_definition_uid,exclusive_group_uid,rule_version,provenance FROM equipment_compatibility_rules$where ORDER BY world_pack_uid,rule_uid", args).use { c ->
            while(c.moveToNext()) out += EquipmentCompatibilityRule(c.getString(0),c.getString(1),c.getString(2),requiredSlots(c.getString(0)),if(c.isNull(3))null else c.getString(3),c.getLong(4),c.getString(5))
        }
        return out
    }

    fun equip(
        characterUid: String,
        itemInstanceUid: String,
        compatibilityRuleUid: String,
        requestedSlotUids: List<String>,
        equipmentEntryUid: String,
        provenance: String,
        entryVersion: Long = 1L
    ): EquipmentRecord {
        val entry = PlayerEquipment(campaignId,characterUid,equipmentEntryUid,itemInstanceUid,compatibilityRuleUid,DEFAULT_EQUIPMENT_LOADOUT_UID,entryVersion,provenance)
        EquipmentPolicy.validateEquipment(entry)
        require(requestedSlotUids.isNotEmpty()) { "requested slots must not be empty" }
        require(requestedSlotUids.all { it.isNotBlank() }) { "requested slot UID must not be blank" }
        require(requestedSlotUids.size == requestedSlotUids.distinct().size) { "duplicate requested slot UID" }

        val instance = requireInstance(itemInstanceUid)
        val item = requireItemDefinition(instance.itemDefinitionUid)
        require(item.storagePolicy == ItemStoragePolicy.UNIQUE_INSTANCE) { "Stackable commodity cannot be equipped without an explicit physical ItemInstance contract" }
        require(item.definitionStatus == ItemDefinitionStatus.ACTIVE) { "Deprecated ItemDefinition cannot be newly equipped" }
        require(isPossessed(characterUid,itemInstanceUid)) { "ItemInstance is not possessed by character: $itemInstanceUid" }
        require(!isEquipped(itemInstanceUid)) { "ItemInstance is already equipped: $itemInstanceUid" }

        val rule = requireRule(compatibilityRuleUid)
        require(rule.itemDefinitionUid == instance.itemDefinitionUid) { "ItemInstance is incompatible with equipment rule" }
        require(rule.worldPackUid == item.worldPackUid) { "Equipment rule World Pack does not match ItemDefinition owner" }
        val canonicalSlots = rule.requiredSlotUids.sorted()
        require(requestedSlotUids.sorted() == canonicalSlots) { "Requested slots do not match explicit compatibility rule" }
        val slots = canonicalSlots.map(::requireSlot)
        slots.forEach {
            require(it.worldPackUid == rule.worldPackUid) { "Equipment slot World Pack does not match rule owner" }
            require(it.definitionStatus == EquipmentDefinitionStatus.ACTIVE) { "Deprecated equipment slot cannot receive a new equip" }
            val occupied = occupancyCount(characterUid,it.slotUid,DEFAULT_EQUIPMENT_LOADOUT_UID)
            require(occupied < it.capacity) { "Equipment slot capacity exhausted: ${it.slotUid}" }
        }
        val proposedGroups = linkedSetOf<Pair<String,String>>()
        rule.exclusiveGroupUid?.let { proposedGroups += rule.worldPackUid to it }
        slots.forEach { slot -> slot.exclusiveGroupUid?.let { proposedGroups += slot.worldPackUid to it } }
        val conflicts = proposedGroups.intersect(activeConflictGroups(characterUid,DEFAULT_EQUIPMENT_LOADOUT_UID)).sortedWith(compareBy<Pair<String,String>>({it.first},{it.second}))
        require(conflicts.isEmpty()) { "Equipment exclusive-group conflict: ${conflicts.joinToString { "${it.first}:${it.second}" }}" }

        db.beginTransaction()
        try {
            db.execSQL("INSERT INTO player_equipment(campaign_id,character_uid,equipment_entry_uid,item_instance_uid,compatibility_rule_uid,loadout_uid,entry_version,provenance) VALUES(?,?,?,?,?,?,?,?)",
                arrayOf(entry.campaignId,entry.characterUid,entry.equipmentEntryUid,entry.itemInstanceUid,entry.compatibilityRuleUid,entry.loadoutUid,entry.entryVersion,entry.provenance))
            canonicalSlots.forEach { slotUid ->
                db.execSQL("INSERT INTO player_equipment_slots(campaign_id,character_uid,equipment_entry_uid,slot_uid) VALUES(?,?,?,?)", arrayOf(campaignId,characterUid,equipmentEntryUid,slotUid))
            }
            ModifierStore(db,campaignId).setSourceActive(characterUid,EQUIPMENT_MODIFIER_SOURCE_TYPE,itemInstanceUid,true,ModifierLifecycle.EQUIPMENT)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return EquipmentRecord(entry,instance,canonicalSlots)
    }

    fun unequip(characterUid: String, equipmentEntryUid: String): EquipmentRecord {
        require(characterUid.isNotBlank() && equipmentEntryUid.isNotBlank()) { "equipment identity must not be blank" }
        val record = equipment(characterUid).singleOrNull { it.equipment.equipmentEntryUid == equipmentEntryUid }
            ?: error("Equipment entry not found: $equipmentEntryUid")
        db.beginTransaction()
        try {
            ModifierStore(db,campaignId).setSourceActive(characterUid,EQUIPMENT_MODIFIER_SOURCE_TYPE,record.equipment.itemInstanceUid,false,ModifierLifecycle.EQUIPMENT)
            db.delete("player_equipment_slots","campaign_id=? AND character_uid=? AND equipment_entry_uid=?",arrayOf(campaignId,characterUid,equipmentEntryUid))
            val removed = db.delete("player_equipment","campaign_id=? AND character_uid=? AND equipment_entry_uid=?",arrayOf(campaignId,characterUid,equipmentEntryUid))
            require(removed == 1) { "Equipment entry disappeared during unequip: $equipmentEntryUid" }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return record
    }

    fun equipment(characterUid: String, loadoutUid: String = DEFAULT_EQUIPMENT_LOADOUT_UID): List<EquipmentRecord> {
        require(characterUid.isNotBlank() && loadoutUid.isNotBlank()) { "equipment scope must not be blank" }
        val slotsByEntry = linkedMapOf<String,MutableList<String>>()
        db.rawQuery("SELECT equipment_entry_uid,slot_uid FROM player_equipment_slots WHERE campaign_id=? AND character_uid=? ORDER BY equipment_entry_uid,slot_uid",arrayOf(campaignId,characterUid)).use { c ->
            while(c.moveToNext()) slotsByEntry.getOrPut(c.getString(0)){ mutableListOf() }.add(c.getString(1))
        }
        val out = mutableListOf<EquipmentRecord>()
        db.rawQuery("""SELECT e.character_uid,e.equipment_entry_uid,e.item_instance_uid,e.compatibility_rule_uid,e.loadout_uid,e.entry_version,e.provenance,
                   i.item_definition_uid,i.instance_version,i.provenance
            FROM player_equipment e JOIN item_instances i ON i.campaign_id=e.campaign_id AND i.item_instance_uid=e.item_instance_uid
            WHERE e.campaign_id=? AND e.character_uid=? AND e.loadout_uid=? ORDER BY e.equipment_entry_uid""".trimIndent(),arrayOf(campaignId,characterUid,loadoutUid)).use { c ->
            while(c.moveToNext()) {
                val entry=PlayerEquipment(campaignId,c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getLong(5),c.getString(6))
                val instance=ItemInstance(campaignId,c.getString(2),c.getString(7),c.getLong(8),c.getString(9))
                out += EquipmentRecord(entry,instance,slotsByEntry[entry.equipmentEntryUid]?.sorted().orEmpty())
            }
        }
        return out
    }

    fun registerEquipmentModifiers(characterUid: String, itemInstanceUid: String, modifiers: List<Modifier>) {
        require(isPossessed(characterUid,itemInstanceUid)) { "Cannot bind equipment modifiers to unpossessed ItemInstance" }
        requireInstance(itemInstanceUid)
        val activeNow = isEquipped(itemInstanceUid)
        val store = ModifierStore(db,campaignId)
        db.beginTransaction()
        try {
            modifiers.forEach { modifier ->
                require(modifier.campaignId == campaignId && modifier.characterUid == characterUid) { "Equipment modifier scope mismatch" }
                require(modifier.lifecycle == ModifierLifecycle.EQUIPMENT) { "Equipment modifier must use EQUIPMENT lifecycle" }
                require(modifier.sourceType == EQUIPMENT_MODIFIER_SOURCE_TYPE) { "Equipment modifier sourceType mismatch" }
                require(modifier.sourceUid == itemInstanceUid) { "Equipment modifier sourceUid must be exact ItemInstance UID" }
                store.save(modifier.copy(sourceActive=activeNow))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun isEquipped(itemInstanceUid: String): Boolean = db.rawQuery("SELECT 1 FROM player_equipment WHERE campaign_id=? AND item_instance_uid=? LIMIT 1",arrayOf(campaignId,itemInstanceUid)).use { it.moveToFirst() }

    private fun isPossessed(characterUid:String,itemInstanceUid:String):Boolean =
        InventoryStore(db,campaignId).reconciled(characterUid).uniqueItems.any { it.instance.itemInstanceUid == itemInstanceUid }

    private fun requireInstance(uid:String):ItemInstance = db.rawQuery("SELECT item_definition_uid,instance_version,provenance FROM item_instances WHERE campaign_id=? AND item_instance_uid=? LIMIT 1",arrayOf(campaignId,uid)).use { c ->
        require(c.moveToFirst()) { "Missing ItemInstance: $uid" }
        ItemInstance(campaignId,uid,c.getString(0),c.getLong(1),c.getString(2))
    }

    private fun requireItemDefinition(uid:String):ItemDefinition = db.rawQuery("SELECT world_pack_uid,item_key,display_name,category,storage_policy,definition_status,definition_version,provenance FROM item_definitions_v2 WHERE item_definition_uid=? LIMIT 1",arrayOf(uid)).use { c ->
        require(c.moveToFirst()) { "Missing ItemDefinition: $uid" }
        ItemDefinition(uid,c.getString(0),c.getString(1),c.getString(2),if(c.isNull(3))null else c.getString(3),ItemStoragePolicy.valueOf(c.getString(4)),ItemDefinitionStatus.valueOf(c.getString(5)),c.getLong(6),c.getString(7))
    }

    private fun requireSlot(uid:String):EquipmentSlotDefinition = db.rawQuery("SELECT world_pack_uid,slot_key,display_name,slot_group_uid,capacity,exclusive_group_uid,definition_status,definition_version,provenance,metadata_json FROM equipment_slot_definitions WHERE slot_uid=? LIMIT 1",arrayOf(uid)).use { c ->
        require(c.moveToFirst()) { "Missing EquipmentSlotDefinition: $uid" }
        EquipmentSlotDefinition(uid,c.getString(0),c.getString(1),c.getString(2),if(c.isNull(3))null else c.getString(3),c.getInt(4),if(c.isNull(5))null else c.getString(5),EquipmentDefinitionStatus.valueOf(c.getString(6)),c.getLong(7),c.getString(8),if(c.isNull(9))null else c.getString(9))
    }

    private fun requireRule(uid:String):EquipmentCompatibilityRule = db.rawQuery("SELECT world_pack_uid,item_definition_uid,exclusive_group_uid,rule_version,provenance FROM equipment_compatibility_rules WHERE rule_uid=? LIMIT 1",arrayOf(uid)).use { c ->
        require(c.moveToFirst()) { "Missing EquipmentCompatibilityRule: $uid" }
        EquipmentCompatibilityRule(uid,c.getString(0),c.getString(1),requiredSlots(uid),if(c.isNull(2))null else c.getString(2),c.getLong(3),c.getString(4))
    }

    private fun requiredSlots(ruleUid:String):List<String> { val out=mutableListOf<String>();db.rawQuery("SELECT slot_uid FROM equipment_rule_slots WHERE rule_uid=? ORDER BY slot_uid",arrayOf(ruleUid)).use{c->while(c.moveToNext())out+=c.getString(0)};return out }
    private fun occupancyCount(characterUid:String,slotUid:String,loadoutUid:String):Int = db.rawQuery("""SELECT COUNT(*) FROM player_equipment_slots s JOIN player_equipment e ON e.campaign_id=s.campaign_id AND e.equipment_entry_uid=s.equipment_entry_uid WHERE s.campaign_id=? AND s.character_uid=? AND s.slot_uid=? AND e.loadout_uid=?""",arrayOf(campaignId,characterUid,slotUid,loadoutUid)).use{c->c.moveToFirst();c.getInt(0)}

    private fun activeConflictGroups(characterUid:String,loadoutUid:String):Set<Pair<String,String>> {
        val out=linkedSetOf<Pair<String,String>>()
        db.rawQuery("""SELECT r.world_pack_uid,r.exclusive_group_uid FROM player_equipment e JOIN equipment_compatibility_rules r ON r.rule_uid=e.compatibility_rule_uid WHERE e.campaign_id=? AND e.character_uid=? AND e.loadout_uid=? AND r.exclusive_group_uid IS NOT NULL ORDER BY r.world_pack_uid,r.exclusive_group_uid""",arrayOf(campaignId,characterUid,loadoutUid)).use{c->while(c.moveToNext())out+=c.getString(0) to c.getString(1)}
        db.rawQuery("""SELECT d.world_pack_uid,d.exclusive_group_uid FROM player_equipment_slots s JOIN player_equipment e ON e.campaign_id=s.campaign_id AND e.equipment_entry_uid=s.equipment_entry_uid JOIN equipment_slot_definitions d ON d.slot_uid=s.slot_uid WHERE s.campaign_id=? AND s.character_uid=? AND e.loadout_uid=? AND d.exclusive_group_uid IS NOT NULL ORDER BY d.world_pack_uid,d.exclusive_group_uid""",arrayOf(campaignId,characterUid,loadoutUid)).use{c->while(c.moveToNext())out+=c.getString(0) to c.getString(1)}
        return out
    }

    private fun slotExists(uid:String)=db.rawQuery("SELECT 1 FROM equipment_slot_definitions WHERE slot_uid=? LIMIT 1",arrayOf(uid)).use{it.moveToFirst()}
    private fun slotKeyExists(pack:String,key:String)=db.rawQuery("SELECT 1 FROM equipment_slot_definitions WHERE world_pack_uid=? AND slot_key=? LIMIT 1",arrayOf(pack,key)).use{it.moveToFirst()}
    private fun ruleExists(uid:String)=db.rawQuery("SELECT 1 FROM equipment_compatibility_rules WHERE rule_uid=? LIMIT 1",arrayOf(uid)).use{it.moveToFirst()}
}
