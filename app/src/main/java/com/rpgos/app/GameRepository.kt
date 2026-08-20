package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import java.io.File

/** Canonical campaign repository. Protected reads require explicit Phase38 audience + purpose. */
interface CampaignRepository {
    fun bootstrap()

    fun activeCampaignRef(): ActiveCampaignRef
    fun activePlayerRef(): ActivePlayerRef?
    fun setActivePlayer(playerUid: String): ActivePlayerRef
    fun playerState(): PlayerStateSnapshot?

    fun statDefinitions(): List<StatDefinition>
    fun resourceDefinitions(): List<ResourceDefinition>
    fun registerStatDefinitions(worldPackUid: String, definitions: List<StatDefinition>)
    fun registerResourceDefinitions(worldPackUid: String, definitions: List<ResourceDefinition>)
    fun playerStats(): List<PlayerStat>
    fun playerResources(): List<PlayerResource>

    fun activeCampaignDirName(): String
    fun activeWorldPackDirName(): String
    fun setActiveCampaign(dirName: String)
    fun setActiveWorldPack(dirName: String)
    fun createCampaign(name: String): File

    fun openWorldDb(): SQLiteDatabase
    fun openCoreDb(): SQLiteDatabase

    fun commitTurn(
        identity: TurnTransactionIdentity,
        proposal: CanonicalCampaignMutationProposal,
        failureInjector: TurnFailureInjector = TurnFailureInjector.NONE
    ): TurnExecutionResult<TurnCommitAppliedResult>

    fun buildContext(playerInput: String, chapter: Int, audience: AudienceContext, purpose: PurposeContext): ContextBundle
    fun fullCharacterPanel(audience: AudienceContext, purpose: PurposeContext): CharacterPanelSnapshot
    fun status(): StatusSnapshot
    fun time(): TimeSnapshot
    fun chronicle(): List<ChronicleEntry>

    fun truthRecords(
        audience: AudienceContext,
        purpose: PurposeContext,
        kind: TruthKind? = null,
        subjectUid: String? = null,
        perspectiveUid: String? = null,
        limit: Int = 100
    ): VisibilityProjection<List<CampaignTruthRecord>>
    fun canonDivergences(audience: AudienceContext, purpose: PurposeContext): VisibilityProjection<List<CanonDivergenceRecord>>

    fun npcs(search: String, audience: AudienceContext, purpose: PurposeContext): List<NpcListItem>
    fun npcDetail(uid: String, audience: AudienceContext, purpose: PurposeContext): NpcDetail
    fun relationEdges(audience: AudienceContext, purpose: PurposeContext): List<RelationEdge>
    fun economies(audience: AudienceContext, purpose: PurposeContext): List<EconomySummary>
    fun wars(audience: AudienceContext, purpose: PurposeContext): List<WarSummary>
    fun relationships(audience: AudienceContext, purpose: PurposeContext): List<RelationshipItem>
    fun organizations(audience: AudienceContext, purpose: PurposeContext): List<OrganizationItem>
    fun politics(audience: AudienceContext, purpose: PurposeContext): List<PoliticalItem>

    fun syncCheck(): SyncCheckResult
    fun dbTables(): List<DbTableInfo>
    fun diagnostics(contextSummary: String): DiagnosticsSnapshot

    fun worldRegions(): List<WorldRegionItem>
    fun worldLocations(search: String = ""): List<WorldLocationItem>
    fun activeWorldEvents(audience: AudienceContext, purpose: PurposeContext): List<WorldEventItem>
    fun techniqueBrowser(search: String = ""): List<TechniqueBrowserItem>
    fun missionBrowser(): List<MissionBrowserItem>

    fun visualLibrary(): List<VisualRecord>
    fun addVisual(
        title: String,
        kind: String,
        uri: String,
        chapter: Int?,
        relatedEntityUid: String?,
        relatedLocationUid: String?,
        prompt: String?,
        revisedPrompt: String?,
        sourceVisualUid: String? = null
    ): String

    fun packageManager(): RpgPackageManager
    fun backups(): List<String>
    fun restoreBackup(path: String): String
    fun createSnapshot(kind: SnapshotKind = SnapshotKind.AUTOMATIC, pinned: Boolean = false): CampaignSnapshotDescriptor
    fun snapshots(): List<CampaignSnapshotDescriptor>
    fun restoreLatestSnapshot(): String
    fun finalizeChapter(chapter: Int, title: String): Pair<String, String>
}
