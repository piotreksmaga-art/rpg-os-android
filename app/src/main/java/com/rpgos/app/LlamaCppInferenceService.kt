package com.rpgos.app

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Full llama.cpp provider host. GGUF and Vulkan live outside the UI process. */
class LlamaCppInferenceService:Service(){
    private val handles=ConcurrentHashMap.newKeySet<Long>()
    private val binder=object:ILlamaCppInferenceService.Stub(){
        override fun open(
            modelPath:String,contextUnits:Int,backend:String,threads:Int,prefillBatch:Int,microBatch:Int,gpuLayers:Int,
            kvKeyType:String,kvValueType:String,temperature:Float,topK:Int,topP:Float,repeatPenalty:Float,
            flashAttention:Boolean,memoryMap:Boolean
        ):Long{
            val handle=NativeLocalInferenceBridge.open(
                modelPath,"GGUF_MODEL_METADATA",contextUnits,0L,backend,threads,prefillBatch,microBatch,gpuLayers,
                kvKeyType,kvValueType,temperature,topK,topP,repeatPenalty,flashAttention,memoryMap
            )
            require(handle!=0L){"LLAMA_OPEN_RETURNED_NULL"}
            handles+=handle
            return handle
        }

        override fun generate(handle:Long,requestUid:String,prompt:String,maximumOutputUnits:Int):Bundle=try{
            require(handle in handles){"LLAMA_HANDLE_NOT_OWNED"}
            val output=NativeLocalInferenceBridge.generate(handle,requestUid,prompt,maximumOutputUnits)
            Bundle().apply{
                putBoolean(KEY_SUCCESS,true);putString(KEY_OUTPUT,output)
                putInt(KEY_TOKENS,output.length/4);putString(KEY_TRACE,"LLAMA_CPP_NATIVE:$requestUid")
            }
        }catch(failure:Throwable){
            Bundle().apply{
                putBoolean(KEY_SUCCESS,false)
                putString(KEY_REASON,failure.message?.takeIf{it.startsWith("LLAMA_")||it=="LOCAL_CANCELLED"}
                    ?:"LLAMA_SERVICE_FAILURE:${failure::class.java.simpleName}:${failure.message.orEmpty().take(160)}")
            }
        }

        override fun openEmbedding(
            modelPath:String,contextUnits:Int,backend:String,threads:Int,batch:Int,gpuLayers:Int,memoryMap:Boolean
        ):Long{
            val handle=NativeLocalInferenceBridge.openEmbedding(
                modelPath,contextUnits,backend,threads,batch,gpuLayers,memoryMap
            )
            require(handle!=0L){"LLAMA_EMBEDDING_OPEN_RETURNED_NULL"}
            handles+=handle
            return handle
        }

        override fun embed(handle:Long,requestUid:String,texts:MutableList<String>,maximumInputUnits:Int):Bundle=try{
            require(handle in handles){"LLAMA_HANDLE_NOT_OWNED"}
            require(texts.isNotEmpty()&&texts.size<=64){"LLAMA_EMBEDDING_BATCH_INVALID"}
            require(maximumInputUnits in 1..8192){"LLAMA_EMBEDDING_INPUT_LIMIT_INVALID"}
            val vectors=NativeLocalInferenceBridge.embed(handle,requestUid,texts.toTypedArray(),maximumInputUnits)
            require(vectors.isNotEmpty()&&vectors.size%texts.size==0){"LLAMA_EMBEDDING_OUTPUT_INVALID"}
            Bundle().apply{
                putBoolean(KEY_SUCCESS,true);putFloatArray(KEY_EMBEDDINGS,vectors)
                putInt(KEY_COUNT,texts.size);putInt(KEY_DIMENSIONS,vectors.size/texts.size)
                putString(KEY_TRACE,"LLAMA_CPP_EMBEDDING:$requestUid")
            }
        }catch(failure:Throwable){
            Bundle().apply{
                putBoolean(KEY_SUCCESS,false)
                putString(KEY_REASON,failure.message?.takeIf{it.startsWith("LLAMA_")||it=="LOCAL_CANCELLED"}
                    ?:"LLAMA_EMBEDDING_SERVICE_FAILURE:${failure::class.java.simpleName}:${failure.message.orEmpty().take(160)}")
            }
        }

        override fun cancel(requestUid:String){NativeLocalInferenceBridge.cancel(requestUid)}
        override fun close(handle:Long){if(handles.remove(handle))NativeLocalInferenceBridge.close(handle)}
    }

    override fun onBind(intent:Intent?):IBinder=binder
    override fun onDestroy(){handles.toList().forEach{runCatching{NativeLocalInferenceBridge.close(it)}};handles.clear();super.onDestroy()}

    companion object{
        const val KEY_SUCCESS="success";const val KEY_OUTPUT="output";const val KEY_TOKENS="tokens"
        const val KEY_TRACE="trace";const val KEY_REASON="reason"
        const val KEY_EMBEDDINGS="embeddings";const val KEY_COUNT="count";const val KEY_DIMENSIONS="dimensions"
    }
}

class IsolatedLlamaCppLocalInferenceDriver(private val context:Context):LocalInferenceDriver{
    private data class Handle(val connection:RemoteConnection,val service:ILlamaCppInferenceService,val nativeHandle:Long)
    private val active=ConcurrentHashMap<String,ILlamaCppInferenceService>()

    override fun open(profile:LocalModelProfile,settings:LocalModelSettings,artifact:LocalModelArtifact,backend:LocalRuntimeBackend):Any{
        require(settings.runtimeEngine==LocalRuntimeEngine.LLAMA_CPP)
        require(profile.variants.single{it.variantUid==settings.variantUid}.format==LocalArtifactFormat.GGUF)
        val connection=RemoteConnection(context)
        return try{
            val service=connection.connect()
            val handle=service.open(
                artifact.absolutePath,settings.contextUnits,backend.name,settings.threads?:4,settings.prefillBatchUnits?:64,
                settings.microBatchUnits?:settings.prefillBatchUnits?:64,settings.gpuLayers?:0,
                settings.kvKeyType.name,settings.kvValueType.name,settings.temperature,settings.topK,settings.topP,
                settings.repeatPenalty,settings.flashAttention,settings.memoryMap
            )
            Handle(connection,service,handle)
        }catch(failure:Throwable){connection.close();throw mapFailure(failure)}
    }

    override fun infer(handle:Any,requestUid:String,prompt:String,maximumOutputUnits:Int,cancellation:AiCancellationSignal,onChunk:(LocalGenerationChunk)->Unit):LocalGenerationOutput{
        val typed=handle as? Handle?:throw AiTransportException("LLAMA_INVALID_HANDLE")
        if(cancellation.isCancelled())throw AiTransportException("LOCAL_CANCELLED")
        if(active.putIfAbsent(requestUid,typed.service)!=null)throw AiTransportException("LOCAL_DUPLICATE_REQUEST")
        return try{
            val result=typed.service.generate(typed.nativeHandle,requestUid,prompt,maximumOutputUnits)
            if(cancellation.isCancelled())throw AiTransportException("LOCAL_CANCELLED")
            if(!result.getBoolean(LlamaCppInferenceService.KEY_SUCCESS))throw AiTransportException(
                result.getString(LlamaCppInferenceService.KEY_REASON)?:"LLAMA_SERVICE_FAILURE",true
            )
            val output=result.getString(LlamaCppInferenceService.KEY_OUTPUT).orEmpty()
            onChunk(LocalGenerationChunk(output,true))
            LocalGenerationOutput(output,result.getString(LlamaCppInferenceService.KEY_TRACE)?:"LLAMA_CPP_NATIVE:$requestUid",0,result.getInt(LlamaCppInferenceService.KEY_TOKENS))
        }catch(failure:Throwable){throw mapFailure(failure)}finally{active.remove(requestUid)}
    }

    override fun cancel(requestUid:String){runCatching{active[requestUid]?.cancel(requestUid)}}
    override fun close(handle:Any){
        val typed=handle as? Handle?:return
        runCatching{typed.service.close(typed.nativeHandle)}
        typed.connection.close()
    }

    private fun mapFailure(failure:Throwable):AiTransportException=when(failure){
        is AiTransportException->failure
        is DeadObjectException->AiTransportException("LLAMA_SERVICE_DIED",true,failure)
        is RemoteException->AiTransportException("LLAMA_SERVICE_IPC_FAILED",true,failure)
        else->AiTransportException(failure.message?.takeIf{it.startsWith("LLAMA_")}
            ?:"LLAMA_SERVICE_FAILURE:${failure::class.java.simpleName}",true,failure)
    }

    private class RemoteConnection(private val context:Context):ServiceConnection{
        private val latch=CountDownLatch(1)
        @Volatile private var service:ILlamaCppInferenceService?=null
        @Volatile private var bound=false
        fun connect():ILlamaCppInferenceService{
            bound=context.bindService(Intent(context,LlamaCppInferenceService::class.java),this,Context.BIND_AUTO_CREATE)
            if(!bound)throw AiTransportException("LLAMA_SERVICE_BIND_FAILED",true)
            if(!latch.await(15,TimeUnit.SECONDS))throw AiTransportException("LLAMA_SERVICE_BIND_TIMEOUT",true)
            return service?:throw AiTransportException("LLAMA_SERVICE_DIED",true)
        }
        override fun onServiceConnected(name:ComponentName?,binder:IBinder?){service=ILlamaCppInferenceService.Stub.asInterface(binder);latch.countDown()}
        override fun onServiceDisconnected(name:ComponentName?){service=null;latch.countDown()}
        override fun onBindingDied(name:ComponentName?){service=null;latch.countDown()}
        fun close(){if(bound){runCatching{context.unbindService(this)};bound=false}}
    }
}
