package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

/**
 * Read-only holder discovery for context assembly. Legacy SQL is contained behind the Phase-37
 * compatibility/projection boundary so ContextBuilder no longer interprets legacy knowledge rows.
 */
internal object KnowledgeContextHolderDiscovery {
    fun characterHolderUids(db: SQLiteDatabase, campaignUid: String, limit: Int = 20): List<String> {
        require(campaignUid.isNotBlank())
        val bounded = limit.coerceIn(1, 100)
        val out = LinkedHashSet<String>()
        if (Phase37KnowledgeSchema.isReady(db)) {
            db.rawQuery(
                """SELECT holder_uid FROM ${Phase37KnowledgeSchema.STATES}
                    WHERE campaign_uid=? AND holder_kind_uid=?
                    ORDER BY updated_order DESC,holder_uid LIMIT $bounded""".trimIndent(),
                arrayOf(campaignUid, KnowledgeHolderKinds.CHARACTER)
            ).use { c -> while (c.moveToNext()) out += c.getString(0) }
        }
        if (out.size < bounded && table(db, "information_knowledge")) {
            val remaining = bounded - out.size
            runCatching {
                db.rawQuery(
                    "SELECT holder_uid FROM information_knowledge ORDER BY confidence DESC,learned_chapter DESC,holder_uid LIMIT $remaining",
                    null
                ).use { c -> while (c.moveToNext()) out += c.getString(0) }
            }
        }
        return out.take(bounded)
    }

    private fun table(db: SQLiteDatabase, name: String): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1", arrayOf(name)
    ).use { it.moveToFirst() }
}
