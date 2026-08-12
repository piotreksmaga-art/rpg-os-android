# CHAT-2 — Phase 16 Final Semantic Revalidation — Hotfix4

Role: CHAT-2 / READ-ONLY semantic auditor
Repository: `piotreksmaga-art/rpg-os-android`
Validated runtime SHA: `2472879e8b1c360837fa45b7b7a356175c96a1db`
Exact CI: GitHub Actions `#345`, run ID `31614230512`, head SHA `2472879e8b1c360837fa45b7b7a356175c96a1db`, `SUCCESS`
Allowed write scope: this report only.

# PHASE 16 SEMANTIC REVALIDATION: PASS

This audit independently revalidates the exact Phase-16 runtime candidate above. It does not carry forward prior PASS/FAIL results automatically, does not modify production/test runtime, does not mark Phase 16 globally accepted, and does not start Phase 17.

## 1. Fresh master / candidate history

At validation start, fresh `master` resolved to exact runtime candidate `2472879e8b1c360837fa45b7b7a356175c96a1db`. No later Phase-16 production/test runtime was present. Earlier runtime `9a4407a5003694e49f6cc1255cc7a0f81b699289` and earlier CHAT-2/CHAT-3/CHAT-5 reports remain in repository history.

Runtime changed after target: **NO** at validation start.

## 2. Canonical Phase-16 semantic boundary

WORK-066 and the CHAT-2 semantic oracle require:

```text
PlayerCommand = typed transient intent/request

PlayerCommand
!= StatePatch
!= PlayerChangeSet
!= mutation
!= committed fact
!= event
!= transaction
!= persistence authority
```

The candidate retains the immutable typed `PlayerCommand` envelope with schema version, stable command UID, campaign UID, actor ref, command kind, typed payload, provenance, causation/correlation, requested effective order, typed preconditions and typed extensions.

No Phase-17 runtime (`PlayerChangeSet`, `PlayerDomainEngine`, `WorldRuleProvider`, `ProgressionEngine`, command execution engine) is present in the candidate tree.

Result: **PASS**.

## 3. Hotfix4 primary blocker — public codec losslessness

Previous blocker:

```text
registry.codec(kindUid).decode(JsonObject)
```

could bypass payload `requireOnlyKeys(...)` even though canonical serialized `registry.decode(serialized)` was fail-closed.

Hotfix4 changes `TypedCommandCodec` to own the public decode path:

```text
fun decode(obj: JsonObject): P =
    decodeKnownFields(obj.requireOnlyKeys(allowedKeys))

protected abstract fun decodeKnownFields(obj: JsonObject): P
```

Core concrete codecs now implement only `decodeKnownFields`.

Semantic consequence: a normal public caller cannot invoke typed payload construction before the shared allowed-key guard. The guard is based on key membership, so unknown values of every JSON value category are rejected equally before typed construction:

- String — reject;
- null — reject;
- boolean — reject;
- number — reject;
- object — reject;
- array — reject.

Representative core domains checked:

- TRAIN — reject unknown payload field;
- TRANSFER_FUNDS — reject unknown finance-result-like field;
- TRANSFER_OWNERSHIP — reject unknown final-owner-like field;
- START_PROJECT — reject unknown progress/result-like field;
- valid direct TRAIN payload — accept.

This closes the lossy path:

```text
semantic payload with extra field
-> direct public codec decode
-> extra field disappears
-> reduced typed payload
-> canonical command/fingerprint
```

Result: **PASS**.

## 4. Alternative public decode surface audit

Reviewed Phase-16 API surface around:

- `TypedCommandCodec`;
- `PlayerCommandKindRegistry.codec(...)`;
- registry `decode(...)`;
- codec `decode(...)`;
- `encode(...)` / `encodeUntyped(...)`;
- `validate(...)` / `validateUntyped(...)`;
- `fingerprint(...)`;
- core codec construction.

Findings:

1. serialized root decode remains guarded by duplicate-key pre-scan, root allowed-key validation, typed root readers and final command validation;
2. direct public codec decode is now self-guarded;
3. `decodeKnownFields` is protected and the core codecs expose no second public raw `JsonObject -> typed payload` hook;
4. core codec construction routes all payload decoders through that shared guarded method;
5. fingerprint calls canonical `encode(command)`, which validates the command first;
6. no alternate Phase-16 raw-map/StatePatch decoder was found.

Result: **PASS**.

## 5. P16-HOTFIX4 regression quality

`PlayerCommandPublicCodecHotfix4Test` contains real assertions for:

- direct TRAIN unknown field;
- finance unknown field;
- ownership unknown field;
- DevelopmentProject unknown field;
- unknown null/object/array;
- valid direct decode;
- canonical registry decode;
- strict String via direct codec;
- strict numeric via direct codec;
- deterministic encode/fingerprint after direct decode;
- proof that unknown field is rejected before typed payload construction;
- kind/payload mismatch;
- unknown command kind;
- duplicate-key canonical serialized path;
- extension-version contract;
- zero authoritative DB mutation;
- Phase 3–15 baseline accessibility.

The omitted explicit boolean/number unknown-value fixtures are not a semantic gap because `requireOnlyKeys()` rejects by key membership before reading the value. Therefore the same unknown key deterministically rejects independent of value type.

Result: **PASS**.

## 6. Strict numeric regression

Hotfix3 remains active in the exact candidate:

- required Int/Long readers reject quoted numerics;
- optional Long rejects quoted numerics when present;
- booleans reject as numeric type errors;
- object/array reject as numeric type errors;
- required null retains missing-required semantics;
- optional absent/null retains previous optional semantics;
- overflow/out-of-range is distinguished as invalid numeric value;
- canonical legal JSON numbers remain accepted.

Representative semantic surfaces remain centralized through the same helpers:

- root `schemaVersion`;
- `requestedEffectiveOrder`;
- `ExpectedRecordVersion.expectedVersion`;
- extension `schemaVersion`;
- effort/resource amounts;
- quantities;
- ownership basis points;
- financial minor-unit amounts/principal;
- project progress/work intent units.

Extension version matrix remains:

```text
1             ACCEPT
-1            REJECT
0             REJECT
2             REJECT
999           REJECT
Int.MAX_VALUE REJECT
"1"           REJECT as wrong numeric JSON type
```

Result: **PASS**.

## 7. Strict String / duplicate / unknown-field regression

Strict String readers still require an actual JSON string and reject non-string primitives/containers.

Serialized duplicate-key protection still runs before `Json.parseToJsonElement()` and therefore prevents parser-side duplicate collapse, including nested/deep and escaped-equivalent object member names.

Unknown-field validation remains fail-closed for:

- root command envelope;
- actor;
- provenance;
- DomainRef;
- payload;
- precondition variants;
- extension object.

Result:

- strict String: **PASS**;
- duplicate keys: **PASS**;
- unknown fields: **PASS**.

## 8. Canonicalization / identity / fingerprint

Canonical semantic identity remains scoped by `(campaignUid, commandUid)` and compares the deterministic fingerprint of the fully validated immutable command content.

Lossy canonicalization attempts reviewed include:

- unknown field through serialized registry decode — rejects;
- unknown field through direct public codec decode — now rejects;
- quoted numeric -> number — rejects;
- non-string -> String — rejects;
- duplicate serialized key -> collapsed canonical object — rejected before JSON tree parse;
- unsupported extension version -> supported version — rejects;
- command-kind/payload mismatch — rejects.

Valid command round-trip remains:

```text
encode -> decode -> encode
```

byte deterministic, and fingerprint remains SHA-256 over canonical validated encoding.

No new semantic convergence blocker was found.

Result:

- canonicalization / identity: **PASS**;
- fingerprint: **PASS**.

## 9. Zero authoritative mutation / persistence boundary

Phase-16 operations audited:

- construction;
- validation;
- direct codec decode;
- registry decode;
- encode;
- fingerprint;
- identity comparison.

No Phase-16 production path here receives a SQLite writer/domain-store mutation callback. Regression tests compare representative Phase 3–15 authoritative table counts before/after command operations and require no change.

No command persistence/inbox/outbox/queue/replay ledger/history authority is introduced.

Result: **PASS**.

## 10. Phase 3–15 regression

Hotfix4 production delta is confined to:

- `PlayerCommandRegistry.kt` public codec guard structure;
- `PlayerCommandCoreCodecs.kt` override rename/routing.

It does not modify accepted Phase 3–15 schema, migrations or domain authorities. Full exact-SHA JVM tests pass in CI.

Result: **PASS**.

## 11. Exact CI evidence

Exact workflow:

```text
GitHub Actions #345
run ID 31614230512
head SHA 2472879e8b1c360837fa45b7b7a356175c96a1db
conclusion SUCCESS
```

Build job steps independently verified as successful:

- Validate project;
- Run JVM unit tests (`:app:testDebugUnitTest` workflow step);
- Build signed ALPHA APK;
- Prepare release files;
- Upload Actions artifact;
- Update existing GitHub Release assets;
- overall job/workflow completion.

Green CI is supporting evidence, not the sole semantic basis for this PASS.

## 12. Final matrix

```text
PUBLIC CODEC BLOCKER              PASS
ALTERNATE PUBLIC DECODE SURFACES PASS
STRICT NUMERIC                    PASS
STRICT STRING                     PASS
DUPLICATE KEYS                    PASS
UNKNOWN FIELDS                    PASS
EXTENSION VERSION                 PASS
CANONICALIZATION / IDENTITY       PASS
FINGERPRINT                       PASS
ZERO AUTHORITATIVE MUTATION       PASS
PHASE 3–15 REGRESSION             PASS
PHASE 17 NEGATIVE GATE            PASS
NEW BLOCKERS                      NONE
```

# FINAL VERDICT

# PHASE 16 SEMANTIC REVALIDATION: PASS

for exactly:

`2472879e8b1c360837fa45b7b7a356175c96a1db`

This CHAT-2 PASS does not independently mark Phase 16 globally accepted. Global closure still requires CHAT-2 semantic PASS + CHAT-3 integrity PASS + CHAT-5 adversarial PASS for this exact same runtime SHA. Phase 17 remains blocked pending that coordination rule and a separate start instruction.