# WORK-20260816-015 — Phase 21–25 Invariant Bypass Fix

## Worker verdict

**FIX COMPLETE — READY FOR EXACT-SHA REVALIDATION**

This is a worker verdict only. It does not declare Phase 21–25 accepted.

## Starting state and freshness

- Failed runtime candidate: `aae30b60b6276ceea6113ade22f27836bda78b26`
- Starting master HEAD: `b9c97024fd95a5750d41730a78e73399a16dc33c`
- Initial drift from failed runtime candidate: docs-only audit commits. Comparison showed no runtime/schema/test drift.
- Final runtime/test candidate: `c028aa355d9b7e1663166a2fedb910c1a2dad795`
- A later concurrent commit `6582885ca7d3f4bf58a2b1a666ec4dae3e5a65e4` added only `docs/test-gm/TEST_GM_REPORT_2026-08-16_WITCHER_CAMPAIGN_02.md`; comparison against `c028aa35...` confirmed no runtime/schema/test drift. It does not replace the exact candidate.

## Blocking defect

Both independent audits identified the same Phase-22 contract violation:

- CHAT-4 / `P21-25-C4-001`
- CHAT-5 / `P21-25-CB-01`
- unified blocker: `P21-25-INVARIANT-BYPASS-01`

Before the fix, normal `PlayerDomainEngine.resolve(...)` assembled and structurally validated a `PlayerChangeSet` and could return `PlayerResolutionOutcome.Resolved` without invoking `PlayerInvariantValidator`. A separate extension, `resolveWithPlayerInvariants(...)`, called `resolve(...)` and then performed invariant validation. This exposed two semantic resolution paths and allowed callers of the canonical API to bypass Phase-22 no-retrogression invariants.

## API surface before / after

### Before

- `PlayerDomainEngine.resolve(command, context)` — normal path, no PlayerInvariantValidator.
- `PlayerDomainEngine.resolveWithPlayerInvariants(command, context, snapshotResolver)` — optional validating wrapper.

### After

- `PlayerDomainEngine.resolve(command, context)` is the single canonical proposal-return path and always performs PlayerInvariantValidator after structural `PlayerChangeSet` validation.
- `resolveWithPlayerInvariants(...)` was removed.
- `PlayerDomainEngine` receives the existing read-only `PlayerInvariantSnapshotResolver` as an internal constructor dependency, defaulting to an empty immutable snapshot resolver.
- There is no public/runtime-supported alternate PlayerDomainEngine resolution method that returns a normal proposal while bypassing player invariants.

No production caller was changed to another semantic API; enforcement moved into the canonical engine boundary itself.

## Canonical resolution pipeline after fix

The retained ordering is:

1. command validation/canonicalization
2. command reference validation
3. pinned World Pack authority validation
4. one `COMMAND_PRECHECK`
5. domain/component resolution
6. base draft reference validation
7. Phase-20/21 progression augmentation
8. augmented reference closure
9. one final `DRAFT_EFFECT_CHECK`
10. `PlayerChangeSet` construction
11. structural `PlayerChangeSetValidator.validate(...)`
12. immutable `PlayerInvariantSnapshot` resolution
13. `PlayerInvariantValidator.validate(...)`
14. final `Resolved` or `Rejected` outcome

The fix did not add a second WorldRuleProvider check and did not reorder Phase-19/20/21 semantics.

## Snapshot / authority boundary

The validator consumes the already implemented immutable `PlayerInvariantSnapshot` through `PlayerInvariantSnapshotResolver`. The engine does not derive invariant truth from `CharacterPanelSnapshotV2` or `PlayerSnapshotBuilder` profiles and does not introduce a new state store or writer. Snapshot read failures fail closed as `PLAYER_INVARIANT_SNAPSHOT_READ_FAILED`.

## Legal negative-change semantics preserved

The fix does not reject all negative values.

Still legal under Phase-22 semantics:

- resource consumption;
- inventory/equipment removal;
- temporary/runtime/derived decreases;
- explicitly authorized durable stat/skill/technique regression with matching typed `DurableRegressionAuthorization`.

Still rejected by default:

- unexplained durable negative Stat/Skill/Technique progression.

The canonical path maps invariant rejection to `PlayerResolutionRejectionReason.DOMAIN_REJECTED` with the invariant detail such as `UNEXPLAINED_DURABLE_PROGRESSION_REGRESSION`.

## Files changed

Production:

- `app/src/main/java/com/rpgos/app/PlayerDomainEngine.kt`
- `app/src/main/java/com/rpgos/app/PlayerInvariantValidator.kt`

Tests:

- `app/src/test/java/com/rpgos/app/Phase22PlayerInvariantValidatorTest.kt`

Exact diff from starting `b9c97024...` to candidate `c028aa35...`: only these three files.

## Tests added / changed

`Phase22PlayerInvariantValidatorTest` now proves through the normal `PlayerDomainEngine.resolve(...)` API that:

- unexplained durable negative stat progression cannot escape as `Resolved`;
- the rejection carries `UNEXPLAINED_DURABLE_PROGRESSION_REGRESSION`;
- the same canonical path accepts the equivalent durable regression when a matching typed injury `DurableRegressionAuthorization` is supplied via the immutable resolver;
- legal negative `ResourceChange` remains `Resolved` through the same canonical path.

Existing Phase-22 standalone tests for Skill/Technique regression, resource/inventory/equipment negatives, runtime decreases, campaign mismatch and snapshot determinism remain in the suite.

An intermediate candidate `62554aa33774c987be373a6bf8e600b7fe89d691` failed CI run #606 only because the new legal-resource test referenced a nonexistent command-kind constant. Production Kotlin compilation succeeded. The fixture was corrected to use the existing canonical `TRAIN` command/component while emitting the legal negative ResourceChange; no runtime behavior was changed to address that test error.

## Regression status

Final exact candidate `c028aa355d9b7e1663166a2fedb910c1a2dad795` ran the repository's normal full JVM unit suite, including accumulated Phase 19–25 tests.

Validated in the final workflow:

- project validation: PASS
- Phase-22 invariant tests: PASS
- new canonical bypass regression: PASS
- legal-negative canonical-path regression: PASS
- Phase-21 tests: PASS
- Phase-20 progression regressions: PASS
- Phase-19 authority regressions: PASS
- Phase-23 provenance tests: PASS
- Phase-24 CharacterPanelSnapshotV2 tests: PASS
- Phase-25 PlayerSnapshotBuilder/profile tests: PASS
- full `:app:testDebugUnitTest`: PASS
- signed validation APK build: PASS
- immutable validation artifact preparation/upload: PASS

## CI

Final exact candidate SHA: `c028aa355d9b7e1663166a2fedb910c1a2dad795`

GitHub Actions:

- workflow: `Validate RPG OS ALPHA`
- run number: `#607`
- run ID: `31968919354`
- status: completed
- conclusion: success

## Schema / migration

**Schema delta: NONE.**

**Migration delta: NONE.**

No persisted invariant store, unified ledger, transaction layer or second player-state authority was introduced.

## Scope confirmation

No Phase-21, Phase-23, Phase-24 or Phase-25 behavior was altered except that all normal PlayerDomainEngine proposal returns now pass the already-defined Phase-22 validator.

Not implemented:

- Phase 26 Single Truth Mutation Path enforcement;
- TurnTransaction;
- commit/repository mutation enforcement;
- global idempotency/retry;
- Event Store or snapshot redesign;
- frontend or unrelated cleanup.

Phase 26 was not started.

## Exact SHA for independent revalidation

CHAT-4 and CHAT-5 must both revalidate exactly:

`c028aa355d9b7e1663166a2fedb910c1a2dad795`

Later docs-only commits do not transfer or replace this exact runtime/test candidate identity.
