# WORK-20260810-038 — Phase 9 Migration / Legacy Integrity Plan

Status: READ-ONLY RUNTIME / PRE-IMPLEMENTATION VALIDATION PLAN

Work ID: `WORK-20260810-038`
Role: PHASE 9 MIGRATION / LEGACY INTEGRITY AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Fresh master at plan creation: `4f8431e4cdf983f7f12fa73e544d988db30953ad`
Accepted Phase 8 runtime: `7a28e6e4b28ff10cbb516b94e5e0c120d0a15397`
Phase 9 implementation work item: `WORK-20260810-036`
Allowed write scope: this report only.

This document defines release gates for Phase 9 persistence, legacy evidence, explicit canonicalization, isolation, migration and no-regression. It does not implement runtime or later phases.

## 1. Confirmed legacy evidence boundary

`PlayerStateStore` reads every legacy `character_status_snapshot` column and classifies fields into persistent/derived/runtime. Fields not matched by derived/runtime heuristics survive as `persistent["legacy_status.<field>"]`. Therefore race/species/clan/bloodline/lineage/form/evolution-like labels can survive losslessly as opaque persistent evidence, but they are not typed Phase-9 authority.

A no-`entity_uid` status snapshot is accepted only when it contains exactly one row; multi-row ambiguity fails loudly. This rule must be preserved by Phase 9.

`canon_characters_v2.clan_uid` and other world/canon reference facts are evidence/reference identity, not automatic innate-feature ownership. `clan_uid` alone must never grant a bloodline feature.

Skills, Techniques, Talent/Potential, stats and resources are already separate authoritative domains and must not be mined heuristically to invent Phase-9 state.

## 2. Required authority split

Phase 9 must preserve separate authorities for:

- origin/identity assignments;
- owned innate features;
- evolution path definitions;
- evolution stages and transitions;
- persistent attained/unlocked state;
- persistent current evolution stage where the path contract requires one;
- unlocked forms;
- currently active form/runtime state.

Hard invariants:

`OWNED / UNLOCKED != CURRENTLY ACTIVE`

`EVOLUTION STAGE != TEMPORARY TRANSFORMATION`

Deactivation may change current runtime state but must never delete unlock/ownership.

## 3. Legacy canonicalization policy

Default classification for legacy labels/fields is `UNRESOLVED EVIDENCE`.

No automatic canonicalization is permitted from:

- `clan_uid`;
- race/species text;
- bloodline/lineage text;
- form/transformation text;
- evolution/stage text;
- prompt prose or historical CharacterPanel text.

Canonicalization requires explicit World-Pack-owned mapping with stable target UID, version and provenance. Mapping target ownership must be validated. Mixed legacy + typed same semantic state without explicit reconciliation must fail loudly or remain explicitly unresolved; it must not expose two canonical truths.

Legacy bytes must remain unchanged after mapping.

## 4. Migration gates

Expected production chain:

`LocalGameStore.ensureCurrentSchema()` -> `CurrentSchema.ensure()` -> latest schema -> V9.

Release blockers:

- production entrypoint does not reach V9;
- bootstrap/restore/campaign switch can leave an old schema;
- V9 migration mutates Phase 3–8 authority;
- repeated latest-schema ensure duplicates state or markers;
- partial migration can leave a success marker beside incomplete schema.

V9 must be additive and idempotent.

## 5. Phase 3–8 no-regression snapshot

Before/after migration verify semantic equality for:

- ActivePlayerRef;
- PlayerStat base values and Resource current values;
- stat/resource aliases and legacy bytes;
- modifiers and all accepted target kinds;
- Talent/Potential profiles and Phase-6 mappings/evidence;
- Skill definitions/player baseMastery/legacy reconciliation;
- Technique definitions/player baseMastery/history/legacy reconciliation/resource-cost mappings;
- legacy `character_status_snapshot` bytes.

Temporary Phase-9 form effects may use existing Phase-5 modifiers, but may not write back into any base authority above.

## 6. Required fixtures

At minimum validate:

1. legacy `race` label only -> unresolved, no canonical origin;
2. legacy `species` label only -> unresolved;
3. `clan_uid` only -> no innate feature grant;
4. legacy `bloodline` / `lineage` label -> unresolved;
5. legacy `form` label -> unresolved, no unlock/active state;
6. legacy `evolution_stage` label -> unresolved, no stage assignment;
7. explicit origin mapping -> exactly one canonical origin;
8. explicit innate mapping -> exactly one owned feature;
9. explicit evolution mapping -> exactly one canonical state according to mapping;
10. mixed legacy+typed without mapping -> fail-loud/unresolved;
11. mapping target missing or owner-changed -> fail-loud;
12. two players same legacy label remain isolated;
13. same character UID in two campaigns remains isolated;
14. same display label in two World Packs remains separate;
15. unlocked form inactive;
16. activate -> active while unlock persists;
17. deactivate -> inactive while unlock persists;
18. active form without unlock -> fail-loud;
19. stage transition source->target legal;
20. missing/invalid transition -> fail-loud;
21. cross-path transition rejected unless definition explicitly allows it;
22. reopen persistence;
23. 1000+ feature/state rows without truncation.

## 7. Evolution integrity gates

Definitions must use stable UIDs and World Pack ownership. Transition identity must be explicit; Core must not infer `stage + 1`.

Validation must prove:

- source and target stages exist;
- path ownership is valid;
- illegal cross-path transitions fail;
- rollback to a prior stage requires an explicit legal transition when the path contract disallows arbitrary rollback;
- transition does not automatically rewrite Skill/Technique mastery, Talent/Potential or base stats;
- attained/history state is not silently erased when current stage changes.

## 8. Unlock / active-form integrity gates

- ownership/unlock is persistent authority;
- active form is separate current/runtime state;
- temporary activation cannot manufacture permanent unlock;
- deactivation cannot remove unlock;
- mutually exclusive forms must follow explicit definition/rule semantics, not name heuristics;
- form-derived effects use the existing generic Modifier/DerivedValueResolver foundation;
- no Race/Bloodline/Evolution-specific second resolver is allowed.

## 9. 1000 / integrity / FK gates

Final validation must verify:

- 1000+ definitions/player state rows without authoritative truncation;
- `PRAGMA integrity_check` = `ok`;
- `PRAGMA foreign_key_check` returns no rows under adopted FK policy;
- reopen and repeated migration preserve exact counts/state.

## 10. Concrete release matrix after WORK-036

- P9-01 definition registration and World Pack ownership;
- P9-02 duplicate UID fail-loud;
- P9-03 same label/different UID remains separate;
- P9-10 origin persistence;
- P9-11 innate feature ownership persistence;
- P9-12 campaign/player/World Pack isolation;
- P9-20 unlocked != active;
- P9-21 deactivation preserves unlock;
- P9-22 active-without-unlock rejected;
- P9-30 path/stage persistence;
- P9-31 legal transition succeeds;
- P9-32 invalid/cross-path transition rejected;
- P9-40 clan/race/bloodline/form/evolution labels alone grant nothing;
- P9-41 unresolved legacy evidence preserved;
- P9-42 explicit mapping canonicalizes exactly once;
- P9-43 mixed ambiguity fail-loud/unresolved;
- P9-44 legacy bytes unchanged;
- P9-50 temporary form changes only derived/effective state;
- P9-51 base Stat/Skill/Technique/Talent/Potential unchanged;
- P9-60 old DB via production current-schema path receives V9;
- P9-61 bootstrap/restore/campaign switch reach V9;
- P9-62 migration idempotent;
- P9-63 Phase 3–8 semantic snapshots unchanged;
- P9-64 1000+ entries no truncation;
- P9-65 integrity/FK clean;
- P9-66 exact candidate CI succeeds.

## 11. Final validation procedure

After WORK-036 appears, CHAT-3 must audit the exact final resultCommit, not an earlier WIP SHA:

1. re-check current master and exact candidate;
2. inspect candidate diff and Phase-9 schema/model/store/tests;
3. verify production `CurrentSchema.ensure()` reaches V9;
4. execute/review the matrix above;
5. verify legacy bytes and Phase 3–8 snapshots;
6. verify 1000+ entries, integrity/FK;
7. verify exact candidate JVM/build/CI evidence;
8. update this report with reproduced evidence and one exact verdict.

Final verdict must be exactly one of:

`PHASE 9 INTEGRITY VALIDATION: PASS`

or

`PHASE 9 INTEGRITY VALIDATION: FAIL`

## 12. Current checkpoint

At plan creation, fresh master is `4f8431e4cdf983f7f12fa73e544d988db30953ad`, a Phase-8 audit/report commit. No Phase-9 runtime candidate has been accepted in this validation yet.

Current evidence supports the conservative policy: legacy status fields are preserved as evidence through `legacy_status.*`; clan/canon references are separate from player feature ownership; Phase 9 must add explicit typed state without semantic guessing.
