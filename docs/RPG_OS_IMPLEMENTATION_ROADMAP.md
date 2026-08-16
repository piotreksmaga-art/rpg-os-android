# RPG OS — KANONICZNA KOLEJNOŚĆ PRAC I CHECKLISTA

Status: ACTIVE / CANONICAL ROADMAP
Architecture: `docs/RPG_OS_MASTER_ARCHITECTURE.md`
Coordination: `docs/PARALLEL_WORK_COORDINATION.md`, `docs/architecture/CHAT_COORDINATION_POLICY.md`
Operational protocol: `docs/PROJECT_WORK_PROTOCOL.md`

> Aktualizacja 2026-08-16: Player Core Completion — Phase 21–25 została globalnie zaakceptowana przez koordynatora na exact runtime SHA `c028aa355d9b7e1663166a2fedb910c1a2dad795`, po zamknięciu blockera `P21-25-INVARIANT-BYPASS-01` i niezależnej exact-SHA rewalidacji CHAT-4 oraz CHAT-5. Następny etap to Phase 26 — Single Truth Mutation Path enforcement, rozpoczynany od AUDIT FIRST.

## Statusy
- `[x] COMPLETE` — globalnie zaakceptowany etap; wdrożony i zweryfikowany zgodnie z wymaganiami danego etapu.
- `[-] PARTIAL` — realny fundament istnieje, ale nie spełnia pełnego kontraktu MASTER.
- `[ ] MISSING` — docelowa implementacja nie istnieje w aktualnym runtime.
- `[!] BLOCKED` — implementacja/kandydat istnieje, ale etap nie może zostać globalnie zaakceptowany z powodu nierozwiązanego blockera.

Sama klasa, tabela, raport audytowy albo zielone CI nie oznacza COMPLETE. Globalną akceptację zmienia koordynator po sprawdzeniu implementacji, integracji, regresji i dowodów.

# AKTUALNY BASELINE PROJEKTU

- Canonical accepted Player Core runtime through Phase 25: `c028aa355d9b7e1663166a2fedb910c1a2dad795`.
- Exact acceptance CI: run #607 / ID `31968919354` — SUCCESS.
- Final exact-SHA revalidation: CHAT-4 PASS (`WORK-20260816-016`), CHAT-5 PASS (`WORK-20260816-017`).
- Closed blocker: `P21-25-INVARIANT-BYPASS-01`.
- Schema/migration delta for Phase 21–25: **NONE**.
- Phase 26: **NOT STARTED / AUDIT FIRST**.

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
- [x] 19. WorldRuleProvider contract
- [x] 20. ProgressionEngine + Progression Ledger
- [x] 21. Diminishing Returns + passive progression hooks
- [x] 22. Player Invariant Validator + No-Retrogression
- [x] 23. Unified Player ledgers + provenance integration
- [x] 24. CharacterPanelSnapshot v2
- [x] 25. PlayerSnapshotBuilder + FULL/COMBAT/PROGRESSION/ECONOMY/SOCIAL/GM_CONTEXT profiles

## Evidence dla globalnie zaakceptowanej linii 1–25

Phase 1–19 pozostają zaakceptowane zgodnie z wcześniejszymi acceptance records i evidence. Phase 20 została zaakceptowana na runtime `38dafe5cc48c87f16218e346d9c0f9a96b6cee50`. Phase 21–25 zostały zaakceptowane jako Player Core Completion na exact runtime `c028aa355d9b7e1663166a2fedb910c1a2dad795`, exact CI #607 / `31968919354`, po targeted fix `WORK-20260816-015` oraz finalnym PASS CHAT-4 (`WORK-20260816-016`) i CHAT-5 (`WORK-20260816-017`) na tym samym SHA.

Kanoniczne dokumenty:
- `docs/architecture/PHASE19_WORLD_RULE_PROVIDER_CANONICAL_SCOPE.md`
- `docs/architecture/PHASE19_ACCEPTANCE.md`
- `docs/architecture/PHASE19_DEFERRED_FINDINGS.md`
- `docs/architecture/PHASE20_ACCEPTANCE.md`
- `docs/architecture/PHASE21_25_ACCEPTANCE.md`

# FAZA 20 — PROGRESSIONENGINE + PROGRESSION LEDGER — COMPLETE

**STATUS: ACCEPTED / COMPLETE**

Accepted runtime SHA:
`38dafe5cc48c87f16218e346d9c0f9a96b6cee50`

Exact acceptance CI:
- run #578
- ID `31961047982`
- conclusion `success`

Final exact-SHA revalidation:
- CHAT-4 / `WORK-20260816-008` — PASS
- CHAT-5 / `WORK-20260816-009` — PASS

Closed acceptance blocker:
- `P20-C4-001` — deterministic canonical ordering of `ProgressionCalculationFactor`.

Accepted boundary includes a pure/deterministic/proposal-only Core ProgressionEngine, deterministic progression/grant/ledger identities, versioned deterministic numeric semantics, typed progression evidence in the existing PlayerLedgerIntent family, mapping grants into existing typed player changes, augmented reference closure, and progression-generated effects visible to the single final Phase-19 DRAFT_EFFECT_CHECK under the same pinned World Pack authority.

# FAZA 21–25 — PLAYER CORE COMPLETION — COMPLETE

**STATUS: ACCEPTED / COMPLETE**

Accepted runtime SHA:
`c028aa355d9b7e1663166a2fedb910c1a2dad795`

Exact acceptance CI:
- run #607
- ID `31968919354`
- conclusion `success`

Final exact-SHA revalidation:
- CHAT-4 / `WORK-20260816-016` — PASS
- CHAT-5 / `WORK-20260816-017` — PASS

Closed acceptance blocker:
- `P21-25-INVARIANT-BYPASS-01` — Phase-22 PlayerInvariantValidator was optional on the first candidate. Accepted runtime makes invariant validation mandatory on canonical `PlayerDomainEngine.resolve(...)` before a resolved proposal can be returned.

Accepted scope:
- Phase 21: deterministic diminishing-returns/progression factor semantics and pure passive progression hooks on the existing Phase-20 engine;
- Phase 22: mandatory read-only PlayerInvariantValidator / No-Retrogression gate with typed authorization for legal durable regression;
- Phase 23: bounded provenance/ledger semantic integration without a global writable unified player ledger or TurnTransaction; P20-CB-01 resolved prospectively without fabricated history;
- Phase 24: CharacterPanelSnapshotV2 as DERIVED_PRESENTATION with delete/rebuild safety and no authoritative ownership;
- Phase 25: deterministic PlayerSnapshotBuilder with FULL / COMBAT / PROGRESSION / ECONOMY / SOCIAL / GM_CONTEXT as DERIVED_PROJECTION profiles preserving FACT / BELIEF / NARRATIVE separation.

No accepted Phase 21–25 delta creates Phase-26 Single Truth Mutation Path enforcement, TurnTransaction, global commit/retry/idempotency, second Player Engine, second WorldRuleProvider, global writable unified player ledger, NPC Knowledge authority, Temporal/Scheduler runtime, or schema/migration changes.

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
- [x] no unexplained permanent regression — Phase 22 accepted proposal gate; full transaction/replay enforcement remains later
- [ ] money conservation / ledger auditability
- [ ] unique item / ownership integrity
- [ ] NPC knowledge isolation
- [ ] temporal historical truth
- [ ] divergence survives canon updates
- [x] CharacterPanelSnapshot delete/rebuild -> no data loss — Phase 24 accepted read-model gate
- [ ] cache/index delete/rebuild -> no data loss

# AKTUALNA NAJBLIŻSZA ZALEŻNOŚĆ

Player Core through Phase 25 jest globalnie **ACCEPTED / COMPLETE**.

Następny etap:
`Phase 26 — Single Truth Mutation Path enforcement`

Obowiązkowa sekwencja:
`AUDIT FIRST -> classify COMPLETE / PARTIAL / MISSING / BLOCKED -> coordinator work split -> explicit implementation authorization`

Do czasu zakończenia audytu i jawnej decyzji koordynatora:
**PHASE 26 = NOT STARTED / NOT AUTHORIZED FOR IMPLEMENTATION.**

# ZASADA AKTUALIZACJI ROADMAPY

Po każdym etapie zmieniaj globalny status wyłącznie z dowodem obejmującym właściwy dla etapu runtime/schema/migration/integration/test/build/CI oraz niezależne audyty, jeżeli zostały wymagane. Raport workera `PASS` lub `COMPLETE` nie jest sam w sobie globalnym `ACCEPTED`.
