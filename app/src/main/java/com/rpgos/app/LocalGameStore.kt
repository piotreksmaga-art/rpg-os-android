package com.rpgos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Internal storage/infrastructure implementation. Raw writable DB access stays here; ordinary
 * application reads open only a database that explicit bootstrap has already made production-ready.
 */
internal class LocalGameStore(private val context: Context) {
    private val baseDir = File(context.filesDir, "rpgos")
    private val selection = CampaignSelectionManager(context)
    private val saveDir: File get() = File(baseDir, "saves/${selection.activeCampaignDirName()}")
    private val worldDir: File get() = File(baseDir, "worldpacks/${selection.activeWorldPackDirName()}")
    private val coreDir = File(baseDir, "core")
    private val worldActorPerceptionRuntime = Phase38WorldActorPerceptionRuntime()

    /** The single production initialization owner. No public authoritative writer is ready before this returns. */
    fun bootstrap() {
        baseDir.mkdirs()
        reconcileCanonicalPackageRoots()
        ensureBootstrapPackage("Naruto_Default.campaign.zip", saveDir, isCampaign = true)
        ensureBootstrapPackage("Naruto.worldpack.zip", worldDir, isCampaign = false)
        if (!File(coreDir, "rpg_core.db").exists()) copyAsset("rpg_core.db", File(coreDir, "rpg_core.db"))
        val campaignUid = selection.activeCampaignRef().campaignId
        openSaveDb().use { save ->
            val explicitBootstrap = {
                ensureCurrentSchema(save)
                AutoRepairEngine().repair(save)
                materializeWorldActors(save,campaignUid)
            }
            runCatching {
                if (GameplayMutationDatabaseGuards.isInstalled(save)) {
                    withAdministrativeMutationAuthority(save, campaignUid, explicitBootstrap)
                } else explicitBootstrap()
            }.onFailure { DiagnosticLogger.log(context, "AUTO_REPAIR_BOOT_FAILED", it) }
            ensureCharacterCreationDefinitions(save,campaignUid)
            GameplayRuntimeBootstrap.initialize(save, campaignUid)
            val snapshots=CampaignSnapshotManager(save,campaignUid,File(saveDir,"snapshots"))
            if(snapshots.latestValidCompatible()==null) snapshots.create(SnapshotKind.AUTOMATIC)
        }
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
        // Keep staging paths short: legacy Windows SQLite drivers used by local validation can
        // still fail before opening a valid DB when an otherwise legal absolute path is too long.
        val staging = File(baseDir, ".s/${if(isCampaign)"c" else "w"}-${System.nanoTime()}")
        staging.deleteRecursively()
        try {
            extractAssetZip(assetName, staging)
            val validation=runCatching{if(isCampaign)validator.validateCampaign(staging) else validator.validateWorldPack(staging)}
                .getOrElse{ValidationResult(false,it.message?:it::class.java.simpleName)}
            require(validation.ok) { "Bootstrap package $assetName failed canonical validation: ${validation.message}" }
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

    /** Pure open+verify boundary. It never migrates, repairs, creates schema, or writes readiness metadata. */
    internal fun openGameplaySaveDb(): SQLiteDatabase {
        val db = openSave()
        val campaignUid = selection.activeCampaignRef().campaignId
        return try {
            GameplayRuntimeBootstrap.requireReady(db, campaignUid)
            db
        } catch (failure: Throwable) {
            db.close()
            throw failure
        }
    }

    private fun <T> withAdminReadyDb(block: (SQLiteDatabase, String) -> T): T = openGameplaySaveDb().use { db ->
        val campaignUid = selection.activeCampaignRef().campaignId
        withAdministrativeMutationAuthority(db, campaignUid) { block(db, campaignUid) }
    }

    fun openWorldDb(): SQLiteDatabase = SQLiteDatabase.openDatabase(File(worldDir, "world.db").absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    fun openCoreDb(): SQLiteDatabase = SQLiteDatabase.openDatabase(File(coreDir, "rpg_core.db").absolutePath, null, SQLiteDatabase.OPEN_READONLY)

    fun buildContext(playerInput: String, chapter: Int, audience: AudienceContext, purpose: PurposeContext): ContextBundle {
        val campaignId = selection.activeCampaignRef().campaignId
        if (audience.campaignUid != campaignId || purpose.campaignUid != campaignId) throw VisibilityAuthorityFailure.CrossCampaign()
        openGameplaySaveDb().use { save ->
            openWorldDb().use { world ->
                val reads=ProtectedCampaignReadRepository.borrowed(save,campaignId){ActivePlayerStore(save,campaignId).active()}
                return ContextBuilder(save, world, protectedReadsOverride=reads, worldActorPerceptionRuntime=worldActorPerceptionRuntime).build(playerInput, chapter, audience, purpose)
            }
        }
    }

    internal fun buildTrustedContext(playerInput:String,chapter:Int,audience:AudienceContext,purpose:PurposeContext,trusted:TrustedPrincipalContext):ContextBundle {
        val campaignId=selection.activeCampaignRef().campaignId
        if(audience.campaignUid!=campaignId||purpose.campaignUid!=campaignId||trusted.campaignUid!=campaignId)throw VisibilityAuthorityFailure.CrossCampaign()
        openGameplaySaveDb().use{save->openWorldDb().use{world->
            val reads=ProtectedCampaignReadRepository.borrowedTrusted(save,campaignId,{ActivePlayerStore(save,campaignId).active()},trusted)
            return ContextBuilder(save,world,protectedReadsOverride=reads,worldActorPerceptionRuntime=worldActorPerceptionRuntime).build(playerInput,chapter,audience,purpose)
        }}
    }

    internal fun issueWorldActorEventSignal(
        event:WorldEventItem,
        evidence:Map<String,Any?>,
        quality:Double=1.0,
        uncertainty:PerceptionUncertainty=PerceptionUncertainty(1.0,1.0,1.0),
        presentedSubject:VisibilitySubjectRef?=null
    ):PerceptionSignal = worldActorPerceptionRuntime.issueWorldEventSignal(
        activeCampaignId(),event,evidence,quality,uncertainty,presentedSubject
    )

    internal fun issueWorldActorEventCapability(
        audience:AudienceContext,
        minimumDetectionQuality:Double=0.0,
        maximumDisclosure:DisclosureLevel=DisclosureLevel.DISCLOSE_FULL,
        capabilityUid:String="WORLD_EVENT:${audience.principal?.kindUid}:${audience.principal?.uid}"
    ):PerceptionCapability {
        requireActiveVisibility(audience,PurposeContext(audience.campaignUid,VisibilityPurposeKinds.WORLD_ACTOR_REASONING))
        require(audience.audienceKindUid==AudienceKinds.WORLD_ACTOR){"RPGOS-P38-PERCEPTION:WORLD_ACTOR_REQUIRED"}
        openGameplaySaveDb().use { db ->
            val active=ActivePlayerStore(db,activeCampaignId()).active()
            val reads=ProtectedCampaignReadRepository.borrowed(db,activeCampaignId()){active}
            val trusted=requireNotNull(reads.trustedPrincipal(audience)){"RPGOS-P38-PERCEPTION:TRUSTED_OBSERVER_REQUIRED"}
            return worldActorPerceptionRuntime.issueWorldEventCapability(trusted,minimumDetectionQuality,maximumDisclosure,capabilityUid)
        }
    }

    internal fun clearWorldActorPerception() = worldActorPerceptionRuntime.clearCampaign(activeCampaignId())

    internal fun activeCampaignId(): String = selection.activeCampaignRef().campaignId
    private fun requireActiveVisibility(audience:AudienceContext,purpose:PurposeContext){
        val campaign=activeCampaignId()
        if(audience.campaignUid!=campaign||purpose.campaignUid!=campaign)throw VisibilityAuthorityFailure.CrossCampaign()
    }

    fun canonDivergences(): List<CanonDivergenceRecord> = openGameplaySaveDb().use { db ->
        CanonDivergenceStore(db, selection.activeCampaignRef().campaignId).list()
    }

    fun activePlayerRef(): ActivePlayerRef? { openGameplaySaveDb().use { db -> return ActivePlayerStore(db, selection.activeCampaignRef().campaignId).active() } }
    fun setActivePlayer(playerUid: String): ActivePlayerRef = withAdminReadyDb { db, campaignUid ->
        val active=ActivePlayerStore(db,campaignUid).set(playerUid)
        materializeWorldActors(db,campaignUid)
        active
    }
    fun characterCreationCatalog():CharacterCreationCatalog=openGameplaySaveDb().use(::characterCreationCatalog)
    fun createPlayerCharacter(draft:PlayerCharacterCreationDraft,confirmation:PlayerCharacterCreationConfirmation):PlayerCharacterBootstrapReceipt=
        openGameplaySaveDb().use{db->
            val catalog=characterCreationCatalog(db)
            val campaignUid=selection.activeCampaignRef().campaignId
            val receipt=PlayerCharacterBootstrapService(db,campaignUid,selection.currentWorldPackAuthority().binding.worldPackUid,catalog).commit(draft,confirmation)
            materializeWorldActorsWithAuthority(db,campaignUid)
            receipt
        }
    fun playerState(): PlayerStateSnapshot? { openGameplaySaveDb().use { db -> return PlayerStateStore(db, selection.activeCampaignRef().campaignId).load() } }
    fun statDefinitions(): List<StatDefinition> { openGameplaySaveDb().use { db -> return StatResourceStore(db, selection.activeCampaignRef().campaignId).statDefinitions() } }
    fun resourceDefinitions(): List<ResourceDefinition> { openGameplaySaveDb().use { db -> return StatResourceStore(db, selection.activeCampaignRef().campaignId).resourceDefinitions() } }
    fun registerStatDefinitions(worldPackUid: String, definitions: List<StatDefinition>) = withAdminReadyDb { db, campaignUid -> StatResourceStore(db, campaignUid).registerStatDefinitions(worldPackUid, definitions) }
    fun registerResourceDefinitions(worldPackUid: String, definitions: List<ResourceDefinition>) = withAdminReadyDb { db, campaignUid -> StatResourceStore(db, campaignUid).registerResourceDefinitions(worldPackUid, definitions) }
    fun playerStats(): List<PlayerStat> { openGameplaySaveDb().use { db -> val campaignId = selection.activeCampaignRef().campaignId; val playerUid = ActivePlayerStore(db, campaignId).active()?.playerUid ?: return emptyList(); return StatResourceStore(db, campaignId).playerStats(playerUid) } }
    fun playerResources(): List<PlayerResource> { openGameplaySaveDb().use { db -> val campaignId = selection.activeCampaignRef().campaignId; val playerUid = ActivePlayerStore(db, campaignId).active()?.playerUid ?: return emptyList(); return StatResourceStore(db, campaignId).playerResources(playerUid) } }
    fun fullCharacterPanel(audience: AudienceContext, purpose: PurposeContext): CharacterPanelSnapshot { val campaign=activeCampaignId();if(audience.campaignUid!=campaign||purpose.campaignUid!=campaign)throw VisibilityAuthorityFailure.CrossCampaign();openGameplaySaveDb().use { db -> val active=ActivePlayerStore(db,campaign).active();val playerUid=active?.playerUid?:return CharacterPanelSnapshot.unresolved();val reads=ProtectedCampaignReadRepository.borrowed(db,campaign){active};val authorized=reads.playerState(audience,purpose,playerUid);return CharacterPanelReader(db,playerUid).load(audience,purpose,authorized) } }
    fun fullCharacterPanelV2(audience:AudienceContext,purpose:PurposeContext):CharacterPanelSnapshotV2?{
        val campaign=activeCampaignId()
        if(audience.campaignUid!=campaign||purpose.campaignUid!=campaign)throw VisibilityAuthorityFailure.CrossCampaign()
        openGameplaySaveDb().use{db->
            val active=ActivePlayerStore(db,campaign).active()?:return null
            val reads=ProtectedCampaignReadRepository.borrowed(db,campaign){active}
            if(reads.playerState(audience,purpose,active.playerUid) !is ProtectedReadResult.Allow)return null
            return CharacterPanelSnapshotV2Builder.build(ProductionCharacterPanelV2ReadSource(db),campaign,active.playerUid)
        }
    }
    fun npcs(search:String,audience:AudienceContext,purpose:PurposeContext):List<NpcListItem>{ requireActiveVisibility(audience,purpose);openWorldDb().use{world->openGameplaySaveDb().use{save->return NpcWorldDashboardReader(world,save).npcs(search,audience,purpose)}} }
    fun npcDetail(uid:String,audience:AudienceContext,purpose:PurposeContext):NpcDetail{ requireActiveVisibility(audience,purpose);openWorldDb().use{world->openGameplaySaveDb().use{save->return NpcWorldDashboardReader(world,save).npcDetail(uid,audience,purpose)}} }
    fun relationEdges(audience:AudienceContext,purpose:PurposeContext):List<RelationEdge>{ requireActiveVisibility(audience,purpose);openWorldDb().use{world->openGameplaySaveDb().use{save->return NpcWorldDashboardReader(world,save).relationEdges(audience,purpose)}} }
    fun economies(audience:AudienceContext,purpose:PurposeContext):List<EconomySummary>{ requireActiveVisibility(audience,purpose);openWorldDb().use{world->openGameplaySaveDb().use{save->return NpcWorldDashboardReader(world,save).economies(audience,purpose)}} }
    fun wars(audience:AudienceContext,purpose:PurposeContext):List<WarSummary>{ requireActiveVisibility(audience,purpose);openWorldDb().use{world->openGameplaySaveDb().use{save->return NpcWorldDashboardReader(world,save).wars(audience,purpose)}} }
    fun syncCheck():SyncCheckResult{ openCoreDb().use{core->openWorldDb().use{world->openGameplaySaveDb().use{save-> return SyncManager().check(core,world,save) }}} }
    fun dbTables():List<DbTableInfo>{ openCoreDb().use{core->openGameplaySaveDb().use{save->return DatabaseExplorer(core,save).tables()}} }
    fun visualLibrary(): List<VisualRecord> { openGameplaySaveDb().use { return VisualLibrary(it).list() } }
    fun addVisual(title: String, kind: String, uri: String, chapter: Int?, relatedEntityUid: String?, relatedLocationUid: String?, prompt: String?, revisedPrompt: String?, sourceVisualUid: String? = null): String { openGameplaySaveDb().use { return VisualLibrary(it).add(title, kind, uri, chapter, relatedEntityUid, relatedLocationUid, prompt, revisedPrompt, sourceVisualUid) } }
    fun relationships(audience:AudienceContext,purpose:PurposeContext): List<RelationshipItem> { requireActiveVisibility(audience,purpose);openWorldDb().use { world -> openGameplaySaveDb().use { save -> return SocialReader(world, save).relationships(audience,purpose) } } }
    fun organizations(audience:AudienceContext,purpose:PurposeContext): List<OrganizationItem> { requireActiveVisibility(audience,purpose);openWorldDb().use { world -> openGameplaySaveDb().use { save -> return SocialReader(world, save).organizations(audience,purpose) } } }
    fun politics(audience:AudienceContext,purpose:PurposeContext): List<PoliticalItem> { requireActiveVisibility(audience,purpose);openWorldDb().use { world -> openGameplaySaveDb().use { save -> return SocialReader(world, save).politics(audience,purpose) } } }

    fun diagnostics(contextSummary: String): DiagnosticsSnapshot {
        val sot = openCoreDb().use { db -> try { db.rawQuery("SELECT COUNT(*) FROM source_of_truth_registry", null).use { if (it.moveToFirst()) it.getInt(0) else 0 } } catch (_: Exception) { 0 } }
        val alerts = openGameplaySaveDb().use { db -> try { db.rawQuery("SELECT COUNT(*) FROM gm_timeline_alerts WHERE resolved=0", null).use { if (it.moveToFirst()) it.getInt(0) else 0 } } catch (_: Exception) { 0 } }
        return DiagnosticsSnapshot(activeCampaignDirName(), activeWorldPackDirName(), backups().size, packageManager().listWorldPacks().size, packageManager().listCampaigns().size, contextSummary, sot, alerts)
    }

    fun worldRegions(): List<WorldRegionItem> { openWorldDb().use { world -> openGameplaySaveDb().use { save -> return WorldReader(world, save).regions() } } }
    fun worldLocations(search: String = ""): List<WorldLocationItem> { openWorldDb().use { world -> openGameplaySaveDb().use { save -> return WorldReader(world, save).locations(search) } } }
    fun activeWorldEvents(audience:AudienceContext,purpose:PurposeContext): List<WorldEventItem> { requireActiveVisibility(audience,purpose);openWorldDb().use { world -> openGameplaySaveDb().use { save -> return WorldReader(world, save).activeEvents(audience,purpose) } } }

    fun restoreBackup(path: String): String {
        val safety = RestoreManager(context).restoreBackup(selection.activeCampaignDirName(), path)
        val campaignUid=selection.activeCampaignRef().campaignId
        openSaveDb().use { restored -> prepareCampaignRuntime(restored,campaignUid) }
        openGameplaySaveDb().use { GameplayRuntimeBootstrap.requireReady(it, selection.activeCampaignRef().campaignId) }
        return safety.absolutePath
    }

    fun techniqueBrowser(search: String = ""): List<TechniqueBrowserItem> { openWorldDb().use { world -> openGameplaySaveDb().use { save -> return TechniqueMissionReader(world, save).techniques(search) } } }
    fun missionBrowser(): List<MissionBrowserItem> { openWorldDb().use { world -> openGameplaySaveDb().use { save -> return TechniqueMissionReader(world, save).missions() } } }

    fun setActiveCampaign(dirName: String) {
        val previousCampaign=selection.activeCampaignDirName()
        selection.setActiveCampaign(dirName)
        try{
            val campaignUid=selection.activeCampaignRef().campaignId
            openSaveDb().use { db -> prepareCampaignRuntime(db,campaignUid) }
        }catch(t:Throwable){
            // A failed preparation of an older save must not leave it selected. Otherwise the next
            // startup would bootstrap the same incomplete campaign and could enter a crash loop.
            runCatching{selection.setActiveCampaign(previousCampaign)}
                .onFailure{DiagnosticLogger.log(context,"CAMPAIGN_ACTIVATION_ROLLBACK_FAILED",it)}
            throw t
        }
    }
    fun setActiveWorldPack(dirName: String) {
        selection.setActiveWorldPack(dirName)
        val campaignUid=selection.activeCampaignRef().campaignId
        openSaveDb().use{db->prepareCampaignRuntime(db,campaignUid)}
    }
    fun createCampaign(name: String): File {
        val previousCampaign=selection.activeCampaignDirName()
        val created = selection.createCampaign(name)
        try{
            val campaignUid=selection.activeCampaignRef().campaignId
            openSaveDb().use { db -> prepareCampaignRuntime(db,campaignUid) }
            return created
        }catch(t:Throwable){
            // A failed post-clone migration/bootstrap must not leave a broken campaign selected.
            // Restore the previous authority first; the incomplete clone can then be quarantined
            // through the same recoverable trash path used by the user-facing campaign manager.
            runCatching{selection.setActiveCampaign(previousCampaign)}
                .onFailure{DiagnosticLogger.log(context,"CAMPAIGN_CREATE_ROLLBACK_SELECTION_FAILED",it)}
            runCatching{selection.moveCampaignToTrash(created.name)}
                .onFailure{DiagnosticLogger.log(context,"CAMPAIGN_CREATE_QUARANTINE_FAILED",it)}
            throw t
        }
    }
    fun moveCampaignToTrash(dirName:String):File = selection.moveCampaignToTrash(dirName)
    fun activeCampaignDirName(): String = selection.activeCampaignDirName()
    fun activeWorldPackDirName(): String = selection.activeWorldPackDirName()
    fun packageManager(): RpgPackageManager = RpgPackageManager(context)
    fun backups(): List<String> = BackupManager(context).listBackups().map { it.absolutePath }
    fun createSnapshot(kind:SnapshotKind=SnapshotKind.AUTOMATIC,pinned:Boolean=false):CampaignSnapshotDescriptor = openGameplaySaveDb().use{CampaignSnapshotManager(it,selection.activeCampaignRef().campaignId,File(saveDir,"snapshots")).create(kind,pinned)}
    fun snapshots():List<CampaignSnapshotDescriptor> = openGameplaySaveDb().use{CampaignSnapshotManager(it,selection.activeCampaignRef().campaignId,File(saveDir,"snapshots")).list()}
    fun restoreLatestSnapshot():String {
        return restoreSnapshot(null)
    }
    fun restoreSnapshot(snapshotUid:String?):String {
        val active=File(saveDir,"campaign.db");val db=openGameplaySaveDb();val manager=CampaignSnapshotManager(db,selection.activeCampaignRef().campaignId,File(saveDir,"snapshots"))
        val staged=manager.reconstructToVerifiedStaging(snapshotUid);manager.activateVerifiedStaging(active,staged)
        val campaignUid=selection.activeCampaignRef().campaignId
        openSaveDb().use{prepareCampaignRuntime(it,campaignUid)}
        return active.absolutePath
    }
    fun finalizeChapter(chapter: Int, title: String): Pair<String, String> { openGameplaySaveDb().use { save -> val hash = ChapterSaveManager(save).finalizeChapter(chapter, title); CampaignSnapshotManager(save,selection.activeCampaignRef().campaignId,File(saveDir,"snapshots")).create(SnapshotKind.AUTOMATIC);val backup = BackupManager(context).createBackup("chapter_$chapter"); return hash to backup.absolutePath } }
    internal fun applyPatch(patch: StatePatch): PatchResult { openGameplaySaveDb().use { save -> openCoreDb().use { core -> return StatePatchEngine(save, SourceOfTruthRegistry(core)).apply(patch) } } }
    private fun openSave(): SQLiteDatabase = SQLiteDatabase.openDatabase(File(saveDir, "campaign.db").absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
    private fun ensureCurrentSchema(saveDb: SQLiteDatabase) { CurrentSchema.ensure(saveDb, selection.activeCampaignRef().campaignId) }

    /**
     * Prepares a cloned, restored, or newly activated campaign through one administrative boundary.
     * A template can already contain Phase 32 guards, so even additive schema/default-definition
     * writes must carry ADMIN authority before the normal gameplay-ready verification is restored.
     */
    private fun prepareCampaignRuntime(saveDb:SQLiteDatabase,campaignUid:String){
        CampaignRuntimeLifecycleLock.withRecovery(campaignUid){
            val prepare={
                ensureCurrentSchema(saveDb)
                ensureCharacterCreationDefinitions(saveDb,campaignUid)
                materializeWorldActors(saveDb,campaignUid)
            }
            if(GameplayMutationDatabaseGuards.isInstalled(saveDb)){
                withAdministrativeMutationAuthority(saveDb,campaignUid,prepare)
            }else prepare()
            GameplayRuntimeBootstrap.initialize(saveDb,campaignUid)
        }
    }

    private fun materializeWorldActors(saveDb:SQLiteDatabase,campaignUid:String){
        if(!File(worldDir,"world.db").isFile)return
        openWorldDb().use{world->WorldActorMechanicalBootstrap.materialize(world,saveDb,campaignUid)}
    }
    private fun materializeWorldActorsWithAuthority(saveDb:SQLiteDatabase,campaignUid:String){
        val action={materializeWorldActors(saveDb,campaignUid)}
        if(GameplayMutationDatabaseGuards.isInstalled(saveDb))withAdministrativeMutationAuthority(saveDb,campaignUid,action) else action()
    }

    private fun ensureCharacterCreationDefinitions(saveDb:SQLiteDatabase,campaignUid:String){
        // Historical migration/restore fixtures can legitimately omit the World Pack package.
        // They still migrate, but no character definitions may be invented for a missing or
        // invalid authority. Normal bootstrap installs and validates the pack before this point.
        if(!File(worldDir,"world.db").isFile)return
        val worldPack=selection.currentWorldPackAuthority().binding
        val install={
            openWorldDb().use{world->CharacterCreationDefinitionBootstrap(saveDb,world,worldPack).ensure()}
        }
        if(GameplayMutationDatabaseGuards.isInstalled(saveDb))withAdministrativeMutationAuthority(saveDb,campaignUid,install) else install()
    }

    private fun characterCreationCatalog(saveDb:SQLiteDatabase):CharacterCreationCatalog{
        val locations=openWorldDb().use{world->WorldReader(world,saveDb).locations().map{
            CharacterCreationDefinitionOption(CharacterCreationDefinitionKind.STARTING_LOCATION,it.uid,it.name)
        }}
        return CharacterCreationCatalogReader(
            saveDb,selection.activeCampaignRef().campaignId,selection.currentWorldPackAuthority().binding.worldPackUid,locations
        ).read()
    }

    fun status(): StatusSnapshot {
        if (!File(saveDir, "campaign.db").exists()) return StatusSnapshot()
        return openGameplaySaveDb().use { db ->
            val playerUid = ActivePlayerStore(db, selection.activeCampaignRef().campaignId).active()?.playerUid
            var location = "—"
            try { if (playerUid != null) db.rawQuery("SELECT location_uid FROM entity_positions WHERE entity_uid=? LIMIT 1", arrayOf(playerUid)).use { if (it.moveToFirst()) location = it.getString(0) ?: "—" } } catch (_: Exception) {}
            StatusSnapshot(location = location)
        }
    }

    fun time(): TimeSnapshot {
        if (!File(saveDir, "campaign.db").exists()) return TimeSnapshot()
        return openGameplaySaveDb().use { db ->
            try { db.rawQuery("SELECT year_label,era_name,season,hour,minute FROM campaign_calendar WHERE id=1", null).use { if (it.moveToFirst()) return@use TimeSnapshot(label = it.getString(0) ?: "—", era = it.getString(1) ?: "—", season = it.getString(2) ?: "—", hour = "%02d:%02d".format(it.getInt(3), it.getInt(4))) } } catch (_: Exception) {}
            TimeSnapshot()
        }
    }

    fun chronicle(): List<ChronicleEntry> {
        if (!File(saveDir, "campaign.db").exists()) return emptyList()
        return openGameplaySaveDb().use { db ->
            val out = mutableListOf<ChronicleEntry>()
            try { db.rawQuery("SELECT chapter,title,continuity_warnings_json FROM chapter_manifests_v2 ORDER BY chapter DESC LIMIT 100", null).use { while (it.moveToNext()) out += ChronicleEntry(chapter = it.getInt(0), title = it.getString(1) ?: "Rozdział ${it.getInt(0)}", summary = it.getString(2) ?: "") } } catch (_: Exception) {}
            out
        }
    }
}
