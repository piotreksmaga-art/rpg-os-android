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
}
