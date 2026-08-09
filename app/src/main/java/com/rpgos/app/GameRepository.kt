package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Single logical CampaignRepository required by the MASTER architecture.
 *
 * UI/application code should depend on this contract instead of coordinating
 * storage, campaign selection, backups and package managers independently.
 */
interface CampaignRepository {
    fun bootstrap()

    fun activeCampaignRef(): ActiveCampaignRef
    fun activePlayerRef(): ActivePlayerRef?
    fun activeCampaignDirName(): String
    fun activeWorldPackDirName(): String
    fun setActiveCampaign(dirName: String)
    fun setActiveWorldPack(dirName: String)
    fun createCampaign(name: String): File

    fun openSaveDb(): SQLiteDatabase
    fun openWorldDb(): SQLiteDatabase
    fun openCoreDb(): SQLiteDatabase

    fun buildContext(playerInput: String, chapter: Int): ContextBundle
    fun fullCharacterPanel(): CharacterPanelSnapshot
    fun status(): StatusSnapshot
    fun time(): TimeSnapshot
    fun chronicle(): List<ChronicleEntry>

    fun recordTruth(
        kind: TruthKind,
        predicate: String,
        provenance: Provenance,
        subjectUid: String? = null,
        objectValue: String? = null,
        perspectiveUid: String? = null,
        narrativeText: String? = null,
        truthUid: String? = null,
        supersedesTruthUid: String? = null
    ): CampaignTruthRecord

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
    fun applyPatch(patch: StatePatch): PatchResult
}
