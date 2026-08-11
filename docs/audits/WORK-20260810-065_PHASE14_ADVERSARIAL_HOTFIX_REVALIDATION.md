# WORK-20260810-065 — Phase 14 Adversarial Hotfix Revalidation

Status: FINAL ADVERSARIAL HOTFIX REVALIDATION — FAIL

Work ID: `WORK-20260810-065`
Worker: `CHAT-5`
Role: `FINAL PHASE 14 ADVERSARIAL VALIDATION — HOTFIX`
Repository: `piotreksmaga-art/rpg-os-android`
Validated runtime SHA: `cace545627b2de41295bacb9e70a0e017a7b49a2`
Exact CI: GitHub Actions `#293`, run ID `31488698595`, head SHA `cace545627b2de41295bacb9e70a0e017a7b49a2`, `SUCCESS`
Accepted Phase-13 baseline: `be10d7f1b6bf0f6a2cd0522b1dac577d0f398790`
Allowed write scope: this report only.

# PHASE 14 ADVERSARIAL VALIDATION: FAIL

The hotfix fixes the previously reported receivable double-count case, and the mandatory Phase-14 concurrency gates remain intact. However, the replacement normalization is over-broad: every owned/valued `ASSET_KIND_RECEIVABLE` AssetRecord is globally excluded from `assetsMinor`, even when no ObligationRecord represents that economic claim. A valid independent receivable asset can therefore contribute zero to net worth.

No runtime/schema/test correction was implemented by CHAT-5. Phase 15 was not started.

---

## 1. Candidate freshness and exact CI

Fresh master was checked before validation. The newest `WORK-20260810-061` runtime commit is exactly:

`cace545627b2de41295bacb9e70a0e017a7b49a2`

No later WORK-061 runtime candidate was present.

Exact GitHub Actions run `31488698595`, run number `293`, is completed with conclusion `SUCCESS` and exact head SHA `cace545627b2de41295bacb9e70a0e017a7b49a2`.

The delta from the previous candidate `0ddae360...` to this hotfix changes Phase-14 production behavior only in `AssetLiabilityStore.netWorth()` plus adds a persistence regression test. The concurrency suite, Phase14Migration, Phase14Hardening and race-sensitive SQLite guards are unchanged.

---

## 2. Previous receivable double-count blocker — PASS

Required reproducer:

```text
B owns RECEIVABLE AssetRecord R valued at 100 CUR
+
Obligation O: A owes B 100 CUR
and R/O represent the same economic claim
```

Hotfix behavior:

- `AssetLiabilityStore.netWorth()` excludes `ASSET_KIND_RECEIVABLE` from the generic owned-asset aggregation;
- Obligation beneficiary outstanding remains included in `receivablesMinor`;
- regression test `receivableAssetAndBeneficiaryObligationContributeExactlyOnce()` creates exactly this configuration and asserts:

```text
assetsMinor      = 0
receivablesMinor = 100
netWorthMinor    = 100
missingValuationCount = 0
```

Therefore the old `100 + 100 = 200` double-count path is closed.

Result: **PASS**.

---

## 3. Reverse undercount attack — FAIL / RELEASE BLOCKER

### Violated invariant

A valid independent owned economic asset with a valid valuation must not disappear from derived net worth merely because its generic asset kind is `RECEIVABLE`.

WORK-059 explicitly allows generic AssetKind categories/classes including `RECEIVABLE`, and its derived net-worth formula is equivalent to:

```text
SUM(value of P-owned asset shares under valuation policy)
+ valid receivables
- outstanding liabilities
```

The anti-double-count rule requires normalization of duplicate representations, not unconditional deletion of one entire valid asset category from wealth attribution.

### Minimal reproducer

Campaign `C`, party `B`, currency `CUR`:

1. Create canonical Phase-14 AssetRecord `R` with `assetKindUid = RPGOS-ASSET-KIND:RECEIVABLE`.
2. Record valid valuation `R = 100 CUR`.
3. Give B 100% economic ownership through Phase-12 OwnershipRecord.
4. Do **not** create any ObligationRecord for this claim.
5. Call `AssetLiabilityStore.netWorth(B, "CUR", asOf)`.

This represents an independent receivable/instrument/claim asset whose canonical economic representation is the AssetRecord itself and which is not duplicated by an Obligation beneficiary view.

### Exact runtime path

`AssetLiabilityStore.kt::netWorth()` now filters the owned-asset query with:

```sql
AND r.asset_kind_uid <> ASSET_KIND_RECEIVABLE
```

Therefore R never reaches `valuationAt()` and never contributes to `assetsMinor`.

The second obligation loop has no matching ObligationRecord, so `receivablesMinor` also remains zero.

### Expected

```text
assetsMinor or normalized receivable contribution = 100
netWorthMinor = 100
```

The exact presentation bucket may be an implementation choice, but the valid economic claim must contribute exactly once.

### Actual

```text
assetsMinor      = 0
receivablesMinor = 0
netWorthMinor    = 0
```

The independent valid receivable asset is globally ignored.

### Why the current regression test is insufficient

The new test validates only the duplicate-representation case where both AssetRecord and ObligationRecord exist. It proves anti-double-counting but does not test a legitimate RECEIVABLE AssetRecord with no corresponding ObligationRecord.

### Minimal correction scope

Phase 14 only. Do not redesign Phase 12 Ownership or Phase 13 Finance.

The normalization must be claim-aware rather than asset-kind-wide. Minimum acceptable options include:

1. add a stable canonical claim/link identity between receivable AssetRecord and ObligationRecord and exclude only linked duplicate representations;
2. define a strict contract that every monetary `ASSET_KIND_RECEIVABLE` must reference one ObligationRecord, enforce that at the authoritative SQLite boundary, and derive its wealth contribution through that obligation;
3. if independent receivable assets are valid, include unlinked RECEIVABLE assets in assets/net-worth and suppress only duplicated linked claims.

Required regression pair:

- linked RECEIVABLE asset + matching Obligation = total contribution exactly 100;
- independent RECEIVABLE asset valued 100 with no Obligation = total contribution exactly 100.

Result: **FAIL / RELEASE BLOCKER**.

---

## 4. Mandatory concurrency gates

The hotfix does not modify concurrency tests or race-sensitive production write guards. The previously validated real multi-connection tests remain in exact runtime history and exact CI #293 runs the JVM unit suite.

### P14-RACE-01 — PASS

Competing same asset identity creation remains guarded by canonical composite identity/SQLite serialization.

### P14-RACE-02 — PASS

Concurrent over-settlement remains guarded at the SQLite settlement INSERT boundary; aggregate settlement cannot exceed principal.

### P14-RACE-03 — PASS

Same-basis valuation authority fork remains blocked by unique/order guards.

### P14-RACE-04 — PASS

Party retirement vs new obligation remains protected by SQLite lifecycle/reference guards.

### P14-RACE-05 — PASS

Competing terminal obligation status events remain serialized to one coherent outcome.

### P14-RACE-06 — PASS

Encumbrance release remains a single conditional/CAS transition; two callers cannot both release the same active row.

All six race fixtures continue to use separate SQLite connections/callers, separate store instances, two executor threads and latch synchronization. Kotlin-only precheck is not the authoritative protection.

---

## 5. Identity / reference / campaign attacks — PASS

No hotfix change touches the accepted Phase-14 identity/reference contract. Duplicate UID, semantic conflicting retry, unresolved/wrong-campaign generic references, inactive parties/currency/assets, cross-kind and cross-campaign identity isolation remain guarded by the existing schema/registry/FK/trigger boundaries.

Result: **PASS**.

---

## 6. Valuation / arithmetic / stale valuation attacks — PASS

No monetary representation change was introduced. Valuation remains SQLite INTEGER / Kotlin Long exact minor units with checked ownership-share attribution. Valuation history remains immutable and append ordered; same-basis forks/backdating remain guarded. The hotfix changes only which asset kind reaches net-worth aggregation.

Result: **PASS**, except for the undercount projection bug described above.

---

## 7. Obligation / settlement / lifecycle attacks — PASS

No Phase-14 obligation, settlement, terminal lifecycle, encumbrance or write-boundary guard changed in the hotfix. Existing over-settlement, terminal-state, PAYMENT evidence, settlement order, asset lifecycle, party lifecycle and CAS release protections remain in place.

Result: **PASS**.

---

## 8. Direct SQL / history / StatePatch — PASS

Append-only/immutable Phase-14 tables remain protected by SQLite triggers. Generic StatePatch typed-only protection remains unchanged. The net-worth bug is a derived projection error, not an authoritative-history mutation bypass.

Result: **PASS**.

---

## 9. Cross-domain separation — PASS

The runtime still preserves:

```text
Asset/Liability
!= OwnershipRecord
!= Inventory
!= Equipment
!= Financial Ledger
```

Asset creation does not create title; valuation is not ownership evidence; payment does not transfer ownership; ownership does not fabricate payment; possession/equipment do not fabricate assets/liabilities; legacy aggregates do not create canonical history.

Result: **PASS**.

---

## 10. Legacy / scale / reopen / restore / campaign isolation — PASS

The hotfix does not change migration/schema/routing. Existing Phase-14 evidence remains:

- zero unsafe legacy synthesis;
- campaign-scoped identities and no cross-campaign leakage;
- >1000 valuation/history completeness without authoritative bounded-reader truncation;
- reopen/restore/latest-schema routing preservation;
- integrity fixtures executing `PRAGMA integrity_check` and `PRAGMA foreign_key_check`.

Exact CI #293 runs the unchanged test suite plus the new receivable regression and succeeds.

Result: **PASS**.

---

## 11. SQLite integrity

Required integrity fixtures remain unchanged and are exercised by the JVM suite:

```sql
PRAGMA integrity_check;
-- ok

PRAGMA foreign_key_check;
-- zero rows
```

No schema change was introduced by the receivable hotfix.

Result: **PASS**.

---

# FINAL VERDICT

# PHASE 14 ADVERSARIAL VALIDATION: FAIL

Validated runtime SHA:

`cace545627b2de41295bacb9e70a0e017a7b49a2`

Exact CI:

GitHub Actions `#293`, run ID `31488698595`, `SUCCESS`.

Mandatory race gates:

```text
P14-RACE-01 PASS
P14-RACE-02 PASS
P14-RACE-03 PASS
P14-RACE-04 PASS
P14-RACE-05 PASS
P14-RACE-06 PASS
```

Receivable anti-double-count attack: **PASS**.

Reverse undercount/regression attack: **FAIL / RELEASE BLOCKER**.

The candidate must not be accepted as final Phase 14 while a valid independent `ASSET_KIND_RECEIVABLE` economic asset can be omitted entirely from net worth.

Phase 15 remains blocked and was not started.
