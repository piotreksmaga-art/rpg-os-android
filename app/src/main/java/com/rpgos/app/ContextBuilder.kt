package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

class ContextBuilder(
    private val saveDb: SQLiteDatabase,
    private val worldDb: SQLiteDatabase,
    private val visibility: VisibilityAuthorityService = VisibilityAuthorityService(),
    private val protectedReadsOverride: ProtectedCampaignReadRepository? = null,
    private val worldActorPerceptionRuntime: Phase38WorldActorPerceptionRuntime? = null
) {
    fun build(playerInput: String, chapter: Int, audience: AudienceContext, purpose: PurposeContext): ContextBundle {
        if (audience.campaignUid != purpose.campaignUid) throw VisibilityAuthorityFailure.CrossCampaign()
        val campaignRef = ActiveCampaignRef.fromDatabasePath(saveDb.path)
        if (campaignRef.campaignId != audience.campaignUid) throw VisibilityAuthorityFailure.CrossCampaign()
        val envelope = visibility.envelope(audience, purpose)
        if (envelope.maximumDisclosure == DisclosureLevel.DENY) {
            return emptyDeniedBundle(playerInput, chapter, envelope)
        }

        val campaign = optionalPresentationOne(saveDb,"SELECT campaign_name,schema_version,current_chapter,current_tome FROM campaign_meta WHERE id=1")
        val time = optionalPresentationOne(saveDb,"SELECT year_label,era_name,season,hour,minute,absolute_day FROM campaign_calendar WHERE id=1")
        val activePlayerRef = ActivePlayerStore(saveDb,campaignRef.campaignId).active()
        val playerUid = activePlayerRef?.playerUid
        val protectedReads = protectedReadsOverride ?: ProtectedCampaignReadRepository.borrowed(saveDb, campaignRef.campaignId) { activePlayerRef }
        val trustedPrincipal = protectedReads.trustedPrincipal(audience)
        val playerStateRead: ProtectedReadResult<PlayerStateSnapshot> = if (playerUid != null) {
            protectedReads.playerState(audience, purpose, playerUid)
        } else ProtectedReadResult.NoData
        val playerStateAuthorized = playerStateRead is ProtectedReadResult.Allow<*>
        val position = if(playerUid!=null && playerStateAuthorized) queryOne(saveDb,"SELECT entity_uid,location_uid,x_coord,y_coord,last_updated_day,updated_chapter FROM entity_positions WHERE entity_uid=? LIMIT 1",arrayOf(playerUid)) else emptyMap()
        val typedStats=if(playerUid!=null && playerStateAuthorized)queryMany(saveDb,"SELECT stat_uid,base_value,version FROM player_stats WHERE campaign_id=? AND character_uid=? ORDER BY stat_uid",arrayOf(campaignRef.campaignId,playerUid))else emptyList()
        val typedResources=if(playerUid!=null && playerStateAuthorized)queryMany(saveDb,"SELECT resource_uid,current_value,version FROM player_resources WHERE campaign_id=? AND character_uid=? ORDER BY resource_uid",arrayOf(campaignRef.campaignId,playerUid))else emptyList()
        val ownership=if(playerUid!=null && playerStateAuthorized)queryMany(saveDb,"SELECT ownership_record_uid,owner_kind_uid,owner_uid,asset_kind_uid,asset_uid,ownership_type_uid,share_units,valid_from_order,record_version,provenance FROM ownership_records WHERE campaign_id=? AND owner_kind_uid='CHARACTER' AND owner_uid=? AND record_status='ACTIVE' AND valid_until_order IS NULL ORDER BY asset_kind_uid,asset_uid,ownership_type_uid",arrayOf(campaignRef.campaignId,playerUid))else emptyList()
        val projects=if(playerUid!=null && playerStateAuthorized)queryMany(saveDb,"SELECT project_uid,project_type_uid,title,objective_summary,target_domain_uid,target_kind_uid,target_uid,intended_output_kind_uid,progress_cap_units,created_order,started_order,project_version,source_event_uid,provenance FROM development_projects WHERE campaign_id=? AND ((initiator_kind_uid='CHARACTER' AND initiator_uid=?) OR (beneficiary_kind_uid='CHARACTER' AND beneficiary_uid=?)) ORDER BY project_uid",arrayOf(campaignRef.campaignId,playerUid,playerUid))else emptyList()

        val status=linkedMapOf<String,Any?>("chapter" to chapter,"player_input" to playerInput,"player_uid" to playerUid,"campaign" to campaign).apply{
            position.forEach{(k,v)->put(k,v)}
            if(playerUid!=null && playerStateAuthorized){
                put("finance_ledger",FinancialContextReader(saveDb,campaignRef.campaignId).forPlayerUid(playerUid))
                put("injuries",queryMany(saveDb,"SELECT injury_uid,body_part_uid,severity,pain,bleeding,status,created_chapter FROM injuries_v2 WHERE entity_uid=? AND status!='healed' ORDER BY severity DESC LIMIT 12",arrayOf(playerUid)))
                put("typed_stats",typedStats);put("typed_resources",typedResources);put("ownership",ownership);put("projects",projects)
            }
        }
        val scene=linkedMapOf<String,Any?>("query" to playerInput,"player_uid" to playerUid).apply{
            position.forEach{(k,v)->put(k,v)}
            val locationUid=position["location_uid"] as? String
            if(!locationUid.isNullOrBlank()) queryOne(worldDb,"SELECT location_uid,name,location_type,region_uid,description FROM map_locations_v2 WHERE location_uid=? LIMIT 1",arrayOf(locationUid)).forEach{(k,v)->put("location_$k",v)}
        }

        val playerFacing = audience.audienceKindUid == AudienceKinds.PLAYER
        val trustedDiagnostic = purpose.purposeUid == VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION &&
            trustedPrincipal?.isPrivileged(Phase38RuntimeAuthority.PRIV_DIAGNOSTIC) == true
        fun diagnosticRows(uid:String, read:()->List<Map<String,Any?>>):List<Map<String,Any?>> =
            if(!trustedDiagnostic) emptyList() else when(val result=protectedReads.diagnosticRows(audience,purpose,uid,read)){
                is ProtectedReadResult.Allow -> result.value
                else -> emptyList()
            }
        val threads = diagnosticRows("STORY_THREADS") { queryMany(saveDb,"SELECT thread_uid,title,thread_type,status,priority,last_advanced_chapter,description FROM story_threads WHERE status='active' ORDER BY priority DESC,last_advanced_chapter DESC LIMIT 20") }
        val worldActorReasoning = audience.audienceKindUid == AudienceKinds.WORLD_ACTOR && purpose.purposeUid == VisibilityPurposeKinds.WORLD_ACTOR_REASONING
        // Missions/future pressure are objective/system domains, not perception. Until a canonical
        // actor knowledge/access carrier exists for them they are category F and stay out of actor reasoning.
        val missions = if(worldActorReasoning) emptyList() else queryMany(saveDb,"SELECT mission_uid,title,mission_rank,status,objective_summary,reward_ryo,deadline_day,location_uid,consequence_on_failure FROM missions_v3 WHERE status IN ('available','active','assigned') ORDER BY CASE status WHEN 'active' THEN 0 WHEN 'assigned' THEN 1 ELSE 2 END,reward_ryo DESC LIMIT 20")
        val pressures = if(worldActorReasoning) emptyList() else queryMany(saveDb,"SELECT pressure_uid,target_type,target_uid,starts_day,peaks_day,pressure_type,magnitude,summary FROM future_world_pressure WHERE hidden=0 ORDER BY magnitude DESC LIMIT 20")
        val objectiveWorldEvents = WorldReader(worldDb,saveDb,visibility).activeEvents(audience,purpose)
        val activeWorldEvents: List<Map<String,Any?>> = if(worldActorReasoning){
            val runtime = worldActorPerceptionRuntime
            val trusted = trustedPrincipal
            if(runtime == null || trusted == null) emptyList() else objectiveWorldEvents.mapNotNull { objective ->
                val projected = runtime.projectWorldEvent(audience,trusted,objective)
                if(projected.decision.dataState != ProjectionDataState.DISCLOSED) null else linkedMapOf<String,Any?>().apply {
                    putAll(projected.presentationPayload())
                    put("subject_uid", projected.subject.subjectUid)
                    put("perception_disclosure", projected.decision.level.name)
                }
            }
        } else objectiveWorldEvents.map { e -> mapOf("name" to e.name,"status" to e.status,"summary" to e.summary) }
        val chronicle = if(trustedDiagnostic) diagnosticRows("CHRONICLE_FULL") { queryMany(saveDb,"SELECT chapter,title,active_threads_json,decisions_json,consequences_json,quests_json,continuity_warnings_json FROM chapter_manifests_v2 ORDER BY chapter DESC LIMIT 10") } else queryMany(saveDb,"SELECT chapter,title FROM chapter_manifests_v2 ORDER BY chapter DESC LIMIT 10")
        val longTermMemory = diagnosticRows("ALL_MEMORIES") {
            queryMany(saveDb,"SELECT memory_uid,entity_uid,memory_type,subject_uid,chapter,day,importance,emotional_valence,accuracy,summary FROM npc_memories_v2 WHERE active=1 ORDER BY importance DESC,chapter DESC LIMIT 30")
        }

        val relevantNpcIds=LinkedHashSet<String>()
        activeWorldEvents.forEach { (it["subject_uid"] as? String)?.let(relevantNpcIds::add) }
        val npcRows=mutableListOf<Map<String,Any?>>()
        for(id in relevantNpcIds.take(16)){
            val req=VisibilityRequest(audience,purpose,VisibilitySubjectRef(campaignRef.campaignId,VisibilitySubjectKinds.PUBLIC_WORLD_ACTOR_PROFILE,id))
            visibility.project(req){queryOne(worldDb,"SELECT character_uid,name,sex,clan_uid,village_uid,rank_title,affiliation_summary FROM canon_characters_v2 WHERE character_uid=? LIMIT 1",arrayOf(id))}.value?.takeIf{it.isNotEmpty()}?.let(npcRows::add)
        }

        val knowledgeRows = mutableListOf<Map<String,Any?>>()
        if (purpose.purposeUid == VisibilityPurposeKinds.WORLD_ACTOR_REASONING) {
            val projection=KnowledgeContextProjection(saveDb,campaignRef.campaignId)
            trustedPrincipal?.cognitionHolders.orEmpty().forEach { holder ->
                val req=VisibilityRequest(audience,purpose,VisibilitySubjectRef(campaignRef.campaignId,VisibilitySubjectKinds.PHASE37_HOLDER_KNOWLEDGE,"${holder.holderKindUid}:${holder.holderUid}",holder=holder))
                visibility.project(req,trustedPrincipal){projection.forHolders(listOf(holder))}.value?.let(knowledgeRows::addAll)
            }
        }

        val constraints=diagnosticRows("CANON_CONSTRAINTS") { queryMany(worldDb,"SELECT constraint_uid,subject_type,subject_uid,constraint_key,constraint_value,canon_scope,notes FROM canon_constraints_v2 LIMIT 40") }
        val skills=if(playerUid!=null && playerStateAuthorized){val r=SkillStore(saveDb,campaignRef.campaignId).reconciled(playerUid);r.skills.map{i->val s=i.playerSkill;linkedMapOf<String,Any?>("entity_uid" to s.characterUid,"skill_uid" to s.skillUid,"mastery" to s.baseMastery,"progress_value" to s.progressValue,"canonical" to true)}}else emptyList()
        val techniques=if(playerUid!=null && playerStateAuthorized){val r=TechniqueStore(saveDb,campaignRef.campaignId).reconciled(playerUid);r.techniques.map{i->val t=i.playerTechnique;linkedMapOf<String,Any?>("entity_uid" to t.characterUid,"technique_uid" to t.techniqueUid,"mastery" to t.baseMastery,"progress_value" to t.progressValue,"canonical" to true)}+r.unresolvedLegacy.map{l->linkedMapOf<String,Any?>("entity_uid" to l.characterUid,"technique_uid" to l.legacyTechniqueUid,"mastery_raw" to l.masteryRaw,"xp_raw" to l.xpRaw,"learned_chapter_raw" to l.learnedChapterRaw,"last_used_chapter_raw" to l.lastUsedChapterRaw,"usage_count_raw" to l.usageCountRaw,"success_count_raw" to l.successCountRaw,"failure_count_raw" to l.failureCountRaw,"is_equipped_raw" to l.isEquippedRaw,"notes_raw" to l.notesRaw,"display_name" to l.displayName,"category" to l.category,"legacy_chakra_cost_override_raw" to l.chakraCostOverrideRaw,"legacy_base_chakra_cost_raw" to l.baseChakraCostRaw,"authority_source" to "LEGACY_UNRESOLVED","canonical" to false)}}else emptyList()
        val inventory=if(playerUid!=null && playerStateAuthorized){val r=InventoryStore(saveDb,campaignRef.campaignId).reconciled(playerUid);r.stacks.map{i->linkedMapOf<String,Any?>("entity_uid" to i.stack.characterUid,"item_definition_uid" to i.stack.itemDefinitionUid,"quantity" to i.stack.quantity,"canonical" to true)}+r.uniqueItems.map{i->linkedMapOf<String,Any?>("entity_uid" to i.entry.characterUid,"item_definition_uid" to i.instance.itemDefinitionUid,"item_instance_uid" to i.entry.itemInstanceUid,"canonical" to true)}+r.unresolvedLegacy.map{e->linkedMapOf<String,Any?>("entity_uid" to e.characterUid,"legacy_evidence_uid" to e.evidenceUid,"item_name" to e.itemName,"row_count" to e.rowCount,"raw_fields" to e.rawFields,"authority_source" to "LEGACY_UNRESOLVED","canonical" to false)}}else emptyList()
        val organizations=if(playerUid!=null && playerStateAuthorized)queryMany(saveDb,"SELECT organization_uid,character_uid,unit_uid,position_uid,role_title,status FROM organization_memberships_v3 WHERE character_uid=? AND status='active'",arrayOf(playerUid))else emptyList()

        val campaignTruthRead: ProtectedReadResult<List<Map<String, Any?>>> = protectedReads.truthContextRows(audience, purpose)
        val campaignTruth = when (campaignTruthRead) {
            is ProtectedReadResult.Allow -> campaignTruthRead.value
            else -> emptyList()
        }
        val canonDivergences = if(trustedDiagnostic) when(val result=protectedReads.canonDivergences(audience,purpose)){
            is ProtectedReadResult.Allow -> result.value
            else -> emptyList()
        } else emptyList()
        val playerState = when (playerStateRead) {
            is ProtectedReadResult.Allow -> playerStateRead.value.toContextMap()
            else -> emptyMap()
        }
        val meta=linkedMapOf<String,Any?>(
            "engine" to "ContextBundle Engine v2/P38","schema" to 2,"campaign_id" to campaignRef.campaignId,"chapter" to chapter,
            "player_uid" to playerUid,"audience_kind_uid" to audience.audienceKindUid,"purpose_uid" to purpose.purposeUid,
            "maximum_disclosure" to envelope.maximumDisclosure.name,"campaign_truth" to campaignTruth.size,"npc_knowledge" to knowledgeRows.size,
            "player_facing" to playerFacing,
            "campaign_truth_state" to campaignTruthRead.stateUid,
            "player_state_state" to playerStateRead.stateUid
        )
        return ContextBundle(status,scene,time,threads,npcRows,knowledgeRows,missions,pressures,constraints,chronicle,longTermMemory,skills,techniques,inventory,organizations,activeWorldEvents,
            npcMemories=emptyList(),campaignTruth=campaignTruth,canonDivergences=canonDivergences,playerState=playerState,contextMeta=meta,visibilityEnvelope=envelope)
    }

    private fun emptyDeniedBundle(playerInput:String,chapter:Int,envelope:VisibilityProjectionEnvelope)=ContextBundle(
        mapOf("chapter" to chapter,"player_input" to playerInput),mapOf("query" to playerInput),emptyMap(),emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),
        visibilityEnvelope=envelope,contextMeta=mapOf("visibility_denied" to true,"campaign_id" to envelope.campaignUid)
    )

    private fun optionalPresentationOne(db:SQLiteDatabase,sql:String,args:Array<String>?=null):Map<String,Any?> = try {
        val out=mutableMapOf<String,Any?>()
        db.rawQuery(sql,args).use { c ->
            if(c.moveToFirst()) for(i in c.columnNames.indices) out[c.columnNames[i]]=when(c.getType(i)){
                android.database.Cursor.FIELD_TYPE_NULL->null
                android.database.Cursor.FIELD_TYPE_INTEGER->c.getLong(i)
                android.database.Cursor.FIELD_TYPE_FLOAT->c.getDouble(i)
                android.database.Cursor.FIELD_TYPE_BLOB->"[BLOB ${c.getBlob(i).size} bytes]"
                else->c.getString(i)
            }
        }
        out
    } catch (_: android.database.sqlite.SQLiteException) { emptyMap() }
    private fun queryOne(db:SQLiteDatabase,sql:String,args:Array<String>?=null):Map<String,Any?> = queryMany(db,sql,args).firstOrNull()?:emptyMap()
    private fun queryMany(db:SQLiteDatabase,sql:String,args:Array<String>?=null):List<Map<String,Any?>> {
        val out=mutableListOf<Map<String,Any?>>()
        try {
            db.rawQuery(sql,args).use{c->val names=c.columnNames;while(c.moveToNext()){val row=LinkedHashMap<String,Any?>();for(i in names.indices)row[names[i]]=when(c.getType(i)){android.database.Cursor.FIELD_TYPE_NULL->null;android.database.Cursor.FIELD_TYPE_INTEGER->c.getLong(i);android.database.Cursor.FIELD_TYPE_FLOAT->c.getDouble(i);android.database.Cursor.FIELD_TYPE_BLOB->"[BLOB ${c.getBlob(i).size} bytes]";else->c.getString(i)};out+=row}}
        } catch (failure: Exception) {
            throw VisibilityAuthorityFailure.CorruptRead("CONTEXT_QUERY", failure)
        }
        return out
    }
}
