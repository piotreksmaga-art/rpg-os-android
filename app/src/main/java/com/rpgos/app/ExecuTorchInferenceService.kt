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
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Native ExecuTorch runs outside the UI process. A native abort or device-specific runtime failure
 * can therefore fail one request without terminating the campaign UI or corrupting canonical state.
 */
class ExecuTorchInferenceService:Service(){
    @Volatile private var activeModule:org.pytorch.executorch.extension.llm.LlmModule?=null
    private val binder=object:IExecuTorchInferenceService.Stub(){
        override fun generate(modelPath:String,tokenizerPath:String,contextUnits:Int,prompt:String,maximumOutputUnits:Int):Bundle{
            var module:org.pytorch.executorch.extension.llm.LlmModule?=null
            return try{
                val config=org.pytorch.executorch.extension.llm.LlmModuleConfig.create()
                    .modulePath(modelPath).tokenizerPath(tokenizerPath).temperature(0.1f)
                    // ExecuTorch Android 1.3.0 initializes dataPath to an empty string. LlmModule
                    // treats every non-null value as an external metadata shard and attempts to
                    // open it; the empty path then aborts inside fbjni before Kotlin can recover.
                    // This text-only PTE embeds its metadata, so the optional path must be null.
                    .dataPath(null)
                    .modelType(org.pytorch.executorch.extension.llm.LlmModuleConfig.MODEL_TYPE_TEXT)
                    .loadMode(org.pytorch.executorch.extension.llm.LlmModuleConfig.LOAD_MODE_MMAP).build()
                module=org.pytorch.executorch.extension.llm.LlmModule(config).also{activeModule=it;it.load()}
                val output=StringBuilder();var tokens=0;var failure:String?=null;var stats=""
                val callback=object:org.pytorch.executorch.extension.llm.LlmCallback{
                    override fun onResult(token:String){
                        output.append(token);tokens++
                        if(tokens>=maximumOutputUnits||completeJsonObjectOrNull(output.toString())!=null)activeModule?.stop()
                    }
                    override fun onStats(value:String){stats=value}
                    override fun onError(code:Int,message:String){failure="EXECUTORCH_$code:$message"}
                }
                val generation=org.pytorch.executorch.extension.llm.LlmGenerationConfig.create().echo(false)
                    .maxNewTokens(maximumOutputUnits).seqLen(contextUnits).temperature(0.1f).build()
                module.generate(bielikChatPrompt(prompt),generation,callback)
                failure?.let{error(it)}
                val structured=bielikStructuredOutput(output.toString())
                Bundle().apply{
                    putBoolean(KEY_SUCCESS,true);putString(KEY_OUTPUT,structured);putInt(KEY_TOKENS,tokens)
                    putString(KEY_TRACE,digest("$stats|$tokens"))
                }
            }catch(failure:Throwable){
                Bundle().apply{putBoolean(KEY_SUCCESS,false);putString(KEY_REASON,"EXECUTORCH_SERVICE_FAILURE:${failure::class.java.simpleName}:${failure.message.orEmpty().take(160)}")}
            }finally{
                activeModule=null
                module?.let{runCatching{it.stop()};runCatching{it.resetContext()};runCatching{it.close()}}
                stopSelf()
            }
        }
        override fun cancelGeneration(){activeModule?.stop()}
    }
    override fun onBind(intent:Intent?):IBinder=binder
    companion object{
        const val KEY_SUCCESS="success";const val KEY_OUTPUT="output";const val KEY_TOKENS="tokens";const val KEY_TRACE="trace";const val KEY_REASON="reason"
        private fun digest(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
        internal fun bielikChatPrompt(payload:String)="""<|im_start|>system
Jesteś lokalnym adapterem RPG OS. Nigdy nie przepisuj danych wejściowych. Wykonaj instrukcję z pola reply i zwróć wyłącznie wynikowy obiekt JSON, bez Markdownu i komentarza.<|im_end|>
<|im_start|>user
Nie powtarzaj poniższego obiektu. Odpowiedz teraz tylko krótkim JSON-em statusu Q albo R.
DANE:
$payload<|im_end|>
<|im_start|>assistant
""".trimIndent()
        internal fun bielikStructuredOutput(value:String):String{
            val cleaned=value.trim().removePrefix("<|im_start|>assistant")
                .substringBefore("<|im_end|>").substringBefore("</s>").trim()
            return completeJsonObjectOrNull(cleaned)?:cleaned.substring(cleaned.indexOf('{').coerceAtLeast(0))
        }
        internal fun completeJsonObjectOrNull(value:String):String?{
            val cleaned=value.trim()
            val start=cleaned.indexOf('{')
            if(start<0)return null
            var depth=0;var quoted=false;var escaped=false
            for(index in start until cleaned.length){
                val character=cleaned[index]
                if(quoted){
                    when{
                        escaped->escaped=false
                        character=='\\'->escaped=true
                        character=='\"'->quoted=false
                    }
                }else when(character){
                    '\"'->quoted=true
                    '{'->depth++
                    '}'->{depth--;if(depth==0)return cleaned.substring(start,index+1)}
                }
            }
            return null
        }
    }
}

class IsolatedExecuTorchLocalInferenceDriver(private val context:Context):LocalInferenceDriver{
    private data class Handle(val settings:LocalModelSettings,val artifact:LocalModelArtifact)
    private val active=ConcurrentHashMap<String,IExecuTorchInferenceService>()
    override fun open(profile:LocalModelProfile,settings:LocalModelSettings,artifact:LocalModelArtifact,backend:LocalRuntimeBackend):Any{
        require(profile.variants.single{it.variantUid==settings.variantUid}.format==LocalArtifactFormat.EXECUTORCH)
        require(backend in setOf(LocalRuntimeBackend.AUTO,LocalRuntimeBackend.CPU)){"RPGOS-P48:EXECUTORCH_BACKEND_UNPACKAGED"}
        requireNotNull(artifact.tokenizerAbsolutePath){"RPGOS-P48:EXECUTORCH_TOKENIZER_REQUIRED"}
        return Handle(settings,artifact)
    }
    override fun infer(handle:Any,requestUid:String,prompt:String,maximumOutputUnits:Int,cancellation:AiCancellationSignal,onChunk:(LocalGenerationChunk)->Unit):LocalGenerationOutput{
        val typed=handle as? Handle?:throw AiTransportException("EXECUTORCH_INVALID_HANDLE")
        if(cancellation.isCancelled())throw AiTransportException("LOCAL_CANCELLED")
        val connection=RemoteConnection(context)
        val service=try{connection.connect()}catch(failure:Throwable){connection.close();throw failure}
        if(active.putIfAbsent(requestUid,service)!=null){connection.close();throw AiTransportException("LOCAL_DUPLICATE_REQUEST")}
        return try{
            val outputLimit=if(prompt.contains("\"v\":\"RPGOS_CC_LOCAL_1\""))minOf(maximumOutputUnits,320) else maximumOutputUnits
            val result=service.generate(
                typed.artifact.absolutePath,requireNotNull(typed.artifact.tokenizerAbsolutePath),typed.settings.contextUnits,
                prompt,outputLimit
            )
            if(cancellation.isCancelled())throw AiTransportException("LOCAL_CANCELLED")
            if(!result.getBoolean(ExecuTorchInferenceService.KEY_SUCCESS))throw AiTransportException(
                result.getString(ExecuTorchInferenceService.KEY_REASON)?:"EXECUTORCH_SERVICE_FAILURE",true
            )
            val output=result.getString(ExecuTorchInferenceService.KEY_OUTPUT).orEmpty()
            onChunk(LocalGenerationChunk(output,true))
            LocalGenerationOutput(output,"EXECUTORCH-SERVICE:${result.getString(ExecuTorchInferenceService.KEY_TRACE).orEmpty()}",0,result.getInt(ExecuTorchInferenceService.KEY_TOKENS))
        }catch(failure:DeadObjectException){
            throw AiTransportException("EXECUTORCH_SERVICE_DIED",true,failure)
        }catch(failure:RemoteException){
            throw AiTransportException("EXECUTORCH_SERVICE_IPC_FAILED",true,failure)
        }finally{active.remove(requestUid);connection.close()}
    }
    override fun cancel(requestUid:String){runCatching{active[requestUid]?.cancelGeneration()}}
    override fun close(handle:Any)=Unit

    private class RemoteConnection(private val context:Context):ServiceConnection{
        private val latch=CountDownLatch(1)
        @Volatile private var service:IExecuTorchInferenceService?=null
        @Volatile private var bound=false
        fun connect():IExecuTorchInferenceService{
            bound=context.bindService(Intent(context,ExecuTorchInferenceService::class.java),this,Context.BIND_AUTO_CREATE)
            if(!bound)throw AiTransportException("EXECUTORCH_SERVICE_BIND_FAILED",true)
            if(!latch.await(15,TimeUnit.SECONDS))throw AiTransportException("EXECUTORCH_SERVICE_BIND_TIMEOUT",true)
            return service?:throw AiTransportException("EXECUTORCH_SERVICE_DIED",true)
        }
        override fun onServiceConnected(name:ComponentName?,binder:IBinder?){service=IExecuTorchInferenceService.Stub.asInterface(binder);latch.countDown()}
        override fun onServiceDisconnected(name:ComponentName?){service=null;latch.countDown()}
        override fun onBindingDied(name:ComponentName?){service=null;latch.countDown()}
        fun close(){if(bound){runCatching{context.unbindService(this)};bound=false}}
    }
}
