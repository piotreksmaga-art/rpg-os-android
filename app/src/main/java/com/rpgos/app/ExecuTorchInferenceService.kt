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
                val characterCreation=prompt.contains("\"v\":\"RPGOS_CC_LOCAL_1\"")
                val intentRows=prompt.contains("\"v\":\"RPGOS_INTENT_LOCAL_6\"")
                val intentRoles=prompt.contains("\"v\":\"RPGOS_INTENT_LOCAL_9\"")
                val narrative=prompt.contains("\"v\":\"RPGOS_NARRATIVE_LOCAL_1\"")||prompt.contains("\"v\":\"RPGOS_NARRATIVE_LOCAL_REPAIR_1\"")
                val intentNamed=prompt.contains("\"v\":\"RPGOS_INTENT_LOCAL_8\"")
                val intentCompact=prompt.contains("\"v\":\"RPGOS_INTENT_LOCAL_7\"")
                val intentParsing=intentRoles||intentNamed||intentCompact||intentRows||prompt.contains("\"v\":\"RPGOS_INTENT_LOCAL_1\"")||prompt.contains("\"v\":\"RPGOS_INTENT_LOCAL_2\"")||prompt.contains("\"v\":\"RPGOS_INTENT_LOCAL_3\"")||prompt.contains("\"v\":\"RPGOS_INTENT_LOCAL_4\"")||prompt.contains("\"v\":\"RPGOS_INTENT_LOCAL_5\"")
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
                val structuredSeed=structuredSeed(prompt)
                val callback=object:org.pytorch.executorch.extension.llm.LlmCallback{
                    override fun onResult(token:String){
                        output.append(token);tokens++
                        val candidate=if(structuredSeed.isNotEmpty())seedCharacterCreationJson(output.toString(),structuredSeed) else output.toString()
                        if(tokens>=maximumOutputUnits||completeJsonObjectOrNull(candidate)!=null||(intentRows&&intentRowsComplete(candidate)))activeModule?.stop()
                    }
                    override fun onStats(value:String){stats=value}
                    override fun onError(code:Int,message:String){failure="EXECUTORCH_$code:$message"}
                }
                val generation=org.pytorch.executorch.extension.llm.LlmGenerationConfig.create().echo(false)
                    .maxNewTokens(maximumOutputUnits).seqLen(contextUnits).temperature(0.1f).build()
                module.generate(bielikChatPrompt(prompt),generation,callback)
                failure?.let{error(it)}
                val structured=normalizeStructuredOutput(prompt,output.toString())
                if(BuildConfig.DEBUG){
                    android.util.Log.d(
                        "RPGOS_LOCAL_AI",
                        "structured_output kind=${if(characterCreation)"CHARACTER_CREATION" else "GENERAL"} tokens=$tokens value=${structured.take(8_192)}"
                    )
                }
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
        internal fun structuredSeed(payload:String):String=when{
            payload.contains("\"v\":\"RPGOS_CC_LOCAL_1\"")->characterCreationSeed(payload)
            payload.contains("\"v\":\"RPGOS_NARRATIVE_LOCAL_1\"")||payload.contains("\"v\":\"RPGOS_NARRATIVE_LOCAL_REPAIR_1\"")->"{\"t\":\""
            payload.contains("\"v\":\"RPGOS_GM_LOCAL_1\"")||payload.contains("\"v\":\"RPGOS_GM_LOCAL_REPAIR_1\"")->"{\"n\":["
            payload.contains("\"v\":\"RPGOS_INTENT_LOCAL_6\"")->""
            payload.contains("\"v\":\"RPGOS_INTENT_LOCAL_9\"")->"{\"steps\":[{\"locality\":\""
            payload.contains("\"v\":\"RPGOS_INTENT_LOCAL_8\"")->"{\"actions\":[{\"route\":\""
            payload.contains("\"v\":\"RPGOS_INTENT_LOCAL_7\"")->"{\"a\":[[\""
            payload.contains("\"v\":\"RPGOS_INTENT_LOCAL_5\"")->"{\"s\":\"U\",\"n\":[{"
            payload.contains("\"v\":\"RPGOS_INTENT_LOCAL_4\"")->"{\"s\":\"U\",\"n\":[[\""
            listOf("RPGOS_INTENT_LOCAL_1","RPGOS_INTENT_LOCAL_2","RPGOS_INTENT_LOCAL_3").any(payload::contains)->"{\"s\":\""
            else->""
        }
        internal fun normalizeStructuredOutput(payload:String,value:String):String{
            val seed=structuredSeed(payload)
            val candidate=if(seed.isNotEmpty())seedCharacterCreationJson(value,seed) else value
            return when{
                payload.contains("\"v\":\"RPGOS_INTENT_LOCAL_6\"")->bielikPlainOutput(candidate)
                payload.contains("\"v\":\"RPGOS_INTENT_LOCAL_9\"")->recoverNamedIntentSteps(candidate)?:bielikStructuredOutput(candidate)
                else->bielikStructuredOutput(candidate)
            }
        }
        internal fun bielikChatPrompt(payload:String):String{
            if(payload.contains("\"v\":\"RPGOS_INTENT_LOCAL_9\"")){
                val root=org.json.JSONObject(payload)
                val rawInput=root.optString("u")
                val segments=root.optJSONArray("segments")?.let{array->(0 until array.length()).joinToString(" | "){array.optString(it)}}.orEmpty()
                return """<|im_start|>system
Jesteś parserem. Analizujesz wyłącznie tekst gracza i zwracasz tylko krótki JSON. Nie opowiadasz historii. Pola wyniku opisują wiadomość gracza, nigdy słowa tej instrukcji.<|im_end|>
<|im_start|>user
Każda niezależna czynność z wiadomości gracza to osobny element steps. Każdy element zawiera locality, kind i action. kind to MOVE, COMBAT, TRAIN, QUERY, TALK albo ACTION. Role występują dosłownie w polach: destination=dokąd, where=gdzie lub skąd, who=kto, what=bezpośredni obiekt czynności; jeden step może mieć kilka ról. Dla „biorę miecz ze stojaka” what to „miecz”, a where to „stojaka”. locality to L dla celu lokalnego lub bliskiego, R dla odległego albo wymagającego podróży, U przy braku danych. Bez podmiotu „ja”, bez słów instrukcji i bez dodatkowych pól.
FRAGMENTY POMOCNICZE (sprawdź każdy, ale nie twórz czynności z samego rzeczownika):
$segments
TEKST GRACZA (jedyne źródło wartości):
$rawInput<|im_end|>
<|im_start|>assistant
{"steps":[{"locality":"""".trimIndent()
            }
            if(payload.contains("\"v\":\"RPGOS_INTENT_LOCAL_8\"")){
                val root=org.json.JSONObject(payload)
                val rawInput=root.optString("u")
                val task=root.optString("reply")
                return """<|im_start|>system
Jesteś parserem znaczenia tury RPG OS. Nie opowiadasz historii i nie wykonujesz akcji. Dokańczasz wyłącznie rozpoczęty obiekt JSON.<|im_end|>
<|im_start|>user
WIADOMOŚĆ GRACZA:
$rawInput
ZADANIE:
$task
Zamknij obiekt natychmiast po opisaniu wszystkich czynności.<|im_end|>
<|im_start|>assistant
{"actions":[{"route":"""".trimIndent()
            }
            if(payload.contains("\"v\":\"RPGOS_CC_LOCAL_1\"")){
                val (data,task)=runCatching{
                    val root=org.json.JSONObject(payload)
                    val extracted=root.optString("reply")
                    root.remove("reply")
                    root.toString() to extracted
                }.getOrElse{payload to ""}
                val seed=characterCreationSeed(payload)
                return """<|im_start|>system
Jesteś lokalnym adapterem RPG OS. Odpowiadasz wyłącznie jednym krótkim obiektem JSON po polsku. Nie kopiujesz danych wejściowych i nie używasz klucza reply.<|im_end|>
<|im_start|>user
DANE JSON:
$data
ZADANIE:
$task
Najważniejsze: nie zwracaj v, x, d ani reply. Zwróć tylko status s=Q lub s=R zgodnie z zadaniem i zakończ na pierwszym }.<|im_end|>
<|im_start|>assistant
$seed""".trimIndent()
            }
            if(payload.contains("\"v\":\"RPGOS_INTENT_LOCAL_6\"")){
                val root=org.json.JSONObject(payload)
                val rawInput=root.optString("u")
                val task=root.optString("reply")
                return """<|im_start|>system
Jesteś parserem znaczenia tury RPG OS. Nie opowiadasz historii i nie wykonujesz akcji. Rozpoznajesz dowolne działania, obiekty i sekwencje. Zwracasz wyłącznie krótkie wiersze zgodne z formatem użytkownika, bez JSON i bez komentarza.<|im_end|>
<|im_start|>user
WIADOMOŚĆ GRACZA:
$rawInput
ZADANIE:
$task
Analizuj tylko wiadomość gracza. Po ostatnim wierszu napisz END.<|im_end|>
<|im_start|>assistant
ACTIONS
""".trimIndent()
            }
            if(payload.contains("\"v\":\"RPGOS_INTENT_LOCAL_7\"")){
                val root=org.json.JSONObject(payload)
                val rawInput=root.optString("u")
                val task=root.optString("reply")
                return """<|im_start|>system
Jesteś parserem znaczenia tury RPG OS. Nie opowiadasz historii i nie wykonujesz akcji. Uzupełniasz tylko rozpoczęty JSON, nie powtarzasz wiadomości ani instrukcji.<|im_end|>
<|im_start|>user
WIADOMOŚĆ GRACZA:
$rawInput
ZADANIE:
$task
Zamknij wszystkie listy i obiekt natychmiast po ostatniej czynności.<|im_end|>
<|im_start|>assistant
{"a":[["""".trimIndent()
            }
            if(payload.contains("\"v\":\"RPGOS_INTENT_LOCAL_1\"")||payload.contains("\"v\":\"RPGOS_INTENT_LOCAL_2\"")||payload.contains("\"v\":\"RPGOS_INTENT_LOCAL_3\"")||payload.contains("\"v\":\"RPGOS_INTENT_LOCAL_4\"")||payload.contains("\"v\":\"RPGOS_INTENT_LOCAL_5\"")){
                val (data,task)=runCatching{
                    val root=org.json.JSONObject(payload)
                    val extracted=root.optString("reply")
                    root.remove("reply")
                    root.toString() to extracted
                }.getOrElse{payload to ""}
                val seed=when{
                    payload.contains("\"v\":\"RPGOS_INTENT_LOCAL_5\"")->"{\"s\":\"U\",\"n\":[{"
                    payload.contains("\"v\":\"RPGOS_INTENT_LOCAL_4\"")->"{\"s\":\"U\",\"n\":[[\""
                    else->"{\"s\":\""
                }
                return """<|im_start|>system
 Jesteś parserem znaczenia tury RPG OS. Nie opowiadasz historii i nie wykonujesz akcji. Rozpoznajesz dowolne rodzaje obiektów i wszystkie działania gracza, także sekwencje. Zwracasz wyłącznie jeden mały obiekt JSON zgodny z podanym formatem. Każdy cel pozostaje opisem; nie wymyślaj UID ani faktów świata.<|im_end|>
<|im_start|>user
DANE:
$data
ZADANIE:
$task
Nie kopiuj przykładu. Przeanalizuj wyłącznie DANE.u. Zwróć tylko JSON i zakończ na pierwszym kompletnym obiekcie.<|im_end|>
<|im_start|>assistant
$seed""".trimIndent()
            }
            if(payload.contains("\"v\":\"RPGOS_GM_LOCAL_1\"")||payload.contains("\"v\":\"RPGOS_GM_LOCAL_REPAIR_1\"")){
                val (data,task)=runCatching{
                    val root=org.json.JSONObject(payload)
                    val extracted=root.optString("reply")
                    root.remove("reply")
                    root.toString() to extracted
                }.getOrElse{payload to ""}
                return """<|im_start|>system
Jesteś Mistrzem Gry RPG OS na etapie oceny propozycji. Nie zmieniasz mechaniki ani świata. Dla każdego węzła wybierasz tylko OK, F albo Q i podajesz krótkie polskie podsumowanie. Zwracasz wyłącznie jeden obiekt JSON.<|im_end|>
<|im_start|>user
DANE:
$data
ZADANIE:
$task
Nie zmieniaj id. Zwróć tylko JSON i zakończ na pierwszym kompletnym obiekcie.<|im_end|>
<|im_start|>assistant
{"n":[""".trimIndent()
            }
            if(payload.contains("\"v\":\"RPGOS_NARRATIVE_LOCAL_1\"")||payload.contains("\"v\":\"RPGOS_NARRATIVE_LOCAL_REPAIR_1\"")){
                val narrativeData=runCatching{
                    val root=org.json.JSONObject(payload)
                    val consequences=root.optJSONArray("results")?.let{array->
                        (0 until array.length()).map{array.optString(it)}.filter(String::isNotBlank)
                    }.orEmpty()
                    val scene=root.optJSONArray("scene")?.let{array->(0 until array.length()).mapNotNull{index->
                        array.optJSONObject(index)?.optString("text")?.trim()?.takeIf(String::isNotBlank)
                    }}.orEmpty()
                    Triple(root.optString("player_action").trim(),consequences,scene)
                }.getOrDefault(Triple("",emptyList(),emptyList()))
                val (playerAction,consequences,scene)=narrativeData
                val approved=consequences.mapIndexed{index,value->"${index+1}. $value"}.joinToString("\n")
                val visibleScene=scene.mapIndexed{index,value->"${index+1}. $value"}.joinToString("\n")
                return """<|im_start|>system
Jesteś polskim Mistrzem Gry. Opisujesz zatwierdzoną turę naturalnie i konkretnie w drugiej osobie. Tekst działania gracza jest zapisany w pierwszej osobie; musisz zmienić ją na drugą, np. „Rozglądam się” na „Rozglądasz się”. Nie pisz jako gracz. Nie dodajesz osób, miejsc, zdarzeń, wyników ani działań. Bez słów „czynność”, „postęp”, „mechanika”, bez nagłówków i list. Zwracasz tylko krótki JSON z tekstem w polu t i vol=false.<|im_end|>
<|im_start|>user
DZIAŁANIE GRACZA JUŻ PODJĘTE W TEJ TURZE:
$playerAction
WIDOCZNE FAKTY SCENY:
$visibleScene
ZATWIERDZONE SKUTKI:
$approved
Napisz 1–2 krótkie, naturalne polskie zdania. Opisz wyłącznie podjęte działanie i zatwierdzone skutki; fakty sceny służą tylko jako tło. Nie pokazuj identyfikatorów ani treści polecenia. Zwróć {"t":"tekst","vol":false} i zakończ na pierwszym domkniętym obiekcie.<|im_end|>
<|im_start|>assistant
{"t":"""".trimIndent()
            }
            val contract=runCatching{org.json.JSONObject(payload).optString("contract")}.getOrDefault("")
            val contractInstruction=when(contract){
                "RPGOS_INTENT_DOCUMENT_V2"->"Zwróć pełny IntentDocument według response_example. Zachowaj campaign_uid, aktora i raw_input dokładnie. Referencje muszą pozostać opisowe i nierozwiązane."
                "RPGOS_GM_PROPOSAL_V1","RPGOS_GM_PROPOSAL_REPAIR_V1"->"Zwróć pełny GmProposalCandidate JSON. Użyj dokładnych plan_uid, node_uid, aktora, akcji, celów, modality i intent_fingerprint z danych. Nie twórz skutków dla NEEDS_CLARIFICATION."
                "RPGOS_COMMITTED_NARRATIVE_V2","RPGOS_COMMITTED_NARRATIVE_REPAIR_V1"->"Zwróć JSON z polami text, stop_reason_uid, committed_order, claims i asserts_player_volition. Używaj wyłącznie podanych faktów i nie dopisuj działań gracza."
                else->"Wykonaj wymagania kontraktu i zwróć kompletny wynikowy obiekt JSON."
            }
            return """<|im_start|>system
Jesteś lokalnym adapterem RPG OS. Nigdy nie przepisuj danych wejściowych. Zwróć wyłącznie jeden kompletny obiekt JSON, bez Markdownu i komentarza. $contractInstruction<|im_end|>
<|im_start|>user
DANE:
$payload
Zwróć wyłącznie JSON zgodny z kontraktem i zakończ na pierwszym domkniętym obiekcie.<|im_end|>
<|im_start|>assistant
""".trimIndent()
        }
        internal fun characterCreationSeed(payload:String):String{
            val mode=runCatching{org.json.JSONObject(payload).optString("mode")}.getOrDefault("")
            return if(mode=="CATALOG_QUESTION")"{\"s\":\"Q\",\"q\":\"" else "{\"s\":\"R\",\"n\":\""
        }
        internal fun seedCharacterCreationJson(value:String,prefix:String="{\"s\":\""):String{
            val cleaned=value.trimStart()
            return when{
                cleaned.startsWith('{')->cleaned
                cleaned.startsWith("\"s\"")->"{$cleaned"
                else->prefix+cleaned.removePrefix("\"")
            }
        }
        internal fun bielikStructuredOutput(value:String):String{
            val cleaned=value.trim().removePrefix("<|im_start|>assistant")
                .substringBefore("<|im_end|>").substringBefore("</s>").trim()
            return completeJsonObjectOrNull(cleaned)?:cleaned.substring(cleaned.indexOf('{').coerceAtLeast(0))
        }
        internal fun bielikPlainOutput(value:String):String=value.trim().removePrefix("<|im_start|>assistant")
            .substringBefore("<|im_end|>").substringBefore("</s>").trim()
        /** Recovers only fully closed step objects when a small model reaches its token ceiling.
         * Incomplete trailing text is discarded; every recovered phrase is still grounded by Core. */
        internal fun recoverNamedIntentSteps(value:String):String?{
            val cleaned=value.trim().removePrefix("<|im_start|>assistant")
                .substringBefore("<|im_end|>").substringBefore("</s>").trim()
            val marker=cleaned.indexOf("\"steps\"")
            val arrayStart=cleaned.indexOf('[',marker.coerceAtLeast(0))
            if(marker<0||arrayStart<0)return null
            val steps=org.json.JSONArray();var objectStart=-1;var depth=0;var quoted=false;var escaped=false
            for(index in arrayStart+1 until cleaned.length){
                val character=cleaned[index]
                if(quoted){
                    when{escaped->escaped=false;character=='\\'->escaped=true;character=='"'->quoted=false}
                }else when(character){
                    '"'->quoted=true
                    '{'->{if(depth==0)objectStart=index;depth++}
                    '}'->{
                        if(depth>0)depth--
                        if(depth==0&&objectStart>=0){
                            runCatching{org.json.JSONObject(cleaned.substring(objectStart,index+1))}.getOrNull()
                                ?.takeIf{it.optString("action").isNotBlank()}?.let(steps::put)
                            objectStart=-1
                        }
                    }
                    ']'->if(depth==0)return org.json.JSONObject().put("steps",steps).toString().takeIf{steps.length()>0}
                }
            }
            return org.json.JSONObject().put("steps",steps).toString().takeIf{steps.length()>0}
        }
        internal fun intentRowsComplete(value:String):Boolean=value.lineSequence().any{it.trim().uppercase()=="END"}
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
            // A real Bielik 1.5B draft reached the former 320-token ceiling at token 319 and was
            // consequently rejected as truncated JSON. 512 still fits the shipped 2k context
            // with the bounded creator prompt while leaving enough room for one compact R draft.
            val outputLimit=if(prompt.contains("\"v\":\"RPGOS_CC_LOCAL_1\""))minOf(maximumOutputUnits,512) else maximumOutputUnits
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
