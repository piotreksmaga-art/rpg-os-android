# WORK-20260816-014 — Phase 21–25 Cross-Boundary / Source-of-Truth Audit

## 1. Audit identity

- **Work ID:** `WORK-20260816-014`
- **Role:** CHAT-5 — independent cross-boundary / source-of-truth / architecture regression reviewer
- **Mode:** READ-ONLY RUNTIME; evidence-only report commit permitted
- **Repository:** `piotreksmaga-art/rpg-os-android`
- **Branch:** `master`
- **Exact runtime SHA audited:** `aae30b60b6276ceea6113ade22f27836bda78b26`
- **Accepted Phase-20 comparison baseline:** `38dafe5cc48c87f16218e346d9c0f9a96b6cee50`
- **Current master observed immediately before report write:** `50132428627cb92792587fc7d581baec7915ae09`
- **Current-master drift from audited runtime:** documentation/audit only; runtime candidate remains `aae30b60...`
- **Exact-SHA CI:** `Validate RPG OS ALPHA`, run `#600`, ID `31967459040`, `head_sha=aae30b60b6276ceea6113ade22f27836bda78b26`, `completed / success`.

This report evaluates only the runtime semantics of `aae30b60b6276ceea6113ade22f27836bda78b26`. Later documentation commits are evidence/context only and are not substituted for the requested runtime.

## 2. Final verdict

**FAIL — FIX REQUIRED BEFORE PHASE 21–25 ACCEPTANCE**

The candidate preserves the accepted Phase-19/20 progression and WorldRuleProvider architecture, keeps Phase-21 arithmetic/policy ownership non-duplicative, keeps Phase-23 provenance proposal-only and future-transaction-compatible, and correctly implements Phase-24/25 as disposable derived read models.

However, Phase 22 has one cross-boundary acceptance blocker: `PlayerInvariantValidator` is not unavoidable on the existing `PlayerDomainEngine.resolve()` proposal-return path. The runtime exposes both:

- `PlayerDomainEngine.resolve(...)` — existing path that can return a structurally valid `PlayerChangeSet` without Phase-22 no-retrogression validation; and
- `resolveWithPlayerInvariants(...)` — an optional extension wrapper that performs Phase-22 validation after `resolve(...)` has already produced the proposal.

Therefore two resolution entry paths can independently answer whether an unexplained durable negative Stat/Skill/Technique proposal is accepted: one accepts it structurally, the other rejects it by Phase-22 invariant policy. This is exactly the kind of cross-boundary split authority this audit is responsible for detecting.

This finding does **not** require Phase-26 `TurnTransaction` or global commit infrastructure. The pre-implementation Phase-22 contract explicitly placed `PlayerInvariantValidator` after structural PlayerChangeSet construction and before returning the proposal. Phase 26 may later enforce a single mutation/commit entry point, but Phase 22 already owns the proposal-level no-retrogression gate.

No other BLOCKER/HIGH finding was identified.

## 3. Exact runtime delta

Comparison `38dafe5cc48c87f16218e346d9c0f9a96b6cee50 -> aae30b60b6276ceea6113ade22f27836bda78b26` shows eleven commits, with production-runtime additions limited to five new files:

- `Phase21ProgressionPolicy.kt`
- `PlayerInvariantValidator.kt`
- `PlayerLedgerProvenance.kt`
- `CharacterPanelSnapshotV2.kt`
- `PlayerSnapshotBuilder.kt`

The corresponding Phase-21 through Phase-25 tests were added. No existing `PlayerDomainEngine.kt`, `ProgressionEngine.kt`, `WorldRuleProvider.kt`, financial/ownership store, schema, migration, or package-format file was changed in this candidate delta.

Consequences:

1. Phase-19 and Phase-20 runtime behavior is inherited unchanged from the accepted Phase-20 candidate.
2. No schema/migration delta unexpectedly landed in Phase 21–25.
3. The Phase-22 validator was not inserted into the existing `PlayerDomainEngine.resolve()` implementation; it exists only in the newly added wrapper.

## 4. Actual runtime pipeline

### 4.1 Phase-19/20 engine path

The actual existing `PlayerDomainEngine.resolve(...)` ordering remains:

```text
PlayerCommand
-> command registry validation / canonical encode-decode / command fingerprint
-> context campaign + actor validation
-> command reference closure
-> canonical World Pack authority validation
-> WorldRuleProvider COMMAND_PRECHECK
-> typed PlayerResolutionComponent.resolve(...)
-> base PlayerResolutionDraft
-> base draft reference closure
-> Phase-20 progression augmentation
   -> ProgressionEvaluationInput
   -> ProgressionEngine.evaluate(...)
   -> ProgressionGrant
   -> existing StatChange / SkillChange / TechniqueChange
   -> PlayerLedgerIntent(PROGRESSION)
-> augmented draft reference closure
-> WorldRuleEffectSnapshot(augmented draft)
-> exactly one final WorldRuleProvider DRAFT_EFFECT_CHECK
-> assemble existing PlayerChangeSet proposal
-> PlayerChangeSetValidator structural validation
-> PlayerResolutionOutcome.Resolved(proposal)
```

Phase-21 policy/hook code is not a second engine stage inside `PlayerDomainEngine`. It is a pure pre-evaluation policy/adapter that converts explicit supplied causal evidence into typed Phase-20 factors/stimuli. Final grant arithmetic still occurs only in the existing Phase-20 `ProgressionEngine`.

### 4.2 Phase-22 path as implemented

The newly added canonical-intent wrapper is:

```text
PlayerDomainEngine.resolveWithPlayerInvariants(...)
-> calls PlayerDomainEngine.resolve(...) exactly once
-> if Resolved: obtain immutable PlayerInvariantSnapshot
-> PlayerInvariantValidator.validate(PlayerChangeSet, snapshot)
-> return original Resolved outcome OR convert to Rejected
```

This sequencing itself is architecturally correct: it preserves the accepted single pinned World Pack authority and one final `DRAFT_EFFECT_CHECK`, then validates a structurally valid proposal.

The problem is enforcement: ordinary `PlayerDomainEngine.resolve(...)` remains directly callable and returns before this Phase-22 gate.

### 4.3 Phase-23 and future transaction boundary

`PlayerLedgerProvenance` is not an automatic mutating stage in `PlayerDomainEngine`. It is a deterministic semantic envelope/view layer over proposal evidence and already-committed family references.

The correct current boundary is therefore:

```text
validated PlayerChangeSet proposal
-> Phase-23 provenance envelope/view semantics as needed
-> FUTURE TurnTransaction / fresh commit-time validation
-> FUTURE events + ledgers + authoritative state + provenance atomic commit
```

No TurnTransaction exists in the audited runtime.

### 4.4 Read-model path

After future/current authoritative stores own state, the read path is one-way:

```text
authoritative domain stores / family histories
-> CharacterPanelV2ReadSource
-> CharacterPanelSnapshotV2Builder
-> CharacterPanelSnapshotV2 (DERIVED_PRESENTATION)
-> PlayerSnapshotBuilder
-> PlayerSnapshot profile (DERIVED_PROJECTION)
```

There is no reverse writer path from snapshot/profile objects to authoritative state.

## 5. Source-of-truth map

| Area | Authoritative/current owner | Phase 21–25 representation | Authority class | Duplicate authority? | Verdict |
|---|---|---|---|---|---|
| Persistent Stats | existing typed player-stat state/store | panel/profile projection | AUTHORITATIVE store -> DERIVED view | No | PASS |
| Persistent Skills | existing typed skill state/store | panel/profile projection | AUTHORITATIVE store -> DERIVED view | No | PASS |
| Persistent Techniques | existing typed technique state/store | panel/profile projection | AUTHORITATIVE store -> DERIVED view | No | PASS |
| Progression arithmetic | Phase-20 `ProgressionEngine` | Phase-21 supplies typed factors/stimuli only | DERIVED proposal calculation | No | PASS |
| Diminishing returns | Phase-21 policy | deterministic factor generation | POLICY / DERIVED factor | No | PASS |
| Passive progression conversion | Phase-21 passive hook/adapter | explicit cause -> existing `ProgressionStimulus` | PROPOSAL adapter | No | PASS |
| World legality | Phase-19 `WorldRuleProvider` | unchanged | READ-ONLY legality authority | No | PASS |
| Player no-retrogression | Phase-22 `PlayerInvariantValidator` | immutable snapshot + validation result | PROPOSAL legality/invariant gate | **Two callable resolution paths disagree because gate is optional** | **FAIL** |
| Progression provenance | Phase-23 envelope over proposal evidence | `PROPOSAL_EVIDENCE` | PROPOSAL/EVIDENCE | No | PASS |
| Financial truth | existing `FinancialStore` / `financial_ledger_transactions` | Phase-23 committed-family reference only; panel summary carries authority record UID | AUTHORITATIVE financial ledger | No | PASS |
| Ownership truth | existing `OwnershipStore` / ownership history records | Phase-23 committed-family reference only; panel summary | AUTHORITATIVE ownership history | No | PASS |
| Character panel | authoritative sources listed above | `CharacterPanelSnapshotV2` | DERIVED_PRESENTATION | No | PASS |
| Player profiles | panel/read source | `PlayerSnapshot` | DERIVED_PROJECTION | No | PASS |
| Future global commit | not implemented | references only | FUTURE AUTHORITY | No premature implementation | PASS |

## 6. Mechanic ownership audit

### Progression arithmetic — one owner

Phase 21 does not create a competing gain engine. It produces deterministic `ProgressionCalculationFactor` values and passive `ProgressionStimulus` inputs that are consumed by the already accepted Phase-20 `ProgressionEngine`. The actual multiplication/fixed-point rounding/grant identity chain remains Phase 20.

**Verdict: PASS.**

### Diminishing returns — one owner

The diminishing-return policy is Phase-21-owned and stateless/deterministic over explicit evidence such as repetition/adaptation/novelty inputs. No second persistent repetition balance or `DiminishingReturnsEngine` was added.

**Verdict: PASS.**

### Passive progression conversion — one owner

The Phase-21 passive adapter/hook does not schedule time, advance time, read a clock, mutate player state, or commit a grant. It only converts an already-resolved external cause into Phase-20-compatible stimuli.

**Verdict: PASS.**

### World legality — one owner

No World Pack-owned Player Engine or provider-owned progression orchestrator was added. Final augmented effects still pass through the same pinned Phase-19 provider before PlayerChangeSet assembly.

**Verdict: PASS.**

### Player invariants / no-retrogression — implementation correct, integration not authoritative enough

`PlayerInvariantValidator` itself has the correct narrow ownership: it neither re-runs progression arithmetic nor evaluates World Pack legality, and it has no writer. But the runtime does not make this owner authoritative over all returned player proposals because callers may use the unchanged `resolve(...)` path.

**Verdict: BLOCKER.**

### Progression provenance — one owner

Phase 23 creates proposal-level provenance envelopes rather than committed history. Forward-going `ProgressionStimulus.evidenceRefs` are incorporated prospectively; legacy unknown provenance remains explicitly unknown.

**Verdict: PASS.**

### Financial truth — one owner

`FinancialStore` remains the existing authoritative financial history owner. `financial_ledger_transactions` is authoritative and `financial_account_balances` is a rebuildable projection checked against ledger-derived balance. Phase-23 unified provenance references finance by family authority/source-record UID; it does not copy financial balance/history into a second writable ledger.

**Verdict: PASS.**

### Ownership truth — one owner

`OwnershipStore` remains the authoritative owner of current/history ownership records and atomic transfer semantics. Phase-23 provenance and Phase-24/25 read models reference/project ownership; they do not create a second ownership writer.

**Verdict: PASS.**

### CharacterPanel derivation and profile projection — one owner each

`CharacterPanelSnapshotV2Builder` owns the deterministic panel read projection; `PlayerSnapshotBuilder` owns profile omission/projection. Neither is a gameplay authority.

**Verdict: PASS.**

## 7. Primary finding

### `P21-25-CB-01` — Phase-22 no-retrogression gate is bypassable at proposal-return boundary

**Severity: BLOCKER**

#### Evidence

The accepted pre-implementation Phase-22 contract states the required order:

```text
... -> construct + structural-validate PlayerChangeSet
-> PlayerInvariantValidator
-> return proposal
```

The audited candidate instead leaves existing `PlayerDomainEngine.resolve(...)` unchanged and adds:

`PlayerDomainEngine.resolveWithPlayerInvariants(...)`

as an extension function that calls `resolve(...)`, then validates only if the caller selected that wrapper.

The validator correctly identifies unexplained negative `StatChange`, `SkillChange`, and `TechniqueChange` deltas and requires an exact typed authorization. But the unchanged raw `resolve(...)` can return the same structurally valid negative proposal without invoking this policy.

#### Cross-boundary consequence

There are now two callable mechanisms that can independently answer the same proposal-level truth:

- raw `resolve(...)`: structurally valid negative durable change may be returned `Resolved`;
- `resolveWithPlayerInvariants(...)`: same proposal is rejected without typed regression authorization.

That is a competing acceptance boundary, even though neither path commits state yet.

#### Why this is not merely Phase-26 work

Phase 26 owns broader Single Truth Mutation Path enforcement. It may later make one command/mutation entry path globally unavoidable and integrate future transaction semantics.

Phase 22 nevertheless owns its own no-retrogression acceptance rule. The Phase-21–25 contract explicitly requires that rule before a PlayerChangeSet proposal is returned. Making the validator optional until Phase 26 would permit invalid proposals to escape the Player Core boundary that Phase 22 is intended to complete.

#### Required semantic correction

This audit is read-only and does not prescribe implementation details. The acceptance requirement is only:

- the canonical PlayerDomainEngine proposal-return path must not allow a durable Stat/Skill/Technique regression to escape without Phase-22 validation;
- the fix must preserve one Phase-19 pinned binding, one final `DRAFT_EFFECT_CHECK`, and proposal-only Phase-20 semantics;
- it must not implement TurnTransaction or other Phase-26+ responsibilities.

## 8. Phase-23 / future Transactional Campaign Core boundary

### Result: PASS

No premature transaction authority was found.

The audited runtime does **not** introduce:

- `TurnTransaction`;
- global atomic state+event+ledger commit claims;
- a unified writable player ledger;
- a second append-only progression history;
- global retry/idempotency guarantees;
- an Event Store authority change;
- snapshot/replay commit authority.

Phase-23 provenance has three explicit semantic states/classes relevant here:

- proposal progression evidence;
- references to already committed family records (finance/ownership);
- unknown legacy provenance.

It does not promote a progression ledger intent into committed history.

### P20-CB-01

The earlier LOW finding is resolved **forward-only**: new Phase-23 progression provenance can include canonicalized/validated `ProgressionStimulus.evidenceRefs`. Existing/legacy artifacts without such refs remain valid historical absence/unknown provenance. There is no retroactive fabrication.

### Future compatibility

A future TurnTransaction can consume the same typed PlayerChangeSet, invariant contract and provenance envelopes without having to reconcile two incompatible committed player ledgers. Finance and ownership family authority remain family-owned.

## 9. Phase-24/25 authority and rebuildability

### CharacterPanelSnapshotV2

**Classification:** `DERIVED_PRESENTATION`.

The builder consumes only `CharacterPanelV2ReadSource`. It sorts/copies source values and fingerprints the result. It does not expose DB/store/repository/transaction/writer capability.

Permanent source-of-truth property:

```text
delete CharacterPanelSnapshotV2
-> authoritative state unchanged
-> rebuild from CharacterPanelV2ReadSource
-> deterministic equivalent snapshot for unchanged authority
```

The Phase-24 regression test proves repeated build equality, delete/discard-and-rebuild equality, zero source writes, stale snapshot inability to override changed source state, and deterministic identity independent of source ordering.

### PlayerSnapshotBuilder profiles

**Classification:** `DERIVED_PROJECTION`.

The six profiles are:

- FULL
- COMBAT
- PROGRESSION
- ECONOMY
- SOCIAL
- GM_CONTEXT

Profile omission semantics are projection-only. For example, COMBAT may omit economy while FULL/ECONOMY still reproduce the same underlying financial value from the source. An omitted profile field therefore means **not projected**, not **false**, **deleted**, or **unknown authoritative state**.

Delete/rebuild of a PlayerSnapshot only causes rereads; it does not mutate authority.

### FACT != BELIEF != NARRATIVE

`GM_CONTEXT` carries typed `PlayerTruthView` records with `PlayerTruthClass.FACT`, `BELIEF`, and `NARRATIVE`. The profile test explicitly verifies all three remain present and distinct. No NPC Knowledge store or omniscient flattened truth channel is introduced by Phase 25.

**Verdict: PASS.**

## 10. Legacy / compatibility audit

### Old campaign representability

No Phase-21–25 schema or migration file changed. Existing typed/legacy reconciliation paths therefore remain the representation substrate. Phase-24/25 are read projections over supplied sources rather than destructive migrations.

### Unknown provenance remains unknown

Phase 23 contains an explicit legacy-unknown representation instead of inventing source events/evidence. No historical progression causes are fabricated.

### Stable UID semantics

No accepted Phase-20 progression identity algorithm was changed. Phase-21 adds deterministic factor evidence that flows through the accepted canonical factor ordering. Phase-23 envelopes use deterministic semantic identities rather than random/time/object identity.

### Migration/read/rebuild cannot trigger progression

No migration code was changed by the Phase-21–25 delta. `CharacterPanelSnapshotV2Builder` and `PlayerSnapshotBuilder` are read-only. The Phase-21 passive hook requires explicit causal input and has no scheduler/time-advance/load hook. Therefore load/read/rebuild is not a passive-progression trigger in this candidate.

**Verdict: PASS.**

## 11. Phase-19 / Phase-20 regression audit

### Phase 19

`PlayerDomainEngine.kt` and `WorldRuleProvider.kt` were not changed by Phase 21–25. The accepted ordering remains:

`COMMAND_PRECHECK -> base resolution -> Phase-20 progression augmentation -> augmented closure -> one final DRAFT_EFFECT_CHECK -> proposal`.

The Phase-22 wrapper calls this engine once and therefore, when used, does not duplicate provider evaluation or rebind World Pack authority.

**ONE RESOLUTION = ONE PINNED WORLD PACK AUTHORITY remains intact.**

### Phase 20

`ProgressionEngine.kt` was not changed from the accepted deterministic Phase-20 runtime. It remains proposal-only and has no DB/store/transaction writer capability. Phase-21 policy adds factors/stimuli rather than another final gain calculator.

**Verdict: PASS.**

## 12. Phase-26+ scope-creep check

No Phase-21–25 runtime delta implements:

- Single Truth Mutation Path enforcement;
- `TurnTransaction`;
- global transaction idempotency;
- Event Store authority redesign;
- Phase-33 Snapshot System;
- NPC Knowledge authority/store;
- Temporal Engine/Scheduler;
- Time Skip Processor;
- World Simulation.

An older `ContextBuilder.kt` exists elsewhere in the repository, but it predates this Phase-21–25 delta and was not added or modified by the candidate. It is therefore not Phase-21–25 scope creep.

The Phase-22 optional wrapper is notably **not** Single Truth Mutation Path enforcement; this absence is expected for Phase 26. The blocker is narrower: Phase 22's own invariant gate is optional at the proposal-return boundary.

**Verdict: PASS for scope containment, subject to the Phase-22 blocker above.**

## 13. Failure / mixed-authority analysis

- If Phase-20 progression fails, existing `PlayerDomainEngine` fails before returning proposal; no authoritative partial mutation occurs.
- If final WorldRuleProvider rejects progression-augmented effects, no proposal success/commit occurs.
- If Phase-22 wrapper invariant validation rejects, it converts the proposal result to `Rejected`; no writer is present in the wrapper/validator.
- Phase-23 provenance envelope construction has no writer and cannot partially commit finance/ownership/progression.
- Phase-24/25 read projections cannot write back.

The only mixed-acceptance condition is the Phase-22 bypass: a caller choosing raw `resolve(...)` avoids the no-retrogression gate entirely.

## 14. Findings by severity

| ID | Severity | Finding | Acceptance impact |
|---|---|---|---|
| `P21-25-CB-01` | **BLOCKER** | `PlayerInvariantValidator` is optional because unchanged `PlayerDomainEngine.resolve()` can return a proposal without Phase-22 validation; wrapper-only enforcement creates two proposal acceptance paths. | Blocks Phase 21–25 acceptance. |
| `P20-CB-01` | CLOSED / forward-only | Phase-23 prospective provenance carries new evidence refs while legacy absence remains unknown/not fabricated. | No blocker. |
| Phase-26 global single mutation path | DEFERRED / EXPECTED | Not implemented by candidate, correctly deferred. | No blocker by itself. |
| TurnTransaction/global atomic commit | DEFERRED / EXPECTED | Not implemented by candidate. | No blocker by itself. |

## 15. Blockers

**One blocker:** `P21-25-CB-01`.

All other cross-boundary/source-of-truth areas reviewed here pass on the exact candidate.

## 16. CI evidence

Exact GitHub Actions run independently checked:

- workflow: `Validate RPG OS ALPHA`
- run number: `#600`
- run ID: `31967459040`
- `head_sha`: `aae30b60b6276ceea6113ade22f27836bda78b26`
- status: `completed`
- conclusion: `success`

Green CI confirms the tested implementation state but does not override the architecture blocker because the current test surface validates the wrapper path rather than proving invariant enforcement through ordinary `PlayerDomainEngine.resolve(...)`.

## 17. Final verdict binding

**FAIL — FIX REQUIRED BEFORE PHASE 21–25 ACCEPTANCE**

This verdict applies **only** to exact runtime SHA:

`aae30b60b6276ceea6113ade22f27836bda78b26`

This report does **not** declare Phase 21–25 accepted or globally rejected beyond this candidate. Only the coordinator may make the acceptance decision after a corrected exact-SHA revalidation.