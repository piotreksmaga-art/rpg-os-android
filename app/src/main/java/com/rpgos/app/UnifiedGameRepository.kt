package com.rpgos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
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

    override fun bootstrap() = store.bootstrap()
    override fun activeCampaignRef(): ActiveCampaignRef = selection.activeCampaignRef()
    override fun activePlayerRef(): ActivePlayerRef? = store.activePlayerRef()
    override fun setActivePlayer(playerUid: String): ActivePlayerRef = store.setActivePlayer(playerUid)
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
    internal fun infrastructureReplayPayload(transactionUid:String,committedOrder:Long):CommittedReplayPayload? =
        openGameplaySaveDb().use{db->CommittedReplayPayloadStore(db).after(activeCampaignRef().campaignId,(committedOrder-1).coerceAtLeast(0)).singleOrNull{it.identity.transactionUid==transactionUid}}
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

    private fun requireActiveVisibility(audience:AudienceContext,purpose:PurposeContext) {
        val campaign=activeCampaignRef().campaignId
        if(audience.campaignUid!=campaign||purpose.campaignUid!=campaign) throw VisibilityAuthorityFailure.CrossCampaign()
    }

    override fun commitTurn(
        identity: TurnTransactionIdentity,
        proposal: CanonicalCampaignMutationProposal,
        failureInjector: TurnFailureInjector
    ): TurnExecutionResult<TurnCommitAppliedResult> = openGameplaySaveDb().use { db ->
        TurnTransactionBoundary.create(db, identity, proposal, failureInjector).commit()
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
    override fun finalizeChapter(chapter: Int, title: String): Pair<String, String> = store.finalizeChapter(chapter, title)
}
