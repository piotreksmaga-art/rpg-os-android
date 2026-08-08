package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

data class GameMasterSessionSnapshot141(
    val campaignUid: String,
    val worldPackUid: String?,
    val currentTurn: Long,
    val currentChapter: Long,
    val playerUid: String?,
    val locationUid: String?,
    val time: TimeSnapshot?,
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
            stateValue(meta.campaignUid, "CHARACTER", it, "position.location_uid")
        }
        val legacyLocation = playerUid?.let {
            scalarString(
                "SELECT location_uid FROM entity_positions WHERE entity_uid=? LIMIT 1",
                arrayOf(it)
            )
        }

        val legacyTime = readLegacyTime()
        val gmTime = readGmTime(meta.campaignUid, legacyTime)

        val warnings = mutableListOf<String>()
        if (!gmLocation.isNullOrBlank() && !legacyLocation.isNullOrBlank() && gmLocation != legacyLocation) {
            warnings += "LOCATION_DIVERGENCE: legacy=$legacyLocation gm141=$gmLocation"
        }
        if (legacyTime != null && gmTime != null) {
            if (legacyTime.label != gmTime.label) {
                warnings += "TIME_LABEL_DIVERGENCE: legacy=${legacyTime.label} gm141=${gmTime.label}"
            }
            if (legacyTime.era != gmTime.era) {
                warnings += "TIME_ERA_DIVERGENCE: legacy=${legacyTime.era} gm141=${gmTime.era}"
            }
            if (legacyTime.season != gmTime.season) {
                warnings += "TIME_SEASON_DIVERGENCE: legacy=${legacyTime.season} gm141=${gmTime.season}"
            }
            if (legacyTime.hour != gmTime.hour) {
                warnings += "TIME_CLOCK_DIVERGENCE: legacy=${legacyTime.hour} gm141=${gmTime.hour}"
            }
        }
        if (meta.currentTurn < 0L) warnings += "NEGATIVE_CURRENT_TURN"

        return GameMasterSessionSnapshot141(
            campaignUid = meta.campaignUid,
            worldPackUid = meta.worldPackUid,
            currentTurn = meta.currentTurn,
            currentChapter = meta.currentChapter,
            playerUid = playerUid,
            locationUid = gmLocation ?: legacyLocation,
            time = gmTime ?: legacyTime,
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

    fun time(base: TimeSnapshot = TimeSnapshot()): TimeSnapshot {
        val session = read() ?: return base
        return session.time ?: base
    }

    private fun readGmTime(campaignUid: String, fallback: TimeSnapshot?): TimeSnapshot? {
        if (!tableExists("gm_entity_state")) return null
        val yearLabel = stateValue(campaignUid, "CAMPAIGN", campaignUid, "time.year_label")
        val era = stateValue(campaignUid, "CAMPAIGN", campaignUid, "time.era")
        val season = stateValue(campaignUid, "CAMPAIGN", campaignUid, "time.season")
        val hour = stateValue(campaignUid, "CAMPAIGN", campaignUid, "time.hour")
        val minute = stateValue(campaignUid, "CAMPAIGN", campaignUid, "time.minute")

        if (listOf(yearLabel, era, season, hour, minute).all { it == null }) return null

        val base = fallback ?: TimeSnapshot()
        return TimeSnapshot(
            label = yearLabel ?: base.label,
            era = era ?: base.era,
            season = season ?: base.season,
            hour = formatClock(hour, minute, base.hour)
        )
    }

    private fun readLegacyTime(): TimeSnapshot? = runCatching {
        db.rawQuery(
            "SELECT year_label,era_name,season,hour,minute FROM campaign_calendar WHERE id=1 LIMIT 1",
            null
        ).use { c ->
            if (!c.moveToFirst()) return@use null
            TimeSnapshot(
                label = if (c.isNull(0)) "—" else c.getString(0),
                era = if (c.isNull(1)) "—" else c.getString(1),
                season = if (c.isNull(2)) "—" else c.getString(2),
                hour = "%02d:%02d".format(c.getInt(3), c.getInt(4))
            )
        }
    }.getOrNull()

    private fun formatClock(hour: String?, minute: String?, fallback: String): String {
        if (hour == null && minute == null) return fallback
        val h = hour?.toIntOrNull()
        val m = minute?.toIntOrNull()
        if (h == null || m == null || h !in 0..23 || m !in 0..59) return fallback
        return "%02d:%02d".format(h, m)
    }

    private fun stateValue(
        campaignUid: String,
        entityType: String,
        entityId: String,
        field: String
    ): String? = scalarString(
        """
        SELECT value_json FROM gm_entity_state
        WHERE campaign_id=? AND entity_type=? AND entity_id=? AND field_key=?
        LIMIT 1
        """.trimIndent(),
        arrayOf(campaignUid, entityType, entityId, field)
    )

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
