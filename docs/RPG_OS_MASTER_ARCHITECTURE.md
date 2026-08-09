# RPG OS — KANONICZNA INSTRUKCJA ARCHITEKTURY I ROZWOJU PROJEKTU

Status: MASTER / CANONICAL

Ten dokument jest nadrzędną instrukcją architektoniczną dla dalszego rozwoju RPG OS. Łączy dotychczasową architekturę GM Engine, Player Domain i zasady integralności systemu.

## 0. Priorytet źródeł
Jeżeli wcześniejszy plan, TODO, komentarz lub rozmowa jest sprzeczna z tym dokumentem, obowiązuje kolejno:
1. rzeczywisty aktualny stan repozytorium i działające dane kampanii,
2. najnowsza jawna decyzja użytkownika,
3. ten dokument,
4. starsze dokumenty/plany/TODO.

Nie implementuj architektury z pamięci. Najpierw sprawdź repozytorium.

## 1. Misja
RPG OS jest uniwersalnym systemem operacyjnym dla bardzo długich kampanii RPG sterowanych przez AI: setki tysięcy tur, miliony eventów i słów, lata rozgrywki, bez utraty stanu, historii, progresji, przedmiotów, pieniędzy, własności, wiedzy NPC, chronologii i campaign divergence.

Core jest niezależny od uniwersum. Naruto, Bleach i kolejne światy są World Packami.

AI NIE jest bazą danych, pamięcią kampanii, kalkulatorem mechaniki ani źródłem prawdy. AI jest Mistrzem Gry korzystającym z kontrolowanego świata RPG OS.

## 2. Model prawdy
Każda trwała informacja rozróżnia co najmniej:
- FACT — obiektywna prawda kampanii,
- BELIEF — przekonanie konkretnego aktora,
- NARRATIVE — informacja przedstawiona graczowi.

NARRATIVE nigdy automatycznie nie staje się FACT. AI OUTPUT != COMMITTED REALITY.

## 3. Sześć warstw
1. SOURCE OF TRUTH — canon, schemas, stable UID, provenance, World Pack rules.
2. CAMPAIGN STATE — player, NPC, inventory, economy, relations, missions, locations, world.
3. CAMPAIGN INTELLIGENCE — events, memories, beliefs, causal graph, ledgers, promises, chronicle, snapshots.
4. SIMULATION / RULE ENGINE — player, combat, progression, economy, projects, travel, time, NPC, world.
5. CONTEXT & DIRECTOR — retrieval, knowledge/temporal filtering, context budget, pacing, anti-repetition.
6. AI GAME MASTER — narration, dialogue, interpretation, important NPC decisions, presentation.

Kierunek zależności: prawda -> stan -> historia/inteligencja -> mechanika -> kontekst -> narracja.

## 4. Canon + divergence
CURRENT CAMPAIGN REALITY = WORLD CANON + CAMPAIGN DIVERGENCES + COMMITTED CAMPAIGN STATE.

Po zmianie kanonu przez kampanię system nie może automatycznie przywracać oryginalnej historii.

## 5. Stable UID i provenance
Każdy trwały obiekt ma stabilny UID. Nazwa jest etykietą, UID to tożsamość.

Ważne fakty/zmiany przechowują provenance, m.in. sourceType, sourceId, createdTurn, createdEvent, confidence, canonStatus, verified, actorUid, method, engineVersion.

## 6. Unified Repository
Cały system widzi jeden logiczny CampaignRepository nad wyspecjalizowanymi repozytoriami: Canon, State, Player, NPC, Event, Memory, Knowledge, Timeline, Snapshot, Economy, Inventory, Ownership, Relationship, Project, Chronicle.

GM/AI nie manipuluje przypadkowymi tabelami SQLite bezpośrednio.

Fizycznie dane mogą być kiedyś rozdzielone na WORLD.DB, CAMPAIGN.DB, EVENTS.DB, MEMORY.DB, VECTOR INDEX, SNAPSHOTS i CONTENT. Podział fizyczny jest decyzją implementacyjną; podział logiczny jest wymaganiem.

## 7. Immutable history + mutable working state
Znacząca historia jest append-only. Working State jest zoptymalizowanym bieżącym stanem. Historia odpowiada „jak do tego doszło?”, working state „jak jest teraz?”.

## 8. Event Store i Causal Graph
Istotne zmiany generują eventy z UID, turnId, campaignTime, actor/target/location, cause, old/new state, source, provenance i metadata. Nie zapisujemy każdego mikro-ruchu.

Eventy mogą mieć relacje caused/enabled/triggered/prevented. System musi wyjaśniać przyczynowość świata.

## 9. Jedna legalna droga zmiany prawdy — GLOBAL INVARIANT
Wiele systemów może proponować zmianę, ale tylko jedna droga może uczynić ją prawdą:

PROPOSAL -> DOMAIN/RULE RESOLUTION -> CHANGE SET -> VALIDATION -> TRANSACTION -> EVENTS + LEDGERS + AUTHORITATIVE STATE -> COMMIT -> COMMITTED REALITY.

Zabronione są boczne ścieżki typu AI/UI/Progression/TimeSkip/WorldSimulation/Economy/Memory/Chronicle/Snapshot -> bezpośrednia zmiana authoritative state.

Jeżeli nowy system wymaga obejścia tej ścieżki, należy rozszerzyć architekturę transakcji, a nie tworzyć wyjątek.

## 10. Turn Transaction
Każda tura jest atomowa. Krytyczny błąd powoduje ROLLBACK. Narracja, eventy, state i ledgers muszą odpowiadać tej samej zatwierdzonej rzeczywistości. COMMIT jest granicą prawdy.

## 11. Idempotency / double-commit protection
Każda commitowalna operacja posiada stabilny transactionUid/commandUid/turnUid. Ponowne wykonanie już zatwierdzonej operacji zwraca ALREADY_COMMITTED zamiast powtarzać skutki.

Jeżeli mechanika używa losowości, zapisuje wynik lub RNG seed potrzebny do replay.

## 12. Crash recovery
Po przerwaniu procesu aplikacja wraca do LAST VALID COMMIT, odrzuca/rollbackuje niekompletną transakcję, weryfikuje integralność i odbudowuje dane derived/cache. Niepełna tura nie może częściowo zmienić świata.

## 13. Authoritative vs Derived vs Cache vs Presentation
Każdy trwały typ danych klasyfikujemy jako:
- AUTHORITATIVE — utrata oznacza utratę informacji kampanii,
- DERIVED — odbudowywalne z authoritative,
- CACHE/INDEX — tylko wydajność,
- PRESENTATION — widok dla użytkownika/AI.

AUTHORITATIVE -> DERIVED -> CACHE/PRESENTATION. Nie odwrotnie bez jawnej zwalidowanej komendy domenowej.

CharacterPanelSnapshot i Chronicle rendering nie są źródłami prawdy.

## 14. Player Domain
PlayerDomainEngine jest jedynym punktem wejścia dla autorytatywnych zmian gracza. AI nie zmienia bezpośrednio statystyk, zasobów, pieniędzy, inventory, umiejętności, technik, własności ani trwałych cech.

Player/World Action -> PlayerCommand -> PlayerDomainEngine -> Rule Pipeline -> WorldRuleProvider -> Mechanics -> InvariantValidator -> PlayerChangeSet -> TurnTransaction -> COMMIT -> PlayerSnapshotBuilder.

## 15. Player Commands i PlayerChangeSet
Używamy jawnych komend domenowych, np. Train, LearnSkill, CreateTechnique, UseTechnique, Purchase, Sell, Equip, GainReward, ApplyInjury, Heal, AdvanceTime, Start/Progress/CompleteProject, TransferAsset.

PlayerChangeSet może zawierać stat/resource/skill/technique/innate/inventory/equipment/money/asset/ownership/condition/runtime changes, events, ledger entries, provenance i warnings. Jest propozycją do czasu COMMIT.

## 16. Player State
Trzy poziomy:
- PERSISTENT — base stats, learned skills/techniques, permanent traits, assets, bloodlines/evolution itd.
- DERIVED — effective values, max resources, regeneration, net worth, combat-derived values.
- RUNTIME — HP, fatigue, cooldowns, buffs/debuffs, bieżące rany/stance.

## 17. Dynamic Stats & Resources
Core nie hardcoduje statystyk konkretnego świata. StatDefinition/PlayerStat i ResourceDefinition/PlayerResource są rozszerzalne przez World Pack.

DerivedValueResolver oblicza effective value z base + permanent + equipment + injury + temporary modifiers bez niszczenia bazowej progresji.

## 18. Talent i Potential
Talent opisuje łatwość/efektywność nauki domeny; Potential długoterminową skalę rozwoju. Są oddzielne od aktualnego poziomu i od siebie. World Pack może dodawać własne domeny talentu/potencjału.

## 19. Progression
Każdy trwały wzrost ma przyczynę i wpis Progression Ledger: source, duration, intensity, difficulty, mentor, environment, method, talent, potential, fatigue, injury, currentLevel, quality, novelty, adaptation, diminishingReturns, modifiers, result.

AI opisuje trening; ProgressionEngine wylicza rezultat. Diminishing returns zapobiega eksplozji statystyk. Time != power bez uwzględnienia jakości, trudności, adaptacji i potencjału.

## 20. No-Retrogression / Player Invariants
Trwałe osiągnięcia nie znikają bez jawnej legalnej przyczyny. Preferujemy temporary modifiers zamiast niszczenia trwałego mastery przy przejściowych ograniczeniach.

Validator pilnuje m.in. stat/mastery regression, inventory, unique items, ownership, equipment, money conservation/debt rules, resources, technique legality, dead-character rules, progression i World Pack rules.

## 21. Skills, Techniques, Innate Abilities
Skill = ogólna kompetencja. Technique = konkretna wykonywalna metoda. Innate/racial/bloodline/evolution/transformations = osobna kategoria. Wszystkie posiadają trwałą historię i wymagania; WorldRuleProvider określa zachowanie specyficzne dla świata.

## 22. DevelopmentProject
Nowa technika/wynalazek nie powstaje przez arbitralne nadanie AI. Wspólny DevelopmentProject obsługuje technique creation/modification, skill development, research, crafting, body adaptation, energy control, infrastructure i world research.

IDEA -> REQUIREMENTS -> PROTOTYPE -> TRAINING/EXPERIMENTS -> FAILURES/IMPROVEMENTS -> MILESTONES -> STABILIZATION -> PROJECT COMPLETED -> STABLE UID -> NORMAL PROGRESSION.

## 23. Inventory, Equipment, Ownership
Inventory != Equipment. Unique items mają własne UID; stackable commodity może używać quantity. Lokalizacja przedmiotu nie oznacza własności.

OwnershipRecord przechowuje owner, asset, ownershipType/share, validFrom/Until i sourceEvent. Obsługuje przedmioty, nieruchomości, firmy, udziały itd.

## 24. Economy, income, expenses, assets
Pieniądze działają księgowo. FinancialTransaction przechowuje from/to/currency/amount/reason/event/time/provenance. Balance może być cache, ledger wyjaśnia historię.

System rozróżnia cash, receivables, debts, property, land, business, laboratory, workshop, vehicle, shares, rare assets i liabilities. Personal wealth != organization wealth. Net worth = assets - liabilities.

## 25. CharacterPanelSnapshot v2
CharacterPanelSnapshot jest wersjonowaną, odbudowywalną projekcją:
Authoritative Player State + Derived Values + Runtime State + Ledger Summaries.

Sekcje mogą obejmować identity, stats, resources, talents, potential, skills, techniques, innate abilities, progression, inventory, equipment, economy, assets, relationships, reputation, organizations, goals, projects, missions, conditions.

PlayerSnapshotBuilder jest jedynym komponentem składającym ten read model. Profile: FULL, COMBAT, PROGRESSION, ECONOMY, SOCIAL, GM_CONTEXT.

## 26. WorldRuleProvider / Core vs World Pack
Nie tworzymy pełnych NarutoPlayerEngine/BleachPlayerEngine. Core posiada mechanizmy uniwersalne; World Pack dostarcza canon, definitions i reguły świata: chakra/reiatsu, ranks, techniques, bloodlines/races, evolutions, organizations, locations, timeline itd.

World Pack nie kopiuje infrastruktury Core: transactions, events, memory, economy framework, snapshots, retrieval itd.

## 27. NPC Knowledge, Brain i Decision Engine
Każdy istotny NPC ma własną wiedzę: known/suspected/false beliefs/rumours/secrets/observations/inferences/organization knowledge. Informacja wymaga legalnej ścieżki acquisition: observation, communication, research, inference, organization, espionage lub World Pack mechanic.

Ważny NPC ma personality, goals, fears, values, loyalties, relationships, resources, abilities, location, current task, long-term plan i constraints.

Małe decyzje rozwiązujemy tanio; silny model tylko dla ważnych, złożonych decyzji.

## 28. Temporal Engine, Scheduler, Time Skip
Stan historyczny może mieć validFrom/validUntil. Retriever musi odpowiadać „co było prawdą wtedy?”, nie używać automatycznie stanu obecnego.

Scheduler planuje przyszłe punkty oceny/deadlines, nie gwarantuje z góry wyniku.

Time Skip: advance time -> scheduled events -> active/passive player progression -> NPC progression -> age/family -> projects -> economy -> travel -> wars/politics -> world simulation -> relationship consequences -> memory consolidation -> snapshot/state update.

## 29. World Simulation LOD
LOD0 scena pełna; LOD1 region szczegółowy; LOD2 organizacje/państwa strategicznie; LOD3 reszta świata jako ważne trendy/eventy. LOD dynamicznie rośnie dla elementów, które stają się ważne.

Background world nie jest generatorem losowego szumu. Eventy wynikają ze stanu, celów, zasobów, procesów, causal history, World Pack rules i kontrolowanej losowości.

## 30. Memory
Tylko trzy główne poziomy: Working, Episodic, Semantic Campaign Memory. Rozdziały/tomy należą do Chronicle.

Consolidation: raw events -> importance -> deduplication -> conflict detection -> episodic -> semantic conclusions. Zakazane recursive summary-of-summary. Event history pozostaje historycznym źródłem.

## 31. Retrieval, Intent, Turn Planner, Context
Retrieval łączy według potrzeb SQL + Knowledge Graph + Vector Search + Temporal Filter + Knowledge Filter. Jest iteracyjny i bounded.

Intent Parser rozpoznaje strukturę intencji, nie rozstrzyga mechaniki. Turn Planner wybiera tylko potrzebne repozytoria, mechaniki, NPC, canon/history i filtry.

Context Budget jest dynamiczny. Context Bundle może zawierać system rules, style, time/location, player, visible world, NPC state/knowledge, threads, history, canon, simulation results, action i output contract.

## 32. AI Adapter i Structured GM Output
AiProvider jest wymienny. Lokalny kod obsługuje lookup/mechanikę/filtry, mniejsze modele proste zadania, silny GM ważną narrację/rozumowanie.

AI zwraca strukturę: narrative, proposedEvents, state/knowledge/relationship changes, memory writes, chronicle entries, threads, timeAdvance, npcIntentions, warnings. AI proponuje; system waliduje; transaction commit zatwierdza.

## 33. Validation, Counterfactual Guard, Repair
Consistency Validator sprawdza canon/divergence, timeline, dead NPC, techniques, NPC knowledge leakage, stats/resources, inventory/ownership/location, causality, projects, World Pack legality i unsupported history.

Counterfactual Guard odrzuca retrospektywnie wymyśloną historię bez eventu. Repair Pass naprawia lokalny błąd zamiast regenerować całą turę, jeśli to możliwe.

Priorytet konfliktów: authoritative current state -> committed event history -> authoritative player/domain state -> campaign divergence -> canon -> persistent memory -> recent committed narrative -> AI inference.

## 34. Director, promises, pacing, anti-repetition, style
Director steruje uwagą/pacingiem, nie prawami świata. Narrative Promise Ledger przechowuje otwarte mysteries/rivalries/threats/promises/projects/tensions. Pacing metrics wykrywają stagnację. Anti-Repetition ogranicza monotonię, ale spójność > różnorodność.

Narrative Style Profile jest trwałym profilem kampanii, nie rekonstruowanym każdorazowo z historii.

## 35. Chronicle
Chronicle jest czytelną projekcją głównie committed structured events. Nie jest źródłem prawdy dla silnika.

## 36. Snapshots, retention, Save/Branching
Load = latest valid snapshot + events after snapshot. Snapshot jest optymalizacją, nie historią.

Na Androidzie zachowujemy maksymalnie 6 najnowszych AUTOMATYCZNYCH snapshotów kampanii. Retencja nie usuwa manual backups, manual exports, pre-restore safety backups ani user-pinned saves. Event history nie jest kasowana razem ze snapshotem.

Save wskazuje snapshotId/eventId/turnId/branchId. Branching współdzieli historię do punktu rozgałęzienia i ma osobne eventy później zamiast kopiować gigantyczną bazę.

## 37. Schema Versioning, migrations, backups
Wersjonujemy engine/worldPack/campaign/player/memory/event/snapshot/economy schemas. Aktualizacja wymaga forward migration z ochroną starych kampanii. Legacy provenance jest oznaczane jako legacy/migrated zamiast wymyślania historii.

Docelowo: autosave, snapshots, local/manual backup/export, optional cloud, pre-migration i pre-restore backup. Content/World Pack update nie nadpisuje campaign divergence.

## 38. Debug, observability, replay
Developer mode mierzy retrieval/context/AI/validation/commit latency, counts, repair/event/memory writes i wynik transakcji.

Replay Debugger pokazuje input, normalized intent, plan, retrieval, context, raw structured AI proposal, simulation, ChangeSets, validators, repair, events, state/ledgers i commit.

## 39. Long-campaign tests
Testujemy docelowo 10k/100k turns, 1M events, 5M+ words. Kontrolujemy fact recall, false memory, contradiction, temporal/causal accuracy, knowledge leakage, retrieval latency, state/economic/inventory integrity i altered canon.

## 40. Performance i AI cost
Android jest głównym targetem. Typowa tura nie może wymagać liniowego skanowania pełnej historii. Używamy working state, indexes, bounded retrieval, snapshots, LOD, cache, derived values i semantic retrieval tylko gdy potrzebne.

Najdroższy model tylko tam, gdzie daje realną wartość. Nie używamy LLM do sumowania pieniędzy, deterministic rules, prostych SQLite lookupów ani prostych decyzji NPC.

## 41. Finalny pipeline tury
PLAYER INPUT -> Input Normalizer -> Intent Parser -> Turn Planner -> Initial Retrieval -> Missing Context -> Follow-up Retrieval -> Knowledge Filter -> Temporal Filter -> Rule/Simulation Precheck -> PlayerDomain/Other Mechanics -> Director Context -> Context Budget -> Context Bundle -> AI GM -> Structured Proposal -> Mechanics Resolution -> ChangeSets -> Consistency/Invariant/Counterfactual Validation -> Repair -> Transaction(events/state/knowledge/relations/ledgers/ownership/projects/threads/chronicle/memory) -> COMMIT -> PlayerSnapshotBuilder -> CharacterPanelSnapshot -> committed narrative -> deferred low-priority consolidation -> snapshot when required.

Deferred/background operacje nie mogą poza kontrolowaną transakcją zmieniać authoritative state.

## 42. Repository-first development protocol
Przed kodem: przeczytaj ten dokument i roadmapę, sprawdź master, recent commits, istniejące klasy/interfejsy/tabele/migracje/testy/build/CI. Oznacz funkcję jako COMPLETE/PARTIAL/MISSING/BLOCKED. Nie twórz równoległego systemu tylko dlatego, że istniejący ma inną nazwę.

Wybieraj najwcześniejszą brakującą zależność, nie kolejny numer na ślepo. Integracja przed refactorem.

## 43. Safe changes, migrations, tests
Zmiany są małe, kontrolowane, migration-safe i chronią istniejące kampanie. Nie cofaj master, nie resetuj GitHuba, nie force-pushuj historii bez jawnego polecenia, nie zmieniaj niepowiązanego kodu.

Po logicznej zmianie: implementation -> local validation -> tests -> commit -> GitHub Actions -> build -> integrity verification. Zepsuty baseline naprawiamy przed dokładaniem dużych zmian.

Kluczowe testy: save-close-load, snapshot-replay, migration-old-campaign, rollback, idempotency/double commit, crash recovery, no-retrogression, money conservation, ownership integrity, NPC knowledge isolation, temporal lookup.

## 44. Frontend — ACTIVE DEVELOPMENT / STYLE PRESERVATION
Frontend nie jest już zamrożony. Może być rozwijany i modyfikowany równolegle z kolejnymi fazami, gdy zmiana jest potrzebna do prawidłowego udostępnienia nowej funkcjonalności, poprawy użyteczności albo integracji nowych kontraktów danych.

Obowiązuje jednak zachowanie aktualnego, zaakceptowanego stylu wizualnego aplikacji. Nowe ekrany, komponenty, panele, animacje i rozszerzenia istniejących widoków muszą być projektowane jako naturalna kontynuacja obecnego RPG OS, a nie jako niezależny redesign.

Dozwolone są m.in.:
- nowe ekrany i panele wymagane przez rozwijane systemy,
- rozwój Character Panel wraz z CharacterPanelSnapshot,
- rozszerzenia nawigacji i interakcji,
- poprawki UX i czytelności,
- animacje i efekty zgodne z istniejącym językiem wizualnym,
- refaktor UI, jeżeli jest potrzebny do integracji funkcjonalności lub utrzymania spójności.

Nie wykonujemy przypadkowej zmiany całej estetyki aplikacji ani globalnego redesignu bez osobnej jawnej decyzji użytkownika. Zachowujemy charakter obecnego interfejsu, jego kierunek artystyczny i spójność pomiędzy starymi i nowymi elementami.

Frontend nadal podlega zasadom małych, kontrolowanych zmian, testowania oraz ochrony działających funkcji. Backendowa zmiana nie jest automatycznym uzasadnieniem do przebudowy niepowiązanych ekranów.

CharacterPanelSnapshot v2 pozostaje kontraktem danych i mechaniki, ale jego rozwój może być teraz równolegle odzwierciedlany w rzeczywistym Character Panel UI przy zachowaniu aktualnego stylu aplikacji.

## 45. Priorytet projektowy
1. Data integrity
2. Campaign continuity
3. Correct mechanics
4. Compatibility/recoverability
5. Performance
6. Retrieval quality
7. Narrative intelligence
8. Additional content
9. Visual polish

## 46. Definicja DONE
Etap jest DONE dopiero gdy implementation exists + integrated + persistence works + migration safe where needed + tests cover core invariants + build succeeds + existing campaign compatibility preserved + brak nierozwiązanego konfliktu legacy/new system.

Sama klasa/tabela/dokument nie oznacza DONE.

## 47. Raport po aktualizacji
Raportuj: stan początkowy, co istniało, brakującą zależność, dokładną zmianę i pliki, migracje, ochronę kampanii, testy, build, GitHub Actions, status frontendu i następny najwcześniejszy brak.

Nie raportuj funkcji, której faktycznie nie zaimplementowano.

## 48. Protokół nowej sesji
1. Przeczytaj `docs/RPG_OS_MASTER_ARCHITECTURE.md`.
2. Przeczytaj `docs/RPG_OS_IMPLEMENTATION_ROADMAP.md`.
3. Przeczytaj `docs/PARALLEL_WORK_COORDINATION.md` i sprawdź ACTIVE WORK REGISTER.
4. Sprawdź master/recent commits/build/CI/migrations/tests.
5. Audytuj istniejącą implementację.
6. Aktualizuj checklistę COMPLETE/PARTIAL/MISSING/BLOCKED na podstawie kodu i testów, nie pamięci.
7. Znajdź najwcześniejszą brakującą zależność.
8. Jeżeli sesja jest workerem, potwierdź własny WORK ITEM, allowedScope, forbiddenScope, rezerwacje oraz baseline przed zmianą kodu.
9. Wykonaj najmniejszą bezpieczną zmianę.
10. Przed zapisem ponownie sprawdź aktualny master i wersję modyfikowanego pliku.
11. Test/build/integrity/commit/CI.
12. Zaktualizuj roadmapę/checklistę tylko jeśli dowody potwierdzają status i nie istnieje otwarty równoległy WORK ITEM wymagany dla tej samej fazy.
13. Frontend może być rozwijany, gdy służy aktualnej funkcjonalności; każda zmiana UI musi zachować zaakceptowany styl aplikacji i nie może wprowadzać niepowiązanego globalnego redesignu.

## 49. Równoległa praca wielu sesji — CANONICAL
RPG OS dopuszcza równoległą pracę wielu chatów/sesji, ale wyłącznie w modelu kontrolowanym.

Jedna sesja pełni rolę COORDINATOR, a pozostałe są WORKERS realizującymi jawnie przydzielone WORK ITEMS. Każde zadanie posiada stabilny identyfikator, zakres, baseline, zależności, rezerwację plików/subsystemów, status, wynikowy commit i status CI.

Dwa aktywne zadania nie mogą jednocześnie modyfikować tego samego authoritative subsystemu lub tych samych plików bez jawnej decyzji koordynatora. Worker, który wykryje konflikt zakresu, zmianę kontraktu zależności albo konieczność wejścia w forbiddenScope, zatrzymuje implementację i oznacza zadanie BLOCKED zamiast wykonywać agresywny merge/reset.

`master` pozostaje wspólnym technicznym źródłem prawdy. Każdy worker sprawdza jego aktualność przed rozpoczęciem pracy oraz ponownie przed zapisem. COMPLETE pojedynczego WORK ITEM nie oznacza automatycznie COMPLETE całej fazy.

Globalne statusy roadmapy są podczas pracy równoległej aktualizowane dopiero po audycie integracyjnym i sprawdzeniu wszystkich wymaganych WORK ITEMS.

Szczegółowy obowiązujący protokół, role, format WORK ITEM, rezerwacje, raportowanie, blokady oraz ACTIVE WORK REGISTER definiuje `docs/PARALLEL_WORK_COORDINATION.md`.

Priorytet przy konflikcie: DATA INTEGRITY > CAMPAIGN CONTINUITY > CORRECT ARCHITECTURE > SAFE INTEGRATION > PARALLEL SPEED.

## 50. Ostateczny cel
RPG OS ma działać jak trwały system świata, nie chatbot z długim promptem.

DATABASE/STATE/EVENTS = REALITY
RULE ENGINE = WHAT CAN HAPPEN
KNOWLEDGE+TEMPORAL = WHO KNOWS WHAT AND WHEN
MEMORY+RETRIEVAL = WHAT MATTERS NOW
DIRECTOR = WHAT DESERVES ATTENTION
AI GM = HOW IT IS INTERPRETED/PRESENTED
VALIDATORS+TRANSACTION = WHAT BECOMES TRUE

Po milionach słów system ma odpowiadać: co/kiedy/gdzie/kto/dlaczego/co zmieniło/kto wie/czy nadal prawda/skąd to wiemy; oraz wyjaśniać statystyki, umiejętności, techniki, przedmioty, pieniądze, własność, relacje, wojny i altered canon bez czytania całej kampanii od początku.

Najpierw prawda. Potem integralność stanu. Potem mechanika. Potem pamięć/retrieval. Potem inteligencja świata. Na końcu narracyjna finezja i content.
