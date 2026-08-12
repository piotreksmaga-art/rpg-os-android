# CHAT-5 — Phase 16 PlayerCommand Final Adversarial Validation

Status: FINAL ADVERSARIAL VALIDATION — FAIL

Repository: `piotreksmaga-art/rpg-os-android`
Role: READ-ONLY adversarial auditor
Validated runtime SHA: `74aa96ac31a94e70a1ad4d265937fa646d21a2bd`
Exact CI: GitHub Actions `#329`, run ID `31586469466`, head SHA `74aa96ac31a94e70a1ad4d265937fa646d21a2bd`, `SUCCESS`
Accepted Phase-15 runtime: `173e501fbe832980bb4eaf177c5ba34d93cd5f37`
Oracle: `docs/audits/WORK-20260812-P16_PLAYERCOMMAND_ADVERSARIAL_ORACLE.md`

# PHASE 16 ADVERSARIAL VALIDATION: FAIL

The candidate correctly implements most of the Phase-16 transient typed intent contract and its tests genuinely execute under Robolectric SDK 35, but a release-blocking serialization/identity defect remains: unknown serialized fields are silently discarded rather than rejected or preserved through a registered typed/versioned extension mechanism. This permits semantic command input to be normalized into an older command without caller awareness, changing the semantic command before fingerprint/identity comparison.

A second manifestation exists in `NamespacedTextCommandExtension`: any positive `schemaVersion` structurally validates even though Phase 16 has only one concrete decoder shape and no extension-version registry/codec. Unsupported extension versions therefore do not deterministically reject.

No runtime change was implemented by this audit.

---

## 1. Fresh master / candidate / CI

Fresh master was checked. The newest runtime commit is exactly:

`74aa96ac31a94e70a1ad4d265937fa646d21a2bd`

No later Phase-16 runtime candidate was present at audit start.

The final commit changes only `PlayerCommandContractTest.kt` by adding Robolectric `@Config(sdk = [35])`; it does not change production PlayerCommand semantics.

Exact CI #329 / run `31586469466` is `SUCCESS` for the exact target SHA. The job contains a completed `Run JVM unit tests` step followed by a successful signed release APK build. Therefore the Robolectric/SDK fix caused the tests to run rather than skip or weaken them.

Result: **CI execution PASS**.

---

# 2. Canonical Phase-16 boundary

Required contract confirmed from MASTER / WORK-066 / oracle:

```text
PlayerCommand = typed transient intent/request
PlayerCommand != StatePatch
PlayerCommand != PlayerChangeSet
PlayerCommand != committed state mutation
PlayerCommand != persistence/execution log
PlayerCommand != PlayerDomainEngine
PlayerCommand != WorldRuleProvider
PlayerCommand != ProgressionEngine
```

Construction, structural validation, serialization, deserialization, fingerprinting and identity comparison must be pure with respect to authoritative campaign state.

---

# 3. Mandatory adversarial matrix results

| Gate | Result | Evidence / finding |
|---|---|---|
| CMD-ADV-01 exact same scoped UID + exact payload | PASS | deterministic fingerprint and `SAME_LOGICAL_COMMAND` |
| CMD-ADV-02 same scoped UID + changed immutable payload | PASS | `COMMAND_IDENTITY_CONFLICT` |
| CMD-ADV-03 same UID different campaign | PASS | explicit scoped identity => `DISTINCT_COMMAND`; no active-campaign rebind |
| CMD-ADV-04 same UID different actor | PASS | actor is fingerprinted semantic content; conflict |
| CMD-ADV-05 kind/payload mismatch | PASS | typed codec payload class check rejects |
| CMD-ADV-06 unknown kind | PASS | no generic/raw fallback |
| CMD-ADV-07 unsupported command schemaVersion | PASS | non-current schema rejects |
| CMD-ADV-08 malformed typed ref | PASS | blank/missing malformed known refs structurally reject; well-formed ghost refs remain later-layer concern by design |
| CMD-ADV-09 raw SQL/table/column representation | PASS | no mutation primitive in typed payload contract; SQL-like text remains inert string intent |
| CMD-ADV-10 StatePatch wrapped as command | PASS | no StatePatch command kind or raw Map/SQL payload fallback |
| CMD-ADV-11 caller declares canonical result | PASS | project/stat/finance/ownership payloads express requested intent, not final canonical outcomes |
| CMD-ADV-12 commandUid conflation | PASS | command/cause/correlation/domain identifiers remain separate fields; no automatic aliasing |
| CMD-ADV-13 encode/decode ordinary known command | PASS | known-field round trip/fingerprint deterministic |
| CMD-ADV-14 unknown serialized field / extension version | **FAIL** | unknown keys are silently discarded; unsupported positive extension schema versions validate |
| CMD-ADV-15 arbitrary table/column precondition | PASS | only typed record-version/lifecycle preconditions exist |
| CMD-ADV-16 construct/validate/serialize mutates DB | PASS | dedicated Robolectric fixture compares accepted authoritative table counts before/after; no Phase-16 writer found |
| CMD-ADV-17 registry collision | PASS for exposed API | registry accepts a `Map`; one key has one codec at API boundary, blank key rejected; no last-writer runtime registration API exists |
| CMD-ADV-18 parallel registry/fingerprint determinism | PASS | parallel fingerprint fixture converges to one fingerprint; registry/codecs are immutable/read-only in Phase 16 |

---

# 4. Release blocker — silent unknown-field semantic loss

## Violated invariant

Phase-16 semantic identity must not silently erase serialized command semantics.

The adversarial oracle requires for unknown serialized semantic fields:

```text
reject unknown semantic fields
OR
preserve them through a registered typed/versioned extension mechanism
```

Forbidden outcome:

```text
input includes unknown semantic field
-> decoder ignores field
-> re-encode omits it
-> fingerprint now describes a different/older semantic command
```

This candidate produces the forbidden outcome.

## Exact runtime path

`PlayerCommandKindRegistry.decode(serialized)` in:

`app/src/main/java/com/rpgos/app/PlayerCommandRegistry.kt`

parses the root `JsonObject` and individually reads known fields through helpers such as:

```text
reqInt
reqString
reqObject
reqArray
optString
optLong
```

Neither root decode nor actor/provenance/ref/precondition/extension decode validates the complete key set of each `JsonObject`.

Core payload codecs similarly decode expected keys without a generic strict-object key check.

Therefore additional keys remain syntactically accepted but disappear from the constructed typed command.

## Minimal reproducer A — root semantic field

1. Start from `registry.encode(validTrainCommand)`.
2. Add a root field, e.g.:

```json
"requestedFinalResult":"AUTHORITATIVE_OVERRIDE"
```

3. Call `registry.decode(modifiedJson)`.
4. Re-encode the returned command.

### Expected

One of:

```text
PlayerCommandStructuralException(UNKNOWN_COMMAND_FIELD)
```

or an explicitly registered/versioned extension preserves the field exactly.

### Actual

The field is not read, not validated and not represented by `PlayerCommand`; decode succeeds when all known fields remain valid. Re-encoding drops the added field. Fingerprinting then fingerprints the reduced command.

## Minimal reproducer B — payload semantic field

Take a valid `TRAIN` payload and add:

```json
"finalStatValue":999999
```

The `TRAIN` decoder reads only the known typed payload keys. The unknown field is dropped. The resulting command becomes indistinguishable from the same command that never carried `finalStatValue`.

This directly intersects CMD-ADV-11: typed Kotlin construction cannot expose the result field, but serialized input can carry it and the decoder silently erases rather than rejecting it.

## Why this is release-blocking

Stable command UID and fingerprint are the Phase-16 semantic identity contract. Silent semantic loss means two distinct serialized inputs can normalize to one fingerprint without an explicit compatibility rule. A future caller using a newer schema/producer could believe it submitted additional semantics while the current runtime interprets an older command.

This is not a Phase-18 execution race or Phase-28 persistence issue. It occurs inside the Phase-16 decode/identity boundary itself.

---

# 5. Secondary blocker manifestation — extension schemaVersion not enforced

`NamespacedTextCommandExtension` includes `schemaVersion`, but current structural validation only rejects:

```text
schemaVersion <= 0
```

There is no registry mapping `(extensionKindUid, schemaVersion)` to a codec and no rule requiring the currently supported version.

Minimal reproducer:

```text
NamespacedTextCommandExtension(
  extensionKindUid = "TEST:EXT",
  schemaVersion = 999,
  value = "typed"
)
```

Expected according to WORK-066/oracle: unsupported extension version deterministically rejects unless that version is explicitly registered/supported.

Actual: version `999` passes the generic `>0` structural check and is encoded/decoded by the same version-agnostic shape.

This is the same compatibility/semantic-version family as CMD-ADV-14.

---

# 6. DevelopmentProject adversarial cases

Result: **PASS** for Phase-16 typed construction boundary.

The exposed project command payloads do not contain canonical-result fields for:

- `progressDeltaUnits` / progress-after;
- `resultKind=BREAKTHROUGH`;
- `milestoneAchieved=true`;
- requirement `satisfied=true`;
- canonical outcome UID / committed order;
- final Truth/Technique/Item creation.

They carry project UID, requested lifecycle/status intent, effort intent and evidence/source references. Existing Phase-15 authority remains responsible for whether those references are legal and whether completion/work/milestones actually commit.

The unknown-field blocker above is the exception: serialized unknown result-like fields are currently dropped rather than rejected, which is why the overall verdict remains FAIL.

---

# 7. StatePatch / authority bypass

Result: **PASS**, aside from the serialization strictness defect.

No Phase-16 production type exposes:

```text
table
column
raw SQL
INSERT/UPDATE/DELETE operation
ContentValues
SQLiteDatabase callback
StatePatch payload
Map<String, Any?> mutation fallback
```

Unknown command kinds reject. PlayerCommand does not call `StatePatchEngine` or typed domain stores during construction/validation/serialization.

---

# 8. Persistence boundary

Result: **PASS**.

No Phase-16 `player_commands`, command execution history, inbox, queue, status ledger, outbox or synthetic legacy command history is introduced by the PlayerCommand runtime.

Existing `commandUid` fields on prior domain records remain provenance/evidence fields; they are not a Phase-16 command persistence authority.

---

# 9. Cross-campaign/reference/actor semantics

Result: **PASS for Phase-16 scope**.

- command campaign is explicit and immutable;
- same command UID in a different campaign is a distinct scoped identity, not silent rebind;
- actor is part of semantic fingerprint;
- malformed refs reject structurally;
- well-formed nonexistent/wrong-campaign references may structurally validate because authoritative existence/campaign/authorization resolution belongs to later orchestration/domain layers.

This is consistent with WORK-066 and must not be mistaken for commit authorization.

---

# 10. Robolectric / SDK correction audit

Result: **PASS**.

The final candidate commit only adds:

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
```

to `PlayerCommandContractTest`.

It does not delete assertions or skip the test class. Exact CI #329 records `Run JVM unit tests` as completed successfully for the exact target SHA. The no-mutation SQLite fixture therefore runs in a supported Robolectric Android environment instead of failing due to unsupported target SDK 36.

---

# 11. Accidental Phase 17+ implementation audit

Result: **PASS**.

No production PlayerChangeSet, PlayerDomainEngine, WorldRuleProvider or ProgressionEngine semantics are introduced as part of the Phase-16 PlayerCommand runtime candidate. Report-only Phase-17 architecture documents do not count as runtime implementation.

PlayerCommand remains an intent contract and does not resolve mechanics or produce committed effects.

---

# 12. Phase 3–15 regression

Result: **PASS within inspected/CI-covered scope**.

The Phase-16 runtime adds/transforms PlayerCommand contract files and tests; the final candidate delta itself changes only the Robolectric SDK selection in the Phase-16 test. No accepted Phase 3–15 authoritative store/schema was replaced by the final correction.

Exact CI #329 executes the complete JVM test task and release build successfully.

---

# 13. Future race/TOCTOU gates — not Phase-16 release requirements

The following remain future Phase-18/28 execution/idempotency gates and were not incorrectly required as SQLite write races for transient Phase 16:

```text
CMD-RACE-01 exact retry
CMD-RACE-02 conflicting retry
CMD-RACE-03 stale expected version
CMD-RACE-04 target changed before execution
CMD-RACE-05 ambiguous previous execution outcome
```

Phase 16 preconditions are optimistic intent metadata and must not become a substitute for future authoritative commit-time validation.

---

# 14. Minimal correction scope

Phase 16 only.

Required minimum:

1. add strict allowed-key validation at every decoded JSON object boundary:
   - root envelope;
   - actor;
   - provenance;
   - DomainRef;
   - every core payload;
   - precondition;
   - extension;
2. reject unknown keys deterministically unless they live inside a deliberately registered typed extension mechanism;
3. enforce supported extension schema versions, ideally keyed by `(extensionKindUid, schemaVersion)` or at minimum reject versions other than the explicitly supported current version;
4. add regressions showing:
   - unknown root field rejects;
   - unknown payload field rejects;
   - unknown actor/provenance/ref/precondition/extension field rejects;
   - unsupported extension version rejects;
   - known command round-trip/fingerprint remains unchanged;
   - no DB mutation remains true.

No Phase-17 PlayerChangeSet, Phase-18 engine, persistence table or Phase-3–15 redesign is needed.

---

# FINAL RESULT

```text
Validated runtime SHA: 74aa96ac31a94e70a1ad4d265937fa646d21a2bd
Exact CI: GitHub Actions #329 / run 31586469466 / SUCCESS
Verdict: PHASE 16 ADVERSARIAL VALIDATION: FAIL

CMD-ADV-01 PASS
CMD-ADV-02 PASS
CMD-ADV-03 PASS
CMD-ADV-04 PASS
CMD-ADV-05 PASS
CMD-ADV-06 PASS
CMD-ADV-07 PASS
CMD-ADV-08 PASS
CMD-ADV-09 PASS
CMD-ADV-10 PASS
CMD-ADV-11 PASS for typed construction; serialized unknown-field bypass contributes to CMD-ADV-14 FAIL
CMD-ADV-12 PASS
CMD-ADV-13 PASS for known fields
CMD-ADV-14 FAIL — silent unknown-field loss + unsupported extension version accepted
CMD-ADV-15 PASS
CMD-ADV-16 PASS
CMD-ADV-17 PASS for exposed registry API
CMD-ADV-18 PASS

PlayerCommand intent-only boundary: PASS except serialization strictness blocker
StatePatch/persistence authority bypass: PASS
Robolectric/SDK test execution: PASS
Phase 3–15 regression: PASS in inspected/CI-covered scope
```

Phase 16 is not marked COMPLETE/ACCEPTED by this auditor. Phase 17 implementation was not started.
