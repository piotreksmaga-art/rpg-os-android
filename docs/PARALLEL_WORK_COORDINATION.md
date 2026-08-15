# RPG OS — PARALLEL WORK COORDINATION

Status: CANONICAL OPERATIONAL PROTOCOL

Ten dokument definiuje sposób równoległej pracy wielu sesji/chatów nad jednym repozytorium RPG OS.

## 1. Model pracy
Jedna sesja pełni rolę COORDINATOR. Pozostałe sesje są WORKERS realizującymi jawnie przydzielone, możliwie rozłączne zadania.
Repozytorium `master` pozostaje wspólnym technicznym źródłem prawdy. Dokument MASTER i roadmapa definiują architekturę i kolejność zależności.
Koordynator nie zakłada, że posiada bieżący stan innych sesji. Stan pracy potwierdza przez repozytorium, commity, CI oraz jawne raporty.

## 2. Role
### COORDINATOR
Odpowiada za odczyt MASTER/roadmapy/protokołu, kontrolę master i CI, podział pracy, zależności, ownership zakresów, konflikty, statusy, audyt wyników oraz globalne aktualizacje roadmapy.

### WORKER
Pracuje wyłącznie w przydzielonym zakresie, sprawdza master przed rozpoczęciem i przed zapisem, nie wchodzi w rezerwacje innych workerów, wykonuje testy/build/CI i raportuje commit SHA, pliki, testy oraz problemy. Konflikt zakresu oznacza STOP/BLOCKED.

## 3. Work Item
Identyfikator: `WORK-YYYYMMDD-NNN`.
Minimalny rekord: workId, title, ownerRole/worker label, phase, status, objective, allowedScope, reservedFilesOrSubsystems, forbiddenScope, dependencies, baselineCommit, resultCommit, ciStatus, startedAt, completedAt, notes/blockers.
Statusy: `PLANNED | READY | ACTIVE | BLOCKED | REVIEW | COMPLETE | CANCELLED`.

## 4. Zasada rezerwacji zakresu
Dwa aktywne WORK ITEMS nie mogą równocześnie modyfikować tego samego authoritative subsystemu lub tych samych plików bez jawnej zgody koordynatora. Rezerwacja może dotyczyć pliku, katalogu, migracji/schema, repository API, subsystemu domenowego lub dokumentu kanonicznego. Nakładanie zakresów => drugi worker zatrzymuje implementację i zgłasza konflikt.

## 5. Baseline freshness
Przed rozpoczęciem kodowania worker sprawdza aktualny `master`. Jeżeli master zmienił się od `baselineCommit`, analizuje nowe commity, wpływ na zakres i aktualizuje baseline przed kontynuacją.

## 6. Zależności i równoległość
Równolegle wykonujemy tylko zadania, których zależności na to pozwalają. Najwcześniejsza brakująca zależność ma pierwszeństwo. Audyty read-only mogą przygotowywać przyszłe prace bez implementowania ich przed zależnościami.

## 7. Zasady zapisu do master
Nie resetuj master, nie force-pushuj bez jawnego polecenia, nie usuwaj cudzych poprawnych zmian, nie rozwiązuj konfliktu ślepym nadpisaniem. Przed zapisem ponownie odczytaj aktualną wersję modyfikowanego pliku. Jeden commit powinien odpowiadać jednemu logicznemu zakresowi.

## 8. Dokumenty kanoniczne
`RPG_OS_MASTER_ARCHITECTURE.md`, `RPG_OS_IMPLEMENTATION_ROADMAP.md` i `PARALLEL_WORK_COORDINATION.md` są obszarem koordynowanym. Worker nie zmienia globalnego statusu fazy na COMPLETE. Globalne statusy aktualizuje koordynator po audycie integracyjnym.

## 9. Definition of Done dla Work Item
COMPLETE wymaga: wykonania zakresu, działającej integracji, testów, zielonego build/CI lub udokumentowanej niezależnej awarii baseline, braku nierozwiązanego konfliktu, znanego resultCommit i raportu końcowego. COMPLETE work itemu != COMPLETE fazy.

## 10. Raport Workera
Raport zawiera: workId, baselineCommit, resultCommit, stan przed zmianą, zmiany, pliki, migracje/schema, testy, build/CI, wpływ na kampanie, konflikty/ryzyka, TODO i potrzebę kolejnego WORK ITEM.

## 11. Konflikt i blokada
Konflikt pliku, kontraktu, migracji, baseline albo konieczność wejścia w forbiddenScope => BLOCKED. Bez agresywnego merge/resetu. Koordynator redefiniuje zakres lub kolejność integracji.

## 12. Audyty read-only
Audyt może czytać całe repozytorium, ale nie implementuje przyszłej fazy, nie rezerwuje plików do zapisu poza własnym raportem audytowym i nie zmienia authoritative state/globalnego statusu fazy.

## 13. Rejestr aktywnej pracy

### ACTIVE WORK REGISTER

| Work ID | Owner | Phase | Status | Scope | Reserved subsystem/files | Baseline | Result | CI |
|---|---|---:|---|---|---|---|---|---|
| WORK-20260809-001 | CHAT-1 | 4 | READY | Implementacja Dynamic Stat/Resource Definitions — pierwszy authoritative krok Fazy 4 | stat/resource definition + persistence; wyłącznie nowe/bezpośrednio wymagane testy; bez globalnych docs | 82b030271e5b7d653da457a2e9b2522e21234457 | — | pending |
| WORK-20260809-002 | CHAT-2 | 4/5 prep | READY | Read-only audyt obecnych stat/resource/modifier paths i projekt kontraktu DerivedValueResolver | zapis tylko do `docs/audits/WORK-20260809-002_DERIVED_VALUE_AUDIT.md`; runtime read-only | 82b030271e5b7d653da457a2e9b2522e21234457 | — | n/a until report commit |
| WORK-20260809-003 | CHAT-3 | 4 validation | READY | Niezależny audyt/test-plan kompatybilności migracyjnej dla Dynamic Stats/Resources i starych kampanii | zapis tylko do `docs/audits/WORK-20260809-003_MIGRATION_TEST_PLAN.md`; schema/runtime read-only | 82b030271e5b7d653da457a2e9b2522e21234457 | — | n/a until report commit |
| WORK-20260809-004 | CHAT-4 | 6 prep | READY | Read-only audyt Talent/Potential legacy data i World Pack requirements | zapis tylko do `docs/audits/WORK-20260809-004_TALENT_POTENTIAL_AUDIT.md`; runtime read-only | 82b030271e5b7d653da457a2e9b2522e21234457 | — | n/a until report commit |
| WORK-20260815-001 | CHAT-7 | NON-PRODUCTION / TEMP TEST HARNESS | ACTIVE | TEMP LOCAL AI-GM A/B device benchmark + localhost bridge + user-authorized bug-reporting harness | `chat7-temp-gm-benchmark`; TEMP GM benchmark/evidence docs; TEMP localhost bridge; TEMP provider abstractions; TEMP bug harness; files created specifically for this harness | 43d5519c79d1247d02ae5178783ca7e38a0d3823 | — | device/benchmark in progress |

### Szczegółowe przydziały

#### WORK-20260809-001 — CHAT-1 — Dynamic Stats/Resources implementation
Objective: rozpocząć najwcześniejszą brakującą zależność roadmapy, punkt 4: `StatDefinition/PlayerStat + ResourceDefinition/PlayerResource`.

AllowedScope:
- audyt dokładnych istniejących tabel/klas statystyk i zasobów,
- zaprojektowanie generic definitions niezależnych od Naruto/Bleach,
- addytywna, migration-safe persistence jeśli wymagana,
- typed repository/domain access potrzebny wyłącznie dla punktu 4,
- testy kontraktu i persistence punktu 4,
- build/CI.

ForbiddenScope:
- DerivedValueResolver/modifier engine (punkt 5),
- Talent/Potential (6),
- Skill/Technique refactor (7/8),
- globalna aktualizacja roadmapy/MASTER/coordination,
- niepowiązany frontend redesign.

Dependencies: Phase 1–3 COMPLETE. Jeżeli implementacja wymaga modyfikacji współdzielonego `CampaignRepository` lub centralnego schema/migration entrypoint, CHAT-1 ma pierwszeństwo zapisu w tym zakresie; inne chaty pozostają read-only.

#### WORK-20260809-002 — CHAT-2 — DerivedValueResolver audit/design
Objective: przygotować punkt 5 bez implementowania go przed ukończeniem punktu 4.

AllowedScope:
- read-only inspekcja wszystkich obecnych obliczeń effective/base/max/current, modifierów, equipment/injury/temporary effects,
- wykrycie hardcoded world-specific logic,
- zaprojektowanie wejść/wyjść `DerivedValueResolver` i modifier model,
- mapa plików i zależności,
- propozycja testów i kolejności wdrożenia,
- utworzenie wyłącznie własnego raportu audytowego.

ForbiddenScope: runtime Kotlin/schema/migrations/repository API, MASTER, roadmap, coordination file, implementacja punktu 5.

Dependency: wynik WORK-001 będzie później wejściem do implementacji. Raport może powstać równolegle.

#### WORK-20260809-003 — CHAT-3 — Migration compatibility/test audit
Objective: zabezpieczyć Fazę 4 przed utratą danych starych kampanii.

AllowedScope:
- read-only audyt `character_stats`, resource-like state, obecnych migracji i test harness,
- zdefiniowanie scenariuszy old campaign -> migration -> authoritative equality,
- test cases dla unknown/custom World Pack stat/resource definitions,
- rollback/failure cases i collision/duplicate UID cases,
- utworzenie wyłącznie własnego raportu testowego.

ForbiddenScope: modyfikacja schema/runtime/migracji/testów produkcyjnych, MASTER, roadmap, coordination file.

Dependency: brak do audytu; implementacja testów zostanie przydzielona po ustabilizowaniu kontraktu WORK-001.

#### WORK-20260809-004 — CHAT-4 — Talent/Potential preparatory audit
Objective: przygotować punkt 6 bez wyprzedzania punktów 4–5.

AllowedScope:
- read-only wyszukanie istniejących talent/potential/aptitude/growth/evolution danych i logiki,
- rozdzielenie `Talent` (learning efficiency) od `Potential` (long-term ceiling/scale),
- analiza wymagań Naruto/Bleach bez hardcodowania ich do Core,
- projekt generic contract i World Pack extension points,
- mapa migracji legacy danych,
- utworzenie wyłącznie własnego raportu audytowego.

ForbiddenScope: implementacja runtime punktu 6, schema/migrations, PlayerState/Stat definitions, MASTER, roadmap, coordination file.

Dependency: implementacja BLOCKED do czasu ukończenia 4 i 5; sam audyt jest READY.

#### WORK-20260815-001 — CHAT-7 — TEMP LOCAL AI-GM A/B device benchmark + localhost bridge + bug-reporting harness

Phase: `NON-PRODUCTION / TEMP TEST HARNESS` — outside canonical AI roadmap phase completion.

Status: `ACTIVE`.

BaselineCommit: `43d5519c79d1247d02ae5178783ca7e38a0d3823`.

Objective: dostarczyć tymczasowy, lokalny, non-authoritative AI-GM do manualnego testowania RPG OS na Samsung Galaxy S24 SM-S921B; wykonać kontrolowany A/B test `LLAMA_3_2_3B` vs `QWEN3_4B`; przygotować odseparowany localhost bridge oraz user-authorized bug-reporting harness.

AllowedScope:
- utrzymywanie non-production branch `chat7-temp-gm-benchmark`,
- durable benchmark/device-test/TEMP GM evidence i reproducibility notes,
- A/B test `Llama 3.2 3B Instruct Q4_K_M` vs `Qwen3 4B Instruct Q4_K_M`,
- `llama.cpp` CPU-first device runtime i pomiary load time, first token, tokens/sec, RSS/RAM, swap, CPU, temperature, battery, 10-turn stability, Android process survival i RPG OS coexistence,
- TEMP-only abstractions `TempGmProvider`, `LocalLlamaTempGmProvider`, `LocalQwenTempGmProvider`,
- logical IDs `LLAMA_3_2_3B` i `QWEN3_4B`,
- TEMP localhost bridge bound to `127.0.0.1:8765`,
- bridge endpoints `GET /health`, `GET /providers`, `GET /active-provider`, `POST /active-provider`, `POST /gm/turn`, `POST /bug`,
- response modes `NARRATIVE_ONLY`, `ENGINE_CONFIRMED`, `TEST_FALLBACK`,
- `/bug <opis>` oraz bundle obejmujący build identity, campaign/worldpack identity, TEMP provider identity, bounded package-specific logcat, ADB status, recent safe actions, optional screenshot after explicit user approval i reproduction context,
- duplicate detection, deterministic bug fingerprint i offline pending bug queue,
- utworzenie/aktualizacja GitHub Issue wyłącznie po jawnej decyzji użytkownika,
- handoff do CHAT-6,
- zapis durable evidence po każdym zakończonym benchmark phase/test case.

ReservedFilesOrSubsystems:
- branch `chat7-temp-gm-benchmark`,
- TEMP GM benchmark/evidence documentation,
- TEMP localhost bridge code,
- TEMP provider abstraction code,
- TEMP bug-reporting harness code,
- wyłącznie pliki utworzone specjalnie dla tego harnessu, chyba że koordynator jawnie zatwierdzi dodatkową integrację.

Explicitly NOT reserved:
- canonical `PlayerDomainEngine`,
- `WorldRuleProvider`,
- transaction/invariant layers,
- release subsystem,
- canonical AI roadmap implementation surfaces.

ForbiddenScope:
- canonical Phase 48 `AiProvider`, Structured GM Output, Mechanics Resolution, Progression Engine, Time Skip Processor, canonical World Simulation, autonomous/self-modifying agent,
- dowolne Phase 20+ implementation w ramach TEMP harness,
- zmiana Phase-19 semantics,
- zmiana globalnego statusu canonical AI roadmap phases,
- bezpośrednie zapisy canonical DB/Save/state,
- authoritative `StatePatch`, `COMMIT`, authoritative `PlayerChangeSet` lub authoritative events,
- bypass `PlayerDomainEngine`, reference validation, `WorldRuleProvider`, validators lub transaction layers,
- autonomiczne tworzenie GitHub Issues bez jawnego `/bug`/potwierdzenia użytkownika,
- automatyczne naprawianie wykrytych bugów,
- merge do canonical production line bez jawnego przydziału/zgody koordynatora,
- APK/release publication.

Dependencies/coordination:
- work item pozostaje niezależny od Phase 19 dopóki TEMP harness nie wymaga ingerencji w production contracts,
- jeśli potrzebna stanie się zmiana Phase-19 contract: `STOP -> BLOCKED -> coordinator decision`,
- CHAT-6 owns Developer Settings UI, active-model selector, Android presentation/status integration, TEST APK i release/publication,
- CHAT-7 owns runtime, bridge contract, benchmark, bug harness i device evidence.

CurrentEvidence at registration:
- evidence branch: `chat7-temp-gm-benchmark`,
- `LLAMA_3_2_3B`: model load PASS; load 4462 ms; VmRSS ~2439504 kB; VmHWM ~2441112 kB,
- `QWEN3_4B`: model load PASS; load 6826 ms; VmRSS ~3073312 kB; VmHWM ~3076008 kB,
- `AB-01 Qwen Polish general prose`: PASS,
- full A/B benchmark: IN PROGRESS,
- winner: NOT SELECTED.

AcceptanceCriteria: Work Item może przejść do `COMPLETE` dopiero gdy oba modele przejdą pełny A/B suite, wykonane zostaną 10-turn stability i RPG OS coexistence tests, potwierdzony zostanie localhost-only runtime, bridge i provider switching będą działać, canonical mutation isolation będzie potwierdzona, `/bug`, bounded logcat, offline pending i duplicate detection będą działać, secrets nie będą ujawniane, pełne evidence i końcowy CHAT-7 benchmark report zostaną zapisane, handoff do CHAT-6 będzie gotowy, Phase-19 semantics pozostanie niezmienione, canonical Phase 20+ nie zostanie zaimplementowane, a `PUBLISHED = NO`.

## 14. Protokół startu workera
Każda nowa sesja wykonawcza:
1. przeczytaj MASTER,
2. przeczytaj ROADMAP,
3. przeczytaj ten dokument,
4. odczytaj swój WORK ITEM,
5. sprawdź aktualny master i baseline,
6. sprawdź rezerwacje,
7. sprawdź recent commits/CI,
8. potwierdź zależności,
9. pracuj tylko w allowedScope,
10. przed zapisem ponownie sprawdź master/plik,
11. testuj i raportuj wynik.

## 15. Protokół koordynatora
Przed przydzieleniem pracy: master/CI -> roadmap/audyty -> najwcześniejsza zależność -> niezależne zadania -> overlap check -> WORK IDs -> allowed/forbidden scope -> baseline -> start.
Po wynikach: result commits -> diff/CI -> konflikty -> audyt integracyjny -> statusy -> dopiero potem globalne COMPLETE.

## 16. Zasada nadrzędna
`DATA INTEGRITY > CAMPAIGN CONTINUITY > CORRECT ARCHITECTURE > SAFE INTEGRATION > PARALLEL SPEED`.
Jeżeli bezpieczna równoległość nie jest możliwa, zadania wykonujemy sekwencyjnie.
