# WORK-20260810-038 — Phase 9 Migration / Legacy Integrity Plan

Status: READ-ONLY RUNTIME / FINAL VALIDATION

Work ID: `WORK-20260810-038`
Role: PHASE 9 MIGRATION / LEGACY INTEGRITY AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Fresh master at plan creation: `4f8431e4cdf983f7f12fa73e544d988db30953ad`
Accepted Phase 8 runtime: `7a28e6e4b28ff10cbb516b94e5e0c120d0a15397`
Audited final Phase 9 runtime: `d796d374f92d94477542da5f753ee411b633076b`
Phase 9 implementation work item: `WORK-20260810-036`
Allowed write scope: this report only.

This document defines and now records final validation results for Phase 9 persistence, legacy evidence, explicit canonicalization, isolation, migration and no-regression. It does not implement runtime or later phases.

## 1. Confirmed legacy evidence boundary

`PlayerStateStore` reads every legacy `character_status_snapshot` column and classifies fields into persistent/derived/runtime. Fields not matched by derived/runtime heuristics survive as `persistent["legacy_status.<field>"]`. Therefore race/species/clan/bloodline/lineage/form/evolution-like labels survive losslessly as opaque persistent evidence, but are not typed Phase-9 authority.

A no-`entity_uid` status snapshot is accepted only when it contains exactly one row; multi-row ambiguity fails loudly.

`clan_uid` and other canon/reference facts are evidence/reference identity, not automatic innate-feature ownership. Skills, Techniques, Talent/Potential, stats and resources remain separate authoritative domains.

## 2. Required authority split

Phase 9 preserves separate authorities for:

- origin/identity assignments;
- owned innate features;
- evolution path definitions;
- evolution stages and transitions;
- persistent attained/unlocked state;
- persistent current evolution stage where applicable;
- unlocked forms;
- currently active form/runtime state.

Hard invariants:

`OWNED / UNLOCKED != CURRENTLY ACTIVE`

`EVOLUTION STAGE != TEMPORARY TRANSFORMATION`

Deactivation changes current active state only and does not delete unlock/ownership.

## 3. Legacy canonicalization policy

Default classification for legacy labels/fields is `UNRESOLVED EVIDENCE`.

No automatic canonicalization is permitted from:

- `clan_uid`;
- race/species text;
- bloodline/lineage text;
- form/transformation text;
- evolution/stage text;
- prompt prose or historical CharacterPanel text.

Canonicalization requires explicit World-Pack-owned mapping with stable target UID, version and provenance. Mapping target ownership is validated. Ambiguous mappings fail loudly. Missing/deleted targets fail loudly. Legacy bytes remain unchanged after mapping.

## 4. Migration gates

Production chain:

`LocalGameStore.ensureCurrentSchema()` -> `CurrentSchema.ensure()` -> `ensureV9()` -> prior schema chain.

`RPGOS-9.0-INNATE-EVOLUTION` is created inside the additive V9 migration transaction after Phase 8 is ensured. Repeated latest-schema ensure is idempotent and leaves one migration marker.

## 5. Phase 3–8 no-regression result

Final runtime preserves:

- ActivePlayerRef semantics;
- PlayerStat base values and PlayerResource current values;
- stat/resource aliases and legacy bytes;
- Phase-5 modifiers and accepted target kinds;
- Talent/Potential profiles;
- Skill baseMastery and reconciliation;
- Technique baseMastery/history/reconciliation/resource-cost mappings;
- legacy `character_status_snapshot` bytes.

Phase-9 active-form effects use existing Phase-5 generic modifiers. Tests verify base Stat/Skill/Technique/Talent/Potential values remain unchanged during activation and after deactivation.

## 6. Final validation matrix

- P9-01 definition registration and World Pack ownership: PASS
- P9-02 duplicate UID fail-loud: PASS
- P9-03 same label/different UID remains separate: PASS
- P9-10 origin persistence: PASS
- P9-11 innate feature ownership persistence: PASS
- P9-12 campaign/player/World Pack isolation: PASS
- P9-20 unlocked != active: PASS
- P9-21 deactivation preserves unlock: PASS
- P9-22 active-without-unlock rejected: PASS
- P9-30 path/stage persistence: PASS
- P9-31 legal transition succeeds: PASS
- P9-32 invalid/cross-path transition rejected: PASS
- P9-33 arbitrary rollback without legal transition rejected: PASS
- P9-34 attained stage history preserved: PASS
- P9-40 clan/race/bloodline/form/evolution labels alone grant nothing: PASS
- P9-41 unresolved legacy evidence preserved: PASS
- P9-42 explicit mapping canonicalizes exactly once: PASS
- P9-43 mixed ambiguity fail-loud/unresolved: PASS
- P9-44 missing/deleted mapping target fail-loud: PASS
- P9-45 mapping owner validation: PASS
- P9-46 mapping re-application/idempotency: PASS
- P9-47 legacy bytes unchanged: PASS
- P9-50 temporary form changes only derived/effective state: PASS
- P9-51 base Stat/Skill/Technique/Talent/Potential unchanged: PASS
- P9-60 current-schema path reaches V9: PASS
- P9-61 restore/campaign switch latest-schema routing: PASS
- P9-62 migration idempotent: PASS
- P9-63 Phase 3–8 no-regression: PASS
- P9-64 1005 entries no authoritative truncation: PASS
- P9-65 `PRAGMA integrity_check` = `ok`: PASS
- P9-66 `PRAGMA foreign_key_check` empty: PASS
- P9-67 exact candidate CI #196 succeeds: PASS

## 7. Evidence summary from final runtime

### Legacy safety

`Phase9LegacySafetyTest` proves bare `clan_uid`, race, bloodline, evolution-stage and form evidence produces no canonical origin, feature, evolution state, stage, unlock or active form. It also proves ambiguous explicit mappings fail, a deleted target fails, and unmapped evidence remains explicitly unresolved beside typed state.

### Explicit mapping and exactly-one materialization

`Phase9EvolutionLegacyMappingTest` applies the same explicit evolution-stage mapping twice and still produces exactly one current state and one attained stage while preserving the original legacy bytes.

### Unlock vs active and reopen

`Phase9ReopenStateTest` persists origin, feature, evolution state, attained stage, unlock and active form across close/reopen. Deactivation removes only active state while the unlock remains.

### Evolution transition integrity

`Phase9PersistenceTest` rejects an unauthorized cross-path transition definition, rejects missing transitions, requires the current source stage to match, preserves attained stages A and B after A -> B, and rejects reusing the A -> B transition as an implicit rollback.

### Isolation and no-retrogression

`Phase9PersistenceTest` covers player and campaign isolation and World-Pack ownership checks. Active forms create generic Phase-5 modifiers but do not mutate base stat, Skill mastery, Technique mastery, Talent or Potential.

### 1000+ / integrity / FK

The same test registers and persists 1005 innate features, confirms all 1005 are returned after reopen, verifies `PRAGMA integrity_check` returns `ok`, and verifies `PRAGMA foreign_key_check` returns no rows.

### Production routing

`Phase9ProductionRoutingTest` proves campaign switch and restore route actual V8 campaign databases through `LocalGameStore` to V9. `CurrentSchema.ensure()` points to `ensureV9()`, so normal latest-schema entrypoints use the same chain.

## 8. CI evidence

Exact runtime SHA:

`d796d374f92d94477542da5f753ee411b633076b`

GitHub Actions run:

`#196` / run ID `31349200549`

Result:

`SUCCESS`

The run is for the exact audited SHA and completed successfully.

## 9. Final verdict

`PHASE 9 INTEGRITY VALIDATION: PASS`
