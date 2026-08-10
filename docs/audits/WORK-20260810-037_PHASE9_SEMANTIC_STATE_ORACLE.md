# WORK-20260810-037 — Phase 9 Semantic / State-Machine Oracle

Status: READ-ONLY RUNTIME / SEMANTIC + STATE-MACHINE ORACLE

Work ID: `WORK-20260810-037`
Owner: `CHAT-2`
Role: PHASE 9 SEMANTIC / STATE-MACHINE ORACLE
Repository: `piotreksmaga-art/rpg-os-android`
Fresh master inspected before write: `4f8431e4cdf983f7f12fa73e544d988db30953ad`
Accepted Phase 8 runtime: `7a28e6e4b28ff10cbb516b94e5e0c120d0a15397`
Primary architecture source: `docs/audits/WORK-20260810-034_NEXT_PHASE_ARCHITECTURE.md`

This report is an independent semantic oracle. It changes documentation only. It does not implement Phase 9 runtime, schema, migrations, World Pack rules, progression, DevelopmentProject, PlayerDomainEngine, inventory/equipment, or any later phase.

---

## 1. Canonical semantic separation

Phase 9 must not compress identity, inherited capability, evolution and temporary activation into one field or one state machine.

The following concepts are distinct authorities:

| Concept | Canonical meaning | Authority class | Must not imply |
|---|---|---|---|
| Origin/species identity | World-Pack-defined identity/origin relationship of the character | persistent authoritative identity state | every racial/innate feature |
| Clan identity | membership/lineage/group identity where the World Pack defines it | persistent authoritative identity/evidence | bloodline ownership |
| Innate feature ownership | durable possession of an intrinsic/inherited/acquired innate-domain feature | persistent authoritative | active form, Skill/Technique mastery |
| Bloodline feature | a World-Pack-classified innate feature whose source semantics are bloodline/lineage | persistent authoritative feature ownership | clan identity equality, current activation |
| Mutation | an innate-domain feature/state classified by the World Pack as mutation | persistent authoritative when acquired | temporary transformation |
| Evolution path | stable World-Pack-defined directed progression/state structure | definition authority | current stage by itself |
| Evolution stage | stable stage identity inside a path | definition authority; player attainment/current stage is persistent state | temporary form |
| Unlocked form | durable permission/ownership to activate a form | persistent authoritative | currently active form |
| Active form | currently active reversible form/state | runtime/current state, persisted only if required for save/reopen continuity | unlock acquisition or permanent base rewrite |
| Temporary transformation | transient activation/state whose consequences are derived | runtime/context + derived effects | evolution stage acquisition |
| Talent | learning/development efficiency profile | Phase-6 persistent authority | innate feature, current mastery |
| Potential | long-horizon growth/headroom profile | Phase-6 persistent authority | evolution stage/current power |
| Skill | learned general competence | Phase-7 persistent mastery authority | innate feature or Technique identity |
| Technique | concrete learned executable method/ability | Phase-8 persistent ownership/mastery authority | innate feature/evolution identity |

Hard separations:

```text
ORIGIN != INNATE FEATURE
CLAN != BLOODLINE FEATURE
OWNED FEATURE != ACTIVE FORM
UNLOCKED FORM != ACTIVE FORM
EVOLUTION STAGE != TEMPORARY TRANSFORMATION
TALENT != INNATE FEATURE
POTENTIAL != EVOLUTION STAGE
SKILL != INNATE FEATURE
TECHNIQUE != INNATE FEATURE
```

A World Pack may define explicit relationships between these concepts through stable UIDs and versioned mappings/rules. Core must never infer equivalence from text labels.

---

## 2. Identity/origin oracle

### 2.1 Origin/species identity

Origin answers an identity question, not a capability question.

A character can have an origin relationship without automatically owning every feature culturally or biologically associated with that origin.

Oracle invariant:

```text
PlayerOrigin(originUid = O)
DOES NOT IMPLY
PlayerInnateFeature(featureUid = F)
```

unless an explicit World Pack grant/mapping rule says that O grants F.

### 2.2 Multiple/hybrid origins

Core must not assume one global `race: String`.

Legal states may include:

- one origin,
- multiple origin relationships,
- hybrid ancestry,
- origin plus later acquired mutation,
- identity unchanged while capabilities evolve.

If a World Pack restricts origins to one mutually exclusive value, that restriction belongs to its rule/definition contract rather than to universal Core semantics.

### 2.3 Clan identity

`clan_uid` or equivalent identity evidence does not automatically grant a bloodline feature.

Canonical negative test:

```text
clanUid = C
no explicit mapping C -> feature F
=> F is NOT canonical owned state
```

Even when a World Pack later defines a canonical clan-to-feature relationship, it must be stable, versioned and provenance-bearing.

---

## 3. Innate feature ownership oracle

An innate feature is a durable intrinsic/non-learned feature according to World Pack semantics.

It may represent, generically:

- racial trait,
- inherited trait,
- bloodline/lineage trait,
- mutation,
- congenital capability,
- permanently acquired intrinsic property.

### 3.1 Ownership is persistent

If feature F is legally acquired/unlocked:

```text
owned(F) = true
```

then temporary suppression, deactivation, injury, resource depletion or form exit must not silently make ownership false.

### 3.2 Ownership is not activity

A feature may be owned but currently dormant/inactive.

```text
owned(F) = true
active(F/form) = false
```

is legal and expected.

### 3.3 Temporary effects cannot grant ownership

A Phase-5 modifier, temporary form effect or runtime condition may change effective stats/resources/Skill/Technique projections but cannot create a persistent `PlayerInnateFeature` row/state.

Required negative test:

```text
temporary modifier source active
=> no new persistent innate ownership
```

### 3.4 Feature ownership does not write other base domains

Acquiring or activating an innate feature must not directly copy values into:

- `PlayerStat.baseValue`,
- `PlayerSkill.baseMastery`,
- `PlayerTechnique.baseMastery`,
- Talent profile,
- Potential profile.

Any temporary mechanical consequence belongs to Phase-5 derived output. Any future permanent cross-domain change requires a legal domain mutation path, not implicit copying.

---

## 4. Bloodline and mutation semantics

### 4.1 Bloodline is a feature classification, not clan identity

A bloodline feature is an innate feature whose World Pack definition/provenance classifies it as lineage/bloodline-related.

The Core relation is:

```text
featureUid + featureKind/source semantics
```

not:

```text
clan name == bloodline feature
```

### 4.2 Mutation is not temporary form

A persistent mutation changes durable character innate-domain state. A temporary transformation is reversible runtime state.

Therefore:

```text
persistent mutation acquisition
!= temporary transformation activation
```

A temporary transformation may visually resemble a mutation but cannot be promoted to durable mutation ownership without an explicit legal persistent transition.

---

## 5. Evolution definition/state oracle

Evolution must be represented as a graph/track with stable identities rather than a universal integer level.

### 5.1 Evolution path

`EvolutionPathDefinition` identifies a World-Pack-defined progression/state graph.

A character may:

- have no state on a path,
- enter a path,
- attain one or more stages,
- have one current stage when the path semantics require it,
- participate in multiple independent paths when explicitly allowed.

### 5.2 Evolution stage

`EvolutionStageDefinition.stageUid` is identity.

Stage order cannot be inferred from names or a numeric suffix unless the World Pack explicitly defines ordering.

### 5.3 Attained/unlocked stage vs current stage

Historical attainment and current canonical stage are distinct facts.

When A -> B occurs legally:

- B becomes attained/current according to path semantics,
- history that A was attained must not be erased merely because B is current,
- any rollback/reversal requires an explicit transition or explicit World Pack rule.

### 5.4 Multiple tracks

Core must not assume one global `evolutionLevel`.

Two independent evolution paths can coexist if World Pack rules permit them. Cross-path transitions are illegal unless an explicit transition definition declares the relationship.

---

## 6. Canonical evolution transition state machine

A transition is an explicit definition:

```text
transitionUid
sourceStageUid (or path-entry source)
targetStageUid
pathUid / declared cross-path semantics
requirements binding
version/provenance
```

### EV-01 — legal transition

Given:

```text
current stage = A
transition T: A -> B
T exists and is valid
transition requirements satisfied
```

Expected:

```text
current stage = B
B attained/unlocked according to path semantics
A remains in historical attainment/provenance where history is tracked
transition provenance identifies T
```

### EV-02 — missing transition

Given current A and target B with no explicit transition A -> B:

Expected deterministic rejection. Core must not infer `B` as the next stage from labels/order.

### EV-03 — wrong source

Transition T is C -> B while current stage is A.

Expected rejection.

### EV-04 — cross-path transition

Source is on path P1 and target on P2.

Expected rejection unless T explicitly permits/defines cross-path semantics.

### EV-05 — rollback

Current B, prior A.

Changing B -> A is not legal merely because A existed historically. It requires an explicit reversible transition or a separate legal state mutation rule.

### EV-06 — repeat/idempotency

Replaying the same committed transition must not duplicate attainment/history or repeatedly apply permanent effects. The eventual mutation path must use stable transaction/transition provenance.

---

## 7. Form/unlock state machine

The canonical reversible form lifecycle is:

```text
LOCKED
  -> UNLOCKED + INACTIVE
  -> ACTIVE
  -> INACTIVE
  -> ACTIVE ...
```

Unlock is persistent authority. Activity is current/runtime state.

### FORM-01 — locked

```text
unlocked = false
active = false
```

Activation attempt must fail deterministically.

### FORM-02 — unlock

After legal unlock:

```text
unlocked = true
active = false
```

No automatic activation is required unless the World Pack explicitly defines activation as part of the unlock transition.

### FORM-03 — activate

Given unlocked=true and activation requirements satisfied:

```text
unlocked remains true
active becomes true
```

Derived form effects may now participate in Phase-5 resolution.

### FORM-04 — deactivate

```text
active true -> false
unlocked remains true
```

This is a release-blocking invariant.

### FORM-05 — reactivate

An unlocked inactive form may become active again if activation requirements are satisfied. No second unlock/grant is created.

### FORM-06 — source suppression

Temporary inability to activate/use a form does not delete the unlock.

### FORM-07 — active without unlock

`active=true` while no unlock/ownership exists must fail validation unless the World Pack explicitly models an external temporary form that does not require a persistent unlock. Such an exception must be explicit definition semantics, never a generic silent fallback.

### FORM-08 — mutually exclusive forms

If forms F1 and F2 are in a World-Pack-defined exclusive group, simultaneous activation is rejected or resolved by an explicit transition policy. Core must not assume all forms are mutually exclusive globally.

---

## 8. Temporary transformation oracle

A temporary transformation is runtime/contextual activation, not durable evolution progression.

Canonical sequence:

```text
base authoritative state
-> activate temporary transformation
-> emit/enable derived Phase-5 effects
-> deactivate transformation
-> derived effects disappear
-> base authoritative state is unchanged
```

Required snapshots before/after transformation:

```text
PlayerStat.baseValue               identical
PlayerSkill.baseMastery            identical
PlayerTechnique.baseMastery        identical
TalentProfile persistent values    identical
PotentialProfile persistent values identical
owned innate features              identical unless a separate legal permanent grant occurred
attained evolution stages          identical unless a separate legal transition occurred
```

### TR-01 — stat effect

Transformation may produce `STAT_EFFECTIVE` modifiers. Removal restores derived result from the unchanged base.

### TR-02 — resource effect

Transformation may change derived resource maximum/regeneration through generic target bindings. Resolver must not silently rewrite current/base authoritative data.

### TR-03 — Skill effect

Transformation may affect `SKILL_EFFECTIVE`. It must not persist to `baseMastery`.

### TR-04 — Technique effect

Transformation may affect `TECHNIQUE_EFFECTIVE`. It must not persist to Technique base mastery.

### TR-05 — Talent/Potential

Temporary transformation may influence a future derived progression parameter if a World Pack rule allows it, but it cannot directly rewrite persistent Talent or Potential.

---

## 9. Requirement semantics — three distinct contracts

Phase 9 must not use one ambiguous global `requirement` meaning for all state changes.

At minimum distinguish:

1. **UNLOCK requirement** — conditions for durably obtaining/unlocking a feature/form/path/stage permission.
2. **TRANSITION requirement** — conditions for committing an evolution transition from one persistent stage/state to another.
3. **ACTIVATION requirement** — conditions for entering/maintaining a currently active reversible form.

These requirements may share infrastructure/rule-provider types, but their semantic phase and consequences are different.

### 9.1 Unlock requirement

Failure means the durable unlock is not granted.

After unlock has been legally committed, later failure of the original unlock requirement does not automatically revoke the unlock unless an explicit revocation mechanic exists.

### 9.2 Transition requirement

Evaluated against a declared source/target transition. Passing it permits a persistent state transition proposal/commit.

A transition requirement is not equivalent to current form activation eligibility.

### 9.3 Activation requirement

Evaluated for reversible current activation.

If it later fails:

- activation may be blocked or terminated according to World Pack rules,
- persistent unlock remains,
- attained evolution state remains,
- base progression remains.

### 9.4 Requirement authority inputs

Requirements may legally depend on stable UID-addressed inputs such as:

- Skill base/effective mastery,
- Technique learned/base/effective mastery,
- Stat base/effective value,
- Resource current/derived max availability,
- Talent/Potential profile values where semantically appropriate,
- owned innate features,
- attained/current evolution stages,
- campaign/world facts,
- equipment/organization/etc. once those domains are typed.

The requirement definition must state which authority level it consumes. Core must not silently substitute effective for base or vice versa.

---

## 10. Requirement examples

### REQ-01 — unlock requirement satisfied

A form unlock rule requires feature F owned.

If F is owned, unlock may proceed through the legal persistent mutation path. The rule does not itself activate the form.

### REQ-02 — unlock requirement later false

Suppose the World Pack allowed temporary context during legal unlock and the unlock was committed.

Afterward the context disappears.

Expected: unlock remains unless explicit revocation semantics exist.

### REQ-03 — activation uses effective Skill

Activation rule explicitly requires Skill S `effectiveMastery >= 50`.

Base=80, injury reduces effective to 40.

Expected: activation fails/is unavailable while injury is active; Skill base remains 80 and form unlock remains.

### REQ-04 — activation uses base Skill

If the rule explicitly requires `baseMastery >= 50`, the same injury does not make the requirement fail solely by reducing effective mastery.

### REQ-05 — transition requirement

A -> B transition requires feature F and a World Pack rule result.

If requirement fails, current stage remains A. No partial B unlock/current-state write is allowed.

---

## 11. Talent / Potential boundary

Talent and Potential are not Phase-9 identity/state substitutes.

Forbidden shortcuts:

```text
high Talent -> grant innate feature
high Potential -> set evolution stage
Potential value -> evolutionLevel
Talent value -> bloodline strength ownership
```

Future ProgressionEngine/WorldRuleProvider may consume Talent/Potential when evaluating progression or evolution eligibility, but Phase 9 stores the resulting legal state only after explicit validated transition/grant.

A character may have high evolution Potential yet remain at an early evolution stage because the required causal progression/transition has not occurred.

---

## 12. Skill boundary

Skill is learned competence, not innate state.

Forbidden:

- Skill name/UID implies race/bloodline ownership,
- high Skill mastery grants a feature automatically,
- feature acquisition copies a value into Skill base mastery,
- form activation rewrites Skill base mastery.

Legal relationship:

- an innate feature/evolution stage may be a requirement or contextual source for Skill/Skill progression rules;
- a Skill may be a requirement for unlock/transition/activation;
- temporary form effects may modify `SKILL_EFFECTIVE` through Phase 5.

---

## 13. Technique boundary

Technique is a concrete learned executable method/ability, not proof of innate state.

Forbidden:

```text
knows Technique T -> infer feature F
feature F -> automatically copy/grant Technique mastery
Technique mastery -> evolution stage
```

A World Pack may explicitly define that feature F permits/unlocks access to Technique T. That relationship changes eligibility/availability, not Technique mastery by copying.

A legal grant/acquisition of Technique remains a separate persistent domain change with its own provenance.

---

## 14. Stat / Resource boundary

Stats/resources can be consequences, requirements or context, not identity evidence.

Forbidden:

```text
high stat -> infer evolved stage
resource type exists -> infer species/race
active-form modifier -> persist stat base
```

Form/evolution consequences should use the existing generic Phase-5 modifier/resolver system for rebuildable effective values.

Resource activation costs must use stable `ResourceDefinition.resourceUid` mappings. Resource depletion may prevent activation/maintenance but must not erase unlock or evolution history.

---

## 15. Legacy evidence oracle

The following are evidence until explicitly mapped:

- `character_status_snapshot`,
- `legacy_status.*`,
- `clan_uid`,
- free-text race/species/bloodline/form/evolution labels,
- prompt text,
- historical panel text,
- canon constraint text/metadata unless its semantics are explicitly structured/mapped.

### LEG-01 — clan only

Legacy `clan_uid=C`, no World Pack mapping.

Expected: preserve evidence; do not create bloodline feature.

### LEG-02 — race label only

Legacy label `race=R`, no mapping.

Expected: preserve unresolved evidence; do not create canonical origin or feature.

### LEG-03 — bloodline label only

Expected: no canonical feature without explicit mapping.

### LEG-04 — evolution label only

Expected: no canonical path/stage without explicit mapping.

### LEG-05 — form label only

Expected: no form unlock/active state without explicit mapping.

### LEG-06 — explicit mapping

Given versioned mapping from source evidence identity to canonical World Pack UID/state semantics:

Expected exactly one canonical state with mapping/provenance; original legacy bytes/evidence remain.

### LEG-07 — mixed legacy + typed, no mapping

If typed state and legacy evidence appear to share a label/key but no explicit mapping proves equivalence:

Expected fail-loud/unresolved compatibility state, never silent deduplication or automatic preference.

### LEG-08 — same label across World Packs

Same human-readable label in two packs must remain distinct by stable UID. No global name-based merge.

---

## 16. Authority/state transition matrix

| Action/input | May change origin? | May grant innate feature? | May change evolution stage? | May change active form? | May mutate base stat/mastery/Talent/Potential directly? |
|---|---:|---:|---:|---:|---:|
| Legacy label read | No | No | No | No | No |
| Clan identity read | No | No without mapping | No | No | No |
| Explicit persistent origin assignment | Yes | No unless separate explicit grant | No | No | No |
| Legal innate grant | No | Yes | No unless separate transition | Maybe unlock dependency, not automatic active | No |
| Legal evolution transition | No | Only if transition explicitly includes separate grant semantics | Yes | May alter allowed forms, not necessarily active | No implicit base rewrite |
| Form unlock | No | No | No | establishes permission only | No |
| Form activation | No | No | No | Yes | No; derived effects only |
| Form deactivation | No | No | No | Yes -> inactive | No |
| Phase-5 modifier | No | No | No | No persistent change | No |
| Talent/Potential update | No | No | No | No | Only own profile through legal mutation |
| Skill/Technique progression | No | No | No | No | Only own domain through legal mutation |

---

## 17. Determinism / no-retrogression oracle

For the same authoritative snapshot, same World Pack rule/provider versions and same runtime activation inputs:

- validation outcome must be deterministic,
- legal transition identity must be deterministic,
- active-form validation must not depend on DB row order,
- Phase-5 derived effects must inherit its deterministic ordering/fingerprints,
- replay without a new legal persistent mutation must not accumulate permanent changes.

No-retrogression release blockers:

1. deactivation deletes unlock;
2. injury deletes feature ownership;
3. resource depletion deletes evolution stage;
4. temporary modifier permanently alters base stat/Skill/Technique mastery;
5. form expiry mutates Talent/Potential;
6. evolution transition silently deletes historical attainment;
7. reopening a campaign changes current/unlocked state without a committed transition.

---

## 18. Persistence oracle

After close/reopen with no domain change:

Persistent equality must hold for:

- origin relationships,
- innate feature ownership,
- form unlocks,
- attained evolution stages,
- current persistent evolution stage/state,
- versions/provenance.

For active form:

- if the runtime contract intentionally persists current activation across save/reopen, it must restore exactly as current/runtime state without duplicating unlock or permanent effects;
- if the contract intentionally clears transient activations on reopen, that policy must be explicit and deterministic.

What is forbidden is accidental behavior where persistence presence/absence silently changes the meaning of ownership.

---

## 19. World Pack isolation oracle

Every canonical definition is owned by a World Pack stable UID.

Tests:

### WP-01 ownership hijack

Pack B attempts to register incompatible metadata under Pack A's feature/path/stage/form UID.

Expected deterministic rejection.

### WP-02 same label, different UID

Legal coexistence. No merge by display label.

### WP-03 relationship target ownership

Mappings/transition/form relationships must point to valid compatible definitions. Missing/deleted/owner-mismatched targets fail validation or remain unresolved according to explicit migration semantics.

### WP-04 no universe literals in Core

Core source must contain no branches whose correctness depends on specific race/clan/bloodline/evolution names.

---

## 20. Minimum semantic test oracle for WORK-036 recheck

After implementation appears, CHAT-2 semantic recheck should verify at minimum:

1. origin and feature ownership use distinct typed state where both concepts are implemented;
2. clan identity alone grants nothing;
3. stable UIDs/World Pack ownership determine identity;
4. owned/unlocked and active are stored/resolved separately;
5. `locked -> unlocked/inactive -> active -> inactive` preserves unlock;
6. active-without-unlock fails unless an explicit external-form definition contract exists;
7. evolution stage identity is UID/path-based, not integer/name inference;
8. A -> B requires explicit transition identity;
9. invalid source/target/cross-path transition fails deterministically;
10. rollback requires explicit reversible transition;
11. unlock, transition and activation requirements are semantically distinguished;
12. temporary activation/deactivation does not mutate base stat;
13. temporary activation/deactivation does not mutate Skill base mastery;
14. temporary activation/deactivation does not mutate Technique base mastery;
15. temporary activation/deactivation does not mutate Talent/Potential;
16. temporary modifiers cannot create persistent feature ownership/unlock;
17. legacy labels remain unresolved without mapping;
18. explicit mapping produces one canonical identity and preserves source evidence;
19. same-name cross-pack definitions remain separate;
20. close/reopen preserves authoritative Phase-9 state according to explicit active-form persistence policy.

---

## 21. Findings against the current pre-Phase-9 repository

The current repository before WORK-036 has no accepted typed Phase-9 runtime. WORK-034 correctly identifies legacy/reference evidence such as `character_status_snapshot`, `legacy_status.*`, `canon_characters_v2.clan_uid` and canon constraints, while warning that these are not automatically canonical innate/evolution state.

The accepted Phase-8 runtime already provides the correct neighboring precedents:

- stable World-Pack-owned definition identity,
- separate persistent player ownership/mastery,
- conservative legacy reconciliation,
- typed Skill/Technique boundaries,
- generic Phase-5 effective-value targets rather than domain-specific modifier engines.

Phase 9 should extend those principles without turning identity/evolution into learned abilities or temporary modifier state.

CI note: the GitHub combined-status connector returned no status entries for accepted runtime `7a28e6e4b28ff10cbb516b94e5e0c120d0a15397` during this audit. The coordinator's accepted Phase-8 evidence remains the release authority; this semantic report does not independently re-certify CI.

---

# Final status

**PHASE 9 SEMANTIC ORACLE READY**
