# RPG OS — PHASE 0 BASELINE AUDIT

Status: ACTIVE AUDIT
Baseline audited: master `5c663dc0ec9df6ae195fbe54a179d250da8fd412`
Architecture: `docs/RPG_OS_MASTER_ARCHITECTURE.md`
Roadmap: `docs/RPG_OS_IMPLEMENTATION_ROADMAP.md`

## Phase 0 status

### [x] 0.1 Current master + recent commits audit
Evidence:
- master at audit start: `5c663dc0` — canonical implementation roadmap/checklist.
- preceding canonical architecture: `bd150f9`.
- recent code changes include `099f87f` GM 141 choices and `3b1fa08` GM 142 six-snapshot retention.

### [x] 0.2 GitHub Actions / build baseline
Evidence:
- Build & Release RPG OS ALPHA run #36 for `5c663dc0` completed SUCCESS.
- Current baseline compiles in configured CI.

### [-] 0.3 Database/schema/migration inventory
Known physical assets:
- `app/src/main/assets/rpg_core.db`
- `Naruto.worldpack.zip` containing world database package
- `Naruto_Default.campaign.zip` containing campaign save package
- runtime uses `campaign.db`, `world.db`, `rpg_core.db`.

Known migration mechanism:
- `MigrationManager.ensureV1()` creates `rpgos_schema_migrations`, ensures VisualLibrary schema and records `RPGOS-1.0` baseline.

Known runtime/queried campaign tables include at least:
- campaign_meta
- campaign_calendar
- entity_positions
- character_status_snapshot
- character_stats
- character_skills
- character_techniques
- character_inventory
- character_finances
- financial_transactions
- injuries_v2
- relationships_v2
- organization_memberships_v3
- character_goals
- chapter_manifests_v2
- story_threads
- story_beats
- decision_points
- consequence_links
- quests_v2
- continuity_checks
- missions_v3
- mission_outcomes
- mission_participants
- future_world_pressure
- active_world_events
- narrative_memory_index
- information_facts
- information_knowledge
- npc_memories_v2
- npc_schedules
- npc_decisions
- npc_action_candidates
- timeline_divergences
- gm_timeline_alerts

Known core registry tables:
- source_of_truth_registry
- table_registry

Still required before COMPLETE:
- authoritative table/column inventory of bundled SQLite databases,
- key/UID/nullability/versioning inventory,
- identify legacy/reference/writable table overlaps,
- compare runtime assumptions to actual schema.

### [x] 0.4 Existing tests inventory
Evidence:
- no standard Android `app/src/test` directory found,
- no standard Android `app/src/androidTest` directory found,
- repository contains `tools/long_campaign_stress_test.py`, `tools/static_audit.py` and historical validation JSON reports.

Conclusion:
- test inventory is known,
- current automated domain/invariant unit/integration coverage is insufficient for MASTER Definition of Done.

### [-] 0.5 Existing backend/domain systems map
Confirmed existing runtime components:
- `LocalGameStore` — central Android facade opening active campaign/world/core DBs and exposing context, character, NPC, world, backup, patch and package operations.
- `CampaignSelectionManager` — active campaign/worldpack selection and campaign cloning.
- `SourceOfTruthRegistry` — write whitelist/read-only table guard.
- `StatePatchEngine` — SQLite transaction for patch insert/update/delete operations.
- `ContextBuilder` — large structured context bundle built from save/world DBs.
- `CharacterPanelReader` / legacy `CharacterPanelSnapshot` — current read model for identity/stats/resources/skills/techniques/equipment/relationships/goals.
- `ChapterSaveManager` — chapter manifest/hash writer.
- `BackupManager` — DB backups and retention of six `chapter_*` automatic snapshots.
- `RestoreManager` — restore with `pre_restore_*` safety copy.
- `MigrationManager` — minimal RPGOS-1.0 migration registry + visual schema baseline.
- `AutoRepairEngine` — creates selected missing runtime tables and repair log.
- `BackendClient` + `backend/app.py` — structured AI turn API.
- `NpcWorldDashboardReader`, `SocialReader`, `WorldReader`, `TechniqueMissionReader` — direct DB readers.
- diagnostics/update/package infrastructure.

Still required:
- map all mutation paths and determine which bypass `StatePatchEngine`,
- map persistence ownership for every Player Domain area,
- map backend response application order in `RpgOsViewModel`,
- inspect all domain tables and direct SQL writers.

### [-] 0.6 Gap map COMPLETE/PARTIAL/MISSING/BLOCKED
Preliminary map below. It is intentionally conservative until schema and writer audits are complete.

## Preliminary architecture gap map

### Phase A — Player/Data Foundation

`[-] 1. Unified Repository + stable UID`
Evidence: `LocalGameStore` is a useful central facade and active campaign selection works, but higher layers still instantiate/read arbitrary SQLite tables directly. No canonical repository interfaces per domain. BackupManager is still hard-coded to `saves/Naruto_Default.campaign`.

`[-] 2. Campaign Source of Truth + FACT/BELIEF/NARRATIVE + provenance`
Evidence: `SourceOfTruthRegistry` controls writable tables and core DB has registry tables. `information_facts` / `information_knowledge` exist. Full FACT/BELIEF/NARRATIVE classification and common provenance contract are not yet enforced.

`[-] 3. Player State Contract`
Evidence: substantial player tables exist and are consumed by CharacterPanel/ContextBuilder, but no explicit Persistent/Derived/Runtime authoritative contract currently coordinates them.

`[-] 4. Dynamic Stats & Resources`
Evidence: `character_stats` is queried and resource-like values exist in status/runtime data. No confirmed generic `StatDefinition/ResourceDefinition + modifier/derived` implementation yet.

`[?] 5. DerivedValueResolver + modifier model`
No confirmed resolver found in audited files yet.

`[?] 6. TalentProfile + PotentialProfile`
Not yet audited at database level.

`[-] 7. Skill model`
Evidence: `character_skills` and `skill_definitions` are used by runtime readers/context. Missing audit of persistence rules, progression provenance and invariants.

`[-] 8. Technique model`
Evidence: `character_techniques` and `technique_definitions` are used. Missing authoritative creation/learning pathway, project linkage and invariant coverage.

`[?] 9. Innate/Racial/Bloodline/Evolution model`
Requires DB audit.

`[-] 10. Inventory model`
Evidence: `character_inventory` is read by CharacterPanel. Ownership, unique-item identity, transaction pathway and invariant enforcement not confirmed.

`[?] 11. Equipment model`
Technique equipment flag exists; general equipment domain requires audit.

`[?] 12. Ownership model`
Requires DB audit.

`[-] 13. Financial Ledger / Economy model`
Evidence: `character_finances` is read; `financial_transactions` is explicitly writable in SourceOfTruthRegistry. Need confirm ledger authority, money conservation and mutation pathways.

`[?] 14. Assets/debts/obligations/net worth`
`character_finances` has debt/property/investment summary columns, but canonical asset/ownership ledger model requires audit.

`[?] 15–23 Player commands/domain/progression/ledgers`
No conclusion until all writers and backend application path are audited.

`[-] 24. CharacterPanelSnapshot v2`
Evidence: legacy `CharacterPanelSnapshot` exists with 8 flat sections. It is a read model, but lacks v2 versioning, nested domain snapshots, economy/assets/talent/potential/etc.

`[ ] 25. PlayerSnapshotBuilder + profiles`
No dedicated builder/profile mechanism found in audited runtime files.

### Phase B — Integrity

`[-] 26. Single Truth Mutation Path enforcement`
Evidence: `StatePatchEngine` is a controlled mutation route for AI patches, but full mutation-path audit is not complete and other code may write directly.

`[-] 27. Turn Transaction atomic commit/rollback`
Evidence: `StatePatchEngine.apply()` uses an SQLite transaction and rollback for patch operations. It does NOT atomically include narrative, chapter_events, ledgers, memory and final player-visible response.

`[?] 28. Idempotency / double-commit`
StatePatch carries `transactionId`, but audited `StatePatchEngine` does not persist/check it before applying. Treat as unresolved until writer/schema audit.

`[?] 29. Crash recovery`
No LAST VALID COMMIT mechanism confirmed yet.

`[?] 30. Event Store append-only`
Many event-like tables exist; backend returns `chapter_events`. A unified append-only Event Store and persistence of `chapter_events` have not yet been confirmed.

`[-] 31. Causal Graph`
Evidence: `consequence_links` exists and ChapterSaveManager reads it. Full generalized causal graph requires audit.

`[?] 32. Authoritative/Derived/Cache/Presentation enforcement`
Concept is now canonical but enforcement not confirmed.

`[-] 33. Snapshot System`
Evidence: chapter manifests + full campaign DB backup points exist. Current system is file-copy backup based, not yet the full snapshot+event replay architecture.

`[-] 34. Automatic snapshot retention max 6`
Evidence: implemented constant `AUTO_SNAPSHOT_RETENTION=6` and pruning of `chapter_*` backups. Critical defect: BackupManager is hard-coded to `Naruto_Default.campaign`, so retention is not per active campaign yet. Manual/pre-restore names are not matched by pruning.

`[-] 35. Canon Divergence`
Evidence: `timeline_divergences` is a permitted runtime table and ContextBuilder asks AI to respect canon unless campaign already diverged. Full enforcement semantics require audit.

`[-] 36. Schema Versioning + migrations`
Evidence: `rpgos_schema_migrations` and `RPGOS-1.0` baseline exist. Current migration framework is minimal and lacks the MASTER multi-schema/version/migration safety contract.

### Phase C/D/E/F/G — selected confirmed partials

`[-] 37. NPC Knowledge`
Evidence: `information_knowledge`, `information_facts`, `npc_memories_v2`, acquisition_method/confidence/accuracy are actively retrieved. Need enforce acquisition writes and perspective isolation.

`[-] 45. Context Builder`
Evidence: substantial real `ContextBuilder` exists and is used by LocalGameStore. It directly queries tables, uses heuristic player UID discovery, and lacks the canonical repository/profile/budget architecture.

`[-] 49. Structured GM Output`
Evidence: backend `TurnResponse` includes narration, max 3 choices, state_patch and chapter_events using strict JSON schema.

`[-] 51. Consistency Validator`
Evidence: current validation includes SourceOfTruth write guard and structured schema, but does not cover the full canonical invariant set.

`[-] 55–57 Memory foundation`
Evidence: narrative_memory_index, npc_memories_v2 and retrieval of long-term memory exist. Canonical three-layer memory semantics/consolidation need audit.

`[-] 70. Chronicle`
Evidence: chapter manifests exist and LocalGameStore renders chronicle from them. Current `summary` is continuity warnings JSON rather than canonical event-derived chapter summary.

`[-] 71. Save/Load`
Evidence: campaign selection, chapter manifests, full DB backups and restore exist. Canonical save pointers + event replay/branch model not implemented/confirmed.

`[-] 73. Backup System`
Evidence: automatic/manual-style DB copies, restore safety copy and update backup infrastructure exist. Active campaign hard-code in BackupManager is a known defect; pre-migration/cloud boundaries incomplete.

`[-] 74. Observability`
Evidence: DiagnosticsSnapshot and DiagnosticLogger exist, but canonical per-turn latency/retrieval/validation/commit metrics are not present.

`[-] 77. Long Campaign Stress Tests`
Evidence: Python script can insert up to configurable chapter counts (default 10,000) and run SQLite integrity_check, but it depends on an externally materialized DB path and does not test the canonical 100k/1M-event/multi-million-word integrity questions.

## High-priority findings

1. `BackupManager` ignores active campaign selection and always targets `Naruto_Default.campaign`. This is an integrity issue and is likely the earliest concrete defect inside the Unified Repository/active-campaign boundary.
2. `LocalGameStore` already behaves as a proto-facade; it should likely evolve into/behind CampaignRepository rather than be discarded.
3. `StatePatchEngine` already supplies useful transactional SQL behavior; it should be incorporated into a larger TurnTransaction rather than replaced blindly.
4. `SourceOfTruthRegistry` is a real starting point for write-policy enforcement, not a placeholder.
5. `CharacterPanelSnapshot` v1 is presentation/read-model code and should be migrated only after authoritative Player State contracts are established.
6. There is currently no standard Android unit/instrumentation test tree. Before declaring core architecture stages COMPLETE, invariant/persistence/migration tests must be added.
7. The backend has structured GM output but `chapter_events` need explicit audit for persistence; structured output alone is not Event Store integration.

## Next Phase 0 work

Before leaving Phase 0:
1. inspect `RpgOsViewModel` GM response/patch sequence,
2. inspect `GameModels`/`ContextModels`/`JsonCodec`/`BackendClient`,
3. audit direct SQLite writes across runtime classes,
4. inventory actual bundled DB schemas/tables/UID columns as far as repository access permits,
5. inspect finance/inventory/ownership/talent/progression tables and readers,
6. finish full 1–84 status map,
7. update canonical roadmap statuses with evidence.
