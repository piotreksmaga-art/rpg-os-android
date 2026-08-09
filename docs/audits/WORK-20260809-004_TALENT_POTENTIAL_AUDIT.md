# WORK-20260809-004 — Talent / Potential preparatory audit

Status: READ-ONLY PHASE 6 DOMAIN ARCHITECTURE AUDIT

Work ID: `WORK-20260809-004`
Worker: `CHAT-4`
Repository: `piotreksmaga-art/rpg-os-android`
Original audit commit: `4d93e9cbe06e4cd1b12708aa6c1e699e0d8fc45c`
Phase 4 implementation audited: `a33514524ccdf8a51ee672f1fbf79616600b8d82`
Start baseline for this delta: `cbadc98dad55360d3bcecfa3c99a998168c48261`
Phase 5 delta consumed: `735af8976082bae3f29affd0ab2ec9fce057bca9`

This document is architecture/audit only. It does not implement Phase 6 and does not modify runtime Kotlin, schema, migrations, PlayerState, stat/resource definitions, CampaignRepository, MASTER, ROADMAP or coordination.

The original full audit remains preserved in Git history at `4d93e9cbe06e4cd1b12708aa6c1e699e0d8fc45c`. This revision preserves its canonical conclusions and records the required delta against the real Phase 4 implementation and CHAT-2's post-Phase-4 Phase 5 design.

## Executive conclusion

The real Phase 4 implementation and the latest Phase 5 delta remove the main architectural uncertainty from the original Phase 6 audit.

Phase 4 now provides a generic, stable-UID, World-Pack-owned definition/value pattern:

- `StatDefinition(statUid, ..., growthRuleUid, derivationRuleUid, worldPackUid)`
- `PlayerStat(campaignId, characterUid, statUid, baseValue, version)`
- `ResourceDefinition(resourceUid, ..., maxRuleUid, regenerationRuleUid, worldPackUid)`
- `PlayerResource(campaignId, characterUid, resourceUid, currentValue, version)`

CHAT-2's delta audit concludes that these contracts are sufficient inputs for Phase 5 and that `derivationRuleUid`, `maxRuleUid`, and `regenerationRuleUid` are sufficient opaque rule binding points when paired later with a versioned rule registry/provider. Its final status is:

`PHASE 5 READY AFTER PHASE 4 VALIDATION`

Phase 6 should use the same identity/isolation/versioning principles, but Talent and Potential remain a separate persistent profile domain rather than a special kind of stat/resource or modifier.

Canonical separation:

- **Talent** = ease / efficiency / aptitude of learning and development.
- **Potential** = long-term possible scale / growth envelope / ceiling-like property.
- **Talent != Skill Level.**
- **Potential != current stat.**
- **Talent != Potential.**

All four combinations must remain valid: high Talent + low Potential, low Talent + high Potential, high/high, low/low.

---

# 1. Existing legacy data

No integrated `TalentProfile`, `PotentialProfile`, `TalentEngine` or equivalent Phase 6 runtime model exists.

Relevant persisted/read data includes:

- Phase 3 `PlayerStateSnapshot` with PERSISTENT / DERIVED / RUNTIME separation,
- legacy `character_status_snapshot`,
- legacy `character_stats`,
- `character_skills`,
- `character_techniques`,
- Phase 4 `stat_definitions`, `player_stats`, `resource_definitions`, `player_resources`.

`PlayerStatePolicy.classifyLegacyField()` still sends unknown legacy status fields to PERSISTENT. Therefore legacy names such as `talent`, `aptitude`, `potential`, or `growth_rate` may survive as opaque `legacy_status.*` values, but do not yet carry canonical Phase 6 semantics.

CharacterPanel v1 still has no Talent/Potential sections.

Bundled Naruto campaign/world-pack artifacts are binary and still require schema-level inspection before a Phase 6 migration is implemented. No Bleach World Pack artifact was present in the audited repository tree.

---

# 2. Terminology conflicts

Do not collapse the following concepts:

- `talent` / `aptitude` / `gifted`: usually learning efficiency, but legacy labels may be ambiguous;
- `learning rate`: normally Talent-side behavior;
- `growth rate`: ambiguous; may mean immediate learning efficiency or long-horizon scaling;
- `maximum potential`: Potential-side property, not necessarily a hard numeric cap;
- current `adaptation`: progression context/history;
- `adaptation potential`: long-horizon capacity to continue adapting;
- current evolution stage/eligibility: innate/evolution state/rule result;
- `evolution potential`: long-term capacity/quality/scaling input;
- `affinity`: compatibility/eligibility/rule input, not globally synonymous with Talent;
- creativity/innovation: can be a Talent domain, Potential dimension, or ordinary stat depending on World Pack semantics.

Ambiguous narrative labels must never be auto-promoted into numeric authoritative profile entries without explicit evidence or World Pack mapping.

---

# 3. Canonical Talent definition

Talent is persistent character state describing how efficiently valid learning, practice, training, research or development produces useful learning in a domain.

Talent may influence:

- effective practice from equal time/quality,
- learning difficulty,
- comprehension speed,
- conversion of feedback into improvement,
- mentorship/training efficiency in a domain.

Talent must not:

- grant a skill/technique automatically,
- generate progress without a causal activity,
- equal current mastery,
- equal a stat value,
- guarantee high end-game scale,
- bypass prerequisites unless an explicit World Pack rule allows it.

Authority class: **PERSISTENT authoritative profile state**.

Temporary conditions that affect learning are contextual effects/modifiers; they do not destructively rewrite base Talent.

---

# 4. Canonical Potential definition

Potential is persistent character state describing long-term growth capacity, scale, adaptation headroom and ceiling-like properties.

Potential may influence:

- high-level diminishing returns,
- remaining growth headroom,
- adaptation at extreme training levels,
- breakthrough/evolution rule inputs,
- long-horizon innovation/scaling.

Potential must not:

- equal current Strength/Chakra/Reiatsu/etc.,
- equal current skill mastery,
- directly grant growth,
- imply fast learning,
- necessarily appear as a visible hard cap.

A low-Talent/high-Potential character may learn slowly but eventually outscale a high-Talent/low-Potential character.

Authority class: **PERSISTENT authoritative profile state**.

---

# 5. Proposed Core model

Conceptual only; exact Kotlin/schema design is deferred until implementation is authorized.

Recommended objects:

`TalentProfile`
- `campaignId`
- `characterUid`
- `entries: domainUid -> TalentEntry`
- `version`
- provenance/version metadata

`TalentEntry`
- `domainUid`
- normalized/base talent value
- optional visibility policy
- provenance/source event
- version
- optional World Pack metadata

`PotentialProfile`
- `campaignId`
- `characterUid`
- global/domain entries
- `version`
- provenance/version metadata

`PotentialEntry`
- `domainUid` or global scope
- `dimensionUid`
- normalized/base potential value
- provenance/source event
- version
- optional World Pack metadata

Phase 4's explicit `version` field is a useful precedent. Phase 6 profile/entry versioning is required for deterministic rebuild, World Pack update compatibility, cache invalidation, migration and replay diagnostics.

---

# 6. Domain model

Talent/Potential domains must be generic and independent from stats, skills and techniques.

Conceptual definition:

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
- optional rule-binding UIDs
- definition version

Exact type name is not canonical yet.

Stable UID is identity; display names/keys are labels.

World Pack ownership should follow Phase 4's proven principle: a stable definition UID belongs to one World Pack and cannot be silently hijacked by another.

If domain hierarchy exists, inheritance/combination must be deterministic and explicit. Never automatically multiply all ancestor domains.

---

# 7. World Pack extension model

World Packs define domain vocabulary and mappings. Core owns profile semantics, stable identity, provenance, validation/versioning and integration points.

World Pack may define:

- progression-domain definitions,
- presentation scales/labels,
- skill -> domain mappings,
- technique/category -> domain mappings,
- stat-growth-rule -> domain dependency mappings,
- innate/bloodline/racial effects on profile entries,
- evolution rules consuming Potential,
- hidden/visible profile policies,
- normalization and rule references.

Core must not contain branches/literals for `genjutsu`, `raiton`, `kido`, `zanjutsu`, `sonido`, `reishi`, `chakra`, `reiatsu`, etc.

---

# 8. Naruto examples

Naruto-specific concepts belong only in the Naruto World Pack.

Possible domains can include illusion learning, elemental-nature development, medical learning, energy-control development, sensory development or creative technique development.

Bloodline abilities may affect Talent/Potential through World Pack rules but are not themselves Talent scores.

A high current Genjutsu mastery does not prove high Genjutsu Talent. A high Chakra-related stat does not prove high Potential.

All high/low Talent/Potential combinations must remain valid.

---

# 9. Bleach examples

Bleach-specific concepts belong only in the Bleach World Pack.

Possible domains can include sword learning, spiritual-control learning, movement learning, spell-system learning, reishi manipulation, Hollow adaptation/evolution or innovative technique development.

Evolution potential is an input to future evolution rules, not current race/stage and not an automatic evolution trigger.

Reiryoku/Reiatsu/Reishi stats/resources remain Phase 4/5 values, not Potential itself.

---

# 10. Legacy migration strategy

Phase 6 migration must be additive and conservative.

Required migration approach:

1. inspect actual campaign/world DB schemas for talent/potential/aptitude/growth/affinity/evolution fields;
2. classify each source as clearly Talent, clearly Potential, unrelated, or ambiguous;
3. create stable domain mappings only where semantics are supported by data/World Pack rules;
4. preserve ambiguous source values instead of inventing semantics;
5. never infer Talent from current Skill mastery;
6. never infer Potential from current stat magnitude;
7. never invent defaults without an explicit migration policy;
8. attach provenance to migrated entries;
9. validate domain UID/World Pack ownership collisions;
10. test idempotency, reopen equality, cross-player isolation and cross-campaign isolation.

Neither legacy `character_stats` nor Phase 4 `player_stats` should be repurposed to store Talent/Potential.

---

# 11. Interaction with Phase 4

Real Phase 4 contract:

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

Phase 6 compatibility rules:

- character profile state is campaign + character scoped;
- domains use stable World-Pack-owned UIDs;
- entries/definitions are versionable;
- numeric values must be finite and normalized under declared bounds;
- Talent/Potential are not represented as `PlayerStat` or `PlayerResource`;
- stat growth rules may consume Talent/Potential through rule metadata/provider dependencies;
- `PlayerResource.currentValue` has no Talent/Potential semantics.

The Phase 4 rule binding fields are sufficient as opaque attachment points. Phase 6 does **not** need `talentDomainUid` / `potentialDomainUid` fields hardcoded into `StatDefinition` or `ResourceDefinition`.

---

# 12. Interaction with Phase 5

CHAT-2 delta commit consumed: `735af8976082bae3f29affd0ab2ec9fce057bca9`.

Its important findings for Phase 6:

- `PlayerStat.baseValue` is authoritative persistent base progression.
- `PlayerResource.currentValue` is authoritative current resource quantity.
- effective stat value, resource maximum and regeneration are DERIVED/rebuildable.
- stable definition UIDs and World Pack ownership are adequate resolver targets.
- `derivationRuleUid`, `maxRuleUid`, `regenerationRuleUid` are sufficient bindings when paired with a versioned deterministic rule registry/provider.
- modifier sources require stable identity/provenance/lifecycle.
- resolver is pure/read-only and must not destroy base progression.
- legacy values must not remain parallel resolver base inputs after canonical Phase 4 migration.
- final status: `PHASE 5 READY AFTER PHASE 4 VALIDATION`.

Phase 6 integration therefore becomes explicit:

- Talent/Potential profiles are **authoritative PERSISTENT resolver/progression inputs**.
- They are **not ordinary TEMPORARY modifier records**.
- Phase 5 may resolve contextual effects that change effective learning/progression parameters without rewriting base Talent/Potential.
- permanent acquired changes to Talent/Potential require future legal domain changes + provenance/versioning, not resolver side effects.
- no second modifier/resolver engine should exist inside Phase 6.

Conceptually:

`base Talent/Potential + rule dependencies + contextual modifier sources -> effective progression parameters`

The profile is an input domain; modifier instances are contextual effects on the calculation.

---

# 13. Interaction with ProgressionEngine

ProgressionEngine is the principal consumer of Talent/Potential.

Conceptual input flow:

`training/combat/research/practice/evolution cause`
`+ current level/mastery/base stats`
`+ duration/intensity/difficulty/quality/novelty`
`+ mentor/environment/method`
`+ fatigue/injury/adaptation state`
`+ TalentProfile`
`+ PotentialProfile`
`+ Phase 5 resolved contextual modifiers`
`-> deterministic progression result`

Talent mainly affects learning efficiency/difficulty/effective practice.

Potential mainly affects long-horizon scaling, diminishing returns, adaptation headroom and breakthrough/evolution scaling where rules declare it.

Neither profile creates progress by itself.

Progression Ledger should record which profile values, source versions and rule/provider versions were used so replay/explainability remains possible.

---

# 14. Required invariants

1. Talent != Skill Level.
2. Potential != current stat.
3. Talent != Potential.
4. High/low combinations remain independent and valid.
5. Profile identity is campaign + character scoped.
6. Domain identity uses stable UID, not display name.
7. World Pack cannot hijack another pack's domain UID.
8. Core has no Naruto/Bleach domain-name branching.
9. Talent/Potential are PERSISTENT authoritative inputs.
10. Resolver execution never overwrites base profile values.
11. Temporary learning effects are separate source/effect facts.
12. No progression without a causal source.
13. Ambiguous legacy labels are not auto-promoted to profile values.
14. Skill mastery cannot auto-seed Talent.
15. Stat magnitude cannot auto-seed Potential.
16. Affinity is not globally equal to Talent.
17. Evolution stage is not evolution potential.
18. Profile/domain numeric values are finite and valid under declared normalization bounds.
19. Same authoritative input + context + rule/provider version gives deterministic resolved progression inputs.
20. Profile creation/change/migration carries provenance.
21. Profile entries and domain definitions are versionable.
22. World Pack update cannot silently reinterpret an existing stable UID.
23. Cross-campaign/profile leakage is impossible.
24. Cross-player/profile leakage is impossible.
25. Derived/cache deletion loses no Talent/Potential authority.
26. Rule dependency cycles are rejected unless a future explicit solver contract defines otherwise.
27. Parent/child domains cannot double-apply implicitly.
28. Hidden Potential presentation does not alter authoritative mechanics.
29. Phase 4 rule UIDs reference Talent/Potential only through stable rule metadata/provider dependencies.
30. Legacy profile migration is idempotent.

---

# 15. Required tests

Future Phase 6 test suite should include:

- high Talent + low Potential;
- low Talent + high Potential;
- high/high;
- low/low;
- same Skill level + different Talent -> different learning efficiency where rule applies;
- same stat base + different Potential -> different long-horizon scaling where rule applies;
- Talent change does not rewrite Skill mastery;
- Potential change does not rewrite stat base value;
- temporary learning debuff changes effective progression input but not base Talent;
- permanent profile change requires provenance/version increment;
- unknown/custom World Pack domain works without Core code changes;
- Naruto/Bleach test packs use the same generic contract;
- duplicate/hijacked domain UID rejected;
- duplicate key collision follows explicit policy;
- player A != player B;
- campaign A != campaign B;
- reopen persistence equality;
- 100+ profile/domain entries are not silently truncated;
- NaN/Infinity rejected;
- ambiguous legacy fields preserved without invented mapping;
- explicit legacy Talent/Potential migrates once/idempotently;
- Phase 5 cache deletion/rebuild gives identical effective progression parameters;
- only declared rule dependencies react to stat/profile changes;
- World Pack update with stable UID preserves semantics/version migration;
- semantic UID hijack on World Pack update fails.

---

# 16. Blockers before implementation

Phase 6 design is now structurally compatible with real Phase 4 and latest Phase 5 architecture.

Implementation is still blocked by dependency order:

1. Phase 4 is still undergoing validation/hardening and has not yet been globally accepted COMPLETE by the coordinator.
2. Phase 5 `DerivedValueResolver + Modifier Model` is architecture-ready after Phase 4 validation but is not implemented.
3. Actual legacy Talent/Potential content inside binary packaged campaign/world DBs still requires schema-level migration inspection before implementation.
4. Full ProgressionEngine and broad WorldRuleProvider remain later roadmap systems; Phase 6 should define narrow stable contracts without implementing those systems prematurely.

There is **no current Phase-4 contract-shape blocker** for the proposed Phase 6 model.

---

# ADDENDUM — Delta against real Phase 4 and CHAT-2 Phase 5 delta

## A. Should Talent/Potential be World Pack definitions?

**Domain definitions and mappings: YES. Character values: NO — character values belong to per-character profiles.**

World Pack defines what domains exist and how its mechanics consume them. The character owns Talent/Potential entries for those domains.

## B. Should profiles be per character?

**YES.** Campaign + character scoping should mirror Phase 4's isolation principles.

## C. Should domains be keyed by stable UID?

**YES, mandatory.** Names/keys are labels only.

## D. Optional associations

World Pack/rule metadata may associate domains with:

- stat growth rule UID,
- skill definition UID,
- technique definition/category UID,
- innate/bloodline/evolution rule UID,
- another progression domain/category UID.

Associations must be explicit metadata/configuration, never inferred from matching strings.

## E. Phase 5 resolver relationship

Talent/Potential are authoritative input state, **not** ordinary temporary modifiers.

Temporary/permanent contextual sources may influence effective progression parameters through the Phase 5 model, but base profiles remain independent and preserved.

## F. Provenance

Every authoritative profile entry/change should be explainable through source type/UID/event, turn/time, actor/method where relevant, migration source, rule/engine version and previous version/supersession where changed.

## G. Versionability

Profile entries and domain definitions must be versionable for World Pack updates, deterministic replay, cache invalidation and safe migration.

## H. Phase 4 rule UID sufficiency

`growthRuleUid`, `derivationRuleUid`, `maxRuleUid`, and `regenerationRuleUid` are sufficient neutral binding points. No Phase 6-specific fields need to be added to Phase 4 definitions merely to connect Talent/Potential.

The later versioned rule registry/provider should declare dependencies on progression domains.

## I. Compatibility with CHAT-2 verdict

CHAT-2's latest verdict is:

`PHASE 5 READY AFTER PHASE 4 VALIDATION`

That is fully compatible with this Phase 6 design. Once Phase 4 validation is accepted and Phase 5 is implemented, Phase 6 can consume exactly one resolver/rule architecture instead of inventing a parallel modifier system.

# PHASE 6 DESIGN READY, IMPLEMENTATION BLOCKED BY PHASE 5
