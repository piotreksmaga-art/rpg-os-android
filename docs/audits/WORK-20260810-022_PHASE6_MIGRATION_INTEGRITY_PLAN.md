# WORK-20260810-022 — Phase 6 Migration / Integrity Plan + Final Runtime Validation

Work ID: `WORK-20260810-022`  
Owner: `CHAT-3`  
Role: PHASE 6 MIGRATION / INTEGRITY AUDITOR  
Mode: READ-ONLY RUNTIME  
Repository: `piotreksmaga-art/rpg-os-android`

Coordinator-issued baseline: `387a0c331eaa11863529a4eababa8dd580c30ff2`  
Pre-WORK-020 master used for implementation delta: `e07891d0ae4188a166fc46781ad698e8d4458175`  
Audited WORK-020 resultCommit: `edce3524998abf2ffb5a6293b63b06b73f11b7cd`  
Accepted Phase 5 runtime: `44011bc0177df846a34fa12d0009d33e887f6c23`

Allowed write scope: this report only.

---

## 1. Final verdict

# PHASE 6 INTEGRITY VALIDATION: FAIL

WORK-020 contains one reproducible release-blocker in real application migration integration:

> Phase 6 schema exists as `MigrationManager.ensureV6(...)`, but the real central application schema entrypoint `LocalGameStore.ensureCurrentSchema(...)` still invokes only `MigrationManager().ensureV4(...)`.

Therefore normal application startup/open, context reads, active-player reads, restore and campaign switching do **not** execute the Phase 6 migration.

The Phase 6 persistence tests call `ensureV6()` directly, so they do not detect this production-entrypoint omission.

This is a migration/integration failure, not naming-only debt.

No global Phase 6 COMPLETE status is justified by this report.

---

## 2. Audited implementation scope

Delta:

`e07891d0ae4188a166fc46781ad698e8d4458175`

->

`edce3524998abf2ffb5a6293b63b06b73f11b7cd`

contains one implementation commit and four files:

1. `app/src/main/java/com/rpgos/app/Phase6Migration.kt`
2. `app/src/main/java/com/rpgos/app/ProgressionProfileModel.kt`
3. `app/src/main/java/com/rpgos/app/ProgressionProfileStore.kt`
4. `app/src/test/java/com/rpgos/app/ProgressionProfilePersistenceTest.kt`

No Phase 7 implementation, ProgressionEngine, Skill redesign, Technique redesign, PlayerDomainEngine, PlayerCommand, PlayerChangeSet or CharacterPanelSnapshot v2 changes are present in WORK-020.

Scope verdict: **PASS**.

---

## 3. Implemented Phase 6 model

The implementation correctly separates authoritative profile concepts:

### `ProgressionDomainDefinition`

Contains stable:

- `domainUid`,
- `worldPackUid`,
- `key`,
- display/category metadata,
- optional parent,
- Talent/Potential capability flags,
- `definitionVersion`,
- provenance.

### Talent

`TalentEntry` is scoped by:

- campaign,
- character,
- domain UID,

and stores independent `baseValue`, entry version and provenance.

### Potential

`PotentialEntry` is scoped by:

- campaign,
- character,
- domain UID,
- dimension UID,

and independently stores `baseValue`, entry version and provenance.

The implementation does not collapse Talent + Potential into one `growthRating`.

Model semantics verdict: **PASS**.

---

## 4. Numeric / provenance policy

`ProgressionProfilePolicy` rejects:

- blank campaign/character/domain identity,
- non-finite profile values,
- negative profile values,
- entry version < 1,
- blank profile provenance,
- invalid definition version/provenance,
- blank Potential dimension,
- invalid legacy mapping version/provenance.

Production numeric scale is therefore currently:

```text
finite Double >= 0.0
```

with no explicit upper bound.

`-0.0` is normalized to `0.0` when storing/loading through `ProgressionProfileStore`.

Store-boundary NaN / +/-Infinity handling: **PASS**.

Direct malformed SQL rows remain governed by SQLite constraints plus store read semantics; no separate corruption hardening is claimed here.

---

## 5. Schema / migration design

`ensureV6()` is additive and first delegates to accepted `ensureV4()`, which already includes Phase 5 objects.

It then creates:

- `progression_domain_definitions`,
- `talent_profile_entries`,
- `potential_profile_entries`,
- `legacy_progression_evidence`,
- `legacy_progression_mappings`,
- indexes,
- migration marker `RPGOS-6.0-TALENT-POTENTIAL`.

The Phase 6 DDL and migration marker execute inside one transaction.

No Phase 6 migration SQL rewrites:

- `active_player_ref`,
- `player_stats`,
- `player_resources`,
- `legacy_stat_aliases`,
- `legacy_resource_aliases`,
- `modifiers`,
- legacy `character_stats`.

Schema design itself: **PASS**.

Migration transaction/idempotent DDL design itself: **PASS**.

Production migration integration: **BLOCKER / FAIL**; see section 11.

---

## 6. Legacy policy actually implemented

The implementation creates an explicit two-layer legacy model:

### Opaque evidence

`LegacyProgressionEvidence`

is preserved separately and does not become Talent/Potential automatically.

### Explicit mapping

`LegacyProgressionMapping`

requires:

- explicit axis `TALENT` or `POTENTIAL`,
- target domain UID,
- World Pack UID,
- mapping version,
- provenance,
- Potential dimension where required.

Materialization requires an existing evidence row plus an existing explicit mapping.

There is no name-based automatic migration in WORK-020.

Legacy policy verdict: **PASS**.

---

## 7. Required legacy classification oracle

The implementation does not hardcode classifications by label, which is correct. The audit classification remains:

| Bare legacy evidence | Required default |
|---|---|
| `talent` | EXPLICIT MAPPING REQUIRED |
| `gifted` | AMBIGUOUS / unresolved |
| `aptitude` | EXPLICIT MAPPING REQUIRED |
| `growth_rate` | AMBIGUOUS / unresolved |
| `learning_rate` | EXPLICIT MAPPING REQUIRED |
| `maximum_potential` | EXPLICIT MAPPING REQUIRED |
| `affinity` | AMBIGUOUS / unresolved |
| `adaptation` | AMBIGUOUS / unresolved |
| `evolution_potential` | EXPLICIT MAPPING REQUIRED unless complete typed source semantics already prove axis/domain/dimension/scale/version |

No bare label is SAFE AUTO-MAP by name alone.

Because WORK-020 performs no automatic scanning/backfill of those fields, it does not violate this rule.

Legacy guessing verdict: **PASS**.

---

## 8. Persistence / isolation evidence present in WORK-020 tests

`ProgressionProfilePersistenceTest` covers:

- all four Talent/Potential high/low quadrants,
- reopen persistence,
- Talent update not changing Potential,
- Potential update not changing Talent,
- profile writes not changing PlayerStat or Skill mastery,
- temporary Phase 5 modifier resolution not changing stored Talent/Potential,
- campaign isolation,
- player isolation,
- domain separation,
- cross-World-Pack same-label domains,
- World Pack UID hijack rejection,
- duplicate same-pack domain-key rejection,
- unknown-domain rejection,
- capability mismatch rejection,
- NaN rejection,
- +Infinity rejection,
- negative-value rejection,
- additive/idempotent direct `ensureV6()` migration,
- existing PlayerStat and modifier preservation in direct migration test,
- ambiguous legacy evidence remaining unresolved until explicit mapping,
- 1005 Talent entries,
- 1005 Potential entries,
- `PRAGMA integrity_check`,
- `PRAGMA foreign_key_check`.

Coverage quality inside the direct test harness is strong.

However these tests instantiate `MigrationManager().ensureV6(...)` directly and therefore bypass the real production schema entrypoint that is defective.

---

## 9. Phase 5 separation

WORK-020 does not introduce `TalentModifierEngine`, `PotentialModifierEngine` or another resolver.

The temporary-effect test uses accepted `ModifierStore` + `DerivedValueResolver` and verifies profile values remain unchanged.

`ProgressionProfileStore` contains no reference from Phase 5 resolver back into profile persistence.

Phase 5 authority separation verdict: **PASS**.

---

## 10. Upstream-state preservation

Static migration audit shows Phase 6 DDL does not contain UPDATE/DELETE/backfill of Phase 3/4/5 authority.

The provided direct migration test explicitly confirms preservation of:

- a legacy `character_stats.current_value`,
- `PlayerStat.baseValue`,
- an existing Phase 5 modifier row,
- zero synthetic Talent rows,
- zero synthetic Potential rows,
- one idempotent Phase 6 migration marker after repeated direct `ensureV6()`.

The test does not exhaustively byte-compare every Phase 3/4/5 table, but code inspection finds no Phase 6 write path to those tables.

Upstream mutation audit for direct `ensureV6()`: **PASS**.

---

## 11. RELEASE BLOCKER — Phase 6 migration is not wired into current schema

### Production code

`LocalGameStore.ensureCurrentSchema(saveDb)` is:

```kotlin
private fun ensureCurrentSchema(saveDb: SQLiteDatabase) {
    MigrationManager().ensureV4(saveDb, selection.activeCampaignRef().campaignId)
}
```

It does not call `ensureV6()`.

### Real application paths using `ensureCurrentSchema()`

The same method is called from important runtime paths including:

- `bootstrap()`,
- `buildContext()`,
- `activePlayerRef()`,
- `setActivePlayer()`,
- `playerState()`,
- stat/resource reads and registration,
- `restoreBackup()`,
- `setActiveCampaign()`,
- status/sync paths.

Therefore a pre-Phase-6 campaign opened normally after installing WORK-020 receives Phase 3/4/5 schema but **not**:

- `progression_domain_definitions`,
- `talent_profile_entries`,
- `potential_profile_entries`,
- `legacy_progression_evidence`,
- `legacy_progression_mappings`,
- `RPGOS-6.0-TALENT-POTENTIAL` marker.

### Why current tests miss it

Every Phase 6 persistence test that requires schema uses direct calls such as:

```kotlin
MigrationManager().ensureV6(db, "C")
```

or a helper that does the same.

No test opens the old campaign through the application's real `LocalGameStore.ensureCurrentSchema()` path and then asserts the Phase 6 schema/marker exists.

### Reproduction

1. Start with an existing Phase 5 campaign DB with no Phase 6 tables.
2. Open the campaign via normal `LocalGameStore.bootstrap()`, `restoreBackup()`, or `setActiveCampaign()` path.
3. `ensureCurrentSchema()` invokes `ensureV4()` only.
4. Query:

```sql
SELECT name FROM sqlite_master WHERE name='talent_profile_entries';
```

Expected for accepted Phase 6 runtime: one table row.

Actual from production call graph: zero rows because `ensureV6()` was never called.

Likewise:

```sql
SELECT COUNT(*)
FROM rpgos_schema_migrations
WHERE migration_id='RPGOS-6.0-TALENT-POTENTIAL';
```

returns `0` on that real path.

### Severity

**RELEASE BLOCKER**.

This violates the required gates:

- old campaign -> Phase 6 migration,
- real persistence integration,
- existing campaigns receive current schema,
- restore/current-schema path,
- Phase 6 migration idempotency through actual app entrypoint.

The earlier Phase 5 `ensureV4()` naming debt was harmless because Phase 5 objects were physically created inside `ensureV4()`. WORK-020 changes that situation: Phase 6 is implemented in a separate `ensureV6()` function and the central entrypoint was not advanced to it.

Thus this is **not merely naming debt**.

---

## 12. Validation matrix

| Gate | Result |
|---|---|
| WORK-020 scope only | PASS |
| Separate Talent/Potential persistence | PASS |
| Domain stable UID model | PASS |
| World Pack ownership validation | PASS |
| Duplicate domain UID fail-loud | PASS |
| Same text label / different UID separate | PASS |
| Campaign isolation | PASS |
| Player isolation | PASS |
| Domain isolation | PASS |
| Version/provenance fields | PASS |
| Finite numeric validation | PASS |
| NaN/+Infinity rejected at store boundary | PASS |
| Negative values rejected | PASS |
| Four quadrants | PASS |
| Talent update leaves Potential | PASS |
| Potential update leaves Talent | PASS |
| Talent/Skill separation | PASS |
| Potential/stat separation | PASS |
| Temporary learning modifier cannot persist Talent | PASS |
| Temporary effect cannot persist Potential | PASS |
| No second modifier/resolver engine | PASS |
| Explicit legacy mapping model | PASS |
| Ambiguous legacy remains unresolved | PASS |
| No automatic label guessing | PASS |
| No synthetic profile migration | PASS |
| Direct `ensureV6()` migration idempotent | PASS |
| Direct `ensureV6()` preserves tested Phase 4/5 state | PASS |
| 1005 Talent entries | PASS by test evidence |
| 1005 Potential entries | PASS by test evidence |
| SQLite integrity_check | PASS by test evidence |
| FK check | PASS by test evidence |
| **Normal application current-schema invokes Phase 6** | **BLOCKER / FAIL** |
| Old campaign opened through real app gets Phase 6 tables | **BLOCKER / FAIL** |
| Restore path executes Phase 6 migration | **BLOCKER / FAIL** |
| Campaign switch executes Phase 6 migration | **BLOCKER / FAIL** |
| CI on exact resultCommit | IN PROGRESS at validation cutoff; not needed to establish blocker |

---

## 13. CI evidence at audit cutoff

GitHub Actions run for exact resultCommit:

- run number: `#146`
- run id: `31339730164`
- head SHA: `edce3524998abf2ffb5a6293b63b06b73f11b7cd`

At the final audit cutoff the run was still `in_progress`, with setup/validation successful and JVM unit tests executing.

Even a later green CI result cannot override the migration-entrypoint blocker because the current tests do not exercise the real application current-schema path for Phase 6.

---

## 14. Required correction before revalidation

CHAT-3 does not implement fixes.

The implementation worker must ensure the single real current-schema path executes Phase 6 migration for every normal old/new campaign open, restore and campaign switch.

A regression test must exercise the real application schema entrypoint, not only direct `ensureV6()` calls.

Minimum revalidation fixture:

```text
old Phase 5 DB
-> real LocalGameStore/current-schema path
-> Phase 6 tables exist
-> RPGOS-6.0-TALENT-POTENTIAL marker exists exactly once
-> ActivePlayerRef unchanged
-> PlayerStat.baseValue unchanged
-> PlayerResource.currentValue unchanged
-> Phase 4 aliases unchanged
-> modifiers unchanged
-> no synthetic Talent/Potential rows
-> repeat open/restore/campaign-switch remains idempotent
```

After a corrected resultCommit appears, WORK-022 should re-run the full migration/integrity matrix and issue a new exact verdict.

---

# Final status

**PHASE 6 INTEGRITY VALIDATION: FAIL**

Release blocker:

`LocalGameStore.ensureCurrentSchema()` still calls only `MigrationManager.ensureV4()`, so the real application does not run `ensureV6()` and does not apply `RPGOS-6.0-TALENT-POTENTIAL` to existing campaigns.
