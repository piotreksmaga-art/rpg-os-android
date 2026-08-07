package com.rpgos.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class UpdateManager(
    private val context: Context,
    private val updateFeedUrl: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    suspend fun checkOnline(): OnlineUpdateInfo = withContext(Dispatchers.IO) {
        require(updateFeedUrl.isNotBlank()) { "Brak adresu kanału aktualizacji." }

        val releaseJson = getJson(updateFeedUrl)
        val assets = releaseJson.getJSONArray("assets")

        var apkUrl: String? = null
        var shaUrl: String? = null
        var metadataUrl: String? = null

        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")

            when {
                name.equals("update.json", ignoreCase = true) ->
                    metadataUrl = url
                name.endsWith(".apk.sha256", ignoreCase = true) ->
                    shaUrl = url
                name.endsWith(".apk", ignoreCase = true) ->
                    apkUrl = url
            }
        }

        require(!apkUrl.isNullOrBlank()) {
            "Release nie zawiera pliku APK."
        }

        val tagName = releaseJson.optString("tag_name")
        val releaseBody = releaseJson.optString("body")
        val releasePage = releaseJson.optString("html_url")
        val releaseName = releaseJson.optString("name")

        var versionName = tagName.removePrefix("v").ifBlank { releaseName }
        var versionCode: Long? = null
        var expectedSha: String? = null
        var notes = releaseBody
        var mandatory = false

        // alpha3+ publishes a tiny machine-readable metadata file.
        if (!metadataUrl.isNullOrBlank()) {
            runCatching {
                val metadata = getJson(metadataUrl!!)
                versionName = metadata.optString("version_name", versionName)
                versionCode = metadata.optLong("version_code").takeIf { it > 0 }
                expectedSha = metadata.optString("sha256").takeIf { it.length == 64 }
                notes = metadata.optString("notes", notes)
                mandatory = metadata.optBoolean("mandatory", false)
            }
        }

        // Compatibility with alpha2 release notes.
        if (versionCode == null) {
            versionCode = Regex(
                """VersionCode\s*:\s*(\d+)""",
                RegexOption.IGNORE_CASE
            ).find(releaseBody)?.groupValues?.getOrNull(1)?.toLongOrNull()
        }

        // SHA sidecar remains independently verified.
        if (expectedSha == null && !shaUrl.isNullOrBlank()) {
            expectedSha = downloadText(shaUrl!!)
                .trim()
                .split(Regex("""\s+"""))
                .firstOrNull()
                ?.lowercase()
                ?.takeIf { it.matches(Regex("""[0-9a-f]{64}""")) }
        }

        require(versionCode != null) {
            "Release nie zawiera VersionCode/update.json."
        }
        require(expectedSha != null) {
            "Release nie zawiera poprawnej sumy SHA-256."
        }

        OnlineUpdateInfo(
            versionCode = versionCode!!,
            versionName = versionName,
            sha256 = expectedSha!!,
            notes = notes,
            mandatory = mandatory,
            apkUrl = apkUrl!!,
            sha256Url = shaUrl,
            releaseUrl = releasePage,
            tagName = tagName
        )
    }

    suspend fun downloadOnline(info: OnlineUpdateInfo): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val safeVersion = info.versionName.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
        val out = File(dir, "RPG-OS-ALPHA-$safeVersion.apk")
        val partial = File(dir, "${out.name}.part")

        if (partial.exists()) partial.delete()

        val req = Request.Builder()
            .url(info.apkUrl)
            .header("User-Agent", "RPG-OS-Android/${BuildConfig.VERSION_NAME}")
            .get()
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("APK download HTTP ${resp.code}")
            resp.body.byteStream().use { input ->
                partial.outputStream().use { output -> input.copyTo(output) }
            }
        }

        val hash = sha256(partial)
        require(hash.equals(info.sha256, ignoreCase = true)) {
            partial.delete()
            "SHA-256 nie zgadza się. Oczekiwano ${info.sha256}, otrzymano $hash."
        }

        if (out.exists()) out.delete()
        require(partial.renameTo(out)) {
            "Nie można przygotować pobranego APK."
        }

        val validation = validateApk(out, true)
        require(validation.ok) {
            out.delete()
            validation.message
        }
        out
    }

    suspend fun importLocal(uri: Uri): Pair<File, ApkValidationResult> = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "local_update.apk")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Nie można odczytać wybranego APK." }
            out.outputStream().use { output -> input.copyTo(output) }
        }
        out to validateApk(out, true)
    }

    fun validateApk(file: File, requireNewer: Boolean): ApkValidationResult {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: return ApkValidationResult(false, "Plik nie jest prawidłowym APK.")

        if (archive.packageName != context.packageName) {
            return ApkValidationResult(false, "To nie jest APK RPG OS.")
        }

        val installed = pm.getPackageInfo(context.packageName, flags)
        val archiveCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            archive.longVersionCode else @Suppress("DEPRECATION") archive.versionCode.toLong()
        val installedCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            installed.longVersionCode else @Suppress("DEPRECATION") installed.versionCode.toLong()
        val archiveName = archive.versionName ?: "?"

        if (requireNewer && archiveCode <= installedCode) {
            return ApkValidationResult(
                false,
                "APK nie jest nowszy. Zainstalowany=$installedCode, APK=$archiveCode.",
                archiveCode,
                archiveName
            )
        }

        if (!sameSigner(installed, archive)) {
            return ApkValidationResult(
                false,
                "Podpis APK różni się od zainstalowanej aplikacji. " +
                    "Jeśli przechodzisz ze starej wersji debugowej na stały podpis alpha3, " +
                    "wymagana jest jednorazowa czysta instalacja po wykonaniu backupu.",
                archiveCode,
                archiveName
            )
        }

        return ApkValidationResult(true, "APK zweryfikowany.", archiveCode, archiveName)
    }

    fun install(file: File) {
        UpdateBackupManager(context).createPreUpdateBackup()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
            error("Włącz „Zezwalaj z tego źródła” i ponów instalację.")
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(65536)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun getJson(url: String): JSONObject {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "RPG-OS-Android/${BuildConfig.VERSION_NAME}")
            .get()
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Update HTTP ${resp.code}")
            return JSONObject(resp.body.string())
        }
    }

    private fun downloadText(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "RPG-OS-Android/${BuildConfig.VERSION_NAME}")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("SHA-256 HTTP ${resp.code}")
            return resp.body.string()
        }
    }

    private fun sameSigner(
        installed: android.content.pm.PackageInfo,
        archive: android.content.pm.PackageInfo
    ): Boolean {
        fun digests(info: android.content.pm.PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners?.toList().orEmpty()
            } else {
                @Suppress("DEPRECATION")
                info.signatures?.toList().orEmpty()
            }
            return signatures.map { sig ->
                MessageDigest.getInstance("SHA-256")
                    .digest(sig.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            }.toSet()
        }
        val a = digests(installed)
        val b = digests(archive)
        return a.isNotEmpty() && a == b
    }
}
