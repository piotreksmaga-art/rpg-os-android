package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

class NpcWorldDashboardReader(
    private val worldDb: SQLiteDatabase,
    private val saveDb: SQLiteDatabase,
    private val visibility: VisibilityAuthorityService = VisibilityAuthorityService()
) {
    fun npcs(search:String="", audience: AudienceContext, purpose: PurposeContext):List<NpcListItem>{
        val request = VisibilityRequest(audience,purpose,VisibilitySubjectRef(audience.campaignUid,VisibilitySubjectKinds.PUBLIC_WORLD_ACTOR_PROFILE,"WORLD_ACTOR_LIST"))
        return visibility.project(request) {
            val out=mutableListOf<NpcListItem>()
            val sql=if(search.isBlank())
                """SELECT character_uid,name,COALESCE(clan_uid,''),COALESCE(village_uid,''),COALESCE(status,'')
                   FROM canon_characters_v2 ORDER BY name LIMIT 1000"""
            else
                """SELECT character_uid,name,COALESCE(clan_uid,''),COALESCE(village_uid,''),COALESCE(status,'')
                   FROM canon_characters_v2 WHERE lower(name) LIKE lower(?) ORDER BY name LIMIT 1000"""
            val args=if(search.isBlank()) null else arrayOf("%$search%")
            worldDb.rawQuery(sql,args).use{c->while(c.moveToNext())out+=NpcListItem(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4))}
            out
        }.value ?: emptyList()
    }

    fun npcDetail(uid:String, audience: AudienceContext, purpose: PurposeContext):NpcDetail{
        val profileRequest=VisibilityRequest(audience,purpose,VisibilitySubjectRef(audience.campaignUid,VisibilitySubjectKinds.PUBLIC_WORLD_ACTOR_PROFILE,uid))
        val profile = visibility.project(profileRequest) {
            val fields=mutableListOf<StatLine>()
            worldDb.rawQuery("""SELECT character_uid,name,sex,COALESCE(clan_uid,''),COALESCE(village_uid,''),COALESCE(rank_title,''),COALESCE(affiliation_summary,''),COALESCE(status,'')
                FROM canon_characters_v2 WHERE character_uid=?""",arrayOf(uid)).use{c->
                if(c.moveToFirst())for(i in c.columnNames.indices)fields+=StatLine(c.columnNames[i],if(c.isNull(i))"—" else c.getString(i))
            }
            fields
        }.value ?: emptyList()

        fun privateRows(kind:String, sql:String):List<String>{
            val req=VisibilityRequest(audience,purpose,VisibilitySubjectRef(audience.campaignUid,kind,uid))
            return visibility.project(req){
                val out=mutableListOf<String>()
                saveDb.rawQuery(sql,arrayOf(uid)).use{c->while(c.moveToNext())out+=c.getString(0)}
                out
            }.value ?: emptyList()
        }
        val memories=privateRows(VisibilitySubjectKinds.WORLD_ACTOR_PRIVATE_MEMORY,"SELECT summary FROM npc_memories_v2 WHERE entity_uid=? ORDER BY importance DESC,chapter DESC LIMIT 50")
        val beliefs=privateRows(VisibilitySubjectKinds.WORLD_ACTOR_PRIVATE_BELIEF,"SELECT content_summary FROM npc_beliefs WHERE entity_uid=? ORDER BY confidence DESC LIMIT 50")
        val schedules=privateRows(VisibilitySubjectKinds.WORLD_ACTOR_PRIVATE_SCHEDULE,"SELECT summary FROM npc_schedules WHERE entity_uid=? ORDER BY start_day DESC LIMIT 20")
        val decisions=privateRows(VisibilitySubjectKinds.WORLD_ACTOR_PRIVATE_DECISION,"SELECT action_type||' • '||COALESCE(reason_summary,'') FROM npc_decisions WHERE entity_uid=? ORDER BY day DESC LIMIT 50")
        val name=profile.firstOrNull{it.key=="name"}?.value?:uid
        return NpcDetail(uid,name,profile,memories,beliefs,schedules,decisions)
    }

    fun relationEdges(audience: AudienceContext, purpose: PurposeContext):List<RelationEdge>{
        val request=VisibilityRequest(audience,purpose,VisibilitySubjectRef(audience.campaignUid,VisibilitySubjectKinds.PUBLIC_DASHBOARD_DATA,"RELATION_EDGES"))
        return visibility.project(request){
            val out=mutableListOf<RelationEdge>()
            saveDb.rawQuery("""SELECT entity_a_uid,entity_b_uid,relationship_type,relationship_score
                               FROM relationships_v2 ORDER BY ABS(relationship_score) DESC LIMIT 300""",null).use{c->
                while(c.moveToNext())out+=RelationEdge(c.getString(0),c.getString(1),c.getString(2),c.getFloat(3))
            }
            out
        }.value ?: emptyList()
    }

    fun economies(audience: AudienceContext, purpose: PurposeContext):List<EconomySummary>{
        val request=VisibilityRequest(audience,purpose,VisibilitySubjectRef(audience.campaignUid,VisibilitySubjectKinds.PUBLIC_DASHBOARD_DATA,"ECONOMIES"))
        return visibility.project(request){
            val out=mutableListOf<EconomySummary>()
            saveDb.rawQuery("SELECT country_uid,treasury,prosperity,stability FROM country_economies ORDER BY treasury DESC",null).use{c->
                while(c.moveToNext())out+=EconomySummary(c.getString(0),c.getString(1),c.getString(2),c.getString(3))
            }
            out
        }.value ?: emptyList()
    }

    fun wars(audience: AudienceContext, purpose: PurposeContext):List<WarSummary>{
        val diagnostic = audience.audienceKindUid==AudienceKinds.DEVELOPER_DIAGNOSTIC && purpose.purposeUid==VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION
        val kind=if(diagnostic) VisibilitySubjectKinds.WORLD_EVENT_GM_DETAIL else VisibilitySubjectKinds.PUBLIC_WAR_SUMMARY
        val request=VisibilityRequest(audience,purpose,VisibilitySubjectRef(audience.campaignUid,kind,"WARS"))
        return visibility.project(request){
            val out=mutableListOf<WarSummary>()
            val summaryExpr=if(diagnostic) "COALESCE(a.public_summary,a.gm_summary,'')" else "COALESCE(a.public_summary,'')"
            saveDb.rawQuery("""SELECT COALESCE(t.name,a.event_type),a.status,$summaryExpr
                               FROM active_world_events a LEFT JOIN timeline_events t ON t.timeline_uid=a.timeline_uid
                               WHERE a.event_type LIKE '%war%' OR a.event_type LIKE '%military%'""",null).use{c->
                while(c.moveToNext())out+=WarSummary(c.getString(0),c.getString(1),c.getString(2))
            }
            out
        }.value ?: emptyList()
    }

    @Deprecated("Phase38 protected reads require audience and purpose") fun npcs(search:String="")=emptyList<NpcListItem>()
    @Deprecated("Phase38 protected reads require audience and purpose") fun npcDetail(uid:String)=NpcDetail(uid,uid,emptyList(),emptyList(),emptyList(),emptyList(),emptyList())
    @Deprecated("Phase38 protected reads require audience and purpose") fun relationEdges()=emptyList<RelationEdge>()
    @Deprecated("Phase38 protected reads require audience and purpose") fun economies()=emptyList<EconomySummary>()
    @Deprecated("Phase38 protected reads require audience and purpose") fun wars()=emptyList<WarSummary>()
}
