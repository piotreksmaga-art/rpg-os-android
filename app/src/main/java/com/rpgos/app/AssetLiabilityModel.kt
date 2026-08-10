package com.rpgos.app

import java.math.BigInteger

const val ASSET_KIND_PROPERTY = "RPGOS-ASSET-KIND:PROPERTY"
const val ASSET_KIND_LAND = "RPGOS-ASSET-KIND:LAND"
const val ASSET_KIND_BUSINESS = "RPGOS-ASSET-KIND:BUSINESS"
const val ASSET_KIND_COMPANY = "RPGOS-ASSET-KIND:COMPANY"
const val ASSET_KIND_SHARES = "RPGOS-ASSET-KIND:SHARES"
const val ASSET_KIND_STAKE = "RPGOS-ASSET-KIND:STAKE"
const val ASSET_KIND_VEHICLE = "RPGOS-ASSET-KIND:VEHICLE"
const val ASSET_KIND_RARE_ASSET = "RPGOS-ASSET-KIND:RARE_ASSET"
const val OWNERSHIP_TYPE_ECONOMIC = "RPGOS-OWNERSHIP-TYPE:ECONOMIC"

enum class AssetClass { ASSET, SECURITY, PROPERTY, BUSINESS, OTHER }
enum class AssetLifecycle { ACTIVE, RETIRED, DESTROYED, LIQUIDATED }
enum class ValuationType { MARKET, BOOK, APPRAISAL, FACE, CUSTOM }
enum class ObligationClass { DEBT, SERVICE, PAYMENT, DELIVERY, OTHER }
enum class ObligationStatus { ACTIVE, SETTLED, DEFAULTED, CANCELLED, EXPIRED }
enum class SettlementKind { PAYMENT, FORGIVENESS, WRITE_OFF, OTHER }

data class AssetKindDefinition(
    val assetKindUid: String,
    val assetClass: AssetClass,
    val displayName: String,
    val worldPackUid: String? = null,
    val status: String = "ACTIVE",
    val version: Long = 1,
    val provenance: String
)

data class AssetRecord(
    val campaignId: String,
    val assetUid: String,
    val assetKindUid: String,
    val createdOrder: Long,
    val provenance: String,
    val lifecycle: AssetLifecycle = AssetLifecycle.ACTIVE,
    val retiredOrder: Long? = null,
    val sourceEventUid: String? = null,
    val version: Long = 1,
    val metadataJson: String? = null
) { val ref: OwnedAssetRef get() = OwnedAssetRef(assetKindUid, assetUid) }

data class AssetValuation(
    val campaignId: String,
    val valuationUid: String,
    val asset: OwnedAssetRef,
    val currencyUid: String,
    val amountMinor: Long,
    val valuationType: ValuationType,
    val effectiveOrder: Long,
    val provenance: String,
    val validUntilOrder: Long? = null,
    val sourceEventUid: String? = null,
    val confidencePpm: Long? = null,
    val version: Long = 1
)

data class ObligationRecord(
    val campaignId: String,
    val obligationUid: String,
    val obligationTypeUid: String,
    val obligationClass: ObligationClass,
    val obligor: OwnershipOwnerRef,
    val beneficiary: OwnershipOwnerRef,
    val createdOrder: Long,
    val provenance: String,
    val currencyUid: String? = null,
    val principalMinor: Long? = null,
    val asset: OwnedAssetRef? = null,
    val dueOrder: Long? = null,
    val validUntilOrder: Long? = null,
    val sourceEventUid: String? = null,
    val sourceContractUid: String? = null,
    val version: Long = 1,
    val metadataJson: String? = null
)

data class ObligationSettlement(
    val campaignId: String,
    val settlementUid: String,
    val obligationUid: String,
    val kind: SettlementKind,
    val effectiveOrder: Long,
    val provenance: String,
    val amountMinor: Long? = null,
    val financialTransactionUid: String? = null,
    val ownershipOperationUid: String? = null,
    val sourceEventUid: String? = null
)

data class NetWorthProjection(
    val campaignId: String,
    val party: OwnershipOwnerRef,
    val currencyUid: String,
    val asOfOrder: Long,
    val assetsMinor: Long,
    val cashMinor: Long,
    val receivablesMinor: Long,
    val liabilitiesMinor: Long,
    val netWorthMinor: Long
)

object AssetLiabilityPolicy {
    fun validateAssetKind(d: AssetKindDefinition) {
        require(d.assetKindUid.isNotBlank() && d.assetKindUid != OWNERSHIP_ASSET_KIND_ITEM_INSTANCE)
        require(d.displayName.isNotBlank() && d.provenance.isNotBlank())
        require(d.worldPackUid == null || d.worldPackUid.isNotBlank())
        require(d.status in setOf("ACTIVE", "DEPRECATED"))
        require(d.version >= 1)
    }
    fun validateAsset(a: AssetRecord) {
        require(a.campaignId.isNotBlank() && a.assetUid.isNotBlank() && a.assetKindUid.isNotBlank())
        require(a.assetKindUid != OWNERSHIP_ASSET_KIND_ITEM_INSTANCE) { "ItemInstance identity belongs to Inventory authority" }
        require(a.provenance.isNotBlank() && a.version >= 1)
        require(a.sourceEventUid == null || a.sourceEventUid.isNotBlank())
        require(a.retiredOrder == null || a.retiredOrder > a.createdOrder)
        require((a.lifecycle == AssetLifecycle.ACTIVE) == (a.retiredOrder == null))
    }
    fun validateValuation(v: AssetValuation) {
        require(v.campaignId.isNotBlank() && v.valuationUid.isNotBlank())
        OwnershipPolicy.validateAsset(v.asset)
        require(v.currencyUid.isNotBlank() && v.amountMinor >= 0L)
        require(v.provenance.isNotBlank() && v.version >= 1)
        require(v.validUntilOrder == null || v.validUntilOrder > v.effectiveOrder)
        require(v.confidencePpm == null || v.confidencePpm in 0..1_000_000)
    }
    fun validateObligation(o: ObligationRecord) {
        require(o.campaignId.isNotBlank() && o.obligationUid.isNotBlank() && o.obligationTypeUid.isNotBlank())
        OwnershipPolicy.validateOwner(o.obligor); OwnershipPolicy.validateOwner(o.beneficiary)
        require(o.obligor != o.beneficiary) { "obligor and beneficiary must differ" }
        require(o.provenance.isNotBlank() && o.version >= 1)
        require((o.currencyUid == null) == (o.principalMinor == null)) { "currency and principal must be both present or both absent" }
        require(o.principalMinor == null || o.principalMinor > 0L)
        require(o.asset == null || o.asset.assetKindUid != OWNERSHIP_ASSET_KIND_ITEM_INSTANCE)
        require(o.dueOrder == null || o.dueOrder >= o.createdOrder)
        require(o.validUntilOrder == null || o.validUntilOrder > o.createdOrder)
    }
    fun exactShareValue(amountMinor: Long, shareUnits: Long): Long {
        require(amountMinor >= 0L && shareUnits in 1..OWNERSHIP_SHARE_SCALE)
        val n = BigInteger.valueOf(amountMinor).multiply(BigInteger.valueOf(shareUnits))
        val parts = n.divideAndRemainder(BigInteger.valueOf(OWNERSHIP_SHARE_SCALE))
        require(parts[1] == BigInteger.ZERO) { "fractional ownership value is not exactly representable in currency minor units" }
        return parts[0].longValueExact()
    }
}
