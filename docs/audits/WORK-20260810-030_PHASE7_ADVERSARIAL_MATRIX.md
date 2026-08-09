# WORK-20260810-030 — Phase 7 Adversarial / No-Retrogression Matrix

Status: READ-ONLY RUNTIME / PRE-IMPLEMENTATION ADVERSARIAL MATRIX

Work ID: `WORK-20260810-030`
Owner: `CHAT-5`
Role: PHASE 7 ADVERSARIAL / NO-RETROGRESSION AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Audited master at matrix creation: `b08e8861fa3d3095584f7cd1bf8cdf827b3cd373`
Accepted Phase 6 runtime: `52af00e441131cc8e7beb4a8036e43d250f35848`
Phase 7 implementation work item: `WORK-20260810-026`
WORK-026 result commit at matrix creation: NOT FOUND
Latest CI observed before matrix write: GitHub Actions run #152 on master = SUCCESS.

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
10. Phase-5 effective-mastery support, if added, must extend the existing generic modifier/resolver foundation rather than create a second Skill-specific engine.
11. Phase-7 migration must be reachable from the production current-schema entrypoint.
12. Authoritative typed reads must not silently truncate large Skill sets.

## 2. Existing attack surface

The accepted pre-Phase-7 architecture still contains legacy `character_skills` with at least `entity_uid`, `skill_uid`, `mastery`, `xp`, `updated_chapter`, while presentation/context paths read legacy Skill state directly. The Phase-7 architecture therefore correctly treats implementation as migration/reconciliation rather than replacement.

Important existing behavior to preserve/test:

- CharacterPanel joins `character_skills.skill_uid` to `skill_definitions.skill_uid`.
- ContextBuilder scopes legacy Skill reads by active player UID but applies a presentation/context `LIMIT 50`; this limit must never leak into authoritative typed Skill reads.
- Skill and Technique storage/read paths are already separate and must remain separate.
- Legacy `xp` exists but its semantic meaning is not yet safe to reinterpret globally.

## 3. Mastery numeric attacks

| ID | Case | Required outcome |
|---|---|---|
| M-01 | `baseMastery = 0` | Legal if declared mastery scale permits; persists exactly. |
| M-02 | negative mastery | Deterministic reject unless explicit mastery-scale contract says otherwise. |
| M-03 | NaN | Reject before persistence/resolution. |
| M-04 | +Infinity / -Infinity | Reject. |
| M-05 | extremely large finite mastery | Must follow explicit scale/rule; never silent wrap or hidden clamp. |
| M-06 | mastery above declared maximum | Fail or explicit legal policy; never row-order/implicit clamp. |
| M-07 | `-0.0` | Canonical numeric handling must be deterministic if Double is used. |
| M-08 | duplicate PlayerSkill logical identity `(campaign,character,skill)` | Deterministic conflict handling; never two authoritative rows. |
| M-09 | missing SkillDefinition | Fail-loud/unresolved compatibility state; never synthesize definition from display label. |
| M-10 | deleted/deprecated definition | Learned mastery must not silently disappear; deprecation must not equal unlearned. |

No arbitrary global `0..100` range is required unless the implemented mastery-scale contract explicitly defines it.

## 4. No-retrogression modifier attacks

Canonical fixture:

```text
baseMastery = 80
injury penalty = -30
effectiveMastery = 50
remove injury
effectiveMastery = 80
baseMastery remains 80 throughout
```

Required adversarial cases:

- NR-01 injury activation never writes lower `baseMastery`.
- NR-02 injury removal restores derived result without mastery write.
- NR-03 equipment bonus changes only effective mastery.
- NR-04 equipment removal does not reduce persistent mastery.
- NR-05 temporary buff expiry does not persist a gain.
- NR-06 override/cap/floor affects derived mastery only.
- NR-07 inactive/expired/future modifiers are ignored according to Phase-5 lifecycle semantics.
- NR-08 same priority uses deterministic UID/tie-break semantics inherited from Phase 5.
- NR-09 reversed modifier insertion/list/SQLite row order produces identical effective mastery.
- NR-10 Player A modifier cannot affect Player B.
- NR-11 Campaign A modifier cannot affect Campaign B.
- NR-12 Skill modifier targeting a stat UID must fail target-kind/definition validation.
- NR-13 Stat modifier targeting Skill UID must fail.

Automatic blocker: any path `Modifier/DerivedValueResolver -> PlayerSkill.baseMastery`.

## 5. Phase-5 extension attacks

If Phase 7 introduces `SKILL_EFFECTIVE` or equivalent target:

- it must be an additive/backward-compatible extension to the existing `ModifierTargetKind`/resolver contract;
- deterministic lifecycle → operation → priority → UID ordering must remain unchanged or be explicitly versioned;
- finite guards must remain active;
- campaign/player scoping must remain strict;
- no `SkillModifierEngine` or second resolver may appear;
- Skill effective mastery must be a derived output, not persisted mastery authority;
- removing all modifiers must reconstruct exactly the canonical base mastery.

If Phase 7 chooses not to add effective mastery support yet, that is acceptable only if it does not fake effective mastery by mutating base mastery.

## 6. Talent/Potential attacks

Try to reproduce each forbidden path:

- TP-01 Talent value directly writes/sets `baseMastery`.
- TP-02 Potential directly writes or caps `baseMastery`.
- TP-03 high Talent causes automatic learn event/PlayerSkill insertion.
- TP-04 high Potential causes automatic learn/mastery increase.
- TP-05 Talent/Potential profile update changes an existing PlayerSkill row.
- TP-06 Skill save writes back to Talent/Potential profiles.

Required result: no direct mutation in Phase 7. Talent/Potential may only be stored as explicit future progression-domain bindings/inputs.

## 7. Legacy reconciliation attacks

Phase 7 must prove lossless compatibility with old campaigns.

Required cases:

- L-01 legacy-only Skill remains visible through typed Skill read.
- L-02 orphan legacy `skill_uid` is preserved; not silently dropped because definition is missing.
- L-03 unknown/custom legacy Skill survives without World-Pack hardcoding.
- L-04 legacy + typed same logical Skill WITHOUT explicit mapping/reconciliation fails loudly or becomes explicit unresolved compatibility state.
- L-05 explicit alias/supersession mapping yields exactly one canonical logical Skill.
- L-06 legacy bytes remain preserved after reconciliation.
- L-07 unrelated unmapped legacy Skill remains visible.
- L-08 same text key/label in two World Packs is not auto-merged.
- L-09 case/name differences do not become guessed equivalence.
- L-10 alias target missing fails loudly.
- L-11 alias owner mismatch/hijack fails.
- L-12 mapping version mismatch does not silently remap.
- L-13 typed canonical row does not get overwritten merely because a legacy row exists.
- L-14 1000 legacy skills/values are not truncated.
- L-15 active and non-active player legacy Skills stay isolated.
- L-16 two campaigns with same character UID string stay isolated.

Core must never use global `same key == same logical Skill` reconciliation.

## 8. XP/progress attacks

Legacy `character_skills.xp` is persisted but semantically under-specified. Therefore:

- X-01 migration must preserve legacy XP losslessly.
- X-02 Phase 7 must not automatically treat XP as mastery.
- X-03 Phase 7 must not treat XP as Talent/Potential.
- X-04 no guessed XP→mastery conversion without a declared rule/mapping.
- X-05 unknown XP scale remains compatibility/evidence state if canonical semantics cannot be proven.
- X-06 reopen must preserve exact canonical/compatibility XP representation.

A missing future ProgressionEngine conversion is not a Phase-7 failure.

## 9. Skill/Technique boundary attacks

Required checks:

- T-01 PlayerSkill save creates no `character_techniques`/typed Technique row.
- T-02 legacy Technique UID appearing similar to Skill UID is not auto-imported as Skill.
- T-03 Skill definition does not become executable Technique definition.
- T-04 Technique mastery/proficiency, if present in legacy data, is not copied into Skill mastery without explicit mapping.
- T-05 learning Skill does not grant Technique.
- T-06 deleting/deprecating Skill does not silently delete learned Technique history.

Any combined Skill+Technique authoritative row introduced by Phase 7 is a blocker.

## 10. Definition identity / World Pack attacks

- D-01 duplicate exact Skill UID + incompatible metadata -> reject.
- D-02 duplicate `(worldPackUid,key)` under different UIDs -> reject unless explicit supersession contract.
- D-03 same label/key in different World Packs + distinct UID -> legal and separate.
- D-04 World Pack B cannot register/update A-owned Skill UID.
- D-05 definition ownership change requires explicit migration; never silent reinterpretation.
- D-06 missing progression-domain binding target -> fail-loud if binding is required/persisted.
- D-07 ProgressionDomain owner mismatch -> reject.
- D-08 definition version < 1 / empty provenance -> reject.
- D-09 deprecated definition remains identity-stable and does not erase PlayerSkill.

Stable UID must win over label.

## 11. Campaign/player/domain isolation

Mandatory attempts:

- I-01 same Skill UID in Campaign A and B with different mastery remains isolated.
- I-02 same character UID string across campaigns remains isolated.
- I-03 Player A update cannot change Player B mastery/XP.
- I-04 Skill A domain binding cannot contaminate Skill B.
- I-05 switching ActivePlayerRef cannot retain/read previous player's typed Skills.
- I-06 typed repository APIs must scope reads explicitly; no first-row/global fallback.

## 12. Migration / production entrypoint attacks

After WORK-026 exists, verify:

1. old campaign with legacy Skills opens through production `LocalGameStore.ensureCurrentSchema()` and receives latest Phase-7 schema;
2. bootstrap reaches Phase 7;
3. restore reaches Phase 7 through the same current-schema path;
4. campaign switch reaches Phase 7;
5. migration is additive/idempotent;
6. existing ActivePlayerRef unchanged;
7. stats/resources unchanged;
8. modifiers unchanged;
9. Talent/Potential profiles and Phase-6 legacy mappings unchanged;
10. legacy Skill bytes preserved;
11. no false migration marker without required tables/data;
12. `PRAGMA integrity_check = ok`;
13. `PRAGMA foreign_key_check` clean under adopted FK policy.

Failure to wire Phase 7 into the production current-schema entrypoint is an automatic release blocker, matching the Phase-6 lesson.

## 13. Scale / truncation attacks

- S-01 authoritative typed read for one player with 100 Skills returns 100.
- S-02 authoritative typed read with >1000 Skills returns exact count.
- S-03 presentation/context limit (legacy `LIMIT 50`) must not truncate authoritative store/repository read.
- S-04 duplicate/conflict near the tail (>1000th row) still fails loudly.
- S-05 reopen after >1000 Skills yields identical authoritative count/values.

## 14. Determinism attacks

If effective mastery is resolved through Phase 5:

- reverse modifier insertion order;
- reverse supplied list order;
- alter SQLite row order;
- same operation/priority/timestamp;
- duplicate modifier UID;
- replay same logical input;

must produce identical result or the same deterministic validation failure.

If Skill rules join Phase-5 dependency graph, also test self-cycle, A→B→A, A→B→C→A, missing node and deep acyclic chain. Do not require graph machinery if Phase 7 introduces no Skill rule dependency graph.

## 15. Automatic FAIL conditions for final validation

`PHASE 7 ADVERSARIAL VALIDATION: FAIL` is mandatory for any reproducible current-runtime case where WORK-026:

- permanently changes baseMastery from temporary injury/equipment/buff state;
- allows Talent/Potential to write mastery;
- creates duplicate authoritative mastery for one logical reconciled Skill;
- loses legacy Skill or unknown/orphan Skill state;
- guesses legacy equivalence from label/key globally;
- cross-leaks campaign/player/World-Pack Skill state;
- allows NaN/Infinity canonical mastery;
- silently truncates authoritative Skill reads;
- creates a parallel Skill modifier engine/resolver;
- makes Skill and Technique the same authoritative object;
- rewrites Phase 3/4/5/6 authority during migration;
- fails to reach Phase-7 schema through the production current-schema entrypoint.

Missing future ProgressionEngine behavior is NOT a Phase-7 failure if persistence/identity/mastery contracts remain correct.

## 16. Current runtime validation status

At matrix creation, `master = b08e8861fa3d3095584f7cd1bf8cdf827b3cd373`. Repository commit search found no `WORK-20260810-026` result commit. Current master CI run #152 is `SUCCESS`, but it validates the Phase-6/report baseline, not Phase-7 runtime.

Therefore final runtime adversarial validation cannot yet be performed.

Current status:

`PHASE 7 ADVERSARIAL MATRIX READY`

After WORK-026 resultCommit appears, extend this report with actual code/test/CI evidence and exactly one final verdict:

`PHASE 7 ADVERSARIAL VALIDATION: PASS`

or

`PHASE 7 ADVERSARIAL VALIDATION: FAIL`

No runtime implementation changes are authorized under WORK-20260810-030.
