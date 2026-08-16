# WORK-20260816-007 — Phase 20 Determinism Fix

## Work identity

- Work ID: `WORK-20260816-007`
- Role: CHAT-1 — Phase 20 implementation owner
- Mode: WRITE — STRICTLY TARGETED FIX
- Repository: `piotreksmaga-art/rpg-os-android`
- Failed runtime candidate: `a09e22e6505be7849e34fbd27faf2cc36d5bceef`
- Starting master HEAD: `7e28a5edce82b60810c52ff58640996415eae7ae`
- Starting drift classification: documentation/test-GM only; no runtime/schema/test drift after `a09e22e...`
- Final runtime candidate SHA: `38dafe5cc48c87f16218e346d9c0f9a96b6cee50`
- Runtime fix commit: `f514ddd43f003f526225f4cad2111682fef51594`
- Regression-test commit: `38dafe5cc48c87f16218e346d9c0f9a96b6cee50`

## Reopening reason

CHAT-4 audit `WORK-20260816-005` identified acceptance blocker `P20-C4-001`: progression calculation factors were canonicalized only by `(factorKindUid, evidenceUid)`, while factor semantic identity/fingerprint also contains `sourceValue.scaledUnits`, `appliedFactor.scaledUnits`, and the versioned numeric-policy identity. Two legal factors could therefore tie under sorting while differing semantically, making permutation order leak into the fingerprint/UID chain.

CHAT-5 audit `WORK-20260816-006` otherwise passed cross-boundary/source-of-truth review and recorded only LOW `P20-CB-01`, which is intentionally not addressed by this work item.

## Freshness / drift check

Comparison `a09e22e6505be7849e34fbd27faf2cc36d5bceef -> 7e28a5edce82b60810c52ff58640996415eae7ae` showed only documentation/audit/test-GM files. No application runtime, schema, migration, or production test file had changed after the failed candidate. The targeted fix therefore proceeded on current `master` without reverting or duplicating later documentation.

Immediately before the final evidence-report write, `master` was exactly the final runtime candidate `38dafe5cc48c87f16218e346d9c0f9a96b6cee50`.

## Exact defect fixed

Previously, the following three canonicalization sites used an incomplete comparator:

- `ProgressionStimulus.calculationFactors`
- `ProgressionEvaluationInput.calculationFactors`
- factor assembly inside `ProgressionEngine.evaluate(...)`

The old comparator was effectively:

`factorKindUid -> evidenceUid`

This was not a total order over all legal semantic factor identities.

## Canonical comparator / key

A single centralized `ProgressionCalculationFactorCanonicalOrder` now owns factor canonicalization.

Ordering is:

1. `factorKindUid`
2. `evidenceUid`
3. `sourceValue.scaledUnits`
4. `appliedFactor.scaledUnits`
5. full deterministic `ProgressionCalculationFactor.fingerprint()` as the final semantic tie-breaker

The full factor fingerprint itself contains:

- factor kind UID;
- evidence UID;
- source fixed-point units;
- applied fixed-point units;
- numeric policy UID;
- numeric policy version.

This preserves the previous ordering for all previously unambiguous ordinary inputs because the original first two keys remain primary. New tie-breakers affect only the previously ambiguous equal-primary-key case. Exact duplicate factors remain interchangeable and therefore semantically order-independent.

No input insertion order, UUID, clock, object identity, runtime `hashCode`, or random tie-breaker is used.

All three canonicalization sites now call the same centralized canonicalizer.

## Files changed

Runtime candidate delta from starting `master` is exactly two files:

1. `app/src/main/java/com/rpgos/app/ProgressionEngine.kt`
   - centralized total deterministic factor ordering;
   - all three factor canonicalization sites routed through the same rule.
2. `app/src/test/java/com/rpgos/app/Phase20FactorCanonicalizationRegressionTest.kt`
   - targeted regression for `P20-C4-001`.

No other runtime, schema, migration, frontend, World Pack, ledger persistence, transaction, or test-GM file was modified by the runtime candidate.

## Regression test

`Phase20FactorCanonicalizationRegressionTest.P20_C4_001_factorPermutationWithSamePrimaryKeysHasStableIdentityChain`

Constructs legal factors with identical primary sort keys but distinct semantic numeric values:

- `F1 = (QUALITY, E, source=1.0, applied=1.5)`
- `F2 = (QUALITY, E, source=2.0, applied=2.0)`
- plus an additional independent factor to exercise broader permutation.

It evaluates permutations including `[F1, F2, F3]`, `[F2, F1, F3]`, and `[F3, F2, F1]` and asserts equality of:

- final arithmetic grant;
- `inputFingerprint`;
- `progressionUid`;
- computation fingerprint;
- computation UID;
- `grantUid`;
- causal `changeUid`;
- `ledgerIntentUid`;
- result fingerprint;
- complete `ProgressionResult` equality.

The counterexample would fail under the old stable-but-incomplete `(factorKindUid, evidenceUid)` ordering because equal comparator keys retained insertion order while full factor fingerprints differed.

## Regression / architecture safety

The fix does not alter:

- proposal-only `ProgressionEngine` semantics;
- `ProgressionLedgerIntent` authority classification;
- current-state source-of-truth ownership;
- augmented reference closure;
- final Phase-19 `DRAFT_EFFECT_CHECK` ordering;
- one-resolution/one-pinned-World-Pack semantics;
- Core/World Pack ownership boundary;
- Talent/Potential causal semantics;
- Phase-20 numeric arithmetic/rounding policy;
- schema or migrations.

No Phase 21 diminishing returns/passive progression, Phase 22 invariant engine, Phase 23 unified ledger, `TurnTransaction`, Event Store, snapshot/replay system, frontend work, `Naruto_Default` cleanup, or test-GM harness change was introduced.

`P20-CB-01` from CHAT-5 remains intentionally deferred for explicit Phase-23 provenance design.

## Tests / build / CI

Separate local Gradle execution was not available in the CHAT-1 execution environment because no repository checkout/networked git workspace was exposed. Validation was therefore performed on the exact pushed runtime SHA by the repository's normal GitHub Actions workflow.

Exact runtime candidate:

`38dafe5cc48c87f16218e346d9c0f9a96b6cee50`

GitHub Actions:

- workflow: `Validate RPG OS ALPHA`
- run number: `#578`
- run ID: `31961047982`
- head SHA: `38dafe5cc48c87f16218e346d9c0f9a96b6cee50`
- status: `completed`
- conclusion: `success`

Successful required steps include:

- release workflow separation validation;
- project validation;
- full `:app:testDebugUnitTest` JVM unit-test suite;
- signed validation APK build;
- immutable validation artifact preparation and upload.

Because `:app:testDebugUnitTest` is the full app JVM suite, the run includes the new `P20-C4-001` regression together with existing Phase-20 tests and the repository's Phase-17/18/19 regression coverage, including Phase-19 canonical regression tests.

## Identity stability assessment

The comparator retains the pre-fix `(factorKindUid, evidenceUid)` keys as the first two ordering keys. Therefore factor lists that were already unambiguous under the old comparator retain the same order and deterministic identity chain. Only collections containing factors that previously compared equal while having different semantic factor identities receive newly canonicalized order.

This is the intended compatibility boundary for `P20-C4-001`.

## Schema / migration delta

- Schema delta: `NONE`
- Migration delta: `NONE`
- Persisted progression authority added: `NONE`

## Final verdict

**FIX COMPLETE — READY FOR EXACT-SHA REVALIDATION**

This is a worker verdict only. It does not declare Phase 20 ACCEPTED.

The exact runtime SHA to provide to CHAT-4 and CHAT-5 for focused revalidation is:

`38dafe5cc48c87f16218e346d9c0f9a96b6cee50`
