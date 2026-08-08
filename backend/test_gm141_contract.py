import importlib
import json
import os

os.environ.setdefault("OPENAI_API_KEY", "test-key")

from fastapi.testclient import TestClient

backend = importlib.import_module("app")


class _FakeResponse:
    def __init__(self, payload):
        self.output_text = json.dumps(payload, ensure_ascii=False)


class _FakeResponses:
    def __init__(self, payload):
        self.payload = payload
        self.calls = []

    def create(self, **kwargs):
        self.calls.append(kwargs)
        return _FakeResponse(self.payload)


class _FakeClient:
    def __init__(self, payload):
        self.responses = _FakeResponses(payload)


def _request_payload():
    return {
        "protocol": "rpg-os-gm141-proposal-v1",
        "campaign_id": "CAMPAIGN-test",
        "worldpack_id": "WORLDPACK-test",
        "chapter": 7,
        "locale": "pl-PL",
        "player_action": "Idę do bramy.",
        "context": {
            "campaign_id": "CAMPAIGN-test",
            "chapter": 7,
            "scene": {"title": "CURRENT_SCENE", "content": "{}", "priority": 100, "characters": 2},
        },
        "response_contract": {"rule": "semantic proposal only"},
    }


def _valid_model_payload():
    return {
        "narrative_draft": "Docierasz do bramy.",
        "proposed_actions": [
            {
                "action_type": "STATE_SET",
                "actor_id": "PLAYER-1",
                "target_id": "PLAYER-1",
                "parameters": json.dumps(
                    {
                        "entity_type": "CHARACTER",
                        "field": "location_uid",
                        "value": "LOC-GATE",
                        "event_key": "move-gate",
                        "event_type": "NPC_MOVED",
                        "description": "Gracz dociera do bramy.",
                    }
                ),
                "reason": "Akcja gracza zmienia lokację.",
            }
        ],
        "proposed_memories": [],
        "proposed_chronicle_entries": [],
        "diagnostics": {
            "context_characters": 100,
            "retrieved_memory_count": 0,
            "retrieved_canon_count": 0,
            "retrieved_npc_count": 0,
            "retrieved_thread_count": 0,
            "warnings": [],
        },
    }


def test_proposal_endpoint_returns_semantic_contract(monkeypatch):
    fake = _FakeClient(_valid_model_payload())
    monkeypatch.setattr(backend, "client", fake)
    client = TestClient(backend.app)

    response = client.post("/v1/gm/proposal", json=_request_payload())

    assert response.status_code == 200, response.text
    body = response.json()
    assert body["narrative_draft"] == "Docierasz do bramy."
    assert body["proposed_actions"][0]["action_type"] == "STATE_SET"
    assert "state_patch" not in body
    assert len(fake.responses.calls) == 1

    call = fake.responses.calls[0]
    assert call["text"]["format"]["type"] == "json_schema"
    assert call["text"]["format"]["strict"] is True
    assert call["text"]["format"]["name"] == "rpg_os_gm141_proposal"


def test_proposal_endpoint_rejects_wrong_protocol(monkeypatch):
    fake = _FakeClient(_valid_model_payload())
    monkeypatch.setattr(backend, "client", fake)
    client = TestClient(backend.app)
    payload = _request_payload()
    payload["protocol"] = "legacy"

    response = client.post("/v1/gm/proposal", json=payload)

    assert response.status_code == 400
    assert fake.responses.calls == []


def test_proposal_endpoint_rejects_database_instructions(monkeypatch):
    payload = _valid_model_payload()
    payload["proposed_actions"][0]["parameters"] = json.dumps(
        {"instruction": "DROP TABLE gm_turns"}
    )
    fake = _FakeClient(payload)
    monkeypatch.setattr(backend, "client", fake)
    client = TestClient(backend.app)

    response = client.post("/v1/gm/proposal", json=_request_payload())

    assert response.status_code == 502
    assert "forbidden database instructions" in response.text
