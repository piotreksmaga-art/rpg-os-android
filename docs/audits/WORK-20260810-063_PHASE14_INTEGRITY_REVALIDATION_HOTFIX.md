# WORK-20260810-063 — Final Phase 14 Integrity Revalidation — Hotfix

Status: FINAL HOTFIX REVALIDATION — FAIL

Work ID: `WORK-20260810-063`
Worker: `CHAT-3`
Role: `FINAL PHASE 14 MIGRATION / INTEGRITY REVALIDATION`
Repository: `piotreksmaga-art/rpg-os-android`
Validated runtime SHA: `cace545627b2de41295bacb9e70a0e017a7b49a2`
Accepted Phase-13 baseline: `be10d7f1b6bf0f6a2cd0522b1dac577d0f398790`
Exact CI: GitHub Actions `#293`, run ID `31488698595`, head SHA `cace545627b2de41295bacb9e70a0e017a7b49a2`, `SUCCESS`
Allowed write scope: this report only.

# PHASE 14 INTEGRITY REVALIDATION: FAIL

The receivable-normalization hotfix correctly repairs the prior WORK-062/065 net-worth double-count blocker without changing Phase-14 schema, canonical asset/history authority or SQLite concurrency guards. `ASSET_KIND_RECEIVABLE` remains a legal active stable asset kind; the hotfix changes only derived projection policy so receivable AssetRecords are excluded from `assetsMinor`, while beneficiary monetary claims continue to contribute through campaign-scoped `ObligationRecord` outstanding value.

However, the candidate still contains the independent stable-identity/idempotent-replay release blocker previously found by WORK-063. The hotfix delta does not touch the replay-match helpers in `AssetLiabilityStore`. Existing `obligationUid`, `valuationUid` and `settlementUid` rows can still be accepted as exact replay using incomplete immutable-payload comparison. In the obligation path a conflicting caller can receive a successful noncanonical object whose principal differs from the persisted Source of Truth.

No runtime/schema/test correction was implemented by this audit. Phase 15 was not started.

---

## 1. Candidate freshness — PASS

Fresh master immediately before validation resolved exactly to:

`cace545627b2de41295bacb9e70a0e017a7b49a2`

No later WORK-061 runtime existed. Validation therefore remained pinned to the requested candidate.

Result: **PASS**.

## 2. Exact CI — PASS

GitHub Actions run `31488698595`, run number `#293`, is attached to exact head SHA `cace545627b2de41295bacb9e70a0e017a7b49a2` and concluded `SUCCESS`.

The job completed `Validate project`, `Run JVM unit tests` and signed ALPHA APK build successfully.

Result: **PASS**.

---

# HOTFIX-SPECIFIC REVALIDATION

## 3. Hotfix scope — PASS

Comparison from prior candidate `0ddae36008b13c6d3ac20cde3eb19d0e2859afd9` to the hotfix candidate shows production modification only in:

`app/src/main/java/com/rpgos/app/AssetLiabilityStore.kt`

plus one regression-test change in:

`app/src/test/java/com/rpgos/app/AssetLiabilityPersistenceTest.kt`

and report-only audit additions.

The production change is confined to `netWorth()` projection comments/query policy: the ownership/asset query adds `r.asset_kind_uid<>ASSET_KIND_RECEIVABLE`.

No Phase-14 migration/schema/hardening trigger file changed. `Phase14Hardening.kt` retains the same blob and SQLite guard set as before.

Result: **PASS**.

## 4. ASSET_KIND_RECEIVABLE remains legal canonical stable kind — PASS

`Phase14Hardening.ensureV14Hardening()` still registers `ASSET_KIND_RECEIVABLE` in both:

- `ownership_asset_kinds` as ACTIVE generic asset namespace;
- `asset_kind_definitions` as ACTIVE Phase-14 core asset kind.

The hotfix does not delete, deprecate or special-case its canonical schema identity.

A receivable AssetRecord can still be created, valued, owned and retained historically under normal Phase-14 asset/reference guards.

Result: **PASS**.

## 5. Projection-only exclusion preserves canonical history — PASS

The hotfix excludes RECEIVABLE only from the first `netWorth()` owned-asset aggregation query.

It does not:

- delete AssetRecord rows;
- delete AssetValuation rows;
- rewrite OwnershipRecord history;
- change asset lifecycle;
- mutate AssetKindDefinition;
- remove generic-reference validity;
- synthesize or erase obligation history.

The regression fixture explicitly creates a receivable AssetRecord, valuation and Phase-12 economic OwnershipRecord and then creates a linked obligation. Those canonical records remain present; only `assetsMinor` contribution is normalized to zero for that asset kind.

Result: **PASS**.

## 6. Beneficiary obligation receivable remains exact and campaign-scoped — PASS

`netWorth()` still queries `obligation_records` with fixed `campaign_id=?`, exact `currency_uid=?` and deterministic as-of filtering.

For each live obligation it derives outstanding through `outstandingMinor()`, which reads exact SQLite INTEGER / Kotlin Long principal and append-preserved settlements scoped to the store campaign.

Beneficiary matching still uses exact generic Phase-12 party identity `(ownerKindUid, ownerUid)`; no display-name matching or cross-campaign inference is introduced.

Result: **PASS**.

## 7. Anti-double-count normalization — PASS

The exact regression fixture creates one economic claim represented simultaneously as:

- `ASSET_KIND_RECEIVABLE` AssetRecord valued at 100 CUR and economically owned by B;
- monetary ObligationRecord from A to beneficiary B with principal 100 CUR and asset reference to that receivable.

Expected and exact test oracle after hotfix:

```text
assetsMinor      = 0
receivablesMinor = 100
netWorthMinor    = 100
```

The same claim therefore contributes exactly once.

Result: **PASS**.

## 8. No silent undercount of independent non-receivable assets — PASS

The exclusion predicate is exact on the stable kind UID `ASSET_KIND_RECEIVABLE`; it does not exclude PROPERTY, COMPANY, BUSINESS, SECURITY or other asset kinds.

Existing persistence tests still prove ordinary PROPERTY/COMPANY assets contribute through `assetsMinor`, including fractional ownership and >1000 valuation history.

The policy intentionally says monetary receivables are normalized through ObligationRecord only. Therefore omission of standalone receivable AssetRecord valuation from assetsMinor is explicit policy, not accidental broad asset undercount.

Result: **PASS for the implemented normalization contract**.

## 9. Net worth remains derived-only — PASS

`netWorth()` remains an on-demand projection over Phase-12 ownership, Phase-13 cash, Phase-14 valuations and obligations.

No persisted authoritative `net_worth` table is introduced. Existing test asserts zero tables matching `%net_worth%`.

Result: **PASS**.

---

# FULL WORK-063 PLAN REVALIDATION

## 10. Clean bootstrap — PASS

Production `LocalGameStore.bootstrap()` continues routing through `CurrentSchema.ensure` to V14 hardening. Hotfix did not modify migration/bootstrap code.

Exact CI #293 reran the unchanged production-routing tests.

Result: **PASS**.

## 11. V13 -> V14 — PASS

Migration path remains additive:

`CurrentSchema.ensure -> ensureV14Hardening -> ensureV14ContractGuards -> ensureV14 -> ensureV13ContractGuards -> earlier accepted chain`.

No hotfix schema change exists.

Result: **PASS**.

## 12. CurrentSchema routing — PASS

Unchanged from the prior validated runtime. CurrentSchema still terminates at `ensureV14Hardening`.

Result: **PASS**.

## 13. Repeated ensure / migration idempotency — PASS

Unchanged migration uses deterministic `CREATE ... IF NOT EXISTS`, guard reinstall and migration markers without canonical asset/obligation synthesis.

Existing production-routing test reran under exact CI #293.

Result: **PASS**.

## 14. Reopen — PASS

Persistence suite still closes/reopens the 1001-valuation fixture, reruns CurrentSchema and obtains identical count and fractional net-worth value.

Result: **PASS**.

## 15. Restore — PASS

Restore routing and zero-synthesis fixture are unchanged and reran in exact JVM tests.

Result: **PASS**.

## 16. Campaign switch / campaign isolation — PASS

Hotfix adds no cache/global state. All modified net-worth queries remain explicitly scoped by the AssetLiabilityStore campaign ID.

Same asset UID may still exist independently across campaigns without leakage.

Result: **PASS**.

## 17. Legacy zero synthesis — PASS

No migration logic changed. Legacy `debt`, `property_value`, `investment_value` and Inventory evidence remain preserved without automatic canonical Asset/Obligation creation.

Result: **PASS**.

## 18. Generic reference integrity — PASS

No FK/trigger/reference code changed. Receivable AssetRecords still pass the same active-kind and Phase-12 generic asset registry authority as all Phase-14 generic assets.

Obligor/beneficiary references remain campaign-scoped Phase-12 party refs.

Result: **PASS**.

## 19. Valuation history — PASS

No valuation schema or triggers changed. Asset valuation history remains immutable/append-only with same-basis/effective-order protection and asset/currency lifecycle validation at SQLite authority.

Result: **PASS**.

## 20. Obligation settlement — PASS

No settlement runtime/schema/trigger change is part of the receivable hotfix.

SQLite authority still protects aggregate outstanding, temporal ordering, terminal status and evidence linkage.

Result: **PASS**.

## 21. Fractional ownership — PASS

Non-receivable assets continue using Phase-12 exact fixed-scale shares and Phase-14 checked valuation attribution. The existing 50% / 1001-valuation reopen fixture remains green in exact CI #293.

Result: **PASS**.

## 22. Payment / Phase-13 reference linkage — PASS

The hotfix does not modify settlement/payment integration. PAYMENT settlement still requires authoritative Phase-13 transaction evidence; forged/nonexistent transaction references remain rejected.

Result: **PASS**.

## 23. StatePatch blocking — PASS

No SourceOfTruthRegistry change occurred. Canonical Phase-14 tables remain typed-only and unavailable to generic StatePatch mutation.

Result: **PASS**.

## 24. >1000 records / no authoritative truncation — PASS

Existing persistence fixture still commits 1001 valuation rows, verifies exact count, derives the expected fractional value and repeats after reopen.

The modified netWorth ownership/obligation queries do not add presentation/context limits.

Result: **PASS**.

## 25. Phase 3–13 regression — PASS

The hotfix changes only a Phase-14 derived projection query plus its test. It does not alter accepted Phase 3–13 schema, stores or migrations.

Exact CI #293 reruns the complete JVM unit-test suite successfully.

No Phase 3–13 blocker was reproduced.

Result: **PASS**.

---

# P14 CONCURRENCY REVALIDATION

The receivable hotfix does not modify `AssetLiabilityConcurrencyTest.kt`, Phase14 migration triggers or hardening guards. Exact CI #293 reran the unchanged race suite.

## P14-RACE-01 — competing identity creation — PASS

Same composite canonical asset identity still resolves to one canonical row under concurrent connections.

## P14-RACE-02 — over-settlement — PASS

Two competing 80 settlements against principal 100 cannot both commit; SQLite settlement authority remains unchanged.

## P14-RACE-03 — valuation authority fork — PASS

Same-basis valuation fork remains blocked by uniqueness/order guards.

## P14-RACE-04 — party retirement vs obligation creation — PASS

SQLite reference/lifecycle serialization remains unchanged.

## P14-RACE-05 — competing terminal lifecycle — PASS

Conflicting terminal obligation statuses cannot both commit.

## P14-RACE-06 — CAS encumbrance release — PASS

Conditional SQLite UPDATE still permits exactly one release transition.

All six race gates: **PASS**.

---

# SQLITE INTEGRITY / FK RESULT

Persistence and concurrency fixtures call:

```sql
PRAGMA integrity_check;
```

and require exact result:

```text
ok
```

They also call:

```sql
PRAGMA foreign_key_check;
```

and require zero rows / zero violations.

The new receivable normalization regression itself calls the same integrity/FK helper after creating its canonical AssetRecord, valuation, OwnershipRecord and ObligationRecord. Exact CI #293 passed the JVM test suite.

Therefore for the tested authoritative Phase-14 scope:

```text
PRAGMA integrity_check = ok
PRAGMA foreign_key_check = 0 violations
```

Result: **PASS**.

---

# RELEASE BLOCKER

## 26. FAIL — stable identity / conflicting typed-store replay remains incomplete

### Violated invariant

A stable authoritative UID must identify one immutable semantic fact. Only an exact immutable-payload retry may be accepted as replay. Reuse of the same UID with a different immutable payload must fail loudly and must never return a caller-provided noncanonical object as though it were the committed Source of Truth.

### Exact runtime path

`app/src/main/java/com/rpgos/app/AssetLiabilityStore.kt`

Affected helpers remain unchanged by the hotfix:

- `createObligation()` -> `obligationMatches()`;
- `recordValuation()` -> `valuationMatches()`;
- `settle()` -> `settlementMatches()`.

### Minimal reproducer — obligation

1. Initialize campaign C with parties A/B and currency CUR.
2. Create:

```text
ObligationRecord(
  campaign = C,
  obligationUid = OBL-X,
  type/class = DEBT,
  obligor = A,
  beneficiary = B,
  createdOrder = 1,
  provenance = same,
  currencyUid = CUR,
  principalMinor = 100
)
```

3. Call `createObligation()` again with the same UID and all fields checked by `obligationMatches()` unchanged, but use:

```text
principalMinor = 200
```

### Actual

`createObligation()` sees an existing UID and calls `obligationMatches()`.

That predicate compares only:

- campaign / obligation UID;
- obligation type/class;
- obligor kind+UID;
- beneficiary kind+UID;
- created order;
- provenance.

It does not compare immutable fields including:

- `currencyUid`;
- `principalMinor`;
- optional asset ref;
- due order;
- valid-until order;
- source event;
- source contract;
- record version;
- metadata.

Therefore the second call is accepted as replay. The function returns the caller's second `ObligationRecord` object while canonical SQLite retains the first row.

Observable split:

```text
returned principal = 200
canonical principal = 100
```

No second INSERT occurs, so PK/FK/triggers do not get an opportunity to reject the conflict.

### Additional affected paths

`valuationMatches()` still omits immutable payload fields including `validUntilOrder`, `sourceEventUid` and confidence.

`settlementMatches()` still omits immutable payload fields including amount, financialTransactionUid, ownershipOperationUid and sourceEventUid.

### Why the hotfix does not repair this

The exact `0ddae360... -> cace5456...` runtime delta changes only the `netWorth()` owned-asset query/comment in `AssetLiabilityStore.kt`. The replay helper predicates are byte-for-byte functionally unchanged.

### Expected

Conflicting stable-UID reuse must fail atomically. Exact retry may succeed only when the complete immutable canonical payload matches, and the success result must reflect the persisted canonical row/effect.

### Minimal correction scope

No correction is implemented here.

Minimal Phase-14-only scope:

1. make `obligationMatches`, `valuationMatches` and `settlementMatches` compare the complete immutable canonical payload;
2. distinguish exact replay from semantic conflict deterministically;
3. return/read the persisted canonical row or explicit ALREADY_COMMITTED result on exact replay rather than blindly returning caller payload;
4. add regression tests for omitted-field conflicting retries, including principal, currency/asset refs, valuation validity/confidence/evidence and settlement amount/evidence links;
5. retain existing SQLite guards/concurrency semantics unchanged.

Result: **FAIL / RELEASE BLOCKER**.

---

# FINAL VERDICT

# PHASE 14 INTEGRITY REVALIDATION: FAIL

Validated SHA:

`cace545627b2de41295bacb9e70a0e017a7b49a2`

Exact CI:

`GitHub Actions #293 / run ID 31488698595 / SUCCESS`

Mandatory race gates:

```text
P14-RACE-01 PASS
P14-RACE-02 PASS
P14-RACE-03 PASS
P14-RACE-04 PASS
P14-RACE-05 PASS
P14-RACE-06 PASS
```

SQLite verification in exact candidate tests:

```text
PRAGMA integrity_check = ok
PRAGMA foreign_key_check = zero violations in tested authoritative Phase-14 scope
```

Hotfix-specific receivable normalization: **PASS**.

Overall release result: **FAIL** because the independent stable-UID conflicting-replay defect remains present in the typed Phase-14 store API.

No runtime correction was implemented. Phase 15 was not started.
