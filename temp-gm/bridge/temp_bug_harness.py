#!/usr/bin/env python3
"""TEMP bug-reporting harness for WORK-20260815-001.

Non-authoritative, local-first, user-authorized. No GitHub write happens here.
Python stdlib only for Termux compatibility.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import time
from copy import deepcopy
from pathlib import Path
from typing import Any

SUBMISSION_STATES = {"LOCAL_PENDING", "READY", "SUBMITTED", "LINKED_DUPLICATE", "CANCELLED"}
MAX_PENDING_REPORTS = 100
MAX_USER_REPORT_CHARS = 12000
MAX_ACTIONS = 12
MAX_GM_RESPONSES = 6
MAX_LOGCAT_LINES = 300
MAX_LOGCAT_CHARS = 30000
LOGCAT_TIMEOUT_SECONDS = 6

SECRET_PATTERNS = [
    re.compile(r"gh[opsu]_[A-Za-z0-9_\-]{20,}"),
    re.compile(r"(?i)(authorization\s*:\s*bearer\s+)[^\s]+"),
    re.compile(r"(?i)((?:api[_-]?key|token|password|secret|cookie)\s*[:=]\s*)[^\s,;]+"),
]


def redact_secrets(value: Any, max_chars: int = MAX_LOGCAT_CHARS) -> str:
    text = str(value or "").replace("\x00", "�")[:max_chars]
    for pattern in SECRET_PATTERNS:
        if pattern.pattern.startswith("gh"):
            text = pattern.sub("[REDACTED_GITHUB_TOKEN]", text)
        else:
            text = pattern.sub(lambda m: m.group(1) + "[REDACTED]", text)
    return text


def normalize_symptom(text: str) -> str:
    text = redact_secrets(text, 1200).lower()
    text = re.sub(r"\b\d{2,}\b", "#", text)
    text = re.sub(r"[^a-ząćęłńóśźż0-9# _-]+", " ", text)
    return " ".join(text.split())[:400]


def normalized_stack_frames(frames: Any) -> list[str]:
    if not isinstance(frames, list):
        return []
    result: list[str] = []
    for frame in frames[:5]:
        s = redact_secrets(frame, 500)
        s = re.sub(r":\d+", ":#", s)
        result.append(s.strip())
    return result


def duplicate_fingerprint(*, version_name: Any, version_code: Any, route: Any,
                          exception_class: Any, top_stack_frames: Any,
                          user_report: Any, stable_environment: Any = None) -> str:
    env = stable_environment if isinstance(stable_environment, dict) else {}
    stable_env = {
        "androidSdk": env.get("androidSdk"),
        "deviceModel": env.get("deviceModel"),
    }
    identity = {
        "versionName": str(version_name or ""),
        "versionCode": str(version_code or ""),
        "route": str(route or ""),
        "exceptionClass": str(exception_class or ""),
        "topStackFrames": normalized_stack_frames(top_stack_frames),
        "symptom": normalize_symptom(str(user_report or "")),
        "stableEnvironment": stable_env,
    }
    canonical = json.dumps(identity, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:24]


def _bounded_text_lines(text: str, max_lines: int = MAX_LOGCAT_LINES, max_chars: int = MAX_LOGCAT_CHARS) -> str:
    safe = redact_secrets(text, max_chars * 2)
    lines = safe.splitlines()[-max_lines:]
    return "\n".join(lines)[-max_chars:]


def capture_package_logcat(package_name: str, adb_command: str = "adb") -> dict[str, Any]:
    """One-shot bounded package-scoped capture. Never records whole logcat continuously."""
    package_name = re.sub(r"[^A-Za-z0-9._]", "", str(package_name or ""))
    if not package_name:
        return {"status": "SKIPPED", "reason": "package_missing", "excerpt": "", "lineLimit": MAX_LOGCAT_LINES}
    try:
        pid = subprocess.run(
            [adb_command, "shell", "pidof", package_name],
            capture_output=True, text=True, timeout=LOGCAT_TIMEOUT_SECONDS, check=False,
        ).stdout.strip().split()
        if not pid:
            return {"status": "UNAVAILABLE", "reason": "package_pid_unavailable", "excerpt": "", "lineLimit": MAX_LOGCAT_LINES}
        target_pid = pid[0]
        proc = subprocess.run(
            [adb_command, "logcat", "-d", "-v", "threadtime", "-t", str(MAX_LOGCAT_LINES), "--pid", target_pid],
            capture_output=True, timeout=LOGCAT_TIMEOUT_SECONDS, check=False,
        )
        raw = proc.stdout.decode("utf-8", errors="replace") if isinstance(proc.stdout, bytes) else str(proc.stdout or "")
        return {
            "status": "CAPTURED" if raw else "UNAVAILABLE",
            "reason": None if raw else "empty_logcat",
            "excerpt": _bounded_text_lines(raw),
            "lineLimit": MAX_LOGCAT_LINES,
            "timeoutSeconds": LOGCAT_TIMEOUT_SECONDS,
            "scope": "package_pid",
        }
    except (FileNotFoundError, subprocess.TimeoutExpired, OSError) as error:
        return {"status": "UNAVAILABLE", "reason": type(error).__name__, "excerpt": "", "lineLimit": MAX_LOGCAT_LINES}


class BugReportStore:
    def __init__(self, root: Path):
        self.root = Path(root)
        self.pending_dir = self.root / "pending-bugs"
        self.pending_dir.mkdir(parents=True, exist_ok=True)

    def _paths(self) -> list[Path]:
        return sorted(self.pending_dir.glob("*.json"))

    def _new_report_uid(self, fingerprint: str, created_ms: int) -> str:
        base = f"bug-{created_ms}-{fingerprint[:8]}"
        uid = base
        n = 1
        existing = {p.stem for p in self._paths()}
        while uid in existing:
            n += 1
            uid = f"{base}-{n}"
        return uid

    def save_new(self, report: dict[str, Any]) -> Path:
        if len(self._paths()) >= MAX_PENDING_REPORTS:
            raise RuntimeError("pending_queue_full")
        path = self.pending_dir / f"{report['reportUid']}.json"
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True), encoding="utf-8")
        tmp.replace(path)
        return path

    def load(self, report_uid: str) -> dict[str, Any]:
        safe_uid = re.sub(r"[^A-Za-z0-9._-]", "", report_uid)
        path = self.pending_dir / f"{safe_uid}.json"
        return json.loads(path.read_text(encoding="utf-8"))

    def update(self, report: dict[str, Any]) -> None:
        path = self.pending_dir / f"{report['reportUid']}.json"
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True), encoding="utf-8")
        tmp.replace(path)

    def list_reports(self) -> list[dict[str, Any]]:
        result = []
        for path in self._paths():
            try:
                result.append(json.loads(path.read_text(encoding="utf-8")))
            except Exception:
                continue
        return result


def build_bug_bundle(body: dict[str, Any], *, provider_id: str, provider_status: str,
                     bridge_status: str, store: BugReportStore,
                     capture_logcat: bool = False) -> dict[str, Any]:
    raw_description = body.get("description") if body.get("description") is not None else body.get("userReport")
    if not isinstance(raw_description, str) or not raw_description:
        raise ValueError("description_required")
    if len(raw_description) > MAX_USER_REPORT_CHARS:
        raise ValueError("description_too_long")

    # Preserve ordinary user wording verbatim; secrets are never persisted.
    persisted_description = redact_secrets(raw_description, MAX_USER_REPORT_CHARS)
    description_redacted = persisted_description != raw_description

    build = body.get("build") if isinstance(body.get("build"), dict) else {}
    runtime = body.get("runtime") if isinstance(body.get("runtime"), dict) else {}
    env = body.get("environment") if isinstance(body.get("environment"), dict) else {}
    route = body.get("route")
    exception_class = body.get("exceptionClass")
    stack = normalized_stack_frames(body.get("topStackFrames", []))

    fingerprint = duplicate_fingerprint(
        version_name=build.get("versionName"), version_code=build.get("versionCode"),
        route=route, exception_class=exception_class, top_stack_frames=stack,
        user_report=persisted_description, stable_environment=env,
    )
    created_ms = int(time.time() * 1000)
    report_uid = store._new_report_uid(fingerprint, created_ms)

    include_logcat = bool(body.get("include_logcat", False) or capture_logcat)
    if include_logcat:
        if body.get("logcatExcerpt") is not None:
            logcat = {
                "status": "CALLER_SUPPLIED_BOUNDED",
                "reason": None,
                "excerpt": _bounded_text_lines(str(body.get("logcatExcerpt", ""))),
                "lineLimit": MAX_LOGCAT_LINES,
                "scope": "package_pid_expected",
            }
        else:
            logcat = capture_package_logcat(str(body.get("packageName", "")))
    else:
        logcat = {"status": "NOT_REQUESTED", "reason": None, "excerpt": "", "lineLimit": MAX_LOGCAT_LINES}

    screenshot_requested = bool(body.get("include_screenshot", False))
    screenshot_approved = bool(body.get("screenshotApproved", False))
    screenshot_ref = redact_secrets(body.get("screenshotReference", ""), 1000) if screenshot_requested and screenshot_approved else ""

    user_supplied = {
        "originalReport": persisted_description,
        "originalReportRedactedForSecretSafety": description_redacted,
        "expected": redact_secrets(body.get("expected", ""), 6000),
        "actual": redact_secrets(body.get("actual", ""), 6000),
        "reproducibilityNotes": redact_secrets(body.get("reproducibilityNotes", ""), 8000),
    }
    device_captured = {
        "app": {
            "versionName": build.get("versionName"),
            "versionCode": build.get("versionCode"),
            "buildSha": build.get("buildSha") or build.get("runtimeSha"),
        },
        "campaignUid": body.get("campaignUid"),
        "worldPackUid": body.get("worldPackUid"),
        "route": route,
        "tempProviderId": provider_id,
        "tempResponseMode": body.get("responseMode"),
        "bridgeState": bridge_status,
        "llamaState": provider_status,
        "adbState": body.get("adbStatus", "UNKNOWN"),
        "runtime": {
            "llamaSha": runtime.get("llamaSha"),
            "backend": runtime.get("backend"),
        },
        "recentSafeActions": deepcopy(body.get("recentSafeActions", [])[-MAX_ACTIONS:]) if isinstance(body.get("recentSafeActions"), list) else [],
        "recentTempGmResponses": deepcopy(body.get("recentGmResponses", [])[-MAX_GM_RESPONSES:]) if isinstance(body.get("recentGmResponses"), list) else [],
        "logcat": logcat,
        "screenshot": {
            "requested": screenshot_requested,
            "userApproved": screenshot_approved,
            "reference": screenshot_ref,
        },
        "exceptionClass": exception_class,
        "topStackFrames": stack,
        "environment": {
            "deviceModel": env.get("deviceModel"),
            "androidSdk": env.get("androidSdk"),
        },
    }
    ai_summarized = {
        "summary": redact_secrets(body.get("aiSummary", ""), 8000),
        "isEvidence": False,
    }

    report = {
        "schemaVersion": 2,
        "reportUid": report_uid,
        "createdAtUnixMs": created_ms,
        "submissionState": "LOCAL_PENDING",
        "evidenceClassification": ["USER-SUPPLIED", "DEVICE-CAPTURED", "AI-SUMMARIZED"],
        "USER-SUPPLIED": user_supplied,
        "DEVICE-CAPTURED": device_captured,
        "AI-SUMMARIZED": ai_summarized,
        "reproductionStatus": str(body.get("reproductionStatus", "UNCONFIRMED")),
        "duplicateFingerprint": fingerprint,
        "duplicateCandidates": [],
        "github": {
            "submissionAuthorized": False,
            "issueNumber": None,
            "issueUrl": None,
            "submissionActionConsumed": False,
        },
        "canonicalMutation": False,
    }
    store.save_new(report)
    return report


def prepare_issue_preview(report: dict[str, Any]) -> str:
    u = report["USER-SUPPLIED"]
    d = report["DEVICE-CAPTURED"]
    ai = report["AI-SUMMARIZED"]
    app = d["app"]
    logcat = d["logcat"]
    shot = d["screenshot"]
    title_seed = normalize_symptom(u["originalReport"])[:90] or "TEMP bug report"
    return f"""TITLE\n[RPG OS TEMP] {title_seed}\n\nUSER REPORT\n{u['originalReport']}\n\nEXPECTED\n{u['expected'] or 'Not supplied'}\n\nACTUAL\n{u['actual'] or 'Not supplied'}\n\nREPRODUCTION\nStatus: {report['reproductionStatus']}\nNotes: {u['reproducibilityNotes'] or 'Not supplied'}\n\nAPP / BUILD\nversionName={app.get('versionName')}\nversionCode={app.get('versionCode')}\nbuildSha={app.get('buildSha')}\n\nCAMPAIGN / WORLD PACK\ncampaignUid={d.get('campaignUid')}\nworldPackUid={d.get('worldPackUid')}\nroute={d.get('route')}\n\nTEMP GM\nprovider={d.get('tempProviderId')}\nmode={d.get('tempResponseMode')}\nbridge={d.get('bridgeState')}\nllama={d.get('llamaState')}\n\nDEVICE EVIDENCE\nadb={d.get('adbState')}\ndevice={d.get('environment', {}).get('deviceModel')}\nandroidSdk={d.get('environment', {}).get('androidSdk')}\n\nLOGCAT EXCERPT\nstatus={logcat.get('status')}\n{logcat.get('excerpt') or '[none]'}\n\nSCREENSHOT\nUSER_APPROVED={'YES' if shot.get('userApproved') else 'NO'}\nreference={shot.get('reference') or '[none]'}\n\nDUPLICATE FINGERPRINT\n{report['duplicateFingerprint']}\n\nAI SUMMARY\n{ai.get('summary') or '[none]'}\n(AI summary is not evidence.)\n\nEVIDENCE CLASSIFICATION\nUSER-SUPPLIED: user report / expected / actual / reproduction notes\nDEVICE-CAPTURED: build, route, provider states, bounded actions/responses/logcat/screenshot metadata\nAI-SUMMARIZED: summary only; not identity authority and not evidence\n"""


def apply_duplicate_candidates(store: BugReportStore, report_uid: str, candidates: list[dict[str, Any]]) -> dict[str, Any]:
    report = store.load(report_uid)
    safe = []
    for candidate in candidates[:10]:
        if not isinstance(candidate, dict):
            continue
        safe.append({
            "issueNumber": candidate.get("issueNumber"),
            "title": redact_secrets(candidate.get("title", ""), 300),
            "url": redact_secrets(candidate.get("url", ""), 1000),
            "fingerprint": candidate.get("fingerprint"),
            "fingerprintMatch": candidate.get("fingerprint") == report["duplicateFingerprint"],
        })
    report["duplicateCandidates"] = safe
    store.update(report)
    return report


def set_user_submission_decision(store: BugReportStore, report_uid: str, decision: str) -> dict[str, Any]:
    report = store.load(report_uid)
    decision = str(decision).upper()
    if report["submissionState"] in {"SUBMITTED", "LINKED_DUPLICATE", "CANCELLED"}:
        return report
    if decision == "CONFIRM_NEW_ISSUE":
        report["submissionState"] = "READY"
        report["github"]["submissionAuthorized"] = True
    elif decision == "CANCEL":
        report["submissionState"] = "CANCELLED"
        report["github"]["submissionAuthorized"] = False
    elif decision == "KEEP_PENDING":
        report["submissionState"] = "LOCAL_PENDING"
        report["github"]["submissionAuthorized"] = False
    else:
        raise ValueError("unsupported_submission_decision")
    store.update(report)
    return report


def consume_issue_creation_authorization(store: BugReportStore, report_uid: str) -> dict[str, Any]:
    """Idempotent gate: exactly one external GitHub create action may consume authorization."""
    report = store.load(report_uid)
    allowed = (
        report["submissionState"] == "READY"
        and report["github"].get("submissionAuthorized") is True
        and report["github"].get("submissionActionConsumed") is not True
    )
    if allowed:
        report["github"]["submissionActionConsumed"] = True
        store.update(report)
    return {"allowed": allowed, "report": report, "issueDraft": prepare_issue_preview(report)}


def mark_submitted(store: BugReportStore, report_uid: str, issue_number: int, issue_url: str) -> dict[str, Any]:
    report = store.load(report_uid)
    if not report["github"].get("submissionActionConsumed"):
        raise ValueError("submission_authorization_not_consumed")
    if report["submissionState"] == "SUBMITTED":
        return report
    report["submissionState"] = "SUBMITTED"
    report["github"]["issueNumber"] = int(issue_number)
    report["github"]["issueUrl"] = redact_secrets(issue_url, 1000)
    store.update(report)
    return report


def mark_linked_duplicate(store: BugReportStore, report_uid: str, issue_number: int, issue_url: str,
                          user_confirmed: bool) -> dict[str, Any]:
    if not user_confirmed:
        raise ValueError("explicit_user_confirmation_required")
    report = store.load(report_uid)
    report["submissionState"] = "LINKED_DUPLICATE"
    report["github"]["submissionAuthorized"] = True
    report["github"]["submissionActionConsumed"] = True
    report["github"]["issueNumber"] = int(issue_number)
    report["github"]["issueUrl"] = redact_secrets(issue_url, 1000)
    store.update(report)
    return report
