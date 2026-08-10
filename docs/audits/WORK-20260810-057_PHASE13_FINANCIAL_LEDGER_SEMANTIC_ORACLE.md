# WORK-20260810-057 — Phase 13 Financial Ledger / Economy Semantic Oracle

Status: SEMANTIC ORACLE READY / FINAL RUNTIME REVALIDATION PENDING

Work ID: `WORK-20260810-057`
Worker: `CHAT-2`
Role: `READ-ONLY PHASE 13 FINANCIAL LEDGER / ECONOMY SEMANTIC ORACLE`
Repository: `piotreksmaga-art/rpg-os-android`
Accepted Phase-12 runtime baseline: `d5f1fd6e7a660e3e398f155784f8602c486b9906`
Fresh master observed immediately before report write: `b08ee3253e62c68ba5a4bccd1840d77644c76a0f`
Architecture input: `WORK-20260810-054 — Phase 13 Financial Ledger / Economy architecture audit`

This artifact defines an independent semantic oracle for Phase 13 before CHAT-1 final runtime is accepted as evidence. It does not inspect a final Phase-13 result commit for PASS/FAIL, does not implement or repair runtime/schema, and does not change MASTER, Roadmap, coordination, Phase 12, Inventory, Equipment, Ownership or later Phase-14 assets/liabilities.

---

## 1. Canonical source hierarchy and phase boundary

The oracle is derived from current repository architecture and roadmap, with the accepted Phase-12 baseline supplied by coordination.

MASTER requires accounting-based money and states that a `FinancialTransaction` carries semantically equivalent fields to:

```text
from / to
currency
amount
reason
event
time
provenance
```

MASTER also states:

```text
Balance may be cache; ledger explains history.
Personal wealth != organization wealth.
Net worth = assets - liabilities.
```

Roadmap identifies Phase 13 as `Financial Ledger / Economy model` and places `Assets / debts / obligations / net-worth model` in Phase 14.

Therefore this oracle treats:

```text
Phase 13 authority = committed financial movement/history + exact monetary/account identity semantics
Phase 14 authority = assets, liabilities/obligations and canonical net-worth composition
```

Phase 13 may later participate in purchase/sale/asset flows, but it must not invent Phase-14 asset or liability authority merely because a financial entry exists.

---

## 2. Canonical domain split

Hard semantic boundary:

```text
FINANCIAL LEDGER
!= OWNERSHIP RECORD
!= INVENTORY POSSESSION
!= EQUIPMENT STATE
!= ASSET VALUATION
!= LIABILITY / OBLIGATION
!= COUNTRY ECONOMY DASHBOARD
```

Each domain answers a different question.

### Financial ledger

Answers:

- what exact economic value movement was committed,
- between which stable financial endpoints/parties/accounts,
- in which exact currency/value identity,
- at what effective campaign time/order,
- for what category/reason,
- under which event/provenance/idempotency identity.

### OwnershipRecord

Answers who holds a legal/right relation to an asset. A payment does not by itself establish legal ownership.

### Inventory possession

Answers who physically possesses an item. Possession movement is not money movement.

### Equipment

Answers loadout/equipped state. Equip/unequip has no implicit financial meaning.

### Asset valuation / liabilities / net worth

These are not reducible to ledger history. Canonical asset/liability identity and net worth belong to Phase 14.

### Country/world economy summaries

Treasury/prosperity dashboards are not a substitute for actor/account transaction history.

---

## 3. FinancialTransaction semantic minimum

Exact names are implementation details, but observable semantics must be equivalent to a durable committed transaction carrying at least:

```text
stable financialTransactionUid or equivalent immutable transaction identity
campaignId
stable source endpoint / account / party reference when required
stable destination endpoint / account / party reference when required
stable currency/value identity
exact positive amount
transaction type/category/reason
source event or equivalent causal reference where available/required
effective campaign time/order
provenance
idempotency/operation identity or equivalent duplicate-commit guard
immutable committed history
```

A transaction is an accounting fact, not a presentation row and not a mutable current-balance field.

Committed transaction semantics must be reconstructible without relying on a display name, current active player label or free-text amount interpretation.

---

## 4. Economic party/account identity

Core must remain generic and not hardcode finance to the active Player.

A valid financial endpoint must resolve to stable identity equivalent to one of these models:

```text
FinancialAccount(campaignId, accountUid, holderRef, currencyRef, accountType, lifecycle, provenance)
```

or a lighter but semantically equivalent stable tuple such as:

```text
(campaignId, holderKindUid, holderUid, accountScopeUid, currencyUid)
```

Required properties:

- stable UID/reference, not display label;
- campaign-scoped identity;
- generic holders can include player, NPC, organization, state, business/company and future legal/economic entities;
- same UID string in different campaigns must not collide;
- same UID string in different namespaces must not collide;
- personal funds and organization funds must not merge implicitly;
- nonexistent/unknown endpoints must not silently become authoritative accounts merely because a string was supplied.

If Phase 13 does not persist a first-class account table, final validation must still prove equivalent stable endpoint authority and leave room for multiple accounts/currencies later.

---

## 5. Currency/value identity oracle

Currency is stable definition identity, not a display string such as `ryo`.

Required semantic properties:

```text
currencyUid / valueKindUid = stable identity
precision/minor-unit contract = deterministic
world/campaign-specific currency definitions remain extensible
```

Core must not globally hardcode Naruto/Bleach currency semantics.

Two currencies sharing a display name do not become the same currency unless their stable identity says so.

A transfer between different currencies/value kinds is not a normal same-currency transfer. Without an explicit exchange/conversion operation and rate semantics, implicit conversion must be rejected.

---

## 6. Exact amount arithmetic

Authoritative conserved value must use exact arithmetic.

Accepted implementation families include:

- integer minor units with declared scale/precision;
- exact decimal representation with explicit scale;
- another exact integer/rational representation whose equality and conservation are deterministic.

Authoritative `Float`/`Double`/SQLite `REAL` arithmetic is not acceptable for conserved money unless future canonical documentation explicitly changes this rule.

### Required amount invariants

For normal money movement:

```text
amount > 0
amount exactly representable in currency precision
no NaN / Infinity
no silent rounding
no integer wraparound
```

Direction belongs to transaction semantics/endpoints, not to a negative-amount sign trick.

Therefore:

- zero normal-transfer amount -> reject unless an explicit zero-value event type is separately defined and does not masquerade as money movement;
- negative amount -> reject;
- precision finer than currency contract -> reject, not round;
- arithmetic overflow/underflow -> reject atomically;
- maximum/minimum legal values are determined by exact representation plus domain policy and must be enforced before commit;
- derived aggregation must also be overflow-safe.

---

## 7. Ledger -> current balance

Canonical relationship:

```text
opening/migration basis
+ committed incoming entries
- committed outgoing entries
= current balance
```

Therefore:

```text
LEDGER = AUTHORITATIVE HISTORY
BALANCE = DERIVED / REBUILDABLE PROJECTION
```

A persisted balance table/cache is allowed only if deleting/corrupting/rebuilding it from authoritative financial history recovers the exact same value.

A write path that independently mutates both ledger and a competing authoritative balance source without reconciliation fails this oracle because it creates double authority.

The ledger must explain the current balance; a current balance cannot explain or reconstruct the detailed ledger history by itself.

---

## 8. Income semantics

`income` is a classification/aggregation over committed value entering an economic endpoint under an income-producing transaction type/category.

It is not synonymous with every incoming transfer.

Examples:

- salary/reward may be income;
- refund may be incoming cash but semantically a reversal/refund category rather than new income;
- internal transfer between two accounts of the same holder is not automatically economic income;
- migration opening balance is not earned income.

If final Phase-13 implementation does not distinguish income categories beyond generic transaction type/reason, the oracle only requires that any reported `income` summary be deterministically derived from explicit committed classifications rather than guessed from sign alone.

Legacy `monthly_income` is a summary/evidence field, not proof of a historical sequence of income transactions.

---

## 9. Expense semantics

`expense` is a classification/aggregation over committed outgoing value under an expense-producing category.

It is not synonymous with every debit.

Examples:

- purchase payment or fee may be expense depending on explicit transaction semantics;
- internal account transfer is not automatically expense;
- refund/reversal must not be double-counted as a new unrelated expense;
- migration opening basis is not an expense.

Legacy `monthly_expenses` is summary/evidence and must not generate synthetic past expenses.

---

## 10. Transfer semantic oracle

For a normal same-currency internal transfer:

Initial:

```text
A balance = a
B balance = b
amount = x > 0
```

Committed transfer result:

```text
A delta = -x
B delta = +x
net conserved delta = 0
```

Observable balance result:

```text
A' = a - x
B' = b + x
```

subject to overdraft/spendability policy.

The semantic transaction must be one atomic operation or an equivalently atomic set of ledger postings. There must never be a committed state where the required debit exists while its corresponding required credit is absent, or vice versa.

Money creation/destruction is outside ordinary transfer conservation and must use an explicitly authorized typed source/sink semantic rather than `null from/to` as an unrestricted escape hatch.

---

## 11. Payment without ownership transfer

Scenario:

```text
A pays B amount X
```

Expected:

```text
financial ledger changes according to committed payment
OwnershipRecord remains unchanged unless an explicit ownership-domain mutation is separately authorized
Inventory remains unchanged unless explicitly mutated
Equipment remains unchanged
```

Payment alone is not title evidence.

---

## 12. Ownership transfer without payment

Scenario:

```text
A transfers ownership of asset X to B as a gift or other zero-price disposition
```

Expected Phase-13 result:

```text
no money transaction is fabricated merely because OwnershipRecord changed
```

The ownership operation may succeed without a Phase-13 payment if governing domain rules allow it.

---

## 13. Theft boundary

Theft proves the domains are independent.

Possible result:

```text
possession: A -> B
legal ownership: remains A
financial ledger: no sale/payment fabricated
```

A stolen item appearing in B inventory must not create revenue, expense, purchase, sale or ownership transfer automatically.

If a later adjudication/payment occurs, that requires explicit domain operations.

---

## 14. Loan boundary

Item/custody loan:

```text
A owns X
B temporarily possesses X
```

does not itself require any FinancialTransaction.

If a loan is a monetary loan/credit obligation, canonical obligation/debt identity belongs to Phase 14. Phase 13 may record actual disbursement/payment cash flows, but must not pretend those entries alone are the complete debt/obligation model.

Thus:

```text
loaned item != payment
cash disbursement != complete liability model
```

---

## 15. Gift boundary

A gift may have:

```text
ownership transfer
zero monetary consideration
```

Phase 13 must not create a fake zero-amount payment to explain the gift unless an explicit non-money event classification exists. Normal amount rules remain `amount > 0` for actual financial movement.

---

## 16. Purchase and sale boundary

A purchase/sale is a cross-domain business operation, not reducible to one Phase-13 row.

Future complete semantics may require coordinated effects such as:

```text
financial payment
+ OwnershipRecord transfer
+ Inventory possession transfer
+ event/provenance
```

But Phase 13 alone must preserve the separation:

```text
payment != ownership transfer != possession transfer
```

If current Phase-13 scope has no higher-level purchase/sale coordinator because PlayerCommand/PlayerDomainEngine are later roadmap phases, the oracle does not require Phase 13 to invent one prematurely. It requires only that no individual domain effect silently synthesizes the others.

Any future higher-level Purchase/Sell operation must coordinate required effects atomically or roll them all back.

---

## 17. Refund oracle

A refund is a new committed economic fact, not destructive deletion of the original payment.

Expected history:

```text
original transaction remains immutable
refund/reversal entry is appended with its own stable UID, time and provenance
refund references original transaction when contract supports linkage
```

Current balance and period summaries must derive from both entries according to their types.

A refund must not silently rewrite original amount/from/to/time.

---

## 18. Immutable history and corrections

Committed financial history is append-preserved.

Forbidden correction:

```text
UPDATE old transaction amount/from/to/currency/time/reason to "fix" history
DELETE old transaction
```

Required correction family:

```text
original T1 remains
append reversal/correction T2 referencing T1 where supported
append corrected T3 if needed
```

Exact schema may differ, but auditability and replay-equivalent history must survive.

---

## 19. Source event and provenance

Each authoritative transaction must retain enough causal evidence to explain why it exists.

At minimum:

```text
nonblank provenance
stable transaction identity
campaign identity
effective time/order
transaction type/reason
```

Where event infrastructure/source-event identity is available or the operation contract requires it, the transaction should also retain `sourceEventUid` or equivalent causal link.

Presentation text is not provenance.

Migration provenance must explicitly identify legacy migration/bootstrap rather than inventing a gameplay event.

Retrying with the same operation/transaction identity but conflicting provenance or semantic fields must fail rather than alias two different facts.

---

## 20. Stable transaction UID and idempotency

Required observable semantics:

### Exact retry

```text
operationUid/financialTransactionUid = K
same semantic payload retried
```

Expected:

```text
at most one committed economic effect
```

The implementation may return the already committed transaction/result or an explicit ALREADY_COMMITTED outcome.

### Conflicting reuse

```text
same idempotency identity K
but amount/from/to/currency/time/type differs
```

Expected:

```text
reject
```

A transaction UID cannot silently refer to two economic facts.

### Concurrent duplicate retry

Two callers racing the same logical operation must still result in one economic effect.

---

## 21. Campaign isolation

Every authoritative finance identity/read/write is scoped to one campaign.

Same transaction/account/holder/currency UID strings may exist in different campaigns without leakage.

Required:

```text
campaign A transaction cannot debit/credit campaign B endpoint
campaign A balance cannot include campaign B history
campaign switch A -> B -> A preserves independent financial histories
```

---

## 22. Atomicity and rollback oracle

A commit boundary is semantic truth.

For any Phase-13 operation consisting of multiple required authoritative writes:

```text
all required pieces commit
OR
none commit
```

Required rollback expectations:

- failure after debit but before credit -> no committed debit;
- failure before transaction-history insert -> no balance side effect;
- failure after an idempotency marker but before economic writes -> marker must not block legitimate retry unless the whole operation was committed;
- validation failure -> zero authoritative changes;
- overflow/insufficient-funds/reference failure -> zero authoritative changes;
- exception inside transaction -> rollback restores prior ledger-derived state.

If balances are cached projections updated in the same operation, a failed operation must not leave cache inconsistent with ledger.

---

## 23. Spendability / overdraft boundary

MASTER requires money conservation/debt rules to be validated, but canonical documents do not currently define one universal overdraft policy for all worlds/accounts.

Therefore the oracle does not invent a global `balance >= 0` rule.

Instead it requires:

- the account/currency/domain policy explicitly decides whether negative spendable balance is legal;
- stale reads cannot bypass that policy;
- if overdraft is forbidden for an account, a debit that would exceed spendable funds must fail atomically;
- if credit/debt is permitted, the legal mechanism must be explicit rather than numeric underflow or accidental negative balance.

Canonical debt/obligation identity itself remains Phase 14.

---

## 24. Concurrency semantic oracle

### FIN-RACE-01 — double spend

Initial:

```text
A spendable balance = 100
```

Concurrent:

```text
T1 A -> B 80
T2 A -> C 80
```

If overdraft is forbidden, both cannot commit. Final committed state must be equivalent to a valid serialization and satisfy the account policy.

### FIN-RACE-02 — competing transfers

Initial:

```text
A has exactly enough for only one of two competing transfers under policy
```

Required:

```text
no lost update
no partial transfer
no aggregate spend beyond legal amount
```

### FIN-RACE-03 — stale balance spend

T1 reads old balance. T2 commits a debit. T1 later attempts a debit based on stale state.

Required:

```text
T1 revalidates against authoritative transaction order/state at commit boundary
```

A Kotlin/UI precheck using stale balance alone is insufficient.

### FIN-RACE-04 — duplicate/idempotency race

Two callers submit identical logical operation ID concurrently.

Required:

```text
one economic effect maximum
```

Conflicting payload under same operation ID must not split-brain.

### FIN-RACE-05 — lifecycle race

Account/party/currency target is valid, then another writer concurrently retires/closes/deactivates it while transaction commit races.

Required:

```text
one coherent serialized outcome
```

No transaction may commit against an endpoint that is invalid in the authoritative serialization order.

### FIN-RACE-06 — overflow/limit race

Two individually legal incoming/outgoing operations race near numeric/account limits.

Required:

```text
combined committed result cannot overflow representation or violate account limit policy
```

No wraparound, drift or post-commit invalid aggregate.

These race gates require authoritative transaction/DB/CAS/constraint/locking/equivalent protection. Presentation-level balance locks or unguarded SELECT-then-INSERT are not sufficient evidence.

---

## 25. Scale and complete authoritative history

Phase 13 is intended for long campaigns. Oracle minimum:

```text
>1000 FinancialTransactions remain authoritative and queryable
```

A hard `LIMIT 1000` in an authoritative ledger/history/balance calculation fails.

Bounded readers are allowed only for:

- UI history pages,
- ContextBuilder slices,
- summaries/presentation,
- explicit pagination windows.

Required relationship:

```text
bounded presentation reader != authoritative ledger completeness
```

Balance/income/expense/reconciliation calculations must not silently use only the first/last 1000 rows unless a complete aggregate/index built from all authoritative rows is itself safely maintained/rebuildable.

---

## 26. Reopen / restore / campaign switch

Required persistence semantics:

### Reopen

After database close/reopen:

```text
transaction history identical
stable UIDs identical
exact amounts/currencies identical
derived balance rebuild identical
```

### Restore

Backup/restore must recover the same committed authoritative ledger and its identity/provenance. Restore must not duplicate migration-opening entries.

### Campaign switch

A -> B -> A must return to exactly A's own financial history and derived balance, with no entries from B.

---

## 27. Legacy boundary

Current/legacy finance summaries include concepts such as:

```text
ryo
monthly_income
monthly_expenses
debt
property_value
investment_value
updated_chapter
```

These are not sufficient to reconstruct detailed historical FinancialTransactions.

Forbidden automatic synthesis:

```text
current balance -> invented salary/purchase/payment history
monthly_income -> repeated historical income entries
monthly_expenses -> repeated historical expense entries
debt aggregate -> fabricated creditor/loan contracts
property_value -> fabricated property assets or OwnershipRecords
investment_value -> fabricated shares/assets or OwnershipRecords
reason/display text -> guessed payer/payee/currency/account
active-player label -> assumed holder when stable identity is ambiguous
```

Ambiguous rows remain legacy evidence/unresolved rather than guessed.

---

## 28. Opening balance migration oracle

Canonical docs permit a conservative migration strategy equivalent to a distinguished opening-balance/bootstrap entry when stable mapping is justified.

Semantic statement of an opening entry:

```text
"At migration boundary T, legacy state reports exact balance X for stable holder/account H in currency C."
```

It must NOT claim:

```text
how H historically earned/spent X
```

Required opening-balance properties:

- one stable migration/transaction identity;
- campaign scope;
- stable holder/account identity;
- stable currency mapping;
- exact amount;
- explicit migration effective boundary;
- explicit legacy source/provenance;
- idempotent migration/reopen/restore behavior;
- no fabricated detailed history;
- no duplicate opening entry on repeated ensure.

If holder/currency cannot be mapped unambiguously, no canonical opening transaction should be guessed.

If imported legacy transaction rows plus opening basis do not reconcile, discrepancy must remain explicit instead of silently rewriting history to force a match.

---

## 29. Legacy financial_transactions boundary

Existing legacy `financial_transactions` is evidence, not automatically canonical merely because it is named like the target domain.

A row may be promoted only if its exact schema/content can be mapped losslessly to the canonical Phase-13 contract with stable identity, campaign, endpoints, currency, exact amount, time and provenance.

Otherwise:

```text
preserve legacy evidence
mark unresolved/unmapped
DO NOT fabricate missing semantics
```

Phase-13 cutover must also prevent the old generic mutation path from bypassing new ledger invariants if the canonical ledger reuses or replaces the legacy table.

---

## 30. Relation ledger -> net worth

MASTER states:

```text
net worth = assets - liabilities
```

Therefore Financial Ledger alone does not define net worth.

Phase-13 ledger can provide:

- cash/account balances,
- cash-flow history,
- income/expense summaries,
- transaction evidence relevant to later asset/liability changes.

But final canonical net worth requires Phase-14 assets/liabilities/obligations and valuations.

Forbidden Phase-13 inference:

```text
cash balance == net worth
property_value summary + cash == canonical net worth
financial transaction history alone == asset ownership/value
```

This is a hard phase boundary, not a missing feature to invent in Phase 13.

---

## 31. Required final semantic revalidation checklist

When CHAT-1 publishes its final Phase-13 result commit, CHAT-2 must validate exactly that SHA and not a later unrelated runtime.

Final semantic gates must include at least:

```text
[ ] FinancialTransaction has stable identity and campaign scope
[ ] party/account refs are generic and stable
[ ] currency/value identity is stable and exact
[ ] amount is exact; no Float/Double authority
[ ] zero/negative/malformed precision rejected according to oracle
[ ] overflow/aggregation overflow cannot commit
[ ] normal transfer conserves value exactly
[ ] transaction is atomic; no debit-only/credit-only committed state
[ ] rollback leaves no partial effect
[ ] ledger history is immutable/append-preserved
[ ] corrections/refunds append rather than rewrite history
[ ] ledger -> balance reconstruction is exact
[ ] balance is not competing authoritative history
[ ] income/expense summaries derive from explicit transaction semantics
[ ] payment does not imply OwnershipRecord transfer
[ ] OwnershipRecord transfer does not imply payment
[ ] Inventory/Equipment mutations do not create ledger entries implicitly
[ ] theft/loan/gift semantics preserve domain separation
[ ] purchase/sale do not collapse independent domains
[ ] provenance/source-event/effective-time are preserved
[ ] duplicate retry is idempotent
[ ] conflicting reuse of identity is rejected
[ ] FIN-RACE-01 double spend passes
[ ] FIN-RACE-02 competing transfers passes
[ ] FIN-RACE-03 stale balance spend passes
[ ] FIN-RACE-04 duplicate/idempotency race passes
[ ] FIN-RACE-05 lifecycle race passes
[ ] FIN-RACE-06 overflow/limit race passes
[ ] >1000 records have no authoritative truncation
[ ] reopen preserves complete ledger
[ ] restore preserves complete ledger
[ ] campaign switch/isolation passes
[ ] legacy current balance does not fabricate historical sequence
[ ] opening balance, if used, is explicit/provenanced/idempotent
[ ] monthly income/expenses do not synthesize historical entries
[ ] debt/property/investment summaries do not create Phase-14 authority
[ ] personal vs organization wealth/account identity remains separate
```

Green CI is supporting evidence only and is not sufficient for semantic PASS.

---

## 32. Verdict status

This artifact intentionally does **not** issue:

```text
PHASE 13 SEMANTIC REVALIDATION: PASS
```

or

```text
PHASE 13 SEMANTIC REVALIDATION: FAIL
```

because no final CHAT-1 Phase-13 result commit has been supplied for revalidation in this work item.

The oracle is fixed against the canonical architecture and accepted Phase-12 boundary. Final runtime will be judged against this oracle, not vice versa.

# PHASE 13 FINANCIAL LEDGER / ECONOMY SEMANTIC ORACLE READY
