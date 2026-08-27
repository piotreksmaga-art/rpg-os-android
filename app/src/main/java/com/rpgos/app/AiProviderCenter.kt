package com.rpgos.app

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

enum class ChatTurnUiStage { IDLE, INTERPRETING, PLANNING, BUILDING_CONTEXT, GENERATING_PROPOSAL, VALIDATING, COMMITTING, NARRATING, COMMITTED_NARRATION_PENDING, COMPLETED, CLARIFICATION, FAILED, CANCELLED }
data class ChatTurnUiState(
    val stage:ChatTurnUiStage=ChatTurnUiStage.IDLE,val requestUid:String?=null,val statusText:String="Gotowy",
    val canCancel:Boolean=false,val canRetryNarration:Boolean=false,val canConfirmCharacterCreation:Boolean=false,
    val committedOrder:Long?=null,val reasonUid:String?=null
)

data class AiModelOptionUi(
    val selection:AiModelSelection,val label:String,val providerKind:AiProviderKind,val availability:AiAvailabilityState,val reasonUid:String
)

data class AiProviderCenterUiState(
    val gameMasterAssignment:AiRoleAssignment,
    val directorAssignment:AiRoleAssignment,
    val modelOptions:List<AiModelOptionUi>,
    val openRouterStatus:CloudConnectionStatus,
    val localProfile:LocalModelProfile,
    val localSettings:LocalModelSettings,
    val localArtifactInstalled:Boolean,
    val localRuntimeAvailable:Boolean,
    val localAdmission:LocalAdmissionResult?,
    val privacy:AiPrivacyPolicy,
    val directorStatusText:String,
    val lastDirectorCommittedOrder:Long?=null
)

fun AiProviderCenterUiState.reconcileLocalAvailability(
    artifactInstalled:Boolean=localArtifactInstalled,
    runtimeAvailable:Boolean=localRuntimeAvailable,
    admission:LocalAdmissionResult?=localAdmission
):AiProviderCenterUiState{
    val availability=when{
        !artifactInstalled->AiAvailabilityState.NOT_CONFIGURED
        !runtimeAvailable->AiAvailabilityState.UNAVAILABLE
        admission is LocalAdmissionResult.Admitted->AiAvailabilityState.READY
        admission is LocalAdmissionResult.Rejected->AiAvailabilityState.UNAVAILABLE
        else->AiAvailabilityState.DEGRADED
    }
    val reason=when{
        !artifactInstalled->"MODEL_ARTIFACT_REQUIRED"
        !runtimeAvailable->"LOCAL_RUNTIME_UNAVAILABLE"
        admission is LocalAdmissionResult.Admitted->"LOCAL_MODEL_READY"
        admission is LocalAdmissionResult.Rejected->"LOCAL_ADMISSION:${admission.reasonUids.joinToString(",")}"
        else->"MODEL_INSTALLED_RUNTIME_CHECK_PENDING"
    }
    return copy(
        localArtifactInstalled=artifactInstalled,
        localRuntimeAvailable=runtimeAvailable,
        localAdmission=admission,
        modelOptions=modelOptions.map{option->
            if(option.providerKind==AiProviderKind.LOCAL)option.copy(availability=availability,reasonUid=reason) else option
        }
    )
}

object AiProviderCenterStateFactory{
    fun initial(settings:AiSystemConfiguration,artifactInstalled:Boolean,openRouter:CloudConnectionStatus,profile:LocalModelProfile=BielikLocalModelProfiles.DEFAULT_ANDROID):AiProviderCenterUiState{
        val local=settings.localModelSettings?:LocalRecommendedSettings.forProfile(profile)
        val localState=if(artifactInstalled)AiAvailabilityState.DEGRADED else AiAvailabilityState.NOT_CONFIGURED
        return AiProviderCenterUiState(
            settings.gameMaster,settings.director,listOf(
                AiModelOptionUi(AiModelSelection("LOCAL:ANDROID_EXECUTORCH_1_3",profile.modelUid),profile.displayName,AiProviderKind.LOCAL,localState,
                    if(artifactInstalled)"MODEL_INSTALLED_RUNTIME_CHECK_PENDING" else "MODEL_ARTIFACT_REQUIRED")
            ),openRouter,profile,local,artifactInstalled,false,null,settings.privacy,"Director oczekuje na pierwszy okresowy trigger"
        )
    }
}

/** UI talks only to this application-level port, never to an AI SDK, DB, mechanics or commit. */
interface AiProviderCenterActions{
    fun assign(role:AiRole,assignment:AiRoleAssignment)
    fun updatePrivacy(policy:AiPrivacyPolicy)
    fun updateLocalSettings(settings:LocalModelSettings)
    fun resetLocalSettings()
    fun beginOpenRouterConnect():String
    fun disconnectOpenRouter()
}

data class OpenRouterConnectionResult(
    val status:CloudConnectionStatus,
    val models:List<CloudModelProfile>
)

fun openRouterFailureMessagePl(reasonUid:String?)=when(reasonUid){
    "OPENROUTER_AUTH_HTTP_400","OPENROUTER_AUTH_HTTP_403"->"OpenRouter odrzucił kod logowania. Rozpocznij połączenie ponownie albo użyj własnego klucza API."
    "OPENROUTER_AUTH_HTTP_405"->"OpenRouter odrzucił metodę wymiany kodu. Zaktualizuj aplikację do najnowszej wersji."
    "OPENROUTER_AUTH_HTTP_408","OPENROUTER_AUTH_HTTP_429"->"OpenRouter jest chwilowo zajęty. Odczekaj moment i spróbuj ponownie."
    "OPENROUTER_AUTH_NETWORK_IO"->"Telefon nie zdołał połączyć się z endpointem OpenRouter. Sprawdź Internet, VPN i prywatny DNS."
    "OPENROUTER_AUTH_RESPONSE_INVALID","OPENROUTER_AUTH_CLIENT_FAILURE","OPENROUTER_AUTH_EXCHANGE_UNEXPECTED"->"OpenRouter zwrócił odpowiedź, której aplikacja nie mogła bezpiecznie przyjąć."
    "OPENROUTER_CREDENTIAL_STORAGE_FAILED","OPENROUTER_CREDENTIAL_STORAGE_UNAVAILABLE"->"Android nie zdołał bezpiecznie zapisać klucza. Ponowienie naprawi nieaktualny wpis Keystore; jeśli błąd wróci, uruchom ponownie telefon."
    "OPENROUTER_MODELS_HTTP_401","OPENROUTER_MODELS_HTTP_403"->"Klucz API został odrzucony przez OpenRouter."
    "MANUAL_API_KEY_FORMAT_INVALID","MANUAL_API_KEY_REJECTED"->"Klucz API ma nieprawidłowy format."
    "CALLBACK_IDENTITY_MISMATCH","NO_PENDING_PKCE"->"Sesja logowania wygasła. Rozpocznij połączenie ponownie."
    null->"Połączenie nie powiodło się. Spróbuj ponownie."
    else->if(reasonUid.startsWith("OPENROUTER_AUTH_HTTP_5"))"OpenRouter ma chwilowy błąd serwera. Spróbuj ponownie później." else "Połączenie nie powiodło się."
}

/** Application owner for provider setup. ViewModels receive typed results, never raw SDK/HTTP/runtime handles. */
class AndroidAiProviderCenterApplication(context:Context){
    private val app=context.applicationContext
    private val artifacts=AndroidLocalModelArtifactStore(app)
    private val http=OpenRouterHttpClient()
    private val callbacks=OpenRouterLoopbackCallbackServer()
    private val auth=OpenRouterPkceAuthPort(AndroidKeystoreSecretStore(app),callbacks,http)
    private val runtimeCapabilities=LocalRuntimeCapabilities(
        "ANDROID_EXECUTORCH_1_3",setOf(LocalArtifactFormat.EXECUTORCH),
        setOf(LocalRuntimeBackend.AUTO,LocalRuntimeBackend.CPU),
        supportsContextTuning=true,supportsKvTuning=false,supportsThreads=false,supportsBatchPrefill=false,
        supportsCancellation=true,supportsStreaming=true
    )
    private val discoveredCloudModels=ConcurrentHashMap<String,CloudModelProfile>()
    private val localRuntime by lazy{DriverBackedLocalInferenceRuntime(runtimeCapabilities,ExecuTorchLocalInferenceDriver())}

    fun initialState(configuration:AiSystemConfiguration):AiProviderCenterUiState{
        val profile=profileFor(configuration.localModelSettings?.modelUid)
        val settings=configuration.localModelSettings?:LocalRecommendedSettings.forProfile(profile)
        val installed=artifacts.find(profile.modelUid,settings.variantUid)!=null
        return AiProviderCenterStateFactory.initial(configuration,installed,auth.status(),profile)
            .reconcileLocalAvailability(installed,true,localAdmission(settings))
    }

    fun onOpenRouterCallback(callback:(OpenRouterConnectionResult)->Unit){
        callbacks.onCallback{authCallback->
            val result=completeOpenRouterBlocking(authCallback)
            callback(result)
            result.status
        }
    }

    fun localAdmission(settings:LocalModelSettings):LocalAdmissionResult = LocalModelAdmissionController().evaluate(
        profileFor(settings.modelUid),settings,runtimeCapabilities,AndroidLocalDeviceProbe.snapshot(app).copy(availableBackends=setOf(LocalRuntimeBackend.CPU))
    )

    suspend fun importBielikArtifact(uri:Uri,settings:LocalModelSettings):LocalModelArtifact=withContext(Dispatchers.IO){
        val profile=profileFor(settings.modelUid)
        val variant=profile.variants.singleOrNull{it.variantUid==settings.variantUid}
        require(settings.modelUid==profile.modelUid&&variant!=null)
        val input=app.contentResolver.openInputStream(uri)?:error("Nie można otworzyć pliku modelu")
        val artifact=artifacts.import(settings.modelUid,settings.variantUid,input)
        val sizeMatches=variant.sha256==null||artifact.byteSize==variant.expectedBytes
        val digestMatches=variant.sha256?.equals(artifact.sha256,ignoreCase=true)?:true
        if(!sizeMatches||!digestMatches){
            artifacts.remove(settings.modelUid,settings.variantUid)
            error("Pakiet modelu nie odpowiada oficjalnemu profilowi RPG OS (rozmiar lub suma SHA-256).")
        }
        artifact
    }

    fun beginOpenRouterConnect():CloudPkceAuthorization=auth.beginConnect()
    fun openRouterStatus():CloudConnectionStatus=auth.status()
    fun disconnectOpenRouter():CloudConnectionStatus{auth.disconnect();return auth.status()}

    suspend fun completeOpenRouter(callback:CloudAuthCallback):OpenRouterConnectionResult=withContext(Dispatchers.IO){
        completeOpenRouterBlocking(callback)
    }

    private fun completeOpenRouterBlocking(callback:CloudAuthCallback):OpenRouterConnectionResult{
        val status=auth.complete(callback)
        val models=if(status.state==CloudAuthState.CONNECTED){
            val credential=auth.accessCredential()
            try{if(credential==null)emptyList() else runCatching{http.discoverModels(credential)}.getOrDefault(emptyList())}finally{credential?.fill('\u0000')}
        }else emptyList()
        models.forEach{discoveredCloudModels[it.modelUid]=it}
        return OpenRouterConnectionResult(status,models)
    }

    suspend fun connectOpenRouterWithApiKey(apiKey:CharArray):OpenRouterConnectionResult=withContext(Dispatchers.IO){
        try{
            if(apiKey.size<20||!apiKey.concatToString().startsWith("sk-or-")){
                return@withContext OpenRouterConnectionResult(
                    CloudConnectionStatus("OPENROUTER",CloudAuthState.ERROR,reasonUid="MANUAL_API_KEY_FORMAT_INVALID"),emptyList()
                )
            }
            val models=http.discoverModels(apiKey)
            val status=auth.connectWithCredential(apiKey.copyOf())
            models.forEach{discoveredCloudModels[it.modelUid]=it}
            OpenRouterConnectionResult(status,models)
        }catch(failure:AiTransportException){
            OpenRouterConnectionResult(CloudConnectionStatus("OPENROUTER",CloudAuthState.ERROR,reasonUid=failure.reasonUid),emptyList())
        }catch(_:Throwable){
            OpenRouterConnectionResult(CloudConnectionStatus("OPENROUTER",CloudAuthState.ERROR,reasonUid="MANUAL_API_KEY_VALIDATION_FAILED"),emptyList())
        }finally{apiKey.fill('\u0000')}
    }

    /** Builds provider adapters only; Chat/UI never sees an SDK or runtime handle. */
    fun provider(selection:AiModelSelection,configuration:AiSystemConfiguration):AiProvider?{
        return when(selection.providerUid){
            "LOCAL:ANDROID_EXECUTORCH_1_3","LOCAL:ANDROID_NATIVE"->{
                val profile=profileFor(selection.modelUid)
                if(selection.modelUid!=profile.modelUid)return null
                val settings=configuration.localModelSettings?:LocalRecommendedSettings.forProfile(profile)
                if(settings.modelUid!=profile.modelUid||profile.variants.none{it.variantUid==settings.variantUid})return null
                LocalAiPort(profile,settings,localRuntime,artifacts,{AndroidLocalDeviceProbe.snapshot(app).copy(availableBackends=setOf(LocalRuntimeBackend.CPU))},CanonicalAiJsonCodec())
            }
            "OPENROUTER"->discoveredCloudModels[selection.modelUid]?.let{CloudAiPort(it,auth,http,CanonicalAiJsonCodec())}
            else->null
        }
    }

    fun availableProviders(configuration:AiSystemConfiguration):List<AiProvider>{
        if(discoveredCloudModels.isEmpty()&&auth.status().state==CloudAuthState.CONNECTED){
            val credential=auth.accessCredential()
            try{
                if(credential!=null)runCatching{http.discoverModels(credential)}.getOrDefault(emptyList()).forEach{discoveredCloudModels[it.modelUid]=it}
            }finally{credential?.fill('\u0000')}
        }
        val profile=profileFor(configuration.localModelSettings?.modelUid)
        val local=provider(AiModelSelection("LOCAL:ANDROID_EXECUTORCH_1_3",profile.modelUid),configuration)
        val cloud=discoveredCloudModels.values.sortedBy{it.modelUid}.mapNotNull{provider(AiModelSelection("OPENROUTER",it.modelUid),configuration)}
        return listOfNotNull(local)+cloud
    }

    private fun profileFor(modelUid:String?):LocalModelProfile=
        BielikLocalModelProfiles.byModelUid(modelUid)?:BielikLocalModelProfiles.DEFAULT_ANDROID
}
