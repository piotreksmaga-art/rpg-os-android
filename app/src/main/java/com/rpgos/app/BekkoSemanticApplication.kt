package com.rpgos.app

import android.content.Context
import java.io.File

data class BekkoSemanticUiState(
    val settings:BekkoSettings=BekkoSettings(),
    val modelInstalled:Boolean=false,
    val downloading:Boolean=false,
    val downloadFraction:Float=0f,
    val availability:EmbeddingAvailability=EmbeddingAvailability(EmbeddingAvailabilityState.NOT_INSTALLED,"BEKKO_MODEL_NOT_INSTALLED"),
    val indexStatus:SemanticIndexStatus?=null,
    val indexProgress:SemanticIndexProgress=SemanticIndexProgress(),
    val notice:String?=null,
    val errorMessage:String?=null
)

class BekkoSemanticApplication(
    context:Context,
    private val repository:UnifiedGameRepository
):AutoCloseable{
    private val app=context.applicationContext
    private val settingsStore=BekkoSettingsStore(app)
    private val modelManager=BekkoModelManager(app)
    private val indexRoot=File(app.filesDir,"semantic-indexes").apply{mkdirs()}
    @Volatile private var settings=settingsStore.load()
    @Volatile private var runtime:Runtime?=null
    @Volatile private var progress=SemanticIndexProgress()
    @Volatile private var progressListener:(()->Unit)?=null

    private data class Runtime(
        val campaignUid:String,val backend:EmbeddingBackend,val provider:EmbeddingProviderPort,
        val index:SemanticIndexPort,val projector:SemanticDocumentProjector,val coordinator:ImmediateSemanticIndexCoordinator
    ):AutoCloseable{override fun close(){coordinator.close()}}

    @Synchronized fun settings():BekkoSettings=settings
    fun setProgressListener(listener:(()->Unit)?){progressListener=listener}
    fun modelInstalled()=modelManager.installed()

    @Synchronized fun updateSettings(value:BekkoSettings){
        if(value==settings)return
        settings=value;settingsStore.save(value);closeRuntime()
        if(value.enabled)runCatching{runtime().coordinator.onCampaignOpened()}
    }

    suspend fun download(onProgress:(BekkoDownloadProgress)->Unit={}):File{
        val file=modelManager.download(onProgress)
        synchronized(this){closeRuntime()}
        if(settings.enabled)runtime().coordinator.onCampaignOpened()
        return file
    }

    @Synchronized fun removeModelAndIndexes():Boolean{
        closeRuntime()
        val modelRemoved=modelManager.remove()
        val indexesRemoved=!indexRoot.exists()||indexRoot.deleteRecursively()
        indexRoot.mkdirs()
        return modelRemoved&&indexesRemoved
    }

    fun rebuild():SemanticIndexStatus{
        val active=runtime();active.index.clear(active.campaignUid);return active.coordinator.catchUp()
    }

    fun catchUp():SemanticIndexStatus=runtime().coordinator.catchUp()
    fun onCampaignOpened(){if(settings.enabled)runtime().coordinator.onCampaignOpened()}
    fun onCanonicalCommit(){if(settings.enabled)runtime().coordinator.onCanonicalCommit()}

    fun structuredBinding():StructuredProviderBinding{
        return StructuredProviderBinding(
            BEKKO_STRUCTURED_PROVIDER_UID,
            setOf(BEKKO_OPERATION_MEMORY,BEKKO_OPERATION_WORLD_PACK,BEKKO_OPERATION_RELATED),
            StructuredQueryProvider(::retrieveSemantic)
        )
    }

    fun directorScout():DirectorContextScoutPort=DirectorContextScoutPort{trigger,context->
        enrichDirector(trigger,context)
    }

    fun futureCandidatePorts():SemanticFutureCandidatePorts=SemanticFutureCandidatePorts.candidateOnly{request->
        val active=runtime()
        active.index.searchAuthorized(request)
    }

    fun state():BekkoSemanticUiState{
        if(!settings.enabled)return BekkoSemanticUiState(
            settings=settings,modelInstalled=modelManager.installed(),
            availability=EmbeddingAvailability(EmbeddingAvailabilityState.UNAVAILABLE,"BEKKO_DISABLED")
        )
        val active=runCatching{runtime()}.getOrNull()
        val availability=active?.provider?.availability()
            ?:EmbeddingAvailability(EmbeddingAvailabilityState.UNAVAILABLE,"BEKKO_RUNTIME_INITIALIZATION_FAILED")
        val indexStatus=active?.let{runCatching{it.index.status(it.campaignUid)}.getOrNull()}
        return BekkoSemanticUiState(settings,modelManager.installed(),availability=availability,indexStatus=indexStatus,indexProgress=progress)
    }

    @Synchronized private fun retrieveSemantic(request:StructuredRetrievalRequest):StructuredRetrievalResult{
        if(!settings.enabled)return StructuredRetrievalResult.Unsupported("BEKKO_DISABLED")
        return try{
            val active=runtime()
            HybridSemanticStructuredQueryProvider(
                SemanticStructuredQueryProvider(active.provider,active.index),
                SemanticHotTailProvider(repository,active.projector),active.index
            ).retrieve(request)
        }catch(failure:Throwable){
            StructuredRetrievalResult.Unsupported(typedReason("BEKKO_RETRIEVAL_FAILED",failure))
        }
    }

    @Synchronized private fun enrichDirector(trigger:DirectorTrigger,context:DirectorContextEnvelope):DirectorContextEnvelope{
        if(!settings.enabled)return context
        return try{
            val active=runtime()
            BekkoDirectorContextScout(active.provider,active.index).enrich(trigger,context)
        }catch(_:Throwable){context}
    }

    private fun typedReason(prefix:String,failure:Throwable):String{
        val message=failure.message?.takeIf{it.isNotBlank()}?.replace(Regex("[^A-Za-z0-9:_-]"),"_")?.take(120)
        return if(message==null)"$prefix:${failure::class.java.simpleName}" else "$prefix:$message"
    }

    @Synchronized private fun runtime():Runtime{
        val campaign=repository.activeCampaignRef().campaignId
        runtime?.takeIf{it.campaignUid==campaign&&it.backend==settings.backend}?.let{return it}
        closeRuntime()
        val provider=LlamaCppBekkoEmbeddingProvider(app,modelManager.modelFile(),settings.backend)
        val campaignSegment=semanticSha256(campaign).take(24)
        val index=FileSemanticIndex(File(indexRoot,campaignSegment))
        val projector=CommittedReplaySemanticProjector(activePlayerUid={repository.activePlayerRef()?.playerUid})
        val coordinator=ImmediateSemanticIndexCoordinator(repository,provider,index,projector,onProgress={update->
            progress=update;progressListener?.invoke()
        })
        return Runtime(campaign,settings.backend,provider,index,projector,coordinator).also{runtime=it}
    }

    @Synchronized private fun closeRuntime(){runtime?.close();runtime=null}
    override fun close(){synchronized(this){closeRuntime()}}
}
