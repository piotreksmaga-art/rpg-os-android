#!/usr/bin/env python3
"""Stdlib-only self-test for CHAT-7 TEMP GM integration modules."""

from __future__ import annotations

import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import temp_context_builder as context_builder
import temp_gm_provider as provider


class FakeLlamaHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def do_GET(self):
        if self.path == "/health":
            data = b'{"status":"ok"}'
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        self.send_response(404)
        self.end_headers()

    def do_POST(self):
        if self.path != "/v1/chat/completions":
            self.send_response(404)
            self.end_headers()
            return
        length = int(self.headers.get("Content-Length", "0"))
        request = json.loads(self.rfile.read(length).decode("utf-8"))
        assert request["messages"][0]["role"] == "system"
        body = {
            "choices": [{"message": {"content": "Narracja testowa."}}],
            "usage": {"prompt_tokens": 123, "completion_tokens": 5},
        }
        data = json.dumps(body).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def assert_true(value, message):
    if not value:
        raise AssertionError(message)


def main() -> int:
    assert_true(context_builder.CTX_WINDOW == 8192, "CTX must be 8192")
    assert_true(sum(context_builder.SEGMENT_BUDGETS.values()) == 8192, "budget must sum to 8192")

    snapshot = {
        "campaignUid": "c1",
        "worldPackUid": "w1",
        "sceneState": {"gmSecret": "SECRET-A", "location": "gate"},
        "playerSceneState": {"visibleInjury": "left hand"},
        "relevantNpcs": [{
            "npcUid": "ren",
            "sceneFacts": {"position": "north"},
            "knowledge": {"observed": ["player entered"], "heard": [], "told": [], "inferred": []},
            "globalWorldState": {"forbidden": "SECRET-B"},
        }],
        "recentDialogueActions": [{"text": "hello"}],
        "retrievedChronicleMemory": [],
        "engineConfirmedResults": [],
    }
    built = context_builder.build_context(snapshot)
    npc = built["relevantNpcs"][0]
    assert_true("globalWorldState" not in npc, "global world state leaked into NPC view")
    assert_true("SECRET-A" not in str(npc["knowledge"]), "scene secret leaked into NPC knowledge")
    assert_true("SECRET-B" not in str(npc["knowledge"]), "global secret leaked into NPC knowledge")

    messages = context_builder.build_messages(snapshot, "Co widzę?", "NARRATIVE_ONLY")
    assert_true(len(messages) == 2, "expected system+user messages")
    assert_true("canonicalMutation=false" in messages[0]["content"], "authority marker missing")

    server = ThreadingHTTPServer(("127.0.0.1", 0), FakeLlamaHandler)
    port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        bielik = provider.LocalBielikTempGmProvider(f"http://127.0.0.1:{port}", timeout_seconds=5)
        assert_true(bielik.provider_id == "BIELIK_4_5B_V3", "provider id mismatch")
        assert_true(bielik.status() == "READY", "provider health failed")
        result = bielik.generate(messages=messages, mode="NARRATIVE_ONLY", max_tokens=64).as_dict()
        assert_true(result["narrative"] == "Narracja testowa.", "narrative mismatch")
        assert_true(result["canonicalMutation"] is False, "TEMP response became authoritative")
        assert_true("statePatch" not in result, "forbidden StatePatch surfaced")
    finally:
        server.shutdown()
        server.server_close()

    print("TEMP_GM_SELFTEST=PASS")
    print("PROVIDER_ID=BIELIK_4_5B_V3")
    print("CTX=8192")
    print("CANONICAL_MUTATION=false")
    print("NPC_KNOWLEDGE_ISOLATION=PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
