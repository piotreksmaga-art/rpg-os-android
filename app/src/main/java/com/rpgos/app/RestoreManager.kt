package com.rpgos.app

import android.content.Context
import java.io.File

class RestoreManager(private val context: Context) {
    private val root = File(context.filesDir, "rpgos")

    fun restoreBackup(campaignDirName: String, backupPath: String): File {
        val campaign = File(root, "saves/$campaignDirName")
        val db = File(campaign, "campaign.db")
        val backup = File(backupPath)
        require(backup.isFile) { "Backup nie istnieje." }
        require(backup.extension == "db") { "Nieprawidłowy typ backupu." }

        // Validate the incoming database before touching the live campaign.
        GameMasterIntegrityGate141.requireHealthyFile(backup, "RESTORE_SOURCE")

        val safety = File(campaign, "backups/pre_restore_${System.currentTimeMillis()}.db")
        safety.parentFile?.mkdirs()
        if (db.exists()) db.copyTo(safety, overwrite = true)

        backup.copyTo(db, overwrite = true)
        return safety
    }
}
