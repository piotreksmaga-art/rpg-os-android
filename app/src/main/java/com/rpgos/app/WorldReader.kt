package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

class WorldReader(
    private val worldDb: SQLiteDatabase,
    private val saveDb: SQLiteDatabase,
    private val visibility: VisibilityAuthorityService = VisibilityAuthorityService()
) {
    fun regions(): List<WorldRegionItem> {
        val out = mutableListOf<WorldRegionItem>()
        worldDb.rawQuery(
            "SELECT region_uid,name,region_type,COALESCE(description,'') FROM map_regions_v2 ORDER BY name",
            null
        ).use { c -> while (c.moveToNext()) out += WorldRegionItem(c.getString(0), c.getString(1), c.getString(2), c.getString(3)) }
        return out
    }

    fun locations(search: String = ""): List<WorldLocationItem> {
        val out = mutableListOf<WorldLocationItem>()
        val sql = if (search.isBlank())
            """SELECT location_uid,name,location_type,COALESCE(region_uid,''),COALESCE(description,'')
               FROM map_locations_v2 ORDER BY name LIMIT 500"""
        else
            """SELECT location_uid,name,location_type,COALESCE(region_uid,''),COALESCE(description,'')
               FROM map_locations_v2 WHERE lower(name) LIKE lower(?) ORDER BY name LIMIT 500"""
        val args = if (search.isBlank()) null else arrayOf("%$search%")
        worldDb.rawQuery(sql, args).use { c -> while (c.moveToNext()) out += WorldLocationItem(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4)) }
        return out
    }

    fun activeEvents(audience: AudienceContext, purpose: PurposeContext): List<WorldEventItem> =
        activeEventsProjection(audience, purpose).value ?: emptyList()

    fun activeEventsProjection(audience: AudienceContext, purpose: PurposeContext): VisibilityProjection<List<WorldEventItem>> {
        val diagnostic = audience.audienceKindUid == AudienceKinds.DEVELOPER_DIAGNOSTIC && purpose.purposeUid == VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION
        val subjectKind = if (diagnostic) VisibilitySubjectKinds.WORLD_EVENT_GM_DETAIL else VisibilitySubjectKinds.PUBLIC_WORLD_EVENT
        val request = VisibilityRequest(audience, purpose, VisibilitySubjectRef(audience.campaignUid, subjectKind, "ACTIVE_WORLD_EVENTS"))
        return visibility.projectList(request) {
            val out = mutableListOf<WorldEventItem>()
            val summaryExpr = if (diagnostic) "COALESCE(a.public_summary,a.gm_summary,'')" else "COALESCE(a.public_summary,'')"
            saveDb.rawQuery(
                """SELECT COALESCE(t.name,a.event_type),a.status,$summaryExpr
                   FROM active_world_events a
                   LEFT JOIN timeline_events t ON t.timeline_uid=a.timeline_uid
                   WHERE a.status='active'
                   ORDER BY a.started_day DESC LIMIT 100""",
                null
            ).use { c -> while (c.moveToNext()) out += WorldEventItem(c.getString(0), c.getString(1), c.getString(2)) }
            out
        }
    }
}
