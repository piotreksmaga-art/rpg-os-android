# WORK-20260810-062 — Phase 14 Assets / Liabilities Semantic Oracle

Status: SEMANTIC ORACLE READY / FINAL WORK-061 RUNTIME REVALIDATION PENDING

Work ID: `WORK-20260810-062`
Worker: `CHAT-2`
Role: `READ-ONLY PHASE 14 SEMANTIC ORACLE`
Repository: `piotreksmaga-art/rpg-os-android`
Accepted Phase-13 runtime: `be10d7f1b6bf0f6a2cd0522b1dac577d0f398790`
Accepted Phase-12 runtime: `d5f1fd6e7a660e3e398f155784f8602c486b9906`
Fresh master observed immediately before report write: `2756809dfd23442b3644d4ced9f8ad3d4d27b83a`
Phase-14 implementation owner: `CHAT-1 / WORK-20260810-061`
Allowed write scope: this report only.

This artifact defines the independent semantic oracle for Phase 14 before a final WORK-061 runtime SHA is available. It does **not** inspect or accept an in-progress Phase-14 implementation, does not modify runtime/schema/tests, does not issue a final Phase-14 PASS/FAIL and does not begin Phase 15.

Final semantic revalidation must later be pinned to one exact WORK-061 runtime SHA and exact CI run. Until then this report is the semantic specification against which the candidate will be judged.

---

# 1. Canonical source hierarchy and accepted prerequisites

The oracle is grounded in the current repository truth:

- `docs/RPG_OS_MASTER_ARCHITECTURE.md`;
- `docs/RPG_OS_IMPLEMENTATION_ROADMAP.md`;
- `docs/PARALLEL_WORK_COORDINATION.md`;
- `docs/audits/WORK-20260810-059_PHASE14_ASSETS_DEBTS_OBLIGATIONS_NET_WORTH_ARCHITECTURE.md`;
- final accepted Phase-12 semantic/integrity/adversarial reports;
- final accepted Phase-13 semantic/integrity/adversarial reports;
- exact accepted Phase-12 and Phase-13 runtime/schema.

MASTER establishes:

```text
stable UID != display name
immutable significant history + mutable working state
one legal authoritative mutation path
AI/UI/presentation != authoritative state
Inventory != Equipment != Ownership
money uses accounting ledger
Balance may be cache; ledger explains history
Personal wealth != organization wealth
Net worth = assets - liabilities
```

Roadmap orders:

```text
12 OwnershipRecord
13 Financial Ledger / Economy
14 Assets / debts / obligations / net-worth
15 DevelopmentProject
```

Therefore Phase 14 is strictly the economic-object / obligation / valuation / wealth-projection layer. It must consume accepted Phase 12 and Phase 13 authority rather than redefining either domain.

---

# 2. Accepted runtime facts that Phase 14 MUST preserve

## 2.1 Phase 12 authority

Accepted Phase 12 already provides generic, campaign-scoped identity infrastructure:

```text
ownership_owner_kinds
ownership_asset_kinds
ownership_party_registry
ownership_asset_registry
ownership_records
ownership_operations
```

Important accepted semantics:

- owner identity = `(campaignId, ownerKindUid, ownerUid)`;
- generic asset reference identity = `(campaignId, assetKindUid, assetUid)`;
- unknown/nonexistent/inactive party or generic asset references fail at SQLite authority boundaries;
- `ITEM_INSTANCE` resolves directly to Phase-10 ItemInstance identity rather than through duplicate generic asset rows;
- OwnershipRecord is temporal and append-preserved;
- ownership share is exact fixed-scale integer semantics;
- aggregate concurrent ownership cannot exceed 100%;
- retirement/deletion guards preserve historical resolvability;
- same display label does not define identity;
- possession/equipment are separate domains.

Phase 14 must reuse or deliberately extend this identity system. A new arbitrary `assetUid` namespace disconnected from `ownership_asset_registry` would recreate a defect already solved in Phase 12.

## 2.2 Phase 13 authority

Accepted Phase 13 provides:

```text
currency_definitions
financial_account_type_definitions
financial_accounts
financial_ledger_transactions
financial_account_balances (derived/rebuildable projection)
legacy_financial_evidence
```

Important accepted semantics:

- canonical conserved money uses exact Kotlin `Long` / SQLite INTEGER minor units;
- `currencyUid` is stable identity; display string is not identity;
- currency precision meaning is immutable;
- financial account identity is campaign-scoped and holder-generic;
- account holders reuse Phase-12 party identity;
- FinancialTransaction history is append-only and immutable;
- transaction UID and optional command UID are idempotency identities;
- SOURCE/SINK/INTERNAL/REVERSAL are explicit typed semantics;
- balance is projection, ledger is explanatory authority;
- transactions cannot be backdated behind later account history;
- funds/overflow/reference/lifecycle race checks occur at SQLite ledger INSERT boundary;
- generic StatePatch cannot write canonical finance authority;
- legacy `character_finances` / old `financial_transactions` are not auto-expanded into synthetic canonical history.

Phase 14 must use these exact identities and exact arithmetic. It may not independently mutate account balances or invent a second monetary representation.

---

# 3. Phase-14 hard semantic boundary

The primary oracle invariant is:

```text
ASSET / LIABILITY DOMAIN
!= OWNERSHIP RECORD
!= INVENTORY POSSESSION
!= EQUIPMENT STATE
!= FINANCIAL LEDGER
```

The following are also distinct concepts:

```text
Asset identity
!= current owner
!= possession/location
!= valuation
!= purchase price
!= cash balance
!= net worth

Liability / obligation identity
!= cash debit
!= negative account balance
!= payment transaction
!= current outstanding projection

Net worth
!= bank balance
!= gross property value
!= stored mutable summary
```

A correct Phase-14 implementation must preserve all of those separations mechanically, not merely document them.

---

# 4. Explicit anti-equivalence rules

The candidate MUST NOT infer any of the following automatically:

```text
possession = ownership
possession = asset identity
ownership = valuation
payment = asset
payment = ownership transfer
ownership transfer = payment
cash balance = net worth
negative cash = liability contract
legacy debt = canonical detailed obligation history
legacy property_value = canonical Property asset
legacy investment_value = canonical Security/BusinessShare history
current purchase price = permanent market value
organization-owned assets = player's personal assets
```

Every cross-domain effect requires an explicit domain operation or one higher-level atomic operation that intentionally coordinates the relevant domains.

---

# 5. Stable asset identity oracle

Phase-14-owned assets require stable identity independent of owner, value and location.

Semantic minimum equivalent to:

```text
AssetRecord
- campaignId
- assetUid
- assetKindUid
- lifecycleStatus
- created/effectiveOrder
- retired/destroyed/liquidatedOrder?
- sourceEventUid?
- version
- provenance
```

Required properties:

1. `assetUid` is nonblank stable identity.
2. Asset identity remains unchanged across ownership transfer.
3. Asset identity remains unchanged across price/value changes.
4. Asset identity remains unchanged across possession/location changes.
5. Same display name may identify multiple different assets.
6. Same UID string may exist in different campaigns without leakage.
7. Duplicate UID with different immutable semantics must fail loudly.
8. Exact idempotent retry may return the existing result only under an explicit operation/idempotency contract.
9. Terminal lifecycle does not delete historical identity.
10. Phase-14-owned generic assets must resolve through Phase-12 asset reference authority.

For `ITEM_INSTANCE`, Phase 14 must not clone ItemInstance identity into a competing canonical asset identity unless a documented adapter row merely references the same stable ItemInstance rather than replacing it.

---

# 6. Generic asset kinds

Core must not hardcode setting-specific wealth objects.

Required generic extensibility must support semantic families equivalent to:

```text
PROPERTY
LAND
BUSINESS
VEHICLE
SECURITY / SHARE
RECEIVABLE
RARE_ASSET
INFRASTRUCTURE
OTHER
```

World Packs may register further asset kinds. Naruto-specific villages/clans/ryo concepts and Bleach-specific institutions must not become universal Core kinds.

A valid asset kind requires stable UID/namespace, lifecycle/status and provenance. An arbitrary string that merely looks like an asset kind is not a valid reference.

Where possible, the Phase-14 asset-kind namespace should be the same stable namespace exposed to `ownership_asset_kinds` so OwnershipRecord can target the economic object without translation by display name.

---

# 7. Asset lifecycle and history

Asset lifecycle changes are historical facts, not destructive rewrites.

A minimum lifecycle may be equivalent to:

```text
ACTIVE -> RETIRED
ACTIVE -> DESTROYED
ACTIVE -> LIQUIDATED
```

The exact names are implementation details, but required semantics are:

- terminal state has explicit effective order and provenance;
- no arbitrary reactivation without a distinct documented correction/reinstatement semantic;
- history survives terminal transition;
- ownership/encumbrance references remain historically resolvable;
- current ownership/encumbrance/valuation rules must agree with lifecycle state;
- an asset cannot be physically deleted merely because it is no longer active.

If the implementation uses mutable current-state rows, immutable identity fields and legal lifecycle transitions must be guarded at the authoritative write boundary.

---

# 8. Asset valuation oracle

Asset identity and asset value are separate.

A valuation record must be equivalent to:

```text
AssetValuation
- campaignId
- valuationUid
- assetRef
- currencyUid
- exactAmountMinor
- valuationTypeUid / valuationPolicyUid
- effectiveOrder
- validUntilOrder? or supersession semantics
- sourceEventUid?
- confidence/estimate classification when applicable
- provenance
- version
```

Required semantics:

1. Valuation UID is stable historical-record identity.
2. Monetary value uses accepted Phase-13 exact representation.
3. Currency resolves to accepted Phase-13 currency identity.
4. Unknown currency is rejected.
5. Historical valuation is append/supersession-preserved, not silently overwritten.
6. Purchase price is evidence of a transaction, not automatically permanent market/book/appraisal value.
7. Appraisal/estimate/market observation/book value/face value remain distinguishable by type/provenance.
8. Missing valuation = `UNKNOWN / INCOMPLETE`, never implicit zero.
9. A terminal asset cannot receive ordinary post-terminal valuations unless the contract explicitly allows a historical/final valuation semantic.
10. If one-current-valuation-per-policy is required, exclusivity must be protected against concurrent writers at the SQLite authority boundary.

The oracle rejects any model in which `currentValue` on the asset identity row is the only historical authority.

---

# 9. Liability / obligation identity oracle

A liability cannot be merely a negative number attached to the player.

A canonical monetary or nonmonetary obligation requires stable contract identity equivalent to:

```text
ObligationRecord
- campaignId
- obligationUid
- obligationTypeUid
- obligorPartyRef
- beneficiaryPartyRef
- currencyUid?              // monetary obligations
- principalAmountMinor?     // exact Phase-13 representation
- assetRef?                 // collateral/delivery subject if relevant
- created/effectiveOrder
- dueOrder?
- lifecycleStatus
- sourceEventUid?
- sourceContractUid?
- version
- provenance
```

Required semantics:

- obligor and beneficiary are stable generic party references, not names;
- wrong-campaign party references fail;
- inactive/unresolved party behavior is explicit and historical references remain resolvable;
- `principal > 0` for a normal positive monetary debt unless a distinct nonmonetary/zero-value contract exists;
- currency is required for monetary principal;
- debt and receivable are views/roles over a shared obligation fact, not two unconstrained mutable duplicate records;
- a negative account balance cannot silently create a creditor/contract/due date;
- a payment transaction does not by itself prove the legal creation or satisfaction terms of an obligation.

---

# 10. Obligation history / settlement oracle

Settlement/correction/default/cancellation must be history-preserving.

Semantic minimum equivalent to:

```text
ObligationSettlement / ObligationOperation
- campaignId
- operationUid / settlementUid
- obligationUid
- operationKind
- exactAmountMinor?           // where quantitative
- financialTransactionUid?    // when money moved
- ownershipOperationUid?      // when collateral/title moved
- effectiveOrder
- sourceEventUid?
- provenance
```

Current outstanding is derived:

```text
principal
- valid quantitative settlements
+ explicit principal adjustments/interest if modeled
= outstanding
```

Required rules:

1. Settlement history is append-preserved.
2. A settlement cannot exceed authoritative outstanding amount.
3. Two concurrent settlements cannot jointly over-settle.
4. Full settlement and cancellation/default races must serialize into one coherent legal history.
5. Payment linkage to Phase-13 transaction must reference an existing correct-campaign immutable transaction.
6. A transaction UID cannot be silently reused as settlement evidence for conflicting obligations if the contract forbids that relation.
7. Settled obligation cannot receive ordinary additional settlement unless explicit correction/reopen semantics exist.
8. Correction must append corrective history; it must not rewrite prior settled amounts.
9. Outstanding projection/cache must be rebuildable from authoritative obligation + operation history.

---

# 11. Temporal semantics

Phase 14 must answer historical questions correctly even before the later general Temporal Engine is implemented.

Use deterministic campaign order consistent with accepted Phase 12/13 semantics.

Required equivalent queries:

```text
assetState(asset, asOfOrder)
valuation(asset, policy, asOfOrder)
ownershipShare(party, asset, asOfOrder)
obligationState(obligation, asOfOrder)
outstanding(obligation, asOfOrder)
portfolio(party, asOfOrder)
netWorth(party, asOfOrder, policy, currency)
```

Preferred interval contract:

```text
[validFrom, validUntil)
```

Mandatory behavior:

- current ownership transfer does not rewrite prior ownership;
- current valuation does not replace prior valuation for historical queries;
- settled debt remains historically outstanding before settlement time;
- destroyed/liquidated asset remains queryable before terminal time;
- backdating behind later committed dependent history must be rejected or resolved by a documented correction mechanism;
- timeline ordering must be deterministic after reopen/restore.

---

# 12. Provenance oracle

Every authoritative Phase-14 fact needs enough causal evidence to explain why it exists.

At minimum, implementation-equivalent semantics must preserve:

```text
provenance
stable object/operation identity
campaign identity
effective order
source event / source contract / source operation where applicable
migration evidence identity where applicable
```

Valuation provenance must preserve whether the value came from:

```text
market observation
appraisal
purchase cost
book value
face value
estimate
custom world rule
```

An estimate cannot silently become an objective FACT merely because it appears in a wealth panel.

Migration provenance must use explicit migration/legacy-evidence semantics and must not invent creditors, contracts, appraisers, purchases, events or historical valuations.

---

# 13. Ownership interaction oracle

Hard split:

```text
AssetRecord = what economic object exists
OwnershipRecord = who holds what legal/right share over it and when
```

Required consequences:

- asset may exist with zero current owner records;
- ownership acquisition does not create a valuation;
- asset creation does not automatically create ownership unless the explicit same high-level operation intentionally includes both effects;
- owner transfer does not regenerate `assetUid`;
- valuation history is not rewritten when owner changes;
- Phase-12 share is the canonical share source;
- same asset cannot have a second hidden Phase-14 ownership percentage competing with OwnershipRecord.

For co-ownership:

```text
party attributable asset value
= asset valuation x exact ownership share
```

The multiplication must avoid floating-point authority. Exact rational/fixed-scale arithmetic or checked integer arithmetic is required, with an explicit rounding policy if conversion to minor units cannot divide evenly.

Missing ownership does not imply owner=player. Missing ownership should produce no attributable ownership unless another explicit rights model says otherwise.

---

# 14. Co-ownership semantic gates

Final WORK-061 candidate must demonstrate at least:

- 50/50 ownership attributes 50% value to each party, not 100%;
- 60/40 ownership preserves exact total attribution;
- ownership share changes at T affect net-worth as-of T without rewriting T-1;
- same asset valuation is not duplicated per owner as independent full-value assets;
- organization-owned asset is not attributed personally merely because the player controls/works for the organization;
- business shareholder value is based on the shareholder's owned interest/valuation policy, not automatic recursive ownership of every underlying business asset;
- co-ownership and valuation calculations remain deterministic after reopen/restore.

---

# 15. Finance interaction oracle

Phase 14 consumes Phase 13; it does not replace it.

Required rules:

```text
FinancialTransaction != Asset
FinancialTransaction != OwnershipRecord
FinancialTransaction != Obligation contract
FinancialAccount balance != Net worth
```

Examples:

### Purchase

A purchase may eventually require one higher-level atomic operation containing:

```text
financial payment
+ ownership transfer
+ possession change where applicable
+ optional initial valuation evidence
+ event/provenance
```

But no individual effect implies the others automatically.

### Sale

A sale payment does not by itself prove title transfer. Title transfer does not by itself prove money moved.

### Debt disbursement

Cash disbursement may be linked to an obligation, but the ledger row alone does not encode creditor, maturity, collateral or legal outstanding balance.

### Debt settlement

A payment can satisfy an obligation only through explicit settlement semantics linking the immutable Phase-13 transaction to the Phase-14 obligation operation.

### Cash in net worth

Account balances may contribute to wealth exactly once. If cash/bank accounts are represented as wealth components, the implementation must normalize them so the same funds are not counted both as Phase-13 balance and a duplicate AssetRecord value.

Phase 14 must never `UPDATE financial_account_balances` directly.

---

# 16. Net-worth derivation oracle

Net worth is DERIVED, never a directly writable economic fact.

Minimum semantic equation:

```text
NetWorth(P, T, valuationPolicy, reportCurrency)
=
SUM(attributable value of assets owned by P at T)
+ valid receivables attributable to P at T
+ included cash/account balances at T
- outstanding liabilities of P at T
```

The exact inclusion taxonomy may vary, but it must be documented and deterministic.

Mandatory anti-double-counting rules:

1. cash/account balance counted once;
2. same AssetRecord counted once per valuation policy;
3. co-owned asset scaled by exact ownership share;
4. personal wealth and organization wealth remain separate;
5. business share value cannot be combined with full underlying business assets in personal wealth unless policy explicitly eliminates duplication;
6. legacy `property_value` / `investment_value` cannot be added on top of canonical assets;
7. receivable and underlying mirrored debt cannot both inflate the same party's position incorrectly;
8. collateral asset remains an asset; encumbrance/liability is modeled separately rather than subtracting value twice unless policy explicitly requires it.

Missing valuation semantics:

```text
UNKNOWN valuation != zero valuation
```

A net-worth projection with missing required values must expose `INCOMPLETE`, `PARTIAL`, unknown components or equivalent. It must not fabricate a confident exact zero.

A persisted `NetWorthSnapshot` is allowed only as DERIVED/CACHE/PRESENTATION with enough dependency/order metadata to detect staleness and rebuild it from authority.

---

# 17. Net-worth arithmetic requirements

Because Phase 13 uses `Long` minor units, Phase-14 aggregation must be checked for overflow.

Required gates:

- checked add/subtract for asset/liability aggregation;
- checked share scaling / multiplication;
- explicit conversion policy for different currencies;
- no implicit FX conversion by display label;
- if no conversion/rate authority exists, multi-currency net worth must remain separated by currency or reject a single-currency exact total;
- no Float/Double/SQLite REAL as conserved or exact wealth authority;
- near-Long.MAX aggregate overflow must fail/mark unrepresentable rather than wrap.

A valuation policy may use an estimate internally, but the canonical exact stored monetary observation and final accounting total cannot silently drift through floating-point accumulation.

---

# 18. Asset encumbrance / collateral oracle

If WORK-061 introduces collateral/encumbrance semantics, they are relations, not ownership transfers.

Equivalent identity:

```text
AssetEncumbrance
- campaignId
- encumbranceUid
- assetRef
- obligationUid
- encumbranceTypeUid
- priority/rank if applicable
- validFrom
- validUntil?
- sourceEventUid?
- provenance
```

Required rules:

- encumbrance does not itself change owner;
- retired/destroyed asset cannot accept ordinary new encumbrance;
- obligation/asset references must exist in the same campaign;
- release/default/foreclosure transitions preserve history;
- collateral release vs default enforcement races serialize coherently;
- no dangling encumbrance after destructive deletion because authoritative asset/obligation identity must not be physically erased.

If WORK-061 does not include encumbrances, final validation should mark these gates `NOT IN IMPLEMENTED SCOPE` rather than inventing a requirement beyond the actual Phase-14 candidate, while still ensuring its model leaves room for obligations referencing assets.

---

# 19. Legacy migration oracle

Known legacy evidence includes aggregate `character_finances` values equivalent to:

```text
debt
property_value
investment_value
```

These are summary evidence, not detailed canonical object history.

Automatic migration MUST NOT perform:

```text
property_value = 500000
=> invent one Property asset worth 500000

investment_value = 100000
=> invent BusinessShare/Security object

debt = 25000
=> invent unknown creditor + obligation contract
```

Required migration policy:

1. preserve legacy fields losslessly;
2. synthesize no detailed Asset/Obligation/Valuation history from aggregate numbers alone;
3. exact source assets may be promoted only when an unambiguous one-to-one evidence mapping exists;
4. exact obligations may be promoted only when debtor, beneficiary, amount/currency, identity and relevant terms are sufficiently evidenced;
5. ambiguous evidence remains unresolved legacy evidence;
6. migration evidence gets stable UID/mapping version/provenance if canonical reconciliation tables are introduced;
7. rerun/reopen/restore cannot duplicate promoted records;
8. migration cannot infer ownership from possession/equipment;
9. migration cannot infer valuation from OwnershipRecord alone;
10. migration cannot infer payment/settlement history from current debt summary;
11. migration cannot fabricate historical value series from one current aggregate;
12. legacy summary fields cannot remain a competing authoritative source once canonical Phase-14 authority exists.

Safe result for ambiguous legacy data:

```text
canonical Phase-14 objects created = 0
legacy evidence preserved = yes
```

This is a PASS outcome, not a migration failure.

---

# 20. StatePatch isolation oracle

Canonical Phase-14 tables must be typed-only authority.

Generic AI `StatePatch` must not directly insert/update/delete:

- asset identities;
- asset valuations;
- obligation contracts;
- settlement/operation history;
- encumbrances;
- canonical derived wealth materializations when they have protected rebuild semantics;
- legacy-to-canonical mapping/evidence authority.

The final candidate should extend the accepted `SourceOfTruthRegistry` typed-only denylist or equivalent authoritative mutation policy.

A presentation table being writable does not authorize writing the canonical Phase-14 source rows behind it.

---

# 21. Scale / completeness oracle

Phase 14 must support very long campaigns without authoritative truncation.

Minimum release fixtures should exceed presentation-style limits:

```text
>1000 assets for one party or portfolio query
>1000 valuations for one asset/history query
>1000 obligations for one party/query family
>1000 settlements/operations
```

Required semantics:

- authoritative `all/history/reconcile/asOf` paths are complete or explicitly paginated/keyset-based;
- hidden `LIMIT 1000`, `.take(1000)` or bounded ContextBuilder lists cannot be reused as authoritative totals;
- net-worth/outstanding derivation cannot silently depend on the first N rows;
- bounded ContextBuilder/CharacterPanel summaries remain presentation only;
- scale data survive close/reopen;
- scale data survive backup/restore;
- derived cache deletion/rebuild produces identical semantic totals;
- campaign switch does not replace one campaign's portfolio cache with another's.

Performance optimization is allowed through indexes/materialized projections/checkpoints, but no cache may become unrebuildable truth.

---

# 22. Reopen / restore / campaign-switch oracle

## Reopen

For authoritative Phase-14 state:

```text
write -> close -> reopen -> CurrentSchema.ensure -> semantic equality
```

Must preserve:

- stable UIDs;
- lifecycle history;
- valuations;
- obligation terms;
- settlement/correction history;
- source links;
- ownership/finance references;
- exact amounts;
- provenance;
- temporal ordering.

Repeated ensure cannot duplicate records or change historical semantics.

## Restore

Required scenarios:

1. V13 backup restored under V14 -> safe forward migration with zero invented legacy object history.
2. V14 backup with active/historical assets -> exact equality after restore.
3. V14 backup with obligations and settlements -> exact outstanding/history equality.
4. V14 backup with legacy unresolved evidence -> evidence remains unresolved, no re-promotion duplicate.
5. Derived net-worth/cache data may be rebuilt without semantic drift.

## Campaign switch A -> B -> A

Use deliberately colliding strings:

```text
same assetUid
same obligationUid
same party UID strings
same valuationUid if namespace permits campaign scope
same currency UID
```

Expected:

- reads remain campaign-scoped;
- writes cannot cross campaigns;
- ownership resolution remains campaign-scoped;
- finance linkage remains campaign-scoped;
- derived portfolio/net-worth cannot leak between campaigns;
- returning to A preserves exact prior A state and does not rerun migrations as economic actions.

---

# 23. Semantic concurrency release gates

Sequential unit tests are insufficient where two operations can each validate a stale current state and together violate the contract.

Final validation must use independent SQLite connections/writers and synchronization barriers where architecture permits.

## ASSET-RACE-01 — duplicate asset identity

Concurrent create of same `(campaign, assetKind, assetUid)`.

Required:

```text
exactly one canonical identity/effect
```

An exact idempotent retry may resolve existing identity; conflicting immutable payload must fail.

## ASSET-RACE-02 — retire/destroy vs new ownership

One writer terminally retires/destroys asset while another creates/acquires OwnershipRecord using stale ACTIVE state.

Required: one legal serialization. No newly active ownership may reference an asset that is terminal in the winning order unless explicit policy allows that exact temporal transition atomically.

This gate requires coordination with Phase-12 asset registry lifecycle at the SQLite write boundary.

## ASSET-RACE-03 — retire/destroy vs new valuation

One writer retires/destroys; another adds current valuation.

Required: defined temporal policy and coherent serialization. No stale lifecycle precheck may create semantically post-terminal current valuation when forbidden.

## ASSET-RACE-04 — retire/destroy vs encumbrance

If encumbrances exist, stale ACTIVE validation cannot create an invalid new encumbrance after terminal lifecycle wins.

## VAL-RACE-01 — competing current valuations

If a policy allows only one current valuation for `(campaign, asset, valuationPolicy)`, two concurrent same-boundary valuations cannot both become conflicting current authority.

Historical multi-source valuations may coexist only if the model explicitly classifies them as separate sources/policies rather than two exclusive current values.

## OBL-RACE-01 — double full settlement

Initial outstanding = 100.

Concurrent:

```text
T1 settle 100
T2 settle 100
```

Required: aggregate applied settlement <= principal/outstanding. At most one full ordinary settlement wins.

## OBL-RACE-02 — competing partial settlements

Initial outstanding = 100.

Concurrent:

```text
T1 settle 60
T2 settle 60
```

Required: both cannot produce settled total 120. One must fail/adjust only under an explicit deterministic contract; no stale-read over-settlement.

## OBL-RACE-03 — payment vs cancellation

Concurrent settlement/payment and cancellation.

Required: one coherent historical ordering. A cancellation cannot coexist with a later ordinary payment treated as valid under a stale ACTIVE assumption unless policy explicitly supports that sequence.

## OBL-RACE-04 — default vs payment

Required: explicit deterministic policy. Historical facts remain consistent and no terminal status is silently overwritten.

## OBL-RACE-05 — novation/transfer vs settlement

If obligation party transfer/novation exists, a concurrent payment must bind to the correct authoritative obligation party state in the winning order. Stale parties cannot receive/owe settlement accidentally.

## COLL-RACE-01 — collateral release vs default enforcement

If implemented, no dangling or contradictory encumbrance/ownership state may survive.

## NW-RACE-01 — stale materialized net-worth cache

If a persisted net-worth/current-portfolio projection exists, concurrent asset/liability changes cannot let a stale cache overwrite a newer projection version. Cache may lag transiently only if explicitly non-authoritative and safely rebuilt; it may never become false authority.

## IDEMP-RACE-01 — duplicate operation UID

Two callers submit the same Phase-14 operation UID concurrently.

Required:

```text
at most one semantic effect
```

Conflicting reuse of the same operation identity must fail.

## CAMPAIGN-RACE-01 — same UID strings in different campaigns

Concurrent writes in A and B using same asset/obligation IDs must remain fully isolated.

---

# 24. Required authoritative protection placement

Kotlin/domain prechecks are useful for:

- request shape;
- descriptive errors;
- World Pack rule selection;
- proposal construction;
- user-facing validation messages.

They are **not sufficient** for current-state invariants vulnerable to TOCTOU.

SQLite/authoritative transaction boundary protection is required for implementation equivalents of:

- stable UID uniqueness;
- campaign-scoped reference validity;
- lifecycle/reference races;
- immutable history/delete protection;
- duplicate operation/idempotency identity;
- settlement aggregate <= principal/outstanding;
- legal terminal lifecycle CAS/version transition;
- exclusive current valuation/interval rules where applicable;
- cross-domain atomicity when one high-level operation commits multiple required effects;
- projection version/CAS if a current wealth cache can be updated by competing writers.

Automatic release-blocker pattern:

```text
SELECT current state
-> Kotlin require(...)
-> later unconditional INSERT/UPDATE
```

when another writer may invalidate the assumption before commit.

---

# 25. Cross-domain atomicity oracle

Phase 14 will often participate in multi-domain operations.

Required invariant:

```text
all required authoritative effects commit
OR
none commit
```

Examples final validation should exercise where APIs exist:

### Purchase failure

Payment commits then title/asset registration fails.

Expected: whole higher-level operation rolls back if title/asset effect was required by that operation.

### Sale failure

Ownership transfer succeeds but ledger payment fails.

Expected: rollback of all required effects for a single atomic sale contract.

### Debt settlement failure

Financial payment succeeds but obligation settlement link fails.

Expected: if both are one semantic payment-to-settle operation, neither survives alone.

### Asset retirement failure

Asset lifecycle transition succeeds but required registry/active ownership closure fails.

Expected: no half-retired semantic state.

This does **not** mean every standalone payment must automatically transfer ownership. Atomicity applies only when one explicit high-level operation declares multiple effects as required.

---

# 26. Derived/cache/presentation classification

## AUTHORITATIVE

- Phase-14-owned Asset identity and lifecycle facts;
- committed valuation observations/history;
- Obligation identity/terms;
- obligation operations/settlements/corrections;
- encumbrance history if implemented;
- stable mapping/provenance evidence required to interpret canonical objects.

## DERIVED

- current asset value under a chosen policy;
- party-attributable asset value after OwnershipRecord share;
- current outstanding debt/receivable;
- portfolio totals;
- net worth;
- leverage/debt ratios;
- current encumbrance summaries.

## CACHE/PRESENTATION

- CharacterPanel assets/liabilities section;
- ContextBuilder bounded economy summary;
- cached current net worth;
- dashboard totals;
- search/index materializations.

## LEGACY EVIDENCE ONLY

- `character_finances.debt`;
- `character_finances.property_value`;
- `character_finances.investment_value`.

Deleting derived/cache/presentation rows must never destroy canonical economic history.

---

# 27. Semantic adversarial matrix

Final candidate must be attacked at least with these cases:

| ID | Attack | Required result |
|---|---|---|
| P14-ADV-001 | arbitrary asset kind string | reject unless registered/active |
| P14-ADV-002 | nonexistent asset target | reject |
| P14-ADV-003 | wrong-campaign asset/party | reject |
| P14-ADV-004 | same label, different asset UID | remain distinct |
| P14-ADV-005 | duplicate asset UID changed semantics | reject |
| P14-ADV-006 | owner transfer regenerates asset UID | forbidden |
| P14-ADV-007 | possession interpreted as title | forbidden |
| P14-ADV-008 | payment interpreted as asset/title | forbidden |
| P14-ADV-009 | ownership acquisition auto-creates valuation | forbidden |
| P14-ADV-010 | purchase price overwrites market/appraisal history | forbidden |
| P14-ADV-011 | missing valuation becomes zero | forbidden/incomplete result |
| P14-ADV-012 | unknown valuation currency | reject |
| P14-ADV-013 | Float/Double/REAL exact-money authority | reject design/path |
| P14-ADV-014 | negative/overflow principal | reject |
| P14-ADV-015 | nonexistent obligor/beneficiary | reject |
| P14-ADV-016 | creditor/debtor cross-campaign | reject |
| P14-ADV-017 | settlement > outstanding | reject atomically |
| P14-ADV-018 | destructive settlement-history update/delete | reject |
| P14-ADV-019 | payment auto-settles arbitrary liability | forbidden without explicit link |
| P14-ADV-020 | cash balance used as complete net worth | forbidden |
| P14-ADV-021 | 50% owner receives 100% value | forbidden |
| P14-ADV-022 | organization asset included personally without ownership interest | forbidden |
| P14-ADV-023 | business shares + underlying business assets double counted personally | forbidden |
| P14-ADV-024 | legacy property_value expanded into fake asset | forbidden |
| P14-ADV-025 | legacy investment_value expanded into fake security | forbidden |
| P14-ADV-026 | legacy debt expanded into fake creditor/history | forbidden |
| P14-ADV-027 | generic StatePatch writes canonical P14 table | reject |
| P14-ADV-028 | hidden LIMIT 1000 affects authoritative portfolio/net worth | forbidden |
| P14-ADV-029 | reopen changes UIDs/history/totals | forbidden |
| P14-ADV-030 | restore duplicates promoted records | forbidden |
| P14-ADV-031 | A -> B -> A leaks portfolio/liability cache | forbidden |
| P14-ADV-032 | retired asset physically deleted with history | reject |
| P14-ADV-033 | obligation physically deleted with history | reject |
| P14-ADV-034 | backdated valuation/settlement invalidates later history | reject or explicit correction semantics |
| P14-ADV-035 | same operation UID conflicting payload | reject |

---

# 28. Net-worth oracle examples

These are semantic control scenarios for final revalidation.

## NW-01 — cash only

Party P has one Phase-13 account balance 100 and no assets/liabilities.

Expected under a policy including cash:

```text
net worth = 100
```

No duplicate cash AssetRecord may add another 100.

## NW-02 — asset + liability

P owns 100% of Asset X valued 1000 and owes obligation 300.

Expected:

```text
net worth = 700
```

assuming no other components and same currency.

## NW-03 — co-ownership

X valued 1000. P owns 25%, Q owns 75%.

Expected attributable gross values:

```text
P = 250
Q = 750
```

subject to exact share/minor-unit policy.

## NW-04 — missing valuation

P owns X, but X has no usable valuation under requested policy.

Expected:

```text
net worth status = incomplete/unknown component
```

not `X=0` silently.

## NW-05 — organization isolation

Organization O owns property 1000. P is merely a character associated with O but owns no economic share in O.

Expected:

```text
O may have 1000 gross asset value
P receives 0 attributable value from that property
```

## NW-06 — business share anti-double-count

P owns a 50% share asset in business B valued at 500. B itself owns machinery/property 1000.

Expected personal projection follows the chosen share valuation once. It must not count both 500 share value and 500/1000 of every underlying B asset unless the valuation policy intentionally decomposes the business interest and omits the share value to avoid duplication.

## NW-07 — multi-currency

P owns values in currencies C1 and C2 with no canonical exchange operation/rate authority.

Expected: separate currency totals or explicit inability to form one exact cross-currency net worth. No implicit display-name conversion.

---

# 29. Required final semantic revalidation evidence

When CHAT-1 supplies the final WORK-061 candidate SHA, CHAT-2 must validate exactly that SHA and capture evidence for:

1. fresh master/candidate identity;
2. exact CI run tied to candidate SHA;
3. actual V14 migration/routing;
4. actual schema columns/types/constraints/indexes/triggers;
5. Asset/Valuation/Obligation models;
6. typed repositories/stores/APIs;
7. ownership registry integration;
8. Phase-13 currency/transaction integration;
9. SourceOfTruth/StatePatch protection;
10. temporal/history semantics;
11. net-worth projection implementation;
12. authoritative completeness beyond 1000 rows;
13. reopen/restore/campaign switch;
14. exact arithmetic/overflow behavior;
15. semantic race tests with separate SQLite connections for all implemented race-sensitive operations;
16. `PRAGMA integrity_check` and `PRAGMA foreign_key_check` plus generic registry/reference checks where FKs are insufficient;
17. regression safety for Phase 3–13;
18. absence of Phase-15 DevelopmentProject work.

Green CI is supporting evidence only. A semantic PASS requires the actual authoritative boundaries to satisfy this oracle.

---

# 30. Final verdict contract

Before final WORK-061 runtime SHA exists, this oracle issues **NO PHASE-14 PASS/FAIL**.

After the final SHA is supplied, semantic revalidation must output exactly one of:

```text
PHASE 14 SEMANTIC REVALIDATION: PASS
```

or

```text
PHASE 14 SEMANTIC REVALIDATION: FAIL
```

for that exact SHA.

A FAIL report must include:

```text
minimal reproducer
violated semantic invariant
exact schema/runtime path
expected vs actual
minimal correction scope
```

No fix is to be implemented by this read-only oracle worker unless a later separate work item explicitly changes the role.

---

# 31. Release-blocker summary

Any of the following is a Phase-14 semantic release blocker:

- `Asset/Liability == Ownership/Inventory/Equipment/Financial Ledger` conflation;
- duplicate competing asset identity system disconnected from Phase-12 registry;
- player-only asset/liability holder model;
- arbitrary-string unresolved party/asset references accepted;
- Float/Double/REAL authority for exact monetary values;
- mutable-only current valuation with no history semantics;
- missing valuation silently treated as zero;
- liability represented only as negative cash/current summary;
- over-settlement possible, especially concurrently;
- Kotlin-only precheck for race-sensitive lifecycle/settlement invariant;
- net worth writable as authority;
- net-worth double counting cash, co-owned assets or business underlying assets;
- organization wealth leaking into player wealth;
- legacy aggregate debt/property/investment promoted into fabricated detailed history;
- generic StatePatch able to mutate canonical Phase-14 authority;
- authoritative reads silently truncated at 1000;
- reopen/restore/campaign switch changes or leaks semantic truth;
- Phase-14 migration corrupts accepted Phase 3–13 domains;
- Phase-15 DevelopmentProject implementation introduced before Phase 14 acceptance.

---

# 32. Oracle conclusion

The accepted Phase-12/13 runtime provides sufficient foundations for a clean Phase-14 design:

```text
Phase 12 -> stable generic party/asset ownership-reference authority + exact temporal shares
Phase 13 -> stable generic financial accounts/currencies + exact immutable ledger
Phase 14 -> stable economic asset identity + valuation history + obligation identity/history + derived wealth
```

The essential semantic equation for Phase 14 is not merely `assets - liabilities`; it is:

```text
stable economic objects
+ explicit historical valuations
+ exact OwnershipRecord share at time T
+ explicit obligations and settlements
+ accepted Phase-13 cash/transaction evidence
-> deterministic, provenance-backed, non-double-counted DERIVED net worth
```

while preserving:

```text
Asset/Liability
!= OwnershipRecord
!= Inventory
!= Equipment
!= Financial Ledger
```

Status remains:

```text
SEMANTIC ORACLE READY
FINAL WORK-061 RUNTIME REVALIDATION PENDING
```

No Phase-14 PASS/FAIL is issued here. Phase 15 is not started.
