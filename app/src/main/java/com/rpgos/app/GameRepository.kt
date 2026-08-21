package com.rpgos.app

import java.io.File

/** Canonical campaign repository. Protected reads require explicit Phase38 audience + purpose. */
interface CampaignRepository {
    fun bootstrap()

    fun activeCampaignRef(): ActiveCampaignRef
    fun activePlayerRef(): ActivePlayerRef?
    fun setActivePlayer(playerUid: String): ActivePlayerRef
    fun protectedReads(): ProtectedCampaignReadRepository

    fun statDefinitions(): List<StatDefinition>
    fun resourceDefinitions(): List<ResourceDefinition>
    fun registerStatDefinitions(worldPackUid: String, definitions: List<StatDefinition>)
    fun registerResourceDefinitions(worldPackUid: String, definitions: List<ResourceDefinition>)

    fun activeCampaignDirName(): String
    fun activeWorldPackDirName(): String
    fun setActiveCampaign(dirName: String)
    fun setActiveWorldPack(dirName: String)
    fun createCampaign(name: String): File

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

    fun npcsProjection(search: String, audience: AudienceContext, purpose: PurposeContext): VisibilityProjection<List<NpcListItem>>
    fun npcDetailProjection(uid: String, audience: AudienceContext, purpose: PurposeContext): NpcDetailProtectedProjection
    fun relationEdgesProjection(audience: AudienceContext, purpose: PurposeContext): VisibilityProjection<List<RelationEdge>>
    fun economiesProjection(audience: AudienceContext, purpose: PurposeContext): VisibilityProjection<List<EconomySummary>>
    fun warsProjection(audience: AudienceContext, purpose: PurposeContext): VisibilityProjection<List<WarSummary>>
    fun relationshipsProjection(audience: AudienceContext, purpose: PurposeContext): VisibilityProjection<List<RelationshipItem>>
    fun organizationsProjection(audience: AudienceContext, purpose: PurposeContext): VisibilityProjection<List<OrganizationItem>>
    fun politicsProjection(audience: AudienceContext, purpose: PurposeContext): VisibilityProjection<List<PoliticalItem>>

    fun syncCheck(): SyncCheckResult
    fun dbTables(): List<DbTableInfo>
    fun diagnostics(contextSummary: String): DiagnosticsSnapshot

    fun worldRegions(): List<WorldRegionItem>
    fun worldLocations(search: String = ""): List<WorldLocationItem>
    fun activeWorldEventsProjection(audience: AudienceContext, purpose: PurposeContext): VisibilityProjection<List<WorldEventItem>>

    /** Legacy/presentation compatibility only. Canonical protected state is activeWorldEventsProjection. */
    fun activeWorldEvents(audience: AudienceContext, purpose: PurposeContext): List<WorldEventItem> =
        activeWorldEventsProjection(audience, purpose).value ?: emptyList()

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
