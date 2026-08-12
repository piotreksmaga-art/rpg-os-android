# WORK-20260810-066 — Phase 16 PlayerCommand Architecture Audit

Status: READ-ONLY NEXT-PHASE ARCHITECTURE AUDIT / CONDITIONAL PREIMPLEMENTATION DESIGN

Work ID: `WORK-20260810-066`
Worker: `CHAT-4`
Role: `READ-ONLY NEXT-PHASE ARCHITECTURE AUDITOR`
Repository: `piotreksmaga-art/rpg-os-android`
Accepted Phase-14 runtime: `8d78398462c7d9f748fc3dc002c01458b7656baf`
Phase-15 runtime candidate observed: `47f85c1689fb78cfd5c7edd9d82f897485357dab`
Exact Phase-15 CI observed: GitHub Actions `#311`, run ID `31572017265`, `SUCCESS`
Fresh master initially observed: `47f85c1689fb78cfd5c7edd9d82f897485357dab`
Fresh master after independent Phase-15 validation reports landed: `0286be6ccb064cf376c88c282ca48a2cf27ec487`
Allowed write scope: this report only.

This document is architecture/audit only. It does not implement Phase 16, does not modify Kotlin runtime, SQLite runtime schema, migrations, production routing, SourceOfTruthRegistry, StatePatch, tests, MASTER, Roadmap, Parallel Work Coordination, or Phase-15 code.

At report time Phase 15 is **NOT ACCEPTED**. Two independent validations of exact runtime `47f85c1689fb78cfd5c7edd9d82f897485357dab` are FAIL:

- semantic revalidation: `PROJECT_OUTPUT_TRUTH` is declared by the Phase-15 model but cannot be committed by the current project outcome SQLite boundary;
- adversarial validation: a milestone achievement may cite a source work record whose `effective_order` is later than the achievement's `achieved_order`.

Therefore this report prepares Phase 16 conditionally, but:

```text
PHASE 16 IMPLEMENTATION REMAINS BLOCKED UNTIL PHASE 15 ACCEPTED.
```

No Phase-15 hotfix is proposed here.

---

# 1. Fresh-master evidence

## 1.1 Roadmap authority

The current canonical Roadmap orders the relevant sequence as:

```text
15. DevelopmentProject model
16. PlayerCommand contract
17. PlayerChangeSet contract
18. PlayerDomainEngine orchestration
19. WorldRuleProvider contract
20. ProgressionEngine + Progression Ledger
```

Therefore the next phase after Phase 15 is exactly:

```text
PHASE 16 — PlayerCommand contract
```

No current Roadmap change contradicting the requested expected phase was found.

## 1.2 MASTER authority

MASTER defines one legal path for committed truth:

```text
PROPOSAL
-> DOMAIN/RULE RESOLUTION
-> CHANGE SET
-> VALIDATION
-> TRANSACTION
-> EVENTS + LEDGERS + AUTHORITATIVE STATE
-> COMMIT
-> COMMITTED REALITY
```

For Player Domain specifically MASTER gives:

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
-> PlayerSnapshotBuilder
```

MASTER also lists example domain commands such as Train, LearnSkill, CreateTechnique, UseTechnique, Purchase, Sell, Equip, GainReward, ApplyInjury, Heal, AdvanceTime, Start/Progress/CompleteProject, TransferAsset.

The architectural conclusion is strict:

```text
PlayerCommand = typed intent/request.
PlayerCommand != legal result.
PlayerCommand != proposed mutation set.
PlayerCommand != committed reality.
```

## 1.3 Actual runtime boundary

At the observed Phase-15 candidate:

- typed authoritative stores already exist for earlier Player domains;
- Phase 15 adds `DevelopmentProjectModel`, `DevelopmentProjectStore`, V15 schema/guards and tests;
- `SourceOfTruthRegistry` blocks canonical Finance, Asset/Liability and DevelopmentProject tables from generic StatePatch authority;
- generic `StatePatchEngine` still exists and accepts table/operation/key/value shaped patch operations for tables allowed by `SourceOfTruthRegistry`;
- no canonical `PlayerCommand` model/store/codec/execution API exists in the inspected runtime;
- Phase-15 work/outcome rows already carry optional `commandUid` evidence fields, demonstrating that command identity is anticipated by existing domains without implementing Phase 16 itself.

This is the correct insertion point for a pure typed intent contract.

## 1.4 Work ID selection

Parallel Work Coordination defines numeric IDs as `WORK-YYYYMMDD-NNN`. Repository history already uses `WORK-20260810-061` through `WORK-20260810-065`; `WORK-20260810-066` was unused at audit start. Recent Phase-15 implementation commits also use ad-hoc `WORK-20260812-P15`, but that does not create a canonical numeric successor. This report therefore uses:

```text
WORK-20260810-066
```

---

# 2. Canonical Phase-16 scope

Phase 16 introduces the **canonical typed command contract** used to express what an actor wants the Player Domain to attempt.

It must answer:

```text
WHO requests WHAT action,
for WHICH campaign,
against WHICH typed targets,
under WHICH optional expected-state assumptions,
with WHICH stable command identity,
and with WHICH provenance/correlation context.
```

It must NOT answer:

```text
whether the action is legal,
what authoritative state changes result,
what World Pack rules say,
what mechanics calculate,
what events/ledger entries are emitted,
whether the request committed,
or what the final narrative says.
```

Canonical hard separation:

```text
PlayerCommand
!= StatePatch
!= PlayerChangeSet
!= domain record
!= FinancialTransaction
!= OwnershipRecord
!= DevelopmentProject record
!= event
!= TurnTransaction
!= committed mutation
!= narrative proposal
!= WorldRuleProvider result
```

PlayerCommand is an **intent object** only.

---

# 3. Explicit non-goals

Phase 16 must not implement:

1. `PlayerChangeSet` data model or mutation semantics — Phase 17.
2. `PlayerDomainEngine` orchestration — Phase 18.
3. World Pack legality/resolution — Phase 19.
4. Progression calculations/ledger — Phase 20.
5. Global invariant validator/no-retrogression — Phase 22.
6. Single Truth Mutation Path enforcement across the app — Phase 26.
7. Turn Transaction atomic commit/rollback — Phase 27.
8. global persisted idempotency/double-commit engine — Phase 28.
9. Event Store command/event causal persistence — Phase 30+.
10. Scheduler semantics for delayed commands — later temporal/scheduler phases.
11. command inbox, workflow queue, retry worker or remote transport protocol unless separately assigned later.
12. conversion of historical StatePatch/chat/narrative/UI actions into synthetic command history.

Phase 16 must remain useful even while all of those are absent.

---

# 4. Command envelope

## 4.1 Minimal canonical envelope

Recommended semantic contract:

```text
PlayerCommand<P : PlayerCommandPayload>
- schemaVersion: Int
- commandUid: String
- campaignUid: String
- actor: CommandActorRef
- commandKindUid: String
- payload: P
- provenance: CommandProvenance
- causationUid: String?
- correlationUid: String?
- requestedEffectiveOrder: Long?
- preconditions: List<CommandPrecondition>
- extensions: List<TypedCommandExtension>
```

The envelope is immutable after construction.

### Required fields

`schemaVersion`
- required;
- positive;
- version of the PlayerCommand serialization/semantic contract, not DB schema version;
- immutable.

`commandUid`
- required stable logical command identity;
- nonblank;
- immutable;
- not a transaction/event/domain-record UID.

`campaignUid`
- required;
- immutable;
- command must never be silently rebound to current UI campaign during execution.

`actor`
- required;
- generic stable actor/party reference;
- immutable;
- identifies the principal requesting/performing the intent, not necessarily ActivePlayer only.

`commandKindUid`
- required stable kind identifier;
- immutable;
- must correspond to the concrete typed payload codec/type.

`payload`
- required typed payload;
- immutable;
- no `Map<String, Any?>`, raw SQL, raw table/column names, mutation lambdas or generic StatePatch operations.

`provenance`
- required minimal source evidence;
- immutable;
- indicates where the command came from, not whether it is true/legal.

### Optional fields

`causationUid`
- optional;
- stable identity of the immediate cause/request that produced this command where known;
- may refer to an event/command/turn/intention according to a typed reference convention established later;
- must never be inferred from display text.

`correlationUid`
- optional;
- groups related commands across one user action/turn/workflow;
- does not imply ordering or atomicity.

`requestedEffectiveOrder`
- optional requested campaign-order intent;
- not authoritative time;
- absence means no caller-declared effective-order preference;
- actual commit/effective order belongs to later orchestration/transaction semantics.

`preconditions`
- optional typed optimistic expectations;
- they are requests for validation, not proof;
- must be revalidated at execution/commit boundary.

`extensions`
- optional typed, namespaced extensions;
- no untyped arbitrary map;
- unknown required extension => structural unsupported-command failure;
- unknown optional extension may be preserved/ignored only if contract explicitly allows it.

## 4.2 IssuedAt / requestedAt decision

A mandatory wall-clock `issuedAt` is **not** recommended as part of logical command identity because:

- wall clock is not campaign time;
- retries should not create a new command merely because serialized later;
- deterministic replay should not depend on client clock skew.

If transport diagnostics need creation time, keep it outside the semantic PlayerCommand envelope or as a typed non-authoritative transport header.

`requestedEffectiveOrder` is the only Phase-16 temporal field proposed in the command itself, and it is explicitly advisory intent.

Authoritative `effectiveOrder`, `committedAt`, `turnUid`, etc. belong later.

## 4.3 Target references

Do **not** duplicate target references both in envelope and payload.

Targets belong in typed payloads. A convenience read-only accessor may expose `payload.targetRefs()` for routing/inspection, but the serialized authority remains one source.

This avoids:

```text
payload.target = X
but envelope.targets = Y
```

which would create ambiguous identity.

## 4.4 Metadata/extensions

Do not add `Map<String, Any?> metadata` to the canonical contract.

If extension support is required, use a typed extension registry:

```text
interface TypedCommandExtension {
    val extensionKindUid: String
    val extensionSchemaVersion: Int
}
```

and codec registration by stable UID. Phase-16 MVP may ship with `extensions = emptyList()` and no extension types.

---

# 5. Typed command taxonomy

## 5.1 Core type boundary

Recommended Kotlin shape for future implementation:

```text
interface PlayerCommandPayload

interface PlayerCommandKind<P : PlayerCommandPayload> {
    val kindUid: String
    val payloadClass / codec
    fun structuralValidate(payload: P): StructuralValidation
}
```

Concrete payloads are normal typed data classes / sealed-family members.

A registry maps stable `commandKindUid` to a typed codec/validator.

Important:

```text
registry lookup gives a typed parser/validator,
not a callback allowed to mutate the database.
```

No command kind may contain executable mutation code.

## 5.2 Extensibility model

Core command kinds should model universe-agnostic actions. World Packs supply stable definition UIDs referenced by those commands rather than creating Naruto/Bleach-specific raw payloads.

Examples:

```text
UseTechniqueCommand(techniqueUid, targetRefs, requestedModeUid?)
PracticeSkillCommand(skillUid, effortIntent, methodUid?)
EquipItemCommand(itemInstanceUid, slotUid)
TransferFundsCommand(fromAccountUid, toAccountUid, amountMinor)
TransferOwnershipCommand(assetRef, toOwnerRef, requestedShare)
StartDevelopmentProjectCommand(projectTypeUid, objective, targetRef?, intendedOutputKindUid?)
```

World-specific semantics remain in definitions/rules later.

A future World Pack may register a new command kind only through a typed codec contract; it cannot supply raw SQL or arbitrary map payloads.

## 5.3 Suggested command families

The taxonomy below is architecture, not a requirement to implement all Phase-16 payload classes immediately.

### Player State / Stats / Resources

Intent-oriented examples:

- `TrainCommand` — domain/method/effort intent, not caller-declared stat gain.
- `UseResourceActionCommand` — requested action/amount where amount is an attempted spend, not final balance.
- `RecoverCommand` — requested recovery action/method, not caller-declared HP/resource result.

Forbidden payload fields:

- `newBaseStat`;
- `newEffectiveStat`;
- `canonicalCurrentResource`;
- `derivedMaxResource`.

### Skills

Examples:

- `LearnSkillCommand(skillUid, sourceRef?)`;
- `PracticeSkillCommand(skillUid, effortIntent, methodUid?)`;
- `UseSkillCommand(skillUid, targetRefs?)`.

Do not carry resulting mastery/xp/progress delta as caller truth.

### Techniques

Examples:

- `LearnTechniqueCommand(techniqueUid, sourceRef?)`;
- `UseTechniqueCommand(techniqueUid, targets, requestedVariantUid?)`;
- `BeginTechniqueDevelopmentCommand(...)` should generally route to DevelopmentProject intent instead of creating a parallel technique-creation process.

Do not carry resulting technique mastery or final created technique object.

### Inventory

Examples:

- `AcquireItemCommand(definitionUid, requestedQuantity, sourceRef?)` only when acquisition intent is meaningful and later authority verifies source;
- `TransferItemCommand(itemRef, fromHolderRef, toHolderRef)`;
- `ConsumeItemCommand(itemRef, requestedQuantity)`;
- `Drop/MoveItemCommand(...)` where location semantics are explicit.

Inventory possession is not ownership.

### Equipment

Examples:

- `EquipItemCommand(itemInstanceUid, slotUid)`;
- `UnequipSlotCommand(slotUid)`.

Payload may request desired slot state but cannot assert resulting modifiers/effective stats.

### Ownership

Examples:

- `TransferOwnershipCommand(assetRef, fromOwnerRef?, toOwnerRef, requestedShare)`;
- `AcquireOwnershipCommand(assetRef, ownerRef, requestedShare, basisRef?)` only as intent.

It cannot declare an OwnershipRecord as already valid.

### Finance

Examples:

- `TransferFundsCommand(fromAccountUid, toAccountUid, amountMinor)`;
- `ExternalPaymentCommand(accountUid, direction, amountMinor, counterpartyRef?, reasonUid)`.

Allowed: exact requested transfer amount.

Forbidden:

- caller-declared post-balance;
- caller-declared ledger row status;
- caller-selected arbitrary transaction UID as equivalent to command UID.

### Assets / liabilities

Examples:

- `AcquireAssetCommand(assetRef, acquisitionTermsRef?)`;
- `DisposeAssetCommand(assetRef, requestedTerms?)`;
- `EnterObligationCommand(obligationTypeUid, counterparties, requestedTerms)`;
- `SettleObligationCommand(obligationUid, requestedSettlementAmount?)`.

The command expresses contractual/economic intent; Phase-14 authority and Finance authority decide resulting records.

### DevelopmentProject

Dedicated integration is in section 12.

---

# 6. Stable UID and idempotency semantics

## 6.1 Identity namespaces

Keep these identities distinct:

```text
commandUid
!= domain record UID
!= transactionUid
!= financialTransactionUid
!= eventUid
!= turnUid
!= causationUid
!= correlationUid
```

They may be linked, never automatically aliased.

## 6.2 Logical command equality

Define a canonical immutable semantic representation of a command.

For a given `(campaignUid, commandUid)`:

```text
same commandUid + same canonical immutable command
=> same logical command identity
```

and:

```text
same commandUid + conflicting immutable semantic content
=> deterministic COMMAND_IDENTITY_CONFLICT
```

This comparison must cover at least:

- schema version compatibility;
- campaign UID;
- actor ref;
- command kind UID;
- full typed payload;
- causation/correlation if they are defined as semantic identity fields;
- requested effective order if present;
- preconditions;
- provenance fields designated immutable identity evidence;
- typed extensions.

## 6.3 Idempotency decision for Phase 16

Phase 16 defines the **identity semantics** but does not implement persisted replay detection.

Later Phase 18/28 may persist:

- canonical serialized command;
- immutable fingerprint/hash;
- execution status;
- resulting ChangeSet/transaction/event references.

Phase 16 must use deterministic canonical serialization/equality so that later persistence can safely distinguish exact retry from conflicting reuse.

## 6.4 Command UID generation

The contract should permit externally generated stable UUID/ULID-like IDs or equivalent project UID mechanism, but should not tie meaning to lexical structure.

Do not derive command UID from:

- display text;
- command kind + timestamp only;
- target label;
- database row ID.

---

# 7. Actor, campaign and reference semantics

## 7.1 Actor

Use a generic stable actor reference equivalent to:

```text
CommandActorRef
- actorKindUid
- actorUid
```

It should consume the existing campaign-scoped party/entity reference system where semantically appropriate, rather than introduce a second identity registry.

The existing Phase-12 ownership party registry is a useful identity foundation, but Phase 16 should avoid naming the generic actor type `OwnershipOwnerRef` in the public command API if that leaks ownership-domain semantics into all commands. A lightweight typed adapter/reference value may wrap the same underlying kind+UID identity and resolver contract.

Phase 16 structural validation checks only:

- nonblank kind UID;
- nonblank actor UID;
- structurally valid reference shape.

Actual existence/active campaign/authorization belongs later.

## 7.2 Campaign

`campaignUid` is mandatory and immutable.

A command created for campaign A must never execute against campaign B because B happens to be active in UI at execution time.

Cross-campaign target resolution must fail during later reference validation.

## 7.3 Generic references

Use a typed reference value:

```text
DomainRef
- kindUid
- uid
```

but do not treat any arbitrary pair of strings as resolved truth.

Phase-12 lesson remains mandatory:

```text
nonblank UID != valid reference
```

Phase 16 may structurally carry a DomainRef; later reference validation resolves it through accepted registries/stores.

## 7.4 Authorization

`actor` does not prove the caller is allowed to act as that actor.

Authorization/actor control is a later validation layer.

A client must not gain authority by constructing:

```text
actor = KING_UID
```

or another NPC/system entity.

---

# 8. Structural validation

Phase 16 owns only **structural contract validation**.

Mandatory structural checks:

1. supported `schemaVersion`;
2. nonblank `commandUid`;
3. nonblank `campaignUid`;
4. structurally valid actor ref;
5. known `commandKindUid` registered to exactly one typed payload codec;
6. payload runtime/serialized type matches command kind;
7. required payload fields present;
8. numeric input bounds safe from overflow/invalid sign according to intent semantics;
9. typed refs structurally valid;
10. optional preconditions structurally valid;
11. extension kinds/version structurally supported;
12. no forbidden raw mutation primitives.

Structural validation must not read authoritative current state except where a serializer/codec registry itself is required.

It must not claim:

- resource sufficient;
- item owned;
- slot free;
- account funded;
- ownership transferable;
- project ready;
- technique legal;
- World Pack allows action.

---

# 9. Validation layer separation

Canonical future pipeline:

## Layer 1 — structural validation

**Phase 16 owns this.**

Checks envelope/payload shape/type/version/basic bounds only.

## Layer 2 — reference validation

Later orchestration/domain integration.

Checks campaign-scoped existence/lifecycle of actor/targets/accounts/assets/projects/etc.

## Layer 3 — authorization / actor validation

Later orchestration/security policy.

Checks whether source is permitted to issue this action as this actor.

## Layer 4 — domain invariant validation

Existing authoritative domain stores + future PlayerDomainEngine/InvariantValidator.

Examples: slot rules, balance/funds, ownership shares, project lifecycle, inventory quantities.

## Layer 5 — WorldRuleProvider validation

Phase 19.

World-specific legality/canon/mechanics.

## Layer 6 — orchestration

Phase 18.

Chooses required domain operations/resolvers/mechanics.

## Layer 7 — PlayerChangeSet generation

Phase 17 contract / Phase 18 production.

Produces proposed mutations/events/ledger changes.

## Layer 8 — commit

Later TurnTransaction / authoritative SQLite/domain boundaries.

Only commit makes reality.

Key invariant:

```text
command validation/precondition != authoritative commit-time validation
```

---

# 10. Preconditions / expected state

Phase 16 should support a small typed optimistic-precondition family without implementing execution.

Recommended types:

```text
sealed interface CommandPrecondition

ExpectedRecordVersion(
    target: DomainRef,
    expectedVersion: Long
)

ExpectedLifecycleState(
    target: DomainRef,
    expectedStateUid: String
)

ExpectedCampaignRevision(
    expectedRevision: Long
) // only if a stable campaign revision contract actually exists later
```

Do not support generic:

```text
ExpectedColumnValue(table, column, value)
```

because that recreates StatePatch/schema coupling.

Precondition semantics:

- immutable request assumption;
- structurally validated in Phase 16;
- resolved later against authoritative domain;
- stale => deterministic precondition failure;
- success at command creation does not guarantee success at commit.

Avoid introducing preconditions whose target domain has no stable version/state contract. Such commands simply omit them until that domain can support them.

---

# 11. Persistence decision

## Decision: Phase-16 PlayerCommand is a transient canonical request contract.

No new SQLite command table is required for Phase 16.

Reasoning:

1. MASTER requires stable `commandUid` for eventual idempotency, but does not require a Phase-16 persisted inbox/log.
2. Roadmap explicitly places idempotency/double-commit protection later at Phase 28.
3. Event Store / causal history is later Phase 30+.
4. Phase 17/18 are still missing, so persisting commands now would create a log with no canonical execution/change-set/commit state machine.
5. Stable UID is useful before persistence: network retry, deterministic identity, correlation and later linkage already require it.

Therefore:

```text
Phase 16 persistence = NONE for canonical command history.
```

Allowed in Phase 16:

- deterministic serialization/deserialization for API/process boundaries and tests;
- in-memory command construction;
- canonical equality/fingerprint calculation if useful.

Not allowed in Phase 16:

- `player_commands` authoritative table;
- inbox/outbox;
- execution status table;
- retry worker;
- synthetic backfill of old commands.

Future integration point:

Phase 18/27/28 can persist command execution identity/status once the commit path exists. That persistence must store the exact immutable semantic command or a collision-safe canonical representation/fingerprint plus sufficient content for conflict diagnosis/replay.

---

# 12. StatePatch boundary

Current generic StatePatch has table-oriented mutation semantics:

```text
PatchOperation
- table
- op insert/update/delete
- key
- values
```

It executes direct SQLite table mutations when SourceOfTruthRegistry allows them.

PlayerCommand must **not** wrap this model.

Forbidden architecture:

```text
PlayerCommand(
    kind = "STATE_PATCH",
    payload = StatePatch(...)
)
```

or:

```text
PlayerCommand
-> arbitrary table/column patch
-> database
```

Required long-term direction:

```text
typed PlayerCommand
-> future PlayerDomainEngine
-> authoritative typed domain operations
-> future PlayerChangeSet / transaction path
```

Phase 16 itself does not remove StatePatch or implement Phase 26 Single Truth Mutation Path.

Migration/deprecation rule:

- existing StatePatch remains legacy/generic runtime until later coordinated phase;
- no new PlayerCommand kind may expose raw StatePatch authority;
- Phase 16 command consumers must not call StatePatch as their generic executor;
- future migration should reduce direct Player-domain StatePatch reach as typed command/domain path becomes complete.

---

# 13. Security / integrity prohibitions

No PlayerCommand or payload may contain caller-controlled:

- raw table name;
- raw column name;
- arbitrary SQL;
- SQL WHERE clause;
- generic StatePatch operation;
- arbitrary `Map<String, Any?>` mutation payload;
- mutation lambda/callback/function reference;
- caller-declared canonical balance;
- caller-declared derived stat/effective value;
- caller-declared OwnershipRecord truth;
- caller-declared authoritative project progress delta/result as already resolved;
- caller-declared DevelopmentProject completion outcome as fact;
- caller-declared event as committed;
- caller-declared ledger entry as committed.

Commands may request quantities/terms that are semantically part of intent, e.g. transfer 100 currency minor units, but resolution decides whether they become effects.

---

# 14. Integration with Phase 3–15

## Phase 3 — Player State

PlayerCommand carries stable actor/campaign identity. It must not become a second Persistent/Derived/Runtime state store.

## Phase 4 — Stats / Resources

Commands may request actions affecting stats/resources. They may not specify final authoritative/effective values. Existing typed stat/resource authority remains source of truth.

## Phase 5 — Modifiers / resolver

Commands never carry caller-computed DerivedValueResolver output as truth. Resolver remains downstream mechanics/derived computation.

## Phase 6 — Talent / Potential

Commands may reference training/development intents; talent/potential are resolved inputs later, not duplicated in payload snapshots unless explicitly needed as non-authoritative expectations.

## Phase 7 — Skills

Skill UID is referenced. SkillStore remains authority for mastery/progress/history.

## Phase 8 — Techniques

Technique UID is referenced. TechniqueStore remains authority. Technique creation/modification should generally use DevelopmentProject workflow rather than command-time creation of a final technique object.

## Phase 9 — Innate / racial / evolution

Commands may request activation/evolution/training intent, but cannot declare resulting form/state legal. WorldRuleProvider/domain authority later decides.

## Phase 10 — Inventory

Commands reference item definitions/instances and quantities. Inventory authority decides possession/quantity result.

## Phase 11 — Equipment

Commands request equip/unequip. EquipmentStore/SQLite guards protect slot and possession invariants at commit-time boundary.

## Phase 12 — Ownership

Commands may request transfer/acquisition terms. OwnershipStore and generic party/asset registries remain authority.

## Phase 13 — Financial Ledger

Commands may request transfers/payments by stable account refs and exact amounts. FinancialStore/SQLite ledger guards remain authority for funds, account state, balance projection, replay and conservation.

`commandUid` may later be passed into FinancialTransaction as causation/idempotency linkage where current Finance API supports it, but FinancialTransaction UID remains distinct.

## Phase 14 — Assets / Liabilities

Commands reference stable assets/obligations and requested actions. Asset/Liability stores remain authority for lifecycle, valuation/settlement and reference integrity.

## Phase 15 — DevelopmentProject candidate

Commands reference project/project content identities and requested actions only. DevelopmentProjectStore remains authority for lifecycle/history/progress/outcomes.

No PlayerCommand becomes a second resolver/source of truth for any Phase 3–15 domain.

---

# 15. DevelopmentProject integration

Phase 15 already exposes stable UIDs for:

- project;
- status event;
- requirement;
- satisfaction;
- milestone;
- achievement;
- work record;
- dependency;
- outcome.

PlayerCommand must **not** construct those authoritative records as if they are already valid.

Recommended future intent payloads:

## 15.1 Start project

```text
StartProjectCommand
- projectTypeUid
- title/objective intent
- beneficiaryRef?
- targetDomainUid
- targetRef?
- intendedOutputKindUid?
- requestedProgressCapUnits?
```

It does NOT include final `projectUid` unless later orchestration supports caller-reserved stable output IDs under an explicit deterministic allocation contract. Preferred Phase-16 minimal design lets engine/domain allocate project record UIDs while command UID remains request identity.

## 15.2 Record/progress project work

```text
RecordProjectWorkCommand
- projectUid
- workKindUid
- effortIntent?
- methodUid?
- evidenceRefs?
- requestedResourceUse?
```

Forbidden caller truth fields:

```text
result = BREAKTHROUGH
progressDeltaUnits = 50
milestoneAchieved = true
```

Those are domain/mechanics outcomes.

## 15.3 Satisfy requirement

```text
SatisfyProjectRequirementCommand
- projectUid
- requirementUid
- evidenceRefs
```

It requests evaluation/attestation. It does not set `satisfied=true` as authority.

## 15.4 Achieve milestone

```text
AchieveProjectMilestoneCommand
- projectUid
- milestoneUid
- evidenceRefs / sourceWorkRef?
```

It does not create the achievement fact itself. Current Phase-15 temporal blocker reinforces why the domain write boundary must validate source-work chronology independently.

## 15.5 Lifecycle transition

```text
ChangeProjectLifecycleCommand
- projectUid
- requestedStatus
- successorProjectUid? only for semantics that require it
```

Requested status is intent. DevelopmentProject lifecycle guards decide legality.

## 15.6 Complete project

```text
CompleteProjectCommand
- projectUid
- completionEvidenceRefs?
```

Do not allow caller to assert:

- canonical output exists;
- project outcome is valid;
- final Technique/Skill/Item/Asset/Truth UID is accepted merely because payload says so.

The future engine resolves/creates the final authoritative domain result and links it through DevelopmentProject authority.

## 15.7 Cancel project

```text
CancelProjectCommand
- projectUid
- reasonUid / reasonText?
```

Domain lifecycle decides terminal transition legality/order.

---

# 16. Concurrency / TOCTOU considerations

Because Phase 16 is transient and non-mutating, it should not invent SQLite concurrency tests.

However its semantics must enable later race-safe execution.

Core rule:

```text
structural validation at command creation
!= reference/domain validation at execution
!= authoritative write-boundary validation at commit
```

Required future race matrix:

## CMD-RACE-01 — same command UID + exact payload

Scenario: two workers receive identical command concurrently.

Phase-16 expectation:
- both parse to identical canonical command identity.

Future Phase-18/28 expectation:
- one logical execution/commit;
- retry returns same logical result / ALREADY_COMMITTED;
- no duplicate domain effects.

## CMD-RACE-02 — same command UID + conflicting payload

Scenario: two requests reuse one command UID with different amount/target/action.

Phase-16 expectation:
- canonical equality/fingerprint detects semantic conflict once both commands are compared.

Future expectation:
- deterministic identity conflict;
- never treat second payload as retry of first.

## CMD-RACE-03 — stale expected state/version

Scenario: command says target version 5; target reaches version 6 before execution.

Future expectation:
- stale precondition rejection or explicit re-resolution path;
- no use of old precheck as authority.

## CMD-RACE-04 — target changes between creation and execution

Examples:
- item transferred away;
- account closed;
- project enters terminal state;
- ownership share changes;
- asset retired.

Future expectation:
- reference/domain validation re-run at execution;
- SQLite/domain write boundary still protects final invariant.

## CMD-RACE-05 — retry after ambiguous prior result

Scenario: client times out after sending command; commit may or may not have happened.

Phase-16 requirement:
- stable command UID survives retry unchanged.

Future Phase-28 requirement:
- lookup persisted execution by command UID before repeating effects.

## CMD-RACE-06 — same target, different command UIDs

Two legal-looking commands compete for a limited invariant, e.g. spending same funds or equipping same item.

Future expectation:
- both commands may structurally validate;
- authoritative domain/SQLite boundary decides winner/coherent result;
- PlayerCommand layer does not promise both succeed.

## CMD-RACE-07 — authorization changes before execution

Actor permission revoked after command creation.

Future expectation:
- authorization revalidated before mutation.

## CMD-RACE-08 — WorldRule definition changes

Command created against one World Pack rule version and executed after content update.

Future architecture must define whether execution uses current rule version or pinned rule version. Phase 16 may carry optional rule/context version only after Phase 19 establishes a canonical contract; do not guess now.

---

# 17. Legacy / migration policy

Phase 16 needs **no SQLite migration** under the chosen transient-contract decision.

Do not synthesize command history from:

- historical StatePatch rows;
- `transactionId` strings in old AI responses;
- chat choices;
- narrative text;
- UI button actions;
- chapter manifests;
- legacy project work rows;
- financial transactions;
- events.

Reason:

A historical mutation/effect does not prove the exact prior typed intent envelope, actor authorization, payload, correlation, causation, preconditions or command schema version.

Rule:

```text
no unambiguous evidence
=> no synthetic PlayerCommand history
```

When future persisted command history is introduced, it begins from the new canonical command boundary unless an explicit one-to-one historical evidence mapping is proven.

---

# 18. Serialization / schema version contract

Although persistence is out of scope, deterministic serialization is recommended because commands cross process/API/test boundaries and later idempotency needs canonical equality.

Requirements:

1. explicit envelope `schemaVersion`;
2. explicit `commandKindUid` discriminator;
3. payload schema tied to command kind + version;
4. no polymorphic class-name serialization as authority;
5. unknown command kind fails structurally;
6. unsupported newer required version fails loudly;
7. round-trip preserves exact semantic command equality;
8. canonical serialization order/fingerprint deterministic if hashing is used later;
9. numeric values preserve exact integer semantics;
10. optional absent vs explicit null semantics defined consistently.

Recommended transport representation may be JSON, but the semantic contract is typed Kotlin/domain data, not arbitrary JSON maps.

---

# 19. Phase-17 handoff — PlayerChangeSet

Phase 16 must hand Phase 17 a clean separation.

## PlayerCommand supplies

- stable command UID;
- campaign UID;
- actor reference;
- command kind;
- typed intent payload;
- provenance/source;
- causation/correlation context;
- optional requested effective order;
- typed preconditions;
- schema version.

## PlayerCommand explicitly does NOT supply

- authoritative old/new values;
- resolved stat/resource deltas;
- final mastery gains;
- created Technique object;
- final Inventory/Equipment/Ownership rows;
- ledger transactions;
- final balance;
- asset/liability authoritative records;
- DevelopmentProject outcome facts;
- events as committed truth;
- warnings produced by resolution;
- domain validation proof;
- WorldRuleProvider result;
- transaction/commit status.

## Phase-17 PlayerChangeSet will need to represent later

Without implementing it here, the next phase must be able to carry proposed, still-uncommitted effects such as:

- origin `commandUid`;
- resolved domain operations/deltas;
- expected old versions/state used for commit validation;
- proposed persistent/runtime state changes;
- proposed inventory/equipment/ownership/finance/asset/project changes;
- proposed events/ledger entries;
- provenance propagated/augmented from command + mechanics;
- warnings/validation context;
- deterministic IDs allocated for proposed durable results where necessary;
- correlation/causation links.

Crucially:

```text
PlayerCommand = intent
PlayerChangeSet = proposed resolved effects
COMMIT = truth
```

Phase 17 must not fold the command payload back into a generic StatePatch.

---

# 20. Implementation file plan for future Phase 16

This is a planning recommendation only; no files are created by this work item except this report.

Minimal likely production files:

```text
app/src/main/java/com/rpgos/app/PlayerCommand.kt
app/src/main/java/com/rpgos/app/PlayerCommandPayload.kt
app/src/main/java/com/rpgos/app/PlayerCommandKinds.kt
app/src/main/java/com/rpgos/app/PlayerCommandValidation.kt
app/src/main/java/com/rpgos/app/PlayerCommandCodec.kt   // only if serialization is implemented in Phase 16
```

Potential tests:

```text
app/src/test/java/com/rpgos/app/PlayerCommandSemanticTest.kt
app/src/test/java/com/rpgos/app/PlayerCommandSerializationTest.kt
app/src/test/java/com/rpgos/app/PlayerCommandBoundaryTest.kt
```

Files Phase 16 should normally NOT modify:

- `MigrationManager.kt`;
- Phase migration files;
- `StatePatchEngine.kt` except only if a separately reviewed compile-time boundary is unavoidable — preferred no change;
- authoritative domain stores Phase 3–15;
- `SourceOfTruthRegistry.kt` unless later implementation accidentally introduces persistence, which this architecture rejects;
- `ContextBuilder.kt`;
- MASTER/Roadmap/coordination global statuses.

No schema migration is expected.

---

# 21. Test matrix

## CMD-SEM-01 — stable UID exact equality

Create two independently deserialized/constructed commands with same `commandUid` and identical immutable semantic content.

Expected:
- semantic equality/fingerprint equal;
- same logical command identity.

## CMD-SEM-02 — same UID conflicting payload

Same command UID, changed amount/target/kind/payload field.

Expected:
- deterministic semantic conflict;
- never treated as exact retry.

## CMD-SEM-03 — campaign/actor identity

Reject structurally blank campaign/actor kind/actor UID.

Verify same actor UID string in different actor namespaces remains distinct structurally.

Do not claim actual existence/authorization in Phase 16 test.

## CMD-SEM-04 — typed payload validation

For each implemented command kind:

- required fields enforced;
- negative/zero/overflow-prone numeric intent rejected where invalid;
- payload type must match kind.

No arbitrary map accepted.

## CMD-SEM-05 — unknown command kind

Unknown `commandKindUid` / codec not registered.

Expected:
- explicit unsupported-command structural failure;
- no fallback to generic map or StatePatch.

## CMD-SEM-06 — cross-campaign target/reference

Phase-16 structural test may carry a target ref from an arbitrary UID because existence is not its responsibility.

Architecture test must prove PlayerCommand itself does not resolve or rebind target to another campaign.

Integration gate for Phase 18:
- actual cross-campaign resolution rejected by authoritative resolver.

## CMD-SEM-07 — no raw StatePatch/SQL authority

Compile/API boundary tests assert no command payload exposes:

- table name;
- column name;
- SQL;
- PatchOperation;
- StatePatch;
- mutation callback.

## CMD-SEM-08 — serialization/version round-trip

If codec is implemented:

```text
command -> serialize -> deserialize -> same semantic command
```

Test unknown/new version failure and stable discriminator.

## CMD-SEM-09 — DevelopmentProject references without domain duplication

Construct project commands that reference project/requirement/milestone UIDs.

Assert payload has no authoritative `ProjectWorkResult`, `progressDeltaUnits`, `ProjectOutcome` object, or direct `ProjectStatusEvent` write representation unless a future command intentionally requests a desired status as intent.

## CMD-SEM-10 — command does not mutate authoritative state by construction

Instantiate/validate/serialize every Phase-16 command against a database fixture.

Expected:
- zero changes to authoritative tables;
- ideally Phase-16 command package requires no SQLite database at all.

## CMD-SEM-11 — command UID distinct from domain IDs

Verify API permits command UID distinct from project/transaction/event/item IDs and never auto-copies them as identity.

## CMD-SEM-12 — causation/correlation semantics

Same command with different causation/correlation fields follows documented immutable equality rules.

No field implies commit/order by itself.

## CMD-SEM-13 — typed preconditions

Validate supported typed expected-version/state structure.

Reject generic table/column predicate representation.

## CMD-SEM-14 — actor is not authorization proof

Structural validator accepts only shape; no API method named/typed as `authorized=true` or caller-declared authorization proof.

Authorization is intentionally absent.

## CMD-SEM-15 — no caller-declared derived/canonical result

Reflection/API-surface assertion or explicit contract tests confirm representative payloads do not contain fields such as:

- finalBalance;
- effectiveStat;
- masteryResult;
- ownershipRecord;
- projectOutcome;
- committedEvent.

---

# 22. Future concurrency gates deferred to Phase 18/28

Because Phase 16 has no persistence and no mutation, do not create artificial SQLite race tests.

The following become mandatory when execution/idempotency is implemented:

```text
CMD-RACE-01 exact same UID retry
CMD-RACE-02 conflicting same UID
CMD-RACE-03 stale expected version
CMD-RACE-04 target changed before execution
CMD-RACE-05 ambiguous-result retry
CMD-RACE-06 competing different commands on same invariant
CMD-RACE-07 authorization changed
CMD-RACE-08 rule-version drift
```

Existing authoritative SQLite gates in Equipment/Ownership/Finance/Assets/DevelopmentProject must remain the final safety boundary for their respective invariants.

Application-side PlayerCommand validation can never replace them.

---

# 23. Acceptance gates

Phase 16 is implementation-ready only when Phase 15 is accepted and the final implementation satisfies all of the following.

## Semantic gates

- Roadmap still identifies Phase 16 as PlayerCommand contract.
- PlayerCommand remains intent-only.
- no `PlayerChangeSet` implementation hidden inside Phase 16.
- no domain resolver/mechanics/WorldRule logic hidden in command codecs.
- no committed-result fields in payloads.
- command kinds are universe-agnostic.

## Identity gates

- stable command UID;
- deterministic exact equality/canonical representation;
- conflicting same-UID semantic payload detectable;
- campaign + actor identity mandatory;
- command/domain/event/transaction identities remain separate.

## Structural gates

- typed payload registry/discriminator;
- unknown kind fails;
- no arbitrary map mutation payload;
- schema version supported;
- typed preconditions only;
- numeric bounds exact.

## StatePatch/security gates

- no PlayerCommand -> generic StatePatch execution path;
- no raw tables/columns/SQL;
- no mutation callbacks;
- no direct authoritative DB writes from command model/validator/codec.

## Persistence gates

- no new command-history DB migration/table in Phase 16;
- serialization round-trip only if transport codec is in scope;
- no synthetic legacy command history.

## Compatibility gates

- no regression/mutation in Phase 3–15 authoritative domains;
- DevelopmentProject payloads reference but do not duplicate project authority;
- Finance/Ownership/Asset command intents do not create canonical records themselves.

## Test gates

`CMD-SEM-01..10` mandatory; `CMD-SEM-11..15` strongly recommended.

No SQLite race suite required until a phase introduces persistent/executing command semantics.

---

# 24. Known risks / open questions

## 24.1 Phase 15 currently FAIL

This is the only immediate implementation blocker for Phase 16.

Current exact candidate has two independent release blockers. This report does not solve them.

## 24.2 Actor reference naming

Existing `OwnershipOwnerRef` has the correct kind+UID shape and registry foundation, but its ownership-domain name is too narrow for universal command actors.

Open implementation choice after Phase 15 acceptance:

- reuse it internally and expose a generic alias/adapter;
- or introduce `CommandActorRef` backed by the same resolver/registry identity.

Do not duplicate party identity registries.

## 24.3 Command kind extensibility vs sealed compile-time safety

A Kotlin `sealed interface` is safe but not dynamically extensible across separately loaded World Packs. A runtime plugin registry is extensible but can become untyped if poorly designed.

Recommended compromise:

- typed `PlayerCommandPayload` interface;
- stable kind registry with generic typed codecs;
- Core built-ins compile-time typed;
- any extension must register a typed codec/schema, never a raw map.

## 24.4 Provenance shape

MASTER lists richer provenance dimensions than many current domain models. Phase 16 should not invent a new large universal provenance model if a shared canonical provenance contract is about to emerge elsewhere.

Minimal Phase-16 provenance must include at least stable source kind/source UID where available, and be extensible without changing command identity rules.

## 24.5 Requested time semantics

Only advisory `requestedEffectiveOrder` is proposed. Scheduler/future command execution is not Phase 16. Do not add `executeAt` queue semantics prematurely.

## 24.6 Output UID reservation

Some commands may eventually need deterministic IDs for objects they intend to create. Phase 16 should not assume caller-provided domain record UID is always valid. Phase 17/18 must define whether IDs are preallocated during change-set construction or accepted from command under typed conflict rules.

## 24.7 Command fingerprint algorithm

If a fingerprint is implemented in Phase 16, the canonical serialization/hash algorithm becomes compatibility-sensitive. It may be safer to implement semantic equality + deterministic serialization now and persist/hash only when Phase 28 requires it.

---

# 25. Final architecture decision

Canonical Phase-16 architecture:

```text
PlayerCommand
= transient, immutable, typed, universe-agnostic intent envelope
+ stable command identity
+ campaign/actor identity
+ typed payload
+ provenance/correlation/causation
+ optional typed optimistic preconditions
+ deterministic schema/version semantics
```

It is deliberately **not**:

```text
StatePatch
PlayerChangeSet
DB transaction
commit log
mutation executor
WorldRule resolver
mechanics result
committed event
```

Persistence decision:

```text
NO authoritative PlayerCommand persistence in Phase 16.
```

Future direction:

```text
typed PlayerCommand
-> Phase 18 PlayerDomainEngine
-> Phase 19 WorldRuleProvider / existing domain rules
-> Phase 17 PlayerChangeSet representation
-> later validation + TurnTransaction
-> authoritative domain stores / SQLite guards
-> COMMIT
```

The exact ordering between Phase-17 data-contract construction and Phase-18 runtime orchestration must follow Roadmap implementation sequencing, but semantically command remains upstream of change set and commit.

---

# 26. Dependency status at report close

Fresh master contains independent Phase-15 validation reports for exact candidate `47f85c1689fb78cfd5c7edd9d82f897485357dab`.

Observed results:

```text
Exact CI #311 / 31572017265: SUCCESS
Phase 15 semantic revalidation: FAIL
Phase 15 adversarial validation: FAIL
```

Therefore:

```text
PHASE 16 ARCHITECTURE: READY
PHASE 16 IMPLEMENTATION: BLOCKED
BLOCKER: Phase 15 not accepted
```

No Phase-16 runtime work is authorized by this report.
