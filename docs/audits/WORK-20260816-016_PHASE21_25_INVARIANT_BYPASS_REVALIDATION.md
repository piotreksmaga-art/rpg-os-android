# WORK-20260816-016 — Phase 21–25 Invariant Bypass Exact-SHA Revalidation

## Audit identity

- **Work ID:** `WORK-20260816-016`
- **Role:** CHAT-4 — independent test / invariant / compatibility reviewer
- **Mode:** READ-ONLY RUNTIME; evidence-only report commit permitted
- **Repository:** `piotreksmaga-art/rpg-os-android`
- **Exact runtime SHA audited:** `c028aa355d9b7e1663166a2fedb910c1a2dad795`
- **Previous failed SHA:** `aae30b60b6276ceea6113ade22f27836bda78b26`
- **Fix work used as context only:** `WORK-20260816-015`
- **Previous blocker:** `P21-25-C4-001` / `P21-25-INVARIANT-BYPASS-01`
- **Current master observed before this evidence-only report:** `b95c253d631c446042f83b64d0e944a9bf4e74e2`

This revalidation applies only to runtime semantics represented by `c028aa355d9b7e1663166a2fedb910c1a2dad795`. Later documentation-only commits are not substituted for the audited runtime.

## Final verdict

**PASS — P21-25-INVARIANT-BYPASS-01 FIX VERIFIED — READY FOR COORDINATOR ACCEPTANCE REVIEW**

This PASS applies **ONLY** to:

`c028aa355d9b7e1663166a2fedb910c1a2dad795`

This report does not declare Phase 21–25 accepted. Global acceptance remains coordinator-owned.

## Drift / exact-SHA analysis

Comparison from failed runtime `aae30b60b6276ceea6113ade22f27836bda78b26` to exact fixed candidate `c028aa355d9b7e1663166a2fedb910c1a2dad795` shows the scoped runtime/test delta in:

- `app/src/main/java/com/rpgos/app/PlayerDomainEngine.kt`
- `app/src/main/java/com/rpgos/app/PlayerInvariantValidator.kt`
- `app/src/test/java/com/rpgos/app/Phase22PlayerInvariantValidatorTest.kt`

The compare range also contains intervening evidence/documentation commits, but no unrelated runtime family was modified.

Current master at audit time was `b95c253d631c446042f83b64d0e944a9bf4e74e2`, two commits ahead of the candidate. Exact comparison from `c028aa35...` to that master contains only:

- `docs/audits/WORK-20260816-015_PHASE21_25_INVARIANT_BYPASS_FIX.md`
- `docs/test-gm/TEST_GM_REPORT_2026-08-16_WITCHER_CAMPAIGN_02.md`

Therefore there is no post-candidate runtime/schema/test drift affecting this exact-SHA audit.

## Primary blocker revalidation

### 1. Canonical `resolve(...)` now owns invariant validation — PASS

`PlayerDomainEngine` now receives `PlayerInvariantSnapshotResolver` as an internal constructor dependency, defaulting to a read-only empty snapshot resolver.

The normal public proposal-returning API remains:

`PlayerDomainEngine.resolve(command, context)`

For a resolved component path it now executes the complete sequence:

1. base draft reference closure;
2. Phase-20/21 progression augmentation;
3. augmented draft reference closure;
4. exactly one final `DRAFT_EFFECT_CHECK`;
5. `PlayerChangeSet` assembly;
6. structural `PlayerChangeSetValidator.validate(...)`;
7. invariant snapshot resolution;
8. `PlayerInvariantValidator.validate(...)`;
9. only then `PlayerResolutionOutcome.Resolved` or invariant rejection.

The previous defect — returning a structurally valid proposal before PlayerInvariantValidator — is no longer present.

### 2. Ordering relative to WorldRuleProvider and structural validation — PASS

The inspected implementation shows one final `DRAFT_EFFECT_CHECK` over the augmented draft before proposal assembly. Only after that check is allowed does the engine assemble `PlayerChangeSet`, invoke `PlayerChangeSetValidator.validate(proposal, changeRegistry)`, obtain immutable invariant evidence, and invoke `PlayerInvariantValidator`.

Thus the Phase-22 gate is after Phase-19/20 legality/reference work and after structural validation, as required.

### 3. Old optional alternate path removed — PASS

`resolveWithPlayerInvariants(...)` was removed from `PlayerInvariantValidator.kt`. Repository search for `resolveWithPlayerInvariants` returned no results.

The previous two-path semantic API no longer exists.

### 4. Normal successful proposal bypass — NOT FOUND

Within the inspected production `PlayerDomainEngine` API, normal successful proposal creation reaches `Resolved` only through `validatePlayerInvariants(...)`. Component resolution returns internal drafts, not public `PlayerChangeSet` proposals. The constructor and component extension points are internal Core surfaces.

No alternative production proposal-return path equivalent to the previous optional wrapper was found.

This Phase-22 fix does not claim Phase-26 Single Truth Mutation Path enforcement; it closes the PlayerDomainEngine resolution bypass only.

## Negative progression / legal regression matrix

| Case | Canonical `resolve(...)` result | Audit result |
|---|---|---|
| unexplained durable negative Stat | `Rejected`, `DOMAIN_REJECTED`, detail `UNEXPLAINED_DURABLE_PROGRESSION_REGRESSION` | PASS |
| unexplained durable negative Skill | validator classifies as durable regression; existing standalone regression test retained | PASS |
| unexplained durable negative Technique | validator classifies as durable regression; existing standalone regression test retained | PASS |
| equivalent negative Stat with matching typed `DurableRegressionAuthorization` | `Resolved` | PASS |
| negative ResourceChange | `Resolved` | PASS |
| inventory/equipment removal | not classified as durable progression regression | PASS |
| runtime/derived-like negative delta | not classified as durable progression regression | PASS |

The authorization matching remains campaign/character/change/target/rule bound. The fix did not weaken no-retrogression semantics to make legal negative-resource tests pass.

## New bypass regression test

`Phase22PlayerInvariantValidatorTest.P22_09_canonicalResolveRejectsUnexplainedAndAcceptsTypedCause` now invokes ordinary `PlayerDomainEngine.resolve(...)` directly twice:

- with the default empty invariant snapshot it requires rejection of an unexplained negative stat proposal;
- with a matching immutable typed injury authorization resolver it requires successful resolution of the same semantic regression.

`P22_10_canonicalResolveKeepsLegalNegativeResourceChange` independently proves that canonical invariant enforcement is not a blanket negative-delta ban.

These tests directly cover the bypass that `WORK-20260816-013` identified; unlike the previous test, they do not rely on an optional wrapper.

## Regression checks

### Phase 19 — PASS

The fix retains the existing WorldRuleProvider ordering and authority model. The resolved path still performs progression/reference augmentation before one final `DRAFT_EFFECT_CHECK`; no second final effect check was introduced and the invariant validator runs afterward. No provider rebinding or alternate World Pack authority path was introduced by the scoped diff.

### Phase 20 — PASS

No `ProgressionEngine` runtime file was changed by the fix. Progression remains proposal-only and deterministic. The previously verified P20-C4-001 factor canonicalization implementation is untouched.

### Phase 21 — PASS

`Phase21ProgressionPolicy.kt` is outside the fix delta. Diminishing-return factors and passive hooks remain unchanged and still feed the existing progression path before final WorldRule effect validation.

### Phase 23 — PASS

`PlayerLedgerProvenance.kt` is outside the fix delta. Proposal/committed-family distinction, forward-only evidence refs, finance authority, ownership authority and legacy `UNKNOWN_NOT_RECORDED` semantics are unchanged.

### Phase 24 — PASS

`CharacterPanelSnapshotV2.kt` is outside the fix delta. It remains a rebuildable `DERIVED_PRESENTATION` read model and is not used as invariant authority.

### Phase 25 — PASS

`PlayerSnapshotBuilder.kt` is outside the fix delta. All six profiles remain derived projections. `GM_CONTEXT` FACT/BELIEF/NARRATIVE typing is unchanged.

### Phase 26 boundary — PASS

No Single Truth Mutation Path, TurnTransaction, global commit enforcement, global retry/idempotency, event-store redesign or transaction/snapshot architecture was introduced. The new invariant resolver is a read-only dependency at PlayerDomainEngine resolution boundary, not a Phase-26 mutation architecture.

## Schema / migration

No schema or migration file appears in the fix delta. **Schema delta: NONE. Migration delta: NONE.**

## CI / regression evidence

Exact-SHA GitHub Actions was independently verified:

- workflow: `Validate RPG OS ALPHA`
- run number: `#607`
- run ID: `31968919354`
- `head_sha=c028aa355d9b7e1663166a2fedb910c1a2dad795`
- status: `completed`
- conclusion: `success`

Workflow job inspection independently confirms successful steps including:

- Validate project
- Run JVM unit tests
- Build signed validation APK
- Prepare immutable validation artifact
- Upload immutable Actions artifact

Green CI was treated as supporting evidence, not as a substitute for semantic inspection.

## Findings

### Blocking findings

**NONE.**

`P21-25-INVARIANT-BYPASS-01` / `P21-25-C4-001` is verified fixed for the exact candidate.

### Non-blocking/deferred

Phase 26+ remains responsible for broader Single Truth Mutation Path / transaction / commit enforcement. This audit does not infer that all future mutation paths are globally guarded merely because `PlayerDomainEngine.resolve(...)` is now invariant-safe.

## Final verdict

**PASS — P21-25-INVARIANT-BYPASS-01 FIX VERIFIED — READY FOR COORDINATOR ACCEPTANCE REVIEW**

PASS is explicitly and exclusively bound to runtime SHA:

`c028aa355d9b7e1663166a2fedb910c1a2dad795`

No Phase 21–25 global acceptance is declared by CHAT-4.
