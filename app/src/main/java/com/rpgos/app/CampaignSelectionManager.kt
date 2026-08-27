package com.rpgos.app

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.util.UUID

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
            val unsettledRollback = worldpacks.listFiles().orEmpty().any {
                it.name.startsWith(".${worldPackDir.name}.rollback-")
            }
            check(!unsettledRollback) { "Active World Pack replacement is unsettled." }
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

internal fun interface CampaignTreeCopier {
    fun copy(source: File, target: File): Boolean
}

private object DefaultCampaignTreeCopier : CampaignTreeCopier {
    override fun copy(source: File, target: File): Boolean =
        source.copyRecursively(target, overwrite = false)
}

class CampaignSelectionManager private constructor(
    private val context: Context,
    private val campaignTreeCopier: CampaignTreeCopier
) {
    constructor(context: Context) : this(context, DefaultCampaignTreeCopier)

    internal constructor(
        context: Context,
        campaignTreeCopier: (File, File) -> Boolean
    ) : this(context, CampaignTreeCopier(campaignTreeCopier))

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
        val target = File(saves, "$safe.campaign")
        val staging = File(saves, ".clone-$safe-${UUID.randomUUID()}")

        try {
            // Snapshot one coherent source generation. Supported campaign replacement/import writers
            // use the write side of this same gate, so they cannot interleave with the directory copy.
            CanonicalPackageAuthorityGate.observe {
                require(source.isDirectory) { "Brak szablonu kampanii." }
                require(!target.exists()) { "Kampania już istnieje." }
                check(campaignTreeCopier.copy(source, staging)) { "CAMPAIGN_CLONE_COPY_FAILED" }
            }

            File(staging, "backups").mkdirs()

            // A cloned campaign must receive its own logical identity instead of silently inheriting
            // the template campaign ID. Validation after the rewrite is the acceptance boundary.
            rewriteClonedCampaignManifestId(staging, ActiveCampaignRef.fallbackCampaignId(target.name))
            val validation = runCatching { PackageValidator().validateCampaign(staging) }
                .getOrElse { throw IllegalStateException("CAMPAIGN_CLONE_VALIDATION_FAILED", it) }
            check(validation.ok) { "CAMPAIGN_CLONE_VALIDATION_FAILED: ${validation.message}" }

            CanonicalPackageAuthorityGate.mutate {
                require(!target.exists()) { "Kampania już istnieje." }
                check(staging.renameTo(target)) { "CAMPAIGN_CLONE_ACTIVATION_FAILED" }
                val ref = ActiveCampaignRef.resolve(saves, target.name)
                prefs.edit()
                    .putString("active_campaign", ref.directoryName)
                    .putString("active_campaign_id", ref.campaignId)
                    .apply()
            }
            return target
        } catch (t: Throwable) {
            // Staging is never authority. A failed/incomplete clone must not survive as a selectable
            // canonical package and must never become active.
            if (staging.exists()) staging.deleteRecursively()
            throw t
        }
    }

    /**
     * Removes a selectable campaign without destroying it. The directory is atomically moved to
     * the hidden saves/.trash directory, so it no longer appears in normal campaign listings but
     * can still be recovered manually if the user removed it by mistake.
     *
     * The bundled template and the active campaign are protected. Callers must make another
     * campaign active before archiving the current one.
     */
    fun moveCampaignToTrash(dirName: String): File = CanonicalPackageAuthorityGate.mutate {
        require(File(dirName).name == dirName && dirName.endsWith(".campaign")) {
            "Nieprawidłowa nazwa katalogu kampanii."
        }
        require(dirName != ActiveCampaignRef.DEFAULT_DIRECTORY) {
            "Nie można usunąć domyślnej kampanii systemowej."
        }
        require(dirName != activeCampaignDirName()) {
            "Nie można usunąć aktywnej kampanii. Najpierw aktywuj inną kampanię."
        }

        val source = File(saves, dirName)
        require(source.isDirectory) { "Nie znaleziono kampanii $dirName" }
        require(source.canonicalFile.parentFile == saves.canonicalFile) {
            "Kampania znajduje się poza katalogiem zapisów."
        }

        val trash = File(saves, ".trash")
        check(trash.isDirectory || trash.mkdirs()) { "Nie udało się utworzyć kosza kampanii." }
        val baseName = dirName.removeSuffix(".campaign")
        val destination = File(
            trash,
            "$baseName-deleted-${System.currentTimeMillis()}-${UUID.randomUUID()}.campaign"
        )
        check(source.renameTo(destination)) { "Nie udało się przenieść kampanii do kosza." }
        destination
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
