package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest

/** Stable compatibility identity: semantic family versions only, never volatile migration-attempt metadata. */
internal object Phase36SchemaCompatibilityFingerprint {
    fun compute(db: SQLiteDatabase): String {
        val material = Phase36SchemaVersioning.contracts.joinToString("|") { contract ->
            "${contract.family.name}:${version(db, contract.family) ?: "MISSING"}"
        }
        return MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun version(db: SQLiteDatabase, family: SchemaFamilyUid): Int? {
        if (!db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(Phase36SchemaVersioning.VERSIONS)).use { it.moveToFirst() }) return null
        return db.rawQuery(
            "SELECT schema_version FROM ${Phase36SchemaVersioning.VERSIONS} WHERE schema_family_uid=?",
            arrayOf(family.name)
        ).use { if (it.moveToFirst()) it.getInt(0) else null }
    }
}
