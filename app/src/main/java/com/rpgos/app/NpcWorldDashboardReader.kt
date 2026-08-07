package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

class NpcWorldDashboardReader(
    private val worldDb: SQLiteDatabase,
    private val saveDb: SQLiteDatabase
) {
    fun npcs(search:String=""):List<NpcListItem>{
        val out=mutableListOf<NpcListItem>()
        val sql=if(search.isBlank())
            """SELECT character_uid,name,COALESCE(clan_uid,''),COALESCE(village_uid,''),COALESCE(status,'')
               FROM canon_characters_v2 ORDER BY name LIMIT 1000"""
        else
            """SELECT character_uid,name,COALESCE(clan_uid,''),COALESCE(village_uid,''),COALESCE(status,'')
               FROM canon_characters_v2 WHERE lower(name) LIKE lower(?) ORDER BY name LIMIT 1000"""
        val args=if(search.isBlank()) null else arrayOf("%$search%")
        try{
            worldDb.rawQuery(sql,args).use{c->
                while(c.moveToNext())out+=NpcListItem(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4))
            }
        }catch(_:Exception){}
        return out
    }

    fun npcDetail(uid:String):NpcDetail{
        val fields=mutableListOf<StatLine>()
        try{
            worldDb.rawQuery("SELECT * FROM canon_characters_v2 WHERE character_uid=?",arrayOf(uid)).use{c->
                if(c.moveToFirst())for(i in c.columnNames.indices)fields+=StatLine(c.columnNames[i],if(c.isNull(i))"—" else c.getString(i))
            }
        }catch(_:Exception){}
        val memories=mutableListOf<String>()
        try{saveDb.rawQuery("SELECT summary FROM npc_memories_v2 WHERE entity_uid=? ORDER BY importance DESC,chapter DESC LIMIT 50",arrayOf(uid)).use{c->while(c.moveToNext())memories+=c.getString(0)}}catch(_:Exception){}
        val beliefs=mutableListOf<String>()
        try{saveDb.rawQuery("SELECT content_summary FROM npc_beliefs WHERE entity_uid=? ORDER BY confidence DESC LIMIT 50",arrayOf(uid)).use{c->while(c.moveToNext())beliefs+=c.getString(0)}}catch(_:Exception){}
        val schedules=mutableListOf<String>()
        try{saveDb.rawQuery("SELECT summary FROM npc_schedules WHERE entity_uid=? ORDER BY start_day DESC LIMIT 20",arrayOf(uid)).use{c->while(c.moveToNext())schedules+=c.getString(0)}}catch(_:Exception){}
        val decisions=mutableListOf<String>()
        try{saveDb.rawQuery("SELECT action_type||' • '||COALESCE(reason_summary,'') FROM npc_decisions WHERE entity_uid=? ORDER BY day DESC LIMIT 50",arrayOf(uid)).use{c->while(c.moveToNext())decisions+=c.getString(0)}}catch(_:Exception){}
        val name=fields.firstOrNull{it.key=="name"}?.value?:uid
        return NpcDetail(uid,name,fields,memories,beliefs,schedules,decisions)
    }

    fun relationEdges():List<RelationEdge>{
        val out=mutableListOf<RelationEdge>()
        try{
            saveDb.rawQuery("""SELECT entity_a_uid,entity_b_uid,relationship_type,relationship_score
                               FROM relationships_v2 ORDER BY ABS(relationship_score) DESC LIMIT 300""",null).use{c->
                while(c.moveToNext())out+=RelationEdge(c.getString(0),c.getString(1),c.getString(2),c.getFloat(3))
            }
        }catch(_:Exception){}
        return out
    }

    fun economies():List<EconomySummary>{
        val out=mutableListOf<EconomySummary>()
        try{
            saveDb.rawQuery("""SELECT country_uid,treasury,prosperity,stability FROM country_economies ORDER BY treasury DESC""",null).use{c->
                while(c.moveToNext())out+=EconomySummary(c.getString(0),c.getString(1),c.getString(2),c.getString(3))
            }
        }catch(_:Exception){}
        return out
    }

    fun wars():List<WarSummary>{
        val out=mutableListOf<WarSummary>()
        try{
            saveDb.rawQuery("""SELECT COALESCE(t.name,a.event_type),a.status,COALESCE(a.gm_summary,a.public_summary,'')
                               FROM active_world_events a LEFT JOIN timeline_events t ON t.timeline_uid=a.timeline_uid
                               WHERE a.event_type LIKE '%war%' OR a.event_type LIKE '%military%'""",null).use{c->
                while(c.moveToNext())out+=WarSummary(c.getString(0),c.getString(1),c.getString(2))
            }
        }catch(_:Exception){}
        return out
    }
}
