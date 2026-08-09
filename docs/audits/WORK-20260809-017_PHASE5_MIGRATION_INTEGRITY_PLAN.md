# WORK-20260809-017 — Phase 5 Migration / Integrity Plan

Work ID: `WORK-20260809-017`  
Owner: CHAT-3  
Role: PHASE 5 MIGRATION / INTEGRITY AUDITOR  
Mode: READ-ONLY RUNTIME  
Repository: `piotreksmaga-art/rpg-os-android`  
Baseline inspected at plan creation: `9d997aaa7fbbb953333fb4d00521d868cc582320`  
Phase 4 canonical runtime: `6bdde251a3ef293a0cfa85c818538da4cc1307eb`  
Phase 5 implementation result under validation: **PENDING — WORK-20260809-015 not present at plan creation**  
Allowed write scope: this report only.

## 1. Purpose and authority model

This document is the independent migration/persistence oracle for Phase 5 (`DerivedValueResolver + Modifier Model`). It does not implement the resolver, modifiers, schema, migrations, repository APIs, PlayerDomainEngine, Talent, Potential, or any Phase 6+ runtime.

The validation baseline is the completed Phase 4 contract:

- `PlayerStat.baseValue` is AUTHORITATIVE / PERSISTENT progression.
- `PlayerResource.currentValue` is AUTHORITATIVE current quantity.
- `LegacyStatAlias` / `LegacyResourceAlias` reconcile legacy identity to one canonical typed UID.
- mapped legacy data must enter later systems exactly once;
- unresolved mixed legacy+typed ambiguity must fail before derived resolution;
- legacy bytes remain preserved.

Phase 5 may introduce persistent modifier state, but **resolved effective values, resource maximums, regeneration rates, traces and caches remain DERIVED unless an explicit later domain transaction commits a separate authoritative mutation**.

The MASTER invariant remains decisive:

`AUTHORITATIVE -> DERIVED -> CACHE/PRESENTATION`

Never the reverse as a resolver side effect.

---

## 2. Baseline observations before WORK-015

At plan creation the latest repository commit was `9d997aaa7fbbb953333fb4d00521d868cc582320`, documentation-only follow-up for the Phase 5 input gate. `WORK-20260809-015` was not yet present in commit history.

Phase 4 input contract is READY after `WORK-20260809-014`:

- legacy/typed same-semantic identities require explicit persisted alias mapping;
- mapped values expose canonical typed UID;
- unmapped same-key legacy+typed state fails loudly;
- alias `mappingVersion` and provenance are expected to influence future deterministic resolution/cache fingerprints;
- effective/max/regeneration remain outside authoritative Phase 4 inputs.

The current roadmap still marks Phase 5 as unimplemented in the checked-in canonical document; the coordinator has separately authorized implementation after declaring Phase 4 COMPLETE. This report does not update roadmap status.

---

## 3. Phase 5 persistence classification

The implementation validator must first classify every newly introduced field/table.

### 3.1 Modifier records

If modifiers are persisted, each persisted modifier is authoritative state describing an active or scheduled cause of derived behavior, not the resolved numeric result itself.

Required persisted identity/scoping invariants:

- stable `modifierUid`;
- `campaignId` scope;
- `characterUid` scope unless modifier is explicitly non-player/global by a documented contract;
- `targetDefinitionUid` plus target kind;
- operation;
- numeric magnitude or versioned rule binding;
- priority / stacking identity where applicable;
- source type + stable `sourceUid`;
- active flag / lifecycle;
- `validFrom` / `validUntil` semantics;
- provenance;
- version;
- no implicit dependence on SQLite insertion order.

If source existence is not enforced by FK because sources span multiple domain tables, the contract must explicitly distinguish:

- structurally valid modifier row,
- currently active/effective source,
- orphaned/deleted source behavior.

A missing/deleted source must never silently convert a temporary/equipment modifier into permanent progression.

### 3.2 Resolver outputs

`ResolvedStat.effectiveValue`, `ResolvedResource.maximumValue`, `ResolvedResource.regenerationRate`, contribution traces, diagnostics and optional fingerprints are derived.

If persisted at all, they are CACHE/PRESENTATION and must satisfy:

- deleting cache changes no authoritative state;
- rebuilding from the same canonical inputs yields the same semantic output;
- stale cache cannot become fallback authority after source/modifier deletion;
- cache rows cannot be fed back as `PlayerStat.baseValue` or `PlayerResource.currentValue`.

### 3.3 Current resource

`PlayerResource.currentValue` remains authoritative Phase 4 state. Resolver execution is read-only with respect to current quantity.

Even if:

`currentValue = 150`

and newly derived:

`maximumValue = 100`,

the resolver may return an over-cap diagnostic/proposal but must not execute or persist `currentValue = 100`.

---

## 4. Migration invariants

### M-01 — Additive schema
An old campaign without Phase 5 tables opens successfully. Migration may add tables/indexes/ledger marker but must not rewrite or drop Phase 4 stat/resource/alias state.

### M-02 — Phase 4 byte/semantic preservation
Before/after Phase 5 migration snapshots of these must be identical:

- `stat_definitions`,
- `player_stats`,
- `resource_definitions`,
- `player_resources`,
- `legacy_stat_aliases`,
- `legacy_resource_aliases`,
- legacy `character_stats`,
- legacy resource-like snapshot fields.

No Phase 5 migration is allowed to normalize, clamp, backfill or reinterpret Phase 4 authoritative values.

### M-03 — Migration idempotency
Repeated current-schema/Phase-5 ensure calls produce:

- one migration marker,
- stable modifier rows,
- no duplicate default modifiers,
- stable indexes/constraints,
- no changes to Phase 4 data.

### M-04 — Transaction safety
Any Phase 5 schema/data initialization and its migration marker that form one migration unit must commit atomically.

Failure before commit must leave:

- no partial modifier schema/data interpreted as valid;
- no applied marker if required changes did not commit;
- unchanged Phase 4 authoritative state.

### M-05 — No invented modifiers
Migration of an existing campaign must not infer injuries, equipment bonuses, buffs, debuffs or permanent modifiers from old presentation/effective/max fields unless there is a separately audited exact legacy mapping contract. Default safe policy: old campaigns start with zero Phase 5 modifier rows unless authoritative legacy modifier provenance already exists and is explicitly mapped.

### M-06 — Stable identity
Modifier UID must not be generated from row number, insertion sequence, current clock on every startup or unstable hash iteration.

### M-07 — Campaign/player isolation
Migration and persistence must never attach modifier state to active-player heuristic when a source player UID is known. No campaign A modifier may be visible in campaign B.

### M-08 — Phase 4 reconciliation preservation
Phase 5 migration cannot change alias target, `mappingVersion`, provenance, owner World Pack or canonical UID.

### M-09 — No parallel resolver truth
There must not be two independently authoritative persistent representations such as:

- authoritative modifier rows and separately authoritative effective-value rows;
- authoritative legacy modifier table and new modifier table both applied;
- persisted effective stat copied back into base stat.

If an old modifier/effect representation exists, implementation must provide one explicit migration/compatibility authority policy.

---

## 5. Required migration fixtures

Use real SQLite campaign databases where persistence is involved.

1. **phase4-clean.db** — Phase 4 typed stats/resources, no legacy data, no modifiers.
2. **phase4-legacy-only.db** — legacy stats/resource compatibility only.
3. **phase4-mapped-mixed.db** — typed definitions plus explicit stat/resource aliases and both legacy/typed values.
4. **phase4-unmapped-ambiguous.db** — intentionally unresolved mixed same-key state; Phase 5 must fail before graph resolution, not silently heal during migration.
5. **two-players.db** — same stat UID, different base values, modifier only on player A.
6. **two-campaigns/** — overlapping player/modifier/definition UIDs but separate campaign values.
7. **persistent-injury.db** — base 100 + persistent injury -40.
8. **persistent-equipment.db** — base 100 + equipment +25.
9. **persistent-temporary.db** — base 100 + active expiring buff +10.
10. **resource-overcap.db** — current 150, derived maximum 100.
11. **modifier-duplicate.db** — duplicate/conflicting modifier UID fixture.
12. **modifier-invalid-target.db** — target UID absent or target kind mismatch.
13. **modifier-invalid-lifetime.db** — invalid `validUntil < validFrom` if contract rejects it.
14. **large-modifiers.db** — >100 and preferably >1000 modifiers.
15. **post-phase5-backup.db** — valid Phase 5 persistent modifiers for backup/restore/reopen checks if persistence is added.

Each fixture needs a normalized pre/post oracle:

```text
AuthoritativeStat = campaignId, playerUid, statUid, baseValue, version
AuthoritativeResource = campaignId, playerUid, resourceUid, currentValue, version
Alias = campaignId, legacyUid, canonicalUid, worldPackUid, mappingVersion, provenance
Modifier = canonical persisted modifier fields only
```

Derived outputs are compared separately and must never replace this authoritative snapshot.

---

## 6. Core integrity tests

### I-01 — Old Phase 4 campaign opens

Given a DB with Phase 4 schema and no Phase 5 table, open through normal current-schema path.

Expected:

- no exception;
- Phase 5 migration applied once if needed;
- all Phase 4 authoritative rows unchanged;
- zero invented modifiers.

### I-02 — Phase 4 typed equality after migration

Snapshot all Phase 4 typed tables before migration and compare after.

Expected exact semantic equality.

### I-03 — Legacy aliases equality after migration

Snapshot `legacy_stat_aliases` and `legacy_resource_aliases` including mapping version/provenance before Phase 5 migration.

Expected exact equality afterward.

### I-04 — Legacy bytes equality

`character_stats` and legacy resource-like snapshot bytes/values remain unchanged by Phase 5 migration.

### I-05 — Repeated ensure

Run migration/schema ensure twice and reopen.

Expected stable modifier row counts, stable UIDs, one Phase 5 marker and identical Phase 4 state.

### I-06 — Partial failure rollback

Inject or reproduce failure during Phase 5 migration after first DDL/data initialization but before migration completion.

Expected no valid-looking partially applied Phase 5 state and unchanged Phase 4 authoritative rows.

### I-07 — Integrity check

After migration and after modifier mutations performed through legal persistence API:

`PRAGMA integrity_check = ok`.

### I-08 — Foreign-key policy

If Phase 5 schema declares FKs, run `PRAGMA foreign_key_check` with enforcement enabled and assert zero violations. If cross-domain sources intentionally cannot use FK, document and test store-side target/source validation explicitly.

---

## 7. No-retrogression release blockers

These tests are mandatory whether modifiers are persistent or supplied from another authoritative source.

### NR-01 — Injury survives reopen without changing base

Initial:

```text
PlayerStat.baseValue = 100
injury modifier = -40
```

Resolve:

```text
effectiveValue = 60
baseValue = 100
```

Close/reopen, resolve again:

```text
effectiveValue = 60
baseValue = 100
```

Remove/deactivate injury through the legal modifier persistence path, resolve:

```text
effectiveValue = 100
baseValue = 100
```

Any result where persisted base becomes `60` is an immediate **BLOCKER**.

### NR-02 — Equipment removal

Base `100`, equipment `+25` -> effective `125`. After equipment-source removal/deactivation -> effective `100`. Base must remain `100` before, during and after reopen.

### NR-03 — Temporary buff expiry/removal

Base `100`, temporary `+10` -> effective `110`. After expiry -> `100`. No `110` may be persisted into base.

### NR-04 — Repeated resolve

Resolve same injury input 100 times.

Expected every result `60`, persisted base always `100`. Never cumulative `20`, `-20`, etc.

### NR-05 — Source removal only removes derived contribution

Removing one source does not mutate unrelated persistent progression or unrelated modifier records.

---

## 8. Resource safety release blockers

### RS-01 — Resolver does not regenerate

Current `40`, regeneration rate derived `3.5`.

After any number of pure resolver calls, DB `currentValue` remains `40`.

### RS-02 — Resolver does not clamp current

Current `150`, derived max `100`.

Expected resolved projection may state current `150`, max `100`, over-cap=true/diagnostic. DB remains `150`.

Any implicit write to `100` is **BLOCKER**.

### RS-03 — Maximum is rebuildable

Delete derived/cache maximum if such cache exists and resolve again.

Expected identical maximum from canonical inputs; no current-value mutation.

### RS-04 — Regeneration is rebuildable

Same as RS-03 for regeneration rate.

### RS-05 — Derived-resource modifier does not rewrite definition bounds

Modifier-based max changes must not mutate `ResourceDefinition.minValue/maxValue` unless a separate explicit definition-management operation exists outside resolver execution.

---

## 9. Modifier persistence tests

Run these if WORK-015 persists modifiers.

### P-01 — Save/close/reopen equality

Persist one modifier of each supported lifecycle/source category. Reopen DB and compare all canonical fields exactly.

### P-02 — Duplicate UID

Same `modifierUid` with incompatible payload must fail loudly; no last-write-wins semantic hijack.

Identical retry may be idempotent only if explicitly documented and tested.

### P-03 — Campaign mismatch

Attempt to write campaign B modifier through campaign A store/repository.

Expected rejection before write.

### P-04 — Player mismatch

Player A resolution must never load Player B modifier.

### P-05 — Missing target

Modifier targeting unknown definition UID is rejected or quarantined according to explicit contract; never silently auto-creates a definition.

### P-06 — Target kind mismatch

A STAT modifier cannot be applied to RESOURCE_MAXIMUM merely because raw UID strings happen to match.

### P-07 — Finite numeric value

NaN/+Infinity/-Infinity rejected at construction/store boundary and again at resolver boundary for corrupted inputs.

### P-08 — Lifetime validation

Invalid lifetime relation must fail if contract requires `validUntil >= validFrom`.

### P-09 — Stable timestamps

Persistence must preserve exact declared validFrom/validUntil values across reopen; resolver receives explicit resolution epoch and does not substitute row insertion time.

### P-10 — Source removal behavior

Removing/deactivating source or modifier must not rewrite base; after reopen derived result reflects source absence.

### P-11 — >1000 modifiers

Persist and resolve >1000 legal modifiers for one or many targets.

Expected no LIMIT/truncation and deterministic complete input set.

---

## 10. Phase 4 reconciliation + Phase 5 tests

### L-01 — Mapped legacy stat resolves once

Legacy `strength` explicitly aliased to canonical typed stat UID.

Phase 5 input contains exactly one stat node at canonical typed UID. Modifier targeting canonical UID applies once.

### L-02 — Mapped legacy resource resolves once

Same for resource current input / derived max and regeneration target.

### L-03 — Unmapped ambiguous stat fails before resolver

Legacy and typed same-key stat without alias must trigger Phase 4 ambiguity before modifier graph evaluation. Resolver must not choose one or apply a modifier to both.

### L-04 — Unmapped ambiguous resource fails before resolver

Same requirement for resource.

### L-05 — Alias fingerprint

If deterministic input fingerprint/cache exists, changing alias `mappingVersion` or mapping identity/provenance that affects semantic input must change fingerprint or invalidate the corresponding derived result.

### L-06 — Alias target ownership

Phase 5 cannot bypass Phase 4 alias ownership validation by resolving raw legacy UID separately.

### L-07 — Same key in different World Packs

Two legal typed definitions with same text key but different UIDs remain distinct resolver nodes. No key-based collapse in Phase 5.

### L-08 — Unknown legacy value survives

An unrelated unmapped legacy value remains visible/resolvable as its compatibility identity unless it creates an unresolved mixed ambiguity. Phase 5 cannot discard it because no modifier targets it.

---

## 11. Rule-binding and graph integrity gates

Persistence audit must verify rule bindings remain references, not hidden authoritative numeric snapshots.

For:

- `StatDefinition.derivationRuleUid`,
- `ResourceDefinition.maxRuleUid`,
- `ResourceDefinition.regenerationRuleUid`,

validate:

1. missing required rule -> deterministic error;
2. incompatible provider/rule version -> deterministic error;
3. no fallback to stale cached numeric answer as authority;
4. cycles produce deterministic validation error before recursion overflow;
5. replay with same rule provider/version/input produces same result;
6. rule execution does not write base/current state;
7. provider/version identity participates in cache/input fingerprint if caching exists.

Cycle fixtures:

```text
A -> A
A -> B -> A
A -> B -> C -> A
```

All must terminate with explicit error.

A long acyclic dependency chain should resolve or hit a documented deterministic depth/resource limit, never stack-overflow unpredictably.

---

## 12. Backup / restore / reopen

If modifiers are persistent, Phase 5 must be verified with full campaign DB lifecycle.

### BR-01 — Backup old Phase 4 -> update -> restore

Restore a pre-Phase-5 backup through normal application restore/current-schema path.

Expected:

- Phase 5 schema initializes safely;
- Phase 4 stats/resources/aliases equal backup truth;
- no modifiers are invented.

### BR-02 — Backup post-Phase-5 modifier state

Persist modifiers, backup, mutate/remove them, restore backup.

Expected modifier rows return exactly to backed-up state; derived values rebuild from restored state; base/current values match backup authoritative state.

### BR-03 — Reopen after active penalty

Mandatory NR-01 injury scenario across actual close/reopen.

### BR-04 — Delete derived cache

If cache exists, delete only cache rows then reopen/resolve.

Expected identical derived output and unchanged authoritative data.

---

## 13. Isolation matrix

### Player isolation

- A and B share stat definition UID.
- base A=100, B=200.
- injury modifier -40 belongs only to A.

Expected:

- A effective=60;
- B effective=200;
- neither base changes.

Switch active player A -> B -> A and reopen. Same result.

### Campaign isolation

Campaign A and B may reuse same player UID, definition UID and modifier UID text.

Expected physical/logical repository scoping prevents cross-campaign contribution. If modifier UID is globally unique by contract, attempted reuse must fail only within the appropriate declared scope, not cause leakage.

### World Pack isolation

Modifier target is stable definition UID, not text key. World Pack B cannot hijack a target owned by World Pack A through same display key.

---

## 14. Required validation matrix after WORK-015 appears

Mark each as `PASS | FAIL | NOT TESTED | BLOCKER` with file/test evidence.

| Gate | Status before WORK-015 |
|---|---|
| Phase 5 migration exists or explicit no-migration design | PENDING |
| Existing Phase 4 campaign opens | PENDING |
| Phase 4 typed values unchanged | PENDING |
| Legacy aliases unchanged | PENDING |
| Legacy bytes unchanged | PENDING |
| Modifier identity stable | PENDING |
| Campaign/player scoping | PENDING |
| Modifier persistence reopen equality | PENDING |
| Duplicate modifier UID behavior | PENDING |
| No-retrogression injury | PENDING |
| No-retrogression equipment | PENDING |
| No-retrogression temporary buff | PENDING |
| Repeated resolve is pure | PENDING |
| Resource current never regenerated | PENDING |
| Resource current never clamp-written | PENDING |
| Derived max/regen rebuildable | PENDING |
| Mapped legacy stat resolves once | PENDING |
| Mapped legacy resource resolves once | PENDING |
| Unmapped ambiguity fails before resolver | PENDING |
| Same key/different World Pack stays distinct | PENDING |
| Rule missing/version mismatch deterministic | PENDING |
| Cycles rejected | PENDING |
| >1000 modifiers/no truncation | PENDING |
| SQLite integrity check | PENDING |
| FK policy/check | PENDING |
| JVM tests | PENDING |
| build | PENDING |
| CI on WORK-015 result commit | PENDING |

---

## 15. Automatic FAIL / BLOCKER conditions

Phase 5 integrity validation must be `FAIL` if any of the following is reproducible:

1. resolver writes `PlayerStat.baseValue`;
2. resolver writes `PlayerResource.currentValue` as regeneration/clamp side effect;
3. injury/equipment/temporary removal causes permanent base regression;
4. effective/max/regeneration cache is required to recover campaign truth;
5. Phase 5 migration changes Phase 4 values or aliases;
6. old campaigns receive inferred/invented modifiers without exact authoritative source mapping;
7. mapped legacy + typed identity is resolved twice;
8. unresolved Phase 4 ambiguity is silently resolved by Phase 5;
9. modifier rows leak across player/campaign;
10. duplicate modifier UID causes silent double application or semantic replacement;
11. expired/inactive modifier remains effective after reopen due stale cache;
12. missing rule/cycle falls back to stale or arbitrary value;
13. SQL errors are swallowed into empty modifier input producing a plausible but incorrect effective value;
14. query LIMIT truncates modifier set without explicit bounded-domain contract;
15. migration marker can commit while required modifier schema/data initialization rolls back.

---

## 16. Acceptance threshold

A future resultCommit for `WORK-20260809-015` may receive:

`PHASE 5 INTEGRITY VALIDATION: PASS`

only when:

- the Phase 5 persistence authority model is explicit;
- old Phase 4 campaigns open safely;
- Phase 4 typed state and reconciliation aliases are unchanged by migration;
- no-retrogression tests prove base progression survives injury/equipment/temporary effects across reopen/removal;
- resource resolver purity is proven;
- mapped/unmapped legacy reconciliation is honored before resolution;
- persistent modifiers, if any, are isolated, finite, stable and idempotent;
- rule/cycle failures are deterministic;
- no silent truncation exists;
- integrity/FK policy tests pass when schema changes;
- full JVM tests/build and CI pass on the exact runtime result commit.

Until a `WORK-20260809-015` resultCommit appears, this document is a validation plan only and does **not** issue PASS/FAIL for Phase 5 runtime.

## Current status

`PHASE 5 INTEGRITY PLAN READY — WAITING FOR WORK-20260809-015 RESULT COMMIT`
