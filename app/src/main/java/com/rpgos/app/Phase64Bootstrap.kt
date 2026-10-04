package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** A catalog is not an instruction to run it. New campaigns start with no wars, diseases,
 * shortages or invented stock. Domain prerequisites still come from their real owners. */
internal object Phase64NewCampaignBootstrap {
    fun initialize(db:SQLiteDatabase,campaign:String,binding:WorldPackRuleBinding,world:SQLiteDatabase?=null) {
        require(db.inTransaction()){ "P64:BOOTSTRAP_TRANSACTION_REQUIRED" }
        val imported=mutableListOf<BackgroundProcessDefinition>()
        if(world!=null && world.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='background_process_definitions'",null).use { it.moveToFirst() })
            world.rawQuery("SELECT definition_uid,definition_version,canonical FROM background_process_definitions ORDER BY definition_uid,definition_version LIMIT 1025",null).use { c->
                while(c.moveToNext()) {
                    require(imported.size<1024){"P64:PACK_DEFINITION_BUDGET"}
                    val wire=c.getString(2);require(wire.length<=65_536){"P64:PACK_DEFINITION_SIZE"}
                    val definition=Phase64BackgroundCodec.readDefinition(Json.parseToJsonElement(wire).jsonObject)
                    require(definition.uid==c.getString(0) && definition.version==c.getInt(1)){"P64:PACK_DEFINITION_BINDING"}
                    imported+=definition
                }
            }
        val core=coreDefinitions()
        require(imported.map { it.uid to it.version }.distinct().size==imported.size){"P64:DUPLICATE_RULE_VERSION"}
        require(imported.none { row->core.any { it.uid==row.uid } }) {"P64:CORE_RULE_REPLACEMENT"}
        Phase64BackgroundStore(db,campaign).initializeNew(binding,core+imported)
    }
    fun coreDefinitions():List<BackgroundProcessDefinition> = buildList {
        val economy=Phase64EconomyRuleCatalog.coreDefinitions()
        // Neutral Core capabilities. World-specific recipes, costs, epidemic contacts and
        // institutional policies must be supplied by a versioned production rule import.
        Phase64EconomyOperations.economy.sorted().forEach { operation->
            val parameters=if(operation==Phase64EconomyOperations.CONSUME)mapOf(
                Phase64ProcessActivation.ACTION_KEY to "CONSUME_OWN_ITEM",Phase64ProcessActivation.PUBLIC_KEY to "true",
                "inputOwnerKind" to "@ACTOR_KIND","inputOwnerUid" to "@ACTOR_UID","inputItemUids" to "@TARGET_UID",
                "sourceUid" to "P64:CORE:CONSUMPTION_V1") else emptyMap()
            if(economy.none { it.uid=="P64:CORE:$operation" })add(BackgroundProcessDefinition("P64:CORE:$operation",1,"ECONOMY",operation,60_000,parameters=parameters))
        }
        Phase64EconomyOperations.projects.sorted().forEach { operation->
            val parameters=if(operation==Phase64EconomyOperations.RESEARCH)mapOf(
                Phase64ProcessActivation.ACTION_KEY to "WORK_ON_RESEARCH",Phase64ProcessActivation.PUBLIC_KEY to "true",
                "projectUid" to "@TARGET_UID","labourPoolUid" to "STAMINA","labourUnits" to "1","progressUnits" to "1",
                "sourceUid" to "P64:CORE:RESEARCH_LABOUR_V1") else emptyMap()
            if(economy.none { it.uid=="P64:CORE:$operation" })add(BackgroundProcessDefinition("P64:CORE:$operation",1,"PROJECT",operation,60_000,parameters=parameters))
        }
        addAll(economy)
        listOf("AGENDA","ASSIGN","REVOKE","DECISION","ALLOCATE").forEach { add(BackgroundProcessDefinition("P64:CORE:ORG:$it",1,"ORGANIZATION",it,1000)) }
        listOf("MESSAGE","REPORT","DIPLOMACY","ESPIONAGE").forEach { operation->
            val parameters=if(operation=="MESSAGE")mapOf(
                Phase64ProcessActivation.ACTION_KEY to Phase64NeutralCommunicationOwner.ACTION,Phase64ProcessActivation.PUBLIC_KEY to "true",
                "recipient_kind" to "@TARGET_KIND","recipient_uid" to "@TARGET_UID","message_uid" to "@PROCESS_UID","message_text" to "@MESSAGE_LITERAL",
                "channel_uid" to Phase64NeutralCommunicationOwner.CHANNEL,"disclosure_policy_uid" to Phase64NeutralCommunicationOwner.DISCLOSURE,"delay_ms" to "1000") else emptyMap()
            add(BackgroundProcessDefinition("P64:CORE:INFO:$operation",1,"INFORMATION",operation,1000,parameters=parameters))
        }
        listOf("MIGRATE","AGE","BIRTH","DEATH").forEach { add(BackgroundProcessDefinition("P64:CORE:POPULATION:$it",1,"POPULATION",it,60_000)) }
        listOf("MOBILIZE","SUPPLY","MOVE","COMBAT").forEach { add(BackgroundProcessDefinition("P64:CORE:CONFLICT:$it",1,"CONFLICT",it,60_000)) }
        // V1 remains readable for existing registered rules. V2 is only a receipt of
        // an actual Phase62/50 combat completion, not a second scheduled attack.
        add(BackgroundProcessDefinition(Phase64CombatReceiptFactory.RULE_UID,Phase64CombatReceiptFactory.RULE_VERSION,
            "CONFLICT","COMBAT",60_000,parameters=mapOf(Phase64CombatReceiptFactory.OWNER_PARAMETER to NpcActionProcess.OWNER)))
        add(BackgroundProcessDefinition("P64:CORE:EPIDEMIC:EXPOSURE",1,"EPIDEMIC","EXPOSURE",60_000))
        addAll(Phase64PopulationRuleCatalog.definitions())
    }
}
