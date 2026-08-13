# WORK-20260814 — PHASE 18 CHAT-3 FINAL INTEGRITY REVALIDATION

ROLE: CHAT-3 — Independent Integrity / Invariant Auditor

VALIDATED RUNTIME SHA: `2fea8659685232ef56947cfbbe87c55df1e44c0f`

## Verdict

**PHASE 18 INTEGRITY REVALIDATION: FAIL**

The finance/account/currency reference-scope blocker that motivated the latest hardening is closed, but the full Phase-18 existing-reference surface is not closed. `draftReferences()` still intentionally skips `OwnershipChange`, allowing existing ownership/owner/asset identities introduced by a component draft to reach a resolved `PlayerChangeSet` without `referenceStatus()` or an equivalent campaign-aware authoritative target check.

## Repository pin

At audit start, fresh `master` was one commit ahead of the target and the only delta was:

- `docs/audits/WORK-20260813-P18_CHAT1_REFERENCE_SCOPE_COVERAGE_HARDENING_2FEA8659.md`

No production/test runtime existed after the target. Audit remained pinned to `2fea8659685232ef56947cfbbe87c55df1e44c0f`.

## Production paths inspected

Primary:

- `app/src/main/java/com/rpgos/app/PlayerDomainEngine.kt`
- `app/src/main/java/com/rpgos/app/PlayerChangeSetModel.kt`
- `app/src/main/java/com/rpgos/app/PlayerChangeSetCodec.kt`
- `app/src/main/java/com/rpgos/app/PlayerCommandModel.kt`
- `app/src/main/java/com/rpgos/app/OwnershipModel.kt`

Also inspected the reference-scope production commits and representative reference tests for finance, project/scalar references, Phase-17 regressions, component-state hardening, and exact CI #421.

## Gate results

### PUBLIC CONSTRUCTION PATHS — PASS

Canonical Phase-18 resolution still goes through `PlayerDomainEngine.resolve`. Components produce internal `PlayerResolutionDraft`, not a final `PlayerChangeSet`; engine owns final proposal linkage and Phase-17 validation.

### ROUTING INTEGRITY — PASS

Exact `commandKindUid` lookup, duplicate component registration rejection, unsupported kind failure, typed payload mismatch, and single component execution remain fail-closed.

### REFERENCE SNAPSHOT IMMUTABILITY — PASS

`PlayerResolutionContext` defensively copies `knownReferences` into an unmodifiable `LinkedHashSet` and `dependencyVersions` into an unmodifiable `TreeMap`. Context fingerprinting sorts full `(campaignUid, kindUid, uid)` identities, so input iteration order does not affect identity. Caller mutation of source collections cannot alter the created context.

### COMMAND-SIDE CLOSURE — PASS

Command reference validation runs before component execution. The target now reconstructs fixed reference kinds where the command payload stores scalar UIDs, including:

- financial from/to as `FINANCIAL_ACCOUNT`;
- financial currency as `CURRENCY`;
- project/requirement/milestone identifiers;
- skill/technique existing references where required.

Unknown/wrong-campaign command references fail before component execution.

### DRAFT-SIDE CLOSURE — FAIL

`draftReferences()` independently validates many references after component output and before final proposal assembly, including Stat/Resource/Skill/Technique/Innate/Inventory/Equipment/Condition/Runtime/Project/Event and finance/ledger references.

However, it still contains:

```kotlin
is AssetChange -> Unit
is OwnershipChange -> Unit
```

The `OwnershipChange` payload includes:

- `ownershipRecordUid`;
- `OwnedAssetRef(assetKindUid, assetUid)`;
- `fromOwner: OwnershipOwnerRef`;
- `toOwner: OwnershipOwnerRef`.

Those are existing authoritative identities for an ownership transfer, but Phase 18 performs no campaign-aware existence/scope resolution for them after component output.

### KIND/CAMPAIGN IDENTITY — PASS for covered DomainRef surface

`referenceStatus()` compares full `CampaignScopedDomainRef(campaignUid, DomainRef(kindUid, uid))`. Textual UID equality alone cannot satisfy a lookup with the wrong kind. The same typed identity in another campaign produces `WRONG_CAMPAIGN`; an unregistered kind/UID produces `UNKNOWN`.

This does not cure the omitted ownership structured-reference surface described above.

### FINANCIAL REFERENCE COVERAGE — PASS

Mandatory finance attacks are closed on the production path:

1. unknown source account — reject;
2. unknown destination — reject;
3. unknown currency — reject;
4. wrong-campaign source — reject;
5. wrong-campaign destination — reject;
6. wrong-campaign currency — reject;
7. same textual UID under wrong kind — reject;
8. same textual UID under multiple kinds — exact expected kind wins;
9. duplicate reference evidence — deterministic Set semantics;
10. valid command + unknown draft substitution — reject;
11. valid command + wrong-campaign draft substitution — reject;
12. component-introduced finance reference absent from command — independently checked;
13. replacement with wrong-scope reference — reject;
14. FinancialChange/ledger reference disagreement — Phase-17 financial-term mismatch;
15. agreeing FinancialChange/ledger with unknown identity — Phase-18 draft reference reject;
16. agreeing FinancialChange/ledger with identity only in another campaign — reject;
17. account UID registered only as wrong kind where currency expected — reject;
18. currency UID registered only as wrong kind where account expected — reject.

`FinancialChange` and `FinancialTransferLedgerIntentPayload` each reconstruct account/currency `DomainRef` values independently in `draftReferences()`.

### COMPONENT STATE SECURITY — PASS under declared trust model

Direct/inherited writer fields and prohibited mutable retained state are rejected by hierarchy-aware component-state validation. Safe immutable primitive/wrapper/String/enum inherited configuration remains allowed. Registry inputs are defensively detached.

Per the audit command, absence of a JVM bytecode sandbox for malicious same-module trusted Core code is not treated as a blocker.

### FAILURE ATOMICITY — PASS for supported capability surface

Command validation, command reference rejection, unsupported route, payload mismatch, typed rejection, component exception, draft reference rejection, and final `PlayerChangeSet` validation do not receive an engine/context writer capability and do not persist partial Phase-18 effects.

### AUTHORITY BOUNDARY / ZERO AUTHORITATIVE MUTATION — PASS under declared trust model

No writer/DAO/SQLite/StatePatch/TurnTransaction capability is supplied through the supported engine/context/registry contracts. Existing zero-authority fixtures remain active.

### DETERMINISM — PASS

Canonical command copy, deterministic context fingerprint, explicit entropy evidence, immutable component configuration, deterministic exact routing and Phase-17 canonical proposal serialization remain deterministic on supported paths.

### PLAYERCHANGESET CONSTRUCTION — PASS

Only the engine transforms the typed draft into the canonical final proposal, assigning campaign/source command/actor/causation/correlation/effective order/preconditions/provenance and validating through `PlayerChangeSetValidator`.

### NUMERIC INTEGRITY — PASS

No new lossy numeric conversions were found. Phase-17 constraints remain intact:

- `ExactLongDelta`: signed, non-zero, checked arithmetic;
- `ProjectProgressDelta`: non-negative, zero legal;
- finance: positive exact minor-unit `Long`;
- `OwnershipShare`: exact fixed scale with constructor-level range invariant and exact arithmetic.

### COMPOSITE IDENTITY — PASS

Shared composite conflict identity remains intact across Stat/Resource/Skill/Technique/Innate/Inventory/Equipment/Asset/OwnedAsset/Condition/Runtime, including prior delimiter/CK1 regressions.

### ASSET IDENTITY — PASS structurally

`OwnedAssetRef(assetKindUid, assetUid)` remains lossless in Phase-17 model/codec/conflict identity. This is distinct from the Phase-18 ownership existence/scope blocker: structural tuple identity does not prove that an ownership transfer references an existing in-campaign asset/owner/record.

### FINANCIAL/LEDGER INTEGRITY — PASS

Phase-17 exact financial/ledger matching remains enforced over from account, to account, amount, currency, and transaction type. Duplicate causal representation remains rejected; independent financial changes can have independent ledgers; standalone ledger behavior and dangling/non-financial causal failure semantics remain unchanged.

### SERIALIZATION / FINGERPRINT — PASS

Legal engine proposals retain deterministic `encode -> decode -> encode` and stable SHA-256 fingerprint semantics. Phase-18 reference context does not leak into the serialized `PlayerChangeSet`; engine-owned provenance/linkage remains canonical.

### TEST QUALITY — FAIL

The new reference tests strongly cover finance and representative scalar/project references through the real engine, including command-before-component ordering, draft substitution, wrong kind/campaign and zero-authority fixtures.

They do not cover the omitted `OwnershipChange` existing-reference scope path. Green CI therefore cannot prove complete reference closure.

### PHASE 3–17 REGRESSION — PASS in inspected/CI-covered scope

Historical Phase-17 protections remain active: zero `ExactLongDelta` rejection, zero project progress acceptance, negative project progress rejection, composite delimiter hardening, full asset tuple identity, financial causal uniqueness, canonical serialization and fixed-scale ownership share semantics.

## Release blocker

### P18-INT-REF-OWNERSHIP-01 — OwnershipChange can introduce ghost existing identities without Phase-18 scope validation

**Invariant violated**

Any Phase-18 existing-reference identity introduced by a component draft must pass `referenceStatus()` or an equivalent campaign-aware authoritative resolution before a resolved proposal escapes.

**Minimal reproducer**

1. Construct a valid `TRANSFER_OWNERSHIP` command whose command-side `subject` and destination party references are present in `PlayerResolutionContext`.
2. Register a trusted internal ownership component whose draft returns an `OwnershipChange` with, for example:
   - `ownershipRecordUid = "OWN-GHOST"`;
   - `asset = OwnedAssetRef("RPGOS-ASSET-KIND:PROPERTY", "ASSET-GHOST")`;
   - `fromOwner = OwnershipOwnerRef("PLAYER", "OWNER-GHOST-A")`;
   - `toOwner = OwnershipOwnerRef("PLAYER", "OWNER-GHOST-B")`;
   - legal non-zero `OwnershipShare`.
3. Do not register any of those ghost draft identities in the campaign reference snapshot.
4. Resolve the command.

**Expected**

Draft-side campaign-aware reference rejection (`UNKNOWN_REFERENCE` or equivalent typed ownership-reference rejection) before final `PlayerChangeSet` escape.

**Actual**

`draftReferences()` returns no references for `OwnershipChange`. Phase-17 `TypedPlayerChangeRegistry` only checks nonblank identity components, owner difference, fixed-scale share and conflict keys; it does not perform campaign-scoped existence resolution. The ghost ownership transfer can therefore reach a structurally valid `Resolved` proposal.

**Production path**

`PlayerDomainEngine.resolve -> component.resolve -> validateReferences(context, draftReferences(draft)) -> draftReferences(OwnershipChange -> Unit) -> assembleProposal -> PlayerChangeSetValidator`.

**Minimal correction scope**

Phase-18 reference-resolution boundary only: define an authoritative typed/scope resolution contract for existing ownership record, owner and asset identities (or an equivalent structured-reference resolver) and apply it to ownership drafts before proposal escape. Do not reduce generic owner/asset identity to arbitrary textual UID lookup.

## Exact CI

GitHub Actions:

- run number: `421`
- run ID: `31739185657`
- head SHA: `2fea8659685232ef56947cfbbe87c55df1e44c0f`
- status: `completed`
- conclusion: `success`

Verified successful steps include Validate project, full `:app:testDebugUnitTest`, signed ALPHA APK build, release-file preparation, artifact upload and update of existing release assets. The workflow log shows `:app:testDebugUnitTest` completed `BUILD SUCCESSFUL` and release assembly also completed successfully.

The expected aggregate `553 passed / 0 failed / 0 skipped` is not emitted as a single aggregate line in the Actions job log available to this auditor; I therefore do not independently claim that exact numeric aggregate beyond confirming the full JVM Gradle task itself succeeded.

No independent local Gradle run was performed because the audit environment does not contain a local repository checkout.

## Non-blocking observations

1. The production reference-scope hardening is materially stronger than the prior runtime and closes the finance account/currency campaign/kind bypass class.
2. Process-global malicious bytecode by a trusted internal Core component is outside this audit's blocker model per the explicit trust-model instruction.
3. Kotlin warnings about future data-class copy visibility remain build warnings; constructor-level invariants still enforce the accepted Phase-17 numeric/share constraints in the current runtime.

## Final CHAT-3 verdict

**FAIL** — release blocker `P18-INT-REF-OWNERSHIP-01` remains.