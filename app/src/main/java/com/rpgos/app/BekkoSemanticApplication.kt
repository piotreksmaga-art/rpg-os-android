package com.rpgos.app

import android.content.Context
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/** Process-wide hand-off used by every repository instance (UI, Bridge and Director). A campaign
 * selection is process-wide too, so every semantic worker must stop before that pointer changes. */
internal object SemanticCampaignTransitionRegistry{
    private val listeners=CopyOnWriteArrayList<WeakReference<()->Unit>>()
    fun register(listener:()->Unit){listeners+=WeakReference(listener)}
    fun unregister(listener:()->Unit){listeners.removeIf{reference->reference.get().let{it==null||it===listener}}}
    fun beforeCampaignStorageTransition(){
        listeners.toList().forEach{reference->
            val listener=reference.get()
            if(listener==null)listeners.remove(reference) else runCatching(listener)
        }
    }
}

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
    private val campaignTransitionListener:()->Unit={synchronized(this){closeRuntime()}}

    init{SemanticCampaignTransitionRegistry.register(campaignTransitionListener)}

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

    /**
     * Ranks only already-authorized World Pack definitions. The returned catalog remains a
     * projection of the caller's authoritative catalog; Bekko can neither invent nor select a
     * canonical value. Any runtime/index failure falls back to the existing lexical projection.
     */
    fun characterCreationCatalogProjection():CharacterCreationCatalogProjectionPort=
        CharacterCreationCatalogProjectionPort{catalog,conversation->rankCharacterCreationCatalog(catalog,conversation)}

    /** Semantic candidates for gameplay references are restricted to the authoritative catalog
     * and returned only as typed UIDs.  Core performs the final resolution; when ranking is not
     * decisive this deliberately returns several candidates and the existing ambiguity path wins. */
    fun gameplayReferenceCandidates():SemanticWorldPackReferenceCandidatePort=
        SemanticWorldPackReferenceCandidatePort{campaign,reference,consumers->
            rankGameplayReference(campaign,reference,consumers)
        }

    @Synchronized private fun rankGameplayReference(
        campaignUid:String,
        reference:IntentReference,
        consumerNodes:List<IntentNode>
    ):List<DomainRef>{
        if(!settings.enabled||campaignUid!=repository.activeCampaignRef().campaignId)return emptyList()
        val allowedKinds=semanticDefinitionKinds(reference)
        if(allowedKinds.isEmpty())return emptyList()
        val query=listOfNotNull(reference.rawPhrase,reference.descriptorHints["surface"],reference.descriptorHints["category"])
            .joinToString(" ").trim().take(512)
        if(query.isEmpty())return emptyList()
        return runCatching{
            val active=runtime()
            if(!active.coordinator.readyForQueries()||active.provider.availability().state!=EmbeddingAvailabilityState.READY)return@runCatching emptyList()
            val options=repository.characterCreationCatalog().options.filter{it.kind in allowedKinds}
            if(options.isEmpty())return@runCatching emptyList()
            val byRecord=options.associateBy(::semanticWorldPackRecordUid)
            val authorized=active.index.authorizedRecordUids(
                campaignUid,SEMANTIC_NAMESPACE_WORLD_PACK,AudienceKinds.PLAYER,
                VisibilityPurposeKinds.GAMEPLAY_NARRATION,Long.MAX_VALUE
            ).intersect(byRecord.keys)
            if(authorized.isEmpty())return@runCatching emptyList()
            val embedded=active.provider.embedBatch(EmbeddingRequest(
                "BEKKO-GAMEPLAY-REFERENCE:${semanticSha256("$campaignUid|$query").take(24)}",listOf(query),256
            )) as? EmbeddingBatchResult.Success?:return@runCatching emptyList()
            val vector=matryoshkaL2(embedded.vectors.single(),active.index.version.dimensions)
            val ranked=active.index.searchAuthorized(SemanticSearchRequest(
                campaignUid,SEMANTIC_NAMESPACE_WORLD_PACK,AudienceKinds.PLAYER,
                VisibilityPurposeKinds.GAMEPLAY_NARRATION,Long.MAX_VALUE,authorized,
                queryVector=vector,topK=minOf(5,authorized.size),minimumScore=0.25f
            )).mapNotNull{candidate->byRecord[candidate.canonicalRecordUid]?.let{Triple(it,candidate.score,candidate.canonicalRecordUid)}}
            if(ranked.isEmpty())return@runCatching emptyList()
            // Ownership is a preference, not a prerequisite for semantic lookup. A transient
            // protected-read/SQLite failure must not discard a valid authorized ranking.
            val ownedTechniques=runCatching{repository.infrastructurePlayerTechniqueUids()}.getOrDefault(emptySet())
            val ownedSkills=runCatching{repository.infrastructurePlayerSkillUids()}.getOrDefault(emptySet())
            val owned=ranked.filter{(option,_,_)->
                (option.kind==CharacterCreationDefinitionKind.TECHNIQUE&&option.definitionUid in ownedTechniques)||
                    (option.kind==CharacterCreationDefinitionKind.SKILL&&option.definitionUid in ownedSkills)
            }
            val selected=when{
                owned.size==1->owned
                owned.size>1->owned.takeWhile{it.second>=owned.first().second-0.02f}
                ranked.size==1||ranked[0].second>=0.65f||ranked[0].second-ranked[1].second>=0.04f->ranked.take(1)
                else->ranked.takeWhile{it.second>=ranked.first().second-0.02f}
            }
            selected.map{(option,_,_)->DomainRef(semanticDomainKind(option.kind),option.definitionUid)}.distinct()
        }.getOrDefault(emptyList())
    }

    private fun semanticDefinitionKinds(reference:IntentReference):Set<CharacterCreationDefinitionKind>{
        val token=(reference.semanticTypeHints+reference.descriptorHints.values+listOfNotNull(reference.rawPhrase))
            .joinToString(" ").let(::normalizedWorldToken)
        return buildSet{
            if("TECHNI" in token)add(CharacterCreationDefinitionKind.TECHNIQUE)
            if("SKILL" in token||"UMIEJ" in token)add(CharacterCreationDefinitionKind.SKILL)
            if("STAT" in token||"STATYST" in token)add(CharacterCreationDefinitionKind.STAT)
            if("RESOURCE" in token||"ZASOB" in token||"ZASÓB" in token)add(CharacterCreationDefinitionKind.RESOURCE)
            if("TALENT" in token)add(CharacterCreationDefinitionKind.TALENT)
            if("POTENTIAL" in token||"POTENCJ" in token)add(CharacterCreationDefinitionKind.POTENTIAL)
            if("ORIGIN" in token||"POCHODZEN" in token||"CLAN" in token||"KLAN" in token)add(CharacterCreationDefinitionKind.ORIGIN)
            if("INNATE" in token||"KEKKEI" in token||"GENKAI" in token||"WRODZON" in token)add(CharacterCreationDefinitionKind.INNATE_FEATURE)
            if("STARTING_LOCATION" in token||"LOKACJA_STARTOWA" in token)add(CharacterCreationDefinitionKind.STARTING_LOCATION)
        }
    }

    private fun semanticDomainKind(kind:CharacterCreationDefinitionKind)=when(kind){
        CharacterCreationDefinitionKind.STARTING_LOCATION->"LOCATION"
        CharacterCreationDefinitionKind.INNATE_FEATURE->"INNATE_FEATURE"
        else->kind.name
    }

    @Synchronized private fun rankCharacterCreationCatalog(
        catalog:CharacterCreationCatalog,
        conversation:List<CharacterCreationConversationEntry>
    ):CharacterCreationCatalog{
        val latestPlayer=conversation.lastOrNull{it.role==CharacterCreationConversationRole.PLAYER}?.text.orEmpty()
        val catalogQuestion=catalog.answerCatalogQuestion(latestPlayer)!=null
        fun project(semanticOrder:List<String> = emptyList())=catalog.projectForAi(
            conversation,
            maximumEstimatedInputUnits=if(catalogQuestion)5_000 else 900,
            semanticOrder=semanticOrder,
            maximumOptionsPerOptionalKind=if(catalogQuestion)8 else 3
        )
        fun lexical()=project()
        if(!settings.enabled)return lexical()
        return runCatching{
            val query=conversation.asSequence().filter{it.role==CharacterCreationConversationRole.PLAYER}
                .map{it.text}.toList().takeLast(8).joinToString("\n").take(1_024).trim()
            if(query.isEmpty())return@runCatching lexical()
            val active=runtime()
            if(!active.coordinator.readyForQueries())return@runCatching lexical()
            if(active.provider.availability().state!=EmbeddingAvailabilityState.READY)return@runCatching lexical()
            val embedded=active.provider.embedBatch(EmbeddingRequest(
                "BEKKO-CHARACTER:${semanticSha256(query).take(24)}",listOf(query),256
            )) as? EmbeddingBatchResult.Success?:return@runCatching lexical()
            val queryVector=matryoshkaL2(embedded.vectors.single(),active.index.version.dimensions)
            val allowed=catalog.options.map(::semanticWorldPackRecordUid).toSet()
            val authorized=active.index.authorizedRecordUids(
                catalog.campaignUid,SEMANTIC_NAMESPACE_WORLD_PACK,AudienceKinds.PLAYER,
                VisibilityPurposeKinds.GAMEPLAY_NARRATION,Long.MAX_VALUE
            ).intersect(allowed)
            if(authorized.isEmpty())return@runCatching lexical()
            val ranked=active.index.searchAuthorized(SemanticSearchRequest(
                catalog.campaignUid,SEMANTIC_NAMESPACE_WORLD_PACK,AudienceKinds.PLAYER,
                VisibilityPurposeKinds.GAMEPLAY_NARRATION,Long.MAX_VALUE,authorized,
                queryVector=queryVector,topK=minOf(64,authorized.size),minimumScore=-1f
            )).map{it.canonicalRecordUid}
            project(ranked)
        }.getOrElse{lexical()}
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
            val hotTail=SemanticHotTailProvider(repository,active.projector)
            if(!active.coordinator.readyForQueries())return hotTail.retrieve(request)
            HybridSemanticStructuredQueryProvider(
                SemanticStructuredQueryProvider(active.provider,active.index),
                hotTail,active.index
            ).retrieve(request)
        }catch(failure:Throwable){
            StructuredRetrievalResult.Unsupported(typedReason("BEKKO_RETRIEVAL_FAILED",failure))
        }
    }

    @Synchronized private fun enrichDirector(trigger:DirectorTrigger,context:DirectorContextEnvelope):DirectorContextEnvelope{
        if(!settings.enabled)return context
        return try{
            val active=runtime()
            if(!active.coordinator.readyForQueries())return context
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
        val coordinator=ImmediateSemanticIndexCoordinator(repository,provider,index,projector,campaign,onProgress={update->
            progress=update;progressListener?.invoke()
        })
        return Runtime(campaign,settings.backend,provider,index,projector,coordinator).also{runtime=it}
    }

    @Synchronized private fun closeRuntime(){runtime?.close();runtime=null}
    override fun close(){
        SemanticCampaignTransitionRegistry.unregister(campaignTransitionListener)
        synchronized(this){closeRuntime()}
    }
}
