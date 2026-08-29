package com.rpgos.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LlamaCppBekkoEmbeddingProvider(
    context:Context,
    private val modelFile:File,
    private val backend:EmbeddingBackend=EmbeddingBackend.CPU,
    private val threads:Int=4
):EmbeddingProviderPort{
    override val capabilities=EmbeddingCapabilities(
        providerUid="LOCAL:LLAMA_CPP:BEKKO_A8M",modelUid=BEKKO_MODEL_UID,modelRevision=BEKKO_SOURCE_REVISION,
        sourceDimensions=384,supportedDimensions=setOf(64,128,256,384),maximumContextUnits=8192,
        maximumBatchSize=64,supportedBackends=setOf(EmbeddingBackend.CPU,EmbeddingBackend.VULKAN)
    )
    private val app=context.applicationContext
    private val active=ConcurrentHashMap.newKeySet<String>()
    @Volatile private var connection:RemoteConnection?=null
    @Volatile private var service:ILlamaCppInferenceService?=null
    @Volatile private var nativeHandle:Long=0

    override fun availability():EmbeddingAvailability=when{
        !NativeLocalInferenceBridge.available->EmbeddingAvailability(EmbeddingAvailabilityState.UNAVAILABLE,"BEKKO_NATIVE_RUNTIME_UNAVAILABLE")
        !modelFile.isFile||modelFile.length()!=BEKKO_MODEL_BYTES->EmbeddingAvailability(EmbeddingAvailabilityState.NOT_INSTALLED,"BEKKO_MODEL_NOT_INSTALLED")
        else->EmbeddingAvailability(EmbeddingAvailabilityState.READY,"BEKKO_READY")
    }

    override fun open():EmbeddingAvailability=try{
        ensureOpen()
        EmbeddingAvailability(EmbeddingAvailabilityState.READY,"BEKKO_RUNTIME_READY")
    }catch(failure:Throwable){
        close()
        val reason=failure.message?.takeIf{it.startsWith("LLAMA_")||it.startsWith("BEKKO_")}
            ?:"BEKKO_RUNTIME_OPEN_FAILED:${failure::class.java.simpleName}"
        EmbeddingAvailability(EmbeddingAvailabilityState.DEGRADED,reason)
    }

    @Synchronized private fun ensureOpen():ILlamaCppInferenceService{
        if(nativeHandle!=0L)return service?:error("BEKKO_SERVICE_DIED")
        val available=availability();if(available.state!=EmbeddingAvailabilityState.READY)error(available.reasonUid)
        val remote=RemoteConnection(app);val bound=remote.connect()
        return try{
            val handle=bound.openEmbedding(
                modelFile.absolutePath,8192,backend.name,threads,512,if(backend==EmbeddingBackend.VULKAN)-1 else 0,true
            )
            require(handle!=0L){"BEKKO_OPEN_RETURNED_NULL"}
            connection=remote;service=bound;nativeHandle=handle;bound
        }catch(failure:Throwable){remote.close();throw failure}
    }

    @Synchronized override fun embedBatch(request:EmbeddingRequest):EmbeddingBatchResult{
        if(request.texts.size>capabilities.maximumBatchSize)return EmbeddingBatchResult.Failure("BEKKO_BATCH_LIMIT",false)
        if(!active.add(request.requestUid))return EmbeddingBatchResult.Failure("BEKKO_DUPLICATE_REQUEST",false)
        return try{
            val result=ensureOpen().embed(nativeHandle,request.requestUid,request.texts,request.maximumInputUnits)
            if(!result.getBoolean(LlamaCppInferenceService.KEY_SUCCESS))return EmbeddingBatchResult.Failure(
                result.getString(LlamaCppInferenceService.KEY_REASON)?:"BEKKO_EMBEDDING_FAILED",true
            )
            val flat=result.getFloatArray(LlamaCppInferenceService.KEY_EMBEDDINGS)
                ?:return EmbeddingBatchResult.Failure("BEKKO_EMBEDDING_OUTPUT_MISSING",true)
            val count=result.getInt(LlamaCppInferenceService.KEY_COUNT)
            val dimensions=result.getInt(LlamaCppInferenceService.KEY_DIMENSIONS)
            if(count!=request.texts.size||dimensions!=capabilities.sourceDimensions||flat.size!=count*dimensions)
                return EmbeddingBatchResult.Failure("BEKKO_EMBEDDING_SHAPE_INVALID",false)
            EmbeddingBatchResult.Success(
                List(count){index->flat.copyOfRange(index*dimensions,(index+1)*dimensions)},
                result.getString(LlamaCppInferenceService.KEY_TRACE)?:"BEKKO:${request.requestUid}"
            )
        }catch(failure:Throwable){
            val reason=when(failure){
                is DeadObjectException->"BEKKO_SERVICE_DIED"
                is RemoteException->"BEKKO_SERVICE_IPC_FAILED"
                else->failure.message?.takeIf{it.startsWith("LLAMA_")||it.startsWith("BEKKO_")}
                    ?:"BEKKO_EMBEDDING_FAILURE:${failure::class.java.simpleName}"
            }
            close();EmbeddingBatchResult.Failure(reason,true)
        }finally{active.remove(request.requestUid)}
    }

    override fun cancel(requestUid:String){runCatching{service?.cancel(requestUid)}}

    @Synchronized override fun close(){
        val handle=nativeHandle;nativeHandle=0
        if(handle!=0L)runCatching{service?.close(handle)}
        service=null;connection?.close();connection=null;active.clear()
    }

    private class RemoteConnection(private val context:Context):ServiceConnection{
        private val latch=CountDownLatch(1);@Volatile private var service:ILlamaCppInferenceService?=null;@Volatile private var bound=false
        fun connect():ILlamaCppInferenceService{
            bound=context.bindService(Intent(context,LlamaCppInferenceService::class.java),this,Context.BIND_AUTO_CREATE)
            if(!bound)error("BEKKO_SERVICE_BIND_FAILED")
            if(!latch.await(15,TimeUnit.SECONDS))error("BEKKO_SERVICE_BIND_TIMEOUT")
            return service?:error("BEKKO_SERVICE_DIED")
        }
        override fun onServiceConnected(name:ComponentName?,binder:IBinder?){service=ILlamaCppInferenceService.Stub.asInterface(binder);latch.countDown()}
        override fun onServiceDisconnected(name:ComponentName?){service=null;latch.countDown()}
        override fun onBindingDied(name:ComponentName?){service=null;latch.countDown()}
        fun close(){if(bound){runCatching{context.unbindService(this)};bound=false}}
    }
}
