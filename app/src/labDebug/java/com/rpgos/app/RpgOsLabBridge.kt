package com.rpgos.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.Uri
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val LAB_PROTOCOL = "RPGOS_LAB_V1"
private const val LAB_SOCKET = "rpgos_lab_bridge"
private const val MAX_REQUEST_CHARS = 1_048_576

/** Machine-readable Stage-3 contract.  Keeping the registry in the lab source set makes it
 * impossible for the release variant to expose these commands accidentally. */
internal object RpgOsLabBridgeContract {
    const val stage = 3
    const val protocol = LAB_PROTOCOL
    val productionPathCommands = setOf(
        "SUBMIT_PLAYER_ACTION", "RUN_ACTION_SEQUENCE", "RUN_COMBAT_SCENARIO",
        "SUBMIT_CHARACTER_CREATION", "CONFIRM_CHARACTER_CREATION", "RECOVER_PENDING_NARRATION"
    )
    val readCommands = setOf(
        "HEALTH", "GET_CAPABILITIES", "LIST_CAMPAIGNS", "GET_ACTIVE_STATE", "GET_CHARACTER_STATE",
        "GET_CONTEXT_BUNDLE", "GET_TURN_STATE", "GET_MECHANICAL_STATE", "GET_PIPELINE_SNAPSHOT", "GET_LAST_COMMIT",
        "GET_CANONICAL_FINGERPRINT", "GET_COMMIT_STATE", "GET_AI_STATE", "GET_RUNTIME_STATE",
        "GET_RECOVERY_STATE", "GET_DIRECTOR_STATE", "SEARCH_BEKKO", "GET_AI_TRACE",
        "GET_LAST_AI_EXCHANGE", "GET_LAST_TURN", "GET_LAST_SCENARIO", "GET_LAST_FAILURE",
        "EXPORT_FAILURE_BUNDLE", "EXPORT_LAB_FIXTURE", "GET_PENDING_CHARACTER_DRAFT",
        "GET_CODEX_PROVIDER_STATE", "GET_DIRECTOR_JOBS", "GET_DIRECTOR_CANDIDATES", "GET_DIRECTOR_GUIDANCE"
    )
    val labAdminCommands = setOf(
        "SET_ACTIVE_CAMPAIGN", "CREATE_CAMPAIGN", "LOAD_LAB_FIXTURE", "IMPORT_LOCAL_GGUF",
        "SELECT_LOCAL_AI", "CLEAR_AI_TRACE", "CANCEL_ACTIVE_OPERATION", "REGISTER_CODEX_HOST",
        "CODEX_HOST_HEARTBEAT", "CLAIM_AI_REQUEST", "COMPLETE_AI_REQUEST", "FAIL_AI_REQUEST",
        "CANCEL_AI_REQUEST", "SET_LAB_AI_ASSIGNMENTS", "RUN_DIRECTOR_NOW", "CLEAR_DIRECTOR_SIDECAR",
        "OPEN_LAB_DIAGNOSTICS"
    )
    val allCommands = productionPathCommands + readCommands + labAdminCommands
}

/** Starts the lab-only bridge before the launcher activity. This class is absent from release. */
class RpgOsLabBridgeInitializer : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.applicationContext?.let{app->
            LabCodexProviderRuntime.install(app)
            RpgOsLabBridgeServer.start(app)
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

private object RpgOsLabBridgeServer {
    private val started = AtomicBoolean(false)
    private val acceptor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "rpgos-lab-accept").apply { isDaemon = true } }
    private val clients = Executors.newCachedThreadPool { runnable -> Thread(runnable, "rpgos-lab-client").apply { isDaemon = true } }
    @Volatile private var server: LocalServerSocket? = null

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        acceptor.execute {
            try {
                val socket = LocalServerSocket(LAB_SOCKET).also { server = it }
                val runtime = RpgOsLabRuntime(context)
                while (!Thread.currentThread().isInterrupted) {
                    val client = socket.accept()
                    clients.execute { handleClient(client, runtime) }
                }
            } catch (failure: Throwable) {
                DiagnosticLogger.log(context, "LAB_BRIDGE_SERVER_DIED", failure)
                started.set(false)
            }
        }
    }

    private fun handleClient(socket: LocalSocket, runtime: RpgOsLabRuntime) {
        // ADB forwarding, a cancelled host worker, or a laptop sleep may close the peer while a
        // long-poll response is being written. A laboratory transport disconnect must never be an
        // uncaught exception capable of terminating the Android application process.
        runCatching {
            socket.use { client ->
                val reader = BufferedReader(InputStreamReader(client.inputStream, Charsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(client.outputStream, Charsets.UTF_8))
                while (true) {
                    val line = reader.readLine() ?: break
                    val response = if (line.length > MAX_REQUEST_CHARS) {
                        failureResponse(null, "LAB_REQUEST_TOO_LARGE")
                    } else {
                        runCatching { runtime.dispatch(JSONObject(line)) }
                            .getOrElse { failure -> failureResponse(null, typedReason("LAB_UNHANDLED", failure)) }
                    }
                    writer.write(response.toString())
                    writer.newLine()
                    writer.flush()
                }
            }
        }
    }

    private fun failureResponse(requestUid: String?, reasonUid: String) = JSONObject()
        .put("protocol", LAB_PROTOCOL)
        .put("request_uid", requestUid ?: JSONObject.NULL)
        .put("state", "FAILURE")
        .put("payload", JSONObject())
        .put("reason_uid", reasonUid)
}

private class RpgOsLabRuntime(context: Context) {
    private val app = context.applicationContext
    private val repository by lazy { UnifiedGameRepository(app).also { it.bootstrap() } }
    private val settings by lazy { AppSettings(app) }
    private val aiTrace = LabCodexProviderRuntime.trace
    private val providerCenter by lazy { AndroidAiProviderCenterApplication(app, aiTrace) }
    private val localArtifacts by lazy { AndroidLocalModelArtifactStore(app) }
    private val semantic by lazy { BekkoSemanticApplication(app, repository) }
    private val composition by lazy {
        ProductionGameEngineCompositionRoot(
            app,
            repository,
            providerCenter,
            configuration = { AiProviderExtensionRegistry.configuration(settings.load().ai) },
            additionalProviders = AiProviderExtensionRegistry::providers,
            semanticApplication = semantic,
            directorGuidance = AiProviderExtensionRegistry.directorGuidancePort()
        )
    }
    private val chat by lazy { DynamicCanonicalChatApplication { composition.chatApplication() } }
    private val characterCreation by lazy { composition.characterCreationApplication() }
    private val actionLock = Any()
    private val bootElapsedRealtime = SystemClock.elapsedRealtime()
    @Volatile private var lastTurn: JSONObject? = null
    @Volatile private var lastFailure: JSONObject? = null
    @Volatile private var lastCharacterCreation: JSONObject? = null
    @Volatile private var lastScenario: JSONObject? = null
    @Volatile private var activeCancellation: MutableAiCancellationSignal? = null

    fun dispatch(request: JSONObject): JSONObject {
        val requestUid = request.optString("request_uid").ifBlank { "LAB:${UUID.randomUUID()}" }
        if (request.optString("protocol") != LAB_PROTOCOL) return response(requestUid, "FAILURE", reasonUid = "LAB_PROTOCOL_MISMATCH")
        val command = request.optString("command").trim().uppercase()
        val arguments = request.optJSONObject("arguments") ?: JSONObject()
        return try {
            val payload = when (command) {
                "HEALTH" -> health()
                "GET_CAPABILITIES" -> capabilities()
                "LIST_CAMPAIGNS" -> listCampaigns()
                "GET_ACTIVE_STATE" -> activeState()
                "SET_ACTIVE_CAMPAIGN" -> setActiveCampaign(arguments)
                "CREATE_CAMPAIGN" -> createCampaign(arguments)
                "GET_CHARACTER_STATE" -> characterState()
                "GET_CONTEXT_BUNDLE" -> contextBundle(arguments)
                "GET_TURN_STATE" -> turnState()
                "GET_MECHANICAL_STATE" -> mechanicalState(arguments)
                "GET_PIPELINE_SNAPSHOT" -> pipelineSnapshot(arguments)
                "GET_LAST_COMMIT" -> lastCommit()
                "GET_CANONICAL_FINGERPRINT" -> canonicalFingerprint()
                "GET_COMMIT_STATE" -> commitState()
                "GET_AI_STATE" -> aiState()
                "GET_RUNTIME_STATE" -> runtimeState()
                "GET_RECOVERY_STATE" -> recoveryState()
                "RECOVER_PENDING_NARRATION" -> recoverPendingNarration()
                "GET_DIRECTOR_STATE" -> directorState()
                "GET_DIRECTOR_JOBS" -> LabCodexProviderRuntime.directorJobs()
                "GET_DIRECTOR_CANDIDATES" -> LabCodexProviderRuntime.directorCandidates()
                "GET_DIRECTOR_GUIDANCE" -> LabCodexProviderRuntime.directorGuidance(arguments)
                "RUN_DIRECTOR_NOW" -> LabCodexProviderRuntime.runDirector(arguments)
                "CLEAR_DIRECTOR_SIDECAR" -> LabCodexProviderRuntime.clearDirector()
                "GET_CODEX_PROVIDER_STATE" -> LabCodexProviderRuntime.state()
                "REGISTER_CODEX_HOST" -> LabCodexProviderRuntime.register(arguments)
                "CODEX_HOST_HEARTBEAT" -> LabCodexProviderRuntime.heartbeat(arguments)
                "CLAIM_AI_REQUEST" -> LabCodexProviderRuntime.claim(arguments)
                "COMPLETE_AI_REQUEST" -> LabCodexProviderRuntime.complete(arguments)
                "FAIL_AI_REQUEST" -> LabCodexProviderRuntime.fail(arguments)
                "CANCEL_AI_REQUEST" -> LabCodexProviderRuntime.cancel(arguments)
                "SET_LAB_AI_ASSIGNMENTS" -> LabCodexProviderRuntime.setAssignments(arguments)
                "OPEN_LAB_DIAGNOSTICS" -> openLabDiagnostics()
                "SEARCH_BEKKO" -> searchBekko(arguments)
                "IMPORT_LOCAL_GGUF" -> importLocalGguf(arguments)
                "SELECT_LOCAL_AI" -> selectLocalAi(arguments)
                "GET_AI_TRACE" -> aiTrace.read(arguments.optInt("limit", 100).coerceIn(1, 500), arguments.optString("request_uid_prefix").ifBlank { null })
                "GET_LAST_AI_EXCHANGE" -> aiTrace.latestExchange(arguments.optString("workload").ifBlank { null })
                "CLEAR_AI_TRACE" -> aiTrace.clear()
                "GET_LAST_TURN" -> lastTurn ?: JSONObject().put("available", false)
                "GET_LAST_SCENARIO" -> lastScenario ?: JSONObject().put("available", false)
                "GET_LAST_FAILURE" -> lastFailure ?: JSONObject().put("available", false)
                "EXPORT_FAILURE_BUNDLE" -> failureBundle(arguments)
                "EXPORT_LAB_FIXTURE" -> fixtureManifest()
                "LOAD_LAB_FIXTURE" -> loadFixture(arguments)
                "SUBMIT_PLAYER_ACTION" -> submitPlayerAction(arguments)
                "RUN_ACTION_SEQUENCE", "RUN_COMBAT_SCENARIO" -> runActionSequence(arguments, command)
                "CANCEL_ACTIVE_OPERATION" -> cancelActiveOperation()
                "SUBMIT_CHARACTER_CREATION" -> submitCharacterCreation(arguments)
                "CONFIRM_CHARACTER_CREATION" -> confirmCharacterCreation(arguments)
                "GET_PENDING_CHARACTER_DRAFT" -> pendingCharacterDraft()
                else -> return response(requestUid, "FAILURE", reasonUid = "LAB_COMMAND_UNSUPPORTED:$command")
            }
            response(requestUid, "SUCCESS", payload)
        } catch (failure: Throwable) {
            val reason = typedReason("LAB_COMMAND_FAILED:$command", failure)
            lastFailure = JSONObject().put("command", command).put("reason_uid", reason).put("at_epoch_ms", System.currentTimeMillis())
            response(requestUid, "FAILURE", reasonUid = reason)
        }
    }

    private fun health(): JSONObject {
        val campaign = runCatching { repository.activeCampaignRef() }.getOrNull()
        return JSONObject()
            .put("bridge", "RPG OS LAB BRIDGE")
            .put("protocol", LAB_PROTOCOL)
            .put("bridge_stage", RpgOsLabBridgeContract.stage)
            .put("socket", LAB_SOCKET)
            .put("build_type", BuildConfig.BUILD_TYPE)
            .put("version_name", BuildConfig.VERSION_NAME)
            .put("process_id", Process.myPid())
            .put("uptime_ms", SystemClock.elapsedRealtime() - bootElapsedRealtime)
            .put("active_campaign_uid", campaign?.campaignId ?: JSONObject.NULL)
            .put("active_campaign_directory", campaign?.directoryName ?: JSONObject.NULL)
    }

    private fun openLabDiagnostics():JSONObject{
        app.startActivity(Intent(app,LabDiagnosticsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return JSONObject().put("opened",true).put("activity","LabDiagnosticsActivity")
    }

    private fun capabilities() = JSONObject()
        .put("protocol", LAB_PROTOCOL)
        .put("bridge_stage", RpgOsLabBridgeContract.stage)
        .put("transport", "ANDROID_LOCALABSTRACT_SOCKET")
        .put("production_path_commands", JSONArray(RpgOsLabBridgeContract.productionPathCommands.sorted()))
        .put("read_commands", JSONArray(RpgOsLabBridgeContract.readCommands.sorted()))
        .put("lab_admin_commands", JSONArray(RpgOsLabBridgeContract.labAdminCommands.sorted()))
        .put("maximum_sequence_actions", 100)
        .put("canonical_writes_only_through_application_ports", true)
        .put("release_included", false)

    private fun listCampaigns(): JSONObject {
        val active = repository.activeCampaignDirName()
        val campaigns = repository.packageManager().listCampaigns().sortedBy { it.name }.map { campaign ->
            JSONObject().put("id", campaign.id).put("name", campaign.name).put("directory", java.io.File(campaign.path).name)
                .put("backup_count", campaign.backupCount).put("active", java.io.File(campaign.path).name == active)
        }
        return JSONObject().put("campaigns", JSONArray(campaigns))
    }

    private fun activeState(): JSONObject {
        val campaign = repository.activeCampaignRef()
        val player = repository.activePlayerRef()
        val status = repository.status()
        val time = repository.time()
        val receipt = repository.infrastructureLastReceipt()
        return JSONObject()
            .put("campaign", campaignJson(campaign))
            .put("player", player?.let(::playerJson) ?: JSONObject.NULL)
            .put("status", statusJson(status))
            .put("time", JSONObject().put("label", time.label).put("era", time.era).put("season", time.season).put("hour", time.hour))
            .put("last_commit", receipt?.let(::receiptJson) ?: JSONObject.NULL)
    }

    private fun setActiveCampaign(arguments: JSONObject): JSONObject {
        val directory = arguments.requiredString("directory")
        repository.setActiveCampaign(directory)
        semantic.onCampaignOpened()
        AiProviderExtensionRegistry.onCampaignOpened(repository.activeCampaignRef().campaignId)
        return activeState()
    }

    private fun createCampaign(arguments: JSONObject): JSONObject {
        val name = arguments.requiredString("name")
        val created = repository.createCampaign(name)
        semantic.onCampaignOpened()
        AiProviderExtensionRegistry.onCampaignOpened(repository.activeCampaignRef().campaignId)
        return JSONObject().put("directory", created.name).put("active_state", activeState())
    }

    /** A fixture is a verifiable pointer to an existing canonical campaign, not a second save
     * format.  Loading it only selects the campaign and verifies its authoritative receipt. */
    private fun fixtureManifest():JSONObject{
        val campaign=repository.activeCampaignRef()
        val player=repository.activePlayerRef()
        val receipt=repository.infrastructureLastReceipt()
        return JSONObject()
            .put("schema","RPGOS_LAB_FIXTURE_V1")
            .put("fixture_uid","LAB-FIXTURE:${semanticSha256("${campaign.campaignId}|${receipt?.commitOrder?:0}|${receipt?.semanticFingerprint.orEmpty()}").take(32)}")
            .put("campaign_directory",campaign.directoryName).put("campaign_uid",campaign.campaignId)
            .put("player_uid",player?.playerUid ?: JSONObject.NULL)
            .put("committed_order",receipt?.commitOrder ?: repository.infrastructureLastCommitOrder())
            .put("semantic_fingerprint",receipt?.semanticFingerprint ?: JSONObject.NULL)
            .put("result_fingerprint",receipt?.resultFingerprint ?: JSONObject.NULL)
            .put("exported_at_epoch_ms",System.currentTimeMillis())
    }

    private fun loadFixture(arguments:JSONObject):JSONObject=synchronized(actionLock){
        require(arguments.optString("schema","RPGOS_LAB_FIXTURE_V1")=="RPGOS_LAB_FIXTURE_V1"){"LAB_FIXTURE_SCHEMA_UNSUPPORTED"}
        val directory=arguments.optString("campaign_directory").ifBlank{arguments.optString("directory")}.trim()
            .takeIf(String::isNotEmpty)?:throw IllegalArgumentException("LAB_ARGUMENT_REQUIRED:campaign_directory")
        val previous=repository.activeCampaignDirName()
        try{
            repository.setActiveCampaign(directory);semantic.onCampaignOpened()
            val actual=fixtureManifest()
            fun exactString(key:String){
                val expected=arguments.optString(key).trim()
                if(expected.isNotBlank())require(actual.optString(key)==expected){"LAB_FIXTURE_${key.uppercase()}_MISMATCH"}
            }
            exactString("campaign_uid");exactString("player_uid");exactString("semantic_fingerprint");exactString("result_fingerprint")
            if(arguments.has("committed_order"))require(actual.getLong("committed_order")==arguments.getLong("committed_order")){
                "LAB_FIXTURE_COMMITTED_ORDER_MISMATCH"
            }
            JSONObject().put("loaded",true).put("fixture",actual).put("active_state",activeState())
        }catch(failure:Throwable){
            if(repository.activeCampaignDirName()!=previous)runCatching{repository.setActiveCampaign(previous);semantic.onCampaignOpened()}
            throw failure
        }
    }

    private fun characterState(): JSONObject {
        val campaign = repository.activeCampaignRef().campaignId
        val audience = VisibilityAudienceFactory.player(campaign)
        val purpose = PurposeContext(campaign, VisibilityPurposeKinds.GAMEPLAY_NARRATION)
        val panel = repository.fullCharacterPanel(audience, purpose)
        val panelV2 = repository.infrastructureCharacterPanelV2(audience,purpose)
        val canonicalState = repository.infrastructurePlayerState()
        return JSONObject()
            .put("active_player", repository.activePlayerRef()?.let(::playerJson) ?: JSONObject.NULL)
            .put("canonical_state", canonicalState?.let{JSONObject(it.toContextMap())} ?: JSONObject.NULL)
            .put("canonical_stats", JSONArray(repository.infrastructurePlayerStats().map(::playerStatJson)))
            .put("canonical_resources", JSONArray(repository.infrastructurePlayerResources().map(::playerResourceJson)))
            .put("panel_v2",panelV2?.let(::characterPanelV2Json) ?: JSONObject.NULL)
            .put("identity", JSONArray(panel.identity.map(::statLineJson)))
            .put("stats", JSONArray(panel.stats.map(::statLineJson)))
            .put("resources", JSONArray(panel.resources.map(::statLineJson)))
            .put("skills", JSONArray(panel.skills.map { JSONObject().put("name", it.name).put("mastery", it.mastery).put("category", it.category) }))
            .put("techniques", JSONArray(panel.techniques.map { JSONObject().put("name", it.name).put("mastery", it.mastery).put("chakra_cost", it.chakraCost).put("category", it.category) }))
            .put("equipment", JSONArray(panel.equipment)).put("relationships", JSONArray(panel.relationships)).put("goals", JSONArray(panel.goals))
    }

    private fun contextBundle(arguments: JSONObject): JSONObject {
        val input = arguments.optString("player_input", "LAB_CONTEXT_INSPECTION").ifBlank { "LAB_CONTEXT_INSPECTION" }
        val campaign = repository.activeCampaignRef().campaignId
        val audience = VisibilityAudienceFactory.player(campaign)
        val purpose = PurposeContext(campaign, VisibilityPurposeKinds.GAMEPLAY_NARRATION)
        val chapter = arguments.optInt("chapter", (repository.infrastructureLastCommitOrder() + 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        val context = repository.buildContext(
            input, chapter, audience, purpose
        )
        val truthDiagnostic = when (val read = repository.protectedReads().truthContextRows(audience, purpose)) {
            is ProtectedReadResult.Allow -> JSONObject().put("state", read.stateUid).put("row_count", read.value.size)
            is ProtectedReadResult.Corruption -> JSONObject().put("state", read.stateUid)
                .put("reason_uid", read.reasonCode)
                .put("failure_type", read.error::class.java.name)
                .put("failure_message", read.error.message ?: JSONObject.NULL)
                .put("cause_type", read.error.cause?.javaClass?.name ?: JSONObject.NULL)
                .put("cause_message", read.error.cause?.message ?: JSONObject.NULL)
            is ProtectedReadResult.Deny -> JSONObject().put("state", read.stateUid).put("reason_uid", read.reasonCode)
            is ProtectedReadResult.NotDisclosed -> JSONObject().put("state", read.stateUid).put("reason_uid", read.reasonCode)
            is ProtectedReadResult.Unknown -> JSONObject().put("state", read.stateUid).put("reason_uid", read.reasonCode)
            ProtectedReadResult.NoData -> JSONObject().put("state", read.stateUid).put("row_count", 0)
        }
        return JSONObject().put("player_input", input).put("chapter", chapter)
            .put("bundle", JsonCodec.contextToJson(context))
            .put("protected_read_diagnostics", JSONObject().put("campaign_truth", truthDiagnostic))
    }

    private fun turnState(): JSONObject = JSONObject()
        .put("active", activeState())
        .put("character", characterState())
        .put("last_turn", lastTurn ?: JSONObject.NULL)
        .put("last_scenario", lastScenario ?: JSONObject.NULL)
        .put("last_commit", lastCommit())
        .put("canonical_fingerprint", canonicalFingerprint())
        .put("recovery", recoveryState())
        .put("pending_character_draft", pendingCharacterDraft())

    private fun mechanicalState(arguments:JSONObject):JSONObject{
        val requested=arguments.optJSONArray("refs")?:JSONArray()
        val refs=buildList{
            for(index in 0 until requested.length())requested.optJSONObject(index)?.let{value->
                val kind=value.optString("kind_uid").trim();val uid=value.optString("uid").trim()
                if(kind.isNotBlank()&&uid.isNotBlank())add(DomainRef(kind,uid))
            }
            if(isEmpty())repository.activePlayerRef()?.let{add(DomainRef("PLAYER",it.playerUid))}
        }.distinct()
        return JSONObject().put("campaign_uid",repository.activeCampaignRef().campaignId).put("actors",JSONArray(refs.map{ref->
            val mechanical=repository.infrastructureMechanicalActor(ref)
            val persistence=repository.infrastructureMechanicalPersistence(ref.uid)
            JSONObject().put("kind_uid",ref.kindUid).put("uid",ref.uid)
                .put("direct_location_uid",repository.infrastructureEntityLocationUid(ref.uid)?:JSONObject.NULL)
                .put("scene_anchor_uid",repository.infrastructureEntitySceneAnchorUid(ref.uid)?:JSONObject.NULL)
                .put("scene_path_uids",JSONArray(repository.infrastructureEntityScenePathUids(ref.uid)))
                .put("position",persistence.position?.let(::combatPositionJson)?:JSONObject.NULL)
                .put("mechanical_materialized",mechanical!=null)
                .put("mechanical_kind",mechanical?.kind?.name?:JSONObject.NULL)
                .put("state_version",mechanical?.stateVersion?:JSONObject.NULL)
                .put("attributes",mechanical?.attributes?.let{JSONObject(it.mapValues{entry->entry.value as Any})}?:JSONObject.NULL)
                .put("abilities",mechanical?.executableAbilityUids?.sorted()?.let(::JSONArray)?:JSONObject.NULL)
                .put("conditions",mechanical?.conditions?.map{it.conditionUid}?.let(::JSONArray)?:JSONObject.NULL)
        }))
    }

    private fun combatPositionJson(position:CombatPosition):JSONObject=when(position){
        is CombatPosition.Exact->JSONObject().put("kind","EXACT").put("x_mm",position.xMillimetres).put("y_mm",position.yMillimetres).put("z_mm",position.zMillimetres)
        is CombatPosition.Grid->JSONObject().put("kind","GRID").put("grid_uid",position.gridUid).put("column",position.column).put("row",position.row).put("layer",position.layer)
        is CombatPosition.Zone->JSONObject().put("kind","ZONE").put("zone_uid",position.zoneUid)
        is CombatPosition.RangeBand->JSONObject().put("kind","RANGE_BAND").put("anchor_kind_uid",position.anchor.kindUid).put("anchor_uid",position.anchor.uid).put("band_uid",position.bandUid).put("ordinal",position.ordinal)
        is CombatPosition.Formation->JSONObject().put("kind","FORMATION").put("formation_uid",position.formationUid).put("slot_uid",position.slotUid)
    }

    private fun pipelineSnapshot(arguments:JSONObject):JSONObject{
        val snapshot=JSONObject()
            .put("captured_at_epoch_ms",System.currentTimeMillis())
            .put("health",health()).put("runtime",runtimeState()).put("active",activeState())
            .put("character",characterState()).put("commit",commitState()).put("ai",aiState())
            .put("recovery",recoveryState()).put("director",directorState())
            .put("last_turn",lastTurn ?: JSONObject.NULL).put("last_scenario",lastScenario ?: JSONObject.NULL)
            .put("last_ai_exchange",aiTrace.latestExchange(arguments.optString("workload").ifBlank{null}))
        if(arguments.optBoolean("include_context",true))snapshot.put("context",contextBundle(arguments))
        return snapshot
    }

    private fun lastCommit(): JSONObject = repository.infrastructureLastReceipt()?.let(::receiptJson)
        ?: JSONObject().put("available", false).put("committed_order", repository.infrastructureLastCommitOrder())

    private fun canonicalFingerprint(): JSONObject {
        val receipt = repository.infrastructureLastReceipt()
        return JSONObject().put("available", receipt != null)
            .put("committed_order", receipt?.commitOrder ?: repository.infrastructureLastCommitOrder())
            .put("result_fingerprint", receipt?.resultFingerprint ?: JSONObject.NULL)
            .put("semantic_fingerprint", receipt?.semanticFingerprint ?: JSONObject.NULL)
    }

    private fun commitState():JSONObject=JSONObject()
        .put("committed_order",repository.infrastructureLastCommitOrder())
        .put("last_commit",lastCommit())
        .put("canonical_fingerprint",canonicalFingerprint())

    private fun runtimeState():JSONObject{
        val memory=Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val javaRuntime=Runtime.getRuntime()
        return JSONObject()
            .put("process_id",Process.myPid()).put("uptime_ms",SystemClock.elapsedRealtime()-bootElapsedRealtime)
            .put("active_operation",activeCancellation!=null).put("thread_count",Thread.getAllStackTraces().size)
            .put("pss_kb",memory.totalPss).put("private_dirty_kb",memory.totalPrivateDirty)
            .put("shared_dirty_kb",memory.totalSharedDirty)
            .put("java_heap_used_bytes",javaRuntime.totalMemory()-javaRuntime.freeMemory())
            .put("java_heap_max_bytes",javaRuntime.maxMemory())
    }

    private fun recoveryState():JSONObject{
        val token=runCatching{chat.pendingRecovery()}.getOrNull()
        return JSONObject().put("available",token!=null).put("request",token?.request?.let(::recoveryRequestJson) ?: JSONObject.NULL)
    }

    private fun recoverPendingNarration():JSONObject=synchronized(actionLock){
        val token=chat.pendingRecovery()?:return@synchronized JSONObject().put("outcome","NO_PENDING_RECOVERY")
        val started=SystemClock.elapsedRealtime()
        val cancellation=MutableAiCancellationSignal().also{activeCancellation=it}
        val recovered=try{runBlocking{chat.recover(token,cancellation)}}finally{activeCancellation=null}
        narrativeRecoveryJson(recovered).put("elapsed_ms",SystemClock.elapsedRealtime()-started)
            .put("request",recoveryRequestJson(token.request))
    }

    /** Phase65 is not allowed to mutate canonical state.  Stage 2 reports the honest production
     * wiring state and the latest wire exchange instead of constructing a parallel Director. */
    private fun directorState():JSONObject{
        val bekko=semantic.state()
        return LabCodexProviderRuntime.directorState()
            .put("semantic_scout_available",bekko.settings.enabled&&bekko.availability.state==EmbeddingAvailabilityState.READY)
            .put("last_ai_exchange",aiTrace.latestExchange(AiWorkload.DIRECTOR_STRATEGY.name))
    }

    private fun aiState(): JSONObject {
        val configuration = AiProviderExtensionRegistry.configuration(settings.load().ai)
        val center = providerCenter.initialState(configuration)
        val bekko = semantic.state()
        val artifact = localArtifacts.find(center.localSettings.modelUid, center.localSettings.variantUid)
        return JSONObject()
            .put("game_master_assignment", assignmentJson(configuration.gameMaster))
            .put("director_assignment", assignmentJson(configuration.director))
            .put("codex",LabCodexProviderRuntime.state())
            .put("local_model_uid", center.localProfile.modelUid)
            .put("local_model_name", center.localProfile.displayName)
            .put("local_runtime_engine", center.localSettings.runtimeEngine.name)
            .put("local_backend", center.localSettings.backend.name)
            .put("local_context_units", center.localSettings.contextUnits)
            .put("local_variant_uid", center.localSettings.variantUid)
            .put("local_threads", center.localSettings.threads ?: JSONObject.NULL)
            .put("local_prefill_batch_units", center.localSettings.prefillBatchUnits ?: JSONObject.NULL)
            .put("local_micro_batch_units", center.localSettings.microBatchUnits ?: JSONObject.NULL)
            .put("local_gpu_layers", center.localSettings.gpuLayers ?: JSONObject.NULL)
            .put("local_artifact_installed", center.localArtifactInstalled)
            .put("local_artifact_bytes", artifact?.byteSize ?: JSONObject.NULL)
            .put("local_artifact_sha256", artifact?.sha256 ?: JSONObject.NULL)
            .put("local_runtime_available", center.localRuntimeAvailable)
            .put("openrouter_state", center.openRouterStatus.state.name)
            .put("openrouter_reason_uid", center.openRouterStatus.reasonUid ?: JSONObject.NULL)
            .put("bekko", JSONObject()
                .put("enabled", bekko.settings.enabled).put("backend", bekko.settings.backend.name)
                .put("model_installed", bekko.modelInstalled).put("availability", bekko.availability.state.name)
                .put("availability_reason_uid", bekko.availability.reasonUid)
                .put("indexed_records", bekko.indexStatus?.recordCount ?: 0)
                .put("checkpoint", bekko.indexStatus?.lastIndexedCommitOrder ?: 0L)
                .put("index_reason_uid", bekko.indexStatus?.reasonUid ?: JSONObject.NULL))
    }

    private fun searchBekko(arguments: JSONObject): JSONObject {
        val query=arguments.requiredString("query").take(1_024)
        val campaign=repository.activeCampaignRef().campaignId
        val audienceKind=arguments.optString("audience","PLAYER").trim().uppercase()
        val (audience,purpose)=when(audienceKind){
            "PLAYER"->VisibilityAudienceFactory.player(campaign) to PurposeContext(campaign,VisibilityPurposeKinds.GAMEPLAY_NARRATION)
            "GM","GM_RUNTIME"->AudienceContext(campaign,AudienceKinds.GM_RUNTIME,VisibilityPrincipalRef(AudienceKinds.GM_RUNTIME,"LAB_GM")) to
                PurposeContext(campaign,VisibilityPurposeKinds.INTERNAL_SIMULATION)
            else->throw IllegalArgumentException("LAB_BEKKO_AUDIENCE_UNSUPPORTED:$audienceKind")
        }
        val operation=when(arguments.optString("namespace","CAMPAIGN").trim().uppercase()){
            "CAMPAIGN","MEMORY"->BEKKO_OPERATION_MEMORY
            "WORLD_PACK","WORLDPACK"->BEKKO_OPERATION_WORLD_PACK
            "RELATED"->BEKKO_OPERATION_RELATED
            else->throw IllegalArgumentException("LAB_BEKKO_NAMESPACE_UNSUPPORTED")
        }
        val filters=buildMap{
            put("query_text",query)
            arguments.optString("record_kinds").trim().takeIf(String::isNotBlank)?.let{put("record_kinds",it.take(512))}
            if(arguments.has("minimum_score"))put("minimum_score",arguments.optDouble("minimum_score").coerceIn(-1.0,1.0).toString())
        }
        val limit=arguments.optInt("limit",10).coerceIn(1,50)
        val at=arguments.optLong("at_order",repository.infrastructureLastCommitOrder()).coerceAtLeast(0)
        semantic.catchUp()
        val binding=semantic.structuredBinding()
        val envelope=CapabilityEnvelope(
            "LAB-BEKKO:${semanticSha256("$campaign|$query|$audienceKind|$operation").take(24)}",campaign,binding.providerUid,operation,
            setOf("query_text","record_kinds","minimum_score"),maximumLimit=50,audience=audience,purpose=purpose,atOrder=at
        )
        val request=StructuredRetrievalRequest(
            "LAB-BEKKO-REQUEST:${UUID.randomUUID()}",campaign,binding.providerUid,operation,filters,limit,audience,purpose,at
        )
        require(envelope.validate(request) is EnvelopeValidationResult.Allowed){"LAB_BEKKO_ENVELOPE_REJECTED"}
        return structuredRetrievalJson(StructuredSqlRetriever(listOf(binding)).retrieve(request))
            .put("query",query).put("audience",audienceKind).put("operation_uid",operation).put("at_order",at)
    }

    private fun failureBundle(arguments: JSONObject): JSONObject {
        val includeContext=arguments.optBoolean("include_context",true)
        val bundle=JSONObject()
            .put("captured_at_epoch_ms",System.currentTimeMillis())
            .put("health",health()).put("runtime_state",runtimeState())
            .put("active_state",activeState()).put("character_state",characterState())
            .put("ai_state",aiState()).put("last_commit",lastCommit()).put("canonical_fingerprint",canonicalFingerprint())
            .put("last_turn",lastTurn ?: JSONObject.NULL).put("last_failure",lastFailure ?: JSONObject.NULL)
            .put("last_scenario",lastScenario ?: JSONObject.NULL).put("recovery_state",recoveryState())
            .put("director_state",directorState()).put("pending_character_draft",pendingCharacterDraft())
            .put("ai_trace",aiTrace.read(arguments.optInt("trace_limit",100).coerceIn(1,500),null))
        if(includeContext)bundle.put("context",contextBundle(JSONObject().put("player_input",arguments.optString("player_input","LAB_FAILURE_INSPECTION"))))
        return bundle
    }

    /**
     * Imports a model from the private lab-import directory. On Android 14+, a file pushed by
     * ADB into getExternalFilesDir() is owned by the shell/FUSE view and may be invisible to the
     * application despite living below its package directory. The debuggable lab build instead
     * receives staged files through `run-as`, while the production artifact store still performs
     * the same atomic private installation used by the real UI.
     */
    private fun importLocalGguf(arguments: JSONObject): JSONObject = synchronized(actionLock) {
        val fileName = arguments.requiredString("file_name")
        require(File(fileName).name == fileName) { "LAB_GGUF_FILE_NAME_ONLY" }
        val importRoot = File(app.filesDir, "lab-import").apply { mkdirs() }.canonicalFile
        val source = File(importRoot, fileName).canonicalFile
        require(source.parentFile == importRoot && source.isFile && source.length() > 0L) { "LAB_GGUF_SOURCE_MISSING" }
        source.inputStream().use { input ->
            val magic = ByteArray(4)
            require(input.read(magic) == 4 && magic.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))) {
                "LAB_GGUF_MAGIC_INVALID"
            }
        }
        val profile = BielikLocalModelProfiles.USER_GGUF
        val modelSettings = ggufSettings(arguments)
        val artifact = localArtifacts.import(profile.modelUid, modelSettings.variantUid, source.inputStream())
        val expectedSha = arguments.optString("expected_sha256").trim().lowercase()
        if (expectedSha.isNotBlank() && artifact.sha256.lowercase() != expectedSha) {
            localArtifacts.remove(profile.modelUid, modelSettings.variantUid)
            throw IllegalArgumentException("LAB_GGUF_SHA256_MISMATCH")
        }
        persistLocalSelection(modelSettings, arguments)
        if (arguments.optBoolean("delete_source_after_import", true)) source.delete()
        JSONObject()
            .put("model_uid", artifact.modelUid).put("variant_uid", artifact.variantUid)
            .put("byte_size", artifact.byteSize).put("sha256", artifact.sha256)
            .put("source_deleted", !source.exists()).put("ai_state", aiState())
    }

    private fun selectLocalAi(arguments: JSONObject): JSONObject = synchronized(actionLock) {
        val engine = arguments.optString("engine", "GGUF").trim().uppercase()
        val selected = when (engine) {
            "GGUF", "LLAMA_CPP" -> ggufSettings(arguments).also {
                require(localArtifacts.find(it.modelUid, it.variantUid) != null) { "LAB_GGUF_ARTIFACT_NOT_INSTALLED" }
            }
            "EXECUTORCH", "BIELIK_1_5B" -> LocalRecommendedSettings.forProfile(BielikLocalModelProfiles.DEFAULT_ANDROID).also {
                require(localArtifacts.find(it.modelUid, it.variantUid) != null) { "LAB_EXECUTORCH_ARTIFACT_NOT_INSTALLED" }
            }
            else -> throw IllegalArgumentException("LAB_LOCAL_ENGINE_UNSUPPORTED:$engine")
        }
        persistLocalSelection(selected, arguments)
        aiState()
    }

    private fun ggufSettings(arguments: JSONObject): LocalModelSettings {
        val recommended = LocalRecommendedSettings.forProfile(BielikLocalModelProfiles.USER_GGUF)
        fun positive(name: String, fallback: Int) = arguments.optInt(name, fallback).takeIf { it > 0 }
            ?: throw IllegalArgumentException("LAB_ARGUMENT_POSITIVE_REQUIRED:$name")
        val backend = runCatching {
            LocalRuntimeBackend.valueOf(arguments.optString("backend", LocalRuntimeBackend.GPU.name).uppercase())
        }.getOrElse { throw IllegalArgumentException("LAB_GGUF_BACKEND_INVALID") }
        require(backend in setOf(LocalRuntimeBackend.AUTO, LocalRuntimeBackend.CPU, LocalRuntimeBackend.GPU)) {
            "LAB_GGUF_BACKEND_INVALID"
        }
        return recommended.copy(
            contextUnits = positive("context_units", 4_096),
            backend = backend,
            threads = positive("threads", 4),
            prefillBatchUnits = positive("prefill_batch_units", 64),
            microBatchUnits = positive("micro_batch_units", 64),
            gpuLayers = arguments.optInt("gpu_layers", 99).takeIf { it >= 0 }
                ?: throw IllegalArgumentException("LAB_ARGUMENT_NON_NEGATIVE_REQUIRED:gpu_layers"),
            recommended = false,
            runtimeEngine = LocalRuntimeEngine.LLAMA_CPP
        )
    }

    private fun persistLocalSelection(local: LocalModelSettings, arguments: JSONObject) {
        val current = settings.load()
        val selection = AiModelSelection(local.localProviderUid(), local.modelUid)
        val pinGameMaster = arguments.optBoolean("pin_game_master", true)
        val pinDirector = arguments.optBoolean("pin_director", true)
        val configured = current.ai.copy(
            gameMaster = if (pinGameMaster) AiRoleAssignment(AiRole.GAME_MASTER, AiAssignmentKind.PINNED, selection) else current.ai.gameMaster,
            director = if (pinDirector) AiRoleAssignment(AiRole.DIRECTOR_SCENARIST, AiAssignmentKind.PINNED, selection) else current.ai.director,
            localModelSettings = local
        )
        settings.save(current.copy(ai = configured))
    }

    private fun submitPlayerAction(arguments: JSONObject): JSONObject = synchronized(actionLock) {
        val input = arguments.requiredString("text")
        val before = repository.infrastructureLastCommitOrder()
        val started = SystemClock.elapsedRealtime()
        val cancellation = MutableAiCancellationSignal().also { activeCancellation = it }
        val outcome = try { runBlocking { chat.play(input, cancellation) } } finally { activeCancellation = null }
        val payload = chatOutcomeJson(outcome)
            .put("input", input)
            .put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            .put("committed_order_before", before)
            .put("committed_order_after", repository.infrastructureLastCommitOrder())
        lastTurn = JSONObject(payload.toString()).put("available", true).put("at_epoch_ms", System.currentTimeMillis())
        if (outcome is ChatApplicationOutcome.Failed || outcome is ChatApplicationOutcome.Rejected) lastFailure = JSONObject(lastTurn.toString())
        payload
    }

    /** Executes every step through the same ChatApplicationPort as the real UI.  It cannot seed,
     * patch or otherwise write canonical tables directly. */
    private fun runActionSequence(arguments:JSONObject,command:String):JSONObject=synchronized(actionLock){
        val actions=labActionSequence(arguments)
        val expectedStart=arguments.optLong("expected_start_order",Long.MIN_VALUE)
        val before=repository.infrastructureLastCommitOrder()
        if(expectedStart!=Long.MIN_VALUE)require(before==expectedStart){"LAB_SCENARIO_START_ORDER_MISMATCH"}
        val scenarioUid=arguments.optString("scenario_uid").trim().ifBlank{"LAB-SCENARIO:${UUID.randomUUID()}"}
        val stopOnNonNarrated=arguments.optBoolean("stop_on_non_narrated",true)
        val resultDetail=arguments.optString("result_detail","FULL").trim().uppercase()
        require(resultDetail in setOf("FULL","COMPACT")){"LAB_SCENARIO_RESULT_DETAIL_INVALID"}
        val started=SystemClock.elapsedRealtime()
        val steps=JSONArray();var stoppedReason:String?=null
        actions.forEachIndexed{index,action->
            if(stoppedReason!=null)return@forEachIndexed
            val turn=submitPlayerAction(JSONObject().put("text",action))
            val outcome=turn.optString("outcome")
            val step=JSONObject().put("index",index).put("input",action)
            if(resultDetail=="FULL")step.put("turn",turn) else step
                .put("outcome",outcome)
                .put("reason_uid",turn.optString("reason_uid").ifBlank{JSONObject.NULL})
                .put("elapsed_ms",turn.optLong("elapsed_ms"))
                .put("committed_order_before",turn.optLong("committed_order_before"))
                .put("committed_order_after",turn.optLong("committed_order_after"))
                .put("semantic_fingerprint",turn.optJSONObject("receipt")?.optString("semantic_fingerprint")?.ifBlank{null}?:JSONObject.NULL)
                .put("result_fingerprint",turn.optJSONObject("receipt")?.optString("result_fingerprint")?.ifBlank{null}?:JSONObject.NULL)
            steps.put(step)
            if(stopOnNonNarrated&&outcome!="NARRATED")stoppedReason="NON_NARRATED:$outcome"
        }
        val result=JSONObject()
            .put("available",true).put("scenario_uid",scenarioUid)
            .put("scenario_kind",if(command=="RUN_COMBAT_SCENARIO")"COMBAT" else "ACTION_SEQUENCE")
            .put("result_detail",resultDetail)
            .put("requested_steps",actions.size).put("completed_steps",steps.length()).put("steps",steps)
            .put("stopped_reason_uid",stoppedReason ?: JSONObject.NULL)
            .put("elapsed_ms",SystemClock.elapsedRealtime()-started)
            .put("committed_order_before",before).put("committed_order_after",repository.infrastructureLastCommitOrder())
            .put("canonical_fingerprint",canonicalFingerprint()).put("finished_at_epoch_ms",System.currentTimeMillis())
        lastScenario=JSONObject(result.toString())
        result
    }

    private fun cancelActiveOperation(): JSONObject {
        val signal = activeCancellation
        signal?.cancel()
        return JSONObject().put("cancel_requested", signal != null)
    }

    private fun submitCharacterCreation(arguments: JSONObject): JSONObject = synchronized(actionLock) {
        val text = arguments.requiredString("text")
        val locks = arguments.optJSONArray("locked_sections")?.let { array ->
            (0 until array.length()).map { CharacterCreationDraftSection.valueOf(array.getString(it).uppercase()) }.toSet()
        } ?: emptySet()
        val started = SystemClock.elapsedRealtime()
        val outcome = characterCreation.play(text, lockedSections = locks)
        characterCreationOutcomeJson(outcome).put("input", text).put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            .also { lastCharacterCreation = JSONObject(it.toString()).put("at_epoch_ms", System.currentTimeMillis()) }
    }

    private fun confirmCharacterCreation(arguments: JSONObject): JSONObject = synchronized(actionLock) {
        val creationUid = arguments.optString("creation_uid").ifBlank {
            characterCreation.pendingDraft()?.creationUid ?: throw IllegalArgumentException("LAB_CHARACTER_DRAFT_REQUIRED")
        }
        val actionUid = arguments.optString("explicit_user_action_uid").ifBlank { "LAB-CONFIRM:${UUID.randomUUID()}" }
        characterCreationOutcomeJson(characterCreation.confirm(creationUid, actionUid))
    }

    private fun pendingCharacterDraft(): JSONObject {
        val draft = characterCreation.pendingDraft()
        return JSONObject().put("available", draft != null).put("draft", draft?.let(::draftJson) ?: JSONObject.NULL)
            .put("last_character_creation", lastCharacterCreation ?: JSONObject.NULL)
    }
}

internal fun labActionSequence(arguments:JSONObject):List<String>{
    val array=arguments.optJSONArray("actions")?:throw IllegalArgumentException("LAB_ARGUMENT_REQUIRED:actions")
    require(array.length() in 1..100){"LAB_SCENARIO_ACTION_COUNT_OUT_OF_RANGE"}
    return (0 until array.length()).map{index->
        array.optString(index).trim().takeIf(String::isNotEmpty)
            ?:throw IllegalArgumentException("LAB_SCENARIO_ACTION_BLANK:$index")
    }
}

private fun recoveryRequestJson(value:ChatTurnRequest)=JSONObject()
    .put("request_uid",value.requestUid).put("campaign_uid",value.campaignUid).put("turn_uid",value.turnUid)
    .put("command_uid",value.commandUid).put("transaction_uid",value.transactionUid)
    .put("input",value.input).put("locale_uid",value.localeUid).put("at_order",value.atOrder ?: JSONObject.NULL)

private fun narrativeRecoveryJson(value:NarrativeRecoveryResult):JSONObject=when(value){
    is NarrativeRecoveryResult.Recovered->JSONObject().put("outcome","RECOVERED").put("rebuilt",value.rebuilt)
        .put("delivery_uid",value.delivery.deliveryUid)
        .put("transaction_uid",value.delivery.identity.transactionUid)
        .put("committed_order",value.delivery.identity.committedOrder)
        .put("provider_uid",value.delivery.providerUid).put("model_uid",value.delivery.modelUid)
        .put("narrative",value.delivery.narrative.text)
        .put("narrative_fingerprint",value.delivery.narrativeFingerprint)
    is NarrativeRecoveryResult.Unavailable->JSONObject().put("outcome","UNAVAILABLE").put("reason_uid",value.reasonUid)
}

private fun response(requestUid: String, state: String, payload: JSONObject = JSONObject(), reasonUid: String? = null) = JSONObject()
    .put("protocol", LAB_PROTOCOL).put("request_uid", requestUid).put("state", state).put("payload", payload)
    .put("reason_uid", reasonUid ?: JSONObject.NULL)

private fun typedReason(prefix: String, failure: Throwable): String {
    val detail = (failure.message ?: failure::class.java.simpleName).replace(Regex("[^A-Za-z0-9:_-]"), "_").take(180)
    return "$prefix:$detail"
}

private fun JSONObject.requiredString(key: String): String = optString(key).trim().takeIf { it.isNotEmpty() }
    ?: throw IllegalArgumentException("LAB_ARGUMENT_REQUIRED:$key")

private fun campaignJson(value: ActiveCampaignRef) = JSONObject().put("directory", value.directoryName).put("campaign_uid", value.campaignId)
private fun playerJson(value: ActivePlayerRef) = JSONObject().put("campaign_uid", value.campaignId).put("player_uid", value.playerUid)
private fun statusJson(value: StatusSnapshot) = JSONObject().put("name", value.name).put("level", value.level).put("age", value.age)
    .put("rank", value.rank).put("chakra", value.chakra).put("location", value.location)
private fun statLineJson(value: StatLine) = JSONObject().put("key", value.key).put("value", value.value)
private fun playerStatJson(value:PlayerStat)=JSONObject().put("campaign_uid",value.campaignId).put("character_uid",value.characterUid)
    .put("stat_uid",value.statUid).put("base_value",value.baseValue).put("version",value.version)
private fun playerResourceJson(value:PlayerResource)=JSONObject().put("campaign_uid",value.campaignId).put("character_uid",value.characterUid)
    .put("resource_uid",value.resourceUid).put("current_value",value.currentValue).put("version",value.version)
private fun characterPanelV2Json(value:CharacterPanelSnapshotV2)=JSONObject()
    .put("campaign_uid",value.campaignUid).put("character_uid",value.characterUid).put("classification",value.classification.name)
    .put("fingerprint",value.fingerprint)
    .put("identity",JSONArray(value.identity.map{JSONObject().put("key_uid",it.keyUid).put("value",it.value)}))
    .put("stats",JSONArray(value.stats.map{JSONObject().put("target_uid",it.targetUid).put("exact_value",it.exactValue).put("semantics_uid",it.semanticsUid)}))
    .put("resources",JSONArray(value.resources.map{JSONObject().put("target_uid",it.targetUid).put("exact_value",it.exactValue).put("semantics_uid",it.semanticsUid)}))
    .put("skills",JSONArray(value.skills.map{JSONObject().put("target_uid",it.targetUid).put("exact_progress",it.exactProgress).put("display_name",it.displayName?:JSONObject.NULL)}))
    .put("techniques",JSONArray(value.techniques.map{JSONObject().put("target_uid",it.targetUid).put("exact_progress",it.exactProgress).put("display_name",it.displayName?:JSONObject.NULL)}))
    .put("talent",JSONArray(value.talent.map{JSONObject().put("domain_uid",it.domainUid).put("dimension_uid",it.dimensionUid?:JSONObject.NULL).put("canonical_value",it.canonicalValue)}))
    .put("potential",JSONArray(value.potential.map{JSONObject().put("domain_uid",it.domainUid).put("dimension_uid",it.dimensionUid?:JSONObject.NULL).put("canonical_value",it.canonicalValue)}))
    .put("innate_and_evolution",JSONArray(value.innateAndEvolution.map{JSONObject().put("innate_uid",it.innateUid).put("state_uid",it.stateUid).put("canonical_value",it.canonicalValue?:JSONObject.NULL)}))
    .put("inventory",JSONArray(value.inventory.map{JSONObject().put("item_instance_uid",it.itemInstanceUid).put("definition_uid",it.definitionUid?:JSONObject.NULL).put("display_name",it.displayName?:JSONObject.NULL).put("quantity",it.quantity)}))
    .put("equipment",JSONArray(value.equipment.map{JSONObject().put("slot_uid",it.slotUid).put("item_instance_uid",it.itemInstanceUid?:JSONObject.NULL)}))
    .put("ownership_and_assets",JSONArray(value.ownershipAndAssets.map{JSONObject().put("asset_kind_uid",it.assetKindUid).put("asset_uid",it.assetUid).put("owner_uid",it.ownerUid)}))
    .put("economy",JSONArray(value.economy.map{JSONObject().put("currency_uid",it.currencyUid).put("exact_balance",it.exactBalance).put("authority_record_uid",it.authorityRecordUid)}))
    .put("progression",JSONArray(value.progression.map{JSONObject().put("target_kind_uid",it.targetKindUid).put("target_uid",it.targetUid).put("exact_value",it.exactValue).put("provenance_status_uid",it.provenanceStatusUid?:JSONObject.NULL)}))
    .put("projects",JSONArray(value.projects.map{JSONObject().put("project_uid",it.projectUid).put("lifecycle_uid",it.lifecycleUid).put("exact_progress",it.exactProgress)}))
    .put("relationships",JSONArray(value.relationships.map{JSONObject().put("other_entity_uid",it.otherEntityUid).put("relationship_type_uid",it.relationshipTypeUid).put("exact_score",it.exactScore)}))
    .put("goals",JSONArray(value.goals.map{JSONObject().put("goal_uid",it.goalUid).put("title",it.title).put("priority",it.priority)}))
private fun receiptJson(value: TurnCommitReceipt) = JSONObject()
    .put("campaign_uid", value.campaignUid).put("turn_uid", value.turnUid).put("command_uid", value.commandUid)
    .put("transaction_uid", value.transactionUid).put("committed_order", value.commitOrder ?: JSONObject.NULL)
    .put("semantic_fingerprint", value.semanticFingerprint).put("result_fingerprint", value.resultFingerprint)
    .put("required_event_count", value.requiredEventCount ?: JSONObject.NULL)
    .put("required_event_manifest_fingerprint", value.requiredEventManifestFingerprint ?: JSONObject.NULL)
    .put("receipt_version", value.receiptVersion)

private fun assignmentJson(value: AiRoleAssignment) = JSONObject().put("role", value.role.name).put("kind", value.kind.name)
    .put("provider_uid", value.pinned?.providerUid ?: JSONObject.NULL).put("model_uid", value.pinned?.modelUid ?: JSONObject.NULL)

private fun structuredRetrievalJson(value:StructuredRetrievalResult):JSONObject{
    val root=JSONObject().put("state",value.state.name)
    return when(value){
        is StructuredRetrievalResult.Value->root.put("complete",value.complete).put("continuation",value.continuation.name)
            .put("next_cursor",value.nextCursor ?: JSONObject.NULL).put("records",JSONArray(value.records.map{record->
                JSONObject().put("record_uid",record.recordUid).put("values",JSONObject(record.values))
                    .put("provenance_uid",record.provenanceUid ?: JSONObject.NULL)
            }))
        is StructuredRetrievalResult.Denied->root.put("reason_uid",value.reasonUid)
        is StructuredRetrievalResult.NotDisclosed->root.put("reason_uid",value.reasonUid)
        is StructuredRetrievalResult.Unknown->root.put("reason_uid",value.reasonUid)
        is StructuredRetrievalResult.Unsupported->root.put("reason_uid",value.reasonUid)
        is StructuredRetrievalResult.Corruption->root.put("reason_uid",value.reasonUid)
        StructuredRetrievalResult.NoData->root.put("records",JSONArray())
    }
}

private fun chatOutcomeJson(value: ChatApplicationOutcome): JSONObject = when (value) {
    is ChatApplicationOutcome.Narrated -> JSONObject().put("outcome", "NARRATED")
        .put("narrative", value.result.narrative.text).put("stop_reason_uid", value.result.narrative.stopReasonUid)
        .put("plan_uid", value.result.planUid).put("proposal_uid", value.result.proposalUid)
        .put("repair_attempts", value.result.repairAttempts).put("deterministic_fallback", value.result.deterministicFallback)
        .put("provider_uid", value.result.delivery.providerUid).put("model_uid", value.result.delivery.modelUid)
        .put("receipt", receiptJson(value.result.receipt))
    is ChatApplicationOutcome.CommittedNarrationPending -> JSONObject().put("outcome", "COMMITTED_NARRATION_PENDING")
        .put("reason_uid", value.result.reasonUid).put("plan_uid", value.result.planUid).put("proposal_uid", value.result.proposalUid)
        .put("receipt", receiptJson(value.result.receipt))
    is ChatApplicationOutcome.Clarification -> JSONObject().put("outcome", "CLARIFICATION").put("reason_uids", JSONArray(value.reasonUids))
    is ChatApplicationOutcome.Rejected -> JSONObject().put("outcome", "REJECTED").put("stage", value.stage.name).put("reason_uids", JSONArray(value.reasonUids))
    is ChatApplicationOutcome.Failed -> JSONObject().put("outcome", "FAILED").put("stage", value.stage.name)
        .put("reason_uid", value.reasonUid).put("mutation_state", value.mutationState.name)
    is ChatApplicationOutcome.Cancelled -> JSONObject().put("outcome", "CANCELLED").put("stage", value.stage.name).put("mutation_state", value.mutationState.name)
    is ChatApplicationOutcome.NonAuthoritativeNarration -> JSONObject().put("outcome", "NON_AUTHORITATIVE_NARRATION")
        .put("narrative", value.text).put("reason_uid", value.reasonUid)
}

private fun characterCreationOutcomeJson(value: CharacterCreationApplicationOutcome): JSONObject = when (value) {
    is CharacterCreationApplicationOutcome.Question -> JSONObject().put("outcome", "QUESTION").put("text", value.text)
    is CharacterCreationApplicationOutcome.AwaitingExplicitConfirmation -> JSONObject().put("outcome", "AWAITING_EXPLICIT_CONFIRMATION")
        .put("creation_uid", value.creationUid).put("summary", value.summary).put("draft_fingerprint", value.draftFingerprint)
    is CharacterCreationApplicationOutcome.Created -> JSONObject().put("outcome", "CREATED")
        .put("creation_uid", value.receipt.creationUid).put("campaign_uid", value.receipt.campaignUid)
        .put("player_uid", value.receipt.playerUid).put("draft_fingerprint", value.receipt.draftFingerprint)
        .put("idempotent_replay", value.receipt.idempotentReplay)
    is CharacterCreationApplicationOutcome.Failed -> JSONObject().put("outcome", "FAILED").put("reason_uid", value.reasonUid)
    is CharacterCreationApplicationOutcome.Cancelled -> JSONObject().put("outcome", "CANCELLED").put("reason_uid", value.reasonUid)
}

private fun draftJson(value: PlayerCharacterCreationDraft) = JSONObject()
    .put("creation_uid", value.creationUid).put("campaign_uid", value.campaignUid).put("player_uid", value.playerUid)
    .put("display_name", value.displayName).put("gender_uid", value.genderUid).put("identity_choices", JSONObject(value.identityChoices))
    .put("stats", choicesJson(value.stats)).put("resources", choicesJson(value.resources)).put("talents", choicesJson(value.talents))
    .put("potentials", choicesJson(value.potentials)).put("skills", choicesJson(value.skills)).put("techniques", choicesJson(value.techniques))
    .put("origin_uids", JSONArray(value.originUids)).put("innate_feature_uids", JSONArray(value.innateFeatureUids))
    .put("starting_location_uid", value.startingLocationUid).put("starting_x_millimetres", value.startingXMillimetres)
    .put("starting_y_millimetres", value.startingYMillimetres)

private fun choicesJson(values: List<CharacterCreationValueChoice>) = JSONArray(values.map { choice ->
    JSONObject().put("definition_uid", choice.definitionUid).put("value", choice.value)
        .put("dimension_uid", choice.dimensionUid ?: JSONObject.NULL)
})

private class RpgOsLabAiTraceStore : AiWireTracePort {
    private val lock = Any()
    private val events = ArrayDeque<AiWireTraceEvent>()

    override fun record(event: AiWireTraceEvent) = synchronized(lock) {
        events.addLast(event.copy(payload = event.payload?.take(750_000)))
        while (events.size > 500) events.removeFirst()
    }

    fun read(limit: Int, requestUidPrefix: String?): JSONObject = synchronized(lock) {
        val selected = events.asSequence()
            .filter { requestUidPrefix == null || it.requestUid.startsWith(requestUidPrefix) }
            .toList().takeLast(limit).map(::eventJson)
        JSONObject().put("event_count", selected.size).put("events", JSONArray(selected))
    }

    fun latestExchange(workloadName:String?):JSONObject=synchronized(lock){
        val workload=workloadName?.let{name->
            runCatching{AiWorkload.valueOf(name.trim().uppercase())}
                .getOrElse{throw IllegalArgumentException("LAB_AI_WORKLOAD_INVALID:$name")}
        }
        val reversed=events.toList().asReversed()
        val response=reversed.firstOrNull{event->
            event.direction=="RESPONSE"&&(workload==null||event.workload==workload)
        }?:return@synchronized JSONObject().put("available",false)
        val request=reversed.firstOrNull{event->
            event.direction=="REQUEST"&&event.requestUid==response.requestUid&&event.workload==response.workload
        }
        JSONObject().put("available",true).put("workload",response.workload.name)
            .put("request_uid",response.requestUid)
            .put("request",request?.let(::eventJson) ?: JSONObject.NULL)
            .put("response",eventJson(response))
    }

    fun clear(): JSONObject = synchronized(lock) {
        val removed = events.size
        events.clear()
        JSONObject().put("removed_events", removed)
    }

    private fun eventJson(event: AiWireTraceEvent) = JSONObject()
        .put("direction", event.direction).put("request_uid", event.requestUid).put("workload", event.workload.name)
        .put("provider_uid", event.providerUid).put("model_uid", event.modelUid)
        .put("payload", event.payload ?: JSONObject.NULL).put("trace_uid", event.traceUid ?: JSONObject.NULL)
        .put("input_units", event.inputUnits ?: JSONObject.NULL).put("output_units", event.outputUnits ?: JSONObject.NULL)
        .put("failure_kind", event.failureKind?.name ?: JSONObject.NULL).put("reason_uid", event.reasonUid ?: JSONObject.NULL)
        .put("at_epoch_ms", event.atEpochMillis)
}
