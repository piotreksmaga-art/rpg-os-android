# WORK-20260817-018 — Phase 26–36 Contract / Architecture / Grouping Audit

Status: PRE-IMPLEMENTATION / EVIDENCE-ONLY / READ-ONLY RUNTIME

Role: CHAT-2 — independent contract / architecture reviewer

Date: 2026-08-17

## 1. Executive verdict

**Final verdict: READY WITH REQUIRED PRECONDITIONS**

Recommended implementation grouping:

1. **GROUP A — Phase 26–29**: Single Truth Mutation Path / Turn Transaction / Idempotency / Crash Recovery
2. **GROUP B — Phase 30–32**: Event Store / Causal Graph / Truth-Layer Enforcement
3. **GROUP C — Phase 33–35**: Snapshot System / Retention / Canon Divergence
4. **GROUP D — Phase 36**: Schema Versioning / Migration Safety / Legacy Provenance

This is **OPTION 3: 26–29 / 30–32 / 33–35 / 36**.

Phase 26–36 should **not** be implemented as one batch. Phase 33–36 should also **not** be collapsed into one implementation group at this point. Phase 36 is safer as an independent final gate because it must migrate the *settled* schemas/contracts produced by 26–35, while preserving legacy campaigns and unknown provenance without inventing history.

No Phase 26–36 phase is declared COMPLETE or ACCEPTED by this audit.

---

## 2. Exact repository/bootstrap state

Repository: `piotreksmaga-art/rpg-os-android`

Branch: `master`

Canonical accepted Player-Core runtime through Phase 25:

`c028aa355d9b7e1663166a2fedb910c1a2dad795`

Accepted Phase-25 CI: Validate RPG OS ALPHA run #607 / ID `31968919354` / SUCCESS, as recorded by the canonical Phase 21–25 acceptance material.

Master HEAD observed at audit bootstrap and again immediately before this evidence-only write:

`fcf51d3478efdb28f09ef5c8a4b3cfaf834477c8`

HEAD message: `docs(test-gm): add phase 25 navigation redirect`

Current master CI at audit bootstrap: Validate RPG OS ALPHA run #619 / ID `32001653887` / completed / success.

### Master drift against accepted Phase-25 runtime

`master` is 12 commits ahead of `c028aa355d9b7e1663166a2fedb910c1a2dad795` and 0 commits behind.

The compare contains only documentation / audit / acceptance / TEST-GM navigation changes. No Kotlin runtime, database schema, migration or runtime-test file changed between accepted Phase-25 runtime and the audited master HEAD.

Drift classification:

| Drift class | Present? | Audit result |
|---|---:|---|
| docs-only | YES | acceptance/roadmap/audit/TEST-GM redirects only |
| tests-only after accepted SHA | NO | no test delta in the 12-commit compare |
| runtime | NO | no Kotlin runtime delta |
| schema | NO | no schema delta |
| migration | NO | no migration delta |
| architecture/acceptance | YES | Phase 21–25 acceptance and roadmap documentation only |

Therefore the current master runtime/schema/migration state is materially the accepted Phase-25 runtime, but the SHA distinction is preserved throughout this report.

### Mandatory sources read

- `docs/PROJECT_WORK_PROTOCOL.md`
- `docs/RPG_OS_MASTER_ARCHITECTURE.md`
- `docs/RPG_OS_IMPLEMENTATION_ROADMAP.md`
- `docs/GM_ENGINE_TARGET_ARCHITECTURE.md`
- `docs/architecture/CHAT_COORDINATION_POLICY.md`
- `docs/architecture/PHASE19_ACCEPTANCE.md`
- `docs/architecture/PHASE20_ACCEPTANCE.md`
- `docs/architecture/PHASE21_25_ACCEPTANCE.md`
- relevant current Phase 21–25 runtime/tests and persistent stores
- Phase 19 deferred recovery/transaction findings where they define later-phase boundaries

The canonical Phase 21–25 endpoint remains a **validated proposal**, not a commit system:

`COMMAND_PRECHECK -> domain resolution -> Phase20/21 progression -> augmented reference closure -> DRAFT_EFFECT_CHECK -> PlayerChangeSet -> structural validation -> PlayerInvariantValidator -> final proposal outcome`

Phase 21–25 acceptance explicitly does not implement Phase-26 single mutation enforcement, TurnTransaction, global idempotency/recovery, Event Store redesign or Snapshot System.

---

## 3. Repository-first Phase 26–36 classification

| Phase | Classification | What exists and is reusable | What is missing for target contract |
|---|---|---|---|
| 26 Single Truth Mutation Path | **PARTIAL** | `UnifiedGameRepository`, typed stores, `SourceOfTruthRegistry`, accepted `PlayerDomainEngine -> PlayerChangeSet` proposal boundary | one campaign commit gateway; direct writer gating; generic patch containment; non-player proposal envelope; privileged administrative path classification |
| 27 Turn Transaction | **PARTIAL** | widespread SQLite transaction primitives; domain-local atomicity; stores such as finance can join an outer transaction | one turn-scoped transaction owner coordinating all authoritative state + events + ledgers; global rollback/result contract; nested-transaction policy |
| 28 Idempotency | **PARTIAL** | stable command/effect identities from Player Core; finance replay by financial transaction UID/command UID; ownership replay by operation UID; several unique indexes | durable turn/transaction idempotency registry and one cross-domain retry result; global uniqueness contract |
| 29 LAST VALID COMMIT | **PARTIAL** | SQLite rollback semantics; package recovery hardening; backup/restore utilities | committed-turn marker, incomplete transaction recovery contract, process-death fault matrix, authoritative recovery from commit state rather than side-effect inference |
| 30 Event Store | **PARTIAL** | many specialized append/history records and `source_event_uid` links; campaign truth provenance can reference an event; legacy story/history structures | canonical bounded append-only significant-event store, typed event identity/version/order/transaction linkage and immutability guarantees |
| 31 Causal Graph | **PARTIAL** | legacy `consequence_links`/story dependency concepts and many source-event/supersession references can seed a graph | canonical `CAUSED/ENABLED/TRIGGERED/PREVENTED` edge contract, stable edge IDs, event-only endpoint integrity, rebuild/replay policy |
| 32 Truth-layer runtime enforcement | **PARTIAL** | `SourceOfTruthRegistry`, derived resolvers, rebuildable finance balance projection, Phase24/25 derived projections, FACT/BELIEF/NARRATIVE policy | enforceable runtime taxonomy across all tables/APIs; reverse-dependency prohibition; generic writer lockdown; deletion/rebuild proofs |
| 33 Snapshot System | **PARTIAL** | `ChapterSaveManager`, `BackupManager`, whole-DB chapter copies, restore utility | commit-boundary snapshot contract, integrity identity/version, deterministic state reconstruction, WAL-aware/SQLite-safe creation, stale/invalid policy |
| 34 Snapshot retention max 6 | **PARTIAL** | `BackupManager.AUTO_SNAPSHOT_RETENTION = 6` and automatic chapter-file pruning | retention attached to canonical snapshot metadata/commit identity; separation from manual backups; proof that pruning can never remove authority |
| 35 Canon Divergence | **PARTIAL** | `CampaignTruthRecord`, `WORLD_CANON` provenance, supersession, existing `timeline_divergences` writable legacy/runtime table | typed durable divergence record tied to canon fact UID + source committed event + valid time + override/suppress/replace semantics; World Pack update reconciliation |
| 36 Schema/migration/legacy provenance | **PARTIAL** | `rpgos_schema_migrations`; migrations V1–V15+hardening; explicit legacy mappings/evidence; several migrations explicitly avoid legacy synthesis | one current schema/version contract covering new 26–35 objects; migration preflight/postflight; failure atomicity; old-campaign fixtures; unknown provenance representation and end-to-end compatibility |

No phase is BLOCKED by an unresolved design question that requires coordinator intervention before Gate 26 can begin. However later gates have explicit prerequisites described below.

---

## 4. Global invariant and mutation-path map

The required canonical flow remains:

```text
PROPOSAL
-> DOMAIN/RULE RESOLUTION
-> CHANGE SET
-> VALIDATION
-> TRANSACTION
-> EVENTS + LEDGERS + AUTHORITATIVE STATE
-> COMMIT
-> COMMITTED REALITY
```

`AI OUTPUT != COMMITTED REALITY`

`FACT != BELIEF != NARRATIVE`

### Current production mutation paths

| Current path | Current role | Classification | Phase-26 treatment |
|---|---|---|---|
| `PlayerDomainEngine.resolve()` -> validated `PlayerChangeSet` | player proposal generation | canonical proposal path, not writer | preserve unchanged; feed commit gateway |
| `UnifiedGameRepository.applyPatch()` -> `StatePatchEngine.apply()` | generic SQL insert/update/delete on registry-allowed tables | **possible authoritative bypass** | remove authoritative-table capability; restrict to explicitly non-authoritative/legacy adapter scope or route typed proposals through commit gateway |
| `UnifiedGameRepository.recordTruth()` -> `CampaignTruthStore.record()` | FACT/BELIEF/NARRATIVE persistence | direct authoritative writer | gate through campaign mutation/TurnTransaction for gameplay writes; migration/admin adapter separate |
| `setActivePlayer()` -> `ActivePlayerStore.set()` | authoritative active-player identity | direct authoritative administrative/domain writer | classify as explicit administrative mutation or transactional domain mutation; never generic AI patch |
| typed player/stat/resource/skill/technique stores | player authority | direct typed authoritative writers | make authoritative mutators internal/gated behind transaction context; keep read APIs public |
| `FinancialStore` | authoritative financial ledger/account state + rebuildable balance projection | typed direct authority with good local atomicity/idempotency | preserve financial authority; invoke under TurnTransaction rather than duplicating finance truth |
| `OwnershipStore` | authoritative ownership record/history + operation evidence | typed direct authority with local atomicity/idempotency | preserve ownership authority; invoke under TurnTransaction |
| Inventory/Equipment stores | authoritative item possession/equipment domain | typed direct authority | gate under TurnTransaction; preserve stable item identity/ownership constraints |
| DevelopmentProject store/history | authoritative project + append-history domain | typed direct authority | gate under TurnTransaction; event links remain evidence, not duplicate project truth |
| `ChapterSaveManager.finalizeChapter()` | chapter manifest write | derived/presentation/continuity product with persistent write | move after commit; it cannot define committed reality |
| `BackupManager.createBackup()` | whole-DB file copy | backup/current proto-snapshot | privileged post-commit operation; not a transaction authority |
| `RestoreManager.restoreBackup()` | replaces `campaign.db` file | privileged recovery/admin authority replacement | never ordinary TurnTransaction child write; require quiescence/validation/recovery gate |
| schema migrations / seed adapters | schema + controlled legacy mapping | privileged maintenance mutation | explicit migration mode outside gameplay transaction; auditable and fail-closed |
| World Pack package replacement/selection | definition authority, not campaign-state mutation | separate canonical package authority | do not fold into TurnTransaction; preserve Phase-19 pinned authority contract |
| Visual library / presentation-only stores | media/presentation | non-authoritative where verified | may remain outside gameplay transaction if classification is enforced and deletion is harmless |

### Critical finding: generic patch path

`StatePatchEngine` currently begins a SQLite transaction and performs generic `insert/update/delete`. `SourceOfTruthRegistry` blocks several typed-only tables and `campaign_truth_records`, but also explicitly allows a set of runtime tables such as story/thread/knowledge/fact/position/NPC/timeline/mission tables. Therefore this is **not** a Phase-26 Single Truth Mutation Path. Table allowlisting is not equivalent to domain/rule/transaction validation.

Phase 26 must not merely add more names to `TYPED_ONLY_TABLES`; the architectural fix is capability-based: authoritative gameplay mutation requires an accepted proposal and transaction-scoped writer capability.

---

## 5. Source-of-truth classification map

### AUTHORITATIVE

- committed player state: active player, stats/resources, skill/technique mastery, accepted durable profile inputs
- finance: `financial_ledger_transactions`, financial account identity/lifecycle; financial ledger remains the financial explanation of truth
- ownership: ownership records/operations and stable item/asset identity evidence according to existing typed ownership contract
- inventory/item-instance state and equipment state where existing stores define current campaign possession/use
- development project identity and append-preserved project lifecycle/work/outcome evidence
- committed significant Event Store records after Phase 30
- campaign FACT/BELIEF/NARRATIVE records subject to their distinct truth kinds; BELIEF/NARRATIVE never upgrade themselves to FACT
- canon divergence records after Phase 35
- committed turn/transaction/idempotency metadata required to identify committed reality after Phase 27–29
- World Pack definitions are authoritative **definitions/policy**, not mutable campaign state and not owned by TurnTransaction

### DERIVED / REBUILDABLE

- financial account balances (already rebuildable from financial ledger)
- derived stat/resource effective values
- project progress snapshots/aggregates
- Causal Graph **index** if implemented as event-derived edges; explicitly authored causal assertions may be append-only evidence but must reference real events
- CharacterPanelSnapshotV2
- PlayerSnapshotBuilder profiles (`FULL`, `COMBAT`, `PROGRESSION`, `ECONOMY`, `SOCIAL`, `GM_CONTEXT`)
- chapter/context summaries derived from authoritative records

### CACHE / INDEX

- search/retrieval indexes
- narrative memory index where it is only an index over retained source records
- materialized lookup/cache tables that can be deleted and rebuilt from accepted authority
- snapshot files if used purely as replay acceleration; they are never the sole authority

### PRESENTATION

- Character panel presentation models
- PlayerSnapshot profile DTOs
- UI/status summaries
- visual library metadata to the extent it does not encode campaign facts
- rendered chronicle text/summary when derived from committed event/history sources

### Legacy / uncertain and therefore restricted

Legacy tables/readers may be read for compatibility, but cannot silently reconstruct new canonical authority. A missing historical UID/source/cause is `UNKNOWN / NOT RECORDED`, not a guessed event or provenance record.

### Reverse dependencies to eliminate

1. Generic `StatePatch` must not write authoritative tables or data later treated as authoritative.
2. CharacterPanelSnapshotV2 / PlayerSnapshot profiles must never be persistence input for player authority.
3. Chronicle/presentation text must never be parsed back into FACT/event/state authority.
4. Snapshot restore must restore a validated committed state, not make an arbitrary cache file authoritative by mere existence.
5. Derived financial balance cannot overwrite ledger history.
6. Causal Graph cannot create missing historical events.
7. World Pack updates cannot overwrite campaign divergences.

---

## 6. Dependency graph

```text
Accepted Phase 25 proposal boundary
        |
        v
P26 Single Truth Mutation Path
        |
        v
P27 TurnTransaction atomicity
        |
        v
P28 durable idempotency
        |
        v
P29 LAST VALID COMMIT / recovery
        |
        +----------------------------+
        v                            |
P30 Event Store                     |
        |                            |
        v                            |
P31 Causal Graph                    |
        |                            |
        v                            |
P32 truth-layer enforcement --------+
        |
        v
P33 Snapshot System
        |
        v
P34 bounded automatic retention
        |
        v
P35 Canon Divergence
        |
        v
P36 schema/version/migration/legacy safety
```

Phase 30 requires the transaction identity/commit boundary from 27–29. Phase 33 requires a stable committed reality and Event Store replay boundary. Phase 35 requires committed event identity if divergence is to survive canon updates with causal provenance. Phase 36 must target the settled database objects produced by all prior gates.

---

## 7. Minimal target contracts

### Phase 26 — Single Truth Mutation Path

Canonical mutation boundary: a **campaign commit gateway** accepting validated domain proposals and issuing a transaction-scoped mutation capability. It must sit after rule/domain resolution and before any authoritative write.

Do not make `PlayerDomainEngine` the campaign-wide engine. It remains the player-domain proposal resolver.

Recommended shared envelope: a small `CampaignChangeSet` / `CampaignMutationProposal` interface/envelope capable of carrying one or more already-validated domain change sets plus stable command/turn/causation metadata. Avoid a new omniscient `CampaignDomainEngine`.

Required Phase-26 rules:

- authoritative gameplay stores cannot be mutated through public generic SQL APIs;
- typed stores remain owners of domain persistence semantics;
- the commit gateway coordinates them but does not recalculate finance, ownership, progression or world rules;
- `StatePatchEngine` is restricted to non-authoritative compatibility/presentation/cache targets, or adapted into typed proposals;
- privileged maintenance modes are explicitly separated: migration, restore, package management;
- writer APIs that alter campaign truth become internal/gated or require a transaction capability/token unavailable to UI/AI/context builders;
- read APIs remain independently reusable.

What must NOT be centralized prematurely: World Pack replacement, migration execution, backup restore, domain-specific validation algorithms, PlayerDomainEngine internals, finance ledger mathematics, ownership history semantics.

### Phase 27 — TurnTransaction

Minimum identities:

- `turnUid`: stable logical turn/action aggregation identity
- `commandUid`: stable originating command identity; already exists for Player Core paths
- `transactionUid`: stable commit-attempt identity/idempotency key; deterministic binding to the logical proposal/turn rather than random retry identity

Minimum durable lifecycle: `BEGIN/IN_PROGRESS`, `COMMITTED`, and terminal `ROLLED_BACK/ABORTED` only if a durable audit row is intentionally retained outside the transaction; never infer commit from partial child writes.

Recommended physical boundary: **one SQLite transaction owned by TurnTransaction** for all campaign-db authoritative writes belonging to one turn. Typed stores must join an existing transaction rather than begin competing top-level transactions. Nested transaction policy: no independent nested commit; child stores either join owner transaction or fail if used in forbidden mode.

Commit ordering is semantic, not a source-of-truth hierarchy. Validate all preconditions first, then write transaction-linked authoritative domain rows, specialized ledgers and significant events inside the same SQLite transaction, then commit once. Any error before successful SQLite commit means no authoritative child effect survives.

WorldRuleProvider remains pre-commit/read-only rule authority; TurnTransaction must never call itself a rule engine or mutate World Pack authority.

Post-commit work: rebuild/invalidate derived/cache/presentation products outside the authoritative transaction. Their failure must not reverse a committed turn; instead mark them stale and rebuild later.

### Phase 28 — Idempotency / double-commit protection

Durable key registry must be in SQLite, not process memory.

Required identities and uniqueness:

- campaign + transactionUid unique
- campaign + logical turnUid unique according to turn semantics
- campaign + commandUid unique for commit-bearing commands
- campaign + eventUid unique
- finance keeps existing financialTransactionUid and command linkage
- ownership keeps existing operationUid and stable record UIDs
- item instance UIDs remain globally/campaign stable as already defined
- progression grant/effect identities remain stable from Phase 20–23 evidence

Retry after committed success:

`same semantic fingerprint -> ALREADY_COMMITTED + same committed result identity + zero child writes`

`same UID + different semantic fingerprint -> fail closed identity collision`

Retry after a fully rolled-back/incomplete attempt: permitted only according to the same deterministic proposal/transaction identity and current preconditions; it must not reuse partial effects because none may be authoritative.

### Phase 29 — LAST VALID COMMIT

Definition:

**LAST VALID COMMIT is the latest transaction that has a durable COMMITTED transaction record and whose authoritative child writes were atomically committed in the same SQLite transaction.**

It is not: newest event row, newest ledger row, newest file timestamp, newest chapter manifest, or newest snapshot file.

Failure matrix:

- before transaction: no state change
- after BEGIN: SQLite rollback/process recovery -> previous commit
- after any one/many authoritative child writes but before COMMIT: rollback -> previous commit
- after ledger/event write but before COMMIT: rollback -> previous commit
- during COMMIT: on reopen trust SQLite atomic transaction outcome + transaction commit marker; never count side effects independently
- immediately after successful COMMIT but before response: retry resolves as ALREADY_COMMITTED
- derived/cache/snapshot failure after COMMIT: committed reality remains; invalidate/rebuild derived artifacts

Recovery algorithm:

1. open/validate campaign DB;
2. let SQLite recover its journal/WAL transaction semantics;
3. inspect durable transaction registry, not child-table recency;
4. reject/clean noncommitted transaction metadata that cannot correspond to a committed SQLite transaction;
5. expose latest durable COMMITTED turn as reality;
6. rebuild or invalidate derived/cache/presentation state;
7. never synthesize event/ledger/history to make partial rows appear consistent.

### Phase 30 — Append-only Event Store

Create one bounded, significant-event store rather than treating every specialized history table as one giant event table.

Minimum event contract:

- `eventUid`
- `campaignUid`
- `transactionUid` and `turnUid`
- event type UID + payload schema/version
- committed/effective campaign order/time
- actor ref, optional target/location refs
- cause/provenance references
- old/new state only where semantically meaningful and bounded
- stable deterministic ordering within transaction
- immutable payload/fingerprint after commit

Store significant campaign changes only. Do not emit micro-events for meaningless reads, UI refresh, cache rebuild or every low-level SQL row.

Relationship to specialized ledgers: Event Store records **that a significant event occurred** and links to finance/ownership/project/progression evidence. It does not replace financial ledger, ownership history, project history, or progression provenance as their domain authority.

### Phase 31 — Causal Graph

Recommended architecture: **hybrid evidence + rebuildable index**.

Authoritative portion: explicitly committed causal edge assertions referencing existing committed event UIDs, with stable edge UID/type/provenance. Query/transitive-closure structures are derived indexes.

Allowed edge types: `CAUSED`, `ENABLED`, `TRIGGERED`, `PREVENTED`.

Rules:

- both event endpoints must exist unless the edge type explicitly models prevented/non-occurring outcome via a typed outcome reference; never fabricate an event for a thing that did not happen;
- stable edge identity/fingerprint and append-preserved provenance;
- replay of Event Store + causal assertions reproduces the same graph;
- deleting graph indexes must not delete Event Store/history.

### Phase 32 — runtime truth-layer enforcement

Replace table-name conventions as the primary enforcement mechanism with declared runtime data roles and capability boundaries.

Recommended mechanisms:

- registry metadata/classification for `AUTHORITATIVE`, `DERIVED`, `CACHE_INDEX`, `PRESENTATION`, plus `REFERENCE_DEFINITION` and `LEGACY_READONLY` where useful;
- authoritative writers require transaction capability;
- derived builders receive read-only authoritative repositories;
- caches/projections have explicit `rebuild()`/`invalidate()` and may be deleted in tests;
- presentation DTOs have no persistence writer path;
- legacy readers are read-only unless an explicit migration adapter records an UNKNOWN or evidence-backed mapping;
- tests fail if a derived/presentation component obtains direct authoritative writer capability.

### Phase 33 — Snapshot System

Snapshot semantics: **replay acceleration/checkpoint, not competing authority**.

A snapshot should contain a canonical serialized/materialized representation of authoritative campaign state at one committed transaction/event position, sufficient to accelerate reconstruction. It should not own facts absent from the authoritative state/Event Store/ledgers.

Required metadata:

- snapshotUid
- campaignUid
- snapshot schema/version
- base committed transactionUid/turnUid/event order
- authoritative state fingerprint
- payload/content fingerprint
- creation reason/type (`AUTO`, future manual if separately specified)
- integrity status/version dependencies

Creation occurs only after a successful committed boundary is known. A snapshot may be created post-commit; failure creates no rollback of campaign reality.

Restore/rebuild semantics: select latest compatible valid snapshot, validate fingerprint/version, replay committed events/state transitions after its boundary as applicable, then verify authoritative fingerprint. Invalid/stale snapshot is ignored/quarantined and reconstruction falls back to older snapshot or full authority/history.

Current raw `campaign.db.copyTo()` is not sufficient as the canonical Phase-33 contract because live SQLite/WAL state and concurrent writer coherence are not guaranteed by raw file copy.

### Phase 34 — automatic retention max 6

Retain at most 6 **automatic canonical snapshots per campaign**, ordered by committed snapshot boundary/metadata rather than filesystem mtime/name alone.

Retention must:

- run only after new snapshot is validated;
- never delete Event Store, ledgers or authoritative history;
- never delete manual backup artifacts under a future separate policy;
- tolerate deletion failure without corrupting campaign reality;
- prune only snapshots belonging to the same campaign and snapshot class.

The current constant `AUTO_SNAPSHOT_RETENTION = 6` is reusable policy evidence, but not sufficient until attached to Phase-33 snapshot metadata.

### Phase 35 — Canon Divergence

Minimum durable record should contain:

- divergenceUid
- campaignUid
- referenced canonFactUid / canon definition identity
- divergence kind: override/replace/suppress (and additive campaign fact where appropriate)
- replacement/override payload or reference
- source committed eventUid/transactionUid
- valid-from campaign order/time and optional closed/superseded boundary
- provenance
- World Pack/canon version observed when divergence was created, without making that version the mutable owner of campaign reality

Required evaluation:

`CURRENT CAMPAIGN REALITY = WORLD CANON + ACTIVE CAMPAIGN DIVERGENCES + COMMITTED CAMPAIGN STATE`

World Pack updates may change base canon definitions but cannot erase campaign divergences. If a referenced canon fact disappears/changes incompatibly, migration/reconciliation must surface an unresolved divergence instead of silently rebasing history.

Do not encode campaign-specific divergence by editing the base World Pack.

### Phase 36 — schema versioning / migration safety / legacy provenance

Existing good foundations:

- `rpgos_schema_migrations(migration_id, applied_at, notes)`
- sequential `ensureV*` migrations and hardening functions
- explicit alias/mapping tables for legacy stat/resource/ownership/finance evidence
- migration notes/contracts repeatedly state no legacy synthesis
- typed tables increasingly use stable UIDs, campaign scoping, FK/check/unique guards and provenance.

Phase-36 target:

- one declared current campaign-schema version/capability set;
- deterministic migration ordering from every supported old fixture;
- migration preflight (schema identity, campaign identity, integrity, available disk/backup strategy as appropriate);
- each migration atomic or safely resumable/idempotent;
- postflight foreign-key/integrity/invariant checks;
- no destructive drop/overwrite until replacement is proven and old evidence is retained where required;
- schema changes introduced by Phases 26–35 get explicit migration IDs and compatibility tests;
- unknown source/event/cause remains NULL/UNKNOWN/NOT_RECORDED, not generated pseudo-history;
- legacy rows without stable UID may receive a migration identity only when deterministic and explicitly marked `SYSTEM_MIGRATION/LEGACY`; that identity must not imply a historical event that never existed.

---

## 8. Grouping comparison

Scores use 1 = low/good risk, 5 = high/poor risk; auditability and gate quality use 1 = poor, 5 = strong.

| Option | Architecture risk | Integration risk | Debugging risk | Migration risk | Auditability | Expected gate quality | Assessment |
|---|---:|---:|---:|---:|---:|---:|---|
| **1: 26–36 one work** | 5 | 5 | 5 | 5 | 1 | 1 | Reject. Too many authority boundaries move simultaneously; failures cannot be localized. |
| **2: 26–29 / 30–32 / 33–36** | 2 | 3 | 3 | 4 | 4 | 4 | Viable but Phase36 migration work becomes entangled with still-stabilizing snapshot/divergence schema. |
| **3: 26–29 / 30–32 / 33–35 / 36** | **1** | **2** | **2** | **2** | **5** | **5** | **Recommended.** Each group has one coherent source-of-truth responsibility and Phase36 migrates settled targets. |

### Why Group C + D should not currently become 33–36

Phase 33–35 define new persisted concepts and their exact schema may change during independent review. Phase 36 is not merely “write migrations for those tables”; it is the project-wide old-campaign compatibility and provenance-safety gate. Combining them encourages migration code to track unstable schemas, hides whether a failure is snapshot/divergence semantics or legacy conversion, and weakens exact-SHA auditability.

Phase 36 may be implemented immediately after G35 in the same broad program cadence, but it should have an independent exact-SHA STOP GATE and no cross-gate shortcuts.

---

## 9. Internal STOP GATES

Every gate requires a dedicated exact-SHA candidate checkpoint and green CI before proceeding. A later gate must not be used to mask a failing invariant in an earlier gate.

### G26 — mutation-path closure

Output: campaign mutation gateway/envelope; authoritative writer capability boundary; `StatePatch` containment; writer API visibility/gating.

Required proof:

- every production authoritative gameplay write is enumerated and gated;
- PlayerDomainEngine still produces proposals only;
- finance/ownership remain their domain authorities;
- UI/AI/generic patch cannot directly commit authority;
- privileged migration/restore/package paths are explicitly excluded and separately guarded.

STOP if any known direct authoritative gameplay writer remains reachable outside the gate.

### G27 — atomic TurnTransaction

Output: transaction lifecycle/owner; typed stores join outer transaction; rollback/result contract.

Required tests: injected failure after each authoritative family write; zero partial state/event/ledger survives; nested commit prohibited; post-commit cache failure does not roll back committed turn.

STOP if any child store can independently commit part of a turn.

### G28 — durable idempotency

Output: durable transaction/turn/command idempotency registry + semantic fingerprint collision checks.

Required tests: retry committed turn -> same result / zero duplicate effects across player, finance, ownership, inventory, progression, projects and event intents; process restart between requests.

STOP if idempotency relies on process-local memory or only per-domain checks.

### G29 — crash recovery

Output: LAST VALID COMMIT marker/algorithm and startup recovery behavior.

Required tests: crash injection before/after each transaction stage; reopen DB; exact authoritative equality to last committed turn; committed-but-response-lost retry -> ALREADY_COMMITTED; derived failure after commit -> rebuild only.

STOP if recovery infers commit from child-table recency or snapshot existence.

### G30 — Event Store

Output: typed append-only significant-event schema/API linked to committed transaction/turn.

Required tests: immutable event payload/identity, stable order, no event on rollback, event and authoritative state always same transaction, no replacement of specialized ledger authority.

STOP if events can be appended independently of committed reality for gameplay mutations.

### G31 — Causal Graph

Output: typed causal edge assertions and rebuildable graph index/query layer.

Required tests: endpoints exist; no fabricated historical events; deterministic replay; delete graph index -> rebuild same graph.

STOP if graph content can create campaign facts/events by itself.

### G32 — truth-layer enforcement

Output: runtime classification/capability enforcement across authoritative/derived/cache/presentation/legacy/reference data.

Required tests: delete every declared derived/cache family -> rebuild/no truth loss; presentation mutation attempts rejected; generic patch cannot alter authority; FACT/BELIEF/NARRATIVE remain separated.

STOP if any reverse dependency from derived/presentation to authority remains.

### G33 — Snapshot System

Output: versioned integrity-checked commit-boundary snapshot contract and deterministic restore/replay path.

Required tests: snapshot + replay = full authoritative state; invalid/stale snapshot ignored; process-safe creation; delete latest snapshot and rebuild from older/full history.

STOP if snapshot is the only copy of any campaign truth.

### G34 — retention

Output: metadata-driven automatic max-6 retention.

Required tests: 7th validated auto snapshot prunes only oldest auto snapshot; manual backup/history untouched; cross-campaign isolation; failed prune harmless.

STOP if retention can remove event history/ledgers/manual backups or select by unreliable filename alone.

### G35 — Canon Divergence

Output: typed durable divergence store/resolver integrated with canon lookup and committed event provenance.

Required tests: divergence survives World Pack update; base World Pack unchanged; replay gives same reality; supersession/valid-time behavior; missing/changed canon fact surfaces unresolved state rather than silent loss.

STOP if campaign-specific reality is represented by mutating World Pack content.

### G36 — schema/migration/legacy safety

Output: current schema version/capability contract; migrations for all accepted 26–35 tables; legacy fixtures and provenance rules.

Required tests: every supported old fixture -> current valid load; migration failure rollback/resume; rerun idempotency; no fabricated event/provenance; unknown remains unknown; old campaign identities preserved; integrity checks green.

STOP if any migration invents causal/history evidence or requires destructive source deletion before validation.

---

## 10. Cross-cutting acceptance matrix

| Test ID | Scenario | Required result |
|---|---|---|
| TX-01 | failed turn after first authoritative write | rollback; zero partial mutation |
| TX-02 | failure after finance/ownership/player write | all authoritative families rollback together |
| IDEM-01 | retry committed transaction | `ALREADY_COMMITTED` / same result identity / zero duplicate effects |
| IDEM-02 | same transaction UID, different fingerprint | fail closed |
| IDEM-03 | restart process then retry | same durable idempotency result |
| REC-01 | crash after BEGIN | previous committed reality |
| REC-02 | crash after ledger/event/state child write | previous committed reality |
| REC-03 | crash immediately after COMMIT before response | committed reality; retry is idempotent |
| REC-04 | crash/failure during derived rebuild | committed reality retained; derived rebuilt later |
| EVT-01 | committed significant change | exactly expected immutable event(s), linked to transaction/turn |
| EVT-02 | rolled-back turn | zero committed events |
| EVT-03 | event replay | same authoritative result where replay contract applies |
| CAUSE-01 | causal edge references missing event | reject, no synthetic event |
| CAUSE-02 | delete causal index | deterministic rebuild |
| LAYER-01 | delete cache/index | rebuild, no data loss |
| LAYER-02 | delete presentation projection | rebuild, no data loss |
| LAYER-03 | presentation/AI attempts authoritative mutation | rejected |
| SNAP-01 | snapshot + replay | exact authoritative equality |
| SNAP-02 | delete snapshot | rebuild from history/authority; no data loss |
| SNAP-03 | corrupt/stale snapshot | ignored/quarantined; safe fallback |
| RET-01 | seventh auto snapshot | max 6 automatic snapshots, correct oldest pruned |
| RET-02 | retention | never deletes event history/manual backup/authority |
| FIN-01 | internal money transfer | conservation across accounts; ledger is authority |
| FIN-02 | retry financial effect under turn retry | no duplicate financial transaction |
| OWN-01 | unique item/ownership transfer | one legal current ownership state, append-preserved history |
| OWN-02 | retry ownership effect | same operation/result; no duplicate successor records |
| PROG-01 | committed progression | durable provenance; no unexplained permanent regression |
| PROG-02 | failed turn with progression proposal | no committed grant/evidence side effect |
| DIV-01 | campaign divergence then World Pack update | campaign divergence survives; base canon not mutated |
| DIV-02 | divergence replay | same current campaign reality |
| MIG-01 | old campaign -> migration | valid current load with stable identities |
| MIG-02 | unknown old source/cause | remains UNKNOWN/NULL/NOT_RECORDED |
| MIG-03 | migration retry | idempotent/no duplicate mappings/evidence |
| MIG-04 | migration failure mid-step | atomic rollback or safe resumable state |
| P19-REG | Phase-19 pinned World Pack behavior | unchanged |
| P20-REG | Phase-20 progression determinism | unchanged |
| P21-25-REG | accepted Player Core proposal/invariant/provenance/snapshot-profile behavior | unchanged |
| ISO-01 | two campaigns | transaction/event/snapshot/divergence/idempotency isolation |

---

## 11. Forbidden shortcuts

- Do not make `StatePatchEngine` the TurnTransaction implementation.
- Do not equate `SourceOfTruthRegistry.canWrite(table)` with domain authorization.
- Do not make `PlayerDomainEngine` the engine for all campaign domains.
- Do not make TurnTransaction a rule engine or WorldRuleProvider replacement.
- Do not replace finance/ownership specialized authority with generic Event Store rows.
- Do not let Event Store become a second mutable current-state database.
- Do not infer committed truth from latest event/ledger/file timestamp.
- Do not use process memory as idempotency authority.
- Do not treat raw whole-DB file copies as sufficient canonical snapshots while SQLite/WAL may be live.
- Do not allow snapshot deletion to delete the only history.
- Do not make Causal Graph fabricate events.
- Do not parse Chronicle/NARRATIVE back into FACT.
- Do not let CharacterPanelSnapshotV2 or PlayerSnapshot profiles mutate authority.
- Do not encode campaign divergence by editing World Pack content.
- Do not migrate missing legacy provenance into invented event/command/actor IDs.
- Do not begin Phase 37+ NPC Knowledge/Temporal/Retrieval/GM Engine work inside these gates.
- Do not expand this program into Naruto_Default cleanup or frontend redesign.

---

## 12. Blockers and required preconditions

### No Gate-26 architecture blocker

The repository already contains enough stable boundaries to begin implementation planning for Phase 26: accepted PlayerChangeSet/InvariantValidator proposal semantics, typed domain stores, stable identities in major domains, and SQLite-backed persistence.

### Required preconditions before proceeding through the program

1. **Inventory all writer APIs at G26 and freeze that inventory in tests.** The current direct/generic writer surface is broader than PlayerDomainEngine.
2. **Choose one transaction-scoped writer capability model at G26/G27.** Do not patch each store with unrelated boolean flags.
3. **Keep privileged maintenance mutations separate.** Restore/migration/package replacement are not ordinary gameplay turns.
4. **Retain specialized domain authority.** Finance ledger and ownership/project histories must not be flattened into a generic global ledger/event table.
5. **Make transaction identity durable before Event Store.** Otherwise Event Store linkage/recovery semantics are unstable.
6. **Make Phase 33 depend on G29/G30, not existing BackupManager copies.** Current chapter backup primitives are compatibility assets only.
7. **Keep G36 independent.** It must migrate accepted/stable 26–35 schemas and be audited against old campaigns after those schemas stop moving.

---

## 13. Recommended CHAT-1 implementation plan

### Program A — 26–29

Implement continuously only with hard STOP checkpoints G26, G27, G28 and G29. Primary likely files/components: new campaign mutation/transaction contracts; `UnifiedGameRepository`/`CampaignRepository`; `StatePatchEngine`; `SourceOfTruthRegistry`; typed store writer surfaces; new transaction/idempotency/recovery schema/migration and tests. Reuse existing domain transactions by converting them to join an outer transaction rather than deleting their domain checks.

Do not start Event Store schema until G29 is independently green on an exact SHA.

### Program B — 30–32

Add bounded significant Event Store and causal assertions tied to committed transaction identity, then enforce truth-layer classification/capabilities. Migrate existing source-event references forward without pretending all historical specialized rows came from a canonical Event Store.

Do not use Phase 32 as a reason to rewrite all persistence into one table/repository.

### Program C — 33–35

Build snapshots from committed reality/history, then attach max-6 automatic retention, then add canon divergence. Reuse current retention constant/policy concept, not raw copy semantics as the final snapshot contract.

Do not begin legacy migration work while snapshot/divergence schema is still being corrected within G33–G35.

### Program D — 36

After exact-SHA G35 acceptance candidate is stable, build the project-wide schema/version/migration safety layer and fixture matrix. Treat every old campaign as evidence, not material to be normalized by invented provenance.

---

## 14. Final decision

### Recommended option

**OPTION 3 — 26–29 / 30–32 / 33–35 / 36**

This best preserves the gate discipline used for Phase 21–25 while matching actual repository boundaries:

- 26–29 define what a commit is and how it survives retry/crash;
- 30–32 define durable significant history, causality, and runtime truth-layer boundaries on top of that commit;
- 33–35 define replay acceleration and canon-vs-campaign reality on top of committed history;
- 36 safely migrates old campaigns into the now-settled contracts.

### Final verdict

**READY WITH REQUIRED PRECONDITIONS**

There is no evidence supporting a single Phase 26–36 implementation batch as safer than gated grouping. There is also insufficient architectural justification to combine Phase 36 into Phase 33–35 before the new schemas and replay/divergence semantics have independently stabilized.
