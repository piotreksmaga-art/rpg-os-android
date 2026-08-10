# WORK-20260810-038 — Phase 9 Migration / Legacy Integrity Plan

Status: READ-ONLY RUNTIME / FINAL REVALIDATION

Work ID: `WORK-20260810-038`
Role: PHASE 9 MIGRATION / LEGACY INTEGRITY AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Fresh master at plan creation: `4f8431e4cdf983f7f12fa73e544d988db30953ad`
Accepted Phase 8 runtime: `7a28e6e4b28ff10cbb516b94e5e0c120d0a15397`
Previously audited Phase 9 runtime: `d796d374f92d94477542da5f753ee411b633076b`
Audited final Phase 9.1 hotfix runtime: `c64c123104f1643a53bf9bb5ebbf19e4bc0dfe87`
Phase 9 implementation work item: `WORK-20260810-036`
Allowed write scope: this report only.

This document defines and records final validation results for Phase 9 persistence, legacy evidence, explicit canonicalization, isolation, migration, requirement-gate hotfixing and no-regression. It does not implement runtime or later phases.

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

## 4. Phase 9.1 requirement-gates migration

The hotfix adds migration:

`RPGOS-9.1-REQUIREMENT-GATES`

Production chain is now:

`LocalGameStore.ensureCurrentSchema()` -> `CurrentSchema.ensure()` -> `ensureV9RequirementHotfix()` -> `ensureV9()` -> prior schema chain.

The migration is additive and idempotent. It adds only nullable requirement-version/binding columns:

- `evolution_transition_definitions.requirement_rule_version`;
- `form_definitions.unlock_requirement_rule_uid`;
- `form_definitions.unlock_requirement_rule_version`;
- `form_definitions.activation_rule_version`.

Existing pre-hotfix `requirement_rule_uid` and `activation_rule_uid` values are preserved. Where an old UID exists without a version, the migration deterministically assigns version `1`. Existing player origins, innate ownership, evolution current state/history, unlocks, active forms, Phase 3–8 authority and legacy evidence are not rewritten.

Repeated current-schema ensure leaves one `RPGOS-9.1-REQUIREMENT-GATES` marker and does not duplicate state.

## 5. Requirement gate semantics / atomicity

Phase 9.1 separates three requirement gates:

- `UNLOCK`;
- `TRANSITION`;
- `ACTIVATION`.

A rule bound for one gate cannot substitute for another. Missing provider/rule, wrong version, malformed result and dependency cycle fail deterministically.

Required failure atomicity is preserved:

- failed form unlock creates no unlock row;
- failed form activation creates no active form and no active Phase-5 modifiers;
- failed evolution transition preserves existing current state and attained history;
- failed ENTRY transition creates neither current evolution state nor attained-stage row.

Successful ENTRY is explicit and provenance-bearing: the attained stage stores the exact `attained_via_transition_uid` for the entry transition.

## 6. Explicit ENTRY and legacy evolution mapping

Direct stage entry is forbidden. A path can start only through an explicit transition whose source stage is null.

For legacy `EVOLUTION_STAGE` materialization, `applyLegacyMappings()` no longer bypasses the transition contract. It requires exactly one explicit ENTRY transition targeting the mapped stage, then executes normal `transitionEvolution()` including its transition requirement gate.

Consequences:

- no ENTRY transition -> fail-loud;
- more than one candidate ENTRY -> fail-loud;
- failed ENTRY requirement -> zero persistent current/attained state;
- successful ENTRY -> exactly one current state and one attained stage with transition provenance;
- replaying ENTRY or using ENTRY as rollback fails.

Legacy bytes remain unchanged throughout.

## 7. Phase 3–8 no-regression result

Final hotfix preserves:

- ActivePlayerRef semantics;
- PlayerStat base values and PlayerResource current values;
- stat/resource aliases and legacy bytes;
- Phase-5 modifiers and accepted target kinds;
- Talent/Potential profiles;
- Skill baseMastery and reconciliation;
- Technique baseMastery/history/reconciliation/resource-cost mappings;
- legacy `character_status_snapshot` bytes;
- pre-hotfix Phase-9 origins/features/evolution state/history/unlocks/active forms.

Phase-9 active-form effects continue to use existing Phase-5 generic modifiers. Requirement failures do not create partial persistent Phase-9 state.

## 8. Production routing / reopen / restore / campaign switch

`CurrentSchema.ensure()` now invokes `ensureV9RequirementHotfix()`, so every production caller of the common latest-schema entrypoint reaches V9.1.

`LocalGameStore.bootstrap()` calls the common `ensureCurrentSchema()` path. `restoreBackup()` restores the database and then invokes `ensureCurrentSchema()`. `setActiveCampaign()` switches the campaign and immediately invokes the same entrypoint. Therefore old V9 databases opened through bootstrap/restore/campaign switch are upgraded through the same V9.1 chain.

Reopen of a V9.1 database is idempotent; successful unlock/active state persists across reopen. Existing V9 rows remain readable after the additive column migration.

## 9. Final revalidation matrix

- P9.1-01 migration additive: PASS
- P9.1-02 migration idempotent / one marker: PASS
- P9.1-03 existing Phase-9 state preserved: PASS
- P9.1-04 Phase 3–8 state preserved: PASS
- P9.1-05 existing transition requirement UID preserved as version 1: PASS
- P9.1-06 existing activation rule UID preserved as version 1: PASS
- P9.1-07 unlock requirement UID/version fields added without guessing: PASS
- P9.1-08 old V9 DB reaches V9.1 through current schema: PASS
- P9.1-09 V9.1 reopen idempotent: PASS
- P9.1-10 bootstrap uses latest schema: PASS
- P9.1-11 restore uses latest schema: PASS
- P9.1-12 campaign switch uses latest schema: PASS
- P9.1-13 failed unlock leaves no partial state: PASS
- P9.1-14 failed activation leaves no partial active state/modifier: PASS
- P9.1-15 failed transition preserves current/history: PASS
- P9.1-16 failed ENTRY writes no current/attained state: PASS
- P9.1-17 legal ENTRY stores exact transition provenance/history: PASS
- P9.1-18 direct stage entry forbidden: PASS
- P9.1-19 ENTRY replay/rollback rejected: PASS
- P9.1-20 legacy evolution materialization requires explicit ENTRY: PASS
- P9.1-21 legacy evidence lossless: PASS
- P9.1-22 explicit legacy mapping owner validation preserved: PASS
- P9.1-23 campaign/player/World-Pack isolation preserved: PASS
- P9.1-24 `PRAGMA integrity_check` = `ok`: PASS
- P9.1-25 `PRAGMA foreign_key_check` empty: PASS
- P9.1-26 full JVM regression suite exact hotfix SHA: PASS
- P9.1-27 signed ALPHA APK exact hotfix SHA: PASS
- P9.1-28 exact hotfix CI succeeds: PASS

## 10. Evidence summary

`Phase9RequirementMigration.kt` proves the hotfix migration calls `ensureV9()` first, adds only missing nullable columns, deterministically backfills version `1` for pre-hotfix transition/activation rule UIDs, writes a single migration marker and does not rewrite player state or legacy evidence.

`Phase9RequirementGatesTest` proves unlock/transition/activation gate separation, failure atomicity, missing/wrong-version/malformed/cycle failures, migration idempotency, reopen persistence, `integrity_check` and `foreign_key_check`.

`Phase9EntryTransitionTest` proves only explicit ENTRY transitions can start a path, failed ENTRY creates zero current/attained state, successful ENTRY creates exactly one state/history record with `attained_via_transition_uid`, ENTRY replay/rollback fails, World-Pack ownership is validated and reopen preserves entry provenance.

`Phase9Store.applyLegacyMappings()` proves mapped evolution stages use `entryTransitionUidForStage()` and normal `transitionEvolution()` rather than direct stage insertion, so legacy mapping cannot bypass transition requirements.

Existing Phase-9 safety/persistence tests continue to cover lossless legacy evidence, explicit mapping, unlock-vs-active, 1005 entries, isolation and Phase 3–8 no-retrogression.

## 11. CI evidence

Exact hotfix SHA:

`c64c123104f1643a53bf9bb5ebbf19e4bc0dfe87`

GitHub Actions exact-SHA run:

`#213` / run ID `31350492914`

Result:

`SUCCESS`

The exact hotfix run completed successfully. `Validate project`, full JVM unit tests and `Build signed ALPHA APK` all passed.

Note: CI `#196` belongs to the previous runtime `d796d374f92d94477542da5f753ee411b633076b`; the exact CI run for the requested hotfix SHA is `#213`.

## 12. Final verdict

`PHASE 9 INTEGRITY REVALIDATION: PASS`
