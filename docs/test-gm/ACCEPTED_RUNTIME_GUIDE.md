# RPG OS — TEST GM ACCEPTED RUNTIME GUIDE

Status: NON-CANONICAL INDEX / MUST BE RECHECKED AGAINST CURRENT ROADMAP
Last refreshed: 2026-08-17

This file is only a navigation aid for a fresh Test GM. It is not an acceptance record and must never override `docs/RPG_OS_IMPLEMENTATION_ROADMAP.md` or canonical acceptance documents.

## Mandatory rule

At the start of every Test GM session, read the current roadmap and determine which phases are globally `ACCEPTED / COMPLETE` now.

Do not assume this list stays current forever.

## Current accepted baseline

Current canonical roadmap confirms Player Core phases **1–25 globally ACCEPTED / COMPLETE**.

Canonical accepted Player Core runtime through Phase 25:

`c028aa355d9b7e1663166a2fedb910c1a2dad795`

Exact acceptance CI for Phase 21–25:

- run #607
- ID `31968919354`
- conclusion `success`

Final exact-SHA revalidation:

- CHAT-4 / `WORK-20260816-016` — PASS
- CHAT-5 / `WORK-20260816-017` — PASS

Closed blocker:

- `P21-25-INVARIANT-BYPASS-01`

Phase 20 was separately accepted on runtime:

`38dafe5cc48c87f16218e346d9c0f9a96b6cee50`

Exact Phase-20 acceptance CI:

- run #578
- ID `31961047982`
- conclusion `success`

Phase 19 canonical runtime referenced by its acceptance record:

`5754f28ccd4f7c1f3522c0af6c34bcaf65e2dcf8`

## Accepted Phase 1–25 domains to inspect as needed

1. Unified Repository + stable UID
2. Campaign Source of Truth + FACT/BELIEF/NARRATIVE + provenance
3. Player State Contract: Persistent / Derived / Runtime
4. Dynamic StatDefinition / PlayerStat + ResourceDefinition / PlayerResource
5. DerivedValueResolver + modifier model
6. TalentProfile + PotentialProfile
7. Skill model
8. Technique model
9. Innate / Racial / Bloodline / Evolution runtime model
10. Inventory model
11. Equipment domain/loadout model
12. OwnershipRecord domain
13. Financial Ledger / Economy model
14. Assets / debts / obligations / net-worth model
15. DevelopmentProject model
16. PlayerCommand contract
17. PlayerChangeSet contract
18. PlayerDomainEngine orchestration
19. WorldRuleProvider contract
20. ProgressionEngine + Progression Ledger
21. Diminishing Returns + passive progression hooks
22. Player Invariant Validator + No-Retrogression
23. Unified Player ledgers + provenance integration within accepted Phase-23 scope
24. CharacterPanelSnapshot v2 as DERIVED_PRESENTATION
25. PlayerSnapshotBuilder + FULL / COMBAT / PROGRESSION / ECONOMY / SOCIAL / GM_CONTEXT derived projection profiles

The Test GM should treat phases 1–25 as accepted mechanics, but must inspect their current runtime contracts and canonical acceptance scope before using details. This guide is not a substitute for those contracts.

## Important accepted-scope boundaries

Phase 21–25 acceptance does **not** create or imply:

- Phase-26 Single Truth Mutation Path enforcement;
- TurnTransaction atomic commit/rollback;
- global commit/retry/idempotency guarantees;
- crash recovery / LAST VALID COMMIT;
- a second Player Engine;
- a second WorldRuleProvider;
- a global writable unified player ledger;
- NPC Knowledge authority;
- Temporal/Scheduler runtime;
- Mechanics Resolution integration;
- schema/migration changes.

Do not infer later-phase authority from Player Core acceptance.

## Phase 26 and later

Current roadmap identifies Phase 26 — Single Truth Mutation Path enforcement — as the next campaign-integrity stage. Its roadmap item is not globally COMPLETE; implementation/partial foundations must not be treated as accepted merely because code or documents exist.

Never classify Phase 26 or any later phase from this guide alone. Recheck the current roadmap and canonical acceptance evidence every session.

For mechanics owned by phases that are not globally `ACCEPTED / COMPLETE`, use the architecture fallback rules below.

## Fallback rule for unfinished phases

When a needed phase is not globally accepted:

1. read its intended design in `docs/RPG_OS_MASTER_ARCHITECTURE.md`;
2. preserve all accepted lower-layer invariants;
3. use the smallest conservative logical resolution sufficient to continue the playtest;
4. do not claim the result came from implemented runtime;
5. avoid irreversible/permanent state inventions when architecture does not determine them safely;
6. never create a second authority path;
7. preserve causal evidence and unresolved state so a future accepted mechanic can resolve it without fabricated history or double counting.

## Canonical acceptance references

- `docs/architecture/PHASE19_ACCEPTANCE.md`
- `docs/architecture/PHASE19_WORLD_RULE_PROVIDER_CANONICAL_SCOPE.md`
- `docs/architecture/PHASE19_DEFERRED_FINDINGS.md`
- `docs/architecture/PHASE20_ACCEPTANCE.md`
- `docs/architecture/PHASE21_25_ACCEPTANCE.md`

## Useful canonical references

- `docs/RPG_OS_MASTER_ARCHITECTURE.md`
- `docs/RPG_OS_IMPLEMENTATION_ROADMAP.md`
- `docs/GM_ENGINE_TARGET_ARCHITECTURE.md`
- `docs/PROJECT_WORK_PROTOCOL.md`
- `docs/architecture/CHAT_COORDINATION_POLICY.md`
- `docs/PARALLEL_WORK_COORDINATION.md`

For World Pack gameplay, discover and inspect the currently relevant World Pack/canon files from the repository rather than relying on this folder to duplicate them.
