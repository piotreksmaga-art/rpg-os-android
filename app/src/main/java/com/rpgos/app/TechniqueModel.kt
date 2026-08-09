package com.rpgos.app

enum class TechniqueDefinitionStatus { ACTIVE, DEPRECATED }
enum class TechniqueSkillMasteryBasis { BASE, EFFECTIVE }
enum class TechniqueRequirementPhase { ACQUISITION, EXECUTION, BOTH }

data class TechniqueSkillRequirement(
    val skillUid: String,
    val requirementPhase: TechniqueRequirementPhase,
    val masteryBasis: TechniqueSkillMasteryBasis,
    val minimumMastery: Double,
    val requirementVersion: Long = 1L,
    val provenance: String
) {
    init {
        require(skillUid.isNotBlank()) { "required skillUid must not be blank" }
        require(minimumMastery.isFinite() && minimumMastery >= 0.0) { "minimum skill mastery must be finite and non-negative" }
        require(requirementVersion >= 1L) { "requirementVersion must be at least 1" }
        require(provenance.isNotBlank()) { "requirement provenance must not be blank" }
    }
}

data class TechniqueResourceCost(
    val resourceUid: String,
    val amount: Double,
    val costVersion: Long = 1L,
    val provenance: String
) {
    init {
        require(resourceUid.isNotBlank()) { "resourceUid must not be blank" }
        require(amount.isFinite() && amount >= 0.0) { "resource cost must be finite and non-negative" }
        require(costVersion >= 1L) { "costVersion must be at least 1" }
        require(provenance.isNotBlank()) { "cost provenance must not be blank" }
    }
}

data class TechniqueDefinition(
    val techniqueUid: String,
    val worldPackUid: String,
    val key: String,
    val displayName: String,
    val category: String,
    val skillRequirements: List<TechniqueSkillRequirement> = emptyList(),
    val resourceCosts: List<TechniqueResourceCost> = emptyList(),
    val minMastery: Double? = 0.0,
    val maxMastery: Double? = null,
    val status: TechniqueDefinitionStatus = TechniqueDefinitionStatus.ACTIVE,
    val definitionVersion: Long = 1L,
    val provenance: String
) { init { TechniquePolicy.validateDefinition(this) } }

data class PlayerTechnique(
    val campaignId: String,
    val characterUid: String,
    val techniqueUid: String,
    val baseMastery: Double,
    val progressValue: Double? = null,
    val progressSemanticsUid: String? = null,
    val learnedChapter: Long? = null,
    val lastUsedChapter: Long? = null,
    val usageCount: Long = 0L,
    val successCount: Long = 0L,
    val failureCount: Long = 0L,
    val isEquipped: Boolean = false,
    val notes: String? = null,
    val entryVersion: Long = 1L,
    val provenance: String
) { init { TechniquePolicy.validatePlayerTechnique(this) } }

data class LegacyTechniqueRecord(
    val campaignId: String,
    val characterUid: String,
    val legacyTechniqueUid: String,
    val masteryRaw: String,
    val xpRaw: String?,
    val learnedChapterRaw: String?,
    val lastUsedChapterRaw: String?,
    val usageCountRaw: String?,
    val successCountRaw: String?,
    val failureCountRaw: String?,
    val isEquippedRaw: String?,
    val notesRaw: String?,
    val chakraCostOverrideRaw: String?,
    val displayName: String?,
    val category: String?,
    val baseChakraCostRaw: String?
)

data class LegacyTechniqueMapping(
    val campaignId: String,
    val characterUid: String,
    val legacyTechniqueUid: String,
    val canonicalTechniqueUid: String,
    val worldPackUid: String,
    val mappingVersion: Long,
    val provenance: String,
    val supersededByTyped: Boolean = false
) {
    init {
        require(campaignId.isNotBlank() && characterUid.isNotBlank())
        require(legacyTechniqueUid.isNotBlank() && canonicalTechniqueUid.isNotBlank())
        require(worldPackUid.isNotBlank() && provenance.isNotBlank())
        require(mappingVersion >= 1L)
    }
}

data class LegacyTechniqueResourceCostMapping(
    val campaignId: String,
    val characterUid: String,
    val legacyTechniqueUid: String,
    val resourceUid: String,
    val worldPackUid: String,
    val mappingVersion: Long,
    val provenance: String
) {
    init {
        require(campaignId.isNotBlank() && characterUid.isNotBlank() && legacyTechniqueUid.isNotBlank())
        require(resourceUid.isNotBlank() && worldPackUid.isNotBlank() && provenance.isNotBlank())
        require(mappingVersion >= 1L)
    }
}

enum class TechniqueAuthoritySource { TYPED, LEGACY_MAPPED }
data class ReconciledTechnique(
    val playerTechnique: PlayerTechnique,
    val authoritySource: TechniqueAuthoritySource,
    val legacyXpRaw: String? = null,
    val legacyChakraCostOverrideRaw: String? = null,
    val legacyBaseChakraCostRaw: String? = null,
    val mappedLegacyCostResourceUid: String? = null
)
data class TechniqueReadResult(val techniques: List<ReconciledTechnique>, val unresolvedLegacy: List<LegacyTechniqueRecord>)

data class ResolvedTechnique(
    val techniqueUid: String,
    val baseMastery: Double,
    val preBoundValue: Double,
    val effectiveMastery: Double,
    val contributions: List<ModifierContribution>,
    val diagnostics: List<DerivedDiagnostic>
)

object TechniquePolicy {
    fun validateDefinition(d: TechniqueDefinition) {
        require(d.techniqueUid.isNotBlank() && d.worldPackUid.isNotBlank() && d.key.isNotBlank())
        require(d.displayName.isNotBlank() && d.category.isNotBlank() && d.provenance.isNotBlank())
        require(d.definitionVersion >= 1L)
        val requirementKeys=d.skillRequirements.map { it.skillUid to it.requirementPhase }
        require(requirementKeys.size == requirementKeys.distinct().size) { "duplicate Technique Skill requirement for same phase" }
        require(d.resourceCosts.map { it.resourceUid }.size == d.resourceCosts.map { it.resourceUid }.distinct().size) { "duplicate Technique resource cost" }
        d.minMastery?.let { require(it.isFinite() && it >= 0.0) }
        d.maxMastery?.let { require(it.isFinite() && it >= 0.0) }
        if (d.minMastery != null && d.maxMastery != null) require(d.minMastery <= d.maxMastery)
    }

    fun validatePlayerTechnique(t: PlayerTechnique) {
        require(t.campaignId.isNotBlank() && t.characterUid.isNotBlank() && t.techniqueUid.isNotBlank())
        require(t.baseMastery.isFinite() && t.baseMastery >= 0.0) { "baseTechniqueMastery must be finite and non-negative" }
        t.progressValue?.let { require(it.isFinite() && it >= 0.0); require(!t.progressSemanticsUid.isNullOrBlank()) { "progressValue requires explicit progressSemanticsUid" } }
        if (t.progressValue == null) require(t.progressSemanticsUid.isNullOrBlank())
        listOfNotNull(t.learnedChapter, t.lastUsedChapter).forEach { require(it >= 0L) }
        require(t.usageCount >= 0L && t.successCount >= 0L && t.failureCount >= 0L)
        require(t.entryVersion >= 1L && t.provenance.isNotBlank())
    }
}
