# WORK-20260810-035 — Phase 8 Adversarial Matrix

Status: READ-ONLY RUNTIME / PRE-IMPLEMENTATION ADVERSARIAL MATRIX

Work ID: `WORK-20260810-035`
Owner: `CHAT-5`
Role: PHASE 8 ADVERSARIAL AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Master at matrix creation: `0653c6c6fe03da3db98623112f7a0af4c3f88464`
Accepted Phase 7 runtime: `8075487d24bf0b3da1bcbd8f9fb2483e9154ec6c`
Phase 8 implementation work item: `WORK-20260810-031`
WORK-031 result commit at matrix creation: NOT FOUND

This document is read-only. It does not implement Phase 8 runtime, schema, migration, DevelopmentProject, ProgressionEngine, PlayerDomainEngine, or CharacterPanelSnapshot v2.

Canonical architecture input: `docs/audits/WORK-20260810-029_PHASE8_TECHNIQUE_ARCHITECTURE.md`.

## 1. Release invariants under attack

Phase 8 must preserve all of the following:

1. `Technique != Skill != Talent != Potential != Stat != Resource`.
2. Learned Technique identity is stable UID based and World-Pack-owned.
3. Player Technique authority is scoped by `(campaign, character, techniqueUid)`.
4. If mastery exists, `baseTechniqueMastery` is persistent authority; temporary effects must never overwrite it.
5. Existing `character_techniques` data must remain losslessly visible through the typed Technique contract.
6. Legacy + typed representations of one logical Technique cannot become two unresolved authoritative Technique states.
7. Legacy XP/history/counters/equipped/notes must be preserved according to proven semantics; no invented XP conversion.
8. Skill requirement relationships use stable Skill UID and cannot copy Skill mastery into Technique mastery.
9. Talent/Potential cannot directly grant or write Technique mastery.
10. Generic resource costs use `ResourceDefinition.resourceUid`; Core must not hardcode chakra or another universe resource.
11. `canon_technique_index` is reference/browser data and must not auto-create/link TechniqueDefinition by name.
12. Authoritative Technique reads must not inherit presentation/context truncation such as legacy `LIMIT 60`.
13. Phase-8 migration must be reachable from the production current-schema entrypoint.
14. Phase 3–7 authority and migrations must remain unchanged except for explicit backward-compatible Phase-5 target extension if required.

## 2. Existing attack surface

Current legacy/runtime evidence includes `character_techniques` fields such as `entity_uid`, `technique_uid`, `mastery`, `xp`, `learned_chapter`, `last_used_chapter`, `usage_count`, `success_count`, `failure_count`, `is_equipped`, `notes`, plus definition-side `base_chakra_cost` and player-side `chakra_cost_override` in existing presentation paths. `ContextBuilder` currently applies a player Technique context limit, so Phase 8 must ensure this remains presentation-only and never becomes authoritative repository truncation.

`canon_technique_index` is a World Pack/reference surface. Its name/category/rank/element metadata is not sufficient proof that a corresponding canonical `TechniqueDefinition` exists or shares identity.

## 3. Technique identity / definition attacks

| ID | Case | Required outcome |
|---|---|---|
| D-01 | duplicate exact Technique UID | Fail-loud unless exact idempotent registration is explicitly supported. |
| D-02 | same UID with incompatible owner/metadata | Reject; no silent reinterpretation. |
| D-03 | same label/name, different UID | Remain separate identities. |
| D-04 | same textual key in different World Packs | No automatic merge. |
| D-05 | World Pack B attempts to register A-owned UID | Reject. |
| D-06 | definition version < 1 / blank provenance | Reject. |
| D-07 | missing/deleted definition for learned player Technique | Preserve unresolved/history; never silently delete learned Technique. |
| D-08 | deprecated definition | Existing learned ownership persists; deprecation must not mean unlearned. |
| D-09 | guessed identity from canon-index name | Forbidden. |

Stable UID has precedence over every display label.

## 4. Mastery numeric attacks

If Phase 8 canonicalizes Technique mastery:

- M-01 `baseMastery = 0` — legal if declared range permits.
- M-02 negative mastery — deterministic reject unless explicit scale allows it.
- M-03 NaN — reject before persistence/resolution.
- M-04 +Infinity / -Infinity — reject.
- M-05 very large finite mastery — obey explicit definition range; no arbitrary global maximum is required.
- M-06 value above declared max — reject or explicit legal policy; never hidden mutation.
- M-07 `-0.0` — deterministic canonical handling if Double is used.
- M-08 duplicate `(campaign,character,techniqueUid)` — never two authoritative rows.

Automatic blocker: any temporary derived path that writes back into persistent mastery.

## 5. No-retrogression / modifier attacks

If `TECHNIQUE_EFFECTIVE` or equivalent is added to Phase 5:

- NR-01 injury penalty changes effective value only.
- NR-02 equipment bonus/removal changes effective value only.
- NR-03 temporary buff expiry does not persist gain.
- NR-04 override/cap/floor do not mutate base mastery.
- NR-05 same priority/order remains deterministic by existing Phase-5 rules.
- NR-06 reversed modifier insertion/list/SQLite order produces identical result.
- NR-07 Player A modifier cannot affect B.
- NR-08 Campaign A modifier cannot affect B.
- NR-09 Technique modifier targeting Skill/Stat UID fails target-kind validation.
- NR-10 Stat/Skill modifier targeting Technique UID fails.
- NR-11 no `TechniqueModifierEngine` or second resolver is introduced.

## 6. Skill requirement attacks

Technique requirements must use stable Skill identity.

- SR-01 missing required Skill definition -> deterministic failure/unresolved requirement.
- SR-02 wrong World Pack Skill owner -> reject if ownership contract requires same/authorized pack.
- SR-03 label-only requirement -> forbidden.
- SR-04 required Skill mastery threshold must declare BASE vs EFFECTIVE semantics explicitly.
- SR-05 temporary Skill injury may affect an EFFECTIVE requirement only; it cannot unlearn Technique.
- SR-06 Skill mastery is never copied into Technique mastery.
- SR-07 Skill update does not automatically rewrite Technique mastery.
- SR-08 learning Skill does not automatically create/grant Technique.
- SR-09 learned Technique remains learned if an execution requirement later becomes temporarily unmet.

## 7. Talent/Potential attacks

Forbidden paths:

- TP-01 Talent directly grants PlayerTechnique.
- TP-02 Potential directly grants PlayerTechnique.
- TP-03 Talent value copied into Technique mastery.
- TP-04 Potential used as direct Technique mastery cap/write in Phase 8.
- TP-05 Talent/Potential profile update rewrites Technique row.
- TP-06 Technique save writes Talent/Potential.

Future progression relationships are allowed only as explicit future ProgressionEngine inputs.

## 8. Legacy reconciliation attacks

Required cases:

- L-01 legacy-only Technique remains visible through typed Technique read.
- L-02 orphan `technique_uid` survives as unresolved compatibility state.
- L-03 unknown/custom Technique survives without universe hardcoding.
- L-04 legacy + typed same logical Technique WITHOUT explicit mapping fails loudly or remains explicitly unresolved.
- L-05 explicit mapping/supersession yields exactly one canonical logical Technique.
- L-06 legacy bytes remain unchanged after mapping.
- L-07 unrelated unmapped legacy Technique remains visible.
- L-08 same name/case-insensitive name across World Packs is not guessed as same Technique.
- L-09 mapping target missing -> fail-loud.
- L-10 mapping owner changed/hijacked -> fail-loud.
- L-11 mapping version mismatch -> no silent remap.
- L-12 typed canonical value cannot be silently overwritten by legacy row.
- L-13 1000+ legacy Techniques are not truncated.
- L-14 active/non-active players remain isolated.
- L-15 same character UID string in two campaigns remains isolated.

No global `same name == same Technique` or `same key == same Technique` rule is acceptable.

## 9. XP / telemetry / learned-state attacks

Legacy XP semantics are not proven. Therefore:

- X-01 preserve XP losslessly.
- X-02 no XP -> mastery conversion without explicit semantics/rule.
- X-03 no XP -> Skill XP conversion.
- X-04 no XP -> Talent/Potential conversion.
- X-05 reopen preserves exact compatibility representation.

Usage/success/failure counters are historical/telemetry-like unless runtime proves stronger authority semantics. They must not manufacture mastery or ownership.

`is_equipped` must not be used as proof of learned ownership; a learned-but-unequipped Technique must remain learned.

## 10. Generic resource cost attacks

New Core contract must target stable `ResourceDefinition.resourceUid`.

- RC-01 no literal `chakra` branch in generic Technique Core model/resolver.
- RC-02 legacy `base_chakra_cost` remains preserved when unmapped.
- RC-03 legacy `chakra_cost_override` remains preserved when unmapped.
- RC-04 no automatic legacy chakra -> arbitrary generic resource mapping.
- RC-05 explicit mapping target resource missing -> fail-loud.
- RC-06 explicit mapping target belongs to wrong World Pack/campaign context -> reject.
- RC-07 deleted/deprecated resource target -> deterministic unresolved/failure semantics.
- RC-08 cost binding with NaN/Infinity/negative invalid value follows explicit validation policy.
- RC-09 multiple resource costs remain separate and deterministic if supported.
- RC-10 cost definition must not mutate `PlayerResource.currentValue`; actual spending belongs to a later legal mechanics/domain action, not the Technique definition/read model.

## 11. Canon index attacks

- CI-01 same Technique name in `canon_technique_index` and legacy definition does not auto-link.
- CI-02 different World Packs with same name remain separate.
- CI-03 browser/reference metadata cannot create PlayerTechnique.
- CI-04 canon index rename cannot change persistent Technique identity.
- CI-05 explicit canon mapping target missing or ownership-changed -> fail-loud.

## 12. Skill != Technique leakage

- ST-01 PlayerTechnique save creates no PlayerSkill.
- ST-02 PlayerSkill save creates no PlayerTechnique.
- ST-03 Skill mastery update does not copy to Technique mastery.
- ST-04 Technique mastery update does not copy to Skill mastery.
- ST-05 Technique requirement rows do not become Skill ownership rows.
- ST-06 legacy similarly named Skill/Technique UIDs are not merged without explicit cross-domain mapping.

Any combined Skill+Technique authoritative object is a blocker.

## 13. Isolation attacks

Mandatory attempts:

- I-01 campaign A -> B leakage.
- I-02 player A -> B leakage.
- I-03 World Pack A -> B definition hijack.
- I-04 same Technique UID string across campaigns respects definition/ownership contract and player state isolation.
- I-05 active-player switch does not retain previous player's Technique read.
- I-06 ContextBuilder/repository never use first-row/global fallback.
- I-07 cost mapping for one campaign/pack cannot alter another.

## 14. Scale / truncation attacks

- S-01 authoritative read with 100 Techniques returns exactly 100.
- S-02 authoritative read with >1000 Techniques returns exact count.
- S-03 old ContextBuilder `LIMIT 60` remains context-only; typed authoritative read has no such limit.
- S-04 duplicate/conflict after the 1000th row still fails loudly.
- S-05 reopen after >1000 Techniques preserves exact count and values.

## 15. Migration / production entrypoint attacks

After WORK-031 exists, verify:

1. `CurrentSchema.ensure()` reaches Phase 8/latest schema.
2. `ensureV8()` or equivalent chains through prior migrations in order.
3. bootstrap reaches V8.
4. restore reaches V8.
5. campaign switch reaches V8.
6. migration is additive/idempotent.
7. ActivePlayerRef unchanged.
8. stats/resources unchanged.
9. Phase-4 reconciliation unchanged.
10. Phase-5 modifiers/resolver unchanged except explicit backward-compatible target extension.
11. Talent/Potential unchanged.
12. Skill definitions/player Skills/reconciliation unchanged.
13. legacy Technique bytes unchanged.
14. no false migration marker without required schema.
15. `PRAGMA integrity_check = ok`.
16. `PRAGMA foreign_key_check` clean under adopted FK policy.

Failure to wire V8 into production current-schema path is an automatic release blocker.

## 16. Automatic FAIL conditions

`PHASE 8 ADVERSARIAL VALIDATION: FAIL` is mandatory for any reproducible current-runtime case where WORK-031:

- loses or silently hides legacy/orphan Technique state;
- creates duplicate authoritative Technique state for one reconciled logical Technique;
- guesses Technique identity from name/key/canon index;
- allows NaN/Infinity canonical mastery;
- permanently changes base Technique mastery from temporary modifier state;
- copies Skill mastery into Technique mastery;
- lets Talent/Potential directly grant or rewrite Technique;
- hardcodes chakra (or another universe-specific resource) into the new generic Core Technique contract;
- silently maps ambiguous legacy chakra cost to a generic resource;
- permits cross-campaign/player/World-Pack leakage;
- silently truncates authoritative Technique reads;
- creates a second Technique-specific modifier/resolver engine;
- collapses Skill and Technique into one authoritative model;
- mutates Phase 3–7 authority during migration;
- fails to reach Phase-8 schema through the production current-schema entrypoint.

Missing future DevelopmentProject/ProgressionEngine behavior is NOT a Phase-8 failure if identity, persistence and no-retrogression contracts remain correct.

## 17. Current validation status

At matrix creation, fresh `master = 0653c6c6fe03da3db98623112f7a0af4c3f88464`. Repository commit search found no `WORK-20260810-031` result commit. Therefore final runtime adversarial validation cannot yet be performed.

Current status:

`PHASE 8 ADVERSARIAL MATRIX READY`

After WORK-031 resultCommit appears, extend this report with actual code/test/CI evidence and exactly one final verdict:

`PHASE 8 ADVERSARIAL VALIDATION: PASS`

or

`PHASE 8 ADVERSARIAL VALIDATION: FAIL`

No runtime implementation changes are authorized under WORK-20260810-035.
