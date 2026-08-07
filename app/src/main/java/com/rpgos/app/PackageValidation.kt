package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File

data class ValidationResult(
    val ok: Boolean,
    val message: String,
    val packageId: String? = null,
    val version: String? = null
)

class PackageValidator {
    fun validateCampaign(dir: File): ValidationResult {
        val db = File(dir, "campaign.db")
        if (!db.exists()) return ValidationResult(false, "Brak campaign.db")
        val manifest = File(dir, "campaign.json")
        if (!manifest.exists()) return ValidationResult(false, "Brak campaign.json")

        val json = JSONObject(manifest.readText())
        val integrity = integrity(db)
        if (integrity != "ok") return ValidationResult(false, "Integralność SQLite: $integrity")

        val coreApi = json.optString("core_api", "")
        if (coreApi != "1") return ValidationResult(false, "Nieobsługiwana wersja Core API: $coreApi")
        return ValidationResult(true, "Campaign OK", json.optString("id"), json.optString("version"))
    }

    fun validateWorldPack(dir: File): ValidationResult {
        val db = File(dir, "world.db")
        if (!db.exists()) return ValidationResult(false, "Brak world.db")
        val manifest = File(dir, "worldpack.json")
        if (!manifest.exists()) return ValidationResult(false, "Brak worldpack.json")

        val json = JSONObject(manifest.readText())
        val integrity = integrity(db)
        if (integrity != "ok") return ValidationResult(false, "Integralność SQLite: $integrity")

        val api = json.optString("engine_api", "")
        if (api != "1") return ValidationResult(false, "Nieobsługiwana wersja Engine API: $api")
        return ValidationResult(true, "World Pack OK", json.optString("id"), json.optString("version"))
    }

    private fun integrity(file: File): String {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return db.use {
            it.rawQuery("PRAGMA integrity_check", null).use { c ->
                if (c.moveToFirst()) c.getString(0) else "unknown"
            }
        }
    }
}
