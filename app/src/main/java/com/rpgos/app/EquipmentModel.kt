package com.rpgos.app

enum class EquipmentDefinitionStatus { ACTIVE, DEPRECATED }

const val DEFAULT_EQUIPMENT_LOADOUT_UID = "RPGOS-DEFAULT-LOADOUT"
const val EQUIPMENT_MODIFIER_SOURCE_TYPE = "EQUIPMENT_ITEM_INSTANCE"

data class EquipmentSlotDefinition(
    val slotUid: String,
    val worldPackUid: String,
    val key: String,
    val displayName: String,
    val slotGroupUid: String? = null,
    val capacity: Int = 1,
    val exclusiveGroupUid: String? = null,
    val definitionStatus: EquipmentDefinitionStatus = EquipmentDefinitionStatus.ACTIVE,
    val definitionVersion: Long = 1L,
    val provenance: String,
    val metadataJson: String? = null
)

data class EquipmentCompatibilityRule(
    val ruleUid: String,
    val worldPackUid: String,
    val itemDefinitionUid: String,
    val requiredSlotUids: List<String>,
    val exclusiveGroupUid: String? = null,
    val ruleVersion: Long = 1L,
    val provenance: String
)

data class PlayerEquipment(
    val campaignId: String,
    val characterUid: String,
    val equipmentEntryUid: String,
    val itemInstanceUid: String,
    val compatibilityRuleUid: String,
    val loadoutUid: String = DEFAULT_EQUIPMENT_LOADOUT_UID,
    val entryVersion: Long = 1L,
    val provenance: String
)

data class EquipmentRecord(
    val equipment: PlayerEquipment,
    val itemInstance: ItemInstance,
    val occupiedSlotUids: List<String>
)

object EquipmentPolicy {
    fun validateSlot(slot: EquipmentSlotDefinition) {
        require(slot.slotUid.isNotBlank()) { "slotUid must not be blank" }
        require(slot.worldPackUid.isNotBlank()) { "worldPackUid must not be blank" }
        require(slot.key.isNotBlank()) { "slot key must not be blank" }
        require(slot.displayName.isNotBlank()) { "slot displayName must not be blank" }
        require(slot.capacity > 0) { "slot capacity must be positive" }
        require(slot.definitionVersion >= 1L) { "slot definitionVersion must be at least 1" }
        require(slot.provenance.isNotBlank()) { "slot provenance must not be blank" }
        require(slot.slotGroupUid == null || slot.slotGroupUid.isNotBlank()) { "slotGroupUid must be null or nonblank" }
        require(slot.exclusiveGroupUid == null || slot.exclusiveGroupUid.isNotBlank()) { "exclusiveGroupUid must be null or nonblank" }
    }

    fun validateRule(rule: EquipmentCompatibilityRule) {
        require(rule.ruleUid.isNotBlank()) { "ruleUid must not be blank" }
        require(rule.worldPackUid.isNotBlank()) { "worldPackUid must not be blank" }
        require(rule.itemDefinitionUid.isNotBlank()) { "itemDefinitionUid must not be blank" }
        require(rule.requiredSlotUids.isNotEmpty()) { "equipment rule requires at least one slot" }
        require(rule.requiredSlotUids.all { it.isNotBlank() }) { "required slot UID must not be blank" }
        require(rule.requiredSlotUids.size == rule.requiredSlotUids.distinct().size) { "duplicate required slot UID" }
        require(rule.ruleVersion >= 1L) { "ruleVersion must be at least 1" }
        require(rule.provenance.isNotBlank()) { "rule provenance must not be blank" }
        require(rule.exclusiveGroupUid == null || rule.exclusiveGroupUid.isNotBlank()) { "exclusiveGroupUid must be null or nonblank" }
    }

    fun validateEquipment(entry: PlayerEquipment) {
        require(entry.campaignId.isNotBlank()) { "campaignId must not be blank" }
        require(entry.characterUid.isNotBlank()) { "characterUid must not be blank" }
        require(entry.equipmentEntryUid.isNotBlank()) { "equipmentEntryUid must not be blank" }
        require(entry.itemInstanceUid.isNotBlank()) { "itemInstanceUid must not be blank" }
        require(entry.compatibilityRuleUid.isNotBlank()) { "compatibilityRuleUid must not be blank" }
        require(entry.loadoutUid.isNotBlank()) { "loadoutUid must not be blank" }
        require(entry.entryVersion >= 1L) { "entryVersion must be at least 1" }
        require(entry.provenance.isNotBlank()) { "equipment provenance must not be blank" }
    }
}
