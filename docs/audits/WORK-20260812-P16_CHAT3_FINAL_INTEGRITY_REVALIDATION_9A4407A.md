# CHAT-3 — Phase 16 Final Integrity Revalidation

Role: CHAT-3 / READ-ONLY INTEGRITY / CONTRACT AUDITOR

Repository: `piotreksmaga-art/rpg-os-android`

Validated runtime SHA: `9a4407a5003694e49f6cc1255cc7a0f81b699289`

Exact CI: GitHub Actions `#341`, run ID `31606993104`, head SHA `9a4407a5003694e49f6cc1255cc7a0f81b699289`, `SUCCESS`.

Allowed write scope: this report only. No production/test runtime modification. Phase 17 remains blocked.

# PHASE 16 INTEGRITY REVALIDATION: FAIL

The numeric-scalar hotfix correctly closes the previous quoted-number blocker in the primary serialized `PlayerCommandKindRegistry.decode(serialized)` path. Required/optional Int/Long helpers now distinguish missing/null, wrong JSON scalar type, and malformed/out-of-range numeric values. Previous strict String typing, duplicate-key rejection, unknown-field rejection, extension-version enforcement, deterministic canonical encode/fingerprint and zero-mutation tests remain green under exact CI #341.

However the public registry/codec surface still exposes an alternate payload decode path that bypasses the strict payload allowed-key boundary. This permits semantic information loss before construction of a PlayerCommand and therefore remains a release blocker for the Phase-16 integrity contract.

## 1. Fresh master / target pinning

Fresh master at audit start and immediately before report write resolved to the target runtime:

`9a4407a5003694e49f6cc1255cc7a0f81b699289`

No later Phase-16 runtime candidate was present before this report-only commit.

## 2. Exact CI — PASS

GitHub Actions run `31606993104`, run number `341`, is completed with `conclusion=success` and exact head SHA `9a4407a5003694e49f6cc1255cc7a0f81b699289`.

The exact run contains successful steps including:

- Validate project;
- Run JVM unit tests;
- Build signed ALPHA APK;
- Prepare release files;
- Upload Actions artifact;
- Update existing GitHub Release assets.

Green CI does not by itself close the public-codec bypass described below because the current regression suites exercise `registry.decode(serialized)` rather than direct public codec decode.

## 3. Numeric scalar blocker — PASS in canonical serialized path

The previous blocker was:

```text
"schemaVersion":"1"
-> reqInt() accepted string content
-> typed Int(1)
-> canonical re-encode emitted numeric 1
```

The target fixes this through strict helpers:

- `reqInt()` requires non-string JsonPrimitive, rejects boolean, distinguishes invalid numeric value;
- `reqLong()` applies the same rule;
- `optLong()` preserves absent/null semantics while enforcing strict numeric type when present.

Required matrix now holds on `registry.decode(serialized)`:

```text
actual JSON number, legal value        -> ACCEPT
quoted numeric string                  -> REJECT INVALID_JSON_NUMERIC_TYPE
boolean                                -> REJECT INVALID_JSON_NUMERIC_TYPE
object                                 -> REJECT INVALID_JSON_NUMERIC_TYPE
array                                  -> REJECT INVALID_JSON_NUMERIC_TYPE
required null                          -> REJECT missing-required-field contract
optional absent/null                   -> preserved as null
out-of-range Int/Long                  -> REJECT INVALID_JSON_NUMERIC_VALUE
```

The helpers are used for root `schemaVersion`, optional `requestedEffectiveOrder`, precondition `expectedVersion`, extension `schemaVersion`, and the numeric fields in the core payload codecs including effort, amounts, quantities, shares/basis points, principal, settlement amount and project progress intents.

Numeric scalar blocker result: **PASS** for the canonical serialized decoder.

## 4. P16-HOTFIX3-01..22 — PASS as implemented

The new suite contains concrete assertions for:

- quoted root schemaVersion;
- quoted requestedEffectiveOrder;
- quoted payload Long;
- quoted expectedVersion;
- quoted extension schemaVersion;
- boolean/object/array numeric attacks;
- required null and optional absent/null;
- valid Int/Long primitives;
- overflow/out-of-range;
- canonical encode/decode/encode;
- fingerprint boundary;
- extension v1 and unsupported-version matrix;
- previous strict String, duplicate-key and unknown-field regressions;
- zero authoritative mutation.

The suite is not a false-positive for the cases it asserts and runs under exact CI #341.

## 5. Previous hotfix regressions

### Strict String typing — PASS

`reqString()` / `optString()` still require actual JSON String primitives and reject number/boolean/object/array forms using the stable structural contract.

### Duplicate JSON object keys — PASS

Pre-parse duplicate-key scanning remains active before ordinary JSON parsing, including nested/deep duplicates and escaped-equivalent names.

### Unknown semantic fields — PASS in registry.decode(serialized)

The serialized registry path still applies exact allowed-key checks to envelope, actor, provenance, DomainRef, preconditions, extensions and payload according to the registered codec schema.

### Extension version — PASS

Numeric schemaVersion `1` is accepted. Numeric `-1`, `0`, `2`, `999`, `Int.MAX_VALUE` are rejected as unsupported. Quoted `"1"` is now rejected earlier as wrong numeric JSON type.

## 6. Stable identity / canonical fingerprint — PASS for canonical accepted path

For input accepted by `registry.decode(serialized)`:

- decode -> encode is deterministic;
- encode -> decode -> encode is byte-deterministic;
- fingerprint is SHA-256 of the canonical validated encoding;
- quoted numerics cannot reach the typed fingerprint boundary;
- same scoped UID with altered accepted semantics remains an identity conflict.

## 7. RELEASE BLOCKER — public codec decode bypass

Blocker ID: `P16-INT-PUBLIC-CODEC-01`

### Violated invariant

The Phase-16 decoder/codec contract must not expose a public alternate decode route through which serialized semantic information can be accepted and discarded before canonical representation/fingerprint.

The audit instruction explicitly requires checking alternate decode paths and whether the strict decoder can be bypassed through public API.

### Exact runtime path

`app/src/main/java/com/rpgos/app/PlayerCommandRegistry.kt`

Current public surface:

```kotlin
abstract class TypedCommandCodec<P : PlayerCommandPayload>(
    val payloadType: KClass<P>,
    val allowedKeys: Set<String> = ...
) {
    abstract fun decode(obj: JsonObject): P
}

class PlayerCommandKindRegistry ... {
    fun codec(kindUid: String): TypedCommandCodec<out PlayerCommandPayload> = ...
}
```

The strict payload key enforcement exists only in:

```kotlin
val payloadObject = root.reqObject("payload").requireOnlyKeys(commandCodec.allowedKeys)
val payload = commandCodec.decode(payloadObject)
```

inside `PlayerCommandKindRegistry.decode(serialized)`.

The codec's own public `decode(JsonObject)` does not call `requireOnlyKeys(allowedKeys)`.

### Minimal local reproducer

Conceptually:

```kotlin
val registry = PlayerCommandKindRegistry.core()
val codec = registry.codec(PlayerCommandKinds.TRAIN)

val attackerPayload = Json.parseToJsonElement(
    """{
      "focus":{"kindUid":"STAT","uid":"STRENGTH"},
      "effortUnits":10,
      "methodUid":"METHOD",
      "requestedCanonicalOutcome":"FORBIDDEN"
    }"""
).jsonObject

val payload = codec.decode(attackerPayload)
```

### Expected

Deterministic rejection of the unknown semantic field before a typed payload can be produced.

### Actual

The core TRAIN codec reads only `focus`, `effortUnits` and `methodUid`. The extra `requestedCanonicalOutcome` member is ignored because direct `codec.decode()` never receives the registry-level `requireOnlyKeys()` guard.

The caller can then construct a `PlayerCommand` with the returned typed payload and invoke normal `registry.encode()` / `fingerprint()`. The unknown semantic field has disappeared.

This recreates the forbidden pattern:

```text
public decode surface accepts semantic input
-> information disappears
-> canonical command/fingerprint describes reduced semantics
```

The numeric hotfix does not address this path.

### Why this is integrity-relevant

`PlayerCommandKindRegistry.codec()` and `TypedCommandCodec.decode()` are public Kotlin APIs, not internal/private implementation details. Therefore the repository currently exposes more than one decode boundary, but only one of them enforces the complete payload schema.

Strict numeric helpers still protect numeric scalar typing inside the direct codec route, but strict unknown-field losslessness does not.

### Minimal correction scope

Phase 16 only:

1. make raw codec decoding inaccessible outside the trusted registry path (`internal`/`private`) OR
2. make every codec's public decode entry enforce its own `allowedKeys` before field extraction;
3. add a regression proving direct/alternate public decode cannot accept unknown payload fields;
4. retain all current registry-level strict string/numeric/duplicate/version checks.

No persistence, DB migration, Phase-17 runtime or domain-execution change is required.

## 8. Authority separation — PASS

The target remains a transient PlayerCommand contract. No command persistence/inbox/outbox/queue, DB writer, StatePatch mutation authority, PlayerChangeSet, PlayerDomainEngine, WorldRuleProvider, ProgressionEngine or Phase-17 runtime is introduced.

Construction, validation, canonical registry encode/decode/fingerprint remain non-mutating in existing fixtures.

## 9. Phase 3–15 regression — PASS

The delta from previous runtime candidate modifies Phase-16 registry code and adds Phase-16 tests/audit reports only. No accepted Phase-3–15 authoritative schema/store writer is modified by HOTFIX3. Exact CI #341 runs the full JVM test task and signed release pipeline successfully.

## 10. Final matrix

```text
ROLE:
CHAT-3

VALIDATED RUNTIME SHA:
9a4407a5003694e49f6cc1255cc7a0f81b699289

FRESH MASTER BEFORE REPORT WRITE:
9a4407a5003694e49f6cc1255cc7a0f81b699289

RUNTIME CHANGED AFTER TARGET:
NO

NUMERIC SCALAR BLOCKER:
PASS

STRICT STRING REGRESSION:
PASS

DUPLICATE KEY REGRESSION:
PASS

UNKNOWN FIELD REGRESSION:
FAIL globally because public codec decode bypasses payload allowed-key enforcement
(PASS on canonical registry.decode(serialized) path)

EXTENSION VERSION:
PASS

CANONICALIZATION / IDENTITY:
FAIL globally because public codec decode can discard unknown semantic payload members before canonical encode/fingerprint

FINGERPRINT:
PASS for canonical validated command path; FAIL as a complete public-boundary guarantee because the alternate decode path can pre-normalize payload semantics

ZERO AUTHORITATIVE MUTATION:
PASS

PHASE 3–15 REGRESSION:
PASS

EXACT CI:
#341
31606993104
9a4407a5003694e49f6cc1255cc7a0f81b699289
SUCCESS

NEW BLOCKERS:
P16-INT-PUBLIC-CODEC-01 — public TypedCommandCodec.decode(JsonObject) bypasses registry payload allowed-key enforcement

ROLE-SPECIFIC VERDICT:
FAIL
```

# FINAL VERDICT

# PHASE 16 INTEGRITY REVALIDATION: FAIL

for exactly:

`9a4407a5003694e49f6cc1255cc7a0f81b699289`

This report is read-only. No runtime/test implementation was changed by CHAT-3. Phase 17 was not started.
