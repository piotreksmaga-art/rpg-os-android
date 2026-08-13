# PHASE 17 INTEGRITY REVALIDATION — VALUE-INVARIANT HARDENING

ROLE: CHAT-3 — Integrity Auditor

VALIDATED RUNTIME SHA: `a13e9c795251da5d2fb241c5ff58e38a2d5b5f87`

Repository: `piotreksmaga-art/rpg-os-android`

Allowed write scope: this report only. No production/test/schema/workflow/runtime changes. Phase 18 not started.

# FINAL VERDICT

`PHASE 17 INTEGRITY REVALIDATION: PASS`

No release-blocking Phase-17 integrity defect was found in the exact target runtime.

## 1. Runtime pin / history

At audit start, fresh master was exactly the target runtime:

`a13e9c795251da5d2fb241c5ff58e38a2d5b5f87`

During the audit, master advanced to:

`fd312d32f56a03d4c52f23390cd81feee182a664`

The exact target..master delta is one report-only file:

`docs/audits/WORK-20260813-P17_PLAYERCHANGESET_SEMANTIC_REVALIDATION_A13E9C7.md`

No newer Phase-17 production/test runtime exists after the target.

Therefore:

`RUNTIME CHANGED AFTER TARGET: NO`

The target ancestry includes:

- prior accepted-in-scope composite conflict identity hardening runtime `e8fce718...`;
- production hotfix `115f885674dc44387434e07baa482cada991ec1f` enforcing the ExactLongDelta invariant at constructor boundary;
- target `a13e9c795251da5d2fb241c5ff58e38a2d5b5f87` adding the exact-value invariant regression suite.

Production delta relative to `e8fce718...` is confined to `PlayerChangeSetModel.kt` ExactLongDelta constructor hardening. The target additionally adds `PlayerChangeSetValueInvariantHardeningTest.kt` and inherited report-only audit files. Accepted Phase 3–16 schema/store authorities are untouched.

## 2. ExactLongDelta structural invariant — PASS

Production implementation:

```kotlin
data class ExactLongDelta private constructor(val units: Long) {
    init {
        if (units == 0L) throw PlayerChangeSetStructuralException("ZERO_DELTA")
    }

    fun plus(other: ExactLongDelta): ExactLongDelta = of(Math.addExact(units, other.units))

    companion object {
        fun of(units: Long): ExactLongDelta {
            if (units == 0L) throw PlayerChangeSetStructuralException("ZERO_DELTA")
            return ExactLongDelta(units)
        }

        fun between(previous: Long, proposed: Long): ExactLongDelta =
            of(Math.subtractExact(proposed, previous))
    }
}
```

The previous vulnerability was:

```kotlin
ExactLongDelta.of(1).copy(units = 0)
```

Before the hotfix, generated `copy` could call the private primary constructor directly without repeating factory-only validation.

The invariant is now constructor-level via `init`. Kotlin data-class generated `copy(...)` invokes the primary constructor, therefore `copy(units = 0)` executes the same init block and deterministically throws `PlayerChangeSetStructuralException("ZERO_DELTA")`.

The generated `component1()`, equality and hash behavior only expose/compare already-constructed `units`; they do not construct alternate instances and cannot bypass the invariant.

Factory `of(0)` remains rejected. `between(...)` routes through `of(...)`. `plus(...)` routes through checked `Math.addExact` and then `of(...)`.

Required result:

- `of(1)` => ACCEPT
- `of(-1)` => ACCEPT
- `of(0)` => REJECT
- `of(1).copy(units=0)` => REJECT
- original legal instance remains unchanged after failed copy
- no supported/generated API inspected can create `ExactLongDelta(units=0)`.

Verdict: PASS.

## 3. Same-class / value-object bypass search — PASS

The Phase-17 model was inspected for the pattern:

- data/value class;
- restricted constructor;
- semantic validation only in factory;
- generated or supported construction path bypassing equivalent validation.

### ExactLongDelta

Closed as described above.

### OwnershipShare

Phase 17 reuses the accepted Phase-12 fixed-scale `OwnershipShare` value object. It independently has constructor-level validation:

```kotlin
data class OwnershipShare private constructor(val units: Long) {
    init {
        require(units in 1..OWNERSHIP_SHARE_SCALE)
    }
    ...
}
```

Therefore generated `copy` also executes the invariant.

Verified semantics:

- zero => rejected;
- negative => rejected;
- `OWNERSHIP_SHARE_SCALE + 1` => rejected;
- exact full share => accepted;
- `copy(units=0)` => rejected;
- `copy(units=scale+1)` => rejected;
- `copy()` preserving the same value => accepted;
- `add` uses `Math.addExact` then constructor validation;
- `subtract` uses `Math.subtractExact`, returns null for exact zero remainder, otherwise constructor validation;
- fractions use BigInteger exact representability and constructor validation;
- no Float/Double constructor exists.

### Other Phase-17 typed payloads

StatChange, ResourceChange, SkillChange, TechniqueChange, InnateChange, InventoryChange, EquipmentChange, FinancialChange, AssetChange, OwnershipChange, ConditionChange and RuntimeChange have public immutable value constructors, but their semantic validity is intentionally enforced at the typed `PlayerDomainChange.create(...)` / registry / PlayerChangeSet boundary rather than through a private-factory-only invariant susceptible to generated-copy bypass.

No second equivalent restricted-constructor/factory-only data-class bypass was found.

DevelopmentProjectChange, PlayerDomainChange, PlayerEventIntent, PlayerLedgerIntent and PlayerChangeSet use private constructors and are normal classes rather than Kotlin data classes; they therefore have no generated `copy` escape. DevelopmentProjectChange defensively copies evidence refs. PlayerDomainChange and PlayerChangeSet validate before returning from their public canonical create paths.

Verdict: PASS.

## 4. In-memory <-> serialized closure — PASS

Required property:

`valid supported in-memory proposal -> canonical encode -> strict decode -> equivalent valid proposal`

The production encoder invokes `PlayerChangeSetValidator.validate(...)` before serialization. Thus an in-memory composition that violates typed payload, numeric, reference, conflict, finance/ledger, warning, event or root invariants cannot acquire canonical serialized identity.

The decoder reconstructs ExactLongDelta-backed fields through `ExactLongDelta.of(pcsReqLong(...))`, which enforces the same non-zero invariant as in-memory construction.

Inspected ExactLongDelta-backed change families:

- StatChange
- ResourceChange
- SkillChange
- TechniqueChange
- InventoryChange
- RuntimeChange
- DevelopmentProjectChange

The target regression suite round-trips these legal paths. Long.MAX_VALUE and Long.MIN_VALUE legal non-zero deltas survive encode/decode without Float/Double conversion.

Other typed change families remain subject to the same registry validation before canonical encoding and after strict decoding:

- InnateChange
- EquipmentChange
- FinancialChange
- AssetChange
- OwnershipChange
- ConditionChange.

No supported construction path was found that can create an object which `PlayerChangeSetCodec.encode()` accepts but strict decode rejects solely because the in-memory side bypassed a structural invariant.

Verdict: PASS.

## 5. Public construction / validation paths — PASS

Inspected public/canonical routes:

- `PlayerDomainChange.create(...)` constructs privately then calls `registry.validateChange(...)` before return;
- `PlayerChangeSet.create(...)` constructs privately then calls `PlayerChangeSetValidator.validate(...)` before return;
- `PlayerChangeSetCodec.encode(...)` validates before canonical serialization;
- `PlayerChangeSetCodec.decode(...)` performs duplicate-key scan, strict structural extraction, typed decode, private/canonical factories and final validation;
- `PlayerChangeSetCodec.fingerprint(...)` calls validated encode;
- `PlayerChangeSetIdentity.compare(...)` compares validated fingerprints;
- typed codec access remains `internal` and typed codec decode self-applies allowed-key validation.

PlayerEventIntent.create and PlayerLedgerIntent.create create immutable nested proposal objects, but they do not constitute a canonical ChangeSet authority or standalone encoder/fingerprint identity. Any invalid nested intent is rejected when included in `PlayerChangeSet.create`, encode or decode. No public route was found that turns such an invalid nested object into an accepted canonical PlayerChangeSet while bypassing validation.

Verdict: PASS.

## 6. Immutability / aliasing — PASS

PlayerChangeSet root collection inputs are copied with:

`Collections.unmodifiableList(ArrayList(values))`

for:

- changes;
- eventIntents;
- ledgerIntents;
- preconditions;
- warnings.

Nested list-bearing structures likewise defensively copy:

- DevelopmentProjectChange.evidenceRefs;
- PlayerEventIntent.targetRefs;
- PlayerEventIntent.causalChangeUids;
- PlayerLedgerIntent.causalChangeUids.

Other inspected nested records/value objects expose immutable `val` fields and no public mutable collection authority.

Decoded structures are constructed through these same factories/copying constructors.

No caller-owned mutable list alias can mutate an already-created ChangeSet through the inspected contract surface.

Verdict: PASS.

## 7. Strict codec — PASS

The production codec continues to enforce:

- root allowed-key set;
- typed payload allowed-key set;
- nested allowed-key checks;
- duplicate JSON object keys rejected before parser collapse;
- escaped-equivalent duplicate keys normalized before duplicate comparison;
- true JSON String required for String fields;
- true JSON numeric primitive required for Int/Long fields;
- quoted numerics rejected;
- booleans rejected as numeric values;
- object/array misuse fails closed;
- required null fails required-field semantics;
- optional null follows explicit optional semantics;
- malformed/out-of-range Long/Int rejected;
- unsupported ChangeSet schema rejected;
- unknown change kinds rejected;
- change payload type mismatch rejected;
- final validation after decode.

As previously documented, some malformed nested array shapes may surface as a library exception family rather than a contract-specific structural exception. This remains fail-closed and does not yield accepted lossy semantics, canonical identity or authority mutation; under the current user instruction this is non-blocking.

Verdict: PASS.

## 8. Composite conflict identity — PASS

The inherited shared `compositeConflictKey(...)` remains unchanged by value hardening.

Multi-component families verified to route through it:

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
- RUNTIME.

The helper retains two disjoint forms:

- legacy `<discriminator>:...` only when all components after the first are colon-free;
- `CK1|...` length-prefixed representation otherwise.

Delimiter-bearing, Unicode, whitespace, pipe, backslash, CK1-looking and encoded-length-looking component content does not alter tuple boundaries.

The prior STAT alias reproducer remains accepted as two distinct semantic targets.

Verdict: PASS.

## 9. Legacy / CK1 collision safety — PASS

Legacy branch starts with the concrete discriminator followed by `:`.

CK1 branch starts with literal `CK1|` and contains:

- discriminator length/content;
- component count;
- each component length/content.

The current closed discriminator set does not produce a legacy prefix equal to `CK1|`.

Legacy branch is injective because every component after component 0 is colon-free; boundaries can therefore be recovered unambiguously from right to left.

CK1 equality requires the same discriminator, arity, component lengths and exact component contents.

Kotlin `String.length` uses UTF-16 code units, but the emitted length and emitted exact String contents are derived from the same immutable String; this does not create identity aliasing.

Verdict: PASS.

## 10. Asset integrity — PASS

AssetChange and OwnershipChange preserve full generic asset identity:

`OwnedAssetRef(assetKindUid, assetUid)`.

Both dimensions survive:

- in-memory model;
- validation;
- nested codec;
- canonical serialization;
- round-trip;
- fingerprint;
- conflict identity.

The value-invariant hotfix does not touch this surface and target regressions retain the previous delimiter/cross-kind tests.

Verdict: PASS.

## 11. Financial / ledger integrity — PASS

No regression found in Hotfix2 invariants.

The validator still enforces:

- positive exact Long minor-unit amount;
- nonblank source/destination/currency/type;
- source != destination;
- exact equality of causal FinancialChange and FinancialTransferLedgerIntent terms:
  - fromAccountUid;
  - toAccountUid;
  - amountMinor;
  - currencyUid;
  - transactionTypeUid;
- term mismatch before duplicate-causal registration;
- global at-most-one ledger representation per FinancialChange.changeUid;
- standalone ledger legality when causalChangeUids is empty;
- dangling causal refs rejected;
- non-financial-only causal refs rejected.

Target regression suite includes a legal exact finance/ledger round-trip.

Verdict: PASS.

## 12. Duplicate / reference integrity — PASS

Rechecked validator enforcement for:

- duplicate changeUid;
- duplicate eventIntentUid;
- duplicate ledgerIntentUid;
- semantic target conflicts;
- equipment target conflicts;
- inventory target conflicts;
- ownership record/asset conflicts;
- condition conflicts;
- runtime counter conflicts;
- dangling event causal change refs;
- dangling ledger causal change refs;
- dangling warning.relatedChangeUid;
- invalid precondition refs;
- invalid nested DomainRefs.

No value hardening regression found.

Verdict: PASS.

## 13. Numeric integrity — PASS

Verified:

- ExactLongDelta non-zero invariant is constructor-level;
- Long.MAX_VALUE and Long.MIN_VALUE are valid non-zero deltas and round-trip exactly;
- addition uses Math.addExact;
- subtraction uses Math.subtractExact;
- overflow/underflow fail rather than wrap;
- financial amounts use Long minor units and require > 0;
- OwnershipShare remains fixed-scale exact Long units;
- OwnershipShare range is constructor-level and generated-copy safe;
- quoted numerics fail strict decoding;
- malformed/out-of-range JSON integer values fail;
- no Float/Double authority was introduced into Phase-17 proposal semantics.

Verdict: PASS.

## 14. Fingerprint / canonical identity — PASS

`PlayerChangeSetCodec.encode(...)` validates first and canonicalizes the semantic payload.

`fingerprint(...)` is SHA-256 over that validated canonical encoding.

Therefore an invalid supported object cannot obtain a canonical legal fingerprint through the public fingerprint path.

Target regression covers:

- encode -> decode -> encode determinism;
- deterministic fingerprint after round-trip;
- Long boundary preservation.

Validator-internal conflict keys are not serialized into canonical JSON and therefore internal conflict-identity hardening does not alter fingerprint for unchanged legal semantic payload.

Verdict: PASS.

## 15. Authority boundary — PASS

Phase 17 remains proposal-only.

No Phase-17 contract path inspected contains:

- apply;
- commit;
- execute;
- persist/save;
- StatePatch mutation bridge;
- DAO writer;
- SQLite writer;
- TurnTransaction execution;
- PlayerDomainEngine authoritative execution;
- ledger append;
- ownership write;
- inventory write;
- asset write;
- project write;
- event persistence.

The ExactLongDelta hotfix changes only in-memory value construction semantics.

Verdict: PASS.

## 16. Zero authoritative mutation — PASS

Construction, validation, strict decode, canonical encode, composite conflict-key derivation, fingerprint and identity comparison remain read-only with respect to authoritative state.

Target regression creates an SQLite authority fixture, executes proposal construction/encode/decode/fingerprint, and verifies the fixture remains unchanged.

No write hook was added by the production delta.

Verdict: PASS.

## 17. Phase 3–16 regression — PASS

Production delta is six lines in Phase-17 `PlayerChangeSetModel.kt` around ExactLongDelta constructor validation.

No Phase 3–16 schema/store/authority code is changed.

Target regression includes representative PlayerCommand round-trip and OwnershipShare invariant checks, and exact CI executes the full JVM suite.

Verdict: PASS.

## 18. Full JVM / exact CI — PASS

Verified exact GitHub Actions workflow:

- run number: `375`
- run ID: `31668369509`
- workflow: `Build & Release RPG OS ALPHA`
- head SHA: `a13e9c795251da5d2fb241c5ff58e38a2d5b5f87`
- conclusion: `SUCCESS`

Successful job steps include:

- Checkout source
- Validate project
- Run JVM unit tests (`:app:testDebugUnitTest` through workflow)
- Build signed ALPHA APK
- Prepare release files
- Upload Actions artifact
- Update existing GitHub Release assets
- Complete job.

A separate local `:app:testDebugUnitTest` execution could not be performed because the audit container does not contain a repository checkout and prior attempts to acquire dependencies/source in this environment are blocked by external DNS/network resolution. No local execution is claimed. Exact CI #375 provides the full-JVM execution evidence for the exact target SHA.

Verdict: PASS.

## 19. Gate summary

```text
ROLE: CHAT-3
VALIDATED RUNTIME SHA: a13e9c795251da5d2fb241c5ff58e38a2d5b5f87
FRESH MASTER BEFORE REPORT: fd312d32f56a03d4c52f23390cd81feee182a664
RUNTIME CHANGED AFTER TARGET: NO

EXACTLONGDELTA STRUCTURAL INVARIANT: PASS
OTHER VALUE-OBJECT BYPASS: PASS
IN-MEMORY/SERIALIZED CLOSURE: PASS
PUBLIC VALIDATION PATHS: PASS
IMMUTABILITY/ALIASING: PASS
STRICT CODEC: PASS
COMPOSITE CONFLICT IDENTITY: PASS
LEGACY/CK1 COLLISION SAFETY: PASS
ASSET INTEGRITY: PASS
FINANCIAL/LEDGER INTEGRITY: PASS
DUPLICATE/REFERENCE INTEGRITY: PASS
NUMERIC INTEGRITY: PASS
FINGERPRINT: PASS
AUTHORITY BOUNDARY: PASS
ZERO AUTHORITATIVE MUTATION: PASS
PHASE 3–16 REGRESSION: PASS
FULL JVM: PASS (exact CI; no separate local checkout available)
EXACT CI: PASS

NEW BLOCKERS: NONE
```

# FINAL CHAT-3 VERDICT

`PASS`

`PHASE 17 INTEGRITY REVALIDATION: PASS`

This report does not mark Phase 17 globally accepted. Phase 18 remains blocked until three independent PASS results exist for exactly `a13e9c795251da5d2fb241c5ff58e38a2d5b5f87`.
