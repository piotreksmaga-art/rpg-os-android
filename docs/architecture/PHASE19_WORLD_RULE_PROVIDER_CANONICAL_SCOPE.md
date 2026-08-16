# Phase 19 — WorldRuleProvider Canonical Scope

Status: CANONICAL AUDITOR SCOPE — CLEAN REBUILD

This document defines the acceptance boundary that CHAT-2, CHAT-3, CHAT-4 and CHAT-5 must use when revalidating the clean Phase-19 candidate.

## Canonical purpose

Phase 19 implements the **WorldRuleProvider contract** and only the minimum infrastructure necessary to make its authority deterministic, coherent and read-only while preserving the accepted Phase-17/18 contracts.

Fundamental invariant:

```text
ONE RESOLUTION
=
ONE CANONICAL AUTHORITY OBSERVATION
=
ONE IMMUTABLE PINNED WORLD PACK BINDING
```

Canonical flow:

```text
CANONICAL WORLD PACK AUTHORITY
-> ONE COHERENT AUTHORITY OBSERVATION
-> ONE PINNED BINDING
-> COMMAND_PRECHECK
-> DOMAIN RESOLUTION
-> DRAFT_EFFECT_CHECK
-> PLAYERCHANGESET PROPOSAL
```

A legal authority change occurring during one resolution may affect only the NEXT resolution.

## IN SCOPE

- one canonical World Pack authority source;
- read-only authority dependency at the `PlayerDomainEngine` / `WorldRuleProvider` boundary;
- coherent observation binding campaign identity + World Pack UID + World Pack version + validated package content identity needed by the rule provider;
- package-content TOCTOU prevention for supported package selection/replacement/import/update paths;
- one authority observation per resolution;
- one immutable pinned binding per resolution;
- exact same pinned binding for `COMMAND_PRECHECK` and `DRAFT_EFFECT_CHECK`;
- legal authority freshness on the next resolution for long-lived engines;
- fail-closed missing/stale/mismatched/cross-campaign/invalid authority before provider invocation;
- provider invocation count `0` for authority rejection/fault paths before provider selection;
- WorldRuleProvider read-only capability boundary;
- `WorldRuleDecision` as rule/decision only, never canonical mutation;
- `PlayerChangeSet` remains a proposal according to Phase 17/18;
- zero authoritative mutation in Phase-19 resolution;
- provider retained-state fail-closed policy;
- Kotlin synthetic capture protection where it affects retained provider state;
- safe stateless/scalar provider configuration;
- deterministic request/decision identities/fingerprints already required by the accepted contracts;
- representative Phase-17 and Phase-18 regression compatibility;
- no Phase-20 runtime implementation.

## OUT OF SCOPE

Unless a direct path to wrong/stale/mixed/uncommitted Phase-19 World Pack authority is first demonstrated, the following are not Phase-19 acceptance blockers:

- full atomic snapshot of active `campaign.db`;
- SQLite/WAL snapshot engine;
- general synchronization of gameplay database writers;
- general `createCampaign()` branching/snapshot semantics;
- general `RestoreManager` transaction/synchronization semantics;
- Save/Load architecture;
- branching without database duplication;
- general Backup System;
- global `LAST VALID COMMIT`;
- full application/process crash recovery;
- general Snapshot System and retention;
- full `TurnTransaction`;
- COMMIT pipeline;
- global authoritative mutation infrastructure;
- ProgressionEngine or any Phase-20+ runtime;
- TEMP GM / CHAT-7 benchmark runtime;
- CHAT-6 Android presentation/release work unless separately assigned.

## Package/recovery exception

Package/recovery behavior enters Phase-19 scope only if a concrete supported path can leave the World Pack such that a later Phase-19 resolution accepts content that is WRONG, STALE, MIXED or UNCOMMITTED as canonical authority.

Fix only the minimum necessary authority boundary. Do not expand the fix into general Phase-29 recovery infrastructure.

## Required canonical acceptance matrix

- `P19_AUTH_01` valid canonical authority allows provider
- `P19_AUTH_02` missing authority fails closed; provider invocations = 0
- `P19_AUTH_03` wrong campaign fails closed
- `P19_AUTH_04` wrong World Pack UID fails closed
- `P19_AUTH_05` wrong World Pack version fails closed
- `P19_AUTH_06` authority read/validation failure fails closed
- `P19_COHERENCE_01` one coherent observation throughout resolution
- `P19_COHERENCE_02` legal authority change visible on next resolution
- `P19_COHERENCE_03` mixed C1+B/equivalent impossible or rejected
- `P19_COHERENCE_04` PRECHECK and EFFECT_CHECK use same pinned binding
- `P19_COHERENCE_05` exactly one authority observation per resolution
- `P19_PROVIDER_01` provider has no canonical mutation capability
- `P19_PROVIDER_02` mutable retained provider state rejected
- `P19_PROVIDER_03` synthetic mutable capture rejected
- `P19_PROVIDER_04` stateless/scalar-safe provider accepted
- `P19_PROVIDER_05` identical semantic input -> deterministic decision identity
- `P19_ZERO_MUTATION_01` successful resolution performs zero authoritative mutation
- `P19_ZERO_MUTATION_02` authority rejection/fault paths perform zero authoritative mutation
- `P19_P17_REGRESSION` representative Phase-17 contract remains intact
- `P19_P18_REGRESSION` representative Phase-18 ordering/reference/orchestration remains intact

## Forbidden deltas

```text
PLAYERCHANGESET SCHEMA DELTA: NONE
DATABASE MIGRATION DELTA: NONE
PACKAGE FORMAT DELTA: NONE
SECOND PERSISTED AUTHORITY: NONE
PHASE-20 RUNTIME DELTA: NONE
```

Any required violation of these constraints is a STOP condition requiring coordinator decision.

## Acceptance authority

CHAT-1 may produce a clean final candidate and exact-SHA evidence, but must not mark Phase 19 globally ACCEPTED. The frozen candidate requires fresh clean-scope CHAT-2/3/4/5 revalidation on the exact same SHA followed by coordinator decision.
