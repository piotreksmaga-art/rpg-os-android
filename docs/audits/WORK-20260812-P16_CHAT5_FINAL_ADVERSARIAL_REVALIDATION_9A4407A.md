# CHAT-5 — Phase 16 Final Adversarial / Robustness Revalidation

Role: `CHAT-5 / READ-ONLY ADVERSARIAL / ROBUSTNESS AUDITOR`

Repository: `piotreksmaga-art/rpg-os-android`

Validated runtime SHA: `9a4407a5003694e49f6cc1255cc7a0f81b699289`

Exact CI: GitHub Actions `#341`, run ID `31606993104`, head SHA `9a4407a5003694e49f6cc1255cc7a0f81b699289`, `SUCCESS`.

Allowed write scope: this report only. No production/test runtime modification. Phase 17 remains blocked.

# PHASE 16 ADVERSARIAL VALIDATION: FAIL

The HOTFIX3 candidate correctly closes the previously demonstrated quoted-numeric canonicalization defect on the canonical serialized `PlayerCommandKindRegistry.decode(serialized)` path. Previous strict String typing, duplicate-key pre-scan, unknown-field rejection, extension-version enforcement, canonical encode/fingerprint behavior, zero-mutation checks, and Phase 3–15 test coverage remain intact.

However, an alternate public decode surface bypasses the strict payload allowed-key boundary. A caller can obtain a public `TypedCommandCodec` through `PlayerCommandKindRegistry.codec(kindUid)` and call its public `decode(JsonObject)` directly. Core codec decoders extract only known fields and do not themselves invoke `requireOnlyKeys(allowedKeys)`. Therefore unknown semantic payload members can disappear before a typed payload is constructed and before canonical PlayerCommand serialization/fingerprinting. This is a Phase-16 release blocker.

---

## 1. Fresh master / candidate pinning

At initial validation start, master resolved to the target runtime:

`9a4407a5003694e49f6cc1255cc7a0f81b699289`

Immediately before this report write, fresh master resolved to:

`f68ce4dce6fabdc3b19abd4bb901081a449cd8c6`

The only post-target commits were:

- `d3a3647448c2805d0973021dae933d6480a739a9` — CHAT-2 Phase-16 semantic audit report only;
- `f68ce4dce6fabdc3b19abd4bb901081a449cd8c6` — CHAT-3 Phase-16 integrity audit report only.

Neither modifies production/test runtime. No newer Phase-16 runtime candidate exists. Therefore this audit remains pinned to exactly `9a4407a5003694e49f6cc1255cc7a0f81b699289`.

Runtime changed after target: **NO**.

---

## 2. Exact CI evidence

GitHub Actions run `31606993104`, run number `341`, has:

```text
workflow: Build & Release RPG OS ALPHA
head SHA: 9a4407a5003694e49f6cc1255cc7a0f81b699289
status: completed
conclusion: success
```

The exact build job reports successful steps for:

- Validate project;
- Run JVM unit tests;
- Build signed ALPHA APK;
- Prepare release files;
- Upload Actions artifact;
- Check existing release;
- Update existing GitHub Release assets;
- Show release information.

The create-release step is skipped because an existing release is updated; the asset update succeeds. CI is supporting evidence, not the sole basis for the adversarial verdict.

---

## 3. HOTFIX3 numeric scalar blocker revalidation

### Result: PASS on canonical serialized decoder

The target introduces distinct structural errors:

```text
INVALID_JSON_NUMERIC_TYPE
INVALID_JSON_NUMERIC_VALUE
```

and hardens `reqInt()`, `reqLong()`, and `optLong()`.

Required behavior now holds on `PlayerCommandKindRegistry.decode(serialized)`:

| Input at Int/Long field | Result |
|---|---|
| legal unquoted JSON integer | ACCEPT, then typed semantic validation |
| quoted numeric string | REJECT `INVALID_JSON_NUMERIC_TYPE` |
| boolean | REJECT `INVALID_JSON_NUMERIC_TYPE` |
| object | REJECT `INVALID_JSON_NUMERIC_TYPE` |
| array | REJECT `INVALID_JSON_NUMERIC_TYPE` |
| required null | required-field failure |
| optional absent | preserved as null |
| optional null | preserved as null |
| Int overflow | REJECT `INVALID_JSON_NUMERIC_VALUE` |
| Long overflow | REJECT `INVALID_JSON_NUMERIC_VALUE` |
| malformed/non-integral numeric representation for Int/Long | REJECT `INVALID_JSON_NUMERIC_VALUE` |

The helpers distinguish wrong scalar type from missing/null and numeric representability failure.

### Numeric surfaces inspected

The envelope and common nested contracts route through the strict helpers:

- root `schemaVersion` -> `reqInt`;
- root `requestedEffectiveOrder` -> `optLong`;
- `ExpectedRecordVersion.expectedVersion` -> `reqLong`;
- extension `schemaVersion` -> `reqInt`.

Core payload codecs use `reqLong` / `optLong` for all Phase-16 numeric intent surfaces found, including:

- effort units;
- requested resource amount;
- item quantities;
- ownership share basis points;
- financial minor-unit amount;
- obligation principal and requested settlement amount;
- project progress cap;
- project work effort intent.

No direct `.int` / `.long` numeric parsing bypass was found in the core payload codec definitions.

### P16-HOTFIX3-01..22

The suite directly covers quoted numerics at root/payload/precondition/extension, boolean/object/array type attacks, required null, optional absent/null, legal Int/Long, Int/Long overflow, deterministic canonicalization, fingerprint boundary, extension version matrix, prior strict-string/duplicate/unknown regressions, and zero authoritative mutation.

Result: **PASS as implemented**.

---

## 4. Previous strict String regression

`reqString()` and `optString()` still require a `JsonPrimitive` with `isString == true`.

Representative number/boolean/object/array substitutions cannot be normalized into String fields.

Result: **PASS**.

---

## 5. Duplicate-key regression

`rejectDuplicateJsonObjectKeys(serialized)` still executes before `Json.parseToJsonElement()`.

The scanner:

- recursively parses objects and arrays;
- keeps a separate seen-key set per object;
- decodes escaped JSON key spelling before comparison;
- ignores structure-like text while inside JSON strings;
- rejects duplicate known and unknown keys before parser-side map collapse;
- rejects escaped-equivalent key identities such as `commandUid` and `\u0063ommandUid`.

Result: **PASS on canonical serialized decoder**.

---

## 6. Unknown-field regression

On the canonical serialized registry path, exact allowed-key checks remain active for:

- command root;
- payload according to registered payload type;
- actor;
- provenance;
- `DomainRef`;
- each precondition variant;
- extension object.

Distinct unknown semantic serialized fields fail closed on this path.

Result for canonical serialized registry path: **PASS**.

Global public-boundary result: **FAIL** because of the alternate public codec decode path described in section 12.

---

## 7. Extension version contract

`NamespacedTextCommandExtension` remains strictly versioned.

Required matrix:

```text
1             -> ACCEPT
-1            -> REJECT
0             -> REJECT
2             -> REJECT
999           -> REJECT
Int.MAX_VALUE -> REJECT
"1"           -> REJECT INVALID_JSON_NUMERIC_TYPE
```

The restriction applies in serialized decode and direct typed command validation.

Result: **PASS**.

---

## 8. Canonicalization / identity attacks

### Canonical registry path

The prior lossy transformations are closed:

```text
"commandUid":123 -> "123"                CLOSED
"effortUnits":"10" -> 10                CLOSED
"schemaVersion":"1" -> 1                CLOSED
unknown serialized semantic field -> drop  CLOSED
parser duplicate member -> last wins        CLOSED before parser
```

Valid canonical `encode -> decode -> encode` remains deterministic.

Same scoped stable UID with altered accepted immutable semantics remains an identity conflict because fingerprint covers validated canonical command content.

### Global public API boundary

FAIL because public codec decode can still erase unknown payload semantic information before the canonical PlayerCommand exists. See section 12.

---

## 9. Fingerprint

`PlayerCommandKindRegistry.fingerprint()` hashes `encode(command)`, and `encode()` first validates the typed command. For commands that enter through the canonical registry decoder, the quoted-numeric and unknown-field lossy paths are closed before fingerprinting.

However, a payload produced through the public direct codec decode bypass can already have lost attacker-supplied semantic members before `PlayerCommand` construction. Fingerprint therefore cannot recover or distinguish information erased through that alternate public decode path.

Result: **FAIL globally**; **PASS for canonical registry decode path**.

---

## 10. Zero authoritative mutation

The PlayerCommand contract remains transient. Construction, structural validation, encode, canonical decode, fingerprint, and identity comparison do not write accepted Phase 3–15 authoritative tables in the provided zero-mutation fixtures.

HOTFIX3 production delta is limited to PlayerCommand structural decoding helpers. No DB mutation callback is added.

Result: **PASS**.

---

## 11. Phase boundary / Phase 3–15 regression

The target does not introduce:

- command persistence table;
- command inbox/outbox/queue;
- StatePatch authority alias;
- PlayerChangeSet runtime;
- PlayerDomainEngine runtime;
- WorldRuleProvider runtime;
- ProgressionEngine runtime;
- Phase-17 execution semantics.

The delta from the previous candidate changes Phase-16 registry decoding and adds Phase-16 regression tests. Accepted Phase 3–15 production stores/schemas are not modified. Exact CI #341 executes the complete JVM unit test task and signed APK build successfully.

Result: **PASS**.

---

# 12. RELEASE BLOCKER — alternate public payload decoder bypass

Blocker ID: `P16-ADV-PUBLIC-CODEC-01`

## Violated invariant

No public Phase-16 decode surface may accept semantic input, silently discard part of it, and then allow the reduced typed value to become a canonical PlayerCommand/fingerprint.

Strict payload schema enforcement must be inseparable from payload decoding or the raw decoder must not be public API.

## Exact runtime path

`app/src/main/java/com/rpgos/app/PlayerCommandRegistry.kt`

Public APIs:

```kotlin
abstract class TypedCommandCodec<P : PlayerCommandPayload>(...) {
    abstract fun decode(obj: JsonObject): P
}

class PlayerCommandKindRegistry ... {
    fun codec(kindUid: String): TypedCommandCodec<out PlayerCommandPayload>
}
```

Canonical registry decoding does:

```kotlin
val payloadObject = root.reqObject("payload")
    .requireOnlyKeys(commandCodec.allowedKeys)
val payload = commandCodec.decode(payloadObject)
```

But direct public `commandCodec.decode(JsonObject)` does **not** perform `requireOnlyKeys(commandCodec.allowedKeys)` itself.

The concrete core codec lambdas read known fields only.

## Minimal local reproducer

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

## Expected

Deterministic structural rejection of `requestedCanonicalOutcome` before any typed payload can be produced.

## Actual

The TRAIN codec reads `focus`, `effortUnits`, and `methodUid`; the extra semantic field is not checked by the codec and disappears from the returned typed payload.

A caller can then construct a normal typed `PlayerCommand` from that payload and call registry `encode()` / `fingerprint()`. The original semantic member is irrecoverably absent.

Forbidden pattern reproduced:

```text
alternate public decode
-> unknown semantic input accepted
-> information disappears
-> typed payload created
-> canonical command/fingerprint represents reduced semantics
```

## Why HOTFIX3 does not close it

HOTFIX3 hardens numeric scalar helpers used by the codec. Therefore quoted-number attacks remain rejected even on direct codec decode where those helpers are invoked.

The bypass instead targets **payload allowed-key enforcement**, which lives outside the codec in the canonical registry serialized path.

## Minimal Phase-16-only correction scope

One of:

1. make raw codec lookup/decode inaccessible outside the trusted registry implementation (`internal`/private boundary as appropriate); or
2. enforce `requireOnlyKeys(allowedKeys)` inside every public codec decode entry before field extraction.

Add a regression proving a direct/alternate codec decode cannot accept an unknown payload member.

Retain all current strict String/numeric/duplicate/version protections.

No DB migration, accepted Phase 3–15 domain change, Phase-17 runtime, PlayerChangeSet, or execution engine is required.

---

# 13. Final result matrix

```text
ROLE: CHAT-5
VALIDATED RUNTIME SHA: 9a4407a5003694e49f6cc1255cc7a0f81b699289
FRESH MASTER BEFORE REPORT WRITE: f68ce4dce6fabdc3b19abd4bb901081a449cd8c6
RUNTIME CHANGED AFTER TARGET: NO

NUMERIC SCALAR BLOCKER: PASS
STRICT STRING REGRESSION: PASS
DUPLICATE KEY REGRESSION: PASS
UNKNOWN FIELD REGRESSION: FAIL globally; PASS on registry.decode(serialized)
EXTENSION VERSION: PASS
CANONICALIZATION / IDENTITY: FAIL globally
FINGERPRINT: FAIL globally; PASS on canonical registry path
ZERO AUTHORITATIVE MUTATION: PASS
PHASE 3–15 REGRESSION: PASS
P16-HOTFIX3-01..22: PASS as implemented
P16-HOTFIX2-01..25: PASS as implemented
P16-HOTFIX-01..12: PASS as implemented
CMD-SEM: PASS as implemented

EXACT CI:
#341
31606993104
9a4407a5003694e49f6cc1255cc7a0f81b699289
SUCCESS

NEW BLOCKERS:
P16-ADV-PUBLIC-CODEC-01 — public TypedCommandCodec.decode(JsonObject) bypasses payload allowed-key enforcement.
```

# FINAL VERDICT

# PHASE 16 ADVERSARIAL VALIDATION: FAIL

for exactly:

`9a4407a5003694e49f6cc1255cc7a0f81b699289`

This CHAT-5 report does not globally accept Phase 16. No runtime/test files were modified. Phase 17 remains BLOCKED.
