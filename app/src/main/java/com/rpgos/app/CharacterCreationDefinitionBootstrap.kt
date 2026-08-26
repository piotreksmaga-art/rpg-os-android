package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

/**
 * Universal definition loader used while opening a campaign. A modern World Pack may expose the
 * typed tables directly. Older packs may be imported by a named compatibility projection, and a
 * pack with no character schema receives a small genre-neutral floor. It creates definitions,
 * never player state, and never infers choices for the player.
 */
internal class CharacterCreationDefinitionBootstrap(
    private val saveDb:SQLiteDatabase,
    private val worldDb:SQLiteDatabase,
    private val worldPack:WorldPackRuleBinding
){
    private val provenance="RPGOS-CHARACTER-CREATION-BRIDGE:${worldPack.worldPackUid}:${worldPack.worldPackVersion}"

    fun ensure(){
        val ownsTransaction=!saveDb.inTransaction()
        if(ownsTransaction)saveDb.beginTransaction()
        try{
            ensureCoreStats()
            ensureCoreResources()
            ensureProgressionDomains()
            ensureSkills()
            ensureTechniques()
            ensureOrigins()
            ensureInnateFeatures()
            if(ownsTransaction)saveDb.setTransactionSuccessful()
        }finally{if(ownsTransaction)saveDb.endTransaction()}
    }

    private fun ensureCoreStats(){
        if(hasPackRows("stat_definitions")){return}
        importTypedStats()
        if(hasPackRows("stat_definitions"))return
        listOf(
            "POWER" to "Power","SKILL" to "Skill","DEFENCE" to "Defence","AGILITY" to "Agility",
            "ENDURANCE" to "Endurance","INTELLECT" to "Intellect","PERCEPTION" to "Perception","WILLPOWER" to "Willpower"
        ).forEach{(key,name)->saveDb.execSQL(
            "INSERT INTO stat_definitions(stat_uid,stat_key,category,unit,min_value,max_value,growth_rule_uid,derivation_rule_uid,world_pack_uid) VALUES(?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(fallbackUid("STAT",key),key,"CORE_ATTRIBUTE",null,0.0,100.0,null,null,worldPack.worldPackUid)
        )}
    }

    private fun ensureCoreResources(){
        if(hasPackRows("resource_definitions")){return}
        importTypedResources()
        if(hasPackRows("resource_definitions"))return
        listOf(
            Triple("HEALTH","Health","VITAL"),Triple("STAMINA","Stamina","VITAL"),
            Triple("FOCUS","Focus","MENTAL"),Triple("ENERGY","Energy","GENERIC_ENERGY")
        ).forEach{(key,name,category)->saveDb.execSQL(
            "INSERT INTO resource_definitions(resource_uid,resource_key,category,unit,min_value,max_value,max_rule_uid,regeneration_rule_uid,world_pack_uid) VALUES(?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(fallbackUid("RESOURCE",key),key,category,null,0.0,100.0,null,null,worldPack.worldPackUid)
        )}
    }

    private fun ensureProgressionDomains(){
        if(hasPackRows("progression_domain_definitions")){return}
        importTypedProgressionDomains()
        if(hasPackRows("progression_domain_definitions"))return
        listOf("PHYSICAL" to "Physical","MENTAL" to "Mental","ENERGY" to "Energy","SOCIAL" to "Social").forEach{(key,name)->
            saveDb.execSQL(
                "INSERT INTO progression_domain_definitions(domain_uid,world_pack_uid,domain_key,display_name,category,parent_domain_uid,applies_to_talent,applies_to_potential,definition_version,provenance) VALUES(?,?,?,?,?,NULL,1,1,1,?)",
                arrayOf<Any?>(fallbackUid("PROGRESSION",key),worldPack.worldPackUid,key,name,"CHARACTER_CREATION",provenance)
            )
        }
    }

    private fun ensureSkills(){
        if(hasPackRows("skill_definitions_v2")){return}
        importTypedSkills()
        if(hasPackRows("skill_definitions_v2"))return
        if(tableExists(saveDb,"skill_definitions")){
            saveDb.rawQuery(
                "SELECT skill_uid,skill_key,display_name,category_key,max_mastery FROM skill_definitions ORDER BY skill_uid",null
            ).use{c->while(c.moveToNext()){
                val uid=c.nonBlank(0)?:continue
                val key=c.nonBlank(1)?:uid
                val display=c.nonBlank(2)?:key
                val category=c.nonBlank(3)?:"LEGACY_SKILL"
                val maximum=if(c.isNull(4))100.0 else c.getDouble(4).coerceAtLeast(0.0)
                saveDb.execSQL(
                    "INSERT INTO skill_definitions_v2(skill_uid,world_pack_uid,skill_key,display_name,category,min_mastery,max_mastery,definition_status,definition_version,provenance) VALUES(?,?,?,?,?,0.0,?,'ACTIVE',1,?)",
                    arrayOf<Any?>(uid,worldPack.worldPackUid,key,display,category,maximum,"$provenance:LEGACY_SKILL_DEFINITION")
                )
            }}
        }
        if(!hasPackRows("skill_definitions_v2"))saveDb.execSQL(
            "INSERT INTO skill_definitions_v2(skill_uid,world_pack_uid,skill_key,display_name,category,min_mastery,max_mastery,definition_status,definition_version,provenance) VALUES(?,?,?,?,?,0.0,100.0,'ACTIVE',1,?)",
            arrayOf<Any?>(fallbackUid("SKILL","GENERAL"),worldPack.worldPackUid,"GENERAL","General aptitude","ARCHITECTURE_FALLBACK","$provenance:EXPLICIT_FALLBACK")
        )
    }

    private fun ensureTechniques(){
        if(hasPackRows("technique_definitions_v2")){return}
        importTypedTechniques()
        if(hasPackRows("technique_definitions_v2"))return
        if(tableExists(worldDb,"canon_technique_index")){
            worldDb.rawQuery("SELECT technique_uid,name,category,rank FROM canon_technique_index ORDER BY technique_uid",null).use{c->while(c.moveToNext()){
                val uid=c.nonBlank(0)?:continue
                val display=c.nonBlank(1)?:uid
                val category=listOfNotNull(c.nonBlank(2),c.nonBlank(3)).joinToString(":").ifBlank{"LEGACY_TECHNIQUE"}
                saveDb.execSQL(
                    "INSERT INTO technique_definitions_v2(technique_uid,world_pack_uid,technique_key,display_name,category,min_mastery,max_mastery,definition_status,definition_version,provenance) VALUES(?,?,?,?,?,0.0,100.0,'ACTIVE',1,?)",
                    arrayOf<Any?>(uid,worldPack.worldPackUid,uid,display,category,"$provenance:CANON_TECHNIQUE_INDEX")
                )
            }}
        }
        if(!hasPackRows("technique_definitions_v2"))saveDb.execSQL(
            "INSERT INTO technique_definitions_v2(technique_uid,world_pack_uid,technique_key,display_name,category,min_mastery,max_mastery,definition_status,definition_version,provenance) VALUES(?,?,?,?,?,0.0,100.0,'ACTIVE',1,?)",
            arrayOf<Any?>(fallbackUid("TECHNIQUE","BASIC_ACTION"),worldPack.worldPackUid,"BASIC_ACTION","Basic action","ARCHITECTURE_FALLBACK","$provenance:EXPLICIT_FALLBACK")
        )
    }

    private fun ensureOrigins(){
        if(hasPackRows("origin_definitions_v2"))return
        importTypedOrigins()
        if(hasPackRows("origin_definitions_v2")||!tableExists(worldDb,"canon_villages"))return
        worldDb.rawQuery("SELECT village_uid,name,village_class FROM canon_villages ORDER BY village_uid",null).use{c->while(c.moveToNext()){
            val uid=c.nonBlank(0)?:continue
            val display=c.nonBlank(1)?:uid
            saveDb.execSQL(
                "INSERT INTO origin_definitions_v2(origin_uid,world_pack_uid,origin_key,display_name,origin_kind,definition_status,definition_version,provenance) VALUES(?,?,?,?,?,'ACTIVE',1,?)",
                arrayOf<Any?>(uid,worldPack.worldPackUid,uid,display,c.nonBlank(2)?:"VILLAGE","$provenance:CANON_VILLAGE")
            )
        }}
    }

    private fun ensureInnateFeatures(){
        if(hasPackRows("innate_feature_definitions"))return
        importTypedInnateFeatures()
        if(hasPackRows("innate_feature_definitions")||!tableExists(worldDb,"canon_kekkei_genkai"))return
        worldDb.rawQuery("SELECT kg_uid,name,kg_type FROM canon_kekkei_genkai ORDER BY kg_uid",null).use{c->while(c.moveToNext()){
            val uid=c.nonBlank(0)?:continue
            val display=c.nonBlank(1)?:uid
            val kind=c.nonBlank(2)?:"KEKKEI_GENKAI"
            saveDb.execSQL(
                "INSERT INTO innate_feature_definitions(feature_uid,world_pack_uid,feature_key,display_name,feature_kind,category,definition_status,definition_version,provenance) VALUES(?,?,?,?,?,?,'ACTIVE',1,?)",
                arrayOf<Any?>(uid,worldPack.worldPackUid,uid,display,kind,"INNATE","$provenance:CANON_KEKKEI_GENKAI")
            )
        }}
    }

    private fun importTypedStats(){
        if(!hasColumns(worldDb,"stat_definitions",setOf("stat_uid","stat_key","category","unit","min_value","max_value","growth_rule_uid","derivation_rule_uid")))return
        worldDb.rawQuery("SELECT stat_uid,stat_key,category,unit,min_value,max_value,growth_rule_uid,derivation_rule_uid FROM stat_definitions ORDER BY stat_uid",null).use{c->while(c.moveToNext()){
            saveDb.execSQL("INSERT INTO stat_definitions(stat_uid,stat_key,category,unit,min_value,max_value,growth_rule_uid,derivation_rule_uid,world_pack_uid) VALUES(?,?,?,?,?,?,?,?,?)",arrayOf<Any?>(
                requireNotNull(c.nonBlank(0)),requireNotNull(c.nonBlank(1)),requireNotNull(c.nonBlank(2)),c.nullableString(3),c.nullableDouble(4),c.nullableDouble(5),c.nullableString(6),c.nullableString(7),worldPack.worldPackUid
            ))
        }}
    }

    private fun importTypedResources(){
        if(!hasColumns(worldDb,"resource_definitions",setOf("resource_uid","resource_key","category","unit","min_value","max_value","max_rule_uid","regeneration_rule_uid")))return
        worldDb.rawQuery("SELECT resource_uid,resource_key,category,unit,min_value,max_value,max_rule_uid,regeneration_rule_uid FROM resource_definitions ORDER BY resource_uid",null).use{c->while(c.moveToNext()){
            saveDb.execSQL("INSERT INTO resource_definitions(resource_uid,resource_key,category,unit,min_value,max_value,max_rule_uid,regeneration_rule_uid,world_pack_uid) VALUES(?,?,?,?,?,?,?,?,?)",arrayOf<Any?>(
                requireNotNull(c.nonBlank(0)),requireNotNull(c.nonBlank(1)),requireNotNull(c.nonBlank(2)),c.nullableString(3),c.nullableDouble(4),c.nullableDouble(5),c.nullableString(6),c.nullableString(7),worldPack.worldPackUid
            ))
        }}
    }

    private data class ImportedDomain(val uid:String,val key:String,val name:String,val category:String,val parent:String?,val talent:Int,val potential:Int,val version:Long,val source:String)
    private fun importTypedProgressionDomains(){
        if(!hasColumns(worldDb,"progression_domain_definitions",setOf("domain_uid","domain_key","display_name","category","parent_domain_uid","applies_to_talent","applies_to_potential","definition_version","provenance")))return
        val pending=mutableListOf<ImportedDomain>()
        worldDb.rawQuery("SELECT domain_uid,domain_key,display_name,category,parent_domain_uid,applies_to_talent,applies_to_potential,definition_version,provenance FROM progression_domain_definitions ORDER BY domain_uid",null).use{c->while(c.moveToNext())pending+=ImportedDomain(
            requireNotNull(c.nonBlank(0)),requireNotNull(c.nonBlank(1)),requireNotNull(c.nonBlank(2)),requireNotNull(c.nonBlank(3)),c.nullableString(4),c.getInt(5),c.getInt(6),c.getLong(7),requireNotNull(c.nonBlank(8))
        )}
        while(pending.isNotEmpty()){
            val ready=pending.filter{it.parent==null||definitionExists("progression_domain_definitions","domain_uid",it.parent)}
            require(ready.isNotEmpty()){"RPGOS-CHARACTER-CREATION:WORLD_PACK_PROGRESSION_GRAPH_INVALID"}
            ready.forEach{d->saveDb.execSQL("INSERT INTO progression_domain_definitions(domain_uid,world_pack_uid,domain_key,display_name,category,parent_domain_uid,applies_to_talent,applies_to_potential,definition_version,provenance) VALUES(?,?,?,?,?,?,?,?,?,?)",arrayOf<Any?>(
                d.uid,worldPack.worldPackUid,d.key,d.name,d.category,d.parent,d.talent,d.potential,d.version,"$provenance:TYPED:${d.source}"
            ))}
            pending.removeAll(ready.toSet())
        }
    }

    private fun importTypedSkills(){
        if(!hasColumns(worldDb,"skill_definitions_v2",setOf("skill_uid","skill_key","display_name","category","min_mastery","max_mastery","definition_status","definition_version","provenance")))return
        worldDb.rawQuery("SELECT skill_uid,skill_key,display_name,category,min_mastery,max_mastery,definition_status,definition_version,provenance FROM skill_definitions_v2 ORDER BY skill_uid",null).use{c->while(c.moveToNext())saveDb.execSQL(
            "INSERT INTO skill_definitions_v2(skill_uid,world_pack_uid,skill_key,display_name,category,min_mastery,max_mastery,definition_status,definition_version,provenance) VALUES(?,?,?,?,?,?,?,?,?,?)",arrayOf<Any?>(
                requireNotNull(c.nonBlank(0)),worldPack.worldPackUid,requireNotNull(c.nonBlank(1)),requireNotNull(c.nonBlank(2)),requireNotNull(c.nonBlank(3)),c.nullableDouble(4),c.nullableDouble(5),requireNotNull(c.nonBlank(6)),c.getLong(7),"$provenance:TYPED:${requireNotNull(c.nonBlank(8))}"
            )
        )}
        if(hasColumns(worldDb,"skill_definition_domains",setOf("skill_uid","domain_uid")))worldDb.rawQuery("SELECT skill_uid,domain_uid FROM skill_definition_domains ORDER BY skill_uid,domain_uid",null).use{c->while(c.moveToNext()){
            val skill=requireNotNull(c.nonBlank(0));val domain=requireNotNull(c.nonBlank(1))
            if(definitionExists("skill_definitions_v2","skill_uid",skill)&&definitionExists("progression_domain_definitions","domain_uid",domain))saveDb.execSQL("INSERT INTO skill_definition_domains(skill_uid,domain_uid) VALUES(?,?)",arrayOf(skill,domain))
        }}
    }

    private fun importTypedTechniques(){
        if(!hasColumns(worldDb,"technique_definitions_v2",setOf("technique_uid","technique_key","display_name","category","min_mastery","max_mastery","definition_status","definition_version","provenance")))return
        worldDb.rawQuery("SELECT technique_uid,technique_key,display_name,category,min_mastery,max_mastery,definition_status,definition_version,provenance FROM technique_definitions_v2 ORDER BY technique_uid",null).use{c->while(c.moveToNext())saveDb.execSQL(
            "INSERT INTO technique_definitions_v2(technique_uid,world_pack_uid,technique_key,display_name,category,min_mastery,max_mastery,definition_status,definition_version,provenance) VALUES(?,?,?,?,?,?,?,?,?,?)",arrayOf<Any?>(
                requireNotNull(c.nonBlank(0)),worldPack.worldPackUid,requireNotNull(c.nonBlank(1)),requireNotNull(c.nonBlank(2)),requireNotNull(c.nonBlank(3)),c.nullableDouble(4),c.nullableDouble(5),requireNotNull(c.nonBlank(6)),c.getLong(7),"$provenance:TYPED:${requireNotNull(c.nonBlank(8))}"
            )
        )}
        importTechniqueRelations()
    }

    private fun importTechniqueRelations(){
        if(hasColumns(worldDb,"technique_skill_requirements",setOf("technique_uid","skill_uid","requirement_phase","mastery_basis","minimum_mastery","requirement_version","provenance")))worldDb.rawQuery(
            "SELECT technique_uid,skill_uid,requirement_phase,mastery_basis,minimum_mastery,requirement_version,provenance FROM technique_skill_requirements ORDER BY technique_uid,skill_uid,requirement_phase",null
        ).use{c->while(c.moveToNext())saveDb.execSQL("INSERT INTO technique_skill_requirements(technique_uid,skill_uid,requirement_phase,mastery_basis,minimum_mastery,requirement_version,provenance) VALUES(?,?,?,?,?,?,?)",arrayOf<Any?>(
            requireNotNull(c.nonBlank(0)),requireNotNull(c.nonBlank(1)),requireNotNull(c.nonBlank(2)),requireNotNull(c.nonBlank(3)),c.getDouble(4),c.getLong(5),"$provenance:TYPED:${requireNotNull(c.nonBlank(6))}"
        ))}
        if(hasColumns(worldDb,"technique_resource_costs",setOf("technique_uid","resource_uid","amount","cost_version","provenance")))worldDb.rawQuery(
            "SELECT technique_uid,resource_uid,amount,cost_version,provenance FROM technique_resource_costs ORDER BY technique_uid,resource_uid",null
        ).use{c->while(c.moveToNext())saveDb.execSQL("INSERT INTO technique_resource_costs(technique_uid,resource_uid,amount,cost_version,provenance) VALUES(?,?,?,?,?)",arrayOf<Any?>(
            requireNotNull(c.nonBlank(0)),requireNotNull(c.nonBlank(1)),c.getDouble(2),c.getLong(3),"$provenance:TYPED:${requireNotNull(c.nonBlank(4))}"
        ))}
    }

    private fun importTypedOrigins(){
        if(!hasColumns(worldDb,"origin_definitions_v2",setOf("origin_uid","origin_key","display_name","origin_kind","definition_status","definition_version","provenance")))return
        worldDb.rawQuery("SELECT origin_uid,origin_key,display_name,origin_kind,definition_status,definition_version,provenance FROM origin_definitions_v2 ORDER BY origin_uid",null).use{c->while(c.moveToNext())saveDb.execSQL(
            "INSERT INTO origin_definitions_v2(origin_uid,world_pack_uid,origin_key,display_name,origin_kind,definition_status,definition_version,provenance) VALUES(?,?,?,?,?,?,?,?)",arrayOf<Any?>(
                requireNotNull(c.nonBlank(0)),worldPack.worldPackUid,requireNotNull(c.nonBlank(1)),requireNotNull(c.nonBlank(2)),requireNotNull(c.nonBlank(3)),requireNotNull(c.nonBlank(4)),c.getLong(5),"$provenance:TYPED:${requireNotNull(c.nonBlank(6))}"
            )
        )}
    }

    private fun importTypedInnateFeatures(){
        if(!hasColumns(worldDb,"innate_feature_definitions",setOf("feature_uid","feature_key","display_name","feature_kind","category","definition_status","definition_version","provenance")))return
        worldDb.rawQuery("SELECT feature_uid,feature_key,display_name,feature_kind,category,definition_status,definition_version,provenance FROM innate_feature_definitions ORDER BY feature_uid",null).use{c->while(c.moveToNext())saveDb.execSQL(
            "INSERT INTO innate_feature_definitions(feature_uid,world_pack_uid,feature_key,display_name,feature_kind,category,definition_status,definition_version,provenance) VALUES(?,?,?,?,?,?,?,?,?)",arrayOf<Any?>(
                requireNotNull(c.nonBlank(0)),worldPack.worldPackUid,requireNotNull(c.nonBlank(1)),requireNotNull(c.nonBlank(2)),requireNotNull(c.nonBlank(3)),c.nullableString(4),requireNotNull(c.nonBlank(5)),c.getLong(6),"$provenance:TYPED:${requireNotNull(c.nonBlank(7))}"
            )
        )}
    }

    private fun hasPackRows(table:String)=tableExists(saveDb,table)&&saveDb.rawQuery("SELECT 1 FROM $table WHERE world_pack_uid=? LIMIT 1",arrayOf(worldPack.worldPackUid)).use{it.moveToFirst()}
    private fun definitionExists(table:String,column:String,uid:String)=saveDb.rawQuery("SELECT 1 FROM $table WHERE $column=? LIMIT 1",arrayOf(uid)).use{it.moveToFirst()}
    private fun tableExists(db:SQLiteDatabase,table:String)=db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",arrayOf(table)
    ).use{it.moveToFirst()}
    private fun hasColumns(db:SQLiteDatabase,table:String,required:Set<String>):Boolean{
        if(!tableExists(db,table))return false
        val available=mutableSetOf<String>();db.rawQuery("PRAGMA table_info($table)",null).use{c->while(c.moveToNext())available+=c.getString(1)}
        return available.containsAll(required)
    }
    private fun fallbackUid(kind:String,key:String)="${worldPack.worldPackUid}:RPGOS:FALLBACK:$kind:$key"
    private fun android.database.Cursor.nonBlank(index:Int)=if(isNull(index))null else getString(index)?.trim()?.takeIf{it.isNotEmpty()}
    private fun android.database.Cursor.nullableString(index:Int)=if(isNull(index))null else getString(index)
    private fun android.database.Cursor.nullableDouble(index:Int)=if(isNull(index))null else getDouble(index)
}
