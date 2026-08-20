from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def replace_once(rel, old, new):
    p = ROOT / rel
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"missing patch anchor in {rel}: {old[:120]!r}")
    if text.count(old) != 1:
        raise SystemExit(f"non-unique patch anchor in {rel}: {old[:120]!r} count={text.count(old)}")
    p.write_text(text.replace(old, new, 1))
    print("patched", rel)

def insert_before(rel, anchor, block):
    replace_once(rel, anchor, block + anchor)

PHASE37 = "app/src/main/java/com/rpgos/app/Phase37WorldActorKnowledge.kt"
CODEC = "app/src/main/java/com/rpgos/app/Phase37KnowledgeChangeCodec.kt"
GATE = "app/src/main/java/com/rpgos/app/GameplayMutationGate.kt"
BOOT = "app/src/main/java/com/rpgos/app/GameplayRuntimeBootstrap.kt"
WORLD = "app/src/main/java/com/rpgos/app/WorldRuleProvider.kt"
TEST = "app/src/test/java/com/rpgos/app/Phase37WorldActorKnowledgeTest.kt"

# ---------------------------------------------------------------------------
# P37-POST-AUD-002: campaign-qualified epistemic references.
# Keep the two-argument constructors source-compatible for read/legacy callers,
# but RECORDED writes now require explicit campaign qualification.
# ---------------------------------------------------------------------------
replace_once(PHASE37,
'''data class KnowledgeHolderRef(val holderKindUid: String, val holderUid: String) {
    init { require(holderKindUid.isNotBlank() && holderUid.isNotBlank()) }
}''',
'''data class KnowledgeHolderRef(
    val holderKindUid: String,
    val holderUid: String,
    val campaignUid: String? = null
) {
    init {
        require(holderKindUid.isNotBlank() && holderUid.isNotBlank())
        require(campaignUid?.isBlank() != true)
    }
}''')

replace_once(PHASE37,
'''data class KnowledgeCarrierRef(val carrierKindUid: String, val carrierUid: String) {
    init { require(carrierKindUid.isNotBlank() && carrierUid.isNotBlank()) }
}''',
'''data class KnowledgeCarrierRef(
    val carrierKindUid: String,
    val carrierUid: String,
    val campaignUid: String? = null
) {
    init {
        require(carrierKindUid.isNotBlank() && carrierUid.isNotBlank())
        require(campaignUid?.isBlank() != true)
    }
}''')

replace_once(PHASE37,
'''data class KnowledgeEvidenceSpec(
    val evidenceUid: String,
    val evidenceKindUid: String,
    val polarity: KnowledgeEvidencePolarity,
    val sourceAcquisitionUid: String? = null,
    val sourceCarrier: KnowledgeCarrierRef? = null,
    val sourceRefKindUid: String? = null,
    val sourceRefUid: String? = null
) {
    init {
        require(evidenceUid.isNotBlank() && evidenceKindUid.isNotBlank())
        require(sourceAcquisitionUid?.isBlank() != true)
        require((sourceRefKindUid == null) == (sourceRefUid == null))
        require(sourceRefKindUid?.isBlank() != true && sourceRefUid?.isBlank() != true)
    }
}''',
'''data class KnowledgeEvidenceSpec(
    val evidenceUid: String,
    val evidenceKindUid: String,
    val polarity: KnowledgeEvidencePolarity,
    val sourceAcquisitionUid: String? = null,
    val sourceCarrier: KnowledgeCarrierRef? = null,
    val sourceRef: KnowledgeSourceRef? = null
) {
    val sourceRefKindUid: String? get() = sourceRef?.kindUid
    val sourceRefUid: String? get() = sourceRef?.entityUid

    init {
        require(evidenceUid.isNotBlank() && evidenceKindUid.isNotBlank())
        require(sourceAcquisitionUid?.isBlank() != true)
    }
}''')

replace_once(PHASE37,
'''data class KnowledgeEvidence(
    val campaignUid: String,
    val evidenceUid: String,
    val acquisitionUid: String,
    val claimUid: String,
    val evidenceKindUid: String,
    val polarity: KnowledgeEvidencePolarity,
    val sourceEventUid: String?,
    val sourceAcquisitionUid: String?,
    val sourceCarrier: KnowledgeCarrierRef?,
    val sourceRefKindUid: String?,
    val sourceRefUid: String?
)''',
'''data class KnowledgeEvidence(
    val campaignUid: String,
    val evidenceUid: String,
    val acquisitionUid: String,
    val claimUid: String,
    val evidenceKindUid: String,
    val polarity: KnowledgeEvidencePolarity,
    val sourceEventUid: String?,
    val sourceAcquisitionUid: String?,
    val sourceCarrier: KnowledgeCarrierRef?,
    val sourceRef: KnowledgeSourceRef?
) {
    val sourceRefKindUid: String? get() = sourceRef?.kindUid
    val sourceRefUid: String? get() = sourceRef?.entityUid
}''')

replace_once(PHASE37,
'''        KnowledgeDomainValidator.validate(change)
        KnowledgeTurnBuffer.stage(db, campaignUid, change, identity, eventUid, createdOrder)''',
'''        Phase37GuardDefinitionIntegrity.requireCanonical(db)
        KnowledgeDomainValidator.validateForCampaign(change, campaignUid)
        KnowledgeTurnBuffer.stage(db, campaignUid, change, identity, eventUid, createdOrder)''')

replace_once(PHASE37,
'''    fun acquisitions(holder: KnowledgeHolderRef? = null): List<KnowledgeAcquisition> {
        if (!Phase37KnowledgeSchema.isReady(db)) return emptyList()
        val where = if (holder == null) "campaign_uid=?" else "campaign_uid=? AND holder_kind_uid=? AND holder_uid=?"''',
'''    fun acquisitions(holder: KnowledgeHolderRef? = null): List<KnowledgeAcquisition> {
        if (!Phase37KnowledgeSchema.isReady(db)) return emptyList()
        holder?.campaignUid?.let { require(it == campaignUid) { "RPGOS-KNOWLEDGE:CROSS_CAMPAIGN_HOLDER_QUERY" } }
        val where = if (holder == null) "campaign_uid=?" else "campaign_uid=? AND holder_kind_uid=? AND holder_uid=?"''')

replace_once(PHASE37,
'''                    campaignUid,c.getString(0),c.getString(1),KnowledgeHolderRef(c.getString(2),c.getString(3)),c.getString(4),
                    KnowledgeScope.valueOf(c.getString(5)),str(c,6),
                    if(c.isNull(7)) null else KnowledgeHolderRef(c.getString(7),c.getString(8)),str(c,9),
                    if(c.isNull(10)) null else KnowledgeCarrierRef(c.getString(10),c.getString(11)),''',
'''                    campaignUid,c.getString(0),c.getString(1),KnowledgeHolderRef(c.getString(2),c.getString(3),campaignUid),c.getString(4),
                    KnowledgeScope.valueOf(c.getString(5)),str(c,6),
                    if(c.isNull(7)) null else KnowledgeHolderRef(c.getString(7),c.getString(8),campaignUid),str(c,9),
                    if(c.isNull(10)) null else KnowledgeCarrierRef(c.getString(10),c.getString(11),campaignUid),''')

replace_once(PHASE37,
'''    fun states(holder: KnowledgeHolderRef): List<KnowledgeState> {
        if (!Phase37KnowledgeSchema.isReady(db)) return emptyList()
        return db.rawQuery''',
'''    fun states(holder: KnowledgeHolderRef): List<KnowledgeState> {
        if (!Phase37KnowledgeSchema.isReady(db)) return emptyList()
        holder.campaignUid?.let { require(it == campaignUid) { "RPGOS-KNOWLEDGE:CROSS_CAMPAIGN_HOLDER_QUERY" } }
        val qualifiedHolder = KnowledgeHolderRef(holder.holderKindUid, holder.holderUid, campaignUid)
        return db.rawQuery''')
replace_once(PHASE37,
'''                campaignUid,c.getString(0),holder,c.getString(1),KnowledgeScope.valueOf(c.getString(2)),c.getString(3).ifBlank { null },''',
'''                campaignUid,c.getString(0),qualifiedHolder,c.getString(1),KnowledgeScope.valueOf(c.getString(2)),c.getString(3).ifBlank { null },''')

replace_once(PHASE37,
'''                str(c,4),str(c,5),if(c.isNull(6))null else KnowledgeCarrierRef(c.getString(6),c.getString(7)),str(c,8),str(c,9)
            )) } }''',
'''                str(c,4),str(c,5),if(c.isNull(6))null else KnowledgeCarrierRef(c.getString(6),c.getString(7),campaignUid),
                if(c.isNull(8)) null else KnowledgeSourceRef.fromStorage(c.getString(8), c.getString(9), campaignUid)
            )) } }''')

replace_once(PHASE37,
'''            put("source_carrier_kind_uid",e.sourceCarrier?.carrierKindUid);put("source_carrier_uid",e.sourceCarrier?.carrierUid)
            put("source_ref_kind_uid",e.sourceRefKindUid);put("source_ref_uid",e.sourceRefUid);put("evidence_schema_version",PHASE37_KNOWLEDGE_SCHEMA_VERSION)''',
'''            put("source_carrier_kind_uid",e.sourceCarrier?.carrierKindUid);put("source_carrier_uid",e.sourceCarrier?.carrierUid)
            put("source_ref_kind_uid",e.sourceRef?.storageKindUid());put("source_ref_uid",e.sourceRef?.entityUid);put("evidence_schema_version",PHASE37_KNOWLEDGE_SCHEMA_VERSION)''')

replace_once(PHASE37,
'''internal object KnowledgeDomainValidator {
    fun validate(change: KnowledgeAcquisitionChange) {
        val a=change.acquisition
        require(a.provenanceStatus==KnowledgeProvenanceStatus.RECORDED) { "RPGOS-KNOWLEDGE:GAMEPLAY_REQUIRES_RECORDED_PROVENANCE" }
        require(a.holder.holderKindUid.isNotBlank()&&a.holder.holderUid.isNotBlank())
        require(change.claim.domainUid.isNotBlank())
        require(change.evidence.none { it.evidenceUid == "RPGOS-KNOWLEDGE-EVENT:${a.acquisitionUid}" }) { "RPGOS-KNOWLEDGE:RESERVED_EVENT_EVIDENCE_UID" }
    }
}''',
'''internal object KnowledgeDomainValidator {
    fun validate(change: KnowledgeAcquisitionChange) {
        val a=change.acquisition
        require(a.provenanceStatus==KnowledgeProvenanceStatus.RECORDED) { "RPGOS-KNOWLEDGE:GAMEPLAY_REQUIRES_RECORDED_PROVENANCE" }
        require(a.holder.holderKindUid.isNotBlank()&&a.holder.holderUid.isNotBlank())
        require(!a.holder.campaignUid.isNullOrBlank()) { "RPGOS-KNOWLEDGE:UNQUALIFIED_HOLDER" }
        a.sourceHolder?.let { require(!it.campaignUid.isNullOrBlank()) { "RPGOS-KNOWLEDGE:UNQUALIFIED_SOURCE_HOLDER" } }
        a.carrier?.let { require(!it.campaignUid.isNullOrBlank()) { "RPGOS-KNOWLEDGE:UNQUALIFIED_CARRIER" } }
        change.evidence.forEach { e ->
            e.sourceCarrier?.let { require(!it.campaignUid.isNullOrBlank()) { "RPGOS-KNOWLEDGE:UNQUALIFIED_EVIDENCE_CARRIER" } }
            e.sourceRef?.validateStructural()
        }
        require(change.claim.domainUid.isNotBlank())
        require(change.evidence.none { it.evidenceUid == "RPGOS-KNOWLEDGE-EVENT:${a.acquisitionUid}" }) { "RPGOS-KNOWLEDGE:RESERVED_EVENT_EVIDENCE_UID" }
    }

    fun validateForCampaign(change: KnowledgeAcquisitionChange, campaignUid: String) {
        validate(change)
        val a = change.acquisition
        require(a.holder.campaignUid == campaignUid) { "RPGOS-KNOWLEDGE:CROSS_CAMPAIGN_HOLDER" }
        a.sourceHolder?.let { require(it.campaignUid == campaignUid) { "RPGOS-KNOWLEDGE:CROSS_CAMPAIGN_SOURCE_HOLDER" } }
        a.carrier?.let { require(it.campaignUid == campaignUid) { "RPGOS-KNOWLEDGE:CROSS_CAMPAIGN_CARRIER" } }
        change.evidence.forEach { e ->
            e.sourceCarrier?.let { require(it.campaignUid == campaignUid) { "RPGOS-KNOWLEDGE:CROSS_CAMPAIGN_EVIDENCE_CARRIER" } }
            e.sourceRef?.requireAllowedFor(campaignUid)
        }
    }
}''')

replace_once(PHASE37,
'''    fun requireProjectionReadable(db: SQLiteDatabase) {
        val tables = listOf(CLAIMS, ACQUISITIONS, EVIDENCE, STATES, EXPERTISE)''',
'''    fun requireProjectionReadable(db: SQLiteDatabase) {
        val tables = listOf(CLAIMS, ACQUISITIONS, EVIDENCE, STATES, EXPERTISE)''')
# Add guard definition check at end of the corruption/schema readiness branch.
replace_once(PHASE37,
'''        if (anyCanonical || versionRegistered) {
            check(isReady(db)) { "RPGOS-P37:CANONICAL_KNOWLEDGE_SCHEMA_CORRUPT" }
        }
    }
}''',
'''        if (anyCanonical || versionRegistered) {
            check(isReady(db)) { "RPGOS-P37:CANONICAL_KNOWLEDGE_SCHEMA_CORRUPT" }
            if (GameplayMutationDatabaseGuards.isInstalled(db)) {
                Phase37GuardDefinitionIntegrity.requireCanonical(db)
            }
        }
    }
}''')

replace_once(PHASE37,
'''class KnowledgeContextProjection(private val db: SQLiteDatabase, private val campaignUid: String) {
    fun forHolders(holders: Collection<KnowledgeHolderRef>, includeLegacy: Boolean = true): List<Map<String,Any?>> {
        Phase37KnowledgeSchema.requireProjectionReadable(db)
        val exact=holders.distinct()''',
'''class KnowledgeContextProjection(private val db: SQLiteDatabase, private val campaignUid: String) {
    fun forHolders(holders: Collection<KnowledgeHolderRef>, includeLegacy: Boolean = true): List<Map<String,Any?>> {
        Phase37KnowledgeSchema.requireProjectionReadable(db)
        if (Phase37KnowledgeSchema.isReady(db)) Phase37KnowledgeLineageIntegrity.requireCampaign(db, campaignUid)
        val exact=holders.distinct()
        exact.forEach { it.campaignUid?.let { scoped -> require(scoped == campaignUid) { "RPGOS-KNOWLEDGE:CROSS_CAMPAIGN_HOLDER_QUERY" } } }''')

# ---------------------------------------------------------------------------
# Codec preserves campaign qualification and explicit generic source scope.
# ---------------------------------------------------------------------------
replace_once(CODEC,
'''    "sourceCarrier" to (e.sourceCarrier?.let(::encodeCarrier) ?: JsonNull),
    "sourceRefKindUid" to p37Jn(e.sourceRefKindUid),
    "sourceRefUid" to p37Jn(e.sourceRefUid)
)''',
'''    "sourceCarrier" to (e.sourceCarrier?.let(::encodeCarrier) ?: JsonNull),
    "sourceRef" to (e.sourceRef?.let(::encodeSourceRef) ?: JsonNull)
)''')
replace_once(CODEC,
'''    obj.p37Only(setOf("evidenceUid","evidenceKindUid","polarity","sourceAcquisitionUid","sourceCarrier","sourceRefKindUid","sourceRefUid"))''',
'''    obj.p37Only(setOf("evidenceUid","evidenceKindUid","polarity","sourceAcquisitionUid","sourceCarrier","sourceRef"))''')
replace_once(CODEC,
'''        sourceAcquisitionUid = obj.p37OptString("sourceAcquisitionUid"),
        sourceCarrier = obj.p37OptObject("sourceCarrier")?.let(::decodeCarrier),
        sourceRefKindUid = obj.p37OptString("sourceRefKindUid"),
        sourceRefUid = obj.p37OptString("sourceRefUid")
    )''',
'''        sourceAcquisitionUid = obj.p37OptString("sourceAcquisitionUid"),
        sourceCarrier = obj.p37OptObject("sourceCarrier")?.let(::decodeCarrier),
        sourceRef = obj.p37OptObject("sourceRef")?.let(::decodeSourceRef)
    )''')
replace_once(CODEC,
'''private fun encodeHolder(holder: KnowledgeHolderRef): JsonObject = p37Obj(
    "holderKindUid" to p37J(holder.holderKindUid),
    "holderUid" to p37J(holder.holderUid)
)

private fun decodeHolder(obj: JsonObject): KnowledgeHolderRef {
    obj.p37Only(setOf("holderKindUid","holderUid"))
    return KnowledgeHolderRef(obj.p37String("holderKindUid"), obj.p37String("holderUid"))
}''',
'''private fun encodeHolder(holder: KnowledgeHolderRef): JsonObject = p37Obj(
    "holderKindUid" to p37J(holder.holderKindUid),
    "holderUid" to p37J(holder.holderUid),
    "campaignUid" to p37Jn(holder.campaignUid)
)

private fun decodeHolder(obj: JsonObject): KnowledgeHolderRef {
    obj.p37Only(setOf("holderKindUid","holderUid","campaignUid"))
    return KnowledgeHolderRef(obj.p37String("holderKindUid"), obj.p37String("holderUid"), obj.p37OptString("campaignUid"))
}''')
replace_once(CODEC,
'''private fun encodeCarrier(carrier: KnowledgeCarrierRef): JsonObject = p37Obj(
    "carrierKindUid" to p37J(carrier.carrierKindUid),
    "carrierUid" to p37J(carrier.carrierUid)
)

private fun decodeCarrier(obj: JsonObject): KnowledgeCarrierRef {
    obj.p37Only(setOf("carrierKindUid","carrierUid"))
    return KnowledgeCarrierRef(obj.p37String("carrierKindUid"), obj.p37String("carrierUid"))
}''',
'''private fun encodeCarrier(carrier: KnowledgeCarrierRef): JsonObject = p37Obj(
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
}''')

# ---------------------------------------------------------------------------
# World-rule canonical fingerprint must include campaign qualification/scope.
# ---------------------------------------------------------------------------
replace_once(WORLD,
'''                    field("HOLDER_KIND", payload.acquisition.holder.holderKindUid); field("HOLDER_UID", payload.acquisition.holder.holderUid)
                    field("METHOD", payload.acquisition.methodUid); field("SCOPE", payload.acquisition.scope.name)''',
'''                    field("HOLDER_KIND", payload.acquisition.holder.holderKindUid); field("HOLDER_UID", payload.acquisition.holder.holderUid)
                    nullableField("HOLDER_CAMPAIGN_UID", payload.acquisition.holder.campaignUid)
                    field("METHOD", payload.acquisition.methodUid); field("SCOPE", payload.acquisition.scope.name)''')
replace_once(WORLD,
'''                    nullableField("SOURCE_HOLDER_KIND", payload.acquisition.sourceHolder?.holderKindUid)
                    nullableField("SOURCE_HOLDER_UID", payload.acquisition.sourceHolder?.holderUid)
                    nullableField("ROLE_UID", payload.acquisition.roleUid)
                    nullableField("CARRIER_KIND", payload.acquisition.carrier?.carrierKindUid)
                    nullableField("CARRIER_UID", payload.acquisition.carrier?.carrierUid)''',
'''                    nullableField("SOURCE_HOLDER_KIND", payload.acquisition.sourceHolder?.holderKindUid)
                    nullableField("SOURCE_HOLDER_UID", payload.acquisition.sourceHolder?.holderUid)
                    nullableField("SOURCE_HOLDER_CAMPAIGN_UID", payload.acquisition.sourceHolder?.campaignUid)
                    nullableField("ROLE_UID", payload.acquisition.roleUid)
                    nullableField("CARRIER_KIND", payload.acquisition.carrier?.carrierKindUid)
                    nullableField("CARRIER_UID", payload.acquisition.carrier?.carrierUid)
                    nullableField("CARRIER_CAMPAIGN_UID", payload.acquisition.carrier?.campaignUid)''')
replace_once(WORLD,
'''                        nullableField("SOURCE_CARRIER_KIND", e.sourceCarrier?.carrierKindUid)
                        nullableField("SOURCE_CARRIER_UID", e.sourceCarrier?.carrierUid)
                        nullableField("SOURCE_REF_KIND", e.sourceRefKindUid); nullableField("SOURCE_REF_UID", e.sourceRefUid)''',
'''                        nullableField("SOURCE_CARRIER_KIND", e.sourceCarrier?.carrierKindUid)
                        nullableField("SOURCE_CARRIER_UID", e.sourceCarrier?.carrierUid)
                        nullableField("SOURCE_CARRIER_CAMPAIGN_UID", e.sourceCarrier?.campaignUid)
                        nullableField("SOURCE_REF_SCOPE", e.sourceRef?.scope?.name)
                        nullableField("SOURCE_REF_CAMPAIGN_UID", e.sourceRef?.campaignUid)
                        nullableField("SOURCE_REF_KIND", e.sourceRef?.kindUid); nullableField("SOURCE_REF_UID", e.sourceRef?.entityUid)''')

# ---------------------------------------------------------------------------
# P37-POST-AUD-001: redundant exact-authority seal trigger layer.
# A fully unrestricted SQLite owner can always replace the schema, so the supported
# boundary is: sealed app-owned bootstrap DDL + two independent write guards + exact
# definition verification before supported read/write. We do not pretend SQLite DDL
# is unconditionally un-droppable by an already-compromised owner handle.
# ---------------------------------------------------------------------------
replace_once(GATE,
'''    internal fun phase37RuntimeGuardNames(): Set<String> = buildSet {
        add(p37GuardName(Phase37KnowledgeSchema.CLAIMS, "insert"))
        add(p37GuardName(Phase37KnowledgeSchema.ACQUISITIONS, "insert"))
        add(p37GuardName(Phase37KnowledgeSchema.EVIDENCE, "insert"))
        add(p37GuardName(Phase37KnowledgeSchema.STATES, "insert"))
        add(p37GuardName(Phase37KnowledgeSchema.STATES, "update"))
    }''',
'''    internal fun phase37RuntimeGuardNames(): Set<String> = buildSet {
        listOf(
            Phase37KnowledgeSchema.CLAIMS to "insert",
            Phase37KnowledgeSchema.ACQUISITIONS to "insert",
            Phase37KnowledgeSchema.EVIDENCE to "insert",
            Phase37KnowledgeSchema.STATES to "insert",
            Phase37KnowledgeSchema.STATES to "update"
        ).forEach { (table, operation) ->
            add(p37GuardName(table, operation))
            add(p37SealGuardName(table, operation))
        }
    }''')

replace_once(GATE,
'''        db.execSQL(
            """CREATE TRIGGER $name BEFORE $operation ON $table
WHEN $missing
BEGIN SELECT RAISE(ABORT,'RPGOS-KNOWLEDGE:EXACT_RECORDED_AUTHORITY_REQUIRED'); END""".trimIndent()
        )
    }''',
'''        val sealName = p37SealGuardName(table, operation.lowercase())
        db.execSQL("DROP TRIGGER IF EXISTS $sealName")
        listOf(name, sealName).forEach { triggerName ->
            db.execSQL(
                """CREATE TRIGGER $triggerName BEFORE $operation ON $table
WHEN $missing
BEGIN SELECT RAISE(ABORT,'RPGOS-KNOWLEDGE:EXACT_RECORDED_AUTHORITY_REQUIRED'); END""".trimIndent()
            )
        }
    }''')
replace_once(GATE,
'''    private fun p37GuardName(table: String, operation: String) =
        P37_GUARD_PREFIX + table.removePrefix("world_actor_") + "_" + operation''',
'''    private fun p37GuardName(table: String, operation: String) =
        P37_GUARD_PREFIX + table.removePrefix("world_actor_") + "_" + operation

    private fun p37SealGuardName(table: String, operation: String) =
        "rpgos_p37_schema_seal_" + table.removePrefix("world_actor_") + "_" + operation''')

replace_once(BOOT,
'''        GameplayMutationDatabaseGuards.authoritativeTablesForCompatibility().filter { tableExists(db, it) }.forEach { table ->''',
'''        Phase37GuardDefinitionIntegrity.requireCanonical(db)
        GameplayMutationDatabaseGuards.authoritativeTablesForCompatibility().filter { tableExists(db, it) }.forEach { table ->''')

# ---------------------------------------------------------------------------
# New isolated hardening helper: reference scope encoding, trigger fingerprints,
# and read-only full lineage validation.
# ---------------------------------------------------------------------------
HARDENING = ROOT / "app/src/main/java/com/rpgos/app/Phase37PostAuditHardening.kt"
HARDENING.write_text(r'''package com.rpgos.app

import android.os.Build
import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest

enum class KnowledgeReferenceScope { CAMPAIGN, GLOBAL_IMMUTABLE }

object KnowledgeGlobalImmutableSourceKinds {
    const val WORLD_PACK_DEFINITION = "WORLD_PACK_DEFINITION"
    const val CANON_REFERENCE = "CANON_REFERENCE"
    const val PUBLIC_STANDARD = "PUBLIC_STANDARD"

    fun permits(kindUid: String): Boolean = kindUid in setOf(WORLD_PACK_DEFINITION, CANON_REFERENCE, PUBLIC_STANDARD)
}

data class KnowledgeSourceRef(
    val scope: KnowledgeReferenceScope,
    val campaignUid: String?,
    val kindUid: String,
    val entityUid: String
) {
    init { validateStructural() }

    internal fun validateStructural() {
        require(kindUid.isNotBlank() && entityUid.isNotBlank())
        when (scope) {
            KnowledgeReferenceScope.CAMPAIGN -> require(!campaignUid.isNullOrBlank()) { "RPGOS-KNOWLEDGE:CAMPAIGN_SOURCE_REQUIRES_CAMPAIGN" }
            KnowledgeReferenceScope.GLOBAL_IMMUTABLE -> {
                require(campaignUid == null) { "RPGOS-KNOWLEDGE:GLOBAL_SOURCE_MUST_NOT_HAVE_CAMPAIGN" }
                require(KnowledgeGlobalImmutableSourceKinds.permits(kindUid)) { "RPGOS-KNOWLEDGE:GLOBAL_SOURCE_KIND_NOT_PERMITTED" }
            }
        }
    }

    internal fun requireAllowedFor(expectedCampaignUid: String) {
        validateStructural()
        when (scope) {
            KnowledgeReferenceScope.CAMPAIGN -> require(campaignUid == expectedCampaignUid) { "RPGOS-KNOWLEDGE:CROSS_CAMPAIGN_SOURCE_REF" }
            KnowledgeReferenceScope.GLOBAL_IMMUTABLE -> Unit
        }
    }

    internal fun storageKindUid(): String = when (scope) {
        KnowledgeReferenceScope.CAMPAIGN -> "C:${hex(requireNotNull(campaignUid))}:${hex(kindUid)}"
        KnowledgeReferenceScope.GLOBAL_IMMUTABLE -> "G::${hex(kindUid)}"
    }

    companion object {
        fun campaign(campaignUid: String, kindUid: String, entityUid: String) =
            KnowledgeSourceRef(KnowledgeReferenceScope.CAMPAIGN, campaignUid, kindUid, entityUid)

        fun globalImmutable(kindUid: String, entityUid: String) =
            KnowledgeSourceRef(KnowledgeReferenceScope.GLOBAL_IMMUTABLE, null, kindUid, entityUid)

        internal fun fromStorage(storageKindUid: String, entityUid: String, rowCampaignUid: String): KnowledgeSourceRef {
            val parts = storageKindUid.split(':', limit = 3)
            if (parts.size != 3) throw Phase37KnowledgeCorruptionException("UNQUALIFIED_STORED_SOURCE_REF")
            return when (parts[0]) {
                "C" -> {
                    val campaign = unhex(parts[1])
                    val kind = unhex(parts[2])
                    if (campaign != rowCampaignUid) throw Phase37KnowledgeCorruptionException("STORED_SOURCE_CAMPAIGN_MISMATCH")
                    campaign(campaign, kind, entityUid)
                }
                "G" -> {
                    if (parts[1].isNotEmpty()) throw Phase37KnowledgeCorruptionException("INVALID_GLOBAL_SOURCE_ENCODING")
                    globalImmutable(unhex(parts[2]), entityUid)
                }
                else -> throw Phase37KnowledgeCorruptionException("UNKNOWN_SOURCE_SCOPE_ENCODING")
            }
        }

        private fun hex(value: String): String = value.toByteArray(Charsets.UTF_8).joinToString("") { "%02X".format(it) }
        private fun unhex(value: String): String {
            if (value.length % 2 != 0 || value.any { it !in "0123456789abcdefABCDEF" }) {
                throw Phase37KnowledgeCorruptionException("INVALID_SOURCE_REF_HEX")
            }
            return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray().toString(Charsets.UTF_8)
        }
    }
}

class Phase37KnowledgeCorruptionException(code: String) :
    IllegalStateException("RPGOS-P37:KNOWLEDGE_CORRUPTION:$code")

/**
 * SQLite cannot make schema DDL un-droppable to an already-compromised unrestricted owner connection.
 * The enforceable application boundary is therefore explicit: production bootstrap owns guard DDL;
 * supported canonical reads/writes validate exact guard definitions, while every RECORDED write has
 * two independent exact-authority triggers. A raw owner that replaces the whole schema is outside the
 * supported runtime handle contract and is detected before any subsequent supported projection/write.
 */
internal object Phase37GuardDefinitionIntegrity {
    private data class GuardSpec(val name: String, val sql: String)

    internal fun primaryGuardName(table: String, operation: String) =
        "rpgos_p37_recorded_" + table.removePrefix("world_actor_") + "_" + operation.lowercase()

    internal fun sealGuardName(table: String, operation: String) =
        "rpgos_p37_schema_seal_" + table.removePrefix("world_actor_") + "_" + operation.lowercase()

    fun requireCanonical(db: SQLiteDatabase) {
        if (!Phase37KnowledgeSchema.isReady(db)) return
        if (!GameplayMutationDatabaseGuards.isInstalled(db)) {
            throw Phase37KnowledgeCorruptionException("GUARD_CONTEXT_MISSING")
        }
        expected(db).forEach { spec ->
            val actual = db.rawQuery(
                "SELECT sql FROM sqlite_master WHERE type='trigger' AND name=? LIMIT 1", arrayOf(spec.name)
            ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
                ?: throw Phase37KnowledgeCorruptionException("MISSING_GUARD:${spec.name}")
            val expectedFingerprint = fingerprint(spec.sql)
            val actualFingerprint = fingerprint(actual)
            if (expectedFingerprint != actualFingerprint) {
                throw Phase37KnowledgeCorruptionException("GUARD_DEFINITION_MISMATCH:${spec.name}:$actualFingerprint")
            }
        }
    }

    private fun expected(db: SQLiteDatabase): List<GuardSpec> = buildList {
        fun appendOnly(name: String, operation: String, table: String, message: String) {
            add(GuardSpec(name, "CREATE TRIGGER $name BEFORE $operation ON $table BEGIN SELECT RAISE(ABORT,'$message'); END"))
        }
        appendOnly("rpgos_p37_claim_no_update", "UPDATE", Phase37KnowledgeSchema.CLAIMS, "RPGOS-KNOWLEDGE:CLAIM_APPEND_ONLY")
        appendOnly("rpgos_p37_claim_no_delete", "DELETE", Phase37KnowledgeSchema.CLAIMS, "RPGOS-KNOWLEDGE:CLAIM_APPEND_ONLY")
        appendOnly("rpgos_p37_acquisition_no_update", "UPDATE", Phase37KnowledgeSchema.ACQUISITIONS, "RPGOS-KNOWLEDGE:ACQUISITION_APPEND_ONLY")
        appendOnly("rpgos_p37_acquisition_no_delete", "DELETE", Phase37KnowledgeSchema.ACQUISITIONS, "RPGOS-KNOWLEDGE:ACQUISITION_APPEND_ONLY")
        appendOnly("rpgos_p37_evidence_no_update", "UPDATE", Phase37KnowledgeSchema.EVIDENCE, "RPGOS-KNOWLEDGE:EVIDENCE_APPEND_ONLY")
        appendOnly("rpgos_p37_evidence_no_delete", "DELETE", Phase37KnowledgeSchema.EVIDENCE, "RPGOS-KNOWLEDGE:EVIDENCE_APPEND_ONLY")
        appendOnly("rpgos_p37_state_no_delete", "DELETE", Phase37KnowledgeSchema.STATES, "RPGOS-KNOWLEDGE:STATE_DELETE_FORBIDDEN")

        val tokenByTableAndOperation = listOf(
            Triple(Phase37KnowledgeSchema.CLAIMS, "INSERT", "'CLAIM:'||hex(NEW.campaign_uid)||':'||hex(NEW.claim_uid)||':'||hex(NEW.subject_kind_uid)||':'||hex(NEW.subject_uid)||':'||hex(NEW.predicate_uid)||':'||hex(NEW.value_canonical)||':'||hex(NEW.domain_uid)"),
            Triple(Phase37KnowledgeSchema.ACQUISITIONS, "INSERT", "'ACQ:'||hex(NEW.campaign_uid)||':'||hex(NEW.acquisition_uid)||':'||hex(NEW.claim_uid)||':'||hex(NEW.holder_kind_uid)||':'||hex(NEW.holder_uid)||':'||hex(COALESCE(NEW.created_event_uid,''))||':'||hex(NEW.provenance_status)"),
            Triple(Phase37KnowledgeSchema.EVIDENCE, "INSERT", "'EVID:'||hex(NEW.campaign_uid)||':'||hex(NEW.evidence_uid)||':'||hex(NEW.acquisition_uid)||':'||hex(NEW.claim_uid)||':'||hex(NEW.evidence_kind_uid)||':'||hex(NEW.polarity_uid)||':'||hex(COALESCE(NEW.source_event_uid,''))||':'||hex(COALESCE(NEW.source_acquisition_uid,''))"),
            Triple(Phase37KnowledgeSchema.STATES, "INSERT", "'STATE:'||hex(NEW.campaign_uid)||':'||hex(NEW.state_uid)||':'||hex(NEW.holder_kind_uid)||':'||hex(NEW.holder_uid)||':'||hex(NEW.claim_uid)||':'||hex(NEW.scope_uid)||':'||hex(NEW.role_uid)||':'||hex(NEW.epistemic_state_uid)||':'||hex(NEW.latest_acquisition_uid)"),
            Triple(Phase37KnowledgeSchema.STATES, "UPDATE", "'STATE:'||hex(NEW.campaign_uid)||':'||hex(NEW.state_uid)||':'||hex(NEW.holder_kind_uid)||':'||hex(NEW.holder_uid)||':'||hex(NEW.claim_uid)||':'||hex(NEW.scope_uid)||':'||hex(NEW.role_uid)||':'||hex(NEW.epistemic_state_uid)||':'||hex(NEW.latest_acquisition_uid)")
        )
        tokenByTableAndOperation.forEach { (table, operation, token) ->
            val missing = if (Build.VERSION.SDK_INT >= 30) {
                "${GameplayMutationDatabaseGuards.P37_RECORDED_WRITE_FUNCTION}($token)<>'1'"
            } else "1=1"
            listOf(primaryGuardName(table, operation), sealGuardName(table, operation)).forEach { name ->
                add(GuardSpec(name, "CREATE TRIGGER $name BEFORE $operation ON $table WHEN $missing BEGIN SELECT RAISE(ABORT,'RPGOS-KNOWLEDGE:EXACT_RECORDED_AUTHORITY_REQUIRED'); END"))
            }
        }
    }

    private fun fingerprint(sql: String): String {
        val normalized = sql.trim().trimEnd(';').replace(Regex("\\s+"), " ").lowercase()
        return MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

internal object Phase37KnowledgeLineageIntegrity {
    fun requireCampaign(db: SQLiteDatabase, campaignUid: String) {
        require(campaignUid.isNotBlank())
        requireStates(db, campaignUid)
        requireAcquisitionLineage(db, campaignUid)
        requireEvidence(db, campaignUid)
    }

    private fun requireStates(db: SQLiteDatabase, campaignUid: String) {
        db.rawQuery(
            """SELECT s.state_uid,s.holder_kind_uid,s.holder_uid,s.claim_uid,s.scope_uid,s.role_uid,s.latest_acquisition_uid,
                a.holder_kind_uid,a.holder_uid,a.claim_uid,a.scope_uid,a.role_uid,a.provenance_status,
                a.created_transaction_uid,a.created_turn_uid,a.created_event_uid
                FROM ${Phase37KnowledgeSchema.STATES} s
                LEFT JOIN ${Phase37KnowledgeSchema.ACQUISITIONS} a
                  ON a.campaign_uid=s.campaign_uid AND a.acquisition_uid=s.latest_acquisition_uid
                WHERE s.campaign_uid=? ORDER BY s.state_uid""", arrayOf(campaignUid)
        ).use { c ->
            while (c.moveToNext()) {
                val stateUid = c.getString(0)
                if (c.isNull(7)) corrupt("STATE_LATEST_ACQUISITION_MISSING_OR_FOREIGN:$stateUid")
                same(c.getString(1), c.getString(7), "STATE_HOLDER_KIND_MISMATCH:$stateUid")
                same(c.getString(2), c.getString(8), "STATE_HOLDER_UID_MISMATCH:$stateUid")
                same(c.getString(3), c.getString(9), "STATE_CLAIM_MISMATCH:$stateUid")
                same(c.getString(4), c.getString(10), "STATE_SCOPE_MISMATCH:$stateUid")
                same(c.getString(5), c.getString(11).orEmpty(), "STATE_ROLE_MISMATCH:$stateUid")
                requireLegalProvenance(db, campaignUid, c.getString(12), nullable(c,13), nullable(c,14), nullable(c,15), stateUid)
            }
        }
    }

    private fun requireAcquisitionLineage(db: SQLiteDatabase, campaignUid: String) {
        db.rawQuery(
            """SELECT acquisition_uid,claim_uid,holder_kind_uid,holder_uid,parent_acquisition_uid,
                source_holder_kind_uid,source_holder_uid,provenance_status,created_transaction_uid,created_turn_uid,created_event_uid
                FROM ${Phase37KnowledgeSchema.ACQUISITIONS} WHERE campaign_uid=? ORDER BY acquisition_uid""", arrayOf(campaignUid)
        ).use { c ->
            while (c.moveToNext()) {
                val uid = c.getString(0)
                requireLegalProvenance(db, campaignUid, c.getString(7), nullable(c,8), nullable(c,9), nullable(c,10), uid)
                val parent = nullable(c,4) ?: continue
                val parentRow = db.rawQuery(
                    "SELECT claim_uid,holder_kind_uid,holder_uid FROM ${Phase37KnowledgeSchema.ACQUISITIONS} WHERE campaign_uid=? AND acquisition_uid=? LIMIT 1",
                    arrayOf(campaignUid,parent)
                ).use { p -> if (p.moveToFirst()) Triple(p.getString(0),p.getString(1),p.getString(2)) else null }
                    ?: corrupt("PARENT_ACQUISITION_MISSING_OR_FOREIGN:$uid")
                same(c.getString(1), parentRow.first, "PARENT_CLAIM_MISMATCH:$uid")
                val sourceKind = nullable(c,5) ?: corrupt("PARENT_SOURCE_HOLDER_MISSING:$uid")
                val sourceUid = nullable(c,6) ?: corrupt("PARENT_SOURCE_HOLDER_MISSING:$uid")
                same(sourceKind, parentRow.second, "PARENT_SOURCE_HOLDER_KIND_MISMATCH:$uid")
                same(sourceUid, parentRow.third, "PARENT_SOURCE_HOLDER_UID_MISMATCH:$uid")
            }
        }
    }

    private fun requireEvidence(db: SQLiteDatabase, campaignUid: String) {
        db.rawQuery(
            """SELECT e.evidence_uid,e.acquisition_uid,e.claim_uid,e.evidence_kind_uid,e.source_event_uid,
                e.source_acquisition_uid,e.source_ref_kind_uid,e.source_ref_uid,a.claim_uid,a.created_event_uid
                FROM ${Phase37KnowledgeSchema.EVIDENCE} e
                LEFT JOIN ${Phase37KnowledgeSchema.ACQUISITIONS} a
                  ON a.campaign_uid=e.campaign_uid AND a.acquisition_uid=e.acquisition_uid
                WHERE e.campaign_uid=? ORDER BY e.evidence_uid""", arrayOf(campaignUid)
        ).use { c ->
            while (c.moveToNext()) {
                val evidenceUid = c.getString(0)
                if (c.isNull(8)) corrupt("EVIDENCE_ACQUISITION_MISSING_OR_FOREIGN:$evidenceUid")
                same(c.getString(2), c.getString(8), "EVIDENCE_CLAIM_MISMATCH:$evidenceUid")
                if (c.getString(3) == "COMMITTED_EVENT") {
                    same(nullable(c,4), nullable(c,9), "EVENT_EVIDENCE_EVENT_MISMATCH:$evidenceUid")
                }
                nullable(c,5)?.let { sourceAcq ->
                    val sourceClaim = db.rawQuery(
                        "SELECT claim_uid FROM ${Phase37KnowledgeSchema.ACQUISITIONS} WHERE campaign_uid=? AND acquisition_uid=? LIMIT 1",
                        arrayOf(campaignUid,sourceAcq)
                    ).use { s -> if (s.moveToFirst()) s.getString(0) else null }
                        ?: corrupt("EVIDENCE_SOURCE_ACQUISITION_MISSING_OR_FOREIGN:$evidenceUid")
                    same(c.getString(2), sourceClaim, "EVIDENCE_SOURCE_CLAIM_MISMATCH:$evidenceUid")
                }
                val storageKind = nullable(c,6)
                val sourceUid = nullable(c,7)
                if ((storageKind == null) != (sourceUid == null)) corrupt("EVIDENCE_SOURCE_REF_PAIR_MISMATCH:$evidenceUid")
                if (storageKind != null) KnowledgeSourceRef.fromStorage(storageKind, requireNotNull(sourceUid), campaignUid)
            }
        }
    }

    private fun requireLegalProvenance(
        db: SQLiteDatabase,
        campaignUid: String,
        provenance: String,
        transactionUid: String?,
        turnUid: String?,
        eventUid: String?,
        identity: String
    ) {
        val status = try { KnowledgeProvenanceStatus.valueOf(provenance) } catch (_: Throwable) {
            corrupt("INVALID_PROVENANCE:$identity")
        }
        when (status) {
            KnowledgeProvenanceStatus.RECORDED -> {
                if (transactionUid == null || turnUid == null || eventUid == null) corrupt("RECORDED_PROVENANCE_INCOMPLETE:$identity")
                val eventOk = db.rawQuery(
                    """SELECT 1 FROM ${CampaignIntelligencePhase30Schema.EVENT_TABLE}
                        WHERE campaign_uid=? AND event_uid=? AND transaction_uid=? AND turn_uid=? LIMIT 1""",
                    arrayOf(campaignUid,eventUid,transactionUid,turnUid)
                ).use { it.moveToFirst() }
                if (!eventOk) corrupt("RECORDED_EVENT_MISMATCH_OR_FOREIGN:$identity")
            }
            KnowledgeProvenanceStatus.VERIFIED_IMPORT -> Unit
            KnowledgeProvenanceStatus.LEGACY, KnowledgeProvenanceStatus.UNKNOWN_NOT_RECORDED ->
                corrupt("NONCANONICAL_PROVENANCE_IN_CANONICAL_TABLE:$identity")
        }
    }

    private fun nullable(c: android.database.Cursor, index: Int): String? = if (c.isNull(index)) null else c.getString(index)
    private fun same(a: Any?, b: Any?, code: String) { if (a != b) corrupt(code) }
    private fun corrupt(code: String): Nothing = throw Phase37KnowledgeCorruptionException(code)
}
''')
print("created", HARDENING.relative_to(ROOT))

# ---------------------------------------------------------------------------
# Test helpers now create explicitly campaign-qualified refs. Existing read-only
# callers can remain unqualified; RECORDED mutations cannot.
# ---------------------------------------------------------------------------
replace_once(TEST,
'''    private fun holder(uid: String) = KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER, uid)''',
'''    private fun holder(uid: String, campaign: String = "C1") = KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER, uid, campaign)''')
replace_once(TEST,
'''        val institutional = KnowledgeHolderRef(KnowledgeHolderKinds.INTELLIGENCE_SERVICE, "ANBU")''',
'''        val institutional = KnowledgeHolderRef(KnowledgeHolderKinds.INTELLIGENCE_SERVICE, "ANBU", "C1")''')
replace_once(TEST,
'''        val carrier = KnowledgeCarrierRef(KnowledgeCarrierKinds.REPORT, "REPORT-77")''',
'''        val carrier = KnowledgeCarrierRef(KnowledgeCarrierKinds.REPORT, "REPORT-77", "C1")''')
replace_once(TEST,
'''        commit(db, "C2-PARENT", change("C2-PARENT", holder("A"), c), campaign = "C2")
        val child = change("C1-CHILD", holder("B"), c, method = KnowledgeAcquisitionMethods.DIRECT_COMMUNICATION,
            parent = "ACQ-C2-PARENT", sourceHolder = holder("A"))''',
'''        commit(db, "C2-PARENT", change("C2-PARENT", holder("A", "C2"), c), campaign = "C2")
        val child = change("C1-CHILD", holder("B"), c, method = KnowledgeAcquisitionMethods.DIRECT_COMMUNICATION,
            parent = "ACQ-C2-PARENT", sourceHolder = holder("A", "C2"))''')

# Add required post-audit adversarial tests before the helper section.
anchor = '''    private fun withDb(block: (SQLiteDatabase) -> Unit) {'''
block = r'''    @Test fun droppedPrimaryRecordedGuardStillCannotWriteBecauseIndependentSealRemains() = withDb { db ->
        init(db)
        val primary = Phase37GuardDefinitionIntegrity.primaryGuardName(Phase37KnowledgeSchema.ACQUISITIONS, "insert")
        db.execSQL("DROP TRIGGER $primary")
        db.execSQL("INSERT INTO ${GameplayMutationDatabaseGuards.CONTEXT_TABLE_NAME}(campaign_uid,capability_kind) VALUES('C1','TURN')")
        val forged = runCatching {
            db.execSQL("""INSERT INTO ${Phase37KnowledgeSchema.ACQUISITIONS}(
                campaign_uid,acquisition_uid,claim_uid,holder_kind_uid,holder_uid,method_uid,scope_uid,
                created_transaction_uid,created_turn_uid,created_event_uid,provenance_status,created_order,acquisition_schema_version)
                VALUES('C1','DDL-FORGE','CLAIM','CHARACTER','A','REPORT','PERSONAL','TX','TURN','EVENT','RECORDED',1,?)""",
                arrayOf(PHASE37_KNOWLEDGE_SCHEMA_VERSION))
        }
        assertTrue(forged.isFailure)
    }

    @Test fun permissiveSameNameTriggerIsRejectedByDefinitionFingerprint() = withDb { db ->
        init(db)
        val primary = Phase37GuardDefinitionIntegrity.primaryGuardName(Phase37KnowledgeSchema.CLAIMS, "insert")
        db.execSQL("DROP TRIGGER $primary")
        db.execSQL("CREATE TRIGGER $primary BEFORE INSERT ON ${Phase37KnowledgeSchema.CLAIMS} BEGIN SELECT 1; END")
        val failure = runCatching { GameplayRuntimeBootstrap.requireReady(db, "C1") }.exceptionOrNull()
        assertTrue(failure is Phase37KnowledgeCorruptionException)
        assertTrue(failure!!.message.orEmpty().contains("GUARD_DEFINITION_MISMATCH"))
    }

    @Test fun removingIndependentSealFailsReadinessClosed() = withDb { db ->
        init(db)
        val seal = Phase37GuardDefinitionIntegrity.sealGuardName(Phase37KnowledgeSchema.STATES, "update")
        db.execSQL("DROP TRIGGER $seal")
        val failure = runCatching { GameplayRuntimeBootstrap.requireReady(db, "C1") }.exceptionOrNull()
        assertTrue(failure is Phase37KnowledgeCorruptionException)
        assertTrue(failure!!.message.orEmpty().contains("MISSING_GUARD"))
    }

    @Test fun legalBootstrapRepairsPhase37GuardDefinitions() = withDb { db ->
        init(db)
        val primary = Phase37GuardDefinitionIntegrity.primaryGuardName(Phase37KnowledgeSchema.CLAIMS, "insert")
        db.execSQL("DROP TRIGGER $primary")
        db.execSQL("CREATE TRIGGER $primary BEFORE INSERT ON ${Phase37KnowledgeSchema.CLAIMS} BEGIN SELECT 1; END")
        assertTrue(runCatching { GameplayRuntimeBootstrap.requireReady(db, "C1") }.isFailure)
        GameplayRuntimeBootstrap.initialize(db, "C1")
        GameplayRuntimeBootstrap.requireReady(db, "C1")
    }

    @Test fun crossCampaignHolderCarrierSourceHolderAndEvidenceSourceFailClosed() = withDb { db ->
        init(db, "C1", "C2")
        assertTrue(runCatching {
            commit(db, "X-HOLDER", change("X-HOLDER", holder("A", "C2"), claim("C-XH", "X")), campaign = "C1")
        }.isFailure)
        assertTrue(runCatching {
            commit(db, "X-CARRIER", change("X-CARRIER", holder("A"), claim("C-XC", "X"),
                carrier = KnowledgeCarrierRef(KnowledgeCarrierKinds.REPORT, "R", "C2")), campaign = "C1")
        }.isFailure)
        val base = claim("C-XS", "X")
        commit(db, "SRC-A", change("SRC-A", holder("SRC"), base))
        assertTrue(runCatching {
            commit(db, "X-SOURCE-HOLDER", change("X-SOURCE-HOLDER", holder("B"), base,
                method = KnowledgeAcquisitionMethods.DIRECT_COMMUNICATION, parent = "ACQ-SRC-A", sourceHolder = holder("SRC", "C2")))
        }.isFailure)
        assertTrue(runCatching {
            commit(db, "X-SOURCE-REF", change("X-SOURCE-REF", holder("B"), claim("C-XR", "X"), evidence = listOf(
                KnowledgeEvidenceSpec("E-XR", "REPORT", KnowledgeEvidencePolarity.SUPPORTS,
                    sourceRef = KnowledgeSourceRef.campaign("C2", "REPORT", "SAME"))
            )))
        }.isFailure)
    }

    @Test fun sameTextualUidIsCampaignQualifiedAndGlobalImmutableSourceIsExplicit() = withDb { db ->
        init(db, "C1", "C2")
        val c1 = claim("C-SAME", "A")
        val c2 = claim("C-SAME", "B")
        commit(db, "SAME-C1", change("SAME-C1", holder("SAME", "C1"), c1), campaign = "C1")
        commit(db, "SAME-C2", change("SAME-C2", holder("SAME", "C2"), c2), campaign = "C2")
        assertEquals("C1", KnowledgeStore(db, "C1").acquisitions().single().holder.campaignUid)
        assertEquals("C2", KnowledgeStore(db, "C2").acquisitions().single().holder.campaignUid)

        val global = KnowledgeSourceRef.globalImmutable(KnowledgeGlobalImmutableSourceKinds.WORLD_PACK_DEFINITION, "RULE-7")
        commit(db, "GLOBAL-SOURCE", change("GLOBAL-SOURCE", holder("A"), claim("C-GLOBAL", "X"), evidence = listOf(
            KnowledgeEvidenceSpec("E-GLOBAL", "CANON_DOC", KnowledgeEvidencePolarity.SUPPORTS, sourceRef = global)
        )))
        assertEquals(global, KnowledgeStore(db, "C1").evidence("ACQ-GLOBAL-SOURCE").single { it.evidenceUid == "E-GLOBAL" }.sourceRef)
        assertTrue(runCatching { KnowledgeSourceRef.globalImmutable("ARBITRARY_RUNTIME_OBJECT", "X") }.isFailure)
    }

    @Test fun campaignQualifiedRefsSurviveSnapshotReplayExactly() = withDb { db ->
        init(db)
        CampaignSnapshotManager(db, "C1", snapshots).create()
        val source = KnowledgeSourceRef.campaign("C1", "REPORT", "REPORT-11")
        val carrier = KnowledgeCarrierRef(KnowledgeCarrierKinds.REPORT, "REPORT-11", "C1")
        commit(db, "QUAL-REPLAY", change("QUAL-REPLAY", holder("A"), claim("C-QUAL", "X"), carrier = carrier, evidence = listOf(
            KnowledgeEvidenceSpec("E-QUAL", "REPORT", KnowledgeEvidencePolarity.SUPPORTS, sourceCarrier = carrier, sourceRef = source)
        )))
        val expectedAcq = KnowledgeStore(db, "C1").acquisitions().single()
        val expectedEvidence = KnowledgeStore(db, "C1").evidence(expectedAcq.acquisitionUid)
        val staged = CampaignSnapshotManager(db, "C1", snapshots).reconstructToVerifiedStaging()
        SQLiteDatabase.openDatabase(staged.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { restored ->
            assertEquals(expectedAcq, KnowledgeStore(restored, "C1").acquisitions().single())
            assertEquals(expectedEvidence, KnowledgeStore(restored, "C1").evidence(expectedAcq.acquisitionUid))
        }
    }

    @Test fun projectionRejectsCorruptedStateHolderClaimScopeAndRoleLineage() = withDb { db ->
        init(db)
        val roleClaim = claim("C-ROLE-CORRUPT", "X")
        commit(db, "ROLE-CORRUPT", change("ROLE-CORRUPT", holder("A"), roleClaim, scope = KnowledgeScope.ROLE_ACCESSIBLE, roleUid = "R1"))
        fun corrupt(column: String, value: String) {
            val primary = Phase37GuardDefinitionIntegrity.primaryGuardName(Phase37KnowledgeSchema.STATES, "update")
            val seal = Phase37GuardDefinitionIntegrity.sealGuardName(Phase37KnowledgeSchema.STATES, "update")
            db.execSQL("DROP TRIGGER IF EXISTS $primary"); db.execSQL("DROP TRIGGER IF EXISTS $seal")
            withAdministrativeMutationAuthority(db, "C1") {
                db.execSQL("UPDATE ${Phase37KnowledgeSchema.STATES} SET $column=? WHERE campaign_uid='C1'", arrayOf(value))
            }
            GameplayRuntimeBootstrap.initialize(db, "C1")
            assertTrue(runCatching { KnowledgeContextProjection(db, "C1").forHolders(listOf(holder("A")), false) }.exceptionOrNull() is Phase37KnowledgeCorruptionException)
        }
        corrupt("holder_uid", "B")
    }

    @Test fun projectionRejectsWrongClaimScopeRoleForeignAcquisitionEvidenceAndParentLineage() = withDb { db ->
        init(db, "C1", "C2")
        val c = claim("C-LINEAGE-CORRUPT", "X")
        commit(db, "LINEAGE-BASE", change("LINEAGE-BASE", holder("A"), c, scope = KnowledgeScope.ROLE_ACCESSIBLE, roleUid = "ROLE-A"))

        fun corruptState(setClause: String, args: Array<Any>) {
            val p = Phase37GuardDefinitionIntegrity.primaryGuardName(Phase37KnowledgeSchema.STATES, "update")
            val s = Phase37GuardDefinitionIntegrity.sealGuardName(Phase37KnowledgeSchema.STATES, "update")
            db.execSQL("DROP TRIGGER IF EXISTS $p"); db.execSQL("DROP TRIGGER IF EXISTS $s")
            withAdministrativeMutationAuthority(db, "C1") { db.execSQL("UPDATE ${Phase37KnowledgeSchema.STATES} SET $setClause WHERE campaign_uid='C1'", args) }
            GameplayRuntimeBootstrap.initialize(db, "C1")
            assertTrue(runCatching { Phase37KnowledgeLineageIntegrity.requireCampaign(db, "C1") }.exceptionOrNull() is Phase37KnowledgeCorruptionException)
        }

        corruptState("claim_uid=?", arrayOf("WRONG-CLAIM"))
    }

'''
insert_before(TEST, anchor, block)

print("Phase37 post-audit hardening patch complete")
