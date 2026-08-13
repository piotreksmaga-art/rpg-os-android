# CHAT-5 — Phase 17 PlayerChangeSet Comprehensive Adversarial / Robustness Revalidation

Role: CHAT-5 — Adversarial / Robustness Auditor

Validated runtime SHA: `e8fce7187a92ffee846b9f60b06809343051a045`

Exact CI: GitHub Actions `#371`, run ID `31666619184`, exact head SHA `e8fce7187a92ffee846b9f60b06809343051a045`, conclusion `SUCCESS`.

Audit mode: report-only. No production/test/schema/workflow/runtime change by CHAT-5. Phase 18 not started.

# FINAL CHAT-5 VERDICT: FAIL

The Phase-17 production implementation passes the new composite-target identity hardening, financial/ledger consistency, strict serialization, canonicalization, defensive collection copying, duplicate/reference handling, architecture/authority boundary, and exact CI/full-JVM execution gates. One new Phase-17 correctness blocker remains: `ExactLongDelta` can be constructed with the forbidden zero value through the generated data-class `copy()` API, and Phase-17 structural validation does not re-check the delta invariant. This permits an accepted in-memory PlayerChangeSet whose canonical encoding cannot be decoded back.

## Fresh master / runtime freshness

At audit start `master` resolved exactly to the target SHA. During the audit, master advanced only by two report-only files under `docs/audits/`. Comparison from the target to the later master head showed no production/test changes. Therefore the validated runtime remained exactly `e8fce7187a92ffee846b9f60b06809343051a045`.

## Composite target identity — PASS

Production uses shared `compositeConflictKey(discriminator, vararg components)` for all multi-component semantic targets:

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

The encoding has two disjoint representations:

1. legacy fast path when all components after the first are colon-free: `<discriminator>:<components joined by ':'>`;
2. CK1 path otherwise: `CK1|<discriminator.length>:<discriminator>|<arity>|<len>:<component>|...`.

For each fixed family the arity and discriminator are fixed. In the legacy path, all components after the first are colon-free, so the tuple is uniquely recoverable from the right even if the first component contains colons. If any later component contains `:`, CK1 is selected. CK1 is injective because discriminator length, arity and every component length are explicit before literal component content. `:`, `|`, backslash, whitespace, Unicode, strings resembling CK1, digits adjacent to length prefixes, multiple delimiters and long UIDs remain literal payload and cannot shift the length-defined boundaries.

Legacy and CK1 cannot collide because legacy keys start with a fixed family discriminator followed by `:`, while CK1 starts with `CK1|`. Cross-family CK1 keys remain distinct because discriminator is length-prefixed and embedded. Identical tuples deterministically produce identical conflict identity and therefore still conflict.

Single-component keys (`FIN_ACCOUNT`, `OWNERSHIP`, `PROJECT`) do not have tuple-boundary ambiguity.

## Asset identity — PASS

`AssetChange` carries `OwnedAssetRef(assetKindUid, assetUid)`. Both fields are validated, encoded, decoded and fingerprint-significant. ASSET and OWNED_ASSET conflict identities use the shared composite encoder. Same textual `assetUid` under different asset kinds remains distinct; same full tuple conflicts.

## Financial / ledger — PASS

Production validation enforces:

- exact equality of `fromAccountUid`, `toAccountUid`, `amountMinor`, `currencyUid`, `transactionTypeUid` between a causal FinancialChange and FINANCIAL_TRANSFER ledger intent;
- term mismatch -> `FINANCIAL_LEDGER_TERMS_MISMATCH`;
- dangling causal refs -> `INVALID_LEDGER_INTENT`;
- causal refs containing no FinancialChange -> `FINANCIAL_LEDGER_CAUSAL_CHANGE_REQUIRED`;
- one FinancialChange cannot be represented by multiple ledger intents -> `DUPLICATE_FINANCIAL_LEDGER_CAUSAL_CHANGE`;
- mixed financial/non-financial causal lists still consume the FinancialChange for uniqueness;
- standalone ledger intents with an empty causal list remain legal by explicit contract;
- independent non-conflicting FinancialChanges can each have their own matching ledger intent.

No bypass combination was found.

## Serialization — PASS

Production decode is fail-closed for:

- unknown root/nested fields;
- duplicate JSON object keys before parser collapse;
- escaped-equivalent duplicate keys;
- wrong String/numeric scalar types;
- quoted numerics;
- required nulls;
- object/array in scalar positions;
- unsupported ChangeSet schema version;
- unknown change kind;
- malformed nested refs/objects.

Legal canonical data preserves information through encode -> decode -> encode. Some malformed nested array element shapes may surface a library `IllegalArgumentException` from `.jsonObject` rather than `PlayerChangeSetStructuralException`; they are still rejected and no accepted semantic bypass/data loss was found. This is non-blocking error-normalization debt only.

## Fingerprint / canonicalization — FAIL because of ExactLongDelta blocker

For valid values constructed through the intended factories, canonical encoding is deterministic and SHA-256 fingerprint is stable. Semantic changes alter canonical bytes/fingerprint; list order is intentionally semantic.

However the `ExactLongDelta.copy(units = 0)` path below creates an object accepted by structural validation and encoding but rejected on decode. Therefore the global invariant that every accepted PlayerChangeSet has deterministic legal round-trip/fingerprint semantics is not fully satisfied.

## Immutability / aliasing — PASS

Root collections are defensive-copied using `Collections.unmodifiableList(ArrayList(values))`. Nested caller-owned lists are also copied for:

- DevelopmentProjectChange evidence refs;
- PlayerEventIntent target refs;
- PlayerEventIntent causal change UIDs;
- PlayerLedgerIntent causal change UIDs.

Other nested values are immutable value objects/scalars. Decode constructs fresh structures. Mutating caller-owned input lists after creation cannot change an existing PlayerChangeSet.

The ExactLongDelta issue is a construction-invariant bypass producing a new object via `copy()`, not mutation/aliasing of an existing ChangeSet.

## Duplicate / conflict handling — PASS

Fail-closed checks cover:

- duplicate `changeUid`;
- duplicate event intent UID;
- duplicate ledger intent UID;
- repeated semantic targets for stat/resource/skill/technique/innate/inventory/equipment/asset/owned-asset/condition/runtime;
- equipment slot conflicts;
- ownership record and owned-asset conflicts;
- dangling event/ledger causal refs;
- dangling warning `relatedChangeUid`;
- financial causal duplicate representation.

The shared composite encoder removes the previous delimiter-alias false conflicts for all multi-component target families inspected.

## Numeric correctness — FAIL

### Release blocker: P17-ROBUST-EXACT-DELTA-COPY-01

`ExactLongDelta` is declared as:

```kotlin
data class ExactLongDelta private constructor(val units: Long)
```

Its factory `ExactLongDelta.of(units)` rejects `units == 0`, but the class has no `init` invariant. Under the Kotlin version used by this repository, the generated data-class `copy()` currently exposes the private constructor; exact CI emits the compiler warning that the non-public primary constructor is exposed through generated `copy()` and that this behavior changes in a future language version.

Minimal reproducer:

```kotlin
val zero = ExactLongDelta.of(1).copy(units = 0)
val change = PlayerDomainChange.create(
    "CH-ZERO",
    PlayerChangeKinds.STAT,
    StatChange(DomainRef("PLAYER", "P1"), "STAT:STR", zero)
)
val set = PlayerChangeSet.create(
    changeSetUid = "CS-ZERO",
    campaignUid = "C1",
    sourceCommandUid = "CMD-ZERO",
    actor = CommandActorRef("PLAYER", "P1"),
    changes = listOf(change),
    provenance = ChangeSetProvenance("CMD-ZERO", "RPGOS-RESOLVER:TEST", "1")
)
val encoded = PlayerChangeSetCodec.encode(set) // accepted, deltaUnits = 0
PlayerChangeSetCodec.decode(encoded)            // rejects via ExactLongDelta.of(0)
```

Expected:

- zero delta cannot be constructed as a valid Phase-17 proposal value; or
- structural validation rejects it before a PlayerDomainChange/PlayerChangeSet is accepted.

Actual:

- generated `copy()` creates `ExactLongDelta(0)` without the factory guard;
- Stat/Resource/Skill/Technique/Inventory/Runtime/DevelopmentProject change validators validate refs/UIDs but do not re-check `delta.units != 0`;
- PlayerChangeSet construction and encode accept the zero delta;
- decode rejects the emitted canonical JSON because decode routes through `ExactLongDelta.of(0)`.

Impact:

- numeric invariant bypass;
- accepted in-memory proposal is not closed under canonical round-trip;
- fingerprint can be produced for a proposal the decoder considers structurally invalid.

Minimal correction scope is Phase 17 only: enforce the invariant in the value type itself and/or revalidate nonzero delta in every relevant change payload; add direct `copy(units=0)` regression coverage. No Phase-18 runtime is required.

Other numeric properties pass:

- `Math.addExact` / `Math.subtractExact` protect arithmetic overflow/underflow;
- Long parsing is exact and rejects out-of-range/non-integral/quoted numeric input;
- financial minor units remain exact Long values and positive;
- values above IEEE-754 exact integer boundary round-trip exactly because no Double/Float authority is used;
- ownership uses accepted fixed-scale `OwnershipShare` with exact integer units and BigInteger conversion.

## Architecture boundary — PASS

PlayerChangeSet remains a transient proposal contract. Phase-17 production files expose no functional `apply`, `commit`, `execute`, `save`, `persist`, StatePatch bridge, DB writer, DAO, repository/store mutation or transaction execution path. Event/ledger structures are intents only. No PlayerChangeSet persistence table/migration is introduced. Core change vocabulary remains world-agnostic; no Naruto/Bleach-specific semantics were found.

## Zero authoritative mutation — PASS

Construction, structural validation, conflict-key derivation, encode, decode, fingerprint and identity comparison have no authoritative writer dependency. Existing zero-mutation fixtures exercise the contract against SQLite authority sentinels; production inspection independently confirms no DB/store mutation path in PlayerChangeSet model/codec/registry.

## Regression / full JVM / exact CI — PASS

Exact GitHub Actions run:

- run number: `371`
- run ID: `31666619184`
- head SHA: `e8fce7187a92ffee846b9f60b06809343051a045`
- conclusion: `SUCCESS`

Successful steps include:

- Validate project;
- full JVM `gradle --no-daemon :app:testDebugUnitTest --stacktrace`;
- signed ALPHA APK build;
- release preparation;
- artifact upload;
- update of existing release assets;
- release information step.

The full JVM task completed `BUILD SUCCESSFUL` with all executed unit-test tasks green. Phase 3–16 regression tests are part of that full suite and representative Phase-16/Ownership/Finance checks are also present in Phase-17 tests.

An additional independent local Gradle rerun was attempted by CHAT-5, but the isolated audit container could not resolve `github.com` and therefore could not clone the repository. No local rerun is claimed. This is non-blocking because exact-SHA CI executes the requested full JVM command successfully; the verdict is based on production inspection plus exact CI, not CI alone.

# Gate summary

COMPOSITE TARGET IDENTITY: PASS
LEGACY / CK1 SEPARATION: PASS
ASSET IDENTITY: PASS
FINANCIAL / LEDGER: PASS
SERIALIZATION: PASS
FINGERPRINT: FAIL
IMMUTABILITY: PASS
DUPLICATE / CONFLICT HANDLING: PASS
NUMERIC CORRECTNESS: FAIL
ARCHITECTURE BOUNDARY: PASS
ZERO AUTHORITATIVE MUTATION: PASS
PHASE 3–16 REGRESSION: PASS
FULL JVM: PASS
EXACT CI: PASS

NON-BLOCKING OBSERVATIONS:

1. Some malformed nested array element shapes may fail with a library exception type rather than the Phase-17 structural exception family; they remain fail-closed and no semantic bypass was found.
2. Exact CI reports pre-existing Kotlin/compiler warnings outside this blocker; they do not invalidate the successful JVM/build execution. The `ExactLongDelta` private-constructor/copy warning is directly relevant to the blocker above.
3. Independent local JVM rerun was unavailable because the audit container could not resolve `github.com`; exact CI #371 ran the full JVM command successfully.

NEW CORRECTNESS PROBLEMS:

- `P17-ROBUST-EXACT-DELTA-COPY-01` — generated `ExactLongDelta.copy(units=0)` bypasses the factory's zero-delta invariant, is accepted by PlayerChangeSet validation/encode, and then fails decode.

# FINAL CHAT-5 VERDICT: FAIL

Phase 17 is not globally accepted by this report. Phase 18 remains blocked until the corrected exact runtime SHA receives independent PASS from CHAT-2, CHAT-3 and CHAT-5.
