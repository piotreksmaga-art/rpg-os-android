package com.rpgos.app

/** World-Pack supplied definition of a persistent base statistic. */
data class StatDefinition(
    val statUid: String,
    val key: String,
    val category: String,
    val unit: String? = null,
    val minValue: Double? = null,
    val maxValue: Double? = null,
    val growthRuleUid: String? = null,
    val derivationRuleUid: String? = null,
    val worldPackUid: String
) {
    init { StatResourcePolicy.validate(this) }
}

/** Authoritative persistent base value for one character statistic. */
data class PlayerStat(
    val campaignId: String,
    val characterUid: String,
    val statUid: String,
    val baseValue: Double,
    val version: Long = 1L
) {
    init { StatResourcePolicy.validate(this) }
}

/**
 * World-Pack supplied resource definition.
 * min/max are definition bounds only. Character-specific effective maxima and
 * regeneration belong to the later derived/rule layer and are not resolved here.
 */
data class ResourceDefinition(
    val resourceUid: String,
    val key: String,
    val category: String,
    val unit: String? = null,
    val minValue: Double? = null,
    val maxValue: Double? = null,
    val maxRuleUid: String? = null,
    val regenerationRuleUid: String? = null,
    val worldPackUid: String
) {
    init { StatResourcePolicy.validate(this) }
}

/** Current persisted amount of a dynamic resource for one character. */
data class PlayerResource(
    val campaignId: String,
    val characterUid: String,
    val resourceUid: String,
    val currentValue: Double,
    val version: Long = 1L
) {
    init { StatResourcePolicy.validate(this) }
}

/**
 * Explicit Phase-4 reconciliation record. It never infers equivalence from text keys:
 * it declares that one deterministic legacy compatibility UID is superseded by one
 * canonical typed definition owned by a specific World Pack in this campaign.
 */
data class LegacyStatAlias(
    val campaignId: String,
    val legacyStatUid: String,
    val canonicalStatUid: String,
    val worldPackUid: String,
    val mappingVersion: Long = 1L,
    val provenance: String
) {
    init { StatResourcePolicy.validate(this) }
}

data class LegacyResourceAlias(
    val campaignId: String,
    val legacyResourceUid: String,
    val canonicalResourceUid: String,
    val worldPackUid: String,
    val mappingVersion: Long = 1L,
    val provenance: String
) {
    init { StatResourcePolicy.validate(this) }
}

/** Public deterministic identity helper for explicit World-Pack reconciliation declarations. */
object LegacyCompatibilityIdentity {
    const val WORLD_PACK_UID = "RPGOS-LEGACY-COMPAT"
    const val STAT_UID_PREFIX = "RPGOS-LEGACY-STAT-"
    const val RESOURCE_UID_PREFIX = "RPGOS-LEGACY-RESOURCE-"

    fun statUidForKey(key: String): String {
        require(key.isNotBlank()) { "legacy stat key must not be blank" }
        return stableUid(STAT_UID_PREFIX, key)
    }

    fun resourceUidForKey(key: String): String {
        require(key.isNotBlank()) { "legacy resource key must not be blank" }
        return stableUid(RESOURCE_UID_PREFIX, key)
    }

    fun isReservedWorldPack(uid: String): Boolean = uid == WORLD_PACK_UID
    fun isLegacyStatUid(uid: String): Boolean = uid.startsWith(STAT_UID_PREFIX)
    fun isLegacyResourceUid(uid: String): Boolean = uid.startsWith(RESOURCE_UID_PREFIX)
    fun isReservedDefinitionUid(uid: String): Boolean = isLegacyStatUid(uid) || isLegacyResourceUid(uid)

    private fun stableUid(prefix: String, identity: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        return prefix + hex
    }
}

object StatResourcePolicy {
    fun validate(definition: StatDefinition) {
        require(definition.statUid.isNotBlank()) { "statUid must not be blank" }
        validateDefinitionFields(
            key = definition.key,
            category = definition.category,
            worldPackUid = definition.worldPackUid,
            minValue = definition.minValue,
            maxValue = definition.maxValue
        )
    }

    fun validate(definition: ResourceDefinition) {
        require(definition.resourceUid.isNotBlank()) { "resourceUid must not be blank" }
        validateDefinitionFields(
            key = definition.key,
            category = definition.category,
            worldPackUid = definition.worldPackUid,
            minValue = definition.minValue,
            maxValue = definition.maxValue
        )
    }

    fun validate(stat: PlayerStat) {
        validatePlayerValue(stat.campaignId, stat.characterUid, stat.statUid, stat.baseValue, stat.version)
    }

    fun validate(resource: PlayerResource) {
        validatePlayerValue(
            resource.campaignId,
            resource.characterUid,
            resource.resourceUid,
            resource.currentValue,
            resource.version
        )
    }

    fun validate(alias: LegacyStatAlias) {
        validateAlias(
            campaignId = alias.campaignId,
            legacyUid = alias.legacyStatUid,
            canonicalUid = alias.canonicalStatUid,
            worldPackUid = alias.worldPackUid,
            mappingVersion = alias.mappingVersion,
            provenance = alias.provenance
        )
        require(LegacyCompatibilityIdentity.isLegacyStatUid(alias.legacyStatUid)) {
            "legacyStatUid must be a deterministic legacy stat UID"
        }
        require(!LegacyCompatibilityIdentity.isReservedDefinitionUid(alias.canonicalStatUid)) {
            "canonicalStatUid must be a normal typed definition UID"
        }
    }

    fun validate(alias: LegacyResourceAlias) {
        validateAlias(
            campaignId = alias.campaignId,
            legacyUid = alias.legacyResourceUid,
            canonicalUid = alias.canonicalResourceUid,
            worldPackUid = alias.worldPackUid,
            mappingVersion = alias.mappingVersion,
            provenance = alias.provenance
        )
        require(LegacyCompatibilityIdentity.isLegacyResourceUid(alias.legacyResourceUid)) {
            "legacyResourceUid must be a deterministic legacy resource UID"
        }
        require(!LegacyCompatibilityIdentity.isReservedDefinitionUid(alias.canonicalResourceUid)) {
            "canonicalResourceUid must be a normal typed definition UID"
        }
    }

    private fun validateDefinitionFields(
        key: String,
        category: String,
        worldPackUid: String,
        minValue: Double?,
        maxValue: Double?
    ) {
        require(key.isNotBlank()) { "definition key must not be blank" }
        require(category.isNotBlank()) { "definition category must not be blank" }
        require(worldPackUid.isNotBlank()) { "worldPackUid must not be blank" }
        minValue?.let { require(it.isFinite()) { "minValue must be finite" } }
        maxValue?.let { require(it.isFinite()) { "maxValue must be finite" } }
        if (minValue != null && maxValue != null) {
            require(minValue <= maxValue) { "minValue must not exceed maxValue" }
        }
    }

    private fun validatePlayerValue(
        campaignId: String,
        characterUid: String,
        definitionUid: String,
        value: Double,
        version: Long
    ) {
        require(campaignId.isNotBlank()) { "campaignId must not be blank" }
        require(characterUid.isNotBlank()) { "characterUid must not be blank" }
        require(definitionUid.isNotBlank()) { "definition UID must not be blank" }
        require(value.isFinite()) { "player value must be finite" }
        require(version >= 1L) { "version must be at least 1" }
    }

    private fun validateAlias(
        campaignId: String,
        legacyUid: String,
        canonicalUid: String,
        worldPackUid: String,
        mappingVersion: Long,
        provenance: String
    ) {
        require(campaignId.isNotBlank()) { "campaignId must not be blank" }
        require(legacyUid.isNotBlank()) { "legacy definition UID must not be blank" }
        require(canonicalUid.isNotBlank()) { "canonical definition UID must not be blank" }
        require(worldPackUid.isNotBlank()) { "worldPackUid must not be blank" }
        require(!LegacyCompatibilityIdentity.isReservedWorldPack(worldPackUid)) {
            "legacy compatibility namespace cannot own canonical aliases"
        }
        require(mappingVersion >= 1L) { "mappingVersion must be at least 1" }
        require(provenance.isNotBlank()) { "alias provenance must not be blank" }
    }
}
