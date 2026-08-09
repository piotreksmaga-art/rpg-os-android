# RPG OS — KANONICZNA KOLEJNOŚĆ PRAC I CHECKLISTA

Status: ACTIVE / CANONICAL ROADMAP

Ten dokument jest operacyjną roadmapą. Architektura nadrzędna: `docs/RPG_OS_MASTER_ARCHITECTURE.md`.

## Zasady statusu
- `[x] COMPLETE` — istnieje, jest zintegrowane, persistence działa, wymagane migracje są bezpieczne, kluczowe testy przechodzą, build przechodzi i nie ma nierozwiązanego konfliktu legacy/new.
- `[-] PARTIAL` — część istnieje, ale brakuje integracji/invariantu/testu/persistence/migracji lub pełnego kontraktu.
- `[ ] MISSING` — brak wymaganej implementacji.
- `[!] BLOCKED` — blokowane przez wcześniejszą zależność lub zepsuty baseline.
- `[?] AUDIT REQUIRED` — nie wolno zgadywać; trzeba sprawdzić repozytorium.

Dokumentacja lub nazwa klasy NIE wystarcza do `[x]`.

## Obowiązkowy check przed zmianą statusu
Dla każdego elementu sprawdź:
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

Dopiero komplet wymaganych punktów pozwala oznaczyć `[x] COMPLETE`.

# FAZA 0 — BASELINE / AUDYT
- [?] 0.1 Current master + recent commits audit
- [?] 0.2 GitHub Actions / build baseline
- [?] 0.3 Database/schema/migration inventory
- [?] 0.4 Existing tests inventory
- [?] 0.5 Existing backend/domain systems map
- [?] 0.6 Gap map COMPLETE/PARTIAL/MISSING/BLOCKED dla etapów 1–56

Kryterium zakończenia: wiemy, co naprawdę istnieje; nie implementujemy z pamięci.

# FAZA A — FUNDAMENT DANYCH I GRACZA
- [?] 1. Unified Repository + stable UID
- [?] 2. Campaign Source of Truth + FACT/BELIEF/NARRATIVE + provenance
- [?] 3. Player State Contract: Persistent / Derived / Runtime
- [?] 4. Dynamic StatDefinition/PlayerStat + ResourceDefinition/PlayerResource
- [?] 5. DerivedValueResolver + modifier model
- [?] 6. TalentProfile + PotentialProfile
- [?] 7. Skill model
- [?] 8. Technique model
- [?] 9. Innate/Racial/Bloodline/Evolution model
- [?] 10. Inventory model
- [?] 11. Equipment model
- [?] 12. Ownership model
- [?] 13. Financial Ledger / Economy model
- [?] 14. Assets / debts / obligations / net-worth model
- [?] 15. DevelopmentProject model
- [?] 16. PlayerCommand contract
- [?] 17. PlayerChangeSet contract
- [?] 18. PlayerDomainEngine orchestration
- [?] 19. WorldRuleProvider contract
- [?] 20. ProgressionEngine + Progression Ledger
- [?] 21. Diminishing Returns + passive progression hooks
- [?] 22. Player Invariant Validator + No-Retrogression
- [?] 23. Player ledgers + provenance integration
- [?] 24. CharacterPanelSnapshot v2
- [?] 25. PlayerSnapshotBuilder + FULL/COMBAT/PROGRESSION/ECONOMY/SOCIAL/GM_CONTEXT profiles

FAZA A DONE, gdy Player State może być legalnie zmieniany przez domenę, zapisany, przeładowany i odbudowany do snapshotu bez bezpośredniego mutowania snapshotu przez AI/UI.

# FAZA B — INTEGRALNOŚĆ KAMPANII
- [?] 26. Single Truth Mutation Path enforcement
- [?] 27. Turn Transaction atomic commit/rollback
- [?] 28. Idempotency: transactionUid/commandUid/turnUid + double-commit protection
- [?] 29. Crash recovery / last valid commit
- [?] 30. Event Store append-only
- [?] 31. Causal Graph
- [?] 32. Authoritative/Derived/Cache/Presentation classification enforcement
- [?] 33. Snapshot System
- [?] 34. Automatic snapshot retention: max 6; exclusions for manual/pinned/safety
- [?] 35. Canon Divergence
- [?] 36. Schema Versioning + migration safety + legacy provenance

FAZA B DONE, gdy awaria/retry/rollback nie może stworzyć częściowego lub podwójnego świata, a historia i stan pozostają audytowalne.

# FAZA C — CZAS, WIEDZA I RETRIEVAL
- [?] 37. NPC Knowledge model + acquisition provenance
- [?] 38. GM/NPC/PC/player-visible knowledge separation
- [?] 39. Temporal Engine: validFrom/validUntil + historical lookup
- [?] 40. Scheduler
- [?] 41. Structured SQL Retriever
- [?] 42. Knowledge Graph retrieval / causal lookup
- [?] 43. Intent Parser
- [?] 44. Turn Planner
- [?] 45. Context Builder
- [?] 46. Context Budget Manager
- [?] 47. Iterative Retrieval + missing-context detection

# FAZA D — GM ENGINE
- [?] 48. AiProvider abstraction
- [?] 49. Structured GM Output contract
- [?] 50. Mechanics Resolution integration
- [?] 51. Consistency Validator
- [?] 52. Counterfactual Guard
- [?] 53. Repair Pass
- [?] 54. Committed narrative delivery only after valid transaction

# FAZA E — PAMIĘĆ I DŁUGOTERMINOWA SYMULACJA
- [?] 55. Working Memory
- [?] 56. Episodic Memory
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
- [?] 70. Chronicle generated from committed structured reality

# FAZA G — SAVE / DEBUG / SKALA
- [?] 71. Save/Load integration
- [?] 72. Branching without full database duplication
- [?] 73. Backup system: autosave/local/manual/pre-migration/pre-restore; optional cloud boundary
- [?] 74. Observability metrics
- [?] 75. Replay Debugger
- [?] 76. Integrity Test Suite
- [?] 77. Long Campaign Stress Tests: 10k/100k turns, 1M events, 5M+ words
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
Każdy odpowiedni etap powinien pokrywać, gdzie ma zastosowanie:
- [?] save -> close -> load
- [?] snapshot -> replay -> same authoritative state
- [?] old campaign -> migration -> valid load
- [?] failed turn -> rollback -> no partial mutation
- [?] retry same transaction -> ALREADY_COMMITTED / no duplicate effects
- [?] simulated crash -> last valid commit recovery
- [?] permanent mastery -> no unexplained regression
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
3. Wykonaj audyt statusów od początku zależności.
4. Znajdź najwcześniejszy `[ ]`, `[-]` lub `[!]`, który blokuje późniejsze elementy.
5. Sprawdź, czy implementacja nie istnieje pod inną nazwą.
6. Zdefiniuj najmniejszy bezpieczny delta.
7. Implementuj, testuj, build, commit, CI.
8. Dopiero po dowodach zmień status w tej checkliście.
9. Zapisz następny najwcześniejszy brak.

# FORMAT AKTUALIZACJI CHECKLISTY
Przy zmianie statusu dodaj pod elementem krótką notę:

`Evidence: <klasy/tabele/migracje/testy/commit/build>`

Przykład:

`[x] 27. Turn Transaction atomic commit/rollback`
`Evidence: TurnTransaction.kt + migration X + TurnTransactionTest + commit abc123 + CI PASS.`

Jeżeli część istnieje:

`[-] 20. ProgressionEngine + Progression Ledger`
`Evidence: ledger persistence exists; diminishing returns integration and reload test missing.`

Nie oznaczaj elementu COMPLETE na podstawie samego dokumentu, TODO lub deklaracji z czatu.

# DEFINITION OF DONE — CAŁY CORE
Core RPG OS jest gotowy do agresywnej rozbudowy contentu dopiero gdy:
- wszystkie krytyczne elementy Faz A–G są COMPLETE,
- nie istnieją krytyczne równoległe legacy/new paths,
- migracje starych kampanii są bezpieczne,
- idempotency i crash recovery są przetestowane,
- rollback chroni atomowość,
- snapshot/cache są odbudowywalne,
- NPC knowledge i temporal truth są izolowane,
- stress tests nie wykazują degradacji integralności,
- Android performance pozostaje w zaakceptowanym budżecie,
- frontend pozostaje niezależny od authoritative domain state.
