# PHASE 17 INTEGRITY REVALIDATION — COMPOSITE CONFLICT IDENTITY HARDENING

ROLE: CHAT-3 — Integrity Auditor

VALIDATED RUNTIME SHA: `e8fce7187a92ffee846b9f60b06809343051a045`

Repository: `piotreksmaga-art/rpg-os-android`

Allowed write scope: this report only. No production/test/schema/workflow/runtime changes. Phase 18 not started.

# FINAL VERDICT

`PHASE 17 INTEGRITY REVALIDATION: PASS`

No release-blocking Phase-17 integrity defect was found in the exact target runtime.

## 1. Repository-first verification

Fresh master at audit start was exactly:

`e8fce7187a92ffee846b9f60b06809343051a045`

During the audit master advanced to:

`a4403593fee7933944ca50eb36914a5e0b1faf3b`

The only target..master change is report-only:

`docs/audits/WORK-20260813-P17_PLAYERCHANGESET_SEMANTIC_REVALIDATION_E8FCE71.md`

No newer Phase-17 production/test runtime exists after the target. Therefore:

`RUNTIME CHANGED AFTER TARGET: NO`

The target ancestry contains the previous `c205776...` candidate and the prior CHAT-3/CHAT-5 conflict-key FAIL reports, followed by production hardening commit `711b49adc62ddf1890c8c0358afe8ddd07725be7` and regression-suite commit `e8fce7187a92ffee846b9f60b06809343051a045`.

## 2. Production delta inspected

The production hardening is confined to:

`app/src/main/java/com/rpgos/app/PlayerChangeSetCodec.kt`

The former hand-built multi-component conflict-key strings for STAT, RESOURCE, SKILL, TECHNIQUE, INNATE, INVENTORY, EQUIPMENT, ASSET, OWNED_ASSET, CONDITION and RUNTIME now route through shared:

`compositeConflictKey(discriminator, vararg components)`

Single-component semantic identities remain separate and unambiguous by construction, including FIN_ACCOUNT, OWNERSHIP record UID and PROJECT UID keys.

No schema, DAO, writer, StatePatch or accepted Phase 3–16 authority implementation was modified.

## 3. Shared composite encoder — PASS

Current algorithm:

- legacy branch when `components.drop(1).all { ':' !in it }`:
  - `<discriminator>:<component0>:<component1>:...`
- CK1 branch otherwise:
  - `CK1|<discriminator.length>:<discriminator>|<componentCount>|<len0>:<component0>|...`

### Legacy branch injectivity

For every current legacy-eligible family, all components after component 0 are guaranteed colon-free by the branch predicate.

Thus the final `N-1` colon separators uniquely recover components 1..N-1 from right to left. Any additional colons can occur only in component 0 and therefore cannot shift boundaries among later components.

For arity 2, a colon-free component 1 means the final colon uniquely separates component 0 and component 1.

For arity 3, colon-free components 1 and 2 mean the last two colons uniquely separate them; all earlier colon content belongs to component 0.

Therefore two different legal ordered tuples within one discriminator cannot produce the same legacy key.

### CK1 branch injectivity

The CK1 representation includes:

- literal `CK1|` branch marker;
- decimal discriminator length;
- discriminator contents;
- explicit component count;
- decimal length for every component;
- exact component contents.

Equality of two CK1 strings therefore requires equality of discriminator length/content, component count, every component length and every component content. Different ordered tuples cannot collide.

### Kotlin `String.length` / Unicode

`String.length` counts UTF-16 code units. This is safe for identity generation because the encoded length and encoded component contents are both derived from the same immutable Kotlin `String`. Supplementary Unicode characters may count as two code units, but two equal encoded strings still require equal length digits and equal exact UTF-16 content. No normalization is performed by the helper.

### Separator-looking content

Characters and strings including:

- `:`
- `|`
- `\\`
- whitespace
- Unicode
- `CK1|`
- digit/colon sequences resembling encoded lengths

remain payload content. In CK1, their boundaries are determined by explicit lengths, not delimiter search.

Result: shared composite encoder is injective for the inspected legal ordered tuple domain.

## 4. Cross-branch collision safety — PASS

Legacy keys always start with the concrete discriminator followed by `:` (for example `STAT:`, `ASSET:`, `OWNED_ASSET:`).

CK1 keys always start with literal `CK1|`.

The current discriminator set is closed and contains no discriminator whose legacy prefix is `CK1|`. Therefore legacy and CK1 key spaces are disjoint.

A component containing literal `CK1|` cannot change the key branch prefix because it appears only after the already-emitted discriminator/branch structure.

Different discriminators cannot collide in legacy mode because their leading `<discriminator>:` prefixes differ. Different discriminators cannot collide in CK1 mode because discriminator length and exact discriminator contents are encoded before the component count.

Different component counts cannot collide in CK1 because the count is explicitly encoded.

## 5. All composite families — PASS

Verified production registry routes these multi-component identities through `compositeConflictKey(...)`:

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

No remaining manually concatenated multi-component conflict identity was found in the Phase-17 core registry.

The remaining conflict keys are semantically single-component:

- `FIN_ACCOUNT:<accountUid>` — one account identity;
- `OWNERSHIP:<ownershipRecordUid>` — one ownership-record identity;
- `PROJECT:<projectUid>` — one project identity.

Delimiter characters inside those single components do not create tuple-boundary ambiguity.

## 6. Composite regression tests — PASS

`PlayerChangeSetCompositeConflictIdentityHardeningTest` contains real production-path assertions for:

- exact STAT delimiter alias reproducer;
- same STAT tuple still conflicting;
- injectivity across every listed composite family;
- former delimiter alias shapes accepted as distinct tuples;
- same tuple still conflicting for every family;
- prior Asset hotfix alias regression;
- Property/Business same-UID asset identity regression;
- financial Hotfix2 causal uniqueness regression;
- single-component Ownership/Project key behavior;
- deterministic canonical serialization/fingerprint;
- zero authoritative DB mutation;
- representative Phase 3–16 checks.

The adversarial vectors include delimiter-bearing, Unicode, whitespace, pipe and backslash content.

## 7. Public validation paths — PASS

Public construction remains fail-closed:

- `PlayerDomainChange.create(...)` invokes `registry.validateChange(...)` before return;
- `PlayerChangeSet.create(...)` constructs privately then invokes `PlayerChangeSetValidator.validate(...)` before return;
- `PlayerChangeSetCodec.decode(...)` performs duplicate-key scanning, strict structural decode and final `PlayerChangeSet.create(...)` / validator execution;
- `PlayerChangeSetCodec.encode(...)` validates before canonical encoding;
- fingerprint calls validated encode;
- typed registry codec access is `internal`;
- typed codec `decode(JsonObject)` self-applies `pcsOnlyKeys(allowedKeys)` before `decodeKnownFields`.

No public construction/decode route was found that returns a legal ChangeSet while bypassing conflict detection or using a separate conflict-key implementation.

## 8. Immutability / aliasing — PASS

`PlayerChangeSet` root list inputs are copied through `immutableList`, implemented as:

`Collections.unmodifiableList(ArrayList(values))`

This prevents caller-owned mutable list aliasing for:

- changes;
- eventIntents;
- ledgerIntents;
- preconditions;
- warnings.

Nested list-bearing objects likewise defensively copy:

- `DevelopmentProjectChange.evidenceRefs`;
- `PlayerEventIntent.targetRefs`;
- `PlayerEventIntent.causalChangeUids`;
- `PlayerLedgerIntent.causalChangeUids`.

Other nested contract types are immutable value objects / data classes with `val` properties and no mutable collection fields in the inspected Phase-17 surface.

No Hotfix regression to equals/hash/fingerprint stability was found.

## 9. Strict codec — PASS

The production decoder remains fail-closed for legal semantic acceptance:

- root allowed-key enforcement;
- typed payload `allowedKeys` enforcement;
- nested object allowed-key enforcement;
- duplicate JSON object key scanning before parser-side collapse;
- escaped-equivalent duplicate keys decoded before duplicate comparison;
- strict JSON String type enforcement;
- strict Int/Long JSON numeric type enforcement;
- quoted numerics rejected;
- required null rejected where required;
- optional null remains explicit optional semantics;
- malformed/out-of-range integer values rejected;
- unsupported ChangeSet schema version rejected;
- unknown change kind rejected;
- payload-kind mismatch rejected;
- final validator invoked after decode.

The previously documented malformed nested-array shape case may surface as a library exception rather than the contract-specific structural exception family. It remains fail-closed: malformed input does not produce an accepted PlayerChangeSet, does not create canonical/fingerprint identity and does not mutate authority. Under the explicit audit instruction, this is not a release blocker.

## 10. Asset regression — PASS

Asset identity remains the full:

`OwnedAssetRef(assetKindUid, assetUid)`

for both `AssetChange` and `OwnershipChange` asset references.

Both dimensions survive model validation, codec round-trip and canonical serialization. The shared conflict helper now preserves the same full tuple for both `ASSET` and `OWNED_ASSET` conflict semantics.

The former false conflicts caused by colon-delimited flattening are resolved.

## 11. Financial regression — PASS

The validator still enforces:

- unique ledgerIntentUid;
- dangling causal ref rejection;
- non-financial-only causal ref rejection for a non-standalone financial ledger;
- standalone ledger legality with empty causalChangeUids;
- exact equality of causal FinancialChange and ledger terms:
  - fromAccountUid
  - toAccountUid
  - amountMinor
  - currencyUid
  - transactionTypeUid;
- term mismatch before duplicate-causal registration;
- global at-most-one ledger representation per FinancialChange.changeUid;
- deterministic `DUPLICATE_FINANCIAL_LEDGER_CAUSAL_CHANGE` on duplicate representation.

No Hotfix2 regression was found.

## 12. Canonicalization / fingerprint — PASS

Conflict keys are validator-internal and are not serialized into canonical PlayerChangeSet JSON.

Therefore changing only the internal conflict-key derivation does not alter canonical bytes or fingerprint for a legal proposal with unchanged semantic payload.

`PlayerChangeSetCodec.fingerprint(...)` remains SHA-256 over validated canonical encoding.

Legal encode -> decode -> encode remains deterministic in the regression suite, and a semantic payload change remains represented in canonical JSON and therefore changes the fingerprint.

## 13. Numeric / reference integrity — PASS

No numeric or reference regression was introduced by the conflict-key hardening.

- exact delta values remain `Long` through `ExactLongDelta`;
- addition/subtraction use `Math.addExact` / `Math.subtractExact` for checked overflow;
- zero ExactLongDelta remains rejected;
- finance amount remains exact Long minor units and must be positive;
- strict JSON Long/Int parsing rejects quoted numerics and malformed/out-of-range numbers;
- OwnershipShare retains accepted fixed-scale semantics from Phase 12;
- duplicate change/event/ledger IDs remain rejected;
- dangling causal refs and warning refs remain rejected;
- no Float/Double proposal authority was introduced.

## 14. Zero authoritative mutation — PASS

Construction, validation, conflict-key derivation, encode, decode, fingerprint and identity comparison contain no authoritative write path.

No Phase-17:

- SQL write;
- DAO writer;
- repository/store writer;
- StatePatch bridge;
- transaction commit;
- apply/execute/save/persist hook;
- ledger append;
- ownership write;
- inventory write;
- asset write;
- project write;
- event persistence

was introduced by this hardening.

The regression suite includes an explicit database fixture proving validate -> encode -> decode -> fingerprint -> conflict-key derivation does not alter an authoritative table.

## 15. Phase 3–16 regression — PASS

Production delta is confined to Phase-17 conflict-key derivation inside `PlayerChangeSetCodec.kt`.

Accepted Phase 3–16 schema/store/authority code is untouched.

The exact CI run executes the full JVM suite successfully, including prior regressions.

## 16. Full JVM / exact CI — PASS

Verified exact workflow:

- GitHub Actions run number: `371`
- run ID: `31666619184`
- workflow: `Build & Release RPG OS ALPHA`
- head SHA: `e8fce7187a92ffee846b9f60b06809343051a045`
- conclusion: `SUCCESS`

Successful job steps include:

- Validate project
- Run JVM unit tests (`:app:testDebugUnitTest` via workflow)
- Build signed ALPHA APK
- Prepare release files
- Upload Actions artifact
- Update existing GitHub Release assets
- Complete job

A separate local Gradle run was attempted from the audit container, but the environment cannot resolve `github.com` (`Could not resolve host: github.com`), so no independent local execution is claimed. This environmental limitation does not alter the exact-CI evidence above.

## 17. Gate summary

```text
ROLE: CHAT-3
VALIDATED RUNTIME SHA: e8fce7187a92ffee846b9f60b06809343051a045
FRESH MASTER BEFORE REPORT: a4403593fee7933944ca50eb36914a5e0b1faf3b
RUNTIME CHANGED AFTER TARGET: NO

SHARED COMPOSITE ENCODER: PASS
CROSS-BRANCH COLLISION SAFETY: PASS
ALL COMPOSITE FAMILIES: PASS
PUBLIC VALIDATION PATHS: PASS
IMMUTABILITY: PASS
STRICT CODEC: PASS
ASSET REGRESSION: PASS
FINANCIAL REGRESSION: PASS
FINGERPRINT: PASS
NUMERIC/REFERENCE INTEGRITY: PASS
ZERO AUTHORITATIVE MUTATION: PASS
PHASE 3–16 REGRESSION: PASS
FULL JVM: PASS (exact CI; local rerun unavailable due audit-environment DNS)
EXACT CI: PASS

NEW BLOCKERS: NONE
```

# FINAL CHAT-3 VERDICT

`PASS`

`PHASE 17 INTEGRITY REVALIDATION: PASS`

This report does not mark Phase 17 globally accepted. Phase 18 remains blocked pending the project's independent acceptance condition.