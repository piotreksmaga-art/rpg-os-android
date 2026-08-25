# RPG OS — Phase 39–47 Single Execution Block

Status: **IMPLEMENTED / CONSOLIDATED ACCEPTANCE / EXACT-SHA CI REQUIRED**

Work ID: `WORK-20260825-001`

Cel: wykonać Phase 39–47 w jednym ciągłym zadaniu i jednym wspólnym kontekście roboczym, zachowując osobne odpowiedzialności faz oraz cztery wewnętrzne bramki jakości.

Dokument zachowuje plan i granice wykonanego bloku. Dowód acceptance znajduje się w `docs/architecture/PHASE39_47_ACCEPTANCE.md`.

## 1. Źródła prawdy

Przed rozpoczęciem bloku obowiązkowo odczytać:

1. `docs/PROJECT_WORK_PROTOCOL.md`;
2. aktualny kod, HEAD, status repozytorium i ostatnie wyniki CI;
3. `docs/Architektura projektu.md`;
4. `docs/Roadmap.md`;
5. właściwe fragmenty `docs/GM_ENGINE_TARGET_ARCHITECTURE.md`;
6. `docs/architecture/PHASE38_ACCEPTANCE.md`;
7. `docs/Adapter-prototyp.mb` dla faz oznaczonych `[REF-ADAPTER]`.

Aktualna numeracja i ownership Phase 39–47 pochodzą z `docs/Roadmap.md` oraz `docs/Architektura projektu.md`:

- 39 — Temporal Engine historical truth;
- 40 — Scheduler;
- 41 — Structured SQL Retriever;
- 42 — Knowledge Graph / causal retrieval;
- 43 — Intent Parser;
- 44 — Turn Planner;
- 45 — Context Builder;
- 46 — Context Budget Manager;
- 47 — Iterative Retrieval + missing-context loop.

Starsze sekcje `docs/GM_ENGINE_TARGET_ARCHITECTURE.md` mają historycznie inną numerację części 41–47. Ich wymagania pozostają materiałem architektonicznym, lecz nie mogą nadpisywać aktualnego przypisania faz. Audyt wejściowy ma przygotować mapę: stare wymaganie → aktualny właściciel Phase 39–47.

## 2. Model jednego bloku

```text
PHASE 39–47 — ONE EXECUTION BLOCK
│
├─ wspólny audit i contract map
│
├─ GATE A: 39–40  Temporal truth + Scheduler
│
├─ GATE B: 41–42  SQL + causal/graph retrieval
│
├─ GATE C: 43–44  Intent + Turn Plan
│
├─ GATE D: 45–47  Context + Budget + bounded iteration
│
└─ final integration audit: input → safe context candidate for Phase 48
```

To jest jeden blok organizacyjny, ale nie jeden monolit. Każda faza zachowuje własny kontrakt, testy, acceptance evidence i właściciela odpowiedzialności.

Praca może przebiegać bez pytania użytkownika pomiędzy bramkami, jeżeli:

- zależności są spełnione;
- testy bramki są zielone;
- nie jest potrzebna decyzja zmieniająca zakres produktu;
- nie wystąpił konflikt z zaakceptowanym kontraktem Phase 1–38;
- nie jest potrzebna destrukcyjna migracja albo nowe uprawnienie.

## 3. Wspólny audit wejściowy

Jeden audit zastępuje dziewięć powtarzanych analiz. Ma powstać trwały raport:

`docs/audits/PHASE_39_47_ENTRY_AUDIT.md`

Raport klasyfikuje istniejący kod jako `COMPLETE`, `PARTIAL`, `MISSING`, `OBSOLETE` albo `CONFLICTING` i obejmuje:

- obecne temporalne pola, eventy, snapshoty, replay, branch i undo;
- istniejące deadline'y, kolejki, joby, projekty, recovery i travel arrival;
- repository APIs, raw SQL consumers, indeksy i query helpers;
- Event Store, Causal Graph oraz ich identyfikatory i provenance;
- istniejące parsery komend, intent-like modele i planery;
- `CONTEXT_BUILDER_SPEC.md`, istniejące context builders i snapshot profiles;
- istniejące limity kontekstu, model/provider capabilities i token estimation;
- wszystkie odczyty danych chronionych przez Phase 38;
- schematy, migracje, kompatybilność starych kampanii i World Packów;
- aktualne testy oraz luki w testach adversarial/replay/cross-campaign;
- mapę starej numeracji architektury na aktualne Phase 39–47.

Dla Phase 39, 40, 41, 42 i 45 raport zapisuje decyzję z `Adapter-prototyp.mb`:

- `NO ADAPTER`,
- `OWNER FIX`,
- albo `MINIMAL ADAPTER` z udowodnionym konsumentem i minimalnym zakresem.

Domyślna decyzja to `NO ADAPTER`. Nie projektujemy wspólnej platformy adapterowej na zapas.

## 4. Wspólne kontrakty do zatwierdzenia przed kodowaniem

Audit ma zaproponować minimalne, world-agnostic kontrakty przepływu. Nazwy są robocze i muszą zostać dopasowane do aktualnego repozytorium:

```text
TemporalQuery / TemporalResult
ScheduledEvaluation / EvaluationDue
RetrievalRequest / RetrievalResult
CausalRetrievalRequest / CausalRetrievalResult
NormalizedIntent
TurnPlan
ContextBuildRequest / ContextCandidate
ContextBudget / BudgetedContext
MissingContext / RetrievalIterationResult
```

Wspólne pola są dodawane tylko wtedy, gdy mają rzeczywiste znaczenie dla więcej niż jednego kontraktu. Potencjalne pola przekrojowe:

- `campaignId`;
- relevant time / temporal scope;
- `AudienceContext`;
- `PurposeContext`;
- provenance;
- typed result/error state;
- deterministic ordering/cursor;
- request/trace identity potrzebne do diagnostyki bez authority.

Nie tworzymy jednego `UniversalRequest`, `UniversalContext` ani `God Service` dla całego bloku.

## 5. GATE A — Phase 39–40

### Phase 39 — Temporal Engine historical truth

Odpowiedzialność:

- odpowiedź „co było prawdą w czasie T?”;
- poprawne validity ranges lub równoważna rekonstrukcja temporalna;
- historyczne access/visibility queries zgodne z Phase 38;
- rozróżnienie braku danych, braku wiedzy, braku dostępu i uszkodzenia;
- deterministyczność względem event history/snapshot/replay.

Poza zakresem:

- przewidywanie przyszłości;
- Scheduler;
- retrieval ranking;
- kontekst dla AI;
- retroaktywne fabrykowanie brakującej historii.

### Phase 40 — Scheduler

Odpowiedzialność:

- przyszłe evaluation points i deadline'y;
- due/overdue/cancelled/processed lub równoważne typowane stany;
- idempotent evaluation claim/processing contract;
- snapshot/replay/branch/rollback safety;
- rezultat oceniany dopiero w czasie wykonania na aktualnym legalnym stanie.

Invariant:

`SCHEDULED EVALUATION != PRECOMMITTED OUTCOME`.

### Gate A acceptance

- temporal query nie podstawia present state za historyczny;
- Scheduler nie zapisuje z góry rezultatu domenowego;
- cross-campaign/time corruption fail closed;
- retry nie tworzy podwójnego wykonania;
- rollback nie pozostawia phantom schedule/history;
- snapshot/replay daje równoważny wynik;
- pełna regresja Phase 1–38 jest zielona;
- powstają osobne acceptance records Phase 39 i 40.

## 6. GATE B — Phase 41–42

### Phase 41 — Structured SQL Retriever

Odpowiedzialność:

- bounded, typowane zapytania do danych strukturalnych;
- parametryzacja i deterministic ordering;
- filtrowanie kampanii i czasu;
- korzystanie z projekcji Phase 38 dla chronionych informacji;
- jawne limity, kursory i explainable source provenance.

Poza zakresem:

- dowolny raw SQL pochodzący z promptu/modelu;
- semantyczny/vector search;
- decyzja, co AI powinno powiedzieć;
- bezpośrednie mutacje.

### Phase 42 — Knowledge Graph / causal retrieval

Odpowiedzialność:

- bounded traversal po zaakceptowanym Event/Causal/Knowledge graph;
- kierunek, głębokość, typ relacji i limity jako jawne dane;
- zachowanie provenance oraz rozróżnienia FACT/KNOWLEDGE/BELIEF;
- temporalne i audience-safe wyniki.

Poza zakresem:

- budowa ogólnego silnika pamięci Phase 55+;
- vector retrieval Phase 59;
- wnioskowanie tworzące nowe canonical facts;
- nieograniczone przeszukiwanie grafu.

### Gate B acceptance

- brak cross-campaign leakage;
- brak omijania Phase 38;
- zapytania są bounded i deterministyczne;
- przyczynowość nie zostaje spłaszczona do podobieństwa tekstowego;
- stale knowledge nie zostaje zastąpione current FACT;
- zapytania temporalne korzystają z Phase 39;
- pełna regresja Gate A i Phase 1–38 jest zielona;
- powstają osobne acceptance records Phase 41 i 42.

## 7. GATE C — Phase 43–44

### Phase 43 — Intent Parser

Odpowiedzialność:

- normalizacja wejścia gracza do typowanej struktury;
- zachowanie aktora, działania, celu, metody, ograniczeń i niepewności;
- jawny wynik `AMBIGUOUS`/`UNSUPPORTED` zamiast zgadywania;
- deterministyczna ścieżka podstawowa; model może być późniejszym kandydatem pomocniczym, nie jedynym parserem.

Invariant:

`PLAYER INPUT != NORMALIZED INTENT != PLAYER COMMAND != COMMIT`.

Parser nie rozstrzyga mechaniki i nie dopowiada dobrowolnej decyzji gracza.

### Phase 44 — Turn Planner

Odpowiedzialność:

- wybór potrzebnych repozytoriów, retrieverów, mechanik i capability;
- jawny, bounded plan bez wykonania operacji;
- minimalizacja uruchamianych subsystemów;
- zachowanie struktury actor/action/target.

Planner nie pobiera pełnego świata, nie wykonuje mechaniki, nie mutuje stanu i nie tworzy narracji.

### Gate C acceptance

- parser nie zmienia intencji gracza;
- brak inputu nie staje się zgodą ani akcją;
- ambiguity zatrzymuje lub ogranicza plan zgodnie z typowanym kontraktem;
- planner nie wywołuje wszystkich systemów dla każdej tury;
- ten sam intent i capability map dają deterministyczny plan;
- fazy 43–44 nie wymagają providera AI;
- pełna regresja Gate A–B i Phase 1–38 jest zielona;
- powstają osobne acceptance records Phase 43 i 44.

## 8. GATE D — Phase 45–47

### Phase 45 — Context Builder

Odpowiedzialność:

- składanie typowanego `ContextCandidate` wyłącznie z dozwolonych projekcji;
- jawne audience, purpose, time i campaign scope;
- separacja contextu lokalnego, cloud i innych workloadów;
- provenance każdego segmentu;
- brak surowych ukrytych pól w bundle'u.

Context Builder nie decyduje o mechanice, nie tworzy prawdy i nie używa prompt instruction jako access control.

### Phase 46 — Context Budget Manager

Odpowiedzialność:

- dynamiczny budżet zależny od `ModelProfile`, capability i workloadu;
- typowane priorytety i reguły redukcji;
- deterministyczne zachowanie dla tego samego wejścia/profilu;
- zachowanie informacji wymaganych dla poprawności, agency i bezpieczeństwa.

Budżetowanie nie może rozszerzyć entitlement ani zmienić partial/uncertain w exact/certain.

### Phase 47 — Iterative Retrieval + missing-context loop

Odpowiedzialność:

- wykrycie jawnie reprezentowalnego braku kontekstu;
- bounded follow-up retrieval zgodny z Turn Planem;
- limity iteracji, czasu, rekordów i budżetu;
- deduplikacja oraz jawny powód zakończenia;
- bezpieczny wynik częściowy, gdy braku nie można uzupełnić.

Invariant:

`MISSING CONTEXT != PERMISSION TO EXPAND ACCESS`.

### Gate D acceptance

- NPC A nie otrzymuje B-only knowledge;
- player context nie zawiera hidden GM/world fields;
- local i cloud obey ten sam semantic access envelope;
- cloud bundle jest minimalny i sanitised;
- redukcja budżetu zachowuje wymagane reguły i intencję gracza;
- iterative retrieval ma twardy limit i nie zapętla się;
- brak danych, brak dostępu i wyczerpanie budżetu pozostają rozróżnialne;
- pełna regresja Gate A–C i Phase 1–38 jest zielona;
- powstają osobne acceptance records Phase 45, 46 i 47.

## 9. Test integracyjny całego bloku

Końcowy test przechodzi przez pipeline bez uruchamiania Phase 48:

```text
player input
→ normalized intent
→ bounded turn plan
→ temporal SQL/causal retrieval
→ Phase 38 projections
→ context candidate
→ budgeted context
→ bounded missing-context iteration
→ final safe context candidate
```

Minimalne scenariusze przekrojowe:

- pytanie o fakt historyczny po zmianie stanu bieżącego;
- deadline oceniony po zmianie warunków bez precommitted outcome;
- rozdzielona drużyna z odmienną wiedzą holderów;
- ukryty fakt istnieje w bazie, ale nie trafia do kontekstu gracza/NPC;
- stale/false/contradictory knowledge pozostaje epistemicznie poprawne;
- niejednoznaczna komenda nie zostaje dopowiedziana jako decyzja gracza;
- mały context budget daje bezpieczny wynik częściowy;
- brakujący kontekst wymaga jednej legalnej dogrywki retrieval;
- malicious carrier/prompt-like text nie eskaluje dostępu;
- retry, rollback, snapshot i replay zachowują równoważność;
- cross-campaign references fail closed;
- minimum dwa odmienne World Pack fixtures bez hardcodowania Core.

Finalny artefakt nie jest promptem ani odpowiedzią AI. Jest bezpiecznym, typowanym kandydatem na wejście dla przyszłej Phase 48.

## 10. Strategia migracji i kompatybilności

- Preferować addytywne zmiany schema.
- Nie przepisywać zamrożonej Phase 1–38 bez udowodnionej konieczności.
- Nie fabrykować historycznych `validFrom`, provenance ani knowledge acquisition.
- Legacy/unknown pozostaje jawnie oznaczone.
- Każda migracja ma test old campaign → migrate → authoritative equality.
- Nie usuwać starych ścieżek przed udowodnieniem równoważności i migracji wszystkich konsumentów.
- Adapter referencyjny stosować tylko po decyzji `MINIMAL ADAPTER` w audycie.

## 11. Strategia oszczędzania limitu pracy z ChatGPT/Codex

Cały blok pozostaje w jednym zadaniu. Pamięcią operacyjną są repozytorium i krótkie pliki, nie wielokrotne streszczanie rozmów.

Obowiązkowe artefakty:

- jeden wspólny entry audit;
- jeden contract/dependency map aktualizowany tylko przy zmianie kontraktu;
- dziewięć krótkich acceptance records;
- jeden końcowy integration audit;
- jeden handoff po całym bloku lub przy rzeczywistym blockerze.

Zasady ekonomii kontekstu:

- nie otwierać osobnego chatu dla każdej fazy;
- nie wklejać ponownie całej architektury — linkować do źródeł i cytować tylko właściwe sekcje;
- wyszukiwać repozytorium według bieżącej bramki;
- testy traktować jako trwałą specyfikację invariantów;
- po każdej bramce zapisać krótki delta summary;
- nie projektować Phase 48+ podczas tego bloku;
- nie pytać użytkownika o decyzje techniczne możliwe do rozstrzygnięcia przez kod, architekturę i testy;
- zatrzymać się tylko przy realnej zmianie produktu, destrukcyjnej migracji, konflikcie źródeł prawdy albo problemie z uprawnieniami.

## 12. Zakres zabroniony całego bloku

- Phase 48 AI Provider i jakiekolwiek produkcyjne wywołanie modelu;
- Structured GM Output, GM validation/repair i narracja;
- Memory Phase 55+ i vector engine Phase 59;
- Time Skip Phase 60;
- NPC Brain/Decision i Living World Phase 61–64;
- Director Phase 65+;
- performance orchestration/routing Phase 78–79;
- World Pack-specific Core branches;
- frontend redesign;
- duży adapter kompatybilności bez udowodnionego konsumenta;
- zmiana statusu fazy na COMPLETE bez pełnego acceptance i CI.

## 13. Commit i checkpoint policy

Jeden blok nie oznacza jednego ogromnego commita. Zalecana struktura:

1. audit/contract documentation;
2. Phase 39;
3. Phase 40 + Gate A evidence;
4. Phase 41;
5. Phase 42 + Gate B evidence;
6. Phase 43;
7. Phase 44 + Gate C evidence;
8. Phase 45;
9. Phase 46;
10. Phase 47 + Gate D evidence;
11. integration hardening i final audit;
12. canonical status update dopiero po akceptacji.

Nie układamy kolejnych bramek na znanym czerwonym buildzie. Każdy commit ma być logicznie odwracalny i nie może mieszać niepowiązanego frontend/content work.

## 14. Definition of Done całego bloku

Blok Phase 39–47 jest gotowy do końcowej akceptacji wyłącznie, gdy:

- istnieje zatwierdzony entry audit i mapowanie starej numeracji;
- wszystkie dziewięć faz spełnia własny zakres i posiada osobną sekcję w skonsolidowanym acceptance record;
- Gate A, B, C i D są zielone;
- końcowy pipeline integracyjny działa bez Phase 48;
- wszystkie protected reads respektują Phase 38;
- temporal truth, knowledge, access i current state nie są mieszane;
- Scheduler nie precommitje outcomes;
- retrieval jest bounded, temporal i audience-safe;
- Intent Parser nie przejmuje decyzji gracza;
- Turn Planner nie wykonuje planu ani mechaniki;
- Context Builder nie posiada surowych ukrytych danych;
- Context Budget nie osłabia poprawności ani bezpieczeństwa;
- iterative retrieval ma twarde limity i bezpieczny wynik częściowy;
- nie dodano funkcji Phase 48+;
- stare kampanie i World Packi przechodzą testy kompatybilności;
- pełne testy Phase 39–47, regresja Phase 1–38, pełny JVM/build i wymagane CI są zielone;
- finalny baseline commit i dowody CI są zapisane;
- roadmapa zostaje zaktualizowana dopiero przez końcowy audyt akceptacyjny.

## 15. Pierwszy krok wykonawczy

Po wydaniu polecenia rozpoczęcia należy wykonać wyłącznie:

1. sprawdzenie czystości repozytorium i aktualnego HEAD/CI;
2. zabezpieczenie lub zatwierdzenie bieżących zmian dokumentacyjnych;
3. read-only audit aktualnej implementacji Phase 39–47;
4. utworzenie `docs/audits/PHASE_39_47_ENTRY_AUDIT.md`;
5. przedstawienie minimalnej mapy kontraktów oraz decyzji adapterowych;
6. dopiero potem rozpoczęcie implementacji Gate A.

Nie rozpoczynać kodowania od projektowania nowych klas bez audytu istniejącego repozytorium.
