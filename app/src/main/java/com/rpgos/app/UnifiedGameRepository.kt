package com.rpgos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

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

    override fun npcs(search: String, audience: AudienceContext, purpose: PurposeContext): List<NpcListItem> = store.npcs(search, audience, purpose)
    override fun npcDetail(uid: String, audience: AudienceContext, purpose: PurposeContext): NpcDetail = store.npcDetail(uid, audience, purpose)
    override fun relationEdges(audience: AudienceContext, purpose: PurposeContext): List<RelationEdge> = store.relationEdges(audience, purpose)
    override fun economies(audience: AudienceContext, purpose: PurposeContext): List<EconomySummary> = store.economies(audience, purpose)
    override fun wars(audience: AudienceContext, purpose: PurposeContext): List<WarSummary> = store.wars(audience, purpose)
    override fun relationships(audience: AudienceContext, purpose: PurposeContext): List<RelationshipItem> = store.relationships(audience, purpose)
    override fun organizations(audience: AudienceContext, purpose: PurposeContext): List<OrganizationItem> = store.organizations(audience, purpose)
    override fun politics(audience: AudienceContext, purpose: PurposeContext): List<PoliticalItem> = store.politics(audience, purpose)
    override fun syncCheck(): SyncCheckResult = store.syncCheck()
    override fun dbTables(): List<DbTableInfo> = store.dbTables()
    override fun diagnostics(contextSummary: String): DiagnosticsSnapshot = store.diagnostics(contextSummary)
    override fun worldRegions(): List<WorldRegionItem> = store.worldRegions()
    override fun worldLocations(search: String): List<WorldLocationItem> = store.worldLocations(search)
    override fun activeWorldEvents(audience: AudienceContext, purpose: PurposeContext): List<WorldEventItem> = store.activeWorldEvents(audience, purpose)
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