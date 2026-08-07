package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

class SocialReader(
    private val worldDb: SQLiteDatabase,
    private val saveDb: SQLiteDatabase
) {
    fun relationships(): List<RelationshipItem> {
        val out = mutableListOf<RelationshipItem>()
        try {
            saveDb.rawQuery(
                """SELECT other_entity_uid,relationship_type,relationship_score
                   FROM relationships_v2
                   ORDER BY ABS(relationship_score) DESC LIMIT 100""",
                null
            ).use { c ->
                while (c.moveToNext()) out += RelationshipItem(c.getString(0),c.getString(1),c.getString(2))
            }
        } catch (_: Exception) {}
        return out
    }

    fun organizations(): List<OrganizationItem> {
        val out = mutableListOf<OrganizationItem>()
        try {
            worldDb.rawQuery(
                """SELECT organization_uid,name,organization_type,active_status
                   FROM organization_definitions_v3 ORDER BY name""",
                null
            ).use { c ->
                while(c.moveToNext()) out += OrganizationItem(c.getString(0),c.getString(1),c.getString(2),c.getString(3))
            }
        } catch (_: Exception) {}
        return out
    }

    fun politics(): List<PoliticalItem> {
        val out = mutableListOf<PoliticalItem>()
        try {
            saveDb.rawQuery(
                """SELECT political_uid,display_name,legitimacy,influence,stability
                   FROM political_entities ORDER BY influence DESC""",
                null
            ).use { c ->
                while(c.moveToNext()) out += PoliticalItem(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4))
            }
        } catch (_: Exception) {}
        return out
    }
}
