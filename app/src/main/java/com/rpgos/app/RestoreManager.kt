package com.rpgos.app

import android.content.Context
import java.io.File

class RestoreManager(private val context: Context) {
    private val root = File(context.filesDir, "rpgos")
    private val selection = CampaignSelectionManager(context)

    fun restoreBackup(campaignDirName: String, backupPath: String): File {
        // Restore is administrative/recovery work. Reject before reading selection or touching files
        // when a canonical gameplay capability is active on this thread.
        requireAdministrativeRecoveryEntryPoint()

        val active = selection.activeCampaignRef()
        require(campaignDirName == active.directoryName) {
            "Restore może dotyczyć wyłącznie aktywnej kampanii ${active.directoryName}."
        }

        val campaign = File(root, "saves/${active.directoryName}")
        val db = File(campaign, "campaign.db")
        val backup = File(backupPath)
        require(backup.isFile) { "Backup nie istnieje." }
        require(backup.extension == "db") { "Nieprawidłowy typ backupu." }

        val backupDir = File(campaign, "backups")
        val canonicalBackup = backup.canonicalFile
        require(canonicalBackup.toPath().startsWith(backupDir.canonicalFile.toPath())) {
            "Backup nie należy do aktywnej kampanii ${active.campaignId}."
        }

        val safety = File(backupDir, "pre_restore_${System.currentTimeMillis()}.db")
        safety.parentFile?.mkdirs()
        if (db.exists()) db.copyTo(safety, overwrite = true)

        canonicalBackup.copyTo(db, overwrite = true)
        return safety
    }
}
