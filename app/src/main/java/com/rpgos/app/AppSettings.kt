package com.rpgos.app

import android.content.Context

data class RpgOsSettings(
    val backendUrl: String,
    val worldPackId: String,
    val campaignId: String,
    val showGmDiagnostics: Boolean,
    val autoBackup: Boolean
)

class AppSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("rpgos_settings", Context.MODE_PRIVATE)

    fun load(): RpgOsSettings = RpgOsSettings(
        backendUrl = prefs.getString("backend_url", BuildConfig.RPGOS_BACKEND_URL) ?: BuildConfig.RPGOS_BACKEND_URL,
        worldPackId = prefs.getString("worldpack_id", "naruto") ?: "naruto",
        campaignId = prefs.getString("campaign_id", "naruto-default") ?: "naruto-default",
        showGmDiagnostics = prefs.getBoolean("show_gm_diagnostics", true),
        autoBackup = prefs.getBoolean("auto_backup", true)
    )

    fun save(settings: RpgOsSettings) {
        prefs.edit()
            .putString("backend_url", settings.backendUrl)
            .putString("worldpack_id", settings.worldPackId)
            .putString("campaign_id", settings.campaignId)
            .putBoolean("show_gm_diagnostics", settings.showGmDiagnostics)
            .putBoolean("auto_backup", settings.autoBackup)
            .apply()
    }
}
