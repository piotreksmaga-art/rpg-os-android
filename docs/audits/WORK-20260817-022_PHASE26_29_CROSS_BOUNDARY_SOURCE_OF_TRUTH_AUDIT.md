# WORK-20260817-022 — Phase 26–29 Cross-Boundary / Source-of-Truth Post-Audit

## 1. Audit identity

- **Work ID:** `WORK-20260817-022`
- **Role:** CHAT-5 — independent cross-boundary / regression / source-of-truth reviewer
- **Mode:** READ-ONLY RUNTIME; evidence-only audit report commit permitted
- **Repository:** `piotreksmaga-art/rpg-os-android`
- **Exact runtime SHA audited:** `2eba3b2f14cf085b00f8187b6e5eb57d8e1991b5`
- **Accepted Phase-25 baseline used for regression comparison:** `c028aa355d9b7e1663166a2fedb910c1a2dad795`
- **Implementation work:** `WORK-20260817-020`
- **Current master observed during audit:** `fab02c16b17321e6162aa9d775ad4e3cb9f3199d`
- **Later drift from exact runtime candidate:** 3 documentation-only commits (`WORK-020` implementation report, `WORK-021` audit report, TEST-GM snapshot); no later runtime/schema/test semantics were substituted for the exact candidate.
- **Exact-SHA CI independently verified:** `Validate RPG OS ALPHA`, run `#642`, ID `32010700796`, `head_sha=2eba3b2f14cf085b00f8187b6e5eb57d8e1991b5`, `completed / success`.

This audit and verdict apply only to runtime semantics at `2eba3b2f14cf085b00f8187b6e5eb57d8e1991b5`.

## 2. Final verdict

**FAIL — FIX REQUIRED BEFORE PHASE 26–29 ACCEPTANCE**

The candidate introduces a useful outer SQLite transaction, durable committed receipts, transaction/command replay protection, rollback behavior and a read-facing LAST VALID COMMIT API. Those pieces are directionally compatible with the architecture.

However, Group A is not yet one structurally coherent transaction-integrity layer. Two acceptance-blocking cross-boundary defects remain:

1. authoritative gameplay-capable typed writers remain directly callable outside `CampaignMutationBoundary` / `TurnTransaction`; and
2. the committed receipt is bound to the declared `PlayerChangeSet` fingerprint but not to the effects actually executed inside the arbitrary `TurnTransaction.execute { ... }` block. A non-empty proposal can therefore receive a committed receipt after zero, partial or unrelated domain effects, after which retry suppression can permanently preserve that incomplete reality.

A third non-blocking but material boundary finding is that `TurnRecoveryReader` constructs `TurnTransactionReceiptStore`, whose initializer performs schema/migration writes, so the advertised recovery reader is not strictly read-only at the database level.

No Phase 30 work is started or recommended by this report.

---

## 3. Exact runtime delta and preserved earlier architecture

Comparison of accepted Phase-25 runtime `c028aa355...` to exact candidate `2eba3b2f...` shows Group-A additions/modifications around:

- `CampaignMutationBoundary.kt`;
- `TurnTransaction.kt`;
- `TurnTransactionReceiptStore.kt`;
- `StatePatchEngine.kt`;
- `UnifiedGameRepository.kt` / existing repository surfaces;
- local-transaction joining changes in selected typed stores;
- additive migration/schema support for transaction receipts;
- Phase 26–29 tests.

The candidate does not replace `PlayerDomainEngine`, `WorldRuleProvider`, `ProgressionEngine`, `PlayerInvariantValidator`, `CharacterPanelSnapshotV2`, or `PlayerSnapshotBuilder` with alternative engines/authorities.

The accepted legality/proposal direction therefore remains conceptually:

`PlayerCommand -> PlayerDomainEngine -> WorldRuleProvider -> PlayerInvariantValidator -> PlayerChangeSet`

Group A is supposed to start **after** that validated proposal and coordinate durable commit. The findings below concern whether that commit boundary is actually exclusive and whether its receipt proves the committed semantics.

---

## 4. Actual authority / writer map

| Subsystem / writer | Authority role | Candidate interaction | Can write outside TurnTransaction? | Verdict |
|---|---|---|---:|---|
| `CampaignMutationBoundary` | proposal admission only | wraps a successful resolved `PlayerChangeSet` in opaque `CanonicalCampaignMutationProposal` | N/A | Correct role, but admission alone does not gate stores |
| `TurnTransactionBoundary` / `TurnTransaction` | outer atomic commit coordinator | verifies campaign/command identity, fingerprints proposal, opens outer SQLite transaction, appends receipt | N/A | Useful coordinator; not yet exclusive |
| `TurnTransactionReceiptStore` | append-only commit/idempotency evidence | committed transaction/command identity + semantic fingerprint + campaign commitOrder | only via internal store, but schema init writes occur on construction | Receipt role mostly correct |
| `StatePatchEngine` | legacy generic patch route | now fails closed for all ordinary patch application | no authoritative patch via this route | PASS |
| `UnifiedGameRepository.recordTruth` | campaign FACT/BELIEF/NARRATIVE authority | directly opens save DB and calls `CampaignTruthStore.record(...)` | **YES** | **BLOCKER** gameplay-capable authority bypass remains |
| `UnifiedGameRepository` / `LocalGameStore` administrative setters | active player, definitions, package/campaign selection etc. | direct store calls | YES | Must be explicitly classified admin/install; not proof of gameplay exclusivity |
| `InventoryStore` | authoritative inventory state | available from `TurnTransactionScope.inventoryStore()` and joins outer tx when used there | **YES**; public mutators can be called directly | **BLOCKER** Phase-26 capability boundary not enforced |
| `FinancialStore` | authoritative financial ledger; balance projection derived | available from scope; local transaction helper joins outer when already in transaction | **YES**; public `commit/transfer/credit/debit/...` still independently callable | **BLOCKER** as mutation-path exclusivity issue; finance authority itself remains correct |
| `OwnershipStore` | authoritative temporal ownership | available from scope; local transaction updated to join outer | **YES**; public ownership mutators remain independently callable | **BLOCKER** as mutation-path exclusivity issue; ownership authority itself remains correct |
| `DevelopmentProjectStore` | authoritative project lifecycle/history | exposed by transaction scope | existing direct typed writer remains | same gating concern |
| stats/resources and other typed stores | player/domain current authority | some internal scope exposure, existing stores remain | existing direct write surfaces remain | same gating concern |
| migrations/install/recovery | privileged structural/administrative mutation | intentionally outside gameplay transaction | YES, by design | acceptable only with explicit non-gameplay classification |
| Character panel / player snapshot builders | derived presentation/projection | unchanged | no writer authority | PASS |

### Source-of-truth conclusion

The domain authorities themselves have not been duplicated: finance ledger remains finance authority, ownership records remain ownership authority, inventory tables remain inventory authority, and transaction receipts do not contain copies of those payloads.

The defect is **writer-path multiplicity**, not duplicate storage representation. The same authoritative stores can still be mutated by two operational paths:

1. through the new outer `TurnTransaction`; or
2. directly through their existing typed public writer APIs / repository surfaces.

That is exactly the “new path beside existing writers” failure mode Group A was supposed to eliminate.

---

## 5. BLOCKER P26-29-CB-001 — authoritative gameplay writers remain bypassable

### Evidence

`CampaignMutationBoundary` only admits a previously resolved proposal and creates an opaque `CanonicalCampaignMutationProposal`. It does not own or issue the only capability required by the typed store mutators.

`TurnTransactionScope` provides convenience accessors for `FinancialStore`, `OwnershipStore`, `InventoryStore`, `StatResourceStore`, and `DevelopmentProjectStore`, but the same store classes still have constructors/mutators usable without a `TurnTransactionScope` capability.

Concrete examples at the exact SHA:

- `InventoryStore.addStack/removeStack/transferStack/addUnique/removeUnique/transferUnique` remain directly callable. Its `tx` helper deliberately starts its own transaction when no outer transaction is active.
- `FinancialStore.commit/transfer/creditExternal/debitExternal/...` remain directly callable. Its local helper joins an outer transaction when present, but otherwise starts and commits its own transaction.
- ownership remains a typed direct authority with independent entry points.
- `UnifiedGameRepository.recordTruth(...)` directly opens the campaign DB and calls `CampaignTruthStore.record(...)` without `CampaignMutationBoundary` or `TurnTransaction`.

The fact that a store *can join* a TurnTransaction does not prove it *must* join one for gameplay mutation.

### Contract conflict

The Phase-26 pre-audits required every authoritative gameplay writer to be internal/gated behind the canonical transaction capability. They explicitly rejected a fifth category of “trusted direct writer”. They also specifically classified `UnifiedGameRepository.recordTruth()` as a direct authoritative writer requiring gating for gameplay writes.

### Impact

A caller can mutate durable campaign truth, inventory, finance, ownership or other typed authority without producing a turn receipt and without sharing atomicity/idempotency with sibling effects of the same gameplay action. LAST VALID COMMIT then cannot be a complete definition of the latest committed gameplay reality because authoritative rows can exist that were committed after/beside the receipt stream.

### Severity

**BLOCKER**

---

## 6. BLOCKER P26-29-CB-002 — receipt semantics are not bound to the executed effect program

### Exact runtime behavior

`TurnTransactionBoundary.create(...)` binds `semanticFingerprint` to `TurnSemanticFingerprint.forProposal(proposal)`, which is `PlayerChangeSetCodec.fingerprint(proposal.playerChangeSet)`.

`TurnTransaction.execute(block)` then runs an arbitrary caller-provided `TurnTransactionScope.() -> T` block. After that block returns, it appends a `COMMITTED` receipt using the proposal fingerprint and commits the outer SQLite transaction.

There is no runtime mechanism that proves:

- each `PlayerDomainChange` in the proposal was applied;
- no proposal change was skipped;
- no unrelated authoritative effect was added;
- cross-domain application is complete;
- the concrete domain operation identities correspond to the proposal payload;
- the number/kinds of writes match the declared proposal.

`authoritativeWrite(...)` only counts synthetic calls for failure injection. Real store calls such as `inventoryStore().addStack(...)` or `financialStore().creditExternal(...)` are not mechanically reconciled against the proposal.

### Concrete proof from existing tests

The Phase-28/29 tests themselves create non-empty proposals containing a `FinancialChange` and successfully execute transactions with `execute { Unit }` in replay/fingerprint tests. This demonstrates that a committed receipt can be created for a proposal without applying the proposal’s declared financial change at all.

The tests for inventory, finance and ownership idempotency exercise each family separately. There is no test demonstrating one mixed proposal whose inventory+finance, inventory+ownership or finance+ownership effects are all structurally required and jointly replay-safe.

### Failure scenario

Example:

1. validated proposal P declares inventory reward + finance reward;
2. transaction block accidentally applies only inventory;
3. block returns normally;
4. receipt stores fingerprint(P) and transaction COMMIT succeeds;
5. response is lost;
6. retry submits the same command/proposal;
7. receipt matches fingerprint(P), so `AlreadyCommitted` is returned and block is not re-executed;
8. finance reward is permanently missing even though durable receipt claims the full proposal semantics were committed.

The inverse can happen for any subset or unrelated write set.

### Impact

The receipt proves **“this transaction block committed under this proposal fingerprint”**, not **“the durable reality represented by this proposal was fully committed.”** That distinction breaks the requested cross-domain idempotency/source-of-truth guarantee and makes durable receipt reuse possible for a different actual reality.

### Severity

**BLOCKER**

---

## 7. Phase 27 atomicity / nested transaction analysis

### What works

Within a correctly used `TurnTransaction`:

- nested outer `TurnTransaction` is rejected;
- one SQLite transaction surrounds child writes and receipt insertion;
- failure before commit rolls back child effects and the receipt together;
- finance’s transaction helper joins an existing SQLite transaction;
- inventory’s `tx` helper joins an existing SQLite transaction;
- ownership was adjusted to participate in an existing outer transaction;
- receipt is inserted before `setTransactionSuccessful()` and therefore rolls back with effects.

Synthetic Phase-27 tests prove rollback after first/second `authoritativeWrite` and successful single commit.

### Limitation

These guarantees apply only to effects actually placed inside that outer block. They do not close P26-29-CB-001 or P26-29-CB-002.

---

## 8. Phase 28 idempotency analysis

### Positive results

- same committed transaction + same semantics -> `AlreadyCommitted`;
- same campaign command + same semantic fingerprint with a new transaction UID -> no repeated execution;
- changed proposal fingerprint for the same command -> conflict;
- transaction UID cross-campaign reuse -> conflict;
- durable receipt survives DB reopen/process recreation;
- rollback leaves no committed dedupe state, allowing retry.

### Cross-domain result

**Insufficient / blocked by semantic execution gap.**

Tests independently cover inventory, finance and ownership. They do not prove mixed-domain combinations. More importantly, even adding combination tests would not by itself close the architectural issue unless the runtime binds the admitted proposal to an executable/apply plan whose complete application is enforced.

---

## 9. Crash / unknown-outcome analysis

The core SQLite/receipt mechanism is sound for a correctly executed effect set:

- rollback before COMMIT removes effects and receipt;
- a later retry is legal;
- a committed receipt persists across database reopen;
- a lost response/process-local object loss can be resolved by querying transaction/command receipt;
- replay avoids executing the block again.

Phase-29 tests cover failures at multiple pre-commit points, process reopen, replay and campaign-isolated commit ordering.

However, because the receipt is not proof of complete proposal application, crash recovery can confidently answer **whether the outer block committed**, but cannot prove that all declared domain effects were represented by that block.

---

## 10. HIGH P26-29-CB-003 — TurnRecoveryReader is not strictly read-only at DB level

`TurnRecoveryReader` constructs `TurnTransactionReceiptStore(db)`.

`TurnTransactionReceiptStore` executes `ensureSchema()` in its initializer. `ensureSchema()` may:

- `CREATE TABLE IF NOT EXISTS turn_transaction_receipts`;
- `ALTER TABLE ... ADD COLUMN commit_order` for older Phase-28 receipt schemas;
- update existing proven receipt rows to assign commit order;
- create indexes;
- create `rpgos_schema_migrations` if needed;
- insert migration markers.

Therefore a nominal recovery read can perform structural/migration writes.

This does **not** create a second gameplay/recovery authority, and LAST VALID COMMIT still comes solely from committed receipts. But it violates the requested strict read-oriented boundary and mixes migration responsibility into recovery reads.

**Severity: HIGH**

---

## 11. Receipt authority / Phase-30 boundary

### Correct classification

`turn_transaction_receipts` contains only transaction/turn/command identity, semantic fingerprint, result fingerprint, campaign-scoped commit order, version and committed state.

It does not contain:

- player state payloads;
- finance ledger entries;
- ownership records;
- narrative/event payloads;
- snapshots;
- FACT/BELIEF/NARRATIVE content.

Therefore it is properly classifiable as **APPEND-ONLY COMMIT EVIDENCE / transaction identity**, not a second domain authority.

### Phase-30 result

No canonical Event Store was introduced. Receipt != gameplay event. Phase 30 remains a genuine next phase and must not be started until Group-A blockers are resolved/accepted.

---

## 12. LAST VALID COMMIT analysis

Subject to the blockers above, the receipt-local ordering mechanism is well designed:

- `commitOrder = MAX(commit_order)+1` is allocated while the outer SQLite write transaction is active;
- `(campaign_uid, commit_order)` is unique;
- failed transactions leave no committed receipt and therefore no false advancement;
- each campaign has independent ordering;
- `lastValidCommit(campaignUid)` selects highest committed campaign order;
- process restart does not alter receipt semantics;
- LAST VALID COMMIT selection does not use wall-clock time, filesystem mtime or lexical UID ordering.

Caveat: because direct authoritative writers remain possible outside receipts, `lastValidCommit` is not yet sufficient to prove “last complete committed gameplay reality” for the whole campaign.

---

## 13. Migration / legacy analysis

The receipt migration is prospective in intent:

- no pre-Phase-28 turn/command/transaction history is fabricated;
- migration notes explicitly state no legacy transaction history is synthesized;
- when upgrading existing Phase-28 receipt rows, commit order is assigned only to already proven committed receipts;
- no actor/cause/event sequence is invented for older campaigns.

`UNKNOWN_NOT_RECORDED` semantics from earlier provenance work are not replaced by fabricated historical transaction records.

One responsibility concern remains under P26-29-CB-003: schema upgrade is lazily invoked from receipt-store construction, including read-facing recovery construction.

---

## 14. Phase 1–25 regression / mechanic ownership

No evidence was found that Group A recalculates or replaces:

- PlayerDomainEngine mechanics;
- Phase-19 pinned WorldRuleProvider legality;
- Phase-20/21 progression arithmetic;
- Phase-22 invariant/no-retrogression semantics;
- Phase-23 progression provenance semantics;
- finance ledger mathematics;
- ownership temporal semantics;
- inventory domain rules.

`CampaignMutationBoundary` accepts only `PlayerResolutionOutcome.Resolved` and does not reinterpret AI output as committed reality.

CharacterPanelSnapshotV2 / PlayerSnapshot remain derived layers; no new writable snapshot authority or FACT/BELIEF/NARRATIVE flattening appears in the Group-A delta.

**Regression verdict for mechanic ownership: PASS.**

---

## 15. Findings

| ID | Severity | Finding | Acceptance impact |
|---|---|---|---|
| `P26-29-CB-001` | **BLOCKER** | authoritative typed/repository writers remain directly callable outside CampaignMutationBoundary/TurnTransaction | blocks Group-A single mutation path |
| `P26-29-CB-002` | **BLOCKER** | committed receipt fingerprints declared proposal but runtime does not enforce that execute-block effects equal the proposal | blocks cross-domain idempotency / receipt-as-commit-evidence semantics |
| `P26-29-CB-003` | **HIGH** | `TurnRecoveryReader` construction can run receipt schema migration writes | violates strict read-only recovery boundary; does not create second gameplay authority |

No separate duplicate finance authority, duplicate ownership authority, duplicate ProgressionEngine, second PlayerEngine, second WorldRuleProvider, writable CharacterPanelSnapshot, writable PlayerSnapshot, or accidental Event Store was found.

---

## 16. Blockers

Two blockers remain:

1. close/gate authoritative gameplay writer surfaces so ordinary gameplay cannot commit outside the canonical transaction capability;
2. bind receipt semantics to an enforced executable mutation plan / complete application of the admitted proposal, rather than merely fingerprinting the proposal while executing an unconstrained block.

This audit is read-only and does not prescribe or implement the specific repair design.

---

## 17. CI evidence

Independently verified GitHub Actions run:

- workflow: `Validate RPG OS ALPHA`
- run: `#642`
- run ID: `32010700796`
- `head_sha`: `2eba3b2f14cf085b00f8187b6e5eb57d8e1991b5`
- status: `completed`
- conclusion: `success`

Green CI is compatible with this FAIL verdict because the existing tests prove local atomicity/replay/recovery behavior but do not enforce the missing cross-boundary exclusivity or proposal-to-effect completeness invariant.

---

# FINAL VERDICT

**FAIL — FIX REQUIRED BEFORE PHASE 26–29 ACCEPTANCE**

This verdict applies **only** to runtime SHA:

`2eba3b2f14cf085b00f8187b6e5eb57d8e1991b5`

This report does **not** declare Phase 26–29 accepted and does **not** start Phase 30.
