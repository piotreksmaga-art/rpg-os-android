package com.rpgos.app

data class ImageEditRequest(
    val sourceVisualUid: String,
    val sourceUri: String,
    val title: String,
    val instruction: String
)
