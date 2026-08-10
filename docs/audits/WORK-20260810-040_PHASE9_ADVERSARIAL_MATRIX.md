# WORK-20260810-040 — Phase 9 Adversarial Matrix

Status: FINAL READ-ONLY ADVERSARIAL REVALIDATION

Work ID: `WORK-20260810-040`
Owner: `CHAT-5`
Role: PHASE 9 ADVERSARIAL AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Accepted Phase 8 runtime: `7a28e6e4b28ff10cbb516b94e5e0c120d0a15397`
Previous failed Phase-9 candidate: `d796d374f92d94477542da5f753ee411b633076b`
Previous blocker: `E-07` — authoritative stage-only evolution-path entry bypass
Final hotfix under revalidation: `c64c123104f1643a53bf9bb5ebbf19e4bc0dfe87`
Fresh master before report write: `12156f917fbc2a79144efdbfd93884a91cbf788c`

This report is read-only. CHAT-5 changed no runtime, schema, migration, tests or later-phase code. The only write performed by this work item is this audit document.

## 1. Executive result

The previous E-07 blocker is removed in the hotfix candidate.

The old public stage-only API still exists only as an intentionally unusable compatibility surface: it is annotated `DeprecationLevel.ERROR`, returns `Nothing`, and always throws. It can no longer materialize a current/attained stage. The authoritative evolution mutation path is now `transitionEvolution(characterUid, transitionUid, ...)`.

An explicit path-entry transition is represented by:

```text
EvolutionTransitionDefinition.sourceStageUid == null
```

and the transition UID is preserved as `attainedViaTransitionUid` for the first attained stage.

No reproducible current-contract blocker was found in the requested adversarial revalidation.

## 2. Explicit ENTRY / E-07 revalidation

| Attack | Result | Evidence |
|---|---|---|
| direct start at B/C without ENTRY | PASS | Missing transition UID fails before state mutation; `Phase9EntryTransitionTest.onlyExplicitEntryTransitionCanStartPathAndIllegalStartingStagesFail`. |
| stage-only `enterEvolutionPath(stageUid)` bypass | PASS | API is compile-error deprecated and unconditional runtime error. Reflection adversarial test confirms no state/history is created. |
| missing ENTRY | PASS | Missing `transitionUid` fails and leaves evolution state/attainment empty. |
| wrong target | PASS | Caller selects transition identity, not raw target stage; target comes from registered transition definition. |
| wrong path | PASS | ENTRY creates state only on target stage's registered path. Existing state on that path rejects replay/re-entry. |
| wrong World Pack | PASS | Transition registration requires target stage World-Pack ownership to match transition owner. |
| multiple legal ENTRY points | PASS | Explicit distinct ENTRY UIDs are legal and deterministic for fresh players. There is no implicit selection by stage label/order. |
| replay ENTRY | PASS | Existing current state on the target path rejects replay. |
| ENTRY used as rollback/re-entry after progress | PASS | Rejected once path state exists, including after advancing to later stage. |
| cross-path ENTRY | PASS | A source-null ENTRY has no source path to smuggle across; it starts only its target stage's registered path. Independent paths require independent explicit ENTRY transitions. |
| failed ENTRY atomicity | PASS | Requirement failure occurs before transaction; test verifies zero current state and zero attained stage. Transaction covers state+attainment on successful ENTRY. |
| `attainedViaTransitionUid` correctness | PASS | ENTRY test and legacy mapping test assert exact ENTRY transition UID is stored. |

The original `d796d374...` E-07 reproducer no longer succeeds on `c64c123...`.

## 3. Transition requirement attacks

Phase 9.1 adds generic requirement gates rather than embedding World-Pack mechanics in Core.

`RequirementEvaluator` enforces:

- explicit provider when a binding exists;
- nonblank provider UID;
- existing rule descriptor;
- exact root rule version match;
- gate compatibility (`UNLOCK`, `TRANSITION`, `ACTIVATION` are distinct);
- deterministic dependency traversal (`dependencies.sorted()`);
- cycle detection;
- fail on dependency false;
- fail on malformed/indeterminate `null` evaluation;
- fail on final false result.

Adversarial matrix:

| Attack | Result |
|---|---|
| ENTRY requirement FAIL | PASS — no state/history materialized. |
| missing requirement provider | PASS — fail-loud. |
| missing requirement rule | PASS — fail-loud. |
| requirement version mismatch | PASS — fail-loud on binding/descriptor mismatch. |
| malformed requirement result (`null`) | PASS — fail-loud. |
| requirement dependency cycle | PASS — deterministic cycle error. |
| requirement gate substitution | PASS — UNLOCK/TRANSITION/ACTIVATION rules cannot silently substitute for each other. |

Dependency descriptors themselves are provider-owned and are resolved by UID; the accepted Phase-9.1 contract pins the explicitly persisted root binding version. No reproducible requirement-version bypass was found in the current contract.

## 4. Legacy evolution-stage mapping attacks

Legacy status remains evidence, not authority.

For an explicit legacy `EVOLUTION_STAGE` mapping:

1. the canonical stage target must exist and belong to the selected World Pack;
2. if no typed state exists, materialization goes through `transitionEvolution()`;
3. `entryTransitionUidForStage(stageUid)` requires **exactly one** explicit `source_stage_uid IS NULL` transition to that target;
4. zero matching ENTRY transitions fails loudly;
5. multiple matching ENTRY transitions fail loudly instead of selecting arbitrarily;
6. the resulting attained stage records the exact ENTRY transition UID;
7. existing conflicting typed state fails loudly;
8. legacy bytes remain preserved.

Result: PASS for legacy mapping without legal ENTRY, ambiguous legacy ENTRY, missing/deleted mapping target and duplicate canonical materialization attacks.

## 5. Unlock / activation requirement attacks

`unlockForm()` and `activateForm()` now use separate generic requirement gates.

Verified behavior:

- locked form cannot activate;
- unlock requirement failure creates no unlock;
- missing provider/rule fails loudly;
- malformed/version/gate mismatch fails loudly;
- activation requirement failure creates no active row and no active form modifier;
- deactivation does not re-check activation eligibility and therefore cannot trap a player in an active form;
- deactivation preserves persistent unlock;
- mutually exclusive forms remain rejected through `exclusiveGroupUid`;
- deprecated form cannot be newly activated.

Result: PASS.

## 6. Modifier activation atomicity / Phase-5 boundary

Activation checks unlock, definition status, exclusivity and activation requirement before the activation transaction. The transaction then creates active state, creates/reuses generic Phase-5 modifiers, and switches the source active flag. A failure inside that transaction rolls back the activation unit.

Phase 9 still uses only the accepted Phase-5 `ModifierStore` / `DerivedValueResolver` foundation. No Race/Bloodline/Evolution/Transformation-specific second resolver exists.

Existing integration tests verify active/deactivated form effects do not mutate:

- `PlayerStat.baseValue`;
- `PlayerResource.currentValue`;
- `PlayerSkill.baseMastery`;
- `PlayerTechnique.baseMastery`;
- Talent base value;
- Potential base value.

Derived effects return to base after deactivation while unlock remains persistent.

Result: PASS.

## 7. Evolution graph integrity beyond ENTRY

The existing explicit transition model continues to enforce:

- transition UID existence;
- source stage existence;
- target stage existence;
- source World-Pack ownership;
- target World-Pack ownership;
- exact current source stage;
- cross-path prohibition unless explicitly allowed;
- explicit history retention through attained stages;
- no implicit stage arithmetic;
- no arbitrary rollback by replaying a prior transition from the wrong source.

ENTRY cannot be used as rollback or replay after any current state exists on its target path.

Result: PASS.

## 8. Identity / legacy / isolation regression gates

Previously passing WORK-040 gates remain valid on the hotfix:

- duplicate definition UID rejection;
- World-Pack owner mismatch rejection;
- same display label with different stable UID remains separate;
- `clan_uid`, race, bloodline, form and evolution labels grant nothing without explicit mapping;
- ambiguous explicit legacy mappings fail loudly;
- deleted/missing legacy mapping targets fail loudly;
- campaign and player state remain scoped by `(campaign_id, character_uid, ...)`;
- World-Pack-owned definitions/mappings cannot hijack another pack's target identity;
- unlock remains distinct from active form.

Result: PASS.

## 9. Scale / authoritative no-truncation / integrity

Existing Phase-9 persistence tests still exercise 1005 innate definitions and 1005 player-owned entries, then close/reopen and assert all 1005 remain visible. Authoritative Phase-9 store reads iterate the full result set; no presentation `LIMIT` is used as an authority boundary.

The suite also checks:

- `PRAGMA integrity_check = ok`;
- clean `PRAGMA foreign_key_check` under the adopted FK test policy.

Result: PASS.

## 10. V9.1 migration / production routing

Phase 9.1 migration marker:

```text
RPGOS-9.1-REQUIREMENT-GATES
```

`CurrentSchema.ensure()` now routes to `ensureV9RequirementHotfix()`, which first calls `ensureV9()`, preserving the complete prior migration chain.

The hotfix is additive:

- adds version columns for transition/activation bindings;
- adds explicit unlock requirement UID/version columns;
- preserves pre-hotfix non-null transition/activation rule UIDs deterministically as version 1;
- rewrites no player state and no legacy evidence;
- uses an idempotent migration marker.

Tests execute `CurrentSchema.ensure()` twice, assert exactly one V9.1 migration marker, verify integrity/FK, and reopen successfully. Existing production restore/campaign-switch routing uses the same `CurrentSchema.ensure()` entrypoint, so those paths now reach V9.1 through the same central schema authority.

Result: PASS.

## 11. Phase 3–8 no-regression assessment

The hotfix changes evolution-entry/requirement gating and migration metadata only. The inspected tests continue to exercise Phase-5 derived integration and persistent Phase-6/7/8 boundaries. No hotfix code path writes base stats/resources, Skill/Technique base mastery, Talent/Potential, or Phase-4/6/7/8 legacy reconciliation data.

No reproducible Phase 3–8 regression was found.

Result: PASS.

## 12. Exact CI evidence

Exact candidate:

`c64c123104f1643a53bf9bb5ebbf19e4bc0dfe87`

GitHub Actions:

- workflow: `Build & Release RPG OS ALPHA`;
- run number: `#213`;
- run id: `31350492914`;
- head SHA: `c64c123104f1643a53bf9bb5ebbf19e4bc0dfe87`;
- status: `completed`;
- conclusion: `success`.

CI gate: PASS.

## 13. Final adversarial matrix summary

| Gate | Result |
|---|---|
| Original E-07 stage-only bypass | PASS — removed as authoritative mutation path |
| Direct B/C start without explicit ENTRY | PASS |
| Missing/wrong ENTRY transition | PASS |
| ENTRY replay/rollback/re-entry | PASS |
| ENTRY World-Pack/path isolation | PASS |
| Multiple explicit ENTRY points | PASS |
| ENTRY requirement fail/missing provider/missing rule | PASS |
| Requirement version mismatch | PASS |
| Malformed requirement result | PASS |
| Requirement dependency cycle | PASS |
| Failed ENTRY atomicity | PASS |
| `attainedViaTransitionUid` | PASS |
| Legacy evolution stage without unique explicit ENTRY | PASS — fail-loud |
| Ambiguous legacy ENTRY | PASS — fail-loud |
| Unlock requirement bypass | PASS |
| Activation requirement bypass | PASS |
| Exclusive forms | PASS |
| Modifier activation/deactivation atomic boundary | PASS |
| Phase-5 generic resolver reuse | PASS |
| Stat/resource/Skill/Technique no-retrogression | PASS |
| Talent/Potential no mutation | PASS |
| Campaign/player/World-Pack isolation | PASS |
| >1000 authoritative entries | PASS |
| Authoritative no-truncation | PASS |
| Production V9.1 routing | PASS |
| Integrity / FK | PASS |
| Exact CI for hotfix SHA | PASS — #213 SUCCESS |

## 14. Final verdict

`PHASE 9 ADVERSARIAL REVALIDATION: PASS`

The previously reproducible E-07 blocker is closed on `c64c123104f1643a53bf9bb5ebbf19e4bc0dfe87`. Explicit ENTRY transition identity is now required for initial evolution-path materialization, requirement gates fail safely before authoritative mutation, legacy evolution-stage mapping cannot bypass a unique legal ENTRY, and the Phase-3–8/no-retrogression boundaries remain intact on the inspected candidate.

No runtime correction was implemented by CHAT-5. Phase 10 was not started.