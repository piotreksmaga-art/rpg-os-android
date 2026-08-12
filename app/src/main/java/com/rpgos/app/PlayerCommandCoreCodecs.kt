package com.rpgos.app

import kotlin.reflect.KClass
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal fun coreCommandCodecs(): Map<String, TypedCommandCodec<out PlayerCommandPayload>> = linkedMapOf(
    PlayerCommandKinds.TRAIN to codec(
        TrainCommandPayload::class,
        { jobj("focus" to encodeRef(it.focus), "effortUnits" to j(it.effortUnits), "methodUid" to jn(it.methodUid)) },
        { TrainCommandPayload(decodeRef(it.reqObject("focus")), it.reqLong("effortUnits"), it.optString("methodUid")) },
        { combine(refErrors(it.focus, "INVALID_FOCUS_REF"), errorIf(it.effortUnits <= 0, "INVALID_EFFORT_UNITS")) }
    ),
    PlayerCommandKinds.USE_RESOURCE to codec(
        UseResourceActionCommandPayload::class,
        { jobj("resource" to encodeRef(it.resource), "requestedAmount" to j(it.requestedAmount), "methodUid" to jn(it.methodUid)) },
        { UseResourceActionCommandPayload(decodeRef(it.reqObject("resource")), it.reqLong("requestedAmount"), it.optString("methodUid")) },
        { combine(refErrors(it.resource, "INVALID_RESOURCE_REF"), errorIf(it.requestedAmount <= 0, "INVALID_REQUESTED_AMOUNT")) }
    ),
    PlayerCommandKinds.RECOVER to codec(
        RecoverCommandPayload::class,
        { jobj("resource" to (it.resource?.let(::encodeRef) ?: JsonNull), "effortUnits" to (it.effortUnits?.let(::j) ?: JsonNull)) },
        { RecoverCommandPayload(it.optObject("resource")?.let(::decodeRef), it.optLong("effortUnits")) },
        { combine(refErrors(it.resource, "INVALID_RESOURCE_REF"), errorIf(it.effortUnits != null && it.effortUnits <= 0, "INVALID_EFFORT_UNITS")) }
    ),
    PlayerCommandKinds.LEARN_SKILL to codec(
        LearnSkillCommandPayload::class,
        { jobj("skillUid" to j(it.skillUid), "methodUid" to jn(it.methodUid)) },
        { LearnSkillCommandPayload(it.reqString("skillUid"), it.optString("methodUid")) },
        { nonblank(it.skillUid, "INVALID_SKILL_UID") }
    ),
    PlayerCommandKinds.PRACTICE_SKILL to codec(
        PracticeSkillCommandPayload::class,
        { jobj("skillUid" to j(it.skillUid), "effortUnits" to j(it.effortUnits), "methodUid" to jn(it.methodUid)) },
        { PracticeSkillCommandPayload(it.reqString("skillUid"), it.reqLong("effortUnits"), it.optString("methodUid")) },
        { combine(nonblank(it.skillUid, "INVALID_SKILL_UID"), errorIf(it.effortUnits <= 0, "INVALID_EFFORT_UNITS")) }
    ),
    PlayerCommandKinds.LEARN_TECHNIQUE to codec(
        LearnTechniqueCommandPayload::class,
        { jobj("techniqueUid" to j(it.techniqueUid), "methodUid" to jn(it.methodUid)) },
        { LearnTechniqueCommandPayload(it.reqString("techniqueUid"), it.optString("methodUid")) },
        { nonblank(it.techniqueUid, "INVALID_TECHNIQUE_UID") }
    ),
    PlayerCommandKinds.USE_TECHNIQUE to codec(
        UseTechniqueCommandPayload::class,
        { jobj("techniqueUid" to j(it.techniqueUid), "target" to (it.target?.let(::encodeRef) ?: JsonNull)) },
        { UseTechniqueCommandPayload(it.reqString("techniqueUid"), it.optObject("target")?.let(::decodeRef)) },
        { combine(nonblank(it.techniqueUid, "INVALID_TECHNIQUE_UID"), refErrors(it.target, "INVALID_TARGET_REF")) }
    ),
    PlayerCommandKinds.ACQUIRE_ITEM to codec(
        AcquireItemCommandPayload::class,
        { jobj("itemDefinitionUid" to j(it.itemDefinitionUid), "requestedQuantity" to j(it.requestedQuantity), "sourceRef" to (it.sourceRef?.let(::encodeRef) ?: JsonNull)) },
        { AcquireItemCommandPayload(it.reqString("itemDefinitionUid"), it.reqLong("requestedQuantity"), it.optObject("sourceRef")?.let(::decodeRef)) },
        { combine(nonblank(it.itemDefinitionUid, "INVALID_ITEM_DEFINITION_UID"), errorIf(it.requestedQuantity <= 0, "INVALID_QUANTITY"), refErrors(it.sourceRef, "INVALID_SOURCE_REF")) }
    ),
    PlayerCommandKinds.TRANSFER_ITEM to codec(
        TransferItemCommandPayload::class,
        { jobj("item" to encodeRef(it.item), "toParty" to encodeRef(it.toParty), "requestedQuantity" to (it.requestedQuantity?.let(::j) ?: JsonNull)) },
        { TransferItemCommandPayload(decodeRef(it.reqObject("item")), decodeRef(it.reqObject("toParty")), it.optLong("requestedQuantity")) },
        { combine(refErrors(it.item, "INVALID_ITEM_REF"), refErrors(it.toParty, "INVALID_PARTY_REF"), errorIf(it.requestedQuantity != null && it.requestedQuantity <= 0, "INVALID_QUANTITY")) }
    ),
    PlayerCommandKinds.CONSUME_ITEM to codec(
        ConsumeItemCommandPayload::class,
        { jobj("item" to encodeRef(it.item), "requestedQuantity" to j(it.requestedQuantity)) },
        { ConsumeItemCommandPayload(decodeRef(it.reqObject("item")), it.reqLong("requestedQuantity")) },
        { combine(refErrors(it.item, "INVALID_ITEM_REF"), errorIf(it.requestedQuantity <= 0, "INVALID_QUANTITY")) }
    ),
    PlayerCommandKinds.EQUIP_ITEM to codec(
        EquipItemCommandPayload::class,
        { jobj("item" to encodeRef(it.item), "requestedSlotUid" to j(it.requestedSlotUid)) },
        { EquipItemCommandPayload(decodeRef(it.reqObject("item")), it.reqString("requestedSlotUid")) },
        { combine(refErrors(it.item, "INVALID_ITEM_REF"), nonblank(it.requestedSlotUid, "INVALID_SLOT_UID")) }
    ),
    PlayerCommandKinds.UNEQUIP_SLOT to codec(
        UnequipSlotCommandPayload::class,
        { jobj("requestedSlotUid" to j(it.requestedSlotUid)) },
        { UnequipSlotCommandPayload(it.reqString("requestedSlotUid")) },
        { nonblank(it.requestedSlotUid, "INVALID_SLOT_UID") }
    ),
    PlayerCommandKinds.TRANSFER_OWNERSHIP to codec(
        TransferOwnershipCommandPayload::class,
        { jobj("subject" to encodeRef(it.subject), "toParty" to encodeRef(it.toParty), "requestedShareBasisPoints" to (it.requestedShareBasisPoints?.let(::j) ?: JsonNull)) },
        { TransferOwnershipCommandPayload(decodeRef(it.reqObject("subject")), decodeRef(it.reqObject("toParty")), it.optLong("requestedShareBasisPoints")) },
        { combine(refErrors(it.subject, "INVALID_SUBJECT_REF"), refErrors(it.toParty, "INVALID_PARTY_REF"), errorIf(it.requestedShareBasisPoints != null && it.requestedShareBasisPoints !in 1..10000, "INVALID_SHARE_BPS")) }
    ),
    PlayerCommandKinds.TRANSFER_FUNDS to codec(
        TransferFundsCommandPayload::class,
        { jobj("fromAccountUid" to j(it.fromAccountUid), "toAccountUid" to j(it.toAccountUid), "amountMinor" to j(it.amountMinor), "currencyUid" to j(it.currencyUid)) },
        { TransferFundsCommandPayload(it.reqString("fromAccountUid"), it.reqString("toAccountUid"), it.reqLong("amountMinor"), it.reqString("currencyUid")) },
        { combine(nonblank(it.fromAccountUid, "INVALID_FROM_ACCOUNT_UID"), nonblank(it.toAccountUid, "INVALID_TO_ACCOUNT_UID"), nonblank(it.currencyUid, "INVALID_CURRENCY_UID"), errorIf(it.amountMinor <= 0, "INVALID_AMOUNT")) }
    ),
    PlayerCommandKinds.ACQUIRE_ASSET to codec(
        AcquireAssetCommandPayload::class,
        { jobj("assetKindUid" to j(it.assetKindUid), "requestedTermsRef" to (it.requestedTermsRef?.let(::encodeRef) ?: JsonNull)) },
        { AcquireAssetCommandPayload(it.reqString("assetKindUid"), it.optObject("requestedTermsRef")?.let(::decodeRef)) },
        { combine(nonblank(it.assetKindUid, "INVALID_ASSET_KIND_UID"), refErrors(it.requestedTermsRef, "INVALID_TERMS_REF")) }
    ),
    PlayerCommandKinds.ENTER_OBLIGATION to codec(
        EnterObligationCommandPayload::class,
        { jobj("obligationTypeUid" to j(it.obligationTypeUid), "counterparty" to encodeRef(it.counterparty), "principalMinor" to (it.principalMinor?.let(::j) ?: JsonNull), "currencyUid" to jn(it.currencyUid)) },
        { EnterObligationCommandPayload(it.reqString("obligationTypeUid"), decodeRef(it.reqObject("counterparty")), it.optLong("principalMinor"), it.optString("currencyUid")) },
        { combine(nonblank(it.obligationTypeUid, "INVALID_OBLIGATION_TYPE_UID"), refErrors(it.counterparty, "INVALID_COUNTERPARTY_REF"), errorIf(it.principalMinor != null && it.principalMinor <= 0, "INVALID_PRINCIPAL"), errorIf((it.principalMinor == null) != (it.currencyUid == null), "PRINCIPAL_CURRENCY_PAIR_REQUIRED")) }
    ),
    PlayerCommandKinds.SETTLE_OBLIGATION to codec(
        SettleObligationCommandPayload::class,
        { jobj("obligationUid" to j(it.obligationUid), "requestedAmountMinor" to (it.requestedAmountMinor?.let(::j) ?: JsonNull)) },
        { SettleObligationCommandPayload(it.reqString("obligationUid"), it.optLong("requestedAmountMinor")) },
        { combine(nonblank(it.obligationUid, "INVALID_OBLIGATION_UID"), errorIf(it.requestedAmountMinor != null && it.requestedAmountMinor <= 0, "INVALID_AMOUNT")) }
    ),
    PlayerCommandKinds.START_PROJECT to codec(
        StartProjectCommandPayload::class,
        { jobj("projectTypeUid" to j(it.projectTypeUid), "titleIntent" to j(it.titleIntent), "objectiveIntent" to j(it.objectiveIntent), "beneficiaryRef" to (it.beneficiaryRef?.let(::encodeRef) ?: JsonNull), "targetDomainUid" to j(it.targetDomainUid), "targetRef" to (it.targetRef?.let(::encodeRef) ?: JsonNull), "intendedOutputKindUid" to jn(it.intendedOutputKindUid), "requestedProgressCapUnits" to (it.requestedProgressCapUnits?.let(::j) ?: JsonNull)) },
        { StartProjectCommandPayload(it.reqString("projectTypeUid"), it.reqString("titleIntent"), it.reqString("objectiveIntent"), it.optObject("beneficiaryRef")?.let(::decodeRef), it.reqString("targetDomainUid"), it.optObject("targetRef")?.let(::decodeRef), it.optString("intendedOutputKindUid"), it.optLong("requestedProgressCapUnits")) },
        { combine(nonblank(it.projectTypeUid, "INVALID_PROJECT_TYPE_UID"), nonblank(it.titleIntent, "INVALID_TITLE_INTENT"), nonblank(it.objectiveIntent, "INVALID_OBJECTIVE_INTENT"), nonblank(it.targetDomainUid, "INVALID_TARGET_DOMAIN_UID"), refErrors(it.beneficiaryRef, "INVALID_BENEFICIARY_REF"), refErrors(it.targetRef, "INVALID_TARGET_REF"), errorIf(it.requestedProgressCapUnits != null && it.requestedProgressCapUnits <= 0, "INVALID_PROGRESS_CAP_INTENT")) }
    ),
    PlayerCommandKinds.RECORD_PROJECT_WORK to codec(
        RecordProjectWorkCommandPayload::class,
        { jobj("projectUid" to j(it.projectUid), "workKindUid" to j(it.workKindUid), "effortUnitsIntent" to (it.effortUnitsIntent?.let(::j) ?: JsonNull), "methodUid" to jn(it.methodUid), "evidenceRefs" to refsJson(it.evidenceRefs), "requestedResourceUse" to refsJson(it.requestedResourceUse)) },
        { RecordProjectWorkCommandPayload(it.reqString("projectUid"), it.reqString("workKindUid"), it.optLong("effortUnitsIntent"), it.optString("methodUid"), it.reqArray("evidenceRefs").map { e -> decodeRef(e.jsonObject) }, it.reqArray("requestedResourceUse").map { e -> decodeRef(e.jsonObject) }) },
        { combine(nonblank(it.projectUid, "INVALID_PROJECT_UID"), nonblank(it.workKindUid, "INVALID_WORK_KIND_UID"), errorIf(it.effortUnitsIntent != null && it.effortUnitsIntent <= 0, "INVALID_EFFORT_UNITS"), refsErrors(it.evidenceRefs, "INVALID_EVIDENCE_REF"), refsErrors(it.requestedResourceUse, "INVALID_RESOURCE_REF")) }
    ),
    PlayerCommandKinds.SATISFY_PROJECT_REQUIREMENT to codec(
        SatisfyProjectRequirementCommandPayload::class,
        { jobj("projectUid" to j(it.projectUid), "requirementUid" to j(it.requirementUid), "evidenceRefs" to refsJson(it.evidenceRefs)) },
        { SatisfyProjectRequirementCommandPayload(it.reqString("projectUid"), it.reqString("requirementUid"), it.reqArray("evidenceRefs").map { e -> decodeRef(e.jsonObject) }) },
        { combine(nonblank(it.projectUid, "INVALID_PROJECT_UID"), nonblank(it.requirementUid, "INVALID_REQUIREMENT_UID"), refsErrors(it.evidenceRefs, "INVALID_EVIDENCE_REF")) }
    ),
    PlayerCommandKinds.ACHIEVE_PROJECT_MILESTONE to codec(
        AchieveProjectMilestoneCommandPayload::class,
        { jobj("projectUid" to j(it.projectUid), "milestoneUid" to j(it.milestoneUid), "evidenceRefs" to refsJson(it.evidenceRefs), "sourceWorkRef" to (it.sourceWorkRef?.let(::encodeRef) ?: JsonNull)) },
        { AchieveProjectMilestoneCommandPayload(it.reqString("projectUid"), it.reqString("milestoneUid"), it.reqArray("evidenceRefs").map { e -> decodeRef(e.jsonObject) }, it.optObject("sourceWorkRef")?.let(::decodeRef)) },
        { combine(nonblank(it.projectUid, "INVALID_PROJECT_UID"), nonblank(it.milestoneUid, "INVALID_MILESTONE_UID"), refsErrors(it.evidenceRefs, "INVALID_EVIDENCE_REF"), refErrors(it.sourceWorkRef, "INVALID_SOURCE_WORK_REF")) }
    ),
    PlayerCommandKinds.CHANGE_PROJECT_LIFECYCLE to codec(
        ChangeProjectLifecycleCommandPayload::class,
        { jobj("projectUid" to j(it.projectUid), "requestedStatusUid" to j(it.requestedStatusUid), "successorProjectUid" to jn(it.successorProjectUid)) },
        { ChangeProjectLifecycleCommandPayload(it.reqString("projectUid"), it.reqString("requestedStatusUid"), it.optString("successorProjectUid")) },
        { combine(nonblank(it.projectUid, "INVALID_PROJECT_UID"), nonblank(it.requestedStatusUid, "INVALID_REQUESTED_STATUS")) }
    ),
    PlayerCommandKinds.COMPLETE_PROJECT to codec(
        CompleteProjectCommandPayload::class,
        { jobj("projectUid" to j(it.projectUid), "completionEvidenceRefs" to refsJson(it.completionEvidenceRefs)) },
        { CompleteProjectCommandPayload(it.reqString("projectUid"), it.reqArray("completionEvidenceRefs").map { e -> decodeRef(e.jsonObject) }) },
        { combine(nonblank(it.projectUid, "INVALID_PROJECT_UID"), refsErrors(it.completionEvidenceRefs, "INVALID_EVIDENCE_REF")) }
    ),
    PlayerCommandKinds.CANCEL_PROJECT to codec(
        CancelProjectCommandPayload::class,
        { jobj("projectUid" to j(it.projectUid), "reasonUid" to jn(it.reasonUid), "reasonText" to jn(it.reasonText)) },
        { CancelProjectCommandPayload(it.reqString("projectUid"), it.optString("reasonUid"), it.optString("reasonText")) },
        { nonblank(it.projectUid, "INVALID_PROJECT_UID") }
    )
)

private fun combine(vararg parts: List<String>): List<String> = parts.flatMap { it }

private fun <P : PlayerCommandPayload> codec(
    type: KClass<P>,
    encode: (P) -> JsonObject,
    decode: (JsonObject) -> P,
    validate: (P) -> List<String>
): TypedCommandCodec<P> = object : TypedCommandCodec<P>(type) {
    override fun encode(payload: P): JsonObject = encode(payload)
    override fun decode(obj: JsonObject): P = decode(obj)
    override fun validate(payload: P): List<String> = validate(payload)
}
