# CHAT-7 TEMP GM semantic hardening

Work item: WORK-20260815-001
Date: 2026-08-15

## Physical baseline evidence
Real Galaxy S24 validation reported two model-output defects for the declaration:

> Przede mną stoi wrogi shinobi.
> Atakuję go kataną.
> Celuję w jego prawą dłoń.
> Opisz reakcję wrogiego shinobi.

Observed defects supplied from device validation:
- ACTION_DIRECTION_REVERSAL: model changed PLAYER -> NPC attack direction and moved right-hand target from NPC to PLAYER.
- PLAYER_AGENCY_VIOLATION: model invented player dodge/counterattack/hit/future behavior.

The pre-fix SYSTEM_PROMPT contained canonical non-mutation and NPC-knowledge constraints but no explicit player-agency, semantic-role preservation, or stop-before-next-player-turn contract. This is the identified contract gap.

## Hardening
`temp_context_builder.SYSTEM_PROMPT` now explicitly states:
- PLAYER action/dialogue/movement/intent source = user input only;
- ACTOR/ACTION/TARGET preservation;
- NPC autonomy remains allowed;
- no autonomous PLAYER dodge/follow-up attack/dialogue/movement;
- stop after declared action + NPC reaction + immediate result;
- optional scene summary label is `TEMP SCENE OBSERVATION — NON-AUTHORITATIVE`.

No canonical state wiring was added. Runtime parameters were not changed.

## Regression suite
Added `temp-gm/bridge/test_temp_gm_semantics.py` with GM_SEM_01..GM_SEM_10 contract regressions, including Polish adversarial declarations, NPC autonomy, NPC knowledge isolation and canonicalMutation=false.

Repository-side test definitions are committed. Real model behavioral PASS is intentionally not claimed until the updated branch is executed against Bielik and CHAT-6 repeats the physical Galaxy S24 fixture.

## Device retest fixture
Use exactly:

Przede mną stoi wrogi shinobi.
Atakuję go kataną.
Celuję w jego prawą dłoń.
Opisz reakcję wrogiego shinobi.

PASS requires: player remains actor of declared katana attack; target remains NPC right hand; NPC may autonomously react; no undeclared player dodge/attack/dialogue/movement; response stops before next player decision; canonicalMutation=false.
