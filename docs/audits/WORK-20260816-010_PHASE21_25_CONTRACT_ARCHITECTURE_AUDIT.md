# WORK-20260816-010 — Phase 21–25 Player Core Completion Contract / Architecture Audit

Status: PRE-IMPLEMENTATION / READ-ONLY RUNTIME / EVIDENCE-ONLY

## 0. Work identity and verdict

- Work ID: `WORK-20260816-010`
- Role: CHAT-2 — independent contract / architecture / dependency auditor
- Program: PLAYER CORE COMPLETION — PHASE 21–25
- Accepted Phase-20 runtime: `38dafe5cc48c87f16218e346d9c0f9a96b6cee50`
- Master inspected before audit/write: `dc294fc655ee12ebffb2d2258cf50dd39cd165cf`
- Master delta from accepted Phase-20 runtime: 6 commits, documentation/evidence-only; no Kotlin/runtime/schema/migration delta detected.
- Latest master CI inspected: `Validate RPG OS ALPHA` run #584 / ID `31964196427` — `completed / success`.
- Production runtime/schema/migrations/tests modified by this work: **NONE**.

**FINAL VERDICT: READY WITH REQUIRED PRECONDITIONS**

The 21→25 program is architecturally implementable on the accepted Phase-20 foundation, but CHAT-1 must honor five internal gates and two cross-phase prerequisites:

1. Phase 21 must extend the existing accepted `ProgressionEngine` factor/evidence contract instead of creating another progression engine, and it must not pretend to implement Time Skip/Scheduler/Temporal Engine.
2. Phase 23 must explicitly resolve `P20-CB-01` and preserve the existing authoritative finance ledger while keeping not-yet-committed progression evidence distinct from committed history.

Neither item requires a new coordinator architecture decision if the contracts below are followed. They are implementation preconditions/gates, not blockers to starting the program.

No Phase 21–25 phase is declared COMPLETE by this audit.

---

## 1. Repository-first bootstrap findings

Canonical source ordering was applied from `PROJECT_WORK_PROTOCOL.md`, MASTER, ROADMAP, coordination policy, GM target architecture, Phase-19 canonical acceptance/scope/deferred findings and Phase-20 acceptance.

The repository confirms:

- Phase 19 remains accepted and frozen around one coherent pinned World Pack authority observation/binding per resolution and zero authoritative mutation by `WorldRuleProvider`.
- Phase 20 is accepted at `38dafe5c...` and already integrates deterministic progression into `PlayerDomainEngine` before the single final `DRAFT_EFFECT_CHECK`.
- `PlayerResolutionDraft` now carries `progressionStimuli`.
- `PlayerDomainEngine` performs base draft reference closure, progression augmentation, augmented draft reference closure, one final Phase-19 draft-effect legality check, then engine-owned `PlayerChangeSet` construction.
- `ProgressionEngine` is pure/deterministic, fixed-point, supports STAT/SKILL/TECHNIQUE grants, canonical calculation factor ordering and stable fingerprints.
- `ProgressionLedgerIntentPayload` exists as proposal-level evidence inside the existing `PlayerLedgerIntent` family.
- No production file named or dedicated to `PlayerInvariantValidator` exists.
- No production `PlayerSnapshotBuilder` exists.
- `CharacterPanelSnapshot` exists, but is an old flat presentation DTO assembled by `CharacterPanelReader` directly from a mixture of legacy/current tables.
- Existing finance has a real append-only authoritative ledger (`financial_ledger_transactions`) with DB trigger enforcement and rebuildable balance projection.
- Existing ownership and development-project domains already preserve structured causal/provenance fields and append/history-like records.

Phase-19 deferred recovery/transaction/snapshot findings remain out of scope. This program must not absorb TurnTransaction, Phase-33 Snapshot System, Save/Load, crash recovery or branching.

---

# 2. Independent phase classifications

## Phase 21 — Diminishing Returns + passive progression hooks

**Classification: PARTIAL**

### Reusable existing contracts

- `ProgressionEngine`
- `ProgressionEvaluationInput`
- `ProgressionStimulus`
- `ProgressionCalculationFactor`
- canonical factor sorting/fingerprinting
- fixed-point `ProgressionScaledValue` / `ProgressionNumericPolicy`
- current factor kinds: TALENT, POTENTIAL, DIFFICULTY, QUALITY, OUTCOME
- `durationUnits`, `effortUnits`, `intensity`, `methodUid`, dependency versions
- deterministic source channels: TRAINING, PRACTICE, PROJECT, COMBAT
- Phase-20 stable grant/computation/progression identities
- Phase-20 PlayerDomainEngine augmentation path

### Missing Phase-21 contract

- diminishing-return factor semantics;
- novelty factor/evidence;
- adaptation/repetition state evidence;
- repetition-window/history input contract;
- explicit fatigue/injury progression factor semantics where applicable;
- passive progression stimulus/hook contract;
- environment-driven passive adaptation stimulus contract;
- time/duration-driven hook contract that does **not** itself advance time;
- tests proving deterministic/replay-stable Phase-21 behavior.

### Correct minimal ownership

Phase 21 extends **the same ProgressionEngine**. It should add typed evidence/factor kinds and a deterministic pre-evaluation policy step that enriches a `ProgressionStimulus`/evaluation plan. It must not add `DiminishingReturnsEngine` or `PassiveProgressionEngine` as competing calculators.

Recommended new generic factor/evidence identities include at least:

- `DIMINISHING_RETURNS`
- `NOVELTY`
- `ADAPTATION`
- `REPETITION`
- `FATIGUE_IMPACT`
- `INJURY_IMPACT`
- `ENVIRONMENT`

These remain generic Core concepts. World Packs may map domain-specific evidence/rules into exact factors, but the canonical arithmetic and result identity remain owned by the accepted ProgressionEngine.

### Passive hooks boundary

Phase 21 should define a pure hook contract such as conceptually:

`PassiveProgressionHookInput -> List<ProgressionStimulus>`

The hook **does not advance time, schedule anything, simulate the world, mutate state, or commit**. It only converts an already-resolved external cause/time/environment fact into canonical progression stimuli.

Therefore:

- Time Skip Processor owns orchestration of a time skip later.
- Scheduler owns future scheduled evaluations later.
- Temporal Engine owns historical/temporal truth later.
- World simulation owns background world causality later.
- Phase 21 owns only the deterministic conversion of supplied causal evidence into progression evaluation.

### Schema/migration implication

Preferred Phase-21 DB migration delta: **NONE**. Historical/repetition evidence should be consumed through a read-only snapshot/history interface whose persistence authority is finalized in Phase 23. Do not create a parallel `diminishing_returns_state` truth table unless later evidence proves a non-derivable authoritative state is required.

### Existing tests reusable

- `Phase20ProgressionEngineTest.kt`
- `Phase20FactorCanonicalizationRegressionTest.kt`
- Phase-19 pinned-authority regressions

### Missing tests

Deterministic diminishing-return, novelty/adaptation/repetition, fatigue/injury factor, passive hook determinism, same evidence => same stimulus/result, reordered input => same canonical result, cross-campaign rejection, no hidden clock/random dependence.

---

## Phase 22 — Player Invariant Validator + No-Retrogression

**Classification: PARTIAL**

### Why PARTIAL, not MISSING

The repository already contains many local guards:

- `PlayerChangeSetValidator` structural/value validation;
- typed change registry;
- Phase-18 reference closure and campaign classification;
- stat/resource contract and persistence bounds;
- ownership policy and share/reference guards;
- inventory/equipment constraints;
- financial DB guards and balance invariants;
- project lifecycle/value guards;
- tests such as `PlayerChangeSetValueInvariantHardeningTest.kt`.

These are useful local invariants, but they are not the canonical cross-player-domain `PlayerInvariantValidator` required by MASTER.

### Missing canonical contract

Add one read-only validator whose concern is **proposal consistency against an immutable current authoritative player-state snapshot**.

Conceptual contract:

`PlayerInvariantValidator.validate(PlayerChangeSet, PlayerInvariantSnapshot) -> InvariantValidationResult`

It must not mutate state or return new mechanics gains.

`PlayerInvariantSnapshot` should contain only authoritative/current facts needed to validate the proposed deltas, including stable UIDs, record versions, current persistent values, legal numeric bounds, ownership/inventory/equipment facts, current resource state and typed causal-regression evidence. It should be immutable and fingerprintable.

### No-Retrogression rule

Permanent negative changes to persistent stat/skill/technique progression must be rejected by default.

Regression may be accepted only with an explicit **typed causal authorization**, not a warning string or free-form prose. Examples may include injury, seal, curse, disease, aging or memory-loss mechanics when represented by an accepted typed cause and when the target change is defined by Core/World Pack rules as legally regressible.

Temporary impairment should normally be represented by modifiers/runtime conditions rather than destructive reduction of persistent mastery.

### WorldRuleProvider vs PlayerInvariantValidator

- `WorldRuleProvider`: universe legality and World Pack rules.
- `PlayerInvariantValidator`: structural/domain consistency of the resulting player proposal.

The validator must not re-run progression arithmetic and must not become WorldRuleProvider #2.

### Correct pipeline position

For current architecture:

`command validation -> command refs -> pinned WorldRuleProvider COMMAND_PRECHECK -> domain resolution -> base draft refs -> Phase20/21 progression augmentation -> augmented refs -> single final WorldRuleProvider DRAFT_EFFECT_CHECK -> construct + structural-validate PlayerChangeSet -> PlayerInvariantValidator -> return proposal`

Future TurnTransaction must reuse/re-run the **same invariant contract** against fresh commit-time state/preconditions; it must not introduce a different second invariant mechanics engine.

### Schema/migration implication

Preferred Phase-22 DB migration delta: **NONE**. The validator should read existing authoritative stores through a bounded snapshot/provider abstraction. If typed regression-cause evidence requires a proposal-schema extension, keep it in the PlayerChangeSet/provenance/evidence contract, not a new persistence authority.

### Missing tests

- unexplained permanent stat regression rejected;
- skill/mastery regression rejected;
- technique regression rejected;
- legal regression accepted only with exact typed cause;
- temporary condition does not rewrite persistent mastery;
- resource underflow/overflow/bounds;
- ownership/item/equipment integrity;
- reference/campaign isolation;
- stale record-version rejection/precondition behavior;
- validator side-effect-free and deterministic;
- Phase17–20 regression preservation.

---

## Phase 23 — Unified Player ledgers + provenance integration

**Classification: PARTIAL**

### Existing ledger/evidence families

1. **Finance** — actual authoritative append ledger:
   - `FinancialTransaction`
   - `financial_ledger_transactions`
   - source event / command / provenance fields
   - DB guards and rebuildable account-balance projection.

2. **Progression** — proposal evidence only:
   - `ProgressionLedgerIntentPayload`
   - `PlayerLedgerIntent(kind=PROGRESSION)`
   - stable progression/grant/input/computation fingerprints.

3. **Ownership** — authoritative interval/history records:
   - `OwnershipRecord`
   - sourceEventUid, supersedes/closure evidence, provenance.

4. **Development projects** — structured history/evidence:
   - project status events;
   - requirement satisfactions;
   - milestone achievements;
   - `ProjectWorkRecord`;
   - project outcomes;
   - source event/command/provenance links.

5. **PlayerChangeSet**:
   - event intents;
   - ledger intents;
   - `ChangeSetProvenance`;
   - causation/correlation IDs.

6. **Legacy evidence**:
   - progression legacy evidence/mappings;
   - skill/technique legacy mapping/reconciliation evidence;
   - older campaign tables that must not be retrospectively upgraded into invented history.

### Core Phase-23 design

Do **not** create one giant polymorphic SQLite table containing the full payload of every ledger family.

Use a **unified semantic envelope + family-owned payload/storage** model.

Recommended conceptual envelope:

`PlayerLedgerRecordRef / PlayerLedgerEnvelope`

Fields should include at minimum:

- ledgerUid
- campaignUid
- subject/actor refs where applicable
- ledgerKindUid
- payloadRefUid or typed payload identity
- commandUid
- sourceEventUid when known
- causationUid/correlationUid when known
- effectiveOrder/turn when known
- provenance refs/evidence refs
- schemaVersion
- engine/component/rule identity where applicable
- commitmentState (`PROPOSAL` vs `COMMITTED`) at the contract level, never inferred from presence in memory.

Family payloads remain typed. Finance remains finance; progression remains progression; ownership remains ownership evidence; project records remain project evidence.

### Source-of-truth decision

- Existing committed finance ledger = authoritative financial history.
- Existing committed ownership/project records = authoritative within their accepted domain contracts.
- `PlayerLedgerIntent` inside an uncommitted `PlayerChangeSet` = proposal, **not history**.
- Progression ledger intent must not be presented as committed progression history until a supported commit path appends it.
- A unified ledger index/catalog may be DERIVED if fully rebuildable from authoritative family records.
- Do not duplicate authoritative payloads into a second unified storage table merely for querying.

### What can safely persist before TurnTransaction

Safe:

- append-preserved family records already owned by accepted stores;
- new typed ledger schema/store substrate if it is explicitly not wired as a side-channel commit path;
- rebuildable unified index/projection over already committed family records;
- schema/version metadata.

Must remain deferred:

- atomic commit of state + events + all ledger families;
- global idempotent transaction identity;
- rollback/crash recovery across those writes;
- event-store redesign.

These belong to Transactional Campaign Core.

### P20-CB-01 resolution

`ProgressionStimulus.evidenceRefs` should become **forward-going durable provenance references** in progression ledger evidence because they explain the causal basis of the grant and are already part of Phase-20 legality/reference closure.

Recommended rule:

- add immutable/canonically ordered `evidenceRefs` to the progression ledger payload/envelope for newly generated Phase-23+ records;
- include them in deterministic ledger identity/fingerprint;
- preserve campaign/reference validation;
- **do not backfill/fabricate** evidence refs for previously created/legacy data that never stored them;
- absence in legacy/current Phase-20 proposal artifacts remains valid historical absence, not an error requiring invented provenance.

This closes `P20-CB-01` without rewriting history.

### Schema/migration implication

A DB migration is **optional, not intrinsically required** for the unified envelope if Phase 23 first ships as typed domain contracts + rebuildable readers/indexes. If a persisted unified index is introduced, mark it explicitly DERIVED/rebuildable.

Do not add a second authoritative progression-history table unless the implementation also has a defined legal append boundary. Since full TurnTransaction is later, safest Phase-23 scope is contracts/read model/index plus family adapters, not a new direct writer from `PlayerDomainEngine`.

### Missing tests

- proposal vs committed distinction;
- no progression intent appears as committed history prematurely;
- finance authority unchanged;
- unified projection rebuilds from family records;
- no duplicate ledger authority;
- evidenceRefs canonicalization/cross-campaign rejection;
- legacy data preserved without fabricated provenance;
- stable ledger identity/replay;
- family payload round-trip compatibility.

---

## Phase 24 — CharacterPanelSnapshot v2

**Classification: PARTIAL**

### Why roadmap PARTIAL is correct

`CharacterPanelSnapshot` and `CharacterPanelReader` exist, but they are still an old presentation implementation:

- flat display lines/strings;
- missing schema version, generated turn/order and explicit character UID;
- no talents/potential;
- no innate abilities/evolution section;
- no progression summary;
- no proper inventory vs equipment distinction;
- no economy/assets/ownership/debts/obligations sections;
- weak provenance/authority metadata;
- direct SQLite reads from mixed legacy tables;
- `character_inventory` is displayed as `equipment`, proving the old DTO is not the target domain projection;
- legacy `character_status_snapshot` may feed identity/resources.

The object is presentation-oriented today, not a canonical v2 projection over accepted Player Domain repositories.

### Target V2 contract

`CharacterPanelSnapshotV2` should be a versioned, immutable, disposable read model containing at least:

- schemaVersion
- generatedAtOrder/turn
- characterUid
- identity
- stats (base + effective/derived separation where useful)
- resources (current/max/derived semantics)
- talents
- potential
- skills
- techniques
- innate/racial/bloodline/evolution abilities
- progression summary/evidence summaries
- inventory
- equipment
- economy/accounts/balances
- assets/liabilities/ownership summaries
- relationships/reputation/organizations where existing accepted sources support them
- active goals/projects/missions
- conditions/runtime state
- diagnostic/source metadata sufficient to explain unavailable/legacy sections without pretending they are authoritative.

Do not copy every ledger event into the panel; expose bounded summaries and stable UIDs.

### Absolute authority rule

`delete CharacterPanelSnapshot -> rebuild -> same authoritative campaign state`

Deleting/corrupting the panel must lose **zero** authoritative information. No gameplay mutation may target the snapshot as a source table/object.

### Migration recommendation

No authoritative DB migration is required merely to introduce V2. Prefer a Kotlin DTO/read-model replacement plus compatibility adapter for current UI. If a cache is later persisted, it must be explicitly CACHE/PRESENTATION, versioned and deletable/rebuildable.

### Missing tests

- snapshot rebuild equality from same authoritative sources;
- deletion/no-data-loss proof;
- stable UID exposure;
- typed section coverage;
- inventory != equipment;
- progression/economy/ownership summaries trace to authoritative sources;
- unknown/legacy data represented without semantic guessing;
- frontend compatibility adapter does not become authority.

---

## Phase 25 — PlayerSnapshotBuilder + profiles

**Classification: MISSING**

### Existing reusable inputs, but no target implementation

There are existing readers/context helpers (`CharacterPanelReader`, inventory/technique/financial/social context readers/builders), but no canonical `PlayerSnapshotBuilder` and no target profile contract for:

- FULL
- COMBAT
- PROGRESSION
- ECONOMY
- SOCIAL
- GM_CONTEXT

Therefore the Phase-25 target implementation itself is MISSING even though its source readers/stores exist.

### Correct architecture

`PlayerSnapshotBuilder` is the only assembler of `CharacterPanelSnapshotV2`/profile projections from authoritative repositories + derived resolvers + runtime state + committed ledger summaries.

Profiles are **projections/filter specifications**, not stores, authorities or independent DTO truth silos.

Recommended shape:

`PlayerSnapshotBuilder.build(characterUid, SnapshotProfile, ReadContext) -> CharacterPanelSnapshotV2/ProfileProjection`

All profiles should share the same canonical source assembly and then select sections through a profile definition. Do not implement six separate builders.

### Profile source map

- FULL: all supported V2 sections.
- COMBAT: identity essentials, effective stats/resources, skills/techniques/innate abilities relevant to combat, equipment, active conditions/runtime modifiers.
- PROGRESSION: base stats, talents/potential, skills/techniques progress, progression summaries, relevant projects/conditions.
- ECONOMY: accounts/balances, committed finance ledger summaries, assets/liabilities/ownership, inventory where valuation/useful.
- SOCIAL: relationships/reputation/organizations and identity fields required to interpret them.
- GM_CONTEXT: bounded player-domain FACT projection only, plus explicit truth-class metadata where source data already has it.

### GM_CONTEXT truth rule

Phase 25 must **not** implement NPC Knowledge or Context Builder. GM_CONTEXT is only a player snapshot profile. It must not collapse `FACT`, `BELIEF`, and `NARRATIVE` into one map.

If a source is not truth-classified, the builder must not silently label it FACT. Either omit it from GM_CONTEXT or carry explicit classification/unknown provenance until later knowledge/context phases define the consumer policy.

Existing generic `ContextBundle` maps are not a substitute for this contract.

### Schema/migration implication

Preferred DB migration delta: **NONE**. Profiles are pure projections.

### Missing tests

- six profile inclusion/exclusion matrices;
- profile output sourced from one canonical builder;
- profiles cannot mutate/store truth;
- cross-campaign isolation;
- stable UID preservation;
- GM_CONTEXT truth separation and no knowledge leakage;
- deterministic output for same read snapshot;
- bounded profile content where applicable.

---

# 3. Reusable code map

| Existing component | Reuse in 21–25 | Authority classification |
|---|---|---|
| `ProgressionEngine.kt` | Phase 21 arithmetic/factor extension | mechanics, no state mutation |
| `ProgressionLedgerIntent.kt` | Phase 23 progression provenance payload | proposal until committed |
| `PlayerDomainEngine.kt` | Phase 21 integration + Phase 22 validator insertion | orchestration/proposal |
| `PlayerChangeSetModel.kt` | Phase 22 validation target + Phase 23 ledger envelope | proposal |
| `ProgressionProfileStore.kt` | Talent/Potential inputs | authoritative player profiles |
| `StatResourceStore.kt` | Phase 22 current state; Phase 24/25 source | authoritative/derived by field contract |
| `SkillStore.kt` / `TechniqueStore.kt` | invariant + snapshot sources | authoritative persistent skill/technique state |
| `DerivedValueResolver.kt` | snapshot effective values | derived |
| `FinancialStore/Model` + Phase13 guards | economy profile and Phase23 family adapter | authoritative ledger + derived balance projection |
| `OwnershipStore/Model` | invariant + economy/assets profile | authoritative ownership history/state |
| `DevelopmentProjectStore/Model` | Phase21 project stimulus evidence; Phase23 evidence; snapshots | authoritative project domain records |
| `CharacterPanel.kt` | compatibility surface only; replace/adapter into V2 | presentation/read model |
| context readers/builders | source adapters only; do not make them PlayerSnapshotBuilder | derived/presentation |
| Phase19 authority/provider contracts | preserved unchanged | World Pack legality |

---

# 4. Source-of-truth map

## Authoritative now

- accepted Player persistent/runtime stores according to their domain contracts;
- finance transaction ledger;
- ownership records;
- accepted project records/status/work/outcomes;
- talent/potential/skill/technique/innate and stat/resource persistent state where canonical typed authority exists;
- canonical World Pack authority.

## Derived/rebuildable

- financial balances (explicitly projection-backed by ledger);
- effective values from `DerivedValueResolver`;
- unified ledger catalog/index if introduced in Phase 23;
- CharacterPanelSnapshot V2;
- all Phase-25 profiles.

## Proposal only

- `PlayerChangeSet`;
- `PlayerEventIntent`;
- `PlayerLedgerIntent`, including Phase-20 progression evidence;
- Phase21 progression results before commit.

## Never a truth source

- AI output;
- CharacterPanelSnapshot;
- profile DTOs;
- GM_CONTEXT;
- legacy presentation strings;
- progression computation itself without later legal commit.

---

# 5. Cross-phase 21→25 pipeline

Recommended current/future-compatible pipeline:

```text
PlayerCommand
-> canonical command validation + reference closure
-> pin one canonical World Pack binding
-> WorldRuleProvider COMMAND_PRECHECK
-> domain resolution -> base PlayerResolutionDraft
-> base draft reference closure
-> Phase 20 ProgressionStimulus extraction
-> Phase 21 deterministic progression factor/hook enrichment
-> SAME accepted ProgressionEngine evaluation
-> merge progression grants + progression ledger intents
-> final augmented reference closure
-> WorldRuleProvider DRAFT_EFFECT_CHECK exactly once on final effects
-> engine-owned PlayerChangeSet construction
-> existing structural PlayerChangeSet validation
-> Phase 22 PlayerInvariantValidator against immutable current-state snapshot
-> validated PlayerChangeSet proposal
-> Phase 23 typed ledger/provenance envelope remains attached to proposal
-> future TurnTransaction
-> authoritative state + committed family ledgers/events
-> derived resolvers + committed ledger summaries
-> Phase 24 CharacterPanelSnapshot V2
-> Phase 25 PlayerSnapshotBuilder profile projection
```

Validation occurs at different boundaries for different reasons, not as duplicate mechanics:

1. structural/type/reference validation before mechanics;
2. WorldRuleProvider universe legality before and after final effects;
3. PlayerInvariantValidator proposal consistency after the complete effect set exists;
4. future transaction revalidation of preconditions/same invariant contract against fresh commit-time state.

Progression arithmetic must run once per canonical stimulus. Snapshot/profile construction must never rerun progression.

---

# 6. Mechanic ownership matrix

| Mechanic | Owner | World Pack influence? | Snapshot expose? | Ledger record? | AI decide? | Forbidden duplicate owner |
|---|---|---:|---:|---:|---:|---|
| progression calculation | Phase20 `ProgressionEngine` | policy/evidence only | summary | yes | no | Phase21 hook, WorldRuleProvider, snapshot |
| diminishing returns | Phase21 extension of progression policy/factors | yes | optional summary | yes | no | second progression engine |
| passive progression | Phase21 stimulus hook; later TimeSkip orchestrates trigger | yes | summary | yes | no | TimeSkip-owned arithmetic |
| novelty/adaptation/repetition | Phase21 factor/evidence policy | yes | optional | yes | no | snapshot/AI |
| no-retrogression | Phase22 `PlayerInvariantValidator` | typed legal exceptions | diagnostics only | causal evidence may record | no | WorldRuleProvider #2 |
| world legality | Phase19 `WorldRuleProvider` | by definition | no/diagnostic | decision evidence | no | invariant validator/progression |
| stable identity | Core domain contracts/registries | definitions may supply UIDs | yes | yes | no | display names |
| provenance | Phase23 unified semantic envelope + family provenance | source metadata only | bounded | yes | no | snapshot as provenance store |
| financial truth | existing financial ledger/store | currency/rules definitions | yes | already ledger | no | unified ledger copy |
| ownership truth | ownership domain/store | definitions/rules may influence | yes | history adapter | no | inventory location/snapshot |
| skill mastery | skill authoritative state; gains proposed by progression/domain rules | yes | yes | progression evidence | no | snapshot |
| technique mastery | technique authoritative state; gains proposed by progression/domain rules | yes | yes | progression evidence | no | snapshot |
| snapshot derivation | Phase24/25 builder path | display definitions only | n/a | no | no | stores/AI |
| profile filtering | Phase25 `SnapshotProfile` | generally no; section definitions may be extensible | n/a | no | no | six independent builders |
| AI narration/choice | future AI/GM phases | uses allowed context | narrative only | not authoritative | yes, narrative/high-level | mechanics/state commit |

---

# 7. Dependency graph and internal gates for CHAT-1

## GATE 21 — Progression scaling/hook extension

Likely files:

- `ProgressionEngine.kt`
- new Phase21 factor/hook model file(s)
- minimal `PlayerDomainEngine.kt` integration only where required
- Phase21 tests

Acceptance gate:

- no second progression engine;
- deterministic exact arithmetic preserved;
- diminishing/novelty/adaptation/repetition modeled as evidence/factors;
- passive hooks produce stimuli only;
- no clock/random/DB writes;
- Phase19 pinned binding and Phase20 identities unchanged;
- all Phase20 tests pass.

Forbidden shortcut: implementing Time Skip/Scheduler/Temporal Engine or persisting ad-hoc adaptation state as a new authority.

## GATE 22 — PlayerInvariantValidator

Likely files:

- new `PlayerInvariantValidator.kt`
- possibly `PlayerInvariantSnapshot.kt`
- `PlayerDomainEngine.kt` insertion point
- minimal typed causal-regression evidence extension if required
- Phase22 tests

Acceptance gate:

- whole-proposal validation against immutable current state;
- unexplained permanent regression rejected;
- legal typed causal regression accepted;
- resources/ownership/item/reference/numeric invariants covered at player-domain boundary;
- no progression/world-rule/transaction duplication;
- no state mutation.

Forbidden shortcut: embedding validator logic separately in every resolution component or directly in snapshots/stores only.

## GATE 23 — Unified ledger/provenance semantic integration

Likely files:

- new unified ledger envelope/adapter model(s)
- `ProgressionLedgerIntent.kt` for evidenceRefs closure
- PlayerChangeSet ledger codec/registry as needed
- adapters/readers over finance/ownership/projects/progression
- optional derived index migration only if justified
- Phase23 tests

Acceptance gate:

- finance authority preserved;
- proposal vs committed state explicit;
- no second authoritative copy;
- `P20-CB-01` closed forward-only;
- old data loads without fabricated provenance;
- cross-campaign refs rejected;
- unified view rebuildable from family sources.

Forbidden shortcut: direct progression-ledger DB append from PlayerDomainEngine before transaction commit, or giant payload table replacing accepted family authorities.

## GATE 24 — CharacterPanelSnapshot V2

Likely files:

- `CharacterPanel.kt` or new V2 model file
- source adapters/readers
- compatibility adapter for current frontend only where necessary
- Phase24 tests

Acceptance gate:

- versioned typed V2 sections;
- authoritative/derived/presentation separation explicit;
- rebuild from authoritative sources;
- delete snapshot => no authoritative loss;
- progression/economy/ownership/talent/potential etc. represented without direct legacy guessing;
- existing frontend style unchanged.

Forbidden shortcut: writing gameplay state back through CharacterPanelSnapshot or creating a persisted snapshot authority.

## GATE 25 — PlayerSnapshotBuilder + profiles

Likely files:

- new `PlayerSnapshotBuilder.kt`
- `SnapshotProfile.kt`
- source adapter interfaces as required
- Phase25 tests

Acceptance gate:

- one canonical builder;
- six profiles implemented as projection/filter definitions;
- no independent truth/cache writes;
- deterministic profile results;
- GM_CONTEXT truth separation preserved;
- no NPC Knowledge/Context Builder implementation;
- full Phase17–24 regression suite green.

Forbidden shortcut: six separate builders or passing unclassified ContextBundle maps through as canonical player truth.

---

# 8. Combined acceptance matrix for final Phase-21–25 candidate

## Phase 21

- `P21_DET_01` identical canonical evidence -> identical factors/result/fingerprints.
- `P21_DET_02` input ordering cannot change canonical result.
- `P21_DR_01` repeated/easier stimulus receives deterministic diminishing factor when policy evidence says so.
- `P21_DR_02` no hidden wall-clock/random dependence.
- `P21_NOVELTY_01` novelty evidence is explicit and fingerprinted.
- `P21_ADAPT_01` adaptation/repetition evidence is explicit and bounded.
- `P21_PASSIVE_01` passive hook produces stimuli only, no state mutation.
- `P21_PASSIVE_02` same supplied duration/environment evidence -> same stimuli.
- `P21_BOUNDARY_01` no TimeSkip/Scheduler/Temporal/world-sim implementation.

## Phase 22

- `P22_NR_01` unexplained stat regression rejected.
- `P22_NR_02` unexplained skill/mastery regression rejected.
- `P22_NR_03` unexplained technique regression rejected.
- `P22_NR_04` legal regression requires typed causal authorization.
- `P22_RESOURCE_01` underflow/overflow/bounds rejected.
- `P22_OWN_01` impossible ownership/item/equipment proposal rejected.
- `P22_REF_01` unknown/wrong-campaign references rejected.
- `P22_SIDE_EFFECT_01` validation mutates no authoritative state.
- `P22_REPLAY_01` same proposal + invariant snapshot -> same decision.

## Phase 23

- `P23_LEDGER_01` proposal intent never masquerades as committed record.
- `P23_LEDGER_02` existing finance ledger remains authoritative and unchanged semantically.
- `P23_LEDGER_03` unified index/view is rebuildable; deletion loses no family history.
- `P23_PROV_01` causation/correlation/command/event identities preserved when known.
- `P23_P20CB01_01` new progression evidenceRefs preserved canonically.
- `P23_P20CB01_02` legacy/current records are not backfilled with invented refs.
- `P23_ISOLATION_01` cross-campaign evidence reference rejected.
- `P23_DUPAUTH_01` no second authoritative ledger payload copy.

## Phase 24

- `P24_REBUILD_01` rebuild from same authoritative sources yields equivalent V2 snapshot.
- `P24_DELETE_01` delete/discard snapshot -> zero authoritative data loss.
- `P24_UID_01` stable UIDs retained; names remain labels.
- `P24_SECTION_01` progression/economy/ownership/talent/potential/skills/techniques/resources sections trace to accepted sources.
- `P24_INV_EQ_01` inventory and equipment remain distinct.
- `P24_LEGACY_01` unknown legacy data is not semantically guessed.

## Phase 25

- `P25_PROFILE_FULL` FULL includes every supported V2 section.
- `P25_PROFILE_COMBAT` COMBAT inclusion/exclusion exact.
- `P25_PROFILE_PROGRESSION` PROGRESSION exact.
- `P25_PROFILE_ECONOMY` ECONOMY exact.
- `P25_PROFILE_SOCIAL` SOCIAL exact.
- `P25_PROFILE_GM` GM_CONTEXT exact and bounded.
- `P25_ONE_BUILDER_01` all profiles derive from one canonical builder/source graph.
- `P25_TRUTH_01` GM_CONTEXT does not collapse FACT/BELIEF/NARRATIVE.
- `P25_SIDE_EFFECT_01` profiles/building cannot mutate truth.

## Cross-phase regression

- `CORE_REG_17` PlayerChangeSet contract remains compatible.
- `CORE_REG_18` PlayerDomainEngine reference/orchestration behavior remains compatible.
- `CORE_REG_19` same pinned World Pack binding across precheck/final effect check; one final DRAFT_EFFECT_CHECK.
- `CORE_REG_20` stable progression grant/ledger/input/computation identities preserved.
- `CORE_REPLAY` deterministic replay from same canonical input/evidence.
- `CORE_ISOLATION` campaign A cannot source player/evidence/ledger/snapshot facts from campaign B.
- `CORE_LEGACY` old campaign migration/load succeeds without fabricated history.
- `CORE_AUTHORITY` no snapshot/profile/unified index becomes independent authoritative truth.

---

# 9. Schema / migration recommendation

Default recommendation for the complete 21–25 program: **avoid authoritative DB schema expansion unless a gate proves it necessary**.

- Phase 21: no DB migration expected.
- Phase 22: no DB migration expected.
- Phase 23: contract/adapters first; optional derived/rebuildable index migration only if needed. Do not add a direct progression commit side channel.
- Phase 24: no authoritative DB migration; V2 is a read model.
- Phase 25: no DB migration; profiles are projections.

Existing accepted family schemas must be reused rather than duplicated.

If implementation discovers that Phase21 repetition/adaptation requires non-derivable durable state, STOP at Gate 21 and prove why it cannot be reconstructed from committed progression evidence/current state before adding schema. Any new authoritative state must have explicit lifecycle/provenance and cannot be a convenience cache masquerading as truth.

---

# 10. Compatibility risks

1. **Legacy CharacterPanel coupling** — current UI/readers may expect flat strings and legacy tables. Use a compatibility adapter; do not force frontend redesign.
2. **Double vs fixed-point** — Talent/Potential/skill/technique models still contain Double values. Preserve Phase20 fixed-point conversion boundary for mechanics; snapshots may display formatted values but must not perform mechanics using display strings.
3. **Finance already has stronger commit semantics** — unified ledgers must not weaken or duplicate its authority.
4. **P20-CB-01** — provenance refs must be added forward-only, not fabricated retroactively.
5. **Invariant TOCTOU** — Phase22 resolution-time validation is not final transaction atomicity. Carry record/precondition/version evidence so future TurnTransaction can revalidate the same invariant contract.
6. **GM_CONTEXT legacy maps** — existing `ContextBundle` untyped maps risk truth-category collapse; do not treat them as Phase25 source authority.
7. **Snapshot naming collision** — CharacterPanelSnapshot is a player read model, not Phase33 campaign Snapshot System.

---

# 11. Blockers

No current repository/runtime blocker prevents starting Gate 21.

The following are **required preconditions**, not architectural blockers:

- preserve accepted Phase19/20 contracts and exact ordering;
- do not proceed from one internal gate to the next until that gate's acceptance tests are green;
- Phase23 must close P20-CB-01 forward-only;
- Phase24/25 must consume committed/authoritative sources and never promote proposal ledger intents to committed history.

If CHAT-1 finds that Phase21 requires new durable adaptation state that cannot be derived from committed evidence, or Phase23 requires atomically committing multiple family ledgers to be meaningful, that specific gate becomes `BLOCKED` and must return to coordinator rather than importing TurnTransaction scope.

---

# 12. Forbidden scope for CHAT-1

Across WORK implementing this program, forbid:

- second ProgressionEngine;
- NarutoProgressionEngine / BleachProgressionEngine;
- WorldRuleProvider redesign without concrete regression evidence;
- TurnTransaction;
- global Event Store redesign;
- Phase33 Snapshot System;
- Save/Load / branching / backup / crash recovery;
- NPC Knowledge;
- Temporal Engine;
- Scheduler orchestration;
- Context Builder redesign;
- AI Adapter / canonical AI phases;
- Time Skip Processor;
- Director;
- world simulation;
- Naruto_Default cleanup;
- TEST-GM findings/runtime expansion;
- frontend redesign;
- making CharacterPanelSnapshot/Profile/ContextBundle authoritative;
- direct progression-ledger persistence from PlayerDomainEngine as a side-channel commit;
- retroactive fabrication of provenance/evidence refs.

---

# 13. Recommended CHAT-1 implementation plan

1. Start from fresh master and preserve accepted Phase20 tests as non-negotiable regressions.
2. Implement Gate 21 as additive factor/evidence/hook extensions around the same ProgressionEngine; run Phase19/20 + new Phase21 tests.
3. Implement Gate 22 as one proposal-level `PlayerInvariantValidator` with immutable state snapshot and typed regression authorization; do not distribute no-retrogression logic across mechanics modules.
4. Implement Gate 23 using a unified semantic envelope/adapters, explicitly preserve existing family authority, close P20-CB-01 forward-only, and avoid premature global commit semantics.
5. Replace/augment old CharacterPanel DTO with typed V2 read model at Gate 24, keeping a compatibility adapter for accepted UI.
6. Build one `PlayerSnapshotBuilder` at Gate 25 with six filter/profile definitions, not six independent pipelines.
7. Run the combined acceptance matrix plus all retained Phase17–20 regressions and full CI.
8. Hand exact candidate SHA + CI + gate evidence to independent validators/coordinator. Do not mark global phases COMPLETE locally.

---

## Final verdict

**READY WITH REQUIRED PRECONDITIONS**

The repository has enough accepted foundation to begin the coordinated Player Core Completion implementation at Gate 21. The primary architecture risks are duplication of mechanics/authority, premature ledger commit semantics, confusing CharacterPanelSnapshot with campaign snapshots, and truth-category collapse in GM_CONTEXT. The contracts and gates above avoid those risks without entering forbidden later-phase scope.
