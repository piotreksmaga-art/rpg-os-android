package com.rpgos.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class RpgOsViewModel(app: Application) : AndroidViewModel(app) {
    private val store = LocalGameStore(app)
    private val appSettings = AppSettings(app)

    private val _settings = MutableStateFlow(appSettings.load())
    val settings: StateFlow<RpgOsSettings> = _settings

    private val _developerStatus = MutableStateFlow("Nie uruchomiono testów.")
    val developerStatus: StateFlow<String> = _developerStatus

    private val _developerDiagnostic = MutableStateFlow("")
    val developerDiagnostic: StateFlow<String> = _developerDiagnostic

    private val _messages = MutableStateFlow(
        listOf(ChatMessage("system", "RPG OS ALPHA 1.2.0-alpha4 • UI Refresh • GitHub Updater • ContextBundle Engine v1."))
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
        store.bootstrap()
        refresh()
        buildStartupContext()
    }

    private fun buildStartupContext() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            runCatching {
                val chapter = (_chronicle.value.maxOfOrNull { it.chapter } ?: 0) + 1
                val context = store.buildContext("STARTUP_CONTEXT", chapter)
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
        _characterPanel.value = store.fullCharacterPanel()
        _time.value = store.time()
        _chronicle.value = store.chronicle()
        _worldPacks.value = store.packageManager().listWorldPacks()
        _campaigns.value = store.packageManager().listCampaigns()
        _backups.value = store.backups()
        _npcs.value = store.npcs()
        _relationEdges.value = store.relationEdges()
        _economies.value = store.economies()
        _wars.value = store.wars()
        _sync.value = store.syncCheck()
        _dbTables.value = store.dbTables()
        _visualLibrary.value = store.visualLibrary()
        _relationships.value = store.relationships()
        _organizations.value = store.organizations()
        _politics.value = store.politics()
        _diagnostics.value = store.diagnostics(_lastContextSummary.value)
        _regions.value = store.worldRegions()
        _locations.value = store.worldLocations()
        _worldEvents.value = store.activeWorldEvents()
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

    fun generateSceneImage(contextApp: android.content.Context, title: String, scenePrompt: String) {
        viewModelScope.launch {
            runCatching {
                _imageStatus.value = "Generowanie obrazu..."
                val chapter = (_chronicle.value.maxOfOrNull { it.chapter } ?: 0) + 1
                val context = store.buildContext(scenePrompt, chapter)
                val prompt = VisualPromptBuilder().buildScenePrompt(scenePrompt, context)
                val result = ImageBackendClient(_settings.value.backendUrl).generate(
                    ImageGenerationRequest("scene", title.ifBlank { "Scena" }, prompt, null, chapter)
                )
                val uri = GalleryService(contextApp).saveGeneratedImage(result, "scene", null)
                store.addVisual(result.title, "scene", uri.toString(), chapter, null, null, prompt, result.revisedPrompt)
                _npcs.value = store.npcs()
        _relationEdges.value = store.relationEdges()
        _economies.value = store.economies()
        _wars.value = store.wars()
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
                val prompt = VisualPromptBuilder().buildCharacterPrompt(
                    name,
                    traits.split(",").map { it.trim() }.filter { it.isNotBlank() },
                    equipment.split(",").map { it.trim() }.filter { it.isNotBlank() },
                    notes
                )
                val result = ImageBackendClient(_settings.value.backendUrl).generate(
                    ImageGenerationRequest("character", name.ifBlank { "Postać" }, prompt)
                )
                val uri = GalleryService(contextApp).saveGeneratedImage(result, "character", null)
                store.addVisual(result.title, "character", uri.toString(), null, null, null, prompt, result.revisedPrompt)
                _npcs.value = store.npcs()
        _relationEdges.value = store.relationEdges()
        _economies.value = store.economies()
        _wars.value = store.wars()
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
                val prompt = VisualPromptBuilder().buildLocationPrompt(name, description, era)
                val result = ImageBackendClient(_settings.value.backendUrl).generate(
                    ImageGenerationRequest("location", name.ifBlank { "Lokacja" }, prompt)
                )
                val uri = GalleryService(contextApp).saveGeneratedImage(result, "location", null)
                store.addVisual(result.title, "location", uri.toString(), null, null, null, prompt, result.revisedPrompt)
                _npcs.value = store.npcs()
        _relationEdges.value = store.relationEdges()
        _economies.value = store.economies()
        _wars.value = store.wars()
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
                val result = ImageEditBackendClient(contextApp, _settings.value.backendUrl).edit(
                    ImageEditRequest(
                        sourceVisualUid = source.visualUid,
                        sourceUri = source.uri,
                        title = source.title + "_edit",
                        instruction = instruction
                    )
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
                _npcs.value = store.npcs()
        _relationEdges.value = store.relationEdges()
        _economies.value = store.economies()
        _wars.value = store.wars()
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

    fun searchNpcs(query:String){ _npcs.value=store.npcs(query) }
    fun selectNpc(uid:String){ _selectedNpc.value=store.npcDetail(uid) }

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

    fun loadGm141OfflineDiagnostics() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            try {
                _developerStatus.value = "GM141: audyt offline..."
                _developerDiagnostic.value = GameMasterDiagnosticsService141(app, store).report()
                _developerStatus.value = "✅ GM141: raport offline gotowy. Bez AI i bez zapisu tury."
            } catch (t: Throwable) {
                DiagnosticLogger.log(app, "GM141_OFFLINE_DIAGNOSTICS_FAILED", t)
                _developerStatus.value = "❌ GM141 diagnostyka: ${t::class.simpleName}: ${t.message}"
            }
        }
    }

    fun testGm141ProposalEndpoint() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            try {
                _developerStatus.value = "GM141: test /v1/gm/proposal bez zapisu..."
                GameMasterRepositoryFactory(app, store).openActiveSession().use { session ->
                    val chapter = (_chronicle.value.maxOfOrNull { it.chapter } ?: 0) + 1L
                    val request = GameMasterTurnRequest(
                        campaignId = session.campaignUid.value,
                        worldPackId = session.worldPackUid.value,
                        playerAction = "DIAGNOSTIC_PROPOSAL_ONLY: zwróć minimalną bezpieczną propozycję testową bez zmian stanu.",
                        currentChapter = chapter,
                        locale = "pl-PL"
                    )
                    val context = GameMasterContextRepository141(app, store).buildContext(request)
                    val proposal = GameMasterBackendGateway141(_settings.value.backendUrl)
                        .generateProposal(request, context)
                    require(proposal.narrativeDraft.isNotBlank()) { "Backend zwrócił pustą narrację." }
                    _developerStatus.value =
                        "✅ GM141 proposal OK | akcje=${proposal.proposedActions.size}, pamięci=${proposal.proposedMemories.size}, kronika=${proposal.proposedChronicleEntries.size}. Nic nie zapisano."
                }
            } catch (t: Throwable) {
                DiagnosticLogger.log(app, "GM141_PROPOSAL_TEST_FAILED", t)
                _developerStatus.value = "❌ GM141 proposal: ${t::class.simpleName}: ${t.message}"
            }
        }
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
                store.buildContext("DEV_SELF_TEST",chapter)
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
                val context=store.buildContext("CONTEXT_TEST",chapter)
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
                val context=store.buildContext("BACKEND_TEST",chapter)
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
        _messages.value = _messages.value + ChatMessage("player", text)

        viewModelScope.launch {
            val app = getApplication<Application>()
            val chapter = (_chronicle.value.maxOfOrNull { it.chapter } ?: 0) + 1

            if (_settings.value.gm141Enabled) {
                try {
                    DiagnosticLogger.log(app, "GM141_SEND_START", message = "chapter=$chapter")
                    _messages.value = _messages.value + ChatMessage(
                        "system",
                        "GM141: budowanie kontrolowanego kontekstu i rozstrzyganie tury..."
                    )

                    _visualSuggestions.value = runCatching {
                        val preview = store.buildContext(text, chapter)
                        VisualSuggestionEngine().suggest(text, preview)
                    }.getOrElse {
                        DiagnosticLogger.log(app, "GM141_VISUAL_SUGGESTIONS_GUARDED", it)
                        emptyList()
                    }

                    val outcome = GameMasterChatBridge141(app, store).play(
                        playerAction = text,
                        chapter = chapter,
                        backendUrl = _settings.value.backendUrl
                    )

                    _lastContextSummary.value = outcome.contextSummary
                    _messages.value = _messages.value + ChatMessage("gm", outcome.narrative)

                    if (_settings.value.showGmDiagnostics && outcome.warnings.isNotEmpty()) {
                        _messages.value = _messages.value + ChatMessage(
                            "system",
                            "GM141 ostrzeżenia: ${outcome.warnings.joinToString("; ")}"
                        )
                    }

                    runCatching { refresh() }
                        .onFailure { DiagnosticLogger.log(app, "GM141_REFRESH_GUARDED", it) }

                    if (_settings.value.autoBackup) {
                        try {
                            val saveInfo = store.finalizeChapter(chapter, "Rozdział $chapter")
                            _messages.value = _messages.value + ChatMessage(
                                "system",
                                "Tura GM141 jest zapisana. Manifest=${saveInfo.first.take(12)}… Backup utworzony."
                            )
                        } catch (t: Throwable) {
                            DiagnosticLogger.log(app, "GM141_AUTOSAVE_GUARDED", t)
                            _messages.value = _messages.value + ChatMessage(
                                "system",
                                "Tura GM141 jest zapisana, ale dodatkowy backup nie powiódł się."
                            )
                        }
                    }

                    DiagnosticLogger.log(app, "GM141_SEND_COMPLETE")
                } catch (t: Throwable) {
                    DiagnosticLogger.log(app, "GM141_SEND_GUARDED", t)
                    _messages.value = _messages.value + ChatMessage(
                        "system",
                        "GM141 odrzucił lub nie zakończył tury: ${t::class.simpleName}: ${t.message ?: "brak szczegółów"}. Stary StatePatch nie został uruchomiony."
                    )
                    _lastContextSummary.value =
                        "GM141: tura niezatwierdzona — ${t::class.simpleName}: ${t.message ?: "brak szczegółów"}"
                }
                return@launch
            }

            try {
                DiagnosticLogger.log(app, "SEND_START", message = "chapter=$chapter")
                _messages.value = _messages.value + ChatMessage("system", "Budowanie ContextBundle...")

                val context = try {
                    store.buildContext(text, chapter)
                } catch (t: Throwable) {
                    DiagnosticLogger.log(app, "CONTEXT_GUARDED", t)
                    _messages.value = _messages.value + ChatMessage(
                        "system",
                        "ContextBuilder zgłosił błąd. Używam bezpiecznego kontekstu awaryjnego."
                    )
                    ContextBundle(
                        playerStatus = mapOf("chapter" to chapter, "player_input" to text, "fallback" to true),
                        scene = mapOf("query" to text),
                        time = emptyMap(),
                        activeThreads = emptyList(),
                        relevantNpcs = emptyList(),
                        npcKnowledge = emptyList(),
                        missions = emptyList(),
                        worldPressures = emptyList(),
                        canonConstraints = emptyList(),
                        recentChronicle = emptyList(),
                        retrievedLongTermMemory = emptyList()
                    )
                }

                _visualSuggestions.value = runCatching {
                    VisualSuggestionEngine().suggest(text, context)
                }.getOrElse {
                    DiagnosticLogger.log(app, "VISUAL_SUGGESTIONS_GUARDED", it)
                    emptyList()
                }

                _lastContextSummary.value =
                    "ContextBundle v1: wątki=${context.activeThreads.size}, NPC=${context.relevantNpcs.size}, " +
                    "wiedza=${context.npcKnowledge.size}, misje=${context.missions.size}, " +
                    "wydarzenia=${context.activeWorldEvents.size}, techniki=${context.playerTechniques.size}, " +
                    "pamięć=${context.retrievedLongTermMemory.size}"

                _messages.value = _messages.value + ChatMessage(
                    "system",
                    "ContextBundle gotowy. Łączenie z backendem..."
                )

                val result = try {
                    BackendClient(_settings.value.backendUrl).sendTurn(text, chapter, context)
                } catch (t: Throwable) {
                    DiagnosticLogger.log(app, "BACKEND_GUARDED", t)
                    _messages.value = _messages.value + ChatMessage(
                        "system",
                        "Backend AI nie odpowiedział. Automatycznie uruchamiam tryb awaryjny."
                    )
                    SafeDemoGameMaster().respond(text, context, chapter)
                }

                _messages.value = _messages.value + ChatMessage("gm", result.narration)

                result.patch?.let { patch ->
                    try {
                        val applied = store.applyPatch(patch)
                        _messages.value = _messages.value + ChatMessage(
                            "system",
                            if (applied.success) "Zapisano ${applied.appliedOperations} zmian."
                            else "StatePatch odrzucony: ${applied.message}"
                        )

                        if (applied.success) {
                            if (_settings.value.autoBackup) {
                                try {
                                    val saveInfo = store.finalizeChapter(chapter, "Rozdział $chapter")
                                    _messages.value = _messages.value + ChatMessage(
                                        "system",
                                        "Rozdział zapisany. Manifest=${saveInfo.first.take(12)}… Backup utworzony."
                                    )
                                } catch (t: Throwable) {
                                    DiagnosticLogger.log(app, "AUTOSAVE_GUARDED", t)
                                    _messages.value = _messages.value + ChatMessage(
                                        "system",
                                        "Autosave nie powiódł się, ale gra działa dalej."
                                    )
                                }
                            }
                            runCatching { refresh() }
                                .onFailure { DiagnosticLogger.log(app, "REFRESH_GUARDED", it) }
                        }
                    } catch (t: Throwable) {
                        DiagnosticLogger.log(app, "STATEPATCH_GUARDED", t)
                        _messages.value = _messages.value + ChatMessage(
                            "system",
                            "StatePatch został bezpiecznie zablokowany: ${t.message ?: t::class.simpleName}"
                        )
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
            }
        }
    }

    fun diagnosticReport(): String =
        DiagnosticLogger.read(getApplication<Application>())

    fun clearDiagnosticReport() {
        DiagnosticLogger.clear(getApplication<Application>())
    }
}
