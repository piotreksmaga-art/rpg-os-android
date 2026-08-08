package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

class WorldReader(
    private val worldDb: SQLiteDatabase,
    private val saveDb: SQLiteDatabase
) {
    fun regions(): List<WorldRegionItem> {
        val out = mutableListOf<WorldRegionItem>()
        try {
            worldDb.rawQuery(
                "SELECT region_uid,name,region_type,COALESCE(description,'') FROM map_regions_v2 ORDER BY name",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    out += WorldRegionItem(c.getString(0),c.getString(1),c.getString(2),c.getString(3))
                }
            }
        } catch (_: Exception) {}
        return out
    }

    fun locations(search: String = ""): List<WorldLocationItem> {
        val out = mutableListOf<WorldLocationItem>()
        try {
            val sql = if (search.isBlank())
                """SELECT location_uid,name,location_type,COALESCE(region_uid,''),COALESCE(description,'')
                   FROM map_locations_v2 ORDER BY name LIMIT 500"""
            else
                """SELECT location_uid,name,location_type,COALESCE(region_uid,''),COALESCE(description,'')
                   FROM map_locations_v2 WHERE lower(name) LIKE lower(?) ORDER BY name LIMIT 500"""
            val args = if(search.isBlank()) null else arrayOf("%$search%")
            worldDb.rawQuery(sql,args).use { c ->
                while (c.moveToNext()) {
                    out += WorldLocationItem(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4))
                }
            }
        } catch (_: Exception) {}
        return out
    }

    fun activeEvents(): List<WorldEventItem> {
        val out = mutableListOf<WorldEventItem>()
        try {
            saveDb.rawQuery(
                """SELECT COALESCE(t.name,a.event_type),a.status,COALESCE(a.public_summary,a.gm_summary,'')
                   FROM active_world_events a
                   LEFT JOIN timeline_events t ON t.timeline_uid=a.timeline_uid
                   WHERE a.status='active'
                   ORDER BY a.started_day DESC LIMIT 100""",
                null
            ).use { c ->
                while(c.moveToNext()) out += WorldEventItem(c.getString(0),c.getString(1),c.getString(2))
            }
        } catch (_: Exception) {}

        // GM141 event history is append-only. Only event classes that describe
        // broad world/timeline changes are projected into this dashboard; combat
        // and low-level state events stay in the event store and chronicle.
        try {
            saveDb.rawQuery(
                """
                SELECT event_type,description,turn_number
                FROM gm_events
                WHERE event_type IN ('WORLD_EVENT','POLITICAL_CHANGE','TIME_SKIP','LOCATION_DISCOVERED')
                ORDER BY turn_number DESC,sequence DESC
                LIMIT 100
                """.trimIndent(),
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val type = c.getString(0) ?: "WORLD_EVENT"
                    val description = c.getString(1) ?: ""
                    val turn = c.getLong(2)
                    out += WorldEventItem(
                        type.replace('_', ' '),
                        "GM141 • tura $turn",
                        description
                    )
                }
            }
        } catch (_: Exception) {}

        return out
    }
}
