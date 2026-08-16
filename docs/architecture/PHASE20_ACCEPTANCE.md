# Phase 20 — Canonical Acceptance Record

Status: ACCEPTED / COMPLETE

This is the concise durable acceptance record for **Phase 20 — ProgressionEngine + Progression Ledger proposal semantics**. Detailed implementation, audit, failure, fix, and revalidation narratives remain under `docs/audits/`.

## Canonical accepted runtime

- Runtime SHA: `38dafe5cc48c87f16218e346d9c0f9a96b6cee50`
- Exact acceptance CI: run #578 / ID `31961047982`
- CI status/conclusion: `completed / success`
- Exact-SHA validation included project validation, full `:app:testDebugUnitTest`, release build, and validation artifact generation.
- Publication: `false`
- User-facing Phase-20 release published: **NO**

Later documentation-only commits do not change the canonical accepted runtime SHA.

## Evidence chain

Pre-implementation:

- `WORK-20260816-002` — CHAT-2 contract/architecture audit — READY FOR CHAT-1 IMPLEMENTATION
- `WORK-20260816-003` — CHAT-3 integrity/migration/adversarial audit — READY FOR CHAT-1 IMPLEMENTATION

Implementation and first post-audit:

- `WORK-20260816-004` — CHAT-1 implementation/completion review — IMPLEMENTATION COMPLETE — READY FOR INDEPENDENT POST-AUDIT
- First runtime candidate: `a09e22e6505be7849e34fbd27faf2cc36d5bceef`
- `WORK-20260816-005` — CHAT-4 — FAIL; found acceptance blocker `P20-C4-001`
- `WORK-20260816-006` — CHAT-5 — PASS; no cross-boundary/source-of-truth blocker

Targeted fix and exact-SHA revalidation:

- `WORK-20260816-007` — CHAT-1 targeted determinism fix
- Final runtime candidate: `38dafe5cc48c87f16218e346d9c0f9a96b6cee50`
- `WORK-20260816-008` — CHAT-4 — PASS — `P20-C4-001` FIX VERIFIED
- `WORK-20260816-009` — CHAT-5 — PASS — CROSS-BOUNDARY REVALIDATION PASSED

Both final independent PASS verdicts are explicitly bound to the same exact runtime SHA `38dafe5cc48c87f16218e346d9c0f9a96b6cee50`.

Coordinator decision: **PHASE 20 = ACCEPTED / COMPLETE**.

## Accepted scope

Phase 20 establishes the Core-owned, universe-agnostic progression proposal layer, including:

- pure, deterministic, side-effect-free `ProgressionEngine` evaluation;
- causal progression stimuli and deterministic progression results;
- deterministic stable identities and fingerprints for progression/grants/ledger intents;
- versioned deterministic numeric/fixed-point boundary with fail-closed invalid numeric inputs;
- mapping accepted progression grants into the existing typed `StatChange`, `SkillChange`, and `TechniqueChange` path rather than a parallel change engine;
- typed progression evidence carried through the existing `PlayerLedgerIntent` family;
- progression ledger semantics as proposal/causal evidence, not committed authoritative history;
- augmented reference closure covering progression-generated references;
- progression integration before the single final Phase-19 `DRAFT_EFFECT_CHECK`;
- preservation of the same pinned World Pack authority across resolution;
- no authoritative state mutation during progression resolution;
- no second Player Engine and no second persisted source of truth;
- preservation of legacy progression evidence/mappings without fabricated historical reinterpretation;
- no database/schema migration required for the accepted Phase-20 runtime.

## Closed acceptance blocker

Closed blocker:

`P20-C4-001`

The first candidate used incomplete canonical ordering for `ProgressionCalculationFactor`. Factors sharing `(factorKindUid, evidenceUid)` but differing in semantic numeric values could retain insertion order and therefore produce different deterministic identity/fingerprint chains for semantically equivalent factor multisets.

The accepted runtime centralizes canonical factor ordering using:

`factorKindUid -> evidenceUid -> sourceValue.scaledUnits -> appliedFactor.scaledUnits -> full factor fingerprint`

Regression coverage verifies permutation-invariant arithmetic result and the full identity chain, including input fingerprint, progression UID, computation identity/fingerprint, grant UID, causal change UID, ledger intent UID, result fingerprint, and complete `ProgressionResult`.

## Deferred finding

`P20-CB-01` remains **LOW / DEFERRED**.

`ProgressionStimulus.evidenceRefs` currently participate in reference closure but are not copied into `ProgressionEvaluationInput` / `ProgressionLedgerIntentPayload`. This does not create a Phase-20 source-of-truth collision or acceptance blocker. A later provenance design, especially Phase 23, must decide whether these references are legality-only metadata or durable provenance. Historical references must not be fabricated retroactively.

## Phase boundaries preserved

Phase 20 does **not** implement:

- Phase 21 full diminishing returns, novelty/adaptation, passive progression, or time-skip progression orchestration;
- Phase 22 global Player Invariant Validator / No-Retrogression engine;
- Phase 23 committed unified player ledgers/provenance authority;
- TurnTransaction, global COMMIT, retry/idempotency, Event Store authority, Snapshot System, Save/Load, or crash recovery.

## Accepted deltas

```text
PROGRESSION ENGINE: ACCEPTED
PROGRESSION LEDGER INTENT / PROPOSAL EVIDENCE: ACCEPTED
PLAYERCHANGESET PARALLEL AUTHORITY: NONE
SECOND PLAYER ENGINE: NONE
SECOND PERSISTED AUTHORITY: NONE
DATABASE MIGRATION DELTA: NONE
PHASE-21 ACTIVE RUNTIME DELTA: NONE
PHASE-22 ACTIVE RUNTIME DELTA: NONE
PHASE-23 COMMITTED LEDGER DELTA: NONE
```

## Next stage

The next engine stage is **Phase 21 — Diminishing Returns + passive progression hooks**.

Phase 21 must begin with **AUDIT FIRST**. This acceptance record does not authorize Phase-21 implementation by itself. The coordinator must inspect the current repository/runtime and explicitly authorize the next work items.
