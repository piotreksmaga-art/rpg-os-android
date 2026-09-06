package com.rpgos.app

import android.content.Context
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.UUID

/** Process-wide hand-off used by every repository instance (UI, Bridge and Director). A campaign
 * selection is process-wide too, so every semantic worker must stop before that pointer changes. */
internal object SemanticCampaignTransitionRegistry{
    private data class ListenerRegistration(
        val transition:WeakReference<()->Unit>,
        val cancellation:WeakReference<()->Unit>
    )
    /** Fairness plus the pending gate gives a storage/configuration writer priority over semantic
     * readers which arrive after the transition starts. Existing readers are cancelled before the
     * writer waits, so a native embedding call can leave its read lease instead of deadlocking the
     * only lifecycle path capable of cancelling it. */
    private val runtimeLock=ReentrantReadWriteLock(true)
    private val transitionSerial=ReentrantLock(true)
    private val pendingLock=ReentrantLock(true)
    private val transitionSettled=pendingLock.newCondition()
    private val transitionDepth=AtomicInteger(0)
    private val listeners=CopyOnWriteArrayList<ListenerRegistration>()

    fun registerAndReload(listener:()->Unit,cancellationListener:()->Unit,reload:()->Unit){
        transitionSerial.lock()
        try{
            val registration=ListenerRegistration(WeakReference(listener),WeakReference(cancellationListener))
            listeners+=registration
            try{reload()}catch(failure:Throwable){listeners.remove(registration);throw failure}
        }finally{transitionSerial.unlock()}
    }

    fun unregisterAndClose(
        listener:()->Unit,
        cancellationListener:()->Unit,
        close:()->Unit
    )=withExclusiveTransition(cancelAll=true,beforeWait={runCatching{cancellationListener()}}){
        // Closing one composition is a process transition: every pooled consumer is quiesced and
        // its rebuildable runtime is closed before the listener disappears. Surviving UI/Bridge/
        // Director instances lazily reopen under the same settings on their next operation.
        notifyAndCloseRuntimes()
        try{close()}finally{removeListener(listener)}
    }

    fun <T> withSemanticRuntimeAccess(block:()->T):T{
        // Nested runtime/index calls are common inside one higher-level semantic consumer lease.
        // They must inherit that lease even when a writer becomes pending in-between; otherwise
        // the thread would wait for a transition while still holding the read lock it needs.
        if(runtimeLock.isWriteLockedByCurrentThread||runtimeLock.readHoldCount>0)return withReadLock(block)
        while(true){
            awaitNoPendingTransition()
            val read=runtimeLock.readLock();read.lock()
            if(!transitionPending())return try{block()}finally{read.unlock()}
            read.unlock()
        }
    }

    fun <T> withSemanticConfigurationTransition(prepare:()->Unit,block:()->T):T=
        withExclusiveTransition(cancelAll=true){prepare();notifyAndCloseRuntimes();block()}

    fun <T> withCampaignStorageTransition(block:()->T):T=
        withExclusiveTransition(cancelAll=true){notifyAndCloseRuntimes();block()}

    private fun <T> withReadLock(block:()->T):T{
        val read=runtimeLock.readLock();read.lock()
        return try{block()}finally{read.unlock()}
    }

    private fun <T> withExclusiveTransition(
        cancelAll:Boolean,
        beforeWait:()->Unit={},
        block:()->T
    ):T{
        transitionSerial.lock()
        val outermost=transitionDepth.incrementAndGet()==1
        if(outermost)markTransitionPending()
        try{
            beforeWait()
            if(cancelAll)requestRuntimeCancellation()
            val write=runtimeLock.writeLock()
            // Cancellation may race with a reader which was constructing its runtime. Repeating
            // the signal while waiting also catches that late publication without allowing new
            // readers to barge in front of this writer.
            while(!write.tryLock(100,TimeUnit.MILLISECONDS)){
                beforeWait()
                if(cancelAll)requestRuntimeCancellation()
            }
            return try{block()}finally{write.unlock()}
        }finally{
            if(transitionDepth.decrementAndGet()==0)markTransitionSettled()
            transitionSerial.unlock()
        }
    }

    private fun awaitNoPendingTransition(){
        pendingLock.lock()
        try{while(transitionDepth.get()>0)transitionSettled.awaitUninterruptibly()}
        finally{pendingLock.unlock()}
    }
    private fun transitionPending():Boolean=transitionDepth.get()>0
    private fun markTransitionPending(){
        // transitionDepth is already visible; taking this lock closes the race with a reader that
        // has just checked the condition and is about to acquire the fair read lock.
        pendingLock.lock();pendingLock.unlock()
    }
    private fun markTransitionSettled(){
        pendingLock.lock()
        try{transitionSettled.signalAll()}finally{pendingLock.unlock()}
    }
    private fun requestRuntimeCancellation(){
        listeners.toList().forEach{registration->
            val cancellation=registration.cancellation.get()
            if(cancellation==null){
                if(registration.transition.get()==null)listeners.remove(registration)
            }else runCatching{cancellation()}
        }
    }
    private fun notifyAndCloseRuntimes(){
        listeners.toList().forEach{registration->
            val listener=registration.transition.get()
            if(listener==null)listeners.remove(registration) else listener()
        }
    }
    private fun removeListener(listener:()->Unit){
        listeners.removeIf{registration->registration.transition.get().let{it==null||it===listener}}
    }
}

/** Owns the rebuildable, per-campaign semantic sidecar location. Replacing canonical history must
 * invalidate this directory only after the active semantic runtime has been stopped. */
internal object SemanticSidecarStorage{
    fun root(context:Context)=File(context.applicationContext.filesDir,"semantic-indexes")
    fun campaignDirectory(context:Context,campaignUid:String):File{
        require(campaignUid.isNotBlank())
        return File(root(context),semanticSha256(campaignUid).take(24))
    }
    fun invalidateCampaign(context:Context,campaignUid:String){
        val campaignDirectory=campaignDirectory(context,campaignUid)
        val root=root(context).apply{mkdirs()}
        root.listFiles{file->file.name.startsWith(".${campaignDirectory.name}.obsolete-")}
            ?.forEach{obsolete->runCatching{obsolete.deleteRecursively()}}
        if(!campaignDirectory.exists())return
        val obsolete=File(root,".${campaignDirectory.name}.obsolete-${UUID.randomUUID()}")
        val detached=campaignDirectory.renameTo(obsolete)
        if(!detached){
            if(!campaignDirectory.deleteRecursively()||campaignDirectory.exists()){
                throw IllegalStateException("BEKKO_INDEX_INVALIDATION_FAILED:$campaignUid")
            }
            return
        }
        if(!obsolete.deleteRecursively()||obsolete.exists()){
            // The active path is already detached, so stale vectors cannot be reopened. Leave a
            // recognizable tombstone for the next cleanup attempt and surface the storage leak.
            throw IllegalStateException("BEKKO_INDEX_TOMBSTONE_DELETE_FAILED:$campaignUid")
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
    private val indexRoot=SemanticSidecarStorage.root(app).apply{mkdirs()}
    @Volatile private var settings=BekkoSettings()
    @Volatile private var runtime:Runtime?=null
    @Volatile private var progress=SemanticIndexProgress()
    @Volatile private var progressListener:(()->Unit)?=null
    private val closed=AtomicBoolean(false)
    private val closeStarted=AtomicBoolean(false)
    private val semanticCancellationListener:()->Unit={runtime?.coordinator?.requestCancellation()}
    private val campaignTransitionListener:()->Unit={synchronized(this){
        // Settings are process-wide. Every UI/Bridge/Director composition reloads them before
        // releasing its lease, so a CPU<->Vulkan switch cannot leave a second native model alive.
        settings=settingsStore.load()
        closeRuntime()
    }}
    private val memoryConsolidationListener:()->Unit={
        if(!closed.get()&&settings.enabled)runCatching{runtime().coordinator.onCanonicalCommit()}
    }
    private val semanticScopeChangeListener:()->Unit={
        if(!closed.get()){
            SemanticCampaignTransitionRegistry.withCampaignStorageTransition{
                val campaign=repository.activeCampaignRef().campaignId
                val enabledAfterReset=settings.enabled
                runCatching{SemanticSidecarStorage.invalidateCampaign(app,campaign)}
                    .onFailure{DiagnosticLogger.log(app,"PHASE59_SCOPE_INDEX_INVALIDATION_FAILED",it)}
                if(enabledAfterReset)runCatching{runtime().coordinator.onCampaignOpened()}
                    .onFailure{DiagnosticLogger.log(app,"PHASE59_SCOPE_REBUILD_FAILED",it)}
            }
        }
    }

    init{
        SemanticCampaignTransitionRegistry.registerAndReload(campaignTransitionListener,semanticCancellationListener){
            settings=settingsStore.load()
        }
        repository.configureMemoryConsolidationListener(memoryConsolidationListener)
        repository.configureSemanticScopeChangeListener(semanticScopeChangeListener)
    }

    private data class Runtime(
        val campaignUid:String,val backend:EmbeddingBackend,val provider:EmbeddingProviderPort,
        val index:SemanticIndexPort,val projector:SemanticDocumentProjector,val coordinator:ImmediateSemanticIndexCoordinator
    ):AutoCloseable{override fun close(){coordinator.close()}}

    fun settings():BekkoSettings=settings
    fun setProgressListener(listener:(()->Unit)?){if(!closed.get()||listener==null)progressListener=listener}
    fun modelInstalled()=modelManager.installed()

    fun updateSettings(transform:(BekkoSettings)->BekkoSettings):BekkoSettings{
        ensureOpen()
        val snapshot=settingsStore.load()
        if(transform(snapshot)==snapshot){settings=snapshot;return snapshot}
        var value=snapshot
        SemanticCampaignTransitionRegistry.withSemanticConfigurationTransition(
            prepare={
                value=transform(settingsStore.load())
                settingsStore.save(value)
            }
        ){synchronized(this){settings=value}}
        if(value.enabled)runCatching{withSemanticLease{runtime().coordinator.onCampaignOpened()}}
        return value
    }

    suspend fun download(onProgress:(BekkoDownloadProgress)->Unit={}):File{
        ensureOpen()
        val file=modelManager.download(onProgress)
        ensureOpen()
        SemanticCampaignTransitionRegistry.withCampaignStorageTransition{synchronized(this){closeRuntime()}}
        if(settings.enabled)withSemanticLease{runtime().coordinator.onCampaignOpened()}
        return file
    }

    fun removeModelAndIndexes():Boolean=SemanticCampaignTransitionRegistry.withCampaignStorageTransition{
        ensureOpen()
        synchronized(this){closeRuntime()}
        val modelRemoved=modelManager.remove()
        val indexesRemoved=!indexRoot.exists()||indexRoot.deleteRecursively()
        indexRoot.mkdirs()
        modelRemoved&&indexesRemoved
    }

    fun rebuild():SemanticIndexStatus{
        ensureOpen()
        return SemanticCampaignTransitionRegistry.withCampaignStorageTransition{
            val campaign=repository.activeCampaignRef().campaignId
            SemanticSidecarStorage.invalidateCampaign(app,campaign)
            runtime().coordinator.catchUp()
        }
    }

    fun catchUp():SemanticIndexStatus=withSemanticLease{ensureOpen();runtime().coordinator.catchUp()}
    fun onCampaignOpened(){if(!closed.get())withSemanticLease{if(settings.enabled)runtime().coordinator.onCampaignOpened()}}
    fun onCanonicalCommit(){if(!closed.get())withSemanticLease{if(settings.enabled)runtime().coordinator.onCanonicalCommit()}}

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
        runCatching{withSemanticLease lease@{
            val active=runtime()
            val candidates=active.index.searchAuthorized(request)
            if(candidates.isEmpty())return@lease emptyList()
            val narrowed=request.copy(authorizedRecordUids=candidates.mapTo(linkedSetOf()){it.canonicalRecordUid})
            val current=active.index.currentProjections(narrowed)
            val structurallyCurrent=candidates.filter{candidate->
                val state=current[candidate.canonicalRecordUid]?:return@filter false
                state.sourceAsOfOrder==candidate.sourceAsOfOrder&&state.sourceVersion==candidate.sourceVersion&&
                    state.sourceFingerprint==candidate.sourceFingerprint&&
                    (request.historyGenerationUid==null||state.historyGenerationUid==request.historyGenerationUid)
            }
            val rehydrated=RepositorySemanticCanonicalRehydrator(repository).rehydrate(narrowed,structurallyCurrent)
            structurallyCurrent.mapNotNull{candidate->
                val owner=rehydrated[candidate.canonicalRecordUid]?:return@mapNotNull null
                if(owner.sourceAsOfOrder!=candidate.sourceAsOfOrder||owner.sourceVersion!=candidate.sourceVersion||
                    owner.sourceFingerprint!=candidate.sourceFingerprint)return@mapNotNull null
                candidate.copy(
                    recordKindUid=owner.recordKindUid,epistemicStateUid=owner.epistemicStateUid,
                    chunkEvidence=owner.chunkEvidence
                )
            }
        }}.getOrDefault(emptyList())
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

    private fun rankGameplayReference(
        campaignUid:String,
        reference:IntentReference,
        consumerNodes:List<IntentNode>
    ):List<DomainRef> = withSemanticLease{
        if(!settings.enabled||campaignUid!=repository.activeCampaignRef().campaignId)return@withSemanticLease emptyList()
        val allowedKinds=semanticDefinitionKinds(reference)
        if(allowedKinds.isEmpty())return@withSemanticLease emptyList()
        val query=listOfNotNull(reference.rawPhrase,reference.descriptorHints["surface"],reference.descriptorHints["category"])
            .joinToString(" ").trim().take(512)
        if(query.isEmpty())return@withSemanticLease emptyList()
        runCatching{
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

    private fun rankCharacterCreationCatalog(
        catalog:CharacterCreationCatalog,
        conversation:List<CharacterCreationConversationEntry>
    ):CharacterCreationCatalog = withSemanticLease{
        val latestPlayer=conversation.lastOrNull{it.role==CharacterCreationConversationRole.PLAYER}?.text.orEmpty()
        val catalogQuestion=catalog.answerCatalogQuestion(latestPlayer)!=null
        fun project(semanticOrder:List<String> = emptyList())=catalog.projectForAi(
            conversation,
            maximumEstimatedInputUnits=if(catalogQuestion)5_000 else 900,
            semanticOrder=semanticOrder,
            maximumOptionsPerOptionalKind=if(catalogQuestion)8 else 3
        )
        fun lexical()=project()
        if(!settings.enabled)return@withSemanticLease lexical()
        runCatching{
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

    fun state():BekkoSemanticUiState=withSemanticLease{
        if(closed.get())return@withSemanticLease BekkoSemanticUiState(
            settings=settings,modelInstalled=modelManager.installed(),
            availability=EmbeddingAvailability(EmbeddingAvailabilityState.UNAVAILABLE,"BEKKO_APPLICATION_CLOSED")
        )
        if(!settings.enabled)return@withSemanticLease BekkoSemanticUiState(
            settings=settings,modelInstalled=modelManager.installed(),
            availability=EmbeddingAvailability(EmbeddingAvailabilityState.UNAVAILABLE,"BEKKO_DISABLED")
        )
        val active=runCatching{runtime()}.getOrNull()
        val availability=active?.provider?.availability()
            ?:EmbeddingAvailability(EmbeddingAvailabilityState.UNAVAILABLE,"BEKKO_RUNTIME_INITIALIZATION_FAILED")
        val indexStatus=active?.let{runCatching{it.index.status(it.campaignUid)}.getOrNull()}
        BekkoSemanticUiState(settings,modelManager.installed(),availability=availability,indexStatus=indexStatus,indexProgress=progress)
    }

    private fun retrieveSemantic(request:StructuredRetrievalRequest):StructuredRetrievalResult=withSemanticLease{
        if(!settings.enabled)return@withSemanticLease StructuredRetrievalResult.Unsupported("BEKKO_DISABLED")
        try{
            val active=runtime()
            val hotTail=SemanticHotTailProvider(repository,active.projector)
            if(!active.coordinator.readyForQueries())return@withSemanticLease hotTail.retrieve(request)
            HybridSemanticStructuredQueryProvider(
                SemanticStructuredQueryProvider(
                    active.provider,active.index,
                    RepositorySemanticRuntimeScopeResolver(repository),
                    RepositorySemanticCanonicalRehydrator(repository)
                ),
                hotTail,active.index
            ).retrieve(request)
        }catch(failure:Throwable){
            StructuredRetrievalResult.Unsupported(typedReason("BEKKO_RETRIEVAL_FAILED",failure))
        }
    }

    private fun enrichDirector(trigger:DirectorTrigger,context:DirectorContextEnvelope):DirectorContextEnvelope=withSemanticLease{
        if(!settings.enabled)return@withSemanticLease context
        try{
            val active=runtime()
            if(!active.coordinator.readyForQueries())return@withSemanticLease context
            BekkoDirectorContextScout(
                active.provider,active.index,
                runtimeScope={campaignUid->
                    check(campaignUid==repository.activeCampaignRef().campaignId){"BEKKO_DIRECTOR_CAMPAIGN_CHANGED"}
                    val principal="LOCAL_GM";val player=repository.activePlayerRef()?.playerUid
                    SemanticRuntimeScope(
                        repository.infrastructureHistoryGenerationUid(),principal,
                        semanticHolderSetFingerprint(AudienceKinds.GM_RUNTIME,principal,player),1L,player
                    )
                },
                canonicalRehydration=RepositorySemanticCanonicalRehydrator(repository)
            ).enrich(trigger,context)
        }catch(_:Throwable){context}
    }

    private fun <T> withSemanticLease(block:()->T):T=
        SemanticCampaignTransitionRegistry.withSemanticRuntimeAccess(block)

    private fun typedReason(prefix:String,failure:Throwable):String{
        val message=failure.message?.takeIf{it.isNotBlank()}?.replace(Regex("[^A-Za-z0-9:_-]"),"_")?.take(120)
        return if(message==null)"$prefix:${failure::class.java.simpleName}" else "$prefix:$message"
    }

    private fun runtime():Runtime=SemanticCampaignTransitionRegistry.withSemanticRuntimeAccess{
        synchronized(this){
            ensureOpen()
            val campaign=repository.activeCampaignRef().campaignId
            runtime?.takeIf{it.campaignUid==campaign&&it.backend==settings.backend}?.let{return@synchronized it}
            closeRuntime()
            val provider=BekkoEmbeddingProviderPool.acquire(app,modelManager.modelFile(),settings.backend)
            val index=FileSemanticIndex(SemanticSidecarStorage.campaignDirectory(app,campaign))
            val projector=CommittedReplaySemanticProjector(
                activePlayerUid={repository.activePlayerRef()?.playerUid},
                historyGenerationUid={repository.infrastructureHistoryGenerationUid()}
            )
            val coordinator=ImmediateSemanticIndexCoordinator(repository,provider,index,projector,campaign,onProgress={update->
                progress=update;progressListener?.invoke()
            })
            Runtime(campaign,settings.backend,provider,index,projector,coordinator).also{runtime=it}
        }
    }

    private fun ensureOpen(){check(!closed.get()){"BEKKO_APPLICATION_CLOSED"}}
    /** Establish terminality synchronously (for ViewModel.onCleared) while resource quiescence can
     * continue on a worker thread without leaving a window in which retained callbacks reopen it. */
    fun beginClose(){
        closed.set(true)
        semanticCancellationListener()
    }
    @Synchronized private fun closeRuntime(){runtime?.close();runtime=null}
    override fun close(){
        beginClose()
        if(!closeStarted.compareAndSet(false,true))return
        SemanticCampaignTransitionRegistry.unregisterAndClose(campaignTransitionListener,semanticCancellationListener){
            repository.configureMemoryConsolidationListener(null)
            repository.configureSemanticScopeChangeListener(null)
            synchronized(this){closeRuntime()}
        }
    }
}
