import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import temp_context_builder as cb
import temp_gm_provider as provider

FIXTURE = "Przede mną stoi wrogi shinobi.\nAtakuję go kataną.\nCeluję w jego prawą dłoń.\nOpisz reakcję wrogiego shinobi."


def messages(msg=FIXTURE):
    return cb.build_messages(
        {
            "relevantNpcs": [
                {
                    "npcUid": "enemy",
                    "sceneFacts": {"hostile": True},
                    "knowledge": {
                        "observed": ["gracz stoi przede mną"],
                        "heard": [],
                        "told": [],
                        "inferred": [],
                    },
                }
            ]
        },
        msg,
        "NARRATIVE_ONLY",
    )


def system_prompt(msg=FIXTURE):
    return messages(msg)[0]["content"]


def test_GM_SEM_01_direction_contract():
    p = system_prompt()
    assert "wykonawca, czynność i cel nie mogą zostać zamienione" in p
    assert "gracz pozostaje wykonawcą" in p


def test_GM_SEM_02_no_autonomous_player_dodge():
    p = system_prompt()
    assert "Nie dodawaj żadnej nowej czynności gracza" in p


def test_GM_SEM_03_no_autonomous_followup_attack():
    p = system_prompt()
    assert "Nie rozpoczynaj kolejnej tury gracza" in p


def test_GM_SEM_04_no_invented_player_dialogue():
    p = system_prompt("Mówię: Stój. Opisz reakcję NPC.")
    assert "wypowiedzi" in p and "wyłącznie z bieżącej deklaracji użytkownika" in p


def test_GM_SEM_05_npc_autonomy_preserved():
    assert "NPC zachowują autonomię" in system_prompt()


def test_GM_SEM_06_npc_defense_counter_allowed():
    p = system_prompt()
    assert "bronić" in p and "kontratakować" in p


def test_GM_SEM_07_stop_before_next_player_turn():
    p = system_prompt()
    assert "Potem zakończ odpowiedź" in p
    assert "Nie rozpoczynaj kolejnej tury gracza" in p


def test_GM_SEM_08_no_diagnostic_token_priming():
    combined = "\n".join(m["content"] for m in messages()).lower()
    assert "test_failure" not in combined
    assert "test_fallback" not in combined
    assert "read-only" not in combined
    assert "canonicalmutation=false" not in combined


def test_GM_SEM_09_npc_knowledge_boundary():
    built = cb.build_context(
        {
            "sceneState": {"secret": "X"},
            "relevantNpcs": [
                {
                    "npcUid": "n",
                    "knowledge": {
                        "observed": ["Y"],
                        "heard": [],
                        "told": [],
                        "inferred": [],
                    },
                    "forbidden": "X",
                }
            ],
        }
    )
    assert built["relevantNpcs"][0]["knowledge"]["observed"] == ["Y"]
    assert "X" not in str(built["relevantNpcs"][0])


def test_GM_SEM_10_canonical_mutation_false():
    r = provider.TempGmResponse(
        provider_id="BIELIK_4_5B_V3",
        mode="NARRATIVE_ONLY",
        narrative="x",
        usage={},
    ).as_dict()
    assert r["canonicalMutation"] is False
    assert "statePatch" not in r
    assert "playerChangeSet" not in r
