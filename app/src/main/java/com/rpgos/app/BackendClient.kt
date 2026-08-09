package com.rpgos.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class BackendClient(
    private val baseUrl: String = BuildConfig.RPGOS_BACKEND_URL
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun sendTurn(
        playerInput: String,
        chapter: Int,
        context: ContextBundle
    ): BackendTurnResult = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || baseUrl.contains("YOUR-BACKEND")) {
            return@withContext BackendTurnResult(
                narration = "Backend nie jest skonfigurowany. Ustaw RPGOS_BACKEND_URL w build.gradle.",
                patch = null
            )
        }

        val payload = JSONObject().apply {
            put("campaign_id", "naruto-default")
            put("chapter", chapter)
            put("player_input", playerInput)
            put("context_bundle", JsonCodec.contextToJson(context))
        }

        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/v1/gm/turn")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Backend HTTP ${response.code}")
            val body = response.body.string()
            val json = JSONObject(body)
            val narration = json.getString("narration")
            val choicesJson = json.optJSONArray("choices")
            val choices = buildList {
                if (choicesJson != null) {
                    for (index in 0 until choicesJson.length()) {
                        choicesJson.optString(index)
                            .takeIf { it.isNotBlank() }
                            ?.let(::add)
                    }
                }
            }.take(3)
            val chapterEventsJson = json.optJSONArray("chapter_events")
            val chapterEvents = buildList {
                if (chapterEventsJson != null) {
                    for (index in 0 until chapterEventsJson.length()) {
                        chapterEventsJson.optJSONObject(index)?.toString()?.let(::add)
                    }
                }
            }
            val patch = json.optJSONObject("state_patch")?.let(JsonCodec::parseStatePatch)
            BackendTurnResult(
                narration = narration,
                patch = patch,
                choices = choices,
                chapterEvents = chapterEvents
            )
        }
    }
}

data class BackendTurnResult(
    val narration: String,
    val patch: StatePatch?,
    val choices: List<String> = emptyList(),
    val chapterEvents: List<String> = emptyList()
)
