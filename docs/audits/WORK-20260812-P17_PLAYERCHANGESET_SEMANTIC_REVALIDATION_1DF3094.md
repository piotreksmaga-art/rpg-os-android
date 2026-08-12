# PHASE 17 — PlayerChangeSet Semantic Revalidation

Role: CHAT-2 / independent semantic revalidator
Repository: `piotreksmaga-art/rpg-os-android`
Exact runtime inspected: `1df30948eb846e7530fcbbb52d56b1b09053d9b4`
Fresh master at final re-check before report write: `1df30948eb846e7530fcbbb52d56b1b09053d9b4`
Verdict: **PHASE 17 SEMANTIC REVALIDATION: FAIL**

This is a report-only audit. No production/test runtime was modified and Phase 18 was not started.

## 1. Scope and evidence

Production files inspected:
- `app/src/main/java/com/rpgos/app/PlayerChangeSetModel.kt`
- `app/src/main/java/com/rpgos/app/PlayerChangeSetCodec.kt`

Tests inspected:
- `app/src/test/java/com/rpgos/app/PlayerChangeSetContractTest.kt`

Architecture/contracts cross-checked:
- MASTER / Roadmap / Parallel Work Coordination
- `docs/audits/WORK-20260810-068_PHASE17_PLAYERCHANGESET_ARCHITECTURE.md`
- accepted Phase-16 PlayerCommand boundary and final Phase-16 reports

Exact CI evidence:
- GitHub Actions `#353`
- run ID `31634593825`
- head SHA `1df30948eb846e7530fcbbb52d56b1b09053d9b4`
- conclusion `SUCCESS`
- build job completed Validate project, full JVM unit tests, signed ALPHA APK, release preparation, artifact upload and release asset update.

Independent local Gradle execution was attempted, but the audit container cannot resolve `github.com`, so the repository could not be cloned into that environment. This does not affect the semantic blocker below, which is directly reproducible from the exact production validation path.

## 2. Gate summary

- P17-SEM-01 proposal-only vs committed fact: PASS
- P17-SEM-02 no alternate authority: PASS
- P17-SEM-03 world-agnostic core: PASS
- P17-SEM-04 typed domain separation: PASS
- P17-SEM-05 no generic mutation primitive: PASS
- P17-SEM-06 finance/ownership/asset/inventory remain proposal-only: **FAIL because finance change and its causal ledger intent may contradict each other while both validate**
- P17-SEM-07 event/ledger intents remain proposed: PASS
- P17-SEM-08 provenance does not fabricate committed evidence: PASS
- P17-SEM-09 warnings non-authoritative: PASS
- P17-SEM-10 duplicate/conflicting changes fail closed: **FAIL for cross-surface FinancialChange ↔ FinancialTransferLedgerIntent contradiction**
- P17-SEM-11 order semantics deterministic: PASS
- P17-SEM-12 no already-committed-result primitive: PASS
- P17-SEM-13 stable UID/ref semantics: PASS
- P17-SEM-14 no Phase-18/19/20/22 implementation: PASS
- P17-SEM-15 Phase-16 PlayerCommand regression: PASS

Immutability: PASS
World-agnostic boundary: PASS
Proposal-only boundary: PASS
Typed change semantics: FAIL (finance/ledger cross-surface consistency)
Serialization losslessness: PASS
Canonicalization/identity: PASS
Numeric safety: PASS
Conflict/duplicate handling: FAIL
Zero authoritative mutation: PASS
Phase 3–16 regression: PASS
Full JVM: PASS via exact CI #353; local rerun unavailable due audit-container network/DNS restriction

## 3. Release blocker

### P17-SEM-FIN-LEDGER-01 — causal financial change and ledger intent can contradict

### Violated invariant

A single PlayerChangeSet is one atomic proposal unit. If a `PlayerLedgerIntent` declares a `causalChangeUid` pointing at a `FinancialChange`, the ledger proposal must not encode different financial terms from the change it claims to be caused by. Phase-17 structural validation is required to reject obvious internal contradictions.

The Phase-17 architecture explicitly requires:
- event/ledger causal references to point to changes in the same set;
- obvious duplicate/conflicting typed changes to be rejected;
- required changes + ledger entries + events to belong to one future atomic commit unit.

### Exact runtime path

`PlayerChangeSetValidator.validate()` in `PlayerChangeSetCodec.kt`:

1. records valid `changeUid`s;
2. validates each `PlayerLedgerIntent` UID/kind;
3. requires every `causalChangeUid` merely to exist in `changeUids`;
4. independently validates `FinancialTransferLedgerIntentPayload` terms;
5. never resolves the referenced causal change and never compares the ledger terms to a causal `FinancialChange`.

The existing `p17_20_proposedLedgerIntents` fixture uses matching values, but no invariant requires them to match.

### Minimal reproducer

Construct:

- change `CH-FIN`:
  - kind = `RPGOS-CHANGE:FINANCIAL_TRANSFER`
  - `FinancialChange(from=A, to=B, amountMinor=100, currency=CUR, transactionType=TRANSFER)`

- ledger intent `LED-1`:
  - kind = `RPGOS-LEDGER-INTENT:FINANCIAL_TRANSFER`
  - `causalChangeUids = ["CH-FIN"]`
  - `FinancialTransferLedgerIntentPayload(from=A, to=C, amountMinor=999, currency=CUR, transactionType=TRANSFER)`

Create one `PlayerChangeSet` containing both.

### Expected

Deterministic structural rejection because the ledger intent claims causal linkage to `CH-FIN` while proposing different destination and amount. This is an obvious internal contradiction inside one atomic proposal.

### Actual

`PlayerChangeSetValidator.validate()` accepts both independently:
- `CH-FIN` is a valid FinancialChange;
- `LED-1` references an existing change UID;
- both financial payloads individually satisfy `validateFinancialTerms()`;
- no semantic equality/compatibility check exists between them.

Therefore one canonical/fingerprintable PlayerChangeSet can simultaneously propose mutually inconsistent financial effects.

### Why this is Phase-17 scope

This is not authorization, balance availability, world-rule legality, or commit-time stale-state validation for Phase 18+. It is internal structural consistency of the ChangeSet itself. The contradiction is fully knowable from immutable data already inside Phase 17.

### Minimal correction scope

Phase 17 only. Choose one canonical representation rule and make it fail closed. Minimal options include:

1. when a financial ledger intent lists a causal `FinancialChange`, require exact immutable term equality for account refs, amount, currency and transaction type; or
2. remove the duplicate financial representation and make either the domain change or the ledger intent the sole canonical financial proposal path, with an explicit typed linkage contract where a companion intent is required.

No Phase-18 engine, database writer, transaction execution, or new authority is required.

## 4. Other independent findings

### Proposal-only / authority boundary

`PlayerChangeSet`, `PlayerDomainChange`, event intents and ledger intents are transient immutable proposal objects. No DAO/store/SQLite writer, StatePatch bridge, `apply`, `commit`, `execute`, `save` or persistence table is introduced by the Phase-17 runtime commit.

Financial changes are classified as `LEDGER_APPEND_INTENT`, not mutable balance assignments. Ownership uses typed Phase-12 refs/share rather than an `owned=true` boolean. Asset, inventory, equipment and DevelopmentProject remain typed proposal payloads rather than replacement authorities.

### World-agnostic boundary

No Naruto/Bleach-specific domain assumptions were found in the Phase-17 model or codec. The change vocabulary is generic: stat/resource/skill/technique/innate/inventory/equipment/financial/asset/ownership/condition/runtime/development-project.

### Immutability and aliasing

`PlayerChangeSet.create()` defensively copies root lists. Nested mutable list inputs are also copied for DevelopmentProject evidence refs, event target refs/causal refs, and ledger causal refs, then exposed through unmodifiable lists. Encode/decode rebuilds new immutable graphs.

### Strict serialization

Root and nested objects use allowed-key validation; duplicate object keys are rejected before parser collapse; string and numeric scalar readers are strict; quoted numerics are rejected; unsupported schema version is rejected; unknown change kinds and payload-kind mismatch fail closed; canonical encode/decode/encode order is deterministic.

### Identity / ordering

Fingerprint is SHA-256 over validated canonical JSON. Semantic list order is preserved rather than globally sorted; reordering changes changes the fingerprint. Same campaign + same changeSet UID + different canonical content produces identity conflict.

### Numeric safety

Proposal arithmetic uses `Long` and `ExactLongDelta` with `Math.addExact/subtractExact`; zero deltas are rejected. Finance preserves values beyond IEEE-754 exact integer range. Ownership reuses the exact fixed-scale `OwnershipShare` authority.

### Duplicate/reference handling

Duplicate `changeUid`, duplicate event/ledger IDs, same conflict-key targets, dangling event/ledger causal change UIDs and dangling warning change refs are rejected. The blocker is specifically the missing semantic consistency check after a causal finance reference resolves to an existing FinancialChange.

## 5. Final verdict

**PHASE 17 SEMANTIC REVALIDATION: FAIL**

Validated exact runtime: `1df30948eb846e7530fcbbb52d56b1b09053d9b4`

Phase 17 remains NOT YET ACCEPTED. Phase 18 remains BLOCKED pending a forward-only correction and fresh independent CHAT-2/3/5 revalidation of the resulting runtime SHA.
