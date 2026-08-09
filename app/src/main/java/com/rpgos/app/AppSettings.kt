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

        val activeCampaignId = CampaignSelectionManager(context).activeCampaignId()

        return RpgOsSettings(
            backendUrl = migratedBackend,
            updateFeedUrl =
                prefs.getString("update_feed_url", BuildConfig.RPGOS_UPDATE_FEED_URL)
                    ?: BuildConfig.RPGOS_UPDATE_FEED_URL,
            worldPackId = prefs.getString("worldpack_id", "naruto") ?: "naruto",
            campaignId = activeCampaignId,
            showGmDiagnostics = prefs.getBoolean("show_gm_diagnostics", true),
            autoBackup = prefs.getBoolean("auto_backup", true)
        )
    }

    fun save(settings: RpgOsSettings) {
        prefs.edit()
            .putString("backend_url", settings.backendUrl)
            .putString("update_feed_url", settings.updateFeedUrl)
            .putString("worldpack_id", settings.worldPackId)
            // Kept only as a compatibility mirror for older builds. Runtime identity
            // is resolved exclusively through CampaignSelectionManager/ActiveCampaignRef.
            .putString("campaign_id", CampaignSelectionManager(context).activeCampaignId())
            .putBoolean("show_gm_diagnostics", settings.showGmDiagnostics)
            .putBoolean("auto_backup", settings.autoBackup)
            .apply()
    }
}
