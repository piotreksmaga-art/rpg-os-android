package com.rpgos.app

enum class SkillDefinitionStatus { ACTIVE, DEPRECATED }

data class SkillDefinition(
    val skillUid: String,
    val worldPackUid: String,
    val key: String,
    val displayName: String,
    val category: String,
    val progressionDomainUids: List<String> = emptyList(),
    val minMastery: Double? = 0.0,
    val maxMastery: Double? = null,
    val status: SkillDefinitionStatus = SkillDefinitionStatus.ACTIVE,
    val definitionVersion: Long = 1L,
    val provenance: String
) {
    init { SkillPolicy.validateDefinition(this) }
}

data class PlayerSkill(
    val campaignId: String,
    val characterUid: String,
    val skillUid: String,
    val baseMastery: Double,
    val progressValue: Double? = null,
    val progressSemanticsUid: String? = null,
    val entryVersion: Long = 1L,
    val provenance: String,
    val learnedChapter: Long? = null
) {
    init { SkillPolicy.validatePlayerSkill(this) }
}

data class LegacySkillRecord(
    val campaignId: String,
    val characterUid: String,
    val legacySkillUid: String,
    val masteryRaw: String,
    val xpRaw: String?,
    val updatedChapterRaw: String?,
    val displayName: String?,
    val category: String?
)

data class LegacySkillMapping(
    val campaignId: String,
    val characterUid: String,
    val legacySkillUid: String,
    val canonicalSkillUid: String,
    val worldPackUid: String,
    val mappingVersion: Long,
    val provenance: String,
    val supersededByTyped: Boolean = false
) {
    init {
        require(campaignId.isNotBlank()) { "campaignId must not be blank" }
        require(characterUid.isNotBlank()) { "characterUid must not be blank" }
        require(legacySkillUid.isNotBlank()) { "legacySkillUid must not be blank" }
        require(canonicalSkillUid.isNotBlank()) { "canonicalSkillUid must not be blank" }
        require(worldPackUid.isNotBlank()) { "worldPackUid must not be blank" }
        require(mappingVersion >= 1L) { "mappingVersion must be at least 1" }
        require(provenance.isNotBlank()) { "mapping provenance must not be blank" }
    }
}

enum class SkillAuthoritySource { TYPED, LEGACY_MAPPED }

data class ReconciledSkill(
    val playerSkill: PlayerSkill,
    val authoritySource: SkillAuthoritySource,
    val legacyXpRaw: String? = null,
    val legacyUpdatedChapterRaw: String? = null
)

data class SkillReadResult(
    val skills: List<ReconciledSkill>,
    val unresolvedLegacy: List<LegacySkillRecord>
)

data class ResolvedSkill(
    val skillUid: String,
    val baseMastery: Double,
    val preBoundValue: Double,
    val effectiveMastery: Double,
    val contributions: List<ModifierContribution>,
    val diagnostics: List<DerivedDiagnostic>
)

object SkillPolicy {
    fun validateDefinition(definition: SkillDefinition) {
        require(definition.skillUid.isNotBlank()) { "skillUid must not be blank" }
        require(definition.worldPackUid.isNotBlank()) { "worldPackUid must not be blank" }
        require(definition.key.isNotBlank()) { "skill key must not be blank" }
        require(definition.displayName.isNotBlank()) { "displayName must not be blank" }
        require(definition.category.isNotBlank()) { "category must not be blank" }
        require(definition.definitionVersion >= 1L) { "definitionVersion must be at least 1" }
        require(definition.provenance.isNotBlank()) { "definition provenance must not be blank" }
        require(definition.progressionDomainUids.none { it.isBlank() }) { "progression domain UID must not be blank" }
        require(definition.progressionDomainUids.size == definition.progressionDomainUids.distinct().size) { "duplicate progression domain UID" }
        definition.minMastery?.let { require(it.isFinite()) { "minMastery must be finite" }; require(it >= 0.0) { "minMastery must be non-negative" } }
        definition.maxMastery?.let { require(it.isFinite()) { "maxMastery must be finite" }; require(it >= 0.0) { "maxMastery must be non-negative" } }
        if (definition.minMastery != null && definition.maxMastery != null) require(definition.minMastery <= definition.maxMastery) { "minMastery must not exceed maxMastery" }
    }

    fun validatePlayerSkill(skill: PlayerSkill) {
        require(skill.campaignId.isNotBlank()) { "campaignId must not be blank" }
        require(skill.characterUid.isNotBlank()) { "characterUid must not be blank" }
        require(skill.skillUid.isNotBlank()) { "skillUid must not be blank" }
        require(skill.baseMastery.isFinite()) { "baseMastery must be finite" }
        require(skill.baseMastery >= 0.0) { "baseMastery must be non-negative" }
        skill.progressValue?.let {
            require(it.isFinite()) { "progressValue must be finite" }
            require(it >= 0.0) { "progressValue must be non-negative" }
            require(!skill.progressSemanticsUid.isNullOrBlank()) { "progressValue requires explicit progressSemanticsUid" }
        }
        if (skill.progressValue == null) require(skill.progressSemanticsUid.isNullOrBlank()) { "progressSemanticsUid without progressValue is invalid" }
        require(skill.entryVersion >= 1L) { "entryVersion must be at least 1" }
        require(skill.provenance.isNotBlank()) { "skill provenance must not be blank" }
        skill.learnedChapter?.let { require(it >= 0L) { "learnedChapter must be non-negative" } }
    }
}
