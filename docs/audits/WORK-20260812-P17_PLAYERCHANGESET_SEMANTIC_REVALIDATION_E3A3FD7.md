# CHAT-2 — Phase 17 PlayerChangeSet Final Semantic Revalidation

Role: independent read-only semantic validator

Validated runtime SHA: `e3a3fd7cf26e6e9a7cebd5dcbe17bc6ce4c9c4d5`
Fresh master at validation start: `e3a3fd7cf26e6e9a7cebd5dcbe17bc6ce4c9c4d5`
Runtime changed after target: NO

## Verdict

`PHASE 17 SEMANTIC REVALIDATION: PASS`

No Phase-17 semantic release blocker was found in the exact target runtime.

## Repository / ancestry

The target is the current production/test Phase-17 runtime. Previous runtime `4ec5ee2bbdcd445beb067097c37bc095e3007540`, earlier `1df30948eb846e7530fcbbb52d56b1b09053d9b4`, and prior CHAT-2/3/5 report-only commits remain in history. No newer production/test runtime existed at validation start.

## Hotfix2: duplicate FinancialChange -> ledger representation

Production validator now tracks `representedFinancialChangeUids` across all ledger intents. For each FINANCIAL_TRANSFER ledger intent it resolves causal change UIDs, validates exact financial terms first, collects all causal FinancialChange UIDs, and rejects a FinancialChange already represented by an earlier ledger with `DUPLICATE_FINANCIAL_LEDGER_CAUSAL_CHANGE`.

Verified semantics:

- one FinancialChange + one matching causal ledger: ACCEPT;
- same FinancialChange represented by two different ledgerIntentUid values: REJECT with `DUPLICATE_FINANCIAL_LEDGER_CAUSAL_CHANGE`;
- mixed causal list `[CH-FIN, CH-NONFIN]` consumes `CH-FIN`, so a later ledger referencing `CH-FIN` is rejected as duplicate;
- term mismatch is checked before duplicate registration and remains `FINANCIAL_LEDGER_TERMS_MISMATCH`;
- causal refs containing only non-financial changes reject with `FINANCIAL_LEDGER_CAUSAL_CHANGE_REQUIRED`;
- dangling causal refs reject with `INVALID_LEDGER_INTENT`;
- standalone financial ledger with `causalChangeUids=[]` remains legal;
- uniqueness is per FinancialChange UID, not global per ChangeSet: two independent FinancialChanges with non-conflicting account targets can each have their own matching ledger intent.

The dedicated `PlayerChangeSetReleaseBlockerHotfix2Test` exercises P17-HOTFIX2-01..12 on the production validator path.

## Previous financial/ledger blocker regression

Exact matching still covers:

- `fromAccountUid`;
- `toAccountUid`;
- `amountMinor`;
- `currencyUid`;
- `transactionTypeUid`.

Any mismatch rejects with `FINANCIAL_LEDGER_TERMS_MISMATCH`. The duplicate-causal guard does not mask the mismatch because matching is evaluated first.

## Asset identity regression

`AssetChange` continues to carry the existing `OwnedAssetRef(assetKindUid, assetUid)`. Validation, codec, canonical JSON, round-trip and conflict keys preserve both dimensions. Therefore `PROPERTY/A-1` and `BUSINESS/A-1` are distinct semantic assets even when the textual asset UID is the same, while same kind + same UID remains one canonical target.

## Full PlayerChangeSet semantic contract

Revalidated with no new blocker:

- immutable proposal-only root and nested list defensive copies;
- typed, world-agnostic change families;
- no generic StatePatch/raw mutation primitive;
- no PlayerChangeSet persistence, DAO/DB writer, apply/commit/execute/save authority or Phase-18 runtime;
- events and ledger entries remain proposed intents, not committed facts;
- provenance and warnings are metadata only and do not fabricate authoritative history;
- stable UID/reference semantics;
- duplicate change/event/ledger IDs and obvious typed target conflicts fail closed;
- list order is semantic and influences canonical encoding/fingerprint;
- strict serialization rejects unknown fields, duplicate object keys, wrong String/numeric scalar types, quoted numerics, unsupported schema versions, unknown change kinds, payload-kind mismatch and malformed nested refs;
- canonical encode -> decode -> encode is deterministic;
- identity is `(campaignUid, changeSetUid)` plus exact canonical immutable content; conflict under same identity rejects;
- exact `Long` finance/progress arithmetic and fixed-scale `OwnershipShare` remain intact; no Float/Double proposal authority was introduced;
- construction, validation, encode, decode, fingerprint and identity comparison have no authoritative mutation path;
- Phase 3-16 boundaries remain unchanged.

## Adversarial semantic review

Reviewed combinations beyond the happy path: multiple ledger intents, mixed financial/non-financial causal refs, independent financial changes, reordered causal refs, duplicate IDs, asset kind aliases, numeric boundaries, malformed JSON, canonicalization and caller-owned mutable collections. No additional release-blocking semantic convergence or authority leak was found.

A repeated identical causal UID inside a single ledger causal list is redundant but does not create an additional ledger append or collapse distinct semantic input into another authoritative fact; it remains represented in canonical list ordering. This was not treated as a release blocker under the current Phase-17 contract.

## CI evidence

Exact workflow:

- GitHub Actions run number: `361`
- run ID: `31639002452`
- head SHA: `e3a3fd7cf26e6e9a7cebd5dcbe17bc6ce4c9c4d5`
- conclusion: `SUCCESS`

Successful steps include Validate project, full JVM unit tests, signed ALPHA APK, release preparation, Actions artifact upload, and update of existing release assets.

## Gate summary

- P17-SEM-FIN-LEDGER-DUP-01: PASS
- FINANCIAL TERM CONSISTENCY: PASS
- MULTIPLE CAUSAL REF SEMANTICS: PASS
- NO FALSE GLOBAL LEDGER CONFLICT: PASS
- STANDALONE LEDGER: PASS
- NON-FINANCIAL CAUSAL REJECTION: PASS
- DANGLING CAUSAL REJECTION: PASS
- ASSET IDENTITY: PASS
- IMMUTABILITY: PASS
- WORLD-AGNOSTIC: PASS
- PROPOSAL-ONLY: PASS
- NO GENERIC MUTATION: PASS
- NO AUTHORITY/PERSISTENCE: PASS
- TYPED DOMAIN CHANGES: PASS
- SERIALIZATION: PASS
- CANONICALIZATION/FINGERPRINT: PASS
- NUMERIC SAFETY: PASS
- ZERO AUTHORITATIVE MUTATION: PASS
- P17-HOTFIX2-01..12: PASS
- P17-HOTFIX-01..16: PASS
- PHASE 3-16 REGRESSION: PASS
- EXACT CI: PASS

NEW BLOCKERS: NONE

This report does not mark Phase 17 globally accepted. Phase 18 remains blocked until independent CHAT-2, CHAT-3 and CHAT-5 PASS all refer to this exact runtime SHA.