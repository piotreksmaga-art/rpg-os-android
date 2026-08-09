# WORK-20260810-029 — Phase 8 Technique Model Architecture

Status: READ-ONLY RUNTIME / FUTURE PHASE ARCHITECTURE AUDIT

Work ID: `WORK-20260810-029`
Worker: `CHAT-4`
Role: PHASE 8 TECHNIQUE MODEL READ-ONLY AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Coordinator-issued baseline: `b08e8861fa3d3095584f7cd1bf8cdf827b3cd373`
Accepted Phase 6 runtime: `52af00e441131cc8e7beb4a8036e43d250f35848`
Fresh master immediately before report write: `b08e8861fa3d3095584f7cd1bf8cdf827b3cd373`
Phase 7 implementation work item: `WORK-20260810-026`

This document is architecture/audit only. It does not implement Phase 8 runtime, Technique schema migration, DevelopmentProject, ProgressionEngine, PlayerCommand, PlayerChangeSet, PlayerDomainEngine, CharacterPanelSnapshot v2, or any universe-specific rule engine.

---

## 1. Executive conclusion

The repository already contains a substantial legacy/current Technique state and read surface. Therefore Phase 8 must integrate and reconcile existing data rather than create an unrelated second Technique truth.

Confirmed runtime behavior:

- `character_techniques` exists and is read as persistent player Technique state;
- `technique_definitions` exists and is joined by `CharacterPanelReader` using `technique_uid`;
- `canon_technique_index` exists in the World Pack read path and is used by the Technique browser;
- `ContextBuilder` sends player Technique rows to the backend for the authoritative active player UID;
- backend instructions currently treat `player_techniques` as authoritative learned abilities;
- legacy Skill and Technique read paths are distinct.

The target Phase 8 model should introduce or formalize a generic World-Pack-owned `TechniqueDefinition` plus campaign/character-scoped `PlayerTechnique`, preserving legacy values through explicit compatibility/reconciliation.

Canonical semantic separation:

```text
Technique
= a concrete executable method / action / ability known by the character

Technique != Skill
Technique != Talent
Technique != Potential
Technique != Stat
```

A Technique may require Skill, stats, resources, innate state, world facts, equipment, or other Techniques according to versioned World Pack rules. Requirement relationships do not collapse those concepts into Technique identity.

Creation of a new Technique must not be an arbitrary AI grant. The future legal creation path is DevelopmentProject / domain mutation, but this audit does not implement it.

Phase 8 implementation remains blocked until Phase 7 is completed and accepted, because Technique requirements must bind to the accepted typed Skill contract rather than to legacy `character_skills` SQL or presentation models.

---

## 2. Canonical source hierarchy applied

This audit follows the project source priority:

1. current repository/runtime and campaign data,
2. current coordinator instruction,
3. MASTER architecture,
4. roadmap and older audits.

Relevant MASTER invariants:

- stable UID is identity; names are labels;
- authoritative/derived/runtime/presentation data must remain separated;
- AI output is not committed reality;
- permanent player ability changes eventually belong behind the Player Domain mutation path;
- Skill is general competence, Technique is a concrete executable method;
- newly created Techniques ultimately require a DevelopmentProject-style legal path;
- temporary constraints should not destroy permanent achievements without explicit legal cause.

---

## 3. Existing Technique inventory

### 3.1 `character_techniques`

`ContextBuilder` confirms the current player Technique row has at least:

```text
entity_uid
technique_uid
mastery
xp
learned_chapter
last_used_chapter
usage_count
success_count
failure_count
is_equipped
notes
```

Runtime query:

```sql
SELECT entity_uid,technique_uid,mastery,xp,learned_chapter,last_used_chapter,
       usage_count,success_count,failure_count,is_equipped,notes
FROM character_techniques
WHERE entity_uid=?
ORDER BY is_equipped DESC,mastery DESC,xp DESC
LIMIT 60
```

Interpretation:

- `entity_uid` = current legacy character scope;
- `technique_uid` = existing identity-like key that must be preserved as migration evidence;
- `mastery` = current persisted Technique proficiency-like value in legacy runtime;
- `xp` = progress-like persistent field whose exact semantics are not proven by the audited Kotlin code;
- `learned_chapter` = useful historical metadata but not full provenance;
- usage/success/failure counters = persistent historical/statistical state, not Technique definition;
- `is_equipped` = current selection/loadout-like presentation/runtime state; it is not proof of Technique ownership;
- `notes` = opaque legacy metadata and cannot be promoted mechanically to rules.

The `LIMIT 60` is a context budget/presentation limit. A future authoritative Technique repository must never truncate a character at 60 Techniques.

### 3.2 `technique_definitions`

`CharacterPanelReader` proves a definition table exists and joins it to player Technique state:

```sql
SELECT t.name,
       ct.mastery,
       COALESCE(ct.chakra_cost_override,t.base_chakra_cost),
       t.category
FROM character_techniques ct
JOIN technique_definitions t ON t.technique_uid=ct.technique_uid
WHERE ct.entity_uid=?
ORDER BY t.category,t.name
```

Confirmed definition fields used by runtime are at least:

```text
technique_uid
name
category
base_chakra_cost
```

This read path also proves a per-player `chakra_cost_override` field exists in `character_techniques` or an equivalent joined source.

The generic Phase 8 model must not hardcode `chakra` as the universal resource type. The current `base_chakra_cost` and `chakra_cost_override` are legacy Naruto-specific storage/presentation facts and must be adapted through a generic cost contract.

### 3.3 `canon_technique_index`

`TechniqueMissionReader` reads World Pack/reference Technique metadata from:

```text
name
category
rank
element_key
wiki_url
verification_status
```

using `canon_technique_index`.

This table is currently a browser/reference surface, not a proven authoritative player Technique definition contract.

Important separation:

```text
canon/reference index
!= player learned Technique
!= necessarily canonical runtime TechniqueDefinition identity
```

Phase 8 must reconcile or explicitly link reference/canon records to `TechniqueDefinition`; it must not infer identity by matching `name`.

### 3.4 CharacterPanel

Current presentation model:

```text
TechniqueLine(name, mastery, chakraCost, category)
```

This is presentation only and contains no:

- campaign ID,
- character UID,
- stable World Pack ownership,
- definition version,
- provenance,
- base/effective proficiency separation,
- requirements,
- generic resource-cost identities,
- creation provenance,
- deprecation/supersession metadata.

CharacterPanel must not become a Technique source of truth.

### 3.5 ContextBuilder

`ContextBuilder` resolves player identity from `ActivePlayerStore`, then reads `character_techniques` scoped to that UID. This is compatible with the Phase 3 active-player contract.

However it directly queries the legacy table and exposes raw rows as `playerTechniques`. Phase 8 should eventually replace this direct SQL dependency with a typed repository/read-model surface after compatibility is proven.

### 3.6 Backend prompt

The backend currently says:

```text
Use player_skills and player_techniques as authoritative learned abilities.
```

This is directionally correct for ownership/learned state, but too coarse for the future model.

After Phase 8 the backend should distinguish at least:

- authoritative learned/base Technique state,
- derived/effective legality or proficiency,
- runtime availability/cooldowns/temporary blocks,
- definition/reference data,
- creation/project state.

The AI must never infer a permanent Technique grant merely because narration mentions experimentation, observation, copying, or attempted use.

---

## 4. Limit of current schema evidence

The audited Kotlin runtime proves the fields listed above because they are actively queried.

The bundled campaign/world-pack assets are binary SQLite/ZIP files. The GitHub connector exposed the binary object but did not provide a complete decoded column-level SQLite dump during this read-only session. Therefore this report does **not** invent unobserved columns such as hidden requirements, owner UIDs, definition versions, or rule IDs.

Mandatory Phase 8 implementation preflight:

- run `PRAGMA table_info(character_techniques)`;
- run `PRAGMA table_info(technique_definitions)`;
- run `PRAGMA table_info(canon_technique_index)`;
- inspect indexes, uniqueness constraints, FKs and row counts;
- enumerate orphan player Technique UIDs;
- inspect duplicate names/UIDs;
- inspect actual mastery/XP/cost ranges;
- identify whether `technique_definitions` is campaign-local, bundled, copied, or World Pack-owned in practice.

No Phase 8 migration should proceed from inferred schema alone.

---

## 5. Canonical Technique definition

A Technique is a concrete executable method/action/ability that a character can know and attempt to use.

Examples are World Pack content. Core models only generic mechanics.

A Technique definition may describe:

- stable identity,
- World Pack ownership,
- display/category metadata,
- requirements,
- generic costs,
- applicable Skill relationships,
- rule bindings,
- version/provenance,
- active/deprecated/superseded lifecycle.

A Technique definition does **not** itself mean the player knows the Technique.

---

## 6. Target `TechniqueDefinition`

Recommended conceptual model:

```text
TechniqueDefinition {
  techniqueUid              stable identity
  worldPackUid              owner
  key                       stable non-localized key
  displayName               presentation only
  category                  generic grouping
  definitionVersion         semantic version
  provenance                definition source
  active                    whether valid for new acquisition/use
  supersededByTechniqueUid? explicit replacement identity

  requirementsRuleUid?      opaque World Pack/rule-provider binding
  executionRuleUid?         opaque mechanics binding
  progressionRuleUid?       future progression binding
  masteryScaleUid?          optional explicit proficiency scale
}
```

Exact class/table names remain implementation decisions.

Required invariants:

- `techniqueUid` defines identity;
- same display name never proves equivalence;
- duplicate UID with incompatible metadata fails loudly;
- World Pack A cannot hijack a Technique UID owned by B;
- `(worldPackUid,key)` collisions require explicit version/supersession handling;
- deprecated definition does not silently delete player ownership/history;
- semantic reinterpretation under the same UID requires explicit migration/version policy;
- Core never branches on Naruto/Bleach Technique names/categories/elements.

---

## 7. Target `PlayerTechnique`

Recommended conceptual model:

```text
PlayerTechnique {
  campaignId
  characterUid
  techniqueUid
  baseMastery? / baseProficiency?
  progressValue? / xp?
  entryVersion
  provenance
  learnedAtChapter?
  learnedAtEventUid?

  usageCount?
  successCount?
  failureCount?
  lastUsedAt? / lastUsedChapter?
}
```

Logical identity:

```text
(campaignId, characterUid, techniqueUid)
```

Preferred learned-state contract:

- absence of `PlayerTechnique` = not learned/owned unless a separate discovery subsystem says otherwise;
- presence = learned/owned;
- temporary inability to execute does not delete the row;
- deprecation of definition does not erase historical ownership;
- a replacement/supersession migration must be explicit.

Legacy `is_equipped` should not automatically remain inside authoritative Technique ownership. It is closer to an active loadout/prepared selection and should be classified separately when Equipment/loadout architecture exists.

---

## 8. Technique mastery / proficiency

Current legacy runtime persists `character_techniques.mastery` and exposes it to CharacterPanel/backend. Therefore Phase 8 cannot simply discard it.

However its exact semantics are not fully proven.

Canonical decision for implementation:

```text
baseTechniqueMastery/baseProficiency
= persistent learned competence with this concrete Technique
```

if and only if the legacy data audit confirms `mastery` really has that meaning.

It must remain distinct from Skill mastery:

```text
Skill baseMastery
!= Technique baseMastery
```

A character may have high general Skill and low proficiency with a newly learned Technique, or low general Skill but unusually practiced proficiency in one narrow Technique if World Pack rules permit it.

No automatic copy is allowed:

```text
Technique mastery = Skill mastery
```

is forbidden as a generic rule.

If a World Pack chooses to derive Technique effectiveness entirely from Skill and no independent Technique mastery, that is a World Pack rule/definition decision. Legacy persisted Technique mastery still must be preserved losslessly until explicitly reconciled.

### Effective Technique proficiency

Temporary injury/equipment/environment effects may alter an effective Technique execution/proficiency projection. They must not overwrite persistent Technique mastery.

Phase 8 should reuse the accepted generic derived/modifier foundation where applicable rather than introduce `TechniqueModifierEngine`.

Whether `TECHNIQUE_EFFECTIVE` needs to become a Phase-5 target should be decided only after Phase 7 finalizes the pattern for `SKILL_EFFECTIVE`.

---

## 9. XP / progress semantics

Legacy `character_techniques.xp` exists, but current Kotlin code only sorts/transports it. It does not prove whether XP means:

- lifetime XP,
- XP toward next mastery threshold,
- practice points,
- usage-weighted progress,
- cache derived from mastery,
- another Naruto-specific convention.

Therefore:

- preserve legacy XP exactly;
- do not infer mastery from XP during migration;
- do not infer Skill XP from Technique XP;
- do not feed XP through Talent/Potential in Phase 8;
- do not implement ProgressionEngine here.

The eventual World Pack/progression rule contract must define conversion semantics.

---

## 10. Skill relationship

Phase 8 should depend on the accepted Phase 7 typed Skill contract.

A Technique may require one or more Skills.

Recommended generic relationship:

```text
TechniqueSkillRequirement {
  techniqueUid
  skillUid
  requirementKind
  minimumBaseMastery? / minimumEffectiveMastery?
  ruleUid?
  version
  provenance
}
```

Prefer a generic versioned rule binding when requirements are more expressive than a simple threshold.

Hard invariants:

- learning a Skill does not automatically grant Technique;
- learning Technique does not automatically rewrite Skill mastery;
- Technique mastery does not copy Skill mastery;
- temporary Skill penalty may temporarily prevent/penalize Technique use but does not unlearn the Technique;
- loss of a requirement after acquisition does not silently delete Technique ownership.

Acquisition requirement and execution requirement may be different. The model should not assume they are identical.

---

## 11. Talent / Potential relationship

Technique is not a Talent/Potential axis.

Future ProgressionEngine may use progression-domain relationships while training or creating a Technique, but Phase 8 must not directly compute progression from Phase 6 profiles.

Forbidden:

- high Talent auto-grants Technique;
- high Potential auto-grants Technique;
- Talent value copied into Technique mastery;
- Potential used as a direct Technique mastery cap without an explicit future progression rule;
- Technique ownership generates Talent/Potential changes automatically.

---

## 12. Stats relationship

Technique requirements/execution may depend on `StatDefinition`/`PlayerStat` through versioned rules.

Examples of legal generic relationships:

- minimum effective/base stat for acquisition/use;
- stat contributes to execution success/effect;
- stat contributes to derived cost/effect.

Forbidden identity collapse:

```text
Technique mastery = stat
Technique ownership = stat threshold
```

A sufficient stat may satisfy a prerequisite but cannot by itself manufacture a learned Technique.

---

## 13. Resource and cost model

Current UI/read path uses Naruto-specific:

```text
base_chakra_cost
chakra_cost_override
```

This cannot be the Core contract.

Target generic model should express costs by stable Resource UID and rule identity, for example:

```text
TechniqueCostDefinition {
  techniqueUid
  resourceUid
  costKind
  baseAmount?
  costRuleUid?
  version
  provenance
}
```

Possible `costKind` examples at generic level:

- FLAT,
- DERIVED,
- PERCENT_OF_MAX,
- PERCENT_OF_CURRENT,
- CONTINUOUS/RATE,

only if actually needed by runtime; do not overbuild Phase 8.

Rules:

- cost resource identity is stable UID, not textual `chakra`;
- a World Pack may define chakra, reiatsu, stamina, mana, charges, ammo or another ResourceDefinition;
- player-specific permanent or temporary cost adjustments should be derived/versioned effects, not silent mutation of definition;
- legacy `chakra_cost_override` must be preserved until explicitly mapped to a generic override/effect contract.

Resource availability can gate execution without affecting learned state.

---

## 14. Requirements model

Technique requirements are definition/rule data, not player mastery itself.

Potential requirement inputs:

- Skill UID/base/effective mastery;
- Stat UID/base/effective value;
- Resource definitions/availability;
- another Technique learned state;
- Innate/Racial/Bloodline/Evolution state;
- equipment/item ownership;
- structured world/campaign fact;
- rank/organization state where World Pack rules define it.

Core should prefer opaque versioned requirement rule UIDs plus typed dependencies over universe-specific fields.

Two distinct checks should be considered:

1. **Acquisition requirements** — may the character learn/create the Technique?
2. **Execution requirements** — may the character use it now?

Temporary failure of execution requirements must not erase acquisition history.

---

## 15. Learned state and no-retrogression

Learned Technique ownership is durable campaign state.

Release blockers for Phase 8 include:

- injury deletes `PlayerTechnique`;
- resource depletion deletes Technique;
- Skill penalty deletes Technique;
- definition deprecation silently deletes Technique;
- temporary transformation expiry deletes permanently learned Technique unless the Technique was explicitly transformation-only state;
- UI/loadout deselection marks Technique unlearned;
- backend narration removes mastery/ownership without legal domain mutation.

A true permanent loss mechanic, if a World Pack supports one, must be an explicit committed mutation with provenance and invariant validation.

---

## 16. `is_equipped` boundary

Legacy Technique state includes `is_equipped`.

This should be treated cautiously because MASTER distinguishes Inventory/Equipment/loadout concepts from learned abilities.

Recommended Phase 8 treatment:

- preserve legacy byte/value;
- do not make `is_equipped` part of Technique identity;
- do not use it to determine learned/unlearned;
- if it means prepared/active Technique slot, move/adapt it later to a typed loadout/selection state;
- if it has Naruto-specific semantics, World Pack adapter must define them.

Phase 8 should not implement the general Equipment domain while merely modeling Technique.

---

## 17. Usage history and counters

Legacy rows contain:

```text
last_used_chapter
usage_count
success_count
failure_count
```

These are useful historical/player metrics, but their authority class should be explicit.

Recommended:

- keep counters losslessly during migration;
- treat them as authoritative historical summaries only if no event ledger can reconstruct them;
- once event/usage ledger becomes authoritative, summaries may become derived/cache;
- never use counters alone to grant mastery, Skill, Talent or Potential;
- do not create missing history events retroactively from counts.

`learned_chapter` is similarly migration/historical metadata, not sufficient proof of how/why Technique was learned.

---

## 18. Definition ownership and World Pack boundary

`TechniqueDefinition` must be owned by a World Pack.

World Pack owns:

- definition identity/content;
- category/rank/presentation metadata;
- acquisition/execution rules;
- Resource/Skill/Stat/Innate requirement bindings;
- canon/reference relationships;
- version/deprecation/supersession semantics.

Core owns:

- stable identity enforcement;
- player Technique persistence;
- campaign/player isolation;
- definition ownership validation;
- version/provenance contracts;
- generic requirement/cost binding framework;
- reconciliation/migration invariants;
- derived/modifier infrastructure reused from earlier phases.

Core must not contain hardcoded names such as ninjutsu, genjutsu, kido, zanjutsu, sonido, chakra, reiatsu, Naruto or Bleach.

---

## 19. `canon_technique_index` relationship

The current browser reads `canon_technique_index` by human-facing fields and does not return a stable Technique UID in the audited query.

Therefore Phase 8 must not globally equate:

```text
canon_technique_index.name
== technique_definitions.name
```

Recommended options:

1. explicit stable canon/reference UID -> TechniqueDefinition mapping, or
2. TechniqueDefinition carries a versioned reference/canon identity owned by the World Pack.

Ambiguous matches remain unresolved. Same textual Technique name can exist in different World Packs or represent variants.

`wiki_url` and `verification_status` are reference/provenance metadata, not player-state authority.

---

## 20. Legacy compatibility and reconciliation

Phase 8 must preserve existing campaigns.

Minimum compatibility contract:

```text
old campaign with character_techniques
-> Phase 8 current-schema migration/read
-> same logical learned Techniques/mastery/xp/history remain visible
```

Forbidden outcomes:

- legacy Technique exists but typed read returns empty;
- legacy and typed same logical Technique become two authoritative ownership/mastery values;
- identity is guessed globally from matching display name;
- unknown/orphan legacy UID is silently discarded;
- unknown `chakra_cost_override` is normalized away;
- old counters/history are discarded because the target model did not include them.

Recommended compatibility pattern, following Phase 4/7 lessons:

- explicit alias/mapping when stable identity equivalence is proven;
- lossless unresolved legacy evidence for unknown/ambiguous rows;
- deterministic fail-loud if legacy + typed representations conflict without a mapping;
- exactly one canonical logical `PlayerTechnique` after explicit reconciliation;
- preserve original legacy rows/bytes until migration has been independently validated.

---

## 21. Orphan/missing-definition policy

A `character_techniques.technique_uid` may potentially lack a matching `technique_definitions` row in legacy campaigns.

Phase 8 must explicitly test this.

Required behavior:

- do not delete orphan player Technique row;
- do not synthesize a definition from the UID string/name guess;
- preserve as unresolved compatibility evidence;
- typed API may expose a diagnostic unresolved Technique state if needed;
- explicit World Pack mapping/repair is required before the row becomes a fully typed definition-backed Technique.

Data preservation takes priority over a cosmetically complete panel.

---

## 22. Deprecation / supersession

Definitions evolve across World Pack versions.

Recommended lifecycle:

```text
ACTIVE
DEPRECATED
SUPERSEDED
```

or equivalent metadata.

Rules:

- deprecated means no longer preferred/newly acquirable according to rules; it does not erase existing ownership;
- superseded definition points explicitly to replacement UID/version;
- migration of player mastery/history must be explicit and lossless;
- one World Pack version cannot silently redefine the same UID to a different semantic Technique;
- deleted definitions should normally be represented as deprecated/tombstoned identity, not disappear while player rows reference them.

---

## 23. Creation boundary — DevelopmentProject

MASTER explicitly requires new Technique creation/modification to pass through DevelopmentProject rather than arbitrary AI grant.

Future conceptual path:

```text
idea/proposal
-> DevelopmentProject
-> requirements
-> prototype
-> training/experiments
-> failures/improvements
-> milestones
-> stabilization
-> project completion
-> stable TechniqueDefinition UID
-> legal player acquisition
```

Phase 8 should prepare interfaces/identity for this future path but must **not** implement DevelopmentProject.

Forbidden Phase 8 shortcut:

```text
AI narration says "you invent X"
-> INSERT PlayerTechnique/TechniqueDefinition
```

The backend may narrate experimentation but cannot make the Technique authoritative until the future validated domain/project path commits it.

---

## 24. Existing mutation-path risk

Current backend returns generic `StatePatch` operations over tables. `SourceOfTruthRegistry` blocks read-only/reference tables and explicitly allows only known writable/active tables.

This is not yet the final MASTER PlayerDomain path for Technique acquisition/creation.

Phase 8 should avoid legitimizing direct arbitrary AI writes to Technique authority. If legacy `character_techniques` is currently writable through registry metadata, the Phase 8 typed store should become the authoritative mutation boundary and later be moved behind PlayerCommand/PlayerDomainEngine when those roadmap phases arrive.

At minimum, Phase 8 should ensure no Technique definition/reference table can be created or mutated directly by GM StatePatch based solely on narration.

---

## 25. Phase 7 dependency

Phase 8 must consume the final accepted Phase 7 contracts for:

- `SkillDefinition` stable identity;
- `PlayerSkill.baseMastery` vs effective mastery;
- `SKILL_EFFECTIVE` or equivalent Phase-5 extension;
- Skill legacy reconciliation policy;
- Skill World Pack ownership and provenance.

Do not bind Technique requirements directly to:

- `SkillLine.name`;
- raw `character_skills.mastery` SQL;
- display category;
- guessed Skill name.

Technique requirement identity should use stable Skill UID.

---

## 26. Phase 5 integration

Phase 8 should follow the same generic derived-value foundation established by Phase 5 and extended by Phase 7.

Possible derived Technique targets, only if needed:

- effective Technique proficiency;
- effective execution cost;
- effective accuracy/power/difficulty parameters.

Do not create a parallel Technique resolver merely for convenience.

Required inherited guarantees:

- deterministic modifier ordering;
- finite numeric guards;
- lifecycle/source validity;
- campaign/player isolation;
- cycle detection for derived rule graphs;
- no mutation of authoritative base mastery/ownership.

The Phase-7 solution for Skill should be the implementation precedent.

---

## 27. Numeric policy

Until actual legacy ranges are inspected, Core must not assume Technique mastery is universally `0..100`.

Required generic guarantees:

- NaN rejected;
- +/-Infinity rejected;
- explicit declared mastery scale/range if bounded;
- no silent clamp of legacy values without a mapping contract;
- deterministic zero/canonicalization policy if fingerprints depend on it;
- XP/counters reject invalid storage values according to their declared semantics.

Cost values similarly require finite/non-invalid validation and ResourceDefinition-aware semantics.

---

## 28. Suggested Phase 8 persistence shape

Conceptual additive tables/models:

```text
technique_definitions_v2
  technique_uid PK
  world_pack_uid
  technique_key
  display_name
  category
  definition_version
  provenance
  active/deprecated/superseded metadata
  rule bindings...

player_techniques_v2
  campaign_id
  character_uid
  technique_uid
  base_mastery/proficiency
  progress/xp
  entry_version
  provenance
  learned metadata
  counters/history summary where still authoritative
  PK(campaign_id,character_uid,technique_uid)

technique_skill_requirements
  technique_uid
  skill_uid / rule binding
  version
  provenance

technique_cost_definitions
  technique_uid
  resource_uid
  cost metadata/rule
  version
  provenance

legacy_technique_aliases/evidence
  campaign + legacy identity -> canonical identity
  mapping version/provenance
```

Names are illustrative. Implementation should minimize schema and reuse accepted generic infrastructure.

---

## 29. Required Phase 8 implementation tests

Minimum future test matrix:

1. typed TechniqueDefinition registration;
2. World Pack ownership;
3. duplicate UID incompatible metadata fail-loud;
4. same display label different stable UID remains separate;
5. player learns/acquires Technique;
6. campaign isolation;
7. player isolation;
8. learned state persists across reopen;
9. base Technique mastery persists;
10. XP preserved according to explicit contract;
11. learned chapter/history/counters preserved;
12. legacy Technique visible through typed API;
13. unknown/orphan legacy UID preserved unresolved;
14. legacy + typed same logical Technique without mapping fails loudly;
15. explicit mapping yields exactly one canonical logical Technique;
16. legacy bytes preserved;
17. migration idempotent;
18. production schema entrypoint reaches Phase 8;
19. restore/campaign switch reaches Phase 8 schema;
20. Skill requirement uses stable Skill UID;
21. Skill mastery does not auto-copy into Technique mastery;
22. Technique acquisition does not mutate Skill mastery;
23. Talent does not auto-grant Technique;
24. Potential does not auto-grant Technique;
25. Stat threshold alone does not auto-grant Technique;
26. Resource depletion does not unlearn Technique;
27. temporary injury/equipment/buff effects do not mutate base Technique mastery;
28. definition deprecation does not delete learned Technique;
29. supersession requires explicit mapping;
30. generic Resource UID cost works independently of Naruto-specific names;
31. invalid mastery rejected;
32. NaN/Infinity rejected;
33. invalid cost rejected;
34. no Skill/Technique identity collision by label;
35. no AI/direct StatePatch definition grant path;
36. >=1000 Techniques/player values no authoritative truncation;
37. deterministic derived ordering if Technique effective targets exist;
38. cycle/missing-rule safety if Technique rules enter derived graph;
39. `PRAGMA integrity_check`;
40. `PRAGMA foreign_key_check`;
41. full JVM tests;
42. signed APK build;
43. CI.

---

## 30. Recommended implementation order after Phase 7 COMPLETE

1. freeze accepted Phase 7 Skill contract;
2. dump actual legacy Technique schemas/data ranges;
3. classify every existing Technique field as authoritative/derived/runtime/presentation/reference;
4. define stable World-Pack-owned TechniqueDefinition;
5. define PlayerTechnique learned/base state;
6. define explicit legacy reconciliation/read-through;
7. model Skill requirements using accepted stable Skill UID;
8. model generic Resource UID costs without hardcoded chakra;
9. integrate derived/effective effects through the existing Phase-5/7 foundation only if required;
10. wire production current-schema entrypoint;
11. migrate/read legacy campaigns losslessly;
12. replace direct ContextBuilder/CharacterPanel reads only after typed parity is proven;
13. test restore/campaign-switch/bootstrap paths;
14. run JVM/build/CI;
15. independent migration/adversarial validation;
16. only coordinator marks Phase 8 COMPLETE.

---

## 31. Known debt / open contract decisions

These must be resolved from runtime evidence during Phase 8 implementation, not guessed now:

- exact schema and constraints of `character_techniques`;
- exact schema and storage location/ownership of `technique_definitions`;
- whether `canon_technique_index` has hidden stable identity/link fields not used by current Kotlin;
- exact legacy mastery scale;
- exact XP semantics;
- whether Technique mastery is independent canonical proficiency for every World Pack or optional by definition;
- meaning of `is_equipped`;
- semantics of `chakra_cost_override` and whether it is permanent, derived, or presentation override;
- whether usage/success/failure counters are authoritative or reconstructable;
- exact Phase-7 generic derived-target pattern to reuse for Technique;
- acquisition vs execution requirement contracts;
- how deprecated/superseded World Pack definitions are retained across pack upgrades.

None of these justify hardcoding Naruto concepts into Core.

---

## 32. Release blockers for future Phase 8

Phase 8 must not be accepted if any of the following is true:

- old learned Techniques disappear;
- legacy and typed Technique state both remain authoritative for one logical Technique;
- Technique identity is matched globally by name;
- Skill mastery is copied into Technique mastery;
- Technique ownership is inferred from Skill/stat/Talent/Potential;
- temporary conditions mutate permanent Technique mastery/ownership;
- `chakra` is hardcoded as Core universal resource;
- orphan legacy Technique data is dropped;
- World Pack ownership can be hijacked;
- AI narration can directly create a canonical new Technique without a legal domain/project path;
- current-schema bootstrap does not run the Phase 8 migration;
- ContextBuilder authoritative reads silently truncate stored Technique truth;
- definition deprecation deletes historical player ownership;
- tests/build/CI fail.

---

## 33. Final architecture verdict

The current system has enough Technique persistence to preserve continuity, but not enough typed semantics to call Phase 8 implemented.

The safe target is:

```text
World Pack
  -> TechniqueDefinition (stable UID, version, provenance, rules)

Campaign + Character
  -> PlayerTechnique (learned persistent state, optional persistent Technique proficiency/progress)

Skill/Stat/Resource/Innate
  -> versioned acquisition/execution dependencies

Phase 5/7 derived foundation
  -> effective Technique parameters only

DevelopmentProject (future)
  -> legal creation/modification path for new Technique definitions

AI/CharacterPanel/ContextBuilder
  -> consumers/presentation, never Technique authority
```

This preserves campaign continuity, Skill/Technique separation, future creation causality, World Pack independence and no-retrogression.

`PHASE 8 ARCHITECTURE READY — IMPLEMENTATION BLOCKED BY PHASE 7`
