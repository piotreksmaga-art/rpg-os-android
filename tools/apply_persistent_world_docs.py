from pathlib import Path


def insert_once(path: str, marker: str, addition: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(marker)
    if count != 1:
        raise SystemExit(f"{path}: expected one marker, found {count}: {marker!r}")
    p.write_text(text.replace(marker, addition + marker, 1))


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}: {old!r}")
    p.write_text(text.replace(old, new, 1))


arch_addition = '''### 15.4 Persistent World i Character Succession — CANONICAL TARGET
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

'''
insert_once("docs/Architektura projektu.md", "## 16. Memory i Chronicle\n", arch_addition)

replace_once(
    "docs/Roadmap.md",
    "- [-] 71. Save/Load integration\n",
    "- [-] 71. Save/Load integration + Persistent World / Character Succession\n",
)

roadmap_addition = '''Persistent World / Character Succession w Phase 71 ma zagwarantować rozdzielenie Campaign World od aktualnej Player Character:
- `CAMPAIGN/WORLD != ACTIVE PLAYER CHARACTER`;
- użytkownik może rozpocząć nową postać w istniejącym świecie bez resetu committed history i world state;
- poprzednia żyjąca postać może przejść do statusu autonomicznego World Actora/NPC zamiast znikać lub pozostawać zamrożona;
- previous PC zachowuje własny authoritative stan, ownership, relacje, memory i holder-scoped knowledge;
- new PC otrzymuje nową identity i własny Player/Knowledge state; nie dziedziczy automatycznie prywatnej wiedzy, memories, abilities, inventory ani relationship state starego PC;
- `SAME HUMAN USER != SAME CHARACTER KNOWLEDGE HOLDER`;
- NPC/organizations zachowują wiedzę i relacje dotyczące poprzedniej postaci po zmianie aktywnego PC;
- World Processes, economy, wars, projects, organizations, canon divergence, Event/Causal history i czas pozostają ciągłe;
- control transfer/retirement jest canonical committed operation z provenance i idempotency, nie flagą UI ani AI side effect;
- unique ownership nie może zostać zdublowane podczas zmiany PC;
- save/load/snapshot/replay/branching zachowują historię aktywnych/former PCs oraz dokładnie jeden spójny Campaign World;
- former PC może zostać później spotkany przez nową postać i działać przez NPC Brain/Decision Engine/Living World zgodnie z własnym stanem;
- death/retirement nie usuwa historycznych skutków byłej postaci.

Obowiązkowe testy Phase 71 obejmują co najmniej:
- `NEW_CHARACTER_SAME_WORLD`: old PC -> retire/control release -> new PC, World UID/history/state unchanged;
- old PC pozostaje poprawnym World Actorem z własnym state/knowledge;
- new PC nie posiada old-PC-only knowledge bez legalnej acquisition/bootstrap rule;
- NPC A pamięta/rozpoznaje old PC, ale nie zna automatycznie new PC;
- unique item/ownership nie duplikuje się przy zmianie PC;
- former PC autonomicznie ewoluuje w Living World po utracie player control;
- new PC może później spotkać former PC, a oba stany pozostają rozdzielone;
- snapshot/save/load/replay przed i po character succession daje authoritative equality;
- branch może zmienić wybór kolejnej PC bez przepisywania wspólnej historii przed branch point;
- rollback/retry control-transfer nie tworzy dwóch ACTIVE_PLAYER_CHARACTER ani phantom succession.

'''
insert_once("docs/Roadmap.md", "## Acceptance direction Phase 71–79\nWymagane m.in.:\n", "## Acceptance direction Phase 71–79\n" + roadmap_addition + "Wymagane m.in.:\n")

cross_marker = "- [ ] `SAME_WORLD_TWO_CAMPAIGNS` divergence explainable by player actions + world processes + controlled randomness\n"
cross_add = '''- [ ] `NEW_CHARACTER_SAME_WORLD`: nowy PC zachowuje ten sam Campaign World/history, old PC pozostaje aktorem świata, brak automatic knowledge/state inheritance\n- [ ] former-PC -> autonomous World Actor continuity + later encounter with new PC\n- [ ] character succession preserves unique ownership, holder-scoped knowledge, NPC relations, save/load/replay and exactly one active PC\n'''
insert_once("docs/Roadmap.md", cross_marker, cross_add)

print("persistent world / character succession docs patched")
