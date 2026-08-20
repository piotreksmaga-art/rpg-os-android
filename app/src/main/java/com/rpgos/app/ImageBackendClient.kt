package com.rpgos.app

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ImageBackendClient(
    private val baseUrl: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    suspend fun generate(requestData: ImageGenerationRequest): GeneratedImageResult =
        withContext(Dispatchers.IO) {
            require(baseUrl.isNotBlank() && !baseUrl.contains("YOUR-BACKEND")) {
                "Backend nie jest skonfigurowany."
            }

            val expectedPurpose = when(requestData.kind) {
                "scene" -> VisibilityPurposeKinds.SCENE_VISUALIZATION
                "character" -> VisibilityPurposeKinds.CHARACTER_VISUALIZATION
                "location" -> VisibilityPurposeKinds.LOCATION_VISUALIZATION
                else -> error("RPGOS-VISIBILITY:UNSUPPORTED_VISUAL_KIND")
            }
            requestData.authorization.requireRequest(requestData.authorization.campaignUid, expectedPurpose, requestData.prompt)
            val json = JSONObject().apply {
                put("kind", requestData.kind)
                put("title", requestData.title)
                put("prompt", requestData.prompt)
                put("related_entity_uid", requestData.relatedEntityUid)
                put("chapter", requestData.chapter)
                put("campaign_uid", requestData.authorization.campaignUid)
                put("visibility_envelope", requestData.authorization.toJson())
            }

            val req = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/v1/images/generate")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Image backend HTTP ${resp.code}")
                val body = JSONObject(resp.body.string())
                GeneratedImageResult(
                    title = body.getString("title"),
                    mimeType = body.optString("mime_type", "image/png"),
                    base64Data = body.getString("base64_data"),
                    revisedPrompt = body.optString("revised_prompt").takeIf { it.isNotBlank() }
                )
            }
        }
}
