# CHAT-3 — Phase 16 Final Integrity Revalidation

Role: CHAT-3 / READ-ONLY INTEGRITY / CONTRACT AUDITOR

Repository: `piotreksmaga-art/rpg-os-android`

Validated runtime SHA: `2472879e8b1c360837fa45b7b7a356175c96a1db`

Exact CI: GitHub Actions `#345`, run ID `31614230512`, head SHA `2472879e8b1c360837fa45b7b7a356175c96a1db`, `SUCCESS`.

Allowed write scope: this report only. No production/test runtime modification. Phase 17 remains blocked.

# PHASE 16 INTEGRITY REVALIDATION: PASS

The exact candidate closes the previous public-codec bypass and preserves all earlier Phase-16 hardening. `TypedCommandCodec.decode(JsonObject)` is now the single public payload decode boundary and self-applies `requireOnlyKeys(allowedKeys)` before entering protected `decodeKnownFields()`. The protected hook is not publicly callable, and the core codec factory overrides only that trusted hook. No alternate public JsonObject -> typed payload route bypassing the shared guard was found.

## Fresh master / target pinning

Fresh master at audit start and immediately before report write resolved to exactly:

`2472879e8b1c360837fa45b7b7a356175c96a1db`

The earlier runtime `9a4407a5003694e49f6cc1255cc7a0f81b699289` and the prior CHAT-2/3/5 audit reports remain in history. No later Phase-16 runtime existed before this report-only commit.

## Public codec blocker

Previous failing path:

```text
registry.codec(kindUid).decode(JsonObject)
-> concrete codec field extraction
-> unknown payload member ignored
-> typed payload produced after information loss
```

Current path:

```text
TypedCommandCodec.decode(JsonObject)
-> requireOnlyKeys(allowedKeys)
-> protected decodeKnownFields(JsonObject)
```

`decode()` is not open and therefore cannot be overridden by concrete codecs. `decodeKnownFields()` is protected, so a normal public caller cannot invoke the trusted hook directly. The core codec factory now overrides only `decodeKnownFields()`.

Result: `P16-INT-PUBLIC-CODEC-01` CLOSED.

## Direct codec attack matrix

Independent code review plus P16-HOTFIX4 coverage confirms:

- TRAIN + unknown String field -> REJECT `UNKNOWN_COMMAND_FIELD`
- TRAIN + unknown null -> REJECT
- TRAIN + unknown boolean -> REJECT by same key boundary
- TRAIN + unknown number -> REJECT by same key boundary
- TRAIN + unknown object -> REJECT
- TRAIN + unknown array -> REJECT
- TRANSFER_FUNDS + unknown field -> REJECT
- TRANSFER_OWNERSHIP + unknown field -> REJECT
- START_PROJECT + unknown field -> REJECT
- valid direct TRAIN payload -> ACCEPT

Because the shared guard checks keys before any trusted field extraction, JSON value type of an unknown member cannot affect the outcome.

## Alternate public decode surface audit

Reviewed Phase-16 public contract surface:

- `TypedCommandCodec`
- `PlayerCommandKindRegistry.codec(...)`
- `PlayerCommandKindRegistry.decode(serialized)`
- `encode(...)`
- `validate(...)`
- `fingerprint(...)`
- `PlayerCommandIdentity.compare(...)`
- core codec factory and nested actor/provenance/ref/precondition/extension decoders

No second publicly reachable raw `JsonObject -> typed PlayerCommandPayload` path was found after HOTFIX4. `registry.decode(serialized)` still performs envelope, payload, nested object, scalar and duplicate-key validation; direct public payload decode is now self-guarded.

## Strict numeric regression

PASS.

`reqInt`, `reqLong`, and `optLong` still require a non-string primitive, reject booleans as wrong type, reject object/array, preserve optional absent/null semantics, and map malformed/out-of-range values to `INVALID_JSON_NUMERIC_VALUE`.

Representative protected surfaces include:

- root `schemaVersion`
- `requestedEffectiveOrder`
- precondition `expectedVersion`
- extension `schemaVersion`
- effort/progress units
- requested amounts
- quantities
- ownership share basis points
- obligation principal / settlement amount intents

Quoted numeric strings are rejected before typed identity/fingerprint.

## Strict String / duplicate / unknown-field regressions

PASS.

`reqString` / `optString` still require actual JSON String primitives.

Pre-parse duplicate-key scanning remains active on the serialized path and rejects root/nested/deep/identical/conflicting/escaped-equivalent duplicates before parser collapse.

Unknown fields remain rejected at envelope, actor, provenance, DomainRef, precondition, extension and payload boundaries. HOTFIX4 additionally restores the same fail-closed behavior for direct public payload decode.

## Extension version contract

PASS.

Namespaced text extension version:

- numeric `1` -> ACCEPT
- numeric `-1`, `0`, `2`, `999`, `Int.MAX_VALUE` -> REJECT `UNSUPPORTED_EXTENSION_SCHEMA_VERSION`
- quoted `"1"` -> REJECT `INVALID_JSON_NUMERIC_TYPE`

No silent future-version acceptance was found.

## Canonicalization / identity / fingerprint

PASS.

For accepted input:

- `decode -> encode` is deterministic
- `encode -> decode -> encode` is byte-deterministic
- fingerprint is deterministic SHA-256 over validated canonical encoding
- public direct codec decode cannot discard unknown semantic members before command construction
- quoted numerics cannot normalize into canonical numeric identity
- same scoped command UID with changed accepted semantics remains an identity conflict

No public lossy canonicalization route was found.

## Authority boundary / Phase 17 negative gate

PASS.

PlayerCommand remains a typed transient intent/request contract only. HOTFIX4 introduces no:

- command persistence table
- inbox/outbox/queue
- replay/history authority
- DB writer
- generic StatePatch authority
- PlayerChangeSet
- PlayerDomainEngine
- WorldRuleProvider
- ProgressionEngine
- Phase-17 execution runtime

Construction, validation, direct codec decode, registry decode, encode and fingerprint remain non-mutating. Existing Phase-3–15 authoritative tables are unchanged by this hotfix.

## Regression suites

Exact CI #345 executes the full JVM unit test task, including the active Phase-16 suites:

- P16-HOTFIX4-01..20
- P16-HOTFIX3-01..22
- P16-HOTFIX2-01..25
- previous P16-HOTFIX suite
- CMD-SEM / PlayerCommand contract tests

The HOTFIX4 tests contain concrete production-path assertions for direct codec unknown-field rejection, representative Finance/Ownership/DevelopmentProject codecs, valid direct decode, canonical determinism, strict String/numeric regressions, extension versions, kind mismatch, unknown kind and zero authoritative DB mutation.

## Exact CI

GitHub Actions:

- workflow: `Build & Release RPG OS ALPHA`
- run number: `#345`
- run ID: `31614230512`
- head SHA: `2472879e8b1c360837fa45b7b7a356175c96a1db`
- conclusion: `SUCCESS`

Successful steps include:

- Validate project
- Run JVM unit tests
- Build signed ALPHA APK
- Prepare release files
- Upload Actions artifact
- Update existing GitHub Release assets
- overall build job

## Final matrix

```text
ROLE:
CHAT-3

VERDICT:
PHASE 16 INTEGRITY REVALIDATION: PASS

VALIDATED RUNTIME SHA:
2472879e8b1c360837fa45b7b7a356175c96a1db

FRESH MASTER BEFORE REPORT WRITE:
2472879e8b1c360837fa45b7b7a356175c96a1db

RUNTIME CHANGED AFTER TARGET:
NO

PUBLIC CODEC BLOCKER:
PASS

ALTERNATE PUBLIC DECODE SURFACES:
PASS

STRICT NUMERIC:
PASS

STRICT STRING:
PASS

DUPLICATE KEYS:
PASS

UNKNOWN FIELDS:
PASS

EXTENSION VERSION:
PASS

CANONICALIZATION / IDENTITY:
PASS

FINGERPRINT:
PASS

ZERO AUTHORITATIVE MUTATION:
PASS

PHASE 3–15 REGRESSION:
PASS

PHASE 17 NEGATIVE GATE:
PASS

EXACT CI:
#345
31614230512
2472879e8b1c360837fa45b7b7a356175c96a1db
SUCCESS

NEW BLOCKERS:
NONE
```

This audit is report-only. Phase 16 is not globally marked ACCEPTED by CHAT-3. Phase 17 was not started.