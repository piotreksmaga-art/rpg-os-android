package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

/** Canonical typed aggregate for NPC detail. Each protected domain keeps its own Phase38 projection state. */
data class NpcDetailProtectedProjection(
    val uid:String,
    val profile:VisibilityProjection<List<StatLine>>,
    val memories:VisibilityProjection<List<String>>,
    val beliefs:VisibilityProjection<List<String>>,
    val schedules:VisibilityProjection<List<String>>,
    val decisions:VisibilityProjection<List<String>>
) {
    /** Dashboard/presentation adapter; authority state has already been preserved above this boundary. */
    fun toPresentation():NpcDetail {
        val fields=profile.value ?: emptyList()
        val name=fields.firstOrNull{it.key=="name"}?.value ?: uid
        return NpcDetail(
            uid,name,fields,
            memories.value ?: emptyList(),
            beliefs.value ?: emptyList(),
            schedules.value ?: emptyList(),
            decisions.value ?: emptyList()
        )
    }
}

class NpcWorldDashboardReader(
    private val worldDb: SQLiteDatabase,
    private val saveDb: SQLiteDatabase,
    private val visibility: VisibilityAuthorityService = VisibilityAuthorityService()
) {
    private val canonCharacters = CanonCharacterProjectionReader(worldDb)
    private fun protectedReads(campaignUid:String)=ProtectedCampaignReadRepository.borrowed(saveDb,campaignUid){null}

    private fun <T> protectedProjection(
        audience:AudienceContext,
        purpose:PurposeContext,
        subjectKind:String,
        subjectUid:String,
        read:()->List<T>
    ):VisibilityProjection<List<T>> {
        val request=VisibilityRequest(audience,purpose,VisibilitySubjectRef(audience.campaignUid,subjectKind,subjectUid))
        return protectedReads(audience.campaignUid).protectedRows(audience,purpose,subjectKind,subjectUid,read).toVisibilityProjection(request)
    }

    /** Dashboard/presentation adapter. Canonical application callers use npcsProjection. */
    fun npcs(search: String, audience: AudienceContext, purpose: PurposeContext): List<NpcListItem> =
        npcsProjection(search, audience, purpose).value ?: emptyList()

    fun npcsProjection(search: String, audience: AudienceContext, purpose: PurposeContext): VisibilityProjection<List<NpcListItem>> =
        protectedProjection(audience,purpose,VisibilitySubjectKinds.PUBLIC_WORLD_ACTOR_PROFILE,"WORLD_ACTOR_LIST") {
            canonCharacters.list(search)
        }

    fun npcDetailProjection(uid:String,audience:AudienceContext,purpose:PurposeContext):NpcDetailProtectedProjection {
        val profile=protectedProjection(audience,purpose,VisibilitySubjectKinds.PUBLIC_WORLD_ACTOR_PROFILE,uid) {
            canonCharacters.profileFields(uid)
        }
        fun privateRows(kind:String,sql:String):VisibilityProjection<List<String>> = protectedProjection(audience,purpose,kind,uid) {
            val out=mutableListOf<String>()
            saveDb.rawQuery(sql,arrayOf(uid)).use { c -> while(c.moveToNext()) out += c.getString(0) }
            out
        }
        return NpcDetailProtectedProjection(
            uid=uid,
            profile=profile,
            memories=privateRows(VisibilitySubjectKinds.WORLD_ACTOR_PRIVATE_MEMORY,"SELECT summary FROM npc_memories_v2 WHERE entity_uid=? ORDER BY importance DESC,chapter DESC LIMIT 50"),
            beliefs=privateRows(VisibilitySubjectKinds.WORLD_ACTOR_PRIVATE_BELIEF,"SELECT content_summary FROM npc_beliefs WHERE entity_uid=? ORDER BY confidence DESC LIMIT 50"),
            schedules=privateRows(VisibilitySubjectKinds.WORLD_ACTOR_PRIVATE_SCHEDULE,"SELECT summary FROM npc_schedules WHERE entity_uid=? ORDER BY start_day DESC LIMIT 20"),
            decisions=privateRows(VisibilitySubjectKinds.WORLD_ACTOR_PRIVATE_DECISION,"SELECT action_type||' • '||COALESCE(reason_summary,'') FROM npc_decisions WHERE entity_uid=? ORDER BY day DESC LIMIT 50")
        )
    }

    /** Dashboard/presentation adapter. Canonical application callers use npcDetailProjection. */
    fun npcDetail(uid: String, audience: AudienceContext, purpose: PurposeContext): NpcDetail =
        npcDetailProjection(uid,audience,purpose).toPresentation()

    /** Dashboard/presentation adapter. Canonical application callers use relationEdgesProjection. */
    fun relationEdges(audience: AudienceContext, purpose: PurposeContext): List<RelationEdge> =
        relationEdgesProjection(audience, purpose).value ?: emptyList()

    fun relationEdgesProjection(audience: AudienceContext, purpose: PurposeContext): VisibilityProjection<List<RelationEdge>> {
        val request = VisibilityRequest(audience, purpose, VisibilitySubjectRef(audience.campaignUid, VisibilitySubjectKinds.RELATIONSHIP_DATA, "RELATION_EDGES"))
        return protectedReads(audience.campaignUid).policyRows(audience,purpose,VisibilitySubjectKinds.RELATIONSHIP_DATA,"RELATION_EDGES") {
            val out = mutableListOf<RelationEdge>()
            saveDb.rawQuery("""SELECT entity_a_uid,entity_b_uid,relationship_type,relationship_score
                               FROM relationships_v2 ORDER BY ABS(relationship_score) DESC LIMIT 300""", null).use { c ->
                while (c.moveToNext()) out += RelationEdge(c.getString(0), c.getString(1), c.getString(2), c.getFloat(3))
            }
            out
        }.toVisibilityProjection(request)
    }

    /** Dashboard/presentation adapter. Canonical application callers use economiesProjection. */
    fun economies(audience: AudienceContext, purpose: PurposeContext): List<EconomySummary> =
        economiesProjection(audience, purpose).value ?: emptyList()

    fun economiesProjection(audience: AudienceContext, purpose: PurposeContext): VisibilityProjection<List<EconomySummary>> {
        val request = VisibilityRequest(audience, purpose, VisibilitySubjectRef(audience.campaignUid, VisibilitySubjectKinds.ECONOMY_DATA, "ECONOMIES"))
        return protectedReads(audience.campaignUid).policyRows(audience,purpose,VisibilitySubjectKinds.ECONOMY_DATA,"ECONOMIES") {
            val out = mutableListOf<EconomySummary>()
            saveDb.rawQuery("SELECT country_uid,treasury,prosperity,stability FROM country_economies ORDER BY treasury DESC", null).use { c ->
                while (c.moveToNext()) out += EconomySummary(c.getString(0), c.getString(1), c.getString(2), c.getString(3))
            }
            out
        }.toVisibilityProjection(request)
    }

    /** Dashboard/presentation adapter. Canonical application callers use warsProjection. */
    fun wars(audience: AudienceContext, purpose: PurposeContext): List<WarSummary> =
        warsProjection(audience, purpose).value ?: emptyList()

    fun warsProjection(audience: AudienceContext, purpose: PurposeContext): VisibilityProjection<List<WarSummary>> =
        protectedProjection(audience,purpose,VisibilitySubjectKinds.PUBLIC_WAR_SUMMARY,"WARS") {
            val out = mutableListOf<WarSummary>()
            saveDb.rawQuery("""SELECT COALESCE(t.name,a.event_type),a.status,COALESCE(a.public_summary,'')
                               FROM active_world_events a LEFT JOIN timeline_events t ON t.timeline_uid=a.timeline_uid
                               WHERE a.event_type LIKE '%war%' OR a.event_type LIKE '%military%'""", null).use { c ->
                while (c.moveToNext()) out += WarSummary(c.getString(0), c.getString(1), c.getString(2))
            }
            out
        }
}
