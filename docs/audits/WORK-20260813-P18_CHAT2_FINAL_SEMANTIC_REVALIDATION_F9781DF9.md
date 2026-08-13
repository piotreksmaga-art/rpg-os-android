# WORK-20260813-P18 — CHAT-2 Final Semantic Revalidation

ROLE: CHAT-2 — Independent Semantic Auditor

VALIDATED RUNTIME SHA: `f9781df9c3828b06562aad86a91dec9682c02530`

FINAL VERDICT: **PHASE 18 SEMANTIC REVALIDATION: FAIL**

## Repository pin

Fresh master at audit start was `956e64671fa3b71493958e91934257efea6c5310`.

`f9781df9c3828b06562aad86a91dec9682c02530..master` initially contained exactly one later file under `docs/audits/`: the CHAT-1 structural-boundary report. It was report-only. No newer production/test Phase-18 runtime existed, so the audit remained pinned to the exact requested SHA.

During the audit CHAT-3 added its own report-only commit for the same SHA. That does not change the audited runtime.

## Independent findings

The structural redesign closes the prior command-to-final-ChangeSet resolver problem:

- `PlayerDomainEngine.resolve(command, context)` is the public canonical orchestration entry;
- resolution components and their registry/draft are internal;
- components return typed `PlayerResolutionComponentOutcome` / `PlayerResolutionDraft`, not final `PlayerChangeSet`;
- `PlayerDomainEngine` owns deterministic ChangeSet identity, provenance, command precondition mapping, canonical Phase-17 construction and structural validation;
- expected domain rejection is a typed `PlayerResolutionOutcome.Rejected` with stable `PlayerResolutionRejectionReason`, distinct from `PlayerDomainEngineStructuralException`;
- `PlayerResolutionContext` is immutable copied data carrying campaign/actor/reference snapshot, dependency versions and explicit entropy evidence; it carries no DB/store/transaction/StatePatch writer capability;
- hierarchy-aware component state validation rejects mutable state and non-scalar/non-enum object capability fields, including inherited fields;
- explicit context entropy is included in deterministic evidence/fingerprint inputs;
- routing remains exact by canonical command kind and payload type, with duplicate/missing component fail-closed behavior.

The audit does **not** count arbitrary process-global malicious JVM code as a required sandbox property. The supported capability surface is substantially improved and is semantically appropriate for Phase 18.

However one release-blocking semantic defect remains in the supported reference/scope validation contract.

## Blocker P18-SEM-REFERENCE-COVERAGE-01

### Invariant

Where Phase 18 has explicit campaign-scoped reference evidence, required pre-existing command/draft targets must not leave the engine as a successful proposal when they are absent or belong to another campaign. This is reference existence/scope, not Phase-19 world-rule semantics.

### Production path

`PlayerDomainEngine.resolve(...)`
→ `validateReferences(context, commandReferences(canonicalCommand))`
→ resolution component
→ `validateReferences(context, draftReferences(draft))`
→ `assembleProposal(...)`
→ Phase-17 `PlayerChangeSetValidator`
→ `PlayerResolutionOutcome.Resolved`

### Missing coverage

In `commandReferences(...)`, `TransferFundsCommandPayload` explicitly maps to `Unit`, so `fromAccountUid`, `toAccountUid` and `currencyUid` are never checked against `PlayerResolutionContext.knownReferences`.

In `draftReferences(...)`, `FinancialChange` explicitly maps to `Unit`. Financial ledger intent account/currency identifiers are also not added to the reference set.

Therefore the engine's otherwise explicit same-campaign reference resolver has no opportunity to reject missing/wrong-campaign financial references.

### Minimal reproducer

Construct:

```text
command.kind = TRANSFER_FUNDS
fromAccountUid = ACCOUNT:GHOST-A
toAccountUid = ACCOUNT:GHOST-B
currencyUid = CUR:GHOST
amountMinor = 125
```

Use a `PlayerResolutionContext` for campaign `C1` / actor `P1` whose `knownReferences` contains the player and other unrelated refs but contains no matching financial accounts or currency.

Use a legal internal finance resolution component that returns:

- one `FinancialChange` with the exact command terms;
- one matching `FinancialTransferLedgerIntentPayload` causally linked to that change.

The canonical Phase-18 flow accepts the draft and can return `PlayerResolutionOutcome.Resolved` because neither command-side nor draft-side reference extraction includes the financial targets.

### Existing regression evidence confirms the gap

`p18Hotfix26_financialLedgerTermsArePreserved` resolves the test finance command containing `ACCOUNT:A`, `ACCOUNT:B` and `CUR:PLN` with the shared `context()` fixture. Its `baseRefs()` contains PLAYER/STAT/PROJECT/EVIDENCE references but no ACCOUNT or CURRENCY references. The test expects and receives a resolved proposal. Thus the current green regression locks term preservation but does not prove financial reference/scope resolution; it demonstrates that unresolved financial identities are presently accepted by Phase 18.

### Expected

Before a financial proposal leaves Phase 18, required pre-existing financial account/currency identities supplied by the command/draft should be resolved against the explicit campaign-scoped context (or an equivalent typed Phase-18 read-only reference snapshot). Missing target should produce typed `UNKNOWN_REFERENCE`; same identity known only in another campaign should produce typed `WRONG_CAMPAIGN_REFERENCE`.

### Actual

A semantically impossible transfer can be emitted as a successful PlayerChangeSet proposal even when the explicit Phase-18 context contains no such financial accounts/currency.

### Why this is Phase 18, not Phase 19

No game/world rule is needed to determine whether a referenced account/currency identity exists in the current campaign. The runtime already introduced `CampaignScopedDomainRef`, `PlayerResolutionContext.referenceStatus(...)`, `UNKNOWN_REFERENCE` and `WRONG_CAMPAIGN_REFERENCE` precisely for this class of structural/domain-scope resolution. The defect is incomplete extraction/application of that existing Phase-18 mechanism.

### Minimal correction scope

Phase 18 only. Extend reference extraction/resolution for command/draft families that carry required pre-existing stable identifiers, beginning with financial account/currency targets, using stable typed DomainRef kinds consistent with accepted Phase-13 authorities. Add direct regressions for unknown and wrong-campaign financial refs while preserving exact financial/ledger terms and standalone proposal semantics. Do not implement WorldRuleProvider, mechanics, TurnTransaction or persistence.

## Gate summary

- Canonical PlayerDomainEngine entry: PASS
- Resolution component boundary: PASS
- Read-only capabilities: PASS for supported capability surface
- Typed resolution outcome: PASS
- Domain rejection vs structural failure: PASS
- Determinism: PASS for explicit modeled inputs/capabilities
- State leakage resistance: PASS for supported component-state model
- Failure atomicity: PASS for supported Phase-18 capabilities; no JVM sandbox requirement applied
- Zero authoritative mutation: PASS for supported capability surface
- Reference/scope validation: **FAIL** (`P18-SEM-REFERENCE-COVERAGE-01`)
- Routing: PASS
- Project zero-progress: PASS
- ExactLongDelta: PASS
- Composite target identity: PASS
- Asset identity: PASS
- Financial/ledger exact terms and causal structure: PASS, but financial reference existence/scope is the blocker above
- Serialization: PASS
- Fingerprint: PASS
- World-agnostic: PASS
- Phase boundary: PASS
- Test quality: **FAIL for reference/scope completeness**; other redesign tests exercise real production paths
- Phase 3–17 regression: PASS in exact-CI-covered scope

## Full JVM / CI

A local full JVM run was attempted, but the audit container cannot resolve `github.com`, so checkout failed before Gradle could run. Local status: NOT-RUN due environment/network limitation.

Exact CI was independently verified:

- workflow run number: `401`
- run ID: `31727239097`
- head SHA: `f9781df9c3828b06562aad86a91dec9682c02530`
- status: `completed`
- conclusion: `success`

The exact build job completed successfully for:

- Validate project;
- Run JVM unit tests;
- Build signed ALPHA APK;
- Prepare release files;
- Upload Actions artifact;
- existing release asset update;
- release information / overall job.

Green CI does not cover the unresolved financial reference case above.

## Final CHAT-2 verdict

**PHASE 18 SEMANTIC REVALIDATION: FAIL**

New blocker: `P18-SEM-REFERENCE-COVERAGE-01`.

No production/test/schema/workflow runtime file was modified by CHAT-2. This commit is report-only.

Phase 18 is not globally accepted. Phase 19 remains blocked.