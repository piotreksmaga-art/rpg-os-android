# Architektura projektu

Status: MASTER / CANONICAL

Ten dokument jest jedyną kanoniczną architekturą projektu RPG OS. Szczegółowy indeks plików znajduje się w `docs/Mapa plików.md`, a jedyna kanoniczna roadmapa w `docs/Roadmap.md`.

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

**Future AI invariant:** żaden AiProvider, model, runtime inference, KV cache, conversation/session cache ani hardware backend nie jest właścicielem trwałej pamięci kampanii. Model może posiadać wyłącznie transient inference/session memory. Po unloadzie, zmianie modelu, restarcie procesu lub urządzenia potrzebny kontekst musi być odbudowywalny z CampaignRepository + authoritative/event/memory/knowledge systems + retrieval/context pipeline. Zmiana modelu lub runtime nie może wymagać migracji historii kampanii.

## 31. Retrieval, Intent, Turn Planner, Context
Retrieval łączy według potrzeb SQL + Knowledge Graph + Vector Search + Temporal Filter + Knowledge Filter. Jest iteracyjny i bounded.

Intent Parser rozpoznaje strukturę intencji, nie rozstrzyga mechaniki. Turn Planner wybiera tylko potrzebne repozytoria, mechaniki, NPC, canon/history i filtry.

Context Budget jest dynamiczny. Context Bundle może zawierać system rules, style, time/location, player, visible world, NPC state/knowledge, threads, history, canon, simulation results, action i output contract.

**Future model-capability requirement:** Context Budget Manager nie może zakładać stałego okna kontekstu ani stałego output budgetu (np. CTX=8192). Musi otrzymywać provider/model capabilities, w tym co najmniej efektywny context window i output limit/recommended output budget, a Turn Planner powinien móc dobierać tylko potrzebne output capabilities. Actor/action/target z normalized intent pozostają strukturą wejściową; nie wolno polegać wyłącznie na modelu jako parserze semantyki akcji.

## 32. AI Adapter i Structured GM Output
AiProvider jest wymienny. Lokalny kod obsługuje lookup/mechanikę/filtry, mniejsze modele proste zadania, silny GM ważną narrację/rozumowanie.

AI zwraca strukturę: narrative, proposedEvents, state/knowledge/relationship changes, memory writes, chronicle entries, threads, timeAdvance, npcIntentions, warnings. AI proponuje; system waliduje; transaction commit zatwierdza.

### 32.1 Future AI Provider & Native Local Inference Architecture — CANONICAL REQUIREMENTS, IMPLEMENTATION DEFERRED

Poniższe wymagania są kanonicznym kierunkiem przyszłej Phase 48+, ale **nie autoryzują obecnie implementacji Phase 48 i nie zmieniają kolejności roadmapy**.

**Wymienność:** model, inference runtime i hardware backend są trzema niezależnymi osiami. Nie tworzymy architektury typu `BielikEngine`, `PLLuMEngine` lub `GemmaEngine`. Kampania ma pozostać poprawna po zmianie modelu, runtime lub backendu.

Docelowy podział odpowiedzialności:

```text
AI GM orchestration
  -> AiProvider
       -> AiCapabilityContract
       -> ModelProfile
       -> GmToolGateway
       -> LocalInferenceRuntime
            -> runtime implementation (np. LiteRT-LM / ExecuTorch / przyszły runtime)
            -> RuntimeBackendSelector
                 -> CPU / GPU / NPU / AUTO
```

Nazwy konkretnych implementacji runtime są przykładami technologii i nie są wymaganiem, aby Phase 48 hardcodowała dzisiejszego zwycięzcę technologicznego.

**AiProvider** definiuje provider-independent kontrakt semantyczny generacji (`generate`, `generateStream`, `cancel` lub równoważny), przyjmuje kontrolowany `GmRequest/ContextBundle` i zwraca provider-independent wynik/proposal. Nie zna GGUF path, CLI flags, portu localhost, konkretnego GPU API ani biblioteki NPU. Nie posiada raw DB ani mutation authority.

**AiCapabilityContract** deklaruje co najmniej capabilities typu TEXT, STREAMING, STRUCTURED_OUTPUT, TOOL_REQUESTS/TOOLS, CONSTRAINED_DECODING tam gdzie wspierane, maksymalne context/output limits i supported languages; vision/audio pozostają opcjonalnymi capabilities.

**ModelProfile** jest data-driven i zawiera stabilną tożsamość modelu oraz jego family/version/format, context/output capabilities, runtime/backend compatibility, language/capability metadata, storage/RAM/device expectations oraz wersjonowany prompt/semantic contract. Model artifact może być pobierany/usuwany bez usuwania kampanii.

**LocalInferenceRuntime** odpowiada wyłącznie za inference execution: load/unload, generation, streaming, cancel, tokenization/runtime preparation, runtime metrics, memory pressure i backend availability. Nie odpowiada za mechanikę RPG, campaign memory ani committed state.

**RuntimeBackend / RuntimeBackendSelector** rozdziela CPU/GPU/NPU/AUTO od wyboru modelu. Backend wybiera się na podstawie rzeczywistego runtime/model compatibility, urządzenia, dostępnej pamięci i późniejszych policy/performance constraints; Vulkan ani NPU nie są wpisane na stałe do GM core.

**ModelLifecycleController** docelowo kontroluje co najmniej NOT_INSTALLED/AVAILABLE/LOADING/READY/GENERATING/UNLOADING/ERROR lub równoważne stany, checksum/artifact verification, load/unload, cancel, OOM/process-death recovery i brak kampanijnych skutków ubocznych awarii inference. Szczegółowy download/update UX pozostaje decyzją późniejszej implementacji/product layer.

**GmToolGateway** jest allowlisted brokerem. AI może QUERY / REQUEST / PROPOSE, ale nie może dostać bezpośrednich operacji `UPDATE DB`, `COMMIT`, `SET HP`, `GRANT SKILL`, `ADD MONEY` ani raw writable database handle. Tool request wymagający mechaniki wraca do istniejących domen/rules/validators/TurnTransaction. Tool calling jest opcjonalną capability provider/runtime, nie warunkiem dla wszystkich modeli.

**Provider-independent Structured GM Output:** canonical schema nie zależy od Bielika, PLLuM, Gemmy, LiteRT-LM, ExecuTorch ani cloud provider. Adapter provider/runtime może się różnić. Turn Planner może wyznaczyć `requiredOutputCapabilities`, aby mały model nie musiał generować wszystkich sekcji na każdej turze.

### 32.2 Player Agency i Actor/Action/Target — GLOBAL GM INVARIANTS

**VOLITIONAL PLAYER ACTION SOURCE = USER / VALIDATED PLAYER COMMAND ONLY.** AI-GM nie może sam dopisywać graczowi dobrowolnego ruchu, wypowiedzi, ataku, uniku, kontrataku, wyboru celu ani użycia zdolności, których użytkownik nie zadeklarował i których nie wynika legalnie z wcześniej committed command/state. System/mechanika może natomiast narzucić graczowi konsekwencję niezależną od woli, np. knockback, stun, upadek lub utratę przytomności; to nie jest „akcja gracza”.

**ACTOR / ACTION / TARGET preservation:** normalized intent i Turn Plan zachowują strukturalnie co najmniej actor identity, action semantics i target identity/part/ref, jeżeli występują. Model nie jest jedynym parserem semantyki akcji i nie może odwrócić kierunku `PLAYER attacks NPC` na `NPC attacks PLAYER` bez nowego legalnego źródła działania.

Każdy przyszły AiProvider/model używany jako GM musi przejść provider-conformance tests obejmujące co najmniej player agency, actor/action/target direction, NPC knowledge isolation, fact/belief separation, stop point, brak invented abilities/dialogue, brak internal-context leakage oraz brak mutation authority.

### 32.3 TEMP Local GM i R&D

TEMP-GM może pozostać niekanoniczną infrastrukturą testową/semantycznym laboratorium. Termux, localhost bridge, llama.cpp/Vulkan i konkretny model referencyjny są **TEST INFRASTRUCTURE / REFERENCE BASELINE**, nie production architecture.

Dozwolone jest niezależne R&D/benchmarking modeli i runtime'ów (np. Bielik, PLLuM, Gemma, LiteRT-LM, ExecuTorch lub przyszłe odpowiedniki), pod warunkiem: brak canonical integration, brak zmiany acceptance status, brak startu Phase 48 i brak bocznej mutation authority. Wyniki R&D są evidence dla przyszłego repo-first Phase-48 audit.

Publiczne benchmarki nie zastępują własnego **RPG OS LOCAL GM / Provider Conformance benchmarku**. Ocena musi obejmować zarówno semantykę RPG/Polish quality/agency/knowledge/continuity/structured output, jak i model size, load time, TTFT, prefill/decode throughput, peak/steady RAM, battery, temperature/thermal throttling, cancel, OOM/recovery, load/unload/process death oraz sustained multi-turn tests.

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

**Future local-inference profiling requirement:** profilowanie Android/local AI obejmuje co najmniej model storage, load/unload time, TTFT, prefill tok/s, decode tok/s, peak/steady RSS, KV/runtime cache, battery drain, temperature, thermal throttling, cancel latency, OOM recovery, process death/restart i sustained 10–30+ turn workload. Wyniki z innego urządzenia są tylko evidence/reference, nie automatycznie profilem target device.

**Future routing requirement:** późniejsza optymalizacja rozdziela `ModelRouter` (którego modelu użyć) od `RuntimeBackendSelector` (na czym kompatybilny model uruchomić). Ten sam model może używać różnych backendów; różne modele mogą używać tego samego runtime. Routing kosztu/jakości nie może zmienić campaign authority ani durable memory ownership.

## 41. Finalny pipeline tury
PLAYER INPUT -> Input Normalizer -> Intent Parser -> Turn Planner -> Initial Retrieval -> Missing Context -> Follow-up Retrieval -> Knowledge Filter -> Temporal Filter -> Rule/Simulation Precheck -> PlayerDomain/Other Mechanics -> Director Context -> Context Budget -> Context Bundle -> AI GM -> Structured Proposal -> Mechanics Resolution -> ChangeSets -> Consistency/Invariant/Counterfactual Validation -> Repair -> Transaction(events/state/knowledge/relations/ledgers/ownership/projects/threads/chronicle/memory) -> COMMIT -> PlayerSnapshotBuilder -> CharacterPanelSnapshot -> committed narrative -> deferred low-priority consolidation -> snapshot when required.

Deferred/background operacje nie mogą poza kontrolowaną transakcją zmieniać authoritative state.

## 42. Repository-first development protocol
Przed kodem: przeczytaj ten dokument, `docs/Roadmap.md` i `docs/Mapa plików.md`, sprawdź master, recent commits, istniejące klasy/interfejsy/tabele/migracje/testy/build/CI. Oznacz funkcję jako COMPLETE/PARTIAL/MISSING/BLOCKED. Nie twórz równoległego systemu tylko dlatego, że istniejący ma inną nazwę.

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
1. Przeczytaj `docs/Architektura projektu.md`.
2. Przeczytaj `docs/Roadmap.md`.
3. Przeczytaj `docs/Mapa plików.md` i użyj go jako indeksu do aktualnego kodu.
4. Przeczytaj `docs/PARALLEL_WORK_COORDINATION.md` i sprawdź ACTIVE WORK REGISTER.
5. Sprawdź master/recent commits/build/CI/migrations/tests.
6. Audytuj istniejącą implementację.
7. Aktualizuj checklistę COMPLETE/PARTIAL/MISSING/BLOCKED na podstawie kodu i testów, nie pamięci.
8. Znajdź najwcześniejszą brakującą zależność.
9. Jeżeli sesja jest workerem, potwierdź własny WORK ITEM, allowedScope, forbiddenScope, rezerwacje oraz baseline przed zmianą kodu.
10. Wykonaj najmniejszą bezpieczną zmianę.
11. Przed zapisem ponownie sprawdź aktualny master i wersję modyfikowanego pliku.
12. Test/build/integrity/commit/CI.
13. Zaktualizuj roadmapę/checklistę tylko jeśli dowody potwierdzają status i nie istnieje otwarty równoległy WORK ITEM wymagany dla tej samej fazy.
14. Frontend może być rozwijany, gdy służy aktualnej funkcjonalności; każda zmiana UI musi zachować zaakceptowany styl aplikacji i nie może wprowadzać niepowiązanego globalnego redesignu.

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

## 51. Future scope lock po zaakceptowanym Phase 36 — CANONICAL
Poniższe sekcje rozszerzają wyłącznie przyszły zakres projektu od Phase 37 wzwyż oraz rozszerzenia post-roadmap. Nie zmieniają zakresu, semantyki, statusu ani evidence żadnej zaakceptowanej Phase 1–36.

Canonical accepted runtime through Phase 36 pozostaje historycznym runtime acceptance pointem. Późniejsze zmiany dokumentacyjne i przyszłe wymagania nie są retroaktywnie częścią acceptance wcześniejszych faz.

Kolejność implementacyjna nadal wynika z `docs/Roadmap.md`: najpierw najwcześniejsza brakująca zależność, repository-first audit, potem minimalny bezpieczny delta. Future AI, NPC, world-simulation ani World Pack Creator nie są zgodą na pominięcie Phase 37–84.

## 52. Hybrid Local-First AI — przyszły canonical target
Docelowa architektura AI jest **HYBRID LOCAL-FIRST** pod jednym provider-independent kontraktem. Local GM jest podstawowym wykonawcą normalnych tur i po zainstalowaniu zgodnego lokalnego modelu musi wystarczać do kontynuowania kampanii bez Internetu. Chmura jest opcjonalnym wzmacniaczem jakości, a nie drugim RPG OS i nie warunkiem dalszej gry.

Globalny invariant:

```text
LOCAL AI MUST BE SUFFICIENT TO CONTINUE THE CAMPAIGN.
CLOUD AI MAY IMPROVE QUALITY, BUT CLOUD UNAVAILABILITY MUST NEVER BLOCK PLAY.
```

Docelowy podział:

```text
RPG OS CORE
  -> AI orchestration
       -> AiProvider / equivalent provider-independent contract
            +-- Local execution
            |    -> LocalInferenceRuntime
            |    -> RuntimeBackendSelector
            |    -> CPU / GPU / supported NPU / AUTO
            `-- Optional cloud execution
                 -> cloud provider adapter(s)
```

Nie tworzymy osobnych `LocalGameMaster` i `CloudGameMaster` posiadających własną pamięć, mechanikę, prawdę lub transaction path. Provider, model, runtime i hardware backend są wymienne; ich zmiana nie może wymagać migracji kampanii.

### 52.1 Workload, routing i odpowiedzialności
Przyszły routing rozróżnia co najmniej rodzaj pracy od konkretnego providera/modelu. Logiczne workloady mogą obejmować m.in. `TURN_GM`, `DIRECTOR_CONTENT`, `WORLD_RESEARCH`, `LARGE_WORLD_EXPANSION` i bounded validation/repair assistance tam, gdzie właściwa faza na to pozwala.

Normalna tura, dialog, reakcja NPC, scena i podstawowa narracja preferują lokalny execution path. Mocniejszy cloud może być używany oszczędnie do long-horizon planning, arc/quest seeds, faction conflicts, NPC agenda candidates, foreshadowing, anti-repetition/pacing assistance i dużych zadań badawczych, ale jego wynik pozostaje proposal/candidate.

`CloudInvocationPolicy` lub równoważna polityka nie może być sztywnym „co N tur”. Powinna być event-driven, budget-aware, privacy-aware i user-consent-aware oraz brać pod uwagę m.in. workload, cooldown, quota/cost, network, privacy mode i user settings.

Failover:

```text
cloud disabled / no network / timeout / 429 / quota exhausted /
credential removed / provider unavailable
-> CONTINUE LOCALLY
```

Privacy constraints mają pierwszeństwo przed availability. Provider failover nie może po cichu wysłać kampanii do providera o gorszej dozwolonej klasie prywatności.

### 52.2 Cloud context, credentials i provenance
Cloud nie dostaje całego Save/Chronicle/DB tylko dlatego, że ma duże okno kontekstu. Przyszły `CloudContextBuilder` / `CloudContextSanitizer` lub równoważna granica tworzy minimalny, zadaniowy i jawnie dozwolony kontekst.

Credential/API key/OAuth state jest infrastrukturą auth/transport i nigdy nie należy do Campaign State, Save, Chronicle ani World Pack authority. Usunięcie klucza/providera nie może uszkodzić kampanii.

Każdy cloud result powinien zachować minimum provenance wykonania: provider/model/workload, czas, contract/profile version, privacy mode i validation status. Provenance nie nadaje wynikowi authority.

### 52.3 Optional Cloud Director
Cloud Director jest przyszłym wariantem wykonania warstwy Director, a nie obowiązkowym elementem bieżącej turn transaction. Może generować `DirectorBundle` lub równoważną paczkę kandydatów: arc seeds, quest seeds, NPC agenda candidates, faction conflicts, world-event candidates, foreshadowing i pacing suggestions.

```text
CLOUD/DIRECTOR OUTPUT = CANDIDATE
CLOUD/DIRECTOR OUTPUT != FACT
CLOUD/DIRECTOR OUTPUT != COMMIT
```

Cloud enrichment może być deferred/asynchronous względem bieżącej tury. Spóźniony wynik nie może przepisać przeszłego COMMIT ani stać się authoritative state poza legalną validation/promotion/transaction path. Phase 48 dostarcza provider/execution foundation, Phase 65 semantics Directora, a Phase 79 dojrzały workload/provider/model/runtime routing.

## 53. NPC individuality, personality i social behavior — przyszły canonical target
NPC nie jest tylko rekordem wiedzy ani chwilową personą wygenerowaną przez LLM. Istotny NPC jest trwałym aktorem świata z własną tożsamością psychologiczną, wartościami, celami, relacjami, pamięcią, wiedzą i historią zachowania.

Przy utworzeniu NPC indywidualizacja powinna wynikać z World Pack archetype/population rules, kultury/organizacji, roli społecznej, backgroundu, life-history constraints oraz kontrolowanej losowości/stable seed. Wynikowe cechy są utrwalane lub odtwarzalne deterministycznie; model nie losuje od nowa osobowości przy każdym spotkaniu.

Należy rozdzielić co najmniej:
- `PERSONALITY / TRAITS` — względnie trwałe predyspozycje;
- `VALUES / FEARS / GOALS` — co NPC ceni, czego unika i do czego dąży;
- `RELATIONSHIP STATE` — stosunek do konkretnej osoby/grupy;
- `EMOTIONAL STATE` — krótkoterminowy lęk, gniew, żal, radość, stres, zazdrość, pewność itd.;
- `KNOWLEDGE / BELIEFS` — co NPC wie, podejrzewa lub błędnie uważa;
- `MEMORY` — co pamięta jako istotne dla siebie;
- `SOCIAL ROLE / ORGANIZATION / CULTURE` — obowiązki, normy i incentives;
- `RESOURCES / CAPABILITIES / CURRENT SITUATION` — realne możliwości działania.

Canonical decision semantics:

```text
NPC DECISION
= personality
+ values/goals/fears
+ emotional state
+ relationships
+ knowledge/beliefs
+ memory
+ social/organizational constraints
+ resources/capabilities
+ current situation
+ World Pack rules
+ controlled randomness where legal
```

`PERSONALITY != RELATIONSHIP != EMOTIONAL STATE`, `KNOWLEDGE != DECISION`, a `AI PROPOSAL != DECISION AUTHORITY`. Ten sam bodziec może legalnie prowadzić dwóch NPC do różnych decyzji, jeżeli różnica wynika z ich trwałego stanu i historii.

Długoterminowa zmiana osobowości jest dozwolona tylko jako bounded adaptation wynikająca z legalnej historii: traumy, wieloletnich doświadczeń, sukcesów/porażek, relacji lub innych committed przyczyn. Nie wolno retroaktywnie zmieniać charakteru dlatego, że AI potrzebuje zwrotu fabularnego.

Reputacja nie jest magiczną globalną liczbą dostępną wszystkim. NPC może posiadać osobiste belief o reputacji gracza nabyte przez spotkanie, plotkę, organizację, media, raport lub inne legalne acquisition provenance. Różne NPC mogą mieć sprzeczne belief o tej samej osobie.

Player-facing context nie pokazuje domyślnie ukrytych numeric traits/emotions NPC. Widoczność wynika z Phase 38, obserwowalnych zachowań i World Pack mechanics.

Skalowanie powinno respektować LOD: tłum może używać agregatów/archetypów, minor NPC ograniczonego profilu, persistent NPC pełniejszej osobowości, a major NPC pełnej pamięci/agenda/Director attention. Materializacja nie może fabrykować szczegółowej nieistniejącej historii; brak provenance pozostaje `UNKNOWN_NOT_RECORDED` lub równoważnym stanem.

## 54. Living World / Autonomous World Simulation — przyszły canonical target
Globalny invariant:

```text
THE WORLD DOES NOT WAIT FOR THE PLAYER.
```

Świat kampanii istnieje i rozwija się niezależnie od bezpośredniej obecności gracza. Gracz jest jednym z aktorów świata, nie jego jedynym zegarem ani jedyną przyczyną zdarzeń.

Autonomiczny runtime powinien obsługiwać aktywnych `WorldActor`/równoważne byty, takie jak NPC, rodziny, klany, organizacje, gildie, firmy, miasta, państwa, armie czy inne podmioty definiowane przez World Pack. Mogą posiadać goals, resources, capabilities, relationships, knowledge, constraints, projects i pressures.

Długotrwałe `WorldProcess`/równoważne procesy mogą obejmować m.in. wojnę, handel, migrację, politykę, faction expansion, research, budowę, epidemię, przestępczość, gospodarkę, demografię, dyplomację, szpiegostwo i inne world-specific procesy. Proces przechowuje przyczyny, uczestników, zasoby, stan/progress, constraints i następny punkt oceny; nie przechowuje z góry gwarantowanego fabularnego outcome.

Scheduler planuje **evaluation points**, nie przeznaczenie. `EVALUATE_CRISIS` jest prawidłowym future checkpointem; `KING_DIES_IN_20_DAYS` nie jest legalne, jeżeli rezultat nie jest już deterministycznie przesądzony przez committed state/rules.

### 54.1 LOD i multi-rate simulation
World Simulation używa dynamicznego LOD oraz różnych częstotliwości aktualizacji. Scena/nearby NPC mogą działać per action/turn, lokalny region per hour/day, strategiczne organizacje/economy per day/week, demografia i wolne procesy per month/season/year. Nie symulujemy każdego farmera co turę.

LOD0 = scena szczegółowa; LOD1 = lokalny region; LOD2 = organizacje/państwa strategicznie; LOD3 = odległy świat jako agregaty/trendy/pressures. Gdy obszar staje się ważny, system materializuje dodatkowy szczegół zgodny z już committed strategiczną historią zamiast wymyślać przeszłość od nowa.

Background population i drobne podmioty mogą być agregowane. Materializacja konkretnego NPC zachowuje tylko legalnie znane/wywiedzione tło; nie generuje retroaktywnie fikcyjnych eventów i provenance.

### 54.2 Causality, conservation i knowledge propagation
Wojny, gospodarka, projekty, armie, populacja, zasoby, handel i organizacje muszą zostawiać spójne skutki. Wymagana jest odpowiednia dla domeny conservation/invariant validation: straty, wydatki, zużycie zasobów, migracja, narodziny/śmierć, produkcja i transfery nie mogą znikać z modelu bez przyczyny.

Ważne background changes generują Event/Causal history; mikroaktywność pozostaje agregowana. Po latach system ma umieć wyjaśnić, dlaczego państwo upadło albo organizacja urosła, przez causal chain, a nie przez „AI tak napisało”.

FACT świata nie staje się automatycznie PLAYER/NPC KNOWLEDGE. Informacja również propaguje się w czasie przez dozwolone kanały: observation, communication, messenger, rumor, media, organization, espionage, world-specific communication itd. World Pack określa realne możliwości/prędkość komunikacji.

Quest/opportunity powinien móc wynikać z realnego world state (`shortage -> guild need -> opportunity`) zamiast być wyłącznie arbitralnie generowanym contentem.

### 54.3 Director vs Simulation
`WORLD SIMULATION = WHAT ACTUALLY DEVELOPS`.
`DIRECTOR = WHAT DESERVES ATTENTION`.

Director ani Cloud Director nie tworzy wojny, kryzysu czy śmierci tylko dlatego, że potrzebuje ciekawszej fabuły. Może podnieść relevance istniejącego pressure/process, zaproponować bounded future candidate lub poprosić o deeper simulation, lecz committed reality musi wynikać z legalnego stanu, reguł, causal history, decisions i transaction path.

### 54.4 Required future stress scenarios
Roadmap acceptance dla odpowiednich późniejszych faz powinna zawierać co najmniej:
- `WORLD_WITHOUT_PLAYER`: wieloletnia symulacja bez aktywnej ingerencji gracza powoduje legalne starzenie, zgony/narodziny tam gdzie wspierane, postęp projektów, zmiany organizacji/economy/politics/wars, legalną propagację wiedzy i replayable causal history;
- `SAME_WORLD_TWO_CAMPAIGNS`: dwie kampanie z tego samego World Pack/initial seed mogą diverge po różnych player actions, a różnice muszą być wyjaśnialne przez committed actions + world processes + controlled randomness + causal history;
- save/load/replay po background simulation zachowuje dokładnie tę samą authoritative reality;
- brak player interaction nie może prowadzić do world freeze, a brak cloud providera nie może zatrzymać lokalnej world simulation.

## 55. World Pack Creator — POST-ROADMAP EXTENSION ONLY
Produkcyjny World Pack Creator rozpoczyna się **dopiero po globalnym ukończeniu i zaakceptowaniu Phase 1–84**, chyba że użytkownik jawnie zmieni kolejność w przyszłości. Nie rezerwujemy dziś automatycznie numeru `Phase 85`.

World Pack Creator jest authoring/compiler layer nad ukończonym Core, a nie drugim RPG OS:

```text
COMPLETED RPG OS CORE (Phase 1–84)
        ^
        | final canonical contracts
WORLD PACK CREATOR
        |
        v
VALIDATED WORLD PACK ARTIFACT
        |
        v
existing World Pack validation / compatibility / activation
        |
        v
CAMPAIGN BOOTSTRAP THROUGH FINAL CORE
```

WPC nie implementuje własnego Event Store, Memory, Retriever, NPC Brain, World Simulation, Save/Load, Transaction Engine ani bezpośrednich losowych zapisów do SQLite. Build Workspace nie jest Campaign Repository i draft data nie jest aktywnym canonem.

WPC może kompilować canon/definitions, entities, locations, organizations, temporal facts, relationships, causal baseline, supported World Rules, initial knowledge seeds, simulation baseline metadata, provenance/localization i Scenario Templates. Nie zapisuje jako World Pack authority Campaign Event history, Campaign Memory, Chronicle, current NPC decisions, current player state ani campaign divergence.

Po roadmapie WPC korzysta z tego samego finalnego `AiProvider`/Model Router/workload routing co reszta systemu. Local authoring/research może działać offline tam, gdzie możliwe; opcjonalny cloud może wspierać research/design tylko za zgodą i z minimalizacją danych. Każdy AI result pozostaje typed proposal do build-time validation.

Generated, AI-assisted, imported i hand-authored pack po kompilacji używają jednego finalnego runtime World Pack contractu. Candidate pack przechodzi staging -> compile -> validation -> compatibility -> explicit/atomic activation. Update aktywnego packa nie może nadpisywać campaign divergence ani po cichu przełączać istniejących kampanii na nową revision.

Pierwszy realny krok po Phase 84 to **POST-ROADMAP AUDIT FIRST** przeciwko rzeczywistemu finalnemu repo. Dopiero wtedy zamykamy nazwy publicznych authoring APIs, schema, activation/bootstrap contract i rozpoczynamy WPC implementation.