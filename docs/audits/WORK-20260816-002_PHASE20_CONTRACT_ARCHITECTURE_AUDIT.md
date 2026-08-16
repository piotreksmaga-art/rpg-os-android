# WORK-20260816-002 — Phase 20 Contract / Architecture Audit

## Work item

- Work ID: `WORK-20260816-002`
- Role: `CHAT-2` — independent contract / architecture reviewer
- Mode: `READ-ONLY` production runtime/schema; audit/evidence write only
- Phase: `20 — ProgressionEngine + Progression Ledger`
- Requested baseline SHA: `9c81b08c86c341d50506ba99d8a6809d94134dcb`
- Master HEAD inspected before audit: `9c81b08c86c341d50506ba99d8a6809d94134dcb`
- Baseline drift: `NONE`
- Phase-19 accepted runtime: `5754f28ccd4f7c1f3522c0af6c34bcaf65e2dcf8`
- Phase-19 state: `ACCEPTED / COMPLETE`; not reopened by this audit

## CI status

Current `master` SHA `9c81b08c86c341d50506ba99d8a6809d94134dcb` has GitHub Actions workflow `Validate RPG OS ALPHA`, run `#539`, run ID `31955952584`, status `completed`, conclusion `success`.

The accepted Phase-19 runtime remains independently evidenced by canonical acceptance CI run `#534`, ID `31943818205`, conclusion `success`.

## Final classification

**PHASE 20 CURRENT RUNTIME CLASSIFICATION: PARTIAL**

Reason:

- meaningful reusable foundations already exist;
- no production `ProgressionEngine` exists;
- no canonical runtime `ProgressionEntry` / progression-specific ledger payload exists;
- no Phase-20 progression resolution is integrated into `PlayerDomainEngine`;
- no progression-specific authoritative persistence/commit path exists, and creating one now would violate later-phase boundaries;
- existing `ProgressionProfileModel/Store` is Phase-6 Talent/Potential/domain infrastructure, not Phase-20 progression runtime.

Therefore Phase 20 is neither COMPLETE nor wholly MISSING and is not technically BLOCKED by a discovered architecture defect. Implementation remains subject to coordinator authorization and work-item assignment.

## Sources and repository evidence inspected

Canonical / operational documents:

- `docs/PROJECT_WORK_PROTOCOL.md`
- `docs/RPG_OS_MASTER_ARCHITECTURE.md`
- `docs/RPG_OS_IMPLEMENTATION_ROADMAP.md`
- `docs/GM_ENGINE_TARGET_ARCHITECTURE.md`
- `docs/PARALLEL_WORK_COORDINATION.md`
- `docs/architecture/CHAT_COORDINATION_POLICY.md`
- `docs/architecture/PHASE19_ACCEPTANCE.md`
- `docs/architecture/PHASE19_WORLD_RULE_PROVIDER_CANONICAL_SCOPE.md`
- `docs/architecture/PHASE19_DEFERRED_FINDINGS.md`
- `docs/architecture/POST_ENGINE_APPLICATION_CLEANUP_ROADMAP.md`
- prior Phase-20 planning artifact `docs/audits/WORK-20260814-P20_CHAT1_PREIMPLEMENTATION_PLAN.md`

Relevant runtime/schema/test surfaces identified or inspected:

- `app/src/main/java/com/rpgos/app/PlayerDomainEngine.kt`
- `app/src/main/java/com/rpgos/app/PlayerCommandModel.kt`
- `app/src/main/java/com/rpgos/app/PlayerChangeSetModel.kt`
- `app/src/main/java/com/rpgos/app/WorldRuleProvider.kt`
- `app/src/main/java/com/rpgos/app/ProgressionProfileModel.kt`
- `app/src/main/java/com/rpgos/app/ProgressionProfileStore.kt`
- `app/src/main/java/com/rpgos/app/Phase6Migration.kt`
- `app/src/main/java/com/rpgos/app/SkillModel.kt`
- `app/src/main/java/com/rpgos/app/SkillStore.kt`
- `app/src/main/java/com/rpgos/app/TechniqueModel.kt`
- `app/src/main/java/com/rpgos/app/TechniqueStore.kt`
- `app/src/main/java/com/rpgos/app/DerivedValueResolver.kt`
- DevelopmentProject model/store and Phase-15 audit surfaces
- Phase-17/18/19 tests and prior audits returned by repository search
- `ProgressionProfilePersistenceTest.kt`, skill/technique persistence tests, Phase-19 canonical regression suites

Repository search for `ProgressionEngine` returned documentation/audit references but no production runtime implementation.

## Architectural invariants retained

This audit treats the following as non-negotiable:

- `FACT != BELIEF != NARRATIVE`
- `AI OUTPUT != COMMITTED REALITY`
- `Stable UID > name`
- `Authoritative > Derived > Cache/Presentation`
- every durable gain requires a cause
- AI may describe or propose progression but cannot assign durable gains arbitrarily
- `PlayerDomainEngine` remains the single player-mechanics orchestration entry point
- `ProgressionEngine` must be pure/read-only with respect to authoritative state
- no subsystem may create a second player engine or second source of truth
- `PlayerChangeSet` remains a proposal until the later transactional commit boundary

## 1. What already exists and is reusable

### 1.1 PlayerCommand contract — reusable

Current typed commands already contain progression-relevant intents:

- `TRAIN`
- `PRACTICE_SKILL`
- `LEARN_SKILL`
- `LEARN_TECHNIQUE`
- `USE_TECHNIQUE`
- `RECORD_PROJECT_WORK`

The important architectural property is that commands carry intent/cause (`effortUnits`, `methodUid`, target refs) rather than an arbitrary target value. Phase 20 should extend resolution semantics, not add a parallel command bus.

Not every conceptual MASTER source currently has a concrete command. In particular, this audit does **not** require adding synthetic `TIME_SKIP`, `EVOLUTION`, `PASSIVE_ADAPTATION`, or reward commands merely to match the target list.

### 1.2 PlayerDomainEngine orchestration — reusable and must remain owner

Current `PlayerDomainEngine` already performs:

1. command structural/canonical validation;
2. command reference/scope validation;
3. Phase-19 `COMMAND_PRECHECK` WorldRuleProvider evaluation;
4. typed internal resolution through `PlayerResolutionComponent`;
5. immutable `PlayerResolutionDraft` production;
6. draft reference validation;
7. Phase-19 `DRAFT_EFFECT_CHECK`;
8. engine-owned `PlayerChangeSet` assembly;
9. Phase-17 `PlayerChangeSet` validation.

`PlayerResolutionDraft` already contains:

- `changes`
- `eventIntents`
- `ledgerIntents`
- `warnings`

This is the correct integration seam. Progression must be inserted into this pipeline and return data to the draft/proposal path rather than write state.

### 1.3 PlayerChangeSet — reusable, no second change-set type

Existing typed change payloads already represent common durable progression targets:

- `StatChange`
- `SkillChange`
- `TechniqueChange`

`ExactLongDelta` provides exact non-zero delta semantics for these Phase-17 proposal effects.

`PlayerChangeSet` already carries a generic `ledgerIntents` collection and provenance. Current ledger payload support is financial only, but the envelope is deliberately generic. The minimal Phase-20 design should extend this family with a progression ledger intent rather than create `ProgressionChangeSet`, a second transaction proposal, or a direct DB writer.

### 1.4 Talent/Potential / progression-domain foundation — reusable but not Phase 20 itself

`ProgressionProfileModel.kt` already defines:

- `ProgressionDomainDefinition`
- `TalentEntry` / `TalentProfile`
- `PotentialEntry` / `PotentialProfile`
- legacy progression evidence/mapping structures

`ProgressionProfileStore.kt` persists and validates those profiles and domain definitions. Phase-6 tables include:

- `progression_domain_definitions`
- `talent_profile_entries`
- `potential_profile_entries`
- `legacy_progression_evidence`
- `legacy_progression_mappings`

These are inputs/foundation for progression calculation. They are **not** a progression event/history ledger and must not be repurposed as one.

### 1.5 Skill / Technique models — reusable targets

`SkillDefinition` already carries `progressionDomainUids`.

`PlayerSkill` stores `baseMastery` and optional `progressValue` plus explicit `progressSemanticsUid`.

Technique models/stores similarly already provide typed technique state/persistence and should remain the authoritative technique domain rather than being duplicated inside progression.

Phase 20 should calculate proposed progress and map it into existing `SkillChange` / `TechniqueChange`, preserving the current stores as the state owners.

### 1.6 DevelopmentProject — reusable source evidence, not owned by ProgressionEngine

Existing project work already has its own project progress semantics and `DevelopmentProjectChange`. Project progress is a project-domain fact.

Project work may become a **source stimulus** for actor progression, but Phase 20 must not recalculate project lifecycle/progress or turn ProgressionEngine into ProjectEngine.

### 1.7 WorldRuleProvider — reusable legality boundary

Accepted Phase 19 is read-only, deterministic and pins one coherent World Pack authority binding through one resolution. It performs command and final-draft legality checks and must not become the progression magnitude calculator.

Phase 20 should consume the same pinned World Pack identity/version context and preserve a single coherent resolution. World Packs may supply progression policy through a dedicated read-only extension point or typed progression policy adapter; they must not introduce `NarutoProgressionEngine` / `BleachProgressionEngine` as parallel engines.

## 2. What is missing

Required Phase-20 delta not present in current runtime:

1. production `ProgressionEngine` contract and implementation;
2. immutable progression evaluation input/stimulus model;
3. typed progression result/grant model;
4. deterministic progression calculation and versioned arithmetic semantics;
5. explicit conversion boundary for current `Double` Talent/Potential/mastery data into deterministic progression arithmetic;
6. progression-specific ledger semantic payload / entry intent;
7. deterministic progression identity/fingerprint rules;
8. progression factor/provenance records sufficient to explain every durable grant;
9. integration seam in `PlayerDomainEngine` that augments the draft before final WorldRuleProvider effect validation;
10. mapping from approved progression grants into existing `StatChange`, `SkillChange`, `TechniqueChange` targets;
11. progression-specific reference closure/validation for target/domain/evidence refs;
12. Phase-20 tests for determinism, zero-result behavior, causal linkage, no-mutation and World Pack isolation.

Not required for Phase 20:

- persisted unified player ledger storage;
- final TurnTransaction commit;
- full no-retrogression validator;
- passive/time-skip scheduling;
- full diminishing-returns algorithm;
- broad event-store redesign.

## 3. Minimal required ProgressionEngine contract

### REQUIRED IN PHASE 20

Recommended top-level contract:

```text
ProgressionEngine.evaluate(
    input: ProgressionEvaluationInput
) -> ProgressionResult
```

The engine must be deterministic and side-effect free.

It must **not** receive:

- SQLiteDatabase;
- repositories/stores with write capability;
- transaction/commit callbacks;
- mutable PlayerState;
- StatePatch;
- arbitrary AI numeric output.

### Minimal `ProgressionEvaluationInput`

Keep the contract smaller than the target architecture's maximal `ProgressionEntry` list while leaving typed extension points.

Required minimum:

- `campaignUid`
- `characterUid` / subject ref
- `sourceTypeUid`
- stable source/stimulus UID
- source command UID + command kind/fingerprint
- target kind + target UID
- progression domain UID when applicable
- current target value snapshot / progress-semantics identity
- effort/duration/intensity **only when actually available from the resolved source**
- method UID when provided
- typed mechanics evidence for resolved difficulty/quality/outcome when available
- relevant Talent snapshot/evidence
- relevant Potential snapshot/evidence
- relevant fatigue/injury/condition evidence only if consumed by the Phase-20 policy
- pinned World Pack UID/version/binding identity when bound
- progression policy/provider UID/version
- ProgressionEngine version
- immutable dependency/version evidence

Fields such as `novelty`, `adaptation` and `diminishingReturns` should have extension identities/contracts but **not full algorithms** in Phase 20.

### Minimal `ProgressionResult`

Recommended:

```text
ProgressionResult
- grants: List<ProgressionGrant>
- ledgerEntries: List<ProgressionLedgerEntryIntent>
- computationRecords: List<ProgressionComputationRecord>
- resultFingerprint
```

Each `ProgressionGrant` should include:

- deterministic grant UID;
- subject/target identity;
- target kind;
- exact non-negative grant units;
- progress semantics UID/version;
- progression domain/channel/source identity;
- causal stimulus UID;
- source rule/policy UID;
- computation fingerprint.

A result is not a `PlayerChangeSet` and is not authoritative state.

## 4. Minimal ProgressionEntry / Progression Ledger contract

### REQUIRED IN PHASE 20

The Phase-20 ledger object should be a **proposal-level immutable ledger intent**, not authoritative persisted history yet.

Extend the existing `PlayerLedgerIntent` family with a new typed kind, conceptually:

```text
PlayerLedgerIntentKinds.PROGRESSION
ProgressionLedgerIntentPayload(...)
```

Minimal semantic payload should contain:

- stable progression/ledger entry UID;
- character/subject UID;
- target kind + target UID;
- source type/channel;
- source command UID;
- source stimulus/effect/work UID;
- progression domain UID when applicable;
- method/mentor/environment UIDs only when actually known;
- current-level/value snapshot identity;
- relevant input factors used by the computation;
- Talent factor evidence;
- Potential factor evidence;
- base grant;
- final grant;
- ProgressionEngine UID/version;
- progression policy/provider UID/version;
- World Pack UID/version when bound;
- input fingerprint;
- result/computation fingerprint;
- proposed turn/effective-order linkage if available through the existing command/proposal contract.

The payload should support typed extension/factor records rather than hard-code every future Phase-21 property as a mandatory database column.

### Authoritative history vs proposal intent

**Decision: split the layers.**

For Phase 20, `ProgressionEntry` should exist as semantic/proposal intent attached to `PlayerChangeSet`. It must not claim to be committed authoritative history before `TurnTransaction` exists.

Future path:

```text
Phase-20 ProgressionLedgerIntent
-> Phase-22 validation
-> Phase-23 unified ledger/provenance integration
-> Phase-27 TurnTransaction
-> committed append-only progression history
```

This avoids both false authority and a future data migration from a premature standalone progression ledger.

## 5. Proposed integration path in PlayerDomainEngine

### REQUIRED IN PHASE 20

Recommended ordering:

```text
PlayerCommand canonicalization
-> command reference validation
-> pinned Phase-19 WorldRuleProvider COMMAND_PRECHECK
-> existing domain resolution to base PlayerResolutionDraft
-> validate base-draft refs
-> extract immutable progression stimuli from command + resolved draft/mechanics evidence
-> ProgressionEngine.evaluate(...)
-> Core maps validated ProgressionGrant -> existing typed PlayerDomainChange(s)
-> append Progression ledger intent(s)
-> construct new immutable augmented draft
-> validate ALL augmented-draft references
-> Phase-19 DRAFT_EFFECT_CHECK ONCE on the FINAL augmented effect snapshot
-> engine-owned PlayerChangeSet assembly
-> Phase-17 validation
-> later Phase-22 validator
-> later transaction/commit
```

Critical change relative to current code: current `DRAFT_EFFECT_CHECK` runs immediately after validation of the base component draft. Phase-20-generated effects must be inserted **before** that final check so they cannot bypass World Pack legality.

Do not call the same Phase-19 draft effect stage twice unless its contract is intentionally versioned to support separate stages.

### No side-channel mutation

Progression integration is invalid if any of these occur during resolution:

- `ProgressionProfileStore.save*` used to persist a gain;
- `SkillStore` / `TechniqueStore` directly updated by ProgressionEngine;
- stat/resource DB rows directly updated;
- ledger table directly appended;
- event store directly appended;
- AI result written into state without typed deterministic calculation.

## 6. Talent / Potential semantics

Talent and Potential are **inputs to a caused calculation**, never sources of growth by themselves.

Correct:

```text
resolved training/practice/combat/project stimulus
+ current target state
+ Talent/Potential snapshots
+ world progression policy
-> deterministic progression result
```

Forbidden:

```text
high Talent -> spontaneous gain
high Potential -> spontaneous gain
AI says training went well -> arbitrary +N
```

Talent should influence learning efficiency/rate/difficulty response. Potential should influence long-run scaling/growth characteristics. Exact Phase-20 arithmetic must be versioned and deterministic.

Current profile/mastery persistence uses `Double`; proposal deltas use exact integer types. CHAT-1 must define a deterministic canonical conversion/fixed-point boundary. Chained platform-dependent `Double` multiplication is not an acceptable progression ledger basis.

## 7. Core vs World Pack boundary

### Core owns

- `ProgressionEngine` orchestration;
- progression stimulus/result/grant contracts;
- exact arithmetic/fingerprinting/canonicalization;
- generic progression target kinds/channels;
- mapping of approved grants into existing typed change payloads;
- progression ledger intent envelope and causal linkage;
- fail-closed handling of malformed/missing required policy;
- deterministic registry/provider selection rules;
- generic validation of progression inputs/results.

### World Pack owns/provides

- mapping of world definitions to progression domains;
- world-specific rates/weights/formula policy;
- interpretation of world-specific methods, affinities, bloodline/racial/evolution constraints;
- world-specific progression requirements/caps where appropriate;
- world-specific technique/stat/domain definitions.

### Forbidden architecture

Do not create:

- `NarutoProgressionEngine` as an authoritative parallel engine;
- `BleachProgressionEngine` as an authoritative parallel engine;
- a World Pack DB writer;
- a provider that returns arbitrary `PlayerDomainChange` objects;
- a second World Pack authority source.

A read-only, deterministically selected generic progression policy/provider is acceptable. It should be bound to the same pinned World Pack identity/version used by the resolution.

## 8. Commands that should use progression in Phase 20

### Required primary Phase-20 progression sources

- `TRAIN`
- `PRACTICE_SKILL`

These are the clearest current commands whose intent directly expresses deliberate development.

### Integrate only when resolved mechanics provide a real progression stimulus

- `LEARN_SKILL`
- `LEARN_TECHNIQUE`
- `USE_TECHNIQUE`
- `RECORD_PROJECT_WORK`

Do not treat command presence alone as proof of gain. For example, use of a technique may fail or be trivial; project work may have its own resolved work outcome.

### Defer

- passive adaptation / passive time progression;
- time-skip progression;
- broad combat progression hooks if combat mechanics do not yet provide a canonical resolved stimulus;
- evolution progression if currently represented as discrete innate/evolution state rather than a numeric progression target.

## 9. Phase boundaries

### REQUIRED IN PHASE 20

- pure deterministic ProgressionEngine;
- active progression stimuli and grants;
- Talent/Potential consumption as calculation inputs;
- generic World Pack progression policy extension point;
- progression-specific PlayerLedgerIntent payload;
- deterministic IDs/fingerprints;
- PlayerDomainEngine draft integration;
- mapping to existing stat/skill/technique changes;
- explicit zero-result semantics;
- unit/contract/integration tests;
- zero authoritative mutation during resolution.

### DEFER TO PHASE 21

- full diminishing-returns algorithm;
- novelty decay;
- adaptation mechanics;
- passive progression hooks;
- passive/time progression scheduling;
- time-skip progression hook behavior.

Phase 20 may reserve factor-kind identities/extension points so Phase 21 can add these without replacing the top-level contract.

### DEFER TO PHASE 22

- global Player Invariant Validator;
- full no-retrogression enforcement;
- cross-domain invariant validation of proposed final state;
- legal regression rules.

Phase 20 may perform only local structural/semantic validation needed to ensure its own result is well-formed.

### DEFER TO PHASE 23+

Phase 23:

- unified player ledger storage/envelope/provenance integration;
- unified ledger query APIs;
- committed progression-history persistence strategy.

Later phases:

- TurnTransaction/atomic COMMIT;
- Event Store redesign/integration;
- Snapshot System;
- Save/Load;
- Time Skip Processor;
- broad mechanics resolution and AI/GM integration;
- world-specific Phase-83 progression/evolution hardening.

## 10. Existing classes/tables to extend instead of duplicate

Extend/reuse:

- `PlayerDomainEngine` / `PlayerResolutionDraft` pipeline;
- `PlayerChangeSet` and `PlayerLedgerIntent` typed envelope;
- `PlayerChangeKinds.STAT/SKILL/TECHNIQUE` changes;
- `ProgressionDomainDefinition`;
- `TalentProfile` / `PotentialProfile` read data;
- `SkillDefinition.progressionDomainUids`;
- existing `PlayerSkill` / `PlayerTechnique` state;
- existing project work model as source evidence;
- accepted Phase-19 pinned World Pack binding/identity.

Do **not** turn:

- `ProgressionProfileStore` into a progression-history store;
- `legacy_progression_evidence` into runtime progression ledger;
- Financial Ledger into a progression database by overloading financial semantics;
- DevelopmentProject into generic player progression persistence.

## 11. Schema / migration implications

### Minimal Phase-20 recommendation

**DATABASE SCHEMA DELTA: NONE REQUIRED.**

The minimum architecture-correct Phase 20 can define ProgressionEngine + semantic progression ledger intent entirely in runtime/domain contracts and `PlayerChangeSet` proposal data.

This is preferred because authoritative ledger persistence and transaction atomicity are intentionally later phases.

Potential Kotlin contract delta:

- add progression input/result/grant/factor/ledger-payload model(s);
- add `PlayerLedgerIntentKinds.PROGRESSION` and typed payload validation/encoding/fingerprinting where applicable;
- add ProgressionEngine / read-only progression policy registry;
- update `draftReferences` / effect snapshot/reference closure to understand progression ledger refs;
- integrate into `PlayerDomainEngine` final-draft path.

If CHAT-1 determines a DB table is indispensable, that is an architecture escalation and should be reviewed by coordinator because it risks prematurely implementing Phase 23/27 semantics.

## 12. Backward compatibility requirements for CHAT-1

CHAT-1 must preserve:

1. PlayerCommand schema v1 compatibility unless a proven contract delta requires explicit versioning;
2. existing Phase-17 `PlayerChangeSet` semantics and deterministic identity;
3. existing Phase-18 reference validation and component isolation;
4. accepted Phase-19 one-resolution/one-pinned-binding semantics;
5. one final `DRAFT_EFFECT_CHECK` seeing the complete augmented proposal;
6. existing campaign DB without destructive migration;
7. existing Talent/Potential rows and unknown/custom World Pack domains;
8. existing Skill/Technique state and progress semantics;
9. existing project progress semantics;
10. deterministic replay identity for equal semantic input;
11. no direct mutation during `resolve()`;
12. no behavior change for commands that produce no progression stimulus;
13. no mandatory Naruto/Bleach assumptions in Core.

If new PlayerChangeSet serialization/fingerprinting is affected by a new ledger payload, it must remain explicit, canonical and deterministic. No silent reinterpretation of old financial ledger intents.

## 13. Source-of-truth risks

### Risk A — persisted progression ledger before transaction architecture

Would make ledger rows appear authoritative while state change remains only a proposal.

Mitigation: Phase-20 ledger = proposal intent; committed authoritative ledger deferred.

### Risk B — ProgressionProfileStore reused as progression history

Talent/Potential/profile state would become mixed with event/history semantics.

Mitigation: keep Phase-6 store unchanged in responsibility.

### Risk C — WorldRuleProvider used as numeric gain generator

Would mix Phase-19 legality with Phase-20 magnitude and destabilize accepted scope.

Mitigation: separate read-only progression policy adapter/provider.

### Risk D — World Pack provider returns arbitrary changes

Could bypass Core validation and create a second player engine.

Mitigation: provider returns typed computation plans/factors only; Core creates grants/changes.

### Risk E — progression inserted after final Phase-19 check

Would allow progression-generated effects to bypass World Pack legality.

Mitigation: augment draft before the single final `DRAFT_EFFECT_CHECK`.

### Risk F — raw Double arithmetic

Could weaken deterministic replay/fingerprint semantics.

Mitigation: canonical fixed-point/rational conversion and versioned rounding.

## 14. Proposed acceptance matrix

Phase 20 should not be accepted unless at least the following gates pass.

| ID | Gate |
|---|---|
| P20-01 | Same canonical input + versions -> byte/field-equivalent ProgressionResult and same fingerprints. |
| P20-02 | ProgressionEngine has no repository/SQLite/write/commit capability. |
| P20-03 | `TRAIN` produces progression only from a valid resolved stimulus, never arbitrary target values. |
| P20-04 | `PRACTICE_SKILL` targets the canonical Skill UID/domain and produces a typed SkillChange only through engine mapping. |
| P20-05 | Talent/Potential alter a caused calculation but never generate spontaneous gains. |
| P20-06 | Missing/mismatched required World Pack progression policy fails closed. |
| P20-07 | Same Phase-19 pinned World Pack binding is used throughout one progression resolution. |
| P20-08 | Progression-generated refs are revalidated before proposal assembly. |
| P20-09 | Final Phase-19 `DRAFT_EFFECT_CHECK` sees progression-generated effects. |
| P20-10 | WorldRuleProvider is not used as the numeric progression calculator. |
| P20-11 | Progression policy/provider cannot manufacture arbitrary PlayerDomainChange or mutate state. |
| P20-12 | Positive grant maps only to supported existing typed change kinds. |
| P20-13 | Exact zero gain creates no `ExactLongDelta(0)`; optional zero-result audit intent remains valid/deterministic. |
| P20-14 | Every non-zero durable progression change has a causally linked progression ledger intent. |
| P20-15 | Ledger intent causalChangeUids reference the progression-generated change(s) deterministically. |
| P20-16 | No progression-specific authoritative DB write occurs during PlayerDomainEngine resolution. |
| P20-17 | Existing financial ledger intents remain backward compatible and distinguishable from progression intents. |
| P20-18 | Existing Talent/Potential DB rows survive unchanged and remain readable. |
| P20-19 | Existing Skill/Technique progress semantics remain valid; no duplicate store/model authority. |
| P20-20 | Existing DevelopmentProject progress remains owned by project domain; actor progression from project work is additive evidence only. |
| P20-21 | Phase-17/18 canonical contract/regression suites remain green. |
| P20-22 | Phase-19 five canonical regression suites remain green. |
| P20-23 | Unknown/custom World Pack progression domains do not get hard-coded Naruto/Bleach interpretation in Core. |
| P20-24 | No Phase-21 passive/diminishing-return behavior is accidentally enabled. |
| P20-25 | Build/CI is green on exact implementation SHA. |

Additional adversarial gates:

- overflow and extreme-value handling;
- canonical factor ordering independent of collection/hash iteration order;
- malformed factor denominator / non-finite legacy scalar rejection;
- duplicate stimulus/grant/ledger UID rejection;
- cross-campaign target/evidence rejection;
- mutable provider retained-state rejection or equivalent deterministic policy hardening;
- command/draft mutation detection remains intact;
- no progression side effects on Phase-19 rejection paths.

## 15. Exact recommended implementation scope for CHAT-1

CHAT-1 should be authorized only for the following minimal Phase-20 delta:

1. implement generic immutable `ProgressionEvaluationInput`, stimulus/evidence, factor, grant, result and ledger-intent payload contracts;
2. implement pure deterministic `ProgressionEngine` and canonical arithmetic/fingerprinting;
3. implement a read-only, universe-agnostic progression policy/provider extension selected deterministically from the current pinned World Pack binding;
4. add `PROGRESSION` to the existing PlayerLedgerIntent typed family;
5. add Core validation and canonical reference extraction for progression intents/results;
6. integrate progression into `PlayerDomainEngine` between base draft resolution and the single final Phase-19 `DRAFT_EFFECT_CHECK`;
7. map Core-approved progression grants to existing typed `StatChange`, `SkillChange`, `TechniqueChange` only as justified by target semantics;
8. support primary active sources `TRAIN` and `PRACTICE_SKILL`;
9. integrate `LEARN_SKILL`, `LEARN_TECHNIQUE`, `USE_TECHNIQUE`, `RECORD_PROJECT_WORK` only where existing resolved mechanics produce sufficient canonical stimulus/evidence;
10. define deterministic fixed-point/rational conversion for current `Double` profile/mastery inputs;
11. add Phase-20 contract/unit/integration/regression tests, including Phase-17/18/19 preservation;
12. produce implementation evidence tied to exact commit and CI.

## 16. Forbidden scope for CHAT-1

Do not implement or modify as part of Phase 20:

- full Phase-21 diminishing returns;
- passive progression hooks;
- time-skip progression hooks/processor;
- Phase-22 Player Invariant Validator / global No-Retrogression;
- Phase-23 unified committed player ledger storage/query architecture;
- standalone authoritative `progression_ledger` DB table unless coordinator explicitly re-scopes after architecture review;
- TurnTransaction / atomic commit infrastructure;
- Event Store redesign;
- Snapshot System;
- Save/Load;
- crash-recovery / LAST VALID COMMIT;
- Naruto_Default cleanup;
- frontend redesign;
- canonical AI phases;
- Phase-19 hotfixes without new concrete regression evidence;
- `NarutoProgressionEngine` / `BleachProgressionEngine` parallel authoritative engines;
- direct stat/skill/technique/resource persistence from ProgressionEngine;
- AI-authored numeric gains treated as authoritative mechanics.

## 17. Blockers

### Technical blockers discovered

**NONE that prevent beginning a correctly scoped Phase-20 implementation.**

The current foundations are sufficient to implement the minimal contract without a required DB migration.

### Coordination / authorization condition

The roadmap and coordination policy still make global phase acceptance and implementation authorization a coordinator decision. This audit does not itself start Phase 20, mark it COMPLETE, modify the roadmap, or reserve production files.

If the coordinator has not explicitly assigned CHAT-1 a Phase-20 implementation work item after this audit, CHAT-1 must not infer authorization from this report alone.

## Verdict

# READY FOR CHAT-1 IMPLEMENTATION

Meaning: **architecture/contract is sufficiently defined and no technical blocker was found for the minimal Phase-20 delta.**

This is **not** a global Phase-20 acceptance and **not** an authorization to bypass coordinator work assignment.

Phase 20 remains globally NOT STARTED/PARTIAL-at-runtime until an implementation candidate exists and the coordinator accepts it after required independent validation.
