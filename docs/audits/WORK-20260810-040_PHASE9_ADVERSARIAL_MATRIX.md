# WORK-20260810-040 — Phase 9 Adversarial Matrix

Status: READ-ONLY RUNTIME / PRE-IMPLEMENTATION ADVERSARIAL MATRIX

Work ID: `WORK-20260810-040`
Owner: `CHAT-5`
Role: PHASE 9 ADVERSARIAL AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Fresh master at matrix creation: `4f8431e4cdf983f7f12fa73e544d988db30953ad`
Accepted Phase 8 runtime: `7a28e6e4b28ff10cbb516b94e5e0c120d0a15397`
Phase 9 implementation work item: `WORK-20260810-036`
WORK-036 result commit at matrix creation: NOT FOUND

This document is read-only. It does not implement Phase 9 runtime, schema, migration, World Pack mechanics, ProgressionEngine, PlayerDomainEngine, DevelopmentProject, or CharacterPanelSnapshot v2.

Canonical architecture input: `docs/audits/WORK-20260810-034_NEXT_PHASE_ARCHITECTURE.md`.

## 1. Release invariants under attack

Phase 9 must preserve all of the following:

1. Origin/identity, innate feature ownership, evolution path, evolution stage, transition, unlocked form and active form are distinct semantic concepts.
2. `OWNED / UNLOCKED != CURRENTLY ACTIVE`.
3. `EVOLUTION STAGE != TEMPORARY TRANSFORMATION`.
4. World-Pack-owned definitions use stable UID identity; labels are presentation/evidence only.
5. Clan/race/species/bloodline labels do not automatically grant gameplay features.
6. Legacy status/prompt/canon labels remain evidence until explicitly mapped.
7. Persistent unlock/ownership cannot be destroyed by temporary deactivation.
8. Temporary form effects may only affect derived Phase-5 targets and must never rewrite Phase-3–8 persistent authority.
9. Evolution state changes must occur only through explicit legal transitions; no implicit stage arithmetic or label guessing.
10. Campaign, character and World Pack isolation are mandatory.
11. Typed Phase-9 authoritative reads must not silently truncate state.
12. Production `CurrentSchema.ensure()` must reach V9/latest schema once WORK-036 exists.
13. Phase 3–8 data, aliases, modifiers, Talent/Potential, Skills and Techniques must remain unchanged except for explicit backward-compatible generic relationships.

## 2. Existing attack surface before WORK-036

The repository currently has no accepted typed Phase-9 runtime. Existing evidence is distributed across:

- `character_status_snapshot` / `legacy_status.*`,
- canon/world character identity surfaces such as `clan_uid`,
- prompt/context text,
- World Pack data,
- existing Phase-4 stats/resources,
- Phase-6 Talent/Potential,
- Phase-7 Skills,
- Phase-8 Techniques.

WORK-034 establishes that these are not sufficient by themselves to grant Phase-9 canonical ownership/state. The matrix therefore treats all label-driven inference as adversarial unless WORK-036 introduces an explicit versioned mapping contract.

## 3. Definition identity / World Pack attacks

| ID | Attack | Required outcome |
|---|---|---|
| D-01 | duplicate exact definition UID in one registration | Fail-loud unless exact idempotent registration is explicitly supported. |
| D-02 | same UID with incompatible owner/metadata | Reject; ownership/meaning cannot be silently changed. |
| D-03 | same display label, different stable UID | Remain separate identities. |
| D-04 | same textual key across different World Packs | No automatic merge. |
| D-05 | World Pack B attempts to register A-owned UID | Reject. |
| D-06 | blank owner UID / blank provenance / invalid version | Reject deterministically. |
| D-07 | deprecated definition referenced by existing player state | Existing state/history remains readable; deprecation cannot erase ownership. |
| D-08 | supersession target missing | Fail-loud/unresolved, never silent reassignment. |
| D-09 | ownership changes after player rows already reference definition | Reject or explicit versioned migration only. |

Stable UID must always outrank label/name matching.

## 4. Origin / identity attacks

- O-01 `clan_uid` alone -> bloodline feature grant: **forbidden**.
- O-02 race/species text label alone -> PlayerOrigin: **forbidden without mapping**.
- O-03 prompt/narrative says a race name -> canonical origin row: **forbidden**.
- O-04 one origin label mapped to two canonical origins without explicit disambiguation -> unresolved/fail-loud.
- O-05 same origin label in two World Packs -> remain isolated.
- O-06 one character with multiple explicitly legal origins/hybrid relationships -> supported if definition contract allows it; Core must not collapse to one `race:String`.
- O-07 rename/display-label change -> persistent identity unchanged.
- O-08 missing/deleted origin definition referenced by legacy mapping -> unresolved/fail-loud, no silent loss.

## 5. Innate feature ownership attacks

- IF-01 temporary modifier -> persistent innate feature grant: **must be impossible**.
- IF-02 owning origin/clan -> automatically own every associated feature: **forbidden** unless explicit World Pack grant/mapping contract exists.
- IF-03 Skill/Technique name resembles bloodline -> automatic innate ownership: **forbidden**.
- IF-04 high Talent/Potential -> automatic innate feature grant: **forbidden**.
- IF-05 high stat/resource -> automatic innate feature grant: **forbidden**.
- IF-06 deactivate/suppress feature -> persistent ownership row deleted: **blocker**.
- IF-07 duplicate `(campaign,character,featureUid)` -> never two authoritative ownership rows.
- IF-08 feature owned by player A appears for B -> blocker.
- IF-09 feature definition owner mismatch at grant time -> reject.
- IF-10 deprecated feature definition -> existing ownership preserved; new grant policy must be explicit.

## 6. Evolution path / stage / transition attacks

- E-01 path definition UID duplicated -> reject.
- E-02 stage UID duplicated/incompatible owner -> reject.
- E-03 transition references missing source stage -> reject.
- E-04 transition references missing target stage -> reject.
- E-05 transition connects stages from different paths when cross-path transitions are not explicitly allowed -> reject.
- E-06 stage rollback `B -> A` without explicit legal transition -> reject.
- E-07 direct write of current stage to arbitrary target bypassing transition identity -> reject or be impossible through authoritative API.
- E-08 stage arithmetic (`level+1`) used as identity instead of stable stage UID -> blocker.
- E-09 attaining target stage deletes evidence/history of prior attained stage -> blocker if attained/history is part of contract.
- E-10 same transition replay creates duplicate persistent transition/state -> idempotent or deterministic conflict; never duplicate authority.
- E-11 transition from a stage the character does not currently/legally hold -> reject.
- E-12 transition target deprecated/missing -> fail-loud, not implicit fallback.
- E-13 transition World Pack owner differs from path/stage owner without explicit allowed cross-pack relationship -> reject.

## 7. Unlock vs active-form state attacks

Canonical state-machine target:

`locked -> unlocked -> inactive -> active -> inactive`

with unlock preserved.

- F-01 active form without prior persistent unlock -> reject unless definition explicitly represents non-unlock runtime state and contract proves legality.
- F-02 activation automatically writes new unlock when no legal unlock command/mapping exists -> blocker.
- F-03 deactivation deletes unlock -> blocker.
- F-04 app reopen after inactive state loses permanent unlock -> blocker.
- F-05 activation after reopen works only if explicit unlock persisted.
- F-06 multiple mutually-exclusive forms active simultaneously -> deterministic reject based on explicit exclusive-group/compatibility definition.
- F-07 form from unrelated World Pack/path activated due to same label -> reject.
- F-08 active-form runtime state used as evidence of evolution-stage ownership -> forbidden unless explicit binding says so.
- F-09 unlocked form automatically becomes active on read/bootstrap without explicit persisted/default rule -> forbidden.
- F-10 form deactivation mutates persistent stats/skills/techniques/talent/potential -> blocker.

## 8. Phase-5 derived boundary attacks

Temporary form/transformation effects must reuse the accepted generic Modifier/DerivedValueResolver foundation.

Automatic blockers:

- P5-01 form effect rewrites `PlayerStat.baseValue`.
- P5-02 form effect rewrites `PlayerResource.currentValue` merely because maximum/regeneration changes.
- P5-03 form effect rewrites `PlayerSkill.baseMastery`.
- P5-04 form effect rewrites `PlayerTechnique.baseMastery`.
- P5-05 form effect rewrites persistent Talent.
- P5-06 form effect rewrites persistent Potential.
- P5-07 Phase 9 creates `RaceModifierEngine`, `BloodlineModifierEngine`, `EvolutionModifierEngine` or `TransformationModifierEngine` as a second derived truth.
- P5-08 Phase-9 effects bypass campaign/player/target-kind checks from Phase 5.
- P5-09 deactivation fails to remove only derived effects while preserving base values/unlock state.
- P5-10 modifier source lifecycle references inactive form but remains effective because source state is not reflected consistently in present contract.

Future PlayerDomainEngine integration is not required in Phase 9; hidden cross-domain authoritative mutation is forbidden.

## 9. Phase 6–8 boundary attacks

- B-01 innate feature acquisition directly changes Talent value -> forbidden in Phase 9.
- B-02 evolution stage directly changes Potential value -> forbidden in Phase 9.
- B-03 feature acquisition copies Skill mastery -> forbidden.
- B-04 feature acquisition copies Technique mastery -> forbidden.
- B-05 Skill ownership inferred from innate feature without explicit future legal grant path -> forbidden.
- B-06 Technique ownership inferred/granted from feature without explicit unlock/requirement relationship and legal domain path -> forbidden.
- B-07 Technique/Skill existence used as proof of bloodline/origin -> forbidden.
- B-08 progression domain relationship uses labels rather than stable UID -> reject.
- B-09 Phase 9 writes or deletes Phase-4 reconciliation aliases -> blocker.
- B-10 Phase 9 writes/deletes Phase-6 legacy evidence/mappings -> blocker.
- B-11 Phase 9 rewrites Phase-7/8 legacy mappings -> blocker.

## 10. Legacy evidence / mapping attacks

Legacy evidence must remain lossless and mechanically inert until explicit mapping.

Mandatory cases:

- L-01 `clan_uid` exists; no mapping -> no canonical bloodline/feature grant.
- L-02 legacy `race/species` label exists; no mapping -> unresolved evidence only.
- L-03 legacy `bloodline/lineage` label exists; no mapping -> unresolved evidence only.
- L-04 legacy `form/transformation` label exists; no mapping -> unresolved evidence only.
- L-05 legacy `evolution_stage` label exists; no mapping -> unresolved evidence only.
- L-06 same label appears under two World Packs -> no guessing.
- L-07 explicit mapping target missing -> fail-loud/unresolved.
- L-08 mapping target owner mismatch -> reject.
- L-09 mapping version mismatch -> no silent reinterpretation.
- L-10 mixed legacy + typed same semantic state without mapping/supersession -> deterministic ambiguity/fail-loud.
- L-11 explicit mapping -> exactly one canonical identity/state.
- L-12 legacy bytes/evidence remain preserved after mapping.
- L-13 unrelated unmapped evidence remains visible.
- L-14 mapping one character does not canonicalize same text for another automatically.
- L-15 mapping one campaign does not affect another.
- L-16 prompt/history text is never authoritative mapping source by itself.

No global `same label == same origin/feature/stage/form` rule is acceptable.

## 11. State integrity / no-retrogression attacks

- S-01 owned feature remains owned after temporary suppression.
- S-02 unlocked stage/form remains unlocked after deactivation.
- S-03 current stage cannot regress without legal transition.
- S-04 temporary transformation cannot erase persistent evolution progression.
- S-05 runtime active-form state cannot overwrite persistent origin identity.
- S-06 missing active-form row means inactive, not unowned.
- S-07 restoring an older runtime activation flag must not roll back permanent unlock acquired later unless the restore/branch operation explicitly restores campaign history.
- S-08 duplicate active-state rows cannot represent conflicting simultaneous current form for same exclusive group.

## 12. Isolation attacks

Mandatory attempts after WORK-036:

- I-01 campaign A state visible in campaign B.
- I-02 player A origin/features/evolution visible for B.
- I-03 same character UID string in two campaigns remains isolated.
- I-04 World Pack A definition UID can be hijacked by B.
- I-05 mapping for campaign A mutates B.
- I-06 active-player switch retains previous player's active form/unlocks.
- I-07 ContextBuilder/CharacterPanel uses first-row/global fallback when active player unresolved.
- I-08 transition/grant APIs accept state from another campaign/player.

Any leakage is a release blocker.

## 13. Scale / truncation attacks

- SC-01 authoritative read with 100 innate/state entries returns all.
- SC-02 authoritative read with >1000 feature/unlock/evolution-state rows returns exact count.
- SC-03 ContextBuilder/CharacterPanel may apply presentation limits, but authoritative repository reads must not.
- SC-04 conflict located after row 1000 still fails loudly.
- SC-05 reopen after >1000 entries preserves exact count and identity.
- SC-06 migration does not use fixed small `LIMIT` for backfill/reconciliation.

## 14. Migration / production routing attacks

After WORK-036 exists, verify:

1. `CurrentSchema.ensure()` reaches V9/latest schema.
2. `ensureV9()` or equivalent chains through V8 and all prior migrations.
3. bootstrap reaches V9.
4. restore reaches V9.
5. campaign switch reaches V9.
6. new campaign starts at current schema.
7. V9 is additive/idempotent.
8. re-running current schema does not duplicate definitions/state/mappings.
9. ActivePlayerRef unchanged.
10. Phase-4 stats/resources and reconciliation unchanged.
11. Phase-5 modifiers/resolver rows unchanged except explicit safe generic relationships if any.
12. Phase-6 Talent/Potential profiles/evidence/mappings unchanged.
13. Phase-7 Skills/reconciliation unchanged.
14. Phase-8 Techniques/reconciliation/resource-cost mappings unchanged.
15. legacy status/evidence bytes unchanged.
16. no migration marker without required schema/tables.
17. `PRAGMA integrity_check = ok`.
18. `PRAGMA foreign_key_check` clean under adopted FK policy.

Failure to route production current-schema to V9 is an automatic blocker.

## 15. Required final validation cases after WORK-036

At minimum, final read-only validation must seek evidence for:

1. duplicate definition UID rejection,
2. owner hijack rejection,
3. same-name/different-UID separation,
4. clan label grants nothing without mapping,
5. race/species label grants nothing without mapping,
6. temporary activation does not grant permanent unlock,
7. deactivation preserves unlock,
8. active form without unlock rejected,
9. invalid stage transition rejected,
10. unauthorized cross-path transition rejected,
11. stage rollback without transition rejected,
12. multiple mutually-exclusive forms rejected,
13. campaign isolation,
14. player isolation,
15. World Pack isolation,
16. temporary form does not rewrite stat base,
17. temporary form does not rewrite Skill base mastery,
18. temporary form does not rewrite Technique base mastery,
19. temporary form does not rewrite Talent/Potential,
20. unresolved legacy evidence preserved,
21. explicit mapping canonicalizes exactly once,
22. mixed legacy+typed ambiguity fails loudly,
23. mapping target deletion/owner mismatch fails loudly,
24. >1000 entries without authoritative truncation,
25. V9 production routing,
26. Phase 3–8 no-regression,
27. integrity/FK checks,
28. exact final CI for WORK-036 result SHA.

## 16. Automatic FAIL conditions

`PHASE 9 ADVERSARIAL VALIDATION: FAIL` is mandatory for any reproducible current-runtime case where WORK-036:

- infers canonical origin/feature/evolution state from label, `clan_uid`, prompt text or Skill/Technique name without explicit mapping;
- collapses identity, innate ownership, evolution stage and active transformation into one flat authority;
- allows temporary activation to create persistent unlock/ownership;
- deletes unlock/ownership on deactivation;
- allows invalid/cross-path/rollback transition without explicit legal transition;
- activates a form that is not legally unlocked;
- permits cross-campaign/player/World-Pack leakage;
- rewrites base stat/Skill/Technique/Talent/Potential from temporary form state;
- creates a second Phase-9-specific derived/modifier engine;
- loses or silently hides unresolved legacy evidence;
- creates duplicate authoritative typed+legacy semantic state;
- silently truncates authoritative Phase-9 reads;
- mutates Phase 3–8 authority during migration;
- fails to reach V9 through the production current-schema entrypoint.

Missing future ProgressionEngine/PlayerDomainEngine behavior is NOT a Phase-9 failure when Phase 9 only models identity/state and preserves authority boundaries.

## 17. Current validation status

Fresh master at matrix creation: `4f8431e4cdf983f7f12fa73e544d988db30953ad`.

Repository commit search found no `WORK-20260810-036` result commit. Therefore final runtime adversarial validation cannot yet be performed.

Current status:

`PHASE 9 ADVERSARIAL MATRIX READY`

After WORK-036 resultCommit appears, extend this report with actual code/test/CI evidence and exactly one final verdict:

`PHASE 9 ADVERSARIAL VALIDATION: PASS`

or

`PHASE 9 ADVERSARIAL VALIDATION: FAIL`

No runtime implementation changes are authorized under WORK-20260810-040.
