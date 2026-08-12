# PHASE 17 INTEGRITY REVALIDATION — HOTFIX2

ROLE: CHAT-3 / independent Integrity / Contract Boundary Auditor

Repository: `piotreksmaga-art/rpg-os-android`

VALIDATED RUNTIME SHA: `e3a3fd7cf26e6e9a7cebd5dcbe17bc6ce4c9c4d5`

Exact CI: GitHub Actions `#361`, run ID `31639002452`, head SHA `e3a3fd7cf26e6e9a7cebd5dcbe17bc6ce4c9c4d5`, conclusion `SUCCESS`.

Allowed write scope: this report only. No production/test/schema/workflow/runtime changes. Phase 18 not started.

# FINAL VERDICT

`PHASE 17 INTEGRITY REVALIDATION: PASS`

No Phase-17 structural/integrity release blocker was found in the exact target runtime.

## 1. Repository-first / runtime pinning

Fresh master at the final pre-report check was:

`2d7dec550626883dcc8773241ca8526ad6c7633b`

Inspection shows that commits after the runtime candidate are report-only audit commits. No newer Phase-17 production/test runtime exists after:

`e3a3fd7cf26e6e9a7cebd5dcbe17bc6ce4c9c4d5`

The audited runtime therefore remains exactly the requested SHA.

Diff from the prior Phase-17 hotfix runtime `4ec5ee2bbdcd445beb067097c37bc095e3007540` changes production only in `PlayerChangeSetCodec.kt` by adding the causal FinancialChange uniqueness guard, plus the dedicated Hotfix2 test and report-only documents. No accepted Phase 3-16 authority is modified.

## 2. Hotfix2 structural gate — PASS

Previous integrity/semantic defect: one `FinancialChange.changeUid` could be represented by multiple distinct `FinancialTransferLedgerIntent` objects, potentially describing duplicate future ledger appends.

The production validator now maintains:

`representedFinancialChangeUids: HashSet<String>`

for the entire ChangeSet.

Per ledger intent, the validator performs the following sequence:

1. validates unique/nonblank `ledgerIntentUid`;
2. validates supported ledger kind;
3. rejects blank/dangling causal refs before dereference;
4. validates ledger financial payload structure;
5. resolves each causal UID through `changesByUid`;
6. for causal FinancialChange, validates exact financial-term equality;
7. records FinancialChange UIDs in a per-intent `LinkedHashSet`;
8. requires a financial cause for non-standalone ledgers;
9. registers each financial cause in the global represented set;
10. rejects reuse across another ledger with `DUPLICATE_FINANCIAL_LEDGER_CAUSAL_CHANGE`.

This is a structural validation boundary inside `PlayerChangeSetValidator`, executed both by `PlayerChangeSet.create(...)` and after `PlayerChangeSetCodec.decode(...)`.

Result: one FinancialChange cannot structurally authorize two causal financial ledger proposals in one valid PlayerChangeSet.

## 3. Exact financial term consistency — PASS

For every causal FinancialChange, exact equality remains required on:

- `fromAccountUid`
- `toAccountUid`
- `amountMinor`
- `currencyUid`
- `transactionTypeUid`

Any mismatch rejects with:

`FINANCIAL_LEDGER_TERMS_MISMATCH`

Important integrity ordering: term comparison runs before the FinancialChange UID is inserted into `representedFinancialChangeUids`. Therefore a malformed/mismatching second ledger is not masked as a duplicate-cause error.

Dedicated Hotfix2 regression verifies this ordering.

## 4. Multiple causal refs — PASS

### Mixed financial + non-financial causality

A ledger containing `[CH-FIN, CH-NONFIN]` can be structurally valid when all refs exist and CH-FIN exactly matches the ledger financial terms. CH-FIN is still registered as represented, so a later ledger referring to CH-FIN is rejected as a duplicate financial causal representation.

### Several financial causes in one ledger

Every causal FinancialChange would have to match the same ledger payload exactly. Existing change conflict keys are evaluated before ledger validation and reject overlapping financial-account semantic targets where applicable. Hotfix2 does not weaken that earlier invariant.

### Duplicate same causal UID within one ledger

The per-ledger `LinkedHashSet` is used only for representation accounting. The original `causalChangeUids` ordered list is not rewritten or canonicalized away. Therefore there is no hidden mutation/loss of serialized identity.

## 5. No false global ledger conflict — PASS

Uniqueness is keyed by `FinancialChange.changeUid`, not by the entire ChangeSet.

Two independent financial changes with disjoint account targets can each have one matching ledger:

- CH-FIN-1 A -> B / LED-1 -> CH-FIN-1
- CH-FIN-2 C -> D / LED-2 -> CH-FIN-2

The dedicated fixture accepts this case, proving Hotfix2 is not an accidental "one ledger per ChangeSet" restriction.

## 6. Standalone / non-financial / dangling causal behavior — PASS

Standalone ledger (`causalChangeUids=[]`) remains explicitly legal and does not consume a FinancialChange UID.

Non-empty causal set containing no FinancialChange rejects with:

`FINANCIAL_LEDGER_CAUSAL_CHANGE_REQUIRED`

Dangling causal UID rejects earlier with:

`INVALID_LEDGER_INTENT`

These are deterministic, distinct structural failures.

## 7. Asset identity integrity regression — PASS

`AssetChange` continues to carry the accepted canonical generic asset identity:

`OwnedAssetRef(assetKindUid, assetUid)`

The ASSET codec:

- encodes the complete nested asset ref;
- decodes both identity dimensions;
- validates both as nonblank;
- uses conflict key `ASSET:<assetKindUid>:<assetUid>`.

Thus:

`PROPERTY/A-1 != BUSINESS/A-1`

Same kind + same UID remains one canonical target; changing asset kind changes canonical JSON/fingerprint. No `assetUid`-only loss path is present.

## 8. Immutable collections / aliasing — PASS

`PlayerChangeSet` constructor is private and the public factory validates before returning.

Caller-provided collections are copied with:

`Collections.unmodifiableList(ArrayList(values))`

This applies to:

- root `changes`
- `eventIntents`
- `ledgerIntents`
- `preconditions`
- `warnings`
- event `targetRefs`
- event `causalChangeUids`
- ledger `causalChangeUids`
- DevelopmentProject `evidenceRefs`.

Hotfix2 introduces only validator-local sets and does not expose mutable aliases.

## 9. Strict codec / decode path integrity — PASS

`PlayerChangeSetCodec.decode(serialized)` remains fail-closed:

- duplicate object keys rejected before parser collapse;
- root allowed-key enforcement;
- typed change wrapper allowed keys;
- typed payload allowed-key enforcement inside `TypedPlayerChangeCodec.decode`;
- nested actor/provenance/ref/owner/asset/event/ledger/precondition/warning allowed-key enforcement;
- strict JSON String scalar handling;
- strict Int/Long scalar handling;
- quoted numerics rejected;
- unsupported ChangeSet schema rejected;
- unknown change/event/ledger kinds rejected;
- payload type mismatch rejected;
- malformed nested refs rejected;
- final `PlayerChangeSetValidator.validate()` is run after decode.

`TypedPlayerChangeRegistry.codec(kindUid)` is `internal`, and `TypedPlayerChangeCodec.decode(JsonObject)` self-applies `pcsOnlyKeys(allowedKeys)` before known-field extraction. The prior public raw-codec bypass class from Phase 16 is not present here.

No alternate public JsonObject -> typed payload route bypassing allowed-key validation was found.

## 10. Conflict / duplicate invariants — PASS

The validator continues to reject:

- duplicate `changeUid`;
- duplicate event UID;
- duplicate ledger UID;
- conflicting stat/resource/skill/technique/innate/inventory/equipment/condition/runtime/project semantic targets;
- same canonical Asset target;
- conflicting ownership target/record identity;
- conflicting financial account targets;
- dangling event causal refs;
- dangling ledger causal refs;
- dangling warning refs;
- duplicate FinancialChange -> ledger causal representation.

Hotfix2 adds the missing financial relation guard without removing previous conflict gates.

## 11. Canonical serialization / identity / fingerprint — PASS

`PlayerChangeSetCodec.encode` validates before canonical JSON construction.

`fingerprint` computes SHA-256 over the validated canonical encoding.

`PlayerChangeSetIdentity.compare` uses `(campaignUid, changeSetUid)` as the stable identity scope and fingerprint equality for exact immutable semantic equality; conflicting immutable content throws `CHANGESET_IDENTITY_CONFLICT`.

Hotfix2 preserves list order and does not mutate/canonicalize input lists. Illegal duplicate financial causal representation is rejected before a legal canonical fingerprint can bless a double-ledger proposal.

No lossy canonicalization or fingerprint-before-validation path was found.

## 12. Numeric integrity — PASS

Phase-17 proposal values remain exact:

- finance `amountMinor: Long`;
- deltas use `ExactLongDelta`;
- `ExactLongDelta.plus` uses `Math.addExact`;
- `ExactLongDelta.between` uses `Math.subtractExact`;
- zero delta is rejected;
- finance amount must be positive;
- strict JSON numeric scalar handling rejects quoted numerics and out-of-range values;
- Ownership uses accepted fixed-scale `OwnershipShare` rather than Float/Double.

No Float/Double proposal authority was introduced by Hotfix2.

## 13. Zero authoritative mutation — PASS

Phase-17 model/codec/validator code has no DAO, SQLite writer, repository mutation, StatePatch bridge, apply/commit/execute/save/persist hook, ledger writer, Ownership writer, Inventory writer, Asset writer, DevelopmentProject writer or event persistence path.

Contract operations remain:

- construct
- validate
- encode
- decode
- fingerprint
- identity compare.

Hotfix2 regression executes validate -> encode -> decode -> fingerprint around an SQLite authority fixture and verifies the authoritative row is unchanged.

## 14. Proposal-only / Phase-18 negative boundary — PASS

PlayerChangeSet remains a transient proposal-only contract.

No Phase-18 PlayerDomainEngine, WorldRuleProvider, ProgressionEngine, TurnTransaction or execution runtime was introduced by Hotfix2.

No ChangeSet persistence/inbox/outbox/queue exists.

## 15. Test integrity review — PASS

`PlayerChangeSetReleaseBlockerHotfix2Test` uses production paths and contains real assertions for P17-HOTFIX2-01..12:

1. same FinancialChange across two ledger IDs -> duplicate causal reject;
2. single matching ledger -> accept;
3. two independent FinancialChanges + separate ledgers -> accept;
4. standalone ledger -> accept;
5. mixed causal refs consume the financial cause;
6. earlier financial conflict semantics remain fail-closed;
7. financial term mismatch wins before duplicate guard;
8. non-financial-only causal refs -> reject;
9. dangling causal ref -> reject;
10. deterministic encode/decode/encode;
11. deterministic fingerprint;
12. zero authoritative DB mutation.

Earlier `PlayerChangeSetReleaseBlockerHotfixTest` and `PlayerChangeSetContractTest` remain part of the full JVM suite and continue to cover asset identity, five-field finance/ledger matching, defensive copies, strict serialization, typed conflicts, numeric exactness and Phase 3-16 regression.

## 16. Exact CI / full JVM — PASS

Verified exact GitHub Actions run:

- run number: `361`
- run ID: `31639002452`
- workflow: `Build & Release RPG OS ALPHA`
- head SHA: `e3a3fd7cf26e6e9a7cebd5dcbe17bc6ce4c9c4d5`
- conclusion: `SUCCESS`

Successful steps include:

- Validate project
- Run JVM unit tests (`:app:testDebugUnitTest`)
- Build signed ALPHA APK
- Prepare release files
- Upload Actions artifact
- Update existing GitHub Release assets
- Complete job

CI is execution evidence, not the basis of the integrity verdict. The verdict is based on independent inspection of the exact production model/validator/codec and the test assertions.

## 17. Phase 3-16 regression — PASS

Hotfix2 production delta is six lines in `PlayerChangeSetCodec.kt`; it does not modify accepted Phase 3-16 schema/store/authority code.

The exact CI full JVM suite passed after the change. No Phase 3-16 authority regression was found in the inspected scope.

## 18. Gate summary

```text
ROLE: CHAT-3
VALIDATED RUNTIME SHA: e3a3fd7cf26e6e9a7cebd5dcbe17bc6ce4c9c4d5

FINANCE/LEDGER CAUSAL UNIQUENESS HOTFIX2: PASS
EXACT FINANCIAL TERM CONSISTENCY: PASS
MULTIPLE CAUSAL REFS: PASS
NO FALSE GLOBAL LEDGER CONFLICT: PASS
STANDALONE LEDGER: PASS
NON-FINANCIAL CAUSAL REJECTION: PASS
DANGLING CAUSAL REJECTION: PASS
ASSET IDENTITY: PASS
IMMUTABILITY / ALIASING: PASS
STRICT CODEC / DECODE PATHS: PASS
CONFLICT / DUPLICATE INVARIANTS: PASS
CANONICAL SERIALIZATION / FINGERPRINT: PASS
NUMERIC BOUNDARIES: PASS
ZERO AUTHORITATIVE MUTATION: PASS
PHASE 3-16 REGRESSION: PASS
FULL JVM: PASS
EXACT CI: PASS

NEW BLOCKERS: NONE
```

# PHASE 17 INTEGRITY REVALIDATION: PASS

This report does not mark Phase 17 globally ACCEPTED. Phase 18 remains blocked until independent CHAT-2 + CHAT-3 + CHAT-5 PASS all refer to this exact runtime SHA.
