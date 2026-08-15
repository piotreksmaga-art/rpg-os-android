import hashlib
import importlib.util
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("bridge", HERE / "temp_gm_bridge.py")
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)


def test_host_is_localhost_only():
    assert bridge.HOST == "127.0.0.1"


def test_only_temp_provider_registered():
    assert set(bridge.PROVIDERS) == {"QWEN3_5_4B"}


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


def test_authority_prompt_contains_guardrails():
    assert "not authoritative game state" in bridge.SYSTEM_PROMPT
    assert "Never claim that stats" in bridge.SYSTEM_PROMPT
    assert "NPCs may only know" in bridge.SYSTEM_PROMPT
