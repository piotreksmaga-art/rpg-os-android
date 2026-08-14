# WORK-20260814 — PHASE 19 COORDINATOR KICKOFF

ROLE: COORDINATOR

PHASE: 19 — WorldRuleProvider contract

ACCEPTED PHASE-18 BASELINE: `b53ae2c5b765cba49f31a0f88e7865cf1df8d5a7`

PHASE-18 GLOBAL ACCEPTANCE COMMIT: `9d8e8ad0fdbf275e44187b84043bddc58701a3b0`

## Canonical basis

The canonical roadmap places `19. WorldRuleProvider contract` immediately after `18. PlayerDomainEngine orchestration` and before `20. ProgressionEngine + Progression Ledger`.

The MASTER pipeline is:

`Player/World Action -> PlayerCommand -> PlayerDomainEngine -> Rule Pipeline -> WorldRuleProvider -> Mechanics -> InvariantValidator -> PlayerChangeSet -> TurnTransaction -> COMMIT -> PlayerSnapshotBuilder`

Core must remain universe-agnostic. Naruto, Bleach and future settings remain World Packs. World-specific legality belongs behind the WorldRuleProvider boundary rather than being hardcoded into Core.

## Coordinator start decision

**PHASE 19: STARTED**

The first Phase-19 operation is an audit/contract-design pass, not speculative implementation.

Repository code search at kickoff returned no indexed `WorldRuleProvider` implementation. This is treated only as an audit signal; CHAT-1 must inspect the actual fresh master tree before deciding MISSING/PARTIAL or introducing files.

## WORK ITEM — CHAT-1 / P19 implementation owner

Goal: audit the current fresh master and implement the smallest production-grade generic `WorldRuleProvider` contract and canonical integration point required by Phase 19.

Required scope:

1. Start from fresh `master`; record exact SHA and verify the accepted Phase-18 runtime remains an ancestor.
2. Read current MASTER/ROADMAP and Phase-18 acceptance/revalidation reports from the repository.
3. Audit actual `PlayerDomainEngine`, resolution components/context/outcomes, command/change models, existing World Pack/definition abstractions, and any rule/mechanics hooks.
4. Reconstruct the complete Phase-19 legality surface from actual command/change semantics. Do not infer legality from every `*Uid` field.
5. Define a universe-agnostic WorldRuleProvider contract with typed deterministic outcomes.
6. Preserve Phase-18 existence/scope validation as a separate concern. Phase 19 decides world-rule legality, not campaign existence lookup.
7. Integrate WorldRuleProvider at the canonical PlayerDomainEngine rule-pipeline boundary without allowing direct authoritative mutation.
8. Provider input must be deterministic/read-only and sufficient for legality decisions; no repository/database writer capability may be exposed.
9. Provider output must be proposal/decision data only. It must not commit state, mutate authoritative state, bypass PlayerChangeSet validation, or own TurnTransaction.
10. Do not implement ProgressionEngine, diminishing returns, No-Retrogression, TurnTransaction, World Pack hardening packs (80–84), AI/GM integration, or unrelated frontend work.
11. Do not hardcode Naruto/Bleach concepts into Core. World-specific rules are data/provider implementations outside generic Core.
12. Add production-path tests for allow/reject behavior, provider absence/default semantics if applicable, deterministic repeatability, no authoritative mutation, provider exception/fault behavior, and preservation of Phase-18 reference/scope ordering.
13. Run the complete `:app:testDebugUnitTest` gate and canonical Build & Release workflow on the exact candidate SHA.
14. Do not mark Phase 19 COMPLETE. Return exact runtime SHA, CI evidence, changed files, contract surface, unresolved questions/blockers, and report-only evidence for independent revalidation.

Forbidden scope:

- Phase 20+ implementation;
- world-specific Naruto/Bleach rule packs except minimal test doubles/fixtures needed to prove the generic contract;
- direct DB/repository writes from WorldRuleProvider;
- generic `*Uid` legality heuristics;
- changing accepted Phase-18 semantics merely to simplify Phase 19;
- redesigning PlayerDomainEngine into a god object;
- moving COMMIT authority into the provider;
- history rewrite/reset/force-push/rebase of published master history.

## Required semantic questions for CHAT-1

The implementation report must explicitly answer:

- What exact input does WorldRuleProvider receive?
- Does it evaluate commands, resolved drafts, or both, and why?
- What is the typed ALLOW/REJECT/FAULT model?
- How are rule IDs/reasons/provenance represented deterministically?
- How does Core select the correct provider/World Pack without hardcoding a universe?
- What happens when no provider exists, the provider is incompatible, or a rule cannot decide?
- Which legality belongs to Phase 19 versus later Mechanics/InvariantValidator/Progression phases?
- How are rule decisions replayable/fingerprintable?
- How is provider state/capability constrained so it cannot mutate authoritative state?
- How are Phase-18 command/draft reference checks ordered relative to WorldRuleProvider evaluation?

## Independent validation plan after CHAT-1 candidate

A Phase-19 runtime cannot be globally accepted from the implementation chat alone. After an exact-green candidate is established, coordinator will require fresh independent review of the same exact runtime SHA:

- CHAT-2 — semantic/world-rule contract revalidation;
- CHAT-3 — integrity/security/authority-boundary revalidation;
- CHAT-4 — architecture/dependency/world-agnostic revalidation;
- CHAT-5 — complete correctness/regression review.

Any blocker found by an independent review returns Phase 19 to CHAT-1 for the smallest forward-only correction, followed by a fresh exact CI and fresh 4× revalidation of the new runtime.

## Acceptance gate

Phase 19 may become ACCEPTED only when all of the following hold for one exact runtime SHA:

- implementation/integration exists;
- Core remains universe-agnostic;
- WorldRuleProvider cannot mutate authoritative state or commit;
- typed deterministic legality outcomes are integrated into the canonical PlayerDomainEngine pipeline;
- Phase-18 reference/scope semantics remain intact;
- tests cover core rule-boundary invariants;
- full JVM succeeds;
- canonical exact CI succeeds on that SHA;
- post-target commits before global acceptance are report-only;
- CHAT-2/3/4/5 all independently PASS that exact SHA;
- coordinator performs the final global acceptance decision.

## Current status

PHASE 18: **ACCEPTED**

PHASE 19: **STARTED / CHAT-1 AUDIT + IMPLEMENTATION AUTHORIZED**

PHASE 20: **BLOCKED UNTIL PHASE 19 GLOBAL ACCEPTANCE**
