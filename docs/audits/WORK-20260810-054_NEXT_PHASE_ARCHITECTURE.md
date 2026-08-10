# WORK-20260810-054 — Next Phase Architecture Audit

ROADMAP NEXT PHASE AFTER PHASE 12:
13. Financial Ledger / Economy model

Status: READ-ONLY NEXT-PHASE ARCHITECTURE AUDIT

Work ID: `WORK-20260810-054`
Worker: `CHAT-4`
Repository: `piotreksmaga-art/rpg-os-android`
Accepted Phase 11 runtime: `c87193a69136a6680102779e4f0cd3d90a616d41`
Fresh master observed at audit start: `6c31afaab2d6d72f246655e07fe0cb2f74e88b8f`
Fresh master observed immediately before report write: `8a913d1f94b5e2602684a7c654bb35d588ee5545`
Phase 12 implementation owner: `CHAT-1 / WORK-20260810-051`

This document is architecture/audit only. It does not implement Phase 12, Phase 13, schema, migrations, runtime stores, PlayerCommand, PlayerChangeSet, PlayerDomainEngine, Event Store, Assets/Liabilities or CharacterPanelSnapshot v2.

---

## 1. Roadmap identity and baseline

The current canonical Roadmap orders the relevant phases as:

```text
11. Equipment domain/loadout model
12. OwnershipRecord domain
13. Financial Ledger / Economy model
14. Assets / debts / obligations / net-worth model
```

Therefore the phase directly after Phase 12 is exactly:

```text
13. Financial Ledger / Economy model
```

Roadmap currently classifies it as PARTIAL because `character_finances` and `financial_transactions` already exist, but ledger authority, conservation validation and a domain mutation path are missing.

The accepted Phase 11 runtime remains `c87193a69136a6680102779e4f0cd3d90a616d41`. Commits after that accepted runtime and before this report are Phase 11 revalidation reports plus Phase 12 audit/test-planning artifacts. No accepted Phase 12 runtime was present on master when this report was written. Consequently all Phase 13 implementation remains blocked on the final accepted Phase 12 contract.

---

# current-state audit

## 2. Existing legacy finance state

The current Android runtime already reads a legacy summary row from `character_finances` for the active player:

```sql
SELECT entity_uid,
       ryo,
       monthly_income,
       monthly_expenses,
       debt,
       property_value,
       investment_value,
       updated_chapter
FROM character_finances
WHERE entity_uid=?
LIMIT 1
```

`ContextBuilder` injects those values into the general player `status` map with a `finance_` prefix. This means the GM currently sees finance-like summary values as current context, but there is no typed finance snapshot or ledger reconciliation behind that presentation.

Confirmed legacy meanings visible in runtime:

- `ryo` — current summary balance-like field,
- `monthly_income` — summary recurring-income field,
- `monthly_expenses` — summary recurring-expense field,
- `debt` — summary liability-like field,
- `property_value` — aggregate property valuation,
- `investment_value` — aggregate investment valuation,
- `updated_chapter` — coarse update marker.

These fields are useful migration evidence, but they are not sufficient to reconstruct a complete historical ledger. In particular, aggregate `debt`, `property_value` and `investment_value` must not be silently expanded into invented liabilities/assets or OwnershipRecords.

## 3. Existing financial transaction surface

`financial_transactions` is explicitly listed by `SourceOfTruthRegistry` as an `isExplicitRuntimeTable`, so generic `StatePatchEngine` operations may write it.

This is the most important current Phase 13 integrity gap:

```text
generic AI StatePatch
-> financial_transactions
```

is possible without a dedicated finance-domain validator enforcing:

- balanced/conserved value movement,
- valid currency identity,
- nonnegative transaction amount,
- from/to semantics,
- source/event/provenance completeness,
- idempotency,
- atomic reconciliation with current balance projections,
- account/owner isolation,
- organization-vs-player wealth separation.

`StatePatchEngine` provides a SQLite transaction across the operations inside one patch, but it validates only table writability and operation type. It does not implement accounting semantics. `transactionId` is reported in the result string but is not persisted as a duplicate-commit guard.

## 4. Existing repository boundary

`CampaignRepository` / `UnifiedGameRepository` currently have no typed Finance/Economy API equivalent to the typed stat/resource APIs. The only economy-named repository method is:

```text
economies(): List<EconomySummary>
```

which is a dashboard read delegated to `NpcWorldDashboardReader`. That reader queries:

```sql
SELECT country_uid, treasury, prosperity, stability
FROM country_economies
ORDER BY treasury DESC
```

This is world/country presentation state, not the canonical player/actor Financial Ledger required by Phase 13.

Therefore:

```text
country_economies dashboard
!= Financial Ledger authority
```

and Phase 13 must not reuse `EconomySummary` as its canonical transaction model.

## 5. ContextBuilder boundary

Current ContextBuilder finance behavior has four architectural weaknesses:

1. finance is embedded in a generic `status` map rather than a typed economy snapshot;
2. only `character_finances` is read; `financial_transactions` is not provided as bounded ledger history;
3. direct SQL exceptions are swallowed by `safeQuery*`, so absent/broken finance schema can degrade silently to missing context;
4. summary fields can look authoritative to the GM even though the ledger is not currently authoritative.

Future Phase 13 integration should expose a bounded derived economy read model produced from the canonical ledger plus migration-safe legacy evidence, not make the AI infer accounting state from raw tables.

## 6. CharacterPanel and player-state classification

`CharacterPanelSnapshot` v1 has no economy or assets section. This is correct to preserve for now because Roadmap places CharacterPanelSnapshot v2 later.

MASTER classification for Phase 13 should be:

```text
AUTHORITATIVE:
  committed FinancialTransaction ledger entries
  currency/account definitions required to interpret those entries
  explicit migration-opening entries/evidence where migration can justify them

DERIVED:
  current balance per account/currency
  income/expense summaries
  cash-flow summaries
  ledger aggregates
  spend/income by period/category

CACHE / PRESENTATION:
  ContextBuilder economy slice
  CharacterPanel economy summary
  dashboard totals

NOT PHASE 13 AUTHORITATIVE ASSETS:
  property_value
  investment_value
  net worth
  individual debt/receivable instruments
```

Phase 14 remains responsible for Assets / debts / obligations / net-worth. Phase 13 may carry financial transfers related to those domains later, but must not prematurely make aggregate asset/debt summary fields into canonical asset identities.

---

# canonical domain separation

## 7. Required semantic split

Phase 13 must preserve these independent concepts:

```text
Financial transaction history
!= current balance projection
!= inventory possession
!= equipment state
!= ownership right
!= asset valuation
!= liability / obligation
!= country economy dashboard
```

A purchase is a useful integration example. The final future transaction may include:

```text
money movement
+ ownership transfer
+ possession/inventory transfer
+ event/provenance
```

but these are separate domain effects committed atomically. A money payment by itself must not imply ownership transfer. Ownership transfer by itself must not fabricate a payment. Inventory movement must not imply either ownership or payment.

Likewise:

- theft can change possession while producing no legal sale transaction;
- a gift can transfer ownership with a zero-price or no-money transfer;
- a loan of an item can change possession without ownership or sale;
- salary/reward can change money without asset ownership;
- organization funds are not automatically player funds.

## 8. MASTER accounting contract

MASTER states that money is accounting-based and that `FinancialTransaction` should contain semantically equivalent data to:

```text
from
/to
currency
amount
reason
event
time
provenance
```

It also states:

```text
Balance may be cache; ledger explains history.
Personal wealth != organization wealth.
Net worth = assets - liabilities.
```

Therefore the Phase 13 design should make the immutable/append-only ledger the explanatory authority and treat balance as a rebuildable projection.

---

# generic Core model

## 9. Generic actor/account model

Core must not hardcode Naruto `ryo` or Bleach/world-specific currencies. Currency is definition data supplied by Core/default content or World Pack/campaign configuration.

A robust generic shape is:

```text
CurrencyDefinition
- currencyUid               stable UID
- worldPackUid / namespace
- currencyKey
- displayName
- precision / minor-unit semantics
- status / version
- provenance
- metadata
```

Do not use floating-point storage for conserved money if exact fractional units are possible. Recommended persisted amount representation is integer minor units when a fixed scale exists, or an exact decimal textual/integer+scale representation. SQLite `REAL` should not become the canonical conserved amount representation.

Funds should belong to a stable account/holder identity rather than to the active-player label:

```text
FinancialAccount
- campaignId
- accountUid                stable UID
- holderEntityUid           player/NPC/org/state/business/etc.
- accountTypeUid            extensible generic type
- currencyUid
- openedAt
- closedAt?
- version
- provenance
```

If Phase 13 implementation intentionally chooses a lighter first contract without a separately persisted `FinancialAccount`, it must still preserve equivalent stable `holder + currency + account-scope` identity and leave room for multiple accounts later. It must not use `ActivePlayerRef` as the universal owner of finance state.

## 10. Canonical FinancialTransaction

Semantic target:

```text
FinancialTransaction
- financialTransactionUid   stable UID / idempotency target
- campaignId
- fromAccountUid?           null only for an explicitly defined external/source boundary
- toAccountUid?             null only for an explicitly defined sink/boundary
- currencyUid
- amountExact               > 0, exact representation
- transactionTypeUid        extensible generic classification
- reason
- campaignTime              deterministic temporal marker
- createdTurn?
- sourceEventUid?
- commandUid? / transactionUid?
- provenance                nonblank structured provenance
- status                    preferably COMMITTED/VOID semantics without destructive delete
- metadata?
```

A normal internal transfer should conserve value exactly:

```text
source delta = -amount
destination delta = +amount
sum deltas = 0
```

Money creation/destruction must use explicit typed source/sink semantics authorized by rules, not `from=NULL` / `to=NULL` as an unvalidated escape hatch.

Examples of legitimate source/sink categories may include minting, taxation sink, system bootstrap/migration opening balance, or world-rule issuance. The Core should model the mechanism generically; World Packs decide universe-specific legality and names.

## 11. Ledger immutability and corrections

Committed financial history should be append-only. Do not allow arbitrary update/delete of a committed transaction to repair a mistake.

Preferred correction model:

```text
original transaction T1 remains
reversal/correction transaction T2 references T1
new corrected transaction T3 if required
```

This preserves auditability and future replay.

`financial_transactions` should therefore leave the generic StatePatch writable surface when Phase 13 becomes authoritative. Writes should pass through the typed finance-domain path, just as `campaign_truth_records` is already blocked from generic StatePatch and uses a dedicated contract.

## 12. Balance projection

Current balance should be mechanically derivable:

```text
opening/migration ledger basis
+ committed incoming entries
- committed outgoing entries
= current balance
```

A persisted balance table is acceptable only as DERIVED/cache with reconciliation metadata. It must be deletable/rebuildable without loss of financial history.

Legacy `character_finances.ryo` cannot automatically be treated as both canonical balance and ledger history after Phase 13. During migration it should be handled by an explicit migration policy, for example a one-time opening-balance entry only when the legacy row is unambiguously attributable to the active/stable entity and currency. The entry must say that its provenance is legacy migration; it must not invent historical transactions.

## 13. Recurring income/expense semantics

`monthly_income` and `monthly_expenses` are summaries, not proof of scheduled transactions. Do not synthesize years of historical salary/rent/payment entries from those aggregates.

Phase 13 should preserve them as legacy evidence/read compatibility until a later Scheduler/Time Skip integration can generate future committed transactions from explicit recurring obligations/income rules.

Future recurrence path:

```text
Scheduler / Time Skip proposal
-> finance rule resolution
-> validated FinancialTransaction changes
-> TurnTransaction
-> ledger commit
```

not direct periodic mutation of balance.

---

# legacy boundary

## 14. Lossless migration rules

Phase 13 migration must be additive and conservative.

Required rules:

1. Never delete `character_finances` or `financial_transactions` during first adoption.
2. Never infer detailed history from a current summary balance.
3. Never infer payer/payee from `reason` text.
4. Never infer currency from display names when a stable mapping is ambiguous.
5. Never turn `property_value` or `investment_value` into OwnershipRecords/assets.
6. Never turn aggregate `debt` into fabricated creditors/contracts.
7. Never assume a finance row belongs to the active player unless stable entity identity proves it.
8. Preserve legacy raw evidence and explicit mapping/version/provenance for every promoted record.
9. Unmappable rows remain legacy-unresolved rather than silently dropped or guessed.
10. Migration rerun must be idempotent.

## 15. Legacy opening-balance strategy

Because historical transaction completeness is not established, a safe migration may need a distinguished bootstrap concept such as:

```text
MIGRATION_OPENING_BALANCE
```

This records only:

```text
"At migration boundary, legacy state reports balance X for holder H in currency C."
```

It does not claim how H earned or spent that money.

If legacy `financial_transactions` can be mapped losslessly to the canonical contract, they may be imported as individual ledger evidence. The implementation audit must compare the exact legacy schema/rows before choosing this path. If legacy transactions plus opening state cannot reconcile, the migration must expose the discrepancy instead of rewriting history until the arithmetic matches.

---

# migration risks

## 16. Primary risks

### R1 — double authority
Keeping writable `character_finances.ryo` while also declaring the new ledger authoritative creates two truths. After cutover, summary balance must be derived/read-compatible only, or guarded from independent mutation.

### R2 — generic StatePatch bypass
`financial_transactions` is currently generic-patch writable. Leaving that path open would allow AI/UI mutations to bypass conservation, idempotency and provenance checks.

### R3 — invented ledger history
Backfilling synthetic historical transactions from aggregate current fields would violate campaign continuity and provenance.

### R4 — floating point drift
Using SQLite `REAL` for conserved currency can make repeated transfers fail exact reconciliation at long-campaign scale.

### R5 — player/organization leakage
A single `entity_uid -> ryo` model can accidentally treat organizational or state wealth as player wealth. Stable holder/account identity is required.

### R6 — cross-campaign collision
Every transaction/account projection must be scoped by `campaignId`; stable UIDs alone must not permit cross-campaign reads/writes.

### R7 — Phase 12 contract drift
Phase 13 purchase/sale integration depends on the accepted OwnershipRecord asset/owner reference scheme. Implementing against the preparatory report instead of final Phase 12 runtime could create incompatible UIDs or duplicate asset references.

### R8 — transaction half-commit
A purchase that writes money but fails ownership/inventory transfer, or vice versa, creates impossible state. Until the later global TurnTransaction phase exists, any interim Phase 13 operation that spans current Phase 10–12 state must still use one SQLite transaction and fail closed.

### R9 — replay/double reward
Mission reward or retry paths can duplicate payment unless stable command/event/financialTransaction UIDs make repeated commits return an already-applied result.

### R10 — silent read failure
ContextBuilder's current exception-swallowing SQL readers can hide a broken/missing finance migration. Typed Phase 13 reads should fail diagnostically at repository/domain boundaries rather than silently report zero/missing money as authoritative.

---

# integration boundaries

## 17. Phase 3 — Player State Contract

Phase 13 must use the persisted active player UID for player-facing projections but must not restrict the finance domain to the active player. Financial holders may be NPCs, organizations, states or businesses.

Finance current balance belongs conceptually to persistent campaign state derived from immutable transaction history; it is not runtime combat state.

## 18. Phases 4–5 — Stats/resources/modifiers

Money is not a `PlayerResource` and should not be encoded as a resource definition. Currency/account balance obeys accounting invariants, not stat/resource max/regeneration/modifier semantics.

## 19. Phases 6–9 — Talent/Skill/Technique/Innate

These domains may create economic reasons/costs/rewards later, but they must not directly write balance. They propose financial effects that the finance domain validates and commits.

## 20. Phase 10 — Inventory

Purchase/sale may eventually coordinate:

```text
FinancialTransaction
+ inventory possession change
```

but inventory quantity/instance identity remains separate from currency balance. A commodity item must not automatically become currency just because it is fungible.

## 21. Phase 11 — Equipment

Equipment is unrelated to finance authority except through explicit transactions such as purchase, sale, repair or rental. Equipping/unequipping must never alter money automatically.

## 22. Phase 12 — OwnershipRecord

This is the hard implementation dependency.

Phase 13 must consume the accepted Phase 12 stable owner/asset reference semantics for purchases, sales, gifts with consideration, shares or other ownership-changing transactions. It must not duplicate owner/asset columns into a competing economic ownership subsystem.

Expected future atomic purchase composition:

```text
validate payer/payee money
validate asset + seller ownership
validate inventory/possession rules
-> append financial ledger entry
-> close/create OwnershipRecord as needed
-> change inventory possession as needed
-> append causal event/provenance
-> one commit
```

The exact ownership APIs/tables cannot be frozen by this report because WORK-20260810-051 is still implementing Phase 12.

## 23. Event/provenance boundary

MASTER requires meaningful changes to be explainable causally. Each canonical financial entry must be attributable to event/command/source provenance.

Phase 13 precedes the canonical Event Store and TurnTransaction phases in Roadmap, so it must not invent a competing event system. It should reserve stable `sourceEventUid` / `commandUid` / `transactionUid` linkage and preserve provenance so later phases can integrate without destructive migration.

## 24. World-Pack boundary

Core owns:

- ledger mechanics,
- exact amount representation,
- account/holder scoping,
- conservation rules,
- append-only/correction semantics,
- transaction idempotency,
- balance projection,
- repository/domain interfaces.

World Pack or world/campaign content owns:

- currency definitions and names (`ryo`, or any other setting-specific currency),
- who may mint/issue currency,
- taxes/fees/exchange/legal rules,
- world-specific transaction classifications/rules,
- canon starting-economic data.

Forbidden Core hardcodes include `ryo`, ninja missions, villages, chakra, reiatsu or setting-specific banks.

---

# test gates

## 25. Required Phase 13 semantic gates

Implementation must not be accepted without at least these gates:

### Identity / isolation
- transaction UID stable and unique in campaign scope;
- account/holder stable UID, no name-based identity;
- no cross-campaign leakage;
- active player projection resolves exactly the persisted ActivePlayer UID;
- player funds remain distinct from organization/state funds.

### Accounting
- transfer of X produces exactly `-X/+X` for internal transfer;
- source/sink creation/destruction requires explicit allowed type;
- zero/negative or invalid exact amount rejected according to contract;
- currency mismatch rejected unless explicit exchange operation exists;
- balance rebuilt from ledger equals cached/projected balance;
- deleting derived balance/cache and rebuilding loses no information.

### Immutability / provenance
- committed transaction cannot be generic-update/deleted;
- correction uses reversal/superseding entry, original remains queryable;
- every promoted canonical entry has nonblank provenance;
- event/command linkage preserved when supplied.

### Idempotency / concurrency
- same command/transaction UID committed twice changes balance once;
- two simultaneous spends cannot both pass against the same insufficient funds snapshot;
- concurrent reward retries do not duplicate money;
- read-check-write TOCTOU cannot violate balance policy.

### Migration
- old campaign -> Phase 13 -> same observable legacy current balance where mapping is valid;
- migration rerun is idempotent;
- ambiguous legacy transaction remains unresolved, not guessed;
- aggregate property/investment/debt fields remain unexpanded evidence;
- opening-balance provenance explicitly says migration/bootstrap;
- legacy rows remain recoverable/auditable.

### Integration
- purchase failure in ownership/inventory rolls back finance effect;
- finance failure rolls back ownership/inventory effect;
- theft/loan/equip operations do not accidentally create financial transaction;
- gift may transfer ownership with no fabricated payment;
- mission reward can create one explicit payment without bypassing ledger.

### Context / presentation
- ContextBuilder gets bounded typed economy summary/history;
- presentation values derive from authoritative ledger, not become a write source;
- missing/corrupt finance schema is diagnosable, not silently rendered as an authoritative zero.

---

# concurrency risks

## 26. SQLite write serialization is necessary but not sufficient

Phase 11 already demonstrates an important pattern: authoritative invariants vulnerable to stale application pre-reads require enforcement within the same SQLite write transaction, including database-level guards where appropriate.

Phase 13 has equivalent TOCTOU hazards:

```text
T1 reads balance 100
T2 reads balance 100
T1 spends 80
T2 spends 80
```

If both validators rely only on pre-read application state, both can approve. The canonical write path must serialize/check the authoritative balance basis inside the same transaction that appends the ledger effect.

The exact policy on overdraft/negative balances should be generic/configurable; but whichever policy applies must be transactionally enforced.

Other races to test:

- duplicate transaction UID insertion,
- simultaneous reversal of one transaction,
- currency/account close vs concurrent payment,
- ownership sale of the same asset to two buyers,
- inventory transfer vs sale commit,
- migration/bootstrap running concurrently with a finance write,
- snapshot/context read during partially assembled multi-domain transaction.

A database UNIQUE key on stable transaction/command identity is preferable to application-only duplicate detection.

---

# scale requirements

## 27. Long-campaign ledger scale

RPG OS targets hundreds of thousands of turns and millions of events. Finance therefore must assume ledger growth to very large row counts.

Required characteristics:

- append-oriented writes;
- exact indexed lookup by `campaignId + financialTransactionUid`;
- indexes for `campaignId + account/holder + currency + time`;
- bounded recent-history ContextBuilder retrieval;
- aggregate queries must not full-scan the ledger each GM turn;
- balance projection may use rebuildable checkpoints/materialized summaries, but never as sole history;
- pagination/keyset retrieval for audit/history UI;
- correction/reversal linkage indexed;
- migration should stream/batch large legacy histories and avoid loading entire ledgers into memory;
- backup/restore must preserve ledger and idempotency keys exactly;
- future snapshot/replay must reconstruct identical balances.

A suggested query contract should support:

```text
balance(account, currency, asOfTime?)
recentTransactions(account, beforeCursor, limit)
transactionsByEvent(eventUid)
transactionsByCommand(commandUid)
reconcile(account, currency)
```

Historical `asOfTime` should be possible from ledger time ordering even before the full Temporal Engine arrives, without overwriting past entries.

---

# blockers/dependencies

## 28. Hard blockers

### BLOCKER A — Phase 12 runtime not accepted
Phase 13 must not implement purchase/sale ownership integration until WORK-20260810-051 produces the final Phase 12 runtime and that runtime is accepted. Phase 13 must consume that real contract, not only WORK-049's preparatory architecture.

### BLOCKER B — exact legacy `financial_transactions` schema/data audit at implementation time
The repository proves that the table exists and is writable, but this report does not have a typed Kotlin schema contract for every legacy column/value. Before migration code is written, implementation must perform exact `PRAGMA table_info`, row-shape/value/nullability and index inspection against bundled and representative old campaign DBs.

### BLOCKER C — current generic StatePatch write path
Phase 13 cannot be considered authoritative while generic StatePatch can independently mutate canonical financial ledger rows. Cutover must close that bypass.

## 29. Dependencies already available

The following earlier contracts provide usable foundations:

- Phase 1 unified CampaignRepository / campaign identity;
- Phase 2 provenance patterns and typed protected truth path;
- Phase 3 stable active player identity and Persistent/Derived/Runtime separation;
- Phases 4–5 generic definition/derived-state design patterns;
- Phases 6–9 stable typed player-domain identities and provenance patterns;
- Phase 10 stable ItemDefinition/ItemInstance/inventory possession identities;
- Phase 11 transaction-safe SQLite invariant patterns for concurrent equipment writes.

Phase 12 is the remaining immediate prerequisite.

## 30. Recommended implementation order after Phase 12 acceptance

This is a future implementation sequence only; this work item does not execute it.

1. Re-read fresh master and accepted Phase 12 schema/store/API.
2. Exact column/data/index audit of `character_finances` and `financial_transactions` in bundled + old campaigns.
3. Freeze exact money representation and currency/account identity contract.
4. Add additive Phase 13 schema and explicit legacy evidence/mapping/bootstrap semantics.
5. Add typed finance repository/store path with append-only transactions and idempotency.
6. Enforce conservation and concurrent balance policy inside authoritative write transaction.
7. Remove/block canonical finance writes from generic StatePatch.
8. Add rebuildable balance/current-finance projection.
9. Integrate bounded typed ContextBuilder economy read without making presentation authoritative.
10. Add migration, semantic, integrity, adversarial and concurrency gates.
11. Only after Phase 13 itself is accepted proceed to Phase 14 Assets / debts / obligations / net-worth.

---

## 31. Final architecture verdict

The next Roadmap phase is not an arbitrary generic "economy system". The immediate canonical delta is to turn the currently split legacy state:

```text
character_finances summary
+
financial_transactions generic writable table
```

into an auditable accounting authority where:

```text
stable typed FinancialTransaction ledger
= authoritative history

balance / income / expense summaries
= derived projections

currency rules / labels
= extensible World-Pack/content definitions

ownership / inventory / equipment / assets / liabilities
= separate domains integrated atomically, never inferred
```

The two highest-risk defects to eliminate are dual authority (`ledger` vs mutable summary balance) and generic StatePatch bypass. The highest dependency risk is implementing purchase/sale semantics before the accepted Phase 12 owner/asset identity contract exists.

No Phase 13 runtime implementation should start from this report until Phase 12 is accepted and fresh master is re-audited.

NEXT PHASE ARCHITECTURE READY — IMPLEMENTATION BLOCKED BY PHASE 12
