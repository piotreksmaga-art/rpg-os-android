# RPG OS — TEST GM ACCEPTED RUNTIME GUIDE

Status: NON-CANONICAL INDEX / MUST BE RECHECKED AGAINST CURRENT ROADMAP

This file is only a navigation aid for a fresh Test GM. It is not an acceptance record and must never override `docs/RPG_OS_IMPLEMENTATION_ROADMAP.md` or canonical acceptance documents.

## Mandatory rule

At the start of every Test GM session, read the current roadmap and determine which phases are globally `ACCEPTED / COMPLETE` now.

Do not assume this list stays current forever.

## Accepted baseline known when this harness was created

At creation time, phases 1–19 had been globally accepted, with Phase 19 explicitly accepted on canonical runtime:

`5754f28ccd4f7c1f3522c0af6c34bcaf65e2dcf8`

Phase 19 acceptance evidence lives in:

- `docs/architecture/PHASE19_ACCEPTANCE.md`
- `docs/architecture/PHASE19_WORLD_RULE_PROVIDER_CANONICAL_SCOPE.md`
- `docs/architecture/PHASE19_DEFERRED_FINDINGS.md`

The Test GM should treat accepted phases 1–19 as implemented mechanics, but must inspect their current runtime surfaces rather than rely on summaries here.

## Accepted Phase 1–19 domains to inspect as needed

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

## Phase 20 and later

Never classify a later phase from this guide alone.

At harness creation time, a Phase-20 candidate existed in current master history, but global acceptance still required coordinator decision and independent post-audits. Therefore the Test GM must check the current roadmap/acceptance records before deciding whether Phase 20 is runtime or architecture fallback.

The same rule applies to all later phases.

## Fallback rule for unfinished phases

When a needed phase is not globally accepted:

1. read its intended design in `docs/RPG_OS_MASTER_ARCHITECTURE.md`;
2. preserve all accepted lower-layer invariants;
3. use the smallest conservative logical resolution sufficient to continue the playtest;
4. do not claim the result came from implemented runtime;
5. avoid irreversible/permanent state inventions when architecture does not determine them safely;
6. never create a second authority path.

## Useful canonical references

- `docs/RPG_OS_MASTER_ARCHITECTURE.md`
- `docs/RPG_OS_IMPLEMENTATION_ROADMAP.md`
- `docs/GM_ENGINE_TARGET_ARCHITECTURE.md`
- `docs/PROJECT_WORK_PROTOCOL.md`
- `docs/architecture/CHAT_COORDINATION_POLICY.md`
- `docs/PARALLEL_WORK_COORDINATION.md`

For World Pack gameplay, discover and inspect the currently relevant World Pack/canon files from the repository rather than relying on this folder to duplicate them.
