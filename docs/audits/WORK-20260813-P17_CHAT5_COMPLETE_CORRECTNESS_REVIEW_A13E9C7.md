# CHAT-5 — Phase 17 Complete Independent Correctness Review

Status: FINAL CORRECTNESS REVIEW — FAIL

Repository: `piotreksmaga-art/rpg-os-android`
Validated runtime SHA: `a13e9c795251da5d2fb241c5ff58e38a2d5b5f87`
Exact CI: GitHub Actions `#375`, run ID `31668369509`, exact head SHA `a13e9c795251da5d2fb241c5ff58e38a2d5b5f87`, `SUCCESS`
Role: CHAT-5 / independent correctness + adversarial reviewer

This is a report-only audit. No production code, tests, schema, migrations, workflow or Phase-18 runtime were modified.

## 1. Review target / freshness

At review start `master` was exactly `a13e9c795251da5d2fb241c5ff58e38a2d5b5f87`.

Target ancestry:

- parent `115f885674dc44387434e07baa482cada991ec1f` — `Phase 17: enforce ExactLongDelta invariant at constructor boundary`;
- target `a13e9c795251da5d2fb241c5ff58e38a2d5b5f87` — `Phase 17: add exact value invariant regression suite`.

During the review `master` advanced through two report-only commits:

- `fd312d32f56a03d4c52f23390cd81feee182a664` — CHAT-2 report;
- `e18766f5c47d586baacdd2b1bbbfc0ba8e1188d2` — CHAT-3 report.

No production/test Phase-17 runtime appeared after the target. Review therefore remained pinned to `a13e9c795251da5d2fb241c5ff58e38a2d5b5f87`.

## 2. ExactLongDelta

PASS.

`ExactLongDelta` now enforces `units != 0` in `init`, in addition to the factory. Generated `data class copy()` invokes the constructor and therefore cannot create `units=0`.

Verified supported paths:

- `ExactLongDelta.of(0)` rejects;
- `ExactLongDelta.of(1).copy(units = 0)` rejects;
- positive and negative nonzero values are legal;
- `Long.MIN_VALUE` and `Long.MAX_VALUE` are representable nonzero deltas;
- `plus()` and `between()` use checked arithmetic and reject overflow/underflow.

The previous `copy(units=0)` defect is closed.

## 3. Other value-object invariants

PASS.

`OwnershipShare` has constructor-level `init` enforcement for `1..OWNERSHIP_SHARE_SCALE`, so generated `copy()` cannot bypass zero, negative or over-100% range checks. Factory/fraction conversion remains exact fixed-scale, with `BigInteger` used for exact representability.

Other Phase-17 public data-class payloads can be constructed with structurally bad scalar/ref values, but they cannot become an accepted `PlayerDomainChange` / `PlayerChangeSet` without re-entering `TypedPlayerChangeRegistry` / `PlayerChangeSetValidator`; no equivalent factory-only/generated-copy bypass was found.

## 4. In-memory / serialized closure

PASS for every accepted in-memory `PlayerChangeSet` representable by the current Phase-17 model.

No accepted proposal was found that encodes to canonical JSON and then fails to decode into an equivalent accepted object. Strict string/numeric decoding, enum decoding, ownership-share reconstruction, stable refs, warnings, preconditions, events and ledger intents round-trip deterministically.

A separate semantic representability blocker exists for DevelopmentProject zero-progress work; see section 17. That blocker prevents construction of the needed legal proposal rather than violating round-trip closure of an already accepted object.

## 5. Serialization correctness

PASS.

Verified production protections:

- unknown root/nested fields reject;
- duplicate JSON object keys reject before parser map-collapse, including escaped-equivalent keys;
- String fields require actual JSON strings;
- numeric fields require actual JSON numeric primitives;
- quoted numerics reject;
- null/missing behavior is explicit;
- unsupported schema versions reject;
- unknown change/event/ledger/precondition kinds reject;
- payload-kind mismatch rejects;
- malformed nested object/array values fail closed.

Non-blocking observation: a few malformed nested element shapes may surface a library `IllegalArgumentException` rather than `PlayerChangeSetStructuralException`; they remain fail-closed and do not permit acceptance, data loss, identity corruption or mutation.

## 6. Fingerprint

PASS for legal proposals.

- canonical encoding is deterministic;
- `encode -> decode -> encode` is byte-stable;
- fingerprint is SHA-256 over validated canonical serialization;
- semantic payload/list-order differences change the fingerprint where identity requires it;
- same `(campaignUid, changeSetUid)` plus different canonical content produces identity conflict;
- invalid `ExactLongDelta(0)` cannot be constructed and therefore cannot receive a legal canonical proposal fingerprint.

## 7. Composite conflict target identity

PASS.

The shared `compositeConflictKey()` is used by:

- STAT
- RESOURCE
- SKILL
- TECHNIQUE
- INNATE
- INVENTORY
- EQUIPMENT
- ASSET
- OWNED_ASSET
- CONDITION
- RUNTIME

The old STAT alias is fixed:

- `DomainRef("PLAYER", "X:Y"), statUid="Z"`
- `DomainRef("PLAYER", "X"), statUid="Y:Z"`

produce distinct conflict identities.

The legacy encoding is used only when all components after the first contain no `:`; because arity is fixed per discriminator, the tuple remains recoverable from the right. Otherwise CK1 uses discriminator length, component count and per-component lengths. No collision was found within legacy, within CK1, legacy↔CK1, across discriminators, or across component counts for legal values containing `:`, `|`, `\\`, spaces, Unicode, CK1-looking or length-prefix-looking text.

Single-component conflict families (`FIN_ACCOUNT`, `OWNERSHIP`, `PROJECT`) remain injective by direct prefix + opaque value and do not share a conflicting discriminator namespace with composite families.

## 8. Asset identity

PASS.

`AssetChange` carries full `OwnedAssetRef(assetKindUid, assetUid)` through model, validation, codec, round-trip, fingerprint and composite conflict handling. Delimiter alias cases remain distinct. Same kind + same UID remains one semantic target.

## 9. Financial / ledger

PASS.

Reviewed:

- source account;
- destination account;
- positive exact `amountMinor`;
- currency;
- transaction type;
- causal-change existence;
- non-financial causal rejection;
- standalone ledger compatibility;
- exact FinancialChange↔ledger term matching;
- at-most-one ledger representation per causal FinancialChange;
- multiple independent financial changes with separate ledgers;
- dangling causal refs;
- duplicate ledger IDs.

No internally contradictory financial/ledger proposal was found that current validation accepts.

## 10. Duplicate / conflict / reference handling

PASS.

Fail-closed handling remains for duplicate change/event/ledger IDs, same semantic targets, dangling event/ledger causal refs and dangling warning refs. Different legal composite targets do not false-conflict under the shared encoder.

## 11. Numeric correctness

FAIL due to a domain-semantic representability problem, not an `ExactLongDelta` constructor bypass.

All currently representable numeric values are exact and round-trip safely. However `DevelopmentProjectChange` uses mandatory nonzero `ExactLongDelta progressDelta`, while accepted Phase-15 `ProjectWorkRecord` explicitly permits `progressDeltaUnits = 0` and includes meaningful `FAILURE`, `NO_PROGRESS` and `INCIDENT` result kinds.

Therefore the Phase-17 project-work proposal contract cannot represent a legal Phase-15 work fact with zero progress.

See blocker P17-PROJECT-ZERO-PROGRESS-01 below.

## 12. Immutability / aliasing

PASS.

Root and nested list inputs are defensively copied and exposed as unmodifiable lists. External mutation of caller-owned lists cannot alter an existing PlayerChangeSet. Payloads/refs/provenance are immutable value objects. The new `ExactLongDelta` constructor invariant also prevents generated-copy aliasing into an invalid zero value.

## 13. Architecture boundary

PASS.

PlayerChangeSet remains transient typed proposal only. No Phase-17 apply/commit/execute/save/persist/StatePatch/DB writer/repository writer/TurnTransaction execution surface was found. No Phase-18 implementation was introduced. Core remains world-agnostic; no Naruto/Bleach-specific semantics were found in the ChangeSet contract.

## 14. Zero authoritative mutation

PASS.

Construction, structural validation, conflict-key derivation, encode, decode, fingerprint and identity comparison contain no authoritative-state writes. Focused tests use an SQLite authority fixture and observe unchanged state.

## 15. Test quality

FAIL overall because the focused P17-VALUE suite correctly proves the old `ExactLongDelta.copy(units=0)` bypass is closed, but its `allExactLongDeltaChangePathsRemainLegal` coverage uses a nonzero project progress delta and does not exercise the accepted Phase-15 legal zero-progress work case.

Older regression assertions for composite conflict identity, financial/ledger consistency, asset identity, round-trip, zero mutation, OwnershipShare and Phase-16 command behavior remain present and were not weakened.

## 16. Full JVM / exact CI

Local full JVM execution: NOT RUN locally because the audit container cannot resolve `github.com`; `git clone` failed with `Could not resolve host: github.com`.

Exact CI was independently verified:

- GitHub Actions run number: `375`
- run ID: `31668369509`
- head SHA: `a13e9c795251da5d2fb241c5ff58e38a2d5b5f87`
- status: completed
- conclusion: SUCCESS
- Validate project: SUCCESS
- Run JVM unit tests: SUCCESS
- Build signed ALPHA APK: SUCCESS
- Prepare release files: SUCCESS
- Upload Actions artifact: SUCCESS
- Update existing GitHub Release assets: SUCCESS
- overall workflow: SUCCESS

Thus FULL JVM is PASS by exact CI evidence; no local rerun is claimed.

## 17. NEW CORRECTNESS PROBLEM

### P17-PROJECT-ZERO-PROGRESS-01 — legal Phase-15 project work with zero progress cannot be represented by Phase 17

Severity: RELEASE BLOCKER / correctness-contract incompleteness.

### Minimal reproducer

Phase 15 accepts and tests:

```kotlin
ProjectWorkRecord(
    campaignId = "C",
    workRecordUid = "W1",
    projectUid = "P",
    workKindUid = "EXPERIMENT",
    actor = owner,
    effectiveOrder = 6,
    result = ProjectWorkResult.FAILURE,
    progressDeltaUnits = 0,
    effortUnits = 10,
    provenance = "failed attempt"
)
```

The Phase-15 SQLite contract explicitly permits `progress_delta_units >= 0` and the existing persistence suite records `FAILURE, 0` as an auditable work fact.

The corresponding Phase-17 resolved project-work proposal must use:

```kotlin
DevelopmentProjectChange.create(
    projectUid = "P",
    workResultKindUid = "FAILURE",
    progressDelta = ExactLongDelta.of(0)
)
```

### Expected

Phase 17 can represent the legal resolved work result as a proposal: result `FAILURE`, zero progress, preserving the work fact for later Phase-18/domain validation and commit.

### Actual

`ExactLongDelta.of(0)` throws `PlayerChangeSetStructuralException("ZERO_DELTA")`; generated copy is also correctly blocked by `init`. There is no alternate typed Phase-17 project-work payload that can encode a `FAILURE/NO_PROGRESS/INCIDENT` work fact with zero progress. EventIntent is not equivalent because it does not propose the authoritative Phase-15 `ProjectWorkRecord` append.

### Production path

```text
resolved project work
-> DevelopmentProjectChange.create(... progressDelta = ExactLongDelta.of(0))
-> ExactLongDelta.of(0)
-> ZERO_DELTA
-> no PlayerDomainChange / PlayerChangeSet can be produced
```

### Why it matters

The Phase-17 architecture explicitly defines `RecordProjectWorkCommand -> resolution -> ProjectWorkChange(resultKind, progressDeltaUnits, evidence refs)` as a legal handoff. Phase 15 explicitly treats failed/no-progress attempts as durable auditable project history. The current Phase-17 numeric type therefore over-constrains one change family and makes a legal accepted-domain state unrepresentable.

This is Phase-17 scope: it is a type/contract correctness issue, not Phase-18 legality, authorization, transaction or persistence behavior.

No fix was implemented.

## 18. Final gate matrix

```text
EXACTLONGDELTA INVARIANT: PASS
OTHER VALUE-OBJECT INVARIANTS: PASS
IN-MEMORY/SERIALIZED CLOSURE: PASS
SERIALIZATION: PASS
FINGERPRINT: PASS
COMPOSITE TARGET IDENTITY: PASS
LEGACY/CK1 SEPARATION: PASS
ASSET IDENTITY: PASS
FINANCIAL/LEDGER: PASS
DUPLICATE/CONFLICT/REFERENCES: PASS
NUMERIC CORRECTNESS: FAIL
IMMUTABILITY/ALIASING: PASS
ARCHITECTURE BOUNDARY: PASS
ZERO AUTHORITATIVE MUTATION: PASS
TEST QUALITY: FAIL
PHASE 3–16 REGRESSION: PASS
FULL JVM: PASS via exact CI; local rerun NOT-RUN due DNS
EXACT CI: PASS
```

FINAL CHAT-5 VERDICT: FAIL

Phase 17 is not marked globally accepted. Phase 18 remains blocked.