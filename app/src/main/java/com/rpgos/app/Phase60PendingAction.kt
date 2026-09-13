package com.rpgos.app

import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.json.*

/** Persists the user's uncommitted request, never a model answer or a second source of truth. */
interface PendingChatActionPort {
    fun begin(request:ChatTurnRequest)
    fun finished(request:ChatTurnRequest)
    fun pendingInput():String?
    companion object { val NONE=object:PendingChatActionPort {
        override fun begin(request:ChatTurnRequest){}
        override fun finished(request:ChatTurnRequest){}
        override fun pendingInput():String?=null
    } }
}

internal class FilePendingChatActionStore(private val directory:File,private val campaignUid:String,
    private val currentScope:()->TemporalScope,private val hasReceipt:(String)->Boolean,
    private val discardCheckpoint:(String)->Unit):PendingChatActionPort {
    private data class Marker(val scope:TemporalScope,val requestUid:String,val transactionUid:String,val commandUid:String,val input:String)
    private val file get()=AtomicFile(File(directory,phase60Hash(campaignUid)+".pending-action"))
    override fun begin(request:ChatTurnRequest):Unit=CampaignRuntimeLifecycleLock.withTurn(campaignUid) {
        require(request.campaignUid==campaignUid && request.input.length<=65_536)
        val scope=currentScope()
        require(request.atOrder==null || request.atOrder==Math.addExact(scope.baseCommitOrder,1L)){"P60:STALE_TURN_CONTEXT"}
        val previous=read()
        // Retrying an interrupted request recomputes its Core proposal. Cached candidates are not
        // replayed as authoritative commands; a new command gets a fresh identity.
        if(previous!=null && previous.commandUid!=request.commandUid)discardCheckpoint(previous.commandUid)
        val payload=buildJsonObject {
            put("version",1);put("campaign",campaignUid);put("generation",scope.historyGenerationUid)
            put("order",scope.baseCommitOrder);put("digest",scope.authoritativeFingerprint)
            put("request",request.requestUid);put("transaction",request.transactionUid);put("command",request.commandUid);put("input",request.input)
        }.toString()
        val bytes=buildJsonObject{put("sha256",phase60Hash(payload));put("payload",payload)}.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size<=MAX_BYTES)
        check(directory.isDirectory||directory.mkdirs())
        val atomic=file;val stream=atomic.startWrite()
        try{stream.write(bytes);atomic.finishWrite(stream)}catch(failure:Exception){atomic.failWrite(stream);throw failure}
    }
    override fun finished(request:ChatTurnRequest):Unit=CampaignRuntimeLifecycleLock.withTurn(campaignUid) {
        require(request.campaignUid==campaignUid)
        if(read()?.requestUid==request.requestUid)file.delete()
        discardCheckpoint(request.commandUid)
    }
    override fun pendingInput():String?=CampaignRuntimeLifecycleLock.withTurn(campaignUid) {
        val marker=read()?:return@withTurn null
        if(marker.scope!=currentScope() || hasReceipt(marker.transactionUid)) {
            file.delete();discardCheckpoint(marker.commandUid);return@withTurn null
        }
        marker.input
    }
    private fun read():Marker?=try {
        val text=file.openRead().use { input->
            val bytes=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){val count=input.read(buffer);if(count<0)break;require(bytes.size()<=MAX_BYTES-count);bytes.write(buffer,0,count)}
            bytes.toByteArray().toString(Charsets.UTF_8)
        }
        val envelope=Json.parseToJsonElement(text).jsonObject
        require(envelope.keys==setOf("sha256","payload"))
        val payload=envelope.getValue("payload").jsonPrimitive.let{require(it.isString);it.content}
        require(envelope.getValue("sha256").jsonPrimitive.content==phase60Hash(payload))
        val row=Json.parseToJsonElement(payload).jsonObject
        require(row.keys==setOf("version","campaign","generation","order","digest","request","transaction","command","input"))
        fun text(key:String)=row.getValue(key).jsonPrimitive.let{require(it.isString && it.content.isNotBlank());it.content}
        fun number(key:String)=row.getValue(key).jsonPrimitive.let{require(!it.isString);it.long}
        require(number("version")==1L && text("campaign")==campaignUid && text("input").length<=65_536)
        Marker(TemporalScope(campaignUid,text("generation"),number("order"),text("digest")),text("request"),text("transaction"),text("command"),text("input"))
    }catch(_:java.io.FileNotFoundException){null}catch(_:Exception){file.delete();null}
    companion object {
        private const val MAX_BYTES=1_048_576
        fun clearCampaign(directory:File,campaignUid:String){require(campaignUid.isNotBlank());AtomicFile(File(directory,phase60Hash(campaignUid)+".pending-action")).delete()}
    }
}
