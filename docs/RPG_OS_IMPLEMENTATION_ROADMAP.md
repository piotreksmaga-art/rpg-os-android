# RPG OS — KANONICZNA KOLEJNOŚĆ PRAC I CHECKLISTA

Status: ACTIVE / CANONICAL ROADMAP
Architecture: `docs/RPG_OS_MASTER_ARCHITECTURE.md`
Phase 0 evidence: `docs/PHASE_0_AUDIT.md`, `docs/PHASE_0_MUTATION_PATH_AUDIT.md`, `docs/PHASE_0_PLAYER_STATE_AUDIT.md`.
Phase 1 evidence: `docs/PHASE_1_UNIFIED_REPOSITORY.md`.
Phase 2 evidence: `docs/PHASE_2_SOURCE_OF_TRUTH.md`.

## Statusy
- `[x] COMPLETE` — wdrożone, zintegrowane, persisted, bezpieczne migracyjnie, przetestowane i build/CI przechodzą.
- `[-] PARTIAL` — realny fundament istnieje, ale nie spełnia pełnego kontraktu MASTER.
- `[ ] MISSING` — docelowa implementacja nie istnieje w aktualnym runtime.
- `[!] BLOCKED` — blokowane przez wcześniejszą zależność lub uszkodzony baseline.

Sama klasa, tabela lub dokument nie oznacza COMPLETE.

# FAZA 0 — BASELINE / AUDYT — COMPLETE
- [x] 0.1 Current master + recent commits audit
- [x] 0.2 GitHub Actions / build baseline
- [x] 0.3 Database/schema/migration inventory na poziomie wymaganym do wyboru architektury
- [x] 0.4 Existing tests inventory
- [x] 0.5 Existing backend/domain systems map
- [x] 0.6 Gap map COMPLETE/PARTIAL/MISSING/BLOCKED dla 1–84

Evidence:
- fizyczne DB/runtime packages, główne tabele używane przez kod i mechanizm migracji są zmapowane;
- mutation path GM, Player read model, ContextBuilder, backup/snapshot, restore, package/update i główne read/write components są zmapowane;
- pełny column-level dump konkretnej tabeli jest wykonywany ponownie przed jej migracją i nie jest potrzebny do dalszego wyboru najwcześniejszej zależności;
- CI run #40 dla audytowanego baseline zakończył się SUCCESS;
- brak standardowego `app/src/test` oraz `app/src/androidTest` został potwierdzony dla baseline Phase 0.

# FAZA A — FUNDAMENT DANYCH I GRACZA
- [x] 1. Unified Repository + stable UID
  Evidence: `ActiveCampaignRef`, `CampaignRepository`, `UnifiedGameRepository` i application-level repository boundary istnieją; `LocalGameStore`, backup/snapshot, restore, ContextBuilder/backend campaign_id i settings używają jednej aktywnej tożsamości kampanii. Legacy Naruto mapping jest zachowany jako jawny default/migration constant. Repository identity tests oraz CI/build przechodzą. Szczegóły: `docs/PHASE_1_UNIFIED_REPOSITORY.md`.
- [x] 2. Campaign Source of Truth + FACT/BELIEF/NARRATIVE + provenance
  Evidence: `CampaignTruthModels`, `CampaignTruthStore`, migracja `RPGOS-2.0-TRUTH`, typed `CampaignRepository.recordTruth/truthRecords`, runtime `campaign_truth` w ContextBundle, jawne provenance oraz semantyka backendu są zintegrowane. BELIEF wymaga perspektywy, NARRATIVE nie może automatycznie stać się FACT, a generic StatePatch nie może pisać bezpośrednio do `campaign_truth_records`. Existing campaigns są chronione addytywną migracją bez wymyślania legacy provenance. JVM tests i signed build przechodzą. Szczegóły: `docs/PHASE_2_SOURCE_OF_TRUTH.md`.
- [-] 3. Player State Contract: Persistent / Derived / Runtime
  Evidence: wiele player tables istnieje; brak authoritative contract i jawnego activePlayerUid.
- [-] 4. Dynamic StatDefinition/PlayerStat + ResourceDefinition/PlayerResource
  Evidence: `character_stats` i resource-like state istnieją; brak potwierdzonego generic definition/resolver architecture.
- [ ] 5. DerivedValueResolver + modifier model
- [ ] 6. TalentProfile + PotentialProfile
- [-] 7. Skill model
  Evidence: `character_skills`, mastery/xp i runtime retrieval istnieją; brak domain command/provenance/progression/invariants.
- [-] 8. Technique model
  Evidence: `character_techniques`, definitions/canon index i runtime retrieval istnieją; brak creation/project/requirements/invariant path.
- [ ] 9. Innate/Racial/Bloodline/Evolution runtime model
- [-] 10. Inventory model
  Evidence: `character_inventory` jest odczytywane; brak canonical item identity/ownership/change path.
- [ ] 11. Equipment domain/loadout model
- [ ] 12. OwnershipRecord domain
- [-] 13. Financial Ledger / Economy model
  Evidence: `character_finances` i `financial_transactions` istnieją; brak ledger authority, conservation validator i domain mutation path.
- [-] 14. Assets / debts / obligations / net-worth model
  Evidence: summary fields debt/property/investment istnieją; brak canonical Asset/Ownership/Liability domain.
- [ ] 15. DevelopmentProject model
- [ ] 16. PlayerCommand contract
- [ ] 17. PlayerChangeSet contract
- [ ] 18. PlayerDomainEngine orchestration
- [ ] 19. WorldRuleProvider contract
- [ ] 20. ProgressionEngine + Progression Ledger
- [ ] 21. Diminishing Returns + passive progression hooks
- [ ] 22. Player Invariant Validator + No-Retrogression
- [ ] 23. Unified Player ledgers + provenance integration
- [-] 24. CharacterPanelSnapshot v2
  Evidence: legacy v1 istnieje jako 8 płaskich sekcji; brak version/characterUid/nested domain snapshots/talent/economy/assets/conditions.
- [ ] 25. PlayerSnapshotBuilder + FULL/COMBAT/PROGRESSION/ECONOMY/SOCIAL/GM_CONTEXT profiles

# FAZA B — INTEGRALNOŚĆ KAMPANII
- [-] 26. Single Truth Mutation Path enforcement
  Evidence: AI patch przechodzi przez `StatePatchEngine`; Phase 2 dodatkowo blokuje bezpośredni generic StatePatch do `campaign_truth_records`, ale manifest, visuals, migrations/repair/restore i inne writer paths nie są jeszcze częścią jednej authoritative mutation policy.
- [-] 27. Turn Transaction atomic commit/rollback
  Evidence: SQLite transaction istnieje tylko dla StatePatch operations; narration/events/ledgers/memory/manifest nie są jednym commit.
- [ ] 28. Idempotency + double-commit protection
  Evidence: `transactionId` istnieje w kontrakcie, ale nie jest persisted/checkowany przed apply.
- [ ] 29. Crash recovery / LAST VALID COMMIT
- [-] 30. Event Store append-only
  Evidence: event-like tables istnieją; backend zwraca `chapter_events`, ale Android ich obecnie nie zapisuje do unified store.
- [-] 31. Causal Graph
  Evidence: `consequence_links` istnieje; brak generalized graph contract/retrieval.
- [ ] 32. Authoritative / Derived / Cache / Presentation runtime enforcement
- [-] 33. Snapshot System
  Evidence: chapter manifests i full DB copy backups istnieją; brak snapshot + event replay architecture.
- [-] 34. Automatic snapshot retention max 6
  Evidence: pruning `chapter_*` do 6 działa dla aktywnej kampanii przez wspólną campaign identity; manual/pre-restore nie są pruningowane.
- [-] 35. Canon Divergence
  Evidence: `timeline_divergences` jest runtime-writable i prompt uwzględnia divergence; brak dedicated validator/resolution semantics.
- [-] 36. Schema Versioning + migration safety + legacy provenance
  Evidence: `rpgos_schema_migrations`, `RPGOS-1.0` i addytywna `RPGOS-2.0-TRUTH` istnieją; ogólny migration framework nadal jest minimalny.

# FAZA C — CZAS, WIEDZA I RETRIEVAL
- [-] 37. NPC Knowledge model + acquisition provenance
  Evidence: information facts/knowledge, npc memories, confidence/accuracy/acquisition_method są pobierane; Phase 2 ma ogólny provenance contract, ale brak jeszcze acquisition validator dla NPC knowledge.
- [-] 38. GM/NPC/PC/player-visible knowledge separation
  Evidence: backend rozróżnia BELIEF od FACT i deklaruje isolation, ale brak mechanicznego perspective filter contract dla wszystkich źródeł wiedzy.
- [-] 39. Temporal Engine historical truth
  Evidence: calendar/day/timeline timestamps istnieją; brak validFrom/validUntil engine i historical-state resolver.
- [-] 40. Scheduler
  Evidence: `npc_schedules`, mission deadlines i `future_world_pressure` są danymi schedulowanymi; brak jednego Scheduler runtime.
- [-] 41. Structured SQL Retriever
  Evidence: rozbudowany SQL retrieval istnieje w `ContextBuilder`/readers, a Phase 2 dodaje bounded truth retrieval; brak repository-driven retriever abstraction.
- [-] 42. Knowledge Graph / causal retrieval
  Evidence: relation/consequence structures istnieją; brak canonical graph retriever.
- [ ] 43. Intent Parser
- [ ] 44. Turn Planner
- [-] 45. Context Builder
  Evidence: `ContextBuilder v1` działa realnie i ContextBundle przenosi już `campaign_truth`; nadal używa direct SQL, heuristic player UID i statycznych LIMIT-ów.
- [ ] 46. Context Budget Manager
- [ ] 47. Iterative Retrieval + missing-context loop

# FAZA D — GM ENGINE
- [ ] 48. AiProvider abstraction
  Evidence: backend wiąże się bezpośrednio z OpenAI client/model env.
- [-] 49. Structured GM Output contract
  Evidence: strict schema: narration, max 3 choices, StatePatch, chapter_events; docelowy proposal contract jest szerszy.
- [ ] 50. Mechanics Resolution integration
- [-] 51. Consistency Validator
  Evidence: structured schema + writable-table guard + truth semantics istnieją; brak pełnych domain/timeline/knowledge/causal invariants.
- [ ] 52. Counterfactual Guard
- [ ] 53. Repair Pass dla proposal/narrative
- [ ] 54. Committed narrative delivery only after valid transaction
  Evidence: `RpgOsViewModel.send()` pokazuje narrację przed `StatePatchEngine.apply()`.

# FAZA E — PAMIĘĆ I DŁUGOTERMINOWA SYMULACJA
- [-] 55. Working Memory
  Evidence: bieżące chat/context state istnieje; brak canonical bounded policy/persistence.
- [-] 56. Episodic Memory
  Evidence: `npc_memories_v2`, `narrative_memory_index` i long-term retrieval istnieją; brak docelowego lifecycle/consolidation contract.
- [-] 57. Semantic Campaign Memory
  Evidence: summary/index/fact structures i Phase 2 truth records mogą pełnić część roli semantic memory; brak explicit semantic memory lifecycle/consolidation engine.
- [ ] 58. Memory Consolidation without recursive summary degradation
- [ ] 59. Vector/Semantic Retrieval engine/index integration
- [ ] 60. Time Skip Processor
- [-] 61. NPC Brain
  Evidence: canon identity/personality/combat + memories/beliefs/schedules/decisions istnieją w rozproszonych tabelach/readers; brak jednego durable agent contract.
- [-] 62. NPC Decision Engine
  Evidence: `npc_decisions` i `npc_action_candidates` istnieją jako schema/data scaffolding; brak zintegrowanego decision runtime.
- [ ] 63. World Simulation LOD 0–3 engine
- [ ] 64. Background-world causal simulation / controlled randomness engine

# FAZA F — JAKOŚĆ NARRACJI
- [ ] 65. Director Engine
- [-] 66. Narrative Promise Ledger
  Evidence: story threads, consequence links i active quests przechowują część otwartych zobowiązań; brak canonical promise ledger/status lifecycle.
- [ ] 67. Pacing Metrics
- [ ] 68. Anti-Repetition
- [ ] 69. Narrative Style Profile
  Evidence: AppSettings zawiera backend/update/campaign/worldpack/diagnostics/autobackup, nie trwały style profile.
- [-] 70. Chronicle generated from committed structured reality
  Evidence: chapter manifests i Chronicle read model istnieją; obecny summary nie jest event-derived canonical chronicle.

# FAZA G — SAVE / DEBUG / SKALA
- [-] 71. Save/Load integration
  Evidence: campaign selection, DB backups, restore i chapter manifests istnieją; brak event-pointer save/load model.
- [ ] 72. Branching without full database duplication
- [-] 73. Backup System
  Evidence: chapter backups, pre-restore i active-campaign pre-update backup są spięte z active campaign identity; brak pre-migration/cloud boundary i pełnego backup policy contract.
- [-] 74. Observability metrics
  Evidence: DiagnosticLogger/DiagnosticsSnapshot i dev self-test istnieją; brak canonical per-turn latency/count/transaction metrics.
- [ ] 75. Replay Debugger
- [-] 76. Integrity Test Suite
  Evidence: standardowe JVM unit tests istnieją od Phase 1 i są uruchamiane przez CI przed release build; Phase 2 dodaje truth/provenance invariants, ale coverage nadal jest wąskie i brak pełnych integration/instrumentation/invariant tests.
- [-] 77. Long Campaign Stress Tests
  Evidence: configurable script domyślnie 10k chapter manifests + SQLite integrity_check; brak 100k/1M events/5M words i canonical fact/knowledge/causal/economy checks.
- [-] 78. Android performance profiling/optimization
  Evidence: bounded SQL LIMIT-y, bounded truth retrieval i proste cache/read models istnieją; brak realnego profiling budget/test harness.
- [-] 79. AI cost optimization / model routing
  Evidence: model może być wybrany przez env, ale brak local/small/medium/strong routing architecture.

# FAZA H — WORLD PACK HARDENING
- [ ] 80. Naruto WorldRuleProvider integration test pack
- [ ] 81. Bleach WorldRuleProvider integration test pack
- [ ] 82. Canon/divergence automated test scenarios
- [ ] 83. World-specific progression/evolution automated tests
- [ ] 84. World Pack update compatibility automated tests

# FRONTEND
- [x] FROZEN BY PROJECT DECISION
  Aktualny styl jest zaakceptowany. Brak proaktywnego rozwoju wizualnego do jawnej decyzji użytkownika.

# CROSS-CUTTING TEST GAPS
- [ ] save -> close -> load authoritative equality
- [ ] snapshot -> replay -> same authoritative state
- [ ] old campaign -> migration -> valid load
- [ ] failed turn -> rollback -> no partial mutation
- [ ] retry transaction -> no duplicate effects
- [ ] simulated crash -> last valid commit recovery
- [ ] no unexplained permanent regression
- [ ] money conservation / ledger auditability
- [ ] unique item / ownership integrity
- [ ] NPC knowledge isolation
- [ ] temporal historical truth
- [ ] divergence survives canon updates
- [ ] CharacterPanelSnapshot delete/rebuild -> no data loss
- [ ] cache/index delete/rebuild -> no data loss

# FAZA 1 — WYNIK
Unified Repository + active campaign identity jest COMPLETE. Spełniono pięć kryteriów delta Phase 1; dowód implementacyjny i CI znajduje się w `docs/PHASE_1_UNIFIED_REPOSITORY.md`.

# FAZA 2 — WYNIK
Campaign Source of Truth + FACT/BELIEF/NARRATIVE + provenance jest COMPLETE. Typowana prawda kampanii jest persisted, odseparowana per campaign, przekazywana do ContextBundle i interpretowana przez backend zgodnie z kontraktem. Generic StatePatch nie może ominąć truth API. Migracja istniejących kampanii jest addytywna i nie fabrykuje historycznego provenance. Dowody: `docs/PHASE_2_SOURCE_OF_TRUTH.md`.

# NASTĘPNA ZALEŻNOŚĆ PO FAZIE 2
Następnym najwcześniejszym zadaniem jest **3. Player State Contract: Persistent / Derived / Runtime**. Nie należy jeszcze skakać do dynamicznych statystyk, talentów ani PlayerDomainEngine, dopóki nie istnieje jawny authoritative player identity/state contract określający, które dane są trwałe, derived i runtime.

Po każdym wdrożeniu zmieniaj status wyłącznie z dowodem: pliki + migracja (jeśli potrzebna) + test + build/CI.
