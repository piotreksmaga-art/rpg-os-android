# WORK-20260809-019 — Phase 5 Adversarial / Edge-Case Test Matrix

Status: READ-ONLY RUNTIME / PRE-IMPLEMENTATION ADVERSARIAL MATRIX

Work ID: `WORK-20260809-019`
Owner: `CHAT-5`
Role: PHASE 5 ADVERSARIAL / EDGE-CASE AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Audited master at report creation: `7e151ff3b44793dbd470e022b5ded9ac9a20cc87`
Phase 4 canonical runtime dependency: `6bdde251a3ef293a0cfa85c818538da4cc1307eb`
Phase 5 implementation WORK item: `WORK-20260809-015`
WORK-015 result commit at report creation: NOT FOUND / implementation not yet visible in repository.

This report does not implement Phase 5. It defines adversarial cases that must be used to review the future `DerivedValueResolver + Modifier Model` implementation.

Canonical inputs used for this matrix:
- `docs/RPG_OS_MASTER_ARCHITECTURE.md`
- `docs/audits/WORK-20260809-007_PHASE5_TEST_CONTRACT.md`
- `docs/audits/WORK-20260809-011_PHASE5_INPUT_COMPATIBILITY.md`
- Phase 4 legacy reconciliation contract from WORK-014.

## 1. Adversarial principles

The resolver must preserve these invariants under malformed, extreme, ambiguous and hostile input:

1. `PlayerStat.baseValue` is authoritative persistent progression and is never rewritten by resolution.
2. `PlayerResource.currentValue` is authoritative current quantity and is never regenerated, clamped or rewritten by the resolver.
3. Effective stat values, resource maximums and regeneration are DERIVED/rebuildable.
4. Resolution is deterministic for the same logical input independent of insertion/row/list order.
5. Campaign/player/World Pack identity boundaries are strict.
6. Invalid graph/rule/modifier input fails deterministically instead of producing partial or guessed results.
7. Phase 4 legacy aliases are authoritative identity reconciliation; Phase 5 never guesses mappings.
8. NaN/Infinity and structurally invalid numeric input must not enter authoritative or derived results.
9. No special-case Naruto/Bleach literals may be required for correctness.
10. A failed resolution must not perform any authoritative side effect.

## 2. Adversarial modifier matrix

| ID | Case | Required outcome |
|---|---|---|
| M-01 | ADD magnitude `0` | Legal no-op if otherwise valid; deterministic trace/result. |
| M-02 | MULTIPLY magnitude `0` | Deterministic zeroing at defined multiply stage; base remains unchanged. |
| M-03 | Negative multiplier | Explicit allow/reject policy required; never implicit. |
| M-04 | Extremely large finite multiplier | Non-finite intermediate/final must fail deterministically. |
| M-05 | Duplicate `modifierUid`, identical payload | Never double-apply; deterministic reject or explicitly idempotent collapse. |
| M-06 | Duplicate `modifierUid`, conflicting payload | Hard validation error; never choose by row/list order. |
| M-07 | Same source emits several unique modifiers to same target | Only if contract permits; each remains independently traceable. |
| M-08 | Same priority, different UID | Stable UID tie-break. |
| M-09 | Same priority and exact same timestamps | Stable identity must still determine order. |
| M-10 | `validUntil == resolutionTime` | Inclusive/exclusive boundary must be explicit and tested. |
| M-11 | `validFrom == resolutionTime` | Exact boundary must be explicit and tested. |
| M-12 | `validUntil < validFrom` | Reject before resolution. |
| M-13 | Missing source | Fail or mark unresolved according to explicit source policy; never silently trust orphan. |
| M-14 | Deleted equipment source with remaining modifier row | Must not remain effective merely because row exists; no resolver-side deletion. |
| M-15 | Dead/inactive source | Explicit lifecycle handling; no implicit application. |
| M-16 | Target definition missing | Deterministic validation error before arithmetic. |
| M-17 | Target-kind mismatch | Deterministic type mismatch error. |
| M-18 | Modifier targets authoritative resource current value | Reject target scope. |
| M-19 | Modifier targets `PlayerStat.baseValue` | Reject target scope. |
| M-20 | Source removed between resolutions | Derived result changes; authoritative base/current does not. |

## 3. Numeric attack matrix

| ID | Case | Required outcome |
|---|---|---|
| N-01 | Modifier `NaN` | Reject at validation/preflight. |
| N-02 | `+Infinity` | Reject. |
| N-03 | `-Infinity` | Reject. |
| N-04 | Corrupted base/current `NaN` | Fail loud; no derived output. |
| N-05 | Corrupted base/current Infinity | Fail loud. |
| N-06 | `-0.0` | Canonical equality/fingerprint policy required. |
| N-07 | Addition overflow | Detect non-finite and fail. |
| N-08 | Multiplication overflow | Detect non-finite and fail. |
| N-09 | Underflow/subnormal values | Same logical input must replay identically on supported runtime. |
| N-10 | Huge finite pre-cap value | Deterministic cap, with pre-cap and final trace. |
| N-11 | Extremely negative modifier | Apply/reject by explicit bounds policy without changing base. |
| N-12 | Multiply by zero followed by ADD | Must obey canonical operation order, not insertion order. |
| N-13 | 100 / >1000 repeated multipliers | No silent truncation; finite-result guard. |
| N-14 | Floating-point associativity stress | Canonical sorting/order prevents list-order result drift. |
| N-15 | Non-finite definition bounds/rule values | Reject before resolution. |

If `Double` is used, tests must avoid locale-dependent fingerprints and must define exact/tolerance comparison policy where binary floating arithmetic is non-exact.

## 4. Graph / rule attack matrix

| ID | Graph/rule case | Required outcome |
|---|---|---|
| G-01 | A -> A | Deterministic cycle error. |
| G-02 | A -> B -> A | Deterministic cycle error. |
| G-03 | A -> B -> C -> A | Deterministic cycle error. |
| G-04 | Deep acyclic chain | No recursive stack overflow; explicit bound/error if limited. |
| G-05 | Missing dependency node | Deterministic missing-node error. |
| G-06 | Duplicate dependency edge | No double application; reject/canonicalize explicitly. |
| G-07 | Same rule invoked recursively | Cycle detection must cover rule invocation identity. |
| G-08 | Missing `derivationRuleUid` provider | Fail loud; no silent fallback. |
| G-09 | Missing `maxRuleUid` provider | Fail loud. |
| G-10 | Missing `regenerationRuleUid` provider | Fail loud. |
| G-11 | Incompatible provider/rule version | Deterministic version error; never silently use latest. |
| G-12 | Rule returns NaN/Infinity | Reject derived result. |
| G-13 | Rule attempts cross-campaign/player dependency | Reject scope violation. |
| G-14 | Rule depends on unordered iteration | Replay/permutation tests must produce identical output. |

Cycle failure must happen before infinite recursion/stack overflow and should identify a stable cycle path for debugging.

## 5. Phase 4 legacy/reconciliation attacks

WORK-014 established `LegacyStatAlias` / `LegacyResourceAlias`. Phase 5 must consume canonical reconciled identity and must not redo semantic guessing.

| ID | Case | Required outcome |
|---|---|---|
| L-01 | Alias target deleted/missing | Fail before resolver graph; never silently fall back by key. |
| L-02 | Alias target owner changed | Fail ownership validation. |
| L-03 | `mappingVersion` mismatch/change | Must affect deterministic input/cache fingerprint or trigger explicit invalidation/error. |
| L-04 | Alias exists, legacy value exists, typed player value absent | Exactly one canonical typed UID node projected from legacy. |
| L-05 | Alias exists, legacy + typed player values both exist | Persisted typed representation canonical; exactly one resolver node. |
| L-06 | Alias exists, legacy source row absent | Alias alone must not invent numeric value. |
| L-07 | Unmapped unrelated legacy value | Remains visible as generic compatibility node. |
| L-08 | Unmapped same-looking legacy + typed | Phase 4 fail-loud must happen before resolver graph. Resolver cannot guess. |
| L-09 | Legacy key case changes (`Strength`/`strength`) | No implicit case-fold merge unless explicit Phase 4 identity contract says so. |
| L-10 | Same typed key in different World Packs | Never auto-merge; stable UIDs remain distinct. |
| L-11 | World Pack modifier targets reserved legacy identity | Reserved namespace cannot be hijacked; explicit policy required for any read-only compatibility targeting. |
| L-12 | Alias provenance missing/corrupt | Fail validation where provenance is required by Phase 4 contract. |
| L-13 | Campaign A alias supplied to campaign B | Reject campaign mismatch. |
| L-14 | Alias target changes without mappingVersion increment | Treat as integrity violation; stale fingerprint is unsafe. |

At minimum, canonical target UID + mappingVersion + relevant version/provenance identity must influence input fingerprint when alias reconciliation influences the resolver input.

## 6. Security / isolation attacks

| ID | Case | Required outcome |
|---|---|---|
| S-01 | Campaign A modifier in campaign B request | Cannot affect B. |
| S-02 | Player A modifier in Player B request | Cannot affect B. |
| S-03 | Same player UID string in two campaigns | Campaign boundary still isolates inputs/results. |
| S-04 | World Pack A targets definition owned by B | Default fail-closed unless explicit generic cross-pack authorization exists. |
| S-05 | World Pack claims `RPGOS-LEGACY-COMPAT` | Reject reserved namespace. |
| S-06 | Source UID collision across campaigns | Scope prevents cross-campaign effect. |
| S-07 | Persisted modifier target changed while modifier UID reused | Conflicting identity/version/provenance must be detected. |
| S-08 | Duplicate modifier UID across players | Contract must define UID scope explicitly and enforce it. |

## 7. Temporal boundary attacks

Resolution must use a supplied deterministic epoch/time rather than repeated wall-clock reads.

- T-01 active interval applies.
- T-02 future modifier ignored.
- T-03 expired modifier ignored.
- T-04 `validFrom == now` exact boundary.
- T-05 `validUntil == now` exact boundary.
- T-06 replay with same historical resolution epoch returns same active set/result.
- T-07 changed epoch can legitimately change set/result/fingerprint.
- T-08 `validUntil < validFrom` rejected.

The inclusive/exclusive boundary convention must be documented and tested without wall-clock race conditions.

## 8. Operation-order adversarial cases

Using WORK-007's deterministic lifecycle/order contract, require at least:

1. base=100, permanent +10, equipment +20, injury -30, temporary +5 => 105.
2. Reversed input list => same 105 and same semantic trace/fingerprint.
3. ADD + MULTIPLY insertion order permutations => same result.
4. Multiple multipliers randomized => same result.
5. OVERRIDE combined with ADD/MULTIPLY => exact documented stage/priority semantics.
6. Contradictory floor/cap ranges => deterministic validation or explicitly defined final behavior.
7. Same priority => stable modifier UID tie-break.
8. SQLite/list/hash ordering variation => identical effective value, applied-set semantics and deterministic fingerprint.

Explanation trace must expose why each contribution was applied, ignored, superseded or rejected.

## 9. Persistence/reopen adversarial cases — conditional

If WORK-015 makes modifiers persistent:

- P-01 old DB without Phase 5 tables migrates/open cleanly.
- P-02 Phase 4 stat/resource/alias state remains unchanged.
- P-03 modifier UID/target/source/time/priority/version/provenance survives reopen.
- P-04 expiry affects resolution, not silent historical deletion unless explicitly designed.
- P-05 source removal never rewrites base/current values.
- P-06 migration idempotency x2.
- P-07 campaign isolation after reopen.
- P-08 player isolation after reopen.
- P-09 >1000 modifiers no silent truncation.
- P-10 `PRAGMA integrity_check == ok`.
- P-11 `PRAGMA foreign_key_check` clean or explicit alternative FK-policy test.

If modifiers are not persisted, mark these NOT APPLICABLE rather than introducing storage just to satisfy the audit.

## 10. Resource-specific adversarial cases

1. current=150, derived max falls from 200 to 100: resolver may emit inconsistency/proposal but MUST NOT write current=100.
2. regenerationRate=+10: resolver reports rate and MUST NOT add 10 to current.
3. negative regeneration: explicit policy required; no guessed semantics.
4. negative derived maximum: deterministic rule/bounds validation; no current mutation.
5. cycle in maximum/regeneration dependency graph: deterministic cycle error.
6. current value belongs to A but graph/modifiers belong to B: reject scope mismatch.
7. alias mappingVersion changes: input fingerprint/trace reflects reconciliation change.

## 11. No-retrogression adversarial cases

For each modifier source class, capture base before/after resolution and after source removal/reopen:

- injury -40: base 100 -> effective 60 -> remove injury -> effective 100; base always 100.
- equipment +25: base 100 -> 125 -> unequip -> 100; base always 100.
- temporary +50: base 100 -> 150 -> expiry -> 100; base always 100.
- temporary multiplier x2: base 100 -> 200 -> expiry -> 100; base always 100.

Any resolver-side write to `PlayerStat.baseValue` or `PlayerResource.currentValue` is a release blocker.

## 12. Scale / pathological input sanity

- 100 modifiers on one target: exact result, no truncation.
- >1000 modifiers: exact result or explicit documented safe-bound failure; never silent truncation.
- deep acyclic graph: no stack overflow.
- deep cycle near tail: deterministic cycle detection.
- duplicate edges/modifier payloads: no exponential expansion.

## 13. Release-gating checklist after WORK-015

After CHAT-1 publishes WORK-015, classify each family as `PASS | FAIL | NOT TESTED | NOT APPLICABLE`.

Automatic blockers include:

- resolver writes base/current authoritative state;
- duplicate modifier UID can double-apply or resolve nondeterministically;
- NaN/Infinity reaches a resolved value;
- result depends on incoming/SQLite/hash order;
- graph cycles can recurse indefinitely/overflow stack;
- campaign/player cross-scope application;
- missing/incompatible rule silently falls back;
- Phase 4 ambiguity gate is bypassed or mapping guessed;
- alias mapping version is invisible to fingerprint/cache where relevant;
- same-key cross-World-Pack definitions auto-merge;
- silent modifier truncation;
- Phase 5 migration damages Phase 4 state/aliases.

## 14. Current implementation review status

Repository history was rechecked immediately before writing this report. Latest visible master was `7e151ff3b44793dbd470e022b5ded9ac9a20cc87`, containing documentation work through WORK-018. No commit matching `WORK-20260809-015` was present.

Therefore the runtime adversarial validation cannot yet be performed.

Current final status for this pre-implementation step:

`PHASE 5 ADVERSARIAL MATRIX READY`

After WORK-015 appears, this report must be extended with actual runtime/test/CI evidence and exactly one final verdict:

`PHASE 5 ADVERSARIAL VALIDATION: PASS`

or

`PHASE 5 ADVERSARIAL VALIDATION: FAIL`

No runtime implementation changes are authorized under WORK-019.
