# WORK-20260810-033 — Phase 8 Final Migration / Legacy Integrity Validation

Status: FINAL READ-ONLY RUNTIME VALIDATION

Work ID: `WORK-20260810-033`
Role: CHAT-3 / READ-ONLY DOMAIN AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Validated candidate: `7a28e6e4b28ff10cbb516b94e5e0c120d0a15397`
Accepted Phase 7 runtime: `8075487d24bf0b3da1bcbd8f9fb2483e9154ec6c`
Instruction baseline: `0653c6c6fe03da3db98623112f7a0af4c3f88464`
Exact release workflow: CI `#177`, run `31344101852`

## Final evidence

The validation was performed against the exact final candidate only. At validation time `master` pointed exactly to `7a28e6e4b28ff10cbb516b94e5e0c120d0a15397`.

### Legacy reconciliation and persistence — PASS

Runtime/tests prove that populated legacy `character_techniques` remains visible through reconciliation; orphan Technique UIDs remain explicit unresolved legacy evidence; XP is retained as raw/opaque legacy progress rather than assigned invented gameplay semantics; learned chapter, last-used chapter, usage/success/failure history, equipped state and notes are preserved. Explicit legacy mapping produces one canonical Technique while unrelated orphan legacy state remains unresolved. Mixed typed+legacy authority without an explicit mapping fails loudly. Mapping/reconciliation tests compare the legacy dump before and after and require unchanged legacy bytes.

No name-based canonicalization is accepted: the canon-index same-name fixture does not create or link a typed TechniqueDefinition.

### Resource costs — PASS

Typed Technique costs bind to generic `ResourceDefinition.resourceUid`. Legacy `base_chakra_cost` and `chakra_cost_override` remain raw legacy evidence. A generic resource-cost mapping cannot be registered before explicit Technique identity mapping; after explicit mapping, the resource UID is bound while the original raw chakra-cost values remain preserved. No Core name heuristic is used to reinterpret chakra as a universal resource.

### Skill requirements / mastery separation — PASS

Technique Skill requirements encode explicit `ACQUISITION`, `EXECUTION`, or `BOTH` phase and explicit `BASE`/`EFFECTIVE` mastery basis. Fingerprint coverage distinguishes requirement phase while remaining order deterministic. Acquisition using BASE remains satisfied when an injury lowers effective Skill; execution using EFFECTIVE correctly fails in that fixture. Updating Skill mastery does not rewrite Technique mastery. Talent/Potential changes likewise do not rewrite Technique mastery.

### Effective Technique derivation — PASS

`TECHNIQUE_EFFECTIVE` extends the existing Phase-5 `DerivedValueResolver`; no second Technique resolver is introduced. Modifier ordering is deterministic. Temporary/injury effects alter effective Technique mastery only; persisted `PlayerTechnique.baseMastery` remains unchanged and removing modifiers restores the base-derived result. Campaign/player/target-kind isolation is fail-loud.

### Authoritative reads / scale — PASS

The authoritative `TechniqueStore.playerTechniques()` / reconciliation path has no presentation `LIMIT 60`. The dedicated ContextBuilder regression fixture creates 1001 typed Techniques plus one unresolved legacy orphan and requires all 1002 entries in context, including `T1000`. Persistence coverage separately exercises 1005 typed Techniques. No authoritative truncation was reproduced.

### Schema routing / migration — PASS

`CurrentSchema.ensure()` delegates to `MigrationManager.ensureV8()`. `LocalGameStore` routes production bootstrap, restore and campaign switch through `ensureCurrentSchema()`, which calls `CurrentSchema.ensure()` with the active campaign ID. Repeated current-schema ensure is covered for Phase-8 migration-marker idempotency and preservation of Phase-7 Skill/modifier state.

The migration is additive with the required controlled modifier target extension to `TECHNIQUE_EFFECTIVE`; the Phase-7 regression fixture verifies pre-existing Skill mastery and modifier rows survive. Existing Phase 3–7 authority remains on the established stores/resolver rather than being replaced by a parallel Technique authority.

### SQLite integrity — PASS

The bulk persistence test runs `PRAGMA integrity_check` and requires `ok`; it also runs `PRAGMA foreign_key_check` and requires no rows. The same test covers 1000+ Technique persistence without truncation.

### Exact CI — PASS

GitHub Actions workflow `Build & Release RPG OS ALPHA` run number `177`, run ID `31344101852`, executed for head SHA `7a28e6e4b28ff10cbb516b94e5e0c120d0a15397` and completed with `conclusion: success`.

## Gate summary

- legacy `character_techniques` visible losslessly: PASS
- orphan Technique UID preserved: PASS
- XP preserved without invented semantics: PASS
- learned/last-used/history/equipped/notes preserved: PASS
- mixed legacy+typed ambiguity fail-loud: PASS
- explicit mapping -> exactly one canonical Technique: PASS
- legacy bytes unchanged: PASS
- no name-based canon-index merge: PASS
- generic Resource UID costs: PASS
- legacy chakra costs unresolved without explicit mapping: PASS
- Skill requirements ACQUISITION/EXECUTION/BOTH: PASS
- BASE/EFFECTIVE requirement semantics: PASS
- Technique mastery != Skill mastery: PASS
- `TECHNIQUE_EFFECTIVE` no-retrogression: PASS
- no second resolver: PASS
- authoritative read without `LIMIT 60`: PASS
- 1000+ Techniques no truncation: PASS
- `CurrentSchema.ensure()` reaches V8: PASS
- bootstrap/restore/campaign switch latest schema: PASS
- Phase 3–7 no-regression: PASS
- migration idempotency: PASS
- `integrity_check`: PASS
- `foreign_key_check`: PASS
- exact CI #177: SUCCESS

## Final verdict

`PHASE 8 INTEGRITY VALIDATION: PASS`
