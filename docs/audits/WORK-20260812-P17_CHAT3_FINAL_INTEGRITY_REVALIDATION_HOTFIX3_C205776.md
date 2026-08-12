# PHASE 17 INTEGRITY REVALIDATION — HOTFIX3

ROLE: CHAT-3 — Integrity Auditor

VALIDATED RUNTIME SHA: `c20577678b319590be09df45a41d4050a74dc783`

Repository: `piotreksmaga-art/rpg-os-android`

Allowed write scope: this report only. No production/test/schema/workflow/runtime changes. Phase 18 not started.

# FINAL VERDICT

`PHASE 17 INTEGRITY REVALIDATION: FAIL`

A release-blocking conflict-key integrity defect remains in the exact target runtime.

## 1. Repository-first verification

Fresh master at audit start was the target runtime:

`c20577678b319590be09df45a41d4050a74dc783`

During the audit, master advanced to:

`c1bb89dbf4d354af900b6b08e53bc0f996e264f2`

Inspection shows the only change after the target is report-only:

`docs/audits/WORK-20260812-P17_PLAYERCHANGESET_SEMANTIC_REVALIDATION_C205776.md`

No newer Phase-17 production/test runtime exists after the target. Therefore the audited runtime remains exactly `c20577678b319590be09df45a41d4050a74dc783`.

Ancestry preserves prior Phase-17 runtimes and reports. The Hotfix3 production delta is inherited from commit `97e6e1ba158f276936dbc52206602294e1cff335`, followed by regression tests in `72a8fd23a5afd160a760f83f2a91443dc5ba2bc2`; target `c205776...` adds only the robustness follow-up report.

## 2. Exact CI

Verified exact GitHub Actions run:

- run number: `366`
- run ID: `31641781605`
- workflow: `Build & Release RPG OS ALPHA`
- head SHA: `c20577678b319590be09df45a41d4050a74dc783`
- conclusion: `SUCCESS`

Successful job steps include:

- Validate project
- Run JVM unit tests
- Build signed ALPHA APK
- Prepare release files
- Upload Actions artifact
- Update existing GitHub Release assets
- Complete job

Green CI is execution evidence only and does not override the integrity defect below.

A separate local Gradle execution was attempted from the audit environment, but the container cannot resolve `github.com`, so repository cloning/dependency execution is unavailable locally. No local run is claimed as evidence.

## 3. Hotfix3 `AssetChange` conflict-key injectivity — PASS in its own surface

The previous vulnerable key:

`ASSET:<assetKindUid>:<assetUid>`

was replaced for `AssetChange` with `assetConflictKey(OwnedAssetRef)`.

Current behavior:

- if `assetUid` contains no `:`, key is `ASSET:<kind>:<uid>`;
- if `assetUid` contains `:`, key is `ASSET|<kind.length>:<kind>|<uid.length>:<uid>`.

The two modes have disjoint prefixes (`ASSET:` vs `ASSET|`).

For the legacy/simple mode, the final colon is unambiguous because `assetUid` is colon-free.

For the length-prefixed mode, equality of produced key strings requires the same decimal component lengths and the same exact component contents. Characters including `:`, `|`, `\\`, Unicode and whitespace do not create another tuple with the same generated string.

Therefore the specific `AssetChange` helper is injective across the legal nonblank string domain inspected.

The Hotfix3 tests correctly cover:

- the original CHAT-5 alias reproducer;
- same tuple => conflict;
- different kind + same UID => distinct;
- same kind + different UID => distinct;
- multiple colons;
- Unicode / spaces / pipes / backslashes;
- round-trip preserving `OwnedAssetRef`;
- fingerprint distinction.

## 4. RELEASE BLOCKER — P17-INT-HOTFIX3-COMPOSITE-KEY-01

### Invariant

Conflict identity must be injective over the legal semantic target domain:

- same semantic tuple => same conflict target / conflict;
- different semantic tuple => no false conflict.

The user-required Hotfix3 audit explicitly requires checking the full legal UID domain, including delimiters, and not limiting inspection to the previous reproducer.

### Production path

`app/src/main/java/com/rpgos/app/PlayerChangeSetCodec.kt`

`PlayerChangeKinds.OWNERSHIP` still produces:

`OWNED_ASSET:${asset.assetKindUid}:${asset.assetUid}`

without length-prefixing or escaping.

The same validator permits `OwnershipChange.asset.assetKindUid` and `asset.assetUid` whenever each is nonblank; it imposes no prohibition on `:`.

### Minimal reproducer

Create two distinct legal asset identities:

Asset A:

- `assetKindUid = "KIND:A"`
- `assetUid = "B"`

Asset B:

- `assetKindUid = "KIND"`
- `assetUid = "A:B"`

These are distinct canonical tuples:

`OwnedAssetRef("KIND:A", "B") != OwnedAssetRef("KIND", "A:B")`

Create two `OwnershipChange` proposals with:

- distinct `changeUid` values;
- distinct `ownershipRecordUid` values;
- otherwise legal, distinct owner refs;
- valid `OwnershipShare` values;
- Asset A on the first change and Asset B on the second.

Current conflict keys include:

For A:

`OWNED_ASSET:KIND:A:B`

For B:

`OWNED_ASSET:KIND:A:B`

The strings are identical even though the semantic assets are different.

`PlayerChangeSetValidator` inserts conflict keys into one `HashSet<String>` and therefore rejects the second change with:

`CONFLICTING_CHANGE_TARGET`

### Expected

Two distinct `OwnedAssetRef(assetKindUid, assetUid)` values must not conflict merely because delimiter concatenation aliases their component boundaries.

### Actual

Distinct assets receive the same `OWNED_ASSET` conflict key and are falsely treated as the same semantic target.

### Architectural impact

This is not cosmetic diagnostic formatting. Conflict keys are authoritative to Phase-17 structural validity of a proposal. A legal PlayerChangeSet containing independent ownership operations on different assets can be deterministically rejected because the internal conflict identity is non-injective.

The exact defect class that Hotfix3 fixes for `AssetChange` therefore remains present in another typed Phase-17 conflict surface using the same canonical asset tuple.

### Wider affected surface

Independent inspection also found the same delimiter-concatenation pattern in other multi-component conflict keys, including:

- `STAT:<subjectKind>:<subjectUid>:<statUid>`
- `RESOURCE:<subjectKind>:<subjectUid>:<resourceUid>`
- `SKILL:<subjectKind>:<subjectUid>:<skillUid>`
- `TECHNIQUE:<subjectKind>:<subjectUid>:<techniqueUid>`
- `INNATE:<subjectKind>:<subjectUid>:<innateUid>`
- `INVENTORY:<subjectKind>:<subjectUid>:<itemInstanceUid>`
- `EQUIPMENT:<subjectKind>:<subjectUid>:<slotUid>`
- `CONDITION:<subjectKind>:<subjectUid>:<conditionUid>`
- `RUNTIME:<subjectKind>:<subjectUid>:<runtimeCounterUid>`

The corresponding validation paths check components for nonblank validity but do not define `:` as forbidden UID syntax. Thus the defect is a generic composite-conflict-key encoding problem, not solely an Ownership special case.

A minimal second reproducer is possible for STAT:

- target A: `(subjectKind="PLAYER:A", subjectUid="B", statUid="C")`
- target B: `(subjectKind="PLAYER", subjectUid="A:B", statUid="C")`

Both serialize to conflict key:

`STAT:PLAYER:A:B:C`

while representing distinct semantic targets.

### Minimal correction scope

Phase-17-only:

- replace raw delimiter concatenation for every composite conflict identity with one shared injective tuple-key representation, or a typed structural conflict key rather than a flattened ambiguous string;
- reuse the same canonical asset-tuple encoding for both ASSET and OWNED_ASSET target identity;
- add regressions for Ownership and at least one generic subject-target family using delimiter-bearing UIDs;
- preserve existing same-target conflict semantics and no-false-global-conflict behavior.

No database/schema migration, Phase-18 engine, persistence or domain write change is required.

No runtime correction was implemented by CHAT-3.

## 5. Immutability / aliasing — PASS

No Hotfix3 regression found.

`PlayerChangeSet` root collections remain defensively copied to unmodifiable lists. Nested list-bearing structures likewise copy caller-owned inputs, including:

- event target refs;
- event causal change UIDs;
- ledger causal change UIDs;
- DevelopmentProject evidence refs.

Hotfix3 changes no model collection ownership or mutability behavior.

## 6. Authority boundary — PASS

No Phase-17 persistence or write authority was introduced.

Construction, validation, encoding, decoding, fingerprinting and identity comparison contain no:

- DAO writer;
- SQLite write;
- StatePatch mutation bridge;
- repository/store mutation;
- apply/commit/execute/save/persist hook;
- transaction commit path;
- PlayerDomainEngine / Phase-18 runtime.

PlayerChangeSet remains proposal-only.

## 7. Public construction / decode boundary — PASS with non-blocking robustness note

The normal public contract remains fail-closed:

- public `PlayerChangeSet.create(...)` performs validation before returning;
- `PlayerChangeSetCodec.decode(...)` performs duplicate-key scanning, strict structural decode and final `PlayerChangeSetValidator.validate(...)`;
- typed registry codec access remains internal;
- typed codec decode self-applies allowed-key validation.

No public path was found that returns a legal typed PlayerChangeSet while bypassing validation.

Target also contains the report-only robustness note that malformed nested array element shapes may escape as a library `IllegalArgumentException` instead of `PlayerChangeSetStructuralException`. This remains fail-closed and does not create a legal ChangeSet, canonical identity or authoritative mutation. It is recorded as a non-release-blocking error-family consistency item in the present integrity scope.

## 8. Asset identity model / codec / canonicalization — PASS

`AssetChange` continues to carry the complete `OwnedAssetRef(assetKindUid, assetUid)`.

Both dimensions survive:

- in-memory model;
- validation;
- nested asset codec;
- canonical JSON;
- encode/decode round-trip;
- fingerprint.

Hotfix3 does not regress the earlier `PROPERTY/A-1 != BUSINESS/A-1` identity correction.

The FAIL is specifically in broader conflict-key construction, not in canonical `OwnedAssetRef` serialization.

## 9. Financial / ledger Hotfix2 regression — PASS

The validator still:

- enforces unique `ledgerIntentUid`;
- rejects dangling causal refs;
- requires a matching FinancialChange for non-standalone financial ledgers;
- allows explicit standalone ledgers with an empty causal list;
- checks exact equality of:
  - `fromAccountUid`
  - `toAccountUid`
  - `amountMinor`
  - `currencyUid`
  - `transactionTypeUid`;
- performs term matching before duplicate-causal registration;
- tracks represented `FinancialChange.changeUid` globally across ledger intents;
- rejects a second causal representation with `DUPLICATE_FINANCIAL_LEDGER_CAUSAL_CHANGE`.

No Hotfix2 regression was found.

## 10. Strict codec integrity — PASS

The inspected production codec continues to enforce:

- root allowed keys;
- typed payload allowed keys;
- nested allowed keys;
- duplicate object key rejection before JSON parser collapse;
- strict String JSON scalar type;
- strict Int/Long JSON scalar type;
- quoted numeric rejection;
- supported ChangeSet schema version;
- supported change/event/ledger/precondition kinds;
- payload type matching;
- final validation after decode.

No lossless-decode/fingerprint bypass was found in the inspected Hotfix3 changes.

## 11. Canonical serialization / fingerprint — PASS apart from conflict-validation blocker

Canonical encode/decode/encode remains deterministic for legal ChangeSets that pass structural validation.

Fingerprint remains SHA-256 over validated canonical encoding.

`OwnedAssetRef` data itself remains lossless.

However overall structural integrity is FAIL because a semantically legal ChangeSet may be rejected before canonicalization due to a false composite conflict-key collision.

## 12. Numeric boundaries — PASS

No Hotfix3 numeric regression found.

- exact finance values remain Long minor units;
- `ExactLongDelta` uses checked `Math.addExact` / `Math.subtractExact` paths;
- zero delta remains rejected;
- strict numeric JSON scalar typing remains active;
- OwnershipShare retains accepted fixed-scale semantics;
- no Float/Double proposal authority was introduced.

## 13. Zero authoritative mutation — PASS

Hotfix3 production code changes only conflict-key generation. It introduces no write path.

The Hotfix3 test suite also includes a database fixture verifying that validate -> encode -> decode -> fingerprint does not alter authoritative state.

## 14. Phase 3–16 regression — PASS in inspected / exact-CI scope

Hotfix3 production delta is limited to `PlayerChangeSetCodec.kt` conflict-key logic. No accepted Phase 3–16 schema/store/authority code is modified.

Exact CI #366 runs the full JVM unit suite successfully.

## 15. Test review

`PlayerChangeSetReleaseBlockerHotfix3Test` contains real assertions using production validator/codec paths for:

- original asset alias reproducer;
- same tuple conflict;
- different kind / same UID;
- same kind / different UID;
- multiple colons;
- Unicode / spaces / pipes / backslashes;
- full asset round-trip;
- distinct fingerprints;
- Hotfix2 ledger uniqueness regression;
- earlier Property/Business identity regression;
- zero DB mutation;
- representative Phase 3–16 checks.

The tests do not cover the independent `OwnershipChange` `OWNED_ASSET` alias described above, nor the broader colon-concatenated multi-component conflict-key families.

Therefore their green result is not a false-positive test execution issue; it is an incomplete integrity matrix relative to the full Phase-17 conflict boundary.

## 16. Gate summary

```text
ROLE: CHAT-3
VALIDATED RUNTIME SHA: c20577678b319590be09df45a41d4050a74dc783
FRESH MASTER BEFORE REPORT: c1bb89dbf4d354af900b6b08e53bc0f996e264f2
RUNTIME CHANGED AFTER TARGET: NO

IMMUTABILITY / ALIASING: PASS
AUTHORITY BOUNDARY: PASS
PUBLIC CONSTRUCTION / DECODE PATHS: PASS
ASSET IDENTITY MODEL/CODEC: PASS
HOTFIX3 ASSET-CONFLICT HELPER: PASS
FULL CONFLICT-KEY INTEGRITY: FAIL
FINANCIAL / LEDGER HOTFIX2: PASS
STRICT CODEC: PASS
CANONICAL SERIALIZATION / FINGERPRINT: PASS FOR ACCEPTED INPUTS
NUMERIC BOUNDARIES: PASS
ZERO AUTHORITATIVE MUTATION: PASS
PHASE 3-16 REGRESSION: PASS
FULL JVM / EXACT CI: PASS

NEW BLOCKERS:
P17-INT-HOTFIX3-COMPOSITE-KEY-01
```

# PHASE 17 INTEGRITY REVALIDATION: FAIL

This report does not mark Phase 17 globally ACCEPTED. Phase 18 remains blocked until three independent PASS results refer to the same corrected runtime SHA.