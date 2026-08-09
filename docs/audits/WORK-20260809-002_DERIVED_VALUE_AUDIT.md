# WORK-20260809-002 — DerivedValueResolver / Modifier Model Architecture Audit

Status: READ-ONLY ARCHITECTURE AUDIT / PHASE 5 PREPARATION

Work ID: `WORK-20260809-002`
Owner: `CHAT-2`
Role: READ-ONLY ARCHITECTURE AUDITOR
Scope: Phase 5 preparation only; no Phase 4 or Phase 5 runtime implementation
Baseline at start of audit: `ace51fa7cb635a4dcd6801865c300bc8c34f52cf`
Registered work-item baseline: `82b030271e5b7d653da457a2e9b2522e21234457`
Dependency: `WORK-20260809-001` (Phase 4 Dynamic StatDefinition / PlayerStat + ResourceDefinition / PlayerResource)

This report is intentionally architecture-only. It does not modify Kotlin runtime, schema, migrations, `CampaignRepository`, Phase 4 code, MASTER, ROADMAP, or `PARALLEL_WORK_COORDINATION.md`.

---

## 1. Aktualny stan

MASTER defines the target Player State split as:

- PERSISTENT — authoritative durable player state, including base stats and permanent learned/progression state,
- DERIVED — rebuildable values such as effective stats, maximum resources, regeneration and combat-derived values,
- RUNTIME — current transient state such as current HP/resources, fatigue, cooldowns, buffs/debuffs and current injuries/stance.

MASTER also already states the core Phase 5 direction: effective values are calculated from base values plus permanent/equipment/injury/temporary modifiers without destroying base progression.

The current runtime is not yet a Phase 5 implementation.

Confirmed current behavior:

1. `PlayerStateContract.kt`
   - defines `PERSISTENT`, `DERIVED`, `RUNTIME` classification,
   - explicitly says Phase 3 does not impose Phase 4 dynamic stat definitions or Phase 5 derived formulas,
   - classifies legacy fields heuristically by field name,
   - sends `effective_*`, `derived_*`, `max_*`, `regeneration`, `net_worth`, `combat_rating` to DERIVED,
   - sends `current_hp`, `hp`, `current_chakra`, `current_energy`, `current_stamina`, `fatigue`, `cooldown`, `bleeding`, `pain`, `temporary`, `runtime` to RUNTIME,
   - everything else defaults to PERSISTENT.

2. `PlayerStateStore.kt`
   - loads `character_stats` wholesale into `persistent["stats"]`,
   - loads active `injuries_v2` into `runtime["injuries"]`,
   - splits only `character_status_snapshot` fields heuristically into persistent/derived/runtime,
   - does not calculate effective values,
   - does not apply equipment/injury/buff/debuff modifier arithmetic,
   - does not rebuild derived state from typed stat/resource definitions.

3. `CharacterPanel.kt`
   - reads `character_stats(stat_key,current_value)` and displays `current_value` directly as the stat value,
   - derives resource-like presentation only by scanning legacy `character_status_snapshot` column names for `chakra`, `stamina`, or `energy`,
   - reads `character_inventory.item_name` and exposes the entire inventory list as `equipment`, meaning inventory and equipped loadout are not actually separated,
   - uses `chakraCost` as a hardcoded technique presentation concept through `chakra_cost_override` / `base_chakra_cost`.

4. `ContextBuilder.kt`
   - forwards active injuries as raw rows containing severity, pain, bleeding and status,
   - forwards techniques/skills and finances,
   - does not resolve any stat/resource effects from these records,
   - therefore current AI context sees fragments that a future resolver must turn into deterministic mechanics.

5. `LocalGameStore.kt`
   - is orchestration/storage access, not a mechanics resolver,
   - builds context through `PlayerStateStore`,
   - returns CharacterPanel through legacy `CharacterPanelReader`,
   - contains Naruto-specific bootstrap/default asset names (`Naruto_Default.campaign.zip`, `Naruto.worldpack.zip`), but this is packaging/default-world behavior rather than an acceptable location for generic stat formulas.

6. `SourceOfTruthRegistry.kt`
   - protects selected writable tables and explicitly prevents generic StatePatch writes to `campaign_truth_records`,
   - does not provide modifier semantics,
   - is not a substitute for a Player Domain / DerivedValue resolver authority boundary.

7. Prior Phase 0 audit already concluded:
   - `character_stats(stat_key,current_value)` exists,
   - resource-like state exists in `character_status_snapshot`,
   - base/permanent/equipment/injury/temporary modifier separation is not implemented,
   - `DerivedValueResolver` is missing,
   - generic equipment/loadout is missing,
   - ProgressionEngine, PlayerDomainEngine, PlayerChangeSet, WorldRuleProvider and Player Invariant Validator are not yet implemented.

Conclusion: Phase 5 must be introduced as a deterministic projection layer over Phase 4 authoritative definitions/values and authoritative effect sources. It must not reinterpret today’s presentation snapshots as truth.

---

## 2. Mapa istniejących obliczeń

### 2.1 Base values

No explicit generic `baseValue` contract was found in the audited runtime before WORK-001 completes Phase 4.

Closest existing representation:
- `character_stats.current_value` is persisted and displayed directly,
- Phase 3 classifies all rows from `character_stats` as PERSISTENT.

Architectural interpretation for migration: unless WORK-001 establishes a different proven semantic, legacy `character_stats.current_value` must be treated as candidate legacy authoritative stat value to migrate/seed into `PlayerStat.baseValue`, not as a future effective value cache.

This semantic must be finalized by WORK-001 based on exact schema/data migration rules.

### 2.2 Effective values

Current generic effective-value calculation: NOT IMPLEMENTED.

Legacy fields named `effective_*` are classified into DERIVED by `PlayerStatePolicy`, but they are only imported/read as fields. There is no resolver proving how they were produced.

Therefore any legacy `effective_*` value is at most legacy derived/cache evidence, never a new authoritative source.

### 2.3 Current values

Two distinct notions are currently conflated by legacy naming:

- stat rows contain `current_value`, but Phase 3 treats the entire stat collection as PERSISTENT,
- current resource fields (`current_hp`, `current_chakra`, `current_energy`, `current_stamina`) are classified as RUNTIME.

Phase 5 must explicitly separate:

- `PlayerStat.baseValue` — authoritative persistent stat value,
- `ResolvedStat.effectiveValue` — derived,
- `PlayerResource.currentValue` — runtime current quantity,
- `ResolvedResource.maximumValue` — derived maximum,
- optional runtime reservations/temporary capacity changes as explicit runtime/effects rather than overwriting base stats.

### 2.4 Maximum values

Legacy `max_*` fields are classified as DERIVED by `PlayerStatePolicy`.

No generic max-resource resolver exists.

Target: resource maximum is calculated from `ResourceDefinition` formula/rule + stat/resource dependencies + modifiers. If cached/persisted for performance, it remains DERIVED/CACHE and must be rebuildable.

### 2.5 Stat bonuses and penalties

No typed generic modifier model exists in audited runtime.

Bonuses/penalties may currently be encoded indirectly in legacy snapshots, narrative, status fields, injuries, equipment-like inventory or world data, but no deterministic canonical stacking pipeline was found.

Target: bonuses/penalties are explicit modifier instances with source identity, target, operation, magnitude/formula, lifecycle and provenance.

### 2.6 Equipment effects

Generic equipment domain is not implemented.

Current CharacterPanel wrongly maps every `character_inventory` item into `equipment` presentation.

Therefore Phase 5 must not derive equipment modifiers from `character_inventory` membership alone. It must wait for/consume an authoritative equipped/loadout fact. Until Phase 11 exists, Phase 5 can define the source type/extension point but must not invent equipped state.

### 2.7 Injury effects

Active injuries are persisted/read from `injuries_v2` and classified under RUNTIME in `PlayerStateStore`.

No deterministic injury -> modifier translation exists.

Target: injury records remain authoritative/runtime facts; a World Pack or generic injury-rule adapter emits derived modifier instances from them. Severity/pain/bleeding must not directly mutate `PlayerStat.baseValue`.

### 2.8 Buffs, debuffs and temporary effects

`PlayerStatePolicy` recognizes names containing `temporary` or `runtime`, but no typed buff/debuff/effect collection and no stacking resolver were found in current audited runtime.

Target: transient effect instances are RUNTIME authoritative facts about active effects, while their numeric consequences are DERIVED modifier contributions.

Important distinction:
- authoritative: “effect X is active from T1 until T2 / N turns, source S, stacks N”,
- derived: “effect X currently contributes +12 speed”.

### 2.9 Regeneration

Legacy field names containing `regeneration` are classified DERIVED.

No regeneration computation/tick engine was found.

Target:
- regeneration rate is DERIVED,
- applying regeneration to a current runtime resource is a state transition handled by the later legal mutation path/time/combat processor,
- the resolver must never mutate current HP/chakra/energy itself.

### 2.10 Combat-derived values

Legacy `combat_rating` is classified DERIVED.

No generic combat-rating formula or typed combat-derived value engine was found.

Target: combat-derived values are named derived definitions that may depend on resolved stats/resources and World Pack formulas. They are read projections, not base stats.

### 2.11 Resource maximums

No generic implementation exists today. Resource-like legacy values are discovered by column-name matching in `character_status_snapshot`, which is presentation/migration logic, not architecture.

Phase 4 must create the authoritative `ResourceDefinition` + `PlayerResource` basis before Phase 5 resolves maxima.

---

## 3. Problemy architektoniczne

### 3.1 `character_stats.current_value` is semantically dangerous

The name `current_value` suggests an effective/current number, but the Phase 3 store treats it as persistent. If Phase 5 adds `effectiveValue` while old code keeps treating `current_value` as final displayed truth, the system will have two competing stat values.

Required migration rule: establish exactly one persistent base/stat authority in Phase 4 and route all future display/context through resolver output.

### 3.2 Legacy derived fields can become accidental second truth

`character_status_snapshot.max_*`, `effective_*`, `regeneration`, `combat_rating` are currently loaded into the DERIVED bucket. If future code writes resolver outputs back into those fields and later reads them as inputs, circular authority/cumulative bonus bugs will occur.

Rule: derived output must never be fed back as base input unless through a separate explicit authoritative domain command that changes the base fact for a real gameplay reason.

### 3.3 CharacterPanel bypasses future resolver

CharacterPanel currently reads `character_stats.current_value` directly. It must later consume a PlayerSnapshotBuilder / resolved projection, not direct SQL.

### 3.4 Equipment source is invalid today

Inventory membership != equipped state. Any Phase 5 implementation that treats all inventory as equipment would grant phantom modifiers.

### 3.5 Injury data exists but effect semantics do not

`injuries_v2` provides conditions but not a canonical generic numeric effect contract. Hardcoding severity -> stat penalty in Core would leak world-specific mechanics into Core.

### 3.6 Buff/debuff lifecycle is not modeled

Without stable effect UID, source UID, duration/expiry, stack identity and application count, replay/idempotency and deterministic rebuild are impossible.

### 3.7 Name-based legacy classification is transitional only

`PlayerStatePolicy.classifyLegacyField` is useful for Phase 3 compatibility but is not adequate as the Phase 5 type system. `max_*` or `current_*` naming conventions must not decide domain semantics in new models.

### 3.8 World-specific naming is present

Confirmed examples:
- `chakraCost` / `chakra_cost_override` / `base_chakra_cost` in CharacterPanel technique presentation,
- `current_chakra` legacy runtime classification,
- `chakra` substring used to classify resource-like legacy status,
- Naruto default package names in `LocalGameStore`.

These existing compatibility paths may remain temporarily, but the Phase 5 resolver API must use generic definition UIDs and rule hooks. Bleach/Naruto mechanics belong in World Pack definitions/providers, not resolver Core branching.

### 3.9 Derived State has no rebuild contract today

Crash recovery architecture requires derived/cache deletion and deterministic rebuild from authoritative state. Phase 5 must define this explicitly from day one.

### 3.10 Formula cycles are a future risk

Dynamic definitions allow dependencies such as maxHP <- vitality, staminaRegen <- endurance, combatPower <- several effective stats. World Packs could accidentally create cycles. Resolver must detect/reject cycles or define tightly constrained iteration semantics. Recommended default: cycles are invalid.

---

## 4. Authoritative vs Derived vs Runtime

### AUTHORITATIVE / PERSISTENT

Must include after Phase 4:
- `StatDefinition` identity/metadata/rule binding supplied by Core/World Pack definition source,
- player stat base/progression value (`PlayerStat.baseValue` or WORK-001 equivalent),
- `ResourceDefinition`,
- durable player resource identity/config if applicable,
- permanent traits/progression facts that produce modifiers,
- equipped-state fact once Equipment domain exists,
- injury/effect source facts where persistence/lifecycle requires them,
- source/provenance and stable UIDs for authoritative modifier-producing facts.

Important: a permanent modifier may itself be authoritative if it represents a durable acquired condition/trait, but its contribution to effective value is still derived.

### DERIVED

- effective stat value,
- resource maximum,
- regeneration rate,
- combat-derived values,
- per-source modifier contribution after rule expansion,
- clamped final values,
- resolved dependency graph results,
- optional resolver snapshot/cache.

All must be rebuildable.

### RUNTIME

- current resource quantity (HP/chakra/energy/stamina equivalent),
- active buff/debuff/effect instances,
- cooldowns,
- fatigue,
- stance,
- active injuries/bleeding/pain state as currently modeled,
- combat session state.

Runtime facts can be authoritative for “what is currently active/current quantity,” but the numeric values calculated from them remain derived.

### CACHE / PRESENTATION

- CharacterPanelSnapshot,
- ContextBundle projections,
- any materialized effective-value cache,
- formatted stat/resource strings,
- legacy `character_status_snapshot` fields that are retained only for compatibility once canonical Phase 4/5 data exists.

### Double-source-of-truth candidates

Highest-risk fields/paths:
1. `character_stats.current_value` vs future `PlayerStat.baseValue` / resolved effective value,
2. `character_status_snapshot.effective_*` vs resolver output,
3. `character_status_snapshot.max_*` vs ResourceDefinition resolver output,
4. legacy resource current values vs Phase 4 `PlayerResource.currentValue`,
5. CharacterPanel direct reads vs future PlayerSnapshotBuilder,
6. inventory list used as equipment vs later Equipment domain,
7. any persisted derived cache that can be mutated independently of its authoritative inputs.

---

## 5. Proponowany kontrakt DerivedValueResolver

Recommended Core interface (conceptual; not an implementation in this work item):

```text
DerivedValueResolver.resolve(request: DerivedResolutionRequest): DerivedResolutionResult
```

### Inputs

`DerivedResolutionRequest` should contain immutable snapshots/references for one resolution epoch:

- `campaignId`
- `playerUid`
- `worldPackId` / world rule provider version
- `resolutionContext`
  - campaign time / turn / transaction boundary
  - optional combat context / environment context
- Phase 4 definitions:
  - `StatDefinition` set
  - `ResourceDefinition` set
- Phase 4 authoritative player values:
  - `PlayerStat` base values
  - `PlayerResource.currentValue` only when current amount is needed for contextual formulas; current amount is not overwritten by resolver
- authoritative modifier sources:
  - permanent modifier facts/traits
  - equipment facts
  - injury facts
  - active temporary effect facts
- World Pack rule adapter/provider
- optional requested output keys/profile to avoid calculating the entire graph when unnecessary.

Inputs MUST NOT include previously resolved effective values as base inputs.

### Outputs

`DerivedResolutionResult` should provide:

- `resolvedStats: Map<StatUid, ResolvedStat>`
  - `baseValue`
  - `effectiveValue`
  - `contributions[]`
  - `clamp/min/max information`
- `resolvedResources: Map<ResourceUid, ResolvedResource>`
  - `currentValue` as observed runtime input/reference
  - `maximumValue`
  - `regenerationRate` if defined
  - `currentValueForPresentation = clamp(current, legal runtime bounds)` only as projection; no state mutation
  - contributions
- `derivedValues: Map<DerivedKey, ResolvedDerivedValue>` for combat rating/secondary values
- `diagnostics`
  - inactive/invalid modifiers
  - missing dependencies
  - cycle errors
  - clamping events
  - unknown definitions
  - rule/provider version
- deterministic `inputFingerprint` / `resolutionVersion` suitable for cache invalidation/debugging.

### Purity requirement

The resolver must be a pure/read-only calculation with no DB writes and no mutation of authoritative/runtime state.

Given the same authoritative inputs + definitions + rule-provider version + explicit context, it must return the same result.

### Dependency graph

Definitions can declare dependencies by stable UID/key.

Recommended evaluation:
1. validate referenced definitions and modifier targets,
2. expand authoritative source facts into modifier instances,
3. build dependency DAG,
4. detect cycles,
5. topologically resolve primitive stats,
6. resolve resource max/regen,
7. resolve secondary/combat-derived values,
8. apply final bounds/rounding policy,
9. emit diagnostics and contribution trace.

No hidden global mutable state.

---

## 6. Proponowany Modifier model

A generic modifier instance should represent one effect contribution without knowing Naruto/Bleach concepts in Core.

Recommended conceptual fields:

```text
Modifier {
  modifierUid: StableUid
  sourceType: ModifierSourceType
  sourceUid: StableUid
  targetKind: STAT | RESOURCE_MAX | RESOURCE_REGEN | DERIVED_VALUE
  targetUid: StableUid
  operation: ADD_FLAT | ADD_PERCENT | MULTIPLY | OVERRIDE | MIN_CAP | MAX_CAP
  magnitude: Decimal?                 // for simple rules
  formulaUid/formulaArgs: optional    // world/generic rule reference
  stackGroup: String?
  stackKey: String?
  stackingPolicy: StackingPolicy
  priority: Int
  lifecycle: PERMANENT | EQUIPMENT | INJURY | TEMPORARY
  validFrom/validUntil or turn bounds where applicable
  conditionUid / predicate rule optional
  provenance
}
```

### Why source identity is mandatory

Without stable `sourceUid` the system cannot:
- remove one equipment effect without removing unrelated bonuses,
- expire exactly one buff,
- rebuild after restore,
- prevent duplicate application,
- explain why effective value changed,
- audit stacking.

### Modifier fact vs modifier projection

Prefer storing authoritative domain facts and deriving modifiers from them where practical.

Examples:
- equipped item fact -> equipment rule emits modifier(s),
- injury fact -> injury rule emits modifier(s),
- active buff fact -> effect definition emits modifier(s).

A stored permanent modifier record is acceptable only when the modifier itself is the durable domain fact (for example a generic permanent blessing/trait), with provenance and stable source identity.

### Operation semantics

Recommended general operation set:
- `ADD_FLAT`: `x + n`
- `ADD_PERCENT`: percentage of the phase base/accumulator according to explicit stage semantics
- `MULTIPLY`: multiplicative scale
- `OVERRIDE`: controlled replacement with strict precedence and usually exclusive group
- `MIN_CAP`: lower bound
- `MAX_CAP`: upper bound

Avoid arbitrary executable code/scripts in modifier rows. World-specific complex calculations should use registered rule/formula IDs handled by `WorldRuleProvider` or a safe formula contract.

---

## 7. Stacking / precedence rules

A deterministic order is required. Recommended pipeline:

```text
BASE AUTHORITATIVE VALUE
  -> PERMANENT
  -> EQUIPMENT
  -> INJURY
  -> TEMPORARY
  -> FINAL CAPS / DEFINITION BOUNDS
  = EFFECTIVE VALUE
```

Within each lifecycle stage:

1. validate active predicate/lifecycle,
2. group by `stackGroup`,
3. apply group `stackingPolicy`,
4. sort surviving modifiers by explicit operation stage and stable tie-breaker,
5. apply flat additions,
6. apply additive percentages,
7. apply multiplicative modifiers,
8. apply controlled override if allowed,
9. apply caps.

Recommended stacking policies:

- `STACK_ALL`: all instances apply,
- `UNIQUE_SOURCE`: one contribution per source UID; duplicate source instances are idempotent,
- `HIGHEST_ONLY`: highest magnitude in group,
- `LOWEST_ONLY`: strongest penalty in group,
- `MAX_STACKS(n)`: deterministic first/highest N according to rule,
- `REPLACE_SAME_STACK_KEY`: newest/authoritative active instance replaces previous instance of same key,
- `EXCLUSIVE`: exactly one active modifier accepted; conflict is validation error or provider-defined winner.

Rules that must be fixed globally:

- stable ordering cannot depend on DB row order,
- duplicate delivery of the same modifier UID must not double-apply,
- positive/negative percent arithmetic must have explicit semantics,
- rounding happens only at definition-declared boundary/final value, not after every modifier unless definition explicitly says so,
- integer display does not imply integer internal arithmetic,
- `OVERRIDE` is exceptional and requires priority + source trace; it must not silently erase later phases.

Permanent vs equipment vs injury vs temporary:

- PERMANENT: durable acquired conditions that alter effective values without rewriting progression base,
- EQUIPMENT: active only while authoritative equipped state is active,
- INJURY: active while injury condition says so; generally penalties but model permits provider-defined behavior,
- TEMPORARY: active effects with duration/expiry/condition; includes buffs/debuffs.

No lifecycle stage is allowed to modify the stored base stat as a side effect.

---

## 8. World Pack extension points

Core must not contain `if Naruto`, `if Bleach`, `chakra`, `reiatsu`, clan/race-specific formula branches.

Required extension points:

### 8.1 Definition registration

World Pack supplies additional:
- `StatDefinition`s,
- `ResourceDefinition`s,
- derived/secondary definitions if supported,
- display metadata separately from mechanical stable UID.

Examples belong in World Packs, not Core:
- Naruto chakra resource/max/regen dependencies,
- Bleach reiatsu/reiryoku-related resources/stat mechanics,
- world-specific bloodline/evolution effects.

### 8.2 Modifier source adapters

World Pack can map domain facts into generic modifiers:

```text
WorldRuleProvider.modifiersForTrait(...)
WorldRuleProvider.modifiersForEquipment(...)
WorldRuleProvider.modifiersForInjury(...)
WorldRuleProvider.modifiersForTemporaryEffect(...)
```

Exact API can be consolidated, e.g. `deriveModifiers(sourceFact, context)`.

### 8.3 Formula registry

Complex values use stable `formulaUid` or rule ID.

Core owns safe orchestration/dependency resolution; World Pack owns world-specific formula semantics.

### 8.4 Validation hooks

Provider validates:
- legal modifier target for world definitions,
- world-specific caps,
- stacking groups/policies when not purely generic,
- race/form/evolution conditional availability,
- incompatible effects.

### 8.5 Versioning

Derived output depends on World Pack rules. Resolver result/cache must include definition/rule version/fingerprint so a World Pack update invalidates derived cache and triggers rebuild rather than silently preserving stale effective values.

---

## 9. Pliki wymagające późniejszej zmiany

This audit does not modify them. Expected Phase 5 integration candidates after WORK-001 is complete:

### Core/Player State
- `app/src/main/java/com/rpgos/app/PlayerStateContract.kt`
  - replace legacy name heuristics as primary Phase 5 source with typed resolved structures; keep legacy adapter only where migration compatibility requires it.
- `app/src/main/java/com/rpgos/app/PlayerStateStore.kt`
  - load canonical Phase 4 base/resource records and authoritative effect sources; compose with resolver or a snapshot builder rather than treating all `character_stats` rows as final values.

### Read models / context
- `app/src/main/java/com/rpgos/app/CharacterPanel.kt`
  - stop direct stat SQL/final-value assumptions; consume resolver/PlayerSnapshotBuilder projection,
  - later split inventory/equipment correctly.
- `app/src/main/java/com/rpgos/app/ContextBuilder.kt`
  - expose typed resolved values/contribution summaries instead of making AI infer mechanics from raw fragments.
- `app/src/main/java/com/rpgos/app/LocalGameStore.kt`
  - wire read-only derived resolution at repository/service boundary if this remains the composition root; no formula logic here.

### Later domain sources
- Equipment subsystem files created in Phase 11,
- injury/effect adapters,
- PlayerDomainEngine/PlayerChangeSet in Phases 16–18,
- WorldRuleProvider in Phase 19,
- Progression/Invariant components in Phases 20–22,
- PlayerSnapshotBuilder in Phase 25.

### Tests
- Phase 5 resolver unit tests,
- Phase 4/5 repository integration tests,
- migration compatibility tests coordinated with WORK-003,
- World Pack integration tests later in Phase H.

No schema file is prescribed by this audit because the exact Phase 4 storage contract is owned by WORK-001 and must be audited before Phase 5 persistence choices are made.

---

## 10. Ryzyka migracyjne

### R1 — Treating legacy `current_value` as effective and then adding modifiers again

Impact: double bonus/penalty and stat inflation.

Mitigation: WORK-001 must explicitly declare migration semantics for `character_stats.current_value`; Phase 5 consumes only canonical base values.

### R2 — Copying legacy `max_*` into authoritative resource maximum

Impact: stale max becomes permanent truth and stops reacting to stat/modifier changes.

Mitigation: migrate max values only as compatibility evidence/cache if needed; canonical max is resolver output.

### R3 — Duplicate current resources

Legacy `character_status_snapshot.current_*` plus Phase 4 PlayerResource can diverge.

Mitigation: one authoritative current resource store after migration; legacy snapshot becomes read compatibility or is rebuilt.

### R4 — Applying inventory effects as equipment

Impact: all carried items grant bonuses.

Mitigation: no equipment modifier adapter until authoritative equipped state exists.

### R5 — Hardcoded Naruto formulas in Core

Impact: Bleach/custom World Pack becomes impossible without branching/refactor.

Mitigation: stable generic UIDs + WorldRuleProvider/formula registry.

### R6 — Modifier duplication after retries/restore

Impact: permanent stat inflation.

Mitigation: stable modifier/source UID, `UNIQUE_SOURCE`/idempotency, rebuild from authoritative sources.

### R7 — Derived cache surviving rule updates

Impact: stale gameplay mechanics after World Pack update.

Mitigation: input/rule fingerprint and forced cache invalidation/rebuild.

### R8 — Formula dependency cycles

Impact: non-determinism/infinite resolution.

Mitigation: DAG validation and fail-fast cycle diagnostics.

### R9 — Runtime resource above newly reduced maximum

Example: injury lowers max HP below current HP.

Mitigation: resolver returns max and projected legal presentation value; actual authoritative current resource correction must occur through a legal domain change policy. The resolver must not silently mutate current state. Define whether over-cap runtime values may temporarily exist or require a domain clamp command/event.

### R10 — Precision/rounding drift

Mitigation: canonical numeric representation/rounding policy declared by definitions and deterministic tests.

---

## 11. Invarianty

Required Phase 5 invariants:

1. **Derived is not authoritative.** Deleting all derived/cache rows/snapshots must not lose campaign information.
2. **Base immutability under resolution.** Resolver execution never changes `PlayerStat.baseValue` or any authoritative source fact.
3. **Current-resource non-mutation.** Resolver never spends/heals/regenerates current resource; it only calculates max/rate/projection.
4. **Determinism.** Same input snapshot + rules/version + context => byte/semantically identical resolution.
5. **Stable identity.** Every modifier source and target uses stable UID/key, never display name identity.
6. **No duplicate application.** Same modifier UID/source cannot apply twice due to retry/read duplication.
7. **No hidden DB-order precedence.** Results are independent of row retrieval order.
8. **No world-name branching in Core.** World-specific mechanics arrive through definitions/provider hooks.
9. **No unresolved target.** Unknown target UID is explicit error/diagnostic according to policy, never silently creates a stat.
10. **Dependency graph valid.** Cycles are rejected unless a future explicit solver contract is introduced.
11. **Definition bounds respected.** Final effective values obey declared min/max/type rules.
12. **Contribution trace complete.** Every delta from base to effective can be attributed to a modifier/source/rule.
13. **Permanent progression not erased by temporary penalty.** Injury/debuff changes effective value, not base progression.
14. **Equipment requires equipped fact.** Inventory possession alone never activates equipment modifiers.
15. **Expired temporary effects do not contribute.** Lifecycle evaluation uses explicit campaign time/turn context.
16. **Derived cache invalidation.** Any authoritative dependency/rule version change invalidates affected result.
17. **Cross-campaign isolation.** No modifier/source from another campaign/player can enter the request.
18. **Legacy compatibility cannot outrank canonical Phase 4 data.** Once migrated, legacy snapshot data is not a competing input.
19. **Explainability.** Resolver can produce `base + contributions -> effective` trace for diagnostics/replay.
20. **No NaN/infinite invalid numeric values.** Numeric domain validation rejects invalid formula output.

---

## 12. Testy

### 12.1 Pure resolver tests

- base only -> effective == base,
- one flat permanent modifier,
- positive and negative flat modifiers,
- additive percentage semantics,
- multiplicative semantics,
- mixed operation order,
- final min/max caps,
- override precedence,
- deterministic tie ordering,
- duplicate modifier UID does not double apply,
- each stacking policy,
- inactive/expired temporary effect ignored,
- injury modifier applies only while injury active,
- unknown target failure/diagnostic,
- cycle detection,
- precision/rounding policy,
- negative values where definitions allow/disallow them,
- huge values/overflow protection.

### 12.2 Resource tests

- max resource from one stat dependency,
- max resource from multiple resolved dependencies,
- current resource remains unchanged after resolve,
- current > newly reduced max follows defined projection/domain policy without resolver mutation,
- regeneration rate derived correctly,
- max/regen rebuild after modifier changes.

### 12.3 Source/lifecycle tests

- permanent + equipment + injury + temporary pipeline order,
- unequipping removes only equipment contribution,
- healing removes only injury contribution,
- temporary expiry removes only temporary contribution,
- two items in same stack group obey configured policy,
- duplicate retry/source fact remains idempotent.

### 12.4 Rebuild tests

- delete derived snapshot/cache -> rebuild equals prior result,
- restore authoritative state -> rebuild equals snapshot-time result,
- World Pack rule version change invalidates derived cache,
- changing one base stat invalidates dependent resource/derived values,
- unrelated stat change does not alter unrelated resolved values.

### 12.5 Migration compatibility tests

Coordinate with WORK-003:
- old campaign with `character_stats.current_value` migrates exactly once into Phase 4 base semantics,
- legacy `effective_*` is not imported as base,
- legacy `max_*` is not made authoritative when formula exists,
- legacy current resource maps to one PlayerResource current source,
- unknown/custom stat/resource keys preserve data and stable identity,
- mixed old/new campaign does not double count,
- migration retry does not duplicate modifier/source records.

### 12.6 World Pack tests

- Naruto definition/provider pack resolves chakra-related values without Core chakra branches,
- Bleach pack resolves its own resource/stat mechanics through the same generic API,
- custom test World Pack defines a new stat/resource/modifier formula without Core code change,
- same generic stacking semantics work across packs,
- invalid pack dependency cycle is rejected with useful diagnostic.

### 12.7 Integration/read-model tests

- CharacterPanel effective stat equals resolver result,
- ContextBundle effective stat/resource equals same resolver result,
- PlayerStateSnapshot persistent base differs from derived effective when modifier exists but both are correctly classified,
- no read path displays legacy `character_stats.current_value` as final effective value after Phase 5 switchover,
- deleting CharacterPanelSnapshot does not lose data,
- deleting derived cache does not lose data.

---

## 13. Kolejność implementacji Fazy 5

Do not start until the required Phase 4 contract is accepted/integrated.

Recommended sequence:

1. **Freeze Phase 4 contracts**
   - exact `StatDefinition`, `PlayerStat`, `ResourceDefinition`, `PlayerResource` semantics,
   - stable UID rules,
   - base vs current resource ownership,
   - migration behavior for legacy fields.

2. **Define Phase 5 value types only**
   - `Modifier`, operation enums, lifecycle/source/stacking types,
   - `ResolvedStat`, `ResolvedResource`, contribution trace,
   - no DB writer side effects.

3. **Implement pure modifier stacking engine**
   - deterministic operations and stacking policies,
   - strong unit tests.

4. **Implement dependency-graph resolver**
   - stat -> resource max/regen -> secondary/derived values,
   - cycle and missing-dependency validation.

5. **Add authoritative source adapters**
   - permanent source facts first,
   - injury facts where semantics are defined,
   - temporary effect source once typed lifecycle source exists,
   - equipment adapter only when reliable equipped state exists; do not misuse inventory.

6. **Add World Pack extension/provider contract needed by resolver**
   - if full Phase 19 WorldRuleProvider remains later, introduce only the narrow Phase-5-compatible rule/formula interface approved by roadmap dependency policy; otherwise use data-driven definitions until Phase 19.
   - do not pull broad Phase 19 scope forward unintentionally.

7. **Integrate PlayerState read projection**
   - persistent base + runtime facts + derived resolver result remain clearly separated.

8. **Integrate CharacterPanel/Context read models**
   - single resolved output path,
   - remove direct-final-value assumptions.

9. **Add cache only after correctness**
   - resolver should work without cache first,
   - cache keyed by authoritative input/rule fingerprint,
   - deletion/rebuild tests mandatory.

10. **Migration hardening and old campaign integration tests**
    - consume WORK-003 scenarios and Phase 4 actual migration design.

11. **Coordinator integration audit**
    - confirm no second source of truth,
    - confirm build/CI and old campaign continuity,
    - only coordinator updates global roadmap status.

---

## 14. Elementy Fazy 4 wymagane przed implementacją Fazy 5

Phase 5 implementation is BLOCKED until WORK-001 delivers and coordinator accepts at least the following:

1. Stable generic `StatDefinition` identity and lookup contract.
2. Stable generic `PlayerStat` record tied to player/campaign and definition UID.
3. Explicit authoritative semantics of the player stat stored number: it must be clear which field is BASE/progression value.
4. Generic `ResourceDefinition` identity and lookup contract.
5. Generic `PlayerResource` record with explicit current runtime quantity semantics.
6. Clear rule for resource definition parameters/dependencies needed to derive maximum/regeneration.
7. Migration mapping from legacy `character_stats(stat_key,current_value)`.
8. Migration mapping from resource-like legacy `character_status_snapshot` fields.
9. No dual-write contract that leaves legacy and new stat/resource rows independently authoritative.
10. Repository/read access needed to load all Phase 4 definitions and player values deterministically.
11. Stable UID collision/unknown-definition behavior.
12. Tests proving old campaign stat/resource values survive Phase 4 migration without duplication or silent loss.

Items NOT required to finish Phase 4 before a minimal pure Phase 5 resolver can be developed, but required before their source categories are fully integrated:
- full Equipment domain (Phase 11),
- full PlayerDomainEngine (Phase 18),
- full WorldRuleProvider (Phase 19),
- full progression/invariant pipeline (20–22),
- CharacterPanelSnapshot v2 (24–25).

Therefore Phase 5 should be structured so source adapters can be added incrementally without changing resolver arithmetic/authority semantics.

---

# Coordinator handoff

## Work ID

`WORK-20260809-002`

## baselineCommit

`ace51fa7cb635a4dcd6801865c300bc8c34f52cf`

The work register originally recorded `82b030271e5b7d653da457a2e9b2522e21234457`, but before the audit started master had advanced to `ace51fa7cb635a4dcd6801865c300bc8c34f52cf` with the work-item registration commit. No WORK-001 implementation commit or branch named for WORK-001 was found during this audit window; the coordination file still showed WORK-001 as READY.

## Najważniejsze findings

1. No current generic DerivedValueResolver exists.
2. `character_stats.current_value` is persisted/displayed directly yet classified as PERSISTENT, making its Phase 4 migration semantics the most important Phase 5 dependency.
3. Legacy `effective_*`, `max_*`, `regeneration`, `combat_rating` are classified DERIVED only by field-name heuristic; they are not proven authoritative mechanics.
4. Current resource values and stat current values have different semantics and must not be conflated.
5. Injuries exist as runtime facts but have no deterministic modifier translation.
6. Generic equipment state is not available; inventory is currently mislabeled as equipment in CharacterPanel.
7. Buff/debuff/temporary effect lifecycle has no typed generic model.
8. CharacterPanel and ContextBuilder bypass a resolver today.
9. World-specific `chakra` naming exists in legacy/presentation paths; new resolver Core must be generic and World Pack driven.
10. Derived state must be disposable/rebuildable and never become a second authoritative stat/resource store.

## Proponowany kontrakt

Pure deterministic resolver:

```text
Phase 4 authoritative definitions + base PlayerStat + current PlayerResource
+ authoritative modifier-producing facts
+ explicit context + World Pack rules
-> DerivedValueResolver
-> ResolvedStat(effective + trace)
   ResolvedResource(max + regen + trace)
   secondary/combat-derived values
```

No DB mutation. No writing effective value back as base. Stable modifier source UID. Deterministic stacking: BASE -> PERMANENT -> EQUIPMENT -> INJURY -> TEMPORARY -> bounds.

## Blokery

Primary blocker: WORK-001 Phase 4 contract/result is not yet available in the audited master state.

Secondary blockers for full category coverage:
- no authoritative generic equipment/loadout domain yet,
- no typed generic temporary effect lifecycle yet,
- broad WorldRuleProvider is scheduled later in roadmap, so Phase 5 must avoid pulling the entire Phase 19 scope forward.

## Zależności od WORK-20260809-001

Must consume WORK-001’s final:
- stat/resource stable UID model,
- exact base/current semantics,
- definition lookup/dependency representation,
- legacy migration mapping,
- repository read contract,
- old-campaign compatibility tests.

If WORK-001 chooses semantics incompatible with assumptions in this audit, Phase 5 design must adapt to the accepted Phase 4 contract rather than create an alternate store.

## Rekomendowana kolejność implementacji Fazy 5

1. Accept/freeze Phase 4 contracts.
2. Add typed Modifier/ResolvedValue contracts.
3. Implement pure stacking engine.
4. Implement dependency DAG resolver + cycle detection.
5. Add permanent/injury/temporary/equipment source adapters only where authoritative source facts exist.
6. Add narrow World Pack formula extension point without broad Phase 19 scope creep.
7. Integrate PlayerState read projection.
8. Route CharacterPanel/Context through one resolver output.
9. Add optional rebuildable cache only after correctness.
10. Run migration/rebuild/world-pack/integration test matrix and coordinator audit.

## CI / repository status observed

At audit start, latest master commit was `ace51fa7cb635a4dcd6801865c300bc8c34f52cf`. GitHub combined status and PR-triggered workflow lookup returned no status entries/runs for that documentation-only registration commit. The canonical roadmap records Phase 3 signed build success through Build #103. This audit changes documentation only and does not alter runtime/build inputs.
