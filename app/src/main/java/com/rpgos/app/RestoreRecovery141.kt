package com.rpgos.app

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Durable restore journal for crash recovery.
 *
 * The marker is persisted before the live database is replaced. If the process
 * dies after replacement but before restore completion, the next campaign open
 * restores the pre-restore safety artifact before exposing campaign.db.
 */
object RestoreRecovery141 {
    private const val MARKER_NAME = ".restore_recovery_141"

    data class Marker(
        val hadLiveDatabase: Boolean,
        val safetyPath: String?
    )

    fun begin(campaignDir: File, safety: File?, hadLiveDatabase: Boolean) {
        campaignDir.mkdirs()
        if (hadLiveDatabase) {
            require(safety?.isFile == true) { "Brak safety artifact przed restore." }
            GameMasterIntegrityGate141.requireHealthyFile(safety, "RESTORE_RECOVERY_SOURCE")
        }

        val marker = markerFile(campaignDir)
        val temp = File(campaignDir, "$MARKER_NAME.tmp")
        val payload = buildString {
            append("hadLiveDatabase=").append(if (hadLiveDatabase) "1" else "0").append('\n')
            append("safetyPath=").append(safety?.absolutePath.orEmpty()).append('\n')
        }
        FileOutputStream(temp).use { out ->
            out.write(payload.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        atomicReplace(temp, marker)
    }

    fun complete(campaignDir: File) {
        val marker = markerFile(campaignDir)
        if (marker.exists()) {
            check(marker.delete()) { "Nie można usunąć restore recovery marker: ${marker.absolutePath}" }
        }
    }

    fun recoverIfNeeded(campaignDir: File): Boolean {
        val markerFile = markerFile(campaignDir)
        if (!markerFile.isFile) return false

        val marker = readMarker(markerFile)
        val target = File(campaignDir, "campaign.db")
        val staged = File(campaignDir, ".restore_recovery_staged.db")

        try {
            if (marker.hadLiveDatabase) {
                val safety = requireNotNull(marker.safetyPath?.takeIf { it.isNotBlank() }) {
                    "Restore recovery marker nie zawiera safetyPath."
                }.let(::File)
                require(safety.isFile) { "Brak safety artifact do recovery: ${safety.absolutePath}" }
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
            complete(campaignDir)
            return true
        } catch (t: Throwable) {
            runCatching { if (staged.exists()) staged.delete() }
            throw t
        }
    }

    fun hasPendingRecovery(campaignDir: File): Boolean = markerFile(campaignDir).isFile

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
            safetyPath = values["safetyPath"]?.takeIf { it.isNotBlank() }
        )
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
