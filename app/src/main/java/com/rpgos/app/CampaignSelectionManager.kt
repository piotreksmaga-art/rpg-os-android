package com.rpgos.app

import android.content.Context
import android.content.SharedPreferences
import java.io.File

internal data class CurrentWorldPackAuthority(
    val campaignUid: String,
    val binding: WorldPackRuleBinding
) {
    init {
        require(campaignUid.isNotBlank()) { "campaignUid must not be blank" }
    }
}

/** Narrow read-only capability used by Phase-19 authority resolution. */
internal fun interface WorldPackAuthoritySource {
    fun currentAuthority(): CurrentWorldPackAuthority
}

/**
 * Reads campaign + selected World Pack directory from one SharedPreferences snapshot and keeps the
 * canonical package-authority read gate held until all mutable package content that determines
 * campaignUid + World Pack uid/version has been validated and copied into an immutable result.
 * No selection mutation API is exposed through this capability.
 */
internal class CanonicalSelectionWorldPackAuthoritySource(
    private val prefs: SharedPreferences,
    private val saves: File,
    private val worldpacks: File
) : WorldPackAuthoritySource {
    override fun currentAuthority(): CurrentWorldPackAuthority =
        CanonicalPackageAuthorityGate.observe {
            val selectionSnapshot = LinkedHashMap(prefs.all)
            val campaignDirName =
                (selectionSnapshot["active_campaign"] as? String)
                    ?: ActiveCampaignRef.DEFAULT_DIRECTORY
            val worldPackDirName =
                (selectionSnapshot["active_worldpack"] as? String)
                    ?: "Naruto.worldpack"

            val campaignUid = ActiveCampaignRef.resolve(saves, campaignDirName).campaignId
            val worldPackDir = File(worldpacks, worldPackDirName)
            val validation = PackageValidator().validateWorldPack(worldPackDir)
            require(validation.ok) { "Active World Pack is invalid: ${validation.message}" }
            val uid = validation.packageId?.takeIf { it.isNotBlank() }
                ?: error("Active World Pack manifest has no id")
            val version = validation.version?.takeIf { it.isNotBlank() }
                ?: error("Active World Pack manifest has no version")

            CurrentWorldPackAuthority(
                campaignUid = campaignUid,
                binding = WorldPackRuleBinding(uid, version)
            )
        }
}

/** Resolver retains only a read-only authority capability, never CampaignSelectionManager mutation API. */
internal class CurrentSelectionWorldPackAuthorityResolver(
    private val source: WorldPackAuthoritySource
) : WorldPackAuthorityResolver {
    override fun bindingForCampaign(campaignUid: String): WorldPackRuleBinding? {
        val authority = source.currentAuthority()
        return authority.binding.takeIf { authority.campaignUid == campaignUid }
    }
}

class CampaignSelectionManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "rpgos")
    private val saves = File(root, "saves")
    private val worldpacks = File(root, "worldpacks")
    private val worldPackAuthoritySource: WorldPackAuthoritySource =
        CanonicalSelectionWorldPackAuthoritySource(prefs, saves, worldpacks)

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

    /**
     * One coherent canonical authority observation for Phase 19.
     * campaignUid + World Pack uid/version are derived from one captured selection snapshot and the
     * matching package content while the shared package-authority read gate is held.
     */
    internal fun currentWorldPackAuthority(): CurrentWorldPackAuthority =
        worldPackAuthoritySource.currentAuthority()

    /** Read-only Phase-19 authority resolver backed by the canonical app selection. */
    internal fun activeWorldPackAuthorityResolver(): WorldPackAuthorityResolver =
        CurrentSelectionWorldPackAuthorityResolver(worldPackAuthoritySource)

    /**
     * Compatibility entry point retained for existing callers. Despite the historical name,
     * this returns the live canonical resolver so long-lived engines observe the newest completed
     * coherent authority observation on every resolution.
     */
    internal fun activeWorldPackAuthoritySnapshot(): WorldPackAuthorityResolver =
        activeWorldPackAuthorityResolver()

    fun setActiveCampaign(dirName: String) {
        CanonicalPackageAuthorityGate.mutate {
            require(File(saves, dirName).isDirectory) { "Nie istnieje kampania $dirName" }
            val ref = ActiveCampaignRef.resolve(saves, dirName)
            prefs.edit()
                .putString("active_campaign", ref.directoryName)
                .putString("active_campaign_id", ref.campaignId)
                .apply()
        }
    }

    fun setActiveWorldPack(dirName: String) {
        CanonicalPackageAuthorityGate.mutate {
            require(File(worldpacks, dirName).isDirectory) { "Nie istnieje World Pack $dirName" }
            prefs.edit().putString("active_worldpack", dirName).apply()
        }
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
