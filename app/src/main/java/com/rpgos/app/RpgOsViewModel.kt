package com.rpgos.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CampaignCreationUiState(
    val inProgress:Boolean=false,
    val completedCampaignDir:String?=null,
    val requiresCharacterCreation:Boolean=false,
    val errorMessage:String?=null
)

data class CampaignManagementUiState(
    val inProgressCampaignDir:String?=null,
    val activatedCampaignDir:String?=null,
    val notice:String?=null,
    val errorMessage:String?=null
)

data class PackageTransferUiState(
    val inProgress:Boolean=false,
    val notice:String?=null,
    val errorMessage:String?=null
)

data class SaveRecoveryUiState(
    val inProgress:Boolean=false,
    val notice:String?=null,
    val errorMessage:String?=null
)

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
        listOf(ChatMessage("system", "RPG OS ${BuildConfig.VERSION_NAME} • pełny Core Phase 1–54 • trwała mechanika i bezpieczna narracja."))
    )
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _status = MutableStateFlow(StatusSnapshot())
    val status: StateFlow<StatusSnapshot> = _status

    private val _characterPanel = MutableStateFlow(
        CharacterPanelSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    )
    val characterPanel: StateFlow<CharacterPanelSnapshot> = _characterPanel

    private val _characterPanelV2=MutableStateFlow<CharacterPanelSnapshotV2?>(null)
    val characterPanelV2:StateFlow<CharacterPanelSnapshotV2?> = _characterPanelV2

    private val _hasActivePlayer=MutableStateFlow(false)
    val hasActivePlayer:StateFlow<Boolean> = _hasActivePlayer

    private val _time = MutableStateFlow(TimeSnapshot())
    val time: StateFlow<TimeSnapshot> = _time

    private val _chronicle = MutableStateFlow<List<ChronicleEntry>>(emptyList())
    val chronicle: StateFlow<List<ChronicleEntry>> = _chronicle

    private val _worldPacks = MutableStateFlow<List<WorldPackInfo>>(emptyList())
    val worldPacks: StateFlow<List<WorldPackInfo>> = _worldPacks

    private val _campaigns = MutableStateFlow<List<CampaignInfo>>(emptyList())
    val campaigns: StateFlow<List<CampaignInfo>> = _campaigns

    private val _campaignCreationUi = MutableStateFlow(CampaignCreationUiState())
    val campaignCreationUi:StateFlow<CampaignCreationUiState> = _campaignCreationUi

    private val _campaignManagementUi = MutableStateFlow(CampaignManagementUiState())
    val campaignManagementUi:StateFlow<CampaignManagementUiState> = _campaignManagementUi

    private val _backups = MutableStateFlow<List<String>>(emptyList())
    val backups: StateFlow<List<String>> = _backups

    private val _snapshots = MutableStateFlow<List<CampaignSnapshotDescriptor>>(emptyList())
    val snapshots:StateFlow<List<CampaignSnapshotDescriptor>> = _snapshots

    private val _packageTransferUi=MutableStateFlow(PackageTransferUiState())
    val packageTransferUi:StateFlow<PackageTransferUiState> = _packageTransferUi

    private val _saveRecoveryUi=MutableStateFlow(SaveRecoveryUiState())
    val saveRecoveryUi:StateFlow<SaveRecoveryUiState> = _saveRecoveryUi

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
        providerCenterApplication.onOpenRouterCallback{connection->viewModelScope.launch{applyOpenRouterConnection(connection)}}
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
        _hasActivePlayer.value = store.activePlayerRef()!=null
        _status.value = store.status()
        _characterPanel.value = store.fullCharacterPanel(playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))
        _characterPanelV2.value = runCatching{
            store.fullCharacterPanelV2(playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))
        }.onFailure{DiagnosticLogger.log(getApplication<Application>(),"CHARACTER_PANEL_V2_REFRESH_FAILED",it)}.getOrNull()
        _time.value = store.time()
        _chronicle.value = store.chronicle()
        _worldPacks.value = store.packageManager().listWorldPacks()
        _campaigns.value = store.packageManager().listCampaigns()
        _backups.value = store.backups()
        _snapshots.value = runCatching{store.snapshots()}.getOrDefault(emptyList())
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
        _aiProviderCenter.value=_aiProviderCenter.value.copy(localSettings=settings)
            .reconcileLocalAvailability(admission=admission)
        if(admission is LocalAdmissionResult.Admitted)persistAi(_settings.value.ai.copy(localModelSettings=settings))
    }

    fun resetLocalAiSettings(){updateLocalAiSettings(LocalRecommendedSettings.forProfile(_aiProviderCenter.value.localProfile))}

    fun importBielikArtifact(uri:android.net.Uri){
        viewModelScope.launch{
            runCatching{providerCenterApplication.importBielikArtifact(uri,_aiProviderCenter.value.localSettings)}.onSuccess{
                _aiProviderCenter.value=_aiProviderCenter.value.reconcileLocalAvailability(artifactInstalled=true)
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

    fun connectOpenRouterWithApiKey(rawApiKey:String){
        val apiKey=rawApiKey.trim().toCharArray()
        _aiProviderCenter.value=_aiProviderCenter.value.copy(
            openRouterStatus=CloudConnectionStatus("OPENROUTER",CloudAuthState.CONNECTING,reasonUid="VALIDATING_MANUAL_API_KEY")
        )
        viewModelScope.launch{applyOpenRouterConnection(providerCenterApplication.connectOpenRouterWithApiKey(apiKey))}
    }

    private fun applyOpenRouterConnection(connection:OpenRouterConnectionResult){
        val result=connection.status
        val local=_aiProviderCenter.value.modelOptions.filterNot{it.providerKind==AiProviderKind.CLOUD}
        _aiProviderCenter.value=_aiProviderCenter.value.copy(
            openRouterStatus=result,modelOptions=local+connection.models.sortedBy{it.displayName}.map{
                AiModelOptionUi(AiModelSelection(it.providerUid,it.modelUid),it.displayName,AiProviderKind.CLOUD,AiAvailabilityState.READY,"OPENROUTER_CONNECTED")
            }
        )
        if(result.state==CloudAuthState.ERROR)DiagnosticLogger.log(getApplication(),"OPENROUTER_CONNECT_FAILED",message=result.reasonUid)
        _messages.value=_messages.value+ChatMessage(
            "system",if(result.state==CloudAuthState.CONNECTED)"OpenRouter połączony." else "Połączenie OpenRouter nie powiodło się: ${openRouterFailureMessagePl(result.reasonUid)}${result.reasonUid?.let{" (kod: $it)"}.orEmpty()}"
        )
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
        if(_saveRecoveryUi.value.inProgress)return
        _saveRecoveryUi.value=SaveRecoveryUiState(inProgress=true)
        viewModelScope.launch{
            try{
                val safety=withContext(Dispatchers.IO){store.restoreBackup(path).also{refresh()}}
                resetConversationForActiveCampaign(store.activeCampaignDirName())
                _messages.value = _messages.value + ChatMessage("system","Przywrócono backup. Poprzedni stan zabezpieczono jako: $safety")
                _saveRecoveryUi.value=SaveRecoveryUiState(notice="Backup przywrócony. Poprzedni stan został zabezpieczony.")
            }catch(t:Throwable){
                DiagnosticLogger.log(getApplication(),"BACKUP_RESTORE_FAILED",t)
                _saveRecoveryUi.value=SaveRecoveryUiState(errorMessage=t.message?:"Nie udało się przywrócić backupu.")
            }
        }
    }

    fun createManualSnapshot(){
        if(_saveRecoveryUi.value.inProgress)return
        _saveRecoveryUi.value=SaveRecoveryUiState(inProgress=true)
        viewModelScope.launch{
            try{
                val snapshot=withContext(Dispatchers.IO){store.createSnapshot(SnapshotKind.USER_PINNED,true).also{refresh()}}
                _saveRecoveryUi.value=SaveRecoveryUiState(notice="Utworzono przypięty snapshot ${snapshot.snapshotUid.takeLast(8)}.")
            }catch(t:Throwable){
                DiagnosticLogger.log(getApplication(),"SNAPSHOT_CREATE_FAILED",t)
                _saveRecoveryUi.value=SaveRecoveryUiState(errorMessage=t.message?:"Nie udało się utworzyć snapshotu.")
            }
        }
    }

    fun restoreSnapshot(snapshotUid:String){
        if(_saveRecoveryUi.value.inProgress)return
        _saveRecoveryUi.value=SaveRecoveryUiState(inProgress=true)
        viewModelScope.launch{
            try{
                withContext(Dispatchers.IO){store.restoreSnapshot(snapshotUid);refresh()}
                resetConversationForActiveCampaign(store.activeCampaignDirName())
                _saveRecoveryUi.value=SaveRecoveryUiState(notice="Snapshot został zweryfikowany i przywrócony.")
            }catch(t:Throwable){
                DiagnosticLogger.log(getApplication(),"SNAPSHOT_RESTORE_FAILED",t)
                _saveRecoveryUi.value=SaveRecoveryUiState(errorMessage=t.message?:"Nie udało się przywrócić snapshotu.")
            }
        }
    }

    fun clearSaveRecoveryMessage(){_saveRecoveryUi.value=SaveRecoveryUiState()}

    fun importCampaign(uri:android.net.Uri){
        transferPackage("CAMPAIGN_IMPORT_FAILED"){
            val app=getApplication<Application>();val manager=RpgPackageManager(app)
            val source=copyUriToCache(uri,"campaign-import")
            try{
                val base=safePackageBaseName(displayName(uri),"Importowana_kampania")
                val existing=manager.listCampaigns().map{java.io.File(it.path).name}.toSet()
                val target=uniquePackageName(base,".campaign",existing)
                val result=manager.validatedImportCampaign(source,target)
                require(result.ok){result.message}
                store.setActiveCampaign(target);refresh();resetConversationForActiveCampaign(target)
                "Zaimportowano i aktywowano kampanię ${target.removeSuffix(".campaign")}."
            }finally{source.delete()}
        }
    }

    fun importWorldPack(uri:android.net.Uri){
        transferPackage("WORLD_PACK_IMPORT_FAILED"){
            val app=getApplication<Application>();val manager=RpgPackageManager(app)
            val source=copyUriToCache(uri,"worldpack-import")
            try{
                val base=safePackageBaseName(displayName(uri),"Importowany_swiat")
                val existing=manager.listWorldPacks().map{java.io.File(it.path).name}.toSet()
                val target=uniquePackageName(base,".worldpack",existing)
                val result=manager.validatedImportWorldPack(source,target)
                require(result.ok){result.message}
                store.setActiveWorldPack(target);refresh()
                "Zaimportowano i aktywowano World Pack ${target.removeSuffix(".worldpack")}."
            }finally{source.delete()}
        }
    }

    fun exportActiveCampaign(uri:android.net.Uri){
        transferPackage("CAMPAIGN_EXPORT_FAILED"){
            val app=getApplication<Application>();val temp=java.io.File(app.cacheDir,"campaign-export-${java.util.UUID.randomUUID()}.zip")
            try{
                store.packageManager().exportCampaign(store.activeCampaignDirName(),temp)
                app.contentResolver.openOutputStream(uri,"w")?.use{out->temp.inputStream().use{it.copyTo(out)}}
                    ?:error("Nie można otworzyć pliku docelowego.")
                "Wyeksportowano aktywną kampanię."
            }finally{temp.delete()}
        }
    }

    fun clearPackageTransferMessage(){_packageTransferUi.value=PackageTransferUiState()}

    private fun transferPackage(logUid:String,operation:()->String){
        if(_packageTransferUi.value.inProgress)return
        _packageTransferUi.value=PackageTransferUiState(inProgress=true)
        viewModelScope.launch{
            try{
                val notice=withContext(Dispatchers.IO){operation()}
                _packageTransferUi.value=PackageTransferUiState(notice=notice)
            }catch(t:Throwable){
                DiagnosticLogger.log(getApplication(),logUid,t)
                _packageTransferUi.value=PackageTransferUiState(errorMessage=t.message?:"Operacja pakietu nie powiodła się.")
            }
        }
    }

    private fun copyUriToCache(uri:android.net.Uri,prefix:String):java.io.File{
        val app=getApplication<Application>();val out=java.io.File(app.cacheDir,"$prefix-${java.util.UUID.randomUUID()}.zip")
        app.contentResolver.openInputStream(uri)?.use{input->out.outputStream().use{input.copyTo(it)}}
            ?:error("Nie można odczytać wybranego pliku.")
        return out
    }

    private fun displayName(uri:android.net.Uri):String?{
        val app=getApplication<Application>()
        return runCatching{app.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{c->
            if(c.moveToFirst())c.getString(0) else null
        }}.getOrNull()
    }

    private fun safePackageBaseName(raw:String?,fallback:String):String{
        val stripped=raw.orEmpty().substringBeforeLast('.').trim().replace(Regex("[^A-Za-z0-9_-]"),"_").trim('_')
        return stripped.ifBlank{fallback}
    }

    private fun uniquePackageName(base:String,suffix:String,existing:Set<String>):String{
        var candidate="$base$suffix";var counter=2
        while(candidate in existing){candidate="$base-$counter$suffix";counter++}
        return candidate
    }

    fun searchTechniques(query: String) {
        _techniques.value = store.techniqueBrowser(query)
    }

    fun activateCampaign(dirName: String) {
        if(_campaignManagementUi.value.inProgressCampaignDir!=null)return
        val previousCampaign=store.activeCampaignDirName()
        _campaignManagementUi.value=CampaignManagementUiState(inProgressCampaignDir=dirName)
        viewModelScope.launch{
            try{
                withContext(Dispatchers.IO){store.setActiveCampaign(dirName)}
                refresh()
                resetConversationForActiveCampaign(dirName)
                _campaignManagementUi.value=CampaignManagementUiState(
                    activatedCampaignDir=dirName,
                    notice="Kampania jest gotowa do kontynuowania."
                )
            }catch(t:Throwable){
                val app=getApplication<Application>()
                DiagnosticLogger.log(app,"CAMPAIGN_ACTIVATION_FAILED",t)
                if(store.activeCampaignDirName()!=previousCampaign){
                    runCatching{
                        withContext(Dispatchers.IO){store.setActiveCampaign(previousCampaign)}
                        refresh()
                    }.onFailure{DiagnosticLogger.log(app,"CAMPAIGN_ACTIVATION_UI_ROLLBACK_FAILED",it)}
                }
                _campaignManagementUi.value=CampaignManagementUiState(
                    errorMessage="Nie udało się otworzyć kampanii. Zapis pozostał bezpieczny. ${t.message?.takeIf{it.isNotBlank()}?:"Sprawdź diagnostykę aplikacji."}"
                )
            }
        }
    }

    fun activateWorldPack(dirName: String) {
        store.setActiveWorldPack(dirName)
        refresh()
        _messages.value = _messages.value + ChatMessage("system", "Aktywny World Pack: $dirName")
    }

    fun createCampaign(name: String) {
        val dir = store.createCampaign(name)
        refresh()
        resetConversationForActiveCampaign(dir.name)
    }

    fun createAndActivateCampaign(name: String) {
        if(_campaignCreationUi.value.inProgress)return
        val clean = name.trim().ifBlank { "Nowa kampania" }
        _campaignCreationUi.value=CampaignCreationUiState(inProgress=true)
        viewModelScope.launch {
            try {
                val dir=withContext(Dispatchers.IO){
                    val created=store.createCampaign(clean)
                    store.setActiveCampaign(created.name)
                    refresh()
                    created
                }
                resetConversationForActiveCampaign(dir.name)
                _campaignCreationUi.value=CampaignCreationUiState(
                    completedCampaignDir=dir.name,
                    requiresCharacterCreation=!_hasActivePlayer.value
                )
            }catch(t:Throwable){
                val app=getApplication<Application>()
                DiagnosticLogger.log(app,"CAMPAIGN_CREATE_FAILED",t)
                _campaignCreationUi.value=CampaignCreationUiState(
                    errorMessage=t.message?.takeIf{it.isNotBlank()}?:"Nie udało się utworzyć kampanii."
                )
            }
        }
    }

    fun consumeCampaignCreationCompletion(){
        if(_campaignCreationUi.value.completedCampaignDir!=null)_campaignCreationUi.value=CampaignCreationUiState()
    }

    private fun resetConversationForActiveCampaign(label:String){
        pendingCharacterCreationUid=null
        pendingNarrationRecovery=runCatching{chatApplication.pendingRecovery()}
            .onFailure{DiagnosticLogger.log(getApplication<Application>(),"NARRATIVE_RECOVERY_DISCOVERY_FAILED",it)}.getOrNull()
        _messages.value=buildList{
            add(ChatMessage("system","RPG OS ${BuildConfig.VERSION_NAME} • aktywna kampania: $label"))
            if(!_hasActivePlayer.value)add(ChatMessage("gm","Zanim rozpoczniemy przygodę, wspólnie stworzymy Twoją postać. Opowiedz mi, kim chcesz grać."))
        }
        _chatTurnUi.value=pendingNarrationRecovery?.let{token->ChatTurnUiState(
            ChatTurnUiStage.COMMITTED_NARRATION_PENDING,token.request.requestUid,
            "Ostatnia tura jest zapisana. Narrację można bezpiecznie odzyskać.",canRetryNarration=true
        )}?:ChatTurnUiState()
    }

    fun moveCampaignToTrash(dirName:String){
        if(_campaignManagementUi.value.inProgressCampaignDir!=null)return
        _campaignManagementUi.value=CampaignManagementUiState(inProgressCampaignDir=dirName)
        viewModelScope.launch{
            try{
                val removed=withContext(Dispatchers.IO){
                    val destination=store.moveCampaignToTrash(dirName)
                    refresh()
                    destination
                }
                _campaignManagementUi.value=CampaignManagementUiState(
                    notice="Kampanię przeniesiono do bezpiecznego kosza: ${removed.nameWithoutExtension}."
                )
            }catch(t:Throwable){
                val app=getApplication<Application>()
                DiagnosticLogger.log(app,"CAMPAIGN_TRASH_FAILED",t)
                _campaignManagementUi.value=CampaignManagementUiState(
                    errorMessage=t.message?.takeIf{it.isNotBlank()}?:"Nie udało się usunąć kampanii."
                )
            }
        }
    }

    fun clearCampaignManagementMessage(){_campaignManagementUi.value=CampaignManagementUiState()}


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
