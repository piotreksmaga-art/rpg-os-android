# CHAT-5 — Phase 16 Final Robustness / Adversarial Revalidation

Role: READ-ONLY QUALITY / ROBUSTNESS AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Validated runtime SHA: `eef9fefc0ef394e366c4a1756939f97362f6d4db`
Fresh master observed at validation: `eef9fefc0ef394e366c4a1756939f97362f6d4db`
Exact CI: GitHub Actions `#337`, run ID `31595697673`, head SHA `eef9fefc0ef394e366c4a1756939f97362f6d4db`, SUCCESS.

## Verdict

`PHASE 16 ADVERSARIAL REVALIDATION: FAIL`

The two specifically targeted hotfixes work in their intended direction:

- strict JSON typing for fields semantically defined as String: PASS;
- duplicate JSON object-key rejection before ordinary parser collapse: PASS.

However the broader canonicalization/identity contract remains lossy for numeric fields. Numeric accessors still accept quoted JSON strings such as `"1"` / `"10"` because `reqInt()` / `reqLong()` use `jsonPrimitive.int` / `.long` without requiring `JsonPrimitive.isString == false`. Consequently a malformed serialized command can decode successfully and re-encode to a different canonical representation.

This is the same class of semantic-information loss that Phase 16 is intended to reject: two distinct serialized inputs collapse into one canonical typed command/fingerprint.

## 1. Fresh-master / candidate pinning

Fresh master at final check is exactly the target runtime SHA. No later runtime commit exists at validation time.

## 2. Exact CI

GitHub Actions #337 / run ID 31595697673 is green for exactly the target SHA. The workflow includes `Run JVM unit tests` and signed APK build, both successful.

Green CI does not cover the quoted-number adversarial case described below.

## 3. HOTFIX A — strict JSON String scalar typing

Status: PASS for the stated String-field contract.

`reqString()` and `optString()` now:

- reject JsonNull where required;
- require JsonPrimitive;
- require `primitive.isString == true`;
- return `INVALID_JSON_STRING_TYPE` otherwise.

Representative protected surfaces include root identities, actor, provenance, DomainRef, payload String fields, precondition String fields, extension String fields and optional String fields.

The P16-HOTFIX2 suite checks representative number/boolean/object/array attacks against String fields.

## 4. HOTFIX B — duplicate JSON object keys

Status: PASS.

`PlayerCommandRegistry.decode()` calls `rejectDuplicateJsonObjectKeys(serialized)` before `Json.parseToJsonElement()`.

`StrictJsonDuplicateKeyScanner`:

- tracks seen keys per object;
- recursively scans nested objects and arrays;
- decodes object-name string tokens before comparison;
- therefore treats escaped-equivalent names such as `commandUid` and `\u0063ommandUid` as the same key;
- keeps string contents isolated from structural parsing;
- rejects duplicate known and duplicate unknown object members with `DUPLICATE_JSON_OBJECT_KEY`.

The scanner behavior is consistent with required handling of identical/different duplicate values, nested duplicates, escaped strings and nested structures.

## 5. P16-HOTFIX2-01..25

Status: PASS as implemented / covered by exact CI #337.

The suite exercises:

- wrong scalar type for representative String fields;
- root/nested duplicate keys;
- escaped-equivalent duplicate names;
- extension version matrix;
- deterministic encode/decode/fingerprint;
- unknown semantic field rejection;
- zero authoritative mutation.

The suite does not exercise quoted numeric values for numeric fields.

## 6. Previous P16-HOTFIX-01..12

Status: PASS as implemented / covered by exact CI #337.

Unknown-field rejection and extension-version restrictions remain present after HOTFIX2.

## 7. CMD-SEM suite

Status: PASS as implemented / covered by exact CI #337.

Stable command identity, kind/payload mismatch, unknown kinds, typed references, deterministic serialization and no-mutation checks remain intact.

## 8. Release blocker — numeric scalar canonicalization

Attack ID: `P16-ROBUST-NUMERIC-01`

### Violated invariant

Serialized PlayerCommand must not accept a malformed alternate JSON scalar representation that decodes to the same typed command and then re-encodes differently. Semantic identity/fingerprint must not be produced after lossy normalization of malformed input.

### Root cause

Current helpers remain:

```kotlin
internal fun JsonObject.reqInt(k: String): Int =
    try { this[k]?.jsonPrimitive?.int ?: error("missing") }
    catch (_: Throwable) { throw PlayerCommandStructuralException("MISSING_$k") }

internal fun JsonObject.reqLong(k: String): Long =
    try { this[k]?.jsonPrimitive?.long ?: error("missing") }
    catch (_: Throwable) { throw PlayerCommandStructuralException("MISSING_$k") }

internal fun JsonObject.optLong(k: String): Long? =
    this[k]?.takeUnless { it is JsonNull }?.jsonPrimitive?.long
```

They do not reject `JsonPrimitive.isString == true`.

In kotlinx.serialization JSON, `.int` and `.long` parse the primitive `content`; the quoted JSON string `"1"` therefore has content `1` and is accepted as an integer/long value.

### Minimal reproducer A — root schemaVersion

Start from any valid canonical encoded command and replace:

```json
"schemaVersion":1
```

with:

```json
"schemaVersion":"1"
```

Expected:

```text
decode => deterministic structural reject
```

Actual:

```text
reqInt("schemaVersion") parses string content "1" as Int 1
schema check passes
decode succeeds
re-encode emits "schemaVersion":1
```

The attacker input is therefore not round-trip stable and is silently canonicalized.

### Minimal reproducer B — payload Long

For a TRAIN command replace:

```json
"effortUnits":10
```

with:

```json
"effortUnits":"10"
```

Expected: reject wrong JSON scalar type.

Actual: payload decoder reaches `reqLong()`; quoted `"10"` parses to Long 10, validation succeeds, and canonical encode emits numeric `10`.

### Additional affected numeric surfaces

The same helper class potentially affects:

- schemaVersion;
- requestedEffectiveOrder via `optLong`;
- precondition expectedVersion;
- effort units;
- requested amounts/quantities/shares;
- principal/settlement amounts;
- project requestedProgressCapUnits;
- extension schemaVersion uses reqInt (although unsupported values are separately checked after parse).

Thus this is not a single-field fixture defect.

### Expected vs actual

Expected:

```text
JSON number fields accept actual JSON number primitives only.
Quoted numeric strings are rejected deterministically.
No malformed serialized input may decode to a canonical command by changing scalar representation.
```

Actual:

```text
Quoted numeric string -> parsed numeric typed value -> canonical numeric output.
Distinct serialized inputs collapse to one canonical command/fingerprint.
```

### Minimal Phase-16-only correction scope

Harden numeric JSON helpers, analogously to String helpers:

- required Int/Long: require JsonPrimitive and `isString == false`, reject booleans/other non-numeric primitives and quoted numeric strings with a stable structural error;
- optional Long: same when present/non-null;
- add regression tests for root, payload, precondition, optional numeric field and extension schemaVersion using quoted numerics;
- preserve existing exact numeric bounds and deterministic canonical encoding.

No Phase 17 implementation is required.

## 9. Unknown-field contract

Status: PASS.

Strict allowed-key validation remains active on root, payload, actor, provenance, DomainRef, precondition and extension objects.

## 10. Extension version contract

Status: PASS.

`NamespacedTextCommandExtension` accepts exactly schemaVersion 1. `-1`, `0`, `2`, `999`, `Int.MAX_VALUE` are rejected by decode and direct validate paths.

## 11. Duplicate-key scanner validity / false positives

Status: PASS for reviewed cases.

The scanner distinguishes structure from string content, recursively handles arrays/objects, respects JSON escapes in string tokens and compares decoded member names. Braces, commas, colons and escaped quotes inside JSON strings are consumed by `readStringToken()` rather than interpreted as structure.

## 12. Valid-input regression

Status: PASS for the hotfix-specific valid-string/duplicate-key behavior, but overall canonicalization gate FAIL because malformed quoted numeric values are accepted rather than rejected.

Canonical valid commands and supported extension v1 round-trip deterministically in the existing suites.

## 13. Zero authoritative mutation

Status: PASS.

Phase-16 construct / validate / encode / decode / fingerprint remain transient contract operations. Existing no-mutation tests snapshot authoritative Phase 3–15 tables before/after these operations.

No Phase-16 command table, queue, inbox, outbox or execution ledger is introduced by this hotfix.

## 14. Phase boundary

Status: PASS.

No PlayerChangeSet, PlayerDomainEngine, WorldRuleProvider, ProgressionEngine or command execution authority is introduced by the target hotfix.

PlayerCommand remains a typed transient intent/request contract.

## 15. Phase 3–15 regression

Status: PASS based on the exact full JVM suite and unchanged Phase 3–15 runtime surfaces in the hotfix scope.

The blocker is isolated to Phase-16 serialized numeric scalar strictness/canonicalization.

## Final matrix

```text
ROLE: CHAT-5
VALIDATED RUNTIME SHA: eef9fefc0ef394e366c4a1756939f97362f6d4db
FRESH MASTER: eef9fefc0ef394e366c4a1756939f97362f6d4db
EXACT CI: #337 / 31595697673 / SUCCESS

STRICT JSON TYPES: FAIL overall
  String-defined fields: PASS
  Numeric-defined fields: FAIL (quoted-number acceptance)

DUPLICATE JSON KEYS: PASS
UNKNOWN FIELD CONTRACT: PASS
EXTENSION VERSION CONTRACT: PASS
P16-HOTFIX2-01..25: PASS
PREVIOUS P16-HOTFIX-01..12: PASS
CMD-SEM: PASS
CANONICALIZATION / IDENTITY: FAIL
VALID INPUT REGRESSION: PASS for valid inputs reviewed
ZERO AUTHORITATIVE MUTATION: PASS
PHASE 3–15 REGRESSION: PASS
ROLE-SPECIFIC VERDICT: FAIL

PHASE 16 ADVERSARIAL REVALIDATION: FAIL
PHASE 17: BLOCKED
```

This audit is report-only and does not modify production/test runtime.
