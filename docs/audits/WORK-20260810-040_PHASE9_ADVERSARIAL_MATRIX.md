# WORK-20260810-040 — Phase 9 Adversarial Matrix

Status: FINAL READ-ONLY ADVERSARIAL VALIDATION

Work ID: `WORK-20260810-040`
Owner: `CHAT-5`
Role: PHASE 9 ADVERSARIAL AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Fresh master at matrix creation: `4f8431e4cdf983f7f12fa73e544d988db30953ad`
Accepted Phase 8 runtime: `7a28e6e4b28ff10cbb516b94e5e0c120d0a15397`
Phase 9 implementation work item: `WORK-20260810-036`
Final audited candidate: `d796d374f92d94477542da5f753ee411b633076b`
Fresh master before final report write: `7d9585bf0457734b7400916c3e113a1ee6c4adbe`

This document is read-only. It does not implement or repair Phase 9 runtime, schema, migration, World Pack mechanics, ProgressionEngine, PlayerDomainEngine, DevelopmentProject, or CharacterPanelSnapshot v2.

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
12. Production `CurrentSchema.ensure()` must reach V9/latest schema.
13. Phase 3–8 data, aliases, modifiers, Talent/Potential, Skills and Techniques must remain unchanged except for explicit backward-compatible generic relationships.

## 2. Definition identity / World Pack attacks

| ID | Attack | Required outcome | Final result |
|---|---|---|---|
| D-01 | duplicate exact definition UID | Fail-loud | PASS |
| D-02 | same UID incompatible owner/metadata | Reject | PASS |
| D-03 | same display label, different UID | Separate identities | PASS |
| D-04 | same textual key across World Packs | No automatic merge | PASS |
| D-05 | World Pack B attempts to register A-owned UID | Reject | PASS |
| D-06 | blank owner/provenance/invalid version | Reject | PASS |

Registration methods validate stable UID, owner UID, definition version and provenance. Stage/form relationships additionally validate ownership of referenced path/feature/stage definitions.

## 3. Origin / innate / legacy attacks

- `clan_uid`, `race`, `bloodline`, `evolution_stage`, `form` and similar legacy fields are read as evidence only.
- Bare evidence creates no `PlayerOrigin`, `PlayerInnateFeature`, evolution state, attained stage, form unlock or active form.
- Explicit World Pack mapping is required for canonicalization.
- Ambiguous mappings fail loudly.
- Missing/deleted mapping target fails loudly.
- Target ownership is checked against the mapping World Pack.
- Applying the same explicit mapping twice canonicalizes exactly once.
- Legacy bytes remain untouched.
- Mixed typed state plus unmapped legacy evidence remains explicitly unresolved.

Result: PASS for label/clan automatic-grant attacks, ambiguity, missing target and lossless evidence preservation.

## 4. Unlock vs active-form attacks

Canonical lifecycle under test:

`locked -> unlocked + inactive -> active -> inactive`

Observed runtime:

- activation without persistent unlock is rejected;
- unlock does not activate automatically;
- deactivation removes only active state and disables form-sourced modifiers;
- deactivation preserves `PlayerFormUnlock`;
- mutually exclusive active forms sharing `exclusiveGroupUid` are rejected;
- deprecated form cannot be newly activated;
- reopen persistence tests preserve unlock/current-state separation.

Result: PASS.

## 5. Phase-5 derived/no-retrogression attacks

Phase 9 does not introduce a second race/bloodline/evolution/transformation resolver. `FormModifierBinding` materializes ordinary Phase-5 `Modifier` rows with source type `PHASE9_FORM`, and `sourceActive` follows form activation/deactivation.

Verified integration tests show:

- stat base remains unchanged while effective stat changes;
- resource `currentValue` remains unchanged while derived maximum changes;
- Skill `baseMastery` remains unchanged while effective mastery changes;
- Technique `baseMastery` remains unchanged while effective mastery changes;
- Talent remains unchanged;
- Potential remains unchanged;
- deactivation returns derived effective values to their base values without deleting unlock state.

Result: PASS.

## 6. Campaign / player / World Pack isolation

`Phase9Store` is campaign-scoped. Player rows use `(campaign_id, character_uid, ...)` identities and reads filter by campaign and character. `validatePlayer()` rejects cross-campaign state objects. Tests independently verify another player and another campaign cannot observe the stored state. World-Pack-owned definition registration rejects owner mismatch and legacy mapping target ownership mismatch.

Result: PASS for tested Phase-9 identity/state isolation boundaries.

## 7. Scale / authoritative truncation

Authoritative Phase-9 store reads iterate complete query result sets and contain no presentation `LIMIT` in the tested player-origin/feature/evolution/form authority paths. The Phase-9 persistence suite creates 1005 innate definitions and 1005 player-owned entries, verifies exact counts, closes/reopens, and verifies 1005 again.

Result: PASS.

## 8. Migration / production routing

Candidate `d796d374f92d94477542da5f753ee411b633076b` provides:

- `CurrentSchema.ensure()` -> `MigrationManager.ensureV9()`;
- `ensureV9()` -> `ensureV8()` -> prior chain;
- additive `RPGOS-9.0-INNATE-EVOLUTION` marker;
- idempotent `CREATE TABLE IF NOT EXISTS` / migration marker semantics;
- production campaign-switch V9 routing test;
- production restore V9 routing test;
- `PRAGMA integrity_check = ok`;
- clean `PRAGMA foreign_key_check` in the adopted test policy.

Result: PASS.

## 9. Exact CI evidence

GitHub Actions run:

- workflow: `Build & Release RPG OS ALPHA`
- run number: `#196`
- run id: `31349200549`
- head SHA: `d796d374f92d94477542da5f753ee411b633076b`
- status: `completed`
- conclusion: `success`

CI gate itself: PASS.

## 10. RELEASE BLOCKER — E-07 explicit evolution-transition bypass

The prepared WORK-040 matrix defines E-07:

> direct write of current stage to arbitrary target bypassing transition identity -> reject or be impossible through authoritative API.

The final candidate violates this invariant through the public authoritative API:

```kotlin
fun enterEvolutionPath(
    characterUid: String,
    stageUid: String,
    provenance: String,
    attainedChapter: Long? = null
)
```

`enterEvolutionPath()` accepts any existing `stageUid` and, when the character has no state on that path, directly inserts:

```text
player_evolution_states.current_stage_uid = supplied stageUid
```

and marks that stage attained. It does not require a `transitionUid`, does not verify a path-entry transition, and does not verify that the stage is a legal entry stage.

This is especially significant because the Phase-9 definition model already supports explicit path-entry transition identity:

```text
EvolutionTransitionDefinition.sourceStageUid: String?
```

where `null` can represent a path-entry source. However `transitionEvolution()` explicitly rejects source-null transitions:

```text
Entry transition cannot mutate an existing current stage; use enterEvolutionPath
```

and the alternative `enterEvolutionPath()` bypasses transition identity entirely.

### Reproducer

Given one path with two stages and no legal entry transition to the advanced stage:

```text
path P
stage A
stage B
```

on a character with no current state on P:

```kotlin
store.enterEvolutionPath("PLAYER", "B", "start")
```

succeeds and makes `B` both current and attained.

No explicit transition `ENTRY -> B` is required. Therefore an arbitrary stage can become authoritative current evolution state at initial path entry while bypassing the explicit transition graph.

The existing test does not catch this because it first calls `enterEvolutionPath(..., "A", ...)` and only then verifies that another direct `enterEvolutionPath(..., "B", ...)` fails after a current path state already exists. The adversarial case is choosing `B` as the *first* stage.

This is a reproducible violation of:

- WORK-040 E-07;
- the Phase-9 invariant that evolution state changes use explicit legal transitions;
- the semantic state oracle requirement that stage identity/order is not inferred or bypassed and legal movement is defined by explicit transition identity.

Under WORK-040 section 16, allowing an invalid transition/state change without explicit legal transition is an automatic FAIL condition.

## 11. Secondary risk observed but not required for this FAIL

`EvolutionTransitionDefinition.requirementRuleUid` and `FormDefinition.activationRuleUid` are persisted as generic bindings, while the direct store mutation methods do not evaluate those rules. This may be intentionally deferred to a future legal domain/rule caller, so this report does not independently classify it as an additional release blocker. The explicit E-07 transition-identity bypass above is sufficient and reproducible on the current Phase-9 authority API.

## 12. Final matrix summary

| Gate | Result |
|---|---|
| Definition stable UID / duplicate rejection | PASS |
| World Pack ownership / same-label separation | PASS |
| Clan/race/bloodline labels grant nothing automatically | PASS |
| Legacy explicit mapping / ambiguity / missing target | PASS |
| Unlock != active | PASS |
| Active form requires unlock | PASS |
| Deactivation preserves unlock | PASS |
| Mutually exclusive forms | PASS |
| Phase-5 derived integration / no base mutation | PASS |
| Resource current value no hidden mutation | PASS |
| Skill/Technique mastery no-retrogression | PASS |
| Talent/Potential no mutation | PASS |
| Campaign/player isolation | PASS |
| >1000 authoritative entries / reopen | PASS |
| V9 production routing | PASS |
| Phase 3–8 migration chain preservation | PASS on inspected/tested boundaries |
| Exact CI #196 for exact candidate SHA | PASS |
| **Explicit legal evolution transition identity / E-07** | **FAIL — BLOCKER** |

## 13. Final verdict

`PHASE 9 ADVERSARIAL VALIDATION: FAIL`

Reason: final candidate `d796d374f92d94477542da5f753ee411b633076b` exposes an authoritative `enterEvolutionPath(characterUid, stageUid, ...)` path that can set any stage as the initial current/attained stage without an explicit legal transition identity. This reproduces the matrix's E-07 automatic-fail condition.

No runtime correction was implemented by CHAT-5.