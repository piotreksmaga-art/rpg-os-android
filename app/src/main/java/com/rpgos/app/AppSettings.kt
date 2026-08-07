package com.rpgos.app

import android.content.Context

data class RpgOsSettings(
    val backendUrl: String,
    val updateFeedUrl: String,
    val worldPackId: String,
    val campaignId: String,
    val showGmDiagnostics: Boolean,
    val autoBackup: Boolean
)

class AppSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("rpgos_settings", Context.MODE_PRIVATE)

    fun load(): RpgOsSettings {
        val storedBackend =
            prefs.getString("backend_url", BuildConfig.RPGOS_BACKEND_URL)
                ?: BuildConfig.RPGOS_BACKEND_URL

        // alpha1 temporarily used the backend field as the GitHub release URL.
        // From alpha3 these are separate settings.
        val migratedBackend =
            if (storedBackend.contains(
                    "api.github.com/repos/piotreksmaga-art/rpg-os-android/releases",
                    ignoreCase = true
                )
            ) {
                BuildConfig.RPGOS_BACKEND_URL
            } else storedBackend

        return RpgOsSettings(
            backendUrl = migratedBackend,
            updateFeedUrl =
                prefs.getString("update_feed_url", BuildConfig.RPGOS_UPDATE_FEED_URL)
                    ?: BuildConfig.RPGOS_UPDATE_FEED_URL,
            worldPackId = prefs.getString("worldpack_id", "naruto") ?: "naruto",
            campaignId = prefs.getString("campaign_id", "naruto-default") ?: "naruto-default",
            showGmDiagnostics = prefs.getBoolean("show_gm_diagnostics", true),
            autoBackup = prefs.getBoolean("auto_backup", true)
        )
    }

    fun save(settings: RpgOsSettings) {
        prefs.edit()
            .putString("backend_url", settings.backendUrl)
            .putString("update_feed_url", settings.updateFeedUrl)
            .putString("worldpack_id", settings.worldPackId)
            .putString("campaign_id", settings.campaignId)
            .putBoolean("show_gm_diagnostics", settings.showGmDiagnostics)
            .putBoolean("auto_backup", settings.autoBackup)
            .apply()
    }
}
