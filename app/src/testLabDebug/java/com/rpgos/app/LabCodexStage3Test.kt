package com.rpgos.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.io.File
import java.util.UUID

class LabCodexStage3Test{
    @Test
    fun `request cannot leave application while host is absent or stale`(){
        var now=1_000L
        val broker=LabCodexRequestBroker(LabAiTraceStore()){now}
        val request=transport("NO-HOST",AiWorkload.GM_PROPOSAL)

        val missing=broker.execute(request,AiCancellationSignal.NONE) as AiProviderResult.Failure
        assertEquals(AiProviderFailureKind.UNAVAILABLE,missing.kind)
        assertEquals("LAB_CODEX_HOST_NOT_REGISTERED",missing.reasonUid)

        broker.register(JSONObject().put("session_uid","SESSION").put("model_uid",LAB_CODEX_MODEL_UID))
        now+=15_001L
        val stale=broker.execute(request.copy(requestUid="STALE"),AiCancellationSignal.NONE) as AiProviderResult.Failure
        assertEquals("LAB_CODEX_HOST_HEARTBEAT_STALE",stale.reasonUid)
    }

    @Test
    fun `GM and Director use independent queues and correlate structured replies`(){
        val broker=LabCodexRequestBroker(LabAiTraceStore())
        broker.register(JSONObject().put("session_uid","SESSION").put("model_uid",LAB_CODEX_MODEL_UID))
        val pool=Executors.newFixedThreadPool(2)
        try{
            val gm=pool.submit<AiProviderResult<AiTransportResponse>>{broker.execute(transport("GM-1",AiWorkload.GM_PROPOSAL),AiCancellationSignal.NONE)}
            val director=pool.submit<AiProviderResult<AiTransportResponse>>{broker.execute(transport("DIR-1",AiWorkload.DIRECTOR_STRATEGY),AiCancellationSignal.NONE)}

            val claimedDirector=claimEventually(broker,"DIRECTOR")
            val claimedGm=claimEventually(broker,"GAME_MASTER")
            assertEquals("DIR-1",claimedDirector.getString("ai_request_uid"))
            assertEquals("GM-1",claimedGm.getString("ai_request_uid"))
            broker.complete(completion("SESSION","DIR-1",JSONObject().put("bundle_uid","BUNDLE")))
            broker.complete(completion("SESSION","GM-1",JSONObject().put("proposal_uid","PROPOSAL")))

            val gmResult=gm.get(2,TimeUnit.SECONDS) as AiProviderResult.Success
            val directorResult=director.get(2,TimeUnit.SECONDS) as AiProviderResult.Success
            assertEquals("PROPOSAL",JSONObject(gmResult.value.structuredPayload).getString("proposal_uid"))
            assertEquals("BUNDLE",JSONObject(directorResult.value.structuredPayload).getString("bundle_uid"))
            assertFalse(broker.state().getBoolean("host_connected").not())
            assertEquals(0,broker.state().getInt("active_requests"))
        }finally{pool.shutdownNow()}
    }

    @Test
    fun `claimed request rejects response from another host session`(){
        val broker=LabCodexRequestBroker(LabAiTraceStore())
        broker.register(JSONObject().put("session_uid","SESSION-A"))
        val pool=Executors.newSingleThreadExecutor()
        try{
            val future=pool.submit<AiProviderResult<AiTransportResponse>>{broker.execute(transport("REQ",AiWorkload.INTENT_INTERPRETATION),AiCancellationSignal.NONE)}
            claimEventually(broker,"GAME_MASTER","SESSION-A")
            assertThrows(IllegalArgumentException::class.java){broker.complete(completion("SESSION-B","REQ",JSONObject()))}
            assertTrue(broker.cancel("REQ"))
            val result=future.get(2,TimeUnit.SECONDS) as AiProviderResult.Failure
            assertEquals(AiProviderFailureKind.CANCELLED,result.kind)
        }finally{pool.shutdownNow()}
    }

    @Test
    fun `malformed completion does not strand a claimed request`(){
        val broker=LabCodexRequestBroker(LabAiTraceStore())
        broker.register(JSONObject().put("session_uid","SESSION"))
        val pool=Executors.newSingleThreadExecutor()
        try{
            val future=pool.submit<AiProviderResult<AiTransportResponse>>{broker.execute(transport("REQ-MALFORMED",AiWorkload.GM_PROPOSAL),AiCancellationSignal.NONE)}
            claimEventually(broker,"GAME_MASTER")
            assertThrows(IllegalArgumentException::class.java){
                broker.complete(JSONObject().put("session_uid","SESSION").put("ai_request_uid","REQ-MALFORMED"))
            }
            broker.complete(completion("SESSION","REQ-MALFORMED",JSONObject().put("proposal_uid","RECOVERED")))
            val result=future.get(2,TimeUnit.SECONDS) as AiProviderResult.Success
            assertEquals("RECOVERED",JSONObject(result.value.structuredPayload).getString("proposal_uid"))
        }finally{pool.shutdownNow()}
    }

    @Test
    fun `host replacement fails in flight work with a typed reason`(){
        val broker=LabCodexRequestBroker(LabAiTraceStore())
        broker.register(JSONObject().put("session_uid","SESSION-A"))
        val pool=Executors.newSingleThreadExecutor()
        try{
            val future=pool.submit<AiProviderResult<AiTransportResponse>>{broker.execute(transport("IN-FLIGHT",AiWorkload.NARRATIVE_RENDER),AiCancellationSignal.NONE)}
            claimEventually(broker,"GAME_MASTER","SESSION-A")
            broker.register(JSONObject().put("session_uid","SESSION-B"))
            val result=future.get(2,TimeUnit.SECONDS) as AiProviderResult.Failure
            assertEquals(AiProviderFailureKind.UNAVAILABLE,result.kind)
            assertEquals("LAB_CODEX_HOST_REPLACED",result.reasonUid)
        }finally{pool.shutdownNow()}
    }

    @Test
    fun `Director jobs and candidate bundles survive store recreation`(){
        val root=File("build/test-stage3/${UUID.randomUUID()}")
        val jobRoot=File(root,"jobs");val candidateRoot=File(root,"candidates")
        try{
            val job=DirectorJobRecord("JOB","CAMPAIGN","TRIGGER","VERSION",10,DirectorJobState.RESERVED)
            val firstJobs=LabPersistentDirectorJobStore(jobRoot)
            assertTrue(firstJobs.reserve(job))
            firstJobs.transition(job.copy(state=DirectorJobState.ACCEPTED,providerUid=LAB_CODEX_PROVIDER_UID,modelUid=LAB_CODEX_MODEL_UID))
            assertEquals(10L,LabPersistentDirectorJobStore(jobRoot).lastAcceptedOrder("CAMPAIGN"))

            val candidate=DirectorCandidate("C",DirectorCandidateKind.QUEST_SEED,"Tytuł","Opis",emptyList(),"NEXT",emptySet(),"PHASE65_DIRECTOR")
            val bundle=DirectorBundle(1,"BUNDLE","JOB","CAMPAIGN","TRIGGER","VERSION",10,LAB_CODEX_PROVIDER_UID,LAB_CODEX_MODEL_UID,listOf(candidate),"FP")
            LabPersistentDirectorCandidateStore(candidateRoot).put(bundle)
            assertEquals(bundle,LabPersistentDirectorCandidateStore(candidateRoot).latest("CAMPAIGN"))
        }finally{root.deleteRecursively()}
    }

    @Test
    fun `Director closes abandoned jobs after application process restart`(){
        val root=File("build/test-stage3/${UUID.randomUUID()}")
        try{
            val store=LabPersistentDirectorJobStore(root)
            val reserved=DirectorJobRecord("RESERVED","CAMPAIGN","T1","V",8,DirectorJobState.RESERVED)
            val running=DirectorJobRecord("RUNNING","CAMPAIGN","T2","V",9,DirectorJobState.RUNNING,LAB_CODEX_PROVIDER_UID,LAB_CODEX_MODEL_UID)
            val accepted=DirectorJobRecord("ACCEPTED","CAMPAIGN","T3","V",7,DirectorJobState.ACCEPTED,LAB_CODEX_PROVIDER_UID,LAB_CODEX_MODEL_UID)
            listOf(reserved,running,accepted).forEach{assertTrue(store.reserve(it))}

            assertEquals(2,LabPersistentDirectorJobStore(root).recoverAbandoned("CAMPAIGN"))
            val recovered=LabPersistentDirectorJobStore(root)
            assertEquals(DirectorJobState.FAILED,recovered.find("RESERVED")?.state)
            assertEquals("DIRECTOR_PROCESS_RESTARTED",recovered.find("RUNNING")?.terminalReasonUid)
            assertEquals(DirectorJobState.ACCEPTED,recovered.find("ACCEPTED")?.state)
        }finally{root.deleteRecursively()}
    }

    @Test
    fun `Director guidance cannot carry a direct mutation payload`(){
        val candidate=DirectorCandidate(
            "CANDIDATE",DirectorCandidateKind.QUEST_SEED,"Tytuł","Opis",emptyList(),"NEXT_TURNS",emptySet(),"PHASE65_DIRECTOR","{\"write\":true}"
        )
        assertThrows(IllegalArgumentException::class.java){
            DirectorGuidanceEnvelope("CAMPAIGN","BUNDLE","VERSION",1,listOf(candidate))
        }
    }

    private fun claimEventually(broker:LabCodexRequestBroker,lane:String,session:String="SESSION"):JSONObject{
        repeat(20){
            val value=broker.claim(JSONObject().put("session_uid",session).put("lane",lane).put("wait_ms",100))
            if(value.optBoolean("available"))return value
        }
        error("request not queued for $lane")
    }
    private fun completion(session:String,request:String,payload:JSONObject)=JSONObject()
        .put("session_uid",session).put("ai_request_uid",request).put("structured_payload",payload)
    private fun transport(uid:String,workload:AiWorkload)=AiTransportRequest(uid,workload,1,"{}",1024)
}
