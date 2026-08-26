package com.rpgos.app

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ChatTurnUiStage { IDLE, INTERPRETING, PLANNING, BUILDING_CONTEXT, GENERATING_PROPOSAL, VALIDATING, COMMITTING, NARRATING, COMMITTED_NARRATION_PENDING, COMPLETED, CLARIFICATION, FAILED, CANCELLED }
data class ChatTurnUiState(
    val stage:ChatTurnUiStage=ChatTurnUiStage.IDLE,val requestUid:String?=null,val statusText:String="Gotowy",
    val canCancel:Boolean=false,val canRetryNarration:Boolean=false,val committedOrder:Long?=null,val reasonUid:String?=null
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

object AiProviderCenterStateFactory{
    fun initial(settings:AiSystemConfiguration,artifactInstalled:Boolean,openRouter:CloudConnectionStatus):AiProviderCenterUiState{
        val profile=BielikLocalModelProfiles.BIELIK_4_5B_V3
        val local=settings.localModelSettings?:LocalRecommendedSettings.forProfile(profile)
        val localState=if(artifactInstalled)AiAvailabilityState.DEGRADED else AiAvailabilityState.NOT_CONFIGURED
        return AiProviderCenterUiState(
            settings.gameMaster,settings.director,listOf(
                AiModelOptionUi(AiModelSelection("LOCAL:ANDROID_NATIVE",profile.modelUid),profile.displayName,AiProviderKind.LOCAL,localState,
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

/** Application owner for provider setup. ViewModels receive typed results, never raw SDK/HTTP/runtime handles. */
class AndroidAiProviderCenterApplication(context:Context){
    private val app=context.applicationContext
    private val artifacts=AndroidLocalModelArtifactStore(app)
    private val http=OpenRouterHttpClient()
    private val callbacks=OpenRouterLoopbackCallbackServer()
    private val auth=OpenRouterPkceAuthPort(AndroidKeystoreSecretStore(app),callbacks,http)
    private val runtimeCapabilities=LocalRuntimeCapabilities(
        "ANDROID_NATIVE",setOf(LocalArtifactFormat.GGUF),
        setOf(LocalRuntimeBackend.AUTO,LocalRuntimeBackend.CPU,LocalRuntimeBackend.GPU),
        supportsContextTuning=true,supportsKvTuning=true,supportsThreads=true,supportsBatchPrefill=true,
        supportsCancellation=true,supportsStreaming=true
    )

    fun initialState(configuration:AiSystemConfiguration):AiProviderCenterUiState{
        val profile=BielikLocalModelProfiles.BIELIK_4_5B_V3
        val settings=configuration.localModelSettings?:LocalRecommendedSettings.forProfile(profile)
        val installed=artifacts.find(profile.modelUid,settings.variantUid)!=null
        return AiProviderCenterStateFactory.initial(configuration,installed,auth.status()).copy(
            localRuntimeAvailable=NativeLocalInferenceBridge.available,
            localAdmission=localAdmission(settings)
        )
    }

    fun onOpenRouterCallback(callback:(CloudAuthCallback)->Unit){callbacks.onCallback(callback)}

    fun localAdmission(settings:LocalModelSettings):LocalAdmissionResult = LocalModelAdmissionController().evaluate(
        BielikLocalModelProfiles.BIELIK_4_5B_V3,settings,runtimeCapabilities,AndroidLocalDeviceProbe.snapshot(app)
    )

    suspend fun importBielikArtifact(uri:Uri,settings:LocalModelSettings):LocalModelArtifact=withContext(Dispatchers.IO){
        val profile=BielikLocalModelProfiles.BIELIK_4_5B_V3
        require(settings.modelUid==profile.modelUid&&profile.variants.any{it.variantUid==settings.variantUid})
        val input=app.contentResolver.openInputStream(uri)?:error("Nie można otworzyć pliku modelu")
        artifacts.import(settings.modelUid,settings.variantUid,input)
    }

    fun beginOpenRouterConnect():CloudPkceAuthorization=auth.beginConnect()
    fun openRouterStatus():CloudConnectionStatus=auth.status()
    fun disconnectOpenRouter():CloudConnectionStatus{auth.disconnect();return auth.status()}

    suspend fun completeOpenRouter(callback:CloudAuthCallback):OpenRouterConnectionResult=withContext(Dispatchers.IO){
        val status=auth.complete(callback)
        val models=if(status.state==CloudAuthState.CONNECTED){
            val credential=auth.accessCredential()
            try{if(credential==null)emptyList() else http.discoverModels(credential)}finally{credential?.fill('\u0000')}
        }else emptyList()
        OpenRouterConnectionResult(status,models)
    }
}
