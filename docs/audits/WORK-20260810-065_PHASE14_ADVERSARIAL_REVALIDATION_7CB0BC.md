# WORK-20260810-065 — Final Phase 14 Adversarial Revalidation

Status: FINAL ADVERSARIAL VALIDATION — FAIL

Work ID: `WORK-20260810-065`
Worker: `CHAT-5`
Role: `FINAL PHASE 14 ADVERSARIAL VALIDATION`
Repository: `piotreksmaga-art/rpg-os-android`
Validated runtime SHA: `7cb0bcdbbb823721fc9e6d3ef1feeb8ffa562154`
Exact CI: GitHub Actions `#303`, run ID `31521701493`, head SHA `7cb0bcdbbb823721fc9e6d3ef1feeb8ffa562154`, `SUCCESS`
Allowed write scope: this report only.

# PHASE 14 ADVERSARIAL VALIDATION: FAIL

The candidate passes the six mandatory P14 race gates, concurrent same-UID exact replay for obligation creation, conflicting replay rejection, claim-aware receivable normalization, scale/reopen/integrity and the previously accepted Phase-14 domain/reference/history guards. One remaining release blocker exists in the full nullable replay surface: `changeObligationStatus()` exact replay with `sourceEventUid = null` still routes through `statusEventMatches()` using a nullable rawQuery selection argument (`source_event_uid IS ?`). The final hotfix made only `initialStatusMatches()` null-safe. Thus obligation-create replay with null provenance event now passes, but status-event replay retains the same nullable-bind shape and is not covered by the green null replay regression.

No runtime/schema/test correction was implemented. Phase 15 was not started.

---

## 1. Candidate freshness and exact CI

Fresh repository history was checked before validation. `7cb0bcdbbb823721fc9e6d3ef1feeb8ffa562154` is the newest WORK-061 runtime commit. No later WORK-061 runtime candidate exists.

Exact GitHub Actions run `31521701493`, run number `303`, is completed `SUCCESS` with the exact requested head SHA.

The final commit changes only `AssetLiabilityStore.kt`, specifically making `initialStatusMatches()` split null and non-null source-event comparison so a null is no longer passed through that replay comparison path.

Result: **PASS**.

---

## 2. Mandatory P14-RACE-01..06

The exact candidate's `AssetLiabilityConcurrencyTest` uses two separate `SQLiteDatabase` connections, two independent `AssetLiabilityStore` instances, a two-thread executor, and `CountDownLatch` synchronization before releasing both operations. These are real competing callers rather than sequential simulations.

- `P14-RACE-01` competing same asset identity: **PASS** — one canonical asset row.
- `P14-RACE-02` concurrent 80 + 80 settlement against principal 100: **PASS** — one commits, one fails, outstanding 20.
- `P14-RACE-03` same valuation basis fork: **PASS** — one commits, one fails, one valuation fact.
- `P14-RACE-04` party retirement vs obligation creation: **PASS** — one coherent serialized outcome.
- `P14-RACE-05` competing terminal status events: **PASS** — one terminal successor only.
- `P14-RACE-06` concurrent encumbrance release: **PASS** — single CAS transition, version advances once.

Each fixture also checks `PRAGMA integrity_check = ok` and zero rows from `PRAGMA foreign_key_check`.

---

## 3. EXACT SAME UID CONCURRENT REPLAY — PASS

Fixture: two separate SQLite callers concurrently invoke `createObligation()` with the same `obligationUid`, same immutable payload and same initial status-event UID.

Important: the fixture's `ObligationRecord` leaves `sourceEventUid` at its default `null` value.

Required:

```text
2 logical successes
1 canonical obligation fact
1 canonical initial status fact
0 conflicting effects
```

The exact test asserts:

- `r.ok == 2`;
- `r.bad == 0`;
- exactly one `obligation_records` row;
- exactly one matching `obligation_status_history` row;
- SQLite integrity/FK checks clean.

CI #303 executes the JVM unit suite successfully. The final hotfix makes `initialStatusMatches()` null-safe by emitting either `source_event_uid IS NULL` with no null bind or `source_event_uid=?` with a non-null argument.

Result: **PASS**.

---

## 4. CONFLICTING SAME UID REPLAY — PASS

Two concurrent callers use the same obligation UID but different immutable `principalMinor` payloads.

Required: one canonical semantic fact; conflicting replay rejected.

The fixture requires exactly one success and one failure, exactly one canonical obligation row, and principal equal to one of the two competing legal payloads, never a merged/corrupt result.

`stableUidWrite()` serializes same stable-UID replay work across store instances through connection-independent striped locks keyed by campaign/domain/UID, while SQLite PK/FK/triggers remain authoritative persistence guards.

Result: **PASS**.

---

## 5. NULL REPLAY PATH — FAIL (remaining status-event endpoint)

### What now passes

The null replay path that caused the prior exact obligation replay failure is fixed:

```text
createObligation(... sourceEventUid = null)
-> existing obligation exact replay
-> initialStatusMatches(...)
```

`initialStatusMatches()` now branches:

- null -> SQL `source_event_uid IS NULL` with no nullable bind;
- non-null -> SQL `source_event_uid=?` with a concrete selection argument.

This exact path is exercised concurrently by the green `exactSameUidConcurrentReplayConvergesToOneCanonicalFact` test.

### Remaining blocker

A second stable-UID replay surface remains structurally unchanged:

```text
changeObligationStatus(
  obligationUid = "O",
  eventUid = "STATUS-1",
  status = DEFAULTED,
  at = 2,
  provenance = "p",
  sourceEventUid = null
)
```

On first call the status event is inserted. On the exact second replay, `statusEventExists(eventUid)` is true and runtime calls:

```text
statusEventMatches(..., sourceEventUid = null)
```

The current implementation still builds:

```sql
... provenance=? AND source_event_uid IS ?
```

and passes `sourceEventUid` as the final `rawQuery` selection argument. This retains the nullable-bind pattern that the final hotfix removed only from `initialStatusMatches()`.

### Minimal reproducer

1. Bootstrap campaign `C`; register valid parties A/B and currency.
2. Create obligation `O` with an initial active status.
3. Call `changeObligationStatus("O", "STATUS-NULL", DEFAULTED, 2, "status", null)`.
4. Repeat the exact same call.

### Required

Second call is an exact replay/no-op:

```text
logical success
one canonical status event
no bind exception
no extra effect
```

### Actual runtime path

Second call enters `statusEventMatches()` whose nullable `sourceEventUid` is still supplied as a `rawQuery` selection argument using `source_event_uid IS ?`; the null-safe split applied to `initialStatusMatches()` is absent here. This is the same class of null replay defect that prompted the final hotfix, now on another stable-UID replay endpoint.

### Violated invariant

All supported exact stable-UID replay endpoints must be deterministic and null-safe for nullable immutable fields. A nullable `sourceEventUid` must compare by value without a bind failure and must not turn a valid retry into an error.

### Minimal correction scope

Phase 14 `AssetLiabilityStore.statusEventMatches()` only, plus regression coverage:

- branch null to `source_event_uid IS NULL` without a null selection arg;
- branch non-null to `source_event_uid=?`;
- add sequential and concurrent exact status-event replay with `sourceEventUid=null`;
- retain semantic-conflict rejection for a different non-null/null source event.

No Phase-12 Ownership, Phase-13 Finance, schema redesign, migration rewrite or Phase-15 work is required.

Result: **FAIL / RELEASE BLOCKER**.

---

## 6. RECEIVABLE CLAIM NORMALIZATION

The earlier double-count and undercount defects are corrected using stable canonical linkage, not heuristic similarity.

`netWorth()` now excludes a RECEIVABLE AssetRecord from `assetsMinor` only when `hasLinkedBeneficiaryObligation()` finds an ObligationRecord carrying the exact same `(assetKindUid, assetUid)` stable claim reference, same beneficiary identity, same currency and active as-of interval.

No amount/label/name similarity is used for deduplication.

### Linked receivable + matching obligation — PASS

Fixture:

```text
owned RECEIVABLE asset = 100
matching beneficiary Obligation = 100
Obligation.asset == exact receivable asset ref
```

Required/observed assertions:

```text
assetsMinor = 0
receivablesMinor = 100
netWorthMinor = 100
```

Result: **PASS**.

### Independent receivable — PASS

Owned/valued RECEIVABLE asset 100 with no linked Obligation remains in asset aggregation.

Required/observed:

```text
assetsMinor = 100
receivablesMinor = 0
netWorthMinor = 100
```

Result: **PASS**.

### Unrelated obligation + receivable — PASS

Owned receivable asset 100 plus a separate obligation receivable 100 with no stable asset link must be two economic claims.

Required/observed:

```text
assetsMinor = 100
receivablesMinor = 100
netWorthMinor = 200
```

Result: **PASS**.

---

## 7. Identity / reference / valuation / settlement / lifecycle attacks — PASS

No regression was found relative to the prior WORK-065 matrix in:

- duplicate asset/valuation/obligation/settlement/encumbrance identities;
- conflicting immutable-payload replay rejection;
- generic reference spoofing;
- wrong-campaign references;
- unknown/inactive party/asset/currency/type guards;
- exact integer valuation/principal/settlement arithmetic;
- valuation current-basis/backdating protections;
- over-settlement and terminal-state settlement guards;
- party retirement vs live obligations;
- asset terminal lifecycle vs ownership/valuation/encumbrance;
- CAS encumbrance release;
- append-preserved histories;
- direct SQL immutable-history guards;
- SourceOfTruthRegistry / generic StatePatch denial.

Result: **PASS** except for the null status-event replay defect in section 5.

---

## 8. Cross-domain separation — PASS

The candidate continues to preserve:

```text
Asset / Liability
!= OwnershipRecord
!= Inventory possession
!= Equipment state
!= Financial Ledger
```

Asset identity/valuation does not itself create ownership. Payment does not create title. Ownership does not fabricate a FinancialTransaction. Inventory/Equipment are not wealth/title authority. Obligation PAYMENT settlement references accepted Financial Ledger evidence rather than mutating balances independently.

Result: **PASS**.

---

## 9. Legacy / scale / reopen / restore / campaign isolation — PASS

The Phase-14 persistence suite continues to prove:

- no synthesis of canonical asset/liability history from legacy `debt`, `property_value`, `investment_value`, inventory labels or possession;
- 1001 valuation-history rows are preserved and used by authoritative as-of/net-worth logic;
- reopen retains exact count and derived result;
- same stable UID strings remain isolated across campaigns;
- latest-schema ensure remains additive/idempotent;
- derived net worth is not a separate mutable authority.

Production CI #303 runs the full JVM test suite and completes successfully.

Result: **PASS**.

---

## 10. SQLite integrity

Mandatory P14 concurrency and persistence fixtures invoke:

```sql
PRAGMA integrity_check;
PRAGMA foreign_key_check;
```

Expected/observed test oracle:

```text
integrity_check = ok
foreign_key_check = zero rows
```

Result: **PASS**.

---

# FINAL VERDICT

# PHASE 14 ADVERSARIAL VALIDATION: FAIL

for exactly:

`7cb0bcdbbb823721fc9e6d3ef1feeb8ffa562154`

Summary:

```text
P14-RACE-01 PASS
P14-RACE-02 PASS
P14-RACE-03 PASS
P14-RACE-04 PASS
P14-RACE-05 PASS
P14-RACE-06 PASS

exact same UID concurrent replay PASS
conflicting same UID replay PASS
null obligation-create replay PASS
null status-event exact replay FAIL

linked receivable PASS
independent receivable PASS
unrelated claim PASS
```

The sole release blocker found is the remaining nullable `sourceEventUid` exact-replay path in `statusEventMatches()`.

No hotfix was implemented by CHAT-5. Phase 15 remains blocked.
