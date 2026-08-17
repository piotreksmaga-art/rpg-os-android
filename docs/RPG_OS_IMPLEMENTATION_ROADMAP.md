# RPG OS — KANONICZNA KOLEJNOŚĆ PRAC I CHECKLISTA

Status: ACTIVE / CANONICAL ROADMAP
Architecture: `docs/RPG_OS_MASTER_ARCHITECTURE.md`
Coordination: `docs/PARALLEL_WORK_COORDINATION.md`, `docs/architecture/CHAT_COORDINATION_POLICY.md`
Operational protocol: `docs/PROJECT_WORK_PROTOCOL.md`

> Aktualizacja 2026-08-17: Transaction Integrity — Phase 26–29 została globalnie zaakceptowana przez koordynatora na exact runtime SHA `45ff53457bff16c4ff72a4cccdecac89124109c3`, po finalnym architectural enforcement repair WORK-20260817-026 i niezależnych exact-SHA rewalidacjach CHAT-4 (`WORK-20260817-027`) oraz CHAT-5 (`WORK-20260817-028`). Następny blok to Phase 30–32, rozpoczynany od AUDIT FIRST.

> Future architecture update 2026-08-17: zaakceptowano kierunek native/local AI-GM jako wymagania przyszłych faz, bez rozpoczęcia Phase 48 i bez zmiany aktualnej kolejności roadmapy. Model, inference runtime i hardware backend mają być wymienne; RPG OS pozostaje właścicielem durable reality/memory/rules/transactions. TEMP-GM/Termux/localhost/llama.cpp/Bielik pozostają niekanoniczną infrastrukturą R&D/reference baseline.

> Product strategy update 2026-08-17: RPG OS przyjmuje **LOCAL-FIRST, PROVIDER-NEUTRAL, CLOUD-LATER**. Pierwszym produkcyjnym GM providerem i priorytetem acceptance Phase 48 ma być realny local Android AI-GM. Płatny/cloud provider zostaje świadomie odłożony na późny etap projektu jako opcjonalna warstwa jakości narracyjnej i nie jest wymagany do ukończenia podstawowego GM Engine ani Phase 48. Przyszły local/cloud/hybrid routing musi używać tych samych contractów, validators, mutation boundary i durable memory ownership.

## Statusy
- `[x] COMPLETE` — globalnie zaakceptowany etap; wdrożony i zweryfikowany zgodnie z wymaganiami danego etapu.
- `[-] PARTIAL` — realny fundament istnieje, ale nie spełnia pełnego kontraktu MASTER.
- `[ ] MISSING` — docelowa implementacja nie istnieje w aktualnym runtime.
- `[!] BLOCKED` — implementacja/kandydat istnieje, ale etap nie może zostać globalnie zaakceptowany z powodu nierozwiązanego blockera.

Sama klasa, tabela, raport audytowy albo zielone CI nie oznacza COMPLETE. Globalną akceptację zmienia koordynator po sprawdzeniu implementacji, integracji, regresji i dowodów.

# AKTUALNY BASELINE PROJEKTU

- Canonical accepted runtime through Phase 29: `45ff53457bff16c4ff72a4cccdecac89124109c3`.
- Exact acceptance CI: run #703 / ID `32038070404` — SUCCESS.
- Final exact-SHA revalidation: CHAT-4 PASS (`WORK-20260817-027`), CHAT-5 PASS (`WORK-20260817-028`).
- Final architectural enforcement repair: `WORK-20260817-026`.
- Phase 30: **NOT STARTED / AUDIT FIRST**.
- Future AI strategy is canonical documentation only: **LOCAL-FIRST / PROVIDER-NEUTRAL / CLOUD-LATER**; **Phase 48 remains NOT STARTED** and current Phase 30–32 order is unchanged.

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

# FAZY 1–19 — WYNIKI ZAAKCEPTOWANEGO PLAYER-CORE FOUNDATION

Poniższe wpisy porządkują wcześniej zaakceptowane fazy. Nie zmieniają ich zakresu ani acceptance status; są skrótem kanonicznego wyniku, a szczegółowe dowody pozostają w historycznych dokumentach implementacyjnych, acceptance records, testach i audytach repozytorium.

## FAZA 1 — WYNIK
Unified Repository + active campaign identity jest **COMPLETE**. Spełniono pięć kryteriów delta Phase 1; dowód implementacyjny i CI znajduje się w `docs/PHASE_1_UNIFIED_REPOSITORY.md`.

## FAZA 2 — WYNIK
Campaign Source of Truth + FACT/BELIEF/NARRATIVE + provenance jest **COMPLETE**. Typowana prawda kampanii jest persisted, odseparowana per campaign, przekazywana do ContextBundle i interpretowana przez backend zgodnie z kontraktem. Generic StatePatch nie może ominąć truth API. Migracja istniejących kampanii jest addytywna i nie fabrykuje historycznego provenance. Dowody: `docs/PHASE_2_SOURCE_OF_TRUTH.md`.

## FAZA 3 — WYNIK
Player State Contract: Persistent / Derived / Runtime jest **COMPLETE**. ActivePlayerRef jest persisted per campaign, legacy player resolution jest wykonywany jednorazowo przy migracji, a następnie główne read paths używają jednego Player UID. PlayerStateStore i CampaignRepository udostępniają canonical player read contract, ContextBundle przekazuje oddzielne warstwy stanu do GM, a legacy CharacterPanel pozostaje presentation adapterem. Dowody: `docs/PHASE_3_PLAYER_STATE_CONTRACT.md`.

## FAZA 4 — WYNIK
Dynamic StatDefinition / PlayerStat oraz ResourceDefinition / PlayerResource są **COMPLETE**. Runtime posiada typowany, dynamiczny model statystyk i zasobów gracza zamiast zamkniętej listy pól specyficznych dla jednego uniwersum; stan jest powiązany ze stabilną tożsamością kampanii i gracza.

## FAZA 5 — WYNIK
DerivedValueResolver + modifier model jest **COMPLETE**. Wartości pochodne są rozdzielone od persistent authority i obliczane przez deterministyczny model zależności/modyfikatorów zamiast utrwalania wyniku prezentacyjnego jako drugiego źródła prawdy.

## FAZA 6 — WYNIK
TalentProfile + PotentialProfile jest **COMPLETE**. Talenty i potencjał mają jawny model domenowy odseparowany od bieżącego poziomu statystyki/umiejętności i mogą uczestniczyć w dalszych mechanikach bez utożsamiania potencjału z osiągniętym mastery.

## FAZA 7 — WYNIK
Skill model jest **COMPLETE**. Umiejętności gracza mają typowaną tożsamość i stan runtime, z zachowaniem rozdzielenia definicji, posiadania i poziomu/mastery oraz z integracją z player-state foundation.

## FAZA 8 — WYNIK
Technique model jest **COMPLETE**. Techniki są osobnym typowanym bytem domenowym, nie aliasem skill/stat; ich stan może być walidowany, odczytywany i zmieniany przez późniejszy canonical player pipeline.

## FAZA 9 — WYNIK
Innate / Racial / Bloodline / Evolution runtime model jest **COMPLETE**. Runtime potrafi reprezentować wrodzone i ewolucyjne właściwości postaci jako odrębną kategorię domenową, bez wymuszania ich reprezentacji jako zwykłych skills lub techniques.

## FAZA 10 — WYNIK
Inventory model jest **COMPLETE**. Ekwipunek magazynowy/stacki mają typowaną authority, stabilne identyfikatory i operacje domenowe stanowiące podstawę późniejszej transakcyjnej integracji.

## FAZA 11 — WYNIK
Equipment domain/loadout model jest **COMPLETE**. Założone wyposażenie i loadout są odseparowane od samego posiadania przedmiotu; obowiązują typowane operacje i walidacja domenowa zamiast traktowania equipment jako pola prezentacyjnego.

## FAZA 12 — WYNIK
OwnershipRecord domain jest **COMPLETE**. Własność ma własny model authority i historię temporalną, z walidacją udziałów, stron i zmian własności; późniejsze fazy transakcyjne wykorzystują tę authority zamiast ją duplikować.

## FAZA 13 — WYNIK
Financial Ledger / Economy model jest **COMPLETE**. Finanse posiadają append-oriented authoritative ledger, trwałe identyfikatory operacji oraz rebuildable balance projection; saldo nie jest niezależnym drugim źródłem prawdy.

## FAZA 14 — WYNIK
Assets / debts / obligations / net-worth model jest **COMPLETE**. Model ekonomiczny obejmuje nie tylko bieżące środki, ale również aktywa, zobowiązania i projekcję wartości netto, zachowując rozdzielenie authority i wartości wyliczanych.

## FAZA 15 — WYNIK
DevelopmentProject model jest **COMPLETE**. Projekty rozwojowe posiadają typowany lifecycle, uczestników/referencje, stan i reguły legalnych przejść, które są później respektowane przez canonical mutation pipeline.

## FAZA 16 — WYNIK
PlayerCommand contract jest **COMPLETE**. Intencja mechaniczna gracza ma typowany kontrakt wejściowy i stabilną tożsamość, dzięki czemu późniejsze resolution nie musi interpretować swobodnego tekstu jako bezpośredniej durable mutation.

## FAZA 17 — WYNIK
PlayerChangeSet contract jest **COMPLETE**. Wynik mechaniczny jest reprezentowany jako typowany proposal zmian z deterministyczną strukturą, referencjami i walidacją; ChangeSet jest granicą pomiędzy resolution a późniejszym commitowaniem authority.

## FAZA 18 — WYNIK
PlayerDomainEngine orchestration jest **COMPLETE**. Canonical player resolution scala command validation, domenowe resolution i budowę proposal bez bezpośredniego zapisu authoritative state. Późniejsze fazy rozszerzają tę samą ścieżkę zamiast tworzyć drugi Player Engine.

## FAZA 19 — WYNIK
WorldRuleProvider contract jest **COMPLETE**. World-specific authority jest przypinana do resolution i uczestniczy w COMMAND_PRECHECK oraz dokładnie jednym finalnym DRAFT_EFFECT_CHECK. Core nie przejmuje reguł konkretnego uniwersum, a późniejsza progresja musi przejść przez tę samą przypiętą World Pack authority. Kanoniczne dowody: `docs/architecture/PHASE19_WORLD_RULE_PROVIDER_CANONICAL_SCOPE.md`, `docs/architecture/PHASE19_ACCEPTANCE.md` oraz `docs/architecture/PHASE19_DEFERRED_FINDINGS.md`.

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

# FAZA B — INTEGRALNOŚĆ KAMPANII
- [x] 26. Single Truth Mutation Path enforcement
- [x] 27. Turn Transaction atomic commit/rollback
- [x] 28. Idempotency + double-commit protection
- [x] 29. Crash recovery / LAST VALID COMMIT
- [-] 30. Event Store append-only
- [-] 31. Causal Graph
- [ ] 32. Authoritative / Derived / Cache / Presentation runtime enforcement
- [-] 33. Snapshot System
- [-] 34. Automatic snapshot retention max 6
- [-] 35. Canon Divergence
- [-] 36. Schema Versioning + migration safety + legacy provenance

# FAZA 26–29 — TRANSACTION INTEGRITY — COMPLETE

**STATUS: ACCEPTED / COMPLETE**

Accepted runtime SHA:
`45ff53457bff16c4ff72a4cccdecac89124109c3`

Exact acceptance CI:
- run #703
- ID `32038070404`
- conclusion `success`

Final exact-SHA revalidation:
- CHAT-4 / `WORK-20260817-027` — PASS
- CHAT-5 / `WORK-20260817-028` — PASS

Final repair:
- `WORK-20260817-026` — final architectural enforcement repair.

Accepted scope:
- Phase 26: supported normal gameplay uses one canonical commit path through repository `commitTurn(...)` and `TurnTransaction`; writable gameplay DB remains private/internal and production initialization installs required schema/receipt/guard enforcement before gameplay mutation;
- Phase 27: authoritative effects and commit evidence are atomic under one turn transaction; rollback leaves no partial committed reality;
- Phase 28: durable transaction-level idempotency binds campaign/turn/command/transaction identity and semantic PlayerChangeSet fingerprint; committed retry does not duplicate effects and semantic mismatch fails closed;
- Phase 29: campaign-scoped monotonic committed ordering and recovery derive LAST VALID COMMIT from committed transaction receipts, while legacy V1 receipts without historical ordering retain `commitOrder = NULL` rather than fabricated chronology.

Cross-boundary guarantees verified by final revalidation include full proposal/effect/receipt binding, retry/rollback safety, G28→G29 migration preservation, read-only recovery, supported end-to-end progression commit without creation of a second progression authority, and no Phase-30 Event Store implementation.

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
- [ ] 46. Context Budget Manager — **future requirement: ModelProfile/AiCapability-aware; no fixed CTX/output budget**
- [ ] 47. Iterative Retrieval + missing-context loop

# FAZA D — GM ENGINE
- [ ] 48. AI Provider & Local Inference Architecture — **LOCAL-FIRST production acceptance**
- [-] 49. Structured GM Output contract — **provider-independent; turn/capability-dependent required outputs**
- [ ] 50. Mechanics Resolution integration
- [-] 51. Consistency Validator
- [ ] 52. Counterfactual Guard
- [ ] 53. Repair Pass dla proposal/narrative
- [ ] 54. Committed narrative delivery only after valid transaction

## FUTURE REQUIREMENTS — PHASE 48 (NOT STARTED)

Phase 48 ma objąć kontrakty architektoniczne potrzebne dla wymiennego AI oraz **pierwszą realną produkcyjną integrację local Android AI-GM**. Nie jest obecnie autoryzowana do implementacji. Repo-first audit w momencie rozpoczęcia fazy ma rozstrzygnąć minimalny rzeczywisty delta.

**Acceptance priority:** Phase 48 nie może zostać uznana za ukończoną wyłącznie dzięki abstrakcji providerów lub testowemu bridge. Wymagany jest rzeczywisty local Android `AiProvider` działający przez kanoniczny `LocalInferenceRuntime`, z lifecycle, structured output, conformance i ochroną transaction/memory boundaries. Płatny/cloud provider nie jest wymagany do Phase 48 i pozostaje odłożony do późnej bramki projektu.

Docelowy scope/acceptance powinien uwzględnić:
- `AiProvider` canonical provider-independent semantic contract;
- `AiCapabilityContract`;
- data-driven `ModelProfile`;
- `LocalInferenceRuntime` poniżej AiProvider;
- `RuntimeBackend` CPU/GPU/NPU/AUTO oraz oddzielny `RuntimeBackendSelector`;
- minimalny `ModelLifecycleController` dla load/unload/cancel/error/OOM/process restart i artifact verification;
- allowlisted `GmToolGateway`, bez raw DB i mutation authority;
- provider-independent semantic/structured output contract;
- **pierwszy produkcyjny local Android provider end-to-end**, nie tylko TEMP-GM/localhost bridge;
- **VOLITIONAL PLAYER ACTION SOURCE = USER / VALIDATED PLAYER COMMAND ONLY**;
- strukturalne **ACTOR/ACTION/TARGET preservation**;
- provider/model conformance suite: player agency, direction, NPC knowledge isolation, FACT/BELIEF, stop point, invented abilities/dialogue, internal-context leak, structured output i canonical mutation boundary;
- provider crash/cancel/process death nie może tworzyć partial committed turn;
- model/runtime/backend replacement nie może wymagać migracji kampanii;
- real Android integration/validation jako acceptance evidence.

Nie hardcodować jako canonical wymogu konkretnego modelu (Bielik/PLLuM/Gemma), formatu (GGUF) ani runtime (LiteRT-LM/ExecuTorch/llama.cpp). Wynik R&D ma być evidence, nie architecture lock-in. Nie kodować także założenia `local = słaby` / `cloud = dobry`; jakość modelu jest zmienna, provider jest wymienny.

# FAZA E — PAMIĘĆ I DŁUGOTERMINOWA SYMULACJA
- [-] 55. Working Memory — **AI provider/model nie jest durable owner**
- [-] 56. Episodic Memory — **AI provider/model nie jest durable owner**
- [-] 57. Semantic Campaign Memory — **AI provider/model nie jest durable owner**
- [ ] 58. Memory Consolidation without recursive summary degradation — **provider-independent durable memory invariant**
- [ ] 59. Vector/Semantic Retrieval engine/index integration — **retrieval reconstructs context after model/runtime replacement**
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
- [-] 78. Android performance profiling/optimization — **local inference: storage/load/TTFT/prefill/decode/RAM/KV/battery/thermal/OOM/cancel/process-death/sustained turns**
- [-] 79. AI Provider / Model / Runtime routing — **separate ModelRouter from RuntimeBackendSelector; do not change roadmap position**

# FAZA H — WORLD PACK HARDENING
- [ ] 80. Naruto WorldRuleProvider integration test pack
- [ ] 81. Bleach WorldRuleProvider integration test pack
- [ ] 82. Canon/divergence automated test scenarios
- [ ] 83. World-specific progression/evolution automated tests
- [ ] 84. World Pack update compatibility automated tests

# LATE-PROJECT — CLOUD / PAID AI PROVIDER INTEGRATION — DEFERRED

Ten blok jest **kanonicznym późnym wymaganiem produktowym**, ale pozostaje celowo bez numeru fazy, aby nie zmieniać obecnej kolejności 30–84. Numer i dokładne miejsce execution zostaną nadane dopiero przy późnym harmonogramowaniu projektu.

Nie rozpoczynać go przed stabilnym, zaakceptowanym local GM pipeline i odpowiednimi systemami jakości/skalowania. Zakres ma obejmować co najmniej:
- implementację co najmniej jednego realnego cloud/paid `AiProvider` na tym samym provider-independent kontrakcie co local AI;
- brak nowych mutation privileges, raw DB access albo AI-owned durable memory;
- te same player-agency, actor/action/target, knowledge, FACT/BELIEF, stop-point i structured-output conformance gates;
- przełączenie **LOCAL ↔ CLOUD** bez migracji kampanii, utraty eventów, pamięci, statystyk, wiedzy NPC albo divergence;
- możliwość trybu LOCAL-ONLY oraz opcjonalnie CLOUD/HYBRID, bez uzależnienia poprawności kampanii od dostępności sieci lub płatnego API;
- porównawczy benchmark jakości narracji, dialogu, różnorodności, continuity i złożonych scen przy tym samym kontrolowanym ContextBundle;
- ewentualny hybrid `ModelRouter`, który może kierować wybrane wysokowartościowe sceny do silniejszego providera, ale nie może zmieniać authority ani legalnej ścieżki commit;
- failure/cancel/rate-limit/network-error handling bez partial committed turn;
- provider replacement i provider removal bez migracji durable campaign state.

Celem tego bloku jest podniesienie jakości GM/narracji, nie naprawianie braków mechaniki, pamięci, retrieval, knowledge isolation ani transaction integrity. Jeżeli cloud model jest wymagany do poprawności kampanii, wcześniejsza architektura jest niekompletna.

# TEMP LOCAL AI-GM / R&D — NONCANONICAL IMPLEMENTATION TRACK

TEMP-GM pozostaje dozwolonym laboratorium semantycznym i device/model benchmark harness. Termux, localhost bridge, llama.cpp/Vulkan i Bielik reference model nie są production architecture.

Dozwolone równoległe R&D bez rozpoczęcia Phase 48 może obejmować feasibility/benchmarking Bielik/PLLuM/Gemma/LiteRT-LM/ExecuTorch/przyszłych odpowiedników, pod warunkiem:
- osobny scope/branch;
- brak canonical runtime integration;
- brak statusu COMPLETE/ACCEPTED dla Phase 48;
- brak production provider;
- brak AI-owned durable memory;
- brak mutation authority;
- wynik tylko jako evidence dla przyszłego Phase-48 audit.

# FRONTEND
- [x] ACTIVE DEVELOPMENT / STYLE PRESERVATION BY PROJECT DECISION

Frontend może być rozwijany wraz z funkcjonalnością, ale należy zachować zaakceptowany styl wizualny i unikać niepowiązanego redesignu. Zmiany wymagane przez backend/integrację pozostają dozwolone zgodnie z aktualną decyzją użytkownika i coordination policy.

# CROSS-CUTTING TEST GAPS
- [ ] save -> close -> load authoritative equality
- [ ] snapshot -> replay -> same authoritative state
- [ ] old campaign -> migration -> valid load
- [x] failed turn -> rollback -> no partial mutation — Phase 27/29 accepted transaction/recovery gate
- [x] retry transaction -> no duplicate effects — Phase 28 accepted idempotency gate
- [x] simulated crash -> last valid commit recovery — Phase 29 accepted recovery gate
- [x] no unexplained permanent regression — Phase 22 accepted proposal gate; transaction enforcement now covered through Phase 29
- [ ] money conservation / ledger auditability
- [ ] unique item / ownership integrity
- [ ] NPC knowledge isolation
- [ ] temporal historical truth
- [ ] divergence survives canon updates
- [x] CharacterPanelSnapshot delete/rebuild -> no data loss — Phase 24 accepted read-model gate
- [ ] cache/index delete/rebuild -> no data loss
- [ ] AI provider/model replacement -> no campaign migration/data loss
- [ ] local AI player-agency + actor/action/target conformance
- [ ] provider crash/cancel/process death -> no partial committed turn
- [ ] future LOCAL ↔ CLOUD provider switch -> same authoritative campaign / no migration
- [ ] hybrid routing -> same authority/memory/transaction semantics as single-provider mode

# AKTUALNA NAJBLIŻSZA ZALEŻNOŚĆ

Runtime through Phase 29 jest globalnie **ACCEPTED / COMPLETE**.

Następny blok:
`Phase 30–32 — Event Store / Causal Graph / runtime truth-layer enforcement`

Obowiązkowa sekwencja:
`AUDIT FIRST -> classify COMPLETE / PARTIAL / MISSING / BLOCKED -> coordinator work split -> explicit implementation authorization`

Do czasu zakończenia audytu i jawnej decyzji koordynatora:
**PHASE 30–32 = NOT ACCEPTED / IMPLEMENTATION REQUIRES EXPLICIT AUTHORIZATION.**

Future AI requirements nie zmieniają tej kolejności. Strategia pozostaje **LOCAL-FIRST / PROVIDER-NEUTRAL / CLOUD-LATER**. **Phase 48 pozostaje NOT STARTED**; dopuszczone jest wyłącznie odseparowane R&D/evidence gathering zgodne z MASTER, a cloud/paid integration pozostaje late-project deferred.

# ZASADA AKTUALIZACJI ROADMAPY

Po każdym etapie zmieniaj globalny status wyłącznie z dowodem obejmującym właściwy dla etapu runtime/schema/migration/integration/test/build/CI oraz niezależne audyty, jeżeli zostały wymagane. Raport workera `PASS` lub `COMPLETE` nie jest sam w sobie globalnym `ACCEPTED`.