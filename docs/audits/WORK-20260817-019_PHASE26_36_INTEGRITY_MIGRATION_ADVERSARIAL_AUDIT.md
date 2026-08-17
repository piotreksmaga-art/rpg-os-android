# WORK-20260817-019 — Phase 26–36 Integrity / Migration / Adversarial Pre-Implementation Audit

Status: READ-ONLY RUNTIME AUDIT

Role: CHAT-3 — independent integrity / adversarial / concurrency / migration reviewer

Repository: `piotreksmaga-art/rpg-os-android`

Branch audited: `master`

Canonical accepted Player-Core runtime through Phase 25: `c028aa355d9b7e1663166a2fedb910c1a2dad795`

Pre-audit master HEAD: `fcf51d3478efdb28f09ef5c8a4b3cfaf834477c8`

Pre-audit master CI: `Validate RPG OS ALPHA` run #619 / ID `32001653887` — `completed / success` on `fcf51d3478efdb28f09ef5c8a4b3cfaf834477c8`.

## 1. Final verdict

**READY WITH REQUIRED PRECONDITIONS**

Phase 26–36 implementation is not blocked by an already-corrupt accepted Phase-25 runtime, but it is unsafe to implement as one undifferentiated transaction/replay feature. The accepted runtime contains multiple legitimate domain authorities, multiple independent domain transactions, generic legacy/runtime mutation surfaces, non-transactional backup/restore file copying, and partially replayable historical data. These are exactly the surfaces that Phases 26–36 must constrain; they must not be hidden behind a new facade while remaining independently writable.

The implementation may proceed only with the phase/group stop gates in this report. In particular:

1. Phase 26 must inventory and close/bound every authoritative writer before Phase 27 is treated as meaningful.
2. Phase 27 must establish one real durability boundary for all writes belonging to one turn; nested domain transactions may join that boundary but may not commit independently.
3. Phase 28 must persist idempotency identities; no in-memory replay flag is sufficient.
4. Phase 29 must identify LAST VALID COMMIT from a durable commit protocol, never from snapshot timestamp, file mtime, UI state, or “latest-looking” domain row.
5. Phase 30 events must commit atomically with the state/ledger effects they describe.
6. Phase 31 causal edges must never point at uncommitted/rolled-back history and must not be synthesized for legacy history.
7. Phase 32 must enforce one-way authority flow: authority -> derived/cache/presentation, never reverse.
8. Phase 33/34 must not reuse the current raw `campaign.db.copyTo(...)` backup mechanism as an authoritative snapshot protocol without proving SQLite/WAL consistency and commit binding.
9. Phase 35 must bind campaign divergence to stable canonical identity/version context so World Pack updates cannot erase campaign history.
10. Phase 36 migration must be restartable/non-destructive and must preserve UNKNOWN as unknown rather than manufacturing causes, UIDs, or historical events.

No Phase 26–36 item is declared COMPLETE or ACCEPTED by this audit.

---

## 2. Bootstrap and drift classification

### 2.1 Mandatory documents inspected

Inspected from current master:

- `docs/PROJECT_WORK_PROTOCOL.md`
- `docs/RPG_OS_MASTER_ARCHITECTURE.md`
- `docs/RPG_OS_IMPLEMENTATION_ROADMAP.md`
- `docs/GM_ENGINE_TARGET_ARCHITECTURE.md`
- `docs/architecture/CHAT_COORDINATION_POLICY.md`
- `docs/architecture/PHASE19_ACCEPTANCE.md`
- `docs/architecture/PHASE20_ACCEPTANCE.md`
- `docs/architecture/PHASE21_25_ACCEPTANCE.md`
- `docs/PARALLEL_WORK_COORDINATION.md` for repository-write coordination/freshness context.

The canonical architecture is consistent on the critical direction of travel:

`PROPOSAL -> DOMAIN/RULE RESOLUTION -> CHANGE SET -> VALIDATION -> TRANSACTION -> EVENTS + LEDGERS + AUTHORITATIVE STATE -> COMMIT -> COMMITTED REALITY`

It also states that CharacterPanelSnapshot / PlayerSnapshot are derived, AI output is not committed reality, FACT/BELIEF/NARRATIVE stay distinct, stable UID is identity, and campaign divergence must survive canon evolution.

### 2.2 Accepted baseline

`PHASE21_25_ACCEPTANCE.md` binds Player Core acceptance to:

- runtime `c028aa355d9b7e1663166a2fedb910c1a2dad795`;
- CI #607 / ID `31968919354`, success;
- Phase 26 Single Truth Mutation Path, TurnTransaction, global retry/idempotency, crash recovery and authoritative Snapshot System explicitly remain outside accepted Phase 21–25 scope.

### 2.3 Current master drift

Comparison:

`c028aa355d9b7e1663166a2fedb910c1a2dad795..fcf51d3478efdb28f09ef5c8a4b3cfaf834477c8`

Result:

- master ahead by 12 commits;
- no runtime Kotlin delta;
- no schema/migration delta;
- no production test delta relevant to Phase 26–36 runtime;
- changed files are documentation/evidence/navigation records.

**Drift classification: DOCUMENTATION-ONLY / RUNTIME-EQUIVALENT TO ACCEPTED PHASE-25 BASELINE.**

Therefore all runtime findings in this audit are evaluated against exact accepted runtime `c028aa355...`; current master documentation is used for coordination/current-status evidence.

---

## 3. Independent Phase 26–36 classification

| Phase | Independent status | Evidence / reason |
|---|---|---|
| 26 Single Truth Mutation Path | **PARTIAL** | `SourceOfTruthRegistry` blocks generic StatePatch access to selected typed authorities and typed stores exist, but many authoritative stores remain independently writable and `StatePatchEngine` still writes a permitted table set directly in its own transaction. No global mutation capability token/transaction context enforces one path. |
| 27 TurnTransaction | **MISSING** | No canonical TurnTransaction/commit coordinator exists. Domain stores own independent SQLite transactions. Phase-25 acceptance explicitly excludes TurnTransaction. |
| 28 Idempotency + double-commit | **PARTIAL** | Finance has durable transaction UID + command UID replay checks; ownership has durable operation UID semantics; some definitions/mappings are naturally unique. Inventory/stat/resource and generic patch paths do not expose a uniform durable operation identity for gameplay mutation. No global command/transaction receipt. |
| 29 Crash recovery / LAST VALID COMMIT | **MISSING** | No durable global commit record defines the last valid turn. Backup/restore exists, but timestamps/file copies are not commit authority. Phase-19 acceptance explicitly deferred global LAST VALID COMMIT. |
| 30 Event Store append-only | **PARTIAL** | Event-like/history tables, provenance fields, sourceEventUid links and ledgers exist, but no single append-only committed Event Store contract atomically tied to turn commit exists. |
| 31 Causal Graph | **PARTIAL** | `PlayerChangeSet` carries causation/correlation/causal change references and legacy narrative tables include consequence links, but there is no committed cross-domain causal graph constrained to committed same-campaign history. |
| 32 Authority/Derived/Cache/Presentation runtime enforcement | **PARTIAL** | Source-of-truth registry, typed-only tables and accepted `DERIVED_PRESENTATION` / `DERIVED_PROJECTION` snapshots are strong foundations. Enforcement is not yet global across restore, legacy tables, caches, generic writes and future snapshot recovery. |
| 33 Snapshot System | **PARTIAL** | Backup/Chapter save infrastructure and derived player snapshots exist, but there is no commit-bound authoritative recovery snapshot protocol. Raw DB copies are not sufficient proof. |
| 34 Automatic snapshot retention max 6 | **PARTIAL** | `BackupManager.AUTO_SNAPSHOT_RETENTION = 6` and pruning exist, but selection uses filename/wall-clock + `lastModified()` and snapshots are raw DB copies. Retention is not yet proven safe relative to committed history/recovery. |
| 35 Canon Divergence | **PARTIAL** | Campaign truth/canon status, World Pack version pinning and failure-atomic package replacement provide foundations. No evidence of a complete stable fact-level divergence overlay that guarantees updated/renamed/removed canon cannot overwrite campaign-diverged reality in all consumers. |
| 36 Schema Versioning + migration safety + legacy provenance | **PARTIAL** | Many additive transactional migrations and explicit legacy mappings/evidence exist and repeatedly avoid semantic guessing. But migrations are invoked lazily from store constructors in places, no global crash/restart migration protocol is yet the canonical campaign gate, and old-history replay remains intentionally incomplete. |

### Roadmap disagreement

No categorical disagreement is required with the roadmap’s broad PARTIAL/MISSING labels for 26–36. The important refinement is that Phase 28 is **PARTIAL**, not conceptually absent, because durable idempotency already exists in finance/ownership; however those guarantees are domain-local and must not be mistaken for global turn idempotency.

---

## 4. Source-of-truth map

### 4.1 Player stats/resources

Authoritative new state: `player_stats`, `player_resources` scoped by `(campaign_id, character_uid, definition_uid)`.

Legacy compatibility: legacy stat/resource data remains readable through explicit reconciliation/alias mechanisms.

Risk: `StatResourceStore.savePlayerStat/savePlayerResource` perform direct upsert and accept caller-supplied version/value. A future TurnTransaction must own validation + write-time preconditions; legacy projection must never overwrite newer typed authority.

### 4.2 Skills / techniques / innate/evolution / equipment

Typed stores and versioned player/domain records are current authoritative families; legacy records remain compatibility evidence where explicitly retained.

Risk: each current store can commit independently; same-turn cross-domain consistency is not globally atomic.

### 4.3 Inventory

Typed authority:

- `player_inventory_stacks` for stackable quantities;
- `item_instances` + `player_inventory_unique` for unique instances.

Legacy rows may contribute only via explicit mapping/reconciled views.

Important risk: stack/unique gameplay mutation methods do not carry a global durable command/reward operation UID. Repeating `addStack(...)` after an unknown commit outcome can duplicate a reward. `transferUnique(...)` is transactional locally but its UPDATE is not checked for affected-row count after a prior source-holder read, so its correctness currently relies on the local SQLite write transaction and single writer serialization, not a global turn CAS protocol.

### 4.4 Ownership

Authoritative temporal history: `ownership_records`, with `ownership_operations` as durable operation identity for transfers/closures.

`transferShare` starts a DB transaction before authoritative reads, uses CAS close with record version, writes successors and operation record, and recognizes idempotent replay by `operationUid` while verifying immutable semantics.

This is a strong reusable pattern for Phase 27/28, but it is not a global turn transaction.

### 4.5 Finance

Authoritative finance history: `financial_ledger_transactions`.

Derived/rebuildable balance projection: `financial_account_balances`.

`FinancialStore.commit` rejects mismatched duplicate financial transaction UID, recognizes exact duplicate transaction/command replays, and ledger insertion/projection triggers execute within one domain transaction. `reconcile()` asserts ledger vs balance equality and `rebuildBalance()` rebuilds projection from ledger.

**Authority winner on disagreement: financial ledger, not balance projection.**

A Phase-32 implementation must preserve that direction; it must never “repair” the ledger from a balance row.

### 4.6 Assets / obligations / projects

Typed Phase 14/15 records, histories and work records are authoritative domain families. They are already protected from generic `StatePatchEngine` via `SourceOfTruthRegistry.TYPED_ONLY_TABLES` for the listed tables.

They still expose typed store writers outside a global TurnTransaction.

### 4.7 Campaign truth

`campaign_truth_records` is typed authority for FACT/BELIEF/NARRATIVE records. Generic StatePatch is blocked from it.

`CampaignTruthStore.record()` owns its own transaction and may supersede an older truth record + insert a new record atomically inside that domain call.

Risk for Phase 27/30: truth record + gameplay state + Event Store must eventually share one committed-turn boundary when they are effects of one turn. A standalone committed truth record cannot be allowed to describe a state change that later rolls back.

### 4.8 Progression

Phase-20/21–25 progression ledger semantics remain proposal/causal evidence unless and until a later transaction commits the corresponding typed domain effects. The accepted Phase-23 design explicitly avoided a new global writable player ledger.

Do not reinterpret proposal ledger evidence as already-committed history.

### 4.9 Derived/presentation

`CharacterPanelSnapshotV2.classification = DERIVED_PRESENTATION`.

`PlayerSnapshot.classification = DERIVED_PROJECTION`.

Both builders are read-only/pure and intentionally own no write path.

**If deletion of either loses campaign truth, Phase 32/33 is wrong.**

---

## 5. Authoritative write-surface inventory

The following are meaningful persistent mutation families observed in accepted runtime. This is an implementation audit inventory, not permission to alter them in this work item.

| Writer/surface | Data family | Authority class | Current transaction ownership | Durable idempotency | Event/provenance notes | Phase-26 concern |
|---|---|---|---|---|---|---|
| `StatePatchEngine.apply` | explicitly permitted generic runtime/narrative tables | mixed authoritative/runtime | owns local SQLite transaction | `patch.transactionId` is message text only; no observed committed receipt | registry gate only | **High**: generic direct DB writer must be narrowed or admitted only through canonical transaction context. |
| `CampaignTruthStore.record` | campaign FACT/BELIEF/NARRATIVE | authoritative | local transaction | truth UID uniqueness only; default random UID | typed provenance, optional createdEvent | Must join turn when emitted by a turn. |
| `StatResourceStore.savePlayerStat/Resource` | player stat/resource | authoritative | caller/direct DB write; helper transactions elsewhere | no global command operation receipt | version in row, no Event Store atomicity | Must be callable only from canonical mutation path for gameplay writes. |
| Skill/Technique stores | mastery/progress | authoritative | domain-local | domain-specific stable identities but no global receipt | provenance varies | Same. |
| `InventoryStore` | stack/unique inventory | authoritative | mixed direct/local transactions | no operation UID on add/remove/transfer stack | provenance string | Duplicate reward/retry risk. |
| `EquipmentStore` | equipped loadout | authoritative | domain-local | domain-specific | provenance/ownership cross-check required | Equipment + inventory/ownership same-turn effects need one transaction. |
| `OwnershipStore` | temporal ownership | authoritative | local transaction | strong `operationUid` semantic replay | requires `sourceEventUid` for transfer/close | Reuse semantics; must join global turn. |
| `FinancialStore` | ledger | authoritative | local transaction, joins an existing DB transaction if `db.inTransaction()` | strong transaction UID + optional command UID | sourceEventUid/commandUid/provenance | Strong reusable participant; ledger remains finance authority. |
| `FinancialStore.rebuildBalance` | balance projection | derived | local transaction | rebuild operation, not gameplay identity | derived from ledger | Must never be authoritative recovery source. |
| Asset/Obligation stores | assets/debts/obligations | authoritative | local typed transactions | domain-specific UIDs | histories/provenance | Must join global turn for cross-domain effects. |
| DevelopmentProjectStore | project/work/history | authoritative | local typed transactions | domain-specific work IDs | provenance/history | Must join global turn. |
| ProgressionProfileStore | talent/potential/profile mappings | authoritative profile inputs + legacy mappings | domain-local | stable mapping keys | explicit legacy evidence | Migration/player setup only unless legal turn mutation specified. |
| ModifierStore | durable modifier inputs | authoritative inputs to derived values | direct/domain-local | modifier UID | source/provenance | Derived effective values must never overwrite base authority. |
| ActivePlayerStore | active player identity | authoritative campaign selection/player ref | direct/domain-local | campaign key | legacy seed path | Must stay outside ordinary turn mutation or be explicitly transactional when changed. |
| MigrationManager / Phase migrations | schema + legacy mapping infrastructure | structural authority | migration transactions, some chained/lazy | migration_id `INSERT OR IGNORE` | notes frequently say no semantic guessing | Must not run opportunistically inside active turn after Phase36 gate. |
| BackupManager | `.db` backup files | recovery artifact, **not campaign authority** | filesystem copy | timestamp filename | no committed turn binding | Must not be promoted to Snapshot authority as-is. |
| RestoreManager | replaces `campaign.db` | extremely high-impact recovery writer | filesystem copy, not SQLite turn transaction | none | active campaign path checks | Must be coordinated with DB lifecycle/WAL and commit metadata. |
| World Pack `CanonicalPackageReplacement` | canonical package files | content authority | file rename rollback protocol under authority gate | transient UUID names | validates package generation | Separate from campaign transaction but must not erase divergence. |

### Required Phase-26 invariant

For every authoritative gameplay writer, exactly one of these must be true:

1. it is internal to the canonical TurnTransaction participant API and cannot commit independently when invoked as part of a turn;
2. it is non-gameplay administrative/migration/package lifecycle work and is explicitly unavailable during a live turn;
3. it is derived/cache/presentation and cannot write authority;
4. it is legacy read-only evidence.

If a fifth category remains (“trusted direct writer”), Phase 26 is not complete.

---

## 6. Split-brain / double-authority attacks

### SB-01 finance ledger + balance disagree

Example: ledger net = +100; balance projection = +200.

Winner: ledger.

Required NOW: `reconcile()` fail closed, never silently mutate ledger to match balance.

Future: Phase 32 recovery may rebuild balance from committed ledger only.

### SB-02 ownership says A owns item; inventory says B possesses item

These may represent different concepts (legal/title ownership versus physical possession), so they must not be auto-merged. A transfer command that intends both effects must commit both atomically or neither. If semantics require ownership==possession for a specific asset policy, enforce via explicit invariant, not inference.

### SB-03 typed skill says 5; legacy table says 4

Typed accepted authority wins when explicit legacy mapping/reconciliation says so. Legacy evidence remains evidence; never copy older presentation/legacy value back into typed authority on load/rebuild.

### SB-04 Event Store says transfer occurred; ownership did not change

Illegal for a committed event describing that authoritative effect. State/ledger/event must share one transaction commit. An event proposal may exist before commit only as non-authoritative in-memory/proposal data.

### SB-05 snapshot newer-looking than committed history

Timestamp/mtime is not authority. Snapshot must carry and verify commit identity/ordinal/state fingerprint. If it cannot bind to a committed transaction, ignore it for authoritative recovery.

### SB-06 derived panel contains value absent from authority

A derived calculated value may legitimately not be stored verbatim, but its authoritative inputs must exist. The panel value itself must never be imported back as a missing base fact.

---

## 7. Phase-27 TurnTransaction adversarial failure matrix

Required global invariant:

**A turn is visible as committed reality iff one durable transaction commit record and all authoritative state/event/ledger/causal effects for that turn are committed in the same atomic durability boundary.**

If RPG OS splits these effects across independent SQLite databases/files, ordinary nested SQLite transactions cannot provide this invariant. Phase 27 must either keep the authoritative turn write set in one atomic SQLite durability boundary or implement an explicit commit protocol whose crash semantics are proven. It must not claim atomicity across independent files by sequencing `commit()` calls.

| Injection point | Authoritative result | What must rollback | What may remain | Recovery/retry rule |
|---|---|---|---|---|
| A before BEGIN | previous committed state | nothing | proposal in memory | retry normally using same command identity |
| B after BEGIN, no writes | previous committed state | open transaction | non-authoritative proposal | rollback/close; retry |
| C after first state write | previous committed state until COMMIT | first write | nothing durable outside tx | rollback all; retry cannot see partial state |
| D after second state write | previous committed state | both writes | nothing durable outside tx | rollback all |
| E after ledger append | previous committed state | state + ledger append | nothing | ledger cannot survive alone |
| F after event append | previous committed state | state + ledger + event | nothing | event cannot survive alone |
| G after causal edge | previous committed state | all writes including edge | nothing | no dangling rolled-back causal edge |
| H immediately before COMMIT | previous committed state | entire tx on failure | nothing | retry same identities |
| I during COMMIT | **outcome may be unknown to caller** | DB engine decides atomically | no partial turn | recovery queries durable transaction receipt by `(campaignUid, transactionUid/commandUid)`; never blindly reapply |
| J immediately after COMMIT before response | new committed turn | nothing | committed state/event/ledger/receipt | retry must return existing committed result, not duplicate |
| K snapshot creation | committed turn remains authority | no turn rollback | failed/partial snapshot may be deleted | snapshot rebuild/retry; must not alter turn |
| L cache rebuild | committed turn remains authority | cache only | old cache can remain marked stale | rebuild later |
| M UI/presentation update | committed turn remains authority | UI only | stale UI | refresh from committed projection; never rollback campaign for UI failure |

### Critical crash rule

The most dangerous boundary is I/J. The client cannot infer “not committed” from a lost response. A durable transaction receipt with immutable request/change-set fingerprint is mandatory for safe retry.

---

## 8. Idempotency / retry matrix

### Required uniqueness boundaries

At minimum persist:

- `(campaign_uid, command_uid)` -> immutable command fingerprint + committed transaction UID/result;
- `(campaign_uid, transaction_uid)` -> immutable transaction/change-set fingerprint + commit ordinal/status;
- domain operation IDs for effects that can be independently referenced (`financial_transaction_uid`, `ownership operationUid`, reward/grant UID, unique-item creation operation, progression `grantUid`);
- `(campaign_uid, event_uid)` unique;
- causal edge identity unique within campaign;
- snapshot identity bound to commit identity/ordinal, not merely timestamp.

### Retry cases

| Case | Required behavior |
|---|---|
| same commandUid + same transactionUid + same fingerprint | return same committed result; zero new writes |
| same commandUid + different transactionUid | resolve to first committed command receipt or fail immutable identity conflict; never perform second turn |
| same transactionUid + different command/fingerprint | fail closed identity collision |
| different commandUid + same semantic player action | normally distinct commands unless domain semantics supply same stable operation/grant UID; if same reward/grant UID, reject/idempotently return prior effect |
| process restart after commit response lost | durable receipt proves committed; return/reconstruct result without replaying effects |
| UI double click | two calls collapse through persisted command/operation identity, not a UI boolean |
| network/API retry | same as process restart |
| replayed AI output | AI output cannot be authority; identical proposal must still pass command/transaction idempotency |
| replayed PlayerChangeSet | immutable change-set fingerprint bound to command; committed receipt prevents second application |
| replayed financial operation | existing FinancialStore behavior is reusable: exact transaction/command replay returns existing; mismatch fails |
| replayed ownership transfer | existing operationUid replay semantic verification is reusable |
| replayed inventory reward | **current gap**: addStack alone is not a durable operation; Phase28 must supply reward/operation identity before canonical turn commit |
| replayed progression gain | deterministic `grantUid` must be bound to committed transaction/effect uniqueness; proposal evaluation itself remains side-effect free |

No accepted solution may rely only on process memory, coroutine state, UI disabled state, or “last command seen” variables.

---

## 9. Concurrency / TOCTOU audit

### Current useful foundations

- SQLite serialized writer behavior is explicitly relied upon by OwnershipStore.
- ownership uses a record-version CAS when closing source records.
- finance joins an already-open DB transaction (`if (db.inTransaction()) return block()`) and otherwise creates a local transaction.
- several domain records carry version fields.

### Risks Phase 26–29 must close now

1. **Check outside canonical transaction -> write later.** Balance, ownership, inventory quantity, stat version or prerequisite checks must be revalidated inside the commit transaction.
2. **Two stores each begin/commit independently.** A coroutine interleave can expose half a logical turn even if every store method is locally atomic.
3. **Nested transaction assumptions.** A domain helper may safely “join” only if it is on the same SQLite connection and same campaign DB transaction. `db.inTransaction()` on another connection/file proves nothing.
4. **Process recreation.** Unknown commit outcome requires durable receipt, not recreation-time re-execution.
5. **Background scheduler/time-skip future.** Later workers must use the same command/transaction path; Phase26 must not leave a second “background direct writer” hole.
6. **Cross-campaign object misuse.** Every persisted identity lookup and uniqueness constraint must include/verify campaign identity unless the object is intentionally global immutable definition authority.

### Safely deferred

- high-contention multi-device distributed consensus;
- remote multi-master database replication;
- scheduler/time-skip semantics themselves (Phase 40/60), provided the canonical mutation API is designed so they cannot bypass it later.

---

## 10. Event Store adversarial requirements — Phase 30

Event Store must not become a competing reconstructed authority by accident.

Required rules:

1. A committed domain event cannot be inserted without its associated authoritative state/ledger changes when it claims those changes happened.
2. State requiring an event by the transaction contract cannot commit without that event.
3. Event UID is stable and unique within campaign; duplicate immutable content is idempotent, conflicting reuse fails closed.
4. Ordering uses a durable commit/effective ordinal with deterministic tie semantics; wall-clock alone is insufficient.
5. Event payload carries event type + schema/version. Unknown newer payload must fail explicit decode/skip policy without rewriting history.
6. Event cannot claim a rolled-back change because event insertion is inside the same TurnTransaction.
7. Specialized ledgers retain their authority semantics. Finance ledger remains finance authority; Event Store records that transaction/event happened and links it, not a second independently editable balance source.
8. Existing legacy histories that cannot be proven equivalent to canonical events stay `LEGACY_UNKNOWN/NOT_RECORDED`; Phase30 must not manufacture events from current state.

### Replay boundary

Phase30 can make **future committed turns** forward-replayable. It cannot make all pre-Phase30 history replayable without a migration baseline/snapshot, because many accepted Phase1–25 records store current authoritative state without a complete event sequence.

---

## 11. Causal Graph adversarial requirements — Phase 31

For a causal relation intended as committed historical fact:

- both endpoints must belong to same campaign;
- referenced committed event/transaction/change must exist;
- cause must be committed before or at a semantically legal earlier ordering point than effect;
- rolled-back proposal IDs are invalid endpoints;
- duplicate edge insertion is idempotent or rejected by stable edge identity;
- cycles are rejected for edge kinds declared acyclic causal ancestry;
- missing/deleted committed endpoints are integrity failure, not an invitation to synthesize replacements;
- legacy event without provenance may exist with unknown cause; do not backfill a guessed parent.

### Authority classification

Recommended distinction:

- explicit causal assertions committed with a turn: append-only historical relationship facts;
- transitive closure/index/graph traversal cache: rebuildable derived data.

Do not make a mutable graph cache the only copy of causal provenance.

---

## 12. Phase-32 authority-class attacks

### Forbidden reverse flows

- `CharacterPanelSnapshotV2 -> player_*` writes: forbidden.
- `PlayerSnapshot FULL -> player_*` writes: forbidden.
- `GM_CONTEXT truth view -> campaign FACT` promotion: forbidden unless a separate legal command establishes a FACT with provenance.
- Chronicle/narrative -> missing Event Store reconstruction as fact: forbidden.
- cache/index -> recreate deleted authoritative row: forbidden.
- snapshot -> overwrite a newer committed state: forbidden unless restore explicitly proves snapshot commit lineage and user/system recovery policy selects that commit.
- legacy presentation table -> overwrite canonical typed authority: forbidden.
- `financial_account_balances -> financial_ledger_transactions`: forbidden.

### Existing good evidence

Phase24/25 classes are strongly typed as `DERIVED_PRESENTATION` and `DERIVED_PROJECTION`, and their builders expose only read-source interfaces. Phase32 should preserve this and enforce it at repository/runtime boundaries rather than add writable snapshot persistence.

---

## 13. Snapshot / recovery attacks — Phase 33–34

### Current partial mechanism

`BackupManager.createBackup()`:

- copies live `campaign.db` directly with `File.copyTo`;
- names files with wall-clock timestamp;
- prunes automatic `chapter_*` snapshots after creation;
- keeps `AUTO_SNAPSHOT_RETENTION = 6` based on file `lastModified()` ordering.

`RestoreManager.restoreBackup()`:

- checks active campaign directory and backup containment;
- creates a pre-restore safety copy with `File.copyTo`;
- overwrites `campaign.db` with selected backup using `copyTo`.

These are useful backup foundations but **not proof of SQLite/WAL-consistent commit snapshots**. Phase19 acceptance explicitly deferred live SQLite/WAL-aware campaign snapshotting.

### Required attack outcomes

| Scenario | Required behavior |
|---|---|
| corrupt newest snapshot | detect integrity/hash/schema/commit mismatch and fall back to earlier valid snapshot + history, never load corrupt state as truth |
| missing newest snapshot | use previous valid snapshot/history; campaign truth survives |
| snapshot before turn COMMIT | it represents previous commit only and must be labeled/bound accordingly |
| snapshot after partial writes | impossible if snapshot acquisition requires committed DB view; otherwise reject |
| snapshot version mismatch | migrate through supported snapshot schema adapter or ignore/fallback; never reinterpret silently |
| snapshot from another campaign | reject by embedded campaign UID, not directory/name alone |
| old schema snapshot | validate then migrate non-destructively or fallback |
| all snapshots deleted | authoritative campaign remains usable; replay starts from baseline/current DB as architecture allows |
| retention interrupted halfway | remaining snapshot set is still valid; no truth loss |
| seventh auto snapshot | delete only the oldest **eligible recovery snapshot** after new snapshot is fully verified/durable |
| clock collision | commit ordinal/snapshot UID disambiguates; timestamp is presentation metadata only |
| same-turn multiple snapshots | deduplicate by commit identity or retain equivalent immutable snapshots without affecting truth |

### Max-6 safety condition

Max-6 is safe only if snapshots are optimization/recovery checkpoints and the authoritative committed history/baseline required after the oldest retained snapshot remains intact. Retention may never prune the sole migration baseline or sole copy of unreplayable authority.

---

## 14. Canon divergence attacks — Phase 35

Canonical required resolution model:

`CURRENT CAMPAIGN REALITY = WORLD PACK CANON BASE + EXPLICIT CAMPAIGN DIVERGENCES + LATER COMMITTED CAMPAIGN STATE`

### Primary scenario

World Pack canon: NPC `X` alive.
Campaign committed event: `X` dies.
World Pack update still/again says `X` alive.

Expected: campaign `X` remains dead. Package update cannot overwrite the divergence.

### Required identity/version rules

1. Divergence references stable canon object/fact identity, not display name.
2. Record World Pack UID and relevant version/content identity at the point divergence was established where needed for interpretation.
3. Renaming canon object preserves stable UID mapping; campaign references remain valid.
4. Removing canon object does not erase campaign entity/reference; use tombstone/legacy canonical identity support when needed.
5. Changed timeline/membership/location/technique definition cannot automatically reset an already-diverged campaign fact.
6. Multiple divergences on same fact resolve by committed campaign order/supersession semantics, not file update time.
7. Package replacement’s failure-atomic file authority does not by itself solve campaign divergence; content installation and campaign truth overlay are separate responsibilities.

### Existing foundation

`CanonicalPackageReplacement` is already hardened so failed/new package generations do not silently become canonical authority. Phase35 should reuse the pinned/validated package identity concepts rather than invent another World Pack authority path.

---

## 15. Migration / legacy adversarial map — Phase 36

### Existing positive patterns

- migrations use `rpgos_schema_migrations` stable migration IDs;
- many schema changes are additive `CREATE TABLE IF NOT EXISTS` + explicit transaction;
- legacy stat/resource reconciliation requires explicit aliases rather than key guessing;
- inventory legacy mappings reject duplicate-identical legacy rows when identity/quantity would be ambiguous;
- ownership mapping explicitly refuses to infer title from inventory/equipment/name fields;
- finance migration opening balance requires explicit legacy evidence UID;
- Phase6 legacy progression evidence/mappings preserve raw evidence rather than auto-interpreting it.

These patterns should become Phase36 policy.

### Current risks

1. Some stores call `MigrationManager().ensureV*()` in constructors. After Phase36, a live gameplay turn must not discover and execute structural migration halfway through its resolution/commit path. Campaign open should establish schema readiness first.
2. `ensureV3()` performs a structural transaction and then calls `ActivePlayerStore.seedFromLegacyIfMissing()`. Phase36 must prove crash/retry semantics across any migration step that includes post-schema data seeding.
3. Legacy current-state data is not a complete event history. Migration cannot turn it into a fabricated causal/Event Store history.
4. Historical numeric values using legacy representation must not be rounded merely to fit a new presentation type; preserve raw value/evidence or fail explicit unsupported conversion.
5. Duplicate/missing stable identities require quarantine/explicit mapping decision, not first-row-wins.

### Required conceptual tests

- old campaign no stable UID: preserve existing identity where determinable; otherwise create a **migration identity with explicit migration provenance**, never pretend it was historical UID;
- old campaign no provenance: provenance status = `UNKNOWN_NOT_RECORDED` (or canonical equivalent), not invented source event;
- legacy mastery: retain raw/mastery semantics unless explicit versioned mapping exists;
- legacy finance: explicit opening baseline/evidence only; do not manufacture transactions that “must have happened”;
- duplicate identities: fail/quarantine/mapping conflict; no silent merge;
- crash during migration: transaction rollback or restart marker resumes safely;
- rerun: same schema/data result, no duplicate mapped rows/effects;
- new app -> old DB: mandatory migration gate before normal runtime stores;
- old snapshot -> new schema: version-aware restore/migrate or reject/fallback;
- new World Pack -> old campaign: preserve campaign stable references/divergence and reject incompatible semantic remap;
- missing migration step: fail closed with diagnostic; do not skip to latest by CREATE TABLE side effects;
- destructive fallback: forbidden for valid campaign data.

---

## 16. Replay / determinism classification

### 16.1 Replayable now or structurally replayable

- finance balance from `financial_ledger_transactions` is replayable/rebuildable;
- ownership current state can be derived from its temporal ownership records/operations for the history captured by that model;
- derived CharacterPanel/PlayerSnapshot projections are rebuildable from authoritative read sources;
- deterministic progression evaluation/proposal identities are reproducible from controlled inputs captured by their accepted contracts.

### 16.2 Partially replayable

- typed player current state (stats/resources/skills/techniques/inventory/equipment) has authoritative current values but not necessarily a complete append-only history for every mutation from genesis;
- campaign truth has supersession/provenance but is not equivalent to a complete Event Store for all domain mutations;
- assets/projects have domain histories, but not a single total campaign event stream.

### 16.3 Forward-only replayable target

Phase30+ can guarantee:

`verified migration/genesis baseline or verified snapshot at commit N + committed transaction/event/ledger history N+1..M -> same authoritative state at M`

for effects brought under the new transaction/event contract.

### 16.4 Legacy unknown

Any pre-contract mutation whose cause/order/event was never recorded remains `UNKNOWN_NOT_RECORDED`. Phase36 must not fabricate an event sequence merely to satisfy a replay test.

### Replay determinism prerequisites

- stable persisted IDs, not newly generated during replay;
- versioned payload/numeric semantics;
- deterministic ordering by committed ordinal + stable tie-breaker;
- World Pack version/content identity pinned where mechanics interpretation depends on it;
- explicit migration baseline fingerprint;
- no wall-clock-dependent mechanic result;
- derived/cache/presentation builders side-effect free.

---

## 17. Grouping stress test

### Option 1 — A: 26–29 / B: 30–32 / C: 33–35 / D: 36

**Recommended.**

Why:

- 26–29 establishes the commit boundary, retry and crash identity before anything claims to be committed history.
- 30–32 then adds Event Store/causality/authority-class enforcement against a real committed transaction substrate.
- 33–35 consumes committed history for recovery and protects canon overlay without mixing schema conversion concerns.
- 36 is isolated because migration errors can irreversibly corrupt old campaigns and need dedicated exact-SHA/crash-retry validation.

Required: internal STOP gate after every individual phase even inside a group. “Group” is integration sequencing, not permission to hide a failing phase until group end.

### Option 2 — 26–29 / 30–32 / 33–36

Higher risk. Combining Phase36 with snapshots/divergence creates a temptation to use migration to compensate for defects discovered in snapshot/canon identity, and makes old-campaign failures harder to attribute.

### Option 3 — 26–36 all at once

**Reject.** Very high risk that an early writer bypass, idempotency bug or ambiguous commit marker survives until snapshot/migration tests. Exact-SHA auditability and rollback diagnosis deteriorate sharply.

### Likelihood of hidden early errors

- recommended four groups: LOWEST, provided each phase stop gate is enforced;
- 33–36 merged: MEDIUM/HIGH for migration/snapshot ambiguity;
- 26–36 one batch: HIGH/CRITICAL.

---

## 18. Internal STOP GATES for CHAT-1

### G26 — Single Truth Mutation Path

STOP if:

- any authoritative gameplay writer can still be invoked without canonical mutation/transaction context and its exception is not formally classified;
- generic StatePatch can mutate a typed authority or future event/ledger table;
- UI/AI/background worker can reach DB mutation directly;
- campaign isolation cannot be enforced at writer boundary.

### G27 — TurnTransaction

STOP if:

- failure injection after any state/ledger/event write leaves a partial committed turn;
- different physical databases/files are sequentially committed while being described as atomic without a proven commit protocol;
- nested domain transactions can independently commit inside a turn;
- commit outcome at crash boundary cannot be determined durably.

### G28 — Idempotency

STOP if:

- retry after lost response duplicates reward/spend/transfer/progression;
- same command UID can bind different immutable semantics;
- idempotency state exists only in memory.

### G29 — LAST VALID COMMIT

STOP if:

- “last valid” is selected by timestamp, snapshot mtime, highest domain row, UI state or incomplete event order;
- crash after COMMIT but before response can regress to prior turn;
- recovery can resurrect a rolled-back write.

### G30 — Event Store

STOP if state can commit without required event, event can commit without claimed state, or event replay can double specialized-ledger effects.

### G31 — Causal Graph

STOP if edge can target missing/rolled-back/cross-campaign history, or legacy causes are synthesized.

### G32 — Authority classes

STOP if any derived/cache/presentation artifact has a path that writes authority or becomes required to recover truth.

### G33 — Snapshot System

STOP if snapshot is a required source of truth, if live DB/WAL consistency is unproven, or if snapshot cannot identify exact committed state.

### G34 — retention

STOP if pruning can delete the sole usable baseline/recovery point, or ordering relies on wall clock/mtime rather than commit identity.

### G35 — canon divergence

STOP if a World Pack update can make a campaign-diverged fact revert to canon, lose identity, or become ambiguous due to rename/removal/version change.

### G36 — schema/migration

STOP before/at migration rollout if:

- valid campaign data can be destructively recreated/dropped;
- migration crash cannot safely retry;
- stable UID collision silently merges records;
- unknown provenance is replaced with invented historical cause;
- load path can run gameplay before migration gate is complete.

---

## 19. Required CHAT-1 adversarial test matrix

Each test below must have an explicit expected invariant, not merely “no exception”.

| Test | Required invariant |
|---|---|
| atomic rollback — first state write failure | zero authoritative turn effects survive; no event/ledger/receipt committed |
| atomic rollback — second state write failure | same |
| partial-write injection after ledger append | state + ledger + event + causal writes all absent after rollback |
| partial-write injection after event append | no committed orphan event |
| commit response lost + retry | exactly one committed transaction/effect set; second request returns same receipt/result |
| double commit same command+tx | exactly one commit ordinal and one copy of every domain effect |
| same command, different tx | no second effects; immutable identity conflict/original receipt returned per contract |
| duplicate reward | inventory/progression quantity increases once only |
| duplicate spend | financial debit occurs once only |
| duplicate ownership transfer | one operation/history transition only; replay returns same result |
| cross-campaign UID misuse | fail before write; zero rows in either campaign change |
| ledger/state equality | finance calculated ledger balance equals projection after every commit/rebuild; ledger wins on synthetic projection corruption |
| event/state equality | every committed event claiming authoritative effect maps to committed transaction/effect; no orphan either direction where event required |
| causal dangling reference | insert rejected transactionally; no edge committed |
| causal cross-campaign edge | rejected |
| causal cycle for acyclic edge kind | rejected with zero partial graph mutation |
| process death during COMMIT boundary | reopen resolves either old complete commit set or new complete commit set, never hybrid |
| LAST VALID COMMIT response-loss | committed turn remains last valid; retry does not regress |
| snapshot corruption fallback | corrupt newest ignored; older verified snapshot + history reaches same authoritative state |
| snapshot delete/rebuild | deletion loses no campaign truth; rebuilt snapshot fingerprint equals expected committed projection |
| snapshot wrong campaign | rejected before restore |
| snapshot old schema | deterministic migrate/fallback; no silent reinterpretation |
| retention max 6 | after verified seventh creation, six eligible auto snapshots remain; manual/required baselines unaffected |
| retention interrupted | remaining artifacts valid and authoritative DB/history untouched |
| same timestamp snapshots | commit identity preserves deterministic order/selection |
| canon divergence after World Pack update | campaign-diverged fact remains unchanged |
| canon object rename | stable UID retains campaign reference/divergence |
| canon object removal | campaign reference remains recoverable/tombstoned, not silently dropped |
| multiple divergences same fact | committed supersession/order deterministically selects current campaign reality |
| migration crash/retry | final schema/data exactly equals clean single migration; no duplicate mappings/effects |
| legacy missing provenance | stays explicit UNKNOWN_NOT_RECORDED; no synthetic event/cause |
| legacy mastery | raw semantics/value preserved unless explicit mapping exists |
| legacy finance | only explicitly evidenced opening baseline created; no invented transaction chain |
| duplicate legacy identity | fail/quarantine/explicit mapping required; no first-row-wins merge |
| old campaign valid load | after migration, authoritative values/UIDs equal pre-migration meaning and campaign is usable |
| schema upgrade gap | missing step fails closed, does not silently skip |
| no presentation->authority flow | mutate/delete/rebuild panel/snapshot/cache cannot alter authoritative tables |
| replay snapshot+history | verified snapshot at N + commits N+1..M yields same authoritative state/fingerprints as live M |
| forward replay determinism | same baseline + same committed payload/version/World Pack bindings -> same authoritative state |

### Tests that should fail before implementation

At least these cross-cutting guarantees do not exist globally in accepted Phase25 and should be red/absent until their owning phase is implemented:

- failed turn -> rollback -> no partial cross-domain mutation;
- retry transaction -> no duplicate effects across all domains;
- simulated crash -> LAST VALID COMMIT;
- Event Store/state atomic equality;
- causal noncommitted endpoint rejection at committed graph layer;
- WAL-safe commit-bound snapshot recovery;
- retention based on committed snapshot lineage rather than mtime;
- canon divergence survives a real World Pack update across all consumers;
- crash/restart migration gate across full current schema chain.

Do not weaken these tests to fit current partial mechanisms.

---

## 20. Concrete blockers / preconditions

### PRE-26-01 — complete writer inventory is acceptance evidence

CHAT-1 must prove all production persistent mutation entry points are classified. A new TransactionRepository alone is insufficient if direct stores remain generally reachable.

### PRE-27-01 — one atomic durability boundary

Before calling Phase27 complete, coordinator/CHAT-1 must explicitly establish whether all turn-authoritative writes live on the same SQLite transaction connection/database. If not, a real crash-safe commit protocol is required. Sequential commits are not TurnTransaction.

### PRE-28-01 — inventory/reward operation identity

Canonical reward/quantity mutation must gain durable operation identity at transaction/effect level. Current `InventoryStore.addStack` cannot by itself distinguish retry from intentional second grant.

### PRE-29-01 — commit receipt precedes recovery algorithm

LAST VALID COMMIT requires a durable immutable committed transaction record/ordinal/fingerprint. It cannot be built correctly from backups first.

### PRE-33-01 — current raw DB copy is not Snapshot System

`BackupManager`/`RestoreManager` must not be promoted by renaming. WAL/live-DB coherence, commit binding, integrity verification and restore synchronization must be proven.

### PRE-35-01 — divergence fact identity

Campaign divergence must have stable object/fact identity/version binding before World Pack update compatibility can be accepted.

### PRE-36-01 — no lazy migration during gameplay

A canonical “campaign schema ready” gate must complete before normal authoritative gameplay writes. Store constructor `ensureV*()` calls may remain defensive only if they are proven no-op after gate and cannot perform semantic migration mid-turn.

---

## 21. Recommendation to CHAT-1 / coordinator

Use the grouping:

**Group A: 26 -> 27 -> 28 -> 29**

then exact-SHA independent audit.

**Group B: 30 -> 31 -> 32**

then exact-SHA independent audit.

**Group C: 33 -> 34 -> 35**

then exact-SHA independent audit.

**Group D: 36 alone**

with migration fixture matrix, crash/retry and old-campaign validation on exact SHA.

Within every group, enforce the per-phase STOP gate. Phase N+1 must not conceal or compensate for a failure in Phase N.

Phase 33/34 must not be used to paper over a defective Phase29 commit model. Phase36 must not manufacture history to make Phase30/31 replay appear complete.

---

## 22. Audit conclusion

The accepted Phase-25 runtime is a viable base for Transactional Campaign Core work. It already has useful integrity primitives: typed authority separation, finance ledger/projection reconciliation, ownership CAS + durable operation identity, deterministic proposal/progression contracts, read-only derived player snapshots, explicit legacy mapping patterns and hardened World Pack package replacement.

The same runtime also demonstrates why Phases 26–36 are necessary: authoritative gameplay writes are distributed across independently callable stores, generic StatePatch remains a persistent mutation mechanism for selected tables, crash-global commit identity does not exist, event/causal history is not globally atomic with domain state, backup retention is based on raw file copies and wall-clock metadata, and old campaigns are not fully replayable from historical events.

Those are pre-implementation conditions, not evidence of accepted campaign corruption.

**FINAL VERDICT: READY WITH REQUIRED PRECONDITIONS**

**PHASES 26–36: NOT COMPLETE / NOT ACCEPTED.**
