package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant

class ChapterSaveManager(
    private val db: SQLiteDatabase
) {
    fun finalizeChapter(chapter: Int, title: String): String {
        val threads = queryIds("SELECT thread_uid FROM story_threads WHERE status='active'", "thread_uid")
        val decisions = queryIds("SELECT decision_uid FROM decision_points WHERE chapter=$chapter", "decision_uid")
        val consequences = queryIds(
            "SELECT consequence_uid FROM consequence_links WHERE status='pending'",
            "consequence_uid"
        )
        val quests = queryIds("SELECT quest_uid FROM quests_v2 WHERE status='active'", "quest_uid")
        val warnings = mutableListOf<Map<String, Any?>>()
        db.rawQuery(
            "SELECT check_type,severity,subject_uid,message FROM continuity_checks WHERE chapter=?",
            arrayOf(chapter.toString())
        ).use { c ->
            val names = c.columnNames
            while (c.moveToNext()) {
                val row = linkedMapOf<String, Any?>()
                for (i in names.indices) row[names[i]] = if (c.isNull(i)) null else c.getString(i)
                warnings += row
            }
        }

        val state = JSONObject().apply {
            put("threads", JSONArray(threads))
            put("decisions", JSONArray(decisions))
            put("consequences", JSONArray(consequences))
            put("quests", JSONArray(quests))
        }.toString()

        val hash = sha256(state)
        val cv = android.content.ContentValues().apply {
            put("chapter", chapter)
            put("title", title)
            put("opening_state_hash", hash)
            put("closing_state_hash", hash)
            put("active_threads_json", JSONArray(threads).toString())
            put("decisions_json", JSONArray(decisions).toString())
            put("consequences_json", JSONArray(consequences).toString())
            put("quests_json", JSONArray(quests).toString())
            put("continuity_warnings_json", JSONArray(warnings.map { JSONObject(it) }).toString())
            put("created_at", Instant.now().toString())
        }
        db.insertWithOnConflict(
            "chapter_manifests_v2",
            null,
            cv,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        return hash
    }

    private fun queryIds(sql: String, column: String): List<String> {
        val out = mutableListOf<String>()
        db.rawQuery(sql, null).use { c ->
            val idx = c.getColumnIndexOrThrow(column)
            while (c.moveToNext()) out += c.getString(idx)
        }
        return out
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
