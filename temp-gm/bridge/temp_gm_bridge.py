#!/usr/bin/env python3
"""CHAT-7 TEMP GM localhost bridge.

Non-production test harness. Never authoritative game state.
Uses Python stdlib only so it can run in Termux without extra pip packages.
"""

from __future__ import annotations

import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

from temp_bug_harness import BugReportStore, build_bug_bundle
from temp_bug_ui_contract import control_bug_report, list_pending_reports
from temp_context_builder import build_messages
from temp_gm_provider import LocalBielikTempGmProvider, RESPONSE_MODES, provider_error_payload

HOST = os.environ.get("TGM_BRIDGE_HOST", "127.0.0.1")
PORT = int(os.environ.get("TGM_BRIDGE_PORT", "8765"))
DATA_DIR = Path(os.path.expanduser(os.environ.get("TGM_DATA_DIR", "~/rpgos-temp-gm/bridge-data")))
BIELIK_URL = os.environ.get("TGM_BIELIK_URL", "http://127.0.0.1:8768").rstrip("/")

if HOST != "127.0.0.1":
    raise SystemExit("SECURITY: TEMP GM bridge must bind to 127.0.0.1 only")
if not BIELIK_URL.startswith("http://127.0.0.1:"):
    raise SystemExit("SECURITY: TEMP Bielik runtime must use 127.0.0.1 only")

DATA_DIR.mkdir(parents=True, exist_ok=True)
STATE_FILE = DATA_DIR / "state.json"
BUG_STORE = BugReportStore(DATA_DIR)

BIELIK = LocalBielikTempGmProvider(BIELIK_URL)
PROVIDERS = {BIELIK.provider_id: BIELIK}
DEFAULT_PROVIDER_ID = BIELIK.provider_id


def _load_state() -> dict[str, Any]:
    if STATE_FILE.exists():
        try:
            raw = json.loads(STATE_FILE.read_text(encoding="utf-8"))
            if raw.get("activeProvider") in PROVIDERS:
                return raw
        except Exception:
            pass
    return {"activeProvider": DEFAULT_PROVIDER_ID}


def _save_state(state: dict[str, Any]) -> None:
    tmp = STATE_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(STATE_FILE)


def _provider_view(pid: str) -> dict[str, Any]:
    provider = PROVIDERS[pid]
    view = provider.metadata()
    view["status"] = provider.status()
    return view


class Handler(BaseHTTPRequestHandler):
    server_version = "RpgOsTempGmBridge/0.6"

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
            self._json(200, {
                "status": "ok",
                "bridge": "READY",
                "activeProvider": active,
                "provider": _provider_view(active),
                "canonicalMutation": False,
            })
            return
        if self.path == "/providers":
            self._json(200, {"providers": [_provider_view(pid) for pid in PROVIDERS]})
            return
        if self.path == "/active-provider":
            state = _load_state()
            self._json(200, {
                "activeProvider": state["activeProvider"],
                "provider": _provider_view(state["activeProvider"]),
            })
            return
        if self.path == "/bug/pending":
            self._json(200, list_pending_reports(BUG_STORE))
            return
        self._json(404, {"error": "not_found"})

    def do_POST(self) -> None:
        try:
            body = self._read_json()
        except Exception as error:
            self._json(400, {"error": "bad_request", "detail": str(error)})
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

        if self.path == "/bug/control":
            self._bug_control(body)
            return

        self._json(404, {"error": "not_found"})

    def _gm_turn(self, body: dict[str, Any]) -> None:
        state = _load_state()
        pid = state["activeProvider"]
        provider = PROVIDERS[pid]
        if provider.status() != "READY":
            self._json(503, {
                "error": "provider_offline",
                "providerId": pid,
                "mode": "TEST_FALLBACK",
                "canonicalMutation": False,
            })
            return

        user_message = str(body.get("message", ""))[:8000]
        if not user_message:
            self._json(400, {"error": "message_required"})
            return

        snapshot = body.get("context", {})
        if not isinstance(snapshot, dict):
            self._json(400, {"error": "context_must_be_object"})
            return

        requested_mode = str(body.get("mode", "NARRATIVE_ONLY"))
        if requested_mode not in RESPONSE_MODES:
            requested_mode = "NARRATIVE_ONLY"

        try:
            messages = build_messages(snapshot, user_message, requested_mode)
        except ValueError as error:
            self._json(400, {
                "error": "context_rejected",
                "detail": str(error),
                "providerId": pid,
                "canonicalMutation": False,
            })
            return

        max_tokens = body.get("maxTokens")
        try:
            result = provider.generate(
                messages=messages,
                mode=requested_mode,
                max_tokens=int(max_tokens) if max_tokens is not None else 1024,
            )
        except Exception as error:
            self._json(502, provider_error_payload(pid, error))
            return

        self._json(200, result.as_dict())

    def _bug(self, body: dict[str, Any]) -> None:
        """Local capture only. This endpoint has no GitHub write capability."""
        state = _load_state()
        provider = PROVIDERS[state["activeProvider"]]
        try:
            provider_status = provider.status()
        except Exception:
            provider_status = "OFFLINE"
        try:
            report = build_bug_bundle(
                body,
                provider_id=state["activeProvider"],
                provider_status=provider_status,
                bridge_status="READY",
                store=BUG_STORE,
            )
        except ValueError as error:
            self._json(400, {"error": "bug_report_rejected", "detail": str(error), "canonicalMutation": False})
            return
        except RuntimeError as error:
            self._json(507, {"error": "bug_queue_unavailable", "detail": str(error), "canonicalMutation": False})
            return
        except Exception as error:
            # Fail degraded: do not touch canonical state. Caller retains the original report text.
            self._json(500, {"error": "bug_capture_failed", "detail": type(error).__name__, "canonicalMutation": False})
            return

        logcat = report["DEVICE-CAPTURED"]["logcat"]
        screenshot = report["DEVICE-CAPTURED"]["screenshot"]
        self._json(201, {
            "reportUid": report["reportUid"],
            "captureStatus": {
                "localBundle": "SAVED",
                "logcat": logcat.get("status"),
                "screenshot": "REFERENCED" if screenshot.get("reference") else "NOT_CAPTURED",
            },
            "duplicateFingerprint": report["duplicateFingerprint"],
            "submissionState": report["submissionState"],
            "githubSubmissionAuthorized": False,
            "canonicalMutation": False,
            "errors": [logcat.get("reason")] if logcat.get("reason") else [],
        })

    def _bug_control(self, body: dict[str, Any]) -> None:
        """Local report UI control only. No GitHub write capability exists here."""
        try:
            result = control_bug_report(BUG_STORE, body)
        except FileNotFoundError:
            self._json(404, {"error": "bug_report_not_found", "canonicalMutation": False})
            return
        except ValueError as error:
            self._json(400, {"error": "bug_control_rejected", "detail": str(error), "canonicalMutation": False})
            return
        except Exception as error:
            self._json(500, {"error": "bug_control_failed", "detail": type(error).__name__, "canonicalMutation": False})
            return
        self._json(200, result)


def main() -> None:
    print(f"TEMP GM bridge listening on http://{HOST}:{PORT}")
    print(f"TEMP provider: {DEFAULT_PROVIDER_ID} -> {BIELIK_URL}")
    print("NON-AUTHORITATIVE: canonicalMutation is always false in this bridge")
    print("BUG HARNESS: local pending/control only; GitHub submission requires a separate explicit user-confirmed privileged action")
    httpd = ThreadingHTTPServer((HOST, PORT), Handler)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()


if __name__ == "__main__":
    main()
