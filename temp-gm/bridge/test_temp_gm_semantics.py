import sys
from pathlib import Path
HERE=Path(__file__).resolve().parent
if str(HERE) not in sys.path: sys.path.insert(0,str(HERE))
import temp_context_builder as cb
import temp_gm_provider as provider

FIXTURE="Przede mną stoi wrogi shinobi.\nAtakuję go kataną.\nCeluję w jego prawą dłoń.\nOpisz reakcję wrogiego shinobi."

def system_prompt(msg=FIXTURE):
    return cb.build_messages({"relevantNpcs":[{"npcUid":"enemy","sceneFacts":{"hostile":True},"knowledge":{"observed":["gracz stoi przede mną"],"heard":[],"told":[],"inferred":[]}}]},msg,"NARRATIVE_ONLY")[0]["content"]

def user_prompt(msg=FIXTURE):
    return cb.build_messages({},msg,"NARRATIVE_ONLY")[1]["content"]

def test_GM_SEM_01_direction_contract():
    p=system_prompt(); assert "Zachowuj role z deklaracji użytkownika" in p and "nie zamieniaj aktora ani celu" in p
    u=user_prompt(); assert "PLAYER DECLARATION" in u and "Atakuję go kataną" in u and "prawą dłoń" in u

def test_GM_SEM_02_no_autonomous_player_dodge():
    assert "Nigdy nie dopisuj PLAYER uniku" in system_prompt()

def test_GM_SEM_03_no_autonomous_followup_attack():
    assert "kolejnego ataku" in system_prompt()

def test_GM_SEM_04_no_invented_player_dialogue():
    assert "wypowiedzi" in system_prompt("Mówię: Stój. Opisz reakcję NPC.")

def test_GM_SEM_05_npc_autonomy_preserved():
    assert "NPC ma autonomię" in system_prompt()

def test_GM_SEM_06_npc_defense_counter_allowed():
    p=system_prompt(); assert "bronić się" in p and "kontratakować jako NPC" in p

def test_GM_SEM_07_stop_before_next_player_turn():
    assert "Nie rozpoczynaj następnej tury PLAYER" in system_prompt()

def test_GM_SEM_08_temp_scene_non_authoritative():
    p=system_prompt(); assert "TEMP SCENE OBSERVATION — NON-AUTHORITATIVE" in p and "Nie ujawniaj ani nie cytuj" in p

def test_GM_SEM_09_npc_knowledge_boundary():
    built=cb.build_context({"sceneState":{"secret":"X"},"relevantNpcs":[{"npcUid":"n","knowledge":{"observed":["Y"],"heard":[],"told":[],"inferred":[]},"forbidden":"X"}]})
    assert built["relevantNpcs"][0]["knowledge"]["observed"]==["Y"] and "X" not in str(built["relevantNpcs"][0])

def test_GM_SEM_10_canonical_mutation_false():
    r=provider.TempGmResponse(provider_id="BIELIK_4_5B_V3",mode="NARRATIVE_ONLY",narrative="x",usage={}).as_dict(); assert r["canonicalMutation"] is False and "statePatch" not in r and "playerChangeSet" not in r
