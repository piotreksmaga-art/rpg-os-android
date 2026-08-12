# CHAT-3 — Phase 16 Final Integrity Revalidation

Role: CHAT-3 / READ-ONLY INTEGRITY / CONTRACT AUDITOR

Repository: `piotreksmaga-art/rpg-os-android`

Validated runtime SHA: `eef9fefc0ef394e366c4a1756939f97362f6d4db`

Exact CI: GitHub Actions `#337`, run ID `31595697673`, head SHA `eef9fefc0ef394e366c4a1756939f97362f6d4db`, `SUCCESS`

Previous candidate: `940750119a24381d53361101be1f8957a508c9e9`

Allowed write scope: this report only. No production/test runtime modification. Phase 17 remains blocked.

# PHASE 16 INTEGRITY REVALIDATION: FAIL

The exact candidate correctly closes the two blockers explicitly targeted by HOTFIX2 in their stated direction:

- String-defined PlayerCommand fields now reject non-string JSON scalar/object/array forms;
- duplicate JSON object member names are rejected before `Json.parseToJsonElement()` can collapse them, including escaped-equivalent names.

However the full serialized contract remains lossy for numeric fields. `reqInt()`, `reqLong()` and `optLong()` still use `jsonPrimitive.int` / `.long` without requiring `JsonPrimitive.isString == false`. With the repository's `kotlinx-serialization-json:1.9.0`, these accessors parse primitive `content`, so quoted numeric strings such as `"1"` and `"10"` are accepted as numeric values. A malformed serialized command can therefore decode successfully and then canonicalize to a different JSON scalar representation, collapsing distinct serialized inputs into one typed command/fingerprint.

This is a release blocker for Phase-16 serialized contract integrity and canonical identity.

---

## 1. Fresh master / target pinning

Fresh master at audit start resolved to exactly:

`eef9fefc0ef394e366c4a1756939f97362f6d4db`

Before report write, fresh master had advanced only by report-only commits:

- CHAT-2 semantic revalidation for the same runtime;
- CHAT-5 adversarial/robustness revalidation for the same runtime.

No later Phase-16 runtime candidate was found. Therefore this audit remains pinned to `eef9fefc...`.

---

## 2. Canonical architecture / Phase boundary

MASTER preserves the single legal mutation path:

`PROPOSAL -> DOMAIN/RULE RESOLUTION -> CHANGE SET -> VALIDATION -> TRANSACTION -> COMMIT`.

Phase 16 owns the transient typed `PlayerCommand` intent contract only. It does not own persistence, domain execution, PlayerChangeSet, transaction commit, StatePatch authority, event/ledger authority or Phase-17 runtime.

The HOTFIX2 delta remains within this boundary.

---

## 3. Exact hotfix delta

Compare `9407501... -> eef9fef...` shows Phase-16 production changes only in:

- `app/src/main/java/com/rpgos/app/PlayerCommandRegistry.kt`;
- `app/src/main/java/com/rpgos/app/PlayerCommandStrictJson.kt`.

Test addition:

- `app/src/test/java/com/rpgos/app/PlayerCommandAdversarialHotfix2Test.kt`.

Other changes between candidates are report-only audit files. No Phase-3–15 authoritative writer/schema/migration or Phase-17 runtime file is changed.

---

# HOTFIX A — STRICT JSON TYPES

## 4. String-defined scalar fields — PASS

`reqString()` and `optString()` now explicitly require:

- a `JsonPrimitive`;
- `primitive.isString == true`.

Required/non-null String fields reject number, boolean, object and array values with `INVALID_JSON_STRING_TYPE` rather than normalizing primitive `content` into a String.

This protects the full String decode surface because root IDs, actor fields, provenance fields, DomainRef fields, payload String fields, precondition String fields and extension String fields all pass through `reqString()` / `optString()`.

Representative result:

```json
"commandUid":123
```

=> deterministic `INVALID_JSON_STRING_TYPE`.

The previous forbidden normalization to `"commandUid":"123"` is closed.

## 5. Numeric-defined scalar fields — FAIL

The numeric helpers remain:

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

The project depends on `org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0`. In that version, `JsonPrimitive.int` / `long` parse the primitive's `content`; `JsonPrimitive.isString` is a separate property and is not consulted by those numeric accessors.

Therefore a quoted numeric string can be accepted as an Int/Long and then re-encoded as an actual JSON number.

This violates strict scalar typing and canonical serialization integrity.

---

# RELEASE BLOCKER

## 6. Blocker ID: P16-INT-NUMERIC-01

### Violated invariant

A serialized `PlayerCommand` must not accept an alternate/malformed JSON scalar representation that changes type during decode -> canonical encode.

Typed numeric fields must require JSON numeric primitives. Quoted numeric strings must not be silently normalized to numbers.

### Minimal reproducer A — root `schemaVersion`

Start from any valid canonical PlayerCommand JSON:

```json
"schemaVersion":1
```

replace with:

```json
"schemaVersion":"1"
```

### Expected

Deterministic structural reject before creation of a semantic command/fingerprint.

### Actual

`reqInt("schemaVersion")` receives a string `JsonPrimitive` whose `content` is `1`; `.int` parses it as integer 1. The schema-version equality check passes. Decode succeeds. Canonical re-encode emits:

```json
"schemaVersion":1
```

Thus input scalar type information disappears.

### Minimal reproducer B — payload `effortUnits`

For a TRAIN command replace:

```json
"effortUnits":10
```

with:

```json
"effortUnits":"10"
```

### Expected

Deterministic wrong-scalar-type reject.

### Actual

The TRAIN payload decoder reaches `reqLong("effortUnits")`; quoted string content `10` parses as Long 10, structural validation succeeds, and canonical encode emits numeric `10`.

### Additional affected numeric surfaces

The same helper family is used or can be used for:

- command `schemaVersion`;
- `requestedEffectiveOrder`;
- precondition `expectedVersion`;
- effort units;
- amounts / quantities / shares;
- principal / settlement amounts;
- project progress cap intent;
- extension `schemaVersion` before version-policy check.

Thus this is a contract-surface issue, not a single-field defect.

### Canonicalization / identity consequence

Two distinct serialized inputs:

```json
{"schemaVersion":1,...}
```

and

```json
{"schemaVersion":"1",...}
```

can decode to the same typed `PlayerCommand`, after which canonical encode/fingerprint operate on the same normalized representation.

The decoder therefore still permits lossy semantic normalization of malformed input.

### Minimal correction scope

Phase 16 only:

1. harden required Int/Long helpers to require a non-string `JsonPrimitive` before parsing;
2. harden optional Long similarly when present/non-null;
3. use stable structural wrong-numeric-type error(s);
4. add regression cases for quoted numeric values at root, payload, precondition, optional numeric field and extension schemaVersion;
5. retain existing numeric bounds/sign validation and canonical encoding.

No persistence, schema, Phase-17 or domain-authority change is required.

---

# HOTFIX B — DUPLICATE JSON OBJECT KEYS

## 7. Pre-parse duplicate-key boundary — PASS

`PlayerCommandKindRegistry.decode(serialized)` calls `rejectDuplicateJsonObjectKeys(serialized)` before `Json.parseToJsonElement()`.

The scanner keeps a `seen` set per object and recursively scans nested arrays/objects.

Object names are decoded as JSON string tokens before comparison, so:

```json
"commandUid":...
"\u0063ommandUid":...
```

are treated as the same key.

The scanner therefore rejects:

- duplicate known root key;
- duplicate unknown root key;
- identical duplicate values;
- conflicting duplicate values;
- nested object duplicates;
- duplicates inside objects contained in arrays;
- escaped-equivalent member names.

## 8. Scanner escaping / false-positive review — PASS

`readStringToken()` consumes JSON string escapes and does not interpret braces, commas, colons or escaped quotes inside strings as structure.

The recursive scanner handles nested arrays and objects. Invalid escapes/control characters are rejected as invalid serialization.

I did not find an alternate duplicate-key path that reaches the ordinary parser after evading the scanner.

HOTFIX B result: PASS.

---

# TEST REVALIDATION

## 9. P16-HOTFIX2-01..25 — PASS as implemented, but incomplete against blocker

The 25-test suite contains concrete production-path assertions for:

- String field wrong scalar forms;
- duplicate keys at root/payload/actor/provenance/DomainRef/precondition/extension;
- escaped-equivalent duplicate key names;
- deeply nested duplicate keys;
- deterministic valid encode/decode/fingerprint;
- extension version 1 acceptance;
- extension versions -1/0/2/999/Int.MAX_VALUE rejection;
- unknown semantic field rejection;
- zero authoritative DB mutation.

The suite is not false-positive for the cases it asserts.

However it does not test quoted numeric strings supplied to numeric fields, so green P16-HOTFIX2 does not close P16-INT-NUMERIC-01.

## 10. Previous P16-HOTFIX-01..12 — PASS

Previous strict unknown-field enforcement and extension version authority remain intact after HOTFIX2.

`NamespacedTextCommandExtension` still accepts exactly schemaVersion 1 and deterministically rejects unsupported versions through both in-memory validation and serialized decode.

## 11. CMD-SEM suite — PASS as implemented

Existing contract tests remain green for:

- stable scoped command identity;
- same UID conflicting semantic payload rejection;
- campaign and actor binding;
- typed payload mismatch;
- unknown command kind;
- structural DomainRef semantics;
- no raw StatePatch/SQL payload surface;
- deterministic known canonical serialization/fingerprint;
- intent-only project commands;
- zero authoritative mutation;
- command UID separation from event/transaction/domain identities;
- typed optimistic preconditions;
- concurrent fingerprint determinism.

The new blocker is outside the current CMD-SEM fixture set.

---

# AUTHORITY / REGRESSION

## 12. Zero authoritative DB mutation — PASS

HOTFIX2 remains purely within command parsing/structural validation.

No PlayerCommand operation gains DB write authority. No command persistence/inbox/outbox/queue/execution-status table is introduced.

Existing no-mutation fixtures remain green under exact CI #337.

## 13. StatePatch / alternate authority — PASS

No StatePatch command kind, raw SQL mutation payload, table/column mutation primitive, mutation callback or hidden writer is introduced by the HOTFIX2 delta.

## 14. Phase 17 boundary — PASS

No PlayerChangeSet, PlayerDomainEngine, WorldRuleProvider, ProgressionEngine or Phase-17 execution implementation is introduced by this runtime candidate.

Phase 17 remains blocked.

## 15. Phase 3–15 regression — PASS

The runtime delta does not modify accepted Phase-3–15 authoritative schema/store semantics. Exact CI #337 runs the full JVM test task and signed release pipeline successfully.

No Phase-3–15 regression attributable to HOTFIX2 was found.

---

# EXACT CI

GitHub Actions:

- workflow: `Build & Release RPG OS ALPHA`;
- run number: `#337`;
- run ID: `31595697673`;
- head SHA: `eef9fefc0ef394e366c4a1756939f97362f6d4db`;
- conclusion: `SUCCESS`.

The green run does not contain a quoted-numeric scalar regression gate, so CI success does not contradict the release blocker above.

---

# FINAL MATRIX

```text
ROLE:
CHAT-3

VALIDATED RUNTIME SHA:
eef9fefc0ef394e366c4a1756939f97362f6d4db

FRESH MASTER BEFORE REPORT WRITE:
33a2bcffbf6c240d21955cf20f1587b8d0ec87d6
(report-only CHAT-5 commit over same runtime; no newer Phase-16 runtime)

EXACT CI:
#337
31595697673
eef9fefc0ef394e366c4a1756939f97362f6d4db
SUCCESS

HOTFIX A — STRICT JSON TYPES:
FAIL overall
- String-defined fields: PASS
- Numeric-defined fields: FAIL — quoted numeric strings accepted

HOTFIX B — DUPLICATE KEYS:
PASS

P16-HOTFIX2:
PASS as implemented / incomplete for numeric-string attack

PREVIOUS P16-HOTFIX:
PASS

CMD-SEM:
PASS as implemented

CANONICALIZATION / IDENTITY:
FAIL — quoted numeric scalar representation can normalize lossily

ZERO AUTHORITATIVE MUTATION:
PASS

PHASE 3–15 REGRESSION:
PASS

ROLE-SPECIFIC VERDICT:
FAIL

PHASE 16 INTEGRITY REVALIDATION: FAIL

PHASE 17:
BLOCKED
```

# FINAL VERDICT

# PHASE 16 INTEGRITY REVALIDATION: FAIL

for exactly:

`eef9fefc0ef394e366c4a1756939f97362f6d4db`

Release blocker: `P16-INT-NUMERIC-01` — numeric JSON fields still accept quoted numeric strings and silently canonicalize them to numeric primitives, violating strict serialized contract typing and canonical identity integrity.

This report is read-only. No runtime/test implementation was changed by CHAT-3. Phase 17 was not started.