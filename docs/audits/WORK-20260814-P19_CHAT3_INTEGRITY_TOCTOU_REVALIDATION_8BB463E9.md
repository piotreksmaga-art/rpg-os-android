# WORK-20260814-P19 — CHAT-3 FRESH INTEGRITY / TOCTOU REVALIDATION

PHASE 19 INTEGRITY REVALIDATION: FAIL

ROLE: CHAT-3 — INDEPENDENT INTEGRITY / TOCTOU AUDITOR

VALIDATED SHA: `8bb463e90142e12a499465b6554d7c8fbf58e355`

## Scope and independence

This audit independently inspected the production code and exact-sha tests for the new `WorldPackAuthorityResolver` and per-resolution authority semantics. Earlier PASS verdicts were not used as evidence. No production or test file was modified by this audit.

At audit time, `target..master` contained only two later `docs/audits` report files and no later runtime/test/build changes.

## Contract established from production

The target commit is explicitly `fix: resolve current World Pack authority per resolution`.

The production authority adapter returned by `CampaignSelectionManager.activeWorldPackAuthorityResolver()` re-reads canonical selection on each resolver lookup. The compatibility method with the historical `Snapshot` name also returns this live resolver rather than a retained binding.

`PlayerDomainEngine.resolve()` performs one authority lookup through `validateWorldRuleAuthority(context)` after command/context/reference checks and before command rule evaluation. Once that lookup matches the immutable `context.worldRuleMode`, both `COMMAND_PRECHECK` and `DRAFT_EFFECT_CHECK` use the same `context.worldRuleMode.binding`. The context fingerprint contains the bound World Pack UID/version, decision fingerprints contain provider/world-pack/stage/request identity, and proposal UID derivation includes the decision fingerprints.

Therefore the actual implemented contract is a single-resolution authority snapshot: authority is required to be current when the authority gate is crossed, and the successfully validated immutable binding is then used consistently for the remainder of that resolution. A canonical selection change after that gate does not by itself make the already-running resolution inconsistent; it remains internally bound to the original validated authority and provenance.

This means the following schedule is NON-BLOCKING by itself:

1. authority gate validates campaign C1 / World Pack A;
2. after the gate, canonical selection changes to B;
3. both rule stages continue under A;
4. proposal identity/provenance remains tied to A's decision fingerprints.

Re-reading authority independently before the second stage would instead risk mixing two authorities inside one proposal and would violate the observed per-resolution deterministic snapshot model.

## New blocker: non-atomic snapshot acquisition

**P19-C3-TOCTOU-001 — NON_ATOMIC_CAMPAIGN_WORLD_PACK_AUTHORITY_READ**

The canonical resolver does not obtain campaign identity and World Pack binding as one atomic/coherent authority read. Its production sequence is effectively:

```kotlin
val currentCampaignUid = activeCampaignId()
if (campaignUid != currentCampaignUid) null else activeWorldRuleMode().binding
```

`activeCampaignId()` and `activeWorldRuleMode()` are separate reads. `activeWorldRuleMode()` itself obtains the current World Pack selection later, after the campaign comparison. There is no lock, generation/version token, atomic pair snapshot, or second campaign check around these reads.

### Exact concurrent reproducer schedule

Initial canonical state: active campaign `C1`, active World Pack `A`.

Resolution R uses context `C1 / Bound(A)`.

1. R enters `bindingForCampaign("C1")`.
2. R executes `activeCampaignId()` and obtains `C1`.
3. Before R executes `activeWorldRuleMode()`, another supported selection action switches the active campaign to `C2`.
4. R now executes `activeWorldRuleMode()` and obtains World Pack `A` (the World Pack need not even change).
5. Resolver returns binding `A` for requested campaign `C1` even though canonical active campaign is now `C2` at return time.
6. `validateWorldRuleAuthority()` compares the returned `A` with context `Bound(A)` and accepts.
7. No later authority read occurs in that resolution.
8. provider A can run for COMMAND_PRECHECK, the component can resolve, provider A can run for DRAFT_EFFECT_CHECK, and a proposal for C1 can escape despite the canonical campaign already having switched to C2 before the authority lookup completed.

A stronger torn-pair variant is also possible if active campaign and active World Pack are changed in separate operations between the two reads, allowing a campaign/binding combination that was not a coherent canonical pair at any single instant.

No production synchronization/serialization guard tying selection mutation and `PlayerDomainEngine.resolve()` was found at the audited SHA. The `CampaignSelectionManager` setters use independent SharedPreferences updates, and the resolver has no generation token/retry mechanism.

This is distinct from the intended post-gate snapshot semantics. The blocker is that the snapshot itself can be torn/stale at acquisition.

## Authority resolver state

AUTHORITY RESOLVER STATE: PASS within the intended internal Core boundary.

`WorldPackAuthorityResolver` is an `internal fun interface` exposing only `bindingForCampaign(campaignUid)`. The canonical production implementation is created by `CampaignSelectionManager` and intentionally reaches the mutable canonical selection so it can be live across resolutions. It does not retain a cached binding.

An arbitrary malicious same-module implementation could of course retain or mutate arbitrary objects, but that would be trusted same-module code outside the supported resolver contract, not an exposed provider/plugin capability boundary. No JVM sandbox is required here.

The canonical closure does retain the selection manager, but the resolver interface does not expose that manager or any write method to providers, requests, components, or callers receiving rule evidence.

## Mutable aliasing / immutability

MUTABLE ALIASING: PASS.

`WorldPackRuleBinding` contains immutable String-valued `val` fields. The fixture `WorldPackAuthoritySnapshot` defensively copies its input map and exposes it through an unmodifiable map. The live canonical resolver returns a freshly derived binding rather than a mutable collection alias.

`PlayerResolutionContext` defensively copies known references/dependency versions, and its `WorldRuleMode.Bound` contains the immutable binding. The context fingerprint includes World Pack UID/version.

## Stale cache / long-lived engine

STALE CACHE: PASS.

The compatibility `activeWorldPackAuthoritySnapshot()` no longer captures a one-time map binding; it returns the live resolver. The exact-sha freshness tests exercise a long-lived engine over A→B and A→B→A, stale binding rejection, stale version rejection, missing authority, and resolver failure.

A sequential campaign/World-Pack change before a new `resolve()` therefore re-enters the resolver and does not reuse an old binding. The blocker above concerns only a race inside the multi-read lookup itself.

## Read-only capability boundary

READ-ONLY CAPABILITY: PASS.

The resolver interface exposes only a binding lookup. It exposes no `SQLiteDatabase`, DAO writer, mutable repository, `StatePatch`, `TurnTransaction`, ledger writer, or commit callback. The resolver object itself is retained by the engine and is not included in `WorldRuleRequest` or provider input.

`WorldRuleRequest` remains a read-only transient request containing immutable identity/fingerprint data and detached command/effect snapshots; no authority resolver capability is leaked to providers.

## Resolver failures and atomicity

FAILURE ATOMICITY: PASS.

`validateWorldRuleAuthority()` wraps unexpected resolver exceptions as `WORLD_RULE_AUTHORITY_READ_FAILED`; missing bound authority fails as `WORLD_RULE_AUTHORITY_MISSING`; mismatched binding fails as `WORLD_RULE_BINDING_AUTHORITY_MISMATCH`. These happen before provider invocation and before component resolution/proposal construction.

The exact-sha authority freshness suite verifies resolver exception fail-closed behavior with zero provider calls, missing authority fail-closed behavior, stale binding/version rejection, and successful resolution not mutating canonical prefs/manifest/database fixture state.

ZERO AUTHORITATIVE MUTATION: PASS for the resolver/engine path itself. The authority read path calls canonical selection read/validation methods; it does not write selection or authoritative game state.

## Provider-state security recheck

PROVIDER STATE SECURITY: PASS.

The provider-state validator still starts from `provider.javaClass`, walks inherited provider classes, rejects non-final retained fields, and only accepts primitive/scalar-safe types or recursively validated enum state.

Enum validation starts from the ACTUAL retained enum value runtime class (`enumValue.javaClass`) and walks its superclass chain. Therefore:

- base enum mutable field: rejected;
- constant-specific enum subclass mutable field: rejected;
- constant-specific nested mutable object: rejected;
- writer-like retained object: rejected;
- mutable collection / inherited unsafe object: rejected;
- safe stateless enum and scalar/String configuration: accepted.

The exact target still contains direct tests for those cases.

## Determinism and single-resolution consistency

DETERMINISM: PASS apart from the concurrent acquisition blocker.

Once a binding has successfully passed the authority gate, the same immutable context binding is used by both rule stages. The request/context/decision fingerprints structurally include World Pack identity and stage. Proposal UID derivation includes the ordered decision fingerprints. Thus one completed resolution cannot silently use A for command precheck and B for draft check under normal engine flow.

The concurrent acquisition blocker is specifically about deciding which authority snapshot was valid at the gate, not about later divergence between the two provider stages.

## Phase-18 regression

PHASE-18 REGRESSION: PASS.

The exact target retains `PlayerDomainEngineReferenceClassificationTest` coverage proving:

- equipment slot is not campaign-overvalidated and survives unchanged;
- new ownershipRecordUid remains local identity;
- full asset kind+UID identity is required;
- full owner kind+UID identity is required;
- unknown and wrong-campaign asset/owner references reject;
- command-valid references cannot authorize ghost draft substitutions.

Reference validation still occurs before the Phase-19 authority/provider gate.

## Phase-17 regression

PHASE-17 REGRESSION: PASS.

The exact target retains the Phase-17 numeric/identity/serialization regression suite, including:

- `ExactLongDelta` zero rejected, including copy-to-zero;
- `ProjectProgressDelta` zero accepted and negative rejected;
- `OwnershipShare` range invariant;
- composite conflict identity separation;
- owned asset typed identity separation;
- financial/ledger round trip;
- zero-progress serialization/fingerprint determinism.

## Exact CI / full JVM

EXACT CI: PASS.

GitHub Actions run #507, run ID `31826220849`, has exact head SHA `8bb463e90142e12a499465b6554d7c8fbf58e355`, status `completed`, conclusion `success`.

The successful job includes:

- Validate project;
- Run JVM unit tests;
- Build signed validation APK;
- Prepare immutable validation artifact;
- Upload immutable Actions artifact.

The exact target workflow defines `Run JVM unit tests` as:

`gradle --no-daemon :app:testDebugUnitTest --stacktrace`

FULL JVM: PASS via exact-sha CI. No separate local full-JVM rerun is claimed by CHAT-3.

## Final classification

AUTHORITY RESOLVER STATE: PASS

MUTABLE ALIASING: PASS

STALE CACHE: PASS

TOCTOU: FAIL — per-resolution snapshot semantics are coherent after the authority gate, but canonical snapshot acquisition is a non-atomic campaign-then-World-Pack multi-read and can authorize an inactive/torn campaign authority under concurrent selection change.

READ-ONLY CAPABILITY: PASS

FAILURE ATOMICITY: PASS

ZERO AUTHORITATIVE MUTATION: PASS

PROVIDER STATE SECURITY: PASS

DETERMINISM: PASS (post-gate); blocked by non-atomic authority acquisition under concurrent selection mutation

IMMUTABILITY: PASS

PHASE-18 REGRESSION: PASS

PHASE-17 REGRESSION: PASS

FULL JVM: PASS (exact-sha CI; no local rerun claimed)

EXACT CI: PASS

NEW BLOCKERS: `P19-C3-TOCTOU-001 NON_ATOMIC_CAMPAIGN_WORLD_PACK_AUTHORITY_READ`

FINAL CHAT-3 VERDICT: FAIL

This does not globally accept Phase 19 and does not start Phase 20.
