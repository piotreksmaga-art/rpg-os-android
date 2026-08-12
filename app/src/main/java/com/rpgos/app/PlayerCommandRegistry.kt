package com.rpgos.app

import java.security.MessageDigest
import kotlin.reflect.KClass
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

private const val NAMESPACED_TEXT_EXTENSION_SCHEMA_VERSION = 1
private const val UNKNOWN_COMMAND_FIELD = "UNKNOWN_COMMAND_FIELD"
private const val INVALID_JSON_STRING_TYPE = "INVALID_JSON_STRING_TYPE"
private const val INVALID_JSON_NUMERIC_TYPE = "INVALID_JSON_NUMERIC_TYPE"
private const val INVALID_JSON_NUMERIC_VALUE = "INVALID_JSON_NUMERIC_VALUE"

abstract class TypedCommandCodec<P : PlayerCommandPayload>(
    val payloadType: KClass<P>,
    val allowedKeys: Set<String> = corePayloadAllowedKeys(payloadType)
) {
    abstract fun encode(payload: P): JsonObject

    fun decode(obj: JsonObject): P = decodeKnownFields(obj.requireOnlyKeys(allowedKeys))

    protected abstract fun decodeKnownFields(obj: JsonObject): P

    open fun validate(payload: P): List<String> = emptyList()

    fun encodeUntyped(payload: PlayerCommandPayload): JsonObject {
        if (!payloadType.isInstance(payload)) throw PlayerCommandStructuralException("COMMAND_PAYLOAD_TYPE_MISMATCH")
        @Suppress("UNCHECKED_CAST")
        return encode(payload as P)
    }

    fun validateUntyped(payload: PlayerCommandPayload): List<String> {
        if (!payloadType.isInstance(payload)) return listOf("COMMAND_PAYLOAD_TYPE_MISMATCH")
        @Suppress("UNCHECKED_CAST")
        return validate(payload as P)
    }
}

class PlayerCommandKindRegistry private constructor(
    private val codecs: Map<String, TypedCommandCodec<out PlayerCommandPayload>>
) {
    fun codec(kindUid: String): TypedCommandCodec<out PlayerCommandPayload> =
        codecs[kindUid] ?: throw PlayerCommandStructuralException("UNKNOWN_COMMAND_KIND")

    fun validate(command: PlayerCommand<out PlayerCommandPayload>) {
        val errors = mutableListOf<String>()
        if (command.schemaVersion != PLAYER_COMMAND_SCHEMA_VERSION) errors += "UNSUPPORTED_COMMAND_SCHEMA_VERSION"
        if (command.commandUid.isBlank()) errors += "EMPTY_COMMAND_UID"
        if (command.campaignUid.isBlank()) errors += "EMPTY_CAMPAIGN_UID"
        if (command.actor.actorKindUid.isBlank() || command.actor.actorUid.isBlank()) errors += "INVALID_ACTOR_REF"
        if (command.commandKindUid.isBlank()) errors += "EMPTY_COMMAND_KIND_UID"
        if (command.provenance.sourceKindUid.isBlank()) errors += "INVALID_PROVENANCE"
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
        return canonicalJson(command, codec(command.commandKindUid).encodeUntyped(command.payload)).toString()
    }

    fun decode(serialized: String): PlayerCommand<out PlayerCommandPayload> {
        rejectDuplicateJsonObjectKeys(serialized)
        val root = try {
            Json.parseToJsonElement(serialized).jsonObject
        } catch (e: PlayerCommandStructuralException) {
            throw e
        } catch (_: Throwable) {
            throw PlayerCommandStructuralException("INVALID_COMMAND_SERIALIZATION")
        }
        root.requireOnlyKeys(
            setOf(
                "schemaVersion", "commandUid", "campaignUid", "actor", "commandKindUid", "payload",
                "provenance", "causationUid", "correlationUid", "requestedEffectiveOrder", "preconditions", "extensions"
            )
        )
        val schema = root.reqInt("schemaVersion")
        if (schema != PLAYER_COMMAND_SCHEMA_VERSION) throw PlayerCommandStructuralException("UNSUPPORTED_COMMAND_SCHEMA_VERSION")
        val kind = root.reqString("commandKindUid")
        val commandCodec = codec(kind)
        val payloadObject = root.reqObject("payload").requireOnlyKeys(commandCodec.allowedKeys)
        val payload = try {
            commandCodec.decode(payloadObject)
        } catch (e: PlayerCommandStructuralException) {
            throw e
        } catch (_: Throwable) {
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

    fun fingerprint(command: PlayerCommand<out PlayerCommandPayload>): String =
        MessageDigest.getInstance("SHA-256")
            .digest(encode(command).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        fun core(): PlayerCommandKindRegistry = PlayerCommandKindRegistry(coreCommandCodecs())
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
        if (left.campaignUid != right.campaignUid || left.commandUid != right.commandUid) {
            return CommandIdentityRelation.DISTINCT_COMMAND
        }
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
    if (x is NamespacedTextCommandExtension) {
        if (x.schemaVersion != NAMESPACED_TEXT_EXTENSION_SCHEMA_VERSION) add("UNSUPPORTED_EXTENSION_SCHEMA_VERSION")
        if (x.value.isBlank()) add("INVALID_EXTENSION_VALUE")
    }
}

internal fun validRef(r: DomainRef): Boolean = r.kindUid.isNotBlank() && r.uid.isNotBlank()
internal fun nonblank(value: String, code: String): List<String> = if (value.isBlank()) listOf(code) else emptyList()
internal fun refErrors(ref: DomainRef?, code: String): List<String> = if (ref != null && !validRef(ref)) listOf(code) else emptyList()
internal fun refsErrors(refs: List<DomainRef>, code: String): List<String> = if (refs.any { !validRef(it) }) listOf(code) else emptyList()
internal fun errorIf(condition: Boolean, code: String): List<String> = if (condition) listOf(code) else emptyList()

private fun encodeActor(x: CommandActorRef) = jobj("actorKindUid" to j(x.actorKindUid), "actorUid" to j(x.actorUid))
private fun decodeActor(x: JsonObject): CommandActorRef {
    x.requireOnlyKeys(setOf("actorKindUid", "actorUid"))
    return CommandActorRef(x.reqString("actorKindUid"), x.reqString("actorUid"))
}

internal fun encodeRef(x: DomainRef) = jobj("kindUid" to j(x.kindUid), "uid" to j(x.uid))
internal fun decodeRef(x: JsonObject): DomainRef {
    x.requireOnlyKeys(setOf("kindUid", "uid"))
    return DomainRef(x.reqString("kindUid"), x.reqString("uid"))
}

private fun encodeProvenance(x: CommandProvenance) = jobj(
    "sourceKindUid" to j(x.sourceKindUid),
    "sourceUid" to jn(x.sourceUid),
    "detail" to jn(x.detail)
)
private fun decodeProvenance(x: JsonObject): CommandProvenance {
    x.requireOnlyKeys(setOf("sourceKindUid", "sourceUid", "detail"))
    return CommandProvenance(x.reqString("sourceKindUid"), x.optString("sourceUid"), x.optString("detail"))
}

private fun encodePrecondition(x: CommandPrecondition): JsonObject = when (x) {
    is ExpectedRecordVersion -> jobj("kind" to j("EXPECTED_RECORD_VERSION"), "target" to encodeRef(x.target), "expectedVersion" to j(x.expectedVersion))
    is ExpectedLifecycleState -> jobj("kind" to j("EXPECTED_LIFECYCLE_STATE"), "target" to encodeRef(x.target), "expectedStateUid" to j(x.expectedStateUid))
}
private fun decodePrecondition(x: JsonObject): CommandPrecondition = when (x.reqString("kind")) {
    "EXPECTED_RECORD_VERSION" -> {
        x.requireOnlyKeys(setOf("kind", "target", "expectedVersion"))
        ExpectedRecordVersion(decodeRef(x.reqObject("target")), x.reqLong("expectedVersion"))
    }
    "EXPECTED_LIFECYCLE_STATE" -> {
        x.requireOnlyKeys(setOf("kind", "target", "expectedStateUid"))
        ExpectedLifecycleState(decodeRef(x.reqObject("target")), x.reqString("expectedStateUid"))
    }
    else -> throw PlayerCommandStructuralException("UNKNOWN_PRECONDITION_KIND")
}

private fun encodeExtension(x: TypedCommandExtension): JsonObject = when (x) {
    is NamespacedTextCommandExtension -> jobj(
        "kind" to j("NAMESPACED_TEXT"), "extensionKindUid" to j(x.extensionKindUid),
        "schemaVersion" to JsonPrimitive(x.schemaVersion), "value" to j(x.value)
    )
}
private fun decodeExtension(x: JsonObject): TypedCommandExtension = when (x.reqString("kind")) {
    "NAMESPACED_TEXT" -> {
        x.requireOnlyKeys(setOf("kind", "extensionKindUid", "schemaVersion", "value"))
        val schemaVersion = x.reqInt("schemaVersion")
        if (schemaVersion != NAMESPACED_TEXT_EXTENSION_SCHEMA_VERSION) {
            throw PlayerCommandStructuralException("UNSUPPORTED_EXTENSION_SCHEMA_VERSION")
        }
        NamespacedTextCommandExtension(x.reqString("extensionKindUid"), schemaVersion, x.reqString("value"))
    }
    else -> throw PlayerCommandStructuralException("UNKNOWN_EXTENSION_KIND")
}

internal fun j(v: String): JsonPrimitive = JsonPrimitive(v)
internal fun j(v: Long): JsonPrimitive = JsonPrimitive(v)
internal fun jn(v: String?): JsonElement = v?.let(::JsonPrimitive) ?: JsonNull
internal fun jobj(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*pairs))
internal fun refsJson(refs: List<DomainRef>): JsonArray = JsonArray(refs.map(::encodeRef))
internal fun JsonObject.requireOnlyKeys(allowedKeys: Set<String>): JsonObject {
    if (keys.any { it !in allowedKeys }) throw PlayerCommandStructuralException(UNKNOWN_COMMAND_FIELD)
    return this
}
internal fun JsonObject.reqString(k: String): String {
    val element = this[k] ?: throw PlayerCommandStructuralException("MISSING_$k")
    if (element is JsonNull) throw PlayerCommandStructuralException("MISSING_$k")
    val primitive = element as? JsonPrimitive ?: throw PlayerCommandStructuralException(INVALID_JSON_STRING_TYPE)
    if (!primitive.isString) throw PlayerCommandStructuralException(INVALID_JSON_STRING_TYPE)
    return primitive.content
}
internal fun JsonObject.reqInt(k: String): Int {
    val element = this[k] ?: throw PlayerCommandStructuralException("MISSING_$k")
    if (element is JsonNull) throw PlayerCommandStructuralException("MISSING_$k")
    val primitive = element as? JsonPrimitive ?: throw PlayerCommandStructuralException(INVALID_JSON_NUMERIC_TYPE)
    if (primitive.isString || primitive.content == "true" || primitive.content == "false") {
        throw PlayerCommandStructuralException(INVALID_JSON_NUMERIC_TYPE)
    }
    return try {
        primitive.int
    } catch (_: Throwable) {
        throw PlayerCommandStructuralException(INVALID_JSON_NUMERIC_VALUE)
    }
}
internal fun JsonObject.reqLong(k: String): Long {
    val element = this[k] ?: throw PlayerCommandStructuralException("MISSING_$k")
    if (element is JsonNull) throw PlayerCommandStructuralException("MISSING_$k")
    val primitive = element as? JsonPrimitive ?: throw PlayerCommandStructuralException(INVALID_JSON_NUMERIC_TYPE)
    if (primitive.isString || primitive.content == "true" || primitive.content == "false") {
        throw PlayerCommandStructuralException(INVALID_JSON_NUMERIC_TYPE)
    }
    return try {
        primitive.long
    } catch (_: Throwable) {
        throw PlayerCommandStructuralException(INVALID_JSON_NUMERIC_VALUE)
    }
}
internal fun JsonObject.optString(k: String): String? {
    val element = this[k] ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive ?: throw PlayerCommandStructuralException(INVALID_JSON_STRING_TYPE)
    if (!primitive.isString) throw PlayerCommandStructuralException(INVALID_JSON_STRING_TYPE)
    return primitive.content
}
internal fun JsonObject.optLong(k: String): Long? {
    val element = this[k] ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive ?: throw PlayerCommandStructuralException(INVALID_JSON_NUMERIC_TYPE)
    if (primitive.isString || primitive.content == "true" || primitive.content == "false") {
        throw PlayerCommandStructuralException(INVALID_JSON_NUMERIC_TYPE)
    }
    return try {
        primitive.long
    } catch (_: Throwable) {
        throw PlayerCommandStructuralException(INVALID_JSON_NUMERIC_VALUE)
    }
}
internal fun JsonObject.reqObject(k: String): JsonObject = this[k]?.takeUnless { it is JsonNull }?.jsonObject ?: throw PlayerCommandStructuralException("MISSING_$k")
internal fun JsonObject.optObject(k: String): JsonObject? = this[k]?.takeUnless { it is JsonNull }?.jsonObject
internal fun JsonObject.reqArray(k: String): JsonArray = this[k]?.takeUnless { it is JsonNull }?.jsonArray ?: throw PlayerCommandStructuralException("MISSING_$k")

private fun corePayloadAllowedKeys(type: KClass<*>): Set<String> = when (type) {
    TrainCommandPayload::class -> setOf("focus", "effortUnits", "methodUid")
    UseResourceActionCommandPayload::class -> setOf("resource", "requestedAmount", "methodUid")
    RecoverCommandPayload::class -> setOf("resource", "effortUnits")
    LearnSkillCommandPayload::class -> setOf("skillUid", "methodUid")
    PracticeSkillCommandPayload::class -> setOf("skillUid", "effortUnits", "methodUid")
    LearnTechniqueCommandPayload::class -> setOf("techniqueUid", "methodUid")
    UseTechniqueCommandPayload::class -> setOf("techniqueUid", "target")
    AcquireItemCommandPayload::class -> setOf("itemDefinitionUid", "requestedQuantity", "sourceRef")
    TransferItemCommandPayload::class -> setOf("item", "toParty", "requestedQuantity")
    ConsumeItemCommandPayload::class -> setOf("item", "requestedQuantity")
    EquipItemCommandPayload::class -> setOf("item", "requestedSlotUid")
    UnequipSlotCommandPayload::class -> setOf("requestedSlotUid")
    TransferOwnershipCommandPayload::class -> setOf("subject", "toParty", "requestedShareBasisPoints")
    TransferFundsCommandPayload::class -> setOf("fromAccountUid", "toAccountUid", "amountMinor", "currencyUid")
    AcquireAssetCommandPayload::class -> setOf("assetKindUid", "requestedTermsRef")
    EnterObligationCommandPayload::class -> setOf("obligationTypeUid", "counterparty", "principalMinor", "currencyUid")
    SettleObligationCommandPayload::class -> setOf("obligationUid", "requestedAmountMinor")
    StartProjectCommandPayload::class -> setOf(
        "projectTypeUid", "titleIntent", "objectiveIntent", "beneficiaryRef", "targetDomainUid", "targetRef",
        "intendedOutputKindUid", "requestedProgressCapUnits"
    )
    RecordProjectWorkCommandPayload::class -> setOf(
        "projectUid", "workKindUid", "effortUnitsIntent", "methodUid", "evidenceRefs", "requestedResourceUse"
    )
    SatisfyProjectRequirementCommandPayload::class -> setOf("projectUid", "requirementUid", "evidenceRefs")
    AchieveProjectMilestoneCommandPayload::class -> setOf("projectUid", "milestoneUid", "evidenceRefs", "sourceWorkRef")
    ChangeProjectLifecycleCommandPayload::class -> setOf("projectUid", "requestedStatusUid", "successorProjectUid")
    CompleteProjectCommandPayload::class -> setOf("projectUid", "completionEvidenceRefs")
    CancelProjectCommandPayload::class -> setOf("projectUid", "reasonUid", "reasonText")
    else -> emptySet()
}
