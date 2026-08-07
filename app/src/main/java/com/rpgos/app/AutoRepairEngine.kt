package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

data class AutoRepairReport(
    val repairs: Int,
    val warnings: List<String>
)

class AutoRepairEngine {
    fun repair(saveDb: SQLiteDatabase): AutoRepairReport {
        var repairs = 0
        val warnings = mutableListOf<String>()

        fun createIfMissing(name: String, sql: String) {
            if (!tableExists(saveDb, name)) {
                try {
                    saveDb.execSQL(sql)
                    repairs++
                } catch (t: Throwable) {
                    warnings += "$name: ${t.message}"
                }
            }
        }

        createIfMissing(
            "narrative_memory_index",
            """CREATE TABLE IF NOT EXISTS narrative_memory_index(
                memory_uid TEXT PRIMARY KEY,
                entity_uid TEXT,
                memory_type TEXT,
                source_chapter INTEGER DEFAULT 0,
                importance REAL DEFAULT 0,
                keywords TEXT,
                summary TEXT,
                active INTEGER NOT NULL DEFAULT 1
            )"""
        )

        createIfMissing(
            "information_facts",
            """CREATE TABLE IF NOT EXISTS information_facts(
                info_uid TEXT PRIMARY KEY,
                title TEXT,
                content_summary TEXT,
                secrecy_level TEXT
            )"""
        )

        createIfMissing(
            "rpgos_repair_log",
            """CREATE TABLE IF NOT EXISTS rpgos_repair_log(
                repair_id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                repair_version TEXT NOT NULL,
                details TEXT
            )"""
        )

        runCatching {
            saveDb.execSQL(
                "INSERT INTO rpgos_repair_log(repair_version,details) VALUES(?,?)",
                arrayOf("FIX8", if (warnings.isEmpty()) "auto-check OK" else warnings.joinToString(" | "))
            )
        }

        return AutoRepairReport(repairs, warnings)
    }

    private fun tableExists(db: SQLiteDatabase, name: String): Boolean {
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(name)
        ).use { return it.moveToFirst() }
    }
}
