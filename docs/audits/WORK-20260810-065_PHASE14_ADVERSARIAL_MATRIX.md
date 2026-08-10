# WORK-20260810-065 — Phase 14 Adversarial Matrix

Status: READ-ONLY ADVERSARIAL PLAN / FINAL RUNTIME VALIDATION PENDING

Work ID: `WORK-20260810-065`
Worker: `CHAT-5`
Role: `READ-ONLY PHASE 14 ADVERSARIAL AUDITOR`
Repository: `piotreksmaga-art/rpg-os-android`
Accepted Phase-13 runtime baseline: `be10d7f1b6bf0f6a2cd0522b1dac577d0f398790`
Fresh master observed before report write: `2756809dfd23442b3644d4ced9f8ad3d4d27b83a`
Phase-14 implementation owner: `CHAT-1 / WORK-20260810-061`
Allowed write scope: this report only.

This document defines the adversarial acceptance matrix for Phase 14 — Assets / debts / obligations / net-worth. It does **not** implement runtime/schema/tests, does **not** issue a Phase-14 PASS/FAIL, and does **not** start Phase 15. Final validation is deferred until CHAT-1 supplies the exact final WORK-061 runtime SHA.

At report creation no WORK-20260810-061 runtime commit was present on master. The latest observed master commit was report-only Phase-13 integrity revalidation. Therefore this document is a pre-runtime oracle/matrix only.

---

# 1. Canonical source hierarchy and phase boundary

Validation must use, in order of authority:

1. exact final WORK-061 runtime/schema at the candidate SHA;
2. current repository state and production routing;
3. `docs/RPG_OS_MASTER_ARCHITECTURE.md`;
4. `docs/RPG_OS_IMPLEMENTATION_ROADMAP.md`;
5. `docs/PARALLEL_WORK_COORDINATION.md`;
6. `WORK-20260810-059_PHASE14_ASSETS_DEBTS_OBLIGATIONS_NET_WORTH_ARCHITECTURE.md`;
7. accepted Phase-12 Ownership audits/runtime;
8. accepted Phase-13 Financial Ledger adversarial/integrity evidence.

MASTER requires stable UID/provenance, one legal mutation path, atomic transaction semantics, idempotency protection, immutable significant history, typed authority and the separation of persistent/derived/cache/presentation state. It explicitly defines net worth as derived from assets minus liabilities and distinguishes money, ownership, possession and assets.

Roadmap identifies Phase 14 as `Assets / debts / obligations / net-worth model` and Phase 15 as `DevelopmentProject model`. Phase 15 is out of scope.

---

# 2. Hard domain separation

The final candidate must preserve:

```text
Asset / Liability domain
!= OwnershipRecord
!= Inventory possession
!= Equipment state
!= Financial Ledger
```

And also:

```text
Asset identity != current owner
Asset identity != possession/location
Asset identity != valuation
Asset valuation != purchase price
Liability/obligation != payment transaction
Outstanding obligation != account balance
Net worth != authoritative mutable row
```

Required cross-domain adversarial scenarios:

- asset creation must not automatically create OwnershipRecord unless an explicit coordinated operation requires it;
- OwnershipRecord creation must not fabricate Asset identity if the target is unresolved;
- possession in Inventory must not create an Asset, ownership, valuation or net worth entry;
- Equipment state must not create ownership, asset or financial effects;
- payment must not create an Asset or transfer title;
- asset transfer/title change must not fabricate payment;
- theft/possession change must not become a legitimate sale;
- monetary loan disbursement must not by itself become a complete liability model;
- collateral/encumbrance must not itself transfer ownership;
- asset destruction/liquidation that requires cross-domain effects must commit atomically or roll back all participating domains.

Any explicit cross-domain operation must preserve each domain's native semantics and use one SQLite transaction boundary until the global TurnTransaction exists.

---

# 3. Accepted lessons inherited from Phase 12 and Phase 13

Phase 12 established:

```text
nonblank UID string != valid reference
```

Final Phase-14 validation must therefore prove target existence and lifecycle validity for every party, asset, registry, ownership, currency, financial transaction, obligation and encumbrance reference. A registry/resolver/trigger boundary is required where direct FKs are not possible.

Phase 12 also established that race-sensitive lifecycle/reference invariants require authoritative SQLite guards, not only Kotlin prechecks.

Phase 13 established that stale balance/amount validation is unsafe unless the authoritative ledger INSERT boundary revalidates state atomically. Phase 14 inherits the same requirement for outstanding obligations, valuation intervals/current records, asset lifecycle and idempotency.

Green CI alone is not sufficient evidence for race safety. Final validation must inspect the actual SQLite write boundary.

---

# 4. Stable identity attacks

Attack all authoritative identities independently:

```text
assetUid
valuationUid
obligationUid
settlementUid
encumbranceUid
operationUid / commandUid / idempotencyUid
```

Required cases:

- blank UID;
- duplicate UID in same authoritative namespace;
- same UID with semantically different payload;
- same display name with different UIDs;
- same UID string in different campaigns;
- same UID string in different namespaces/kinds;
- exact retry;
- conflicting retry;
- concurrent exact retry;
- concurrent conflicting retry.

Expected result: one canonical identity/effect or deterministic exact replay; conflicting semantic reuse must fail loudly.

---

# 5. Generic reference spoofing

For every generic reference verify that the exact target exists in the correct campaign, namespace/kind and lifecycle state.

Attack:

- known party kind + nonexistent party UID;
- known asset kind + nonexistent asset UID;
- unknown party kind;
- unknown asset kind;
- retired/inactive party;
- retired/destroyed/liquidated asset;
- nonexistent obligation;
- nonexistent FinancialTransaction link;
- financial transaction from another campaign;
- ownership operation from another campaign;
- same UID string registered in another namespace;
- same UID string registered only in another campaign;
- spoofed registry row without corresponding authoritative target where the contract requires target resolution.

String presence alone is never acceptance evidence.

---

# 6. Asset lifecycle attacks

Validate legal lifecycle transitions and append-preserved history.

Attack:

- duplicate asset creation;
- ACTIVE -> ACTIVE fake transition;
- terminal -> ACTIVE resurrection without explicit correction semantics;
- RETIRED/DESTROYED/LIQUIDATED -> new valuation when policy forbids it;
- terminal asset -> new encumbrance;
- terminal asset -> new OwnershipRecord;
- destructive DELETE of asset with valuation/ownership/encumbrance/history references;
- asset kind/identity mutation;
- campaign mutation;
- source/provenance rewrite;
- retirement timestamp/order before creation;
- overlapping contradictory lifecycle intervals.

Historical identity must remain queryable after terminal lifecycle.

---

# 7. Valuation attacks

Valuation is historical evidence, not asset identity and not automatically objective truth.

Attack:

- valuation for nonexistent asset;
- wrong campaign asset;
- unknown/inactive valuation type/policy;
- unknown/inactive currency;
- zero/negative valuation where contract forbids it;
- malformed value;
- unsupported precision;
- `Float`/`Double`/SQLite `REAL` authority for conserved/aggregated monetary value;
- overflow at maximum exact representation;
- underflow during aggregation/subtraction;
- stale valuation read used as authoritative current value after a newer valuation commits;
- duplicate current valuation where policy allows only one;
- temporal overlap for exclusive valuation intervals;
- backdated valuation that illegally rewrites a historical current interval;
- direct UPDATE/DELETE of historical valuation;
- mutation of valuation currency/type/effective order/provenance;
- purchase price silently promoted to permanent valuation;
- estimate silently promoted to objective market fact.

Current valuation must be deterministically resolved from append-preserved valuation history under an explicit policy/as-of order.

---

# 8. Liability / obligation attacks

Attack obligation construction and lifecycle:

- blank/duplicate obligation UID;
- same obligor and beneficiary accidentally collapsed;
- nonexistent obligor/beneficiary;
- wrong campaign party;
- unknown/inactive party kind;
- zero/negative principal when illegal;
- malformed amount;
- unsupported precision;
- principal overflow;
- due order before creation where illegal;
- terminal state without causal settlement/cancellation/default evidence;
- arbitrary status toggling;
- mutation of principal/currency/parties after commitment;
- destructive DELETE;
- duplicate liability imported from the same source;
- one economic obligation represented twice and both counted in net worth;
- receivable and liability mirrors double-counted as two independent wealth effects for the same party.

Outstanding amount must be derived from principal/authorized changes minus valid settlements, never trusted from a stale presentation field.

---

# 9. Settlement attacks

Mandatory cases:

- settlement for nonexistent obligation;
- wrong campaign obligation;
- settlement after terminal lifecycle when not explicitly legal;
- settlement amount <= 0 when illegal;
- settlement amount > outstanding;
- exact full settlement;
- multiple legal partial settlements;
- aggregate partial settlements > principal;
- duplicate settlement UID;
- duplicate financial transaction linked to multiple settlements when contract forbids reuse;
- settlement link to nonexistent FinancialTransaction;
- settlement link to finance transaction in another campaign;
- settlement amount inconsistent with linked financial movement when the operation contract requires exact matching;
- double settlement after already SETTLED;
- settlement UPDATE/DELETE;
- forgiveness/write-off masquerading as payment;
- payment masquerading as ownership transfer;
- settlement accepted from stale outstanding read.

Partial settlement must never leave a state where the settlement history committed but the required outstanding/lifecycle projection did not, or vice versa.

---

# 10. Encumbrance / collateral attacks

If WORK-061 implements encumbrances/collateral, test:

- unresolved asset;
- unresolved obligation;
- wrong campaign on either side;
- terminal asset;
- terminal obligation;
- duplicate encumbrance UID;
- illegal overlapping exclusive collateral claim;
- priority collision where priority must be unique;
- encumbrance validUntil <= validFrom;
- direct history UPDATE/DELETE;
- release without matching active encumbrance;
- collateral release racing with default/enforcement;
- asset retirement while active encumbrance exists;
- obligation settlement while stale encumbrance state remains contradictory.

Encumbrance must not transfer OwnershipRecord title.

---

# 11. Net-worth adversarial oracle

Net worth must be DERIVED and rebuildable.

Required formula family:

```text
NetWorth(P, asOf, valuationPolicy, currency)
= owned attributable asset value
+ valid receivables
+ accepted cash/account contribution if included by contract
- outstanding liabilities
```

Attack double counting:

- count Phase-13 cash balance and duplicate cash AssetRecord;
- count whole business assets personally plus shareholder business-interest value;
- count 100% asset value despite fractional Phase-12 ownership share;
- count property_value/investment_value legacy summaries together with canonical assets;
- count settled liability as still outstanding;
- count receivable both as AssetRecord and Obligation receivable if both represent same canonical claim;
- mix organization assets into personal net worth;
- mix campaign B assets into campaign A;
- count terminal/destroyed asset after as-of terminal order;
- use future valuation for historical as-of query;
- use stale materialized snapshot after dependency change.

A persisted net-worth snapshot/cache must be deletable and rebuildable without loss of authoritative economic history.

---

# 12. Direct SQL bypass matrix

Attempt raw SQL against every Phase-14 authoritative table.

Required attacks:

- INSERT unresolved party/asset/obligation refs;
- INSERT wrong campaign refs;
- INSERT duplicate identities;
- INSERT invalid amount/precision;
- INSERT over-settlement;
- INSERT overlapping exclusive valuation/current interval;
- INSERT terminal-asset reference;
- UPDATE committed identity;
- UPDATE amount/principal/valuation;
- UPDATE campaign;
- UPDATE effective time/order;
- UPDATE provenance where immutable;
- DELETE asset history;
- DELETE valuation history;
- DELETE obligation history;
- DELETE settlement history;
- DELETE encumbrance history;
- retire/delete registry target while active references exist.

If an invariant can be violated by two direct or independent writers, Kotlin validation does not satisfy the contract. SQLite FK/UNIQUE/CHECK/trigger/CAS/serialized write-transaction protection is required.

---

# 13. Generic StatePatch bypass

Final candidate must add all canonical Phase-14 authority tables to typed-only protection or equivalent central writer guard.

Generic AI StatePatch must not directly mutate:

- assets;
- asset valuations;
- obligations/liabilities;
- obligation settlements;
- encumbrances;
- canonical Phase-14 registry/evidence mappings;
- net-worth materialization if it could be mistaken for authority.

A presentation/context read model must never become a write-back source.

---

# 14. Legacy fabrication attacks

Legacy fields such as:

```text
property_value
investment_value
debt
```

are aggregate evidence, not canonical object history.

Required migration attacks:

- `property_value > 0` with no exact asset source -> canonical assets created must be zero;
- `investment_value > 0` with no instrument identity -> no fabricated share/business asset;
- `debt > 0` with no creditor/contract identity -> no fabricated liability;
- narration/reason text containing a creditor name -> no automatic creditor inference;
- same label across multiple legacy rows -> no UID merge by display name;
- repeated migration/ensure -> no duplicate evidence/assets/obligations/valuations;
- legacy aggregate remains preserved losslessly;
- unresolved evidence remains explicitly non-authoritative if an evidence table exists.

Conservative `0 promoted canonical rows + preserved evidence` is a valid safe result.

---

# 15. 1000-record truncation / bounded-reader misuse

Create fixtures above UI/context limits.

Minimum stress fixtures:

- >1000 assets for one campaign/party across kinds;
- >1000 valuation rows for one asset or portfolio where technically practical;
- >1000 obligations;
- >1000 settlements on an obligation/portfolio where contract permits;
- >1000 ownership-linked assets.

Validate:

- authoritative totals/counts/rebuilds read all rows;
- net worth uses complete authoritative input, not `LIMIT 1000`/bounded context readers;
- bounded `recent/current/context` APIs may intentionally truncate but are never authority;
- pagination/order remains deterministic;
- reopen/restore preserves complete authoritative counts and totals.

---

# 16. Reopen / restore / campaign switch attacks

Required scenarios:

1. V13 -> V14 migration -> close -> reopen -> exact authoritative equality.
2. V14 backup -> restore -> exact assets/valuations/obligations/settlements/encumbrances.
3. Restore older V13 backup under V14 -> automatic forward migration without fabricated Phase-14 rows.
4. Repeated ensure -> no duplicate identities/evidence/history.
5. Campaign A and B intentionally reuse the same UID strings -> strict isolation.
6. Switch A -> B -> A -> no stale repository/store cache leakage.
7. Restore while another campaign is active -> no cross-campaign contamination.
8. Derived net-worth cache deleted after reopen/restore -> rebuild yields same exact result.

After each critical fixture run integrity/reference checks.

---

# 17. Mandatory Phase-14 concurrency gates

Every race-sensitive test below must use **real separate SQLite connections/callers**, independent store/repository instances where applicable, separate threads/executors and deterministic synchronization such as `CountDownLatch`/barriers. One shared `SQLiteDatabase` object, sequential calls, mocked concurrency or Kotlin-only precheck tests do not satisfy a RACE gate.

## ASSET-RACE-01 — concurrent duplicate asset identity

Two independent callers create the same `(campaign, asset kind, assetUid)` concurrently.

Required result: exactly one canonical asset identity/effect; the loser is exact replay only if payload/idempotency is identical, otherwise deterministic failure.

Protection expected: UNIQUE/PK + transactional/idempotency semantics at SQLite boundary.

## ASSET-RACE-02 — retire asset vs new ownership/reference

Caller A retires/destroys asset X while caller B concurrently creates a new OwnershipRecord or other active Phase-14 reference to X.

Required result: one legal serialization only. No committed terminal asset may end with a newly-created active reference that the lifecycle contract forbids.

Protection expected at SQLite authority boundary; Kotlin `isActive()` precheck is insufficient.

## ASSET-RACE-03 — retire asset vs valuation/encumbrance

A terminal lifecycle transition races with addValuation/addEncumbrance.

Required result: deterministic policy. If terminal assets reject new records, the losing operation must fail atomically. No post-terminal hidden write from stale ACTIVE read.

## VAL-RACE-01 — competing current valuation

Two independent callers add valuations that would both become the unique/current value for the same `(campaign, asset, valuation policy/type, effective boundary)` where exclusivity applies.

Required result: one legal current state or explicitly allowed ordered history; never two contradictory active/current rows.

Protection expected: unique/partial index, interval-overlap trigger or equivalent SQLite guard.

## VAL-RACE-02 — stale valuation / backdating race

Caller A reads current valuation V1. Caller B commits later V2. Caller A then attempts an operation based on stale V1 or inserts a backdated row that would invalidate deterministic historical selection.

Required result: stale authority cannot overwrite/redefine committed history unless explicit append-preserving correction semantics permit it.

## OBL-RACE-01 — double full settlement

Outstanding = 100. Two separate callers concurrently settle 100.

Required result: exactly one full economic settlement may win; aggregate applied settlement <= authorized outstanding; obligation reaches one coherent terminal state.

This is a mandatory SQLite write-boundary test.

## OBL-RACE-02 — competing partial settlements

Outstanding = 100. Two callers concurrently settle 60 and 60.

Required result: at most legal aggregate <=100. If both cannot coexist, one must fail. No 120 total settlement.

## OBL-RACE-03 — legal concurrent partial settlements

Outstanding = 100. Two callers concurrently settle 40 and 50.

Required result: both may commit if policy permits, final outstanding exactly 10, history contains both, no lost update.

This detects over-conservative locking and stale projection overwrite as well as over-settlement.

## OBL-RACE-04 — settlement vs cancellation/default

Payment/settlement races with CANCEL/DEFAULT/EXPIRE or equivalent terminal transition.

Required result: deterministic legal ordering. No contradictory terminal semantics such as fully settled and defaulted from incompatible stale states unless the contract explicitly records both in an ordered, causally valid way.

## OBL-RACE-05 — duplicate settlement/idempotency race

Two callers submit the same settlement UID and same operation/idempotency UID concurrently.

Required result: exactly one economic effect/history append; exact retry becomes deterministic replay. Conflicting payload under same identity fails.

## OBL-RACE-06 — obligation lifecycle vs new settlement

One caller marks obligation terminal while another attempts a new settlement from stale ACTIVE state.

Required result: one valid serialization; no unauthorized post-terminal settlement.

## REF-RACE-01 — retire party/reference vs create obligation

Party/reference retirement races with creation of an obligation naming that party.

Required result: no obligation committed against a reference that is inactive in the winning serialization order.

## ENC-RACE-01 — collateral release vs default/enforcement

If encumbrances exist, release races with default/enforcement/terminal asset handling.

Required result: no dangling, double-active or contradictory collateral state.

## CROSS-RACE-01 — settlement + Financial Ledger atomicity

Where a settlement requires a Phase-13 FinancialTransaction, inject failure/race between financial movement and Phase-14 settlement append.

Required result: either both domain effects commit under the explicit operation or neither commits. No paid-but-outstanding and no settled-without-required-payment state.

## CROSS-RACE-02 — asset transfer/destruction + Ownership atomicity

Where an asset lifecycle/title operation explicitly spans Phase 14 and Phase 12, inject failure after the first domain write.

Required result: complete rollback. No terminal asset with stale active title, no transferred title with failed required asset lifecycle event.

## CACHE-RACE-01 — stale net-worth materialization

If a persisted net-worth/current-portfolio cache exists, race cache generation/swap against asset valuation or liability mutation.

Required result: stale cache cannot become authority. Version/fingerprint/CAS or invalidation must ensure readers can detect/rebuild it. Deleting cache must remain safe.

## CAMP-RACE-01 — same UID strings in two campaigns concurrently

Concurrent writes in campaigns A and B use intentionally identical asset/obligation/settlement UIDs.

Required result: complete campaign isolation according to the chosen identity contract; no cross-campaign idempotency collision or totals leakage.

---

# 18. Required race harness evidence

For every RACE gate final validation must record:

- exact test method/path;
- number of independent SQLite connections;
- independent caller/store instances;
- synchronization mechanism proving both operations reached the contested point;
- initial authoritative state;
- both attempted operations;
- success/failure counts;
- final authoritative rows;
- exact derived outstanding/net-worth/balance where relevant;
- `PRAGMA integrity_check`;
- `PRAGMA foreign_key_check`;
- domain-specific registry/reference validation where generic references are not direct FKs.

A test that only asserts Kotlin exceptions without demonstrating independent SQLite writers is not a concurrency PASS.

---

# 19. Temporal/history mutation attacks

Attempt to rewrite past truth:

- UPDATE asset created/retired order;
- UPDATE valuation amount/type/currency/effective order;
- DELETE old valuation;
- UPDATE obligation principal/parties/currency;
- DELETE obligation;
- UPDATE settlement amount/kind/reference/time;
- DELETE settlement;
- UPDATE encumbrance interval/priority after commitment;
- DELETE encumbrance history;
- backdate a record behind later dependent history;
- create overlapping intervals that make `asOf` ambiguous.

Expected: significant committed history is append-preserved; corrections use new records/operations rather than mutation.

---

# 20. Precision and overflow oracle

Authoritative value arithmetic must use the exact Phase-13 monetary representation or an equivalently exact contract.

Test:

- min legal unit;
- unsupported sub-minor precision;
- maximum representable valuation/principal;
- valuation sum overflow across many assets;
- liability sum overflow;
- exact ownership-share multiplication without floating point drift;
- `assets - liabilities` overflow/underflow;
- currency mismatch;
- no silent rounding;
- no NaN/Infinity path;
- no SQLite REAL authority for canonical monetary totals.

Derived net worth must fail loudly or use checked/bounded exact arithmetic; integer wraparound is a release blocker.

---

# 21. Production routing / migration gates

Final WORK-061 validation must inspect the normal `CurrentSchema.ensure` production route, not only direct `ensureV14()` calls.

Required:

- V14 ensures accepted V13 chain;
- additive migration;
- exactly one stable migration marker per contract;
- failure does not leave half-created authority;
- bootstrap, campaign switch and restore route through V14;
- repeated ensure is idempotent;
- no destructive reinterpretation of Phase 12/13 tables;
- conservative legacy evidence preservation;
- triggers/indexes/reference guards present after restore/reopen;
- no automatic downgrade.

---

# 22. Phase 3–13 regression matrix

Final candidate must preserve previously accepted domains and their authoritative guards.

At minimum run/inspect regressions for:

- Player State;
- Stats/Resources;
- Derived modifiers;
- Talent/Potential;
- Skills;
- Techniques;
- Innate/Racial/Evolution;
- Inventory;
- Equipment;
- OwnershipRecord including Phase-12 concurrency/reference tests;
- Financial Ledger including Phase-13 FIN-RACE gates, immutable history, StatePatch guard, >1000 completeness and restore/reopen.

Phase 14 must not weaken Phase-12 registry guards or Phase-13 ledger/balance triggers to simplify integration.

---

# 23. Required integrity checks

For fresh, migrated, restored, reopened and race fixtures execute:

```sql
PRAGMA integrity_check;
PRAGMA foreign_key_check;
```

Expected:

```text
integrity_check = ok
foreign_key_check = zero violations
```

Where generic references are enforced through registries/triggers rather than direct FKs, additionally query/assert zero unresolved active references. Clean FK output alone is not enough.

---

# 24. Final validation disposition contract

When CHAT-1 supplies a final WORK-061 runtime SHA, CHAT-5 must:

1. re-check fresh master;
2. determine whether later commits are report-only or a newer WORK-061 runtime candidate;
3. pin validation to the exact requested final runtime SHA;
4. inspect exact diff/runtime/schema/write boundaries;
5. execute/verify all matrix groups above;
6. execute all RACE gates with independent SQLite connections/callers;
7. run integrity/reference checks;
8. verify Phase 3–13 regression;
9. issue exactly one final Phase-14 adversarial verdict for that SHA;
10. commit only the final validation report.

No final Phase-14 PASS/FAIL is issued by this matrix document.

---

# 25. Release-blocker criteria

Any one of the following is sufficient for final FAIL:

- duplicate canonical identity under concurrent writers;
- unresolved/wrong-campaign generic reference accepted;
- lifecycle/reference TOCTOU protected only by Kotlin precheck;
- aggregate settlement exceeding authorized outstanding;
- partial settlement/financial cross-domain half-commit;
- stale valuation/current row becoming authority;
- mutable/deletable committed obligation/valuation/settlement history;
- direct SQL bypass of authoritative invariant;
- generic StatePatch writing canonical Phase-14 authority;
- fabricated canonical assets/liabilities from ambiguous legacy summaries;
- net-worth double counting;
- authoritative >1000 truncation;
- cross-campaign leakage;
- restore/reopen loss or duplication;
- arithmetic overflow/wraparound or silent precision loss;
- regression weakening accepted Ownership/Finance invariants;
- non-clean SQLite/domain reference integrity after a required fixture.

---

# MATRIX STATUS

`WORK-20260810-065`: **ADVERSARIAL MATRIX READY — FINAL WORK-061 RUNTIME VALIDATION PENDING**

No runtime implementation was performed. No Phase-14 PASS/FAIL was issued. Phase 15 was not started.
