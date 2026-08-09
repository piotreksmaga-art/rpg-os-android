# WORK-20260810-033 — Phase 8 Migration / Legacy Integrity Plan

Status: READ-ONLY RUNTIME / PRE-IMPLEMENTATION VALIDATION PLAN

Work ID: `WORK-20260810-033`
Owner: `CHAT-3`
Role: PHASE 8 MIGRATION / LEGACY INTEGRITY AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Fresh master at plan creation: `0653c6c6fe03da3db98623112f7a0af4c3f88464`
Accepted Phase 7 runtime: `8075487d24bf0b3da1bcbd8f9fb2483e9154ec6c`
Phase 8 implementation work item: `WORK-20260810-031`
Allowed write scope: this report only.

This document defines the independent migration, legacy-compatibility, reconciliation, persistence and no-regression gates for Phase 8 (`TechniqueDefinition + PlayerTechnique`). It does not implement runtime, schema, resolver changes, DevelopmentProject, Technique creation workflow, ProgressionEngine, CharacterPanel v2, or any later phase.

---

## 1. Canonical authority boundary

Phase 8 must preserve the semantic split:

```text
Technique
= concrete learned/owned executable method, action, ability or move

Technique != Skill
Technique != Talent
Technique != Potential
Technique != Stat
Technique != Resource
```

A Skill is general competence. A Technique is one concrete ability/method that may require one or more Skills and may consume one or more Resources.

If Technique mastery exists, the expected authority model is:

```text
PlayerTechnique.baseMastery
= persistent authoritative proficiency for that Technique

effective Technique mastery
= derived/rebuildable projection only
```

Temporary injury/equipment/environment/buff effects must never rewrite persistent Technique mastery merely because an effect is active, expires or is removed.

Logical player-Technique identity must be at least:

```text
(campaignId, characterUid, techniqueUid)
```

Stable UID is identity. Display name is not identity.

---

## 2. Confirmed legacy/runtime inventory before WORK-031

### 2.1 `character_techniques`

Current `ContextBuilder` directly reads the active player's legacy Technique rows with:

```text
entity_uid
technique_uid
mastery
xp
learned_chapter
last_used_chapter
usage_count
success_count
failure_count
is_equipped
notes
```

and sorts them by equipped/mastery/xp with a presentation/context `LIMIT 60`.

Validation interpretation:

- `entity_uid` is legacy character identity evidence and must be preserved exactly;
- `technique_uid` is legacy Technique identity evidence and must not disappear even if its definition is missing;
- `mastery` is persisted proficiency-like state and must remain lossless until Phase 8 proves its exact mapping;
- `xp` is persisted progress-like state, but its semantics are not proven by the current Kotlin read path; preserve losslessly and do not reinterpret;
- `learned_chapter` is historical metadata, not a complete provenance record;
- `last_used_chapter`, `usage_count`, `success_count`, `failure_count` are persistent historical/telemetry-like evidence and must not be discarded;
- `is_equipped` is persisted current selection/loadout-like state and must not be confused with ownership/learned state;
- `notes` is opaque legacy metadata and cannot silently become rule input.

The current `LIMIT 60` is presentation/context budgeting only. A new authoritative Technique store/read must not truncate at 60.

### 2.2 `technique_definitions`

Current CharacterPanel joins `character_techniques` to `technique_definitions` using `technique_uid` and consumes at least:

```text
technique_uid
name
category
base_chakra_cost
```

The player row also exposes `chakra_cost_override` through the CharacterPanel join.

This proves a Naruto-specific legacy storage fact, not a valid generic Core cost model.

### 2.3 `canon_technique_index`

Current browser/reference code reads `canon_technique_index` from the World Pack with at least:

```text
name
category
rank
element_key
wiki_url
verification_status
```

This is a browser/reference/canon-index surface. It is not proven to be the authoritative `TechniqueDefinition` store and must not auto-create or auto-link a typed Technique definition by name.

### 2.4 Existing presentation/backend surfaces

- CharacterPanel reads legacy Technique rows directly.
- ContextBuilder sends legacy Technique rows for the resolved active player.
- Backend prompt currently treats `player_techniques` as authoritative learned abilities.

Phase 8 therefore must replace or adapt these direct legacy reads only after typed compatibility/reconciliation is proven. UI/prompt history must not become a competing source of truth.

---

## 3. Mandatory schema preflight for WORK-031 and final validation

Before accepting any migration logic, inspect the actual candidate database schema rather than infer from column names alone:

```text
PRAGMA table_info(character_techniques)
PRAGMA table_info(technique_definitions)
PRAGMA table_info(canon_technique_index)
PRAGMA index_list(...)
PRAGMA foreign_key_list(...)
```

Also determine:

- whether `(entity_uid, technique_uid)` is unique or historical duplicate rows are possible;
- exact data types/defaults/nullability;
- actual mastery and XP ranges;
- whether negative/corrupt/non-finite values can exist historically;
- whether `technique_definitions` is campaign-local, bundled, copied, or World-Pack-owned in practice;
- whether `chakra_cost_override` can be null/negative/non-finite;
- whether usage counters can be inconsistent (`success + failure > usage`, negative counters, etc.);
- orphan `technique_uid` count;
- duplicate names/categories across definition/reference sources.

Binary asset contents must not be semantically guessed.

---

## 4. Required compatibility/reconciliation contract

Phase 8 must not create a second unrelated Technique truth beside `character_techniques`.

Acceptable model:

```text
legacy Technique evidence / legacy Technique UID
    -> explicit, versioned mapping/supersession
    -> canonical typed TechniqueDefinition UID
```

Requirements:

- mapping is explicit and deterministic;
- mapping is campaign-safe, character-safe where necessary, and World-Pack-safe;
- mapping records version/provenance;
- mapping target ownership is validated;
- mapping target deletion/owner change fails loudly;
- after explicit supersession, exactly one canonical player Technique authority is exposed;
- legacy bytes remain unchanged for compatibility/history;
- unrelated unmapped legacy Techniques remain visible as unresolved compatibility state;
- legacy-only rows must never become an empty typed read.

Forbidden:

- same name == same Technique;
- same category == same Technique;
- canon-index name match == canonical Technique definition;
- always prefer the newest row/typed representation without explicit mapping;
- merge/average mastery;
- delete legacy rows after mapping;
- silently discard orphan UIDs.

Mixed legacy + typed same logical Technique without explicit mapping must fail loudly or surface as explicit unresolved ambiguity. It must never silently expose two authoritative mastery values.

---

## 5. Legacy XP/history acceptance rule

The final validator must not approve any implementation that infers XP semantics only because the column is named `xp`.

Acceptable outcomes:

1. exact semantics are proven and mapped through an explicit versioned rule; or
2. XP is preserved as opaque/raw legacy progress evidence until a future progression rule defines it.

Likewise, usage/success/failure fields must be classified explicitly:

- if authoritative historical counters, preserve exact values;
- if telemetry/cache, preserve legacy bytes and document that they are not gameplay authority;
- do not silently recompute them during migration.

Unacceptable:

- resetting XP/counters to zero;
- deriving Technique mastery from XP without a proven rule;
- deriving Skill mastery/Talent/Potential from Technique XP;
- converting counters into permanent progression without a later legal progression engine.

---

## 6. Generic resource-cost migration gate

Legacy storage includes Naruto-specific:

```text
base_chakra_cost
chakra_cost_override
```

Phase 8 Core must not promote `chakra` into a universal resource concept.

Required generic target shape is equivalent to:

```text
TechniqueDefinition/TechniqueCostBinding
    -> ResourceDefinition.resourceUid
    -> amount and/or versioned rule binding
```

Legacy cost handling rules:

- preserve `base_chakra_cost` and `chakra_cost_override` losslessly;
- without explicit World Pack mapping from legacy chakra-cost semantics to a specific `ResourceDefinition.resourceUid`, keep the cost unresolved/compatibility-only;
- do not choose a resource by display name/key heuristics;
- explicit mapping must validate World Pack ownership and target existence;
- deleted or owner-changed resource target must fail loudly;
- player override must not overwrite definition base cost or vice versa;
- migration must not charge resources or mutate current resource values.

---

## 7. Skill requirement integrity gates

A typed TechniqueDefinition may require typed `SkillDefinition` by stable UID.

Final validation must prove:

- missing Skill UID fails deterministically;
- wrong World Pack/owner relationship follows the declared policy and cannot hijack identity;
- requirement semantics explicitly state whether they use `baseMastery` or `effectiveMastery`;
- no requirement is inferred by matching label/category;
- Skill mastery is not copied into Technique mastery;
- learning/updating a Skill does not automatically grant Technique or rewrite Technique mastery;
- losing a temporary effective Skill threshold may temporarily block use but must not delete learned Technique ownership;
- acquisition requirement and execution requirement are not silently assumed identical unless the definition contract explicitly says so.

---

## 8. Phase-5 effective Technique boundary

If WORK-031 introduces `TECHNIQUE_EFFECTIVE` or equivalent, it must extend the existing generic derived/modifier foundation only.

Required gates:

- no `TechniqueModifierEngine` or parallel resolver;
- deterministic modifier ordering/tie-breaking remains identical to Phase 5;
- target-kind validation prevents Technique modifiers from accidentally targeting stat/skill/resource definitions and vice versa;
- resolver is read-only with respect to `PlayerTechnique.baseMastery`;
- removing injury/equipment/buff restores effective mastery without base regression;
- campaign/player isolation remains enforced;
- cycle/missing-rule guards remain deterministic if Technique participates in derived dependency graph;
- input fingerprint/cache identity includes relevant Technique definition/version/mapping data where applicable.

---

## 9. Production schema / migration gates

Production latest-schema routing is release-blocking.

Required final path:

```text
LocalGameStore.ensureCurrentSchema()
    -> CurrentSchema.ensure()
    -> latest schema
    -> Phase 8 migration
```

Final validation must prove at least:

- old Phase-7 campaign opens through real production path and receives Phase-8 objects/marker;
- bootstrap reaches latest schema;
- restore reaches latest schema;
- campaign switch reaches latest schema;
- reopen is idempotent;
- current-schema ensure repeated twice creates one Phase-8 migration marker and no duplicate definitions/player values/mappings;
- failed/partial migration cannot leave success marker beside incomplete schema.

A test that invokes `MigrationManager.ensureV8()` directly is insufficient by itself.

---

## 10. Phase 3–7 no-regression snapshot

Before migration, capture semantic snapshots / row counts / stable hashes where practical for:

### Phase 3
- ActivePlayerRef.

### Phase 4
- stat definitions/player base values;
- resource definitions/player current values;
- stat/resource legacy aliases and legacy bytes.

### Phase 5
- all existing modifiers and lifecycle/source/version fields;
- accepted target kinds including `SKILL_EFFECTIVE`.

### Phase 6
- progression domains;
- Talent profiles;
- Potential profiles;
- Phase-6 legacy evidence/mappings.

### Phase 7
- Skill definitions;
- PlayerSkill `baseMastery` and progress fields;
- Skill legacy mappings/evidence;
- legacy `character_skills` bytes;
- effective Skill remains derived only.

After Phase-8 migration, all of the above must remain semantically identical except for explicitly authorized additive schema changes needed to extend generic modifier target constraints.

Any migration that changes ActivePlayerRef, stat/resource authority, Skill baseMastery, Talent/Potential, or unrelated modifiers is a release blocker.

---

## 11. Required legacy fixtures

At minimum final validation must cover:

1. normal legacy Technique with definition/mastery/xp/history;
2. legacy-only Technique with no typed row;
3. orphan `technique_uid` without definition;
4. mixed legacy + typed same logical Technique without mapping;
5. mixed legacy + typed with explicit mapping;
6. mapping target missing;
7. mapping owner changed;
8. mapping version mismatch;
9. two players with same Technique UID remain isolated;
10. same character UID string in two campaigns remains isolated;
11. same display name under different World Packs remains separate;
12. mastery = 0;
13. very large finite mastery where definition has no declared max;
14. negative/non-finite/corrupt legacy mastery fixture where SQLite permits it;
15. XP = 0 and very large XP;
16. learned chapter retained;
17. usage/success/failure counters retained;
18. equipped state retained/classified without being treated as ownership;
19. notes retained as opaque data;
20. legacy `base_chakra_cost` present without generic resource mapping;
21. legacy player `chakra_cost_override` present without mapping;
22. explicit generic ResourceDefinition cost mapping;
23. wrong/deleted resource target;
24. canon index same-name entry with no explicit mapping;
25. 1000+ legacy Technique rows/player Technique values;
26. Technique/Skill same-looking labels remain semantically separate.

Malformed historical data may fail loudly/quarantine, but source data must not be silently deleted.

---

## 12. Concrete validation matrix after WORK-031

### Typed definitions / ownership
- V8-01 register TechniqueDefinition;
- V8-02 incompatible duplicate UID fails loudly;
- V8-03 same label/different UID remains distinct;
- V8-04 World Pack ownership hijack fails;
- V8-05 deprecated definition does not erase existing ownership/history.

### Player Technique persistence
- V8-10 learn/save PlayerTechnique;
- V8-11 reopen equality;
- V8-12 campaign isolation;
- V8-13 player isolation;
- V8-14 baseMastery persistence;
- V8-15 XP preserved under declared opaque/typed semantics;
- V8-16 learned chapter preserved;
- V8-17 usage/success/failure data preserved/classified;
- V8-18 equipped state preserved/classified;
- V8-19 notes preserved as opaque data;
- V8-20 no authoritative truncation at 60 or other hidden limit.

### Legacy reconciliation
- V8-30 legacy-only Technique visible through typed compatibility read;
- V8-31 orphan UID survives;
- V8-32 mixed legacy+typed without mapping fails loudly/unresolved;
- V8-33 explicit mapping yields exactly one canonical Technique;
- V8-34 legacy bytes unchanged after mapping;
- V8-35 unrelated unmapped legacy Technique remains visible;
- V8-36 no name/category/canon-index automatic merge;
- V8-37 owner/version mismatch fails deterministically;
- V8-38 repeated migration/reopen preserves reconciliation.

### Skill / mastery boundary
- V8-40 stable-UID Skill requirement works;
- V8-41 missing Skill requirement fails deterministically;
- V8-42 requirement clearly uses declared base/effective semantics;
- V8-43 Skill mastery != Technique mastery;
- V8-44 Skill update does not rewrite Technique baseMastery;
- V8-45 Talent/Potential update does not rewrite Technique baseMastery.

### Effective Technique / modifiers
- V8-50 temporary modifier changes derived Technique mastery only;
- V8-51 modifier removal restores effective result, base unchanged;
- V8-52 insertion order does not change derived result;
- V8-53 campaign/player/target-kind isolation;
- V8-54 resolver does not write PlayerTechnique.

### Generic resource costs
- V8-60 generic cost references `ResourceDefinition.resourceUid`;
- V8-61 no new Core chakra literal in generic Technique contract;
- V8-62 legacy chakra cost stays unresolved without explicit mapping;
- V8-63 explicit mapping creates one canonical generic cost binding;
- V8-64 deleted/wrong-owner resource target fails loudly;
- V8-65 resolution does not mutate PlayerResource.currentValue.

### Canon index boundary
- V8-70 same name in `canon_technique_index` does not auto-create/link TechniqueDefinition;
- V8-71 explicit stable mapping is required where linking is desired.

### Schema / migration / integrity
- V8-80 old V7 DB opens through production current-schema path;
- V8-81 Phase-8 marker/object creation is idempotent;
- V8-82 bootstrap reaches V8;
- V8-83 restore reaches V8;
- V8-84 campaign switch reaches V8;
- V8-85 Phase 3–7 semantic snapshots unchanged;
- V8-86 1000+ Techniques no authoritative truncation;
- V8-87 `PRAGMA integrity_check` = `ok`;
- V8-88 `PRAGMA foreign_key_check` returns no rows under adopted FK policy;
- V8-89 exact candidate CI succeeds.

---

## 13. Release blockers for CHAT-3 final validation

`PHASE 8 INTEGRITY VALIDATION: FAIL` if any reproducible case shows:

1. populated `character_techniques` but empty typed/compat read;
2. orphan Technique disappears;
3. two authoritative mastery values for one mapped logical Technique;
4. name/category/canon-index heuristic merge;
5. legacy Technique bytes are destructively altered/deleted;
6. XP or history is discarded/reinterpreted without proven semantics;
7. `base_chakra_cost`/override is auto-mapped to a generic resource by name guess;
8. Skill mastery is copied into Technique mastery;
9. Skill/Talent/Potential writes directly rewrite Technique mastery;
10. temporary modifier rewrites persistent Technique mastery;
11. production current-schema path does not reach V8;
12. bootstrap/restore/campaign switch can leave old schema;
13. authoritative read truncates Techniques;
14. Phase 3–7 authority regresses;
15. migration is not idempotent;
16. integrity/FK checks fail;
17. exact candidate CI fails.

---

## 14. Current checkpoint before WORK-031

Fresh master confirmed at plan creation:

```text
0653c6c6fe03da3db98623112f7a0af4c3f88464
```

Current master CI run #160 is SUCCESS.

Current evidence is consistent with WORK-029:

- Technique is still legacy/direct-read state;
- ContextBuilder has `LIMIT 60` only on presentation/context path;
- CharacterPanel still uses Naruto-specific chakra-cost columns;
- `canon_technique_index` remains a separate reference/browser source;
- backend currently treats player Techniques as learned authority at a coarse level.

No Phase-8 runtime candidate existed at this checkpoint. Therefore this report is a validation plan, not a PASS/FAIL verdict on WORK-031.

---

## 15. Final procedure after WORK-031 appears

CHAT-3 must validate the exact `resultCommit` rather than the implementer's report:

1. re-check current master and exact candidate SHA;
2. inspect full diff from accepted Phase-7 runtime/current baseline to WORK-031;
3. inspect actual Phase-8 schema/migration and `CurrentSchema.ensure()` chain;
4. run/review the concrete V8 matrix above;
5. verify legacy bytes and Phase 3–7 snapshots;
6. verify 1000+ Technique behavior, integrity/FK;
7. verify exact candidate JVM/build evidence and GitHub Actions;
8. update this document with reproduced evidence and exact final verdict.

Final verdict must be exactly one of:

```text
PHASE 8 INTEGRITY VALIDATION: PASS
```

or

```text
PHASE 8 INTEGRITY VALIDATION: FAIL
```

No global Phase-8 COMPLETE decision belongs to CHAT-3.
