# WORK-20260810-030 — Phase 7 Adversarial / No-Retrogression Matrix

Status: READ-ONLY RUNTIME / FINAL ADVERSARIAL VALIDATION

Work ID: `WORK-20260810-030`
Owner: `CHAT-5`
Role: PHASE 7 ADVERSARIAL / NO-RETROGRESSION AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Audited master at matrix creation: `b08e8861fa3d3095584f7cd1bf8cdf827b3cd373`
Accepted Phase 6 runtime: `52af00e441131cc8e7beb4a8036e43d250f35848`
Phase 7 implementation work item: `WORK-20260810-026`
Final audited Phase 7 candidate: `8075487d24bf0b3da1bcbd8f9fb2483e9154ec6c`
Latest master observed before final write: `fe5eea46c6cb483c7e56f931341d0b74530faa7d` (read-only Phase 7 integrity report on top of the audited runtime)
Exact runtime CI: GitHub Actions run #158 = SUCCESS.

This report is read-only. It does not implement Skill runtime, schema, migrations, Technique, ProgressionEngine, PlayerDomainEngine or CharacterPanelSnapshot v2.

Canonical reference: `docs/audits/WORK-20260810-023_PHASE7_SKILL_ARCHITECTURE.md`.

## 1. Release invariants under attack

Phase 7 must preserve all of the following:

1. `Skill != Talent != Potential != Technique != Stat`.
2. `PlayerSkill.baseMastery` is authoritative persistent learned competence.
3. `effectiveMastery` is derived and rebuildable; temporary conditions must never overwrite `baseMastery`.
4. Existing legacy `character_skills` mastery must remain losslessly visible through the typed Skill contract.
5. Legacy + typed representations of the same logical Skill cannot become two unresolved authoritative mastery inputs.
6. Stable Skill UID has precedence over label/display name.
7. World Pack ownership, campaign scope, player scope and Skill identity must be isolated.
8. Talent/Potential may be future progression inputs but cannot directly write Skill mastery in Phase 7.
9. Skill persistence cannot create or rewrite Technique state.
10. Phase-5 effective-mastery support must extend the existing generic modifier/resolver foundation rather than create a second Skill-specific engine.
11. Phase-7 migration must be reachable from the production current-schema entrypoint.
12. Authoritative typed reads must not silently truncate large Skill sets.

## 2. Existing attack surface

The accepted pre-Phase-7 architecture contained legacy `character_skills` with `entity_uid`, `skill_uid`, `mastery`, `xp`, `updated_chapter`, while presentation/context paths read legacy Skill state directly. WORK-026 correctly treats implementation as typed integration plus reconciliation rather than destructive replacement.

The final candidate introduces:

- `SkillDefinition` and `PlayerSkill`;
- explicit `LegacySkillMapping` plus unresolved legacy preservation;
- generic `SKILL_EFFECTIVE` target in the existing Phase-5 modifier/resolver foundation;
- additive Phase-7 schema with `skill_definitions_v2`, `skill_definition_domains`, `player_skills_v2`, `legacy_skill_mappings`;
- `CurrentSchema.ensure() -> ensureV7()` as the current production schema path;
- ContextBuilder Skill retrieval through `SkillStore.reconciled()` instead of the previous authoritative-looking legacy `LIMIT 50` path.

## 3. Mastery numeric attacks

| ID | Case | Final evidence/result |
|---|---|---|
| M-01 | `baseMastery = 0` | PASS — non-negative finite values are legal unless a definition minimum narrows the range. |
| M-02 | negative mastery | PASS — `SkillPolicy.validatePlayerSkill()` rejects values `< 0`. |
| M-03 | NaN | PASS — rejected by `isFinite()`. |
| M-04 | +Infinity / -Infinity | PASS — rejected by `isFinite()`. |
| M-05 | extremely large finite mastery | PASS — allowed when no definition max exists; tested with `1.0e200`; no hidden global cap. |
| M-06 | mastery above declared maximum | PASS — `SkillStore.savePlayerSkill()` checks definition min/max and rejects overflow of the declared scale. |
| M-07 | `-0.0` | NON-BLOCKING / deterministic JVM-SQL numeric behavior; no semantic branch depends on sign-zero. No current contract violation reproduced. |
| M-08 | duplicate PlayerSkill logical identity | PASS — DB PK `(campaign_id,character_uid,skill_uid)` plus explicit upsert gives one authoritative typed row. |
| M-09 | missing SkillDefinition | PASS — typed save fails loudly; orphan legacy stays unresolved rather than receiving a guessed definition. |
| M-10 | deprecated definition | PASS BY CODE — new learning is rejected when definition is deprecated; existing typed rows are not silently deleted. Dedicated regression test would be useful but absence is not a reproduced contract failure. |

## 4. No-retrogression modifier attacks

Canonical fixture implemented by tests:

```text
baseMastery = 80
injury = -30
equipment = +5
temporary buff = +10
effectiveMastery = 65
remove all temporary/context modifiers
effectiveMastery = 80
baseMastery = 80 throughout
```

Final result:

- injury does not write lower `baseMastery`;
- equipment removal does not lower persistent mastery;
- temporary buff expiration/removal does not persist gain;
- resolver output is a `ResolvedSkill` projection;
- `DerivedValueResolver` never writes `PlayerSkill` persistence;
- removing modifiers reconstructs base mastery exactly;
- campaign/player scope is checked before resolution.

`NO-RETROGRESSION: PASS`.

## 5. Phase-5 extension attacks

WORK-026 adds `ModifierTargetKind.SKILL_EFFECTIVE` to the existing generic Phase-5 model. It does not introduce `SkillModifierEngine`, `TalentModifierEngine`, `PotentialModifierEngine` or a second resolver.

The existing resolver semantics are retained:

- modifier lifecycle order is explicit;
- within operation stages ordering is deterministic by `(priority, modifierUid)`;
- active/source-active/time-window filtering is reused;
- finite guards are reused;
- duplicate modifier UIDs are rejected by resolver input uniqueness;
- target kind controls which definition namespace is legal;
- `SKILL_EFFECTIVE` requires both a SkillDefinition and learned PlayerSkill.

The provided test reverses modifier list order and obtains the same fingerprint/result/contribution order (`a`, `b`).

`SKILL_EFFECTIVE DETERMINISM: PASS`.

## 6. Talent/Potential attacks

The Phase-7 Skill persistence API has no path that reads Talent/Potential and writes mastery automatically.

A concrete regression test updates Talent and Potential after saving an 80-mastery Skill and verifies:

- Skill mastery remains 80;
- stat base remains 100;
- legacy Technique mastery remains 33.

There is no automatic Skill creation from high Talent/Potential and no Potential-as-direct-mastery-cap implementation in Phase 7.

`TALENT/POTENTIAL BOUNDARY: PASS`.

## 7. Legacy reconciliation attacks

Final behavior is conservative and lossless:

- legacy-only Skills remain in `unresolvedLegacy` until explicit mapping exists;
- orphan legacy UIDs survive;
- legacy XP and updated chapter are preserved as raw compatibility evidence;
- explicit `LegacySkillMapping` is required to produce canonical mapped authority;
- mapping target must exist;
- mapping target World Pack owner must match;
- supersession requires an existing typed PlayerSkill;
- when mapped legacy and typed authority would coexist without explicit supersession, reconciliation fails loudly;
- legacy bytes are not deleted by Phase 7;
- no global `same key == same concept` merge exists;
- same label in different World Packs can coexist under distinct stable UIDs.

Important nuance: unmapped legacy and typed states with no explicit identity relation are not guessed to be the same concept. The legacy side remains explicit unresolved compatibility state, so the system does not create two canonical authoritative mastery values.

`LEGACY RECONCILIATION: PASS`.

## 8. XP/progress attacks

Phase 7 does not reinterpret legacy XP as mastery, Talent or Potential.

Typed `progressValue` is only accepted together with explicit `progressSemanticsUid`. Legacy XP remains `legacyXpRaw` during read-through unless a future explicit semantic migration is defined.

`XP SEMANTIC SAFETY: PASS`.

## 9. Skill/Technique boundary attacks

Skill and Technique remain separate storage and semantic paths.

A regression test creates a legacy Technique row, performs Skill writes and Talent/Potential changes, then verifies Technique mastery is unchanged. `SkillStore` has no Technique creation/write path, and Phase 7 does not convert Technique rows into Skills.

`SKILL != TECHNIQUE: PASS`.

## 10. Definition identity / World Pack attacks

Validated by code/tests:

- duplicate exact Skill UID rejected;
- duplicate key within the same World Pack rejected;
- same display label in another World Pack allowed with distinct stable UID;
- caller World Pack must equal definition owner;
- progression-domain binding target must exist and be owned by the same World Pack;
- definition version must be >= 1;
- provenance must be non-empty;
- deprecated identity remains represented explicitly instead of being deleted from the model.

`WORLD PACK / UID ISOLATION: PASS`.

## 11. Campaign/player/domain isolation

Typed PlayerSkill identity is `(campaign_id, character_uid, skill_uid)`. Store reads always filter both campaign and character. Resolver validates every PlayerSkill and Modifier against request campaign and character.

Tests verify another campaign and another player cannot read the saved row, and a modifier copied to another player is rejected.

No first-row/global Skill fallback was found.

`CAMPAIGN/PLAYER/DOMAIN ISOLATION: PASS`.

## 12. Migration / production entrypoint attacks

`CurrentSchema.ensure(saveDb,campaignId)` calls `MigrationManager().ensureV7(...)`, while `ensureV7()` starts by calling `ensureV6()`. `LocalGameStore.ensureCurrentSchema()` delegates to `CurrentSchema.ensure()`.

Normal runtime paths using the production helper include bootstrap/buildContext/player-state reads, restore (`restoreBackup()`), and campaign switch (`setActiveCampaign()`).

Phase-7 migration:

- is transactional;
- preserves prior modifiers while rebuilding the modifiers table to extend the target-kind CHECK;
- adds the Phase-7 marker with `INSERT OR IGNORE`;
- leaves legacy Skill tables/bytes untouched;
- is explicitly tested for repeated `ensureV7()`;
- preserves existing stat and modifier state in the migration fixture.

A dedicated current-schema entrypoint test confirms the Phase-7 marker and `player_skills_v2` table appear through `CurrentSchema.ensure()`.

`PRODUCTION CURRENT-SCHEMA GATE: PASS`.

## 13. Scale / truncation attacks

`SkillStore.playerSkills()` has no LIMIT and iterates the full result set. The scale test registers and saves 1005 definitions/PlayerSkills and asserts all 1005 are returned, followed by clean `PRAGMA integrity_check` and `PRAGMA foreign_key_check`.

ContextBuilder no longer reads authoritative player Skills using the old `LIMIT 50` SQL. It calls `SkillStore.reconciled(playerUid)` and emits canonical plus unresolved compatibility rows.

`NO SILENT AUTHORITATIVE TRUNCATION: PASS`.

## 14. Determinism attacks

For `SKILL_EFFECTIVE`, the resolver:

- validates unique modifier UIDs;
- filters lifecycle state deterministically;
- applies lifecycle stages in enum order;
- applies operations in explicit fixed order;
- sorts each operation stage by `(priority, modifierUid)`;
- fingerprints sorted Skill definitions/PlayerSkills/modifiers;
- reuses the existing dependency cycle guard for `SKILL_EFFECTIVE` dependencies.

Reversed input-list ordering is covered by test and produces identical fingerprint/result ordering.

`DETERMINISM: PASS`.

## 15. CI evidence

Exact candidate SHA:

`8075487d24bf0b3da1bcbd8f9fb2483e9154ec6c`

GitHub Actions:

- workflow: `Build & Release RPG OS ALPHA`
- run: `#158`
- head SHA: exact candidate above
- status: completed
- conclusion: `success`

This is the required exact-SHA CI evidence.

## 16. Non-blocking hardening debt

No reproducing release blocker was found. Useful future hardening tests include:

- explicit negative-zero persistence/reopen assertion;
- direct deprecated-definition existing-skill update/reopen test;
- explicit alias mapping-version policy beyond the current `>=1` invariant if semantic upgrade rules become necessary;
- large legacy-only (>1000) unresolved compatibility fixture in addition to the existing >1000 typed fixture;
- explicit source-lifecycle tests for each `SKILL_EFFECTIVE` equipment/injury/temp expiration boundary, although the shared Phase-5 lifecycle engine is already validated.

These are defensive coverage improvements, not current contract failures.

## 17. Final verdict

No reproducible violation of the current Phase-7 contract was found on `8075487d24bf0b3da1bcbd8f9fb2483e9154ec6c`.

`PHASE 7 ADVERSARIAL VALIDATION: PASS`

This verdict does not mark Phase 7 globally COMPLETE and does not authorize or implement Phase 8.
