from pathlib import Path
import subprocess

BASE = "ff51711ecb98683b43cd49381b4dbe30c6fe1b5c"


def restore(path: str) -> None:
    content = subprocess.check_output(["git", "show", f"{BASE}:{path}"], text=True)
    Path(path).write_text(content)
    print(f"restored baseline {path}")


def replace(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1))
    print(f"patched {path}")


# Preserve accepted Phase 32/35 mutation-gate text and add only Phase-37 deltas.
gate = "app/src/main/java/com/rpgos/app/GameplayMutationGate.kt"
restore(gate)
replace(
    gate,
    '    internal const val CANON_DIVERGENCE_RUNTIME_TURN_GUARD = "rpgos_canon_divergence_runtime_turn_insert"\n',
    '    internal const val CANON_DIVERGENCE_RUNTIME_TURN_GUARD = "rpgos_canon_divergence_runtime_turn_insert"\n'
    '    private const val P37_GUARD_PREFIX = "rpgos_p37_runtime_turn_"\n',
)
replace(
    gate,
    '    internal fun campaignColumnForCompatibility(db: SQLiteDatabase, table: String): String? = campaignColumn(db, table)\n',
    '    internal fun campaignColumnForCompatibility(db: SQLiteDatabase, table: String): String? = campaignColumn(db, table)\n'
    '    internal fun phase37RuntimeGuardNames(): Set<String> = Phase37KnowledgeSchema.canonicalTables.map { p37GuardName(it) }.toSet()\n',
)
replace(
    gate,
    '        Phase35CanonDivergenceSchema.ensureReady(db)\n        authoritativeTables.filter { tableExists(db, it) }.forEach { table ->',
    '        Phase35CanonDivergenceSchema.ensureReady(db)\n        Phase37KnowledgeSchema.ensureReady(db)\n        authoritativeTables.filter { tableExists(db, it) }.forEach { table ->',
)
replace(
    gate,
    '''        installRuntimeTurnAuthorityTrigger(
            db,
            CANON_DIVERGENCE_RUNTIME_TURN_GUARD,
            Phase35CanonDivergenceSchema.TABLE,
            "NEW.provenance_status='RECORDED'"
        )
    }
''',
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
''',
)
replace(
    gate,
    '    private fun suspendLegacyRuntimeTurnAuthorityGuards(db: SQLiteDatabase) {\n        if (Build.VERSION.SDK_INT >= 30) return\n        requireCanonicalGameplayMutation(db, activeGameplayMutation.get()?.campaignUid ?: error("RPGOS-MUTATION-GATE:NO_ACTIVE_TURN"))\n        db.execSQL("DROP TRIGGER IF EXISTS $CANON_DIVERGENCE_RUNTIME_TURN_GUARD")\n    }',
    '    private fun suspendLegacyRuntimeTurnAuthorityGuards(db: SQLiteDatabase) {\n        if (Build.VERSION.SDK_INT >= 30) return\n        requireCanonicalGameplayMutation(db, activeGameplayMutation.get()?.campaignUid ?: error("RPGOS-MUTATION-GATE:NO_ACTIVE_TURN"))\n        db.execSQL("DROP TRIGGER IF EXISTS $CANON_DIVERGENCE_RUNTIME_TURN_GUARD")\n        phase37RuntimeGuardNames().forEach { db.execSQL("DROP TRIGGER IF EXISTS $it") }\n    }',
)
replace(
    gate,
    '''        installRuntimeTurnAuthorityTrigger(
            db,
            CANON_DIVERGENCE_RUNTIME_TURN_GUARD,
            Phase35CanonDivergenceSchema.TABLE,
            "NEW.provenance_status='RECORDED'"
        )
    }

    internal fun enterRuntimeTurnAuthority''',
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

    internal fun enterRuntimeTurnAuthority''',
)
replace(
    gate,
    '''    var bufferStarted = false
    var runtimeAuthorityEntered = false
    return try {
        CanonDivergenceTurnBuffer.begin(db, campaignUid)
        bufferStarted = true
        GameplayMutationDatabaseGuards.enterRuntimeTurnAuthority(db, campaignUid)
        runtimeAuthorityEntered = true
        val result = block()
        CanonDivergenceTurnBuffer.flush(db, campaignUid)
        result
    } finally {
        if (runtimeAuthorityEntered) GameplayMutationDatabaseGuards.leaveRuntimeTurnAuthority(db)
        if (bufferStarted) CanonDivergenceTurnBuffer.clear()
        activeGameplayMutation.set(previous)
        GameplayMutationDatabaseGuards.leaveTurn(db, campaignUid)
    }''',
    '''    var bufferStarted = false
    var knowledgeBufferStarted = false
    var runtimeAuthorityEntered = false
    return try {
        CanonDivergenceTurnBuffer.begin(db, campaignUid)
        bufferStarted = true
        KnowledgeTurnBuffer.begin(db, campaignUid)
        knowledgeBufferStarted = true
        GameplayMutationDatabaseGuards.enterRuntimeTurnAuthority(db, campaignUid)
        runtimeAuthorityEntered = true
        val result = block()
        CanonDivergenceTurnBuffer.flush(db, campaignUid)
        KnowledgeTurnBuffer.flush(db, campaignUid)
        result
    } finally {
        if (runtimeAuthorityEntered) GameplayMutationDatabaseGuards.leaveRuntimeTurnAuthority(db)
        if (knowledgeBufferStarted) KnowledgeTurnBuffer.clear()
        if (bufferStarted) CanonDivergenceTurnBuffer.clear()
        activeGameplayMutation.set(previous)
        GameplayMutationDatabaseGuards.leaveTurn(db, campaignUid)
    }''',
)

# Preserve accepted Phase 36 migration graph; Phase 37 is a structural-additive family only.
p36 = "app/src/main/java/com/rpgos/app/Phase36SchemaVersioning.kt"
restore(p36)
replace(
    p36,
    '    CANON_DIVERGENCE, FINANCE, INVENTORY, OWNERSHIP, DEVELOPMENT_PROJECT\n',
    '    CANON_DIVERGENCE, KNOWLEDGE, FINANCE, INVENTORY, OWNERSHIP, DEVELOPMENT_PROJECT\n',
)
replace(
    p36,
    '        SchemaFamilyContract(SchemaFamilyUid.CANON_DIVERGENCE, CANON_DIVERGENCE_SCHEMA_VERSION, 1, setOf(SchemaFamilyUid.EVENT)),\n',
    '        SchemaFamilyContract(SchemaFamilyUid.CANON_DIVERGENCE, CANON_DIVERGENCE_SCHEMA_VERSION, 1, setOf(SchemaFamilyUid.EVENT)),\n'
    '        SchemaFamilyContract(SchemaFamilyUid.KNOWLEDGE, PHASE37_KNOWLEDGE_SCHEMA_VERSION, 1, setOf(SchemaFamilyUid.CAMPAIGN, SchemaFamilyUid.EVENT)),\n',
)
replace(
    p36,
    '        // Phase35 repair semantics are preserved. Schema installation is structural/additive only.\n        administrativeWrite(db, campaignUid) { Phase35CanonDivergenceSchema.ensureReady(db) }\n',
    '        // Phase35 repair semantics are preserved. Phase37 adds only structural epistemic tables; legacy rows are not rewritten.\n        administrativeWrite(db, campaignUid) {\n            Phase35CanonDivergenceSchema.ensureReady(db)\n            Phase37KnowledgeSchema.ensureReady(db)\n        }\n',
)
replace(
    p36,
    '        check(table(db, VERSIONS) && table(db, ATTEMPTS) && Phase35CanonDivergenceSchema.isReady(db)) { "RPGOS-SCHEMA:NOT_READY" }\n',
    '        check(table(db, VERSIONS) && table(db, ATTEMPTS) && Phase35CanonDivergenceSchema.isReady(db) && Phase37KnowledgeSchema.isReady(db)) { "RPGOS-SCHEMA:NOT_READY" }\n',
)
replace(
    p36,
    '''        return when (contract.family) {
            SchemaFamilyUid.EVENT -> Phase36EventSchemaScaffold.detectPhysicalVersion(db) ?: contract.currentVersion
            else -> contract.currentVersion
        }
''',
    '''        return when (contract.family) {
            SchemaFamilyUid.EVENT -> Phase36EventSchemaScaffold.detectPhysicalVersion(db) ?: contract.currentVersion
            SchemaFamilyUid.KNOWLEDGE -> if (Phase37KnowledgeSchema.isReady(db)) PHASE37_KNOWLEDGE_SCHEMA_VERSION else contract.currentVersion
            else -> contract.currentVersion
        }
''',
)

# Preserve accepted bootstrap text and add Phase-37 structural/readiness requirements only.
boot = "app/src/main/java/com/rpgos/app/GameplayRuntimeBootstrap.kt"
restore(boot)
replace(
    boot,
    '        GameplayMutationDatabaseGuards.CANON_DIVERGENCE_RUNTIME_TURN_GUARD\n    )',
    '        GameplayMutationDatabaseGuards.CANON_DIVERGENCE_RUNTIME_TURN_GUARD,\n'
    '        "rpgos_p37_acquisition_no_update", "rpgos_p37_acquisition_no_delete",\n'
    '        "rpgos_p37_evidence_no_update", "rpgos_p37_evidence_no_delete"\n'
    '    ) + GameplayMutationDatabaseGuards.phase37RuntimeGuardNames()',
)
replace(
    boot,
    '                CampaignSnapshotSchema.ensureReady(db)\n',
    '                CampaignSnapshotSchema.ensureReady(db)\n                Phase37KnowledgeSchema.ensureReady(db)\n',
)
replace(
    boot,
    '        check(CampaignSnapshotSchema.isReady(db)) { "RPGOS-G34:SNAPSHOT_SCHEMA_NOT_READY" }\n        Phase36SchemaVersioning.requireReady(db)\n',
    '        check(CampaignSnapshotSchema.isReady(db)) { "RPGOS-G34:SNAPSHOT_SCHEMA_NOT_READY" }\n        check(Phase37KnowledgeSchema.isReady(db)) { "RPGOS-P37:KNOWLEDGE_SCHEMA_NOT_READY" }\n        Phase36SchemaVersioning.requireReady(db)\n',
)

print("baseline-preserving Phase 37 patches complete")
