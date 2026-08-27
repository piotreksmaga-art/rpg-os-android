package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

internal class TechniqueStore(private val db: SQLiteDatabase, private val campaignId: String) {
    init { require(campaignId.isNotBlank()) }

    fun registerDefinitions(worldPackUid: String, definitions: List<TechniqueDefinition>) {
        require(worldPackUid.isNotBlank())
        val seenUid = hashSetOf<String>()
        val seenKey = hashSetOf<String>()
        definitions.forEach { d ->
            TechniquePolicy.validateDefinition(d)
            require(d.worldPackUid == worldPackUid) { "TechniqueDefinition ${d.techniqueUid} belongs to another World Pack" }
            require(seenUid.add(d.techniqueUid)) { "Duplicate Technique UID in request: ${d.techniqueUid}" }
            require(seenKey.add(d.key)) { "Duplicate Technique key in request: ${d.key}" }
            require(!definitionExists(d.techniqueUid)) { "Duplicate Technique UID: ${d.techniqueUid}" }
            require(!definitionKeyExists(worldPackUid, d.key)) { "Duplicate Technique key for World Pack: ${d.key}" }
            d.skillRequirements.forEach { r -> require(skillDefinitionExists(r.skillUid)) { "Missing Skill requirement ${r.skillUid}" } }
            d.resourceCosts.forEach { c -> requireResourceExists(c.resourceUid) }
        }
        db.beginTransaction()
        try {
            definitions.forEach { d ->
                db.execSQL(
                    """INSERT INTO technique_definitions_v2(technique_uid,world_pack_uid,technique_key,display_name,category,min_mastery,max_mastery,definition_status,definition_version,provenance) VALUES(?,?,?,?,?,?,?,?,?,?)""",
                    arrayOf<Any?>(d.techniqueUid, d.worldPackUid, d.key, d.displayName, d.category, d.minMastery, d.maxMastery, d.status.name, d.definitionVersion, d.provenance)
                )
                d.skillRequirements.forEach { r ->
                    db.execSQL(
                        """INSERT INTO technique_skill_requirements(technique_uid,skill_uid,requirement_phase,mastery_basis,minimum_mastery,requirement_version,provenance) VALUES(?,?,?,?,?,?,?)""",
                        arrayOf<Any?>(d.techniqueUid, r.skillUid, r.requirementPhase.name, r.masteryBasis.name, r.minimumMastery, r.requirementVersion, r.provenance)
                    )
                }
                d.resourceCosts.forEach { c ->
                    db.execSQL(
                        """INSERT INTO technique_resource_costs(technique_uid,resource_uid,amount,cost_version,provenance) VALUES(?,?,?,?,?)""",
                        arrayOf<Any?>(d.techniqueUid, c.resourceUid, c.amount, c.costVersion, c.provenance)
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun definitions(): List<TechniqueDefinition> {
        val req = linkedMapOf<String, MutableList<TechniqueSkillRequirement>>()
        db.rawQuery(
            "SELECT technique_uid,skill_uid,requirement_phase,mastery_basis,minimum_mastery,requirement_version,provenance FROM technique_skill_requirements ORDER BY technique_uid,skill_uid,requirement_phase",
            null
        ).use { c ->
            while (c.moveToNext()) {
                req.getOrPut(c.getString(0)) { mutableListOf() } += TechniqueSkillRequirement(
                    c.getString(1),
                    TechniqueRequirementPhase.valueOf(c.getString(2)),
                    TechniqueSkillMasteryBasis.valueOf(c.getString(3)),
                    c.getDouble(4),
                    c.getLong(5),
                    c.getString(6)
                )
            }
        }
        val costs = linkedMapOf<String, MutableList<TechniqueResourceCost>>()
        db.rawQuery(
            "SELECT technique_uid,resource_uid,amount,cost_version,provenance FROM technique_resource_costs ORDER BY technique_uid,resource_uid",
            null
        ).use { c ->
            while (c.moveToNext()) {
                costs.getOrPut(c.getString(0)) { mutableListOf() } += TechniqueResourceCost(
                    c.getString(1), c.getDouble(2), c.getLong(3), c.getString(4)
                )
            }
        }
        val out = mutableListOf<TechniqueDefinition>()
        db.rawQuery(
            "SELECT technique_uid,world_pack_uid,technique_key,display_name,category,min_mastery,max_mastery,definition_status,definition_version,provenance FROM technique_definitions_v2 ORDER BY world_pack_uid,technique_key,technique_uid",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out += TechniqueDefinition(
                    c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
                    req[c.getString(0)] ?: emptyList(),
                    costs[c.getString(0)] ?: emptyList(),
                    if (c.isNull(5)) null else c.getDouble(5),
                    if (c.isNull(6)) null else c.getDouble(6),
                    TechniqueDefinitionStatus.valueOf(c.getString(7)),
                    c.getLong(8), c.getString(9)
                )
            }
        }
        return out
    }

    fun playerTechniques(characterUid: String): List<PlayerTechnique> {
        require(characterUid.isNotBlank())
        val out = mutableListOf<PlayerTechnique>()
        db.rawQuery(
            """SELECT campaign_id,character_uid,technique_uid,base_mastery,progress_value,progress_semantics_uid,learned_chapter,last_used_chapter,usage_count,success_count,failure_count,is_equipped,notes,entry_version,provenance FROM player_techniques_v2 WHERE campaign_id=? AND character_uid=? ORDER BY technique_uid""",
            arrayOf(campaignId, characterUid)
        ).use { c ->
            while (c.moveToNext()) {
                out += PlayerTechnique(
                    c.getString(0), c.getString(1), c.getString(2), c.getDouble(3),
                    if (c.isNull(4)) null else c.getDouble(4),
                    if (c.isNull(5)) null else c.getString(5),
                    if (c.isNull(6)) null else c.getLong(6),
                    if (c.isNull(7)) null else c.getLong(7),
                    c.getLong(8), c.getLong(9), c.getLong(10), c.getInt(11) != 0,
                    if (c.isNull(12)) null else c.getString(12),
                    c.getLong(13), c.getString(14)
                )
            }
        }
        return out
    }

    fun savePlayerTechnique(t: PlayerTechnique) {
        TechniquePolicy.validatePlayerTechnique(t)
        require(t.campaignId == campaignId) { "PlayerTechnique belongs to another campaign" }
        val d = definition(t.techniqueUid) ?: error("Missing TechniqueDefinition ${t.techniqueUid}")
        d.minMastery?.let { require(t.baseMastery >= it) { "baseTechniqueMastery below declared range" } }
        d.maxMastery?.let { require(t.baseMastery <= it) { "baseTechniqueMastery above declared range" } }
        val exists = typedExists(t.characterUid, t.techniqueUid)
        if (!exists) require(d.status == TechniqueDefinitionStatus.ACTIVE) { "Cannot learn deprecated Technique ${t.techniqueUid}" }
        if (legacyExactExists(t.characterUid, t.techniqueUid) && mapping(t.characterUid, t.techniqueUid) == null) {
            error("Mixed legacy + typed Technique with same UID requires explicit mapping: ${t.techniqueUid}")
        }
        val mutable=arrayOf<Any?>(t.baseMastery,t.progressValue,t.progressSemanticsUid,t.learnedChapter,t.lastUsedChapter,t.usageCount,t.successCount,t.failureCount,if(t.isEquipped)1 else 0,t.notes,t.entryVersion,t.provenance)
        db.updateOrInsertCompat(
            "UPDATE player_techniques_v2 SET base_mastery=?,progress_value=?,progress_semantics_uid=?,learned_chapter=?,last_used_chapter=?,usage_count=?,success_count=?,failure_count=?,is_equipped=?,notes=?,entry_version=?,provenance=? WHERE campaign_id=? AND character_uid=? AND technique_uid=?",
            arrayOf<Any?>(*mutable,t.campaignId,t.characterUid,t.techniqueUid),
            "INSERT INTO player_techniques_v2(campaign_id,character_uid,technique_uid,base_mastery,progress_value,progress_semantics_uid,learned_chapter,last_used_chapter,usage_count,success_count,failure_count,is_equipped,notes,entry_version,provenance) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(t.campaignId,t.characterUid,t.techniqueUid,t.baseMastery,t.progressValue,t.progressSemanticsUid,t.learnedChapter,t.lastUsedChapter,t.usageCount,t.successCount,t.failureCount,if(t.isEquipped)1 else 0,t.notes,t.entryVersion,t.provenance)
        )
    }

    fun registerLegacyMapping(m: LegacyTechniqueMapping) {
        require(m.campaignId == campaignId)
        require(legacyExactExists(m.characterUid, m.legacyTechniqueUid)) { "Legacy Technique not found" }
        val d = definition(m.canonicalTechniqueUid) ?: error("Mapping target TechniqueDefinition not found")
        require(d.worldPackUid == m.worldPackUid) { "Legacy Technique mapping owner mismatch" }
        require(mapping(m.characterUid, m.legacyTechniqueUid) == null) { "Duplicate legacy Technique mapping" }
        if (m.supersededByTyped) require(typedExists(m.characterUid, m.canonicalTechniqueUid)) { "Supersession requires typed PlayerTechnique" }
        db.execSQL(
            "INSERT INTO legacy_technique_mappings(campaign_id,character_uid,legacy_technique_uid,canonical_technique_uid,world_pack_uid,mapping_version,provenance,superseded_by_typed) VALUES(?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(m.campaignId, m.characterUid, m.legacyTechniqueUid, m.canonicalTechniqueUid, m.worldPackUid, m.mappingVersion, m.provenance, if (m.supersededByTyped) 1 else 0)
        )
    }

    fun registerLegacyResourceCostMapping(m: LegacyTechniqueResourceCostMapping) {
        require(m.campaignId == campaignId)
        require(legacyExactExists(m.characterUid, m.legacyTechniqueUid)) { "Legacy Technique not found" }
        val identity = mapping(m.characterUid, m.legacyTechniqueUid) ?: error("Resource-cost mapping requires explicit Technique identity mapping")
        require(identity.worldPackUid == m.worldPackUid) { "Legacy Technique resource-cost mapping owner mismatch" }
        requireResourceExists(m.resourceUid)
        require(resourceMapping(m.characterUid, m.legacyTechniqueUid) == null) { "Duplicate legacy Technique resource-cost mapping" }
        db.execSQL(
            "INSERT INTO legacy_technique_resource_cost_mappings(campaign_id,character_uid,legacy_technique_uid,resource_uid,world_pack_uid,mapping_version,provenance) VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any?>(m.campaignId, m.characterUid, m.legacyTechniqueUid, m.resourceUid, m.worldPackUid, m.mappingVersion, m.provenance)
        )
    }

    fun reconciled(characterUid: String): TechniqueReadResult {
        val typed = playerTechniques(characterUid).associateBy { it.techniqueUid }.toMutableMap()
        val result = typed.values.map { ReconciledTechnique(it, TechniqueAuthoritySource.TYPED) }.toMutableList()
        val unresolved = mutableListOf<LegacyTechniqueRecord>()
        legacy(characterUid).forEach { legacyTechnique ->
            val identity = mapping(characterUid, legacyTechnique.legacyTechniqueUid)
            if (identity == null) {
                if (typed.containsKey(legacyTechnique.legacyTechniqueUid)) {
                    error("Mixed legacy + typed same Technique UID without explicit mapping: ${legacyTechnique.legacyTechniqueUid}")
                }
                unresolved += legacyTechnique
            } else {
                val d = definition(identity.canonicalTechniqueUid) ?: error("Legacy Technique mapping target missing")
                require(d.worldPackUid == identity.worldPackUid)
                val typedTechnique = typed[identity.canonicalTechniqueUid]
                if (identity.supersededByTyped) {
                    require(typedTechnique != null) { "Superseded legacy Technique missing typed authority" }
                } else {
                    require(typedTechnique == null) { "Legacy + typed duplicate Technique authority requires explicit supersession" }
                    val mastery = legacyTechnique.masteryRaw.toDoubleOrNull() ?: error("Legacy Technique mastery is not numeric")
                    require(mastery.isFinite() && mastery >= 0.0)
                    d.minMastery?.let { require(mastery >= it) }
                    d.maxMastery?.let { require(mastery <= it) }
                    val resourceMapping = resourceMapping(characterUid, legacyTechnique.legacyTechniqueUid)
                    result += ReconciledTechnique(
                        PlayerTechnique(
                            campaignId = campaignId,
                            characterUid = characterUid,
                            techniqueUid = identity.canonicalTechniqueUid,
                            baseMastery = mastery,
                            learnedChapter = legacyTechnique.learnedChapterRaw?.toLongOrNull(),
                            lastUsedChapter = legacyTechnique.lastUsedChapterRaw?.toLongOrNull(),
                            usageCount = legacyTechnique.usageCountRaw?.toLongOrNull() ?: 0,
                            successCount = legacyTechnique.successCountRaw?.toLongOrNull() ?: 0,
                            failureCount = legacyTechnique.failureCountRaw?.toLongOrNull() ?: 0,
                            isEquipped = (legacyTechnique.isEquippedRaw?.toIntOrNull() ?: 0) != 0,
                            notes = legacyTechnique.notesRaw,
                            provenance = "legacy-read-through:${identity.provenance}"
                        ),
                        TechniqueAuthoritySource.LEGACY_MAPPED,
                        legacyTechnique.xpRaw,
                        legacyTechnique.chakraCostOverrideRaw,
                        legacyTechnique.baseChakraCostRaw,
                        resourceMapping?.resourceUid
                    )
                }
            }
        }
        return TechniqueReadResult(
            result.sortedBy { it.playerTechnique.techniqueUid },
            unresolved.sortedBy { it.legacyTechniqueUid }
        )
    }

    fun legacy(characterUid: String): List<LegacyTechniqueRecord> {
        if (!tableExists("character_techniques")) return emptyList()
        val hasDef = tableExists("technique_definitions")
        fun col(name: String, fallback: String = "NULL") = if (columnExists("character_techniques", name)) "CAST(ct.$name AS TEXT)" else fallback
        val join = if (hasDef) " LEFT JOIN technique_definitions td ON td.technique_uid=ct.technique_uid" else ""
        val name = if (hasDef && columnExists("technique_definitions", "name")) "td.name" else "NULL"
        val cat = if (hasDef && columnExists("technique_definitions", "category")) "td.category" else "NULL"
        val baseCost = if (hasDef && columnExists("technique_definitions", "base_chakra_cost")) "CAST(td.base_chakra_cost AS TEXT)" else "NULL"
        val sql = "SELECT ct.entity_uid,ct.technique_uid,${col("mastery")},${col("xp")},${col("learned_chapter")},${col("last_used_chapter")},${col("usage_count")},${col("success_count")},${col("failure_count")},${col("is_equipped")},${col("notes")},${col("chakra_cost_override")},$name,$cat,$baseCost FROM character_techniques ct$join WHERE ct.entity_uid=? ORDER BY ct.technique_uid"
        val out = mutableListOf<LegacyTechniqueRecord>()
        db.rawQuery(sql, arrayOf(characterUid)).use { c ->
            while (c.moveToNext()) {
                out += LegacyTechniqueRecord(
                    campaignId, c.getString(0), c.getString(1), c.getString(2),
                    if (c.isNull(3)) null else c.getString(3),
                    if (c.isNull(4)) null else c.getString(4),
                    if (c.isNull(5)) null else c.getString(5),
                    if (c.isNull(6)) null else c.getString(6),
                    if (c.isNull(7)) null else c.getString(7),
                    if (c.isNull(8)) null else c.getString(8),
                    if (c.isNull(9)) null else c.getString(9),
                    if (c.isNull(10)) null else c.getString(10),
                    if (c.isNull(11)) null else c.getString(11),
                    if (c.isNull(12)) null else c.getString(12),
                    if (c.isNull(13)) null else c.getString(13),
                    if (c.isNull(14)) null else c.getString(14)
                )
            }
        }
        return out
    }

    fun requirementsSatisfied(
        characterUid: String,
        techniqueUid: String,
        phase: TechniqueRequirementPhase,
        resolutionEpoch: Long,
        modifiers: List<Modifier>
    ): Boolean {
        require(phase != TechniqueRequirementPhase.BOTH) { "Evaluate ACQUISITION or EXECUTION, not BOTH" }
        val d = definition(techniqueUid) ?: error("Missing TechniqueDefinition $techniqueUid")
        val applicable = d.skillRequirements.filter { it.requirementPhase == phase || it.requirementPhase == TechniqueRequirementPhase.BOTH }
        if (applicable.isEmpty()) return true
        val skillStore = SkillStore(db, campaignId)
        val skills = skillStore.playerSkills(characterUid)
        val defs = skillStore.definitions()
        if (applicable.any { requirement -> skills.none { it.skillUid == requirement.skillUid } }) return false
        if (applicable.none { it.masteryBasis == TechniqueSkillMasteryBasis.EFFECTIVE }) {
            return applicable.all { requirement -> skills.single { it.skillUid == requirement.skillUid }.baseMastery >= requirement.minimumMastery }
        }
        val skillModifiers = modifiers.filter { it.targetKind == ModifierTargetKind.SKILL_EFFECTIVE }
        val resolved = DerivedValueResolver().resolve(
            DerivedResolutionRequest(
                campaignId,
                characterUid,
                resolutionEpoch,
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                skillModifiers,
                skillDefinitions = defs,
                playerSkills = skills
            )
        ).resolvedSkills.associateBy { it.skillUid }
        return applicable.all { requirement ->
            val value = if (requirement.masteryBasis == TechniqueSkillMasteryBasis.BASE) {
                skills.single { it.skillUid == requirement.skillUid }.baseMastery
            } else {
                resolved[requirement.skillUid]?.effectiveMastery ?: error("Missing effective Skill ${requirement.skillUid}")
            }
            value >= requirement.minimumMastery
        }
    }

    private fun definition(uid: String) = definitions().firstOrNull { it.techniqueUid == uid }
    private fun definitionExists(uid: String) = db.rawQuery("SELECT 1 FROM technique_definitions_v2 WHERE technique_uid=? LIMIT 1", arrayOf(uid)).use { it.moveToFirst() }
    private fun definitionKeyExists(pack: String, key: String) = db.rawQuery("SELECT 1 FROM technique_definitions_v2 WHERE world_pack_uid=? AND technique_key=? LIMIT 1", arrayOf(pack, key)).use { it.moveToFirst() }
    private fun skillDefinitionExists(uid: String) = tableExists("skill_definitions_v2") && db.rawQuery("SELECT 1 FROM skill_definitions_v2 WHERE skill_uid=? LIMIT 1", arrayOf(uid)).use { it.moveToFirst() }
    private fun typedExists(character: String, uid: String) = db.rawQuery("SELECT 1 FROM player_techniques_v2 WHERE campaign_id=? AND character_uid=? AND technique_uid=? LIMIT 1", arrayOf(campaignId, character, uid)).use { it.moveToFirst() }
    private fun legacyExactExists(character: String, uid: String) = tableExists("character_techniques") && db.rawQuery("SELECT 1 FROM character_techniques WHERE entity_uid=? AND technique_uid=? LIMIT 1", arrayOf(character, uid)).use { it.moveToFirst() }
    private fun mapping(character: String, uid: String): LegacyTechniqueMapping? = db.rawQuery(
        "SELECT campaign_id,character_uid,legacy_technique_uid,canonical_technique_uid,world_pack_uid,mapping_version,provenance,superseded_by_typed FROM legacy_technique_mappings WHERE campaign_id=? AND character_uid=? AND legacy_technique_uid=? LIMIT 1",
        arrayOf(campaignId, character, uid)
    ).use { c -> if (!c.moveToFirst()) null else LegacyTechniqueMapping(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getLong(5), c.getString(6), c.getInt(7) != 0) }
    private fun resourceMapping(character: String, uid: String): LegacyTechniqueResourceCostMapping? = db.rawQuery(
        "SELECT campaign_id,character_uid,legacy_technique_uid,resource_uid,world_pack_uid,mapping_version,provenance FROM legacy_technique_resource_cost_mappings WHERE campaign_id=? AND character_uid=? AND legacy_technique_uid=? LIMIT 1",
        arrayOf(campaignId, character, uid)
    ).use { c -> if (!c.moveToFirst()) null else LegacyTechniqueResourceCostMapping(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getLong(5), c.getString(6)) }
    private fun requireResourceExists(uid: String) {
        db.rawQuery("SELECT 1 FROM resource_definitions WHERE resource_uid=? LIMIT 1", arrayOf(uid)).use { c -> require(c.moveToFirst()) { "Missing ResourceDefinition $uid" } }
    }
    private fun tableExists(name: String) = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1", arrayOf(name)).use { it.moveToFirst() }
    private fun columnExists(table: String, column: String) = db.rawQuery("PRAGMA table_info($table)", null).use { c ->
        var found = false
        while (c.moveToNext()) if (c.getString(1) == column) found = true
        found
    }
}
