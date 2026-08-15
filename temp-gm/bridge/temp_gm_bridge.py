#!/usr/bin/env python3
"""CHAT-7 TEMP GM localhost bridge.

Non-production test harness. Never authoritative game state.
Uses Python stdlib only so it can run in Termux without extra pip packages.
"""
from __future__ import annotations

import json
import os
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse

from temp_bug_harness import BugReportStore, build_bug_bundle
from temp_bug_ui_contract import (
    apply_user_decision,
    consume_authorization,
    control_bug_report,
    delete_report,
    list_pending_reports,
    list_reports,
    record_linked_duplicate,
    record_submitted,
    report_detail,
    report_preview,
    retry_report,
    set_duplicates,
)
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

BUG_ROUTE_RE = re.compile(r"^/bugs/([A-Za-z0-9._-]+)(?:/(preview|duplicates|decision|retry|cancel|submission-authorization|submitted|linked-duplicate))?$")


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
    server_version = "RpgOsTempGmBridge/0.7"

    def log_message(self, fmt: str, *args: Any) -> None:
        print(f"[bridge] {self.address_string()} {fmt % args}")

    def _json(self, status: int, body: dict[str, Any]) -> None:
        body.setdefault("canonicalMutation", False)
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def _read_json(self, *, allow_empty: bool = False) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0"))
        if length == 0 and allow_empty:
            return {}
        if length <= 0 or length > 256_000:
            raise ValueError("invalid content length")
        raw = self.rfile.read(length)
        value = json.loads(raw.decode("utf-8"))
        if not isinstance(value, dict):
            raise ValueError("JSON object required")
        return value

    def _parsed_path(self):
        return urlparse(self.path)

    def _bug_route(self) -> tuple[str, str | None] | None:
        match = BUG_ROUTE_RE.fullmatch(self._parsed_path().path)
        if not match:
            return None
        return match.group(1), match.group(2)

    def _lifecycle_error(self, error: Exception) -> None:
        if isinstance(error, FileNotFoundError):
            self._json(404, {"error": "bug_report_not_found"})
        elif isinstance(error, ValueError):
            detail = str(error)
            conflict_markers = {
                "submitted_report_cannot_retry",
                "submitted_report_cannot_delete",
                "new_issue_submission_not_ready",
                "submission_authorization_not_consumed",
                "duplicate_link_not_ready",
                "duplicate_link_authorization_not_consumed",
                "duplicate_issue_mismatch",
                "terminal_report_requires_retry_or_new_report",
            }
            self._json(409 if detail in conflict_markers else 400, {"error": "bug_lifecycle_rejected", "detail": detail})
        else:
            self._json(500, {"error": "bug_lifecycle_failed", "detail": type(error).__name__})

    def do_GET(self) -> None:
        parsed = self._parsed_path()
        path = parsed.path
        if path == "/health":
            state = _load_state()
            active = state["activeProvider"]
            self._json(200, {
                "status": "ok",
                "bridge": "READY",
                "activeProvider": active,
                "provider": _provider_view(active),
            })
            return
        if path == "/providers":
            self._json(200, {"providers": [_provider_view(pid) for pid in PROVIDERS]})
            return
        if path == "/active-provider":
            state = _load_state()
            self._json(200, {
                "activeProvider": state["activeProvider"],
                "provider": _provider_view(state["activeProvider"]),
            })
            return
        if path == "/bug/pending":  # legacy alias
            self._json(200, list_pending_reports(BUG_STORE))
            return
        if path == "/bugs":
            query = parse_qs(parsed.query)
            pending_only = query.get("scope", ["pending"])[0].lower() != "all"
            self._json(200, list_reports(BUG_STORE, pending_only=pending_only))
            return

        route = self._bug_route()
        if route:
            report_uid, action = route
            try:
                if action is None:
                    self._json(200, report_detail(BUG_STORE, report_uid))
                    return
                if action == "preview":
                    self._json(200, report_preview(BUG_STORE, report_uid))
                    return
            except Exception as error:
                self._lifecycle_error(error)
                return
        self._json(404, {"error": "not_found"})

    def do_POST(self) -> None:
        try:
            body = self._read_json(allow_empty=True)
        except Exception as error:
            self._json(400, {"error": "bad_request", "detail": str(error)})
            return

        path = self._parsed_path().path
        if path == "/active-provider":
            pid = str(body.get("providerId", ""))
            if pid not in PROVIDERS:
                self._json(400, {"error": "unknown_provider"})
                return
            _save_state({"activeProvider": pid})
            self._json(200, {"activeProvider": pid, "provider": _provider_view(pid)})
            return
        if path == "/gm/turn":
            self._gm_turn(body)
            return
        if path == "/bug":
            self._bug(body)
            return
        if path == "/bug/control":  # legacy alias
            self._bug_control(body)
            return

        route = self._bug_route()
        if route:
            report_uid, action = route
            try:
                if action == "duplicates":
                    self._json(200, set_duplicates(BUG_STORE, report_uid, body.get("candidates", [])))
                    return
                if action == "decision":
                    self._json(200, apply_user_decision(BUG_STORE, report_uid, body))
                    return
                if action == "retry":
                    self._json(200, retry_report(BUG_STORE, report_uid))
                    return
                if action == "cancel":
                    self._json(200, apply_user_decision(BUG_STORE, report_uid, {"decision": "CANCEL"}))
                    return
                if action == "submission-authorization":
                    result = consume_authorization(BUG_STORE, report_uid, body.get("kind"))
                    self._json(200 if result.get("allowed") else 409, result)
                    return
                if action == "submitted":
                    self._json(200, record_submitted(BUG_STORE, report_uid, body))
                    return
                if action == "linked-duplicate":
                    self._json(200, record_linked_duplicate(BUG_STORE, report_uid, body))
                    return
            except Exception as error:
                self._lifecycle_error(error)
                return
        self._json(404, {"error": "not_found"})

    def do_DELETE(self) -> None:
        route = self._bug_route()
        if not route or route[1] is not None:
            self._json(404, {"error": "not_found"})
            return
        report_uid, _ = route
        query = parse_qs(self._parsed_path().query)
        confirmed = query.get("confirm", ["false"])[0].lower() == "true"
        try:
            self._json(200, delete_report(BUG_STORE, report_uid, confirmed=confirmed))
        except Exception as error:
            self._lifecycle_error(error)

    def _gm_turn(self, body: dict[str, Any]) -> None:
        state = _load_state()
        pid = state["activeProvider"]
        provider = PROVIDERS[pid]
        if provider.status() != "READY":
            self._json(503, {
                "error": "provider_offline",
                "providerId": pid,
                "mode": "TEST_FALLBACK",
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
            self._json(400, {"error": "bug_report_rejected", "detail": str(error)})
            return
        except RuntimeError as error:
            self._json(507, {"error": "bug_queue_unavailable", "detail": str(error)})
            return
        except Exception as error:
            self._json(500, {"error": "bug_capture_failed", "detail": type(error).__name__})
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
            "errors": [logcat.get("reason")] if logcat.get("reason") else [],
        })

    def _bug_control(self, body: dict[str, Any]) -> None:
        """Legacy local report control alias. No GitHub write capability exists here."""
        try:
            result = control_bug_report(BUG_STORE, body)
        except Exception as error:
            self._lifecycle_error(error)
            return
        self._json(200, result)


def main() -> None:
    print(f"TEMP GM bridge listening on http://{HOST}:{PORT}")
    print(f"TEMP provider: {DEFAULT_PROVIDER_ID} -> {BIELIK_URL}")
    print("NON-AUTHORITATIVE: canonicalMutation is always false in this bridge")
    print("BUG HARNESS: BugReportStore owns lifecycle; bridge is transport only")
    print("GITHUB: bridge stores/consumes explicit authorization only; no GitHub credentials or writer")
    httpd = ThreadingHTTPServer((HOST, PORT), Handler)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()


if __name__ == "__main__":
    main()
