# WORK-20260810-063 — Phase 14 Migration / Integrity Plan

Status: READ-ONLY RUNTIME / VALIDATION PLAN

Work ID: `WORK-20260810-063`
Worker: `CHAT-3`
Role: `READ-ONLY PHASE 14 MIGRATION / INTEGRITY AUDITOR`
Repository: `piotreksmaga-art/rpg-os-android`
Accepted Phase-13 runtime baseline: `be10d7f1b6bf0f6a2cd0522b1dac577d0f398790`
Accepted Phase-13 state: SEMANTIC PASS / INTEGRITY PASS / ADVERSARIAL PASS
Fresh master observed immediately before report write: `2756809dfd23442b3644d4ced9f8ad3d4d27b83a`
Phase-14 implementation owner: `CHAT-1 / WORK-20260810-061`
Architecture input: `WORK-20260810-059 — Phase 14 Architecture Audit`
Allowed write scope: this report only.

This document defines independent release gates for Phase 14 — Assets / debts / obligations / net-worth model. It does not implement Phase 14, alter schema/runtime/tests, modify MASTER/Roadmap/coordination, or issue a final Phase-14 PASS/FAIL before CHAT-1 publishes a final WORK-061 runtime SHA.

---

# 1. Canonical boundary

The Phase-14 integrity boundary is:

```text
Asset identity
!= OwnershipRecord
!= Inventory possession
!= Equipment state
!= Financial Ledger transaction
!= current cash/account balance
!= Asset valuation
!= Liability / obligation contract
!= Net-worth projection
```

MASTER requires durable stable UID/provenance, immutable significant history, atomic commit/rollback and a single legal mutation path. It distinguishes cash, receivables, debts, property, land, business, laboratory, workshop, vehicle, shares, rare assets and liabilities; personal wealth remains separate from organization wealth; net worth is `assets - liabilities`.

Phase 14 must build on accepted Phase 12 Ownership and accepted Phase 13 Finance rather than creating parallel identity/value authorities.

Hard migration/integrity rules:

1. Asset identity is owner-independent and value-independent.
2. OwnershipRecord remains the title/right authority.
3. Asset valuation is temporal/history-preserving and separate from asset identity.
4. Liability/obligation identity is authoritative; outstanding amount may be derived from principal plus settlement history.
5. Net worth is DERIVED/rebuildable, never a freely mutable authoritative number.
6. Missing valuation means UNKNOWN, not zero.
7. Legacy aggregate `debt`, `property_value`, `investment_value` or labels are evidence only and cannot synthesize detailed canonical assets/liabilities without one-to-one evidence.
8. ItemInstance identity must not be cloned into a conflicting second canonical identity.

---

# 2. Accepted runtime foundations that Phase 14 must preserve

## 2.1 Phase 12 Ownership

Accepted Phase 12 provides:

- generic `ownership_owner_kinds`;
- generic `ownership_asset_kinds`;
- campaign-scoped `ownership_party_registry`;
- campaign-scoped `ownership_asset_registry`;
- temporal/history-preserving `ownership_records`;
- exact fixed-scale ownership shares;
- write-boundary reference/lifecycle/share/overlap guards;
- ItemInstance target validation;
- immutable legal ownership history.

Phase 14 should reuse these generic external identity boundaries where semantically compatible. A canonical Phase-14 asset intended to be ownable should resolve to the same stable `(campaign, assetKindUid, assetUid)` reference used by Ownership, not a display label or duplicate identity.

## 2.2 Phase 13 Finance

Accepted Phase 13 provides:

- exact `Long`/SQLite INTEGER minor-unit money;
- stable `currency_uid` and immutable precision semantics;
- generic campaign-scoped financial accounts with generic holder references;
- append-only `financial_ledger_transactions`;
- SOURCE/SINK/INTERNAL/REVERSAL semantics;
- campaign-scoped transaction/command idempotency;
- rebuildable balance projection;
- DB-authoritative insufficient-funds/overflow guards;
- conservative legacy finance migration;
- generic StatePatch finance blocking.

Any Phase-14 monetary valuation/principal/settlement must use the accepted Phase-13 exact currency/value contract. Phase 14 may not introduce `Float`, `Double`, SQLite `REAL`, hardcoded `ryo`, display-name matching or another independent currency authority.

---

# 3. Expected Phase-14 authoritative objects

Exact names may differ, but final runtime must expose semantic equivalents of these categories if implemented by WORK-061:

```text
AssetKindDefinition / AssetDefinition namespace
AssetRecord or equivalent canonical non-item asset identity
AssetValuation history
ObligationRecord / LiabilityRecord
ObligationSettlement history
optional AssetEncumbrance / collateral relation
NetWorth projection/read model
```

Each authoritative historical object requires stable campaign-scoped identity where appropriate:

```text
assetUid
valuationUid
obligationUid
settlementUid
encumbranceUid
```

Names/display labels are never identity.

---

# 4. Production migration chain gate

Final candidate must route actual production `CurrentSchema.ensure(saveDb, campaignId)` through V14 or equivalent and preserve the accepted forward-only chain:

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
-> Phase 13.0
-> Phase 13.1
-> Phase 13.2
-> Phase 14
```

Direct test invocation of an `ensureV14()` helper is insufficient.

PASS requires:

- V14 ensures the complete accepted V13 contract first;
- migration is additive/forward-only;
- marker/version is stable and idempotent;
- marker is not committed before required schema/guards are usable;
- no destructive reinterpretation/downgrade of V13;
- runtime bootstrap, restore and campaign selection all reach V14 through the same latest-schema route;
- failure inside V14 migration leaves no semantically half-installed contract.

---

# 5. Clean bootstrap gate

Create a fresh campaign through actual production bootstrap.

Required:

- CurrentSchema reaches V14;
- expected V14 tables/indices/triggers/definitions exist;
- required generic built-in definitions are seeded deterministically and idempotently;
- no fake player assets, liabilities, valuations, settlements or net worth are generated merely because bootstrap occurs;
- no ownership/payment/possession records are synthesized by asset bootstrap;
- repeated bootstrap/ensure does not duplicate definitions/reference targets;
- `PRAGMA integrity_check` returns `ok`;
- `PRAGMA foreign_key_check` returns zero rows.

Foreign-key cleanliness is necessary but not sufficient for generic reference validity.

---

# 6. V13 -> V14 upgrade gate

Construct a database at exactly accepted Phase-13 runtime semantics with representative Phase 3–13 data, then upgrade using only normal production latest-schema routing.

Preserve unchanged:

- campaign identity/player state;
- stats/resources/modifiers;
- Talent/Potential;
- Skills/Techniques/Innate;
- Inventory ItemInstance identities and possession;
- Equipment/loadout state;
- OwnershipRecord history and registries;
- Finance accounts, exact ledger history, currencies, balances/projections, legacy finance evidence;
- unrelated world/campaign state.

After migration:

- V14 marker appears exactly once;
- no Phase-13 financial transaction is inserted merely because Phase 14 exists;
- no ownership record is inserted merely because an AssetRecord exists;
- no legacy aggregate wealth summary is promoted without explicit evidence;
- no existing ItemInstance receives conflicting cloned identity;
- Phase-13 balance reconciliation remains exact.

---

# 7. Full migration-chain gate

Exercise at least one older supported campaign fixture through the real production chain to V14.

Required checks:

- complete expected migration markers;
- no skipped migration dependency;
- no name/type collision with legacy `assets`, finance summaries or world-pack tables if they exist;
- same conservative V14 legacy policy regardless of whether database enters through V13 or much older schema;
- no later migration rewrites immutable Ownership/Finance history;
- final integrity and generic-reference checks pass.

---

# 8. Repeated ensure / idempotency

Run:

```text
ensure -> ensure -> ensure -> close -> reopen -> ensure
```

Required:

- one V14 migration marker;
- no duplicated asset kinds/asset identities;
- no duplicated ownership registry mapping;
- no duplicated valuations;
- no duplicated obligations;
- no duplicated settlement entries;
- no duplicated encumbrances;
- no invented net-worth snapshot/history;
- stable UIDs/provenance/effective times remain unchanged;
- deterministic guard recreation is allowed only if it does not mutate committed history.

If migration promotes any explicit legacy evidence, promotion must carry a stable migration/evidence idempotency identity so rerun cannot double-create authority.

---

# 9. Reopen gate

For each important Phase-14 state:

```text
write -> close DB -> reopen -> CurrentSchema.ensure -> exact authoritative equality
```

Preserve at minimum:

- asset identity/kind/lifecycle/provenance;
- valuation UID, exact amount, currency, effective time, valuation type/provenance;
- obligation UID, obligor/beneficiary, principal/currency, due/effective times, lifecycle/provenance;
- settlement UID, obligation link, amount/type/financial-transaction reference/source/provenance;
- encumbrance/collateral links if implemented;
- exact OwnershipRecord references and shares;
- derived outstanding amount equality;
- net-worth rebuild equality under the same explicit valuation policy/as-of time.

Reopen must not regenerate authoritative UIDs or turn presentation/cache rows into authority.

---

# 10. Restore gate

Mandatory scenarios:

1. V13 backup -> restore under V14 app -> automatic V14 migration.
2. V14 backup with assets + valuation history -> restore exact equality.
3. V14 backup with active/settled obligations and settlement history -> exact equality.
4. V14 backup with ownership + asset registry integration -> no duplicate registry targets.
5. V14 backup with explicit legacy evidence mappings -> restore without replay.
6. Restore while another campaign is active -> no cross-campaign state leakage.

After each restore:

- run latest CurrentSchema;
- run `PRAGMA integrity_check`;
- run `PRAGMA foreign_key_check`;
- run generic owner/asset/party/currency/financial reference audits;
- rebuild derived outstanding balances/net worth and compare to expected authoritative history.

---

# 11. Campaign switch A -> B -> A

Create campaigns A and B with deliberately colliding strings:

- same `assetUid` in same or different asset kinds;
- same `valuationUid` if identity is campaign-scoped;
- same `obligationUid`;
- same `settlementUid`;
- same party UID under different campaigns/namespaces;
- same currency UID where global currency definitions are intended;
- distinct OwnershipRecord shares, valuations, liabilities and ledger balances.

Switch:

```text
A -> B -> A
```

Required:

- all reads/writes are campaign-scoped;
- B never sees or mutates A authority;
- return to A preserves exact histories;
- no store/cache remains bound to previous campaign;
- net worth for A never sums B values or balances;
- same display labels/UID strings do not create cross-campaign matching.

---

# 12. Legacy preservation / zero unsafe synthesis

Phase 14 migration must preserve raw legacy evidence and avoid guessing.

Explicitly test legacy rows/fields equivalent to:

```text
character_finances.debt
character_finances.property_value
character_finances.investment_value
character_finances.ryo
monthly_income
monthly_expenses
item_name / labels
Inventory possession
Equipment state
Ownership display labels
opaque finance transaction reason text
```

Forbidden synthesis without explicit one-to-one evidence:

```text
property_value -> canonical Property asset
investment_value -> canonical Business/Share asset
debt -> creditor/debtor Obligation
ryo -> non-cash asset
inventory possession -> legal ownership / asset value
equipment -> asset ownership / valuation
same label -> same asset
finance payment -> asset identity/title
```

If a migration evidence/mapping table exists:

- raw evidence must remain preserved;
- mapping must carry stable evidence UID, mapping version and provenance;
- ambiguous evidence remains unresolved rather than guessed;
- rerun is idempotent;
- migration provenance explicitly says migration/bootstrap and does not fabricate gameplay event IDs.

---

# 13. Stable identity integrity

For each V14 authoritative identity test:

- blank UID -> reject;
- duplicate UID with conflicting immutable payload -> reject;
- exact idempotent replay -> deterministic no-op only when explicit idempotency contract exists;
- same display label with different UIDs -> both legal and independent;
- lifecycle transition does not regenerate UID;
- same UID string in different campaigns -> no leakage;
- same UID string in different namespaces/kinds -> no collision when composite namespace is intended.

Asset identity may not depend on owner, valuation or location.

---

# 14. Generic party reference integrity

Obligor, beneficiary and any party-bearing Phase-14 record must resolve through an authoritative generic party mechanism, preferably the accepted Phase-12 ownership party registry or an equivalent shared resolver.

Required failures:

- nonexistent party;
- wrong-campaign party;
- unknown/unregistered party kind;
- retired/inactive party when new acquisition/obligation is prohibited;
- same party UID string in two namespaces -> no collision;
- same party UID string in two campaigns -> no leakage.

Core must remain generic: character/player, NPC, organization, state, business/company and future legal entities must remain possible without world-specific hardcode.

A Kotlin `require(uid.isNotBlank())` or pre-INSERT SELECT alone is not sufficient where lifecycle can race.

---

# 15. Generic asset reference integrity

For Phase-14 canonical assets intended to integrate with Ownership:

- registered asset kind required;
- canonical target must exist in same campaign;
- ownership asset registry mapping must resolve to the actual canonical target;
- unknown asset kind -> reject;
- known kind + nonexistent target -> reject;
- wrong-campaign target -> reject;
- retired/destroyed target behavior explicit;
- same asset UID in two kinds -> no collision;
- same asset UID in two campaigns -> no leakage;
- ItemInstance remains validated against actual Phase-10 ItemInstance authority, not cloned/free-text bypass.

Do not accept `PRAGMA foreign_key_check=clean` as proof if generic reference validity depends on registries/triggers/resolvers.

---

# 16. Cross-domain lifecycle integrity

Historical references must remain resolvable.

Release gates must test:

- asset cannot be physically deleted if Ownership history, valuation history, encumbrance or obligation history references it;
- retiring/destroying/liquidating an asset cannot leave active Ownership/encumbrance semantics incoherent;
- obligation cannot be physically deleted after settlement/history exists;
- party cannot be retired while active obligations requiring that party remain unresolved, unless explicit contract defines historical-only retirement semantics;
- currency cannot be retired/mutated so historical valuations/principal become uninterpretable;
- financial transaction referenced by settlement remains immutable and resolvable;
- ownership registry target cannot be retired before dependent active Ownership/Phase-14 relations are coherently closed.

Historical identity preservation is preferred over ON DELETE CASCADE for authoritative history.

---

# 17. Asset lifecycle/history integrity

If AssetRecord has temporal lifecycle:

- valid/open interval semantics must be deterministic;
- close/retire/destroy/liquidate is a legal state transition, not arbitrary UPDATE;
- immutable identity/kind/creation provenance cannot be rewritten;
- illegal reopen or double-close rejected unless canonical contract explicitly supports a new successor record;
- retirement timestamp/order cannot precede creation;
- asset history survives ownership changes;
- destroying an asset does not silently erase ownership or valuation history.

Any mutable current-state convenience row must be distinguishable from immutable history.

---

# 18. Valuation integrity

Valuation is history, not asset identity.

Required gates:

- stable `valuationUid`;
- exact Phase-13-compatible amount representation;
- explicit stable `currencyUid`;
- explicit valuation type/policy;
- deterministic effective order/time;
- source/provenance;
- optional valid-until semantics are `[validFrom, validUntil)` if temporal intervals are used;
- invalid interval/inversion rejected;
- negative valuation behavior explicit by type; ordinary asset market/book value should not use accidental negative values;
- zero legal only if explicitly meaningful; missing valuation must remain UNKNOWN;
- Float/Double/REAL never authoritative;
- arithmetic overflow rejected;
- currency precision cannot be silently reinterpreted;
- new valuation appends history or legally closes predecessor; old valuation is not overwritten/deleted;
- same-time competing valuations follow explicit policy (multiple valuation types may coexist; same exact policy/asset/time collision must be deterministic).

Current-value projection must identify valuation policy and as-of time; it cannot silently pick a random/latest display row across incompatible policies.

---

# 19. Valuation/ownership interaction

Net-worth valuation must apply exact OwnershipRecord share at the requested as-of time.

Required examples:

```text
asset X value = 1000
A owns 25%
B owns 75%
=> A gross asset contribution = 250
=> B gross asset contribution = 750
```

Use deterministic exact arithmetic compatible with ownership share scale and currency precision. If division produces non-representable minor units, rounding policy must be explicit, deterministic and part of valuation/net-worth projection semantics; silent floating-point rounding is forbidden.

Ownership changes must not mutate historical valuation rows. Valuation changes must not mutate OwnershipRecord.

---

# 20. Liability / obligation integrity

A monetary obligation must explain who owes whom, what amount/currency, why, and lifecycle/time semantics.

Required:

- stable `obligationUid`;
- campaign-scoped obligor and beneficiary generic references;
- obligor != beneficiary unless a specific legal type explicitly permits it;
- stable obligation type;
- exact principal amount using Phase-13-compatible representation;
- positive principal for ordinary debt; direction represented by parties/type, not negative sign trick;
- valid currency identity for monetary obligations;
- creation/due/effective ordering validated;
- provenance/source preserved;
- immutable contract identity/initial principal unless changes are modeled through append-only amendments/settlements;
- lifecycle statuses legal and deterministic;
- settled/defaulted/cancelled/expired transitions cannot arbitrarily erase history.

Receivable should be the beneficiary-side view of the same underlying obligation, not a second independent debt that can diverge.

---

# 21. Settlement history integrity

If outstanding amount is derived from principal minus settlements:

```text
outstanding = principal - SUM(valid quantitative settlements)
```

Required:

- stable `settlementUid`;
- settlement references existing same-campaign obligation;
- exact nonnegative/positive amount rules explicit;
- aggregate monetary settlement cannot exceed outstanding amount unless a typed overpayment/refund contract explicitly supports it;
- settlement timestamp/order cannot illegally precede obligation;
- settlement type stable;
- provenance required;
- financialTransactionUid, when present, must resolve to exact same-campaign immutable Phase-13 transaction and semantically compatible currency/amount/direction according to the chosen contract;
- one FinancialTransaction must not accidentally settle multiple incompatible obligations unless explicit allocation model exists;
- settlement history append-only;
- correction requires reversal/correction semantics, not UPDATE/DELETE.

Outstanding cache, if persisted, must be rebuildable from authoritative obligation + settlement history.

---

# 22. Encumbrance / collateral integrity

If Phase 14 implements encumbrance:

- stable `encumbranceUid`;
- same-campaign valid asset target;
- same-campaign valid active obligation;
- explicit encumbrance type/priority;
- temporal interval valid;
- no deletion of historical encumbrance;
- conflicting priority/exclusivity rules enforced at authoritative boundary;
- settling/cancelling obligation must close/release encumbrance coherently;
- encumbrance alone does not transfer OwnershipRecord;
- ownership transfer does not silently delete encumbrance unless explicit legal operation coordinates both domains.

---

# 23. Net-worth projection correctness

Net worth is DERIVED and must be rebuildable from authoritative inputs at an explicit party/as-of/valuation-policy/currency basis.

At minimum:

```text
NetWorth(P,T,Policy,C)
= value of P-owned asset shares at T
+ valid receivables included by policy
+ cash/account balances included by policy
- outstanding liabilities included by policy
```

Critical release gates:

- personal party != organization party;
- cash counted exactly once;
- bank/financial balance not also duplicated as AssetRecord unless projection normalizes one representation out;
- business-owned assets are not directly counted as shareholder personal assets in addition to share/business-interest valuation;
- property/investment legacy summaries not double-counted with canonical assets;
- ownership fraction applied exactly;
- missing valuation is UNKNOWN/partial, not zero unless policy explicitly says so;
- liabilities not counted twice as both debt and negative asset;
- receivables not duplicated if represented as obligation view and asset view;
- same asset reachable through multiple relations counted once under stable identity;
- as-of historical query uses ownership/valuation/liability state valid at T;
- currency conversion is not implicit; multi-currency net worth requires explicit conversion/rate policy or separate per-currency result;
- arithmetic overflow detected, no wraparound;
- cached snapshot deletion/rebuild yields same result.

A persisted `net_worth` number that can be edited independently of sources fails this gate.

---

# 24. StatePatch blocking

All new Phase-14 canonical authority must be typed-only or equivalently guarded.

Generic AI StatePatch must not directly write:

- asset identity authority;
- asset-kind definitions;
- valuation history;
- obligation/liability authority;
- settlement history;
- encumbrance history;
- net-worth cache if that would create a false authority;
- generic reference/evidence mapping tables.

Final candidate must update `SourceOfTruthRegistry` or equivalent mutation boundary so Phase-14 tables cannot bypass invariants.

Direct SQL by trusted migration/runtime code still requires SQLite constraints/triggers for race-sensitive invariants.

---

# 25. Scale / no authoritative truncation

Create >1000 records for each implemented authoritative collection where feasible:

- assets;
- valuations;
- obligations;
- settlements;
- encumbrances;
- ownership-linked assets.

Required:

- authoritative counts/history complete;
- current/as-of resolution complete;
- outstanding calculations use all settlements;
- net-worth calculation uses all applicable owned assets/liabilities;
- no `LIMIT 1000` or bounded presentation reader silently defines truth;
- pagination may be bounded only when caller can retrieve complete authoritative history across pages;
- reopen/restore preserve >1000 records exactly.

A bounded UI/Context reader is acceptable only if explicitly presentation/cache and not used as net-worth/history authority.

---

# 26. SQLite integrity gates

For clean bootstrap, migrated fixtures, heavy history, concurrency fixtures, reopen and restore run:

```sql
PRAGMA integrity_check;
```

Required: `ok`.

Run:

```sql
PRAGMA foreign_key_check;
```

Required: zero rows.

Additionally run semantic generic-reference queries because FK cleanliness alone cannot prove:

- party registry target activity;
- generic asset namespace target resolution;
- ownership registry synchronization;
- lifecycle validity;
- valuation policy/type validity;
- currency/type lifecycle validity;
- referenced financial transaction semantic compatibility.

---

# 27. Phase 3–13 no-regression gate

Final candidate must rerun accepted test suites/invariants for Phase 3–13. In particular verify Phase 14 does not mutate or weaken:

- Player State identity/classification;
- Stat/Resource authority;
- DerivedValueResolver/modifiers;
- Talent/Potential;
- Skills/Techniques/Innate;
- Inventory stable ItemInstance/possession;
- Equipment/loadout constraints;
- Ownership exact shares/history/reference guards;
- Financial ledger immutability, exact amounts, balance reconciliation, idempotency, concurrency and StatePatch blocking.

Any new trigger on shared Phase-12 registries or Phase-13 currency/party tables must be tested against accepted Ownership and Finance lifecycle operations.

---

# 28. SQLite write-boundary invariants

The following invariants are race-sensitive and require SQLite-authoritative protection (trigger/FK/constraint/CAS/transactional serialization or equivalent). Kotlin/application prechecks alone are insufficient.

## WB-01 — Asset target registration integrity

Atomic/coherent creation of canonical asset and its ownership-reference registry target. No committed registry target may point to nonexistent wrong-campaign canonical asset.

## WB-02 — Asset lifecycle vs new OwnershipRecord

Concurrent asset retirement/destruction and ownership acquisition must serialize. A stale ACTIVE precheck cannot commit a new OwnershipRecord after target invalidation.

## WB-03 — Asset lifecycle vs valuation

Concurrent retirement/destruction and new valuation must obey explicit policy; no valuation may be committed against an invalid target due to stale precheck.

## WB-04 — Party lifecycle vs obligation creation

Concurrent retire/invalidate party and create obligation must serialize. Both obligor and beneficiary must remain legal references at commit boundary.

## WB-05 — Asset lifecycle vs encumbrance creation

Concurrent asset retirement and encumbrance insertion must not leave active encumbrance against illegal target.

## WB-06 — Obligation lifecycle vs settlement

Concurrent close/settle/cancel/default transition and new settlement must produce one legal serialized outcome. No settlement may attach after final closure unless contract explicitly permits it.

## WB-07 — Aggregate settlement <= outstanding

Two concurrent settlements individually valid against stale outstanding amount must not jointly over-settle obligation.

## WB-08 — Duplicate settlement/idempotency

Concurrent retry with same operation/settlement identity creates at most one economic/legal effect.

## WB-09 — FinancialTransaction allocation/link integrity

Concurrent reuse of one payment transaction for incompatible settlement allocations must obey explicit allocation total/uniqueness policy at DB boundary.

## WB-10 — Valuation uniqueness/temporal overlap

If same asset + valuation policy/type permits only one active/as-of record for a period, concurrent writes must not create illegal overlaps/ambiguous current valuation.

## WB-11 — Encumbrance priority/exclusivity

Concurrent liens/pledges must not violate explicit exclusive collateral or priority constraints.

## WB-12 — Ownership registry lifecycle coupling

Retirement/deletion of generic ownership asset registry target must be blocked while canonical asset/history/dependencies require it, and canonical asset retirement must not bypass active Ownership records.

## WB-13 — Currency lifecycle vs valuation/obligation write

A currency may not retire/change precision between application validation and commit of valuation/principal/settlement.

## WB-14 — Definition lifecycle vs record creation

Asset kind, obligation type, valuation type/policy, settlement type and encumbrance type must be ACTIVE/registered at authoritative commit boundary.

## WB-15 — Exact arithmetic overflow

Principal, settlement aggregates, owned-share valuation contributions and any persisted derived caches must reject overflow rather than wrap.

## WB-16 — Historical immutability

Direct UPDATE/DELETE of committed valuation/settlement/obligation-history rows must be blocked by authoritative DB boundary, not only service API convention.

## WB-17 — Cross-campaign reference isolation

Composite campaign-scoped references or equivalent triggers must prevent attaching A's asset/party/obligation/settlement to B records.

---

# 29. Concurrency / TOCTOU release gates

Use separate SQLite connections/transactions where architecture permits, synchronization barriers proving competing execution, and inspect final committed state.

## A14-RACE-01 — Asset create/register collision

Concurrent attempts to create same `(campaign, assetKind, assetUid)` with conflicting payloads.

Required: at most one canonical identity; no split-brain between AssetRecord and ownership registry.

## A14-RACE-02 — Asset retire vs Ownership acquisition

T1 validates asset active; T2 retires/destroys asset; T1 attempts new OwnershipRecord.

Required: one coherent serialized outcome; no newly committed legal ownership against target invalid in transaction order.

## A14-RACE-03 — Asset retire vs valuation append

T1 prepares valuation; T2 retires/destroys asset; T1 commits.

Required: outcome follows explicit lifecycle policy at authoritative boundary; stale precheck cannot bypass it.

## A14-RACE-04 — Party retire vs obligation creation

T1 validates obligor/beneficiary active; T2 retires one party; T1 inserts obligation.

Required: one coherent outcome; no invalid party reference committed.

## A14-RACE-05 — Double settlement / over-settlement

Initial outstanding = 100.

Concurrent:

```text
T1 settle 70
T2 settle 70
```

Required: aggregate committed settlement never exceeds allowed outstanding (unless explicit overpayment contract). At most one succeeds under ordinary debt semantics.

## A14-RACE-06 — Stale outstanding settlement

T1 reads outstanding 100; T2 settles 80; T1 tries settle 50 based on stale read.

Required: T1 revalidated/rejected at authoritative boundary.

## A14-RACE-07 — Duplicate settlement/idempotency race

Two callers use same settlement/command UID concurrently.

Required: one committed effect; exact retry resolves idempotently or one deterministic conflict, never double settlement.

## A14-RACE-08 — Obligation final-close vs settlement

T1 finalizes/cancels/settles obligation; T2 concurrently adds settlement.

Required: one legal serialized result consistent with lifecycle ordering.

## A14-RACE-09 — Payment allocation race

One immutable Phase-13 FinancialTransaction of amount X is concurrently linked/allocated to multiple obligations beyond permitted total.

Required: allocation invariant cannot be exceeded.

## A14-RACE-10 — Competing current valuation

Two callers publish valuations for same asset/policy/time interval where contract allows one current value.

Required: deterministic legal history; no ambiguous overlapping authority.

## A14-RACE-11 — Encumbrance exclusivity/priority race

Two obligations concurrently pledge an asset under an exclusive collateral policy.

Required: illegal combined result cannot commit.

## A14-RACE-12 — Currency retirement vs valuation/principal

T1 validates currency ACTIVE; T2 retires currency; T1 writes new valuation or obligation principal.

Required: serialized legal outcome; no stale-currency commit.

## A14-RACE-13 — Asset registry retire vs Phase-14 operation

T1 validates generic asset target; T2 retires ownership asset registry entry; T1 tries valuation/encumbrance/ownership-dependent operation.

Required: no stale target bypass.

## A14-RACE-14 — Cross-domain purchase outer transaction rollback

Start one higher-level transaction that coordinates finance payment + ownership transfer + possession + asset/liability effect; force failure after an intermediate domain write.

Required: all coordinated authoritative effects rollback. No payment-only, ownership-only, possession-only or asset-only half-state.

The Phase-14 store itself must not silently invent this higher-level coordinator if PlayerDomain orchestration remains a later phase; the gate applies if WORK-061 introduces any coordinated operation.

---

# 30. Temporal/history release gates

For any Phase-14 temporal relations, validate exact boundaries with queries immediately before, at and after transitions.

If interval representation is used, expected convention should remain consistent with accepted project semantics:

```text
[validFrom, validUntil)
```

Test:

- asset lifecycle boundary;
- valuation effective/expiry boundary;
- obligation active/due/settled boundary;
- encumbrance interval;
- historical net-worth queries using as-of Ownership + valuation + obligation state.

At the exact end boundary an expired/closed record must not still be treated as active.

No historical query may substitute current ownership/value/liability merely because current rows are easier to read.

---

# 31. Net-worth reconstruction test matrix

At minimum test:

### NW-01 — simple owned asset
A owns 100% X; X value=1000; no liabilities => net worth contribution 1000.

### NW-02 — partial ownership
A owns 25% X value=1000 => 250.

### NW-03 — co-ownership
A 25%, B 75% => aggregate projected contributions = 1000 without double count.

### NW-04 — cash + asset
Cash 100 + asset 500 => 600 if policy includes both once.

### NW-05 — liability
Assets/cash 1000; outstanding liability 300 => 700.

### NW-06 — receivable
Receivable included according to policy exactly once; do not also duplicate same obligation as another asset.

### NW-07 — organization isolation
Player owns shares/business interest but organization owns property/cash. Player net worth includes only player's legally owned interest valuation, not organization's underlying assets again.

### NW-08 — missing valuation
Owned unvalued asset => result reports unknown/partial valuation condition rather than silently adding zero as if known.

### NW-09 — historical as-of
Ownership/value/liability changes at T. Queries at T-1 and T yield historically correct different net worth.

### NW-10 — cache destruction/rebuild
Delete/corrupt only derived net-worth cache and rebuild from authority => exact same result.

### NW-11 — >1000 inputs
>1000 owned assets/liabilities/history rows remain fully included without presentation truncation.

### NW-12 — overflow
Values near Long bounds must reject/flag overflow; never wrap into negative/incorrect net worth.

---

# 32. Minimal integrity evidence required for final revalidation

When WORK-061 final runtime SHA is published, final CHAT-3 revalidation must independently inspect and/or execute evidence for:

1. exact candidate SHA and exact CI;
2. diff from accepted Phase-13 runtime;
3. production CurrentSchema route;
4. migration marker and complete chain;
5. Phase-14 schema/indices/triggers;
6. reference registries/resolvers;
7. typed store/repository API;
8. SourceOfTruthRegistry blocking;
9. clean bootstrap;
10. V13->V14 migration;
11. repeated ensure;
12. reopen;
13. restore;
14. A->B->A campaign switching;
15. legacy zero-synthesis fixtures;
16. stable identity conflicts;
17. generic party/asset/currency reference failures;
18. lifecycle failures;
19. temporal/history queries;
20. valuation exactness/history;
21. liability/outstanding settlement arithmetic;
22. net-worth reconstruction/anti-double-count;
23. >1000 records;
24. mandatory concurrency/TOCTOU cases;
25. `PRAGMA integrity_check`;
26. `PRAGMA foreign_key_check`;
27. Phase 3–13 regression suite.

Green CI alone is never sufficient evidence.

---

# 33. FAIL reporting contract for final revalidation

If any release blocker is reproduced against final WORK-061 runtime, report:

- violated invariant;
- minimal reproducer;
- exact runtime/schema/domain path;
- expected result;
- actual result;
- whether violation is migration, reference, temporal, valuation, liability, projection, atomicity, concurrency or regression;
- minimal correction scope.

CHAT-3 must not implement the correction.

---

# 34. Final-status rule

This document issues no Phase-14 PASS/FAIL.

Current status:

```text
PHASE 14 MIGRATION / INTEGRITY PLAN READY
FINAL INTEGRITY REVALIDATION PENDING FINAL WORK-061 RUNTIME SHA
```

Phase 15 is out of scope and must not be started by this work item.
