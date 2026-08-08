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
                        addStatusLine(identity, resources, name, value)
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

        // GM141 is authoritative once a field exists there. Legacy tables remain
        // a read fallback during migration, never a competing source of truth.
        overlayGm141(identity, stats, resources, skills, techniques)

        return CharacterPanelSnapshot(identity, stats, resources, skills, techniques, equipment, relationships, goals)
    }

    private fun overlayGm141(
        identity: MutableList<StatLine>,
        stats: MutableList<StatLine>,
        resources: MutableList<StatLine>,
        skills: MutableList<SkillLine>,
        techniques: MutableList<TechniqueLine>
    ) {
        if (!tableExists("gm_entity_state")) return
        val playerUid = resolvePlayerUid() ?: return

        runCatching {
            db.rawQuery(
                """
                SELECT field_key,value_json
                FROM gm_entity_state
                WHERE entity_type='CHARACTER' AND entity_id=?
                ORDER BY field_key
                """.trimIndent(),
                arrayOf(playerUid)
            ).use { c ->
                while (c.moveToNext()) {
                    val field = c.getString(0) ?: continue
                    val value = c.getString(1) ?: continue
                    when {
                        field.startsWith("stat.") ->
                            upsertStat(stats, field.removePrefix("stat."), value)

                        field.startsWith("status.") -> {
                            val key = field.removePrefix("status.")
                            removeStat(identity, key)
                            removeStat(resources, key)
                            addStatusLine(identity, resources, key, value)
                        }

                        field.startsWith("skill.") && field.endsWith(".mastery") -> {
                            val uid = field.removePrefix("skill.").removeSuffix(".mastery")
                            overlaySkill(skills, uid, value)
                        }

                        field.startsWith("technique.") && field.endsWith(".mastery") -> {
                            val uid = field.removePrefix("technique.").removeSuffix(".mastery")
                            overlayTechnique(techniques, uid, value)
                        }
                    }
                }
            }
        }
    }

    private fun overlaySkill(skills: MutableList<SkillLine>, uid: String, mastery: String) {
        val definition = queryDefinition("skill_definitions", "skill_uid", uid, "name", "category")
        val name = definition?.first ?: uid
        val category = definition?.second ?: "GM141"
        val index = skills.indexOfFirst { it.name == name }
        val resolved = SkillLine(name, mastery, category)
        if (index >= 0) skills[index] = resolved else skills += resolved
    }

    private fun overlayTechnique(techniques: MutableList<TechniqueLine>, uid: String, mastery: String) {
        val definition = queryDefinition("technique_definitions", "technique_uid", uid, "name", "category")
        val name = definition?.first ?: uid
        val category = definition?.second ?: "GM141"
        val index = techniques.indexOfFirst { it.name == name }
        if (index >= 0) {
            techniques[index] = techniques[index].copy(mastery = mastery)
        } else {
            techniques += TechniqueLine(name, mastery, "—", category)
        }
    }

    private fun queryDefinition(
        table: String,
        keyColumn: String,
        uid: String,
        nameColumn: String,
        categoryColumn: String
    ): Pair<String, String>? = runCatching {
        db.rawQuery(
            "SELECT $nameColumn,$categoryColumn FROM $table WHERE $keyColumn=? LIMIT 1",
            arrayOf(uid)
        ).use { c ->
            if (!c.moveToFirst()) null else (c.getString(0) to c.getString(1))
        }
    }.getOrNull()

    private fun resolvePlayerUid(): String? {
        val candidates = listOf(
            "SELECT entity_id FROM gm_entity_state WHERE entity_type='CHARACTER' GROUP BY entity_id ORDER BY COUNT(*) DESC LIMIT 1",
            "SELECT entity_uid FROM character_skills GROUP BY entity_uid ORDER BY COUNT(*) DESC LIMIT 1",
            "SELECT entity_uid FROM character_techniques GROUP BY entity_uid ORDER BY COUNT(*) DESC LIMIT 1",
            "SELECT entity_uid FROM character_finances LIMIT 1",
            "SELECT entity_uid FROM entity_positions ORDER BY updated_chapter DESC LIMIT 1"
        )
        for (sql in candidates) {
            val value = runCatching {
                db.rawQuery(sql, null).use { c ->
                    if (c.moveToFirst()) c.getString(0)?.trim()?.takeIf { it.isNotEmpty() } else null
                }
            }.getOrNull()
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun tableExists(name: String): Boolean = runCatching {
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(name)
        ).use { it.moveToFirst() }
    }.getOrDefault(false)

    private fun addStatusLine(
        identity: MutableList<StatLine>,
        resources: MutableList<StatLine>,
        name: String,
        value: String
    ) {
        if (isResource(name)) resources += StatLine(name, value)
        else identity += StatLine(name, value)
    }

    private fun isResource(name: String): Boolean =
        name.contains("chakra", true) || name.contains("stamina", true) || name.contains("energy", true)

    private fun upsertStat(lines: MutableList<StatLine>, key: String, value: String) {
        val index = lines.indexOfFirst { it.key == key }
        val resolved = StatLine(key, value)
        if (index >= 0) lines[index] = resolved else lines += resolved
    }

    private fun removeStat(lines: MutableList<StatLine>, key: String) {
        lines.removeAll { it.key == key }
    }
}
