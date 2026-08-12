package com.rpgos.app

const val PROJECT_TYPE_RESEARCH = "RPGOS-PROJECT-TYPE:RESEARCH"
const val PROJECT_TYPE_TECHNIQUE = "RPGOS-PROJECT-TYPE:TECHNIQUE"
const val PROJECT_TYPE_SKILL = "RPGOS-PROJECT-TYPE:SKILL"
const val PROJECT_TYPE_CRAFTING = "RPGOS-PROJECT-TYPE:CRAFTING"
const val PROJECT_TYPE_INFRASTRUCTURE = "RPGOS-PROJECT-TYPE:INFRASTRUCTURE"
const val PROJECT_TYPE_ADAPTATION = "RPGOS-PROJECT-TYPE:ADAPTATION"

const val PROJECT_OUTPUT_TECHNIQUE = "RPGOS-PROJECT-OUTPUT:TECHNIQUE"
const val PROJECT_OUTPUT_SKILL = "RPGOS-PROJECT-OUTPUT:SKILL"
const val PROJECT_OUTPUT_ITEM_INSTANCE = "RPGOS-PROJECT-OUTPUT:ITEM_INSTANCE"
const val PROJECT_OUTPUT_ASSET = "RPGOS-PROJECT-OUTPUT:ASSET"
const val PROJECT_OUTPUT_TRUTH = "RPGOS-PROJECT-OUTPUT:TRUTH"

enum class ProjectStatus { IDEA, REQUIREMENTS, PROTOTYPE, ACTIVE_WORK, STABILIZATION, READY_TO_COMPLETE, PAUSED, COMPLETED, ABANDONED, FAILED, SUPERSEDED, CANCELLED }
enum class ProjectWorkResult { SUCCESS, PARTIAL, FAILURE, BREAKTHROUGH, NO_PROGRESS, INCIDENT }

data class ProjectTypeDefinition(
    val projectTypeUid: String,
    val genericCategoryUid: String,
    val lifecyclePolicyUid: String = "RPGOS-PROJECT-LIFECYCLE:STANDARD",
    val worldPackUid: String? = null,
    val definitionStatus: String = "ACTIVE",
    val definitionVersion: Int = 1,
    val provenance: String,
    val metadataJson: String? = null
)

data class DevelopmentProject(
    val campaignId: String,
    val projectUid: String,
    val projectTypeUid: String,
    val initiator: OwnershipOwnerRef,
    val beneficiary: OwnershipOwnerRef? = null,
    val title: String,
    val objectiveSummary: String,
    val targetDomainUid: String,
    val targetKindUid: String? = null,
    val targetUid: String? = null,
    val intendedOutputKindUid: String? = null,
    val progressCapUnits: Long? = null,
    val createdOrder: Long,
    val startedOrder: Long? = null,
    val projectVersion: Int = 1,
    val sourceEventUid: String? = null,
    val provenance: String,
    val metadataJson: String? = null
)

data class ProjectStatusEvent(
    val campaignId: String,
    val statusEventUid: String,
    val projectUid: String,
    val status: ProjectStatus,
    val effectiveOrder: Long,
    val successorProjectUid: String? = null,
    val sourceEventUid: String? = null,
    val provenance: String
)

data class ProjectRequirement(
    val campaignId: String,
    val requirementUid: String,
    val projectUid: String,
    val requirementTypeUid: String,
    val targetKindUid: String? = null,
    val targetUid: String? = null,
    val comparatorUid: String? = null,
    val thresholdValue: Long? = null,
    val quantityValue: Long? = null,
    val required: Boolean = true,
    val requiredFromOrder: Long,
    val requirementVersion: Int = 1,
    val provenance: String,
    val metadataJson: String? = null
)

data class ProjectRequirementSatisfaction(
    val campaignId: String,
    val satisfactionUid: String,
    val projectUid: String,
    val requirementUid: String,
    val satisfiedOrder: Long,
    val evidenceKindUid: String? = null,
    val evidenceUid: String? = null,
    val sourceEventUid: String? = null,
    val provenance: String
)

data class ProjectMilestoneDefinition(
    val campaignId: String,
    val milestoneUid: String,
    val projectUid: String,
    val sequenceOrder: Long,
    val milestoneTypeUid: String,
    val successCriteria: String,
    val required: Boolean = true,
    val provenance: String
)

data class ProjectMilestoneAchievement(
    val campaignId: String,
    val achievementUid: String,
    val projectUid: String,
    val milestoneUid: String,
    val achievedOrder: Long,
    val sourceWorkRecordUid: String? = null,
    val sourceEventUid: String? = null,
    val provenance: String
)

data class ProjectWorkRecord(
    val campaignId: String,
    val workRecordUid: String,
    val projectUid: String,
    val workKindUid: String,
    val actor: OwnershipOwnerRef,
    val effectiveOrder: Long,
    val result: ProjectWorkResult,
    val progressDeltaUnits: Long = 0,
    val effortUnits: Long? = null,
    val financialTransactionUid: String? = null,
    val commandUid: String? = null,
    val sourceEventUid: String? = null,
    val provenance: String,
    val metadataJson: String? = null
)

data class ProjectDependency(
    val campaignId: String,
    val dependencyUid: String,
    val projectUid: String,
    val dependsOnProjectUid: String,
    val dependencyTypeUid: String,
    val milestoneUid: String? = null,
    val validFromOrder: Long,
    val provenance: String
)

data class ProjectOutcome(
    val campaignId: String,
    val outcomeUid: String,
    val projectUid: String,
    val outputKindUid: String,
    val outputRefKindUid: String? = null,
    val outputUid: String,
    val committedOrder: Long,
    val sourceEventUid: String? = null,
    val commandUid: String? = null,
    val provenance: String
)

data class ProjectProgressSnapshot(
    val projectUid: String,
    val status: ProjectStatus,
    val progressUnits: Long,
    val progressCapUnits: Long?,
    val requiredMilestones: Int,
    val achievedRequiredMilestones: Int,
    val workRecordCount: Long
)
