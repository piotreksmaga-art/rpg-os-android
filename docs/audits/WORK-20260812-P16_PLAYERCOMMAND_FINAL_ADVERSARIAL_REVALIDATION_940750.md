# CHAT-5 — Phase 16 PlayerCommand Final Adversarial Revalidation After Hotfix

Status: FINAL ADVERSARIAL REVALIDATION — FAIL

Repository: `piotreksmaga-art/rpg-os-android`
Role: READ-ONLY ADVERSARIAL AUDITOR
Validated runtime SHA: `940750119a24381d53361101be1f8957a508c9e9`
Exact CI: GitHub Actions `#333`, run ID `31593150977`, workflow `Build & Release RPG OS ALPHA`, head SHA `940750119a24381d53361101be1f8957a508c9e9`, `SUCCESS`
Previous failed candidate: `74aa96ac31a94e70a1ad4d265937fa646d21a2bd`
Prior blocker: `CMD-ADV-14`
Allowed write scope: this audit report only.

# PHASE 16 ADVERSARIAL VALIDATION: FAIL

The hotfix closes the previously demonstrated ordinary unknown-key dropping path and unsupported `NamespacedTextCommandExtension` schema-version path. However, expanded adversarial canonicalization attacks found two still-live lossy decode paths. Both allow a serialized input with semantics/representation different from the eventual canonical command to decode successfully and then re-encode/fingerprint as a different normalized representation.

These are Phase-16-only release blockers. No runtime/test fix is implemented by CHAT-5. Phase 17 is not started.

---

## 1. Fresh master / candidate / CI

Fresh master at validation start resolved to the target runtime `940750119a24381d53361101be1f8957a508c9e9`; no later Phase-16 runtime commit was present.

Exact CI evidence:

```text
GitHub Actions #333
run ID 31593150977
workflow Build & Release RPG OS ALPHA
head SHA 940750119a24381d53361101be1f8957a508c9e9
conclusion SUCCESS
```

The job executed `Run JVM unit tests` successfully and then built the signed ALPHA APK successfully. Green CI is therefore genuine test execution, not a skipped suite.

Hotfix production scope relative to `74aa96...` is Phase-16-local: `PlayerCommandRegistry.kt`; regression coverage adds `PlayerCommandReleaseBlockerHotfixTest.kt`. Other changed files in the compare are report-only artifacts.

---

## 2. Previous CMD-ADV-14 blocker recheck

### 2.1 Ordinary unknown semantic fields — PASS

The hotfix adds `requireOnlyKeys()` and applies explicit allowed-key sets at:

- root command object;
- payload object according to registered payload type;
- actor;
- provenance;
- `DomainRef`;
- each precondition variant;
- `NamespacedTextCommandExtension`.

An unknown field surviving JSON parse as a distinct map key is rejected with stable `UNKNOWN_COMMAND_FIELD`.

Attack values considered include null, false, 0, empty string, object, array, multiple unknown keys, case variants and semantic-looking future names. Their value type does not matter because rejection is by key membership.

Result: **PASS for distinct unknown keys**.

### 2.2 Extension schema versions — PASS

`NamespacedTextCommandExtension` supports only schema version `1`.

Required matrix:

```text
-1  reject
0   reject
1   accept
2   reject
999 reject
Int.MAX_VALUE reject
```

This is enforced both for direct object validation and serialized decode.

Result: **PASS**.

---

# 3. NEW BLOCKER A — malformed scalar type is silently normalized

Attack ID: `P16-ADV-HOTFIX-BYPASS-01 / CMD-ADV-13 + CMD-ADV-14 malformed-type canonicalization`.

## Violated invariant

Serialized semantic input must not change meaning/type during decode -> encode. Malformed typed input must reject rather than normalize into another canonical command.

## Exact path

`app/src/main/java/com/rpgos/app/PlayerCommandRegistry.kt`

```text
PlayerCommandKindRegistry.decode()
 -> reqString()/optString()
 -> jsonPrimitive.content
 -> typed command object
 -> canonical encode/fingerprint
```

`reqString()` and `optString()` use `JsonPrimitive.content` without requiring that the primitive was a JSON string.

## Minimal reproducer

Start from a valid encoded command and change:

```json
"commandUid":"HOTFIX-CMD-1"
```

to:

```json
"commandUid":123
```

Equivalent attacks apply to other string surfaces such as campaign UID, actor kind/UID, command kind where lookup remains resolvable, provenance strings and payload string fields.

## Expected

```text
decode() rejects wrong JSON scalar type
```

with a deterministic structural error.

## Actual

The JSON number is a `JsonPrimitive`; `.content` yields textual content (`"123"` at the Kotlin value layer). Decode can therefore construct `commandUid = "123"`, and canonical re-encode emits a JSON string:

```json
"commandUid":"123"
```

Thus:

```text
attacker serialized input != canonical serialized input
but decode succeeds
and attacker representation disappears
```

This is a lossy canonicalization collision surface.

## Minimal correction scope

Phase 16 only: make string readers strict about JSON primitive type (`isString == true`) for required/optional string fields, and add malformed-type tests across root, actor, provenance, payload, precondition and extension string surfaces. Do not implement Phase 17+.

Result: **FAIL / RELEASE BLOCKER**.

---

# 4. NEW BLOCKER B — duplicate known JSON keys bypass strict unknown-field checking

Attack ID: `P16-ADV-HOTFIX-BYPASS-02 / CMD-ADV-14 duplicate semantic representation`.

## Violated invariant

Two semantically distinct serialized inputs must not collapse to the same canonical PlayerCommand through parser-side field loss. Duplicate semantic representations must be rejected when parser behavior would otherwise make identity ambiguous.

## Dependency/runtime evidence

The app uses:

```text
org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0
```

`JsonTreeReader` in kotlinx.serialization v1.9.0 builds JSON objects with a `linkedMapOf<String, JsonElement>()` and assigns:

```text
result[key] = element
```

Therefore a later duplicate key replaces the earlier value before a `JsonObject` is returned.

## Exact Phase-16 path

```text
Json.parseToJsonElement(serialized).jsonObject
 -> duplicate key already collapsed by JsonTreeReader
 -> requireOnlyKeys(root.keys)
 -> only one surviving known key is visible
 -> decode succeeds
 -> canonical encode/fingerprint contains only surviving value
```

## Minimal reproducer

Starting from a valid TRAIN command, submit for example:

```json
{
  "commandUid":"ATTACKER-A",
  "commandUid":"HOTFIX-CMD-1",
  ...
}
```

or inside payload:

```json
"payload": {
  "focus":{"kindUid":"STAT","uid":"STRENGTH"},
  "effortUnits":999,
  "effortUnits":10,
  "methodUid":"METHOD"
}
```

## Expected

Reject ambiguous duplicate known fields deterministically before typed semantic identity is accepted.

## Actual

Parser-side map assignment keeps only the later value. `requireOnlyKeys()` sees no unknown key and cannot distinguish this input from a single-field canonical command. After decode/encode, the earlier duplicate value disappears completely.

This meets the adversarial condition:

```text
attacker JSON
-> decode succeeds
-> attacker semantic representation disappears
-> canonical encode/fingerprint equals a different single-representation command
```

## Minimal correction scope

Phase 16 only: reject duplicate JSON object keys before `Json.parseToJsonElement()` has collapsed them, or use a strict parser/reader path capable of detecting duplicate member names. Add regression tests for duplicates at root and nested object surfaces (payload, actor, provenance, DomainRef, precondition, extension). No Phase 17+ implementation.

Result: **FAIL / RELEASE BLOCKER**.

---

# 5. Unknown-field attack matrix

```text
unknown root key                         PASS
unknown actor key                        PASS
unknown provenance key                   PASS
unknown DomainRef key                    PASS
unknown payload key                      PASS
unknown precondition key                 PASS
unknown extension key                    PASS
unknown=null                              PASS
unknown=false                             PASS
unknown=0                                 PASS
unknown=""                                PASS
unknown object                            PASS
unknown array                             PASS
multiple distinct unknown keys           PASS
similar/case-variant unknown key          PASS
semantic-looking future unknown key      PASS
duplicate KNOWN semantic field            FAIL
```

Overall unknown-field/canonicalization gate: **FAIL** because duplicate known semantic representations are lost before `requireOnlyKeys()`.

---

# 6. Extension-version matrix

```text
schemaVersion -1              PASS — reject
schemaVersion 0               PASS — reject
schemaVersion 1               PASS — supported
schemaVersion 2               PASS — reject
schemaVersion 999             PASS — reject
schemaVersion Int.MAX_VALUE   PASS — reject
serialized decode             PASS
direct construction+validate  PASS
encode/fingerprint             PASS — validate reached first
unknown extension kind         PASS — reject on decode
malformed extension payload    PASS structurally
```

Overall: **PASS**.

---

# 7. Identity / canonicalization matrix

```text
same scoped UID exact command                 PASS
same scoped UID changed payload               PASS — conflict
same command UID different campaign           PASS — distinct scoped identity
same scoped UID different actor               PASS — conflict
kind/payload mismatch                         PASS — reject
unknown command kind                          PASS — reject
unsupported command schema                    PASS — reject
known-field encode/decode/fingerprint          PASS
parallel fingerprint determinism               PASS
unknown distinct field dropping                PASS — now rejected
wrong scalar JSON type -> string normalization FAIL
duplicate known JSON key -> last-value collapse FAIL
```

Overall: **FAIL**.

---

# 8. Malformed input / structural boundary

The following remain correctly closed by the typed contract or validator:

- malformed JSON syntax;
- missing required object/array/number fields;
- unknown command kind;
- command-kind / payload runtime type mismatch;
- blank command/campaign/actor identity;
- malformed typed references;
- invalid typed preconditions;
- unknown extension kind;
- unsupported extension schema version;
- raw StatePatch/SQL command kind fallback.

However wrong JSON scalar type for string fields is not strict and is therefore part of blocker A.

Result: **FAIL overall due to malformed string-type acceptance**.

---

# 9. Authority / zero-mutation attacks

Operations attacked:

```text
construct
validate
encode
decode
fingerprint
identity compare
```

No Phase-16 production path receives a `SQLiteDatabase`, domain Store writer, StatePatch engine or mutation callback. Existing no-mutation tests run against representative accepted authoritative Phase 3–15 tables and CI executes them under Robolectric SDK 35.

No evidence was found that these transient operations create or mutate:

- Inventory/Equipment;
- OwnershipRecord;
- Financial Ledger;
- Asset/Liability;
- DevelopmentProject;
- Skill/Technique authority;
- Campaign Truth;
- command history/persistence.

Result: **PASS**.

---

# 10. Persistence / Phase-boundary attacks

No new Phase-16 command persistence authority was introduced:

```text
no player_commands table
no command queue/inbox/outbox
no execution-status ledger
no synthetic legacy command history
```

Hotfix does not introduce PlayerChangeSet, PlayerDomainEngine, WorldRuleProvider, ProgressionEngine, command execution or Phase-17+ mutation behavior.

Result: **PASS**.

---

# 11. DevelopmentProject / domain-authority smuggling

Typed PlayerCommand payloads continue to express intent/evidence references rather than canonical Phase-15 outcomes.

No direct caller result fields for final project progress, milestone achievement fact, durable Truth/Technique/Item result or committed project history were added by the hotfix.

`PlayerCommand` remains distinct from canonical write authorities.

Result: **PASS**.

---

# 12. Phase 3–15 regression

Production delta for this hotfix is confined to the Phase-16 PlayerCommand decoder/validation surface. It does not modify accepted Phase 3–15 stores/schema/authority implementations.

Exact CI #333 runs the complete JVM test suite and signed APK build successfully on the exact target SHA.

No Phase 3–15 regression was found in scope.

Result: **PASS**.

---

# 13. Test-quality audit

P16-HOTFIX-01..12 are real Robolectric tests and CI executes them. They are useful but not sufficient for adversarial PASS because:

1. unknown-field tests inject only distinct string-valued unknown keys;
2. they do not test duplicate known JSON member names;
3. they do not test wrong JSON scalar types on declared String fields;
4. extension-version regression directly covers 999 but production predicate correctly generalizes to `!=1`, so the full version matrix is protected.

No evidence suggests the fixtures are skipped or assertions disabled. The failure is coverage incompleteness, not false green execution.

---

# 14. Future race gates

Phase 16 is a transient immutable request contract and performs no authoritative writes. SQLite race guards are therefore not required here.

The existing future gates remain deferred to execution/idempotency phases:

```text
CMD-RACE-01 exact retry
CMD-RACE-02 conflicting retry
CMD-RACE-03 stale expected version
CMD-RACE-04 target changed before execution
CMD-RACE-05 ambiguous previous execution outcome
```

No Phase-16 precheck is treated as authoritative commit protection.

---

# FINAL VERDICT

# PHASE 16 ADVERSARIAL VALIDATION: FAIL

for exactly:

`940750119a24381d53361101be1f8957a508c9e9`

Exact CI:

`GitHub Actions #333 / run ID 31593150977 / SUCCESS`

Required summary:

```text
CMD-ADV-14                         FAIL
Unknown-field attack matrix       FAIL — duplicate known-key collapse
Extension-version attack matrix   PASS
Canonicalization/identity attacks FAIL — wrong scalar string normalization + duplicate keys
Zero-mutation attacks             PASS
Phase-boundary attacks            PASS
Phase 3–15 regression             PASS
```

The prior ordinary unknown-field and unsupported extension-version defects are fixed, but strict semantic decoding is not yet complete. Phase 16 remains blocked by two Phase-16-only lossy canonicalization paths: malformed non-string primitives accepted by string readers, and duplicate known JSON keys collapsed by the JSON tree parser before strict key validation.

No runtime or test change was implemented by CHAT-5. Phase 17 was not started.
