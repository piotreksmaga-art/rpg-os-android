# WORK-20260814 — Phase 19 CHAT-4 Final Architecture / World-Agnostic Revalidation

Role: `CHAT-4 — INDEPENDENT ARCHITECTURE AUDITOR`

Validated runtime SHA: `48854043bdde9753830ffc20ff6a8e8a4d4299e1`

Audit type: REPORT ONLY. No production/test changes. Phase 20 not started.

# PHASE 19 ARCHITECTURE REVALIDATION: FAIL

## 1. Exact target / history

Fresh master immediately before report creation was `5d43d0b35b834a55e6ef9c19e6eec9756c1cfdc6`.

Comparison `48854043bdde9753830ffc20ff6a8e8a4d4299e1..5d43d0b35b834a55e6ef9c19e6eec9756c1cfdc6` is forward-only, with merge-base exactly the target and only two later files:

- `docs/audits/WORK-20260814-P19_CHAT1_WORLDRULEPROVIDER_IMPLEMENTATION_48854043.md`
- `docs/audits/WORK-20260814-P19_CHAT3_FINAL_INTEGRITY_REVALIDATION_48854043.md`

No production/test runtime exists after target.

`RUNTIME CHANGED AFTER TARGET: NO`

## 2. Canonical pipeline position

**FAIL due one bypass described in P19-ARCH-NULL-BINDING-01.**

When a World Pack binding is present, actual ordering is architecturally correct:

1. PlayerCommand structural/canonical validation;
2. campaign/actor checks;
3. Phase-18 command reference/scope validation;
4. Phase-19 `COMMAND_PRECHECK`;
5. internal typed resolution component;
6. Phase-18 draft reference/scope validation;
7. Phase-19 `DRAFT_EFFECT_CHECK`;
8. engine-owned PlayerChangeSet assembly;
9. existing Phase-17 PlayerChangeSet validation;
10. proposal return.

However `PlayerResolutionContext.worldPackBinding == null` causes `evaluateWorldRules()` to return immediately and skip both Phase-19 stages. The engine itself has no independent knowledge of whether the current campaign is actually bound to an active World Pack. Therefore the intended pipeline is not mandatory for a bound-world resolution path.

## 3. WorldRuleProvider responsibility

**PASS.**

`WorldRuleProvider` is a narrow internal legality extension point. It receives `WorldRuleRequest` and returns typed `WorldRuleDecision`. It cannot return PlayerChangeSet, TurnTransaction or committed state through its supported contract.

The request carries stage, World Pack binding, campaign/actor, canonical command/fingerprint, context fingerprint and optional immutable effect snapshot. No numeric mechanics API, progression calculation, invariant authority, transaction or persistence API is part of the provider contract.

## 4. Command / draft two-stage model

**PASS.**

`COMMAND_PRECHECK` and `DRAFT_EFFECT_CHECK` have distinct semantic roles:

- command precheck can reject world-illegal intent before mechanics/component resolution;
- draft effect check can reject a candidate effect that is illegal even when the original intent was legal.

The stages are encoded in `WorldRuleEvaluationStage`; precheck requires `effects == null`, draft check requires a concrete effect snapshot. The model is not redundant because draft-producing mechanics/components may introduce specific proposed effects that did not exist in the command.

## 5. World Pack binding

**FAIL due P19-ARCH-NULL-BINDING-01.**

The transient binding itself is minimal and correct in shape: `WorldPackRuleBinding(worldPackUid, worldPackVersion)`. Phase 19 does not add persisted World Pack selection. Existing application infrastructure already owns active World Pack selection separately.

The defect is not a duplicate authority but insufficient enforcement of the relationship: the resolution context accepts an optional binding supplied by its caller and does not prove that `null` is compatible with the campaign's actual active World Pack selection.

## 6. Null binding architecture

**FAIL — blocker.**

`PlayerResolutionContext.create(..., worldPackBinding: WorldPackRuleBinding? = null)` makes no-rule mode the default. `PlayerDomainEngine.evaluateWorldRules()` contains the effective behavior:

`val binding = context.worldPackBinding ?: return null`

Thus omission of binding means "skip all world legality" rather than a fail-closed distinction between a genuinely unbound legacy/Core context and a bound campaign.

The test suite deliberately preserves a Phase-18 compatibility path where `worldRules = false` supplies `null` and a command resolves successfully without provider evaluation. What is missing is an architectural gate proving that this mode cannot be used for a campaign whose canonical runtime selection has a World Pack.

## 7. Provider registry

**PASS, with one non-blocking future limitation.**

Selection is deterministic by World Pack UID and exact bound version is checked. Duplicate providers for a UID fail closed; a bound world with no provider fails with `WORLD_RULE_PROVIDER_MISSING`; incompatible version fails with `WORLD_RULE_PROVIDER_VERSION_MISMATCH`.

The current registry stores one provider per World Pack UID, so it cannot simultaneously hold multiple versions of the same UID. This is a constrained current design rather than a Phase-19 blocker because exact binding/version mismatch is explicit and deterministic; future multi-version coexistence may require keying by full `(uid, version)` if the runtime ever needs parallel versions.

## 8. Core world-agnostic

**PASS.**

The new Core contract contains no Naruto/Bleach-specific rules or mechanics. Provider, World Pack, rule, reason and evidence identifiers are opaque stable strings. Tests use generic `TEST-WORLD`/generic provider fixtures.

## 9. Rule provenance

**PASS.**

Core, not the provider, constructs `WorldRuleDecisionRecord` with provider UID/version, World Pack UID/version, stage, rule UID, optional reason UID, evidence UIDs, request fingerprint and decision fingerprint.

This is transient resolution evidence. Phase 19 does not create a new persistence table or rule ledger, so it does not prematurely absorb later event/ledger/persistence phases.

## 10. Replayability design

**PASS.**

`WorldRuleRequest` has a deterministic request fingerprint. `WorldRuleEffectSnapshot` explicitly canonicalizes all current typed change/event/ledger/warning payload fields. `WorldRuleDecisionRecord` deterministically fingerprints provider/version, World Pack/version, stage, request, rule/reason and sorted evidence.

Dedicated regression proves semantically equal DevelopmentProject effects produce equal fingerprints independent of object identity, while a semantic evidence change changes the fingerprint.

The design is sufficient for Phase-19 replay evidence without introducing a second general serialization framework.

## 11. Mechanics / progression separation

**PASS.**

WorldRuleProvider answers legality. It does not compute stat gains, resource recovery, damage, training output, progression factors, diminishing returns or other mechanics.

No ProgressionEngine implementation is introduced. A later ProgressionEngine can operate after command/world legality and before final draft-effect legality/invariant/transaction stages without requiring a redesign of provider decisions themselves.

## 12. Invariant separation

**PASS.**

Aggregate/domain invariants remain downstream. Phase 19 does not implement no-retrogression, conservation, aggregate ownership, financial accounting invariants or final transaction validation.

## 13. TurnTransaction / COMMIT separation

**PASS.**

There is no TurnTransaction, database write, StatePatch, repository writer or commit callback in `WorldRuleProvider`, `WorldRuleRequest`, `WorldRuleProviderRegistry` or the Phase-19 engine integration. A resolved PlayerChangeSet remains a proposal.

## 14. Phase-18 preservation

**PASS.**

Phase-18 existence/scope validation still executes before provider legality on commands and before draft legality on component-produced effects. `UNKNOWN_REFERENCE` and `WRONG_CAMPAIGN_REFERENCE` remain distinct Phase-18 outcomes.

Equipment slot remains Class-B definition identity and is not routed through campaign reference lookup. Ownership preserves D/A/A/A split. Finance preserves typed account/currency references. No generic UID heuristic was introduced.

## 15. PlayerDomainEngine god-object risk

**PASS.**

The engine gained orchestration for two rule stages plus evidence propagation, but world-rule request/decision/fingerprint logic lives in `WorldRuleProvider.kt`. PlayerDomainEngine does not embed world-specific rule branches, mechanics, persistence, UI or Android integration.

## 16. Dependency direction

**PASS.**

World-rule Core types depend only on Core/JDK/Kotlin types and do not import Android database/UI implementation. Existing active World Pack selection remains outside Core and is not duplicated as persistence inside Phase 19.

## 17. Error model

**PASS.**

Expected legality rejection is typed as `PlayerResolutionOutcome.Rejected` with `WORLD_RULE_REJECTED` and a stable reason UID. Provider faults are structural (`WORLD_RULE_PROVIDER_FAILURE`), malformed decisions are structural (`WORLD_RULE_PROVIDER_MALFORMED_DECISION`), and Phase-18 reference rejection remains its own typed reason. Normal rejection is not inferred from exception prose.

## 18. Missing / version semantics

**FAIL because missing *binding* is not fail-closed for bound worlds.**

Once a non-null binding exists, missing provider and version mismatch are correctly fail-closed. The gap is one level earlier: a caller can omit the binding entirely and the engine treats that as no-rule compatibility mode without knowing whether omission contradicts canonical world selection.

## 19. Phase-20 readiness

**FAIL only because P19-ARCH-NULL-BINDING-01 must be closed first.**

Provider/mechanics boundaries themselves are clean. But ProgressionEngine must not be added on top of a pipeline where a bound-world caller can accidentally bypass all world legality by constructing a context with the default null binding. Once binding/no-binding authority is explicit and fail-closed, Phase-20 integration can proceed without redesigning provider semantics.

## 20. Test architecture

**FAIL due missing bound-world/null-binding negative gate.**

The suite strongly covers legal/reject/fault paths, command-before-provider reference checks, draft checks, deterministic decisions, missing provider, version mismatch, zero mutation, world-agnostic contract and Phase-18/17 regressions.

However it explicitly tests the no-rule mode (`context(worldRules = false)`) as successful without also testing that a canonically bound campaign cannot enter that mode. Because PlayerDomainEngine has no canonical binding authority input besides the nullable context field, such a negative test cannot currently be expressed at the engine boundary.

## 21. Exact CI / full JVM

Verified exact canonical workflow:

- workflow: `Build & Release RPG OS ALPHA`
- run number: `452`
- run ID: `31801538074`
- head SHA: `48854043bdde9753830ffc20ff6a8e8a4d4299e1`
- status: `completed`
- conclusion: `success`

Build job confirms `Run JVM unit tests` SUCCESS, plus validation, signed APK build, artifact preparation/upload and release update.

`FULL JVM: PASS`

`EXACT CI: PASS`

## 22. Architecture blocker

### P19-ARCH-NULL-BINDING-01 — BOUND_WORLD_RULE_BYPASS_VIA_NULL_CONTEXT_BINDING

**Current architecture**

`PlayerResolutionContext.create()` exposes `worldPackBinding: WorldPackRuleBinding? = null`. `evaluateWorldRules()` treats null as an unconditional no-op. Existing application infrastructure separately has an active World Pack selector, but PlayerDomainEngine receives no authoritative "this campaign is bound/unbound" fact other than the caller-supplied nullable field.

**Counterexample / future integration scenario**

A production caller resolves a command for a campaign that is running under an active World Pack but constructs `PlayerResolutionContext` without explicitly passing `worldPackBinding` (or deliberately uses the compatibility helper/default). Phase-18 reference validation succeeds; both Phase-19 legality stages are skipped; the component can produce a draft and PlayerDomainEngine can return a PlayerChangeSet. Phase 20 would then consume a proposal that never passed the world's legality layer.

**Expected architecture**

No-rule compatibility must be an explicit, authoritative mode that cannot be confused with a bound campaign. Examples of acceptable minimal direction:

- construct the resolution binding/mode at the canonical campaign/World-Pack selection boundary and make bound contexts require `WorldPackRuleBinding`; or
- replace nullable ambiguity with a typed mode such as `Bound(WorldPackRuleBinding)` vs explicit `UnboundLegacy/Core`, with the production composition root allowed to create `Unbound` only when canonical campaign/world selection says so.

For a bound mode, missing provider/version remains fail-closed as today.

**Actual consequence**

World legality is optional caller metadata rather than a guaranteed pipeline stage for bound worlds. The canonical Phase-19 boundary can therefore be bypassed without touching the provider registry or triggering a structural error.

**Minimal corrective direction**

Harden only context/binding construction and composition semantics so that bound-world resolution cannot supply null. Do not redesign provider decisions, mechanics, persistence or Phase-20 behavior.

## 23. Final matrix

```text
PHASE 19 ARCHITECTURE REVALIDATION: FAIL
ROLE: CHAT-4
VALIDATED RUNTIME SHA: 48854043bdde9753830ffc20ff6a8e8a4d4299e1
FRESH MASTER: 5d43d0b35b834a55e6ef9c19e6eec9756c1cfdc6
RUNTIME CHANGED AFTER TARGET: NO
CANONICAL PIPELINE POSITION: FAIL
WORLDRULEPROVIDER RESPONSIBILITY: PASS
COMMAND/DRAFT TWO-STAGE MODEL: PASS
WORLD PACK BINDING: FAIL
NULL BINDING ARCHITECTURE: FAIL
PROVIDER REGISTRY: PASS
CORE WORLD-AGNOSTIC: PASS
RULE PROVENANCE: PASS
REPLAYABILITY DESIGN: PASS
MECHANICS SEPARATION: PASS
PROGRESSION SEPARATION: PASS
INVARIANT SEPARATION: PASS
TURNTRANSACTION / COMMIT SEPARATION: PASS
PHASE-18 REFERENCE LAYERING: PASS
PLAYERDOMAINENGINE GOD-OBJECT RISK: PASS
DEPENDENCY DIRECTION: PASS
ERROR MODEL: PASS
MISSING/VERSION SEMANTICS: FAIL
PHASE-20 READINESS: FAIL
TEST ARCHITECTURE: FAIL
PHASE 3–18 REGRESSION: PASS
FULL JVM: PASS
EXACT CI: PASS
NEW ARCHITECTURE BLOCKERS: P19-ARCH-NULL-BINDING-01
FINAL CHAT-4 VERDICT: FAIL
```

This verdict applies only to runtime `48854043bdde9753830ffc20ff6a8e8a4d4299e1`. It does not globally accept Phase 19 and does not start Phase 20.