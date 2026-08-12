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

private const val PCS_UNKNOWN_FIELD = "UNKNOWN_CHANGESET_FIELD"
private const val PCS_INVALID_STRING_TYPE = "INVALID_CHANGESET_JSON_STRING_TYPE"
private const val PCS_INVALID_NUMERIC_TYPE = "INVALID_CHANGESET_JSON_NUMERIC_TYPE"
private const val PCS_INVALID_NUMERIC_VALUE = "INVALID_CHANGESET_JSON_NUMERIC_VALUE"
private const val PCS_DUPLICATE_JSON_OBJECT_KEY = "DUPLICATE_CHANGESET_JSON_OBJECT_KEY"

internal abstract class TypedPlayerChangeCodec<P : PlayerDomainChangePayload>(
    val payloadType: KClass<P>,
    val classification: ChangeIntentClassification,
    val allowedKeys: Set<String>
) {
    abstract fun encode(payload: P): JsonObject
    protected abstract fun decodeKnownFields(obj: JsonObject): P
    open fun validate(payload: P): List<String> = emptyList()
    open fun conflictKeys(payload: P): Set<String> = emptySet()

    fun decode(obj: JsonObject): P = decodeKnownFields(obj.pcsOnlyKeys(allowedKeys))

    fun encodeUntyped(payload: PlayerDomainChangePayload): JsonObject {
        if (!payloadType.isInstance(payload)) throw PlayerChangeSetStructuralException("CHANGE_PAYLOAD_TYPE_MISMATCH")
        @Suppress("UNCHECKED_CAST")
        return encode(payload as P)
    }

    fun validateUntyped(payload: PlayerDomainChangePayload): List<String> {
        if (!payloadType.isInstance(payload)) return listOf("CHANGE_PAYLOAD_TYPE_MISMATCH")
        @Suppress("UNCHECKED_CAST")
        return validate(payload as P)
    }

    fun conflictKeysUntyped(payload: PlayerDomainChangePayload): Set<String> {
        if (!payloadType.isInstance(payload)) return emptySet()
        @Suppress("UNCHECKED_CAST")
        return conflictKeys(payload as P)
    }
}

class TypedPlayerChangeRegistry private constructor(
    private val codecs: Map<String, TypedPlayerChangeCodec<out PlayerDomainChangePayload>>
) {
    internal fun codec(kindUid: String): TypedPlayerChangeCodec<out PlayerDomainChangePayload> =
        codecs[kindUid] ?: throw PlayerChangeSetStructuralException("UNKNOWN_CHANGE_KIND")

    fun classificationFor(kindUid: String): ChangeIntentClassification = codec(kindUid).classification

    fun validateChange(change: PlayerDomainChange) {
        if (change.changeUid.isBlank()) throw PlayerChangeSetStructuralException("EMPTY_CHANGE_UID")
        if (change.changeKindUid.isBlank()) throw PlayerChangeSetStructuralException("UNKNOWN_CHANGE_KIND")
        if (change.sourceRuleUid?.isBlank() == true) throw PlayerChangeSetStructuralException("INVALID_SOURCE_RULE_UID")
        val errors = codec(change.changeKindUid).validateUntyped(change.payload)
        if (errors.isNotEmpty()) throw PlayerChangeSetStructuralException(errors.first())
    }

    internal fun conflictKeys(change: PlayerDomainChange): Set<String> =
        codec(change.changeKindUid).conflictKeysUntyped(change.payload)

    companion object {
        fun core(): TypedPlayerChangeRegistry = TypedPlayerChangeRegistry(coreChangeCodecs())
    }
}

object PlayerChangeSetValidator {
    fun validate(
        changeSet: PlayerChangeSet,
        registry: TypedPlayerChangeRegistry = TypedPlayerChangeRegistry.core()
    ) {
        if (changeSet.schemaVersion != PLAYER_CHANGE_SET_SCHEMA_VERSION) fail("UNSUPPORTED_CHANGESET_SCHEMA_VERSION")
        if (changeSet.changeSetUid.isBlank()) fail("EMPTY_CHANGESET_UID")
        if (changeSet.campaignUid.isBlank()) fail("EMPTY_CAMPAIGN_UID")
        if (changeSet.sourceCommandUid.isBlank()) fail("EMPTY_SOURCE_COMMAND_UID")
        if (changeSet.actor.actorKindUid.isBlank() || changeSet.actor.actorUid.isBlank()) fail("INVALID_ACTOR_REF")
        if (changeSet.provenance.sourceCommandUid != changeSet.sourceCommandUid) fail("INVALID_PROVENANCE_COMMAND_LINK")
        if (changeSet.provenance.resolverKindUid.isBlank() || changeSet.provenance.resolverVersion.isBlank()) fail("INVALID_PROVENANCE")
        if (changeSet.provenance.worldRuleProviderUid?.isBlank() == true ||
            changeSet.provenance.mechanicsVersion?.isBlank() == true ||
            changeSet.provenance.sourceEventUid?.isBlank() == true) fail("INVALID_PROVENANCE")
        if (changeSet.causationUid?.isBlank() == true) fail("INVALID_CAUSATION_UID")
        if (changeSet.correlationUid?.isBlank() == true) fail("INVALID_CORRELATION_UID")
        if (changeSet.changes.isEmpty() && changeSet.eventIntents.isEmpty() && changeSet.ledgerIntents.isEmpty()) fail("EMPTY_CHANGESET_PROPOSAL")

        val changesByUid = LinkedHashMap<String, PlayerDomainChange>()
        val semanticTargets = HashSet<String>()
        changeSet.changes.forEach { change ->
            registry.validateChange(change)
            if (changesByUid.put(change.changeUid, change) != null) fail("DUPLICATE_CHANGE_UID")
            registry.conflictKeys(change).forEach { key ->
                if (!semanticTargets.add(key)) fail("CONFLICTING_CHANGE_TARGET")
            }
        }

        changeSet.preconditions.forEach { precondition ->
            when (precondition) {
                is ChangeSetExpectedRecordVersion -> {
                    if (!validRef(precondition.target)) fail("INVALID_PRECONDITION_REF")
                    if (precondition.expectedVersion < 0L) fail("INVALID_EXPECTED_VERSION")
                }
                is ChangeSetExpectedLifecycleState -> {
                    if (!validRef(precondition.target)) fail("INVALID_PRECONDITION_REF")
                    if (precondition.expectedStateUid.isBlank()) fail("INVALID_EXPECTED_LIFECYCLE_STATE")
                }
            }
        }

        val eventIds = HashSet<String>()
        changeSet.eventIntents.forEach { intent ->
            if (intent.eventIntentUid.isBlank() || !eventIds.add(intent.eventIntentUid)) fail("INVALID_EVENT_INTENT")
            if (intent.eventKindUid != PlayerEventIntentKinds.DOMAIN_EFFECT) fail("UNKNOWN_EVENT_INTENT_KIND")
            if (intent.actorRef != null && !validRef(intent.actorRef)) fail("INVALID_EVENT_INTENT")
            if (intent.targetRefs.any { !validRef(it) }) fail("INVALID_EVENT_INTENT")
            if (intent.causalChangeUids.any { it.isBlank() || it !in changesByUid }) fail("INVALID_EVENT_INTENT")
            val payload = intent.payload as? DomainEffectEventIntentPayload ?: fail("EVENT_PAYLOAD_TYPE_MISMATCH")
            if (!validRef(payload.subject) || payload.effectKindUid.isBlank()) fail("INVALID_EVENT_INTENT")
        }

        val ledgerIds = HashSet<String>()
        changeSet.ledgerIntents.forEach { intent ->
            if (intent.ledgerIntentUid.isBlank() || !ledgerIds.add(intent.ledgerIntentUid)) fail("INVALID_LEDGER_INTENT")
            if (intent.ledgerKindUid != PlayerLedgerIntentKinds.FINANCIAL_TRANSFER) fail("UNKNOWN_LEDGER_INTENT_KIND")
            if (intent.causalChangeUids.any { it.isBlank() || it !in changesByUid }) fail("INVALID_LEDGER_INTENT")
            val payload = intent.payload as? FinancialTransferLedgerIntentPayload ?: fail("LEDGER_PAYLOAD_TYPE_MISMATCH")
            validateFinancialTerms(
                payload.fromAccountUid, payload.toAccountUid, payload.amountMinor,
                payload.currencyUid, payload.transactionTypeUid, "INVALID_LEDGER_INTENT"
            )

            var matchingFinancialCauseFound = false
            intent.causalChangeUids.forEach { causalUid ->
                val causalChange = changesByUid.getValue(causalUid)
                if (causalChange.changeKindUid == PlayerChangeKinds.FINANCIAL) {
                    val financial = causalChange.payload as? FinancialChange ?: fail("CHANGE_PAYLOAD_TYPE_MISMATCH")
                    if (!financialTermsMatch(financial, payload)) fail("FINANCIAL_LEDGER_TERMS_MISMATCH")
                    matchingFinancialCauseFound = true
                }
            }
            if (!matchingFinancialCauseFound) fail("FINANCIAL_LEDGER_CAUSAL_CHANGE_REQUIRED")
        }

        changeSet.warnings.forEach { warning ->
            if (warning.warningKindUid.isBlank()) fail("INVALID_WARNING")
            if (warning.relatedChangeUid?.isBlank() == true) fail("INVALID_WARNING")
            if (warning.relatedChangeUid != null && warning.relatedChangeUid !in changesByUid) fail("INVALID_WARNING")
        }
    }

    private fun fail(code: String): Nothing = throw PlayerChangeSetStructuralException(code)
}

object PlayerChangeSetCodec {
    fun encode(
        changeSet: PlayerChangeSet,
        registry: TypedPlayerChangeRegistry = TypedPlayerChangeRegistry.core()
    ): String {
        PlayerChangeSetValidator.validate(changeSet, registry)
        return canonicalJson(changeSet, registry).toString()
    }

    fun decode(
        serialized: String,
        registry: TypedPlayerChangeRegistry = TypedPlayerChangeRegistry.core()
    ): PlayerChangeSet {
        rejectDuplicatePlayerChangeSetJsonObjectKeys(serialized)
        val root = try {
            Json.parseToJsonElement(serialized).jsonObject
        } catch (e: PlayerChangeSetStructuralException) {
            throw e
        } catch (_: Throwable) {
            throw PlayerChangeSetStructuralException("INVALID_CHANGESET_SERIALIZATION")
        }
        root.pcsOnlyKeys(
            setOf(
                "schemaVersion", "changeSetUid", "campaignUid", "sourceCommandUid", "actor", "changes",
                "eventIntents", "ledgerIntents", "preconditions", "provenance", "causationUid", "correlationUid",
                "requestedEffectiveOrder", "warnings"
            )
        )
        val schemaVersion = root.pcsReqInt("schemaVersion")
        if (schemaVersion != PLAYER_CHANGE_SET_SCHEMA_VERSION) throw PlayerChangeSetStructuralException("UNSUPPORTED_CHANGESET_SCHEMA_VERSION")
        val changes = root.pcsReqArray("changes").map { decodeChange(it.jsonObject, registry) }
        val eventIntents = root.pcsReqArray("eventIntents").map { decodeEventIntent(it.jsonObject) }
        val ledgerIntents = root.pcsReqArray("ledgerIntents").map { decodeLedgerIntent(it.jsonObject) }
        val changeSet = PlayerChangeSet.create(
            schemaVersion = schemaVersion,
            changeSetUid = root.pcsReqString("changeSetUid"),
            campaignUid = root.pcsReqString("campaignUid"),
            sourceCommandUid = root.pcsReqString("sourceCommandUid"),
            actor = decodeChangeSetActor(root.pcsReqObject("actor")),
            changes = changes,
            eventIntents = eventIntents,
            ledgerIntents = ledgerIntents,
            preconditions = root.pcsReqArray("preconditions").map { decodeChangeSetPrecondition(it.jsonObject) },
            provenance = decodeChangeSetProvenance(root.pcsReqObject("provenance")),
            causationUid = root.pcsOptString("causationUid"),
            correlationUid = root.pcsOptString("correlationUid"),
            requestedEffectiveOrder = root.pcsOptLong("requestedEffectiveOrder"),
            warnings = root.pcsReqArray("warnings").map { decodeWarning(it.jsonObject) },
            registry = registry
        )
        PlayerChangeSetValidator.validate(changeSet, registry)
        return changeSet
    }

    fun fingerprint(
        changeSet: PlayerChangeSet,
        registry: TypedPlayerChangeRegistry = TypedPlayerChangeRegistry.core()
    ): String = MessageDigest.getInstance("SHA-256")
        .digest(encode(changeSet, registry).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

object PlayerChangeSetIdentity {
    fun compare(
        left: PlayerChangeSet,
        right: PlayerChangeSet,
        registry: TypedPlayerChangeRegistry = TypedPlayerChangeRegistry.core()
    ): PlayerChangeSetIdentityRelation {
        if (left.campaignUid != right.campaignUid || left.changeSetUid != right.changeSetUid) {
            return PlayerChangeSetIdentityRelation.DISTINCT_CHANGE_SET
        }
        if (PlayerChangeSetCodec.fingerprint(left, registry) != PlayerChangeSetCodec.fingerprint(right, registry)) {
            throw PlayerChangeSetIdentityConflictException()
        }
        return PlayerChangeSetIdentityRelation.SAME_LOGICAL_CHANGE_SET
    }
}

private fun coreChangeCodecs(): Map<String, TypedPlayerChangeCodec<out PlayerDomainChangePayload>> = linkedMapOf(
    PlayerChangeKinds.STAT to simpleCodec(
        StatChange::class, ChangeIntentClassification.AUTHORITATIVE_MUTATION_INTENT,
        setOf("subject", "statUid", "deltaUnits"),
        encode = { pcsObj("subject" to encodeChangeSetRef(it.subject), "statUid" to pcsJ(it.statUid), "deltaUnits" to pcsJ(it.delta.units)) },
        decode = { StatChange(decodeChangeSetRef(it.pcsReqObject("subject")), it.pcsReqString("statUid"), ExactLongDelta.of(it.pcsReqLong("deltaUnits"))) },
        validate = { refAndUidErrors(it.subject, it.statUid) },
        conflicts = { setOf("STAT:${it.subject.kindUid}:${it.subject.uid}:${it.statUid}") }
    ),
    PlayerChangeKinds.RESOURCE to simpleCodec(
        ResourceChange::class, ChangeIntentClassification.AUTHORITATIVE_MUTATION_INTENT,
        setOf("subject", "resourceUid", "deltaUnits"),
        encode = { pcsObj("subject" to encodeChangeSetRef(it.subject), "resourceUid" to pcsJ(it.resourceUid), "deltaUnits" to pcsJ(it.delta.units)) },
        decode = { ResourceChange(decodeChangeSetRef(it.pcsReqObject("subject")), it.pcsReqString("resourceUid"), ExactLongDelta.of(it.pcsReqLong("deltaUnits"))) },
        validate = { refAndUidErrors(it.subject, it.resourceUid) },
        conflicts = { setOf("RESOURCE:${it.subject.kindUid}:${it.subject.uid}:${it.resourceUid}") }
    ),
    PlayerChangeKinds.SKILL to simpleCodec(
        SkillChange::class, ChangeIntentClassification.AUTHORITATIVE_MUTATION_INTENT,
        setOf("subject", "skillUid", "progressDeltaUnits"),
        encode = { pcsObj("subject" to encodeChangeSetRef(it.subject), "skillUid" to pcsJ(it.skillUid), "progressDeltaUnits" to pcsJ(it.progressDelta.units)) },
        decode = { SkillChange(decodeChangeSetRef(it.pcsReqObject("subject")), it.pcsReqString("skillUid"), ExactLongDelta.of(it.pcsReqLong("progressDeltaUnits"))) },
        validate = { refAndUidErrors(it.subject, it.skillUid) },
        conflicts = { setOf("SKILL:${it.subject.kindUid}:${it.subject.uid}:${it.skillUid}") }
    ),
    PlayerChangeKinds.TECHNIQUE to simpleCodec(
        TechniqueChange::class, ChangeIntentClassification.AUTHORITATIVE_MUTATION_INTENT,
        setOf("subject", "techniqueUid", "progressDeltaUnits"),
        encode = { pcsObj("subject" to encodeChangeSetRef(it.subject), "techniqueUid" to pcsJ(it.techniqueUid), "progressDeltaUnits" to pcsJ(it.progressDelta.units)) },
        decode = { TechniqueChange(decodeChangeSetRef(it.pcsReqObject("subject")), it.pcsReqString("techniqueUid"), ExactLongDelta.of(it.pcsReqLong("progressDeltaUnits"))) },
        validate = { refAndUidErrors(it.subject, it.techniqueUid) },
        conflicts = { setOf("TECHNIQUE:${it.subject.kindUid}:${it.subject.uid}:${it.techniqueUid}") }
    ),
    PlayerChangeKinds.INNATE to simpleCodec(
        InnateChange::class, ChangeIntentClassification.AUTHORITATIVE_MUTATION_INTENT,
        setOf("subject", "innateUid", "proposedStateUid"),
        encode = { pcsObj("subject" to encodeChangeSetRef(it.subject), "innateUid" to pcsJ(it.innateUid), "proposedStateUid" to pcsJ(it.proposedStateUid)) },
        decode = { InnateChange(decodeChangeSetRef(it.pcsReqObject("subject")), it.pcsReqString("innateUid"), it.pcsReqString("proposedStateUid")) },
        validate = { refAndUidErrors(it.subject, it.innateUid) + blankError(it.proposedStateUid) },
        conflicts = { setOf("INNATE:${it.subject.kindUid}:${it.subject.uid}:${it.innateUid}") }
    ),
    PlayerChangeKinds.INVENTORY to simpleCodec(
        InventoryChange::class, ChangeIntentClassification.AUTHORITATIVE_MUTATION_INTENT,
        setOf("subject", "itemInstanceUid", "quantityDeltaUnits"),
        encode = { pcsObj("subject" to encodeChangeSetRef(it.subject), "itemInstanceUid" to pcsJ(it.itemInstanceUid), "quantityDeltaUnits" to pcsJ(it.quantityDelta.units)) },
        decode = { InventoryChange(decodeChangeSetRef(it.pcsReqObject("subject")), it.pcsReqString("itemInstanceUid"), ExactLongDelta.of(it.pcsReqLong("quantityDeltaUnits"))) },
        validate = { refAndUidErrors(it.subject, it.itemInstanceUid) },
        conflicts = { setOf("INVENTORY:${it.subject.kindUid}:${it.subject.uid}:${it.itemInstanceUid}") }
    ),
    PlayerChangeKinds.EQUIPMENT to simpleCodec(
        EquipmentChange::class, ChangeIntentClassification.AUTHORITATIVE_MUTATION_INTENT,
        setOf("subject", "slotUid", "operation", "itemInstanceUid"),
        encode = { pcsObj("subject" to encodeChangeSetRef(it.subject), "slotUid" to pcsJ(it.slotUid), "operation" to pcsJ(it.operation.name), "itemInstanceUid" to pcsJn(it.itemInstanceUid)) },
        decode = {
            val operation = enumValue<EquipmentOperation>(it.pcsReqString("operation"), "INVALID_EQUIPMENT_OPERATION")
            EquipmentChange(decodeChangeSetRef(it.pcsReqObject("subject")), it.pcsReqString("slotUid"), operation, it.pcsOptString("itemInstanceUid"))
        },
        validate = {
            buildList {
                if (!validRef(it.subject) || it.slotUid.isBlank()) add("INVALID_EQUIPMENT_CHANGE")
                if (it.operation == EquipmentOperation.EQUIP && it.itemInstanceUid.isNullOrBlank()) add("INVALID_EQUIPMENT_CHANGE")
                if (it.operation == EquipmentOperation.UNEQUIP && it.itemInstanceUid != null) add("INVALID_EQUIPMENT_CHANGE")
            }
        },
        conflicts = { setOf("EQUIPMENT:${it.subject.kindUid}:${it.subject.uid}:${it.slotUid}") }
    ),
    PlayerChangeKinds.FINANCIAL to simpleCodec(
        FinancialChange::class, ChangeIntentClassification.LEDGER_APPEND_INTENT,
        setOf("fromAccountUid", "toAccountUid", "amountMinor", "currencyUid", "transactionTypeUid"),
        encode = { pcsObj("fromAccountUid" to pcsJ(it.fromAccountUid), "toAccountUid" to pcsJ(it.toAccountUid), "amountMinor" to pcsJ(it.amountMinor), "currencyUid" to pcsJ(it.currencyUid), "transactionTypeUid" to pcsJ(it.transactionTypeUid)) },
        decode = { FinancialChange(it.pcsReqString("fromAccountUid"), it.pcsReqString("toAccountUid"), it.pcsReqLong("amountMinor"), it.pcsReqString("currencyUid"), it.pcsReqString("transactionTypeUid")) },
        validate = {
            runCatching { validateFinancialTerms(it.fromAccountUid, it.toAccountUid, it.amountMinor, it.currencyUid, it.transactionTypeUid, "INVALID_FINANCIAL_CHANGE") }
                .exceptionOrNull()?.let { e -> listOf((e as PlayerChangeSetStructuralException).code) } ?: emptyList()
        },
        conflicts = { setOf("FIN_ACCOUNT:${it.fromAccountUid}", "FIN_ACCOUNT:${it.toAccountUid}") }
    ),
    PlayerChangeKinds.ASSET to simpleCodec(
        AssetChange::class, ChangeIntentClassification.AUTHORITATIVE_MUTATION_INTENT,
        setOf("asset", "proposedLifecycleStateUid"),
        encode = { pcsObj("asset" to encodeOwnedAsset(it.asset), "proposedLifecycleStateUid" to pcsJ(it.proposedLifecycleStateUid)) },
        decode = { AssetChange(decodeOwnedAsset(it.pcsReqObject("asset")), it.pcsReqString("proposedLifecycleStateUid")) },
        validate = {
            buildList {
                if (it.asset.assetKindUid.isBlank() || it.asset.assetUid.isBlank()) add("INVALID_ASSET_CHANGE")
                if (it.proposedLifecycleStateUid.isBlank()) add("INVALID_ASSET_CHANGE")
            }
        },
        conflicts = { setOf("ASSET:${it.asset.assetKindUid}:${it.asset.assetUid}") }
    ),
    PlayerChangeKinds.OWNERSHIP to simpleCodec(
        OwnershipChange::class, ChangeIntentClassification.AUTHORITATIVE_MUTATION_INTENT,
        setOf("ownershipRecordUid", "asset", "fromOwner", "toOwner", "shareUnits"),
        encode = { pcsObj("ownershipRecordUid" to pcsJ(it.ownershipRecordUid), "asset" to encodeOwnedAsset(it.asset), "fromOwner" to encodeOwner(it.fromOwner), "toOwner" to encodeOwner(it.toOwner), "shareUnits" to pcsJ(it.share.units)) },
        decode = { OwnershipChange(it.pcsReqString("ownershipRecordUid"), decodeOwnedAsset(it.pcsReqObject("asset")), decodeOwner(it.pcsReqObject("fromOwner")), decodeOwner(it.pcsReqObject("toOwner")), OwnershipShare.ofUnits(it.pcsReqLong("shareUnits"))) },
        validate = {
            buildList {
                if (it.ownershipRecordUid.isBlank()) add("INVALID_OWNERSHIP_CHANGE")
                if (it.asset.assetKindUid.isBlank() || it.asset.assetUid.isBlank()) add("INVALID_OWNERSHIP_CHANGE")
                if (it.fromOwner.ownerKindUid.isBlank() || it.fromOwner.ownerUid.isBlank()) add("INVALID_OWNERSHIP_CHANGE")
                if (it.toOwner.ownerKindUid.isBlank() || it.toOwner.ownerUid.isBlank() || it.fromOwner == it.toOwner) add("INVALID_OWNERSHIP_CHANGE")
            }
        },
        conflicts = { setOf("OWNERSHIP:${it.ownershipRecordUid}", "OWNED_ASSET:${it.asset.assetKindUid}:${it.asset.assetUid}") }
    ),
    PlayerChangeKinds.CONDITION to simpleCodec(
        ConditionChange::class, ChangeIntentClassification.AUTHORITATIVE_MUTATION_INTENT,
        setOf("subject", "conditionUid", "operation"),
        encode = { pcsObj("subject" to encodeChangeSetRef(it.subject), "conditionUid" to pcsJ(it.conditionUid), "operation" to pcsJ(it.operation.name)) },
        decode = { ConditionChange(decodeChangeSetRef(it.pcsReqObject("subject")), it.pcsReqString("conditionUid"), enumValue(it.pcsReqString("operation"), "INVALID_CONDITION_OPERATION")) },
        validate = { refAndUidErrors(it.subject, it.conditionUid) },
        conflicts = { setOf("CONDITION:${it.subject.kindUid}:${it.subject.uid}:${it.conditionUid}") }
    ),
    PlayerChangeKinds.RUNTIME to simpleCodec(
        RuntimeChange::class, ChangeIntentClassification.RUNTIME_MUTATION_INTENT,
        setOf("subject", "runtimeCounterUid", "deltaUnits"),
        encode = { pcsObj("subject" to encodeChangeSetRef(it.subject), "runtimeCounterUid" to pcsJ(it.runtimeCounterUid), "deltaUnits" to pcsJ(it.delta.units)) },
        decode = { RuntimeChange(decodeChangeSetRef(it.pcsReqObject("subject")), it.pcsReqString("runtimeCounterUid"), ExactLongDelta.of(it.pcsReqLong("deltaUnits"))) },
        validate = { refAndUidErrors(it.subject, it.runtimeCounterUid) },
        conflicts = { setOf("RUNTIME:${it.subject.kindUid}:${it.subject.uid}:${it.runtimeCounterUid}") }
    ),
    PlayerChangeKinds.DEVELOPMENT_PROJECT to simpleCodec(
        DevelopmentProjectChange::class, ChangeIntentClassification.AUTHORITATIVE_MUTATION_INTENT,
        setOf("projectUid", "workResultKindUid", "progressDeltaUnits", "evidenceRefs"),
        encode = { pcsObj("projectUid" to pcsJ(it.projectUid), "workResultKindUid" to pcsJ(it.workResultKindUid), "progressDeltaUnits" to pcsJ(it.progressDelta.units), "evidenceRefs" to JsonArray(it.evidenceRefs.map(::encodeChangeSetRef))) },
        decode = { DevelopmentProjectChange.create(it.pcsReqString("projectUid"), it.pcsReqString("workResultKindUid"), ExactLongDelta.of(it.pcsReqLong("progressDeltaUnits")), it.pcsReqArray("evidenceRefs").map { e -> decodeChangeSetRef(e.jsonObject) }) },
        validate = { blankError(it.projectUid) + blankError(it.workResultKindUid) + if (it.evidenceRefs.any { ref -> !validRef(ref) }) listOf("INVALID_PROJECT_EVIDENCE_REF") else emptyList() },
        conflicts = { setOf("PROJECT:${it.projectUid}") }
    )
)

private fun <P : PlayerDomainChangePayload> simpleCodec(
    type: KClass<P>,
    classification: ChangeIntentClassification,
    keys: Set<String>,
    encode: (P) -> JsonObject,
    decode: (JsonObject) -> P,
    validate: (P) -> List<String>,
    conflicts: (P) -> Set<String>
): TypedPlayerChangeCodec<P> = object : TypedPlayerChangeCodec<P>(type, classification, keys) {
    override fun encode(payload: P): JsonObject = encode(payload)
    override fun decodeKnownFields(obj: JsonObject): P = decode(obj)
    override fun validate(payload: P): List<String> = validate(payload)
    override fun conflictKeys(payload: P): Set<String> = conflicts(payload)
}

private fun canonicalJson(changeSet: PlayerChangeSet, registry: TypedPlayerChangeRegistry): JsonObject = buildJsonObject {
    put("schemaVersion", JsonPrimitive(changeSet.schemaVersion))
    put("changeSetUid", pcsJ(changeSet.changeSetUid))
    put("campaignUid", pcsJ(changeSet.campaignUid))
    put("sourceCommandUid", pcsJ(changeSet.sourceCommandUid))
    put("actor", encodeChangeSetActor(changeSet.actor))
    put("changes", buildJsonArray { changeSet.changes.forEach { add(encodeChange(it, registry)) } })
    put("eventIntents", buildJsonArray { changeSet.eventIntents.forEach { add(encodeEventIntent(it)) } })
    put("ledgerIntents", buildJsonArray { changeSet.ledgerIntents.forEach { add(encodeLedgerIntent(it)) } })
    put("preconditions", buildJsonArray { changeSet.preconditions.forEach { add(encodeChangeSetPrecondition(it)) } })
    put("provenance", encodeChangeSetProvenance(changeSet.provenance))
    put("causationUid", pcsJn(changeSet.causationUid))
    put("correlationUid", pcsJn(changeSet.correlationUid))
    put("requestedEffectiveOrder", changeSet.requestedEffectiveOrder?.let(::JsonPrimitive) ?: JsonNull)
    put("warnings", buildJsonArray { changeSet.warnings.forEach { add(encodeWarning(it)) } })
}

private fun encodeChange(change: PlayerDomainChange, registry: TypedPlayerChangeRegistry): JsonObject = pcsObj(
    "changeUid" to pcsJ(change.changeUid),
    "changeKindUid" to pcsJ(change.changeKindUid),
    "payload" to registry.codec(change.changeKindUid).encodeUntyped(change.payload),
    "sourceRuleUid" to pcsJn(change.sourceRuleUid)
)

private fun decodeChange(obj: JsonObject, registry: TypedPlayerChangeRegistry): PlayerDomainChange {
    obj.pcsOnlyKeys(setOf("changeUid", "changeKindUid", "payload", "sourceRuleUid"))
    val kind = obj.pcsReqString("changeKindUid")
    val codec = registry.codec(kind)
    val payload = try {
        codec.decode(obj.pcsReqObject("payload"))
    } catch (e: PlayerChangeSetStructuralException) {
        throw e
    } catch (_: Throwable) {
        throw PlayerChangeSetStructuralException("INVALID_CHANGE_PAYLOAD")
    }
    return PlayerDomainChange.create(obj.pcsReqString("changeUid"), kind, payload, obj.pcsOptString("sourceRuleUid"), registry)
}

private fun encodeEventIntent(intent: PlayerEventIntent): JsonObject {
    val payload = when (val p = intent.payload) {
        is DomainEffectEventIntentPayload -> pcsObj("subject" to encodeChangeSetRef(p.subject), "effectKindUid" to pcsJ(p.effectKindUid))
        else -> throw PlayerChangeSetStructuralException("EVENT_PAYLOAD_TYPE_MISMATCH")
    }
    return pcsObj(
        "eventIntentUid" to pcsJ(intent.eventIntentUid), "eventKindUid" to pcsJ(intent.eventKindUid),
        "actorRef" to (intent.actorRef?.let(::encodeChangeSetRef) ?: JsonNull),
        "targetRefs" to JsonArray(intent.targetRefs.map(::encodeChangeSetRef)),
        "causalChangeUids" to JsonArray(intent.causalChangeUids.map(::pcsJ)),
        "payload" to payload,
        "proposedEffectiveOrder" to (intent.proposedEffectiveOrder?.let(::JsonPrimitive) ?: JsonNull)
    )
}

private fun decodeEventIntent(obj: JsonObject): PlayerEventIntent {
    obj.pcsOnlyKeys(setOf("eventIntentUid", "eventKindUid", "actorRef", "targetRefs", "causalChangeUids", "payload", "proposedEffectiveOrder"))
    val kind = obj.pcsReqString("eventKindUid")
    if (kind != PlayerEventIntentKinds.DOMAIN_EFFECT) throw PlayerChangeSetStructuralException("UNKNOWN_EVENT_INTENT_KIND")
    val payloadObj = obj.pcsReqObject("payload").pcsOnlyKeys(setOf("subject", "effectKindUid"))
    return PlayerEventIntent.create(
        eventIntentUid = obj.pcsReqString("eventIntentUid"),
        eventKindUid = kind,
        actorRef = obj.pcsOptObject("actorRef")?.let(::decodeChangeSetRef),
        targetRefs = obj.pcsReqArray("targetRefs").map { decodeChangeSetRef(it.jsonObject) },
        causalChangeUids = obj.pcsReqArray("causalChangeUids").map { it.pcsStringValue() },
        payload = DomainEffectEventIntentPayload(decodeChangeSetRef(payloadObj.pcsReqObject("subject")), payloadObj.pcsReqString("effectKindUid")),
        proposedEffectiveOrder = obj.pcsOptLong("proposedEffectiveOrder")
    )
}

private fun encodeLedgerIntent(intent: PlayerLedgerIntent): JsonObject {
    val payload = when (val p = intent.payload) {
        is FinancialTransferLedgerIntentPayload -> pcsObj(
            "fromAccountUid" to pcsJ(p.fromAccountUid), "toAccountUid" to pcsJ(p.toAccountUid),
            "amountMinor" to pcsJ(p.amountMinor), "currencyUid" to pcsJ(p.currencyUid),
            "transactionTypeUid" to pcsJ(p.transactionTypeUid)
        )
        else -> throw PlayerChangeSetStructuralException("LEDGER_PAYLOAD_TYPE_MISMATCH")
    }
    return pcsObj(
        "ledgerIntentUid" to pcsJ(intent.ledgerIntentUid), "ledgerKindUid" to pcsJ(intent.ledgerKindUid),
        "causalChangeUids" to JsonArray(intent.causalChangeUids.map(::pcsJ)), "payload" to payload
    )
}

private fun decodeLedgerIntent(obj: JsonObject): PlayerLedgerIntent {
    obj.pcsOnlyKeys(setOf("ledgerIntentUid", "ledgerKindUid", "causalChangeUids", "payload"))
    val kind = obj.pcsReqString("ledgerKindUid")
    if (kind != PlayerLedgerIntentKinds.FINANCIAL_TRANSFER) throw PlayerChangeSetStructuralException("UNKNOWN_LEDGER_INTENT_KIND")
    val payload = obj.pcsReqObject("payload").pcsOnlyKeys(setOf("fromAccountUid", "toAccountUid", "amountMinor", "currencyUid", "transactionTypeUid"))
    return PlayerLedgerIntent.create(
        ledgerIntentUid = obj.pcsReqString("ledgerIntentUid"),
        ledgerKindUid = kind,
        causalChangeUids = obj.pcsReqArray("causalChangeUids").map { it.pcsStringValue() },
        payload = FinancialTransferLedgerIntentPayload(
            payload.pcsReqString("fromAccountUid"), payload.pcsReqString("toAccountUid"), payload.pcsReqLong("amountMinor"),
            payload.pcsReqString("currencyUid"), payload.pcsReqString("transactionTypeUid")
        )
    )
}

private fun encodeChangeSetActor(actor: CommandActorRef): JsonObject = pcsObj(
    "actorKindUid" to pcsJ(actor.actorKindUid), "actorUid" to pcsJ(actor.actorUid)
)

private fun decodeChangeSetActor(obj: JsonObject): CommandActorRef {
    obj.pcsOnlyKeys(setOf("actorKindUid", "actorUid"))
    return CommandActorRef(obj.pcsReqString("actorKindUid"), obj.pcsReqString("actorUid"))
}

private fun encodeChangeSetRef(ref: DomainRef): JsonObject = pcsObj("kindUid" to pcsJ(ref.kindUid), "uid" to pcsJ(ref.uid))
private fun decodeChangeSetRef(obj: JsonObject): DomainRef {
    obj.pcsOnlyKeys(setOf("kindUid", "uid"))
    return DomainRef(obj.pcsReqString("kindUid"), obj.pcsReqString("uid"))
}

private fun encodeOwner(ref: OwnershipOwnerRef): JsonObject = pcsObj("ownerKindUid" to pcsJ(ref.ownerKindUid), "ownerUid" to pcsJ(ref.ownerUid))
private fun decodeOwner(obj: JsonObject): OwnershipOwnerRef {
    obj.pcsOnlyKeys(setOf("ownerKindUid", "ownerUid"))
    return OwnershipOwnerRef(obj.pcsReqString("ownerKindUid"), obj.pcsReqString("ownerUid"))
}

private fun encodeOwnedAsset(ref: OwnedAssetRef): JsonObject = pcsObj("assetKindUid" to pcsJ(ref.assetKindUid), "assetUid" to pcsJ(ref.assetUid))
private fun decodeOwnedAsset(obj: JsonObject): OwnedAssetRef {
    obj.pcsOnlyKeys(setOf("assetKindUid", "assetUid"))
    return OwnedAssetRef(obj.pcsReqString("assetKindUid"), obj.pcsReqString("assetUid"))
}

private fun encodeChangeSetProvenance(p: ChangeSetProvenance): JsonObject = pcsObj(
    "sourceCommandUid" to pcsJ(p.sourceCommandUid), "resolverKindUid" to pcsJ(p.resolverKindUid),
    "resolverVersion" to pcsJ(p.resolverVersion), "worldRuleProviderUid" to pcsJn(p.worldRuleProviderUid),
    "mechanicsVersion" to pcsJn(p.mechanicsVersion), "sourceEventUid" to pcsJn(p.sourceEventUid)
)

private fun decodeChangeSetProvenance(obj: JsonObject): ChangeSetProvenance {
    obj.pcsOnlyKeys(setOf("sourceCommandUid", "resolverKindUid", "resolverVersion", "worldRuleProviderUid", "mechanicsVersion", "sourceEventUid"))
    return ChangeSetProvenance(
        obj.pcsReqString("sourceCommandUid"), obj.pcsReqString("resolverKindUid"), obj.pcsReqString("resolverVersion"),
        obj.pcsOptString("worldRuleProviderUid"), obj.pcsOptString("mechanicsVersion"), obj.pcsOptString("sourceEventUid")
    )
}

private fun encodeChangeSetPrecondition(p: ChangeSetPrecondition): JsonObject = when (p) {
    is ChangeSetExpectedRecordVersion -> pcsObj("kind" to pcsJ("EXPECTED_RECORD_VERSION"), "target" to encodeChangeSetRef(p.target), "expectedVersion" to pcsJ(p.expectedVersion))
    is ChangeSetExpectedLifecycleState -> pcsObj("kind" to pcsJ("EXPECTED_LIFECYCLE_STATE"), "target" to encodeChangeSetRef(p.target), "expectedStateUid" to pcsJ(p.expectedStateUid))
}

private fun decodeChangeSetPrecondition(obj: JsonObject): ChangeSetPrecondition = when (obj.pcsReqString("kind")) {
    "EXPECTED_RECORD_VERSION" -> {
        obj.pcsOnlyKeys(setOf("kind", "target", "expectedVersion"))
        ChangeSetExpectedRecordVersion(decodeChangeSetRef(obj.pcsReqObject("target")), obj.pcsReqLong("expectedVersion"))
    }
    "EXPECTED_LIFECYCLE_STATE" -> {
        obj.pcsOnlyKeys(setOf("kind", "target", "expectedStateUid"))
        ChangeSetExpectedLifecycleState(decodeChangeSetRef(obj.pcsReqObject("target")), obj.pcsReqString("expectedStateUid"))
    }
    else -> throw PlayerChangeSetStructuralException("UNKNOWN_CHANGESET_PRECONDITION_KIND")
}

private fun encodeWarning(w: ChangeSetWarning): JsonObject = pcsObj(
    "warningKindUid" to pcsJ(w.warningKindUid), "detail" to pcsJn(w.detail), "relatedChangeUid" to pcsJn(w.relatedChangeUid)
)

private fun decodeWarning(obj: JsonObject): ChangeSetWarning {
    obj.pcsOnlyKeys(setOf("warningKindUid", "detail", "relatedChangeUid"))
    return ChangeSetWarning(obj.pcsReqString("warningKindUid"), obj.pcsOptString("detail"), obj.pcsOptString("relatedChangeUid"))
}

private fun refAndUidErrors(ref: DomainRef, uid: String): List<String> = buildList {
    if (!validRef(ref)) add("INVALID_TARGET_REF")
    if (uid.isBlank()) add("INVALID_TARGET_UID")
}

private fun blankError(value: String): List<String> = if (value.isBlank()) listOf("INVALID_TARGET_UID") else emptyList()

private fun validateFinancialTerms(
    fromAccountUid: String,
    toAccountUid: String,
    amountMinor: Long,
    currencyUid: String,
    transactionTypeUid: String,
    code: String
) {
    if (fromAccountUid.isBlank() || toAccountUid.isBlank() || fromAccountUid == toAccountUid || amountMinor <= 0L ||
        currencyUid.isBlank() || transactionTypeUid.isBlank()) throw PlayerChangeSetStructuralException(code)
}

private fun financialTermsMatch(
    financial: FinancialChange,
    ledger: FinancialTransferLedgerIntentPayload
): Boolean = financial.fromAccountUid == ledger.fromAccountUid &&
    financial.toAccountUid == ledger.toAccountUid &&
    financial.amountMinor == ledger.amountMinor &&
    financial.currencyUid == ledger.currencyUid &&
    financial.transactionTypeUid == ledger.transactionTypeUid

private inline fun <reified T : Enum<T>> enumValue(value: String, code: String): T = try {
    enumValueOf<T>(value)
} catch (_: Throwable) {
    throw PlayerChangeSetStructuralException(code)
}

private fun pcsJ(value: String): JsonPrimitive = JsonPrimitive(value)
private fun pcsJ(value: Long): JsonPrimitive = JsonPrimitive(value)
private fun pcsJn(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
private fun pcsObj(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*pairs))

private fun JsonObject.pcsOnlyKeys(allowed: Set<String>): JsonObject {
    if (keys.any { it !in allowed }) throw PlayerChangeSetStructuralException(PCS_UNKNOWN_FIELD)
    return this
}

private fun JsonObject.pcsReqString(key: String): String {
    val element = this[key] ?: throw PlayerChangeSetStructuralException("MISSING_$key")
    if (element is JsonNull) throw PlayerChangeSetStructuralException("MISSING_$key")
    return element.pcsStringValue()
}

private fun JsonElement.pcsStringValue(): String {
    val primitive = this as? JsonPrimitive ?: throw PlayerChangeSetStructuralException(PCS_INVALID_STRING_TYPE)
    if (!primitive.isString) throw PlayerChangeSetStructuralException(PCS_INVALID_STRING_TYPE)
    return primitive.content
}

private fun JsonObject.pcsReqInt(key: String): Int {
    val element = this[key] ?: throw PlayerChangeSetStructuralException("MISSING_$key")
    if (element is JsonNull) throw PlayerChangeSetStructuralException("MISSING_$key")
    val primitive = element as? JsonPrimitive ?: throw PlayerChangeSetStructuralException(PCS_INVALID_NUMERIC_TYPE)
    if (primitive.isString || primitive.content == "true" || primitive.content == "false") throw PlayerChangeSetStructuralException(PCS_INVALID_NUMERIC_TYPE)
    return try { primitive.int } catch (_: Throwable) { throw PlayerChangeSetStructuralException(PCS_INVALID_NUMERIC_VALUE) }
}

private fun JsonObject.pcsReqLong(key: String): Long {
    val element = this[key] ?: throw PlayerChangeSetStructuralException("MISSING_$key")
    if (element is JsonNull) throw PlayerChangeSetStructuralException("MISSING_$key")
    val primitive = element as? JsonPrimitive ?: throw PlayerChangeSetStructuralException(PCS_INVALID_NUMERIC_TYPE)
    if (primitive.isString || primitive.content == "true" || primitive.content == "false") throw PlayerChangeSetStructuralException(PCS_INVALID_NUMERIC_TYPE)
    return try { primitive.long } catch (_: Throwable) { throw PlayerChangeSetStructuralException(PCS_INVALID_NUMERIC_VALUE) }
}

private fun JsonObject.pcsOptString(key: String): String? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return element.pcsStringValue()
}

private fun JsonObject.pcsOptLong(key: String): Long? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive ?: throw PlayerChangeSetStructuralException(PCS_INVALID_NUMERIC_TYPE)
    if (primitive.isString || primitive.content == "true" || primitive.content == "false") throw PlayerChangeSetStructuralException(PCS_INVALID_NUMERIC_TYPE)
    return try { primitive.long } catch (_: Throwable) { throw PlayerChangeSetStructuralException(PCS_INVALID_NUMERIC_VALUE) }
}

private fun JsonObject.pcsReqObject(key: String): JsonObject =
    this[key]?.takeUnless { it is JsonNull }?.jsonObject ?: throw PlayerChangeSetStructuralException("MISSING_$key")

private fun JsonObject.pcsOptObject(key: String): JsonObject? = this[key]?.takeUnless { it is JsonNull }?.jsonObject

private fun JsonObject.pcsReqArray(key: String): JsonArray =
    this[key]?.takeUnless { it is JsonNull }?.jsonArray ?: throw PlayerChangeSetStructuralException("MISSING_$key")

private fun rejectDuplicatePlayerChangeSetJsonObjectKeys(serialized: String) {
    PlayerChangeSetDuplicateKeyScanner(serialized).scan()
}

private class PlayerChangeSetDuplicateKeyScanner(private val input: String) {
    private var index = 0

    fun scan() {
        skipWhitespace()
        parseValue()
        skipWhitespace()
        if (index != input.length) invalid()
    }

    private fun parseValue() {
        skipWhitespace()
        if (index >= input.length) invalid()
        when (input[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> readStringToken()
            else -> parseBareValue()
        }
    }

    private fun parseObject() {
        expect('{')
        skipWhitespace()
        if (consume('}')) return
        val seen = HashSet<String>()
        while (true) {
            skipWhitespace()
            if (index >= input.length || input[index] != '"') invalid()
            val token = readStringToken()
            val key = try { Json.parseToJsonElement(token).jsonPrimitive.content } catch (_: Throwable) { invalid() }
            if (!seen.add(key)) throw PlayerChangeSetStructuralException(PCS_DUPLICATE_JSON_OBJECT_KEY)
            skipWhitespace()
            expect(':')
            parseValue()
            skipWhitespace()
            if (consume('}')) return
            expect(',')
        }
    }

    private fun parseArray() {
        expect('[')
        skipWhitespace()
        if (consume(']')) return
        while (true) {
            parseValue()
            skipWhitespace()
            if (consume(']')) return
            expect(',')
        }
    }

    private fun readStringToken(): String {
        val start = index
        expect('"')
        while (index < input.length) {
            when (val ch = input[index++]) {
                '"' -> return input.substring(start, index)
                '\\' -> {
                    if (index >= input.length) invalid()
                    when (input[index++]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> repeat(4) {
                            if (index >= input.length || input[index] !in "0123456789abcdefABCDEF") invalid()
                            index++
                        }
                        else -> invalid()
                    }
                }
                else -> if (ch.code < 0x20) invalid()
            }
        }
        invalid()
    }

    private fun parseBareValue() {
        val start = index
        while (index < input.length) {
            val ch = input[index]
            if (ch.isWhitespace() || ch == ',' || ch == ']' || ch == '}') break
            index++
        }
        if (index == start) invalid()
    }

    private fun skipWhitespace() { while (index < input.length && input[index].isWhitespace()) index++ }
    private fun consume(expected: Char): Boolean = if (index < input.length && input[index] == expected) { index++; true } else false
    private fun expect(expected: Char) { if (!consume(expected)) invalid() }
    private fun invalid(): Nothing = throw PlayerChangeSetStructuralException("INVALID_CHANGESET_SERIALIZATION")
}
