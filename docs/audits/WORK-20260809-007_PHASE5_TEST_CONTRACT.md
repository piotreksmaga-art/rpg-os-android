# WORK-20260809-007 — Phase 5 Implementation Test / Contract Preparation

Status: READ-ONLY RUNTIME / IMPLEMENTATION TEST CONTRACT

Work ID: `WORK-20260809-007`
Owner: `CHAT-2`
Role: PHASE 5 IMPLEMENTATION TEST / CONTRACT PREPARATION AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Baseline inspected: `10793f25511daabc874410c62c81a544e8a3bc2f`
Prior Phase 5 audit: `735af8976082bae3f29affd0ab2ec9fce057bca9`
Latest Phase 4 runtime hardening visible at start: `640e70b4cfeb7e363b46646c2f2367266edb4413`
Latest Phase 4 validation visible at start: `6f97d495cb03759f35ce128dfea1ea5498a1d67a` — `PHASE 4 VALIDATION: FAIL`

This document defines the implementation contract and release-gating tests for the future Phase 5 `DerivedValueResolver + Modifier Model`. It does not implement runtime Kotlin, schema, migrations, repository APIs, modifiers, resolver logic, Talent, or Potential.

The critical dependency remains unchanged: Phase 5 implementation must not begin until Phase 4 passes follow-up validation and proves old-campaign typed authoritative equality.

---

## 1. Canonical authority model

Phase 5 must preserve the Phase 4 meaning of these values:

```text
PlayerStat.baseValue
= AUTHORITATIVE / PERSISTENT progression/base value

PlayerResource.currentValue
= AUTHORITATIVE current resource quantity

ResolvedStat.effectiveValue
= DERIVED / rebuildable

ResolvedResource.maximumValue
= DERIVED / rebuildable

ResolvedResource.regenerationRate
= DERIVED / rebuildable
```

Absolute rule:

**No resolver execution, modifier application, cache rebuild, injury, equipment removal, temporary effect, buff, debuff, or World Pack rule is allowed to rewrite `PlayerStat.baseValue` as a side effect.**

The resolver is a pure projection. State mutation belongs to later legal domain mutation paths.

---

## 2. Proposed future resolver contract

Conceptual API:

```text
DerivedValueResolver.resolve(request: DerivedResolutionRequest): DerivedResolutionResult
```

### 2.1 Input contract

`DerivedResolutionRequest` must contain one immutable resolution snapshot:

```text
campaignId
characterUid
worldPackUid / active rule-set identity
ruleProviderVersion / ruleFingerprint
resolutionEpoch

statDefinitions: Map<StatUid, StatDefinition>
resourceDefinitions: Map<ResourceUid, ResourceDefinition>
playerStats: Map<StatUid, PlayerStat>
playerResources: Map<ResourceUid, PlayerResource>

permanentModifierSources
equipmentModifierSources
injuryModifierSources
temporaryModifierSources

explicit context required by rules
requested targets/profile
```

Inputs must be campaign/player isolated before resolution begins.

Forbidden inputs:

- previously resolved `effectiveValue` used as base,
- legacy `effective_*` as authoritative values,
- legacy `max_*` as authoritative resource maximum,
- legacy `regeneration` as authoritative state,
- `combat_rating` or presentation snapshots as authoritative base,
- inventory possession treated as equipped-state authority,
- unversioned hidden mutable global rule state.

### 2.2 Output contract

```text
DerivedResolutionResult {
  resolvedStats
  resolvedResources
  secondaryDerivedValues
  contributionTrace
  diagnostics
  inputFingerprint
  ruleFingerprint
}
```

`ResolvedStat` must expose at minimum:

```text
statUid
baseValue
effectiveValue
contributions[]
preCapValue
finalMin/finalMax if applicable
clamp/cap diagnostics
```

`ResolvedResource` must expose at minimum:

```text
resourceUid
currentValueObserved
maximumValue
regenerationRate
contributions[]
```

The resolver must never persist or mutate `currentValueObserved`.

---

## 3. Generic Modifier contract

A future generic modifier should carry enough information for deterministic replay, removal, stacking, diagnostics and cache invalidation.

Recommended conceptual fields:

```text
Modifier {
  modifierUid
  sourceUid
  sourceKind
  targetKind
  targetUid
  lifecycle
  operation
  magnitude OR ruleUid + args
  stackGroup
  stackKey
  stackingPolicy
  priority
  active
  validFrom
  validUntil
  providerVersion / definitionVersion
  provenance
}
```

### 3.1 Required source kinds

Core may use generic categories such as:

- `PERMANENT`
- `EQUIPMENT`
- `INJURY`
- `TEMPORARY`

These are lifecycle/source categories, not world-specific mechanics.

### 3.2 Required target scopes

At minimum:

- `STAT_EFFECTIVE`
- `RESOURCE_MAXIMUM`
- `RESOURCE_REGENERATION`
- `DERIVED_VALUE`

A modifier must not target `PlayerStat.baseValue` through the resolver pipeline.

### 3.3 Required operations

Minimum generic set:

- `ADD_FLAT`
- `ADD_PERCENT`
- `MULTIPLY`
- `OVERRIDE`
- `MIN_CAP`
- `MAX_CAP`

A later implementation may add a safe soft-cap rule binding, but arbitrary executable scripts in modifier rows are forbidden.

---

## 4. Deterministic ordering contract

Canonical lifecycle order:

```text
BASE
-> PERMANENT
-> EQUIPMENT
-> INJURY
-> TEMPORARY
-> FINAL CAPS / DEFINITION BOUNDS
= EFFECTIVE
```

Within each lifecycle stage, the implementation must use an explicit deterministic order. Recommended algorithm:

1. discard inactive/expired/non-matching modifiers,
2. de-duplicate by `modifierUid`,
3. group by stacking group/key,
4. resolve stacking policy,
5. sort by operation stage,
6. sort by explicit `priority`,
7. use a stable UID tie-breaker,
8. apply flat additions,
9. apply additive-percent group,
10. apply multipliers,
11. apply controlled override,
12. apply caps/bounds.

No result may depend on database row order, collection insertion order, hash-map iteration order, thread scheduling or filesystem order.

---

## 5. Mandatory baseline arithmetic test

Required example:

```text
base = 100
permanent +10
equipment +20
injury -30
temporary +5
```

All four modifiers are `ADD_FLAT` and active.

Expected resolution:

```text
100 + 10 + 20 - 30 + 5 = 105
```

Assertions:

- `baseValue == 100`
- `effectiveValue == 105`
- `PlayerStat.baseValue` remains exactly `100`
- contribution trace contains exactly four contributions in canonical lifecycle order
- resolving the same logical inputs in a different input-list order still returns `105`
- resolving twice returns semantically identical result/fingerprint

---

## 6. STAT test matrix

### S01 — base only

Input: base `100`, no modifiers.
Expected: effective `100`.

### S02 — additive stacking

Input: base `100`, flat `+10`, `+20`, `-30`, `+5` across canonical lifecycles.
Expected: `105`.

### S03 — multiple modifiers same lifecycle

Three permanent flat modifiers: `+2`, `+3`, `-1`.
Expected lifecycle subtotal: `+4`; stable ordering in trace.

### S04 — additive percentage semantics

Contract must define a fixed percentage base.

Recommended rule: within one lifecycle operation stage, `ADD_PERCENT` values combine additively against the value entering that percentage stage, not sequentially against each other.

Example:

```text
stage input = 100
+10%
+20%
```

Expected: `130`, not `132`.

This exact semantic must be frozen by implementation tests.

### S05 — multiplicative stacking

Example:

```text
input 100
x1.10
x1.20
```

Expected: `132` if `MULTIPLY` is sequential multiplicative composition.

Reversing input storage order must still produce the same result.

### S06 — mixed operations

Example:

```text
base 100
flat +20
add-percent +10%
multiply x1.5
```

Under the declared operation order:

```text
120 -> 132 -> 198
```

Expected `198` before caps.

### S07 — override

Override semantics must be exceptional and explicit.

Example:

- base after ordinary modifiers: `140`
- override A priority `10` -> `80`
- override B priority `20` -> `120`

Expected winner: B, result `120` before caps.

Equal priority must be resolved by stable deterministic UID tie-breaker or rejected as an ambiguous exclusive-group configuration. Silent nondeterminism is forbidden.

### S08 — hard max cap

Input pre-cap `180`, hard max `150`.
Expected final `150`, trace/diagnostic records cap.

### S09 — hard min cap

Input pre-cap `-20`, hard min `0`.
Expected final `0`.

### S10 — definition min/max

`StatDefinition(minValue=0,maxValue=200)` and resolver result before final bounds `230`.
Expected final `200` unless contract explicitly defines definition bounds as validation-only for a target type. Phase 5 implementation must not leave this ambiguous.

### S11 — soft cap

Soft caps must be represented by a deterministic registered rule, not an implicit magic formula in Core.

Example rule fixture:

```text
ruleUid = TEST.SOFT_CAP.V1
threshold = 100
above-threshold contribution factor = 0.5
input = 140
```

Expected according to fixture rule:

```text
100 + (40 * 0.5) = 120
```

Tests assert both numerical result and bound rule UID/version.

### S12 — priority

Two same-stage modifiers with different priorities and order-sensitive operation types must resolve according to explicit priority, never input sequence.

### S13 — deterministic tie-breaking

Two equal-priority surviving modifiers that require ordering must resolve by stable `modifierUid` lexical/defined comparator or fail validation according to contract.

Test runs randomized input permutations at least 100 times and asserts identical output.

### S14 — expired modifier

Modifier with `validUntil < resolutionEpoch` contributes zero and appears as inactive/expired diagnostic if diagnostics are enabled.

### S15 — inactive modifier

`active=false` contributes zero.

### S16 — future modifier

`validFrom > resolutionEpoch` contributes zero.

### S17 — source removal

Resolve with equipment source present, then resolve same authoritative base with that source absent.

Expected:

- only that source contribution disappears,
- base remains unchanged,
- unrelated modifiers remain identical.

### S18 — duplicate modifier UID

Same `modifierUid` delivered twice in input.

Expected: no double application. Preferred policy: reject duplicated inconsistent payload; exact duplicate is idempotently collapsed or explicit validation error. Whichever policy is chosen must be deterministic and tested.

### S19 — same source, different legal modifiers

One item/source may legitimately emit multiple different modifier UIDs to multiple targets. Deduplication must be by modifier identity, not bluntly by `sourceUid`.

### S20 — replay determinism

Same canonical input snapshot, same rule provider/version, same resolution epoch -> same complete result and same fingerprint.

### S21 — randomized input order determinism

Randomly permute modifier lists and definition maps; output must remain semantically identical.

### S22 — NaN / Infinity from rule

Any rule returning NaN or Infinity causes deterministic validation/resolution error. Invalid numeric output must never be cached or displayed as a legal value.

### S23 — overflow/extreme magnitude

Very large finite values must either resolve safely within chosen numeric domain or fail explicitly. No wraparound.

### S24 — unknown target UID

Modifier targets absent definition UID.
Expected: deterministic validation error; never auto-create a stat/resource.

### S25 — cross-campaign source contamination

Request campaign A containing modifier/source tagged for campaign B.
Expected: reject before calculation.

### S26 — cross-character contamination

Modifier/source for Player B cannot alter Player A resolution.

---

## 7. Stacking policy tests

Minimum policies to freeze:

### ST01 `STACK_ALL`
All legal instances apply.

### ST02 `UNIQUE_SOURCE`
At most one legal contribution for a given source + stack key according to deterministic rule.

### ST03 `HIGHEST_ONLY`
Only greatest magnitude/winner applies.

### ST04 `LOWEST_ONLY`
Only lowest/strongest negative according to signed contract applies.

### ST05 `MAX_STACKS(n)`
Apply exactly deterministic top/first `n` according to declared comparator.

### ST06 `REPLACE_SAME_STACK_KEY`
Exactly one active instance survives; replacement identity/order derives from authoritative lifecycle/version/time contract, never DB row position.

### ST07 `EXCLUSIVE`
More than one active exclusive modifier either produces deterministic winner by explicit priority or validation error. No arbitrary winner.

For every policy, test:

- input order permutation,
- duplicate retry,
- source removal,
- expiration,
- replay.

---

## 8. NO-RETROGRESSION contract

This is a release-blocking invariant.

### NR01 injury

Before:

```text
PlayerStat.baseValue = 100
injury penalty = -40
```

Expected:

```text
effectiveValue = 60
baseValue still = 100
```

After injury removal:

```text
effectiveValue = 100
baseValue still = 100
```

### NR02 debuff

Temporary debuff cannot reduce persisted base.

### NR03 equipment removal

If equipment granted `+25`, removing it changes effective from `125` to `100`, never base from `125` to `100`.

### NR04 buff expiry

Temporary buff expiry removes only derived contribution.

### NR05 repeated resolve

Repeated resolution under a penalty must never cumulatively change base:

```text
100 -> resolve -10 = 90
resolve again same inputs = 90
```

Never `80`, `70`, etc.

### NR06 cache restore

Deleting/rebuilding derived state cannot alter authoritative base.

---

## 9. RESOURCE contract

### 9.1 Authority

`PlayerResource.currentValue` is current authoritative quantity.

`maximumValue` and `regenerationRate` are derived.

### 9.2 Resolver restrictions

Resolver MUST NOT:

- regenerate current quantity,
- spend resource,
- heal resource,
- clamp-write current resource,
- persist a changed current value,
- advance time,
- create regeneration events.

It may return a read-only projection/diagnostic that current quantity is outside the current derived legal maximum.

### R01 current remains unchanged

Input current `40`, derived max `100`, regen `5/turn`.
Expected current observed remains `40` after resolve.

### R02 maximum derived from stat

Fixture:

```text
maxRuleUid = TEST.MAX_FROM_STAT.V1
max = resolved Stat A * 10
Stat A effective = 12
```

Expected maximum `120`.

### R03 regeneration derived

Fixture rule returns `3.5`.
Expected resolver output rate `3.5`; PlayerResource.currentValue unchanged.

### R04 resolver does not apply regeneration

Current `40`, regeneration `3.5`.
After resolver call current still `40`, not `43.5`.

### R05 modifier changes maximum

Equipment modifier raises relevant effective stat, maximum changes deterministically, current remains unchanged.

### R06 injury lowers maximum below current

Current `90`, old max `100`, injury causes new derived max `70`.

Resolver output:

```text
currentValueObserved = 90
maximumValue = 70
```

plus explicit over-cap diagnostic/projection policy.

It must NOT silently persist `70` into current. Any legal current clamp belongs to a separate domain state transition.

### R07 regeneration rule missing

If `regenerationRuleUid` is null, output must represent “no defined derived regeneration rule” according to explicit contract, not silently assume zero unless definition states zero.

### R08 current NaN/Infinity impossible

Phase 4 validation should prevent invalid authoritative current values; resolver still fails loudly if corrupted input appears.

---

## 10. Rule binding contract

Phase 4 exposes opaque rule-binding UIDs:

- `StatDefinition.derivationRuleUid`
- `ResourceDefinition.maxRuleUid`
- `ResourceDefinition.regenerationRuleUid`

These are sufficient as references, but Phase 5 needs a versioned rule provider/registry contract.

Recommended conceptual lookup:

```text
RuleProvider.resolve(
  ruleUid,
  providerVersion,
  targetUid,
  immutable dependency view,
  explicit context
) -> deterministic result
```

### 10.1 Binding invariants

1. Rule UID is opaque to Core.
2. Rule identity is stable.
3. Rule provider/version participates in resolver fingerprint.
4. Rule declares or exposes dependencies before/while graph construction.
5. Missing rule is not interpreted as a zero formula unless the definition explicitly allows missing/optional rule semantics.
6. Rule output must be finite and deterministic.
7. Rule cannot write database state.
8. Rule cannot secretly read mutable global state not included in resolution context/fingerprint.
9. Rule cannot call resolver recursively in an uncontrolled manner.
10. Rule version changes invalidate affected derived cache.

### RB01 valid derivationRuleUid

Registered rule resolves deterministically and contribution trace records rule UID/version.

### RB02 valid maxRuleUid

Resource max rule resolves from declared dependencies.

### RB03 valid regenerationRuleUid

Regen rule resolves rate only; no current-resource mutation.

### RB04 missing rule

Definition references unknown rule UID.
Expected: deterministic `MissingRule` validation/resolution error naming target + UID.

### RB05 incompatible provider version

Definition/rule metadata requires version range incompatible with active provider.
Expected: deterministic version compatibility error, no fallback to arbitrary current implementation.

### RB06 provider update

Same authoritative data but provider version V1 -> V2.
Expected different fingerprint; prior cache invalid; V2 recomputed.

### RB07 hidden dependency attempt

A rule reading an undeclared derived target must be rejected or dependency must be registered before calculation. Resolver graph cannot depend on accidental evaluation order.

---

## 11. Dependency graph and cycle tests

The resolver must build a deterministic dependency graph and detect cycles before recursive evaluation can loop.

### C01 direct self-cycle

```text
A derives from A
```

Expected deterministic cycle error: `A -> A`.

### C02 two-node cycle

Required scenario:

```text
A derives from B
B derives from A
```

Expected deterministic validation error identifying cycle `A -> B -> A`.

Forbidden outcomes:

- stack overflow,
- infinite recursion,
- timeout-based “detection”,
- partial cached result,
- whichever node happened to be evaluated first winning.

### C03 longer cycle

`A -> B -> C -> A` rejected.

### C04 acyclic diamond

`A -> B`, `A -> C`, `B/C -> D` resolves in stable topological order and D evaluates once or according to a deterministic memoization contract.

### C05 stat/resource mixed cycle

Stat derives from resource maximum while resource maximum derives from same stat.
Expected cycle rejection.

### C06 current-resource dependency is explicit

If a legal rule depends on `PlayerResource.currentValue`, current value is a leaf authoritative input and cannot itself become derived by the same graph.

---

## 12. Modifier provenance contract

Every applied contribution must be explainable.

Minimum trace entry:

```text
modifierUid
sourceUid
sourceKind
targetUid
operation
inputValue
contribution / outputValue
priority
stackGroup
ruleUid if any
providerVersion
provenance reference
```

### P01 complete trace

Baseline arithmetic test must explain all four non-base deltas.

### P02 removed source disappears

After equipment removal, its contribution is absent and no stale trace remains.

### P03 duplicate source delivery

Trace does not show duplicate effective contribution for idempotently duplicated modifier UID.

### P04 error provenance

Invalid rule/modifier diagnostics name source and target sufficiently for replay/debugging.

---

## 13. Lifetime / duration tests

Temporary modifiers require explicit lifetime semantics.

Allowed abstractions may include:

- campaign absolute time,
- turn/round range,
- event-validity predicate,
- explicit active flag.

Do not mix incomparable clocks implicitly.

### L01 starts now

`validFrom == epoch`: active.

### L02 expires now

Contract must explicitly decide inclusive/exclusive `validUntil` and test boundary exactly.

Recommended: half-open interval `[validFrom, validUntil)`.

### L03 no expiry

Null `validUntil` means indefinite while source remains active, if lifecycle permits.

### L04 changed clock domain

Modifier expressed in combat rounds cannot be compared to campaign days without explicit context mapping; fail validation rather than guess.

---

## 14. CACHE contract

Correctness must exist without cache first.

Any derived cache is optional and disposable.

Cache key/fingerprint must include all material dependencies, at minimum:

- campaign ID,
- character UID,
- Phase 4 player stat/resource versions or content hash,
- relevant definition versions/content hash,
- active modifier-source versions/content hash,
- rule/provider fingerprint,
- resolution context/epoch where time-sensitive effects exist.

### CA01 delete cache -> rebuild

Resolve result X.
Delete all derived cache.
Resolve again from authoritative inputs.
Expected semantically identical X.

### CA02 cache cannot become authority

Corrupt/delete cache while base/state remain correct.
Fresh resolution follows authoritative inputs, not stale cache.

### CA03 base change invalidates

Change PlayerStat base version/value.
Expected affected cache miss/rebuild.

### CA04 source removal invalidates

Unequip/remove source -> rebuild excludes modifier.

### CA05 rule version invalidates

Provider version V2 invalidates V1 result.

### CA06 unrelated dependency

Changing an unrelated stat should not change an unaffected resolved target result; cache implementation may invalidate broadly for simplicity, but semantic result must stay identical.

### CA07 expired temporary effect

Advancing resolution epoch past expiry invalidates time-sensitive result.

---

## 15. World Pack neutrality tests

Core resolver/modifier implementation must contain no literal mechanics tied to a specific setting.

Forbidden Core mechanical literals include at minimum:

- `chakra`
- `reiatsu`
- `genjutsu`
- `kido`
- `raiton`
- `sonido`

and any equivalent Naruto/Bleach-specific branch names.

### WP01 synthetic world pack

Create a test pack using intentionally neutral names:

```text
stat: test.stat.alpha
resource: test.resource.flux
rules: test.rule.*
```

All resolver features must work without production-world vocabulary.

### WP02 two packs same display key

World Pack A and B may use the same human-readable key/name with different stable definition UIDs. Resolver targets stable UID and does not cross-wire them.

### WP03 rule namespace isolation

Pack A rule UID cannot silently resolve to Pack B implementation unless registry contract explicitly allows globally shared Core rules.

### WP04 unknown/custom pack

A newly supplied valid pack can register definitions/rules and use resolver without Core source change.

### WP05 no source-code world branches

Static test/search over resolver/modifier Core package fails if forbidden world-mechanic literals appear outside test fixtures/adapters specifically owned by World Packs.

---

## 16. Phase 4 integration gates for Phase 5

Phase 5 tests must assume only validated canonical Phase 4 input.

Before implementation begins, WORK-006/WORK-008 must prove:

1. old legacy stats are visible through typed canonical Phase 4 semantics,
2. resource-like legacy current state is mapped/read-through only when semantically safe,
3. unknown/custom keys survive,
4. all players are migrated/read safely, not only active player,
5. migration/read bridge is idempotent,
6. no competing authoritative legacy/new truth remains,
7. derived legacy fields are not promoted into base/current authority,
8. bounds/definition identity semantics are hardened,
9. no silent empty typed read masks real legacy data.

Until these pass, Phase 5 must not use an empty `playerStats()` / `playerResources()` result as proof that an old character has no stats/resources.

---

## 17. Phase 5 implementation sequencing contract

Recommended implementation order once Phase 4 is validated:

1. immutable Modifier/value/result contracts,
2. validator for modifier identity/target/lifetime/numeric validity,
3. deterministic stacking policy engine,
4. pure stat resolver,
5. rule registry/provider contract,
6. dependency DAG + cycle detection,
7. resource maximum/regeneration resolution,
8. provenance/contribution trace,
9. permanent source adapter,
10. injury source adapter where authoritative semantics exist,
11. temporary source adapter when typed active-effect facts exist,
12. equipment adapter only when authoritative equipped state exists,
13. PlayerState read projection integration,
14. CharacterPanel/Context integration,
15. optional derived cache only after all pure correctness tests pass.

Do not pull Talent/Potential implementation into this phase.

---

## 18. Release-gating invariant checklist

Phase 5 cannot be considered complete unless all are proven:

- derived values are rebuildable,
- base progression is never overwritten,
- current resource is never modified by resolver,
- same inputs produce same outputs,
- modifier UID duplicates cannot double-apply,
- source removal removes only its own effects,
- expired/inactive modifiers do not contribute,
- stacking policies are deterministic,
- priority/ties are deterministic,
- override is explicit and traceable,
- caps/min/max are deterministic,
- soft cap is rule-driven/versioned,
- missing rule fails loudly,
- incompatible rule version fails loudly,
- cycles fail before recursion loops,
- unknown target fails loudly,
- NaN/Infinity output fails loudly,
- cross-campaign/player contamination is impossible,
- cache deletion is lossless,
- provider/rule update invalidates cache,
- contribution trace explains every effective delta,
- Core remains World-Pack neutral.

---

## 19. Minimum future automated test suite names

Suggested test classes/files for the future implementation worker:

```text
DerivedValueResolverBaseTest
ModifierAdditiveStackingTest
ModifierMultiplicativeStackingTest
ModifierOverridePriorityTest
ModifierCapsTest
ModifierLifetimeTest
ModifierStackingPolicyTest
ModifierDeterminismTest
ModifierProvenanceTest
DerivedRuleBindingTest
DerivedDependencyCycleTest
DerivedResourceResolutionTest
DerivedNoRetrogressionTest
DerivedCacheRebuildTest
DerivedWorldPackNeutralityTest
DerivedCampaignIsolationTest
DerivedPlayerIsolationTest
Phase4Phase5IntegrationTest
```

Each suite should be pure/JVM wherever possible; persistence/integration tests should be separated from resolver arithmetic tests so failures clearly identify domain vs storage defects.

---

## 20. Acceptance examples

### Example A — basic stat

```text
base 100
permanent +10
equipment +20
injury -30
temporary +5
=> effective 105
=> persisted base still 100
```

### Example B — temporary penalty removal

```text
base 100
temporary -40
=> 60
remove temporary source
=> 100
base never changed
```

### Example C — resource

```text
current = 40
maxRule -> 100
regenRule -> 5
resolver output: currentObserved=40, max=100, regen=5
persisted current remains 40
```

### Example D — cycle

```text
A derives from B
B derives from A
=> deterministic CycleDependency error
```

### Example E — cache

```text
resolve X
delete derived cache
resolve from authoritative facts
=> X exactly again
```

---

# Final status

The Phase 5 implementation contract is architecture-ready and test-ready. The real Phase 4 contracts provide the correct conceptual base/current separation and opaque rule-binding UIDs. The unresolved dependency is not a missing Phase 5 design element; it is the currently failed Phase 4 legacy-integration validation gate.

**PHASE 5 IMPLEMENTATION CONTRACT READY — WAITING FOR PHASE 4 PASS**
