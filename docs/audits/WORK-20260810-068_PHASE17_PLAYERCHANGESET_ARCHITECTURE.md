# WORK-20260810-068 — Phase 17 PlayerChangeSet Architecture Audit

Status: READ-ONLY NEXT-PHASE ARCHITECTURE AUDIT / PREIMPLEMENTATION DESIGN

Work ID: `WORK-20260810-068`
Worker: `CHAT-4`
Repository: `piotreksmaga-art/rpg-os-android`
Accepted Phase-15 runtime baseline: `173e501fbe832980bb4eaf177c5ba34d93cd5f37`
Fresh master observed before report write: `9e3825e624bd108646aee01847d7f0f19d6dd20e`
Current Phase-16 implementation work: `WORK-20260810-067`
Allowed write scope: this report only.

This report is architecture/audit only. It does not implement Phase 17, does not modify Kotlin runtime, SQLite schema, migrations, StatePatch, PlayerCommand, production routing, tests, MASTER, Roadmap, Parallel Work Coordination, or Phase-16 code.

At report time Phase 16 has runtime implementation on master but is not treated as final ACCEPTED dependency. Therefore:

```text
PHASE 17 ARCHITECTURE: READY
PHASE 17 IMPLEMENTATION: BLOCKED UNTIL PHASE 16 ACCEPTED
```

---

# 1. Fresh-master evidence and Roadmap decision

The current canonical Roadmap orders the relevant phases:

```text
15. DevelopmentProject model
16. PlayerCommand contract
17. PlayerChangeSet contract
18. PlayerDomainEngine orchestration
19. WorldRuleProvider contract
20. ProgressionEngine + Progression Ledger
```

Therefore the exact next phase after Phase 16 is:

```text
PHASE 17 — PlayerChangeSet contract
```

MASTER is consistent. It defines the global mutation flow as:

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

and the Player Domain flow as:

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

MASTER explicitly says PlayerChangeSet may contain stat/resource/skill/technique/innate/inventory/equipment/money/asset/ownership/condition/runtime changes, events, ledger entries, provenance and warnings, and remains a proposal until COMMIT.

No Roadmap/MASTER conflict was found.

## Work ID

Numeric repository work has reached `WORK-20260810-067` for Phase 16. `WORK-20260810-068` is the next unused numeric work ID observed at audit start and is used for this report.

---

# 2. Actual Phase-16 boundary observed

Fresh master contains a transient typed PlayerCommand implementation split across:

```text
PlayerCommandModel.kt
PlayerCommandRegistry.kt
PlayerCommandCoreCodecs.kt
PlayerCommandContractTest.kt
```

The implemented command envelope includes:

```text
schemaVersion
commandUid
campaignUid
actor
commandKindUid
typed payload
provenance
causationUid?
correlationUid?
requestedEffectiveOrder?
typed preconditions
typed extensions
```

Phase 16 also provides canonical deterministic serialization/fingerprinting and command semantic identity. Same `(campaignUid, commandUid)` with different canonical content is an identity conflict.

Important observed boundary:

- PlayerCommand is transient;
- no command persistence table exists;
- no command execution status exists;
- no mutation authority exists in Phase 16;
- Phase 17+ is explicitly not implemented by the Phase-16 commits.

Phase 17 must preserve this separation rather than expanding PlayerCommand into mutation data.

---

# 3. Canonical Phase-17 scope

PlayerChangeSet is the canonical typed **proposed-effects contract** emitted after command interpretation/resolution and before commit.

Hard semantic split:

```text
PlayerCommand = requested intent
PlayerChangeSet = proposed typed effects
Committed domain records = authoritative reality after transaction commit
```

And:

```text
PlayerChangeSet
!= PlayerCommand
!= StatePatch
!= raw SQL
!= database transaction
!= committed state
!= event history itself
!= ledger authority itself
!= WorldRuleProvider result by itself
!= PlayerSnapshot
```

PlayerChangeSet answers:

```text
WHAT typed effects are proposed,
for WHICH campaign,
caused by WHICH command,
against WHICH stable domain targets,
under WHICH expected-state guards,
with WHICH provenance,
and which event/ledger intents must accompany the same future commit.
```

It does NOT answer:

```text
whether commit succeeded,
what transaction UID was committed,
whether optimistic state is still current,
or whether the database now contains the effects.
```

---

# 4. Explicit non-goals

Phase 17 must not implement:

- Phase 18 PlayerDomainEngine orchestration;
- Phase 19 WorldRuleProvider;
- Phase 20 ProgressionEngine/Progression Ledger calculation;
- Phase 22 global Player Invariant Validator;
- Phase 26 Single Truth Mutation Path enforcement;
- Phase 27 TurnTransaction;
- Phase 28 persisted idempotency/double-commit protection;
- Phase 30 Event Store persistence;
- direct domain-store writes;
- DB schema/migrations for ChangeSet persistence;
- generic StatePatch wrapping;
- AI-facing free-form mutation JSON.

---

# 5. Persistence decision

## Decision: PlayerChangeSet is transient in Phase 17.

Phase 17 should not introduce a `player_change_sets` SQLite table, inbox, outbox or committed history.

Reasons:

1. MASTER says ChangeSet is a proposal until COMMIT.
2. TurnTransaction is Phase 27.
3. persisted idempotency/double-commit is Phase 28.
4. Event Store persistence is later.
5. Persisting a ChangeSet now would create an ambiguous state: durable but not committed truth.

Allowed in Phase 17:

- immutable in-memory model;
- deterministic canonical serialization;
- fingerprinting/equality;
- structural validation;
- typed conflict detection inside one set.

Persistence, execution status and transaction association belong to later orchestration/transaction architecture.

Legacy consequence:

```text
No existing StatePatch/chat/narrative/UI mutation history is backfilled into synthetic PlayerChangeSet history.
```

No evidence => no synthetic ChangeSet.

---

# 6. Canonical ChangeSet envelope

Recommended minimal contract:

```text
PlayerChangeSet
- schemaVersion: Int
- changeSetUid: String
- campaignUid: String
- sourceCommandUid: String
- actor: CommandActorRef
- changes: List<PlayerDomainChange>
- eventIntents: List<PlayerEventIntent>
- ledgerIntents: List<PlayerLedgerIntent>
- preconditions: List<ChangeSetPrecondition>
- provenance: ChangeSetProvenance
- causationUid: String?
- correlationUid: String?
- requestedEffectiveOrder: Long?
- warnings: List<ChangeSetWarning>
```

## Mandatory immutable fields

### `schemaVersion`

- positive;
- version of ChangeSet contract, not SQLite schema;
- independent from `PLAYER_COMMAND_SCHEMA_VERSION`.

### `changeSetUid`

- required stable UID for this exact proposed effect set;
- immutable;
- not equal by definition to `commandUid`, transaction UID, event UID or any domain record UID.

### `campaignUid`

- required;
- must equal the source command campaign at orchestration handoff;
- never rebound to active UI campaign later.

### `sourceCommandUid`

- required direct causal link to the command being resolved;
- must not be inferred from text;
- one command may theoretically produce a rejected resolution or one canonical ChangeSet attempt; future orchestration policy decides multiplicity/re-resolution semantics.

### `actor`

- required generic stable actor reference;
- normally inherited from source command;
- does not itself prove authorization.

### `changes`

- typed immutable proposed domain effects;
- may be empty only for explicitly no-op/rejected-resolution contracts if Phase 18 chooses to represent those as ChangeSets; Phase-17 MVP SHOULD require at least one proposed effect or event/ledger intent to avoid meaningless sets.

### `provenance`

- required resolution provenance;
- identifies source engine/rule/mechanic/version where available;
- must preserve source command relation.

## Optional immutable fields

### `causationUid` / `correlationUid`

Preserve causal/workflow trace where available. They do not imply atomic commit by themselves.

### `requestedEffectiveOrder`

May carry the resolved/requested order from the command pipeline but is still not committed time.

### `warnings`

Typed non-authoritative diagnostics. Warnings cannot mutate legality or state.

---

# 7. UID and identity semantics

Keep all identities distinct:

```text
commandUid
!= changeSetUid
!= changeUid
!= domainRecordUid
!= transactionUid
!= eventUid
!= financialTransactionUid
!= turnUid
!= causationUid
!= correlationUid
```

Recommended individual effect identity:

```text
PlayerDomainChange
- changeUid: String
- changeKindUid: String
- payload: typed change payload
```

Individual `changeUid` is valuable because cross-domain sets can contain many effects and later validation/transaction diagnostics need stable per-effect addressing.

Identity semantics:

```text
same campaignUid + same changeSetUid + exact canonical immutable ChangeSet
=> same logical ChangeSet proposal
```

```text
same campaignUid + same changeSetUid + different immutable content
=> CHANGE_SET_IDENTITY_CONFLICT
```

Within one ChangeSet:

```text
same changeUid + exact content repeated
=> reject as DUPLICATE_CHANGE (preferred)
```

Do not silently deduplicate. Duplicate generation is an orchestration defect and should remain visible.

```text
same changeUid + conflicting content
=> CHANGE_IDENTITY_CONFLICT
```

Distinct change UIDs that target the same semantic record may still conflict; Phase 17 should detect obvious structural conflicts, while domain-specific legality remains Phase 18+/domain validation.

---

# 8. Deterministic serialization

Phase 17 should mirror the successful Phase-16 pattern:

- explicit schema version;
- explicit stable kind UIDs;
- typed codecs;
- canonical field ordering;
- deterministic encoding of lists in semantic order;
- SHA-256 or equivalent fingerprint over canonical bytes;
- strict decode of unknown required kinds;
- no reflection-driven arbitrary object serialization as semantic authority.

List ordering must be contractually meaningful.

Recommended rule:

```text
changes preserve generated execution/validation order
```

Therefore a caller must not reorder changes without changing the ChangeSet fingerprint.

If a set is intended to be order-independent, that fact must be explicit in a later atomic operation group contract; do not globally sort by UID and accidentally erase dependency order.

---

# 9. Typed domain change system

Do not implement:

```text
Change(table, column, operation, value)
```

or:

```text
Map<String, Any?>
```

Use an extensible typed family:

```text
sealed interface PlayerDomainChangePayload

interface PlayerDomainChangeKind<P : PlayerDomainChangePayload> {
    val kindUid: String
    val payloadCodec: TypedChangeCodec<P>
    fun structuralValidate(payload: P): List<ChangeSetError>
}
```

Registry:

```text
changeKindUid -> typed codec + structural validator
```

Never:

```text
changeKindUid -> SQL callback
```

`PlayerDomainChange` wraps:

```text
changeUid
changeKindUid
typed payload
targetRefs
expectedState?
provenance?
```

Targets should live once in typed payload unless a generic read-only accessor derives them.

---

# 10. Proposed typed change taxonomy

Exact Kotlin class names are implementation choices. The semantic surface should cover earlier authorities without duplicating them.

## 10.1 Stats

Examples:

```text
AdjustBaseStatChange(playerUid, statDefinitionUid, deltaExact)
SetBaseStatResolvedChange(playerUid, statDefinitionUid, expectedOldValue, proposedNewValue)
```

Prefer operation shapes matching accepted StatStore APIs.

Forbidden:

- setting derived/effective stat as authoritative;
- caller-provided formula result without provenance;
- table/column addressing.

## 10.2 Resources

Examples:

```text
AdjustResourceCurrentChange(playerUid, resourceDefinitionUid, deltaExact)
SetResourceCurrentResolvedChange(...)
```

Derived max/regeneration should not be persisted as authoritative change merely because mechanics calculated them. Instead use explicit recompute/invalidation intents if needed.

## 10.3 Modifiers

Typed add/expire/remove modifier proposals referencing stable modifier identities and sources. Do not mutate resolved effective values directly.

## 10.4 Talent / Potential

Only permanent profile changes explicitly resolved by lawful mechanics should appear. Training commands should not arbitrarily rewrite Potential.

## 10.5 Skills

Typed operations such as:

```text
LearnSkillChange
AdjustSkillProgressChange
SetSkillMasteryResolvedChange
```

must reference stable Skill UID and player UID. No raw legacy XP columns.

## 10.6 Techniques

Typed learn/progress/use-history proposals. New technique definition creation from DevelopmentProject must reference the actual Technique-domain creation contract, not embed arbitrary technique JSON.

## 10.7 Innate / racial / bloodline / evolution

Typed state transition/entry proposals referencing accepted Phase-9 definitions and stable player identity. Evolution legality remains domain/WorldRuleProvider validation.

## 10.8 Inventory

Separate typed effects for stack quantity and unique ItemInstance presence/transfer. Preserve:

```text
Inventory possession != Equipment != Ownership
```

A purchase resolution may need multiple changes (finance + inventory + ownership), not one generic `PURCHASE_ROW_UPDATE`.

## 10.9 Equipment

Typed equip/unequip proposals referencing item instance and slot. Resulting derived modifiers are separate changes/recompute effects, not embedded arbitrary stat values.

## 10.10 Ownership

Typed acquire/transfer/close ownership-operation proposal compatible with OwnershipStore semantics. It proposes operation terms; it does not declare `OwnershipRecord` already authoritative.

## 10.11 Finance

Prefer typed ledger-entry intent rather than mutable balance change:

```text
FinancialTransferIntent
ExternalCreditIntent
ExternalDebitIntent
ReversalIntent
```

Canonical balance remains derived/projection from ledger authority. A ChangeSet must never contain:

```text
SetBalance(account, 12345)
```

## 10.12 Assets / liabilities

Typed create/update lifecycle/valuation/obligation/settlement intents matching accepted Phase-14 authority. Net worth is derived and cannot be a direct authoritative change.

## 10.13 DevelopmentProject

Typed project operation proposals compatible with Phase 15:

```text
CreateProjectChange
RecordProjectWorkChange
SatisfyProjectRequirementChange
AchieveProjectMilestoneChange
ChangeProjectStatusChange
CommitProjectOutcomeLinkChange
```

Important boundary:

Phase 16 command payload carries intent such as effort/evidence. Phase 17 may carry the resolved proposed canonical work result/progress delta **because it is the output of mechanics/rules**, but it is still only proposed until domain validation + transaction commit.

Thus:

```text
RecordProjectWorkCommand.effort intent
-> resolution
-> ProjectWorkChange(resultKind, progressDeltaUnits, evidence refs)
```

is legal architecture.

But:

```text
caller supplies BREAKTHROUGH as command truth
```

remains forbidden.

## 10.14 Conditions/runtime

Typed runtime state/condition proposals are allowed because MASTER explicitly includes condition/runtime changes. They still require classification and must not overwrite persistent achievements by accident.

---

# 11. Authoritative vs Derived distinction

Every change kind must declare one of:

```text
AUTHORITATIVE_MUTATION_INTENT
DERIVED_RECOMPUTE_INTENT
RUNTIME_MUTATION_INTENT
LEDGER_APPEND_INTENT
EVENT_APPEND_INTENT
CACHE_INVALIDATION_INTENT
```

This is a semantic classification, not a permission to write directly.

Critical rules:

1. `DERIVED_RECOMPUTE_INTENT` does not carry caller-declared final derived truth unless the relevant resolver defines a deterministic materialized projection contract.
2. Net worth, effective stats and canonical account balances are not direct authoritative mutation payloads.
3. Cache invalidation is never campaign truth.
4. Event/ledger intents become history only after the same future transaction commits.

A ChangeSet may include derived recompute instructions so downstream code knows what must be rebuilt after authoritative effects, but those instructions cannot be used to replace authoritative inputs.

---

# 12. Expected versions and preconditions

Phase 16 already has command-level optimistic preconditions:

```text
ExpectedRecordVersion
ExpectedLifecycleState
```

Phase 17 must not blindly copy them and assume they passed forever.

Recommended separation:

```text
source command preconditions
-> Phase 18 resolution checks
-> resolved ChangeSet commit guards
```

ChangeSet may carry typed `ChangeSetPrecondition` / `ExpectedStateGuard` values representing the exact state assumptions used to produce proposed effects.

Examples:

```text
ExpectedRecordVersion(targetRef, expectedVersion)
ExpectedLifecycleState(targetRef, expectedStateUid)
ExpectedCanonicalValueVersion(targetRef, version)
```

Do not introduce raw column predicates.

These guards are mandatory input to later commit-time validation where relevant.

Key invariant:

```text
resolution-time validation != commit-time validation
```

If state changes after ChangeSet creation, stale ChangeSet must be rejected/re-resolved rather than blindly committed.

---

# 13. Provenance / causation

Recommended `ChangeSetProvenance`:

```text
sourceCommandUid
resolverKindUid
resolverVersion
worldRuleProviderUid?       // future Phase 19
mechanicsVersion?           // future Phase 20+
sourceEventUid?
notes?/typed metadata extension
```

Do not require future components before they exist; optional fields may remain null.

Per-change provenance should be available when a set contains outputs from multiple rule/mechanics sources. Either:

- each `PlayerDomainChange` carries a typed provenance override; or
- change inherits ChangeSet provenance and optionally adds `sourceRuleUid`.

The architecture must be able to explain later:

```text
which command caused which proposed change
which committed event/ledger/domain fact came from which change
```

without equating all IDs.

---

# 14. Event and ledger intents

MASTER permits events and ledger entries in PlayerChangeSet.

To avoid implementing Phase 30/23 prematurely, Phase 17 should model **intents**, not persisted records.

```text
PlayerEventIntent
- eventIntentUid
- eventKindUid
- actorRef?
- targetRefs
- causalChangeUids
- proposedEffectiveOrder?
- typed payload
- provenance
```

```text
PlayerLedgerIntent
- ledgerIntentUid
- ledgerKindUid
- causalChangeUids
- typed payload
- provenance
```

Finance-specific ledger operations should usually be represented through the accepted Financial domain operation intent rather than a generic ledger JSON blob.

No event/ledger intent is historical truth before commit.

---

# 15. Atomicity expectations

Phase 17 does not implement TurnTransaction, but must define the future expectation:

```text
ONE PlayerChangeSet = ONE atomic proposal unit by default.
```

If accepted for commit:

```text
all authoritative changes + required ledger entries + required events
must commit together or none become reality.
```

No partial success semantics belong in the base Phase-17 contract.

If future orchestration needs partial actions, it should produce multiple explicit ChangeSets/transactions rather than silently committing a prefix of one ChangeSet.

Do not add a caller-controlled `allowPartial=true` flag.

---

# 16. Structural validation owned by Phase 17

Phase 17 should validate only contract-level structure/consistency:

1. supported schema version;
2. nonblank `changeSetUid`, `campaignUid`, `sourceCommandUid`;
3. actor ref structurally valid;
4. known typed change kinds;
5. payload type matches change kind;
6. unique nonblank `changeUid` inside set;
7. typed references structurally valid;
8. numeric signs/ranges structurally legal;
9. deterministic codec round trip;
10. event/ledger intent IDs unique;
11. causal references point to changes in the same set where required;
12. no raw SQL/table/column/StatePatch payload;
13. no forbidden direct derived-authority mutations;
14. obvious duplicate/conflicting typed changes rejected.

Phase 17 does not validate:

- target exists;
- actor authorized;
- account funded;
- ownership share aggregate remains legal;
- item is possessed;
- slot is free;
- project lifecycle permits transition;
- World Pack legality;
- progression formula correctness;
- no-retrogression globally.

Those belong to Phase 18+/authoritative domain validators/SQLite write boundaries.

---

# 17. Duplicate and conflict policy

A robust ChangeSet must reject ambiguous internal proposals before orchestration.

## Duplicate identity

```text
same changeUid twice
=> reject
```

Even exact duplicate content should be rejected rather than silently deduplicated.

## Obvious semantic conflicts

Examples Phase 17 can structurally reject:

- two exact `SetBaseStatResolvedChange` effects for same player/stat with different final values;
- equip two different items into one exclusive slot when represented as two final-state operations;
- create and delete/retire the same new domain identity in one set unless a dedicated composite operation explicitly supports it;
- two ledger intents reuse the same intent UID with different payload;
- event intent references nonexistent `causalChangeUid` within this set.

## Domain-dependent conflicts

Leave to later validation:

- ownership share aggregate across existing DB state;
- account funds;
- project transition legality;
- inventory availability;
- technique requirements.

Do not overbuild Phase 18 inside Phase 17.

---

# 18. PlayerCommand -> PlayerChangeSet boundary

Phase 16 supplies:

```text
stable command identity
campaign
actor
command kind
typed intent payload
provenance
causation/correlation
requested effective order
optimistic preconditions
```

Phase 16 does NOT supply:

```text
resolved numeric effects
resulting domain records
ledger entries
events
validated World Pack legality
commit status
```

Phase 17 represents the *result of future resolution*:

```text
PlayerCommand
-> [Phase 18 orchestration / Phase 19 rules / mechanics]
-> PlayerChangeSet proposal
```

Phase 17 contract must not itself call the resolver. It only provides the output type.

Required linkage:

```text
PlayerChangeSet.sourceCommandUid == PlayerCommand.commandUid
PlayerChangeSet.campaignUid == PlayerCommand.campaignUid
```

Actor should normally match command actor unless future orchestration explicitly supports delegated/derived actor semantics with provenance.

Command and ChangeSet fingerprints remain independent.

---

# 19. Phase-18 handoff

Phase 18 PlayerDomainEngine will need to:

1. receive structurally valid PlayerCommand;
2. resolve campaign/actor/target references;
3. authorize actor;
4. evaluate command preconditions;
5. route to domain/rule/mechanics handlers;
6. invoke WorldRuleProvider when Phase 19 exists;
7. build typed PlayerChangeSet;
8. run domain/global invariant validation;
9. revalidate expected-state guards before commit;
10. pass ChangeSet to future transaction boundary.

Phase 17 therefore must expose:

```text
PlayerChangeSetValidator.structuralValidate(...)
PlayerChangeSetCodec.encode/decode/fingerprint(...)
PlayerChangeSetIdentity.compare(...)
TypedPlayerChangeRegistry
```

but not:

```text
execute(changeSet)
commit(changeSet)
applyToDatabase(changeSet)
```

No `apply()` method should exist on PlayerChangeSet itself.

---

# 20. StatePatch boundary

Current StatePatch is table-oriented and directly writes SQLite for registry-allowed tables.

Phase 17 must explicitly forbid:

```text
PlayerDomainChange(kind = STATE_PATCH, payload = StatePatch(...))
PlayerDomainChange(table = "...", column = "...", value = ...)
PlayerDomainChange(sql = "UPDATE ...")
```

Target architecture:

```text
PlayerCommand
-> future PlayerDomainEngine
-> typed PlayerChangeSet
-> authoritative domain APIs / future TurnTransaction
```

Never:

```text
PlayerCommand
-> PlayerChangeSet
-> generic StatePatch
-> DB
```

Phase 26 may later eliminate/bound legacy mutation routes globally. Phase 17 must not depend on that work to maintain its own safety boundary.

---

# 21. Integration with Phase 3–16

## Phase 3 Player State

ChangeSet references stable campaign/player identity and preserves Persistent / Derived / Runtime distinctions.

## Phase 4 Stats/Resources

Typed change kinds target stable definition UIDs and accepted stores; no hardcoded Naruto/Bleach stat names in Core.

## Phase 5 Modifier/Resolver

ChangeSet mutates modifier/base inputs or requests derived recomputation; it does not become a second DerivedValueResolver.

## Phase 6 Talent/Potential

Changes reference canonical profile identities; no arbitrary training-to-potential rewrite.

## Phase 7 Skills

Typed skill operations only; no legacy raw row mutation.

## Phase 8 Techniques

Typed technique operations; creation/modification respects DevelopmentProject/domain authority.

## Phase 9 Innate/Evolution

Typed transition proposals, later World Pack validation.

## Phase 10 Inventory

Stack vs unique item identities preserved.

## Phase 11 Equipment

Slot/loadout state remains separate from possession and ownership.

## Phase 12 Ownership

Ownership operations use validated owner/asset reference model. Proposed operation != OwnershipRecord fact.

## Phase 13 Finance

Ledger transaction intent, not balance setting.

## Phase 14 Assets/Liabilities

Asset/obligation changes use canonical stable IDs; net worth remains derived.

## Phase 15 DevelopmentProject

ChangeSet may contain resolved canonical project operation proposals; project store remains authoritative writer after validation/transaction.

## Phase 16 PlayerCommand

Source command identity and deterministic serialization are consumed directly. Phase 17 does not reinterpret command payload as mutation data without resolution.

---

# 22. Concurrency / TOCTOU implications

Phase 17 is transient, so it should not invent SQLite race tests.

However it must preserve information needed for later safe concurrency handling.

## CS-RACE-01 — stale expected version

Command resolved at version N; record becomes N+1 before commit.

Expected later behavior:

```text
ChangeSet commit guard fails -> re-resolve/reject
```

Never blindly apply proposed values.

## CS-RACE-02 — target deleted/retired after resolution

Structurally valid ChangeSet references target that disappears before commit.

Later authoritative reference validation must reject.

## CS-RACE-03 — funds/quantity consumed concurrently

Two ChangeSets each look individually affordable/available.

SQLite/domain write boundary must arbitrate. ChangeSet precheck is not authority.

## CS-RACE-04 — ownership aggregate race

Two transfers independently satisfy prechecks but aggregate share would exceed 100% together.

Ownership write boundary remains authoritative.

## CS-RACE-05 — project lifecycle race

Progress work and cancellation/completion proposals compete.

Phase-15 SQLite guards remain authoritative.

## CS-RACE-06 — same ChangeSet UID exact retry

Future execution layer must converge to one logical proposal/execution result when persisted idempotency arrives.

## CS-RACE-07 — same ChangeSet UID conflicting payload

Deterministic identity conflict before any mutation.

## CS-RACE-08 — command re-resolution produces different ChangeSet

Same command may be re-resolved after state changed. Architecture must not reuse the old `changeSetUid` with different content. Either:

- deterministic same result -> same ChangeSet identity allowed; or
- changed proposed effects -> new `changeSetUid` / explicit supersession relation.

Phase 18 defines the operational policy.

Core principle:

```text
structural ChangeSet validation
!= authoritative commit-time invariant validation
```

---

# 23. Legacy / migration policy

Phase 17 requires no SQLite migration if kept transient.

Do not synthesize ChangeSets from:

- historical StatePatch rows/objects;
- old chat choices;
- narrative text;
- chapter manifests;
- legacy UI actions;
- existing ledger transactions;
- existing domain history.

Those records may be evidence of historical reality but do not prove the exact pre-commit proposal that produced it.

No evidence => no synthetic ChangeSet history.

Reopen/restore has no Phase-17 persistence requirement. Accepted Phase 3–15 data must remain unchanged by adding the contract.

---

# 24. Scale

PlayerChangeSet is per command/transaction proposal and should remain bounded.

Do not design a ChangeSet with tens of thousands of unrelated changes. Large batch/world updates should later be partitioned into transaction-safe units.

Recommended implementation guards:

- configurable maximum number of changes/event intents/ledger intents at parser/contract boundary;
- no fixed small limit that blocks legitimate multi-domain operations;
- payload byte-size budget if serialized across process/network boundaries;
- O(n) structural duplicate detection using UID maps;
- avoid O(n²) pairwise conflict scans where a semantic target key can index changes.

Authoritative campaign history scale is not stored inside ChangeSet; history remains domain/event/ledger responsibility.

---

# 25. Failure semantics

Structural failures should be deterministic typed codes, e.g.:

```text
UNSUPPORTED_CHANGESET_SCHEMA_VERSION
EMPTY_CHANGESET_UID
EMPTY_CAMPAIGN_UID
EMPTY_SOURCE_COMMAND_UID
UNKNOWN_CHANGE_KIND
CHANGE_PAYLOAD_TYPE_MISMATCH
DUPLICATE_CHANGE_UID
CHANGE_IDENTITY_CONFLICT
INVALID_TARGET_REF
INVALID_EXPECTED_VERSION
INVALID_EVENT_INTENT
INVALID_LEDGER_INTENT
FORBIDDEN_RAW_MUTATION
FORBIDDEN_DERIVED_AUTHORITY_CHANGE
CHANGESET_IDENTITY_CONFLICT
```

Phase 17 should not use exception message prose as semantic protocol.

Domain failures such as insufficient funds, illegal equip, ownership conflict or project lifecycle violation are not structural Phase-17 codes; they belong to later validation results.

---

# 26. Test matrix

## PCS-SEM-01 — stable ChangeSet UID exact equality

Same `(campaignUid, changeSetUid)` + exact canonical content -> same logical ChangeSet.

## PCS-SEM-02 — same UID conflicting payload

Same identity + changed effect/provenance/precondition -> deterministic `CHANGESET_IDENTITY_CONFLICT`.

## PCS-SEM-03 — command linkage

`sourceCommandUid`, campaign and actor linkage preserved through encode/decode.

## PCS-SEM-04 — typed payload validation

Each core change kind accepts only its declared payload type.

## PCS-SEM-05 — unknown change kind

Strict deterministic rejection.

## PCS-SEM-06 — cross-campaign structural boundary

ChangeSet campaign is immutable and cannot be silently rebound. Actual target campaign resolution remains Phase 18/domain validation.

## PCS-SEM-07 — no raw StatePatch/SQL authority

No type or codec accepts table name, column name, SQL or StatePatch payload as mutation authority.

## PCS-SEM-08 — deterministic serialization

encode -> decode -> encode byte-for-byte/canonical equality; stable fingerprint.

## PCS-SEM-09 — duplicate change UID

Two effects with same change UID -> reject, even if content is exact duplicate.

## PCS-SEM-10 — conflicting final-state proposals

Obvious same-target conflicting SET-style typed changes rejected structurally.

## PCS-SEM-11 — derived authority protection

Direct `SetNetWorth`, `SetEffectiveStat`, `SetCanonicalBalance` change kinds do not exist / are rejected.

## PCS-SEM-12 — finance ledger boundary

Finance proposal encodes ledger operation intent, not balance mutation.

## PCS-SEM-13 — inventory/equipment/ownership separation

Representative multi-domain set preserves separate typed effects.

## PCS-SEM-14 — DevelopmentProject resolution handoff

A project-work ChangeSet may contain resolved proposed progress/result, linked to source command, but does not call `DevelopmentProjectStore` and does not mutate project DB.

## PCS-SEM-15 — no mutation by construction

Construct/validate/serialize/fingerprint ChangeSet against a database fixture and assert authoritative Phase 3–15 tables unchanged.

## PCS-SEM-16 — precondition round trip

Expected version/lifecycle guards survive deterministic serialization.

## PCS-SEM-17 — event causal references

Event intent referencing unknown local `changeUid` rejected.

## PCS-SEM-18 — ledger causal references

Ledger intent causal links deterministic and validated structurally.

## PCS-SEM-19 — ordering is semantic

Reordering change list changes fingerprint unless an explicit operation group contract says otherwise.

## PCS-SEM-20 — no persistence

Phase-17 implementation creates no SQLite tables/migration markers and reopen/restore baseline is unchanged.

No SQLite concurrency test is required for transient Phase 17. Race gates listed in section 22 become mandatory when Phase 18/27/28 introduces execution/commit persistence.

---

# 27. Adversarial gates

## PCS-ADV-01

Attempt to encode a fake `RawSqlChange` / `StatePatchChange` -> impossible by public type system or rejected by registry.

## PCS-ADV-02

Unknown World Pack change kind without registered typed codec -> reject, no generic fallback.

## PCS-ADV-03

Malicious payload attempts to provide table/column names through extensions -> extensions cannot become mutation authority.

## PCS-ADV-04

Caller supplies derived final balance/net worth -> no legal typed authoritative change kind.

## PCS-ADV-05

Conflicting exact-domain operations hidden under different change UIDs -> structural semantic target-key conflict detector catches obvious SET conflicts.

## PCS-ADV-06

Cross-domain operation reuses domain record UID as change UID -> allowed only as strings if coincidentally equal, but identities remain semantically separate and no automatic equivalence is inferred.

## PCS-ADV-07

ChangeSet claims commit/transaction status in warnings/extensions -> ignored/rejected as authority; no committed-state field exists.

## PCS-ADV-08

Synthetic ChangeSet reconstructed from legacy StatePatch -> migration produces zero canonical ChangeSet persistence/history.

---

# 28. Implementation file plan

Implementation remains blocked, but when Phase 16 is accepted, Phase 17 should likely add only contract/runtime-neutral files similar to Phase 16:

```text
app/src/main/java/com/rpgos/app/PlayerChangeSetModel.kt
app/src/main/java/com/rpgos/app/PlayerChangeSetRegistry.kt
app/src/main/java/com/rpgos/app/PlayerChangeSetCoreCodecs.kt
app/src/test/java/com/rpgos/app/PlayerChangeSetContractTest.kt
```

Optional split if taxonomy becomes large:

```text
PlayerChangeSetDomainChanges.kt
PlayerChangeSetEventLedgerIntents.kt
```

Should NOT require:

```text
Phase17Migration.kt
MigrationManager changes
CurrentSchema changes
SourceOfTruthRegistry DB table entries
PlayerChangeSetStore.kt
PlayerDomainEngine.kt
```

unless fresh accepted Phase-16/runtime evidence changes the contract before implementation begins.

---

# 29. Acceptance gates / Definition of Done

Phase 17 is implementation-ready only when Phase 16 is final accepted and its actual public types/codecs are re-read.

Phase-17 implementation is COMPLETE only if all are true:

1. universe-agnostic typed ChangeSet exists;
2. stable `changeSetUid` and individual `changeUid` identity semantics are deterministic;
3. explicit `sourceCommandUid` linkage exists;
4. no raw table/column/SQL/StatePatch mutation surface exists;
5. authoritative vs derived/runtime/ledger/event classifications are explicit;
6. no direct DB mutation API exists;
7. no Phase-17 persistence/schema migration is introduced without a new canonical decision;
8. deterministic serialization/fingerprint round trip passes;
9. duplicate/conflicting change gates pass;
10. Phase 3–16 regression tests remain green;
11. DevelopmentProject, Finance, Ownership, Equipment etc. remain their own authorities;
12. ChangeSet cannot mark itself committed;
13. Phase-18 handoff contract is sufficient without implementing orchestration;
14. report/CI evidence confirms no Phase 18+ scope creep.

---

# 30. Known risks / open questions

## R1 — Phase 16 not yet accepted

Fresh master contains active Phase-16 correction work (`WORK-20260810-067`). Phase 17 must re-read the final accepted Phase-16 SHA before coding. If PlayerCommand public names/equality/codec rules change, Phase-17 linkage must adapt rather than freezing this candidate.

## R2 — ChangeSet generation ownership

Roadmap puts PlayerDomainEngine at Phase 18. Therefore Phase 17 should define the ChangeSet type, not prematurely decide one giant resolver function. Generation policy belongs Phase 18.

## R3 — per-change expected versions

Some accepted domains expose versions differently. The generic guard vocabulary should stay small until Phase 18 maps each domain. Do not invent fake version fields for records that do not have them.

## R4 — event/ledger intent breadth

MASTER requires the ChangeSet surface to be able to carry events/ledgers, but full unified player ledgers and Event Store arrive later. Phase 17 should model typed intent envelopes without declaring storage schemas.

## R5 — deterministic UID allocation

Whether `changeSetUid`/`changeUid` are caller-generated random stable IDs, deterministic derivations, or engine allocations is an orchestration policy. Phase 17 requires stable uniqueness and conflict semantics, not a specific generator.

## R6 — multiple resolutions of same command

If stale state requires re-resolution, Phase 18 must define whether a new ChangeSet UID is allocated. This report recommends new identity whenever canonical content differs; never reuse the same UID with conflicting content.

---

# FINAL ARCHITECTURAL DECISION

```text
PlayerCommand
= typed intent

PlayerChangeSet
= typed transient proposed effects + commit guards + event/ledger intents

PlayerChangeSet
!= StatePatch
!= SQL
!= authoritative state
!= commit
!= transaction
!= event history
```

Required future path:

```text
PlayerCommand
-> Phase 18 PlayerDomainEngine / rule-mechanics resolution
-> PlayerChangeSet
-> validation
-> future TurnTransaction
-> authoritative domain writes + events + ledgers
-> COMMIT
```

Phase 17 must provide the proposal vocabulary and deterministic identity needed by that path without implementing the path itself.

# STATUS

```text
WORK ID: WORK-20260810-068
NEXT PHASE: Phase 17 — PlayerChangeSet contract
ARCHITECTURE: READY
IMPLEMENTATION: BLOCKED UNTIL PHASE 16 ACCEPTED
```
