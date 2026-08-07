package com.rpgos.app

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class CampaignSelectionManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "rpgos")
    private val saves = File(root, "saves")
    private val worldpacks = File(root, "worldpacks")

    fun activeCampaignDirName(): String =
        prefs.getString("active_campaign", "Naruto_Default.campaign") ?: "Naruto_Default.campaign"

    fun activeWorldPackDirName(): String =
        prefs.getString("active_worldpack", "Naruto.worldpack") ?: "Naruto.worldpack"

    fun setActiveCampaign(dirName: String) {
        require(File(saves, dirName).isDirectory) { "Nie istnieje kampania $dirName" }
        prefs.edit().putString("active_campaign", dirName).apply()
    }

    fun setActiveWorldPack(dirName: String) {
        require(File(worldpacks, dirName).isDirectory) { "Nie istnieje World Pack $dirName" }
        prefs.edit().putString("active_worldpack", dirName).apply()
    }

    fun createCampaign(name: String, fromCampaignDirName: String = "Naruto_Default.campaign"): File {
        val safe = name.trim().replace(Regex("[^A-Za-z0-9_-]"), "_")
        require(safe.isNotBlank()) { "Nazwa kampanii jest pusta." }
        val source = File(saves, fromCampaignDirName)
        require(source.isDirectory) { "Brak szablonu kampanii." }
        val target = File(saves, "$safe.campaign")
        require(!target.exists()) { "Kampania już istnieje." }
        source.copyRecursively(target, overwrite = false)
        File(target, "backups").mkdirs()
        setActiveCampaign(target.name)
        return target
    }
}
