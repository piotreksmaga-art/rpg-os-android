package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

data class StatLine(val key: String, val value: String)
data class SkillLine(val name: String, val mastery: String, val category: String)
data class TechniqueLine(val name: String, val mastery: String, val chakraCost: String, val category: String)

data class CharacterPanelSnapshot(
    val identity: List<StatLine>,
    val stats: List<StatLine>,
    val resources: List<StatLine>,
    val skills: List<SkillLine>,
    val techniques: List<TechniqueLine>,
    val equipment: List<String>,
    val relationships: List<String>,
    val goals: List<String>
)

class CharacterPanelReader(
    private val db: SQLiteDatabase,
    private val playerUid: String? = null
) {

    fun load(): CharacterPanelSnapshot {
        val identity = mutableListOf<StatLine>()
        val stats = mutableListOf<StatLine>()
        val resources = mutableListOf<StatLine>()
        val skills = mutableListOf<SkillLine>()
        val techniques = mutableListOf<TechniqueLine>()
        val equipment = mutableListOf<String>()
        val relationships = mutableListOf<String>()
        val goals = mutableListOf<String>()

        try {
            val hasEntityUid = hasColumn("character_status_snapshot", "entity_uid")
            val sql = if (playerUid != null && hasEntityUid)
                "SELECT * FROM character_status_snapshot WHERE entity_uid=? LIMIT 1"
            else
                "SELECT * FROM character_status_snapshot LIMIT 1"
            val args = if (playerUid != null && hasEntityUid) arrayOf(playerUid) else null
            db.rawQuery(sql, args).use { c ->
                if (c.moveToFirst()) {
                    for (i in c.columnNames.indices) {
                        val name = c.columnNames[i]
                        val value = if (c.isNull(i)) "—" else c.getString(i)
                        if (name.contains("chakra", true) || name.contains("stamina", true) || name.contains("energy", true))
                            resources += StatLine(name, value)
                        else
                            identity += StatLine(name, value)
                    }
                }
            }
        } catch (_: Exception) {}

        try {
            val hasEntityUid = hasColumn("character_stats", "entity_uid")
            val sql = if (playerUid != null && hasEntityUid)
                "SELECT stat_key,current_value FROM character_stats WHERE entity_uid=? ORDER BY stat_key"
            else
                "SELECT stat_key,current_value FROM character_stats ORDER BY stat_key"
            val args = if (playerUid != null && hasEntityUid) arrayOf(playerUid) else null
            db.rawQuery(sql, args).use { c ->
                while (c.moveToNext()) stats += StatLine(c.getString(0), c.getString(1))
            }
        } catch (_: Exception) {}

        try {
            val where = if (playerUid != null) "WHERE cs.entity_uid=?" else ""
            val args = if (playerUid != null) arrayOf(playerUid) else null
            db.rawQuery(
                """SELECT s.name,cs.mastery,s.category
                   FROM character_skills cs
                   JOIN skill_definitions s ON s.skill_uid=cs.skill_uid
                   $where
                   ORDER BY s.category,s.name""",
                args
            ).use { c ->
                while (c.moveToNext()) {
                    skills += SkillLine(c.getString(0), c.getString(1), c.getString(2))
                }
            }
        } catch (_: Exception) {}

        try {
            val where = if (playerUid != null) "WHERE ct.entity_uid=?" else ""
            val args = if (playerUid != null) arrayOf(playerUid) else null
            db.rawQuery(
                """SELECT t.name,ct.mastery,COALESCE(ct.chakra_cost_override,t.base_chakra_cost),t.category
                   FROM character_techniques ct
                   JOIN technique_definitions t ON t.technique_uid=ct.technique_uid
                   $where
                   ORDER BY t.category,t.name""",
                args
            ).use { c ->
                while (c.moveToNext()) {
                    techniques += TechniqueLine(
                        c.getString(0),
                        c.getString(1),
                        if (c.isNull(2)) "—" else c.getString(2),
                        c.getString(3)
                    )
                }
            }
        } catch (_: Exception) {}

        try {
            val hasEntityUid = hasColumn("character_inventory", "entity_uid")
            val sql = if (playerUid != null && hasEntityUid)
                "SELECT item_name FROM character_inventory WHERE entity_uid=? ORDER BY item_name"
            else
                "SELECT item_name FROM character_inventory ORDER BY item_name"
            val args = if (playerUid != null && hasEntityUid) arrayOf(playerUid) else null
            db.rawQuery(sql, args).use { c ->
                while (c.moveToNext()) equipment += c.getString(0)
            }
        } catch (_: Exception) {}

        try {
            val hasEntityUid = hasColumn("relationships_v2", "entity_uid")
            val sql = if (playerUid != null && hasEntityUid)
                "SELECT other_entity_uid,relationship_type,relationship_score FROM relationships_v2 WHERE entity_uid=? ORDER BY ABS(relationship_score) DESC LIMIT 30"
            else
                "SELECT other_entity_uid,relationship_type,relationship_score FROM relationships_v2 ORDER BY ABS(relationship_score) DESC LIMIT 30"
            val args = if (playerUid != null && hasEntityUid) arrayOf(playerUid) else null
            db.rawQuery(sql, args).use { c ->
                while (c.moveToNext()) {
                    relationships += "${c.getString(0)} • ${c.getString(1)} • ${c.getString(2)}"
                }
            }
        } catch (_: Exception) {}

        try {
            val hasEntityUid = hasColumn("character_goals", "entity_uid")
            val sql = if (playerUid != null && hasEntityUid)
                "SELECT title FROM character_goals WHERE entity_uid=? AND status='active' ORDER BY priority DESC"
            else
                "SELECT title FROM character_goals WHERE status='active' ORDER BY priority DESC"
            val args = if (playerUid != null && hasEntityUid) arrayOf(playerUid) else null
            db.rawQuery(sql, args).use { c ->
                while (c.moveToNext()) goals += c.getString(0)
            }
        } catch (_: Exception) {}

        return CharacterPanelSnapshot(identity, stats, resources, skills, techniques, equipment, relationships, goals)
    }

    private fun hasColumn(table: String, column: String): Boolean = try {
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val nameIndex = c.getColumnIndex("name")
            while (c.moveToNext()) {
                if (nameIndex >= 0 && c.getString(nameIndex).equals(column, ignoreCase = true)) return@use true
            }
            false
        }
    } catch (_: Throwable) {
        false
    }
}
