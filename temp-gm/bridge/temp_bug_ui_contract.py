#!/usr/bin/env python3
"""UI-facing local control contract for the CHAT-7 TEMP bug harness.

This module never talks to GitHub and never mutates canonical RPG OS state.
It only exposes bounded LOCAL_PENDING report state to the localhost bridge.
"""
from __future__ import annotations

from typing import Any

from temp_bug_harness import (
    BugReportStore,
    apply_duplicate_candidates,
    prepare_issue_preview,
    set_user_submission_decision,
)

VISIBLE_PENDING_STATES = {"LOCAL_PENDING", "READY"}
CONTROL_ACTIONS = {
    "PREVIEW",
    "SET_DUPLICATES",
    "CONFIRM_NEW_ISSUE",
    "CONFIRM_LINK_DUPLICATE",
    "KEEP_PENDING",
    "CANCEL",
}


def report_summary(report: dict[str, Any]) -> dict[str, Any]:
    user = report.get("USER-SUPPLIED", {})
    device = report.get("DEVICE-CAPTURED", {})
    github = report.get("github", {})
    screenshot = device.get("screenshot", {})
    return {
        "reportUid": report.get("reportUid"),
        "createdAtUnixMs": report.get("createdAtUnixMs"),
        "submissionState": report.get("submissionState"),
        "duplicateFingerprint": report.get("duplicateFingerprint"),
        "descriptionPreview": str(user.get("originalReport", ""))[:240],
        "route": device.get("route"),
        "tempProviderId": device.get("tempProviderId"),
        "logcatStatus": device.get("logcat", {}).get("status"),
        "screenshotRequested": bool(screenshot.get("requested")),
        "screenshotUserApproved": bool(screenshot.get("userApproved")),
        "duplicateCandidateCount": len(report.get("duplicateCandidates", [])),
        "userConfirmationRequired": report.get("submissionState") == "LOCAL_PENDING",
        "submissionKind": github.get("submissionKind"),
        "targetIssueNumber": github.get("targetIssueNumber"),
        "issueNumber": github.get("issueNumber"),
        "issueUrl": github.get("issueUrl"),
        "canonicalMutation": False,
    }


def list_pending_reports(store: BugReportStore) -> dict[str, Any]:
    reports = [
        report_summary(report)
        for report in store.list_reports()
        if report.get("submissionState") in VISIBLE_PENDING_STATES
    ]
    reports.sort(key=lambda item: int(item.get("createdAtUnixMs") or 0), reverse=True)
    return {
        "count": len(reports),
        "reports": reports,
        "canonicalMutation": False,
    }


def _clear_duplicate_link_authorization(report: dict[str, Any]) -> None:
    github = report.setdefault("github", {})
    github["duplicateLinkAuthorized"] = False
    github["targetIssueNumber"] = None
    if github.get("submissionKind") == "LINK_DUPLICATE":
        github["submissionKind"] = None


def _confirm_link_duplicate(store: BugReportStore, report_uid: str, target_issue_number: Any) -> dict[str, Any]:
    report = store.load(report_uid)
    try:
        target = int(target_issue_number)
    except (TypeError, ValueError):
        raise ValueError("target_issue_number_required")

    matching = [
        candidate for candidate in report.get("duplicateCandidates", [])
        if candidate.get("issueNumber") == target
    ]
    if not matching:
        raise ValueError("target_duplicate_not_in_candidates")

    # This is only a local user authorization marker. It does not update GitHub.
    report["submissionState"] = "READY"
    github = report.setdefault("github", {})
    github["submissionAuthorized"] = False  # prevents NEW ISSUE authorization from being consumed
    github["submissionActionConsumed"] = False
    github["duplicateLinkAuthorized"] = True
    github["submissionKind"] = "LINK_DUPLICATE"
    github["targetIssueNumber"] = target
    store.update(report)
    return report


def control_bug_report(store: BugReportStore, body: dict[str, Any]) -> dict[str, Any]:
    report_uid = str(body.get("reportUid", ""))
    if not report_uid:
        raise ValueError("report_uid_required")

    action = str(body.get("action", "PREVIEW")).upper()
    if action not in CONTROL_ACTIONS:
        raise ValueError("unsupported_bug_control_action")

    if action == "PREVIEW":
        report = store.load(report_uid)
    elif action == "SET_DUPLICATES":
        candidates = body.get("candidates", [])
        if not isinstance(candidates, list):
            raise ValueError("candidates_must_be_array")
        report = apply_duplicate_candidates(store, report_uid, candidates)
    elif action == "CONFIRM_NEW_ISSUE":
        report = set_user_submission_decision(store, report_uid, "CONFIRM_NEW_ISSUE")
        _clear_duplicate_link_authorization(report)
        github = report.setdefault("github", {})
        github["submissionKind"] = "NEW_ISSUE"
        store.update(report)
    elif action == "CONFIRM_LINK_DUPLICATE":
        report = _confirm_link_duplicate(store, report_uid, body.get("targetIssueNumber"))
    elif action == "KEEP_PENDING":
        report = set_user_submission_decision(store, report_uid, "KEEP_PENDING")
        _clear_duplicate_link_authorization(report)
        report.setdefault("github", {})["submissionKind"] = None
        store.update(report)
    elif action == "CANCEL":
        report = set_user_submission_decision(store, report_uid, "CANCEL")
        _clear_duplicate_link_authorization(report)
        report.setdefault("github", {})["submissionKind"] = None
        store.update(report)
    else:  # pragma: no cover - CONTROL_ACTIONS keeps this unreachable
        raise ValueError("unsupported_bug_control_action")

    return {
        "report": report,
        "summary": report_summary(report),
        "issuePreview": prepare_issue_preview(report),
        "githubWritePerformed": False,
        "canonicalMutation": False,
    }
