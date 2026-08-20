from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if text.count(old) != 1:
        raise SystemExit(f'{path}: expected one match for marker')
    p.write_text(text.replace(old, new, 1))

arch_old = '''### 13.2 Player agency
`VOLITIONAL PLAYER ACTION SOURCE = USER / VALIDATED PLAYER COMMAND ONLY`.

AI nie dopisuje graczowi dobrowolnych ruchów, wypowiedzi, ataków, wyborów celu ani zdolności. Mechanika może narzucić konsekwencje niezależne od woli, np. stun/knockback/utrata przytomności.

`ACTOR / ACTION / TARGET` muszą być zachowane strukturalnie. Provider conformance obejmuje player agency, direction, NPC knowledge isolation, FACT/BELIEF, stop point, invented abilities/dialogue, internal-context leakage i brak mutation authority.
'''
arch_new = '''### 13.2 Player agency
`VOLITIONAL PLAYER ACTION SOURCE = USER / VALIDATED PLAYER COMMAND ONLY`.

`ACTIVE_PLAYER_CHARACTER CONTROL AUTHORITY = USER ONLY`.

AI/MG, Director, NPC Brain, World Simulation, Scheduler, cloud/local provider ani żaden inny autonomiczny subsystem nie może przejąć kontroli nad aktualnie graną postacią, wygenerować za nią dobrowolnej decyzji ani zamienić proposal/narracji w akcję PC bez zwalidowanego inputu użytkownika.

AI nie dopisuje graczowi dobrowolnych ruchów, wypowiedzi, ataków, wyborów celu, użycia techniki/przedmiotu, akceptacji/odrzucenia zadania, zmiany celu, relacji, planu ani transferu kontroli. Mechanika może narzucić wyłącznie konsekwencje niezależne od woli, np. stun, knockback, utrata przytomności, forced movement lub inne jawnie zdefiniowane World Rule effects; taki skutek pozostaje `MECHANICAL CONSEQUENCE != VOLITIONAL PLAYER ACTION`.

System musi structurally rozróżniać co najmniej `PLAYER_COMMAND`, `NPC/WORLD ACTION`, `MECHANICAL CONSEQUENCE` i `NARRATIVE DESCRIPTION`. Brak inputu użytkownika nie może być interpretowany jako zgoda na działanie aktualnego PC.

Control transfer z `ACTIVE_PLAYER_CHARACTER` na innego aktora wymaga jawnej, zwalidowanej komendy użytkownika i canonical committed transition. AI/MG nie może samodzielnie przełączyć aktywnego PC, retired/relinquish obecnej postaci ani oddać jej pod NPC autonomy.

`ACTOR / ACTION / TARGET` muszą być zachowane strukturalnie. Provider conformance obejmuje player agency, direction, NPC knowledge isolation, FACT/BELIEF, stop point, invented abilities/dialogue, internal-context leakage i brak mutation authority.
'''
replace_once('docs/Architektura projektu.md', arch_old, arch_new)

road_old = '''- `VOLITIONAL PLAYER ACTION SOURCE = USER / VALIDATED PLAYER COMMAND ONLY`;
- structural ACTOR/ACTION/TARGET preservation;
'''
road_new = '''- `VOLITIONAL PLAYER ACTION SOURCE = USER / VALIDATED PLAYER COMMAND ONLY`;
- `ACTIVE_PLAYER_CHARACTER CONTROL AUTHORITY = USER ONLY`; AI/MG/Director/NPC Brain/World Simulation nie wykonują dobrowolnych akcji, dialogu, wyborów ani control transfer za aktualnego PC;
- brak user input/odpowiedzi nie oznacza zgody; `MECHANICAL CONSEQUENCE != VOLITIONAL PLAYER ACTION`;
- structural ACTOR/ACTION/TARGET preservation;
'''
replace_once('docs/Roadmap.md', road_old, road_new)

road71_old = '''- control transfer/retirement jest canonical committed operation z provenance i idempotency, nie flagą UI ani AI side effect;
'''
road71_new = '''- control transfer/retirement jest canonical committed operation z provenance i idempotency, nie flagą UI ani AI side effect;
- transfer/relinquish bieżącego `ACTIVE_PLAYER_CHARACTER` wymaga jawnej validated user command; MG/AI nie może samodzielnie odebrać graczowi kontroli ani przekazać aktualnej postaci do NPC autonomy;
'''
replace_once('docs/Roadmap.md', road71_old, road71_new)

# Clean accidental duplicate heading from previous documentation insertion.
dup = '''Wymagane m.in.:
## Acceptance direction Phase 71–79
Wymagane m.in.:
'''
replace_once('docs/Roadmap.md', dup, 'Wymagane m.in.:\n')

cross_old = '''- [ ] local AI player-agency + actor/action/target conformance
'''
cross_new = '''- [ ] local AI player-agency + actor/action/target conformance
- [ ] `ACTIVE_PLAYER_CHARACTER_CONTROL`: AI/MG/Director/NPC/World Simulation cannot generate or commit voluntary PC action without validated user command
- [ ] silence/missing user input never becomes consent; forced mechanical consequence remains typed separately from player volition
- [ ] character control transfer requires explicit validated user request + atomic commit; no autonomous relinquish/retirement by MG
'''
replace_once('docs/Roadmap.md', cross_old, cross_new)
print('active player control invariant docs patched')
