package com.rpgos.app

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

private const val PHASE9_FORM_SOURCE_TYPE = "PHASE9_FORM"

class Phase9Store(
    private val db: SQLiteDatabase,
    private val campaignId: String,
    requirementRuleProvider: RequirementRuleProvider? = null
) {
    private val requirementEvaluator = RequirementEvaluator(requirementRuleProvider)

    init {
        require(campaignId.isNotBlank()) { "campaignId must not be blank" }
        MigrationManager().ensureV9RequirementHotfix(db, campaignId)
    }

    fun registerOrigins(worldPackUid: String, definitions: List<OriginDefinition>) {
        require(worldPackUid.isNotBlank())
        definitions.forEach { definition ->
            Phase9Policy.requireIdentity(definition.originUid, "originUid")
            Phase9Policy.requireDefinition(definition.worldPackUid, definition.key, definition.displayName, definition.definitionVersion, definition.provenance)
            require(definition.worldPackUid == worldPackUid) { "Origin ${definition.originUid} belongs to another World Pack" }
            require(definition.originKind.isNotBlank()) { "originKind must not be blank" }
            require(!exists("origin_definitions_v2", "origin_uid", definition.originUid)) { "Duplicate origin UID: ${definition.originUid}" }
            db.execSQL("INSERT INTO origin_definitions_v2(origin_uid,world_pack_uid,origin_key,display_name,origin_kind,definition_status,definition_version,provenance) VALUES(?,?,?,?,?,?,?,?)",
                arrayOf(definition.originUid,definition.worldPackUid,definition.key,definition.displayName,definition.originKind,definition.status.name,definition.definitionVersion,definition.provenance))
        }
    }

    fun registerInnateFeatures(worldPackUid: String, definitions: List<InnateFeatureDefinition>) {
        require(worldPackUid.isNotBlank())
        definitions.forEach { definition ->
            Phase9Policy.requireIdentity(definition.featureUid, "featureUid")
            Phase9Policy.requireDefinition(definition.worldPackUid, definition.key, definition.displayName, definition.definitionVersion, definition.provenance)
            require(definition.worldPackUid == worldPackUid) { "Feature ${definition.featureUid} belongs to another World Pack" }
            require(definition.featureKind.isNotBlank()) { "featureKind must not be blank" }
            require(!exists("innate_feature_definitions", "feature_uid", definition.featureUid)) { "Duplicate innate feature UID: ${definition.featureUid}" }
            db.execSQL("INSERT INTO innate_feature_definitions(feature_uid,world_pack_uid,feature_key,display_name,feature_kind,category,definition_status,definition_version,provenance) VALUES(?,?,?,?,?,?,?,?,?)",
                arrayOf(definition.featureUid,definition.worldPackUid,definition.key,definition.displayName,definition.featureKind,definition.category,definition.status.name,definition.definitionVersion,definition.provenance))
        }
    }

    fun registerEvolutionPaths(worldPackUid: String, definitions: List<EvolutionPathDefinition>) {
        require(worldPackUid.isNotBlank())
        definitions.forEach { definition ->
            Phase9Policy.requireIdentity(definition.pathUid, "pathUid")
            Phase9Policy.requireDefinition(definition.worldPackUid, definition.key, definition.displayName, definition.definitionVersion, definition.provenance)
            require(definition.worldPackUid == worldPackUid) { "Path ${definition.pathUid} belongs to another World Pack" }
            require(!exists("evolution_path_definitions", "path_uid", definition.pathUid)) { "Duplicate evolution path UID: ${definition.pathUid}" }
            db.execSQL("INSERT INTO evolution_path_definitions(path_uid,world_pack_uid,path_key,display_name,definition_status,definition_version,provenance) VALUES(?,?,?,?,?,?,?)",
                arrayOf(definition.pathUid,definition.worldPackUid,definition.key,definition.displayName,definition.status.name,definition.definitionVersion,definition.provenance))
        }
    }

    fun registerEvolutionStages(worldPackUid: String, definitions: List<EvolutionStageDefinition>) {
        require(worldPackUid.isNotBlank())
        definitions.forEach { definition ->
            Phase9Policy.requireIdentity(definition.stageUid, "stageUid")
            Phase9Policy.requireIdentity(definition.pathUid, "pathUid")
            Phase9Policy.requireDefinition(definition.worldPackUid, definition.key, definition.displayName, definition.definitionVersion, definition.provenance)
            require(definition.worldPackUid == worldPackUid) { "Stage ${definition.stageUid} belongs to another World Pack" }
            require(owner("evolution_path_definitions", "path_uid", definition.pathUid) == worldPackUid) { "Stage path belongs to another World Pack or is missing" }
            require(!exists("evolution_stage_definitions", "stage_uid", definition.stageUid)) { "Duplicate evolution stage UID: ${definition.stageUid}" }
            db.execSQL("INSERT INTO evolution_stage_definitions(stage_uid,path_uid,world_pack_uid,stage_key,display_name,definition_status,definition_version,provenance) VALUES(?,?,?,?,?,?,?,?)",
                arrayOf(definition.stageUid,definition.pathUid,definition.worldPackUid,definition.key,definition.displayName,definition.status.name,definition.definitionVersion,definition.provenance))
        }
    }

    fun registerEvolutionTransitions(worldPackUid: String, definitions: List<EvolutionTransitionDefinition>) {
        require(worldPackUid.isNotBlank())
        definitions.forEach { definition ->
            Phase9Policy.requireIdentity(definition.transitionUid, "transitionUid")
            Phase9Policy.requireIdentity(definition.targetStageUid, "targetStageUid")
            Phase9Policy.requireOptionalRule(definition.requirementRuleUid, definition.requirementRuleVersion, "transition")
            require(definition.transitionVersion >= 1L) { "transitionVersion must be at least 1" }
            require(definition.provenance.isNotBlank()) { "provenance must not be blank" }
            require(definition.worldPackUid == worldPackUid) { "Transition ${definition.transitionUid} belongs to another World Pack" }
            val target = stageRecord(definition.targetStageUid)
            require(target.worldPackUid == worldPackUid) { "Transition target belongs to another World Pack" }
            val source = definition.sourceStageUid?.let(::stageRecord)
            if (source != null) {
                require(source.worldPackUid == worldPackUid) { "Transition source belongs to another World Pack" }
                require(source.pathUid == target.pathUid || definition.crossPathAllowed) { "Cross-path transition requires explicit crossPathAllowed" }
            }
            require(!exists("evolution_transition_definitions", "transition_uid", definition.transitionUid)) { "Duplicate evolution transition UID: ${definition.transitionUid}" }
            db.execSQL("INSERT INTO evolution_transition_definitions(transition_uid,world_pack_uid,source_stage_uid,target_stage_uid,requirement_rule_uid,reversible,cross_path_allowed,transition_version,provenance,requirement_rule_version) VALUES(?,?,?,?,?,?,?,?,?,?)",
                arrayOf(definition.transitionUid,definition.worldPackUid,definition.sourceStageUid,definition.targetStageUid,definition.requirementRuleUid,if(definition.reversible)1 else 0,if(definition.crossPathAllowed)1 else 0,definition.transitionVersion,definition.provenance,definition.requirementRuleVersion))
        }
    }

    fun registerForms(worldPackUid: String, definitions: List<FormDefinition>) {
        require(worldPackUid.isNotBlank())
        definitions.forEach { definition ->
            Phase9Policy.requireIdentity(definition.formUid, "formUid")
            Phase9Policy.requireDefinition(definition.worldPackUid, definition.key, definition.displayName, definition.definitionVersion, definition.provenance)
            Phase9Policy.requireOptionalRule(definition.unlockRequirementRuleUid, definition.unlockRequirementRuleVersion, "unlock")
            Phase9Policy.requireOptionalRule(definition.activationRuleUid, definition.activationRuleVersion, "activation")
            require(definition.worldPackUid == worldPackUid) { "Form ${definition.formUid} belongs to another World Pack" }
            definition.sourceFeatureUid?.let { require(owner("innate_feature_definitions","feature_uid",it) == worldPackUid) { "Form feature source belongs to another World Pack or is missing" } }
            definition.sourceStageUid?.let { require(owner("evolution_stage_definitions","stage_uid",it) == worldPackUid) { "Form stage source belongs to another World Pack or is missing" } }
            require(!exists("form_definitions", "form_uid", definition.formUid)) { "Duplicate form UID: ${definition.formUid}" }
            db.execSQL("INSERT INTO form_definitions(form_uid,world_pack_uid,form_key,display_name,source_feature_uid,source_stage_uid,exclusive_group_uid,activation_rule_uid,definition_status,definition_version,provenance,unlock_requirement_rule_uid,unlock_requirement_rule_version,activation_rule_version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf(definition.formUid,definition.worldPackUid,definition.key,definition.displayName,definition.sourceFeatureUid,definition.sourceStageUid,definition.exclusiveGroupUid,definition.activationRuleUid,definition.status.name,definition.definitionVersion,definition.provenance,definition.unlockRequirementRuleUid,definition.unlockRequirementRuleVersion,definition.activationRuleVersion))
        }
    }

    fun registerFormModifierBindings(worldPackUid: String, bindings: List<FormModifierBinding>) {
        bindings.forEach { binding ->
            require(binding.bindingUid.isNotBlank() && binding.provenance.isNotBlank())
            require(binding.bindingVersion >= 1L && binding.value.isFinite())
            require(binding.worldPackUid == worldPackUid) { "Binding ${binding.bindingUid} belongs to another World Pack" }
            require(owner("form_definitions","form_uid",binding.formUid) == worldPackUid) { "Binding form belongs to another World Pack or is missing" }
            requireTargetExists(binding.targetKind, binding.targetDefinitionUid)
            require(!exists("form_modifier_bindings","binding_uid",binding.bindingUid)) { "Duplicate form modifier binding UID: ${binding.bindingUid}" }
            db.execSQL("INSERT INTO form_modifier_bindings(binding_uid,world_pack_uid,form_uid,target_definition_uid,target_kind,operation,modifier_value,priority,binding_version,provenance) VALUES(?,?,?,?,?,?,?,?,?,?)",
                arrayOf(binding.bindingUid,binding.worldPackUid,binding.formUid,binding.targetDefinitionUid,binding.targetKind.name,binding.operation.name,binding.value,binding.priority,binding.bindingVersion,binding.provenance))
        }
    }

    fun registerLegacyMappings(worldPackUid: String, mappings: List<LegacyPhase9Mapping>) {
        mappings.forEach { mapping ->
            require(mapping.worldPackUid == worldPackUid) { "Legacy mapping belongs to another World Pack" }
            require(mapping.evidenceField.isNotBlank() && mapping.evidenceValue.isNotBlank() && mapping.targetUid.isNotBlank() && mapping.provenance.isNotBlank())
            require(mapping.mappingVersion >= 1L)
            requireTargetOwnedBy(mapping.targetKind, mapping.targetUid, worldPackUid)
            require(!legacyMappingExists(mapping)) { "Duplicate Phase 9 legacy mapping" }
            db.execSQL("INSERT INTO legacy_phase9_mappings(world_pack_uid,evidence_field,evidence_value,target_kind,target_uid,mapping_version,provenance) VALUES(?,?,?,?,?,?,?)",
                arrayOf(mapping.worldPackUid,mapping.evidenceField,mapping.evidenceValue,mapping.targetKind.name,mapping.targetUid,mapping.mappingVersion,mapping.provenance))
        }
    }

    fun origins(worldPackUid: String? = null): List<OriginDefinition> {
        val args = worldPackUid?.let { arrayOf(it) }
        val where = if (worldPackUid == null) "" else " WHERE world_pack_uid=?"
        val out = mutableListOf<OriginDefinition>()
        db.rawQuery("SELECT origin_uid,world_pack_uid,origin_key,display_name,origin_kind,definition_status,definition_version,provenance FROM origin_definitions_v2$where ORDER BY world_pack_uid,origin_key,origin_uid",args).use { c ->
            while(c.moveToNext()) out += OriginDefinition(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),Phase9DefinitionStatus.valueOf(c.getString(5)),c.getLong(6),c.getString(7))
        }
        return out
    }

    fun innateFeatures(worldPackUid: String? = null): List<InnateFeatureDefinition> {
        val args = worldPackUid?.let { arrayOf(it) }
        val where = if (worldPackUid == null) "" else " WHERE world_pack_uid=?"
        val out=mutableListOf<InnateFeatureDefinition>()
        db.rawQuery("SELECT feature_uid,world_pack_uid,feature_key,display_name,feature_kind,category,definition_status,definition_version,provenance FROM innate_feature_definitions$where ORDER BY world_pack_uid,feature_key,feature_uid",args).use { c ->
            while(c.moveToNext()) out += InnateFeatureDefinition(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),if(c.isNull(5))null else c.getString(5),Phase9DefinitionStatus.valueOf(c.getString(6)),c.getLong(7),c.getString(8))
        }
        return out
    }

    fun saveOrigin(origin: PlayerOrigin) {
        validatePlayer(origin.campaignId,origin.characterUid,origin.entryVersion,origin.provenance)
        require(origin.relationshipKind.isNotBlank())
        require(exists("origin_definitions_v2","origin_uid",origin.originUid)) { "Missing origin definition ${origin.originUid}" }
        require(!playerRowExists("player_origins_v2","origin_uid",origin.characterUid,origin.originUid)) { "Player origin already exists: ${origin.originUid}" }
        db.execSQL("INSERT INTO player_origins_v2(campaign_id,character_uid,origin_uid,relationship_kind,entry_version,provenance) VALUES(?,?,?,?,?,?)",arrayOf(origin.campaignId,origin.characterUid,origin.originUid,origin.relationshipKind,origin.entryVersion,origin.provenance))
    }

    fun grantInnateFeature(feature: PlayerInnateFeature) {
        validatePlayer(feature.campaignId,feature.characterUid,feature.entryVersion,feature.provenance)
        require(feature.acquiredChapter == null || feature.acquiredChapter >= 0)
        require(exists("innate_feature_definitions","feature_uid",feature.featureUid)) { "Missing innate feature definition ${feature.featureUid}" }
        require(!playerRowExists("player_innate_features","feature_uid",feature.characterUid,feature.featureUid)) { "Player already owns innate feature ${feature.featureUid}" }
        db.execSQL("INSERT INTO player_innate_features(campaign_id,character_uid,feature_uid,acquired_chapter,entry_version,provenance) VALUES(?,?,?,?,?,?)",arrayOf(feature.campaignId,feature.characterUid,feature.featureUid,feature.acquiredChapter,feature.entryVersion,feature.provenance))
    }

    fun enterEvolutionPath(characterUid: String, stageUid: String, provenance: String, attainedChapter: Long? = null) {
        require(characterUid.isNotBlank() && provenance.isNotBlank())
        val stage=stageRecord(stageUid)
        require(evolutionState(characterUid,stage.pathUid)==null) { "Evolution path ${stage.pathUid} already has current state" }
        db.beginTransaction()
        try {
            db.execSQL("INSERT INTO player_evolution_states(campaign_id,character_uid,path_uid,current_stage_uid,state_version,provenance) VALUES(?,?,?,?,1,?)",arrayOf(campaignId,characterUid,stage.pathUid,stageUid,provenance))
            attainStageIfMissing(characterUid,stageUid,null,attainedChapter,provenance)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun transitionEvolution(characterUid: String, transitionUid: String, provenance: String, attainedChapter: Long? = null) {
        require(characterUid.isNotBlank() && transitionUid.isNotBlank() && provenance.isNotBlank())
        val t=transitionRecord(transitionUid)
        val target=stageRecord(t.targetStageUid)
        val source=t.sourceStageUid?.let(::stageRecord)
        require(source != null) { "Entry transition cannot mutate an existing current stage; use enterEvolutionPath" }
        val sourceState=evolutionState(characterUid,source.pathUid) ?: error("Character has no current state on source path ${source.pathUid}")
        require(sourceState.currentStageUid == source.stageUid) { "Invalid evolution transition source: expected ${source.stageUid}, actual ${sourceState.currentStageUid}" }
        require(source.pathUid == target.pathUid || t.crossPathAllowed) { "Cross-path transition is not allowed" }
        requirementEvaluator.requirePass(
            t.requirement,
            RequirementContext(campaignId, characterUid, RequirementGate.TRANSITION, t.transitionUid)
        )
        db.beginTransaction()
        try {
            if(source.pathUid==target.pathUid){
                db.execSQL("UPDATE player_evolution_states SET current_stage_uid=?,state_version=state_version+1,provenance=? WHERE campaign_id=? AND character_uid=? AND path_uid=?",arrayOf(target.stageUid,provenance,campaignId,characterUid,target.pathUid))
            } else {
                val targetState=evolutionState(characterUid,target.pathUid)
                require(targetState == null || targetState.currentStageUid == target.stageUid) { "Cross-path target already has conflicting current stage" }
                if(targetState==null) db.execSQL("INSERT INTO player_evolution_states(campaign_id,character_uid,path_uid,current_stage_uid,state_version,provenance) VALUES(?,?,?,?,1,?)",arrayOf(campaignId,characterUid,target.pathUid,target.stageUid,provenance))
            }
            attainStageIfMissing(characterUid,target.stageUid,t.transitionUid,attainedChapter,provenance)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun unlockForm(unlock: PlayerFormUnlock) {
        validatePlayer(unlock.campaignId,unlock.characterUid,unlock.entryVersion,unlock.provenance)
        val form=formRecord(unlock.formUid)
        if(playerRowExists("player_form_unlocks","form_uid",unlock.characterUid,unlock.formUid)) return
        requirementEvaluator.requirePass(
            form.unlockRequirement,
            RequirementContext(campaignId, unlock.characterUid, RequirementGate.UNLOCK, unlock.formUid)
        )
        db.execSQL("INSERT INTO player_form_unlocks(campaign_id,character_uid,form_uid,entry_version,provenance) VALUES(?,?,?,?,?)",arrayOf(unlock.campaignId,unlock.characterUid,unlock.formUid,unlock.entryVersion,unlock.provenance))
    }

    fun activateForm(active: PlayerActiveForm) {
        validatePlayer(active.campaignId,active.characterUid,active.stateVersion,active.provenance)
        require(playerRowExists("player_form_unlocks","form_uid",active.characterUid,active.formUid)) { "Cannot activate locked form ${active.formUid}" }
        val form=formRecord(active.formUid)
        require(form.status == Phase9DefinitionStatus.ACTIVE) { "Deprecated form cannot be newly activated" }
        form.exclusiveGroupUid?.let { group ->
            val conflict=db.rawQuery("""SELECT a.form_uid FROM player_active_forms a JOIN form_definitions f ON f.form_uid=a.form_uid
                WHERE a.campaign_id=? AND a.character_uid=? AND f.exclusive_group_uid=? AND a.form_uid<>? LIMIT 1""".trimIndent(),arrayOf(campaignId,active.characterUid,group,active.formUid)).use { c -> if(c.moveToFirst()) c.getString(0) else null }
            require(conflict==null){"Mutually exclusive form already active: $conflict"}
        }
        requirementEvaluator.requirePass(
            form.activationRequirement,
            RequirementContext(campaignId, active.characterUid, RequirementGate.ACTIVATION, active.formUid)
        )
        db.beginTransaction()
        try {
            if(!playerRowExists("player_active_forms","form_uid",active.characterUid,active.formUid)){
                db.execSQL("INSERT INTO player_active_forms(campaign_id,character_uid,form_uid,activated_at,state_version,provenance) VALUES(?,?,?,?,?,?)",arrayOf(active.campaignId,active.characterUid,active.formUid,active.activatedAt,active.stateVersion,active.provenance))
            }
            ensureFormModifiers(active.characterUid,active.formUid)
            ModifierStore(db,campaignId).setSourceActive(active.characterUid,PHASE9_FORM_SOURCE_TYPE,active.formUid,true)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun deactivateForm(characterUid: String, formUid: String) {
        require(characterUid.isNotBlank() && formUid.isNotBlank())
        db.beginTransaction()
        try {
            db.delete("player_active_forms","campaign_id=? AND character_uid=? AND form_uid=?",arrayOf(campaignId,characterUid,formUid))
            ModifierStore(db,campaignId).setSourceActive(characterUid,PHASE9_FORM_SOURCE_TYPE,formUid,false)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun playerOrigins(characterUid:String):List<PlayerOrigin>{
        val out=mutableListOf<PlayerOrigin>()
        db.rawQuery("SELECT campaign_id,character_uid,origin_uid,relationship_kind,entry_version,provenance FROM player_origins_v2 WHERE campaign_id=? AND character_uid=? ORDER BY origin_uid",arrayOf(campaignId,characterUid)).use{c->while(c.moveToNext())out+=PlayerOrigin(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getLong(4),c.getString(5))}
        return out
    }
    fun playerInnateFeatures(characterUid:String):List<PlayerInnateFeature>{
        val out=mutableListOf<PlayerInnateFeature>()
        db.rawQuery("SELECT campaign_id,character_uid,feature_uid,acquired_chapter,entry_version,provenance FROM player_innate_features WHERE campaign_id=? AND character_uid=? ORDER BY feature_uid",arrayOf(campaignId,characterUid)).use{c->while(c.moveToNext())out+=PlayerInnateFeature(c.getString(0),c.getString(1),c.getString(2),if(c.isNull(3))null else c.getLong(3),c.getLong(4),c.getString(5))}
        return out
    }
    fun evolutionStates(characterUid:String):List<PlayerEvolutionState>{
        val out=mutableListOf<PlayerEvolutionState>()
        db.rawQuery("SELECT campaign_id,character_uid,path_uid,current_stage_uid,state_version,provenance FROM player_evolution_states WHERE campaign_id=? AND character_uid=? ORDER BY path_uid",arrayOf(campaignId,characterUid)).use{c->while(c.moveToNext())out+=PlayerEvolutionState(c.getString(0),c.getString(1),c.getString(2),if(c.isNull(3))null else c.getString(3),c.getLong(4),c.getString(5))}
        return out
    }
    fun attainedStages(characterUid:String):List<PlayerEvolutionStage>{
        val out=mutableListOf<PlayerEvolutionStage>()
        db.rawQuery("SELECT campaign_id,character_uid,stage_uid,attained_via_transition_uid,attained_chapter,entry_version,provenance FROM player_evolution_stages WHERE campaign_id=? AND character_uid=? ORDER BY stage_uid",arrayOf(campaignId,characterUid)).use{c->while(c.moveToNext())out+=PlayerEvolutionStage(c.getString(0),c.getString(1),c.getString(2),if(c.isNull(3))null else c.getString(3),if(c.isNull(4))null else c.getLong(4),c.getLong(5),c.getString(6))}
        return out
    }
    fun formUnlocks(characterUid:String):List<PlayerFormUnlock>{
        val out=mutableListOf<PlayerFormUnlock>()
        db.rawQuery("SELECT campaign_id,character_uid,form_uid,entry_version,provenance FROM player_form_unlocks WHERE campaign_id=? AND character_uid=? ORDER BY form_uid",arrayOf(campaignId,characterUid)).use{c->while(c.moveToNext())out+=PlayerFormUnlock(c.getString(0),c.getString(1),c.getString(2),c.getLong(3),c.getString(4))}
        return out
    }
    fun activeForms(characterUid:String):List<PlayerActiveForm>{
        val out=mutableListOf<PlayerActiveForm>()
        db.rawQuery("SELECT campaign_id,character_uid,form_uid,activated_at,state_version,provenance FROM player_active_forms WHERE campaign_id=? AND character_uid=? ORDER BY form_uid",arrayOf(campaignId,characterUid)).use{c->while(c.moveToNext())out+=PlayerActiveForm(c.getString(0),c.getString(1),c.getString(2),if(c.isNull(3))null else c.getLong(3),c.getLong(4),c.getString(5))}
        return out
    }

    fun legacyEvidence(characterUid:String):List<LegacyPhase9Evidence>{
        if(!tableExists("character_status_snapshot")) return emptyList()
        val row=legacyStatusRow(characterUid)
        val candidates=setOf("race","species","clan","clan_uid","bloodline","lineage","heritage","innate","innate_trait","trait","mutation","evolution","evolution_stage","stage","form","transformation","kekkei_genkai")
        return row.entries.filter { (key,value) -> value != null && candidates.any { token -> key.lowercase()==token || key.lowercase().contains(token) } }
            .map { LegacyPhase9Evidence(it.key,it.value.toString()) }
            .sortedWith(compareBy({it.field},{it.value}))
    }

    fun reconcileLegacy(characterUid:String,worldPackUid:String):List<LegacyPhase9Resolution>{
        return legacyEvidence(characterUid).map { evidence ->
            val matches=legacyMappings(worldPackUid,evidence)
            when(matches.size){
                0 -> LegacyPhase9Resolution(evidence,null,null,false,"UNRESOLVED_NO_EXPLICIT_MAPPING")
                1 -> {
                    val m=matches.single(); requireTargetOwnedBy(m.targetKind,m.targetUid,worldPackUid)
                    LegacyPhase9Resolution(evidence,m.targetKind,m.targetUid,true,"EXPLICIT_WORLD_PACK_MAPPING")
                }
                else -> error("Ambiguous Phase 9 legacy mapping for ${evidence.field}=${evidence.value}")
            }
        }
    }

    fun applyLegacyMappings(characterUid:String,worldPackUid:String):List<LegacyPhase9Resolution>{
        val resolutions=reconcileLegacy(characterUid,worldPackUid)
        db.beginTransaction()
        try {
            resolutions.filter{it.canonical}.forEach { resolution ->
                val targetUid=resolution.targetUid ?: error("Mapped target missing")
                val mapping=legacyMappings(worldPackUid,resolution.evidence).single()
                val provenance="legacy-map:${mapping.provenance}"
                when(resolution.targetKind!!){
                    LegacyPhase9TargetKind.ORIGIN -> if(!playerRowExists("player_origins_v2","origin_uid",characterUid,targetUid)) saveOrigin(PlayerOrigin(campaignId,characterUid,targetUid,"LEGACY_MAPPED",mapping.mappingVersion,provenance))
                    LegacyPhase9TargetKind.INNATE_FEATURE -> if(!playerRowExists("player_innate_features","feature_uid",characterUid,targetUid)) grantInnateFeature(PlayerInnateFeature(campaignId,characterUid,targetUid,null,mapping.mappingVersion,provenance))
                    LegacyPhase9TargetKind.EVOLUTION_STAGE -> {
                        val stage=stageRecord(targetUid); val state=evolutionState(characterUid,stage.pathUid)
                        if(state==null) enterEvolutionPath(characterUid,targetUid,provenance)
                        else require(state.currentStageUid==targetUid){"Legacy mapping conflicts with typed evolution state on ${stage.pathUid}"}
                    }
                    LegacyPhase9TargetKind.FORM_UNLOCK -> unlockForm(PlayerFormUnlock(campaignId,characterUid,targetUid,mapping.mappingVersion,provenance))
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return resolutions
    }

    fun snapshot(characterUid:String):Phase9PlayerSnapshot=Phase9PlayerSnapshot(
        origins=playerOrigins(characterUid),innateFeatures=playerInnateFeatures(characterUid),evolutionStates=evolutionStates(characterUid),
        attainedStages=attainedStages(characterUid),formUnlocks=formUnlocks(characterUid),activeForms=activeForms(characterUid),unresolvedLegacy=legacyEvidence(characterUid))

    private data class StageRow(val stageUid:String,val pathUid:String,val worldPackUid:String)
    private data class TransitionRow(
        val transitionUid:String,
        val sourceStageUid:String?,
        val targetStageUid:String,
        val crossPathAllowed:Boolean,
        val requirement: RequirementBinding?
    )

    private fun stageRecord(uid:String):StageRow=db.rawQuery("SELECT stage_uid,path_uid,world_pack_uid FROM evolution_stage_definitions WHERE stage_uid=?",arrayOf(uid)).use{c->require(c.moveToFirst()){ "Missing evolution stage $uid" };StageRow(c.getString(0),c.getString(1),c.getString(2))}
    private fun transitionRecord(uid:String):TransitionRow=db.rawQuery("SELECT transition_uid,source_stage_uid,target_stage_uid,cross_path_allowed,requirement_rule_uid,requirement_rule_version FROM evolution_transition_definitions WHERE transition_uid=?",arrayOf(uid)).use{c->
        require(c.moveToFirst()){ "Missing evolution transition $uid" }
        val ruleUid=if(c.isNull(4))null else c.getString(4)
        val ruleVersion=if(c.isNull(5))null else c.getLong(5)
        Phase9Policy.requireOptionalRule(ruleUid,ruleVersion,"transition")
        TransitionRow(c.getString(0),if(c.isNull(1))null else c.getString(1),c.getString(2),c.getInt(3)!=0,ruleUid?.let{RequirementBinding(it,ruleVersion!!)})
    }
    private fun evolutionState(characterUid:String,pathUid:String):PlayerEvolutionState?=db.rawQuery("SELECT campaign_id,character_uid,path_uid,current_stage_uid,state_version,provenance FROM player_evolution_states WHERE campaign_id=? AND character_uid=? AND path_uid=?",arrayOf(campaignId,characterUid,pathUid)).use{c->if(!c.moveToFirst())null else PlayerEvolutionState(c.getString(0),c.getString(1),c.getString(2),if(c.isNull(3))null else c.getString(3),c.getLong(4),c.getString(5))}
    private fun formRecord(uid:String):FormDefinition=db.rawQuery("SELECT form_uid,world_pack_uid,form_key,display_name,source_feature_uid,source_stage_uid,exclusive_group_uid,activation_rule_uid,definition_status,definition_version,provenance,unlock_requirement_rule_uid,unlock_requirement_rule_version,activation_rule_version FROM form_definitions WHERE form_uid=?",arrayOf(uid)).use{c->
        require(c.moveToFirst()){ "Missing form $uid" }
        FormDefinition(
            c.getString(0),c.getString(1),c.getString(2),c.getString(3),
            if(c.isNull(4))null else c.getString(4),if(c.isNull(5))null else c.getString(5),if(c.isNull(6))null else c.getString(6),if(c.isNull(7))null else c.getString(7),
            Phase9DefinitionStatus.valueOf(c.getString(8)),c.getLong(9),c.getString(10),
            if(c.isNull(11))null else c.getString(11),if(c.isNull(12))null else c.getLong(12),if(c.isNull(13))null else c.getLong(13)
        )
    }

    private fun attainStageIfMissing(characterUid:String,stageUid:String,transitionUid:String?,chapter:Long?,provenance:String){
        if(playerRowExists("player_evolution_stages","stage_uid",characterUid,stageUid)) return
        db.execSQL("INSERT INTO player_evolution_stages(campaign_id,character_uid,stage_uid,attained_via_transition_uid,attained_chapter,entry_version,provenance) VALUES(?,?,?,?,?,1,?)",arrayOf(campaignId,characterUid,stageUid,transitionUid,chapter,provenance))
    }

    private fun ensureFormModifiers(characterUid:String,formUid:String){
        val existing=ModifierStore(db,campaignId).modifiers(characterUid).associateBy{it.modifierUid}
        formBindings(formUid).forEach{binding->
            val uid="phase9:$characterUid:${binding.bindingUid}"
            val prior=existing[uid]
            if(prior==null){
                ModifierStore(db,campaignId).save(Modifier(uid,campaignId,characterUid,binding.targetDefinitionUid,binding.targetKind,ModifierLifecycle.TEMPORARY,binding.operation,binding.value,binding.priority,PHASE9_FORM_SOURCE_TYPE,formUid,true,null,null,true,binding.provenance,binding.bindingVersion))
            } else {
                require(prior.sourceType==PHASE9_FORM_SOURCE_TYPE && prior.sourceUid==formUid && prior.targetKind==binding.targetKind && prior.targetDefinitionUid==binding.targetDefinitionUid){"Existing Phase 9 form modifier identity conflict: $uid"}
            }
        }
    }

    private fun formBindings(formUid:String):List<FormModifierBinding>{
        val out=mutableListOf<FormModifierBinding>()
        db.rawQuery("SELECT binding_uid,world_pack_uid,form_uid,target_definition_uid,target_kind,operation,modifier_value,priority,binding_version,provenance FROM form_modifier_bindings WHERE form_uid=? ORDER BY binding_uid",arrayOf(formUid)).use{c->while(c.moveToNext())out+=FormModifierBinding(c.getString(0),c.getString(1),c.getString(2),c.getString(3),ModifierTargetKind.valueOf(c.getString(4)),ModifierOperation.valueOf(c.getString(5)),c.getDouble(6),c.getInt(7),c.getLong(8),c.getString(9))}
        return out
    }

    private fun validatePlayer(itemCampaign:String,characterUid:String,version:Long,provenance:String){
        require(itemCampaign==campaignId){"State belongs to another campaign"};require(characterUid.isNotBlank());require(version>=1L);require(provenance.isNotBlank())
    }
    private fun exists(table:String,column:String,value:String)=db.rawQuery("SELECT 1 FROM $table WHERE $column=? LIMIT 1",arrayOf(value)).use{it.moveToFirst()}
    private fun playerRowExists(table:String,column:String,characterUid:String,value:String)=db.rawQuery("SELECT 1 FROM $table WHERE campaign_id=? AND character_uid=? AND $column=? LIMIT 1",arrayOf(campaignId,characterUid,value)).use{it.moveToFirst()}
    private fun owner(table:String,column:String,value:String):String?=db.rawQuery("SELECT world_pack_uid FROM $table WHERE $column=? LIMIT 1",arrayOf(value)).use{c->if(c.moveToFirst())c.getString(0) else null}
    private fun tableExists(table:String)=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",arrayOf(table)).use{it.moveToFirst()}
    private fun hasColumn(table:String,column:String)=db.rawQuery("PRAGMA table_info($table)",null).use{c->val i=c.getColumnIndex("name");while(c.moveToNext())if(i>=0&&c.getString(i).equals(column,true))return@use true;false}
    private fun scalarLong(sql:String)=db.rawQuery(sql,null).use{c->if(c.moveToFirst())c.getLong(0) else 0L}

    private fun requireTargetExists(kind:ModifierTargetKind,uid:String){
        val pair=when(kind){ModifierTargetKind.STAT_EFFECTIVE->"stat_definitions" to "stat_uid";ModifierTargetKind.RESOURCE_MAXIMUM,ModifierTargetKind.RESOURCE_REGENERATION->"resource_definitions" to "resource_uid";ModifierTargetKind.SKILL_EFFECTIVE->"skill_definitions_v2" to "skill_uid";ModifierTargetKind.TECHNIQUE_EFFECTIVE->"technique_definitions_v2" to "technique_uid"}
        require(exists(pair.first,pair.second,uid)){"Form modifier targets missing ${kind.name} definition $uid"}
    }
    private fun requireTargetOwnedBy(kind:LegacyPhase9TargetKind,uid:String,worldPackUid:String){
        val pair=when(kind){LegacyPhase9TargetKind.ORIGIN->"origin_definitions_v2" to "origin_uid";LegacyPhase9TargetKind.INNATE_FEATURE->"innate_feature_definitions" to "feature_uid";LegacyPhase9TargetKind.EVOLUTION_STAGE->"evolution_stage_definitions" to "stage_uid";LegacyPhase9TargetKind.FORM_UNLOCK->"form_definitions" to "form_uid"}
        require(owner(pair.first,pair.second,uid)==worldPackUid){"Legacy mapping target $uid is missing or belongs to another World Pack"}
    }
    private fun legacyMappingExists(m:LegacyPhase9Mapping)=db.rawQuery("SELECT 1 FROM legacy_phase9_mappings WHERE world_pack_uid=? AND evidence_field=? AND evidence_value=? AND target_kind=?",arrayOf(m.worldPackUid,m.evidenceField,m.evidenceValue,m.targetKind.name)).use{it.moveToFirst()}
    private fun legacyMappings(worldPackUid:String,e:LegacyPhase9Evidence):List<LegacyPhase9Mapping>{
        val out=mutableListOf<LegacyPhase9Mapping>()
        db.rawQuery("SELECT world_pack_uid,evidence_field,evidence_value,target_kind,target_uid,mapping_version,provenance FROM legacy_phase9_mappings WHERE world_pack_uid=? AND evidence_field=? AND evidence_value=? ORDER BY target_kind,target_uid",arrayOf(worldPackUid,e.field,e.value)).use{c->while(c.moveToNext())out+=LegacyPhase9Mapping(c.getString(0),c.getString(1),c.getString(2),LegacyPhase9TargetKind.valueOf(c.getString(3)),c.getString(4),c.getLong(5),c.getString(6))}
        return out
    }

    private fun legacyStatusRow(characterUid:String):Map<String,Any?>{
        val cursor=if(hasColumn("character_status_snapshot","entity_uid")) db.rawQuery("SELECT * FROM character_status_snapshot WHERE entity_uid=? LIMIT 1",arrayOf(characterUid)) else {
            val count=scalarLong("SELECT COUNT(*) FROM character_status_snapshot");when(count){0L->return emptyMap();1L->db.rawQuery("SELECT * FROM character_status_snapshot LIMIT 1",null);else->error("Ambiguous legacy character_status_snapshot: $count rows without entity_uid")}
        }
        cursor.use{c->if(!c.moveToFirst())return emptyMap();return c.toRow()}
    }
    private fun Cursor.toRow():Map<String,Any?>{val row=linkedMapOf<String,Any?>();for(i in columnNames.indices)row[columnNames[i]]=when(getType(i)){Cursor.FIELD_TYPE_NULL->null;Cursor.FIELD_TYPE_INTEGER->getLong(i);Cursor.FIELD_TYPE_FLOAT->getDouble(i);Cursor.FIELD_TYPE_BLOB->"[BLOB ${getBlob(i).size} bytes]";else->getString(i)};return row}
}
