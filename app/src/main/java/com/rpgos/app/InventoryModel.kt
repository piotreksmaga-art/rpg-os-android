package com.rpgos.app

enum class ItemDefinitionStatus { ACTIVE, DEPRECATED }
enum class ItemStoragePolicy { STACKABLE, UNIQUE_INSTANCE }
enum class InventoryAuthoritySource { TYPED, LEGACY_MAPPED, LEGACY_UNRESOLVED }

const val UNIVERSAL_WORLD_OBJECT_ITEM_DEFINITION_UID="RPGOS:UNIVERSAL:ITEM:WORLD_OBJECT"
const val UNIVERSAL_WORLD_OBJECT_ITEM_PACK_UID="RPGOS:UNIVERSAL"
const val UNIVERSAL_WORLD_OBJECT_ITEM_KEY="world-object"

internal fun universalWorldObjectItemDefinition(provenance:String)=ItemDefinition(
 UNIVERSAL_WORLD_OBJECT_ITEM_DEFINITION_UID,UNIVERSAL_WORLD_OBJECT_ITEM_PACK_UID,UNIVERSAL_WORLD_OBJECT_ITEM_KEY,
 "Przedmiot świata","WORLD_OBJECT",ItemStoragePolicy.UNIQUE_INSTANCE,ItemDefinitionStatus.ACTIVE,1,provenance
)

data class ItemDefinition(val itemDefinitionUid:String,val worldPackUid:String,val key:String,val displayName:String,val category:String?=null,val storagePolicy:ItemStoragePolicy,val definitionStatus:ItemDefinitionStatus=ItemDefinitionStatus.ACTIVE,val definitionVersion:Long=1,val provenance:String)
data class ItemInstance(val campaignId:String,val itemInstanceUid:String,val itemDefinitionUid:String,val instanceVersion:Long=1,val provenance:String)
data class PlayerInventoryStack(val campaignId:String,val characterUid:String,val itemDefinitionUid:String,val quantity:Long,val entryVersion:Long=1,val provenance:String)
data class PlayerInventoryUnique(val campaignId:String,val characterUid:String,val itemInstanceUid:String,val entryVersion:Long=1,val provenance:String)
data class LegacyInventoryEvidence(val campaignId:String,val characterUid:String,val evidenceUid:String,val itemName:String?,val rowCount:Long,val rawFields:Map<String,String?>)
data class LegacyInventoryMapping(val campaignId:String,val characterUid:String,val legacyEvidenceUid:String,val canonicalItemDefinitionUid:String,val canonicalItemInstanceUid:String?=null,val worldPackUid:String,val mappingVersion:Long=1,val provenance:String)
data class InventoryStackView(val stack:PlayerInventoryStack,val authoritySource:InventoryAuthoritySource,val mappedLegacyEvidenceUid:String?=null)
data class InventoryUniqueView(val entry:PlayerInventoryUnique,val instance:ItemInstance,val authoritySource:InventoryAuthoritySource,val mappedLegacyEvidenceUid:String?=null)
data class ReconciledInventory(val stacks:List<InventoryStackView>,val uniqueItems:List<InventoryUniqueView>,val unresolvedLegacy:List<LegacyInventoryEvidence>)

object InventoryPolicy{
 fun requireDefinition(d:ItemDefinition){require(d.itemDefinitionUid.isNotBlank());require(d.worldPackUid.isNotBlank());require(d.key.isNotBlank());require(d.displayName.isNotBlank());require(d.definitionVersion>=1);require(d.provenance.isNotBlank())}
 fun requireQuantity(q:Long){require(q>0){"Inventory quantity must be greater than zero"}}
 fun checkedAdd(current:Long,delta:Long):Long{require(delta>0);return Math.addExact(current,delta).also(::requireQuantity)}
}
