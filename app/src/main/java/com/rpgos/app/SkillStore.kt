package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

internal class SkillStore(
    private val db: SQLiteDatabase,
    private val campaignId: String
) {
    init { require(campaignId.isNotBlank()) { "campaignId must not be blank" } }

    fun registerDefinitions(worldPackUid: String, definitions: List<SkillDefinition>) {
        require(worldPackUid.isNotBlank()) { "worldPackUid must not be blank" }
        val seenUid = hashSetOf<String>()
        val seenKey = hashSetOf<String>()
        definitions.forEach { definition ->
            SkillPolicy.validateDefinition(definition)
            require(definition.worldPackUid == worldPackUid) { "SkillDefinition ${definition.skillUid} belongs to another World Pack" }
            require(seenUid.add(definition.skillUid)) { "Duplicate skill UID in request: ${definition.skillUid}" }
            require(seenKey.add(definition.key)) { "Duplicate skill key in request: ${definition.key}" }
            require(!definitionExists(definition.skillUid)) { "Duplicate skill UID: ${definition.skillUid}" }
            require(!definitionKeyExists(worldPackUid, definition.key)) { "Duplicate skill key for World Pack: ${definition.key}" }
            definition.progressionDomainUids.forEach { requireDomainOwnedBy(it, worldPackUid) }
        }
        db.beginTransaction()
        try {
            definitions.forEach { definition ->
                db.execSQL(
                    """INSERT INTO skill_definitions_v2(skill_uid,world_pack_uid,skill_key,display_name,category,min_mastery,max_mastery,definition_status,definition_version,provenance)
                       VALUES(?,?,?,?,?,?,?,?,?,?)""".trimIndent(),
                    arrayOf<Any?>(definition.skillUid, definition.worldPackUid, definition.key, definition.displayName, definition.category,
                        definition.minMastery, definition.maxMastery, definition.status.name, definition.definitionVersion, definition.provenance)
                )
                definition.progressionDomainUids.forEach { domainUid ->
                    db.execSQL("INSERT INTO skill_definition_domains(skill_uid,domain_uid) VALUES(?,?)", arrayOf(definition.skillUid, domainUid))
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun definitions(): List<SkillDefinition> {
        val domains = linkedMapOf<String, MutableList<String>>()
        db.rawQuery("SELECT skill_uid,domain_uid FROM skill_definition_domains ORDER BY skill_uid,domain_uid", null).use { c ->
            while (c.moveToNext()) domains.getOrPut(c.getString(0)) { mutableListOf() } += c.getString(1)
        }
        val out = mutableListOf<SkillDefinition>()
        db.rawQuery("""SELECT skill_uid,world_pack_uid,skill_key,display_name,category,min_mastery,max_mastery,definition_status,definition_version,provenance
                       FROM skill_definitions_v2 ORDER BY world_pack_uid,skill_key,skill_uid""".trimIndent(), null).use { c ->
            while (c.moveToNext()) out += SkillDefinition(
                skillUid = c.getString(0), worldPackUid = c.getString(1), key = c.getString(2), displayName = c.getString(3), category = c.getString(4),
                progressionDomainUids = domains[c.getString(0)]?.toList() ?: emptyList(),
                minMastery = if (c.isNull(5)) null else c.getDouble(5), maxMastery = if (c.isNull(6)) null else c.getDouble(6),
                status = SkillDefinitionStatus.valueOf(c.getString(7)), definitionVersion = c.getLong(8), provenance = c.getString(9)
            )
        }
        return out
    }

    fun playerSkills(characterUid: String): List<PlayerSkill> {
        require(characterUid.isNotBlank()) { "characterUid must not be blank" }
        val out = mutableListOf<PlayerSkill>()
        db.rawQuery("""SELECT campaign_id,character_uid,skill_uid,base_mastery,progress_value,progress_semantics_uid,entry_version,provenance,learned_chapter
                       FROM player_skills_v2 WHERE campaign_id=? AND character_uid=? ORDER BY skill_uid""".trimIndent(), arrayOf(campaignId, characterUid)).use { c ->
            while (c.moveToNext()) out += PlayerSkill(
                campaignId=c.getString(0), characterUid=c.getString(1), skillUid=c.getString(2), baseMastery=c.getDouble(3),
                progressValue=if(c.isNull(4)) null else c.getDouble(4), progressSemanticsUid=if(c.isNull(5)) null else c.getString(5),
                entryVersion=c.getLong(6), provenance=c.getString(7), learnedChapter=if(c.isNull(8)) null else c.getLong(8)
            )
        }
        return out
    }

    fun savePlayerSkill(skill: PlayerSkill) {
        SkillPolicy.validatePlayerSkill(skill)
        require(skill.campaignId == campaignId) { "PlayerSkill belongs to another campaign" }
        val definition = definition(skill.skillUid) ?: error("Missing SkillDefinition ${skill.skillUid}")
        definition.minMastery?.let { require(skill.baseMastery >= it) { "baseMastery below declared range" } }
        definition.maxMastery?.let { require(skill.baseMastery <= it) { "baseMastery above declared range" } }
        val exists = typedExists(skill.characterUid, skill.skillUid)
        if (!exists) require(definition.status == SkillDefinitionStatus.ACTIVE) { "Cannot learn deprecated skill ${skill.skillUid}" }
        if (legacyExactExists(skill.characterUid, skill.skillUid) && mapping(skill.characterUid, skill.skillUid) == null) {
            error("Mixed legacy + typed Skill with same UID requires explicit mapping: ${skill.skillUid}")
        }
        db.execSQL("""INSERT INTO player_skills_v2(campaign_id,character_uid,skill_uid,base_mastery,progress_value,progress_semantics_uid,entry_version,provenance,learned_chapter)
                       VALUES(?,?,?,?,?,?,?,?,?)
                       ON CONFLICT(campaign_id,character_uid,skill_uid) DO UPDATE SET
                       base_mastery=excluded.base_mastery,progress_value=excluded.progress_value,progress_semantics_uid=excluded.progress_semantics_uid,
                       entry_version=excluded.entry_version,provenance=excluded.provenance,learned_chapter=excluded.learned_chapter""".trimIndent(),
            arrayOf<Any?>(skill.campaignId, skill.characterUid, skill.skillUid, skill.baseMastery, skill.progressValue, skill.progressSemanticsUid, skill.entryVersion, skill.provenance, skill.learnedChapter))
    }

    fun registerLegacyMapping(mapping: LegacySkillMapping) {
        require(mapping.campaignId == campaignId) { "LegacySkillMapping belongs to another campaign" }
        require(legacyExactExists(mapping.characterUid, mapping.legacySkillUid)) { "Legacy skill not found: ${mapping.legacySkillUid}" }
        val definition = definition(mapping.canonicalSkillUid) ?: error("Mapping target SkillDefinition not found: ${mapping.canonicalSkillUid}")
        require(definition.worldPackUid == mapping.worldPackUid) { "Legacy mapping World Pack owner mismatch" }
        require(this.mapping(mapping.characterUid, mapping.legacySkillUid) == null) { "Duplicate legacy skill mapping" }
        if (mapping.supersededByTyped) require(typedExists(mapping.characterUid, mapping.canonicalSkillUid)) { "Supersession requires existing typed PlayerSkill" }
        db.execSQL("""INSERT INTO legacy_skill_mappings(campaign_id,character_uid,legacy_skill_uid,canonical_skill_uid,world_pack_uid,mapping_version,provenance,superseded_by_typed)
                       VALUES(?,?,?,?,?,?,?,?)""".trimIndent(),
            arrayOf<Any?>(mapping.campaignId,mapping.characterUid,mapping.legacySkillUid,mapping.canonicalSkillUid,mapping.worldPackUid,mapping.mappingVersion,mapping.provenance,if(mapping.supersededByTyped)1 else 0))
    }

    fun reconciled(characterUid: String): SkillReadResult {
        require(characterUid.isNotBlank()) { "characterUid must not be blank" }
        val typed = playerSkills(characterUid).associateBy { it.skillUid }.toMutableMap()
        val result = typed.values.map { ReconciledSkill(it, SkillAuthoritySource.TYPED) }.toMutableList()
        val unresolved = mutableListOf<LegacySkillRecord>()
        legacy(characterUid).forEach { legacy ->
            val mapping = mapping(characterUid, legacy.legacySkillUid)
            if (mapping == null) {
                if (typed.containsKey(legacy.legacySkillUid)) error("Mixed legacy + typed same Skill UID without explicit mapping: ${legacy.legacySkillUid}")
                unresolved += legacy
            } else {
                val definition = definition(mapping.canonicalSkillUid) ?: error("Legacy mapping target missing: ${mapping.canonicalSkillUid}")
                require(definition.worldPackUid == mapping.worldPackUid) { "Legacy mapping target owner changed" }
                val typedSkill = typed[mapping.canonicalSkillUid]
                if (mapping.supersededByTyped) {
                    require(typedSkill != null) { "Superseded legacy skill missing typed authority" }
                } else {
                    require(typedSkill == null) { "Legacy + typed duplicate authority requires explicit supersession" }
                    val mastery = legacy.masteryRaw.toDoubleOrNull() ?: error("Legacy mastery is not numeric for ${legacy.legacySkillUid}")
                    require(mastery.isFinite() && mastery >= 0.0) { "Invalid legacy mastery for ${legacy.legacySkillUid}" }
                    definition.minMastery?.let { require(mastery >= it) { "Legacy mastery below declared range" } }
                    definition.maxMastery?.let { require(mastery <= it) { "Legacy mastery above declared range" } }
                    result += ReconciledSkill(
                        PlayerSkill(campaignId, characterUid, mapping.canonicalSkillUid, mastery, provenance="legacy-read-through:${mapping.provenance}"),
                        SkillAuthoritySource.LEGACY_MAPPED,
                        legacyXpRaw=legacy.xpRaw,
                        legacyUpdatedChapterRaw=legacy.updatedChapterRaw
                    )
                }
            }
        }
        return SkillReadResult(result.sortedBy { it.playerSkill.skillUid }, unresolved.sortedBy { it.legacySkillUid })
    }

    fun legacy(characterUid: String): List<LegacySkillRecord> {
        if (!tableExists("character_skills")) return emptyList()
        val out = mutableListOf<LegacySkillRecord>()
        val hasDefinitions = tableExists("skill_definitions")
        val sql = if (hasDefinitions) """SELECT cs.entity_uid,cs.skill_uid,CAST(cs.mastery AS TEXT),CAST(cs.xp AS TEXT),CAST(cs.updated_chapter AS TEXT),sd.name,sd.category
            FROM character_skills cs LEFT JOIN skill_definitions sd ON sd.skill_uid=cs.skill_uid WHERE cs.entity_uid=? ORDER BY cs.skill_uid"""
        else """SELECT entity_uid,skill_uid,CAST(mastery AS TEXT),CAST(xp AS TEXT),CAST(updated_chapter AS TEXT),NULL,NULL FROM character_skills WHERE entity_uid=? ORDER BY skill_uid"""
        db.rawQuery(sql, arrayOf(characterUid)).use { c ->
            while(c.moveToNext()) out += LegacySkillRecord(campaignId,c.getString(0),c.getString(1),c.getString(2),if(c.isNull(3))null else c.getString(3),if(c.isNull(4))null else c.getString(4),if(c.isNull(5))null else c.getString(5),if(c.isNull(6))null else c.getString(6))
        }
        return out
    }

    private fun definition(uid: String): SkillDefinition? = definitions().firstOrNull { it.skillUid == uid }
    private fun definitionExists(uid:String)=db.rawQuery("SELECT 1 FROM skill_definitions_v2 WHERE skill_uid=? LIMIT 1",arrayOf(uid)).use{it.moveToFirst()}
    private fun definitionKeyExists(pack:String,key:String)=db.rawQuery("SELECT 1 FROM skill_definitions_v2 WHERE world_pack_uid=? AND skill_key=? LIMIT 1",arrayOf(pack,key)).use{it.moveToFirst()}
    private fun typedExists(character:String,uid:String)=db.rawQuery("SELECT 1 FROM player_skills_v2 WHERE campaign_id=? AND character_uid=? AND skill_uid=? LIMIT 1",arrayOf(campaignId,character,uid)).use{it.moveToFirst()}
    private fun legacyExactExists(character:String,uid:String)=tableExists("character_skills") && db.rawQuery("SELECT 1 FROM character_skills WHERE entity_uid=? AND skill_uid=? LIMIT 1",arrayOf(character,uid)).use{it.moveToFirst()}
    private fun mapping(character:String,legacyUid:String): LegacySkillMapping? = db.rawQuery("""SELECT campaign_id,character_uid,legacy_skill_uid,canonical_skill_uid,world_pack_uid,mapping_version,provenance,superseded_by_typed
        FROM legacy_skill_mappings WHERE campaign_id=? AND character_uid=? AND legacy_skill_uid=? LIMIT 1""",arrayOf(campaignId,character,legacyUid)).use{c->if(!c.moveToFirst())null else LegacySkillMapping(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getLong(5),c.getString(6),c.getInt(7)!=0)}
    private fun requireDomainOwnedBy(domainUid:String,pack:String){
        db.rawQuery("SELECT world_pack_uid FROM progression_domain_definitions WHERE domain_uid=? LIMIT 1",arrayOf(domainUid)).use{c->require(c.moveToFirst()){ "Missing progression domain $domainUid" };require(c.getString(0)==pack){ "Progression domain owner mismatch for $domainUid" }}
    }
    private fun tableExists(name:String)=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",arrayOf(name)).use{it.moveToFirst()}
}
