package com.rpgos.app

data class ImageEditRequest(
    val sourceVisualUid: String,
    val sourceUri: String,
    val title: String,
    val instruction: String,
    val authorization: Phase38VisualAuthorization
)
