# WORK-20260816-003 — Phase 20 Integrity / Migration / Adversarial Audit

## 0. Audit identity

- **Work ID:** `WORK-20260816-003`
- **Role:** CHAT-3 — independent integrity / migration / adversarial reviewer
- **Mode:** READ-ONLY production runtime/schema/migrations/tests
- **Phase:** 20 — `ProgressionEngine + Progression Ledger`
- **Requested baseline SHA:** `9c81b08c86c341d50506ba99d8a6809d94134dcb`
- **Actual master HEAD at audit start:** `9c81b08c86c341d50506ba99d8a6809d94134dcb`
- **Actual master HEAD immediately before this report write:** `9c81b08c86c341d50506ba99d8a6809d94134dcb`
- **Baseline drift:** NONE
- **Production runtime modifications:** NONE
- **Database/schema/migration modifications:** NONE
- **Production test modifications:** NONE
- **Canonical MASTER/ROADMAP/ACTIVE WORK REGISTER modifications:** NONE

This audit is pre-implementation only. It does not implement Phase 20, does not repair findings, and does not mark Phase 20 COMPLETE or ACCEPTED.

## 1. Repository-first bootstrap evidence

The audit followed repository-first priority and inspected the current repository before drawing Phase-20 conclusions.

Canonical/required documents read:

- `docs/PROJECT_WORK_PROTOCOL.md`
- `docs/RPG_OS_MASTER_ARCHITECTURE.md`
- `docs/RPG_OS_IMPLEMENTATION_ROADMAP.md`
- `docs/GM_ENGINE_TARGET_ARCHITECTURE.md`
- `docs/PARALLEL_WORK_COORDINATION.md`
- `docs/architecture/CHAT_COORDINATION_POLICY.md`
- `docs/architecture/PHASE19_ACCEPTANCE.md`
- `docs/architecture/PHASE19_WORLD_RULE_PROVIDER_CANONICAL_SCOPE.md`
- `docs/architecture/PHASE19_DEFERRED_FINDINGS.md`
- `docs/architecture/POST_ENGINE_APPLICATION_CLEANUP_ROADMAP.md`

Recent commits were checked. The current `master` is exactly the requested baseline; therefore there are no post-baseline commits whose impact must be incorporated into this audit.

Most recent relevant commit at audit start:

`9c81b08c86c341d50506ba99d8a6809d94134dcb` — `docs: defer fresh-install campaign cleanup until engine completion`.

The accepted Phase-19 runtime remains the Phase-19 acceptance SHA recorded by the canonical acceptance documents, while current master contains later release/documentation-only sequencing. No current evidence was found that reopens Phase 19.

## 2. CI status

GitHub Actions was checked for current `master`.

- Workflow: `Validate RPG OS ALPHA`
- Run number: **539**
- Run ID: `31955952584`
- Head SHA: `9c81b08c86c341d50506ba99d8a6809d94134dcb`
- Event: `push`
- Status: `completed`
- Conclusion: **success**

The commit combined-status endpoint returned no legacy status contexts; the GitHub Actions workflow run itself is the relevant current CI evidence.

**CI verdict for audit baseline: GREEN.**

## 3. Canonical invariants controlling Phase 20

The following are mandatory and must remain true after any Phase-20 implementation:

1. `AI OUTPUT != COMMITTED REALITY`.
2. `FACT != BELIEF != NARRATIVE`.
3. Stable UID is identity; names/keys are labels.
4. `AUTHORITATIVE > DERIVED > CACHE/PRESENTATION`.
5. Durable progression does not regress without an explicit legal cause.
6. Every permanent gain has a structured cause/provenance path.
7. `PlayerChangeSet` remains a proposal until a later transaction commits it.
8. `PlayerDomainEngine.resolve()` must not perform authoritative commit.
9. `WorldRuleProvider` remains read-only legality authority and must not become a progression calculator or writer.
10. Phase 20 must not create a second Player Engine or second persisted source of truth.
11. A rejected command/resolution must create no authoritative progression.
12. A WorldRuleProvider rejection at PRECHECK or EFFECT_CHECK must create no authoritative progression.
13. A retry of the same logical operation must be architecturally capable of becoming `no duplicate effects` once Phase 27/28 transaction/idempotency infrastructure exists.

## 4. Existing progression-related infrastructure — current repository state

### 4.1 There is no production Phase-20 ProgressionEngine

Repository search found no current production `ProgressionEngine` implementation and no current persisted `progression_ledger` authoritative subsystem. Phase 20 remains NOT STARTED in the canonical roadmap.

This is correct for a pre-implementation baseline.

### 4.2 Existing `ProgressionProfile` infrastructure is Phase-6 Talent/Potential infrastructure, not Phase-20 runtime progression

Current files:

- `app/src/main/java/com/rpgos/app/ProgressionProfileModel.kt`
- `app/src/main/java/com/rpgos/app/ProgressionProfileStore.kt`
- `app/src/main/java/com/rpgos/app/Phase6Migration.kt`
- `app/src/test/java/com/rpgos/app/ProgressionProfilePersistenceTest.kt`

Existing model contains:

- `ProgressionDomainDefinition`
- `TalentEntry` / `TalentProfile`
- `PotentialEntry` / `PotentialProfile`
- `LegacyProgressionEvidence`
- `LegacyProgressionMapping`

Important semantics already accepted by the repository:

- progression domains are stable-UID definitions scoped to a `worldPackUid`;
- Talent and Potential are separate authoritative profile axes;
- profile values must be finite and non-negative;
- legacy ambiguous values are preserved as raw evidence;
- legacy evidence is not semantically reinterpreted until an explicit mapping exists;
- profile writes must not mutate stats, skills, resources or modifiers as a side effect;
- migration is additive/idempotent and does not auto-create Talent/Potential from old campaign values.

**Integrity requirement for Phase 20:** do not rename/reuse these tables/types as the new Progression Ledger. They are current-state/profile data and migration evidence, not an append-only record of Phase-20 gain causes.

### 4.3 Existing progression domains

`progression_domain_definitions` is already a durable definition table created by Phase 6.

Its identity is `domain_uid` with `world_pack_uid`, key, category, parent, applicability to Talent/Potential, version and provenance.

Phase 20 may consume progression-domain identity/version as an input, but must not silently reinterpret a domain definition as a mutable player progression balance or ledger row.

### 4.4 TalentProfile / PotentialProfile

Current Talent/Potential storage uses `Double` `baseValue` and explicit provenance. Current validation rejects NaN, Infinity and negative values.

Critical semantic boundary:

- **Talent:** learning/efficiency aptitude.
- **Potential:** long-term scale/growth property.
- neither is itself accumulated progress/mastery;
- neither may create a gain without a real source/cause.

Phase 20 must not implement `gain = talent` or `gain = potential`, must not mutate Talent/Potential merely because training occurs, and must not treat absence of a profile entry as an implicit numeric default unless a versioned rule explicitly defines that default.

### 4.5 SkillState / mastery / progress

Current `SkillStore` separates typed skill definitions and player skill state.

`PlayerSkill` persistence contains at least:

- `campaign_id`
- `character_uid`
- `skill_uid`
- `base_mastery`
- optional `progress_value`
- optional `progress_semantics_uid`
- version/provenance
- learned chapter

Skill definitions may link to multiple `progressionDomainUids`; `SkillStore` verifies those domains exist and belong to the same World Pack as the skill definition.

Legacy skill tables are still readable. Legacy mastery/XP is preserved. A typed/legacy collision with the same UID is not silently merged: explicit legacy mapping/supersession is required.

### 4.6 TechniqueState / mastery / progress

Current `TechniqueStore` uses campaign-scoped typed player state and stable technique UIDs. `PlayerTechnique` contains base mastery and optional progress value/semantics plus historical usage counters and provenance.

Legacy technique mastery, XP and related fields are preserved/read through explicit mappings. Typed/legacy duplicate authority requires explicit supersession.

Phase 20 must not overwrite legacy XP/mastery simply because it introduces a generic progression result representation.

### 4.7 Player stats/resources

Current stat/resource foundation is typed and campaign-scoped. PlayerChangeSet already expresses exact stat/resource deltas via `ExactLongDelta`.

Existing persisted stat/mastery/profile surfaces still use `Double` in several accepted models. This creates a mandatory deterministic conversion boundary for Phase 20 if calculation inputs use those values while proposed durable changes remain exact integer units.

### 4.8 DevelopmentProject history

DevelopmentProject is already an accepted domain with its own work/progress semantics. Existing `DevelopmentProjectChange` uses `ProjectProgressDelta`, deliberately distinct from `ExactLongDelta`.

Phase 20 may treat project work as a *cause/stimulus for player development*, but must not take ownership of project lifecycle/progress or recalculate project progress as generic progression.

### 4.9 Existing ledger/provenance envelope

`PlayerChangeSet` already has:

- `changes`
- `eventIntents`
- `ledgerIntents`
- preconditions
- `ChangeSetProvenance`

Current ledger payload family contains financial-transfer intents only.

`PlayerLedgerIntent` already carries:

- `ledgerIntentUid`
- `ledgerKindUid`
- `causalChangeUids`
- typed payload

This is the correct architectural extension point for a Phase-20 **progression ledger intent**.

Phase 20 should add a typed progression ledger intent/payload rather than creating an independent persisted writer/store that would bypass the later transaction/unified-ledger architecture.

## 5. Source-of-truth map

| Surface | Classification for Phase 20 | Rule |
|---|---|---|
| `player_stats` base/current persistent stat state | AUTHORITATIVE current player state | May change only through legal proposal -> later commit path. Do not reconstruct from ledger/narrative. |
| `player_skills_v2` base mastery/progress | AUTHORITATIVE current player state | Stable skill UID + campaign + character identity required. |
| `player_techniques_v2` base mastery/progress | AUTHORITATIVE current player state | Stable technique UID + campaign + character identity required. |
| TalentProfile/PotentialProfile entries | AUTHORITATIVE profile inputs | Inputs/modifiers to progression policy, not accumulated progression ledger. |
| `progression_domain_definitions` | AUTHORITATIVE World-Pack definition metadata | Definition identity/version; not player state. |
| Legacy progression evidence | HISTORY/EVIDENCE | Preserve raw data; never auto-convert without explicit mapping. |
| Legacy skill/technique records | LEGACY AUTHORITY/EVIDENCE according to existing reconciliation rules | Preserve and reconcile explicitly; do not overwrite. |
| Legacy mappings | MIGRATION/PROVENANCE EVIDENCE | Explicit semantic bridge; must remain stable/replayable. |
| Proposed Phase-20 `ProgressionLedgerIntent` inside PlayerChangeSet | PROPOSAL/EVIDENCE INTENT, **not committed truth** | Exists only as part of proposed result until future transaction commit. |
| Future committed progression ledger entry | HISTORY/EVIDENCE, append-only once commit exists | Explains why authoritative state changed; should not become a second current-state balance. |
| `PlayerResolutionEvidence` | RESOLUTION EVIDENCE | Deterministic context/component/entropy/WorldRule decision evidence; not current state. |
| CharacterPanelSnapshot / progression summary | DERIVED/PRESENTATION | Rebuildable; must never be used to restore progression truth. |
| cached summaries/indexes | CACHE | Deletable/rebuildable. |
| narrative training description | NARRATIVE | Never sufficient to create or reconstruct durable gain. |

### Source-of-truth conclusion

The primary double-truth risk is creating a `progression_ledger` table in Phase 20 and then treating both ledger totals and current stat/mastery values as independently writable authoritative balances.

**Required Phase-20 rule:** current state remains authoritative for “what is true now”; ledger is causal/history evidence for “why the change was proposed/committed”. The ledger must not be a parallel mutable balance.

## 6. Migration risk map

### M1 — automatic reinterpretation of legacy values

**Risk:** old skill XP, technique XP, mastery, custom profile keys, narrative fields or legacy “gifted/potential/talent” values are auto-mapped into Phase-20 meanings.

**Required behavior:** PRESERVE DATA + require explicit mapping. Existing Phase-6/7/8 behavior is the model to preserve.

### M2 — destructive remapping of legacy evidence

**Risk:** migration replaces raw legacy evidence with only a new normalized value.

**Required behavior:** PRESERVE DATA. Raw value/source/version/provenance must survive even when an explicit mapping later exists.

### M3 — collision between legacy and typed state

**Risk:** a legacy skill/technique UID and typed UID are treated as the same authority without explicit supersession.

**Required behavior:** FAIL CLOSED / explicit mapping. Preserve current SkillStore/TechniqueStore collision semantics.

### M4 — unknown/custom World Pack progression domains

**Risk:** migration drops a custom domain because Core does not recognize its name/category.

**Required behavior:** PRESERVE DATA. Stable UID and World Pack ownership outrank names. Unknown custom definitions are not deleted or renamed merely because Phase 20 has no calculator for them.

### M5 — old campaign without Phase-20 data

Recommended Phase-20 design does not require an authoritative persisted progression-ledger table before Phase 23/27. Therefore an old campaign may validly load with **zero Phase-20 committed ledger rows** and its current stats/masteries remain unchanged.

If CHAT-1 proposes any Phase-20 schema addition, it must be additive, idempotent, optional for old campaigns, and must not derive current values from absence/presence of new rows.

### M6 — migration interruption

Because Phase 20 should not perform destructive reinterpretation, interruption must never leave legacy rows deleted or partially remapped. Any additive migration must use the existing transactional migration pattern and be rerunnable.

### M7 — stable UID mutation during migration

`domainUid`, `skillUid`, `techniqueUid`, `characterUid`, campaign identity and explicit mapping UIDs must not be regenerated from current names.

### M8 — provenance loss

Any migrated/bridged evidence must retain original source identity and add new mapping provenance rather than replace the original provenance.

### M9 — unknown schema element from older data

Unknown/custom data should be retained as opaque/legacy evidence when Core cannot safely interpret it. “Unknown” is not permission to drop or coerce.

## 7. Legacy-data map

| Existing legacy/current location | What may be progression-relevant | Phase-20 migration rule |
|---|---|---|
| `character_stats` / older stat surfaces | historical/current numeric stat evidence | Do not copy blindly into ledger. Preserve accepted stat reconciliation authority. |
| `character_skills` | mastery, XP, chapter fields | Preserve; explicit typed mapping/supersession only. XP is not automatically Phase-20 progress units. |
| `character_techniques` | mastery, XP, use counters, costs, chapters | Preserve; explicit mapping only. Do not manufacture progression causes for historical mastery. |
| `legacy_progression_evidence` | raw ambiguous talent/potential/progression-like values | Preserve raw evidence exactly; explicit mapping required. |
| `legacy_progression_mappings` | explicit semantic mapping | Preserve and validate target domain/World Pack ownership. |
| typed Talent/Potential profiles | learning/growth input | Read as profile input; do not mutate from ordinary gain. |
| `player_skills_v2.progress_value` | typed optional progress value | Respect its `progress_semantics_uid`; do not assume universal XP units. |
| `player_techniques_v2.progress_value` | typed optional progress value | Respect semantics UID; do not overwrite with incompatible units. |
| DevelopmentProject work/history | project-domain work and evidence | May be source evidence; do not replace project-domain history with progression ledger. |

## 8. Campaign-isolation analysis

Existing typed player entries are campaign-scoped, but World Pack definitions such as progression-domain definitions are definition-level data scoped by World Pack UID rather than by campaign.

Therefore Phase 20 must carry and validate **both**:

- campaign identity for the subject/current state;
- immutable pinned World Pack binding/definition ownership for the policy/definitions used.

Required fail-closed checks before producing a grant:

1. command campaign == resolution context campaign;
2. target character exists in that same campaign and is the intended subject;
3. target stat/skill/technique belongs to the target character/current campaign when state is read;
4. target definition UID exists;
5. definition is compatible with the pinned World Pack binding;
6. progression domain UID exists and is owned by the pinned World Pack when domain-specific progression is used;
7. Talent/Potential entries come from the same campaign + character and their domain identity matches the requested computation;
8. no data may be selected by human-readable name alone;
9. no fallback to “first matching provider/domain” is allowed.

### Important Phase-18 integration observation

Current `PlayerResolutionReferenceKinds` includes skill and technique references plus several finance/project reference kinds, but does not currently expose a generic Phase-20 progression-domain/stat reference vocabulary sufficient by itself for all possible progression-generated targets.

**Mandatory CHAT-1 integration gate:** progression-generated stat/domain/other target references must pass an equivalent fail-closed current-campaign reference validation before final proposal assembly. This can be done by extending the typed reference vocabulary/validation in the authorized Phase-20 integration surface; it must not be skipped merely because the base command references were already validated.

## 9. Duplicate / idempotency risk map

Full transaction idempotency belongs to later phases, but Phase 20 must not make it impossible.

### Duplicate risk classes

1. same `progressionUid` emitted twice in one result;
2. same `ledgerIntentUid` emitted twice in one PlayerChangeSet;
3. one grant linked to two semantically duplicate ledger intents;
4. one ledger intent with no corresponding progression change/grant where a non-zero grant is claimed;
5. one progression change with no cause/ledger evidence;
6. same command resolved twice producing random IDs;
7. semantically identical input resolved twice producing different result fingerprints;
8. concurrent-looking duplicate inputs produce independent UUIDs and later cannot be deduplicated;
9. retry after later transaction boundary appends the same committed ledger entry twice;
10. multiple stimuli accidentally collapse to one UID because identity excludes target/policy/version.

### MUST EXIST NOW IN PHASE 20

The following stable identities/evidence must exist in the Phase-20 contract now:

- `progressionUid` or equivalent stable semantic progression result/entry identity;
- deterministic `grantUid` for each proposed durable gain;
- deterministic `ledgerIntentUid` / progression ledger semantic identity;
- `sourceCommandUid`;
- stable source stimulus/effect/change identity (`stimulusUid`, `sourceChangeUid`, or equivalent);
- `campaignUid`;
- `characterUid` / typed subject ref;
- target kind UID + target UID (`statUid`, `skillUid`, `techniqueUid`, etc.);
- progression domain UID when used;
- pinned World Pack UID/version/binding identity;
- ProgressionEngine UID/version;
- progression policy/provider UID/version;
- deterministic input fingerprint;
- deterministic computation/result fingerprint;
- explicit progress semantics UID/version where progress units are not self-evident;
- optional/future-compatible `sourceEventUid` field/link, which may be null before event commit.

Identity generation must be deterministic from canonicalized semantic inputs; no `UUID.randomUUID()`, wall-clock time, iteration-order-dependent identity or hidden RNG.

### DEFER TO TRANSACTION / IDEMPOTENCY PHASE

The following must **not** be falsely implemented as complete in Phase 20:

- global committed-command registry;
- atomic dedupe + state/ledger commit;
- global unique-constraint semantics across all player ledgers;
- crash-safe `ALREADY_COMMITTED` behavior;
- TurnTransaction rollback;
- LAST VALID COMMIT recovery;
- cross-process concurrent commit serialization;
- authoritative event UID assignment at commit time if the event does not yet exist;
- retry-after-crash commit semantics.

Phase 20 only needs deterministic identities and proposal-level consistency so those later phases can enforce dedupe atomically.

## 10. Determinism / replay requirements

For identical canonical input, dependency versions and explicit entropy evidence, Phase 20 must produce identical:

- grant targets;
- grant units;
- grant UIDs;
- progression/ledger identities;
- causal links;
- computation trace/fingerprint;
- final proposal content relevant to progression.

Forbidden nondeterminism:

- `Random.Default` inside ProgressionEngine;
- implicit current time;
- random UUIDs;
- hash/set iteration order affecting results or identities;
- reading mutable repositories during the middle of pure calculation;
- reading current World Pack a second time after Phase-19 binding is pinned;
- floating-point factor chains whose rounding depends on evaluation order/platform.

If future stochastic progression is needed, the random result/seed must be explicit persisted/replayable evidence supplied from an upstream mechanic. Phase 20 should not invent hidden randomness.

## 11. Numeric / validation risk map

Current accepted data includes `Double` values for Talent/Potential and mastery/progress surfaces, while Phase-17 player changes use exact integer deltas (`ExactLongDelta`).

This boundary is the main numeric integrity risk for Phase 20.

### Required numeric policy

- reject NaN and ±Infinity on every Phase-20 numeric input;
- reject negative duration;
- reject zero duration when a source requires real elapsed effort; if a particular source legally allows zero duration, that must be explicit typed semantics, not default behavior;
- reject invalid/negative intensity and enforce a versioned legal range;
- reject fatigue outside the legal input range;
- reject invalid injury impact;
- reject negative effective training/gain where the operation is a durable gain;
- detect integer overflow with exact arithmetic;
- avoid lossy `Double -> Long` conversion without a versioned canonical fixed-point/rounding rule;
- normalize signed zero where doubles are accepted;
- define one canonical factor ordering / rounding point;
- do not implement full diminishing returns in Phase 20;
- zero final gain should not manufacture `ExactLongDelta(0)` because Phase 17 explicitly rejects zero deltas; a zero-result ledger/evidence record may still be valid if semantically useful.

### Absurd numeric overflow

Any input whose exact multiplication/addition exceeds the supported bounded integer/fixed-point representation must fail closed rather than wrap, clamp silently or produce Infinity.

## 12. WorldRuleProvider / Phase-19 regression boundary

Phase 19 is ACCEPTED / COMPLETE and is not reopened by this audit.

Phase 20 must preserve:

1. one coherent canonical World Pack authority observation per resolution;
2. one immutable pinned binding per resolution;
3. the same binding for PRECHECK and EFFECT_CHECK;
4. read-only WorldRuleProvider capability;
5. deterministic WorldRule request/decision identity;
6. fail-closed missing/stale/mismatched/cross-campaign authority;
7. zero authoritative mutation during resolution;
8. `PlayerChangeSet` proposal semantics.

### Correct Phase-20 integration order

Recommended integrity-preserving order:

`command validation`
-> `Phase-18 command reference validation`
-> `Phase-19 COMMAND_PRECHECK`
-> `base domain resolution`
-> `base-draft reference validation`
-> `extract immutable progression stimulus + authoritative read snapshot`
-> `pure ProgressionEngine`
-> `merge typed progression grants + progression ledger intents into immutable final draft`
-> `final reference/campaign closure over progression-generated targets`
-> `Phase-19 DRAFT_EFFECT_CHECK on the final augmented effects`
-> `engine-owned PlayerChangeSet proposal`
-> `Phase-17 validation`
-> later Phase-22 invariant validation
-> later TurnTransaction/COMMIT.

The critical requirement is that progression effects do not appear **after** the final WorldRule effect check. Otherwise progression could bypass Phase-19 legality.

## 13. Failure matrix

| Scenario | Expected behavior | Notes |
|---|---|---|
| Unknown character UID | **REJECT / NO MUTATION** | Do not create a new implicit player. |
| Character belongs to another campaign | **FAIL CLOSED / NO MUTATION** | No cross-campaign reads or grants. |
| Unknown progression domain | **REJECT / NO MUTATION** | Preserve unknown custom data if loading; do not calculate against nonexistent target. |
| Progression domain belongs to wrong World Pack | **FAIL CLOSED** | Must match pinned binding. |
| Unknown stat UID | **REJECT / NO MUTATION** | No name-based fallback. |
| Unknown skill UID | **REJECT / NO MUTATION** | Final progression-generated ref must be validated. |
| Unknown technique UID | **REJECT / NO MUTATION** | Same. |
| Deleted/missing World Pack definition | **FAIL CLOSED** | Do not infer from cached presentation/narrative. |
| Mismatched World Pack UID/version | **FAIL CLOSED** | Reuse Phase-19 pinned authority semantics. |
| Invalid Talent reference | **REJECT / NO MUTATION** | Wrong campaign/character/domain must fail. |
| Invalid Potential reference | **REJECT / NO MUTATION** | Same; Potential is not default grant. |
| Missing Talent/Potential entry where rule requires one | **REJECT or explicit versioned neutral-default rule** | Never silently fabricate value. |
| Malformed duration | **REJECT** | Typed parse/validation before calculation. |
| Negative duration | **REJECT** | No negative effort/time. |
| Zero duration | **REJECT unless source contract explicitly permits it** | Must not accidentally grant from zero work. |
| Invalid intensity | **REJECT** | Versioned legal range. |
| NaN | **REJECT** | Applies to all float-derived inputs. |
| +/−Infinity | **REJECT** | Applies to all float-derived inputs. |
| Numeric overflow | **FAIL CLOSED** | Exact overflow detection; no wrap/clamp. |
| fatigue outside legal range | **REJECT** | Do not silently normalize invalid current state. |
| invalid injury impact | **REJECT** | Negative/NaN/etc. according to typed contract. |
| Missing source/cause | **REJECT / NO PROGRESSION RESULT** | Every permanent gain requires cause. |
| Missing provenance identity | **REJECT** | Engine/provider/input identities required. |
| Duplicate `progressionUid` within one result | **FAIL CLOSED** | Structural identity collision. |
| Duplicate `ledgerIntentUid` within one proposal | **FAIL CLOSED** | No duplicate proposed append. |
| Same semantic request resolves twice | **IDENTICAL DETERMINISTIC PROPOSAL; COMMIT DEDUPE DEFERRED** | Phase 28 later enforces committed dedupe. |
| Same command UID with different semantic payload | **FAIL CLOSED identity conflict** | Never treat as a valid retry. |
| Concurrent-looking duplicate inputs | **same deterministic semantic identities; atomic dedupe deferred** | Enables later unique/commit enforcement. |
| WorldRuleProvider PRECHECK rejection | **REJECT / NO PROGRESSION CALCULATION THAT BECOMES PROPOSAL / NO MUTATION** | Prefer no progression engine invocation. |
| WorldRuleProvider EFFECT_CHECK rejection | **REJECT WHOLE RESOLUTION / NO AUTHORITATIVE PROGRESSION** | Any computed progression remains discarded proposal data. |
| Resolution component exception | **FAIL CLOSED / NO AUTHORITATIVE LEDGER / NO MUTATION** | Preserve Phase-18 structural failure semantics. |
| Progression component exception | **FAIL CLOSED / NO PARTIAL DRAFT ESCAPE** | Convert to structural failure at integration boundary, not partial proposal. |
| Partial progression draft construction | **DISCARD / NO MUTATION** | Immutable final result only. |
| Ledger entry without corresponding non-zero grant/change | **REJECT unless explicitly typed zero-result evidence** | Zero-result evidence must declare zero semantics. |
| Non-zero progression effect without cause/ledger intent | **REJECT** | Core Phase-20 invariant. |
| Grant target mismatches ledger target | **FAIL CLOSED** | Identity/causal consistency. |
| Migration interruption | **SAFE MIGRATION / PRESERVE DATA** | Additive transaction, rerunnable, no destructive remap. |
| Legacy mapping collision | **FAIL CLOSED / PRESERVE DATA** | Existing explicit mapping wins; no silent rewrite. |
| Custom World Pack progression data | **PRESERVE DATA** | Unknown to Core does not mean invalid storage. |
| Old campaign without Phase-20 table/rows | **SAFE LOAD** | Current authoritative state must remain valid; no forced synthetic history. |
| Old campaign has mastery but no historical progression cause | **PRESERVE CURRENT AUTHORITY; DO NOT FABRICATE LEDGER HISTORY** | “Unknown historical cause” is better than invented provenance. |
| CharacterPanelSnapshot has value differing from authoritative state | **IGNORE/REBUILD SNAPSHOT** | Presentation is not source of truth. |
| Narrative says “trained for a year” but no committed structured cause | **NO AUTHORITATIVE GAIN** | Narrative cannot be replay source. |
| Deleted cached progression summary | **REBUILD** | No data loss. |
| Identical input with different HashSet iteration order | **IDENTICAL RESULT** | Canonical sort required. |
| Hidden RNG call | **REJECT DESIGN / BLOCK MERGE** | Replay must not depend on unpersisted randomness. |
| Different engine/provider version | **DISTINCT computation identity** | Version is part of replay/provenance identity. |
| Player current value changes between snapshot and calculation | **REJECT stale input / version-precondition path** | Phase 20 must calculate from immutable snapshot; later transaction validates preconditions. |

## 14. Regression gates for accepted Phases 17–19

### Phase 17 — PlayerChangeSet

Must remain true:

- `PlayerChangeSet` is a proposal.
- engine-owned typed changes remain validated by the Phase-17 registry/validator.
- progression adds typed ledger intent/payload; it does not bypass the typed model with arbitrary JSON/map mutation.
- `ExactLongDelta` keeps non-zero exact semantics.
- stable change/changeSet/ledger identities remain deterministic.
- ledger `causalChangeUids` must refer to actual proposed changes where required.

### Phase 18 — PlayerDomainEngine orchestration

Must remain true:

- `resolve()` performs no authoritative write/commit;
- context campaign/actor mismatch still rejects;
- unknown/wrong-campaign references fail before they become accepted effects;
- component exceptions fail closed;
- component state/read capability remains controlled;
- Phase-20 integration must not expose SQLite/repository mutation handles to a progression component.

### Phase 19 — WorldRuleProvider

Must remain true:

- provider remains read-only;
- PRECHECK and EFFECT_CHECK retain accepted semantics;
- effect check sees the final progression-augmented effect set;
- same pinned World Pack binding is used throughout the resolution;
- no second World Pack authority read/fallback is introduced by ProgressionEngine;
- rejection creates no authoritative progression.

## 15. Required CHAT-1 tests

The implementation candidate should not be handed to coordinator acceptance without at least the following Phase-20-focused automated coverage.

### Determinism / identity

- identical canonical input -> identical grants, ledger intents, UIDs and fingerprints;
- input list/set iteration order does not affect output;
- same command UID + different payload/evidence -> identity conflict/fail closed;
- engine/provider/world-pack version changes are visible in computation identity;
- no runtime wall-clock/random UUID dependency.

### Duplicate / causal consistency

- duplicate progression UID in result rejected;
- duplicate ledger intent UID rejected;
- non-zero grant without ledger cause rejected;
- ledger cause targeting different character/target rejected;
- one-to-many and many-to-one mappings are explicit and validated rather than accidental duplication;
- zero-result semantics do not construct zero `ExactLongDelta`.

### Campaign isolation

- unknown character rejected;
- wrong-campaign character rejected;
- same character UID text in two campaigns does not cross-read;
- wrong-campaign Talent/Potential entry rejected;
- wrong World Pack domain rejected;
- current pinned World Pack binding required for world-specific progression.

### Reference validation

- unknown stat rejected;
- unknown skill rejected;
- unknown technique rejected;
- unknown progression domain rejected;
- progression-generated target that was not present in base command references is still final-reference validated;
- deleted/deprecated definition behavior is explicit and versioned.

### Numeric adversarial

- NaN/Infinity rejected;
- negative/zero duration cases;
- intensity bounds;
- fatigue/injury bounds;
- exact overflow near Long limits;
- deterministic conversion from accepted `Double` current/profile values to Phase-20 calculation scalar;
- rounding boundary test vectors locked to engine version.

### WorldRule / orchestration regression

- PRECHECK rejection -> ProgressionEngine not used or result discarded, no proposal progression;
- final EFFECT_CHECK rejection -> whole progression-augmented proposal rejected;
- final effect snapshot includes progression changes and progression ledger intent identity where relevant;
- same pinned World Pack binding used by progression policy and Phase-19 legality checks;
- ProgressionEngine/provider cannot mutate repositories/state during resolve;
- component/progression exception leaves no authoritative state.

### Migration / legacy

- old campaign -> current migration -> valid load with unchanged stats/mastery/profiles;
- old campaign with no Phase-20 rows -> valid load;
- legacy skill XP survives untouched;
- legacy technique XP survives untouched;
- `legacy_progression_evidence` survives untouched;
- explicit mapping remains stable after reopen;
- custom World Pack progression domain survives even if Core has no current policy for it;
- migration rerun is idempotent;
- migration interruption leaves old data recoverable;
- no synthetic historical progression ledger is fabricated for pre-Phase-20 mastery.

### Source-of-truth

- delete/rebuild CharacterPanelSnapshot -> progression state unchanged;
- no progression reads from narrative text;
- no independent ledger-total overwrite of current stat/mastery;
- no direct persistence from ProgressionEngine.

## 16. Required stable identities — minimum Phase-20 contract

Recommended required identity set:

1. `progressionUid` — stable semantic progression record/result identity;
2. `grantUid` — stable identity of each proposed durable target gain;
3. `ledgerIntentUid` — stable append-intent identity;
4. `sourceCommandUid`;
5. `stimulusUid` or equivalent stable source effect/change identity;
6. `campaignUid`;
7. typed `subjectRef` including `characterUid`;
8. target kind UID + target UID;
9. progression `domainUid` where applicable;
10. progress semantics UID/version;
11. World Pack UID/version + pinned binding fingerprint/identity;
12. ProgressionEngine UID/version;
13. Progression policy/provider UID/version;
14. canonical input fingerprint;
15. computation/result fingerprint;
16. `sourceEventUid` future-compatible link, nullable before committed event exists;
17. causal change UIDs linking ledger intent to proposed state effects.

Names, display labels and narrative descriptions must never be part of the primary identity key.

## 17. MUST EXIST NOW vs DEFERRED

### MUST EXIST NOW IN PHASE 20

- pure/deterministic progression calculation contract;
- immutable evaluation input/result;
- stable deterministic identities/fingerprints;
- explicit campaign/character/target/domain identity validation;
- World Pack pinned-binding compatibility;
- explicit source/cause/provenance for every non-zero durable grant;
- typed progression ledger **intent** integrated into `PlayerChangeSet` proposal envelope;
- final progression-generated reference closure before accepted proposal;
- final Phase-19 effect check over progression-augmented effects;
- no authoritative mutation from ProgressionEngine;
- no standalone authoritative progression DB/store;
- deterministic numeric conversion/rounding policy;
- legacy-preservation/migration tests;
- Phase-17/18/19 regression tests.

### DEFER TO PHASE 21

- full diminishing returns algorithm;
- novelty/adaptation long-horizon system;
- passive progression hooks;
- Time Skip progression orchestration.

Phase 20 may define generic extension/factor identities that Phase 21 can use later, but must not claim Phase-21 behavior.

### DEFER TO PHASE 22

- full no-retrogression/invariant engine;
- global permanent-regression enforcement across all mutation types.

Phase 20 must still avoid proposing nonsensical negative “gain” as normal progression and must preserve enough cause data for Phase 22.

### DEFER TO PHASE 23

- unified persisted player-ledger storage/integration;
- unified provenance query APIs;
- cross-ledger summaries/projections.

### DEFER TO PHASE 27+

- TurnTransaction;
- atomic state + event + ledger commit;
- rollback;
- committed idempotency / ALREADY_COMMITTED;
- global retry commit semantics;
- crash recovery / LAST VALID COMMIT.

## 18. Concrete blockers / stop conditions

### Current pre-implementation blockers found in baseline

**NONE that require repairing Phase 17–19 before CHAT-1 can begin Phase-20 implementation.**

The current baseline is green and Phase 19 remains accepted. The existing Phase-6/7/8/17/18/19 foundations provide a viable extension path.

### Mandatory implementation stop conditions

CHAT-1 must stop and return BLOCKED to the coordinator if any proposed implementation requires one of the following:

- direct authoritative state write from `ProgressionEngine` or a progression provider;
- standalone authoritative persisted progression store/ledger that bypasses PlayerChangeSet/TurnTransaction architecture;
- adding progression effects after the final Phase-19 EFFECT_CHECK;
- reading a second/unpinned World Pack authority during the same resolution;
- semantic migration that guesses legacy XP/mastery/talent/potential meaning;
- destructive remapping/deletion of custom/unknown World Pack data;
- random UUID/time/hidden RNG for progression identity;
- uncontrolled floating-point calculation without a deterministic versioned conversion/rounding boundary;
- inability to fail closed for stat/domain/character references generated by progression;
- need to modify Phase-21/22/23/27+ behavior to make Phase 20 “work”.

These are not requests for CHAT-3 to fix anything; they are coordinator/CHAT-1 gates.

## 19. Recommended forbidden scope for CHAT-1

For Phase-20 implementation, CHAT-1 should avoid:

- full diminishing returns/novelty/adaptation systems;
- passive progression scheduler/hooks;
- Time Skip Processor;
- full no-retrogression validator;
- unified persisted player-ledger architecture;
- TurnTransaction, rollback, LAST VALID COMMIT or global idempotency;
- event-store overhaul;
- authoritative ProgressionEngine DB writes;
- direct PlayerStat/Skill/Technique store writes from progression resolution;
- WorldRuleProvider redesign beyond the minimal existing integration needed to ensure final progression effects remain covered by accepted Phase-19 semantics;
- Naruto/Bleach-specific formula hardcoding in Core;
- cleanup of `Naruto_Default` bootstrap content;
- frontend redesign;
- unrelated migration/schema cleanup;
- synthetic backfilling of historical progression causes for old campaigns.

## 20. Recommended Phase-20 architecture boundary

The safest minimal Phase-20 boundary is:

```text
resolved legal stimulus + immutable authoritative snapshot
        |
        v
PURE ProgressionEngine
        |
        +--> deterministic ProgressionGrant(s)
        +--> deterministic progression ledger intent(s)
        +--> deterministic computation evidence/fingerprint
        |
        v
map grants to existing typed PlayerDomainChange payloads
        |
        v
final ref/campaign validation
        |
        v
Phase-19 final EFFECT_CHECK
        |
        v
PlayerChangeSet PROPOSAL
        |
        v
(no commit in Phase 20)
```

This keeps current stat/mastery state as current authoritative truth, ledger as causal evidence, and commit authority in the future transaction layer.

## 21. Final verdict

# READY FOR CHAT-1 IMPLEMENTATION

Technical meaning of this verdict:

- baseline/master match exactly;
- current CI is green;
- no new evidence reopens Phase 19;
- existing Talent/Potential, progression-domain, skill, technique, project and PlayerChangeSet foundations can support Phase 20 without destructive migration;
- no pre-existing blocker requires a repair outside Phase 20 before implementation starts.

This verdict is conditional on CHAT-1 treating the requirements in this audit as implementation gates, especially:

1. progression is pure/proposal-only;
2. no standalone persisted authoritative ledger in Phase 20;
3. deterministic stable identities now, global commit idempotency later;
4. every non-zero durable gain has explicit cause/provenance + ledger intent;
5. final progression effects remain inside Phase-18 reference closure and Phase-19 EFFECT_CHECK;
6. legacy data is preserved, not guessed/remapped;
7. numeric conversion from current Double-based accepted state is versioned and deterministic;
8. no Phase-21/22/23/27+ scope creep.

Phase 20 remains **NOT COMPLETE / NOT ACCEPTED**. Global acceptance remains a coordinator decision after implementation, independent audit, tests and exact-SHA CI evidence.
