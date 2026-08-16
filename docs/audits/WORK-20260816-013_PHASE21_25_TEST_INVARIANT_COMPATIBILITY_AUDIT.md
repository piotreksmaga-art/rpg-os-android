# WORK-20260816-013 — Phase 21–25 Test / Invariant / Compatibility Audit

## 1. Audit identity

- **Work ID:** `WORK-20260816-013`
- **Role:** CHAT-4 — independent test / invariant / compatibility reviewer
- **Mode:** READ-ONLY RUNTIME; evidence-only report commit permitted
- **Repository:** `piotreksmaga-art/rpg-os-android`
- **Exact runtime SHA audited:** `aae30b60b6276ceea6113ade22f27836bda78b26`
- **Accepted Phase-20 baseline used for regression comparison:** `38dafe5cc48c87f16218e346d9c0f9a96b6cee50`
- **Current master observed before report write:** `c7bae535e12c6c22a53fbee76c89c280cfd83906`
- **Later master drift:** exactly one docs-only commit adding `WORK-20260816-012_PHASE21_25_PLAYER_CORE_COMPLETION_IMPLEMENTATION.md`; audited runtime is not replaced by this documentation commit.
- **Exact-SHA CI independently verified:** `Validate RPG OS ALPHA`, run `#600`, ID `31967459040`, `head_sha=aae30b60b6276ceea6113ade22f27836bda78b26`, `completed / success`.

This audit applies only to runtime semantics represented by `aae30b60b6276ceea6113ade22f27836bda78b26`.

## 2. Final verdict

**FAIL — FIX REQUIRED BEFORE PHASE 21–25 ACCEPTANCE**

The exact candidate passes the focused Phase-21, Phase-23, Phase-24 and Phase-25 semantic checks and preserves the Phase-17–20 runtime by additive implementation. However, Gate 22 has an acceptance-blocking enforcement defect: the new no-retrogression validator is not on the canonical `PlayerDomainEngine.resolve()` result path. A caller using the existing canonical resolution API can still receive a resolved `PlayerChangeSet` containing unexplained durable negative stat/skill/technique progression without ever invoking `PlayerInvariantValidator`.

This is not a declaration of global acceptance or rejection beyond the exact candidate SHA.

## 3. Runtime delta and drift

Comparison `38dafe5c... -> aae30b60...` is additive for production runtime. The new production files are:

- `Phase21ProgressionPolicy.kt`
- `PlayerInvariantValidator.kt`
- `PlayerLedgerProvenance.kt`
- `CharacterPanelSnapshotV2.kt`
- `PlayerSnapshotBuilder.kt`

The corresponding Phase-21 through Phase-25 JVM tests were added. Existing Phase-17–20 production files, including `PlayerDomainEngine.kt` and `ProgressionEngine.kt`, were not modified by this candidate delta. This strongly limits regression surface and also proves the Gate-22 validator was not inserted into the existing `PlayerDomainEngine.resolve()` implementation.

Current master `c7bae535...` is one commit ahead of the candidate and changes documentation only.

## 4. Required source review

Reviewed independently:

- pre-implementation contract audit `WORK-20260816-010`;
- pre-implementation integrity/adversarial audit `WORK-20260816-011`;
- CHAT-1 implementation report `WORK-20260816-012` as context only;
- all five new production files at exact candidate SHA;
- all five new primary test files at exact candidate SHA;
- exact-SHA GitHub Actions run #600.

The pre-audits require Phase 22 to fail closed for unexplained durable progression regression and place invariant validation after structural PlayerChangeSet construction before the proposal is returned. They also distinguish this requirement from future Phase-26 single mutation-path/TurnTransaction enforcement.

## 5. Phase 21 — Diminishing returns + passive progression hooks

### Result: PASS

`Phase21DiminishingReturnsPolicy` is deterministic fixed-point/rational arithmetic over explicit `repetitionCount` and `resistanceUnits`. No process-local repetition state, clock, random source, database/store writer or second progression engine is present.

Novelty, adaptation, repetition, fatigue impact, injury impact and environment are typed factor kinds. `PassiveProgressionHookInput` canonicalizes factor evidence, evidence refs and dependency versions. `PassiveProgressionHook` is a pure adapter from explicit external causal evidence to an existing Phase-20 `ProgressionStimulus`; it does not advance time or commit anything.

Factor/evidence permutation remains stable because converted factors flow through the accepted Phase-20 canonical factor ordering. The Phase-21 tests explicitly compare reordered typed factors through `ProgressionEngine` and repeated passive-hook evaluations.

No Phase-20 identity algorithm was replaced.

## 6. Phase 22 — PlayerInvariantValidator + No-Retrogression

### Local validator semantics: PASS

When invoked directly, the validator correctly:

- rejects unexplained negative persistent stat progression;
- rejects unexplained negative skill and technique progression;
- accepts durable regression with a typed authorization;
- does not treat resource consumption, inventory removal, equipment removal or runtime/derived decreases as earned-progression retrogression;
- rejects snapshot campaign mismatch and mismatched authorization;
- has no writer capability;
- uses deterministic immutable authorization snapshots.

### Acceptance blocker: P21-25-C4-001 — validator is not enforced on canonical resolution path

**Severity: BLOCKER**

The implementation adds:

`PlayerDomainEngine.resolveWithPlayerInvariants(command, context, snapshotResolver)`

as an extension function. That function first calls existing `resolve(command, context)`, then reads an invariant snapshot, then validates the already-built proposal.

However, the existing canonical `PlayerDomainEngine.resolve()` itself is unchanged by the Phase-21–25 runtime delta. Therefore invariant validation is optional and caller-dependent.

The Gate-22 integration test demonstrates this exact shape: the negative-stat component is rejected only when the test calls `resolveWithPlayerInvariants(...)`; the test does not prove that ordinary `resolve(...)` rejects the same unexplained durable regression.

This violates the Phase-22 fail-closed contract established by the pre-audits: an unexplained durable permanent regression must not be returned as an accepted PlayerChangeSet proposal. Future Phase 26 may own a single mutation path / TurnTransaction, but that later enforcement is distinct from Phase 22's required proposal-level invariant gate.

### Reproduction by code path

Using the test's own `NegativeStatComponent` semantics:

1. component emits `StatChange(... ExactLongDelta.of(-1L) ...)`;
2. existing `PlayerDomainEngine.resolve(...)` completes its normal Phase-17–20 path and constructs a proposal;
3. because `PlayerDomainEngine.kt` is unchanged and does not call `PlayerInvariantValidator`, no no-retrogression validation occurs;
4. only callers that voluntarily switch to `resolveWithPlayerInvariants(...)` receive the intended rejection.

Thus the same runtime exposes both an invariant-enforcing and invariant-bypassing proposal resolution API.

### Required correction boundary

This audit does not prescribe or implement the fix. The requirement is semantic: Gate-22 no-retrogression validation must be unavoidable on the canonical proposal-return path while preserving the already accepted one-pinned-World-Pack / one-final-DRAFT_EFFECT_CHECK sequencing and without starting Phase 26.

## 7. Phase 23 — unified ledger/provenance integration

### Result: PASS for scoped Phase-23 contract

No global second writable player authority is introduced. The unified construct is a semantic provenance envelope/view only.

- finance remains referenced via `RPGOS-AUTHORITY:FINANCIAL_LEDGER`;
- ownership remains referenced via `RPGOS-AUTHORITY:OWNERSHIP_HISTORY`;
- progression remains `PROPOSAL_EVIDENCE` with `RPGOS-PROPOSAL:PROGRESSION_LEDGER_INTENT`;
- committed family references and proposal provenance are explicitly classified;
- legacy provenance can be represented as `UNKNOWN_NOT_RECORDED` with no fabricated refs.

P20-CB-01 is propagated prospectively through progression provenance envelopes. Evidence refs are deduplicated, canonically sorted, included in deterministic envelope identity, and fail closed for unknown or cross-campaign references.

No global commit/TurnTransaction authority is claimed.

## 8. Phase 24 — CharacterPanelSnapshot V2

### Result: PASS

`CharacterPanelSnapshotV2` is explicitly `DERIVED_PRESENTATION`. It has only a read-source interface and builder; no writer/commit capability exists.

The builder rereads source authority, copies/sorts exact values and fingerprints the projection. Tests demonstrate:

- repeated build equality;
- discard/delete and rebuild equality;
- zero source writes during build;
- stale snapshot remaining stale while a fresh rebuild reflects changed source state;
- exact Long numeric values survive projection;
- input order does not affect snapshot identity.

A stale snapshot has no mechanism to overwrite source authority.

## 9. Phase 25 — PlayerSnapshotBuilder + six profiles

### Result: PASS

All required profiles exist:

- FULL
- COMBAT
- PROGRESSION
- ECONOMY
- SOCIAL
- GM_CONTEXT

Every `PlayerSnapshot` is classified `DERIVED_PROJECTION`. Profile projection is implemented by rebuilding a panel from the full read projection; omitted sections become empty only in that profile and do not mutate or delete source facts.

Repeated builds have stable fingerprints and stable projected panel values.

ECONOMY carries the economy section including its authority record UID; it does not become finance authority. SOCIAL exposes relationship/goal projections and no NPC-knowledge store is introduced.

GM_CONTEXT obtains typed `PlayerTruthView` values and sorts/fingerprints with `truthClass` included. FACT, BELIEF and NARRATIVE remain distinct even when other truth fields collide; no flattening into one truth class occurs.

## 10. Adversarial matrix

| Case | Result |
|---|---|
| factor/evidence reorder | PASS — Phase-21 and Phase-20 canonicalization preserve identity |
| repeated passive evaluation | PASS — same explicit input => same stimulus/factors/evidence/dependencies |
| illegal negative stat/mastery change | **FAIL at system enforcement boundary** — validator rejects if invoked, but canonical `resolve()` can bypass it |
| legal typed injury regression | PASS through invariant-aware path |
| wrong campaign evidenceRef | PASS — Phase-23 provenance fails closed |
| legacy missing provenance | PASS — remains `UNKNOWN_NOT_RECORDED` |
| snapshot delete/rebuild | PASS |
| stale snapshot | PASS — stale data cannot override source; fresh rebuild differs |
| repeated profile builds | PASS |
| GM_CONTEXT truth-class collision | PASS by typed class in ordering/fingerprint |

## 11. Cross-phase Phase 17–20 regression

The candidate is additive relative to accepted Phase-20 runtime and does not modify existing Phase-17–20 production implementation files. Therefore:

- existing PlayerChangeSet semantics remain intact;
- Talent/Potential semantics remain intact;
- pinned WorldRuleProvider implementation remains intact;
- exactly one final `DRAFT_EFFECT_CHECK` implementation remains intact;
- Phase-20 `ProgressionEngine` remains proposal-only;
- Phase-20 progression IDs/fingerprints remain unchanged;
- the P20-C4-001 total canonical factor ordering fix remains unchanged.

The new Phase-22 optional wrapper calls existing `resolve()` exactly once and therefore does not itself introduce a second DRAFT_EFFECT_CHECK. The blocker is absence of mandatory invariant enforcement, not a regression in accepted Phase-17–20 sequencing.

## 12. CI / test evidence

GitHub Actions run independently verified:

- workflow: `Validate RPG OS ALPHA`
- run number: `#600`
- run ID: `31967459040`
- exact `head_sha`: `aae30b60b6276ceea6113ade22f27836bda78b26`
- status: `completed`
- conclusion: `success`

The job steps independently show success for:

- project validation;
- full JVM unit-test task;
- signed validation APK build;
- immutable validation artifact preparation/upload.

CI success does not negate P21-25-C4-001 because the existing Gate-22 integration test covers the opt-in wrapper and does not assert the canonical `resolve()` bypass is impossible.

A local exact-SHA Gradle rerun was not possible in this execution environment because direct network clone access was unavailable. Exact-SHA GitHub Actions provides the full JVM execution evidence; semantic enforcement was additionally checked statically against the exact source and test code.

## 13. Findings

### P21-25-C4-001 — BLOCKER — PlayerInvariantValidator can be bypassed through canonical `PlayerDomainEngine.resolve()`

Impact: unexplained durable negative stat/skill/technique progression can still be returned as a resolved proposal by an existing public/canonical resolution path.

Why acceptance-blocking: Phase 22 is specifically the no-retrogression invariant gate. A validator that is correct but optional does not establish the invariant.

No runtime or test repair was performed by CHAT-4.

## 14. Final verdict

**FAIL — FIX REQUIRED BEFORE PHASE 21–25 ACCEPTANCE**

This verdict is bound **ONLY** to exact runtime SHA:

`aae30b60b6276ceea6113ade22f27836bda78b26`

No Phase 26 work was started and no global acceptance decision is made by this audit.
