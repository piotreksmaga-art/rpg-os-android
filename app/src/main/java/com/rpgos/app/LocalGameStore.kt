package com.rpgos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Internal storage/infrastructure implementation. It is deliberately not a
 * gameplay-facing repository contract; raw writable DB access stays here.
 */
internal class LocalGameStore(private val context: Context) {
    private val baseDir = File(context.filesDir, "rpgos")
    private val selection = CampaignSelectionManager(context)
    private val saveDir: File get() = File(baseDir, "saves/${selection.activeCampaignDirName()}")
    private val worldDir: File get() = File(baseDir, "worldpacks/${selection.activeWorldPackDirName()}")
    private val coreDir = File(baseDir, "core")

    fun bootstrap() {
        baseDir.mkdirs()
        reconcileCanonicalPackageRoots()
        ensureBootstrapPackage("Naruto_Default.campaign.zip", saveDir, isCampaign = true)
        ensureBootstrapPackage("Naruto.worldpack.zip", worldDir, isCampaign = false)
        if (!File(coreDir, "rpg_core.db").exists()) copyAsset("rpg_core.db", File(coreDir, "rpg_core.db"))
        runCatching { openSaveDb().use { save -> ensureCurrentSchema(save); AutoRepairEngine().repair(save) } }
            .onFailure { DiagnosticLogger.log(context, "AUTO_REPAIR_BOOT_FAILED", it) }
    }

    private fun reconcileCanonicalPackageRoots() {
        val validator = PackageValidator()
        CanonicalPackageReplacement.reconcileRoot(File(baseDir, "saves")) { candidate -> runCatching { validator.validateCampaign(candidate).ok }.getOrDefault(false) }
        CanonicalPackageReplacement.reconcileRoot(File(baseDir, "worldpacks")) { candidate -> runCatching { validator.validateWorldPack(candidate).ok }.getOrDefault(false) }
    }

    private fun ensureBootstrapPackage(assetName: String, target: File, isCampaign: Boolean) {
        val validator = PackageValidator()
        val validPackage: (File) -> Boolean = { candidate ->
            runCatching { if (isCampaign) validator.validateCampaign(candidate).ok else validator.validateWorldPack(candidate).ok }.getOrDefault(false)
        }
        CanonicalPackageReplacement.reconcile(target, validPackage)
        if (validPackage(target)) return
        val staging = File(baseDir, ".bootstrap-staging/${target.name}-${System.nanoTime()}")
        staging.deleteRecursively()
        try {
            extractAssetZip(assetName, staging)
            require(validPackage(staging)) { "Bootstrap package $assetName failed canonical validation" }
            val prepared = CanonicalPackageReplacement.prepareCopy(staging, target)
            CanonicalPackageReplacement.activatePreparedIf(prepared, target, validPackage) { !validPackage(target) }
        } finally { staging.deleteRecursively() }
    }

    private fun extractAssetZip(assetName: String, target: File) {
        target.mkdirs()
        context.assets.open(assetName).use { input -> ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val outFile = File(target, entry.name)
                if (entry.isDirectory) outFile.mkdirs() else { outFile.parentFile?.mkdirs(); FileOutputStream(outFile).use { output -> zip.copyTo(output) } }
                zip.closeEntry(); entry = zip.nextEntry
            }
        } }
    }

    private fun copyAsset(assetName: String, target: File) { target.parentFile?.mkdirs(); context.assets.open(assetName).use { input -> FileOutputStream(target).use { output -> input.copyTo(output) } } }

    /** Infrastructure/admin raw write handle. Never exported by CampaignRepository. */
    internal fun openSaveDb(): SQLiteDatabase = openSave()

    /**
     * Production gameplay open boundary. Once this returns, current schema,
     * receipt schema, Phase30 Event Store, Phase31 Causal Graph and all G32
     * mutation/append-only guards are verified ready. Re-open is fail-closed.
     */
    internal fun openGameplaySaveDb(): SQLiteDatabase {
        val db = openSave()
        val campaignUid = selection.activeCampaignRef().campaignId
        return try {
            GameplayRuntimeBootstrap.ensureReady(db, campaignUid)
            db
        } catch (failure: Throwable) {
            db.close()
            throw failure
        }
    }

    fun openWorldDb(): SQLiteDatabase = SQLiteDatabase.openDatabase(File(worldDir, "world.db").absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    fun openCoreDb(): SQLiteDatabase = SQLiteDatabase.openDatabase(File(coreDir, "rpg_core.db").absolutePath, null, SQLiteDatabase.OPEN_READONLY)

    fun buildContext(playerInput: String, chapter: Int): ContextBundle {
        openGameplaySaveDb().use { save ->
            openWorldDb().use { world ->
                val base = ContextBuilder(save, world).build(playerInput, chapter)
                val campaignId = selection.activeCampaignRef().campaignId
                val truth = CampaignTruthStore(save, campaignId).activeForContext(limit = 80)
                val state = PlayerStateStore(save, campaignId).load()
                return base.copy(campaignTruth = truth, playerState = state?.toContextMap() ?: emptyMap(), contextMeta = base.contextMeta + mapOf("campaign_truth_records" to truth.size, "player_state_contract" to (state != null), "active_player_uid" to state?.activePlayer?.playerUid))
            }
        }
    }

    fun activePlayerRef(): ActivePlayerRef? { openSaveDb().use { db -> ensureCurrentSchema(db); return ActivePlayerStore(db, selection.activeCampaignRef().campaignId).active() } }
    fun setActivePlayer(playerUid: String): ActivePlayerRef { openSaveDb().use { db -> ensureCurrentSchema(db); return ActivePlayerStore(db, selection.activeCampaignRef().campaignId).set(playerUid) } }
    fun playerState(): PlayerStateSnapshot? { openSaveDb().use { db -> ensureCurrentSchema(db); return PlayerStateStore(db, selection.activeCampaignRef().campaignId).load() } }
    fun statDefinitions(): List<StatDefinition> { openSaveDb().use { db -> ensureCurrentSchema(db); return StatResourceStore(db, selection.activeCampaignRef().campaignId).statDefinitions() } }
    fun resourceDefinitions(): List<ResourceDefinition> { openSaveDb().use { db -> ensureCurrentSchema(db); return StatResourceStore(db, selection.activeCampaignRef().campaignId).resourceDefinitions() } }
    fun registerStatDefinitions(worldPackUid: String, definitions: List<StatDefinition>) { openSaveDb().use { db -> ensureCurrentSchema(db); StatResourceStore(db, selection.activeCampaignRef().campaignId).registerStatDefinitions(worldPackUid, definitions) } }
    fun registerResourceDefinitions(worldPackUid: String, definitions: List<ResourceDefinition>) { openSaveDb().use { db -> ensureCurrentSchema(db); StatResourceStore(db, selection.activeCampaignRef().campaignId).registerResourceDefinitions(worldPackUid, definitions) } }
    fun playerStats(): List<PlayerStat> { openSaveDb().use { db -> ensureCurrentSchema(db); val campaignId = selection.activeCampaignRef().campaignId; val playerUid = ActivePlayerStore(db, campaignId).active()?.playerUid ?: return emptyList(); return StatResourceStore(db, campaignId).playerStats(playerUid) } }
    fun playerResources(): List<PlayerResource> { openSaveDb().use { db -> ensureCurrentSchema(db); val campaignId = selection.activeCampaignRef().campaignId; val playerUid = ActivePlayerStore(db, campaignId).active()?.playerUid ?: return emptyList(); return StatResourceStore(db, campaignId).playerResources(playerUid) } }
    fun fullCharacterPanel(): CharacterPanelSnapshot { openSaveDb().use { db -> ensureCurrentSchema(db); val playerUid = ActivePlayerStore(db, selection.activeCampaignRef().campaignId).active()?.playerUid; return CharacterPanelReader(db, playerUid).load() } }
    fun npcs(search:String=""):List<NpcListItem>{ openWorldDb().use{world->openSaveDb().use{save->return NpcWorldDashboardReader(world,save).npcs(search)}} }
    fun npcDetail(uid:String):NpcDetail{ openWorldDb().use{world->openSaveDb().use{save->return NpcWorldDashboardReader(world,save).npcDetail(uid)}} }
    fun relationEdges():List<RelationEdge>{ openWorldDb().use{world->openSaveDb().use{save->return NpcWorldDashboardReader(world,save).relationEdges()}} }
    fun economies():List<EconomySummary>{ openWorldDb().use{world->openSaveDb().use{save->return NpcWorldDashboardReader(world,save).economies()}} }
    fun wars():List<WarSummary>{ openWorldDb().use{world->openSaveDb().use{save->return NpcWorldDashboardReader(world,save).wars()}} }
    fun syncCheck():SyncCheckResult{ openCoreDb().use{core->openWorldDb().use{world->openSaveDb().use{save-> ensureCurrentSchema(save); return SyncManager().check(core,world,save) }}} }
    fun dbTables():List<DbTableInfo>{ openCoreDb().use{core->openSaveDb().use{save->return DatabaseExplorer(core,save).tables()}} }
    fun visualLibrary(): List<VisualRecord> { openSaveDb().use { return VisualLibrary(it).list() } }
    fun addVisual(title: String, kind: String, uri: String, chapter: Int?, relatedEntityUid: String?, relatedLocationUid: String?, prompt: String?, revisedPrompt: String?, sourceVisualUid: String? = null): String { openSaveDb().use { return VisualLibrary(it).add(title, kind, uri, chapter, relatedEntityUid, relatedLocationUid, prompt, revisedPrompt, sourceVisualUid) } }
    fun relationships(): List<RelationshipItem> { openWorldDb().use { world -> openSaveDb().use { save -> return SocialReader(world, save).relationships() } } }
    fun organizations(): List<OrganizationItem> { openWorldDb().use { world -> openSaveDb().use { save -> return SocialReader(world, save).organizations() } } }
    fun politics(): List<PoliticalItem> { openWorldDb().use { world -> openSaveDb().use { save -> return SocialReader(world, save).politics() } } }

    fun diagnostics(contextSummary: String): DiagnosticsSnapshot {
        val sot = openCoreDb().use { db -> try { db.rawQuery("SELECT COUNT(*) FROM source_of_truth_registry", null).use { if (it.moveToFirst()) it.getInt(0) else 0 } } catch (_: Exception) { 0 } }
        val alerts = openSaveDb().use { db -> try { db.rawQuery("SELECT COUNT(*) FROM gm_timeline_alerts WHERE resolved=0", null).use { if (it.moveToFirst()) it.getInt(0) else 0 } } catch (_: Exception) { 0 } }
        return DiagnosticsSnapshot(activeCampaignDirName(), activeWorldPackDirName(), backups().size, packageManager().listWorldPacks().size, packageManager().listCampaigns().size, contextSummary, sot, alerts)
    }

    fun worldRegions(): List<WorldRegionItem> { openWorldDb().use { world -> openSaveDb().use { save -> return WorldReader(world, save).regions() } } }
    fun worldLocations(search: String = ""): List<WorldLocationItem> { openWorldDb().use { world -> openSaveDb().use { save -> return WorldReader(world, save).locations(search) } } }
    fun activeWorldEvents(): List<WorldEventItem> { openWorldDb().use { world -> openSaveDb().use { save -> return WorldReader(world, save).activeEvents() } } }
    fun restoreBackup(path: String): String { val safety = RestoreManager(context).restoreBackup(selection.activeCampaignDirName(), path); openGameplaySaveDb().use { GameplayRuntimeBootstrap.requireReady(it, selection.activeCampaignRef().campaignId) }; return safety.absolutePath }
    fun techniqueBrowser(search: String = ""): List<TechniqueBrowserItem> { openWorldDb().use { world -> openSaveDb().use { save -> return TechniqueMissionReader(world, save).techniques(search) } } }
    fun missionBrowser(): List<MissionBrowserItem> { openWorldDb().use { world -> openSaveDb().use { save -> return TechniqueMissionReader(world, save).missions() } } }
    fun setActiveCampaign(dirName: String) { selection.setActiveCampaign(dirName); openSaveDb().use { ensureCurrentSchema(it) } }
    fun setActiveWorldPack(dirName: String) { selection.setActiveWorldPack(dirName) }
    fun createCampaign(name: String): File = selection.createCampaign(name)
    fun activeCampaignDirName(): String = selection.activeCampaignDirName()
    fun activeWorldPackDirName(): String = selection.activeWorldPackDirName()
    fun packageManager(): RpgPackageManager = RpgPackageManager(context)
    fun backups(): List<String> = BackupManager(context).listBackups().map { it.absolutePath }
    fun finalizeChapter(chapter: Int, title: String): Pair<String, String> { openSaveDb().use { save -> val hash = ChapterSaveManager(save).finalizeChapter(chapter, title); val backup = BackupManager(context).createBackup("chapter_$chapter"); return hash to backup.absolutePath } }
    internal fun applyPatch(patch: StatePatch): PatchResult { openSaveDb().use { save -> openCoreDb().use { core -> return StatePatchEngine(save, SourceOfTruthRegistry(core)).apply(patch) } } }
    private fun openSave(): SQLiteDatabase = SQLiteDatabase.openDatabase(File(saveDir, "campaign.db").absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
    private fun ensureCurrentSchema(saveDb: SQLiteDatabase) { CurrentSchema.ensure(saveDb, selection.activeCampaignRef().campaignId) }

    fun status(): StatusSnapshot {
        if (!File(saveDir, "campaign.db").exists()) return StatusSnapshot()
        return openSave().use { db ->
            val playerUid = runCatching { ensureCurrentSchema(db); ActivePlayerStore(db, selection.activeCampaignRef().campaignId).active()?.playerUid }.getOrNull()
            var location = "—"
            try { if (playerUid != null) db.rawQuery("SELECT location_uid FROM entity_positions WHERE entity_uid=? LIMIT 1", arrayOf(playerUid)).use { if (it.moveToFirst()) location = it.getString(0) ?: "—" } } catch (_: Exception) {}
            StatusSnapshot(location = location)
        }
    }

    fun time(): TimeSnapshot {
        if (!File(saveDir, "campaign.db").exists()) return TimeSnapshot()
        return openSave().use { db ->
            try { db.rawQuery("SELECT year_label,era_name,season,hour,minute FROM campaign_calendar WHERE id=1", null).use { if (it.moveToFirst()) return@use TimeSnapshot(label = it.getString(0) ?: "—", era = it.getString(1) ?: "—", season = it.getString(2) ?: "—", hour = "%02d:%02d".format(it.getInt(3), it.getInt(4))) } } catch (_: Exception) {}
            TimeSnapshot()
        }
    }

    fun chronicle(): List<ChronicleEntry> {
        if (!File(saveDir, "campaign.db").exists()) return emptyList()
        return openSave().use { db ->
            val out = mutableListOf<ChronicleEntry>()
            try { db.rawQuery("SELECT chapter,title,continuity_warnings_json FROM chapter_manifests_v2 ORDER BY chapter DESC LIMIT 100", null).use { while (it.moveToNext()) out += ChronicleEntry(chapter = it.getInt(0), title = it.getString(1) ?: "Rozdział ${it.getInt(0)}", summary = it.getString(2) ?: "") } } catch (_: Exception) {}
            out
        }
    }
}
