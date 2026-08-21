package com.rpgos.app

import android.content.Context
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
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()

    suspend fun prepareSource(sourceVisualUid:String,sourceUri:String):PreparedImageEditSource = withContext(Dispatchers.IO){
        require(sourceVisualUid.isNotBlank())
        val uri=android.net.Uri.parse(sourceUri)
        val bytes=context.contentResolver.openInputStream(uri).use{input->requireNotNull(input){"Nie można odczytać obrazu źródłowego."};input.readBytes()}
        PreparedImageEditSource(sourceVisualUid,bytes,Phase38VisualAuthorization.digestBytes(bytes))
    }

    suspend fun edit(reqData:ImageEditRequest):GeneratedImageResult = editPrepared(reqData,prepareSource(reqData.sourceVisualUid,reqData.sourceUri))

    suspend fun editPrepared(reqData:ImageEditRequest,prepared:PreparedImageEditSource):GeneratedImageResult = withContext(Dispatchers.IO){
        require(baseUrl.isNotBlank()&&!baseUrl.contains("YOUR-BACKEND")){"Backend nie jest skonfigurowany."}
        require(prepared.sourceVisualUid==reqData.sourceVisualUid){"RPGOS-VISIBILITY:VISUAL_SOURCE_SUBSTITUTION"}
        val sourceDigest=Phase38VisualAuthorization.digestBytes(prepared.bytes)
        require(sourceDigest==prepared.sha256){"RPGOS-VISIBILITY:VISUAL_SOURCE_DIGEST_MISMATCH"}
        reqData.authorization.requireRequest(VisualSemanticRequest(
            reqData.authorization.campaignUid,reqData.authorization.audienceKindUid,reqData.authorization.audienceUid,
            VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION,reqData.authorization.subjectKindUid,reqData.authorization.subjectUid,
            reqData.authorization.requestUid,VisualRequestKinds.EDIT,reqData.instruction,
            relatedEntityUid=reqData.authorization.relatedEntityUid,sourceVisualUid=reqData.sourceVisualUid,sourceImageSha256=sourceDigest
        ))
        val multipart=MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("title",reqData.title).addFormDataPart("instruction",reqData.instruction)
            .addFormDataPart("campaign_uid",reqData.authorization.campaignUid).addFormDataPart("source_visual_uid",reqData.sourceVisualUid)
            .addFormDataPart("visibility_envelope",reqData.authorization.toJson().toString())
            .addFormDataPart("image","source.png",prepared.bytes.toRequestBody("image/png".toMediaType())).build()
        val request=Request.Builder().url(baseUrl.trimEnd('/')+"/v1/images/edit").post(multipart).build()
        client.newCall(request).execute().use{resp->
            if(!resp.isSuccessful)error("Image edit backend HTTP ${resp.code}")
            val body=JSONObject(resp.body.string())
            GeneratedImageResult(body.getString("title"),body.optString("mime_type","image/png"),body.getString("base64_data"),body.optString("revised_prompt").takeIf{it.isNotBlank()})
        }
    }
}
