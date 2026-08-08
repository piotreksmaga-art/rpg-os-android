package com.rpgos.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class ContentUpdateManager(
    private val context: Context,
    private val manifestUrl: String = BuildConfig.RPGOS_CONTENT_UPDATE_URL
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private val root = File(context.filesDir, "rpgos")
    private val contentRoot = File(root, "content").apply { mkdirs() }
    private val registryFile = File(contentRoot, "installed-content.json")
    private val stagingRoot = File(contentRoot, ".staging").apply { mkdirs() }
    private val backupRoot = File(contentRoot, "backups").apply { mkdirs() }

    suspend fun check(): List<ContentUpdateCandidate> = withContext(Dispatchers.IO) {
        val manifest = fetchManifest()
        require(manifest.schemaVersion == 1) {
            "Nieobsługiwana wersja manifestu zawartości: ${manifest.schemaVersion}."
        }

        val installed = installedPackages().associateBy { it.id }
        manifest.packages
            .filter { it.minEngineVersionCode <= BuildConfig.VERSION_CODE }
            .filter { remote -> (installed[remote.id]?.version ?: 0) < remote.version }
            .map { remote -> ContentUpdateCandidate(remote, installed[remote.id]?.version) }
    }

    suspend fun install(candidate: ContentUpdateCandidate): InstalledContentPackage =
        install(candidate.remote)

    suspend fun install(pkg: ContentPackageManifest): InstalledContentPackage =
        withContext(Dispatchers.IO) {
            require(pkg.minEngineVersionCode <= BuildConfig.VERSION_CODE) {
                "Pakiet ${pkg.id} wymaga silnika ${pkg.minEngineVersionCode}, a zainstalowany jest ${BuildConfig.VERSION_CODE}."
            }
            require(pkg.sha256.matches(Regex("[0-9a-fA-F]{64}"))) {
                "Pakiet ${pkg.id} nie ma poprawnej sumy SHA-256."
            }

            val safeId = safeId(pkg.id)
            val downloadDir = File(context.cacheDir, "content-updates").apply { mkdirs() }
            val zip = File(downloadDir, "$safeId-${pkg.version}.zip")
            val partial = File(downloadDir, "${zip.name}.part")
            partial.delete()

            download(pkg.downloadUrl, partial)
            val actualSha = sha256(partial)
            require(actualSha.equals(pkg.sha256, ignoreCase = true)) {
                partial.delete()
                "SHA-256 pakietu ${pkg.id} nie zgadza się."
            }
            if (zip.exists()) zip.delete()
            require(partial.renameTo(zip)) { "Nie można przygotować pakietu ${pkg.id}." }

            val staging = File(stagingRoot, "$safeId-${System.currentTimeMillis()}")
            staging.deleteRecursively()
            staging.mkdirs()

            try {
                unzipSafely(zip, staging)
                validatePayload(pkg, staging)

                val target = targetDirectory(pkg)
                target.parentFile?.mkdirs()
                val previous = if (target.exists()) {
                    File(backupRoot, "$safeId-${System.currentTimeMillis()}").also { backup ->
                        copyDirectory(target, backup)
                    }
                } else null

                try {
                    val replacement = File(target.parentFile, ".${target.name}.new")
                    replacement.deleteRecursively()
                    copyDirectory(staging, replacement)
                    if (target.exists()) target.deleteRecursively()
                    require(replacement.renameTo(target)) {
                        "Nie można aktywować pakietu ${pkg.id}."
                    }

                    val installed = InstalledContentPackage(
                        id = pkg.id,
                        type = pkg.type,
                        version = pkg.version,
                        installedAt = System.currentTimeMillis(),
                        sha256 = actualSha.lowercase()
                    )
                    saveInstalled(installed)
                    pruneBackups(safeId, keep = 3)
                    installed
                } catch (t: Throwable) {
                    if (target.exists()) target.deleteRecursively()
                    if (previous != null && previous.exists()) copyDirectory(previous, target)
                    throw t
                }
            } finally {
                staging.deleteRecursively()
                zip.delete()
            }
        }

    fun installedPackages(): List<InstalledContentPackage> {
        if (!registryFile.exists()) return emptyList()
        return runCatching {
            val rootJson = JSONObject(registryFile.readText())
            val array = rootJson.optJSONArray("packages") ?: JSONArray()
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(
                        InstalledContentPackage(
                            id = item.getString("id"),
                            type = ContentPackageType.valueOf(item.getString("type")),
                            version = item.getInt("version"),
                            installedAt = item.optLong("installedAt", 0L),
                            sha256 = item.optString("sha256")
                        )
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun fetchManifest(): ContentUpdateManifest {
        require(manifestUrl.isNotBlank()) { "Brak adresu kanału aktualizacji zawartości." }
        val req = Request.Builder()
            .url(manifestUrl)
            .header("User-Agent", "RPG-OS-Android/${BuildConfig.VERSION_NAME}")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Content update HTTP ${resp.code}")
            return parseManifest(JSONObject(resp.body.string()))
        }
    }

    private fun parseManifest(json: JSONObject): ContentUpdateManifest {
        val array = json.getJSONArray("packages")
        val packages = buildList {
            for (i in 0 until array.length()) {
                val p = array.getJSONObject(i)
                add(
                    ContentPackageManifest(
                        id = p.getString("id"),
                        type = ContentPackageType.valueOf(p.getString("type")),
                        version = p.getInt("version"),
                        minEngineVersionCode = p.getInt("minEngineVersionCode"),
                        downloadUrl = p.getString("downloadUrl"),
                        sha256 = p.getString("sha256"),
                        sizeBytes = p.optLong("sizeBytes", 0L),
                        description = p.optString("description")
                    )
                )
            }
        }
        return ContentUpdateManifest(
            schemaVersion = json.optInt("schemaVersion", 1),
            channel = json.optString("channel", "alpha"),
            generatedAt = json.optString("generatedAt"),
            packages = packages
        )
    }

    private fun download(url: String, out: File) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "RPG-OS-Android/${BuildConfig.VERSION_NAME}")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Pobieranie pakietu HTTP ${resp.code}")
            resp.body.byteStream().use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    private fun targetDirectory(pkg: ContentPackageManifest): File = when (pkg.type) {
        ContentPackageType.WORLD -> File(root, "worldpacks/${safeId(pkg.id)}.worldpack")
        ContentPackageType.GAME_MASTER_RULES -> File(contentRoot, "gm/${safeId(pkg.id)}")
        ContentPackageType.CONFIG -> File(contentRoot, "config/${safeId(pkg.id)}")
        ContentPackageType.DATA -> File(contentRoot, "data/${safeId(pkg.id)}")
    }

    private fun validatePayload(pkg: ContentPackageManifest, dir: File) {
        require(dir.walkTopDown().any { it.isFile }) { "Pakiet ${pkg.id} jest pusty." }
        when (pkg.type) {
            ContentPackageType.WORLD -> {
                require(File(dir, "world.db").exists()) { "Worldpack ${pkg.id} nie zawiera world.db." }
                val validation = PackageValidator().validateWorldPack(dir)
                require(validation.ok) { validation.message.ifBlank { "Worldpack jest niepoprawny." } }
            }
            else -> {
                require(File(dir, "content.json").exists()) {
                    "Pakiet ${pkg.id} nie zawiera content.json."
                }
            }
        }
    }

    private fun saveInstalled(newItem: InstalledContentPackage) {
        val map = installedPackages().associateBy { it.id }.toMutableMap()
        map[newItem.id] = newItem
        val array = JSONArray()
        map.values.sortedBy { it.id }.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id)
                put("type", item.type.name)
                put("version", item.version)
                put("installedAt", item.installedAt)
                put("sha256", item.sha256)
            })
        }
        val json = JSONObject().apply {
            put("schemaVersion", 1)
            put("packages", array)
        }
        val tmp = File(registryFile.parentFile, "${registryFile.name}.tmp")
        tmp.writeText(json.toString(2))
        if (registryFile.exists()) registryFile.delete()
        require(tmp.renameTo(registryFile)) { "Nie można zapisać rejestru zawartości." }
    }

    private fun unzipSafely(zipFile: File, target: File) {
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

    private fun copyDirectory(source: File, target: File) {
        if (target.exists()) target.deleteRecursively()
        source.walkTopDown().forEach { file ->
            val relative = file.relativeTo(source)
            val out = File(target, relative.path)
            if (file.isDirectory) out.mkdirs() else {
                out.parentFile?.mkdirs()
                file.inputStream().use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
    }

    private fun pruneBackups(safeId: String, keep: Int) {
        backupRoot.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("$safeId-") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(keep)
            ?.forEach { it.deleteRecursively() }
    }

    private fun safeId(value: String): String {
        val safe = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
        require(safe.isNotBlank() && safe != "." && safe != "..") { "Niepoprawny identyfikator pakietu." }
        return safe
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                md.update(buffer, 0, count)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
