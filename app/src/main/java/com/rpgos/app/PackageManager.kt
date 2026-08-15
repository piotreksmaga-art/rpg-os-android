package com.rpgos.app

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class WorldPackInfo(val id: String, val name: String, val path: String)
data class CampaignInfo(val id: String, val name: String, val path: String, val backupCount: Int)

class RpgPackageManager(private val context: Context) {
    private val root = File(context.filesDir, "rpgos")
    private val worldpacksDir = File(root, "worldpacks")
    private val savesDir = File(root, "saves")
    private val stagingDir = File(root, ".package-import-staging")

    fun listWorldPacks(): List<WorldPackInfo> = worldpacksDir.listFiles()
        ?.filter { it.isDirectory && !it.name.startsWith(".") }
        ?.map { dir -> WorldPackInfo(dir.name.substringBefore(".worldpack"), dir.nameWithoutExtension, dir.absolutePath) }
        ?: emptyList()

    fun listCampaigns(): List<CampaignInfo> = savesDir.listFiles()
        ?.filter { it.isDirectory && !it.name.startsWith(".") }
        ?.map { dir ->
            val backups = File(dir, "backups").listFiles()?.count { it.isFile && it.extension == "db" } ?: 0
            CampaignInfo(dir.name.substringBefore(".campaign"), dir.nameWithoutExtension, dir.absolutePath, backups)
        } ?: emptyList()

    fun exportCampaign(campaignDirName: String, destination: File): File {
        val source = File(savesDir, campaignDirName)
        require(source.exists()) { "Nie znaleziono kampanii." }
        ZipOutputStream(FileOutputStream(destination)).use { zip ->
            source.walkTopDown().filter { it.isFile }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(source).invariantSeparatorsPath))
                FileInputStream(file).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return destination
    }

    fun importCampaign(zipFile: File, targetDirName: String): File =
        importPrepared(zipFile, File(savesDir, targetDirName), "campaign.db")

    fun importWorldPack(zipFile: File, targetDirName: String): File =
        importPrepared(zipFile, File(worldpacksDir, targetDirName), "world.db")

    fun validatedImportCampaign(zipFile: File, targetDirName: String): ValidationResult =
        validatedImport(zipFile, File(savesDir, targetDirName), "campaign.db") { PackageValidator().validateCampaign(it) }

    fun validatedImportWorldPack(zipFile: File, targetDirName: String): ValidationResult =
        validatedImport(zipFile, File(worldpacksDir, targetDirName), "world.db") { PackageValidator().validateWorldPack(it) }

    private fun importPrepared(zipFile: File, target: File, requiredFile: String): File {
        val staging = extractToStaging(zipFile)
        try {
            require(File(staging, requiredFile).isFile) { "Paczka nie zawiera $requiredFile" }
            val validPackage: (File) -> Boolean = { candidate -> File(candidate, requiredFile).isFile }
            val prepared = CanonicalPackageReplacement.prepareCopy(staging, target)
            CanonicalPackageReplacement.activatePrepared(prepared, target, validPackage)
            return target
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun validatedImport(
        zipFile: File,
        target: File,
        requiredFile: String,
        validate: (File) -> ValidationResult
    ): ValidationResult {
        val staging = extractToStaging(zipFile)
        try {
            if (!File(staging, requiredFile).isFile) return ValidationResult(false, "Brak $requiredFile")
            val result = try {
                validate(staging)
            } catch (t: Throwable) {
                throw IllegalArgumentException("PACKAGE_VALIDATION_FAILED_BEFORE_ACTIVATION", t)
            }
            if (!result.ok) return result
            val prepared = CanonicalPackageReplacement.prepareCopy(staging, target)
    CanonicalPackageReplacement.activatePrepared(
        prepared = prepared,
        target = target,
        isValidPackage = { candidate -> runCatching { validate(candidate).ok }.getOrDefault(false) }
    )
    return result
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun extractToStaging(zipFile: File): File {
        stagingDir.mkdirs()
        val staging = File(stagingDir, "import-${UUID.randomUUID()}")
        staging.mkdirs()
        try {
            unzip(zipFile, staging)
            return staging
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
    }

    private fun unzip(zipFile: File, target: File) {
        val rootPath = target.canonicalFile.toPath()
        ZipInputStream(FileInputStream(zipFile)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val out = File(target, entry.name).canonicalFile
                require(out.toPath().startsWith(rootPath)) { "Niebezpieczna ścieżka ZIP." }
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zip.copyTo(it) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }
}
