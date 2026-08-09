# WORK-20260809-002 — DerivedValueResolver / Modifier Model Architecture Audit

Status: READ-ONLY PHASE 5 ARCHITECTURE AUDIT — DELTA AFTER PHASE 4

Work ID: `WORK-20260809-002`
Owner: `CHAT-2`
Role: READ-ONLY PHASE 5 ARCHITECTURE AUDITOR
Original report commit: `053efb44989ac82fb9720e0449a40f4b43616911`
Phase 4 implementation audited: `a33514524ccdf8a51ee672f1fbf79616600b8d82`
Delta-audit baseline: `cbadc98dad55360d3bcecfa3c99a998168c48261`
Scope: Phase 5 architecture only. No runtime/schema/migration/repository API changes.

---

## 1. Delta executive summary

Phase 4 materially resolves the largest ambiguity identified in the original audit.

The runtime now has explicit generic contracts:

- `StatDefinition`
- `PlayerStat(baseValue)`
- `ResourceDefinition`
- `PlayerResource(currentValue)`
- `StatResourceStore`
- typed repository reads for definitions/player values
- `MigrationManager.ensureV4()` preserving `ensureV3()`

The most important architectural distinction is now explicit:

```text
PlayerStat.baseValue
= AUTHORITATIVE / PERSISTENT progression/base statistic

PlayerResource.currentValue
= AUTHORITATIVE current resource quantity

ResolvedStat.effectiveValue
ResolvedResource.maximumValue
ResolvedResource.regenerationRate
= DERIVED / rebuildable
```

This is a sufficient Core input contract for a pure `DerivedValueResolver` and generic Modifier model.

However Phase 5 implementation must remain gated by Phase 4 validation/hardening. In particular, old-campaign integration and migration semantics must be proven so Phase 5 never interprets empty new tables as authoritative absence while legacy `character_stats` / resource-like state still contains real campaign data.

---

## 2. Real Phase 4 contract now available

### `StatDefinition`

Confirmed fields:

- `statUid`
- `key`
- `category`
- `unit`
- `minValue`
- `maxValue`
- `growthRuleUid`
- `derivationRuleUid`
- `worldPackUid`

`StatDefinition` is generic and contains no Naruto/Bleach-specific mechanic.

### `PlayerStat`

Confirmed fields:

- `campaignId`
- `characterUid`
- `statUid`
- `baseValue`
- `version`

The code explicitly documents this as an authoritative persistent base value.

This resolves the original report's largest semantic uncertainty around legacy `character_stats.current_value`.

### `ResourceDefinition`

Confirmed fields:

- `resourceUid`
- `key`
- `category`
- `unit`
- `minValue`
- `maxValue`
- `maxRuleUid`
- `regenerationRuleUid`
- `worldPackUid`

The contract explicitly documents definition min/max as definition bounds, while character-specific effective maximum and regeneration belong to the later derived/rule layer.

### `PlayerResource`

Confirmed fields:

- `campaignId`
- `characterUid`
- `resourceUid`
- `currentValue`
- `version`

This is the authoritative current quantity. It is not the resource maximum.

### `StatResourceStore`

Confirmed properties relevant to Phase 5:

- complete list reads; no LIMIT-based canonical truncation,
- campaign-scoped player reads,
- player reads take explicit character UID,
- definition registration is World-Pack scoped,
- stable UID hijacking across World Packs is rejected,
- duplicate `(world_pack_uid,key)` is constrained by schema,
- player-value persistence checks that the target definition exists,
- no public Phase-4 mutation API was added that would intentionally bypass the future PlayerDomainEngine.

### `CampaignRepository`

The repository now exposes:

- `statDefinitions()`
- `resourceDefinitions()`
- `registerStatDefinitions(...)`
- `registerResourceDefinitions(...)`
- `playerStats()`
- `playerResources()`

The player reads resolve through the existing authoritative `ActivePlayerRef` path.

---

## 3. Is Phase 4 sufficient input for Phase 5?

Architecturally: **YES**.

The minimum resolver needs:

1. stable target definition identity,
2. authoritative base stat values,
3. authoritative current resource quantities,
4. rule-binding hooks,
5. campaign/player identity,
6. definition bounds,
7. World Pack ownership/version context.

Phase 4 now supplies items 1–6 directly and enough context to obtain item 7.

Phase 5 does NOT require Phase 4 to persist effective values, maximums, regeneration, modifier rows or formulas. Those belong to Phase 5 or later source-domain phases.

The remaining condition is validation of Phase 4 migration/compatibility, not a missing conceptual resolver input field.

---

## 4. Exact proposed `DerivedValueResolver` input

Conceptual request:

```text
DerivedResolutionRequest {
  campaignId
  characterUid
  worldPackUid
  ruleSetVersion / providerFingerprint
  resolutionEpoch

  statDefinitions: Map<StatUid, StatDefinition>
  resourceDefinitions: Map<ResourceUid, ResourceDefinition>

  playerStats: Map<StatUid, PlayerStat>
  playerResources: Map<ResourceUid, PlayerResource>

  permanentSources
  equipmentSources
  injurySources
  temporaryEffectSources

  ruleProvider
  requestedTargets/profile
}
```

Hard rules:

- `PlayerStat.baseValue` is the only Phase-4 numeric base input for that stat.
- `PlayerResource.currentValue` may be read when a formula explicitly depends on current quantity, but it is never rewritten by resolution.
- legacy `character_stats.current_value` must not be fed into the resolver in parallel with `PlayerStat.baseValue` once canonical migration has established Phase-4 values.
- legacy `effective_*`, `max_*`, `regeneration`, `combat_rating` are never accepted as authoritative resolver inputs.
- no previously resolved `effectiveValue` may become an input substitute for base.

---

## 5. Exact proposed resolver output

```text
DerivedResolutionResult {
  resolvedStats: Map<StatUid, ResolvedStat>
  resolvedResources: Map<ResourceUid, ResolvedResource>
  secondaryValues: Map<DerivedUid, ResolvedDerivedValue>
  diagnostics
  dependencyFingerprint
  rulesFingerprint
}
```

Recommended structures:

```text
ResolvedStat {
  statUid
  baseValue
  effectiveValue
  contributions[]
  appliedBounds
}

ResolvedResource {
  resourceUid
  currentValueObserved
  maximumValue
  regenerationRate
  contributions[]
  definitionBounds
  projectedCurrentForPresentation?
}

ModifierContribution {
  modifierUid
  sourceUid
  sourceType
  lifecycle
  operation
  preValue
  deltaOrFactor
  postValue
  ruleUid?
  provenance
}
```

The output is DERIVED/read-only and must be disposable.

---

## 6. Base vs effective contract

Canonical equation:

```text
AUTHORITATIVE PlayerStat.baseValue
+/- persistent effects
+/- equipment effects
+/- injury effects
+/- temporary effects
+ rule-specific transforms
+ final caps/bounds
= DERIVED effectiveValue
```

`effectiveValue` must never be written back into `PlayerStat.baseValue` as part of resolution.

A permanent progression increase is a later legal domain mutation of base value, not a permanent modifier automatically folded into base by the resolver.

A permanent trait may produce a persistent modifier while still leaving base progression unchanged.

This distinction preserves no-retrogression and prevents repeated resolve cycles from inflating stats.

---

## 7. Proposed generic Modifier model

```text
Modifier {
  modifierUid: StableUid
  sourceUid: StableUid
  sourceType: PERMANENT_TRAIT | EQUIPMENT | INJURY | TEMPORARY_EFFECT | WORLD_RULE | OTHER

  targetKind: STAT | RESOURCE_MAX | RESOURCE_REGEN | DERIVED_VALUE
  targetUid: StableUid

  operation: ADD_FLAT | ADD_PERCENT | MULTIPLY | OVERRIDE | MIN_CAP | MAX_CAP
  magnitude: Decimal?
  ruleUid: StableUid?
  ruleArgs: immutable structured args?

  lifecycle: PERMANENT | EQUIPMENT | INJURY | TEMPORARY
  priority: Int

  stackGroup: String?
  stackKey: String?
  stackingPolicy: STACK_ALL | UNIQUE_SOURCE | HIGHEST_ONLY | LOWEST_ONLY | MAX_STACKS | REPLACE_SAME_KEY | EXCLUSIVE

  validFrom?
  validUntil?
  remainingUses?
  conditionRuleUid?

  provenance
}
```

A modifier is not required to be an authoritative persisted row.

Preferred design:

```text
authoritative source fact
-> source adapter / World Pack rule
-> Modifier projection
-> resolver
```

Examples:

- equipped item fact -> equipment modifiers,
- active injury fact -> injury modifiers,
- active buff/debuff fact -> temporary modifiers,
- durable trait fact -> permanent modifiers.

---

## 8. Modifier provenance and source UID

Mandatory fields for every applied modifier contribution:

- stable `modifierUid`,
- stable `sourceUid`,
- source type,
- target UID,
- World Pack/rule UID where relevant,
- created/activated event or domain fact provenance when available,
- rule/provider version or fingerprint.

`sourceUid` is mandatory because the engine must be able to:

- remove exactly one item's contribution,
- remove exactly one healed injury contribution,
- expire exactly one temporary effect,
- prove idempotency,
- explain effective values,
- rebuild from authoritative state after cache deletion/restore.

Display names are never identity.

---

## 9. Scope: stat vs resource

Core target kinds should remain generic:

### STAT

Input base:

`PlayerStat.baseValue`

Output:

`ResolvedStat.effectiveValue`

### RESOURCE_MAX

Input:

`ResourceDefinition` + resolved stat/resource dependencies + modifiers + `maxRuleUid` rule.

Output:

`ResolvedResource.maximumValue`

### RESOURCE_REGEN

Input:

resolved dependencies + `regenerationRuleUid` + modifiers/context.

Output:

`ResolvedResource.regenerationRate`

### RESOURCE CURRENT

`PlayerResource.currentValue` is runtime/authoritative current quantity.

Phase 5 resolver must not spend, heal, regenerate or clamp-persist it. If current exceeds a newly reduced derived maximum, resolver may return a presentation/legal projection and diagnostic, but the actual state correction belongs to a legal later domain transition.

---

## 10. Ordering and deterministic resolution

Recommended lifecycle order:

```text
BASE
-> PERMANENT
-> EQUIPMENT
-> INJURY
-> TEMPORARY
-> FINAL CAPS / DEFINITION BOUNDS
= EFFECTIVE
```

Within a lifecycle stage:

1. filter inactive/expired/inapplicable modifiers,
2. deduplicate by stable modifier/source identity,
3. partition by stack group,
4. apply stacking policy,
5. sort deterministically,
6. flat additions,
7. additive percentages,
8. multiplicative operations,
9. override winner if allowed,
10. caps/bounds.

Recommended deterministic tie-break order:

```text
lifecycleStage
-> operationStage
-> priority
-> stackGroup
-> sourceUid
-> modifierUid
```

Never depend on SQL retrieval order or HashMap iteration order.

---

## 11. Stacking rules

Required semantics:

- `STACK_ALL`: every distinct modifier applies.
- `UNIQUE_SOURCE`: at most one modifier for the same source/target/stack identity.
- `HIGHEST_ONLY`: strongest positive/highest configured value survives.
- `LOWEST_ONLY`: lowest/strongest penalty survives.
- `MAX_STACKS(n)`: deterministic N winners selected according to explicit ranking.
- `REPLACE_SAME_KEY`: one active instance per stack key; later authoritative lifecycle instance replaces prior one according to explicit rule.
- `EXCLUSIVE`: only one may apply; unresolved conflict is validation error unless provider supplies a deterministic winner.

Percentage semantics must be fixed by contract. Recommended default:

```text
stageBaseAfterFlat = base + flatSum
percentStage = stageBaseAfterFlat * (1 + sum(addPercent))
multiplierStage = percentStage * product(multipliers)
```

World Pack rules may define a different formula through rule UIDs, but generic `ADD_PERCENT` must not have ambiguous behavior.

---

## 12. Priority, override and caps

### Priority

Priority resolves conflicts only where ordering affects semantics, especially `OVERRIDE` and exclusive groups. It must not become an invisible global "higher priority always wins everything" mechanism.

### Override

`OVERRIDE` is exceptional.

Requirements:

- explicit source,
- explicit target,
- explicit priority,
- deterministic tie-break,
- contribution trace,
- preferably exclusive stack group,
- never silently rewrites base.

### Caps

Use two distinct concepts:

1. modifier-provided dynamic caps (`MIN_CAP`, `MAX_CAP`),
2. definition bounds (`StatDefinition.minValue/maxValue`, `ResourceDefinition.minValue/maxValue`).

Recommended order:

```text
arithmetic
-> modifier caps
-> definition final bounds
```

If a World Pack requires a different legal rule, bind it by rule UID/provider rather than hardcoding world-specific behavior in Core.

---

## 13. `derivationRuleUid`, `maxRuleUid`, `regenerationRuleUid`

### Verdict

These fields are **sufficient as Phase-4 binding points**, but **not sufficient as the complete Phase-5 rule contract by themselves**.

They should remain opaque stable references.

Phase 5 requires a rule registry/provider contract that can resolve each UID into deterministic rule metadata/behavior.

Required rule metadata should include at least:

- `ruleUid`,
- rule version/fingerprint,
- supported target kind,
- declared dependency UIDs or a deterministic dependency-discovery contract,
- required context keys,
- numeric/rounding policy if non-default,
- provider/World Pack ownership,
- validation behavior for missing dependencies,
- deterministic evaluation function/DSL representation.

Do NOT expand `StatDefinition` / `ResourceDefinition` into arbitrary formula-script containers merely to implement Phase 5.

Good boundary:

```text
Definition.derivationRuleUid
Definition.maxRuleUid
Definition.regenerationRuleUid
        |
        v
Versioned DerivedRuleRegistry / narrow World Pack rule provider
        |
        v
Safe deterministic evaluation
```

Therefore no Phase-4 schema extension is required merely because those fields are opaque UIDs.

A later extension is only needed if validation proves a required definition-level property cannot be represented by the rule registry/provider (for example explicit rounding/type metadata desired as definition metadata rather than rule metadata). That is not presently a blocker.

---

## 14. Permanent modifiers

Permanent modifier sources are durable authoritative character facts such as generic permanent traits, adaptations, scars, blessings/curses or other World-Pack-defined durable effects.

They must not be confused with base progression.

Correct:

```text
base strength = 40
permanent trait +10%
effective strength = 44
```

Incorrect:

```text
resolver changes stored base from 40 to 44 every resolve
```

If progression itself raises the learned/base stat from 40 to 42, that is a legal Player Domain mutation of `PlayerStat.baseValue`, after which the same permanent +10% effect resolves from 42.

---

## 15. Equipment modifiers

Phase 5 should define the generic source type and adapter contract now, but it must not activate equipment effects from current inventory possession.

Current project debt remains:

`character_inventory` membership is not authoritative equipped state.

Therefore:

```text
inventory item exists != equipment modifier active
```

Full equipment activation integration waits for an authoritative Equipment/loadout model or another explicitly accepted equipped fact source.

This is not a blocker for implementing the pure Phase-5 resolver arithmetic.

---

## 16. Injury modifiers

`injuries_v2` already supplies current injury facts, but Core must not hardcode mappings such as severity -> speed penalty.

Recommended path:

```text
active injury fact
-> generic/world injury rule adapter
-> Modifier(s)
-> DerivedValueResolver
```

The injury fact/lifecycle is runtime authoritative; the numeric penalty is derived.

Healing/removing the injury removes its modifier contribution on rebuild without altering base progression.

---

## 17. Temporary modifiers

Temporary buffs/debuffs need authoritative lifecycle facts carrying enough information for deterministic activity evaluation:

- effect UID,
- source UID,
- target character UID,
- activation time/turn,
- expiry time/turn or remaining uses,
- stack identity/count,
- definition/rule UID,
- provenance.

The resolver consumes these facts read-only.

Expiry must be evaluated against an explicit `resolutionEpoch`, not wall-clock time and not implicit mutable global state.

---

## 18. Dependency graph and rule resolution

Recommended resolver pipeline:

1. validate campaign/player identity,
2. validate all definitions and player values,
3. expand source facts to generic modifiers,
4. resolve rule UIDs through versioned rule provider,
5. construct dependency graph,
6. detect missing dependencies,
7. detect cycles,
8. topological evaluation,
9. apply modifier arithmetic/stacking,
10. apply caps/bounds,
11. emit contribution trace and fingerprints.

Default invariant: dependency cycles are invalid and fail explicitly.

Do not introduce iterative hidden convergence logic unless a later canonical contract explicitly requires it.

---

## 19. Rebuildability and cache policy

Derived state must be reproducible from:

```text
Phase-4 authoritative values
+ authoritative modifier-producing facts
+ definition versions
+ rule/provider versions
+ explicit resolution context
```

Required invariant:

Deleting every resolved-value cache must cause **zero campaign information loss**.

Recommended first implementation: no persistent derived cache.

Only add cache after correctness.

If cache is later added, key/invalidate using a fingerprint containing at least:

- campaignId,
- characterUid,
- relevant `PlayerStat.version`s,
- relevant `PlayerResource.version`s when used,
- source-fact versions/fingerprint,
- definition fingerprint,
- rule/provider fingerprint,
- resolution context epoch/profile where relevant.

A cache miss always recomputes; cache never becomes authoritative.

---

## 20. World Pack extension points

Core remains world-agnostic.

Phase 5 needs only narrow extension points:

```text
DerivedRuleRegistry.resolve(ruleUid)
ModifierSourceAdapter.derive(sourceFact, context)
WorldDerivedRules.validate(...)
```

World Packs may define any domains/resources through Phase-4 UIDs and these rules.

Core must not contain explicit logic for:

- chakra,
- reiatsu/reiryoku/reishi,
- genjutsu,
- raiton,
- kido,
- zanjutsu,
- sonido,
- clan/race-specific mechanics.

Those names can exist in World Pack definitions/data, never as Core branching conditions.

---

## 21. Authoritative / Derived / Runtime classification after Phase 4

### AUTHORITATIVE / PERSISTENT

- `PlayerStat.baseValue`
- stable definition identities / accepted World Pack definitions
- durable permanent effect source facts
- permanent player progression facts

### AUTHORITATIVE current/runtime facts

- `PlayerResource.currentValue`
- active injuries
- active temporary effects
- equipped-state facts once implemented
- fatigue/cooldowns/stance and other runtime condition facts where canonical

### DERIVED

- effective stats
- resource maximums
- regeneration rates
- combat/secondary derived values
- modifier projections/contributions
- derived clamped presentation values

### CACHE/PRESENTATION

- materialized resolver cache
- CharacterPanel effective-value rendering
- ContextBundle derived-value summaries

Legacy compatibility snapshots must never outrank canonical Phase-4 values.

---

## 22. Phase-4 compatibility gate relevant to Phase 5

This delta audit does not perform CHAT-3's migration validation, but Phase 5 must explicitly depend on its result.

The key risk is no longer ambiguity in the new model; it is integration coverage:

```text
old campaign has legacy character_stats/resources
ensureV4 creates new tables
new canonical read path returns empty player_stats/player_resources
```

If that state is possible without an explicit compatibility bridge/migration policy, Phase 5 could resolve base values as absent/zero despite valid legacy campaign state.

Therefore before Phase 5 implementation begins, Phase 4 validation must establish one of these canonical strategies:

A. deterministic migration/seed from legacy to `player_stats/player_resources`, or
B. an explicit compatibility adapter that surfaces legacy authoritative values through the Phase-4 contract until migrated.

What is forbidden is two independent authoritative stores or silent empty-new-model precedence over populated legacy state.

This is a Phase-4 validation/hardening condition, not a missing field in the Phase-4 data model.

---

## 23. Required Phase 5 invariants

1. Resolver is pure/read-only.
2. Resolver never changes `PlayerStat.baseValue`.
3. Resolver never spends/heals/regenerates `PlayerResource.currentValue`.
4. Same complete input + rule versions + context -> same result.
5. No SQL-row-order dependence.
6. Stable UID targets/sources only.
7. Duplicate modifier identity cannot double-apply.
8. Unknown target is explicit failure/diagnostic.
9. Missing rule UID is explicit failure/diagnostic.
10. Rule dependency cycles are rejected.
11. Definition bounds are obeyed.
12. NaN/Infinity rule output is rejected.
13. Every effective delta is explainable through a contribution trace.
14. Temporary/injury/equipment penalties never destroy base progression.
15. Inventory possession does not imply equipped effect.
16. Expired runtime effects do not contribute.
17. Cross-player/cross-campaign contributions are impossible.
18. Legacy derived snapshot values are not resolver base inputs.
19. Derived/cache deletion loses no campaign information.
20. World Pack rule update invalidates affected cache/result fingerprint.

---

## 24. Required tests for Phase 5

### Pure arithmetic

- base only,
- flat positive/negative,
- additive percentages,
- multipliers,
- mixed operation order,
- deterministic ties,
- all stacking policies,
- duplicate UID/source idempotency,
- override priority,
- min/max caps,
- definition bounds,
- NaN/Infinity rejection.

### Rule graph

- `derivationRuleUid` resolves,
- `maxRuleUid` resolves,
- `regenerationRuleUid` resolves,
- missing rule fails explicitly,
- missing dependency fails explicitly,
- cycle rejected,
- same rule/version/input is deterministic.

### Lifecycle

- permanent contribution persists across resolve,
- unequipped source contributes zero,
- healed injury contributes zero,
- expired temporary effect contributes zero,
- stack replacement/max-stack semantics deterministic.

### Resources

- current resource is unchanged by resolve,
- maximum derives from stat dependency,
- regen derives independently from current quantity unless rule explicitly needs it,
- lowering max does not silently mutate current,
- max/regen rebuild after base/modifier change.

### Rebuild/cache

- delete derived cache -> identical rebuild,
- restore authoritative state -> identical historical resolution for same rules/context,
- rule-version change invalidates cache,
- one base-version change invalidates dependents.

### Phase 4/5 integration

- active player A values only,
- campaign A values never leak into B,
- 100+ definitions/values are not truncated,
- unknown/custom World Pack definitions resolve via generic contract,
- legacy populated campaign never resolves as empty because new Phase-4 tables are empty,
- CharacterPanel and Context consume the same resolved projection once integration occurs.

---

## 25. Recommended implementation order for Phase 5

1. Wait for Phase 4 validation/hardening result from WORK-001/WORK-003.
2. Freeze accepted `StatDefinition/PlayerStat/ResourceDefinition/PlayerResource` semantics.
3. Add Phase-5 pure value/modifier contracts.
4. Implement deterministic modifier arithmetic and stacking.
5. Add rule registry/provider for `derivationRuleUid`, `maxRuleUid`, `regenerationRuleUid`.
6. Add dependency DAG + cycle/missing dependency validation.
7. Add permanent source adapter.
8. Add injury source adapter where semantics are defined.
9. Add temporary effect adapter when lifecycle facts exist.
10. Add equipment adapter only after authoritative equipped state exists.
11. Integrate PlayerState derived projection.
12. Route CharacterPanel/Context through a single resolved-value path.
13. Add optional cache only after full rebuild tests pass.
14. Run old-campaign + custom World Pack + isolation + deterministic replay tests.
15. Coordinator performs integration audit before global Phase 5 COMPLETE.

---

## 26. Files likely requiring later Phase-5 change

This work item does not modify them.

Expected future Phase-5 implementation candidates:

- `app/src/main/java/com/rpgos/app/StatResourceContract.kt` — likely no mandatory Phase-4 field change; Phase-5 types should preferably live separately.
- new `DerivedValueResolver` / Modifier contract files.
- new narrow rule registry/provider files.
- `PlayerStateStore.kt` / snapshot composition layer.
- `CharacterPanel.kt` or later PlayerSnapshotBuilder path.
- `ContextBuilder.kt` read projection path.
- repository/service composition only as needed for read resolution.

Do not put formula logic into `LocalGameStore`, `CampaignRepository`, SQL migration code or CharacterPanel.

---

## 27. Delta verdict on the three Phase-4 rule UID fields

| Field | Phase-5 suitability | Required later companion |
|---|---|---|
| `StatDefinition.derivationRuleUid` | SUFFICIENT binding point | versioned rule registry/provider + dependencies |
| `ResourceDefinition.maxRuleUid` | SUFFICIENT binding point | versioned max rule + dependencies |
| `ResourceDefinition.regenerationRuleUid` | SUFFICIENT binding point | versioned regen rule + dependencies/context |

No schema extension is required simply to store formulas inside those definitions.

The Phase-5 engine should resolve opaque rule UIDs rather than expand Core definitions with universe-specific formula fields.

---

## 28. Coordinator handoff

### baselineCommit

`cbadc98dad55360d3bcecfa3c99a998168c48261`

### Phase 4 implementation audited

`a33514524ccdf8a51ee672f1fbf79616600b8d82`

### Findings

- Phase 4 now supplies a clean authoritative `PlayerStat.baseValue`.
- Phase 4 now supplies a clean current `PlayerResource.currentValue`.
- definition stable UIDs and World Pack ownership are adequate resolver targets.
- min/max definition bounds are adequate final-bound inputs.
- the three rule UID fields are adequate bindings, but require a versioned deterministic rule registry/provider in Phase 5.
- no reason exists to persist effective values as authoritative rows.
- equipment/injury/temporary effects should enter through source adapters and generic Modifier projections.
- legacy migration/integration remains the only important pre-Phase-5 gate identified by this delta audit.

### Blockers

No **Phase-4 contract-shape** blocker for a DerivedValueResolver was found.

Phase 5 must nevertheless wait for Phase-4 validation/hardening because old-campaign compatibility must prove that canonical Phase-4 reads do not silently become empty while legacy authoritative stats/resources remain populated.

### Final status

**PHASE 5 READY AFTER PHASE 4 VALIDATION**
