package com.rpgos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

internal data class InfrastructureMechanicalPersistence(
    val activeEffects:List<Pair<String,Long>>,
    val position:CombatPosition?,
    val stateVersion:Long
)

/** Canonical repository facade for the application layer. */
class UnifiedGameRepository(context: Context) : CampaignRepository {
    private val context = context.applicationContext
    private val store = LocalGameStore(this.context)
    private val selection = CampaignSelectionManager(this.context)
    private val visibility = VisibilityAuthorityService()
    private val memoryExecutor=Executors.newSingleThreadExecutor{task->Thread(task,"rpgos-memory-consolidation").apply{isDaemon=true}}
    @Volatile private var memoryEnrichmentPort:MemoryEnrichmentPort?=null
    @Volatile private var memoryConsolidationListener:(()->Unit)?=null
    @Volatile private var semanticScopeChangeListener:(()->Unit)?=null

    internal fun closeBackgroundWork(){memoryExecutor.shutdownNow()}
    internal fun closeBackgroundWorkForTest(){
        closeBackgroundWork()
        memoryExecutor.awaitTermination(5,TimeUnit.SECONDS)
    }

    internal fun configureMemoryEnrichment(port:MemoryEnrichmentPort?){
        memoryEnrichmentPort=port
    }

    internal fun configureMemoryConsolidationListener(listener:(()->Unit)?){
        memoryConsolidationListener=listener
    }

    internal fun configureSemanticScopeChangeListener(listener:(()->Unit)?){
        semanticScopeChangeListener=listener
    }

    override fun bootstrap(){store.bootstrap();scheduleMemoryCatchUp()}
    override fun activeCampaignRef(): ActiveCampaignRef = selection.activeCampaignRef()
    override fun activePlayerRef(): ActivePlayerRef? = store.activePlayerRef()
    override fun setActivePlayer(playerUid: String): ActivePlayerRef {
        val previous=store.activePlayerRef()?.playerUid
        return store.setActivePlayer(playerUid).also{
            if(previous!=playerUid)runCatching{semanticScopeChangeListener?.invoke()}
                .onFailure{DiagnosticLogger.log(context,"PHASE59_ACTIVE_PLAYER_REINDEX_SIGNAL_FAILED",it)}
        }
    }
    fun characterCreationCatalog():CharacterCreationCatalog=store.characterCreationCatalog()
    fun createPlayerCharacter(draft:PlayerCharacterCreationDraft,confirmation:PlayerCharacterCreationConfirmation):PlayerCharacterBootstrapReceipt=
        store.createPlayerCharacter(draft,confirmation)
    internal fun infrastructurePlayerState(): PlayerStateSnapshot? = store.playerState()
    internal fun infrastructureMemoryEnrichmentContext(request:MemoryEnrichmentRequest):MemoryEnrichmentContext? {
        val campaign=activeCampaignRef().campaignId
        if(campaign!=request.manifest.identity.campaignUid)return null
        return openGameplaySaveDb().use { db ->
            val player=ActivePlayerStore(db,campaign).active()?:return@use null
            val holder=KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER,player.playerUid,campaign)
            val projected=ProtectedCampaignReadRepository.borrowed(db,campaign){player}.episodeKnowledge(
                VisibilityAudienceFactory.player(campaign),PurposeContext(campaign,VisibilityPurposeKinds.GAMEPLAY_NARRATION),
                holder,request.manifest)
            val records=(projected as? ProtectedReadResult.Allow)?.takeIf{it.disclosure==DisclosureLevel.DISCLOSE_FULL}?.value
                ?.takeIf{it.isNotEmpty()}?:return@use null
            MemoryEnrichmentContext(campaign,request.manifest.identity.historyGenerationUid,holder,records)
        }
    }
    override fun protectedReads(): ProtectedCampaignReadRepository =
        ProtectedCampaignReadRepository.owned(::openGameplaySaveDb, activeCampaignRef().campaignId, ::activePlayerRef)
    override fun statDefinitions(): List<StatDefinition> = store.statDefinitions()
    override fun resourceDefinitions(): List<ResourceDefinition> = store.resourceDefinitions()
    override fun registerStatDefinitions(worldPackUid: String, definitions: List<StatDefinition>) = store.registerStatDefinitions(worldPackUid, definitions)
    override fun registerResourceDefinitions(worldPackUid: String, definitions: List<ResourceDefinition>) = store.registerResourceDefinitions(worldPackUid, definitions)
    internal fun infrastructurePlayerStats(): List<PlayerStat> = store.playerStats()
    internal fun infrastructurePlayerResources(): List<PlayerResource> = store.playerResources()
    internal fun infrastructurePlayerTechniqueUids():Set<String> = openGameplaySaveDb().use{db->
        val player=activePlayerRef()?:return@use emptySet()
        TechniqueStore(db,player.campaignId).playerTechniques(player.playerUid).map{it.techniqueUid}.toSet()
    }
    internal fun infrastructurePlayerSkillUids():Set<String> = openGameplaySaveDb().use{db->
        val player=activePlayerRef()?:return@use emptySet()
        SkillStore(db,player.campaignId).playerSkills(player.playerUid).map{it.skillUid}.toSet()
    }
    internal fun infrastructureHeldItemInstanceUids(characterUid:String):Set<String> = openGameplaySaveDb().use{db->
        InventoryStore(db,activeCampaignRef().campaignId).typedUnique(characterUid)
            .mapTo(linkedSetOf()){it.first.itemInstanceUid}
    }
    internal fun infrastructureCharacterPanelV2(audience:AudienceContext,purpose:PurposeContext):CharacterPanelSnapshotV2? =
        store.fullCharacterPanelV2(audience,purpose)
    override fun activeCampaignDirName(): String = activeCampaignRef().directoryName
    override fun activeWorldPackDirName(): String = store.activeWorldPackDirName()
    override fun setActiveCampaign(dirName: String) = store.setActiveCampaign(dirName)
    override fun setActiveWorldPack(dirName: String) = store.setActiveWorldPack(dirName)
    override fun createCampaign(name: String): File = store.createCampaign(name)
    override fun createNativeCampaign(spec:NativeWorldCreationSpec):File = store.createNativeCampaign(spec)

    private fun openGameplaySaveDb(): SQLiteDatabase = store.openGameplaySaveDb()
    internal fun infrastructureOpenWorldDb(): SQLiteDatabase = store.openWorldDb()
    internal fun infrastructureOpenCoreDb(): SQLiteDatabase = store.openCoreDb()
    internal fun infrastructureNpcTravelRoutePort():NpcTravelRoutePort = NpcTravelRoutePort { campaignUid,actor,origin ->
        val active=activeCampaignRef().campaignId
        if(campaignUid!=active)emptyList() else openGameplaySaveDb().use{db->
            val snapshot=infrastructureTemporalRead()
            val canonical=MechanicalActorStateStore(db,campaignUid).actor(actor)
            val runtime=Phase63WorldStore(db,campaignUid).edgesFrom(origin).filter { edge->
                edge.validFrom<=snapshot.state.time && (edge.validThrough==null || snapshot.state.time+edge.duration<edge.validThrough) &&
                    edge.version<=Int.MAX_VALUE && edge.resourceCosts.size<=16 && canonical?.executableAbilityUids?.containsAll(edge.requiredCapabilities)==true &&
                    infrastructureWorldRouteKnown(db,campaignUid,actor,edge,snapshot.scope.baseCommitOrder)
            }.map { edge->NpcTravelRouteContract(campaignUid,edge.uid,edge.version.toInt(),edge.origin,edge.destination,edge.duration,
                "P63:ROUTE_MS_V1",resourceCosts=edge.resourceCosts,requiredCapabilities=edge.requiredCapabilities) }
            (SqliteNpcTravelRoutePort(db).routes(campaignUid,actor,origin)+runtime).also { require(it.size<=1024) }
        }
    }
    private fun infrastructureWorldRouteKnown(db:SQLiteDatabase,campaign:String,actor:DomainRef,edge:WorldTopologyEdge,order:Long):Boolean {
        val holder=KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER,actor.uid,campaign)
        val player=activePlayerRef()
        val isPlayer=actor.kindUid=="PLAYER" && player?.playerUid==actor.uid
        if(actor.kindUid=="PLAYER" && !isPlayer)return false
        // This is the actor's route prerequisite reasoning, not a disclosure of private
        // holder knowledge to the human-facing narration audience. Phase38 already permits
        // only the explicitly mapped holder with WORLD_ACTOR_REASONING.
        val audience=AudienceContext(campaign,AudienceKinds.WORLD_ACTOR,VisibilityPrincipalRef(actor.kindUid,actor.uid))
        val authority=UniversalAccessAuthority(AccessAuthorityStore(db,campaign))
        val trusted=authority.trustedContext(audience,order)?.copy(cognitionHolders=setOf(holder))?:return false
        val purpose=PurposeContext(campaign,VisibilityPurposeKinds.WORLD_ACTOR_REASONING)
        val reads=ProtectedCampaignReadRepository.borrowedTrusted(db,campaign,::activePlayerRef,trusted)
        val claim=WorldRouteKnowledge.claimUid(edge)
        val known=reads.npcRequiredClaims(audience,purpose,holder,order,setOf(claim))
        if(claim in ((known as? ProtectedReadResult.Allow)?.value?:emptySet()))return true
        if(!edge.provenanceUid.startsWith("P62:ROUTE:"))return false
        // Preserve the existing Phase62 contract for imported routes: the actor-scoped
        // route catalog enforces access, and the holder must legally know its destination.
        val records=reads.npcKnowledge(audience,purpose,holder,order,64)
        return (records as? ProtectedReadResult.Allow)?.value.orEmpty().any { record->record.subjectRefs.any {
            (it.kindUid=="WORLD_ROUTE" && it.uid==edge.uid) || WorldTopologyAnchor.same(it,edge.destination)
        } }
    }
    internal fun infrastructureNpcActivityContractPort():NpcActivityContractPort = object:NpcActivityContractPort {
        override fun contract(campaignUid:String,capabilityUid:String)=forCapability(campaignUid,capabilityUid).singleOrNull()
        override fun inherent(campaignUid:String)=if(campaignUid==activeCampaignRef().campaignId)NpcActivityContractPort.STANDARD.inherent(campaignUid) else emptyList()
        override fun forCapability(campaignUid:String,capabilityUid:String)=if(campaignUid!=activeCampaignRef().campaignId)emptyList() else
            openGameplaySaveDb().use{SqliteNpcActivityContractPort(it).forCapability(campaignUid,capabilityUid)}
    }
    internal fun infrastructureNpcLearningStatePort():NpcLearningStatePort=NpcLearningStatePort { campaign,actor,rule->
        if(campaign!=activeCampaignRef().campaignId)null else openGameplaySaveDb().use { db->
            when(rule.targetKindUid) {
                ProgressionTargetKinds.SKILL -> {
                    val store=SkillStore(db,campaign)
                    val entry=store.playerSkills(actor.uid).singleOrNull{it.skillUid==rule.targetUid}
                    val definition=store.definitions().singleOrNull{it.skillUid==rule.targetUid && it.status==SkillDefinitionStatus.ACTIVE}
                    if(entry?.progressValue==null || entry.progressSemanticsUid==null || definition==null)null else
                        NpcLearningState(entry.baseMastery,entry.progressValue,entry.progressSemanticsUid,entry.entryVersion,definition.definitionVersion)
                }
                ProgressionTargetKinds.TECHNIQUE -> {
                    val store=TechniqueStore(db,campaign)
                    val entry=store.playerTechniques(actor.uid).singleOrNull{it.techniqueUid==rule.targetUid}
                    val definition=store.definitions().singleOrNull{it.techniqueUid==rule.targetUid && it.status==TechniqueDefinitionStatus.ACTIVE}
                    if(entry?.progressValue==null || entry.progressSemanticsUid==null || definition==null)null else
                        NpcLearningState(entry.baseMastery,entry.progressValue,entry.progressSemanticsUid,entry.entryVersion,definition.definitionVersion)
                }
                else -> null
            }
        }
    }
    internal fun infrastructureNpcReadingAccessPort():NpcReadingAccessPort=NpcReadingAccessPort { campaign,actor,rule->
        if(campaign!=activeCampaignRef().campaignId)false else openGameplaySaveDb().use { db->
            if(!Phase38AccessAuthoritySchema.isReady(db))return@use false
            // Physical possession is necessary, but does not grant disclosure by itself.
            val held=InventoryStore(db,campaign).typedUnique(actor.uid).any{it.first.itemInstanceUid==rule.carrier.uid}
            if(!held)return@use false
            val order=TurnTransactionReceiptStore(db).lastValidCommit(campaign)?.commitOrder?:0L
            val authority=UniversalAccessAuthority(AccessAuthorityStore(db,campaign))
            val audience=AudienceContext(campaign,AudienceKinds.WORLD_ACTOR,VisibilityPrincipalRef(actor.kindUid,actor.uid))
            val trusted=authority.trustedContext(audience,order)?:return@use false
            val carrier=InformationCarrierRef(campaign,rule.carrier.kindUid,rule.carrier.uid)
            val requirement=AccessRequirement(rule.accessPolicyUid,explicitGrantRequired=true,carrier=carrier,
                requiredCarrierStages=CarrierAccessStage.entries.toSet())
            val authorized=authority.authorize(trusted,requirement,order)
            if(!authorized.authorized)return@use false
            val path=Phase38AccessRuntimeAuthority.issuePath(trusted,carrier,"P62:POSSESSED_READABLE_CARRIER",rule.fingerprint,
                false,CarrierAccessStage.entries.toSet())
            authority.effectiveAccess(trusted,requirement,authorized,path).accessible
        }
    }
    internal fun infrastructureNpcActivityRequirementPort():NpcActivityRequirementPort=NpcActivityRequirementPort { campaign,actor,r->
        if(campaign!=activeCampaignRef().campaignId)false else openGameplaySaveDb().use { db->
            val held=InventoryStore(db,campaign).typedUnique(actor.uid).map{it.first.itemInstanceUid}.toSet()
            if(!held.containsAll(r.toolInstanceUids))return@use false
            val holder=KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER,actor.uid,campaign)
            val order=TurnTransactionReceiptStore(db).lastValidCommit(campaign)?.commitOrder?:0L
            val audience=AudienceContext(campaign,AudienceKinds.WORLD_ACTOR,VisibilityPrincipalRef(actor.kindUid,actor.uid))
            val authority=UniversalAccessAuthority(AccessAuthorityStore(db,campaign))
            val trusted=authority.trustedContext(audience,order)?:return@use !r.needsExternalRead
            val cognition=trusted.copy(cognitionHolders=setOf(holder))
            val reads=ProtectedCampaignReadRepository.borrowedTrusted(db,campaign,::activePlayerRef,cognition)
            if(r.knowledgeClaimUids.isNotEmpty()) {
                val knowledge=reads.npcRequiredClaims(audience,PurposeContext(campaign,VisibilityPurposeKinds.WORLD_ACTOR_REASONING),holder,order,r.knowledgeClaimUids)
                val records=(knowledge as? ProtectedReadResult.Allow)?.value?:return@use false
                if(!records.containsAll(r.knowledgeClaimUids))return@use false
            }
            r.teacher?.let { teacher->
                val requirement=AccessRequirement("P62:TEACHER_ACCESS",explicitGrantRequired=true,
                    carrier=InformationCarrierRef(campaign,teacher.kindUid,teacher.uid))
                if(!authority.authorize(trusted,requirement,order).authorized)return@use false
                val self=MechanicalActorStateStore(db,campaign).actor(actor)?:return@use false
                val other=MechanicalActorStateStore(db,campaign).actor(teacher)?:return@use false
                if(self.locationRef==null || self.locationRef!=other.locationRef || other.resources.any{it.resourceUid=="HEALTH" && it.current==0L} ||
                    other.conditions.any{it.conditionUid in setOf("DEAD","UNCONSCIOUS","INCAPACITATED") && it.intensity>0})return@use false
                val a=infrastructureMechanicalPersistence(actor.uid).position as? CombatPosition.Exact?:return@use false
                val b=infrastructureMechanicalPersistence(teacher.uid).position as? CombatPosition.Exact?:return@use false
                if(!npcWithinInteractionRange(a,b,3000))return@use false
            }
            true
        }
    }
    internal fun infrastructureNpcTreatmentReadPort():NpcTreatmentReadPort=NpcTreatmentReadPort { campaign,healer,patient,rule,staged->
        if(campaign!=activeCampaignRef().campaignId) null else openGameplaySaveDb().use { db->
            if(healer!=patient) {
                if(StagedMechanicalProjection.hasSpatialChange(setOf(healer,patient),staged))return@use null
                val order=TurnTransactionReceiptStore(db).lastValidCommit(campaign)?.commitOrder?:0L
                val authority=UniversalAccessAuthority(AccessAuthorityStore(db,campaign))
                val trusted=authority.trustedContext(AudienceContext(campaign,AudienceKinds.WORLD_ACTOR,VisibilityPrincipalRef(healer.kindUid,healer.uid)),order)?:return@use null
                val access=AccessRequirement("P62:TREATMENT_ACCESS",explicitGrantRequired=true,carrier=InformationCarrierRef(campaign,patient.kindUid,patient.uid))
                if(!authority.authorize(trusted,access,order).authorized)return@use null
                val self=MechanicalActorStateStore(db,campaign).actor(healer)?:return@use null
                val other=MechanicalActorStateStore(db,campaign).actor(patient)?:return@use null
                if(self.locationRef==null || self.locationRef!=other.locationRef)return@use null
                val a=infrastructureMechanicalPersistence(healer.uid).position as? CombatPosition.Exact?:return@use null
                val b=infrastructureMechanicalPersistence(patient.uid).position as? CombatPosition.Exact?:return@use null
                if(!npcWithinInteractionRange(a,b,rule.maximumRangeMillimetres))return@use null
            }
            MechanicalActorStateStore(db,campaign).actor(patient)?.let{StagedMechanicalProjection.actor(it,staged)}
        }
    }
    internal fun infrastructureReceipt(transactionUid:String):TurnCommitReceipt? =
        openGameplaySaveDb().use{TurnTransactionReceiptStore(it).committedTransaction(transactionUid)}
    internal fun infrastructureNpcDutyAssignmentPort():NpcDutyAssignmentPort=NpcDutyAssignmentPort { campaign,actor,rule,at->
        if(campaign!=activeCampaignRef().campaignId || at>rule.due)false else openGameplaySaveDb().use { db->
            SqliteNpcDutyAssignmentPort(db,campaign).admitted(campaign,actor,rule,at)
        }
    }
    internal fun infrastructureLastCommitOrder():Long =
        openGameplaySaveDb().use{TurnTransactionReceiptStore(it).lastValidCommit(activeCampaignRef().campaignId)?.commitOrder?:0L}
    internal fun prepareNpcResultConfirmations(input:TemporalOwnerInput,actors:List<DomainRef>):List<NpcBrainChange> {
        require(infrastructureTemporalRead().scope==input.scope){"P61:STALE_RESULT_SCOPE"}
        return openGameplaySaveDb().use { db->
            val store=NpcBrainStore(db,input.scope.campaignUid)
            val owner=NpcCanonicalResultOwner(db,input.scope.campaignUid)
            actors.distinct().take(32).mapNotNull{actor->
                val canonical=store.read(actor)?:return@mapNotNull null
                val brain=applyNpcBrainOverlay(canonical,input.scope,input.stagedChanges.filterIsInstance<NpcBrainChange>())
                owner.reconcile(brain,input.scope)
            }
        }
    }
    internal fun infrastructureTemporalRead():TemporalReadSnapshot {
        val campaign = activeCampaignRef().campaignId
        return CampaignRuntimeLifecycleLock.withTurn(campaign) {
            openGameplaySaveDb().use { db ->
                check(activeCampaignRef().campaignId == campaign) { "P60:CAMPAIGN_CHANGED" }
                TemporalReadSnapshot(TemporalScope(campaign, HistoryGenerationStore(db,campaign).current().value,
                    TurnTransactionReceiptStore(db).lastValidCommit(campaign)?.commitOrder ?: 0L, AuthoritativeStateDigest.compute(db)),
                    Phase60TemporalStateStore(db,campaign).read())
            }
        }
    }
    /** Composition-root entry for a selected NPC. No database/lock escapes into an AI call. */
    internal fun projectNpcDecision(scope:NpcDecisionScope,trigger:NpcTrigger,profile:ContextRuntimeProfile,
                                    affordances:(NpcBrainState,List<NpcKnownRecord>)->List<NpcActionOption>,
                                    recall:NpcRecallPort=NpcRecallPort.NONE,stagedBrains:List<NpcBrainChange> = emptyList(),initialization:NpcBrainState?=null):NpcContextResult {
        val campaign=activeCampaignRef().campaignId
        val snapshot=CampaignRuntimeLifecycleLock.withTurn(campaign) {
            if(scope.temporal.campaignUid!=campaign || activePlayerRef()?.playerUid!=scope.activePlayerUid ||
                infrastructureTemporalRead().scope!=scope.temporal)return@withTurn null
            openGameplaySaveDb().use { db ->
                val brain=NpcBrainStore(db,campaign).read(scope.actor) ?: initialization?.also {
                    require(it.actor==scope.actor && it.campaignUid==campaign)
                    NpcBrainOwner.validateTransition(null,it,NpcBrainRules.GENESIS,listOf(NpcCauseRef(NpcCauseKind.GENESIS,"P61:GENESIS:${it.seedFingerprint}")))
                } ?: return@use null
                val audience=AudienceContext(campaign,AudienceKinds.WORLD_ACTOR,VisibilityPrincipalRef(scope.actor.kindUid,scope.actor.uid))
                val purpose=PurposeContext(campaign,VisibilityPurposeKinds.WORLD_ACTOR_REASONING)
                val base=if(Phase38AccessAuthoritySchema.isReady(db))UniversalAccessAuthority(AccessAuthorityStore(db,campaign)).trustedContext(audience)
                    else Phase38RuntimeAuthority.application(audience)
                // Only this canonical brain's holder, never caller-supplied knowledgeHolders or
                // somebody else's institutional/private cognition mappings.
                val trusted=TrustedPrincipalContext(campaign,audience.principal!!,AudienceKinds.WORLD_ACTOR,
                    roleUids=base?.roleUids.orEmpty(),organizationUids=base?.organizationUids.orEmpty(),
                    clearanceUids=base?.clearanceUids.orEmpty(),cognitionHolders=setOf(brain.knowledgeHolder))
                val reads=ProtectedCampaignReadRepository.borrowedTrusted(db,campaign,::activePlayerRef,trusted)
                val canonicalRead=reads.npcBrain(audience,purpose,scope.actor,brain.knowledgeHolder,initialization)
                val protectedBrain=if(canonicalRead is ProtectedReadResult.Allow) canonicalRead.copy(
                    value=applyNpcBrainOverlay(canonicalRead.value,scope.temporal,stagedBrains)) else canonicalRead
                val decisionBrain=(protectedBrain as? ProtectedReadResult.Allow)?.value?:brain
                val preferred=(listOfNotNull(trigger.cause.takeIf{it.kind==NpcCauseKind.KNOWLEDGE_ACQUISITION}?.uid)+
                    decisionBrain.goals.filter{it.lifecycle==NpcGoalLifecycle.ACTIVE}.sortedWith(compareByDescending<NpcGoal>{it.priority.basisPoints}.thenBy{it.uid})
                        .mapNotNull{it.cause.takeIf{c->c.kind==NpcCauseKind.KNOWLEDGE_ACQUISITION}?.uid}).distinct().take(32).toSet()
                val protectedKnowledge=reads.npcKnowledge(audience,purpose,brain.knowledgeHolder,scope.temporal.baseCommitOrder,64,preferred)
                val protectedHistory=runCatching{reads.npcHistoricalMemory(audience,purpose,brain.knowledgeHolder,
                    HistoryGenerationUid(scope.temporal.historyGenerationUid),scope.temporal.baseCommitOrder)}.getOrElse{ProtectedReadResult.NoData}
                val immutableReads=object:NpcProjectionReadPort {
                    override fun currentRoles(a:AudienceContext,p:PurposeContext):Set<String> =
                        if(a==audience && p==purpose)trusted.roleUids else emptySet()
                    override fun brain(a:AudienceContext,p:PurposeContext,actor:DomainRef,holder:KnowledgeHolderRef):ProtectedReadResult<NpcBrainState> =
                        if(a==audience && p==purpose && actor==scope.actor && holder==brain.knowledgeHolder)protectedBrain else ProtectedReadResult.NoData
                    override fun knowledge(a:AudienceContext,p:PurposeContext,holder:KnowledgeHolderRef,order:Long,limit:Int):ProtectedReadResult<List<NpcKnownRecord>> =
                        if(a==audience && p==purpose && holder==brain.knowledgeHolder && order==scope.temporal.baseCommitOrder && limit==64)protectedKnowledge else ProtectedReadResult.NoData
                    override fun historical(a:AudienceContext,p:PurposeContext,holder:KnowledgeHolderRef,generation:HistoryGenerationUid,order:Long):ProtectedReadResult<List<NpcKnownRecord>> =
                        if(a==audience && p==purpose && holder==brain.knowledgeHolder && generation.value==scope.temporal.historyGenerationUid && order==scope.temporal.baseCommitOrder)protectedHistory else ProtectedReadResult.NoData
                }
                brain.knowledgeHolder to immutableReads
            }
        } ?: return NpcContextResult.Unavailable("P62:STALE_SCOPE_OR_MISSING_BRAIN")
        // The model and optional embedding worker run with no live SQL handle or lifecycle lock.
        val projected=NpcDecisionContextProjector(snapshot.second,recall).project(scope,trigger,snapshot.first,profile,affordances)
        if(activeCampaignRef().campaignId!=campaign || infrastructureTemporalRead().scope!=scope.temporal)
            return NpcContextResult.Unavailable("P62:STALE_SCOPE")
        return projected
    }
    /** A first conversation can use a deterministic genesis projection without a read-side write.
     * Only an existing canonical actor or the exact Core-resolved latent actor may get one. */
    internal fun npcConversationContext(snapshot:TemporalReadSnapshot,actor:DomainRef,plan:CanonicalTurnPlan,
                                        recall:NpcRecallPort=NpcRecallPort.NONE):NpcContextResult {
        if(plan.campaignUid!=snapshot.scope.campaignUid || activeCampaignRef().campaignId!=plan.campaignUid ||
            activePlayerRef()?.playerUid!=plan.intent.actor.actorUid || actor.uid==plan.intent.actor.actorUid)
            return NpcContextResult.Unavailable("P62:DIALOGUE_SCOPE")
        val stored=infrastructureNpcBrainDiagnostics(actor)
        val genesis=if(stored!=null)null else prepareNpcBrainInitializations(snapshot.scope,emptyList(),listOf(actor))
            .singleOrNull()?.let{NpcBrainCodec.decode(it.stateCanonical)} ?: plan.intent.references
            .mapNotNull{LatentWorldReferenceCodec.decode(plan.campaignUid,it)}
            .singleOrNull{it.element==actor && it.baseKind==WorldElementBaseKind.ACTOR}
            ?.let{NpcBrainOwner.initialize(plan.campaignUid,actor,"P61:CANONICAL_ACTOR:1")}
        val brain=stored?:genesis?:return NpcContextResult.Unavailable("P62:DIALOGUE_ACTOR_NOT_CANONICAL")
        val scope=NpcDecisionScope(snapshot.scope,actor,brain.revision,snapshot.state.time,0,plan.intent.actor.actorUid)
        val cause=brain.motivations.firstOrNull()?.uid?:return NpcContextResult.Unavailable("P62:DIALOGUE_BRAIN_INVALID")
        val trigger=NpcTrigger("P62:CONVERSATION:${phase60Hash(plan.planUid+actor)}",NpcTriggerKind.SELF_REFLECTION,snapshot.state.time,
            NpcCauseRef(NpcCauseKind.INTRINSIC_MOTIVATION,cause))
        return projectNpcDecision(scope,trigger,ContextRuntimeProfile("ANDROID-NPC-DIALOGUE",2048,128,512,128,128),
            {_,_->emptyList()},recall,initialization=genesis)
    }
    internal fun infrastructureNpcDecisionScope(actor:DomainRef,at:WorldTimeTick,ordinal:Int):NpcDecisionScope {
        val campaign=activeCampaignRef().campaignId
        return CampaignRuntimeLifecycleLock.withTurn(campaign) {
            val active=activePlayerRef() ?: error("P62:ACTIVE_PLAYER_REQUIRED")
            val brain=openGameplaySaveDb().use{NpcBrainStore(it,campaign).read(actor)} ?: error("P62:BRAIN_NOT_INITIALIZED")
            NpcDecisionScope(infrastructureTemporalRead().scope,actor,brain.revision,at,ordinal,active.playerUid)
        }
    }
    /** Infrastructure selects a participant's legal stimulus; it does not grant another holder's knowledge. */
    internal fun npcCognitionStimulus(expected:TemporalScope,actor:DomainRef,diagnostic:(String)->Unit={}):NpcCognitionStimulus? {
        val campaign=activeCampaignRef().campaignId
        return CampaignRuntimeLifecycleLock.withTurn(campaign) {
            if(expected!=infrastructureTemporalRead().scope || actor.uid==activePlayerRef()?.playerUid)return@withTurn null
            openGameplaySaveDb().use { db ->
                val brain=NpcBrainStore(db,campaign).read(actor)?:return@use null
                val audience=AudienceContext(campaign,AudienceKinds.WORLD_ACTOR,VisibilityPrincipalRef(actor.kindUid,actor.uid))
                val base=if(Phase38AccessAuthoritySchema.isReady(db))UniversalAccessAuthority(AccessAuthorityStore(db,campaign)).trustedContext(audience) else null
                val trusted=TrustedPrincipalContext(campaign,audience.principal!!,AudienceKinds.WORLD_ACTOR,
                    roleUids=base?.roleUids.orEmpty(),organizationUids=base?.organizationUids.orEmpty(),clearanceUids=base?.clearanceUids.orEmpty(),
                    cognitionHolders=setOf(brain.knowledgeHolder))
                val reads=ProtectedCampaignReadRepository.borrowedTrusted(db,campaign,::activePlayerRef,trusted)
                val projected=reads.npcKnowledge(audience,PurposeContext(campaign,VisibilityPurposeKinds.WORLD_ACTOR_REASONING),brain.knowledgeHolder,expected.baseCommitOrder,64)
                val allowed=when(projected) {
                    is ProtectedReadResult.Allow->projected.value
                    ProtectedReadResult.NoData->emptyList()
                    else->{
                        val reason=when(projected) {
                            is ProtectedReadResult.Deny->projected.reasonCode
                            is ProtectedReadResult.NotDisclosed->projected.reasonCode
                            is ProtectedReadResult.Unknown->projected.reasonCode
                            is ProtectedReadResult.Corruption->projected.reasonCode
                            else->projected.stateUid
                        }
                        diagnostic("P62:KNOWLEDGE_${projected.stateUid}:$reason")
                        return@use null // denied/corrupt holder data is never an empty authorized record set
                    }
                }
                val record=allowed.maxWithOrNull(compareBy<NpcKnownRecord>{it.sourceCommittedOrder}.thenBy{it.uid})
                if(record==null) {
                    val motivation=brain.motivations.sortedWith(compareByDescending<NpcMotivation>{it.strength.basisPoints}.thenBy{it.uid}).firstOrNull()?:return@use null
                    return@use NpcCognitionStimulus(actor,brain.revision,motivation.uid,0,NpcCauseKind.INTRINSIC_MOTIVATION)
                }
                NpcCognitionStimulus(actor,brain.revision,record.acquisitionUid,record.sourceCommittedOrder)
            }
        }
    }
    internal fun infrastructureNpcBrainDiagnostics(actor:DomainRef):NpcBrainState? {
        val campaign=activeCampaignRef().campaignId
        return CampaignRuntimeLifecycleLock.withTurn(campaign) { openGameplaySaveDb().use{NpcBrainStore(it,campaign).read(actor)} }
    }
    /** Lazy initialization is a proposal for the current transaction, never an on-read write. */
    internal fun prepareNpcBrainInitializations(expected:TemporalScope,effects:List<VerifiedMechanicsCommandEffect>,participants:List<DomainRef> = emptyList(),drafts:List<WorldElementDraft> = emptyList()):List<NpcBrainChange> {
        val campaign=activeCampaignRef().campaignId
        require(expected.campaignUid==campaign)
        return CampaignRuntimeLifecycleLock.withTurn(campaign) {
            require(infrastructureTemporalRead().scope==expected) { "P61:STALE_INITIALIZATION" }
            val active=activePlayerRef()?.playerUid
            openGameplaySaveDb().use { db ->
                val brains=NpcBrainStore(db,campaign);val actors=MechanicalActorStateStore(db,campaign)
                val candidates=(effects.map{it.target}+participants).distinct().sortedWith(compareBy<DomainRef>{it.kindUid}.thenBy{it.uid})
                    .filter{it.uid!=active}.take(32)
                if(candidates.isEmpty())return@use emptyList<NpcBrainChange>()
                val canonicalActors=infrastructureCanonicalWorldElementsAt(candidates.mapTo(linkedSetOf()){it.uid},expected.baseCommitOrder)
                    .filter{it.presentationFacts[CampaignWorldFacts.KIND]==WorldElementBaseKind.ACTOR.name}.mapTo(linkedSetOf()){it.subjectUid}
                val materializedActors=NpcBrainOwner.admittedMaterializations(campaign,effects,drafts)
                store.openWorldDb().use { worldDb -> candidates.mapNotNull { actor ->
                        if(brains.read(actor)!=null)return@mapNotNull null
                        val canonical=actors.actor(actor)
                        if(canonical!=null && canonical.kind !in setOf(MechanicalActorKind.NPC,MechanicalActorKind.MONSTER,
                                MechanicalActorKind.SUMMON,MechanicalActorKind.FORMER_PLAYER))return@mapNotNull null
                        val packActor=actor.kindUid in setOf("NPC","CHARACTER") &&
                            runCatching{CanonCharacterProjectionReader(worldDb).profileRow(actor.uid).isNotEmpty()}.getOrDefault(false)
                        val campaignActor=actor.kindUid in setOf("ACTOR","NPC","CHARACTER","WORLD_ACTOR") && actor.uid in canonicalActors
                        if(canonical==null && !packActor && !campaignActor && actor !in materializedActors)return@mapNotNull null
                        val initialized=NpcBrainOwner.initialize(campaign,actor,canonical?.generationProvenanceUid?:"P61:CANONICAL_ACTOR:1")
                        NpcBrainChange(campaign,actor,expected.historyGenerationUid,0,null,NpcBrainCodec.encode(initialized),
                            NpcBrainRules.GENESIS.uid,NpcBrainRules.GENESIS.version,
                            listOf(NpcCauseRef(NpcCauseKind.GENESIS,"P61:GENESIS:${initialized.seedFingerprint}")))
                    } }
            }
        }
    }
    internal fun prepareNpcConsequenceObservations(expected:TemporalScope,effects:List<VerifiedMechanicsCommandEffect>):List<VerifiedMechanicsCommandEffect> {
        val campaign=activeCampaignRef().campaignId
        require(expected.campaignUid==campaign)
        return CampaignRuntimeLifecycleLock.withTurn(campaign) {
            require(infrastructureTemporalRead().scope==expected) { "P62:STALE_OBSERVATION" }
            val active=activePlayerRef()?.playerUid
            openGameplaySaveDb().use { db ->
                val actors=MechanicalActorStateStore(db,campaign)
                val observing=db.rawQuery("SELECT actor_kind_uid,actor_uid FROM ${Phase61NpcSchema.STATES} WHERE campaign_uid=? ORDER BY actor_kind_uid,actor_uid LIMIT 32",arrayOf(campaign))
                    .use{c->buildList{while(c.moveToNext())add(DomainRef(c.getString(0),c.getString(1)))}}
                val witnessed=NpcWitnessObservation.annotate(expected,effects) { effect->
                    if(effect.effectKindUid!="WOUND" || effect.mechanicsOwnerUid!="UNIVERSAL_COMBAT" || effect.magnitude<=0)return@annotate emptyList()
                    observing.filter{it!=effect.target && it.uid!=active}.mapNotNull { observer->
                        val body=actors.actor(observer)?:return@mapNotNull null
                        if(body.materialization!=MechanicalStateMaterialization.FULL || NpcLegalEffectObservation.CAPABILITY !in body.executableAbilityUids ||
                            body.conditions.any{it.intensity>0 && it.conditionUid in setOf("DEAD","UNCONSCIOUS","INCAPACITATED","BLIND")} ||
                            body.resources.any{it.resourceUid=="HEALTH" && it.current==0L})return@mapNotNull null
                        if(effects.any{it.target==observer && it.effectKindUid in setOf("SPATIAL","LOCATION_TRANSITION","CONDITION","CONTROL","RESTRICTION")})return@mapNotNull null
                        val observerLocation=infrastructureEntityLocationUid(observer.uid)?:return@mapNotNull null
                        if(observerLocation!=infrastructureEntityLocationUid(effect.target.uid))return@mapNotNull null
                        val a=infrastructureMechanicalPersistence(observer.uid).position as? CombatPosition.Exact?:return@mapNotNull null
                        val b=infrastructureMechanicalPersistence(effect.target.uid).position as? CombatPosition.Exact?:return@mapNotNull null
                        if(!npcWithinInteractionRange(a,b,NpcLegalEffectObservation.RANGE_MM))return@mapNotNull null
                        val audience=AudienceContext(campaign,AudienceKinds.WORLD_ACTOR,VisibilityPrincipalRef(observer.kindUid,observer.uid))
                        val authority=UniversalAccessAuthority(AccessAuthorityStore(db,campaign))
                        val trusted=authority.trustedContext(audience,expected.baseCommitOrder)?:return@mapNotNull null
                        if(!authority.authorize(trusted,AccessRequirement(NpcLegalEffectObservation.POLICY,explicitGrantRequired=true,
                            carrier=InformationCarrierRef(campaign,effect.target.kindUid,effect.target.uid)),expected.baseCommitOrder).authorized)return@mapNotNull null
                        val holder=KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER,observer.uid,campaign)
                        val reads=ProtectedCampaignReadRepository.borrowedTrusted(db,campaign,::activePlayerRef,trusted.copy(cognitionHolders=setOf(holder)))
                        val knowledge=reads.npcKnowledge(audience,PurposeContext(campaign,VisibilityPurposeKinds.WORLD_ACTOR_REASONING),holder,expected.baseCommitOrder,64)
                        val records=(knowledge as? ProtectedReadResult.Allow)?.value?:return@mapNotNull null
                        val recognized=records.filter{effect.target in it.subjectRefs}
                        if(recognized.isEmpty())return@mapNotNull null
                        NpcLegalEffectObservation.project(authority,trusted,expected,effect.target,effect,NpcWitnessPerceptionInput(body,
                            observerLocation,infrastructureEntityLocationUid(effect.target.uid),a,b,recognized))
                    }
                }
                NpcConsequenceObservation.annotate(expected,witnessed){actor->if(actor.uid==active)null else actors.actor(actor)}
            }
        }
    }
    internal fun infrastructureConditionExpiryApplications(entries:List<ScheduledConditionExpiry>):Map<String,Set<String>> =
        openGameplaySaveDb().use { db -> entries.associate { entry -> entry.deadlineUid to db.rawQuery(
            "SELECT active_effect_uid FROM active_combat_effects WHERE entity_uid=? AND effect_key=? AND status='active' ORDER BY active_effect_uid",
            arrayOf(entry.subject.uid,"CONDITION:${entry.conditionUid}")).use { cursor->buildSet{while(cursor.moveToNext())add(cursor.getString(0))} }
        } }
    internal fun commitTemporalTurn(identity:TurnTransactionIdentity, proposal:CanonicalCampaignMutationProposal,
                                   expected:TemporalScope?):TurnExecutionResult<TurnCommitAppliedResult> {
        val result=CampaignRuntimeLifecycleLock.withTurn(identity.campaignUid) {
            check(activeCampaignRef().campaignId == identity.campaignUid) { "P60:CAMPAIGN_CHANGED" }
            val retried = openGameplaySaveDb().use { TurnTransactionReceiptStore(it).committedCommand(identity.campaignUid,identity.commandUid) != null }
            if (!retried && proposal.playerChangeSet.changes.any { it.payload is TemporalStateChange }) {
                check(expected != null && infrastructureTemporalRead().scope == expected) { "P60:STALE_HISTORY" }
            }
            commitTurnWithoutMaintenance(identity,proposal,TurnFailureInjector.NONE)
        }
        // Snapshot publication is an administrative recovery operation, not gameplay authority.
        // Leave the outer temporal scope before maintenance takes its own recovery lock.
        // Otherwise withRecovery correctly rejects every post-turn Undo checkpoint.
        finishTurnMaintenance(identity)
        return result
    }
    internal fun infrastructureLastReceipt():TurnCommitReceipt? =
        openGameplaySaveDb().use{TurnTransactionReceiptStore(it).lastValidCommit(activeCampaignRef().campaignId)}
    internal fun infrastructureReplayPayload(transactionUid:String,committedOrder:Long):CommittedReplayPayload? =
        openGameplaySaveDb().use{db->CommittedReplayPayloadStore(db).after(activeCampaignRef().campaignId,(committedOrder-1).coerceAtLeast(0)).singleOrNull{it.identity.transactionUid==transactionUid}}
    internal fun infrastructureReplayPayloadsAfter(committedOrder:Long):List<CommittedReplayPayload> =
        openGameplaySaveDb().use{db->CommittedReplayPayloadStore(db).after(activeCampaignRef().campaignId,committedOrder.coerceAtLeast(0))}
    internal fun infrastructureReplayPayloadsAfterLimited(committedOrder:Long,maximumTransactions:Int):List<CommittedReplayPayload> =
        openGameplaySaveDb().use{db->CommittedReplayPayloadStore(db).afterLimited(activeCampaignRef().campaignId,committedOrder.coerceAtLeast(0),maximumTransactions)}
    internal fun infrastructureReplayTailAfterLimited(committedOrder:Long,maximumTransactions:Int):List<CommittedReplayPayload> =
        openGameplaySaveDb().use{db->CommittedReplayPayloadStore(db).tailAfterLimited(activeCampaignRef().campaignId,committedOrder.coerceAtLeast(0),maximumTransactions)}
    internal fun infrastructureReplayPayloadsAtOrders(committedOrders:Set<Long>):List<CommittedReplayPayload> =
        openGameplaySaveDb().use{db->CommittedReplayPayloadStore(db).atOrders(activeCampaignRef().campaignId,committedOrders)}
    internal fun infrastructureCanonicalWorldElementsAt(
        subjectUids:Set<String>,asOfOrder:Long
    ):List<CanonicalWorldElementSemanticState> = openGameplaySaveDb().use{db->
        require(asOfOrder>=0&&subjectUids.size in 1..200&&subjectUids.none{it.isBlank()})
        val campaign=activeCampaignRef().campaignId
        val ordered=subjectUids.sorted();val placeholders=ordered.joinToString(","){"?"}
        val latestChangeBySubject=db.rawQuery("""SELECT b.subject_uid,
                MAX(CASE WHEN COALESCE(sr.commit_order,0)<=?
                    THEN MAX(COALESCE(br.commit_order,0),COALESCE(sr.commit_order,0))
                    ELSE COALESCE(br.commit_order,0) END)
            FROM campaign_truth_records b
            LEFT JOIN ${CampaignSnapshotSchema.REPLAY} br ON br.campaign_uid=b.campaign_id AND br.turn_uid=b.created_turn
            LEFT JOIN campaign_truth_records s ON s.campaign_id=b.campaign_id AND s.supersedes_truth_uid=b.truth_uid
            LEFT JOIN ${CampaignSnapshotSchema.REPLAY} sr ON sr.campaign_uid=s.campaign_id AND sr.turn_uid=s.created_turn
            WHERE b.campaign_id=? AND b.subject_uid IN ($placeholders)
              AND b.predicate LIKE 'RPGOS-WORLD:%' AND COALESCE(br.commit_order,0)<=?
            GROUP BY b.subject_uid""",
            (listOf(asOfOrder.toString(),campaign)+ordered+listOf(asOfOrder.toString())).toTypedArray()
        ).use{cursor->buildMap{while(cursor.moveToNext())put(cursor.getString(0),cursor.getLong(1))}}
        val args=(listOf(campaign)+ordered+listOf(asOfOrder.toString(),campaign,asOfOrder.toString())).toTypedArray()
        data class Fact(val truthUid:String,val subjectUid:String,val predicate:String,val value:String?,val order:Long,val createdAt:Long)
        val facts=db.rawQuery("""SELECT t.truth_uid,t.subject_uid,t.predicate,t.object_value,
                COALESCE(r.commit_order,0),t.created_at
            FROM campaign_truth_records t
            LEFT JOIN ${CampaignSnapshotSchema.REPLAY} r ON r.campaign_uid=t.campaign_id AND r.turn_uid=t.created_turn
            WHERE t.campaign_id=? AND t.subject_uid IN ($placeholders) AND t.truth_kind='FACT'
              AND t.predicate LIKE 'RPGOS-WORLD:%' AND COALESCE(r.commit_order,0)<=?
              AND NOT EXISTS(
                SELECT 1 FROM campaign_truth_records s
                LEFT JOIN ${CampaignSnapshotSchema.REPLAY} sr ON sr.campaign_uid=s.campaign_id AND sr.turn_uid=s.created_turn
                WHERE s.campaign_id=? AND s.supersedes_truth_uid=t.truth_uid AND COALESCE(sr.commit_order,0)<=?
              )
            ORDER BY t.subject_uid,t.predicate,COALESCE(r.commit_order,0),t.created_at,t.truth_uid""",args
        ).use{cursor->buildList{while(cursor.moveToNext())add(Fact(
            cursor.getString(0),cursor.getString(1),cursor.getString(2),
            if(cursor.isNull(3))null else cursor.getString(3),cursor.getLong(4),cursor.getLong(5)
        ))}}
        facts.filter{it.predicate in CampaignWorldFacts.ALL}.groupBy{it.subjectUid}.mapNotNull{(subject,group)->
            fun latest(predicate:String)=group.filter{it.predicate==predicate}
                .maxWithOrNull(compareBy<Fact>{it.order}.thenBy{it.createdAt}.thenBy{it.truthUid})?.value
            val presentation=linkedMapOf<String,String>()
            listOf(
                CampaignWorldFacts.KIND,CampaignWorldFacts.NAME,CampaignWorldFacts.CATEGORY,
                CampaignWorldFacts.PARENT,CampaignWorldFacts.TOPOLOGY,CampaignWorldFacts.AUDIENCE_SCOPE
            ).forEach{predicate->latest(predicate)?.takeIf(String::isNotBlank)?.let{presentation[predicate]=it}}
            val affordances=group.filter{it.predicate==CampaignWorldFacts.AFFORDANCE}
                .mapNotNull{it.value?.takeIf(String::isNotBlank)}.distinct().sorted()
            if(affordances.isNotEmpty())presentation[CampaignWorldFacts.AFFORDANCE]=affordances.joinToString(",")
            if(presentation.isEmpty())null else CanonicalWorldElementSemanticState(
                campaign,subject,presentation,latestChangeBySubject[subject]?:group.maxOf{it.order}
            )
        }.sortedBy{it.subjectUid}
    }
    internal fun infrastructureWorldTruthSubjects(truthUids:Set<String>):Map<String,String> = openGameplaySaveDb().use{db->
        if(truthUids.isEmpty())return@use emptyMap()
        require(truthUids.size<=200&&truthUids.none{it.isBlank()})
        val ordered=truthUids.sorted();val placeholders=ordered.joinToString(","){"?"}
        val args=(listOf(activeCampaignRef().campaignId)+ordered).toTypedArray()
        db.rawQuery("""SELECT truth_uid,subject_uid FROM campaign_truth_records
            WHERE campaign_id=? AND truth_uid IN ($placeholders) AND subject_uid IS NOT NULL
              AND predicate LIKE 'RPGOS-WORLD:%'""",args
        ).use{cursor->buildMap{while(cursor.moveToNext())put(cursor.getString(0),cursor.getString(1))}}
    }
    internal fun infrastructureCanonicalWorldEpistemicAssertionsAt(
        truthUids:Set<String>,asOfOrder:Long
    ):List<CanonicalWorldEpistemicSemanticState> = openGameplaySaveDb().use{db->
        require(asOfOrder>=0&&truthUids.size in 1..200&&truthUids.none{it.isBlank()})
        val campaign=activeCampaignRef().campaignId;val ordered=truthUids.sorted()
        val placeholders=ordered.joinToString(","){"?"}
        val args=(listOf(campaign)+ordered+listOf(asOfOrder.toString(),campaign,asOfOrder.toString())).toTypedArray()
        db.rawQuery("""SELECT t.truth_uid,t.truth_kind,t.subject_uid,t.predicate,t.object_value,
                t.perspective_uid,t.narrative_text,COALESCE(r.commit_order,0)
            FROM campaign_truth_records t
            LEFT JOIN ${CampaignSnapshotSchema.REPLAY} r ON r.campaign_uid=t.campaign_id AND r.turn_uid=t.created_turn
            WHERE t.campaign_id=? AND t.truth_uid IN ($placeholders) AND t.truth_kind!='FACT'
              AND t.predicate LIKE 'RPGOS-WORLD:%' AND COALESCE(r.commit_order,0)<=?
              AND NOT EXISTS(
                SELECT 1 FROM campaign_truth_records s
                LEFT JOIN ${CampaignSnapshotSchema.REPLAY} sr ON sr.campaign_uid=s.campaign_id AND sr.turn_uid=s.created_turn
                WHERE s.campaign_id=? AND s.supersedes_truth_uid=t.truth_uid AND COALESCE(sr.commit_order,0)<=?
              ) ORDER BY t.truth_uid""",args
        ).use{cursor->buildList{while(cursor.moveToNext())add(CanonicalWorldEpistemicSemanticState(
            campaignUid=campaign,truthUid=cursor.getString(0),epistemicKind=TruthKind.valueOf(cursor.getString(1)),
            subjectUid=if(cursor.isNull(2))null else cursor.getString(2),predicate=cursor.getString(3),
            objectValue=if(cursor.isNull(4))null else cursor.getString(4),
            perspectiveUid=if(cursor.isNull(5))null else cursor.getString(5),
            narrativeText=if(cursor.isNull(6))null else cursor.getString(6),sourceAsOfOrder=cursor.getLong(7)
        ))}}
    }
    internal fun infrastructureHistoryGenerationUid():HistoryGenerationUid = openGameplaySaveDb().use{db->
        HistoryGenerationStore(db,activeCampaignRef().campaignId).current()
    }
    /** Preparation only. Legacy roots are first written together with the next accepted action. */
    internal fun infrastructureWorldSkeletonCandidate():CampaignWorldSkeleton? {
        val campaign=activeCampaignRef().campaignId
        return CampaignRuntimeLifecycleLock.withTurn(campaign) {
            openGameplaySaveDb().use { db ->
                Phase63WorldStore(db,campaign).root()?.let { return@use it.skeleton }
                val player=activePlayerRef()?.playerUid?:return@use null
                val anchor=infrastructureEntityLocationUid(player)?:return@use null
                val binding=infrastructureWorldPackAuthority().binding
                val rules=if(binding.sourceKind==CampaignRuleSourceKind.WORLD_PACK)store.openWorldDb().use { Phase63RuleSourceImport.read(it,binding) }
                    else Phase63RuleSourceData(CoreLatentWorldRules.initial(),emptyList())
                CampaignWorldSkeleton.legacy(campaign,binding.ruleSource,"EXISTING_CAMPAIGN",DomainRef("PLACE",anchor))
                    .copy(latentRules=rules.localRules,macroRegionRules=rules.macroRules)
            }
        }
    }
    internal fun infrastructureWorldFrame(audience:AudienceContext,purpose:PurposeContext):ProtectedReadResult<Map<String,String>> {
        val campaign=activeCampaignRef().campaignId
        if(audience.campaignUid!=campaign || purpose.campaignUid!=campaign)return ProtectedReadResult.Deny("CROSS_CAMPAIGN_CONTEXT")
        val player=activePlayerRef()?:return ProtectedReadResult.NoData
        // The registered player-state read establishes the same principal/purpose authority.
        // No latent region, seed, private constraint or NPC knowledge is included in this frame.
        when(val access=protectedReads().playerState(audience,purpose,player.playerUid)) {
            is ProtectedReadResult.Allow -> Unit
            is ProtectedReadResult.Deny -> return access
            is ProtectedReadResult.NoData -> return access
            is ProtectedReadResult.NotDisclosed -> return access
            is ProtectedReadResult.Unknown -> return access
            is ProtectedReadResult.Corruption -> return access
        }
        val skeleton=infrastructureWorldSkeletonCandidate()?:return ProtectedReadResult.NoData
        return ProtectedReadResult.Allow(buildMap {
            put("source_kind",skeleton.ruleSource.kind.name);put("era",skeleton.era)
            if(skeleton.ruleSource.kind==CampaignRuleSourceKind.CAMPAIGN_NATIVE)skeleton.constraints["WORLD_PREMISE"]?.let { put("declared_world_premise",it.take(2048)) }
            put("classification","DECLARED_CAMPAIGN_CONFIGURATION");put("source_version",skeleton.ruleSource.version)
        },DisclosureLevel.DISCLOSE_FULL,"P63:PUBLIC_WORLD_FRAME")
    }
    internal fun infrastructureWorldInitialization(request:ChatTurnRequest):WorldSimulationChange? {
        val campaign=activeCampaignRef().campaignId
        require(request.campaignUid==campaign) { "P63:CROSS_CAMPAIGN_INITIALIZATION" }
        return CampaignRuntimeLifecycleLock.withTurn(campaign) {
            openGameplaySaveDb().use { db ->
                if(Phase63WorldStore(db,campaign).root()!=null)return@use null
                val candidate=infrastructureWorldSkeletonCandidate()?:return@use null
                WorldSimulationChange(campaign,HistoryGenerationStore(db,campaign).current(),0,candidate)
            }
        }
    }
    internal fun infrastructureWorldExpansion(request:ChatTurnRequest,effects:List<VerifiedMechanicsCommandEffect>):List<WorldSimulationChange> {
        val scope=infrastructureWorldResolutionScope()?:return listOfNotNull(infrastructureWorldInitialization(request))
        val drafts=effects.mapNotNull { CoreLatentWorldRules.draft(scope.campaignUid,it) }
        val owner=CapturedWorldMaterializationPort(scope,drafts,::infrastructureWorldResolutionCurrent) { prepareWorldExpansion(request,effects) }
        val registry=WorldComponentOwnerRegistry(mapOf("WORLD_SIMULATION" to owner))
        return registry.requireOwner("WORLD_SIMULATION").prepare(scope,drafts).map { it as WorldSimulationChange }
    }
    private fun prepareWorldExpansion(request:ChatTurnRequest,effects:List<VerifiedMechanicsCommandEffect>):List<WorldSimulationChange> {
        val campaign=activeCampaignRef().campaignId
        require(request.campaignUid==campaign) { "P63:CROSS_CAMPAIGN_EXPANSION" }
        return CampaignRuntimeLifecycleLock.withTurn(campaign) { openGameplaySaveDb().use { db->
            val root=Phase63WorldStore(db,campaign).root()
            val skeleton=root?.skeleton?:infrastructureWorldSkeletonCandidate()?:return@use emptyList()
            val time=infrastructureTemporalRead().state.time
            val edges=effects.mapNotNull { CoreLatentWorldRules.draft(campaign,it) }
                .flatMap { CoreLatentWorldRules.localEdges(skeleton,it,time) }.distinctBy { it.uid }
            require(edges.size<=32) { "P63:LOCAL_CONNECTION_BUDGET" }
            val createdManifests=effects.mapNotNull { CoreLatentWorldRules.draft(campaign,it) }.filter { it.baseKind==WorldElementBaseKind.GROUP }.mapNotNull { draft->
                val seed=Phase63ActorGeneration.forDraft(skeleton,draft,MechanicalStateMaterialization.FULL)?:return@mapNotNull null
                WorldPopulationManifest(draft.element,requireNotNull(seed.aggregateCount),skeleton.domainSeed("POPULATION",draft.element.toString()))
            }.distinctBy { it.uid }
            val populations=WorldPopulationStore(db,campaign)
            val selectedAggregates=effects.mapNotNull { effect->effect.canonicalPayload["p63_population_aggregate_kind"]?.let { kind->
                DomainRef(kind,requireNotNull(effect.canonicalPayload["p63_population_aggregate_uid"])) } }
            val adoptedManifests=(effects.map { it.target }+selectedAggregates).distinct().filter { it.kindUid in setOf("GROUP","UNIT") && populations.forAggregate(it)==null }
                .mapNotNull { aggregate->MechanicalActorStateStore(db,campaign).population(aggregate)?.totalCount?.takeIf { it>0 }?.let { count->
                    WorldPopulationManifest(aggregate,count,skeleton.domainSeed("POPULATION",aggregate.toString())) } }
            val manifests=(createdManifests+adoptedManifests).distinctBy { it.uid }
            val baseVersions=mutableMapOf<String,Long>()
            val extractedCounts=mutableMapOf<String,Long>()
            val extractions=effects.filter { "p63_population_manifest" in it.canonicalPayload }.distinctBy { it.target }.map { effect->
                val manifestUid=requireNotNull(effect.canonicalPayload["p63_population_manifest"])
                val baseVersion=requireNotNull(effect.canonicalPayload["p63_population_version"]).toLong()
                require(baseVersions.getOrPut(manifestUid) { baseVersion }==baseVersion) { "P63:POPULATION_PREPARATION_VERSION_CONFLICT" }
                val offset=extractedCounts[manifestUid]?:0L
                extractedCounts[manifestUid]=offset+1
                WorldPopulationExtraction(requireNotNull(effect.canonicalPayload["p63_population_manifest"]),requireNotNull(effect.canonicalPayload["p63_population_ordinal"]).toLong(),
                    effect.target,requireNotNull(effect.canonicalPayload["display_name"]),Math.addExact(baseVersion,offset),effect.proofUid)
            }.distinctBy { it.member }
            if(root!=null && edges.isEmpty() && manifests.isEmpty() && extractions.isEmpty())emptyList() else listOf(WorldSimulationChange(campaign,HistoryGenerationStore(db,campaign).current(),
                root?.version?:0,if(root==null)skeleton else null,edges,populationManifests=manifests,populationExtractions=extractions))
        } }
    }
    internal fun infrastructureWorldWorkPlan(snapshot:TemporalReadSnapshot,direct:List<DomainRef>):WorldLodWorkPlan = openGameplaySaveDb().use { db->
        require(direct.size<=128 && snapshot.scope.campaignUid==activeCampaignRef().campaignId && infrastructureTemporalRead().scope==snapshot.scope) { "P63:STALE_LOD_SCOPE" }
        val campaign=snapshot.scope.campaignUid
        val player=activePlayerRef()?.let { DomainRef("PLAYER",it.playerUid) }
        val pending=NpcActionProcess.decode(snapshot.state.processStates.singleOrNull { it.ownerUid==NpcActionProcess.OWNER }).map { it.actor }.toSet()
        val populations=WorldPopulationStore(db,campaign)
        val subjects=(direct+pending+listOfNotNull(player)).distinct().map { ref->
            val aggregate=populations.aggregateForMember(ref)
            val body=if(ref.kindUid in setOf("GROUP","UNIT"))MechanicalActorStateStore(db,campaign).actor(ref) else null
            WorldLodSubject(ref,aggregate,body?.aggregatePopulation?.totalCount?:0,ref==player,ref in direct,
                ref in pending,ref.kindUid=="UNIT")
        }
        WorldLodWorkPlan(snapshot.scope,subjects)
    }
    /** Anonymous bodies and named members are disjoint. An area attack is resolved by the
     * existing Phase50 selector, not by adding the named people back into anonymous counts. */
    internal fun infrastructureWorldCombatMembers(plan:CanonicalTurnPlan,targets:List<DomainRef>):List<DomainRef> = openGameplaySaveDb().use { db->
        require(targets.size<=256 && plan.campaignUid==activeCampaignRef().campaignId) { "P63:FORMATION_SCOPE" }
        val scope=infrastructureWorldResolutionScope()?:error("P63:FORMATION_SCOPE")
        require(plan.atOrder==null || plan.atOrder in scope.asOfCommittedOrder..Math.addExact(scope.asOfCommittedOrder,1L)) { "P63:STALE_FORMATION_SCOPE" }
        val populations=WorldPopulationStore(db,plan.campaignUid)
        val members=targets.filter { it.kindUid in setOf("GROUP","UNIT") }.flatMap { populations.namedMembers(it) }.distinct()
        val projection=CampaignWorldProjectionStore(db,plan.campaignUid)
        val canonical=members.map { projection.canonicalElement(it.uid)?:error("P63:FORMATION_MEMBER_NOT_DISCLOSED") }
        val admitted=WorldResolutionReadAuthority(scope,{infrastructureWorldResolutionCurrent(scope)}).project(scope,canonical)
        require(admitted.map { it.element }.toSet()==members.toSet()) { "P63:FORMATION_MEMBER_NOT_DISCLOSED" }
        val result=(targets+members).distinct().sortedWith(compareBy<DomainRef>{it.kindUid}.thenBy{it.uid})
        require(result.size<=256) { "P63:FORMATION_READ_BUDGET" }
        result
    }
    internal fun infrastructureWorldProcesses(snapshot:TemporalReadSnapshot,effects:List<VerifiedMechanicsCommandEffect>):TemporalProcessExtension = openGameplaySaveDb().use { db->
        require(snapshot.scope.campaignUid==activeCampaignRef().campaignId && infrastructureTemporalRead().scope==snapshot.scope) { "P63:STALE_PROCESS_PREPARATION" }
        val campaign=snapshot.scope.campaignUid
        val targets=effects.map { it.target }.distinct()
        require(targets.size<=256) { "P63:REFINEMENT_FRONTIER_BUDGET" }
        var sliceStart=System.nanoTime()
        var sliceReads=0
        val jobs=targets.mapNotNull { ref->
            if(sliceReads>=32 || System.nanoTime()-sliceStart>=50_000_000L) {
                Thread.yield();sliceReads=0;sliceStart=System.nanoTime()
                require(infrastructureTemporalRead().scope==snapshot.scope) { "P63:STALE_PROCESS_PREPARATION" }
            }
            sliceReads++
            val actor=MechanicalActorStateStore(db,campaign).actor(ref)?:return@mapNotNull null
            if(actor.materialization==MechanicalStateMaterialization.FULL || Phase50ActorExpansion.preview(db,campaign,actor)==null)return@mapNotNull null
            MechanicalActorExpansion(ref,actor.stateVersion,MechanicalStateMaterialization.FULL)
        }
        require(infrastructureTemporalRead().scope==snapshot.scope) { "P63:STALE_PROCESS_PREPARATION" }
        Phase63WorldProcessOwner(snapshot.scope,Phase63WorldStore(db,campaign).root()?.version?:0,jobs,
            currentScope={infrastructureTemporalRead().scope}).extension()
    }
    /** Explicit selection from a known population is a refinement, not a second NPC roll. */
    internal fun infrastructurePopulationReference(reference:IntentReference,consumers:List<IntentNode>):IntentReference? {
        val shape=WorldReferenceShapeClassifier.classify(reference,consumers)
        if(shape.baseKind!=WorldElementBaseKind.ACTOR)return null
        if(shape.kind==WorldReferenceShapeKind.NAMED_INSTANCE || (shape.quantity?:1)>1 ||
            reference.kind in setOf(IntentReferenceKind.DISCOURSE,IntentReferenceKind.DEICTIC))return null
        val aggregateUid=reference.descriptorHints["aggregate_uid"]
        val populationName=reference.descriptorHints["member_of"]?.trim()?.takeIf { it.isNotBlank() }
        if(aggregateUid==null && populationName==null)return null
        val campaign=activeCampaignRef().campaignId
        val player=activePlayerRef()?:return null
        val anchor=infrastructureEntityLocationUid(player.playerUid)?:return null
        return openGameplaySaveDb().use { db->
            val projection=CampaignWorldProjectionStore(db,campaign)
            val scope=infrastructureWorldResolutionScope()?:return@use null
            val possible=if(aggregateUid!=null)listOfNotNull(projection.canonicalElement(aggregateUid)) else
                projection.searchPlayerVisible(requireNotNull(populationName),WorldReferenceShape(WorldReferenceShapeKind.CATEGORY,
                    WorldElementBaseKind.GROUP,reference.descriptorHints["population_category"]?.let(WorldCategoryVocabulary::canonical),emptySet(),"LOCAL_SITE"),requireAffordances=false)
            val allowed=WorldResolutionReadAuthority(scope,{infrastructureWorldResolutionCurrent(scope)}).project(scope,possible)
                .filter { it.element.kindUid in setOf("GROUP","UNIT") && it.parentAnchorUid==anchor }
            if(allowed.size>1)return@use reference.copy(descriptorHints=reference.descriptorHints+("world_resolution_reason" to "REFERENCE_AMBIGUOUS"))
            val group=allowed.singleOrNull()?:return@use reference.copy(descriptorHints=reference.descriptorHints+("world_resolution_reason" to "P63:KNOWN_POPULATION_REQUIRED"))
            val skeleton=infrastructureWorldSkeletonCandidate()?:return@use null
            val manifest=WorldPopulationStore(db,campaign).candidate(group.element,skeleton)?:return@use null
            val ordinal=((shape.ordinal?:1)-1).toLong()
            if(ordinal !in 0 until manifest.originalCount)return@use null
            val member=manifest.member(ordinal)
            CampaignWorldProjectionStore(db,campaign).canonicalElement(member.uid)?.let { existing->
                if(WorldResolutionReadAuthority(scope,{infrastructureWorldResolutionCurrent(scope)}).project(scope,listOf(existing)).singleOrNull()==null)
                    return@use reference.copy(descriptorHints=reference.descriptorHints+("world_resolution_reason" to "P63:MEMBER_NOT_DISCLOSED"))
                return@use reference.copy(state=IntentReferenceState.RESOLVED_PROJECTED,resolvedProjectedRef=existing.element,
                    candidateProjectedRefs=emptyList(),resolutionEvidenceUid="P63:POPULATION-MEMBER:${manifest.uid}:$ordinal")
            }
            val draft=WorldElementDraft(campaign,member,"${reference.rawPhrase?.take(120)?:group.displayName} ${ordinal+1}",WorldElementBaseKind.ACTOR,
                shape.categoryUid?:group.categoryUid,anchor,shape.affordanceUids,"LOCAL_SITE",WorldEvidenceClassification.GENERATED_PLAUSIBLE,
                listOf(manifest.uid,Phase63PopulationCodec.aggregateEvidence(group.element)),null,null,null,"FULL",ordinal)
            LatentWorldReferenceCodec.attach(reference,draft,WorldFeasibilityDecision(WorldFeasibilityState.FEASIBLE_NEARBY,"P63:EXISTING_POPULATION",anchor,listOf(manifest.uid)))
        }
    }
    internal fun infrastructureWorldPopulationPayload(draft:WorldElementDraft):Map<String,String> {
        val campaign=activeCampaignRef().campaignId
        if(draft.campaignUid!=campaign || draft.baseKind!=WorldElementBaseKind.ACTOR)return emptyMap()
        return openGameplaySaveDb().use { db->
            val skeleton=infrastructureWorldSkeletonCandidate()?:return@use emptyMap()
            val manifest=WorldPopulationStore(db,campaign).candidateForDraft(draft,skeleton)?:return@use emptyMap()
            require(manifest.member(draft.slotOrdinal)==draft.element) { "P63:POPULATION_FOREIGN_MEMBER" }
            val aggregate=requireNotNull(MechanicalActorStateStore(db,campaign).actor(manifest.aggregate))
            require(aggregate.locationRef?.uid==draft.parentAnchorUid ||
                CampaignWorldProjectionStore(db,campaign).canonicalElement(manifest.aggregate.uid)?.parentAnchorUid==draft.parentAnchorUid) { "P63:POPULATION_LOCATION_CHANGED" }
            mapOf("p63_population_manifest" to manifest.uid,"p63_population_ordinal" to draft.slotOrdinal.toString(),"p63_population_version" to aggregate.stateVersion.toString(),
                "p63_population_aggregate_kind" to manifest.aggregate.kindUid,"p63_population_aggregate_uid" to manifest.aggregate.uid)
        }
    }
    internal fun infrastructureWorldActorSeed(draft:WorldElementDraft):String?=infrastructureWorldSkeletonCandidate()?.let { skeleton->
        Phase63ActorGeneration.forDraft(skeleton,draft,MechanicalStateMaterialization.FULL)?.seedUid
    }
    private fun infrastructureWorldContainment(scope:WorldResolutionScope,element:DomainRef):DomainRef? {
        if(!infrastructureWorldResolutionCurrent(scope))return null
        return openGameplaySaveDb().use { db->
            val skeleton=Phase63WorldStore(db,scope.campaignUid).root()?.skeleton?:return@use null
            val projected=CampaignWorldProjectionStore(db,scope.campaignUid).canonicalElement(element.uid)?:return@use null
            if(WorldResolutionReadAuthority(scope,{infrastructureWorldResolutionCurrent(scope)}).project(scope,listOf(projected)).isEmpty())return@use null
            val parent=projected.parentAnchorUid?:return@use null
            val canonical=CampaignWorldProjectionStore(db,scope.campaignUid).canonicalElement(parent)
            if(canonical!=null) {
                if(WorldResolutionReadAuthority(scope,{infrastructureWorldResolutionCurrent(scope)}).project(scope,listOf(canonical)).isEmpty())null
                else WorldTopologyAnchor.canonical(canonical.element)
            } else if(LatentWorldGeography.regions(skeleton).any { it.ref.uid==parent })DomainRef("LOCATION",parent) else null
        }
    }
    internal fun infrastructureNearestWorldElement(candidates:List<CampaignWorldElement>):CampaignWorldElement? {
        require(candidates.size<=512)
        val campaign=activeCampaignRef().campaignId
        val player=activePlayerRef()?:return null
        val actor=DomainRef("PLAYER",player.playerUid)
        val anchor=infrastructureEntityLocationUid(player.playerUid)?:return null
        fun target(element:CampaignWorldElement)=if(element.element.kindUid in setOf("PLACE","LOCATION"))element.element.uid else element.parentAnchorUid
        candidates.filter { target(it)==anchor }.minByOrNull { it.element.uid }?.let { return it }
        val destinations=candidates.mapNotNull { target(it)?.let { uid->DomainRef("LOCATION",uid) } }.toSet()
        if(destinations.isEmpty())return null
        return openGameplaySaveDb().use { db->
            val snapshot=infrastructureTemporalRead()
            val skeleton=infrastructureWorldSkeletonCandidate()?:return@use null
            val scope=WorldResolutionScope(campaign,HistoryGenerationUid(snapshot.scope.historyGenerationUid),snapshot.scope.baseCommitOrder,player.playerUid,
                VisibilityPurposeKinds.GAMEPLAY_NARRATION,mapOf(skeleton.ruleSource.uid to skeleton.ruleSource.version))
            val body=MechanicalActorStateStore(db,campaign).actor(actor)?:return@use null
            val topology=AuthorizedWorldTopology(WorldTopologyEdgeReadPort { c,origin->Phase63WorldStore(db,c).edgesFrom(origin)+
                SqliteNpcTravelRoutePort(db).routes(c,actor,origin).map { route->WorldTopologyEdge(route.routeUid,route.version.toLong(),
                    route.origin,route.destination,route.duration,route.resourceCosts.filterValues { it>0 },route.requiredCapabilities,WorldTimeTick(Long.MIN_VALUE),null,
                    "P62:ROUTE:${route.fingerprint}") } },
                object:WorldTopologyAuthorizationPort {
                    override fun current(scope:WorldResolutionScope)=infrastructureWorldResolutionCurrent(scope)
                    override fun permitted(scope:WorldResolutionScope,edge:WorldTopologyEdge,at:WorldTimeTick)=
                        infrastructureWorldRouteKnown(db,campaign,actor,edge,scope.asOfCommittedOrder)
                },pathPermitted={ plan->plan.edges.all { body.executableAbilityUids.containsAll(it.requiredCapabilities) } &&
                    plan.resourceCosts.all { (uid,cost)->body.resources.singleOrNull { it.resourceUid==uid }?.current?.let { it>=cost }==true } },
                containmentRead=WorldTopologyContainmentPort(::infrastructureWorldContainment))
            val route=topology.closest(scope,DomainRef("LOCATION",anchor),destinations,snapshot.state.time) as? WorldResolutionResult.Journey?:return@use null
            candidates.filter { target(it)==route.element.uid }.minByOrNull { it.element.uid }
        }
    }
    internal fun infrastructureNearestProjectedWorldRef(candidates:List<DomainRef>):DomainRef? {
        require(candidates.size<=512)
        val campaign=activeCampaignRef().campaignId
        val elements=openGameplaySaveDb().use { db->candidates.mapNotNull { ref->
            CampaignWorldProjectionStore(db,campaign).canonicalElement(ref.uid)?.takeIf { it.element.kindUid==ref.kindUid ||
                WorldTopologyAnchor.same(it.element,ref) }?:when(ref.kindUid) {
                "PLACE","LOCATION"->CampaignWorldElement(ref,ref.uid,"WORLD_PACK_LOCATION",null,emptySet(),"WORLD_PACK",WorldEvidenceClassification.SOURCE_CANON)
                "NPC","ACTOR","GROUP","UNIT"->infrastructureEntityLocationUid(ref.uid)?.let { anchor->CampaignWorldElement(ref,ref.uid,"EXISTING_ACTOR",anchor,
                    emptySet(),"LOCAL_SITE",WorldEvidenceClassification.CAMPAIGN_FACT) }
                else->null
            }
        } }
        val selected=infrastructureNearestWorldElement(elements)?.element?:return null
        return candidates.singleOrNull { it==selected || WorldTopologyAnchor.same(it,selected) }
    }
    internal fun infrastructureWorldDiagnostics():Map<String,Any?> = openGameplaySaveDb().use { db->
        val campaign=activeCampaignRef().campaignId
        val player=activePlayerRef()
        val root=Phase63WorldStore(db,campaign).root()
        val anchor=player?.let { infrastructureEntityLocationUid(it.playerUid) }
        val order=TurnTransactionReceiptStore(db).lastValidCommit(campaign)?.commitOrder?:0L
        val actor=player?.let { DomainRef("PLAYER",it.playerUid) }
        val edges=if(anchor==null || actor==null)emptyList() else Phase63WorldStore(db,campaign).edgesFrom(DomainRef("LOCATION",anchor))
            .filter { infrastructureWorldRouteKnown(db,campaign,actor,it,order) }
        val public=CampaignWorldProjectionStore(db,campaign).canonicalPublicElements(128)
        val populations=WorldPopulationStore(db,campaign)
        val knownPopulations=public.filter { it.element.kindUid in setOf("GROUP","UNIT") }.mapNotNull { element->
            val manifest=populations.forAggregate(element.element)?:return@mapNotNull null
            val body=MechanicalActorStateStore(db,campaign).actor(element.element)?:return@mapNotNull null
            mapOf("aggregate_uid" to element.element.uid,"manifest_uid" to manifest.uid,"original_count" to manifest.originalCount,
                "anonymous_count" to body.aggregatePopulation?.totalCount,"named_count" to Phase50PopulationPartition.namedCount(db,campaign,element.element),
                "named_members" to populations.namedOrdinals(manifest.uid).map { manifest.member(it).uid },
                "processing_lod" to WorldLodPolicy.level(WorldLodInterest(element.element,formation=element.element.kindUid=="UNIT")).name)
        }
        val temporal=Phase60TemporalStateStore(db,campaign).read()
        mapOf("campaign_uid" to campaign,"initialized" to (root!=null),"world_version" to root?.version,
            "source_kind" to root?.skeleton?.ruleSource?.kind?.name,"source_version" to root?.skeleton?.ruleSource?.version,
            "skeleton_fingerprint" to root?.skeleton?.fingerprint,"history_generation" to HistoryGenerationStore(db,campaign).current().value,
            "as_of_order" to order,"current_anchor_uid" to anchor,"generator_version" to root?.skeleton?.generatorVersion,
            "known_connections" to edges.map { edge->mapOf("uid" to edge.uid,"version" to edge.version,
                "origin_uid" to edge.origin.uid,"destination_uid" to edge.destination.uid,"duration_ms" to edge.duration.milliseconds) },
            "active_player_lod" to player?.let { WorldLodPolicy.level(WorldLodInterest(DomainRef("PLAYER",it.playerUid),activePlayer=true)).name },
            "known_populations" to knownPopulations,"diagnostic_element_limit" to 128,
            "world_process_deadlines" to temporal.deadlines.count { it.ownerUid==Phase63WorldProcessOwner.OWNER },
            "world_process_state" to temporal.processStates.singleOrNull { it.ownerUid==Phase63WorldProcessOwner.OWNER }?.canonicalValue)
    }
    internal fun infrastructureWorldPreview(phrase:String,kind:WorldElementBaseKind,category:String?,affordances:Set<String>):UniversalWorldReferenceResolution {
        require(phrase.isNotBlank() && phrase.length<=256 && affordances.size<=16)
        val campaign=activeCampaignRef().campaignId
        val reference=IntentReference("P63:PREVIEW",IntentReferenceKind.DESCRIPTIVE,phrase,"TARGET",
            setOf(kind.name),buildMap { category?.let { put("category",it) };if(affordances.isNotEmpty())put("affordances",affordances.sorted().joinToString(",")) })
        val player=activePlayerRef()?:return UniversalWorldReferenceResolution.Unresolved("P63:ACTIVE_PLAYER_REQUIRED")
        // No Scout, model or write is involved in a laboratory preview.
        return UniversalWorldMaterializationResolver().resolve(campaign,reference,emptyList(),infrastructureEntityLocationUid(player.playerUid),
            infrastructureWorldElements(reference,emptyList()),null,skeleton=infrastructureWorldSkeletonCandidate())
    }
    internal fun infrastructureWorldActorReference(uid:String):DomainRef {
        if(infrastructureWorldPackAuthority().binding.sourceKind==CampaignRuleSourceKind.CAMPAIGN_NATIVE)return DomainRef("ACTOR",uid)
        return DomainRef("NPC",uid)
    }
    internal fun infrastructureMechanicalActorCandidate(plan:CanonicalTurnPlan,ref:DomainRef):MechanicalActorView? {
        infrastructureMechanicalActor(ref)?.let { actor->
            if(actor.materialization==MechanicalStateMaterialization.FULL)return actor
            return openGameplaySaveDb().use { Phase50ActorExpansion.preview(it,plan.campaignUid,actor) }
        }
        if(plan.campaignUid!=activeCampaignRef().campaignId || ref.kindUid !in setOf("ACTOR","GROUP"))return null
        val draft=plan.intent.references.mapNotNull { LatentWorldReferenceCodec.decode(plan.campaignUid,it) }.singleOrNull { it.element==ref }?:return null
        openGameplaySaveDb().use { db->
            val skeleton=infrastructureWorldSkeletonCandidate()?:return null
            val manifest=WorldPopulationStore(db,plan.campaignUid).candidateForDraft(draft,skeleton)
            if(manifest!=null && manifest.member(draft.slotOrdinal)==ref)
                return Phase50PopulationPartition.preview(db,plan.campaignUid,manifest,draft.slotOrdinal)
        }
        val skeleton=infrastructureWorldSkeletonCandidate()?:return null
        val seed=Phase63ActorGeneration.forDraft(skeleton,draft,MechanicalStateMaterialization.FULL)?:return null
        return MechanicalActorView(plan.campaignUid,ref,seed.kind,0,seed.materialization,seed.attributes,seed.resources,seed.abilities,
            locationRef=draft.parentAnchorUid?.let { DomainRef("LOCATION",it) },generationProvenanceUid=seed.provenanceUid,
            aggregatePopulation=seed.aggregateCount?.let { AggregateMechanicalPopulation(it,it) })
    }
    internal fun infrastructureWorldTravel(request:MechanicsEffectRequest,context:MechanicsResolutionContext):MechanicsEffectResolution {
        fun reject(reason:String)=MechanicsEffectResolution.Rejected("P63:$reason")
        val campaign=activeCampaignRef().campaignId
        if(context.campaignUid!=campaign || context.npcAuthorization!=null)return reject("TRAVEL_SCOPE")
        val player=activePlayerRef()?:return reject("PLAYER_REQUIRED")
        if(context.plan.intent.actor!=CommandActorRef("PLAYER",player.playerUid))return reject("PLAYER_VOLITION_REQUIRED")
        val destination=request.targetProjectedRef?.takeIf { it.kindUid in setOf("PLACE","LOCATION") }?:return reject("DESTINATION_REQUIRED")
        return CampaignRuntimeLifecycleLock.withTurn(campaign) { openGameplaySaveDb().use { db->
            val snapshot=infrastructureTemporalRead()
            val skeleton=infrastructureWorldSkeletonCandidate()?:return@use reject("WORLD_ROOT_REQUIRED")
            val anchor=infrastructureEntityLocationUid(player.playerUid)?:return@use reject("TRAVEL_ORIGIN_UNKNOWN")
            val actorRef=DomainRef("PLAYER",player.playerUid)
            val canonical=MechanicalActorStateStore(db,campaign).actor(actorRef)?:MechanicalActorView(campaign,actorRef,
                MechanicalActorKind.ACTIVE_PLAYER,0,MechanicalStateMaterialization.FULL,emptyMap(),
                infrastructurePlayerResources().map { MechanicalResource(it.resourceUid,it.currentValue.roundToLong().coerceAtLeast(0),it.currentValue.roundToLong().coerceAtLeast(0)) },
                emptySet(),locationRef=DomainRef("LOCATION",anchor),generationProvenanceUid="PLAYER-DOMAIN:${player.playerUid}")
            val actor=StagedMechanicalProjection.actor(canonical,context.stagedEffects)
            val scope=WorldResolutionScope(campaign,HistoryGenerationUid(snapshot.scope.historyGenerationUid),snapshot.scope.baseCommitOrder,
                player.playerUid,VisibilityPurposeKinds.GAMEPLAY_NARRATION,mapOf(skeleton.ruleSource.uid to skeleton.ruleSource.version))
            val drafts=context.plan.intent.references.mapNotNull { LatentWorldReferenceCodec.decode(campaign,it) }
                .filter { it.element==destination && it.parentAnchorUid==anchor }
            val proposed=drafts.flatMap { CoreLatentWorldRules.localEdges(skeleton,it,snapshot.state.time) }
            val topology=AuthorizedWorldTopology(WorldTopologyEdgeReadPort { _,origin->
                (Phase63WorldStore(db,campaign).edgesFrom(origin)+proposed.filter { it.origin==origin }+
                    SqliteNpcTravelRoutePort(db).routes(campaign,actorRef,origin).map { route->WorldTopologyEdge(route.routeUid,route.version.toLong(),
                        route.origin,route.destination,route.duration,route.resourceCosts.filterValues { it>0 },route.requiredCapabilities,WorldTimeTick(Long.MIN_VALUE),null,
                        "P62:ROUTE:${route.fingerprint}") }).distinctBy { it.uid }
            },object:WorldTopologyAuthorizationPort {
                override fun current(s:WorldResolutionScope)=s==scope && infrastructureWorldResolutionCurrent(s)
                override fun permitted(s:WorldResolutionScope,edge:WorldTopologyEdge,at:WorldTimeTick):Boolean {
                    if(edge in proposed)return true // Registered visible local path; acquired in the same eventual commit.
                    return infrastructureWorldRouteKnown(db,campaign,actorRef,edge,scope.asOfCommittedOrder)
                }
            },pathPermitted={ plan->plan.edges.all { actor.executableAbilityUids.containsAll(it.requiredCapabilities) } &&
                plan.resourceCosts.all { (uid,cost)->actor.resources.singleOrNull { it.resourceUid==uid }?.current?.let { it>=cost }==true } },
                containmentRead=WorldTopologyContainmentPort(::infrastructureWorldContainment))
            when(val result=topology.travel(scope,DomainRef("LOCATION",anchor),DomainRef("LOCATION",destination.uid),snapshot.state.time)) {
                is WorldResolutionResult.Journey->WorldTravelMechanics.verified(request,context,actor,result.plan)
                is WorldResolutionResult.Unavailable->MechanicsEffectResolution.Rejected(result.reasonUid)
                is WorldResolutionResult.Clarification->MechanicsEffectResolution.Rejected(result.reasonUid)
                else->reject("TRAVEL_ROUTE_REQUIRED")
            }
        } }
    }
    internal fun infrastructureActiveMemoryArtifacts(
        asOfOrder:Long=Long.MAX_VALUE,
        revisionUids:Set<String> = emptySet()
    ):List<ActiveMemoryArtifactRevision> = openGameplaySaveDb().use{db->
        require(asOfOrder>=0)
        val campaign=activeCampaignRef().campaignId
        val generation=HistoryGenerationStore(db,campaign).current()
        if(revisionUids.isNotEmpty())return@use MemoryArtifactStore(db).activeCleanRevisionsByUid(
            campaign,generation,revisionUids,asOfOrder
        )
        db.rawQuery("""SELECT logical_artifact_uid,artifact_revision_uid,artifact_kind_uid,
            source_leaf_set_fingerprint,derivation_version,as_of_committed_order,payload_json
            FROM ${Phase55To58MemorySchema.ARTIFACTS}
            WHERE campaign_uid=? AND history_generation_uid=? AND status_uid=? AND as_of_committed_order<=?
            ORDER BY as_of_committed_order,artifact_revision_uid""",arrayOf(
            campaign,generation.value,MemoryArtifactStatus.CLEAN.name,asOfOrder.toString()
        )).use{cursor->buildList{
            while(cursor.moveToNext()){
                val revision=cursor.getString(1)
                add(ActiveMemoryArtifactRevision(
                    campaign,generation,cursor.getString(0),revision,
                    MemoryArtifactKind.valueOf(cursor.getString(2)),cursor.getString(3),cursor.getLong(4),
                    cursor.getLong(5),cursor.getString(6)
                ))
            }
        }}
    }
    internal fun infrastructureActiveMemoryArtifactsPage(
        afterAsOfOrder:Long,afterRevisionUid:String,limit:Int,asOfOrder:Long=Long.MAX_VALUE
    ):List<ActiveMemoryArtifactRevision> = openGameplaySaveDb().use{db->
        require(afterAsOfOrder>=-1&&limit in 1..256&&asOfOrder>=0)
        val campaign=activeCampaignRef().campaignId
        val generation=HistoryGenerationStore(db,campaign).current()
        db.rawQuery("""SELECT logical_artifact_uid,artifact_revision_uid,artifact_kind_uid,
            source_leaf_set_fingerprint,derivation_version,as_of_committed_order,payload_json
            FROM ${Phase55To58MemorySchema.ARTIFACTS}
            WHERE campaign_uid=? AND history_generation_uid=? AND status_uid=? AND as_of_committed_order<=?
              AND (as_of_committed_order>? OR (as_of_committed_order=? AND artifact_revision_uid>?))
            ORDER BY as_of_committed_order,artifact_revision_uid LIMIT ?""",arrayOf(
            campaign,generation.value,MemoryArtifactStatus.CLEAN.name,asOfOrder.toString(),
            afterAsOfOrder.toString(),afterAsOfOrder.toString(),afterRevisionUid,limit.toString()
        )).use{cursor->buildList{while(cursor.moveToNext())add(ActiveMemoryArtifactRevision(
            campaign,generation,cursor.getString(0),cursor.getString(1),
            MemoryArtifactKind.valueOf(cursor.getString(2)),cursor.getString(3),cursor.getLong(4),
            cursor.getLong(5),cursor.getString(6)
        ))}}
    }
    internal fun infrastructureWorldPackAuthority():CurrentWorldPackAuthority = CampaignSelectionManager(context).currentWorldPackAuthority()
    internal fun infrastructureMechanicalPersistence(entityUid:String):InfrastructureMechanicalPersistence = openGameplaySaveDb().use{db->
        val effects=mutableListOf<Pair<String,Long>>();var version=0L
        db.rawQuery(
            "SELECT effect_key,magnitude,started_chapter FROM active_combat_effects WHERE entity_uid=? AND status='active' ORDER BY started_chapter,active_effect_uid",
            arrayOf(entityUid)
        ).use{cursor->while(cursor.moveToNext()){
            effects+=cursor.getString(0) to cursor.getDouble(1).roundToLong()
            version=maxOf(version,cursor.getLong(2))
        }}
        val position=db.rawQuery(
            "SELECT location_uid,x_coord,y_coord,updated_chapter FROM entity_positions WHERE entity_uid=? LIMIT 1",
            arrayOf(entityUid)
        ).use{cursor->
            if(!cursor.moveToFirst())null else{
                version=maxOf(version,if(cursor.isNull(3))0L else cursor.getLong(3))
                when{
                    !cursor.isNull(1)&&!cursor.isNull(2)->CombatPosition.Exact(cursor.getDouble(1).roundToLong(),cursor.getDouble(2).roundToLong())
                    !cursor.isNull(0)&&cursor.getString(0).isNotBlank()->CombatPosition.Zone(cursor.getString(0))
                    else->null
                }
            }
        }
        InfrastructureMechanicalPersistence(effects,position,version)
    }
    internal fun infrastructureMechanicalActor(ref:DomainRef):MechanicalActorView? =
        openGameplaySaveDb().use{MechanicalActorStateStore(it,activeCampaignRef().campaignId).actor(ref)}
    internal fun infrastructureAggregatePopulation(ref:DomainRef):AggregateMechanicalPopulation? =
        openGameplaySaveDb().use{MechanicalActorStateStore(it,activeCampaignRef().campaignId).population(ref)}
    internal fun infrastructureAggregateTargets(phrase:String):List<Pair<String,DomainRef>> =
        openGameplaySaveDb().use{MechanicalActorStateStore(it,activeCampaignRef().campaignId).aggregateTargets(phrase)}
    internal fun infrastructureEntityLocationUid(entityUid:String):String?=openGameplaySaveDb().use{db->
        db.rawQuery("SELECT location_uid FROM entity_positions WHERE entity_uid=? LIMIT 1",arrayOf(entityUid)).use{cursor->
            if(cursor.moveToFirst()&&!cursor.isNull(0))cursor.getString(0)?.takeIf(String::isNotBlank) else null
        }
    }
    internal fun infrastructureEntitySceneAnchorUid(entityUid:String):String?=openGameplaySaveDb().use{db->
        val direct=db.rawQuery("SELECT location_uid FROM entity_positions WHERE entity_uid=? LIMIT 1",arrayOf(entityUid)).use{cursor->
            if(cursor.moveToFirst()&&!cursor.isNull(0))cursor.getString(0)?.takeIf(String::isNotBlank) else null
        }
        canonicalCampaignSceneAnchor(direct){uid->
            CampaignWorldProjectionStore(db,activeCampaignRef().campaignId).canonicalElement(uid)?.let {
                CampaignSceneParent(it.element.kindUid,it.parentAnchorUid)
            }
        }
    }
    internal fun infrastructureEntityScenePathUids(entityUid:String):List<String> = openGameplaySaveDb().use{db->
        val direct=db.rawQuery("SELECT location_uid FROM entity_positions WHERE entity_uid=? LIMIT 1",arrayOf(entityUid)).use{cursor->
            if(cursor.moveToFirst()&&!cursor.isNull(0))cursor.getString(0)?.takeIf(String::isNotBlank) else null
        }
        canonicalCampaignScenePath(direct){uid->
            CampaignWorldProjectionStore(db,activeCampaignRef().campaignId).canonicalElement(uid)?.let {
                CampaignSceneParent(it.element.kindUid,it.parentAnchorUid)
            }
        }
    }
    internal fun infrastructureWorldResolutionScope():WorldResolutionScope? {
        val player=activePlayerRef()?:return null
        val snapshot=infrastructureTemporalRead()
        val source=infrastructureWorldSkeletonCandidate()?.ruleSource?:infrastructureWorldPackAuthority().binding.ruleSource
        return WorldResolutionScope(snapshot.scope.campaignUid,HistoryGenerationUid(snapshot.scope.historyGenerationUid),snapshot.scope.baseCommitOrder,
            player.playerUid,VisibilityPurposeKinds.GAMEPLAY_NARRATION,mapOf(source.uid to source.version))
    }
    /** A route traversal checks the commit/generation/source fences, not a full canonical hash
     * for every edge. Gameplay mutations can only change them together in one transaction. */
    internal fun infrastructureWorldResolutionCurrent(scope:WorldResolutionScope):Boolean {
        if(activeCampaignRef().campaignId!=scope.campaignUid || activePlayerRef()?.playerUid!=scope.principalUid ||
            scope.purposeUid!=VisibilityPurposeKinds.GAMEPLAY_NARRATION)return false
        return openGameplaySaveDb().use { db->
            if(HistoryGenerationStore(db,scope.campaignUid).current()!=scope.historyGenerationUid ||
                (TurnTransactionReceiptStore(db).lastValidCommit(scope.campaignUid)?.commitOrder?:0L)!=scope.asOfCommittedOrder)return@use false
            val source=Phase63WorldStore(db,scope.campaignUid).root()?.skeleton?.ruleSource?:infrastructureWorldPackAuthority().binding.ruleSource
            scope.sourceVersions==mapOf(source.uid to source.version)
        }
    }
    internal fun infrastructureEstablishedWorldSlot(scope:WorldResolutionScope,ref:DomainRef):CampaignWorldElement? = openGameplaySaveDb().use { db->
        if(!infrastructureWorldResolutionCurrent(scope))return@use null
        val canonical=CampaignWorldProjectionStore(db,scope.campaignUid).canonicalElement(ref.uid)?:return@use null
        if(canonical.element!=ref)return@use null
        WorldResolutionReadAuthority(scope,{infrastructureWorldResolutionCurrent(scope)}).project(scope,listOf(canonical)).singleOrNull()
    }
    internal fun infrastructureWorldElements(reference:IntentReference,consumers:List<IntentNode>,scope:WorldResolutionScope?=infrastructureWorldResolutionScope()):List<CampaignWorldElement> =
        openGameplaySaveDb().use{db->
            if(scope==null || !infrastructureWorldResolutionCurrent(scope))return@use emptyList()
            val phrase=(reference.rawPhrase?:reference.descriptorHints["surface"]).orEmpty().trim()
            val shape=WorldReferenceShapeClassifier.classify(reference,consumers)
            val found=if(phrase.isBlank())emptyList() else CampaignWorldProjectionStore(db,scope.campaignUid)
                .searchPlayerVisible(phrase,shape,requireAffordances=reference.kind !in setOf(IntentReferenceKind.DISCOURSE,IntentReferenceKind.DEICTIC) && shape.kind!=WorldReferenceShapeKind.ROLE)
            WorldResolutionReadAuthority(scope,{infrastructureWorldResolutionCurrent(scope)}).project(scope,found)
        }

    /** Only the latest committed exchange heard by the current PC supplies this identity anchor.
     * No host memory, global narrative search, hidden holder or previous history generation. */
    internal fun infrastructureRecentInterlocutors():Set<DomainRef> {
        val campaign=activeCampaignRef().campaignId
        return CampaignRuntimeLifecycleLock.withTurn(campaign) {
            val player=activePlayerRef()?.playerUid?:return@withTurn emptySet()
            openGameplaySaveDb().use { db ->
                val receipt=TurnTransactionReceiptStore(db).lastValidCommit(campaign)?:return@use emptySet()
                val order=receipt.commitOrder?:return@use emptySet()
                val replay=CommittedReplayPayloadStore(db).atOrders(campaign,setOf(order)).singleOrNull()
                    ?.takeIf{it.identity.transactionUid==receipt.transactionUid}?:return@use emptySet()
                NpcCommunicationMemory.heardInterlocutors(campaign,player,replay.changeSet.changes)
            }
        }
    }

    private fun requireActiveVisibility(audience:AudienceContext,purpose:PurposeContext) {
        val campaign=activeCampaignRef().campaignId
        if(audience.campaignUid!=campaign||purpose.campaignUid!=campaign) throw VisibilityAuthorityFailure.CrossCampaign()
    }

    override fun commitTurn(
        identity: TurnTransactionIdentity,
        proposal: CanonicalCampaignMutationProposal,
        failureInjector: TurnFailureInjector
    ): TurnExecutionResult<TurnCommitAppliedResult> {
        val result=commitTurnWithoutMaintenance(identity,proposal,failureInjector)
        finishTurnMaintenance(identity)
        return result
    }

    private fun commitTurnWithoutMaintenance(identity:TurnTransactionIdentity,
                                            proposal:CanonicalCampaignMutationProposal,
                                            failureInjector:TurnFailureInjector):TurnExecutionResult<TurnCommitAppliedResult> =
        openGameplaySaveDb().use { db ->TurnTransactionBoundary.create(db, identity, proposal, failureInjector).commit()}

    private fun finishTurnMaintenance(identity:TurnTransactionIdentity) {
        runCatching{store.ensureUndoCheckpointAfterCommit()}
            .onFailure{DiagnosticLogger.log(context,"UNDO_BASELINE_CHECKPOINT_FAILED",it)}
        scheduleMemoryConsolidation(identity.campaignUid)
    }

    private fun scheduleMemoryConsolidation(campaignUid:String)=scheduleMemoryCatchUp(campaignUid,"PHASE58_POST_COMMIT_FAILED")

    private fun scheduleMemoryCatchUp(
        campaignUid:String=activeCampaignRef().campaignId,
        failureCode:String="PHASE58_OPEN_CATCHUP_FAILED"
    ){
        memoryExecutor.execute{
            // Storage replacement (restore, campaign switch, destructive undo) owns the same
            // process-wide gate. It therefore waits for the bounded <=500ms consolidation slice
            // and prevents queued workers from opening an obsolete campaign.db/WAL handle.
            SemanticCampaignTransitionRegistry.withSemanticRuntimeAccess{
                var continueCatchUp=false
                var consolidationCommitted=false
                runCatching{
                    if(activeCampaignRef().campaignId!=campaignUid)return@runCatching
                    openGameplaySaveDb().use{db->
                        val generationAtOpen=HistoryGenerationStore(db,campaignUid).current()
                        val pending=CanonicalMemoryLeafProjection.pending(db,campaignUid,256)
                        if(pending.isEmpty()&&!CanonicalMemoryLeafProjection.hasPendingResume(db,campaignUid))return@use
                        val receipt=Phase58MemoryConsolidation(
                            db=db,campaignUid=campaignUid,enrichmentPort=memoryEnrichmentPort
                        ).open(pending,"pl-PL")
                        check(HistoryGenerationStore(db,campaignUid).current()==generationAtOpen){
                            "RPGOS-MEMORY:HISTORY_GENERATION_CHANGED_DURING_CONSOLIDATION"
                        }
                        if(receipt.status==ConsolidationStatus.COMMITTED){
                            consolidationCommitted=true
                            continueCatchUp=receipt.resumeCursor!=null||CanonicalMemoryLeafProjection.pending(db,campaignUid,1).isNotEmpty()
                        }
                    }
                }.onFailure{DiagnosticLogger.log(context,failureCode,it)}
                if(consolidationCommitted&&activeCampaignRef().campaignId==campaignUid){
                    runCatching{memoryConsolidationListener?.invoke()}
                        .onFailure{DiagnosticLogger.log(context,"PHASE59_MEMORY_INDEX_SIGNAL_FAILED",it)}
                }
                if(continueCatchUp&&activeCampaignRef().campaignId==campaignUid){
                    scheduleMemoryCatchUp(campaignUid,failureCode)
                }
            }
        }
    }

    override fun buildContext(playerInput: String, chapter: Int, audience: AudienceContext, purpose: PurposeContext): ContextBundle =
        store.buildContext(playerInput, chapter, audience, purpose)
    internal fun infrastructureBuildTrustedContext(playerInput:String,chapter:Int,audience:AudienceContext,purpose:PurposeContext,trusted:TrustedPrincipalContext):ContextBundle =
        store.buildTrustedContext(playerInput,chapter,audience,purpose,trusted)
    internal fun infrastructureIssueWorldActorEventSignal(
        event:WorldEventItem,evidence:Map<String,Any?>,quality:Double=1.0,
        uncertainty:PerceptionUncertainty=PerceptionUncertainty(1.0,1.0,1.0),presentedSubject:VisibilitySubjectRef?=null
    ):PerceptionSignal = store.issueWorldActorEventSignal(event,evidence,quality,uncertainty,presentedSubject)
    internal fun infrastructureIssueWorldActorEventCapability(
        audience:AudienceContext,minimumDetectionQuality:Double=0.0,
        maximumDisclosure:DisclosureLevel=DisclosureLevel.DISCLOSE_FULL,capabilityUid:String="WORLD_EVENT:${audience.principal?.kindUid}:${audience.principal?.uid}"
    ):PerceptionCapability = store.issueWorldActorEventCapability(audience,minimumDetectionQuality,maximumDisclosure,capabilityUid)
    internal fun infrastructureClearWorldActorPerception() = store.clearWorldActorPerception()
    override fun fullCharacterPanel(audience: AudienceContext, purpose: PurposeContext): CharacterPanelSnapshot =
        store.fullCharacterPanel(audience, purpose)
    override fun status(): StatusSnapshot = store.status()
    override fun time(): TimeSnapshot = store.time()
    override fun chronicle(): List<ChronicleEntry> = store.chronicle()

    override fun truthRecords(
        audience: AudienceContext,
        purpose: PurposeContext,
        kind: TruthKind?,
        subjectUid: String?,
        perspectiveUid: String?,
        limit: Int
    ): VisibilityProjection<List<CampaignTruthRecord>> {
        val campaign = activeCampaignRef().campaignId
        val request = VisibilityRequest(audience, purpose, VisibilitySubjectRef(campaign, VisibilitySubjectKinds.CAMPAIGN_TRUTH, "CAMPAIGN_TRUTH_RECORDS"))
        return protectedReads().truthFiltered(audience,purpose,kind,subjectUid,perspectiveUid,limit).toVisibilityProjection(request)
    }

    override fun canonDivergences(audience: AudienceContext, purpose: PurposeContext): VisibilityProjection<List<CanonDivergenceRecord>> {
        val campaign = activeCampaignRef().campaignId
        val request = VisibilityRequest(audience, purpose, VisibilitySubjectRef(campaign, VisibilitySubjectKinds.CANON_DIVERGENCE, "CANON_DIVERGENCES"))
        return protectedReads().canonDivergences(audience,purpose).toVisibilityProjection(request)
    }

    override fun npcsProjection(search:String,audience:AudienceContext,purpose:PurposeContext):VisibilityProjection<List<NpcListItem>> {
        requireActiveVisibility(audience,purpose)
        return infrastructureOpenWorldDb().use{world->openGameplaySaveDb().use{save->NpcWorldDashboardReader(world,save).npcsProjection(search,audience,purpose)}}
    }
    override fun npcDetailProjection(uid:String,audience:AudienceContext,purpose:PurposeContext):NpcDetailProtectedProjection {
        requireActiveVisibility(audience,purpose)
        return infrastructureOpenWorldDb().use{world->openGameplaySaveDb().use{save->NpcWorldDashboardReader(world,save).npcDetailProjection(uid,audience,purpose)}}
    }
    override fun relationEdgesProjection(audience:AudienceContext,purpose:PurposeContext):VisibilityProjection<List<RelationEdge>> {
        requireActiveVisibility(audience,purpose)
        return infrastructureOpenWorldDb().use{world->openGameplaySaveDb().use{save->NpcWorldDashboardReader(world,save).relationEdgesProjection(audience,purpose)}}
    }
    override fun economiesProjection(audience:AudienceContext,purpose:PurposeContext):VisibilityProjection<List<EconomySummary>> {
        requireActiveVisibility(audience,purpose)
        return infrastructureOpenWorldDb().use{world->openGameplaySaveDb().use{save->NpcWorldDashboardReader(world,save).economiesProjection(audience,purpose)}}
    }
    override fun warsProjection(audience:AudienceContext,purpose:PurposeContext):VisibilityProjection<List<WarSummary>> {
        requireActiveVisibility(audience,purpose)
        return infrastructureOpenWorldDb().use{world->openGameplaySaveDb().use{save->NpcWorldDashboardReader(world,save).warsProjection(audience,purpose)}}
    }
    override fun relationshipsProjection(audience:AudienceContext,purpose:PurposeContext):VisibilityProjection<List<RelationshipItem>> {
        requireActiveVisibility(audience,purpose)
        return infrastructureOpenWorldDb().use{world->openGameplaySaveDb().use{save->SocialReader(world,save).relationshipsProjection(audience,purpose)}}
    }
    override fun organizationsProjection(audience:AudienceContext,purpose:PurposeContext):VisibilityProjection<List<OrganizationItem>> {
        requireActiveVisibility(audience,purpose)
        return infrastructureOpenWorldDb().use{world->openGameplaySaveDb().use{save->SocialReader(world,save).organizationsProjection(audience,purpose)}}
    }
    override fun politicsProjection(audience:AudienceContext,purpose:PurposeContext):VisibilityProjection<List<PoliticalItem>> {
        requireActiveVisibility(audience,purpose)
        return infrastructureOpenWorldDb().use{world->openGameplaySaveDb().use{save->SocialReader(world,save).politicsProjection(audience,purpose)}}
    }
    override fun syncCheck(): SyncCheckResult = store.syncCheck()
    override fun dbTables(): List<DbTableInfo> = store.dbTables()
    override fun diagnostics(contextSummary: String): DiagnosticsSnapshot = store.diagnostics(contextSummary)
    override fun worldRegions(): List<WorldRegionItem> = store.worldRegions()
    override fun worldLocations(search: String): List<WorldLocationItem> = store.worldLocations(search)
    override fun activeWorldEventsProjection(audience:AudienceContext,purpose:PurposeContext):VisibilityProjection<List<WorldEventItem>> {
        requireActiveVisibility(audience,purpose)
        return infrastructureOpenWorldDb().use{world->openGameplaySaveDb().use{save->WorldReader(world,save).activeEventsProjection(audience,purpose)}}
    }
    override fun techniqueBrowser(search: String): List<TechniqueBrowserItem> = store.techniqueBrowser(search)
    override fun missionBrowser(): List<MissionBrowserItem> = store.missionBrowser()
    override fun visualLibrary(): List<VisualRecord> = store.visualLibrary()
    override fun addVisual(title: String, kind: String, uri: String, chapter: Int?, relatedEntityUid: String?, relatedLocationUid: String?, prompt: String?, revisedPrompt: String?, sourceVisualUid: String?): String = store.addVisual(
        title = title, kind = kind, uri = uri, chapter = chapter, relatedEntityUid = relatedEntityUid, relatedLocationUid = relatedLocationUid,
        prompt = prompt, revisedPrompt = revisedPrompt, sourceVisualUid = sourceVisualUid
    )
    override fun packageManager(): RpgPackageManager = store.packageManager()
    override fun backups(): List<String> = store.backups()
    override fun restoreBackup(path: String): String = store.restoreBackup(path)
    override fun createSnapshot(kind: SnapshotKind, pinned: Boolean): CampaignSnapshotDescriptor = store.createSnapshot(kind, pinned)
    override fun snapshots(): List<CampaignSnapshotDescriptor> = store.snapshots()
    override fun restoreLatestSnapshot(): String = store.restoreLatestSnapshot()
    override fun previewUndoLastTurn():UndoPreview=store.previewUndoLastTurn()
    override fun confirmUndoLastTurn(previewToken:String):DestructiveUndoResult=store.confirmUndoLastTurn(previewToken)
    override fun finalizeChapter(chapter: Int, title: String): Pair<String, String> = store.finalizeChapter(chapter, title)
}

internal data class CampaignSceneParent(val elementKindUid:String,val parentAnchorUid:String?)

/** Resolves nested scene elements (actor -> activity -> place) without inventing topology. */
internal fun canonicalCampaignSceneAnchor(
    directAnchorUid:String?,
    parentOf:(String)->CampaignSceneParent?
):String?{
    var current=directAnchorUid?.takeIf(String::isNotBlank)?:return null
    val seen=linkedSetOf<String>()
    repeat(32){
        if(!seen.add(current))return null
        val row=parentOf(current)?:return current
        if(row.elementKindUid.uppercase() in setOf("PLACE","LOCATION"))return current
        current=row.parentAnchorUid?.takeIf(String::isNotBlank)?:return current
    }
    return null
}

internal fun canonicalCampaignScenePath(
    directAnchorUid:String?,
    parentOf:(String)->CampaignSceneParent?
):List<String>{
    var current=directAnchorUid?.takeIf(String::isNotBlank)?:return emptyList()
    val path=mutableListOf<String>()
    repeat(32){
        if(current in path)return emptyList()
        path+=current
        current=parentOf(current)?.parentAnchorUid?.takeIf(String::isNotBlank)?:return path
    }
    return emptyList()
}
