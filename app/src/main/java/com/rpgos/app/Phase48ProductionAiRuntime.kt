package com.rpgos.app

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

enum class AiRole { GAME_MASTER, DIRECTOR_SCENARIST }
enum class AiAssignmentKind { AUTO, PINNED }

data class AiModelSelection(val providerUid:String,val modelUid:String){
    init{require(providerUid.isNotBlank()&&modelUid.isNotBlank())}
    val stableUid:String get()="$providerUid::$modelUid"
}

data class AiRoleAssignment(
    val role:AiRole,
    val kind:AiAssignmentKind=AiAssignmentKind.AUTO,
    val pinned:AiModelSelection?=null
){init{require((kind==AiAssignmentKind.PINNED)==(pinned!=null))}}

data class AiSystemConfiguration(
    val gameMaster:AiRoleAssignment=AiRoleAssignment(AiRole.GAME_MASTER),
    val director:AiRoleAssignment=AiRoleAssignment(AiRole.DIRECTOR_SCENARIST),
    val privacy:AiPrivacyPolicy=AiPrivacyPolicy(),
    val localModelSettings:LocalModelSettings?=null
){init{require(gameMaster.role==AiRole.GAME_MASTER&&director.role==AiRole.DIRECTOR_SCENARIST)}}

data class AiPrivacyPolicy(
    val cloudAllowed:Boolean=true,
    val cloudAllowedForPlayerText:Boolean=true,
    val cloudAllowedForDirector:Boolean=true
)

enum class AiAvailabilityState { READY, DEGRADED, NOT_CONFIGURED, UNAVAILABLE }
data class AiProviderAvailability(
    val selection:AiModelSelection,
    val state:AiAvailabilityState,
    val reasonUid:String,
    val resourceAdmitted:Boolean=true
){init{require(reasonUid.isNotBlank())}}

fun interface AiAvailabilityPort{fun availability(provider:AiProvider):AiProviderAvailability}

sealed interface AiRouteResult{
    data class Selected(val provider:AiProvider,val automatic:Boolean,val reasonUid:String):AiRouteResult
    data class Unavailable(val reasonUids:List<String>):AiRouteResult{init{require(reasonUids.isNotEmpty())}}
}

/**
 * Production-minimum deterministic router. Phase79 may optimise quality/cost/latency, but may not
 * change these privacy, capability or explicit-pin semantics.
 */
class RoleAwareModelRouter(
    private val registry:AiProviderRegistry,
    assignments:List<AiRoleAssignment>,
    private val privacy:AiPrivacyPolicy,
    private val availability:AiAvailabilityPort
){
    private val assignments=assignments.associateBy{it.role}
    init{require(this.assignments.size==assignments.size);AiRole.entries.forEach{require(it in this.assignments)}}

    fun route(role:AiRole,workload:AiWorkload,requiredContextUnits:Int=0):AiRouteResult{
        require(requiredContextUnits>=0)
        val assignment=assignments.getValue(role)
        val evaluatedCapabilities=registry.all().map{provider->provider to when{
            workload !in provider.capabilities.supportedWorkloads->"WORKLOAD_UNSUPPORTED:${workload.name}"
            provider.capabilities.maximumContextUnits<requiredContextUnits->
                "CONTEXT_LIMIT_EXCEEDED:required=$requiredContextUnits:maximum=${provider.capabilities.maximumContextUnits}"
            !cloudPermitted(role,provider)->"PRIVACY_POLICY_REJECTED"
            else->null
        }}
        val eligible=evaluatedCapabilities.filter{it.second==null}.map{it.first}
        if(assignment.kind==AiAssignmentKind.PINNED){
            val pin=assignment.pinned!!
            val provider=registry.find(pin.providerUid,pin.modelUid)
                ?:return AiRouteResult.Unavailable(listOf("PINNED_MODEL_NOT_REGISTERED:${pin.stableUid}"))
            evaluatedCapabilities.first{it.first===provider}.second?.let{reason->
                return AiRouteResult.Unavailable(listOf("PINNED_MODEL_REJECTED:${pin.stableUid}:$reason"))
            }
            val state=availability.availability(provider)
            if(state.state!=AiAvailabilityState.READY||!state.resourceAdmitted)return AiRouteResult.Unavailable(listOf("PINNED_MODEL_UNAVAILABLE:${state.reasonUid}"))
            return AiRouteResult.Selected(provider,false,"EXPLICIT_ROLE_PIN")
        }
        val evaluated=eligible.map{it to availability.availability(it)}
        val ready=evaluated.filter{(_,state)->state.state==AiAvailabilityState.READY&&state.resourceAdmitted}
            .sortedWith(compareBy<Pair<AiProvider,AiProviderAvailability>>{
                autoKindRank(role,it.first.capabilities.providerKind)
            }.thenByDescending{it.first.capabilities.maximumContextUnits}
                .thenBy{it.first.capabilities.providerUid}.thenBy{it.first.capabilities.modelUid})
        val selected=ready.firstOrNull()?.first
            ?:return AiRouteResult.Unavailable((
                evaluatedCapabilities.mapNotNull{(provider,reason)->reason?.let{"${provider.capabilities.providerUid}:$it"}}+
                    evaluated.map{"${it.first.capabilities.providerUid}:${it.second.reasonUid}"}+"NO_ELIGIBLE_MODEL"
                ).distinct().sorted())
        return AiRouteResult.Selected(selected,true,"AUTO_DETERMINISTIC_CAPABILITY_ROUTE")
    }

    private fun cloudPermitted(role:AiRole,provider:AiProvider):Boolean =
        provider.capabilities.providerKind!=AiProviderKind.CLOUD ||
            (privacy.cloudAllowed && (role!=AiRole.GAME_MASTER||privacy.cloudAllowedForPlayerText) &&
                (role!=AiRole.DIRECTOR_SCENARIST||privacy.cloudAllowedForDirector))

    private fun autoKindRank(role:AiRole,kind:AiProviderKind)=when(kind){
        AiProviderKind.LOCAL->0
        AiProviderKind.CLOUD->if(role==AiRole.DIRECTOR_SCENARIST)1 else 2
        AiProviderKind.CONTROLLED_TEST->3
    }
}

enum class LocalRuntimeBackend { AUTO, CPU, GPU, NPU }
enum class LocalThermalState { NOMINAL, WARM, HOT, CRITICAL, UNKNOWN }
enum class LocalArtifactFormat { GGUF, LITERT, EXECUTORCH, VENDOR_NATIVE }

data class LocalArtifactVariant(
    val variantUid:String,
    val format:LocalArtifactFormat,
    val quantizationUid:String,
    val expectedBytes:Long,
    val sha256:String?=null
){init{
    require(variantUid.isNotBlank()&&quantizationUid.isNotBlank()&&expectedBytes>0)
    require(sha256==null||sha256.matches(Regex("[0-9a-fA-F]{64}")))
}}

data class LocalModelProfile(
    val modelUid:String,
    val displayName:String,
    val familyUid:String,
    val tokenizerUid:String,
    val chatTemplateUid:String,
    val supportedWorkloads:Set<AiWorkload>,
    val recommendedContextUnits:Int,
    val maximumContextUnits:Int,
    val recommendedKvBytesPerContextUnit:Long,
    val variants:List<LocalArtifactVariant>
){init{
    require(modelUid.isNotBlank()&&displayName.isNotBlank()&&familyUid.isNotBlank()&&tokenizerUid.isNotBlank()&&chatTemplateUid.isNotBlank())
    require(supportedWorkloads.isNotEmpty()&&recommendedContextUnits>0&&maximumContextUnits>=recommendedContextUnits)
    require(recommendedKvBytesPerContextUnit>0&&variants.isNotEmpty()&&variants.map{it.variantUid}.distinct().size==variants.size)
}}

object BielikLocalModelProfiles{
    /** Mobile-first profile shipped as a separate RPG OS package for current Android devices. */
    val BIELIK_1_5B_V3_EXECUTORCH = LocalModelProfile(
        modelUid="speakleash/bielik-1.5b-v3.0-instruct",
        displayName="Bielik 1.5B v3.0 Instruct (mobilny)",
        familyUid="BIELIK",
        tokenizerUid="BIELIK_SENTENCEPIECE",
        chatTemplateUid="BIELIK_CHAT_V3",
        supportedWorkloads=setOf(
            AiWorkload.INTENT_INTERPRETATION,AiWorkload.GM_PROPOSAL,AiWorkload.PROPOSAL_REPAIR,AiWorkload.CHARACTER_CREATION,
            AiWorkload.NARRATIVE_RENDER,AiWorkload.NARRATIVE_REPAIR,AiWorkload.DIRECTOR_STRATEGY
        ),
        recommendedContextUnits=2_048,
        maximumContextUnits=2_048,
        recommendedKvBytesPerContextUnit=65_536,
        variants=listOf(
            LocalArtifactVariant(
                "EXECUTORCH-XNNPACK-8DA4W",
                LocalArtifactFormat.EXECUTORCH,
                "8DA4W",
                923_083_008L,
                "4e5a6b8e6684e94d794a609a2f76cfb56f3b3ddef3dfc96904cd10f40244457e"
            )
        )
    )

    /** Data profile, not a Bielik-specific engine. Artifact is supplied/imported by the user. */
    val BIELIK_4_5B_V3 = LocalModelProfile(
        modelUid="speakleash/bielik-4.5b-v3-instruct",
        displayName="Bielik 4.5B v3 Instruct",
        familyUid="BIELIK",
        tokenizerUid="BIELIK_SENTENCEPIECE",
        chatTemplateUid="BIELIK_CHAT_V3",
        supportedWorkloads=setOf(
            AiWorkload.INTENT_INTERPRETATION,AiWorkload.GM_PROPOSAL,AiWorkload.PROPOSAL_REPAIR,AiWorkload.CHARACTER_CREATION,
            AiWorkload.NARRATIVE_RENDER,AiWorkload.NARRATIVE_REPAIR,AiWorkload.DIRECTOR_STRATEGY
        ),
        recommendedContextUnits=8_192,
        maximumContextUnits=32_768,
        recommendedKvBytesPerContextUnit=262_144,
        variants=listOf(
            LocalArtifactVariant("GGUF-Q4_K_M",LocalArtifactFormat.GGUF,"Q4_K_M",3_200_000_000L),
            LocalArtifactVariant("GGUF-Q5_K_M",LocalArtifactFormat.GGUF,"Q5_K_M",3_900_000_000L)
        )
    )

    /** Production Android profile for the official packaged ExecuTorch runtime. */
    val BIELIK_4_5B_V3_EXECUTORCH = BIELIK_4_5B_V3.copy(
        displayName="Bielik 4.5B v3 Instruct (lokalny ExecuTorch)",
        recommendedContextUnits=4_096,
        maximumContextUnits=16_384,
        recommendedKvBytesPerContextUnit=131_072,
        variants=listOf(
            LocalArtifactVariant("EXECUTORCH-XNNPACK",LocalArtifactFormat.EXECUTORCH,"EXPORT_QUANTIZED",3_600_000_000L)
        )
    )

    val DEFAULT_ANDROID:LocalModelProfile get()=BIELIK_1_5B_V3_EXECUTORCH

    fun byModelUid(modelUid:String?):LocalModelProfile?=when(modelUid){
        BIELIK_1_5B_V3_EXECUTORCH.modelUid->BIELIK_1_5B_V3_EXECUTORCH
        BIELIK_4_5B_V3_EXECUTORCH.modelUid->BIELIK_4_5B_V3_EXECUTORCH
        else->null
    }
}

data class LocalRuntimeCapabilities(
    val runtimeUid:String,
    val supportedFormats:Set<LocalArtifactFormat>,
    val supportedBackends:Set<LocalRuntimeBackend>,
    val supportsContextTuning:Boolean,
    val supportsKvTuning:Boolean,
    val supportsThreads:Boolean,
    val supportsBatchPrefill:Boolean,
    val supportsCancellation:Boolean,
    val supportsStreaming:Boolean
){init{require(runtimeUid.isNotBlank()&&supportedFormats.isNotEmpty()&&supportedBackends.isNotEmpty())}}

data class LocalDeviceCapabilities(
    val availableMemoryBytes:Long,
    val totalMemoryBytes:Long,
    val thermalState:LocalThermalState,
    val availableBackends:Set<LocalRuntimeBackend>,
    val recommendedSafetyMarginBytes:Long
){init{
    require(availableMemoryBytes>=0&&totalMemoryBytes>0&&availableMemoryBytes<=totalMemoryBytes&&recommendedSafetyMarginBytes>=0)
    require(availableBackends.isNotEmpty())
}}

data class LocalModelSettings(
    val modelUid:String,
    val variantUid:String,
    val contextUnits:Int,
    val kvBytesPerContextUnit:Long,
    val backend:LocalRuntimeBackend=LocalRuntimeBackend.AUTO,
    val threads:Int?=null,
    val prefillBatchUnits:Int?=null,
    val recommended:Boolean=true
){init{
    require(modelUid.isNotBlank()&&variantUid.isNotBlank()&&contextUnits>0&&kvBytesPerContextUnit>0)
    require(threads==null||threads in 1..256);require(prefillBatchUnits==null||prefillBatchUnits in 1..65_536)
}}

object LocalRecommendedSettings{
    fun forProfile(profile:LocalModelProfile)=LocalModelSettings(
        profile.modelUid,profile.variants.first().variantUid,profile.recommendedContextUnits,
        profile.recommendedKvBytesPerContextUnit,LocalRuntimeBackend.AUTO,recommended=true
    )
}

sealed interface LocalAdmissionResult{
    data class Admitted(val selectedBackend:LocalRuntimeBackend,val estimatedPeakBytes:Long,val reasonUid:String):LocalAdmissionResult
    data class Rejected(val reasonUids:List<String>,val suggested:LocalModelSettings?):LocalAdmissionResult{init{require(reasonUids.isNotEmpty())}}
}

class LocalModelAdmissionController{
    fun evaluate(
        profile:LocalModelProfile,
        settings:LocalModelSettings,
        runtime:LocalRuntimeCapabilities,
        device:LocalDeviceCapabilities
    ):LocalAdmissionResult{
        val reasons=linkedSetOf<String>()
        if(settings.modelUid!=profile.modelUid)reasons+="MODEL_SETTINGS_IDENTITY_MISMATCH"
        val variant=profile.variants.singleOrNull{it.variantUid==settings.variantUid}
        if(variant==null)reasons+="ARTIFACT_VARIANT_UNSUPPORTED"
        else if(variant.format !in runtime.supportedFormats)reasons+="ARTIFACT_FORMAT_UNSUPPORTED"
        if(settings.contextUnits>profile.maximumContextUnits)reasons+="CONTEXT_LIMIT_EXCEEDED"
        if(!runtime.supportsContextTuning&&settings.contextUnits!=profile.recommendedContextUnits)reasons+="CONTEXT_TUNING_UNSUPPORTED"
        if(!runtime.supportsKvTuning&&settings.kvBytesPerContextUnit!=profile.recommendedKvBytesPerContextUnit)reasons+="KV_TUNING_UNSUPPORTED"
        if(!runtime.supportsThreads&&settings.threads!=null)reasons+="THREAD_TUNING_UNSUPPORTED"
        if(!runtime.supportsBatchPrefill&&settings.prefillBatchUnits!=null)reasons+="PREFILL_TUNING_UNSUPPORTED"
        val backend=selectBackend(settings.backend,runtime,device)?:run{reasons+="BACKEND_UNAVAILABLE";LocalRuntimeBackend.CPU}
        if(device.thermalState==LocalThermalState.CRITICAL)reasons+="THERMAL_CRITICAL"
        val artifactBytes=variant?.expectedBytes?:0L
        val kvBytes=saturatedMultiply(settings.contextUnits.toLong(),settings.kvBytesPerContextUnit)
        val runtimeBuffers=maxOf(256L*1024*1024,artifactBytes/8)
        val estimate=saturatedAdd(saturatedAdd(artifactBytes,kvBytes),saturatedAdd(runtimeBuffers,device.recommendedSafetyMarginBytes))
        if(estimate>device.availableMemoryBytes)reasons+="UNSAFE_MEMORY_PROFILE"
        if(reasons.isNotEmpty()){
            val suggested=LocalRecommendedSettings.forProfile(profile).takeIf{it!=settings}
            return LocalAdmissionResult.Rejected(reasons.sorted(),suggested)
        }
        return LocalAdmissionResult.Admitted(backend,estimate,"RESOURCE_PROFILE_ADMITTED")
    }

    private fun selectBackend(requested:LocalRuntimeBackend,runtime:LocalRuntimeCapabilities,device:LocalDeviceCapabilities):LocalRuntimeBackend?{
        val available=runtime.supportedBackends.intersect(device.availableBackends)
        if(requested!=LocalRuntimeBackend.AUTO)return requested.takeIf{it in available}
        return listOf(LocalRuntimeBackend.NPU,LocalRuntimeBackend.GPU,LocalRuntimeBackend.CPU).firstOrNull{it in available}
    }
    private fun saturatedMultiply(a:Long,b:Long)=if(a==0L||b<=Long.MAX_VALUE/a)a*b else Long.MAX_VALUE
    private fun saturatedAdd(a:Long,b:Long)=if(b<=Long.MAX_VALUE-a)a+b else Long.MAX_VALUE
}

data class LocalModelArtifact(
    val modelUid:String,val variantUid:String,val absolutePath:String,val byteSize:Long,val sha256:String,
    val tokenizerAbsolutePath:String?=null
){init{
    require(modelUid.isNotBlank()&&variantUid.isNotBlank()&&absolutePath.isNotBlank()&&byteSize>0)
    require(sha256.matches(Regex("[0-9a-fA-F]{64}")))
    require(tokenizerAbsolutePath?.isBlank()!=true)
}}

fun interface LocalModelArtifactStore{fun find(modelUid:String,variantUid:String):LocalModelArtifact?}

data class LocalGenerationRequest(
    val requestUid:String,val workload:AiWorkload,val structuredPrompt:String,val maximumOutputUnits:Int,
    val model:LocalModelProfile,val settings:LocalModelSettings,val artifact:LocalModelArtifact
)
data class LocalGenerationChunk(val text:String,val final:Boolean=false)
data class LocalGenerationOutput(val structuredPayload:String,val traceUid:String,val inputUnits:Int,val outputUnits:Int)
data class LocalRuntimeMetrics(
    val loadedModelUid:String?,val residentBytes:Long,val kvBytes:Long,val backend:LocalRuntimeBackend?,
    val thermalState:LocalThermalState,val activeRequestCount:Int
)

interface LocalInferenceRuntime{
    val capabilities:LocalRuntimeCapabilities
    fun load(profile:LocalModelProfile,settings:LocalModelSettings,artifact:LocalModelArtifact,admission:LocalAdmissionResult.Admitted)
    fun generate(request:LocalGenerationRequest,cancellation:AiCancellationSignal,onChunk:(LocalGenerationChunk)->Unit={}):LocalGenerationOutput
    fun cancel(requestUid:String)
    fun unload(modelUid:String)
    fun metrics():LocalRuntimeMetrics
}

/** Driver isolates JNI/LiteRT/ExecuTorch/vendor details from the stable production runtime port. */
interface LocalInferenceDriver{
    fun open(profile:LocalModelProfile,settings:LocalModelSettings,artifact:LocalModelArtifact,backend:LocalRuntimeBackend):Any
    fun infer(handle:Any,requestUid:String,prompt:String,maximumOutputUnits:Int,cancellation:AiCancellationSignal,onChunk:(LocalGenerationChunk)->Unit):LocalGenerationOutput
    fun cancel(requestUid:String)
    fun close(handle:Any)
}

class DriverBackedLocalInferenceRuntime(
    override val capabilities:LocalRuntimeCapabilities,
    private val driver:LocalInferenceDriver
):LocalInferenceRuntime{
    private data class Loaded(val profile:LocalModelProfile,val settings:LocalModelSettings,val handle:Any,val backend:LocalRuntimeBackend,val artifactBytes:Long)
    private val loaded=AtomicReference<Loaded?>(null)
    private val active=ConcurrentHashMap.newKeySet<String>()
    override fun load(profile:LocalModelProfile,settings:LocalModelSettings,artifact:LocalModelArtifact,admission:LocalAdmissionResult.Admitted){
        val current=loaded.get()
        if(current?.profile?.modelUid==profile.modelUid&&current.settings==settings)return
        current?.let{driver.close(it.handle)}
        loaded.set(Loaded(profile,settings,driver.open(profile,settings,artifact,admission.selectedBackend),admission.selectedBackend,artifact.byteSize))
    }
    override fun generate(request:LocalGenerationRequest,cancellation:AiCancellationSignal,onChunk:(LocalGenerationChunk)->Unit):LocalGenerationOutput{
        val state=loaded.get()?:throw AiTransportException("LOCAL_MODEL_NOT_LOADED")
        require(state.profile.modelUid==request.model.modelUid&&state.settings==request.settings){"RPGOS-P48:LOCAL_MODEL_STATE_MISMATCH"}
        if(!active.add(request.requestUid))throw AiTransportException("LOCAL_DUPLICATE_REQUEST")
        return try{driver.infer(state.handle,request.requestUid,request.structuredPrompt,request.maximumOutputUnits,cancellation,onChunk)}finally{active-=request.requestUid}
    }
    override fun cancel(requestUid:String){driver.cancel(requestUid)}
    override fun unload(modelUid:String){loaded.getAndSet(null)?.takeIf{it.profile.modelUid==modelUid}?.let{driver.close(it.handle)}}
    override fun metrics():LocalRuntimeMetrics{
        val state=loaded.get()
        val kv=state?.settings?.let{saturatedMultiply(it.contextUnits.toLong(),it.kvBytesPerContextUnit)}?:0L
        return LocalRuntimeMetrics(state?.profile?.modelUid,(state?.artifactBytes?:0L)+kv,kv,state?.backend,LocalThermalState.UNKNOWN,active.size)
    }
    private fun saturatedMultiply(a:Long,b:Long)=if(a==0L||b<=Long.MAX_VALUE/a)a*b else Long.MAX_VALUE
}

class LocalAiPort(
    profile:LocalModelProfile,
    settings:LocalModelSettings,
    runtime:LocalInferenceRuntime,
    artifacts:LocalModelArtifactStore,
    device:()->LocalDeviceCapabilities,
    codec:AiStructuredCodec,
    admissionController:LocalModelAdmissionController=LocalModelAdmissionController()
):AiProvider by TransportAiProviderAdapter(
    capabilities=AiCapabilityContract(
        "LOCAL:${runtime.capabilities.runtimeUid}:${profile.modelUid}","LOCAL:${runtime.capabilities.runtimeUid}",profile.modelUid,
        profile.supportedWorkloads,supportsStreaming=runtime.capabilities.supportsStreaming,
        maximumContextUnits=minOf(profile.maximumContextUnits,settings.contextUnits),providerKind=AiProviderKind.LOCAL
    ),
    transport=LocalRuntimeTransport(profile,settings,runtime,artifacts,device,admissionController),
    codec=codec,
    maximumOutputUnits=(settings.contextUnits/2).coerceIn(256,1_024),
    cancellationHook=runtime::cancel
)

private class LocalRuntimeTransport(
    private val profile:LocalModelProfile,
    private val settings:LocalModelSettings,
    private val runtime:LocalInferenceRuntime,
    private val artifacts:LocalModelArtifactStore,
    private val device:()->LocalDeviceCapabilities,
    private val admissionController:LocalModelAdmissionController
):AiStructuredTransport{
    override fun execute(request:AiTransportRequest,cancellation:AiCancellationSignal):AiProviderResult<AiTransportResponse>{
        val artifact=artifacts.find(profile.modelUid,settings.variantUid)
            ?:return AiProviderResult.Failure(AiProviderFailureKind.UNAVAILABLE,"LOCAL_MODEL_ARTIFACT_MISSING")
        val admission=admissionController.evaluate(profile,settings,runtime.capabilities,device())
        if(admission is LocalAdmissionResult.Rejected)return AiProviderResult.Failure(AiProviderFailureKind.UNAVAILABLE,"LOCAL_ADMISSION:${admission.reasonUids.joinToString(",")}")
        return try{
            runtime.load(profile,settings,artifact,admission as LocalAdmissionResult.Admitted)
            val output=runtime.generate(LocalGenerationRequest(request.requestUid,request.workload,request.payload,request.maximumOutputUnits,profile,settings,artifact),cancellation)
            AiProviderResult.Success(AiTransportResponse(request.requestUid,output.structuredPayload,output.traceUid),"LOCAL-RUNTIME",profile.modelUid,output.traceUid)
        }catch(failure:OutOfMemoryError){
            runtime.unload(profile.modelUid);AiProviderResult.Failure(AiProviderFailureKind.UNAVAILABLE,"LOCAL_OOM",true)
        }catch(failure:AiTransportException){
            AiProviderResult.Failure(AiProviderFailureKind.UNAVAILABLE,failure.reasonUid,failure.retryable)
        }
    }
}

enum class CloudAuthState { DISCONNECTED, CONNECTING, CONNECTED, EXPIRED, ERROR }
data class CloudConnectionStatus(val providerUid:String,val state:CloudAuthState,val accountUid:String?=null,val reasonUid:String?=null)
data class CloudPkceAuthorization(val authorizationUrl:String,val callbackUrl:String,val verifierHandleUid:String)
data class CloudAuthCallback(val callbackUrl:String,val authorizationCode:String)

interface CloudAuthPort{
    val providerUid:String
    fun status():CloudConnectionStatus
    fun beginConnect():CloudPkceAuthorization
    fun complete(callback:CloudAuthCallback):CloudConnectionStatus
    fun accessCredential():CharArray?
    fun disconnect()
}

interface SecretStore{
    fun put(secretUid:String,value:CharArray)
    fun get(secretUid:String):CharArray?
    fun remove(secretUid:String)
}

fun interface OpenRouterCodeExchange{
    /** Returns API key + optional user uid. The verifier is never logged or persisted in campaign state. */
    fun exchange(code:String,verifier:String):Pair<CharArray,String?>
}

data class OpenRouterCallbackEndpoint(val callbackUrl:String,val verifierHandleUid:String)
fun interface OpenRouterCallbackEndpointFactory{fun create(nonce:String):OpenRouterCallbackEndpoint}

class OpenRouterPkceAuthPort(
    private val secretStore:SecretStore,
    private val callbacks:OpenRouterCallbackEndpointFactory,
    private val exchange:OpenRouterCodeExchange,
    private val random:SecureRandom=SecureRandom()
):CloudAuthPort{
    override val providerUid="OPENROUTER"
    private data class Pending(val callbackUrl:String,val verifier:String,val handle:String)
    private val pending=AtomicReference<Pending?>(null)
    private val account=AtomicReference<String?>(null)
    override fun status():CloudConnectionStatus{
        val credentialPresent=try{
            secretStore.get(CREDENTIAL_UID)?.let{credential->credential.fill('\u0000');true}?:false
        }catch(_:Throwable){
            return CloudConnectionStatus(providerUid,CloudAuthState.ERROR,reasonUid="OPENROUTER_CREDENTIAL_STORAGE_UNAVAILABLE")
        }
        return when{
        credentialPresent->CloudConnectionStatus(providerUid,CloudAuthState.CONNECTED,account.get())
        pending.get()!=null->CloudConnectionStatus(providerUid,CloudAuthState.CONNECTING)
        else->CloudConnectionStatus(providerUid,CloudAuthState.DISCONNECTED)
        }
    }
    override fun beginConnect():CloudPkceAuthorization{
        val verifier=base64Url(ByteArray(64).also(random::nextBytes))
        val challenge=base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val nonce=base64Url(ByteArray(24).also(random::nextBytes))
        val endpoint=callbacks.create(nonce)
        pending.set(Pending(endpoint.callbackUrl,verifier,endpoint.verifierHandleUid))
        val url="https://openrouter.ai/auth?callback_url=${urlEncode(endpoint.callbackUrl)}&code_challenge=$challenge&code_challenge_method=S256"
        return CloudPkceAuthorization(url,endpoint.callbackUrl,endpoint.verifierHandleUid)
    }
    override fun complete(callback:CloudAuthCallback):CloudConnectionStatus{
        val state=pending.getAndSet(null)?:return CloudConnectionStatus(providerUid,CloudAuthState.ERROR,reasonUid="NO_PENDING_PKCE")
        if(callback.callbackUrl.substringBefore('?')!=state.callbackUrl.substringBefore('?'))return CloudConnectionStatus(providerUid,CloudAuthState.ERROR,reasonUid="CALLBACK_IDENTITY_MISMATCH")
        val exchanged=try{
            exchange.exchange(callback.authorizationCode,state.verifier)
        }catch(failure:AiTransportException){
            return CloudConnectionStatus(providerUid,CloudAuthState.ERROR,reasonUid=failure.reasonUid)
        }catch(_:Throwable){
            return CloudConnectionStatus(providerUid,CloudAuthState.ERROR,reasonUid="OPENROUTER_AUTH_EXCHANGE_UNEXPECTED")
        }
        val (key,user)=exchanged
        return try{
            if(key.isEmpty())return CloudConnectionStatus(providerUid,CloudAuthState.ERROR,reasonUid="OPENROUTER_AUTH_RESPONSE_INVALID")
            if(!persistCredential(key)){
                return CloudConnectionStatus(providerUid,CloudAuthState.ERROR,reasonUid="OPENROUTER_CREDENTIAL_STORAGE_FAILED")
            }
            account.set(user)
            CloudConnectionStatus(providerUid,CloudAuthState.CONNECTED,user)
        }finally{key.fill('\u0000')}
    }
    fun connectWithCredential(credential:CharArray):CloudConnectionStatus{
        pending.set(null)
        return try{
            if(credential.size<20||!credential.concatToString().startsWith("sk-or-")){
                return CloudConnectionStatus(providerUid,CloudAuthState.ERROR,reasonUid="MANUAL_API_KEY_REJECTED")
            }
            if(!persistCredential(credential)){
                return CloudConnectionStatus(providerUid,CloudAuthState.ERROR,reasonUid="OPENROUTER_CREDENTIAL_STORAGE_FAILED")
            }
            account.set(null)
            CloudConnectionStatus(providerUid,CloudAuthState.CONNECTED)
        }finally{credential.fill('\u0000')}
    }
    override fun accessCredential()=secretStore.get(CREDENTIAL_UID)
    override fun disconnect(){secretStore.remove(CREDENTIAL_UID);pending.set(null);account.set(null)}
    private fun persistCredential(value:CharArray):Boolean{
        return try{
            secretStore.put(CREDENTIAL_UID,value)
            val stored=secretStore.get(CREDENTIAL_UID)
            try{stored!=null&&constantTimeEquals(value,stored)}finally{stored?.fill('\u0000')}
        }catch(_:Throwable){
            false
        }.also{stored->if(!stored)runCatching{secretStore.remove(CREDENTIAL_UID)}}
    }
    private fun constantTimeEquals(first:CharArray,second:CharArray):Boolean{
        var difference=first.size xor second.size
        val count=maxOf(first.size,second.size)
        for(index in 0 until count){
            val left=if(index<first.size)first[index].code else 0
            val right=if(index<second.size)second[index].code else 0
            difference=difference or (left xor right)
        }
        return difference==0
    }
    private fun base64Url(value:ByteArray)=Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    private fun urlEncode(value:String)=java.net.URLEncoder.encode(value,Charsets.UTF_8.name())
    companion object{private const val CREDENTIAL_UID="openrouter.user-controlled-api-key"}
}

data class CloudModelProfile(
    val providerUid:String,val modelUid:String,val displayName:String,val maximumContextUnits:Int,
    val supportedWorkloads:Set<AiWorkload>,val supportsStreaming:Boolean,val supportsStructuredOutput:Boolean
){init{require(providerUid.isNotBlank()&&modelUid.isNotBlank()&&displayName.isNotBlank()&&maximumContextUnits>0&&supportedWorkloads.isNotEmpty())}}

data class CloudUsage(val inputUnits:Int,val outputUnits:Int,val costMicroUsd:Long?=null)
data class CloudInferenceResponse(val structuredPayload:String,val traceUid:String,val usage:CloudUsage,val rateLimitRemaining:Int?=null)

interface CloudInferenceClient{
    fun discoverModels(credential:CharArray):List<CloudModelProfile>
    fun execute(model:CloudModelProfile,credential:CharArray,request:AiTransportRequest,cancellation:AiCancellationSignal):CloudInferenceResponse
    fun cancel(requestUid:String)
}

class CloudAiPort(
    model:CloudModelProfile,
    auth:CloudAuthPort,
    client:CloudInferenceClient,
    codec:AiStructuredCodec
):AiProvider by TransportAiProviderAdapter(
    capabilities=AiCapabilityContract(
        "CLOUD:${model.providerUid}:${model.modelUid}",model.providerUid,model.modelUid,model.supportedWorkloads,
        supportsStreaming=model.supportsStreaming,maximumContextUnits=model.maximumContextUnits,
        providerKind=AiProviderKind.CLOUD,supportsJsonSchema=model.supportsStructuredOutput
    ),
    transport=CloudRuntimeTransport(model,auth,client),codec=codec,cancellationHook=client::cancel
)

private class CloudRuntimeTransport(
    private val model:CloudModelProfile,private val auth:CloudAuthPort,private val client:CloudInferenceClient
):AiStructuredTransport{
    override fun execute(request:AiTransportRequest,cancellation:AiCancellationSignal):AiProviderResult<AiTransportResponse>{
        val credential=auth.accessCredential()?:return AiProviderResult.Failure(AiProviderFailureKind.UNAVAILABLE,"CLOUD_NOT_CONNECTED")
        return try{
            val response=client.execute(model,credential,request,cancellation)
            AiProviderResult.Success(AiTransportResponse(request.requestUid,response.structuredPayload,response.traceUid),model.providerUid,model.modelUid,response.traceUid)
        }catch(failure:CloudRateLimitException){
            AiProviderResult.Failure(AiProviderFailureKind.UNAVAILABLE,"CLOUD_RATE_LIMIT",true)
        }catch(failure:AiTransportException){
            AiProviderResult.Failure(AiProviderFailureKind.UNAVAILABLE,failure.reasonUid,failure.retryable)
        }finally{credential.fill('\u0000')}
    }
}

class CloudRateLimitException:RuntimeException("CLOUD_RATE_LIMIT")
