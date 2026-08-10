package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

enum class OwnershipReferenceStatus { ACTIVE, RETIRED }

/**
 * Minimal generic authority for Ownership reference namespaces and campaign-scoped targets.
 * Future entity/asset domains register their namespace and stable identities here; Ownership
 * never accepts arbitrary free-text kinds/targets. ITEM_INSTANCE target existence remains
 * authoritative in the Phase-10 item_instances table.
 */
class OwnershipReferenceRegistry(private val db: SQLiteDatabase, private val campaignId: String) {
    init {
        require(campaignId.isNotBlank()) { "campaignId must not be blank" }
        MigrationManager().ensureV12(db, campaignId)
    }

    fun registerOwnerKind(ownerKindUid: String, provenance: String) {
        require(ownerKindUid.isNotBlank()) { "ownerKindUid must not be blank" }
        require(provenance.isNotBlank()) { "owner kind provenance must not be blank" }
        db.execSQL(
            "INSERT OR IGNORE INTO ownership_owner_kinds(owner_kind_uid,kind_status,provenance) VALUES(?,'ACTIVE',?)",
            arrayOf(ownerKindUid, provenance)
        )
        require(kindIsActive("ownership_owner_kinds", "owner_kind_uid", ownerKindUid)) { "owner namespace is retired" }
    }

    fun registerAssetKind(assetKindUid: String, provenance: String) {
        require(assetKindUid.isNotBlank()) { "assetKindUid must not be blank" }
        require(assetKindUid != OWNERSHIP_ASSET_KIND_ITEM_INSTANCE) { "ITEM_INSTANCE namespace is built in" }
        require(provenance.isNotBlank()) { "asset kind provenance must not be blank" }
        db.execSQL(
            "INSERT OR IGNORE INTO ownership_asset_kinds(asset_kind_uid,kind_status,provenance) VALUES(?,'ACTIVE',?)",
            arrayOf(assetKindUid, provenance)
        )
        require(kindIsActive("ownership_asset_kinds", "asset_kind_uid", assetKindUid)) { "asset namespace is retired" }
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

    private fun kindIsActive(table: String, column: String, uid: String): Boolean =
        db.rawQuery("SELECT kind_status FROM $table WHERE $column=?", arrayOf(uid)).use { c ->
            c.moveToFirst() && c.getString(0) == "ACTIVE"
        }
}
