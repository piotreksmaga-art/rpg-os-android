package com.rpgos.app

@ConsistentCopyVisibility
data class PreparedImageEditSource internal constructor(
    val sourceVisualUid:String,
    val bytes:ByteArray,
    val sha256:String
)

data class ImageEditRequest(
    val sourceVisualUid: String,
    val sourceUri: String,
    val title: String,
    val instruction: String,
    val authorization: Phase38VisualAuthorization
)
