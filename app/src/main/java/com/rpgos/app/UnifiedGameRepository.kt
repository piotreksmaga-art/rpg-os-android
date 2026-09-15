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

    private fun openGameplaySaveDb(): SQLiteDatabase = store.openGameplaySaveDb()
    internal fun infrastructureOpenWorldDb(): SQLiteDatabase = store.openWorldDb()
    internal fun infrastructureOpenCoreDb(): SQLiteDatabase = store.openCoreDb()
    internal fun infrastructureReceipt(transactionUid:String):TurnCommitReceipt? =
        openGameplaySaveDb().use{TurnTransactionReceiptStore(it).committedTransaction(transactionUid)}
    internal fun infrastructureLastCommitOrder():Long =
        openGameplaySaveDb().use{TurnTransactionReceiptStore(it).lastValidCommit(activeCampaignRef().campaignId)?.commitOrder?:0L}
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
    internal fun npcCognitionStimulus(expected:TemporalScope,actor:DomainRef):NpcCognitionStimulus? {
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
                val allowed=(projected as? ProtectedReadResult.Allow)?.value?:return@use null
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
                NpcConsequenceObservation.annotate(expected,effects){actor->if(actor.uid==active)null else actors.actor(actor)}
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
            if(!CampaignWorldProjectionSchema.isReady(db))return@canonicalCampaignSceneAnchor null
            db.rawQuery("""SELECT element_kind_uid,parent_anchor_uid FROM ${CampaignWorldProjectionSchema.TABLE}
                WHERE campaign_id=? AND audience_scope_uid=? AND element_uid=? LIMIT 1""",
                arrayOf(activeCampaignRef().campaignId,CampaignWorldAudience.PLAYER_VISIBLE,uid)
            ).use{cursor->if(!cursor.moveToFirst())null else CampaignSceneParent(
                cursor.getString(0),if(cursor.isNull(1))null else cursor.getString(1)
            )}
        }
    }
    internal fun infrastructureEntityScenePathUids(entityUid:String):List<String> = openGameplaySaveDb().use{db->
        val direct=db.rawQuery("SELECT location_uid FROM entity_positions WHERE entity_uid=? LIMIT 1",arrayOf(entityUid)).use{cursor->
            if(cursor.moveToFirst()&&!cursor.isNull(0))cursor.getString(0)?.takeIf(String::isNotBlank) else null
        }
        canonicalCampaignScenePath(direct){uid->
            if(!CampaignWorldProjectionSchema.isReady(db))return@canonicalCampaignScenePath null
            db.rawQuery("""SELECT element_kind_uid,parent_anchor_uid FROM ${CampaignWorldProjectionSchema.TABLE}
                WHERE campaign_id=? AND audience_scope_uid=? AND element_uid=? LIMIT 1""",
                arrayOf(activeCampaignRef().campaignId,CampaignWorldAudience.PLAYER_VISIBLE,uid)
            ).use{cursor->if(!cursor.moveToFirst())null else CampaignSceneParent(
                cursor.getString(0),if(cursor.isNull(1))null else cursor.getString(1)
            )}
        }
    }
    internal fun infrastructureWorldElements(reference:IntentReference,consumers:List<IntentNode>):List<CampaignWorldElement> =
        openGameplaySaveDb().use{db->
            val phrase=(reference.rawPhrase?:reference.descriptorHints["surface"]).orEmpty().trim()
            val shape=WorldReferenceShapeClassifier.classify(reference,consumers)
            if(phrase.isBlank())emptyList() else CampaignWorldProjectionStore(db,activeCampaignRef().campaignId)
                .searchPlayerVisible(phrase,shape,requireAffordances=reference.kind !in setOf(IntentReferenceKind.DISCOURSE,IntentReferenceKind.DEICTIC) && shape.kind!=WorldReferenceShapeKind.ROLE)
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
