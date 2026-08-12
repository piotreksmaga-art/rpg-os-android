# WORK-20260812-P16 — Phase 16 PlayerCommand Semantic Oracle

Status: READ-ONLY SEMANTIC ORACLE — FINAL RUNTIME REVALIDATION PENDING

Role: CHAT-2 / READ-ONLY semantic auditor
Repository: `piotreksmaga-art/rpg-os-android`
Accepted Phase-15 runtime baseline: `173e501fbe832980bb4eaf177c5ba34d93cd5f37`
Implementation owner: CHAT-1 / Phase 16
Allowed write scope: this report only.

This oracle does not implement Phase 16, does not modify runtime/schema/migrations/tests, does not mark Phase 16 complete, and does not start Phase 17. It defines the independent semantic contract and test matrix that must be applied later to the exact final Phase-16 runtime SHA supplied by the coordinator.

---

# 1. Canonical sources

This oracle is grounded in:

- `docs/RPG_OS_MASTER_ARCHITECTURE.md`;
- `docs/RPG_OS_IMPLEMENTATION_ROADMAP.md`;
- `docs/PARALLEL_WORK_COORDINATION.md`;
- `docs/audits/WORK-20260810-066_PHASE16_PLAYERCOMMAND_ARCHITECTURE.md`;
- final accepted Phase-15 DevelopmentProject semantics and final Phase-15 reports;
- accepted Phase 1–15 authority boundaries in the runtime.

The canonical Player Domain flow remains:

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

Phase 16 owns only the `PlayerCommand` typed intent/request contract and structural semantics.

---

# 2. Hard semantic boundary

Mandatory invariant:

```text
PlayerCommand = typed intent/request
```

and:

```text
PlayerCommand
!= StatePatch
!= PlayerChangeSet
!= mutation
!= committed fact
!= event
!= transaction
!= FinancialTransaction
!= OwnershipRecord
!= DevelopmentProject fact
!= authoritative result
```

A `PlayerCommand` says what an actor requests. It never means the request is legal, resolved, executed, committed, successful, or authoritative.

Any runtime that makes command construction itself mutate canonical state fails Phase 16.

---

# 3. Required command envelope

The final runtime must expose a semantically equivalent immutable envelope containing at least:

```text
schemaVersion
commandUid
campaignUid
actorRef
commandKindUid
typed payload
provenance
causationUid?
correlationUid?
requestedEffectiveOrder?
typed preconditions
typed extensions
```

Exact names may differ. Semantics may not.

## 3.1 `schemaVersion`

- mandatory;
- positive;
- version of PlayerCommand wire/semantic contract, not DB schema;
- unsupported version must deterministically reject;
- belongs to semantic identity because the same bytes interpreted under another schema version are not necessarily the same command.

## 3.2 `commandUid`

- stable logical command identity;
- mandatory/nonblank;
- not derived from display label, row ID, wall-clock timestamp, random UI row, or mutable active-campaign state;
- distinct from event UID, transaction UID, domain-record UID, turn UID, causation UID and correlation UID.

## 3.3 `campaignUid`

- mandatory/nonblank;
- immutable semantic identity field;
- command may never silently rebind to currently active campaign later;
- same command UID in another campaign is a semantic conflict when compared as the same stable command identity.

## 3.4 `actorRef`

Must be a generic typed reference, e.g. semantic equivalent of:

```text
actorKindUid + actorUid
```

Requirements:

- nonblank shape;
- no hardcoding to Player only;
- no parallel duplicate identity registry created by Phase 16;
- existence/authorization is not Phase-16 structural truth, but the reference must remain typed and campaign-bound by the envelope;
- changing actor under the same command UID changes semantic identity.

## 3.5 `commandKindUid`

- stable typed discriminator;
- maps one-to-one to an expected payload type/schema;
- unknown kind rejects;
- payload-kind mismatch rejects;
- no raw-map fallback.

## 3.6 `payload`

- strongly typed immutable intent;
- no `Map<String, Any?>`, JSON patch, SQL fragment, table/column/value mutation primitive, or generic StatePatch wrapper;
- must contain requested inputs/refs only, never caller-declared canonical effects.

## 3.7 `provenance`

- required typed provenance;
- provenance is origin/context evidence, not proof of truth or authorization;
- semantic provenance fields belong to semantic identity;
- diagnostic-only transport metadata may remain outside identity if clearly separated.

## 3.8 `causationUid` / `correlationUid`

- immutable when present;
- semantically meaningful identity fields because changing causal/correlation context changes the request record;
- do not imply commit/order/atomicity by themselves.

## 3.9 `requestedEffectiveOrder`

- optional immutable requested temporal intent;
- advisory only;
- actual committed effective order belongs to later execution/transaction layers;
- if present, it belongs to semantic identity;
- wall-clock creation timestamp must not substitute for it.

## 3.10 Preconditions

Only typed optimistic expectations are allowed, e.g.:

```text
ExpectedRecordVersion(DomainRef, version)
ExpectedLifecycleState(DomainRef, stateUid)
```

Forbidden:

```text
ExpectedColumnValue(table, column, value)
raw SQL predicate
arbitrary key/value condition
```

Preconditions belong to semantic identity and are not commit-time authority.

## 3.11 Typed extensions

- namespaced typed extension only;
- explicit extension kind + schema version;
- unknown/unsupported extension version rejects unless architecture defines a typed negotiated compatibility rule;
- arbitrary maps/raw JSON mutation payloads are forbidden;
- extensions belong to semantic identity.

---

# 4. Semantic identity / deterministic equality oracle

Canonical rule for a stable `commandUid`:

```text
same commandUid + exact immutable semantic content
=> SAME_LOGICAL_COMMAND
```

```text
same commandUid + any conflicting immutable semantic content
=> deterministic COMMAND_IDENTITY_CONFLICT
```

The following fields MUST participate in semantic identity:

- `schemaVersion`;
- `campaignUid`;
- actor kind + actor UID;
- `commandKindUid`;
- full typed payload;
- semantic provenance;
- `causationUid`;
- `correlationUid`;
- `requestedEffectiveOrder`;
- complete ordered/canonicalized typed preconditions;
- complete ordered/canonicalized typed extensions.

`commandUid` is the key being compared, not merely another payload field.

## 4.1 Fields that MUST NOT accidentally perturb retry equality

Do not include as semantic identity unless the canonical contract explicitly elevates them:

- wall-clock serialization time;
- deserialization time;
- object identity/hashCode memory address;
- display labels;
- database row IDs;
- UI list positions;
- transient transport headers;
- active campaign selected at retry time.

A retry after process restart must be semantically equal to the original if all immutable semantic content is the same.

## 4.2 Fingerprint/canonical serialization

If runtime exposes a fingerprint/hash:

- it must be deterministic for semantic content;
- same semantic command across serialize/deserialize cycles must yield same fingerprint;
- JSON/object property iteration order must not alter fingerprint;
- typed collection canonicalization rules must be explicit;
- fingerprint collision must never be treated as sufficient proof of semantic equality without equality check unless a cryptographic canonical identity contract explicitly says so.

---

# 5. Intent vs result — forbidden payload truth

A command may carry requested inputs, desired action, references and effort/amount intent. It may NOT declare canonical result facts.

Forbidden command payload authority includes:

- final stat/base/effective stat;
- final resource balance/max/current result;
- final skill mastery/XP result;
- final technique mastery/learned truth;
- final financial balance;
- caller-created `FinancialTransaction` result;
- final Inventory possession fact;
- final Equipment state/effects/modifiers;
- final OwnershipRecord / legal ownership share truth;
- final AssetRecord/Liability/Obligation committed state;
- final DevelopmentProject progress delta as resolved truth;
- canonical work result such as BREAKTHROUGH/SUCCESS when it is supposed to be mechanically resolved;
- `requirementSatisfied=true` as authority;
- `milestoneAchieved=true` as authority;
- durable project outcome already-valid truth;
- final project COMPLETED fact;
- committed event/transaction result.

Requested amount/share/effort/desired status may be legal intent when clearly named/requested and validated later. Result authority belongs to later domains and phases.

---

# 6. StatePatch / raw mutation boundary

Absolute release gate:

```text
PlayerCommand cannot be an arbitrary StatePatch transport.
```

Forbidden designs include:

```text
Command(kind="STATE_PATCH", table, op, key, values)
Command(kind="SQL", sql)
Command(kind="GENERIC", payload: Map<String, Any?>)
UnknownCommand(rawJson/rawMap)
```

Unknown command kinds must fail closed. There must be no fallback that serializes unknown data and later lets execution code interpret it as a mutation.

Phase 16 must not expand `StatePatchEngine` authority or create a second generic mutation path.

---

# 7. Persistence boundary

Phase 16 PlayerCommand is a **transient canonical request contract**.

Therefore the oracle expects no new authoritative:

- `player_commands` table;
- command inbox/outbox;
- command queue;
- command execution status table;
- persisted retry ledger;
- replay ledger;
- synthetic legacy command history;
- migration that fabricates command facts from old StatePatch/chat/UI actions.

Absence of command persistence is NOT a defect in Phase 16.

If final runtime adds persisted command execution state, this is a scope violation unless fresh MASTER/roadmap explicitly changed before validation.

Deterministic serialization/deserialization for transport/testing is allowed and recommended.

---

# 8. DevelopmentProject integration oracle

Phase 15 remains canonical project authority. Phase 16 project commands carry intent and refs only.

## START_PROJECT

Allowed intent examples:

- project type UID;
- title/objective intent;
- beneficiary ref;
- target domain/ref;
- intended output kind request;
- requested cap/constraints.

Forbidden:

- caller-declared persisted project row;
- caller-selected canonical status history UID set as already committed;
- canonical requirement/milestone facts created inside command.

## RECORD_PROJECT_WORK

Allowed:

- project UID;
- work kind;
- effort intent;
- method/evidence refs;
- requested resource use.

Forbidden canonical result:

- `progressDeltaUnits = resolved amount` when resolution belongs to mechanics;
- `result = BREAKTHROUGH/SUCCESS` as authoritative truth;
- milestone achieved flag;
- direct project work record object intended for blind insert.

## SATISFY_PROJECT_REQUIREMENT

Allowed:

- project UID;
- requirement UID;
- evidence refs/attestation request.

Forbidden:

- canonical satisfaction row declared true by caller without project authority resolution.

## ACHIEVE_PROJECT_MILESTONE

Allowed:

- project UID;
- milestone UID;
- source-work/evidence ref.

Forbidden:

- treating source work presence in command as proof of legal achievement;
- bypassing Phase-15 same-project/campaign/chronology guards.

## CHANGE/COMPLETE/CANCEL PROJECT

Desired lifecycle state is intent only. Phase-15 lifecycle/outcome authority determines legality. `CompleteProjectCommand` cannot fabricate Truth/Technique/Skill/Item/Asset outcomes.

---

# 9. Structural validation responsibility

Phase 16 validator must deterministically reject malformed requests before later layers.

It SHOULD validate:

- supported command schema version;
- nonblank command/campaign/actor IDs;
- known command kind;
- kind/payload type correspondence;
- payload required fields and sign/range invariants belonging to request shape;
- typed ref shape;
- typed precondition shape;
- typed extension schema/version;
- no raw mutation primitive.

It MUST NOT claim to prove:

- target existence;
- ownership/authorization;
- account funds;
- lifecycle legality;
- world-rule legality;
- actual resulting values;
- commit success.

Those belong to later resolver/orchestration/domain authority.

---

# 10. Deterministic serialization oracle

If a codec exists, require:

```text
command -> canonical serialized form -> command'
```

with:

```text
semanticEqual(command, command') == true
fingerprint(command) == fingerprint(command')
```

for every supported command family, including nullable optionals, preconditions and extensions.

Required negative behavior:

- unknown kind rejects;
- unsupported schema version rejects;
- kind/payload mismatch rejects;
- malformed typed ref rejects;
- extension kind/version mismatch rejects;
- no raw fallback object survives as a command.

Serialization must not introduce timestamp-based inequality.

---

# 11. Required semantic test matrix

The final Phase-16 runtime must be independently checked against at least the following matrix.

## CMD-SEM-01 — exact stable-UID replay

Construct command C1. Reconstruct C2 with same `commandUid` and exact immutable semantic fields.

Required:

```text
SAME_LOGICAL_COMMAND
same canonical fingerprint/serialization semantics
```

No mutation occurs.

## CMD-SEM-02 — same UID / different campaign

Same command UID, identical everything except campaign.

Required: deterministic `COMMAND_IDENTITY_CONFLICT`; no silent active-campaign rebinding.

## CMD-SEM-03 — same UID / different actor

Change actor kind or actor UID.

Required: deterministic conflict.

## CMD-SEM-04 — same UID / different semantic provenance

Change semantic provenance source kind/source UID/detail if detail is defined as semantic.

Required: deterministic conflict.

Diagnostic transport timestamp, if outside semantic provenance, must not cause conflict.

## CMD-SEM-05 — same UID / different typed payload

Change any immutable payload field, requested amount/effort/target/ref.

Required: deterministic conflict.

## CMD-SEM-06 — payload-kind mismatch

Use payload type A with command kind B.

Required: structural rejection; never reinterpretation/coercion to raw map.

## CMD-SEM-07 — unknown command kind

Unknown kind with otherwise valid envelope.

Required: fail closed. No raw-map/unknown fallback.

## CMD-SEM-08 — unsupported schema version

Use version 0, negative, or unsupported future version.

Required: deterministic structural rejection.

## CMD-SEM-09 — malformed typed reference

Blank actor kind/UID or malformed `DomainRef` required by payload.

Required: structural rejection.

This test does not require Phase 16 to prove target existence.

## CMD-SEM-10 — raw mutation attempt

Attempt to represent table/op/key/value StatePatch, SQL, or arbitrary mutation map as a command.

Required: impossible by public typed model or deterministically rejected by parser/registry.

## CMD-SEM-11 — deterministic serialization round-trip

For each supported command family:

```text
encode -> decode -> encode
```

Required:

- exact semantic equality;
- deterministic canonical fingerprint;
- no wall-clock field injected into identity;
- optionals/preconditions/extensions preserved.

## CMD-SEM-12 — no mutation by construction

Create/validate/serialize/fingerprint every supported command against a fixture DB containing accepted Phase 3–15 state.

Required:

- no authoritative row count/value changes;
- no FinancialTransaction/Event/Ownership/Project facts created;
- no StatePatch apply;
- no migration/persistence side effect attributable to command creation/validation.

---

# 12. Additional mandatory edge tests

## CMD-SEM-13 — requested effective order identity

Same UID, one command has `requestedEffectiveOrder=null`, another has concrete order, or values differ.

Required: conflict.

A changed wall-clock issue time outside identity must not cause conflict.

## CMD-SEM-14 — precondition identity

Same UID with changed expected version/lifecycle target/state.

Required: conflict.

Precondition validation must remain structural only.

## CMD-SEM-15 — extension identity/version

Same UID with changed typed extension payload/version.

Required: conflict. Unsupported extension version rejects.

## CMD-SEM-16 — causation/correlation identity

Same UID with changed causation or correlation UID.

Required: conflict if these fields are part of canonical envelope, as WORK-066 requires.

## CMD-SEM-17 — DevelopmentProject forbidden truth

Construct a project-work intent attempting to inject resolved progress/breakthrough/milestone/outcome authority.

Required: payload model does not expose such canonical result fields, or validator rejects them.

## CMD-SEM-18 — command UID distinct from result IDs

Verify API does not require/assume:

```text
commandUid == projectUid
commandUid == transactionUid
commandUid == eventUid
```

Required: identities remain distinct.

---

# 13. Semantic fingerprint canonicalization rules

If the implementation provides `semanticFingerprint`, final audit must inspect the exact algorithm.

Required properties:

1. includes every semantic identity field;
2. excludes non-semantic wall-clock/transport noise;
3. uses explicit type tags so structurally similar different variants cannot collide by concatenation ambiguity;
4. handles null distinctly from empty string/list;
5. canonicalizes ordered vs unordered collections according to contract;
6. is stable across process restart;
7. uses stable UTF-8/canonical encoding rather than `toString()` of objects/maps;
8. equality check remains authoritative for conflict determination if fingerprint is only an optimization.

Display labels must never define identity.

---

# 14. Generic / universe-agnostic requirement

Core PlayerCommand kinds and refs must remain world-neutral.

Naruto/Bleach-specific mechanics must not be hardcoded into Core command execution because execution itself is beyond Phase 16. World-specific command extensions are acceptable only through typed registered schemas, not arbitrary free-form fallback.

Actor/ref design must support player, character/NPC, organization or future legal actors without redesigning command envelope identity.

---

# 15. Scope-violation gates

Final Phase-16 revalidation must FAIL for Phase-16 scope violation if the candidate introduces, without a newer explicit MASTER decision:

- `PlayerChangeSet` execution authority;
- `PlayerDomainEngine` orchestration;
- WorldRuleProvider resolution;
- canonical progression calculation/ledger;
- TurnTransaction implementation;
- command persistence/execution status/replay ledger;
- event creation as a side effect of command construction;
- generic command-to-StatePatch executor;
- direct SQL mutation callbacks in command registry.

Minimal interfaces/adapters required solely to compile/serialize typed commands are not scope violations if they have no execution authority.

---

# 16. Regression gates against accepted Phase 1–15

Command construction/validation must leave all accepted authorities unchanged, especially:

```text
Stats/Resources
Modifier/Resolver
Talent/Potential
Skills
Techniques
Innate/Evolution
Inventory
Equipment
Ownership
Financial Ledger
Assets/Liabilities
Campaign Truth
DevelopmentProject
SourceOfTruthRegistry
```

Mandatory semantic separation remains:

```text
PlayerCommand
!= Inventory possession
!= Equipment state
!= OwnershipRecord
!= FinancialTransaction
!= Asset/Liability
!= Campaign Truth
!= DevelopmentProject fact
```

Phase-16 tests must not weaken StatePatch typed-only protections installed by earlier phases.

---

# 17. Final revalidation procedure when CHAT-1 supplies runtime SHA

Do not transfer this oracle's readiness into an automatic PASS.

For the exact supplied runtime SHA:

1. recheck fresh master;
2. if later commits are report-only, keep validating the supplied runtime;
3. if a later Phase-16 runtime exists, STOP and request candidate refresh;
4. inspect exact commit/diff and CI head SHA;
5. inspect PlayerCommand model, registry, validator, codec/fingerprint and tests;
6. run/verify CMD-SEM-01..18;
7. inspect for persisted command tables/migrations/inbox/status/replay ledger;
8. inspect for StatePatch/raw-map fallback;
9. inspect Phase-15 project command payloads for result-truth leakage;
10. verify command construction/validation performs no mutation;
11. verify Phase 3–15 regression boundaries;
12. issue exactly one final semantic verdict for that SHA.

Final verdict format later:

```text
PHASE 16 SEMANTIC REVALIDATION: PASS
```

or:

```text
PHASE 16 SEMANTIC REVALIDATION: FAIL
```

A FAIL must include violated invariant, minimal reproducer, exact code/runtime path, expected vs actual, and minimal correction scope.

---

# ORACLE STATUS

```text
PLAYERCOMMAND SEMANTIC ORACLE READY
FINAL PHASE-16 RUNTIME REVALIDATION PENDING
```

No Phase-16 PASS/FAIL is issued by this document.

Phase 17 was not started.
