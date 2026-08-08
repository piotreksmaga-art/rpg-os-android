package com.rpgos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class LocalGameStore(private val context: Context) {
    private val baseDir = File(context.filesDir, "rpgos")
    private val selection = CampaignSelectionManager(context)
    private val saveDir: File get() = File(baseDir, "saves/${selection.activeCampaignDirName()}")
    private val worldDir: File get() = File(baseDir, "worldpacks/${selection.activeWorldPackDirName()}")
    private val coreDir = File(baseDir, "core")

    fun bootstrap() {
        baseDir.mkdirs()
        if (!File(saveDir, "campaign.db").exists()) {
            extractAssetZip("Naruto_Default.campaign.zip", saveDir)
        }
        if (!File(worldDir, "world.db").exists()) {
            extractAssetZip("Naruto.worldpack.zip", worldDir)
        }
        if (!File(coreDir, "rpg_core.db").exists()) {
            copyAsset("rpg_core.db", File(coreDir, "rpg_core.db"))
        }

        runCatching {
            openSaveDb().use { AutoRepairEngine().repair(it) }
        }.onFailure {
            DiagnosticLogger.log(context, "AUTO_REPAIR_BOOT_FAILED", it)
        }
    }

    private fun extractAssetZip(assetName: String, target: File) {
        target.mkdirs()
        context.assets.open(assetName).use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val outFile = File(target, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { output -> zip.copyTo(output) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
    }


    private fun copyAsset(assetName: String, target: File) {
        target.parentFile?.mkdirs()
        context.assets.open(assetName).use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
    }

    fun openSaveDb(): SQLiteDatabase = openSave()

    fun openWorldDb(): SQLiteDatabase =
        SQLiteDatabase.openDatabase(File(worldDir, "world.db").absolutePath, null, SQLiteDatabase.OPEN_READONLY)

    fun openCoreDb(): SQLiteDatabase =
        SQLiteDatabase.openDatabase(File(coreDir, "rpg_core.db").absolutePath, null, SQLiteDatabase.OPEN_READONLY)

    fun buildContext(playerInput: String, chapter: Int): ContextBundle {
        openSaveDb().use { save ->
            runCatching { AutoRepairEngine().repair(save) }
                .onFailure { DiagnosticLogger.log(context, "AUTO_REPAIR_SEND_FAILED", it) }

            openWorldDb().use { world ->
                return ContextBuilder(save, world).build(playerInput, chapter)
            }
        }
    }



    fun fullCharacterPanel(): CharacterPanelSnapshot {
        openSaveDb().use { db ->
            return CharacterPanelReader(db).load()
        }
    }






    fun npcs(search:String=""):List<NpcListItem>{
        openWorldDb().use{world->openSaveDb().use{save->return NpcWorldDashboardReader(world,save).npcs(search)}}
    }
    fun npcDetail(uid:String):NpcDetail{
        openWorldDb().use{world->openSaveDb().use{save->return NpcWorldDashboardReader(world,save).npcDetail(uid)}}
    }
    fun relationEdges():List<RelationEdge>{
        openWorldDb().use{world->openSaveDb().use{save->return NpcWorldDashboardReader(world,save).relationEdges()}}
    }
    fun economies():List<EconomySummary>{
        openWorldDb().use{world->openSaveDb().use{save->return NpcWorldDashboardReader(world,save).economies()}}
    }
    fun wars():List<WarSummary>{
        openWorldDb().use{world->openSaveDb().use{save->return NpcWorldDashboardReader(world,save).wars()}}
    }
    fun syncCheck():SyncCheckResult{
        openCoreDb().use{core->openWorldDb().use{world->openSaveDb().use{save->
            MigrationManager().ensureV1(save)
            return SyncManager().check(core,world,save)
        }}}
    }
    fun dbTables():List<DbTableInfo>{
        openCoreDb().use{core->openSaveDb().use{save->return DatabaseExplorer(core,save).tables()}}
    }

    fun visualLibrary(): List<VisualRecord> {
        openSaveDb().use { db ->
            return VisualLibrary(db).list()
        }
    }

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
    ): String {
        openSaveDb().use { db ->
            return VisualLibrary(db).add(
                title, kind, uri, chapter, relatedEntityUid, relatedLocationUid,
                prompt, revisedPrompt, sourceVisualUid
            )
        }
    }

    fun relationships(): List<RelationshipItem> {
        openWorldDb().use { world ->
            openSaveDb().use { save ->
                return SocialReader(world, save).relationships()
            }
        }
    }

    fun organizations(): List<OrganizationItem> {
        openWorldDb().use { world ->
            openSaveDb().use { save ->
                return SocialReader(world, save).organizations()
            }
        }
    }

    fun politics(): List<PoliticalItem> {
        openWorldDb().use { world ->
            openSaveDb().use { save ->
                return SocialReader(world, save).politics()
            }
        }
    }

    fun diagnostics(contextSummary: String): DiagnosticsSnapshot {
        val sot = openCoreDb().use { db ->
            try {
                db.rawQuery("SELECT COUNT(*) FROM source_of_truth_registry", null).use {
                    if (it.moveToFirst()) it.getInt(0) else 0
                }
            } catch (_: Exception) { 0 }
        }
        val alerts = openSaveDb().use { db ->
            try {
                db.rawQuery("SELECT COUNT(*) FROM gm_timeline_alerts WHERE resolved=0", null).use {
                    if (it.moveToFirst()) it.getInt(0) else 0
                }
            } catch (_: Exception) { 0 }
        }
        return DiagnosticsSnapshot(
            activeCampaignDirName(),
            activeWorldPackDirName(),
            backups().size,
            packageManager().listWorldPacks().size,
            packageManager().listCampaigns().size,
            contextSummary,
            sot,
            alerts
        )
    }

    fun worldRegions(): List<WorldRegionItem> {
        openWorldDb().use { world ->
            openSaveDb().use { save ->
                return WorldReader(world, save).regions()
            }
        }
    }

    fun worldLocations(search: String = ""): List<WorldLocationItem> {
        openWorldDb().use { world ->
            openSaveDb().use { save ->
                return WorldReader(world, save).locations(search)
            }
        }
    }

    fun activeWorldEvents(): List<WorldEventItem> {
        openWorldDb().use { world ->
            openSaveDb().use { save ->
                return WorldReader(world, save).activeEvents()
            }
        }
    }

    fun restoreBackup(path: String): String {
        val safety = RestoreManager(context).restoreBackup(selection.activeCampaignDirName(), path)
        return safety.absolutePath
    }

    fun techniqueBrowser(search: String = ""): List<TechniqueBrowserItem> {
        openWorldDb().use { world ->
            openSaveDb().use { save ->
                return TechniqueMissionReader(world, save).techniques(search)
            }
        }
    }

    fun missionBrowser(): List<MissionBrowserItem> {
        openWorldDb().use { world ->
            openSaveDb().use { save ->
                return TechniqueMissionReader(world, save).missions()
            }
        }
    }

    fun setActiveCampaign(dirName: String) {
        selection.setActiveCampaign(dirName)
    }

    fun setActiveWorldPack(dirName: String) {
        selection.setActiveWorldPack(dirName)
    }

    fun createCampaign(name: String): File = selection.createCampaign(name)

    fun activeCampaignDirName(): String = selection.activeCampaignDirName()
    fun activeWorldPackDirName(): String = selection.activeWorldPackDirName()

    fun packageManager(): RpgPackageManager = RpgPackageManager(context)

    fun backups(): List<String> = BackupManager(context).listBackups().map { it.absolutePath }

    fun finalizeChapter(chapter: Int, title: String): Pair<String, String> {
        openSaveDb().use { save ->
            val hash = ChapterSaveManager(save).finalizeChapter(chapter, title)
            val backup = BackupManager(context).createBackup("chapter_$chapter")
            return hash to backup.absolutePath
        }
    }

    fun applyPatch(patch: StatePatch): PatchResult {
        openSaveDb().use { save ->
            openCoreDb().use { core ->
                return StatePatchEngine(save, SourceOfTruthRegistry(core)).apply(patch)
            }
        }
    }

    private fun openSave(): SQLiteDatabase =
        SQLiteDatabase.openDatabase(File(saveDir, "campaign.db").absolutePath, null, SQLiteDatabase.OPEN_READWRITE)

    fun status(): StatusSnapshot {
        if (!File(saveDir, "campaign.db").exists()) return StatusSnapshot()
        return openSave().use { db ->
            var legacy = StatusSnapshot()
            try {
                db.rawQuery("SELECT location_uid FROM entity_positions LIMIT 1", null).use {
                    if (it.moveToFirst()) legacy = StatusSnapshot(location = it.getString(0) ?: "—")
                }
            } catch (_: Exception) {}
            GameMasterSessionReader141(db).status(legacy)
        }
    }

    fun time(): TimeSnapshot {
        if (!File(saveDir, "campaign.db").exists()) return TimeSnapshot()
        return openSave().use { db ->
            var legacy = TimeSnapshot()
            try {
                db.rawQuery(
                    "SELECT year_label,era_name,season,hour,minute FROM campaign_calendar WHERE id=1",
                    null
                ).use {
                    if (it.moveToFirst()) {
                        legacy = TimeSnapshot(
                            label = it.getString(0) ?: "—",
                            era = it.getString(1) ?: "—",
                            season = it.getString(2) ?: "—",
                            hour = "%02d:%02d".format(it.getInt(3), it.getInt(4))
                        )
                    }
                }
            } catch (_: Exception) {}
            GameMasterSessionReader141(db).time(legacy)
        }
    }

    fun chronicle(): List<ChronicleEntry> {
        if (!File(saveDir, "campaign.db").exists()) return emptyList()
        return openSave().use { db ->
            val out = mutableListOf<ChronicleEntry>()
            try {
                db.rawQuery(
                    "SELECT chapter,title,continuity_warnings_json FROM chapter_manifests_v2 ORDER BY chapter DESC LIMIT 100",
                    null
                ).use {
                    while (it.moveToNext()) {
                        out += ChronicleEntry(
                            chapter = it.getInt(0),
                            title = it.getString(1) ?: "Rozdział ${it.getInt(0)}",
                            summary = it.getString(2) ?: ""
                        )
                    }
                }
            } catch (_: Exception) {}
            out
        }
    }
}
