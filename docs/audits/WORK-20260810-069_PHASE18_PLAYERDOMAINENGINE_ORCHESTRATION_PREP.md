# WORK-20260810-069 — Phase 18 PlayerDomainEngine Orchestration Prep

Status: READ-ONLY PREIMPLEMENTATION ARCHITECTURE / TEST PLAN

Work ID: `WORK-20260810-069`
Worker: `CHAT-4`
Repository: `piotreksmaga-art/rpg-os-android`
Fresh master before report write: `5378becc29795cb02f626641a58fe02538314ff0`
Accepted Phase-16 runtime candidate validated by 3×PASS: `2472879e8b1c360837fa45b7b7a356175c96a1db`
Phase-17 runtime candidate: **NONE PRESENT ON FRESH MASTER**
Phase-17 architecture source: `docs/audits/WORK-20260810-068_PHASE17_PLAYERCHANGESET_ARCHITECTURE.md`
Allowed write scope: this report only.

This report does **not** implement Phase 18, does not create `PlayerDomainEngine` runtime, does not modify production code, tests, schema, migrations, MASTER, Roadmap, Parallel Work Coordination, Phase-16 runtime, Phase-17 architecture, or any ongoing Phase-17 audit.

Current gate:

```text
PHASE 18 ARCHITECTURE / TEST PLAN: READY
PHASE 18 IMPLEMENTATION: BLOCKED UNTIL FINAL 3×PASS PHASE 17
PHASE 18 STATUS: NOT STARTED
```

---

# 1. Repository truth and dependency pinning

## 1.1 Fresh master

Fresh master immediately before this report resolved to:

```text
5378becc29795cb02f626641a58fe02538314ff0
CHAT-5 — Phase 17 adversarial audit blocked: no runtime candidate
```

That commit is report-only.

Immediately preceding fresh repository evidence independently states the same condition from CHAT-2:

```text
PHASE 17 SEMANTIC REVALIDATION: NOT RUN — NO PHASE-17 RUNTIME CANDIDATE
```

The last production/test runtime on master remains Phase 16:

```text
2472879e8b1c360837fa45b7b7a356175c96a1db
```

Therefore this report must **not** pretend an actual implemented `PlayerChangeSet` runtime exists.

## 1.2 Exact Phase-17 runtime candidate

Decision:

```text
PHASE-17 RUNTIME CANDIDATE = NONE
```

There is no valid SHA to pin for Phase-17 production/test runtime.

Consequences:

1. no Phase-18 implementation may begin;
2. no Phase-18 test may claim integration against a real Phase-17 runtime;
3. all PlayerChangeSet-facing signatures in this document are **preimplementation targets** derived from MASTER + WORK-068 and must be reconciled against the eventual Phase-17 accepted runtime before Phase-18 coding;
4. if the final Phase-17 API differs materially from WORK-068, Phase-17 runtime wins and this report must be updated before implementation.

## 1.3 Accepted Phase-16 boundary

Exact Phase-16 runtime `2472879...` has independent semantic, integrity and adversarial PASS reports on the same runtime SHA with exact CI #345 SUCCESS.

Relevant Phase-16 properties:

- `PlayerCommand` is immutable/transient intent;
- command carries `schemaVersion`, `commandUid`, `campaignUid`, `actor`, `commandKindUid`, typed payload, provenance, causation/correlation, requested order, typed preconditions and extensions;
- command kind resolves through a typed codec registry;
- canonical serialization/fingerprint is deterministic and fail-closed;
- no persistence authority exists;
- no `PlayerChangeSet`, `PlayerDomainEngine`, `WorldRuleProvider`, ProgressionEngine or command execution engine exists in Phase 16;
- construction/validation/serialization/fingerprint have zero authoritative mutation.

Phase 18 must preserve these semantics exactly.

## 1.4 Roadmap ordering

Current Roadmap remains:

```text
16. PlayerCommand contract
17. PlayerChangeSet contract
18. PlayerDomainEngine orchestration
19. WorldRuleProvider contract
20. ProgressionEngine + Progression Ledger
21. Diminishing Returns + passive progression hooks
22. Player Invariant Validator + No-Retrogression
```

Phase 18 is therefore the next orchestration layer **after** accepted Phase 17, not a substitute for Phase 17, Phase 19, Phase 20, Phase 22 or Phase 27.

---

# 2. Canonical architecture constraints from MASTER

MASTER defines one legal mutation path:

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

For Player Domain:

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

MASTER also requires:

- `PlayerDomainEngine` is the single Player Domain entrypoint for authoritative player-change requests;
- AI does not directly mutate player stats/resources/money/inventory/skills/techniques/ownership/permanent traits;
- World Pack supplies world-specific definitions/rules but does not duplicate Core transaction/event/memory/economy/snapshot infrastructure;
- if randomness is used, result or RNG seed required for replay must be retained;
- COMMIT is the truth boundary;
- ChangeSet is still a proposal before COMMIT.

The design below preserves that ordering without prematurely implementing Phase 19/20/22/27/28.

---

# 3. Core decision — what PlayerDomainEngine is and is not

## 3.1 PlayerDomainEngine is an orchestrator

It coordinates pure/read-only resolution components.

It is responsible for:

```text
validate command envelope already typed
-> bind immutable resolution scope
-> resolve command handler by typed kind
-> obtain immutable read evidence
-> invoke core rule pipeline
-> invoke WorldRuleProvider through a narrow rule port
-> invoke selected mechanics
-> invoke invariant validation through a narrow validator port
-> assemble/finalize immutable PlayerChangeSet
-> return Resolved or Rejected
```

It is **not** responsible for:

```text
SQL
repository writes
store writes
StatePatch
transaction BEGIN/COMMIT/ROLLBACK
ledger persistence
event persistence
snapshot write
idempotency history
retry state
Phase-28 replay status
world-specific switch logic
AI narration
```

## 3.2 No generic mutation engine

Critical invariant:

```text
PlayerDomainEngine.resolve(command)
NEVER means
PlayerDomainEngine.apply(command)
```

The public Phase-18 action verb should semantically be `resolve`, not `executeAndCommit`, `apply`, `mutate`, `persist`, `save`, or equivalent.

The only successful output is a **proposal object** suitable for later TurnTransaction.

## 3.3 No God Engine

The engine must not know detailed mechanics for every command kind.

It owns orchestration sequence, not domain algorithms.

Correct shape:

```text
PlayerDomainEngine
  -> CommandResolverRegistry
  -> PlayerResolutionContextReader
  -> RulePipeline
  -> WorldRuleProviderPort
  -> MechanicsResolver
  -> InvariantValidationPort
  -> PlayerChangeSetAssembler
```

Incorrect shape:

```text
PlayerDomainEngine {
  when(commandKind) {
    TRAIN -> calculateTraining(...)
    TRANSFER_FUNDS -> writeFinance(...)
    EQUIP -> updateInventoryAndEquipment(...)
    ... 200 branches ...
  }
}
```

---

# 4. Q1 — How PlayerDomainEngine accepts immutable PlayerCommand

Recommended entrypoint:

```kotlin
interface PlayerDomainEngine {
    fun resolve(
        command: PlayerCommand<out PlayerCommandPayload>,
        execution: ResolutionExecutionContext
    ): PlayerResolutionResult
}
```

`command` is accepted by reference as an immutable domain value.

The engine must not:

- rewrite `commandUid`;
- replace `campaignUid` with currently active campaign;
- replace actor with ActivePlayer;
- normalize payload to a different semantic command;
- add/remove preconditions;
- mutate caller-owned nested lists;
- mutate extensions or provenance.

At the beginning and end of resolution, semantic command fingerprint should remain identical when the accepted Phase-16 registry is used.

Recommended test oracle:

```text
fingerprintBefore == fingerprintAfter
```

The engine may derive an internal read-only `ResolvedCommandDescriptor`, but it must preserve source identity and cannot become a second command contract.

---

# 5. Q2 — Typed command recognition without hardcoded World Pack switch

## 5.1 Two registries, two responsibilities

Phase 16 already has:

```text
commandKindUid -> typed codec / structural validator
```

Phase 18 should introduce a separate orchestration registry:

```text
commandKindUid -> typed command resolver descriptor
```

Recommended shape:

```kotlin
interface PlayerCommandResolver<P : PlayerCommandPayload> {
    val commandKindUid: String
    val payloadType: KClass<P>

    fun plan(
        command: PlayerCommand<P>,
        context: PlayerResolutionContext,
        rules: ResolvedRuleSet,
        entropy: ResolutionEntropy
    ): ResolutionDraft
}
```

or equivalent typed generic form.

## 5.2 Registry must be Core-domain oriented

Core registry contains handlers for universal command semantics such as:

- training intent;
- resource use;
- skill practice;
- technique use;
- inventory transfer;
- equipment request;
- ownership transfer;
- finance transfer;
- project lifecycle/work requests.

It must **not** contain:

```text
if worldPack == NARUTO
if campaign contains chakra
if Bleach then reiatsu
NarutoTrainHandler
BleachTrainHandler
```

World-specific interpretation is delegated to WorldRuleProvider.

## 5.3 Fail closed

Unknown command kind at orchestration registry:

```text
ResolutionRejected(UNKNOWN_COMMAND_RESOLVER)
```

No fallback to:

- StatePatch;
- generic JSON handler;
- reflection-based dynamic invocation;
- arbitrary database mutation;
- AI interpretation.

---

# 6. Q3 — How mechanics/rules are selected

Recommended separation:

```text
command kind
-> core resolver descriptor
-> required evidence selectors
-> core rule stages
-> WorldRuleProvider contributions
-> mechanics capability selection
```

Do not select mechanics by World Pack name.

Select mechanics by stable capability/rule UIDs.

Example conceptual flow:

```text
TRAIN command
-> TrainCommandResolver
-> requests capability `RPGOS-MECHANIC:TRAINING_RESOLUTION`
-> WorldRuleProvider supplies world-specific training definitions/modifiers/constraints
-> MechanicsRegistry resolves `TRAINING_RESOLUTION`
-> mechanic computes ResolutionDraft
```

Technique use:

```text
USE_TECHNIQUE
-> UseTechniqueResolver
-> requests technique definition + actor resources + target context
-> WorldRuleProvider supplies world-specific legality/cost/rank/canon rules
-> TechniqueMechanicsResolver computes proposed consequences
```

Finance transfer may require no world-specific mechanic beyond currency/organization policy and therefore the provider may return neutral/no-op world constraints.

Selection must be deterministic for the same:

```text
command + immutable context + rule provider version + mechanics version + entropy evidence
```

---

# 7. Q4 — Exact WorldRuleProvider boundary

Phase 19 owns the full canonical provider contract. Phase 18 must only define the minimal port it needs, without implementing world rules prematurely.

Recommended future port:

```kotlin
interface WorldRuleProviderPort {
    fun resolveRules(request: WorldRuleRequest): WorldRuleDecision
}
```

`WorldRuleRequest` contains only immutable semantic data:

```text
campaignUid
worldPackUid/version
commandKindUid
actorRef
stable target refs
resolved read evidence references/snapshots
requested effective order
core rule facts required by this command
```

It does not contain:

- SQLiteDatabase;
- writable repository/store;
- StatePatchEngine;
- TurnTransaction;
- mutable campaign context;
- function callback to commit.

`WorldRuleDecision` may provide:

```text
allow / deny / constraints
world-specific definitions
required prerequisites
mechanics modifiers
rule evidence
warnings
worldRuleProviderVersion
```

It must not return committed mutations.

Exact position:

```text
Core structural/reference/context checks
-> Core Rule Pipeline
-> WorldRuleProviderPort
-> Mechanics
```

WorldRuleProvider refines legality/parameters before mechanics calculates final proposed effects.

A post-mechanics world invariant check may eventually be needed, but should be modeled as a validator contribution or second rule validation stage, not as a World Pack commit hook.

---

# 8. Q5 — Who creates PlayerChangeSet

To preserve MASTER ordering:

```text
Mechanics
-> ResolutionDraft
-> InvariantValidator
-> PlayerChangeSetAssembler
-> PlayerChangeSet
```

The mechanics should **not** construct the final canonical aggregate directly if that would bypass cross-domain validation.

Recommended intermediate:

```kotlin
sealed interface ProposedDomainEffect

data class ResolutionDraft(
    val effects: List<ProposedDomainEffect>,
    val eventIntents: List<ProposedEventIntent>,
    val ledgerIntents: List<ProposedLedgerIntent>,
    val evidence: List<ResolutionEvidence>,
    val warnings: List<ResolutionWarning>,
    val deterministicEvidence: DeterministicResolutionEvidence?
)
```

`ResolutionDraft` is an **internal Phase-18 orchestration object**, not a new persistence contract.

Then:

```text
InvariantValidationPort.validate(command, context, rules, draft)
-> ValidationAccepted(normalized? NO)
-> PlayerChangeSetAssembler.assemble(...)
```

Important: validator should not silently rewrite effects. It accepts or rejects. If repair/recalculation is needed, orchestration re-runs an explicit mechanic/rule step.

Final `PlayerChangeSet` is assembled from already validated typed effects while preserving:

- source `commandUid`;
- campaign UID;
- actor;
- provenance;
- expected versions/preconditions;
- event/ledger intents;
- deterministic resolution evidence;
- warnings.

When actual Phase-17 runtime exists, the assembler signature must exactly target its accepted constructor/factory and validation semantics.

---

# 9. Q6 — Who can validate PlayerChangeSet / proposed effects

Validation responsibilities must remain layered.

## Layer A — Phase-16 command structural validation

Owner:

```text
PlayerCommandKindRegistry
```

Purpose:

- schema/kind/payload shape;
- strict serialization semantics;
- no domain legality claim.

## Layer B — Phase-17 ChangeSet structural validation

Owner:

```text
accepted Phase-17 ChangeSet validator/registry
```

Purpose:

- typed effect shape;
- stable identities;
- duplicate/conflict structural rules;
- serialization/identity semantics if accepted runtime provides them.

## Layer C — Phase-18 orchestration reference/scope validation

Owner:

```text
PlayerDomainEngine boundary services
```

Purpose:

- source command campaign matches loaded resolution context;
- actor/player scope matches context;
- target refs are resolved from same campaign;
- required evidence exists;
- resolver and mechanic capability are registered.

## Layer D — domain/world invariant validation

Owner:

```text
InvariantValidationPort
+ domain-specific validators
+ future WorldRuleProvider validation contribution
```

Purpose:

- money conservation;
- inventory ownership/location constraints;
- equipment compatibility;
- project lifecycle legality;
- stat/resource invariants;
- no-retrogression when Phase 22 exists;
- world-specific rules.

## Layer E — commit-time validation

Owner:

```text
TurnTransaction / authoritative stores
```

Purpose:

- versions still current;
- constraints still hold;
- no concurrent write invalidated proposal;
- atomic commit is still legal.

Phase 18 may not replace Layer E.

---

# 10. Q7 — Who may NOT commit PlayerChangeSet

Explicit negative authority matrix:

```text
PlayerDomainEngine             MUST NOT COMMIT
PlayerCommandResolver          MUST NOT COMMIT
RulePipeline                   MUST NOT COMMIT
WorldRuleProvider              MUST NOT COMMIT
MechanicsResolver              MUST NOT COMMIT
InvariantValidator             MUST NOT COMMIT
PlayerChangeSetAssembler       MUST NOT COMMIT
PlayerChangeSet itself         MUST NOT COMMIT
AI / UI                        MUST NOT COMMIT
StatePatchEngine fallback      MUST NOT COMMIT Player Domain ChangeSet
```

Only future transaction authority may make the proposal reality.

No interface in Phase 18 should expose a method named or semantically equivalent to:

```text
save
insert
update
delete
apply
persist
commit
transaction
write
recordTruth
appendLedger
```

except on later explicitly transaction-owned adapters outside Phase-18 resolution scope.

---

# 11. Q8 — Preventing direct DB mutation from mechanics

This is the highest-risk implementation boundary.

## 11.1 Capability denial by type

Mechanics receives only immutable/read-only values.

Recommended:

```kotlin
interface PlayerMechanic<P : PlayerCommandPayload> {
    fun resolve(
        command: PlayerCommand<P>,
        context: PlayerResolutionContext,
        rules: ResolvedRuleSet,
        entropy: ResolutionEntropy
    ): ResolutionDraft
}
```

`PlayerResolutionContext` must contain immutable snapshots/value objects, not stores.

Forbidden constructor dependencies for mechanics:

- `SQLiteDatabase`;
- `LocalGameStore`;
- `UnifiedGameRepository` if it exposes writers;
- `StatResourceStore`;
- `SkillStore`;
- `TechniqueStore`;
- `InventoryStore`;
- `EquipmentStore`;
- `OwnershipStore`;
- `FinancialStore`;
- `AssetLiabilityStore`;
- `DevelopmentProjectStore`;
- `StatePatchEngine`;
- transaction manager.

## 11.2 Read model boundary

Read acquisition occurs through a narrow reader:

```kotlin
interface PlayerResolutionContextReader {
    fun read(request: ResolutionReadRequest): PlayerResolutionContext
}
```

Its production implementation may internally query repositories/SQLite read paths, but the returned context is detached immutable data.

The engine and mechanics never receive the writer object used by the reader.

## 11.3 Static dependency gate

Phase-18 production packages should be checked for forbidden imports/references to writer types.

## 11.4 Runtime mutation fixture

For each representative command family:

```text
snapshot authoritative tables
-> resolve(command)
-> compare authoritative tables byte/row/value-equivalent
=> unchanged
```

This test must include rejection paths and exception paths.

---

# 12. Q9 — Zero side effects before TurnTransaction

Definition:

```text
Resolution may READ authoritative state.
Resolution may ALLOCATE transient immutable objects.
Resolution may NOT change campaign reality.
```

Forbidden side effects before transaction:

- DB write;
- ledger append;
- event append;
- project work insertion;
- runtime HP update;
- ownership mutation;
- resource decrement;
- analytics counters that are campaign-authoritative;
- file write that becomes campaign truth;
- cache mutation if cache affects resolution semantics for same input;
- random global state mutation;
- active player/campaign rebinding.

Non-authoritative diagnostics may be emitted only if they cannot influence campaign semantics, but Phase-18 contract should prefer returned diagnostics over mutable logging state during deterministic tests.

Strong contract:

```text
resolve() is observationally pure with respect to campaign-authoritative state.
```

---

# 13. Q10 — Provenance and evidence propagation

Provenance must be additive, not fabricated.

Recommended chain:

```text
CommandProvenance
+ ResolverEvidence
+ WorldRuleEvidence
+ MechanicsEvidence
+ InvariantValidationEvidence
+ DeterministicResolutionEvidence
-> ChangeSetProvenance
```

Each evidence item should be typed/versioned and carry stable source identity where available:

```text
evidenceKindUid
sourceUid?
sourceVersion?
ruleUid?
mechanicUid?
inputRef(s)?
resultCode/value when semantically required
```

Do not copy narrative text into provenance and treat it as verified fact.

No component may invent:

- event UID that does not exist;
- project work evidence that does not exist;
- ownership record evidence that does not exist;
- source command other than actual source command UID.

Evidence should reference resolved read facts by stable UID/version when possible.

For eventual event/ledger intents, provenance says why they are proposed, not that they are committed.

---

# 14. Q11 — Deterministic RNG/result evidence without Phase-28 redesign

Phase 18 needs deterministic resolution now, but must not implement persisted idempotency/replay state owned by Phase 28.

## 14.1 No hidden random source

Forbidden:

```text
Random.Default
System.currentTimeMillis seed
UUID-random result semantics
thread-local RNG
Math.random
provider-internal mutable RNG
```

inside mechanics resolution.

## 14.2 Explicit entropy input

Recommended transient contract:

```kotlin
interface ResolutionEntropy {
    val algorithmUid: String
    val algorithmVersion: Int
    val seedEvidence: String
    fun nextLong(bound: Long, drawUid: String): Long
}
```

or a deterministic precomputed draw bundle:

```text
ResolutionEntropyBundle
- algorithmUid
- algorithmVersion
- seed
- ordered draw requests/results
```

The exact representation may evolve, but mechanics must receive entropy as an explicit dependency.

## 14.3 Phase-18 responsibility

Phase 18 records enough **transient result evidence** in the ChangeSet proposal so two resolutions with the same explicit inputs can be reproduced.

Suggested evidence:

```text
rngAlgorithmUid
rngAlgorithmVersion
seed or deterministic seed evidence
draw UID -> result for semantically relevant draws
mechanicsVersion
```

This does **not** create:

- persisted command execution history;
- retry status;
- already-committed detection;
- transaction identity;
- Phase-28 replay store.

## 14.4 Determinism rule

```text
same canonical command
+ same immutable resolution context
+ same rule provider version/output
+ same mechanics version
+ same entropy bundle
=> same canonical ResolutionDraft / PlayerChangeSet
```

If any version/evidence input differs, different result is allowed and must be traceable.

---

# 15. Q12 — Campaign and player isolation

## 15.1 Campaign binding

The engine must never resolve using `ActiveCampaignRef` as replacement for `command.campaignUid`.

Correct:

```text
command.campaignUid
-> exact resolution context request
-> every read scoped to same campaign UID
```

If active UI campaign changes concurrently, resolution remains bound to command campaign.

Cross-campaign target resolution must fail closed.

## 15.2 Player binding

Phase-16 command actor is generic. Phase 18 needs explicit resolution subject.

Recommended context includes:

```text
campaignUid
subjectPlayerUid
actorRef
```

For direct player commands, subject player may be actor if actor kind is PLAYER.

For world/GM/system actions affecting a player, actor may differ from subject player.

Therefore do **not** derive subject player universally as `actor.actorUid`.

A typed command resolver must define how subject player refs are obtained and validate they belong to the command campaign.

## 15.3 No active-player rebinding

`ActivePlayerRef` may be a UI convenience for command creation, not Phase-18 resolution authority.

Resolution must use explicit stable IDs already bound into command/typed targets or an explicit subject binding resolved once from command semantics.

---

# 16. Q13 — Command rejection vs transaction failure

These are separate domains.

Recommended Phase-18 result:

```kotlin
sealed interface PlayerResolutionResult

data class Resolved(
    val changeSet: PlayerChangeSet,
    val diagnostics: ResolutionDiagnostics
) : PlayerResolutionResult

data class Rejected(
    val rejection: CommandRejection,
    val diagnostics: ResolutionDiagnostics
) : PlayerResolutionResult
```

`Rejected` means no valid proposal was produced.

Examples:

- unknown resolver;
- campaign mismatch;
- unsupported actor/subject;
- missing required target;
- world rule DENY;
- insufficient prerequisite discovered during resolution;
- mechanic-level impossible action;
- invariant validation fail.

There is no transaction because there is no ChangeSet to commit.

Future transaction result is different:

```text
TransactionCommitted
TransactionRejected/Stale
TransactionRolledBack
TransactionFailed
```

Examples:

- expected version changed after resolution;
- DB constraint fails;
- concurrent funds transfer changed balance;
- write error;
- atomic commit rollback.

Phase 18 must never map transaction failure to `CommandRejected`, because command may have been valid when resolved.

Likewise command rejection must never allocate a fake transaction failure.

---

# 17. Q14 — Minimal interfaces

The minimum recommended surface is intentionally small.

## 17.1 PlayerDomainEngine

```kotlin
interface PlayerDomainEngine {
    fun resolve(
        command: PlayerCommand<out PlayerCommandPayload>,
        execution: ResolutionExecutionContext
    ): PlayerResolutionResult
}
```

## 17.2 Command resolver registry

```kotlin
interface PlayerCommandResolverRegistry {
    fun resolverFor(commandKindUid: String): PlayerCommandResolver<*>?
}
```

Registry is immutable after construction for Phase-18 MVP.

## 17.3 PlayerCommandResolver

```kotlin
interface PlayerCommandResolver<P : PlayerCommandPayload> {
    val commandKindUid: String
    val payloadType: KClass<P>
    val requiredMechanicUid: String

    fun requiredReads(command: PlayerCommand<P>): ResolutionReadRequest

    fun resolveDraft(
        command: PlayerCommand<P>,
        context: PlayerResolutionContext,
        rules: ResolvedRuleSet,
        mechanic: PlayerMechanic,
        entropy: ResolutionEntropy
    ): ResolutionDraft
}
```

Alternative decomposition may move `resolveDraft` fully into mechanic, but avoid duplicating orchestration responsibilities.

## 17.4 Context reader

```kotlin
interface PlayerResolutionContextReader {
    fun read(request: ResolutionReadRequest): PlayerResolutionContext
}
```

Read-only contract only.

## 17.5 Rule pipeline

```kotlin
interface PlayerRulePipeline {
    fun evaluate(request: RuleEvaluationRequest): RulePipelineResult
}
```

Pure/read-only.

## 17.6 WorldRuleProviderPort

```kotlin
interface WorldRuleProviderPort {
    fun resolveRules(request: WorldRuleRequest): WorldRuleDecision
}
```

Phase 18 may depend on this port; Phase 19 implements canonical provider behavior.

Until Phase 19 exists, Phase-18 tests should use deterministic fake providers and **must not** smuggle real world rules into Core.

## 17.7 Mechanics registry

```kotlin
interface PlayerMechanicsRegistry {
    fun mechanicFor(mechanicUid: String): PlayerMechanic?
}
```

## 17.8 Mechanic

```kotlin
interface PlayerMechanic {
    val mechanicUid: String
    val mechanicVersion: String

    fun resolve(request: MechanicsRequest): ResolutionDraft
}
```

No writer dependencies.

## 17.9 Invariant validation port

```kotlin
interface PlayerInvariantValidationPort {
    fun validate(request: InvariantValidationRequest): InvariantValidationResult
}
```

Phase 18 owns orchestration of the port. Phase 22 later supplies the full global No-Retrogression validator contract.

Phase-18 MVP can use domain validators already accepted plus deterministic test doubles, without claiming Phase 22 complete.

## 17.10 ChangeSet assembler

```kotlin
interface PlayerChangeSetAssembler {
    fun assemble(request: ChangeSetAssemblyRequest): PlayerChangeSet
}
```

Pure. No stores. No DB. No commit.

## 17.11 Entropy

```kotlin
interface ResolutionEntropyFactory {
    fun create(evidence: ResolutionEntropyInput): ResolutionEntropy
}
```

Factory itself must be deterministic from explicit input.

---

# 18. Q15 — Avoiding a God Engine

Use five rules.

## Rule 1 — Engine owns sequence, not domain algorithms

`PlayerDomainEngine` should be a small orchestration shell.

## Rule 2 — Resolver owns command-specific adaptation

Train resolver knows how `TrainCommandPayload` maps to training inputs, but not how Naruto chakra works.

## Rule 3 — WorldRuleProvider owns world-specific rules

World Pack logic is behind a narrow provider port.

## Rule 4 — Mechanics owns deterministic calculations

Mechanic returns proposed effects/evidence, not writes.

## Rule 5 — Validators own acceptance/rejection

Validation is explicit and cannot be bypassed by resolver/mechanic.

A practical source-size heuristic is useful but not authoritative: if PlayerDomainEngine begins accumulating domain formulas, table names, World Pack names, store dependencies or large per-kind branching, architecture has failed.

---

# 19. End-to-end future pipeline

Recommended Phase-18 resolution flow:

```text
INPUT: immutable PlayerCommand + explicit ResolutionExecutionContext

1. PlayerCommand structural validation
2. locate typed resolver by commandKindUid
3. verify resolver payload type matches command payload type
4. bind campaign / subject / actor resolution scope
5. compute bounded ResolutionReadRequest
6. read immutable PlayerResolutionContext
7. verify context campaign/player isolation
8. evaluate Core Rule Pipeline
9. call WorldRuleProviderPort
10. merge rule decisions into immutable ResolvedRuleSet
11. resolve mechanic by stable mechanic capability UID
12. obtain explicit deterministic entropy
13. mechanic computes ResolutionDraft only
14. invariant validation over command + context + rules + draft
15. if rejected -> PlayerResolutionResult.Rejected, no ChangeSet
16. assemble immutable Phase-17 PlayerChangeSet
17. run Phase-17 structural/canonical validation on resulting ChangeSet
18. return PlayerResolutionResult.Resolved(changeSet)

STOP.

NO DB WRITE.
NO TRANSACTION.
NO COMMIT.
```

Future Phase-27+ continuation:

```text
Resolved(changeSet)
-> TurnTransaction
-> commit-time validation
-> events + ledgers + authoritative state
-> COMMIT
```

Phase 18 does not implement this continuation.

---

# 20. Rule pipeline structure

Avoid one generic validator list with hidden ordering.

Recommended explicit stages:

```text
A. structural command gate (Phase 16)
B. resolution-scope gate
C. reference/evidence acquisition
D. core pre-rule gate
E. WorldRuleProvider rule contribution
F. mechanics resolution
G. cross-domain invariant validation
H. ChangeSet assembly
I. Phase-17 ChangeSet structural validation
```

Each stage returns a typed result:

```text
CONTINUE
REJECT(code, evidence)
ERROR(internalFailure)
```

Internal engine errors are neither domain rejection nor transaction failure.

Example:

```text
unknown mechanic registry entry
=> INTERNAL_CONFIGURATION_ERROR
```

not:

```text
PLAYER_ACTION_ILLEGAL
```

This distinction matters for diagnostics and replay.

---

# 21. Duplicate validation risk

Validation duplication is dangerous because different layers can diverge.

Policy:

## Structural facts

Validate exactly once at their canonical contract layer and optionally assert again defensively without changing semantics.

Example:

```text
PlayerCommand kind/payload strictness -> Phase 16 registry
```

Engine calls the canonical validator; it does not reimplement strict JSON rules.

## Domain invariants

Authoritative domain stores may already enforce some invariants. Phase 18 may prevalidate for deterministic rejection, but commit-time authority must still enforce them.

Never remove DB/domain constraint because Phase-18 prevalidation exists.

## World rules

One provider decision contract. Do not duplicate Naruto/Bleach legality both in resolver and provider.

## Cross-domain invariants

One explicit `PlayerInvariantValidationPort` orchestration stage.

If different domains expose validators, aggregate them through the port with deterministic order and stable codes.

---

# 22. Hidden mutable state risk

Forbidden mutable state inside shared resolution components:

- last command;
- cached current player that changes with UI;
- mutable campaign pointer;
- internal RNG cursor shared across resolutions;
- mutable rules list modified by World Pack registration during resolution;
- mutable handler registry after engine construction;
- reusable mutable `ResolutionDraft` builder shared across threads;
- static/global warning/evidence list;
- store-backed entity objects that lazy-write.

Preferred:

- registries immutable after startup;
- per-resolution context local;
- immutable copies of collections;
- entropy instance local to one resolution;
- provider output immutable;
- mechanics stateless or pure from immutable configuration.

---

# 23. Concurrency implications

Phase 18 itself does not commit, but resolution can race with authoritative mutations made elsewhere.

Correct model:

```text
read snapshot/version evidence
-> resolve proposal
-> carry expected versions/preconditions into ChangeSet
-> later TurnTransaction revalidates
```

Phase 18 must not hold DB write transaction open while AI/mechanics resolution occurs.

It may use a bounded read snapshot if repository supports it, but this is not a commit lock.

Concurrent change after resolution:

```text
not a Phase-18 corruption
```

It becomes a later transaction stale-precondition outcome.

Do not solve this by letting mechanics lock tables or write placeholder rows.

---

# 24. StatePatch boundary

Hard negative rule:

```text
PlayerCommand
-> PlayerDomainEngine
-> StatePatch
```

is forbidden.

Also forbidden:

```text
PlayerChangeSet
-> StatePatchEngine.apply(...)
```

Phase 18 cannot create a bridge that serializes typed effects into legacy table/op/key/value operations.

Legacy StatePatch may continue to exist for older non-migrated paths until Phase 26, but it is not a fallback for Player Domain orchestration.

Unknown typed command must reject, not route to StatePatch.

---

# 25. World Pack infrastructure boundary

World Pack may provide:

- definitions;
- canon constraints;
- ranks;
- energy-system rules;
- technique legality;
- bloodline/racial/evolution rules;
- world-specific mechanics modifiers;
- stable rule/provider version.

World Pack may **not** provide its own:

- SQLite transaction manager;
- event store commit infrastructure;
- player-state write repository bypass;
- idempotency store;
- TurnTransaction implementation;
- StatePatch mutation fallback;
- duplicate Finance/Ownership/Inventory transaction framework.

If Naruto/Bleach requires special atomicity, it expresses extra proposed effects/preconditions, and Core transaction infrastructure later commits them atomically.

---

# 26. Required deterministic error taxonomy

Recommended categories:

```text
STRUCTURAL_REJECTION
SCOPE_REJECTION
REFERENCE_REJECTION
RULE_REJECTION
MECHANICS_REJECTION
INVARIANT_REJECTION
INTERNAL_CONFIGURATION_ERROR
INTERNAL_RESOLUTION_ERROR
```

Each should expose stable code UID/string and typed evidence refs.

Avoid exception-message parsing as domain result.

Exceptions may indicate programmer/system failure; expected illegal player action should return typed rejection.

No Phase-18 category called:

```text
TRANSACTION_FAILED
COMMIT_FAILED
ALREADY_COMMITTED
```

because those belong later.

---

# 27. Phase-18 test architecture

Tests should be separated into:

```text
A. pure unit orchestration tests
B. deterministic fake-provider/mechanic tests
C. no-mutation repository integration fixtures
D. campaign/player isolation integration tests
E. Phase 3–17 regression suite
F. static/architecture dependency guards
```

When Phase-17 runtime finally exists, every ChangeSet assertion must target the exact accepted Phase-17 API rather than the conceptual shape in WORK-068.

---

# 28. Required P18 test gates

## P18-ORCH-01 — command dispatch

Purpose: known typed command reaches exactly the registered typed resolver.

Fixture:

- canonical Phase-16 `TrainCommandPayload`;
- resolver registry with `TRAIN -> TrainResolverSpy`;
- second unrelated resolver spy.

Expected:

```text
TrainResolver called exactly once
unrelated resolver never called
payload instance/type preserved
command fingerprint unchanged
```

Failure if engine performs world-name switch or generic fallback.

## P18-ORCH-02 — unknown command fail-closed

Construct a structurally representable command kind not present in orchestration resolver registry through a test registry boundary.

Expected:

```text
Rejected(UNKNOWN_COMMAND_RESOLVER)
zero mechanics calls
zero WorldRuleProvider calls unless architecture intentionally validates provider before dispatch (not recommended)
zero ChangeSet
zero DB mutation
```

No StatePatch/AI/generic handler fallback.

## P18-ORCH-03 — world-agnostic dispatch

Use identical Core command shape with two fake WorldRuleProviders:

```text
WORLD_A
WORLD_B
```

Resolver selected must be identical.

Only rule/mechanics parameters may differ through provider output.

Static assertion: Core resolver registry contains no `Naruto`, `Bleach`, chakra/reiatsu-specific dispatch branch.

## P18-ORCH-04 — zero DB mutation during resolution

For representative command families:

- stats/resource;
- skill/technique;
- inventory/equipment;
- finance/ownership/assets;
- DevelopmentProject.

Take authoritative DB/table/value snapshots before `resolve()` and compare after.

Run for:

- success;
- domain rejection;
- provider rejection;
- mechanics exception;
- validator rejection.

Expected all unchanged.

## P18-ORCH-05 — immutable command preserved

Before/after:

```text
Phase16Fingerprint(command) identical
command equals original semantic value
nested refs/preconditions/extensions unchanged
```

Also mutate caller-owned lists used during command construction if Phase-16 model permits aliasing and prove engine does not alter them.

## P18-ORCH-06 — immutable ChangeSet output

Once Phase 17 exists:

- returned ChangeSet is accepted Phase-17 immutable aggregate;
- no mutable builder/store callback escapes;
- attempts to mutate source lists after resolution cannot alter output;
- output cannot self-commit.

Use Phase-17 fingerprint/canonical identity if accepted runtime defines it.

## P18-ORCH-07 — deterministic resolution

Given exact same:

```text
command
context
provider output/version
mechanic version
entropy bundle
```

resolve N times, including concurrent calls.

Expected:

```text
same result category
same rejection code OR same canonical ChangeSet
same evidence/warning order
same deterministic RNG evidence
```

No wall-clock/thread-order dependency.

## P18-ORCH-08 — campaign isolation

Command bound to campaign C while ActiveCampaignRef is D.

Context reader spy must receive C.

Attempt target from D.

Expected deterministic rejection.

No reads/writes silently rebound to D.

## P18-ORCH-09 — player isolation

Campaign contains player P1 and P2.

Resolve command targeting P1 while ActivePlayer is P2.

Expected:

- only P1 resolution context used;
- no P2 state included unless explicitly required as counterparty/target;
- P2 mutation absent from ChangeSet unless command semantics explicitly target P2;
- unauthorized/cross-subject ref rejected deterministically.

## P18-ORCH-10 — WorldRuleProvider boundary

Provider spy proves exact call location:

```text
resolver/context acquired
core rules evaluated
provider called
mechanics called afterward
```

Provider receives immutable semantic request and no writer capability.

Provider DENY -> no mechanics call and no ChangeSet.

Provider output alone cannot commit.

## P18-ORCH-11 — mechanics cannot commit

Architecture/static test verifies mechanic constructor/interface exposes no DB/store/transaction writer.

Runtime hostile mechanic attempts to access only provided capabilities and has no commit method available.

Authoritative snapshot unchanged after mechanic invocation.

## P18-ORCH-12 — validator cannot bypass transaction

Validator spy returns ACCEPT/REJECT only.

No validator API contains persistence callback.

Hostile/throwing validator cannot mutate authoritative tables.

Accepted validation produces ChangeSet proposal only.

## P18-ORCH-13 — no StatePatch fallback

Repository/static search around Phase-18 implementation for:

```text
StatePatch
StatePatchEngine
raw table/op/key/value
SQLiteDatabase writer
```

must find no orchestration dependency.

Unknown command and unsupported change must reject, never patch.

## P18-ORCH-14 — Phase 3–17 regression

Run all accepted tests from Phases 3–17.

Additional regression assertions:

- Phase-16 command canonical encoding unchanged;
- Phase-16 command public codec guards unchanged;
- Phase-17 ChangeSet canonical/identity/conflict semantics unchanged;
- existing domain stores retain their authoritative constraints;
- no source-of-truth registry relaxation;
- no migration/schema changes unless independently required and approved (Phase 18 architecture itself requires none).

---

# 29. Additional recommended gates

## P18-ORCH-15 — resolver type mismatch

Registry claims command kind K but resolver payload type differs.

Expected fail closed during engine initialization/registry construction or deterministic resolution configuration error.

Never unchecked cast crash after partial work.

## P18-ORCH-16 — duplicate resolver registration

Two resolvers for same command kind.

Expected deterministic registry construction failure.

Never last-wins.

## P18-ORCH-17 — mechanic missing

Resolver requests mechanic capability not registered.

Expected internal configuration error, no ChangeSet, no mutation.

## P18-ORCH-18 — provider version evidence

Same command/context with different provider version must produce traceably distinct evidence/fingerprint when output semantics differ.

Provider version must be preserved in ChangeSet provenance/evidence.

## P18-ORCH-19 — RNG draw order

Mechanic declares stable draw UIDs.

Repeated run with same entropy bundle returns same draw evidence.

Changing unrelated thread scheduling does not change result.

## P18-ORCH-20 — rejection vs transaction namespace

Resolution rejection result cannot contain transaction UID/commit status.

Future fake transaction failure is never converted into `CommandRejected` by engine.

## P18-ORCH-21 — read-only context alias attack

Provide mutable source objects from fake reader, mutate them after reader returns.

Engine/mechanics must operate on immutable copied context or otherwise prove detached immutability.

## P18-ORCH-22 — active identity race

During resolution, switch active campaign/player repeatedly in another thread.

Command-bound campaign/player resolution remains unchanged.

## P18-ORCH-23 — event/ledger proposal authority

Resolved ChangeSet may include event/ledger intents according to Phase-17 contract, but authoritative event/ledger stores remain unchanged until future transaction.

## P18-ORCH-24 — deterministic validation ordering

Multiple invariant violations exist.

Returned rejection/warning ordering is deterministic and documented.

No HashMap iteration-order dependency.

## P18-ORCH-25 — provider cannot install handlers at runtime

During resolution provider tries to influence resolver/mechanics registry registration.

Registries remain immutable.

---

# 30. Risk analysis

## RISK-A — PlayerDomainEngine becomes generic mutation engine

Failure smell:

```text
engine accepts generic changes and applies them
engine imports writer stores
engine has save/apply/commit method
```

Mitigation:

- `resolve()` only;
- output proposal only;
- no writer dependencies;
- P18-ORCH-04/11/12/13.

Severity: CRITICAL.

## RISK-B — mechanics write directly to stores

Failure smell:

```text
TrainingMechanic(statStore)
FinanceMechanic(financialStore)
ProjectMechanic(projectStore)
```

Mitigation:

- mechanics receive immutable `PlayerResolutionContext`;
- writer types forbidden by dependency gate;
- no-mutation integration fixtures.

Severity: CRITICAL.

## RISK-C — World Pack owns transaction infrastructure

Failure smell:

```text
NarutoTransactionManager
BleachPlayerRepository.commitTechniqueUse()
```

Mitigation:

- provider returns rules/constraints/evidence only;
- Core owns later transaction infrastructure;
- P18-ORCH-03/10/25.

Severity: CRITICAL.

## RISK-D — command -> DB shortcut

Failure smell:

```text
resolver.handle(command) { store.update(...) }
```

Mitigation:

- resolver output draft only;
- no writer callback;
- static forbidden dependency gate.

Severity: CRITICAL.

## RISK-E — ChangeSet -> DB shortcut

Failure smell:

```text
changeSet.apply(db)
changeSet.commit(repo)
engine.persist(changeSet)
```

Mitigation:

- Phase-17 aggregate remains data only;
- Phase-18 assembler pure;
- transaction boundary later.

Severity: CRITICAL.

## RISK-F — duplicated validation

Failure smell:

- resolver, mechanic, provider and DB each implement slightly different same rule;
- error codes disagree;
- one branch bypasses another.

Mitigation:

- explicit layer ownership;
- canonical structural validators reused;
- domain/store commit constraints remain final authority;
- deterministic `InvariantValidationPort` aggregation.

Severity: HIGH.

## RISK-G — hidden mutable state

Failure smell:

- shared RNG;
- mutable current campaign/player;
- registries modified at runtime;
- cached draft builder.

Mitigation:

- immutable registries;
- per-resolution local context/entropy;
- P18-ORCH-07/21/22/25.

Severity: HIGH.

## RISK-H — nondeterministic resolution

Failure smell:

- wall clock changes output;
- uncontrolled Random;
- unordered maps alter effect order;
- async rule completion order changes result.

Mitigation:

- explicit entropy;
- stable rule/mechanics versions;
- deterministic collection ordering;
- P18-ORCH-07/19/24.

Severity: CRITICAL for replay integrity.

---

# 31. Phase 3–17 integration map

## Phase 3 — Player State Contract

Phase 18 reads stable player/campaign identity and persistent/derived/runtime separation.

It does not flatten those layers.

## Phase 4–5 — Stats/Resources + DerivedValueResolver

Mechanics reads canonical base/current definitions and derived snapshots.

Derived values are not converted into authoritative writes unless a legal typed domain change explicitly targets authoritative base/current state.

## Phase 6 — Talent/Potential

Training mechanics may consume talent/potential as read inputs; Phase 18 does not redefine profiles.

## Phase 7–9 — Skills/Techniques/Innate/Evolution

Typed resolvers reference stable UIDs and delegate world legality to provider/domain validators.

## Phase 10–12 — Inventory/Equipment/Ownership

Cross-domain commands may propose multiple typed effects but do not bypass each domain's invariants.

## Phase 13–14 — Finance/Assets/Liabilities

Finance uses ledger-oriented proposed effects rather than set-balance mutation.

World-specific economy policy may constrain, not replace, Core ledger authority.

## Phase 15 — DevelopmentProject

Project command resolvers may propose validated project-domain effects/evidence; no direct project store write.

## Phase 16 — PlayerCommand

Accepted immutable intent contract is input and remains unchanged.

## Phase 17 — PlayerChangeSet

Future accepted immutable proposal contract is output.

Because no runtime candidate exists today, implementation must re-pin exact Phase-17 accepted SHA before coding.

---

# 32. Legacy policy

Phase 18 should not auto-wrap existing legacy flows into commands/change sets unless explicitly migrated by a later coordinated phase.

No synthetic command history.

No synthetic ChangeSet history.

Legacy StatePatch remains outside Phase-18 typed player path.

If an existing UI/AI path still writes player state directly, Phase 18 architecture does not silently fix all historical writers; Phase 26 owns global Single Truth Mutation Path enforcement.

However **new Phase-18 PlayerDomainEngine must not create another direct writer**.

---

# 33. Persistence and schema decision

Phase 18 orchestration requires no new persistent table by architecture.

No need for:

- `player_domain_engine_runs`;
- `resolution_results` table;
- command execution status;
- RNG history table;
- ChangeSet persistence table;
- transaction table.

All Phase-18 artifacts may remain transient until later transaction/idempotency/event infrastructure defines durable lifecycle.

If implementation proposes a migration merely to make Phase 18 work, that is a design warning requiring explicit review.

---

# 34. Test doubles required before implementation

To test orchestration without prematurely implementing later phases:

```text
FakeWorldRuleProvider
FakePlayerMechanic
FakeInvariantValidator
FakeResolutionContextReader
FakeResolutionEntropy
SpyCommandResolver
FakePlayerChangeSetAssembler wrapping exact accepted Phase-17 contract
```

Test doubles must themselves be side-effect free unless a test deliberately injects hostile behavior.

Do not create a fake TurnTransaction inside Phase-18 production package.

---

# 35. Proposed implementation file boundaries (future only)

This is a planning map, not authorization to create files now.

Possible Phase-18 production split:

```text
PlayerDomainEngine.kt
PlayerDomainResolutionModel.kt
PlayerCommandResolver.kt
PlayerCommandResolverRegistry.kt
PlayerResolutionContext.kt
PlayerRulePipeline.kt
PlayerMechanics.kt
PlayerMechanicsRegistry.kt
PlayerInvariantValidationPort.kt
PlayerChangeSetAssembler.kt
ResolutionEntropy.kt
```

Avoid one giant `PlayerDomainEngine.kt` containing all models and handlers.

World-specific provider implementation files belong to Phase 19 / World Pack integration, not Phase 18 Core.

---

# 36. Static architecture gates

Future CI should include architecture-source checks stronger than simple naming where feasible.

At minimum inspect Phase-18 production files for forbidden dependencies:

```text
android.database.sqlite.SQLiteDatabase
StatePatchEngine
LocalGameStore writer surface
domain Store writer classes
transaction BEGIN/COMMIT APIs
Naruto/Bleach direct references
```

A whitelist is safer than blacklist alone:

Allowed categories:

- Phase-16 command models/registry;
- accepted Phase-17 ChangeSet model/registry;
- immutable domain read models;
- read-only resolution context port;
- rule/provider port;
- pure mechanics interfaces;
- pure validators;
- deterministic entropy abstraction.

---

# 37. Acceptance criteria for future Phase-18 implementation

Phase 18 implementation should not be considered ready for validation unless all are true:

1. Phase 17 is final 3×PASS on one exact runtime SHA.
2. Implementation baseline pins that exact SHA or a report-only descendant.
3. `PlayerDomainEngine.resolve()` accepts accepted Phase-16 PlayerCommand directly.
4. Successful output is accepted Phase-17 PlayerChangeSet directly.
5. No Phase-18 production type can commit.
6. No mechanics/provider/validator receives writer capabilities.
7. Resolver/mechanics registries are deterministic and fail-closed.
8. WorldRuleProvider is behind a narrow port; Core contains no world-name dispatch.
9. explicit deterministic entropy exists for stochastic mechanics.
10. provenance/evidence chain is preserved.
11. campaign/player isolation tests pass.
12. zero-mutation fixtures pass for success/rejection/error paths.
13. no StatePatch fallback exists.
14. Phase 3–17 full regression passes.
15. exact CI is green.
16. Phase 18 is not globally marked COMPLETE by worker; coordinator closes it after independent audits.

---

# 38. Phase-17 handoff checklist before any Phase-18 coding

Because Phase-17 runtime is absent now, future implementer must re-open this report and verify:

```text
[ ] exact accepted Phase-17 runtime SHA
[ ] 3×PASS reports all target same SHA
[ ] exact CI SUCCESS for that SHA
[ ] actual PlayerChangeSet class/package names
[ ] actual schemaVersion
[ ] actual changeSetUid/sourceCommandUid contract
[ ] actual typed change registry API
[ ] actual structural validation API
[ ] actual duplicate/conflict semantics
[ ] actual immutable collection semantics
[ ] actual deterministic serialization/fingerprint if implemented
[ ] actual provenance/event/ledger/warning types
[ ] actual precondition/expected-version types
[ ] negative gate confirms no Phase-18 runtime already leaked into Phase 17
```

If any differs from WORK-068 assumptions, update this Phase-18 design before coding.

---

# 39. Final proposed contract summary

```text
PlayerDomainEngine
= pure/read-only orchestration boundary

INPUT
= immutable accepted Phase-16 PlayerCommand

DISPATCH
= stable commandKindUid -> typed Core resolver registry

WORLD RULES
= narrow WorldRuleProviderPort, no writer/transaction authority

MECHANICS
= deterministic pure calculation over immutable snapshots + explicit entropy

VALIDATION
= layered structural/scope/domain/world invariants, no writes

OUTPUT SUCCESS
= immutable accepted Phase-17 PlayerChangeSet proposal

OUTPUT FAILURE
= typed CommandRejection / internal resolution error

COMMIT
= NOT PHASE 18

STATEPATCH
= NO FALLBACK

PERSISTENCE
= NONE REQUIRED
```

---

# 40. Final status

```text
WORK ID:
WORK-20260810-069

TARGET PHASE:
18 — PlayerDomainEngine orchestration

PHASE-17 RUNTIME CANDIDATE:
NONE PRESENT ON FRESH MASTER

LAST PRODUCTION/TEST RUNTIME:
2472879e8b1c360837fa45b7b7a356175c96a1db
(Phase 16)

FRESH MASTER BEFORE REPORT WRITE:
5378becc29795cb02f626641a58fe02538314ff0
(report-only Phase-17 no-runtime audit)

PHASE-18 ARCHITECTURE/TEST PLAN:
READY

PHASE-18 IMPLEMENTATION:
BLOCKED UNTIL FINAL 3×PASS PHASE 17

PHASE-18 STARTED:
NO

RUNTIME CHANGES:
NONE

SCHEMA/MIGRATIONS:
NONE

PRODUCTION TEST CHANGES:
NONE

OPEN BLOCKER:
No Phase-17 PlayerChangeSet runtime candidate exists yet, therefore no final 3×PASS Phase 17 can exist yet.
```
