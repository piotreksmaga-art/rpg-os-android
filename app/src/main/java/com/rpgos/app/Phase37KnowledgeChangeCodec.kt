package com.rpgos.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

internal const val PHASE37_KNOWLEDGE_CHANGE_KIND = "RPGOS-CHANGE:KNOWLEDGE_ACQUISITION"

internal fun phase37KnowledgeChangeCodec(): TypedPlayerChangeCodec<KnowledgeAcquisitionChange> =
    object : TypedPlayerChangeCodec<KnowledgeAcquisitionChange>(
        KnowledgeAcquisitionChange::class,
        ChangeIntentClassification.AUTHORITATIVE_MUTATION_INTENT,
        setOf("claim", "acquisition", "evidence")
    ) {
        override fun encode(payload: KnowledgeAcquisitionChange): JsonObject = encodeKnowledgeChange(payload)

        override fun decodeKnownFields(obj: JsonObject): KnowledgeAcquisitionChange = decodeKnowledgeChange(obj)

        override fun validate(payload: KnowledgeAcquisitionChange): List<String> = try {
            KnowledgeDomainValidator.validate(payload)
            emptyList()
        } catch (_: IllegalArgumentException) {
            listOf("INVALID_KNOWLEDGE_ACQUISITION_CHANGE")
        } catch (_: IllegalStateException) {
            listOf("INVALID_KNOWLEDGE_ACQUISITION_CHANGE")
        }

        override fun conflictKeys(payload: KnowledgeAcquisitionChange): Set<String> = setOf(
            p37ConflictKey(
                payload.acquisition.holder.holderKindUid,
                payload.acquisition.holder.holderUid,
                payload.claim.claimUid,
                payload.acquisition.scope.name,
                payload.acquisition.roleUid.orEmpty()
            )
        )
    }

private fun encodeKnowledgeChange(change: KnowledgeAcquisitionChange): JsonObject = p37Obj(
    "claim" to encodeClaim(change.claim),
    "acquisition" to encodeAcquisition(change.acquisition),
    "evidence" to JsonArray(change.evidence.map(::encodeEvidence))
)

private fun decodeKnowledgeChange(obj: JsonObject): KnowledgeAcquisitionChange {
    obj.p37Only(setOf("claim", "acquisition", "evidence"))
    return KnowledgeAcquisitionChange(
        claim = decodeClaim(obj.p37Object("claim")),
        acquisition = decodeAcquisition(obj.p37Object("acquisition")),
        evidence = obj.p37Array("evidence").map { decodeEvidence(it.jsonObject) }
    )
}

private fun encodeClaim(claim: KnowledgeClaim): JsonObject = p37Obj(
    "claimUid" to p37J(claim.claimUid),
    "subjectKindUid" to p37J(claim.subjectKindUid),
    "subjectUid" to p37J(claim.subjectUid),
    "predicateUid" to p37J(claim.predicateUid),
    "valueCanonical" to p37J(claim.valueCanonical),
    "objectKindUid" to p37Jn(claim.objectKindUid),
    "objectUid" to p37Jn(claim.objectUid),
    "domainUid" to p37J(claim.domainUid)
)

private fun decodeClaim(obj: JsonObject): KnowledgeClaim {
    obj.p37Only(setOf("claimUid","subjectKindUid","subjectUid","predicateUid","valueCanonical","objectKindUid","objectUid","domainUid"))
    return KnowledgeClaim(
        claimUid = obj.p37String("claimUid"),
        subjectKindUid = obj.p37String("subjectKindUid"),
        subjectUid = obj.p37String("subjectUid"),
        predicateUid = obj.p37String("predicateUid"),
        valueCanonical = obj.p37String("valueCanonical"),
        objectKindUid = obj.p37OptString("objectKindUid"),
        objectUid = obj.p37OptString("objectUid"),
        domainUid = obj.p37String("domainUid")
    )
}

private fun encodeAcquisition(a: KnowledgeAcquisitionSpec): JsonObject = p37Obj(
    "acquisitionUid" to p37J(a.acquisitionUid),
    "holder" to encodeHolder(a.holder),
    "methodUid" to p37J(a.methodUid),
    "scope" to p37J(a.scope.name),
    "epistemicState" to p37J(a.epistemicState.name),
    "quality" to encodeQuality(a.quality),
    "parentAcquisitionUid" to p37Jn(a.parentAcquisitionUid),
    "sourceHolder" to (a.sourceHolder?.let(::encodeHolder) ?: JsonNull),
    "roleUid" to p37Jn(a.roleUid),
    "carrier" to (a.carrier?.let(::encodeCarrier) ?: JsonNull),
    "provenanceStatus" to p37J(a.provenanceStatus.name)
)

private fun decodeAcquisition(obj: JsonObject): KnowledgeAcquisitionSpec {
    obj.p37Only(setOf("acquisitionUid","holder","methodUid","scope","epistemicState","quality","parentAcquisitionUid","sourceHolder","roleUid","carrier","provenanceStatus"))
    return KnowledgeAcquisitionSpec(
        acquisitionUid = obj.p37String("acquisitionUid"),
        holder = decodeHolder(obj.p37Object("holder")),
        methodUid = obj.p37String("methodUid"),
        scope = p37Enum(obj.p37String("scope"), "INVALID_KNOWLEDGE_SCOPE"),
        epistemicState = p37Enum(obj.p37String("epistemicState"), "INVALID_KNOWLEDGE_STATE"),
        quality = decodeQuality(obj.p37Object("quality")),
        parentAcquisitionUid = obj.p37OptString("parentAcquisitionUid"),
        sourceHolder = obj.p37OptObject("sourceHolder")?.let(::decodeHolder),
        roleUid = obj.p37OptString("roleUid"),
        carrier = obj.p37OptObject("carrier")?.let(::decodeCarrier),
        provenanceStatus = p37Enum(obj.p37String("provenanceStatus"), "INVALID_KNOWLEDGE_PROVENANCE")
    )
}

private fun encodeQuality(q: KnowledgeQuality): JsonObject = p37Obj(
    "confidence" to JsonPrimitive(q.confidence),
    "precision" to JsonPrimitive(q.precision),
    "completeness" to JsonPrimitive(q.completeness),
    "sourceReliability" to JsonPrimitive(q.sourceReliability),
    "corroborationCount" to JsonPrimitive(q.corroborationCount),
    "sourceObservedOrder" to (q.sourceObservedOrder?.let(::JsonPrimitive) ?: JsonNull)
)

private fun decodeQuality(obj: JsonObject): KnowledgeQuality {
    obj.p37Only(setOf("confidence","precision","completeness","sourceReliability","corroborationCount","sourceObservedOrder"))
    return KnowledgeQuality(
        confidence = obj.p37Double("confidence"),
        precision = obj.p37Double("precision"),
        completeness = obj.p37Double("completeness"),
        sourceReliability = obj.p37Double("sourceReliability"),
        corroborationCount = obj.p37Int("corroborationCount"),
        sourceObservedOrder = obj.p37OptLong("sourceObservedOrder")
    )
}

private fun encodeEvidence(e: KnowledgeEvidenceSpec): JsonObject = p37Obj(
    "evidenceUid" to p37J(e.evidenceUid),
    "evidenceKindUid" to p37J(e.evidenceKindUid),
    "polarity" to p37J(e.polarity.name),
    "sourceAcquisitionUid" to p37Jn(e.sourceAcquisitionUid),
    "sourceCarrier" to (e.sourceCarrier?.let(::encodeCarrier) ?: JsonNull),
    "sourceRef" to (e.sourceRef?.let(::encodeSourceRef) ?: JsonNull)
)

private fun decodeEvidence(obj: JsonObject): KnowledgeEvidenceSpec {
    obj.p37Only(setOf("evidenceUid","evidenceKindUid","polarity","sourceAcquisitionUid","sourceCarrier","sourceRef"))
    return KnowledgeEvidenceSpec(
        evidenceUid = obj.p37String("evidenceUid"),
        evidenceKindUid = obj.p37String("evidenceKindUid"),
        polarity = p37Enum(obj.p37String("polarity"), "INVALID_KNOWLEDGE_EVIDENCE_POLARITY"),
        sourceAcquisitionUid = obj.p37OptString("sourceAcquisitionUid"),
        sourceCarrier = obj.p37OptObject("sourceCarrier")?.let(::decodeCarrier),
        sourceRef = obj.p37OptObject("sourceRef")?.let(::decodeSourceRef)
    )
}

private fun encodeHolder(holder: KnowledgeHolderRef): JsonObject = p37Obj(
    "holderKindUid" to p37J(holder.holderKindUid),
    "holderUid" to p37J(holder.holderUid),
    "campaignUid" to p37Jn(holder.campaignUid)
)

private fun decodeHolder(obj: JsonObject): KnowledgeHolderRef {
    obj.p37Only(setOf("holderKindUid","holderUid","campaignUid"))
    return KnowledgeHolderRef(obj.p37String("holderKindUid"), obj.p37String("holderUid"), obj.p37OptString("campaignUid"))
}

private fun encodeCarrier(carrier: KnowledgeCarrierRef): JsonObject = p37Obj(
    "carrierKindUid" to p37J(carrier.carrierKindUid),
    "carrierUid" to p37J(carrier.carrierUid),
    "campaignUid" to p37Jn(carrier.campaignUid)
)

private fun decodeCarrier(obj: JsonObject): KnowledgeCarrierRef {
    obj.p37Only(setOf("carrierKindUid","carrierUid","campaignUid"))
    return KnowledgeCarrierRef(obj.p37String("carrierKindUid"), obj.p37String("carrierUid"), obj.p37OptString("campaignUid"))
}

private fun encodeSourceRef(ref: KnowledgeSourceRef): JsonObject = p37Obj(
    "scope" to p37J(ref.scope.name),
    "campaignUid" to p37Jn(ref.campaignUid),
    "kindUid" to p37J(ref.kindUid),
    "entityUid" to p37J(ref.entityUid)
)

private fun decodeSourceRef(obj: JsonObject): KnowledgeSourceRef {
    obj.p37Only(setOf("scope","campaignUid","kindUid","entityUid"))
    return KnowledgeSourceRef(
        scope = p37Enum(obj.p37String("scope"), "INVALID_KNOWLEDGE_REFERENCE_SCOPE"),
        campaignUid = obj.p37OptString("campaignUid"),
        kindUid = obj.p37String("kindUid"),
        entityUid = obj.p37String("entityUid")
    )
}

private fun p37ConflictKey(vararg values: String): String = buildString {
    append("P37K|")
    values.forEach { value -> append(value.length).append(':').append(value).append('|') }
}

private fun p37Obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*pairs))
private fun p37J(value: String) = JsonPrimitive(value)
private fun p37Jn(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull

private fun JsonObject.p37Only(keys: Set<String>): JsonObject {
    if (this.keys.any { it !in keys }) throw PlayerChangeSetStructuralException("UNKNOWN_KNOWLEDGE_CHANGE_FIELD")
    return this
}

private fun JsonObject.p37String(key: String): String {
    val p = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive
        ?: throw PlayerChangeSetStructuralException("MISSING_$key")
    if (!p.isString) throw PlayerChangeSetStructuralException("INVALID_KNOWLEDGE_STRING")
    return p.content
}

private fun JsonObject.p37OptString(key: String): String? {
    val e = this[key] ?: return null
    if (e is JsonNull) return null
    val p = e.jsonPrimitive
    if (!p.isString) throw PlayerChangeSetStructuralException("INVALID_KNOWLEDGE_STRING")
    return p.content
}

private fun JsonObject.p37Double(key: String): Double {
    val p = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive
        ?: throw PlayerChangeSetStructuralException("MISSING_$key")
    if (p.isString) throw PlayerChangeSetStructuralException("INVALID_KNOWLEDGE_NUMBER")
    return try { p.double } catch (_: Throwable) { throw PlayerChangeSetStructuralException("INVALID_KNOWLEDGE_NUMBER") }
}

private fun JsonObject.p37Int(key: String): Int {
    val p = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive
        ?: throw PlayerChangeSetStructuralException("MISSING_$key")
    if (p.isString) throw PlayerChangeSetStructuralException("INVALID_KNOWLEDGE_NUMBER")
    return try { p.int } catch (_: Throwable) { throw PlayerChangeSetStructuralException("INVALID_KNOWLEDGE_NUMBER") }
}

private fun JsonObject.p37OptLong(key: String): Long? {
    val e = this[key] ?: return null
    if (e is JsonNull) return null
    val p = e.jsonPrimitive
    if (p.isString) throw PlayerChangeSetStructuralException("INVALID_KNOWLEDGE_NUMBER")
    return try { p.long } catch (_: Throwable) { throw PlayerChangeSetStructuralException("INVALID_KNOWLEDGE_NUMBER") }
}

private fun JsonObject.p37Object(key: String): JsonObject =
    this[key]?.takeUnless { it is JsonNull }?.jsonObject ?: throw PlayerChangeSetStructuralException("MISSING_$key")

private fun JsonObject.p37OptObject(key: String): JsonObject? =
    this[key]?.takeUnless { it is JsonNull }?.jsonObject

private fun JsonObject.p37Array(key: String): JsonArray =
    this[key]?.takeUnless { it is JsonNull }?.jsonArray ?: throw PlayerChangeSetStructuralException("MISSING_$key")

private inline fun <reified T : Enum<T>> p37Enum(value: String, code: String): T = try {
    enumValueOf<T>(value)
} catch (_: Throwable) {
    throw PlayerChangeSetStructuralException(code)
}
