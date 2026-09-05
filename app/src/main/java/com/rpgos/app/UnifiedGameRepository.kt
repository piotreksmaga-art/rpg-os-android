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
            if(phrase.isBlank())emptyList() else CampaignWorldProjectionStore(db,activeCampaignRef().campaignId)
                .searchPlayerVisible(phrase,WorldReferenceShapeClassifier.classify(reference,consumers))
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
        val result=openGameplaySaveDb().use { db ->TurnTransactionBoundary.create(db, identity, proposal, failureInjector).commit()}
        runCatching{store.ensureUndoCheckpointAfterCommit()}
            .onFailure{DiagnosticLogger.log(context,"UNDO_BASELINE_CHECKPOINT_FAILED",it)}
        scheduleMemoryConsolidation(identity.campaignUid)
        return result
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
