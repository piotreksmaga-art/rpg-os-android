import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import temp_bug_harness as bug
import temp_bug_ui_contract as bug_ui
import temp_context_builder as context_builder
import temp_gm_bridge as bridge
import temp_gm_provider as provider


def test_host_is_localhost_only():
    assert bridge.HOST == "127.0.0.1"
    assert bridge.BIELIK_URL.startswith("http://127.0.0.1:")


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


def test_modes_are_locked():
    assert provider.RESPONSE_MODES == {"NARRATIVE_ONLY", "ENGINE_CONFIRMED", "TEST_FALLBACK"}


def _new_report(store: bug.BugReportStore):
    return bug.build_bug_bundle(
        {
            "description": "Po kliknięciu Kontynuuj nic się nie dzieje.",
            "include_logcat": False,
            "include_screenshot": False,
            "build": {"versionName": "test", "versionCode": 1, "buildSha": "abc"},
            "route": "SAVES",
            "environment": {"deviceModel": "SM-S921B", "androidSdk": 36},
        },
        provider_id="BIELIK_4_5B_V3",
        provider_status="READY",
        bridge_status="READY",
        store=store,
    )


def test_bug_pending_contract_is_local_and_non_authoritative():
    with tempfile.TemporaryDirectory() as tmp:
        store = bug.BugReportStore(Path(tmp))
        report = _new_report(store)
        view = bug_ui.list_pending_reports(store)
        assert view["count"] == 1
        assert view["reports"][0]["reportUid"] == report["reportUid"]
        assert view["canonicalMutation"] is False


def test_bug_preview_never_performs_github_write():
    with tempfile.TemporaryDirectory() as tmp:
        store = bug.BugReportStore(Path(tmp))
        report = _new_report(store)
        result = bug_ui.control_bug_report(store, {"reportUid": report["reportUid"], "action": "PREVIEW"})
        assert result["githubWritePerformed"] is False
        assert result["canonicalMutation"] is False
        assert "USER REPORT" in result["issuePreview"]


def test_duplicate_candidates_do_not_authorize_submission():
    with tempfile.TemporaryDirectory() as tmp:
        store = bug.BugReportStore(Path(tmp))
        report = _new_report(store)
        result = bug_ui.control_bug_report(
            store,
            {
                "reportUid": report["reportUid"],
                "action": "SET_DUPLICATES",
                "candidates": [{"issueNumber": 7, "title": "same", "url": "https://example.invalid/7", "fingerprint": report["duplicateFingerprint"]}],
            },
        )
        assert result["report"]["submissionState"] == "LOCAL_PENDING"
        assert result["report"]["github"]["submissionAuthorized"] is False


def test_confirm_new_issue_is_only_local_authorization_marker():
    with tempfile.TemporaryDirectory() as tmp:
        store = bug.BugReportStore(Path(tmp))
        report = _new_report(store)
        result = bug_ui.control_bug_report(store, {"reportUid": report["reportUid"], "action": "CONFIRM_NEW_ISSUE"})
        assert result["report"]["submissionState"] == "READY"
        assert result["report"]["github"]["submissionAuthorized"] is True
        assert result["report"]["github"]["submissionKind"] == "NEW_ISSUE"
        assert result["githubWritePerformed"] is False


def test_confirm_link_duplicate_cannot_be_consumed_as_new_issue_authorization():
    with tempfile.TemporaryDirectory() as tmp:
        store = bug.BugReportStore(Path(tmp))
        report = _new_report(store)
        bug_ui.control_bug_report(
            store,
            {
                "reportUid": report["reportUid"],
                "action": "SET_DUPLICATES",
                "candidates": [{"issueNumber": 8, "title": "same", "url": "https://example.invalid/8", "fingerprint": report["duplicateFingerprint"]}],
            },
        )
        result = bug_ui.control_bug_report(store, {"reportUid": report["reportUid"], "action": "CONFIRM_LINK_DUPLICATE", "targetIssueNumber": 8})
        assert result["report"]["submissionState"] == "READY"
        assert result["report"]["github"]["duplicateLinkAuthorized"] is True
        assert result["report"]["github"]["submissionAuthorized"] is False
        gate = bug.consume_issue_creation_authorization(store, report["reportUid"])
        assert gate["allowed"] is False


def test_cancel_keeps_no_issue_authorization():
    with tempfile.TemporaryDirectory() as tmp:
        store = bug.BugReportStore(Path(tmp))
        report = _new_report(store)
        bug_ui.control_bug_report(store, {"reportUid": report["reportUid"], "action": "CONFIRM_NEW_ISSUE"})
        result = bug_ui.control_bug_report(store, {"reportUid": report["reportUid"], "action": "CANCEL"})
        assert result["report"]["submissionState"] == "CANCELLED"
        assert result["report"]["github"]["submissionAuthorized"] is False
        assert result["githubWritePerformed"] is False
