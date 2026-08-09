# WORK-20260810-028 — Phase 7 Migration / Legacy Integrity Validation

Status: FINAL / READ-ONLY RUNTIME VALIDATION

Work ID: `WORK-20260810-028`
Owner: `CHAT-3`
Role: PHASE 7 MIGRATION / LEGACY INTEGRITY AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Accepted Phase 6 runtime: `52af00e441131cc8e7beb4a8036e43d250f35848`
Validated Phase 7 candidate: `8075487d24bf0b3da1bcbd8f9fb2483e9154ec6c`
Earlier implementation commit `49b0b28e...` is not the final validation target.

No runtime fixes were implemented by CHAT-3. This file is the only write in final validation scope.

## Final integrity matrix

| Gate | Result | Evidence / conclusion |
|---|---|---|
| 1. legacy `character_skills` -> typed Skill read equality | PASS | `SkillStore.reconciled()` performs explicit legacy read-through. Mapped legacy mastery becomes canonical `PlayerSkill.baseMastery`; legacy rows remain untouched. `ContextBuilder` on final candidate now consumes `SkillStore.reconciled()` rather than raw legacy top-50 SQL. |
| 2. orphan skill UID survives | PASS | Unmapped/orphan legacy records remain in `unresolvedLegacy`; test fixture proves orphan survives alongside a mapped legacy Skill. |
| 3. mixed legacy+typed without mapping -> fail-loud | PASS | `savePlayerSkill()` and `reconciled()` reject same-UID mixed authority without explicit mapping. Covered by `mixedLegacyTypedSameUidWithoutMappingFailsLoud`. |
| 4. explicit mapping -> exactly one canonical Skill | PASS | `LegacySkillMapping` resolves legacy evidence to one canonical UID. Non-superseded mapping requires no typed duplicate; supersession requires typed authority. Test proves one canonical result. |
| 5. legacy XP preserved losslessly without invented semantics | PASS | Legacy XP is read as raw text/evidence (`legacyXpRaw` / `xpRaw`); no conversion to mastery, Talent, Potential or typed progress semantics is invented. Fixture preserves `123.25` exactly and verifies legacy bytes unchanged. |
| 6. no authoritative LIMIT 50 | PASS | `SkillStore.playerSkills()`, `legacy()` and `reconciled()` contain no authoritative result limit. Final candidate removes ContextBuilder's legacy `LIMIT 50` Skill query and routes context through reconciliation. |
| 7. campaign/player isolation | PASS | Typed queries are scoped by `campaign_id` + `character_uid`; tests prove another campaign/player reads empty and modifier cross-player leakage fails. |
| 8. 1000 Skills no truncation | PASS | Test registers and persists 1005 Skills and asserts all 1005 are returned. |
| 9. baseMastery persistence/reopen | PASS | `typedDefinitionLearnMasteryProgressIsolationAndReopen` persists 80.0 and verifies equality after reopen/`ensureV7`. |
| 10. Phase-5 `SKILL_EFFECTIVE` does not mutate baseMastery | PASS | Resolver starts from `PlayerSkill.baseMastery`, derives effective mastery, and does not persist resolver output. Injury/equipment/buff test verifies base remains 80 and effective returns to 80 after removal. |
| 11. Talent/Potential unchanged | PASS | Dedicated boundary test mutates Talent/Potential and verifies Skill mastery remains 80; Skill writes do not rewrite those profiles. |
| 12. Skill != Technique preserved | PASS | Skill persistence is isolated from `character_techniques`; boundary test preserves Technique mastery 33 while writing Skill. No automatic Technique creation/import path exists in Phase 7 runtime. |
| 13. `CurrentSchema.ensure()` reaches `ensureV7()` | PASS | `CurrentSchema.ensure(saveDb,campaignId)` directly delegates to `MigrationManager().ensureV7(...)`; dedicated test verifies Phase 7 marker and `player_skills_v2`. |
| 14. bootstrap/restore/campaign switch use latest schema | PASS | `LocalGameStore.ensureCurrentSchema()` delegates to `CurrentSchema.ensure`. `bootstrap()`, `restoreBackup()` and `setActiveCampaign()` all call `ensureCurrentSchema`; normal context/open paths do likewise. |
| 15. Phase 3–6 state unchanged | PASS | Phase 7 migration is additive except the required lossless modifier-table CHECK extension for `SKILL_EFFECTIVE`. Regression test seeds Phase-5 stat/modifier state, runs V7 twice, and verifies existing modifier and stat base value unchanged. Phase-6 boundary test verifies Talent/Potential independence. Existing full JVM suite passed in exact candidate CI. |
| 16. migration idempotency | PASS | Repeated `ensureV7()` yields exactly one Phase 7 migration marker and preserves pre-existing state. |
| 17. `PRAGMA integrity_check` | PASS | 1005-Skill persistence fixture asserts `integrity_check = ok`. |
| 18. `PRAGMA foreign_key_check` | PASS | Same fixture enables FKs and asserts `foreign_key_check` returns no row. |
| 19. CI #158 exact SHA | PASS | GitHub Actions run #158 is `completed/success` with `head_sha=8075487d24bf0b3da1bcbd8f9fb2483e9154ec6c`. |

## Legacy authority and reconciliation

The final contract is non-destructive. Legacy `character_skills` remains compatibility evidence. Unmapped records are explicitly unresolved rather than silently promoted. A mapped record can project to canonical typed identity while preserving raw XP and historical metadata. A typed row cannot coexist as a second authority for the same mapped logical Skill unless the mapping explicitly declares supersession; in that case typed state is the sole authority.

No global display-name/key matching is used for reconciliation. World Pack ownership is checked for mapping targets and definition/domain ownership.

## Phase-5 extension

`ModifierTargetKind.SKILL_EFFECTIVE` extends the existing generic Phase-5 resolver rather than introducing a Skill-specific modifier engine. Resolver validation requires matching Skill definition and learned PlayerSkill, preserves campaign/player scope, deterministic ordering, finite guards and no mutation of authoritative base mastery.

## Production schema routing

The Phase-6 production-entrypoint defect is not repeated:

```text
LocalGameStore.ensureCurrentSchema()
  -> CurrentSchema.ensure(...)
  -> MigrationManager.ensureV7(...)
```

`bootstrap()`, restored campaigns and campaign switching route through this latest-schema function.

## Final candidate hardening

The final candidate `8075487d...` is materially required over the earlier implementation commit because `ContextBuilder` no longer reads Skills directly with `ORDER BY mastery DESC,xp DESC LIMIT 50`. It now consumes the typed reconciliation result, including canonical mapped Skills and explicitly unresolved legacy evidence. Therefore the prompt/context path no longer reintroduces a 50-row pseudo-authority or bypasses reconciliation.

## CI

Exact candidate validation:

- workflow: `Build & Release RPG OS ALPHA`
- run: `#158`
- SHA: `8075487d24bf0b3da1bcbd8f9fb2483e9154ec6c`
- status: `completed`
- conclusion: `success`

## Verdict

All 19 coordinator gates for final Phase 7 migration / legacy integrity validation are satisfied on the exact candidate.

**PHASE 7 INTEGRITY VALIDATION: PASS**
