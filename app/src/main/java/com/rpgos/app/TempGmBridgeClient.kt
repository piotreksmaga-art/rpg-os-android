package com.rpgos.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class TempGmStatus { OFFLINE, STARTING, READY, ERROR }

data class TempGmHealth(val bridgeConnected:Boolean, val status:TempGmStatus, val providerId:String="BIELIK_4_5B_V3", val model:String="Bielik 4.5B v3")
data class TempGmTurn(val narrative:String, val mode:String, val providerId:String)
data class TempBugSummary(val reportUid:String,val submissionState:String,val fingerprint:String,val description:String,val route:String,val logcatStatus:String,val adbState:String,val screenshotRequested:Boolean,val screenshotApproved:Boolean,val screenshotAvailable:Boolean,val duplicateCount:Int,val submissionKind:String?,val targetIssueNumber:Int?)
data class TempBugDetail(val summary:TempBugSummary,val report:JsonObject)
data class TempBugPreview(val reportUid:String,val submissionState:String,val fingerprint:String,val candidates:JsonArray,val preview:String)
data class TempBugCreated(val reportUid:String,val submissionState:String,val fingerprint:String,val logcatStatus:String,val screenshotStatus:String)

class TempBridgeException(val httpCode:Int, val safeMessage:String): IOException(safeMessage)

class TempGmBridgeClient(
    private val baseUrl:String="http://127.0.0.1:8765",
    private val http:OkHttpClient=OkHttpClient.Builder()
        .connectTimeout(2,TimeUnit.SECONDS)
        .readTimeout(SHORT_READ_TIMEOUT_SECONDS,TimeUnit.SECONDS)
        .build()
){
    companion object {
        internal const val SHORT_READ_TIMEOUT_SECONDS = 35L
        internal const val GM_TURN_READ_TIMEOUT_SECONDS = 210L
        internal const val BACKEND_GENERATION_TIMEOUT_SECONDS = 180L
    }

    private val gmTurnHttp=http.newBuilder()
        .readTimeout(GM_TURN_READ_TIMEOUT_SECONDS,TimeUnit.SECONDS)
        .build()
    private val json=Json{ignoreUnknownKeys=true}
    private val media="application/json; charset=utf-8".toMediaType()

    private suspend fun call(method:String,path:String,body:JsonObject?=null,client:OkHttpClient=http):JsonObject=withContext(Dispatchers.IO){
        val b=Request.Builder().url(baseUrl+path).header("Cache-Control","no-store")
        val rb=(body?:buildJsonObject{}).toString().toRequestBody(media)
        when(method){"GET"->b.get();"POST"->b.post(rb);"DELETE"->b.delete();else->error("unsupported method")}
        client.newCall(b.build()).execute().use{r->
            val text=r.body?.string().orEmpty(); val obj=runCatching{json.parseToJsonElement(text).jsonObject}.getOrElse{buildJsonObject{put("error","invalid_bridge_response")}}
            if(!r.isSuccessful){
                val detail=obj["detail"]?.jsonPrimitive?.contentOrNull ?: obj["error"]?.jsonPrimitive?.contentOrNull ?: "HTTP ${r.code}"
                throw TempBridgeException(r.code,detail)
            }
            if(obj["canonicalMutation"]?.jsonPrimitive?.booleanOrNull==true) throw TempBridgeException(409,"TEMP bridge violated canonicalMutation=false")
            obj
        }
    }

    suspend fun health():TempGmHealth=try{
        val o=call("GET","/health"); val p=o["provider"]?.jsonObject
        val raw=p?.get("status")?.jsonPrimitive?.contentOrNull ?: "ERROR"
        TempGmHealth(true,runCatching{TempGmStatus.valueOf(raw)}.getOrDefault(TempGmStatus.ERROR),o["activeProvider"]?.jsonPrimitive?.contentOrNull?:"BIELIK_4_5B_V3")
    }catch(_:IOException){TempGmHealth(false,TempGmStatus.OFFLINE)}

    suspend fun turn(message:String):TempGmTurn{
        val context=buildJsonObject{
            put("campaignUid","");put("worldPackUid","");put("playerIdentity",buildJsonObject{});put("sceneState",buildJsonObject{});put("playerSceneState",buildJsonObject{});put("relevantNpcs",buildJsonArray{});put("recentDialogueActions",buildJsonArray{});put("retrievedChronicleMemory",buildJsonArray{});put("availableTestCapabilities",buildJsonArray{});put("engineConfirmedResults",buildJsonArray{})
        }
        val o=call("POST","/gm/turn",buildJsonObject{put("message",message);put("mode","NARRATIVE_ONLY");put("maxTokens",1024);put("context",context)},gmTurnHttp)
        if(o["canonicalMutation"]?.jsonPrimitive?.booleanOrNull!=false) throw TempBridgeException(409,"TEMP response rejected: canonicalMutation invariant missing")
        return TempGmTurn(o["narrative"]?.jsonPrimitive?.contentOrNull.orEmpty(),o["mode"]?.jsonPrimitive?.contentOrNull?:"NARRATIVE_ONLY",o["providerId"]?.jsonPrimitive?.contentOrNull?:"BIELIK_4_5B_V3")
    }

    suspend fun createBug(description:String,includeLogcat:Boolean,includeScreenshot:Boolean,screenshotApproved:Boolean,screenshotReference:String=""):TempBugCreated{
        val o=call("POST","/bug",buildJsonObject{put("description",description);put("include_logcat",includeLogcat);put("include_screenshot",includeScreenshot);put("screenshotApproved",screenshotApproved);if(includeScreenshot&&screenshotApproved&&screenshotReference.isNotBlank())put("screenshotReference",screenshotReference);put("route","ANDROID_TEMP_GM_UI");put("build",buildJsonObject{put("versionName",BuildConfig.VERSION_NAME);put("versionCode",BuildConfig.VERSION_CODE)})})
        val cap=o["captureStatus"]?.jsonObject?:buildJsonObject{}
        return TempBugCreated(o["reportUid"]!!.jsonPrimitive.content,o["submissionState"]!!.jsonPrimitive.content,o["duplicateFingerprint"]!!.jsonPrimitive.content,cap["logcat"]?.jsonPrimitive?.contentOrNull?:"UNAVAILABLE",cap["screenshot"]?.jsonPrimitive?.contentOrNull?:"NOT_CAPTURED")
    }

    suspend fun listBugs():List<TempBugSummary>{val o=call("GET","/bugs");return o["reports"]?.jsonArray?.map{summary(it.jsonObject)}?:emptyList()}
    suspend fun detail(id:String):TempBugDetail{val o=call("GET","/bugs/${safeId(id)}");return TempBugDetail(summary(o["summary"]!!.jsonObject),o["report"]!!.jsonObject)}
    suspend fun preview(id:String):TempBugPreview{val o=call("GET","/bugs/${safeId(id)}/preview");return TempBugPreview(id,o["submissionState"]!!.jsonPrimitive.content,o["duplicateFingerprint"]!!.jsonPrimitive.content,o["duplicateCandidates"]?.jsonArray?:buildJsonArray{},o["issuePreview"]?.jsonPrimitive?.contentOrNull.orEmpty())}
    suspend fun decision(id:String,decision:String,target:Int?=null)=call("POST","/bugs/${safeId(id)}/decision",buildJsonObject{put("decision",decision);if(target!=null)put("targetIssueNumber",target)})
    suspend fun retry(id:String)=call("POST","/bugs/${safeId(id)}/retry")
    suspend fun cancel(id:String)=call("POST","/bugs/${safeId(id)}/cancel")
    suspend fun delete(id:String,confirmed:Boolean)=call("DELETE","/bugs/${safeId(id)}?confirm=$confirmed")
    suspend fun consumeAuthorization(id:String,kind:String)=call("POST","/bugs/${safeId(id)}/submission-authorization",buildJsonObject{put("kind",kind)})

    private fun safeId(id:String)=id.filter{it.isLetterOrDigit()||it in "._-"}
    private fun summary(o:JsonObject)=TempBugSummary(
        o["reportUid"]?.jsonPrimitive?.contentOrNull.orEmpty(),o["submissionState"]?.jsonPrimitive?.contentOrNull.orEmpty(),o["duplicateFingerprint"]?.jsonPrimitive?.contentOrNull.orEmpty(),o["descriptionPreview"]?.jsonPrimitive?.contentOrNull.orEmpty(),o["route"]?.jsonPrimitive?.contentOrNull.orEmpty(),o["logcatStatus"]?.jsonPrimitive?.contentOrNull?:"UNAVAILABLE",o["adbState"]?.jsonPrimitive?.contentOrNull?:"UNAVAILABLE",o["screenshotRequested"]?.jsonPrimitive?.booleanOrNull?:false,o["screenshotUserApproved"]?.jsonPrimitive?.booleanOrNull?:false,o["screenshotAvailable"]?.jsonPrimitive?.booleanOrNull?:false,o["duplicateCandidateCount"]?.jsonPrimitive?.intOrNull?:0,o["submissionKind"]?.jsonPrimitive?.contentOrNull,o["targetIssueNumber"]?.jsonPrimitive?.intOrNull)
}
