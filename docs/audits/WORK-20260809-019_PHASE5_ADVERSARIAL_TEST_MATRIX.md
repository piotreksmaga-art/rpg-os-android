# WORK-20260809-019 — Phase 5 Adversarial / Edge-Case Validation

Status: FINAL READ-ONLY ADVERSARIAL VALIDATION

Work ID: `WORK-20260809-019`
Owner: `CHAT-5`
Role: PHASE 5 ADVERSARIAL / EDGE-CASE AUDITOR
Repository: `piotreksmaga-art/rpg-os-android`
Runtime under final audit: `44011bc0177df846a34fa12d0009d33e887f6c23`
Runtime commit: `WORK-20260809-015 — implement deterministic derived values and modifiers`
CI evidence: GitHub Actions run #141 — SUCCESS

This report is a read-only adversarial validation of the actual WORK-015 runtime. It does not implement or modify Phase 5 runtime.

## 1. Final verdict

`PHASE 5 ADVERSARIAL VALIDATION: PASS`

No reproducible defect was found that violates the current Phase 5 contract by mutating authoritative base/current state, applying modifiers nondeterministically, leaking across campaign/player scope, accepting non-finite arithmetic, bypassing Phase 4 legacy reconciliation, or silently allowing cyclic rule resolution.

Several future integration responsibilities and additional stress tests remain useful, but they do not constitute a current Phase 5 contract failure.

## 2. Runtime inspected

WORK-015 adds:

- `DerivedValueResolver`
- generic `Modifier` / lifecycle / operation / target contracts
- `DerivedRuleProvider` and versioned rule descriptors
- persistent `ModifierStore`
- additive migration marker `RPGOS-5.0-DERIVED-MODIFIERS`
- resolver unit tests
- modifier persistence/integrity tests

The resolver is a pure projection. It receives `PlayerStat.baseValue` and `PlayerResource.currentValue` as inputs and returns derived effective/max/regeneration values without persisting resolved results.

## 3. Numeric adversarial matrix

| Case | Result | Evidence / assessment |
|---|---|---|
| modifier `NaN` | PASS | `ModifierPolicy.validate()` requires finite value; unit test rejects NaN. |
| `+Infinity` / `-Infinity` modifier | PASS by same finite guard | Both are non-finite and rejected by the same policy. |
| non-finite base/current | PASS | resolver validates `PlayerStat.baseValue` and `PlayerResource.currentValue` with `isFinite()`. |
| arithmetic overflow | PASS | every arithmetic stage uses `finite(...)`; `Double.MAX_VALUE * 2` is tested as failure. |
| rule returns NaN/Infinity | PASS by code path | rule output passes through `finite(...)`. |
| `-0.0` | PASS | public/fingerprint zero is canonicalized to `+0.0`; test checks raw bits. |
| zero multiplier | PASS by code inspection | finite `0.0` is legal and multiply stage deterministically yields zero; no base mutation. |
| negative multiplier | PASS / explicit generic behavior | Phase 5 generic model permits any finite multiplier; it is deterministic and may later be constrained by World Pack/domain rules. No hidden mutation occurs. |
| huge finite multiplier | PASS | legal while finite; overflow fails rather than producing Infinity. |
| underflow/subnormal | PASS by deterministic IEEE-754 semantics; dedicated stress fixture absent | No alternate rounding or unordered reduction is used. |
| many multipliers | PASS by implementation shape; dedicated >1000 multiplier fixture absent | modifiers are not truncated and are canonically sorted; any non-finite intermediate fails loud. |

No numeric attack was found that can produce a non-finite accepted derived result.

## 4. Determinism validation

Canonical ordering is explicit:

1. lifecycle enum order: `PERMANENT -> EQUIPMENT -> INJURY -> TEMPORARY`,
2. operation stage inside lifecycle,
3. priority,
4. stable `modifierUid` tie-break.

The resolver does not consume incoming modifier list order as semantic order. Tests reverse and randomly shuffle the same logical modifiers and require identical full `DerivedResolutionResult`. A >1000 additive fixture verifies no silent truncation.

Assessment:

- reversed insertion/list order: PASS,
- random list permutations: PASS,
- same priority: PASS via UID tie-break,
- same validity timestamps: PASS; timestamp coincidence does not affect tie-break,
- SQLite row order: PASS architecturally because `ModifierStore` has explicit ordering and resolver independently canonicalizes by stage/priority/UID,
- replay same request: PASS,
- input fingerprint: deterministic sorting by stable identity,
- alias metadata: mapping version/provenance/canonical identity participates in fingerprint.

No result dependence on `HashMap`/SQLite/insertion order was found.

## 5. Lifecycle and temporal attacks

`ModifierPolicy.isEffectiveAt()` requires:

- `active == true`,
- `sourceActive == true`,
- `resolutionEpoch >= validFrom` when present,
- `resolutionEpoch <= validUntil` when present.

Therefore boundaries are explicitly inclusive.

Results:

- inactive modifier: PASS,
- inactive source: PASS,
- future modifier: PASS,
- expired modifier: PASS,
- `validFrom == epoch`: PASS and tested,
- `validUntil == epoch`: PASS and tested,
- `validUntil < validFrom`: PASS, rejected at model validation and SQL CHECK layer,
- explicit source removal / modifier removal: PASS; derived result changes without base mutation.

### `sourceActive` assessment

`sourceActive` is currently a persisted generic snapshot/input to the resolver. `ModifierStore.setSourceActive()` can deactivate all modifiers for a concrete `(campaign, character, sourceType, sourceUid)` and the resolver ignores them.

Phase 5 does not yet own Equipment, Inventory or Injury-domain lifecycle orchestration. Therefore automatic synchronization such as “equipment row deleted => sourceActive automatically flips false” is a future integration responsibility of those domains, not a Phase 5 resolver defect. WORK-015 does not create a side mutation path that would make stale source state silently authoritative outside the modifier input model.

Current assessment: PASS with future integration responsibility.

## 6. Graph / rule attacks

The resolver uses a node stack keyed by `(targetKind, definitionUid)` and fails on re-entry.

Assessment:

- A -> A: PASS by cycle guard,
- A -> B -> A: PASS and tested,
- A -> B -> C -> A: PASS by same node-cycle mechanism,
- missing dependency node: PASS; missing target resolution fails loud,
- duplicate dependency: PASS; descriptor and resolver reject duplicate dependencies,
- missing rule: PASS and tested,
- missing provider: PASS by explicit error,
- missing rule-version binding: PASS by explicit error,
- incompatible rule version: PASS and tested,
- rule non-finite result: PASS via `finite(...)`,
- same rule reached recursively through a target cycle: PASS through target-node cycle guard.

### Deep acyclic graph

The current implementation uses recursive target resolution and does not declare a maximum graph depth. Existing tests cover cycles but not a pathological thousands-deep acyclic rule chain. This is a useful future stress test and defensive-hardening candidate.

It is not classified as a current release blocker because no normal/current Phase 5 contract defines such an unbounded rule graph or promises arbitrary recursion depth; importantly, cycles do not recurse indefinitely and missing nodes/rules fail deterministically.

## 7. Isolation / security boundaries

- modifier campaign mismatch: PASS, resolver rejects,
- modifier player mismatch: PASS, resolver rejects,
- persisted reads are scoped by campaign + character: PASS,
- same player UID string in another campaign: isolated by campaign scope,
- target definition missing: PASS, rejected,
- reserved legacy definition target: PASS, rejected by resolver and store,
- reserved legacy namespace cannot be used as canonical modifier target: PASS,
- duplicate modifier UID in one request: PASS, rejected,
- duplicate persisted modifier UID in campaign: PASS, rejected / PK-protected,
- player/campaign persistence isolation: PASS and tested.

Phase 5 does not contain a source-World-Pack authorization model. A modifier targets a canonical typed definition UID; whether a future World Pack/domain is authorized to create a cross-pack effect belongs to the future command/rule/domain authorization layer. The current resolver does not infer or merge definitions by text key and does not allow reserved legacy identities as targets.

## 8. Legacy / Phase 4 reconciliation attacks

WORK-015 consumes the Phase 4 canonical read contract rather than guessing mappings.

Results:

- mapped legacy value projected to canonical typed UID: PASS,
- mapped legacy + typed representation resolves once: PASS / covered by persistence test,
- unmapped same-looking legacy + typed: PASS; Phase 4 typed read fails before resolver,
- same textual key across two typed World Packs: PASS; stable UIDs remain distinct,
- alias from another campaign: PASS; resolver rejects campaign mismatch,
- mappingVersion/provenance changes fingerprint: PASS and tested,
- modifier targeting reserved legacy UID: PASS, rejected,
- unknown unmapped legacy values: remain Phase 4 compatibility data and are not silently guessed by Phase 5.

Alias target existence/ownership is validated by the Phase 4 reconciliation registration/read boundary before canonical resolver input is assembled. Phase 5 does not implement a second competing reconciliation engine, which is the correct separation of responsibilities.

## 9. Resource safety

`ResolvedResource` exposes:

- `currentValueObserved`,
- derived `maximumValue`,
- derived `regenerationRate`.

The resolver performs no authoritative write.

Tests prove:

- current resource remains unchanged,
- regeneration is reported but not applied,
- current > derived maximum produces a diagnostic only,
- no hidden clamp writes current to maximum,
- maximum/regeneration modifiers operate only on derived target kinds.

Assessment: PASS.

Negative maximum/regeneration values are generic numeric results unless constrained by definition/rule/domain policy; Phase 5 does not silently reinterpret or mutate current state based on them.

## 10. No-retrogression

Verified behavior:

- injury modifies effective stat only,
- equipment modifies effective stat only,
- temporary modifier modifies effective stat only,
- removing/deactivating source restores effective result from unchanged base,
- persistence/reopen does not rewrite `player_stats.base_value`,
- resolver does not write `player_resources.current_value`.

Persistence tests explicitly inspect SQL base/current values before/after resolution/removal.

Assessment: PASS.

## 11. Persistence / migration / scale

Modifiers are persistent authoritative inputs; derived outputs are not persisted.

Observed protections:

- Phase 5 table is additive,
- migration marker is idempotent,
- old campaign creates zero invented modifiers,
- Phase 4 legacy bytes and base values remain unchanged,
- modifier source state survives reopen,
- duplicate UID is rejected,
- missing target is rejected,
- invalid lifetime is rejected,
- >1000 persisted modifiers are returned and resolved without truncation,
- `PRAGMA integrity_check` is `ok`,
- `PRAGMA foreign_key_check` is clean in the test fixture.

Assessment: PASS.

## 12. Explanation / diagnostics

Applied contributions expose stable sequence index, modifier UID, lifecycle, operation, priority, source identity, input, magnitude, output and provenance. Inactive/source-inactive/future/expired modifiers produce diagnostics. Definition bounds and resource-current-above-max also produce diagnostics.

The trace is sufficient to reconstruct the current implemented arithmetic. Grouped `ADD_PERCENT` records the shared stage input and cumulative outputs. Overrides are represented as sequential deterministic contributions; later higher-priority/UID application establishes the final value rather than silently depending on input order.

Assessment: PASS for current Phase 5 auditability contract.

## 13. CI evidence

GitHub Actions run #141 corresponds exactly to:

`44011bc0177df846a34fa12d0009d33e887f6c23`

Conclusion: `success`.

The workflow job reports:

- Validate project: SUCCESS,
- Run JVM unit tests: SUCCESS,
- Build signed ALPHA APK: SUCCESS,
- artifact/release steps: SUCCESS.

## 14. Matrix summary

| Family | Final assessment |
|---|---|
| Numeric finite guards | PASS |
| Overflow / negative-zero | PASS |
| Underflow pathological stress | NOT TESTED, non-blocking |
| Deterministic ordering | PASS |
| Exact time boundaries | PASS |
| Duplicate UID | PASS |
| Source active/inactive | PASS |
| Automatic future domain-source synchronization | NOT APPLICABLE to current Phase 5; future integration responsibility |
| Missing target | PASS |
| Campaign/player isolation | PASS |
| Reserved legacy identity | PASS |
| Legacy mapping/fingerprint | PASS |
| Mixed legacy/typed ambiguity gate | PASS at Phase 4 boundary |
| Resource current immutability | PASS |
| No-retrogression | PASS |
| Missing/incompatible rule | PASS |
| Cycles | PASS |
| Pathologically deep acyclic graph | NOT TESTED, defensive debt |
| >1000 modifiers | PASS |
| Persistence/reopen | PASS |
| SQLite integrity/FK checks | PASS |
| CI #141 | PASS |

## 15. Non-blocking follow-up debt

Recommended future hardening, without expanding current Phase 5 scope:

1. add explicit test for `+Infinity` and `-Infinity` modifiers/base/current even though the shared finite guard already rejects them;
2. add dedicated zero/negative multiplier tests to pin the intentionally generic finite-multiplier policy;
3. add underflow/subnormal fixture;
4. add >1000 repeated multiplier fixture with a finite case and overflow-failure case;
5. add deep acyclic graph stress test and consider an explicit defensive maximum graph depth/size if World Pack rule graphs become externally configurable;
6. when Equipment/Inventory/Injury domains are implemented, make them responsible for synchronizing modifier `sourceActive` through a legal domain/transaction path rather than teaching the resolver to inspect those future stores directly;
7. if cross-World-Pack modifier creation later requires authorization, enforce it in the command/rule/domain creation path while preserving resolver identity semantics.

None of these items demonstrates a current reproducible Phase 5 contract violation in WORK-015.

# Final status

`PHASE 5 ADVERSARIAL VALIDATION: PASS`

This verdict is only the CHAT-5 adversarial gate. It does not mark global Phase 5 COMPLETE; coordinator must still combine WORK-015 runtime/CI with CHAT-2 determinism findings, CHAT-3 integrity validation and the remaining canonical completion criteria.
