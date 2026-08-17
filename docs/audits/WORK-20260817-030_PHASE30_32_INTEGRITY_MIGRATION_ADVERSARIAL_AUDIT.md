# WORK-20260817-030 — Phase 30–32 Integrity / Source-of-Truth / Migration Adversarial Audit

**Role:** CHAT-3 — independent integrity / source-of-truth / migration / adversarial auditor  
**Mode:** READ-ONLY RUNTIME; evidence-only report commit  
**Repository:** `piotreksmaga-art/rpg-os-android`  
**Canonical accepted runtime through Phase 29:** `45ff53457bff16c4ff72a4cccdecac89124109c3`  
**Audited master before this evidence-only commit:** `fdd30e864789edabc1baa55eaa8b86db2aa24ded`  
**Scope:** feasibility and integrity boundaries for Phase 30 Event Store, Phase 31 Causal Graph, Phase 32 Authoritative / Derived / Cache / Presentation enforcement.  
**Acceptance status:** Phase 1–29 remain accepted. This report does **not** accept or start Phase 30, 31, or 32.

---

## 1. Final verdict

# READY WITH REQUIRED PRECONDITIONS

The repository has a credible implementation path for Phase 30–32 without violating the accepted Phase 26–29 transaction/source-of-truth boundaries, but only if the hard preconditions and STOP gates in this report are made implementation invariants rather than conventions.

There is no evidence that current `master` has runtime drift from the accepted Phase-29 SHA: the `app` tree and `backend` tree at `master` match the accepted runtime tree SHAs inspected during this audit. The drift after `45ff53457bff16c4ff72a4cccdecac89124109c3` is documentation-only at the inspected head `fdd30e864789edabc1baa55eaa8b86db2aa24ded`.

The most important feasibility fact is that the accepted runtime already has the correct atomicity anchor: `TurnTransaction`. It commits canonical domain mutation and `turn_transaction_receipts` in one SQLite transaction, and current `CanonicalPlayerChangeApplier.preflight()` rejects non-empty `eventIntents`. Therefore Phase 30 can be added without creating a second commit rail only if event append becomes a participant in that same transaction. A separate post-commit Event Store writer, asynchronous append, or independent event transaction would violate the accepted integrity model.

The most important migration fact is that pre-Phase-30 history is not fully reconstructable. Existing receipts, finance ledgers, ownership temporal records, progression provenance/evidence, `chapter_events`, chapter artifacts and `consequence_links` preserve different and incomplete semantics. They do not jointly prove complete historical Event/Cause/Actor/Provenance tuples. Canonical Event Store and Causal Graph therefore require a forward-only activation boundary. No migration may invent historical events or causal edges to make a new schema look complete.

The most important Phase-31 fact is that `consequence_links` are not sufficient evidence for canonical causality. They can at most be treated as legacy relation/scaffolding unless an individual record already carries explicit causal semantics and evidence. Temporal order, narrative association, transaction co-membership and retrieval co-occurrence must never be promoted to `CAUSES`.

The most important Phase-32 fact is that the repository already contains structural mutation barriers — fail-closed `StatePatchEngine`, a `CampaignRepository` surface that does not expose the writable campaign DB for gameplay, and DB triggers in `GameplayMutationDatabaseGuards` for known authoritative tables — but the enforcement set is finite. Phase 32 must classify and structurally gate every new Phase-30/31 storage family and every derived/cache/presentation surface. Naming and developer discipline are not sufficient.

---

## 2. Evidence base and runtime drift check

### 2.1 Canonical/documentation sources inspected

Current repository sources inspected include:

- `docs/RPG_OS_MASTER_ARCHITECTURE.md`
- `docs/RPG_OS_IMPLEMENTATION_ROADMAP.md`
- `docs/PROJECT_WORK_PROTOCOL.md`
- `docs/architecture/CHAT_COORDINATION_POLICY.md`
- `docs/PARALLEL_WORK_COORDINATION.md`
- Phase 26–29 final architectural/revalidation audit artifacts under `docs/audits/`

The MASTER architecture explicitly defines four storage/runtime classes:

1. **Authoritative State** — controlled writable state.
2. **Derived State** — rebuildable from authority.
3. **Cache** — disposable acceleration/storage optimization.
4. **Presentation** — display-only projection with no persistence authority.

It also defines Event Store as append-only history but does not permit events to replace authoritative domain state. This distinction is mandatory for Phase 30 and Phase 32.

### 2.2 Accepted runtime code inspected at exact SHA

The audit inspected relevant files at `45ff53457bff16c4ff72a4cccdecac89124109c3`, including:

- `TurnTransaction.kt`
- `TurnTransactionReceiptStore.kt`
- `GameplayMutationGate.kt`
- `StatePatchEngine.kt`
- `GameRepository.kt`
- `SourceOfTruthRegistry.kt`
- `PlayerChangeSetModel.kt`
- `FinancialStore.kt`
- `OwnershipStore.kt`
- `CampaignTruthStore.kt`
- `PlayerLedgerProvenance.kt`
- `ProgressionLedgerIntent.kt`
- `CharacterPanelSnapshotV2.kt`
- `PlayerSnapshotBuilder.kt`
- `ContextBuilder.kt`
- `ChapterSaveManager.kt`
- `LocalGameStore.kt`
- `MigrationManager.kt`

### 2.3 Runtime-relevant drift

At the audit point, `master` was `fdd30e864789edabc1baa55eaa8b86db2aa24ded`.

Repository-tree inspection found the same runtime subtree identities at accepted SHA and current master:

- `app` tree: `9334b08cad537962e9c262d4d9f1f211cd2face1`
- `backend` tree: `56a349fa5eebd27ab0fabbb48eee709e21b335b0`

Therefore the inspected runtime-relevant master state is byte-tree-identical to the accepted Phase-29 runtime for these two runtime roots. The observed post-acceptance drift is documentation-only. This report is added as evidence only and does not alter that runtime conclusion.

---

## 3. Threat model findings

### BLOCKER

**None in the currently accepted runtime.**

This means Phase 30–32 are feasible to implement. It does **not** mean implementation may begin without the HIGH preconditions below. Failure to satisfy any HIGH item at its gate converts that gate into a blocker for proceeding to the next phase.

### HIGH

**H-01 — Event Store can become a second source of truth unless its authority is narrowly defined.**  
Event Store may be authoritative only for the proposition “these event records were durably committed.” It must not become an independently writable mirror of inventory, finance, ownership, truth, progression or other domain state. Domain stores remain authoritative for current domain state. Event replay must not silently overwrite current authority unless a future explicitly designed reconstruction mode proves that domain family replay-complete.

**H-02 — Event append outside `TurnTransaction` creates partial-commit and retry divergence.**  
If domain state commits and event append happens afterward, a crash can produce domain effect + receipt without event. If an event commits before domain state, the reverse divergence occurs. If retry is receipt-idempotent but event append is separately retried, duplicate events can be created. Required event rows must participate in the exact same SQLite transaction as domain effects and the receipt.

**H-03 — Legacy history is incomplete; historical Event/Cause/Actor/Provenance backfill would fabricate facts.**  
The current system stores different slices of history but no complete canonical event stream. Migration must be additive and forward-only from a declared activation point. Unknown historical properties must remain unknown/not recorded.

**H-04 — `consequence_links` cannot be directly promoted to canonical causal edges.**  
Existing link naming/association is insufficient to prove causation. Direct reuse as `CAUSES` would allow narrative/temporal/association semantics to become facts. Adapt only as explicitly typed legacy relation data or leave outside the canonical Causal Graph.

**H-05 — Causality must be typed and evidence-bearing; transaction/time/retrieval proximity are not causation.**  
Without a closed relation taxonomy and anti-promotion rules, Phase 31 can produce `before → caused-by`, `same transaction → caused-by`, retrieval co-occurrence → cause, or narrative association → fact. `CAUSES` must require explicit supported assertion; it must never be inferred from adjacency alone.

**H-06 — Downgrade/reopen can break mandatory event invariants after Phase-30 activation.**  
Once a database requires event append for canonical mutations, an older binary that can reopen and mutate that database could commit domain state without corresponding events. The implementation needs a compatibility/version policy that fails closed or otherwise prevents old writers from mutating a post-Phase-30 database.

**H-07 — Phase-32 enforcement must be structural and exhaustive, not registry-by-convention.**  
`GameplayMutationDatabaseGuards` protects an explicit table list. New authoritative/Event/Causal tables and any tables reclassified by Phase 32 must be reviewed against that list and against administrative authority. A table omitted from enforcement can become a bypass. A derived/cache/presentation writer that receives an authoritative DB handle can also become an overwrite path unless structurally prohibited.

**H-08 — Cross-campaign references require hard validation in Event Store and Causal Graph.**  
Event references, evidence references and graph edges must not be able to join records from different campaigns merely because IDs exist. Campaign scope must be part of identity/validation, and graph endpoints must agree on campaign.

**H-09 — Receipt/event/domain-ledger disagreement needs an invariant, not only diagnostics.**  
A receipt proves an accepted command/turn commit, but it is not itself the event stream. Phase 30 must define what event set is required for a successful mutation and make disagreement impossible at write time. Detection on reopen is still required for corruption/legacy mismatches, but normal code must not be able to create the disagreement.

### MEDIUM

**M-01 — `chapter_events` creates a semantic naming collision.**  
It is a legacy chapter/narrative event surface and must not be merged wholesale into canonical Event Store or treated as equivalent merely because both use the word “event.”

**M-02 — Chapter summaries/manifests are persisted artifacts, not domain authority.**  
They may be durable and not always perfectly rebuildable from remaining text, but they still must not drive authoritative mutation or prove causation.

**M-03 — `CharacterPanelSnapshotV2` and `PlayerSnapshotBuilder` can be mistaken for Phase-33 authoritative snapshots by name.**  
Current behavior is read/derived. Phase 32/33 documentation and runtime contracts must preserve that distinction.

**M-04 — Causal edge correction should not mutate history in place.**  
If a previously asserted causal edge is later invalidated, correction should be represented by a superseding/retraction assertion or equivalent append-only state transition, not destructive mutation of historical evidence.

**M-05 — Administrative mutation authority is necessary but dangerous.**  
Migration/install/recovery has an explicit ADMIN capability. Phase-30/31 tables and invariants need equally strict administrative rules so recovery cannot silently bypass append-only or source-of-truth guarantees.

**M-06 — Derived/cache deletion and rebuild must be executable, not theoretical.**  
Phase 32 should prove classification by deleting/rebuilding derived/cache state and showing unchanged authority. If deletion changes authoritative behavior, the item was misclassified.

---

## 4. Phase 30 — Event Store adversarial audit

### 4.1 Existing history / ledger / receipt structures

| Existing structure | Current semantic owner | What it proves | Reuse for Phase 30 | MUST NOT be merged/treated as |
|---|---|---|---|---|
| `turn_transaction_receipts` | `TurnTransactionReceiptStore` | idempotent canonical turn/command completion and stored result identity | reference from/compare with event commit; optionally bind event-set metadata/digest | Event Store, domain state, full mutation history |
| finance ledger / transactions | `FinancialStore` | authoritative finance-domain accounting/history | events may reference finance transaction/ledger IDs | generic Event Store or replacement for finance authority |
| ownership current + temporal/operations | `OwnershipStore` | authoritative ownership state and domain-specific temporal history | events may reference ownership operation/history IDs | generic Event Store or universal causality |
| progression ledger/evidence/provenance | progression stores / provenance model | progression-domain evidence and provenance | events may reference committed progression evidence/entries | generic event history, causal truth outside its captured semantics |
| campaign truth records | `CampaignTruthStore` | typed campaign knowledge/truth with FACT/BELIEF/NARRATIVE semantics | event may describe the committed truth mutation and reference record | Event Store as truth authority; narrative/belief promotion |
| `chapter_events` | legacy/local chapter storage | chapter/narrative event records | legacy adapter/read-only historical context if explicitly typed | canonical events by name equivalence |
| `consequence_links` | legacy/local relation storage | a stored association/link according to legacy semantics | at most legacy relation scaffold | canonical `CAUSES` edges |
| chapter summaries/manifests | `ChapterSaveManager` / local storage | persisted chapter artifact/manifest state | event can reference finalization artifact if canonical command requires it | domain authority, causal evidence by itself |
| `eventIntents` in change-set model | proposal/intention contract | requested event intent before durable commit | suitable input to Phase-30 preflight after semantics are defined | proof that an event happened before commit |

### 4.2 Explicit questions

#### Could a unified Event Store accidentally become another authority?

**Yes. HIGH risk.**

The failure mode is any design where current inventory/finance/ownership/truth/progression state is computed from Event Store and then written back over the existing domain authority without an explicit reconstruction contract. A generic “all changes are events” model is not enough to make the event stream the owner of every domain. The MASTER architecture already forbids this by separating append-only history from authoritative domain state.

Required rule: Event Store is authoritative for committed event records and their provenance only; current domain state remains owned by existing domain stores unless a future separately accepted phase explicitly changes ownership for a named state family.

#### Could an event be committed without its domain effect?

**Yes if Event Store uses a separate transaction or pre-commit writer.**

Required prevention: event append and its required domain effect must share the ambient `TurnTransaction` SQLite transaction. If domain preflight/apply fails, event append must roll back. If event append fails, domain changes and receipt must roll back.

#### Could a domain effect commit without a required event?

**Yes if event append is post-commit, asynchronous, optional by default, or omitted from a mutation type.**

Required prevention: Phase 30 must define which admitted gameplay changes require which event intents before apply. Required events are part of transaction validation. There must be no “best effort event logging” after successful domain commit.

#### Could retry duplicate events while receipt remains idempotent?

**Yes with independent append.**

A lost response can cause the caller to retry. Current receipt logic can return the prior committed result, but a separate event writer could append again. Required prevention is twofold:

1. receipt lookup remains before new mutation/event application; and
2. event identity/order is stable and uniqueness-constrained within campaign/turn/command semantics so duplicate append cannot occur even under an erroneous retry path.

A practical contract is deterministic event identity or a unique `(campaign, turn/command identity, event ordinal)` relationship established during preflight. Exact schema is an implementation choice; uniqueness semantics are not optional.

#### Could migration invent events for legacy state?

**Yes, and it must not.**

Current state can often prove that something is true now, but not when, by whom, in what exact command, for what cause, or through what complete sequence it became true. A migration that emits synthetic historical events from present rows would convert absence of history into false history.

Required rule: pre-activation state stays legacy state. The Event Store starts at a declared activation boundary. Historical rows may remain referenceable as legacy records, but no synthetic Event/Cause/Actor/Provenance value is generated solely to satisfy a new schema.

#### What is the safest atomic integration with `TurnTransaction`?

The safest order is:

1. resolve/validate campaign and transaction identity;
2. check existing receipt; on exact semantic match, return the stored result without running event append again;
3. preflight the entire domain mutation **and** all required event intents, including campaign scope, target references and deterministic event identity/order;
4. enter the existing canonical gameplay mutation capability inside the same SQLite transaction;
5. apply authoritative domain mutations and domain-specific ledgers/provenance;
6. append the canonical Event Store records required by that admitted change;
7. insert the transaction receipt, preferably with enough event-set identity metadata to make receipt/event disagreement detectable;
8. mark the outer transaction successful and commit;
9. only after commit, invalidate/rebuild derived read models or publish non-authoritative notifications.

Any failure before step 8 rolls back domain effects, ledgers, events and receipt together. No Event Store append belongs after the durable commit boundary.

### 4.3 Append-only semantics

Canonical Event Store rows must be immutable to ordinary runtime gameplay. Corrections are new records (supersede/retract/correct semantics), not UPDATE/DELETE of history. Migration/recovery authority must not have a routine path that rewrites history. Any exceptional disaster-repair path must be explicitly outside normal gameplay and auditable.

“Append-only” must include payload and provenance, not just event ID. An UPDATE that changes payload while preserving ID is still history mutation and must be prohibited.

---

## 5. Phase 31 — Causal Graph adversarial audit

### 5.1 Relation taxonomy must be semantic, not inferred

At minimum the graph/model must distinguish the following relation families:

| Relation family | Meaning | May imply causation? | Required anti-promotion rule |
|---|---|---:|---|
| **CAUSAL** (`CAUSES` or equivalent) | source is explicitly asserted to have caused target/effect | yes, only by definition and with evidence/provenance | never infer from time, transaction, narrative, retrieval or co-occurrence |
| **PROVENANCE** | record/assertion originates from a source/receipt/ledger/evidence item | no | origin is not cause |
| **EVIDENCE** | evidence supports/contradicts a claim | no | evidence relation does not automatically promote BELIEF/NARRATIVE to FACT |
| **TEMPORAL** | before/after/same interval/order | no | `before` is never converted to `caused-by` |
| **NARRATIVE ASSOCIATION** | story/editorial relation or chapter association | no | narrative adjacency is not factual causation |
| **DERIVED** | deterministic/read-model derivation dependency | no | derivation is not real-world/domain causation unless separately asserted |
| **RETRIEVAL** | co-retrieved/similar/relevant in context search | no | retrieval score/co-occurrence can never produce canonical cause |

### 5.2 Required contamination barriers

The following promotions must be structurally impossible without a new explicit authoritative assertion supported by the correct owner:

- `FACT → CAUSE` merely because a fact exists.
- `BELIEF → FACT` through graph insertion or retrieval.
- `NARRATIVE → FACT` through chapter/event association.
- `before → caused-by`.
- `same transaction → caused-by`.
- `same chapter → caused-by`.
- `retrieved together → caused-by`.
- `same actor/entity → caused-by`.

`CampaignTruthStore` already preserves FACT/BELIEF/NARRATIVE distinctions. Causal graph ingestion/read APIs must carry those semantics rather than flattening all references into undifferentiated “facts.”

### 5.3 `consequence_links` assessment

**Verdict: legacy/scaffolding requiring adaptation; not authoritative enough for direct canonical causal reuse.**

The existence of a legacy consequence association is evidence that the old system stored a relation. It is not sufficient evidence that the relation satisfied the new causal contract, carried causal proof, distinguished narrative from fact, or captured provenance/actor semantics required by Phase 31.

Safe handling:

- do not bulk-map existing `consequence_links` to `CAUSES`;
- if retained for compatibility, expose them as an explicitly legacy/unknown relation class;
- only an individual legacy link with independently stored explicit semantics/evidence sufficient for the new contract could be adapted to a stronger typed edge, and no missing fields may be guessed;
- default migration of canonical causal graph is forward-only.

### 5.4 Canonical causal assertion minimum contract

Without prescribing a specific SQL schema, a canonical edge needs at least:

- stable edge identity;
- campaign identity;
- explicit relation type;
- typed source reference and typed target reference;
- provenance/assertion source;
- evidence references where the relation requires evidence;
- creation/commit identity;
- semantics for correction/retraction without destructive history edit.

Both endpoints and referenced evidence must be campaign-consistent. Dangling references must either be rejected for canonical edges or represented with an explicitly non-canonical/unresolved status that cannot be read as established cause.

### 5.5 Forward-only migration semantics

No historical cause may be guessed.

At Phase-31 activation:

- existing domain records remain valid according to their current owners;
- legacy links remain legacy links unless individually provable;
- the canonical causal graph begins accepting new explicit relation assertions;
- no inferred cause is generated from event order, receipts, chapter order, ledger sequence or timestamps;
- absence of a historical causal edge means **unknown/not recorded**, not “no cause existed.”

---

## 6. Phase 32 — Source-of-truth matrix

The matrix below is the required ownership contract Phase 32 must enforce. “Legal deletion” means ordinary runtime semantics, not destructive disaster recovery.

| State family | OWNER | WRITE PATH | READ PATH | PERSISTENCE | REBUILDABILITY | LEGAL DELETION | MUTATION CAPABILITY | TRANSACTION BOUNDARY | LEGACY STATUS |
|---|---|---|---|---|---|---|---|---|---|
| Package/core/world definitions | package/content authority | package install/version/import only | package repositories / read-only DB handles | package DB/assets | not from campaign state | package replacement/version policy | no gameplay mutation | outside turn transaction | canonical pre-30 authority |
| Core player/domain state (stats/resources/skills/techniques/equipment/inventory/projects) | respective domain stores | typed canonical mutation | domain stores / snapshot builders | campaign SQLite | generally not safely rebuildable from pre-30 event history | only domain-defined operations | `TURN` or explicit `ADMIN` | `TurnTransaction` for gameplay | accepted Phase 26–29 |
| Campaign truth FACT/BELIEF/NARRATIVE | `CampaignTruthStore` | typed truth mutation | truth/context/read models | campaign SQLite | not generic-event rebuildable | domain-defined | `TURN`/`ADMIN` as applicable | canonical transaction for gameplay | accepted; preserve type separation |
| Finance current/accounting ledger | `FinancialStore` | finance domain operations | finance reads/context | campaign SQLite | ledger may reconstruct finance slices, not generic world state | accounting/domain semantics only | `TURN`/`ADMIN` | ambient canonical transaction | accepted domain authority/history |
| Ownership current + temporal/operations | `OwnershipStore` | ownership domain operations | ownership reads/context | campaign SQLite | ownership history supports its domain only | domain semantics only; historical rows not casually deleted | `TURN`/`ADMIN` | ambient canonical transaction | accepted domain authority/history |
| Progression ledger/evidence/provenance | progression owner | typed progression apply/evidence path | progression/read models | campaign SQLite | partial within progression semantics | append/history rules; no arbitrary erase | canonical mutation/admin recovery | same transaction as represented change where required | accepted domain evidence/history |
| Transaction receipts | `TurnTransactionReceiptStore` | `TurnTransaction` only | idempotency/reopen diagnostics | campaign SQLite | not derivable after deletion without losing idempotency proof | no ordinary gameplay deletion | internal transaction writer/admin repair only | exact same transaction as domain commit | accepted Phase 26–29 commit evidence, **not domain authority** |
| `chapter_events` | legacy chapter/local store | legacy chapter workflow | chapter/history reads | campaign/local SQLite | not guaranteed from current domain state | legacy policy | legacy writer, not canonical domain writer | separate chapter/save boundary | legacy narrative/event surface |
| `consequence_links` | legacy relation store | legacy workflow | legacy relation reads | campaign/local SQLite | not safely derivable as canonical causes | legacy policy | legacy writer | legacy boundary | legacy relation scaffold; **not canonical cause** |
| Chapter summaries/manifests | `ChapterSaveManager` / artifact owner | chapter finalization/save | context/chapter UI | persisted artifact/DB/file metadata | may be only partially rebuildable depending retained source | artifact lifecycle only | chapter artifact writer, no domain capability | separate finalization boundary | persisted narrative/artifact; non-domain authority |
| `CharacterPanelSnapshotV2` | derived builder | **no authoritative write** | UI/status/context consumers | ephemeral/object (unless separately serialized as presentation artifact) | yes from authority | always disposable | none | read boundary only | derived/presentation |
| `PlayerSnapshotBuilder` output | derived builder | **no authoritative write** | UI/context/status | ephemeral | yes | always disposable | none | read boundary only | derived |
| Context/read models | `ContextBuilder` and readers | **no authoritative write** | runtime prompt/UI | ephemeral/derived | yes from authoritative stores plus persisted narrative artifacts | disposable | none | read boundary only | derived/retrieval |
| Caches | cache owner | cache population only | optimized reads | optional | yes | always disposable | must not possess authority mutation capability | outside authority commit; invalidate after commit | cache |
| Presentation models/UI | presentation owner | display state only | UI | ephemeral or presentation persistence only | yes/replaceable | disposable | **none for authority** | outside domain transaction | presentation |
| Future Event Store | Event Store component | append by `TurnTransaction` for canonical gameplay; tightly scoped admin recovery | history/audit/Phase-31 references | campaign SQLite | authoritative only for committed event history; not a universal source for current domain reconstruction | no ordinary UPDATE/DELETE; corrections append | append capability only | same transaction as required domain effect + receipt | forward-only from Phase-30 activation |
| Future Causal Graph | Causal Graph component | explicit typed assertion path; when command-required, inside canonical transaction | causal queries/retrieval with typed semantics | campaign SQLite | graph record itself is historical assertion; may derive views/indexes | no destructive historical correction; supersede/retract | typed edge append capability only | same canonical transaction when edge is required consequence of command; otherwise explicit dedicated authoritative assertion transaction | forward-only from Phase-31 activation |

### 6.1 Writer-path attacks

#### `CharacterPanelSnapshotV2`

Current inspected implementation is a read/computed snapshot. It must remain unable to acquire an authoritative mutation capability. A stale panel submitted back to persistence must be rejected or treated as a new validated command, never as an overwrite snapshot.

#### `PlayerSnapshotBuilder`

Current inspected implementation composes state from owners and does not write. Its name must not be used in Phase 33 to justify writing a whole snapshot over newer domain state.

#### Context/read models

`ContextBuilder` is a retrieval/assembly surface. Retrieval results, missing-data fallbacks, ranking and narrative context must not mutate authority. “Model saw it in context” is not provenance for a domain fact or cause.

#### Chapter summaries/manifests

Persistence does not make them domain authority. They can be durable narrative/artifact records while still being forbidden from overwriting campaign truth, ownership, finance, inventory or event history.

#### Transaction receipts

Receipts are authoritative evidence that an admitted transaction identity committed and are part of idempotency, but they are not the owner of the domain values that transaction changed. A receipt payload cannot be replayed as a generic state overwrite unless the domain contract explicitly provides such a recovery method.

#### Future Event Store

Must have append capability, not generic CRUD. It must not expose a generic UPDATE/DELETE path to normal runtime and must not become a shortcut around typed domain mutation.

#### Future Causal Graph

Must own explicit relation assertions only. A causal edge must not write the underlying FACT/BELIEF/NARRATIVE record or target domain state. Graph query results are read models; they cannot become mutation commands without new validation by the correct owner.

### 6.2 Existing structural enforcement and gaps to close

Positive accepted-runtime evidence:

- `StatePatchEngine` fails closed for generic gameplay state patching.
- `CampaignRepository` exposes `commitTurn` as the sole supported normal gameplay durable mutation entry and deliberately does not expose writable campaign DB handles.
- `GameplayMutationDatabaseGuards` installs DB triggers on a defined set of authoritative tables and requires `TURN` or `ADMIN` capability.
- canonical gameplay mutation also uses an in-process capability/seal boundary.

Phase-32 obligation:

- enumerate every authoritative table after Phase 30/31 and prove it is behind the correct capability;
- prove derived/cache/presentation tables are not accepted as authoritative targets by generic mutation infrastructure;
- review ADMIN behavior for new append-only structures;
- ensure no new repository/factory leaks a writable campaign DB to presentation/retrieval code;
- ensure stale read models cannot perform whole-object replacement of authoritative rows.

---

## 7. Migration / legacy classification

### 7.1 Classification

| Legacy information | Classification | What is genuinely recoverable | What is not safely recoverable |
|---|---|---|---|
| Exact present authoritative domain rows | **PARTIALLY REPLAYABLE** | current value/state and stored domain metadata | full sequence of historical events, actor/cause where absent |
| `turn_transaction_receipts` | **PARTIALLY REPLAYABLE** | command/turn identity, semantic hash/version/result evidence as stored, successful canonical commit evidence | exact universal domain-event list if not recorded; causal chain |
| finance ledger/history | **PARTIALLY REPLAYABLE** | finance-domain recorded transactions/order/provenance fields that actually exist | unrelated domain events, missing actor/cause semantics |
| ownership temporal/operation history | **PARTIALLY REPLAYABLE** | recorded ownership transitions/operations within its schema | universal event semantics and absent cause/actor/provenance |
| progression ledger/evidence/provenance | **PARTIALLY REPLAYABLE** | recorded progression evidence/intents/entries | events/causes not explicitly represented |
| `chapter_events` | **PARTIALLY REPLAYABLE** | legacy chapter-event records as legacy records | proof they are canonical domain events or causes |
| `consequence_links` | **FORWARD-ONLY** for canonical causality; legacy rows remain readable | legacy association itself | canonical cause unless independently and explicitly evidenced |
| chapter summaries/manifests | **PARTIALLY REPLAYABLE** as artifact history | saved artifact/manifest facts that exist | complete domain mutation/event chronology |
| historical events absent from all durable records | **UNKNOWN_NOT_RECORDED** | nothing beyond present-state inference | event identity/time/actor/cause/provenance |
| historical causal links not explicitly captured | **UNKNOWN_NOT_RECORDED** | none | cause, confidence, evidence, actor |
| canonical Event Store after Phase-30 activation | **REPLAYABLE** as event-history records after activation | exact committed event rows | pre-activation history not recorded |
| canonical Causal Graph after Phase-31 activation | **REPLAYABLE** as explicit relation assertions after activation | exact committed typed edge assertions | pre-activation causes not recorded |

“REPLAYABLE” here means the durable record itself can be replayed/read according to its contract; it does not automatically mean it is authorized to reconstruct every domain table.

### 7.2 Required additive migration strategy

1. Add new Phase-30 storage without rewriting legacy domain/history tables.
2. Record a clear schema/activation version from which canonical events become mandatory for the mutation classes defined by Phase 30.
3. Do not backfill guessed events. Existing state remains valid legacy state.
4. Add Phase-31 graph storage after G30 is proven. Start canonical edge assertions forward-only.
5. Do not bulk-promote `consequence_links` to `CAUSES`.
6. Preserve legacy readers/adapters only where needed; label semantics explicitly so retrieval cannot treat legacy association as canonical causality.
7. Phase 32 then freezes/enforces owner/write/read capability classes across old and new structures.
8. Any migration marker is evidence of migration/activation **at migration time**, not a fabricated historical domain event. It must not claim an actor/cause/time for old state that was never recorded.

### 7.3 Downgrade/reopen hazards

**New DB → old app:** highest risk. An older runtime may understand existing domain tables but not the newly mandatory Event Store, so it could create event-less canonical state. Post-activation databases require a fail-closed minimum-writer/schema compatibility check or another mechanism that prevents old runtime mutation.

**Interrupted upgrade:** new tables/constraints must be created in a transaction or otherwise leave a recognized pre/post state. Migration ledger checksums/versioning must not report success before all required new invariants are installed.

**Process recreation after upgrade:** reopening must verify schema/invariant readiness before accepting `commitTurn`. It must not silently downgrade to legacy event-optional behavior because one optional table/read fails.

**Backup/restore across versions:** restoring a pre-Phase-30 backup into a new runtime should reopen as legacy/pre-activation state and begin forward-only history; restoring a post-Phase-30 backup into an old runtime must fail closed for writes.

---

## 8. Grouping decision

### Option A — Phase 30 → Phase 31 → Phase 32 separately

**Safety:** good isolation, but highest semantic handoff risk. Event identity/provenance decisions made in Phase 30 may be interpreted differently by a separate Phase-31 worker; Phase-32 enforcement arrives only after both new authoritative-history surfaces exist. Repeated migration/ownership handoffs increase drift risk.

### Option B — Phase 30–31 together → Phase 32

**Safety:** better than A because causal identity can be designed against the exact event contract. However, source-of-truth enforcement is still deferred until after Event Store and Causal Graph surfaces both exist. This leaves the largest opportunity for a new writer/read model to become de facto authority before Phase 32 hardens it.

### Option C — Phase 30–32 one worker program with G30/G31/G32 hard STOP gates

**Recommendation: C.**

This should be treated as one implementation program for semantic continuity, **not** as one undifferentiated implementation/acceptance batch. Each phase keeps a hard STOP gate and may not proceed on failure.

#### G30 — Event Store STOP gate

Must prove before Phase 31 work:

- Event Store authority is limited to committed history, not current domain ownership.
- domain effect + required domain ledger/provenance + event set + receipt are atomic;
- retry/lost response cannot duplicate event rows;
- event/receipt semantic disagreement is fail-closed/detectable;
- cross-campaign event references are rejected;
- append-only mutation is structurally enforced;
- migration is forward-only with no fabricated legacy event/actor/provenance;
- downgrade/reopen writer compatibility is safe;
- Phase 26–29 regression suite remains green.

If any item fails: **STOP. Do not start Phase 31.**

#### G31 — Causal Graph STOP gate

Must prove before Phase 32 work:

- relation taxonomy separates causal/provenance/evidence/temporal/narrative/derived/retrieval;
- canonical `CAUSES` cannot be inferred from adjacency, transaction co-membership, narrative links or retrieval;
- `consequence_links` are not auto-promoted;
- FACT/BELIEF/NARRATIVE contamination tests pass;
- dangling/cross-campaign graph endpoints are rejected or explicitly non-canonical;
- causal correction is non-destructive;
- migration is forward-only with no guessed historical causes.

If any item fails: **STOP. Do not start Phase 32.**

#### G32 — Source-of-truth enforcement STOP gate

Must prove before any Phase-33 work:

- complete source-of-truth matrix exists in runtime-enforced form for all major state families;
- every authoritative gameplay writer is behind canonical typed capability/transaction boundaries;
- derived/cache/presentation components have no authority mutation path;
- deletion/rebuild of derived/cache leaves authoritative state unchanged;
- stale presentation/read model overwrite attempts fail;
- Event Store cannot replace domain authority;
- Causal Graph cannot promote/read back relations as domain facts without the correct owner;
- Phase 33 snapshot contract is explicit: snapshot is a consistent capture/rebuild aid, not a new competing authority.

If any item fails: **STOP. Do not start Phase 33.**

---

## 9. Required adversarial test matrix

The tests below are required implementation gates; this audit does not implement them.

| Test | Setup / attack | Required invariant / expected result | Gate |
|---|---|---|---|
| Atomic domain effect + event + receipt | valid command changes authoritative domain and requires event | all three visible after commit; none independently visible | G30 |
| Rollback — domain failure | inject failure during/after domain apply before event/receipt commit | no domain effect, no event, no receipt, no partial ledger | G30 |
| Rollback — event failure | make event append fail after domain apply | entire transaction rolls back including domain/ledger/receipt | G30 |
| Rollback — receipt failure | force duplicate/conflict/failure at receipt insert | domain + event + ledgers roll back | G30 |
| Lost response / retry | commit succeeds but caller receives no response; retry same identity | prior result returned; zero duplicate domain effects; zero duplicate events | G30 |
| Duplicate command, same identity | invoke exact command twice | exactly-once durable effect and event set | G30 |
| Duplicate identity, semantic mismatch | same transaction identity with different command hash/schema/payload | fail closed; no new event/domain mutation | G30 |
| Process recreation | commit/recreate process/retry or reopen | receipt/event/domain invariants preserved; no re-append | G30 |
| Cross-campaign event reference | event intent in campaign A references domain record in B | reject before commit; no partial writes | G30 |
| Event/domain authority disagreement | corrupt/test fixture creates event value conflicting with authoritative domain | Event Store must not overwrite authority; integrity check surfaces disagreement | G30/G32 |
| Event/receipt disagreement | committed receipt missing required event or wrong event-set identity in corrupted fixture | reopen/diagnostic fails closed or marks integrity failure; never silently fabricates event | G30 |
| Event/domain-ledger disagreement | event claims finance/ownership/progression operation not represented by required domain ledger | write-time prevention; corruption detected | G30 |
| Append-only history mutation | attempt UPDATE/DELETE event payload/id/provenance through gameplay and ordinary admin path | blocked; correction only via append/supersede mechanism | G30 |
| Legacy DB upgrade | open pre-Phase-30 DB with representative old state | state preserved; schema upgrades additively; no synthetic historical events | G30 |
| No fabricated historical event | upgrade legacy DB containing current state but no event history | canonical Event Store has no invented backfill for old mutations | G30 |
| Downgrade/reopen | open post-Phase-30 DB with old writer/runtime or simulated older schema capability | writes fail closed; cannot create event-less domain mutation | G30 |
| Causal edge explicitness | insert valid CAUSES with supported source/evidence | accepted only when relation contract satisfied | G31 |
| `before` ≠ cause | two events ordered in time with no causal assertion | temporal edge/query only; no CAUSES edge | G31 |
| Same transaction ≠ cause | two events in same turn/transaction | no automatic causal edge | G31 |
| Narrative association ≠ cause | `chapter_events`/summary associates entities/events | no canonical cause created | G31 |
| Retrieval relation ≠ cause | retrieval returns two related records together | no graph mutation/CAUSES assertion | G31 |
| No fabricated causal edge | migrate legacy `consequence_links` | no automatic canonical CAUSES backfill | G31 |
| Causal dangling refs | canonical edge references missing endpoint/evidence | reject, or store only explicit unresolved non-canonical relation that cannot answer as cause | G31 |
| Cross-campaign causal refs | source in campaign A, target/evidence in B | reject | G31 |
| Causal correction | retract/correct prior asserted edge | original historical assertion remains auditable; new retraction/supersession determines active view | G31 |
| FACT/BELIEF contamination | BELIEF used as graph input | remains BELIEF; cannot become FACT by relation/retrieval | G31/G32 |
| FACT/NARRATIVE contamination | NARRATIVE/chapter statement linked to FACT | no promotion to FACT and no implied cause | G31/G32 |
| FACT→unsupported CAUSE | valid FACT precedes another FACT | no CAUSES without explicit evidence-bearing assertion | G31 |
| Derived deletion/rebuild | delete derived snapshots/read tables/objects | rebuild from owners yields equivalent read model; authoritative DB unchanged | G32 |
| Cache deletion/rebuild | clear all cache data | authoritative semantics unchanged; cache repopulates only from legal reads | G32 |
| Stale presentation overwrite | build snapshot, mutate authority through later turn, then submit old snapshot via any exposed path | stale snapshot cannot overwrite newer authority; only explicit validated command may mutate | G32 |
| Generic StatePatch bypass regression | attempt ordinary generic patch against authoritative table | remains fail-closed | G32 |
| Writable DB leakage | presentation/context code attempts direct authoritative SQL through repository surface | no supported writable handle/path; DB guard rejects unauthorized mutation | G32 |
| ADMIN bypass attack | use migration/recovery scope against append-only/new authority rules | only explicitly legal recovery operations succeed; no silent history rewrite | G32 |
| Phase 26–29 regression | run accepted transaction/ownership/finance/provenance/read-model tests | all remain green and behaviorally unchanged except intentional additive new invariants | G30/G31/G32 |
| Future Phase-33 compatibility | create future-style snapshot from consistent post-30/31 state; delete derived/cache; reopen | snapshot contract does not treat Event Store as competing current-state authority or Causal Graph as truth promotion source | G32 |
| Phase-33 replay boundary | request reconstruction across pre-30 and post-30 history | system explicitly reports legacy/non-replayable boundary; does not fabricate missing pre-30 events | G32 |

### 9.1 Additional property/invariant tests recommended

- For every authoritative table classified by Phase 32, attempt INSERT/UPDATE/DELETE outside `TURN/ADMIN` and require DB-level rejection.
- For every derived/cache/presentation table/object, prove no path can call typed authoritative stores without producing a new validated command.
- Randomly inject failures at every statement boundary inside `TurnTransaction` and assert the same all-or-nothing event/domain/receipt invariant.
- Generate repeated retries across process reopen and assert stable event cardinality and stable receipt result.
- Generate same IDs under two campaigns and assert all reference resolution remains campaign-scoped.
- Fuzz graph relation types and ensure unsupported/unknown values fail closed rather than defaulting to CAUSES.

---

## 10. Phase-33 compatibility boundary

Phase 33 Snapshot System is the immediate downstream consumer that can accidentally undo Phase-30/32 semantics.

Required boundary before Phase 33 starts:

1. A snapshot is not automatically Authoritative State merely because it is durable.
2. `CharacterPanelSnapshotV2` and `PlayerSnapshotBuilder` remain derived read models unless a new separately specified snapshot artifact says otherwise.
3. Event Store is not a universal replacement for the existing authoritative current-state stores.
4. Snapshot restore must not replay events into domain tables for state families that were never declared replay-complete.
5. The pre-Phase-30 portion of history remains explicitly incomplete/legacy.
6. Causal Graph edges are typed assertions and must not be flattened into FACT records during snapshot/retrieval.
7. Derived/cache contents may be omitted from snapshots or rebuilt; omission must not lose authority.
8. If a future snapshot includes derived data for performance, it must carry invalidation/version provenance so stale derived values cannot overwrite newer authority on restore.

---

## 11. Required implementation preconditions

Phase 30 may be started only after its work order/implementation plan explicitly adopts all of the following contracts:

1. **Single atomic commit rail:** required domain effects + domain ledgers/provenance + Event Store rows + receipt participate in one `TurnTransaction` DB transaction.
2. **No second authority:** Event Store owns committed history, not current state of existing domain families.
3. **Exactly-once event identity:** retry-safe stable identity/uniqueness bound to campaign and canonical transaction semantics.
4. **Forward-only history:** no synthetic pre-Phase-30 events, actors, causes or provenance.
5. **Append-only enforcement:** ordinary runtime cannot UPDATE/DELETE event history.
6. **Cross-campaign rejection:** all event/graph references are campaign-consistent.
7. **Receipt/event invariant:** a successful canonical mutation cannot legally exist with its required event set missing, and duplicate retry cannot append another set.
8. **Downgrade safety:** post-activation DB cannot be mutated by an incompatible old writer that omits mandatory events.
9. **Typed causal model:** Phase 31 cannot infer CAUSES from temporal/provenance/evidence/narrative/derived/retrieval relations.
10. **Legacy consequence isolation:** `consequence_links` are not canonical causes by default.
11. **Structural Phase-32 enforcement:** new and old authority tables/capabilities are comprehensively classified and guarded; derived/cache/presentation writers cannot mutate authority.
12. **Phase-33 contract:** snapshot/replay semantics acknowledge the forward-only Event Store boundary and preserve owner hierarchy.

---

## 12. Direct answers / audit disposition

- **Unified Event Store another authority?** Yes, if allowed to mirror/rewrite current domain state. Prevent by narrow ownership: authoritative for committed history only.
- **Event committed without domain effect?** Possible with separate/pre-commit writer; forbidden by same-transaction integration.
- **Domain effect without required event?** Possible with post-commit/best-effort logging; forbidden by event-set preflight and same transaction.
- **Retry duplicates event while receipt is idempotent?** Possible if append is independent; prevent via receipt-first replay plus stable event uniqueness in the same transaction.
- **Migration invents legacy events?** It could, but must not. Pre-30 history is incomplete and must remain explicitly incomplete.
- **Safest `TurnTransaction` integration?** Event append inside the existing canonical SQLite transaction between admitted domain apply and receipt/commit, with all-or-nothing rollback and no post-commit authoritative writer.
- **`consequence_links` authoritative enough for causal reuse?** No. Legacy/scaffolding only unless an individual record independently satisfies new explicit causal/evidence semantics.
- **Historical causes guessed?** Forbidden.
- **Recommended grouping?** **C — one Phase 30–32 worker program with sequential G30/G31/G32 hard STOP gates**, while retaining separate gate evidence and no cross-gate acceptance shortcut.

---

## 13. Final severity disposition

### BLOCKER

- None in the inspected accepted runtime that makes Phase 30–32 infeasible.

### HIGH — mandatory preconditions

- H-01 Event Store second-authority risk.
- H-02 atomic domain/event/receipt and retry duplication risk.
- H-03 legacy fabrication risk.
- H-04 `consequence_links` unsupported-causality risk.
- H-05 relation-type/causal inference contamination risk.
- H-06 downgrade/reopen event-bypass risk.
- H-07 incomplete structural source-of-truth enforcement risk.
- H-08 cross-campaign reference risk.
- H-09 receipt/event/domain-ledger disagreement risk.

### MEDIUM — must be covered by implementation/test evidence

- M-01 `chapter_events` naming/semantic collision.
- M-02 chapter summary/manifest authority confusion.
- M-03 read-model “snapshot” authority confusion.
- M-04 destructive causal-edge correction risk.
- M-05 ADMIN capability bypass/recovery risk.
- M-06 derived/cache rebuildability must be proven.

---

## 14. Acceptance statement

This is an independent feasibility/integrity/migration audit only.

**Verdict: READY WITH REQUIRED PRECONDITIONS.**

This report does **not** declare Phase 30, Phase 31, or Phase 32 accepted, complete, started, or implemented. No runtime, schema, migration, test, roadmap, or acceptance-status changes are authorized or performed by this work item.
