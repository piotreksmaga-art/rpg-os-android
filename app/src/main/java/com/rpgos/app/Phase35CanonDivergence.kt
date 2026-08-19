package com.rpgos.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest

const val CANON_DIVERGENCE_SCHEMA_VERSION = 1

enum class CanonDivergenceKind { STATE, OUTCOME, RELATION, TIMELINE }
enum class CanonDivergenceStatus { ACTIVE, SUPERSEDED, RESOLVED }
enum class HistoricalProvenanceStatus { RECORDED, VERIFIED_IMPORT, LEGACY, UNKNOWN_NOT_RECORDED }

data class CanonReference(
    val subjectKindUid: String,
    val subjectUid: String,
    val expectationUid: String
) {
    init { require(subjectKindUid.isNotBlank() && subjectUid.isNotBlank() && expectationUid.isNotBlank()) }
}

data class CanonDivergenceSpec(
    val divergenceUid: String,
    val canonicalReference: CanonReference,
    val worldPackUid: String,
    val worldPackVersion: String,
    val kind: CanonDivergenceKind,
    val expectedCanonicalValue: String,
    val actualCampaignValue: String,
    val status: CanonDivergenceStatus = CanonDivergenceStatus.ACTIVE,
    val effectiveFrom: Long? = null,
    val effectiveUntil: Long? = null,
    val supersedesDivergenceUid: String? = null,
    val resolvesDivergenceUid: String? = null,
    val provenanceStatus: HistoricalProvenanceStatus = HistoricalProvenanceStatus.RECORDED,
    val schemaVersion: Int = CANON_DIVERGENCE_SCHEMA_VERSION
) {
    init {
        require(divergenceUid.isNotBlank() && worldPackUid.isNotBlank() && worldPackVersion.isNotBlank())
        require(expectedCanonicalValue.isNotBlank() && actualCampaignValue.isNotBlank())
        require(expectedCanonicalValue != actualCampaignValue) { "RPGOS-CANON:NO_DIVERGENCE" }
        require(schemaVersion == CANON_DIVERGENCE_SCHEMA_VERSION) { "RPGOS-CANON:UNSUPPORTED_SCHEMA:$schemaVersion" }
        require(effectiveUntil == null || effectiveFrom == null || effectiveUntil >= effectiveFrom)
    }
}

data class CanonDivergenceRecord(
    val campaignUid: String,
    val spec: CanonDivergenceSpec,
    val createdTransactionUid: String?,
    val createdTurnUid: String?,
    val createdEventUid: String?,
    val createdAtEpochMs: Long
)

/**
 * Provider-issued evidence binding one canonical expectation identity to the exact expected value.
 * The evidence is only authoritative when it arrives in the final DRAFT_EFFECT_CHECK decision of
 * the WorldRuleProvider selected through the campaign's authoritative World Pack binding.
 */
internal object CanonExpectationEvidence {
    fun uid(reference: CanonReference, expectedValue: String): String {
        require(expectedValue.isNotBlank())
        val canonical = listOf(
            "RPGOS-CANON-EXPECTATION-V1",
            reference.subjectKindUid,
            reference.subjectUid,
            reference.expectationUid,
            expectedValue
        ).joinToString("\u001f")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "RPGOS-CANON-EXPECTATION:$digest"
    }
}

/**
 * Final admission check for RECORDED divergences. World Pack provenance comes from the trusted
 * final WorldRuleDecisionRecord, expected value from provider-issued expectation evidence, and
 * actual value from the concrete CampaignTruthChange that will be committed.
 */
internal object CanonDivergenceAdmissionValidator {
    const val UNBOUND_WORLD_RULE = "RPGOS-CANON:RECORDED_REQUIRES_BOUND_WORLD_RULE"
    const val WORLD_PACK_MISMATCH = "RPGOS-CANON:WORLD_PACK_AUTHORITY_MISMATCH"
    const val EXPECTATION_NOT_AUTHENTICATED = "RPGOS-CANON:EXPECTATION_NOT_AUTHENTICATED"
    const val ACTUAL_NOT_AUTHENTICATED = "RPGOS-CANON:ACTUAL_NOT_AUTHENTICATED"
    const val INVALID_GAMEPLAY_PROVENANCE = "RPGOS-CANON:INVALID_GAMEPLAY_PROVENANCE"

    fun rejectionReason(changeSet: PlayerChangeSet, evidence: PlayerResolutionEvidence): String? {
        val entries = changeSet.changes.mapNotNull { change ->
            val truth = change.payload as? CampaignTruthChange ?: return@mapNotNull null
            truth.canonDivergence?.let { Triple(change.changeUid, truth, it) }
        }
        if (entries.isEmpty()) return null

        val finalDecision = evidence.worldRuleDecisions.lastOrNull {
            it.stage == WorldRuleEvaluationStage.DRAFT_EFFECT_CHECK && it.allowed
        } ?: return UNBOUND_WORLD_RULE

        entries.forEach { (_, truth, spec) ->
            if (spec.provenanceStatus != HistoricalProvenanceStatus.RECORDED) {
                return INVALID_GAMEPLAY_PROVENANCE
            }
            if (spec.worldPackUid != finalDecision.worldPackUid ||
                spec.worldPackVersion != finalDecision.worldPackVersion) {
                return WORLD_PACK_MISMATCH
            }
            val expectationEvidence = CanonExpectationEvidence.uid(
                spec.canonicalReference,
                spec.expectedCanonicalValue
            )
            if (expectationEvidence !in finalDecision.evidenceUids) {
                return EXPECTATION_NOT_AUTHENTICATED
            }
            if (truth.kind != TruthKind.FACT || truth.objectValue != spec.actualCampaignValue) {
                return ACTUAL_NOT_AUTHENTICATED
            }
        }
        return null
    }
}

internal object Phase35CanonDivergenceSchema {
    const val TABLE = "campaign_canon_divergences"
    const val RECORDED_INSERT_GUARD = "rpgos_canon_divergence_recorded_insert_guard"
    const val IMPORT_INSERT_GUARD = "rpgos_canon_divergence_import_insert_guard"
    const val LIFECYCLE_INSERT_GUARD = "rpgos_canon_divergence_lifecycle_insert_guard"

    fun ensureReady(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS $TABLE(
            divergence_uid TEXT PRIMARY KEY,campaign_uid TEXT NOT NULL,
            canonical_subject_kind_uid TEXT NOT NULL,canonical_subject_uid TEXT NOT NULL,
            canonical_expectation_uid TEXT NOT NULL,world_pack_uid TEXT NOT NULL,world_pack_version TEXT NOT NULL,
            divergence_kind TEXT NOT NULL,expected_canonical_value TEXT NOT NULL,actual_campaign_value TEXT NOT NULL,
            lifecycle_status TEXT NOT NULL,created_transaction_uid TEXT,created_turn_uid TEXT,created_event_uid TEXT,
            provenance_status TEXT NOT NULL,effective_from INTEGER,effective_until INTEGER,
            supersedes_divergence_uid TEXT,resolves_divergence_uid TEXT,
            divergence_schema_version INTEGER NOT NULL,created_at_epoch_ms INTEGER NOT NULL,
            CHECK(expected_canonical_value<>actual_campaign_value),
            CHECK(divergence_schema_version=$CANON_DIVERGENCE_SCHEMA_VERSION),
            UNIQUE(campaign_uid,canonical_expectation_uid,created_transaction_uid))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_canon_divergence_campaign ON $TABLE(campaign_uid,lifecycle_status,created_at_epoch_ms,divergence_uid)")
        installGuards(db)
    }

    private fun installGuards(db: SQLiteDatabase) {
        db.execSQL("DROP TRIGGER IF EXISTS $RECORDED_INSERT_GUARD")
        db.execSQL("""CREATE TRIGGER $RECORDED_INSERT_GUARD BEFORE INSERT ON $TABLE
            WHEN NEW.provenance_status='RECORDED'
            BEGIN
                SELECT CASE WHEN NEW.created_transaction_uid IS NULL OR NEW.created_turn_uid IS NULL OR NEW.created_event_uid IS NULL
                    THEN RAISE(ABORT,'RPGOS-CANON:RECORDED_PROVENANCE_REQUIRED') END;
                SELECT CASE WHEN NOT EXISTS(
                    SELECT 1 FROM rpgos_gameplay_mutation_context c
                    WHERE c.campaign_uid=NEW.campaign_uid AND c.capability_kind='TURN'
                ) THEN RAISE(ABORT,'RPGOS-CANON:RECORDED_REQUIRES_CANONICAL_TURN') END;
                SELECT CASE WHEN NOT EXISTS(
                    SELECT 1 FROM canonical_gameplay_events e
                    WHERE e.campaign_uid=NEW.campaign_uid
                      AND e.event_uid=NEW.created_event_uid
                      AND e.transaction_uid=NEW.created_transaction_uid
                      AND e.turn_uid=NEW.created_turn_uid
                ) THEN RAISE(ABORT,'RPGOS-CANON:RECORDED_EVENT_PROVENANCE_INVALID') END;
            END""")

        db.execSQL("DROP TRIGGER IF EXISTS $IMPORT_INSERT_GUARD")
        db.execSQL("""CREATE TRIGGER $IMPORT_INSERT_GUARD BEFORE INSERT ON $TABLE
            WHEN NEW.provenance_status<>'RECORDED'
            BEGIN
                SELECT CASE WHEN NEW.created_transaction_uid IS NOT NULL OR NEW.created_turn_uid IS NOT NULL OR NEW.created_event_uid IS NOT NULL
                    THEN RAISE(ABORT,'RPGOS-CANON:IMPORT_CANNOT_FABRICATE_COMMITTED_PROVENANCE') END;
                SELECT CASE WHEN NOT EXISTS(
                    SELECT 1 FROM rpgos_gameplay_mutation_context c
                    WHERE c.campaign_uid=NEW.campaign_uid AND c.capability_kind='ADMIN'
                ) THEN RAISE(ABORT,'RPGOS-CANON:IMPORT_REQUIRES_ADMIN') END;
            END""")

        db.execSQL("DROP TRIGGER IF EXISTS $LIFECYCLE_INSERT_GUARD")
        db.execSQL("""CREATE TRIGGER $LIFECYCLE_INSERT_GUARD BEFORE INSERT ON $TABLE
            BEGIN
                SELECT CASE WHEN NEW.supersedes_divergence_uid=NEW.divergence_uid OR NEW.resolves_divergence_uid=NEW.divergence_uid
                    THEN RAISE(ABORT,'RPGOS-CANON:LIFECYCLE_SELF_REFERENCE') END;
                SELECT CASE WHEN NEW.supersedes_divergence_uid IS NOT NULL AND NEW.resolves_divergence_uid IS NOT NULL
                    THEN RAISE(ABORT,'RPGOS-CANON:LIFECYCLE_AMBIGUOUS_LINK') END;
                SELECT CASE WHEN NEW.lifecycle_status='ACTIVE' AND (NEW.supersedes_divergence_uid IS NOT NULL OR NEW.resolves_divergence_uid IS NOT NULL)
                    THEN RAISE(ABORT,'RPGOS-CANON:LIFECYCLE_STATUS_LINK_MISMATCH') END;
                SELECT CASE WHEN NEW.lifecycle_status='SUPERSEDED' AND (NEW.supersedes_divergence_uid IS NULL OR NEW.resolves_divergence_uid IS NOT NULL)
                    THEN RAISE(ABORT,'RPGOS-CANON:LIFECYCLE_STATUS_LINK_MISMATCH') END;
                SELECT CASE WHEN NEW.lifecycle_status='RESOLVED' AND (NEW.resolves_divergence_uid IS NULL OR NEW.supersedes_divergence_uid IS NOT NULL)
                    THEN RAISE(ABORT,'RPGOS-CANON:LIFECYCLE_STATUS_LINK_MISMATCH') END;
                SELECT CASE WHEN NEW.supersedes_divergence_uid IS NOT NULL AND NOT EXISTS(
                    SELECT 1 FROM $TABLE d WHERE d.campaign_uid=NEW.campaign_uid AND d.divergence_uid=NEW.supersedes_divergence_uid
                ) THEN RAISE(ABORT,'RPGOS-CANON:SUPERSEDES_TARGET_INVALID') END;
                SELECT CASE WHEN NEW.resolves_divergence_uid IS NOT NULL AND NOT EXISTS(
                    SELECT 1 FROM $TABLE d WHERE d.campaign_uid=NEW.campaign_uid AND d.divergence_uid=NEW.resolves_divergence_uid
                ) THEN RAISE(ABORT,'RPGOS-CANON:RESOLVES_TARGET_INVALID') END;
            END""")
    }

    fun isReady(db: SQLiteDatabase): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(TABLE)
    ).use { it.moveToFirst() }
}

private data class PendingCanonDivergence(
    val campaignUid: String,
    val spec: CanonDivergenceSpec,
    val identity: TurnTransactionIdentity,
    val eventUid: String,
    val createdAt: Long
)

/** In-memory capability buffer. SQL cannot manufacture entries in this buffer. */
internal object CanonDivergenceTurnBuffer {
    private data class Active(
        val db: SQLiteDatabase,
        val campaignUid: String,
        val entries: MutableList<PendingCanonDivergence>
    )

    private val local = ThreadLocal<Active?>()

    fun begin(db: SQLiteDatabase, campaignUid: String) {
        check(local.get() == null) { "RPGOS-CANON:NESTED_TURN_BUFFER" }
        local.set(Active(db, campaignUid, mutableListOf()))
    }

    fun stage(
        db: SQLiteDatabase,
        campaignUid: String,
        spec: CanonDivergenceSpec,
        identity: TurnTransactionIdentity,
        eventUid: String
    ): CanonDivergenceRecord {
        val active = local.get() ?: error("RPGOS-CANON:NO_CANONICAL_TURN_BUFFER")
        check(active.db === db && active.campaignUid == campaignUid) { "RPGOS-CANON:TURN_BUFFER_SCOPE_MISMATCH" }
        val createdAt = spec.effectiveFrom ?: 0L
        val pending = PendingCanonDivergence(campaignUid, spec, identity, eventUid, createdAt)
        active.entries.firstOrNull { it.spec.divergenceUid == spec.divergenceUid }?.let { existing ->
            require(existing == pending) { "RPGOS-CANON:DIVERGENCE_IDENTITY_CONFLICT" }
            return CanonDivergenceRecord(campaignUid, spec, identity.transactionUid, identity.turnUid, eventUid, createdAt)
        }
        active.entries += pending
        return CanonDivergenceRecord(campaignUid, spec, identity.transactionUid, identity.turnUid, eventUid, createdAt)
    }

    fun flush(db: SQLiteDatabase, campaignUid: String) {
        val active = local.get() ?: return
        check(active.db === db && active.campaignUid == campaignUid) { "RPGOS-CANON:TURN_BUFFER_SCOPE_MISMATCH" }
        active.entries.forEach { pending ->
            CanonDivergenceStore(db, campaignUid).finalizeCommitted(pending)
        }
        active.entries.clear()
    }

    fun clear() {
        local.remove()
    }
}

class CanonDivergenceStore(private val db: SQLiteDatabase, private val campaignUid: String) {
    init { require(campaignUid.isNotBlank()) }

    fun list(): List<CanonDivergenceRecord> {
        if (!Phase35CanonDivergenceSchema.isReady(db)) return emptyList()
        return db.rawQuery("""SELECT divergence_uid,canonical_subject_kind_uid,canonical_subject_uid,
            canonical_expectation_uid,world_pack_uid,world_pack_version,divergence_kind,expected_canonical_value,
            actual_campaign_value,lifecycle_status,created_transaction_uid,created_turn_uid,created_event_uid,
            provenance_status,effective_from,effective_until,supersedes_divergence_uid,resolves_divergence_uid,
            divergence_schema_version,created_at_epoch_ms FROM ${Phase35CanonDivergenceSchema.TABLE}
            WHERE campaign_uid=? ORDER BY created_at_epoch_ms,divergence_uid""", arrayOf(campaignUid)).use { c ->
            buildList { while (c.moveToNext()) add(decode(c)) }
        }
    }

    /** Verified legacy/import material only. Unknown historical fields remain null. */
    fun importVerified(spec: CanonDivergenceSpec): CanonDivergenceRecord {
        requireAdministrativeRecoveryEntryPoint()
        require(spec.provenanceStatus != HistoricalProvenanceStatus.RECORDED)
        validateLifecycle(spec)
        return insert(spec, null, null, null, System.currentTimeMillis())
    }

    /**
     * Records intent to persist RECORDED evidence. Durable insertion is deferred until the end of
     * the sealed canonical gameplay capability, after Event Store append but before outer commit.
     */
    internal fun recordCommitted(
        spec: CanonDivergenceSpec,
        identity: TurnTransactionIdentity,
        eventUid: String
    ): CanonDivergenceRecord {
        require(identity.campaignUid == campaignUid) { "RPGOS-CANON:CAMPAIGN_MISMATCH" }
        check(db.inTransaction()) { "RPGOS-CANON:OUTSIDE_TURN" }
        requireCanonicalGameplayMutation(db, campaignUid)
        require(spec.provenanceStatus == HistoricalProvenanceStatus.RECORDED)
        require(eventUid.isNotBlank())
        validateLifecycle(spec)
        return CanonDivergenceTurnBuffer.stage(db, campaignUid, spec, identity, eventUid)
    }

    internal fun finalizeCommitted(pending: PendingCanonDivergence): CanonDivergenceRecord {
        require(pending.campaignUid == campaignUid && pending.identity.campaignUid == campaignUid) {
            "RPGOS-CANON:CAMPAIGN_MISMATCH"
        }
        check(db.inTransaction()) { "RPGOS-CANON:OUTSIDE_TURN" }
        requireCanonicalGameplayMutation(db, campaignUid)
        require(pending.spec.provenanceStatus == HistoricalProvenanceStatus.RECORDED)
        validateEventProvenance(pending.identity, pending.eventUid)
        validateLifecycle(pending.spec)
        return insert(
            pending.spec,
            pending.identity.transactionUid,
            pending.identity.turnUid,
            pending.eventUid,
            pending.createdAt
        )
    }

    private fun validateEventProvenance(identity: TurnTransactionIdentity, eventUid: String) {
        val valid = db.rawQuery(
            """SELECT 1 FROM canonical_gameplay_events
                WHERE campaign_uid=? AND event_uid=? AND transaction_uid=? AND turn_uid=? AND command_uid=? LIMIT 1""",
            arrayOf(campaignUid, eventUid, identity.transactionUid, identity.turnUid, identity.commandUid)
        ).use { it.moveToFirst() }
        require(valid) { "RPGOS-CANON:INVALID_COMMITTED_EVENT_PROVENANCE" }
    }

    private fun validateLifecycle(spec: CanonDivergenceSpec) {
        require(spec.supersedesDivergenceUid != spec.divergenceUid && spec.resolvesDivergenceUid != spec.divergenceUid) {
            "RPGOS-CANON:LIFECYCLE_SELF_REFERENCE"
        }
        require(spec.supersedesDivergenceUid == null || spec.resolvesDivergenceUid == null) {
            "RPGOS-CANON:LIFECYCLE_AMBIGUOUS_LINK"
        }
        when (spec.status) {
            CanonDivergenceStatus.ACTIVE -> require(spec.supersedesDivergenceUid == null && spec.resolvesDivergenceUid == null) {
                "RPGOS-CANON:LIFECYCLE_STATUS_LINK_MISMATCH"
            }
            CanonDivergenceStatus.SUPERSEDED -> {
                val target = requireNotNull(spec.supersedesDivergenceUid) { "RPGOS-CANON:LIFECYCLE_STATUS_LINK_MISMATCH" }
                require(spec.resolvesDivergenceUid == null) { "RPGOS-CANON:LIFECYCLE_STATUS_LINK_MISMATCH" }
                require(existing(target) != null) { "RPGOS-CANON:SUPERSEDES_TARGET_INVALID" }
            }
            CanonDivergenceStatus.RESOLVED -> {
                val target = requireNotNull(spec.resolvesDivergenceUid) { "RPGOS-CANON:LIFECYCLE_STATUS_LINK_MISMATCH" }
                require(spec.supersedesDivergenceUid == null) { "RPGOS-CANON:LIFECYCLE_STATUS_LINK_MISMATCH" }
                require(existing(target) != null) { "RPGOS-CANON:RESOLVES_TARGET_INVALID" }
            }
        }
    }

    private fun insert(spec: CanonDivergenceSpec, transactionUid: String?, turnUid: String?, eventUid: String?, createdAt: Long): CanonDivergenceRecord {
        existing(spec.divergenceUid)?.let { existing ->
            require(existing.spec == spec && existing.createdTransactionUid == transactionUid &&
                existing.createdTurnUid == turnUid && existing.createdEventUid == eventUid) {
                "RPGOS-CANON:DIVERGENCE_IDENTITY_CONFLICT"
            }
            return existing
        }
        db.insertOrThrow(Phase35CanonDivergenceSchema.TABLE, null, ContentValues().apply {
            put("divergence_uid", spec.divergenceUid); put("campaign_uid", campaignUid)
            put("canonical_subject_kind_uid", spec.canonicalReference.subjectKindUid)
            put("canonical_subject_uid", spec.canonicalReference.subjectUid)
            put("canonical_expectation_uid", spec.canonicalReference.expectationUid)
            put("world_pack_uid", spec.worldPackUid); put("world_pack_version", spec.worldPackVersion)
            put("divergence_kind", spec.kind.name); put("expected_canonical_value", spec.expectedCanonicalValue)
            put("actual_campaign_value", spec.actualCampaignValue); put("lifecycle_status", spec.status.name)
            put("created_transaction_uid", transactionUid); put("created_turn_uid", turnUid); put("created_event_uid", eventUid)
            put("provenance_status", spec.provenanceStatus.name); put("effective_from", spec.effectiveFrom)
            put("effective_until", spec.effectiveUntil); put("supersedes_divergence_uid", spec.supersedesDivergenceUid)
            put("resolves_divergence_uid", spec.resolvesDivergenceUid); put("divergence_schema_version", spec.schemaVersion)
            put("created_at_epoch_ms", createdAt)
        })
        return requireNotNull(existing(spec.divergenceUid))
    }

    private fun existing(uid: String): CanonDivergenceRecord? = db.rawQuery(
        """SELECT divergence_uid,canonical_subject_kind_uid,canonical_subject_uid,canonical_expectation_uid,
            world_pack_uid,world_pack_version,divergence_kind,expected_canonical_value,actual_campaign_value,
            lifecycle_status,created_transaction_uid,created_turn_uid,created_event_uid,provenance_status,
            effective_from,effective_until,supersedes_divergence_uid,resolves_divergence_uid,
            divergence_schema_version,created_at_epoch_ms FROM ${Phase35CanonDivergenceSchema.TABLE}
            WHERE campaign_uid=? AND divergence_uid=?""", arrayOf(campaignUid, uid)
    ).use { if (it.moveToFirst()) decode(it) else null }

    private fun decode(c: android.database.Cursor): CanonDivergenceRecord = CanonDivergenceRecord(
        campaignUid,
        CanonDivergenceSpec(
            c.getString(0), CanonReference(c.getString(1), c.getString(2), c.getString(3)),
            c.getString(4), c.getString(5), CanonDivergenceKind.valueOf(c.getString(6)), c.getString(7), c.getString(8),
            CanonDivergenceStatus.valueOf(c.getString(9)), longOrNull(c, 14), longOrNull(c, 15),
            stringOrNull(c, 16), stringOrNull(c, 17), HistoricalProvenanceStatus.valueOf(c.getString(13)), c.getInt(18)
        ), stringOrNull(c, 10), stringOrNull(c, 11), stringOrNull(c, 12), c.getLong(19)
    )

    private fun stringOrNull(c: android.database.Cursor, i: Int) = if (c.isNull(i)) null else c.getString(i)
    private fun longOrNull(c: android.database.Cursor, i: Int) = if (c.isNull(i)) null else c.getLong(i)
}
