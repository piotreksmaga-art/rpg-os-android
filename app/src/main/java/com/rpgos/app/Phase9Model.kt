package com.rpgos.app

enum class Phase9DefinitionStatus { ACTIVE, DEPRECATED }
enum class LegacyPhase9TargetKind { ORIGIN, INNATE_FEATURE, EVOLUTION_STAGE, FORM_UNLOCK }

data class OriginDefinition(
    val originUid: String,
    val worldPackUid: String,
    val key: String,
    val displayName: String,
    val originKind: String,
    val status: Phase9DefinitionStatus = Phase9DefinitionStatus.ACTIVE,
    val definitionVersion: Long = 1L,
    val provenance: String
)

data class PlayerOrigin(
    val campaignId: String,
    val characterUid: String,
    val originUid: String,
    val relationshipKind: String,
    val entryVersion: Long = 1L,
    val provenance: String
)

data class InnateFeatureDefinition(
    val featureUid: String,
    val worldPackUid: String,
    val key: String,
    val displayName: String,
    val featureKind: String,
    val category: String? = null,
    val status: Phase9DefinitionStatus = Phase9DefinitionStatus.ACTIVE,
    val definitionVersion: Long = 1L,
    val provenance: String
)

data class PlayerInnateFeature(
    val campaignId: String,
    val characterUid: String,
    val featureUid: String,
    val acquiredChapter: Long? = null,
    val entryVersion: Long = 1L,
    val provenance: String
)

data class EvolutionPathDefinition(
    val pathUid: String,
    val worldPackUid: String,
    val key: String,
    val displayName: String,
    val status: Phase9DefinitionStatus = Phase9DefinitionStatus.ACTIVE,
    val definitionVersion: Long = 1L,
    val provenance: String
)

data class EvolutionStageDefinition(
    val stageUid: String,
    val pathUid: String,
    val worldPackUid: String,
    val key: String,
    val displayName: String,
    val status: Phase9DefinitionStatus = Phase9DefinitionStatus.ACTIVE,
    val definitionVersion: Long = 1L,
    val provenance: String
)

data class EvolutionTransitionDefinition(
    val transitionUid: String,
    val worldPackUid: String,
    val sourceStageUid: String?,
    val targetStageUid: String,
    val requirementRuleUid: String? = null,
    val reversible: Boolean = false,
    val crossPathAllowed: Boolean = false,
    val transitionVersion: Long = 1L,
    val provenance: String,
    val requirementRuleVersion: Long? = requirementRuleUid?.let { 1L }
) {
    val transitionRequirement: RequirementBinding?
        get() = requirementRuleUid?.let { RequirementBinding(it, requirementRuleVersion ?: error("Transition requirement rule version missing")) }
}

data class PlayerEvolutionState(
    val campaignId: String,
    val characterUid: String,
    val pathUid: String,
    val currentStageUid: String?,
    val stateVersion: Long = 1L,
    val provenance: String
)

data class PlayerEvolutionStage(
    val campaignId: String,
    val characterUid: String,
    val stageUid: String,
    val attainedViaTransitionUid: String? = null,
    val attainedChapter: Long? = null,
    val entryVersion: Long = 1L,
    val provenance: String
)

data class FormDefinition(
    val formUid: String,
    val worldPackUid: String,
    val key: String,
    val displayName: String,
    val sourceFeatureUid: String? = null,
    val sourceStageUid: String? = null,
    val exclusiveGroupUid: String? = null,
    val activationRuleUid: String? = null,
    val status: Phase9DefinitionStatus = Phase9DefinitionStatus.ACTIVE,
    val definitionVersion: Long = 1L,
    val provenance: String,
    val unlockRequirementRuleUid: String? = null,
    val unlockRequirementRuleVersion: Long? = unlockRequirementRuleUid?.let { 1L },
    val activationRuleVersion: Long? = activationRuleUid?.let { 1L }
) {
    val unlockRequirement: RequirementBinding?
        get() = unlockRequirementRuleUid?.let { RequirementBinding(it, unlockRequirementRuleVersion ?: error("Unlock requirement rule version missing")) }
    val activationRequirement: RequirementBinding?
        get() = activationRuleUid?.let { RequirementBinding(it, activationRuleVersion ?: error("Activation requirement rule version missing")) }
}

data class PlayerFormUnlock(
    val campaignId: String,
    val characterUid: String,
    val formUid: String,
    val entryVersion: Long = 1L,
    val provenance: String
)

data class PlayerActiveForm(
    val campaignId: String,
    val characterUid: String,
    val formUid: String,
    val activatedAt: Long? = null,
    val stateVersion: Long = 1L,
    val provenance: String
)

data class FormModifierBinding(
    val bindingUid: String,
    val worldPackUid: String,
    val formUid: String,
    val targetDefinitionUid: String,
    val targetKind: ModifierTargetKind,
    val operation: ModifierOperation,
    val value: Double,
    val priority: Int = 0,
    val bindingVersion: Long = 1L,
    val provenance: String
)

data class LegacyPhase9Evidence(val field: String, val value: String)

data class LegacyPhase9Mapping(
    val worldPackUid: String,
    val evidenceField: String,
    val evidenceValue: String,
    val targetKind: LegacyPhase9TargetKind,
    val targetUid: String,
    val mappingVersion: Long = 1L,
    val provenance: String
)

data class LegacyPhase9Resolution(
    val evidence: LegacyPhase9Evidence,
    val targetKind: LegacyPhase9TargetKind?,
    val targetUid: String?,
    val canonical: Boolean,
    val reason: String
)

data class Phase9PlayerSnapshot(
    val origins: List<PlayerOrigin>,
    val innateFeatures: List<PlayerInnateFeature>,
    val evolutionStates: List<PlayerEvolutionState>,
    val attainedStages: List<PlayerEvolutionStage>,
    val formUnlocks: List<PlayerFormUnlock>,
    val activeForms: List<PlayerActiveForm>,
    val unresolvedLegacy: List<LegacyPhase9Evidence>
) {
    fun toContextMap(): Map<String, Any?> = linkedMapOf(
        "origins" to origins,
        "innate_features" to innateFeatures,
        "evolution_states" to evolutionStates,
        "attained_stages" to attainedStages,
        "form_unlocks" to formUnlocks,
        "active_forms" to activeForms,
        "unresolved_legacy" to unresolvedLegacy
    )
}

object Phase9Policy {
    fun requireIdentity(uid: String, name: String) = require(uid.isNotBlank()) { "$name must not be blank" }
    fun requireDefinition(worldPackUid: String, key: String, displayName: String, version: Long, provenance: String) {
        requireIdentity(worldPackUid, "worldPackUid")
        requireIdentity(key, "definition key")
        requireIdentity(displayName, "displayName")
        require(version >= 1L) { "definition version must be at least 1" }
        requireIdentity(provenance, "provenance")
    }
    fun requireOptionalRule(uid: String?, version: Long?, label: String) {
        if (uid == null) require(version == null) { "$label rule version without rule UID" }
        else {
            requireIdentity(uid, "$label ruleUid")
            require(version != null && version >= 1L) { "$label rule version must be at least 1" }
        }
    }
}
