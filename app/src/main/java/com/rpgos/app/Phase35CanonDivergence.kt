package com.rpgos.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

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

internal object Phase35CanonDivergenceSchema {
    const val TABLE = "campaign_canon_divergences"

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
    }

    fun isReady(db: SQLiteDatabase): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(TABLE)
    ).use { it.moveToFirst() }
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
        return insert(spec, null, null, null, System.currentTimeMillis())
    }

    internal fun recordCommitted(
        spec: CanonDivergenceSpec,
        identity: TurnTransactionIdentity,
        eventUid: String
    ): CanonDivergenceRecord {
        check(db.inTransaction()) { "RPGOS-CANON:OUTSIDE_TURN" }
        require(spec.provenanceStatus == HistoricalProvenanceStatus.RECORDED)
        return insert(spec, identity.transactionUid, identity.turnUid, eventUid, spec.effectiveFrom ?: 0L)
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
