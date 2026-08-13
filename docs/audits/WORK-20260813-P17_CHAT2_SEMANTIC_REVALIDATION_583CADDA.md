# PHASE 17 — CHAT-2 FINAL SEMANTIC REVALIDATION AFTER PROJECT ZERO-PROGRESS FIX

ROLE: CHAT-2 — Independent Semantic Auditor

Validated runtime SHA: `583cadda7aca20e3d4c243a3007e8f8a19e1bbae`

Verdict: **PASS**

## Runtime pin

At audit start `master` pointed exactly to `583cadda7aca20e3d4c243a3007e8f8a19e1bbae`. The target exists and is therefore trivially an ancestor of that fresh master; `target..master` was empty. No newer production/test Phase-17 runtime existed.

## P17-PROJECT-ZERO-PROGRESS-01

Phase-15 production semantics genuinely permit durable project work with zero progress. `ProjectWorkResult` includes FAILURE and NO_PROGRESS and `ProjectWorkRecord.progressDeltaUnits` defaults to 0. The Phase-15 persistence regression explicitly records a FAILURE work record with progress 0 and later verifies three work records despite only the positive records contributing to aggregate progress. A zero-progress work occurrence is therefore semantically distinct from absence of project work.

Phase 17 now models project progress using `ProjectProgressDelta`, whose constructor-level invariant is `units >= 0`. `of(0)`, `of(1)`, and `of(Long.MAX_VALUE)` are legal; negative values including `Long.MIN_VALUE`, including through generated `copy`, are rejected. `DevelopmentProjectChange` uses this project-specific value type, preserving work-result, evidence, source-rule and proposal provenance semantics without weakening `ExactLongDelta`.

## ExactLongDelta and constrained values

`ExactLongDelta` retains constructor-level `units != 0`; `of(0)` and `of(1).copy(units=0)` reject. Positive and negative nonzero Long values remain legal. Arithmetic helpers use `Math.addExact` / `Math.subtractExact` for overflow/underflow failure.

Remaining usages are StatChange, ResourceChange, SkillChange, TechniqueChange, InventoryChange and RuntimeChange; each represents a mutation delta where zero is a no-op rather than a durable work-result event. No second semantic misuse was found.

`OwnershipShare` retains constructor-level range `1..OWNERSHIP_SHARE_SCALE`; generated `copy` therefore cannot bypass zero/range constraints. Fraction construction is exact through `BigInteger`, with no Float/Double constructor.

## Serialization closure and numeric typing

Development-project codec emits `progressDeltaUnits` as a JSON numeric Long and decodes through strict `pcsReqLong` followed by `ProjectProgressDelta.of`. Legal zero therefore round-trips as numeric 0. Quoted numeric values are rejected as wrong JSON type; non-integral numeric values fail Long parsing; negative project progress fails the project value invariant. Canonical `encode -> decode -> encode` is deterministic.

All Long fields are serialized directly as numeric Long values, preserving values beyond the IEEE-754 exact-integer range without Float/Double conversion.

## Fingerprint

Fingerprint is SHA-256 over validated canonical encoding. A legal zero-progress project proposal therefore has stable identity across round-trip. Work result UID, progress value and evidence are part of the canonical payload, so semantic changes such as FAILURE+0 versus SUCCESS+positive progress change the fingerprint. Invalid constrained-value state cannot pass construction/validation to obtain a canonical legal fingerprint.

## Composite conflict identity

Shared composite identity remains injective for STAT, RESOURCE, SKILL, TECHNIQUE, INNATE, INVENTORY, EQUIPMENT, ASSET, OWNED_ASSET, CONDITION and RUNTIME over legal adversarial component strings. The active hardening suite covers colon, pipe, backslash, Unicode, whitespace and CK1-looking content. Ambiguous legacy delimiter shapes switch to length-prefixed `CK1` representation; same tuples still conflict, distinct tuples remain distinct, and legacy/CK1 representations are separated.

Historical STAT reproducer remains distinct:
- `DomainRef("PLAYER", "X:Y"), statUid="Z"`
- `DomainRef("PLAYER", "X"), statUid="Y:Z"`

## Asset identity

`AssetChange` preserves the full `OwnedAssetRef(assetKindUid, assetUid)` through model, validation, composite conflict key, canonical serialization/decode and fingerprint. Property/business and delimiter-alias regressions remain distinct.

## Financial / ledger

Financial terms are exact five-field tuples: `fromAccountUid`, `toAccountUid`, `amountMinor`, `currencyUid`, `transactionTypeUid`. Validation requires nonblank accounts/currency/type, distinct accounts and positive exact Long minor units. Causal ledger matching compares all five fields exactly.

A financial change UID cannot be represented by more than one causal ledger intent. Multiple independent financial changes remain legal when target-conflict rules permit them. Multiple causal refs are checked; standalone ledger intents remain legal; dangling refs reject; causal lists containing no financial change reject. No internally contradictory accepted proposal was found.

## PlayerChangeSet contract / mutation safety

Phase 17 remains immutable, typed, world-agnostic, proposal-only and non-authoritative. Nested collections are defensively copied/unmodifiable. Construction, validation, conflict-key derivation, encode, decode and fingerprint have no DB/store/transaction execution authority and do not apply/commit/save/persist state. Existing zero-authoritative-mutation regression verifies an external SQLite authority fixture remains unchanged through the full proposal pipeline.

Duplicate change UIDs, event-intent UIDs and ledger-intent UIDs are rejected; duplicate semantic targets reject through conflict identity; dangling event/ledger causal refs and dangling warning related-change refs reject.

## Regression / exact CI

GitHub Actions run #379, run ID `31702264554`, is pinned to head SHA `583cadda7aca20e3d4c243a3007e8f8a19e1bbae`, status COMPLETED, conclusion SUCCESS.

The job log confirms the exact full command:
`gradle --no-daemon :app:testDebugUnitTest --stacktrace`

It completed `BUILD SUCCESSFUL` with 31 actionable tasks. Compilation includes `PlayerChangeSetProjectZeroProgressSemanticFixTest.kt` and `PlayerChangeSetValueInvariantHardeningTest.kt`, confirming those suites remain active. Validate project, signed release APK build, release-file preparation, Actions artifact upload and existing-release asset update all succeeded. Release creation itself was correctly skipped because the release already existed; asset update succeeded.

Representative Phase 3–16 regressions also remained in the full suite and passed.

## Final CHAT-2 verdict

**PASS**

NEW BLOCKERS: NONE

This report does not mark Phase 17 globally ACCEPTED and does not authorize Phase 18. Phase 18 remains blocked until CHAT-2, CHAT-3 and CHAT-5 independently PASS exactly `583cadda7aca20e3d4c243a3007e8f8a19e1bbae`.
