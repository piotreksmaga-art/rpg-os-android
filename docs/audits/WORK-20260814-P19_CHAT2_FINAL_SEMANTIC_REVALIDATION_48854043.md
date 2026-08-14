# WORK-20260814 — PHASE 19 CHAT-2 FINAL SEMANTIC / WORLD-RULE CONTRACT REVALIDATION

ROLE: CHAT-2 — Independent Semantic Auditor

VALIDATED RUNTIME SHA: `48854043bdde9753830ffc20ff6a8e8a4d4299e1`

PHASE-18 ACCEPTED BASELINE: `b53ae2c5b765cba49f31a0f88e7865cf1df8d5a7`

## Verdict

**PHASE 19 SEMANTIC REVALIDATION: FAIL**

Audit only. No production code, tests, config/workflow or runtime was modified. No fix was implemented. Phase 20 was not started.

The bound-World-Pack path is otherwise semantically strong: Phase-18 reference validation precedes rules, command and draft legality are distinct, rejection is typed, provider faults are structural, provider selection is deterministic/fail-closed, canonical fingerprints are field-based, current draft effect families are represented, and Phase-18 reference semantics remain intact.

One contract blocker remains: **null `worldPackBinding` is a supported public context-construction path that unconditionally skips all WorldRuleProvider evaluation, without an enforceable distinction between legitimate generic/legacy Phase-18-only context and an active World Pack whose binding was accidentally omitted.** Under the requested audit criterion, this is a blocker.

## SHA discipline

The target exists and is the merge base/ancestor of fresh master. Repeated `48854043..master` comparisons showed only report files under `docs/audits/`; no production/test/config/workflow runtime changed after target.

**RUNTIME CHANGED AFTER TARGET: NO.**

The Phase-18 baseline → Phase-19 target production diff is confined to `PlayerDomainEngine.kt` and the new `WorldRuleProvider.kt`; Phase-19 tests are in `WorldRuleProviderPhase19Test.kt` and `WorldRuleProviderDeterminismRegressionTest.kt`. No PlayerChangeSet schema/codec, persistence, progression, transaction or aggregate-invariant file was changed by Phase 19.

## WorldRuleProvider contract

`WorldRuleProvider` is an internal trusted Core legality extension point. It receives a read-only `WorldRuleRequest` and returns a typed `WorldRuleDecision`. It receives no DB/store/repository/TurnTransaction/StatePatch/commit writer. Its API does not calculate damage, training gains, passive growth, mastery/progression, derived stats or random outcomes.

This responsibility boundary is correct when a binding is present, but not globally enforceable because null binding bypasses the legality layer.

**WORLDRULEPROVIDER CONTRACT: FAIL.**

## Command precheck

Engine order is canonical command validation/canonicalization → context campaign/actor checks → Phase-18 command reference validation → `COMMAND_PRECHECK` → internal component resolution.

For bound contexts: legal rule continues; normal rule rejection returns typed `WORLD_RULE_REJECTED`; unknown reference returns `UNKNOWN_REFERENCE` before provider; wrong campaign returns `WRONG_CAMPAIGN_REFERENCE` before provider. P19-04/05 use an explode-if-called provider, genuinely proving the provider is not invoked first.

**COMMAND PRECHECK: PASS.**

## Draft effect check

After component resolution the engine performs Phase-18 draft reference validation → `WorldRuleEffectSnapshot` → `DRAFT_EFFECT_CHECK` → only after ALLOW constructs the PlayerChangeSet and runs the existing validator.

P19-06 proves command ALLOW does not bless arbitrary resolved effects: the first decision is ALLOW and the second is REJECT at `DRAFT_EFFECT_CHECK`, with no proposal escape.

**DRAFT EFFECT CHECK: PASS.**

## Typed outcome / structural fault

`WorldRuleDecision.Allowed` and `.Rejected` are distinct normal outcomes. Rejection retains stable rule/reason/evidence identity and maps to `PlayerResolutionOutcome.Rejected(WORLD_RULE_REJECTED)`. Provider exceptions use `WORLD_RULE_PROVIDER_FAILURE`; malformed decisions and input mutation use structural channels. P19-13 directly distinguishes rejection from fault.

**TYPED ALLOW/REJECT: PASS.**

**REJECTION VS STRUCTURAL FAULT: PASS.**

## Rule identity / request replayability

Decision identity includes provider UID/version, World Pack UID/version, evaluation stage, request fingerprint, rule UID, reason UID or explicit ALLOW sentinel, and evidence UIDs. Request identity binds stage, binding, campaign/actor, canonical command UID/kind/fingerprint, context fingerprint and draft-effect fingerprint where applicable.

Canonical replay identity does not depend on data-class `toString()`, object identity, `hashCode()`, memory addresses, unordered map/set traversal or locale formatting. Tokens are length-prefixed and effect semantics are field-encoded. P19-12 proves provider-version changes alter decision/proposal identity; P19-29/30 prove independently allocated equivalent project effects fingerprint equally while changed evidence changes fingerprint.

**RULE IDENTITY: PASS.**

**REQUEST REPLAYABILITY: PASS.**

## Effect snapshot completeness

The snapshot canonicalizer is exhaustive across current PlayerDomainChange families. It preserves common change identity plus payload semantics including subjects and typed targets, deltas/progress, skill/technique IDs, inventory item/quantity, equipment slot/operation/item, all five finance terms, asset kind+UID+lifecycle, ownership record + full asset/from/to owner tuples + share, condition/runtime semantics, and development-project UID/work-result/progress/evidence.

Event snapshots preserve event identity/kind, actor, targets, causal changes, effective order and DomainEffect payload. Ledger snapshots preserve ledger identity/kind, causal changes and all financial transfer terms. Warnings are represented.

No current meaningful effect family was found to collapse because a target, delta, currency, asset/owner kind, project semantics, equipment identity or analogous field was omitted.

**EFFECT SNAPSHOT COMPLETENESS: PASS.**

## Provider selection

Registry selection is by opaque World Pack UID, not universe branching. Exact provider selects deterministically; duplicate provider fails `DUPLICATE_WORLD_RULE_PROVIDER`; bound context with no provider fails `WORLD_RULE_PROVIDER_MISSING`; different World Pack does not select; matching UID with wrong version fails `WORLD_RULE_PROVIDER_VERSION_MISMATCH`; copied/keyed registry state makes registration order irrelevant.

**PROVIDER SELECTION: PASS.**

**DUPLICATE PROVIDER: PASS.**

**MISSING PROVIDER: PASS for non-null binding.**

**VERSION MISMATCH: PASS.**

## Null binding semantics — BLOCKER

`PlayerResolutionContext` is public and its public `create(...)` factory exposes nullable `worldPackBinding` with default `null`.

`PlayerDomainEngine.evaluateWorldRules()` starts by returning no evaluation when `context.worldPackBinding` is null. P19-28 deliberately proves this supported Phase-18 compatibility path resolves without rules.

A generic/legacy/no-rule compatibility mode can be valid. The blocker is that the supported API contains **no enforceable discriminator** proving a null-binding context is such a mode. A caller constructing context for a campaign that should have an active World Pack can omit the binding; the engine cannot distinguish omission from legitimate compatibility and silently bypasses both command and draft legality.

This directly meets the audit's explicit blocker condition.

**NULL BINDING SEMANTICS: FAIL — BLOCKER.**

## Phase-18 semantics

Reference validation remains outside and before WorldRuleProvider. Representative semantics remain:

- Equipment slot UID is B definition identity, not campaign-looked-up.
- `ownershipRecordUid` is D proposal-local identity.
- Owned asset/fromOwner/toOwner remain full existing typed A references.
- finance source/destination account and currency remain Phase-18 typed references.

The provider neither duplicates nor overrides those lookups.

**PHASE-18 REFERENCE ORDERING: PASS.**

**PHASE-18 REFERENCE SEMANTICS: PASS.**

## Separation from mechanics / future phases

WorldRuleProvider only decides legality. Numeric values in its request/effect snapshot describe proposed semantics; the provider API does not output general mechanics calculations.

No Phase-20 ProgressionEngine/ledger, diminishing returns, training reward, passive growth or mastery progression was introduced. No Phase-22 aggregate invariant engine was introduced. Existing final PlayerChangeSet validation remains separate.

**MECHANICS SEPARATION: PASS.**

**PROGRESSION SEPARATION: PASS.**

**INVARIANT VALIDATOR SEPARATION: PASS.**

## PlayerChangeSet / provenance

Phase 19 did not change PlayerChangeSet schema or codec. Existing `ChangeSetProvenance.worldRuleProviderUid` is reused. Full rule decision records remain transient in `PlayerResolutionEvidence`.

Proposal UID derivation incorporates canonical command, context fingerprint, component identity/version and every rule decision fingerprint. Equivalent deterministic rule decisions therefore preserve proposal identity, while semantically changed decision/provider identity changes it.

**PLAYERCHANGESET / PROVENANCE: PASS.**

## Determinism / immutability / mutation authority

No hidden time/random/object identity is used by the rule layer. Fixed canonical command/context/binding/provider/effects/evidence yields deterministic request, decision and proposal semantics.

Decision evidence, decision-record evidence, effect collections and registry collections are defensively copied/frozen; binding is immutable. Caller mutation cannot alter an existing decision fingerprint.

Supported provider inputs expose no authoritative writer. Normal rejection/fault occurs before proposal construction; P19-03/15/16 observe no authority fixture mutation and P19-27 checks supported contract types for writer leakage. Malicious arbitrary same-module JVM code is outside the declared contract and not treated as a sandbox target.

**DETERMINISM: PASS.**

**IMMUTABILITY: PASS.**

**ZERO AUTHORITATIVE MUTATION: PASS.**

## World-agnostic Core

No Naruto/Bleach/chakra/reiatsu/Sharingan/Kido/Raiton/Sonido/Hollow/Shinigami hardcoding was found in Phase-19 production Core. Test-only `TEST-WORLD` is not production branching.

**CORE WORLD-AGNOSTIC: PASS.**

## Test quality

P19-01..30 predominantly exercise real production paths. High-risk tests are meaningful: P19-04/05 prove provider-not-called before reference rejection; P19-06 reaches genuine draft rejection; P19-08/09/10 cover duplicate/missing/version mismatch; P19-11 determinism; P19-13 rejection vs fault; P19-27 capability surface; P19-29/30 independently allocated DevelopmentProjectChange effects. The inspected P19 tests contain no `@Ignore`/`@Disabled`.

However, the suite codifies null binding as a supported compatibility mode without modeling or testing an enforceable distinction that prevents a World-Pack-required campaign from using it. P19-28 proves the bypass path works, not that only legitimate generic/legacy contexts can enter it.

**TEST QUALITY: FAIL** due to the unguarded null-binding acceptance gap.

## Phase 3–18 regression

Representative accepted locks remain intact and are included in exact CI: equipment/ownership/finance references, zero ProjectProgressDelta, zero ExactLongDelta rejection, composite identity, OwnedAssetRef identity, finance/ledger exactness, serialization/fingerprint and Phase-18 compatibility behavior.

**PHASE 3–18 REGRESSION: PASS.**

## Exact CI

Verified GitHub Actions:

- workflow `Build & Release RPG OS ALPHA`
- run #452
- run ID `31801538074`
- head SHA `48854043bdde9753830ffc20ff6a8e8a4d4299e1`
- status `completed`
- conclusion `success`
- exact-SHA checkout confirmed in logs
- project validation success
- full JVM `:app:testDebugUnitTest` BUILD SUCCESSFUL
- signed release APK assembly success
- release-file preparation success
- Actions artifact upload success (artifact ID `9219582395`)
- existing release asset update success

Green CI does not negate the semantic blocker because the required null-binding distinction is not enforced/tested.

**FULL JVM: PASS.**

**EXACT CI: PASS.**

## New blockers

1. **NULL WORLD-RULE BINDING BYPASS** — public `PlayerResolutionContext.create()` defaults `worldPackBinding` to null; the engine treats null as an unconditional no-rule short circuit; no enforceable campaign/context property distinguishes legitimate Phase-18-only compatibility from an active World Pack that must run Phase-19 legality. A supported caller can omit the binding and bypass both rule stages.

## Final CHAT-2 verdict

**FAIL** for Phase-19 semantic/world-rule contract acceptance on exact runtime `48854043bdde9753830ffc20ff6a8e8a4d4299e1`.

This is not global Phase-19 acceptance. Phase 20 was not started.