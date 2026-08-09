# RPG OS — KANONICZNA KOLEJNOŚĆ PRAC I CHECKLISTA

Status: ACTIVE / CANONICAL ROADMAP

Architektura nadrzędna: `docs/RPG_OS_MASTER_ARCHITECTURE.md`.
Dowody audytu: `docs/PHASE_0_AUDIT.md`, `docs/PHASE_0_MUTATION_PATH_AUDIT.md`, `docs/PHASE_0_PLAYER_STATE_AUDIT.md`.

## Statusy
- `[x] COMPLETE` — istnieje, jest zintegrowane, persistence działa, wymagane migracje są bezpieczne, kluczowe testy przechodzą, build przechodzi i nie ma nierozwiązanego konfliktu legacy/new.
- `[-] PARTIAL` — część istnieje, ale brakuje integracji/invariantu/testu/persistence/migracji lub pełnego kontraktu.
- `[ ] MISSING` — brak docelowej implementacji w audytowanym runtime.
- `[!] BLOCKED` — blokowane przez wcześniejszą zależność lub zepsuty baseline.
- `[?] AUDIT REQUIRED` — nie wolno zgadywać; potrzebny dalszy audyt, zwykle binarnego schematu DB lub niezaudytowanej ścieżki.

Dokumentacja lub nazwa klasy NIE wystarcza do `[x]`.

## Obowiązkowy check przed COMPLETE
- [ ] implementacja istnieje,
- [ ] jest używana przez realny runtime,
- [ ] persistence działa,
- [ ] migracja/legacy compatibility jest bezpieczna, jeśli dotyczy,
- [ ] kluczowe invarianty mają testy,
- [ ] reload/recovery działa, jeśli dotyczy,
- [ ] build przechodzi,
- [ ] GitHub Actions przechodzi,
- [ ] brak równoległego konfliktującego systemu,
- [ ] istniejące kampanie są chronione.

# FAZA 0 — BASELINE / AUDYT
- [x] 0.1 Current master + recent commits audit
  - Evidence: baseline i historia commitów zmapowane w `PHASE_0_AUDIT.md`.
- [x] 0.2 GitHub Actions / build baseline
  - Evidence: Build & Release RPG OS ALPHA run #36 dla baseline zakończył się SUCCESS.
- [-] 0.3 Database/schema/migration inventory
  - Evidence: znane runtime DB i wiele tabel; pełny column/UID/nullability/legacy inventory binarnych DB nadal wymagany.
- [x] 0.4 Existing tests inventory
  - Evidence: brak `app/src/test` i `app/src/androidTest`; istnieją ad-hoc `tools/*` i historyczne validation JSON.
- [-] 0.5 Existing backend/domain systems map
  - Evidence: główne runtime readers/writers/GM path/player path zmapowane; pozostaje pełny schema-level writer inventory.
- [-] 0.6 Gap map COMPLETE/PARTIAL/MISSING/BLOCKED
  - Evidence: etapy 1–56 sklasyfikowane poniżej; kilka pozycji nadal `[?]` do audytu DB.

Kryterium zakończenia Fazy 0: domknąć 0.3/0.5/0.6 i pozostawić jednoznaczny pierwszy brak implementacyjny.

# FAZA A — FUNDAMENT DANYCH I GRACZA
- [-] 1. Unified Repository + stable UID
  - Evidence: `LocalGameStore` jest proto-fasadą i active campaign selection działa, ale brak pełnych repozytoriów domenowych; `BackendClient` hardkoduje `naruto-default`, `BackupManager` hardkoduje `Naruto_Default.campaign`.
- [-] 2. Campaign Source of Truth + FACT/BELIEF/NARRATIVE + provenance
  - Evidence: realny `SourceOfTruthRegistry` + `information_facts/knowledge`, lecz brak jednolitego FACT/BELIEF/NARRATIVE/provenance contract.
- [-] 3. Player State Contract: Persistent / Derived / Runtime
  - Evidence: wiele tabel gracza istnieje, lecz player UID jest rozwiązywany heurystycznie i brak jednego authoritative contract.
- [-] 4. Dynamic StatDefinition/PlayerStat + ResourceDefinition/PlayerResource
  - Evidence: `character_stats` i resource-like fields istnieją, lecz docelowe generic definitions nie są potwierdzone.
- [ ] 5. DerivedValueResolver + modifier model
  - Evidence: brak docelowego resolvera w audytowanym Kotlin runtime.
- [?] 6. TalentProfile + PotentialProfile
  - Evidence: brak runtime abstraction; wymagany DB-level audit legacy talent/potential tables.
- [-] 7. Skill model
  - Evidence: `character_skills`, `skill_definitions`, mastery/xp i context integration istnieją; brak command/provenance/progression/invariant path.
- [-] 8. Technique model
  - Evidence: `character_techniques`, definitions/canon index i context integration istnieją; brak creation/requirements/project/invariant path.
- [?] 9. Innate/Racial/Bloodline/Evolution model
  - Evidence: brak docelowej runtime abstraction; wymagany audyt danych World Pack/campaign.
- [-] 10. Inventory model
  - Evidence: `character_inventory` jest odczytywane; ownership/UID/transaction rules niepotwierdzone.
- [ ] 11. Equipment model
  - Evidence: CharacterPanel traktuje cały inventory jako `equipment`; general loadout system niepotwierdzony. `character_techniques.is_equipped` nie zastępuje Equipment Domain.
- [?] 12. Ownership model
  - Evidence: brak potwierdzonego canonical OwnershipRecord runtime; wymagany DB audit.
- [-] 13. Financial Ledger / Economy model
  - Evidence: `character_finances`, `financial_transactions`, rewards istnieją; ledger authority/conservation/mutation path nie są egzekwowane domenowo.
- [-] 14. Assets / debts / obligations / net-worth model
  - Evidence: debt/property/investment summary fields istnieją; canonical assets/ownership/liabilities model niepotwierdzony.
- [ ] 15. DevelopmentProject model
  - Evidence: brak docelowego runtime contract w zaudytowanym kodzie; możliwe legacy project tables wymagają tylko migracyjnego audytu.
- [ ] 16. PlayerCommand contract
  - Evidence: brak; player/backend zmiany trafiają obecnie do niskopoziomowego StatePatch.
- [ ] 17. PlayerChangeSet contract
  - Evidence: brak docelowego domain changeset; `StatePatch` jest formatem SQL mutation, nie PlayerChangeSet.
- [ ] 18. PlayerDomainEngine orchestration
  - Evidence: brak w runtime.
- [ ] 19. WorldRuleProvider contract
  - Evidence: brak docelowej abstraction; świat jest obecnie częściowo zaszyty w schematach/backend prompt.
- [ ] 20. ProgressionEngine + Progression Ledger
  - Evidence: brak docelowego silnika w runtime; legacy DB ledger wymaga audytu przed projektowaniem migracji.
- [ ] 21. Diminishing Returns + passive progression hooks
  - Evidence: brak potwierdzonej mechaniki runtime.
- [ ] 22. Player Invariant Validator + No-Retrogression
  - Evidence: SourceOfTruth write guard istnieje, lecz nie waliduje mastery/stat regression ani Player Domain invariants.
- [ ] 23. Player ledgers + provenance integration
  - Evidence: istnieją wybrane ledger/event-like tabele, ale brak jednolitej Player Domain provenance integration.
- [-] 24. CharacterPanelSnapshot v2
  - Evidence: istnieje legacy v1 z 8 płaskimi sekcjami; brak version/characterUid/profiles/talent/economy/assets/conditions itd.
- [ ] 25. PlayerSnapshotBuilder + FULL/COMBAT/PROGRESSION/ECONOMY/SOCIAL/GM_CONTEXT profiles
  - Evidence: brak dedicated builder/profile mechanism.

FAZA A DONE dopiero, gdy Player State jest legalnie zmieniany przez domenę, persisted/reloaded i odbudowywany do snapshotu bez mutowania snapshotu przez AI/UI.

# FAZA B — INTEGRALNOŚĆ KAMPANII
- [-] 26. Single Truth Mutation Path enforcement
  - Evidence: AI patch ma kontrolowany `StatePatchEngine`, ale chapter manifest/visual/migration/repair/restore mają osobne ścieżki; brak nadrzędnej polityki authoritative transaction.
- [-] 27. Turn Transaction atomic commit/rollback
  - Evidence: `StatePatchEngine` ma SQLite transaction/rollback tylko dla patch operations; narracja/events/ledgers/memory/manifest nie są wspólnym commit.
- [ ] 28. Idempotency: transactionUid/commandUid/turnUid + double-commit protection
  - Evidence: `transactionId` istnieje, ale nie jest persisted/checkowany przed ponownym apply.
- [ ] 29. Crash recovery / last valid commit
  - Evidence: try/catch i SQLite patch transaction nie tworzą persisted LAST_VALID_COMMIT ani recovery journal.
- [-] 30. Event Store append-only
  - Evidence: event-like tables istnieją, backend zwraca `chapter_events`, ale `RpgOsViewModel.send()` ich nie zapisuje jako unified Event Store.
- [-] 31. Causal Graph
  - Evidence: `consequence_links` i event relations istnieją częściowo; brak uogólnionego causal graph contract/retrieval.
- [ ] 32. Authoritative/Derived/Cache/Presentation classification enforcement
  - Evidence: zasada istnieje w MASTER, ale runtime nie egzekwuje klasyfikacji.
- [-] 33. Snapshot System
  - Evidence: chapter manifests + full DB copy backups istnieją; brak canonical snapshot + event replay architecture.
- [-] 34. Automatic snapshot retention: max 6; exclusions for manual/pinned/safety
  - Evidence: retencja 6 `chapter_*` działa, ale `BackupManager` nie używa active campaign. Manual/pre_restore nie są matchowane przez pruning.
- [-] 35. Canon Divergence
  - Evidence: `timeline_divergences` istnieje i jest writable; ContextBuilder/backend prompt uwzględniają divergence ideowo, lecz brak dedicated validation/enforcement.
- [-] 36. Schema Versioning + migration safety + legacy provenance
  - Evidence: `rpgos_schema_migrations` i `RPGOS-1.0` baseline istnieją; framework jest minimalny.

# FAZA C — CZAS, WIEDZA I RETRIEVAL
- [-] 37. NPC Knowledge model + acquisition provenance
  - Evidence: `information_facts`, `information_knowledge`, `npc_memories_v2`, acquisition_method/confidence/accuracy są realnie używane; brak dedicated acquisition validator.
- [-] 38. GM/NPC/PC/player-visible knowledge separation
  - Evidence: backend prompt nakazuje brak leakage i ContextBuilder przesyła knowledge; brak mechanicznego perspective filter contract.
- [-] 39. Temporal Engine: validFrom/validUntil + historical lookup
  - Evidence: campaign_calendar/day/timeline fields istnieją, lecz brak dedicated temporal truth engine i validFrom/validUntil enforcement.
- [-] 40. Scheduler
  - Evidence: `npc_schedules`, `future_world_pressure`, deadlines istnieją jako dane; brak ujednoliconego Scheduler runtime.
- [-] 41. Structured SQL Retriever
  - Evidence: rozbudowane direct SQL retrieval istnieje w ContextBuilder/readers; brak repository-driven bounded retriever abstraction.
- [-] 42. Knowledge Graph retrieval / causal lookup
  - Evidence: relations/consequence data istnieją; brak canonical graph retrieval layer.
- [ ] 43. Intent Parser
  - Evidence: brak structured intent parser w audytowanym runtime.
- [ ] 44. Turn Planner
  - Evidence: `send()` uruchamia stały pipeline; brak planner wybierającego potrzebne systemy.
- [-] 45. Context Builder
  - Evidence: realny `ContextBuilder v1` działa, ale używa direct SQL, heurystycznego player UID i brak profile/repository architecture.
- [ ] 46. Context Budget Manager
  - Evidence: hardcoded LIMIT-y istnieją, lecz brak dynamic budget manager.
- [ ] 47. Iterative Retrieval + missing-context detection
  - Evidence: brak follow-up retrieval loop.

# FAZA D — GM ENGINE
- [ ] 48. AiProvider abstraction
  - Evidence: backend bezpośrednio inicjalizuje `OpenAI`; brak wymiennego provider contract.
- [-] 49. Structured GM Output contract
  - Evidence: strict JSON schema zawiera narration, max 3 choices, state_patch, chapter_events; pełny canonical proposal contract jest szerszy.
- [ ] 50. Mechanics Resolution integration
  - Evidence: AI proponuje StatePatch bez PlayerDomain/Combat/etc. mechanics resolution layer.
- [-] 51. Consistency Validator
  - Evidence: JSON schema + SourceOfTruth table guard istnieją; pełne timeline/knowledge/stats/inventory/causality invariants nie.
- [ ] 52. Counterfactual Guard
  - Evidence: brak dedicated supporting-event verification przed commit.
- [ ] 53. Repair Pass
  - Evidence: `AutoRepairEngine` naprawia schema/runtime tables, nie AI proposal/narrative repair.
- [ ] 54. Committed narrative delivery only after valid transaction
  - Evidence: current `send()` pokazuje GM narration PRZED `StatePatchEngine.apply()`, więc invariant jest obecnie naruszony.

# FAZA E — PAMIĘĆ I DŁUGOTERMINOWA SYMULACJA
- [-] 55. Working Memory
  - Evidence: bieżące chat messages/context istnieją w runtime, ale brak canonical bounded Working Memory persistence/policy.
- [-] 56. Episodic Memory
  - Evidence: `npc_memories_v2`, `narrative_memory_index` i long-term retrieval istnieją; canonical episodic contract/consolidation niepełne.
- [?] 57. Semantic Campaign Memory
- [?] 58. Memory Consolidation without recursive summary degradation
- [?] 59. Vector/Semantic Retrieval
- [?] 60. Time Skip Processor
- [?] 61. NPC Brain
- [?] 62. NPC Decision Engine
- [?] 63. World Simulation LOD 0–3
- [?] 64. Background-world causal simulation / controlled randomness

# FAZA F — JAKOŚĆ NARRACJI
- [?] 65. Director Engine
- [?] 66. Narrative Promise Ledger
- [?] 67. Pacing Metrics
- [?] 68. Anti-Repetition
- [?] 69. Narrative Style Profile
- [-] 70. Chronicle generated from committed structured reality
  - Evidence: chapter manifests/read model istnieją; current Chronicle summary uses continuity warnings rather than canonical event-derived summary.

# FAZA G — SAVE / DEBUG / SKALA
- [-] 71. Save/Load integration
- [?] 72. Branching without full database duplication
- [-] 73. Backup system: autosave/local/manual/pre-migration/pre-restore; optional cloud boundary
- [-] 74. Observability metrics
- [?] 75. Replay Debugger
- [-] 76. Integrity Test Suite
  - Evidence: ad-hoc stress/static tools istnieją; brak standard domain/invariant test suite i CI test execution.
- [-] 77. Long Campaign Stress Tests: 10k/100k turns, 1M events, 5M+ words
  - Evidence: script 10k chapter manifests + integrity_check istnieje; pełna skala/invariant questions nie.
- [?] 78. Performance profiling/optimization on Android
- [?] 79. AI cost optimization / model routing

# FAZA H — WORLD PACK HARDENING
Rozpocząć agresywnie dopiero po stabilnym Core.
- [?] 80. Naruto WorldRuleProvider integration test pack
- [?] 81. Bleach WorldRuleProvider integration test pack
- [?] 82. Canon/divergence test scenarios
- [?] 83. World-specific progression/evolution tests
- [?] 84. World Pack update compatibility tests

# FRONTEND
- [x] FROZEN BY PROJECT DECISION — nie oznacza ukończenia wszystkich ekranów; oznacza zakaz proaktywnego rozwoju wizualnego na obecnym etapie.
- [ ] Re-enable only by explicit user decision.

# CROSS-CUTTING TEST CHECKLIST
- [?] save -> close -> load
- [?] snapshot -> replay -> same authoritative state
- [?] old campaign -> migration -> valid load
- [?] failed turn -> rollback -> no partial mutation
- [ ] retry same transaction -> ALREADY_COMMITTED / no duplicate effects
- [ ] simulated crash -> last valid commit recovery
- [ ] permanent mastery -> no unexplained regression
- [?] money ledger -> balance conservation/auditability
- [?] unique item -> no duplicate ownership
- [?] ownership -> temporal history valid
- [?] NPC knowledge -> no leakage
- [?] temporal lookup -> correct historical truth
- [?] campaign divergence -> canon does not overwrite campaign
- [?] CharacterPanelSnapshot delete/rebuild -> no data loss
- [?] cache/index delete/rebuild -> no data loss

# JAK WYBIERAĆ NASTĘPNE ZADANIE
1. Jeżeli build/CI jest zepsuty — napraw baseline.
2. Jeżeli istnieje ryzyko utraty danych/integralności — napraw je.
3. Audytuj statusy od początku zależności.
4. Znajdź najwcześniejszy `[ ]`, `[-]` lub `[!]`, który blokuje dalszą architekturę.
5. Sprawdź, czy implementacja nie istnieje pod inną nazwą.
6. Zdefiniuj najmniejszy bezpieczny delta.
7. Implementuj, testuj, build, commit, CI.
8. Dopiero po dowodach zmień status.
9. Zapisz następny najwcześniejszy brak.

# FORMAT EVIDENCE
`Evidence: <klasy/tabele/migracje/testy/commit/build>`

Nie oznaczaj COMPLETE na podstawie samego dokumentu, TODO lub deklaracji z czatu.

# DEFINITION OF DONE — CAŁY CORE
Core jest gotowy do agresywnej rozbudowy contentu dopiero gdy krytyczne elementy Faz A–G są COMPLETE, migracje są bezpieczne, idempotency/crash recovery/rollback są przetestowane, snapshot/cache są odbudowywalne, knowledge/temporal truth izolowane, stress tests nie wykazują degradacji, Android performance mieści się w zaakceptowanym budżecie, a frontend pozostaje niezależny od authoritative domain state.
