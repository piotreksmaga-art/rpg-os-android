# WORK-20260809-016 — Phase 5 Determinism / Mathematical Oracle

Status: READ-ONLY RUNTIME

Work ID: `WORK-20260809-016`
Owner: `CHAT-2`
Role: INDEPENDENT PHASE 5 DETERMINISM / MATHEMATICAL AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Baseline inspected: `9d997aaa7fbbb953333fb4d00521d868cc582320`
Phase 4 runtime authority baseline: `6bdde251a3ef293a0cfa85c818538da4cc1307eb`
Primary contract: `docs/audits/WORK-20260809-007_PHASE5_TEST_CONTRACT.md`
Phase 5 input compatibility gate: `docs/audits/WORK-20260809-011_PHASE5_INPUT_COMPATIBILITY.md`

This document is an independent mathematical/test oracle. It does not implement resolver runtime, modifiers, persistence, schema or migrations.

---

## 1. Canonical mathematical contract

The authoritative input for a stat is `PlayerStat.baseValue`. Effective state is derived only.

The canonical lifecycle order from WORK-007 is:

```text
BASE
-> PERMANENT
-> EQUIPMENT
-> INJURY
-> TEMPORARY
-> FINAL CAPS / DEFINITION BOUNDS
= EFFECTIVE
```

Within one lifecycle, canonical operation stages are:

```text
ADD_FLAT
-> ADD_PERCENT
-> MULTIPLY
-> OVERRIDE
-> CAPS / BOUNDS
```

Priority and then stable `modifierUid` tie-breaking must make each stage deterministic. Input list order, SQL row order and insertion history are not part of the mathematical meaning.

For all examples below, modifiers are active, in-range, source-valid and target-valid unless explicitly stated otherwise.

---

## 2. Baseline oracle

Input:

```text
base = 100
PERMANENT ADD_FLAT +10
EQUIPMENT ADD_FLAT +20
INJURY ADD_FLAT -30
TEMPORARY ADD_FLAT +5
```

Expected trace:

```text
BASE            100
PERMANENT +10   110
EQUIPMENT +20   130
INJURY -30      100
TEMPORARY +5    105
FINAL           105
```

Required assertions:

- `baseValue == 100`
- `effectiveValue == 105`
- authoritative `PlayerStat.baseValue` remains exactly `100`
- four applied contributions appear in canonical lifecycle order
- replay under any modifier-list permutation returns the same result and canonical trace

---

## 3. ADD + MULTIPLY oracle

WORK-007 defines flat additions before multipliers inside a lifecycle.

Input:

```text
base = 100
ADD_FLAT +20
MULTIPLY x1.5
```

Expected:

```text
100 -> 120 -> 180
```

Final: `180`.

The inverse input-list order must still yield `180`; a runtime yielding `170` is using insertion order instead of the canonical operation stage.

---

## 4. MULTIPLY + ADD presented in reverse input order

Input list supplied as:

```text
MULTIPLY x1.5
ADD_FLAT +20
```

Logical operations are identical to section 3.

Expected final remains `180`, not `170`.

This is a mandatory determinism discriminator.

---

## 5. Multiple MULTIPLY modifiers

Input:

```text
base = 100
MULTIPLY x1.10
MULTIPLY x1.20
```

Expected sequential multiplicative composition:

```text
100 * 1.10 * 1.20 = 132
```

Final: `132` within normal `Double` comparison tolerance only if implementation arithmetic creates representation noise; semantically it must correspond to the same deterministic IEEE-754 operation sequence after canonical sort.

Because multiplication is mathematically commutative for exact reals but not perfectly associative in floating point, canonical modifier ordering remains required even when all operations are MULTIPLY.

---

## 6. ADD_PERCENT oracle

WORK-007 recommends additive percentages sharing one stage input rather than sequential percentage compounding.

Input:

```text
stage input = 100
ADD_PERCENT +0.10
ADD_PERCENT +0.20
```

Expected:

```text
combined percent = 0.30
100 + 100*0.30 = 130
```

Not `132`.

Mixed fixture:

```text
base 100
ADD_FLAT +20
ADD_PERCENT +10%
MULTIPLY x1.5
```

Expected:

```text
100 -> 120 -> 132 -> 198
```

Final: `198` before caps.

---

## 7. OVERRIDE oracle

Input after ordinary modifiers: `140`.

Overrides:

```text
OVERRIDE A: value 80, priority 10, uid MOD-A
OVERRIDE B: value 120, priority 20, uid MOD-B
```

Expected winner: priority `20`; effective pre-cap value `120`.

For equal priority, implementation must freeze one of two legal contracts:

1. stable UID tie-break, or
2. deterministic ambiguity validation error.

It must never use insertion order, timestamp coincidence or SQLite row order implicitly.

If stable UID tie-break is chosen, comparator direction must be explicitly tested and documented.

---

## 8. Floor / minimum bound oracle

Input pre-bound: `-20`.

Hard floor/minimum: `0`.

Expected final: `0`.

Trace must record both pre-bound `-20` and final `0` plus the bound source/identity.

Definition `minValue` must participate only according to the frozen Phase-5 contract; if Phase 5 treats definition bounds as final bounds, the same diagnostic rule applies.

---

## 9. Cap / maximum bound oracle

Input pre-cap: `180`.

Hard max cap: `150`.

Expected final: `150`.

Trace must preserve `preCapValue = 180` and record which cap produced `150`.

If several caps survive stacking, the resolver must deterministically derive the effective cap according to explicit cap semantics; for hard upper caps the strictest upper limit is normally the minimum surviving cap.

---

## 10. Priority collision oracle

Two order-sensitive modifiers in the same operation stage:

```text
M1 priority=10 uid=A
M2 priority=20 uid=B
```

Expected application/winner order is defined solely by the chosen priority convention and stable UID comparator. The implementation must state whether lower or higher numeric priority executes first.

The oracle does not invent that convention beyond WORK-007; instead the implementation test must pin it. Once pinned, all permutations must yield identical result and trace.

Equal priority:

```text
M1 priority=10 uid=A
M2 priority=10 uid=B
```

Expected: deterministic UID ordering or deterministic validation error, never insertion-order dependence.

---

## 11. Determinism permutation oracle

For each arithmetic fixture, construct logically identical inputs in at least these forms:

1. canonical modifier order,
2. exact reverse order,
3. random list permutation,
4. rows inserted into SQLite in reverse order,
5. definitions/maps rebuilt with different iteration order,
6. reopened database returning rows through a different query plan where possible.

All must produce identical:

- effective numerical value,
- canonical applied modifier order,
- winner/stacking decisions,
- diagnostics,
- canonical input fingerprint,
- rule fingerprint for equal provider/version state.

Recommended property test: at least 100 random permutations per representative fixture; 1000 for one large modifier fixture.

---

## 12. Floating-point contract

Phase 4 contracts use Kotlin `Double`; therefore Phase 5 should treat IEEE-754 `Double` as the current numeric domain unless implementation intentionally introduces another explicit type.

### 12.1 NaN

NaN is invalid for:

- base/current authoritative input,
- modifier magnitude,
- rule output,
- intermediate derived value,
- cap/floor,
- regeneration/max result.

Any NaN must cause deterministic validation/resolution failure before output/cache acceptance.

### 12.2 Positive/negative Infinity

`+Infinity` and `-Infinity` are invalid in the same places. Resolver must fail loudly rather than clamp or serialize them as valid state.

### 12.3 Negative zero

IEEE-754 distinguishes `-0.0` from `0.0` at bit level although ordinary numeric equality often treats them equal.

For deterministic fingerprints/traces, Phase 5 should canonicalize any mathematically zero final/intermediate public numeric value to `+0.0` before hashing/serialization, unless the implementation explicitly proves a domain meaning for negative zero. RPG OS currently has no such domain meaning.

Required oracle:

```text
base = 0.0
ADD_FLAT = -0.0
```

Canonical public result/fingerprint representation should be `+0.0`.

### 12.4 Rounding

Core should not introduce display rounding into resolution arithmetic. Keep full `Double` result for domain resolution; UI/presentation rounding is separate.

Tests should compare exact bit/canonical serialization where the operation sequence is frozen, and use documented tolerance only for assertions whose expected decimal is not exactly representable.

### 12.5 Overflow

Every arithmetic stage must check `isFinite()` after calculation.

Example:

```text
Double.MAX_VALUE * 2.0
```

Expected: deterministic numeric overflow error; never legal `Infinity` output.

### 12.6 Extremely large finite values

Large but finite input is legal only if all intermediate and final results remain finite and definition/rule constraints allow it. No silent saturation unless a declared cap operation causes it before overflow in a mathematically well-defined stage.

### 12.7 Underflow

Very small multiplication may underflow toward zero under IEEE-754. Because this can be deterministic but semantically surprising, tests should pin behavior. No hidden conversion to another scale/precision is allowed.

If result becomes zero, normalize public/fingerprint zero as described in 12.3.

### 12.8 Repeated floating-point operations

Because floating-point addition/multiplication is not associative, canonical sort/order is part of the contract. A resolver may not reduce modifiers via unordered parallel aggregation unless it guarantees the exact canonical reduction order.

---

## 13. Explanation trace oracle

Trace must be sufficient to independently reproduce the number.

Minimum contribution entry should expose conceptually:

```text
sequenceIndex
lifecycle
operation
modifierUid
sourceType
sourceUid
priority
inputValue
magnitude / rule identity
outputValue
applied or ignored reason
provenance
```

Baseline trace:

```text
0 BASE                         input=null output=100
1 PERMANENT ADD +10            input=100 output=110
2 EQUIPMENT ADD +20            input=110 output=130
3 INJURY ADD -30               input=130 output=100
4 TEMPORARY ADD +5             input=100 output=105
5 FINAL                         input=105 output=105
```

For grouped `ADD_PERCENT`, trace must reveal stage input and combined percentage, so `100 +10% +20% = 130` is distinguishable from sequential compounding.

For MULTIPLY, each multiplier's canonical order must be visible.

For OVERRIDE, ignored losing overrides should appear in diagnostics/trace or equivalent explanation structure with winner reason.

For caps, trace must preserve pre-cap value and cap identity.

Ignored inactive/expired/future modifiers may be in a diagnostics trace rather than applied trace, but the system must make it possible to explain why they did not affect the result.

---

## 14. Lifecycle versus operation interaction

Lifecycle order is outer ordering; operation stage is inner ordering per lifecycle according to WORK-007.

Example:

```text
base 100
PERMANENT MULTIPLY x2
EQUIPMENT ADD_FLAT +10
```

Under outer lifecycle semantics:

```text
PERMANENT stage: 100 * 2 = 200
EQUIPMENT stage: 200 + 10 = 210
```

Expected `210`.

This is intentionally different from globally collecting all ADDs before all MULTIPLYs (`220`). Implementation tests must pin the exact nesting selected from WORK-007's lifecycle-first contract.

Likewise:

```text
PERMANENT ADD +10
EQUIPMENT MULTIPLY x2
```

Expected `220`.

This distinction is essential for deterministic mathematical semantics.

---

## 15. Resource mathematical oracle

`PlayerResource.currentValue` is observed authoritative current quantity. Resolver must not mutate it.

Fixture:

```text
currentValue = 40
max rule => 100
regen rule => 3.5
```

Expected derived output:

```text
currentValueObserved = 40
maximumValue = 100
regenerationRate = 3.5
```

No output-side mutation to `43.5` is permitted.

Over-cap fixture:

```text
currentValue = 150
new derived maximum = 100
```

Expected:

```text
currentValueObserved = 150
maximumValue = 100
overCap diagnostic = true/equivalent
```

Authoritative current remains `150` until a separate legal state mutation occurs.

---

## 16. Rule-binding determinism oracle

For `derivationRuleUid`, `maxRuleUid`, `regenerationRuleUid`:

- rule lookup must include provider identity/version,
- missing rule => deterministic typed error,
- incompatible provider/rule version => deterministic typed error,
- undeclared dependency => deterministic validation error,
- same immutable input + same provider version => same rule output/fingerprint,
- provider version change must change rule/input fingerprint even if numeric output happens to remain equal.

No rule may read hidden mutable global state not represented in the resolution snapshot/fingerprint.

---

## 17. Cycle oracle

Graph fixtures:

```text
A -> A
A -> B -> A
A -> B -> C -> A
```

Expected: deterministic cycle validation error before unbounded recursion.

Cycle diagnostic should contain a canonical cycle path, e.g. starting from lexicographically smallest UID or another documented canonicalization, so equivalent graph traversal orders produce identical diagnostics.

Deep acyclic chain must resolve without being mistaken for a cycle; implementation should define a defensive maximum graph size/depth only if required for safety, and such a limit must fail explicitly rather than truncate.

---

## 18. Legacy reconciliation oracle

Phase 5 must consume the canonical Phase 4 read contract after WORK-014.

Mapped legacy stat/resource:

```text
legacyUid -> canonicalTypedUid
```

Expected: one resolver node identified by canonical typed UID.

If typed persisted player value exists, it remains canonical. If it does not, the mapped legacy value projected to canonical UID may supply the authoritative compatible value according to Phase 4 semantics.

Unmapped same-key legacy + typed ambiguity must fail before resolver invocation.

Alias `mappingVersion`, canonical target identity and provenance must influence future resolution/cache fingerprint whenever reconciliation changes the canonical input graph.

No key-only auto-merge and no Naruto/Bleach literal rules are allowed.

---

## 19. Large-set oracle

Create 100 and >1000 active modifiers with deterministic generated UIDs and known arithmetic.

Required properties:

- no truncation,
- no skipped modifier due to pagination/list limits,
- deterministic canonical ordering,
- same result after reverse insertion,
- same trace cardinality for applied modifiers,
- finite intermediate values,
- duplicate UID handling remains fail-loud/idempotent according to chosen payload policy.

For a simple 1000 x `ADD_FLAT +1` fixture with base 0, expected effective value is exactly `1000`.

---

## 20. Release-gating determinism invariants

Phase 5 implementation should fail validation if any of these are false:

1. Same logical resolution snapshot always yields same effective result.
2. Same logical snapshot yields same canonical trace ordering.
3. Same logical snapshot yields same input/rule fingerprint.
4. Result is independent of modifier insertion/order and SQL row order.
5. `PlayerStat.baseValue` is never changed by resolver execution.
6. `PlayerResource.currentValue` is never changed by resolver execution.
7. Every public numeric derived output is finite.
8. Negative zero is canonicalized for serialization/fingerprinting or explicitly and consistently specified otherwise.
9. Rule provider/version is part of deterministic identity.
10. Legacy alias version/identity participates when it affects canonical input.
11. Cycles, missing rules and incompatible versions fail deterministically.
12. Explanation trace is sufficient to reproduce every effective number.
13. No world-specific literal participates in Core ordering/arithmetic.
14. No cache value is treated as authoritative input.

---

# Final status

`PHASE 5 DETERMINISM ORACLE READY`
