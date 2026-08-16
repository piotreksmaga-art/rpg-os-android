# WORK-20260816-008 — Phase 20 P20-C4-001 Exact-SHA Revalidation

## 1. Audit identity

- **Work ID:** `WORK-20260816-008`
- **Role:** CHAT-4 — independent test / invariant / compatibility revalidator
- **Mode:** READ-ONLY, evidence-only report permitted
- **Repository:** `piotreksmaga-art/rpg-os-android`
- **Exact runtime SHA audited:** `38dafe5cc48c87f16218e346d9c0f9a96b6cee50`
- **Previously failed candidate:** `a09e22e6505be7849e34fbd27faf2cc36d5bceef`
- **Previous CHAT-4 audit:** `WORK-20260816-005`
- **Fix work reviewed as context:** `WORK-20260816-007`
- **Current master observed immediately before this evidence-only report commit:** `b2b2b8582a3258a988e607ec92adffdfe4cfab18`
- **Known exact-SHA CI independently verified:** `Validate RPG OS ALPHA`, run `#578`, ID `31961047982`, `head_sha=38dafe5cc48c87f16218e346d9c0f9a96b6cee50`, `completed / success`

This revalidation applies only to runtime semantics represented by `38dafe5cc48c87f16218e346d9c0f9a96b6cee50`. Later documentation commits are not part of the audited runtime.

## 2. Final verdict

**PASS — P20-C4-001 FIX VERIFIED — READY FOR COORDINATOR ACCEPTANCE REVIEW**

This PASS applies **ONLY** to exact runtime SHA:

`38dafe5cc48c87f16218e346d9c0f9a96b6cee50`

This is not a declaration that Phase 20 is accepted. Global acceptance remains coordinator-owned.

## 3. Required evidence reviewed

Reviewed:

- `docs/audits/WORK-20260816-005_PHASE20_TEST_INVARIANT_COMPATIBILITY_AUDIT.md`
- `docs/audits/WORK-20260816-007_PHASE20_DETERMINISM_FIX.md`
- exact old runtime `a09e22e6505be7849e34fbd27faf2cc36d5bceef`
- exact new runtime `38dafe5cc48c87f16218e346d9c0f9a96b6cee50`
- `app/src/main/java/com/rpgos/app/ProgressionEngine.kt`
- `app/src/test/java/com/rpgos/app/Phase20FactorCanonicalizationRegressionTest.kt`
- existing Phase-20 and Phase-17/18/19 regression evidence carried by the full JVM suite
- GitHub Actions run `31961047982` / `#578`

The CHAT-1 fix report was treated as context only. The implementation and exact-SHA CI were independently inspected.

## 4. Drift and targeted-delta analysis

### 4.1 Previous failed candidate -> fixed candidate

The semantic production change from the failed Phase-20 candidate is limited to factor canonicalization in:

- `app/src/main/java/com/rpgos/app/ProgressionEngine.kt`

The corresponding targeted test addition is:

- `app/src/test/java/com/rpgos/app/Phase20FactorCanonicalizationRegressionTest.kt`

A direct comparison from the immediately preceding audit/documentation state `7e28a5edce82b60810c52ff58640996415eae7ae` to `38dafe5c...` contains exactly two commits and exactly these two files: `ProgressionEngine.kt` (+20/-5) and the new regression test (+87).

The broader comparison `a09e22e... -> 38dafe5c...` also carries intervening documentation/audit/test-GM commits, but no additional production runtime changes beyond `ProgressionEngine.kt` and no unrelated production tests.

### 4.2 Candidate -> current master

Immediately before writing this report, current `master` was:

`b2b2b8582a3258a988e607ec92adffdfe4cfab18`

The candidate `38dafe5c...` is its ancestor. The two commits after the candidate are documentation-only and affect only:

- `docs/audits/WORK-20260816-007_PHASE20_DETERMINISM_FIX.md`
- `docs/test-gm/TEST_GM_FINDING_2026-08-16_WITCHER_NEW_CAMPAIGN.md`

Therefore no later runtime drift was substituted into this audit.

## 5. Original blocker reproduction

`WORK-20260816-005` identified `P20-C4-001` because the old implementation canonicalized calculation factors only by:

`factorKindUid -> evidenceUid`

while a factor's semantic fingerprint included:

- `factorKindUid`
- `evidenceUid`
- `sourceValue.scaledUnits`
- `appliedFactor.scaledUnits`
- numeric policy UID
- numeric policy version

The legal counterexample remains:

- `F1 = (QUALITY, E, source=1.0, applied=1.5)`
- `F2 = (QUALITY, E, source=2.0, applied=2.0)`

Under the old comparator, `F1` and `F2` compared equal despite different semantic fingerprints. Kotlin's stable list sort then retained their insertion order. Thus `[F1,F2]` and `[F2,F1]` produced the same multiplicative arithmetic result but different ordered fingerprint serialization and therefore different downstream identity chains.

The newly added regression test would fail against the old implementation at least at `inputFingerprint` equality (and consequently the downstream UID/fingerprint assertions). This conclusion follows directly from the old comparator plus the fact that `inputFingerprint` serializes the ordered list of full factor fingerprints; it is not dependent on the CHAT-1 report.

## 6. Fixed comparator / total-order analysis

At exact SHA `38dafe5c...`, a centralized `ProgressionCalculationFactorCanonicalOrder` canonicalizes with the following lexicographic key:

1. `factorKindUid`
2. `evidenceUid`
3. `sourceValue.scaledUnits`
4. `appliedFactor.scaledUnits`
5. `fingerprint()`

The factor fingerprint is SHA-256 over a length-prefixed canonical string containing factor kind, evidence UID, source scaled units, applied scaled units, numeric policy UID, and numeric policy version.

### 6.1 Comparator equality proof

For two valid factors A and B:

- if `factorKindUid` differs, comparator is non-zero;
- otherwise if `evidenceUid` differs, comparator is non-zero;
- otherwise if `sourceValue.scaledUnits` differs, comparator is non-zero;
- otherwise if `appliedFactor.scaledUnits` differs, comparator is non-zero;
- otherwise the four instance-level semantic fields are identical.

The remaining fingerprint inputs — numeric policy UID and version — are runtime constants shared by both factors. Therefore when the first four comparator fields are equal, `fingerprint(A) == fingerprint(B)` necessarily follows in this runtime.

I found no remaining legal case where:

`compare(A, B) == 0`

while:

`semanticFingerprint(A) != semanticFingerprint(B)`.

Exact semantic duplicates may compare equal. Their interchange cannot affect canonical serialization, arithmetic, ledger evidence, or identity because their serialized fingerprints and numeric values are identical.

### 6.2 Centralization coverage

The same canonicalizer is used at all three relevant sites:

- `ProgressionStimulus.calculationFactors`
- `ProgressionEvaluationInput.calculationFactors`
- combined factor assembly in `ProgressionEngine.evaluate(...)`, including Talent/Potential factor evidence

This removes the prior possibility that one layer used a different ordering rule from another.

## 7. Permutation matrix

The repository regression test explicitly evaluates:

| Input order | Canonical result | Identity-chain result |
|---|---|---|
| `[F1,F2,F3]` | same canonical sequence | PASS |
| `[F2,F1,F3]` | same canonical sequence | PASS |
| `[F3,F2,F1]` | same canonical sequence | PASS |

where `F3 = (OUTCOME, Z, source=1.0, applied=1.0)`.

Independent comparator reasoning covers the full six permutations of the three-factor multiset. `OUTCOME` sorts before `QUALITY`; between the two `QUALITY/E` factors, source units `1_000_000 < 2_000_000`, so every permutation canonicalizes to:

`[F3, F1, F2]`.

With base grant `10`, arithmetic is also permutation-independent:

`10 × 1.0 × 1.5 × 2.0 = 30`.

Thus all six permutations produce the same final grant and the same ordered factor-fingerprint stream.

## 8. Identity-chain verification

The targeted regression test asserts equality across the tested permutations for:

- arithmetic grant units;
- `inputFingerprint`;
- `progressionUid`;
- computation fingerprint;
- computation UID;
- `grantUid`;
- causal `changeUid`;
- `ledgerIntentUid`;
- `resultFingerprint`;
- complete `ProgressionResult` equality.

Code inspection confirms the dependency chain is consistent:

canonical factors
→ `inputFingerprint`
→ `progressionUid`
→ computation fingerprint / computation UID
→ grant fingerprint / `grantUid`
→ causal `changeUid`
→ progression ledger intent UID
→ result fingerprint.

No insertion-order-dependent factor sequence remains in this chain.

## 9. Stable-ID compatibility for previously unambiguous inputs

### Result: PASS

The old first two keys remain the first two keys of the new comparator. Therefore any factor collection that had no `(factorKindUid,evidenceUid)` tie keeps exactly the same relative order as before.

The fix does not alter:

- factor fingerprint construction;
- numeric policy UID/version;
- progression engine UID/version;
- progression UID derivation;
- grant UID derivation;
- ledger UID derivation;
- result fingerprint construction.

Consequently previously unambiguous Phase-20 inputs retain their canonical representation and stable identity chain. Only the formerly ambiguous equal-primary-key class obtains a newly deterministic order, which is the intended compatibility boundary.

The full exact-SHA JVM suite, including the pre-existing Phase-20 deterministic fixtures, also passes.

## 10. Nondeterminism review

### Result: PASS

The fix does not introduce or consult:

- insertion order as a tie-breaker for semantically distinct factors;
- random UUIDs;
- random numbers;
- wall clock/current time;
- object identity;
- runtime `hashCode()`;
- locale-sensitive numeric formatting;
- unordered map/set iteration.

Comparator primitives are deterministic `String` and `Long` values plus the deterministic factor fingerprint. Factor numeric values are canonical fixed-point longs before comparison.

The fingerprint helper uses explicit UTF-8 SHA-256 over length-prefixed canonical fields. The comparator's final fingerprint key is deterministic and, given equal prior semantic fields, equal for exact semantic duplicates.

## 11. Numeric-policy revalidation

### Result: PASS

The fix continues to use the existing versioned numeric policy:

- UID `RPGOS-PROGRESSION-NUMERIC:FIXED_1E6_HALF_UP`
- version `1`
- scale `1_000_000`
- rounding `HALF_UP`

The comparator compares already validated `scaledUnits: Long`. Factor fingerprinting uses those same scaled units plus numeric-policy UID/version.

Existing fail-closed behavior remains unchanged:

| Numeric condition | Result |
|---|---|
| NaN | rejected as non-finite |
| +Infinity | rejected as non-finite |
| -Infinity | rejected as non-finite |
| negative factor value | rejected |
| positive fixed-point underflow | rejected |
| scaled conversion overflow | rejected |
| final grant overflow | rejected |
| negative base grant | rejected |
| zero arithmetic result | handled without zero durable delta |

No comparator path bypasses `ProgressionScaledValue` construction or numeric validation.

## 12. Focused regression revalidation of previously passing gates

### Result: PASS

`WORK-20260816-005` had already passed the following areas on `a09e22e...`. The fix changes only factor canonicalization in `ProgressionEngine.kt` plus one test; the affected paths were rechecked for regression and the full JVM suite passes on the exact new SHA.

- **ProgressionEngine pure / proposal-only:** unchanged. No database/store/repository/transaction/callback capability was added.
- **No authoritative mutation:** unchanged. `evaluate` returns proposal/evidence objects only.
- **Cause/provenance linkage:** unchanged; source stimulus/command → grant → typed change → progression ledger intent remains intact.
- **Augmented reference closure:** `PlayerDomainEngine` is unchanged by the fix.
- **Progression before final DRAFT_EFFECT_CHECK:** unchanged by the fix.
- **Exactly one final DRAFT_EFFECT_CHECK:** unchanged by the fix.
- **Pinned World Pack authority:** unchanged; no WorldRuleProvider or binding code changed.
- **Proposal-only progression ledger:** unchanged; no ledger model/codec/persistence change.
- **Legacy compatibility:** unchanged; no legacy XP/mastery/Talent/Potential mapping path changed.
- **Phase 17–19 regressions:** full JVM suite passed on exact SHA; no Phase-17/18/19 runtime file was modified by this fix.
- **Phase boundary:** no Phase 21 diminishing returns/passive progression/time-skip engine; no Phase 22 global invariant/no-retrogression engine; no Phase 23+ committed unified ledger, TurnTransaction, Event Store redesign, global retry/idempotency, or snapshot/replay implementation was introduced.

No regression attributable to the fix was found.

## 13. Exact-SHA CI verification

GitHub Actions run independently verified:

- workflow: `Validate RPG OS ALPHA`
- run number: `#578`
- run ID: `31961047982`
- exact head SHA: `38dafe5cc48c87f16218e346d9c0f9a96b6cee50`
- status: `completed`
- conclusion: `success`

The job log confirms checkout of exactly `38dafe5c...`, then successful execution of:

- release workflow separation validation;
- project validation;
- `gradle --no-daemon :app:testDebugUnitTest --stacktrace`;
- full JVM unit-test task with `BUILD SUCCESSFUL`;
- signed release assembly with `BUILD SUCCESSFUL`;
- immutable validation artifact preparation/upload whose provenance records the same exact SHA and run ID.

This CI evidence is supporting evidence, not the basis of the semantic verdict. The semantic verdict rests on the corrected total-order analysis plus the regression-test counterexample and identity-chain inspection.

## 14. Findings

### Blocking findings

**NONE.**

`P20-C4-001` is verified fixed at exact SHA `38dafe5cc48c87f16218e346d9c0f9a96b6cee50`.

### New regressions

**NONE FOUND** within the focused revalidation scope.

### Deferred findings

No new deferred finding is introduced by this revalidation. Existing later-phase/deferred items from prior audits remain outside this focused work unless separately reopened by the coordinator.

## 15. Final verdict

**PASS — P20-C4-001 FIX VERIFIED — READY FOR COORDINATOR ACCEPTANCE REVIEW**

PASS applies **ONLY** to:

`38dafe5cc48c87f16218e346d9c0f9a96b6cee50`

This report does **not** declare `PHASE 20 ACCEPTED`.