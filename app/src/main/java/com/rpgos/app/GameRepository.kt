package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Single logical CampaignRepository required by the MASTER architecture.
 *
 * UI/application code should depend on this contract instead of coordinating
 * storage, campaign selection, backups and package managers independently.
 *
 * Authoritative gameplay writes are intentionally absent from this surface.
 * The only gameplay-authoritative commit API is [commitTurn], which consumes
 * an already-admitted canonical proposal and delegates to TurnTransaction.
 */
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

    /** Read-only package databases; campaign writable database is deliberately not exposed. */
    fun openWorldDb(): SQLiteDatabase
    fun openCoreDb(): SQLiteDatabase

    /** Sole supported NORMAL GAMEPLAY durable mutation entry on the repository facade. */
    fun commitTurn(
        identity: TurnTransactionIdentity,
        proposal: CanonicalCampaignMutationProposal,
        failureInjector: TurnFailureInjector = TurnFailureInjector.NONE
    ): TurnExecutionResult<TurnCommitAppliedResult>

    fun buildContext(playerInput: String, chapter: Int): ContextBundle
    fun fullCharacterPanel(): CharacterPanelSnapshot
    fun status(): StatusSnapshot
    fun time(): TimeSnapshot
    fun chronicle(): List<ChronicleEntry>

    fun truthRecords(
        kind: TruthKind? = null,
        subjectUid: String? = null,
        perspectiveUid: String? = null,
        limit: Int = 100
    ): List<CampaignTruthRecord>

    fun npcs(search: String = ""): List<NpcListItem>
    fun npcDetail(uid: String): NpcDetail
    fun relationEdges(): List<RelationEdge>
    fun economies(): List<EconomySummary>
    fun wars(): List<WarSummary>
    fun relationships(): List<RelationshipItem>
    fun organizations(): List<OrganizationItem>
    fun politics(): List<PoliticalItem>

    fun syncCheck(): SyncCheckResult
    fun dbTables(): List<DbTableInfo>
    fun diagnostics(contextSummary: String): DiagnosticsSnapshot

    fun worldRegions(): List<WorldRegionItem>
    fun worldLocations(search: String = ""): List<WorldLocationItem>
    fun activeWorldEvents(): List<WorldEventItem>
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
    fun finalizeChapter(chapter: Int, title: String): Pair<String, String>
}
