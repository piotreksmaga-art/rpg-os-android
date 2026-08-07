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

    private val _backendTest = MutableStateFlow("Nie testowano backendu.")
    val backendTest: StateFlow<String> = _backendTest

    fun testBackend() {
        viewModelScope.launch {
            runCatching {
                val client = BackendHealthClient(_settings.value.backendUrl)
                val health = client.check()
                val openai = client.checkOpenAI()
                _backendTest.value = "$health\n$openai"
            }.onFailure {
                _backendTest.value = "Błąd połączenia: ${it.message}"
            }
        }
    }

    private val _messages = MutableStateFlow(
        listOf(ChatMessage("system", "RPG OS Android v0.4 uruchomiony."))
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

    private val _lastContextSummary = MutableStateFlow("Brak zbudowanego ContextBundle.")
    val lastContextSummary: StateFlow<String> = _lastContextSummary

    init {
        store.bootstrap()
        refresh()
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

    fun send(text: String) {
        if (text.isBlank()) return
        _messages.value = _messages.value + ChatMessage("player", text)

        viewModelScope.launch {
            val chapter = (_chronicle.value.maxOfOrNull { it.chapter } ?: 0) + 1
            val context = store.buildContext(text, chapter)
            _visualSuggestions.value = VisualSuggestionEngine().suggest(text, context)
            _lastContextSummary.value =
                "Context: wątki=${context.activeThreads.size}, NPC=${context.relevantNpcs.size}, " +
                "wiedza=${context.npcKnowledge.size}, misje=${context.missions.size}, " +
                "presje=${context.worldPressures.size}, pamięć=${context.retrievedLongTermMemory.size}"

            val backend = BackendClient(_settings.value.backendUrl)
            val result = backend.sendTurn(text, chapter, context)
            _messages.value = _messages.value + ChatMessage("gm", result.narration)

            result.patch?.let { patch ->
                val applied = store.applyPatch(patch)
                _messages.value = _messages.value + ChatMessage(
                    "system",
                    if (applied.success) "Zapisano ${applied.appliedOperations} zmian."
                    else "StatePatch odrzucony: ${applied.message}"
                )
                if (applied.success) {
                    if (_settings.value.autoBackup) {
                        val saveInfo = store.finalizeChapter(chapter, "Rozdział $chapter")
                        _messages.value = _messages.value + ChatMessage(
                            "system",
                            "Rozdział zapisany. Manifest=${saveInfo.first.take(12)}… Backup utworzony."
                        )
                    }
                    refresh()
                }
            }
        }
    }
}
