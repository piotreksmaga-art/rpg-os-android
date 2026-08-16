package com.rpgos.app

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
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

    internal fun currentWorldPackAuthority(): CurrentWorldPackAuthority =
        worldPackAuthoritySource.currentAuthority()

    internal fun activeWorldPackAuthorityResolver(): WorldPackAuthorityResolver =
        CurrentSelectionWorldPackAuthorityResolver(worldPackAuthoritySource)

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
            CanonicalPackageAuthorityGate.observe {
                require(source.isDirectory) { "Brak szablonu kampanii." }
                require(!target.exists()) { "Kampania już istnieje." }
                copyCampaignTreeFromCoherentSqliteSnapshot(source, staging)
            }

            File(staging, "backups").mkdirs()
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
            if (staging.exists()) staging.deleteRecursively()
            throw t
        }
    }

    /**
     * Package-authority locking prevents replacement/import races, but ordinary gameplay writes use
     * independent SQLiteDatabase connections. Hold SQLite's EXCLUSIVE transaction on the source DB
     * while the bounded local tree copy is made, so every filesystem byte belongs to one committed
     * database generation. The production open path does not opt into WAL; sidecar files, if any,
     * are copied while this source write lock is held and the staged package is fully validated
     * before it can become authority.
     */
    private fun copyCampaignTreeFromCoherentSqliteSnapshot(source: File, staging: File) {
        val sourceDbFile = File(source, "campaign.db")
        require(sourceDbFile.isFile) { "CAMPAIGN_CLONE_SOURCE_DB_MISSING" }
        SQLiteDatabase.openDatabase(
            sourceDbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE
        ).use { sourceDb ->
            sourceDb.beginTransaction()
            try {
                check(campaignTreeCopier.copy(source, staging)) { "CAMPAIGN_CLONE_COPY_FAILED" }
            } finally {
                sourceDb.endTransaction()
            }
        }
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
