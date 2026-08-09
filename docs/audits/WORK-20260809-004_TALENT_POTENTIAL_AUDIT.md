# WORK-20260809-004 — Talent / Potential preparatory audit

Status: READ-ONLY DOMAIN AUDIT / PHASE 6 PREPARATION

Work ID: `WORK-20260809-004`
Worker: `CHAT-4`
Role: READ-ONLY PHASE 6 DOMAIN ARCHITECTURE AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Original audit commit: `4d93e9cbe06e4cd1b12708aa6c1e699e0d8fc45c`
Fresh delta baseline: `cbadc98dad55360d3bcecfa3c99a998168c48261`
Phase 4 implementation audited: `a33514524ccdf8a51ee672f1fbf79616600b8d82`

This remains an architecture/audit document only. It does not implement Phase 6, alter runtime Kotlin, schema, migrations, PlayerState, stat/resource definitions, CampaignRepository, MASTER, ROADMAP or coordination.

The original full audit is preserved in Git history at commit `4d93e9cbe06e4cd1b12708aa6c1e699e0d8fc45c`. This revision retains its canonical conclusions and adds the required delta against the real Phase 4 implementation and the latest Phase 5 architecture report currently present on master.

## Executive conclusion

The real Phase 4 implementation is compatible with the proposed Phase 6 direction. It establishes a generic stable-UID definition/value pattern that Phase 6 should mirror conceptually without reusing stat/resource tables or semantics:

- `StatDefinition` is World-Pack-owned and keyed by stable `statUid`.
- `PlayerStat` is campaign + character scoped and stores authoritative persistent `baseValue`.
- `ResourceDefinition` is World-Pack-owned and keyed by stable `resourceUid`.
- `PlayerResource` is campaign + character scoped and stores current persisted `currentValue`.
- definitions expose generic rule references such as `growthRuleUid`, `derivationRuleUid`, `maxRuleUid`, `regenerationRuleUid`.
- Core does not hardcode Naruto/Bleach stat/resource names.

Phase 6 should follow the same identity and ownership principles, but Talent and Potential are not stats/resources and must not be represented as `PlayerStat` or `PlayerResource`.

Canonical separation remains mandatory:

- **Talent** = ease / efficiency / aptitude of learning and developing in a domain.
- **Potential** = long-term possible growth scale / growth envelope / ceiling-like property.
- **Talent != Skill Level.**
- **Potential != current stat.**
- **Talent != Potential.**

All four combinations must remain valid: high Talent + low Potential, low Talent + high Potential, high/high, low/low.

---

# 1. Existing legacy data

Current Android runtime still has no integrated `TalentProfile`, `PotentialProfile`, `TalentEngine` or equivalent Phase 6 model.

Relevant existing state remains:

- Phase 3 `PlayerStateSnapshot` with PERSISTENT / DERIVED / RUNTIME separation,
- legacy `character_status_snapshot`,
- legacy `character_stats`,
- `character_skills`,
- `character_techniques`,
- Phase 4 `stat_definitions`, `player_stats`, `resource_definitions`, `player_resources`.

`PlayerStatePolicy.classifyLegacyField()` still places unknown status names into PERSISTENT. Therefore legacy fields named like `talent`, `aptitude`, `potential`, `growth_rate`, etc. can survive as opaque `legacy_status.*` values, but are not semantically modeled.

The CharacterPanel v1 still has no Talent/Potential sections.

Bundled Naruto World Pack/campaign artifacts are binary and require schema-level inspection before Phase 6 migration. No Bleach World Pack artifact was present in the audited repository tree.

---

# 2. Terminology conflicts

The following must not be collapsed into one concept:

- `talent` / `aptitude` / `gifted` — usually learning efficiency, but legacy labels may be ambiguous.
- `growth rate` — ambiguous between immediate learning efficiency and long-horizon scaling.
- `learning rate` — normally Talent-side behavior.
- `maximum potential` — Potential-side property; not necessarily a hard numeric cap.
- `adaptation` — current adaptation state is progression context; adaptation potential is long-horizon capability.
- `evolution potential` — not current evolution stage or eligibility flag.
- `innovation` / `creativity` — may be domain-specific Talent or Potential dimensions depending on the World Pack rule.
- `affinity` — compatibility/eligibility/modifier input; not globally synonymous with Talent.
- bloodline/racial talent or potential — profile inputs/definitions associated with innate systems, not the innate ability itself.

No migration should convert ambiguous descriptive labels into authoritative numeric profile entries without explicit evidence or World Pack mapping.

---

# 3. Canonical Talent definition

Talent is a persistent character property describing how efficiently a valid learning/development activity produces useful learning in a domain.

Talent may influence:

- effective practice gained from the same duration/quality,
- learning difficulty,
- comprehension speed,
- conversion of feedback into improvement,
- efficiency of training/research/mentorship in a domain.

Talent must not:

- grant a skill or technique automatically,
- generate progression without a causal source,
- equal current mastery,
- equal a stat value,
- guarantee high end-game scale,
- bypass prerequisites unless a World Pack rule explicitly defines such behavior.

Canonical authority class: **PERSISTENT authoritative profile state**. Temporary conditions affecting learning should be separate contextual modifiers/effects, not destructive changes to the base Talent profile.

---

# 4. Canonical Potential definition

Potential is a persistent character property describing long-term growth capacity, sustainable scale, adaptation headroom and ceiling-like characteristics.

Potential may influence:

- high-level diminishing returns,
- remaining growth headroom,
- response to increasingly difficult stimuli,
- breakthrough probability/eligibility inputs,
- evolution scaling,
- innovation/adaptation ceilings where rules support them.

Potential must not:

- equal Strength/Chakra/Reiatsu/etc.,
- equal current skill mastery,
- directly create stat growth,
- imply fast learning,
- always be exposed as a visible hard cap.

A low-Talent/high-Potential character may learn slowly but eventually surpass a high-Talent/low-Potential character after enough valid development.

Canonical authority class: **PERSISTENT authoritative profile state**.

---

# 5. Proposed Core model

Conceptual contract only; no runtime/schema implementation is authorized in this work item.

Recommended Core concepts:

`TalentProfile`
- `campaignId`
- `characterUid`
- `entries: domainUid -> TalentEntry`
- `version`
- provenance/version metadata

`TalentEntry`
- `domainUid`
- base talent rating/value in a normalized contract
- optional visibility policy
- provenance/source event
- version
- optional World Pack metadata

`PotentialProfile`
- `campaignId`
- `characterUid`
- domain/global entries
- `version`
- provenance/version metadata

`PotentialEntry`
- `domainUid` or global scope
- `dimensionUid`
- base potential rating/value
- provenance/source event
- version
- optional World Pack metadata

The exact persistence schema must be designed only when Phase 5 is stable and Phase 6 implementation is authorized.

Important: the Phase 4 `version` pattern is useful precedent. Phase 6 profiles/entries should be versionable to support deterministic cache invalidation, rule upgrades, migration and replay diagnostics.

---

# 6. Domain model

Phase 6 needs stable generic domains independent of stats/skills/techniques.

Recommended conceptual definition:

`ProgressionDomainDefinition`
- `domainUid`
- `key`
- `displayName`
- `category`
- `worldPackUid`
- optional `parentDomainUid`
- `appliesToTalent`
- `appliesToPotential`
- tags/metadata
- optional rule binding UIDs
- definition version

The name is illustrative; implementation may choose another neutral type name.

Domains must be keyed by stable UID, never by display name. World Pack ownership should follow the same rule demonstrated by Phase 4 definitions: a definition UID is owned by one World Pack and cannot be silently hijacked by another.

Hierarchy is optional. If parent/child domains exist, inheritance/combination semantics must be explicit and deterministic; Core must not automatically multiply every ancestor factor.

---

# 7. World Pack extension model

World Packs should define domain definitions and explicit associations. Core owns generic profile semantics, stable identity, provenance, versioning and integration points.

World Pack may define:

- domain definitions,
- labels/presentation scales,
- mappings from a skill definition to one or more progression domains,
- mappings from stat growth rules to progression domains,
- technique-learning domain references,
- innate/bloodline/racial effects that create or modify profile entries through legal domain changes,
- evolution rules that consume Potential dimensions,
- visibility rules for hidden/known potential,
- normalization/rule references.

Core must not contain literals/branches for `genjutsu`, `raiton`, `kido`, `zanjutsu`, `sonido`, `reishi`, `chakra`, `reiatsu`, etc.

Phase 4 proves that generic World-Pack-owned definition UIDs are viable. Phase 6 should use the same ownership principle without sharing the stat/resource definition tables.

---

# 8. Naruto examples

Examples are World Pack data/rules only, never Core enums.

Possible Naruto World Pack domains:

- illusion learning,
- lightning-nature development,
- medical technique learning,
- chakra-control development,
- physical conditioning,
- sensory development,
- creative technique development.

A bloodline can influence Talent/Potential entries or progression rules without being represented as a Talent itself.

Example valid combinations:

- high learning efficiency for illusion arts + low long-term growth scale,
- slow elemental learning + extremely high long-term potential,
- high talent and high potential for energy control,
- low talent and low potential for a domain.

Current skill mastery and current chakra-related stats remain independent.

---

# 9. Bleach examples

Examples are World Pack data/rules only.

Possible Bleach domains:

- sword-combat learning,
- spiritual-control learning,
- movement learning,
- spell-system learning,
- reishi manipulation,
- Hollow adaptation/evolution,
- innovative technique development.

Evolution potential is an input to future evolution rules, not current race/stage and not an automatic evolution trigger.

Reiryoku/reiatsu/reishi-related stats/resources remain Phase 4/5 domain values, not Potential itself.

---

# 10. Legacy migration strategy

Phase 6 migration must be additive, conservative and evidence-based.

Recommended sequence:

1. inventory all legacy talent/potential/aptitude/growth/affinity/evolution fields from actual campaign/world databases;
2. classify each source as clearly Talent, clearly Potential, unrelated, or ambiguous;
3. create stable domain mappings only where semantics are supported by data or World Pack rules;
4. preserve ambiguous source values as legacy data rather than inventing profile meaning;
5. never infer Talent from high current skill mastery;
6. never infer Potential from high current stat value;
7. never invent default potential for old campaigns without explicit migration policy;
8. record provenance from legacy source/path and migration version;
9. validate World Pack UID/domain ownership collisions;
10. verify reopen equality and cross-campaign/player isolation.

Legacy `character_stats` and Phase 4 `player_stats` must not be repurposed to store Talent/Potential.

---

# 11. Interaction with Phase 4

Real Phase 4 contract audited at `a33514524ccdf8a51ee672f1fbf79616600b8d82`:

`StatDefinition`
- `statUid`
- `key`
- `category`
- `unit`
- `minValue`
- `maxValue`
- `growthRuleUid`
- `derivationRuleUid`
- `worldPackUid`

`PlayerStat`
- `campaignId`
- `characterUid`
- `statUid`
- `baseValue`
- `version`

`ResourceDefinition`
- `resourceUid`
- `key`
- `category`
- `unit`
- `minValue`
- `maxValue`
- `maxRuleUid`
- `regenerationRuleUid`
- `worldPackUid`

`PlayerResource`
- `campaignId`
- `characterUid`
- `resourceUid`
- `currentValue`
- `version`

Phase 6 compatibility decisions:

- use `campaignId + characterUid` scoping for character profile state;
- use stable World-Pack-owned definition/domain UIDs;
- use explicit versioning;
- use finite normalized numeric values under a declared contract;
- do not encode Talent/Potential as `StatDefinition`/`PlayerStat`;
- allow stat `growthRuleUid` to reference a progression rule that in turn declares which Talent/Potential domains it consumes;
- allow derived/stat/resource formulas to consume profile inputs only through explicit rule dependencies, never by hardcoded key names;
- keep `PlayerResource.currentValue` unrelated to Potential/Talent semantics.

The Phase 4 fields `growthRuleUid`, `derivationRuleUid`, `maxRuleUid`, `regenerationRuleUid` are sufficient as **opaque rule binding identifiers** for Phase 6 integration. They do not need Phase 6-specific fields embedded into Phase 4 definitions. The later rule registry/provider can describe dependencies on Talent/Potential domains.

This avoids polluting Phase 4 with future-domain knowledge.

---

# 12. Interaction with Phase 5

The latest Phase 5 report currently present on master is `docs/audits/WORK-20260809-002_DERIVED_VALUE_AUDIT.md` from commit `053efb44989ac82fb9720e0449a40f4b43616911`. At this delta-audit moment, CHAT-2's newly requested post-Phase-4 addendum has not yet landed on master, so this report consumes the latest repository-visible Phase 5 design plus the real Phase 4 implementation directly.

Phase 5 design establishes a pure deterministic resolver over authoritative inputs and typed modifier sources. Phase 6 must integrate as follows:

- Talent/Potential base profiles are **authoritative PERSISTENT inputs**.
- They are not ordinary `TEMPORARY` modifier records.
- A resolver/rule evaluation may read profile entries when deriving effective progression parameters or other values.
- Temporary conditions can modify effective learning/progression context through the Phase 5 modifier system without rewriting base Talent/Potential.
- Permanent acquired changes to Talent/Potential require an explicit future legal domain change/event/provenance path; they are not resolver side effects.
- Derived effective learning factors are rebuildable outputs, not persisted back as profile base values.

Phase 5 ordering such as BASE -> PERMANENT -> EQUIPMENT -> INJURY -> TEMPORARY -> bounds should not be blindly reused as the semantic meaning of Talent/Potential. Instead:

`base Talent/Potential profile + explicit contextual modifier sources + rule context -> effective progression parameters`

The profile is an input domain, while modifier instances are contextual effects on the calculation.

No second modifier/resolver engine should be created inside Phase 6.

---

# 13. Interaction with ProgressionEngine

ProgressionEngine is the primary consumer of Talent/Potential.

Conceptual flow:

`training/combat/research/practice/evolution cause`
`+ current skill/stat level`
`+ duration/intensity/difficulty/quality/novelty`
`+ mentor/environment/method`
`+ fatigue/injury/adaptation state`
`+ TalentProfile domain entries`
`+ PotentialProfile domain/dimension entries`
`+ Phase 5 resolved contextual modifiers`
`-> deterministic progression result`

Talent should mainly affect learning efficiency/difficulty/effective practice.

Potential should mainly affect long-horizon scaling, diminishing returns, adaptation headroom, breakthrough/evolution scaling where applicable.

Neither profile generates progress alone.

Progression ledger should record which profile values/rule versions were used for explainability/replay, without turning derived multipliers into authoritative profile values.

---

# 14. Required invariants

1. Talent != Skill Level.
2. Potential != current stat.
3. Talent != Potential.
4. High/low combinations remain independent and valid.
5. Profile identity is campaign + character scoped.
6. Domain identity uses stable UID, not display names.
7. World Pack owns its domain definitions; UID hijacking is invalid.
8. Core contains no Naruto/Bleach domain-name branching.
9. Talent/Potential are persistent authoritative inputs.
10. Resolver output never overwrites profile base values.
11. Temporary effects are separate modifier/effect facts.
12. No progress occurs without a causal progression source.
13. Ambiguous legacy labels are not auto-promoted to numeric profiles.
14. Current mastery cannot seed Talent automatically.
15. Current stat magnitude cannot seed Potential automatically.
16. Affinity is not globally equal to Talent.
17. Evolution stage is not evolution potential.
18. Profile/domain values are finite and satisfy their normalization bounds.
19. Same input + rules/version gives deterministic effective progression parameters.
20. Provenance exists for profile creation/change/migration.
21. Profile entries are versionable.
22. Definition changes across World Pack versions cannot silently reinterpret existing values.
23. Cross-campaign/player profile leakage is impossible.
24. Derived/cache deletion loses no Talent/Potential authority.
25. Rule dependency cycles involving profile/stat/resource derived values are rejected or explicitly resolved by a future declared solver policy.
26. A Phase 4 stat/resource rule may reference profile domains only through stable rule metadata/provider contracts.
27. No duplicate profile application due to parent/child domain overlap unless combination policy explicitly allows it.
28. Hidden Potential presentation policy does not change authoritative mechanics.

---

# 15. Required tests

Future Phase 6 tests should include:

- high Talent + low Potential;
- low Talent + high Potential;
- high/high;
- low/low;
- same skill level with different Talent -> different learning efficiency;
- same current stat with different Potential -> different long-horizon scaling where rule applies;
- Talent changes do not rewrite Skill mastery;
- Potential changes do not rewrite stat base values;
- temporary learning debuff changes effective progression input but not base Talent;
- permanent profile change requires provenance/version increment;
- unknown custom World Pack domain works without Core code change;
- Naruto and Bleach test packs use the same generic Core contract;
- duplicate domain UID across World Packs is rejected;
- duplicate key within one World Pack follows explicit collision policy;
- reopen persistence equality;
- player A != player B;
- campaign A != campaign B;
- 100+ profile/domain entries are returned without silent truncation;
- NaN/Infinity rejected;
- legacy ambiguous labels preserved without invented mapping;
- legacy explicit talent/potential source migrates once and idempotently;
- Phase 5 cache deletion/rebuild produces identical effective progression inputs;
- changing a Phase 4 stat base value affects only rules that declare that dependency;
- changing Talent/Potential affects only rules/domains that declare those dependencies;
- World Pack update with stable domain UID preserves meaning/version migration;
- World Pack update attempting UID semantic hijack fails.

---

# 16. Blockers before implementation

Phase 6 implementation remains blocked by dependency order.

Current blockers:

1. Phase 4 exists but is still under validation/hardening by CHAT-1/CHAT-3 and has not been globally marked COMPLETE by the coordinator.
2. Phase 5 `DerivedValueResolver + Modifier Model` is not implemented.
3. CHAT-2's requested post-Phase-4 delta audit is not yet repository-visible at this exact baseline; any later accepted Phase 5 contract must supersede assumptions here where necessary.
4. Actual legacy Talent/Potential schema/content inside binary packaged campaign/world DBs still requires implementation-time inspection.
5. Full ProgressionEngine and WorldRuleProvider are later roadmap dependencies; Phase 6 should define narrow stable contracts without pulling their complete implementation forward.

None of these is an architectural defect in the proposed Phase 6 model. They are dependency/validation blockers.

---

# ADDENDUM — Delta against real Phase 4 and Phase 5 design

## A. Is Phase 4 an adequate structural predecessor for Phase 6?

**YES, at the contract level.**

Phase 4 demonstrates the exact generic principles Phase 6 needs:

- stable UID definitions,
- World Pack ownership,
- campaign/player scoped values,
- explicit base vs current semantics,
- versionable values,
- opaque rule-binding UIDs,
- no Naruto/Bleach hardcoding.

Phase 6 should not extend `StatDefinition` with `talentDomainUid`/`potentialDomainUid` fields by default. The cleaner architecture is for the referenced growth/derivation/progression rule metadata to declare domain dependencies. This keeps stats/resources generic and prevents future coupling.

## B. Should Talent/Potential be World Pack definitions?

**Domains and rule mappings: YES. Character values: NO — they are per-character profile state.**

World Pack defines what domains exist and how its mechanics consume them. A character owns profile entries for those domains.

## C. Should profiles be per character?

**YES.** They should be campaign + character scoped, matching Phase 4 isolation principles.

## D. Should domains use stable UID?

**YES, mandatory.** Display keys/names are labels. Stable UID is identity.

## E. Optional association targets

A World Pack/rule definition may associate a progression domain with:

- a stat growth rule UID,
- a skill definition UID,
- a technique definition/category UID,
- an innate/evolution rule UID,
- another domain/category UID.

The association must be explicit metadata/rule configuration, not inferred from matching strings.

## F. Resolver relationship

Talent/Potential are authoritative inputs to resolution/progression. They are **not** ordinary temporary modifiers.

A Phase 5 resolver may produce an effective learning factor or resolved progression parameter from:

`profile base + permanent contextual sources + injury/environment/temporary sources + rule semantics`.

The base profile remains intact.

## G. Provenance

Every authoritative profile entry/change should carry enough provenance to explain:

- source type,
- source UID/event,
- migration source if legacy,
- actor/method if relevant,
- created turn/time,
- rule/engine version,
- previous version/supersession if changed.

This follows MASTER's provenance requirement and future progression ledger needs.

## H. Versionability

Profile entries and domain definitions must be versionable. This is necessary for:

- World Pack updates,
- cache invalidation,
- deterministic replay,
- migration,
- preventing semantic reinterpretation of old values.

## I. Phase 4 rule UID sufficiency

`growthRuleUid`, `derivationRuleUid`, `maxRuleUid`, `regenerationRuleUid` are sufficient as opaque attachment points. Phase 6 does not require adding hardcoded Talent/Potential fields to Phase 4 definitions.

If a later Phase 5/Progression rule schema needs richer dependencies, extend the rule metadata/provider contract, not the Phase 4 base value objects unless a concrete accepted requirement proves otherwise.

## J. Final delta verdict

The real Phase 4 implementation removes the major uncertainty from the original audit: there is now a generic, stable-UID, World-Pack-owned definition pattern and explicit player base/current value semantics that Phase 6 can integrate with cleanly.

The repository-visible Phase 5 architecture is compatible with this design because it requires pure deterministic resolution, provenance-bearing modifier sources, rebuildable derived outputs, and protection of base progression. Phase 6 can therefore remain a separate authoritative profile domain consumed by later ProgressionEngine/resolution rules rather than becoming a special kind of stat or temporary modifier.

# PHASE 6 DESIGN READY, IMPLEMENTATION BLOCKED BY PHASE 5
