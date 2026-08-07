package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

class TechniqueMissionReader(
    private val worldDb: SQLiteDatabase,
    private val saveDb: SQLiteDatabase
) {
    fun techniques(search: String = ""): List<TechniqueBrowserItem> {
        val out = mutableListOf<TechniqueBrowserItem>()
        val q = if (search.isBlank()) {
            """SELECT name,category,COALESCE(rank,''),COALESCE(element_key,''),COALESCE(wiki_url,''),COALESCE(verification_status,'')
               FROM canon_technique_index ORDER BY category,name LIMIT 500"""
        } else {
            """SELECT name,category,COALESCE(rank,''),COALESCE(element_key,''),COALESCE(wiki_url,''),COALESCE(verification_status,'')
               FROM canon_technique_index
               WHERE lower(name) LIKE lower(?)
               ORDER BY category,name LIMIT 500"""
        }
        val args = if (search.isBlank()) null else arrayOf("%$search%")
        worldDb.rawQuery(q,args).use { c ->
            while (c.moveToNext()) {
                out += TechniqueBrowserItem(
                    c.getString(0), c.getString(1), c.getString(2),
                    c.getString(3), c.getString(4), c.getString(5)
                )
            }
        }
        return out
    }

    fun missions(): List<MissionBrowserItem> {
        val out = mutableListOf<MissionBrowserItem>()
        try {
            saveDb.rawQuery(
                """SELECT mission_uid,title,mission_rank,status,reward_ryo,objective_summary
                   FROM missions_v3
                   ORDER BY CASE status WHEN 'active' THEN 0 WHEN 'assigned' THEN 1 ELSE 2 END,
                            mission_rank DESC,reward_ryo DESC LIMIT 200""",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    out += MissionBrowserItem(
                        c.getString(0), c.getString(1), c.getString(2),
                        c.getString(3), c.getString(4), c.getString(5)
                    )
                }
            }
        } catch (_: Exception) {}
        return out
    }
}
