# WORK-20260817-029 — PHASE 30–32 PRE-IMPLEMENTATION CONTRACT / ARCHITECTURE AUDIT

**Role:** CHAT-2 — architecture / contract / implementation-planning auditor  
**Mode:** READ-ONLY RUNTIME; evidence-only report commit  
**Repository:** `piotreksmaga-art/rpg-os-android`  
**Accepted runtime baseline:** `45ff53457bff16c4ff72a4cccdecac89124109c3`  
**Audit scope:** Phase 30 Event Store, Phase 31 Causal Graph, Phase 32 runtime truth-layer enforcement  
**Implementation authorization:** **NONE**  
**Acceptance authorization:** **NONE**

---

## 1. Executive verdict

### Final verdict: **READY WITH REQUIRED PRECONDITIONS**

Repository-first inspection supports a single sequential implementation program with hard stop gates:

`G30 Event Store -> G31 Causal Graph -> G32 truth-layer enforcement`

The phases **must not be implemented in parallel** and must not be collapsed into one ungated mega-change. G31 depends on stable Phase-30 event identity/commit semantics; G32 must classify and mechanically protect the Phase-30 and Phase-31 artifacts after those contracts exist.

Actual repository state at accepted runtime `45ff53457bff16c4ff72a4cccdecac89124109c3`:

| Phase | Repository state | Basis |
|---|---|---|
| 30 — Event Store append-only | **PARTIAL** | Event-like contracts/history/provenance exist, including `PlayerEventIntent`, finance and ownership histories, truth provenance and turn receipts, but there is no generalized canonical Event Store. `TurnTransaction` currently fails closed on every non-empty `eventIntents` list. |
| 31 — Causal Graph | **PARTIAL** | `consequence_links`, supersession/history links, `causalChangeUids`, source-event/provenance references and transaction identity provide fragments, but there is no generalized typed campaign-scoped causal-edge authority or its validation/idempotency contract. |
| 32 — Authoritative / Derived / Cache / Presentation enforcement | **PARTIAL** | Several families are already mechanically separated (notably authoritative finance ledger vs rebuildable balance projection; derived snapshots with no write path), but there is no complete mechanically enforced runtime-wide truth-layer boundary covering all important state families. |

None of Phase 30–32 is ACCEPTED by this audit.

---

## 2. Mandatory bootstrap and evidence baseline

### 2.1 Accepted runtime is the only runtime baseline

The accepted runtime commit exists and is:

`45ff53457bff16c4ff72a4cccdecac89124109c3`

Its commit message is `WORK-026 final architectural enforcement repair` and its tree is `39e4861b36acec14196f04f7ab9af99d7ef928e3`.

CI evidence was independently checked through GitHub Actions API:

- workflow: `Validate RPG OS ALPHA`;
- run number: **703**;
- run ID: **32038070404**;
- `head_sha`: exactly `45ff53457bff16c4ff72a4cccdecac89124109c3`;
- conclusion: **success**;
- completed 2026-08-17.

This matches the supplied Phase-26–29 acceptance context.

### 2.2 Current master drift is context, not a substituted runtime

At audit time current `master` is:

`fdd30e864789edabc1baa55eaa8b86db2aa24ded`

with message:

`docs: normalize phase 1-29 result documentation`

The inspected head change is documentary roadmap normalization. It is therefore treated as later documentation context and is **not** substituted for accepted runtime `45ff534...`.

### 2.3 Canonical documents inspected

The audit read current project coordination/architecture material, including:

- `docs/RPG_OS_MASTER_ARCHITECTURE.md`
- `docs/RPG_OS_IMPLEMENTATION_ROADMAP.md`
- `docs/PARALLEL_WORK_COORDINATION.md`
- `docs/architecture/CHAT_COORDINATION_POLICY.md`
- `docs/PROJECT_WORK_PROTOCOL.md`

The repository architecture consistently places the transactional core before Event Store, Event Store before Causal Graph, and snapshot/knowledge/chronicle systems later. Existing canonical player flow is proposal-first and commit-authority-centered rather than direct UI/read-model mutation.

### 2.4 Phase 26–29 runtime evidence inspected

Material runtime inspected at exact accepted SHA includes at minimum:

- `TurnTransaction.kt`
- `TurnTransactionReceiptStore.kt`
- `PlayerChangeSetModel.kt`
- `PlayerDomainEngine.kt`
- `CampaignTruthStore.kt`
- `FinancialStore.kt`
- `OwnershipStore.kt`
- `LocalGameStore.kt`
- `ContextBuilder.kt`
- `CharacterPanelSnapshotV2.kt`
- `PlayerSnapshotBuilder.kt`
- `ChapterSaveManager.kt`
- `MigrationManager.kt`
- Phase 12/13 ownership/finance migrations
- Phase-26–29 mutation/idempotency/recovery tests and corresponding accepted evidence present in the accepted tree.

No Phase-30/31/32 implementation is inferred from roadmap labels.

---

# 3. PHASE 30 — EVENT STORE

## 3.1 State classification: **PARTIAL**

The repository contains substantial event-like infrastructure but **not** the Phase-30 canonical Event Store.

Most decisive evidence: accepted `TurnTransaction` calls `CanonicalPlayerChangeApplier.preflight(...)` before opening its gameplay write transaction, and `preflight` contains:

`if(changeSet.eventIntents.isNotEmpty()) throw UnsupportedCanonicalIntentException("EVENT_INTENT")`

Therefore an Event intent can be proposed by the existing typed model but is deliberately **not commit-capable** at Phase 29. This is a fail-closed seam prepared for later work, not an Event Store implementation.

## 3.2 Existing event-like infrastructure

### 3.2.1 `PlayerEventIntent`

`PlayerChangeSetModel.kt` already defines:

- `EVENT_APPEND_INTENT` as a change-intent classification;
- `PlayerEventIntent`;
- `eventIntentUid`;
- `eventKindUid`;
- optional `actorRef`;
- `targetRefs`;
- `causalChangeUids`;
- typed payload;
- optional `proposedEffectiveOrder`;
- `causationUid` and `correlationUid` at ChangeSet level.

This is a **proposal contract**, not durable history. It must remain semantically distinct from an event that was actually committed.

### 3.2.2 TurnTransaction receipts

`TurnTransactionReceiptStore.kt` explicitly describes receipts as:

`APPEND-ONLY COMMIT EVIDENCE`

Receipt identity contains:

- `campaignUid`;
- `turnUid`;
- `commandUid`;
- `transactionUid`;
- semantic fingerprint;
- result fingerprint;
- Phase-29 `commitOrder`;
- receipt version.

Receipt insertion requires the active outer SQLite transaction. Receipt replay detects transaction/command semantic mismatch and provides Phase-28 idempotency and Phase-29 recovery evidence.

Receipts are therefore **commit evidence**, not gameplay truth and not a replacement for Event Store.

### 3.2.3 Finance history

Phase-13 schema and `FinancialStore` establish `financial_ledger_transactions` as authoritative financial history. Migration notes explicitly say:

- append-only exact-integer financial ledger;
- rebuildable balance projection;
- zero legacy financial history synthesis.

Committed ledger rows are protected against UPDATE and DELETE by DB triggers. This history is a domain authority and must remain so after Phase 30.

### 3.2.4 Ownership history

Phase-12 schema provides `ownership_records` and `ownership_operations`, source-event references, predecessor/successor semantics and history-preserving triggers. `ownership_records` permit only a legal close transition; delete is forbidden. Ownership is independent authority from possession/equipment.

Phase-30 Event Store must reference ownership effects where useful but must not replace the ownership history model.

### 3.2.5 Campaign truth provenance

`CampaignTruthStore` remains the sole truth authority for typed FACT/BELIEF/NARRATIVE records. `CampaignTruthChange` in `PlayerChangeSetModel.kt` explicitly documents this boundary. Existing provenance can include source command/turn/event references.

An Event row may evidence a truth transition but cannot itself become a competing truth record.

### 3.2.6 Progression proposal/evidence intent

The Phase-29 transaction preflight accepts typed progression ledger intent only when its `causalChangeUids` resolve to legal stat/skill/technique changes. That is already a useful provenance discipline, but it is not the generalized Event Store.

### 3.2.7 Narrative/chapter/consequence structures

`ContextBuilder` reads chapter manifests and active world events as context/read-model inputs. `ChapterSaveManager` reads pending `consequence_links` and writes `chapter_manifests_v2` with `CONFLICT_REPLACE`. These structures are chapter/narrative/continuity facilities, not canonical generalized immutable gameplay events.

`chapter_events` was specifically searched in repository source at this baseline. No production code reference was found by repository code search. Consequently, even if a legacy/package schema table by that name exists in shipped data, there is no evidence that accepted runtime exposes it as the Phase-30 canonical event authority. It must not be promoted by name alone.

### 3.2.8 Save / backup / recovery

`LocalGameStore` exposes backup/restore/finalize-chapter infrastructure. Turn recovery is receipt-based. Backup files are recovery artifacts/copies; chapter manifests are summaries. Neither is an Event Store.

---

## 3.3 Canonical definition: what is an Event in RPG OS?

A Phase-30 Event should be defined as:

> **An immutable, campaign-scoped, schema-versioned historical commit-evidence record stating that a specifically identified semantically admitted effect occurred as part of a successfully committed canonical `TurnTransaction`.**

Important boundary: an Event records **that an effect occurred and its committed identity/provenance**. It does not become the mutable current-state authority for finance, ownership, inventory, campaign truth, stats/resources, skills/techniques or projects.

Thus Event Store is primarily:

- **append-only commit evidence**;
- **historical evidence**;
- a stable identity/provenance substrate for later causal references.

It is **not** a universal event-sourced replacement for existing authoritative stores.

Some event payload fields may be derived from an admitted change, but the committed Event row itself is immutable evidence once written.

---

## 3.4 Which committed transitions require events?

The safest canonical rule is **explicit event-bearing admission**, not heuristic table observation.

For Phase 30, every gameplay `PlayerDomainChange` admitted through canonical `TurnTransaction` should map deterministically to at least one required event record unless a narrowly enumerated change kind is explicitly declared non-event-bearing in the canonical registry. The default must be **event-bearing**, because silent omission would make receipt/event completeness unverifiable.

Current supported Phase-29 committed families therefore prospectively require event mapping:

- stat mutation;
- resource mutation;
- skill progression;
- technique progression;
- inventory mutation;
- equipment mutation;
- finance transaction;
- ownership transfer;
- campaign truth transition;
- development project work/progress.

For domains with their own append-only authority (finance ledger; ownership history), the Event records the canonical committed occurrence and points to the authoritative domain record. It does not duplicate ownership/finance authority.

Future change kinds must register their event contract before canonical admission.

---

## 3.5 Atomic Event Store integration with accepted TurnTransaction

The required Phase-30 atomic unit is:

`AUTHORITATIVE EFFECTS + REQUIRED EVENT RECORDS + TRANSACTION RECEIPT`

inside **one SQLite transaction**.

Recommended exact sequence within the already accepted outer `TurnTransaction`:

1. replay/idempotency check;
2. full preflight of domain changes, ledger intents and event intents/event mapping;
3. begin SQLite transaction;
4. repeat replay check inside transaction;
5. acquire canonical gameplay mutation authority;
6. apply authoritative/domain-ledger effects;
7. append all required Event Store rows;
8. verify the actual event manifest equals the deterministic required manifest;
9. append receipt carrying/binding the event-manifest result;
10. `setTransactionSuccessful()`;
11. COMMIT;
12. only after commit: rebuild/invalidate derived/cache/presentation state as required.

Required failure semantics:

- if any authoritative write fails, no event survives;
- if any required event write fails, all authoritative writes roll back;
- if event completeness verification fails, no receipt is written and transaction rolls back;
- if receipt write fails, authoritative effects and events roll back;
- failure after receipt insertion but before SQLite commit leaves neither receipt nor events nor authoritative effects.

This naturally extends the already accepted failure-injection semantics including `AFTER_RECEIPT_BEFORE_COMMIT`.

### Receipt extension requirement

Receipt V3 (or equivalent schema-versioned successor) should bind the required Event Store result, minimally by deterministic:

- event count; and
- ordered event-manifest fingerprint/hash.

A receipt must not claim a committed event-bearing turn if its required event manifest is absent or mismatched.

---

## 3.6 Event identity contract

Minimum canonical Event row/record identity:

- `campaign_uid` — mandatory, part of every uniqueness/reference boundary;
- `event_uid` — stable immutable identity;
- `transaction_uid` — mandatory for gameplay-commit events;
- `command_uid` — mandatory for player-command-derived events;
- `turn_uid` — mandatory where the transaction has one;
- `event_ordinal` — deterministic ordinal within transaction;
- `event_kind_uid` / event type;
- actor/source reference with typed kind + uid where applicable;
- typed subject/object/target references where applicable;
- causal/provenance references, without assuming chronology is causality;
- payload/schema identity and `event_schema_version`;
- deterministic semantic fingerprint;
- Phase-29 `commit_order` relation;
- optional effective/domain order only where domain semantics require it.

Canonical total committed gameplay ordering should be the pair:

`(commitOrder, eventOrdinal)`

rather than inventing a second independent global order.

### Deterministic idempotency

The implementation must make the same logical retry resolve to the same event identity/manifest. Acceptable design:

- derive `eventUid` from stable transaction identity + deterministic ordinal + event semantics; or
- accept a proposed stable event UID only after exact validation against the deterministic manifest.

Required constraints include at least:

- unique `(campaign_uid, event_uid)`;
- unique `(campaign_uid, transaction_uid, event_ordinal)`;
- fingerprint mismatch for an existing identity fails closed.

Do not use wall-clock time or rowid as semantic identity.

---

## 3.7 Legacy semantics

Phase 30 must be **prospective**.

Do **not** synthesize Event Store rows for pre-Phase-30 state merely because current authoritative state, finance history, ownership history, chapter manifests or provenance exist.

This follows existing repository migration discipline:

- Phase-13: no legacy balance/history synthesis;
- Phase-29: old receipts receive `commit_order = NULL` and order remains UNKNOWN;
- Campaign truth migration: no legacy facts/provenance are fabricated.

Canonical representation for missing pre-Phase-30 event provenance must remain explicit:

- `UNKNOWN` / `NOT_RECORDED` / null with documented semantics;
- never a fabricated pseudo-event.

An old domain ledger row can remain valid authoritative history without a Phase-30 event UID.

---

## 3.8 Append-only policy and exceptions

### Normal gameplay

Committed Event Store rows are append-only:

- no UPDATE;
- no DELETE;
- no semantic re-interpretation by changing mutable type/version fields.

Corrections should normally be represented by a later typed compensating/superseding/correction event plus the domain’s own legal correction mechanism.

### Administrative / migration / recovery

Allowed exceptional authority must be separate from gameplay mutation authority and narrowly scoped:

1. **Schema migration:** additive columns/tables/indexes or structure migration preserving existing committed event semantics.
2. **Recovery of uncommitted/corrupt technical residue:** only where the row is demonstrably not a committed event bound to a valid receipt; operation must be auditable and cannot silently rewrite gameplay meaning.
3. **Administrative repair:** only under explicit privileged repair path and preferably by append-only correction. Destructive rewrite of valid committed historical meaning is prohibited.

Normal `TurnTransaction` capability must never expose update/delete of committed events.

---

## 3.9 G30 prospective schema/migration requirements

Recommended additive schema objects:

- `event_store_events` (or one canonical equivalent name);
- optional typed target/reference child table if multiple targets are normalized rather than encoded in payload;
- Event kind/schema registry if repository conventions require it;
- append-only UPDATE/DELETE DB guards;
- same-campaign reference guards where enforceable;
- transaction/order indexes;
- receipt schema extension for event manifest hash/count;
- migration marker explicitly stating **prospective event capture; no historical event synthesis**.

Do not mutate the authoritative finance, ownership, inventory, truth, stat, skill, technique or project schemas merely to make Event Store their new truth authority.

---

# 4. PHASE 31 — CAUSAL GRAPH

## 4.1 State classification: **PARTIAL**

The repository already contains relation/provenance fragments but no smallest generalized causal contract.

Existing evidence includes:

- `consequence_links` consumed by chapter finalization;
- `causalChangeUids` in event and ledger intents;
- `causationUid` and `correlationUid` in `PlayerChangeSet`;
- CampaignTruth `supersedes` and provenance references;
- ownership predecessor/successor/history links;
- finance reversal references;
- project evidence references;
- source event/command references;
- TurnTransaction identities and future Phase-30 event identities.

These are not interchangeable, and they do not establish a generalized graph simply by existing.

---

## 4.2 Core invariant

The Phase-31 contract must mechanically preserve:

`CORRELATION != CAUSATION`

`NARRATIVE ORDER != CAUSAL EDGE`

`EVENT A BEFORE EVENT B != A CAUSED B`

No edge may be generated solely because two records share a turn, are adjacent in `commitOrder`, appear in the same chapter, or are narrated in sequence.

---

## 4.3 Smallest canonical generalized Causal Graph

Phase 31 should store **typed immutable relationships between canonical references**, not duplicate node payload/state.

### Node identity

A graph node should be a typed reference:

`(campaignUid, nodeKindUid, nodeUid)`

where the referenced object remains authoritative in its own subsystem. Phase-31 node kinds should initially be minimal and explicit, for example:

- committed Phase-30 event;
- campaign truth record/version;
- authoritative domain-history/evidence record when a real causal/provenance relation requires it;
- transaction/command provenance identity only where semantics cannot be expressed via an Event node.

Do not create a mutable “node state” copy of player/domain truth.

### Edge identity

Minimum edge record:

- `campaign_uid`;
- `edge_uid`;
- `from_node_kind_uid`, `from_node_uid`;
- `to_node_kind_uid`, `to_node_uid`;
- typed `relation_kind_uid`;
- relation schema/version;
- provenance/source describing **why** the edge is asserted;
- `transaction_uid` and/or creating `event_uid` where the edge is created by a gameplay commit;
- deterministic fingerprint;
- optional confidence only if the relation type explicitly permits epistemic uncertainty. A causal FACT edge must not silently become a belief score.

### Relation semantics

A small canonical vocabulary is preferable. Repository evidence supports separating at least these semantic families:

- causal: `CAUSES`, `ENABLES`, `PREVENTS`, `TRIGGERED_BY`;
- provenance/evidence: `DERIVED_FROM`, `EVIDENCED_BY`;
- history/version: `SUPERSEDES`.

The exact identifiers may follow repository UID naming conventions, but their semantics must be closed/registered. A free-text edge type is not sufficient.

A relation that is merely correlation must have a distinct non-causal type **only if** Phase 31 needs it. Otherwise correlation should remain outside the causal core.

---

## 4.4 Validation and dangling-reference policy

Required validation:

- both node refs are nonblank and typed;
- both internal node refs exist at admission time;
- both belong to exactly the same `campaign_uid` as the edge;
- cross-campaign references fail closed;
- relation kind is registered and valid for the node-kind pair;
- edge fingerprint matches canonical normalized content;
- duplicate semantic edge is idempotent, not duplicated;
- same edge UID with different semantics fails closed;
- no automatic edge from order/adjacency.

### Unknown legacy provenance

Do not create fake “legacy unknown event” nodes.

Where a pre-Phase-31 record has no known causal origin:

- causal origin remains absent/unknown;
- legacy provenance remains the existing explicit provenance/null semantics;
- no edge is fabricated simply to make the graph look connected.

### External/opaque references

If later phases need external evidence, that should use an explicitly registered external-reference node kind with separate validation semantics. Do not treat a missing internal object as an “external” object to bypass integrity.

---

## 4.5 Cycle policy

For the smallest Phase-31 contract, fail closed on:

- self-edge for causal/derivation/supersession relations;
- directed cycles for relation families whose semantics imply causal or derivational precedence (`CAUSES`, `ENABLES`, `TRIGGERED_BY`, `DERIVED_FROM`, `SUPERSEDES`).

`PREVENTS` should also be treated conservatively as causal dependency unless a later formal semantics document says otherwise.

If a relation does not imply an acyclic dependency, do not mix it into the causal DAG contract without explicitly classifying its cycle policy.

Acyclicity checks in Phase 31 need only operate at write validation level; this does not authorize Phase-42 graph retrieval/ranking.

---

## 4.6 Atomic integration

A causal edge that is **required by a newly committed effect/event** must join the same outer `TurnTransaction` as:

`authoritative effects + events + required causal edges + receipt`

Thus rollback cannot leave an edge describing an effect/event that never committed.

The receipt/event manifest must be extended or structured so commit completeness can bind required causal records where the Phase-31 event contract says they are mandatory.

A non-gameplay administrative provenance edge may use a separate privileged transaction, but cannot bypass campaign/reference validation.

---

## 4.7 Traversal boundary and Phase 42

Phase 31 is a **causal truth/data contract**, not a knowledge retrieval engine.

Allowed Phase-31 retrieval surface should be minimal:

- edge by UID;
- direct incoming/outgoing adjacency for validation/debug/audit;
- bounded reachability used only for cycle prevention;
- exact provenance lookup.

Explicitly deferred to Phase 42:

- semantic multi-hop retrieval;
- ranking/relevance;
- graph-informed GM context selection;
- knowledge synthesis;
- causal explanation generation;
- embeddings/search/vector retrieval;
- query planning across knowledge domains.

This prevents Phase 31 from silently becoming Knowledge Graph / causal retrieval early.

---

## 4.8 G31 prospective schema/migration requirements

After G30 is accepted by its own implementation gate, add prospectively:

- `causal_graph_edges` (or canonical equivalent);
- registered relation kinds/semantics;
- `(campaign_uid, edge_uid)` identity;
- indexes by source and target typed ref;
- deterministic semantic uniqueness/fingerprint;
- append-only guards;
- same-campaign/reference validation in store plus DB constraints where possible;
- migration marker saying **no synthetic legacy causal edges**.

A separate mutable node payload table is not required for the minimal contract.

---

# 5. PHASE 32 — RUNTIME TRUTH-LAYER ENFORCEMENT

## 5.1 State classification: **PARTIAL**

The repository already contains strong local classifications but lacks a uniform system-wide mechanically enforced write-direction policy.

Two especially strong accepted precedents are already present:

1. `CharacterPanelSnapshotV2` is classified `DERIVED_PRESENTATION`; its read source is explicitly read-only and the snapshot “may be discarded and rebuilt at any time”.
2. `PlayerSnapshotBuilder` classifies output `DERIVED_PROJECTION`; builder is pure/deterministic and explicitly “never write[s], commit[s], advance[s] time, run[s] progression, or become[s] a replacement source of player truth”.

Finance is another mechanical precedent: ledger is authoritative; balances are rebuildable projection.

Phase 32 should generalize these truths into enforced runtime architecture rather than inventing new copies of state.

---

## 5.2 Canonical state-family classification

Some stores have compound roles. The classification below uses the **semantic role of each record family**, not merely table mutability.

| Runtime family | Required classification after Phase 32 | Notes |
|---|---|---|
| `CampaignTruthStore` truth records | **AUTHORITATIVE** | Typed FACT/BELIEF/NARRATIVE authority. Provenance does not turn Event Store into truth. |
| active player identity / typed player stores | **AUTHORITATIVE** | Canonical current player identity/state. |
| base stats / current resources | **AUTHORITATIVE** | Derived effective/max/regeneration values remain rebuildable. |
| skill / technique state | **AUTHORITATIVE** | Reconciled legacy display/context adapters are not independent authority. |
| innate/evolution state | **AUTHORITATIVE** | Subject to canonical store contract. |
| inventory | **AUTHORITATIVE** | Possession remains distinct from equipment and ownership. |
| equipment/loadout | **AUTHORITATIVE** | Must not be inferred as truth from panel/context freshness. |
| ownership records/operations | **AUTHORITATIVE DOMAIN HISTORY** | Temporal/history model; append-preserved semantics. Not generic Event Store evidence. |
| finance accounts + `financial_ledger_transactions` | **AUTHORITATIVE DOMAIN STATE/HISTORY** | Ledger is append-only authority. |
| `financial_account_balances` | **DERIVED** | Rebuildable from ledger; deletion/rebuild must not lose financial truth. |
| development projects + committed project work | **AUTHORITATIVE** | Project evidence refs are provenance, not Causal Graph replacement. |
| authoritative modifier inputs | **AUTHORITATIVE** | Existing migration notes explicitly distinguish them from resolved values. |
| resolved effective/max/regeneration values | **DERIVED** | Must never write back over base authority. |
| TurnTransaction receipts | **APPEND_ONLY_COMMIT_EVIDENCE** | Commit/idempotency/recovery evidence; not gameplay truth. |
| Phase-30 Event Store | **APPEND_ONLY_COMMIT_EVIDENCE / HISTORICAL EVIDENCE** | Explicitly not a second domain authority. |
| Phase-31 Causal Graph edges | **APPEND_ONLY_COMMIT/PROVENANCE EVIDENCE** | Typed relationships only; no mutable node truth copy. |
| `CharacterPanelSnapshotV2` | **DERIVED + PRESENTATION** | Existing enum already says `DERIVED_PRESENTATION`; no write path. |
| `PlayerSnapshotBuilder` outputs/profiles | **DERIVED** | Existing enum says `DERIVED_PROJECTION`; pure projection. |
| `ContextBundle` / `ContextBuilder` read models | **DERIVED / PRESENTATION-CONTEXT** | Aggregates truth/current stores and legacy context; cannot write authority backward. |
| chapter manifests/summaries | **PRESENTATION / SUMMARY / RECOVERY-METADATA as applicable** | `ChapterSaveManager` uses `CONFLICT_REPLACE`; this proves they are unsuitable as immutable event authority. |
| rebuildable indexes/materializations | **CACHE or DERIVED** | Deletion must not destroy authority. |
| UI panel/view state | **PRESENTATION** | Newer timestamp never promotes it to truth. |
| backup packages/files | **ADMINISTRATIVE / RECOVERY ARTIFACT** | A copy of authority, not live authority. Restore needs privileged validated path. |
| migrations / schema metadata / repair authority | **ADMINISTRATIVE / MIGRATION / RECOVERY** | Separate capability from normal gameplay. |

### Important compound-role rule

“Append-only” is not synonymous with “generic evidence”. `financial_ledger_transactions` and ownership history are append-oriented **domain authorities**. They must not be downgraded to Event Store evidence just because they are historical and immutable.

---

## 5.3 Required mechanical enforcement

Documentation-only classification is insufficient for Phase 32 COMPLETE.

Implementation must enforce at least:

### A. Write-direction rule

Only canonical authoritative mutation capabilities may write AUTHORITATIVE families.

`DERIVED`, `CACHE`, `PRESENTATION` builders/readers expose no API that can overwrite authoritative stores.

### B. Stale-data rule

A snapshot/context/panel carrying newer wall-clock time, higher presentation version or later rebuild timestamp gains **no authority**.

Any API that attempts to persist a snapshot back into authority must be absent or fail closed.

### C. Safe rebuild/delete rule

There must be tests demonstrating that deleting/rebuilding DERIVED/CACHE/PRESENTATION artifacts does not delete or semantically alter authoritative state or append-only commit evidence.

### D. Append-only history rule

Receipt/Event/Causal historical meaning cannot be mutated in-place. Corrections use explicit later records or privileged repair with audit semantics.

### E. Administrative capability rule

Migration/restore/repair authority must remain explicitly separate from normal gameplay capability. `LocalGameStore.openGameplaySaveDb()` and existing gameplay DB guards are the right architectural precedent.

### F. FACT/BELIEF/NARRATIVE rule

Event type and causal edge do not collapse truth classes. `PlayerSnapshotBuilder` already preserves typed `PlayerTruthClass`; Phase-30/31 integration must preserve this through Event and Causal references.

---

# 6. Exact recommended pipeline after Phase 32

Repository evidence supports this pipeline:

`PlayerCommand`

`-> PlayerDomainEngine`

`-> validated PlayerChangeSet`

`-> canonical campaign mutation admission / mutation boundary`

`-> TurnTransaction preflight + idempotency replay check`

`-> BEGIN SQLite transaction`

`-> authoritative typed-store writes + authoritative domain ledgers/history`

`-> required Phase-30 Event Store append`

`-> required Phase-31 causal/provenance edge append`

`-> verify authoritative-change / event / edge manifest completeness`

`-> append TurnTransaction receipt binding semantic + committed-result manifests`

`-> COMMIT`

`-> invalidate/rebuild DERIVED / CACHE / PRESENTATION projections`

`-> read-only Context / CharacterPanelSnapshotV2 / PlayerSnapshot profiles / UI`

No derived/presentation rebuild is allowed to feed backward into the authoritative transaction as a source of truth.

Where a canonical action has no derived artifact to rebuild, the post-commit phase can be a no-op.

---

# 7. Implementation grouping recommendation

## Recommendation: **ONE implementation program, THREE independently enforced hard gates**

The coordinator preference is confirmed with constraints.

Reasons:

1. Phase 30 naturally extends the accepted TurnTransaction seam that currently rejects `EVENT_INTENT`.
2. Phase 31 needs the stable committed event identity produced by Phase 30.
3. Phase 32 cannot correctly classify/protect Event Store and Causal Graph until both exist.
4. A single program reduces schema/identity drift between three tightly linked phases.
5. Separate gate commits/tests prevent one phase from masking defects in another.

Do **not** run G30/G31/G32 as parallel workers touching the same commit path/schema.

Recommended implementation branch/program discipline:

- implement G30 only;
- run full G30 acceptance matrix + existing 26–29 regression suite;
- evidence checkpoint/commit;
- only if G30 passes, implement G31;
- run G31 + G30 + 26–29 suite;
- evidence checkpoint/commit;
- only if G31 passes, implement G32;
- run G32 + all prior gate tests;
- then hand to independent acceptance/audit.

This audit does not authorize those implementation steps; it only defines the program contract.

---

# 8. Explicit STOP GATES

## G30 STOP GATE — Event Store

**STOP; do not begin G31 unless every item is true:**

- Event Store schema/migration is prospective and performs zero fabricated legacy event synthesis.
- Event identity includes campaign, event, transaction, command/turn where applicable, deterministic ordinal/fingerprint and schema version.
- Existing Phase-29 `commitOrder` is the transaction-order authority; Event Store does not invent a competing global commit sequence.
- Every admitted event-bearing authoritative change has a deterministic required event mapping.
- `authoritative effects + required events + receipt` commit atomically in one outer SQLite transaction.
- receipt binds event count/manifest fingerprint (or an equivalently strong completeness proof).
- rollback at every injected failure point leaves zero committed event rows for the failed transaction.
- retry/replay creates zero duplicate events.
- same identity + semantic mismatch fails closed.
- cross-campaign event/reference attempts fail closed.
- committed events cannot be UPDATEd/DELETEd through gameplay path.
- Event Store cannot overwrite or supersede domain authority by read/write precedence.
- finance ledger and ownership history remain their respective domain authorities.
- all Phase-26–29 regressions remain GREEN.

## G31 STOP GATE — Causal Graph

**STOP; do not begin G32 unless every item is true:**

- G30 is green on exact implementation SHA.
- edge identity/fingerprint is deterministic and campaign-scoped.
- registered typed relation semantics are closed and documented.
- correlation, chronology and narrative adjacency never auto-create causal edges.
- both internal node refs are validated and same-campaign.
- illegal/dangling/cross-campaign refs fail closed.
- legacy unknown causal provenance remains unknown, not synthesized.
- self/cycle rules for causal/derivation/supersession families are enforced.
- required commit-time causal edges join the same TurnTransaction atomic unit.
- rollback leaves no edge for a rolled-back effect/event.
- retry creates no duplicate edge.
- Causal Graph has no mutable duplicate of gameplay truth.
- retrieval remains direct/bounded validation/audit only; Phase 42 is not implemented.
- all G30 and Phase-26–29 regressions remain GREEN.

## G32 STOP GATE — Runtime truth-layer enforcement

**STOP; do not present Phase 30–32 for acceptance unless every item is true:**

- every important runtime family has one explicit semantic layer classification (with documented compound domain-authority cases where necessary);
- authoritative write APIs are unreachable from derived/cache/presentation builders;
- stale derived/presentation state cannot overwrite authority;
- `CharacterPanelSnapshotV2` remains derived/presentation and write-less;
- PlayerSnapshot FULL/COMBAT/PROGRESSION/ECONOMY/SOCIAL/GM_CONTEXT remain derived projections and write-less;
- deleting/rebuilding every declared cache/derived projection under test causes zero authoritative loss;
- receipt/Event/Causal evidence is immutable under gameplay path;
- finance balance projection rebuild remains ledger-derived;
- administrative migration/recovery capabilities remain distinct from gameplay mutation capability;
- FACT/BELIEF/NARRATIVE remains typed and uncollapsed through Event/Causal references;
- all G31 + G30 + Phase-26–29 regressions remain GREEN.

---

# 9. Mandatory test / acceptance matrix

## 9.1 G30 tests

At minimum:

1. rollback before first write -> no authoritative change, no event, no receipt;
2. rollback after first domain write -> no authoritative change survives, no event, no receipt;
3. rollback after multiple domain writes -> nothing survives;
4. failure during event append -> authoritative writes/events/receipt all roll back;
5. failure after all events but before receipt -> all roll back;
6. existing `AFTER_RECEIPT_BEFORE_COMMIT` failure -> receipt, events and domain writes all absent after rollback;
7. successful commit contains every and only required event record;
8. receipt event-manifest hash/count exactly matches committed events;
9. deliberately missing required event causes fail-closed rollback;
10. extra/unexpected event causes fail-closed rollback;
11. retry same command/transaction/semantic fingerprint returns committed receipt and creates no duplicate event;
12. retry same UID with different semantics fails closed;
13. same command with conflicting transaction semantics fails closed;
14. cross-campaign event UID/reference rejection;
15. deterministic ordering equals `(commitOrder,eventOrdinal)` semantics;
16. no wall-clock-dependent identity;
17. normal Event Store UPDATE rejected;
18. normal Event Store DELETE rejected;
19. Event Store API cannot mutate finance/ownership/inventory/truth/player stores;
20. migration over legacy campaign produces zero synthetic historical Phase-30 events;
21. pre-Phase-30 missing event provenance remains explicit unknown/not-recorded;
22. finance commit retains authoritative ledger truth even when Event Store projection/read is removed;
23. ownership history retains authority independent of Event Store;
24. FACT/BELIEF/NARRATIVE truth event references do not change truth kind;
25. full Phase-26 mutation authority, Phase-27 atomicity, Phase-28 idempotency and Phase-29 recovery suite green.

## 9.2 G31 tests

At minimum:

1. valid typed causal edge persists only with valid same-campaign nodes;
2. missing source node rejected;
3. missing target node rejected;
4. cross-campaign source rejected;
5. cross-campaign target rejected;
6. illegal relation kind/node-kind pair rejected;
7. same edge identity + same semantics is idempotent;
8. same edge identity + different semantics fails closed;
9. deterministic edge fingerprint stable across retry;
10. event A immediately before B does **not** create edge automatically;
11. same turn does not imply causal edge;
12. same chapter/narrative order does not imply causal edge;
13. correlation UID does not become `CAUSES` edge;
14. rollback removes required edge for rolled-back effect/event;
15. retry creates no duplicate causal edge;
16. self-edge rejected for causal/derivation/supersession family;
17. directed cycle rejected for acyclic relation family;
18. explicit unknown legacy provenance creates no fake node/edge;
19. graph write cannot mutate referenced CampaignTruth/domain/Event records;
20. direct adjacency/read API works without implementing semantic Phase-42 retrieval;
21. FACT/BELIEF/NARRATIVE remain typed when used as graph references;
22. all G30 and Phase-26–29 tests green.

## 9.3 G32 tests

At minimum:

1. layer registry/classifier covers every required family;
2. DERIVED write-to-AUTHORITATIVE attempt fails closed;
3. CACHE write-to-AUTHORITATIVE attempt fails closed;
4. PRESENTATION write-to-AUTHORITATIVE attempt fails closed;
5. stale CharacterPanel snapshot cannot overwrite a newer stat/resource/skill/etc.;
6. CharacterPanelSnapshotV2 remains `DERIVED_PRESENTATION`;
7. PlayerSnapshot remains `DERIVED_PROJECTION` for every profile;
8. deleting/rebuilding finance balance projection preserves ledger and reconstructs exact balance;
9. deleting/rebuilding CharacterPanel/PlayerSnapshot/context projection loses no authority;
10. deleting a declared cache loses no canonical state;
11. newer presentation timestamp/version does not establish authority precedence;
12. receipt UPDATE/DELETE rejected in gameplay path;
13. Event UPDATE/DELETE rejected in gameplay path;
14. Causal edge UPDATE/DELETE rejected in gameplay path;
15. migration/restore path cannot be invoked through normal gameplay mutation capability;
16. restore validates/re-establishes schema/guards before gameplay opens;
17. Event/Causal integration preserves FACT/BELIEF/NARRATIVE separation;
18. finance ledger remains authority, balances remain projection;
19. ownership history remains domain authority rather than generic Event-store reconstruction;
20. derived resolver never persists resolved values as competing base authority;
21. all G31, G30 and Phase-26–29 tests green.

---

# 10. Boundary with Phase 33+

Phase 30–32 must expose only the interfaces later phases need, without implementing them.

## Phase 33 — Snapshot System

Allowed interface now:

- stable committed transaction/event order;
- read-only authoritative-state boundary;
- explicit layer classification so Phase 33 knows what may be snapshotted and what is rebuildable.

Do **not** implement durable replay snapshots, snapshot retention, snapshot compaction or snapshot-as-authority in Phase 30–32.

## Phase 34 — retention

Do not add event pruning/retention rules now. Phase-30 committed event history is append-preserved unless later retention architecture explicitly defines safe mechanics.

## Phase 35 — Canon Divergence

Do not infer divergence from event mismatches or create divergence engine now. Preserve typed truth/provenance so Phase 35 can consume it later.

## Phase 36 — migration completion

Phase 30/31 migrations must be additive/prospective and not attempt broad historical migration completion.

## Phase 37+ knowledge systems

Do not turn Event Store payloads or Causal Graph into a generic knowledge store.

## Phase 42 — Knowledge Graph / causal retrieval

Phase 31 supplies stable typed causal data only. No semantic retrieval, ranking, explanation engine or context selection should be implemented early.

## Phase 70 — Chronicle

Do not make Event Store a Chronicle and do not serialize narrative prose as canonical event truth by default. Chronicle may later consume event/truth/chapter evidence while preserving FACT/BELIEF/NARRATIVE.

---

# 11. Key architectural non-negotiables for implementation handoff

1. **TurnTransaction remains the canonical gameplay commit boundary.** Phase 30 extends it; it does not create a second transaction engine.
2. **Event Store is not event-sourcing migration.** Existing current/domain authorities remain authoritative.
3. **Finance ledger remains authoritative finance history.** Event Store records occurrence/provenance only.
4. **Ownership history remains authoritative ownership history.** Event Store does not reconstruct ownership as the new truth path.
5. **CampaignTruthStore remains sole typed truth authority.** Event/Causal records may reference truth but cannot override truth class/value.
6. **No fabricated legacy history.** Missing pre-feature provenance is unknown/not-recorded.
7. **Causal edges require semantic evidence.** Temporal order alone is insufficient.
8. **Derived freshness never beats authority.** A newer snapshot is still derived.
9. **Receipt must prove the complete committed bundle.** Phase 30/31 require strengthening result evidence so missing required event/edge records cannot coexist with a valid success receipt.
10. **Administrative authority stays separate from gameplay authority.** Recovery/migration exceptions cannot become a bypass.

---

# 12. Required preconditions before implementation starts

This audit finds no architectural reason to split the work into unrelated programs, but implementation should not begin until its worker contract explicitly freezes these decisions:

1. Event Store semantic role = append-only commit/historical evidence, **not** domain source-of-truth replacement.
2. Event identity/order = campaign-scoped stable UID + transaction identity + deterministic ordinal/fingerprint + Phase-29 `commitOrder` relation.
3. Event completeness is part of TurnTransaction atomic success and receipt result evidence.
4. Legacy migration is prospective, with no synthetic historical Event/Causal records.
5. Causal Graph stores typed immutable edges between canonical references, not mutable duplicated node truth.
6. Relation semantics and cycle policy are registered/fail-closed.
7. Phase-31 traversal is minimal; Phase 42 retrieval is deferred.
8. Phase-32 layer matrix is implemented as mechanical capability/write-direction enforcement, not just documentation.
9. G30/G31/G32 each require an exact-SHA green checkpoint before the next phase begins.

If an implementation proposal contradicts any of these decisions, it must return to architecture review rather than silently widening scope.

---

# 13. Final decision

## **READY WITH REQUIRED PRECONDITIONS**

Recommended implementation grouping:

**ONE sequential worker program with hard `G30 -> G31 -> G32` STOP GATES and separate exact-SHA validation at each gate.**

Phase 30, Phase 31 and Phase 32 remain **NOT ACCEPTED** and **NOT IMPLEMENTED** by this work item.

This work item modifies no runtime, schema, migration, production test, roadmap, acceptance record or TEST GM material. Its only intended repository artifact is this evidence/contract audit.
