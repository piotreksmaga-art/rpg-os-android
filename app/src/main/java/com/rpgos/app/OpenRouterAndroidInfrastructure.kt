package com.rpgos.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** API secrets are encrypted with an Android Keystore key and never share campaign/save storage. */
class AndroidKeystoreSecretStore(context:Context):SecretStore{
    private val prefs=context.getSharedPreferences("rpgos_ai_secrets",Context.MODE_PRIVATE)
    override fun put(secretUid:String,value:CharArray){
        val cipher=Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE,key())
        val plain=value.concatToString().toByteArray(Charsets.UTF_8)
        try{
            val encrypted=cipher.doFinal(plain)
            prefs.edit().putString("$secretUid.iv",b64(cipher.iv)).putString("$secretUid.data",b64(encrypted)).apply()
        }finally{plain.fill(0)}
    }
    override fun get(secretUid:String):CharArray?{
        val iv=prefs.getString("$secretUid.iv",null)?.let(::decode)?:return null
        val data=prefs.getString("$secretUid.data",null)?.let(::decode)?:return null
        val cipher=Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,iv))
        val plain=cipher.doFinal(data)
        return try{plain.toString(Charsets.UTF_8).toCharArray()}finally{plain.fill(0)}
    }
    override fun remove(secretUid:String){prefs.edit().remove("$secretUid.iv").remove("$secretUid.data").apply()}
    private fun key():SecretKey{
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (store.getKey(KEY_ALIAS,null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").run{
            init(KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    private fun b64(bytes:ByteArray)=Base64.getEncoder().encodeToString(bytes)
    private fun decode(value:String)=Base64.getDecoder().decode(value)
    companion object{private const val KEY_ALIAS="rpgos.openrouter.credentials.v1";private const val TRANSFORMATION="AES/GCM/NoPadding"}
}

/**
 * Official OpenRouter PKCE supports localhost callbacks for local-first apps. This bounded loopback
 * receiver avoids assuming undocumented custom-scheme OAuth behaviour.
 */
class OpenRouterLoopbackCallbackServer:OpenRouterCallbackEndpointFactory{
    @Volatile private var consumer:((CloudAuthCallback)->CloudConnectionStatus)?=null
    fun onCallback(consumer:(CloudAuthCallback)->CloudConnectionStatus){this.consumer=consumer}
    override fun create(nonce:String):OpenRouterCallbackEndpoint{
        require(nonce.matches(Regex("[A-Za-z0-9_-]{16,}")))
        val server=ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))
        val path="/openrouter/callback/$nonce"
        val callback="http://127.0.0.1:${server.localPort}$path"
        Thread({serveOnce(server,path,callback)},"rpgos-openrouter-pkce").apply{isDaemon=true;start()}
        return OpenRouterCallbackEndpoint(callback,"LOOPBACK:${server.localPort}:$nonce")
    }
    private fun serveOnce(server:ServerSocket,path:String,callback:String){
        server.soTimeout=180_000
        try{server.use{s->s.accept().use{socket->
            socket.soTimeout=10_000
            val reader=socket.getInputStream().bufferedReader()
            val requestLine=reader.readLine().orEmpty()
            val target=requestLine.split(' ').getOrNull(1).orEmpty()
            val uri=runCatching{URI(target)}.getOrNull()
            val code=uri?.rawQuery?.split('&')?.mapNotNull{part->
                val split=part.split('=',limit=2);if(split.firstOrNull()=="code")java.net.URLDecoder.decode(split.getOrElse(1){""},"UTF-8") else null
            }?.firstOrNull()
            val accepted=uri?.path==path&&!code.isNullOrBlank()
            val result=if(accepted)runCatching{consumer?.invoke(CloudAuthCallback(callback,code!!))}.getOrNull() else null
            val body=when{
                result?.state==CloudAuthState.CONNECTED->"RPG OS połączono z OpenRouter. Możesz wrócić do aplikacji."
                accepted->"Autoryzacja dotarła, ale OpenRouter nie zakończył połączenia. Wróć do RPG OS, aby zobaczyć dokładny powód i spróbować ponownie."
                else->"Nie udało się odebrać autoryzacji. Wróć do RPG OS i spróbuj ponownie."
            }
            val payload="<html><head><meta charset=\"utf-8\"></head><body><h2>$body</h2></body></html>".toByteArray(Charsets.UTF_8)
            socket.getOutputStream().apply{
                write("HTTP/1.1 ${if(accepted)"200 OK" else "400 Bad Request"}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                write(payload);flush()
            }
        }}}catch(_:Throwable){/* typed timeout remains visible through CloudAuthPort status */}
    }
}

class OpenRouterHttpClient(
    private val client:OkHttpClient=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(180,TimeUnit.SECONDS).build()
):CloudInferenceClient,OpenRouterCodeExchange{
    private val active=ConcurrentHashMap<String,Call>()
    override fun exchange(code:String,verifier:String):Pair<CharArray,String?>{
        val body=JSONObject().put("code",code).put("code_verifier",verifier).put("code_challenge_method","S256")
        val request=Request.Builder().url("https://openrouter.ai/api/v1/auth/keys")
            .post(body.toString().toRequestBody(JSON)).build()
        client.newCall(request).execute().use{response->
            if(!response.isSuccessful)throw AiTransportException("OPENROUTER_AUTH_HTTP_${response.code}",response.code>=500)
            val json=JSONObject(response.body.string())
            return json.getString("key").toCharArray() to json.optString("user_id").takeIf{it.isNotBlank()}
        }
    }
    override fun discoverModels(credential:CharArray):List<CloudModelProfile>{
        val request=Request.Builder().url("https://openrouter.ai/api/v1/models?output_modalities=text")
            .header("Authorization","Bearer ${credential.concatToString()}").build()
        client.newCall(request).execute().use{response->
            if(!response.isSuccessful)throw AiTransportException("OPENROUTER_MODELS_HTTP_${response.code}",response.code>=500||response.code==429)
            val data=JSONObject(response.body.string()).getJSONArray("data")
            return buildList{for(index in 0 until data.length()){
                val model=data.getJSONObject(index)
                val supported=model.optJSONArray("supported_parameters").strings()
                add(CloudModelProfile(
                    "OPENROUTER",model.getString("id"),model.optString("name",model.getString("id")),
                    model.optInt("context_length",8_192).coerceAtLeast(1),AiWorkload.entries.toSet(),
                    supportsStreaming=true,supportsStructuredOutput="response_format" in supported||"structured_outputs" in supported
                ))
            }}
        }
    }
    override fun execute(model:CloudModelProfile,credential:CharArray,request:AiTransportRequest,cancellation:AiCancellationSignal):CloudInferenceResponse{
        if(cancellation.isCancelled())throw AiTransportException("CANCELLED_BEFORE_CLOUD")
        val payload=JSONObject().put("model",model.modelUid).put("stream",false).put("temperature",temperature(request.workload))
            .put("messages",JSONArray().put(JSONObject().put("role","system").put("content",systemInstruction(request.workload)))
                .put(JSONObject().put("role","user").put("content",request.payload)))
            .put("max_tokens",request.maximumOutputUnits)
        if(model.supportsStructuredOutput){
            payload.put("response_format",OpenRouterStructuredOutputSchema.responseFormat(request.workload))
            // OpenRouter must route only to providers which honour the requested schema parameters.
            payload.put("provider",JSONObject().put("require_parameters",true))
        }
        val http=Request.Builder().url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization","Bearer ${credential.concatToString()}")
            .header("HTTP-Referer","https://github.com/piotreksmaga-art/rpg-os-android")
            .header("X-OpenRouter-Title","RPG OS")
            .post(payload.toString().toRequestBody(JSON)).build()
        val call=client.newCall(http)
        if(active.putIfAbsent(request.requestUid,call)!=null)throw AiTransportException("DUPLICATE_CLOUD_REQUEST")
        try{call.execute().use{response->
            if(response.code==429)throw CloudRateLimitException()
            if(!response.isSuccessful)throw AiTransportException("OPENROUTER_HTTP_${response.code}",response.code>=500||response.code==408)
            val json=JSONObject(response.body.string())
            val content=json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").stripJsonFence()
            val usage=json.optJSONObject("usage")
            return CloudInferenceResponse(content,json.optString("id","OPENROUTER:${request.requestUid}"),CloudUsage(
                usage?.optInt("prompt_tokens")?:0,usage?.optInt("completion_tokens")?:0
            ),response.header("X-RateLimit-Remaining")?.toIntOrNull())
        }}finally{active.remove(request.requestUid)}
    }
    override fun cancel(requestUid:String){active.remove(requestUid)?.cancel()}
    private fun temperature(workload:AiWorkload)=when(workload){
        AiWorkload.INTENT_INTERPRETATION,AiWorkload.GM_PROPOSAL,AiWorkload.PROPOSAL_REPAIR,AiWorkload.CHARACTER_CREATION->0.1
        AiWorkload.NARRATIVE_RENDER,AiWorkload.NARRATIVE_REPAIR->0.7
        AiWorkload.DIRECTOR_STRATEGY->0.6
    }
    private fun systemInstruction(workload:AiWorkload)="""
        You are a bounded RPG OS ${workload.name} adapter. Return exactly one JSON object matching the schema in the request.
        Never invent canonical IDs, hidden facts, mechanics results, player speech, player choices, or mutations.
        Preserve campaign/actor/action/target/modality and stop at the requested player decision point.
    """.trimIndent()
    companion object{private val JSON="application/json; charset=utf-8".toMediaType()}
}

class AndroidLocalModelArtifactStore(private val context:Context):LocalModelArtifactStore{
    private val root=File(context.filesDir,"ai-models")
    override fun find(modelUid:String,variantUid:String):LocalModelArtifact?{
        val safeModel=stableSegment(modelUid);val safeVariant=stableSegment(variantUid)
        val packageDir=File(File(root,safeModel),safeVariant)
        val pte=packageDir.listFiles()?.singleOrNull{it.isFile&&it.extension.equals("pte",true)}
        val tokenizer=packageDir.listFiles()?.singleOrNull{it.isFile&&it.name.startsWith("tokenizer",true)}
        if(pte!=null&&tokenizer!=null&&pte.length()>0&&tokenizer.length()>0){
            return LocalModelArtifact(modelUid,variantUid,pte.absolutePath,pte.length(),sha256(pte),tokenizer.absolutePath)
        }
        val file=File(File(root,safeModel),"$safeVariant.model")
        if(!file.isFile||file.length()<=0)return null
        return LocalModelArtifact(modelUid,variantUid,file.absolutePath,file.length(),sha256(file))
    }
    fun import(modelUid:String,variantUid:String,input:java.io.InputStream):LocalModelArtifact{
        if(variantUid.startsWith("EXECUTORCH"))return importExecuTorchPackage(modelUid,variantUid,input)
        val dir=File(root,stableSegment(modelUid)).apply{mkdirs()}
        val target=File(dir,"${stableSegment(variantUid)}.model")
        val staging=File(dir,".${target.name}.${System.nanoTime()}.partial")
        try{input.use{source->staging.outputStream().use(source::copyTo)};require(staging.length()>0);if(!staging.renameTo(target)){staging.copyTo(target,true);staging.delete()}}
        finally{if(staging.exists())staging.delete()}
        return LocalModelArtifact(modelUid,variantUid,target.absolutePath,target.length(),sha256(target))
    }
    private fun importExecuTorchPackage(modelUid:String,variantUid:String,input:java.io.InputStream):LocalModelArtifact{
        val parent=File(root,stableSegment(modelUid)).apply{mkdirs()}
        val target=File(parent,stableSegment(variantUid))
        val staging=File(parent,".${target.name}.${System.nanoTime()}.partial").apply{mkdirs()}
        var model:File?=null;var tokenizer:File?=null
        try{
            ZipInputStream(input.buffered()).use{zip->
                while(true){
                    val entry=zip.nextEntry?:break
                    if(entry.isDirectory)continue
                    val base=File(entry.name).name
                    val destination=when{
                        base.endsWith(".pte",true)&&model==null->File(staging,"model.pte").also{model=it}
                        base.startsWith("tokenizer",true)&&tokenizer==null->File(staging,"tokenizer${base.substringAfterLast('.',"").let{if(it.isBlank())"" else ".$it"}}").also{tokenizer=it}
                        else->null
                    }
                    destination?.outputStream()?.use{output->copyBounded(zip,output,MAX_EXECUTORCH_ENTRY_BYTES)}
                    zip.closeEntry()
                }
            }
            require(model?.length()?.let{it>0}==true){"RPGOS-P48:EXECUTORCH_MODEL_PTE_REQUIRED"}
            require(tokenizer?.length()?.let{it>0}==true){"RPGOS-P48:EXECUTORCH_TOKENIZER_REQUIRED"}
            if(target.exists())target.deleteRecursively()
            require(staging.renameTo(target)){"RPGOS-P48:EXECUTORCH_PACKAGE_INSTALL_FAILED"}
            return requireNotNull(find(modelUid,variantUid))
        }finally{if(staging.exists())staging.deleteRecursively()}
    }
    fun remove(modelUid:String,variantUid:String):Boolean{
        val parent=File(root,stableSegment(modelUid));val segment=stableSegment(variantUid)
        val packageDir=File(parent,segment)
        if(packageDir.isDirectory)return packageDir.deleteRecursively()
        val file=File(parent,"$segment.model")
        return !file.exists()||file.delete()
    }
    private fun copyBounded(input:java.io.InputStream,output:java.io.OutputStream,maximumBytes:Long){
        val buffer=ByteArray(1024*1024);var total=0L
        while(true){val count=input.read(buffer);if(count<0)break;total+=count;require(total<=maximumBytes){"RPGOS-P48:EXECUTORCH_PACKAGE_ENTRY_TOO_LARGE"};output.write(buffer,0,count)}
    }
    private fun stableSegment(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(12).joinToString(""){"%02x".format(it)}
    private fun sha256(file:File):String{
        val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use{input->val buffer=ByteArray(1024*1024);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
        return digest.digest().joinToString(""){"%02x".format(it)}
    }
    companion object{private const val MAX_EXECUTORCH_ENTRY_BYTES=8_000_000_000L}
}

object AndroidLocalDeviceProbe{
    fun snapshot(context:Context):LocalDeviceCapabilities{
        val memory=ActivityManager.MemoryInfo().also{(context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)}
        val power=context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermal=if(Build.VERSION.SDK_INT>=29)when(power.currentThermalStatus){
            PowerManager.THERMAL_STATUS_NONE,PowerManager.THERMAL_STATUS_LIGHT->LocalThermalState.NOMINAL
            PowerManager.THERMAL_STATUS_MODERATE->LocalThermalState.WARM
            PowerManager.THERMAL_STATUS_SEVERE->LocalThermalState.HOT
            PowerManager.THERMAL_STATUS_CRITICAL,PowerManager.THERMAL_STATUS_EMERGENCY,PowerManager.THERMAL_STATUS_SHUTDOWN->LocalThermalState.CRITICAL
            else->LocalThermalState.UNKNOWN
        }else LocalThermalState.UNKNOWN
        // Some vendor and test ActivityManager implementations temporarily report total=0 or
        // available>total. Capability probing must degrade safely instead of crashing chat routing.
        val total=memory.totalMem.coerceAtLeast(memory.availMem).coerceAtLeast(1)
        val available=memory.availMem.coerceIn(0,total)
        return LocalDeviceCapabilities(available,total,thermal,setOf(LocalRuntimeBackend.CPU),maxOf(512L*1024*1024,total/8))
    }
}

/** Android JNI adapter boundary. It is unavailable until a compatible native implementation is packaged. */
class JniLocalInferenceDriver:LocalInferenceDriver{
    init{require(NativeLocalInferenceBridge.available){"RPGOS-P48:NATIVE_LOCAL_RUNTIME_NOT_PACKAGED"}}
    override fun open(profile:LocalModelProfile,settings:LocalModelSettings,artifact:LocalModelArtifact,backend:LocalRuntimeBackend):Any =
        NativeLocalInferenceBridge.open(artifact.absolutePath,profile.chatTemplateUid,settings.contextUnits,settings.kvBytesPerContextUnit,backend.name,settings.threads?:0,settings.prefillBatchUnits?:0)
    override fun infer(handle:Any,requestUid:String,prompt:String,maximumOutputUnits:Int,cancellation:AiCancellationSignal,onChunk:(LocalGenerationChunk)->Unit):LocalGenerationOutput{
        require(handle is Long)
        val started=System.nanoTime();val output=NativeLocalInferenceBridge.generate(handle,requestUid,prompt,maximumOutputUnits)
        if(cancellation.isCancelled())throw AiTransportException("LOCAL_CANCELLED")
        onChunk(LocalGenerationChunk(output,true))
        return LocalGenerationOutput(output,"JNI:$requestUid:${System.nanoTime()-started}",0,0)
    }
    override fun cancel(requestUid:String){NativeLocalInferenceBridge.cancel(requestUid)}
    override fun close(handle:Any){require(handle is Long);NativeLocalInferenceBridge.close(handle)}
}

object NativeLocalInferenceBridge{
    val available:Boolean=runCatching{System.loadLibrary("rpgos_ai_runtime");true}.getOrDefault(false)
    external fun open(artifactPath:String,chatTemplateUid:String,contextUnits:Int,kvBytesPerUnit:Long,backend:String,threads:Int,prefillBatch:Int):Long
    external fun generate(handle:Long,requestUid:String,prompt:String,maximumOutputUnits:Int):String
    external fun cancel(requestUid:String)
    external fun close(handle:Long)
}

/** Official ExecuTorch LLM Java API backed by the native libraries packaged in its Android AAR. */
class ExecuTorchLocalInferenceDriver:LocalInferenceDriver{
    private data class Handle(val module:org.pytorch.executorch.extension.llm.LlmModule,val contextUnits:Int)
    private val active=ConcurrentHashMap<String,org.pytorch.executorch.extension.llm.LlmModule>()
    override fun open(profile:LocalModelProfile,settings:LocalModelSettings,artifact:LocalModelArtifact,backend:LocalRuntimeBackend):Any{
        require(profile.variants.single{it.variantUid==settings.variantUid}.format==LocalArtifactFormat.EXECUTORCH)
        require(backend in setOf(LocalRuntimeBackend.AUTO,LocalRuntimeBackend.CPU)){"RPGOS-P48:EXECUTORCH_BACKEND_UNPACKAGED"}
        val tokenizer=requireNotNull(artifact.tokenizerAbsolutePath){"RPGOS-P48:EXECUTORCH_TOKENIZER_REQUIRED"}
        val config=org.pytorch.executorch.extension.llm.LlmModuleConfig.create()
            .modulePath(artifact.absolutePath).tokenizerPath(tokenizer).temperature(0.1f)
            .modelType(org.pytorch.executorch.extension.llm.LlmModuleConfig.MODEL_TYPE_TEXT)
            .loadMode(org.pytorch.executorch.extension.llm.LlmModuleConfig.LOAD_MODE_MMAP).build()
        return Handle(org.pytorch.executorch.extension.llm.LlmModule(config).also{it.load()},settings.contextUnits)
    }
    override fun infer(handle:Any,requestUid:String,prompt:String,maximumOutputUnits:Int,cancellation:AiCancellationSignal,onChunk:(LocalGenerationChunk)->Unit):LocalGenerationOutput{
        val typed=handle as? Handle?:throw AiTransportException("EXECUTORCH_INVALID_HANDLE")
        val module=typed.module
        if(cancellation.isCancelled())throw AiTransportException("LOCAL_CANCELLED")
        require(active.putIfAbsent(requestUid,module)==null){"RPGOS-P48:LOCAL_DUPLICATE_REQUEST"}
        val output=StringBuilder();var tokens=0;var failure:String?=null;var stats=""
        val callback=object:org.pytorch.executorch.extension.llm.LlmCallback{
            override fun onResult(token:String){
                if(cancellation.isCancelled()){module.stop();return}
                output.append(token);tokens++;onChunk(LocalGenerationChunk(token,false))
            }
            override fun onStats(value:String){stats=value}
            override fun onError(code:Int,message:String){failure="EXECUTORCH_$code:$message"}
        }
        return try{
            val config=org.pytorch.executorch.extension.llm.LlmGenerationConfig.create().echo(false)
                .maxNewTokens(maximumOutputUnits).seqLen(typed.contextUnits).temperature(0.1f).build()
            module.generate(prompt,config,callback)
            if(cancellation.isCancelled())throw AiTransportException("LOCAL_CANCELLED")
            failure?.let{throw AiTransportException(it)}
            val text=output.toString();onChunk(LocalGenerationChunk("",true))
            LocalGenerationOutput(text,"EXECUTORCH:${digest("$requestUid|$stats|$tokens")}",0,tokens)
        }finally{active.remove(requestUid)}
    }
    override fun cancel(requestUid:String){active[requestUid]?.stop()}
    override fun close(handle:Any){(handle as? Handle)?.module?.let{module->runCatching{module.stop()};runCatching{module.resetContext()};module.close()}}
    private fun digest(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
}

private fun JSONArray?.strings():Set<String>{
    if(this==null)return emptySet();return buildSet{for(index in 0 until length())optString(index).takeIf{it.isNotBlank()}?.let(::add)}
}
private fun String.stripJsonFence():String{
    val trimmed=trim();if(!trimmed.startsWith("```"))return trimmed
    return trimmed.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
}
