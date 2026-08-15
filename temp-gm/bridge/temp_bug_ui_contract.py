#!/usr/bin/env python3
"""UI-facing lifecycle adapter for CHAT-7 TEMP bug reports.

BugReportStore remains the single durable queue. This module adds no second store,
contains no GitHub credentials/writer and never mutates canonical RPG OS state.
"""
from __future__ import annotations

from typing import Any

from temp_bug_harness import (
    BugReportStore,
    apply_duplicate_candidates,
    consume_issue_creation_authorization,
    mark_submitted,
    prepare_issue_preview,
    set_user_submission_decision,
)

VISIBLE_PENDING_STATES = {"LOCAL_PENDING", "READY"}
TERMINAL_STATES = {"SUBMITTED", "LINKED_DUPLICATE"}


def _github(report: dict[str, Any]) -> dict[str, Any]:
    g = report.setdefault("github", {})
    g.setdefault("submissionAuthorized", False)
    g.setdefault("submissionActionConsumed", False)
    g.setdefault("submissionKind", None)
    g.setdefault("duplicateLinkAuthorized", False)
    g.setdefault("duplicateLinkActionConsumed", False)
    g.setdefault("targetIssueNumber", None)
    g.setdefault("issueNumber", None)
    g.setdefault("issueUrl", None)
    return g


def _clear_authorization(report: dict[str, Any]) -> None:
    g = _github(report)
    g.update({
        "submissionAuthorized": False,
        "submissionActionConsumed": False,
        "submissionKind": None,
        "duplicateLinkAuthorized": False,
        "duplicateLinkActionConsumed": False,
        "targetIssueNumber": None,
    })


def report_summary(report: dict[str, Any]) -> dict[str, Any]:
    user = report.get("USER-SUPPLIED", {})
    device = report.get("DEVICE-CAPTURED", {})
    g = _github(report)
    shot = device.get("screenshot", {})
    return {
        "reportUid": report.get("reportUid"),
        "createdAtUnixMs": report.get("createdAtUnixMs"),
        "submissionState": report.get("submissionState"),
        "duplicateFingerprint": report.get("duplicateFingerprint"),
        "descriptionPreview": str(user.get("originalReport", ""))[:240],
        "route": device.get("route"),
        "tempProviderId": device.get("tempProviderId"),
        "bridgeState": device.get("bridgeState"),
        "llamaState": device.get("llamaState"),
        "adbState": device.get("adbState"),
        "logcatStatus": device.get("logcat", {}).get("status"),
        "screenshotRequested": bool(shot.get("requested")),
        "screenshotUserApproved": bool(shot.get("userApproved")),
        "screenshotAvailable": bool(shot.get("reference")) and bool(shot.get("userApproved")),
        "duplicateCandidateCount": len(report.get("duplicateCandidates", [])),
        "userConfirmationRequired": report.get("submissionState") == "LOCAL_PENDING",
        "submissionKind": g.get("submissionKind"),
        "targetIssueNumber": g.get("targetIssueNumber"),
        "issueNumber": g.get("issueNumber"),
        "issueUrl": g.get("issueUrl"),
        "canonicalMutation": False,
    }


def list_reports(store: BugReportStore, pending_only: bool = True) -> dict[str, Any]:
    raw = store.list_reports()
    pending_count = sum(1 for r in raw if r.get("submissionState") in VISIBLE_PENDING_STATES)
    if pending_only:
        raw = [r for r in raw if r.get("submissionState") in VISIBLE_PENDING_STATES]
    reports = [report_summary(r) for r in raw]
    reports.sort(key=lambda x: int(x.get("createdAtUnixMs") or 0), reverse=True)
    return {"count": len(reports), "pendingCount": pending_count, "reports": reports, "canonicalMutation": False}


def list_pending_reports(store: BugReportStore) -> dict[str, Any]:
    return list_reports(store, pending_only=True)


def report_detail(store: BugReportStore, report_uid: str) -> dict[str, Any]:
    report = store.load(report_uid)
    _github(report)
    return {"report": report, "summary": report_summary(report), "canonicalMutation": False}


def report_preview(store: BugReportStore, report_uid: str) -> dict[str, Any]:
    report = store.load(report_uid)
    return {
        "reportUid": report_uid,
        "submissionState": report.get("submissionState"),
        "duplicateFingerprint": report.get("duplicateFingerprint"),
        "duplicateCandidates": report.get("duplicateCandidates", []),
        "issuePreview": prepare_issue_preview(report),
        "userConfirmationRequired": report.get("submissionState") == "LOCAL_PENDING",
        "githubWritePerformed": False,
        "canonicalMutation": False,
    }


def set_duplicates(store: BugReportStore, report_uid: str, candidates: Any) -> dict[str, Any]:
    if not isinstance(candidates, list):
        raise ValueError("candidates_must_be_array")
    report = apply_duplicate_candidates(store, report_uid, candidates)
    return {"report": report, "summary": report_summary(report), "githubWritePerformed": False, "canonicalMutation": False}


def _authorize_duplicate_link(store: BugReportStore, report_uid: str, target: Any) -> dict[str, Any]:
    report = store.load(report_uid)
    if report.get("submissionState") in TERMINAL_STATES:
        raise ValueError("terminal_report_requires_new_report")
    try:
        target = int(target)
    except (TypeError, ValueError):
        raise ValueError("target_issue_number_required")
    if not any(c.get("issueNumber") == target for c in report.get("duplicateCandidates", [])):
        raise ValueError("target_duplicate_not_in_candidates")
    _clear_authorization(report)
    g = _github(report)
    report["submissionState"] = "READY"
    g["submissionKind"] = "LINK_DUPLICATE"
    g["duplicateLinkAuthorized"] = True
    g["targetIssueNumber"] = target
    store.update(report)
    return report


def apply_user_decision(store: BugReportStore, report_uid: str, body: dict[str, Any]) -> dict[str, Any]:
    decision = str(body.get("decision", "")).upper()
    if decision == "CONFIRM_LINK_DUPLICATE":
        report = _authorize_duplicate_link(store, report_uid, body.get("targetIssueNumber"))
    elif decision in {"CONFIRM_NEW_ISSUE", "KEEP_PENDING", "CANCEL"}:
        # Clear old/consumed authorization before creating a new explicit decision.
        existing = store.load(report_uid)
        if existing.get("submissionState") in TERMINAL_STATES:
            raise ValueError("terminal_report_requires_new_report")
        _clear_authorization(existing)
        store.update(existing)
        report = set_user_submission_decision(store, report_uid, decision)
        g = _github(report)
        if decision == "CONFIRM_NEW_ISSUE":
            g["submissionKind"] = "NEW_ISSUE"
        else:
            _clear_authorization(report)
        store.update(report)
    else:
        raise ValueError("unsupported_submission_decision")
    return {"report": report, "summary": report_summary(report), "issuePreview": prepare_issue_preview(report), "githubWritePerformed": False, "canonicalMutation": False}


def retry_report(store: BugReportStore, report_uid: str) -> dict[str, Any]:
    report = store.load(report_uid)
    if report.get("submissionState") in TERMINAL_STATES:
        raise ValueError("submitted_report_cannot_retry")
    report["submissionState"] = "LOCAL_PENDING"
    _clear_authorization(report)
    store.update(report)
    return {"report": report, "summary": report_summary(report), "githubWritePerformed": False, "canonicalMutation": False}


def consume_authorization(store: BugReportStore, report_uid: str, kind: Any) -> dict[str, Any]:
    kind = str(kind or "").upper()
    if kind == "NEW_ISSUE":
        report = store.load(report_uid)
        if _github(report).get("submissionKind") != "NEW_ISSUE":
            result = {"allowed": False, "kind": kind, "report": report, "issueDraft": prepare_issue_preview(report)}
        else:
            result = consume_issue_creation_authorization(store, report_uid)
            result["kind"] = kind
    elif kind == "LINK_DUPLICATE":
        report = store.load(report_uid)
        g = _github(report)
        allowed = (
            report.get("submissionState") == "READY"
            and g.get("submissionKind") == "LINK_DUPLICATE"
            and g.get("duplicateLinkAuthorized") is True
            and g.get("duplicateLinkActionConsumed") is not True
            and g.get("targetIssueNumber") is not None
        )
        if allowed:
            g["duplicateLinkActionConsumed"] = True
            store.update(report)
        result = {"allowed": allowed, "kind": kind, "targetIssueNumber": g.get("targetIssueNumber"), "report": report, "issueDraft": prepare_issue_preview(report)}
    else:
        raise ValueError("unsupported_submission_kind")
    return {
        "allowed": bool(result.get("allowed")),
        "kind": result.get("kind"),
        "targetIssueNumber": result.get("targetIssueNumber"),
        "reportUid": report_uid,
        "issueDraft": result.get("issueDraft"),
        "githubWritePerformed": False,
        "canonicalMutation": False,
    }


def record_submitted(store: BugReportStore, report_uid: str, body: dict[str, Any]) -> dict[str, Any]:
    report = mark_submitted(store, report_uid, body.get("issueNumber"), body.get("issueUrl", ""))
    return {"report": report, "summary": report_summary(report), "canonicalMutation": False}


def record_linked_duplicate(store: BugReportStore, report_uid: str, body: dict[str, Any]) -> dict[str, Any]:
    report = store.load(report_uid)
    g = _github(report)
    if report.get("submissionState") == "LINKED_DUPLICATE":
        return {"report": report, "summary": report_summary(report), "canonicalMutation": False}
    if report.get("submissionState") != "READY" or g.get("submissionKind") != "LINK_DUPLICATE":
        raise ValueError("duplicate_link_not_ready")
    if g.get("duplicateLinkActionConsumed") is not True:
        raise ValueError("duplicate_link_authorization_not_consumed")
    issue_number = int(body.get("issueNumber"))
    if issue_number != int(g.get("targetIssueNumber")):
        raise ValueError("duplicate_issue_mismatch")
    report["submissionState"] = "LINKED_DUPLICATE"
    g["issueNumber"] = issue_number
    g["issueUrl"] = str(body.get("issueUrl", ""))[:1000]
    g["duplicateLinkAuthorized"] = False
    store.update(report)
    return {"report": report, "summary": report_summary(report), "canonicalMutation": False}


def delete_report(store: BugReportStore, report_uid: str, confirmed: bool) -> dict[str, Any]:
    if confirmed is not True:
        raise ValueError("explicit_delete_confirmation_required")
    report = store.load(report_uid)
    if report.get("submissionState") in TERMINAL_STATES:
        raise ValueError("submitted_report_cannot_delete")
    # One durable queue only: remove the exact BugReportStore backing file.
    safe = "".join(ch for ch in str(report_uid) if ch.isalnum() or ch in "._-")
    if not safe or safe != report_uid:
        raise FileNotFoundError("bug_report_not_found")
    path = store.pending_dir / f"{safe}.json"
    if not path.exists():
        raise FileNotFoundError("bug_report_not_found")
    path.unlink()
    return {"reportUid": report_uid, "deleted": True, "canonicalMutation": False}


# Backward-compatible adapter for earlier CHAT-6 draft.
def control_bug_report(store: BugReportStore, body: dict[str, Any]) -> dict[str, Any]:
    uid = str(body.get("reportUid", ""))
    if not uid:
        raise ValueError("report_uid_required")
    action = str(body.get("action", "PREVIEW")).upper()
    if action == "PREVIEW": return report_preview(store, uid)
    if action == "SET_DUPLICATES": return set_duplicates(store, uid, body.get("candidates", []))
    if action in {"CONFIRM_NEW_ISSUE", "CONFIRM_LINK_DUPLICATE", "KEEP_PENDING", "CANCEL"}:
        return apply_user_decision(store, uid, {"decision": action, "targetIssueNumber": body.get("targetIssueNumber")})
    raise ValueError("unsupported_bug_control_action")
