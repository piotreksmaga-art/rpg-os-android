# WORK-20260809-004 — Talent / Potential preparatory audit

Status: READ-ONLY DOMAIN AUDIT / PHASE 6 PREPARATION

Work ID: `WORK-20260809-004`
Worker: `CHAT-4`
Role: READ-ONLY DOMAIN AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Audited master at start: `ace51fa7cb635a4dcd6801865c300bc8c34f52cf`
Registered baseline in coordination file: `82b030271e5b7d653da457a2e9b2522e21234457`
Implementation status: **BLOCKED** until Phase 4 Dynamic Stats & Resources and Phase 5 DerivedValueResolver + Modifier Model are complete and stable.

This report is architecture/audit only. It does not implement models, tables, migrations, PlayerState changes, stat definitions, repository APIs, MASTER/ROADMAP/coordination changes, or runtime logic.

## Executive conclusion

The current runtime contains no integrated `TalentProfile`, `PotentialProfile`, `TalentEngine`, `PotentialEngine`, or equivalent domain abstraction. Legacy player data is currently projected mainly through `character_status_snapshot`, `character_stats`, `character_skills`, `character_techniques`, finances, organizations, goals and runtime conditions. `PlayerStateStore` classifies unknown legacy status fields as generic `PERSISTENT` data, therefore talent/potential-like columns can survive as `legacy_status.*` but are not semantically separated or validated.

The canonical Phase 6 design must preserve the strict distinction:

- **Talent** = ease / efficiency / aptitude of learning and developing in a domain.
- **Potential** = long-term possible scale, growth envelope or ceiling-like property in a domain or globally.

They are independent axes. The system must support all four combinations: high Talent + low Potential, low Talent + high Potential, high + high, low + low.

`Talent != current Skill Level` and `Potential != current stat value` are mandatory invariants.

The recommended Core contract is a generic, UID-addressed profile system using World Pack-defined domains. Core must not hardcode `genjutsu`, `raiton`, `zanjutsu`, `sonido`, `kido`, `reishi`, chakra-specific, Hollow-specific, bloodline-specific or other universe vocabulary. World Packs should register domain definitions and optional mappings/rules, while Core owns stable identity, profile semantics, provenance, validation and progression integration.

---

# 1. Existing legacy data

## 1.1 Current Android runtime

Confirmed relevant files:

- `app/src/main/java/com/rpgos/app/PlayerStateContract.kt`
- `app/src/main/java/com/rpgos/app/PlayerStateStore.kt`
- `app/src/main/java/com/rpgos/app/CharacterPanel.kt`
- `app/src/main/java/com/rpgos/app/LocalGameStore.kt`
- `app/src/main/java/com/rpgos/app/ContextBuilder.kt`
- `app/src/main/java/com/rpgos/app/MigrationManager.kt`
- `backend/app.py`
- `docs/PHASE_0_PLAYER_STATE_AUDIT.md`
- `docs/GM_ENGINE_TARGET_ARCHITECTURE.md`
- `docs/RPG_OS_MASTER_ARCHITECTURE.md`

No dedicated Kotlin file implementing target Talent/Potential concepts exists in the audited tree.

## 1.2 Legacy CharacterPanel

`CharacterPanelSnapshot` v1 contains:

- identity,
- stats,
- resources,
- skills,
- techniques,
- equipment,
- relationships,
- goals.

There are no `talents` or `potential` sections.

`CharacterPanelReader.readLegacyStatus()` reads every column of `character_status_snapshot` and classifies only fields whose names contain `chakra`, `stamina` or `energy` as resources. Every other legacy status column is rendered under `identity`. Therefore any legacy columns such as `talent`, `potential`, `growth_rate` etc. would currently be presentation-level identity lines, not a domain model.

## 1.3 Phase 3 PlayerState legacy preservation

`PlayerStateStore.splitLegacyStatus()` reads the whole legacy `character_status_snapshot` row and sends every field through `PlayerStatePolicy.classifyLegacyField()`.

The policy recognizes runtime-like names (`current_hp`, current resource names, fatigue, cooldown, temporary etc.) and derived-like names (`effective_`, `derived_`, `max_`, regeneration, net worth, combat rating). Everything else becomes `PERSISTENT` as `legacy_status.<field>`.

Implication: talent/potential-like legacy fields are likely preserved, but only as opaque persistent key/value data. Their meaning, unit, scope, provenance, visibility, domain and relation to progression are not defined.

This preservation behavior is valuable for migration safety and should not be replaced by destructive reinterpretation.

## 1.4 Stats and skills are not substitutes

Existing:

- `character_stats(stat_key,current_value)` read path,
- `character_skills` with mastery/xp,
- `character_techniques` with mastery/xp and other state.

None of those should be repurposed as the canonical storage for Talent or Potential.

A value such as Genjutsu mastery 80 is learned competence, not talent. Strength 90 is current/base stat state, not potential. Technique mastery is not aptitude. A rank, class or bloodline is not itself a Talent score.

## 1.5 Bundled data and World Packs

The audited master tree contains:

- `app/src/main/assets/Naruto.worldpack.zip`
- `content/packages/Naruto-v2.zip`
- `app/src/main/assets/Naruto_Default.campaign.zip`
- `app/src/main/assets/rpg_core.db`

No Bleach World Pack artifact is present in the audited repository tree.

The packaged SQLite/ZIP artifacts are binary. The available repository text tooling exposes their existence but does not provide safe schema/content text inspection of those binary files in this audit session. Therefore this report does **not** claim absence of talent-like columns inside the bundled Naruto DBs. Before Phase 6 migration implementation, the implementing worker must perform a schema-level dump of `campaign.db`, `world.db` and relevant core/reference DBs and search for all terminology listed in this work item.

This limitation is intentionally recorded instead of guessing at binary contents.

## 1.6 Backend/system prompt state

`backend/app.py` currently describes `player_state.persistent`, `.runtime`, `.derived`, plus skills/techniques. It has no canonical Talent/Potential behavior. The AI therefore has no validated semantic contract preventing it from confusing aptitude, mastery, stats and long-term potential unless later domain mechanics enforce the distinction.

---

# 2. Terminology conflicts

The repository architecture documents already use some terminology that can collide if Phase 6 is implemented naively.

## 2.1 `growthRatePotential`

The target architecture sketch lists `growthRatePotential` under Potential while Talent is defined as modifying learning rate/effective practice.

Risk: both could be interpreted as the same multiplier.

Resolution:

- Talent modifies **efficiency of acquiring progress from a concrete learning/development attempt** in a domain.
- Potential modifies **long-horizon growth response/envelope**, especially as current level rises, adaptation accumulates, soft caps are approached, breakthroughs/evolutions occur or high-end scaling is resolved.

A character may learn fundamentals quickly due to Talent but stop scaling early because Potential is low.

## 2.2 `aptitude`, `gifted`, `talent`

Treat these as migration vocabulary requiring explicit mapping, not as separate Core concepts by default.

Suggested semantic mapping candidates:

- aptitude -> usually Talent if explicitly learning/affinity related,
- gifted -> ambiguous label, requires context/manual mapping,
- prodigy -> usually narrative/presentation descriptor derived from observed Talent + achievement, not an authoritative numeric type,
- genius -> ambiguous; may mean learning efficiency, creativity/innovation or simply high current skill.

Never auto-map ambiguous narrative labels to numeric authoritative values without evidence.

## 2.3 `affinity`

Affinity is not universally the same as Talent.

Examples:

- elemental affinity can mean eligibility/compatibility,
- biological affinity can reduce cost/risk,
- spiritual affinity can modify output,
- affinity can influence learning efficiency.

Core should allow World Pack rules to *use* affinity as an input/modifier to Talent or progression, but should not globally equate affinity with Talent.

## 2.4 `creativity` / `innovation`

These can be Talent domains, Potential dimensions or ordinary stats depending on game rules.

Canonical recommendation:

- `creativity` as a Talent domain only when it measures effectiveness of creative/problem-solving learning/development actions,
- `innovationPotential` as long-horizon ability to create novel methods/break paradigms,
- actual created techniques/projects remain historical achievements, not profile values.

## 2.5 `adaptation`

Current MASTER progression vocabulary includes adaptation as a factor and target Potential architecture mentions adaptation potential.

Distinguish:

- current adaptation state/history = mutable progression context (how adapted the character already is to a stimulus),
- adaptation potential = long-term trait governing how much/how well the character can continue adapting.

## 2.6 `evolution potential`

Must not mean current evolution stage or eligibility flag.

- current stage = innate/evolution state,
- eligibility = World Pack rule result,
- evolution potential = long-term capacity/quality/scaling related to evolution pathways.

## 2.7 `maximum potential`

Avoid interpreting as a universal hard numeric cap unless a World Pack explicitly defines one. MASTER already allows soft caps, breakthroughs and evolutions.

Core should support a normalized/relative descriptor or parameter consumed by the progression/evolution rule rather than globally enforce `current <= maximumPotential`.

---

# 3. Canonical Talent definition

**Talent is the character's domain-specific or general efficiency/ease of learning, practicing, understanding or developing capability, given a valid cause and activity.**

Talent answers questions such as:

- How quickly does this character understand a new concept?
- How much useful progress is extracted from equivalent training quality/time?
- How difficult is it for this character to learn within a domain?
- How efficiently does feedback translate into improvement?

Talent does **not**:

- grant a learned skill automatically,
- grant XP without a causal action,
- imply a high current mastery,
- guarantee high end-game scale,
- override prerequisites/eligibility unless a World Pack rule explicitly says so,
- overwrite stats,
- replace affinities, bloodlines or racial capabilities.

Recommended ProgressionEngine role:

`effectiveLearning = basePracticeEffect * talentFactor * methodQuality * mentorFactor * environmentFactor * novelty * otherModifiers`

This is illustrative semantics, not a Phase 6 implementation formula.

Talent should generally be PERSISTENT authoritative state, with temporary changes represented by modifiers resolved through Phase 5 rather than destructive rewrites.

---

# 4. Canonical Potential definition

**Potential is the character's long-term growth envelope: the possible scale, sustainability, adaptive capacity and/or ceiling-like properties of future development.**

Potential answers questions such as:

- How far can this character plausibly continue developing?
- How strongly do diminishing returns tighten at high levels?
- Can the character continue adapting to harder stimuli?
- What scale of breakthrough/evolution can be supported?
- How much high-end growth remains plausible after extensive training?

Potential does **not**:

- equal current Strength/Chakra/Reiatsu/etc.,
- equal current skill mastery,
- directly grant progress,
- always define a visible hard cap,
- imply fast learning.

Potential is normally consumed by ProgressionEngine and evolution/innate rule providers at long-horizon/high-level decisions.

A low-Talent/high-Potential character may progress slowly but eventually exceed a high-Talent/low-Potential character after enough valid development.

---

# 5. Proposed Core model

This is a conceptual contract only. Exact Kotlin/data/schema design is deferred until Phases 4 and 5 stabilize.

## 5.1 Core entities

Recommended conceptual objects:

### `TalentProfile`

- `characterUid`
- `entries: Map<domainUid, TalentEntry>`
- optional general/default entry
- profile version
- provenance/version metadata

### `TalentEntry`

- `domainUid`
- `value` or normalized rating
- optional confidence/visibility metadata if game design requires hidden values
- source/provenance
- created/updated event references
- optional metadata owned by World Pack rules

### `PotentialProfile`

- `characterUid`
- global dimensions and/or domain-scoped entries
- profile version
- provenance/version metadata

### `PotentialEntry`

- `domainUid` or global scope
- `dimensionUid`
- `value`
- source/provenance
- optional World Pack metadata

## 5.2 Recommended Potential dimensions

Core may define stable semantic dimensions, provided they are universe-neutral:

- `growth_scale`
- `adaptation`
- `innovation`
- `evolution`

`maximumPotential` should be treated carefully. If retained, define it as a generic high-end scaling parameter rather than a mandatory hard cap.

An alternative safer model is to keep all dimensions themselves UID-defined and provide standard Core definitions through seed/reference data. That avoids forcing every universe to implement irrelevant dimensions.

## 5.3 Stable UID rules

Use stable UIDs, not display labels.

Examples of generic IDs:

- `DOMAIN-GENERAL-LEARNING`
- `DOMAIN-PHYSICAL-DEVELOPMENT`
- `DOMAIN-ENERGY-CONTROL`
- `DOMAIN-CREATIVE-DEVELOPMENT`

World Pack-owned IDs should be namespaced/stably owned, for example conceptually:

- `naruto:domain:genjutsu`
- `naruto:domain:lightning-nature`
- `bleach:domain:zanjutsu`
- `bleach:domain:reishi-control`

Exact UID syntax should follow whatever stable UID convention becomes canonical for definitions after Phase 4/WorldRuleProvider work.

## 5.4 Values and scaling

Do not hardcode `1–5 stars`, `0–100`, letter grades or multipliers into the domain meaning without a normalization contract.

Recommended separation:

- storage value / rating,
- normalization/interpretation rule,
- presentation label.

World Packs may present 5 stars while Core consumes a normalized factor.

---

# 6. Domain model

Talent/Potential domains must be independent of Skills and Stats while allowing explicit relations.

## 6.1 DomainDefinition concept

Conceptual fields:

- `domainUid`
- `key`
- `displayName`
- `category`
- `worldPackUid` or Core ownership
- `parentDomainUid` optional
- `appliesToTalent`
- `appliesToPotential`
- `tags/metadata`
- optional resolver/rule references

## 6.2 Hierarchy

A hierarchy can support general -> specific domains:

`general_learning`
-> `combat_learning`
-> world-specific domain

However inheritance behavior must be deterministic and explicit. Do not automatically multiply every ancestor because that can cause runaway stacking.

Phase 5 modifier semantics should define whether domain factors are selected, combined, overridden, capped or weighted.

## 6.3 Skill relation

A SkillDefinition may optionally reference one or more Talent/Potential domains used during progression.

Example conceptual mapping:

`SkillDefinition.skillUid -> progressionDomainRefs[]`

This avoids matching by string labels such as `skill.category == "Genjutsu"`.

## 6.4 Stat relation

Stat growth may similarly reference domains through stat/growth definitions established in Phase 4.

Potential must not be stored inside `PlayerStat.baseValue`.

---

# 7. World Pack extension model

World Pack responsibilities:

- register domain definitions,
- map world skills/stat growth/evolution routes to those domains,
- define optional default/generated profile policies for newly created characters,
- define eligibility prerequisites,
- define visibility/presentation,
- define domain-specific progression/evolution hooks,
- define how affinity/bloodline/race affects Talent/Potential, if applicable.

Core responsibilities:

- stable profile ownership by characterUid,
- canonical Talent vs Potential semantics,
- validation,
- provenance/history integration,
- generic resolution inputs/outputs,
- generic persistence contract,
- migration safety,
- ProgressionEngine integration points.

World Pack must **not** create `NarutoTalentEngine` and `BleachTalentEngine` as full duplicated player engines. It should provide definitions/rules through the future `WorldRuleProvider` boundary.

---

# 8. Naruto examples

These are examples of extension capability, not hardcoded Core fields and not claims about current bundled DB contents.

## 8.1 Generic Talent domains a Naruto pack could define

- genjutsu learning
- ninjutsu learning
- taijutsu learning
- medical ninjutsu learning
- chakra control learning
- elemental nature domains (e.g. lightning nature)
- sealing/fuinjutsu learning
- sensory learning
- creative technique development

## 8.2 Potential dimensions/domains

- chakra growth scale
- physical growth scale
- chakra-control high-end potential
- adaptation potential
- technique innovation potential
- bloodline/evolution-related potential where canon/rules support it

## 8.3 Bloodline separation

Kekkei Genkai/bloodline ability is an innate eligibility/state system, not Talent itself.

A bloodline may provide modifiers/defaults to Talent/Potential or unlock domains, but ownership of the bloodline and Talent profile should remain distinct.

Example:

A character can possess Ketsuryugan but have mediocre general learning Talent. Another can have exceptional genjutsu Talent without possessing a particular bloodline.

## 8.4 Element affinity

Lightning affinity should not automatically equal lightning-learning Talent. A Naruto World Pack may choose to relate them through a modifier or rule.

---

# 9. Bleach examples

No Bleach World Pack artifact is present in the audited master tree. These are architecture examples only, based on the required generic capability.

A future Bleach pack could define Talent domains such as:

- zanjutsu learning
- hakuda learning
- hoho/sonido movement learning
- kido learning
- reishi control learning
- reiatsu control learning
- racial technique learning
- research/innovation if mechanically supported

Potential domains/dimensions could include:

- reiryoku growth scale
- reiatsu density/high-end scaling
- adaptation potential
- evolution potential for Hollow paths
- innovation potential

Racial state, Hollow stage, Zanpakuto state, awakening and evolution state must remain separate innate/evolution state. They may consume Potential or modify progression but must not be represented as Talent values.

---

# 10. Legacy migration strategy

Phase 6 migration must be conservative and provenance-preserving.

## 10.1 First rule: never infer destructive semantics

Do not delete, overwrite or reinterpret old fields merely because their names look similar to `talent` or `potential`.

Migration categories:

1. **Exact semantic match** — safe automatic mapping.
2. **Context-qualified match** — automatic mapping only if schema/table/column contract proves the meaning.
3. **Ambiguous label** — preserve legacy field and require explicit mapping/default policy.
4. **Narrative descriptor** — preserve as narrative/fact metadata; do not fabricate numeric Talent/Potential.

## 10.2 Search terms for implementation-time schema audit

Search tables, columns, JSON/text payloads and manifests for:

- talent
- aptitude
- gifted
- growth rate
- learning rate
- potential
- maximum potential
- adaptation
- evolution potential
- innovation
- creativity
- affinity
- bloodline talent
- racial talent/potential

Also inspect legacy CharacterPanel/status fields and World Pack definitions.

## 10.3 Migration provenance

Every migrated entry should record at minimum:

- original table/source,
- original column/key,
- original value,
- migration version,
- mapping rule ID,
- whether mapping was exact or manual/default,
- source campaign/world pack version if available.

## 10.4 No invented defaults for existing campaigns

If an old campaign has no Talent/Potential data, migration should not invent exceptional or average values silently.

Valid strategies include:

- explicit UNKNOWN/uninitialized state,
- deterministic World Pack backfill policy with provenance and version,
- player/GM-approved character-generation migration command,
- rules-derived defaults only where canon/world data clearly provides them.

The exact strategy must be selected after WorldRuleProvider and Player Domain mutation path exist.

---

# 11. Interaction with Phase 4

Phase 6 depends on Phase 4 but must not collapse into it.

Required contracts from Phase 4:

- stable dynamic `StatDefinition` identity,
- stable `PlayerStat` identity/state,
- clear base vs current/runtime/resource semantics,
- World Pack ownership/definition mechanism,
- migration-safe definition lookup.

Talent/Potential may affect **growth of stats**, but must never become hidden stat columns or be inferred from a current stat value.

A Phase 4 growth definition should be able to reference a progression/domain UID later without knowing Naruto/Bleach concepts.

Critical Phase 4 dependency question before implementation:

> What is the canonical definition UID/world-pack namespace pattern and how are definition references validated across campaign/world-pack versions?

Phase 6 should reuse that pattern rather than invent a second definition identity system.

---

# 12. Interaction with Phase 5

Phase 5 is a direct semantic dependency.

Talent/Potential need modifier behavior for:

- permanent innate modifiers,
- equipment/world effects where allowed,
- temporary seals/injuries/buffs affecting learning efficiency,
- environment effects,
- evolution-stage effects,
- affinity/bloodline/racial effects.

Do not destructively mutate canonical Talent because the character is temporarily injured, exhausted or sealed.

Recommended separation:

- authoritative/base Talent/Potential profile,
- modifier inputs from Phase 5,
- effective Talent/Potential used by ProgressionEngine,
- presentation of base vs effective where needed.

Phase 5 must define stacking/priority rules before Phase 6 can safely decide how multiple domain modifiers combine.

Critical dependency question:

> Does `DerivedValueResolver` support generic keyed/domain-scoped targets beyond Stats/Resources, or will Phase 6 require a reusable modifier-resolution primitive?

Do not answer this by bypassing Phase 5 with a custom Talent-only modifier engine.

---

# 13. Interaction with ProgressionEngine

ProgressionEngine is the primary consumer of Talent/Potential.

Recommended conceptual inputs for each progression attempt:

- action/source type,
- target stat/skill/technique/project domain,
- current level/mastery,
- duration,
- intensity,
- difficulty,
- mentor,
- environment,
- method,
- effective Talent for relevant domain,
- effective Potential parameters,
- fatigue/injury,
- novelty,
- adaptation state,
- diminishing returns,
- World Pack modifiers.

Recommended semantic ordering:

1. validate cause/action/prerequisites,
2. resolve relevant domain(s),
3. resolve effective Talent,
4. resolve training/practice quality,
5. resolve current adaptation/diminishing returns,
6. resolve Potential/high-end scaling response,
7. compute proposed gain,
8. validate no illegal regression/overflow/world-rule violation,
9. emit Progression Ledger entry and ChangeSet,
10. commit through canonical mutation path.

Talent and Potential must never be used by AI as permission to directly assign gains.

---

# 14. Required invariants

Mandatory invariants for future implementation:

1. `Talent != Skill mastery`.
2. `Talent != Technique mastery`.
3. `Talent != current stat value`.
4. `Potential != current stat value`.
5. `Potential != current resource maximum`.
6. `Potential != current evolution stage`.
7. High Talent does not imply high Potential.
8. High Potential does not imply high Talent.
9. No progress is created solely because Talent/Potential exists; a valid progression cause is required.
10. Temporary modifiers do not overwrite authoritative base Talent/Potential.
11. Every authoritative profile entry is scoped to one player/campaign identity.
12. Domain references use stable UID, not display-name matching.
13. World-specific domains cannot be hardcoded into Core enums/columns.
14. Unknown legacy values are preserved, not silently discarded.
15. Ambiguous legacy terms are not auto-converted to numeric authoritative values.
16. Profile changes require a legal domain/change path and provenance.
17. CharacterPanel/GM context are projections, never source of truth for profile mutation.
18. World Pack update cannot silently remap a domain UID to different semantics.
19. Removing a World Pack domain must not orphan/destroy historical profile data without compatibility handling.
20. Effective profile resolution must be deterministic for the same authoritative state + modifiers + world rules.
21. Potential hard caps are World Pack/rule decisions, not a universal Core assumption.
22. Bloodline/race/evolution ownership remains a separate innate domain.
23. Affinity is separate unless explicitly linked by World Pack rules.
24. Talent/Potential visibility to player is presentation policy and cannot alter authoritative values.
25. Cross-campaign player UID leakage is forbidden.

---

# 15. Required tests

## 15.1 Core semantic tests

- high Talent + low Potential remains representable and distinct,
- low Talent + high Potential remains representable and distinct,
- high + high,
- low + low,
- same skill mastery with different Talent profiles,
- same current stat with different Potential profiles,
- same Talent with different Potential produces different high-end progression behavior when ProgressionEngine exists,
- no learning attempt => no progress despite high Talent,
- no domain definition => fail loud or explicit unresolved result, never string fallback.

## 15.2 Domain tests

- Core domain + World Pack domain coexist,
- parent/general + specific domain resolution deterministic,
- same display name with different UIDs does not collide,
- renamed display label preserves UID identity,
- removed/deprecated domain remains migration-readable,
- invalid foreign World Pack domain reference is rejected or handled by explicit compatibility rule.

## 15.3 Modifier/Phase 5 tests

- temporary learning debuff changes effective Talent but not base Talent,
- permanent modifier stacking follows Phase 5 rules,
- injury/condition effect rollback restores effective value without rewriting profile,
- duplicate modifier application is prevented by stable source/transaction identity where applicable.

## 15.4 Migration tests

- exact legacy `talent_*` mapping preserves numeric value and provenance,
- exact legacy `potential_*` mapping preserves value and provenance,
- ambiguous `gifted` label is not fabricated into a numeric value,
- `affinity` is not auto-mapped to Talent without World Pack mapping,
- `growth_rate` ambiguous source is retained until classified,
- unknown custom World Pack talent domain survives migration,
- migration rerun is idempotent,
- partial migration failure rolls back,
- old campaign save -> migrate -> load preserves all unrelated Player State,
- no legacy PlayerStat/Skill/Technique value changes during Talent/Potential migration.

## 15.5 Progression integration tests

When Phase 20 exists:

- equal training inputs + higher domain Talent yields higher effective learning under rules,
- low Potential can tighten high-level diminishing returns without lowering current skill/stat,
- high Potential does not skip prerequisites,
- evolution Potential affects only explicitly connected evolution rule paths,
- innovation Potential can affect DevelopmentProject/creation progression without auto-creating a technique,
- ledger records resolved Talent/Potential inputs or normalized factors for auditability.

## 15.6 World Pack examples

Naruto pack tests should eventually cover at least:

- general learning vs genjutsu-specific Talent,
- elemental affinity separate from Talent,
- bloodline ownership separate from Talent/Potential,
- chakra-stat growth consumes potential through explicit mapping.

Bleach pack tests should eventually cover at least:

- zanjutsu vs reishi-control domains,
- racial/evolution state separate from evolution Potential,
- reiryoku/reitsu growth mapping through explicit definitions,
- no Naruto-specific Core assumptions.

## 15.7 Snapshot/context tests

- CharacterPanelSnapshot v2 can show Talent/Potential without becoming authoritative,
- GM_CONTEXT profile includes only policy-allowed profile detail,
- hidden Potential remains hidden in narrative while mechanics can still consume it,
- deleting/rebuilding presentation snapshot does not lose profile state.

---

# 16. Blockers before implementation

Phase 6 implementation is **BLOCKED** until the following are satisfied.

## Blocker A — Phase 4 contract stable

Need final stable contracts for:

- `StatDefinition` / `PlayerStat`,
- `ResourceDefinition` / `PlayerResource`,
- World Pack definition identity/namespace,
- persistence/migration pattern,
- repository access boundary.

The Phase 6 implementation must be rebased/audited against the actual result of `WORK-20260809-001`, not against this preparatory sketch.

## Blocker B — Phase 5 contract stable

Need final:

- `DerivedValueResolver` semantics,
- modifier target/addressing model,
- stacking/priority behavior,
- permanent vs temporary modifier representation,
- deterministic resolution contract.

Without this, Phase 6 risks creating an incompatible second modifier engine.

## Blocker C — binary schema audit

Before migration code, inspect actual bundled/current campaign and world databases, including Naruto packages, for all legacy terminology. Record exact tables, columns, types, constraints and representative values.

## Blocker D — WorldRuleProvider direction

Roadmap Phase 19 is later than Phase 6, so Phase 6 must avoid implementing world-rule orchestration prematurely. The profile/domain contract should be compatible with a later `WorldRuleProvider`, but Phase 6 should not create full universe-specific player engines.

If implementation requires rule-provider behavior to be authoritative immediately, coordinator should split/sequence the work rather than hardcode Naruto.

## Blocker E — canonical mutation path maturity

MASTER requires all authoritative changes to go through proposal -> resolution -> ChangeSet -> validation -> transaction -> commit. Current Phase 3 runtime still exposes lower-level legacy state patterns and the full PlayerDomainEngine/PlayerChangeSet path is later in roadmap.

Phase 6 should therefore focus on safe authoritative representation/read semantics and avoid granting broad direct mutation shortcuts that would later violate the global invariant.

---

# Proposed contract summary

The future contract should satisfy:

`Character -> TalentProfile(domainUid -> base aptitude/learning efficiency)`

`Character -> PotentialProfile(domain/dimension -> long-horizon growth property)`

`World Pack -> DomainDefinitions + mappings + optional rules/default policy`

`Phase 5 -> effective profile modifier resolution`

`ProgressionEngine -> consumes effective Talent + Potential together with cause, current level, difficulty, adaptation, novelty, environment, fatigue/injury and diminishing returns`

`Innate/Bloodline/Racial/Evolution -> separate state; may provide modifiers/eligibility/mappings but is not the profile itself`

`CharacterPanel/GM Context -> read-only projection`

Core remains universe-agnostic.

---

# Coordinator handoff

## Work ID

`WORK-20260809-004`

## baselineCommit

Fresh audit baseline used for this report: `ace51fa7cb635a4dcd6801865c300bc8c34f52cf`

Coordination-record baseline at assignment: `82b030271e5b7d653da457a2e9b2522e21234457`.

The difference consists of coordination/work-item registration commits, not Phase 4 implementation.

## findings

- no integrated Talent/Potential runtime model exists,
- Phase 3 preserves unknown legacy status fields but cannot assign Talent/Potential semantics,
- CharacterPanel v1 has no Talent/Potential sections,
- current stat/skill/technique data must not be reused as Talent/Potential,
- Naruto binary World Pack artifacts exist; Bleach pack is absent from master tree,
- binary DB/ZIP schema still requires implementation-time inspection,
- canonical Talent/Potential separation is compatible with MASTER and target architecture,
- generic `domainUid` extension model is required to avoid hardcoding universes.

## proposed contract

- `TalentProfile`: domain-scoped learning efficiency/aptitude state,
- `PotentialProfile`: long-horizon scale/adaptation/innovation/evolution properties,
- stable UID-based domains defined by Core/World Packs,
- explicit mappings from skills/stat growth/evolution rules to domains,
- Phase 5 resolves effective profile modifiers,
- ProgressionEngine consumes profiles but profiles never self-generate progress.

## migration risks

- ambiguous legacy vocabulary (`gifted`, `aptitude`, `growth_rate`, `affinity`),
- hidden talent/potential-like fields inside binary packaged DBs,
- accidental conversion of current skill/stat to profile value,
- invented defaults for old campaigns,
- domain UID changes across World Pack updates,
- double modifier engines if Phase 6 bypasses Phase 5,
- hardcoded Naruto/Bleach concepts leaking into Core.

## blockers

- Phase 4 result not yet available at audit baseline,
- Phase 5 not implemented,
- binary schema inspection pending,
- final modifier target/stacking contract pending,
- final World Pack definition UID pattern pending.

## dependencies on Phases 4 and 5

Phase 4 supplies definition identity, dynamic stat/resource growth attachment points and migration conventions.

Phase 5 supplies reusable deterministic modifier/effective-value semantics. Phase 6 must reuse those semantics and must not implement a parallel resolver.

Implementation should begin only after the coordinator accepts Phase 4 and Phase 5 contracts as stable enough for Phase 6 integration.
