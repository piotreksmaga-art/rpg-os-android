from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if text.count(old) != 1:
        raise SystemExit(f'{path}: expected one marker, got {text.count(old)}')
    p.write_text(text.replace(old, new, 1))

arch_marker = '### 15.2 LOD i multi-rate simulation\n'
arch_insert = '''### 15.2 Motivational Core, Life Continuity i autonomous agency
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
'''
replace_once('docs/Architektura projektu.md', arch_marker, arch_insert)
replace_once('docs/Architektura projektu.md', '### 15.3 Causality, conservation i informacja', '### 15.5 Causality, conservation i informacja')
replace_once('docs/Architektura projektu.md', '### 15.4 Persistent World i Character Succession — CANONICAL TARGET', '### 15.7 Persistent World i Character Succession — CANONICAL TARGET')

arch_evolve_marker = '''Director może podnosić relevance albo proponować future candidates; nie tworzy committed wojny, kryzysu czy śmierci bez legalnego causal/domain basis.

### 15.7 Persistent World i Character Succession — CANONICAL TARGET
'''
arch_evolve_new = '''Director może podnosić relevance albo proponować future candidates; nie tworzy committed wojny, kryzysu czy śmierci bez legalnego causal/domain basis.

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
'''
replace_once('docs/Architektura projektu.md', arch_evolve_marker, arch_evolve_new)

road_old = '''Phase 63–64 Living World:
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
'''
road_new = '''Phase 63–64 Living World:
- global invariant: **THE WORLD DOES NOT WAIT FOR THE PLAYER**;
- Living World is a causal simulator, not a random event generator; material events require actor/process/domain basis;
- WorldActor support for NPC/family/clan/organization/company/guild/city/state/army/world-specific actors;
- persisted `Motivational Core`: needs/pressures, desires, dreams/aspirations, ambitions, fears/aversions, values, loyalties/obligations, goals, plans/commitments and optional core drives/obsessions;
- motivations can form/change/weaken/resolve/conflict through committed causes; actor is not reset to archetype off-screen;
- `World Actor Life Continuity`: knowledge + memory + relationships + personality + values + motivation + goals + commitments survive scenes/time skips according to authority;
- causal loop: `WORLD STATE -> KNOWLEDGE -> PERSONALITY/VALUES/NEEDS -> DESIRES/DREAMS -> GOALS -> PLANS -> OPPORTUNITY/THREAT -> DECISION -> ACTION -> CONSEQUENCES -> WORLD STATE`;
- long-running WorldProcess: wars, trade, politics, migration, research, construction, epidemics, crime, economy, demography, diplomacy, espionage itd.;
- institutional agendas/strategic drives exist separately from every member's personal motivations;
- information ecology: actors react to their holder-scoped beliefs/estimates, while information propagates with world-specific channels/delays/distortion;
- opportunity/threat engine can surface legal action candidates from goals + knowledge + situation, but does not commit decisions itself;
- consequence propagation follows relationships/dependencies/ownership/supply/organization/process links;
- collective phenomena may emerge from many legal processes instead of arbitrary random events;
- dynamic LOD0–3 + multi-rate simulation;
- population/crowd aggregation and provenance-safe materialization;
- world/domain conservation for supported resources/population/money/goods/armies/projects;
- important background changes -> Event/Causal history;
- background FACT does not automatically become PLAYER/NPC KNOWLEDGE;
- legal information propagation using world-specific communication constraints;
- opportunities/quests may emerge from world state/process;
- former PC after explicit relinquish may use autonomous motivational/decision/Living World pipeline; active PC remains USER-controlled only;
- local world simulation works without cloud;
- Living World remains explicitly extensible: further improvements are allowed, but every material improvement must be documented with semantic contract, authority/invariants, replay/migration/performance impact and regression/adversarial tests before canonical acceptance;
- `LIVING WORLD IMPROVEMENT WITHOUT DOCUMENTED SEMANTIC CONTRACT = NOT CANONICAL`.
'''
replace_once('docs/Roadmap.md', road_old, road_new)

cross_marker = '- [ ] world-process/domain conservation for supported economy/population/resources/projects\n'
cross_new = '''- [ ] world-process/domain conservation for supported economy/population/resources/projects
- [ ] `WORLD_CAUSALITY_LOOP`: actor knowledge/motivation/goals/plans produce explainable decisions/actions/consequences without random-event fabrication
- [ ] actor desires/dreams/goals can evolve through committed causes and survive off-screen/time-skip continuity
- [ ] conflicting motivations can produce different legal decisions without rewriting personality/history
- [ ] institutional agenda != every member personal desire/goal
- [ ] information delay/distortion can change decisions while objective FACT remains unchanged
- [ ] consequence propagation affects causally linked actors/processes without omniscient/global leakage
- [ ] active PC motivational state never authorizes autonomous voluntary PC action
- [ ] every material Living World enhancement has documented semantics, authority/invariants, LOD/performance, replay/migration compatibility and tests before acceptance
'''
replace_once('docs/Roadmap.md', cross_marker, cross_new)
print('Living World expansion applied')
