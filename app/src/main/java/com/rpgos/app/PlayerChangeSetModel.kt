package com.rpgos.app

import java.util.Collections

const val PLAYER_CHANGE_SET_SCHEMA_VERSION = 1

class PlayerChangeSetStructuralException(val code: String) : IllegalArgumentException(code)
class PlayerChangeSetIdentityConflictException : IllegalStateException("CHANGESET_IDENTITY_CONFLICT")
enum class PlayerChangeSetIdentityRelation { SAME_LOGICAL_CHANGE_SET, DISTINCT_CHANGE_SET }

enum class ChangeIntentClassification {
    AUTHORITATIVE_MUTATION_INTENT,
    DERIVED_RECOMPUTE_INTENT,
    RUNTIME_MUTATION_INTENT,
    LEDGER_APPEND_INTENT,
    EVENT_APPEND_INTENT,
    CACHE_INVALIDATION_INTENT
}

data class ExactLongDelta private constructor(val units: Long) {
    init {
        if (units == 0L) throw PlayerChangeSetStructuralException("ZERO_DELTA")
    }

    fun plus(other: ExactLongDelta): ExactLongDelta = of(Math.addExact(units, other.units))

    companion object {
        fun of(units: Long): ExactLongDelta {
            if (units == 0L) throw PlayerChangeSetStructuralException("ZERO_DELTA")
            return ExactLongDelta(units)
        }

        fun between(previous: Long, proposed: Long): ExactLongDelta = of(Math.subtractExact(proposed, previous))
    }
}

sealed interface PlayerDomainChangePayload

data class StatChange(
    val subject: DomainRef,
    val statUid: String,
    val delta: ExactLongDelta
) : PlayerDomainChangePayload

data class ResourceChange(
    val subject: DomainRef,
    val resourceUid: String,
    val delta: ExactLongDelta
) : PlayerDomainChangePayload

data class SkillChange(
    val subject: DomainRef,
    val skillUid: String,
    val progressDelta: ExactLongDelta
) : PlayerDomainChangePayload

data class TechniqueChange(
    val subject: DomainRef,
    val techniqueUid: String,
    val progressDelta: ExactLongDelta
) : PlayerDomainChangePayload

data class InnateChange(
    val subject: DomainRef,
    val innateUid: String,
    val proposedStateUid: String
) : PlayerDomainChangePayload

data class InventoryChange(
    val subject: DomainRef,
    val itemInstanceUid: String,
    val quantityDelta: ExactLongDelta
) : PlayerDomainChangePayload

enum class EquipmentOperation { EQUIP, UNEQUIP }

data class EquipmentChange(
    val subject: DomainRef,
    val slotUid: String,
    val operation: EquipmentOperation,
    val itemInstanceUid: String? = null
) : PlayerDomainChangePayload

data class FinancialChange(
    val fromAccountUid: String,
    val toAccountUid: String,
    val amountMinor: Long,
    val currencyUid: String,
    val transactionTypeUid: String
) : PlayerDomainChangePayload

data class AssetChange(
    val asset: OwnedAssetRef,
    val proposedLifecycleStateUid: String
) : PlayerDomainChangePayload

data class OwnershipChange(
    val ownershipRecordUid: String,
    val asset: OwnedAssetRef,
    val fromOwner: OwnershipOwnerRef,
    val toOwner: OwnershipOwnerRef,
    val share: OwnershipShare
) : PlayerDomainChangePayload

/** Typed truth mutation; CampaignTruthStore remains the sole truth authority. */
data class CampaignTruthChange(
    val truthUid: String,
    val kind: TruthKind,
    val subjectUid: String?,
    val predicate: String,
    val objectValue: String?,
    val perspectiveUid: String?,
    val narrativeText: String?,
    val supersedesTruthUid: String?
) : PlayerDomainChangePayload

enum class ConditionOperation { ADD, REMOVE }

data class ConditionChange(
    val subject: DomainRef,
    val conditionUid: String,
    val operation: ConditionOperation
) : PlayerDomainChangePayload

data class RuntimeChange(
    val subject: DomainRef,
    val runtimeCounterUid: String,
    val delta: ExactLongDelta
) : PlayerDomainChangePayload

class DevelopmentProjectChange private constructor(
    val projectUid: String,
    val workResultKindUid: String,
    val progressDelta: ProjectProgressDelta,
    evidenceRefs: List<DomainRef>
) : PlayerDomainChangePayload {
    val evidenceRefs: List<DomainRef> = immutableList(evidenceRefs)

    override fun equals(other: Any?): Boolean = other is DevelopmentProjectChange &&
        projectUid == other.projectUid &&
        workResultKindUid == other.workResultKindUid &&
        progressDelta == other.progressDelta &&
        evidenceRefs == other.evidenceRefs

    override fun hashCode(): Int = arrayOf(projectUid, workResultKindUid, progressDelta, evidenceRefs).contentHashCode()

    companion object {
        fun create(
            projectUid: String,
            workResultKindUid: String,
            progressDelta: ProjectProgressDelta,
            evidenceRefs: List<DomainRef> = emptyList()
        ): DevelopmentProjectChange = DevelopmentProjectChange(projectUid, workResultKindUid, progressDelta, evidenceRefs)
    }
}

object PlayerChangeKinds {
    const val STAT = "RPGOS-CHANGE:STAT_ADJUST"
    const val RESOURCE = "RPGOS-CHANGE:RESOURCE_ADJUST"
    const val SKILL = "RPGOS-CHANGE:SKILL_PROGRESS"
    const val TECHNIQUE = "RPGOS-CHANGE:TECHNIQUE_PROGRESS"
    const val INNATE = "RPGOS-CHANGE:INNATE_STATE"
    const val INVENTORY = "RPGOS-CHANGE:INVENTORY_QUANTITY"
    const val EQUIPMENT = "RPGOS-CHANGE:EQUIPMENT"
    const val FINANCIAL = "RPGOS-CHANGE:FINANCIAL_TRANSFER"
    const val ASSET = "RPGOS-CHANGE:ASSET_LIFECYCLE"
    const val OWNERSHIP = "RPGOS-CHANGE:OWNERSHIP_TRANSFER"
    const val CAMPAIGN_TRUTH = "RPGOS-CHANGE:CAMPAIGN_TRUTH"
    const val CONDITION = "RPGOS-CHANGE:CONDITION"
    const val RUNTIME = "RPGOS-CHANGE:RUNTIME_COUNTER"
    const val DEVELOPMENT_PROJECT = "RPGOS-CHANGE:DEVELOPMENT_PROJECT_WORK"
}

class PlayerDomainChange private constructor(
    val changeUid: String,
    val changeKindUid: String,
    val payload: PlayerDomainChangePayload,
    val sourceRuleUid: String?
) {
    override fun equals(other: Any?): Boolean = other is PlayerDomainChange &&
        changeUid == other.changeUid && changeKindUid == other.changeKindUid &&
        payload == other.payload && sourceRuleUid == other.sourceRuleUid

    override fun hashCode(): Int = arrayOf(changeUid, changeKindUid, payload, sourceRuleUid).contentHashCode()

    companion object {
        fun create(
            changeUid: String,
            changeKindUid: String,
            payload: PlayerDomainChangePayload,
            sourceRuleUid: String? = null,
            registry: TypedPlayerChangeRegistry = TypedPlayerChangeRegistry.core()
        ): PlayerDomainChange {
            val result = PlayerDomainChange(changeUid, changeKindUid, payload, sourceRuleUid)
            registry.validateChange(result)
            return result
        }
    }
}

sealed interface ChangeSetPrecondition

data class ChangeSetExpectedRecordVersion(
    val target: DomainRef,
    val expectedVersion: Long
) : ChangeSetPrecondition

data class ChangeSetExpectedLifecycleState(
    val target: DomainRef,
    val expectedStateUid: String
) : ChangeSetPrecondition

data class ChangeSetProvenance(
    val sourceCommandUid: String,
    val resolverKindUid: String,
    val resolverVersion: String,
    val worldRuleProviderUid: String? = null,
    val mechanicsVersion: String? = null,
    val sourceEventUid: String? = null
)

data class ChangeSetWarning(
    val warningKindUid: String,
    val detail: String? = null,
    val relatedChangeUid: String? = null
)

sealed interface PlayerEventIntentPayload

data class DomainEffectEventIntentPayload(
    val subject: DomainRef,
    val effectKindUid: String
) : PlayerEventIntentPayload

object PlayerEventIntentKinds {
    const val DOMAIN_EFFECT = "RPGOS-EVENT-INTENT:DOMAIN_EFFECT"
}

class PlayerEventIntent private constructor(
    val eventIntentUid: String,
    val eventKindUid: String,
    val actorRef: DomainRef?,
    targetRefs: List<DomainRef>,
    causalChangeUids: List<String>,
    val payload: PlayerEventIntentPayload,
    val proposedEffectiveOrder: Long?
) {
    val targetRefs: List<DomainRef> = immutableList(targetRefs)
    val causalChangeUids: List<String> = immutableList(causalChangeUids)

    override fun equals(other: Any?): Boolean = other is PlayerEventIntent &&
        eventIntentUid == other.eventIntentUid && eventKindUid == other.eventKindUid && actorRef == other.actorRef &&
        targetRefs == other.targetRefs && causalChangeUids == other.causalChangeUids && payload == other.payload &&
        proposedEffectiveOrder == other.proposedEffectiveOrder

    override fun hashCode(): Int = arrayOf(eventIntentUid, eventKindUid, actorRef, targetRefs, causalChangeUids, payload, proposedEffectiveOrder).contentHashCode()

    companion object {
        fun create(
            eventIntentUid: String,
            eventKindUid: String,
            actorRef: DomainRef? = null,
            targetRefs: List<DomainRef> = emptyList(),
            causalChangeUids: List<String> = emptyList(),
            payload: PlayerEventIntentPayload,
            proposedEffectiveOrder: Long? = null
        ): PlayerEventIntent = PlayerEventIntent(
            eventIntentUid, eventKindUid, actorRef, targetRefs, causalChangeUids, payload, proposedEffectiveOrder
        )
    }
}

sealed interface PlayerLedgerIntentPayload

data class FinancialTransferLedgerIntentPayload(
    val fromAccountUid: String,
    val toAccountUid: String,
    val amountMinor: Long,
    val currencyUid: String,
    val transactionTypeUid: String
) : PlayerLedgerIntentPayload

object PlayerLedgerIntentKinds {
    const val FINANCIAL_TRANSFER = "RPGOS-LEDGER-INTENT:FINANCIAL_TRANSFER"
}

class PlayerLedgerIntent private constructor(
    val ledgerIntentUid: String,
    val ledgerKindUid: String,
    causalChangeUids: List<String>,
    val payload: PlayerLedgerIntentPayload
) {
    val causalChangeUids: List<String> = immutableList(causalChangeUids)

    override fun equals(other: Any?): Boolean = other is PlayerLedgerIntent &&
        ledgerIntentUid == other.ledgerIntentUid && ledgerKindUid == other.ledgerKindUid &&
        causalChangeUids == other.causalChangeUids && payload == other.payload

    override fun hashCode(): Int = arrayOf(ledgerIntentUid, ledgerKindUid, causalChangeUids, payload).contentHashCode()

    companion object {
        fun create(
            ledgerIntentUid: String,
            ledgerKindUid: String,
            causalChangeUids: List<String> = emptyList(),
            payload: PlayerLedgerIntentPayload
        ): PlayerLedgerIntent = PlayerLedgerIntent(ledgerIntentUid, ledgerKindUid, causalChangeUids, payload)
    }
}

class PlayerChangeSet private constructor(
    val schemaVersion: Int,
    val changeSetUid: String,
    val campaignUid: String,
    val sourceCommandUid: String,
    val actor: CommandActorRef,
    changes: List<PlayerDomainChange>,
    eventIntents: List<PlayerEventIntent>,
    ledgerIntents: List<PlayerLedgerIntent>,
    preconditions: List<ChangeSetPrecondition>,
    val provenance: ChangeSetProvenance,
    val causationUid: String?,
    val correlationUid: String?,
    val requestedEffectiveOrder: Long?,
    warnings: List<ChangeSetWarning>
) {
    val changes: List<PlayerDomainChange> = immutableList(changes)
    val eventIntents: List<PlayerEventIntent> = immutableList(eventIntents)
    val ledgerIntents: List<PlayerLedgerIntent> = immutableList(ledgerIntents)
    val preconditions: List<ChangeSetPrecondition> = immutableList(preconditions)
    val warnings: List<ChangeSetWarning> = immutableList(warnings)

    override fun equals(other: Any?): Boolean = other is PlayerChangeSet &&
        schemaVersion == other.schemaVersion && changeSetUid == other.changeSetUid && campaignUid == other.campaignUid &&
        sourceCommandUid == other.sourceCommandUid && actor == other.actor && changes == other.changes &&
        eventIntents == other.eventIntents && ledgerIntents == other.ledgerIntents && preconditions == other.preconditions &&
        provenance == other.provenance && causationUid == other.causationUid && correlationUid == other.correlationUid &&
        requestedEffectiveOrder == other.requestedEffectiveOrder && warnings == other.warnings

    override fun hashCode(): Int = arrayOf(
        schemaVersion, changeSetUid, campaignUid, sourceCommandUid, actor, changes, eventIntents, ledgerIntents,
        preconditions, provenance, causationUid, correlationUid, requestedEffectiveOrder, warnings
    ).contentHashCode()

    companion object {
        fun create(
            schemaVersion: Int = PLAYER_CHANGE_SET_SCHEMA_VERSION,
            changeSetUid: String,
            campaignUid: String,
            sourceCommandUid: String,
            actor: CommandActorRef,
            changes: List<PlayerDomainChange> = emptyList(),
            eventIntents: List<PlayerEventIntent> = emptyList(),
            ledgerIntents: List<PlayerLedgerIntent> = emptyList(),
            preconditions: List<ChangeSetPrecondition> = emptyList(),
            provenance: ChangeSetProvenance,
            causationUid: String? = null,
            correlationUid: String? = null,
            requestedEffectiveOrder: Long? = null,
            warnings: List<ChangeSetWarning> = emptyList(),
            registry: TypedPlayerChangeRegistry = TypedPlayerChangeRegistry.core()
        ): PlayerChangeSet {
            val result = PlayerChangeSet(
                schemaVersion, changeSetUid, campaignUid, sourceCommandUid, actor,
                changes, eventIntents, ledgerIntents, preconditions, provenance,
                causationUid, correlationUid, requestedEffectiveOrder, warnings
            )
            PlayerChangeSetValidator.validate(result, registry)
            return result
        }
    }
}

internal fun <T> immutableList(values: List<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
