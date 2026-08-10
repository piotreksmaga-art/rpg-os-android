package com.rpgos.app

import java.math.BigInteger

const val OWNERSHIP_SHARE_SCALE: Long = 3_600_000_000L
const val OWNERSHIP_ASSET_KIND_ITEM_INSTANCE = "RPGOS-ASSET-KIND:ITEM_INSTANCE"

data class OwnershipOwnerRef(
    val ownerKindUid: String,
    val ownerUid: String
)

data class OwnedAssetRef(
    val assetKindUid: String,
    val assetUid: String
)

/**
 * Exact fixed-scale ownership share. No floating-point constructor exists.
 * The canonical scale is deliberately divisible by common 2/3/4/5/6/8/9/10 fractions.
 */
data class OwnershipShare private constructor(val units: Long) {
    init { require(units in 1..OWNERSHIP_SHARE_SCALE) { "ownership share must be in (0, 100%]" } }

    val numerator: Long
        get() {
            val g = gcd(units, OWNERSHIP_SHARE_SCALE)
            return units / g
        }
    val denominator: Long
        get() {
            val g = gcd(units, OWNERSHIP_SHARE_SCALE)
            return OWNERSHIP_SHARE_SCALE / g
        }

    fun subtract(other: OwnershipShare): OwnershipShare? {
        require(other.units <= units) { "transferred share exceeds source share" }
        val remaining = Math.subtractExact(units, other.units)
        return if (remaining == 0L) null else ofUnits(remaining)
    }

    fun add(other: OwnershipShare): OwnershipShare = ofUnits(Math.addExact(units, other.units))

    companion object {
        fun full(): OwnershipShare = OwnershipShare(OWNERSHIP_SHARE_SCALE)

        fun ofUnits(units: Long): OwnershipShare = OwnershipShare(units)

        fun ofFraction(numerator: Long, denominator: Long): OwnershipShare {
            require(numerator > 0L) { "ownership share numerator must be positive" }
            require(denominator > 0L) { "ownership share denominator must be positive" }
            require(numerator <= denominator) { "ownership share cannot exceed 100%" }
            val scaled = BigInteger.valueOf(numerator).multiply(BigInteger.valueOf(OWNERSHIP_SHARE_SCALE))
            val parts = scaled.divideAndRemainder(BigInteger.valueOf(denominator))
            require(parts[1] == BigInteger.ZERO) { "ownership share precision is not exactly representable" }
            val value = parts[0].longValueExact()
            return OwnershipShare(value)
        }

        private fun gcd(a: Long, b: Long): Long {
            var x = a
            var y = b
            while (y != 0L) {
                val t = x % y
                x = y
                y = t
            }
            return x
        }
    }
}

enum class OwnershipRecordStatus { ACTIVE, CLOSED }

data class OwnershipRecord(
    val campaignId: String,
    val ownershipRecordUid: String,
    val owner: OwnershipOwnerRef,
    val asset: OwnedAssetRef,
    val ownershipTypeUid: String,
    val share: OwnershipShare,
    val validFrom: Long,
    val validUntil: Long? = null,
    val sourceEventUid: String? = null,
    val supersedesRecordUid: String? = null,
    val closedByEventUid: String? = null,
    val recordVersion: Long = 1L,
    val status: OwnershipRecordStatus = if (validUntil == null) OwnershipRecordStatus.ACTIVE else OwnershipRecordStatus.CLOSED,
    val provenance: String,
    val closureProvenance: String? = null,
    val metadataJson: String? = null
)

data class OwnershipTransferResult(
    val operationUid: String,
    val closedSource: OwnershipRecord,
    val sourceSuccessor: OwnershipRecord?,
    val destinationSuccessor: OwnershipRecord
)

object OwnershipPolicy {
    fun validateOwner(owner: OwnershipOwnerRef) {
        require(owner.ownerKindUid.isNotBlank()) { "ownerKindUid must not be blank" }
        require(owner.ownerUid.isNotBlank()) { "ownerUid must not be blank" }
    }

    fun validateAsset(asset: OwnedAssetRef) {
        require(asset.assetKindUid.isNotBlank()) { "assetKindUid must not be blank" }
        require(asset.assetUid.isNotBlank()) { "assetUid must not be blank" }
    }

    fun validateRecord(record: OwnershipRecord) {
        require(record.campaignId.isNotBlank()) { "campaignId must not be blank" }
        require(record.ownershipRecordUid.isNotBlank()) { "ownershipRecordUid must not be blank" }
        validateOwner(record.owner)
        validateAsset(record.asset)
        require(record.ownershipTypeUid.isNotBlank()) { "ownershipTypeUid must not be blank" }
        require(record.recordVersion >= 1L) { "recordVersion must be at least 1" }
        require(record.provenance.isNotBlank()) { "ownership provenance must not be blank" }
        require(record.sourceEventUid == null || record.sourceEventUid.isNotBlank()) { "sourceEventUid must be null or nonblank" }
        require(record.supersedesRecordUid == null || record.supersedesRecordUid.isNotBlank()) { "supersedesRecordUid must be null or nonblank" }
        require(record.closedByEventUid == null || record.closedByEventUid.isNotBlank()) { "closedByEventUid must be null or nonblank" }
        require(record.validUntil == null || record.validUntil > record.validFrom) { "ownership interval must be [validFrom, validUntil)" }
        if (record.validUntil == null) {
            require(record.status == OwnershipRecordStatus.ACTIVE) { "open ownership record must be ACTIVE" }
            require(record.closedByEventUid == null && record.closureProvenance == null) { "open ownership record cannot have closure provenance" }
        } else {
            require(record.status == OwnershipRecordStatus.CLOSED) { "closed ownership record must be CLOSED" }
            require(record.closureProvenance != null && record.closureProvenance.isNotBlank()) { "closed ownership record requires closure provenance" }
        }
    }
}
