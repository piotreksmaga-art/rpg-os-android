# WORK-20260813-P18 — CHAT-5 FINAL COMPLETE REVALIDATION

**Role:** CHAT-5 independent adversarial / robustness / correctness reviewer  
**Target runtime SHA:** `f9781df9c3828b06562aad86a91dec9682c02530`  
**Fresh master before report write:** `54122a9ed9562bcfe2c608747887f2d640b3b937`  
**Verdict:** **FAIL**

This is a fresh audit. Earlier CHAT-5/other-chat reports were not used as truth; their claims were treated only as leads and rechecked against the target production code, target tests, MASTER/Phase-18 architecture and exact GitHub Actions evidence.

## 1. Repository target / ancestry / post-target diff

GitHub branch metadata resolved fresh `master` to `54122a9ed9562bcfe2c608747887f2d640b3b937`. GitHub compare `f9781df9...` -> `54122a9...` reports the target as the merge base, `ahead_by=5`, `behind_by=0`; target is an ancestor of master.

Equivalent of requested `git diff --name-status f9781df9...HEAD` contains exactly five additions, all under `docs/audits/`:

- `A docs/audits/WORK-20260813-P18_CHAT1_STRUCTURAL_BOUNDARY_REDESIGN_F9781DF9.md`
- `A docs/audits/WORK-20260813-P18_CHAT2_FINAL_SEMANTIC_REVALIDATION_F9781DF9.md`
- `A docs/audits/WORK-20260813-P18_CHAT3_FINAL_INTEGRITY_REVALIDATION_F9781DF9.md`
- `A docs/audits/WORK-20260813-P18_CHAT4_FINAL_ARCHITECTURE_REVALIDATION_F9781DF9.md`
- `A docs/audits/WORK-20260813-P18_CHAT5_FINAL_COMPLETE_REVIEW_F9781DF9.md`

Classification after target: **production: none; tests: none; docs/audits: five additions; other: none.** Therefore **RUNTIME CHANGED AFTER TARGET: NO**.

A local fresh clone was attempted but the sandbox has no GitHub DNS (`Could not resolve host: github.com`). Repository truth, history and CI were therefore verified through the connected GitHub API. No later report-only SHA was treated as runtime.

## 2. Inspected target files

Production inspected directly at the target includes at minimum:

- `app/src/main/java/com/rpgos/app/PlayerDomainEngine.kt`
- `app/src/main/java/com/rpgos/app/PlayerResolutionComponentStateValidator.kt`
- `app/src/main/java/com/rpgos/app/PlayerCommandModel.kt`
- `app/src/main/java/com/rpgos/app/PlayerCommandRegistry.kt`
- `app/src/main/java/com/rpgos/app/PlayerCommandCoreCodecs.kt`
- `app/src/main/java/com/rpgos/app/PlayerCommandStrictJson.kt`
- `app/src/main/java/com/rpgos/app/PlayerChangeSetModel.kt`
- `app/src/main/java/com/rpgos/app/PlayerChangeSetCodec.kt`
- `app/src/main/java/com/rpgos/app/ProjectProgressDelta.kt`
- adjacent authoritative store/repository tree entries were reviewed for the Phase-18 capability boundary.

Tests inspected directly include:

- `PlayerDomainEngineTest.kt`
- `PlayerDomainEngineInheritedStateTest.kt`
- Phase-17 ChangeSet contract/hardening/hotfix/value-invariant/project-zero/composite-identity regression tests present at the target
- Phase-16 command contract/adversarial/numeric/public-codec regression tests present at the target.

Architecture inspected at the target:

- `docs/RPG_OS_MASTER_ARCHITECTURE.md`
- `docs/audits/WORK-20260810-069_PHASE18_PLAYERDOMAINENGINE_ORCHESTRATION_PREP.md`.

## 3. Exact CI evidence

GitHub Actions run **#401**, run ID **31727239097**, was queried directly. It is `Build & Release RPG OS ALPHA`, event `push`, head branch `master`, **head SHA exactly `f9781df9c3828b06562aad86a91dec9682c02530`**, status `completed`, conclusion `success`.

Job `build` ID `94538554269` completed `success`. Its relevant steps all completed successfully:

- Validate project — success
- Run JVM unit tests — success
- Build signed ALPHA APK — success
- Prepare release files — success
- Upload Actions artifact — success
- Check if release already exists — success
- Create GitHub Release — skipped because release already existed
- Update existing GitHub Release assets — success
- Show release information — success
- Complete job — success.

Target workflow `build-alpha.yml` confirms `Run JVM unit tests` executes `gradle --no-daemon :app:testDebugUnitTest --stacktrace` and signed release executes `gradle --no-daemon :app:assembleRelease --stacktrace` after restoring the permanent signing key.

Artifact ID `9191894439`, `RPG-OS-ALPHA-1.2.0-alpha5-hybrid140`, exists, is not expired, is tied to exact target head SHA and has digest `sha256:47f98fbe8f23a05b72b775ae32a3b99c7c168888c964fb302dc563473924b314`.

**EXACT CI: PASS.**

**LOCAL FULL JVM: NOT-RUN** — the local sandbox could not fresh-clone/fetch GitHub because DNS resolution for `github.com` failed. This does not alter the independently verified exact-CI success.

## 4. Phase-18 orchestration and typed boundary

### PlayerDomainEngine / canonical entry / routing — PASS

`PlayerDomainEngine.resolve(command, context)` validates the command, canonicalizes by registry encode/decode, fingerprints the canonical command, validates context campaign/actor, validates extracted command refs, resolves exactly one component from a duplicate-rejecting map, checks payload runtime type before invocation, checks command fingerprint after invocation, validates draft refs, assembles the final `PlayerChangeSet` itself and runs Phase-17 `PlayerChangeSetValidator` before returning `Resolved`.

Unknown component kind fails closed (`UNKNOWN_COMMAND_RESOLUTION_COMPONENT`); duplicate component kind fails at registry construction (`DUPLICATE_COMMAND_RESOLUTION_COMPONENT`); payload mismatch fails before component body (`COMMAND_RESOLUTION_COMPONENT_PAYLOAD_TYPE_MISMATCH`).

`PlayerResolutionComponent` and its registry/draft/outcome are `internal`; the component returns `PlayerResolutionComponentOutcome` containing an internal `PlayerResolutionDraft`, not a final `PlayerChangeSet`. The only public Phase-18 command-to-proposal operation found is the engine `resolve` returning `PlayerResolutionOutcome`.

### Engine-owned linkage — PASS

`assembleProposal()` takes campaign/source command/actor/preconditions/causation/correlation/requested order from the canonical command. Resolver identity/version comes from the selected component. Draft cannot replace those identities. ChangeSet UID is a length-delimited SHA-256 derivation over canonical command encoding + context fingerprint + component identity/version.

### Typed outcomes — PASS

Domain rejection is represented by `PlayerResolutionComponentOutcome.Rejected` / public `PlayerResolutionOutcome.Rejected` with stable enum reason UIDs. Unexpected component throws are wrapped as structural `PlayerDomainEngineStructuralException("RESOLUTION_COMPONENT_FAILURE")`. Domain rejection is not encoded as an arbitrary structural string.

## 5. Resolution context / immutability / evidence

`PlayerResolutionContext` constructor is private. `create()` defensively copies `knownReferences` into a new `LinkedHashSet`, dependency versions into a `TreeMap`, and exposes unmodifiable collections. `CampaignScopedDomainRef`, `DomainRef`, actor and entropy evidence are immutable value objects. Context fingerprint sorts reference tuples, uses sorted dependency versions, and includes campaign, actor, entropy evidence UID and exact entropy value with length-prefixed tokens; source collection mutation cannot change the context.

Registry input is copied and exposed kind set is unmodifiable. Draft, ChangeSet event/ledger/evidence lists and project evidence lists use defensive immutable copies. No Float/Double is introduced for exact semantics.

**READ-ONLY CAPABILITY MODEL: PASS.** No database/store/repository/transaction/StatePatch writer is present in `PlayerResolutionContext` or injected by the normal engine/component API.

## 6. Component-state security and Lead B

`PlayerResolutionComponentStateValidator` walks the complete subclass hierarchy up to `PlayerResolutionComponent`. Every retained field must be `final` and its declared type must be one of primitive/boxed scalar, `String`, or enum. Consequently normal retained-state attacks are rejected:

- direct database/writer field: rejected as unsafe;
- inherited writer field: rejected as unsafe;
- mutable counter (`var`): rejected as mutable;
- mutable collections/nested objects/arrays/Atomic*/lazy/delegate: rejected by the narrow allow-list or by generated mutable/delegate fields;
- safe inherited immutable scalar configuration: accepted, covered by target test.

This policy is deliberately conservative; synthetic instance fields are also inspected rather than blanket-ignored.

### Stateless/global side-effect attack — NON-BLOCKING under the actual trust boundary

A same-module malicious component with **no retained writer field** can of course write via an independently reachable Kotlin/JVM global/static/singleton or read `System.currentTimeMillis()`, `nanoTime`, `Random`, `UUID.randomUUID()` or another global. The runtime validator does not sandbox arbitrary JVM bytecode.

However, this does **not** establish a supported Phase-18 capability bypass. The actual component type, registry and draft boundary are `internal`; normal components receive only canonical command + detached read-only context. MASTER/Phase-18 architecture explicitly forbids writer dependencies and hidden random sources and defines resolution as observationally pure, but it does not define untrusted plugin execution or a JVM sandbox as a Phase-18 security boundary. A malicious same-module implementation can equally bypass almost any ordinary JVM architectural convention using globals/reflection/native calls. Under the audit's stated “NO FALSE SECURITY REQUIREMENTS” rule, absence of a JVM sandbox is therefore **NON-BLOCKING**.

Important qualification: purity remains a trusted-internal-code contract plus capability-denial design, not a mathematical runtime proof against arbitrary code. Static analysis/lint could strengthen enforcement later, but is not required to accept this trust model.

Thus **Lead B as a runtime blocker is not reproduced**. Retained writer/mutable-state attacks are blocked; stateless malicious-global attacks are outside the supported trust boundary.

## 7. Determinism / explicit entropy / failure atomicity / zero mutation

Within the supported trusted-component contract, deterministic inputs are explicit: canonical command, immutable context, dependency versions, component identity/version and entropy evidence. Context/reference ordering is canonicalized before fingerprinting, and proposal UID derives from canonical input/evidence. Target tests cover same-input replay and explicit entropy variation.

No Phase-18 normal API exposes commit/persistence/write authority. `PlayerDomainEngine` constructs a proposal and calls Phase-17 validation only. It has no `TurnTransaction`, `StatePatch`, DB, repository/store writer, ledger persistence or inventory/project/world mutation capability. Rejection, reference rejection, component throw and final ChangeSet validation failure cannot perform an authoritative write through the supported injected capability graph. Robolectric tests use a real SQLite authority fixture and verify unchanged values for supported failure paths; the tests actually reach the validator/engine (not the historical unmocked-SQLite failure mode).

**DETERMINISM: PASS within supported trust model.**  
**EXPLICIT ENTROPY/EVIDENCE: PASS within supported trust model.**  
**FAILURE ATOMICITY: PASS within supported trust model.**  
**ZERO AUTHORITATIVE MUTATION: PASS.**

## 8. BLOCKER P18-REF-01 — incomplete reference/scope closure

**Severity:** correctness / campaign-isolation blocker.  
**Violated invariant:** Phase-18 reference/scope validation must resolve stable targets against the same campaign before a proposal leaves the engine; unknown and cross-campaign references must fail closed with typed `UNKNOWN_REFERENCE` / `WRONG_CAMPAIGN_REFERENCE`.

Production extraction is asymmetric.

`commandReferences()` validates precondition targets and many explicit `DomainRef` fields, but contains `TransferFundsCommandPayload -> Unit`. `draftReferences()` contains `FinancialChange -> Unit`, and does not inspect financial ledger account/currency identities either. Phase-17 validates finance structural terms and exact change↔ledger matching, but it has no campaign-aware existence snapshot. Therefore the omitted identities never reach `PlayerResolutionContext.referenceStatus()`.

### Minimal Lead-A reproducer

Command:

```text
TRANSFER_FUNDS
fromAccountUid = ACCOUNT:GHOST-A
toAccountUid   = ACCOUNT:GHOST-B
currencyUid    = CUR:GHOST
amountMinor    = 125
```

Context: correct campaign/actor; `knownReferences` deliberately contains none of the three identities. Registered internal typed finance component returns a structurally valid `FinancialChange` plus matching `FinancialTransferLedgerIntentPayload`, exactly as the target `FinanceComponent` test fixture does.

Expected Phase-18 result: typed `UNKNOWN_REFERENCE` before accepted proposal.

Actual target path:

```text
commandReferences(TRANSFER_FUNDS) -> []
component executes
Finance draft created
draftReferences(FinancialChange) -> []
ledger financial identities are not added by draftReferences
assembleProposal
PlayerChangeSetValidator validates nonblank/positive/exact terms and change-ledger equality
Resolved(proposal)
```

The same attack with `CampaignScopedDomainRef("OTHER", DomainRef("FINANCIAL_ACCOUNT", ...))`/currency identities only in another campaign also cannot produce `WRONG_CAMPAIGN_REFERENCE`, because `referenceStatus()` is never called for them.

Target test `p18Hotfix26_financialLedgerTermsArePreserved` does not close this: its context does not contain `ACCOUNT:A`, `ACCOUNT:B` or `CUR:PLN`, yet it expects and obtains `Resolved`. That is production-path evidence of the gap, not merely missing test coverage.

### Broader audit of command/draft families

Checked command refs include generic preconditions, TRAIN focus, USE_RESOURCE, RECOVER resource, USE_TECHNIQUE target, ACQUIRE_ITEM source, TRANSFER_ITEM item/toParty, CONSUME item, EQUIP item, TRANSFER_OWNERSHIP subject/toParty, ACQUIRE_ASSET terms, ENTER_OBLIGATION counterparty, START_PROJECT beneficiary/target, project evidence/resource/source-work and completion evidence.

Checked draft refs include STAT/RESOURCE/SKILL/TECHNIQUE/INNATE/INVENTORY/EQUIPMENT/CONDITION/RUNTIME/PROJECT plus project evidence and event actor/targets/domain-effect subject.

But the command-side extractor also omits stable target/definition identities represented only as scalar UIDs for several families (notably skill/technique UID fields, equipment slot fields, obligation UID and multiple project/project-lifecycle UID fields). The draft-side extractor omits `FinancialChange`, `OwnershipChange` and `AssetChange` entirely. Not every scalar UID is necessarily an existence reference (for example a newly allocated asset identity can legitimately be introduced by an acquire proposal), so those omissions cannot all be labeled defects merely by grep. Nevertheless, where the command semantically addresses an already-existing target and no corresponding checked draft reference is guaranteed, the current generic boundary does not enforce campaign-aware closure.

Most importantly, finance is unambiguous: existing source/destination accounts and currency identities are dependencies of a transfer, not newly created outputs, and the target's own finance fixture demonstrates that an empty reference context is accepted.

**REFERENCE/SCOPE VALIDATION: FAIL.**  
**FINANCIAL REFERENCE COVERAGE: FAIL.**

## 9. Phase-17 regression revalidation

The target retains the accepted Phase-17 value types/validator/codec and regression suites; Phase-18 does not rewrite them.

- `ExactLongDelta`: constructor init and `of()` reject zero; signed non-zero semantics including `Long.MIN_VALUE`/`MAX_VALUE` remain legal. `copy(units=0)` still invokes data-class construction/init and rejects.
- `ProjectProgressDelta`: zero and positive including `Long.MAX_VALUE` legal; negative rejected in init, including `copy(negative)`. Phase-18 test preserves FAILURE+0 and NO_PROGRESS+0 behavior.
- Composite conflict keys remain canonical tuple identities rather than ambiguous delimiter concatenation; historical `PLAYER/X:Y + Z` vs `PLAYER/X + Y:Z` remains distinct. Delimiter/Unicode/whitespace/CK1-looking inputs are encoded structurally rather than parsed as a legacy key.
- Legacy/CK1 and canonical identity remain separated by structural encoding; identical canonical tuple yields identical key.
- `OwnedAssetRef` carries both asset-kind UID and asset UID; asset identity regression remains intact.
- Financial change/ledger matching is exact for from/to/amount/currency/transaction type; dangling causal UIDs, non-financial causal representation and duplicate financial causal representation are rejected by Phase-17 validator. This structural correctness does not substitute for Phase-18 existence/scope lookup.
- Duplicate change/event/ledger IDs, duplicate semantic targets, event causal links and warning related-change links remain fail-closed.
- Exact numeric semantics use `Long`; fixed-scale `OwnershipShare` stays integral; finance amount validator remains positive-only; no Float/Double exact path found.
- Canonical codec remains strict about scalar types/unknown keys/duplicate keys and validates before encoding and after decoding; legal proposal round-trip is stable.
- Fingerprint is SHA-256 of canonical validated serialization, so illegal accepted state cannot be fingerprinted through the public codec and validator-internal conflict keys are not serialized into the fingerprint.

**PROJECT ZERO-PROGRESS: PASS**  
**EXACTLONGDELTA: PASS**  
**COMPOSITE TARGET IDENTITY: PASS**  
**LEGACY/CK1: PASS**  
**ASSET IDENTITY: PASS**  
**FINANCIAL/LEDGER structural contract: PASS**  
**DUPLICATE/CONFLICT/REFERENCES (Phase-17 structural links): PASS**  
**NUMERIC CORRECTNESS: PASS**  
**IN-MEMORY/SERIALIZED CLOSURE: PASS**  
**SERIALIZATION: PASS**  
**FINGERPRINT: PASS**  
**PHASE 3–17 REGRESSION: PASS**

## 10. World/phase boundary

No Naruto/Bleach/world-specific mechanics are encoded in the Phase-18 engine/context/components. No TurnTransaction execution, StatePatch application, commit, persistence, rollback engine, world simulation or Phase-19 WorldRuleProvider implementation is introduced. Phase-18 outputs remain proposals until a later transaction boundary.

**WORLD-AGNOSTIC: PASS.**  
**PHASE BOUNDARY: PASS.**

## 11. Test quality

Security/state tests are materially stronger than superficial reflection-only checks: direct SQLite writer capture is rejected before body execution under Robolectric; inherited writable capability rejection has a pure JVM fixture; mutable retained state is rejected; safe inherited immutable scalar state is accepted; deterministic replay/entropy, unknown/wrong campaign for a TRAIN `DomainRef`, failure paths, payload mismatch, duplicate routing, proposal linkage and Phase-17 regression locks are covered.

However, test quality is **FAIL for release acceptance** because the high-risk reference/scope matrix is not complete. In particular, finance's existing test actually proves the gap by resolving `ACCOUNT:A`/`ACCOUNT:B`/`CUR:PLN` despite those identities being absent from `knownReferences`; there is no adversarial test requiring `UNKNOWN_REFERENCE` or `WRONG_CAMPAIGN_REFERENCE` for financial accounts/currency.

No audit-only test was committed because local execution was unavailable and the minimal reproducer is directly established by target production control flow plus the existing production-path finance fixture. No production or existing test file was modified.

## 12. Mandatory area verdict matrix

| Area | Verdict | Basis |
|---|---|---|
| PlayerDomainEngine | PASS | canonicalize/dispatch/draft/assemble/final validate |
| Canonical single entry | PASS | public engine resolve; internal component boundary |
| Resolution component boundary | PASS | internal typed component returns draft/outcome |
| Component state security | PASS | hierarchy-aware final+narrow-safe-type validator |
| Read-only capability model | PASS | no writer capability injected |
| Stateless/global side-effect analysis | NON-BLOCKING | arbitrary malicious JVM global/static calls outside trusted internal capability boundary |
| Typed outcome model | PASS | Resolved/Rejected + structural exception |
| Domain rejection vs structural failure | PASS | distinct typed paths/reason UIDs |
| Determinism | PASS | supported contract; canonical fingerprints + explicit evidence |
| Explicit entropy/evidence | PASS | context entropy/evidence included |
| Failure atomicity | PASS | supported path has no write capability |
| Zero authoritative mutation | PASS | proposal-only, no commit authority |
| Reference/scope validation | **FAIL** | P18-REF-01 |
| Financial reference coverage | **FAIL** | unambiguous Lead-A reproducer |
| Command→proposal linkage | PASS | engine-owned identities/provenance |
| Routing | PASS | exactly-one map + duplicate/unknown/type fail closed |
| Project zero-progress | PASS | 0 legal, negative rejected |
| ExactLongDelta | PASS | zero rejected incl copy |
| Composite target identity | PASS | canonical structural keys |
| Legacy/CK1 | PASS | structured canonical separation |
| Asset identity | PASS | kind+asset preserved |
| Financial/ledger | PASS | Phase-17 exact structural/cause matching; existence/scope failure scored separately |
| Duplicate/conflict/references | PASS | Phase-17 structural relations preserved |
| Numeric correctness | PASS | integral exact semantics |
| Immutability/aliasing | PASS | defensive copies/unmodifiable boundaries |
| In-memory/serialized closure | PASS | validated roundtrip |
| Serialization | PASS | strict/canonical |
| Fingerprint | PASS | validated canonical encode |
| World-agnostic | PASS | no world-specific mechanics |
| Phase boundary | PASS | no transaction/commit/persistence |
| Test quality | **FAIL** | finance scope attack absent / existing test accepts it |
| Phase 3–17 regression | PASS | no regression found |
| Full JVM local | NOT-RUN | sandbox DNS prevents fresh clone/fetch |
| Exact CI | PASS | run 401 exact target, full JVM+release successful |

## 13. Blockers and non-blocking observations

### BLOCKER P18-REF-01 — financial and broader scalar-reference coverage is not closed

Minimal finance reproducer above is sufficient to reject Phase 18. A valid typed transfer proposal can contain account/currency identities absent from the campaign reference snapshot, and same UID existing only in another campaign does not produce the required wrong-campaign rejection.

Required correction belongs in Phase-18 reference extraction/linkage policy (or an equivalently strong typed reference representation), followed by adversarial regressions across every command/change family. CHAT-5 does not modify production.

### Non-blocking observations

1. Stateless same-module code can call global/static sources because this is ordinary Kotlin/JVM, but components are trusted `internal` implementation code and no writer capability is provided by the supported API. This is not a JVM sandbox boundary and is not scored as a blocker.
2. The component-state allow-list is intentionally conservative; safe immutable scalar inherited state is accepted. Broader immutable configuration objects are rejected, which is restrictive but not a correctness defect under the current explicit retained-state contract.
3. A future lint/static dependency rule for forbidden global time/random/writer calls would make the purity convention easier to police without pretending to sandbox hostile bytecode.

## 14. Final verdict

There is at least one unresolved correctness/campaign-isolation blocker in the exact target runtime. Green exact CI does not override it.

**FINAL CHAT-5 VERDICT: FAIL.**

This report does not mark Phase 18 globally accepted. Phase 19 remains blocked pending the required independent exact-SHA PASS set.