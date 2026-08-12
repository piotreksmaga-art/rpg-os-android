# WORK-20260810-066 — Phase 16 PlayerCommand Architecture Audit

Status: READ-ONLY NEXT-PHASE ARCHITECTURE AUDIT / CONDITIONAL PREIMPLEMENTATION DESIGN

Work ID: `WORK-20260810-066`
Worker: `CHAT-4`
Repository: `piotreksmaga-art/rpg-os-android`
Accepted Phase-14 runtime: `8d78398462c7d9f748fc3dc002c01458b7656baf`
Phase-15 runtime candidate: `47f85c1689fb78cfd5c7edd9d82f897485357dab`
Exact Phase-15 CI: GitHub Actions `#311`, run ID `31572017265`, `SUCCESS`
Fresh master before report finalization: `2f22c6f39a3d79cd3c4e9545a7a5b9afe7cfc550`
Allowed write scope: this report only.

This report is architecture/audit only. It does not implement Phase 16, does not modify Kotlin runtime, SQLite schema, migrations, production routing, tests, MASTER, Roadmap, Parallel Work Coordination, or Phase-15 runtime.

At finalization, Phase 15 is **NOT ACCEPTED**. Three independent validations of exact candidate `47f85c1689fb78cfd5c7edd9d82f897485357dab` are FAIL:

1. semantic revalidation: declared `PROJECT_OUTPUT_TRUTH` cannot be committed by the current project outcome SQLite boundary;
2. adversarial validation: a milestone achievement may cite source work from the future;
3. migration/integrity revalidation: `source_work_record_uid` may also belong to another project, and temporal causality is not enforced.

These are Phase-15-only blockers. No hotfix is designed here.

```text
PHASE 16 IMPLEMENTATION REMAINS BLOCKED UNTIL PHASE 15 ACCEPTED.
```

---

# 1. Fresh-master evidence

The canonical Roadmap orders:

```text
15. DevelopmentProject model
16. PlayerCommand contract
17. PlayerChangeSet contract
18. PlayerDomainEngine orchestration
19. WorldRuleProvider contract
20. ProgressionEngine + Progression Ledger
```

Therefore the exact next phase is:

```text
PHASE 16 — PlayerCommand contract
```

MASTER is consistent. It defines one legal mutation path:

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

and Player Domain flow:

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

Actual runtime evidence at the Phase-15 candidate:

- typed authoritative domains already exist for Player State, Stats/Resources, modifiers, Talent/Potential, Skills, Techniques, Innate/Evolution, Inventory, Equipment, Ownership, Finance, Assets/Liabilities and DevelopmentProject;
- `SourceOfTruthRegistry` blocks typed Finance/Asset/Project tables from generic StatePatch authority;
- generic `StatePatchEngine` still accepts table/op/key/value operations for allowed tables;
- Phase 15 already stores optional `commandUid` evidence on work/outcome records but implements no PlayerCommand contract;
- no canonical `PlayerCommand`, `PlayerChangeSet`, `PlayerDomainEngine`, `WorldRuleProvider`, or ProgressionEngine implementation exists at this boundary.

Work ID selection: coordination specifies `WORK-YYYYMMDD-NNN`; numeric repository history already uses `061..065`, and `WORK-20260810-066` was unused. Recent ad-hoc `WORK-20260812-P15` labels do not occupy numeric `066`.

---

# 2. Canonical Phase-16 scope

PlayerCommand is the canonical **typed intent/request contract** for future Player Domain changes.

It answers:

```text
WHO requests WHAT,
in WHICH campaign,
against WHICH typed targets,
with WHICH stable command identity,
under WHICH optional optimistic expectations,
and with WHICH provenance/causation/correlation context.
```

It does not answer whether the action is legal or what effects result.

Hard boundary:

```text
PlayerCommand
!= StatePatch
!= PlayerChangeSet
!= committed mutation
!= database transaction
!= domain record
!= FinancialTransaction
!= OwnershipRecord
!= DevelopmentProject fact
!= event
!= narrative proposal
!= WorldRuleProvider result
```

PlayerCommand is intent only.

---

# 3. Explicit non-goals

Phase 16 must not implement:

- Phase 17 `PlayerChangeSet`;
- Phase 18 `PlayerDomainEngine`;
- Phase 19 WorldRuleProvider legality;
- Phase 20 progression calculation/ledger;
- Phase 22 global Player Invariant Validator;
- Phase 26 Single Truth Mutation Path enforcement;
- Phase 27 TurnTransaction;
- Phase 28 persisted double-commit/idempotency engine;
- Phase 30 Event Store command history;
- Scheduler/delayed execution;
- command inbox/outbox/queue;
- automatic migration of old StatePatch/chat/UI/narrative actions into command history.

---

# 4. Canonical command envelope

Recommended semantic envelope:

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

## Mandatory and immutable

`schemaVersion`
- positive command-contract version;
- not DB schema version.

`commandUid`
- stable logical command identity;
- nonblank;
- distinct from event/transaction/domain IDs.

`campaignUid`
- mandatory;
- never rebound to whichever campaign is active later.

`actor`
- generic stable actor/party ref;
- not ActivePlayer-only.

`commandKindUid`
- stable discriminator;
- must map to exactly one typed payload codec/type.

`payload`
- typed and immutable;
- never `Map<String, Any?>` mutation data.

`provenance`
- required source evidence;
- proves origin metadata, not legality/truth.

## Optional but immutable when present

`causationUid`
- immediate causal/request identity where known;
- does not imply commit.

`correlationUid`
- groups related commands;
- does not imply ordering or atomicity.

`requestedEffectiveOrder`
- advisory campaign-time/order intent;
- not authoritative effective order.

`preconditions`
- typed optimistic expectations only;
- must be revalidated later.

`extensions`
- typed namespaced extensions only;
- no arbitrary object map.

## IssuedAt/requestedAt decision

Do not make wall-clock `issuedAt` part of logical command identity. Wall clock is not campaign time and would make retry identity unstable.

If transport diagnostics need creation timestamp, keep it outside semantic command identity. `requestedEffectiveOrder` is sufficient for Phase-16 temporal intent. Actual `effectiveOrder`, commit time and turn identity belong later.

## Targets

Targets live inside typed payloads. Do not duplicate target fields in the envelope. A derived `targetRefs()` accessor is acceptable; a second serialized target list is not.

---

# 5. Typed command taxonomy

Recommended type boundary:

```text
interface PlayerCommandPayload

interface PlayerCommandKind<P : PlayerCommandPayload> {
    val kindUid: String
    val codec: TypedCommandCodec<P>
    fun structuralValidate(payload: P): StructuralResult
}
```

Registry responsibility:

```text
commandKindUid -> typed codec/validator
```

Never:

```text
commandKindUid -> database mutation callback
```

Core command kinds must be universe-agnostic. Naruto/Bleach semantics arrive later through definitions and WorldRuleProvider, not raw world-specific SQL or untyped payloads.

Representative families:

### Stats / resources

`TrainCommand`, `UseResourceActionCommand`, `RecoverCommand`.

May carry requested effort/amount/method. Must not carry final stat, effective value, max resource, or canonical post-state.

### Skills

`LearnSkillCommand`, `PracticeSkillCommand`, `UseSkillCommand`.

References stable skill UID. No caller-declared mastery/progress result.

### Techniques

`LearnTechniqueCommand`, `UseTechniqueCommand`.

Technique creation/modification should usually enter DevelopmentProject, not create a final Technique record from command payload.

### Inventory

`AcquireItemCommand`, `TransferItemCommand`, `ConsumeItemCommand`, `Move/DropItemCommand`.

Possession is distinct from ownership.

### Equipment

`EquipItemCommand`, `UnequipSlotCommand`.

Desired equip state is intent; resulting modifiers/effective stats are not payload truth.

### Ownership

`TransferOwnershipCommand`, `AcquireOwnershipCommand`.

Payload may request asset/share/party terms but cannot declare OwnershipRecord already valid.

### Finance

`TransferFundsCommand`, `ExternalPaymentCommand`.

Exact requested amount is legitimate intent. Caller cannot declare final balance, ledger status, or treat commandUid as transactionUid.

### Assets / liabilities

`AcquireAssetCommand`, `DisposeAssetCommand`, `EnterObligationCommand`, `SettleObligationCommand`.

Terms are requested intent; Phase-14 authority determines resulting records.

### DevelopmentProject

See section 12.

## Extensibility

Preferred compromise:

- typed `PlayerCommandPayload` interface;
- Core command kinds as compile-time typed data classes;
- stable kind registry with typed codecs;
- any World Pack extension must register a typed schema/codec;
- no raw map fallback for unknown command kinds.

---

# 6. Stable UID and idempotency semantics

Keep identities distinct:

```text
commandUid
!= domainRecordUid
!= transactionUid
!= financialTransactionUid
!= eventUid
!= turnUid
!= causationUid
!= correlationUid
```

For `(campaignUid, commandUid)`:

```text
same commandUid + exact immutable semantic command
=> same logical command identity
```

```text
same commandUid + conflicting immutable semantic content
=> deterministic COMMAND_IDENTITY_CONFLICT
```

Canonical comparison must cover campaign, actor, kind, payload, semantic provenance, requested order, preconditions and typed extensions according to documented equality rules.

Phase 16 defines identity/equality semantics only. It does **not** persist replay status. Phase 18/28 may later persist canonical command/fingerprint + execution outcome.

Command UID must not be derived from display text, target label, row ID, or timestamp-only scheme.

---

# 7. Actor, campaign and reference semantics

Recommended generic actor shape:

```text
CommandActorRef
- actorKindUid
- actorUid
```

Reuse the existing campaign-scoped party/entity identity foundation where semantically valid. Do not create a parallel actor registry.

`OwnershipOwnerRef` has the correct kind+UID shape but its public name is ownership-specific; Phase 16 should expose a generic actor ref backed by the same underlying resolver/registry contract rather than duplicate identity.

Structural validation checks only nonblank/valid shape. Existence, active lifecycle and authorization are later layers.

A caller saying `actorUid = SOME_NPC` is not authorization proof.

Generic target refs may use:

```text
DomainRef(kindUid, uid)
```

but Phase-12 lesson remains:

```text
nonblank UID != valid reference
```

Cross-campaign refs must be rejected later by authoritative resolver. PlayerCommand itself must never silently resolve against the current campaign if envelope says another campaign.

---

# 8. Structural validation

Phase 16 owns only structural validation:

1. supported `schemaVersion`;
2. nonblank command/campaign/actor IDs;
3. known command kind;
4. payload type matches kind;
5. required payload fields present;
6. exact numeric bounds/sign valid for the intent contract;
7. typed refs have valid shape;
8. typed preconditions valid structurally;
9. typed extension version supported;
10. no raw SQL/StatePatch/mutation primitive.

Phase 16 does **not** verify:

- item exists/owned;
- equipment slot free;
- account funded;
- owner authorized;
- asset transferable;
- project ready;
- skill/technique legal;
- World Pack rule permits action.

---

# 9. Validation layer separation

Canonical layers:

1. **Structural validation** — Phase 16.
2. **Reference validation** — later resolver/orchestration.
3. **Authorization/actor validation** — later security/orchestration.
4. **Domain invariant validation** — authoritative domains + later invariant pipeline.
5. **WorldRuleProvider validation** — Phase 19.
6. **Orchestration** — Phase 18.
7. **PlayerChangeSet generation** — Phase 17 contract, Phase 18 production.
8. **Commit** — later transaction/write boundaries.

Mandatory rule:

```text
command validation/precondition
!= authoritative commit-time validation
```

Application precheck cannot replace SQLite/domain guards learned in Phases 11–15.

---

# 10. Preconditions / expected state

Support a small typed optimistic family:

```text
sealed interface CommandPrecondition

ExpectedRecordVersion(target: DomainRef, expectedVersion: Long)
ExpectedLifecycleState(target: DomainRef, expectedStateUid: String)
```

Only add `ExpectedCampaignRevision` if a stable campaign revision contract actually exists.

Forbidden:

```text
ExpectedColumnValue(table, column, value)
```

because it recreates table-coupled StatePatch semantics.

Preconditions are immutable expectations, not proof. Stale precondition later yields deterministic rejection or re-resolution. A successful check at command creation never guarantees commit success.

---

# 11. Persistence decision

## Decision: Phase-16 PlayerCommand is a transient canonical request contract.

No new SQLite command table, inbox, queue, execution-status table or migration belongs to Phase 16.

Reasons:

- MASTER requires stable command identity, not Phase-16 persistence;
- Roadmap places double-commit protection at Phase 28;
- Event Store is later;
- PlayerChangeSet/PlayerDomainEngine do not exist yet;
- persisting commands now would create an incomplete execution state machine.

Allowed:

- in-memory immutable command;
- deterministic serialization/deserialization;
- semantic equality/canonical representation.

Not allowed:

- authoritative `player_commands` history table;
- replay status persistence;
- synthetic legacy command backfill.

Future Phase 18/27/28 can persist exact command execution identity once canonical commit semantics exist.

---

# 12. DevelopmentProject integration

Phase 15 already has authoritative project/status/requirement/milestone/work/dependency/outcome records. PlayerCommand must carry intent/references, never pre-authorized project facts.

### Start project

```text
StartProjectCommand
- projectTypeUid
- objective/title intent
- beneficiaryRef?
- targetDomainUid
- targetRef?
- intendedOutputKindUid?
- requestedProgressCapUnits?
```

Do not assume commandUid == projectUid. Output/domain UID allocation belongs later.

### Record/progress work

```text
RecordProjectWorkCommand
- projectUid
- workKindUid
- effortIntent?
- methodUid?
- evidenceRefs?
- requestedResourceUse?
```

Forbidden caller truth:

```text
result = BREAKTHROUGH
progressDeltaUnits = 50
milestoneAchieved = true
```

### Satisfy requirement

```text
SatisfyProjectRequirementCommand
- projectUid
- requirementUid
- evidenceRefs
```

Requests evaluation/attestation; does not set canonical `satisfied=true`.

### Achieve milestone

```text
AchieveProjectMilestoneCommand
- projectUid
- milestoneUid
- evidenceRefs/sourceWorkRef?
```

Current Phase-15 FAIL proves why source-work project identity and chronology must be revalidated by project authority, not trusted from command.

### Lifecycle transition

```text
ChangeProjectLifecycleCommand
- projectUid
- requestedStatus
- successorProjectUid? when semantically required
```

Desired status is intent only.

### Complete project

```text
CompleteProjectCommand
- projectUid
- completionEvidenceRefs?
```

Cannot declare canonical project outcome/final Technique/Skill/Item/Asset/Truth as already valid.

### Cancel project

```text
CancelProjectCommand
- projectUid
- reasonUid/reasonText?
```

Domain lifecycle determines legality/order.

PlayerCommand never duplicates `DevelopmentProjectStore` logic.

---

# 13. StatePatch boundary

Current StatePatch is table-oriented and directly mutates SQLite when registry allows it.

Forbidden:

```text
PlayerCommand(kind="STATE_PATCH", payload=StatePatch(...))
```

or any payload exposing table, column, SQL, PatchOperation, WHERE clause, mutation callback, or generic authoritative map.

Required long-term direction:

```text
typed PlayerCommand
-> future PlayerDomainEngine
-> authoritative typed domain operations
-> future PlayerChangeSet/transaction
```

not:

```text
PlayerCommand
-> arbitrary StatePatch
-> database
```

Phase 16 does not remove StatePatch and does not implement Phase 26. It establishes the deprecation boundary: no new PlayerCommand path may depend on generic StatePatch as universal executor.

---

# 14. Integration with Phase 3–15

PlayerCommand must never become a second resolver/source of truth.

- **Phase 3 Player State:** carries actor/campaign intent only; no replacement Persistent/Derived/Runtime state.
- **Phase 4 Stats/Resources:** requests actions; no final base/effective/current/max values.
- **Phase 5 Modifier/Resolver:** no caller-computed derived result authority.
- **Phase 6 Talent/Potential:** referenced/consumed later; no duplicate talent/potential state.
- **Phase 7 Skills:** stable skill refs; SkillStore remains authority.
- **Phase 8 Techniques:** stable technique refs; TechniqueStore remains authority.
- **Phase 9 Innate/Evolution:** requested activation/evolution intent only; legality later.
- **Phase 10 Inventory:** item refs/quantities; InventoryStore remains possession authority.
- **Phase 11 Equipment:** desired equip/unequip; EquipmentStore/SQLite guards remain authority.
- **Phase 12 Ownership:** transfer/acquisition intent; OwnershipStore/registries remain authority.
- **Phase 13 Finance:** requested accounts/amount; FinancialStore/ledger guards remain authority. `commandUid` may later link causally but is not FinancialTransaction UID.
- **Phase 14 Assets/Liabilities:** requested asset/obligation action; Phase-14 stores remain authority.
- **Phase 15 DevelopmentProject:** project references and intent only; store remains lifecycle/history/progress/outcome authority.

---

# 15. Concurrency / TOCTOU

Phase 16 is transient/non-mutating, so do not invent SQLite race tests solely for this phase.

It must however preserve future race-safe semantics.

### CMD-RACE-01 — same command UID + exact payload

Phase 16: canonical semantic equality.
Future Phase 18/28: one logical execution/commit; retry returns existing result/ALREADY_COMMITTED.

### CMD-RACE-02 — same UID + conflicting payload

Phase 16: deterministic semantic conflict.
Future: never execute second as retry.

### CMD-RACE-03 — stale expected version

Command expected target v5; target is v6 at execution.
Future: reject stale precondition or explicitly re-resolve.

### CMD-RACE-04 — target changes after command creation

Item transferred, account closed, project terminal, ownership share changed, asset retired.
Future: revalidate reference/domain state at execution and commit.

### CMD-RACE-05 — retry after ambiguous prior result

Client times out; command may have committed.
Stable command UID survives retry. Future persisted idempotency lookup decides.

### CMD-RACE-06 — different command UIDs compete on same invariant

Example: two spends of same funds or two equip actions. Both may structurally validate; authoritative domain/SQLite boundary decides coherent winner/result.

### CMD-RACE-07 — authorization changes

Permission revoked after creation. Authorization must be checked later again.

### CMD-RACE-08 — rule-version drift

World Pack changes between creation/execution. Phase 19 must later define current-vs-pinned rule semantics. Phase 16 must not guess.

---

# 16. Legacy / migration policy

No Phase-16 schema migration under the transient decision.

Do not synthesize command history from:

- historical StatePatch;
- old transactionId strings;
- chat choices;
- narrative text;
- UI button actions;
- chapter manifests;
- DevelopmentProject rows;
- FinancialTransactions;
- event-like rows.

Historical effects do not prove the original typed intent, actor authorization, payload, preconditions, causation, correlation or schema version.

```text
no unambiguous evidence
=> no synthetic PlayerCommand history
```

---

# 17. Serialization/version and Phase-17 handoff

## Serialization

If Phase 16 implements codec support:

- explicit `schemaVersion`;
- explicit `commandKindUid` discriminator;
- payload schema bound to kind/version;
- unknown kind fails;
- unsupported required version fails;
- exact integer semantics preserved;
- absent/null semantics deterministic;
- round-trip preserves semantic equality;
- no runtime class-name serialization as authority;
- no raw map fallback.

## Phase-17 handoff

PlayerCommand supplies:

- command UID;
- campaign UID;
- actor ref;
- command kind;
- typed intent payload;
- provenance;
- causation/correlation;
- optional requested order;
- typed preconditions;
- schema version.

PlayerCommand does NOT supply:

- old/new authoritative state;
- resolved stat/resource/mastery deltas;
- final created Technique/Item/Asset/Ownership records;
- ledger transactions/balance;
- final DevelopmentProject outcome;
- committed events;
- WorldRuleProvider result;
- transaction/commit status.

Phase 17 must later represent proposed, still-uncommitted effects including origin command UID, resolved domain operations, expected versions, events/ledger proposals, provenance/warnings and deterministic result IDs where required.

Canonical separation:

```text
PlayerCommand = intent
PlayerChangeSet = proposed resolved effects
COMMIT = truth
```

---

# 18. Implementation file plan

Future Phase-16 implementation should be small and schema-free.

Likely files:

```text
app/src/main/java/com/rpgos/app/PlayerCommand.kt
app/src/main/java/com/rpgos/app/PlayerCommandPayload.kt
app/src/main/java/com/rpgos/app/PlayerCommandKinds.kt
app/src/main/java/com/rpgos/app/PlayerCommandValidation.kt
app/src/main/java/com/rpgos/app/PlayerCommandCodec.kt   // only if codec is in scope
```

Tests:

```text
app/src/test/java/com/rpgos/app/PlayerCommandSemanticTest.kt
app/src/test/java/com/rpgos/app/PlayerCommandSerializationTest.kt
app/src/test/java/com/rpgos/app/PlayerCommandBoundaryTest.kt
```

Normally do not modify:

- migration chain/schema;
- Phase 3–15 stores;
- StatePatchEngine;
- SourceOfTruthRegistry;
- ContextBuilder;
- MASTER/Roadmap/coordination global status.

---

# 19. Test matrix and acceptance gates

## Mandatory semantic tests

### CMD-SEM-01 — stable UID exact equality

Same command UID + exact semantic envelope/payload => same logical command identity.

### CMD-SEM-02 — same UID conflicting payload

Changed target/amount/kind/semantic field => deterministic conflict.

### CMD-SEM-03 — campaign/actor identity

Blank campaign/actor kind/actor UID rejected structurally. Same UID in different actor namespaces remains distinct.

### CMD-SEM-04 — typed payload validation

Required fields and numeric bounds enforced; kind/payload mismatch rejected; no arbitrary map.

### CMD-SEM-05 — unknown command kind

Fails explicitly; no generic fallback.

### CMD-SEM-06 — cross-campaign target/reference

Command never rebinds campaign. Structural layer does not falsely claim ref validity; Phase-18 integration later must reject actual cross-campaign resolution.

### CMD-SEM-07 — no raw StatePatch/SQL authority

API surface exposes no table/column/SQL/PatchOperation/StatePatch/mutation callback.

### CMD-SEM-08 — serialization/version round-trip

If codec implemented: serialize -> deserialize -> exact semantic equality; unknown version/kind fails deterministically.

### CMD-SEM-09 — DevelopmentProject refs without duplication

Project commands reference stable project/requirement/milestone IDs and do not carry authoritative `ProjectWorkResult`, `progressDeltaUnits`, `ProjectOutcome`, or committed status event facts.

### CMD-SEM-10 — no mutation by construction

Construct/validate/serialize commands against a fixture; authoritative DB remains byte/row-equivalent. Prefer no SQLite dependency in Phase-16 code at all.

## Strongly recommended

- `CMD-SEM-11`: command UID remains distinct from domain/event/transaction IDs;
- `CMD-SEM-12`: causation/correlation equality semantics deterministic;
- `CMD-SEM-13`: typed preconditions only, no table/column predicate;
- `CMD-SEM-14`: actor ref is not caller-declared authorization proof;
- `CMD-SEM-15`: payloads expose no caller-declared derived/canonical result fields.

## Acceptance gates

Phase 16 implementation is acceptable only if:

- Roadmap still says Phase 16 PlayerCommand contract;
- Phase 15 is finally accepted;
- PlayerCommand remains transient immutable intent;
- no Phase 17–20 implementation leaks into it;
- no StatePatch wrapper/generic SQL path exists;
- stable UID/equality/conflict semantics are deterministic;
- campaign/actor identities are explicit;
- command kinds are typed and universe-agnostic;
- structural validation is the only validation responsibility owned by Phase 16;
- no command-history DB migration/table is added;
- legacy command history is not synthesized;
- CMD-SEM-01..10 pass;
- Phase 3–15 authoritative state is unaffected by command creation/validation/serialization.

---

# 20. Known risks / open questions

1. **Phase 15 is currently FAIL** — immediate blocker; this report does not fix it.
2. **Actor reference naming** — reuse existing party identity/resolver foundation without leaking `OwnershipOwnerRef` naming into universal command API or duplicating registries.
3. **Sealed safety vs dynamic extensibility** — use typed codec registry; never untyped fallback.
4. **Provenance shape** — keep minimal but compatible with MASTER; do not invent a second incompatible universal provenance system.
5. **Requested time** — advisory only; Scheduler semantics later.
6. **Output UID reservation** — Phase 17/18 must decide whether result IDs are engine-allocated or caller-reserved under an explicit typed contract.
7. **Command fingerprint** — semantic equality/canonical serialization is required; persistent hash algorithm can wait for Phase 28 to avoid premature compatibility lock-in.

---

# Final decision

```text
ROADMAP NEXT PHASE: 16 — PlayerCommand contract

PlayerCommand
= transient
+ immutable
+ typed
+ universe-agnostic
+ stable command identity
+ explicit campaign/actor identity
+ typed payload
+ provenance/causation/correlation
+ optional typed optimistic preconditions
+ deterministic schema/version semantics
```

It is not a mutation executor, StatePatch, ChangeSet, transaction, rule result or committed fact.

Persistence decision:

```text
NO authoritative PlayerCommand persistence in Phase 16.
```

Dependency state at finalization:

```text
Exact Phase-15 CI #311 / 31572017265: SUCCESS
Phase-15 semantic validation: FAIL
Phase-15 adversarial validation: FAIL
Phase-15 migration/integrity validation: FAIL

PHASE 16 ARCHITECTURE: READY
PHASE 16 IMPLEMENTATION: BLOCKED UNTIL PHASE 15 ACCEPTED
```
