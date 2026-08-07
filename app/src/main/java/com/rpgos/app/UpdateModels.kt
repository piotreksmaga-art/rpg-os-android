package com.rpgos.app

data class OnlineUpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val sha256: String,
    val notes: String,
    val mandatory: Boolean = false
)

data class ApkValidationResult(
    val ok: Boolean,
    val message: String,
    val versionCode: Long? = null,
    val versionName: String? = null
)
