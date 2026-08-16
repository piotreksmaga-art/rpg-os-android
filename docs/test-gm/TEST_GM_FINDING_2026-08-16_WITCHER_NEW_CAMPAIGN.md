# TEST GM FINDING — Wiedźmin / nowa kampania

Date: 2026-08-16
Status: PLAYTEST EVIDENCE / NON-CANONICAL / NON-RUNTIME
Scope boundary: `docs/test-gm/**` only

This report records TEST GM findings from a new-campaign Witcher playtest. It does not modify runtime, roadmap, acceptance records, World Packs, migrations, or canonical architecture. Findings are evidence for coordinator review only.

## 1. Brak World Packa dla Wiedźmina — wysoki priorytet

Kampania działa obecnie na `ARCHITECTURE FALLBACK` dla warstwy uniwersum. Powoduje to, że GM może zachować kanon narracyjny, ale brakuje formalnych definicji m.in. ras, magii, szkół, geografii, organizacji, ekonomii i charakterystycznych ścieżek rozwoju.

Wniosek: system dobrze pozwala rozpocząć kampanię, ale brak World Packa szybko staje się głównym ograniczeniem.

## 2. Character Creation nie daje bezpiecznego sposobu inicjalizacji pełnego panelu liczbowego

Gracz poprosił o status/statystyki. TEST GM potrafił bezpiecznie pokazać fakty jakościowe, ale nie miał podstaw do arbitralnego nadania np. Siły 8, Inteligencji 12 czy wartości potencjału magicznego.

Zgodnie z TEST GM Rules było poprawne pozostawienie liczb nieustalonych, ponieważ bez podstawy mechanicznej nie wolno wymyślać permanentnego numerical gain/state.

Potrzeba: deterministyczny/legalny bootstrap początkowych statystyk nowej postaci.

## 3. Brakuje wygodnego runtime dla latent/innate potential

Gracz ustanowił przy tworzeniu postaci: „magiczny potencjał do zostania magiem”.

Playtest potrzebował potem rozdzielić:

- potencjał wrodzony;
- spontaniczną manifestację;
- obserwację manifestacji;
- formalnie rozpoznaną predyspozycję;
- nauczoną technikę;
- mastery.

To testuje granicę `innate ability != skill != technique`.

Potrzeba: formalny lifecycle `latent trait -> discovered trait -> assessed trait -> trained capability`.

## 4. Training/progression potrzebuje obsługi „evidence without gain”

Smagi rozpoczął naukę:

- czytania od ojca;
- zielarstwa od matki;
- obserwacji roślin.

Nie było jeszcze podstaw do przyznania permanentnego poziomu umiejętności. TEST GM przechowywał jakościowe evidence/progress zamiast wymyślać liczby.

Potrzeba: runtime powinien naturalnie reprezentować:

`training event -> accumulated evidence/progress -> threshold/evaluation -> actual permanent gain`

bez konieczności natychmiastowego podnoszenia statystyki.

## 5. Knowledge/BELIEF działa koncepcyjnie dobrze, ale potrzebuje runtime

Playtest wymagał rozróżnienia:

- matka słyszała o możliwościach magów;
- kupiec twierdził, że magowie bywają przy dworze;
- Smagi podejrzewał, że wywołuje wiatr;
- faktem było tylko, że poruszył się liść;
- Elvar dopiero później uzyskał evidence wskazujące na rzeczywistą predyspozycję.

To jest praktyczny przypadek dla invariantu `FACT != BELIEF != NARRATIVE` oraz ograniczonej wiedzy NPC.

Wniosek: ta warstwa staje się potrzebna bardzo wcześnie w realnej kampanii.

## 6. Investigation/diagnostics nie powinno automatycznie tworzyć prawdy

Test Elvara pokazał potrzebę mechaniki:

`observation -> evidence -> hypothesis -> repeated test -> confidence -> conclusion`

Pierwszy magiczny test nie powinien automatycznie ustanawiać np. `MagicPotential = 87`.

Potrzeba: ogólny mechanizm assessment/check/diagnostic evidence przydatny m.in. dla magii, medycyny, identyfikacji przedmiotów, badań i talentów.

## 7. TEST SESSION STATE powinien mieć łatwy snapshot/status

Po kilkunastu turach kampania posiadała m.in. rodzinę, lokalizację, czas, przedmiot, wiedzę NPC, sekret, rozpoczęte szkolenia, manifestacje magiczne, cele, kontakt z Elvarem i list polecający.

TEST GM Rules dopuszczają bounded conversational TEST SESSION STATE dla takich danych.

Potrzeba: standardowy testowy format snapshotu/statusu, aby dłuższe playtesty nie polegały na ręcznym rekonstruowaniu stanu.

## 8. Najważniejszy finding playtestu

RPG OS już teraz dobrze wymusza kauzalność. Największy problem pojawia się w momencie, w którym naturalna narracja chce ustanowić permanentny fakt, a nie istnieje jeszcze accepted runtime mogący go legalnie rozstrzygnąć.

Wtedy TEST GM musi zatrzymać `GM fiat`.

Priorytetowy łańcuch ujawniony przez playtest:

`Character bootstrap -> latent traits -> training evidence -> knowledge/evidence -> permanent-state commit`

Ten łańcuch pojawił się praktycznie natychmiast w pierwszych kilkudziesięciu turach prawdziwej kampanii.

## Coordinator triage notes

- Finding 1: likely World Pack content gap, not Core blocker by itself.
- Finding 2: likely future character bootstrap / new-game initialization gap; requires roadmap mapping before implementation.
- Finding 3: likely cross-cutting domain modeling gap touching innate traits, discovery, assessment, skills/techniques; do not implement ad hoc.
- Finding 4: strongly related to progression evidence semantics and should be checked against Phase 20/21 boundaries before any change.
- Finding 5: expected to map to future NPC Knowledge / belief-state runtime; evidence suggests practical importance appears early.
- Finding 6: may require a generic evidence/assessment contract rather than magic-specific logic.
- Finding 7: test harness concern today; canonical snapshot authority remains a later engine concern.
- Finding 8: accepted as a useful integration insight: permanent-state boundaries are the first place where incomplete phases materially constrain natural play.

No roadmap priority is changed by this report alone. Coordinator must map each item against current canonical dependencies before assigning production work.
