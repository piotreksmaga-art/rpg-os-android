#!/usr/bin/env python3
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import temp_bug_harness as bug


def base_body(description="Po kliknięciu Kontynuuj nic się nie dzieje."):
    return {
        "description": description,
        "include_logcat": False,
        "include_screenshot": False,
        "screenshotApproved": False,
        "build": {"versionName": "1.2.0-test", "versionCode": 120, "buildSha": "abc123"},
        "campaignUid": "campaign-test",
        "worldPackUid": "world-test",
        "route": "SAVES",
        "responseMode": "NARRATIVE_ONLY",
        "adbStatus": "UNAVAILABLE",
        "recentSafeActions": [f"action-{i}" for i in range(20)],
        "recentGmResponses": [f"gm-{i}" for i in range(10)],
        "exceptionClass": "IllegalStateException",
        "topStackFrames": ["A.kt:123", "B.kt:456"],
        "environment": {"deviceModel": "SM-S921B", "androidSdk": 36},
        "expected": "Powinno otworzyć kampanię.",
        "actual": "Brak reakcji.",
        "reproductionStatus": "REPRODUCED_ONCE",
        "reproducibilityNotes": "Odtworzono po ponownym wejściu w SAVES.",
        "aiSummary": "Możliwy problem nawigacji.",
    }


class BugHarnessTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.store = bug.BugReportStore(self.root)

    def tearDown(self):
        self.tmp.cleanup()

    def build(self, body=None, provider_status="READY", bridge_status="READY"):
        return bug.build_bug_bundle(
            body or base_body(),
            provider_id="BIELIK_4_5B_V3",
            provider_status=provider_status,
            bridge_status=bridge_status,
            store=self.store,
        )

    def test_BUG_01_original_user_report_verbatim(self):
        text = "Po kliknięciu Kontynuuj nic się nie dzieje.  Dwa odstępy!"
        report = self.build(base_body(text))
        self.assertEqual(report["USER-SUPPLIED"]["originalReport"], text)
        self.assertFalse(report["USER-SUPPLIED"]["originalReportRedactedForSecretSafety"])

    def test_BUG_02_evidence_classes_separated(self):
        report = self.build()
        self.assertEqual(report["evidenceClassification"], ["USER-SUPPLIED", "DEVICE-CAPTURED", "AI-SUMMARIZED"])
        self.assertFalse(report["AI-SUMMARIZED"]["isEvidence"])

    def test_BUG_03_bounded_logcat_respects_limit(self):
        body = base_body()
        body["include_logcat"] = True
        body["logcatExcerpt"] = "\n".join(f"L{i}" for i in range(1000))
        report = self.build(body)
        excerpt = report["DEVICE-CAPTURED"]["logcat"]["excerpt"]
        self.assertLessEqual(len(excerpt.splitlines()), bug.MAX_LOGCAT_LINES)
        self.assertLessEqual(len(excerpt), bug.MAX_LOGCAT_CHARS)

    def test_BUG_04_no_adb_does_not_lose_report(self):
        body = base_body()
        body["include_logcat"] = True
        body["packageName"] = "pl.rpgos.invalid"
        with patch("temp_bug_harness.capture_package_logcat", return_value={"status":"UNAVAILABLE","reason":"adb_missing","excerpt":"","lineLimit":300}):
            report = self.build(body)
        self.assertEqual(report["submissionState"], "LOCAL_PENDING")
        self.assertEqual(report["USER-SUPPLIED"]["originalReport"], body["description"])

    def test_BUG_05_no_internet_is_local_pending(self):
        report = self.build()
        self.assertEqual(report["submissionState"], "LOCAL_PENDING")
        self.assertFalse(report["github"]["submissionAuthorized"])

    def test_BUG_06_pending_survives_store_restart(self):
        report = self.build()
        uid = report["reportUid"]
        restarted = bug.BugReportStore(self.root)
        self.assertEqual(restarted.load(uid)["submissionState"], "LOCAL_PENDING")

    def test_BUG_07_fingerprint_deterministic(self):
        b = base_body()
        a = bug.duplicate_fingerprint(version_name="1", version_code=1, route="SAVES", exception_class="X", top_stack_frames=b["topStackFrames"], user_report=b["description"], stable_environment=b["environment"])
        c = bug.duplicate_fingerprint(version_name="1", version_code=1, route="SAVES", exception_class="X", top_stack_frames=b["topStackFrames"], user_report=b["description"], stable_environment=b["environment"])
        self.assertEqual(a, c)

    def test_BUG_08_timestamp_does_not_change_fingerprint(self):
        with patch("temp_bug_harness.time.time", return_value=1000.0):
            a = self.build()
        with patch("temp_bug_harness.time.time", return_value=9999.0):
            b = self.build()
        self.assertEqual(a["duplicateFingerprint"], b["duplicateFingerprint"])

    def test_BUG_09_ai_summary_does_not_change_fingerprint(self):
        a_body = base_body(); a_body["aiSummary"] = "A"
        b_body = base_body(); b_body["aiSummary"] = "Completely different AI text"
        self.assertEqual(self.build(a_body)["duplicateFingerprint"], self.build(b_body)["duplicateFingerprint"])

    def test_BUG_10_duplicate_search_data_does_not_create_issue(self):
        report = self.build()
        updated = bug.apply_duplicate_candidates(self.store, report["reportUid"], [{"issueNumber":7,"title":"same","url":"https://example.invalid/7","fingerprint":report["duplicateFingerprint"]}])
        self.assertEqual(updated["submissionState"], "LOCAL_PENDING")
        self.assertFalse(updated["github"]["submissionAuthorized"])
        self.assertIsNone(updated["github"]["issueNumber"])

    def test_BUG_11_issue_gate_requires_explicit_confirmation(self):
        report = self.build()
        gate = bug.consume_issue_creation_authorization(self.store, report["reportUid"])
        self.assertFalse(gate["allowed"])

    def test_BUG_12_cancel_means_no_issue(self):
        report = self.build()
        cancelled = bug.set_user_submission_decision(self.store, report["reportUid"], "CANCEL")
        gate = bug.consume_issue_creation_authorization(self.store, report["reportUid"])
        self.assertEqual(cancelled["submissionState"], "CANCELLED")
        self.assertFalse(gate["allowed"])

    def test_BUG_13_confirmed_submission_exactly_one_issue_action(self):
        report = self.build()
        bug.set_user_submission_decision(self.store, report["reportUid"], "CONFIRM_NEW_ISSUE")
        first = bug.consume_issue_creation_authorization(self.store, report["reportUid"])
        second = bug.consume_issue_creation_authorization(self.store, report["reportUid"])
        self.assertTrue(first["allowed"])
        self.assertFalse(second["allowed"])

    def test_BUG_14_retry_after_submitted_does_not_reauthorize(self):
        report = self.build()
        bug.set_user_submission_decision(self.store, report["reportUid"], "CONFIRM_NEW_ISSUE")
        gate = bug.consume_issue_creation_authorization(self.store, report["reportUid"])
        self.assertTrue(gate["allowed"])
        bug.mark_submitted(self.store, report["reportUid"], 12, "https://example.invalid/issues/12")
        retry = bug.consume_issue_creation_authorization(self.store, report["reportUid"])
        self.assertFalse(retry["allowed"])
        self.assertEqual(retry["report"]["github"]["issueNumber"], 12)

    def test_BUG_15_screenshot_requires_user_approval(self):
        body = base_body()
        body["include_screenshot"] = True
        body["screenshotReference"] = "/tmp/screen.png"
        body["screenshotApproved"] = False
        report = self.build(body)
        shot = report["DEVICE-CAPTURED"]["screenshot"]
        self.assertFalse(shot["userApproved"])
        self.assertEqual(shot["reference"], "")

    def test_BUG_16_secrets_redaction(self):
        body = base_body("Błąd token=gho_abcdefghijklmnopqrstuvwxyz123456 i password=abc123")
        body["logcatExcerpt"] = "Authorization: Bearer super-secret-value"
        body["include_logcat"] = True
        report = self.build(body)
        serialized = json.dumps(report, ensure_ascii=False)
        self.assertNotIn("gho_", serialized)
        self.assertNotIn("abc123", serialized)
        self.assertNotIn("super-secret-value", serialized)
        self.assertTrue(report["USER-SUPPLIED"]["originalReportRedactedForSecretSafety"])

    def test_BUG_17_canonical_state_before_after_identical(self):
        canonical = {"player": {"hp": 100, "money": 20}, "saveSha": "deadbeef"}
        before = json.dumps(canonical, sort_keys=True)
        self.build()
        after = json.dumps(canonical, sort_keys=True)
        self.assertEqual(before, after)

    def test_BUG_18_temp_gm_offline_capture_still_works(self):
        report = self.build(provider_status="OFFLINE")
        self.assertEqual(report["DEVICE-CAPTURED"]["llamaState"], "OFFLINE")
        self.assertEqual(report["submissionState"], "LOCAL_PENDING")

    def test_BUG_19_bridge_offline_direct_local_capture_still_pending(self):
        report = self.build(bridge_status="OFFLINE")
        self.assertEqual(report["DEVICE-CAPTURED"]["bridgeState"], "OFFLINE")
        self.assertEqual(report["submissionState"], "LOCAL_PENDING")

    def test_BUG_20_malformed_logcat_binary_junk_does_not_break_bundle(self):
        body = base_body()
        body["include_logcat"] = True
        body["logcatExcerpt"] = "ok\x00bad�\udcff\nlast"
        report = self.build(body)
        self.assertEqual(report["submissionState"], "LOCAL_PENDING")
        self.assertLessEqual(len(report["DEVICE-CAPTURED"]["logcat"]["excerpt"]), bug.MAX_LOGCAT_CHARS)


if __name__ == "__main__":
    unittest.main(verbosity=2)
