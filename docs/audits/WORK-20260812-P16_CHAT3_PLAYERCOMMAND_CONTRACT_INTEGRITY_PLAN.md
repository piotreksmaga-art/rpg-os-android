# CHAT-3 — Phase 16 PlayerCommand Contract / Integrity Plan

Status: READ-ONLY CONTRACT / INTEGRITY AUDIT PLAN — NO FINAL VERDICT

Repository: `piotreksmaga-art/rpg-os-android`
Role: CHAT-3 / READ-ONLY contract-integration auditor
Accepted Phase-15 runtime: `173e501fbe832980bb4eaf177c5ba34d93cd5f37`
Fresh master at plan finalization: `9e3825e624bd108646aee01847d7f0f19d6dd20e`
Current Phase-16 state: implementation-in-progress (`WORK-20260810-067` series); no final CHAT-1 result SHA has been declared to this auditor.
Allowed write scope: this report only.

This document defines the independent Phase-16 Contract / Integrity Revalidation plan. It does not implement runtime, schema, migrations, tests, PlayerChangeSet, PlayerDomainEngine, WorldRuleProvider, ProgressionEngine, or any Phase-17+ component. It intentionally does **not** issue a Phase-16 PASS/FAIL before a final CHAT-1 result SHA is provided.

---

## 1. Canonical basis

The revalidation will be grounded in:

- `docs/RPG_OS_MASTER_ARCHITECTURE.md`;
- `docs/RPG_OS_IMPLEMENTATION_ROADMAP.md`;
- `docs/PARALLEL_WORK_COORDINATION.md`;
- `docs/audits/WORK-20260810-066_PHASE16_PLAYERCOMMAND_ARCHITECTURE.md`;
- accepted Phase-15 runtime `173e501fbe832980bb4eaf177c5ba34d93cd5f37`;
- final CHAT-1 Phase-16 result SHA and exact tests/CI attached to that SHA.

Binding architecture:

```text
Player/World Action
-> PlayerCommand
-> PlayerDomainEngine
-> Rule Pipeline
-> WorldRuleProvider
-> Mechanics
-> InvariantValidator
-> PlayerChangeSet
-> TurnTransaction
-> COMMIT
```

Phase 16 owns only the typed, immutable, transient command intent contract plus deterministic structural validation/serialization/identity semantics.

Hard boundary:

```text
PlayerCommand
!= StatePatch
!= PlayerChangeSet
!= committed mutation
!= DB transaction
!= event
!= financial transaction
!= domain record
!= execution authority
```

---

## 2. Fresh-master observation before final SHA

At report finalization, fresh master is `9e3825e624bd108646aee01847d7f0f19d6dd20e` and already contains an in-progress `WORK-20260810-067` Phase-16 implementation series.

A compare from accepted Phase-15 runtime `173e501...` to current master shows Phase-16 production changes currently limited to:

- `PlayerCommandModel.kt`;
- `PlayerCommandRegistry.kt`;
- `PlayerCommandCoreCodecs.kt`;
- `PlayerCommandContractTest.kt`;
- audit reports.

No schema/migration/accepted Phase-3–15 authoritative writer file appears in that current compare.

This is a scoping observation only. Final revalidation will restart from the exact CHAT-1 result SHA and will not assume current master remains unchanged.

---

# FINAL REVALIDATION PROCEDURE

## 3. Freshness / pinning gate

Before any final verdict:

1. fetch fresh master;
2. identify exact CHAT-1 final result SHA;
3. compare accepted Phase-15 runtime `173e501...` -> final Phase-16 SHA;
4. distinguish runtime commits from report-only commits;
5. inspect exact CI for the final SHA;
6. stop/re-pin if a newer Phase-16 runtime candidate appears during audit.

No verdict may be based on a moving branch head or on a pre-final intermediate commit.

---

# CONTRACT SURFACE GATES

## 4. `schemaVersion` contract

Validate that:

- command schema version is explicit and positive;
- it is command-contract version, not SQLite schema version;
- encode preserves it exactly;
- decode rejects unsupported versions deterministically;
- no implicit upgrade/downgrade or version fallback occurs;
- unsupported extension schema version is rejected when extension contract defines a supported version set.

Required attacks:

```text
supported version -> ACCEPT
0 / negative version -> REJECT
future unknown version -> REJECT
missing version -> REJECT
wrong JSON numeric form where exact Int is required -> REJECT
```

## 5. Stable `commandUid`

Validate:

- mandatory nonblank stable UID;
- same campaign + same commandUid + identical semantic command -> same logical command identity;
- same campaign + same commandUid + changed immutable semantic content -> deterministic identity conflict;
- different commandUid -> distinct commands even with otherwise identical payload;
- display text, timestamps or mutable labels are not used as identity authority.

Phase 16 must define identity semantics only; no persisted replay/execution state is required here.

## 6. Campaign binding

Validate:

- `campaignUid` is mandatory and immutable;
- encode/decode never rebinds to active/current campaign;
- fingerprint includes campaign binding;
- same `commandUid` in different campaigns is not conflated;
- no registry/codec consults an active-campaign singleton during serialization.

Required adversarial case:

```text
encode command(campaign=C)
change active campaign to D
then decode
=> campaign remains C
```

if any active-campaign facility is reachable in the implementation.

## 7. Actor ref shape

Structural-only gate:

```text
CommandActorRef(actorKindUid, actorUid)
```

Validate:

- both fields nonblank;
- actor identity is generic, not forced to ActivePlayer;
- structural validation does not claim authorization/existence;
- no parallel persisted actor registry is introduced;
- actor is included in semantic identity/fingerprint.

Ghost actor with structurally valid UID may pass Phase-16 structural validation; existence/authorization belongs later.

## 8. Command kind -> typed payload uniqueness

Core invariant:

```text
one commandKindUid -> exactly one typed payload schema/codec
```

Validate all registered entries and registry construction APIs.

Required cases:

- correct kind + correct typed payload -> ACCEPT;
- correct kind + wrong payload type -> REJECT;
- unknown kind -> REJECT;
- duplicate/collision registration of two different payload schemas under one kind UID -> REJECT, never last-wins silently;
- same kind + semantically same codec schema may only be accepted if registry API explicitly defines idempotent duplicate registration; otherwise reject duplicates uniformly.

If the API accepts a `Map`, test whether duplicate keys can be silently collapsed before registry validation. If that makes schema collision detection impossible at the registry boundary, classify it explicitly during final audit against the architecture requirement.

Registry must map kind -> typed schema/codec/structural validator only, never mutation callback.

## 9. Deterministic serialization

For representative commands from every command family and edge numeric values:

```text
decode(encode(command)) == semantic command
encode(command) == encode(command) byte-for-byte
encode(decode(encode(command))) == encode(command)
```

Canonical serialization must preserve:

- schemaVersion;
- commandUid;
- campaignUid;
- actor kind + UID;
- command kind discriminator;
- exact payload type/data;
- provenance;
- causation/correlation IDs;
- requestedEffectiveOrder;
- typed preconditions;
- typed extensions;
- Long integer values exactly.

It must not reorder semantic list fields unless the contract explicitly declares them set-like and canonicalizes them.

## 10. Deterministic canonical equality / fingerprint

Validate fingerprint/equality over the complete documented semantic command.

Required changes that must alter fingerprint for same `(campaignUid, commandUid)` and therefore trigger identity conflict:

- actor;
- command kind;
- any payload field;
- provenance semantic field;
- requestedEffectiveOrder;
- causation/correlation when contract declares them semantic;
- precondition contents/order if list order is semantic;
- extension contents/order if list order is semantic;
- schemaVersion.

Fingerprint algorithm must not depend on JVM object identity, hash-map iteration order, wall clock, locale, random seed, active campaign, DB state, or thread scheduling.

## 11. Unsupported kind rejection

Validate at all public entry points:

- `validate`;
- `encode`;
- `decode`;
- fingerprint/equality when they transitively encode.

No generic fallback codec and no `Map<String, Any?>` interpretation for unknown command kinds.

## 12. Unsupported version rejection

Validate both command and typed extension version policy.

No best-effort interpretation of future versions unless architecture explicitly adds an extension compatibility rule before final SHA.

## 13. Payload-kind mismatch rejection

Construct an in-memory `PlayerCommand` whose `commandKindUid` declares kind A while payload instance is schema B.

Expected: deterministic structural rejection before serialization/fingerprint can treat it as valid.

Repeat through any deserialization route by crafting JSON with discriminator A and payload shape B.

## 14. No raw SQL contract

Inspect model, codec, registry and all Phase-16 files for:

- SQL strings;
- table names/columns as mutation primitives;
- `SQLiteDatabase` dependency in production command code;
- generic operation/op/value structures equivalent to StatePatch.

Tests may open a DB only to prove no mutation; production codec/registry must not.

## 15. No arbitrary StatePatch

Reject any Phase-16 kind/payload that can express:

```text
table + operation + key + column + arbitrary value
```

No `STATE_PATCH` command kind, embedded `StatePatch`, generic JSON mutation object, or bridge that simply wraps the old StatePatchEngine.

## 16. No mutation callbacks / function pointers

Inspect registry/codec/model public and private fields for:

- lambdas taking repository/DB/store and mutating state;
- function pointers/method refs used as command handlers;
- callback registration such as `kind -> execute(...)`;
- closures capturing writer objects.

Typed structural validation functions are allowed only if pure.

## 17. No hidden DB writer inside codec/registry

Static inspection plus runtime no-mutation fixture:

1. establish counts/snapshots in representative Phase-3–15 authoritative tables;
2. construct command;
3. validate;
4. encode;
5. decode;
6. fingerprint/equality;
7. confirm authoritative data is unchanged.

If production Phase-16 code imports/opens SQLite, repositories, stores, StatePatchEngine or writer services, investigate as a likely scope violation.

## 18. No persisted command execution authority

Search exact final diff and repository for new Phase-16 persistence such as:

- command status;
- executed/failed/pending markers;
- retry/replay outcome;
- inbox/outbox/queue;
- canonical command history.

Phase 28 owns persisted double-commit protection; later transaction/event phases own execution history.

## 19. UID separation

Prove contract does not conflate:

```text
commandUid
!= transactionUid
!= eventUid
!= turnUid
!= domainRecordUid
!= financialTransactionUid
!= projectUid/workUid/outcomeUid
!= causationUid
!= correlationUid
```

Field names, constructors/codecs and fixtures must not default one of these to commandUid as canonical identity.

Correlation/causation may legitimately equal external identifiers if explicitly supplied by caller; this must not make them command identity aliases.

---

# NO-PERSISTENCE GATE

## 20. Schema diff

Compare accepted Phase-15 runtime to final Phase-16 SHA for any modification of:

- CurrentSchema routing;
- MigrationManager/Phase migrations;
- CREATE/ALTER/DROP statements;
- source-of-truth/table registries;
- backup/restore schema behavior.

Expected Phase-16 result: no schema change.

## 21. Forbidden persistence artifacts

Repository-wide search for new Phase-16 equivalents of:

```text
player_commands
command_inbox
command_outbox
command_queue
command_execution_status
command_results
command_replay_status
```

Also inspect migrations for command markers/table creation and importers for synthetic legacy command history.

If any such canonical persistence appears without a new explicit canonical requirement, final result is a Phase-16 scope violation.

No PRAGMA migration suite is invented if schema remains unchanged.

---

# SERIALIZATION INTEGRITY

## 22. Round-trip matrix

Run round-trip tests for at least one payload of every registered command kind, not only a representative subset.

For optional fields test both `null/empty` and populated forms.

For integer fields include:

- 0 where structurally legal;
- 1;
- maximum/minimum legal values;
- values above 2^53 to prove JSON/JVM Long exactness;
- `Long.MAX_VALUE` when the intent contract permits it.

No Float/Double conversion for integral intent values.

## 23. Discriminator preservation

Verify kind UID exists in canonical serialization and decoder selects the codec solely through the declared registered kind.

Decoder must not infer type heuristically from payload keys.

## 24. Provenance preservation

`CommandProvenance` required semantic fields must round-trip exactly and be fingerprint-covered.

Empty/invalid required provenance source kind must reject structurally.

## 25. Unknown extension behavior

Craft serialization with an extension discriminator/version unknown to registry/decoder.

Expected: REJECT.

Never:

- discard silently;
- turn into generic map;
- preserve only opaque JSON while claiming semantic command equality.

If the architecture later adds a deliberately opaque extension envelope, that must be explicit and versioned before it can be accepted.

## 26. Unknown payload behavior

Unknown command kind or unregistered payload schema -> REJECT.

No generic map fallback.

For unexpected fields inside a known payload, document and test the codec policy. If ignored fields can change external semantics while disappearing from canonical re-encoding, classify the risk against deterministic canonical identity rather than silently assuming safety.

---

# TYPE REGISTRY / CONCURRENCY

## 27. Registry purity

Registry may contain only immutable/pure metadata:

```text
kind UID
payload class/schema
codec
structural validator
```

No repositories, DB handles, StatePatch engine, stores, mutation callbacks or execution handlers.

## 28. Registry collision tests

Test deterministic initialization with:

- unique kinds;
- duplicate kind same schema;
- duplicate kind different schema;
- blank kind;
- unknown lookup;
- repeated initialization.

Collision outcome must be deterministic and must not depend on registration order/thread timing.

## 29. Concurrent read determinism

SQLite concurrency suite is not required.

If registry is shared/immutable, run parallel calls over the same command for:

- validate;
- encode;
- decode;
- fingerprint.

All returned canonical serialization/fingerprints must be identical and no mutation/race exception may occur.

If registry supports runtime registration, additionally test concurrent registration collision behavior; initialization must either be immutable-before-use or have deterministic atomic duplicate rejection.

---

# PRECONDITIONS

## 30. Structural optimistic preconditions only

Allowed family includes architecture-compatible typed expectations such as:

```text
ExpectedRecordVersion(DomainRef, expectedVersion)
ExpectedLifecycleState(DomainRef, expectedStateUid)
```

Validate:

- typed target shape;
- exact integer/state value structural rules;
- deterministic round-trip/fingerprint;
- no DB lookup during Phase-16 validation;
- no claim that a successful precheck guarantees later commit.

Forbidden shapes:

```text
ExpectedColumnValue(table, column, value)
SqlPredicate(...)
WhereClause(...)
RawQuery(...)
```

Preconditions must not recreate StatePatch or SQL predicates indirectly.

---

# REGRESSION / AUTHORITY CONTAINMENT

## 31. Phase 3–15 diff audit

Compare final Phase-16 SHA against `173e501...`.

Expected: Phase-16 additions/changes only, with no semantic modifications to accepted authoritative DB domains.

Inspect specifically:

- SourceOfTruthRegistry;
- CurrentSchema/MigrationManager;
- Player State;
- Stats/Resources;
- Skills/Techniques;
- Inventory/Equipment;
- Ownership;
- Finance;
- Assets/Liabilities;
- DevelopmentProject;
- Campaign Truth.

Any changed authoritative semantics require an explicit Phase-16 necessity analysis; schema/writer changes are presumptively out of scope.

## 32. State non-mutation regression

A structural command operation must leave representative authoritative rows byte/logically unchanged.

No main Phase-16 gate requires `PRAGMA integrity_check` or `foreign_key_check` because Phase 16 should not change persistence. If final diff changes schema, that is first treated as scope evidence requiring explanation, not normalized into a migration test plan.

---

# FINAL RESULT FORMAT AFTER CHAT-1 SHA

## 33. Required final evidence

Final report will state:

```text
PHASE 16 CONTRACT / INTEGRITY REVALIDATION: PASS
```

or

```text
PHASE 16 CONTRACT / INTEGRITY REVALIDATION: FAIL
```

for exactly one final CHAT-1 runtime SHA, with exact CI evidence.

For every failure/blocker include:

- exact invariant;
- minimal reproducer;
- expected vs observed behavior;
- whether contract, codec, registry, serialization or scope boundary is responsible;
- minimal correction scope;
- explicit statement that CHAT-3 did not implement the correction.

For PASS include every gate above and explicit no-persistence/no-authority-mutation evidence.

---

## 34. Stop boundary

This plan does not mark Phase 16 COMPLETE.

This plan does not implement or begin Phase 17.

Final PASS/FAIL is intentionally deferred until CHAT-1 supplies its final Phase-16 result SHA.