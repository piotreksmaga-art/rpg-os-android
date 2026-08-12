# CHAT-3 — Phase 16 PlayerCommand Final Integrity / Contract Revalidation

Status: FINAL INTEGRITY / CONTRACT REVALIDATION — FAIL

Repository: `piotreksmaga-art/rpg-os-android`
Role: CHAT-3 / READ-ONLY integrity/contract auditor
Validated runtime SHA: `74aa96ac31a94e70a1ad4d265937fa646d21a2bd`
Exact CI: GitHub Actions `#329`, run ID `31586469466`, head SHA `74aa96ac31a94e70a1ad4d265937fa646d21a2bd`, `SUCCESS`
Accepted Phase-15 runtime: `173e501fbe832980bb4eaf177c5ba34d93cd5f37`
Allowed write scope: this report only.

# PHASE 16 INTEGRITY REVALIDATION: FAIL

The exact candidate correctly implements most of the intended transient typed `PlayerCommand` contract and preserves accepted Phase 3–15 persistence authorities. However, two Phase-16 contract-integrity blockers remain in the serialization/version boundary:

1. unknown serialized semantic fields are silently ignored instead of rejected or preserved through a registered typed/versioned extension mechanism;
2. `NamespacedTextCommandExtension` accepts any positive `schemaVersion`, including unsupported future versions, because validation checks only `schemaVersion > 0` and no extension-version authority exists.

Both defects are inside Phase 16 itself. They can cause a serialized request carrying additional semantics to normalize into an older command identity/fingerprint without caller awareness. Green CI does not cover these attacks.

No runtime correction was implemented. Phase 17 was not started.

---

## 1. Fresh master / runtime pin — PASS

At audit start, fresh master resolved exactly to the requested runtime:

`74aa96ac31a94e70a1ad4d265937fa646d21a2bd`.

During the audit, master advanced only through two report-only commits for the same runtime:

- `86c7b5fd2f981c8a040ea9944219c77fbf0a0be2` — CHAT-5 Phase-16 adversarial report;
- `66e961e495da6f7ec453d901e16985728d1c927c` — CHAT-2 Phase-16 semantic report.

No newer Phase-16 runtime commit appeared. Validation therefore remains pinned exactly to `74aa96ac31a94e70a1ad4d265937fa646d21a2bd`.

Result: **PASS**.

## 2. Exact CI — PASS

GitHub Actions evidence:

- workflow: `Build & Release RPG OS ALPHA`;
- run number: `329`;
- run ID: `31586469466`;
- head SHA: `74aa96ac31a94e70a1ad4d265937fa646d21a2bd`;
- conclusion: `success`.

The job contains a completed successful `Run JVM unit tests` step and successful signed ALPHA APK build.

Result: **PASS**.

## 3. Robolectric SDK 35 correction — PASS / environment-only

The target commit changes only `PlayerCommandContractTest.kt`:

- adds `org.robolectric.annotation.Config`;
- adds `@Config(sdk = [35])`.

It does not alter production `PlayerCommandModel.kt`, `PlayerCommandRegistry.kt`, `PlayerCommandCoreCodecs.kt`, schema, migrations, or accepted Phase 3–15 authorities.

The correction therefore fixes the test environment for Robolectric 4.14.1 without removing assertions or weakening production semantics.

Result: **PASS**.

---

# PHASE-16 CONTRACT SCOPE

## 4. Transient typed request boundary — PASS

The candidate keeps:

```text
PlayerCommand
!= StatePatch
!= PlayerChangeSet
!= committed mutation
!= DB transaction
!= event
!= FinancialTransaction
!= domain fact
!= execution authority
```

`PlayerCommand` contains an explicit schema version, stable command UID, campaign UID, generic actor ref, stable kind UID, typed payload, provenance, optional causation/correlation/requested order, typed preconditions and typed extensions.

No production Phase-16 file imports `SQLiteDatabase`, repository/store mutation APIs, or `StatePatchEngine`.

Result: **PASS**.

## 5. Stable identity / campaign isolation — PASS

`PlayerCommandIdentity.compare()` scopes command identity by `(campaignUid, commandUid)`.

Within the same scope:

- exact canonical content => `SAME_LOGICAL_COMMAND`;
- changed immutable content => `COMMAND_IDENTITY_CONFLICT`.

Different campaign => `DISTINCT_COMMAND`, preventing silent campaign rebinding.

The fingerprint includes actor, command kind, typed payload, provenance, causation/correlation, requested order, preconditions and extensions because it hashes canonical encoding.

Result: **PASS for known canonical fields**.

## 6. Typed command registry / codecs — PASS for known kinds

The core registry maps each known `commandKindUid` to one concrete `TypedCommandCodec<P>` with a `KClass<P>` payload type.

Observed behavior:

- unknown command kind => reject;
- in-memory kind/payload mismatch => `COMMAND_PAYLOAD_TYPE_MISMATCH`;
- unsupported top-level command schema version => reject;
- malformed known refs and invalid numeric intent shapes => reject;
- no raw-map fallback.

The exposed registry API is immutable after construction; there is no runtime mutation dispatcher.

Result: **PASS for registered known kinds**.

## 7. Deterministic known-field serialization / fingerprint — PASS

For known typed fields, canonical encoding uses fixed object construction order and exact JSON integer encoding for Kotlin `Long` values. `decode()` reconstructs the typed command and validates it; `fingerprint()` is SHA-256 over canonical encoding.

`PlayerCommandContractTest` verifies repeated encoding, round-trip encoding, fingerprint stability and parallel fingerprint determinism.

Result: **PASS for known-field inputs**.

## 8. Structural preconditions — PASS

The contract exposes only typed optimistic preconditions:

- `ExpectedRecordVersion`;
- `ExpectedLifecycleState`.

There are no table/column/value SQL predicates or generic mutation conditions.

Result: **PASS**.

## 9. No StatePatch / no hidden writer — PASS

No `STATE_PATCH` command kind exists. Phase-16 production files contain no table/column/SQL mutation primitive and no callbacks to DB/repositories/stores.

The dedicated no-mutation fixture snapshots representative accepted authoritative tables, then constructs, validates, encodes, decodes and fingerprints a command; counts remain unchanged.

Result: **PASS**.

## 10. No Phase-16 persistence — PASS

Compare `173e501... -> 74aa96a...` shows no schema/migration changes and no new command persistence table/inbox/outbox/queue/execution-status authority.

No synthetic legacy command history is introduced.

Result: **PASS**.

## 11. No Phase-17+ runtime implementation — PASS

The exact runtime diff contains only Phase-16 PlayerCommand model/registry/codecs/tests plus audit documents. It does not add production `PlayerChangeSet`, `PlayerDomainEngine`, `WorldRuleProvider`, `ProgressionEngine` or command execution transaction machinery.

A Phase-17 architecture document exists only as report documentation and is not runtime.

Result: **PASS**.

---

# RELEASE BLOCKER P16-INT-01 — UNKNOWN SERIALIZED FIELDS ARE SILENTLY LOST

## Violated invariant

The Phase-16 canonical serialization/identity boundary must not silently erase unknown semantic input.

Required policy from the Phase-16 contract/oracles:

```text
unknown semantic field
=> deterministic reject
OR
=> exact preservation through a registered typed/versioned extension mechanism
```

Forbidden:

```text
serialized input contains additional semantic field
-> decode accepts
-> field disappears from typed command
-> re-encode drops it
-> fingerprint describes a different/reduced command
```

The candidate implements the forbidden behavior.

## Exact runtime path

`app/src/main/java/com/rpgos/app/PlayerCommandRegistry.kt`

`PlayerCommandKindRegistry.decode()` parses a `JsonObject` and reads only required/optional known fields with helpers such as:

- `reqInt`;
- `reqString`;
- `reqObject`;
- `reqArray`;
- `optString`;
- `optLong`.

No allowed-key-set validation occurs for the root envelope.

The same is true for:

- actor decode;
- provenance decode;
- `DomainRef` decode;
- precondition decode;
- extension decode;
- every core payload decoder in `PlayerCommandCoreCodecs.kt`.

## Minimal reproducer A — root field

1. Create a valid TRAIN command and serialize it using `registry.encode(command)`.
2. Add to the root JSON:

```json
"requestedFinalResult":"AUTHORITATIVE_OVERRIDE"
```

3. Call `registry.decode(modifiedJson)`.
4. Re-encode the returned command.

### Expected

Deterministic structural rejection such as `UNKNOWN_COMMAND_FIELD`, or explicit registered typed-extension preservation.

### Actual

Decode succeeds because all known required fields remain valid. The unknown field is ignored. Re-encode drops it. Fingerprint then represents the reduced older command rather than the submitted serialized semantics.

## Minimal reproducer B — typed payload field

Start with valid TRAIN payload JSON and add:

```json
"finalStatValue":999999
```

The TRAIN codec reads only `focus`, `effortUnits` and `methodUid`. The added field disappears on decode/re-encode and does not participate in identity.

## Why release-blocking

Stable UID + canonical fingerprint is Phase-16 identity authority. Two different serialized requests can currently normalize to one semantic fingerprint without any explicit compatibility/version rule.

This violates deterministic canonical identity and malformed/unknown command rejection at the contract boundary.

---

# RELEASE BLOCKER P16-INT-02 — UNSUPPORTED EXTENSION VERSION ACCEPTED

## Violated invariant

WORK-066 structural validation requires a typed extension version to be supported, not merely positive.

A typed/versioned extension must not claim an unknown future schema and then be interpreted with the current decoder shape.

## Exact runtime path

`app/src/main/java/com/rpgos/app/PlayerCommandRegistry.kt`

`validateExtension()` currently checks:

```text
extensionKindUid nonblank
schemaVersion > 0
NamespacedText value nonblank
```

There is no supported-version registry or exact allowed-version rule.

`decodeExtension()` selects only discriminator `NAMESPACED_TEXT` and constructs `NamespacedTextCommandExtension` with whatever positive `schemaVersion` was supplied.

## Minimal reproducer

Construct:

```text
NamespacedTextCommandExtension(
  extensionKindUid = "TEST:EXT",
  schemaVersion = 999,
  value = "typed"
)
```

Attach it to an otherwise valid command and call `registry.validate()` or encode/decode.

### Expected

Deterministic reject because version 999 is not explicitly supported/registered.

### Actual

Validation succeeds because `999 > 0`; encoding and decoding use the same current `NAMESPACED_TEXT` shape.

## Why release-blocking

This defeats the purpose of typed extension schema versioning and creates the same silent-forward-compatibility ambiguity as P16-INT-01.

---

# TEST / CI ASSESSMENT

## 12. `PlayerCommandContractTest` assertions — PARTIAL

The test class genuinely executes under Robolectric SDK 35 and contains real assertions for:

- stable command identity;
- changed-payload conflict;
- campaign/actor behavior;
- kind/payload mismatch;
- unknown command kind;
- malformed ref shape;
- StatePatch/raw mutation field absence on representative payloads;
- deterministic known-field round-trip/fingerprint;
- DevelopmentProject intent-only fields;
- no mutation of representative canonical tables;
- UID separation;
- typed optimistic preconditions;
- parallel fingerprint determinism.

However, it does **not** cover the two failing release gates:

- unknown extra root/payload/object keys must reject or be preserved;
- unsupported positive extension schema versions must reject.

Thus CI #329 can be green while these contract-integrity blockers remain.

## 13. Phase 3–15 regression — PASS within changed/CI-covered scope

The Phase-16 runtime delta from accepted Phase 15 does not modify accepted schema, migrations or authoritative writer implementations. Full JVM tests and signed build pass on the exact target SHA.

No Phase 3–15 regression was found in the inspected scope.

---

# MINIMAL CORRECTION SCOPE

Phase 16 only.

Required minimum:

1. add strict allowed-key validation for every decoded Phase-16 JSON object boundary:
   - root envelope;
   - actor;
   - provenance;
   - `DomainRef`;
   - all core payload objects;
   - preconditions;
   - extensions;
2. reject unknown keys deterministically unless semantics are represented inside an explicitly registered typed/versioned extension;
3. enforce supported extension versions, ideally through `(extensionKindUid, schemaVersion)` registration or, for the current single concrete extension schema, explicit acceptance of only the implemented version;
4. add regression tests for unknown root field, unknown payload field, nested-object unknown fields and unsupported extension version;
5. preserve all current known-field round-trip/fingerprint/no-mutation tests.

No schema migration, command persistence, PlayerChangeSet, PlayerDomainEngine or Phase-17+ implementation is required.

---

# FINAL VERDICT

# PHASE 16 INTEGRITY REVALIDATION: FAIL

Validated runtime SHA:
`74aa96ac31a94e70a1ad4d265937fa646d21a2bd`

Exact CI:
`GitHub Actions #329 / run ID 31586469466 / head 74aa96ac31a94e70a1ad4d265937fa646d21a2bd / SUCCESS`

Release blockers:

- `P16-INT-01` — silent unknown-field semantic loss during decode/canonicalization;
- `P16-INT-02` — unsupported positive extension schema versions accepted.

Phase 16 is not marked COMPLETE/ACCEPTED by this report.

Phase 17 was not started.
