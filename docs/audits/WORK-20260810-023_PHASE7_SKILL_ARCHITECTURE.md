# WORK-20260810-023 — Phase 7 Skill Model Architecture

Status: READ-ONLY RUNTIME / FUTURE PHASE ARCHITECTURE AUDIT

Work ID: `WORK-20260810-023`
Worker: `CHAT-4`
Role: PHASE 7 SKILL MODEL READ-ONLY AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Coordinator-issued baseline: `387a0c331eaa11863529a4eababa8dd580c30ff2`
Fresh master used for final write: `98296c2c18a4f07659435549fb4e95e02379f82a`
Accepted Phase 5 runtime: `44011bc0177df846a34fa12d0009d33e887f6c23`
Observed Phase 6 implementation result while auditing: `edce3524998abf2ffb5a6293b63b06b73f11b7cd` (`WORK-20260810-020 — implement TalentProfile and PotentialProfile`)

This document is architecture/audit only. It does not implement Skill runtime, schema, migration, ProgressionEngine, Technique redesign, PlayerCommand, PlayerChangeSet, PlayerDomainEngine or CharacterPanelSnapshot v2.

---

## 1. Executive conclusion

The existing project already contains real Skill persistence and read paths, but not yet a canonical typed Skill domain contract.

Confirmed legacy/current behavior:

- `character_skills` contains at least `entity_uid`, `skill_uid`, `mastery`, `xp`, `updated_chapter`;
- `CharacterPanelReader` joins `character_skills.skill_uid` to `skill_definitions.skill_uid` and presents definition `name`, player `mastery`, and definition `category`;
- `ContextBuilder` sends player Skill rows to the backend using the authoritative active player UID;
- backend system instructions currently say `player_skills` and `player_techniques` are authoritative learned abilities;
- Skill and Technique persistence/read paths are already separate.

This is enough to require migration/integration, not replacement by an unrelated second truth.

The target Phase 7 model should therefore introduce a generic World-Pack-owned `SkillDefinition` plus campaign/character-scoped `PlayerSkill` while preserving legacy Skill identity and values through an explicit compatibility/reconciliation policy.

Canonical semantic separation:

```text
Skill
= current learned competence / mastery in a specific learned capability

Skill != Talent
Skill != Potential
Skill != Technique
Skill != Stat
```

Phase 7 implementation remains blocked until Phase 6 is formally accepted by the coordinator, because the actual Phase 7 domain-binding contract should target the accepted `ProgressionDomainDefinition` runtime rather than a provisional duplicate abstraction.

---

## 2. Existing Skill data and read paths

### 2.1 `character_skills`

Runtime code confirms at least:

```text
entity_uid
skill_uid
mastery
xp
updated_chapter
```

`ContextBuilder` reads:

```sql
SELECT entity_uid,skill_uid,mastery,xp,updated_chapter
FROM character_skills
WHERE entity_uid=?
ORDER BY mastery DESC,xp DESC
LIMIT 50
```

Important interpretation:

- `entity_uid` is current character identity in legacy storage;
- `skill_uid` is already an identity-like key and should be preserved as migration evidence;
- `mastery` is current persistent learned competence in the existing system;
- `xp` is current persisted progress-like state, but its exact scale/meaning must be re-audited before migration assumptions are made;
- `updated_chapter` is useful historical metadata but is not sufficient provenance by itself.

The `LIMIT 50` belongs to context retrieval/presentation, not to the target authoritative Skill store. Phase 7 authoritative reads must not silently truncate characters with more skills.

### 2.2 `skill_definitions`

`CharacterPanelReader` proves a definition table exists and joins on `skill_uid`:

```sql
SELECT s.name,cs.mastery,s.category
FROM character_skills cs
JOIN skill_definitions s ON s.skill_uid=cs.skill_uid
WHERE cs.entity_uid=?
```

Confirmed definition attributes from runtime reads are therefore at least:

```text
skill_uid
name
category
```

The full binary bundled DB schema was not decoded during this read-only audit. Before implementation, Phase 7 must inspect the actual current `skill_definitions` schema and bundled campaign/world-pack files rather than infer additional columns from names.

### 2.3 CharacterPanel

Current presentation model is:

```text
SkillLine(name, mastery, category)
```

It is presentation only. It does not carry:

- campaign ID,
- character UID,
- World Pack ownership,
- provenance,
- version,
- base/effective mastery separation,
- requirements,
- progression-domain links.

It must not become the new Skill source of truth.

### 2.4 ContextBuilder / backend prompt

The active player is resolved through `ActivePlayerStore`, and Skill rows are scoped to that UID. This preserves the Phase 3 identity contract.

Backend prompt currently treats `player_skills` as authoritative learned abilities. This is semantically useful but too broad for the future architecture: after Phase 7, the backend/context should distinguish canonical learned/base Skill state from derived effective mastery and should never invent Skill progress directly from narrative.

### 2.5 Current repository API gap

`CampaignRepository` currently exposes typed stat/resource APIs but has no typed SkillDefinition/PlayerSkill API. Skills still enter higher layers through direct SQL in legacy readers.

Phase 7 should close that boundary rather than add another direct-SQL consumer.

---

## 3. Canonical Skill definition

A Skill is an acquired, current competence that can improve over time.

Examples belong to World Packs; Core only models the generic concept.

A Skill may have:

- stable identity,
- learned/unlearned state,
- base mastery,
- progress/XP accumulator according to explicit World Pack semantics,
- requirements for learning/use/progression,
- one or more progression-domain bindings,
- provenance/version history,
- derived effective mastery under temporary conditions.

A Skill is not:

- learning efficiency (`Talent`),
- long-term growth headroom (`Potential`),
- a physical/spiritual stat,
- a concrete executable technique,
- a temporary modifier,
- a narrative claim.

---

## 4. Final target Core model

Exact class/table names remain implementation-time choices. The semantic target should be equivalent to the following.

### 4.1 `SkillDefinition`

Conceptual fields:

```text
skillUid                 stable skill identity
worldPackUid             owner
key                      stable non-localized key
displayName              presentation only
category                 generic grouping
baseProgressionDomainUid? optional primary/default domain link
definitionVersion        semantic version
requirementsRuleUid?     optional versioned rule binding
progressionRuleUid?      future ProgressionEngine binding
masteryScaleUid?         explicit mastery scale/interpretation
tags/metadata?           extensibility
provenance               definition source
```

Required invariants:

- `skillUid` is identity; display name is not;
- World Pack ownership is immutable without explicit versioned migration;
- duplicate UID with incompatible metadata fails loudly;
- duplicate `(worldPackUid,key)` under different UIDs fails unless an explicit version/supersession contract exists;
- same display name/key in different World Packs may coexist;
- Core never branches on universe-specific skill names.

### 4.2 `PlayerSkill`

Conceptual fields:

```text
campaignId
characterUid
skillUid
baseMastery
progressValue / xp
entryVersion
provenance
learnedAtEventUid? / learnedAtChapter?
```

Logical identity:

```text
(campaignId, characterUid, skillUid)
```

`baseMastery` is authoritative learned competence before temporary derived effects.

`progressValue/xp` is authoritative progression state only after the Skill definition/rule contract explicitly defines its semantics. Phase 7 must not guess whether every legacy `xp` means lifetime XP, XP-to-next-level, fractional mastery progress, or another world-specific scale.

### 4.3 Learned/unlearned state

Preferred canonical rule:

- absence of `PlayerSkill` means the character has not learned the Skill unless another explicit discovery/eligibility subsystem says otherwise;
- presence of `PlayerSkill` means learned/acquired;
- temporary inability to use a Skill does **not** delete the row or make it unlearned;
- prerequisites becoming temporarily false do not erase historical mastery.

If future design needs `DISCOVERED`, `AVAILABLE`, `LOCKED`, or similar states, those should be modeled as discovery/eligibility state rather than overloading mastery.

This avoids storing one row for every unlearned definition while preserving a clear learned-state contract.

---

## 5. Mastery semantics

### 5.1 Authoritative base mastery

Phase 7 should adopt:

```text
PlayerSkill.baseMastery
= persistent learned competence
```

This mirrors the architectural split already established for stats:

```text
PlayerStat.baseValue
!= ResolvedStat.effectiveValue
```

and extends it to Skill without copying stat semantics.

### 5.2 Effective mastery

Conceptually:

```text
baseMastery
+/- valid derived/contextual effects
= effectiveMastery
```

Examples of temporary effects:

- injury penalty,
- fatigue/condition penalty,
- equipment-assisted competence,
- temporary transformation or stance effect,
- environmental/contextual modifier.

`effectiveMastery` is DERIVED and rebuildable.

It must never overwrite `PlayerSkill.baseMastery` merely because a modifier becomes active or expires.

### 5.3 Numeric scale

Core should not assume a universal `0..100` mastery scale unless the project explicitly adopts one.

A definition or generic mastery-scale registry should specify:

- valid range,
- representation,
- rounding/canonicalization,
- interpretation thresholds if any,
- whether progress/XP maps continuously or discretely to mastery.

NaN and Infinity must always be rejected.

---

## 6. XP / progress contract

Existing `character_skills.xp` must be preserved, but exact meaning requires schema/data/rule audit before canonical conversion.

Phase 7 should distinguish:

```text
current competence = baseMastery
progress accumulator = progressValue / xp
```

Rules:

1. XP/progress is not Talent.
2. XP/progress is not Potential.
3. XP/progress does not automatically equal mastery.
4. The future ProgressionEngine, not Phase 7, decides how a legal activity changes XP/mastery.
5. A Skill definition/rule provider must define conversion semantics.
6. Migration must preserve unknown legacy XP exactly rather than reinterpret it.

If the existing World Pack can explicitly prove the legacy XP scale, it may be mapped directly. Otherwise preserve it as migration evidence/compatibility state until a versioned mapping exists.

---

## 7. Relationship with Phase 6 Talent/Potential

The actual WORK-020 runtime introduces `ProgressionDomainDefinition` with stable:

```text
domainUid
worldPackUid
key
displayName
category
parentDomainUid
appliesToTalent
appliesToPotential
definitionVersion
provenance
```

Phase 7 should reuse this accepted domain identity instead of defining `SkillDomain` as a competing system.

### 7.1 Skill-to-domain binding

A Skill may be associated with one or more progression domains.

Recommended design:

```text
SkillProgressionDomainBinding {
  skillUid
  domainUid
  role / bindingKind
  priority/weight/rule metadata if required
  bindingVersion
  provenance
}
```

Do not put arbitrary universe-specific domain names in Core.

A single optional primary domain field can be convenient, but a many-to-many binding is safer because one learned competence may legally depend on several progression domains in some World Packs.

### 7.2 Talent interaction

Future ProgressionEngine may consume relevant Talent entries to determine how efficiently training produces Skill progress.

Hard invariant:

```text
Talent change
!= direct Skill mastery rewrite
```

High Talent does not grant or level the Skill without a causal progression action.

### 7.3 Potential interaction

Potential may influence high-end growth/headroom/diminishing returns in future ProgressionEngine rules.

Hard invariant:

```text
Potential change
!= direct Skill mastery rewrite
```

A high-Potential character can still have low current Skill mastery.

### 7.4 Domain hierarchy

`ProgressionDomainDefinition.parentDomainUid` does not imply automatic Skill double-scaling.

If a Skill binds to a child domain and a parent also has Talent/Potential values, the future rule provider must explicitly define aggregation/inheritance. Core must not silently apply both.

---

## 8. Relationship with Phase 5 modifiers

Accepted Phase 5 runtime currently has target kinds:

```text
STAT_EFFECTIVE
RESOURCE_MAXIMUM
RESOURCE_REGENERATION
```

Therefore **effective Skill mastery is not yet a first-class Phase 5 modifier target**.

Phase 7 must not solve this by creating `SkillModifierEngine` or a second resolver.

Preferred future extension:

- add a generic Phase-5-compatible target scope such as `SKILL_EFFECTIVE` or a more general `DERIVED_VALUE`/domain-target abstraction;
- preserve the existing deterministic lifecycle/operation/priority/UID semantics;
- ensure modifier target identity uses canonical `skillUid`;
- keep modifier rows as derived inputs, not Skill authority.

Temporary Skill effects must follow the same no-retrogression contract:

```text
baseMastery = 60
injury effective penalty = -20
=> effectiveMastery = 40
baseMastery remains 60

remove injury
=> effectiveMastery = 60
```

Equipment removal, debuff expiry, or environmental changes similarly remove only derived contributions.

Phase 7 implementation should extend Phase 5 only if coordinator explicitly allows the required generic target integration; it must not fork Phase 5 semantics.

---

## 9. Relationship with Stats/Resources

Skill may use stats/resources as requirements or progression context through versioned rules, but must remain a separate domain.

Forbidden shortcuts:

- `Skill mastery = PlayerStat.baseValue`;
- high stat automatically creates/levels a Skill;
- resource amount becomes Skill XP;
- derived effective stat is persisted as Skill mastery without a legal progression event.

A rule may legally say a stat is required to learn/use/progress a Skill, but that is a rule dependency, not identity equivalence.

---

## 10. Relationship with Technique

Current runtime already stores and reads Skills and Techniques separately. The target architecture should preserve and strengthen this separation.

Canonical distinction:

```text
Skill
= general learned competence

Technique
= concrete executable method/action/application
```

A Technique may require one or more Skills at specified mastery/effective mastery thresholds, but:

- learning a Skill does not automatically grant every Technique;
- learning a Technique does not automatically set Skill mastery;
- temporary loss of effective Skill capability does not erase a learned Technique;
- Technique usage may later create Skill progression events through ProgressionEngine, but Phase 7 does not implement that behavior.

The current world-specific `chakra_cost` presentation in `TechniqueLine` is legacy UI and must not influence generic Skill Core design.

---

## 11. Requirements contract

Skill requirements should be versioned, deterministic World Pack/rule-provider data.

Possible requirement inputs:

- other Skill UID/mastery,
- Stat UID/effective/base value according to explicit rule,
- progression domain/profile value,
- innate/bloodline/evolution state,
- prerequisite Technique/knowledge where appropriate,
- narrative/world facts only through structured rule inputs.

The Core `SkillDefinition` should use opaque requirement/rule UIDs rather than hardcoded requirement fields for specific universes.

Important no-retrogression distinction:

```text
requirement for learning/use/progression
!= automatic ownership/mastery deletion when requirement later fails
```

Temporary requirement failure should generally disable use/progression through derived/eligibility state, not erase the learned Skill.

Permanent loss/downgrade of a learned Skill requires a future explicit legal domain mutation with provenance and no-retrogression validation.

---

## 12. Provenance and versioning

Every canonical Skill definition and player Skill should support explainability.

### Definition provenance

Examples:

- World Pack seed,
- World Pack version update,
- canonical imported definition,
- explicit migration.

### PlayerSkill provenance

At minimum retain enough source information to identify why the Skill became authoritative player state.

Examples for future domain paths:

- character creation,
- training event,
- mentor instruction,
- mission/reward,
- explicit legacy migration,
- committed domain change.

Phase 7 must not invent historical training events for old rows that only contain `updated_chapter`.

Version rules:

- definition version changes cannot silently reinterpret existing mastery/XP;
- player entry version changes only on legal authoritative change/migration;
- changing display name does not change identity;
- changing Skill ownership/domain semantics requires explicit migration policy.

---

## 13. No-retrogression contract

Skill mastery is a durable achievement unless there is an explicit legal permanent-loss mechanic.

Release-blocking invariants for future Phase 7:

1. injury cannot lower stored base mastery;
2. fatigue cannot lower stored base mastery;
3. equipment removal cannot lower stored base mastery;
4. temporary debuff cannot lower stored base mastery;
5. expired buff cannot leave mastery permanently increased;
6. failed requirements do not silently delete a learned Skill;
7. Talent/Potential changes do not rewrite mastery;
8. deleting derived cache/effective Skill projection does not lose Skill authority;
9. repeated resolution cannot accumulate penalties into base mastery;
10. migration cannot reduce legacy mastery merely to fit a new scale without explicit lossless conversion policy.

If the story/world has a real permanent Skill-loss mechanic, it must later use the legal PlayerDomain/transaction path with explicit cause, validation, event and provenance. Phase 7 should not create an unrestricted setter as a shortcut.

---

## 14. Legacy migration strategy

### 14.1 Preserve existing rows

Existing `character_skills` rows are real campaign state and must remain intact during migration until independent cleanup is explicitly authorized.

### 14.2 Legacy identity

Existing `skill_uid` is strong migration evidence but cannot automatically be assumed to be globally World-Pack-owned canonical identity without checking `skill_definitions` ownership/source.

Safe paths:

- direct reuse when a versioned World Pack migration proves the existing UID is already canonical;
- explicit legacy Skill alias/supersession mapping to a canonical `SkillDefinition.skillUid`;
- reserved legacy compatibility identity/read-through when ownership/definition semantics cannot yet be proven.

### 14.3 Never reconcile by display name

Same `name`/`category` does not prove semantic equivalence.

Do not globally merge two Skills because both are called the same thing.

### 14.4 Mixed legacy + typed

Follow the Phase 4 reconciliation lesson:

- explicit mapping identifies legacy UID -> canonical Skill UID;
- canonical typed representation wins after valid mapping;
- legacy bytes remain for compatibility/audit;
- unmapped unrelated legacy Skill remains visible;
- apparent same-name/same-key mixed state without mapping fails loudly or remains explicitly unresolved;
- no silent duplicate authoritative resolver/progression inputs.

### 14.5 Legacy mastery

Legacy `mastery` should be preserved exactly when representation is compatible.

If new mastery scale differs, migration requires a deterministic versioned conversion rule. No silent clamp/normalization.

### 14.6 Legacy XP

Preserve exact legacy XP. Do not infer a progression formula from the numeric value.

### 14.7 Unknown/custom skills

Unknown custom `skill_uid` values must survive. Phase 7 must not only migrate skills known to the bundled World Pack.

---

## 15. Campaign/player/domain isolation

Canonical Skill reads/writes must be scoped by:

```text
campaignId
characterUid
skillUid
```

Required behavior:

- same character UID string in two campaigns is independent;
- Player A Skill cannot leak to Player B;
- active-player UI/repository convenience reads use persisted `ActivePlayerRef`;
- migration correctness processes all source entities, not only the active player;
- a Skill bound to domain A does not automatically consume domain B Talent/Potential;
- World Pack A cannot hijack World Pack B Skill UID or progression domain UID.

---

## 16. Repository/read-model target

Phase 7 should introduce a typed boundary equivalent to:

```text
skillDefinitions(worldPackUid?)
playerSkills(characterUid)
```

and registration/mutation methods only to the extent authorized by the Phase 7 work item.

Application/UI/context should stop depending on direct `character_skills` SQL once compatibility is proven.

Preferred flow:

```text
legacy/typed Skill persistence
-> SkillStore / repository boundary
-> optional Phase 5 effective mastery resolution
-> Context/Snapshot presentation
```

CharacterPanel v1 remains a legacy read model until its own roadmap phase.

---

## 17. Backend/context contract after Phase 7

Current backend prompt says `player_skills` are authoritative learned abilities. The refined contract should eventually communicate:

- canonical learned Skill identity,
- base mastery/progress as authoritative,
- effective mastery only as derived projection,
- provenance/version where useful,
- no direct AI mutation of mastery/XP outside legal future PlayerDomain path.

AI may narrate training attempts and outcomes supplied by mechanics. It must not decide arbitrary mastery increments by itself.

The current ContextBuilder `LIMIT 50` is acceptable only as bounded context retrieval. It must never be interpreted as the full authoritative Skill inventory.

---

## 18. Future ProgressionEngine contract

Phase 7 must not implement ProgressionEngine, but Skill architecture must be ready for it.

Conceptual future input:

```text
ProgressionAction/Event
SkillDefinition
PlayerSkill.baseMastery
PlayerSkill.progressValue
relevant Talent profile values by progression domain
relevant Potential profile values/dimensions
Phase 5 contextual/effective modifiers
stats/resources/conditions required by the rule
mentor/environment/method/difficulty/intensity/duration
rule/provider version
```

Conceptual output is a proposed authoritative progression change plus explanation/ledger data, later committed through the legal mutation path.

Important causal rule:

```text
Talent/Potential are inputs to progression
not replacements for current Skill state
```

No Skill gain occurs merely because Talent/Potential are high.

---

## 19. Required future Phase 7 tests

### Identity / ownership

1. stable Skill UID survives reopen;
2. duplicate UID incompatible metadata fails;
3. same label in different World Packs remains distinct;
4. World Pack ownership hijack fails;
5. duplicate `(worldPackUid,key)` conflicting UID fails;
6. missing definition fails loudly or remains explicit compatibility state.

### Mastery / progress

7. learned Skill persists exact base mastery;
8. unlearned Skill absence is distinct from learned mastery zero;
9. XP/progress persists independently from mastery;
10. invalid numeric mastery/XP fails according to scale contract;
11. NaN/Infinity rejected;
12. reopen equality;
13. >1000 Skills no truncation in authoritative store.

### Separation

14. Skill update does not mutate Talent;
15. Skill update does not mutate Potential;
16. Talent update does not mutate Skill mastery;
17. Potential update does not mutate Skill mastery;
18. Skill does not mutate PlayerStat.baseValue as a side effect;
19. Skill is not Technique;
20. Technique acquisition does not auto-create/level Skill unless a future progression rule explicitly commits such a result.

### Derived mastery / no-retrogression

21. injury modifies only effective mastery;
22. equipment modifier removal restores effective mastery without changing base;
23. temporary buff expiry leaves base unchanged;
24. repeated resolve cannot accumulate penalties;
25. cache/rebuild gives identical effective result from same inputs;
26. modifier for another Skill UID has no effect;
27. modifier from Player B/campaign B cannot affect Player A/campaign A.

### Requirements

28. unmet learn requirement blocks acquisition, not unrelated skills;
29. temporary requirement failure does not delete learned Skill;
30. missing requirement rule fails deterministically;
31. requirement cycle, if dependency graph supports Skill requirements, fails deterministically.

### Phase 6 domains

32. Skill binds to valid progression domain UID;
33. missing domain UID fails;
34. another World Pack's domain cannot be hijacked;
35. parent/child domains are not automatically double-applied;
36. multiple domain bindings are deterministic/versioned.

### Legacy

37. legacy-only Skill visible;
38. typed-only Skill visible;
39. mapped legacy+typed -> exactly one canonical Skill;
40. unmapped apparent duplicate -> fail-loud/unresolved, never silent merge;
41. unknown custom legacy Skill survives;
42. legacy bytes remain after mapping;
43. legacy mastery equality;
44. legacy XP equality;
45. migration idempotency;
46. migration processes active and non-active characters;
47. old campaign opens with no data loss;
48. `PRAGMA integrity_check` passes;
49. adopted FK policy passes `foreign_key_check` or explicit equivalent tests.

---

## 20. Implementation sequence recommendation

After the coordinator marks Phase 6 COMPLETE:

1. re-audit actual accepted WORK-020 runtime/API and CI result;
2. inventory physical legacy `skill_definitions` + `character_skills` schemas from representative old campaigns and bundled assets;
3. freeze `SkillDefinition` stable UID / World Pack ownership contract;
4. freeze `PlayerSkill` mastery/XP numeric semantics;
5. freeze Skill -> `ProgressionDomainDefinition` binding model;
6. decide explicit legacy UID reconciliation/alias strategy;
7. implement additive persistence + typed store/repository access;
8. migrate/read-through all players losslessly;
9. integrate generic Phase 5 effective-mastery target only if explicitly in Phase 7 scope;
10. keep ProgressionEngine and domain mutation commands out of Phase 7 unless separately authorized;
11. run migration/no-retrogression/isolation/scale tests;
12. independent validation before global COMPLETE.

---

## 21. Known blockers and debts before implementation

### Blocking dependency

Phase 6 is not yet formally COMPLETE at the time of this report. WORK-020 appeared during this audit, but its independent semantic/integrity/adversarial validation and coordinator acceptance are still upstream gates.

Therefore Phase 7 implementation must wait.

### Contract items to freeze after Phase 6 acceptance

1. exact accepted `ProgressionDomainDefinition` APIs and any changes required by validation;
2. exact physical legacy Skill schemas in bundled/old campaign DBs;
3. mastery numeric scale;
4. legacy XP semantics;
5. generic Phase 5 target extension for effective Skill mastery, because current `ModifierTargetKind` has no Skill target;
6. Skill legacy reconciliation shape.

None of these requires implementing ProgressionEngine during Phase 7.

---

## 22. Final architecture contract

The target dependency chain is:

```text
World Pack SkillDefinition
        |
        +---- stable Skill UID / requirements / domain bindings
        |
PlayerSkill(baseMastery, progress, version, provenance)
        |
        +---- authoritative persistent learned competence
        |
Phase 5 generic derived modifier foundation
        |
        +---- effective mastery only; never rewrites base
        |
Phase 6 ProgressionDomainDefinition + Talent/Potential profiles
        |
        +---- future progression inputs by stable domain UID
        |
future ProgressionEngine
        |
        +---- causal proposed progress; not part of Phase 7
```

This preserves all required distinctions:

```text
Skill != Talent
Skill != Potential
Skill != Technique
Skill != Stat
base mastery != effective mastery
learned state != temporary usability
progression input != progression result
```

No universe-specific Skill name or mechanic needs to exist in Core.

# Final status

**PHASE 7 ARCHITECTURE READY — IMPLEMENTATION BLOCKED BY PHASE 6**
