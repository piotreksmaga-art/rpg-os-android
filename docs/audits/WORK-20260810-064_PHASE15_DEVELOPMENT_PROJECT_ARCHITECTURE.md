# WORK-20260810-064 — Phase 15 DevelopmentProject Architecture Audit

Status: READ-ONLY NEXT-PHASE ARCHITECTURE AUDIT

Work ID: `WORK-20260810-064`
Worker: `CHAT-4`
Role: `READ-ONLY NEXT-PHASE ARCHITECTURE AUDITOR`
Repository: `piotreksmaga-art/rpg-os-android`
Accepted Phase-13 runtime baseline: `be10d7f1b6bf0f6a2cd0522b1dac577d0f398790`
Fresh master observed before report write: `2756809dfd23442b3644d4ced9f8ad3d4d27b83a`
Phase-14 implementation owner: `WORK-20260810-061`
Allowed write scope: this report only.

This document is architecture/audit only. It does not implement Phase 14 or Phase 15, does not modify Kotlin runtime, SQLite runtime schema, migration routing, production code, implementation tests, MASTER, Roadmap, or Parallel Work Coordination.

---

# ROADMAP NEXT PHASE AFTER PHASE 14

## 15. DevelopmentProject model

The canonical Roadmap section `FAZA A — FUNDAMENT DANYCH I GRACZA` orders the relevant phases as:

```text
13. Financial Ledger / Economy model
14. Assets / debts / obligations / net-worth model
15. DevelopmentProject model
16. PlayerCommand contract
17. PlayerChangeSet contract
18. PlayerDomainEngine orchestration
```

Therefore the exact phase immediately following Phase 14 is:

```text
PHASE 15 — DevelopmentProject model
```

MASTER is consistent with this domain. Its DevelopmentProject contract states that a new technique/invention must not be granted arbitrarily by AI and that one shared DevelopmentProject mechanism covers technique creation/modification, skill development, research, crafting, body adaptation, energy control, infrastructure, and world research.

MASTER lifecycle:

```text
IDEA
-> REQUIREMENTS
-> PROTOTYPE
-> TRAINING/EXPERIMENTS
-> FAILURES/IMPROVEMENTS
-> MILESTONES
-> STABILIZATION
-> PROJECT COMPLETED
-> STABLE UID
-> NORMAL PROGRESSION
```

No Roadmap/MASTER ordering conflict was found.

Phase 15 implementation remains BLOCKED until Phase 14 is final and accepted.

---

# 1. Canonical domain boundary

DevelopmentProject is an authoritative process/history domain for creating, modifying, discovering, constructing, adapting, or stabilizing durable campaign capabilities and objects.

It is not the final authority for the produced thing.

Required semantic split:

```text
DevelopmentProject
!= Technique
!= Skill
!= Innate/Racial/Bloodline state
!= Inventory item
!= ItemInstance
!= Asset
!= OwnershipRecord
!= FinancialTransaction
!= progression ledger
!= mission/quest
!= story thread
!= free-form AI note
```

The project records the controlled development process. Completion may authorize or propose a final domain mutation, but the resulting technique/skill/item/asset/etc. must be created by its own authoritative domain store or later unified PlayerDomainEngine path.

Examples:

- developing a new jutsu -> project history + final TechniqueDefinition/PlayerTechnique mutation;
- modifying an existing technique -> project history + validated Technique-domain update/new variant UID;
- learning a difficult skill through an explicit project -> project history + Skill-domain progression/entry;
- crafting a unique weapon -> project history + Inventory/ItemInstance creation, and later ownership assignment if legal evidence exists;
- constructing a laboratory -> project history + Phase-14 Asset/Ownership integration once accepted;
- body adaptation/energy-control research -> project history + later appropriate Stat/Resource/Innate/Modifier domain effect, never direct arbitrary field write;
- world research -> project history may produce knowledge/truth proposals, but FACT/BELIEF/NARRATIVE semantics remain Phase-2/knowledge-domain authority.

A project can fail, pause, be abandoned, branch, be superseded, or complete without producing the originally intended result. Completion status alone must never imply that a Technique, Asset, Item, or permanent stat change already exists.

---

# 2. Source of truth classification

## AUTHORITATIVE

- project stable identity and owner/initiator scope;
- project type/domain target intent;
- lifecycle state transitions;
- project requirements and their immutable/versioned definitions;
- milestone definitions and achieved milestone records;
- attempt/experiment/training/crafting work records that materially advance or fail the project;
- project dependencies;
- explicit target references when modifying an existing entity;
- committed project outcome linkage to final domain objects;
- provenance, source event/command identifiers, campaign ordering/time;
- project operation idempotency identities.

## DERIVED

- percentage/progress score;
- current completion estimate;
- requirement satisfaction summary;
- readiness-to-stabilize status if mechanically derivable;
- remaining resource/cost estimate;
- aggregate success/failure counts;
- current project risk rating;
- latest status summary.

## CACHE / PRESENTATION

- ContextBuilder project slice;
- UI cards;
- CharacterPanel project summary;
- AI-facing prose summary;
- sorted “active projects” dashboard.

Derived/presentation deletion must not erase project history.

---

# 3. Repository/runtime baseline actually present

## 3.1 Accepted Phase 13

Accepted Phase 13 provides canonical financial identities and exact accounting primitives that Phase 15 must consume rather than duplicate:

```text
CurrencyDefinition
FinancialAccount
FinancialTransaction
FinancialStore
```

Money amounts are exact `Long` minor units; financial transactions have stable UID, optional command UID, source event UID, effective order, provenance, and append-only/reversal semantics. Phase 15 project costs therefore must reference committed finance transactions or future atomic cross-domain changes, not maintain an independent mutable `spent_money` authority.

## 3.2 Existing typed earlier player domains

The accepted runtime already has separate authoritative typed domains for:

- Stats/Resources;
- modifiers/resolution;
- Talent/Potential;
- Skills;
- Techniques;
- Innate/Racial/Bloodline/Evolution;
- Inventory/ItemInstance;
- Equipment;
- Ownership;
- Financial Ledger.

Phase 15 must integrate with these stores through stable references and final-domain operations, not duplicate their state inside project rows.

## 3.3 ContextBuilder

Accepted Phase 13 ContextBuilder currently exposes typed/reconciled Skills, Techniques, Inventory, finance-ledger projection and other campaign read models. It does not expose a canonical typed DevelopmentProject slice.

Phase 15 should add a bounded project read model only after the authoritative project domain exists. Presentation retrieval must not become project authority.

## 3.4 No accepted canonical DevelopmentProject runtime

At the observed fresh-master boundary, no accepted typed `DevelopmentProjectModel`, `DevelopmentProjectStore`, project migration, or repository API exists. No runtime filename indicates an accepted project authority.

Legacy campaign databases may still contain research/story/mission-like rows or textual notes. Their exact table/column shapes must be inspected at implementation time before any migration promotion. Absence of typed runtime usage is not proof that old DBs contain no project-like evidence.

---

# 4. Required prior foundations

Phase 15 depends on:

1. Phase 1 — campaign identity / unified repository.
2. Phase 2 — provenance and protected authoritative truth path.
3. Phase 3 — stable active-player identity and Persistent/Derived/Runtime separation.
4. Phases 4–5 — typed stat/resource definitions and derived resolver; project work must not directly rewrite effective values.
5. Phase 6 — Talent/Potential inputs may affect future project efficiency but must not be copied as authoritative project outcomes.
6. Phase 7 — Skill authority for skill-development outcomes.
7. Phase 8 — Technique authority for creation/modification outcomes.
8. Phase 9 — Innate/Racial/Bloodline/Evolution authority for body/evolution-related outcomes.
9. Phase 10 — Inventory/ItemDefinition/ItemInstance authority for crafting outcomes.
10. Phase 11 — SQLite write-boundary concurrency pattern.
11. Phase 12 — stable generic owner/asset references where projects target owned entities/assets.
12. Phase 13 — exact financial identities and ledger for project spending/rewards.
13. Phase 14 — final accepted Asset/Liability/Obligation contract for infrastructure, facilities, property, funded obligations, collateral, and net-worth interaction.

Hard blocker:

```text
No Phase-15 implementation until final accepted Phase-14 runtime is inspected.
```

Phase 15 must consume the actual accepted Phase-14 asset/obligation APIs and UIDs; this report must not freeze guessed class/table names from the preparatory Phase-14 architecture as runtime truth.

---

# 5. Generic Core domain model

Exact Kotlin/table names remain implementation decisions. The semantic contract should be equivalent to the following.

## 5.1 DevelopmentProject

```text
DevelopmentProject
- campaignId
- projectUid                    stable UID
- projectTypeUid                generic type definition UID
- initiatorPartyRef             stable validated party reference
- beneficiaryPartyRef?          optional if different from initiator
- title                         presentation label only
- objectiveSummary              human-readable intent
- targetDomainUid               e.g. TECHNIQUE / SKILL / CRAFTING / RESEARCH / BODY_ADAPTATION / ENERGY_CONTROL / INFRASTRUCTURE / WORLD_RESEARCH
- targetRef?                    optional validated existing target when modifying something
- intendedOutputKindUid?        what kind of authoritative output is expected
- createdOrder
- startedOrder?
- status                        explicit lifecycle state
- projectVersion >= 1
- sourceEventUid?
- provenance
- metadataJson?
```

`projectUid` is identity. Title/objective text is not identity.

## 5.2 ProjectTypeDefinition

Core should not hardcode Naruto/Bleach-specific project categories.

```text
ProjectTypeDefinition
- projectTypeUid
- genericCategory
- allowedTargetDomainUid(s)
- lifecyclePolicyUid
- worldPackUid / namespace?
- definitionStatus
- definitionVersion
- provenance
- metadata
```

Core categories can include semantic equivalents of:

```text
TECHNIQUE_CREATION
TECHNIQUE_MODIFICATION
SKILL_DEVELOPMENT
RESEARCH
CRAFTING
BODY_ADAPTATION
ENERGY_CONTROL
INFRASTRUCTURE
WORLD_RESEARCH
```

World Pack defines setting-specific rules, requirements, costs, legal methods, resources, risks, and outputs.

## 5.3 ProjectRequirement

Requirements must be explicit and typed rather than free-form “AI says ready”.

```text
ProjectRequirement
- campaignId
- requirementUid
- projectUid
- requirementTypeUid
- targetRef / definitionRef?
- comparator / threshold / quantity?
- requirementVersion
- requiredFromOrder
- retiredOrder?
- provenance
- metadata
```

Possible requirement types:

- stat/resource threshold;
- skill mastery;
- known technique prerequisite;
- item/material quantity;
- equipment/tool/facility availability;
- asset/facility ownership or access;
- monetary budget/funding evidence;
- mentor/party participation;
- prior project milestone;
- world/canon rule requirement.

Requirement evaluation is derived. Requirement definition/history is authoritative.

## 5.4 ProjectMilestoneDefinition and ProjectMilestoneAchievement

Do not collapse milestone definition and achievement into one mutable row.

```text
ProjectMilestoneDefinition
- milestoneUid
- projectUid
- sequence/orderKey
- milestoneTypeUid
- successCriteria
- required? 
- provenance

ProjectMilestoneAchievement
- achievementUid
- projectUid
- milestoneUid
- achievedOrder
- sourceWorkRecordUid?
- sourceEventUid?
- provenance
```

Achievement history is append-preserved. A milestone should not become “unachieved” by arbitrary update; if invalidated, use an explicit correction/revocation operation with provenance.

## 5.5 ProjectWorkRecord

The project needs an append-only operational history for meaningful attempts.

```text
ProjectWorkRecord
- workRecordUid
- campaignId
- projectUid
- workKindUid                  TRAINING / EXPERIMENT / DESIGN / CRAFT / TEST / ANALYSIS / CONSTRUCTION / OTHER
- actorPartyRef
- effectiveOrder
- duration / effort units?
- methodUid?
- environmentRef?
- consumedResourceRefs?
- resultKind                   SUCCESS / PARTIAL / FAILURE / BREAKTHROUGH / NO_PROGRESS / INCIDENT
- progressDeltaExact?          only if the chosen project model uses canonical additive progress units
- sourceEventUid?
- commandUid?
- provenance
- metadata
```

The record must preserve failures and non-progress attempts when they are material to continuity. History cannot be rewritten into a success-only summary.

## 5.6 ProjectProgress model

Avoid a universal arbitrary `%` unless the project contract defines a deterministic meaning.

Preferred options:

1. milestone-driven progress, derived from required milestones;
2. typed exact progress units per project definition;
3. hybrid: milestones + bounded exact internal progress units.

If numeric canonical progress exists:

- integer/fixed exact representation, not floating-point authority;
- monotonic only when the project type semantically requires monotonicity;
- setbacks should be explicit events/rules, not silent decrement;
- no overflow;
- no progress beyond completion scale.

## 5.7 ProjectDependency

```text
ProjectDependency
- dependencyUid
- projectUid
- dependsOnProjectUid
- dependencyTypeUid            REQUIRES_COMPLETION / REQUIRES_MILESTONE / INPUT / BLOCKER
- milestoneUid?
- validFromOrder
- validUntilOrder?
- provenance
```

Graph cycles that create impossible prerequisite loops must be rejected at authoritative boundary when the dependency type requires acyclic ordering.

## 5.8 ProjectOutcome

Completion must link to the actual final authoritative result.

```text
ProjectOutcome
- outcomeUid
- projectUid
- outcomeKindUid
- outputRef                    validated stable reference to resulting domain object/record
- committedOrder
- sourceEventUid?
- commandUid?
- provenance
```

Examples:

- technique creation -> `Technique` stable UID;
- technique modification -> new variant/revision reference according to Phase-8 contract;
- skill development -> existing/new Skill entry reference;
- crafted unique item -> ItemInstance UID;
- infrastructure -> accepted Phase-14 Asset UID;
- financial research result -> not a fake balance; use proper final domain result;
- world research -> knowledge/truth record reference if the target domain supports it.

A project cannot transition to `COMPLETED` with a required durable output unless the outcome reference is committed and valid in the same authoritative transaction boundary or an explicitly defined two-step `READY_TO_COMMIT -> COMPLETED` protocol guarantees no half-state.

---

# 6. Lifecycle

Recommended lifecycle state machine:

```text
IDEA
REQUIREMENTS
PROTOTYPE
ACTIVE_WORK
STABILIZATION
READY_TO_COMPLETE
COMPLETED
```

Terminal/non-happy-path states:

```text
PAUSED
ABANDONED
FAILED
SUPERSEDED
CANCELLED
```

Exact labels may differ, but transitions must be explicit and validated.

Required invariants:

- no transition directly from IDEA to COMPLETED unless a special project type explicitly defines a trivial legal lifecycle;
- required requirements satisfied before PROTOTYPE/ACTIVE_WORK as appropriate;
- required milestones achieved before READY_TO_COMPLETE;
- final domain output committed before/with COMPLETED;
- COMPLETED cannot return to ACTIVE by generic update;
- ABANDONED/FAILED/CANCELLED cannot silently become COMPLETED; reopening requires explicit clone/restart/superseding project semantics;
- SUPERSEDED requires successor project reference;
- timestamps/orders respect transition chronology;
- history of prior statuses remains reconstructable.

A mutable current `project_status` row may exist for efficient reads, but transitions must have append-preserved history or equivalent immutable operation records.

---

# 7. Commands / mutation surface

Phase 15 precedes Roadmap Phase 16 PlayerCommand, so it must not prematurely implement the global PlayerCommand framework.

It should nevertheless expose typed project-domain operations that can later be wrapped by PlayerCommand without semantic rewrite.

Suggested domain operations:

```text
createProject(...)
addRequirement(...)
retireRequirement(...)
addMilestone(...)
recordWork(...)
recordFailure(...)
recordBreakthrough(...)
achieveMilestone(...)
pauseProject(...)
resumeProject(...)
abandonProject(...)
markReadyToComplete(...)
commitOutcome(...)
supersedeProject(...)
```

Every mutation needs:

- campaign scope;
- stable operation/command UID where idempotency matters;
- effective order/time;
- provenance;
- expected project version for CAS-sensitive transitions where appropriate.

Generic StatePatch must not be the canonical project writer.

---

# 8. StatePatch policy

Once Phase 15 becomes authoritative:

```text
StatePatchEngine -> direct INSERT/UPDATE/DELETE canonical project tables
```

must be forbidden.

The project tables should be removed/excluded from generic writable surfaces, following the same architectural principle as CampaignTruth and later protected domains.

StatePatch may still propose high-level project effects through structured output in the future, but canonical mutation must go through typed project-domain validation.

Direct generic mutation risks include:

- skipping lifecycle states;
- fake completion;
- deleting failed experiments;
- arbitrary progress inflation;
- forging milestone achievements;
- creating output references to nonexistent techniques/assets/items;
- bypassing finance/material consumption;
- cross-campaign project mutation.

---

# 9. Stable identities and reference integrity

Every authoritative reference must resolve; arbitrary nonblank strings are insufficient.

Required stable identities include:

```text
projectUid
projectTypeUid
requirementUid
milestoneUid
achievementUid
workRecordUid
dependencyUid
outcomeUid
operationUid / commandUid
```

Reference categories:

- initiator/actor/beneficiary party -> reuse accepted generic party/owner registry or equivalent shared resolver;
- existing technique/skill/innate/stat/resource/item/equipment -> resolve through the actual accepted domain authority;
- asset/facility -> accepted Phase-14 authority after acceptance;
- financial account/transaction/currency -> accepted Phase-13 authority;
- project-to-project dependency -> campaign-scoped project FK/resolver;
- outputRef -> typed domain resolver, not arbitrary string;
- sourceEventUid -> optional until canonical Event Store exists, but if supplied must be nonblank and later resolvable under Event Store integration rules.

Do not build a parallel generic `entity_registry` unless coordination explicitly chooses one shared system for multiple domains.

---

# 10. Temporal semantics

Phase 15 must preserve temporal order before the later general Temporal Engine exists.

At minimum every meaningful operation needs a deterministic `effectiveOrder` compatible with existing campaign ordering conventions.

Required rules:

- project creation order <= first work order;
- milestone definition must exist before achievement unless migration evidence explicitly imports both with deterministic legacy order;
- outcome commit order >= all mandatory milestone achievements;
- completion order >= outcome order when separate;
- dependency validity intervals cannot be nonsensical;
- no backdated work that changes already-committed project state unless an explicit correction/import mode exists;
- reopen/restore preserves exact ordering.

Future Temporal Engine must be able to answer:

```text
What was the project status at order T?
Which requirements were active at T?
Which milestones had been achieved by T?
What failures/work attempts existed by T?
Did the final output already exist at T?
```

Therefore destructive overwrite-only history is not acceptable.

---

# 11. Cross-domain interactions

## 11.1 Skills and Techniques

DevelopmentProject can govern creation/modification process, but final Skill/Technique authority remains Phase 7/8.

Project work may reference current mastery, technique prerequisites, usage/testing results, but cannot directly mutate `baseMastery` or Technique definitions through generic project SQL.

## 11.2 Stats / Resources / Modifiers

Training/experiments may consume resources or produce validated permanent/temporary effects, but project progress is not a PlayerResource and must not be represented as one.

## 11.3 Talent / Potential

Talent/Potential may modify project efficiency, breakthrough probability, learning quality, or requirement thresholds under WorldRuleProvider. They are inputs, not project-owned copies.

## 11.4 Innate / Evolution

Body adaptation/evolution research must respect Phase-9 legal transitions. A completed project cannot arbitrarily grant a bloodline/evolution state.

## 11.5 Inventory / Equipment

Crafting/material consumption requires atomic inventory operations. A failed project attempt must not lose or duplicate materials unless rules explicitly commit that consumption.

Equipment state is not project state.

## 11.6 Ownership

Creating an ItemInstance or Asset does not automatically prove who legally owns it. If project completion legally grants title, OwnershipRecord must be committed as a separate explicit domain effect with valid evidence.

## 11.7 Finance

Project funding/spending must use Phase-13 ledger transactions.

Forbidden:

```text
project.costSpent += X
without FinancialTransaction
```

Project may store references to funding/payment transaction UIDs and derive spend totals.

## 11.8 Assets / obligations

Infrastructure projects may create/modify Phase-14 assets. Contracts/debts incurred for a project belong to Phase 14 obligations plus Phase-13 finance flows, not inside project free-text fields.

## 11.9 Truth/knowledge

Research can produce conclusions, but NARRATIVE hypothesis != FACT. Project outcome must route discoveries into FACT/BELIEF/knowledge authority according to evidence and perspective.

## 11.10 Missions/story threads

Mission/quest/story thread may motivate or require a project but cannot serve as project authority. Same title or objective text is not identity equivalence.

---

# 12. Migration and legacy policy

Phase 15 migration must be additive and conservative.

Before implementation, inspect exact legacy schemas and representative old campaign rows for project-like evidence such as:

- research notes/tables;
- crafting/construction records;
- technique-development notes;
- story threads named as projects;
- arbitrary status text;
- CharacterPanel/project summaries;
- mission objectives that resemble research;
- historical narrative references.

Rules:

1. Never synthesize canonical DevelopmentProject solely from prose or a same-name match.
2. Never infer milestones from chapter summaries unless the source has explicit structured milestone semantics.
3. Never infer project completion because a Technique/Item/Asset currently exists.
4. Never infer project ownership/initiator from current item/asset owner.
5. Never fabricate failed experiments to make a narrative sequence look complete.
6. Never backfill detailed work history from a single aggregate progress value.
7. Do not turn mission/story-thread status into project lifecycle automatically.
8. Preserve unresolved legacy evidence when mapping is ambiguous.
9. If a legacy project-like row has stable identity, type, owner, lifecycle, and enough structured data for safe promotion, import with explicit migration provenance.
10. Migration rerun must be idempotent.
11. No destructive migration of Phase 3–14 data.
12. Phase 15 migration must chain through final accepted Phase 14 migration, not preparatory assumptions.

A migration mapping table may be useful:

```text
legacy_project_mappings
- campaign_id
- legacy_evidence_uid
- project_uid
- mapping_version
- provenance
```

but only for records actually promoted.

---

# 13. Persistence / schema requirements

Recommended physical separation:

```text
project_type_definitions

development_projects
project_requirements
project_milestone_definitions
project_milestone_achievements
project_work_records
project_dependencies
project_outcomes
project_operations / idempotency
legacy_project_evidence / mappings (if needed)
```

Critical indexes:

- `(campaign_id, project_uid)` primary identity;
- `(campaign_id, initiator_kind_uid, initiator_uid, status)` active-project lookup;
- `(campaign_id, status, created_order)`;
- `(campaign_id, project_uid, effective_order)` work/history;
- `(campaign_id, project_uid, milestone_uid)`;
- `(campaign_id, depends_on_project_uid)`;
- output reverse lookup by typed `output_kind_uid + output_uid`;
- command/operation idempotency key;
- source event lookup if used.

Authoritative full-history readers must not use hidden `LIMIT`. Presentation readers must be bounded separately.

---

# 14. SQLite authoritative invariants

Lessons from Phase 11–13 require race-sensitive invariants at write boundary, not only Kotlin prechecks.

SQLite/transaction-authoritative constraints should enforce where representable:

- project UID uniqueness per campaign;
- requirement/milestone/work/outcome UID uniqueness;
- valid lifecycle state values;
- legal transition version/CAS;
- no destructive deletion of committed project history;
- immutable work/milestone achievement/outcome payload after commit;
- valid project FK on all child rows;
- project dependency same campaign;
- no self-dependency;
- cycle prevention for hard prerequisite dependencies via transaction-authoritative check;
- unique milestone achievement when milestone can be achieved once;
- output reference uniqueness where one durable output may not be claimed as creation result by multiple mutually exclusive projects;
- terminal project cannot accept new normal work records;
- COMPLETED requires committed outcome for output-producing project types;
- operation UID / command UID idempotency;
- timeline monotonicity/non-backdating where contract requires it.

Cross-domain target resolution may require triggers or transaction-scoped resolver checks when direct FK is impossible.

---

# 15. Concurrency / TOCTOU race matrix

The race matrix must exist before implementation.

## R1 — double work submission

```text
T1 records work command C
T2 records same work command C
```

Required: one committed work record/effect; second exact retry idempotent, conflicting reuse fails.

## R2 — milestone double achievement

Two writers both observe milestone unachieved and insert achievement.

Required: one authoritative achievement for single-achievement milestone.

## R3 — completion vs additional work

T1 decides project is ready and completes it while T2 concurrently records another experiment.

Required: serialized boundary. Either work commits before completion and is included, or completion wins and later work is rejected.

## R4 — completion vs abandonment/failure

Only one terminal transition can win from the same project version.

## R5 — dependency completion race

Project A depends on B. A tries to advance while B is concurrently completed/rolled back.

Required: dependency satisfaction checked inside transaction against committed state.

## R6 — finance/material double spend

Two project attempts try to consume the same account balance/material quantity.

Required: rely on Phase-13/Inventory authoritative guards inside one atomic cross-domain write boundary; Kotlin pre-read is insufficient.

## R7 — output creation collision

Two projects attempt to create the same stable Technique/Item/Asset UID.

Required: target domain UID uniqueness rejects one; losing project must not be marked COMPLETED.

## R8 — target modification race

Two projects modify the same existing Technique/Asset against the same base version.

Required: expected-version/CAS or explicit branch/variant semantics. Silent last-write-wins forbidden.

## R9 — project deletion/retirement vs historical reference

Deletion must be forbidden once authoritative child history or external references exist.

## R10 — project type/definition retirement vs new project

A retired definition cannot be used for a new project, while existing project history remains interpretable.

## R11 — same dependency edge concurrent insert

Duplicate edge prevented; incompatible cycles prevented transactionally.

## R12 — restore/migration vs active project write

Production architecture must not run migration/restore concurrently with live writes to the same campaign DB.

---

# 16. Failure semantics

Typed operations should fail closed and diagnostically.

Recommended error classes/outcomes:

- INVALID_PROJECT_REFERENCE;
- INVALID_PROJECT_STATE;
- ILLEGAL_TRANSITION;
- REQUIREMENT_NOT_MET;
- DEPENDENCY_NOT_MET;
- MILESTONE_NOT_MET;
- OUTPUT_REFERENCE_INVALID;
- OUTPUT_ALREADY_EXISTS;
- TARGET_VERSION_CONFLICT;
- INSUFFICIENT_FUNDS;
- INSUFFICIENT_MATERIALS;
- WORLD_RULE_REJECTED;
- ALREADY_COMMITTED;
- IDEMPOTENCY_CONFLICT;
- CAMPAIGN_SCOPE_MISMATCH;
- TEMPORAL_ORDER_VIOLATION;
- PROJECT_TERMINAL;
- MIGRATION_EVIDENCE_AMBIGUOUS.

No partial side effects after failure.

---

# 17. Reopen / restore / campaign isolation

Required persistence behavior:

```text
write project history
-> close DB
-> reopen
-> CurrentSchema.ensure
-> exact authoritative equality
```

Preserve:

- project identity/type/party refs;
- lifecycle state and transition history;
- requirements;
- milestones/achievements;
- work/failure history;
- dependency graph;
- outcome references;
- command/operation idempotency;
- provenance;
- temporal order.

Backup/restore must preserve all authoritative history exactly and must not regenerate work records, achievements, outputs, or migration mappings.

Campaign switch A -> B -> A must preserve independent project spaces even when projects deliberately reuse the same UID strings across campaigns.

No repository/store cache may remain bound to the prior active campaign.

---

# 18. Scale requirements

Long campaigns can accumulate many projects and very large work histories.

Required scale properties:

- append-oriented work/milestone/outcome history;
- keyset pagination by effective order/UID;
- active-project reads indexed and bounded for ContextBuilder;
- no full history scan every GM turn;
- no authoritative `LIMIT 1000` completeness bug;
- project history >1000 records must survive reopen/restore;
- many completed/abandoned projects must not degrade active-project lookup;
- dependency graph traversal must be bounded and cycle-safe;
- derived progress summaries may be cached/rebuilt but never replace history;
- migration must stream/batch large legacy evidence sets;
- backup/restore retains idempotency keys and operation history.

Suggested repository reads:

```text
project(projectUid)
activeProjectsByParty(partyRef, cursor, limit)
projectHistory(projectUid, cursor, limit)
requirements(projectUid)
milestones(projectUid)
dependencies(projectUid)
outcomes(projectUid)
projectsByOutput(outputRef)
projectStateAsOf(projectUid, effectiveOrder)
```

---

# 19. ContextBuilder / presentation contract

After authoritative Phase 15 exists, ContextBuilder should expose a bounded typed project slice for the active player/visible party context.

Suggested GM_CONTEXT fields:

- stable project UID;
- title/objective;
- type/category;
- current lifecycle state;
- unresolved required requirements;
- next milestone(s);
- recent meaningful work/failure records;
- target/output references;
- blocked reason/dependency;
- concise provenance/source summary where relevant.

Do not send entire laboratory/work history every turn. Full history remains queryable through repository retrieval.

ContextBuilder data is presentation only. AI must not change project progress by editing the context snapshot.

---

# 20. World-Pack boundary

Core owns:

- project lifecycle infrastructure;
- stable IDs;
- persistence/history;
- requirement/milestone/work/outcome contracts;
- reference integrity;
- idempotency/concurrency;
- project dependency graph;
- failure/rollback semantics;
- generic progress/milestone machinery.

World Pack owns:

- setting-specific project types;
- canon legality;
- required stats/skills/techniques/resources/materials;
- chakra/reishi/etc. mechanics;
- risk formulas;
- experiment/training methods;
- difficulty/quality rules;
- valid outputs and transformations;
- world-specific breakthrough conditions.

Forbidden Core hardcodes include Naruto-specific jutsu ranks, chakra nature logic, Bleach evolution terminology, villages, shinobi ranks, reiatsu mechanics, or setting-specific laboratory rules.

---

# 21. Semantic release gates

Phase 15 semantic acceptance requires at least:

## Domain separation

- project process != final Technique/Skill/Item/Asset authority;
- mission/story thread != project;
- financial spend != project progress;
- project completion != automatic ownership;
- project completion != automatic FACT.

## Lifecycle

- legal state transitions only;
- terminal state cannot silently reopen;
- required milestones/requirements gate completion;
- failure history remains visible;
- output-producing completion has valid committed output.

## Identity

- stable project/work/milestone/outcome UIDs;
- same labels do not merge records;
- cross-campaign isolation.

## World Pack

- generic Core can represent Naruto/Bleach/custom projects without hardcoding either;
- world-specific rules supplied externally.

## Progress

- no arbitrary AI-authored percentage authority;
- project progress mechanically derives from canonical work/milestones according to type policy.

---

# 22. Migration / integrity release gates

Mandatory:

- Phase 14 -> Phase 15 production migration route;
- full Phase 3 -> ... -> Phase 15 chain;
- clean bootstrap;
- upgrade existing campaign;
- reopen;
- repeated ensure;
- migration idempotency;
- backup/restore;
- campaign switch A -> B -> A;
- preservation of Phase 3–14 data;
- no destructive migration;
- no invented project from ambiguous legacy evidence;
- >1000 project-history records complete after reopen/restore;
- `PRAGMA integrity_check = ok`;
- `PRAGMA foreign_key_check` clean for represented FKs;
- explicit resolver validation tests for generic references not representable as direct FK.

---

# 23. Adversarial release gates

Required attacks/tests:

1. fake COMPLETED insert via generic StatePatch/SQL path;
2. nonexistent target Technique/Asset/Item UID;
3. wrong-campaign output reference;
4. duplicate command/work UID with changed payload;
5. concurrent double milestone achievement;
6. concurrent completion vs failure/abandonment;
7. concurrent completion vs new work;
8. two projects creating same output UID;
9. cyclic hard project dependencies;
10. self-dependency;
11. backdated work after terminal completion;
12. delete/modify committed failure history;
13. project marked complete while final domain write fails;
14. final domain write succeeds while project completion fails — must roll back or use explicit recoverable two-step protocol;
15. material double-consumption race;
16. financial double-spend race;
17. retired project type used for new project;
18. same-name legacy rows incorrectly merged;
19. project progress overflow/invalid negative units where numeric progress exists;
20. ContextBuilder bounded read accidentally used as authoritative complete history.

---

# 24. Regression requirements

Phase 15 must not regress accepted prior domains.

Verify at minimum:

- Phase 3 active player identity;
- Phase 4 Stat/Resource authority;
- Phase 5 derived modifiers/resolution;
- Phase 6 Talent/Potential;
- Phase 7 Skill history/mastery;
- Phase 8 Technique identity/requirements/history;
- Phase 9 Innate/Racial/Evolution transitions;
- Phase 10 Inventory/ItemInstance identities and quantities;
- Phase 11 Equipment/loadouts;
- Phase 12 Ownership/reference registries;
- Phase 13 financial conservation/idempotency/balance projections;
- Phase 14 accepted Asset/Liability/Obligation semantics once available.

Particularly enforce:

```text
project progress
!= skill mastery
!= technique mastery
!= financial balance
!= inventory quantity
!= ownership share
!= asset value
```

---

# 25. Recommended implementation order after Phase 14 acceptance

This is preparatory architecture only, not an implementation instruction to execute now.

1. Re-read fresh master and final accepted Phase-14 runtime/API/schema.
2. Exact legacy schema/data audit for project/research/crafting-like evidence.
3. Freeze generic project type/lifecycle/reference contracts.
4. Add additive Phase-15 migration chained from accepted Phase 14.
5. Add typed ProjectStore/Repository API.
6. Add append-preserved lifecycle/work/milestone/outcome history.
7. Add SQLite write-boundary transition/idempotency/concurrency guards.
8. Add final-domain outcome resolver contract without implementing PlayerCommand/PlayerDomainEngine prematurely.
9. Protect canonical project tables from generic StatePatch.
10. Add bounded typed ContextBuilder projection.
11. Add migration/integrity/semantic/adversarial/concurrency/scale tests.
12. Only after final acceptance proceed to Roadmap Phase 16 PlayerCommand contract.

---

# 26. Definition of Done

Phase 15 is implementation-complete only when all are true:

- canonical DevelopmentProject domain exists and is generic;
- stable identities are persisted;
- project lifecycle/history is authoritative and append-preserved;
- requirements/milestones/work/outcomes are typed and validated;
- arbitrary AI/StatePatch project mutation is blocked;
- output references resolve to real target-domain records;
- final-domain creation/update and project completion cannot half-commit;
- migration is additive, idempotent and legacy-safe;
- reopen/restore/campaign isolation pass;
- >1000-history scale completeness passes;
- race matrix passes at SQLite/transaction boundary;
- all accepted Phase 3–14 regressions pass;
- semantic, integrity and adversarial validators issue PASS;
- exact CI for the accepted runtime is green.

---

# FINAL ARCHITECTURE VERDICT

The exact next Roadmap phase after Phase 14 is:

```text
PHASE 15 — DevelopmentProject model
```

Its essential architectural contract is:

```text
DevelopmentProject = authoritative, append-preserved process/history

Final Technique / Skill / Item / Asset / Knowledge / Player state
= remains authoritative in its own domain

PROJECT COMPLETED
= only after valid requirements/milestones and durable output commit

AI narrative / StatePatch / text notes
!= project authority
```

The most important implementation hazards are:

1. conflating project progress with final domain state;
2. allowing generic StatePatch to forge completion/progress;
3. half-committing completion and output creation;
4. relying on Kotlin prechecks for concurrent milestones/completion/material/finance races;
5. inventing project history during migration from prose/aggregate legacy evidence;
6. duplicating Phase-14 asset or Phase-13 finance identities instead of consuming accepted contracts.

**NEXT-PHASE ARCHITECTURE READY — PHASE 15 IMPLEMENTATION BLOCKED BY FINAL PHASE 14 ACCEPTANCE**
