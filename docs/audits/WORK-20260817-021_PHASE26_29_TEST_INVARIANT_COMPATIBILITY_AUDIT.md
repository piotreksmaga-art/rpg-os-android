# WORK-20260817-021 — Phase 26–29 Test / Invariant / Compatibility Audit

## 1. Audit identity

- **Work ID:** `WORK-20260817-021`
- **Role:** CHAT-4 — independent test / invariant / compatibility reviewer
- **Mode:** READ-ONLY RUNTIME; evidence-only report commit permitted
- **Repository:** `piotreksmaga-art/rpg-os-android`
- **Branch:** `master`
- **Exact runtime SHA audited:** `2eba3b2f14cf085b00f8187b6e5eb57d8e1991b5`
- **Implementation work used as context only:** `WORK-20260817-020`
- **Accepted Phase-25 baseline used for drift context:** `c028aa355d9b7e1663166a2fedb910c1a2dad795`
- **Master observed before this evidence-only report write:** `fab02c16b17321e6162aa9d775ad4e3cb9f3199d`
- **Exact candidate CI independently verified:** `Validate RPG OS ALPHA` #642, run ID `32010700796`, `head_sha=2eba3b2f14cf085b00f8187b6e5eb57d8e1991b5`, `completed / success`.

This audit applies only to runtime/schema/test semantics represented by `2eba3b2f14cf085b00f8187b6e5eb57d8e1991b5`. Later documentation commits were used only as context and do not replace the audited runtime.

## 2. Final verdict

**FAIL — FIX REQUIRED BEFORE PHASE 26–29 ACCEPTANCE**

The candidate contains working transaction/idempotency/recovery primitives, but it does not satisfy the required Phase-26 Single Truth Mutation Path enforcement and contains an upgrade-breaking Phase-29 receipt-schema compatibility defect. Both are acceptance blockers.

No Phase 30 work is authorized or declared complete by this report.

## 3. Repository / drift analysis

Comparison of accepted Phase-25 runtime `c028aa355...` to candidate `2eba3b2f...` shows the Group-A runtime delta concentrated in:

- `CampaignMutationBoundary.kt`
- `StatePatchEngine.kt`
- `TurnTransaction.kt`
- `TurnTransactionReceiptStore.kt`
- `InventoryStore.kt`
- `OwnershipStore.kt`
- Phase-26/27/28/29 tests

plus documentation/acceptance/test-gm material.

Current master at audit time was `fab02c16...`, two commits ahead of the candidate. The candidate-to-master compare contains only:

- `docs/audits/WORK-20260817-020_PHASE26_29_TRANSACTION_INTEGRITY_IMPLEMENTATION.md`
- `docs/test-gm/TEST_GM_REPORT_2026-08-17_WITCHER_PROGRESSION_ORCHESTRATION.md`

Therefore there is **no later runtime/schema/migration/test drift** after the exact audited SHA.

## 4. Sources inspected

Required context read:

- `docs/audits/WORK-20260817-018_PHASE26_36_CONTRACT_ARCHITECTURE_GROUPING_AUDIT.md`
- `docs/audits/WORK-20260817-019_PHASE26_36_INTEGRITY_MIGRATION_ADVERSARIAL_AUDIT.md`
- `docs/audits/WORK-20260817-020_PHASE26_29_TRANSACTION_INTEGRITY_IMPLEMENTATION.md`

Exact candidate runtime/tests inspected:

- `CampaignMutationBoundary.kt`
- `StatePatchEngine.kt`
- `TurnTransaction.kt`
- `TurnTransactionReceiptStore.kt`
- `InventoryStore.kt`
- `OwnershipStore.kt`
- accepted `PlayerDomainEngine.kt` proposal/outcome surface
- `Phase26MutationBoundaryTest.kt`
- `Phase27TurnTransactionTest.kt`
- `Phase28TurnIdempotencyTest.kt`
- `Phase29CrashRecoveryTest.kt`

Historical Phase-28 receipt schema at checkpoint `9ceab0345f487fe9fceecf87a684bfcb84dd8144` was also inspected to test the real G28 -> G29 upgrade path rather than only fresh-database behavior.

## 5. Findings summary

| ID | Severity | Gate | Result |
|---|---|---|---|
| `P26-C4-001` | **BLOCKER** | G26 | Canonical mutation capability can be fabricated/bypassed by ordinary same-module production callers; `PlayerResolutionOutcome.Resolved` is public, `CanonicalCampaignMutationProposal.create` is module-internal, and `TurnTransaction` itself has a module-internal direct constructor. |
| `P26-C4-002` | **BLOCKER** | G26/G27 | Authoritative typed stores remain directly writable and self-committing outside the canonical boundary; therefore Single Truth Mutation Path is not enforced globally. |
| `P29-C4-001` | **BLOCKER** | G29 / migration | A real database upgraded from the Phase-28 receipt schema retains `CHECK(receipt_version = 1)`, while G29 writes receipt version 2. The migration adds `commit_order` but never rebuilds the old CHECK constraint, so post-upgrade committed turns can fail. |
| `P28-C4-001` | MEDIUM | G28 | Existing tests are sequential and do not execute a true two-connection concurrent retry race. The implementation performs an in-transaction replay recheck, which is directionally correct, but concurrent behavior is not directly regression-locked. This is not the reason for FAIL because stronger blockers already exist. |

## 6. G26 — Single Truth Mutation Path

### 6.1 Generic StatePatch

**PASS for the specific generic StatePatch bypass.**

`StatePatchEngine.apply(...)` now unconditionally returns a failed `PatchResult` with zero applied operations and `RPGOS-MUTATION-GATE:GENERIC_STATE_PATCH_NOT_AUTHORIZED`. The former generic SQL gameplay path is therefore fail-closed.

### 6.2 CampaignMutationBoundary design

The new boundary has useful classification semantics:

- `GAMEPLAY_AUTHORITATIVE`
- `ADMINISTRATIVE_MIGRATION_INSTALL_RECOVERY`
- `DERIVED_CACHE_PRESENTATION`
- `APPEND_ONLY_COMMIT_EVIDENCE`

and cross-campaign admission is explicitly rejected.

However the capability is **not unforgeable at the production-module boundary**.

`PlayerResolutionOutcome` is a public sealed interface and `Resolved` is a public data-class constructor:

```kotlin
sealed interface PlayerResolutionOutcome {
    data class Resolved(val proposal: PlayerChangeSet, val evidence: PlayerResolutionEvidence) : PlayerResolutionOutcome
    ...
}
```

`CampaignMutationBoundary.admitPlayerProposal(...)` checks only that the supplied object is a `Resolved` and that `proposal.campaignUid` matches the expected campaign. It has no proof that this `Resolved` instance came from `PlayerDomainEngine.resolve()`.

Therefore an ordinary production caller can construct a structurally valid `PlayerChangeSet`, construct a `PlayerResolutionOutcome.Resolved` manually, and obtain `CampaignMutationAdmission.Accepted` without running Phase-19 WorldRule checks, Phase-20 progression integration, or mandatory Phase-22 invariant validation.

This directly violates the required invariant that a successful gameplay mutation cannot bypass the validated PlayerDomainEngine proposal path.

### 6.3 Direct canonical-envelope and TurnTransaction construction

The problem is stronger than the public `Resolved` constructor:

- `CanonicalCampaignMutationProposal.create(...)` is `internal`, which is accessible to ordinary production Kotlin code in the same app module.
- `TurnTransaction` has an `internal` constructor and accepts an identity-only fallback semantic fingerprint.
- The Phase-27/28/29 tests themselves create `CanonicalCampaignMutationProposal` directly rather than proving that every transaction candidate is necessarily issued by `CampaignMutationBoundary` from a real engine result.

`internal` is a module visibility boundary, not a capability boundary. It does not meet the stated requirement that ordinary production callers cannot obtain mutation capability to bypass the canonical path.

### 6.4 Direct typed writers remain live

`InventoryStore` and `OwnershipStore` still expose public authoritative mutation methods and their local `tx` helper behaves as:

```text
if outer DB transaction exists -> join it
else -> begin and commit a new local transaction
```

Examples include inventory add/remove/transfer and ownership acquire/transfer/close. Thus a production caller can mutate authoritative gameplay state directly without obtaining a `CanonicalCampaignMutationProposal` or entering `TurnTransactionBoundary` at all.

This violates the pre-audit rule that every authoritative gameplay writer must either be transaction-capability gated, explicitly non-gameplay administrative, derived/cache/presentation, or legacy read-only evidence. A remaining category of “trusted direct writer” means Phase 26 is not complete.

### G26 result

**FAIL — BLOCKED BY `P26-C4-001` and `P26-C4-002`.**

Administrative capability types are explicitly distinguishable, and no second Player Engine was introduced, but the canonical mutation path is not enforceable as the only authoritative gameplay path.

## 7. G27 — TurnTransaction / atomicity

### Positive evidence

When `TurnTransaction` is actually used as intended:

- nested outer `TurnTransaction` is rejected with `check(!db.inTransaction())`;
- one SQLite transaction owns the execution block;
- failure after first write rolls back;
- failure after multiple writes rolls back all writes;
- failure before commit rolls back;
- receipt append occurs before `setTransactionSuccessful()` and therefore joins the same outer transaction;
- inventory and ownership local transaction helpers join an already-active transaction instead of nesting another commit.

The Phase-27 and Phase-29 failure-injection tests meaningfully exercise rollback after one and multiple writes.

### Boundary defect interaction

The atomicity mechanism does **not** repair G26. `TurnTransactionScope.authoritativeWrite` exposes the raw `SQLiteDatabase` and allows arbitrary SQL unrelated to the admitted `PlayerChangeSet`. Even with a legitimate benign proposal, a caller can commit arbitrary authoritative writes inside the transaction unless higher layers bind execution to the proposal.

Further, typed stores remain independently self-committing when invoked outside a TurnTransaction.

Thus `FAILED TURN -> NO PARTIAL COMMITTED REALITY` is demonstrated for writes actually routed through the outer transaction, but the repository does not yet enforce that all gameplay-authoritative writes participate in that boundary.

### G27 result

**Transaction primitive: PASS in isolation. Global gate: FAIL transitively because G26 does not force authoritative gameplay writes through it.**

## 8. G28 — durable global idempotency

### Positive evidence

`turn_transaction_receipts` is durable DB evidence and is appended inside the active outer transaction. Replay validates:

- transaction UID;
- campaign binding;
- turn/command identity for same transaction UID;
- semantic fingerprint;
- campaign-scoped command UID replay;
- same command + same semantics -> `AlreadyCommitted`;
- same command + changed semantics -> fail closed;
- transaction UID reuse across campaigns -> fail closed.

Rollback leaves no receipt because authoritative effects and receipt are in the same SQLite transaction. Tests cover exact retry, same-command/new-transaction retry, retry after rollback, semantic conflict, cross-campaign collision, inventory, finance, ownership, and DB reopen.

The pre-transaction replay check is repeated after `beginTransaction()`, which is the correct shape for closing a sequential admission/commit TOCTOU window on a shared SQLite database.

### Remaining test gap

No inspected test executes two competing database connections/threads attempting the same semantic command concurrently. The unique constraints and in-transaction replay recheck provide meaningful protection, but the concurrency property is not directly regression-locked.

### G28 result

**No independent acceptance blocker found in the core replay algorithm. MEDIUM test-coverage finding only.**

## 9. G29 — crash recovery / LAST VALID COMMIT

### Positive fresh-database semantics

For a fresh G29 schema:

- `commit_order` is campaign-scoped and positive;
- `(campaign_uid, commit_order)` is unique;
- `nextCommitOrder()` is evaluated while the outer transaction is active;
- only rows with `commit_state='COMMITTED'` participate in replay/recovery queries;
- no durable `IN_PROGRESS` row exists;
- failed/rolled-back turns leave no committed receipt;
- `lastValidCommit` is ordered by `commit_order`, not timestamps, UIDs, files, snapshots or narrative state;
- process reopen recovers receipts and lost-response retry returns `AlreadyCommitted`.

The crash matrix tests correctly demonstrate that failed later turns do not replace the last valid commit.

### BLOCKER: real G28 -> G29 upgrade breaks receipt version constraint

The Phase-28 checkpoint schema created:

```sql
receipt_version INTEGER NOT NULL CHECK(receipt_version = 1)
```

G29 changes the Kotlin receipt version constant to `2` and the fresh-table DDL to:

```sql
receipt_version INTEGER NOT NULL CHECK(receipt_version IN (1,2))
```

but `ensureSchema()` uses `CREATE TABLE IF NOT EXISTS`. On an existing Phase-28 database, SQLite does not replace the old table definition or its CHECK constraint. G29 then only detects/adds the `commit_order` column and creates indexes/markers. It never rebuilds the table to widen the `receipt_version` CHECK.

After that migration, `appendCommitted(...)` attempts to insert `receipt_version=2`, which violates the still-active Phase-28 `CHECK(receipt_version = 1)` constraint.

Consequences:

- an actual campaign that already produced Phase-28 committed receipts can be migrated far enough for recovery reads/backfill, but new G29 commits may fail at receipt insertion;
- because the receipt insert is in the outer transaction, the turn should roll back rather than partially commit, but the upgraded campaign becomes unable to commit normally;
- fresh-database tests do not expose this defect.

This violates the required old-campaign/migration compatibility contract and is an acceptance blocker.

### G29 result

**FAIL — BLOCKED BY `P29-C4-001`.**

## 10. Schema / migration result

Observed additive structures:

- `turn_transaction_receipts`
- durable transaction/campaign/turn/command/semantic/result identities
- `commit_order`
- `(campaign_uid, commit_order)` unique index
- migration markers:
  - `RPGOS-28.0-TURN-IDEMPOTENCY`
  - `RPGOS-29.0-CRASH-RECOVERY`

No pre-Phase-28 transaction history is fabricated. Existing G28 rows are treated as proven committed evidence and are assigned per-campaign order; campaigns with no receipts remain not recorded.

However schema compatibility is **not acceptable** because the old receipt-version CHECK constraint is not migrated. `P29-C4-001` must be fixed and covered by an explicit real Phase-28-schema -> G29-schema upgrade test.

## 11. Regression / boundary check

The candidate diff does not modify `PlayerDomainEngine`, `ProgressionEngine`, Phase-21 hooks, Phase-23 provenance envelopes, `CharacterPanelSnapshotV2`, or `PlayerSnapshotBuilder`.

Therefore no concrete new regression evidence was found for:

- Phase-19 pinned WorldRuleProvider authority;
- one final `DRAFT_EFFECT_CHECK`;
- Phase-20 proposal-only deterministic progression;
- mandatory Phase-22 invariant validation inside canonical `PlayerDomainEngine.resolve()`;
- Phase-23 provenance authority boundaries;
- Phase-24 derived presentation;
- Phase-25 derived projections;
- FACT/BELIEF/NARRATIVE separation.

The Group-A runtime diff contains no Phase-30 Event Store implementation. Phase 30 was not started by the inspected candidate.

The important caveat is that G26 currently allows callers to bypass the accepted PlayerDomainEngine proposal path entirely, so those preserved contracts can be skipped by an alternative writer even though their own implementations were not regressed.

## 12. CI verification

GitHub Actions run independently inspected:

- workflow: `Validate RPG OS ALPHA`
- run number: `#642`
- run ID: `32010700796`
- exact `head_sha`: `2eba3b2f14cf085b00f8187b6e5eb57d8e1991b5`
- status: `completed`
- conclusion: `success`

The job shows successful project validation, JVM unit tests, signed validation APK build and immutable artifact upload.

Green CI does not overturn the findings because:

1. Phase-26 tests prove that a real engine result can be admitted and that the constructor of `CanonicalCampaignMutationProposal` is not public, but do not prove that module-internal production code cannot call the `internal` factory or direct `TurnTransaction` constructor, nor do they attempt a forged public `PlayerResolutionOutcome.Resolved`.
2. Phase-27/28/29 tests directly create canonical proposals using the `internal` factory, demonstrating that same-module code can bypass the intended admission boundary.
3. Phase-29 tests create fresh databases and do not migrate a real Phase-28 receipt table with `CHECK(receipt_version = 1)` before attempting a G29 commit.

## 13. Required fixes before acceptance review

### `P26-C4-001` — BLOCKER

Make mutation admission/capability issuance cryptographically/type-system/module-boundary unforgeable for ordinary production callers. A caller must not be able to synthesize a successful engine outcome or canonical proposal and then enter the gameplay commit path without the accepted validated resolution pipeline.

### `P26-C4-002` — BLOCKER

Close or capability-gate all gameplay-authoritative typed write surfaces so they cannot independently commit outside the canonical mutation/TurnTransaction boundary. Administrative/migration/install/recovery paths must remain explicitly separate.

### `P29-C4-001` — BLOCKER

Implement an actual compatible G28 -> G29 receipt-table migration that widens/rebuilds the `receipt_version` constraint while preserving proven committed G28 rows, then regression-test the upgrade with an existing V1 receipt followed by a successful new V2 committed turn.

### `P28-C4-001` — MEDIUM

Add a true concurrent/two-connection same-command retry regression test to lock the intended TOCTOU behavior. This is recommended but is not the primary reason for FAIL.

## 14. Final verdict

**FAIL — FIX REQUIRED BEFORE PHASE 26–29 ACCEPTANCE**

This verdict applies **ONLY** to exact runtime SHA:

`2eba3b2f14cf085b00f8187b6e5eb57d8e1991b5`

This report does **not** declare Phase 26–29 accepted and does **not** authorize Phase 30.
