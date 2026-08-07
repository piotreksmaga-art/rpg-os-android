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

class CharacterPanelReader(private val db: SQLiteDatabase) {

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
            db.rawQuery("SELECT * FROM character_status_snapshot LIMIT 1", null).use { c ->
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
            db.rawQuery(
                "SELECT stat_key,current_value FROM character_stats ORDER BY stat_key",
                null
            ).use { c ->
                while (c.moveToNext()) stats += StatLine(c.getString(0), c.getString(1))
            }
        } catch (_: Exception) {}

        try {
            db.rawQuery(
                """SELECT s.name,cs.mastery,s.category
                   FROM character_skills cs
                   JOIN skill_definitions s ON s.skill_uid=cs.skill_uid
                   ORDER BY s.category,s.name""",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    skills += SkillLine(c.getString(0), c.getString(1), c.getString(2))
                }
            }
        } catch (_: Exception) {}

        try {
            db.rawQuery(
                """SELECT t.name,ct.mastery,COALESCE(ct.chakra_cost_override,t.base_chakra_cost),t.category
                   FROM character_techniques ct
                   JOIN technique_definitions t ON t.technique_uid=ct.technique_uid
                   ORDER BY t.category,t.name""",
                null
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
            db.rawQuery("SELECT item_name FROM character_inventory ORDER BY item_name", null).use { c ->
                while (c.moveToNext()) equipment += c.getString(0)
            }
        } catch (_: Exception) {}

        try {
            db.rawQuery(
                "SELECT other_entity_uid,relationship_type,relationship_score FROM relationships_v2 ORDER BY ABS(relationship_score) DESC LIMIT 30",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    relationships += "${c.getString(0)} • ${c.getString(1)} • ${c.getString(2)}"
                }
            }
        } catch (_: Exception) {}

        try {
            db.rawQuery(
                "SELECT title FROM character_goals WHERE status='active' ORDER BY priority DESC",
                null
            ).use { c ->
                while (c.moveToNext()) goals += c.getString(0)
            }
        } catch (_: Exception) {}

        return CharacterPanelSnapshot(identity, stats, resources, skills, techniques, equipment, relationships, goals)
    }
}
