# PHASE 17 — HOTFIX3 FINAL ADVERSARIAL / ROBUSTNESS REVALIDATION

ROLE: CHAT-5 — Adversarial / Robustness Auditor

VALIDATED RUNTIME SHA: `c20577678b319590be09df45a41d4050a74dc783`

Repository: `piotreksmaga-art/rpg-os-android`

This is a REPORT-ONLY audit. No production, test, schema, migration, workflow, runtime, or persistence code was modified.

## Repository-first

FRESH MASTER at final pre-report verification: `5949c3bf8f0a5d2e267d7bd332e62ebaabb31138`.

Target `c20577678b319590be09df45a41d4050a74dc783` exists and is the merge-base/ancestor of master.

Commits after target: 3. The compare contains only added files under `docs/audits/`:

- `docs/audits/WORK-20260812-P17_CHAT3_FINAL_INTEGRITY_REVALIDATION_HOTFIX3_C205776.md`
- `docs/audits/WORK-20260812-P17_CHAT5_FINAL_HOTFIX3_ADVERSARIAL_REVALIDATION_C205776.md`
- `docs/audits/WORK-20260812-P17_PLAYERCHANGESET_SEMANTIC_REVALIDATION_C205776.md`

No production or test Phase-17 change exists after the target.

RUNTIME CHANGED AFTER TARGET: NO

A fresh local clone was attempted, but the audit container could not resolve `github.com`. Repository/history/code/CI verification therefore used the connected GitHub repository API at exact refs. No local Gradle execution is claimed.

Earlier audit reports were read only after independently establishing the counterexample below; prior PASS/FAIL conclusions were not used as proof.

## Primary target — canonical asset conflict identity

### AssetChange helper itself

`AssetChange` is routed through `assetConflictKey(OwnedAssetRef)`.

The helper has two disjoint representation spaces:

- `ASSET:<kind>:<uid>` only when `assetUid` contains no `:`;
- `ASSET|<kind.length>:<kind>|<uid.length>:<uid>` when `assetUid` contains `:`.

For the simple branch, the final `:` delimiter is unambiguous because the UID is colon-free. For the encoded branch, explicit component lengths preserve boundaries. The `ASSET:` and `ASSET|` prefixes are disjoint.

Adversarial reasoning covered `:`, `|`, `\\`, Unicode, whitespace, multiple delimiters, digits adjacent to length prefixes, encoded-looking strings, delimiters in kind/uid/both, and arbitrarily long UID content. No collision was found for the `AssetChange` helper itself, and identical tuples still produce identical keys.

### RELEASE BLOCKER: OWNED_ASSET conflict identity remains non-injective

ID: `P17-ROBUST-ASSET-CONFLICT-KEY-ALIAS-02`

Severity: RELEASE BLOCKER / HIGH

Production path:

`PlayerChangeSet.create` -> `PlayerChangeSetValidator.validate` -> `TypedPlayerChangeRegistry.conflictKeys` -> `PlayerChangeKinds.OWNERSHIP`

`OwnershipChange` still uses the raw composite conflict key:

`OWNED_ASSET:${asset.assetKindUid}:${asset.assetUid}`

Both tuple components are legal whenever nonblank; `:` is not forbidden.

Minimal reproducer:

- A = `OwnedAssetRef("KIND:A", "B")`
- B = `OwnedAssetRef("KIND", "A:B")`
- A != B
- both flatten to `OWNED_ASSET:KIND:A:B`

Construct two otherwise legal `OwnershipChange` values with distinct `changeUid` and distinct `ownershipRecordUid`, using A and B respectively. The validator inserts their conflict keys into the same `HashSet<String>`.

Expected: both independent ownership proposals are accepted as distinct asset targets.

Actual: the second proposal is rejected with `CONFLICTING_CHANGE_TARGET` because two different canonical asset tuples alias to one conflict identity.

Why existing tests miss it: `PlayerChangeSetReleaseBlockerHotfix3Test` adversarially exercises `AssetChange` keys, but it does not run the same delimiter-boundary injectivity probes through `OwnershipChange` / `OWNED_ASSET`.

This directly violates the required canonical identity invariant for `OwnedAssetRef(assetKindUid, assetUid)` and is release-blocking.

### Wider conflict-key surface

The same raw delimiter-concatenation class remains in other multi-component typed conflict targets, including STAT, RESOURCE, SKILL, TECHNIQUE, INNATE, INVENTORY, EQUIPMENT, CONDITION, and RUNTIME. Example:

- `(subjectKind="PLAYER:A", subjectUid="B", statUid="C")`
- `(subjectKind="PLAYER", subjectUid="A:B", statUid="C")`

Both flatten to `STAT:PLAYER:A:B:C` despite being distinct semantic targets. This is recorded as additional scope of the same structural conflict-identity defect class; no fix was implemented.

## Asset losslessness

Model and codec carry both `assetKindUid` and `assetUid`. Canonical JSON encodes both, decode reconstructs both, and legal single-change encode -> decode -> encode preserves them. Fingerprint is computed from canonical encoding, so changing either component changes serialized semantic content.

However, conflict detection is not lossless over the canonical asset tuple on the `OWNED_ASSET` path: delimiter flattening destroys tuple-boundary identity and maps distinct namespaces to the same target string.

ASSET LOSSLESSNESS: FAIL

## Financial / ledger Hotfix2

Independent production review and adversarial reasoning found no new release blocker.

Verified behavior:

- the same causal FinancialChange represented by multiple ledger intents is rejected;
- exact match is enforced for `fromAccountUid`, `toAccountUid`, `amountMinor`, `currencyUid`, and `transactionTypeUid`;
- term mismatch is checked before duplicate-cause registration;
- mixed/multiple causal refs remain fail-closed where required;
- independent FinancialChanges can be represented independently when their proposal conflicts do not already invalidate the ChangeSet;
- standalone ledger with an empty causal list remains allowed;
- dangling causal UID rejects;
- non-financial-only causal UID rejects;
- one repeated causal UID inside a single ledger intent is locally set-deduplicated for uniqueness accounting, but this does not create a second ledger posting or validation bypass; recorded as non-blocking semantics hardening rather than a release blocker.

FINANCIAL/LEDGER: PASS

## Codec / structural adversarial

Reviewed strict decode paths for unknown fields, duplicate keys, escaped-equivalent duplicate key names, quoted numerics, wrong scalar types, null/object/array substituted for scalars, malformed nested objects/arrays, unsupported schema versions, deep/malformed nesting, canonical legal round-trip, and fingerprint path.

The duplicate-key pre-scan compares decoded JSON key content, so escaped-equivalent duplicate names are rejected as duplicate object keys.

Strict scalar readers reject quoted numerics and wrong JSON scalar types. Unknown fields and unsupported schema versions reject. Legal canonical inputs retain their typed semantics through decode/re-encode.

Confirmed non-blocking observation: some malformed nested shapes reach `.jsonObject` / `.jsonArray` conversion and can surface library `IllegalArgumentException` rather than `PlayerChangeSetStructuralException`. They still fail closed; no malformed payload was found that bypasses validation, is accepted with altered semantics, or decodes lossily into a legal ChangeSet.

CODEC ROBUSTNESS: PASS

## Immutability / aliasing

`PlayerChangeSet` root collections are copied into `Collections.unmodifiableList(ArrayList(values))`. Nested list-bearing objects do the same for event target refs, event causal refs, ledger causal refs, and development-project evidence refs. Caller-owned mutable lists therefore cannot mutate an already-created proposal through list aliasing. Decode-created structures flow through the same constructors.

IMMUTABILITY: PASS

## Other required gates

WORLD-AGNOSTIC: PASS

PROPOSAL-ONLY: PASS

NO GENERIC MUTATION: PASS

NO AUTHORITATIVE WRITER: PASS

NO STATEPATCH AUTHORITY: PASS

NO PERSISTENCE BYPASS: PASS

TYPED DOMAIN CHANGES: PASS

STABLE UID REFERENCES: PASS

DUPLICATE/CONFLICT SEMANTICS: FAIL — due non-injective composite conflict identities described above.

CANONICALIZATION: PASS for legal accepted ChangeSets.

FINGERPRINT: PASS

NUMERIC SAFETY: PASS — exact `Long` representation is preserved; delta arithmetic uses `Math.addExact` / `Math.subtractExact`, and overflow fails closed.

ZERO AUTHORITATIVE DB MUTATION: PASS

PlayerChangeSet remains a typed transient proposal before future validation -> TurnTransaction -> COMMIT authority. No Phase-18 authoritative writer was found in the Phase-17 surface.

## Phase 3–16 regression

No regression was found by target code review and the exact target full JVM execution. Representative Phase-17 regression tests remain in the suite, and the exact target workflow completed the full JVM suite successfully.

PHASE 3–16 REGRESSION: PASS

## Full JVM and exact CI

Independent local rerun was not possible because the local audit container could not resolve GitHub for a fresh repository clone. This limitation is not treated as a pass by itself.

Exact GitHub Actions execution for the target SHA was verified directly:

- workflow: `Build & Release RPG OS ALPHA`
- run number: `366`
- run ID: `31641781605`
- head SHA: `c20577678b319590be09df45a41d4050a74dc783`
- conclusion: `SUCCESS`
- job: `build` / job ID `94265783003`
- JVM command: `gradle --no-daemon :app:testDebugUnitTest --stacktrace`
- JVM result: `BUILD SUCCESSFUL`; task `:app:testDebugUnitTest` executed
- release command: `gradle --no-daemon :app:assembleRelease --stacktrace`
- signed release gates including `validateSigningRelease`, packaging and `assembleRelease` succeeded
- artifact upload and existing GitHub Release asset update succeeded

FULL JVM: PASS — exact target CI execution verified; no second local rerun is claimed.

EXACT CI: PASS

## Gate summary

ROLE: CHAT-5

VALIDATED RUNTIME SHA: `c20577678b319590be09df45a41d4050a74dc783`

FRESH MASTER: `5949c3bf8f0a5d2e267d7bd332e62ebaabb31138` at final pre-report verification

RUNTIME CHANGED AFTER TARGET: NO

ASSET CONFLICT IDENTITY: FAIL

ASSET LOSSLESSNESS: FAIL

FINANCIAL/LEDGER: PASS

CODEC ROBUSTNESS: PASS

IMMUTABILITY: PASS

WORLD-AGNOSTIC: PASS

PROPOSAL-ONLY: PASS

FINGERPRINT: PASS

NUMERIC SAFETY: PASS

ZERO AUTHORITATIVE MUTATION: PASS

PHASE 3–16 REGRESSION: PASS

FULL JVM: PASS

EXACT CI: PASS

NON-BLOCKING OBSERVATIONS:

1. Some malformed nested JSON shapes can surface `IllegalArgumentException` instead of `PlayerChangeSetStructuralException`; all inspected paths remain fail-closed with no accepted semantic bypass.
2. Repeating the same financial causal UID inside one ledger intent is locally de-duplicated for cross-intent uniqueness accounting; no duplicate ledger posting or authority bypass was found from this behavior.
3. A second independent local full-JVM rerun could not be performed because the audit container lacked DNS access to GitHub; exact target CI provides direct execution evidence instead.

NEW BLOCKERS:

- `P17-ROBUST-ASSET-CONFLICT-KEY-ALIAS-02` — RELEASE BLOCKER / HIGH — `OwnershipChange` canonical `OwnedAssetRef` identity is still flattened non-injectively by `OWNED_ASSET:<kind>:<uid>`. Wider raw composite conflict-key families share the same defect class.

REPORT PATH:

`docs/audits/WORK-20260812-P17_CHAT5_NEWCHAT_FINAL_ADVERSARIAL_REVALIDATION_C205776.md`

REPORT COMMIT SHA:

Filled by the repository commit created for this report.

FINAL CHAT-5 VERDICT:

FAIL

Phase 17 is not marked globally ACCEPTED. Phase 18 remains BLOCKED pending CHAT-2 PASS + CHAT-3 PASS + CHAT-5 PASS for the exact same runtime candidate.