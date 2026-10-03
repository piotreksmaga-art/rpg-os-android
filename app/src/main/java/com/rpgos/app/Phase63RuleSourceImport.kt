package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.*

internal data class Phase63RuleSourceData(val localRules:List<LatentWorldGenerationRule>,val macroRules:List<WorldMacroRegionRule>)

/** Optional versioned World Pack definitions. This reader neither imports saved campaigns
 * nor patches existing roots. First initialization binds the captured data in the ordinary turn. */
internal object Phase63RuleSourceImport {
    const val TABLE="phase63_world_generation_definitions"
    fun read(world:SQLiteDatabase,binding:WorldPackRuleBinding):Phase63RuleSourceData {
        require(binding.sourceKind==CampaignRuleSourceKind.WORLD_PACK)
        val exists=world.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",arrayOf(TABLE)).use { it.moveToFirst() }
        if(!exists)return Phase63RuleSourceData(CoreLatentWorldRules.initial(),emptyList())
        val fields=world.rawQuery("PRAGMA table_info($TABLE)",null).use { c->buildSet { while(c.moveToNext())add(c.getString(1)) } }
        require(fields==setOf("definition_uid","contract_version","definition_kind","canonical_value")) { "P63:WORLD_PACK_RULE_SCHEMA" }
        val local=mutableListOf<LatentWorldGenerationRule>();val macro=mutableListOf<WorldMacroRegionRule>()
        world.rawQuery("SELECT definition_uid,contract_version,definition_kind,canonical_value FROM $TABLE ORDER BY definition_uid LIMIT 257",null).use { c->
            var count=0
            while(c.moveToNext()) {
                require(++count<=256 && c.getInt(1)==2) { "P63:WORLD_PACK_RULE_CONTRACT" }
                val value=Json.parseToJsonElement(c.getString(3)).jsonObject
                when(c.getString(2)) {
                    "LOCAL_ELEMENT"->Phase63LatentRuleCodec.decode(value).also { require(it.uid==c.getString(0));local+=it }
                    "MACRO_REGION"->Phase63MacroRegionCodec.decode(value).also { require(it.uid==c.getString(0));macro+=it }
                    else->error("P63:WORLD_PACK_RULE_KIND")
                }
            }
        }
        require(local.size<=128 && local.map { it.uid }.distinct().size==local.size && macro.size<=128 &&
            macro.map { it.uid }.distinct().size==macro.size && macro.map { it.ordinal }.distinct().size==macro.size) { "P63:WORLD_PACK_RULE_BUDGET_OR_IDENTITY" }
        // A present table is authoritative configuration, including a deliberately empty local
        // rule list. Generic fallback must not silently defeat the pack's exclusions.
        return Phase63RuleSourceData(local.toList(),macro.toList())
    }
}
