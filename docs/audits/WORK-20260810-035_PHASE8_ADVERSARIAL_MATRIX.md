# WORK-20260810-035 — Phase 8 Adversarial Matrix

Status: FINAL READ-ONLY ADVERSARIAL VALIDATION

Work ID: `WORK-20260810-035`
Owner: `CHAT-5`
Role: PHASE 8 ADVERSARIAL AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Matrix baseline: `0653c6c6fe03da3db98623112f7a0af4c3f88464`
Final audited Phase 8 candidate: `7a28e6e4b28ff10cbb516b94e5e0c120d0a15397`
Fresh master before final report write: `540e6693644d50194b2f0a7df73f696d8d61c941` (read-only CHAT-3 Phase 8 integrity report on top of the audited runtime)
Exact CI evidence: GitHub Actions run #177, run id `31344101852`, head SHA `7a28e6e4b28ff10cbb516b94e5e0c120d0a15397`, conclusion `success`.

This document is read-only. No runtime implementation was changed by CHAT-5.

Canonical architecture input: `docs/audits/WORK-20260810-029_PHASE8_TECHNIQUE_ARCHITECTURE.md`.

## 1. Final executive verdict

`PHASE 8 ADVERSARIAL VALIDATION: PASS`

No reproducible violation of the current Phase-8 contract was found on exact runtime candidate `7a28e6e4b28ff10cbb516b94e5e0c120d0a15397`.

The accepted implementation keeps Technique separate from Skill/Talent/Potential/Stat/Resource, introduces stable World-Pack-owned Technique definitions and campaign/character-scoped PlayerTechnique rows, preserves legacy Technique state through explicit reconciliation, extends the existing Phase-5 generic resolver with `TECHNIQUE_EFFECTIVE`, keeps base Technique mastery persistent, and routes production schema through V8.

## 2. Technique identity / definition attacks

PASS.

Evidence from `TechniqueModel.kt` / `TechniqueStore.kt`:

- `TechniqueDefinition` has stable `techniqueUid`, `worldPackUid`, key/display/category metadata, definition version, provenance and ACTIVE/DEPRECATED status.
- registration rejects duplicate UID in a request, duplicate `(worldPackUid,key)`, duplicate already-persisted UID, and owner mismatch.
- same display name with different stable UID is legal and tested.
- missing Skill requirement and missing ResourceDefinition cost target are rejected before definition persistence.
- a deprecated Technique cannot be newly learned, while an already persisted PlayerTechnique is not automatically erased.
- `canon_technique_index` is not consulted by Technique definition registration and same-name canon index data does not auto-create or auto-link a TechniqueDefinition.

No name-based identity merge was found.

## 3. Mastery numeric attacks

PASS for the contractually required semantics.

`TechniquePolicy.validatePlayerTechnique()` rejects:

- negative `baseMastery`,
- NaN,
- +Infinity / -Infinity through `isFinite()`,
- invalid negative progress/history counters,
- invalid versions/provenance.

`TechniqueStore.savePlayerTechnique()` additionally enforces declared definition `minMastery/maxMastery` bounds.

The test suite explicitly covers negative, NaN, +Infinity, over-range mastery and an extremely large finite (`1.0e200`) mastery where the definition has no maximum. No arbitrary global maximum is introduced.

`-0.0` is not separately asserted in Phase-8 tests, but it is finite and numerically equal to zero; no current path turns it into a distinct identity or persistence authority. This is non-blocking hardening debt, not a reproduced contract break.

## 4. No-retrogression / TECHNIQUE_EFFECTIVE

PASS.

Phase 8 does not introduce a Technique-specific modifier engine. It extends the existing `ModifierTargetKind`/`DerivedValueResolver` with `TECHNIQUE_EFFECTIVE`.

`DerivedValueResolver`:

- validates campaign/player scope,
- requires the TechniqueDefinition and learned PlayerTechnique to exist,
- rejects wrong target kind / missing target,
- uses the same generic modifier lifecycle and deterministic sorting (`lifecycle`, operation stage, priority, `modifierUid`),
- returns `ResolvedTechnique` containing base/pre-bound/effective mastery,
- never writes the resolved value back to `PlayerTechnique.baseMastery`.

Tests prove reversed modifier list order gives the same effective value and contribution order, temporary/injury/equipment-like derived effects leave `baseMastery` unchanged, and removing all modifiers reconstructs base mastery exactly.

No retrogression path was found.

## 5. Skill requirement attacks

PASS.

Technique Skill requirements use stable `skillUid`, explicit `TechniqueRequirementPhase` (ACQUISITION/EXECUTION/BOTH), explicit mastery basis (BASE/EFFECTIVE), threshold, version and provenance.

Registration fails when a required SkillDefinition is missing.

The runtime requirement check distinguishes base from effective Skill mastery. The test fixture demonstrates that an injury penalty can make an EXECUTION requirement fail while the ACQUISITION requirement based on base mastery remains satisfied.

Updating Skill mastery does not copy that value into Technique mastery; the persisted Technique base mastery remains independent.

No Skill->Technique mastery copy or automatic Technique grant was found.

## 6. Talent / Potential boundary

PASS.

Phase-6 Talent/Potential profile updates and Skill updates are exercised while an existing PlayerTechnique is present; Technique mastery remains unchanged.

There is no direct API/path from TalentEntry or PotentialEntry to TechniqueStore.savePlayerTechnique(), and Phase 8 does not implement ProgressionEngine or automatic Technique acquisition.

No direct Talent/Potential Technique write was found.

## 7. Campaign / player / World Pack isolation

PASS.

PlayerTechnique logical identity is `(campaign_id, character_uid, technique_uid)`. `TechniqueStore` requires its campaign ID and all PlayerTechnique saves must match that campaign. Reads filter by campaign + character.

Definition registration validates World Pack ownership. Legacy identity mappings validate mapping owner against the target TechniqueDefinition owner. Resource-cost compatibility mapping also requires the existing Technique identity mapping and matching World Pack owner.

Tests cover campaign and player isolation; resolver rejects modifier inputs belonging to another player/campaign.

No cross-campaign/player/World-Pack leakage was reproduced.

## 8. Legacy + typed reconciliation / orphan preservation

PASS.

The legacy read path keeps raw legacy Technique rows, including orphan UIDs, as `LegacyTechniqueRecord` / unresolved compatibility state.

Without explicit mapping:

- legacy-only rows remain visible as unresolved;
- a legacy and typed row with the same UID causes a deterministic fail-loud condition rather than silent precedence.

With explicit mapping:

- mapped legacy mastery can be projected to one canonical Technique identity;
- if a typed PlayerTechnique is the authority, `supersededByTyped` must be explicit and the typed row must actually exist;
- legacy bytes are left untouched.

Tests preserve orphan Technique rows and verify before/after legacy table contents.

No global same-name/same-key equivalence rule was found.

## 9. XP / history / equipped / notes preservation

PASS.

Typed PlayerTechnique persists explicit progress only when paired with a `progressSemanticsUid`. Legacy XP is not automatically reinterpreted; mapped legacy projection carries `legacyXpRaw` separately.

Legacy reconciliation preserves:

- learned chapter,
- last-used chapter,
- usage count,
- success count,
- failure count,
- equipped flag,
- notes,
- raw XP,
- raw chakra cost override / base chakra cost.

The implementation does not derive mastery from legacy XP, usage counters, success rate or equipped state.

## 10. Canon-index collision attacks

PASS.

A dedicated test creates `canon_technique_index` with a Technique name matching a newly registered TechniqueDefinition. The registry remains empty before explicit definition registration and the resulting Technique identity is the explicitly supplied stable UID, not the canon-index entry UID/name.

No automatic canon-index link or PlayerTechnique creation exists.

## 11. Generic resource cost / legacy chakra attacks

PASS.

New Technique Core cost contract uses `TechniqueResourceCost(resourceUid, amount, version, provenance)` and validates that the referenced ResourceDefinition exists.

Legacy `base_chakra_cost` and `chakra_cost_override` remain opaque/raw compatibility data. They do not become generic resource costs merely from their names.

A legacy resource-cost mapping is only legal after an explicit legacy Technique identity mapping; its ResourceDefinition target must exist and its World Pack owner must match the Technique identity mapping.

The implementation preserves both raw legacy chakra cost values even after a resource identity mapping exists.

No `chakra` branch was introduced into the generic TechniqueDefinition/TechniqueResourceCost model.

Wrong/missing ResourceDefinition target fails at registration/mapping time. There is no current public deletion workflow that produces a reproducible silent dangling cost target through normal Phase-8 APIs; future deletion semantics remain a domain hardening responsibility.

## 12. No automatic resource spending

PASS.

Technique resource cost handling is definition/read/reconciliation logic only. No Phase-8 TechniqueStore or DerivedValueResolver path subtracts from or rewrites `PlayerResource.currentValue`.

Calculating/reading a cost does not perform spending, regeneration or hidden clamping. Actual spending remains a future legal mechanics/domain mutation responsibility.

## 13. Scale / no LIMIT 60 authoritative truncation

PASS.

`TechniqueStore.playerTechniques(characterUid)` has no authoritative LIMIT and orders the full set by Technique UID.

The Phase-8 test suite inserts and reads 1005 Technique definitions/player Techniques and asserts the full count while also running SQLite integrity/FK checks.

`ContextBuilder` now obtains Techniques from `TechniqueStore.reconciled(playerUid)` and exposes canonical + unresolved entries. It no longer performs the old direct `character_techniques ... LIMIT 60` authoritative-looking query.

No silent authoritative truncation was found.

## 14. Production V8 migration

PASS.

`CurrentSchema.ensure()` now calls `MigrationManager().ensureV8(...)`.

`ensureV8()` begins by calling `ensureV7()`, preserving the ordered previous migration chain, then adds Phase-8 tables and the backward-compatible `TECHNIQUE_EFFECTIVE` modifier target extension.

The migration is additive with respect to Technique authority: legacy `character_techniques`, legacy `technique_definitions`, and canon reference data are not rewritten/deleted.

The migration test calls `CurrentSchema.ensure()` twice and confirms a single V8 migration marker, preserved Phase-7 Skill state and existing modifier state.

Normal `LocalGameStore` bootstrap/restore/campaign-switch paths already route through `ensureCurrentSchema()` / `CurrentSchema.ensure()`, so they reach V8.

## 15. Phase 3–7 regression checks

PASS for observed/current gates.

The Phase-8 migration chains through prior migrations instead of replacing them. The regression test explicitly confirms existing Phase-7 Skill mastery and Phase-5 modifier row preservation.

The runtime diff does not change ActivePlayerRef/PlayerState contracts, Phase-4 stat/resource reconciliation, Phase-6 Talent/Potential semantics, or Skill persistence semantics beyond the intended generic modifier enum extension.

The current master immediately before this report was `540e6693644d50194b2f0a7df73f696d8d61c941`, whose only commit on top of the audited candidate is CHAT-3's read-only Phase-8 integrity validation report. Thus the audited runtime remains exactly `7a28e6e4b28ff10cbb516b94e5e0c120d0a15397`.

## 16. CI gate

PASS.

Exact SHA: `7a28e6e4b28ff10cbb516b94e5e0c120d0a15397`

GitHub Actions:

- workflow: `Build & Release RPG OS ALPHA`
- run number: `177`
- run id: `31344101852`
- head SHA: exact audited SHA
- status: completed
- conclusion: success

Therefore the exact runtime candidate requested by the coordinator has green CI evidence.

## 17. Non-blocking hardening debt

No item below reproduces a current Phase-8 contract violation:

- add an explicit `-0.0` mastery canonicalization assertion;
- add a dedicated persisted-deprecated-Technique reopen test;
- add a dedicated resource target deletion scenario once resource-definition deletion/supersession becomes a supported domain operation;
- add very-large legacy-only Technique fixtures beyond the existing 1005 typed scale test;
- future version/supersession policy can become stricter when content-update mutation APIs are introduced.

These do not justify a FAIL for the current Phase-8 contract.

## 18. Final verdict

`PHASE 8 ADVERSARIAL VALIDATION: PASS`

CHAT-5 found no reproducible adversarial blocker in final Phase-8 candidate `7a28e6e4b28ff10cbb516b94e5e0c120d0a15397`.

This report does not mark global Phase 8 COMPLETE; that decision remains with the coordinator.
