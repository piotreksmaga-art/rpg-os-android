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
    private val backendUrl: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    suspend fun checkOnline(): OnlineUpdateInfo = withContext(Dispatchers.IO) {
        require(backendUrl.isNotBlank()) { "Brak adresu backendu." }
        val req = Request.Builder()
            .url(backendUrl.trimEnd('/') + "/v1/updates/latest")
            .get()
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Update backend HTTP ${resp.code}")
            val json = JSONObject(resp.body.string())
            OnlineUpdateInfo(
                versionCode = json.getLong("version_code"),
                versionName = json.getString("version_name"),
                sha256 = json.getString("sha256"),
                notes = json.optString("notes"),
                mandatory = json.optBoolean("mandatory", false)
            )
        }
    }

    suspend fun downloadOnline(info: OnlineUpdateInfo): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "RPG_OS_${info.versionName}.apk")

        val req = Request.Builder()
            .url(backendUrl.trimEnd('/') + "/v1/updates/apk")
            .get()
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("APK download HTTP ${resp.code}")
            resp.body.byteStream().use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }

        val hash = sha256(out)
        require(hash.equals(info.sha256, ignoreCase = true)) {
            "SHA-256 nie zgadza się."
        }

        val validation = validateApk(out, true)
        require(validation.ok) { validation.message }
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
                "Podpis APK różni się od zainstalowanej aplikacji.",
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
