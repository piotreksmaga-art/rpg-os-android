package com.rpgos.app

/**
 * Manifest niezależnej aktualizacji zawartości RPG OS.
 *
 * Kod wykonywalny aplikacji pozostaje w podpisanym APK. Ten mechanizm jest
 * przeznaczony wyłącznie dla deklaratywnych danych: worldpacków, reguł MG,
 * konfiguracji, promptów i innych zasobów obsługiwanych przez silnik.
 */
data class ContentUpdateManifest(
    val schemaVersion: Int = 1,
    val channel: String = "alpha",
    val generatedAt: String,
    val packages: List<ContentPackageManifest>
)

data class ContentPackageManifest(
    val id: String,
    val type: ContentPackageType,
    val version: Int,
    val minEngineVersionCode: Int,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long = 0L,
    val description: String = ""
)

enum class ContentPackageType {
    WORLD,
    GAME_MASTER_RULES,
    CONFIG,
    DATA
}

data class InstalledContentPackage(
    val id: String,
    val type: ContentPackageType,
    val version: Int,
    val installedAt: Long,
    val sha256: String
)

data class ContentUpdateCandidate(
    val remote: ContentPackageManifest,
    val installedVersion: Int?
) {
    val isNew: Boolean get() = installedVersion == null
}
