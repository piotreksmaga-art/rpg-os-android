# WORK-20260810-060 — Phase 13 Adversarial Matrix

Status: ADVERSARIAL MATRIX READY / FINAL VALIDATION PENDING

Work ID: `WORK-20260810-060`
Worker: `CHAT-5`
Role: `READ-ONLY PHASE 13 ADVERSARIAL AUDITOR`
Repository: `piotreksmaga-art/rpg-os-android`
Accepted Phase-12 runtime baseline: `d5f1fd6e7a660e3e398f155784f8602c486b9906`
Fresh master observed at matrix preparation: `919f1718e0e1b3cbd869a1e58b69baaebac3eeec`
Architecture input: `WORK-20260810-054 — Phase 13 economy architecture`
Phase-13 implementation owner: CHAT-1, final result commit not yet available.

This artifact defines the independent adversarial validation matrix only. It does not issue a Phase-13 PASS/FAIL and does not implement or repair runtime/schema.

---

## 1. Canonical invariants under attack

Phase 13 must preserve the following hard boundaries:

```text
Financial Ledger != OwnershipRecord != Inventory != Equipment
Financial transaction history != current balance projection
Personal wealth != organization wealth
Ledger authority != legacy finance summary
```

A normal internal transfer must conserve value exactly:

```text
source delta = -amount
destination delta = +amount
sum deltas = 0
```

unless the operation is an explicitly authorized typed source/sink transaction under the Phase-13 contract.

A payment alone must not imply ownership transfer. Ownership transfer alone must not fabricate payment. Inventory/equipment mutations must not create money or legal economic history unless a higher-level atomic domain operation explicitly coordinates those effects.

Committed financial history must be append-preserved. Corrections should occur through reversal/correction entries, not destructive mutation of committed history.

---

## 2. Core financial adversarial matrix

| ID | Attack | Minimal setup/action | Required result |
|---|---|---|---|
| FIN-ADV-001 | duplicate transaction UID | submit second transaction with same stable FinancialTransaction UID | Reject or deterministic idempotent replay; never duplicate economic effect |
| FIN-ADV-002 | duplicate semantic retry | repeat exact same logical command/idempotency identity | At most one committed economic effect |
| FIN-ADV-003 | malformed amount | inject wrong storage type/text/REAL/malformed exact amount | Reject at model and authoritative DB/write boundary |
| FIN-ADV-004 | zero amount | attempt amount = 0 for normal transaction | Reject unless a specific zero-value event type is explicitly part of canonical contract; never count as money movement |
| FIN-ADV-005 | negative amount | attempt amount < 0 to reverse direction implicitly | Reject; direction belongs to transaction semantics, not sign trick |
| FIN-ADV-006 | unsupported precision | amount cannot be represented exactly by currency/value-kind scale | Reject deterministically; no rounding-based silent acceptance |
| FIN-ADV-007 | overflow | amount or resulting balance near numeric max then +1 | Reject safely; no wraparound |
| FIN-ADV-008 | underflow | debit below numeric/domain minimum | Reject according to overdraft policy; no wraparound |
| FIN-ADV-009 | wrong campaign | account/party/currency from campaign A used in campaign B transaction | Reject; no cross-campaign lookup fallback |
| FIN-ADV-010 | nonexistent party/account | target/source account or holder does not exist | Reject at authoritative boundary |
| FIN-ADV-011 | unknown namespace | arbitrary account/holder/value/currency kind | Reject unless registered/authorized by actual Phase-13 namespace contract |
| FIN-ADV-012 | inactive/retired target | transact against retired/closed party/account/value definition | Reject according to lifecycle policy |
| FIN-ADV-013 | currency mismatch | source and destination incompatible currencies/value kinds without explicit exchange operation | Reject; no implicit conversion |
| FIN-ADV-014 | timestamp/order manipulation | backdate/future-order transaction to bypass balance or lifecycle checks | Reject or enforce deterministic canonical ordering |
| FIN-ADV-015 | provenance omission | blank/missing provenance/source event where required | Reject |
| FIN-ADV-016 | provenance spoofing | reuse provenance/event/command identity with conflicting semantics | Reject; no idempotency aliasing |
| FIN-ADV-017 | history mutation | UPDATE committed transaction amount/from/to/currency/time | Reject at authoritative boundary |
| FIN-ADV-018 | history deletion | DELETE committed transaction | Reject; append-preserved history |
| FIN-ADV-019 | partial transfer commit | fail after debit before credit | Entire transfer rollback; no one-sided state |
| FIN-ADV-020 | rollback failure | induced exception after intermediate writes | No committed partial effect, no orphan operation/idempotency marker |
| FIN-ADV-021 | reopen corruption | close/reopen after mixed credits/debits/transfers | Exact ledger/history and derived balances preserved |
| FIN-ADV-022 | restore corruption | backup/restore after ledger activity | Exact committed financial state and history restored |
| FIN-ADV-023 | campaign leakage | same UIDs/accounts in campaigns A/B | Queries and mutations remain fully campaign-scoped |
| FIN-ADV-024 | legacy fabricated history | V12 database has `character_finances` summaries only | Migration must not invent detailed historical transactions |
| FIN-ADV-025 | >1000 transaction truncation | create 1001+ FinancialTransactions | Authoritative history/balance/income/expense reads remain complete |
| FIN-ADV-026 | stale balance write | read old balance then commit based on stale assumption | Authoritative transaction boundary revalidates spendability/conservation |
| FIN-ADV-027 | generic patch bypass | write canonical ledger table via generic StatePatch/direct bypass | Must be blocked or guarded by same finance invariants as typed API |
| FIN-ADV-028 | opening-balance double import | repeated migration/ensure/restore | Opening basis created at most once per exact migration identity |
| FIN-ADV-029 | ambiguous legacy mapping | same label/reason/entity display text could map multiple targets/currencies | Remain unresolved; do not guess |
| FIN-ADV-030 | source/sink escape hatch | null/from-to or external boundary used without typed authorization | Reject unauthorized mint/burn |
| FIN-ADV-031 | correction rewriting history | “fix” prior transaction by editing/deleting it | Reject; correction must be append-only reversal/new entry |
| FIN-ADV-032 | balance cache as authority | corrupt/delete cached balance projection | Ledger rebuild must recover exact balance; cache cannot explain history |

---

## 3. Domain-confusion attacks

| ID | Confusion attack | Required result |
|---|---|---|
| FIN-DOM-001 | payment -> automatic ownership | Payment alone does not alter OwnershipRecord |
| FIN-DOM-002 | ownership -> automatic payment | Ownership transfer alone does not create FinancialTransaction |
| FIN-DOM-003 | inventory possession -> money | Inventory move/add/remove does not mutate financial ledger |
| FIN-DOM-004 | equipment -> ownership/payment | Equip/unequip does not create ownership/payment effects |
| FIN-DOM-005 | theft -> legitimate sale | Theft/possession change cannot fabricate sale/payment/title transfer |
| FIN-DOM-006 | loan -> ownership transfer | Item loan/custody cannot create legal title or payment without explicit coordinated operation |
| FIN-DOM-007 | same label/name -> same economic identity | Stable UID/namespace required; display text cannot resolve account/party/currency |
| FIN-DOM-008 | same UID string in different namespace | No collision; full namespace identity used |
| FIN-DOM-009 | same UID string in different campaign | No leakage; campaign scope is authoritative |
| FIN-DOM-010 | organization treasury -> player wallet | No implicit merge of organization and personal wealth |
| FIN-DOM-011 | country economy dashboard -> actor ledger | Dashboard treasury/prosperity state cannot act as canonical account transaction history |
| FIN-DOM-012 | property/investment summary -> owned asset | Phase-13 migration must not synthesize Phase-14 assets/ownership from aggregate valuations |

For any future purchase/sale operation that legitimately coordinates multiple domains, final validation must verify one explicit transaction boundary that commits all required domain effects atomically or rolls them all back.

---

## 4. Exact arithmetic attack matrix

Final validation must discover the actual amount representation and currency precision contract before executing these gates.

### FIN-ARITH-01 — smallest legal unit

Create/send/receive exactly one minor unit (or equivalent minimum exact amount). Expected: accepted without rounding and exactly recoverable after reopen.

### FIN-ARITH-02 — maximum legal amount

Persist the maximum canonical amount/balance allowed by representation and domain policy. Expected: exact value preserved.

### FIN-ARITH-03 — max + 1

Attempt one unit beyond canonical max. Expected: reject before commit; no wraparound.

### FIN-ARITH-04 — negative edge

Attempt minimum negative integer or equivalent malformed negative representation. Expected: reject safely without negation overflow.

### FIN-ARITH-05 — aggregation overflow

Create individually legal entries whose authoritative SUM/derived balance would exceed numeric range. Expected: illegal combined state cannot be committed or silently wrapped during read/rebuild.

### FIN-ARITH-06 — large history sum

Use a long transaction history whose aggregate is near numeric bounds. Expected: exact deterministic aggregate.

### FIN-ARITH-07 — rounding edge

Submit values around half-minor-unit/boundary precision. Expected: unsupported precision rejected, not rounded silently.

### FIN-ARITH-08 — repeated tiny operations

Perform many minimum-unit debits/credits. Expected: final arithmetic equals exact integer/exact-decimal sum with zero drift.

### FIN-ARITH-09 — Float/Double drift attack

Attempt to inject decimal values through model/SQL/parser paths such as `0.1`, NaN, +Infinity, -Infinity where technically possible. Expected: no floating-point representation becomes authoritative conserved money.

### FIN-ARITH-10 — multiplication overflow

If pricing/quantity/tax/rate multiplication exists in Phase 13, combine large individually valid operands. Expected: checked/exact multiplication; no wraparound or silent precision loss.

---

## 5. Mandatory concurrency release gates

Sequential execution is explicitly insufficient. Final validation must use separate SQLite callers/connections/transactions where architecture permits and a synchronization barrier proving overlap.

### FIN-RACE-01 — DOUBLE SPEND

Initial:

```text
A available balance = 100
T1 spends 80
T2 spends 80
```

Required:

- under no-overdraft policy: at most one spend commits;
- if Phase 13 explicitly supports overdraft/credit, final state must still satisfy that exact policy and limit;
- never accept two transactions merely because both Kotlin prechecks read balance=100 before writing.

Automatic FAIL if both can commit into a state disallowed by canonical overdraft policy.

### FIN-RACE-02 — COMPETING TRANSFERS

Initial A has funds sufficient for only one of:

```text
T1: A -> B
T2: A -> C
```

Required: one legal serialized outcome according to actual balance policy. Debit/credit pairs remain conserved.

### FIN-RACE-03 — STALE BALANCE

```text
T1 reads balance
T2 changes balance and commits
T1 submits transaction using stale assumption
```

Required: write boundary uses authoritative current state/CAS/version/serialized ledger check, not stale caller value.

### FIN-RACE-04 — DUPLICATE IDEMPOTENCY

Two callers concurrently submit the same logical/idempotency identity.

Required: at most one economic effect. Second caller may receive deterministic replay/result or conflict, but no duplicate debit/credit/history effect.

### FIN-RACE-05 — LIFECYCLE

Race transaction acquisition/transfer against whichever lifecycle operations actually exist in Phase 13, including where applicable:

- account close,
- party retirement,
- currency/value-kind disable,
- account disable/freeze.

Required: coherent serialization. A stale active validation cannot commit against a target that won a concurrent retirement/close race.

### FIN-RACE-06 — CONCURRENT OVERFLOW

Two individually legal concurrent credits/debits together exceed numeric or domain limit.

Required: combined illegal state cannot commit. Protection must be at authoritative DB/transaction boundary, not just per-call precheck.

### Concurrency evidence required

For every FIN-RACE gate capture:

- exact initial rows/projection,
- separate connections/callers,
- synchronization primitive proving concurrent attempt,
- both results/exceptions,
- final ledger rows,
- final derived balances,
- operation/idempotency rows if any,
- `PRAGMA integrity_check`,
- `PRAGMA foreign_key_check`,
- actual protection mechanism: transaction serialization, conditional update/CAS, trigger, constraint, FK, lock, ledger-sum guard, or equivalent.

Potential release-blocker pattern:

```text
SELECT balance
-> Kotlin check
-> later unconditional INSERT ledger
```

when another writer may change authoritative spendability between those steps.

---

## 6. Atomicity attacks

### FIN-ATOM-01 — debit commits / credit fails

Induce destination failure after source debit would otherwise be persisted. Required: source debit rolls back too.

### FIN-ATOM-02 — credit commits / debit fails

Induce source failure after destination write ordering. Required: destination credit cannot survive alone.

### FIN-ATOM-03 — event commits / ledger fails

If Phase 13 requires linked event provenance in same operation, force ledger failure. Required: operation-specific event/economic state follows declared atomicity contract; no false event claiming payment occurred.

### FIN-ATOM-04 — ledger commits / required linked mutation fails

For an explicitly coordinated purchase/sale/reward operation, fail required Ownership/Inventory/etc. mutation. Required: entire high-level operation rolls back unless architecture explicitly models a valid compensating state.

### FIN-ATOM-05 — idempotency marker commits / ledger fails

Force failure after operation identity reservation. Required: retry must not be permanently poisoned into “already committed” without economic effect.

### FIN-ATOM-06 — ledger commits / balance projection fails

If balance cache/projection is persisted, induce projection failure. Required: either whole transaction rolls back or projection is explicitly rebuildable/reconciled without losing canonical ledger truth.

---

## 7. Migration / legacy adversarial matrix

| ID | Scenario | Required result |
|---|---|---|
| FIN-MIG-001 | clean DB -> V13 | Complete canonical schema with no fabricated finance history |
| FIN-MIG-002 | V12 -> V13 | Additive migration; Phase 3–12 authoritative data preserved |
| FIN-MIG-003 | reopen | No duplicate opening balance, transaction, account, mapping or migration marker |
| FIN-MIG-004 | repeated ensure | Idempotent schema ensure and no economic duplication |
| FIN-MIG-005 | backup/restore | Ledger/history/provenance/idempotency preserved exactly |
| FIN-MIG-006 | campaign switch A -> B -> A | No cache/account/ledger leakage across campaigns |
| FIN-MIG-007 | partially initialized schema | Transactional migration either completes coherently or leaves prior valid state; no half-authoritative finance domain |
| FIN-MIG-008 | current money, no history | Do not invent historical transactions; only explicit migration-opening evidence if architecture authorizes it |
| FIN-MIG-009 | legacy monthly income/expense | Do not synthesize past recurring transactions from aggregates |
| FIN-MIG-010 | legacy debt/property/investment | Do not synthesize Phase-14 liabilities/assets/ownership |
| FIN-MIG-011 | ambiguous currency mapping | Leave unresolved rather than guess by display label |
| FIN-MIG-012 | ambiguous payer/payee/reason text | Never infer identities from text alone |
| FIN-MIG-013 | legacy transaction import | Only lossless, explicitly mapped rows promoted; unmappable rows preserved as legacy evidence |
| FIN-MIG-014 | reconciliation mismatch | Expose discrepancy; do not fabricate balancing history to force equality |
| FIN-MIG-015 | generic StatePatch legacy path | Canonical ledger no longer writable through unvalidated generic patch path |

---

## 8. Scale / completeness matrix

Minimum final fixture: 1001+ committed FinancialTransactions in one campaign, preferably mixed income, expense and internal transfer entries.

Required checks:

1. authoritative transaction history returns all records without implicit `LIMIT 1000`;
2. balance projection derives from complete ledger, not bounded history UI;
3. income/expense summaries use complete authoritative data for requested scope;
4. reopen retains full count and exact balance;
5. backup/restore retains full count and exact balance;
6. campaign switch does not replace one campaign's projection with another's;
7. no migration path uses bounded context/dashboard reader as source of truth;
8. any context/UI history may be intentionally bounded only if explicitly presentation-only.

Also inspect SQL/code for dangerous completeness patterns such as:

```text
LIMIT 1000
.take(1000)
first N rows as reconciliation input
context reader reused for balance authority
```

---

## 9. SQLite and authoritative boundary checks

Final validation must execute:

```sql
PRAGMA integrity_check;
```

Required: `ok`.

And:

```sql
PRAGMA foreign_key_check;
```

Required: zero violations in authoritative DB scope.

However FK cleanliness is not sufficient. Final validation must also directly inspect and attack:

- account/party/currency/value-kind reference resolution,
- lifecycle state enforcement,
- spendability/overdraft enforcement,
- exact amount storage type,
- append-only history guards,
- idempotency uniqueness,
- migration-opening uniqueness,
- transaction atomicity,
- generic StatePatch/direct SQL bypass exposure.

---

## 10. Phase 3–12 regression boundary

Phase 13 must not silently mutate or reinterpret accepted authorities:

- Stats / Resources,
- Modifier/Resolver,
- Talent / Potential,
- Skills,
- Techniques,
- Innate/Racial,
- Inventory,
- Equipment,
- OwnershipRecord.

Especially preserve:

```text
Financial Ledger != OwnershipRecord != Inventory != Equipment
```

Cross-domain linkage is legal only when an explicit higher-level operation coordinates all required effects atomically and each domain still retains its own authoritative record.

---

## 11. Final-validation protocol

No Phase-13 runtime SHA was available when this matrix was prepared. Therefore final verdict is intentionally deferred.

After CHAT-1 publishes the final Phase-13 result commit, CHAT-5 must:

1. refresh master and identify exact Phase-13 runtime SHA;
2. verify exact CI head SHA/result;
3. inspect the complete Phase-13 runtime/schema/migration/write boundary;
4. identify the exact amount/account/currency/lifecycle/overdraft/idempotency contracts;
5. execute the full FIN-ADV, FIN-DOM, FIN-ARITH, FIN-RACE, FIN-ATOM, FIN-MIG and scale matrices against that exact SHA;
6. run real competing SQLite concurrency tests with synchronization barriers;
7. verify `PRAGMA integrity_check` and `foreign_key_check`;
8. verify Phase 3–12 no-regression;
9. record minimal reproducer, violated invariant, exact path, expected/actual and minimal correction scope for every blocker;
10. issue exactly one final verdict:

```text
PHASE 13 ADVERSARIAL VALIDATION: PASS
```

or

```text
PHASE 13 ADVERSARIAL VALIDATION: FAIL
```

No blocker discovered by CHAT-5 is to be hotfixed by CHAT-5.

Phase 14 / next phase remains blocked until Phase 13 is formally accepted.

# ADVERSARIAL MATRIX READY / FINAL VALIDATION PENDING
