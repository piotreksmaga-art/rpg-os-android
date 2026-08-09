# RPG OS — PHASE 0 BASELINE AUDIT

Status: COMPLETE
Architecture: `docs/RPG_OS_MASTER_ARCHITECTURE.md`
Canonical roadmap: `docs/RPG_OS_IMPLEMENTATION_ROADMAP.md`
Supporting evidence: `docs/PHASE_0_MUTATION_PATH_AUDIT.md`, `docs/PHASE_0_PLAYER_STATE_AUDIT.md`.

## Phase 0 completion

### [x] 0.1 Current master + recent commits audit
Repository history, recent GM/snapshot changes and current documentation baseline were inspected. Current code is treated as the implementation source of truth; old TODOs are not assumed current.

### [x] 0.2 GitHub Actions / build baseline
Configured `Build & Release RPG OS ALPHA` pipeline compiles/signs the current Android project. Run #40 for the classified roadmap baseline completed SUCCESS.

Important limitation: CI currently builds/releases APK but does not execute a canonical domain/invariant test suite.

### [x] 0.3 Database/schema/migration inventory
The audit identified physical/runtime stores and the schema surfaces actively used by current code:
- `rpg_core.db`, campaign `campaign.db`, World Pack `world.db`, packaged campaign/worldpack ZIPs;
- campaign/player: campaign_meta, campaign_calendar, entity_positions, character_status_snapshot, character_stats, character_skills, character_techniques, character_inventory, character_finances, financial_transactions, injuries, relationships, organizations, goals;
- narrative/intelligence: chapter_manifests_v2, story_threads, story_beats, decision_points, consequence_links, quests/missions, continuity_checks, future_world_pressure, active_world_events;
- knowledge/memory/NPC: narrative_memory_index, information_facts, information_knowledge, npc_memories_v2, npc_beliefs where present, npc_schedules, npc_decisions, npc_action_candidates;
- timeline/divergence: timeline_divergences, gm_timeline_alerts and timeline/event data consumed by readers;
- Core registry: source_of_truth_registry, table_registry;
- migration/runtime support: rpgos_schema_migrations, visual library schema and AutoRepair-created support tables.

Phase 0 does not claim every legacy column is already normalized. Before touching/migrating a concrete domain table, implementation work must perform a fresh PRAGMA/table-level inspection and migration compatibility check. This is an implementation precondition, not an unresolved architecture-selection blocker.

### [x] 0.4 Existing tests inventory
Confirmed:
- no standard `app/src/test` tree,
- no standard `app/src/androidTest` tree,
- ad-hoc tools include `tools/long_campaign_stress_test.py`, `tools/static_audit.py`, package SQLite integrity validation and historical reports.

Conclusion: test infrastructure is substantially below MASTER Definition of Done.

### [x] 0.5 Existing backend/domain systems map
Confirmed current runtime components and responsibilities:
- `LocalGameStore` — central proto-facade over active campaign/world/core DBs;
- `CampaignSelectionManager` — active campaign/world selection;
- `SourceOfTruthRegistry` — writable/read-only table policy;
- `StatePatchEngine` — transactional low-level SQL patch application;
- `ContextBuilder` — structured ContextBundle retrieval;
- `CharacterPanelReader` / legacy `CharacterPanelSnapshot` — current player read model;
- `ChapterSaveManager` — chapter manifest/hash persistence;
- `BackupManager`, `RestoreManager`, `UpdateBackupManager` — file-copy backup/restore paths;
- `MigrationManager`, `AutoRepairEngine` — current schema/runtime maintenance;
- `BackendClient` + `backend/app.py` — structured GM turn API;
- NPC/world/social/technique/mission readers;
- diagnostics, package management, application/content update infrastructure.

### [x] 0.6 Full gap map 1–84
All roadmap entries now have an explicit COMPLETE/PARTIAL/MISSING classification in `RPG_OS_IMPLEMENTATION_ROADMAP.md`. No `[?] AUDIT REQUIRED` item remains as a blocker to selecting the next architectural dependency.

## Critical findings

### A. Narration is exposed before commit
`RpgOsViewModel.send()` appends GM narration before applying/validating `StatePatch`. If the patch fails, the player can still see narration describing a reality that was never committed.

Target invariant violated: `AI OUTPUT != COMMITTED REALITY`.

### B. chapter_events are currently dropped
Backend and Android client parse `chapter_events`, but the current `send()` mutation path does not persist them into a unified Event Store.

### C. StatePatch transactionId is not idempotency protection
`transactionId` exists in the contract, but current application does not persist/check committed transaction IDs. Retry/double-apply protection is therefore missing.

### D. Player identity is not authoritative
`ContextBuilder` heuristically guesses player UID from skills/techniques/finances/positions. Legacy CharacterPanel reads status with `LIMIT 1`. Multi-character/multi-campaign correctness requires one explicit active player identity.

### E. Campaign identity leaks/hardcodes
`CampaignSelectionManager` supports active campaigns, but:
- `BackupManager` targets `Naruto_Default.campaign`,
- `BackendClient` sends `campaign_id = naruto-default`.

This is the earliest concrete boundary defect in Unified Repository work.

### F. Existing transaction/repository systems should be evolved, not replaced
`LocalGameStore`, `StatePatchEngine` and `SourceOfTruthRegistry` are real foundations. The target architecture should wrap/refactor/integrate them rather than create parallel replacements.

### G. Existing snapshot retention is useful but incomplete
Six automatic `chapter_*` backups are retained. The pruning naming rule excludes manual/pre-restore copies as intended, but BackupManager must first become active-campaign aware before roadmap item 34 can become COMPLETE.

### H. Knowledge/time/memory have substantial schema scaffolding but limited enforcement
Knowledge facts, NPC knowledge/memories/schedules/decisions, calendar/timeline, divergence, threads and pressures exist. Missing are the canonical acquisition validator, perspective isolation, temporal historical resolver, scheduler orchestration, memory consolidation and semantic/vector retrieval engines.

### I. Later intelligence layers are mostly not implemented
Intent Parser, Turn Planner, Context Budget, iterative retrieval, PlayerDomainEngine, ProgressionEngine, AiProvider abstraction, Mechanics Resolution, Counterfactual Guard, narrative Repair Pass, Time Skip Processor, Director, pacing, anti-repetition, style profile, Replay Debugger and World Simulation LOD are not present as integrated target-runtime systems.

## First implementation dependency after Phase 0

### Roadmap 1 — Unified Repository + active campaign identity

Do NOT start by creating the whole PlayerDomainEngine.

First safe delta:
1. define one authoritative active campaign reference/identity contract;
2. make existing `LocalGameStore`/selection infrastructure the starting point rather than replacing it;
3. route backup/snapshot/restore and backend campaign identity through the active campaign;
4. remove runtime hardcodes `Naruto_Default.campaign` / `naruto-default` while preserving Naruto default compatibility;
5. add the first real repository/persistence tests and execute them in CI before claiming item 1 COMPLETE.

After that, continue with the earliest remaining dependency in the canonical roadmap.

## Phase 0 verdict

PHASE 0 IS COMPLETE.

We now know:
- what already exists,
- which systems are partial rather than absent,
- where current truth/integrity invariants are violated,
- which major target systems are missing,
- which legacy foundations must be preserved,
- and the exact first implementation boundary.

No production/runtime code was intentionally changed during Phase 0; only canonical audit/roadmap documentation was updated.
