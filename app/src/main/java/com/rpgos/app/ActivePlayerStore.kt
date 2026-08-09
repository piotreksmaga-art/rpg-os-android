package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

/**
 * Persists the single authoritative player identity for a campaign.
 * Legacy heuristics are used only once to seed an old save; after that the
 * persisted player UID is the source of truth.
 */
class ActivePlayerStore(
    private val db: SQLiteDatabase,
    private val campaignId: String
) {
    fun active(): ActivePlayerRef? {
        db.rawQuery(
            "SELECT player_uid FROM active_player_ref WHERE campaign_id=? LIMIT 1",
            arrayOf(campaignId)
        ).use { c ->
            if (!c.moveToFirst()) return null
            val uid = c.getString(0)?.trim().orEmpty()
            return if (uid.isBlank()) null else ActivePlayerRef(campaignId, uid)
        }
    }

    fun requireActive(): ActivePlayerRef =
        active() ?: error("No active player configured for campaign $campaignId")

    fun set(playerUid: String): ActivePlayerRef {
        val ref = ActivePlayerRef(campaignId, playerUid.trim())
        PlayerStatePolicy.validate(ref)
        db.execSQL(
            """
            INSERT INTO active_player_ref(campaign_id,player_uid,updated_at)
            VALUES(?,?,strftime('%s','now'))
            ON CONFLICT(campaign_id) DO UPDATE SET
                player_uid=excluded.player_uid,
                updated_at=excluded.updated_at
            """.trimIndent(),
            arrayOf(ref.campaignId, ref.playerUid)
        )
        return ref
    }

    /**
     * One-time deterministic migration helper for legacy saves.
     * This is intentionally kept out of normal runtime selection logic.
     */
    fun seedFromLegacyIfMissing(): ActivePlayerRef? {
        active()?.let { return it }
        val candidate = legacyCandidate() ?: return null
        return set(candidate)
    }

    private fun legacyCandidate(): String? {
        val queries = listOf(
            "SELECT entity_uid FROM character_skills GROUP BY entity_uid ORDER BY COUNT(*) DESC, entity_uid ASC LIMIT 1",
            "SELECT entity_uid FROM character_techniques GROUP BY entity_uid ORDER BY COUNT(*) DESC, entity_uid ASC LIMIT 1",
            "SELECT entity_uid FROM character_finances ORDER BY entity_uid ASC LIMIT 1",
            "SELECT entity_uid FROM entity_positions ORDER BY updated_chapter DESC, entity_uid ASC LIMIT 1"
        )
        for (sql in queries) {
            try {
                db.rawQuery(sql, null).use { c ->
                    if (c.moveToFirst()) {
                        val uid = c.getString(0)?.trim().orEmpty()
                        if (uid.isNotBlank()) return uid
                    }
                }
            } catch (_: Throwable) {
                // Legacy schemas differ. Move to the next deterministic source.
            }
        }
        return null
    }
}
