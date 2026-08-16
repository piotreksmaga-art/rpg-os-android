# RPG OS — KANONICZNA KOLEJNOŚĆ PRAC I CHECKLISTA

Status: ACTIVE / CANONICAL ROADMAP
Architecture: `docs/RPG_OS_MASTER_ARCHITECTURE.md`
Coordination: `docs/PARALLEL_WORK_COORDINATION.md`, `docs/architecture/CHAT_COORDINATION_POLICY.md`
Operational protocol: `docs/PROJECT_WORK_PROTOCOL.md`

> Aktualizacja 2026-08-16: Phase 19 — WorldRuleProvider została globalnie zaakceptowana przez koordynatora po 4× niezależnej clean-scope rewalidacji na dokładnie tym samym runtime SHA. Phase 20 pozostaje NOT STARTED do zakończenia post-acceptance cleanup/release sequence zgodnie z bieżącą polityką koordynatora.

## Statusy
- `[x] COMPLETE` — globalnie zaakceptowany etap; wdrożony i zweryfikowany zgodnie z wymaganiami danego etapu.
- `[-] PARTIAL` — realny fundament istnieje, ale nie spełnia pełnego kontraktu MASTER.
- `[ ] MISSING` — docelowa implementacja nie istnieje w aktualnym runtime.
- `[!] BLOCKED` — implementacja/kandydat istnieje, ale etap nie może zostać globalnie zaakceptowany z powodu nierozwiązanego blockera.

Sama klasa, tabela, raport audytowy albo zielone CI nie oznacza COMPLETE. Globalną akceptację zmienia koordynator po sprawdzeniu implementacji, integracji, regresji i dowodów.

# AKTUALNY BASELINE PROJEKTU

- Canonical accepted Phase-19 runtime: `5754f28ccd4f7c1f3522c0af6c34bcaf65e2dcf8`.
- Exact acceptance CI: run #534 / ID `31943818205` — SUCCESS.
- Accepted validation artifact: `9262792137`.
- Artifact digest: `sha256:8287def96eaa74d679d3b68848f29cc7878efd8ce5857d59924a62e7cc829433`.
- Accepted validation APK SHA-256: `414d92dde528cc7ef002eff6d74ba13f5f4fded01fb5f222bdcf9483f0a8abc6`.
- Clean-scope independent revalidation on the accepted SHA: CHAT-2 PASS, CHAT-3 PASS, CHAT-4 PASS, CHAT-5 PASS.
- Phase 19 publication: **NO** (`publication=false`).
- Ostatnia opublikowana linia użytkowa pozostaje wcześniejszą linią release; Phase 19 nie została jeszcze opublikowana przez CHAT-6.
- Phase 20: **NOT STARTED**.

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
- [ ] 20. ProgressionEngine + Progression Ledger
- [ ] 21. Diminishing Returns + passive progression hooks
- [ ] 22. Player Invariant Validator + No-Retrogression
- [ ] 23. Unified Player ledgers + provenance integration
- [-] 24. CharacterPanelSnapshot v2
- [ ] 25. PlayerSnapshotBuilder + FULL/COMBAT/PROGRESSION/ECONOMY/SOCIAL/GM_CONTEXT profiles

## Evidence dla globalnie zaakceptowanej linii 1–19

Phase 1–18 pozostają zaakceptowane zgodnie z wcześniejszymi raportami i release evidence. Phase 19 została zaakceptowana na runtime `5754f28ccd4f7c1f3522c0af6c34bcaf65e2dcf8` po exact CI #534 / `31943818205` oraz 4× niezależnym PASS na tym samym SHA.

Kanoniczne dokumenty Phase 19:
- `docs/architecture/PHASE19_WORLD_RULE_PROVIDER_CANONICAL_SCOPE.md`
- `docs/architecture/PHASE19_ACCEPTANCE.md`
- `docs/architecture/PHASE19_DEFERRED_FINDINGS.md`

# FAZA 19 — WORLDRULEPROVIDER — COMPLETE

## Stan kanoniczny

**STATUS: ACCEPTED / COMPLETE**

Accepted runtime SHA:
`5754f28ccd4f7c1f3522c0af6c34bcaf65e2dcf8`

Exact acceptance CI:
- run #534
- ID `31943818205`
- conclusion `success`

Validation artifact:
- ID `9262792137`
- digest `sha256:8287def96eaa74d679d3b68848f29cc7878efd8ce5857d59924a62e7cc829433`
- APK SHA-256 `414d92dde528cc7ef002eff6d74ba13f5f4fded01fb5f222bdcf9483f0a8abc6`
- publication `false`

Independent acceptance:
- CHAT-2 — PASS
- CHAT-3 — PASS
- CHAT-4 — PASS
- CHAT-5 — PASS

Closed in-scope blocker:
- `P19-C3-UNCOMMITTED-WORLDPACK-ROLLBACK-FAIL-01`

Accepted Phase-19 boundary includes the generic `WorldRuleProvider` contract, deterministic provider selection/identity, one coherent canonical World Pack authority observation per resolution, immutable pinned binding across `COMMAND_PRECHECK` and `DRAFT_EFFECT_CHECK`, fail-closed authority handling, provider retained-state hardening, deterministic request/decision identity, and preservation of Phase-17/18 proposal/reference semantics.

No acceptance delta was introduced to:
- `PlayerChangeSet` schema;
- database schema/migrations;
- canonical package format;
- persisted authority source count;
- Phase-20 runtime.

Phase 19 is **not yet published in the user application**. Publication remains a separate CHAT-6 release responsibility.

## Deferred findings are NOT fixed by Phase 19 acceptance

`docs/architecture/PHASE19_DEFERRED_FINDINGS.md` remains authoritative for findings intentionally deferred to later roadmap phases. In particular Phase 19 acceptance does **not** mark complete:
- general live SQLite/WAL campaign snapshotting;
- general createCampaign/clone/RestoreManager coherence;
- global crash recovery / LAST VALID COMMIT;
- Snapshot System;
- Save/Load;
- Branching;
- Backup;
- general recovery/cleanup availability;
- TurnTransaction / global COMMIT infrastructure.

These items remain deferred to their roadmap destinations and must not be retroactively treated as Phase-19 functionality.

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

Phase 19 jest globalnie **ACCEPTED / COMPLETE**, ale zgodnie z decyzją koordynatora implementacja Phase 20 nie rozpoczyna się jeszcze w tym cleanup work itemie.

Aktualna sekwencja operacyjna:

`Phase 19 post-acceptance canonical cleanup -> coordinator review -> CHAT-6 release-145 preparation/publication task -> explicit coordinator authorization -> Phase 20 ProgressionEngine + Progression Ledger`.

Do czasu zakończenia tej sekwencji:

**PHASE 20 = NOT STARTED.**

# ZASADA AKTUALIZACJI ROADMAPY

Po każdym etapie zmieniaj globalny status wyłącznie z dowodem obejmującym właściwy dla etapu runtime/schema/migration/integration/test/build/CI oraz niezależne audyty, jeżeli zostały wymagane. Raport workera `PASS` lub `COMPLETE` nie jest sam w sobie globalnym `ACCEPTED`.