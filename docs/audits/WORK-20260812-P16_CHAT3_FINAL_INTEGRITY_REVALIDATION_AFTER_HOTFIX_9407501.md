# CHAT-3 — Phase 16 Final Integrity Revalidation After Hotfix

Status: FINAL INTEGRITY / CONTRACT REVALIDATION — PASS

Repository: `piotreksmaga-art/rpg-os-android`
Role: CHAT-3 / READ-ONLY integrity/contract auditor
Validated runtime SHA: `940750119a24381d53361101be1f8957a508c9e9`
Exact CI: GitHub Actions `#333`, run ID `31593150977`, workflow `Build & Release RPG OS ALPHA`, head SHA `940750119a24381d53361101be1f8957a508c9e9`, `SUCCESS`
Previous failed runtime: `74aa96ac31a94e70a1ad4d265937fa646d21a2bd`
Allowed write scope: this report only.

# PHASE 16 INTEGRITY REVALIDATION: PASS

The exact hotfix candidate closes both previously reported Phase-16 integrity blockers without introducing persistence, schema, mutation authority, or Phase-17 runtime. The hotfix production delta is limited to `PlayerCommandRegistry.kt`; regression coverage is added in `PlayerCommandReleaseBlockerHotfixTest.kt`.

## 1. Fresh master / target pinning

Fresh master resolved to exactly `940750119a24381d53361101be1f8957a508c9e9` at audit start and again before report write. No later Phase-16 runtime candidate was present.

The compare from failed runtime `74aa96a...` to target shows only:

- production: `app/src/main/java/com/rpgos/app/PlayerCommandRegistry.kt`;
- test: `app/src/test/java/com/rpgos/app/PlayerCommandReleaseBlockerHotfixTest.kt`;
- report-only audit files.

No schema, migration, accepted Phase-3–15 domain writer, StatePatch authority, or Phase-17 runtime file is modified.

## 2. Exact CI — PASS

GitHub Actions run `31593150977`, run number `333`, is completed with `conclusion=success` and exact head SHA `940750119a24381d53361101be1f8957a508c9e9`.

The exact run includes successful steps:

- Checkout source;
- Validate project;
- Run JVM unit tests;
- Build signed ALPHA APK;
- artifact/release pipeline.

Therefore the hotfix tests and pre-existing PlayerCommand tests execute as part of the exact candidate's full JVM suite.

## 3. P16-INT-01 — strict lossless decode boundary — PASS

Previous blocker:

```text
unknown serialized semantic field
-> decode succeeds
-> field disappears
-> canonical re-encode/fingerprint describes a reduced command
```

The target rejects this state before semantic command construction.

`PlayerCommandRegistry.decode()` now performs root-envelope `requireOnlyKeys()` with the exact canonical envelope keys.

The payload path resolves the registered codec, obtains its `allowedKeys`, and performs:

```text
root.reqObject("payload").requireOnlyKeys(commandCodec.allowedKeys)
```

before invoking the typed payload decoder.

All core payload classes are explicitly mapped in `corePayloadAllowedKeys(KClass)`.

Nested semantic objects independently reject unknown keys:

- actor: `actorKindUid`, `actorUid` only;
- provenance: `sourceKindUid`, `sourceUid`, `detail` only;
- DomainRef: `kindUid`, `uid` only;
- `ExpectedRecordVersion`: `kind`, `target`, `expectedVersion` only;
- `ExpectedLifecycleState`: `kind`, `target`, `expectedStateUid` only;
- `NamespacedTextCommandExtension`: `kind`, `extensionKindUid`, `schemaVersion`, `value` only.

DomainRefs nested inside payload fields, evidence lists, resource-use lists, targets and preconditions all pass through `decodeRef()`, which applies its own strict key check.

### Adversarial value-shape review

`requireOnlyKeys()` checks object key membership independently of field value. Therefore the same deterministic `UNKNOWN_COMMAND_FIELD` rejection applies when an unknown key contains:

- scalar text/number/boolean;
- `null`;
- object;
- array;
- multiple simultaneous unknown fields;
- a mixture of all known fields plus one unknown field;
- a result-looking field intended to alter canonical meaning.

No accepted route remains where an unknown key can be consumed and silently disappear before canonical representation/fingerprint.

### Surface result

```text
root envelope                         PASS
actor                                 PASS
provenance                            PASS
DomainRef                             PASS
all core payload variants             PASS
ExpectedRecordVersion                 PASS
ExpectedLifecycleState                PASS
extension object                      PASS
nested refs/evidence/resource refs    PASS
```

P16-INT-01 result: **PASS**.

## 4. P16-INT-02 — extension version authority — PASS

The target introduces an explicit supported version constant:

```text
NAMESPACED_TEXT_EXTENSION_SCHEMA_VERSION = 1
```

In-memory structural validation now requires exact equality to the supported version.

Serialized decode also reads `schemaVersion` and rejects anything other than v1 before constructing the extension.

Required matrix:

```text
schemaVersion = 1      -> ACCEPT
schemaVersion = 999    -> REJECT: UNSUPPORTED_EXTENSION_SCHEMA_VERSION
schemaVersion = 2      -> REJECT: UNSUPPORTED_EXTENSION_SCHEMA_VERSION
schemaVersion = 0      -> REJECT: UNSUPPORTED_EXTENSION_SCHEMA_VERSION
schemaVersion = -1     -> REJECT: UNSUPPORTED_EXTENSION_SCHEMA_VERSION
```

The same rule exists in both public semantic routes:

- in-memory `registry.validate(command)`;
- serialized `registry.decode(serialized)`.

No version-agnostic alternative decoder or extension fallback was found.

P16-INT-02 result: **PASS**.

## 5. P16-HOTFIX-01..12 review

The new `PlayerCommandReleaseBlockerHotfixTest` is not a placeholder. It performs concrete exception/equality/state assertions:

1. P16-HOTFIX-01 unknown root field -> `UNKNOWN_COMMAND_FIELD`;
2. P16-HOTFIX-02 unknown payload field -> `UNKNOWN_COMMAND_FIELD`;
3. P16-HOTFIX-03 unknown actor field -> `UNKNOWN_COMMAND_FIELD`;
4. P16-HOTFIX-04 unknown provenance field -> `UNKNOWN_COMMAND_FIELD`;
5. P16-HOTFIX-05 unknown DomainRef field -> `UNKNOWN_COMMAND_FIELD`;
6. P16-HOTFIX-06 unknown precondition field -> `UNKNOWN_COMMAND_FIELD`;
7. P16-HOTFIX-07 unknown extension field -> `UNKNOWN_COMMAND_FIELD`;
8. P16-HOTFIX-08 unsupported extension version -> deterministic rejection in both serialized and in-memory paths;
9. P16-HOTFIX-09 supported v1 extension -> exact deterministic round-trip;
10. P16-HOTFIX-10 encode/decode/encode -> byte-identical canonical serialization;
11. P16-HOTFIX-11 repeated + round-trip fingerprint -> deterministic equality;
12. P16-HOTFIX-12 construct/validate/encode/decode/fingerprint -> no authoritative DB count changes.

The fixtures directly exercise the production methods that contain the hotfix. They are not assertions over duplicated test-side validation logic.

Exact CI #333 runs the complete JVM test task successfully, so P16-HOTFIX-01..12 and the existing `PlayerCommandContractTest` are part of the green exact-candidate suite.

## 6. Stable identity / replay contract — PASS

Canonical serialization remains fixed-field and deterministic for accepted commands. Fingerprint remains SHA-256 over canonical encoded form.

The hotfix does not alter the semantic identity key or accepted-field encoding; it only rejects previously lossy serialized inputs.

Therefore:

```text
same (campaignUid, commandUid) + exact immutable semantic command
=> SAME_LOGICAL_COMMAND

same scoped UID + changed accepted immutable semantic content
=> COMMAND_IDENTITY_CONFLICT

unknown serialized semantic content
=> REJECT before PlayerCommand creation/fingerprint
```

The previously possible convergence:

```text
known command + unknown semantic field
and
known command without field
-> same canonical representation
```

is no longer reachable through successful decode.

Stable identity / lossless semantic boundary result: **PASS**.

## 7. Deterministic error behavior — PASS

Unknown object keys use one stable structural error code:

`UNKNOWN_COMMAND_FIELD`

Unsupported namespaced-text extension versions use:

`UNSUPPORTED_EXTENSION_SCHEMA_VERSION`

The behavior is deterministic across the inspected surface and does not depend on DB state, current campaign, thread scheduling, or codec fallback.

## 8. Authority separation — PASS

The hotfix does not change Phase-16's transient nature.

Repository/diff inspection confirms no new:

- `player_commands` table;
- command inbox/outbox/queue;
- command execution status/replay table;
- schema migration;
- DB writer in PlayerCommand model/registry/codec;
- StatePatch command kind or generic mutation payload;
- PlayerChangeSet implementation;
- PlayerDomainEngine implementation;
- Phase-17 execution authority.

The existing and hotfix no-mutation tests construct/validate/serialize/decode/fingerprint commands around a real Robolectric SQLite database and assert representative Phase-3–15 authoritative table counts are unchanged.

Authority separation result: **PASS**.

## 9. Command-kind / typed payload / malformed input regression — PASS

The hotfix leaves the prior typed registry model intact:

- one registered core kind resolves to one typed codec;
- payload class mismatch rejects;
- unknown command kind rejects;
- unsupported command schemaVersion rejects;
- malformed refs and optimistic preconditions reject structurally;
- no generic map fallback exists.

Strict unknown-field enforcement is additive to this validation rather than a replacement for it.

## 10. Campaign isolation / actor semantics — PASS

The hotfix does not alter campaign/actor identity semantics.

`campaignUid` remains explicit in the envelope and semantic identity. Actor remains generic `actorKindUid + actorUid`. Decode uses serialized fields and does not consult/rebind to active campaign state.

Same textual command UID in another campaign remains a distinct scoped command identity.

## 11. No persistence / schema mutation — PASS

The accepted Phase-15 runtime to Phase-16 candidate diff contains PlayerCommand contract code/tests/reports only. The hotfix delta itself touches only the registry and hotfix tests (plus reports).

No Phase-16 schema or migration exists, which is correct for this transient contract. No synthetic SQLite migration/concurrency requirement is introduced by this audit.

## 12. Phase 3–15 regression — PASS

No accepted Phase-3–15 authoritative runtime/schema file is modified by the hotfix. Exact CI #333 executes the complete JVM unit-test suite and successful signed release build.

The PlayerCommand no-mutation fixtures cover representative authorities including Campaign Truth, Stats, Skills, Techniques, Item instances, Financial Ledger, Assets, DevelopmentProject and project work history.

No regression attributable to the Phase-16 hotfix was found.

## 13. Final gate summary

```text
Validated runtime SHA:
940750119a24381d53361101be1f8957a508c9e9

Exact CI:
GitHub Actions #333
run ID 31593150977
SUCCESS

P16-INT-01:
PASS

P16-INT-02:
PASS

P16-HOTFIX-01..12:
PASS

Stable identity / lossless semantic boundary:
PASS

Authority separation:
PASS

No Phase-16 persistence/schema authority:
PASS

StatePatch bypass:
PASS

No Phase-17 runtime:
PASS

Phase 3–15 regression:
PASS
```

# FINAL VERDICT

# PHASE 16 INTEGRITY REVALIDATION: PASS

for exactly:

`940750119a24381d53361101be1f8957a508c9e9`

No remaining Phase-16 integrity release blocker was found.

This audit does not mark Phase 16 COMPLETE/ACCEPTED and does not start Phase 17.
