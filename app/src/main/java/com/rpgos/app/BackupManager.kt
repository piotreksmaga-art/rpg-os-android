package com.rpgos.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupManager(private val context: Context) {
    companion object {
        const val AUTO_SNAPSHOT_RETENTION = 6
    }

    private val base = File(context.filesDir, "rpgos")
    private val campaign = File(base, "saves/Naruto_Default.campaign")
    private val db = File(campaign, "campaign.db")
    private val backups = File(campaign, "backups")

    fun createBackup(label: String): File {
        backups.mkdirs()
        require(db.exists()) { "campaign.db nie istnieje" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safe = label.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val out = File(backups, "${stamp}_${safe}.db")
        db.copyTo(out, overwrite = true)

        if (isAutomaticSnapshotLabel(safe)) {
            pruneAutomaticSnapshots()
        }

        return out
    }

    fun listBackups(): List<File> =
        backups.listFiles()
            ?.filter { it.isFile && it.extension == "db" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    private fun isAutomaticSnapshotLabel(label: String): Boolean =
        label.startsWith("chapter_")

    private fun pruneAutomaticSnapshots() {
        val automatic = backups.listFiles()
            ?.filter {
                it.isFile &&
                    it.extension == "db" &&
                    Regex("^\\d{8}_\\d{6}_chapter_.*\\.db$").matches(it.name)
            }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        automatic.drop(AUTO_SNAPSHOT_RETENTION).forEach { stale ->
            runCatching { stale.delete() }
                .onFailure { DiagnosticLogger.log(context, "AUTO_SNAPSHOT_PRUNE_FAILED", it) }
        }
    }
}
