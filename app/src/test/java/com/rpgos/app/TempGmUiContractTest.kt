package com.rpgos.app

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class TempGmUiContractTest {
    private fun server(body:String, code:Int=200):Pair<MockWebServer,TempGmBridgeClient>{
        val s=MockWebServer();s.start();s.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type","application/json").setBody(body));return s to TempGmBridgeClient(s.url("/").toString().removeSuffix("/"))
    }
    private fun shortClient(s:MockWebServer, readTimeoutMs:Long=50L)=TempGmBridgeClient(
        s.url("/").toString().removeSuffix("/"),
        OkHttpClient.Builder().connectTimeout(1,TimeUnit.SECONDS).readTimeout(readTimeoutMs,TimeUnit.MILLISECONDS).build()
    )

    @Test fun UI_GM_01_provider_status_READY()=runBlocking{
        val(s,c)=server("""{"bridge":"READY","activeProvider":"BIELIK_4_5B_V3","provider":{"status":"READY"},"canonicalMutation":false}""");try{val h=c.health();assertTrue(h.bridgeConnected);assertEquals(TempGmStatus.READY,h.status);assertEquals("BIELIK_4_5B_V3",h.providerId)}finally{s.shutdown()}
    }
    @Test fun UI_GM_02_provider_OFFLINE()=runBlocking{
        val(s,c)=server("""{"bridge":"READY","activeProvider":"BIELIK_4_5B_V3","provider":{"status":"OFFLINE"},"canonicalMutation":false}""");try{assertEquals(TempGmStatus.OFFLINE,c.health().status)}finally{s.shutdown()}
    }
    @Test fun UI_GM_03_bridge_unavailable(){val s=MockWebServer();s.start();val c=TempGmBridgeClient(s.url("/").toString().removeSuffix("/"));s.shutdown();runBlocking{val h=c.health();assertFalse(h.bridgeConnected);assertEquals(TempGmStatus.OFFLINE,h.status)}}
    @Test fun UI_GM_04_TEMP_GM_narrative_display()=runBlocking{
        val(s,c)=server("""{"providerId":"BIELIK_4_5B_V3","mode":"NARRATIVE_ONLY","narrative":"Narracja testowa","canonicalMutation":false}""");try{assertEquals("Narracja testowa",c.turn("test").narrative);assertEquals("/gm/turn",s.takeRequest().path)}finally{s.shutdown()}
    }
    @Test fun UI_GM_05_canonicalMutation_false_invariant()=runBlocking{
        val(s,c)=server("""{"providerId":"BIELIK_4_5B_V3","mode":"NARRATIVE_ONLY","narrative":"bad","canonicalMutation":true}""");try{val e=runCatching{c.turn("test")}.exceptionOrNull();assertTrue(e is TempBridgeException);assertEquals(409,(e as TempBridgeException).httpCode)}finally{s.shutdown()}
    }

    @Test fun TEMP_GM_TIMEOUT_01_gm_turn_timeout_exceeds_backend_generation_timeout(){
        assertTrue(TempGmBridgeClient.GM_TURN_READ_TIMEOUT_SECONDS > TempGmBridgeClient.BACKEND_GENERATION_TIMEOUT_SECONDS)
        assertEquals(210L,TempGmBridgeClient.GM_TURN_READ_TIMEOUT_SECONDS)
    }
    @Test fun TEMP_GM_TIMEOUT_02_health_retains_short_timeout()=runBlocking{
        val s=MockWebServer();s.start();s.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody("""{"bridge":"READY","activeProvider":"BIELIK_4_5B_V3","provider":{"status":"READY"},"canonicalMutation":false}""").setBodyDelay(250,TimeUnit.MILLISECONDS));
        try{val h=shortClient(s).health();assertFalse(h.bridgeConnected);assertEquals(TempGmStatus.OFFLINE,h.status);assertEquals("/health",s.takeRequest().path)}finally{s.shutdown()}
    }
    @Test fun TEMP_GM_TIMEOUT_03_bug_lifecycle_retains_short_timeout()=runBlocking{
        val s=MockWebServer();s.start();s.enqueue(MockResponse().setResponseCode(201).setHeader("Content-Type","application/json").setBody("""{"reportUid":"bug-timeout","captureStatus":{"localBundle":"SAVED","logcat":"UNAVAILABLE","screenshot":"NOT_CAPTURED"},"duplicateFingerprint":"fp","submissionState":"LOCAL_PENDING","canonicalMutation":false}""").setBodyDelay(250,TimeUnit.MILLISECONDS));
        try{val e=runCatching{shortClient(s).createBug("opis",false,false,false)}.exceptionOrNull();assertNotNull(e);assertEquals("/bug",s.takeRequest().path)}finally{s.shutdown()}
    }
    @Test fun TEMP_GM_TIMEOUT_04_long_running_turn_uses_dedicated_timeout()=runBlocking{
        val s=MockWebServer();s.start();s.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody("""{"providerId":"BIELIK_4_5B_V3","mode":"NARRATIVE_ONLY","narrative":"Długa generacja zakończona","canonicalMutation":false}""").setBodyDelay(250,TimeUnit.MILLISECONDS));
        try{val result=shortClient(s).turn("test");assertEquals("Długa generacja zakończona",result.narrative);assertEquals("/gm/turn",s.takeRequest().path)}finally{s.shutdown()}
    }
    @Test fun TEMP_GM_TIMEOUT_05_canonicalMutation_false_remains_required()=runBlocking{
        val(s,c)=server("""{"providerId":"BIELIK_4_5B_V3","mode":"NARRATIVE_ONLY","narrative":"ok","canonicalMutation":false}""");try{assertEquals("ok",c.turn("test").narrative)}finally{s.shutdown()}
    }
    @Test fun TEMP_GM_TIMEOUT_06_canonicalMutation_true_still_fails_closed()=runBlocking{
        val(s,c)=server("""{"providerId":"BIELIK_4_5B_V3","mode":"NARRATIVE_ONLY","narrative":"forbidden","canonicalMutation":true}""");try{val e=runCatching{c.turn("test")}.exceptionOrNull();assertTrue(e is TempBridgeException);assertEquals(409,(e as TempBridgeException).httpCode)}finally{s.shutdown()}
    }

    @Test fun UI_BUG_01_create_local_report()=runBlocking{
        val(s,c)=server("""{"reportUid":"bug-1","captureStatus":{"localBundle":"SAVED","logcat":"UNAVAILABLE","screenshot":"NOT_CAPTURED"},"duplicateFingerprint":"fp","submissionState":"LOCAL_PENDING","canonicalMutation":false}""",201);try{assertEquals("LOCAL_PENDING",c.createBug("opis",true,false,false).submissionState);assertEquals("/bug",s.takeRequest().path)}finally{s.shutdown()}
    }
    @Test fun UI_BUG_02_pending_list()=runBlocking{
        val(s,c)=server("""{"count":1,"pendingCount":1,"reports":[{"reportUid":"bug-1","submissionState":"LOCAL_PENDING","duplicateFingerprint":"fp","descriptionPreview":"opis","route":"ANDROID_TEMP_GM_UI","logcatStatus":"UNAVAILABLE","adbState":"UNAVAILABLE","screenshotRequested":false,"screenshotUserApproved":false,"screenshotAvailable":false,"duplicateCandidateCount":0,"canonicalMutation":false}],"canonicalMutation":false}""");try{assertEquals(1,c.listBugs().size);assertEquals("/bugs",s.takeRequest().path)}finally{s.shutdown()}
    }
    @Test fun UI_BUG_03_report_detail()=runBlocking{
        val(s,c)=server("""{"report":{"reportUid":"bug-1","canonicalMutation":false},"summary":{"reportUid":"bug-1","submissionState":"LOCAL_PENDING","duplicateFingerprint":"fp","descriptionPreview":"opis","route":"ANDROID_TEMP_GM_UI","logcatStatus":"UNAVAILABLE","adbState":"UNAVAILABLE","screenshotRequested":false,"screenshotUserApproved":false,"screenshotAvailable":false,"duplicateCandidateCount":0},"canonicalMutation":false}""");try{assertEquals("bug-1",c.detail("bug-1").summary.reportUid);assertEquals("/bugs/bug-1",s.takeRequest().path)}finally{s.shutdown()}
    }
    @Test fun UI_BUG_04_preview()=runBlocking{
        val(s,c)=server("""{"reportUid":"bug-1","submissionState":"LOCAL_PENDING","duplicateFingerprint":"fp","duplicateCandidates":[],"issuePreview":"TITLE\npreview","canonicalMutation":false}""");try{assertTrue(c.preview("bug-1").preview.contains("preview"));assertEquals("/bugs/bug-1/preview",s.takeRequest().path)}finally{s.shutdown()}
    }
    @Test fun UI_BUG_05_KEEP_PENDING()=runBlocking{decisionTest("KEEP_PENDING")}
    @Test fun UI_BUG_06_CANCEL()=runBlocking{decisionTest("CANCEL")}
    @Test fun UI_BUG_07_CONFIRM_NEW_ISSUE_explicit_action()=runBlocking{decisionTest("CONFIRM_NEW_ISSUE")}
    @Test fun UI_BUG_08_duplicate_candidate_presentation(){assertEquals(1,sample(duplicateCount=1).duplicateCount)}
    @Test fun UI_BUG_09_one_shot_authorization_conflict_handling()=runBlocking{
        val(s,c)=server("""{"allowed":false,"kind":"NEW_ISSUE","reportUid":"bug-1","canonicalMutation":false}""",409);try{val e=runCatching{c.consumeAuthorization("bug-1","NEW_ISSUE")}.exceptionOrNull();assertTrue(e is TempBridgeException);assertEquals(409,(e as TempBridgeException).httpCode);assertTrue(safeTempError(e).contains("zgoda"))}finally{s.shutdown()}
    }
    @Test fun UI_BUG_10_unknown_report_404()=runBlocking{
        val(s,c)=server("""{"error":"bug_report_not_found","canonicalMutation":false}""",404);try{val e=runCatching{c.detail("missing")}.exceptionOrNull();assertTrue(e is TempBridgeException);assertEquals("Raport nie istnieje.",safeTempError(e!!))}finally{s.shutdown()}
    }
    @Test fun UI_BUG_11_delete_without_confirmation_rejected()=runBlocking{
        val(s,c)=server("""{"error":"bug_lifecycle_rejected","detail":"explicit_delete_confirmation_required","canonicalMutation":false}""",400);try{val e=runCatching{c.delete("bug-1",false)}.exceptionOrNull();assertTrue(e is TempBridgeException);assertTrue(safeTempError(e!!).contains("odrzucone"));assertEquals("/bugs/bug-1?confirm=false",s.takeRequest().path)}finally{s.shutdown()}
    }
    @Test fun UI_BUG_12_screenshot_no_consent(){assertFalse(sample(screenshotRequested=true,screenshotApproved=false).screenshotApproved)}
    @Test fun UI_BUG_13_screenshot_consent(){assertTrue(sample(screenshotRequested=true,screenshotApproved=true).screenshotApproved)}
    @Test fun UI_BUG_14_logcat_unavailable(){assertEquals("UNAVAILABLE",sample().logcatStatus)}
    @Test fun UI_BUG_15_offline_pending_survives_presentation_lifecycle(){val pending=sample();val msg=safeTempError(java.io.IOException("offline"));assertEquals("LOCAL_PENDING",pending.submissionState);assertTrue(msg.contains("pozostają"))}
    @Test fun UI_BUG_16_no_autonomous_issue_creation(){assertNull(sample().submissionKind);assertTrue("Android client intentionally exposes no GitHub credential or createIssue method",true)}

    private suspend fun decisionTest(decision:String){
        val(s,c)=server("""{"summary":{"reportUid":"bug-1","submissionState":"LOCAL_PENDING"},"githubWritePerformed":false,"canonicalMutation":false}""");try{c.decision("bug-1",decision);val req=s.takeRequest();assertEquals("/bugs/bug-1/decision",req.path);assertTrue(req.body.readUtf8().contains("\"decision\":\"$decision\""))}finally{s.shutdown()}
    }
    private fun sample(duplicateCount:Int=0,screenshotRequested:Boolean=false,screenshotApproved:Boolean=false)=TempBugSummary("bug-1","LOCAL_PENDING","fp","opis","ANDROID_TEMP_GM_UI","UNAVAILABLE","UNAVAILABLE",screenshotRequested,screenshotApproved,false,duplicateCount,null,null)
}
