# WORK-20260810-063 — Final Phase 14 Migration / Integrity Revalidation

Status: FINAL REVALIDATION — FAIL

Work ID: `WORK-20260810-063`
Worker: `CHAT-3`
Role: `FINAL PHASE 14 MIGRATION / INTEGRITY REVALIDATION`
Repository: `piotreksmaga-art/rpg-os-android`
Validated runtime SHA: `0ddae36008b13c6d3ac20cde3eb19d0e2859afd9`
Accepted Phase-13 baseline: `be10d7f1b6bf0f6a2cd0522b1dac577d0f398790`
Exact CI: GitHub Actions `#289`, run ID `31487248358`, head SHA `0ddae36008b13c6d3ac20cde3eb19d0e2859afd9`, `SUCCESS`

# PHASE 14 INTEGRITY REVALIDATION: FAIL

The exact candidate passes the migration/routing, SQLite reference/lifecycle, concurrency, scale, PRAGMA, legacy-preservation and cross-domain gates inspected below, but it has a release-blocking stable-identity/idempotent-replay defect in the typed `AssetLiabilityStore` API. Existing authoritative rows are recognized as an exact replay using incomplete payload comparisons. A caller can therefore reuse an existing `obligationUid`, `settlementUid` or `valuationUid` with semantically conflicting immutable fields and receive a successful return instead of a conflict, while canonical SQLite authority remains unchanged. In the obligation case the method returns the caller's conflicting object, producing an observable split between returned "committed" result and Source of Truth.

No runtime/schema/test fix is implemented by this audit.

---

## 1. Candidate identity / freshness

Fresh master immediately before revalidation resolved to exactly:

`0ddae36008b13c6d3ac20cde3eb19d0e2859afd9`

No later runtime WORK-061 commit existed. Validation therefore remained pinned to the requested candidate.

PASS for candidate identity/freshness.

## 2. CI #288 follow-up scope

The two commits after the previous Phase-14 runtime merge point are the Kotlin compilation fix and its forward merge. Comparing `19e55391bd335e94d4b8657bbb6ec875e45e5c0e` to the validated candidate shows exactly one changed file:

`app/src/test/java/com/rpgos/app/AssetLiabilityConcurrencyTest.kt`

No production schema/runtime/guard file changed in that range. Production blobs at the candidate remain the Phase-14 runtime implementation (`Phase14Migration.kt`, `Phase14Hardening.kt`, `AssetLiabilityStore.kt`, etc.).

Therefore the post-CI-#288 fix was test-syntax only as required.

PASS.

## 3. Exact CI

GitHub Actions run `31487248358`, run number `#289`, is attached to head SHA `0ddae36008b13c6d3ac20cde3eb19d0e2859afd9` and concluded `SUCCESS`.

The build job includes successful `Run JVM unit tests` and signed ALPHA build steps.

CI is evidence that the committed tests executed; it is not treated as proof of semantic/integrity correctness by itself.

PASS.

---

# MIGRATION / ROUTING REVALIDATION

## 4. CurrentSchema full chain

Production `CurrentSchema.ensure(saveDb, campaignId)` routes to `MigrationManager().ensureV14Hardening(...)`.

The chain is:

`ensureV14Hardening -> ensureV14ContractGuards -> ensureV14 -> ensureV13ContractGuards -> accepted earlier chain`.

This preserves the accepted V13.0/V13.1/V13.2 contract and earlier Phase 3–12 migrations.

PASS.

## 5. Clean bootstrap

`Phase14ProductionRoutingTest.bootstrapRoutesBundledCampaignThroughV14()` exercises actual `LocalGameStore.bootstrap()` and verifies:

- Phase-14 migration marker exists;
- eight expected Phase-14 tables exist;
- Phase-14 triggers are installed;
- `PRAGMA integrity_check = ok`;
- Phase-14 table-scoped `PRAGMA foreign_key_check(...)` returns no rows.

No asset/liability authority is synthesized during bootstrap.

PASS.

## 6. V13 -> V14

The production routing test constructs a V13 database through `ensureV13ContractGuards`, then selects/routes it through the production campaign path. V14 is installed additively.

`ensureV14()` begins by ensuring the accepted V13 contract and creates the Phase-14 schema transactionally before writing its marker.

PASS.

## 7. Repeated ensure / idempotency of migration

`repeatedEnsureIsIdempotentAndDoesNotRecreateAuthority()` runs `CurrentSchema.ensure` repeatedly and verifies one V14 marker and zero invented Asset/Obligation rows.

DDL uses `CREATE ... IF NOT EXISTS` and migration markers use `INSERT OR IGNORE`; guard installation is deterministic.

PASS for migration idempotency.

Important: this PASS does not cover the typed-record conflicting replay defect described in blocker section 25.

## 8. Reopen

`AssetLiabilityPersistenceTest.exactFractionalOwnershipScaleHistoryAndReopen()` closes/reopens a database containing 1001 valuation history rows and re-runs `CurrentSchema.ensure`. Count and derived net-worth result remain exact after reopen.

PASS.

## 9. Restore

`restoreRoutesV13ThroughV14WithoutLegacyAssetLiabilitySynthesis()` restores a V13 backup through `LocalGameStore.restoreBackup()` and verifies V14 installation while preserving legacy `character_finances` and creating zero canonical assets/obligations.

PASS.

## 10. Campaign switch A -> B -> A / isolation

Production routing covers selecting an alternate V13 campaign and upgrading it independently. Persistence tests create colliding stable asset UID strings in campaigns C and D and verify campaign-scoped counts remain independent.

All Phase-14 primary identities that are campaign-scoped include `campaign_id`; typed store queries scope by its fixed campaign ID.

PASS.

---

# LEGACY / IDENTITY / REFERENCE INTEGRITY

## 11. Legacy preservation / zero unsafe synthesis

The candidate does not auto-promote legacy `debt`, `property_value`, `investment_value`, Inventory labels or other aggregate hints into canonical Phase-14 records.

Tests verify legacy values remain present while `asset_records` and `obligation_records` remain empty after migration.

PASS.

## 12. Generic asset reference integrity

Phase-14 asset creation uses the Phase-12 ownership asset-kind/asset registry. `asset_records` has a composite FK to the registry and `trg_p14_asset_insert` additionally requires an ACTIVE AssetKindDefinition, ACTIVE ownership asset kind and ACTIVE campaign-scoped registry target.

The target identity remains `(campaign, assetKindUid, assetUid)` and `ITEM_INSTANCE` is excluded from duplicate Phase-14 AssetRecord identity.

PASS.

## 13. Generic party reference integrity

Obligor/beneficiary references are composite FKs to `ownership_party_registry`, and `trg_p14_obligation_insert` requires both references to be ACTIVE in the same campaign at insert time.

`trg_p14_obligation_party_retire` protects the reverse lifecycle direction for active obligations.

PASS.

## 14. Currency / Phase-13 financial reference integrity

Valuation/principal money is SQLite INTEGER / Kotlin Long and uses Phase-13 `currency_definitions`.

Settlement `financial_transaction_uid` is a campaign-scoped FK to immutable Phase-13 ledger history. The settlement insert guard additionally checks PAYMENT evidence against the obligation contract, including matching campaign/currency/amount semantics implemented by the candidate. Hardening adds unique financial-transaction evidence usage.

PASS for reference integrity.

## 15. StatePatch blocking

`SourceOfTruthRegistry.TYPED_ONLY_TABLES` includes:

- asset definitions/records/valuations;
- obligation definitions/records/status/settlements;
- encumbrances;
- accepted finance authority.

Generic StatePatch cannot write canonical Phase-14 authority.

PASS.

---

# TEMPORAL / HISTORY / VALUE INTEGRITY

## 16. Asset lifecycle

Asset identity is append-preserved. Direct DELETE is blocked. The only store lifecycle transition is an ACTIVE-row CAS UPDATE carrying `record_version`, and DB triggers reject illegal identity/history mutations, terminal transitions conflicting with active ownership, later valuation or active encumbrance, and registry retirement while the canonical asset is active.

PASS.

## 17. Valuation history

`asset_valuations` is append-only: UPDATE and DELETE are blocked. A uniqueness constraint protects one valuation basis at the same effective order, and hardening rejects backdating/conflicting current-basis insertion behind an equal or later valuation. Currency and asset lifecycle are revalidated by SQLite INSERT guards.

The authoritative amount is INTEGER, and derived arithmetic uses checked Long operations.

PASS for DB history/authority exclusivity.

## 18. Obligation settlement / over-settlement

Settlement rows are append-only. SQLite settlement INSERT guards validate obligation existence/lifecycle, temporal order, allowed amount and aggregate outstanding; hardening blocks backdating and duplicate reuse of linked Finance/Ownership evidence.

`P14-RACE-02` proves two 80-unit settlements against principal 100 cannot both commit on independent SQLite connections.

PASS for over-settlement and DB settlement-history integrity.

## 19. Obligation terminal lifecycle

Status is append history rather than mutation of `obligation_records`. SQLite status guard serializes legal transitions and prevents competing terminal events from both becoming valid history. Settling an obligation with a still-active encumbrance is blocked until release.

`P14-RACE-05` confirms competing DEFAULTED/CANCELLED terminal events yield exactly one winner.

PASS.

## 20. Encumbrance lifecycle

Encumbrance creation is FK/reference guarded and tied to a live obligation. Release is an authoritative conditional SQLite UPDATE (`WHERE released_order IS NULL`) and version increments on the single legal release transition. DB update guards prevent arbitrary mutation.

`P14-RACE-06` proves two concurrent release attempts yield one winner and exactly one version-2 released row.

PASS.

## 21. Fractional ownership / derived net worth

Net worth reads current/as-of Phase-12 `ownership_records`, filters on the dedicated economic ownership type, and applies exact fixed-scale share arithmetic. It does not infer title from valuation.

The persistence test proves 1/2 ownership of a value 3000 at the queried time contributes exactly 1500.

PASS.

## 22. Derived net worth / no alternate authority

`netWorth(...)` derives:

owned attributable asset value + Phase-13 cash + receivables - outstanding liabilities.

It uses exact `Math.addExact` / `Math.subtractExact` paths and reports missing asset valuations as an incomplete count rather than silently treating them as known zero.

The tests verify no persisted `net_worth` authority table exists.

PASS for the implemented derived projection contract.

## 23. Scale / no authoritative truncation

The persistence fixture writes 1001 valuation rows. Authoritative count and derived net-worth query remain complete before and after reopen. No authoritative `LIMIT 1000` is used in the valuation history aggregation exercised by this fixture.

PASS for the mandatory >1000 record gate exercised by the candidate.

## 24. Phase 3–13 preservation

V14 is additive above `ensureV13ContractGuards`. No Phase 3–13 authoritative table is destructively migrated by Phase-14 migration. Phase-12 ownership and Phase-13 finance are referenced, not replaced. Existing Phase-13 finance JVM tests run in the same successful exact CI suite.

No Phase 3–13 regression blocker was reproduced.

PASS.

---

# RELEASE BLOCKER

## 25. FAIL — stable identity / conflicting retry is not exact

### Violated invariant

Stable authoritative UID may identify only one immutable semantic fact. An exact retry may return the existing canonical result, but reuse of the same UID with a different immutable payload must fail loudly.

This is required by WORK-059 architecture, WORK-063 integrity plan, WORK-062 semantic oracle and WORK-065 adversarial matrix.

### Exact runtime path

`app/src/main/java/com/rpgos/app/AssetLiabilityStore.kt`

Affected paths:

- `createObligation()` -> `obligationMatches()`;
- `settle()` -> `settlementMatches()`;
- `recordValuation()` -> `valuationMatches()`.

### Minimal reproducer — obligation

1. Initialize campaign C, owners A/B and currency CUR.
2. Call:

```text
createObligation(
  obligationUid = "OBL-X",
  obligor = A,
  beneficiary = B,
  type/class = DEBT,
  createdOrder = 1,
  provenance = "same",
  currencyUid = CUR,
  principalMinor = 100
)
```

3. Call `createObligation()` again with the same fields tested by `obligationMatches()` but change only:

```text
principalMinor = 200
```

### Actual

`createObligation()` sees the existing UID and evaluates `obligationMatches(o)`.

`obligationMatches()` compares only:

- campaign / obligation UID;
- type/class;
- obligor and beneficiary refs;
- created order;
- provenance.

It does **not** compare immutable fields including `currencyUid`, `principalMinor`, `assetRef`, `dueOrder`, `validUntilOrder`, `sourceEventUid`, `sourceContractUid`, version or metadata.

Therefore the second call is accepted as a replay. `createObligation()` returns the caller's second `ObligationRecord` object (principal 200), while the canonical SQLite row remains principal 100.

Observable illegal split:

```text
returned result principal = 200
canonical Source of Truth principal = 100
```

No exception/conflict is raised.

### Expected

Conflicting reuse of `OBL-X` must reject atomically, or the API must return the actual already-committed canonical row only when the **entire immutable semantic payload** is exactly equal.

### Additional affected identity paths

The same defect family exists in:

`settlementMatches()` — it omits at least amount, financialTransactionUid, ownershipOperationUid and sourceEventUid from replay equality.

`valuationMatches()` — it omits at least validUntilOrder, sourceEventUid and confidence from replay equality.

Thus the blocker is systemic typed-store replay equality, not an isolated obligation field.

### Why SQLite UNIQUE/FK does not save this path

SQLite PK/UNIQUE would reject a second conflicting INSERT, but the store does not attempt that INSERT. It short-circuits before the write when its incomplete match predicate returns true. Consequently DB constraints/guards never get a chance to reject the conflict.

### Minimal correction scope

No correction is implemented here.

The minimal runtime correction scope is limited to Phase-14 typed idempotent-replay handling:

1. compare the complete immutable canonical payload for `ObligationRecord`, `AssetValuation` and `ObligationSettlement` before treating an existing UID as replay;
2. on exact match return/read the persisted canonical row (or explicit ALREADY_COMMITTED result), not blindly return caller input;
3. conflicting payload must throw/reject;
4. add exact/conflicting retry tests for all stable Phase-14 record identities, including concurrent variants where applicable.

No schema redesign is required for this blocker.

Result: **RELEASE BLOCKER / FAIL**.

---

# P14-RACE RESULTS

## P14-RACE-01 — competing identity creation

Two independent SQLite connections concurrently create the same exact Asset identity. Final canonical row count is one; at least one caller succeeds. Same-payload replay/constraint behavior does not produce duplicate authority.

PASS for the defined race.

Note: this does not cure the different-payload replay blocker in section 25.

## P14-RACE-02 — over-settlement

Two concurrent 80 settlements against 100 principal: one succeeds, one fails, outstanding is 20, one settlement row remains.

PASS.

## P14-RACE-03 — valuation authority fork

Two concurrent MARKET valuations for the same asset/currency/effective order with different values: one succeeds, one fails, one valuation row remains.

PASS.

## P14-RACE-04 — party retirement vs obligation creation

Owner retirement races new obligation creation. Exactly one succeeds and the final state is coherent: either active party + obligation, or retired party + no obligation.

PASS.

## P14-RACE-05 — competing terminal lifecycle

DEFAULTED and CANCELLED race at the same effective order. Exactly one succeeds; status history contains initial ACTIVE plus one terminal event.

PASS.

## P14-RACE-06 — CAS encumbrance release

Two concurrent release attempts: one succeeds, one fails; one released row remains at record version 2.

PASS.

All six fixtures use separate SQLite connections and a synchronization barrier, so they exercise actual competing writers rather than sequential service calls.

---

# SQLITE INTEGRITY RESULTS

Exact candidate JVM tests executed under GitHub Actions #289 and include repeated direct checks:

```text
PRAGMA integrity_check
=> ok
```

and:

```text
PRAGMA foreign_key_check
=> zero violations
```

`Phase14ProductionRoutingTest` additionally performs table-scoped foreign-key checks for the authoritative Phase-14 tables after bootstrap/upgrade/restore. `AssetLiabilityPersistenceTest` and all P14 race fixtures execute integrity and FK checks on their resulting databases.

Therefore:

- SQLite structural integrity: **PASS / ok**
- Phase-14 FK check: **PASS / zero violations**

These clean PRAGMA results do not negate the typed-store conflicting-replay blocker, because that defect returns success before a conflicting SQL INSERT is attempted.

---

# FINAL VERDICT

`PHASE 14 INTEGRITY REVALIDATION: FAIL`

Validated runtime SHA:
`0ddae36008b13c6d3ac20cde3eb19d0e2859afd9`

Exact CI:
GitHub Actions `#289`, run ID `31487248358`, `SUCCESS`

Release blocker:
incomplete immutable-payload equality for Phase-14 stable-UID replay handling in `AssetLiabilityStore`, reproducible with conflicting `obligationUid` principal and also affecting settlement/valuation replay semantics.

Phase 15 was not started.
