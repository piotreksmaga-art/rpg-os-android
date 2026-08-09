package com.rpgos.app

enum class ProgressionProfileAxis { TALENT, POTENTIAL }

data class ProgressionDomainDefinition(
    val domainUid: String,
    val worldPackUid: String,
    val key: String,
    val displayName: String,
    val category: String,
    val parentDomainUid: String? = null,
    val appliesToTalent: Boolean = true,
    val appliesToPotential: Boolean = true,
    val definitionVersion: Long = 1,
    val provenance: String
)

data class TalentEntry(
    val campaignId: String,
    val characterUid: String,
    val domainUid: String,
    val baseValue: Double,
    val entryVersion: Long = 1,
    val provenance: String
)

data class PotentialEntry(
    val campaignId: String,
    val characterUid: String,
    val domainUid: String,
    val dimensionUid: String,
    val baseValue: Double,
    val entryVersion: Long = 1,
    val provenance: String
)

data class TalentProfile(val campaignId: String, val characterUid: String, val entries: List<TalentEntry>)
data class PotentialProfile(val campaignId: String, val characterUid: String, val entries: List<PotentialEntry>)

data class LegacyProgressionEvidence(
    val evidenceUid: String,
    val campaignId: String,
    val characterUid: String,
    val legacyKey: String,
    val rawValue: String,
    val sourceType: String,
    val sourceUid: String,
    val sourceVersion: Long,
    val provenance: String
)

data class LegacyProgressionMapping(
    val campaignId: String,
    val evidenceUid: String,
    val axis: ProgressionProfileAxis,
    val domainUid: String,
    val dimensionUid: String? = null,
    val worldPackUid: String,
    val mappingVersion: Long,
    val provenance: String
)

object ProgressionProfilePolicy {
    fun validate(definition: ProgressionDomainDefinition) {
        require(definition.domainUid.isNotBlank()) { "domainUid must not be blank" }
        require(definition.worldPackUid.isNotBlank()) { "worldPackUid must not be blank" }
        require(definition.key.isNotBlank()) { "key must not be blank" }
        require(definition.displayName.isNotBlank()) { "displayName must not be blank" }
        require(definition.category.isNotBlank()) { "category must not be blank" }
        require(definition.definitionVersion >= 1) { "definitionVersion must be >= 1" }
        require(definition.provenance.isNotBlank()) { "definition provenance must not be blank" }
        require(definition.appliesToTalent || definition.appliesToPotential) { "domain must apply to Talent and/or Potential" }
        require(definition.parentDomainUid != definition.domainUid) { "domain cannot parent itself" }
    }

    fun validate(entry: TalentEntry) {
        common(entry.campaignId, entry.characterUid, entry.domainUid, entry.baseValue, entry.entryVersion, entry.provenance)
    }

    fun validate(entry: PotentialEntry) {
        common(entry.campaignId, entry.characterUid, entry.domainUid, entry.baseValue, entry.entryVersion, entry.provenance)
        require(entry.dimensionUid.isNotBlank()) { "dimensionUid must not be blank" }
    }

    fun validate(evidence: LegacyProgressionEvidence) {
        require(evidence.evidenceUid.isNotBlank())
        require(evidence.campaignId.isNotBlank())
        require(evidence.characterUid.isNotBlank())
        require(evidence.legacyKey.isNotBlank())
        require(evidence.sourceType.isNotBlank())
        require(evidence.sourceUid.isNotBlank())
        require(evidence.sourceVersion >= 1)
        require(evidence.provenance.isNotBlank())
    }

    fun validate(mapping: LegacyProgressionMapping) {
        require(mapping.campaignId.isNotBlank())
        require(mapping.evidenceUid.isNotBlank())
        require(mapping.domainUid.isNotBlank())
        require(mapping.worldPackUid.isNotBlank())
        require(mapping.mappingVersion >= 1)
        require(mapping.provenance.isNotBlank())
        if (mapping.axis == ProgressionProfileAxis.POTENTIAL) require(!mapping.dimensionUid.isNullOrBlank()) { "Potential mapping requires dimensionUid" }
        if (mapping.axis == ProgressionProfileAxis.TALENT) require(mapping.dimensionUid == null) { "Talent mapping must not carry dimensionUid" }
    }

    private fun common(campaignId: String, characterUid: String, domainUid: String, value: Double, version: Long, provenance: String) {
        require(campaignId.isNotBlank())
        require(characterUid.isNotBlank())
        require(domainUid.isNotBlank())
        require(value.isFinite()) { "profile value must be finite" }
        require(value >= 0.0) { "profile value must be >= 0" }
        require(version >= 1) { "entryVersion must be >= 1" }
        require(provenance.isNotBlank()) { "profile provenance must not be blank" }
    }
}
