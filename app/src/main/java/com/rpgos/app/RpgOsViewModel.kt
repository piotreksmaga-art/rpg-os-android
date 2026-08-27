package com.rpgos.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RpgOsViewModel(app: Application) : AndroidViewModel(app) {
    private val store = LocalGameStore(app)
    private val repository = UnifiedGameRepository(app)
    private val appSettings = AppSettings(app)
    private val providerCenterApplication=AndroidAiProviderCenterApplication(app)
    private fun playerAudience() = VisibilityAudienceFactory.player(store.activeCampaignId())
    private fun playerPurpose(uid:String) = PurposeContext(store.activeCampaignId(),uid)
    private fun diagnosticAudience() = VisibilityAudienceFactory.diagnostic(store.activeCampaignId())
    private fun diagnosticPurpose() = PurposeContext(store.activeCampaignId(),VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)

    private val _settings = MutableStateFlow(appSettings.load())
    val settings: StateFlow<RpgOsSettings> = _settings

    private val _aiProviderCenter=MutableStateFlow(providerCenterApplication.initialState(_settings.value.ai))
    val aiProviderCenter:StateFlow<AiProviderCenterUiState> = _aiProviderCenter

    private val _chatTurnUi=MutableStateFlow(ChatTurnUiState())
    val chatTurnUi:StateFlow<ChatTurnUiState> = _chatTurnUi
    @Volatile private var activeAiCancellation:MutableAiCancellationSignal?=null
    private var pendingNarrationRecovery:ChatNarrationRecoveryToken?=null
    private val productionEngine by lazy{
        ProductionGameEngineCompositionRoot(app,repository,providerCenterApplication,configuration={_settings.value.ai})
    }
    private val chatApplication:ChatApplicationPort by lazy{
        DynamicCanonicalChatApplication{productionEngine.chatApplication()}
    }
    private val characterCreationApplication by lazy{productionEngine.characterCreationApplication()}
    private var pendingCharacterCreationUid:String?=null

    private val _developerStatus = MutableStateFlow("Nie uruchomiono testów.")
    val developerStatus: StateFlow<String> = _developerStatus

    private val _developerDiagnostic = MutableStateFlow("")
    val developerDiagnostic: StateFlow<String> = _developerDiagnostic

    private val _messages = MutableStateFlow(
        listOf(ChatMessage("system", "RPG OS ALPHA 1.3.0-alpha7-core54 • pełny Core Phase 1–54 • trwała mechanika i bezpieczna narracja."))
    )
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _status = MutableStateFlow(StatusSnapshot())
    val status: StateFlow<StatusSnapshot> = _status

    private val _characterPanel = MutableStateFlow(
        CharacterPanelSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    )
    val characterPanel: StateFlow<CharacterPanelSnapshot> = _characterPanel

    private val _time = MutableStateFlow(TimeSnapshot())
    val time: StateFlow<TimeSnapshot> = _time

    private val _chronicle = MutableStateFlow<List<ChronicleEntry>>(emptyList())
    val chronicle: StateFlow<List<ChronicleEntry>> = _chronicle

    private val _worldPacks = MutableStateFlow<List<WorldPackInfo>>(emptyList())
    val worldPacks: StateFlow<List<WorldPackInfo>> = _worldPacks

    private val _campaigns = MutableStateFlow<List<CampaignInfo>>(emptyList())
    val campaigns: StateFlow<List<CampaignInfo>> = _campaigns

    private val _backups = MutableStateFlow<List<String>>(emptyList())
    val backups: StateFlow<List<String>> = _backups

    private val _npcs = MutableStateFlow<List<NpcListItem>>(emptyList())
    val npcs: StateFlow<List<NpcListItem>> = _npcs

    private val _selectedNpc = MutableStateFlow<NpcDetail?>(null)
    val selectedNpc: StateFlow<NpcDetail?> = _selectedNpc

    private val _relationEdges = MutableStateFlow<List<RelationEdge>>(emptyList())
    val relationEdges: StateFlow<List<RelationEdge>> = _relationEdges

    private val _economies = MutableStateFlow<List<EconomySummary>>(emptyList())
    val economies: StateFlow<List<EconomySummary>> = _economies

    private val _wars = MutableStateFlow<List<WarSummary>>(emptyList())
    val wars: StateFlow<List<WarSummary>> = _wars

    private val _sync = MutableStateFlow(SyncCheckResult(true,emptyList()))
    val sync: StateFlow<SyncCheckResult> = _sync

    private val _dbTables = MutableStateFlow<List<DbTableInfo>>(emptyList())
    val dbTables: StateFlow<List<DbTableInfo>> = _dbTables

    private val _relationships = MutableStateFlow<List<RelationshipItem>>(emptyList())
    val relationships: StateFlow<List<RelationshipItem>> = _relationships

    private val _organizations = MutableStateFlow<List<OrganizationItem>>(emptyList())
    val organizations: StateFlow<List<OrganizationItem>> = _organizations

    private val _politics = MutableStateFlow<List<PoliticalItem>>(emptyList())
    val politics: StateFlow<List<PoliticalItem>> = _politics

    private val _diagnostics = MutableStateFlow(
        DiagnosticsSnapshot("", "", 0, 0, 0, "", 0, 0)
    )
    val diagnostics: StateFlow<DiagnosticsSnapshot> = _diagnostics

    private val _visualLibrary = MutableStateFlow<List<VisualRecord>>(emptyList())
    val visualLibrary: StateFlow<List<VisualRecord>> = _visualLibrary

    private val _visualSuggestions = MutableStateFlow<List<VisualSuggestion>>(emptyList())
    val visualSuggestions: StateFlow<List<VisualSuggestion>> = _visualSuggestions

    private val _generatedImages = MutableStateFlow<List<GalleryImageItem>>(emptyList())
    val generatedImages: StateFlow<List<GalleryImageItem>> = _generatedImages

    private val _imageStatus = MutableStateFlow("")
    val imageStatus: StateFlow<String> = _imageStatus

    private val _regions = MutableStateFlow<List<WorldRegionItem>>(emptyList())
    val regions: StateFlow<List<WorldRegionItem>> = _regions

    private val _locations = MutableStateFlow<List<WorldLocationItem>>(emptyList())
    val locations: StateFlow<List<WorldLocationItem>> = _locations

    private val _worldEvents = MutableStateFlow<List<WorldEventItem>>(emptyList())
    val worldEvents: StateFlow<List<WorldEventItem>> = _worldEvents

    private val _techniques = MutableStateFlow<List<TechniqueBrowserItem>>(emptyList())
    val techniques: StateFlow<List<TechniqueBrowserItem>> = _techniques

    private val _missions = MutableStateFlow<List<MissionBrowserItem>>(emptyList())
    val missions: StateFlow<List<MissionBrowserItem>> = _missions

    private val _activeCampaign = MutableStateFlow("")
    val activeCampaign: StateFlow<String> = _activeCampaign

    private val _activeWorldPack = MutableStateFlow("")
    val activeWorldPack: StateFlow<String> = _activeWorldPack

    private val _updateStatus = MutableStateFlow("Nie sprawdzano aktualizacji.")
    val updateStatus: StateFlow<String> = _updateStatus

    private val _availableUpdate = MutableStateFlow<OnlineUpdateInfo?>(null)
    val availableUpdate: StateFlow<OnlineUpdateInfo?> = _availableUpdate

    private var downloadedUpdateApk: java.io.File? = null

    fun checkForUpdates(context: android.content.Context) {
        viewModelScope.launch {
            try {
                _updateStatus.value = "Sprawdzanie aktualizacji..."
                val info = UpdateManager(context, _settings.value.updateFeedUrl).checkOnline()
                _availableUpdate.value = info
                val installed = context.packageManager.getPackageInfo(context.packageName, 0)
                val currentCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
                    installed.longVersionCode
                else
                    @Suppress("DEPRECATION") installed.versionCode.toLong()

                _updateStatus.value =
                    if (info.versionCode > currentCode)
                        "Dostępna wersja ${info.versionName} (${info.versionCode})."
                    else "Masz najnowszą wersję."
            } catch (t: Throwable) {
                DiagnosticLogger.log(context, "UPDATE_CHECK_FAILED", t)
                _updateStatus.value = "Błąd sprawdzania aktualizacji: ${t.message}"
            }
        }
    }

    fun downloadOnlineUpdate(context: android.content.Context) {
        val info = _availableUpdate.value ?: run {
            _updateStatus.value = "Najpierw sprawdź aktualizacje."
            return
        }
        viewModelScope.launch {
            try {
                _updateStatus.value = "Pobieranie i weryfikacja APK..."
                downloadedUpdateApk = UpdateManager(context, _settings.value.updateFeedUrl)
                    .downloadOnline(info)
                _updateStatus.value = "Aktualizacja pobrana i zweryfikowana."
            } catch (t: Throwable) {
                DiagnosticLogger.log(context, "UPDATE_DOWNLOAD_FAILED", t)
                _updateStatus.value = "Błąd pobierania: ${t.message}"
            }
        }
    }

    fun selectLocalUpdate(context: android.content.Context, uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                _updateStatus.value = "Sprawdzanie lokalnego APK..."
                val (file, result) = UpdateManager(context, _settings.value.updateFeedUrl).importLocal(uri)
                if (result.ok) {
                    downloadedUpdateApk = file
                    _updateStatus.value =
                        "Lokalny APK gotowy: ${result.versionName} (${result.versionCode})."
                } else {
                    downloadedUpdateApk = null
                    _updateStatus.value = "APK odrzucony: ${result.message}"
                }
            } catch (t: Throwable) {
                DiagnosticLogger.log(context, "LOCAL_UPDATE_FAILED", t)
                _updateStatus.value = "Błąd lokalnego APK: ${t.message}"
            }
        }
    }

    fun installPreparedUpdate(context: android.content.Context) {
        val apk = downloadedUpdateApk ?: run {
            _updateStatus.value = "Nie ma przygotowanego APK."
            return
        }
        try {
            _updateStatus.value = "Backup i uruchamianie instalatora..."
            UpdateManager(context, _settings.value.updateFeedUrl).install(apk)
        } catch (t: Throwable) {
            DiagnosticLogger.log(context, "UPDATE_INSTALL_FAILED", t)
            _updateStatus.value = "Instalacja: ${t.message}"
        }
    }

    private val _lastContextSummary = MutableStateFlow("ContextBundle: inicjalizacja...")
    val lastContextSummary: StateFlow<String> = _lastContextSummary

    init {
        providerCenterApplication.onOpenRouterCallback{callback->completeOpenRouter(callback)}
        store.bootstrap()
        pendingNarrationRecovery=runCatching{chatApplication.pendingRecovery()}.onFailure{DiagnosticLogger.log(app,"NARRATIVE_RECOVERY_DISCOVERY_FAILED",it)}.getOrNull()
        pendingNarrationRecovery?.let{token->_chatTurnUi.value=ChatTurnUiState(
            ChatTurnUiStage.COMMITTED_NARRATION_PENDING,token.request.requestUid,
            "Ostatnia tura jest zapisana. Narrację można bezpiecznie odzyskać.",canRetryNarration=true
        )}
        if(store.activePlayerRef()==null)_messages.value+=ChatMessage("gm","Zanim rozpoczniemy przygodę, wspólnie stworzymy Twoją postać. Opowiedz mi, kim chcesz grać.")
        refresh()
        buildStartupContext()
    }

    private fun buildStartupContext() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            runCatching {
                val chapter = (_chronicle.value.maxOfOrNull { it.chapter } ?: 0) + 1
                val context = store.buildContext("STARTUP_CONTEXT", chapter,playerAudience(),playerPurpose(VisibilityPurposeKinds.GAMEPLAY_NARRATION))
                _lastContextSummary.value =
                    "ContextBundle v1: wątki=${context.activeThreads.size}, NPC=${context.relevantNpcs.size}, " +
                    "wiedza=${context.npcKnowledge.size}, wydarzenia=${context.activeWorldEvents.size}, " +
                    "techniki=${context.playerTechniques.size}, pamięć=${context.retrievedLongTermMemory.size}"
                _diagnostics.value = store.diagnostics(_lastContextSummary.value)
            }.onFailure {
                DiagnosticLogger.log(app, "STARTUP_CONTEXT_FAILED", it)
                _lastContextSummary.value =
                    "ContextBundle v1 tryb ograniczony: ${it::class.simpleName}: ${it.message}"
            }
        }
    }

    fun refresh() {
        _status.value = store.status()
        _characterPanel.value = store.fullCharacterPanel(playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))
        _time.value = store.time()
        _chronicle.value = store.chronicle()
        _worldPacks.value = store.packageManager().listWorldPacks()
        _campaigns.value = store.packageManager().listCampaigns()
        _backups.value = store.backups()
        _npcs.value = store.npcs("",playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))
        _relationEdges.value = store.relationEdges(playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))
        _economies.value = store.economies(playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))
        _wars.value = store.wars(playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))
        _sync.value = store.syncCheck()
        _dbTables.value = store.dbTables()
        _visualLibrary.value = store.visualLibrary()
        _relationships.value = store.relationships(playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))
        _organizations.value = store.organizations(playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))
        _politics.value = store.politics(playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))
        _diagnostics.value = store.diagnostics(_lastContextSummary.value)
        _regions.value = store.worldRegions()
        _locations.value = store.worldLocations()
        _worldEvents.value = store.activeWorldEvents(playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))
        _techniques.value = store.techniqueBrowser()
        _missions.value = store.missionBrowser()
        _activeCampaign.value = store.activeCampaignDirName()
        _activeWorldPack.value = store.activeWorldPackDirName()
    }

    fun saveSettings(newSettings: RpgOsSettings) {
        appSettings.save(newSettings)
        _settings.value = newSettings
        _messages.value = _messages.value + ChatMessage("system", "Ustawienia zapisane.")
    }

    fun assignAiRole(role:AiRole,selection:AiModelSelection?){
        val assignment=if(selection==null)AiRoleAssignment(role) else AiRoleAssignment(role,AiAssignmentKind.PINNED,selection)
        val ai=when(role){
            AiRole.GAME_MASTER->_settings.value.ai.copy(gameMaster=assignment)
            AiRole.DIRECTOR_SCENARIST->_settings.value.ai.copy(director=assignment)
        }
        persistAi(ai)
    }

    fun updateAiPrivacy(policy:AiPrivacyPolicy){persistAi(_settings.value.ai.copy(privacy=policy))}

    fun updateLocalAiSettings(settings:LocalModelSettings){
        val admission=providerCenterApplication.localAdmission(settings)
        _aiProviderCenter.value=_aiProviderCenter.value.copy(localSettings=settings,localAdmission=admission)
        if(admission is LocalAdmissionResult.Admitted)persistAi(_settings.value.ai.copy(localModelSettings=settings))
    }

    fun resetLocalAiSettings(){updateLocalAiSettings(LocalRecommendedSettings.forProfile(BielikLocalModelProfiles.BIELIK_4_5B_V3_EXECUTORCH))}

    fun importBielikArtifact(uri:android.net.Uri){
        viewModelScope.launch{
            runCatching{providerCenterApplication.importBielikArtifact(uri,_aiProviderCenter.value.localSettings)}.onSuccess{
                _aiProviderCenter.value=_aiProviderCenter.value.copy(localArtifactInstalled=true)
                updateLocalAiSettings(_aiProviderCenter.value.localSettings)
                _messages.value=_messages.value+ChatMessage("system","Model lokalny został bezpiecznie zaimportowany.")
            }.onFailure{
                DiagnosticLogger.log(getApplication(),"LOCAL_MODEL_IMPORT_FAILED",it)
                _messages.value=_messages.value+ChatMessage("system","Import modelu nie powiódł się: ${it.message}")
            }
        }
    }

    fun beginOpenRouterConnect():String{
        val authorization=providerCenterApplication.beginOpenRouterConnect()
        _aiProviderCenter.value=_aiProviderCenter.value.copy(openRouterStatus=providerCenterApplication.openRouterStatus())
        return authorization.authorizationUrl
    }

    fun disconnectOpenRouter(){
        _aiProviderCenter.value=_aiProviderCenter.value.copy(
            openRouterStatus=providerCenterApplication.disconnectOpenRouter(),modelOptions=_aiProviderCenter.value.modelOptions.filterNot{it.providerKind==AiProviderKind.CLOUD}
        )
        val ai=_settings.value.ai
        persistAi(ai.copy(
            gameMaster=ai.gameMaster.takeUnless{it.pinned?.providerUid=="OPENROUTER"}?:AiRoleAssignment(AiRole.GAME_MASTER),
            director=ai.director.takeUnless{it.pinned?.providerUid=="OPENROUTER"}?:AiRoleAssignment(AiRole.DIRECTOR_SCENARIST)
        ))
    }

    private fun completeOpenRouter(callback:CloudAuthCallback){
        viewModelScope.launch{
            val connection=providerCenterApplication.completeOpenRouter(callback)
            val result=connection.status
            val models=connection.models
            val local=_aiProviderCenter.value.modelOptions.filterNot{it.providerKind==AiProviderKind.CLOUD}
            _aiProviderCenter.value=_aiProviderCenter.value.copy(
                openRouterStatus=result,modelOptions=local+models.sortedBy{it.displayName}.map{
                    AiModelOptionUi(AiModelSelection(it.providerUid,it.modelUid),it.displayName,AiProviderKind.CLOUD,AiAvailabilityState.READY,"OPENROUTER_CONNECTED")
                }
            )
            _messages.value=_messages.value+ChatMessage("system",if(result.state==CloudAuthState.CONNECTED)"OpenRouter połączony." else "Połączenie OpenRouter nie powiodło się.")
        }
    }

    private fun persistAi(ai:AiSystemConfiguration){
        val updated=_settings.value.copy(ai=ai);appSettings.save(updated);_settings.value=updated
        _aiProviderCenter.value=_aiProviderCenter.value.copy(gameMasterAssignment=ai.gameMaster,directorAssignment=ai.director,privacy=ai.privacy,localSettings=ai.localModelSettings?:_aiProviderCenter.value.localSettings)
    }

    fun generateSceneImage(contextApp: android.content.Context, title: String, scenePrompt: String) {
        viewModelScope.launch {
            runCatching {
                _imageStatus.value = "Generowanie obrazu..."
                val chapter = (_chronicle.value.maxOfOrNull { it.chapter } ?: 0) + 1
                val context = store.buildContext(scenePrompt,chapter,playerAudience(),playerPurpose(VisibilityPurposeKinds.SCENE_VISUALIZATION))
                val prompt = VisualPromptBuilder().buildScenePrompt(scenePrompt, context)
                val result = ImageBackendClient(_settings.value.backendUrl).generate(
                    ImageGenerationRequest(
                        "scene", title.ifBlank { "Scena" }, prompt, null, chapter,
                        Phase38VisualAuthorization.authorize(context.visibilityEnvelope,VisibilityPurposeKinds.SCENE_VISUALIZATION,"SCENE",title.ifBlank { "SCENE" },prompt)
                    )
                )
                val uri = GalleryService(contextApp).saveGeneratedImage(result, "scene", null)
                store.addVisual(result.title, "scene", uri.toString(), chapter, null, null, prompt, result.revisedPrompt)
                _npcs.value = store.npcs("",playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))
        _relationEdges.value = store.relationEdges(playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))
        _economies.value = store.economies(playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))
        _wars.value = store.wars(playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))
        _sync.value = store.syncCheck()
        _dbTables.value = store.dbTables()
        _visualLibrary.value = store.visualLibrary()
                _generatedImages.value = listOf(
                    GalleryImageItem(result.title, uri.toString(), System.currentTimeMillis(), "scene", null)
                ) + _generatedImages.value
                _imageStatus.value = "Zapisano w galerii: ${result.title}"
            }.onFailure {
                _imageStatus.value = "Błąd generowania obrazu: ${it.message}"
            }
        }
    }

    fun generateCharacterImage(
        contextApp: android.content.Context,
        name: String,
        traits: String,
        equipment: String,
        notes: String
    ) {
        viewModelScope.launch {
            runCatching {
                _imageStatus.value = "Generowanie postaci..."
                val chapter = (_chronicle.value.maxOfOrNull { it.chapter } ?: 0) + 1
                val visualContext = store.buildContext("CHARACTER_VISUALIZATION:$name",chapter,playerAudience(),playerPurpose(VisibilityPurposeKinds.CHARACTER_VISUALIZATION))
                val prompt = VisualPromptBuilder().buildCharacterPrompt(
                    name,
                    traits.split(",").map { it.trim() }.filter { it.isNotBlank() },
                    equipment.split(",").map { it.trim() }.filter { it.isNotBlank() },
                    notes,
                    visualContext
                )
                val result = ImageBackendClient(_settings.value.backendUrl).generate(
                    ImageGenerationRequest(
                        "character", name.ifBlank { "Postać" }, prompt,
                        authorization = Phase38VisualAuthorization.authorize(visualContext.visibilityEnvelope,VisibilityPurposeKinds.CHARACTER_VISUALIZATION,"CHARACTER",name.ifBlank { "CHARACTER" },prompt)
                    )
                )
                val uri = GalleryService(contextApp).saveGeneratedImage(result, "character", null)
                store.addVisual(result.title, "character", uri.toString(), null, null, null, prompt, result.revisedPrompt)
                _npcs.value = store.npcs("",playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))
        _relationEdges.value = store.relationEdges(playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))
        _economies.value = store.economies(playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))
        _wars.value = store.wars(playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))
        _sync.value = store.syncCheck()
        _dbTables.value = store.dbTables()
        _visualLibrary.value = store.visualLibrary()
                _generatedImages.value = listOf(
                    GalleryImageItem(result.title, uri.toString(), System.currentTimeMillis(), "character", null)
                ) + _generatedImages.value
                _imageStatus.value = "Zapisano postać w galerii."
            }.onFailure { _imageStatus.value = "Błąd: ${it.message}" }
        }
    }

    fun generateLocationImage(
        contextApp: android.content.Context,
        name: String,
        description: String
    ) {
        viewModelScope.launch {
            runCatching {
                _imageStatus.value = "Generowanie scenerii..."
                val era = _time.value.era
                val chapter = (_chronicle.value.maxOfOrNull { it.chapter } ?: 0) + 1
                val visualContext = store.buildContext("LOCATION_VISUALIZATION:$name",chapter,playerAudience(),playerPurpose(VisibilityPurposeKinds.LOCATION_VISUALIZATION))
                val prompt = VisualPromptBuilder().buildLocationPrompt(name, description, era, visualContext)
                val result = ImageBackendClient(_settings.value.backendUrl).generate(
                    ImageGenerationRequest(
                        "location", name.ifBlank { "Lokacja" }, prompt,
                        authorization = Phase38VisualAuthorization.authorize(visualContext.visibilityEnvelope,VisibilityPurposeKinds.LOCATION_VISUALIZATION,"LOCATION",name.ifBlank { "LOCATION" },prompt)
                    )
                )
                val uri = GalleryService(contextApp).saveGeneratedImage(result, "location", null)
                store.addVisual(result.title, "location", uri.toString(), null, null, null, prompt, result.revisedPrompt)
                _npcs.value = store.npcs("",playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))
        _relationEdges.value = store.relationEdges(playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))
        _economies.value = store.economies(playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))
        _wars.value = store.wars(playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))
        _sync.value = store.syncCheck()
        _dbTables.value = store.dbTables()
        _visualLibrary.value = store.visualLibrary()
                _generatedImages.value = listOf(
                    GalleryImageItem(result.title, uri.toString(), System.currentTimeMillis(), "location", null)
                ) + _generatedImages.value
                _imageStatus.value = "Zapisano scenerię w galerii."
            }.onFailure { _imageStatus.value = "Błąd: ${it.message}" }
        }
    }

    fun editVisual(
        contextApp: android.content.Context,
        source: VisualRecord,
        instruction: String
    ) {
        viewModelScope.launch {
            runCatching {
                _imageStatus.value = "Edycja obrazu..."
                val editClient = ImageEditBackendClient(contextApp, _settings.value.backendUrl)
                val prepared = editClient.prepareSource(source.visualUid, source.uri)
                val editEnvelope = VisibilityAuthorityService().envelope(
                    playerAudience(),
                    playerPurpose(VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION)
                )
                val editAuthorization = Phase38VisualAuthorization.authorize(
                    editEnvelope,
                    VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION,
                    "VISUAL",
                    source.visualUid,
                    instruction,
                    VisualInputOrigins.CAMPAIGN_PROJECTION,
                    sourceVisualUid = source.visualUid,
                    sourceImageSha256 = prepared.sha256
                )
                val result = editClient.editPrepared(
                    ImageEditRequest(
                        sourceVisualUid = source.visualUid,
                        sourceUri = source.uri,
                        title = source.title + "_edit",
                        instruction = instruction,
                        authorization = editAuthorization
                    ), prepared
                )
                val uri = GalleryService(contextApp).saveGeneratedImage(
                    result,
                    source.kind,
                    source.relatedEntityUid
                )
                store.addVisual(
                    result.title,
                    source.kind,
                    uri.toString(),
                    source.chapter,
                    source.relatedEntityUid,
                    source.relatedLocationUid,
                    instruction,
                    result.revisedPrompt,
                    source.visualUid
                )
                _npcs.value = store.npcs("",playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))
        _relationEdges.value = store.relationEdges(playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))
        _economies.value = store.economies(playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))
        _wars.value = store.wars(playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))
        _sync.value = store.syncCheck()
        _dbTables.value = store.dbTables()
        _visualLibrary.value = store.visualLibrary()
                _imageStatus.value = "Edycja zapisana w galerii i bibliotece kampanii."
            }.onFailure {
                _imageStatus.value = "Błąd edycji: ${it.message}"
            }
        }
    }

    fun generateSuggestedVisual(
        contextApp: android.content.Context,
        suggestion: VisualSuggestion
    ) {
        when (suggestion.kind) {
            "location" -> generateLocationImage(contextApp, suggestion.title, suggestion.promptSeed)
            "character" -> generateCharacterImage(contextApp, suggestion.title, suggestion.promptSeed, "", "")
            else -> generateSceneImage(contextApp, suggestion.title, suggestion.promptSeed)
        }
    }

    fun searchNpcs(query:String){ _npcs.value=store.npcs(query,playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI)) }
    fun selectNpc(uid:String){ _selectedNpc.value=store.npcDetail(uid,playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI)) }

    fun searchWorld(query: String) {
        _locations.value = store.worldLocations(query)
    }

    fun restoreBackup(path: String) {
        val safety = store.restoreBackup(path)
        refresh()
        _messages.value = _messages.value + ChatMessage(
            "system",
            "Przywrócono backup. Poprzedni stan zabezpieczono jako: $safety"
        )
    }

    fun searchTechniques(query: String) {
        _techniques.value = store.techniqueBrowser(query)
    }

    fun activateCampaign(dirName: String) {
        store.setActiveCampaign(dirName)
        refresh()
        _messages.value = _messages.value + ChatMessage("system", "Aktywna kampania: $dirName")
    }

    fun activateWorldPack(dirName: String) {
        store.setActiveWorldPack(dirName)
        refresh()
        _messages.value = _messages.value + ChatMessage("system", "Aktywny World Pack: $dirName")
    }

    fun createCampaign(name: String) {
        val dir = store.createCampaign(name)
        refresh()
        _messages.value = _messages.value + ChatMessage("system", "Utworzono kampanię: ${dir.name}")
    }

    fun createAndActivateCampaign(name: String) {
        val clean = name.trim().ifBlank { "Nowa kampania" }
        val dir = store.createCampaign(clean)
        store.setActiveCampaign(dir.name)
        refresh()
        _messages.value = _messages.value + ChatMessage(
            "system",
            "Utworzono i aktywowano kampanię: ${dir.name}"
        )
    }


    fun loadDeveloperDiagnostics() {
        _developerDiagnostic.value = diagnosticReport()
        _developerStatus.value = "Raport diagnostyczny odświeżony."
    }

    fun clearDeveloperDiagnostics() {
        clearDiagnosticReport()
        _developerDiagnostic.value = ""
        _developerStatus.value = "Raport diagnostyczny wyczyszczony."
    }

    fun runDeveloperSelfTest() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val results = mutableListOf<String>()

            fun check(name:String, block:()->Unit) {
                try {
                    block()
                    results += "✅ $name"
                } catch (t:Throwable) {
                    results += "❌ $name: ${t::class.simpleName}: ${t.message}"
                    DiagnosticLogger.log(app,"DEV_SELFTEST/$name",t)
                }
            }

            check("Bootstrap / AutoRepair") { store.bootstrap() }
            check("Refresh danych") { refresh() }
            check("ContextBundle") {
                val chapter = (_chronicle.value.maxOfOrNull { it.chapter } ?: 0) + 1
                store.buildContext("DEV_SELF_TEST",chapter,diagnosticAudience(),diagnosticPurpose())
            }
            check("Ustawienia") { appSettings.load() }
            check("Pliki aplikacji") {
                require(app.filesDir.exists()) { "filesDir nie istnieje" }
            }

            _developerStatus.value = results.joinToString("\n")
            _developerDiagnostic.value = diagnosticReport()
        }
    }

    fun testContextBuilder() {
        viewModelScope.launch {
            val app=getApplication<Application>()
            try {
                val chapter=(_chronicle.value.maxOfOrNull{it.chapter}?:0)+1
                val context=store.buildContext("CONTEXT_TEST",chapter,diagnosticAudience(),diagnosticPurpose())
                _developerStatus.value=
                    "✅ ContextBundle OK | wątki=${context.activeThreads.size}, NPC=${context.relevantNpcs.size}, " +
                    "misje=${context.missions.size}, pamięć=${context.retrievedLongTermMemory.size}"
            } catch(t:Throwable) {
                DiagnosticLogger.log(app,"DEV_CONTEXT_TEST",t)
                _developerStatus.value="❌ ContextBundle: ${t::class.simpleName}: ${t.message}"
            }
        }
    }

    fun testBackendConnection() {
        viewModelScope.launch {
            val app=getApplication<Application>()
            try {
                val chapter=(_chronicle.value.maxOfOrNull{it.chapter}?:0)+1
                val context=store.buildContext("BACKEND_TEST",chapter,playerAudience(),playerPurpose(VisibilityPurposeKinds.GAMEPLAY_NARRATION))
                val result=BackendClient(_settings.value.backendUrl)
                    .sendTurn("Odpowiedz wyłącznie: RPG_OS_BACKEND_OK",chapter,context)
                _developerStatus.value="✅ Backend odpowiedział: ${result.narration.take(120)}"
            } catch(t:Throwable) {
                DiagnosticLogger.log(app,"DEV_BACKEND_TEST",t)
                _developerStatus.value="❌ Backend: ${t::class.simpleName}: ${t.message}"
            }
        }
    }

    fun createDeveloperBackup() {
        viewModelScope.launch {
            val app=getApplication<Application>()
            try {
                val chapter=(_chronicle.value.maxOfOrNull{it.chapter}?:0)
                val result=store.finalizeChapter(chapter,"Developer diagnostic backup")
                _developerStatus.value="✅ Backup utworzony. Manifest=${result.first.take(12)}…"
                refresh()
            } catch(t:Throwable) {
                DiagnosticLogger.log(app,"DEV_BACKUP",t)
                _developerStatus.value="❌ Backup: ${t::class.simpleName}: ${t.message}"
            }
        }
    }

    fun send(text: String) {
        if (text.isBlank()) return
        if(activeAiCancellation!=null)return
        val cancellation=MutableAiCancellationSignal().also{activeAiCancellation=it}
        val requestUid="CHAT:${System.currentTimeMillis()}"
        _chatTurnUi.value=ChatTurnUiState(ChatTurnUiStage.INTERPRETING,requestUid,"Rozumiem Twoją decyzję…",canCancel=true)
        _messages.value = _messages.value + ChatMessage("player", text)

        viewModelScope.launch {
            val app = getApplication<Application>()
            try {
                DiagnosticLogger.log(app, "SEND_START", message = "request=$requestUid")
                if(repository.activePlayerRef()==null){
                    _chatTurnUi.value=_chatTurnUi.value.copy(stage=ChatTurnUiStage.GENERATING_PROPOSAL,statusText="Mistrz Gry pomaga stworzyć postać…")
                    when(val creation=withContext(Dispatchers.IO){characterCreationApplication.play(text,cancellation)}){
                        is CharacterCreationApplicationOutcome.Question->{
                            _messages.value+=ChatMessage("gm",creation.text)
                            _chatTurnUi.value=ChatTurnUiState(ChatTurnUiStage.CLARIFICATION,requestUid,"Tworzenie postaci — czekam na Twój wybór")
                        }
                        is CharacterCreationApplicationOutcome.AwaitingExplicitConfirmation->{
                            pendingCharacterCreationUid=creation.creationUid
                            _messages.value+=ChatMessage("gm",creation.summary)
                            _messages.value+=ChatMessage("system","Sprawdź podsumowanie. Postać nie została jeszcze zapisana — użyj przycisku „Potwierdź postać”.")
                            _chatTurnUi.value=ChatTurnUiState(ChatTurnUiStage.CLARIFICATION,requestUid,"Projekt postaci czeka na Twoje potwierdzenie",canConfirmCharacterCreation=true)
                        }
                        is CharacterCreationApplicationOutcome.Created->Unit
                        is CharacterCreationApplicationOutcome.Failed->_chatTurnUi.value=ChatTurnUiState(ChatTurnUiStage.FAILED,requestUid,"Nie udało się przygotować postaci.",reasonUid=creation.reasonUid)
                        is CharacterCreationApplicationOutcome.Cancelled->_chatTurnUi.value=ChatTurnUiState(ChatTurnUiStage.CANCELLED,requestUid,"Tworzenie postaci anulowane.",reasonUid=creation.reasonUid)
                    }
                    return@launch
                }
                _chatTurnUi.value=_chatTurnUi.value.copy(stage=ChatTurnUiStage.BUILDING_CONTEXT,statusText="Buduję bezpieczny kontekst…")
                _chatTurnUi.value=_chatTurnUi.value.copy(stage=ChatTurnUiStage.GENERATING_PROPOSAL,statusText="Mistrz Gry przygotowuje propozycję…")
                val applicationOutcome=withContext(Dispatchers.IO){chatApplication.play(text,cancellation)}
                when(val outcome=applicationOutcome){
                    is ChatApplicationOutcome.Narrated->{
                        _chatTurnUi.value=_chatTurnUi.value.copy(stage=ChatTurnUiStage.NARRATING,statusText="Odbieram zatwierdzoną narrację…",canCancel=false)
                        _messages.value+=ChatMessage("gm",outcome.result.narrative.text)
                        _chatTurnUi.value=ChatTurnUiState(ChatTurnUiStage.COMPLETED,requestUid,"Tura zapisana i zakończona",committedOrder=outcome.result.receipt.commitOrder)
                        runCatching{refresh()}.onFailure{DiagnosticLogger.log(app,"REFRESH_GUARDED",it)}
                    }
                    is ChatApplicationOutcome.CommittedNarrationPending->{
                        pendingNarrationRecovery=outcome.recovery
                        _chatTurnUi.value=ChatTurnUiState(ChatTurnUiStage.COMMITTED_NARRATION_PENDING,requestUid,"Tura jest zapisana. Narrację można bezpiecznie ponowić.",canRetryNarration=true,committedOrder=outcome.result.receipt.commitOrder,reasonUid=outcome.result.reasonUid)
                    }
                    is ChatApplicationOutcome.Clarification->{
                        _chatTurnUi.value=ChatTurnUiState(ChatTurnUiStage.CLARIFICATION,requestUid,"Potrzebuję doprecyzowania decyzji.",reasonUid=outcome.reasonUids.joinToString("|"))
                        _messages.value+=ChatMessage("system","Doprecyzuj proszę, co dokładnie chcesz zrobić.")
                    }
                    is ChatApplicationOutcome.Rejected->{
                        _chatTurnUi.value=ChatTurnUiState(ChatTurnUiStage.FAILED,requestUid,"Ta decyzja wymaga bezpiecznego rozstrzygnięcia.",reasonUid=outcome.reasonUids.joinToString("|"))
                    }
                    is ChatApplicationOutcome.Failed->{
                        _chatTurnUi.value=ChatTurnUiState(ChatTurnUiStage.FAILED,requestUid,"Nie udało się ukończyć tury.",reasonUid=outcome.reasonUid)
                    }
                    is ChatApplicationOutcome.Cancelled->{
                        _chatTurnUi.value=ChatTurnUiState(ChatTurnUiStage.CANCELLED,requestUid,if(outcome.mutationState==TurnMutationState.COMMITTED)"Tura została zapisana; narracja oczekuje na odzyskanie." else "Tura anulowana przed zapisem.",reasonUid="CANCELLED:${outcome.stage}")
                    }
                    is ChatApplicationOutcome.NonAuthoritativeNarration->{
                        _chatTurnUi.value=_chatTurnUi.value.copy(stage=ChatTurnUiStage.NARRATING,statusText="Tryb zgodności — bez zmiany stanu gry",canCancel=false)
                        _messages.value+=ChatMessage("gm",outcome.text)
                        _messages.value+=ChatMessage("system","Ta odpowiedź pochodzi ze starego trybu narracyjnego; żadna zmiana stanu nie została przyjęta. (${outcome.reasonUid})")
                        _chatTurnUi.value=ChatTurnUiState(ChatTurnUiStage.COMPLETED,requestUid,"Narracja zakończona bez zmiany stanu",reasonUid=outcome.reasonUid)
                    }
                }

                DiagnosticLogger.log(app, "SEND_COMPLETE")
            } catch (t: Throwable) {
                DiagnosticLogger.log(app, "SEND_OUTER_GUARD", t)
                _messages.value = _messages.value + ChatMessage(
                    "system",
                    "Anti-Crash przechwycił błąd: ${t::class.simpleName}: ${t.message ?: "brak szczegółów"}"
                )
                _lastContextSummary.value =
                    "Anti-Crash: ${t::class.simpleName}: ${t.message ?: "brak szczegółów"}"
                _chatTurnUi.value=ChatTurnUiState(ChatTurnUiStage.FAILED,requestUid,"Tura nie została ukończona.",reasonUid=t.message?:t::class.simpleName)
            }finally{
                activeAiCancellation=null
            }
        }
    }

    fun cancelCurrentAiTurn(){activeAiCancellation?.cancel()}

    fun confirmCharacterCreation(){
        val creationUid=pendingCharacterCreationUid?:return
        if(activeAiCancellation!=null)return
        viewModelScope.launch{
            val actionUid="CHARACTER-CONFIRM:${System.currentTimeMillis()}"
            _chatTurnUi.value=ChatTurnUiState(ChatTurnUiStage.COMMITTING,actionUid,"Zapisuję potwierdzoną postać…")
            when(val outcome=withContext(Dispatchers.IO){characterCreationApplication.confirm(creationUid,actionUid)}){
                is CharacterCreationApplicationOutcome.Created->{
                    pendingCharacterCreationUid=null
                    _messages.value+=ChatMessage("system","Postać ${outcome.receipt.playerUid} została utworzona i jest gotowa do gry.")
                    _chatTurnUi.value=ChatTurnUiState(ChatTurnUiStage.COMPLETED,actionUid,"Postać utworzona")
                    refresh()
                }
                is CharacterCreationApplicationOutcome.Failed->_chatTurnUi.value=ChatTurnUiState(ChatTurnUiStage.FAILED,actionUid,"Nie udało się zapisać postaci.",canConfirmCharacterCreation=true,reasonUid=outcome.reasonUid)
                else->_chatTurnUi.value=ChatTurnUiState(ChatTurnUiStage.FAILED,actionUid,"Nieprawidłowy stan tworzenia postaci.",canConfirmCharacterCreation=true)
            }
        }
    }

    fun retryCommittedNarration(){
        val token=pendingNarrationRecovery?:run{
            _messages.value+=ChatMessage("system","Brak zapisanej tury oczekującej na narrację.")
            return
        }
        if(activeAiCancellation!=null)return
        val cancellation=MutableAiCancellationSignal().also{activeAiCancellation=it}
        viewModelScope.launch{
            try{
                _chatTurnUi.value=_chatTurnUi.value.copy(stage=ChatTurnUiStage.NARRATING,statusText="Odzyskuję narrację wyłącznie z zapisanego rezultatu…",canCancel=false)
                val recoveryOutcome=withContext(Dispatchers.IO){chatApplication.recover(token,cancellation)}
                when(val recovered=recoveryOutcome){
                    is NarrativeRecoveryResult.Recovered->{
                        _messages.value+=ChatMessage("gm",recovered.delivery.narrative.text)
                        pendingNarrationRecovery=null
                        _chatTurnUi.value=ChatTurnUiState(ChatTurnUiStage.COMPLETED,token.request.requestUid,"Narracja odzyskana",committedOrder=recovered.delivery.identity.committedOrder)
                    }
                    is NarrativeRecoveryResult.Unavailable->_chatTurnUi.value=ChatTurnUiState(ChatTurnUiStage.COMMITTED_NARRATION_PENDING,token.request.requestUid,"Zapis jest bezpieczny, ale narracja nadal niedostępna.",canRetryNarration=true,reasonUid=recovered.reasonUid)
                }
            }finally{activeAiCancellation=null}
        }
    }

    fun diagnosticReport(): String =
        DiagnosticLogger.read(getApplication<Application>())

    fun clearDiagnosticReport() {
        DiagnosticLogger.clear(getApplication<Application>())
    }
}
