package com.rpgos.app

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Durable restore journal for crash recovery.
 *
 * The marker is persisted before the live database is replaced. If the process
 * dies after replacement but before restore completion, the next campaign open
 * restores the exact pre-restore safety artifact before exposing campaign.db.
 */
object RestoreRecovery141 {
    private const val MARKER_NAME = ".restore_recovery_141"

    data class Marker(
        val hadLiveDatabase: Boolean,
        val safetyPath: String?,
        val safetySha256: String?
    )

    fun begin(campaignDir: File, safety: File?, hadLiveDatabase: Boolean) {
        campaignDir.mkdirs()
        val normalizedCampaign = campaignDir.canonicalFile
        val normalizedSafety = safety?.canonicalFile
        val safetyHash = if (hadLiveDatabase) {
            require(normalizedSafety?.isFile == true) { "Brak safety artifact przed restore." }
            requireSafetyInsideBackups(normalizedCampaign, normalizedSafety)
            GameMasterIntegrityGate141.requireHealthyFile(normalizedSafety, "RESTORE_RECOVERY_SOURCE")
            sha256(normalizedSafety)
        } else {
            require(safety == null) { "Restore bez poprzedniej bazy nie może wskazywać safety artifact." }
            null
        }

        val marker = markerFile(normalizedCampaign)
        val temp = File(normalizedCampaign, "$MARKER_NAME.tmp")
        val payload = buildString {
            append("hadLiveDatabase=").append(if (hadLiveDatabase) "1" else "0").append('\n')
            append("safetyPath=").append(normalizedSafety?.absolutePath.orEmpty()).append('\n')
            append("safetySha256=").append(safetyHash.orEmpty()).append('\n')
        }
        FileOutputStream(temp).use { out ->
            out.write(payload.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        atomicReplace(temp, marker)
    }

    fun complete(campaignDir: File) {
        val marker = markerFile(campaignDir.canonicalFile)
        if (marker.exists()) {
            check(marker.delete()) { "Nie można usunąć restore recovery marker: ${marker.absolutePath}" }
        }
    }

    fun recoverIfNeeded(campaignDir: File): Boolean {
        val normalizedCampaign = campaignDir.canonicalFile
        val markerFile = markerFile(normalizedCampaign)
        if (!markerFile.isFile) return false

        val marker = readMarker(markerFile)
        val target = File(normalizedCampaign, "campaign.db")
        val staged = File(normalizedCampaign, ".restore_recovery_staged.db")

        try {
            if (marker.hadLiveDatabase) {
                val safety = requireNotNull(marker.safetyPath?.takeIf { it.isNotBlank() }) {
                    "Restore recovery marker nie zawiera safetyPath."
                }.let(::File).canonicalFile
                val expectedHash = requireNotNull(marker.safetySha256?.takeIf { it.isNotBlank() }) {
                    "Restore recovery marker nie zawiera safetySha256."
                }
                requireSafetyInsideBackups(normalizedCampaign, safety)
                require(safety.isFile) { "Brak safety artifact do recovery: ${safety.absolutePath}" }
                require(sha256(safety) == expectedHash) {
                    "RESTORE_RECOVERY_SAFETY_HASH_MISMATCH: safety artifact zmienił się po utworzeniu markera."
                }
                GameMasterIntegrityGate141.requireHealthyFile(safety, "RESTORE_RECOVERY_SOURCE")
                SQLitePersistenceCopy141.stageStandaloneDatabase(
                    source = safety,
                    staged = staged,
                    artifactBoundary = "RESTORE_RECOVERY_STAGED"
                )
                SQLitePersistenceCopy141.replaceDatabaseWithStaged(staged, target)
                GameMasterIntegrityGate141.requireHealthyFile(target, "RESTORE_RECOVERY_TARGET")
            } else {
                runCatching { if (target.exists()) target.delete() }
                SQLitePersistenceCopy141.deleteWalSidecars(target)
            }
            complete(normalizedCampaign)
            return true
        } catch (t: Throwable) {
            runCatching { if (staged.exists()) staged.delete() }
            throw t
        }
    }

    fun hasPendingRecovery(campaignDir: File): Boolean = markerFile(campaignDir.canonicalFile).isFile

    private fun markerFile(campaignDir: File): File = File(campaignDir, MARKER_NAME)

    private fun readMarker(file: File): Marker {
        val values = file.readLines(Charsets.UTF_8)
            .mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
            }
            .toMap()
        return Marker(
            hadLiveDatabase = values["hadLiveDatabase"] == "1",
            safetyPath = values["safetyPath"]?.takeIf { it.isNotBlank() },
            safetySha256 = values["safetySha256"]?.takeIf { it.isNotBlank() }
        )
    }

    private fun requireSafetyInsideBackups(campaignDir: File, safety: File) {
        val backups = File(campaignDir, "backups").canonicalFile
        val safetyPath = safety.canonicalPath
        val prefix = backups.canonicalPath + File.separator
        require(safetyPath.startsWith(prefix) && safety.extension == "db") {
            "RESTORE_RECOVERY_SAFETY_OUTSIDE_BACKUPS: ${safety.absolutePath}"
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                md.update(buffer, 0, count)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun atomicReplace(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
