# WORK-20260810-058 — Phase 13 Migration / Integrity Plan

Status: READ-ONLY RUNTIME / VALIDATION PLAN

Work ID: `WORK-20260810-058`
Role: `READ-ONLY PHASE 13 MIGRATION / INTEGRITY AUDITOR`
Repository: `piotreksmaga-art/rpg-os-android`
Accepted Phase-12 runtime baseline: `d5f1fd6e7a660e3e398f155784f8602c486b9906`
Accepted Phase-12 state: SEMANTIC PASS / INTEGRITY PASS / ADVERSARIAL PASS
Phase-13 architecture input: `docs/audits/WORK-20260810-054_NEXT_PHASE_ARCHITECTURE.md`
Fresh master observed before report write: `b08ee3253e62c68ba5a4bccd1840d77644c76a0f` (report-only Phase-13 adversarial matrix on top of accepted Phase-12 runtime)
Allowed write scope: this report only.

This document defines independent release gates for Phase 13 Financial Ledger / Economy migration, persistence, reference integrity, concurrency, scale and Phase 3–12 preservation. It does not implement Phase 13 and does not issue a final PASS/FAIL before CHAT-1 supplies a final runtime result commit.

---

## 1. Canonical boundary

The Phase-13 hard separation is:

```text
Inventory possession
!= Equipment state
!= OwnershipRecord
!= Financial Ledger
```

Financial transaction history is also distinct from:

```text
current balance projection
asset valuation
liability / obligation
country economy dashboard
```

A payment alone must not transfer Inventory possession or OwnershipRecord title. Ownership transfer alone must not fabricate a payment. Equip/unequip must not create finance entries. Theft/custody/loan must not become legal sale/payment history unless an explicit finance-domain operation is committed.

Phase 14 remains responsible for Assets / debts / obligations / net-worth. Phase 13 must not manufacture Phase-14 identities from `property_value`, `investment_value`, or aggregate `debt`.

---

## 2. Repository baseline and migration chain precondition

At plan creation the production latest-schema route is still:

```text
CurrentSchema.ensure(saveDb, campaignId)
-> MigrationManager.ensureV12(saveDb, campaignId)
```

The final Phase-13 candidate must change the production latest-schema route to V13 or equivalent while retaining the complete additive chain:

```text
Phase 3
-> Phase 4
-> Phase 5
-> Phase 6
-> Phase 7
-> Phase 8
-> Phase 9
-> Phase 9 requirement hotfix
-> Phase 10
-> Phase 11
-> Phase 12
-> Phase 13
```

Direct unit invocation of `ensureV13()` is insufficient. Final validation must prove the production path used by `LocalGameStore` / repository bootstrap, restore and campaign switching.

---

## 3. Lessons inherited from Phase 10–12

Phase 10 established the migration pattern that legacy evidence must be preserved losslessly, same labels cannot define identity, authoritative readers may not silently truncate >1000 rows, and backup/restore must preserve typed state plus legacy evidence.

Phase 11 established that application prechecks are insufficient for race-sensitive invariants. Slot/possession consistency was accepted only after SQLite write-boundary guards closed TOCTOU races.

Phase 12 added two further rules that are mandatory for Phase 13:

1. nonblank UID strings are not proof that a target exists;
2. `PRAGMA foreign_key_check = clean` is not proof of generic-reference validity when authority is provided by registries/resolvers/triggers rather than direct FKs.

Final Phase-13 validation must inspect actual authoritative write boundaries, not infer correctness from service-layer intent.

---

# MIGRATION / ROUTING GATES

## 4. Additive V13 migration

PASS requires:

- V13 first ensures V12;
- no destructive drop/rewrite/truncation of Phase 3–12 authoritative tables;
- Phase-13 schema creation occurs transactionally;
- migration marker/version is written only after successful schema establishment;
- a failed V13 migration does not leave a half-created ledger contract;
- legacy `character_finances` / existing `financial_transactions` bytes are not silently destroyed;
- Phase-13 triggers/indexes/reference registries required for integrity are present after migration;
- migration is forward-only; normal runtime never attempts an automatic downgrade from V13 to V12.

A migration may create new canonical ledger/account/currency tables and explicit legacy mapping/evidence tables. It may not convert presentation/summary fields into fabricated detailed history without a documented evidence contract.

## 5. Clean bootstrap

Test a fresh bundled/default campaign through the actual app bootstrap path.

Required result:

- full CurrentSchema route reaches V13;
- exactly one V13 marker exists;
- canonical Phase-13 tables/indices/triggers exist;
- required default currency/account/reference definitions are deterministic and idempotent if the architecture requires them;
- no duplicate opening balances or bootstrap transactions are created by a second bootstrap;
- `PRAGMA integrity_check = ok`.

## 6. V12 -> V13 upgrade

Build a fixture at exactly the accepted V12 schema, populate representative data from Phases 3–12, close the DB, reopen under the V13 runtime and invoke only the normal production ensure path.

Validate:

- V13 marker appears exactly once;
- all Phase 3–12 authoritative rows survive semantically unchanged;
- no OwnershipRecord, Inventory or Equipment mutation is caused by finance migration;
- finance legacy promotion follows only explicit deterministic rules;
- a V12 DB with no sufficient finance evidence receives no invented transaction history.

## 7. Full Phase 3 -> V13 chain

Create/obtain an older Phase-3-compatible fixture and run the real latest CurrentSchema path through all later migrations.

Required:

- complete marker chain appropriate to the implementation;
- no skipped dependency such as V9 requirement hotfix;
- no schema-name collision between historical finance tables and new Phase-13 tables;
- no reliance on manually invoking intermediate migration functions outside production routing;
- final Phase-13 ledger state consistent with the same conservative legacy rules as direct V12 -> V13.

## 8. Reopen

For every important Phase-13 state fixture:

```text
write -> close DB -> reopen -> CurrentSchema.ensure -> exact authoritative equality
```

Preserve at minimum:

- FinancialTransaction UID;
- campaign scope;
- party/account references;
- currency/value identity;
- exact amount representation;
- transaction order/time;
- source event/command/operation IDs if present;
- provenance/version/status;
- opening-balance/migration evidence;
- reversal/correction links if supported.

Reopen must not regenerate transaction UIDs, duplicate derived balances, or replay opening entries.

## 9. Repeated ensure / idempotency

Execute:

```text
ensure -> ensure -> ensure -> close -> reopen -> ensure
```

Validate:

- one migration marker;
- no duplicated accounts/currencies/reference targets;
- no duplicated opening balances;
- no duplicated imported legacy transactions;
- no changed provenance/version/time fields;
- already-migrated databases receive any required guard/trigger refresh deterministically without modifying committed ledger history.

## 10. Restore

Mandatory scenarios:

1. V12 backup -> restore under V13 app -> automatic V13 migration.
2. V13 backup containing current + historical ledger entries -> restore -> exact ledger equality.
3. V13 backup containing explicit migration/opening evidence -> restore -> no replay/duplication.
4. Restore while another campaign is active must remain scoped to the active campaign contract and must not leak ledger state.

After restore run latest CurrentSchema and then integrity/reference checks.

## 11. Campaign switch A -> B -> A

Create two campaigns with intentionally colliding strings:

- same account UID;
- same party UID;
- same transaction UID if namespace contract permits campaign-scoped identity;
- same currency UID where global currency identity is intended;
- distinct balances and histories.

Switch:

```text
A -> B -> A
```

Required:

- reads after each switch expose only the active campaign;
- no store/cache remains bound to previous campaign;
- no migration marker/opening transaction is duplicated on return to A;
- a transaction in B cannot mutate A;
- balance derivation is campaign-scoped.

## 12. Schema marker/version / forward-only behavior

The final candidate must expose a stable Phase-13 migration ID/version.

PASS requires:

- marker count exactly one per schema DB where marker semantics are global;
- V13 marker cannot be written before V13 objects are usable;
- reopening an already-V13 DB is a no-op except deterministic guard refreshes explicitly designed as such;
- runtime does not destructively reinterpret V13 DB as V12;
- restore of an older DB moves forward through the current path.

---

# LEDGER INTEGRITY GATES

## 13. Stable FinancialTransaction UID

Every committed canonical financial entry must have a stable identity independent of display reason, row order, amount label or current balance.

Test:

- nonblank UID required;
- duplicate committed UID in its authoritative namespace rejected or replayed as an exact idempotent no-op according to the contract;
- same UID with changed immutable fields must fail loudly;
- UID survives reopen and restore;
- correction/reversal creates a new transaction UID and preserves original history rather than rewriting it.

## 14. Campaign scope

All authoritative ledger reads/writes must scope by campaign.

Test same account/party/transaction strings across campaigns and prove:

- reads do not cross;
- transfers do not cross;
- idempotency lookup does not treat another campaign's transaction as already committed unless transaction identity is intentionally global and the implementation proves it;
- balance aggregation never sums another campaign.

## 15. Exact monetary representation

Canonical conserved money must not use SQLite `REAL`, Kotlin `Float`, or `Double` authority.

Acceptable designs include integer minor units or an equivalently exact decimal/integer+scale representation.

Required tests:

- zero amount rejected for normal value movement unless a specific zero-value transaction type is explicitly legal and semantically non-monetary;
- negative amount rejected as input; direction belongs to from/to or typed entry semantics;
- amount above canonical maximum rejected;
- exact repeated split/recombine produces original amount;
- unsupported precision fails loudly rather than rounds silently;
- arithmetic uses checked overflow semantics.

If currency precision is configurable, test at least two precision definitions and a value not representable in the target currency.

## 16. Currency/value identity

If Phase 13 introduces `CurrencyDefinition` or equivalent, validate:

- stable currency UID;
- exact precision/minor-unit semantics;
- unknown currency rejected;
- deprecated/inactive currency behavior explicit;
- World Pack/campaign scoping follows the chosen contract;
- same display name never substitutes for currency UID;
- transaction amount interpretation cannot change retroactively because a mutable definition silently changes precision.

If the implementation intentionally supports only one fixed currency initially, final validation must confirm this is an explicit stable definition contract, not a hardcoded string/label bypass that prevents future World Packs.

## 17. Party/account references

If `FinancialAccount` exists, validate stable account UID and its holder/currency relation.

If a lighter `holder + currency + account scope` model is used, apply the same reference requirements to that composite authority.

Required failures:

- blank identity;
- nonexistent account/party;
- wrong campaign;
- unknown namespace/type;
- inactive/closed/retired target when new transactions are prohibited;
- account currency mismatch;
- same UID in different party/account namespaces must not collide.

A legal generic non-player holder (NPC/organization/state/business or equivalent registered type) must remain representable. Core may not hardcode finances to ActivePlayer only.

## 18. Source / provenance / time

Every authoritative committed financial transaction must retain sufficient audit evidence.

Validate implementation equivalents of:

- reason / transaction type;
- source event or operation/command identity where available;
- deterministic campaign order/time;
- provenance;
- migration/opening source if applicable;
- version/status.

Blank required provenance must fail. Migration may not invent historical event IDs when none exist; it must use explicit migration provenance instead.

Order/time must be deterministic enough to answer ledger history ordering after reopen/restore.

## 19. Immutable committed history

Committed financial history must be append-preserved.

Direct SQL/API attempts to:

- change amount;
- change source/destination;
- change currency;
- change campaign;
- change transaction UID;
- delete a committed transaction

must fail at the authoritative boundary unless the model has an explicitly documented mutable pre-commit state that is not yet ledger truth.

Corrections should use reversal/correction entries rather than mutation of committed history.

## 20. Duplicate identity / operation idempotency

Test repeated submission of exactly the same stable operation/transaction ID:

- first commit succeeds;
- retry returns existing committed result or deterministic already-committed outcome;
- no duplicate debit/credit;
- no duplicate ledger row;
- no duplicate opening-balance row.

Then retry with the same idempotency key but changed amount/source/destination/currency/provenance-relevant identity. It must fail, not silently reuse the old result as if semantically identical.

## 21. Atomic internal transfer

For a normal same-currency internal transfer:

```text
source delta = -amount
destination delta = +amount
net conserved delta = 0
```

Required test:

- both sides become visible together;
- any failure after source validation but before destination commit rolls back all effects;
- derived balance cache/projection cannot commit only one side;
- transaction history is sufficient to rebuild both balances.

If the model records one bilateral FinancialTransaction rather than two ledger legs, the same atomic/conservation semantics must be derivable without ambiguity.

## 22. Rollback

Inject failures at each meaningful write step:

- invalid destination reference;
- currency mismatch;
- amount overflow;
- duplicate transaction UID;
- source insufficient funds if Phase-13 rules disallow negative balances;
- trigger/constraint failure;
- derived projection/cache failure if it participates in the same transaction.

After each failure:

- no partial ledger entry;
- no partial account delta;
- no changed balance projection;
- no unrelated Inventory/Equipment/Ownership mutation.

## 23. Negative balance / overdraft policy

The architecture must explicitly state whether accounts may go below zero.

If prohibited, balance sufficiency is a race-sensitive invariant and must be protected at authoritative commit boundary.

If permitted for specific account types, overdraft authority must be typed/rule-based and must not become a universal escape from double-spend tests.

The migration/integrity audit must not assume either policy from UI behavior.

---

# REFERENCE INTEGRITY GATES

## 24. General rule

Phase 12 established:

```text
string UID != valid reference
```

For every Phase-13 reference category — party, holder, account, currency, organization, external boundary, and any asset reference actually introduced by Phase 13 — final validation must identify the authoritative resolution strategy.

Acceptable strategies include:

- direct campaign-scoped FK;
- generic registry + namespace FK + target row;
- SQLite trigger/resolver guard;
- transaction-authoritative resolver with equivalent integrity and race protection.

Application `require(uid.isNotBlank())` alone is not sufficient.

## 25. Reference lifecycle

For mutable lifecycle targets, test:

```text
target ACTIVE
T1 validates transaction
T2 retires/closes/deletes target
T1 attempts commit
```

Required: one coherent serialized outcome. A stale target validation cannot create a newly committed transaction against an invalid reference.

If lifecycle deletion is forbidden once ledger history references a target, prove the DB/reference boundary preserves historical resolvability.

## 26. External source/sink boundaries

`from=NULL` or `to=NULL` must not be an unrestricted bypass.

If the model supports external mint/source/sink semantics, require:

- explicit registered/typed boundary identity or transaction type;
- provenance;
- rule authorization;
- exact amount;
- campaign/currency scope;
- idempotency.

Unknown external source/sink strings must be rejected.

---

# CONCURRENCY / TOCTOU RELEASE GATES

## 27. FIN-RACE-01 — double spend

Initial state:

```text
A balance = 100
```

Concurrent:

```text
T1: A -> B 80
T2: A -> C 80
```

If negative balance is not permitted, at most one transfer may commit.

Forbidden final state:

```text
both commits succeed based on stale balance read
```

Test with two independent SQLite connections/transactions and synchronization barrier proving competing execution.

## 28. FIN-RACE-02 — competing source transfers

Use a source with exactly enough value for one of two mutually exclusive transactions, including cases with different destinations and different transaction UIDs.

The authoritative source/account/balance boundary must serialize them. Service-level pre-read is not evidence.

## 29. FIN-RACE-03 — stale balance

Explicitly reproduce:

```text
T1 reads balance N
T2 commits a debit
T1 attempts transaction calculated from old N
```

Required: T1 revalidates atomically at commit boundary or fails through a DB-authoritative invariant. Cached/presentation balance may not authorize spend.

## 30. FIN-RACE-04 — duplicate/idempotency transaction

Two simultaneous callers submit the same stable financial transaction/operation UID.

Required:

- exactly one economic effect;
- both calls may return success/already-committed according to API contract, but only one ledger mutation exists;
- no duplicate source/destination delta.

A unique constraint without semantic replay validation is insufficient if the losing caller can partially mutate another table before uniqueness failure.

## 31. FIN-RACE-05 — party/account lifecycle vs transaction

Race an account/party close/retirement against a transaction that references it.

Required coherent outcomes:

- transaction commits first -> lifecycle operation sees committed dependency and follows policy; or
- lifecycle commits first -> transaction is rejected.

No stale validation window may leave a transaction referencing a target that became invalid in the same commit order.

## 32. FIN-RACE-06 — overflow/limit concurrency

Where account balance, aggregate, supply, credit limit or transaction counters have a finite numeric ceiling, race two individually valid additions that jointly exceed the limit.

Required: combined committed state remains within exact numeric range. No wraparound, SQLite dynamic-type coercion, `SUM()` overflow surprise, or stale maximum precheck may produce invalid authority.

## 33. Authoritative DB/write boundary requirement

For every race above, final report must identify the actual protection:

- SQLite transaction serialization;
- CAS update;
- conditional debit;
- trigger;
- unique constraint;
- FK/registry lifecycle guard;
- or equivalent authoritative mechanism.

Kotlin/application precheck by itself is a release blocker for invariants whose truth can change between read and write.

---

# LEGACY MIGRATION GATES

## 34. Legacy preflight

Before accepting any automatic finance promotion, inspect the real bundled/current legacy schema with PRAGMA and actual readers:

```sql
PRAGMA table_info(character_finances);
PRAGMA index_list(character_finances);
PRAGMA foreign_key_list(character_finances);
PRAGMA table_info(financial_transactions);
PRAGMA index_list(financial_transactions);
PRAGMA foreign_key_list(financial_transactions);
```

Do not infer undocumented columns or semantics from table names.

## 35. Current balance is not transaction history

Legacy `character_finances.ryo` is current balance-like evidence only.

Forbidden migration:

```text
ryo = 5000
=> invent arbitrary historical income/payment transactions totaling 5000
```

If no opening-balance contract is implemented, preserve the row as unresolved/legacy evidence and synthesize no canonical ledger history.

## 36. Opening-balance migration contract

If V13 chooses a deterministic opening-balance entry, PASS requires all of:

- stable target holder/account identity proven;
- stable currency identity proven;
- exact amount conversion;
- one deterministic opening transaction UID/idempotency key;
- explicit type equivalent to `MIGRATION_OPENING_BALANCE`;
- explicit migration provenance;
- deterministic migration boundary/order/time;
- repeated ensure does not create another opening entry;
- opening entry claims only the known balance at cutover, not historical causes;
- zero/negative legacy values follow an explicit policy rather than ad hoc coercion.

## 37. Existing `financial_transactions` legacy evidence

Do not assume the old table already satisfies the canonical contract.

Promotion is allowed only if each required identity/amount/reference/time/provenance field can be mapped deterministically and losslessly, or if the implementation stores an explicit migration evidence mapping that documents missing semantics.

Forbidden:

- infer payer/payee from reason text;
- infer account from active player merely because UI shows the row;
- infer currency from a display label when ambiguous;
- silently drop rows that cannot be mapped;
- rewrite legacy rows to force reconciliation.

Unmappable legacy rows must remain preserved evidence.

## 38. Legacy summary fields outside Phase 13

No automatic canonical transaction/asset/ownership synthesis from:

- `monthly_income`;
- `monthly_expenses`;
- `debt`;
- `property_value`;
- `investment_value`.

Recurring summaries do not prove historical scheduled transactions. Debt/property/investment summaries belong to later domains unless explicit historical finance evidence independently exists.

---

# SCALE / COMPLETENESS

## 39. >1000 FinancialTransactions

Persist at least 1001 canonical transactions in one campaign/account/currency history, with a mix of incoming/outgoing and historical times.

Validate exact counts through authoritative ledger readers before and after reopen.

No authoritative reader may depend on a bounded presentation method such as `LIMIT 1000`, `LIMIT 100`, context budget, or first-page-only retrieval.

## 40. Balance derivation at scale

Compute expected balance independently from the fixture and compare with the canonical derived balance after >1000 entries.

Required:

- no truncation;
- no floating-point drift;
- no integer overflow;
- no omission of old history because a recent-history reader is bounded;
- same result after reopen and restore.

## 41. Income/expense aggregation

If Phase 13 provides income/expense/category/period aggregates, test >1000 transactions spanning multiple categories/time windows.

Aggregation must operate from complete authoritative data or clearly documented time filters, not from bounded presentation history.

## 42. Historical ledger completeness

Test current state and historical reads separately.

A valid current-balance query is not evidence that the ledger history reader is complete. Closed/reversed/old entries must remain queryable according to contract.

## 43. Restore scale

Create backup with >1000 ledger entries, restore it, run latest schema ensure and validate:

- exact ledger count;
- exact transaction UIDs;
- exact balances/aggregates;
- exact migration/opening evidence;
- no duplicate replay.

---

# SQLITE INTEGRITY

## 44. `PRAGMA integrity_check`

Final validation executes after migration and after mutation/race/scale fixtures:

```sql
PRAGMA integrity_check;
```

Required result:

```text
ok
```

## 45. `PRAGMA foreign_key_check`

Final validation executes:

```sql
PRAGMA foreign_key_check;
```

Required: zero violations for the authoritative DB fixture.

For bundled historical DBs with known unrelated legacy FK debt, a Phase-13-specific scoped check may be useful diagnostically, but it cannot replace a clean full check on fresh canonical Phase-13 fixtures.

## 46. Generic resolver validation is separate

Even when `foreign_key_check` is clean, separately attack every generic reference registry/resolver:

- nonexistent UID;
- wrong campaign;
- unknown namespace;
- inactive target;
- cross-kind collision;
- lifecycle race.

A clean FK check cannot grant PASS to references that SQLite does not model as direct FKs.

---

# REGRESSION / PRESERVATION

## 47. Phase 3–12 preservation matrix

Before/after V13 migration semantic equality must be demonstrated for prior accepted domains, including:

- ActivePlayerRef / Player State classification;
- Stats / Resources;
- Modifier/DerivedValueResolver;
- Talent / Potential;
- Skills;
- Techniques;
- Innate/Racial/Bloodline/Evolution/Forms;
- Inventory definitions/instances/stacks/unique possession/legacy evidence;
- Equipment definitions/loadouts/slots/modifier activation;
- OwnershipRecord history/reference registries/shares/provenance.

V13 migration must not directly mutate those domains merely to make finance migration succeed.

## 48. Cross-domain hard boundary regression

Mandatory tests retain:

```text
Inventory possession
!= Equipment
!= OwnershipRecord
!= Financial Ledger
```

Examples:

- inventory transfer only -> no ledger transaction;
- equip/unequip only -> no ledger transaction;
- ownership transfer only -> no payment fabricated;
- payment only -> no ownership/possession/equipment mutation;
- theft possession change -> no sale/payment fabricated;
- gift ownership transfer -> payment not required;
- salary/reward payment -> no asset ownership side effect.

If Phase 13 exposes a higher-level purchase/sale operation that intentionally spans domains, all participating effects must be in one atomic SQLite transaction; failure of any side must rollback all sides. Phase 13 must not silently couple unrelated lower-level operations.

## 49. Ownership reference integrity preservation

Phase 13 must not weaken Phase-12 owner/asset registries or lifecycle triggers to make account/party integration easier.

Any reuse of Phase-12 generic party reference authority must respect:

- campaign scope;
- namespace identity;
- ACTIVE/RETIRED status;
- history-preserving lifecycle constraints.

A finance account holder may reference a party authority, but Finance must not reinterpret OwnershipRecord itself as account ownership or cash balance.

## 50. Generic StatePatch bypass

WORK-054 identified that legacy `financial_transactions` is currently generic-StatePatch-writable.

When canonical Phase-13 ledger becomes authoritative, final validation must search every write path and prove that generic AI/UI patching cannot bypass finance-domain conservation/reference/idempotency rules.

Acceptable outcomes include removing canonical ledger tables from generic StatePatch writability or making every such path pass through an equivalent authoritative finance validator/transaction boundary.

A typed FinanceStore plus an unchanged raw generic patch bypass is a release blocker.

---

# FINAL VALIDATION PROCEDURE AFTER CHAT-1 RESULT

## 51. Candidate identity gate

Do not validate “latest” by assumption.

Final WORK-058 validation will require:

- exact final CHAT-1 result commit SHA;
- fresh master state;
- diff from accepted V12 runtime;
- confirmation whether later master commits are report-only or runtime-changing;
- exact GitHub Actions run tied to the candidate SHA.

A green CI on another SHA is insufficient.

## 52. Required evidence collection

Inspect at minimum:

- Phase-13 model(s);
- migration file and migration marker;
- `CurrentSchema.ensure()` routing;
- Finance/Ledger store/write API;
- account/currency/party reference schema/resolvers;
- StatePatch/SourceOfTruth writable-table boundary;
- bootstrap/restore/campaign-switch production paths;
- authoritative ledger readers and balance aggregation;
- concurrency tests and DB guards;
- backup/restore tests;
- >1000 scale tests;
- Phase 3–12 regression suite;
- CI metadata for exact candidate.

## 53. Final PASS criteria

`PHASE 13 INTEGRITY VALIDATION: PASS` will require all of:

- additive V13 migration;
- correct latest CurrentSchema routing;
- clean bootstrap;
- V12 -> V13 upgrade;
- full earlier migration-chain compatibility;
- reopen/repeated ensure/restore/campaign switch;
- migration idempotency and forward-only behavior;
- conservative legacy treatment / no invented transaction history;
- stable immutable FinancialTransaction identity;
- exact monetary representation and checked arithmetic;
- authoritative party/account/currency reference integrity;
- atomic transfer/rollback;
- idempotency/double-commit protection;
- race-safe balance/conservation/lifecycle invariants at DB/write boundary;
- >1000 ledger/history/balance completeness;
- `PRAGMA integrity_check = ok`;
- clean required `PRAGMA foreign_key_check` fixture;
- direct generic-resolver attacks pass;
- Phase 3–12 preserved;
- no raw StatePatch bypass of canonical ledger authority;
- exact candidate CI SUCCESS.

Any reproducible violation of a load-bearing invariant produces FAIL even if CI is green.

## 54. Required FAIL report shape

If final validation fails, report:

- violated invariant;
- minimal reproducer;
- exact schema/migration/runtime path;
- expected result;
- actual result;
- minimal required correction scope.

CHAT-3 must not implement the correction.

---

# STATUS

No final Phase-13 PASS/FAIL is issued by this plan.

`MIGRATION / INTEGRITY PLAN READY`

Final verdict is deferred until CHAT-1 supplies the final Phase-13 result commit and exact CI for validation.