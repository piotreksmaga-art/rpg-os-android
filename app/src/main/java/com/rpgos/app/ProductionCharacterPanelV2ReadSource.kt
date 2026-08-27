package com.rpgos.app

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlin.math.roundToLong

/** Production adapter from the current authoritative campaign stores to the Phase-24 V2 panel. */
internal class ProductionCharacterPanelV2ReadSource(
    private val db:SQLiteDatabase
):CharacterPanelV2ReadSource{
    override fun identity(campaignUid:String,characterUid:String):List<CharacterPanelIdentityV2> = buildList{
        queryIfTable("campaign_truth_records","""SELECT predicate,object_value FROM campaign_truth_records
            WHERE campaign_id=? AND subject_uid=? AND active=1 AND predicate LIKE 'RPGOS:PLAYER_IDENTITY:%'
            ORDER BY predicate""",arrayOf(campaignUid,characterUid)){c->
            while(c.moveToNext())add(CharacterPanelIdentityV2(c.getString(0).substringAfterLast(':'),c.getString(1)))
        }
        queryIfTable("player_origins_v2","""SELECT d.display_name,p.relationship_kind FROM player_origins_v2 p
            JOIN origin_definitions_v2 d ON d.origin_uid=p.origin_uid
            WHERE p.campaign_id=? AND p.character_uid=? ORDER BY d.display_name""",arrayOf(campaignUid,characterUid)){c->
            while(c.moveToNext())add(CharacterPanelIdentityV2("ORIGIN:${c.position}","${c.getString(0)} • ${c.getString(1)}"))
        }
    }

    override fun stats(campaignUid:String,characterUid:String)=buildList{
        queryIfTable("player_stats","""SELECT p.stat_uid,p.base_value,d.stat_key FROM player_stats p
            JOIN stat_definitions d ON d.stat_uid=p.stat_uid WHERE p.campaign_id=? AND p.character_uid=? ORDER BY d.stat_key""",arrayOf(campaignUid,characterUid)){c->
            while(c.moveToNext())add(CharacterPanelExactValueV2(c.getString(0),c.getDouble(1).roundToLong(),c.getString(2)))
        }
    }

    override fun resources(campaignUid:String,characterUid:String)=buildList{
        queryIfTable("player_resources","""SELECT p.resource_uid,p.current_value,d.resource_key FROM player_resources p
            JOIN resource_definitions d ON d.resource_uid=p.resource_uid WHERE p.campaign_id=? AND p.character_uid=? ORDER BY d.resource_key""",arrayOf(campaignUid,characterUid)){c->
            while(c.moveToNext())add(CharacterPanelExactValueV2(c.getString(0),c.getDouble(1).roundToLong(),c.getString(2)))
        }
    }

    override fun skills(campaignUid:String,characterUid:String)=buildList{
        queryIfTable("player_skills_v2","""SELECT p.skill_uid,p.base_mastery,d.display_name FROM player_skills_v2 p
            JOIN skill_definitions_v2 d ON d.skill_uid=p.skill_uid WHERE p.campaign_id=? AND p.character_uid=? ORDER BY d.display_name""",arrayOf(campaignUid,characterUid)){c->
            while(c.moveToNext())add(CharacterPanelMasteryV2(c.getString(0),c.getDouble(1).roundToLong(),c.getString(2)))
        }
    }

    override fun techniques(campaignUid:String,characterUid:String)=buildList{
        queryIfTable("player_techniques_v2","""SELECT p.technique_uid,p.base_mastery,d.display_name FROM player_techniques_v2 p
            JOIN technique_definitions_v2 d ON d.technique_uid=p.technique_uid WHERE p.campaign_id=? AND p.character_uid=? ORDER BY d.display_name""",arrayOf(campaignUid,characterUid)){c->
            while(c.moveToNext())add(CharacterPanelMasteryV2(c.getString(0),c.getDouble(1).roundToLong(),c.getString(2)))
        }
    }

    override fun talent(campaignUid:String,characterUid:String)=buildList{
        queryIfTable("talent_profile_entries","SELECT domain_uid,base_value FROM talent_profile_entries WHERE campaign_id=? AND character_uid=? ORDER BY domain_uid",arrayOf(campaignUid,characterUid)){c->
            while(c.moveToNext())add(CharacterPanelProfileValueV2(c.getString(0),null,c.getDouble(1).toString(),null))
        }
    }

    override fun potential(campaignUid:String,characterUid:String)=buildList{
        queryIfTable("potential_profile_entries","SELECT domain_uid,dimension_uid,base_value FROM potential_profile_entries WHERE campaign_id=? AND character_uid=? ORDER BY domain_uid,dimension_uid",arrayOf(campaignUid,characterUid)){c->
            while(c.moveToNext())add(CharacterPanelProfileValueV2(c.getString(0),c.getString(1),c.getDouble(2).toString(),null))
        }
    }

    override fun innateAndEvolution(campaignUid:String,characterUid:String)=buildList{
        queryIfTable("player_innate_features","""SELECT p.feature_uid,d.display_name FROM player_innate_features p
            JOIN innate_feature_definitions d ON d.feature_uid=p.feature_uid WHERE p.campaign_id=? AND p.character_uid=? ORDER BY d.display_name""",arrayOf(campaignUid,characterUid)){c->
            while(c.moveToNext())add(CharacterPanelInnateV2(c.getString(0),"ACQUIRED",c.getString(1)))
        }
        queryIfTable("player_evolution_states","SELECT path_uid,current_stage_uid FROM player_evolution_states WHERE campaign_id=? AND character_uid=? ORDER BY path_uid",arrayOf(campaignUid,characterUid)){c->
            while(c.moveToNext())add(CharacterPanelInnateV2(c.getString(0),c.str(1)?:"NOT_STARTED",null))
        }
        queryIfTable("player_active_forms","SELECT form_uid FROM player_active_forms WHERE campaign_id=? AND character_uid=? ORDER BY form_uid",arrayOf(campaignUid,characterUid)){c->
            while(c.moveToNext())add(CharacterPanelInnateV2(c.getString(0),"ACTIVE_FORM",null))
        }
    }

    override fun inventory(campaignUid:String,characterUid:String)=buildList{
        queryIfTable("player_inventory_stacks","SELECT item_definition_uid,quantity FROM player_inventory_stacks WHERE campaign_id=? AND character_uid=? ORDER BY item_definition_uid",arrayOf(campaignUid,characterUid)){c->
            while(c.moveToNext())add(CharacterPanelInventoryV2("STACK:${c.getString(0)}",c.getString(0),c.getLong(1)))
        }
        queryIfTable("player_inventory_unique","""SELECT u.item_instance_uid,i.item_definition_uid FROM player_inventory_unique u
            JOIN item_instances i ON i.campaign_id=u.campaign_id AND i.item_instance_uid=u.item_instance_uid
            WHERE u.campaign_id=? AND u.character_uid=? ORDER BY u.item_instance_uid""",arrayOf(campaignUid,characterUid)){c->
            while(c.moveToNext())add(CharacterPanelInventoryV2(c.getString(0),c.getString(1),1))
        }
    }

    override fun equipment(campaignUid:String,characterUid:String)=buildList{
        queryIfTable("player_equipment","""SELECT s.slot_uid,e.item_instance_uid FROM player_equipment e
            JOIN player_equipment_slots s ON s.campaign_id=e.campaign_id AND s.character_uid=e.character_uid AND s.equipment_entry_uid=e.equipment_entry_uid
            WHERE e.campaign_id=? AND e.character_uid=? ORDER BY s.slot_uid""",arrayOf(campaignUid,characterUid)){c->
            while(c.moveToNext())add(CharacterPanelEquipmentV2(c.getString(0),c.str(1)))
        }
    }

    override fun ownershipAndAssets(campaignUid:String,characterUid:String)=buildList{
        queryIfTable("ownership_records","""SELECT asset_kind_uid,asset_uid,owner_uid FROM ownership_records
            WHERE campaign_id=? AND owner_uid=? AND record_status='ACTIVE' ORDER BY asset_kind_uid,asset_uid""",arrayOf(campaignUid,characterUid)){c->
            while(c.moveToNext())add(CharacterPanelOwnershipV2(c.getString(0),c.getString(1),c.getString(2)))
        }
    }

    override fun economy(campaignUid:String,characterUid:String)=buildList{
        queryIfTable("financial_accounts","""SELECT a.currency_uid,b.balance_minor,a.account_uid FROM financial_accounts a
            JOIN financial_account_balances b ON b.campaign_id=a.campaign_id AND b.account_uid=a.account_uid
            WHERE a.campaign_id=? AND a.holder_uid=? AND a.closed_order IS NULL ORDER BY a.currency_uid,a.account_uid""",arrayOf(campaignUid,characterUid)){c->
            while(c.moveToNext())add(CharacterPanelEconomyV2(c.getString(0),c.getLong(1),c.getString(2)))
        }
    }

    override fun progression(campaignUid:String,characterUid:String)=buildList{
        queryIfTable("player_skills_v2","SELECT skill_uid,progress_value,progress_semantics_uid FROM player_skills_v2 WHERE campaign_id=? AND character_uid=? AND progress_value IS NOT NULL ORDER BY skill_uid",arrayOf(campaignUid,characterUid)){c->
            while(c.moveToNext())add(CharacterPanelProgressionV2("SKILL",c.getString(0),c.getDouble(1).roundToLong(),c.str(2)))
        }
        queryIfTable("player_techniques_v2","SELECT technique_uid,progress_value,progress_semantics_uid FROM player_techniques_v2 WHERE campaign_id=? AND character_uid=? AND progress_value IS NOT NULL ORDER BY technique_uid",arrayOf(campaignUid,characterUid)){c->
            while(c.moveToNext())add(CharacterPanelProgressionV2("TECHNIQUE",c.getString(0),c.getDouble(1).roundToLong(),c.str(2)))
        }
    }

    override fun projects(campaignUid:String,characterUid:String)=buildList{
        queryIfTable("development_projects","""SELECT p.project_uid,
            COALESCE((SELECT status FROM project_status_history h WHERE h.campaign_id=p.campaign_id AND h.project_uid=p.project_uid ORDER BY effective_order DESC LIMIT 1),'IDEA'),
            COALESCE((SELECT SUM(progress_delta_units) FROM project_work_records w WHERE w.campaign_id=p.campaign_id AND w.project_uid=p.project_uid),0)
            FROM development_projects p WHERE p.campaign_id=? AND (p.initiator_uid=? OR p.beneficiary_uid=?) ORDER BY p.project_uid""",arrayOf(campaignUid,characterUid,characterUid)){c->
            while(c.moveToNext())add(CharacterPanelProjectV2(c.getString(0),c.getString(1),c.getLong(2)))
        }
    }

    override fun relationships(campaignUid:String,characterUid:String)=buildList{
        queryIfTable("relationships_v2","SELECT other_entity_uid,relationship_type,relationship_score FROM relationships_v2 WHERE entity_uid=? ORDER BY other_entity_uid",arrayOf(characterUid)){c->
            while(c.moveToNext())add(CharacterPanelRelationshipV2(c.getString(0),c.getString(1),c.getDouble(2).roundToLong()))
        }
    }

    override fun goals(campaignUid:String,characterUid:String)=buildList{
        queryIfTable("character_goals","SELECT rowid,title,priority FROM character_goals WHERE entity_uid=? AND status='active' ORDER BY priority DESC,rowid",arrayOf(characterUid)){c->
            while(c.moveToNext())add(CharacterPanelGoalV2("GOAL:${c.getLong(0)}",c.getString(1),c.getLong(2)))
        }
    }

    private inline fun queryIfTable(table:String,sql:String,args:Array<String>,block:(Cursor)->Unit){
        if(!tableExists(table))return
        db.rawQuery(sql,args).use(block)
    }

    private fun tableExists(table:String)=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",arrayOf(table)).use{it.moveToFirst()}
    private fun Cursor.str(index:Int)=if(isNull(index))null else getString(index)
}
