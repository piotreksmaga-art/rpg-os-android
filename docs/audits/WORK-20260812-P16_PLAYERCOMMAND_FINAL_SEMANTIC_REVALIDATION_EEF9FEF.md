# CHAT-2 — Phase 16 PlayerCommand Final Semantic Revalidation

Status: FINAL SEMANTIC REVALIDATION — FAIL

Repository: `piotreksmaga-art/rpg-os-android`
Role: CHAT-2 / READ-ONLY semantic auditor
Validated runtime SHA: `eef9fefc0ef394e366c4a1756939f97362f6d4db`
Exact CI: GitHub Actions `#337`, run ID `31595697673`, head SHA `eef9fefc0ef394e366c4a1756939f97362f6d4db`, `SUCCESS`
Allowed write scope: this report only.

# PHASE 16 SEMANTIC REVALIDATION: FAIL

The new hotfix closes the two previously reported adversarial blockers on the String/duplicate-key paths: non-string JSON primitives no longer coerce into String fields, and duplicate object members are rejected before `Json.parseToJsonElement()` can collapse them. The previous strict unknown-field and extension-version fixes also remain intact.

However, independent full-surface semantic review found one still-live lossy scalar canonicalization path on numeric fields. `reqInt()`, `reqLong()` and `optLong()` still read `JsonPrimitive.int/long` without requiring an actual unquoted JSON number. In kotlinx.serialization-json 1.9.0 these accessors parse the primitive's textual `content`; a JSON string primitive such as `"42"` therefore parses as numeric `42`. The decoder then re-encodes it as an unquoted JSON number. This violates the same strict scalar/canonical representation invariant that the final hotfix is intended to enforce.

---

## 1. Fresh master / exact candidate

Fresh `master` resolved to exactly:

`eef9fefc0ef394e366c4a1756939f97362f6d4db`

No later runtime Phase-16 commit existed at report creation. Therefore this report validates exactly the requested candidate.

Exact CI evidence:

- workflow: `Build & Release RPG OS ALPHA`
- GitHub Actions `#337`
- run ID `31595697673`
- head SHA `eef9fefc0ef394e366c4a1756939f97362f6d4db`
- conclusion `SUCCESS`

Green CI is accepted as genuine test/build evidence, but it is not sufficient to override the independent semantic blocker below.

---

## 2. Hotfix A — strict JSON scalar types

### String side — PASS

`reqString()` / `optString()` now require `JsonPrimitive.isString == true` and reject number/boolean/object/array substitution with `INVALID_JSON_STRING_TYPE`.

This closes the prior example:

```json
"commandUid":123
```

which can no longer normalize into:

```json
"commandUid":"123"
```

The same strict String reader is reused across root fields, actor, provenance, DomainRef, payload Strings, precondition discriminators/state values, extension String fields and optional String fields.

### Numeric side — FAIL / RELEASE BLOCKER

Current helpers remain semantically permissive:

```kotlin
reqInt(k)  -> jsonPrimitive.int
reqLong(k) -> jsonPrimitive.long
optLong(k) -> jsonPrimitive.long
```

No `isString == false` check is performed.

kotlinx.serialization-json 1.9.0 defines `JsonPrimitive.isString` separately from `content`, while `.int` and `.long` parse numeric content. Therefore the quoted JSON string primitive `"42"` has `isString == true` but numeric content and can be consumed by `.long`/`.int`.

### Minimal reproducer A

Start from a canonical encoded command containing:

```json
"requestedEffectiveOrder":42
```

Change only the JSON scalar type:

```json
"requestedEffectiveOrder":"42"
```

Path:

```text
PlayerCommandKindRegistry.decode
 -> duplicate-key scanner passes
 -> Json.parseToJsonElement
 -> optLong("requestedEffectiveOrder")
 -> JsonPrimitive.long
 -> Kotlin Long(42)
 -> PlayerCommand
 -> encode/fingerprint
 -> canonical JSON contains numeric 42
```

Expected:

```text
deterministic structural REJECT for wrong JSON scalar type
```

Actual:

```text
decode succeeds and quoted numeric representation is lost
```

### Minimal reproducer B — extension schema

Canonical extension:

```json
{"kind":"NAMESPACED_TEXT","extensionKindUid":"TEST:EXT","schemaVersion":1,"value":"typed"}
```

Attack:

```json
{"kind":"NAMESPACED_TEXT","extensionKindUid":"TEST:EXT","schemaVersion":"1","value":"typed"}
```

`reqInt("schemaVersion")` can parse quoted `"1"` as Int 1, then the semantic version check sees supported version `1`. Canonical re-encode emits numeric `1`.

This is especially relevant because the release gate explicitly requires strict scalar typing and an exact extension schema contract.

Affected numeric surfaces include at least:

- root `schemaVersion`;
- root `requestedEffectiveOrder`;
- `ExpectedRecordVersion.expectedVersion`;
- extension `schemaVersion`;
- numeric command payload fields such as effort, requested amounts, quantities, share basis points, principal/settlement amounts and requested progress cap.

### Violated invariant

```text
serialized PlayerCommand semantic scalar type
must not be silently normalized into another canonical representation
```

and:

```text
malformed/alternate typed representation
must deterministically reject rather than fingerprint as a different canonical form
```

### Minimal correction scope

Phase 16 only:

- make numeric readers require a non-string numeric `JsonPrimitive` before calling `.int/.long`;
- provide deterministic structural error for wrong JSON numeric type;
- cover required/optional Int/Long surfaces, including extension `schemaVersion`, root `schemaVersion`, requested order, preconditions and representative payload numbers;
- do not implement Phase 17+.

No redesign of PlayerCommand is required.

Result: **HOTFIX A FAIL**.

---

## 3. Hotfix B — duplicate JSON object keys

Result: **PASS**.

`rejectDuplicateJsonObjectKeys(serialized)` executes before `Json.parseToJsonElement()`. The scanner:

- has a separate `seen` set per object;
- recursively scans nested objects and arrays;
- reads quoted strings without interpreting braces/commas/colons inside them as structure;
- validates escape sequences while scanning;
- decodes object member name tokens through JSON string decoding before comparing keys;
- therefore catches escaped-equivalent names such as `commandUid` vs `\u0063ommandUid`;
- rejects duplicate known and unknown keys before parser-side map collapse.

The P16-HOTFIX2 suite covers root, payload, actor, provenance, DomainRef, precondition, extension, identical/escaped-equivalent and deeper nested duplicates.

No semantic bypass was found in this scanner for the role-specific revalidation.

---

## 4. Previous P16 hotfix gates

Result: **PASS**.

The previous candidate's fixes remain intact:

- distinct unknown semantic keys reject with `UNKNOWN_COMMAND_FIELD` across root, payload, actor, provenance, DomainRef, precondition and extension decode surfaces;
- `NamespacedTextCommandExtension` supports semantic version value `1` only;
- direct in-memory validation rejects unsupported values `-1/0/2/999/Int.MAX_VALUE`;
- serialized numeric versions outside `1` reject.

Caveat: the new blocker is scalar-type strictness, not acceptance of a different numeric version value. A quoted `"1"` is semantically malformed JSON typing but is normalized to supported numeric value 1.

---

## 5. CMD-SEM / PlayerCommand meaning

Result: **PASS** for the original CMD-SEM contract.

`PlayerCommand` remains:

```text
typed transient intent/request
```

and remains distinct from:

```text
StatePatch
PlayerChangeSet
mutation
committed fact
event
transaction
persistence authority
```

The runtime still uses typed command payload classes, typed kind-to-codec registry, generic campaign-bound actor ref, typed provenance/preconditions/extensions, stable scoped command identity and deterministic canonical encode/fingerprint for already-valid typed commands.

Unknown command kinds and command-kind/payload mismatch fail closed. DevelopmentProject commands continue to carry intent/evidence references rather than project outcome/progress authority.

---

## 6. Canonicalization / identity

Result: **FAIL** due to the numeric string-to-number normalization reproducer.

For canonical valid in-memory commands:

- encode is deterministic;
- decode -> encode is deterministic;
- fingerprint is deterministic;
- same scoped UID + exact semantic content yields the same logical identity;
- same scoped UID + changed semantic content conflicts;
- different campaign + same textual command UID is a distinct campaign-scoped command identity.

But malformed serialized numeric-as-string input can still decode into the same canonical command as a proper numeric representation. Therefore serialized semantic identity is not yet strictly lossless.

---

## 7. Zero authoritative mutation

Result: **PASS**.

Phase-16 operations under review:

```text
construct
validate
encode
decode
fingerprint
identity compare
```

remain transient and have no canonical domain writer. P16-HOTFIX2-25 rechecks representative Phase 3–15 authoritative tables before/after these operations.

No direct mutation path was found into Inventory, Equipment, Ownership, Financial Ledger, Asset/Liability, DevelopmentProject, Skill/Technique or Campaign Truth authority.

---

## 8. Persistence / Phase boundary

Result: **PASS**.

No Phase-16 command table, queue, inbox, outbox, execution ledger or mutation engine was introduced by the hotfix.

No Phase-17+ runtime was found in this hotfix scope:

- no `PlayerChangeSet` implementation;
- no `PlayerDomainEngine`;
- no `WorldRuleProvider` execution layer;
- no `ProgressionEngine`;
- no command execution/commit authority.

Phase 17 remains blocked.

---

## 9. Phase 3–15 regression

Result: **PASS**.

The production hotfix is confined to the PlayerCommand decoder/scanner surface. No accepted Phase 3–15 authoritative store/schema/domain mutation path was changed. Exact CI #337 is green for the requested SHA.

---

## 10. Required gate summary

```text
HOTFIX A — STRICT JSON TYPES:        FAIL
  String scalar strictness:          PASS
  Numeric scalar strictness:         FAIL
HOTFIX B — DUPLICATE KEYS:           PASS
P16-HOTFIX2:                         FAIL (suite green, uncovered numeric scalar canonicalization blocker)
PREVIOUS P16-HOTFIX:                 PASS
CMD-SEM:                             PASS
CANONICALIZATION / IDENTITY:         FAIL
ZERO AUTHORITATIVE MUTATION:         PASS
PHASE 3–15 REGRESSION:               PASS
ROLE-SPECIFIC SEMANTIC VERDICT:      FAIL
PHASE 17:                            BLOCKED
```

---

# FINAL VERDICT

# PHASE 16 SEMANTIC REVALIDATION: FAIL

for exactly:

`eef9fefc0ef394e366c4a1756939f97362f6d4db`

The duplicate-key blocker is fixed, and the prior String coercion blocker is fixed. The remaining release blocker is the symmetric numeric scalar coercion path: quoted numeric JSON strings are still accepted by `reqInt/reqLong/optLong` and normalized into canonical unquoted numbers, including on identity-bearing and extension-version fields.

No runtime/test fix was implemented by CHAT-2. Phase 16 is not globally accepted and Phase 17 is not started.
