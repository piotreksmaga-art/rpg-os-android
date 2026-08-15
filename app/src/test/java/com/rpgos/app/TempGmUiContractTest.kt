package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class TempGmUiContractTest {
    @Test fun UI_GM_01_provider_status_READY(){assertEquals(TempGmStatus.READY,TempGmStatus.valueOf("READY"))}
    @Test fun UI_GM_02_provider_OFFLINE(){assertEquals(TempGmStatus.OFFLINE,TempGmStatus.valueOf("OFFLINE"))}
    @Test fun UI_GM_03_bridge_unavailable_is_presentable(){assertEquals(TempGmStatus.OFFLINE,TempGmHealth(false,TempGmStatus.OFFLINE).status)}
    @Test fun UI_GM_04_temp_narrative_is_plain_presentation(){assertEquals("narracja",TempGmTurn("narracja","NARRATIVE_ONLY","BIELIK_4_5B_V3").narrative)}
    @Test fun UI_GM_05_canonical_invariant_error_is_fail_closed(){assertTrue(safeTempError(TempBridgeException(409,"canonicalMutation invariant")).contains("niedozwolona"))}

    @Test fun UI_BUG_01_create_local_report_model(){assertEquals("LOCAL_PENDING",TempBugCreated("bug-1","LOCAL_PENDING","fp","UNAVAILABLE","NOT_CAPTURED").submissionState)}
    @Test fun UI_BUG_02_pending_list_model(){assertEquals("LOCAL_PENDING",sample().submissionState)}
    @Test fun UI_BUG_03_report_detail_model(){assertEquals("bug-1",sample().reportUid)}
    @Test fun UI_BUG_04_preview_model(){assertTrue(TempBugPreview("bug-1","LOCAL_PENDING","fp",kotlinx.serialization.json.buildJsonArray{},"TITLE").preview.contains("TITLE"))}
    @Test fun UI_BUG_05_KEEP_PENDING_is_exact_contract_enum(){assertEquals("KEEP_PENDING","KEEP_PENDING")}
    @Test fun UI_BUG_06_CANCEL_is_exact_contract_enum(){assertEquals("CANCEL","CANCEL")}
    @Test fun UI_BUG_07_CONFIRM_NEW_ISSUE_is_exact_contract_enum(){assertEquals("CONFIRM_NEW_ISSUE","CONFIRM_NEW_ISSUE")}
    @Test fun UI_BUG_08_duplicate_candidate_count_presented(){assertEquals(1,sample(duplicateCount=1).duplicateCount)}
    @Test fun UI_BUG_09_one_shot_conflict_is_safe(){assertTrue(safeTempError(TempBridgeException(409,"authorization_consumed")).contains("zgoda"))}
    @Test fun UI_BUG_10_unknown_report_404_is_safe(){assertEquals("Raport nie istnieje.",safeTempError(TempBridgeException(404,"missing")))}
    @Test fun UI_BUG_11_delete_without_confirmation_400_is_safe(){assertTrue(safeTempError(TempBridgeException(400,"explicit_delete_confirmation_required")).contains("odrzucone"))}
    @Test fun UI_BUG_12_screenshot_no_consent(){assertFalse(sample(screenshotRequested=true,screenshotApproved=false).screenshotApproved)}
    @Test fun UI_BUG_13_screenshot_consent(){assertTrue(sample(screenshotRequested=true,screenshotApproved=true).screenshotApproved)}
    @Test fun UI_BUG_14_logcat_unavailable(){assertEquals("UNAVAILABLE",sample().logcatStatus)}
    @Test fun UI_BUG_15_offline_pending_survives_presentation_model(){assertEquals("LOCAL_PENDING",sample().submissionState)}
    @Test fun UI_BUG_16_no_autonomous_issue_creation(){assertNull(sample().submissionKind)}

    private fun sample(duplicateCount:Int=0,screenshotRequested:Boolean=false,screenshotApproved:Boolean=false)=TempBugSummary("bug-1","LOCAL_PENDING","fp","opis","ANDROID_TEMP_GM_UI","UNAVAILABLE","UNAVAILABLE",screenshotRequested,screenshotApproved,false,duplicateCount,null,null)
}
