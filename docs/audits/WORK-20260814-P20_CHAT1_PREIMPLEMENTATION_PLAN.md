# WORK-20260814 — Phase 20 CHAT-1 pre-implementation plan

## Status

**PLANNING COMPLETE.**

This is a planning-only artifact for Phase 20 — `ProgressionEngine + Progression Ledger`.

No Phase-20 production code, production tests, migrations, schema changes, `PlayerDomainEngine` integration, runtime commit, or CI candidate was created. Phase 19 is not globally accepted; Phase 20 remains blocked for implementation.

Audit baseline:

- Phase-19 runtime audit target: `48854043bdde9753830ffc20ff6a8e8a4d4299e1`
- Fresh master inspected for this plan: `14784bf6d31f44eb90d168f6b654845a7a147daf`
- `14784bf6...` is the Phase-19 report-only commit immediately above the runtime.
- Phase-20 implementation must begin from a fresh master only after coordinator global Phase-19 acceptance.

## Repository-first findings

Current production surface relevant to Phase 20:

- `PlayerDomainEngine` already performs canonical command validation, Phase-18 command reference validation, Phase-19 command precheck, internal resolution to an immutable `PlayerResolutionDraft`, Phase-18 draft reference validation, Phase-19 draft-effect legality, engine-owned `PlayerChangeSet` assembly, and Phase-17 validation.
- `PlayerResolutionEvidence` already carries deterministic context/entropy/component identity and Phase-19 `WorldRuleDecisionRecord`s.
- `PlayerChangeSet` already has generic `changes`, `eventIntents`, and `ledgerIntents`; the current ledger payload family contains financial transfer intents only.
- `ChangeSetProvenance` already contains `mechanicsVersion` and `worldRuleProviderUid` fields.
- `ExactLongDelta` is the exact non-zero integer delta used by stat/resource/skill/technique/inventory change payloads; `ProjectProgressDelta` has deliberately different zero semantics.
- Existing `ProgressionProfileModel/Store` is **not** a runtime ProgressionEngine. It stores World-Pack-scoped progression domains plus Talent/Potential profiles and legacy mapping evidence.
- `SkillDefinition` already links skills to `progressionDomainUids`. `PlayerSkill` and `PlayerTechnique` retain base mastery plus optional progress value/semantics.
- `DevelopmentProject` already has project work records and exact `progressDeltaUnits`; project lifecycle/work resolution is an existing project-domain concern.
- Phase-9 origin/innate/evolution/form models are discrete state models, not generic numeric XP channels.
- No production `ProgressionEngine` and no `progression_ledger` persistence surface currently exist.

Canonical roadmap boundaries:

- Phase 20: `ProgressionEngine + Progression Ledger`.
- Phase 21: `Diminishing Returns + passive progression hooks`.
- Phase 22: `Player Invariant Validator + No-Retrogression`.
- Phase 23: `Unified Player ledgers + provenance integration`.
- Phase 27: atomic `Turn Transaction` commit/rollback.
- Phase 50: broader GM Mechanics Resolution integration.
- Phase 60: Time Skip Processor.
- Phase 83: world-specific progression/evolution automated hardening.

MASTER requires every durable growth to have a causal progression record, and globally forbids Progression/TimeSkip/etc. from directly mutating authoritative state.

## 1. Proposed Phase-20 contract

Phase 20 should add a pure deterministic **ProgressionEngine** that consumes an immutable, already-structurally/reference-valid progression input and returns a typed **ProgressionResult**. It must not receive repositories, SQLite handles, stores, DAOs, transaction writers, StatePatch, or commit callbacks.

Recommended minimal conceptual API:

```text
ProgressionEngine.evaluate(ProgressionEvaluationInput) -> ProgressionResult
```

`ProgressionEngine` is Core orchestration and exact arithmetic. World-specific numeric progression policy is injected through a separate generic `ProgressionRuleProvider`/registry selected by `WorldPackRuleBinding`; do not overload Phase-19 `WorldRuleProvider`, because Phase 19 answers legality while Phase 20 answers durable-development magnitude/provenance.

The engine/provider must not return `PlayerChangeSet` and must not commit state.

## 2. Exact input model

The recommended `ProgressionEvaluationInput` is immutable and contains only canonical, replayable values:

1. **Identity**
   - `campaignUid`
   - actor/subject typed identity
   - active `WorldPackRuleBinding` when world-specific progression applies
   - canonical `commandUid`, `commandKindUid`, and command fingerprint
   - resolution component kind/version

2. **Source stimulus snapshot** — a narrow typed intermediate, not the whole mutable domain system
   - one or more `ProgressionStimulus` records
   - stable stimulus UID
   - generic channel UID
   - target reference/kind
   - source command/effect/change UID
   - explicit effort/duration/intensity if present
   - method UID if present
   - outcome/work-result/quality/difficulty evidence only when actually resolved upstream
   - evidence/reference UIDs

3. **Current read snapshot**
   - current exact progression target value or canonical fixed-point representation
   - relevant World-Pack progression-domain identity/version
   - Talent/Potential evidence for relevant domains
   - relevant current fatigue/injury/condition evidence when Phase-20 policy actually consumes it
   - immutable dependency/version evidence

4. **Rule provenance linkage**
   - fingerprints/identity of applicable Phase-19 command-side decisions for audit linkage only
   - Phase-19 allow/reject must **not** be used as an arithmetic multiplier

5. **Explicit mechanics evidence when available**
   - narrow typed facts such as resolved difficulty, quality, success grade, intensity, or outcome UID
   - not a generic future `MechanicsResult` dependency

6. **Engine/policy versions**
   - ProgressionEngine version
   - ProgressionRuleProvider UID/version
   - World Pack UID/version

7. **Entropy**
   - Phase-20 base progression should require no hidden randomness.
   - if a future mechanic legitimately makes progression stochastic, the random outcome/seed must arrive as explicit replayable mechanics evidence; ProgressionEngine must never call `Random.Default`, current time, or UUID generation.

### Why not consume PlayerCommand alone?

A raw command expresses intent, not what actually happened. `effortUnits` in `TRAIN` or `PRACTICE_SKILL`, for example, is insufficient to prove success/quality/difficulty or a mechanical outcome.

### Why not consume the full resolved draft directly as the public contract?

The draft contains unrelated inventory/economy/condition/etc. effects. A narrow `ProgressionStimulusSnapshot` prevents ProgressionEngine from becoming a god-object and provides a stable extension point for later mechanics/time-skip sources.

### Does it consume WorldRuleDecision?

Not as a numeric input. Phase-19 decisions gate legality and their stable fingerprints may be linked into provenance. Progression magnitude is independent from ALLOW/REJECT semantics.

### Does it consume a Mechanics result?

Not a generic future Mechanics object in Phase 20. It consumes only the narrow mechanics facts needed by progression, represented as typed stimulus/evidence. Later Mechanics integration can populate those fields without changing the ProgressionEngine top-level contract.

## 3. Proposed pipeline position

Future Phase-20 integration should preserve accepted Phase-18/19 semantics and ensure progression-generated effects do not bypass them.

Recommended canonical ordering:

```text
canonical/structural command validation
-> Phase-18 command reference/scope validation
-> Phase-19 COMMAND_PRECHECK
-> internal resolution / current domain mechanics to base draft
-> Phase-18 base-draft reference validation
-> extract immutable ProgressionStimulusSnapshot
-> ProgressionEngine
-> merge progression grants + progression ledger intents into a new immutable candidate draft
-> Phase-18 FINAL draft reference/scope closure over the augmented draft
-> Phase-19 DRAFT_EFFECT_CHECK ONCE over the final augmented effect snapshot
-> engine-owned PlayerChangeSet construction
-> Phase-17 PlayerChangeSet validation
-> later Phase-22 invariant validation
-> later TurnTransaction
-> COMMIT
```

Rationale:

- ProgressionEngine never sees an obviously unknown/cross-campaign base reference.
- Progression-generated references/effects are still validated by Phase 18.
- Phase-19 draft legality sees the **final** effect set, including progression effects, so progression cannot bypass world legality.
- Phase-19 `DRAFT_EFFECT_CHECK` should remain a single final legality decision rather than two contradictory checks.
- If coordinator later requires a distinct pre-progression draft rule stage, extend the typed Phase-19 stage model intentionally; do not silently execute the same draft check twice.

## 4. Output model

Recommended `ProgressionResult`:

- immutable `grants: List<ProgressionGrant>`
- immutable `ledgerEntries: List<ProgressionLedgerEntryIntent>`
- immutable deterministic computation records/traces
- stable result fingerprint
- no repository/store/transaction handle
- no `PlayerChangeSet`

Each `ProgressionGrant` should contain at minimum:

- stable grant UID derived deterministically from input/stimulus/target/policy identity
- subject/target typed identity
- generic progression target kind UID
- exact non-negative grant units
- progress semantics UID/version
- causal stimulus UID
- source rule/policy UID
- computation fingerprint

The PlayerDomainEngine-side assembler should map supported grants into the existing typed domain changes (`StatChange`, `SkillChange`, `TechniqueChange`, etc.). It must not permit a provider to manufacture arbitrary `PlayerDomainChange` payloads.

For exact-zero results:

- produce an auditable zero-result progression ledger entry when meaningful;
- do **not** manufacture `ExactLongDelta(0)`, because Phase-17 deliberately forbids zero for that type;
- preserve the existing distinct zero semantics of `ProjectProgressDelta`.

## 5. Progression Ledger role

Phase-20 Progression Ledger should initially be a **canonical append intent in the proposal**, not an authoritative DB writer.

Recommended addition to the existing generic ledger-intent family:

```text
PlayerLedgerIntent
  kind = PROGRESSION
  payload = ProgressionLedgerIntentPayload(...)
```

A progression ledger intent records why a durable gain was proposed. It should be causally linked to its generated progression change(s) via `causalChangeUids`.

The ledger payload should contain stable structured identity rather than only human-readable text:

- ledger entry UID
- actor/subject/target identity
- progression channel UID
- source command/effect/stimulus UID
- engine UID/version
- policy/provider UID/version
- World Pack UID/version where applicable
- progression domain UID/version
- method UID/evidence UIDs where applicable
- current-level snapshot identity/value
- base grant
- ordered factor/adjustment evidence
- final grant
- input fingerprint
- computation/result fingerprint

Do not add a `progression_ledger` table in Phase 20. Phase 23 can unify persistence/provenance, and Phase 27 supplies the atomic commit boundary.

## 6. Progression Ledger vs later Unified Player ledgers

**Phase 20 owns:**

- the semantic progression entry schema;
- causal linkage between a proposed durable progression gain and its reason/calculation;
- deterministic identity/fingerprint;
- proposal-level ledger intent generation;
- one-to-one/one-to-many consistency between grants and ledger intents.

**Phase 23 owns:**

- unified ledger envelope/storage integration across progression, finance and other player ledgers;
- unified query/provenance APIs;
- cross-ledger correlation and summaries;
- persisted ledger projection/indexing strategy.

Phase 20 must therefore avoid designing a second standalone persistence authority that Phase 23 would later have to replace.

## 7. Mechanics boundary

Mechanics answers **what happened in the action**. Examples:

- success/failure/outcome grade;
- resource cost/use;
- damage/healing;
- project work outcome;
- difficulty/quality/intensity produced by the resolved situation;
- target interaction/combat consequences.

ProgressionEngine answers **what durable development results from that resolved stimulus**.

Examples:

- how many stat/skill/technique progression units result from legal training/practice;
- what progression-domain factors apply;
- how Talent/Potential affect durable growth;
- how the result is provenance-linked and ledgered.

ProgressionEngine must not calculate combat damage, healing amount, inventory/economy effects, technique resource costs, project lifecycle legality, or other unrelated mechanics.

### Development-project split

Existing `DevelopmentProjectChange.progressDelta` and `ProjectWorkRecord.progressDeltaUnits` remain project-domain/mechanics facts. `PROJECT_WORK` can be a **progression source channel** for actor skill/stat/technique development and the progression ledger can link to the project work result. Phase 20 must not recalculate or take ownership of project lifecycle progress merely because project work can cause player progression.

## 8. WorldRuleProvider boundary

`WorldRuleProvider` remains legality-only:

- “may this actor train/practice/use this method here?” — Phase 19.
- “are the final proposed progression effects legal under this World Pack?” — final Phase-19 draft-effect check.

It must not calculate the amount of progression.

`ProgressionRuleProvider` should be a separate trusted/read-only generic numeric-policy extension point:

- selected deterministically by World Pack UID/version;
- duplicate registration rejected;
- incompatible World Pack version rejected;
- no arbitrary first-provider fallback;
- fail closed when a world-specific progression stimulus requires policy but no matching provider exists;
- no provider required for a stimulus that Core explicitly classifies as having no progression effect.

No Naruto/Bleach-specific concepts belong in Core.

## 9. Deterministic progression calculation

The Phase-20 calculation should use **exact integer/rational arithmetic**, not chained `Double` multiplication.

Recommended algorithm:

1. Canonicalize and sort stimuli by stable stimulus UID/target identity.
2. Provider maps each stimulus to a `ProgressionComputationPlan` containing:
   - target;
   - exact base units;
   - ordered/canonically identified factors;
   - optional cap/floor semantics;
   - explicit rounding mode/version.
3. Represent each multiplier as an exact ratio (`numerator: Long`, `denominator: Long`) or equivalent bounded fixed-point value.
4. Multiply using overflow-safe integer arithmetic (`BigInteger` internally if needed).
5. Apply all factors in a mathematically canonical order or as one combined rational.
6. Apply cap/floor rules.
7. Round **once** at the final boundary using a versioned Core rounding rule.
8. Produce exact grant units plus a canonical computation trace/fingerprint.

Same canonical stimulus + same authoritative snapshot + same World Pack/provider/engine versions + same explicit evidence must produce the same grant and ledger identity.

### Existing Double risk

Current Talent/Potential and skill/technique mastery models use `Double`, while Phase-17 proposed deltas use exact integer types. Phase 20 must define a deterministic conversion boundary before implementation, e.g. a versioned fixed-point `ProgressionScalar` snapshot built from authoritative values. It must reject NaN/infinity and avoid platform-dependent free-form conversion. This is a release blocker if left unspecified in code.

## 10. Diminishing returns without breaking Phase 20

Do **not** put a Phase-21 diminishing-return algorithm into Phase 20.

Phase 20 should make its factor pipeline extensible from day one. A computation plan/ledger entry should carry ordered, typed `ProgressionFactor` records such as:

- factor UID/kind UID;
- source rule/evidence UID;
- provider/version;
- exact numerator/denominator;
- optional bounded reason metadata.

Phase-20 providers can emit base/talent/potential/quality factors as needed. Phase 21 later adds `DIMINISHING_RETURNS`, `NOVELTY`, `ADAPTATION`, passive/time-channel factors, etc. without changing the top-level `ProgressionEvaluationInput`, `ProgressionResult`, grant, or ledger contracts.

The factor kind is generic identity, not a hardcoded universe concept.

## 11. Generic progression channels

Use a stable generic **channel UID** separate from target type and separate from command kind.

Core-defined generic channels may include:

- `ACTIVE_ACTION` — deliberate training/practice/work;
- `EXPERIENCE` — durable growth resulting from successful real use when mechanics supplies evidence;
- `PROJECT_WORK` — actor growth caused by project work;
- `REWARD` — only if/when a canonical reward command/effect exists;
- `PASSIVE_TIME` — reserved contract identity for Phase 21/60, with no hook/scheduler implementation in Phase 20;
- `SYSTEM_EFFECT` — bounded internal/canonical progression source when justified by a typed effect.

Channel UID answers *where the progression came from*. Target kind answers *what grows*. They must not be conflated.

Current actual commands `TRAIN`, `PRACTICE_SKILL`, `LEARN_SKILL`, `LEARN_TECHNIQUE`, `USE_TECHNIQUE`, and project work are mapped only where they provide a real progression stimulus. Do not invent current `AdvanceTime`, `GainReward`, or evolution commands merely because MASTER uses them as conceptual examples.

Innate/evolution transitions remain discrete world-rule/domain transitions unless a future explicit numeric progression target is defined.

## 12. World-agnostic strategy

Core may know generic concepts:

- progression stimulus;
- progression domain;
- target kind;
- channel;
- exact factor;
- grant;
- progression ledger entry;
- provider/policy identity/version;
- immutable evidence/provenance.

World Packs/providers supply:

- which progression domains apply;
- mapping from world definitions/methods to progression plans;
- base rates/scales;
- world-specific method legality remains Phase 19;
- world-specific Talent/Potential interpretation;
- caps/requirements that are genuinely world policy.

Core must not contain Naruto, Bleach, chakra, reiatsu, Sharingan, Kido, Raiton, Sonido, Hollow, Shinigami or world-specific skill/technique names.

Existing `ProgressionDomainDefinition.worldPackUid` and `SkillDefinition.progressionDomainUids` should be reused rather than introducing a competing progression-domain source of truth.

## 13. Provenance / deterministic replay

Every progression result should be reconstructable from stored/committed evidence later.

A progression computation record/ledger intent should link:

- campaign/actor/subject;
- command UID + canonical command fingerprint;
- source component/version;
- source stimulus/effect/change UID;
- applicable Phase-19 decision fingerprint(s) for legality provenance;
- World Pack UID/version;
- ProgressionRuleProvider UID/version;
- ProgressionEngine version;
- exact input snapshot fingerprint;
- target/progression-domain identity;
- ordered exact factors and evidence UIDs;
- base and final grant;
- result fingerprint.

No replay identity may depend on presentation messages, JVM object identity, unordered collection iteration, wall-clock time, or random UUIDs.

Phase-20 records can remain proposal/transient until committed by later transaction infrastructure. Phase 23/27 must preserve these identities rather than recomputing history from current policy versions.

## 14. Zero-authoritative-mutation strategy

Phase 20 must be a pure proposal stage:

- immutable read snapshots in;
- typed grants + ledger intents out;
- PlayerDomainEngine owns conversion/merge into candidate draft/proposal;
- no direct calls to `ProgressionProfileStore.save*`, `SkillStore`, `TechniqueStore`, `DevelopmentProjectStore`, `StatResourceStore`, SQLite, StatePatch, or any repository writer;
- provider has no writable supported capability;
- exceptions/rejection/fault produce no proposal and no state mutation;
- COMMIT remains future TurnTransaction work.

`ProgressionProfileStore` is specifically **not** to be passed into ProgressionEngine; its read values must be snapshotted outside the engine.

## 15. Database / migration expectation

**Expected Phase-20 DB/schema delta: NONE.**

Reasons:

- the calculation can be pure/transient;
- `PlayerChangeSet` already has a generic ledger-intent envelope;
- Phase 23 explicitly owns unified Player ledger/provenance integration;
- Phase 27 owns atomic persistence/commit;
- adding a standalone Phase-20 table now risks a second ledger authority and later migration churn.

A DB migration should be considered a Phase-20 blocker requiring coordinator review, not the default plan.

## 16. Phase-17 / 18 / 19 invariant preservation

### Phase 17

Must preserve:

- `ExactLongDelta` non-zero exact semantics;
- `ProjectProgressDelta` zero semantics;
- ownership/composite identity semantics;
- financial exactness;
- immutable `PlayerChangeSet`;
- canonical serialization/fingerprint behavior;
- proposal-only semantics.

If a progression ledger payload is added to `PlayerLedgerIntentPayload`, `PlayerChangeSetCodec` must gain an additive canonical encoding/decoding branch. Existing no-progression payload fingerprints/roundtrips must remain byte/semantic compatible.

### Phase 18

Must preserve:

- command and draft reference closure;
- UNKNOWN_REFERENCE and WRONG_CAMPAIGN_REFERENCE ownership by Phase 18;
- class-B definition identities such as equipment slot;
- D/A/A/A ownership classification;
- financial reference coverage;
- cross-kind/cross-campaign safety;
- immutable reference snapshots.

Final augmented progression effects must pass Phase-18 closure before proposal construction.

### Phase 19

Must preserve:

- command precheck ordering;
- typed rule decision/fault distinction;
- deterministic provider/world-pack identity;
- final draft-effect legality;
- fail-closed provider absence/version semantics where rules apply;
- read-only provider capability model;
- world-agnostic Core;
- zero authoritative mutation.

Phase-19 final effect fingerprint/canonicalization must be updated for any newly introduced progression ledger intent payload, otherwise Phase 19 cannot deterministically identify the final augmented draft.

## 17. Expected future production files

After Phase 19 global acceptance, the smallest coherent production delta is expected to be:

### New

- `app/src/main/java/com/rpgos/app/ProgressionEngine.kt`
  - pure deterministic orchestration/calculation;
  - exact arithmetic and structural faults.

- `app/src/main/java/com/rpgos/app/ProgressionEngineModel.kt`
  - immutable input/stimulus/snapshot/grant/result/factor/computation identity types.

- `app/src/main/java/com/rpgos/app/ProgressionRuleProvider.kt`
  - generic World-Pack-scoped progression policy provider + deterministic registry.

- `app/src/main/java/com/rpgos/app/ProgressionLedgerModel.kt`
  - semantic progression ledger payload/entry intent types and validation.

### Modified

- `app/src/main/java/com/rpgos/app/PlayerDomainEngine.kt`
  - Phase-20 orchestration only after global Phase-19 acceptance;
  - progression snapshot/stimulus wiring;
  - augmented-draft final Phase-18/19 validation ordering;
  - deterministic resolution evidence/provenance linkage.

- `app/src/main/java/com/rpgos/app/PlayerChangeSetModel.kt`
  - additive progression ledger intent payload/kind only; no authoritative persistence.

- `app/src/main/java/com/rpgos/app/PlayerChangeSetCodec.kt`
  - canonical serialization/fingerprint support for progression ledger intents.

- `app/src/main/java/com/rpgos/app/WorldRuleProvider.kt`
  - only if required to extend canonical final effect snapshot encoding for the new progression ledger payload; no change to legality responsibility.

Potentially no modification is required to existing `ProgressionProfileModel/Store`; they should remain Talent/Potential/domain-definition authority/read source. If a snapshot adapter is needed, prefer a new read-only `ProgressionSnapshotBuilder.kt` rather than adding write capability to the engine.

No migration file is expected.

## 18. Expected Phase-20 test matrix

Minimum proposed matrix:

**P20-01** eligible active training stimulus -> deterministic non-zero grant + matching progression ledger intent.

**P20-02** skill practice stimulus -> typed skill progression grant.

**P20-03** technique-use mechanics evidence can produce technique progression without ProgressionEngine calculating the technique's combat effect.

**P20-04** project work is represented as `PROJECT_WORK` source channel and does not let ProgressionEngine recalculate project lifecycle/work progress.

**P20-05** zero progression result creates no `ExactLongDelta(0)` and preserves a deterministic zero-result audit record where policy requires it.

**P20-06** same canonical input/snapshot/provider/versions -> identical grants, ledger entries and fingerprints.

**P20-07** semantically different stimulus/evidence -> different deterministic result identity.

**P20-08** provider version change participates in computation/result identity.

**P20-09** World Pack version change participates in identity/compatibility.

**P20-10** duplicate progression provider registration rejected deterministically.

**P20-11** missing required progression provider fails closed.

**P20-12** provider/World Pack mismatch/version mismatch fails closed.

**P20-13** normal “no progression” policy outcome is distinct from structural provider/engine fault.

**P20-14** provider fault produces no proposal.

**P20-15** overflow/invalid ratio/zero denominator/non-finite snapshot conversion fails structurally and deterministically.

**P20-16** exact rational factor composition obeys one defined rounding rule.

**P20-17** factor ordering/caller collection ordering cannot change equivalent result fingerprints.

**P20-18** caller-owned input/factor/evidence collection mutation cannot alter a produced request/result.

**P20-19** hidden time/random/UUID is absent from supported progression path.

**P20-20** Phase-18 UNKNOWN_REFERENCE rejects before progression evaluation.

**P20-21** Phase-18 WRONG_CAMPAIGN_REFERENCE rejects before progression evaluation.

**P20-22** progression-generated references undergo final Phase-18 closure before proposal construction.

**P20-23** Phase-19 command rejection prevents progression provider evaluation.

**P20-24** final Phase-19 `DRAFT_EFFECT_CHECK` sees progression-generated changes/ledger intents and can reject them.

**P20-25** progression rejection/fault cannot bypass Phase-19 outcome semantics.

**P20-26** every non-zero progression grant mapped into a domain change has exactly one causally linked progression ledger intent (or an explicitly defined deterministic grouping rule).

**P20-27** no orphan progression ledger intent references an absent generated change.

**P20-28** ledger causal-change identity remains stable under serialization/roundtrip.

**P20-29** progression ledger payload canonical serialization/fingerprint roundtrip.

**P20-30** existing PlayerChangeSet serialization/fingerprints remain unchanged when no progression ledger payload is present.

**P20-31** `ExactLongDelta` zero regression remains PASS.

**P20-32** `ProjectProgressDelta` zero regression remains PASS.

**P20-33** composite conflict identity / `OwnedAssetRef` / finance exact semantics regressions remain PASS.

**P20-34** Phase-18 equipment B and ownership D/A/A/A classifications remain unchanged.

**P20-35** Phase-19 deterministic WorldRuleDecision regressions remain PASS.

**P20-36** progression provider/input exposes no writable DB/store/repository/transaction/StatePatch capability.

**P20-37** zero authoritative mutation on progression success-before-commit, no-progression, rejection and structural fault.

**P20-38** world-agnostic static scan of new/modified Core production files finds no World Pack-specific tokens.

**P20-39** passive progression hook is not activated in Phase 20; reserved channel identity alone cannot schedule/apply passive growth.

**P20-40** full representative Phase 3–19 regression remains PASS.

**P20-41** full `:app:testDebugUnitTest` required for implementation acceptance; no focused-only acceptance.

Additional adversarial tests should cover very large effort/base values, factor cancellation/order, duplicated stimuli, duplicate grant identity, target-kind mismatches, stale provider versions, snapshot mutation attempts, and mismatched causal UIDs.

## 19. Likely release blockers

1. **Phase 19 not globally accepted.** Absolute blocker to implementation.
2. **Exact-scalar boundary unresolved in code.** Existing Talent/Potential/mastery use `Double`; Phase-20 arithmetic must define a versioned deterministic fixed-point/rational conversion before runtime acceptance.
3. **Final reference/rule closure.** Any integration that lets progression-generated effects skip Phase 18 or final Phase-19 draft legality is a blocker.
4. **Provider authority creep.** A progression provider returning `PlayerChangeSet`, arbitrary domain changes, or receiving writable stores is a blocker.
5. **Progression-vs-mechanics confusion.** Recalculating damage, healing, resource costs or project lifecycle progress inside ProgressionEngine is a blocker.
6. **Unledgered durable grants.** Non-zero engine-generated permanent progression without deterministic progression ledger intent is a blocker.
7. **Standalone progression DB authority.** A speculative Phase-20 table/migration before Phase-23/27 architecture requires coordinator justification and should block default implementation.
8. **Codec/fingerprint incompatibility.** New ledger payload must not silently alter old PlayerChangeSet canonical semantics.
9. **Phase-19 effect fingerprint incompleteness.** New progression ledger payload must be included in final canonical WorldRuleEffectSnapshot identity.
10. **Hidden randomness/time/UUID.** Any such dependency without explicit replay evidence is a blocker.
11. **World-specific Core leakage.** Hardcoded universe terms/formulas in Core are a blocker.
12. **Passive/diminishing scope creep.** Phase-21 logic implemented early is a blocker.
13. **Invented command surface.** Do not add conceptual MASTER examples (`AdvanceTime`, `GainReward`, etc.) solely to exercise Phase 20.

## 20. Explicit deferrals

Remain deferred after Phase 20:

- diminishing returns algorithm — Phase 21;
- passive progression scheduling/hooks — Phase 21;
- time-skip processing/passive time advancement — Phase 60;
- Player Invariant Validator / no-retrogression / aggregate caps/conservation — Phase 22;
- unified persisted Player ledgers/provenance queries — Phase 23;
- CharacterPanelSnapshot v2 / PlayerSnapshotBuilder — Phase 24/25;
- TurnTransaction, atomic commit/rollback and idempotency — Phase 27/28;
- event-store/causal-history persistence integration — Phase 30/31;
- broader GM mechanics-resolution integration — Phase 50;
- world simulation / AI/GM integration;
- Naruto/Bleach production progression packs and hardening — Phase 80–84, especially Phase 83;
- new evolution commands or generic numeric evolution model unless separately authorized;
- any DB migration not proven necessary by the fresh post-Phase-19 implementation audit.

## 21. Implementation gate when Phase 19 becomes globally accepted

Before writing Phase-20 code, CHAT-1 should:

1. fetch fresh master;
2. record exact SHA and global Phase-19 acceptance commit;
3. verify `48854043...` or its explicitly accepted successor is an ancestor;
4. re-read any Phase-19 revalidation/hotfix reports created after this planning document;
5. repeat the ProgressionProfile/PlayerDomainEngine/PlayerChangeSet/WorldRuleProvider audit because planning assumptions may have changed;
6. freeze the exact fixed-point/rational scalar semantics;
7. implement only after those checks remain compatible.

## Final planning verdict

**READY**, contingent on coordinator global Phase-19 acceptance and a fresh repository audit at implementation start.

Phase 20 implementation remains **BLOCKED** now.
