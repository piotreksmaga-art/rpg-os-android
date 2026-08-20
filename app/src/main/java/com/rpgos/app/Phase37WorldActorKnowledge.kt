package com.rpgos.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

const val PHASE37_KNOWLEDGE_SCHEMA_VERSION = 1

object KnowledgeHolderKinds {
    const val CHARACTER = "CHARACTER"
    const val PLAYER_CHARACTER = "PLAYER_CHARACTER"
    const val ORGANIZATION = "ORGANIZATION"
    const val MILITARY_COMMAND = "MILITARY_COMMAND"
    const val CITY_ADMINISTRATION = "CITY_ADMINISTRATION"
    const val STATE = "STATE"
    const val INTELLIGENCE_SERVICE = "INTELLIGENCE_SERVICE"
    const val RESEARCH_TEAM = "RESEARCH_TEAM"
    const val LABORATORY = "LABORATORY"
    const val GUILD = "GUILD"
    const val COMPANY = "COMPANY"
    const val WORLD_SPECIFIC = "WORLD_SPECIFIC"
}

data class KnowledgeHolderRef(
    val holderKindUid: String,
    val holderUid: String,
    val campaignUid: String? = null
) {
    init {
        require(holderKindUid.isNotBlank() && holderUid.isNotBlank())
        require(campaignUid?.isBlank() != true)
    }
}

data class KnowledgeDomain(val domainUid: String) {
    init { require(domainUid.isNotBlank()) }
}

object KnowledgeDomains {
    const val MILITARY_INTELLIGENCE = "MILITARY_INTELLIGENCE"
    const val TACTICS = "TACTICS"
    const val MARKET = "MARKET"
    const val VALUATION = "VALUATION"
    const val MEDICINE = "MEDICINE"
    const val SCIENCE = "SCIENCE"
    const val CRAFTING = "CRAFTING"
    const val POLITICS = "POLITICS"
    const val LAW = "LAW"
    const val GEOGRAPHY = "GEOGRAPHY"
    const val HISTORY = "HISTORY"
    const val TECHNIQUE_KNOWLEDGE = "TECHNIQUE_KNOWLEDGE"
    const val INVESTIGATION = "INVESTIGATION"
    const val WORLD_SPECIFIC = "WORLD_SPECIFIC"
}

object KnowledgeAcquisitionMethods {
    const val DIRECT_OBSERVATION = "DIRECT_OBSERVATION"
    const val DIRECT_COMMUNICATION = "DIRECT_COMMUNICATION"
    const val DOCUMENT = "DOCUMENT"
    const val REPORT = "REPORT"
    const val RUMOR = "RUMOR"
    const val EDUCATION = "EDUCATION"
    const val TRAINING = "TRAINING"
    const val RESEARCH = "RESEARCH"
    const val EXPERIMENT = "EXPERIMENT"
    const val INFERENCE = "INFERENCE"
    const val SURVEILLANCE = "SURVEILLANCE"
    const val ESPIONAGE = "ESPIONAGE"
    const val INTERROGATION = "INTERROGATION"
    const val INSTITUTIONAL_SHARING = "INSTITUTIONAL_SHARING"
    const val MEMORY_RECALL = "MEMORY_RECALL"
    const val WORLD_SPECIFIC = "WORLD_SPECIFIC"
    const val LEGACY = "LEGACY"
    const val UNKNOWN_NOT_RECORDED = "UNKNOWN_NOT_RECORDED"
}

enum class KnowledgeEpistemicState {
    KNOWN, BELIEVED, SUSPECTED, PARTIALLY_KNOWN, DOUBTED, DISBELIEVED, CONTRADICTED, OUTDATED
}

enum class KnowledgeScope { PERSONAL, INSTITUTIONAL, ROLE_ACCESSIBLE }
enum class KnowledgeEvidencePolarity { SUPPORTS, CONTRADICTS, NEUTRAL }
enum class KnowledgeProvenanceStatus { RECORDED, VERIFIED_IMPORT, LEGACY, UNKNOWN_NOT_RECORDED }

object KnowledgeCarrierKinds {
    const val DOCUMENT = "DOCUMENT"
    const val BOOK = "BOOK"
    const val MAP = "MAP"
    const val REPORT = "REPORT"
    const val DATABASE = "DATABASE"
    const val ARCHIVE = "ARCHIVE"
    const val LETTER = "LETTER"
    const val RESEARCH_NOTEBOOK = "RESEARCH_NOTEBOOK"
    const val MEDIA_RECORD = "MEDIA_RECORD"
    const val WORLD_SPECIFIC = "WORLD_SPECIFIC"
}

data class KnowledgeCarrierRef(
    val carrierKindUid: String,
    val carrierUid: String,
    val campaignUid: String? = null
) {
    init {
        require(carrierKindUid.isNotBlank() && carrierUid.isNotBlank())
        require(campaignUid?.isBlank() != true)
    }
}

data class KnowledgeClaim(
    val claimUid: String,
    val subjectKindUid: String,
    val subjectUid: String,
    val predicateUid: String,
    val valueCanonical: String,
    val objectKindUid: String? = null,
    val objectUid: String? = null,
    val domainUid: String
) {
    init {
        require(claimUid.isNotBlank() && subjectKindUid.isNotBlank() && subjectUid.isNotBlank())
        require(predicateUid.isNotBlank() && valueCanonical.isNotBlank() && domainUid.isNotBlank())
        require((objectKindUid == null) == (objectUid == null))
        require(objectKindUid?.isBlank() != true && objectUid?.isBlank() != true)
    }
}

data class KnowledgeQuality(
    val confidence: Double,
    val precision: Double,
    val completeness: Double,
    val sourceReliability: Double,
    val corroborationCount: Int,
    val sourceObservedOrder: Long? = null
) {
    init {
        listOf(confidence, precision, completeness, sourceReliability).forEach {
            require(it.isFinite() && it in 0.0..1.0)
        }
        require(corroborationCount >= 0)
        require(sourceObservedOrder == null || sourceObservedOrder >= 0L)
    }
}

data class KnowledgeEvidenceSpec(
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
}

data class KnowledgeAcquisitionSpec(
    val acquisitionUid: String,
    val holder: KnowledgeHolderRef,
    val methodUid: String,
    val scope: KnowledgeScope,
    val epistemicState: KnowledgeEpistemicState,
    val quality: KnowledgeQuality,
    val parentAcquisitionUid: String? = null,
    val sourceHolder: KnowledgeHolderRef? = null,
    val roleUid: String? = null,
    val carrier: KnowledgeCarrierRef? = null,
    val provenanceStatus: KnowledgeProvenanceStatus = KnowledgeProvenanceStatus.RECORDED
) {
    init {
        require(acquisitionUid.isNotBlank() && methodUid.isNotBlank())
        require(parentAcquisitionUid?.isBlank() != true && roleUid?.isBlank() != true)
        if (scope == KnowledgeScope.ROLE_ACCESSIBLE) require(!roleUid.isNullOrBlank())
        else require(roleUid == null)
        require((parentAcquisitionUid == null) == (sourceHolder == null)) {
            "RPGOS-KNOWLEDGE:LINEAGE_SOURCE_PAIR_REQUIRED"
        }
    }
}

data class KnowledgeAcquisitionChange(
    val claim: KnowledgeClaim,
    val acquisition: KnowledgeAcquisitionSpec,
    val evidence: List<KnowledgeEvidenceSpec> = emptyList()
) : PlayerDomainChangePayload {
    init {
        require(acquisition.provenanceStatus == KnowledgeProvenanceStatus.RECORDED) {
            "RPGOS-KNOWLEDGE:GAMEPLAY_REQUIRES_RECORDED_PROVENANCE"
        }
        require(evidence.map { it.evidenceUid }.distinct().size == evidence.size)
    }
}

data class KnowledgeAcquisition(
    val campaignUid: String,
    val acquisitionUid: String,
    val claimUid: String,
    val holder: KnowledgeHolderRef,
    val methodUid: String,
    val scope: KnowledgeScope,
    val parentAcquisitionUid: String?,
    val sourceHolder: KnowledgeHolderRef?,
    val roleUid: String?,
    val carrier: KnowledgeCarrierRef?,
    val createdTransactionUid: String?,
    val createdTurnUid: String?,
    val createdEventUid: String?,
    val provenanceStatus: KnowledgeProvenanceStatus,
    val createdOrder: Long
)

data class KnowledgeEvidence(
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
}

data class KnowledgeState(
    val campaignUid: String,
    val stateUid: String,
    val holder: KnowledgeHolderRef,
    val claimUid: String,
    val scope: KnowledgeScope,
    val roleUid: String?,
    val epistemicState: KnowledgeEpistemicState,
    val quality: KnowledgeQuality,
    val latestAcquisitionUid: String,
    val updatedOrder: Long,
    val stateVersion: Long
)

data class ExpertiseProfile(
    val campaignUid: String,
    val holder: KnowledgeHolderRef,
    val domainUid: String,
    val levelUnits: Long,
    val interpretationReliability: Double,
    val profileVersion: Long
) {
    init {
        require(campaignUid.isNotBlank() && domainUid.isNotBlank())
        require(levelUnits >= 0L && interpretationReliability.isFinite() && interpretationReliability in 0.0..1.0)
        require(profileVersion >= 1L)
    }
}

internal object Phase37KnowledgeSchema {
    const val CLAIMS = "world_actor_knowledge_claims"
    const val ACQUISITIONS = "world_actor_knowledge_acquisitions"
    const val EVIDENCE = "world_actor_knowledge_evidence"
    const val STATES = "world_actor_knowledge_states"
    const val EXPERTISE = "world_actor_expertise"

    val canonicalTables = setOf(CLAIMS, ACQUISITIONS, EVIDENCE, STATES)

    fun ensureReady(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS $CLAIMS(
            campaign_uid TEXT NOT NULL,claim_uid TEXT NOT NULL,subject_kind_uid TEXT NOT NULL,subject_uid TEXT NOT NULL,
            predicate_uid TEXT NOT NULL,value_canonical TEXT NOT NULL,object_kind_uid TEXT,object_uid TEXT,domain_uid TEXT NOT NULL,
            claim_schema_version INTEGER NOT NULL CHECK(claim_schema_version=$PHASE37_KNOWLEDGE_SCHEMA_VERSION),
            PRIMARY KEY(campaign_uid,claim_uid),CHECK((object_kind_uid IS NULL)=(object_uid IS NULL)))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS $ACQUISITIONS(
            campaign_uid TEXT NOT NULL,acquisition_uid TEXT NOT NULL,claim_uid TEXT NOT NULL,
            holder_kind_uid TEXT NOT NULL,holder_uid TEXT NOT NULL,method_uid TEXT NOT NULL,scope_uid TEXT NOT NULL,
            parent_acquisition_uid TEXT,source_holder_kind_uid TEXT,source_holder_uid TEXT,role_uid TEXT,
            carrier_kind_uid TEXT,carrier_uid TEXT,created_transaction_uid TEXT,created_turn_uid TEXT,created_event_uid TEXT,
            provenance_status TEXT NOT NULL,created_order INTEGER NOT NULL,
            acquisition_schema_version INTEGER NOT NULL CHECK(acquisition_schema_version=$PHASE37_KNOWLEDGE_SCHEMA_VERSION),
            PRIMARY KEY(campaign_uid,acquisition_uid),
            CHECK((source_holder_kind_uid IS NULL)=(source_holder_uid IS NULL)),
            CHECK((carrier_kind_uid IS NULL)=(carrier_uid IS NULL)),
            CHECK((parent_acquisition_uid IS NULL)=(source_holder_uid IS NULL)),
            CHECK(provenance_status!='RECORDED' OR (created_transaction_uid IS NOT NULL AND created_turn_uid IS NOT NULL AND created_event_uid IS NOT NULL)))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS $EVIDENCE(
            campaign_uid TEXT NOT NULL,evidence_uid TEXT NOT NULL,acquisition_uid TEXT NOT NULL,claim_uid TEXT NOT NULL,
            evidence_kind_uid TEXT NOT NULL,polarity_uid TEXT NOT NULL,source_event_uid TEXT,source_acquisition_uid TEXT,
            source_carrier_kind_uid TEXT,source_carrier_uid TEXT,source_ref_kind_uid TEXT,source_ref_uid TEXT,
            evidence_schema_version INTEGER NOT NULL CHECK(evidence_schema_version=$PHASE37_KNOWLEDGE_SCHEMA_VERSION),
            PRIMARY KEY(campaign_uid,evidence_uid),
            CHECK((source_carrier_kind_uid IS NULL)=(source_carrier_uid IS NULL)),
            CHECK((source_ref_kind_uid IS NULL)=(source_ref_uid IS NULL)))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS $STATES(
            campaign_uid TEXT NOT NULL,state_uid TEXT NOT NULL,holder_kind_uid TEXT NOT NULL,holder_uid TEXT NOT NULL,
            claim_uid TEXT NOT NULL,scope_uid TEXT NOT NULL,role_uid TEXT NOT NULL DEFAULT '',epistemic_state_uid TEXT NOT NULL,
            confidence REAL NOT NULL,precision_value REAL NOT NULL,completeness REAL NOT NULL,source_reliability REAL NOT NULL,
            corroboration_count INTEGER NOT NULL,source_observed_order INTEGER,latest_acquisition_uid TEXT NOT NULL,
            updated_order INTEGER NOT NULL,state_version INTEGER NOT NULL,
            state_schema_version INTEGER NOT NULL CHECK(state_schema_version=$PHASE37_KNOWLEDGE_SCHEMA_VERSION),
            PRIMARY KEY(campaign_uid,state_uid),
            UNIQUE(campaign_uid,holder_kind_uid,holder_uid,claim_uid,scope_uid,role_uid))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS $EXPERTISE(
            campaign_uid TEXT NOT NULL,holder_kind_uid TEXT NOT NULL,holder_uid TEXT NOT NULL,domain_uid TEXT NOT NULL,
            level_units INTEGER NOT NULL CHECK(level_units>=0),interpretation_reliability REAL NOT NULL,
            profile_version INTEGER NOT NULL CHECK(profile_version>=1),
            PRIMARY KEY(campaign_uid,holder_kind_uid,holder_uid,domain_uid))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_p37_acquisition_holder ON $ACQUISITIONS(campaign_uid,holder_kind_uid,holder_uid,created_order,acquisition_uid)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_p37_acquisition_claim ON $ACQUISITIONS(campaign_uid,claim_uid,created_order,acquisition_uid)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_p37_evidence_acquisition ON $EVIDENCE(campaign_uid,acquisition_uid,evidence_uid)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_p37_state_holder ON $STATES(campaign_uid,holder_kind_uid,holder_uid,epistemic_state_uid,claim_uid)")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS rpgos_p37_claim_no_update BEFORE UPDATE ON $CLAIMS BEGIN SELECT RAISE(ABORT,'RPGOS-KNOWLEDGE:CLAIM_APPEND_ONLY'); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS rpgos_p37_claim_no_delete BEFORE DELETE ON $CLAIMS BEGIN SELECT RAISE(ABORT,'RPGOS-KNOWLEDGE:CLAIM_APPEND_ONLY'); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS rpgos_p37_acquisition_no_update BEFORE UPDATE ON $ACQUISITIONS BEGIN SELECT RAISE(ABORT,'RPGOS-KNOWLEDGE:ACQUISITION_APPEND_ONLY'); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS rpgos_p37_acquisition_no_delete BEFORE DELETE ON $ACQUISITIONS BEGIN SELECT RAISE(ABORT,'RPGOS-KNOWLEDGE:ACQUISITION_APPEND_ONLY'); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS rpgos_p37_evidence_no_update BEFORE UPDATE ON $EVIDENCE BEGIN SELECT RAISE(ABORT,'RPGOS-KNOWLEDGE:EVIDENCE_APPEND_ONLY'); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS rpgos_p37_evidence_no_delete BEFORE DELETE ON $EVIDENCE BEGIN SELECT RAISE(ABORT,'RPGOS-KNOWLEDGE:EVIDENCE_APPEND_ONLY'); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS rpgos_p37_state_no_delete BEFORE DELETE ON $STATES BEGIN SELECT RAISE(ABORT,'RPGOS-KNOWLEDGE:STATE_DELETE_FORBIDDEN'); END")
    }

    fun isReady(db: SQLiteDatabase): Boolean = listOf(CLAIMS, ACQUISITIONS, EVIDENCE, STATES, EXPERTISE).all { table ->
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1", arrayOf(table)).use { it.moveToFirst() }
    }

    fun requireProjectionReadable(db: SQLiteDatabase) {
        val tables = listOf(CLAIMS, ACQUISITIONS, EVIDENCE, STATES, EXPERTISE)
        val anyCanonical = tables.any { table ->
            db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1", arrayOf(table)).use { it.moveToFirst() }
        }
        val versionRegistered = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1", arrayOf(Phase36SchemaVersioning.VERSIONS)
        ).use { it.moveToFirst() } && db.rawQuery(
            "SELECT 1 FROM ${Phase36SchemaVersioning.VERSIONS} WHERE schema_family_uid=? LIMIT 1", arrayOf(SchemaFamilyUid.KNOWLEDGE.name)
        ).use { it.moveToFirst() }
        if (anyCanonical || versionRegistered) {
            check(isReady(db)) { "RPGOS-P37:CANONICAL_KNOWLEDGE_SCHEMA_CORRUPT" }
            if (GameplayMutationDatabaseGuards.isInstalled(db)) {
                Phase37GuardDefinitionIntegrity.requireCanonical(db)
            }
        }
    }
}

internal data class PendingKnowledgeAcquisition(
    val campaignUid: String,
    val change: KnowledgeAcquisitionChange,
    val identity: TurnTransactionIdentity,
    val eventUid: String,
    val createdOrder: Long
)

internal object Phase37KnowledgeWriteTokens {
    private fun hex(value: String?): String = value.orEmpty().toByteArray(Charsets.UTF_8).joinToString("") { "%02X".format(it) }
    fun claim(campaignUid: String, c: KnowledgeClaim) =
        "CLAIM:${hex(campaignUid)}:${hex(c.claimUid)}:${hex(c.subjectKindUid)}:${hex(c.subjectUid)}:${hex(c.predicateUid)}:${hex(c.valueCanonical)}:${hex(c.domainUid)}"
    fun acquisition(campaignUid: String, a: KnowledgeAcquisitionSpec, claimUid: String, eventUid: String) =
        "ACQ:${hex(campaignUid)}:${hex(a.acquisitionUid)}:${hex(claimUid)}:${hex(a.holder.holderKindUid)}:${hex(a.holder.holderUid)}:${hex(eventUid)}:${hex(KnowledgeProvenanceStatus.RECORDED.name)}"
    fun evidence(campaignUid: String, acquisitionUid: String, claimUid: String, e: KnowledgeEvidenceSpec) =
        "EVID:${hex(campaignUid)}:${hex(e.evidenceUid)}:${hex(acquisitionUid)}:${hex(claimUid)}:${hex(e.evidenceKindUid)}:${hex(e.polarity.name)}:${hex("")}:${hex(e.sourceAcquisitionUid)}"
    fun eventEvidence(campaignUid: String, acquisitionUid: String, claimUid: String, eventUid: String) =
        "EVID:${hex(campaignUid)}:${hex("RPGOS-KNOWLEDGE-EVENT:$acquisitionUid")}:${hex(acquisitionUid)}:${hex(claimUid)}:${hex("COMMITTED_EVENT")}:${hex(KnowledgeEvidencePolarity.SUPPORTS.name)}:${hex(eventUid)}:${hex("")}"
    fun state(campaignUid: String, change: KnowledgeAcquisitionChange) = with(change.acquisition) {
        val role = roleUid.orEmpty()
        val uid = "RPGOS-KNOWLEDGE-STATE:${holder.holderKindUid}:${holder.holderUid}:${change.claim.claimUid}:${scope.name}:$role"
        "STATE:${hex(campaignUid)}:${hex(uid)}:${hex(holder.holderKindUid)}:${hex(holder.holderUid)}:${hex(change.claim.claimUid)}:${hex(scope.name)}:${hex(role)}:${hex(epistemicState.name)}:${hex(acquisitionUid)}"
    }
    fun forPending(p: PendingKnowledgeAcquisition): Set<String> = buildSet {
        add(claim(p.campaignUid, p.change.claim))
        add(acquisition(p.campaignUid, p.change.acquisition, p.change.claim.claimUid, p.eventUid))
        add(eventEvidence(p.campaignUid, p.change.acquisition.acquisitionUid, p.change.claim.claimUid, p.eventUid))
        p.change.evidence.forEach { add(evidence(p.campaignUid, p.change.acquisition.acquisitionUid, p.change.claim.claimUid, it)) }
        add(state(p.campaignUid, p.change))
    }
}

internal object KnowledgeRecordedWriteAuthority {
    private data class Active(val db: SQLiteDatabase, val campaignUid: String, val tokens: Set<String>)
    private val local = ThreadLocal<Active?>()

    fun isAuthorized(db: SQLiteDatabase, token: String): Boolean {
        val a = local.get()
        return a != null && a.db === db && token in a.tokens && isCanonicalGameplayMutationActive(db, a.campaignUid)
    }

    fun <T> withPending(db: SQLiteDatabase, campaignUid: String, pending: PendingKnowledgeAcquisition, block: () -> T): T {
        requireCanonicalGameplayMutation(db, campaignUid)
        check(local.get() == null) { "RPGOS-KNOWLEDGE:NESTED_RECORDED_WRITE_AUTHORITY" }
        local.set(Active(db, campaignUid, Phase37KnowledgeWriteTokens.forPending(pending)))
        GameplayMutationDatabaseGuards.suspendLegacyPhase37RecordedWriteGuards(db)
        return try {
            block()
        } finally {
            GameplayMutationDatabaseGuards.restoreLegacyPhase37RecordedWriteGuards(db)
            local.remove()
        }
    }
}

/** Exact-db/campaign in-memory buffer. Mutable SQLite context rows cannot manufacture this capability. */
internal object KnowledgeTurnBuffer {
    private data class Active(val db: SQLiteDatabase, val campaignUid: String, val entries: MutableList<PendingKnowledgeAcquisition>)
    private val local = ThreadLocal<Active?>()

    fun begin(db: SQLiteDatabase, campaignUid: String) {
        check(local.get() == null) { "RPGOS-KNOWLEDGE:NESTED_TURN_BUFFER" }
        local.set(Active(db, campaignUid, mutableListOf()))
    }

    fun stage(db: SQLiteDatabase, campaignUid: String, change: KnowledgeAcquisitionChange, identity: TurnTransactionIdentity, eventUid: String, createdOrder: Long) {
        val active = local.get() ?: error("RPGOS-KNOWLEDGE:NO_CANONICAL_TURN_BUFFER")
        require(active.db === db && active.campaignUid == campaignUid && identity.campaignUid == campaignUid) {
            "RPGOS-KNOWLEDGE:TURN_BUFFER_SCOPE_MISMATCH"
        }
        val pending = PendingKnowledgeAcquisition(campaignUid, change, identity, eventUid, createdOrder)
        active.entries.firstOrNull { it.change.acquisition.acquisitionUid == change.acquisition.acquisitionUid }?.let {
            require(it == pending) { "RPGOS-KNOWLEDGE:ACQUISITION_IDENTITY_CONFLICT" }
            return
        }
        active.entries += pending
    }

    fun flush(db: SQLiteDatabase, campaignUid: String) {
        val active = local.get() ?: return
        require(active.db === db && active.campaignUid == campaignUid) { "RPGOS-KNOWLEDGE:TURN_BUFFER_SCOPE_MISMATCH" }
        active.entries.forEach { pending ->
            KnowledgeRecordedWriteAuthority.withPending(db, campaignUid, pending) {
                KnowledgeStore(db, campaignUid).finalizeRecorded(pending)
            }
        }
        active.entries.clear()
    }

    fun clear() { local.remove() }
}

class KnowledgeStore(private val db: SQLiteDatabase, private val campaignUid: String) {
    init { require(campaignUid.isNotBlank()) }

    internal fun stageRecorded(change: KnowledgeAcquisitionChange, identity: TurnTransactionIdentity, eventUid: String, createdOrder: Long) {
        require(identity.campaignUid == campaignUid) { "RPGOS-KNOWLEDGE:CROSS_CAMPAIGN" }
        require(change.acquisition.provenanceStatus == KnowledgeProvenanceStatus.RECORDED)
        requireCanonicalGameplayMutation(db, campaignUid)
        Phase37GuardDefinitionIntegrity.requireCanonical(db)
        KnowledgeDomainValidator.validateForCampaign(change, campaignUid)
        KnowledgeTurnBuffer.stage(db, campaignUid, change, identity, eventUid, createdOrder)
    }

    internal fun finalizeRecorded(pending: PendingKnowledgeAcquisition) {
        requireCanonicalGameplayMutation(db, campaignUid)
        require(pending.campaignUid == campaignUid && pending.identity.campaignUid == campaignUid) { "RPGOS-KNOWLEDGE:CROSS_CAMPAIGN" }
        validateCommittedEvent(pending.identity, pending.eventUid)
        validateLineage(pending.change)
        validateEvidenceSources(pending.change)
        insertClaim(pending.change.claim)
        insertAcquisition(pending)
        insertCanonicalEventEvidence(pending)
        pending.change.evidence.forEach { insertEvidence(pending.change, it) }
        projectState(pending)
    }

    fun acquisitions(holder: KnowledgeHolderRef? = null): List<KnowledgeAcquisition> {
        if (!Phase37KnowledgeSchema.isReady(db)) return emptyList()
        holder?.campaignUid?.let { require(it == campaignUid) { "RPGOS-KNOWLEDGE:CROSS_CAMPAIGN_HOLDER_QUERY" } }
        val where = if (holder == null) "campaign_uid=?" else "campaign_uid=? AND holder_kind_uid=? AND holder_uid=?"
        val args = if (holder == null) arrayOf(campaignUid) else arrayOf(campaignUid, holder.holderKindUid, holder.holderUid)
        return db.rawQuery("""SELECT acquisition_uid,claim_uid,holder_kind_uid,holder_uid,method_uid,scope_uid,
            parent_acquisition_uid,source_holder_kind_uid,source_holder_uid,role_uid,carrier_kind_uid,carrier_uid,
            created_transaction_uid,created_turn_uid,created_event_uid,provenance_status,created_order
            FROM ${Phase37KnowledgeSchema.ACQUISITIONS} WHERE $where ORDER BY created_order,acquisition_uid""", args).use { c ->
            buildList {
                while (c.moveToNext()) add(KnowledgeAcquisition(
                    campaignUid,c.getString(0),c.getString(1),KnowledgeHolderRef(c.getString(2),c.getString(3),campaignUid),c.getString(4),
                    KnowledgeScope.valueOf(c.getString(5)),str(c,6),
                    if(c.isNull(7)) null else KnowledgeHolderRef(c.getString(7),c.getString(8),campaignUid),str(c,9),
                    if(c.isNull(10)) null else KnowledgeCarrierRef(c.getString(10),c.getString(11),campaignUid),
                    str(c,12),str(c,13),str(c,14),KnowledgeProvenanceStatus.valueOf(c.getString(15)),c.getLong(16)
                ))
            }
        }
    }

    fun states(holder: KnowledgeHolderRef): List<KnowledgeState> {
        if (!Phase37KnowledgeSchema.isReady(db)) return emptyList()
        holder.campaignUid?.let { require(it == campaignUid) { "RPGOS-KNOWLEDGE:CROSS_CAMPAIGN_HOLDER_QUERY" } }
        val qualifiedHolder = KnowledgeHolderRef(holder.holderKindUid, holder.holderUid, campaignUid)
        return db.rawQuery("""SELECT state_uid,claim_uid,scope_uid,role_uid,epistemic_state_uid,confidence,precision_value,
            completeness,source_reliability,corroboration_count,source_observed_order,latest_acquisition_uid,updated_order,state_version
            FROM ${Phase37KnowledgeSchema.STATES} WHERE campaign_uid=? AND holder_kind_uid=? AND holder_uid=?
            ORDER BY claim_uid,scope_uid,role_uid""",arrayOf(campaignUid,holder.holderKindUid,holder.holderUid)).use { c ->
            buildList { while(c.moveToNext()) add(KnowledgeState(
                campaignUid,c.getString(0),qualifiedHolder,c.getString(1),KnowledgeScope.valueOf(c.getString(2)),c.getString(3).ifBlank { null },
                KnowledgeEpistemicState.valueOf(c.getString(4)),KnowledgeQuality(c.getDouble(5),c.getDouble(6),c.getDouble(7),c.getDouble(8),c.getInt(9),if(c.isNull(10))null else c.getLong(10)),
                c.getString(11),c.getLong(12),c.getLong(13)
            )) }
        }
    }

    fun evidence(acquisitionUid: String): List<KnowledgeEvidence> {
        if (!Phase37KnowledgeSchema.isReady(db)) return emptyList()
        return db.rawQuery("""SELECT evidence_uid,claim_uid,evidence_kind_uid,polarity_uid,source_event_uid,source_acquisition_uid,
            source_carrier_kind_uid,source_carrier_uid,source_ref_kind_uid,source_ref_uid
            FROM ${Phase37KnowledgeSchema.EVIDENCE} WHERE campaign_uid=? AND acquisition_uid=? ORDER BY evidence_uid""",
            arrayOf(campaignUid,acquisitionUid)).use { c -> buildList { while(c.moveToNext()) add(KnowledgeEvidence(
                campaignUid,c.getString(0),acquisitionUid,c.getString(1),c.getString(2),KnowledgeEvidencePolarity.valueOf(c.getString(3)),
                str(c,4),str(c,5),if(c.isNull(6))null else KnowledgeCarrierRef(c.getString(6),c.getString(7),campaignUid),
                if(c.isNull(8)) null else KnowledgeSourceRef.fromStorage(c.getString(8), c.getString(9), campaignUid)
            )) } }
    }

    fun expertise(holder: KnowledgeHolderRef): List<ExpertiseProfile> {
        if (!Phase37KnowledgeSchema.isReady(db)) return emptyList()
        return db.rawQuery("""SELECT domain_uid,level_units,interpretation_reliability,profile_version FROM ${Phase37KnowledgeSchema.EXPERTISE}
            WHERE campaign_uid=? AND holder_kind_uid=? AND holder_uid=? ORDER BY domain_uid""",
            arrayOf(campaignUid,holder.holderKindUid,holder.holderUid)).use { c -> buildList { while(c.moveToNext()) add(
                ExpertiseProfile(campaignUid,holder,c.getString(0),c.getLong(1),c.getDouble(2),c.getLong(3))
            ) } }
    }

    private fun validateCommittedEvent(identity: TurnTransactionIdentity, eventUid: String) {
        val ok = db.rawQuery("""SELECT 1 FROM ${CampaignIntelligencePhase30Schema.EVENT_TABLE}
            WHERE campaign_uid=? AND event_uid=? AND transaction_uid=? AND turn_uid=? AND command_uid=? LIMIT 1""",
            arrayOf(campaignUid,eventUid,identity.transactionUid,identity.turnUid,identity.commandUid)).use { it.moveToFirst() }
        require(ok) { "RPGOS-KNOWLEDGE:COMMITTED_EVENT_PROVENANCE_REQUIRED" }
    }

    private fun validateLineage(change: KnowledgeAcquisitionChange) {
        val parentUid = change.acquisition.parentAcquisitionUid ?: return
        val sourceHolder = requireNotNull(change.acquisition.sourceHolder)
        val row = acquisitionScope(parentUid) ?: run {
            val otherCampaign = db.rawQuery("SELECT campaign_uid FROM ${Phase37KnowledgeSchema.ACQUISITIONS} WHERE acquisition_uid=? LIMIT 1",arrayOf(parentUid)).use { c -> if(c.moveToFirst())c.getString(0) else null }
            if (otherCampaign != null && otherCampaign != campaignUid) error("RPGOS-KNOWLEDGE:CROSS_CAMPAIGN_LINEAGE")
            error("RPGOS-KNOWLEDGE:DANGLING_PARENT_ACQUISITION")
        }
        require(row.claimUid == change.claim.claimUid) { "RPGOS-KNOWLEDGE:LINEAGE_CLAIM_MISMATCH" }
        require(row.holder == sourceHolder) { "RPGOS-KNOWLEDGE:CROSS_HOLDER_PROVENANCE_MISMATCH" }
    }

    private fun validateEvidenceSources(change: KnowledgeAcquisitionChange) {
        change.evidence.forEach { e ->
            e.sourceAcquisitionUid?.let { source ->
                val row = acquisitionScope(source) ?: error("RPGOS-KNOWLEDGE:EVIDENCE_SOURCE_ACQUISITION_NOT_FOUND")
                require(row.claimUid == change.claim.claimUid) { "RPGOS-KNOWLEDGE:EVIDENCE_CLAIM_MISMATCH" }
            }
        }
    }

    private data class AcquisitionScope(val claimUid: String, val holder: KnowledgeHolderRef)
    private fun acquisitionScope(uid: String): AcquisitionScope? = db.rawQuery(
        "SELECT claim_uid,holder_kind_uid,holder_uid FROM ${Phase37KnowledgeSchema.ACQUISITIONS} WHERE campaign_uid=? AND acquisition_uid=? LIMIT 1",
        arrayOf(campaignUid,uid)).use { c -> if(c.moveToFirst())AcquisitionScope(c.getString(0),KnowledgeHolderRef(c.getString(1),c.getString(2),campaignUid)) else null }

    private fun insertClaim(claim: KnowledgeClaim) {
        val existing = db.rawQuery("""SELECT subject_kind_uid,subject_uid,predicate_uid,value_canonical,object_kind_uid,object_uid,domain_uid
            FROM ${Phase37KnowledgeSchema.CLAIMS} WHERE campaign_uid=? AND claim_uid=?""",arrayOf(campaignUid,claim.claimUid)).use { c ->
            if(!c.moveToFirst()) null else KnowledgeClaim(claim.claimUid,c.getString(0),c.getString(1),c.getString(2),c.getString(3),str(c,4),str(c,5),c.getString(6))
        }
        if(existing != null) { require(existing == claim) { "RPGOS-KNOWLEDGE:CLAIM_IDENTITY_CONFLICT" }; return }
        db.insertOrThrow(Phase37KnowledgeSchema.CLAIMS,null,ContentValues().apply {
            put("campaign_uid",campaignUid);put("claim_uid",claim.claimUid);put("subject_kind_uid",claim.subjectKindUid);put("subject_uid",claim.subjectUid)
            put("predicate_uid",claim.predicateUid);put("value_canonical",claim.valueCanonical);put("object_kind_uid",claim.objectKindUid);put("object_uid",claim.objectUid)
            put("domain_uid",claim.domainUid);put("claim_schema_version",PHASE37_KNOWLEDGE_SCHEMA_VERSION)
        })
    }

    private fun insertAcquisition(p: PendingKnowledgeAcquisition) {
        val a=p.change.acquisition
        val existing=acquisitionScope(a.acquisitionUid)
        require(existing==null) { "RPGOS-KNOWLEDGE:ACQUISITION_IDENTITY_CONFLICT" }
        db.insertOrThrow(Phase37KnowledgeSchema.ACQUISITIONS,null,ContentValues().apply {
            put("campaign_uid",campaignUid);put("acquisition_uid",a.acquisitionUid);put("claim_uid",p.change.claim.claimUid)
            put("holder_kind_uid",a.holder.holderKindUid);put("holder_uid",a.holder.holderUid);put("method_uid",a.methodUid);put("scope_uid",a.scope.name)
            put("parent_acquisition_uid",a.parentAcquisitionUid);put("source_holder_kind_uid",a.sourceHolder?.holderKindUid);put("source_holder_uid",a.sourceHolder?.holderUid)
            put("role_uid",a.roleUid);put("carrier_kind_uid",a.carrier?.carrierKindUid);put("carrier_uid",a.carrier?.carrierUid)
            put("created_transaction_uid",p.identity.transactionUid);put("created_turn_uid",p.identity.turnUid);put("created_event_uid",p.eventUid)
            put("provenance_status",KnowledgeProvenanceStatus.RECORDED.name);put("created_order",p.createdOrder);put("acquisition_schema_version",PHASE37_KNOWLEDGE_SCHEMA_VERSION)
        })
    }

    private fun insertCanonicalEventEvidence(p: PendingKnowledgeAcquisition) {
        val uid="RPGOS-KNOWLEDGE-EVENT:${p.change.acquisition.acquisitionUid}"
        db.insertOrThrow(Phase37KnowledgeSchema.EVIDENCE,null,ContentValues().apply {
            put("campaign_uid",campaignUid);put("evidence_uid",uid);put("acquisition_uid",p.change.acquisition.acquisitionUid);put("claim_uid",p.change.claim.claimUid)
            put("evidence_kind_uid","COMMITTED_EVENT");put("polarity_uid",KnowledgeEvidencePolarity.SUPPORTS.name);put("source_event_uid",p.eventUid)
            put("evidence_schema_version",PHASE37_KNOWLEDGE_SCHEMA_VERSION)
        })
    }

    private fun insertEvidence(change: KnowledgeAcquisitionChange, e: KnowledgeEvidenceSpec) {
        db.insertOrThrow(Phase37KnowledgeSchema.EVIDENCE,null,ContentValues().apply {
            put("campaign_uid",campaignUid);put("evidence_uid",e.evidenceUid);put("acquisition_uid",change.acquisition.acquisitionUid);put("claim_uid",change.claim.claimUid)
            put("evidence_kind_uid",e.evidenceKindUid);put("polarity_uid",e.polarity.name);put("source_acquisition_uid",e.sourceAcquisitionUid)
            put("source_carrier_kind_uid",e.sourceCarrier?.carrierKindUid);put("source_carrier_uid",e.sourceCarrier?.carrierUid)
            put("source_ref_kind_uid",e.sourceRef?.storageKindUid());put("source_ref_uid",e.sourceRef?.entityUid);put("evidence_schema_version",PHASE37_KNOWLEDGE_SCHEMA_VERSION)
        })
    }

    private fun projectState(p: PendingKnowledgeAcquisition) {
        val a=p.change.acquisition;val role=a.roleUid.orEmpty();val stateUid=stateUid(a.holder,p.change.claim.claimUid,a.scope,role)
        val current=db.rawQuery("SELECT state_version FROM ${Phase37KnowledgeSchema.STATES} WHERE campaign_uid=? AND state_uid=?",arrayOf(campaignUid,stateUid)).use { c -> if(c.moveToFirst())c.getLong(0) else 0L }
        val q=a.quality
        val values=ContentValues().apply {
            put("campaign_uid",campaignUid);put("state_uid",stateUid);put("holder_kind_uid",a.holder.holderKindUid);put("holder_uid",a.holder.holderUid)
            put("claim_uid",p.change.claim.claimUid);put("scope_uid",a.scope.name);put("role_uid",role);put("epistemic_state_uid",a.epistemicState.name)
            put("confidence",q.confidence);put("precision_value",q.precision);put("completeness",q.completeness);put("source_reliability",q.sourceReliability)
            put("corroboration_count",q.corroborationCount);put("source_observed_order",q.sourceObservedOrder);put("latest_acquisition_uid",a.acquisitionUid)
            put("updated_order",p.createdOrder);put("state_version",current+1L);put("state_schema_version",PHASE37_KNOWLEDGE_SCHEMA_VERSION)
        }
        if (current == 0L) {
            db.insertOrThrow(Phase37KnowledgeSchema.STATES, null, values)
        } else {
            val updated = db.update(
                Phase37KnowledgeSchema.STATES, values, "campaign_uid=? AND state_uid=?", arrayOf(campaignUid, stateUid)
            )
            require(updated == 1) { "RPGOS-KNOWLEDGE:STATE_UPDATE_IDENTITY_CONFLICT" }
        }
    }

    private fun stateUid(holder: KnowledgeHolderRef, claimUid: String, scope: KnowledgeScope, role: String) =
        "RPGOS-KNOWLEDGE-STATE:${holder.holderKindUid}:${holder.holderUid}:$claimUid:${scope.name}:$role"
    private fun str(c: android.database.Cursor,index:Int)=if(c.isNull(index))null else c.getString(index)
}

internal object KnowledgeDomainValidator {
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
}

/** Read-only compatibility adapter. It never manufactures structured historical provenance. */
class LegacyKnowledgeCompatibilityAdapter(private val db: SQLiteDatabase, private val campaignUid: String) {
    fun forHolder(holder: KnowledgeHolderRef): List<Map<String,Any?>> {
        if(holder.holderKindUid != KnowledgeHolderKinds.CHARACTER || !table("information_knowledge")) return emptyList()
        return try {
            db.rawQuery("""SELECT k.holder_uid,k.info_uid,k.confidence,k.accuracy,k.acquisition_method,k.learned_chapter,
                f.title,f.content_summary,f.secrecy_level FROM information_knowledge k LEFT JOIN information_facts f ON f.info_uid=k.info_uid
                WHERE k.holder_uid=? ORDER BY k.confidence DESC,k.learned_chapter DESC,k.info_uid""",arrayOf(holder.holderUid)).use { c ->
                buildList { while(c.moveToNext()) add(linkedMapOf<String,Any?>(
                    "holder_kind_uid" to holder.holderKindUid,"holder_uid" to holder.holderUid,"claim_uid" to "LEGACY-INFO:${c.getString(1)}",
                    "subject_kind_uid" to "LEGACY_INFORMATION","subject_uid" to c.getString(1),"predicate_uid" to "LEGACY_OPAQUE_TEXT",
                    "value_canonical" to (if(c.isNull(7)) c.getString(1) else c.getString(7)),"domain_uid" to "LEGACY",
                    "epistemic_state_uid" to KnowledgeEpistemicState.BELIEVED.name,"confidence" to c.getDouble(2),"precision" to c.getDouble(3),
                    "acquisition_method_uid" to (if(c.isNull(4)) KnowledgeAcquisitionMethods.UNKNOWN_NOT_RECORDED else c.getString(4)),
                    "learned_order" to (if(c.isNull(5)) null else c.getLong(5)),"title" to if(c.isNull(6))null else c.getString(6),
                    "secrecy_level" to if(c.isNull(8))null else c.getString(8),"provenance_status" to KnowledgeProvenanceStatus.UNKNOWN_NOT_RECORDED.name,
                    "canonical" to false
                )) }
            }
        } catch(_:Throwable) { emptyList() }
    }
    private fun table(name:String)=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",arrayOf(name)).use{it.moveToFirst()}
}

/** Holder-scoped read projection. Phase 38 visibility policy is deliberately not implemented here. */
class KnowledgeContextProjection(private val db: SQLiteDatabase, private val campaignUid: String) {
    fun forHolders(holders: Collection<KnowledgeHolderRef>, includeLegacy: Boolean = true): List<Map<String,Any?>> {
        Phase37KnowledgeSchema.requireProjectionReadable(db)
        if (Phase37KnowledgeSchema.isReady(db)) Phase37KnowledgeLineageIntegrity.requireCampaign(db, campaignUid)
        val exact=holders.distinct()
        exact.forEach { it.campaignUid?.let { scoped -> require(scoped == campaignUid) { "RPGOS-KNOWLEDGE:CROSS_CAMPAIGN_HOLDER_QUERY" } } }
        val out=mutableListOf<Map<String,Any?>>()
        exact.forEach { holder ->
            if(Phase37KnowledgeSchema.isReady(db)) {
                db.rawQuery("""SELECT s.holder_kind_uid,s.holder_uid,s.claim_uid,c.subject_kind_uid,c.subject_uid,c.predicate_uid,
                    c.value_canonical,c.object_kind_uid,c.object_uid,c.domain_uid,s.epistemic_state_uid,s.confidence,s.precision_value,
                    s.completeness,s.source_reliability,s.corroboration_count,s.source_observed_order,s.latest_acquisition_uid,
                    a.method_uid,a.created_event_uid,a.provenance_status,s.scope_uid,s.role_uid
                    FROM ${Phase37KnowledgeSchema.STATES} s JOIN ${Phase37KnowledgeSchema.CLAIMS} c
                      ON c.campaign_uid=s.campaign_uid AND c.claim_uid=s.claim_uid
                    JOIN ${Phase37KnowledgeSchema.ACQUISITIONS} a ON a.campaign_uid=s.campaign_uid AND a.acquisition_uid=s.latest_acquisition_uid
                    WHERE s.campaign_uid=? AND s.holder_kind_uid=? AND s.holder_uid=? ORDER BY s.claim_uid,s.scope_uid,s.role_uid""",
                    arrayOf(campaignUid,holder.holderKindUid,holder.holderUid)).use { c ->
                    while(c.moveToNext()) out += linkedMapOf(
                        "holder_kind_uid" to c.getString(0),"holder_uid" to c.getString(1),"claim_uid" to c.getString(2),
                        "subject_kind_uid" to c.getString(3),"subject_uid" to c.getString(4),"predicate_uid" to c.getString(5),
                        "value_canonical" to c.getString(6),"object_kind_uid" to if(c.isNull(7))null else c.getString(7),"object_uid" to if(c.isNull(8))null else c.getString(8),
                        "domain_uid" to c.getString(9),"epistemic_state_uid" to c.getString(10),"confidence" to c.getDouble(11),"precision" to c.getDouble(12),
                        "completeness" to c.getDouble(13),"source_reliability" to c.getDouble(14),"corroboration_count" to c.getInt(15),
                        "source_observed_order" to if(c.isNull(16))null else c.getLong(16),"latest_acquisition_uid" to c.getString(17),
                        "acquisition_method_uid" to c.getString(18),"created_event_uid" to if(c.isNull(19))null else c.getString(19),
                        "provenance_status" to c.getString(20),"knowledge_scope_uid" to c.getString(21),"role_uid" to c.getString(22).ifBlank { null },
                        "canonical" to true
                    )
                }
            }
            if(includeLegacy) out += LegacyKnowledgeCompatibilityAdapter(db,campaignUid).forHolder(holder)
        }
        return out
    }
}
