# WORK-20260810-059 — Phase 14 Architecture Audit

Status: READ-ONLY NEXT-PHASE ARCHITECTURE AUDIT

Work ID: `WORK-20260810-059`
Worker: `CHAT-4`
Role: `READ-ONLY NEXT-PHASE ARCHITECTURE AUDITOR`
Repository: `piotreksmaga-art/rpg-os-android`
Accepted Phase-12 runtime baseline: `d5f1fd6e7a660e3e398f155784f8602c486b9906`
Fresh master observed before report write: `80f3cbf577217905286585083063896691eb3c0e`
Phase-13 implementation owner: `CHAT-1`
Allowed write scope: this report only.

This document is architecture/audit only. It does not implement Phase 13 or Phase 14, does not modify Kotlin runtime, SQLite runtime schema, migration routing, production code or implementation tests, and does not mark any global Roadmap status COMPLETE.

---

# ROADMAP NEXT PHASE AFTER PHASE 13

## 14. Assets / debts / obligations / net-worth model

The canonical Roadmap section `FAZA A — FUNDAMENT DANYCH I GRACZA` orders the relevant phases as:

```text
12. OwnershipRecord domain
13. Financial Ledger / Economy model
14. Assets / debts / obligations / net-worth model
15. DevelopmentProject model
```

Roadmap currently describes Phase 14 as PARTIAL because legacy summary fields equivalent to `debt`, `property_value` and `investment_value` exist, while a canonical Asset/Ownership/Liability domain does not.

Therefore the exact phase immediately following Phase 13 is:

```text
PHASE 14 — Assets / debts / obligations / net-worth model
```

Phase 14 implementation is BLOCKED until Phase 13 is final and accepted. This report prepares the contract only.

---

# 1. Canonical scope

Phase 14 must turn aggregate, presentation-like wealth hints into a generic authoritative domain capable of representing durable economic objects and obligations without conflating them with money balances, possession or OwnershipRecord history.

MASTER requires the economy layer to distinguish at least:

```text
cash
receivables
debts
property
land
business
laboratory
workshop
vehicle
shares
rare assets
liabilities
```

and states:

```text
Personal wealth != organization wealth
Net worth = assets - liabilities
```

The Phase-14 hard semantic split is therefore:

```text
Asset identity
!= OwnershipRecord
!= Inventory possession
!= Equipment state
!= Financial Ledger transaction
!= current cash/account balance
!= Asset valuation
!= Liability/obligation contract
!= Net-worth projection
```

An asset may exist without being owned by the player. An OwnershipRecord may point to an asset without storing its valuation or debt terms. A payment may occur without transferring an asset. A liability may exist before, after or independently from a cash payment. Net worth is never authoritative mutable state; it is a rebuildable projection over owned assets and liabilities under an explicit valuation basis.

---

# 2. Repository/runtime baseline actually present

## 2.1 Accepted Phase 12 foundations

Accepted Phase 12 provides a generic Ownership reference layer with:

- `ownership_owner_kinds`,
- `ownership_asset_kinds`,
- `ownership_party_registry`,
- `ownership_asset_registry`,
- `ownership_records`,
- `ownership_operations`,
- campaign-scoped reference validation,
- lifecycle guards,
- exact ownership shares,
- immutable temporal ownership history.

The accepted `OwnershipReferenceRegistry` is explicitly designed so future entity/asset domains can register stable namespaces and campaign-scoped targets. Phase 14 should consume this authority rather than creating a parallel generic asset identity system.

For non-ItemInstance assets the preferred integration is:

```text
Phase-14 canonical Asset
-> register/maintain compatible target identity in ownership_asset_registry
-> OwnershipRecord references the same stable asset kind + asset UID
```

The Phase-14 store remains authoritative for asset-specific semantics; the Phase-12 registry remains the generic reference validity boundary for Ownership.

## 2.2 Existing legacy finance summary

Current runtime reads legacy `character_finances` fields equivalent to:

```text
ryo
monthly_income
monthly_expenses
debt
property_value
investment_value
updated_chapter
```

The fields `debt`, `property_value`, `investment_value` are aggregate summaries. They do not identify a creditor, debtor, contract, property, business, shareholding or valuation source.

Therefore they are migration evidence only.

Forbidden inference examples:

```text
property_value = 500000
=> create Property asset worth 500000

investment_value = 100000
=> create BusinessShare asset

debt = 25000
=> create Liability with unknown creditor
```

No such promotion is legal without explicit one-to-one source evidence.

## 2.3 No canonical Phase-14 runtime on accepted baseline

The accepted Phase-12 application tree has typed Inventory, Equipment and Ownership stores/models but no accepted typed `AssetStore`, `LiabilityStore`, `ObligationStore`, `NetWorthStore` or equivalent Phase-14 authority.

The current `CampaignRepository` similarly has no typed Phase-14 API. CharacterPanel v1 has no dedicated assets/liabilities/net-worth section.

This is consistent with Roadmap Phase 14 remaining PARTIAL/MISSING as a canonical domain.

## 2.4 Phase 13 is in progress, not an accepted dependency

Fresh master at report time contains Phase-13 preparatory audits, including WORK-058 migration/integrity planning and WORK-060 adversarial planning. No accepted Phase-13 runtime contract is available at this report boundary.

Phase 14 must therefore define its required Phase-13 interface abstractly and re-read the final accepted implementation before any Phase-14 coding begins.

---

# 3. Required prior foundations

Phase 14 depends on:

1. Phase 1 — campaign identity / unified repository.
2. Phase 2 — provenance patterns and protected authoritative writes.
3. Phase 3 — stable active player identity and Persistent/Derived/Runtime classification.
4. Phases 4–9 — typed stable definitions/state patterns; no wealth model may be hidden inside stats/resources.
5. Phase 10 — stable unique item identity; ItemInstance remains an ownable asset kind where appropriate.
6. Phase 11 — write-boundary concurrency lesson: Kotlin precheck alone is insufficient.
7. Phase 12 — authoritative owner/asset reference registries and temporal OwnershipRecord.
8. Phase 13 — accepted currency/account/ledger/value representation and transaction authority.

Hard blocker:

```text
No Phase-14 implementation before final accepted Phase-13 runtime is inspected.
```

The exact amount representation, currency identity, account holder model, idempotency keys and ledger APIs must be consumed from Phase 13 rather than redefined by Phase 14.

---

# 4. Canonical domain model

Exact class/table names remain implementation decisions. The semantic contract should be equivalent to the following.

## 4.1 AssetDefinition / AssetKindDefinition

Asset categories must remain generic and extensible.

```text
AssetKindDefinition
- assetKindUid                 stable namespace UID
- assetClass                   ASSET / RECEIVABLE / SECURITY / PROPERTY / BUSINESS / OTHER
- displayName
- valuationPolicyUid?
- depreciable?                 capability flag, not hardcoded world logic
- worldPackUid / namespace?
- definitionStatus             ACTIVE / DEPRECATED
- definitionVersion >= 1
- provenance
- metadata
```

Do not hardcode Naruto-specific types into Core. Core may provide generic categories equivalent to `PROPERTY`, `LAND`, `BUSINESS`, `VEHICLE`, `SHARE`, `RECEIVABLE`, `RARE_ASSET`, but World Packs/campaign content may register additional stable asset kinds.

Where semantically appropriate, Phase 14 should reuse the Phase-12 `ownership_asset_kinds` UID as the externally referenceable asset-kind namespace rather than creating a second unrelated kind UID.

## 4.2 AssetRecord

```text
AssetRecord
- campaignId
- assetUid                     stable asset identity
- assetKindUid
- canonicalOwnerIndependentIdentity
- lifecycleStatus              ACTIVE / RETIRED / DESTROYED / LIQUIDATED or small equivalent
- createdOrder / validFrom
- retiredOrder? / validUntil?
- sourceEventUid?
- recordVersion >= 1
- provenance
- metadata
```

Asset identity must not include current owner. Ownership can change while `assetUid` remains stable.

Asset identity must not include display name, current market price or physical location.

For an ItemInstance already owned by Phase 10, Phase 14 must not duplicate the item identity into a second canonical AssetRecord unless the accepted contract deliberately uses a lightweight adapter/reference record. `ITEM_INSTANCE` already has stable identity and should be referenced, not cloned.

## 4.3 Asset valuation history

Valuation is temporally variable and must be separate from asset identity.

```text
AssetValuation
- campaignId
- valuationUid                 stable historical record UID
- assetKindUid
- assetUid
- valueCurrencyUid
- amountExact                  Phase-13-compatible exact value representation
- valuationTypeUid             MARKET / BOOK / APPRAISAL / FACE / CUSTOM
- effectiveOrder
- validUntilOrder?
- sourceEventUid?
- confidence?                  when valuation is estimate rather than legal fact
- provenance
- valuationVersion
```

Valuation history should be append/history preserving. A price change should not rewrite historical valuation.

Current value is a query/projection over the appropriate active/as-of valuation, not a mutable field on Asset identity.

Not all assets need a valuation. Missing valuation must remain `UNKNOWN`, not silently become zero.

## 4.4 Liability / Debt / Obligation

Debt is a specialization of a broader obligation contract.

Recommended generic split:

```text
ObligationRecord
- campaignId
- obligationUid               stable UID
- obligationTypeUid           DEBT / RECEIVABLE / SERVICE / PAYMENT / DELIVERY / OTHER
- obligorPartyRef              who owes/performs
- beneficiaryPartyRef          who is owed/receives
- currencyUid?                 required only for monetary obligations
- principalAmountExact?        Phase-13 exact amount type
- assetRef?                    optional collateral/delivery subject
- createdOrder
- dueOrder?
- validUntilOrder?
- lifecycleStatus              ACTIVE / SETTLED / DEFAULTED / CANCELLED / EXPIRED
- sourceEventUid?
- sourceContractUid?
- recordVersion
- provenance
- metadata
```

A monetary debt should not be represented only as a negative balance. The obligation explains who owes whom, how much, why and when.

A receivable is the same underlying obligation viewed from the beneficiary side. Avoid storing two independent mutable records for the same debt unless the model uses a shared canonical `obligationUid` and derives both views.

## 4.5 Obligation settlement history

Principal/status must not be mutated arbitrarily without audit history.

Recommended authoritative settlement events/legs:

```text
ObligationSettlement
- campaignId
- settlementUid
- obligationUid
- settlementKind              PAYMENT / FORGIVENESS / WRITE_OFF / TRANSFER / NOVATION / OTHER
- amountExact?                for quantitative settlement
- financialTransactionUid?    when money actually moved
- ownershipOperationUid?      when collateral/title moved
- effectiveOrder
- sourceEventUid?
- provenance
```

Derived outstanding amount:

```text
principal
- valid applied settlements
= outstanding amount
```

If Phase 14 chooses a current-state row for performance, it must be DERIVED/rebuildable or mutation-guarded by the canonical settlement history.

## 4.6 Asset encumbrance / collateral link

Ownership and usable economic value can differ when an asset is pledged or encumbered.

```text
AssetEncumbrance
- campaignId
- encumbranceUid
- assetRef
- obligationUid
- encumbranceTypeUid
- priority / rank if required
- validFrom
- validUntil?
- sourceEventUid?
- provenance
```

This relation must not itself transfer OwnershipRecord title.

## 4.7 NetWorthSnapshot / projection

Net worth is DERIVED:

```text
NetWorth(asOf T, party P, valuationPolicy V, currency C)
=
SUM(value of P-owned asset shares under V at T)
+ valid receivables
- outstanding liabilities
```

Cash/account balances may be included only through the accepted Phase-13 balance projection and only once.

Critical anti-double-count rules:

- do not count a bank/cash balance both as account balance and as another AssetRecord unless the model explicitly normalizes one representation out;
- do not count a business's owned assets directly as the shareholder's personal assets in addition to the value of the shareholder's business interest;
- do not count gross property value and a separate duplicate investment summary;
- apply OwnershipRecord share exactly;
- personal party and organization party remain separate.

A persisted `NetWorthSnapshot` may exist as cache/presentation with `generatedAtOrder`, `valuationPolicyUid`, `currencyUid` and dependency fingerprints, but deletion must never lose authoritative economic history.

---

# 5. Stable identity contract

Every authoritative object requires a stable UID:

```text
assetUid
valuationUid
obligationUid
settlementUid
encumbranceUid
```

Names are labels, never identity.

Required properties:

- nonblank UID;
- campaign isolation where identity is campaign-scoped;
- duplicate UID collision fails loudly or exact replay is handled by an explicit idempotency contract;
- lifecycle changes do not regenerate object identity;
- historical valuation/settlement entries use new UIDs and preserve predecessors;
- same display name may legally correspond to many distinct assets/obligations;
- same UID string in two campaigns must not leak data.

---

# 6. Reference integrity

Phase 12 established the rule:

```text
nonblank string UID != valid reference
```

Phase 14 must apply it to every reference.

## 6.1 Party references

Obligor, beneficiary, owner-facing projections and related parties must resolve through an authoritative party/entity reference mechanism.

Preferred option where semantically compatible:

- reuse Phase-12 `ownership_party_registry` / owner-kind namespace for party identities;
- or define one shared generic party resolver consumed by both Phase 12 and Phase 14.

Do not create a second arbitrary-string `partyUid` namespace with no resolver.

## 6.2 Asset references

Phase-14 Asset creation should register/activate its stable external reference through the accepted Phase-12 asset registry so OwnershipRecords can target it.

Unknown asset kind or unresolved asset target must fail at authoritative write boundary.

## 6.3 Financial references

Any Phase-14 monetary value or settlement linkage must use the exact accepted Phase-13 currency/account/FinancialTransaction identity. No hardcoded `ryo`, no display-name currency matching, and no guessed account holder.

## 6.4 Cross-domain reference lifecycle

Retirement/deletion must preserve historical references.

Examples:

- asset cannot be physically deleted while OwnershipRecord, valuation history, encumbrance or obligation history references it;
- obligation cannot be deleted after settlement history exists;
- party retirement cannot make historical obligations unreadable;
- financial transaction correction does not delete the original transaction referenced by settlement history.

Use soft lifecycle transitions + append-preserved history rather than destructive cascade that erases campaign truth.

---

# 7. Persistent / Derived / Runtime classification

## AUTHORITATIVE

- stable Asset identity where Phase 14 owns that asset type;
- asset lifecycle facts;
- asset valuation history when explicitly committed as campaign fact/estimate record;
- Obligation identity and legal terms;
- settlement/cancellation/default history;
- encumbrance relations/history;
- provenance/reference mappings required to interpret the records.

## DERIVED

- current asset value under a valuation policy;
- outstanding debt/receivable amount;
- current asset/liability totals by party;
- net worth;
- leverage/debt ratio;
- current encumbrance summary;
- current economic portfolio composition.

## CACHE / PRESENTATION

- CharacterPanel economy/assets section;
- ContextBuilder bounded wealth summary;
- dashboard net-worth values;
- valuation indexes/materialized aggregates.

## NOT PHASE-14 AUTHORITY

- legacy `property_value` aggregate;
- legacy `investment_value` aggregate;
- legacy aggregate `debt`;
- raw AI narration;
- UI totals.

---

# 8. Temporal semantics

Phase 14 requires historical queries from the start even though full Temporal Engine is later.

Use one deterministic ordering compatible with accepted Phase 12/13, e.g. campaign order/event order.

Required interval semantics should be explicit, preferably:

```text
[validFrom, validUntil)
```

Queries should support equivalents of:

```text
assetState(asset, asOf)
valuation(asset, policy, asOf)
obligationState(obligation, asOf)
outstanding(obligation, asOf)
portfolio(party, asOf)
netWorth(party, asOf, valuationPolicy, currency)
```

Do not rewrite past records when current valuation, ownership or debt status changes.

A liability that settles at T remains historically active before T.

A transferred asset remains the same asset; OwnershipRecord history determines who owned what share at T.

---

# 9. Provenance

Every authoritative creation/change requires sufficient provenance to answer why the record exists.

Minimum semantics should include implementation equivalents of:

- source type;
- source ID/event/operation;
- campaign order/turn when available;
- actor/method when available;
- migration source when applicable;
- engine/schema version if the project pattern requires it.

Valuation provenance is especially important because value may be:

- market observation;
- appraisal;
- purchase price;
- book value;
- face value;
- estimate.

Do not silently promote estimates to objective historical market facts.

Migration must use explicit `LEGACY_EVIDENCE` / `MIGRATION` provenance rather than inventing creditors, contracts, appraisers or events.

---

# 10. Legacy policy

Phase-14 migration must be additive, conservative and lossless.

Required rules:

1. Never delete or destructively rewrite `character_finances` during first adoption.
2. `property_value` remains aggregate evidence unless exact source assets exist.
3. `investment_value` remains aggregate evidence unless exact source instruments exist.
4. `debt` remains aggregate evidence unless creditor/obligation identity and amount are unambiguous.
5. Never infer asset type from a display label alone.
6. Never infer current legal owner from possession/equipment.
7. Never infer a liability creditor from narration/reason text during automatic migration.
8. Never synthesize historical valuations from a single current summary.
9. Preserve unresolved evidence explicitly with stable evidence UID/mapping version/provenance if the implementation introduces a reconciliation table.
10. Migration rerun must not create duplicate assets, obligations or valuation rows.

Safe migration result for ambiguous legacy summaries may be:

```text
canonical Phase-14 rows created = 0
legacy aggregate evidence preserved = yes
```

That is preferable to fabricated continuity.

---

# 11. Integration with Phase 12 Ownership

OwnershipRecord is the legal/right-holder relationship. AssetRecord is the economic object.

Hard invariant:

```text
Asset existence != ownership
Ownership != possession
Ownership != valuation
```

Phase-14 asset creation may register the asset reference but must not automatically create an OwnershipRecord unless the same explicit committed domain operation supplies valid ownership semantics.

When ownership exists:

```text
ownership share from OwnershipRecord
x asset value from AssetValuation
= owner-attributable gross asset value
```

Co-ownership must use exact Phase-12 shares; no floating-point share multiplication.

Transfer of ownership must not rewrite asset identity or valuation history.

Asset retirement/destruction must coordinate with active OwnershipRecords and historical references. If destruction terminates ownership, both domain effects must commit atomically in the future transaction path.

---

# 12. Integration with Phase 13 Financial Ledger

Phase 14 must consume the final accepted Phase-13 model after it exists.

Required conceptual links:

- acquisition transaction may explain purchase cost, but purchase price != permanent asset valuation;
- sale transaction may coexist with Ownership transfer;
- debt settlement may reference one or more canonical FinancialTransactions;
- receivable payment reduces outstanding obligation through a settlement record linked to the ledger;
- interest/fees become explicit obligation changes and/or financial transactions according to accepted rules;
- account balances contribute to wealth exactly once.

No Phase-14 code should independently mutate Phase-13 balances.

Conceptual future purchase flow:

```text
validated payment / finance effect
+ explicit Ownership transfer
+ optional possession transfer
+ asset acquisition/valuation evidence
+ events/provenance
-> one atomic transaction boundary
```

Until global TurnTransaction exists, any interim cross-domain operation must still be one SQLite transaction or fail closed.

---

# 13. Repository / store / API boundaries

Recommended logical boundaries:

```text
AssetRepository / AssetStore
- registerAsset(...)
- asset(assetRef)
- assetsByKind(...)
- retireAsset(...)
- addValuation(...)
- valuationAt(...)
- valuationHistory(...)

ObligationRepository / ObligationStore
- createObligation(...)
- obligation(uid)
- obligationsByParty(...)
- recordSettlement(...)
- outstandingAt(...)
- activeObligations(...)

WealthProjectionService
- assetsForParty(...)
- liabilitiesForParty(...)
- netWorth(...)
```

`CampaignRepository` may expose typed Phase-14 operations/read models, but raw SQLite handles must not become the normal write interface.

AI/StatePatch must not directly insert/update/delete canonical Phase-14 rows once Phase 14 becomes authoritative. Follow the Phase-2/Phase-13 pattern of protecting typed authoritative tables from generic patch bypass.

CharacterPanel and ContextBuilder consume bounded typed projections only; they never write back into authority.

---

# 14. Lifecycle rules

## Asset

Possible canonical lifecycle:

```text
ACTIVE -> RETIRED / DESTROYED / LIQUIDATED
```

Transitions must be explicit and provenance-backed. Historical identity remains queryable.

## Obligation

Recommended lifecycle:

```text
ACTIVE
-> SETTLED
-> or DEFAULTED
-> or CANCELLED
-> or EXPIRED
```

Do not allow arbitrary status toggles. Status must agree with settlement/default/cancellation evidence.

A settled obligation cannot later receive an ordinary additional settlement unless correction/reopen semantics are explicit and append-preserving.

## Valuation

Valuation entries are historical observations/decisions and should not be deleted merely because a newer value arrives.

---

# 15. Failure semantics

Every authoritative operation must be fail-closed and atomic.

On validation/constraint failure:

- no partial Asset row;
- no partial registry target;
- no partial OwnershipRecord;
- no partial Liability/Settlement row;
- no partial FinancialTransaction;
- no changed derived net-worth cache;
- no guessed recovery data.

Stable operation/idempotency UID should be required where an operation can be retried.

Retry of an already committed identical operation returns deterministic already-committed/existing result. Retry with same operation UID but changed semantics must fail loudly.

---

# 16. Concurrency / TOCTOU architecture

Phase 11 and Phase 12 make SQLite write-boundary protection mandatory for race-sensitive invariants.

Kotlin precheck alone is not sufficient whenever two writers can both read a valid old state and then commit incompatible results.

## 16.1 Race-sensitive invariants

The following must be enforced in the same serialized write transaction and, where possible, by SQLite constraints/triggers/UNIQUE/CAS guards:

- duplicate Asset UID creation;
- asset kind/target registry consistency;
- asset retirement vs concurrent new ownership/encumbrance/reference;
- duplicate Obligation UID creation;
- obligation settlement total must not exceed outstanding amount;
- two concurrent full settlements cannot both win;
- settlement vs cancellation/default race;
- asset encumbrance priority/exclusivity rules where applicable;
- collateral release vs concurrent settlement/default;
- valuation effective interval overlap where a policy requires one active value;
- concurrent destructive lifecycle transition;
- duplicate operation/idempotency UID;
- net-worth materialization version/cache swap if persisted.

## 16.2 Kotlin precheck insufficiency examples

### Double settlement

```text
Outstanding = 100
T1 reads 100 and proposes settle 80
T2 reads 100 and proposes settle 80
```

Without authoritative in-transaction recheck both could commit 160 total settlement.

Required boundary:

```text
BEGIN WRITE TRANSACTION
-> recompute authoritative outstanding
-> CAS/guard settlement
-> append settlement + optional ledger link
-> commit
```

### Retirement race

```text
T1 validates asset has no active encumbrance and retires it
T2 concurrently creates encumbrance based on pre-retirement ACTIVE state
```

DB boundary must ensure exactly one legal ordering and reject invalid post-retirement reference creation.

### Duplicate valuation/current record

If a valuation policy permits only one current valuation for `(campaign, asset, policy)`, a partial unique index/overlap trigger is needed. Application-only lookup is insufficient.

---

# 17. Required race matrix before implementation

Phase-14 implementation must not begin without turning this matrix into executable tests or equivalent acceptance fixtures.

| Race | Required outcome |
|---|---|
| same asset UID created concurrently | exactly one canonical identity |
| retire asset vs create OwnershipRecord | one valid serialization; no unresolved active title |
| retire asset vs add valuation | defined policy; no silent write after terminal lifecycle |
| retire asset vs create encumbrance | invalid post-retirement relation rejected |
| two settlements each exhausting same debt | at most one may overrun boundary; aggregate <= principal |
| partial settlement A vs partial settlement B | both may commit only if aggregate remains valid |
| full settlement vs cancellation | exactly one terminal semantics wins or deterministic compatible ordering |
| default vs payment | explicit policy; history remains internally consistent |
| obligation transfer/novation vs payment | payment resolves correct current obligation party state |
| collateral release vs default enforcement | no dangling/contradictory encumbrance |
| valuation A vs valuation B at same effective boundary | deterministic policy / overlap guard |
| same operation UID retried concurrently | exactly one effect |
| campaign A write vs campaign B same UID strings | complete isolation |
| Phase-14 migration vs normal Phase-14 write | migration lock/order prevents duplicate bootstrap/promotion |

After every race fixture:

```sql
PRAGMA integrity_check;
PRAGMA foreign_key_check;
```

must remain clean, plus domain-specific registry/reference checks where references are trigger/resolver-based rather than direct FKs.

---

# 18. Persistence architecture

Phase 14 should use additive normalized tables with explicit campaign scope and indexed stable references.

Conceptual table groups:

```text
asset_kind_definitions / existing ownership_asset_kinds integration
assets
asset_valuations
obligations
obligation_settlements
asset_encumbrances
legacy_asset_liability_evidence/mappings (only if required)
```

Recommended indexes:

- `(campaign_id, asset_kind_uid, asset_uid)`;
- `(campaign_id, lifecycle_status, asset_kind_uid)`;
- `(campaign_id, asset_uid, valuation_type_uid, effective_order)`;
- `(campaign_id, obligor_kind_uid, obligor_uid, lifecycle_status, due_order)`;
- `(campaign_id, beneficiary_kind_uid, beneficiary_uid, lifecycle_status, due_order)`;
- `(campaign_id, obligation_uid, effective_order)` for settlements;
- `(campaign_id, asset_ref, active interval)` for encumbrances;
- source event / financial transaction / ownership operation link indexes.

Avoid authoritative JSON blobs for fields that participate in integrity constraints, joins, temporal queries or arithmetic.

---

# 19. Migration architecture

Future V14 migration must:

```text
ensureV14()
-> ensure accepted V13
-> additive schema only
-> indexes/constraints/triggers
-> explicit migration marker after successful creation
```

Required gates:

- clean bootstrap reaches V14 through production routing;
- V13 -> V14 upgrade preserves all Phase 3–13 state;
- full older-chain upgrade remains valid;
- reopen is idempotent;
- repeated ensure creates no duplicate promoted assets/obligations;
- restore older backup -> latest route -> V14;
- V14 backup/restore preserves active + historical assets/valuations/obligations/settlements;
- campaign switch A -> B -> A preserves isolation;
- no destructive migration of legacy finance summaries;
- no automatic Asset/Obligation synthesis without explicit evidence;
- migration marker count correct;
- `integrity_check = ok`;
- no FK/domain-reference violations.

Migration must not depend on bounded presentation readers or `LIMIT 1000` scans for completeness.

---

# 20. Scale requirements

RPG OS targets very long campaigns, so Phase 14 must assume:

- hundreds of thousands of economic changes;
- many historical valuations per asset;
- many obligations/settlements across years of simulated time;
- organizations and NPCs, not only player assets;
- long ownership histories.

Required scale properties:

- authoritative reads paginate/keyset rather than fixed hidden LIMIT;
- ContextBuilder remains bounded but is not the authority;
- net-worth computation must not full-scan all history every turn;
- current-value/current-liability projections may use rebuildable indexes/checkpoints;
- as-of historical queries use indexed temporal predicates;
- large migration processes in batches/streaming where needed;
- backup/restore preserves stable IDs exactly;
- deleting derived caches and rebuilding yields identical semantic totals.

Minimum scale fixture recommendations:

- >1000 assets for one party;
- >1000 valuations for one asset/history set;
- >1000 obligations;
- >1000 settlements;
- mixed ownership shares across many parties;
- reopen/restore equality after scale load.

---

# 21. Semantic gates

Implementation acceptance must verify at minimum:

## Domain separation

- possession does not prove ownership;
- ownership does not create asset valuation;
- payment does not automatically transfer title;
- asset transfer does not fabricate payment;
- debt is not represented solely as negative cash;
- net worth is derived, not directly writable.

## Identity/reference

- stable UIDs;
- no name-based identity;
- unknown party rejected;
- unknown asset kind/target rejected;
- wrong campaign rejected;
- retired/inactive reference behavior explicit;
- same-name assets remain distinct.

## Ownership integration

- co-owned asset attribution follows exact OwnershipRecord share;
- changing owner does not change asset UID;
- historical owner-at-time queries remain valid;
- asset registry integration does not duplicate Phase-12 identity.

## Liability

- principal/outstanding exact;
- zero/negative invalid principal rejected unless explicit nonmonetary obligation contract;
- settlement cannot exceed outstanding;
- historical settlements preserved;
- creditor/debtor roles cannot silently swap;
- settled/defaulted/cancelled state is internally consistent.

## Valuation

- unknown value != zero;
- valuation history preserved;
- currency UID stable/validated;
- exact amount representation uses Phase 13;
- purchase price does not automatically overwrite market value;
- appraisal/estimate provenance preserved.

## Net worth

- assets minus liabilities under documented policy;
- ownership shares applied exactly;
- personal != organization wealth;
- no double-counting cash/business underlying assets;
- missing valuations surface as incomplete/unknown, not fabricated zero;
- same input state produces deterministic projection.

---

# 22. Adversarial gates

The implementation should be attacked with at least these cases:

1. arbitrary string asset kind;
2. nonexistent generic asset target;
3. cross-campaign party reference;
4. same label, different asset UID;
5. duplicate asset UID with changed semantics;
6. retire asset while active ownership exists;
7. delete asset with historical ownership;
8. delete obligation with settlement history;
9. settle 101 against principal 100;
10. two concurrent 60/60 settlements against 100;
11. negative/overflow principal;
12. unknown currency;
13. liability creditor equals debtor when contract forbids self-obligation;
14. same settlement operation UID with changed amount;
15. valuation interval overlap under exclusive policy;
16. current valuation written after terminal asset retirement when forbidden;
17. asset owned 50/50 but net worth gives one party 100%;
18. organization-owned asset included in player net worth without share title;
19. business shares + business underlying assets double counted personally;
20. missing valuation interpreted as zero;
21. legacy `property_value` automatically expanded into fake Property;
22. legacy `debt` expanded into fake creditor;
23. StatePatch direct write to canonical Phase-14 table;
24. restore duplicates migration-promoted rows;
25. A -> B -> A campaign switching leaks liabilities/valuation cache.

---

# 23. Test matrix

## Model tests

- UID validation;
- lifecycle transition policy;
- exact amount adapter compatibility with Phase 13;
- net-worth arithmetic and incomplete-valuation semantics;
- obligation outstanding computation.

## Persistence tests

- asset CRUD only through legal lifecycle APIs;
- valuation append/history;
- obligation + settlements;
- encumbrances;
- reopen equality;
- campaign isolation.

## Migration tests

- fresh bootstrap;
- V13 -> V14;
- older chain -> V14;
- repeated ensure;
- legacy ambiguity = zero synthesis;
- backup/restore;
- campaign switch.

## Reference-integrity tests

- party registry/resolver;
- Phase-12 asset registry integration;
- Phase-13 currency/transaction references;
- retirement/delete guards;
- historical reference preservation.

## Concurrency tests

- complete race matrix from section 17 using separate SQLite connections where needed;
- post-race integrity/reference checks.

## Scale tests

- >1000 authoritative records per principal read path;
- no hidden truncation;
- bounded presentation reads explicitly separated from complete authority.

## Regression tests

Preserve accepted domains Phase 3–13, especially:

```text
Stats
Resources
Modifier/Resolver
Talent/Potential
Skills
Techniques
Innate/Racial/Evolution
Inventory
Equipment
Ownership
Financial Ledger / account balances
```

Phase-14 migration alone must not move inventory, equip items, transfer ownership, alter money balances or rewrite Phase-13 transactions.

---

# 24. Concurrency protection placement

The architecture should deliberately split checks into two classes.

## Kotlin/domain prechecks are appropriate for

- descriptive error messages;
- World Pack rule evaluation;
- request shape validation;
- known lifecycle intent;
- assembling a proposed changeset.

## SQLite write-boundary enforcement is mandatory for

- uniqueness;
- campaign-scoped FK/registry target validity where enforceable;
- lifecycle/reference races;
- aggregate settlement <= principal/outstanding;
- immutable-history protection;
- illegal destructive delete;
- one-current-record/temporal-overlap invariants;
- idempotency keys;
- CAS version transitions.

If an invariant depends on the current state and can be violated by two concurrent writers, a service-layer `SELECT` before `beginTransaction()` is not sufficient.

---

# 25. World-Pack boundary

Core owns:

- generic Asset/Obligation/Valuation mechanisms;
- stable IDs and lifecycle;
- reference validation;
- exact arithmetic integration;
- settlement history;
- net-worth projection framework;
- concurrency/integrity infrastructure.

World Pack/content owns:

- setting-specific asset kinds;
- legal concepts/rules where needed;
- valuation source rules;
- depreciation/appreciation rules if world-specific;
- canonical starting properties/businesses/obligations;
- setting-specific contract types.

Forbidden Core hardcodes:

- Naruto villages/clans as asset classes;
- ryo as universal currency;
- Bleach-specific institutions;
- chakra/reiatsu-based economic semantics;
- a fixed real-world legal taxonomy assumed universal.

---

# 26. ContextBuilder / CharacterPanel boundary

Phase 14 must not implement CharacterPanelSnapshot v2 early.

However it should expose typed bounded read models suitable for later profiles:

```text
AssetsSummary
LiabilitiesSummary
NetWorthProjection
RecentObligations
MajorAssets
```

ContextBuilder should receive only relevant bounded data, for example major current assets, active near-due obligations and a labeled net-worth projection.

A bounded GM context list is presentation. It must never be used to answer authoritative questions such as "all assets" or migration completeness.

Missing/corrupt valuation or unresolved legacy evidence must be diagnosable and must not silently render a false authoritative zero net worth.

---

# 27. Primary implementation risks

## R1 — duplicate identity systems
Creating `AssetRecord.assetUid` disconnected from Phase-12 `ownership_asset_registry` would reopen the generic-reference defect Phase 12 already fixed.

## R2 — double authority
Keeping mutable `property_value/investment_value/debt` as authoritative alongside canonical assets/liabilities creates conflicting truths.

## R3 — double counting
Cash, business interests, business underlying assets and ownership shares can be counted multiple times if net-worth policy is not explicit.

## R4 — invented migration history
Aggregate legacy fields cannot reconstruct contracts, creditors or individual assets.

## R5 — monetary drift
Phase 14 must reuse Phase-13 exact amount representation; no new REAL/Double authority.

## R6 — settlement TOCTOU
Concurrent settlements can overpay an obligation without DB/write-boundary protection.

## R7 — lifecycle/reference race
Retirement/destruction concurrent with new Ownership/encumbrance/valuation writes can create dangling economic state.

## R8 — player/organization leakage
Assets and liabilities belong to stable parties, not automatically to ActivePlayer.

## R9 — presentation becoming truth
Legacy summary/ContextBuilder/CharacterPanel totals must not become write sources.

## R10 — Phase-13 contract drift
Design assumptions about accounts/currencies/ledger must be revalidated against final accepted Phase 13 before implementation.

---

# 28. Blockers / dependencies

## BLOCKER A — Phase 13 not final/accepted

Phase 14 implementation remains blocked until CHAT-1's Phase-13 runtime is independently accepted. At implementation start re-audit:

- exact V13 schema;
- currency identity;
- account/party resolver;
- exact amount type;
- ledger UID/idempotency model;
- balance projection;
- transaction correction/reversal semantics;
- ContextBuilder integration;
- SourceOfTruth/StatePatch protection.

## BLOCKER B — exact legacy schema/data preflight

Before V14 migration code is written, inspect bundled and representative old campaign databases for all asset/debt-like tables and columns. The known summary fields alone do not prove absence of more specific evidence.

If exact unambiguous legacy asset/liability rows are discovered, map only those with stable evidence UIDs and explicit provenance. Otherwise synthesize nothing.

## BLOCKER C — final ownership registry contract must be preserved

Phase 14 must consume accepted Phase-12 reference registries and cannot weaken their retirement/delete/history guards.

---

# 29. Definition of Done for future Phase 14 implementation

Phase 14 is implementation-complete only when all of the following are true:

1. exact Roadmap scope is implemented without Phase-15 work;
2. stable generic Asset identity exists for Phase-14-owned asset kinds;
3. Asset identities integrate with Phase-12 generic asset reference authority;
4. Obligation/Debt/Receivable model has stable parties, terms and lifecycle;
5. settlement history is append-preserved and can link Phase-13 transactions;
6. valuation history is explicit and provenance-backed;
7. net worth is deterministic derived state, not a direct mutation target;
8. personal vs organization wealth is mechanically isolated;
9. legacy aggregate fields are not silently promoted into invented objects;
10. migration is additive/idempotent and preserves Phase 3–13;
11. generic StatePatch cannot bypass canonical Phase-14 write contracts;
12. campaign isolation is proven;
13. race matrix passes under real concurrent SQLite writers;
14. authoritative reads are complete beyond 1000 records and presentation reads are explicitly bounded;
15. reopen/backup/restore/campaign-switch equality is proven;
16. `PRAGMA integrity_check = ok` and reference/FK checks are clean;
17. semantic + migration + adversarial + concurrency gates pass;
18. build/CI is green on the exact candidate runtime;
19. no Phase-15 DevelopmentProject implementation is introduced.

---

# 30. Recommended future implementation sequence

This is planning only; this work item does not execute it.

1. Wait for Phase-13 final accepted runtime.
2. Re-read fresh master, final V13 migration/store/model/tests and final Phase-13 audits.
3. Perform exact DB preflight for all legacy asset/debt/obligation evidence.
4. Freeze Phase-14 Asset/Obligation/Valuation identity and lifecycle contracts.
5. Reuse/extend Phase-12 asset/party reference authority rather than duplicate it.
6. Freeze Phase-13 exact money/currency reference integration.
7. Implement additive V14 schema with write-boundary invariants.
8. Implement typed repositories/stores.
9. Protect canonical tables from generic StatePatch.
10. Add derived wealth/net-worth projection.
11. Add bounded ContextBuilder adapter only after authority exists.
12. Execute migration, semantic, adversarial, concurrency and scale suites.
13. Independent validation and CI.
14. Only after Phase 14 is accepted may Roadmap proceed to Phase 15 DevelopmentProject.

---

# Final architecture verdict

The next phase after Phase 13 is exactly:

```text
14. Assets / debts / obligations / net-worth model
```

The implementation-ready architecture should center on four independent authorities:

```text
Asset identity + lifecycle
Obligation/debt/receivable identity + lifecycle
Valuation/settlement history
Derived net-worth projection
```

integrated with, but never conflated with:

```text
Phase 12 OwnershipRecord/reference registries
Phase 13 Financial Ledger/account balances
Phase 10 Inventory
Phase 11 Equipment
```

The strongest reusable lesson from Phase 12 is that generic references require a real namespace/target authority, not arbitrary strings. The strongest reusable lesson from Phases 11–12 is that race-sensitive invariants must be enforced at the SQLite write boundary, not only by Kotlin prechecks.

**NEXT-PHASE ARCHITECTURE READY**

Implementation remains **BLOCKED until final accepted Phase 13**.
