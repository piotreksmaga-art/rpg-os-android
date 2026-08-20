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
- Canonical accepted runtime through Phase 37: `7538c3ca16b5e74133f13ce611821d0699c798d0`.
- Phase 37 final independent post-hardening audit: PASS on candidate `53aa66931926e92ed7cbe0d68deff4f4ee2378d6`; `P37-POST-AUD-001..003` FIXED; no new findings.
- Audited GREEN evidence: full JVM `884` tests / `0` failures / `0` skipped on the exact audited app/test subtree; integration PR #69 preserved that subtree bit-for-bit on current master.
- Separate push-triggered exact-merge-SHA Actions run for `7538c3ca...` was not surfaced by the available connector at coordinator close; no run ID is invented. Acceptance is based on independent audit + exact subtree identity + inspected GREEN evidence.
- Pełne historyczne SHA/CI/artifacts/findingi: `docs/Historia projektu.md` i phase acceptance records.
- Następny implementacyjny gate: **Phase 38 — GM/NPC/PC/player-visible knowledge separation + belief/reputation/access visibility boundaries, AUDIT FIRST**.
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
- [x] 37. World Actor Knowledge, Expertise & Acquisition Provenance
- [-] 38. GM/NPC/PC/player-visible knowledge separation + belief/reputation/access visibility boundaries
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
Phase 37 jest zaakceptowanym uniwersalnym epistemic core świata, nie tylko tabelą `NPC knowledge`. Obsługuje indywidualnych i instytucjonalnych holderów bez budowania osobnych systemów wiedzy dla każdego gatunku gry.

Accepted Phase 37 contract obejmuje:
- typed `KnowledgeHolder`/równoważny model dla character/NPC, PC, organization, military command/unit, city/state/agency, intelligence service, research institution/team i World Pack-defined holderów;
- rozdzielenie `FACT`, `INFORMATION/CLAIM`, `EVIDENCE`, `KNOWLEDGE STATE`, `BELIEF/ESTIMATE/HYPOTHESIS/SUSPICION`;
- twarde invarianty `FACT != KNOWLEDGE`, `KNOWLEDGE != BELIEF`, `KNOWLEDGE != EXECUTABLE SKILL`, `KNOWLEDGE != DECISION`;
- granular claim identity zamiast jednego omniscient blobu „wiedza o obiekcie”;
- typed/data-driven acquisition provenance: observation, communication, document/report/media, rumor/hearsay, education/training, research/experiment, inference, institutional sharing, interrogation, surveillance/espionage, memory, World Pack mechanics, legacy/unknown;
- immutable/traceable acquisition evidence + current holder epistemic state/projection;
- acquisition lineage umożliwiający śledzenie event/observation -> report -> summary -> recipient -> later sharing;
- legacy unknown provenance pozostaje `LEGACY` / `UNKNOWN_NOT_RECORDED`; bez fabrykowania przeszłości;
- informacje mogą być true/false/partial/uncertain/contradicted/outdated bez zmiany authoritative FACT;
- quality metadata/semantics dla confidence, precision, freshness, completeness, source reliability, corroboration i uncertainty gdzie domena tego wymaga;
- sprzeczne evidence może współistnieć zamiast bezwarunkowego last-write-wins;
- typed/data-driven knowledge domains + expertise hooks; expertise poprawia recognition/interpretation/estimate/inference, ale nie nadaje zdolności wykonawczej;
- personal knowledge, institutional knowledge i role-accessible knowledge są rozdzielone;
- wiedza instytucji nie przecieka automatycznie do wszystkich jej członków;
- dokumenty/raporty/archiwa/notatki/mapy mogą być nośnikami evidence bez bycia autonomicznymi decision actors;
- access/secrecy metadata przygotowują Phase 38, ale pełne GM/NPC/PC/player-visible enforcement pozostaje Phase 38;
- model jest temporal-ready dla późniejszego pytania „co holder wiedział wtedy?”, pełny historical query engine pozostaje Phase 39;
- canonical acquisition korzysta z Single Truth Mutation Path / TurnTransaction / Event-Causal evidence / idempotency / rollback / snapshot/replay / schema safety;
- AI, raw SQL, ContextBuilder i generic StatePatch nie posiadają authority do tworzenia legalnego canonical acquisition;
- ContextBuilder używa holder-scoped typed Knowledge projection/API zamiast definiować własny kontrakt bezpośrednimi legacy SQL reads.

Dalsze Phase 38–47:
- FACT/BELIEF/NARRATIVE i GM/NPC/PC/player-visible/access separation;
- historical truth queries są temporalne, nie present-state substitution;
- Scheduler owns future evaluation points/deadlines, not guaranteed outcomes;
- retrieval jest bounded/iterative i context actor/time/visibility-safe;
- cloud context, gdy później aktywny, jest minimalny i sanitised zamiast whole-save export.

# FAZA D — GM ENGINE / HYBRID AI FOUNDATION
- [ ] 48. AI Provider & Hybrid Local-First Inference Architecture
- [-] 49. Structured GM Output contract
- [ ] 50. Universal Mechanics & Combat Resolution integration
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
- `ACTIVE_PLAYER_CHARACTER CONTROL AUTHORITY = USER ONLY`; AI/MG/Director/NPC Brain/World Simulation nie wykonują dobrowolnych akcji, dialogu, wyborów ani control transfer za aktualnego PC;
- brak user input/odpowiedzi nie oznacza zgody; `MECHANICAL CONSEQUENCE != VOLITIONAL PLAYER ACTION`;
- structural ACTOR/ACTION/TARGET preservation;
- provider conformance: agency, direction, knowledge isolation, FACT/BELIEF, stop point, invented ability/dialogue, internal-context leak, structured output, mutation boundary;
- AI crash/cancel/process-death -> no partial committed turn;
- real Android local integration/performance evidence.

Konkretny model, provider, format i runtime są adapter/evidence candidates, nie canonical lock-in.

Phase 50 Universal Mechanics & Combat Resolution acceptance obejmuje co najmniej:
- jeden `MechanicalActorView`/równoważny adapter contract dla Active PC, NPC, former PC, monster/summon/vehicle/unit/group bez przepisywania accepted Player Domain Phase 1–36;
- persistent World Actor Mechanical State dla non-player actors: dynamic attributes/resources/skills/executable abilities/traits/resistances/equipment/components/conditions/wounds/cooldowns/modifiers;
- `GENERATION TEMPLATE != CURRENT MECHANICAL STATE`; materialized actor nie jest rerollowany przy kolejnym encounter;
- `ENCOUNTER DIFFICULTY MUST EMERGE FROM WORLD STATE, NOT PLAYER POWER SCALING`;
- World Actor Generation Core: composable archetypes + REQUIRED/CONDITIONAL/WEIGHTED/FORBIDDEN rules + controlled variance + persistent hierarchical seed + power envelope + provenance;
- ordinary generation nie dostaje Player mechanical power jako difficulty input; causal counter-selection może używać tylko legalnej holder-scoped wiedzy aktora/organizacji;
- `CombatIntent != Outcome`; Decision Engine/validated player command wybiera intencję, Combat Engine tylko ją rozstrzyga;
- immutable relevant Combat Snapshot + eligibility/preconditions + spatial/timing + detection + reaction/interrupt + clash + contest + effect + objectives + resolution evidence;
- reaction wymaga capability + perception/knowledge + time + resource; hidden FACT nie daje automatycznej reakcji;
- effects są typed/compositional i mogą obejmować HP/wounds/resources/status/movement/equipment/structure/morale/cohesion/formation/environment zamiast jednego damage number;
- optional TargetComponentModel obsługuje anatomy oraz non-biological components;
- deterministic/replay-safe RNG/evidence; same committed inputs/rules/random evidence -> same outcome;
- Combat Engine nie zapisuje authority bezpośrednio: wynik -> domain ChangeSets -> validation -> TurnTransaction -> Event/Causal evidence -> COMMIT -> narration;
- mechanical consequence pozostaje odrębna od voluntary PC action;
- extreme mismatch może być rozstrzygany deterministic bounds, bez obowiązkowego fixed critical-success/failure percentage;
- adversarial tests: no player scaling, no retroactive stat buff, no omniscient perfect counter, unavailable ability rejected, hidden attack cannot be auto-reacted to, rollback/retry/replay equality, outcome explainability.

# FAZA E — PAMIĘĆ I DŁUGOTERMINOWA SYMULACJA
- [-] 55. Working Memory — AI provider/model is not durable owner
- [-] 56. Episodic Memory — AI provider/model is not durable owner
- [-] 57. Semantic Campaign Memory — AI provider/model is not durable owner
- [ ] 58. Memory Consolidation without recursive summary degradation
- [ ] 59. Vector/Semantic Retrieval engine/index integration
- [ ] 60. Time Skip Processor + Scheduler/WorldProcess orchestration
- [-] 61. NPC Brain + persistent individuality/personality/values/goals/fears/emotional state/relationships
- [-] 62. NPC Decision Engine + knowledge/memory/social-role constrained autonomy
- [ ] 63. World Simulation LOD 0–3 + World Actor mechanical materialization + Combat LOD integration
- [ ] 64. Background-world causal simulation: organizations/economy/projects/demography/wars/knowledge propagation/conflict resolution + controlled randomness

# FAZA F — DIRECTOR / JAKOŚĆ NARRACJI
- [ ] 65. Director Engine + optional Cloud Director / candidate bundles
- [-] 66. Narrative Promise Ledger
- [ ] 67. Pacing Metrics
- [ ] 68. Anti-Repetition
- [ ] 69. Narrative Style Profile
- [-] 70. Chronicle generated from committed structured reality

# FAZA G — SAVE / DEBUG / SKALA
- [-] 71. Save/Load integration + Persistent World / Character Succession
- [ ] 72. Branching without full database duplication
- [-] 73. Backup System
- [-] 74. Observability metrics
- [ ] 75. Replay Debugger
- [-] 76. Integrity Test Suite
- [-] 77. Long Campaign Stress Tests
- [-] 78. Android performance profiling/optimization + Adaptive Turn Runtime
- [-] 79. AI workload / provider / model / runtime routing + Response-Time Policy

# FAZA H — WORLD PACK HARDENING
- [ ] 80. Naruto WorldRuleProvider integration test pack
- [ ] 81. Bleach WorldRuleProvider integration test pack
- [ ] 82. Canon/divergence automated test scenarios
- [ ] 83. World-specific progression/evolution automated tests
- [ ] 84. World Pack update compatibility automated tests

# POST-ROADMAP EXTENSION — WORLD PACK CREATOR

**STATUS: DEFERRED UNTIL GLOBAL PHASE 1–84 ACCEPTED**

Nie rezerwujemy obecnie Phase 85. Po Phase 84 wykonujemy `POST-ROADMAP AUDIT FIRST` przeciwko exact final repo/API/schema.

WPC jest authoring/compiler layer nad finalnym Core, nie drugim Event Store, Memory, Retriever, NPC Brain, World Simulation, Save/Load ani Transaction Engine.

# NONCANONICAL AI R&D
TEMP-GM/Termux/localhost/llama.cpp/Vulkan/Bielik i inne konkretne model/runtime/provider eksperymenty pozostają R&D/reference evidence, nie production architecture.

R&D może być prowadzone równolegle przed Phase 48 wyłącznie bez canonical integration, production provider, AI-owned durable memory i mutation authority.

# FRONTEND
- [x] ACTIVE DEVELOPMENT / STYLE PRESERVATION BY PROJECT DECISION

Frontend może rozwijać funkcjonalność równolegle, ale zachowuje zaakceptowany język wizualny i nie wykonuje niepowiązanego globalnego redesignu.

# CROSS-CUTTING GATES / TESTS
Już zamknięte przez Phase 1–37 pozostają historycznie zaakceptowane i nie są ponownie otwierane bez nowego findingu.

Przyszłe obowiązkowe gates obejmują m.in. temporal/visibility isolation, hybrid-AI conformance, player agency, Living World causality, World Actor mechanical persistence, Combat LOD conservation, no player scaling/rubber-banding oraz adaptive response-time correctness. Szczegółowy kontrakt pozostaje w `docs/Architektura projektu.md` i acceptance direction odpowiednich faz.

# AKTUALNA NAJBLIŻSZA ZALEŻNOŚĆ
`Phase 38 — GM/NPC/PC/player-visible knowledge separation + belief/reputation/access visibility boundaries`

Obowiązkowa sekwencja:
`READ ARCHITECTURE + ROADMAP + MAPA PLIKÓW -> AUDIT FIRST -> classify COMPLETE/PARTIAL/MISSING/BLOCKED -> minimal implementation -> targeted tests -> compatibility -> full JVM -> PR -> exact-SHA CI -> coordinator acceptance`.

Do acceptance Phase 38: **AUDIT FIRST / NOT GLOBALLY ACCEPTED**.

Future Hybrid AI, NPC individuality, Living World i post-roadmap WPC nie zmieniają tej kolejności.

# ZASADA AKTUALIZACJI
Roadmap ma pozostać aktywnym planem, nie changelogiem. Historyczne SHA/CI/findings/rozbudowane wcześniejsze opisy przenosimy do `docs/Historia projektu.md` i phase acceptance records. Status zmieniamy tylko na podstawie aktualnego repo i pełnego evidence.
