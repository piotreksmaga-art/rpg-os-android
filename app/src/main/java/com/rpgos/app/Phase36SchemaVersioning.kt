package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.security.MessageDigest
import java.util.UUID

enum class SchemaFamilyUid {
    ENGINE, CAMPAIGN, WORLD_PACK, PLAYER, RECEIPT, EVENT, CAUSAL, SNAPSHOT, REPLAY,
    CANON_DIVERGENCE, FINANCE, INVENTORY, OWNERSHIP, DEVELOPMENT_PROJECT
}

enum class MigrationMateriality { STRUCTURAL_ADDITIVE, MATERIAL_DATA_MUTATION }

data class SchemaFamilyContract(
    val family: SchemaFamilyUid,
    val currentVersion: Int,
    val minimumSupportedVersion: Int,
    val dependencies: Set<SchemaFamilyUid> = emptySet(),
    val materiality: MigrationMateriality = MigrationMateriality.STRUCTURAL_ADDITIVE
)

class UnsupportedFutureSchemaException(val family: SchemaFamilyUid, val found: Int, val maximum: Int) :
    IllegalStateException("RPGOS-SCHEMA:UNSUPPORTED_FUTURE:$family:$found:$maximum")

class MigrationPlanMismatchException(val stored: String, val current: String) :
    IllegalStateException("RPGOS-SCHEMA:MIGRATION_PLAN_MISMATCH:$stored:$current")

enum class MigrationAttemptState { PREPARED, RUNNING, APPLIED, FAILED }

internal object Phase36SchemaVersioning {
    const val VERSIONS = "rpgos_schema_family_versions"
    const val ATTEMPTS = "rpgos_migration_attempts"
    const val PLAN_VERSION = 1
    const val PLAN_IMPLEMENTATION_REVISION = "RPGOS-P36-MIGRATION-IMPL-1"

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

    fun ensureReady(db: SQLiteDatabase, campaignUid: String, safetySnapshotUid: String? = null) {
        require(!db.inTransaction()) { "RPGOS-SCHEMA:TOP_LEVEL_MIGRATION_REQUIRED" }
        inspectCompatibilityBeforeMutation(db)
        ensureMetadataTables(db)
        val pending = contracts.filter { contract ->
            val found = current(db, contract.family)
            found == null || found < contract.currentVersion
        }
        if (pending.isEmpty()) {
            recoverInterrupted(db, campaignUid, currentPlanFingerprint = null)
            return
        }
        val ordered = MigrationPlanRegistry.order(pending)
        val plan = MigrationPlanRegistry.fingerprint(ordered)
        recoverInterrupted(db, campaignUid, currentPlanFingerprint = plan)
        MigrationSafetyPolicy.requireProtectedSnapshot(db, campaignUid, ordered, safetySnapshotUid)

        val source = vectorFingerprint(db)
        val target = targetFingerprint()
        val attempt = "MIG-$campaignUid-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        administrativeWrite(db, campaignUid) {
            db.execSQL("""INSERT INTO $ATTEMPTS(
                migration_attempt_uid,campaign_uid,source_vector_fingerprint,target_vector_fingerprint,
                plan_fingerprint,plan_version,safety_snapshot_uid,state,started_at_epoch_ms)
                VALUES(?,?,?,?,?,?,?,?,?)""",
                arrayOf(attempt, campaignUid, source, target, plan, PLAN_VERSION, safetySnapshotUid,
                    MigrationAttemptState.PREPARED.name, now))
        }

        try {
            administrativeWrite(db, campaignUid) {
                db.execSQL("UPDATE $ATTEMPTS SET state=? WHERE migration_attempt_uid=?", arrayOf(MigrationAttemptState.RUNNING.name, attempt))
                Phase35CanonDivergenceSchema.ensureReady(db)
                ordered.forEach { contract ->
                    db.execSQL("INSERT OR REPLACE INTO $VERSIONS(schema_family_uid,schema_version,migration_owner,updated_at_epoch_ms) VALUES(?,?,?,?)",
                        arrayOf(contract.family.name, contract.currentVersion, "GameplayRuntimeBootstrap", now))
                }
                db.execSQL("UPDATE $ATTEMPTS SET state=?,completed_at_epoch_ms=? WHERE migration_attempt_uid=?",
                    arrayOf(MigrationAttemptState.APPLIED.name, System.currentTimeMillis(), attempt))
            }
        } catch (t: Throwable) {
            administrativeWrite(db, campaignUid) {
                db.execSQL("UPDATE $ATTEMPTS SET state=?,completed_at_epoch_ms=?,failure_code=? WHERE migration_attempt_uid=?",
                    arrayOf(MigrationAttemptState.FAILED.name, System.currentTimeMillis(), "MIGRATION_STEP_FAILED", attempt))
            }
            throw t
        }
    }

    /** Must run before any bootstrap migration writes. */
    fun requireNoUnsupportedFuture(db: SQLiteDatabase) = inspectCompatibilityBeforeMutation(db)

    fun requireReady(db: SQLiteDatabase) {
        check(table(db, VERSIONS) && table(db, ATTEMPTS) && Phase35CanonDivergenceSchema.isReady(db)) { "RPGOS-SCHEMA:NOT_READY" }
        contracts.forEach { c ->
            val found = current(db, c.family) ?: error("RPGOS-SCHEMA:MISSING_FAMILY:${c.family}")
            if (found > c.currentVersion) throw UnsupportedFutureSchemaException(c.family, found, c.currentVersion)
            check(found == c.currentVersion) { "RPGOS-SCHEMA:FAMILY_NOT_CURRENT:${c.family}:$found:${c.currentVersion}" }
        }
        check(db.rawQuery("SELECT 1 FROM $ATTEMPTS WHERE state IN (?,?) LIMIT 1", arrayOf(MigrationAttemptState.PREPARED.name, MigrationAttemptState.RUNNING.name)).use { !it.moveToFirst() }) {
            "RPGOS-SCHEMA:INCOMPLETE_MIGRATION"
        }
    }

    fun activeSafetySnapshotUids(db: SQLiteDatabase, campaignUid: String): Set<String> {
        if (!table(db, ATTEMPTS)) return emptySet()
        return db.rawQuery("""SELECT safety_snapshot_uid FROM $ATTEMPTS
            WHERE campaign_uid=? AND state IN (?,?) AND safety_snapshot_uid IS NOT NULL""",
            arrayOf(campaignUid, MigrationAttemptState.PREPARED.name, MigrationAttemptState.RUNNING.name)).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
    }

    private fun ensureMetadataTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS $VERSIONS(
            schema_family_uid TEXT PRIMARY KEY,schema_version INTEGER NOT NULL,
            migration_owner TEXT NOT NULL,updated_at_epoch_ms INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS $ATTEMPTS(
            migration_attempt_uid TEXT PRIMARY KEY,campaign_uid TEXT NOT NULL,
            source_vector_fingerprint TEXT NOT NULL,target_vector_fingerprint TEXT NOT NULL,
            plan_fingerprint TEXT NOT NULL,plan_version INTEGER NOT NULL,safety_snapshot_uid TEXT,
            state TEXT NOT NULL,started_at_epoch_ms INTEGER NOT NULL,completed_at_epoch_ms INTEGER,
            failure_code TEXT)""")
    }

    private fun inspectCompatibilityBeforeMutation(db: SQLiteDatabase) {
        if (!table(db, VERSIONS)) return
        contracts.forEach { c ->
            current(db, c.family)?.let { found ->
                if (found > c.currentVersion) throw UnsupportedFutureSchemaException(c.family, found, c.currentVersion)
                require(found >= c.minimumSupportedVersion) { "RPGOS-SCHEMA:UNSUPPORTED_OLD:${c.family}:$found:${c.minimumSupportedVersion}" }
            }
        }
    }

    private fun recoverInterrupted(db: SQLiteDatabase, campaignUid: String, currentPlanFingerprint: String?) {
        val active = db.rawQuery("""SELECT migration_attempt_uid,plan_fingerprint FROM $ATTEMPTS
            WHERE campaign_uid=? AND state IN (?,?) ORDER BY started_at_epoch_ms,migration_attempt_uid""",
            arrayOf(campaignUid, MigrationAttemptState.PREPARED.name, MigrationAttemptState.RUNNING.name)).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
        }
        if (currentPlanFingerprint != null) {
            active.firstOrNull { it.second != currentPlanFingerprint }?.let { mismatch ->
                throw MigrationPlanMismatchException(mismatch.second, currentPlanFingerprint)
            }
        }
        if (active.isNotEmpty()) {
            administrativeWrite(db, campaignUid) {
                db.execSQL("UPDATE $ATTEMPTS SET state=?,completed_at_epoch_ms=?,failure_code=? WHERE campaign_uid=? AND state IN (?,?)",
                    arrayOf(MigrationAttemptState.FAILED.name, System.currentTimeMillis(), "INTERRUPTED_RESTART_SAFE", campaignUid,
                        MigrationAttemptState.PREPARED.name, MigrationAttemptState.RUNNING.name))
            }
        }
    }

    private fun administrativeWrite(db: SQLiteDatabase, campaignUid: String, block: () -> Unit) {
        if (GameplayMutationDatabaseGuards.isInstalled(db)) {
            withAdministrativeMutationAuthority(db, campaignUid) { block() }
        } else {
            db.beginTransaction()
            try {
                block()
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun current(db: SQLiteDatabase, family: SchemaFamilyUid): Int? = if (!table(db, VERSIONS)) null else db.rawQuery(
        "SELECT schema_version FROM $VERSIONS WHERE schema_family_uid=?", arrayOf(family.name)
    ).use { if (it.moveToFirst()) it.getInt(0) else null }

    private fun vectorFingerprint(db: SQLiteDatabase): String = contracts.joinToString("|") { "${it.family}:${current(db, it.family) ?: 0}" }.sha256()
    private fun targetFingerprint(): String = contracts.joinToString("|") { "${it.family}:${it.currentVersion}" }.sha256()
    private fun table(db: SQLiteDatabase, name: String) = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)).use { it.moveToFirst() }
}

internal object MigrationSafetyPolicy {
    fun requireProtectedSnapshot(
        db: SQLiteDatabase,
        campaignUid: String,
        steps: List<SchemaFamilyContract>,
        safetySnapshotUid: String?
    ) {
        if (steps.none { it.materiality == MigrationMateriality.MATERIAL_DATA_MUTATION }) return
        val uid = requireNotNull(safetySnapshotUid) { "RPGOS-SCHEMA:MATERIAL_MIGRATION_REQUIRES_SAFETY_SNAPSHOT" }
        require(CampaignSnapshotSchema.isReady(db)) { "RPGOS-SCHEMA:SNAPSHOT_SCHEMA_NOT_READY" }
        val row = db.rawQuery("""SELECT snapshot_kind,snapshot_schema_version,payload_path,payload_sha256,publication_state,pinned
            FROM ${CampaignSnapshotSchema.CATALOG} WHERE campaign_uid=? AND snapshot_uid=? LIMIT 1""",
            arrayOf(campaignUid, uid)).use { c ->
            if (!c.moveToFirst()) null else SafetySnapshotRow(
                SnapshotKind.valueOf(c.getString(0)), c.getInt(1), c.getString(2),
                if (c.isNull(3)) null else c.getString(3), SnapshotPublicationState.valueOf(c.getString(4)), c.getInt(5) != 0
            )
        } ?: error("RPGOS-SCHEMA:SAFETY_SNAPSHOT_NOT_FOUND")
        require(row.state == SnapshotPublicationState.VALID && row.schemaVersion == CampaignSnapshotSchema.VERSION) {
            "RPGOS-SCHEMA:SAFETY_SNAPSHOT_NOT_VALID"
        }
        require(row.pinned || row.kind != SnapshotKind.AUTOMATIC) { "RPGOS-SCHEMA:SAFETY_SNAPSHOT_NOT_PROTECTED" }
        val file = File(row.path)
        require(file.isFile && row.sha256 != null && sha256File(file) == row.sha256) { "RPGOS-SCHEMA:SAFETY_SNAPSHOT_PAYLOAD_INVALID" }
    }

    private data class SafetySnapshotRow(
        val kind: SnapshotKind,
        val schemaVersion: Int,
        val path: String,
        val sha256: String?,
        val state: SnapshotPublicationState,
        val pinned: Boolean
    )

    private fun sha256File(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
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
        "${it.family}:${it.minimumSupportedVersion}->${it.currentVersion}:${it.materiality.name}:${it.dependencies.map { d -> d.name }.sorted().joinToString(",")}:v${Phase36SchemaVersioning.PLAN_VERSION}:${Phase36SchemaVersioning.PLAN_IMPLEMENTATION_REVISION}"
    }.sha256()
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256").digest(toByteArray()).joinToString("") { "%02x".format(it) }
