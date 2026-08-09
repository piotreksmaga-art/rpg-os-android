# WORK-20260810-034 — Next Phase Architecture

Status: READ-ONLY RUNTIME / FUTURE PHASE ARCHITECTURE AUDIT

Work ID: `WORK-20260810-034`
Worker: `CHAT-4`
Role: NEXT-PHASE READ-ONLY ARCHITECTURE AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Coordinator-issued baseline: `0653c6c6fe03da3db98623112f7a0af4c3f88464`
Fresh master before report write: `0653c6c6fe03da3db98623112f7a0af4c3f88464`
Accepted Phase 7 runtime: `8075487d24bf0b3da1bcbd8f9fb2483e9154ec6c`
Phase 8 implementation work item: `WORK-20260810-031`

This document is architecture/audit only. It does not implement Phase 9 runtime, schema, migration, PlayerCommand, PlayerChangeSet, PlayerDomainEngine, WorldRuleProvider, ProgressionEngine, DevelopmentProject, CharacterPanelSnapshot v2, or any universe-specific mechanics.

---

## 1. Exact next phase from Roadmap

The canonical roadmap order in Phase A is:

```text
7. Skill model
8. Technique model
9. Innate/Racial/Bloodline/Evolution runtime model
10. Inventory model
```

Therefore the exact next phase after Technique Model is:

# PHASE 9 — Innate/Racial/Bloodline/Evolution runtime model

This report designs only the Phase 9 input architecture and remains implementation-blocked until Phase 8 is formally accepted.

---

## 2. Executive conclusion

The current repository has no typed Phase-9 Core model comparable to `SkillDefinition`, `PlayerSkill`, `ProgressionDomainDefinition`, or the Phase-4 stat/resource contracts.

However relevant legacy/reference state already exists in several forms:

1. `character_status_snapshot` can contain arbitrary persistent legacy fields. `PlayerStateStore` classifies fields that are not explicitly runtime/derived as `PERSISTENT` and exposes them under `legacy_status.<field>`.
2. World/canon character records expose at least `clan_uid` through `canon_characters_v2`.
3. World canon constraints can encode structured subject constraints through `canon_constraints_v2`.
4. Skills and Techniques are already separate domains and must not be used as substitutes for innate/bloodline/racial state.
5. Phase 6 Talent/Potential is already a separate persistent profile domain and must not be used as a substitute for inherited traits or evolution stage.
6. Phase 5 modifiers provide the generic derived-effect foundation and should be reused for temporary form/effect projections where appropriate rather than creating an innate-specific modifier engine.

The key architectural decision is to avoid collapsing four different semantic classes into one string such as `race` or one numeric `evolutionLevel`:

```text
INNATE / RACIAL / BLOODLINE FEATURE
= durable character-owned trait/capability/origin property

EVOLUTION PATH
= World-Pack-defined directed progression/state structure

EVOLUTION STATE
= character's authoritative current unlocked/attained stage(s)

TRANSFORMATION / FORM
= optional state derived from or enabled by an innate/evolution definition;
  permanent unlock and currently-active form are different authorities
```

A clan, family, organization, species label, narrative adjective, Skill name, Technique name, Talent value, Potential value, or current stat must never automatically become a canonical Phase-9 feature without explicit World Pack mapping.

---

## 3. Canonical semantics

### 3.1 Innate feature

An innate feature is a durable character property that is not learned as a Skill or Technique.

Examples belong to World Packs. Core does not contain universe-specific names.

A feature may represent concepts such as:

- species/racial origin,
- bloodline/lineage trait,
- congenital capability,
- inherited organ/property,
- non-learned affinity encoded as a trait rather than Talent,
- permanently acquired physiological/spiritual trait if a World Pack classifies it as innate-domain state.

The exact feature category is metadata/definition data owned by the World Pack.

### 3.2 Race/species identity vs racial capability

Core must distinguish identity from capability.

```text
species/race identity
!= automatically every racial feature
```

A World Pack may define explicit grants from an identity definition to feature definitions, but Core must not infer them from labels.

This distinction is necessary because:

- two characters of the same species may have different innate variants;
- a hybrid character may have multiple origins;
- a character can permanently acquire a trait normally associated with another origin through a legal story mechanic;
- one identity can grant several distinct capabilities with independent activation/unlock rules.

### 3.3 Clan/family identity vs bloodline

`clan_uid` exists in current canon character data, but clan membership is not sufficient proof of a bloodline capability.

Hard invariant:

```text
clanUid == X
DOES NOT IMPLY
character owns every bloodline feature associated with X
```

A World Pack may explicitly map clan/lineage identity to feature grants when canon guarantees the relationship, but the mapping must be stable, versioned and provenance-bearing.

### 3.4 Evolution

Evolution is not a Stat, Skill, Talent or Potential value.

Evolution should be modeled as a World-Pack-defined state graph/track with stable stage identities.

A character's evolution state answers:

- which path/track applies,
- which stages are attained/unlocked,
- what the current canonical stage is when a path has a singular current state,
- whether branches are mutually exclusive or coexist,
- provenance/version of the transition.

Core must not assume every universe uses a simple linear level sequence.

Legal structures include:

- linear path,
- branching path,
- multiple independent evolution tracks,
- reversible active forms over irreversible unlocks,
- mutually exclusive stages,
- additive mutations/traits that do not replace the prior identity.

### 3.5 Transformation/form

A transformation/form should not automatically be the same thing as evolution stage.

Recommended distinction:

```text
UNLOCKED FORM
= persistent authority

ACTIVE FORM
= runtime/current state unless the World Pack explicitly defines it as permanent

FORM EFFECTS
= derived modifiers/rule outputs where possible
```

Temporary transformation effects should use the accepted Phase-5 derived/modifier foundation when they alter stats/resources/Skill/Technique effective values. They must not rewrite base progression merely because a form is active.

---

## 4. Legacy inventory observed in repository

### 4.1 `character_status_snapshot`

`PlayerStateStore.splitLegacyStatus()` reads every column of `character_status_snapshot` for the active player when identity is unambiguous.

Current classification policy:

- explicit current HP/energy/stamina/fatigue/cooldown/pain/etc. -> RUNTIME,
- `effective_*`, `derived_*`, `max_*`, regeneration/net worth/combat rating -> DERIVED,
- everything else -> PERSISTENT.

Therefore legacy fields such as hypothetical:

```text
race
species
clan
bloodline
kekkei_genkai
innate_trait
evolution_stage
form
lineage
heritage
```

would currently survive as opaque `legacy_status.*` PERSISTENT values unless their name happens to hit a runtime/derived heuristic.

This is useful for lossless preservation, but it is not a typed Phase-9 semantic contract.

### 4.2 `canon_characters_v2.clan_uid`

`ContextBuilder` reads canonical NPC data from `canon_characters_v2` including:

```text
character_uid
name
sex
birth_era
clan_uid
village_uid
rank_title
affiliation_summary
personality_summary
combat_summary
```

`clan_uid` is therefore a confirmed stable-ish canon reference field used by runtime retrieval.

It must remain reference/identity evidence, not automatic player bloodline ownership.

### 4.3 `canon_constraints_v2`

Context retrieval reads:

```text
constraint_uid
subject_type
subject_uid
constraint_key
constraint_value
canon_scope
notes
```

This can carry canon facts or restrictions relevant to Phase 9, but constraint text/value is not automatically a canonical character feature row.

Future World Pack import/mapping may use these records as authoritative source evidence only when semantics are explicit.

### 4.4 Skills

Phase 7 owns learned competence.

A Skill whose display name sounds hereditary must still remain Skill unless the World Pack explicitly models a separate innate feature and relationship.

Forbidden migration shortcut:

```text
Skill name contains bloodline/racial word
=> create InnateFeature
```

### 4.5 Techniques

Technique state represents concrete learned/owned executable methods.

A Technique associated with an innate power does not itself prove the underlying trait unless an explicit definition relationship says so.

Forbidden shortcut:

```text
character knows Technique X
=> infer bloodline/race/evolution state Y
```

### 4.6 Talent/Potential

Phase 6 values are progression profiles.

They are not inherited trait ownership and must not be migrated to Phase 9.

Potential may later influence eligibility for evolution through explicit rule inputs, but:

```text
Potential value
!= evolution stage
```

### 4.7 Stats/resources

Phase 4 stats/resources can be consequences or requirements of Phase-9 state, but they are not identity substitutes.

A high stat does not prove an evolution, and a resource type does not prove a race/bloodline.

### 4.8 World Pack binary data

The repository contains bundled SQLite/ZIP World Pack/campaign assets. The GitHub connector exposes them as binary content but did not provide a full decoded SQLite schema in this audit.

Mandatory Phase-9 implementation preflight must therefore include real `PRAGMA table_info`, index/FK inventory and row inspection for any tables/columns matching:

```text
race / species / lineage / clan / bloodline / kekkei / innate
trait / mutation / evolution / stage / form / transformation
```

No implementation should infer missing schema from names alone.

---

## 5. Authoritative ownership model

Phase 9 should use World-Pack-owned definitions and campaign-character-owned state.

Recommended authority split:

### World Pack authority

- innate feature definitions,
- origin/species/race definitions if the World Pack exposes them as gameplay identities,
- evolution path definitions,
- evolution stage definitions,
- transformation/form definitions,
- relationship/rule bindings,
- compatibility/migration mappings.

### Campaign character authority

- character-owned innate feature grants,
- character origin/species identity assignments if gameplay-authoritative,
- attained/unlocked evolution stages,
- current canonical evolution stage when applicable,
- permanently unlocked forms,
- persistent variant/feature parameters only when explicitly defined,
- provenance/version of grants/transitions.

### Runtime authority

- currently active reversible form,
- temporary activation state,
- cooldown/duration if owned by this domain,
- current suppression/availability state.

### Derived

- effective stat/resource/Skill/Technique consequences,
- computed eligibility,
- current bonuses/penalties generated by active forms,
- presentation labels.

Derived values must never become fallback authority for missing feature/evolution state.

---

## 6. Proposed Core model

Exact Kotlin/table names remain implementation decisions. The semantic shape below is the recommended contract.

### 6.1 `InnateFeatureDefinition`

```text
featureUid                stable identity
worldPackUid              owner
key                       stable non-localized key
displayName               presentation only
featureKind               generic semantic category
category                  optional grouping
definitionVersion         semantic version
provenance                definition source
active                    valid for new grants/use
supersededByFeatureUid?   explicit replacement
requirementsRuleUid?      optional generic rule binding
effectsRuleUid?           optional generic derived effects binding
metadata?                 opaque extensibility
```

`featureKind` should remain generic. A practical set may include categories equivalent to:

```text
ORIGIN
RACIAL
LINEAGE
BLOODLINE
INNATE
MUTATION
OTHER
```

The enum/category must not contain universe-specific names.

If implementers prefer an open stable kind UID rather than enum, that is also valid and more extensible.

### 6.2 `PlayerInnateFeature`

```text
campaignId
characterUid
featureUid
state / ownershipStatus
entryVersion
provenance
acquiredAtChapter?
acquiredAtEventUid?
variantUid?
opaqueParameters?
```

Logical identity:

```text
(campaignId, characterUid, featureUid [, variantUid if definition explicitly permits variants])
```

Presence should normally mean the feature is durably owned/attained.

Temporary suppression does not delete the row.

### 6.3 `OriginDefinition` / identity layer

If Phase-9 implementation discovers real legacy species/race identity data distinct from capabilities, prefer a separate identity concept rather than overloading feature rows.

Conceptual shape:

```text
OriginDefinition {
  originUid
  worldPackUid
  key
  displayName
  originKind
  definitionVersion
  provenance
}

PlayerOrigin {
  campaignId
  characterUid
  originUid
  relationshipKind
  provenance
  version
}
```

This allows hybrids/multiple origins without pretending every origin is a Skill-like ability.

If the implementation finds no real legacy need for a separate origin table, it may defer this object and represent only concrete gameplay features; it must still not infer feature ownership from labels.

### 6.4 `EvolutionPathDefinition`

```text
pathUid
worldPackUid
key
displayName
definitionVersion
provenance
active
requirementsRuleUid?
metadata?
```

### 6.5 `EvolutionStageDefinition`

```text
stageUid
pathUid
worldPackUid
key
displayName
definitionVersion
provenance
active
requirementsRuleUid?
effectsRuleUid?
```

Do not require a global numeric stage level as identity.

### 6.6 `EvolutionTransitionDefinition`

Evolution should support graph topology explicitly:

```text
transitionUid
pathUid
fromStageUid?             null may mean path entry
intoStageUid
transitionKind
requirementsRuleUid?
reversible
exclusiveGroupUid?
transitionVersion
provenance
```

This avoids hardcoding linear `stage + 1` semantics.

### 6.7 `PlayerEvolutionState`

Recommended persistent state:

```text
campaignId
characterUid
pathUid
currentStageUid?          when path has singular current state
stateVersion
provenance
attainedStageUids/history references?
```

A separate attained-stage/history structure is preferable when historical stages matter or branches can coexist.

Do not delete prior evolution history merely because current stage changes.

### 6.8 `TransformationDefinition`

If the World Pack needs active forms:

```text
formUid
worldPackUid
key
displayName
sourceFeatureUid? / sourceStageUid?
requirementsRuleUid?
effectsRuleUid?
definitionVersion
provenance
```

### 6.9 Persistent unlock vs active state

```text
PlayerFormUnlock
= persistent character ownership/unlock

PlayerActiveForm
= runtime/current activation state
```

This split prevents temporary deactivation from erasing permanent progression.

---

## 7. World Pack ownership and stable identity

All canonical definitions use stable UIDs.

Hard invariants:

1. World Pack A cannot register incompatible metadata under a UID already owned by B.
2. Same display name/key in different World Packs can coexist under different stable UIDs.
3. Same text legacy label is never global identity.
4. Definition ownership cannot silently change after player state references it.
5. Deprecated definitions do not delete existing character history/state.
6. Supersession is explicit and versioned.
7. A parent/lineage relationship does not imply grants unless an explicit rule/mapping says so.

---

## 8. Relationship with Phase 4 Stats/Resources

Phase-9 features/evolution stages may affect stats/resources, but only through explicit generic rule/modifier bindings.

Legal direction:

```text
PlayerInnateFeature / EvolutionStage / ActiveForm
-> rule/provider
-> Phase-5 derived modifiers/results
-> effective stat/resource projection
```

Forbidden direction:

```text
high stat/resource
-> automatically create feature/evolution stage
```

Permanent feature acquisition may legally trigger permanent player-domain changes later, but Phase 9 should not create an unvalidated cross-domain writer. Until PlayerDomainEngine exists, Phase-9 implementation must keep its own authority boundaries explicit.

Resource costs for activating forms must bind to stable `ResourceDefinition.resourceUid`, never universe-specific resource names in Core.

---

## 9. Relationship with Phase 5 Derived/Modifier model

Phase 5 remains the generic derived-effect foundation.

Phase 9 must not create:

- `InnateModifierEngine`,
- `BloodlineResolver`,
- `EvolutionStatEngine`,
- `TransformationModifierEngine`.

Temporary effects from an active form should produce normal generic modifiers/rule outputs targeting existing effective domains.

Examples:

```text
persistent feature remains owned
active form = true
stat effective modifier applies
form deactivates
modifier disappears
persistent feature remains owned
```

A temporary suppression effect may alter whether a feature is currently usable/effective, but does not silently erase its persistent ownership.

---

## 10. Relationship with Phase 6 Talent/Potential

Talent/Potential may later be inputs to eligibility/progression rules, but they are independent state.

Hard invariants:

```text
Talent change != feature grant
Talent change != evolution transition
Potential change != feature grant
Potential change != evolution transition
```

A future World Pack rule may require a Potential threshold or use Talent/Potential in progression toward an evolution, but only a legal transition event changes authoritative evolution state.

Evolution also must not rewrite Talent/Potential merely because a stage changes unless a separate explicit permanent domain change is part of the validated transition.

---

## 11. Relationship with Phase 7 Skill

Skill is learned competence. Innate/racial/bloodline state is not Skill.

Legal relationships:

- feature can be prerequisite for Skill,
- feature can unlock eligibility to learn Skill,
- Skill can be prerequisite for evolution transition,
- evolution stage can modify effective Skill through Phase 5,
- future ProgressionEngine can use feature/evolution context.

Forbidden:

```text
feature acquired -> copy arbitrary Skill mastery
Skill mastery high -> infer bloodline feature
Skill name -> infer race/evolution
```

Permanent Skill changes remain Skill-domain authority.

---

## 12. Relationship with Phase 8 Technique

Technique is a concrete learned/owned executable method.

Legal relationships:

- feature/stage can be prerequisite for Technique learning/use,
- stage/form may enable Technique execution,
- Technique may require an active form,
- Technique use may later contribute to a legal evolution/progression event through ProgressionEngine.

Forbidden:

```text
knows Technique -> infer feature/evolution ownership
feature acquired -> automatically grant all related Techniques
Technique mastery -> copy into evolution level
```

Technique and innate/evolution state require explicit stable UID relationships.

---

## 13. Legacy migration strategy

Phase 9 legacy data is semantically more ambiguous than Phase 4 stats or Phase 7 skills.

Use the same conservative two-layer principle established for Talent/Potential.

### Layer A — opaque legacy evidence

Preserve evidence such as:

```text
evidenceUid
campaignId
characterUid
source table/field
legacy key
raw value
source version
provenance
```

Opaque evidence is not a canonical feature/evolution row.

### Layer B — explicit typed mapping

Canonical materialization requires mapping that identifies:

- axis/type: origin / innate feature / evolution path-stage / form unlock,
- canonical stable UID,
- World Pack owner,
- supported source version,
- deterministic value/state conversion,
- mapping version,
- provenance.

### Default classification guidance

Bare labels should be treated conservatively:

| Legacy label/example | Default classification |
|---|---|
| `race`, `species` | EXPLICIT WORLD PACK MAPPING REQUIRED |
| `clan`, `clan_uid` | identity/reference evidence; DO NOT AUTO-GRANT BLOODLINE |
| `bloodline`, `lineage`, `heritage` | EXPLICIT WORLD PACK MAPPING REQUIRED |
| `innate`, `innate_trait` | EXPLICIT WORLD PACK MAPPING REQUIRED |
| `evolution`, `evolution_stage`, `stage` | EXPLICIT WORLD PACK MAPPING REQUIRED |
| `form`, `transformation` | AMBIGUOUS: may be runtime active form or persistent unlock; DO NOT AUTO-MIGRATE |
| narrative adjective such as `gifted`, `mutated`, `awakened` | AMBIGUOUS — PRESERVE OPAQUE |
| Skill/Technique name that resembles bloodline/race | DO NOT AUTO-MIGRATE |
| Talent/Potential domain label | DO NOT AUTO-MIGRATE |

SAFE AUTO-MAP is permitted only when a versioned World Pack mapping proves exact semantics and stable identity.

---

## 14. Mixed legacy + typed reconciliation

Do not repeat the Phase-4 mixed truth problem.

If legacy evidence and typed Phase-9 state refer to the same semantic identity:

### without explicit mapping

- preserve both sources losslessly,
- typed/reconciled authoritative read should report unresolved ambiguity when the legacy source could represent a competing truth,
- never merge by display label.

### with explicit mapping

- canonical typed state is the single read representation,
- mapped legacy projection is suppressed from canonical output,
- original legacy bytes remain untouched,
- mapping provenance/version is retained,
- disagreement becomes diagnostic rather than a second truth.

---

## 15. Persistence model

Phase 9 implementation will likely require additive tables, but exact schema is an implementation-time decision after real legacy DB inventory.

Expected logical tables/collections:

```text
innate_feature_definitions
player_innate_features
legacy_innate_evidence
legacy_innate_mappings

evolution_path_definitions
evolution_stage_definitions
evolution_transition_definitions
player_evolution_state
player_evolution_history / attained stages (if required)

transformation_definitions
player_form_unlocks
player_active_forms (only if runtime persistence is required)
```

If origin identity is proven necessary:

```text
origin_definitions
player_origins
```

Schema rules:

- additive migration,
- idempotent ensure,
- campaign+character scope,
- World Pack owner validation,
- stable UIDs,
- foreign keys where lifecycle permits,
- no destructive changes to Phase 3–8 tables,
- current schema entrypoint must reach Phase 9 only after Phase 8 accepted implementation exists.

---

## 16. Versioning and provenance

Every canonical feature/evolution assignment requires provenance.

Definition provenance examples:

- World Pack seed,
- canon import,
- World Pack update,
- explicit migration.

Character state provenance examples:

- character creation,
- explicit canon seed,
- committed story event,
- legal evolution transition,
- explicit migration.

Do not invent historical awakening/evolution events for legacy rows that only contain a label/value.

Versioning rules:

- changing display name does not change identity,
- changing ownership requires explicit migration,
- changing stage topology requires compatible version/migration policy,
- player state version changes only on legal authoritative change,
- temporary active-form changes do not rewrite permanent feature/evolution history.

---

## 17. Evolution transition contract for future domain engine

Phase 9 should define persisted state and validation-ready transition data, but it must not implement the future full PlayerDomainEngine/ProgressionEngine.

Conceptual future command flow:

```text
request transition
-> identify current authoritative path/stage
-> World Pack rule validates prerequisites
-> produce proposed evolution change
-> invariant validation
-> authoritative commit/event
```

Phase 9 implementation may expose deterministic validation primitives, but no arbitrary AI/UI direct write should be considered a legal transition path.

---

## 18. Forbidden cross-domain mutations

The following are forbidden in Phase 9 architecture:

1. Stat threshold directly writes feature/evolution state without explicit validated transition.
2. Resource amount directly writes evolution state.
3. Talent/Potential write directly grants a feature or evolution.
4. Skill mastery directly grants feature/evolution merely by reaching a number.
5. Technique ownership directly creates feature/evolution state.
6. Feature acquisition silently rewrites persistent Skill/Technique mastery.
7. Evolution stage silently overwrites PlayerStat.baseValue as a temporary effect.
8. Active transformation writes permanent base stats merely because it is active.
9. Clan label automatically grants every associated bloodline feature.
10. World Pack name/display label becomes identity.
11. AI narration becomes canonical feature/evolution state without legal persistence path.
12. Derived effective values are persisted back as Phase-9 authority.

---

## 19. Required implementation preflight

Before Phase 9 runtime work begins, implementation must inspect the actual accepted post-Phase-8 master and campaign assets.

Required inventory:

1. `PRAGMA table_info(character_status_snapshot)` on representative old campaigns.
2. Search all save DB table/column names for race/species/clan/lineage/bloodline/innate/evolution/form/transformation-like data.
3. Search World DB tables for corresponding definitions and stable UIDs.
4. Inspect `canon_characters_v2` schema and real `clan_uid` usage.
5. Inspect `canon_constraints_v2` data that references lineage/innate/evolution semantics.
6. Inspect all prompt/context surfaces for narrative-only race/bloodline/evolution fields.
7. Check orphan references and duplicate labels.
8. Check multiple players/campaigns.
9. Verify accepted Phase-8 Technique schema and any innate requirements it introduces.
10. Confirm production `CurrentSchema.ensure()` target before adding V9.

---

## 20. Required test gates

### Identity/ownership

1. register World-Pack-owned innate feature definition;
2. duplicate feature UID incompatible metadata -> fail loud;
3. same label different World Pack UID -> separate;
4. ownership hijack -> reject;
5. missing/deprecated definition behavior explicit;
6. campaign isolation;
7. player isolation.

### Innate feature persistence

8. grant/persist/reopen feature;
9. temporary suppression does not delete ownership;
10. removing temporary effect restores derived state without re-grant;
11. feature provenance/version survives reopen;
12. 1000 features/records no authoritative truncation.

### Origin/clan semantics

13. clan identity alone does not auto-grant bloodline;
14. explicit World Pack clan->feature mapping works when canonical;
15. same clan label under different World Packs remains distinct;
16. hybrid/multiple origin fixture if model supports origins.

### Evolution

17. linear transition fixture;
18. branching transition fixture;
19. cycle/topology validation according to declared rules;
20. missing transition target fails deterministically;
21. invalid transition does not mutate current stage;
22. current stage persists across reopen;
23. attained history is not erased by later stage;
24. version mismatch fails or requires migration;
25. World Pack A path cannot reference B-owned stage unless explicit cross-pack policy exists.

### Transformation

26. form unlock persists;
27. active form is separate from unlock;
28. deactivation does not remove unlock;
29. active form temporary modifiers do not mutate base stat/Skill/Technique mastery;
30. resource cost uses stable Resource UID if applicable;
31. unknown/deleted resource target fails deterministically.

### Cross-domain isolation

32. Talent update does not grant feature/evolution;
33. Potential update does not grant feature/evolution;
34. Skill update does not grant feature/evolution;
35. Technique update does not grant feature/evolution;
36. feature/evolution update does not silently rewrite Skill mastery;
37. feature/evolution update does not silently rewrite Technique mastery;
38. temporary effects use Phase-5 derived semantics.

### Legacy

39. old campaign with legacy race/bloodline/evolution fields opens losslessly;
40. no mapping -> no synthetic canonical feature;
41. explicit mapping -> one canonical identity;
42. mixed legacy+typed without mapping -> deterministic unresolved/fail-loud;
43. legacy bytes preserved;
44. `clan_uid` not auto-converted to feature;
45. ambiguous `form` remains unresolved;
46. migration idempotency;
47. reopen equality.

### Production migration/integrity

48. old Phase-8 campaign production bootstrap reaches Phase 9 schema;
49. restore reaches Phase 9 schema;
50. campaign switch reaches Phase 9 schema;
51. Phase 3–8 authoritative state unchanged by migration;
52. `PRAGMA integrity_check`;
53. `PRAGMA foreign_key_check` or documented FK policy;
54. full JVM tests;
55. signed APK build;
56. CI success.

---

## 21. Known architectural risks

### Risk A — semantic over-unification

A single `race_or_bloodline` string cannot represent origin, feature ownership, evolution state and active form safely.

### Risk B — clan inference

Existing `clan_uid` is tempting as an automatic bloodline source. This is unsafe unless World Pack semantics explicitly guarantee the mapping.

### Risk C — evolution as numeric stat

A single global `evolutionLevel` would incorrectly force every World Pack into a linear scale and blur irreversible stages with reversible forms.

### Risk D — temporary transformation retrogression

If active-form bonuses are written into base stats/mastery, deactivation can destroy or fabricate permanent progression. Reuse Phase-5 derived mechanics.

### Risk E — legacy label guessing

Words like `awakened`, `form`, `stage`, `race`, `bloodline`, `mutation` are not sufficient semantic contracts.

### Risk F — duplicate truth after typed migration

Phase 9 must adopt explicit mapping/suppression semantics from Phase 4/7 lessons so legacy and typed representations cannot both become authoritative.

### Risk G — World Pack topology updates

Changing evolution path topology under stable stage UIDs can invalidate player state. Definitions must be versioned and migration-aware.

---

## 22. Recommended implementation order after Phase 8 COMPLETE

1. Re-audit final accepted Phase-8 master and actual SQLite legacy schemas.
2. Freeze canonical Phase-9 semantic classification based on real data.
3. Implement World-Pack-owned definitions first.
4. Implement campaign/character feature ownership state.
5. Implement explicit legacy evidence/mapping/reconciliation.
6. Implement evolution path/stage/transition definitions and player state.
7. Add transformation unlock/runtime split only where real data/requirements justify it.
8. Integrate generic Phase-5 effects without a second resolver.
9. Add typed repository/context read path.
10. Wire production CurrentSchema entrypoint.
11. Run migration/no-regression/integrity/adversarial validation.

Do not implement Inventory Phase 10 as part of this work.

---

## 23. Final architectural contract

Phase 9 should establish a universe-agnostic runtime where:

```text
stable World Pack definitions
+ explicit character-owned innate/origin state
+ explicit evolution path/stage state
+ optional persistent form unlocks
+ separate runtime active forms
+ generic rule/modifier effects
+ versioned provenance
+ conservative legacy reconciliation
```

replace ambiguous strings without erasing the original legacy evidence.

The authoritative direction is:

```text
World Pack definition
-> explicit legal character grant/transition
-> persistent Phase-9 state
-> generic rule/derived projections
-> presentation/context
```

Never the reverse.

Phase-9 state remains separate from Stats, Resources, Talent, Potential, Skills and Techniques while allowing explicit stable-UID rule relationships to each.

# NEXT PHASE ARCHITECTURE READY — IMPLEMENTATION BLOCKED BY PHASE 8
