package com.rpgos.app

import android.content.Context
import java.io.File

class CampaignSelectionManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "rpgos")
    private val saves = File(root, "saves")
    private val worldpacks = File(root, "worldpacks")

    fun activeCampaignRef(): ActiveCampaignRef {
        val directoryName =
            prefs.getString("active_campaign", ActiveCampaignRef.DEFAULT_DIRECTORY)
                ?: ActiveCampaignRef.DEFAULT_DIRECTORY
        return ActiveCampaignRef.resolve(saves, directoryName)
    }

    fun activeCampaignDirName(): String = activeCampaignRef().directoryName

    fun activeCampaignId(): String = activeCampaignRef().campaignId

    fun activeWorldPackDirName(): String =
        prefs.getString("active_worldpack", "Naruto.worldpack") ?: "Naruto.worldpack"

    /** Canonical app-level authority for the active World Pack rule mode. */
    fun activeWorldRuleMode(): WorldRuleMode.Bound {
        val dir = File(worldpacks, activeWorldPackDirName())
        val validation = PackageValidator().validateWorldPack(dir)
        require(validation.ok) { "Active World Pack is invalid: ${validation.message}" }
        val uid = validation.packageId?.takeIf { it.isNotBlank() }
            ?: error("Active World Pack manifest has no id")
        val version = validation.version?.takeIf { it.isNotBlank() }
            ?: error("Active World Pack manifest has no version")
        return WorldRuleMode.Bound(WorldPackRuleBinding(uid, version))
    }

    fun setActiveCampaign(dirName: String) {
        require(File(saves, dirName).isDirectory) { "Nie istnieje kampania $dirName" }
        val ref = ActiveCampaignRef.resolve(saves, dirName)
        prefs.edit()
            .putString("active_campaign", ref.directoryName)
            .putString("active_campaign_id", ref.campaignId)
            .apply()
    }

    fun setActiveWorldPack(dirName: String) {
        require(File(worldpacks, dirName).isDirectory) { "Nie istnieje World Pack $dirName" }
        prefs.edit().putString("active_worldpack", dirName).apply()
    }

    fun createCampaign(
        name: String,
        fromCampaignDirName: String = ActiveCampaignRef.DEFAULT_DIRECTORY
    ): File {
        val safe = name.trim().replace(Regex("[^A-Za-z0-9_-]"), "_")
        require(safe.isNotBlank()) { "Nazwa kampanii jest pusta." }
        val source = File(saves, fromCampaignDirName)
        require(source.isDirectory) { "Brak szablonu kampanii." }
        val target = File(saves, "$safe.campaign")
        require(!target.exists()) { "Kampania już istnieje." }
        source.copyRecursively(target, overwrite = false)
        File(target, "backups").mkdirs()

        // A cloned campaign must receive its own logical identity instead of
        // silently inheriting the template campaign ID.
        rewriteClonedCampaignManifestId(target, ActiveCampaignRef.fallbackCampaignId(target.name))

        setActiveCampaign(target.name)
        return target
    }

    private fun rewriteClonedCampaignManifestId(campaignDir: File, campaignId: String) {
        val manifest = File(campaignDir, "campaign.json")
        if (!manifest.isFile) return

        val original = runCatching { manifest.readText() }.getOrNull() ?: return
        val idRegex = Regex("(\\\"id\\\"\\s*:\\s*)\\\"[^\\\"]*\\\"")
        val match = idRegex.find(original) ?: return
        val replacement = "${match.groupValues[1]}\"$campaignId\""
        val updated = original.replaceRange(match.range, replacement)
        if (updated != original) manifest.writeText(updated)
    }
}
