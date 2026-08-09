package com.rpgos.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UpdateBackupManager(private val context: Context) {
    fun createPreUpdateBackup(): File {
        val base = File(context.filesDir, "rpgos")
        val campaignName = CampaignSelectionManager(context).activeCampaignDirName()
        val campaign = File(base, "saves/$campaignName")
        val db = File(campaign, "campaign.db")
        require(db.exists()) { "campaign.db nie istnieje" }

        // Recover an interrupted restore before taking a pre-update checkpoint.
        RestoreRecovery141.recoverIfNeeded(campaign)

        val backups = File(campaign, "backups").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val out = File(backups, "${stamp}_pre_update.db")
        return SQLitePersistenceCopy141.copyLiveDatabase(
            source = db,
            target = out,
            sourceBoundary = "PRE_UPDATE_BACKUP_SOURCE",
            artifactBoundary = "PRE_UPDATE_BACKUP_ARTIFACT"
        )
    }
}
