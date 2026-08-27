package com.rpgos.app

const val PLAYER_COMMAND_SCHEMA_VERSION = 1

interface PlayerCommandPayload

data class CommandActorRef(val actorKindUid: String, val actorUid: String)
data class DomainRef(val kindUid: String, val uid: String)
data class CommandProvenance(
    val sourceKindUid: String,
    val sourceUid: String? = null,
    val detail: String? = null
)

sealed interface CommandPrecondition
data class ExpectedRecordVersion(val target: DomainRef, val expectedVersion: Long) : CommandPrecondition
data class ExpectedLifecycleState(val target: DomainRef, val expectedStateUid: String) : CommandPrecondition

sealed interface TypedCommandExtension {
    val extensionKindUid: String
    val schemaVersion: Int
}
data class NamespacedTextCommandExtension(
    override val extensionKindUid: String,
    override val schemaVersion: Int,
    val value: String
) : TypedCommandExtension

data class PlayerCommand<P : PlayerCommandPayload>(
    val schemaVersion: Int = PLAYER_COMMAND_SCHEMA_VERSION,
    val commandUid: String,
    val campaignUid: String,
    val actor: CommandActorRef,
    val commandKindUid: String,
    val payload: P,
    val provenance: CommandProvenance,
    val causationUid: String? = null,
    val correlationUid: String? = null,
    val requestedEffectiveOrder: Long? = null,
    val preconditions: List<CommandPrecondition> = emptyList(),
    val extensions: List<TypedCommandExtension> = emptyList()
)

class PlayerCommandStructuralException(val code: String) : IllegalArgumentException(code)
class CommandIdentityConflictException : IllegalStateException("COMMAND_IDENTITY_CONFLICT")
enum class CommandIdentityRelation { SAME_LOGICAL_COMMAND, DISTINCT_COMMAND }

object PlayerCommandKinds {
    const val TRAIN = "RPGOS-COMMAND:TRAIN"
    const val USE_RESOURCE = "RPGOS-COMMAND:USE_RESOURCE_ACTION"
    const val RECOVER = "RPGOS-COMMAND:RECOVER"
    const val LEARN_SKILL = "RPGOS-COMMAND:LEARN_SKILL"
    const val PRACTICE_SKILL = "RPGOS-COMMAND:PRACTICE_SKILL"
    const val LEARN_TECHNIQUE = "RPGOS-COMMAND:LEARN_TECHNIQUE"
    const val USE_TECHNIQUE = "RPGOS-COMMAND:USE_TECHNIQUE"
    const val ACQUIRE_ITEM = "RPGOS-COMMAND:ACQUIRE_ITEM"
    const val TRANSFER_ITEM = "RPGOS-COMMAND:TRANSFER_ITEM"
    const val CONSUME_ITEM = "RPGOS-COMMAND:CONSUME_ITEM"
    const val EQUIP_ITEM = "RPGOS-COMMAND:EQUIP_ITEM"
    const val UNEQUIP_SLOT = "RPGOS-COMMAND:UNEQUIP_SLOT"
    const val TRANSFER_OWNERSHIP = "RPGOS-COMMAND:TRANSFER_OWNERSHIP"
    const val TRANSFER_FUNDS = "RPGOS-COMMAND:TRANSFER_FUNDS"
    const val ACQUIRE_ASSET = "RPGOS-COMMAND:ACQUIRE_ASSET"
    const val ENTER_OBLIGATION = "RPGOS-COMMAND:ENTER_OBLIGATION"
    const val SETTLE_OBLIGATION = "RPGOS-COMMAND:SETTLE_OBLIGATION"
    const val START_PROJECT = "RPGOS-COMMAND:START_PROJECT"
    const val RECORD_PROJECT_WORK = "RPGOS-COMMAND:RECORD_PROJECT_WORK"
    const val SATISFY_PROJECT_REQUIREMENT = "RPGOS-COMMAND:SATISFY_PROJECT_REQUIREMENT"
    const val ACHIEVE_PROJECT_MILESTONE = "RPGOS-COMMAND:ACHIEVE_PROJECT_MILESTONE"
    const val CHANGE_PROJECT_LIFECYCLE = "RPGOS-COMMAND:CHANGE_PROJECT_LIFECYCLE"
    const val COMPLETE_PROJECT = "RPGOS-COMMAND:COMPLETE_PROJECT"
    const val CANCEL_PROJECT = "RPGOS-COMMAND:CANCEL_PROJECT"
    /** One aggregate, verified mechanics command for one canonical turn. */
    const val APPLY_VERIFIED_MECHANICS = "RPGOS-COMMAND:APPLY_VERIFIED_MECHANICS"
}

data class TrainCommandPayload(val focus: DomainRef, val effortUnits: Long, val methodUid: String? = null) : PlayerCommandPayload
data class UseResourceActionCommandPayload(val resource: DomainRef, val requestedAmount: Long, val methodUid: String? = null) : PlayerCommandPayload
data class RecoverCommandPayload(val resource: DomainRef?, val effortUnits: Long? = null) : PlayerCommandPayload
data class LearnSkillCommandPayload(val skillUid: String, val methodUid: String? = null) : PlayerCommandPayload
data class PracticeSkillCommandPayload(val skillUid: String, val effortUnits: Long, val methodUid: String? = null) : PlayerCommandPayload
data class LearnTechniqueCommandPayload(val techniqueUid: String, val methodUid: String? = null) : PlayerCommandPayload
data class UseTechniqueCommandPayload(val techniqueUid: String, val target: DomainRef? = null) : PlayerCommandPayload
data class AcquireItemCommandPayload(val itemDefinitionUid: String, val requestedQuantity: Long = 1, val sourceRef: DomainRef? = null) : PlayerCommandPayload
data class TransferItemCommandPayload(val item: DomainRef, val toParty: DomainRef, val requestedQuantity: Long? = null) : PlayerCommandPayload
data class ConsumeItemCommandPayload(val item: DomainRef, val requestedQuantity: Long = 1) : PlayerCommandPayload
data class EquipItemCommandPayload(val item: DomainRef, val requestedSlotUid: String) : PlayerCommandPayload
data class UnequipSlotCommandPayload(val requestedSlotUid: String) : PlayerCommandPayload
data class TransferOwnershipCommandPayload(val subject: DomainRef, val toParty: DomainRef, val requestedShareBasisPoints: Long? = null) : PlayerCommandPayload
data class TransferFundsCommandPayload(val fromAccountUid: String, val toAccountUid: String, val amountMinor: Long, val currencyUid: String) : PlayerCommandPayload
data class AcquireAssetCommandPayload(val assetKindUid: String, val requestedTermsRef: DomainRef? = null) : PlayerCommandPayload
data class EnterObligationCommandPayload(val obligationTypeUid: String, val counterparty: DomainRef, val principalMinor: Long?, val currencyUid: String?) : PlayerCommandPayload
data class SettleObligationCommandPayload(val obligationUid: String, val requestedAmountMinor: Long? = null) : PlayerCommandPayload

data class VerifiedMechanicsCommandEffect(
    val effectUid:String,
    val nodeUid:String,
    val mechanicsOwnerUid:String,
    val effectKindUid:String,
    val target:DomainRef,
    val magnitude:Long,
    val canonicalPayload:Map<String,String>,
    val proofUid:String,
    val deterministicInputFingerprint:String,
    val deterministicOutputFingerprint:String
) {
    init {
        require(listOf(effectUid,nodeUid,mechanicsOwnerUid,effectKindUid,proofUid,deterministicInputFingerprint,deterministicOutputFingerprint).none{it.isBlank()})
        require(validRef(target)&&canonicalPayload.keys.none{it.isBlank()}&&canonicalPayload.values.none{it.length>512})
    }
}

data class ApplyVerifiedMechanicsCommandPayload(
    val planUid:String,
    val effects:List<VerifiedMechanicsCommandEffect>
):PlayerCommandPayload {
    init {
        require(planUid.isNotBlank()&&effects.isNotEmpty())
        require(effects.map{it.effectUid}.distinct().size==effects.size)
    }
}

data class StartProjectCommandPayload(
    val projectTypeUid: String,
    val titleIntent: String,
    val objectiveIntent: String,
    val beneficiaryRef: DomainRef? = null,
    val targetDomainUid: String,
    val targetRef: DomainRef? = null,
    val intendedOutputKindUid: String? = null,
    val requestedProgressCapUnits: Long? = null
) : PlayerCommandPayload

data class RecordProjectWorkCommandPayload(
    val projectUid: String,
    val workKindUid: String,
    val effortUnitsIntent: Long? = null,
    val methodUid: String? = null,
    val evidenceRefs: List<DomainRef> = emptyList(),
    val requestedResourceUse: List<DomainRef> = emptyList()
) : PlayerCommandPayload

data class SatisfyProjectRequirementCommandPayload(
    val projectUid: String,
    val requirementUid: String,
    val evidenceRefs: List<DomainRef> = emptyList()
) : PlayerCommandPayload

data class AchieveProjectMilestoneCommandPayload(
    val projectUid: String,
    val milestoneUid: String,
    val evidenceRefs: List<DomainRef> = emptyList(),
    val sourceWorkRef: DomainRef? = null
) : PlayerCommandPayload

data class ChangeProjectLifecycleCommandPayload(
    val projectUid: String,
    val requestedStatusUid: String,
    val successorProjectUid: String? = null
) : PlayerCommandPayload

data class CompleteProjectCommandPayload(
    val projectUid: String,
    val completionEvidenceRefs: List<DomainRef> = emptyList()
) : PlayerCommandPayload

data class CancelProjectCommandPayload(
    val projectUid: String,
    val reasonUid: String? = null,
    val reasonText: String? = null
) : PlayerCommandPayload
