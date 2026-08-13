# WORK-20260813 — Phase 17 PlayerChangeSet Semantic Revalidation

Role: CHAT-2 — Semantic Auditor

Validated runtime SHA: `a13e9c795251da5d2fb241c5ff58e38a2d5b5f87`

Verdict: **PHASE 17 SEMANTIC REVALIDATION: PASS**

## Runtime pin

Fresh master at audit start and immediately before report commit was exactly `a13e9c795251da5d2fb241c5ff58e38a2d5b5f87`. No later production/test Phase-17 commit existed, so runtime changed after target = NO.

## ExactLongDelta invariant

PASS. `ExactLongDelta` now enforces `units != 0` in its private constructor `init` block as well as in `of()`. Because generated data-class `copy()` invokes that constructor, `ExactLongDelta.of(1).copy(units = 0)` deterministically throws `PlayerChangeSetStructuralException("ZERO_DELTA")`. Positive and negative non-zero values remain legal. `plus()` and `between()` use `Math.addExact` / `Math.subtractExact`, so arithmetic overflow fails closed.

Verified expected semantics:
- `ExactLongDelta.of(1)` — ACCEPT
- `ExactLongDelta.of(-1)` — ACCEPT
- `ExactLongDelta.of(0)` — REJECT
- `ExactLongDelta.of(1).copy(units = 0)` — REJECT
- `Long.MIN_VALUE` / `Long.MAX_VALUE` legal as standalone non-zero exact deltas; overflow in arithmetic rejects.

## Value-object copy / construction safety

PASS. The Phase-17 value-object review found no second factory-only invariant bypass of the same class. `OwnershipShare` uses a private constructor with an `init` invariant requiring `units in 1..OWNERSHIP_SHARE_SCALE`, so generated `copy()` cannot construct zero, negative, or >100% shares. There is no floating-point ownership constructor; fraction construction uses `BigInteger` and exact representability checks.

Other Phase-17 model constraints that are aggregate or cross-field semantics remain enforced by typed registry / PlayerChangeSet validation rather than being represented as self-validating scalar value objects, so generated copying of their plain data containers does not create a second authoritative path: a complete PlayerChangeSet still must pass canonical validation before encode/decode/fingerprint operations.

## Serialization closure and fingerprint

PASS. Every constructible legal `ExactLongDelta` is now serializable as a non-zero exact Long and is accepted on canonical decode. The previous state where an in-memory zero delta could be manufactured via `copy()` and then fail its own decode is closed. The target regression suite covers legal positive/negative deltas, all ExactLongDelta-bearing change paths, Long.MIN_VALUE/Long.MAX_VALUE round-trip, encode→decode→encode determinism and stable fingerprinting. Invalid zero typed state cannot be constructed through the public/generated value-object paths.

## Composite target identity / CK1 regression

PASS. Shared composite conflict identity remains applied to STAT, RESOURCE, SKILL, TECHNIQUE, INNATE, INVENTORY, EQUIPMENT, ASSET, OWNED_ASSET, CONDITION and RUNTIME. The prior STAT delimiter-alias reproducer remains distinct:

- `DomainRef("PLAYER", "X:Y"), statUid="Z"`
- `DomainRef("PLAYER", "X"), statUid="Y:Z"`

Legacy and `CK1|...` representations remain separated; ambiguous later components switch to explicit length-prefixed encoding.

## Asset identity

PASS. `AssetChange` retains full `OwnedAssetRef(assetKindUid, assetUid)` identity through model, validation, conflict identity, serialization, round-trip and fingerprint semantics. Earlier delimiter-aliasing hardening remains intact.

## Financial / ledger regression

PASS. Exact equality remains required for `fromAccountUid`, `toAccountUid`, `amountMinor`, `currencyUid`, and `transactionTypeUid` when a ledger intent causally represents a FinancialChange. A FinancialChange cannot be represented by multiple causal ledger intents. Standalone ledger intents remain legal. Dangling and non-financial-only causal references fail closed, while independent financial changes remain legal when they do not violate existing target-conflict semantics.

## PlayerChangeSet semantics

PASS. PlayerChangeSet remains immutable at its collection boundaries, world-agnostic, typed, proposal-only and non-authoritative. No `apply`, `commit`, `execute`, `save`, StatePatch authority, DB writer or PlayerChangeSet persistence authority was introduced by this hardening.

## Numeric / duplicate / reference safety

PASS. Exact Long semantics are preserved; no Float/Double authority was introduced. Ownership uses exact fixed-scale shares. Duplicate change/event/ledger IDs, duplicate semantic targets, dangling causal references and dangling warning references remain validated fail-closed. Strict numeric JSON behavior from earlier hardening remains unchanged.

## Zero authoritative mutation

PASS. Construction, validation, conflict-key derivation, encode, decode, identity/fingerprint operations remain pure proposal processing and do not write authoritative Phase 3–16 state. The target regression suite includes a direct zero-authoritative-DB-mutation fixture.

## Full regression / CI

Local checkout + `:app:testDebugUnitTest` was attempted, but the audit execution environment could not resolve `github.com`, so cloning failed before Gradle could start. This is recorded as an audit-environment limitation, not a test failure.

Exact CI was independently verified instead:
- GitHub Actions run #375
- run ID `31668369509`
- head SHA `a13e9c795251da5d2fb241c5ff58e38a2d5b5f87`
- status: completed
- conclusion: SUCCESS

The exact job shows SUCCESS for Validate project, full JVM unit tests, signed ALPHA APK, release preparation, Actions artifact upload and update of the existing release asset. Phase 3–16 representative regression checks remain present in the target value-invariant suite.

## New blockers

NONE.

## Final CHAT-2 verdict

**PHASE 17 SEMANTIC REVALIDATION: PASS**

This does not mark Phase 17 globally accepted. Phase 18 remains blocked until CHAT-2, CHAT-3 and CHAT-5 independently PASS this same runtime SHA.