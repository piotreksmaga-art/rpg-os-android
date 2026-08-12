# PHASE 17 ADVERSARIAL / ROBUSTNESS REVALIDATION: FAIL

ROLE: CHAT-5
VALIDATED RUNTIME SHA: c20577678b319590be09df45a41d4050a74dc783

Audit mode: read-only adversarial / robustness review. No production, test, schema, workflow or runtime modifications were made.

## Repository-first

Fresh master at audit start was exactly `c20577678b319590be09df45a41d4050a74dc783`. No later production/test runtime existed at audit start.

The target commit itself adds only `docs/audits/WORK-20260812-073_PHASE17_HOTFIX3_ROBUSTNESS_FOLLOWUP.md`; the actual Hotfix3 production change is in ancestor `97e6e1ba158f276936dbc52206602294e1cff335`, and Hotfix3 tests are in ancestor `72a8fd23a5afd160a760f83f2a91443dc5ba2bc2`. Therefore the audited runtime tree contains both the production fix and regression suite.

## Hotfix3 — asset conflict identity

Result: PASS for the stated AssetChange blocker.

Production now routes AssetChange conflict identity through `assetConflictKey(asset)`.

- If `assetUid` contains no colon, the old `ASSET:<kind>:<uid>` form is used. This branch remains injective because the final `assetUid` component itself cannot contain `:` in that branch, so equality of complete keys fixes both the final uid and the full prefix kind.
- If `assetUid` contains `:`, a length-prefixed representation is used: `ASSET|<kind.length>:<kind>|<uid.length>:<uid>`.
- `|`, `\\`, Unicode, whitespace inside otherwise nonblank values, digits adjacent to length prefixes, and text resembling the encoded representation do not create ambiguity because component lengths delimit the actual payload.
- The previous CHAT-5 reproducer now remains two distinct semantic targets.
- Same kind + same uid still conflicts.
- Different kind + same uid and same kind + different uid remain distinct.
- codec round-trip preserves the complete `OwnedAssetRef` tuple and changing `assetKindUid` changes canonical fingerprint semantics.

No new AssetChange conflict-identity collision was found in the legal input domain.

## NEW RELEASE BLOCKER — P17-ROBUST-TYPED-CONFLICT-KEY-ALIAS-02

Severity: RELEASE BLOCKER / HIGH

Hotfix3 fixes only AssetChange. Multiple other core typed change families still use raw delimiter-concatenated conflict keys even though every component accepts arbitrary nonblank strings, including `:`.

Representative production path:

`PlayerChangeSet.create` -> `PlayerChangeSetValidator.validate` -> `TypedPlayerChangeRegistry.conflictKeys` -> `StatChange` codec conflict key.

StatChange conflict key remains:

`STAT:${subject.kindUid}:${subject.uid}:${statUid}`

`DomainRef` validity is only `kindUid.isNotBlank() && uid.isNotBlank()`, and the stat UID is likewise only required to be nonblank. Therefore the encoding is not injective.

### Minimal reproducer

Create two otherwise legal StatChange proposals with different change UIDs:

```
A:
  subject = DomainRef("A", "B:C")
  statUid = "D"

B:
  subject = DomainRef("A:B", "C")
  statUid = "D"
```

Both are distinct semantic targets:

```
("A",   "B:C", "D")
("A:B", "C",   "D")
```

but both conflict keys are:

```
STAT:A:B:C:D
```

Expected:
ACCEPT as two distinct typed targets.

Actual:
The second key collides in the validator's `semanticTargets` HashSet and the ChangeSet is rejected as `CONFLICTING_CHANGE_TARGET`.

This is the same representation class as the earlier asset defect, but remains present in `STAT`, `RESOURCE`, `SKILL`, `TECHNIQUE`, `INNATE`, `INVENTORY`, `EQUIPMENT`, `CONDITION`, and `RUNTIME` multi-component conflict keys. The exact same ambiguity can be constructed by moving a `:` boundary between adjacent legal UID components.

Why existing Hotfix3 tests miss it:

P17-HOTFIX3-01..08 target only AssetChange conflict identity. They do not adversarially test injectivity of the other typed conflict-key families. The full JVM suite therefore remains green while this counterexample exists.

Minimal correction scope (not implemented):

Phase 17 only. Replace raw delimiter concatenation for all multi-component typed conflict identities with an injective structured key or a common length-prefixed/escaped encoding. Add tuple-alias regressions for each affected family, ideally property-style over arbitrary legal strings.

## Hotfix2 finance / ledger

Result: PASS.

Adversarial cross-check confirmed:

- at most one FinancialTransferLedgerIntent per causal FinancialChange across the ChangeSet;
- two different ledgerIntentUid values representing the same causal FinancialChange reject;
- exact matching remains on fromAccountUid, toAccountUid, amountMinor, currencyUid and transactionTypeUid;
- term mismatch remains checked before duplicate-causal registration;
- mixed causal refs consume the FinancialChange for uniqueness;
- independent FinancialChanges with disjoint account targets may each have their own matching ledger;
- standalone ledger intents with empty causal refs remain legal;
- dangling causal refs reject;
- non-financial-only causal refs reject.

No new finance/ledger blocker was found.

## Codec robustness

Result: PASS fail-closed, with one non-blocking error-family observation.

Reviewed:

- unknown semantic fields;
- duplicate JSON keys, including escaped-equivalent names;
- quoted numerics;
- wrong scalar types;
- null/object/array in scalar positions;
- unsupported schema versions;
- malformed nested objects/arrays;
- public decode/validate surfaces;
- canonical encode/decode/fingerprint paths.

No path was found where malformed serialized input becomes a legal typed ChangeSet after silent semantic loss.

Some malformed nested array elements are still converted through `.jsonObject` outside localized error normalization and can throw a library `IllegalArgumentException` instead of `PlayerChangeSetStructuralException`. This remains fail-closed and does not bypass validation, canonical identity, or authority. It is recorded as NON-BLOCKING error-family consistency hardening.

## Immutability / aliasing / canonicalization / authority

- defensive copies remain in root and nested list-bearing structures;
- no caller-owned mutable-list aliasing blocker was found;
- canonical encode/decode remains deterministic for legal inputs;
- no fingerprint collision from lossy serialization was found;
- exact Long/fixed-scale numeric semantics remain intact;
- zero authoritative mutation remains true for construction, validate, encode, decode and fingerprint;
- no world-specific Core semantics were introduced;
- PlayerChangeSet remains proposal-only and non-authoritative.

## Exact CI / full JVM evidence

Exact workflow for the target SHA:

- run number: 366
- run ID: 31641781605
- head SHA: c20577678b319590be09df45a41d4050a74dc783
- conclusion: SUCCESS

Verified successful job steps include:

- Validate project
- Run JVM unit tests
- Build signed ALPHA APK
- Prepare release files
- Upload Actions artifact
- Update existing GitHub Release assets
- overall job success

The workflow command is the repository's full `:app:testDebugUnitTest` suite. A second local checkout/run was attempted from the audit environment but could not be performed because the container has no DNS/network access to GitHub; this limitation was not treated as evidence either way. Exact target CI is therefore the execution evidence, while the adversarial verdict is based independently on production-code counterexamples.

## Gate summary

- RUNTIME CHANGED AFTER TARGET: NO at audit start
- HOTFIX3 ASSET IDENTITY: PASS
- HOTFIX2 FINANCE/LEDGER: PASS
- STRICT SERIALIZATION: PASS fail-closed
- MALFORMED NESTED ERROR FAMILY: NON-BLOCKING OBSERVATION
- IMMUTABILITY / ALIASING: PASS
- CANONICALIZATION / FINGERPRINT: PASS
- NUMERIC SAFETY: PASS
- ZERO AUTHORITATIVE MUTATION: PASS
- WORLD-AGNOSTIC BOUNDARY: PASS
- PROPOSAL-ONLY BOUNDARY: PASS
- DUPLICATE / CONFLICT HANDLING: FAIL due non-injective typed conflict keys outside AssetChange
- PHASE 3-16 REGRESSION: PASS by exact CI and code review
- FULL JVM: PASS in exact CI #366

NEW BLOCKERS:

- P17-ROBUST-TYPED-CONFLICT-KEY-ALIAS-02 — RELEASE BLOCKER / HIGH

FINAL CHAT-5 VERDICT: FAIL

Phase 17 is not globally ACCEPTED. Phase 18 remains BLOCKED until CHAT-2, CHAT-3 and CHAT-5 all independently PASS the same later runtime candidate after release blockers are resolved.
