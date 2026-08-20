package com.rpgos.app

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ImageEditBackendClient(
    private val context: Context,
    private val baseUrl: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    suspend fun edit(reqData: ImageEditRequest): GeneratedImageResult =
        withContext(Dispatchers.IO) {
            require(baseUrl.isNotBlank() && !baseUrl.contains("YOUR-BACKEND")) {
                "Backend nie jest skonfigurowany."
            }

            reqData.authorization.requireRequest(
                reqData.authorization.campaignUid,
                VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION,
                reqData.instruction
            )
            val uri = android.net.Uri.parse(reqData.sourceUri)
            val bytes = context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Nie można odczytać obrazu źródłowego." }
                input.readBytes()
            }

            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("title", reqData.title)
                .addFormDataPart("instruction", reqData.instruction)
                .addFormDataPart("campaign_uid", reqData.authorization.campaignUid)
                .addFormDataPart("visibility_envelope", reqData.authorization.toJson().toString())
                .addFormDataPart(
                    "image",
                    "source.png",
                    bytes.toRequestBody("image/png".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/v1/images/edit")
                .post(multipart)
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("Image edit backend HTTP ${resp.code}")
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
