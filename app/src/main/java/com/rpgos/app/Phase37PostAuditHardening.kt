package com.rpgos.app

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
