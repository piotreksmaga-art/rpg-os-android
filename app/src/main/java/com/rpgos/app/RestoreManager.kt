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

        // Validate the incoming standalone database before touching the live campaign.
        GameMasterIntegrityGate141.requireHealthyFile(backup, "RESTORE_SOURCE")

        val now = System.currentTimeMillis()
        val safety = File(campaign, "backups/pre_restore_$now.db")
        safety.parentFile?.mkdirs()
        if (db.exists()) {
            SQLitePersistenceCopy141.copyLiveDatabase(
                source = db,
                target = safety,
                sourceBoundary = "RESTORE_LIVE_SOURCE",
                artifactBoundary = "RESTORE_SAFETY_ARTIFACT"
            )
        }

        val staged = File(campaign, ".restore_staged_$now.db")
        SQLitePersistenceCopy141.stageStandaloneDatabase(
            source = backup,
            staged = staged,
            artifactBoundary = "RESTORE_STAGED_ARTIFACT"
        )

        try {
            SQLitePersistenceCopy141.replaceDatabaseWithStaged(staged, db)
            GameMasterIntegrityGate141.requireHealthyFile(db, "RESTORE_TARGET")
            return safety
        } catch (t: Throwable) {
            runCatching { if (staged.exists()) staged.delete() }
            throw t
        }
    }
}
