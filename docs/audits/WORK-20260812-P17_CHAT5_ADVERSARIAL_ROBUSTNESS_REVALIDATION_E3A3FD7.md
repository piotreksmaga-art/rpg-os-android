# PHASE 17 ADVERSARIAL / ROBUSTNESS REVALIDATION: FAIL

ROLE: CHAT-5
VALIDATED RUNTIME SHA: e3a3fd7cf26e6e9a7cebd5dcbe17bc6ce4c9c4d5

Audit mode: independent adversarial / robustness review, runtime read-only. No production, test, schema, workflow or runtime modification performed.

## Repository-first

At the last pre-report check, master was `2cb1045eff50866af98590a6b00692d73892d971`. Comparison from the validated runtime to that head showed exactly three report-only files under `docs/audits/`; merge-base remained the validated runtime. Therefore no newer production/test runtime superseded `e3a3fd7cf26e6e9a7cebd5dcbe17bc6ce4c9c4d5`.

## Executive verdict

Hotfix2 itself is robust for its stated financial causal uniqueness invariant, but the Phase-17 contract still contains a release-blocking semantic defect in typed conflict-key construction. Asset identity is modeled and serialized as `(assetKindUid, assetUid)`, but `TypedPlayerChangeRegistry` reduces that tuple to the non-injective string `ASSET:${assetKindUid}:${assetUid}`. Both components permit nonblank strings containing `:`. Distinct valid `OwnedAssetRef` values can therefore alias to the same conflict key and be rejected as `CONFLICTING_CHANGE_TARGET`.

This breaks the required full asset identity semantics and means the earlier asset hotfix is not robust across the legal UID domain.

## BLOCKER — P17-ROBUST-ASSET-CONFLICT-KEY-ALIAS-01

Severity: RELEASE BLOCKER / HIGH

Production path:

`PlayerChangeSet.create` -> `PlayerChangeSetValidator.validate` -> `TypedPlayerChangeRegistry.conflictKeys` -> AssetChange codec conflict key.

Relevant production behavior:

- `AssetChange` carries an `OwnedAssetRef(assetKindUid, assetUid)`.
- Asset validation requires only nonblank `assetKindUid`, nonblank `assetUid`, and nonblank proposed lifecycle state.
- `OwnedAssetRef` itself is a two-field data class with no delimiter restriction.
- asset/domain policy also accepts nonblank kind/UID values and does not forbid `:`.
- the conflict key is built as `ASSET:${assetKindUid}:${assetUid}`.
- the ChangeSet validator uses a `HashSet<String>` of these keys and rejects a duplicate string with `CONFLICTING_CHANGE_TARGET`.

Minimal reproduction:

```
A = OwnedAssetRef(
  assetKindUid = "RPGOS-ASSET-KIND:PROPERTY",
  assetUid = "BUSINESS:A-1"
)

B = OwnedAssetRef(
  assetKindUid = "RPGOS-ASSET-KIND:PROPERTY:BUSINESS",
  assetUid = "A-1"
)
```

Both tuples are structurally distinct and valid under the current nonblank-only constraints.

But both produce:

```
ASSET:RPGOS-ASSET-KIND:PROPERTY:BUSINESS:A-1
```

Constructing two `AssetChange` values with different change UIDs and these two asset refs therefore reaches the semantic-target set with an identical String key.

Expected:

ACCEPT as two distinct asset identities because `(assetKindUid, assetUid)` differs.

Actual:

REJECT with `CONFLICTING_CHANGE_TARGET` due to delimiter aliasing in the conflict key.

Architectural impact:

- full AssetChange identity is not preserved by conflict detection;
- legal world/custom asset-kind UID domains can false-conflict;
- the conflict key is not an injective representation of the tuple it claims to identify;
- P17-HOTFIX-03 verifies only ordinary values and asserts the vulnerable concatenated representation, so it does not protect against tuple aliasing;
- the same concatenation pattern appears in several other multi-component typed conflict keys and should be reviewed for the same class of aliasing.

Minimal recommended fix (not implemented):

Use an injective structured conflict identity rather than delimiter concatenation, e.g. a typed data key / tuple object, or an unambiguous length-prefixed/escaped canonical encoding. Preserve exact `(assetKindUid, assetUid)` equality semantics. Add adversarial tests where delimiters occur in different tuple components and prove distinct tuples remain distinct.

## Financial / ledger robustness

Hotfix2 gate itself passed adversarial review:

- one causal `FinancialChange.changeUid` can be represented by at most one FinancialTransferLedgerIntent across a ChangeSet;
- two different `ledgerIntentUid` values referencing the same FinancialChange reject with `DUPLICATE_FINANCIAL_LEDGER_CAUSAL_CHANGE`;
- exact term matching remains `fromAccountUid`, `toAccountUid`, `amountMinor`, `currencyUid`, `transactionTypeUid`;
- mismatch is evaluated before duplicate-causal registration, preserving `FINANCIAL_LEDGER_TERMS_MISMATCH`;
- a mixed causal list containing a FinancialChange consumes that FinancialChange for uniqueness purposes;
- two independent financial changes with disjoint account targets and two corresponding ledger intents remain legal;
- standalone financial ledger intents with empty causal refs remain legal;
- causal refs containing only non-financial changes reject with `FINANCIAL_LEDGER_CAUSAL_CHANGE_REQUIRED`;
- dangling causal refs reject with `INVALID_LEDGER_INTENT`;
- repeated causal UID inside one ledger is redundant metadata but still one ledger intent / one append proposal, not a second ledger representation.

Two semantically identical standalone ledger intents with different ledgerIntentUid values are not rejected by terms alone. This was not classified as a defect: without a causal FinancialChange, two transfers with identical immutable terms may be two intentional proposed transfers, and term-only deduplication would create false conflicts.

## Codec / serialized input robustness

The production codec:

- validates before encode;
- duplicate-key scans before JSON parse;
- rejects unknown root and nested fields;
- enforces String scalar typing;
- enforces numeric scalar typing and rejects quoted numbers;
- rejects unsupported schema versions;
- re-enters `PlayerChangeSet.create` and validator during decode;
- re-validates before returning;
- exposes no public registry encode/decode bypass;
- canonical encode/decode/encode and fingerprint paths remain deterministic for legal inputs.

Robustness note, not the release blocker above: some malformed nested array element shapes are converted with `.jsonObject` outside the localized payload normalization blocks, so they may escape as library `IllegalArgumentException` rather than `PlayerChangeSetStructuralException`. They still fail closed, but the error type is less uniform than the tested malformed-input cases. This should be hardened separately if public decode promises a single structural-error family.

## Immutability / aliasing / authority

- root lists are defensive-copied into unmodifiable lists;
- event target/causal lists and ledger causal lists are defensively copied;
- DevelopmentProject evidence refs are defensively copied;
- ordinary payloads are immutable value objects;
- no PlayerChangeSet apply/commit/save/persist/DB-authority method exists;
- validator, codec, fingerprint and identity comparison are proposal operations only;
- zero-authoritative-mutation tests remain on production paths.

No authoritative mutation path was found.

## Duplicate/conflict semantics

- duplicate change UIDs fail closed;
- duplicate event and ledger intent UIDs fail closed;
- same declared typed target conflicts fail closed;
- ordered lists remain part of canonical representation/fingerprint;
- however, typed semantic target conflict keys that concatenate multiple opaque UID components with `:` are not robustly injective. AssetChange is the release-blocking instance verified above.

## Numeric robustness

- financial amounts are exact signed Long values and must be positive;
- exact financial equality is Long equality;
- ExactLongDelta uses checked `Math.addExact` / `Math.subtractExact`;
- fixed-scale OwnershipShare avoids floating point;
- no Float/Double authority was found in PlayerChangeSet financial/progress representation.

No new numeric blocker was found.

## Existing tests review

`PlayerChangeSetReleaseBlockerHotfix2Test` genuinely exercises production construction/validator/codec paths for Hotfix2-01..12. Assertions cover same FinancialChange across two ledger UIDs, independent finance changes, standalone ledger, mixed refs, existing conflict gate, mismatch ordering, non-financial and dangling refs, round-trip, fingerprint and zero mutation.

`PlayerChangeSetReleaseBlockerHotfixTest` genuinely exercises the earlier asset/finance production paths. However, its asset conflict-key tests use ordinary delimiter placement and therefore do not test injectivity of `(assetKindUid, assetUid)` when `:` occurs in different components. In particular, P17-HOTFIX-03 asserts the exact concatenated String form that permits the alias.

`PlayerChangeSetContractTest` covers immutability, typed families, world-agnostic checks, no generic mutation primitive, proposal-only behavior, duplicate/conflict basics, order behavior, numeric exactness, strict serialization cases and Phase 3-16 regressions.

The suite is meaningful, but it does not prove the adversarial asset conflict-key property discovered here.

## Phase 3-16 / CI

Exact CI rechecked independently:

- workflow run number: 361
- run ID: 31639002452
- head SHA: e3a3fd7cf26e6e9a7cebd5dcbe17bc6ce4c9c4d5
- conclusion: SUCCESS
- `Validate project`: SUCCESS
- `Run JVM unit tests`: SUCCESS
- workflow command for the full JVM suite: `gradle --no-daemon :app:testDebugUnitTest --stacktrace`
- signed ALPHA APK: SUCCESS
- release preparation: SUCCESS
- artifact upload: SUCCESS
- update of existing release assets: SUCCESS
- overall workflow: SUCCESS

Green CI does not clear the newly found adversarial semantic defect because no existing test covers the delimiter-alias fixture.

## Gate summary

HOTFIX2 FINANCIAL CAUSAL UNIQUENESS: PASS
FINANCIAL TERM CONSISTENCY: PASS
MULTIPLE FINANCIAL / MULTIPLE LEDGER: PASS
MULTIPLE CAUSAL REFS: PASS
STANDALONE LEDGER: PASS
NON-FINANCIAL CAUSAL REJECTION: PASS
DANGLING CAUSAL REJECTION: PASS
ASSET MODEL / CODEC ROUND-TRIP: PASS
ASSET CONFLICT IDENTITY ROBUSTNESS: FAIL
DUPLICATE / CONFLICT SEMANTICS: FAIL (due non-injective conflict-key encoding)
PUBLIC CODEC / VALIDATION PATH: PASS, with non-blocking exception-normalization note
STRICT SERIALIZED INPUT: PASS fail-closed; error-family hardening note above
CANONICALIZATION / FINGERPRINT: PASS
NUMERIC SAFETY: PASS
IMMUTABILITY / ALIASING: PASS
ZERO AUTHORITATIVE MUTATION: PASS
PHASE 3-16 REGRESSION: PASS
FULL :app:testDebugUnitTest: PASS in exact CI
EXACT CI #361: PASS

NEW BLOCKERS:

- P17-ROBUST-ASSET-CONFLICT-KEY-ALIAS-01 — RELEASE BLOCKER / HIGH

FINAL CHAT-5 VERDICT: FAIL

Phase 17 is NOT globally ACCEPTED.
Phase 18 remains BLOCKED until 3× independent PASS on the same runtime SHA after all release blockers are resolved.
