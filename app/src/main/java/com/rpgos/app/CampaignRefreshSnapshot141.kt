package com.rpgos.app

data class CampaignRefreshSnapshot141(
    val access: CampaignApplicationAccess141,
    val status: StatusSnapshot = StatusSnapshot(),
    val characterPanel: CharacterPanelSnapshot = CharacterPanelSnapshot(
        emptyList(), emptyList(), emptyList(), emptyList(),
        emptyList(), emptyList(), emptyList(), emptyList()
    ),
    val time: TimeSnapshot = TimeSnapshot(),
    val chronicle: List<ChronicleEntry> = emptyList(),
    val worldPacks: List<WorldPackInfo> = emptyList(),
    val campaigns: List<CampaignInfo> = emptyList(),
    val backups: List<String> = emptyList(),
    val npcs: List<NpcListItem> = emptyList(),
    val relationEdges: List<RelationEdge> = emptyList(),
    val economies: List<EconomySummary> = emptyList(),
    val wars: List<WarSummary> = emptyList(),
    val sync: SyncCheckResult = SyncCheckResult(true, emptyList()),
    val dbTables: List<DbTableInfo> = emptyList(),
    val visualLibrary: List<VisualRecord> = emptyList(),
    val relationships: List<RelationshipItem> = emptyList(),
    val organizations: List<OrganizationItem> = emptyList(),
    val politics: List<PoliticalItem> = emptyList(),
    val diagnostics: DiagnosticsSnapshot,
    val regions: List<WorldRegionItem> = emptyList(),
    val locations: List<WorldLocationItem> = emptyList(),
    val worldEvents: List<WorldEventItem> = emptyList(),
    val techniques: List<TechniqueBrowserItem> = emptyList(),
    val missions: List<MissionBrowserItem> = emptyList(),
    val activeCampaign: String,
    val activeWorldPack: String
)

/**
 * Single application-facing refresh transaction.
 *
 * The health gate is evaluated before campaign readers are called. BLOCKED
 * campaigns expose package/backup metadata and explicit diagnostics only; no
 * reader is allowed to interpret a partially trustworthy campaign.db.
 */
class CampaignRefreshSnapshotLoader141(
    private val store: LocalGameStore
) {
    fun load(contextSummary: String): CampaignRefreshSnapshot141 {
        val access = CampaignApplicationGate141(store).inspect()
        val activeCampaign = store.activeCampaignDirName()
        val activeWorldPack = store.activeWorldPackDirName()
        val worldPacks = store.packageManager().listWorldPacks()
        val campaigns = store.packageManager().listCampaigns()
        val backups = store.backups()

        if (!access.canReadCampaignData) {
            return CampaignRefreshSnapshot141(
                access = access,
                worldPacks = worldPacks,
                campaigns = campaigns,
                backups = backups,
                diagnostics = DiagnosticsSnapshot(
                    activeCampaign,
                    activeWorldPack,
                    backups.size,
                    worldPacks.size,
                    campaigns.size,
                    "${access.statusMessage} | $contextSummary",
                    0,
                    access.health.errorCodes.size
                ),
                activeCampaign = activeCampaign,
                activeWorldPack = activeWorldPack
            )
        }

        return CampaignRefreshSnapshot141(
            access = access,
            status = store.status(),
            characterPanel = store.fullCharacterPanel(),
            time = store.time(),
            chronicle = store.chronicle(),
            worldPacks = worldPacks,
            campaigns = campaigns,
            backups = backups,
            npcs = store.npcs(),
            relationEdges = store.relationEdges(),
            economies = store.economies(),
            wars = store.wars(),
            sync = store.syncCheck(),
            dbTables = store.dbTables(),
            visualLibrary = store.visualLibrary(),
            relationships = store.relationships(),
            organizations = store.organizations(),
            politics = store.politics(),
            diagnostics = store.diagnostics(contextSummary),
            regions = store.worldRegions(),
            locations = store.worldLocations(),
            worldEvents = store.activeWorldEvents(),
            techniques = store.techniqueBrowser(),
            missions = store.missionBrowser(),
            activeCampaign = activeCampaign,
            activeWorldPack = activeWorldPack
        )
    }
}
