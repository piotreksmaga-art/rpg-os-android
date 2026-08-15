#!/usr/bin/env python3
"""CHAT-7 TEMP GM localhost bridge.

Non-production test harness. Never authoritative game state.
Uses Python stdlib only so it can run in Termux without extra pip packages.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

HOST = os.environ.get("TGM_BRIDGE_HOST", "127.0.0.1")
PORT = int(os.environ.get("TGM_BRIDGE_PORT", "8765"))
DATA_DIR = Path(os.path.expanduser(os.environ.get("TGM_DATA_DIR", "~/rpgos-temp-gm/bridge-data")))
QWEN35_URL = os.environ.get("TGM_QWEN35_URL", "http://127.0.0.1:8768").rstrip("/")

if HOST != "127.0.0.1":
    raise SystemExit("SECURITY: TEMP GM bridge must bind to 127.0.0.1 only")

DATA_DIR.mkdir(parents=True, exist_ok=True)
STATE_FILE = DATA_DIR / "state.json"
BUG_DIR = DATA_DIR / "pending-bugs"
BUG_DIR.mkdir(parents=True, exist_ok=True)

SYSTEM_PROMPT = """You are TEMP AI GM for RPG OS manual testing.
You are not authoritative game state.
Use only supplied campaign facts as established truth.
Never claim that stats, skills, inventory, money, ownership, projects or permanent state changed unless RPG OS explicitly reports the change as confirmed.
If a requested mechanic is unavailable, continue narratively when reasonable but classify the result TEST_FALLBACK.
Do not rewrite established campaign history.
NPCs may only know information they could observe, infer or were told.
Respect supplied World Pack.
Do not invent hidden player abilities as established facts.
Your job is to make manual testing playable, not to replace RPG OS.
Return natural Polish unless the player explicitly uses another language.
"""

PROVIDERS = {
    "QWEN3_5_4B": {
        "id": "QWEN3_5_4B",
        "name": "Qwen3.5 4B",
        "runtime": "llama.cpp",
        "runtime_url": QWEN35_URL,
    }
}


def _load_state() -> dict[str, Any]:
    if STATE_FILE.exists():
        try:
            raw = json.loads(STATE_FILE.read_text(encoding="utf-8"))
            if raw.get("activeProvider") in PROVIDERS:
                return raw
        except Exception:
            pass
    return {"activeProvider": "QWEN3_5_4B"}


def _save_state(state: dict[str, Any]) -> None:
    tmp = STATE_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(STATE_FILE)


def _runtime_health(url: str, timeout: float = 1.5) -> bool:
    try:
        with urllib.request.urlopen(url + "/health", timeout=timeout) as r:
            body = json.loads(r.read().decode("utf-8"))
            return r.status == 200 and body.get("status") == "ok"
    except Exception:
        return False


def _provider_view(pid: str) -> dict[str, Any]:
    p = dict(PROVIDERS[pid])
    p["status"] = "READY" if _runtime_health(p["runtime_url"]) else "OFFLINE"
    p.pop("runtime_url", None)
    return p


def _post_json(url: str, payload: dict[str, Any], timeout: float = 180.0) -> dict[str, Any]:
    req = urllib.request.Request(
        url,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def _sanitize_text(value: Any, max_len: int = 12000) -> str:
    text = str(value or "")[:max_len]
    text = re.sub(r"(?i)(token|authorization|password|secret)\s*[:=]\s*\S+", r"\1=[REDACTED]", text)
    text = re.sub(r"gh[opsu]_[A-Za-z0-9_\-]{20,}", "[REDACTED_GITHUB_TOKEN]", text)
    return text


def _normalize_symptom(text: str) -> str:
    text = text.lower()
    text = re.sub(r"\d+", "#", text)
    text = re.sub(r"[^a-ząćęłńóśźż0-9# ]+", " ", text)
    return " ".join(text.split())[:300]


def _bug_fingerprint(report: dict[str, Any]) -> str:
    parts = [
        str(report.get("build", {}).get("versionName", "")),
        str(report.get("build", {}).get("versionCode", "")),
        str(report.get("route", "")),
        str(report.get("exceptionClass", "")),
        "|".join(report.get("topStackFrames", [])[:5]),
        _normalize_symptom(str(report.get("userReport", ""))),
    ]
    return hashlib.sha256("\n".join(parts).encode("utf-8")).hexdigest()[:20]


class Handler(BaseHTTPRequestHandler):
    server_version = "RpgOsTempGmBridge/0.3"

    def log_message(self, fmt: str, *args: Any) -> None:
        print(f"[bridge] {self.address_string()} {fmt % args}")

    def _json(self, status: int, body: dict[str, Any]) -> None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def _read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > 256_000:
            raise ValueError("invalid content length")
        raw = self.rfile.read(length)
        value = json.loads(raw.decode("utf-8"))
        if not isinstance(value, dict):
            raise ValueError("JSON object required")
        return value

    def do_GET(self) -> None:
        if self.path == "/health":
            state = _load_state()
            active = state["activeProvider"]
            self._json(200, {"status": "ok", "bridge": "READY", "activeProvider": active, "provider": _provider_view(active)})
            return
        if self.path == "/providers":
            self._json(200, {"providers": [_provider_view(pid) for pid in PROVIDERS]})
            return
        if self.path == "/active-provider":
            state = _load_state()
            self._json(200, {"activeProvider": state["activeProvider"], "provider": _provider_view(state["activeProvider"])})
            return
        self._json(404, {"error": "not_found"})

    def do_POST(self) -> None:
        try:
            body = self._read_json()
        except Exception as e:
            self._json(400, {"error": "bad_request", "detail": str(e)})
            return

        if self.path == "/active-provider":
            pid = str(body.get("providerId", ""))
            if pid not in PROVIDERS:
                self._json(400, {"error": "unknown_provider"})
                return
            state = {"activeProvider": pid}
            _save_state(state)
            self._json(200, {"activeProvider": pid, "provider": _provider_view(pid)})
            return

        if self.path == "/gm/turn":
            self._gm_turn(body)
            return

        if self.path == "/bug":
            self._bug(body)
            return

        self._json(404, {"error": "not_found"})

    def _gm_turn(self, body: dict[str, Any]) -> None:
        state = _load_state()
        pid = state["activeProvider"]
        provider = PROVIDERS[pid]
        if not _runtime_health(provider["runtime_url"]):
            self._json(503, {"error": "provider_offline", "providerId": pid, "mode": "TEST_FALLBACK", "canonicalMutation": False})
            return

        user_message = _sanitize_text(body.get("message", ""), 8000)
        if not user_message:
            self._json(400, {"error": "message_required"})
            return

        snapshot = body.get("context", {})
        if not isinstance(snapshot, dict):
            self._json(400, {"error": "context_must_be_object"})
            return

        requested_mode = str(body.get("mode", "NARRATIVE_ONLY"))
        if requested_mode not in {"NARRATIVE_ONLY", "ENGINE_CONFIRMED", "TEST_FALLBACK"}:
            requested_mode = "NARRATIVE_ONLY"

        safe_context = {
            "campaignUid": snapshot.get("campaignUid"),
            "worldPackUid": snapshot.get("worldPackUid"),
            "playerIdentity": snapshot.get("playerIdentity"),
            "currentScene": snapshot.get("currentScene"),
            "currentLocation": snapshot.get("currentLocation"),
            "visiblePlayerStatus": snapshot.get("visiblePlayerStatus"),
            "relevantNpcFacts": snapshot.get("relevantNpcFacts"),
            "availableTestCapabilities": snapshot.get("availableTestCapabilities"),
            "engineConfirmedResults": snapshot.get("engineConfirmedResults"),
            "recentMessages": snapshot.get("recentMessages", [])[-8:] if isinstance(snapshot.get("recentMessages", []), list) else [],
        }

        system_content = (
            SYSTEM_PROMPT.rstrip()
            + "\n\nREAD-ONLY RPG OS CONTEXT:\n"
            + json.dumps(safe_context, ensure_ascii=False)
            + "\n\nResponse classification for this turn: "
            + requested_mode
            + ". Never imply a canonical mutation unless engineConfirmedResults explicitly supports it."
        )
        messages = [
            {"role": "system", "content": system_content},
            {"role": "user", "content": user_message},
        ]
        request_payload: dict[str, Any] = {
            "messages": messages,
            "temperature": 0.7,
            "top_p": 0.8,
            "top_k": 20,
            "stream": False,
            "chat_template_kwargs": {"enable_thinking": False},
        }
        # No arbitrary completion cap by default. A caller may opt into a cap
        # explicitly for benchmark or UI latency experiments.
        if body.get("maxTokens") is not None:
            request_payload["max_tokens"] = int(body["maxTokens"])

        try:
            raw = _post_json(provider["runtime_url"] + "/v1/chat/completions", request_payload)
            narrative = raw["choices"][0]["message"]["content"]
            usage = raw.get("usage", {})
        except urllib.error.HTTPError as e:
            try:
                detail = _sanitize_text(e.read().decode("utf-8", errors="replace"), 4000)
            except Exception:
                detail = "HTTPError"
            self._json(502, {"error": "runtime_failure", "detail": detail, "providerId": pid, "mode": "TEST_FALLBACK", "canonicalMutation": False})
            return
        except (urllib.error.URLError, TimeoutError, KeyError, ValueError, json.JSONDecodeError) as e:
            self._json(502, {"error": "runtime_failure", "detail": type(e).__name__, "providerId": pid, "mode": "TEST_FALLBACK", "canonicalMutation": False})
            return

        self._json(200, {
            "providerId": pid,
            "mode": requested_mode,
            "narrative": narrative,
            "canonicalMutation": False,
            "usage": usage,
        })

    def _bug(self, body: dict[str, Any]) -> None:
        description = _sanitize_text(body.get("description") or body.get("userReport"), 12000)
        if not description:
            self._json(400, {"error": "description_required"})
            return

        report = {
            "schemaVersion": 1,
            "status": "PENDING_USER_SUBMISSION",
            "timestampUnixMs": int(time.time() * 1000),
            "USER-SUPPLIED": {"description": description},
            "DEVICE-CAPTURED": {
                "build": body.get("build", {}),
                "campaignUid": body.get("campaignUid"),
                "worldPackUid": body.get("worldPackUid"),
                "route": body.get("route"),
                "providerId": _load_state()["activeProvider"],
                "bridgeStatus": "READY",
                "llamaStatus": "READY" if _runtime_health(PROVIDERS[_load_state()["activeProvider"]]["runtime_url"]) else "OFFLINE",
                "adbStatus": body.get("adbStatus", "UNKNOWN"),
                "recentSafeActions": body.get("recentSafeActions", [])[-12:] if isinstance(body.get("recentSafeActions", []), list) else [],
                "recentGmResponses": body.get("recentGmResponses", [])[-6:] if isinstance(body.get("recentGmResponses", []), list) else [],
                "logcatExcerpt": _sanitize_text(body.get("logcatExcerpt", ""), 20000),
                "screenshotApproved": bool(body.get("screenshotApproved", False)),
            },
            "AI-SUMMARIZED": body.get("aiSummary"),
            "githubSubmissionAuthorized": False,
        }
        flat = {
            "build": report["DEVICE-CAPTURED"].get("build", {}),
            "route": report["DEVICE-CAPTURED"].get("route"),
            "userReport": description,
            "exceptionClass": body.get("exceptionClass"),
            "topStackFrames": body.get("topStackFrames", []),
        }
        fingerprint = _bug_fingerprint(flat)
        report["fingerprint"] = fingerprint
        path = BUG_DIR / f"{report['timestampUnixMs']}-{fingerprint}.json"
        path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        self._json(201, {"status": report["status"], "fingerprint": fingerprint, "pendingPath": str(path), "githubSubmissionAuthorized": False})


def main() -> None:
    print(f"TEMP GM bridge listening on http://{HOST}:{PORT}")
    print("NON-AUTHORITATIVE: canonicalMutation is always false in this bridge")
    httpd = ThreadingHTTPServer((HOST, PORT), Handler)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()


if __name__ == "__main__":
    main()
