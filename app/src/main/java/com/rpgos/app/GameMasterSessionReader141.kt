package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

data class GameMasterSessionSnapshot141(
    val campaignUid: String,
    val worldPackUid: String?,
    val currentTurn: Long,
    val currentChapter: Long,
    val playerUid: String?,
    val locationUid: String?,
    val stateFieldCount: Int,
    val eventCount: Int,
    val factCount: Int,
    val memoryCount: Int,
    val activeDivergenceCount: Int,
    val consistencyWarnings: List<String>
)

/** Lightweight read model for diagnostics and UI status surfaces. */
class GameMasterSessionReader141(private val db: SQLiteDatabase) {
    fun read(): GameMasterSessionSnapshot141? {
        if (!tableExists("gm_campaign_meta")) return null
        val meta = db.rawQuery(
            "SELECT campaign_id,world_pack_id,current_turn,current_chapter FROM gm_campaign_meta LIMIT 1",
            null
        ).use { c ->
            if (!c.moveToFirst()) return null
            Meta(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3))
        }

        val playerUid = resolvePlayerUid()
        val gmLocation = playerUid?.let {
            scalarString(
                """
                SELECT value_json FROM gm_entity_state
                WHERE campaign_id=? AND entity_type='CHARACTER' AND entity_id=?
                  AND field_key='position.location_uid'
                LIMIT 1
                """.trimIndent(),
                arrayOf(meta.campaignUid, it)
            )
        }
        val legacyLocation = playerUid?.let {
            scalarString(
                "SELECT location_uid FROM entity_positions WHERE entity_uid=? LIMIT 1",
                arrayOf(it)
            )
        }

        val warnings = mutableListOf<String>()
        if (!gmLocation.isNullOrBlank() && !legacyLocation.isNullOrBlank() && gmLocation != legacyLocation) {
            warnings += "LOCATION_DIVERGENCE: legacy=$legacyLocation gm141=$gmLocation"
        }
        if (meta.currentTurn < 0L) warnings += "NEGATIVE_CURRENT_TURN"

        return GameMasterSessionSnapshot141(
            campaignUid = meta.campaignUid,
            worldPackUid = meta.worldPackUid,
            currentTurn = meta.currentTurn,
            currentChapter = meta.currentChapter,
            playerUid = playerUid,
            locationUid = gmLocation ?: legacyLocation,
            stateFieldCount = count("gm_entity_state", meta.campaignUid),
            eventCount = count("gm_events", meta.campaignUid),
            factCount = count("gm_facts", meta.campaignUid),
            memoryCount = count("gm_memories", meta.campaignUid),
            activeDivergenceCount = count("gm_divergences", meta.campaignUid, "active=1"),
            consistencyWarnings = warnings
        )
    }

    fun status(base: StatusSnapshot = StatusSnapshot()): StatusSnapshot {
        val session = read() ?: return base
        return base.copy(location = session.locationUid ?: base.location)
    }

    private fun resolvePlayerUid(): String? {
        val candidates = listOf(
            "SELECT entity_id FROM gm_entity_state WHERE entity_type='CHARACTER' GROUP BY entity_id ORDER BY COUNT(*) DESC LIMIT 1",
            "SELECT entity_uid FROM character_skills GROUP BY entity_uid ORDER BY COUNT(*) DESC LIMIT 1",
            "SELECT entity_uid FROM character_techniques GROUP BY entity_uid ORDER BY COUNT(*) DESC LIMIT 1",
            "SELECT entity_uid FROM character_finances LIMIT 1",
            "SELECT entity_uid FROM entity_positions ORDER BY updated_chapter DESC LIMIT 1"
        )
        for (sql in candidates) {
            val uid = scalarString(sql)
            if (!uid.isNullOrBlank()) return uid
        }
        return null
    }

    private fun count(table: String, campaignUid: String, extraWhere: String? = null): Int {
        if (!tableExists(table)) return 0
        val where = buildString {
            append("campaign_id=?")
            if (!extraWhere.isNullOrBlank()) append(" AND ").append(extraWhere)
        }
        return runCatching {
            db.rawQuery("SELECT COUNT(*) FROM $table WHERE $where", arrayOf(campaignUid)).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        }.getOrDefault(0)
    }

    private fun scalarString(sql: String, args: Array<String>? = null): String? = runCatching {
        db.rawQuery(sql, args).use { c ->
            if (!c.moveToFirst() || c.isNull(0)) null
            else c.getString(0)?.trim()?.takeIf { it.isNotEmpty() }
        }
    }.getOrNull()

    private fun tableExists(name: String): Boolean = runCatching {
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(name)
        ).use { it.moveToFirst() }
    }.getOrDefault(false)

    private data class Meta(
        val campaignUid: String,
        val worldPackUid: String?,
        val currentTurn: Long,
        val currentChapter: Long
    )
}
