package com.rpgos.app

data class ImageGenerationRequest(
    val kind: String,
    val title: String,
    val prompt: String,
    val relatedEntityUid: String? = null,
    val chapter: Int? = null,
    val authorization: Phase38VisualAuthorization
)

data class GeneratedImageResult(
    val title: String,
    val mimeType: String,
    val base64Data: String,
    val revisedPrompt: String? = null
)

data class GalleryImageItem(
    val title: String,
    val uri: String,
    val createdAt: Long,
    val kind: String,
    val relatedEntityUid: String?
)
