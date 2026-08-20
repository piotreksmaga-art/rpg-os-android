# Architektura projektu

Status: MASTER / CANONICAL

Ten dokument zawiera wyłącznie obowiązującą architekturę RPG OS. Historia decyzji, wcześniejsze warianty i pełne snapshoty poprzednich wersji znajdują się w `docs/Historia projektu.md` oraz `docs/history/`. Kolejność implementacji definiuje `docs/Roadmap.md`, a indeks kodu `docs/Mapa plików.md`.

## 0. Priorytet źródeł
Przy konflikcie obowiązuje kolejno:
1. rzeczywisty aktualny stan repozytorium i działające dane kampanii;
2. najnowsza jawna decyzja użytkownika;
3. ten dokument;
4. starsze dokumenty, plany, TODO i rozmowy.

Nie implementuj architektury z pamięci. Najpierw sprawdź repozytorium.

## 1. Misja i granica odpowiedzialności
RPG OS jest uniwersalnym systemem operacyjnym dla bardzo długich kampanii RPG: setki tysięcy tur, miliony eventów i słów, lata rozgrywki, bez utraty stanu, historii, progresji, przedmiotów, pieniędzy, własności, wiedzy, chronologii i campaign divergence.

Core jest niezależny od uniwersum. Naruto, Bleach i kolejne światy są World Packami.

AI nie jest bazą danych, pamięcią kampanii, kalkulatorem mechaniki ani źródłem prawdy. AI interpretuje, proponuje i narracyjnie prezentuje kontrolowany świat RPG OS.

## 2. Model prawdy
Każda trwała informacja rozróżnia co najmniej:
- `FACT` — obiektywna prawda kampanii;
- `BELIEF` — przekonanie konkretnego aktora;
- `NARRATIVE` — informacja przedstawiona graczowi.

`NARRATIVE != FACT`. `AI OUTPUT != COMMITTED REALITY`.

Bieżąca rzeczywistość:

`CURRENT CAMPAIGN REALITY = WORLD CANON + CAMPAIGN DIVERGENCES + COMMITTED CAMPAIGN STATE`.

Zmiana kanonu przez kampanię nie może automatycznie przywracać oryginalnej historii.

## 3. Warstwy systemu
1. `SOURCE OF TRUTH` — canon, schemas, stable UID, provenance, World Pack rules.
2. `CAMPAIGN STATE` — player, NPC, inventory, economy, relations, missions, locations, world.
3. `CAMPAIGN INTELLIGENCE` — events, memories, beliefs, causal graph, ledgers, promises, chronicle, snapshots.
4. `SIMULATION / RULE ENGINE` — player, combat, progression, economy, projects, travel, time, NPC, world.
5. `CONTEXT & DIRECTOR` — retrieval, knowledge/temporal filtering, context budget, pacing, anti-repetition.
6. `AI GAME MASTER` — narration, dialogue, interpretation i proposal generation.

Kierunek zależności: prawda -> stan -> historia/inteligencja -> mechanika -> kontekst -> AI/narracja.

## 4. Stable UID, provenance i repository
Każdy trwały obiekt ma stabilny UID. Nazwa jest etykietą, UID to tożsamość.

Ważne fakty i zmiany zachowują provenance odpowiednie dla domeny, np. sourceType/sourceId, createdTurn, createdEvent, actorUid, method, confidence, canonStatus, verified, engineVersion.

System widzi jeden logiczny `CampaignRepository` nad wyspecjalizowanymi repozytoriami. GM/AI nie manipuluje przypadkowymi tabelami SQLite bezpośrednio.

Fizyczny podział danych na osobne DB/indexy/pliki jest decyzją implementacyjną; logiczny podział authority jest wymaganiem.

## 5. Historia, Event Store i Causal Graph
Znacząca historia jest append-oriented/immutable; working state odpowiada za bieżący wydajny stan.

Istotne zmiany generują Eventy z tożsamością kampanii/tury, czasem, aktorem/celem/lokalizacją, przyczyną, zmianą stanu i provenance. Nie zapisujemy każdego mikro-ruchu.

Causal Graph przechowuje legalne relacje przyczynowe, np. caused/enabled/triggered/prevented. System ma umieć wyjaśnić, jak i dlaczego powstała ważna zmiana świata.

## 6. Jedna legalna droga zmiany prawdy — GLOBAL INVARIANT

`PROPOSAL -> DOMAIN/RULE RESOLUTION -> CHANGE SET -> VALIDATION -> TRANSACTION -> EVENTS + LEDGERS + AUTHORITATIVE STATE -> COMMIT -> COMMITTED REALITY`

AI, UI, Progression, TimeSkip, WorldSimulation, Economy, Memory, Chronicle, Snapshot, Director ani Cloud nie mogą tworzyć bocznej authoritative mutation path.

Jeżeli nowa funkcja wymaga nowego rodzaju zmiany, rozszerzamy canonical domain/transaction path zamiast tworzyć wyjątek.

### 6.1 Turn Transaction
Tura/commitowalna operacja jest atomowa. Krytyczny błąd powoduje rollback. Narracja, Eventy, state i ledgers muszą odpowiadać tej samej committed reality.

### 6.2 Idempotency
Commitowalna operacja posiada stabilną tożsamość transaction/command/turn. Retry już committed operacji nie może powtarzać skutków. Losowość potrzebna do replay zachowuje wynik lub odpowiedni RNG seed/evidence.

### 6.3 Crash recovery
Po przerwaniu procesu runtime wraca do `LAST VALID COMMIT`, odrzuca/rollbackuje niekompletną pracę, weryfikuje integralność i odbudowuje derived/cache.

## 7. Klasy danych
Każdy persistent typ jest sklasyfikowany jako:
- `AUTHORITATIVE` — utrata oznacza utratę informacji kampanii;
- `DERIVED` — odbudowywalne z authority;
- `CACHE/INDEX` — wydajność;
- `PRESENTATION` — widok dla użytkownika/AI.

Przepływ: `AUTHORITATIVE -> DERIVED -> CACHE/PRESENTATION`.

Presentation ani cache nie mogą stać się authority bez jawnej zwalidowanej komendy domenowej.

## 8. Player Domain i mechanika
`PlayerDomainEngine` pozostaje canonical wejściem dla autorytatywnych zmian gracza. AI nie ustawia bezpośrednio statystyk, zasobów, pieniędzy, inventory, skills, techniques, ownership ani permanent traits.

`Player/World Action -> PlayerCommand -> PlayerDomainEngine -> Rule Pipeline -> WorldRuleProvider -> Mechanics -> InvariantValidator -> PlayerChangeSet -> TurnTransaction -> COMMIT`

Player State rozdziela:
- `PERSISTENT` — trwałe osiągnięcia i stan;
- `DERIVED` — obliczalne wartości efektywne;
- `RUNTIME` — bieżące HP/fatigue/cooldowns/buffs/wounds/stance.

Core używa dynamicznych definicji statów/resources. Talent, Potential, Skills, Techniques i Innate/Racial/Bloodline/Evolution są odrębnymi konceptami.

Progression posiada legalną przyczynę i ledger/evidence; time alone nie oznacza power. No-Retrogression chroni trwałe osiągnięcia przed niewyjaśnioną utratą.

`DevelopmentProject` obsługuje tworzenie i rozwój technik, research, crafting, body adaptation, infrastructure i inne wieloetapowe projekty zamiast arbitralnego przyznawania rezultatu przez AI.

Inventory != Equipment. Ownership jest odrębną authority. Finanse są ledger-driven; balance/net worth są projekcjami tam, gdzie możliwe.

`CharacterPanelSnapshot` jest wersjonowaną odbudowywalną projekcją, nie authority.

## 9. Core vs World Pack
Core posiada mechanizmy uniwersalne. World Pack dostarcza canon, definitions i world-specific rules: zasoby energii, ranks, techniques, races/bloodlines, evolutions, organizations, locations, timeline, communication rules itd.

World Pack nie kopiuje infrastruktury Core: transactions, events, memory, economy framework, snapshots, retrieval, NPC Brain, World Simulation, Save/Load.

## 10. World Actor Knowledge / Epistemic State — CANONICAL TARGET
Phase 37 buduje uniwersalny epistemiczny fundament świata, nie wyłącznie tabelę wiedzy NPC. System ma odpowiadać: **kto co wie, uważa, podejrzewa lub szacuje; z jakiego dowodu to wynika; kiedy informację uzyskał; jaka jest jej jakość, aktualność i dostępność**.

Globalny FACT nie oznacza automatycznie wiedzy NPC, gracza, organizacji, urzędu ani innego World Actora.

### 10.1 KnowledgeHolder
Knowledge holderem może być zależnie od domeny m.in.:
- `CHARACTER` / NPC;
- `PLAYER_CHARACTER`;
- `MILITARY_UNIT` / `ARMY_COMMAND`;
- `ORGANIZATION` / guild / company / clan;
- `CITY_ADMINISTRATION` / `STATE` / agency;
- `INTELLIGENCE_SERVICE`;
- `RESEARCH_TEAM` / `LABORATORY` / institution;
- world-specific actor.

Dokument, raport, książka, mapa, baza, archiwum, notatnik badawczy lub inny nośnik może przechowywać/przenosić evidence bez bycia autonomicznym aktorem decyzyjnym.

`PERSONAL KNOWLEDGE`, `INSTITUTIONAL KNOWLEDGE` i `ROLE-ACCESSIBLE KNOWLEDGE` są odrębnymi semantykami. Wiedza instytucji nie staje się automatycznie wiedzą każdego członka.

### 10.2 Truth, information, evidence i belief
Rozdzielamy co najmniej:
- `FACT` — authoritative reality;
- `INFORMATION / CLAIM` — treść możliwa do przekazania lub oceny;
- `EVIDENCE` — obserwacja, raport, dokument, pomiar, wynik, wypowiedź itd.;
- `KNOWLEDGE STATE` — aktualny stan epistemiczny konkretnego holdera;
- `BELIEF / ESTIMATE / HYPOTHESIS / SUSPICION` — interpretacja holdera.

`FACT != KNOWLEDGE`, `KNOWLEDGE != BELIEF`, `KNOWLEDGE != EXECUTABLE SKILL`, `KNOWLEDGE != DECISION`.

Claimy powinny być granularne: np. osoba może być znana jako członek organizacji, użytkownik konkretnej techniki, ostatnio widziana w lokacji X, ranna, podejrzana itd. Każdy claim może mieć własne evidence, czas, precision, confidence i secrecy.

Stan epistemiczny może rozróżniać m.in. `KNOWN`, `BELIEVED`, `SUSPECTED`, `PARTIALLY_KNOWN`, `DOUBTED`, `DISBELIEVED`, `CONTRADICTED`, `OUTDATED`. Brak rekordu zwykle oznacza brak recorded knowledge, nie materializowany wpis `UNKNOWN` dla każdego możliwego faktu.

### 10.3 Acquisition provenance i lineage
Legalne acquisition provenance obejmuje zależnie od świata m.in.:
- direct observation;
- direct communication;
- document / media / report;
- rumor / hearsay;
- education / training;
- research / experiment;
- inference;
- institutional sharing;
- interrogation;
- surveillance / espionage;
- memory;
- World Pack mechanics;
- `LEGACY` / `UNKNOWN_NOT_RECORDED`.

Nowe canonical acquisition wymagają legalnego committed cause/evidence. AI, ContextBuilder, generic StatePatch ani raw storage helper nie mogą samodzielnie tworzyć authoritative knowledge acquisition.

Sharing tworzy nowe acquisition provenance odbiorcy; nie jest magicznym kopiowaniem globalnej wiedzy. Lineage powinien pozwalać prześledzić łańcuch `event/observation -> report -> summary -> recipient -> later sharing`, w tym distortion, omission, misunderstanding, exaggeration, translation error lub deliberate deception.

Legacy wiedza bez zachowanej historycznej provenance pozostaje `LEGACY` / `UNKNOWN_NOT_RECORDED`; system nie fabrykuje przeszłości.

Historyczne acquisitions są trwałym evidence, a current KnowledgeState może być odbudowywalną/wersjonowaną projekcją nad nimi i innymi legalnymi źródłami.

### 10.4 Quality, freshness i contradiction
Informacja może być prawdziwa, fałszywa, częściowa, niepewna, sprzeczna lub nieaktualna. Confidence nie jest truth flag.

Jakość informacji może uwzględniać według domeny m.in.:
- confidence;
- precision;
- freshness / observedAt / lastUpdated;
- completeness;
- source reliability;
- corroboration;
- known deception;
- uncertainty interval/range.

Nowy FACT w świecie nie aktualizuje automatycznie wiedzy holdera. Kupiec może znać starą cenę, generał stary meldunek, naukowiec nieaktualną teorię, lekarz błędną diagnozę.

Sprzeczne evidence nie powinno być bezwarunkowo nadpisywane. Holder może posiadać competing evidence i zmieniać belief wraz z nowymi źródłami.

### 10.5 Expertise i wiedza domenowa
Core wspiera typed/data-driven knowledge domains i expertise hooks bez zamykania systemu na jeden gatunek gry. Przykładowe domeny: military intelligence, tactics, market/valuation, medicine, science, crafting, politics/law, geography, history, technique knowledge, investigation i World Pack-defined domains.

Ekspertyza wpływa na jakość rozpoznania, interpretacji, szacowania, inference i wykrywania deception, ale nie daje automatycznie wykonawczej zdolności. NPC może rozpoznać technikę, znać jej kontrę i nadal nie umieć jej wykonać; wykonanie pozostaje authority Skill/Technique/Mechanics.

### 10.6 Style gry wspierane jednym epistemic core
Ten sam Knowledge Engine ma obsługiwać różne style gry bez osobnych konkurencyjnych systemów:
- character RPG — osobista wiedza, sekrety, relacje, rozpoznanie zdolności;
- generał / tactical / grand strategy — fog of war, reconnaissance, intelligence estimates, chain of command;
- city/state management — censuses, tax/economy/food/crime/public-order reports, corruption i opóźnione dane;
- merchant/trading — regional prices, demand/supply, route risk, information arbitrage;
- science/research — observations, hypotheses, experiments, replications, disputed theories i discoveries;
- medicine — symptoms, tests, differential diagnosis i uncertain evidence;
- espionage/politics — secrets, deception, counterintelligence, promises, faction assessments;
- detective/investigation — clues, witness statements, hypotheses i evidence chains;
- exploration/cartography — known routes/locations/hazards/resources i niepełne map knowledge;
- World Pack-defined styles.

Domena mechaniki rozstrzyga rzeczywistość; Knowledge Engine przechowuje tylko epistemiczny obraz tej rzeczywistości u holderów.

### 10.7 Organization, role i access metadata
Wiedza może należeć do organizacji/urzędu i być udostępniana przez rolę, clearance lub legalny sharing. Zmiana stanowiska może zmienić dostęp do institutional records, ale nie kopiuje prywatnych memories poprzednika.

Phase 37 przechowuje metadata potrzebne do access/visibility, np. `PUBLIC`, `PRIVATE`, `SECRET`, `CLASSIFIED`, `ROLE_RESTRICTED`, `ORGANIZATION_RESTRICTED`, `WORLD_SPECIFIC`; pełne egzekwowanie GM/NPC/PC/player-visible granic należy do Phase 38.

Player-visible, PC-known, NPC-known i GM/internal context nie są synonimami.

### 10.8 Temporal readiness, durability i context boundary
Model Phase 37 musi być temporal-ready: system później ma móc odpowiedzieć „co holder wiedział wtedy?”, a nie tylko „co wie teraz”. Pełny historical truth/query engine należy do Phase 39.

Canonical acquisitions uczestniczą w Single Truth Mutation Path, TurnTransaction, Event/Causal evidence, idempotency, rollback, snapshot/replay i schema/migration safety.

Retry tej samej logicznej acquisition nie tworzy duplikatu; rollback nie pozostawia phantom knowledge; snapshot/replay odtwarza ten sam epistemic state.

ContextBuilder nie jest authority wiedzy. Docelowo pobiera holder-scoped `KnowledgeContextProjection`/typed Knowledge API zamiast definiować własną semantykę bezpośrednimi SQL query.

## 11. Temporal Engine, Scheduler i Time Skip
Stan historyczny może posiadać `validFrom/validUntil` lub równoważny temporal contract. Retrieval musi odpowiadać „co było prawdą wtedy?”, nie automatycznie używać teraźniejszości.

Scheduler planuje evaluation points/deadlines, nie z góry outcome. Przyszły rezultat powstaje dopiero po ocenie aktualnego stanu i reguł.

Time Skip orkiestruje upływ czasu przez odpowiednie subsystemy: scheduled evaluations, player/NPC progression, age/family, projects, economy, travel, organizations, wars/politics, world simulation, relationships, knowledge propagation, memory consolidation i snapshot/state update.

## 12. Retrieval, Intent, Turn Planner i Context
Retrieval łączy według potrzeb SQL + Knowledge/Causal Graph + Vector/Semantic Search + Temporal Filter + Knowledge Filter. Jest bounded i iteracyjny.

Intent Parser rozpoznaje strukturę intencji, nie rozstrzyga mechaniki. Turn Planner wybiera tylko potrzebne repozytoria, mechaniki, NPC, history/canon i output capabilities.

Context Budget jest dynamiczny i provider/model/workload-aware; nie zakłada stałego CTX ani output budgetu.

Actor/action/target z normalized intent pozostają strukturalnym wejściem. Model nie jest jedynym parserem semantyki akcji.

Local context i cloud context nie muszą być identyczne. Cloud otrzymuje minimalny, zadaniowy, sanitizowany i dozwolony bundle — nie automatycznie całe Save/Chronicle/DB.

## 13. Hybrid Local-First AI — CANONICAL TARGET
Docelowy AI jest `HYBRID LOCAL-FIRST` pod jednym provider-independent semantic contractem.

`LOCAL AI MUST BE SUFFICIENT TO CONTINUE THE CAMPAIGN.`

Cloud może poprawiać jakość, ale brak sieci, timeout, 429, quota, provider failure albo usunięcie credentials nie może blokować gry.

### 13.1 Rozdzielenie odpowiedzialności
- `AiProvider` / równoważny kontrakt — semantic generation boundary;
- `AiCapabilityContract` — capabilities i limits;
- `ModelProfile` — data-driven model identity/capabilities/compatibility;
- `LocalInferenceRuntime` — execution/load/unload/generation/cancel/metrics;
- `RuntimeBackendSelector` — CPU/GPU/supported NPU/AUTO;
- optional cloud adapters — ten sam semantic contract, bez campaign authority;
- `GmToolGateway` — allowlisted QUERY/REQUEST/PROPOSE boundary, bez raw writable DB i COMMIT.

Model, provider, runtime i backend są wymienne i nie mogą wymagać migracji kampanii.

Credentials/auth state nie należą do Campaign State, Save, Chronicle ani World Pack.

### 13.2 Player agency
`VOLITIONAL PLAYER ACTION SOURCE = USER / VALIDATED PLAYER COMMAND ONLY`.

AI nie dopisuje graczowi dobrowolnych ruchów, wypowiedzi, ataków, wyborów celu ani zdolności. Mechanika może narzucić konsekwencje niezależne od woli, np. stun/knockback/utrata przytomności.

`ACTOR / ACTION / TARGET` muszą być zachowane strukturalnie. Provider conformance obejmuje player agency, direction, NPC knowledge isolation, FACT/BELIEF, stop point, invented abilities/dialogue, internal-context leakage i brak mutation authority.

### 13.3 Workload routing i Cloud Director
Routing rozdziela co najmniej: workload policy, provider choice, ModelRouter i RuntimeBackendSelector. Deterministic work omija AI.

Normalne tury preferują local path. Optional cloud może obsługiwać wybrane expensive/long-horizon workloads.

Cloud Director generuje tylko bounded candidates, np. arc/quest seeds, NPC agenda candidates, faction conflicts, foreshadowing i pacing suggestions.

`DIRECTOR/CLOUD OUTPUT = CANDIDATE != FACT != COMMIT`.

Deferred/late cloud result nie może przepisać przeszłego COMMIT.

## 14. NPC individuality, personality i decyzje — CANONICAL TARGET
NPC nie jest chwilową personą z promptu. Istotny NPC jest trwałym aktorem świata.

Rozdzielamy co najmniej:
- `PERSONALITY / TRAITS`;
- `VALUES / GOALS / FEARS`;
- `RELATIONSHIP STATE`;
- `EMOTIONAL STATE`;
- `KNOWLEDGE / BELIEFS`;
- `MEMORY`;
- `SOCIAL ROLE / ORGANIZATION / CULTURE`;
- `RESOURCES / CAPABILITIES / CURRENT SITUATION`.

Indywidualizacja powstaje z World Pack archetype/population rules, backgroundu, kultury/organizacji, roli i controlled stable RNG. Wynik jest persisted lub deterministically reproducible; nie losuje się nowej osobowości przy każdym spotkaniu.

`NPC DECISION = personality + values/goals/fears + emotions + relationships + knowledge/beliefs + memory + social/organizational constraints + resources/capabilities + situation + World Pack rules + controlled randomness where legal`.

`PERSONALITY != RELATIONSHIP != EMOTIONAL STATE`, `KNOWLEDGE != DECISION`, `AI PROPOSAL != COMMIT`.

Ten sam bodziec może dać różne legalne reakcje różnych NPC, jeżeli wynikają z ich trwałego stanu. Long-term personality adaptation wymaga committed cause/provenance; AI nie zmienia charakteru retroaktywnie dla fabuły.

Reputacja jest holder-scoped belief acquired legalnie, nie omniscient globalnym score.

LOD może stosować crowd/minor/persistent/major tiers. Materializacja szczegółu nie fabrykuje nieistniejącej historii.

## 15. Living World / Autonomous World Simulation — CANONICAL TARGET
`THE WORLD DOES NOT WAIT FOR THE PLAYER.`

Świat rozwija się niezależnie od bezpośredniej obecności gracza. Gracz jest uczestnikiem, nie jedynym zegarem ani przyczyną zdarzeń.

### 15.1 World Actors i World Processes
World Actors mogą obejmować NPC, rodziny, klany, organizacje, gildie/firmy, miasta, państwa, armie i world-specific actors. Posiadają goals, resources, capabilities, relationships, knowledge, constraints, projects i pressures.

World Processes mogą obejmować wojny, handel, migrację, politykę, faction expansion, research, budowę, epidemie, crime, economy, demografię, dyplomację, espionage itd. Proces zachowuje przyczyny, uczestników, zasoby, progress/state, constraints i następny evaluation point — nie precommitted fabularny outcome.

### 15.2 LOD i multi-rate simulation
- `LOD0` — scena szczegółowa;
- `LOD1` — lokalny region;
- `LOD2` — organizacje/państwa strategicznie;
- `LOD3` — odległy świat jako agregaty/trendy/pressures.

Scena może aktualizować się per action/turn, region per hour/day, strategic systems per day/week, wolne procesy per month/season/year. Nie symulujemy każdego bytu co turę.

Agregowane population/crowd mogą zostać później materializowane w szczegół zgodny z już committed strategiczną historią. Brak historycznej provenance pozostaje unknown.

### 15.3 Causality, conservation i informacja
Wojny, gospodarka, projects, armies, population, resources i organizations pozostawiają spójne skutki. Domena stosuje właściwe conservation/invariant validation.

Istotne background changes generują Event/Causal history; mikroaktywność może pozostać agregowana.

Background FACT nie staje się automatycznie PLAYER/NPC KNOWLEDGE. Informacja propaguje się legalnymi kanałami i z world-specific możliwościami/prędkością komunikacji.

Quest/opportunity może wynikać z rzeczywistego world state/process zamiast z arbitralnego random contentu.

`WORLD SIMULATION = WHAT ACTUALLY DEVELOPS`.
`DIRECTOR = WHAT DESERVES ATTENTION`.

Director może podnosić relevance albo proponować future candidates; nie tworzy committed wojny, kryzysu czy śmierci bez legalnego causal/domain basis.

### 15.4 Persistent World i Character Succession — CANONICAL TARGET
Campaign World i aktualnie sterowana postać gracza są odrębnymi tożsamościami. `CAMPAIGN/WORLD != ACTIVE PLAYER CHARACTER`.

Zmiana aktywnej postaci nie tworzy automatycznie nowego świata i nie resetuje historii kampanii. System ma docelowo pozwalać utworzyć nową postać w istniejącym Campaign World, zachowując committed reality, Event/Causal history, czas, NPC, organizacje, gospodarkę, przedmioty, własność, relacje, wiedzę holderów, World Processes, divergence i inne world-owned authority.

Poprzednia player character może po relinquish/retirement/death/control-transfer:
- pozostać pełnoprawnym World Actorem/NPC, jeżeli nadal żyje i istnieje w świecie;
- zachować własne stats/resources/skills/techniques/inventory/ownership/relationships/memory/knowledge i historię zgodnie z authority odpowiednich domen;
- podlegać później NPC Brain/Decision Engine i Living World zamiast pozostawać zamrożonym artefaktem starego save'a;
- zostać ponownie spotkana, obserwowana, wspomniana lub stać się stroną późniejszych wydarzeń;
- pozostać historycznym aktorem nawet po śmierci, bez usuwania skutków jej życia.

Nowa player character otrzymuje własną identity, Player State, knowledge holder state, memories i legalny initial/bootstrap state. Nie dziedziczy automatycznie prywatnej wiedzy, wspomnień, relacji, umiejętności, inventory ani metawiedzy poprzedniej postaci tylko dlatego, że steruje nią ten sam użytkownik.

`SAME HUMAN USER != SAME CHARACTER KNOWLEDGE HOLDER`.

Jeżeli gracz jako człowiek pamięta sekret ze starej postaci, nowa postać nadal musi zdobyć go legalnie przez Phase 37/38 acquisition/visibility rules, chyba że World Pack lub jawna canonical bootstrap rule legalnie nadaje tę wiedzę.

Control transfer jest commitowalną operacją domenową z durable provenance. System musi rozróżniać co najmniej:
- `ACTIVE_PLAYER_CHARACTER` — obecnie kontrolowany aktor;
- `FORMER_PLAYER_CHARACTER / RETIRED_TO_WORLD` — były PC pozostający aktorem świata;
- `DECEASED/HISTORICAL` — aktor nieaktywny biologicznie lub historycznie, którego skutki i historia pozostają;
- world-specific legal control states.

Jedna kampania może posiadać sekwencję wielu player characters bez resetu World UID/history. Save/Load, replay, branching, snapshot i migration muszą zachowywać zarówno ciągłość świata, jak i historię zmian aktywnego PC.

Zmiana postaci nie może:
- kopiować całej wiedzy starego PC do nowego;
- usuwać starego PC z Event/Causal history;
- duplikować unikalnej własności/przedmiotów;
- resetować NPC knowledge/reputation/world consequences;
- tworzyć drugiego Campaign World pod pozorem tej samej kampanii;
- pozwalać AI na nieautoryzowany transfer kontroli.

Docelowy przykład legalny: gracz kończy grę magiem, tworzy wiedźmina w tym samym świecie, a dawny mag nadal istnieje jako autonomiczny World Actor z własną wiedzą i konsekwencjami; nowy wiedźmin posiada odrębny epistemiczny i mechaniczny stan i może później spotkać poprzednią postać.

## 16. Memory i Chronicle
Trwała pamięć kampanii należy do RPG OS, nie do modelu/provider/runtime/KV/session cache.

Główne poziomy: Working, Episodic, Semantic Campaign Memory. Consolidation nie może opierać się na recursive summary-of-summary; Event history pozostaje historycznym źródłem.

Chronicle jest czytelną projekcją committed structured reality, nie source of truth.

## 17. Validation, Counterfactual Guard i Repair
Consistency/Invariant validators sprawdzają odpowiednie dla operacji canon/divergence, timeline, NPC knowledge, stats/resources, inventory/ownership/location, causality, projects i World Pack legality.

Counterfactual Guard odrzuca retrospektywnie wymyśloną historię bez legalnego evidence. Repair Pass naprawia lokalny błąd bez zmiany authority poza canonical transaction path.

Priorytet konfliktów: authoritative current state -> committed event history -> authoritative domain state -> campaign divergence -> canon -> persistent memory -> recent committed narrative -> AI inference.

## 18. Director, pacing i narrative systems
Director steruje uwagą/pacingiem, nie prawami świata. Narrative Promise Ledger, pacing metrics, anti-repetition i Narrative Style Profile wspierają spójność długiej kampanii.

Narrative Style Profile jest trwałym profilem kampanii, nie chwilowym promptem rekonstruowanym z całej historii.

## 19. Snapshots, Save/Load, branching i schema migration
Load rekonstruuje latest valid snapshot + wymagany committed tail. Snapshot jest recovery/performance mechanism, nie zastępstwem historii.

Automatyczna retencja zachowuje maksymalnie 6 najnowszych eligible AUTOMATIC snapshots kampanii; nie usuwa manual/export/pre-restore/pinned recovery points ani Event history.

Branching współdzieli historię do punktu rozgałęzienia i rozdziela późniejsze eventy zamiast kopiować pełną bazę.

Schemas są versioned. Forward migrations chronią stare kampanie, fail closed na unsupported future/corrupt state i nie fabrykują legacy provenance. Material migrations używają sprawdzonego recoverable safety snapshotu oraz durable migration lifecycle.

World Pack update nie nadpisuje campaign divergence.

## 20. Debug, observability, replay i performance
Developer tooling mierzy retrieval/context/AI/validation/commit oraz relevant simulation/runtime metrics. Replay Debugger pokazuje pełny legalny pipeline od input/intent przez retrieval/proposal/validation do events/state/commit.

Android jest głównym targetem. Typowa tura nie skanuje liniowo pełnej historii ani całego świata. Używamy working state, indexes, bounded retrieval, snapshots, LOD, multi-rate simulation i cache/derived data.

Local inference profiling obejmuje m.in. storage/load, TTFT, prefill/decode, RAM/KV, battery/thermal, cancel, OOM/restart i sustained workload. World simulation posiada budżet pracy/backlog tak, aby nie blokować UI.

## 21. Finalny pipeline tury
`PLAYER INPUT -> Input Normalizer -> Intent Parser -> Turn Planner -> Retrieval -> Knowledge/Temporal Filters -> Rule/Simulation Precheck -> Domain Mechanics -> Director Context -> Context Budget -> Context Bundle -> AI GM -> Structured Proposal -> Mechanics/ChangeSets -> Consistency/Invariant/Counterfactual Validation -> Repair -> Transaction -> COMMIT -> Derived/Presentation Snapshots -> committed narrative -> deferred bounded work`.

Deferred/background operacje nie mogą poza kontrolowaną transakcją zmieniać authoritative state.

## 22. World Pack Creator — POST-ROADMAP ONLY
Produkcyjny World Pack Creator rozpoczyna się dopiero po globalnym zaakceptowaniu Phase 1–84, chyba że użytkownik jawnie zmieni kolejność. Nie rezerwujemy obecnie Phase 85.

WPC jest authoring/compiler layer nad finalnym Core, nie drugim RPG OS. Nie implementuje własnego Event Store, Memory, Retriever, NPC Brain, World Simulation, Save/Load ani Transaction Engine.

WPC może kompilować canon/definitions, entities, locations, organizations, temporal facts, relationships, causal baseline, supported World Rules, initial knowledge seeds, simulation baseline metadata, provenance/localization i Scenario Templates.

Build Workspace != Campaign Repository; draft != active canon. Candidate pack przechodzi staging -> compile -> validation -> compatibility -> explicit/atomic activation.

Generated, imported, AI-assisted i hand-authored pack używają jednego runtime World Pack contractu. WPC korzysta po roadmapie z tego samego provider/workload routing co system; AI output pozostaje proposal do build-time validation.

## 23. Development protocol
Przed zmianą:
1. przeczytaj `docs/Architektura projektu.md`, `docs/Roadmap.md`, `docs/Mapa plików.md` i właściwy coordination/work protocol;
2. sprawdź aktualny `master`, code/schema/migrations/tests/CI;
3. sklasyfikuj rzeczywisty stan `COMPLETE / PARTIAL / MISSING / BLOCKED`;
4. wybierz najwcześniejszą brakującą zależność i minimalny bezpieczny delta;
5. nie twórz równoległego systemu tylko dlatego, że istniejący ma inną nazwę.

Zmiany są małe, migration-safe i chronią istniejące kampanie. Nie cofaj historii ani nie force-pushuj `master` bez jawnego polecenia.

Worker respektuje coordination policy, allowed/forbidden scope i reservations. Globalny status fazy zmienia koordynator po integracji i evidence.

Frontend może być rozwijany wraz z funkcjonalnością, ale zachowuje zaakceptowany styl i nie wykonuje niepowiązanego globalnego redesignu.

## 24. Priorytet projektowy i DONE
Priorytet:
1. Data integrity
2. Campaign continuity
3. Correct mechanics
4. Compatibility/recoverability
5. Performance
6. Retrieval quality
7. Narrative/world intelligence
8. Additional content
9. Visual polish

Etap jest DONE dopiero gdy implementation exists + integrated + persistence works + migration safe where needed + core invariants są testowane + build/CI succeeds + existing campaign compatibility preserved + brak nierozwiązanego conflict legacy/new system.

Sama klasa, tabela, dokument albo raport workera nie oznacza DONE.

## 25. Ostateczny cel
RPG OS ma działać jak trwały system świata, nie chatbot z długim promptem.

`DATABASE/STATE/EVENTS = REALITY`

`RULE ENGINE = WHAT CAN HAPPEN`

`KNOWLEDGE+TEMPORAL = WHO KNOWS WHAT AND WHEN`

`MEMORY+RETRIEVAL = WHAT MATTERS NOW`

`WORLD SIMULATION = WHAT ACTUALLY DEVELOPS`

`DIRECTOR = WHAT DESERVES ATTENTION`

`AI GM = HOW IT IS INTERPRETED/PRESENTED`

`VALIDATORS+TRANSACTION = WHAT BECOMES TRUE`

Po bardzo długiej kampanii system ma umieć wyjaśnić co, kiedy, gdzie, kto, dlaczego, co zmieniło, kto o tym wie, czy nadal jest to prawdą i skąd to wiemy — bez czytania całej kampanii od początku.