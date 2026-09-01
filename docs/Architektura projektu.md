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

## 0.1 Globalny Invariant Uniwersalności — OBOWIĄZUJE WSZYSTKIE PRZYSZŁE FAZY
RPG OS jest uniwersalnym Core dla dowolnego uniwersum, World Packa, gatunku gry, stylu rozgrywki i rodzaju odgrywanej postaci. **Każda przyszła faza, mechanizm, domena, API, schema, resolver, AI boundary i acceptance contract MUSI być projektowany najpierw jako rozwiązanie world-agnostic.**

Nie wolno projektować Core pod jeden lub dwa aktualnie używane World Packi ani traktować Naruto, Bleach, Wiedźmina, fantasy, sci-fi, strategii, horroru czy jakiegokolwiek innego świata jako ukrytego modelu referencyjnego Core. Przykłady z konkretnych światów są wyłącznie testami/fixtures; nie mogą stać się hardcoded authority, nazwą domeny ani warunkiem działania Core.

Globalne zasady:
- `CORE DEFINES UNIVERSAL CONTRACTS; WORLD PACK DEFINES SEMANTICS AND CONTENT`;
- `WORLD PACK MAY EXTEND DATA/RULE DEFINITIONS; IT MAY NOT REPLACE CORE AUTHORITY ENGINES`;
- future feature musi działać dla różnych typów aktorów: pojedynczej postaci, grupy, organizacji, państwa, armii, pojazdu, bytu nieludzkiego, kolektywu, AI/world-defined actor itd., jeżeli dana domena ma do nich zastosowanie;
- future feature musi uwzględniać różne style gry: character RPG, tactical/strategy, management, trading, science/research, medicine, investigation, espionage/politics, exploration i World Pack-defined styles;
- world-specific role, race, rank, ability, sense, secrecy class, resource, organization lub metafizyka jest `UID/definition/rule` dostarczanym przez World Pack, nie `if/else` zaszytym w Core;
- jeżeli rozwiązanie działa tylko w jednym/dwóch obecnych World Packach, faza jest jakościowo NIEGOTOWA do canonical acceptance, chyba że sama faza jest jawnie World-Pack-specific (np. Phase 80–84 integration packs);
- acceptance nowych Core phases MUSI zawierać multi-world / multi-style adversarial cases potwierdzające brak ukrytego world lock-in.

Ten invariant ma pierwszeństwo przed wygodą implementacyjną późniejszych faz i nie może zostać osłabiony przez prompt, AI provider, UI ani World Pack.

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

### 9.1 World Actor Mechanical Domain — CANONICAL FUTURE CONTRACT
Combat, Living World i inne mechaniki potrzebują jednego uniwersalnego widoku rzeczywistych możliwości aktora. Core nie może utrzymywać osobnych, konkurencyjnych fizyk dla Playera i NPC. Zaakceptowany Player Domain Phase 1–36 pozostaje bez zmian; przyszły `WorldActorMechanicalView`/adapter udostępnia wspólny kontrakt nad istniejącym Player State, a NPC/monster/summon/vehicle/unit mogą posiadać native `WorldActorMechanicalState`.

Minimalny kontrakt mechanicznego aktora obejmuje zależnie od świata: identity, dynamic attributes, resources, skills, executable abilities/techniques, innate traits, resistances, equipment, target components/anatomy, conditions, wounds/structural damage, cooldowns i effective modifiers. `KNOWLEDGE ABOUT ABILITY != EXECUTABLE ABILITY AUTHORITY`.

`GENERATION TEMPLATE != CURRENT MECHANICAL STATE`. Po materializacji aktor posiada persistent canonical state i zmienia go wyłącznie przez legalny domain/transaction path: trening, rozwój, obrażenia, starzenie, equipment, learned abilities itd. Aktualny stan nie może być ponownie losowany z archetypu przy kolejnym spotkaniu.

### 9.2 World Actor Generation & Materialization Framework
Core dostarcza uniwersalny język generacji, nie world-specific zawartość. World Pack może komponować dowolne archetypes/definitions i reguły `REQUIRED`, `CONDITIONAL`, `WEIGHTED`, `FORBIDDEN`, dependencies/exclusions, rarity/population/uniqueness constraints oraz mechanical power envelopes. Core nie zna pojęć `GENIN`, `DRAGON`, `WITCHER` itd.

Generacja używa controlled variance, korelacji i budget/envelope constraints zamiast niezależnego randomowania wszystkich statów. Hierarchiczny persistent seed rozdziela co najmniej mechanical/appearance/personality/knowledge/history randomness, aby zmiana jednego generatora nie zmieniała innych domen. Existing canonical facts zawsze mają pierwszeństwo przed generative defaults.

Materialization może być lazy (`SEED_ONLY` / `PARTIAL_MECHANICAL` / `FULL_MECHANICAL`) dla skali Living World, ale musi być deterministic/replay-safe i conservation-safe. Actor promoted z population/group aggregate zachowuje wcześniejsze fakty, a aggregate traci odpowiadającego mu członka/zasoby.

Zwykły actor power wynika z world context, roli, frakcji, rank/status, historii i World Pack constraints — nie z mocy aktualnego PC. Globalny invariant: `ENCOUNTER DIFFICULTY MUST EMERGE FROM WORLD STATE, NOT PLAYER POWER SCALING`. Director/AI nie może retconować statów ani generować perfect counter tylko dlatego, że zna kartę gracza. Wyjątek wymaga legalnej causal przyczyny w świecie; np. organizacja świadomie dobiera przeciwnika na podstawie własnej holder-scoped wiedzy o PC.

### 9.3 Universal Combat Engine — IMPLEMENTED CANDIDATE / CANONICAL CONTRACT
Combat Engine nie wybiera intencji aktora, nie generuje przeciwników i nie jest źródłem narracji. `DECISION ENGINE DECIDES; COMBAT ENGINE RESOLVES`. Jego zadanie: z legalnego `CombatIntent`/`CombatAction`, immutable relevant snapshotu, World Rules, przestrzeni, czasu i deterministic RNG evidence wyprowadzić `CombatResolution`/domain ChangeSets, które dopiero canonical transaction może commitować.

Docelowy pipeline:
`CombatIntent -> Action Construction -> Eligibility/Preconditions -> Spatial Feasibility -> Temporal Scheduling -> Detection/Perception -> Reaction/Interrupt Opportunities -> Action-Action Interaction/Clash -> Contest Resolution -> Effect Pipeline -> Target Components/Protection/Resistance -> Conditions/Resources/Movement -> Objective Evaluation -> Resolution Evidence -> Domain ChangeSets -> Validation -> TurnTransaction -> Events/Causal Graph/Ledgers -> COMMIT -> Knowledge acquisition/narration`.

Core zapewnia abstrakcje, a World Pack definiuje konkretne reguły. Spatial model może używać exact coordinates, grid, zones, range bands, formation space lub world-defined resolvera. Timing nie zakłada wyłącznie rund; akcja może mieć fazy `DECLARE/PREPARE/COMMIT/EXECUTE/IMPACT/RECOVERY`, simultaneous actions, delayed effects, reactions i interrupts.

Reaction jest legalna tylko gdy aktor posiada capability, wykrył/zna zagrożenie, ma czas i wymagany resource. `COUNTER CAPABILITY MUST PREEXIST`; `COUNTER SELECTION MUST USE ACTOR-AVAILABLE KNOWLEDGE`. Ukryta akcja istniejąca w FACT nie daje automatycznej reakcji targetowi.

Effect resolution jest kompozycyjne i nie redukuje walki do jednego HP: damage/wounds, resources, status, displacement, equipment/structure damage, morale/cohesion, formation, environment i World Pack-defined effects. Optional `TargetComponentModel` obsługuje ciało, skrzydła smoka, moduły pojazdu, okręt itd. Mechanika zachowuje degree-of-effect i objective outcomes (kill/capture/delay/escape/protect/hold/break formation/survive/world-defined), nie tylko `winnerUid`.

Efekty statusu są własnością Core, nie zawartością konkretnego świata. `UniversalStatusEffectRegistry` definiuje stabilne semantyki i stacking policy m.in. dla `BURNING`, `POISONED`, `PARALYZED`, `FROZEN`, `BLEEDING`, `STUNNED`, `SLOWED` i `ROOTED`. World Pack definiuje zdolność oraz typed `AbilityStatusApplication(statusEffectUid, chanceBasisPoints)` — np. zdolność może mieć 20% szansy na `BURNING`, lecz nie może tworzyć prywatnego znaczenia statusu omijającego Core. Canonical materializer zachowuje identity statusu także dla aggregate condition.

AoE jest uniwersalnym contractem shape/range/cost/targeting, a nie specjalnym przypadkiem kuli ognia. Blast, cone, line, zone, sweep i inne rodziny zdolności wiążą się przez `CombatAbilityContractPort`. Neutralny fallback nie nakłada statusu; szansa i powiązanie statusu pochodzą z World Packa.

Duże walki pozostają bounded. `AggregateAreaEffectResolver`, `AggregateDirectImpactResolver` i `AggregateGroupEngagementResolver` rozstrzygają w O(1) individual-vs-group, group-vs-group i unit-vs-unit na licznościach oraz reprezentatywnych parametrach, bez iterowania po tysiącach celów. Ekstremalna różnica siły może zamienić pojedynczy/melee atak w ograniczony group impact; throughput/casualty bounds zapobiegają arbitralnemu „wszyscy giną”.

Combat supports LOD: `LOD0 strategic aggregate`, `LOD1 formations/units`, `LOD2 groups + important actors`, `LOD3 full individual tactical resolution`. Przejścia LOD zachowują manpower/resources/casualties/unique actors/equipment/important conditions i nie materializują dodatkowej authority. Lokalny rezultat LOD3 może propagować causal effect do LOD1/0, np. utrata generała -> command/morale effect.

Mechanical fairness oznacza te same legalne reguły i brak hidden boost/rubber-banding, nie równe szanse. Easy, fair i suicidal encounters są legalne. Extreme mismatch może dawać deterministic outcome bounds zamiast obowiązkowego RNG; ekspert nie ma sztucznego fixed-percent critical failure bez world-rule przyczyny.

Player Agency pozostaje nadrzędna: forced mechanical consequence (`knockback`, stun, unconsciousness itd.) nie jest voluntary PC action. Combat Engine nie może sam wybierać ruchu/dialogu/techniki aktualnego PC.

### 9.4 Adaptive Turn Runtime & Response-Time Policy
RPG OS optymalizuje perceived latency do jakości, nie do minimalnej liczby milisekund. Globalne cele: `MECHANICS LATENCY << AI LATENCY`, `SIMULATION COST scales with RELEVANT STATE, not TOTAL WORLD SIZE`, `NO SERIAL AI CALLS WITHOUT NECESSITY`, `PRECOMPUTE/PARALLELIZE work that does not require AI output`.

`AdaptiveTurnRuntime` jest performance orchestrator, nie source of truth ani mutation authority. Zarządza workload estimation, AI-latency estimation, quality budgetem, parallel preparation, fast/deep paths i background-safe work. Praca jest klasyfikowana co najmniej jako `CRITICAL`, `REQUIRED`, `QUALITY`, `BACKGROUND`; deadline może ograniczyć tylko opcjonalną jakość, nigdy player agency, canonical validation, World Rules, transaction/replay safety ani data integrity.

Domyślny `ResponseTimeMode.AUTO` dobiera budżet do modelu, urządzenia, thermal state i workload. Normalna interaktywna tura ma preferred target około `5 s`, lecz nie jest to correctness timeout. Szybszy model powinien pozwalać użyć wolnego budżetu na relevant retrieval, continuity/consistency, NPC/world evaluation, combat verification lub narrative repair zamiast sztucznego natychmiastowego zwrotu.

Ustawienia aplikacji mogą oferować proste profile `AUTO` (default), `FAST`, `BALANCED`, `QUALITY`, `CUSTOM`; Custom może określać preferowane minimum czasu odpowiedzi. Przy ręcznie ustawionym minimum wolny czas jest zużywany `QUALITY FIRST -> IDLE ONLY LAST`. Auto nie musi sztucznie czekać do pełnych 5 s, jeśli kompletna odpowiedź jest gotowa i dalsza praca nie wnosi wartości.

Background-safe praca może wykorzystywać czas czytania/myślenia gracza, ale `SPECULATION MAY PREPARE; SPECULATION MAY NOT COMMIT`. Performance/profile sprzętu może zmieniać ilość opcjonalnej pracy, nigdy canonical semantics/mechanical truth.

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

Phase 37 przechowuje metadata potrzebne do access/visibility, np. `PUBLIC`, `PRIVATE`, `SECRET`, `CLASSIFIED`, `ROLE_RESTRICTED`, `ORGANIZATION_RESTRICTED`, `WORLD_SPECIFIC`; ich GM/NPC/PC/player-visible egzekwowanie zapewnia zaakceptowany Phase 38 boundary.

Player-visible, PC-known, NPC-known i GM/internal context nie są synonimami.

### 10.8 Temporal readiness, durability i context boundary
Model Phase 37 musi być temporal-ready: system później ma móc odpowiedzieć „co holder wiedział wtedy?”, a nie tylko „co wie teraz”. Pełny historical truth/query engine należy do Phase 39.

Canonical acquisitions uczestniczą w Single Truth Mutation Path, TurnTransaction, Event/Causal evidence, idempotency, rollback, snapshot/replay i schema/migration safety.

Retry tej samej logicznej acquisition nie tworzy duplikatu; rollback nie pozostawia phantom knowledge; snapshot/replay odtwarza ten sam epistemic state.

ContextBuilder nie jest authority wiedzy. Docelowo pobiera holder-scoped `KnowledgeContextProjection`/typed Knowledge API zamiast definiować własną semantykę bezpośrednimi SQL query.

### 10.9 Phase 38 — Universal Visibility, Access & Audience Boundary — ACCEPTED CONTRACT
Phase 38 jest drugim filarem epistemicznym po Phase 37. Phase 37 odpowiada `WHO KNOWS/THINKS WHAT AND WHY`; Phase 38 odpowiada `WHO MAY ACCESS / PERCEIVE / UNDERSTAND / RECEIVE WHICH INFORMATION FOR WHICH PURPOSE`. Nie tworzy nowej prawdy ani nowej wiedzy; buduje fail-closed projections nad authoritative state i Phase-37 epistemic state.

Globalny invariant:
`FACT != KNOWLEDGE != ACCESS != PERCEPTION != INTERPRETATION != DISCLOSURE != PRESENTATION`.
`DATA EXISTS` nigdy nie oznacza automatycznie `THIS AUDIENCE MAY SEE/USE IT`.

#### 10.9.1 World-agnostic Core / World Pack boundary
Core Phase 38 zna wyłącznie generic concepts: `AudienceContext`, `PurposeContext`, `VisibilitySubject/PropertyRef`, `AccessPolicy`, `AccessGrant/Revocation`, `Role/Organization/Clearance/Capability bindings`, `InformationCarrier`, `Signal`, `PerceptionAttempt`, `DisclosureLevel`, `VisibilityDecision/Projection` i provenance. Core nie zna nazw ról, klas tajności, zmysłów, technologii, magii ani metafizyki konkretnego świata.

World Pack może definiować role, organizations, clearance levels, carrier kinds, signal/detection channels, comprehension capabilities, protection/bypass rules i world-specific policies, ale **nie może implementować konkurencyjnego Visibility/Access Engine**. Unknown/unsupported world rule failuje zamknięcie zamiast awansować do PUBLIC.

#### 10.9.2 Audience i purpose są obowiązkowe
Chroniony odczyt posiada jawny `AudienceContext` związany co najmniej z campaign, audience kind oraz odpowiednimi holder/actor/organization/role/grant/capability bindings. `PurposeContext` ogranicza minimalny potrzebny widok, np. world simulation, actor decision, combat decision, dialogue, planning, player narration, player UI, player suggestion lub authorized debug.

Nie istnieje jeden omniscient `ContextBundle`, z którego consumer ma sam usuwać sekrety. Raw authoritative stores -> Phase38 projection -> purpose filter -> consumer context.

`WORLD_INTERNAL`, `GM_INTERNAL`, `ACTOR_INTERNAL`, `PC_INTERNAL`, `PLAYER_VISIBLE`, `ORGANIZATION/ROLE_CONTEXT`, `PUBLIC` są różnymi audience semantics, nie kopiami prawdy. Projection privilege może pozostać równy lub maleć; nie może implicit eskalować do szerszego audience.

#### 10.9.3 Policy access i effective access są rozdzielone
`AUTHORIZED TO ACCESS != CAN EFFECTIVELY OBTAIN`. Formalny role/clearance/grant nie jest jedyną drogą informacji: świat może pozwalać na theft, interception, physical/technical bypass, social engineering, telepathy, magical/technical penetration albo World Pack-defined mechanisms. Phase 38 musi reprezentować legalność/authorization oddzielnie od faktycznego effective access; konsekwencje prawne/moralne należą do odpowiednich domen świata.

Pipeline dostępu rozróżnia co najmniej: `AUTHORIZED -> REACHABLE/AVAILABLE -> OPEN/DECODE -> COMPREHEND -> DISCLOSE/OBSERVE -> possible Phase37 acquisition`. `ACCESS != ACQUISITION`; dostęp do biblioteki/bazy/archiwum nie daje wiedzy o całej zawartości.

#### 10.9.4 PolicyAccessResolver i PerceptionResolver
Pod wspólnym `VisibilityAuthorityService` istnieją co najmniej dwa typy rozstrzygnięć:
- policy/carrier access — role, organization, grant, clearance, ownership, credentials, protection/bypass, availability;
- observational perception — signal emission, channel compatibility, detection capability, conditions, attention/capacity hooks, recognition/interpretation.

Perception operuje na signals/evidence, nie na omniscient objective identity. Dzięki temu disguise, illusion, stealth, camouflage, decoy, encryption i false credentials mogą działać bez automatycznego poprawiania obserwatora hidden FACT-em.

`DETECT != LOCATE != RECOGNIZE != CLASSIFY != INTERPRET != UNDERSTAND`. Expertise Phase 37 może poprawiać recognition/interpretation, lecz nie generuje sygnału ani wiedzy z niczego.

#### 10.9.5 Granular subjects i disclosure
Chroniony subject może być FACT/claim/evidence/carrier/event/entity/location/effect/resource/army/project/stat/property albo World Pack-defined ref. Ochrona może działać na pojedynczej właściwości (`SubjectPropertyRef`), ponieważ odbiorca może znać istnienie obiektu bez jego lokalizacji, właściciela, siły, celu lub innych pól.

Disclosure nie jest boolean. Core wspiera semantyczne poziomy typu `DENY`, `EXISTENCE_ONLY`, `CATEGORY/QUALITATIVE`, `APPROXIMATE/RANGE`, `SUMMARY`, `REDACTED`, `DETAILED`, `FULL` lub równoważny data-driven contract. Projection zachowuje uncertainty/confidence/precision/completeness/freshness zamiast zamieniać szacunek w dokładny FACT.

`NOT_VISIBLE`, `UNKNOWN`, `KNOWN_ABSENT`, `REDACTED`, `ACCESS_DENIED` i `UNRESOLVED` nie są synonimami.

#### 10.9.6 Holder, actor i player są odrębnymi tożsamościami
`WORLD ACTOR != KNOWLEDGE HOLDER != HUMAN PLAYER`. Jeden shared/hive mind może być jednym holderem dla wielu aktorów; possession, split party, multi-character control i World Pack-defined cognition mogą wiązać audience z wieloma holderami bez kopiowania ich acquisitions.

`PC_INTERNAL != PLAYER_VISIBLE`. Domyślny character-RPG player view jest ograniczony przez PC knowledge/perception, ale jawna game-mode/world policy może legalnie pokazać graczowi więcej (np. strategic UI, controlled-party union, spectator/cutscene disclosure) **bez nadawania tej wiedzy PC**. Player disclosure i PC acquisition muszą pozostawać osobnymi semantykami.

Former PC po legalnym control transfer staje się zwykłym World Actorem/holderem; nowy PC nie dziedziczy jego prywatnego visibility/knowledge state.

#### 10.9.7 Organization, role, clearance i grants
Institutional knowledge nie staje się osobistą pamięcią członków. Role/clearance/grant może udostępnić institutional view; dopiero rzeczywiste observation/read/briefing może utworzyć Phase37 acquisition. Revocation usuwa przyszły dostęp, nie kasuje osobistej wiedzy zdobytej wcześniej.

Canonical access grants/revocations/role/clearance bindings są campaign-qualified, temporal-ready i posiadają provenance/cause. Global immutable definitions są jawne; `campaignUid=null` nie oznacza automatycznie globalności.

#### 10.9.8 InformationCarrier i communication
Carrier jest generic nośnikiem informacji, nie hardcoded dokumentem: może być materialny, cyfrowy, biologiczny, magiczny, sygnałowy lub World Pack-defined. Możliwe poziomy interakcji obejmują istnienie/lokalizację/reach/open/decode/comprehend/copy/share zgodnie z rules świata.

Authorization nie gwarantuje availability: zerwana komunikacja, brak nośnika, zniszczony carrier, brak klucza/języka/capability albo opóźnienie raportu mogą blokować aktualny dostęp. Zniszczenie carriera nie usuwa wcześniejszej osobistej acquisition holdera.

#### 10.9.9 Reputation i public belief
Reputation nie jest omniscient globalnym score. Jest holder/group/institution-scoped assessment/belief o subject i typed dimension, z confidence/evidence/lineage. Różne populacje/frakcje mogą legalnie posiadać przeciwne reputacje tej samej osoby. Group/population holders i LOD aggregation mogą ograniczać koszt bez materializowania wiedzy każdego mieszkańca.

#### 10.9.10 Context, AI, UI i anti-leak boundary
`PROMPT INSTRUCTION IS NOT ACCESS CONTROL`. Sekret nie może trafiać do promptu/NPC brain/player suggestions/UI tylko z instrukcją „nie ujawniaj”. Consumer otrzymuje już zminimalizowaną/redacted `VisibilityProjection`.

Presentation layer nie otrzymuje hidden raw fields tylko po to, by ukryć je wizualnie. Player suggestions, `Continue`, situation recap, dialogue, NPC decision, Combat reaction, local AI i cloud AI muszą używać odpowiedniego audience+purpose projection. Cloud może mieć inny format/compression, ale nie szersze semantic entitlement.

World Simulation/Combat physics może używać hidden FACT do rozstrzygnięcia rzeczywistych skutków; aktor Decision Engine nie może używać tego FACT do dobrowolnej decyzji bez legalnej perception/knowledge. `PHYSICS MAY KNOW; VOLITION MAY NOT CHEAT`.

#### 10.9.11 Persistence, replay i temporal readiness
Authoritative są co najmniej trwałe grants/revocations/bindings oraz inne world-state changes wpływające na access. `VisibilityDecision`, `VisibilityProjection` i większość query-time perception views mogą być derived, ale każde losowe/nieodwracalne perception result wpływające na historię musi być replay-safe przez Event/evidence/RNG provenance.

Phase 38 jest temporal-ready dla Phase 39: później musi dać się odtworzyć, kto miał legalny/effective access lub player disclosure w czasie T. Snapshot/load/replay/branch/undo zachowują access-authority state i nie pozostawiają wiedzy/disclosure z cofniętej linii.

#### 10.9.12 Performance i LOD
Visibility jest oceniane on-demand/batch dla relevant subjects/audiences, nie materializowane jako globalna macierz `all actors x all facts`. System wspiera group audience/aggregate, batch evaluation, cache/derived projections z bezpieczną invalidacją i lazy detail. Koszt skaluje się z relevant state, nie całym światem.

#### 10.9.13 Visibility Consumer Inventory — GLOBAL GUARD
Każdy runtime subsystem/UI/AI path konsumujący protected information musi być jawnie sklasyfikowany jako `ProtectedInformationConsumer`/równoważny wpis z dozwolonym audience/purpose/projection source. Brak klasyfikacji lub bezpośredni protected raw-query bypass failuje repository-wide validation/CI.

World Pack, plugin, UI ani przyszła faza nie może ominąć Phase38 przez własną tabelę/flagę `visible`, raw SQL ani prompt. Ten inventory jest odpowiednikiem writer-inventory guardu dla odczytów chronionych informacji.

#### 10.9.14 Fail-closed i explainability
Unknown audience/policy/role/grant/disclosure/cross-campaign ref/corrupted lineage/projection schema -> `DENY` lub typed corruption/error, nigdy fallback PUBLIC. Same-name/legacy `hidden`, `gm`, `visibility` fields są compatibility inputami do validated adaptera, nie canonical authority.

Authorized debug może wyjaśnić `WHY ALLOW/DENY/PARTIAL` przez policy/role/grant/capability/evidence bez przekazywania samej internal explanation niewłaściwemu audience.

Phase 38 nie implementuje jeszcze pełnego Living World, Decision Engine, Combat Engine, rumor propagation, Temporal Engine ani World Pack Creator. Dostarcza uniwersalny boundary, z którego te systemy korzystają.

## 11. Temporal Engine, Scheduler i Time Skip
Stan historyczny może posiadać `validFrom/validUntil` lub równoważny temporal contract. Retrieval musi odpowiadać „co było prawdą wtedy?”, nie automatycznie używać teraźniejszości.

Scheduler planuje evaluation points/deadlines, nie z góry outcome. Przyszły rezultat powstaje dopiero po ocenie aktualnego stanu i reguł.

Time Skip orkiestruje upływ czasu przez odpowiednie subsystemy: scheduled evaluations, player/NPC progression, age/family, projects, economy, travel, organizations, wars/politics, world simulation, relationships, knowledge propagation, memory consolidation i snapshot/state update.

## 12. Retrieval, Intent, Turn Planner i Context
Canonical pipeline Phase 39–47 jest addytywnym następcą wcześniejszej ścieżki regułowej:

```text
raw player input
  -> IntentDocument v2 + deterministic validator
  -> GraphTurnPlanner
  -> CapabilityDescriptor + CapabilityEnvelope
  -> allowlisted StructuredRetrievalRequest
  -> Phase38-projected ContextIntegrityBuilder
  -> non-droppable SemanticCoreCapsule
  -> SemanticContextBudgetManager
  -> typed, envelope-constrained bounded completion
  -> BudgetedCanonicalContext.safeForAi
```

`IntentDocument` zachowuje graf wielu działań, participants/roles, reference states, future-result dependencies, conditions, constraints/preferences, modality/polarity, correction/cancellation relations, commitment state, uncertainty i player-context claims. AI może zaproponować semantykę, ale nie może sam nadać canonical action UID ani rozwiązać hidden/world UID. `LegacyIntentDocumentAdapter` utrzymuje deterministic fallback; legacy parser nie jest już canonical semantic schema nowych ścieżek.

`GraphTurnPlanner` jest pure i deterministic. Nie czyta repository, nie wykonuje mechaniki i nie mutuje świata. Dopasowuje wyłącznie capabilities z trusted composition root, planuje wszystkie legalnie rozwiązane cele i wydaje formalne envelope określające campaign/audience/purpose, provider/operation, filter dimensions, fixed identity, at-order, cursor capability i limit. Brak capability, niejednoznaczność lub brak wymaganego resolved reference prowadzą do adjudication/rejection, nie do zgadywania.

Retrieval jest allowlisted, temporal/graph-aware, bounded i iteracyjny. Provider ordering pozostaje semantycznym kontraktem providera; Core nie przestawia wyników globalnie. Cursor jest opaque i kryptograficznie związany ze scope requestu. Nadmiarowy wynik jest jawnie incomplete, unsafe cursor/duplicate identity jest corruption, a programmer errors nie są maskowane jako data corruption.

`ContextIntegrityBuilder` przyjmuje wyłącznie wyniki wymagań obecnych w TurnPlan, zachowuje typed retrieval state i wymaga projected provenance oraz bezpiecznych wartości payloadu. `SemanticCoreCapsule` zawiera pełną canonical semantykę intencji i planu i nigdy nie jest dropowany. Budżet obejmuje core, segmenty, final serialized payload oraz osobne protocol/system/output/safety reserves. REQUIRED/SAFETY context nie jest po cichu usuwany: overflow lub brak danych oznacza `safeForAi=false`.

Phase 47 może wyłącznie zawężać albo kontynuować request w oryginalnym `CapabilityEnvelope`. Obowiązują limity iteracji, follow-upów, rekordów i payload units, deduplikacja fingerprintów, re-budget po każdej iteracji i typed terminal reason. Strategy nie może rozszerzyć campaign, audience, purpose, provider, operation, identity, filter dimensions, at-order, cursor capability ani limitu.

Local context i cloud context nie muszą być identyczne. Oba powstają z tego samego semantic entitlement; cloud otrzymuje minimalny, zadaniowy, sanitizowany i dozwolony bundle — nigdy automatycznie całe Save/Chronicle/DB.

### 12.1 Pamięć semantyczna Bekko a8m — rebuildable ranking sidecar

Bekko jest niezależnym od modeli generatywnych systemem `SEARCH / MATCH / RANK / CLUSTER`. Jego wynik jest kandydatem rankingu, nigdy FACT-em, rozstrzygnięciem mechaniki, przyczynowością ani mutation proposal. Bielik, OpenRouter, dowolny generatywny GGUF i Bekko mają rozdzielne modele, ustawienia oraz uchwyty runtime.

```text
canonical commit/replay
  -> Phase38 audience+purpose projection
  -> deterministic <=512-token chunks
  -> Bekko mean pooling + L2 (384)
  -> Matryoshka first 256 + ponowne L2
  -> per-campaign FP16 CACHE/REBUILDABLE sidecar

CapabilityEnvelope
  -> authorized UID/kind/time scope
  -> exact/structured/graph/temporal filters
  -> exact cosine/dot scan tylko tego scope
  -> stable canonical-UID tie break
  -> Phase45 integrity + typed budget
  -> GM/Director/World Pack consumer
```

`EmbeddingProviderPort` posiada typed capabilities, availability/open, batch embedding, cancel i close. Produkcyjny adapter używa osobnego trybu embeddingów w izolowanym procesie `:local_ai`, osobnego uchwytu llama.cpp, mean pooling i L2. Domyślny profil to CPU/Q8_0; Vulkan tego samego artefaktu jest wyłącznie ręcznym wyborem wykonania i nie zmienia Core semantics.

Przypięty artefakt `bekko-embedding-v1-a8m-Q8_0.gguf` nie wchodzi do APK. Instalator pobiera go z osobnego wydania modeli, wznawia transfer, sprawdza stały rozmiar i SHA-256, instaluje atomowo oraz pozwala usunąć model wraz z wyłącznie odbudowywalnym indeksem. Manifest wiąże upstream revision, licencję i kontrakt embeddingów.

`SemanticDocumentProjector` może emitować tylko tekst legalny dla wskazanego campaign/audience/purpose/as-of. Globalny dump hidden knowledge nie jest wejściem embeddingów. Dokument indeksowy zachowuje namespace, canonical UID, kind, FACT/BELIEF/NARRATIVE, as-of order, source version/fingerprint, chunk i projector version. Indeks nie uczestniczy w canonical hash, save, snapshot, receipt ani replay truth.

`SemanticIndexPort.searchAuthorized` wykonuje deterministyczny dokładny scan po wcześniej autoryzowanej liście UID. Nie ma globalnego ANN przeszukującego hidden rekordy. Wyniki fragmentów są scalane do canonical UID, a remisy rozstrzyga stabilny UID. Exact UID i niedropowalny REQUIRED/SAFETY context mają pierwszeństwo przed score Bekko.

Natychmiastowa kolejka indeksująca jest uruchamiana dopiero po `Committed` lub zgodnym `AlreadyCommitted`, poza canonical transakcją. Rollback nie tworzy wpisu. Nie ma okresowego WorkManagera: przy otwarciu kampanii checkpoint indeksu jest porównywany z receipt/replay, a luka jest uzupełniana. Niezindeksowany hot tail pozostaje dostępny przez typed structured reads. Wersja indeksu wiąże model SHA, wymiar, normalizację, vector format i projector; niezgodność wymusza czystą odbudowę, nigdy mieszanie wektorów.

Bekko jest trusted Phase41 `StructuredQueryProvider`, ale każda aktywna operacja nadal pochodzi z Phase44 `CapabilityEnvelope` i przechodzi Phase45 integrity/budget. Aktywni konsumenci to MG memory, Director Semantic Scout oraz World Pack search. Typed future candidate ports istnieją dla Phase58, Phase61–64, Phase66 i Phase68, lecz bez właściciela nie są aktywne i nie mogą oznaczyć tych faz jako ukończonych ani tworzyć aliasów, konsolidacji, FACT, `CAUSES`, decyzji NPC lub zmian świata.

Każdy błąd modelu, procesu, indeksu lub wersji zwraca typed reason i przełącza na istniejący retrieval/hot tail bez mutacji i bez blokowania tury. Emulator potwierdza UI i correctness flow; wydajność, Vulkan, temperatura oraz współistnienie z Bielikiem są akceptowane wyłącznie na fizycznym Galaxy S24.

## 13. Role-based Local/Cloud AI — IMPLEMENTED CANDIDATE
Istnieje jeden provider-independent system AI. Nie ma osobnych trybów produktu Local/Cloud/Hybrid. Użytkownik przypisuje model niezależnie do roli Game Master oraz Director/Scenarist: `Auto` albo kompatybilny model local/cloud.

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

Repair candidate Phase48–54 implementuje `AiProvider`, registry/capability contracts, role-aware deterministic router, universal `LocalAiPort`/`CloudAiPort`, lifecycle/admission/settings/artifact contracts, spakowany oficjalny ExecuTorch Android runtime, mobilny profil Bielik 1.5B v3 ExecuTorch/XNNPACK, zachowany legacy profil Bielik 4.5B, OpenRouter PKCE/Keystore/discovery/inference adapter oraz workload-specific strict JSON Schema z ponowną walidacją w Core. Deterministic provider pozostaje wyłącznie controlled conformance backend i przechodzi ten sam production port.

Administracyjne ścieżki kampanii (clone/activate/World Pack/backup/snapshot restore) przygotowują schema, definicje i world actors we wspólnym lifecycle-serialized `ADMIN` boundary, zanim przywrócą gameplay-ready state. Canonical turns tej samej kampanii są serializowane w jednym commit order, natomiast blokady pozostają rozdzielone per campaign. Zapobiega to zarówno naruszeniu guardów przez clone chronionego template, jak i wyciekowi SQLite busy przy równoległym idempotentnym retry.

Auto routing respektuje workload, context limit, availability, privacy, resource admission i explicit pins. Local jest preferowany dla normalnego GM workloadu, lecz nie jest fałszywie oznaczany READY bez modelu, bezpiecznego profilu urządzenia i działającego runtime. Director cadence pozostaje niezależny od wyboru providera.

Canonical runtime flow tego slice:

```text
ChatTurnRequest
  -> provider interpretation candidate
  -> trusted IntentDocument validation/resolution
  -> GraphTurnPlan + safe projected context
  -> structured GmProposalCandidate
  -> StructuredGmProposalValidator
  -> trusted MechanicsResolutionEngine (proposal only)
  -> ConsistencyValidator + CounterfactualGuard
  -> bounded repair + full revalidation
  -> CanonicalMutationAssembler
  -> existing TurnTransaction / CampaignRepository authority
  -> persisted V3 TurnCommitReceipt verification
  -> exact Phase38 player-visible post-commit readback
  -> committed-narrative semantic validation
  -> bounded repair or natural deterministic fallback
  -> idempotent/recoverable delivery
```

`AI CANDIDATE != VALIDATED PROPOSAL != MECHANICS RESOLUTION != CANONICAL MUTATION PROPOSAL != COMMIT`. Żaden provider, codec, proposal validator, mechanics resolver ani narrative renderer nie otrzymuje sam przez te kontrakty raw DB lub COMMIT authority. `CanonicalMutationAssembler` może zwrócić wyłącznie proposal już zapieczętowany przez istniejący PlayerDomainEngine admission path, a durable write pozostaje własnością istniejącej TurnTransaction.

Narracja jest renderowana dopiero po autoryzacji receiptu znalezionego w trwałym receipt store i exact post-commit readbacku przez Phase38 projection. Precommit proposal nie jest wejściem factual narration. Sam strukturalnie podobny obiekt receipt nie wystarcza. Awaria/cancel/invalid output przed commit nie mutuje kampanii; po udanym atomic commit awaria narracji zwraca typed committed-without-narrative. Recovery zaczyna od persisted receipt/readback i nigdy nie powtarza planu, mechanics ani commit.

Model, provider, runtime i backend są wymienne i nie mogą wymagać migracji kampanii.

Credentials/auth state nie należą do Campaign State, Save, Chronicle ani World Pack.

### 13.1a Production implementation gates

`CanonicalChatApplication` jest jedynym adapterem UI uprawnionym do wywołania `AiChatEngineFacade`. UI nie może wywołać DB, ChangeSet, mechanics, commit, raw OpenRouter HTTP ani local runtime. Stary `ViewModel -> StatePatch` został usunięty. Legacy backend jest odizolowany jako narration-only compatibility boundary; każdy zwrócony patch jest odrzucany przed LocalGameStore.

`ProductionGameEngineCompositionRoot` jest jednym composition rootem dla canonical Android chat: provider -> IntentDocument -> plan/context -> GM proposal -> mechanics/guards/repair -> existing TurnTransaction -> committed narration. `ProductionCanonicalMutationAssembler` wykonuje zależne multi-action w plan order, a `ProductionCombatSnapshotAuthority` nakłada wcześniej zweryfikowane staged effects na snapshot następnego node bez przedwczesnego zapisu. Dopiero cały zestaw typed effects przechodzi przez jeden TurnTransaction. Wcześniejsze blockery produkcyjnej kompozycji oraz brakującego native packaging są zamknięte; realne weights, urządzenie i live provider authorization pozostają osobnymi bramkami evidence, nie brakującym kodem Core.

Android presentation używa tych samych granic aplikacyjnych zamiast bocznych ścieżek: wspólny `AiProviderCenterScreen` konfiguruje role AI przed rozpoczęciem kampanii, dedykowany uniwersalny kreator deleguje draft/clarification/confirmation do `AiCharacterCreationApplication`, a panel postaci V2 jest wyłącznie visibility-gated derived projection z authoritative stores. Import kampanii/World Packów przechodzi przez validated package staging, eksport przez package manager, a przywracanie backupów i snapshotów pozostaje confirmation-gated i używa istniejącego verified recovery activation. UI nie otrzymuje bezpośredniej authority do mutowania campaign DB.

Phase50 ma własną sklasyfikowaną authoritative family `MECHANICAL_ACTOR_AND_AGGREGATE_STATE`. Administracyjny bootstrap materializuje PC, NPC, location/world actors, units i groups dokładnie raz z deterministic provenance; normalny combat snapshot nigdy nie generuje ponownie NPC na podstawie aktywnego gracza. Późniejsze wounds/resources/spatial/equipment/structure/tracks/aggregate population zmieniają typed owners wyłącznie wewnątrz TurnTransaction. `RuntimeChange` nie jest zastępczym ownerem dla nowych materialnych skutków i pozostaje jedynie wąską kompatybilnością replay dla wcześniejszego movement counter.

Phase54 zapisuje recovery marker natychmiast po autoryzacji trwałego receiptu. Po restarcie marker lub latest valid receipt pozwala odtworzyć wyłącznie etap readback/narration. Autoryzacja wiąże campaign, turn, command i transaction; readback pochodzi z replay payload dokładnie dla committed order, a delivery store zachowuje claims, `assertsPlayerVolition` i fingerprint. Recovery nie posiada wejścia do plannera, mechanics, assemblera ani commit portu.

OpenRouter authorization używa OAuth PKCE oraz ephemeral loopback callback, a credential trafia do Android Keystore poza campaign/save. Strona callbacku może raportować sukces dopiero po wykonaniu `/api/v1/auth/keys`, walidacji wyniku i zaszyfrowanym zapisie; typed transport reason pozostaje dostępny diagnostycznie bez ujawniania sekretu. Ręczne wprowadzenie klucza jest drugorzędnym recovery path i również wymaga online validation przed zapisem. Cloud otrzymuje wyłącznie projected/minimised structured context. Każdy workload wysyła nazwany `response_format.type=json_schema` z `strict=true`; routing wymaga obsługi parametrów, a `CanonicalAiJsonCodec` i walidatory Core nadal odrzucają semantycznie nielegalne dane.

### 13.1b Uniwersalne rozpoczęcie kampanii i postać gracza

Nowa kampania używa provider-independent workloadu `CHARACTER_CREATION`. AI/MG może prowadzić rozmowę i stworzyć wyłącznie `PlayerCharacterCreationDraft`; nie otrzymuje mutation authority. Draft musi zachować wybory użytkownika i zawierać kompletną tożsamość, płeć, startowe statystyki i zasoby, talent, potencjał, umiejętności, techniki, origins/innate features oraz startową lokalizację. `PlayerCharacterBootstrapService` zapisuje całość w jednej transakcji dopiero po osobnym `PlayerCharacterCreationConfirmation` związanym fingerprintem draftu.

`CharacterCreationDefinitionBootstrap` pobiera typed definitions aktywnego World Packa. Dla starszych paczek stosuje jawnie ograniczony adapter legacy, a dla poprawnej paczki bez schematu — namespaced, gatunkowo neutralny fallback. Brak World Pack authority nie prowadzi do fabrykowania definicji. Naruto jest fixture kompatybilności, nie architekturą kreatora.

### 13.2 Player agency
`VOLITIONAL PLAYER ACTION SOURCE = USER / VALIDATED PLAYER COMMAND ONLY`.

`ACTIVE_PLAYER_CHARACTER CONTROL AUTHORITY = USER ONLY`.

AI/MG, Director, NPC Brain, World Simulation, Scheduler, cloud/local provider ani żaden inny autonomiczny subsystem nie może przejąć kontroli nad aktualnie graną postacią, wygenerować za nią dobrowolnej decyzji ani zamienić proposal/narracji w akcję PC bez zwalidowanego inputu użytkownika.

AI nie dopisuje graczowi dobrowolnych ruchów, wypowiedzi, ataków, wyborów celu, użycia techniki/przedmiotu, akceptacji/odrzucenia zadania, zmiany celu, relacji, planu ani transferu kontroli. Mechanika może narzucić wyłącznie konsekwencje niezależne od woli, np. stun, knockback, utrata przytomności, forced movement lub inne jawnie zdefiniowane World Rule effects; taki skutek pozostaje `MECHANICAL CONSEQUENCE != VOLITIONAL PLAYER ACTION`.

System musi structurally rozróżniać co najmniej `PLAYER_COMMAND`, `NPC/WORLD ACTION`, `MECHANICAL CONSEQUENCE` i `NARRATIVE DESCRIPTION`. Brak inputu użytkownika nie może być interpretowany jako zgoda na działanie aktualnego PC.

Control transfer z `ACTIVE_PLAYER_CHARACTER` na innego aktora wymaga jawnej, zwalidowanej komendy użytkownika i canonical committed transition. AI/MG nie może samodzielnie przełączyć aktywnego PC, retired/relinquish obecnej postaci ani oddać jej pod NPC autonomy.

`ACTOR / ACTION / TARGET` muszą być zachowane strukturalnie. Provider conformance obejmuje player agency, direction, NPC knowledge isolation, FACT/BELIEF, stop point, invented abilities/dialogue, internal-context leakage i brak mutation authority.

### 13.3 Player Interaction Orchestrator, Suggestions, Continue i Undo
Interfejs gracza może być prosty jak komunikator, mimo że pipeline pod spodem pozostaje typed i rygorystyczny. Zwykły gracz powinien móc grać przez wpisanie wiadomości; opcjonalne skróty nie mogą zmieniać authority modelu.

`PLAYER ACTION CANDIDATE != PLAYER COMMAND != COMMIT`.

System rozróżnia co najmniej:
- `TYPED_PLAYER_COMMAND` — ręcznie wpisana/wybrana przez użytkownika decyzja;
- `SUGGESTED_PLAYER_COMMAND` — kandydat wygenerowany przez AI, który staje się PlayerCommand dopiero po jawnym kliknięciu/wyborze użytkownika;
- `CONTINUE_COMMAND` — jawna decyzja użytkownika, by nie tworzyć teraz nowej wolitywnej akcji i pozwolić światu/NPC/już zatwierdzonym procesom działać do następnego Player Decision Point;
- `UNDO_REQUEST` — jawne żądanie rekonstrukcji/branchingu do wcześniejszej committed granicy;
- `MECHANICAL CONSEQUENCE` — skutek niezależny od woli;
- `NARRATIVE DESCRIPTION` — prezentacja, bez authority.

`Player Interaction Orchestrator` jest przyszłą warstwą wejściową pomiędzy UI a canonical PlayerCommand/Turn pipeline. Nie posiada samodzielnej mutation authority; klasyfikuje żądanie, uruchamia bounded helper workflows i przekazuje do canonical validatora wyłącznie jawnie autoryzowane przez użytkownika komendy.

#### Suggestions
UI może posiadać akcję `Sugestie`, domyślnie pokazującą maksymalnie trzy krótkie, różnorodne kandydaty działania. AI może `PROPOSE / EXPLAIN / SIMULATE OPTIONS`, ale nie może `SELECT / AUTHORIZE / COMMIT` wolitywnej decyzji PC. Kliknięcie propozycji jest explicit user authorization i semantycznie odpowiada wpisaniu tej samej komendy ręcznie. Gracz zawsze może zignorować sugestie i wpisać własną odpowiedź.

Generator sugestii musi korzystać z `PLAYER CHARACTER EPISTEMIC CONTEXT`: wiedzy, obserwacji, pamięci i legalnie dostępnych informacji aktualnej PC. Nie dostaje omniscient GM/internal FACT tylko po to, by podpowiedzieć graczowi ruch; ukryta wiedza nie może wyciekać przez sugestię. Optional ranking może uwzględniać zadeklarowany styl, values/desires/dreams i historię wyborów PC, ale nie ogranicza legalnych własnych decyzji gracza.

Tryb `ASSISTED` może automatycznie wyświetlać sugestie po każdej turze, lecz authority pozostaje identyczne: żadna propozycja nie staje się działaniem bez wyboru użytkownika.

#### Continue i Player Decision Point
`CONTINUE_COMMAND` oznacza: kontynuuj legalne skutki już podjętej decyzji, działania NPC/świata i rozpoczęte procesy, ale nie wymyślaj nowej wolitywnej decyzji PC. Może dynamicznie przyjąć UI label typu `Kontynuuj`, `Kontynuuj podróż`, `Kontynuuj trening`, `Czekaj dalej`, pozostając tym samym typed contractem.

Living World/GM musi zatrzymać auto-advance przy `PLAYER DECISION POINT` — chwili, gdy dalszy istotny przebieg wymaga nowej dobrowolnej decyzji gracza. `Meaningful Interruption Policy` może uwzględniać significance/threat/opportunity/irreversibility, ale nie może służyć do pomijania ważnych wyborów PC. Soft stop powinien nastąpić przed znaczącą, nieautoryzowaną decyzją lub nieodwracalną konsekwencją zależną od woli PC.

#### Undo / rewind
`UNDO_REQUEST` nigdy nie jest pojedynczym przypadkowym write. `UNDO CONFIRMATION INVARIANT`: cofnięcie committed tury wymaga oddzielnego, świadomego potwierdzenia użytkownika po pierwszym żądaniu. Potwierdzenie powinno pokazać przynajmniej identyfikowalną ostatnią decyzję/granicę, która zostanie cofnięta.

Undo działa na pełnej canonical granicy tury/branch/reconstruction, nie przez ręczne odwracanie kilku rekordów. Musi cofnąć spójnie world state, knowledge, NPC reactions, resources, ownership, relations, events i inne skutki tej linii. Preferowany model zachowuje porzuconą przyszłość jako branch/evidence zamiast destrukcyjnego kasowania Event history. Większe cofnięcie z historii tur wymaga jeszcze wyraźniejszego potwierdzenia.

#### UX simplicity
`COMPLEXITY BELONGS IN THE ENGINE, NOT IN THE PLAYER INTERACTION SURFACE`.

Domyślna powierzchnia może ograniczać się do pola tekstowego oraz trzech akcji: `Cofnij`, `Kontynuuj`, `Sugestie`. Zaawansowane funkcje — historia tur, branching, knowledge view, goals, debug/advanced assistance — powinny być schowane za progressive disclosure/menu i nie zaśmiecać głównego ekranu. Maksymalna liczba jednocześnie prezentowanych sugestii powinna pozostać mała, domyślnie trzy.

UI może oferować `Co się dzieje?`/situation recap, ale podsumowanie dla gracza/PC musi respektować Phase 37/38 visibility i nie ujawniać GM/internal secrets.

### 13.4 Workload routing i Cloud Director
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

### 15.2 Motivational Core, Life Continuity i autonomous agency
Living World nie jest generatorem losowych wydarzeń. Jest symulatorem legalnych przyczyn świata. Zmiany powinny wynikać z aktorów, procesów, zasobów, wiedzy, możliwości, constraints i konsekwencji, a nie z arbitralnego wymagania fabularnego.

Każdy istotny autonomiczny World Actor może posiadać trwały `MOTIVATIONAL CORE`, odrębny od Knowledge i od samej decyzji. Model może obejmować zależnie od rodzaju aktora:
- needs / pressures;
- desires;
- dreams / aspirations;
- ambitions;
- fears / aversions;
- values / moral constraints;
- loyalties / obligations;
- grudges / attachments;
- short- i long-term goals;
- active plans / commitments;
- core drives / obsessions dla wyjątkowo trwałych motywacji.

Motywacja nie jest pojedynczym `goal` ani statycznym tekstem z promptu. Może powstawać, wzmacniać się, słabnąć, zostać zaspokojona, porzucona, zastąpiona lub wejść w konflikt z inną motywacją. Aktor może jednocześnie chcieć władzy, bezpieczeństwa rodziny i zachowania reputacji; Decision Engine rozstrzyga konflikt na podstawie pełnego stanu, nie jednej etykiety.

`KNOWLEDGE = WHAT ACTOR THINKS IS TRUE`.
`MOTIVATION = WHAT ACTOR WANTS / AVOIDS`.
`DECISION = WHAT ACTOR CHOOSES TO DO`.
`ACTION/COMMIT = WHAT LEGALLY CHANGES THE WORLD`.

Docelowa causal loop:
`WORLD STATE -> OBSERVATION/KNOWLEDGE -> PERSONALITY/VALUES/NEEDS -> DESIRES/DREAMS -> GOALS -> PLANS -> OPPORTUNITY/THREAT -> DECISION -> ACTION -> CONSEQUENCES -> WORLD STATE`.

Aktor nie otrzymuje omniscient najlepszego planu. Cele i plany powstają z jego własnej wiedzy/beliefs, expertise, resources, relationships, culture, organization constraints i dostępnych okazji. Błędna wiedza może więc legalnie prowadzić do błędnej decyzji.

`World Actor Life Continuity` oznacza, że istotny aktor zachowuje między scenami i time skipami własną wiedzę, memory, relationships, personality, values, motivational state, goals, commitments i legalnie rozpoczęte plans/projects. Nie jest resetowany do archetypu po zejściu z ekranu.

Autonomous goals mogą być wieloetapowe: dream/aspiration -> strategic goal -> subgoals -> plan -> actions. Porażka może prowadzić do retry, replanning, zmiany metody, rezygnacji albo zmiany samego marzenia, zgodnie z persisted state i committed causes.

Motywacje mogą dotyczyć konkretnych ludzi, organizacji i świata: ochrony, przyjaźni, zemsty, zdobycia uznania, władzy, wiedzy, bogactwa, odkrycia, przetrwania, założenia rodziny, reformy państwa, przywrócenia osoby/bytu itd. World Pack może definiować własne typed motivation domains bez tworzenia równoległego Decision/Living World engine.

Organizacje/państwa/armie mogą posiadać `INSTITUTIONAL AGENDA / STRATEGIC DRIVES`, ale nie są one automatycznie identyczne z prywatnymi motywacjami każdego członka. Wewnętrzne frakcje, konflikty interesów, succession i zdrady pozostają możliwe.

Dla `ACTIVE_PLAYER_CHARACTER` Motivational Core może przechowywać zadeklarowane przez gracza values/desires/dreams i wyliczać presje/opcje, ale nie może autonomicznie generować ani commitować dobrowolnej decyzji PC. `ACTIVE_PLAYER_CHARACTER CONTROL AUTHORITY = USER ONLY` pozostaje nadrzędnym invariantem. Po legalnym relinquish/retirement former PC może używać tego samego motivational/decision/Living World pipeline jako autonomiczny World Actor.

### 15.3 Information ecology, opportunities i consequence propagation
Living World reaguje nie tylko na objective FACT, ale na epistemiczny obraz aktorów. Legalna pętla może mieć postać:
`FACT -> observation -> information transmission -> belief/estimate -> decision -> action -> new FACT`.

Informacja podróżuje zgodnie z kanałami i prędkością świata: rozmowa, posłaniec, dokument, raport, sieć handlowa/wywiadowcza, media, radio/internet, magia/telepatia lub inne World Pack-defined channels. Dwa regiony mogą przez długi czas posiadać różne obrazy tej samej sytuacji.

`Opportunity/Threat detection` może tworzyć kandydatów do działania z połączenia goals + knowledge + current situation. Opportunity nie narzuca decyzji; jedynie staje się wejściem Decision Engine.

Konsekwencje propagują się przez istniejące relacje i zależności. Śmierć, awans, bankructwo, odkrycie, wojna lub decyzja polityczna może wpływać na rodzinę, współpracowników, długi, ownership, supply chains, organizations, goals i inne World Processes. Skutek nie jest ograniczony do aktora stojącego aktualnie obok gracza.

Collective phenomena mogą emergować z wielu legalnych mikro/makro procesów zamiast być losowym eventem: inflacja, migracja, niedobór, boom gospodarczy, bunt, epidemia, przestępczość, urban growth, zmiany kulturowe lub wojna. Domena zachowuje właściwe conservation/invariants i causal provenance.

### 15.4 LOD i multi-rate simulation
- `LOD0` — scena szczegółowa;
- `LOD1` — lokalny region;
- `LOD2` — organizacje/państwa strategicznie;
- `LOD3` — odległy świat jako agregaty/trendy/pressures.

Scena może aktualizować się per action/turn, region per hour/day, strategic systems per day/week, wolne procesy per month/season/year. Nie symulujemy każdego bytu co turę.

Agregowane population/crowd mogą zostać później materializowane w szczegół zgodny z już committed strategiczną historią. Brak historycznej provenance pozostaje unknown.

### 15.5 Causality, conservation i informacja
Wojny, gospodarka, projects, armies, population, resources i organizations pozostawiają spójne skutki. Domena stosuje właściwe conservation/invariant validation.

Istotne background changes generują Event/Causal history; mikroaktywność może pozostać agregowana.

Background FACT nie staje się automatycznie PLAYER/NPC KNOWLEDGE. Informacja propaguje się legalnymi kanałami i z world-specific możliwościami/prędkością komunikacji.

Quest/opportunity może wynikać z rzeczywistego world state/process zamiast z arbitralnego random contentu.

`WORLD SIMULATION = WHAT ACTUALLY DEVELOPS`.
`DIRECTOR = WHAT DESERVES ATTENTION`.

Director może podnosić relevance albo proponować future candidates; nie tworzy committed wojny, kryzysu czy śmierci bez legalnego causal/domain basis.

### 15.6 Living World evolution i obowiązek dokumentacji
Living World jest świadomie projektowany jako system rozszerzalny. Można go dalej ulepszać, jeżeli nowe mechanizmy zwiększają wiarygodność, skalę, emergencję, różnorodność lub jakość symulacji bez naruszania istniejących authority/invariants.

Każde materialne ulepszenie Living World MUSI zostać udokumentowane przed/razem z canonical acceptance. Dokumentacja ma opisać co najmniej:
- problem/use case i zakres nowego mechanizmu;
- ownership/source of truth i mutation authority;
- nowe/zmienione invariants;
- relacje z Knowledge, Memory, Personality, Motivation, Decision, Scheduler, Economy/World domains, Director i Player Agency;
- deterministic vs controlled-random semantics oraz seed/provenance, jeżeli dotyczy;
- LOD/performance/budget implications;
- save/load/snapshot/replay/branching/migration compatibility;
- failure/recovery behavior;
- test matrix, w tym adversarial/regression cases;
- wpływ na World Pack compatibility i extension surface.

`LIVING WORLD IMPROVEMENT WITHOUT DOCUMENTED SEMANTIC CONTRACT = NOT CANONICAL`.

Nie wolno „ulepszać” świata przez ukryte AI behavior, prompt-only semantics, nieudokumentowane heurystyki zmieniające authority albo przez generowanie historii bez committed causal evidence. Historia ewolucji Living World powinna być zachowywana w aktualnej architekturze/roadmapie oraz odpowiednich acceptance/audit/history records zgodnie z project protocol.

### 15.7 Persistent World i Character Succession — CANONICAL TARGET
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

### 20.1 RPG OS LAB Bridge — Etapy 1–3

Wariant `labDebug` wystawia lokalny socket `localabstract:rpgos_lab_bridge`, dostępny komputerowi wyłącznie przez jawny forwarding ADB i protokół `RPGOS_LAB_V1`. Bridge jest narzędziem laboratoryjnym, nie częścią produktu, nową warstwą authority ani alternatywnym silnikiem. Initializer, socket, panel diagnostyczny, `LAB_CODEX` i jego przypisania muszą być nieobecne w wariancie `release`.

Nienaruszalne zasady:

- Core pozostaje jedynym źródłem prawdy. Bridge nie rozstrzyga mechaniki, nie tworzy FACT i nie zapisuje arbitralnie do canonical SQL;
- każda komenda gameplay wywołuje te same application/Core ports co prawdziwy interfejs i przechodzi normalny planner, retrieval, AI, walidację, mechanikę i `TurnTransaction`;
- odczyty ContextBundle, AI/Bekko/Director i failure bundle są ograniczone do aktywnej kampanii oraz legalnego audience/purpose/as-of; posiadanie ADB nie znosi polityk wiedzy ani prywatności;
- AI zawsze zwraca kandydata. Bielik, generatywny GGUF, OpenRouter i `LAB_CODEX` używają tych samych produkcyjnych kodeków i walidatorów;
- awaria narracji po commicie używa recovery i nie może ponownie wykonać mechaniki ani commitu;
- trace, fixtures, indeks Bekko, sidecar Directora i failure bundle nie należą do canonical save/hash. Fixture jest weryfikowalnym wskaźnikiem istniejącej kampanii, nie drugim formatem save;
- każdy błąd pozostaje typed failure. Bridge nie może maskować regresji narracją awaryjną ani niejawnym sukcesem;
- testy wykonuje się na dedykowanym urządzeniu/emulatorze i kampaniach laboratoryjnych. Artefakty mogą zawierać legalny kontekst i nie są automatycznie publikowane.

Etap 1 ustanawia izolowany transport i pojedynczą pionową ścieżkę: health/capabilities, kampanie, stan postaci i tury, ContextBundle, commit/fingerprint, pojedynczą akcję, kreator postaci, stan AI, trace, Bekko oraz wybór lokalnego modelu. Już ta ścieżka korzysta z produkcyjnych portów i nie posiada własnych writerów mechaniki.

Etap 2 dodaje pełny snapshot pipeline'u, sekwencje do 100 realnych akcji, scenariusze walki, ostatnią wymianę AI, restart-safe recovery, weryfikowalne fixtures, runtime/memory diagnostics oraz hostowe screenshoty i failure bundles. Sekwencja może zatrzymać się przy pierwszym wyniku innym niż `NARRATED`, pozostawiając dowód i nie fałszując wyniku.

Etap 3 rejestruje wyłącznie w `labDebug` rozszerzenie `LAB_CODEX`, dostępne zarówno dla UI, jak i Bridge'a. Supervisor utrzymuje heartbeat co 5 sekund; po 15 sekundach provider jest niedostępny. Osobne kolejki MG i Directora uruchamiają bezstanowe `codex exec --ephemeral` w pustym katalogu, z pojedynczym autoryzowanym payloadem i istniejącym schematem odpowiedzi. Provider ma rodzaj `CLOUD` i podlega wszystkim zgodom prywatności. Jawny PIN bez hosta kończy się typed failure; AUTO może użyć istniejącego fallbacku.

Phase65 Director pracuje asynchronicznie po otwarciu kampanii, zatwierdzeniu postaci, co 10 commitów i po legalnych triggerach. Zadania i kandydaci są trwałym per-campaign `CACHE/REBUILDABLE` sidecarem. Do `GM_PROPOSAL` trafia tylko budżetowana `DirectorGuidanceEnvelope`, której supporting UID-y są ponownie dozwolone także w kontekście bieżącej tury. Wskazówka nie jest FACT, narracją ani mutation authority i nie może samodzielnie wykonać commitu.

Pełna architektura, lista komend, procedura instalacji, przykłady dla kampanii/postaci/Bekko/Codex/Directora, recovery, fixtures, failure bundle oraz diagnostyka znajdują się w `docs/development/RPG_OS_LAB_BRIDGE.md`. Raporty etapowe pozostają w `docs/development/RPG_OS_LAB_BRIDGE_STAGE2.md` i `docs/development/RPG_OS_LAB_BRIDGE_STAGE3.md`. Machine-readable źródłem aktualnej listy komend jest zawsze `GET_CAPABILITIES` działającego APK.

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
