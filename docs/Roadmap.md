# Roadmap

Status: ACTIVE / CANONICAL ROADMAP

Architecture: `docs/Architektura projektu.md`

File map: `docs/Mapa plików.md`

Project history / acceptance chronology: `docs/Historia projektu.md`

Coordination: `docs/PARALLEL_WORK_COORDINATION.md`, `docs/architecture/CHAT_COORDINATION_POLICY.md`

Operational protocol: `docs/PROJECT_WORK_PROTOCOL.md`

## Statusy
- `[x] COMPLETE` — globalnie zaakceptowany etap;
- `[-] PARTIAL` — realny fundament istnieje, ale pełny kontrakt nie jest jeszcze zaakceptowany;
- `[ ] MISSING` — docelowa implementacja nie istnieje;
- `[!] BLOCKED` — kandydat istnieje, ale acceptance blokuje nierozwiązany problem.

Globalny status zmienia koordynator po sprawdzeniu implementacji, integracji, persistence/migration safety, regresji, full JVM/build/CI i wymaganych audytów. Raport workera, sama klasa/tabela albo zielony pojedynczy test nie oznaczają COMPLETE.

## Aktualny baseline
- Canonical accepted runtime through Phase 36: `4d5a114fc9f08141d75ae79f998a3400866b52ba`.
- Exact acceptance CI: `Validate RPG OS ALPHA`, run #801 / `32309493128` — SUCCESS.
- Późniejsze commity dokumentacyjne nie zmieniają accepted runtime SHA.
- Pełne historyczne SHA/CI/artifacts/findingi: `docs/Historia projektu.md` i phase acceptance records.
- Następny implementacyjny gate: **Phase 37 — NPC Knowledge model + acquisition provenance, AUDIT FIRST**.
- Phase 48 pozostaje NOT STARTED.
- World Pack Creator pozostaje DEFERRED do czasu globalnego ACCEPTED Phase 1–84.

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

# FAZA B — INTEGRALNOŚĆ KAMPANII
- [x] 26. Single Truth Mutation Path enforcement
- [x] 27. Turn Transaction atomic commit/rollback
- [x] 28. Idempotency + double-commit protection
- [x] 29. Crash recovery / LAST VALID COMMIT
- [x] 30. Event Store append-only
- [x] 31. Causal Graph
- [x] 32. Authoritative / Derived / Cache / Presentation runtime enforcement
- [x] 33. Snapshot System
- [x] 34. Automatic snapshot retention max 6
- [x] 35. Canon Divergence
- [x] 36. Schema Versioning + migration safety + legacy provenance

Accepted scope i historical evidence Phase 1–36 są zamrożone i zarchiwizowane. Future changes nie reinterpretują ich retroaktywnie.

# FAZA C — CZAS, WIEDZA I RETRIEVAL
- [-] 37. NPC Knowledge model + acquisition provenance
- [-] 38. GM/NPC/PC/player-visible knowledge separation + belief/reputation visibility boundaries
- [-] 39. Temporal Engine historical truth
- [-] 40. Scheduler — evaluation points/deadlines, not precommitted outcomes
- [-] 41. Structured SQL Retriever
- [-] 42. Knowledge Graph / causal retrieval
- [ ] 43. Intent Parser
- [ ] 44. Turn Planner
- [-] 45. Context Builder — holder isolation + separate local/cloud task context boundaries
- [ ] 46. Context Budget Manager — ModelProfile/AiCapability/workload-aware
- [ ] 47. Iterative Retrieval + missing-context loop

## Acceptance direction Phase 37–47
- holder-scoped NPC knowledge with typed acquisition provenance;
- FACT/BELIEF/NARRATIVE and GM/NPC/PC/player-visible separation;
- legacy unknown provenance remains unknown;
- historical truth queries are temporal, not present-state substitution;
- Scheduler owns future evaluation points, not guaranteed outcomes;
- retrieval is bounded/iterative and context is actor/time/visibility-safe;
- cloud context, when later enabled, is minimal and sanitised rather than whole-save export.

# FAZA D — GM ENGINE / HYBRID AI FOUNDATION
- [ ] 48. AI Provider & Hybrid Local-First Inference Architecture
- [-] 49. Structured GM Output contract
- [ ] 50. Mechanics Resolution integration
- [-] 51. Consistency Validator
- [ ] 52. Counterfactual Guard
- [ ] 53. Repair Pass for proposal/narrative
- [ ] 54. Committed narrative delivery only after valid transaction

## Acceptance direction Phase 48–54
Phase 48 buduje provider/execution foundation, nie pełny Director.

Wymagane docelowo:
- provider-independent `AiProvider`/semantic contract;
- `AiCapabilityContract`, data-driven `ModelProfile`;
- `LocalInferenceRuntime` + independent `RuntimeBackendSelector` CPU/GPU/supported NPU/AUTO;
- optional cloud execution pod tym samym semantic contractem;
- model/provider/runtime/backend replacement bez campaign migration;
- local path wystarcza do kontynuowania kampanii offline po zainstalowaniu kompatybilnego modelu;
- cloud disabled/no-network/timeout/429/quota/provider/credential failure -> local continuation;
- allowlisted `GmToolGateway`, no raw writable DB / no COMMIT authority;
- cloud context minimization/privacy boundary i credentials poza Campaign State;
- `VOLITIONAL PLAYER ACTION SOURCE = USER / VALIDATED PLAYER COMMAND ONLY`;
- structural ACTOR/ACTION/TARGET preservation;
- provider conformance: agency, direction, knowledge isolation, FACT/BELIEF, stop point, invented ability/dialogue, internal-context leak, structured output, mutation boundary;
- AI crash/cancel/process-death -> no partial committed turn;
- real Android local integration/performance evidence.

Konkretny model, provider, format i runtime są adapter/evidence candidates, nie canonical lock-in.

# FAZA E — PAMIĘĆ I DŁUGOTERMINOWA SYMULACJA
- [-] 55. Working Memory — AI provider/model is not durable owner
- [-] 56. Episodic Memory — AI provider/model is not durable owner
- [-] 57. Semantic Campaign Memory — AI provider/model is not durable owner
- [ ] 58. Memory Consolidation without recursive summary degradation
- [ ] 59. Vector/Semantic Retrieval engine/index integration
- [ ] 60. Time Skip Processor + Scheduler/WorldProcess orchestration
- [-] 61. NPC Brain + persistent individuality/personality/values/goals/fears/emotional state/relationships
- [-] 62. NPC Decision Engine + knowledge/memory/social-role constrained autonomy
- [ ] 63. World Simulation LOD 0–3 + multi-rate WorldProcess engine
- [ ] 64. Background-world causal simulation: organizations/economy/projects/demography/wars/knowledge propagation + controlled randomness

## Acceptance direction Phase 55–64
Memory pozostaje RPG OS-owned i odtwarzalna po zmianie modelu/runtime.

Phase 61 NPC individuality:
- personality/traits persisted lub deterministically reproducible;
- archetype/culture/organization/background + controlled stable RNG;
- osobne personality, values/goals/fears, relationship, emotional state, knowledge/beliefs, memory, social role i resources/capabilities;
- różne NPC mogą legalnie reagować inaczej na ten sam bodziec;
- long-term personality adaptation wymaga committed cause/provenance;
- reputation/rumor są holder-scoped beliefs, nie omniscient global score;
- hidden NPC traits nie są automatycznie player-visible;
- LOD/tier dla crowd/minor/persistent/major NPC.

Phase 62 decyzje:
- decision inputs = personality + values/goals/fears + emotions + relationships + knowledge/beliefs + memory + social/organization constraints + resources/capabilities + current situation + World Pack rules + controlled randomness where legal;
- `KNOWLEDGE != DECISION`, `PERSONALITY != DECISION`, `AI PROPOSAL != COMMIT`;
- debug/replay powinien móc wyjaśnić czynniki/evidence decyzji.

Phase 63–64 Living World:
- global invariant: **THE WORLD DOES NOT WAIT FOR THE PLAYER**;
- WorldActor support for NPC/family/clan/organization/company/guild/city/state/army/world-specific actors;
- long-running WorldProcess: wars, trade, politics, migration, research, construction, epidemics, crime, economy, demography, diplomacy, espionage itd.;
- dynamic LOD0–3 + multi-rate simulation;
- population/crowd aggregation and provenance-safe materialization;
- world/domain conservation for supported resources/population/money/goods/armies/projects;
- important background changes -> Event/Causal history;
- background FACT does not automatically become PLAYER/NPC KNOWLEDGE;
- legal information propagation using world-specific communication constraints;
- opportunities/quests may emerge from world state/process;
- local world simulation works without cloud.

# FAZA F — DIRECTOR / JAKOŚĆ NARRACJI
- [ ] 65. Director Engine + optional Cloud Director / candidate bundles
- [-] 66. Narrative Promise Ledger
- [ ] 67. Pacing Metrics
- [ ] 68. Anti-Repetition
- [ ] 69. Narrative Style Profile
- [-] 70. Chronicle generated from committed structured reality

## Acceptance direction Phase 65–70
- Director controls attention/pacing/strategic candidates, not laws of reality;
- optional Cloud Director may produce bounded arc/quest/NPC-agenda/faction/foreshadowing/pacing candidates;
- `DIRECTOR OUTPUT = CANDIDATE != FACT != COMMIT`;
- Director cannot retroactively rewrite NPC personality/history or fabricate committed crisis/war without causal/domain basis;
- cloud enrichment may be deferred/asynchronous and cannot block current turn or rewrite past COMMIT;
- Chronicle remains projection from committed structured reality.

# FAZA G — SAVE / DEBUG / SKALA
- [-] 71. Save/Load integration
- [ ] 72. Branching without full database duplication
- [-] 73. Backup System
- [-] 74. Observability metrics
- [ ] 75. Replay Debugger
- [-] 76. Integrity Test Suite
- [-] 77. Long Campaign Stress Tests
- [-] 78. Android performance profiling/optimization
- [-] 79. AI workload / provider / model / runtime routing

## Acceptance direction Phase 71–79
Wymagane m.in.:
- save/load/replay authoritative equality;
- branch history sharing without full DB duplication;
- recovery/backups and debug/replay evidence;
- `WORLD_WITHOUT_PLAYER`: wieloletnia autonomous simulation + causal history + save/load/replay equality;
- `SAME_WORLD_TWO_CAMPAIGNS`: same World Pack/initial seed + different player actions -> explainably different histories;
- world simulation budget/backlog does not linearly scan/full-freeze world/UI;
- cloud disabled/timeout/429/quota/provider removed -> local campaign continuation;
- malformed/late cloud candidate -> no mutation / no rewrite of past COMMIT;
- provider/model/runtime switch and credential removal -> no campaign migration/data loss;
- privacy/failover policy respected;
- Phase 79 separates workload policy, provider execution choice, ModelRouter and RuntimeBackendSelector; deterministic tasks bypass AI.

# FAZA H — WORLD PACK HARDENING
- [ ] 80. Naruto WorldRuleProvider integration test pack
- [ ] 81. Bleach WorldRuleProvider integration test pack
- [ ] 82. Canon/divergence automated test scenarios
- [ ] 83. World-specific progression/evolution automated tests
- [ ] 84. World Pack update compatibility automated tests

## Acceptance direction Phase 80–84
World Packs must prove final Core compatibility, canon/divergence behavior, world-specific rules/progression/evolution and update compatibility before World Pack Creator production work begins.

# POST-ROADMAP EXTENSION — WORLD PACK CREATOR

**STATUS: DEFERRED UNTIL GLOBAL PHASE 1–84 ACCEPTED**

Nie rezerwujemy obecnie Phase 85. Po Phase 84 wykonujemy `POST-ROADMAP AUDIT FIRST` przeciwko exact final repo/API/schema.

WPC jest authoring/compiler layer nad finalnym Core, nie drugim Event Store, Memory, Retriever, NPC Brain, World Simulation, Save/Load ani Transaction Engine.

Robocza sekwencja bez numerów kanonicznych faz:
- `WPC-A` — final Core audit;
- `WPC-B` — authoring contract / Build Workspace / provenance / compiler trace / activation boundary;
- `WPC-C` — Original World vertical slice;
- `WPC-D` — Historical Research;
- `WPC-E` — Rule Compilation / Impact Analysis;
- `WPC-F` — Scenario Templates + canonical campaign bootstrap;
- `WPC-G` — Progressive/JIT expansion przez candidate revision;
- `WPC-H` — QUICK/STANDARD/DEEP UX + explainability/repair;
- `WPC-I` — scale/security/offline/process-death/update/Android hardening;
- `WPC-J` — final acceptance.

WPC używa finalnego wspólnego AiProvider/workload routing. AI-assisted, imported, hand-authored i generated pack kończą w jednym validated runtime World Pack contract. Draft/build workspace != active canon/Campaign Repository.

# NONCANONICAL AI R&D
TEMP-GM/Termux/localhost/llama.cpp/Vulkan/Bielik i inne konkretne model/runtime/provider eksperymenty pozostają R&D/reference evidence, nie production architecture.

R&D może być prowadzone równolegle przed Phase 48 wyłącznie bez canonical integration, production provider, AI-owned durable memory i mutation authority.

# FRONTEND
- [x] ACTIVE DEVELOPMENT / STYLE PRESERVATION BY PROJECT DECISION

Frontend może rozwijać funkcjonalność równolegle, ale zachowuje zaakceptowany język wizualny i nie wykonuje niepowiązanego globalnego redesignu.

# CROSS-CUTTING GATES / TESTS
Już zamknięte przez Phase 1–36 pozostają historycznie zaakceptowane i nie są ponownie otwierane bez nowego findingu.

Przyszłe obowiązkowe gates obejmują co najmniej:
- [ ] money conservation / ledger auditability where not yet fully covered
- [ ] unique item / ownership integrity where not yet fully covered
- [ ] NPC knowledge isolation and acquisition provenance
- [ ] temporal historical truth
- [ ] cache/index delete/rebuild -> no data loss
- [ ] AI provider/model/runtime replacement -> no campaign migration/data loss
- [ ] local AI player-agency + actor/action/target conformance
- [ ] provider crash/cancel/process death -> no partial committed turn
- [ ] cloud failure -> local continuation
- [ ] cloud/Director candidate cannot mutate authority or rewrite past COMMIT
- [ ] same stimulus + different persisted NPC traits/relationships can yield different legal decisions
- [ ] personality adaptation requires committed cause/provenance
- [ ] reputation/rumor remains holder-scoped belief
- [ ] `WORLD_WITHOUT_PLAYER` causal evolution + save/load/replay equality
- [ ] `SAME_WORLD_TWO_CAMPAIGNS` divergence explainable by player actions + world processes + controlled randomness
- [ ] background FACT does not automatically become player/NPC knowledge
- [ ] world-process/domain conservation for supported economy/population/resources/projects

# AKTUALNA NAJBLIŻSZA ZALEŻNOŚĆ
`Phase 37 — NPC Knowledge model + acquisition provenance`

Obowiązkowa sekwencja:
`READ ARCHITECTURE + ROADMAP + MAPA PLIKÓW -> AUDIT FIRST -> classify COMPLETE/PARTIAL/MISSING/BLOCKED -> minimal implementation -> targeted tests -> compatibility -> full JVM -> PR -> exact-SHA CI -> coordinator acceptance`.

Do acceptance Phase 37: **AUDIT FIRST / NOT GLOBALLY ACCEPTED**.

Future Hybrid AI, NPC individuality, Living World i post-roadmap WPC nie zmieniają tej kolejności.

# ZASADA AKTUALIZACJI
Roadmap ma pozostać aktywnym planem, nie changelogiem. Historyczne SHA/CI/findings/rozbudowane wcześniejsze opisy przenosimy do `docs/Historia projektu.md` i phase acceptance records. Status zmieniamy tylko na podstawie aktualnego repo i pełnego evidence.
