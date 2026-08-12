# PHASE 17 — CHAT-5 FINAL ADVERSARIAL / ROBUSTNESS REVALIDATION

Role: CHAT-5 / READ-ONLY adversarial auditor  
Repository: `piotreksmaga-art/rpg-os-android`  
Exact runtime inspected: `1df30948eb846e7530fcbbb52d56b1b09053d9b4`  
Fresh master at final report write: `8bf18a3e3d7679868275dc04950469918362c3b5` (later commits after runtime are report-only)  
Exact CI: GitHub Actions `#353`, run ID `31634593825`, head SHA `1df30948eb846e7530fcbbb52d56b1b09053d9b4`, conclusion `SUCCESS`.

This audit modifies no production/test runtime and does not start Phase 18.

# FINAL VERDICT

`PHASE 17 ADVERSARIAL / ROBUSTNESS REVALIDATION: FAIL`

## Production / tests inspected

Production delta for exact Phase-17 runtime:
- `app/src/main/java/com/rpgos/app/PlayerChangeSetModel.kt`
- `app/src/main/java/com/rpgos/app/PlayerChangeSetCodec.kt`

Tests:
- `app/src/test/java/com/rpgos/app/PlayerChangeSetContractTest.kt`

Cross-checked contracts:
- MASTER / Roadmap / Parallel Work Coordination
- `docs/audits/WORK-20260810-068_PHASE17_PLAYERCHANGESET_ARCHITECTURE.md`
- accepted Phase-12 Ownership identity contract
- accepted Phase-13 Finance contract
- accepted Phase-14 Asset/Liability generic asset contract
- accepted Phase-16 PlayerCommand boundary

Exact CI #353 executed Validate project, full JVM `:app:testDebugUnitTest`, signed ALPHA APK, release preparation, artifact upload, and release asset update successfully. An independent local Gradle rerun was attempted from the audit container, but outbound DNS to github.com is unavailable in that environment, so no independent local run is claimed. The blockers below follow directly from the exact production code paths and are not dependent on CI interpretation.

## Gate summary

- P17-ADV-01 mutable root list aliasing: PASS
- P17-ADV-02 nested list aliasing: PASS
- P17-ADV-03 generic StatePatch smuggling: PASS
- P17-ADV-04 world-specific Core smuggling: PASS
- P17-ADV-05 domain/reference confusion: **FAIL — generic Asset identity loses assetKindUid**
- P17-ADV-06 numeric boundaries / exactness: PASS
- P17-ADV-07 duplicate change UID: PASS
- P17-ADV-08 conflicting same semantic change target: PASS for same-surface conflict keys; **FAIL cross-surface FinanceChange ↔ ledger contradiction**
- P17-ADV-09 semantic order / fingerprint: PASS
- P17-ADV-10 unknown JSON fields: PASS
- P17-ADV-11 duplicate/escaped-equivalent JSON keys: PASS
- P17-ADV-12 wrong scalar type / quoted numeric: PASS
- P17-ADV-13 unsupported schema version: PASS
- P17-ADV-14 authority mutation attempt: PASS
- P17-ADV-15 store/repository writer leak: PASS
- P17-ADV-16 fake event/ledger committed authority: PASS as persistence boundary; FAIL for internal finance/ledger contradiction described below
- P17-ADV-17 fake provenance creates fact: PASS
- P17-ADV-18 campaign/player structural scope: PASS at Phase-17 structural level; authoritative target campaign resolution remains later validation
- P17-ADV-19 Phase-16 regression: PASS
- P17-ADV-20 property-style/collision analysis: FAIL due two concrete semantic-collision classes below

## BLOCKER 1 — P17-ADV-ASSET-IDENTITY-01

### Violated invariant

Phase 17 must preserve stable canonical domain references and must not collapse accepted Phase-3–16 identities. Phase 14 generic asset identity is not `assetUid` alone; it is the namespaced pair `(assetKindUid, assetUid)` represented canonically by `OwnedAssetRef` / `AssetRecord.ref`.

### Exact path

`PlayerChangeSetModel.kt`:

```kotlin
data class AssetChange(
    val assetUid: String,
    val proposedLifecycleStateUid: String
) : PlayerDomainChangePayload
```

`PlayerChangeSetCodec.kt` Asset codec:

```kotlin
setOf("assetUid", "proposedLifecycleStateUid")
...
AssetChange(it.pcsReqString("assetUid"), ...)
...
conflicts = { setOf("ASSET:${it.assetUid}") }
```

Accepted Phase-14 `AssetRecord` is:

```kotlin
data class AssetRecord(
    val campaignId: String,
    val assetUid: String,
    val assetKindUid: String,
    ...
) {
    val ref: OwnedAssetRef get() = OwnedAssetRef(assetKindUid, assetUid)
}
```

and Core supports multiple generic asset kinds (PROPERTY, BUSINESS, COMPANY, SHARES, STAKE, RECEIVABLE, etc.).

### Minimal reproducer

Assume two legal Phase-14 asset targets in the same campaign:

```text
(RPGOS-ASSET-KIND:PROPERTY, A-1)
(RPGOS-ASSET-KIND:BUSINESS, A-1)
```

Both are valid because `assetKindUid` is part of canonical identity.

Try to construct Phase-17 lifecycle proposals for each. The only representable payload is:

```kotlin
AssetChange("A-1", "RETIRED")
```

for both targets.

### Expected

The two canonical targets remain distinguishable in the ChangeSet (e.g. `OwnedAssetRef(assetKindUid, assetUid)` or equivalent typed namespaced ref). Their canonical encoding/fingerprint/conflict key must preserve the namespace.

### Actual

Both distinct authoritative targets collapse to the exact same typed `AssetChange`, canonical JSON and fingerprint. Phase 18 cannot recover which accepted Phase-14 asset was intended because Phase 17 discarded the namespace before handoff.

### Why this is Phase-17 scope

This is not later existence/authorization/campaign resolution. It is irreversible loss of canonical target identity inside the proposal contract itself. The Phase-17 architecture requires Assets/Liabilities to use canonical stable IDs and to remain compatible with accepted Phase-14 authority.

### Minimal correction scope

Phase 17 only: make `AssetChange` carry the full canonical generic asset reference (`assetKindUid + assetUid`, preferably accepted `OwnedAssetRef`) and include both components in serialization, validation and conflict keys. No Phase-18 engine or DB write is required.

---

## BLOCKER 2 — P17-ADV-FIN-LEDGER-01

### Violated invariant

One PlayerChangeSet is one deterministic proposed-effects unit. If a ledger intent explicitly names a causal `FinancialChange`, the immutable financial terms must not contradict the change it claims to derive from. Obvious contradictions fully visible inside one ChangeSet must fail closed at Phase 17.

### Exact path

`PlayerChangeSetValidator.validate()`:

1. gathers valid `changeUid`s;
2. validates each `FinancialChange` independently;
3. validates each `PlayerLedgerIntent` independently;
4. requires each ledger `causalChangeUid` merely to exist in the set;
5. never resolves the referenced change and never compares financial terms.

Existing `p17_20_proposedLedgerIntents` uses matching terms but does not assert that mismatch is rejected.

### Minimal reproducer

Create:

```text
CH-FIN:
FinancialChange(from=A, to=B, amountMinor=100, currency=CUR, transactionType=TRANSFER)

LED-1:
causalChangeUids=[CH-FIN]
FinancialTransferLedgerIntentPayload(
  from=A,
  to=C,
  amountMinor=999,
  currency=CUR,
  transactionType=TRANSFER
)
```

Put both into the same PlayerChangeSet.

### Expected

Deterministic structural rejection: the ledger intent claims `CH-FIN` as cause but encodes incompatible destination/amount.

### Actual

Both payloads are individually legal and `CH-FIN` exists, so current validator accepts the canonical ChangeSet. It can therefore encode/fingerprint a self-contradictory financial proposal.

### Minimal correction scope

Phase 17 only. Either:
1. when a FinancialTransfer ledger intent causally references a `FinancialChange`, require exact immutable financial-term equality; or
2. establish one canonical financial proposal representation and remove the duplicative contradictory surface.

No balance lookup, DB write, Phase-18 orchestration or transaction execution is required.

---

## Other adversarial results

### Immutability — PASS

Root lists are defensively copied into unmodifiable lists. Nested mutable collection inputs are also copied for DevelopmentProject evidence refs, event target/causal refs and ledger causal refs. Value objects are composed of immutable scalars/references. Caller mutation after construction cannot change the constructed ChangeSet through the implemented public collection surfaces.

### World-agnostic boundary — PASS

No Naruto/Bleach/chakra/reiatsu/Sharingan/Hollow-specific fields or change kinds were found in the Phase-17 Core files. Typed families are generic RPG OS concepts.

### Proposal-only / zero authority — PASS

The Phase-17 production delta introduces no SQLite schema, migration, DAO/store/repository writer, command queue, persistence table, StatePatch bridge, `apply`, `commit`, `execute`, `save` or equivalent authoritative write path. Construction, validation, encode/decode, fingerprint and identity comparison are in-memory proposal operations.

### Typed semantics — FAIL overall

The change family is typed and rejects payload-kind mismatch, but `AssetChange` drops the accepted generic asset namespace, and the finance/ledger surfaces can contradict each other while validating.

### Serialization — PASS for represented information

The canonical decoder enforces allowed keys at root and nested objects, pre-scans duplicate object members (including escaped-equivalent keys), uses strict actual JSON String/Numeric scalar readers, rejects quoted numerics, rejects unsupported ChangeSet schema versions, unknown change/event/ledger/precondition kinds and payload type mismatches, and preserves deterministic list order.

A robustness note that is not separately release-blocking in this report: some malformed nested `.jsonObject/.jsonArray` type accesses can surface library exceptions rather than a uniform `PlayerChangeSetStructuralException`; they still reject and do not create a lossy canonical ChangeSet. The two blockers above are stronger semantic failures.

### Identity/fingerprint — FAIL overall

SHA-256 over validated canonical JSON is deterministic, and list order is fingerprint-significant. However, fingerprint cannot preserve information that `AssetChange` never models: PROPERTY:A-1 and BUSINESS:A-1 lifecycle proposals collapse before canonicalization.

### Conflict/duplicates — FAIL overall

Duplicate change/event/ledger IDs, same conflict-key targets, dangling causalChangeUid and dangling warning refs are rejected. Cross-surface FinanceChange ↔ FinancialTransferLedgerIntent consistency is not enforced.

### Numeric safety — PASS

`ExactLongDelta` uses exact Long arithmetic with `Math.addExact/subtractExact`; zero deltas reject. Finance uses Long minor units and preserves values beyond IEEE-754 exact range. Ownership reuses accepted fixed-scale `OwnershipShare`; no Float/Double authority was introduced.

### Phase 3–16 regression — PASS within observed evidence

Phase-17 runtime adds only its two production files and its contract test; it does not modify accepted Phase 3–16 authorities. Exact CI #353 executes full JVM tests successfully, including Phase-16 regression fixture.

### Full JVM — PASS (exact CI evidence)

GitHub Actions #353 / run ID `31634593825` / head SHA `1df30948eb846e7530fcbbb52d56b1b09053d9b4` completed successfully. The `Run JVM unit tests` step completed `success`. Local independent rerun could not be performed because the isolated audit container cannot resolve github.com; no contrary local result is claimed.

# Required final summary

```text
PHASE 17 ADVERSARIAL / ROBUSTNESS REVALIDATION

Exact SHA: 1df30948eb846e7530fcbbb52d56b1b09053d9b4
Fresh master SHA at report write: 8bf18a3e3d7679868275dc04950469918362c3b5
Production files inspected:
- PlayerChangeSetModel.kt
- PlayerChangeSetCodec.kt
Tests inspected:
- PlayerChangeSetContractTest.kt

Immutability: PASS
World-agnostic: PASS
Proposal-only: PASS
Typed semantics: FAIL
Serialization: PASS for represented information
Identity/fingerprint: FAIL
Conflict handling: FAIL
Numeric safety: PASS
Zero authoritative mutation: PASS
Phase 3–16 regression: PASS
Full JVM: PASS via exact CI #353

BLOCKERS:
1. P17-ADV-ASSET-IDENTITY-01 — AssetChange drops assetKindUid and collapses generic Phase-14 asset identity.
2. P17-ADV-FIN-LEDGER-01 — causal FinancialChange and ledger intent may contradict while both validate.

FINAL VERDICT: FAIL
```

Phase 17 is NOT marked globally accepted by this report. Phase 18 remains BLOCKED.