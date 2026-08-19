package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
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

internal data class VersionMigrationEdge(
    val family: SchemaFamilyUid,
    val fromVersion: Int,
    val toVersion: Int,
    val implementationId: String,
    val materiality: MigrationMateriality,
    val migrate: (SQLiteDatabase, String) -> Unit
) {
    init {
        require(fromVersion >= 0 && toVersion >= 0 && fromVersion != toVersion)
        require(implementationId.isNotBlank())
    }

    fun semanticIdentity(dependencies: Set<SchemaFamilyUid>, planVersion: Int): String =
        listOf(
            family.name,
            "$fromVersion->$toVersion",
            materiality.name,
            implementationId,
            dependencies.map { it.name }.sorted().joinToString(","),
            "plan=$planVersion"
        ).joinToString(":")
}

internal class VersionMigrationGraph(private val edges: List<VersionMigrationEdge>) {
    init {
        require(edges.map { Triple(it.family, it.fromVersion, it.toVersion) }.toSet().size == edges.size) {
            "RPGOS-SCHEMA:DUPLICATE_MIGRATION_EDGE"
        }
    }

    fun route(family: SchemaFamilyUid, fromVersion: Int, toVersion: Int): List<VersionMigrationEdge> {
        if (fromVersion == toVersion) return emptyList()
        val familyEdges = edges.filter { it.family == family }
        val paths = mutableListOf<List<VersionMigrationEdge>>()
        var cycleSeen = false

        fun dfs(version: Int, path: List<VersionMigrationEdge>, seen: Set<Int>) {
            if (version == toVersion) {
                paths += path
                return
            }
            familyEdges.filter { it.fromVersion == version }
                .sortedWith(compareBy<VersionMigrationEdge> { it.toVersion }.thenBy { it.implementationId })
                .forEach { edge ->
                    if (edge.toVersion in seen) {
                        cycleSeen = true
                    } else {
                        dfs(edge.toVersion, path + edge, seen + edge.toVersion)
                    }
                }
        }

        dfs(fromVersion, emptyList(), setOf(fromVersion))
        require(!cycleSeen) { "RPGOS-SCHEMA:MIGRATION_VERSION_CYCLE:$family:$fromVersion:$toVersion" }
        require(paths.isNotEmpty()) { "RPGOS-SCHEMA:MISSING_MIGRATION_EDGE:$family:$fromVersion:$toVersion" }
        require(paths.size == 1) { "RPGOS-SCHEMA:AMBIGUOUS_MIGRATION_PATH:$family:$fromVersion:$toVersion" }
        return paths.single()
    }

    fun manifestFingerprint(contracts: List<SchemaFamilyContract>, planVersion: Int): String =
        edges.sortedWith(compareBy<VersionMigrationEdge> { it.family.name }.thenBy { it.fromVersion }.thenBy { it.toVersion }.thenBy { it.implementationId })
            .joinToString("|") { edge ->
                val contract = contracts.single { it.family == edge.family }
                edge.semanticIdentity(contract.dependencies, planVersion)
            }.sha256()

    fun allEdges(): List<VersionMigrationEdge> = edges.toList()
}

internal data class PlannedMigration(
    val contract: SchemaFamilyContract,
    val sourceVersion: Int,
    val edges: List<VersionMigrationEdge>
) {
    val isMaterial: Boolean get() = edges.any { it.materiality == MigrationMateriality.MATERIAL_DATA_MUTATION }
}

class UnsupportedFutureSchemaException(val family: SchemaFamilyUid, val found: Int, val maximum: Int) :
    IllegalStateException("RPGOS-SCHEMA:UNSUPPORTED_FUTURE:$family:$found:$maximum")

class MigrationPlanMismatchException(val stored: String, val current: String) :
    IllegalStateException("RPGOS-SCHEMA:MIGRATION_PLAN_MISMATCH:$stored:$current")

class CorruptMigrationAttemptException(code: String) :
    IllegalStateException("RPGOS-SCHEMA:CORRUPT_MIGRATION_ATTEMPT:$code")

/** Test-only process-death analogue: caller intentionally bypasses the ordinary FAILED finalizer. */
internal class SimulatedMigrationProcessDeath(point: String) : RuntimeException("RPGOS-SCHEMA:SIMULATED_PROCESS_DEATH:$point")

enum class MigrationAttemptState { PREPARED, RUNNING, APPLIED, FAILED }

internal object Phase36SchemaVersioning {
    const val VERSIONS = "rpgos_schema_family_versions"
    const val ATTEMPTS = "rpgos_migration_attempts"
    const val PLAN_VERSION = 2

    val contracts = listOf(
        SchemaFamilyContract(SchemaFamilyUid.ENGINE, 1, 1),
        SchemaFamilyContract(SchemaFamilyUid.CAMPAIGN, 1, 1, setOf(SchemaFamilyUid.ENGINE)),
        SchemaFamilyContract(SchemaFamilyUid.WORLD_PACK, 1, 1, setOf(SchemaFamilyUid.ENGINE)),
        SchemaFamilyContract(SchemaFamilyUid.PLAYER, 1, 1, setOf(SchemaFamilyUid.CAMPAIGN)),
        SchemaFamilyContract(SchemaFamilyUid.RECEIPT, TURN_TRANSACTION_RECEIPT_VERSION, 1, setOf(SchemaFamilyUid.CAMPAIGN)),
        SchemaFamilyContract(SchemaFamilyUid.EVENT, PHASE30_EVENT_SCHEMA_VERSION, 1, setOf(SchemaFamilyUid.RECEIPT), MigrationMateriality.MATERIAL_DATA_MUTATION),
        SchemaFamilyContract(SchemaFamilyUid.CAUSAL, PHASE31_CAUSAL_SCHEMA_VERSION, 1, setOf(SchemaFamilyUid.EVENT)),
        SchemaFamilyContract(SchemaFamilyUid.SNAPSHOT, CampaignSnapshotSchema.VERSION, 1, setOf(SchemaFamilyUid.CAMPAIGN)),
        SchemaFamilyContract(SchemaFamilyUid.REPLAY, 1, 1, setOf(SchemaFamilyUid.SNAPSHOT, SchemaFamilyUid.EVENT)),
        SchemaFamilyContract(SchemaFamilyUid.CANON_DIVERGENCE, CANON_DIVERGENCE_SCHEMA_VERSION, 1, setOf(SchemaFamilyUid.EVENT)),
        SchemaFamilyContract(SchemaFamilyUid.FINANCE, 1, 1, setOf(SchemaFamilyUid.PLAYER)),
        SchemaFamilyContract(SchemaFamilyUid.INVENTORY, 1, 1, setOf(SchemaFamilyUid.PLAYER)),
        SchemaFamilyContract(SchemaFamilyUid.OWNERSHIP, 1, 1, setOf(SchemaFamilyUid.INVENTORY)),
        SchemaFamilyContract(SchemaFamilyUid.DEVELOPMENT_PROJECT, 1, 1, setOf(SchemaFamilyUid.PLAYER))
    )

    private fun productionGraph(eventFaultInjector: EventV1ToV2FaultInjector = EventV1ToV2FaultInjector.NONE) = VersionMigrationGraph(
        listOf(
            VersionMigrationEdge(
                family = SchemaFamilyUid.EVENT,
                fromVersion = 1,
                toVersion = PHASE30_EVENT_SCHEMA_VERSION,
                implementationId = "RPGOS-P36-EVENT-1-2-ORDINAL-REWRITE-1",
                materiality = MigrationMateriality.MATERIAL_DATA_MUTATION,
                migrate = { db, campaignUid ->
                    Phase36EventV1ToV2Migration.migrate(db, eventFaultInjector)
                    CampaignIntelligencePhase30Schema.ensureActivated(db, campaignUid)
                }
            )
        )
    )

    fun migrationManifestFingerprint(): String = productionGraph().manifestFingerprint(contracts, PLAN_VERSION)

    fun ensureReady(
        db: SQLiteDatabase,
        campaignUid: String,
        safetySnapshotUid: String? = null,
        eventFaultInjector: EventV1ToV2FaultInjector = EventV1ToV2FaultInjector.NONE,
        beforeApplied: (() -> Unit)? = null
    ) {
        require(campaignUid.isNotBlank())
        CampaignRuntimeLifecycleLock.withRecovery(campaignUid) {
            ensureReadyLocked(db, campaignUid, safetySnapshotUid, productionGraph(eventFaultInjector), beforeApplied)
        }
    }

    internal fun ensureReadyWithGraph(
        db: SQLiteDatabase,
        campaignUid: String,
        graph: VersionMigrationGraph,
        safetySnapshotUid: String? = null,
        beforeApplied: (() -> Unit)? = null
    ) {
        CampaignRuntimeLifecycleLock.withRecovery(campaignUid) {
            ensureReadyLocked(db, campaignUid, safetySnapshotUid, graph, beforeApplied)
        }
    }

    private fun ensureReadyLocked(
        db: SQLiteDatabase,
        campaignUid: String,
        safetySnapshotUid: String?,
        graph: VersionMigrationGraph,
        beforeApplied: (() -> Unit)?
    ) {
        require(!db.inTransaction()) { "RPGOS-SCHEMA:TOP_LEVEL_MIGRATION_REQUIRED" }
        inspectCompatibilityBeforeMutation(db)
        ensureMetadataTables(db)
        validateAllAttemptStates(db)

        // Phase35 repair semantics are preserved. Its schema creation is structural/additive and
        // remains under administrative bootstrap authority; no historical provenance is invented.
        administrativeWrite(db, campaignUid) { Phase35CanonDivergenceSchema.ensureReady(db) }

        val plan = buildPlan(db, graph)
        val planFingerprint = fingerprint(plan)
        val source = vectorFingerprint(db)
        val target = targetFingerprint()
        recoverInterrupted(db, campaignUid, plan, planFingerprint, source, target)

        if (plan.isEmpty()) {
            administrativeWrite(db, campaignUid) { registerMissingCurrentVersions(db, System.currentTimeMillis()) }
            return
        }

        val material = plan.any { it.isMaterial }
        val effectiveSafetyUid = if (material) {
            MigrationSafetyPolicy.requireProtectedSnapshot(db, campaignUid, plan, safetySnapshotUid)
        } else null

        val attempt = "MIG-$campaignUid-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        administrativeWrite(db, campaignUid) {
            db.execSQL("""INSERT INTO $ATTEMPTS(
                migration_attempt_uid,campaign_uid,source_vector_fingerprint,target_vector_fingerprint,
                plan_fingerprint,plan_version,safety_snapshot_uid,state,started_at_epoch_ms)
                VALUES(?,?,?,?,?,?,?,?,?)""",
                arrayOf(attempt, campaignUid, source, target, planFingerprint, PLAN_VERSION, effectiveSafetyUid,
                    MigrationAttemptState.PREPARED.name, now))
        }

        // Durable protection now exists. Revalidate the exact payload under the same campaign WRITE
        // lifecycle lock immediately before material mutation, closing validation->PREPARED TOCTOU.
        if (material) {
            MigrationSafetyPolicy.requireProtectedSnapshot(db, campaignUid, plan, effectiveSafetyUid)
        }

        try {
            administrativeWrite(db, campaignUid) {
                db.execSQL("UPDATE $ATTEMPTS SET state=? WHERE migration_attempt_uid=?",
                    arrayOf(MigrationAttemptState.RUNNING.name, attempt))
                plan.forEach { familyPlan ->
                    familyPlan.edges.forEach { edge ->
                        edge.migrate(db, campaignUid)
                        db.execSQL("INSERT OR REPLACE INTO $VERSIONS(schema_family_uid,schema_version,migration_owner,updated_at_epoch_ms) VALUES(?,?,?,?)",
                            arrayOf(edge.family.name, edge.toVersion, edge.implementationId, System.currentTimeMillis()))
                    }
                }
                registerMissingCurrentVersions(db, System.currentTimeMillis())
                beforeApplied?.invoke()
                db.execSQL("UPDATE $ATTEMPTS SET state=?,completed_at_epoch_ms=? WHERE migration_attempt_uid=?",
                    arrayOf(MigrationAttemptState.APPLIED.name, System.currentTimeMillis(), attempt))
            }
        } catch (t: Throwable) {
            if (t is SimulatedMigrationProcessDeath) throw t
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
        validateAllAttemptStates(db)
        contracts.forEach { c ->
            val found = current(db, c.family) ?: error("RPGOS-SCHEMA:MISSING_FAMILY:${c.family}")
            if (found > c.currentVersion) throw UnsupportedFutureSchemaException(c.family, found, c.currentVersion)
            check(found == c.currentVersion) { "RPGOS-SCHEMA:FAMILY_NOT_CURRENT:${c.family}:$found:${c.currentVersion}" }
        }
        check(db.rawQuery("SELECT 1 FROM $ATTEMPTS WHERE state IN (?,?) LIMIT 1",
            arrayOf(MigrationAttemptState.PREPARED.name, MigrationAttemptState.RUNNING.name)).use { !it.moveToFirst() }) {
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

    internal fun fingerprint(plan: List<PlannedMigration>): String = plan.joinToString("|") { familyPlan ->
        familyPlan.edges.joinToString(">") { edge ->
            edge.semanticIdentity(familyPlan.contract.dependencies, PLAN_VERSION)
        }
    }.sha256()

    internal fun buildPlan(db: SQLiteDatabase, graph: VersionMigrationGraph): List<PlannedMigration> {
        val plans = contracts.mapNotNull { contract ->
            val source = effectiveVersion(db, contract)
            if (source > contract.currentVersion) throw UnsupportedFutureSchemaException(contract.family, source, contract.currentVersion)
            require(source >= contract.minimumSupportedVersion) {
                "RPGOS-SCHEMA:UNSUPPORTED_OLD:${contract.family}:$source:${contract.minimumSupportedVersion}"
            }
            if (source == contract.currentVersion) null
            else PlannedMigration(contract, source, graph.route(contract.family, source, contract.currentVersion))
        }
        if (plans.isEmpty()) return emptyList()

        val remaining = plans.associateBy { it.contract.family }.toMutableMap()
        val ordered = mutableListOf<PlannedMigration>()
        while (remaining.isNotEmpty()) {
            val ready = remaining.values.filter { p -> p.contract.dependencies.none { it in remaining } }
                .sortedBy { it.contract.family.name }
            require(ready.isNotEmpty()) { "RPGOS-SCHEMA:MIGRATION_DEPENDENCY_CYCLE" }
            ready.forEach { ordered += it; remaining.remove(it.contract.family) }
        }
        return ordered
    }

    private fun ensureMetadataTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS $VERSIONS(
            schema_family_uid TEXT PRIMARY KEY,schema_version INTEGER NOT NULL,
            migration_owner TEXT NOT NULL,updated_at_epoch_ms INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS $ATTEMPTS(
            migration_attempt_uid TEXT PRIMARY KEY,campaign_uid TEXT NOT NULL,
            source_vector_fingerprint TEXT NOT NULL,target_vector_fingerprint TEXT NOT NULL,
            plan_fingerprint TEXT NOT NULL,plan_version INTEGER NOT NULL,safety_snapshot_uid TEXT,
            state TEXT NOT NULL CHECK(state IN ('PREPARED','RUNNING','APPLIED','FAILED')),
            started_at_epoch_ms INTEGER NOT NULL,completed_at_epoch_ms INTEGER,
            failure_code TEXT)""")
        // Existing accepted databases predate the CHECK constraint. Triggers provide the same
        // fail-closed enforcement without a destructive metadata-table rebuild.
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS rpgos_migration_attempt_state_insert
            BEFORE INSERT ON $ATTEMPTS WHEN NEW.state NOT IN ('PREPARED','RUNNING','APPLIED','FAILED')
            BEGIN SELECT RAISE(ABORT,'RPGOS-SCHEMA:INVALID_MIGRATION_ATTEMPT_STATE'); END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS rpgos_migration_attempt_state_update
            BEFORE UPDATE OF state ON $ATTEMPTS WHEN NEW.state NOT IN ('PREPARED','RUNNING','APPLIED','FAILED')
            BEGIN SELECT RAISE(ABORT,'RPGOS-SCHEMA:INVALID_MIGRATION_ATTEMPT_STATE'); END""")
    }

    private fun validateAllAttemptStates(db: SQLiteDatabase) {
        if (!table(db, ATTEMPTS)) return
        val legal = MigrationAttemptState.entries.map { it.name }.toSet()
        db.rawQuery("SELECT DISTINCT state FROM $ATTEMPTS", null).use { c ->
            while (c.moveToNext()) if (c.getString(0) !in legal) {
                throw CorruptMigrationAttemptException("ILLEGAL_STATE:${c.getString(0)}")
            }
        }
    }

    private data class ActiveAttempt(
        val uid: String,
        val source: String,
        val target: String,
        val plan: String,
        val planVersion: Int,
        val state: MigrationAttemptState
    )

    private fun recoverInterrupted(
        db: SQLiteDatabase,
        campaignUid: String,
        currentPlan: List<PlannedMigration>,
        currentPlanFingerprint: String,
        currentSourceVector: String,
        currentTargetVector: String
    ) {
        val active = db.rawQuery("""SELECT migration_attempt_uid,source_vector_fingerprint,target_vector_fingerprint,
            plan_fingerprint,plan_version,state FROM $ATTEMPTS
            WHERE campaign_uid=? AND state IN (?,?) ORDER BY started_at_epoch_ms,migration_attempt_uid""",
            arrayOf(campaignUid, MigrationAttemptState.PREPARED.name, MigrationAttemptState.RUNNING.name)).use { c ->
            buildList {
                while (c.moveToNext()) add(ActiveAttempt(
                    c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4),
                    MigrationAttemptState.valueOf(c.getString(5))
                ))
            }
        }
        if (active.isEmpty()) return
        if (active.size != 1) throw CorruptMigrationAttemptException("MULTIPLE_ACTIVE_ATTEMPTS:$campaignUid")
        val attempt = active.single()

        if (currentPlan.isEmpty()) throw CorruptMigrationAttemptException("ACTIVE_ATTEMPT_WITH_CURRENT_SCHEMA:${attempt.uid}")
        if (attempt.planVersion != PLAN_VERSION) throw CorruptMigrationAttemptException("PLAN_VERSION:${attempt.uid}")
        if (attempt.source != currentSourceVector) throw CorruptMigrationAttemptException("SOURCE_VECTOR:${attempt.uid}")
        if (attempt.target != currentTargetVector) throw CorruptMigrationAttemptException("TARGET_VECTOR:${attempt.uid}")
        if (attempt.plan != currentPlanFingerprint) throw MigrationPlanMismatchException(attempt.plan, currentPlanFingerprint)

        administrativeWrite(db, campaignUid) {
            db.execSQL("UPDATE $ATTEMPTS SET state=?,completed_at_epoch_ms=?,failure_code=? WHERE migration_attempt_uid=?",
                arrayOf(MigrationAttemptState.FAILED.name, System.currentTimeMillis(), "INTERRUPTED_RESTART_SAFE", attempt.uid))
        }
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

    private fun registerMissingCurrentVersions(db: SQLiteDatabase, now: Long) {
        contracts.forEach { contract ->
            if (current(db, contract.family) == null) {
                val effective = effectiveVersion(db, contract)
                check(effective == contract.currentVersion) {
                    "RPGOS-SCHEMA:CANNOT_REGISTER_NONCURRENT:${contract.family}:$effective:${contract.currentVersion}"
                }
                db.execSQL("INSERT INTO $VERSIONS(schema_family_uid,schema_version,migration_owner,updated_at_epoch_ms) VALUES(?,?,?,?)",
                    arrayOf(contract.family.name, contract.currentVersion, "GameplayRuntimeBootstrap", now))
            }
        }
    }

    private fun effectiveVersion(db: SQLiteDatabase, contract: SchemaFamilyContract): Int {
        current(db, contract.family)?.let { return it }
        return when (contract.family) {
            SchemaFamilyUid.EVENT -> Phase36EventSchemaScaffold.detectPhysicalVersion(db) ?: contract.currentVersion
            else -> contract.currentVersion
        }
    }

    private fun current(db: SQLiteDatabase, family: SchemaFamilyUid): Int? = if (!table(db, VERSIONS)) null else db.rawQuery(
        "SELECT schema_version FROM $VERSIONS WHERE schema_family_uid=?", arrayOf(family.name)
    ).use { if (it.moveToFirst()) it.getInt(0) else null }

    private fun vectorFingerprint(db: SQLiteDatabase): String = contracts.joinToString("|") { contract ->
        "${contract.family}:${effectiveVersion(db, contract)}"
    }.sha256()

    private fun targetFingerprint(): String = contracts.joinToString("|") { "${it.family}:${it.currentVersion}" }.sha256()
    private fun table(db: SQLiteDatabase, name: String) = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)).use { it.moveToFirst() }
}

internal object MigrationSafetyPolicy {
    fun requireProtectedSnapshot(
        db: SQLiteDatabase,
        campaignUid: String,
        plan: List<PlannedMigration>,
        safetySnapshotUid: String?
    ): String? {
        if (plan.none { it.isMaterial }) return null
        val uid = requireNotNull(safetySnapshotUid) { "RPGOS-SCHEMA:MATERIAL_MIGRATION_REQUIRES_SAFETY_SNAPSHOT" }
        val snapshot = RecoverableSnapshotPolicy.requireRecoverable(db, campaignUid, uid)
        require(snapshot.pinned || snapshot.kind in setOf(SnapshotKind.MANUAL_BACKUP, SnapshotKind.PRE_RESTORE, SnapshotKind.USER_PINNED)) {
            "RPGOS-SCHEMA:SAFETY_SNAPSHOT_NOT_PROTECTED"
        }
        return uid
    }

    /** Compatibility overload for existing focused tests. */
    fun requireProtectedSnapshot(
        db: SQLiteDatabase,
        campaignUid: String,
        steps: List<SchemaFamilyContract>,
        safetySnapshotUid: String?
    ) {
        if (steps.none { it.materiality == MigrationMateriality.MATERIAL_DATA_MUTATION }) return
        val uid = requireNotNull(safetySnapshotUid) { "RPGOS-SCHEMA:MATERIAL_MIGRATION_REQUIRES_SAFETY_SNAPSHOT" }
        val snapshot = RecoverableSnapshotPolicy.requireRecoverable(db, campaignUid, uid)
        require(snapshot.pinned || snapshot.kind in setOf(SnapshotKind.MANUAL_BACKUP, SnapshotKind.PRE_RESTORE, SnapshotKind.USER_PINNED)) {
            "RPGOS-SCHEMA:SAFETY_SNAPSHOT_NOT_PROTECTED"
        }
    }
}

/** Legacy family-order helpers retained only for old focused tests; production uses VersionMigrationGraph. */
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
        "${it.family}:${it.minimumSupportedVersion}->${it.currentVersion}:${it.materiality.name}:${it.dependencies.map { d -> d.name }.sorted().joinToString(",")}:v${Phase36SchemaVersioning.PLAN_VERSION}"
    }.sha256()
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256").digest(toByteArray()).joinToString("") { "%02x".format(it) }
