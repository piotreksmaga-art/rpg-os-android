package com.rpgos.app

import java.io.File

/**
 * Canonical runtime identity of the currently selected campaign.
 *
 * directoryName identifies the physical campaign package on the device.
 * campaignId is the stable logical identity sent to backend/domain layers.
 */
data class ActiveCampaignRef(
    val directoryName: String,
    val campaignId: String
) {
    companion object {
        const val DEFAULT_DIRECTORY = "Naruto_Default.campaign"
        const val DEFAULT_CAMPAIGN_ID = "naruto-default"

        fun resolve(savesDir: File, directoryName: String): ActiveCampaignRef {
            require(directoryName.isNotBlank()) { "Nazwa katalogu kampanii jest pusta." }
            require(File(directoryName).name == directoryName) { "Nieprawidłowa nazwa katalogu kampanii." }

            val campaignDir = File(savesDir, directoryName)
            val manifestId = readManifestId(File(campaignDir, "campaign.json"))
            val id = manifestId ?: fallbackCampaignId(directoryName)
            return ActiveCampaignRef(directoryName = directoryName, campaignId = id)
        }

        fun fromDatabasePath(databasePath: String): ActiveCampaignRef {
            val campaignDir = File(databasePath).parentFile
                ?: return ActiveCampaignRef(DEFAULT_DIRECTORY, DEFAULT_CAMPAIGN_ID)
            val savesDir = campaignDir.parentFile
                ?: return ActiveCampaignRef(campaignDir.name, fallbackCampaignId(campaignDir.name))
            return resolve(savesDir, campaignDir.name)
        }

        internal fun fallbackCampaignId(directoryName: String): String {
            if (directoryName == DEFAULT_DIRECTORY) return DEFAULT_CAMPAIGN_ID

            val base = directoryName
                .removeSuffix(".campaign")
                .trim()
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')

            return base.ifBlank { "campaign" }
        }

        private fun readManifestId(manifest: File): String? {
            if (!manifest.isFile) return null
            val text = runCatching { manifest.readText() }.getOrNull() ?: return null
            return Regex("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }
    }
}
