from pathlib import Path


def replace(path: str, old: str, new: str, count: int = 1) -> None:
    p = Path(path)
    text = p.read_text()
    found = text.count(old)
    if found != count:
        raise SystemExit(f"{path}: expected {count} match(es), found {found}: {old[:160]!r}")
    p.write_text(text.replace(old, new, count))
    print(f"patched {path}")


def append_before(path: str, marker: str, addition: str) -> None:
    replace(path, marker, addition + marker)


# ---------------------------------------------------------------------------
# P37-AUD-003: exact in-memory RECORDED write authority, not generic TURN/ADMIN.
# The baseline restoration script intentionally adds only minimal Phase37 hooks;
# this finalizer tightens those hooks without altering accepted Phase1-36 semantics.
# ---------------------------------------------------------------------------
gate = "app/src/main/java/com/rpgos/app/GameplayMutationGate.kt"
replace(
    gate,
    '    internal const val CANON_DIVERGENCE_RUNTIME_TURN_GUARD = "rpgos_canon_divergence_runtime_turn_insert"\n    private const val P37_GUARD_PREFIX = "rpgos_p37_runtime_turn_"\n',
    '    internal const val CANON_DIVERGENCE_RUNTIME_TURN_GUARD = "rpgos_canon_divergence_runtime_turn_insert"\n'
    '    internal const val P37_RECORDED_WRITE_FUNCTION = "rpgos_p37_recorded_write_authority"\n'
    '    private const val P37_GUARD_PREFIX = "rpgos_p37_recorded_"\n',
)
replace(
    gate,
    '    internal fun phase37RuntimeGuardNames(): Set<String> = Phase37KnowledgeSchema.canonicalTables.map { p37GuardName(it) }.toSet()\n',
    '''    internal fun phase37RuntimeGuardNames(): Set<String> = buildSet {
        add(p37GuardName(Phase37KnowledgeSchema.CLAIMS, "insert"))
        add(p37GuardName(Phase37KnowledgeSchema.ACQUISITIONS, "insert"))
        add(p37GuardName(Phase37KnowledgeSchema.EVIDENCE, "insert"))
        add(p37GuardName(Phase37KnowledgeSchema.STATES, "insert"))
        add(p37GuardName(Phase37KnowledgeSchema.STATES, "update"))
    }
''',
)
replace(
    gate,
    '        Phase35CanonDivergenceSchema.ensureReady(db)\n        Phase37KnowledgeSchema.ensureReady(db)\n        authoritativeTables.filter { tableExists(db, it) }.forEach { table ->',
    '        Phase35CanonDivergenceSchema.ensureReady(db)\n        check(Phase37KnowledgeSchema.isReady(db)) { "RPGOS-P37:KNOWLEDGE_SCHEMA_NOT_READY" }\n        authoritativeTables.filter { tableExists(db, it) }.forEach { table ->',
)
replace(
    gate,
    '''        if (Build.VERSION.SDK_INT >= 30) {
            db.setCustomScalarFunction(RUNTIME_TURN_FUNCTION, UnaryOperator { campaignUid ->
                if (isCanonicalGameplayMutationActive(db, campaignUid)) "1" else "0"
            })
        }
''',
    '''        if (Build.VERSION.SDK_INT >= 30) {
            db.setCustomScalarFunction(RUNTIME_TURN_FUNCTION, UnaryOperator { campaignUid ->
                if (isCanonicalGameplayMutationActive(db, campaignUid)) "1" else "0"
            })
            db.setCustomScalarFunction(P37_RECORDED_WRITE_FUNCTION, UnaryOperator { token ->
                if (KnowledgeRecordedWriteAuthority.isAuthorized(db, token)) "1" else "0"
            })
        }
''',
)
replace(
    gate,
    '''        Phase37KnowledgeSchema.canonicalTables.forEach { table ->
            installRuntimeTurnAuthorityTrigger(db, p37GuardName(table), table, null)
        }
    }

    private fun installRuntimeTurnAuthorityTrigger''',
    '''        installPhase37RecordedWriteAuthorityGuards(db)
    }

    private fun installRuntimeTurnAuthorityTrigger''',
)
replace(
    gate,
    '        db.execSQL("DROP TRIGGER IF EXISTS $CANON_DIVERGENCE_RUNTIME_TURN_GUARD")\n        phase37RuntimeGuardNames().forEach { db.execSQL("DROP TRIGGER IF EXISTS $it") }\n',
    '        db.execSQL("DROP TRIGGER IF EXISTS $CANON_DIVERGENCE_RUNTIME_TURN_GUARD")\n',
)
replace(
    gate,
    '''        installRuntimeTurnAuthorityTrigger(
            db,
            CANON_DIVERGENCE_RUNTIME_TURN_GUARD,
            Phase35CanonDivergenceSchema.TABLE,
            "NEW.provenance_status='RECORDED'"
        )
        Phase37KnowledgeSchema.canonicalTables.forEach { table ->
            installRuntimeTurnAuthorityTrigger(db, p37GuardName(table), table, null)
        }
    }

    private fun p37GuardName(table: String) = P37_GUARD_PREFIX + table.removePrefix("world_actor_") + "_insert"
''',
    '''        installRuntimeTurnAuthorityTrigger(
            db,
            CANON_DIVERGENCE_RUNTIME_TURN_GUARD,
            Phase35CanonDivergenceSchema.TABLE,
            "NEW.provenance_status='RECORDED'"
        )
    }

    private fun installPhase37RecordedWriteAuthorityGuards(db: SQLiteDatabase) {
        installPhase37RecordedWriteGuard(
            db, Phase37KnowledgeSchema.CLAIMS, "INSERT", "NEW",
            "'CLAIM:'||hex(NEW.campaign_uid)||':'||hex(NEW.claim_uid)||':'||hex(NEW.subject_kind_uid)||':'||hex(NEW.subject_uid)||':'||hex(NEW.predicate_uid)||':'||hex(NEW.value_canonical)||':'||hex(NEW.domain_uid)"
        )
        installPhase37RecordedWriteGuard(
            db, Phase37KnowledgeSchema.ACQUISITIONS, "INSERT", "NEW",
            "'ACQ:'||hex(NEW.campaign_uid)||':'||hex(NEW.acquisition_uid)||':'||hex(NEW.claim_uid)||':'||hex(NEW.holder_kind_uid)||':'||hex(NEW.holder_uid)||':'||hex(COALESCE(NEW.created_event_uid,''))||':'||hex(NEW.provenance_status)"
        )
        installPhase37RecordedWriteGuard(
            db, Phase37KnowledgeSchema.EVIDENCE, "INSERT", "NEW",
            "'EVID:'||hex(NEW.campaign_uid)||':'||hex(NEW.evidence_uid)||':'||hex(NEW.acquisition_uid)||':'||hex(NEW.claim_uid)||':'||hex(NEW.evidence_kind_uid)||':'||hex(NEW.polarity_uid)||':'||hex(COALESCE(NEW.source_event_uid,''))||':'||hex(COALESCE(NEW.source_acquisition_uid,''))"
        )
        val stateToken = "'STATE:'||hex(NEW.campaign_uid)||':'||hex(NEW.state_uid)||':'||hex(NEW.holder_kind_uid)||':'||hex(NEW.holder_uid)||':'||hex(NEW.claim_uid)||':'||hex(NEW.scope_uid)||':'||hex(NEW.role_uid)||':'||hex(NEW.epistemic_state_uid)||':'||hex(NEW.latest_acquisition_uid)"
        installPhase37RecordedWriteGuard(db, Phase37KnowledgeSchema.STATES, "INSERT", "NEW", stateToken)
        installPhase37RecordedWriteGuard(db, Phase37KnowledgeSchema.STATES, "UPDATE", "NEW", stateToken)
    }

    private fun installPhase37RecordedWriteGuard(
        db: SQLiteDatabase,
        table: String,
        operation: String,
        row: String,
        tokenExpression: String
    ) {
        if (!tableExists(db, table)) return
        val name = p37GuardName(table, operation.lowercase())
        db.execSQL("DROP TRIGGER IF EXISTS $name")
        val missing = if (Build.VERSION.SDK_INT >= 30) "$P37_RECORDED_WRITE_FUNCTION($tokenExpression)<>'1'" else "1=1"
        db.execSQL(
            """CREATE TRIGGER $name BEFORE $operation ON $table
WHEN $missing
BEGIN SELECT RAISE(ABORT,'RPGOS-KNOWLEDGE:EXACT_RECORDED_AUTHORITY_REQUIRED'); END""".trimIndent()
        )
    }

    internal fun suspendLegacyPhase37RecordedWriteGuards(db: SQLiteDatabase) {
        if (Build.VERSION.SDK_INT >= 30) return
        val campaignUid = activeGameplayMutation.get()?.campaignUid ?: error("RPGOS-MUTATION-GATE:NO_ACTIVE_TURN")
        requireCanonicalGameplayMutation(db, campaignUid)
        phase37RuntimeGuardNames().forEach { db.execSQL("DROP TRIGGER IF EXISTS $it") }
    }

    internal fun restoreLegacyPhase37RecordedWriteGuards(db: SQLiteDatabase) {
        if (Build.VERSION.SDK_INT >= 30) return
        installPhase37RecordedWriteAuthorityGuards(db)
    }

    private fun p37GuardName(table: String, operation: String) =
        P37_GUARD_PREFIX + table.removePrefix("world_actor_") + "_" + operation
''',
)

# Phase37 schema installation belongs to Phase36. Do not pre-create it in bootstrap and thereby
# turn a missing current canonical table into a silently empty replacement.
boot = "app/src/main/java/com/rpgos/app/GameplayRuntimeBootstrap.kt"
replace(
    boot,
    '                CampaignSnapshotSchema.ensureReady(db)\n                Phase37KnowledgeSchema.ensureReady(db)\n',
    '                CampaignSnapshotSchema.ensureReady(db)\n',
)

# Phase36: additive first installation is legal only when the KNOWLEDGE family is not yet registered.
# Once version 1 is durable, missing physical tables are corruption and must fail closed.
p36 = "app/src/main/java/com/rpgos/app/Phase36SchemaVersioning.kt"
replace(
    p36,
    '''        administrativeWrite(db, campaignUid) {
            Phase35CanonDivergenceSchema.ensureReady(db)
            Phase37KnowledgeSchema.ensureReady(db)
        }
''',
    '''        administrativeWrite(db, campaignUid) {
            Phase35CanonDivergenceSchema.ensureReady(db)
            if (current(db, SchemaFamilyUid.KNOWLEDGE) == null) {
                Phase37KnowledgeSchema.ensureReady(db)
            } else {
                check(Phase37KnowledgeSchema.isReady(db)) { "RPGOS-SCHEMA:KNOWLEDGE_PHYSICAL_SCHEMA_NOT_CURRENT" }
            }
        }
''',
)
replace(
    p36,
    '''            SchemaFamilyUid.EVENT -> Phase36EventSchemaScaffold.detectPhysicalVersion(db) ?: contract.currentVersion
            SchemaFamilyUid.KNOWLEDGE -> if (Phase37KnowledgeSchema.isReady(db)) PHASE37_KNOWLEDGE_SCHEMA_VERSION else contract.currentVersion
            else -> contract.currentVersion
''',
    '''            SchemaFamilyUid.EVENT -> Phase36EventSchemaScaffold.detectPhysicalVersion(db) ?: contract.currentVersion
            SchemaFamilyUid.KNOWLEDGE -> if (Phase37KnowledgeSchema.isReady(db)) PHASE37_KNOWLEDGE_SCHEMA_VERSION
                else error("RPGOS-SCHEMA:KNOWLEDGE_PHYSICAL_SCHEMA_NOT_CURRENT")
            else -> contract.currentVersion
''',
)

# ---------------------------------------------------------------------------
# Phase37 implementation hardening: exact pending-row capability, immutable claim/evidence history,
# fail-closed canonical projection, deterministic current-state update.
# ---------------------------------------------------------------------------
p37 = "app/src/main/java/com/rpgos/app/Phase37WorldActorKnowledge.kt"
replace(
    p37,
    '        db.execSQL("CREATE INDEX IF NOT EXISTS idx_p37_state_holder ON $STATES(campaign_uid,holder_kind_uid,holder_uid,epistemic_state_uid,claim_uid)")\n        db.execSQL("CREATE TRIGGER IF NOT EXISTS rpgos_p37_acquisition_no_update BEFORE UPDATE ON $ACQUISITIONS BEGIN SELECT RAISE(ABORT,\'RPGOS-KNOWLEDGE:ACQUISITION_APPEND_ONLY\'); END")\n',
    '        db.execSQL("CREATE INDEX IF NOT EXISTS idx_p37_state_holder ON $STATES(campaign_uid,holder_kind_uid,holder_uid,epistemic_state_uid,claim_uid)")\n'
    '        db.execSQL("CREATE TRIGGER IF NOT EXISTS rpgos_p37_claim_no_update BEFORE UPDATE ON $CLAIMS BEGIN SELECT RAISE(ABORT,\'RPGOS-KNOWLEDGE:CLAIM_APPEND_ONLY\'); END")\n'
    '        db.execSQL("CREATE TRIGGER IF NOT EXISTS rpgos_p37_claim_no_delete BEFORE DELETE ON $CLAIMS BEGIN SELECT RAISE(ABORT,\'RPGOS-KNOWLEDGE:CLAIM_APPEND_ONLY\'); END")\n'
    '        db.execSQL("CREATE TRIGGER IF NOT EXISTS rpgos_p37_acquisition_no_update BEFORE UPDATE ON $ACQUISITIONS BEGIN SELECT RAISE(ABORT,\'RPGOS-KNOWLEDGE:ACQUISITION_APPEND_ONLY\'); END")\n',
)
replace(
    p37,
    '        db.execSQL("CREATE TRIGGER IF NOT EXISTS rpgos_p37_evidence_no_delete BEFORE DELETE ON $EVIDENCE BEGIN SELECT RAISE(ABORT,\'RPGOS-KNOWLEDGE:EVIDENCE_APPEND_ONLY\'); END")\n    }\n\n    fun isReady',
    '        db.execSQL("CREATE TRIGGER IF NOT EXISTS rpgos_p37_evidence_no_delete BEFORE DELETE ON $EVIDENCE BEGIN SELECT RAISE(ABORT,\'RPGOS-KNOWLEDGE:EVIDENCE_APPEND_ONLY\'); END")\n'
    '        db.execSQL("CREATE TRIGGER IF NOT EXISTS rpgos_p37_state_no_delete BEFORE DELETE ON $STATES BEGIN SELECT RAISE(ABORT,\'RPGOS-KNOWLEDGE:STATE_DELETE_FORBIDDEN\'); END")\n'
    '    }\n\n    fun isReady',
)
replace(
    p37,
    '''    fun isReady(db: SQLiteDatabase): Boolean = listOf(CLAIMS, ACQUISITIONS, EVIDENCE, STATES, EXPERTISE).all { table ->
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1", arrayOf(table)).use { it.moveToFirst() }
    }
}

private data class PendingKnowledgeAcquisition''',
    '''    fun isReady(db: SQLiteDatabase): Boolean = listOf(CLAIMS, ACQUISITIONS, EVIDENCE, STATES, EXPERTISE).all { table ->
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
        }
    }
}

internal data class PendingKnowledgeAcquisition''',
)
append_before(
    p37,
    '/** Exact-db/campaign in-memory buffer. Mutable SQLite context rows cannot manufacture this capability. */\ninternal object KnowledgeTurnBuffer',
    '''internal object Phase37KnowledgeWriteTokens {
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

''',
)
replace(
    p37,
    '''    fun flush(db: SQLiteDatabase, campaignUid: String) {
        val active = local.get() ?: return
        require(active.db === db && active.campaignUid == campaignUid) { "RPGOS-KNOWLEDGE:TURN_BUFFER_SCOPE_MISMATCH" }
        active.entries.forEach { KnowledgeStore(db, campaignUid).finalizeRecorded(it) }
        active.entries.clear()
    }
''',
    '''    fun flush(db: SQLiteDatabase, campaignUid: String) {
        val active = local.get() ?: return
        require(active.db === db && active.campaignUid == campaignUid) { "RPGOS-KNOWLEDGE:TURN_BUFFER_SCOPE_MISMATCH" }
        active.entries.forEach { pending ->
            KnowledgeRecordedWriteAuthority.withPending(db, campaignUid, pending) {
                KnowledgeStore(db, campaignUid).finalizeRecorded(pending)
            }
        }
        active.entries.clear()
    }
''',
)
replace(
    p37,
    '''        db.insertWithOnConflict(Phase37KnowledgeSchema.STATES,null,values,SQLiteDatabase.CONFLICT_REPLACE).also { require(it!=-1L) }
    }
''',
    '''        if (current == 0L) {
            db.insertOrThrow(Phase37KnowledgeSchema.STATES, null, values)
        } else {
            val updated = db.update(
                Phase37KnowledgeSchema.STATES, values, "campaign_uid=? AND state_uid=?", arrayOf(campaignUid, stateUid)
            )
            require(updated == 1) { "RPGOS-KNOWLEDGE:STATE_UPDATE_IDENTITY_CONFLICT" }
        }
    }
''',
)
replace(
    p37,
    '''class KnowledgeContextProjection(private val db: SQLiteDatabase, private val campaignUid: String) {
    fun forHolders(holders: Collection<KnowledgeHolderRef>, includeLegacy: Boolean = true): List<Map<String,Any?>> {
        val exact=holders.distinct()
''',
    '''class KnowledgeContextProjection(private val db: SQLiteDatabase, private val campaignUid: String) {
    fun forHolders(holders: Collection<KnowledgeHolderRef>, includeLegacy: Boolean = true): List<Map<String,Any?>> {
        Phase37KnowledgeSchema.requireProjectionReadable(db)
        val exact=holders.distinct()
''',
)

# Integration helper stageRecorded signature remains unchanged; exact write authority binds the
# deterministic event UID generated from the exact TurnTransaction change/event intent.

print("Phase 37 known-finding finalization patch complete")
