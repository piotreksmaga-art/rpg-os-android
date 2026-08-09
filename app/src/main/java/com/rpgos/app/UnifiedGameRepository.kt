package com.rpgos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.UUID

/** Canonical repository facade for the application layer. */
class UnifiedGameRepository(context: Context) : CampaignRepository {
    private val context = context.applicationContext
    private val store = LocalGameStore(this.context)
    private val selection = CampaignSelectionManager(this.context)

    override fun bootstrap() = store.bootstrap()

    override fun activeCampaignRef(): ActiveCampaignRef = selection.activeCampaignRef()
    override fun activePlayerRef(): ActivePlayerRef? = store.activePlayerRef()
    override fun setActivePlayer(playerUid: String): ActivePlayerRef = store.setActivePlayer(playerUid)
    override fun playerState(): PlayerStateSnapshot? = store.playerState()
    override fun statDefinitions(): List<StatDefinition> = store.statDefinitions()
    override fun resourceDefinitions(): List<ResourceDefinition> = store.resourceDefinitions()
    override fun registerStatDefinitions(worldPackUid: String, definitions: List<StatDefinition>) =
        store.registerStatDefinitions(worldPackUid, definitions)
    override fun registerResourceDefinitions(worldPackUid: String, definitions: List<ResourceDefinition>) =
        store.registerResourceDefinitions(worldPackUid, definitions)
    override fun playerStats(): List<PlayerStat> = store.playerStats()
    override fun playerResources(): List<PlayerResource> = store.playerResources()
    override fun activeCampaignDirName(): String = activeCampaignRef().directoryName
    override fun activeWorldPackDirName(): String = store.activeWorldPackDirName()
    override fun setActiveCampaign(dirName: String) = store.setActiveCampaign(dirName)
    override fun setActiveWorldPack(dirName: String) = store.setActiveWorldPack(dirName)
    override fun createCampaign(name: String): File = store.createCampaign(name)

    override fun openSaveDb(): SQLiteDatabase = store.openSaveDb()
    override fun openWorldDb(): SQLiteDatabase = store.openWorldDb()
    override fun openCoreDb(): SQLiteDatabase = store.openCoreDb()

    override fun buildContext(playerInput: String, chapter: Int): ContextBundle =
        store.buildContext(playerInput, chapter)

    override fun fullCharacterPanel(): CharacterPanelSnapshot = store.fullCharacterPanel()
    override fun status(): StatusSnapshot = store.status()
    override fun time(): TimeSnapshot = store.time()
    override fun chronicle(): List<ChronicleEntry> = store.chronicle()

    override fun recordTruth(
        kind: TruthKind,
        predicate: String,
        provenance: Provenance,
        subjectUid: String?,
        objectValue: String?,
        perspectiveUid: String?,
        narrativeText: String?,
        truthUid: String?,
        supersedesTruthUid: String?
    ): CampaignTruthRecord = openSaveDb().use { db ->
        MigrationManager().ensureV4(db, activeCampaignRef().campaignId)
        CampaignTruthStore(db, activeCampaignRef().campaignId).record(
            kind = kind,
            predicate = predicate,
            provenance = provenance,
            subjectUid = subjectUid,
            objectValue = objectValue,
            perspectiveUid = perspectiveUid,
            narrativeText = narrativeText,
            truthUid = truthUid ?: "TRUTH-${UUID.randomUUID()}",
            supersedesTruthUid = supersedesTruthUid
        )
    }

    override fun truthRecords(
        kind: TruthKind?,
        subjectUid: String?,
        perspectiveUid: String?,
        limit: Int
    ): List<CampaignTruthRecord> = openSaveDb().use { db ->
        MigrationManager().ensureV4(db, activeCampaignRef().campaignId)
        CampaignTruthStore(db, activeCampaignRef().campaignId).active(
            kind = kind,
            subjectUid = subjectUid,
            perspectiveUid = perspectiveUid,
            limit = limit
        )
    }

    override fun npcs(search: String): List<NpcListItem> = store.npcs(search)
    override fun npcDetail(uid: String): NpcDetail = store.npcDetail(uid)
    override fun relationEdges(): List<RelationEdge> = store.relationEdges()
    override fun economies(): List<EconomySummary> = store.economies()
    override fun wars(): List<WarSummary> = store.wars()
    override fun relationships(): List<RelationshipItem> = store.relationships()
    override fun organizations(): List<OrganizationItem> = store.organizations()
    override fun politics(): List<PoliticalItem> = store.politics()

    override fun syncCheck(): SyncCheckResult = store.syncCheck()
    override fun dbTables(): List<DbTableInfo> = store.dbTables()
    override fun diagnostics(contextSummary: String): DiagnosticsSnapshot = store.diagnostics(contextSummary)

    override fun worldRegions(): List<WorldRegionItem> = store.worldRegions()
    override fun worldLocations(search: String): List<WorldLocationItem> = store.worldLocations(search)
    override fun activeWorldEvents(): List<WorldEventItem> = store.activeWorldEvents()
    override fun techniqueBrowser(search: String): List<TechniqueBrowserItem> = store.techniqueBrowser(search)
    override fun missionBrowser(): List<MissionBrowserItem> = store.missionBrowser()

    override fun visualLibrary(): List<VisualRecord> = store.visualLibrary()

    override fun addVisual(
        title: String,
        kind: String,
        uri: String,
        chapter: Int?,
        relatedEntityUid: String?,
        relatedLocationUid: String?,
        prompt: String?,
        revisedPrompt: String?,
        sourceVisualUid: String?
    ): String = store.addVisual(
        title = title,
        kind = kind,
        uri = uri,
        chapter = chapter,
        relatedEntityUid = relatedEntityUid,
        relatedLocationUid = relatedLocationUid,
        prompt = prompt,
        revisedPrompt = revisedPrompt,
        sourceVisualUid = sourceVisualUid
    )

    override fun packageManager(): RpgPackageManager = store.packageManager()
    override fun backups(): List<String> = store.backups()
    override fun restoreBackup(path: String): String = store.restoreBackup(path)
    override fun finalizeChapter(chapter: Int, title: String): Pair<String, String> =
        store.finalizeChapter(chapter, title)

    override fun applyPatch(patch: StatePatch): PatchResult = store.applyPatch(patch)
}
