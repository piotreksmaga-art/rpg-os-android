package com.rpgos.app

import org.json.JSONArray
import org.json.JSONObject

object JsonCodec {
    fun contextToJson(context: ContextBundle): JSONObject = JSONObject().apply {
        put("player_status", JSONObject(context.playerStatus))
        put("scene", JSONObject(context.scene))
        put("time", JSONObject(context.time))
        put("active_threads", JSONArray(context.activeThreads.map(::JSONObject)))
        put("relevant_npcs", JSONArray(context.relevantNpcs.map(::JSONObject)))
        put("npc_knowledge", JSONArray(context.npcKnowledge.map(::JSONObject)))
        put("missions", JSONArray(context.missions.map(::JSONObject)))
        put("world_pressures", JSONArray(context.worldPressures.map(::JSONObject)))
        put("canon_constraints", JSONArray(context.canonConstraints.map(::JSONObject)))
        put("recent_chronicle", JSONArray(context.recentChronicle.map(::JSONObject)))
        put("retrieved_long_term_memory", JSONArray(context.retrievedLongTermMemory.map(::JSONObject)))
        put("player_skills", JSONArray(context.playerSkills.map(::JSONObject)))
        put("player_techniques", JSONArray(context.playerTechniques.map(::JSONObject)))
        put("player_organizations", JSONArray(context.playerOrganizations.map(::JSONObject)))
        put("active_world_events", JSONArray(context.activeWorldEvents.map(::JSONObject)))
        put("npc_memories", JSONArray(context.npcMemories.map(::JSONObject)))
        put("context_meta", JSONObject(context.contextMeta))
    }

    fun parseStatePatch(obj: JSONObject): StatePatch {
        val operations = mutableListOf<PatchOperation>()
        val arr = obj.optJSONArray("operations") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            operations += PatchOperation(
                op = o.getString("op"),
                table = o.getString("table"),
                key = jsonObjectToMap(o.optJSONObject("key") ?: JSONObject()),
                values = jsonObjectToMap(o.optJSONObject("values") ?: JSONObject())
            )
        }
        return StatePatch(
            transactionId = obj.getString("transaction_id"),
            operations = operations,
            chapterManifest = jsonObjectToMap(obj.optJSONObject("chapter_manifest") ?: JSONObject()),
            requiresValidation = obj.optBoolean("requires_validation", true)
        )
    }

    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = when (val v = obj.opt(key)) {
                JSONObject.NULL -> null
                is JSONObject -> jsonObjectToMap(v)
                is JSONArray -> (0 until v.length()).map { idx -> v.opt(idx) }
                else -> v
            }
        }
        return out
    }
}
