# Phase 21–25 — Canonical Acceptance Record

Status: ACCEPTED / COMPLETE

This is the concise durable acceptance record for **Player Core Completion — Phase 21 through Phase 25**. Detailed pre-audits, implementation, first failed post-audits, targeted fix, and exact-SHA revalidation remain under `docs/audits/`.

## Canonical accepted runtime

- Runtime SHA: `c028aa355d9b7e1663166a2fedb910c1a2dad795`
- Exact acceptance CI: run #607 / ID `31968919354`
- CI status/conclusion: `completed / success`
- Exact-SHA validation included project validation, full JVM unit suite, signed validation APK, and immutable validation artifact.
- Publication: `false`

Later documentation-only commits do not change the canonical accepted runtime SHA.

## Coordinator decision

- **PHASE 21 = ACCEPTED / COMPLETE**
- **PHASE 22 = ACCEPTED / COMPLETE**
- **PHASE 23 = ACCEPTED / COMPLETE**
- **PHASE 24 = ACCEPTED / COMPLETE**
- **PHASE 25 = ACCEPTED / COMPLETE**

All final acceptance evidence is bound to the same exact runtime SHA `c028aa355d9b7e1663166a2fedb910c1a2dad795`.

## Evidence chain

Pre-implementation:

- `WORK-20260816-010` — CHAT-2 contract/architecture audit — READY WITH REQUIRED PRECONDITIONS
- `WORK-20260816-011` — CHAT-3 integrity/migration/adversarial audit — READY WITH REQUIRED PRECONDITIONS

Implementation:

- `WORK-20260816-012` — CHAT-1 Player Core Completion implementation
- Gate 21 PASS SHA: `8cd1dc63f4736ddd2e5c419d2d48ec72fa3e1d07`
- Gate 22 PASS SHA: `239c06c6dd71c806beae3c6c03524d64aa0fe2b9`
- Gate 23 PASS SHA: `32f68228844af38b657264fa01618a7acbb5d931`
- Gate 24 PASS SHA: `96ac11c60fc52175bc9166b16a2cb15294469579`
- Initial Gate-25/final candidate: `aae30b60b6276ceea6113ade22f27836bda78b26`

First post-audit:

- `WORK-20260816-013` — CHAT-4 — FAIL; blocker `P21-25-C4-001`
- `WORK-20260816-014` — CHAT-5 — FAIL; blocker `P21-25-CB-01`
- Coordinator unified both findings as `P21-25-INVARIANT-BYPASS-01`.

Targeted fix and final exact-SHA revalidation:

- `WORK-20260816-015` — CHAT-1 targeted invariant-bypass fix
- Final runtime candidate: `c028aa355d9b7e1663166a2fedb910c1a2dad795`
- `WORK-20260816-016` — CHAT-4 — PASS — invariant bypass fix verified
- `WORK-20260816-017` — CHAT-5 — PASS — cross-boundary fix verified

## Closed acceptance blocker

Closed blocker:

`P21-25-INVARIANT-BYPASS-01`

The initial candidate exposed two semantically different proposal-return paths: canonical `PlayerDomainEngine.resolve(...)` could return a proposal before Phase-22 invariant validation, while the optional `resolveWithPlayerInvariants(...)` wrapper applied `PlayerInvariantValidator` afterward.

The accepted runtime removes the optional wrapper and makes invariant validation mandatory inside canonical `PlayerDomainEngine.resolve(...)` after `PlayerChangeSetValidator` and before `PlayerResolutionOutcome.Resolved` can be returned.

Accepted canonical ordering remains:

`COMMAND_PRECHECK -> domain resolution -> Phase20/21 progression -> augmented reference closure -> ONE DRAFT_EFFECT_CHECK -> PlayerChangeSet -> structural validation -> PlayerInvariantValidator -> final proposal outcome`

This fix does not implement Phase 26 Single Truth Mutation Path enforcement, TurnTransaction, repository commit enforcement, or a second WorldRuleProvider.

## Phase 21 accepted scope

Phase 21 accepts deterministic Core-owned diminishing-returns/progression-factor semantics and pure passive-progression hook semantics built on the accepted Phase-20 ProgressionEngine.

Accepted boundaries include:

- deterministic/versioned factor semantics for diminishing returns and related progression evidence;
- passive hooks as pure adapters from already-resolved causal facts into canonical progression stimuli;
- no wall-clock, random, process-local, scheduler, world-simulation, or hidden mutable adaptation authority;
- no second progression engine;
- no schema/migration delta.

## Phase 22 accepted scope

Phase 22 accepts the read-only `PlayerInvariantValidator` / No-Retrogression proposal gate.

Accepted boundaries include:

- mandatory invariant validation on canonical `PlayerDomainEngine.resolve(...)` proposal return;
- unexplained durable regression of earned Stat/Skill/Technique progression rejected by default;
- typed `DurableRegressionAuthorization` for legal durable regression;
- legal negative resource/equipment/runtime semantics are not treated as unexplained earned-progression regression;
- validator remains separate from World Pack legality and has no writer/commit authority;
- no schema/migration delta.

## Phase 23 accepted scope

Phase 23 accepts bounded unified provenance/ledger semantics, not a premature global writable ledger authority.

Accepted boundaries include:

- semantic/provenance envelope distinguishing proposal evidence, committed family references, and unknown/not-recorded history;
- existing finance authority remains authoritative for finance;
- existing ownership authority remains authoritative for ownership;
- progression remains proposal evidence at this stage;
- `P20-CB-01` is resolved prospectively: new progression evidence references participate in structured provenance/identity/reference validation where required;
- historical missing provenance is not fabricated or backfilled;
- no `unified_player_ledger` writable authority and no TurnTransaction introduced;
- no schema/migration delta.

## Phase 24 accepted scope

Phase 24 accepts `CharacterPanelSnapshotV2` as a derived/presentation read model.

Accepted boundaries include:

- snapshot is not authoritative state;
- delete/rebuild causes zero authoritative data loss;
- rebuild does not mutate authority;
- stale snapshot cannot overwrite current authoritative/current read sources;
- exact numeric values remain stable through projection;
- no snapshot-only permanent truth;
- no persistence/schema migration required by the accepted implementation.

## Phase 25 accepted scope

Phase 25 accepts deterministic `PlayerSnapshotBuilder` projections for:

- `FULL`
- `COMBAT`
- `PROGRESSION`
- `ECONOMY`
- `SOCIAL`
- `GM_CONTEXT`

Accepted boundaries include:

- all profiles are `DERIVED_PROJECTION`, never independent player authorities;
- omission from a profile does not mean nonexistence in campaign reality;
- economy projection does not replace finance authority;
- social projection does not create NPC Knowledge authority;
- `GM_CONTEXT` preserves distinct FACT / BELIEF / NARRATIVE classes and does not establish an omniscient merged truth store;
- deterministic rebuild and zero authoritative mutation.

## Source-of-truth and phase boundaries preserved

The accepted Phase 21–25 runtime does **not** create:

- a second Player Engine;
- a second WorldRuleProvider;
- a second persisted progression authority;
- a global writable unified player ledger;
- TurnTransaction;
- global retry/idempotency guarantees;
- crash recovery;
- Event Store redesign;
- authoritative Snapshot System;
- NPC Knowledge runtime;
- Temporal Engine/Scheduler;
- Time Skip Processor;
- Phase 26 Single Truth Mutation Path enforcement.

Schema/migration delta for the accepted Phase 21–25 program: **NONE**.

## TEST GM consequence

Before this acceptance record, TEST GM correctly treated Phase 21–25 as not accepted and used architecture fallback for mechanics belonging to these phases.

After this canonical acceptance record, TEST GM may treat the accepted Phase 21–25 contracts at runtime SHA `c028aa355d9b7e1663166a2fedb910c1a2dad795` as accepted mechanics. This does not promote later phases or mechanics not contained in the accepted scope.

## Next stage

The next roadmap stage is **Phase 26 — Single Truth Mutation Path enforcement**.

Phase 26 must begin with **AUDIT FIRST**. This acceptance record does not authorize Phase-26 implementation by itself. The coordinator must inspect current repository/runtime, classify the actual delta, and explicitly authorize the next work items.
