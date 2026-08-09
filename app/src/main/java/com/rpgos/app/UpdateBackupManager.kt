package com.rpgos.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UpdateBackupManager(private val context: Context) {
    fun createPreUpdateBackup(): File {
        val base = File(context.filesDir, "rpgos")
        val campaignRef = CampaignSelectionManager(context).activeCampaignRef()
        val campaign = File(base, "saves/${campaignRef.directoryName}")
        val db = File(campaign, "campaign.db")
        require(db.exists()) { "campaign.db nie istnieje" }

        val backups = File(campaign, "backups").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val out = File(backups, "${stamp}_pre_update.db")
        db.copyTo(out, overwrite = true)
        return out
    }
}
