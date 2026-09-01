package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

data class StatLine(val key: String, val value: String)
data class SkillLine(val name: String, val mastery: String, val category: String)
data class TechniqueLine(val name: String, val mastery: String, val chakraCost: String, val category: String)

internal enum class CharacterPanelDefinitionShape { LEGACY, BUNDLED_WORLD, TYPED_V2, NONE }

internal fun characterPanelSkillDefinitionShape(legacy:Set<String>,typed:Set<String>)=when{
    legacy.containsAll(setOf("skill_uid","name","category"))->CharacterPanelDefinitionShape.LEGACY
    legacy.containsAll(setOf("skill_uid","display_name","category_key"))->CharacterPanelDefinitionShape.BUNDLED_WORLD
    typed.containsAll(setOf("skill_uid","display_name","category"))->CharacterPanelDefinitionShape.TYPED_V2
    else->CharacterPanelDefinitionShape.NONE
}

internal fun characterPanelTechniqueDefinitionShape(legacy:Set<String>,typed:Set<String>)=when{
    legacy.containsAll(setOf("technique_uid","name","category"))->CharacterPanelDefinitionShape.LEGACY
    typed.containsAll(setOf("technique_uid","display_name","category"))->CharacterPanelDefinitionShape.TYPED_V2
    else->CharacterPanelDefinitionShape.NONE
}

data class CharacterPanelSnapshot(
    val identity: List<StatLine>,
    val stats: List<StatLine>,
    val resources: List<StatLine>,
    val skills: List<SkillLine>,
    val techniques: List<TechniqueLine>,
    val equipment: List<String>,
    val relationships: List<String>,
    val goals: List<String>
) {
    companion object {
        fun unresolved(): CharacterPanelSnapshot = CharacterPanelSnapshot(
            identity = listOf(StatLine("status", "PLAYER_NOT_RESOLVED")), stats = emptyList(), resources = emptyList(),
            skills = emptyList(), techniques = emptyList(), equipment = emptyList(), relationships = emptyList(), goals = emptyList()
        )
    }
}

class CharacterPanelReader(
    private val db: SQLiteDatabase,
    private val playerUid: String? = null,
    private val visibility: VisibilityAuthorityService = VisibilityAuthorityService()
) {
    @Deprecated("PLAYER_STATE authorization must come from ProtectedCampaignReadRepository")
    fun load(audience: AudienceContext, purpose: PurposeContext): CharacterPanelSnapshot = CharacterPanelSnapshot.unresolved()

    fun load(audience: AudienceContext, purpose: PurposeContext, playerStateRead: ProtectedReadResult<PlayerStateSnapshot>): CharacterPanelSnapshot {
        val uid = playerUid?.trim().orEmpty()
        if (uid.isBlank() || playerStateRead !is ProtectedReadResult.Allow) return CharacterPanelSnapshot.unresolved()
        if (playerStateRead.value.activePlayer.playerUid != uid || playerStateRead.value.activePlayer.campaignId != audience.campaignUid) return CharacterPanelSnapshot.unresolved()
        return loadProjectedPlayerState(uid, audience, purpose)
    }

    private fun loadProjectedPlayerState(uid: String, audience: AudienceContext, purpose: PurposeContext): CharacterPanelSnapshot {
        val identity = mutableListOf<StatLine>()
        val stats = mutableListOf<StatLine>()
        val resources = mutableListOf<StatLine>()
        val skills = mutableListOf<SkillLine>()
        val techniques = mutableListOf<TechniqueLine>()
        val equipment = mutableListOf<String>()
        val relationships = mutableListOf<String>()
        val goals = mutableListOf<String>()

        readLegacyStatus(uid, identity, resources)
        readEntityRows("character_stats", "entity_uid", uid, "SELECT stat_key,current_value FROM character_stats WHERE entity_uid=? ORDER BY stat_key") { c ->
            while (c.moveToNext()) stats += StatLine(c.getString(0), c.getString(1))
        }
        readCompatibleLegacySkills(uid,skills)
        readCompatibleLegacyTechniques(uid,techniques)
        readEntityRows("character_inventory", "entity_uid", uid, "SELECT item_name FROM character_inventory WHERE entity_uid=? ORDER BY item_name") { c ->
            while (c.moveToNext()) equipment += c.getString(0)
        }

        val relationshipRequest = VisibilityRequest(audience, purpose, VisibilitySubjectRef(audience.campaignUid, VisibilitySubjectKinds.RELATIONSHIP_DATA, uid))
        visibility.projectList(relationshipRequest) {
            val rows = mutableListOf<String>()
            readEntityRows("relationships_v2", "entity_uid", uid, "SELECT other_entity_uid,relationship_type,relationship_score FROM relationships_v2 WHERE entity_uid=? ORDER BY ABS(relationship_score) DESC LIMIT 30") { c ->
                while (c.moveToNext()) rows += "${c.getString(0)} • ${c.getString(1)} • ${c.getString(2)}"
            }
            rows
        }.value?.let(relationships::addAll)

        readEntityRows("character_goals", "entity_uid", uid, "SELECT title FROM character_goals WHERE entity_uid=? AND status='active' ORDER BY priority DESC") { c ->
            while (c.moveToNext()) goals += c.getString(0)
        }
        return CharacterPanelSnapshot(identity, stats, resources, skills, techniques, equipment, relationships, goals)
    }

    private fun readLegacyStatus(uid: String, identity: MutableList<StatLine>, resources: MutableList<StatLine>) {
        if (!tableExists("character_status_snapshot")) return
        val hasEntityUid = hasColumn("character_status_snapshot", "entity_uid")
        if (!hasEntityUid) {
            val count = db.rawQuery("SELECT COUNT(*) FROM character_status_snapshot", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
            if (count != 1L) return
        }
        val sql = if (hasEntityUid) "SELECT * FROM character_status_snapshot WHERE entity_uid=? LIMIT 1" else "SELECT * FROM character_status_snapshot LIMIT 1"
        val args = if (hasEntityUid) arrayOf(uid) else null
        db.rawQuery(sql, args).use { c ->
            if (c.moveToFirst()) for (i in c.columnNames.indices) {
                val name = c.columnNames[i]
                val value = if (c.isNull(i)) "—" else c.getString(i)
                if (name.contains("chakra", true) || name.contains("stamina", true) || name.contains("energy", true)) resources += StatLine(name, value) else identity += StatLine(name, value)
            }
        }
    }

    private inline fun readEntityRows(table: String, entityColumn: String, playerUid: String, sql: String, block: (android.database.Cursor) -> Unit) {
        if (!tableExists(table) || !hasColumn(table, entityColumn)) return
        db.rawQuery(sql, arrayOf(playerUid)).use(block)
    }

    /**
     * The bundled Naruto legacy schema uses display_name/category_key, while genuinely old saves
     * use name/category. The compatibility panel used to assume only the latter and crashed the
     * entire app immediately after a newly created V2 character was committed.
     */
    private fun readCompatibleLegacySkills(uid:String,out:MutableList<SkillLine>){
        if(!tableExists("character_skills")||!hasColumn("character_skills","entity_uid")||!hasColumn("character_skills","mastery"))return
        val sql=when(characterPanelSkillDefinitionShape(columns("skill_definitions"),columns("skill_definitions_v2"))){
            CharacterPanelDefinitionShape.LEGACY->
                "SELECT s.name,cs.mastery,s.category FROM character_skills cs JOIN skill_definitions s ON s.skill_uid=cs.skill_uid WHERE cs.entity_uid=? ORDER BY s.category,s.name"
            CharacterPanelDefinitionShape.BUNDLED_WORLD->
                "SELECT s.display_name,cs.mastery,s.category_key FROM character_skills cs JOIN skill_definitions s ON s.skill_uid=cs.skill_uid WHERE cs.entity_uid=? ORDER BY s.category_key,s.display_name"
            CharacterPanelDefinitionShape.TYPED_V2->
                "SELECT s.display_name,cs.mastery,s.category FROM character_skills cs JOIN skill_definitions_v2 s ON s.skill_uid=cs.skill_uid WHERE cs.entity_uid=? ORDER BY s.category,s.display_name"
            CharacterPanelDefinitionShape.NONE->return
        }
        db.rawQuery(sql,arrayOf(uid)).use{c->while(c.moveToNext())out+=SkillLine(c.getString(0),c.getString(1),if(c.isNull(2))"—" else c.getString(2))}
    }

    private fun readCompatibleLegacyTechniques(uid:String,out:MutableList<TechniqueLine>){
        if(!tableExists("character_techniques")||!hasColumn("character_techniques","entity_uid")||!hasColumn("character_techniques","mastery"))return
        val shape=characterPanelTechniqueDefinitionShape(columns("technique_definitions"),columns("technique_definitions_v2"))
        val sql=when{
            shape==CharacterPanelDefinitionShape.LEGACY&&hasColumn("character_techniques","chakra_cost_override")&&hasColumn("technique_definitions","base_chakra_cost")->
                "SELECT t.name,ct.mastery,COALESCE(ct.chakra_cost_override,t.base_chakra_cost),t.category FROM character_techniques ct JOIN technique_definitions t ON t.technique_uid=ct.technique_uid WHERE ct.entity_uid=? ORDER BY t.category,t.name"
            shape==CharacterPanelDefinitionShape.LEGACY->
                "SELECT t.name,ct.mastery,NULL,t.category FROM character_techniques ct JOIN technique_definitions t ON t.technique_uid=ct.technique_uid WHERE ct.entity_uid=? ORDER BY t.category,t.name"
            shape==CharacterPanelDefinitionShape.TYPED_V2->
                "SELECT t.display_name,ct.mastery,NULL,t.category FROM character_techniques ct JOIN technique_definitions_v2 t ON t.technique_uid=ct.technique_uid WHERE ct.entity_uid=? ORDER BY t.category,t.display_name"
            else->return
        }
        db.rawQuery(sql,arrayOf(uid)).use{c->while(c.moveToNext())out+=TechniqueLine(
            c.getString(0),c.getString(1),if(c.isNull(2))"—" else c.getString(2),if(c.isNull(3))"—" else c.getString(3)
        )}
    }

    private fun tableExists(table: String): Boolean = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1", arrayOf(table)).use { it.moveToFirst() }
    private fun columns(table:String):Set<String>{
        if(!tableExists(table))return emptySet()
        return db.rawQuery("PRAGMA table_info($table)",null).use{c->buildSet{
            val nameIndex=c.getColumnIndex("name")
            while(c.moveToNext())if(nameIndex>=0)add(c.getString(nameIndex))
        }}
    }
    private fun hasColumn(table: String, column: String): Boolean = db.rawQuery("PRAGMA table_info($table)", null).use { c ->
        val nameIndex = c.getColumnIndex("name")
        while (c.moveToNext()) if (nameIndex >= 0 && c.getString(nameIndex).equals(column, ignoreCase = true)) return@use true
        false
    }
}
