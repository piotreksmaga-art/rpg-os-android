# RPG OS — Phase 39–47 Entry Audit

Status: **AUDIT COMPLETE / IMPLEMENTATION NOT STARTED**

Work ID: `WORK-20260825-001`

Audit date: `2026-08-25`

Baseline branch: `master`

Baseline commit: `a5d5fab0457d70ef6ba36213b4067cfd327be82c`

Latest exact-SHA CI: `Validate RPG OS ALPHA`, run `32778253485`, **SUCCESS**.

Scope: read-only audit runtime, persistence, tests and architecture for the prepared single execution block Phase 39–47. No production Kotlin, schema or test implementation was changed by this audit.

## 1. Executive result

The block is feasible, but implementation must begin with Gate A and must not build all nine phases simultaneously.

Current classification:

| Phase | Classification | Main conclusion |
|---|---|---|
| 39 Temporal Engine | **PARTIAL** | Strong event/order/replay and several domain-specific temporal primitives exist; there is no universal historical truth/query engine. |
| 40 Scheduler | **PARTIAL** | Legacy schedule/deadline state exists, but no canonical evaluation-point contract or execution lifecycle exists. |
| 41 Structured SQL Retriever | **PARTIAL** | Many bounded domain reads and raw query helpers exist; there is no typed, plan-driven structured retrieval boundary. |
| 42 Knowledge/Causal Graph Retrieval | **PARTIAL** | Canonical causal and knowledge graphs exist; bounded traversal/search APIs do not. |
| 43 Intent Parser | **MISSING** | Typed `PlayerCommand` exists downstream, but no player-text normalization/ambiguity contract exists. |
| 44 Turn Planner | **MISSING** | No typed bounded plan selecting repositories/mechanics/output capabilities exists. |
| 45 Context Builder | **PARTIAL / OBSOLETE SHAPE** | A protected Phase-38-aware builder exists, but it is a fixed eager bundle with raw SQL/maps rather than a plan-driven typed candidate. |
| 46 Context Budget Manager | **MISSING** | Fixed per-query limits exist; no dynamic model/capability/workload-aware budget manager exists. |
| 47 Iterative Retrieval | **MISSING** | No missing-context model, bounded iteration state or termination contract exists. |

The most important architectural conclusion is:

> Reuse the accepted transactional, event, causal, knowledge and visibility foundations. Replace neither Phase 1–38 nor the existing ContextBuilder wholesale. Add the smallest missing read/orchestration contracts in dependency order.

## 2. Baseline and repository state

- Branch: `master`.
- Exact HEAD: `a5d5fab0457d70ef6ba36213b4067cfd327be82c` (`feat(p38): accept universal visibility boundary`).
- Exact-SHA CI is green.
- The working tree already contains the user-requested documentation preparation:
  - `docs/Adapter-prototyp.mb`;
  - `docs/PHASE_39_47_EXECUTION_BLOCK.md`;
  - roadmap and file-map annotations.
- No runtime implementation was modified during this audit.

The current code is authoritative over older architectural descriptions. Current Phase numbering follows `docs/Roadmap.md`; older `docs/GM_ENGINE_TARGET_ARCHITECTURE.md` numbering for parts of 41–47 is treated as historical requirement text only.

## 3. Reusable accepted foundations

### 3.1 Transactional order and replay

Reusable:

- `TurnTransaction` and `TurnTransactionReceiptStore` provide a canonical committed order and idempotent receipt boundary.
- `CampaignEventStore` is append-only, campaign-qualified and binds events to transaction/turn/command/change evidence.
- `CampaignCausalGraph` stores typed causal/provenance/evidence/temporal/narrative/derived/retrieval relations.
- `CommittedReplayPayloadStore` and `CampaignSnapshotManager` provide replay/snapshot material.

Important limitation:

`CanonicalGameplayEventRecord` is an integrity/evidence record, not a full historical state delta. It stores identities, kinds, order and fingerprints but does not expose complete domain payload values. Phase 39 cannot reconstruct every historical state from `eventsForTransaction()` alone. It must use accepted domain history, committed replay material or an explicitly supported projection.

### 3.2 Temporal-ready domain data

Existing temporal primitives include:

- ownership history with `valid_from_order` / `valid_until_order` and time indexes;
- asset valuations, obligations and settlements with effective/due/valid orders;
- modifiers with `validFrom` / `validUntil`;
- canon divergence `effectiveFrom` / `effectiveUntil`;
- Phase 37 acquisition `createdOrder`, state `updatedOrder` and source observation order;
- Phase 38 access authority `validFromOrder`, `validUntilOrder`, `createdOrder` and an `effective(principal, atOrder)` resolver;
- canonical event and causal relation committed order;
- transaction receipts, snapshots and replay payloads.

These are useful sources, but their field names and semantics are heterogeneous. There is no global temporal registry defining which source can answer which historical question.

### 3.3 Knowledge and visibility

Reusable:

- Phase 37 provides typed holders, claims, acquisitions, evidence, epistemic states, quality, provenance and current holder projections.
- Phase 38 provides `AudienceContext`, `PurposeContext`, typed disclosure states and protected reads.
- `ProtectedCampaignReadRepository` distinguishes allow, deny, no data, not disclosed, unknown and corruption.
- `VisibilityConsumerInventory` is a fail-closed registry for protected consumers.

Important limitations:

- `KnowledgeContextProjection.forHolders()` returns current state joined to the latest acquisition; it is not an as-of query.
- `CampaignTruthStore.active()` returns current active truth only.
- `ProtectedCampaignReadRepository.truthFiltered()` and `truthContextRows()` are present-state reads without a temporal scope.
- Phase 38 access authority can resolve `atOrder`, but protected truth, player-state and knowledge projections do not yet share one historical query envelope.

### 3.4 Player command foundation

`PlayerCommand` already provides:

- campaign and actor identity;
- typed command kinds and payloads;
- provenance, causation and correlation;
- requested effective order;
- preconditions and typed extensions;
- strict serialized-command validation in the existing command codec/scanner.

It is a downstream executable command contract. It is not a natural-language intent parser and must not be reused as if parsing and command authorization were the same step.

### 3.5 Existing context foundation

`ContextBuilder` and `ContextBundle` already:

- require audience and purpose;
- bind reads to the active campaign;
- use Phase 38 protected reads for player state, campaign truth and diagnostics;
- project knowledge for trusted cognition holders;
- carry a `VisibilityProjectionEnvelope`;
- support real payload reduction without relabelling hidden bytes;
- build player/domain/context information from existing stores.

They are reusable as migration targets and regression fixtures, not as the final Phase 45 shape.

## 4. Phase 39 — Temporal Engine

Classification: **PARTIAL**.

### Existing

- Canonical committed order from Turn Transaction receipts.
- Append-only event and causal evidence.
- Replay payload and snapshots.
- Domain-specific temporal histories.
- Phase 38 access resolution at an explicit order.
- Phase 37 temporal-ready acquisition/evidence metadata.

### Missing

- one typed `TemporalQuery`/`TemporalResult` contract;
- a canonical time coordinate policy: commit order versus campaign day/chapter/wall-clock metadata;
- source capability registry for supported historical queries;
- historical Campaign Truth resolution;
- historical KnowledgeState projection;
- historical protected projection combining Phase 38 audience/purpose with `atOrder`;
- typed unsupported/unknown/corruption behavior per source;
- cross-domain historical query conformance tests.

### Risks

- treating `active=1` current truth as historical truth;
- using event fingerprints as domain payload history;
- fabricating missing validity start/end for legacy data;
- mixing campaign day, chapter and canonical commit order;
- returning current holder knowledge for a past-time question;
- resolving current access for a historical disclosure question.

### Adapter decision

**MINIMAL ADAPTER** — justified only as a set of read-only temporal source ports over heterogeneous accepted owners.

This is not approval for a global Phase 38.5 platform. Each port must have a concrete Phase 39 query and preserve owner semantics. Phase 39 owns temporal interpretation; source ports only expose accepted history mechanically.

Recommended first contract:

```text
TemporalCoordinate = CanonicalCommitOrder
TemporalQuery(campaignId, subject, atOrder, audience, purpose)
TemporalResult = VALUE | NO_DATA | DENIED | NOT_DISCLOSED | UNKNOWN | UNSUPPORTED | CORRUPTION
```

Campaign day/chapter should be related through explicit committed evidence, not treated as interchangeable numbers.

## 5. Phase 40 — Scheduler

Classification: **PARTIAL**.

### Existing

- legacy persistent families: `npc_schedules`, `time_skip_training_plans`, `timeline_event_dependencies`, `timeline_event_influences`;
- mission `deadline_day`;
- `future_world_pressure` start/peak fields;
- obligations/projects/travel-like domain fields that can become evaluation sources;
- transaction and idempotency infrastructure required for safe processing.

### Missing

- canonical scheduled-evaluation identity and schema;
- lifecycle such as pending/due/claimed/processed/cancelled/failed;
- idempotent claim and completion semantics;
- explicit evaluator kind and typed payload/reference;
- canonical mapping from campaign time to due evaluations;
- authorization proving that Scheduler may request evaluation but cannot bypass domain engines;
- retry/crash/rollback/replay/branch tests;
- distinction between a deadline/evaluation and a predetermined result in persistence.

### Risks

- converting `future_world_pressure` or NPC plans into guaranteed future facts;
- direct background writes outside `TurnTransaction`;
- duplicate execution after crash/retry;
- scanning every schedule record every turn;
- importing Phase 60 Time Skip orchestration prematurely.

### Adapter decision

**OWNER FIX**.

The new Scheduler should own one canonical evaluation-point contract. Legacy schedule tables are migration/compatibility inputs to that owner, not justification for a cross-project adapter. Unsupported legacy rows remain explicit and cannot become canonical scheduled outcomes automatically.

## 6. Phase 41 — Structured SQL Retriever

Classification: **PARTIAL**.

### Existing

- many domain stores already provide campaign-qualified bounded reads;
- several domain-specific historical reads exist;
- `ProtectedCampaignReadRepository` is the trusted Phase 38 gateway;
- ContextBuilder contains bounded parameterized queries;
- indexes exist for important temporal/event/causal paths.

### Missing

- typed retrieval request/result contract;
- allowlisted query/provider registry;
- deterministic global ordering/cursor rules;
- query cost/row/depth limits as contract data;
- explicit temporal delegation to Phase 39;
- typed Phase 38 projection per protected result;
- provenance/source descriptors;
- conformance test preventing prompt/model-generated SQL;
- instrumentation for bounded-query evidence.

### Risks

- turning a retriever into a generic raw SQL gateway;
- returning `Map<String, Any?>` with unstable semantics;
- applying `LIMIT` without deterministic ordering or completeness state;
- bypassing owner stores and Phase 38;
- confusing presentation limits with authoritative repository limits.

### Adapter decision

**OWNER FIX**.

Prefer small typed read operations added to the owning repository or a retriever provider implemented by that owner. A generic compatibility adapter would enlarge the protected surface and encourage raw legacy access.

## 7. Phase 42 — Knowledge Graph / causal retrieval

Classification: **PARTIAL**.

### Existing

- canonical typed causal relation records;
- append-only campaign-bound event endpoints;
- relation-class validation and strong-cause evidence requirements;
- cycle prevention for dependency relations;
- typed Knowledge claims/acquisitions/evidence/state;
- holder-scoped current knowledge projection;
- tests for cross-campaign edges, cycles, evidence and narrative-not-cause separation.

### Missing

- bounded graph traversal API;
- direction, relation-class/kind, maximum depth/node/edge limits;
- deterministic ordering and cursor;
- path/provenance result model;
- temporal filtering through Phase 39;
- Phase 38 audience-safe protected graph projection;
- joint query semantics that preserve FACT/KNOWLEDGE/BELIEF distinctions;
- tests for wide/deep graph exhaustion and safe partial results.

### Risks

- treating narrative association as causation;
- unbounded traversal;
- mixing current knowledge state with historical acquisition;
- returning hidden nodes because one visible endpoint exists;
- creating new inferred canonical facts during retrieval.

### Adapter decision

**OWNER FIX**.

Add bounded read/traversal capabilities to the accepted `CampaignCausalGraph` and Phase 37 projection owners. Do not copy graph data into a compatibility subsystem.

## 8. Phase 43 — Intent Parser

Classification: **MISSING**, with reusable downstream command contracts.

### Existing

- typed `PlayerCommand` and payload families;
- strict JSON structural checks;
- actor/campaign/provenance/precondition contracts;
- Player Domain components and reference validation.

### Missing

- `NormalizedIntent` independent of `PlayerCommand`;
- player text/input normalization;
- explicit actor/action/target/method/time/entity mentions;
- ambiguity, unsupported and clarification states;
- deterministic rule-based baseline;
- guarantee that omitted player choices are not invented;
- mapping from an accepted intent to candidate command construction without commit.

### Required boundary

`PLAYER INPUT != NORMALIZED INTENT != PLAYER COMMAND != COMMIT`.

No adapter decision is required. Phase 43 should consume public command vocabulary and identity contracts without reading historical storage.

## 9. Phase 44 — Turn Planner

Classification: **MISSING**.

### Existing

- typed command/domain references;
- registered Player Domain components;
- repository APIs and snapshot profiles;
- future retrieval sources from Gate B.

### Missing

- typed `TurnPlan`;
- capability registry for retrievers/mechanics/context/output needs;
- bounded selection rules;
- dependency and ordering constraints;
- explicit ambiguity/unsupported propagation;
- stable plan fingerprint and deterministic test oracle;
- proof that plan construction performs no read/mutation/mechanics execution.

No adapter decision is required. The planner should depend on stable capabilities exposed by Gate A–B, not on legacy tables.

## 10. Phase 45 — Context Builder

Classification: **PARTIAL / OBSOLETE SHAPE**.

### Existing

- production `ContextBuilder` and `ContextBundle`;
- Phase 38 audience/purpose/envelope integration;
- active-campaign binding;
- protected player/truth/diagnostic reads;
- knowledge projection and disclosure reduction;
- regression tests for canonical domains, read-only behavior and visibility.

### Missing

- input from a typed `TurnPlan`;
- typed context segments instead of a broad map-heavy eager bundle;
- per-segment provenance, completeness and required/optional classification;
- retrieval results from Phase 41–42 rather than embedded raw SQL;
- historical time scope from Phase 39;
- explicit local/cloud/workload context variants;
- compatibility path from current `ContextBundle` without dual authority.

### Current technical debt

- fixed queries and fixed limits are embedded directly in `ContextBuilder`;
- it eagerly reads many domains regardless of a typed plan;
- many fields are `Map<String, Any?>`;
- mission/pressure/chronicle legacy tables require explicit future classification;
- the existing bundle combines candidate construction and presentation-oriented shape.

### Adapter decision

**OWNER FIX**.

Evolve the existing ContextBuilder behind its current repository entry point. Initially produce the new typed candidate and project it to the legacy `ContextBundle` for existing consumers. Do not build a separate Context Builder adapter platform.

## 11. Phase 46 — Context Budget Manager

Classification: **MISSING**.

### Existing

- fixed SQL limits and snapshot profiles;
- disclosure reduction;
- a specification stating that the full database must not be sent to AI.

### Missing

- `ModelProfile` / `AiCapability` input contract;
- token/size estimator abstraction;
- workload-aware budget;
- required/safety/quality segment priorities;
- deterministic reduction policy;
- truncation/completeness evidence;
- semantic preservation tests for uncertainty, actor/action/target and security.

Fixed `LIMIT 20/30/40/...` values are not Phase 46. They are legacy presentation safeguards and must not become authoritative completeness rules.

## 12. Phase 47 — Iterative Retrieval

Classification: **MISSING**.

### Existing

- bounded individual queries;
- no-data/denied/not-disclosed/unknown/corruption states from Phase 38;
- future `TurnPlan` and retrieval contracts can provide the required foundation.

### Missing

- `MissingContext` typed reason model;
- iteration state and deduplication;
- hard limits for attempts/time/records/budget/depth;
- legal follow-up query generation from the existing plan;
- termination reasons and safe partial result;
- proof that missing data cannot expand audience entitlement;
- loop determinism and exhaustion tests.

Phase 47 must not use an AI model to decide whether access should expand or whether hidden data should be fetched.

## 13. Phase 38 integration requirements

Every new protected consumer introduced by Phase 39–47 must:

1. have an explicit `AudienceContext` and `PurposeContext`;
2. be registered in `VisibilityConsumerInventory` when it matches protected markers;
3. use `ProtectedCampaignReadRepository` or a Phase-38-approved projection source;
4. preserve all typed states, especially `NO_DATA`, `DENIED`, `NOT_DISCLOSED`, `UNKNOWN` and `CORRUPTION`;
5. preserve campaign identity and fail closed on cross-campaign references;
6. avoid raw hidden fields in context candidates;
7. use historical access/knowledge at the requested time after Phase 39 adds that capability;
8. never treat prompt instructions as access control.

The existing inventory is path-based. New files will require explicit registry updates and inventory tests; otherwise CI should fail closed.

## 14. Persistence and migration findings

### Safe foundations

- Phase 36 provides schema-family versioning and migration attempts.
- persistent state families are centrally classified in `RuntimeTruthLayerRegistry`.
- event, causal, access, knowledge, snapshot and replay schemas have explicit readiness/migration logic.
- old campaign compatibility has extensive precedent and tests.

### Required additions

- any new Phase 39/40 tables must receive a registered state family and schema version;
- Phase 40 scheduled evaluations are authoritative and may mutate only through canonical turn/domain paths;
- Phase 41–47 should preferably be read/derived state and avoid new domain tables;
- retrieval indexes may be rebuildable cache only and must not become authority;
- migration tests must cover old campaign with legacy schedules, no temporal metadata and unknown provenance;
- missing legacy history stays `UNKNOWN`/`UNSUPPORTED`; migration must not invent dates, causes or acquisitions.

## 15. Test assets that should be reused

Existing foundations include:

- `Phase30EventStoreTest`;
- `Phase31CausalGraphTest`;
- Phase 32 Context Builder truth/read-only/cross-campaign tests;
- `Phase33SnapshotSystemTest` and Phase 34 retention tests;
- `Phase37WorldActorKnowledgeTest`;
- Phase 38 visibility/access/protected-read/inventory tests;
- PlayerCommand structural/semantic/adversarial tests;
- inventory and technique ContextBuilder regression tests;
- transaction retry/rollback/replay tests.

No existing test proves the complete Phase 39–47 pipeline. New tests must be layered per Gate A–D and finish with the integration scenario described in `docs/PHASE_39_47_EXECUTION_BLOCK.md`.

## 16. Dependency and implementation order

Required order:

```text
39 temporal contract + minimum sources
→ 40 scheduler contract/lifecycle
→ Gate A
→ 41 typed structured retrieval
→ 42 bounded causal/knowledge traversal
→ Gate B
→ 43 normalized intent
→ 44 bounded turn plan
→ Gate C
→ 45 typed context candidate, evolved from current builder
→ 46 dynamic budget
→ 47 bounded missing-context loop
→ Gate D
→ final pipeline audit without Phase 48
```

Phase 43 contract design may be prepared while Gate A–B runs, but its production integration must not bypass the accepted retrieval capabilities. Phase 45–47 implementation must wait for Gate B and the Turn Plan contract.

## 17. Initial file/subsystem impact map

Expected existing owners to extend:

- temporal/event/replay: `CampaignEventStore.kt`, `CampaignSnapshotSystem.kt`, domain history stores;
- access time: `Phase38AccessAuthority.kt`, `Phase38ProtectedRead.kt`;
- knowledge time: `Phase37WorldActorKnowledge.kt`;
- causal retrieval: `CampaignCausalGraph.kt`;
- repository surface: `GameRepository.kt`, `UnifiedGameRepository.kt`, `LocalGameStore.kt`;
- context evolution: `ContextBuilder.kt`, `ContextModels.kt`;
- command vocabulary reuse: `PlayerCommandModel.kt`;
- consumer classification: `Phase38VisibilityConsumerInventory.kt`;
- schema/migration classification: Phase 36 registry plus `RuntimeTruthLayerRegistry.kt`.

New files should be separated by responsibility rather than placed into one Phase39To47 service.

## 18. Blockers and decisions

No user/product decision currently blocks Gate A.

Technical decisions that Gate A must settle through tests:

1. Canonical temporal coordinate is committed order; campaign day/chapter require explicit mapping evidence.
2. Historical truth source priority must be declared per domain.
3. Event records alone are not full historical state.
4. Legacy temporal gaps remain unknown.
5. Scheduler creates evaluation points, never guaranteed outcomes.

Potential blocker discovered for implementation planning:

The scope of universal historical truth spans several owners with different temporal conventions. A small source-port layer is justified for Phase 39, but only after the first concrete query matrix is written. It must not become the deferred Phase 38.5 platform.

## 19. Gate A readiness verdict

**READY WITH CONSTRAINTS**.

Gate A may begin after creating a narrow contract/test matrix for:

- current versus historical Campaign Truth;
- one history-native domain such as ownership;
- Phase 37 holder knowledge at order T;
- Phase 38 access/disclosure at order T;
- unknown legacy history;
- scheduled evaluation lifecycle and idempotent processing.

The first implementation change should define/test the typed temporal contract and source capability behavior. It should not begin with a broad schema migration or a generic adapter registry.
