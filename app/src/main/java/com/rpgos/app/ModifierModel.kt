package com.rpgos.app

enum class ModifierTargetKind { STAT_EFFECTIVE, RESOURCE_MAXIMUM, RESOURCE_REGENERATION, SKILL_EFFECTIVE }
enum class ModifierLifecycle { PERMANENT, EQUIPMENT, INJURY, TEMPORARY }
enum class ModifierOperation { ADD_FLAT, ADD_PERCENT, MULTIPLY, OVERRIDE, MIN_FLOOR, MAX_CAP }

data class Modifier(
    val modifierUid: String,
    val campaignId: String,
    val characterUid: String,
    val targetDefinitionUid: String,
    val targetKind: ModifierTargetKind,
    val lifecycle: ModifierLifecycle,
    val operation: ModifierOperation,
    val value: Double,
    val priority: Int = 0,
    val sourceType: String,
    val sourceUid: String,
    val sourceActive: Boolean = true,
    val validFrom: Long? = null,
    val validUntil: Long? = null,
    val active: Boolean = true,
    val provenance: String,
    val version: Long = 1L
) {
    init { ModifierPolicy.validate(this) }
}

object ModifierPolicy {
    fun validate(modifier: Modifier) {
        require(modifier.modifierUid.isNotBlank()) { "modifierUid must not be blank" }
        require(modifier.campaignId.isNotBlank()) { "campaignId must not be blank" }
        require(modifier.characterUid.isNotBlank()) { "characterUid must not be blank" }
        require(modifier.targetDefinitionUid.isNotBlank()) { "targetDefinitionUid must not be blank" }
        require(modifier.value.isFinite()) { "modifier value must be finite" }
        require(modifier.sourceType.isNotBlank()) { "sourceType must not be blank" }
        require(modifier.sourceUid.isNotBlank()) { "sourceUid must not be blank" }
        require(modifier.provenance.isNotBlank()) { "modifier provenance must not be blank" }
        require(modifier.version >= 1L) { "modifier version must be at least 1" }
        if (modifier.validFrom != null && modifier.validUntil != null) require(modifier.validUntil >= modifier.validFrom) { "validUntil must not be before validFrom" }
    }

    fun isEffectiveAt(modifier: Modifier, epoch: Long): Boolean = modifier.active && modifier.sourceActive &&
        (modifier.validFrom == null || epoch >= modifier.validFrom) && (modifier.validUntil == null || epoch <= modifier.validUntil)
}

data class DerivedDependency(val targetKind: ModifierTargetKind, val targetDefinitionUid: String) {
    init { require(targetDefinitionUid.isNotBlank()) { "dependency targetDefinitionUid must not be blank" } }
}

data class DerivedRuleDescriptor(val ruleUid: String, val version: Long, val dependencies: List<DerivedDependency> = emptyList()) {
    init {
        require(ruleUid.isNotBlank()) { "ruleUid must not be blank" }
        require(version >= 1L) { "rule version must be at least 1" }
        require(dependencies.size == dependencies.distinct().size) { "duplicate rule dependency in $ruleUid" }
    }
}

data class DerivedRuleContext(
    val campaignId: String,
    val characterUid: String,
    val targetKind: ModifierTargetKind,
    val targetDefinitionUid: String,
    val resolutionEpoch: Long,
    val dependencyValues: Map<DerivedDependency, Double>
)

interface DerivedRuleProvider {
    val providerUid: String
    fun descriptor(ruleUid: String): DerivedRuleDescriptor?
    fun evaluate(descriptor: DerivedRuleDescriptor, context: DerivedRuleContext): Double
}

data class ModifierContribution(
    val sequenceIndex: Int,
    val modifierUid: String,
    val lifecycle: ModifierLifecycle,
    val operation: ModifierOperation,
    val priority: Int,
    val sourceType: String,
    val sourceUid: String,
    val inputValue: Double,
    val magnitude: Double,
    val outputValue: Double,
    val provenance: String
)

data class DerivedDiagnostic(val code: String, val targetDefinitionUid: String, val message: String)

data class ResolvedStat(
    val statUid: String, val baseValue: Double?, val ruleDerivedValue: Double?, val preCapValue: Double,
    val effectiveValue: Double, val contributions: List<ModifierContribution>, val diagnostics: List<DerivedDiagnostic>
)

data class ResolvedResource(
    val resourceUid: String, val currentValueObserved: Double, val maximumValue: Double?, val regenerationRate: Double?,
    val maximumContributions: List<ModifierContribution>, val regenerationContributions: List<ModifierContribution>, val diagnostics: List<DerivedDiagnostic>
)

data class DerivedResolutionRequest(
    val campaignId: String,
    val characterUid: String,
    val resolutionEpoch: Long,
    val statDefinitions: List<StatDefinition>,
    val resourceDefinitions: List<ResourceDefinition>,
    val playerStats: List<PlayerStat>,
    val playerResources: List<PlayerResource>,
    val modifiers: List<Modifier>,
    val ruleVersions: Map<String, Long> = emptyMap(),
    val legacyStatAliases: List<LegacyStatAlias> = emptyList(),
    val legacyResourceAliases: List<LegacyResourceAlias> = emptyList(),
    val skillDefinitions: List<SkillDefinition> = emptyList(),
    val playerSkills: List<PlayerSkill> = emptyList()
)

data class DerivedResolutionResult(
    val resolvedStats: List<ResolvedStat>,
    val resolvedResources: List<ResolvedResource>,
    val diagnostics: List<DerivedDiagnostic>,
    val inputFingerprint: String,
    val ruleFingerprint: String,
    val resolvedSkills: List<ResolvedSkill> = emptyList()
)
