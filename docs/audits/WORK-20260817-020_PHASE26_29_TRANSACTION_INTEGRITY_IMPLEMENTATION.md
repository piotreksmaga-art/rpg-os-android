# WORK-20260817-020 — Phase 26–29 Transaction Integrity Implementation

Status: implementation evidence only. This document does **not** mark Phase 26–29 accepted and does not authorize Phase 30.

## Final runtime candidate

`2eba3b2f14cf085b00f8187b6e5eb57d8e1991b5`

This is the runtime SHA to be independently post-audited by CHAT-4 / CHAT-5. The commit containing this report is docs-only and is not the runtime candidate.

## Gate evidence

### G26 — canonical mutation boundary

PASS checkpoint: `cddb02bdc5203e6313a3e2a572b2dd29e352c3d7`.

Established `CampaignMutationBoundary` as the canonical gameplay mutation path and kept generic StatePatchEngine gameplay bypass closed. Validated proposals remain the input to authoritative mutation.

### G27 — TurnTransaction atomicity

PASS checkpoint: `8d1808190a25c1b6b93b5e19a86bc60c9ef0ca2c`.

Established one outer SQLite `TurnTransaction` owning gameplay atomicity. Finance, ownership, inventory and other participating stores join that transaction; failed multi-domain execution rolls back as a unit. Existing domain authority and invariants remain intact.

CI: Validate RPG OS ALPHA #635, run `32007622711`, completed/success.

### G28 — global retry / idempotency

PASS checkpoint: `9ceab0345f487fe9fceecf87a684bfcb84dd8144`.

Added durable append-only `turn_transaction_receipts` commit evidence at the TurnTransaction boundary. Receipt insertion joins the same outer transaction as gameplay effects. Replay is keyed by durable transaction identity and campaign-scoped command identity, with canonical `PlayerChangeSetCodec.fingerprint` semantic fingerprinting. Exact committed retry returns `AlreadyCommitted`; semantic mismatch and cross-campaign transaction UID reuse fail closed. Rollback leaves no committed dedupe row. Inventory stack rewards therefore cannot duplicate after commit-success/response-loss retry without adding an inventory-specific replay engine.

Schema marker: `RPGOS-28.0-TURN-IDEMPOTENCY`.

CI: Validate RPG OS ALPHA #639, run `32009396328`, completed/success. Final G28 freshness matched the exact checkpoint.

### G29 — crash recovery / LAST VALID COMMIT

Final runtime candidate: `2eba3b2f14cf085b00f8187b6e5eb57d8e1991b5`.

G29 extends the G28 receipt authority rather than creating a competing recovery ledger. `TurnCommitReceipt` now carries `commitOrder`. Ordering is:

- campaign-scoped;
- durable;
- positive and monotonic;
- unique within a campaign;
- allocated while the authoritative outer SQLite write transaction is active;
- independent of wall clock, filesystem timestamps, UI/narrative state and UID lexical order.

`TurnRecoveryReader` is read-oriented and answers:

- last valid committed transaction for a campaign;
- whether a transaction UID has committed evidence;
- whether a campaign/command UID has committed evidence;
- the immutable receipt identifying the commit.

There is deliberately no durable pre-commit / IN_PROGRESS marker. Post-upgrade recovery has two durable evidence outcomes: a COMMITTED receipt exists, or no committed transaction metadata is recorded. Process-local `TurnTransactionState` may distinguish an executing/rolled-back call while alive, but recovery after process death never guesses that state from partial metadata. SQLite transaction rollback/reopen semantics determine whether effects and receipt became durable.

Schema delta is additive to the existing receipt infrastructure: `commit_order` plus a unique `(campaign_uid, commit_order)` index and migration marker `RPGOS-29.0-CRASH-RECOVERY`. Existing Phase-28 receipt rows are already proven committed evidence and receive deterministic per-campaign ordering during upgrade; no transaction records are fabricated for pre-Phase-28 campaign history. For campaigns without a receipt, historical transactional metadata remains unknown/not recorded until the first real post-upgrade committed TurnTransaction.

## Crash matrix evidence

G29 tests deterministically cover:

- crash/failure before first authoritative write / before useful transaction work: zero committed effect and unchanged LAST VALID COMMIT;
- failure after first write: full rollback;
- failure after multiple writes: full rollback;
- failure before receipt finalization: no committed turn;
- receipt/effects transaction abort: no durable receipt/effect survives;
- successful commit followed by caller-response loss/process recreation: receipt and full authoritative effects survive, recovery reports COMMITTED, retry returns `AlreadyCommitted`, and effects occur exactly once;
- successful authoritative commit followed by derived/presentation rebuild failure: authoritative truth and LAST VALID COMMIT remain valid;
- multi-turn A/B/C ordering: A=1, B=2, failed C does not advance B, retry B does not allocate another order, successful retry C receives order 3;
- campaign isolation: each campaign owns an independent commit-order sequence;
- ordering is not inferred from transaction UID lexical order.

A failure at the physical SQLite COMMIT boundary is resolved after reopen from SQLite durability itself: either the transaction committed atomically and its COMMITTED receipt/effects are visible, or neither is. Recovery does not consult BackupManager, raw DB-copy names, file mtimes, snapshots, caches, narrative output or process-local flags.

## Derived state

CharacterPanelSnapshotV2, PlayerSnapshot, caches, indexes and presentation state are not commit authority and cannot advance LAST VALID COMMIT. Failure to rebuild them after an authoritative commit cannot undo the receipt or authoritative domain writes; they remain rebuildable derived state.

## Regression evidence

The exact G29 runtime candidate passed the repository validation workflow including project validation, the full JVM unit-test suite, signed validation APK construction and immutable artifact upload. This re-runs the accepted Phase 19–28 suite, including G28 replay/conflict/rollback/reopen tests and existing G26/G27 atomicity/bypass tests. No finance, ownership or inventory competing transaction authority was introduced.

G29 CI: Validate RPG OS ALPHA #642, run `32010700796`, exact head SHA `2eba3b2f14cf085b00f8187b6e5eb57d8e1991b5`, completed/success.

Freshness before this docs-only report: master exactly matched `2eba3b2f14cf085b00f8187b6e5eb57d8e1991b5`.

## Boundaries intentionally not crossed

- No Phase-30 Event Store.
- No Phase-33 backup/recovery mechanism.
- No acceptance-record update.
- No roadmap COMPLETE/ACCEPTED update.
- No snapshot/filesystem/wall-clock recovery authority.
- No fabricated legacy transaction history.

## Worker verdict

TRANSACTION INTEGRITY IMPLEMENTATION COMPLETE —
READY FOR INDEPENDENT POST-AUDIT
