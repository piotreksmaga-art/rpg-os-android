package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

/** Explicit actor bindings supplement, rather than replace, the accepted mechanical profile.
 * Absence of this extension leaves the NPC unchanged. A pack must supply both the rule and an
 * existing Phase21 definition; no unknown skill/technique or reward is synthesized here. */
internal object NpcActivityActorImport {
    fun importPack(save:SQLiteDatabase,world:SQLiteDatabase,campaign:String) {
        val exists=world.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='npc_activity_actor_bindings'",null).use{it.moveToFirst()}
        if(!exists)return
        require(save.inTransaction() && GameplayMutationDatabaseGuards.isAdminActive(save,campaign))
        world.rawQuery("SELECT actor_kind_uid,actor_uid,rule_uid,rule_version,initial_mastery FROM npc_activity_actor_bindings ORDER BY actor_kind_uid,actor_uid,rule_uid,rule_version LIMIT 1025",null).use { c->
            var count=0
            while(c.moveToNext()) {
                require(++count<=1024){"P62:PACK_ACTOR_ACTIVITY_BUDGET"}
                val actor=DomainRef(c.getString(0),c.getString(1))
                val mechanical=MechanicalActorStateStore(save,campaign)
                require(mechanical.actor(actor)?.materialization==MechanicalStateMaterialization.FULL && actor.kindUid!="PLAYER") { "P62:PACK_ACTIVITY_ACTOR_NOT_MATERIALIZED" }
                val contract=save.rawQuery("SELECT contract_json FROM ${Phase62ActivitySchema.TABLE} WHERE campaign_uid=? AND rule_uid=? AND rule_version=? AND active=1",
                    arrayOf(campaign,c.getString(2),c.getInt(3).toString())).use{if(it.moveToFirst())NpcActivityContractCodec.decode(it.getString(0)) else null}
                    ?:continue // An older pack binding cannot resurrect a superseded contract.
                val mastery=if(c.isNull(4))null else c.getDouble(4).also{require(it.isFinite() && it>=0)}
                contract.learning?.let { rule->
                    require(mastery!=null){"P62:PACK_LEARNING_INITIAL_STATE_REQUIRED"}
                    when(rule.targetKindUid) {
                        ProgressionTargetKinds.SKILL->{
                            val owner=SkillStore(save,campaign)
                            require(owner.definitions().any{it.skillUid==rule.targetUid && it.status==SkillDefinitionStatus.ACTIVE}) { "P62:PACK_SKILL_DEFINITION_MISSING" }
                            if(owner.playerSkills(actor.uid).none{it.skillUid==rule.targetUid})owner.savePlayerSkill(PlayerSkill(campaign,actor.uid,rule.targetUid,mastery,0.0,rule.progressSemanticsUid,provenance="P62:WORLD_PACK_ACTOR_BINDING:${contract.ruleUid}:${contract.version}"))
                        }
                        ProgressionTargetKinds.TECHNIQUE->{
                            val owner=TechniqueStore(save,campaign)
                            require(owner.definitions().any{it.techniqueUid==rule.targetUid && it.status==TechniqueDefinitionStatus.ACTIVE}) { "P62:PACK_TECHNIQUE_DEFINITION_MISSING" }
                            if(owner.playerTechniques(actor.uid).none{it.techniqueUid==rule.targetUid})owner.savePlayerTechnique(PlayerTechnique(campaign,actor.uid,rule.targetUid,mastery,0.0,rule.progressSemanticsUid,provenance="P62:WORLD_PACK_ACTOR_BINDING:${contract.ruleUid}:${contract.version}"))
                        }
                    }
                }
                mechanical.importActivityCapability(actor,contract.capabilityUid)
            }
        }
    }
}
