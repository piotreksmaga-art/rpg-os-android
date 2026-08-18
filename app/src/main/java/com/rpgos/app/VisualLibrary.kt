package com.rpgos.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.util.UUID

data class VisualRecord(
    val visualUid: String,
    val title: String,
    val kind: String,
    val uri: String,
    val chapter: Int?,
    val relatedEntityUid: String?,
    val relatedLocationUid: String?,
    val prompt: String?,
    val revisedPrompt: String?,
    val createdAt: Long,
    val sourceVisualUid: String?
)

class VisualLibrary(private val db: SQLiteDatabase) {

    /** Explicit migration/bootstrap schema owner. Ordinary reads and presentation writes verify only. */
    internal fun ensureSchema() {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS campaign_visual_library (
                visual_uid TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                kind TEXT NOT NULL,
                uri TEXT NOT NULL,
                chapter INTEGER,
                related_entity_uid TEXT,
                related_location_uid TEXT,
                prompt TEXT,
                revised_prompt TEXT,
                created_at INTEGER NOT NULL,
                source_visual_uid TEXT,
                status TEXT NOT NULL DEFAULT 'active'
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_visual_chapter ON campaign_visual_library(chapter)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_visual_entity ON campaign_visual_library(related_entity_uid)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_visual_location ON campaign_visual_library(related_location_uid)")
    }

    fun add(
        title: String,
        kind: String,
        uri: String,
        chapter: Int?,
        relatedEntityUid: String?,
        relatedLocationUid: String?,
        prompt: String?,
        revisedPrompt: String?,
        sourceVisualUid: String? = null
    ): String {
        requireSchemaReady()
        val uid = "VIS-" + UUID.randomUUID().toString()
        val cv = ContentValues().apply {
            put("visual_uid", uid)
            put("title", title)
            put("kind", kind)
            put("uri", uri)
            if (chapter != null) put("chapter", chapter) else putNull("chapter")
            put("related_entity_uid", relatedEntityUid)
            put("related_location_uid", relatedLocationUid)
            put("prompt", prompt)
            put("revised_prompt", revisedPrompt)
            put("created_at", System.currentTimeMillis())
            put("source_visual_uid", sourceVisualUid)
            put("status", "active")
        }
        db.insertOrThrow("campaign_visual_library", null, cv)
        return uid
    }

    fun list(limit: Int = 200): List<VisualRecord> {
        requireSchemaReady()
        val out = mutableListOf<VisualRecord>()
        db.rawQuery(
            """SELECT visual_uid,title,kind,uri,chapter,related_entity_uid,related_location_uid,
                      prompt,revised_prompt,created_at,source_visual_uid
               FROM campaign_visual_library
               WHERE status='active'
               ORDER BY created_at DESC LIMIT ?""",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += VisualRecord(
                    visualUid = c.getString(0),
                    title = c.getString(1),
                    kind = c.getString(2),
                    uri = c.getString(3),
                    chapter = if (c.isNull(4)) null else c.getInt(4),
                    relatedEntityUid = if (c.isNull(5)) null else c.getString(5),
                    relatedLocationUid = if (c.isNull(6)) null else c.getString(6),
                    prompt = if (c.isNull(7)) null else c.getString(7),
                    revisedPrompt = if (c.isNull(8)) null else c.getString(8),
                    createdAt = c.getLong(9),
                    sourceVisualUid = if (c.isNull(10)) null else c.getString(10)
                )
            }
        }
        return out
    }

    fun get(uid: String): VisualRecord? =
        list(500).firstOrNull { it.visualUid == uid }

    private fun requireSchemaReady() {
        require(db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='campaign_visual_library' LIMIT 1",
            null
        ).use { it.moveToFirst() }) { "RPGOS-G32:VISUAL_LIBRARY_SCHEMA_NOT_READY" }
    }
}
