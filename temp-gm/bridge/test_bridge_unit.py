import importlib.util
from pathlib import Path

HERE = Path(__file__).resolve().parent


def _load(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


bridge = _load("bridge", "temp_gm_bridge.py")
provider = _load("provider", "temp_gm_provider.py")
context_builder = _load("context_builder", "temp_context_builder.py")


def test_host_is_localhost_only():
    assert bridge.HOST == "127.0.0.1"


def test_only_final_bielik_temp_provider_registered():
    assert set(bridge.PROVIDERS) == {"BIELIK_4_5B_V3"}
    assert bridge.DEFAULT_PROVIDER_ID == "BIELIK_4_5B_V3"


def test_provider_metadata_locks_final_profile():
    metadata = bridge.BIELIK.metadata()
    assert metadata["quantization"] == "Q4_K_M"
    assert metadata["backend"] == "Vulkan"
    assert metadata["contextWindow"] == 8192
    assert metadata["kvKey"] == "f16"
    assert metadata["kvValue"] == "f16"
    assert metadata["batch"] == 64
    assert metadata["ubatch"] == 64
    assert metadata["parallel"] == 1
    assert metadata["gpuLayers"] == 99


def test_response_contract_is_never_authoritative():
    response = provider.TempGmResponse(
        provider_id="BIELIK_4_5B_V3",
        mode="NARRATIVE_ONLY",
        narrative="test",
        usage={},
    ).as_dict()
    assert response["canonicalMutation"] is False
    assert "statePatch" not in response
    assert "playerChangeSet" not in response


def test_context_budget_is_native_8192():
    assert context_builder.CTX_WINDOW == 8192
    assert context_builder.RESPONSE_RESERVE == 1024
    assert sum(context_builder.SEGMENT_BUDGETS.values()) == 8192


def test_npc_knowledge_is_explicitly_isolated():
    snapshot = {
        "sceneState": {"gmOnlySecret": "SECRET_A"},
        "relevantNpcs": [
            {
                "npcUid": "npc-1",
                "sceneFacts": {"position": "gate"},
                "knowledge": {"observed": ["rain"], "heard": [], "told": [], "inferred": []},
                "globalWorldState": {"forbidden": "SECRET_B"},
            }
        ],
    }
    built = context_builder.build_context(snapshot)
    npc = built["relevantNpcs"][0]
    assert npc["knowledge"]["observed"] == ["rain"]
    assert "globalWorldState" not in npc
    assert "SECRET_A" not in str(npc["knowledge"])
    assert "SECRET_B" not in str(npc["knowledge"])


def test_oldest_dialogue_trimmed_first_when_segment_is_large():
    items = [{"i": i, "text": "x" * 900} for i in range(20)]
    built = context_builder.build_context({"recentDialogueActions": items})
    kept = built["recentDialogueActions"]
    assert kept
    assert kept[-1]["i"] == 19
    assert kept[0]["i"] > 0


def test_unknown_mode_normalizes_to_narrative_only():
    assert "NARRATIVE_ONLY" in provider.RESPONSE_MODES
    result = provider.TempGmResponse("BIELIK_4_5B_V3", "NARRATIVE_ONLY", "x", {}).as_dict()
    assert result["mode"] == "NARRATIVE_ONLY"


def test_normalize_symptom_is_deterministic():
    a = bridge._normalize_symptom("Przycisk NIE działa 123!")
    b = bridge._normalize_symptom("Przycisk NIE działa 456!")
    assert a == b


def test_fingerprint_is_deterministic():
    report = {
        "build": {"versionName": "x", "versionCode": 1},
        "route": "save",
        "userReport": "button broken",
        "exceptionClass": "IllegalStateException",
        "topStackFrames": ["a", "b"],
    }
    assert bridge._bug_fingerprint(report) == bridge._bug_fingerprint(report)


def test_redacts_github_tokens():
    text = bridge._sanitize_text("token=gho_abcdefghijklmnopqrstuvwxyz123456")
    assert "gho_" not in text
