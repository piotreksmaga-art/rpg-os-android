package com.rpgos.app

import java.security.MessageDigest
import kotlin.reflect.KClass
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

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

abstract class TypedCommandCodec<P : PlayerCommandPayload>(val payloadType: KClass<P>) {
    abstract fun encode(payload: P): JsonObject
    abstract fun decode(obj: JsonObject): P
    open fun validate(payload: P): List<String> = emptyList()
    fun encodeUntyped(payload: PlayerCommandPayload): JsonObject {
        if (!payloadType.isInstance(payload)) throw PlayerCommandStructuralException("COMMAND_PAYLOAD_TYPE_MISMATCH")
        @Suppress("UNCHECKED_CAST") return encode(payload as P)
    }
    fun validateUntyped(payload: PlayerCommandPayload): List<String> {
        if (!payloadType.isInstance(payload)) return listOf("COMMAND_PAYLOAD_TYPE_MISMATCH")
        @Suppress("UNCHECKED_CAST") return validate(payload as P)
    }
}

class PlayerCommandKindRegistry private constructor(private val codecs: Map<String, TypedCommandCodec<out PlayerCommandPayload>>) {
    fun codec(kindUid: String): TypedCommandCodec<out PlayerCommandPayload> =
        codecs[kindUid] ?: throw PlayerCommandStructuralException("UNKNOWN_COMMAND_KIND")

    fun validate(command: PlayerCommand<out PlayerCommandPayload>) {
        val errors = mutableListOf<String>()
        if (command.schemaVersion != PLAYER_COMMAND_SCHEMA_VERSION) errors += "UNSUPPORTED_COMMAND_SCHEMA_VERSION"
        if (command.commandUid.isBlank()) errors += "EMPTY_COMMAND_UID"
        if (command.campaignUid.isBlank()) errors += "EMPTY_CAMPAIGN_UID"
        if (command.actor.actorKindUid.isBlank() || command.actor.actorUid.isBlank()) errors += "INVALID_ACTOR_REF"
        if (command.commandKindUid.isBlank()) errors += "EMPTY_COMMAND_KIND_UID"
        if (command.causationUid?.isBlank() == true) errors += "INVALID_CAUSATION_UID"
        if (command.correlationUid?.isBlank() == true) errors += "INVALID_CORRELATION_UID"
        command.preconditions.forEach { errors += validatePrecondition(it) }
        command.extensions.forEach { errors += validateExtension(it) }
        val codec = codecs[command.commandKindUid]
        if (codec == null) errors += "UNKNOWN_COMMAND_KIND" else errors += codec.validateUntyped(command.payload)
        if (errors.isNotEmpty()) throw PlayerCommandStructuralException(errors.first())
    }

    fun encode(command: PlayerCommand<out PlayerCommandPayload>): String {
        validate(command)
        val codec = codec(command.commandKindUid)
        return canonicalJson(command, codec.encodeUntyped(command.payload)).toString()
    }

    fun decode(serialized: String): PlayerCommand<out PlayerCommandPayload> {
        val root = try { Json.parseToJsonElement(serialized).jsonObject } catch (_: Throwable) {
            throw PlayerCommandStructuralException("INVALID_COMMAND_SERIALIZATION")
        }
        val schema = root.reqInt("schemaVersion")
        if (schema != PLAYER_COMMAND_SCHEMA_VERSION) throw PlayerCommandStructuralException("UNSUPPORTED_COMMAND_SCHEMA_VERSION")
        val kind = root.reqString("commandKindUid")
        val codec = codec(kind)
        val payload = try { codec.decode(root.reqObject("payload")) } catch (e: PlayerCommandStructuralException) { throw e } catch (_: Throwable) {
            throw PlayerCommandStructuralException("INVALID_COMMAND_PAYLOAD")
        }
        val command = PlayerCommand(
            schemaVersion = schema,
            commandUid = root.reqString("commandUid"),
            campaignUid = root.reqString("campaignUid"),
            actor = decodeActor(root.reqObject("actor")),
            commandKindUid = kind,
            payload = payload,
            provenance = decodeProvenance(root.reqObject("provenance")),
            causationUid = root.optString("causationUid"),
            correlationUid = root.optString("correlationUid"),
            requestedEffectiveOrder = root.optLong("requestedEffectiveOrder"),
            preconditions = root.reqArray("preconditions").map { decodePrecondition(it.jsonObject) },
            extensions = root.reqArray("extensions").map { decodeExtension(it.jsonObject) }
        )
        validate(command)
        return command
    }

    fun fingerprint(command: PlayerCommand<out PlayerCommandPayload>): String {
        val bytes = encode(command).toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun core(): PlayerCommandKindRegistry = PlayerCommandKindRegistry(coreCodecs())
        fun of(entries: Map<String, TypedCommandCodec<out PlayerCommandPayload>>): PlayerCommandKindRegistry {
            require(entries.keys.none { it.isBlank() })
            return PlayerCommandKindRegistry(entries.toMap())
        }
    }
}

object PlayerCommandIdentity {
    fun compare(
        left: PlayerCommand<out PlayerCommandPayload>,
        right: PlayerCommand<out PlayerCommandPayload>,
        registry: PlayerCommandKindRegistry = PlayerCommandKindRegistry.core()
    ): CommandIdentityRelation {
        if (left.campaignUid != right.campaignUid || left.commandUid != right.commandUid) return CommandIdentityRelation.DISTINCT_COMMAND
        if (registry.fingerprint(left) != registry.fingerprint(right)) throw CommandIdentityConflictException()
        return CommandIdentityRelation.SAME_LOGICAL_COMMAND
    }
}

private fun canonicalJson(command: PlayerCommand<out PlayerCommandPayload>, payload: JsonObject): JsonObject = buildJsonObject {
    put("schemaVersion", JsonPrimitive(command.schemaVersion))
    put("commandUid", JsonPrimitive(command.commandUid))
    put("campaignUid", JsonPrimitive(command.campaignUid))
    put("actor", encodeActor(command.actor))
    put("commandKindUid", JsonPrimitive(command.commandKindUid))
    put("payload", payload)
    put("provenance", encodeProvenance(command.provenance))
    put("causationUid", command.causationUid?.let(::JsonPrimitive) ?: JsonNull)
    put("correlationUid", command.correlationUid?.let(::JsonPrimitive) ?: JsonNull)
    put("requestedEffectiveOrder", command.requestedEffectiveOrder?.let(::JsonPrimitive) ?: JsonNull)
    put("preconditions", buildJsonArray { command.preconditions.forEach { add(encodePrecondition(it)) } })
    put("extensions", buildJsonArray { command.extensions.forEach { add(encodeExtension(it)) } })
}

private fun validatePrecondition(p: CommandPrecondition): List<String> = when (p) {
    is ExpectedRecordVersion -> buildList {
        if (!validRef(p.target)) add("INVALID_PRECONDITION_REF")
        if (p.expectedVersion < 0) add("INVALID_EXPECTED_VERSION")
    }
    is ExpectedLifecycleState -> buildList {
        if (!validRef(p.target)) add("INVALID_PRECONDITION_REF")
        if (p.expectedStateUid.isBlank()) add("INVALID_EXPECTED_LIFECYCLE_STATE")
    }
}
private fun validateExtension(x: TypedCommandExtension): List<String> = buildList {
    if (x.extensionKindUid.isBlank()) add("INVALID_EXTENSION_KIND")
    if (x.schemaVersion <= 0) add("INVALID_EXTENSION_VERSION")
    if (x is NamespacedTextCommandExtension && x.value.isBlank()) add("INVALID_EXTENSION_VALUE")
}
private fun validRef(r: DomainRef) = r.kindUid.isNotBlank() && r.uid.isNotBlank()
private fun nonblank(value: String, code: String) = if (value.isBlank()) listOf(code) else emptyList()
private fun refErrors(ref: DomainRef?, code: String): List<String> = if (ref != null && !validRef(ref)) listOf(code) else emptyList()
private fun refsErrors(refs: List<DomainRef>, code: String): List<String> = if (refs.any { !validRef(it) }) listOf(code) else emptyList()

private fun encodeActor(x: CommandActorRef) = buildJsonObject { put("actorKindUid", JsonPrimitive(x.actorKindUid)); put("actorUid", JsonPrimitive(x.actorUid)) }
private fun decodeActor(x: JsonObject) = CommandActorRef(x.reqString("actorKindUid"), x.reqString("actorUid"))
private fun encodeRef(x: DomainRef) = buildJsonObject { put("kindUid", JsonPrimitive(x.kindUid)); put("uid", JsonPrimitive(x.uid)) }
private fun decodeRef(x: JsonObject) = DomainRef(x.reqString("kindUid"), x.reqString("uid"))
private fun encodeProvenance(x: CommandProvenance) = buildJsonObject {
    put("sourceKindUid", JsonPrimitive(x.sourceKindUid)); put("sourceUid", x.sourceUid?.let(::JsonPrimitive) ?: JsonNull); put("detail", x.detail?.let(::JsonPrimitive) ?: JsonNull)
}
private fun decodeProvenance(x: JsonObject) = CommandProvenance(x.reqString("sourceKindUid"), x.optString("sourceUid"), x.optString("detail"))
private fun encodePrecondition(x: CommandPrecondition) = when (x) {
    is ExpectedRecordVersion -> buildJsonObject { put("kind", JsonPrimitive("EXPECTED_RECORD_VERSION")); put("target", encodeRef(x.target)); put("expectedVersion", JsonPrimitive(x.expectedVersion)) }
    is ExpectedLifecycleState -> buildJsonObject { put("kind", JsonPrimitive("EXPECTED_LIFECYCLE_STATE")); put("target", encodeRef(x.target)); put("expectedStateUid", JsonPrimitive(x.expectedStateUid)) }
}
private fun decodePrecondition(x: JsonObject): CommandPrecondition = when (x.reqString("kind")) {
    "EXPECTED_RECORD_VERSION" -> ExpectedRecordVersion(decodeRef(x.reqObject("target")), x.reqLong("expectedVersion"))
    "EXPECTED_LIFECYCLE_STATE" -> ExpectedLifecycleState(decodeRef(x.reqObject("target")), x.reqString("expectedStateUid"))
    else -> throw PlayerCommandStructuralException("UNKNOWN_PRECONDITION_KIND")
}
private fun encodeExtension(x: TypedCommandExtension) = when (x) {
    is NamespacedTextCommandExtension -> buildJsonObject {
        put("kind", JsonPrimitive("NAMESPACED_TEXT")); put("extensionKindUid", JsonPrimitive(x.extensionKindUid)); put("schemaVersion", JsonPrimitive(x.schemaVersion)); put("value", JsonPrimitive(x.value))
    }
}
private fun decodeExtension(x: JsonObject): TypedCommandExtension = when (x.reqString("kind")) {
    "NAMESPACED_TEXT" -> NamespacedTextCommandExtension(x.reqString("extensionKindUid"), x.reqInt("schemaVersion"), x.reqString("value"))
    else -> throw PlayerCommandStructuralException("UNKNOWN_EXTENSION_KIND")
}

private fun coreCodecs(): Map<String, TypedCommandCodec<out PlayerCommandPayload>> = linkedMapOf(
    PlayerCommandKinds.TRAIN to codec(TrainCommandPayload::class,
        encode = { obj("focus" to encodeRef(it.focus), "effortUnits" to j(it.effortUnits), "methodUid" to jn(it.methodUid)) },
        decode = { TrainCommandPayload(decodeRef(it.reqObject("focus")), it.reqLong("effortUnits"), it.optString("methodUid")) },
        validate = { refErrors(it.focus, "INVALID_FOCUS_REF") + if (it.effortUnits <= 0) listOf("INVALID_EFFORT_UNITS") else emptyList() }),
    PlayerCommandKinds.USE_RESOURCE to codec(UseResourceActionCommandPayload::class,
        { obj("resource" to encodeRef(it.resource), "requestedAmount" to j(it.requestedAmount), "methodUid" to jn(it.methodUid)) },
        { UseResourceActionCommandPayload(decodeRef(it.reqObject("resource")), it.reqLong("requestedAmount"), it.optString("methodUid")) },
        { refErrors(it.resource, "INVALID_RESOURCE_REF") + if (it.requestedAmount <= 0) listOf("INVALID_REQUESTED_AMOUNT") else emptyList() }),
    PlayerCommandKinds.RECOVER to codec(RecoverCommandPayload::class,
        { obj("resource" to (it.resource?.let(::encodeRef) ?: JsonNull), "effortUnits" to (it.effortUnits?.let(::j) ?: JsonNull)) },
        { RecoverCommandPayload(it.optObject("resource")?.let(::decodeRef), it.optLong("effortUnits")) },
        { refErrors(it.resource, "INVALID_RESOURCE_REF") + if (it.effortUnits != null && it.effortUnits <= 0) listOf("INVALID_EFFORT_UNITS") else emptyList() }),
    PlayerCommandKinds.LEARN_SKILL to codec(LearnSkillCommandPayload::class,
        { obj("skillUid" to j(it.skillUid), "methodUid" to jn(it.methodUid)) }, { LearnSkillCommandPayload(it.reqString("skillUid"), it.optString("methodUid")) }, { nonblank(it.skillUid,"INVALID_SKILL_UID") }),
    PlayerCommandKinds.PRACTICE_SKILL to codec(PracticeSkillCommandPayload::class,
        { obj("skillUid" to j(it.skillUid), "effortUnits" to j(it.effortUnits), "methodUid" to jn(it.methodUid)) }, { PracticeSkillCommandPayload(it.reqString("skillUid"),it.reqLong("effortUnits"),it.optString("methodUid")) }, { nonblank(it.skillUid,"INVALID_SKILL_UID") + if(it.effortUnits<=0) listOf("INVALID_EFFORT_UNITS") else emptyList() }),
    PlayerCommandKinds.LEARN_TECHNIQUE to codec(LearnTechniqueCommandPayload::class,
        { obj("techniqueUid" to j(it.techniqueUid), "methodUid" to jn(it.methodUid)) }, { LearnTechniqueCommandPayload(it.reqString("techniqueUid"),it.optString("methodUid")) }, { nonblank(it.techniqueUid,"INVALID_TECHNIQUE_UID") }),
    PlayerCommandKinds.USE_TECHNIQUE to codec(UseTechniqueCommandPayload::class,
        { obj("techniqueUid" to j(it.techniqueUid), "target" to (it.target?.let(::encodeRef) ?: JsonNull)) }, { UseTechniqueCommandPayload(it.reqString("techniqueUid"),it.optObject("target")?.let(::decodeRef)) }, { nonblank(it.techniqueUid,"INVALID_TECHNIQUE_UID") + refErrors(it.target,"INVALID_TARGET_REF") }),
    PlayerCommandKinds.ACQUIRE_ITEM to codec(AcquireItemCommandPayload::class,
        { obj("itemDefinitionUid" to j(it.itemDefinitionUid),"requestedQuantity" to j(it.requestedQuantity),"sourceRef" to (it.sourceRef?.let(::encodeRef)?:JsonNull)) }, { AcquireItemCommandPayload(it.reqString("itemDefinitionUid"),it.reqLong("requestedQuantity"),it.optObject("sourceRef")?.let(::decodeRef)) }, { nonblank(it.itemDefinitionUid,"INVALID_ITEM_DEFINITION_UID") + if(it.requestedQuantity<=0) listOf("INVALID_QUANTITY") else emptyList() + refErrors(it.sourceRef,"INVALID_SOURCE_REF") }),
    PlayerCommandKinds.TRANSFER_ITEM to codec(TransferItemCommandPayload::class,
        { obj("item" to encodeRef(it.item),"toParty" to encodeRef(it.toParty),"requestedQuantity" to (it.requestedQuantity?.let(::j)?:JsonNull)) }, { TransferItemCommandPayload(decodeRef(it.reqObject("item")),decodeRef(it.reqObject("toParty")),it.optLong("requestedQuantity")) }, { refErrors(it.item,"INVALID_ITEM_REF") + refErrors(it.toParty,"INVALID_PARTY_REF") + if(it.requestedQuantity!=null&&it.requestedQuantity<=0) listOf("INVALID_QUANTITY") else emptyList() }),
    PlayerCommandKinds.CONSUME_ITEM to codec(ConsumeItemCommandPayload::class,
        { obj("item" to encodeRef(it.item),"requestedQuantity" to j(it.requestedQuantity)) }, { ConsumeItemCommandPayload(decodeRef(it.reqObject("item")),it.reqLong("requestedQuantity")) }, { refErrors(it.item,"INVALID_ITEM_REF") + if(it.requestedQuantity<=0) listOf("INVALID_QUANTITY") else emptyList() }),
    PlayerCommandKinds.EQUIP_ITEM to codec(EquipItemCommandPayload::class,
        { obj("item" to encodeRef(it.item),"requestedSlotUid" to j(it.requestedSlotUid)) }, { EquipItemCommandPayload(decodeRef(it.reqObject("item")),it.reqString("requestedSlotUid")) }, { refErrors(it.item,"INVALID_ITEM_REF") + nonblank(it.requestedSlotUid,"INVALID_SLOT_UID") }),
    PlayerCommandKinds.UNEQUIP_SLOT to codec(UnequipSlotCommandPayload::class,
        { obj("requestedSlotUid" to j(it.requestedSlotUid)) }, { UnequipSlotCommandPayload(it.reqString("requestedSlotUid")) }, { nonblank(it.requestedSlotUid,"INVALID_SLOT_UID") }),
    PlayerCommandKinds.TRANSFER_OWNERSHIP to codec(TransferOwnershipCommandPayload::class,
        { obj("subject" to encodeRef(it.subject),"toParty" to encodeRef(it.toParty),"requestedShareBasisPoints" to (it.requestedShareBasisPoints?.let(::j)?:JsonNull)) }, { TransferOwnershipCommandPayload(decodeRef(it.reqObject("subject")),decodeRef(it.reqObject("toParty")),it.optLong("requestedShareBasisPoints")) }, { refErrors(it.subject,"INVALID_SUBJECT_REF") + refErrors(it.toParty,"INVALID_PARTY_REF") + if(it.requestedShareBasisPoints!=null&&it.requestedShareBasisPoints !in 1..10000) listOf("INVALID_SHARE_BPS") else emptyList() }),
    PlayerCommandKinds.TRANSFER_FUNDS to codec(TransferFundsCommandPayload::class,
        { obj("fromAccountUid" to j(it.fromAccountUid),"toAccountUid" to j(it.toAccountUid),"amountMinor" to j(it.amountMinor),"currencyUid" to j(it.currencyUid)) }, { TransferFundsCommandPayload(it.reqString("fromAccountUid"),it.reqString("toAccountUid"),it.reqLong("amountMinor"),it.reqString("currencyUid")) }, { nonblank(it.fromAccountUid,"INVALID_FROM_ACCOUNT_UID")+nonblank(it.toAccountUid,"INVALID_TO_ACCOUNT_UID")+nonblank(it.currencyUid,"INVALID_CURRENCY_UID")+if(it.amountMinor<=0) listOf("INVALID_AMOUNT") else emptyList() }),
    PlayerCommandKinds.ACQUIRE_ASSET to codec(AcquireAssetCommandPayload::class,
        { obj("assetKindUid" to j(it.assetKindUid),"requestedTermsRef" to (it.requestedTermsRef?.let(::encodeRef)?:JsonNull)) }, { AcquireAssetCommandPayload(it.reqString("assetKindUid"),it.optObject("requestedTermsRef")?.let(::decodeRef)) }, { nonblank(it.assetKindUid,"INVALID_ASSET_KIND_UID")+refErrors(it.requestedTermsRef,"INVALID_TERMS_REF") }),
    PlayerCommandKinds.ENTER_OBLIGATION to codec(EnterObligationCommandPayload::class,
        { obj("obligationTypeUid" to j(it.obligationTypeUid),"counterparty" to encodeRef(it.counterparty),"principalMinor" to (it.principalMinor?.let(::j)?:JsonNull),"currencyUid" to jn(it.currencyUid)) }, { EnterObligationCommandPayload(it.reqString("obligationTypeUid"),decodeRef(it.reqObject("counterparty")),it.optLong("principalMinor"),it.optString("currencyUid")) }, { nonblank(it.obligationTypeUid,"INVALID_OBLIGATION_TYPE_UID")+refErrors(it.counterparty,"INVALID_COUNTERPARTY_REF")+if(it.principalMinor!=null&&it.principalMinor<=0) listOf("INVALID_PRINCIPAL") else emptyList()+if((it.principalMinor==null)!=(it.currencyUid==null)) listOf("PRINCIPAL_CURRENCY_PAIR_REQUIRED") else emptyList() }),
    PlayerCommandKinds.SETTLE_OBLIGATION to codec(SettleObligationCommandPayload::class,
        { obj("obligationUid" to j(it.obligationUid),"requestedAmountMinor" to (it.requestedAmountMinor?.let(::j)?:JsonNull)) }, { SettleObligationCommandPayload(it.reqString("obligationUid"),it.optLong("requestedAmountMinor")) }, { nonblank(it.obligationUid,"INVALID_OBLIGATION_UID")+if(it.requestedAmountMinor!=null&&it.requestedAmountMinor<=0) listOf("INVALID_AMOUNT") else emptyList() }),
    PlayerCommandKinds.START_PROJECT to codec(StartProjectCommandPayload::class,
        { obj("projectTypeUid" to j(it.projectTypeUid),"titleIntent" to j(it.titleIntent),"objectiveIntent" to j(it.objectiveIntent),"beneficiaryRef" to (it.beneficiaryRef?.let(::encodeRef)?:JsonNull),"targetDomainUid" to j(it.targetDomainUid),"targetRef" to (it.targetRef?.let(::encodeRef)?:JsonNull),"intendedOutputKindUid" to jn(it.intendedOutputKindUid),"requestedProgressCapUnits" to (it.requestedProgressCapUnits?.let(::j)?:JsonNull)) }, { StartProjectCommandPayload(it.reqString("projectTypeUid"),it.reqString("titleIntent"),it.reqString("objectiveIntent"),it.optObject("beneficiaryRef")?.let(::decodeRef),it.reqString("targetDomainUid"),it.optObject("targetRef")?.let(::decodeRef),it.optString("intendedOutputKindUid"),it.optLong("requestedProgressCapUnits")) }, { nonblank(it.projectTypeUid,"INVALID_PROJECT_TYPE_UID")+nonblank(it.titleIntent,"INVALID_TITLE_INTENT")+nonblank(it.objectiveIntent,"INVALID_OBJECTIVE_INTENT")+nonblank(it.targetDomainUid,"INVALID_TARGET_DOMAIN_UID")+refErrors(it.beneficiaryRef,"INVALID_BENEFICIARY_REF")+refErrors(it.targetRef,"INVALID_TARGET_REF")+if(it.requestedProgressCapUnits!=null&&it.requestedProgressCapUnits<=0) listOf("INVALID_PROGRESS_CAP_INTENT") else emptyList() }),
    PlayerCommandKinds.RECORD_PROJECT_WORK to codec(RecordProjectWorkCommandPayload::class,
        { obj("projectUid" to j(it.projectUid),"workKindUid" to j(it.workKindUid),"effortUnitsIntent" to (it.effortUnitsIntent?.let(::j)?:JsonNull),"methodUid" to jn(it.methodUid),"evidenceRefs" to refsJson(it.evidenceRefs),"requestedResourceUse" to refsJson(it.requestedResourceUse)) }, { RecordProjectWorkCommandPayload(it.reqString("projectUid"),it.reqString("workKindUid"),it.optLong("effortUnitsIntent"),it.optString("methodUid"),it.reqArray("evidenceRefs").map{e->decodeRef(e.jsonObject)},it.reqArray("requestedResourceUse").map{e->decodeRef(e.jsonObject)}) }, { nonblank(it.projectUid,"INVALID_PROJECT_UID")+nonblank(it.workKindUid,"INVALID_WORK_KIND_UID")+if(it.effortUnitsIntent!=null&&it.effortUnitsIntent<=0) listOf("INVALID_EFFORT_UNITS") else emptyList()+refsErrors(it.evidenceRefs,"INVALID_EVIDENCE_REF")+refsErrors(it.requestedResourceUse,"INVALID_RESOURCE_REF") }),
    PlayerCommandKinds.SATISFY_PROJECT_REQUIREMENT to codec(SatisfyProjectRequirementCommandPayload::class,
        { obj("projectUid" to j(it.projectUid),"requirementUid" to j(it.requirementUid),"evidenceRefs" to refsJson(it.evidenceRefs)) }, { SatisfyProjectRequirementCommandPayload(it.reqString("projectUid"),it.reqString("requirementUid"),it.reqArray("evidenceRefs").map{e->decodeRef(e.jsonObject)}) }, { nonblank(it.projectUid,"INVALID_PROJECT_UID")+nonblank(it.requirementUid,"INVALID_REQUIREMENT_UID")+refsErrors(it.evidenceRefs,"INVALID_EVIDENCE_REF") }),
    PlayerCommandKinds.ACHIEVE_PROJECT_MILESTONE to codec(AchieveProjectMilestoneCommandPayload::class,
        { obj("projectUid" to j(it.projectUid),"milestoneUid" to j(it.milestoneUid),"evidenceRefs" to refsJson(it.evidenceRefs),"sourceWorkRef" to (it.sourceWorkRef?.let(::encodeRef)?:JsonNull)) }, { AchieveProjectMilestoneCommandPayload(it.reqString("projectUid"),it.reqString("milestoneUid"),it.reqArray("evidenceRefs").map{e->decodeRef(e.jsonObject)},it.optObject("sourceWorkRef")?.let(::decodeRef)) }, { nonblank(it.projectUid,"INVALID_PROJECT_UID")+nonblank(it.milestoneUid,"INVALID_MILESTONE_UID")+refsErrors(it.evidenceRefs,"INVALID_EVIDENCE_REF")+refErrors(it.sourceWorkRef,"INVALID_SOURCE_WORK_REF") }),
    PlayerCommandKinds.CHANGE_PROJECT_LIFECYCLE to codec(ChangeProjectLifecycleCommandPayload::class,
        { obj("projectUid" to j(it.projectUid),"requestedStatusUid" to j(it.requestedStatusUid),"successorProjectUid" to jn(it.successorProjectUid)) }, { ChangeProjectLifecycleCommandPayload(it.reqString("projectUid"),it.reqString("requestedStatusUid"),it.optString("successorProjectUid")) }, { nonblank(it.projectUid,"INVALID_PROJECT_UID")+nonblank(it.requestedStatusUid,"INVALID_REQUESTED_STATUS") }),
    PlayerCommandKinds.COMPLETE_PROJECT to codec(CompleteProjectCommandPayload::class,
        { obj("projectUid" to j(it.projectUid),"completionEvidenceRefs" to refsJson(it.completionEvidenceRefs)) }, { CompleteProjectCommandPayload(it.reqString("projectUid"),it.reqArray("completionEvidenceRefs").map{e->decodeRef(e.jsonObject)}) }, { nonblank(it.projectUid,"INVALID_PROJECT_UID")+refsErrors(it.completionEvidenceRefs,"INVALID_EVIDENCE_REF") }),
    PlayerCommandKinds.CANCEL_PROJECT to codec(CancelProjectCommandPayload::class,
        { obj("projectUid" to j(it.projectUid),"reasonUid" to jn(it.reasonUid),"reasonText" to jn(it.reasonText)) }, { CancelProjectCommandPayload(it.reqString("projectUid"),it.optString("reasonUid"),it.optString("reasonText")) }, { nonblank(it.projectUid,"INVALID_PROJECT_UID") })
)

private fun <P : PlayerCommandPayload> codec(
    type: KClass<P>,
    encode: (P) -> JsonObject,
    decode: (JsonObject) -> P,
    validate: (P) -> List<String>
): TypedCommandCodec<P> = object : TypedCommandCodec<P>(type) {
    override fun encode(payload: P) = encode(payload)
    override fun decode(obj: JsonObject) = decode(obj)
    override fun validate(payload: P) = validate(payload)
}
private fun j(v: String) = JsonPrimitive(v)
private fun j(v: Long) = JsonPrimitive(v)
private fun jn(v: String?): JsonElement = v?.let(::JsonPrimitive) ?: JsonNull
private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*pairs))
private fun refsJson(refs: List<DomainRef>) = JsonArray(refs.map(::encodeRef))
private fun JsonObject.reqString(k: String): String = this[k]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content ?: throw PlayerCommandStructuralException("MISSING_$k")
private fun JsonObject.reqInt(k: String): Int = try { this[k]?.jsonPrimitive?.int ?: throw IllegalStateException() } catch (_: Throwable) { throw PlayerCommandStructuralException("MISSING_$k") }
private fun JsonObject.reqLong(k: String): Long = try { this[k]?.jsonPrimitive?.long ?: throw IllegalStateException() } catch (_: Throwable) { throw PlayerCommandStructuralException("MISSING_$k") }
private fun JsonObject.optString(k: String): String? = this[k]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
private fun JsonObject.optLong(k: String): Long? = this[k]?.takeUnless { it is JsonNull }?.jsonPrimitive?.long
private fun JsonObject.reqObject(k: String): JsonObject = this[k]?.takeUnless { it is JsonNull }?.jsonObject ?: throw PlayerCommandStructuralException("MISSING_$k")
private fun JsonObject.optObject(k: String): JsonObject? = this[k]?.takeUnless { it is JsonNull }?.jsonObject
private fun JsonObject.reqArray(k: String): JsonArray = this[k]?.takeUnless { it is JsonNull }?.jsonArray ?: throw PlayerCommandStructuralException("MISSING_$k")
