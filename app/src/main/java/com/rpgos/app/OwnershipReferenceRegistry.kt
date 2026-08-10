package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

enum class OwnershipReferenceStatus { ACTIVE, RETIRED }

/**
 * Minimal campaign-scoped authority for owner and generic non-item asset references.
 * It does not define future entity/asset domains; it only registers stable identities
 * that those domains may own and retire. ITEM_INSTANCE remains authoritative in Inventory.
 */
class OwnershipReferenceRegistry(private val db: SQLiteDatabase, private val campaignId: String) {
    init {
        require(campaignId.isNotBlank()) { "campaignId must not be blank" }
        MigrationManager().ensureV12(db, campaignId)
    }

    fun registerOwner(owner: OwnershipOwnerRef, provenance: String) {
        OwnershipPolicy.validateOwner(owner)
        require(provenance.isNotBlank()) { "owner reference provenance must not be blank" }
        db.execSQL(
            """INSERT INTO ownership_party_registry(campaign_id,owner_kind_uid,owner_uid,reference_status,provenance)
               VALUES(?,?,?,'ACTIVE',?)""".trimIndent(),
            arrayOf(campaignId, owner.ownerKindUid, owner.ownerUid, provenance)
        )
    }

    fun retireOwner(owner: OwnershipOwnerRef, provenance: String) {
        OwnershipPolicy.validateOwner(owner)
        require(provenance.isNotBlank()) { "owner retirement provenance must not be blank" }
        val s = db.compileStatement(
            """UPDATE ownership_party_registry
               SET reference_status='RETIRED', retirement_provenance=?
               WHERE campaign_id=? AND owner_kind_uid=? AND owner_uid=? AND reference_status='ACTIVE'""".trimIndent()
        )
        s.use {
            it.bindString(1, provenance)
            it.bindString(2, campaignId)
            it.bindString(3, owner.ownerKindUid)
            it.bindString(4, owner.ownerUid)
            require(it.executeUpdateDelete() == 1) { "active ownership owner reference not found" }
        }
    }

    fun registerAsset(asset: OwnedAssetRef, provenance: String) {
        OwnershipPolicy.validateAsset(asset)
        require(asset.assetKindUid != OWNERSHIP_ASSET_KIND_ITEM_INSTANCE) { "ITEM_INSTANCE is resolved by item_instances authority" }
        require(provenance.isNotBlank()) { "asset reference provenance must not be blank" }
        db.execSQL(
            """INSERT INTO ownership_asset_registry(campaign_id,asset_kind_uid,asset_uid,reference_status,provenance)
               VALUES(?,?,?,'ACTIVE',?)""".trimIndent(),
            arrayOf(campaignId, asset.assetKindUid, asset.assetUid, provenance)
        )
    }

    fun retireAsset(asset: OwnedAssetRef, provenance: String) {
        OwnershipPolicy.validateAsset(asset)
        require(asset.assetKindUid != OWNERSHIP_ASSET_KIND_ITEM_INSTANCE) { "ITEM_INSTANCE lifecycle belongs to item authority" }
        require(provenance.isNotBlank()) { "asset retirement provenance must not be blank" }
        val s = db.compileStatement(
            """UPDATE ownership_asset_registry
               SET reference_status='RETIRED', retirement_provenance=?
               WHERE campaign_id=? AND asset_kind_uid=? AND asset_uid=? AND reference_status='ACTIVE'""".trimIndent()
        )
        s.use {
            it.bindString(1, provenance)
            it.bindString(2, campaignId)
            it.bindString(3, asset.assetKindUid)
            it.bindString(4, asset.assetUid)
            require(it.executeUpdateDelete() == 1) { "active ownership asset reference not found" }
        }
    }
}
