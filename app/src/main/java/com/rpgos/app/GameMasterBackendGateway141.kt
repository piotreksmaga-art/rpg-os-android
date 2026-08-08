package com.rpgos.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * GM 141 backend adapter.
 *
 * This endpoint returns an untrusted proposal, never a StatePatch. The Android
 * rule resolver and validator remain authoritative for mechanics and durable
 * campaign state.
 */
class GameMasterBackendGateway141(
    private val baseUrl: String = BuildConfig.RPGOS_BACKEND_URL
) : GameMasterModelGateway {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    override suspend fun generateProposal(
        request: GameMasterTurnRequest,
        context: GameMasterContext
    ): GameMasterProposal = withContext(Dispatchers.IO) {
        require(baseUrl.isNotBlank() && !baseUrl.contains("YOUR-BACKEND")) {
            "Backend GM141 nie jest skonfigurowany."
        }

        val payload = JSONObject().apply {
            put("protocol", "rpg-os-gm141-proposal-v1")
            put("campaign_id", request.campaignId)
            put("worldpack_id", request.worldPackId)
            put("chapter", request.currentChapter)
            put("locale", request.locale)
            put("player_action", request.playerAction)
            put("context", contextJson(context))
            put("response_contract", responseContract())
        }

        val httpRequest = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/v1/gm/proposal")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) error("Backend GM141 HTTP ${response.code}")
            val raw = response.body.string()
            parseProposal(JSONObject(raw), request.currentChapter)
        }
    }

    private fun contextJson(context: GameMasterContext): JSONObject = JSONObject().apply {
        put("campaign_id", context.campaignId)
        put("chapter", context.chapter)
        put("scene", sectionJson(context.scene))
        put("player_state", sectionJson(context.playerState))
        put("active_world_state", sectionJson(context.activeWorldState))
        put("active_threads", sectionJson(context.activeThreads))
        put("relevant_memories", sectionJson(context.relevantMemories))
        put("canon_knowledge", sectionJson(context.canonKnowledge))
        put("rules", sectionJson(context.rules))
        put("recent_narrative", sectionJson(context.recentNarrative))
        put("provenance", JSONArray(context.provenance.map { source ->
            JSONObject().apply {
                put("source_type", source.sourceType)
                put("source_id", source.sourceId)
                put("reason", source.reason)
                put("confidence", source.confidence)
            }
        }))
    }

    private fun sectionJson(section: ContextSection): JSONObject = JSONObject().apply {
        put("title", section.title)
        put("content", section.content)
        put("priority", section.priority)
        put("characters", section.estimatedCharacters)
    }

    private fun responseContract(): JSONObject = JSONObject().apply {
        put("narrative_draft", "required string")
        put("proposed_actions", JSONArray().put(JSONObject().apply {
            put("action_type", "semantic action, e.g. WORLD_EVENT, STATE_INCREMENT, ASSERT_BELIEF")
            put("actor_id", "optional UID")
            put("target_id", "optional UID")
            put("parameters", "JSON object; mechanics are resolved on device")
            put("reason", "why this action follows from the scene")
        }))
        put("proposed_memories", "optional array")
        put("proposed_chronicle_entries", "optional array")
        put("rule", "Do not return SQL, table names, StatePatch or final trusted database mutations.")
    }

    private fun parseProposal(root: JSONObject, currentChapter: Long): GameMasterProposal {
        val actions = mutableListOf<ProposedWorldAction>()
        val actionArray = root.optJSONArray("proposed_actions") ?: JSONArray()
        for (i in 0 until actionArray.length()) {
            val item = actionArray.getJSONObject(i)
            actions += ProposedWorldAction(
                actionType = item.getString("action_type"),
                actorId = item.optNullableString("actor_id"),
                targetId = item.optNullableString("target_id"),
                parametersJson = when (val parameters = item.opt("parameters")) {
                    is JSONObject -> parameters.toString()
                    is String -> parameters.takeIf { it.isNotBlank() } ?: "{}"
                    null, JSONObject.NULL -> "{}"
                    else -> error("proposed_actions[$i].parameters musi być obiektem JSON.")
                },
                reason = item.optString("reason", "")
            )
        }

        val memories = mutableListOf<MemoryWrite>()
        val memoryArray = root.optJSONArray("proposed_memories") ?: JSONArray()
        for (i in 0 until memoryArray.length()) {
            val item = memoryArray.getJSONObject(i)
            memories += MemoryWrite(
                memoryType = enumValue(item.optString("memory_type", "FACT"), MemoryType.FACT),
                subjectId = item.optNullableString("subject_id"),
                text = item.getString("text"),
                importance = item.optDouble("importance", 0.5),
                chapter = item.optLong("chapter", currentChapter),
                tags = item.optJSONArray("tags").stringSet()
            )
        }

        val chronicle = mutableListOf<ChronicleWrite>()
        val chronicleArray = root.optJSONArray("proposed_chronicle_entries") ?: JSONArray()
        for (i in 0 until chronicleArray.length()) {
            val item = chronicleArray.getJSONObject(i)
            chronicle += ChronicleWrite(
                chapter = item.optLong("chapter", currentChapter),
                title = item.getString("title"),
                summary = item.getString("summary"),
                participants = item.optJSONArray("participants").stringSet(),
                locationIds = item.optJSONArray("location_ids").stringSet()
            )
        }

        val diagnosticJson = root.optJSONObject("diagnostics")
        val diagnostics = GameMasterDiagnostics(
            contextCharacters = diagnosticJson?.optInt("context_characters", 0) ?: 0,
            retrievedMemoryCount = diagnosticJson?.optInt("retrieved_memory_count", 0) ?: 0,
            retrievedCanonCount = diagnosticJson?.optInt("retrieved_canon_count", 0) ?: 0,
            retrievedNpcCount = diagnosticJson?.optInt("retrieved_npc_count", 0) ?: 0,
            retrievedThreadCount = diagnosticJson?.optInt("retrieved_thread_count", 0) ?: 0,
            warnings = diagnosticJson?.optJSONArray("warnings").stringSet().toList()
        )

        return GameMasterProposal(
            narrativeDraft = root.getString("narrative_draft"),
            proposedActions = actions,
            proposedMemories = memories,
            proposedChronicleEntries = chronicle,
            diagnostics = diagnostics
        )
    }

    private inline fun <reified T : Enum<T>> enumValue(raw: String, fallback: T): T =
        runCatching { enumValueOf<T>(raw.trim().uppercase(Locale.ROOT)) }.getOrDefault(fallback)

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }

    private fun JSONArray?.stringSet(): Set<String> {
        if (this == null) return emptySet()
        val out = linkedSetOf<String>()
        for (i in 0 until length()) optString(i).trim().takeIf { it.isNotEmpty() }?.let(out::add)
        return out
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
