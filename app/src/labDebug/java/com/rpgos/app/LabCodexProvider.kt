package com.rpgos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

internal const val LAB_CODEX_PROVIDER_UID="LAB_CODEX"
internal const val LAB_CODEX_MODEL_UID="gpt-5.6-sol"
private const val HOST_FRESH_MILLIS=15_000L
private const val GM_TIMEOUT_MILLIS=180_000L
private const val DIRECTOR_TIMEOUT_MILLIS=300_000L

internal enum class LabAiLane{GAME_MASTER,DIRECTOR}
internal enum class LabAiRequestState{QUEUED,CLAIMED,COMPLETED,FAILED,CANCELLED,TIMED_OUT}

internal data class LabAiHostRegistration(
    val sessionUid:String,val modelUid:String,val hostUid:String,val registeredAt:Long,val lastHeartbeatAt:Long
)

internal data class LabAiPendingRequest(
    val transport:AiTransportRequest,
    val lane:LabAiLane,
    val createdAt:Long,
    val deadlineAt:Long,
    val outputSchema:String,
    val result:CompletableFuture<AiProviderResult<AiTransportResponse>> = CompletableFuture(),
    val state:AtomicReference<LabAiRequestState> = AtomicReference(LabAiRequestState.QUEUED),
    @Volatile var claimedBySessionUid:String?=null
)

/** Host-facing status contract; implementation is lab-only and never exported from release. */
internal fun interface LabAiHostStatusPort{fun state():JSONObject}

internal class LabAiTraceStore:AiWireTracePort{
    private val lock=Any()
    private val events=ArrayDeque<AiWireTraceEvent>()
    override fun record(event:AiWireTraceEvent)=synchronized(lock){
        events.addLast(event.copy(payload=event.payload?.take(750_000)))
        while(events.size>500)events.removeFirst()
    }
    fun read(limit:Int,requestUidPrefix:String?):JSONObject=synchronized(lock){
        val selected=events.asSequence().filter{requestUidPrefix==null||it.requestUid.startsWith(requestUidPrefix)}
            .toList().takeLast(limit).map(::eventJson)
        JSONObject().put("event_count",selected.size).put("events",JSONArray(selected))
    }
    fun latestExchange(workloadName:String?):JSONObject=synchronized(lock){
        val workload=workloadName?.let{name->AiWorkload.valueOf(name.trim().uppercase())}
        val reversed=events.toList().asReversed()
        val response=reversed.firstOrNull{it.direction=="RESPONSE"&&(workload==null||it.workload==workload)}
            ?:return@synchronized JSONObject().put("available",false)
        val request=reversed.firstOrNull{it.direction=="REQUEST"&&it.requestUid==response.requestUid&&it.workload==response.workload}
        JSONObject().put("available",true).put("workload",response.workload.name).put("request_uid",response.requestUid)
            .put("request",request?.let(::eventJson)?:JSONObject.NULL).put("response",eventJson(response))
    }
    fun clear():JSONObject=synchronized(lock){val removed=events.size;events.clear();JSONObject().put("removed_events",removed)}
    private fun eventJson(event:AiWireTraceEvent)=JSONObject()
        .put("direction",event.direction).put("request_uid",event.requestUid).put("workload",event.workload.name)
        .put("provider_uid",event.providerUid).put("model_uid",event.modelUid)
        .put("payload",event.payload?:JSONObject.NULL).put("trace_uid",event.traceUid?:JSONObject.NULL)
        .put("input_units",event.inputUnits?:JSONObject.NULL).put("output_units",event.outputUnits?:JSONObject.NULL)
        .put("failure_kind",event.failureKind?.name?:JSONObject.NULL).put("reason_uid",event.reasonUid?:JSONObject.NULL)
        .put("at_epoch_ms",event.atEpochMillis)
}

internal class LabCodexRequestBroker(
    private val trace:AiWireTracePort,
    private val clock:()->Long={System.currentTimeMillis()}
):AiStructuredTransport,LabAiHostStatusPort{
    private val host=AtomicReference<LabAiHostRegistration?>(null)
    private val gmQueue=LinkedBlockingQueue<LabAiPendingRequest>()
    private val directorQueue=LinkedBlockingQueue<LabAiPendingRequest>()
    private val pending=ConcurrentHashMap<String,LabAiPendingRequest>()
    @Volatile private var lastReasonUid:String="HOST_NOT_REGISTERED"
    @Volatile private var lastRequestErrorUid:String?=null

    fun register(arguments:JSONObject):JSONObject{
        val sessionUid=arguments.requiredLabString("session_uid")
        val modelUid=arguments.optString("model_uid",LAB_CODEX_MODEL_UID).trim()
        require(modelUid==LAB_CODEX_MODEL_UID){"LAB_CODEX_MODEL_MISMATCH:$modelUid"}
        val now=clock()
        val registration=LabAiHostRegistration(
            sessionUid,modelUid,arguments.optString("host_uid","CODEX_HOST").trim().ifBlank{"CODEX_HOST"},now,now
        )
        val previous=host.getAndSet(registration)
        if(previous!=null&&previous.sessionUid!=sessionUid)failAll("LAB_CODEX_HOST_REPLACED")
        lastReasonUid="READY"
        return state().put("registered",true)
    }

    fun heartbeat(arguments:JSONObject):JSONObject{
        val sessionUid=arguments.requiredLabString("session_uid")
        val current=requireHost(sessionUid)
        host.set(current.copy(lastHeartbeatAt=clock()))
        lastReasonUid="READY"
        return state()
    }

    fun claim(arguments:JSONObject):JSONObject{
        val sessionUid=arguments.requiredLabString("session_uid")
        requireHost(sessionUid)
        val lane=when(arguments.optString("lane","GAME_MASTER").trim().uppercase()){
            "GAME_MASTER","GM"->LabAiLane.GAME_MASTER
            "DIRECTOR"->LabAiLane.DIRECTOR
            else->throw IllegalArgumentException("LAB_CODEX_LANE_INVALID")
        }
        val waitMillis=arguments.optLong("wait_ms",5_000L).coerceIn(0L,10_000L)
        val queue=if(lane==LabAiLane.DIRECTOR)directorQueue else gmQueue
        val deadline=clock()+waitMillis
        while(true){
            val remaining=(deadline-clock()).coerceAtLeast(0L)
            val request=if(remaining==0L)queue.poll() else queue.poll(remaining,TimeUnit.MILLISECONDS)
                ?:return JSONObject().put("available",false).put("lane",lane.name)
            if(request.state.compareAndSet(LabAiRequestState.QUEUED,LabAiRequestState.CLAIMED)){
                request.claimedBySessionUid=sessionUid
                return pendingJson(request).put("available",true)
            }
            if(clock()>=deadline)return JSONObject().put("available",false).put("lane",lane.name)
        }
    }

    fun complete(arguments:JSONObject):JSONObject{
        val sessionUid=arguments.requiredLabString("session_uid")
        requireHost(sessionUid)
        val requestUid=arguments.requiredLabString("ai_request_uid")
        val request=requireNotNull(pending[requestUid]){"LAB_CODEX_REQUEST_NOT_FOUND"}
        require(request.claimedBySessionUid==sessionUid){"LAB_CODEX_REQUEST_SESSION_MISMATCH"}
        val structured=when(val value=arguments.opt("structured_payload")){
            is JSONObject,is JSONArray->value.toString()
            is String->value
            else->throw IllegalArgumentException("LAB_CODEX_STRUCTURED_PAYLOAD_REQUIRED")
        }
        val traceUid=arguments.optString("trace_uid").trim().ifBlank{"LAB-CODEX:${UUID.randomUUID()}"}
        require(request.state.compareAndSet(LabAiRequestState.CLAIMED,LabAiRequestState.COMPLETED)){
            "LAB_CODEX_REQUEST_NOT_COMPLETABLE:${request.state.get()}"
        }
        request.result.complete(AiProviderResult.Success(
            AiTransportResponse(requestUid,structured,traceUid),LAB_CODEX_PROVIDER_UID,LAB_CODEX_MODEL_UID,traceUid
        ))
        pending.remove(requestUid,request)
        trace.record(AiWireTraceEvent("RESPONSE",requestUid,request.transport.workload,LAB_CODEX_PROVIDER_UID,LAB_CODEX_MODEL_UID,
            payload=structured,traceUid=traceUid,outputUnits=structured.length/4+1))
        return JSONObject().put("completed",true).put("ai_request_uid",requestUid)
    }

    fun fail(arguments:JSONObject):JSONObject{
        val sessionUid=arguments.requiredLabString("session_uid")
        requireHost(sessionUid)
        val requestUid=arguments.requiredLabString("ai_request_uid")
        val reason=arguments.optString("reason_uid","LAB_CODEX_HOST_FAILURE").trim().ifBlank{"LAB_CODEX_HOST_FAILURE"}
        val retryable=arguments.optBoolean("retryable",true)
        finishFailure(requestUid,LabAiRequestState.FAILED,AiProviderFailureKind.UNAVAILABLE,reason,retryable,sessionUid)
        return JSONObject().put("failed",true).put("ai_request_uid",requestUid).put("reason_uid",reason)
    }

    fun cancel(requestUid:String,reasonUid:String="LAB_CODEX_CANCELLED"):Boolean{
        val request=pending[requestUid]?:return false
        while(true){
            val current=request.state.get()
            if(current.terminal())return false
            if(request.state.compareAndSet(current,LabAiRequestState.CANCELLED)){
                request.result.complete(AiProviderResult.Failure(AiProviderFailureKind.CANCELLED,reasonUid,false))
                pending.remove(requestUid,request)
                lastRequestErrorUid=reasonUid
                traceFailure(request,AiProviderFailureKind.CANCELLED,reasonUid)
                return true
            }
        }
    }

    override fun execute(request:AiTransportRequest,cancellation:AiCancellationSignal):AiProviderResult<AiTransportResponse>{
        if(!hostReady())return AiProviderResult.Failure(AiProviderFailureKind.UNAVAILABLE,currentHostReason(),true)
        if(cancellation.isCancelled())return AiProviderResult.Failure(AiProviderFailureKind.CANCELLED,"LAB_CODEX_CANCELLED_BEFORE_QUEUE")
        val lane=if(request.workload==AiWorkload.DIRECTOR_STRATEGY)LabAiLane.DIRECTOR else LabAiLane.GAME_MASTER
        val now=clock();val timeout=if(lane==LabAiLane.DIRECTOR)DIRECTOR_TIMEOUT_MILLIS else GM_TIMEOUT_MILLIS
        val pendingRequest=LabAiPendingRequest(request,lane,now,now+timeout,OpenRouterStructuredOutputSchema.schema(request.workload).toString())
        require(pending.putIfAbsent(request.requestUid,pendingRequest)==null){"LAB_CODEX_DUPLICATE_REQUEST_UID"}
        trace.record(AiWireTraceEvent("REQUEST",request.requestUid,request.workload,LAB_CODEX_PROVIDER_UID,LAB_CODEX_MODEL_UID,
            payload=request.payload,inputUnits=request.payload.length/4+1))
        (if(lane==LabAiLane.DIRECTOR)directorQueue else gmQueue).put(pendingRequest)
        while(true){
            if(cancellation.isCancelled()){
                cancel(request.requestUid,"LAB_CODEX_CANCELLED_BY_CALLER")
                return AiProviderResult.Failure(AiProviderFailureKind.CANCELLED,"LAB_CODEX_CANCELLED_BY_CALLER")
            }
            if(!hostReady()){
                finishFailure(request.requestUid,LabAiRequestState.FAILED,AiProviderFailureKind.UNAVAILABLE,currentHostReason(),true,null)
            }
            val remaining=pendingRequest.deadlineAt-clock()
            if(remaining<=0L){
                finishFailure(request.requestUid,LabAiRequestState.TIMED_OUT,AiProviderFailureKind.TIMEOUT,"LAB_CODEX_TIMEOUT",true,null)
            }
            try{return pendingRequest.result.get(remaining.coerceIn(1L,250L),TimeUnit.MILLISECONDS)}
            catch(_:TimeoutException){Unit}
        }
    }

    override fun state():JSONObject{
        val current=host.get();val now=clock();val age=current?.let{(now-it.lastHeartbeatAt).coerceAtLeast(0L)}
        return JSONObject()
            .put("provider_uid",LAB_CODEX_PROVIDER_UID).put("model_uid",LAB_CODEX_MODEL_UID)
            .put("host_connected",hostReady()).put("reason_uid",currentHostReason())
            .put("last_error_uid",lastRequestErrorUid?:JSONObject.NULL)
            .put("session_uid",current?.sessionUid?:JSONObject.NULL).put("host_uid",current?.hostUid?:JSONObject.NULL)
            .put("heartbeat_age_ms",age?:JSONObject.NULL).put("heartbeat_fresh_ms",HOST_FRESH_MILLIS)
            .put("game_master_queue",gmQueue.count{!it.state.get().terminal()})
            .put("director_queue",directorQueue.count{!it.state.get().terminal()})
            .put("active_requests",pending.values.count{it.state.get()==LabAiRequestState.CLAIMED})
            .put("pending_requests",JSONArray(pending.values.sortedBy{it.createdAt}.map(::pendingJson)))
    }

    private fun finishFailure(requestUid:String,state:LabAiRequestState,kind:AiProviderFailureKind,reason:String,retryable:Boolean,sessionUid:String?){
        val request=pending[requestUid]?:return
        if(sessionUid!=null&&request.claimedBySessionUid!=sessionUid)throw IllegalArgumentException("LAB_CODEX_REQUEST_SESSION_MISMATCH")
        val current=request.state.get()
        if(current.terminal())return
        if(request.state.compareAndSet(current,state)){
            lastRequestErrorUid=reason
            request.result.complete(AiProviderResult.Failure(kind,reason,retryable));pending.remove(requestUid,request);traceFailure(request,kind,reason)
        }
    }
    private fun failAll(reason:String)=pending.keys.toList().forEach{
        finishFailure(it,LabAiRequestState.FAILED,AiProviderFailureKind.UNAVAILABLE,reason,true,null)
    }
    private fun traceFailure(request:LabAiPendingRequest,kind:AiProviderFailureKind,reason:String)=trace.record(
        AiWireTraceEvent("FAILURE",request.transport.requestUid,request.transport.workload,LAB_CODEX_PROVIDER_UID,LAB_CODEX_MODEL_UID,
            failureKind=kind,reasonUid=reason)
    )
    private fun hostReady():Boolean=host.get()?.let{clock()-it.lastHeartbeatAt<=HOST_FRESH_MILLIS}==true
    private fun currentHostReason():String=when{
        host.get()==null->"LAB_CODEX_HOST_NOT_REGISTERED"
        !hostReady()->"LAB_CODEX_HOST_HEARTBEAT_STALE"
        else->lastReasonUid
    }
    private fun requireHost(sessionUid:String):LabAiHostRegistration{
        val current=requireNotNull(host.get()){"LAB_CODEX_HOST_NOT_REGISTERED"}
        require(current.sessionUid==sessionUid){"LAB_CODEX_HOST_SESSION_MISMATCH"}
        return current
    }
    private fun pendingJson(request:LabAiPendingRequest)=JSONObject()
        .put("ai_request_uid",request.transport.requestUid).put("workload",request.transport.workload.name)
        .put("schema_version",request.transport.schemaVersion).put("lane",request.lane.name)
        .put("state",request.state.get().name).put("created_at_epoch_ms",request.createdAt)
        .put("deadline_at_epoch_ms",request.deadlineAt).put("maximum_output_units",request.transport.maximumOutputUnits)
        .put("request_payload",runCatching{JSONObject(request.transport.payload)}.getOrElse{request.transport.payload})
        .put("output_schema",JSONObject(request.outputSchema))
    private fun LabAiRequestState.terminal()=this in setOf(
        LabAiRequestState.COMPLETED,LabAiRequestState.FAILED,LabAiRequestState.CANCELLED,LabAiRequestState.TIMED_OUT
    )
}

/** Name used by the Stage-3 public lab contract; retained alias keeps older tests/source compatible. */
internal typealias LabCodexStructuredTransport=LabCodexRequestBroker

private class LabCodexAiProvider(
    private val delegate:TransportAiProviderAdapter,
    private val broker:LabCodexRequestBroker
):AiProvider by delegate,AiProviderAvailabilityReporter{
    override fun currentAvailability():AiProviderAvailability{
        val state=broker.state();val ready=state.optBoolean("host_connected")
        return AiProviderAvailability(
            AiModelSelection(LAB_CODEX_PROVIDER_UID,LAB_CODEX_MODEL_UID),
            if(ready)AiAvailabilityState.READY else AiAvailabilityState.UNAVAILABLE,
            state.optString("reason_uid","LAB_CODEX_HOST_NOT_REGISTERED"),resourceAdmitted=ready
        )
    }
}

private class LabCodexProviderExtension(
    context:Context,
    private val provider:LabCodexAiProvider,
    private val broker:LabCodexRequestBroker,
    private val director:LabDirectorCoordinator
):AiProviderExtension{
    private val prefs=context.getSharedPreferences("rpgos_lab_ai_stage3",Context.MODE_PRIVATE)
    override val extensionUid="RPGOS_LAB_CODEX_STAGE3"
    private val selection=AiModelSelection(LAB_CODEX_PROVIDER_UID,LAB_CODEX_MODEL_UID)
    override fun providers()=listOf(provider)
    override fun overrideConfiguration(base:AiSystemConfiguration):AiSystemConfiguration=base.copy(
        gameMaster=if(prefs.getBoolean("pin_game_master",true))AiRoleAssignment(AiRole.GAME_MASTER,AiAssignmentKind.PINNED,selection) else base.gameMaster,
        director=if(prefs.getBoolean("pin_director",true))AiRoleAssignment(AiRole.DIRECTOR_SCENARIST,AiAssignmentKind.PINNED,selection) else base.director
    )
    override fun modelOptions():List<AiModelOptionUi>{
        val availability=provider.currentAvailability()
        return listOf(AiModelOptionUi(selection,"Codex gpt-5.6-sol (LAB)",AiProviderKind.CLOUD,availability.state,availability.reasonUid))
    }
    override fun onCampaignOpened(campaignUid:String)=director.onCampaignOpened(campaignUid)
    override fun onCanonicalCommit(receipt:TurnCommitReceipt)=director.onCanonicalCommit(receipt)
    override fun onCharacterCreated(campaignUid:String,playerUid:String)=director.onCharacterCreated(campaignUid,playerUid)
    override fun directorGuidancePort():DirectorGuidancePort=director
    override fun assign(role:AiRole,selection:AiModelSelection?):Boolean{
        val key=if(role==AiRole.GAME_MASTER)"pin_game_master" else "pin_director"
        if(selection==this.selection){prefs.edit().putBoolean(key,true).apply();return true}
        if(prefs.getBoolean(key,true))prefs.edit().putBoolean(key,false).apply()
        return false
    }
    fun setAssignments(arguments:JSONObject):JSONObject{
        val editor=prefs.edit()
        if(arguments.has("game_master"))editor.putBoolean("pin_game_master",arguments.optString("game_master").equals("PINNED",true))
        if(arguments.has("director"))editor.putBoolean("pin_director",arguments.optString("director").equals("PINNED",true))
        editor.apply()
        return assignmentState()
    }
    fun assignmentState()=JSONObject()
        .put("game_master",if(prefs.getBoolean("pin_game_master",true))"PINNED" else "BASE")
        .put("director",if(prefs.getBoolean("pin_director",true))"PINNED" else "BASE")
        .put("selection",selection.stableUid).put("provider",broker.state())
}

internal object LabCodexProviderRuntime{
    val trace=LabAiTraceStore()
    private val broker=LabCodexRequestBroker(trace)
    @Volatile private var extension:LabCodexProviderExtension?=null
    @Volatile private var directorRuntime:LabDirectorCoordinator?=null

    fun install(context:Context){
        if(extension!=null)return
        synchronized(this){
            if(extension!=null)return
            val capabilities=AiCapabilityContract(
                "RPGOS-LAB-CODEX-1",LAB_CODEX_PROVIDER_UID,LAB_CODEX_MODEL_UID,AiWorkload.entries.toSet(),
                maximumContextUnits=32_768,providerKind=AiProviderKind.CLOUD,supportsJsonSchema=true
            )
            val adapter=TransportAiProviderAdapter(capabilities,broker,CanonicalAiJsonCodec(),maximumOutputUnits=8_192,
                cancellationHook={broker.cancel(it)})
            val director=LabDirectorCoordinator(context.applicationContext)
            val installed=LabCodexProviderExtension(context.applicationContext,LabCodexAiProvider(adapter,broker),broker,director)
            directorRuntime=director;extension=installed;AiProviderExtensionRegistry.register(installed)
        }
    }
    fun register(arguments:JSONObject)=broker.register(arguments)
    fun heartbeat(arguments:JSONObject)=broker.heartbeat(arguments)
    fun claim(arguments:JSONObject)=broker.claim(arguments)
    fun complete(arguments:JSONObject)=broker.complete(arguments)
    fun fail(arguments:JSONObject)=broker.fail(arguments)
    fun cancel(arguments:JSONObject)=JSONObject().put("cancelled",broker.cancel(arguments.requiredLabString("ai_request_uid")))
    fun state():JSONObject=(extension?.assignmentState()?:JSONObject().put("installed",false)).put("bridge_stage",3)
        .put("director",directorRuntime?.state()?:JSONObject.NULL)
    fun setAssignments(arguments:JSONObject)=requireNotNull(extension){"LAB_CODEX_EXTENSION_NOT_INSTALLED"}.setAssignments(arguments)
        .also{directorRuntime?.onHostReady()}
    fun directorState()=requireNotNull(directorRuntime){"LAB_DIRECTOR_NOT_INSTALLED"}.state()
    fun directorJobs()=requireNotNull(directorRuntime){"LAB_DIRECTOR_NOT_INSTALLED"}.jobsJson()
    fun directorCandidates()=requireNotNull(directorRuntime){"LAB_DIRECTOR_NOT_INSTALLED"}.candidatesJson()
    fun directorGuidance(arguments:JSONObject)=requireNotNull(directorRuntime){"LAB_DIRECTOR_NOT_INSTALLED"}.guidanceJson(arguments)
    fun runDirector(arguments:JSONObject)=requireNotNull(directorRuntime){"LAB_DIRECTOR_NOT_INSTALLED"}.runNow(arguments)
    fun clearDirector()=requireNotNull(directorRuntime){"LAB_DIRECTOR_NOT_INSTALLED"}.clear()
}

private fun JSONObject.requiredLabString(key:String)=optString(key).trim().takeIf(String::isNotBlank)
    ?:throw IllegalArgumentException("LAB_CODEX_FIELD_REQUIRED:$key")
