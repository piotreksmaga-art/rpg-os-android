# WORK-20260816-005 — Phase 20 Post-Implementation Test / Invariant / Compatibility Audit

## 1. Audit identity

- **Work ID:** `WORK-20260816-005`
- **Role:** CHAT-4 — independent test / invariant / compatibility reviewer
- **Mode:** READ-ONLY AUDIT
- **Repository:** `piotreksmaga-art/rpg-os-android`
- **Branch used for repository coordination:** `master`
- **Exact semantic runtime audited:** `a09e22e6505be7849e34fbd27faf2cc36d5bceef`
- **Pre-Phase-20 baseline:** `ccf14eace3d23ba519624ec6fe3156e1436c340a`
- **Master HEAD observed immediately before writing this evidence-only audit report:** `d79161712291fac7af04f8df1dea1fcc9b31f425`
- **Known candidate CI evidence:** `Validate RPG OS ALPHA`, run `#548`, run ID `31958516535`, `completed / success`

This report audits **only** runtime semantics represented by `a09e22e6505be7849e34fbd27faf2cc36d5bceef`. Later documentation commits, including the CHAT-1 implementation report and `docs/test-gm/**`, were used only as evidence/context and were not substituted for the candidate runtime.

## 2. Final verdict

**FAIL — FIX REQUIRED BEFORE PHASE 20 ACCEPTANCE**

The candidate satisfies the large majority of Phase-20 purity, numeric-safety, causal-provenance, reference-closure, WorldRuleProvider, ledger, compatibility, failure-boundary, and Phase-17/18/19 regression requirements. However, one acceptance-blocking determinism defect remains in canonical ordering of progression calculation factors.

The defect permits two semantically equivalent factor collections to produce the same arithmetic progression result while producing different deterministic identity/fingerprint chains. This violates the required replay/determinism contract for `progressionUid`, `grantUid`, `ledgerIntentUid`, and associated fingerprints.

This verdict is **not** a global Phase-20 acceptance decision. Global acceptance remains coordinator-owned.

## 3. Repository-first bootstrap and drift analysis

### 3.1 Baseline ancestry

Comparison of:

`ccf14eace3d23ba519624ec6fe3156e1436c340a`

→

`a09e22e6505be7849e34fbd27faf2cc36d5bceef`

shows the candidate is **7 commits ahead** of the pre-Phase-20 baseline and **0 commits behind**, with the baseline as merge base.

The Phase-20 candidate changes are confined to the following runtime/test files:

- `app/src/main/java/com/rpgos/app/PlayerChangeSetCodec.kt`
- `app/src/main/java/com/rpgos/app/PlayerDomainEngine.kt`
- `app/src/main/java/com/rpgos/app/ProgressionEngine.kt`
- `app/src/main/java/com/rpgos/app/ProgressionLedgerIntent.kt`
- `app/src/main/java/com/rpgos/app/ProgressionLedgerKindExtension.kt`
- `app/src/main/java/com/rpgos/app/WorldRuleProvider.kt`
- `app/src/test/java/com/rpgos/app/Phase20ProgressionEngineTest.kt`

No Phase-20 schema migration, authoritative progression store, transaction layer, event-store redesign, or snapshot/replay persistence implementation appears in this candidate delta.

### 3.2 Drift after the candidate

At audit time, `master` had advanced to:

`d79161712291fac7af04f8df1dea1fcc9b31f425`

Comparison of candidate `a09e22e...` to that HEAD showed **26 commits ahead of the candidate**, all confined to documentation paths. The changed paths consist of:

- `docs/audits/WORK-20260816-004_PHASE20_IMPLEMENTATION.md`
- `docs/test-gm/ACCEPTED_RUNTIME_GUIDE.md`
- `docs/test-gm/GM_TEST_BOOTSTRAP.md`
- `docs/test-gm/GM_TEST_RULES.md`
- `docs/test-gm/README.md`
- `docs/test-gm/phases/PHASE_01.md` through `PHASE_19.md`

No runtime source/test drift was present in those 26 commits. Therefore later master documentation did not contaminate the semantic runtime under audit.

## 4. Evidence inspected

### 4.1 Required project/architecture documents

The audit reviewed:

- `docs/PROJECT_WORK_PROTOCOL.md`
- `docs/RPG_OS_MASTER_ARCHITECTURE.md`
- `docs/RPG_OS_IMPLEMENTATION_ROADMAP.md`
- `docs/GM_ENGINE_TARGET_ARCHITECTURE.md`
- `docs/PARALLEL_WORK_COORDINATION.md`
- `docs/architecture/CHAT_COORDINATION_POLICY.md`
- `docs/architecture/PHASE19_ACCEPTANCE.md`
- `docs/architecture/PHASE19_WORLD_RULE_PROVIDER_CANONICAL_SCOPE.md`
- `docs/architecture/PHASE19_DEFERRED_FINDINGS.md`
- `docs/architecture/POST_ENGINE_APPLICATION_CLEANUP_ROADMAP.md`

The governing architectural interpretation used in this audit is:

1. Phase 20 owns deterministic **proposal/evidence** progression computation, not persistence or commit.
2. Permanent progression requires a legal causal stimulus; Talent/Potential may modify a legal progression calculation but may not spontaneously cause permanent gain.
3. Progression-generated effects must be included before the one final `DRAFT_EFFECT_CHECK`.
4. Phase 19's one-resolution/one-pinned-World-Pack authority contract remains binding.
5. Global transactions, committed unified ledgers, global provenance persistence, replay architecture, and global retry/idempotency are later-phase responsibilities and must not be demanded from Phase 20.

### 4.2 Pre-implementation audits

Reviewed:

- `docs/audits/WORK-20260816-002_PHASE20_CONTRACT_ARCHITECTURE_AUDIT.md`
- `docs/audits/WORK-20260816-003_PHASE20_INTEGRITY_MIGRATION_ADVERSARIAL_AUDIT.md`

These correctly established the Phase-20 boundary as a minimal pure engine plus typed proposal/evidence integration before the final effect check, with no new authoritative progression persistence.

### 4.3 CHAT-1 implementation report

Reviewed as later documentation-only context:

- `docs/audits/WORK-20260816-004_PHASE20_IMPLEMENTATION.md`

Its claims were not treated as proof. Runtime behavior was inspected independently at the exact candidate SHA.

### 4.4 Runtime files inspected at exact candidate SHA

- `ProgressionEngine.kt`
- `PlayerDomainEngine.kt`
- `ProgressionLedgerIntent.kt`
- `ProgressionLedgerKindExtension.kt`
- `PlayerChangeSetCodec.kt`
- `WorldRuleProvider.kt`

### 4.5 Tests inspected

Primary Phase-20 test suite:

- `Phase20ProgressionEngineTest.kt`

Representative/canonical regression suites inspected or traced:

- `Phase19CanonicalRegressionTest.kt`
- Phase-19 canonical authority/provider/coherence/rollback test family present in the candidate test tree
- Phase-17/18 behaviors exercised by the canonical Phase-19 regression gate
- existing PlayerDomainEngine reference/orchestration regressions exercised by exact-SHA JVM test execution

### 4.6 Tests executed / CI evidence

The exact candidate's GitHub Actions run was independently checked rather than merely relying on the supplied statement.

Run `31958516535` / `#548` checked out exactly:

`a09e22e6505be7849e34fbd27faf2cc36d5bceef`

and completed successfully, including:

- project validation;
- `:app:testDebugUnitTest`;
- signed release assembly;
- immutable validation artifact preparation/upload.

The workflow log records `BUILD SUCCESSFUL` for the JVM unit-test task and release assembly.

This audit did **not** treat CI success as semantic proof. The blocker in Section 6 is a static semantic counterexample not covered by the green suite.

## 5. ProgressionEngine purity / proposal-only audit

### Result: PASS

`ProgressionEngine` is structurally pure at the current boundary:

- constructor state is limited to engine UID/version;
- `evaluate(...)` receives a value-like `ProgressionEvaluationInput`;
- output is a `ProgressionResult` containing grants, proposal ledger intents, and computation evidence;
- no writable database, DAO, repository, store, transaction object, mutable authoritative state, persistence callback, commit callback, or transaction callback is retained or accepted;
- no random UUID generator is used;
- no current time/clock access is used;
- no direct mutation of persisted ledger state exists;
- no universe-specific hard-coded progression behavior appears in the engine; World Pack identity/policy data arrives as input/evidence.

`PlayerDomainEngine` integrates the result into a draft proposal. It does not commit it.

Therefore the Phase-20 engine is genuinely proposal-only and side-effect free within the audited runtime boundary.

## 6. Determinism audit

### 6.1 Determinism matrix

| Requirement | Result | Audit result |
|---|---|---|
| identical concrete input → identical result | PASS | Existing P20 test and code derivation support this. |
| identical concrete input → identical `progressionUid` | PASS | UID derives from input fingerprint. |
| identical concrete input → identical `grantUid` | PASS | Derived from deterministic progression/computation data. |
| identical concrete input → identical `ledgerIntentUid` | PASS | Derived from grant/computation/change fingerprints. |
| identical concrete input → identical fingerprints | PASS | SHA-256 over explicit canonical strings. |
| no random UUID | PASS | No random/UUID generation in progression engine. |
| no clock/current time | PASS | No clock/time source in progression engine. |
| dependency-map order canonical | PASS | `TreeMap` canonicalizes dependency versions. |
| deterministic fixed numeric representation | PASS | Versioned fixed point, BigDecimal/BigInteger boundary. |
| deterministic serialization round trip | PASS | Typed proposal codec round-trip test exists and passed exact-SHA CI. |
| policy/version participates in identity | PASS | Numeric, progression, engine, semantics and dependency versions are fingerprint inputs. |
| deterministic ordering of factor collections | **FAIL** | Comparator is incomplete for valid factor values; see P20-C4-001. |
| semantically equivalent factor multiset → identical UID/fingerprint chain | **FAIL** | Counterexample below. |

### 6.2 Acceptance-blocking counterexample

`ProgressionCalculationFactor` identity/fingerprint includes:

- `factorKindUid`
- `evidenceUid`
- `sourceValue.scaledUnits`
- `appliedFactor.scaledUnits`
- numeric policy UID/version

However, the factor list is canonicalized only with:

`compareBy({ it.factorKindUid }, { it.evidenceUid })`

This comparator is used by `ProgressionStimulus`, `ProgressionEvaluationInput`, and again while constructing the evaluation factor list.

The type permits two factors with the same `factorKindUid` and the same `evidenceUid` but different source/applied values. No uniqueness invariant rejects that pair.

A valid conceptual pair is therefore:

- `F1 = (QUALITY, E, source=1.0, applied=1.5)`
- `F2 = (QUALITY, E, source=2.0, applied=2.0)`

For input A:

`[F1, F2]`

and input B:

`[F2, F1]`

both entries compare equal under the canonical comparator because both sorting keys are identical.

The arithmetic outcome is the same because factor application is multiplicative. But `inputFingerprint` and `computationFingerprint` serialize the ordered sequence of each factor's **full fingerprint**. Because `F1.fingerprint() != F2.fingerprint()`, the sequence differs when their relative order differs.

Consequences propagate to:

- `inputFingerprint`;
- `progressionUid`;
- `computationFingerprint` / computation UID;
- `grantUid`;
- causal `changeUid`;
- `ledgerIntentUid`;
- result fingerprint.

Thus the candidate does not define a canonical identity for all currently legal factor collections.

This is not merely a test-coverage concern. It is an implementation-level deterministic identity defect under a legal input shape.

### 6.3 Hidden nondeterminism checklist

- **random UUIDs:** none found — PASS.
- **clock/current time:** none found — PASS.
- **unordered dependency maps:** canonicalized using `TreeMap` — PASS.
- **unordered sets in semantic fingerprints:** no progression fingerprint relies directly on `HashSet` iteration; the duplicate-stimulus set is membership-only — PASS.
- **locale-sensitive numeric serialization:** progression numeric identity uses integral scaled values, not locale-formatted decimal strings — PASS for progression numeric semantics.
- **platform-dependent floating arithmetic:** fixed-point conversion crosses the `Double` boundary once through `BigDecimal.valueOf`, with explicit scale/rounding; factor arithmetic uses `BigInteger` — PASS for evaluated arithmetic.
- **unstable collection ordering:** **FAIL for calculation-factor tie case described above**.

## 7. Numeric invariant audit

### Result: PASS

The candidate defines an explicit numeric policy:

- policy UID: `RPGOS-PROGRESSION-NUMERIC:FIXED_1E6_HALF_UP`
- policy version: `1`
- scale: `1_000_000`
- rounding: `HALF_UP`

### Numeric boundary matrix

| Boundary / invariant | Result | Evidence / reasoning |
|---|---|---|
| finite non-negative Double conversion | PASS | `BigDecimal.valueOf(value)` → fixed scale. |
| `NaN` | PASS | rejected before conversion. |
| `+Infinity` | PASS | rejected as non-finite. |
| `-Infinity` | PASS | rejected as non-finite by same guard. |
| negative numeric factor input | PASS | fail-closed. |
| positive value rounding to fixed-point zero | PASS | explicit underflow rejection. |
| scaled-value Long overflow | PASS | BigInteger bound checked against `Long.MAX_VALUE`. |
| intermediate multiplication overflow | PASS | BigInteger prevents machine-Long intermediate overflow. |
| final grant overflow | PASS | explicit bound check. |
| negative base progression grant | PASS | rejected. |
| missing causal effort | PASS | rejected. |
| zero base effort | PASS | safe zero computation; no durable grant. |
| zero factor result | PASS | computation evidence exists, but no grant/ledger durable delta. |
| `ExactLongDelta(0)` | PASS | not emitted; existing delta type rejects zero. |
| silent fractional truncation | PASS | explicit HALF_UP policy; no implicit Double→Long truncation. |
| policy/version in deterministic evidence | PASS | included in fingerprints/computation record/ledger. |

The exact-SHA tests cover NaN, positive infinity, negative input, underflow, scaled overflow, final grant overflow, HALF_UP behavior, and zero-result handling. Code inspection extends the non-finite conclusion to negative infinity as well.

## 8. Cause and provenance audit

### Result: PASS

The non-zero permanent progression proposal path is causally linked as:

`source command`

→ component-emitted `ProgressionStimulus`

→ `ProgressionEvaluationInput`

→ `ProgressionResult`

→ `ProgressionGrant`

→ typed `PlayerDomainChange`

→ typed `ProgressionLedgerIntentPayload`

Key causal/provenance properties:

- a non-null causal `effortUnits` field is required;
- negative effort is rejected;
- Talent/Potential evidence is used only as a calculation modifier;
- Talent/Potential with zero effort yields no grant and no progression ledger intent;
- source command UID, source channel/type, stimulus UID, progression policy, current-value evidence, computation fingerprint, grant UID, and causal change UID are retained in the proposal/evidence chain;
- ledger causal-change UIDs link to the generated typed durable change;
- generated `sourceRuleUid` is the progression policy UID;
- no AI-selected arbitrary numeric durable gain interface exists in `ProgressionEngine`.

No evidence was found that Talent or Potential alone can manufacture permanent progression.

## 9. Reference / campaign invariant audit

### 9.1 Matrix

| Condition | Result | Audit result |
|---|---|---|
| wrong campaign command/reference | PASS | fail-closed reference validation. |
| unknown campaign-scoped reference | PASS | resolution rejects before final proposal. |
| unknown character/subject reference | PASS | subject references participate in draft/reference closure. |
| wrong modifier character | PASS | `PROGRESSION_MODIFIER_CHARACTER_MISMATCH`. |
| wrong modifier campaign | PASS | `PROGRESSION_MODIFIER_CAMPAIGN_MISMATCH`. |
| unknown stat | PASS | augmented typed stat ref is validated. |
| unknown skill | PASS | dedicated test confirms augmented closure rejection. |
| unknown technique | PASS | technique grant uses existing typed change and reference model. |
| unknown progression domain | PASS | augmented reference closure rejects missing domain. |
| custom World Pack progression domain | PASS | preserved unchanged when known/bound. |
| progression-domain World Pack mismatch | PASS | explicit fail-closed check. |
| expected World Pack mismatch | PASS | explicit fail-closed check. |
| cross-campaign reference | PASS | `CampaignScopedDomainRef` validation remains active. |
| progression-generated references included in augmented closure | PASS | second reference validation occurs after augmentation. |
| invalid target ownership | CURRENT-BOUNDARY SATISFIED / NOT GLOBALIZED | Phase 20 does not introduce a bypass around the existing campaign/reference model; a global ownership/invariant validator is not a Phase-20 deliverable. |

The critical integration property is present: the original component draft is reference-validated, progression augments the draft, and the **augmented** draft is then reference-validated again before the final WorldRule effect check and proposal construction.

No new cross-campaign or unknown-target bypass was found.

## 10. WorldRuleProvider / Phase-19 regression gate

### Result: PASS

The actual candidate order in `PlayerDomainEngine.resolve(...)` is:

1. canonical command/reference validation;
2. World Pack authority validation;
3. `COMMAND_PRECHECK`;
4. base resolution component;
5. base draft reference validation;
6. progression calculation / draft augmentation;
7. augmented draft reference closure;
8. build `WorldRuleEffectSnapshot` from the augmented draft;
9. **one** `DRAFT_EFFECT_CHECK`;
10. assemble `PlayerChangeSet` proposal;
11. `PlayerChangeSetValidator`;
12. return resolved proposal.

This matches the required semantic sequence:

`COMMAND_PRECHECK`

→ base resolution

→ progression calculation

→ augmented draft

→ augmented reference closure

→ ONE final `DRAFT_EFFECT_CHECK`

→ `PlayerChangeSet`

### Progression visibility to WorldRuleProvider

PASS. The effect snapshot is built **after** progression augmentation. Phase-20 typed changes and progression ledger payload are included in the WorldRule effect snapshot/canonical effect encoding.

The P20 integration test uses a rejecting provider and confirms:

- one precheck;
- one final effect check;
- progression is visible at that final check;
- provider rejection prevents a final proposal.

### One pinned World Pack authority

PASS.

The progression input receives the binding from the already-pinned resolution context. `validateWorldRuleAuthority(...)` checks that context binding against the authoritative campaign binding. Both WorldRule stages obtain a provider for the same binding; progression does not rebind or select a fallback authority.

No evidence was found of:

- double `DRAFT_EFFECT_CHECK` semantics;
- progression running after the final effect check;
- provider rebinding;
- fallback-provider switching;
- mixed World Pack authority.

No new concrete Phase-19 regression evidence was found. Phase 19 therefore remains accepted under its existing coordinator-owned acceptance record.

## 11. Progression ledger semantics

### Result: PASS

`ProgressionLedgerIntent` is a typed proposal/evidence object only.

It is not implemented as:

- a second current-state authority;
- a persisted progression balance;
- an independently writable ledger store;
- a replacement for the future unified ledger architecture;
- a source used to recompute current player state.

The candidate adds a typed progression ledger kind/payload and codec support inside `PlayerChangeSet`. No persistence table/store/migration is added.

### Typed payload validation

PASS.

The payload carries causal, target, current-value, factor, numeric-policy, engine, progression-policy, World Pack, input/computation, and grant evidence.

Codec/validation behavior is fail-closed for malformed typed progression payloads through typed construction and PlayerChangeSet validation. Causal change linkage is validated against the matching typed change and durable delta.

No malformed payload path was identified that silently becomes authoritative state.

## 12. Legacy / compatibility audit

### Result: PASS

No evidence was found that Phase 20 reinterprets or migrates:

- legacy skill XP;
- legacy technique XP;
- mastery;
- `legacy_progression_evidence`;
- historical Talent/Potential mappings;
- custom World Pack progression domains.

Compatibility observations:

- no Phase-20 DB migration/table was introduced;
- old campaigns are not required to contain Phase-20 progression ledger evidence;
- typed progression ledger payload is additive within PlayerChangeSet codec semantics;
- legacy evidence has a regression test confirming evaluation does not rewrite it;
- custom progression-domain identity survives unchanged in a bound World Pack test;
- Phase-20 grant mapping uses existing typed stat/skill/technique change kinds rather than inventing replacement XP semantics.

No typed/legacy collision reinterpretation was found in the candidate code path.

## 13. Phase-17 / Phase-18 / Phase-19 regression gates

### Result: PASS, subject to the Phase-20-specific determinism blocker

The exact-SHA JVM test suite completed successfully.

Independent source inspection confirms representative accepted contracts remain present:

### Phase 17

- `PlayerChangeSet` remains a deterministic transient proposal rather than committed state.
- codec round trip and fingerprint semantics remain exercised.

### Phase 18

- unknown reference rejection still occurs before successful proposal orchestration;
- unbound generic orchestration remains supported;
- Phase-20 integration augments the draft rather than replacing the resolution architecture.

### Phase 19

- command precheck remains before base resolution;
- final effect check remains after final draft augmentation;
- same pinned World Pack binding is used;
- no second effect-check path was introduced;
- provider rejection remains mutation-free and proposal-blocking.

The discovered blocker is a **new Phase-20 deterministic identity defect**, not evidence that Phase 17, 18, or 19 should be rolled back or declared unaccepted.

## 14. Failure atomicity at the current Phase-20 boundary

### Result: PASS

Phase 20 does not yet own global `TurnTransaction`, and this audit does not demand Phase-27 transaction semantics.

Within the present resolution/proposal boundary, no authoritative mutation path is exposed by `ProgressionEngine` or the inspected `PlayerDomainEngine.resolve(...)` sequence.

The following failures occur before any authoritative commit capability exists in this path:

- command/precheck rejection;
- base/augmented reference failure;
- progression structural calculation failure;
- malformed/non-finite/overflow numeric input;
- unsupported progression target;
- WorldRule rejection;
- component/resolution exception.

They may return rejection or raise a structural exception depending on failure class, but in the audited path they do not possess an authoritative writer to partially persist progression.

### MUST EXIST NOW vs deferred

**Must exist now and present:**

- pure calculation;
- fail-closed structural checks;
- proposal-only changes/ledger evidence;
- augmented reference validation;
- final WorldRule check after progression.

**Deferred by architecture and not demanded from Phase 20:**

- global crash-safe transaction;
- process-kill rollback guarantees;
- global event/ledger/state atomic commit;
- committed replay/event-store redesign;
- global retry/idempotency substrate.

## 15. Phase boundary audit

### Result: PASS

No premature competing implementation was found for later phases.

### Phase 21 — not prematurely implemented

The candidate does not implement a complete:

- diminishing-returns engine;
- passive progression engine;
- novelty/adaptation engine;
- time-skip progression orchestrator.

The factor/evidence extension seams remain compatible with future work and do not constitute those systems.

### Phase 22 — not prematurely implemented

No global Player Invariant Validator or global No-Retrogression engine is introduced.

### Phase 23+ — not prematurely implemented

No candidate implementation introduces:

- committed unified progression ledger;
- global provenance persistence;
- `TurnTransaction`;
- Event Store redesign;
- global retry/idempotency;
- snapshot/replay persistence architecture.

The Phase-20 ledger intent remains proposal evidence only.

## 16. Concrete findings

### P20-C4-001 — HIGH / ACCEPTANCE BLOCKER — incomplete canonical factor ordering breaks semantic deterministic identity

**Status:** OPEN in candidate `a09e22e6505be7849e34fbd27faf2cc36d5bceef`

**Affected area:** `ProgressionEngine.kt`

**Condition:** two legal `ProgressionCalculationFactor` values share `(factorKindUid, evidenceUid)` but differ in `sourceValue` and/or `appliedFactor`.

**Observed implementation property:** sorting compares only `(factorKindUid, evidenceUid)`, while factor fingerprints include additional value fields.

**Impact:** a permutation of the same semantic factor multiset can preserve the arithmetic grant but alter factor fingerprint sequence and therefore alter progression/computation/grant/change/ledger/result identities.

**Contract violated:** deterministic ordering where collections participate; semantically identical progression input must yield identical UID/fingerprint outputs; replay-compatible deterministic proposal identity.

**Why CI did not catch it:** `P20_01` checks repeated construction of one input shape but does not test factor permutation under comparator-key collision. No test observed in the Phase-20 suite rejects duplicate ordering keys or proves full-factor canonical ordering.

**Acceptance effect:** blocking. The exact candidate cannot receive CHAT-4 PASS while this legal-input nondeterminism remains.

**Required evidence before re-audit:** the candidate must establish an unambiguous canonical contract for this collision case and include a regression proving either canonical permutation equivalence or fail-closed rejection of the ambiguous factor set. This report does not prescribe or implement the fix.

### P20-C4-002 — LOW / TEST COVERAGE — deterministic collection tests are narrower than the contract

**Status:** OPEN as test-evidence gap

The suite meaningfully tests deterministic repeated evaluation, numeric boundaries, WorldRule visibility, reference closure, typed ledger mapping, and legacy safety. However, it does not contain a matrix for:

- factor-list permutation;
- comparator-key collision;
- duplicate factor identity/value collision.

This finding is subordinate to P20-C4-001: the implementation defect is the blocker; the missing adversarial test explains why green CI did not expose it.

## 17. Blockers

One acceptance blocker exists:

- **P20-C4-001 — incomplete canonical progression-factor ordering / semantic identity nondeterminism.**

No other acceptance-blocking defect was established by this audit.

## 18. Deferred / non-blocking findings

The following remain outside the Phase-20 acceptance boundary unless new evidence changes their scope:

- Phase-19 deferred global transaction/crash/replay concerns remain deferred per the accepted Phase-19 architecture.
- Global player ownership/no-retrogression validation remains a later invariant-layer responsibility; Phase 20 must not invent a competing global validator.
- Committed ledger persistence remains Phase 23+ work.
- Global retry/idempotency/snapshot/event-store redesign remains later work.
- Application-layer cleanup documented in `POST_ENGINE_APPLICATION_CLEANUP_ROADMAP.md` remains separate from Phase-20 progression calculation semantics.

## 19. Audit conclusion

The Phase-20 candidate is architecturally disciplined in most critical respects:

- progression calculation is pure and proposal-only;
- numeric arithmetic is explicit, versioned, deterministic fixed-point with fail-closed boundaries;
- causality and typed provenance are preserved;
- Talent/Potential do not spontaneously cause gain;
- augmented references are validated;
- progression reaches exactly one final WorldRule effect check;
- the pinned World Pack authority model remains intact;
- ledger intent remains proposal evidence rather than a second authority;
- legacy progression semantics are not reinterpreted;
- Phase-17/18/19 accepted behavior shows no new concrete regression;
- later-phase transaction/ledger/invariant architecture was not prematurely implemented.

Nevertheless, deterministic identity is a core Phase-20 invariant, not an optional polish item. Because the currently legal factor model contains an ordering-key collision that can change the complete UID/fingerprint chain for a semantically equivalent factor multiset, the exact runtime candidate does not yet meet the acceptance bar.

**FAIL — FIX REQUIRED BEFORE PHASE 20 ACCEPTANCE**
