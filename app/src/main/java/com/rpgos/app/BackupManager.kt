package com.rpgos.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupManager(private val context: Context) {
    private val base = File(context.filesDir, "rpgos")
    private val selection = CampaignSelectionManager(context)
    private val campaign: File get() = File(base, "saves/${selection.activeCampaignDirName()}")
    private val db: File get() = File(campaign, "campaign.db")
    private val backups: File get() = File(campaign, "backups")

    fun createBackup(label: String): File {
        backups.mkdirs()
        require(db.exists()) { "campaign.db nie istnieje" }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safe = label.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val out = File(backups, "${stamp}_${safe}.db")
        return SQLitePersistenceCopy141.copyLiveDatabase(
            source = db,
            target = out,
            sourceBoundary = "BACKUP_SOURCE",
            artifactBoundary = "BACKUP_ARTIFACT"
        )
    }

    fun listBackups(): List<File> =
        backups.listFiles()?.filter { it.isFile && it.extension == "db" }?.sortedByDescending { it.lastModified() } ?: emptyList()
}
