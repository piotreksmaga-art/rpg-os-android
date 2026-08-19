# Historia projektu

Status: ARCHIWUM / HISTORYCZNE ŹRÓDŁO ODNIESIENIA

Ten plik przejmuje rolę kroniki zmian dokumentacji kanonicznej. Aktywne `docs/Architektura projektu.md` i `docs/Roadmap.md` mają od tej chwili zawierać wyłącznie obowiązujące kontrakty, statusy, kolejność i kryteria; historyczne rozwinięcia, wcześniejsze warianty oraz acceptance evidence są archiwizowane tutaj albo w wskazanych acceptance records.

## Snapshot przed normalizacją — 2026-08-20

Źródłowy branch: `docs-future-architecture-hybrid-living-world`.

Stan dokumentów bezpośrednio przed odchudzeniem:
- `docs/Architektura projektu.md` — blob `f92517f6b748383c8f36d266cfaf15feefd7f07e`;
- `docs/Roadmap.md` — blob `71af97868308dae8caa6f62ce67ffe67a572d36d`;
- commit z zatwierdzonym rozszerzeniem Hybrid Local-First / NPC individuality / Living World: `9b0a79ae5b252925223937d08795d4985de0aa25`.

Pełne treści 1:1 zostały zachowane jako załączniki archiwalne:
- [`history/Architektura projektu.snapshot-2026-08-20.md`](history/Architektura%20projektu.snapshot-2026-08-20.md)
- [`history/Roadmap.snapshot-2026-08-20.md`](history/Roadmap.snapshot-2026-08-20.md)

Git history pozostaje dodatkowym, niezależnym źródłem rekonstrukcji wcześniejszych wersji.

## Accepted baseline do Phase 36

Canonical accepted runtime through Phase 36: `4d5a114fc9f08141d75ae79f998a3400866b52ba`.

Final post-audit acceptance CI: `Validate RPG OS ALPHA`, run #801 / run ID `32309493128` — SUCCESS.

Historyczne główne acceptance points:
- Phase 20: `38dafe5cc48c87f16218e346d9c0f9a96b6cee50`, CI #578 / `31961047982`;
- Phase 21–25: `c028aa355d9b7e1663166a2fedb910c1a2dad795`, CI #607 / `31968919354`;
- Phase 26–29: `45ff53457bff16c4ff72a4cccdecac89124109c3`, CI #703 / `32038070404`;
- Phase 30–32: `c202e1a7e620f1839763b8be513fd2b397760ac0`, CI #779 / `32166222114`, artifact `9335687331`, digest `sha256:9d2e41407f47874f854c17f2f35959aea2f166adbc5a2ffc093630ff1062c629`;
- Phase 33–34: `b141a590c64b21930abcae6c63353ea93aaf50f4`, CI #783 / `32217138911`, artifact `9352815554`, digest `sha256:01fdb47e78a2abdd1c56fa20591431f1f3bb33f1b6f2fcdf40812c517de18e1f`;
- Phase 35–36 initial acceptance: `7cb61d3cdc42e0c20f2688181d054e55eacbfd8f`, run `32241299329`, artifact `9361064715`, digest `sha256:73da8802468e0302c5f4548bda9a2240d5c44a17ceed209b27e10c6e11f84b90`;
- Phase 35–36 post-audit re-acceptance: `4d5a114fc9f08141d75ae79f998a3400866b52ba`, run #801 / `32309493128`.

Phase 35 findings `P35-AUD-001..003` oraz Phase 36 findings `P36-AUD-001..006` zostały naprawione, niezależnie zweryfikowane i ponownie przyjęte. Szczegółowe acceptance records pozostają w `docs/architecture/PHASE35_36_ACCEPTANCE.md` i wcześniejszych dokumentach fazowych.

## Historia kierunku AI

Pierwszy przyszły kierunek zakładał wymienny native/local AI-GM, bez lock-in na Bielik/PLLuM/Gemma, GGUF, llama.cpp, LiteRT-LM, ExecuTorch ani backend CPU/GPU/NPU. TEMP-GM/Termux/localhost pozostawały wyłącznie laboratorium R&D.

Następnie użytkownik zatwierdził docelowy model **HYBRID LOCAL-FIRST**:
- lokalny GM musi wystarczać do kontynuowania kampanii offline;
- cloud jest opcjonalnym wzmacniaczem jakości i nie ma mutation authority;
- provider/model/runtime/backend pozostają wymienne;
- cloud failure, quota, 429, brak sieci albo usunięcie credentials nie może blokować kampanii;
- cloud context jest minimalizowany i sanitizowany;
- credentials nie należą do Save/Campaign State/World Pack;
- Cloud Director generuje jedynie bounded candidates, nie FACT/COMMIT;
- Phase 48 buduje provider/execution foundation, Phase 65 semantics Directora, a Phase 79 routing workload/provider/model/runtime.

## Historia kierunku NPC

Początkowy kontrakt NPC obejmował holder-scoped knowledge, personality, goals, fears, values, loyalties, relationships, resources, abilities, location, current task i long-term plan.

Późniejsze zatwierdzone rozszerzenie doprecyzowało trwałą indywidualność NPC:
- personality/traits;
- values/goals/fears;
- relationship state;
- emotional state;
- knowledge/beliefs;
- memory;
- social role / organization / culture;
- resources/capabilities/current situation;
- archetype/background + controlled stable RNG przy tworzeniu NPC;
- brak ponownego losowania osobowości przy każdym spotkaniu;
- long-term personality adaptation tylko z legalnym committed cause/provenance;
- reputacja jako holder-scoped belief, nie omniscient global score;
- różne NPC mogą legalnie reagować inaczej na ten sam bodziec z powodu trwałego stanu.

## Historia kierunku Living World

Zatwierdzony globalny invariant brzmi: **THE WORLD DOES NOT WAIT FOR THE PLAYER.**

Rozwinięto dotychczasowe World Simulation LOD o:
- autonomicznych WorldActorów: NPC, rodziny, klany, organizacje, firmy/gildie, miasta, państwa, armie i world-specific actors;
- długotrwałe WorldProcess: wojny, handel, migracja, polityka, research, budowa, epidemie, crime, economy, demografia, dyplomacja, espionage itd.;
- Scheduler jako system evaluation points/deadlines, nie precommitted outcomes;
- dynamiczne LOD0–3 i multi-rate simulation;
- agregację tłumu/populacji;
- materializację bez fabrykowania historycznej provenance;
- domain conservation dla wspieranych zasobów, populacji, pieniędzy, armii i projektów;
- Event/Causal history dla istotnych background changes;
- legalną propagację informacji — background FACT nie oznacza automatycznie PLAYER/NPC KNOWLEDGE;
- emergent opportunities/quests wynikające ze stanu świata;
- rozdzielenie `WORLD SIMULATION = WHAT ACTUALLY DEVELOPS` od `DIRECTOR = WHAT DESERVES ATTENTION`;
- stress scenarios `WORLD_WITHOUT_PLAYER` i `SAME_WORLD_TWO_CAMPAIGNS`.

## Historia World Pack Creator

World Pack Creator został przesunięty **po globalne zaakceptowanie Phase 1–84**. Nie rezerwuje się obecnie Phase 85.

WPC ma być authoring/compiler layer nad finalnym Core, nie drugim RPG OS. Nie implementuje własnego Event Store, Memory, Retriever, NPC Brain, World Simulation, Save/Load, Transaction Engine ani gameplay raw-DB path.

Robocza post-roadmap sekwencja została zapisana jako `WPC-A..WPC-J`: audit finalnego Core, authoring contract, original-world vertical slice, historical research, rule compilation/impact analysis, scenario templates/bootstrap, progressive expansion, UX, scale/security/Android hardening oraz final acceptance.

## Zasada dalszej historii

Przy kolejnych dużych normalizacjach aktywnych dokumentów:
1. zachowaj exact snapshot w `docs/history/` albo w innym jawnie wskazanym archiwum;
2. dopisz do tego pliku datę, commit/blob SHA i najważniejsze decyzje;
3. nie przenoś historycznych opisów z powrotem do aktywnej Roadmapy ani Architektury, chyba że stają się ponownie obowiązującym kontraktem.
