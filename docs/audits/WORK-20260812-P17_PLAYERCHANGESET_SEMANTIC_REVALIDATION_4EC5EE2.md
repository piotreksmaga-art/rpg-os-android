# WORK-20260812 — Phase 17 PlayerChangeSet Semantic Revalidation

Role: CHAT-2 / independent semantic auditor
Validated runtime SHA: `4ec5ee2bbdcd445beb067097c37bc095e3007540`
Fresh master at validation start: `4ec5ee2bbdcd445beb067097c37bc095e3007540`
Exact CI: GitHub Actions #357, run ID `31637247305`, exact head SHA `4ec5ee2bbdcd445beb067097c37bc095e3007540`, SUCCESS.

## Final verdict

`PHASE 17 SEMANTIC REVALIDATION: FAIL`

This audit is report-only. No production/test/runtime/schema/migration changes were made. Phase 18 remains blocked.

## Hotfix A — asset identity: PASS

`AssetChange` now carries the accepted `OwnedAssetRef(assetKindUid, assetUid)` rather than a bare assetUid. Validation checks both stable identity components, canonical JSON encodes both through the shared owned-asset representation, decode restores both, and the conflict key is `ASSET:<assetKindUid>:<assetUid>`.

Consequences verified from the exact hotfix path and tests:
- PROPERTY/A-1 and BUSINESS/A-1 remain distinct;
- same kind + same uid is the same asset target and conflicts fail closed;
- different kind + same uid does not false-conflict;
- round-trip preserves both identity dimensions;
- asset-kind changes alter canonical representation/fingerprint.

## Hotfix B — FinancialChange / ledger term consistency: PASS for the original blocker

The validator now resolves causalChangeUids through a stable `changesByUid` map. For each causal FinancialChange, the ledger payload must exactly match:
- fromAccountUid
- toAccountUid
- amountMinor
- currencyUid
- transactionTypeUid

Mismatch is rejected with `FINANCIAL_LEDGER_TERMS_MISMATCH`. A causal list containing no FinancialChange is rejected with `FINANCIAL_LEDGER_CAUSAL_CHANGE_REQUIRED`; dangling causal refs remain rejected. Standalone ledger proposals with an empty causal list remain legal by explicit compatibility contract.

The exact hotfix tests independently cover all five original mismatch fields, non-financial causal refs, dangling refs, canonical round-trip/fingerprint, zero mutation and Phase 3–16 regression.

## New release blocker

### P17-SEM-FIN-LEDGER-DUP-01 — one FinancialChange may cause multiple duplicate ledger appends

The new validator enforces term equality per ledger intent but does not enforce uniqueness of the FinancialChange -> FinancialTransferLedgerIntent semantic relation.

Minimal reproducer:

1. Create one `FinancialChange`:
   - changeUid = `CH-FIN`
   - A -> B
   - amountMinor = 100
   - currency = CUR
   - transactionType = TRANSFER
2. Create two distinct ledger intents:
   - ledgerIntentUid = `LED-1`
   - ledgerIntentUid = `LED-2`
   - both causalChangeUids = [`CH-FIN`]
   - both payloads exactly A -> B / 100 CUR / TRANSFER
3. Construct one PlayerChangeSet containing the single FinancialChange and both ledger intents.

Expected:
- deterministic rejection as duplicate semantic financial/ledger effect, or an explicit one-to-many contract with semantics proving that multiple entries do not duplicate the same proposed financial effect.

Actual:
- ledger UID uniqueness passes because `LED-1 != LED-2`;
- each causal UID resolves to `CH-FIN`;
- each ledger independently passes exact financial-term matching;
- no semantic key tracks that `CH-FIN` has already been represented by a FinancialTransfer ledger intent;
- therefore the whole ChangeSet is accepted and receives a canonical fingerprint while proposing two ledger appends causally attributed to one FinancialChange.

Why this is a release blocker:
- PlayerChangeSet is one future atomic proposal unit;
- event/ledger intents are proposed future append effects;
- Phase-17 contract requires obvious duplicate/conflicting typed changes to fail closed;
- duplicate IDs are not the same invariant as duplicate semantic effects;
- allowing two distinct ledgerIntentUid values to represent the same causal FinancialChange can produce a future double ledger append from one financial proposal.

Minimal Phase-17-only correction scope:
- enforce at most one `FINANCIAL_TRANSFER` ledger intent per causal FinancialChange, unless the contract explicitly introduces a typed split/allocation model that proves multiple ledger entries are intentional and non-duplicative;
- add focused tests for duplicate identical ledger intents with different IDs pointing at the same FinancialChange, plus several independent FinancialChanges each with its own unique matching ledger intent.

No PlayerDomainEngine, TurnTransaction, persistence, Phase-18 runtime or DB change is required for this correction.

## Full semantic gate results

- Asset identity hotfix: PASS
- Original finance/ledger mismatch blocker: PASS
- Immutability / defensive copies: PASS
- World-agnostic Core: PASS
- Proposal-only / non-authoritative boundary: PASS
- Typed domain families: PASS except duplicate financial-ledger relation above
- Strict serialization / unknown fields / duplicate keys / strict strings / strict numerics / unsupported versions: PASS
- Canonicalization / identity / fingerprint: PASS for legal inputs; FAIL overall because the duplicate semantic effect is treated as a legal canonical ChangeSet
- Duplicate/conflict handling: FAIL due P17-SEM-FIN-LEDGER-DUP-01
- Exact Long / overflow / >IEEE-754 / OwnershipShare fixed scale: PASS
- Zero authoritative mutation: PASS
- Phase 3–16 regression: PASS
- Phase-18 negative boundary: PASS

## CI evidence

GitHub Actions #357 / run `31637247305` / exact head SHA `4ec5ee2bbdcd445beb067097c37bc095e3007540` / SUCCESS.

The exact build job completed successfully:
- Validate project
- Run JVM unit tests
- Build signed ALPHA APK
- Prepare release files
- Upload Actions artifact
- Update existing GitHub Release assets
- overall workflow success

Green CI is not used as proof of semantic PASS; the release blocker above is visible in the exact production validator logic despite the green suite.

## Status

`PHASE 17 = NOT YET ACCEPTED`

`PHASE 18 = BLOCKED`
