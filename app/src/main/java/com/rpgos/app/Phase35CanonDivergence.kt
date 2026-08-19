package com.rpgos.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest
import java.util.UUID

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

enum class SchemaFamilyUid {
    ENGINE, CAMPAIGN, WORLD_PACK, PLAYER, RECEIPT, EVENT, CAUSAL, SNAPSHOT, REPLAY,
    CANON_DIVERGENCE, FINANCE, INVENTORY, OWNERSHIP, DEVELOPMENT_PROJECT
}

data class SchemaFamilyContract(
    val family: SchemaFamilyUid,
    val currentVersion: Int,
    val minimumSupportedVersion: Int,
    val dependencies: Set<SchemaFamilyUid> = emptySet()
)

class UnsupportedFutureSchemaException(val family: SchemaFamilyUid, val found: Int, val maximum: Int) :
    IllegalStateException("RPGOS-SCHEMA:UNSUPPORTED_FUTURE:$family:$found:$maximum")

enum class MigrationAttemptState { PREPARED, RUNNING, APPLIED, FAILED }

internal object Phase36SchemaVersioning {
    const val VERSIONS = "rpgos_schema_family_versions"
    const val ATTEMPTS = "rpgos_migration_attempts"
    const val PLAN_VERSION = 1
    val contracts = listOf(
        SchemaFamilyContract(SchemaFamilyUid.ENGINE, 1, 1),
        SchemaFamilyContract(SchemaFamilyUid.CAMPAIGN, 1, 1, setOf(SchemaFamilyUid.ENGINE)),
        SchemaFamilyContract(SchemaFamilyUid.WORLD_PACK, 1, 1, setOf(SchemaFamilyUid.ENGINE)),
        SchemaFamilyContract(SchemaFamilyUid.PLAYER, 1, 1, setOf(SchemaFamilyUid.CAMPAIGN)),
        SchemaFamilyContract(SchemaFamilyUid.RECEIPT, TURN_TRANSACTION_RECEIPT_VERSION, 1, setOf(SchemaFamilyUid.CAMPAIGN)),
        SchemaFamilyContract(SchemaFamilyUid.EVENT, PHASE30_EVENT_SCHEMA_VERSION, 1, setOf(SchemaFamilyUid.RECEIPT)),
        SchemaFamilyContract(SchemaFamilyUid.CAUSAL, PHASE31_CAUSAL_SCHEMA_VERSION, 1, setOf(SchemaFamilyUid.EVENT)),
        SchemaFamilyContract(SchemaFamilyUid.SNAPSHOT, CampaignSnapshotSchema.VERSION, 1, setOf(SchemaFamilyUid.CAMPAIGN)),
        SchemaFamilyContract(SchemaFamilyUid.REPLAY, 1, 1, setOf(SchemaFamilyUid.SNAPSHOT, SchemaFamilyUid.EVENT)),
        SchemaFamilyContract(SchemaFamilyUid.CANON_DIVERGENCE, CANON_DIVERGENCE_SCHEMA_VERSION, 1, setOf(SchemaFamilyUid.EVENT)),
        SchemaFamilyContract(SchemaFamilyUid.FINANCE, 1, 1, setOf(SchemaFamilyUid.PLAYER)),
        SchemaFamilyContract(SchemaFamilyUid.INVENTORY, 1, 1, setOf(SchemaFamilyUid.PLAYER)),
        SchemaFamilyContract(SchemaFamilyUid.OWNERSHIP, 1, 1, setOf(SchemaFamilyUid.INVENTORY)),
        SchemaFamilyContract(SchemaFamilyUid.DEVELOPMENT_PROJECT, 1, 1, setOf(SchemaFamilyUid.PLAYER))
    )

    fun ensureReady(db: SQLiteDatabase, campaignUid: String) {
        inspectFutureBeforeMutation(db)
        db.execSQL("""CREATE TABLE IF NOT EXISTS $VERSIONS(
            schema_family_uid TEXT PRIMARY KEY,schema_version INTEGER NOT NULL,
            migration_owner TEXT NOT NULL,updated_at_epoch_ms INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS $ATTEMPTS(
            migration_attempt_uid TEXT PRIMARY KEY,campaign_uid TEXT NOT NULL,
            source_vector_fingerprint TEXT NOT NULL,target_vector_fingerprint TEXT NOT NULL,
            plan_fingerprint TEXT NOT NULL,plan_version INTEGER NOT NULL,safety_snapshot_uid TEXT,
            state TEXT NOT NULL,started_at_epoch_ms INTEGER NOT NULL,completed_at_epoch_ms INTEGER,
            failure_code TEXT)""")
        recoverInterrupted(db, campaignUid)
        val missing = contracts.filter { current(db, it.family) == null }
        if (missing.isEmpty()) return
        val ordered = MigrationPlanRegistry.order(missing)
        val source = vectorFingerprint(db)
        val target = targetFingerprint()
        val plan = MigrationPlanRegistry.fingerprint(ordered)
        val attempt = "MIG-$campaignUid-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        db.execSQL("INSERT INTO $ATTEMPTS(migration_attempt_uid,campaign_uid,source_vector_fingerprint,target_vector_fingerprint,plan_fingerprint,plan_version,state,started_at_epoch_ms) VALUES(?,?,?,?,?,?,?,?)",
            arrayOf(attempt, campaignUid, source, target, plan, PLAN_VERSION, MigrationAttemptState.PREPARED.name, now))
        db.beginTransaction()
        try {
            db.execSQL("UPDATE $ATTEMPTS SET state=? WHERE migration_attempt_uid=?", arrayOf(MigrationAttemptState.RUNNING.name, attempt))
            Phase35CanonDivergenceSchema.ensureReady(db)
            ordered.forEach { contract -> db.execSQL("INSERT OR REPLACE INTO $VERSIONS(schema_family_uid,schema_version,migration_owner,updated_at_epoch_ms) VALUES(?,?,?,?)",
                arrayOf(contract.family.name, contract.currentVersion, "GameplayRuntimeBootstrap", now)) }
            db.execSQL("UPDATE $ATTEMPTS SET state=?,completed_at_epoch_ms=? WHERE migration_attempt_uid=?",
                arrayOf(MigrationAttemptState.APPLIED.name, System.currentTimeMillis(), attempt))
            db.setTransactionSuccessful()
        } catch (t: Throwable) {
            throw t
        } finally { db.endTransaction() }
    }

    /** Must run before any bootstrap migration writes. */
    fun requireNoUnsupportedFuture(db: SQLiteDatabase) = inspectFutureBeforeMutation(db)

    fun requireReady(db: SQLiteDatabase) {
        check(table(db, VERSIONS) && table(db, ATTEMPTS) && Phase35CanonDivergenceSchema.isReady(db)) { "RPGOS-SCHEMA:NOT_READY" }
        contracts.forEach { c ->
            val found = current(db, c.family) ?: error("RPGOS-SCHEMA:MISSING_FAMILY:${c.family}")
            if (found > c.currentVersion) throw UnsupportedFutureSchemaException(c.family, found, c.currentVersion)
            check(found >= c.minimumSupportedVersion) { "RPGOS-SCHEMA:UNSUPPORTED_OLD:${c.family}:$found" }
        }
        check(db.rawQuery("SELECT 1 FROM $ATTEMPTS WHERE state IN (?,?) LIMIT 1", arrayOf(MigrationAttemptState.PREPARED.name, MigrationAttemptState.RUNNING.name)).use { !it.moveToFirst() }) {
            "RPGOS-SCHEMA:INCOMPLETE_MIGRATION"
        }
    }

    private fun inspectFutureBeforeMutation(db: SQLiteDatabase) {
        if (!table(db, VERSIONS)) return
        contracts.forEach { c -> current(db, c.family)?.let { if (it > c.currentVersion) throw UnsupportedFutureSchemaException(c.family, it, c.currentVersion) } }
    }

    private fun recoverInterrupted(db: SQLiteDatabase, campaignUid: String) {
        db.execSQL("UPDATE $ATTEMPTS SET state=?,completed_at_epoch_ms=?,failure_code=? WHERE campaign_uid=? AND state IN (?,?)",
            arrayOf(MigrationAttemptState.FAILED.name, System.currentTimeMillis(), "INTERRUPTED_RESTART_SAFE", campaignUid,
                MigrationAttemptState.PREPARED.name, MigrationAttemptState.RUNNING.name))
    }

    private fun current(db: SQLiteDatabase, family: SchemaFamilyUid): Int? = if (!table(db, VERSIONS)) null else db.rawQuery(
        "SELECT schema_version FROM $VERSIONS WHERE schema_family_uid=?", arrayOf(family.name)
    ).use { if (it.moveToFirst()) it.getInt(0) else null }

    private fun vectorFingerprint(db: SQLiteDatabase): String = contracts.joinToString("|") { "${it.family}:${current(db, it.family) ?: 0}" }.sha256()
    private fun targetFingerprint(): String = contracts.joinToString("|") { "${it.family}:${it.currentVersion}" }.sha256()
    private fun table(db: SQLiteDatabase, name: String) = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)).use { it.moveToFirst() }
}

internal object MigrationPlanRegistry {
    fun order(steps: List<SchemaFamilyContract>): List<SchemaFamilyContract> {
        require(steps.map { it.family }.toSet().size == steps.size) { "RPGOS-SCHEMA:AMBIGUOUS_MIGRATION_PATH" }
        val remaining = steps.associateBy { it.family }.toMutableMap()
        val result = mutableListOf<SchemaFamilyContract>()
        while (remaining.isNotEmpty()) {
            val ready = remaining.values.filter { step -> step.dependencies.none { it in remaining } }.sortedBy { it.family.name }
            require(ready.isNotEmpty()) { "RPGOS-SCHEMA:MIGRATION_DEPENDENCY_CYCLE" }
            ready.forEach { result += it; remaining.remove(it.family) }
        }
        return result
    }

    fun fingerprint(steps: List<SchemaFamilyContract>): String = steps.joinToString("|") {
        "${it.family}:${it.minimumSupportedVersion}->${it.currentVersion}:${it.dependencies.map { d -> d.name }.sorted().joinToString(",")}:v${Phase36SchemaVersioning.PLAN_VERSION}"
    }.sha256()
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256").digest(toByteArray()).joinToString("") { "%02x".format(it) }
