package com.rpgos.app

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class WorldPackInfo(
    val id: String,
    val name: String,
    val path: String
)

data class CampaignInfo(
    val id: String,
    val name: String,
    val path: String,
    val backupCount: Int
)

class RpgPackageManager(private val context: Context) {
    private val root = File(context.filesDir, "rpgos")
    private val worldpacksDir = File(root, "worldpacks")
    private val savesDir = File(root, "saves")

    fun listWorldPacks(): List<WorldPackInfo> {
        return worldpacksDir.listFiles()
            ?.filter { it.isDirectory }
            ?.map { dir ->
                val id = dir.name.substringBefore(".worldpack")
                WorldPackInfo(id, dir.nameWithoutExtension, dir.absolutePath)
            } ?: emptyList()
    }

    fun listCampaigns(): List<CampaignInfo> {
        return savesDir.listFiles()
            ?.filter { it.isDirectory }
            ?.map { dir ->
                val id = dir.name.substringBefore(".campaign")
                val backups = File(dir, "backups").listFiles()?.count { it.isFile && it.extension == "db" } ?: 0
                CampaignInfo(id, dir.nameWithoutExtension, dir.absolutePath, backups)
            } ?: emptyList()
    }

    fun exportCampaign(campaignDirName: String, destination: File): File {
        val source = File(savesDir, campaignDirName)
        require(source.exists()) { "Nie znaleziono kampanii." }
        ZipOutputStream(FileOutputStream(destination)).use { zip ->
            source.walkTopDown().filter { it.isFile }.forEach { file ->
                val rel = file.relativeTo(source).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry(rel))
                FileInputStream(file).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return destination
    }

    fun importCampaign(zipFile: File, targetDirName: String): File {
        val target = File(savesDir, targetDirName)
        if (target.exists()) target.deleteRecursively()
        target.mkdirs()
        unzip(zipFile, target)
        require(File(target, "campaign.db").exists()) { "Paczka nie zawiera campaign.db" }
        return target
    }

    fun importWorldPack(zipFile: File, targetDirName: String): File {
        val target = File(worldpacksDir, targetDirName)
        if (target.exists()) target.deleteRecursively()
        target.mkdirs()
        unzip(zipFile, target)
        require(File(target, "world.db").exists()) { "Paczka nie zawiera world.db" }
        return target
    }


    fun validatedImportCampaign(zipFile: File, targetDirName: String): ValidationResult {
        val target = importCampaign(zipFile, targetDirName)
        val result = PackageValidator().validateCampaign(target)
        if (!result.ok) {
            target.deleteRecursively()
        }
        return result
    }

    fun validatedImportWorldPack(zipFile: File, targetDirName: String): ValidationResult {
        val target = importWorldPack(zipFile, targetDirName)
        val result = PackageValidator().validateWorldPack(target)
        if (!result.ok) {
            target.deleteRecursively()
        }
        return result
    }

    private fun unzip(zipFile: File, target: File) {
        ZipInputStream(FileInputStream(zipFile)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val out = File(target, entry.name)
                val canonical = out.canonicalPath
                require(canonical.startsWith(target.canonicalPath)) { "Niebezpieczna ścieżka ZIP." }
                if (entry.isDirectory) out.mkdirs()
                else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zip.copyTo(it) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }
}
