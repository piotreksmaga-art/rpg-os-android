# RPG OS — KANONICZNA KOLEJNOŚĆ PRAC I CHECKLISTA

Status: ACTIVE / CANONICAL ROADMAP
Architecture: `docs/RPG_OS_MASTER_ARCHITECTURE.md`
Coordination: `docs/PARALLEL_WORK_COORDINATION.md`, `docs/architecture/CHAT_COORDINATION_POLICY.md`
Operational protocol: `docs/PROJECT_WORK_PROTOCOL.md`

> Aktualizacja 2026-08-15: roadmapa została zsynchronizowana z rzeczywistym stanem repozytorium i zaakceptowaną linią runtime. Poprzednia wersja dokumentu była historycznie opóźniona i nadal wskazywała Fazę 4 jako następną zależność mimo zaakceptowanego runtime przez Fazę 18.

## Statusy
- `[x] COMPLETE` — globalnie zaakceptowany etap; wdrożony i zweryfikowany zgodnie z wymaganiami danego etapu.
- `[-] PARTIAL` — realny fundament istnieje, ale nie spełnia pełnego kontraktu MASTER.
- `[ ] MISSING` — docelowa implementacja nie istnieje w aktualnym runtime.
- `[!] BLOCKED` — implementacja/kandydat istnieje, ale etap nie może zostać globalnie zaakceptowany z powodu nierozwiązanego blockera.

Sama klasa, tabela, raport audytowy albo zielone CI nie oznacza COMPLETE. Globalną akceptację zmienia koordynator po sprawdzeniu implementacji, integracji, regresji i dowodów.

# AKTUALNY BASELINE PROJEKTU

- Aktualny `master` w chwili tej synchronizacji przed zapisem: `adb6ba27ca0a06cded24930715235a852e7975ab`.
- Ostatnia opublikowana linia użytkowa: `v1.2.0-alpha5-hybrid142`.
- Release `hybrid142` deklaruje cumulative accepted implementation through Phase 18 i jawnie wyklucza Phase 19 oraz Phase 20+.
- Accepted runtime wskazany przez ten release: `b53ae2c5b765cba49f31a0f88e7865cf1df8d5a7`.
- Kandydat Phase 19 audytowany w ostatniej serii: `c86a61f019d8579b970b0c07c8a9df41b922ff83`.
- Exact CI dla tego kandydata: run #518 / ID `31868961756` — SUCCESS.
- Phase 19 NIE jest opublikowana w aplikacji użytkowej.

# FAZA 0 — BASELINE / AUDYT
- [x] 0. Baseline / audit foundation

# FAZA A — FUNDAMENT DANYCH I GRACZA
- [x] 1. Unified Repository + stable UID
- [x] 2. Campaign Source of Truth + FACT/BELIEF/NARRATIVE + provenance
- [x] 3. Player State Contract: Persistent / Derived / Runtime
- [x] 4. Dynamic StatDefinition/PlayerStat + ResourceDefinition/PlayerResource
- [x] 5. DerivedValueResolver + modifier model
- [x] 6. TalentProfile + PotentialProfile
- [x] 7. Skill model
- [x] 8. Technique model
- [x] 9. Innate/Racial/Bloodline/Evolution runtime model
- [x] 10. Inventory model
- [x] 11. Equipment domain/loadout model
- [x] 12. OwnershipRecord domain
- [x] 13. Financial Ledger / Economy model
- [x] 14. Assets / debts / obligations / net-worth model
- [x] 15. DevelopmentProject model
- [x] 16. PlayerCommand contract
- [x] 17. PlayerChangeSet contract
- [x] 18. PlayerDomainEngine orchestration
- [!] 19. WorldRuleProvider contract
- [ ] 20. ProgressionEngine + Progression Ledger
- [ ] 21. Diminishing Returns + passive progression hooks
- [ ] 22. Player Invariant Validator + No-Retrogression
- [ ] 23. Unified Player ledgers + provenance integration
- [-] 24. CharacterPanelSnapshot v2
- [ ] 25. PlayerSnapshotBuilder + FULL/COMBAT/PROGRESSION/ECONOMY/SOCIAL/GM_CONTEXT profiles

## Evidence dla globalnie zaakceptowanej linii 1–18

Szczegółowe raporty implementacyjne, architektoniczne, semantyczne, integrity i correctness znajdują się w `docs/` oraz `docs/audits/`. Opublikowany release `v1.2.0-alpha5-hybrid142` jest jawnie opisany jako cumulative accepted implementation through Phase 18 i synchronizuje aplikację użytkową z globalnie zaakceptowanym runtime Phase 3–18.

Nie należy ponownie otwierać Faz 4–18 wyłącznie dlatego, że stara wersja tej roadmapy miała przy nich status MISSING/PARTIAL. Nowy regresyjny blocker może oczywiście ponownie zablokować dalszy rozwój, ale wymaga konkretnego dowodu.

# FAZA 19 — WORLDRULEPROVIDER — BLOCKED

## Stan

Phase 19 ma rozbudowanego kandydata runtime i szerokie testy, ale **nie jest globalnie ACCEPTED**.

Ostatni wspólny kandydat audytowy:
`c86a61f019d8579b970b0c07c8a9df41b922ff83`

Pozytywne dowody na tym SHA obejmują:
- atomic snapshot kluczy wyboru `active_campaign` + `active_worldpack` z jednego `SharedPreferences.all`;
- read-only authority dependency na granicy `PlayerDomainEngine`;
- jeden authority read na pojedynczą resolution;
- wspólny pinned binding dla `COMMAND_PRECHECK` i `DRAFT_EFFECT_CHECK`;
- long-lived engine lifecycle tests;
- cross-campaign rejection w standardowych scenariuszach;
- provider invocation count = 0 dla odrzuconej authority;
- provider-state hardening;
- deterministyczne request/decision fingerprints i provenance;
- regresje Phase 17/18 przechodzące;
- exact CI #518 SUCCESS;
- immutable validation artifact z `publication:false`.

## Nierozwiązany blocker

`P19-C3-ATOMIC-AUTHORITY-PACKAGE-CONTENT-TOCTOU-02`

CHAT-3 wykazał, że snapshotowanie samych nazw wyboru w `SharedPreferences` nie atomizuje późniejszego odczytu zawartości `campaign.json`, `worldpack.json` i `world.db`.

Możliwy interleaving supported-path:
1. resolution przechwytuje stare nazwy wyboru, np. C1/A;
2. canonical selection przechodzi do innej kampanii/pakietu;
3. wspierany update/import podmienia zawartość katalogu World Packa;
4. stara resolution wznawia odczyt manifestu/bazy z już zmienionej zawartości;
5. authority może zostać złożona z tożsamości, która nie odpowiada jednemu rzeczywistemu canonical observation.

Szczególnie krytyczne jest to, że `RpgPackageManager.validatedImportWorldPack()` może podmienić wskazany katalog, a walidacja nie gwarantuje w audytowanym scenariuszu związania manifest identity z nazwą katalogu wystarczającego do wykluczenia C1+B.

Raport blockera:
`docs/audits/WORK-20260815-P19_CHAT3_INTEGRITY_TOCTOU_REVALIDATION_C86A61F0.md`

Raporty PASS pozostają wartościowymi dowodami dla swoich zakresów, ale nie unieważniają tego blockera. W szczególności PASS dla atomowego snapshotu preference keys nie jest dowodem atomowości package-content identity.

## Gate zamknięcia Phase 19

Phase 19 może zostać oznaczona `[x] COMPLETE` dopiero po:
1. naprawie package-content TOCTOU bez tworzenia drugiego persisted source of truth;
2. zachowaniu read-only dependency dla PlayerDomainEngine;
3. adversarial controlled-interleaving testach obejmujących realną podmianę/aktualizację zawartości World Packa;
4. testach C1/A1 -> C2/A1 -> C2/A2 i próbie wymuszenia C1/A2;
5. teście próby C1+B przez import do starego katalogu;
6. potwierdzeniu single-resolution binding consistency;
7. pełnym JVM/regression suite Phase 17/18;
8. exact CI na jednym finalnym SHA;
9. niezależnych audytach wymaganych przez koordynatora na dokładnie tym samym finalnym runtime SHA;
10. globalnej decyzji koordynatora ACCEPTED.

Do tego czasu **Phase 20 jest BLOCKED BY DEPENDENCY i nie wolno rozpoczynać jej implementacji produkcyjnej**.

# FAZA B — INTEGRALNOŚĆ KAMPANII
- [-] 26. Single Truth Mutation Path enforcement
- [-] 27. Turn Transaction atomic commit/rollback
- [ ] 28. Idempotency + double-commit protection
- [ ] 29. Crash recovery / LAST VALID COMMIT
- [-] 30. Event Store append-only
- [-] 31. Causal Graph
- [ ] 32. Authoritative / Derived / Cache / Presentation runtime enforcement
- [-] 33. Snapshot System
- [-] 34. Automatic snapshot retention max 6
- [-] 35. Canon Divergence
- [-] 36. Schema Versioning + migration safety + legacy provenance

# FAZA C — CZAS, WIEDZA I RETRIEVAL
- [-] 37. NPC Knowledge model + acquisition provenance
- [-] 38. GM/NPC/PC/player-visible knowledge separation
- [-] 39. Temporal Engine historical truth
- [-] 40. Scheduler
- [-] 41. Structured SQL Retriever
- [-] 42. Knowledge Graph / causal retrieval
- [ ] 43. Intent Parser
- [ ] 44. Turn Planner
- [-] 45. Context Builder
- [ ] 46. Context Budget Manager
- [ ] 47. Iterative Retrieval + missing-context loop

# FAZA D — GM ENGINE
- [ ] 48. AiProvider abstraction
- [-] 49. Structured GM Output contract
- [ ] 50. Mechanics Resolution integration
- [-] 51. Consistency Validator
- [ ] 52. Counterfactual Guard
- [ ] 53. Repair Pass dla proposal/narrative
- [ ] 54. Committed narrative delivery only after valid transaction

# FAZA E — PAMIĘĆ I DŁUGOTERMINOWA SYMULACJA
- [-] 55. Working Memory
- [-] 56. Episodic Memory
- [-] 57. Semantic Campaign Memory
- [ ] 58. Memory Consolidation without recursive summary degradation
- [ ] 59. Vector/Semantic Retrieval engine/index integration
- [ ] 60. Time Skip Processor
- [-] 61. NPC Brain
- [-] 62. NPC Decision Engine
- [ ] 63. World Simulation LOD 0–3 engine
- [ ] 64. Background-world causal simulation / controlled randomness engine

# FAZA F — JAKOŚĆ NARRACJI
- [ ] 65. Director Engine
- [-] 66. Narrative Promise Ledger
- [ ] 67. Pacing Metrics
- [ ] 68. Anti-Repetition
- [ ] 69. Narrative Style Profile
- [-] 70. Chronicle generated from committed structured reality

# FAZA G — SAVE / DEBUG / SKALA
- [-] 71. Save/Load integration
- [ ] 72. Branching without full database duplication
- [-] 73. Backup System
- [-] 74. Observability metrics
- [ ] 75. Replay Debugger
- [-] 76. Integrity Test Suite
- [-] 77. Long Campaign Stress Tests
- [-] 78. Android performance profiling/optimization
- [-] 79. AI cost optimization / model routing

# FAZA H — WORLD PACK HARDENING
- [ ] 80. Naruto WorldRuleProvider integration test pack
- [ ] 81. Bleach WorldRuleProvider integration test pack
- [ ] 82. Canon/divergence automated test scenarios
- [ ] 83. World-specific progression/evolution automated tests
- [ ] 84. World Pack update compatibility automated tests

# FRONTEND
- [x] ACTIVE DEVELOPMENT / STYLE PRESERVATION BY PROJECT DECISION

Frontend może być rozwijany wraz z funkcjonalnością, ale należy zachować zaakceptowany styl wizualny i unikać niepowiązanego redesignu. Zmiany wymagane przez backend/integrację pozostają dozwolone zgodnie z aktualną decyzją użytkownika i coordination policy.

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

# AKTUALNA NAJBLIŻSZA ZALEŻNOŚĆ

**Najwcześniejszym zadaniem nie jest już Faza 4.**

Aktualna kolejność:

`Phase 19 package-content authority TOCTOU fix -> exact-SHA adversarial validation -> independent audits -> coordinator ACCEPTED -> dopiero Phase 20 ProgressionEngine + Progression Ledger`.

Nie implementować Phase 20 na produkcyjnej linii, dopóki Phase 19 pozostaje `[!] BLOCKED`.

# ZASADA AKTUALIZACJI ROADMAPY

Po każdym etapie zmieniaj globalny status wyłącznie z dowodem obejmującym właściwy dla etapu runtime/schema/migration/integration/test/build/CI oraz niezależne audyty, jeżeli zostały wymagane. Raport workera `PASS` lub `COMPLETE` nie jest sam w sobie globalnym `ACCEPTED`.