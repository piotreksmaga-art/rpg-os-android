package com.rpgos.app

import org.json.JSONArray
import org.json.JSONObject

object JsonCodec {
    fun contextToJson(context: ContextBundle): JSONObject = JSONObject().apply {
        put("player_status", JSONObject(context.playerStatus))
        put("scene", JSONObject(context.scene))
        put("time", JSONObject(context.time))
        put("active_threads", JSONArray(context.activeThreads.map { JSONObject(it) }))
        put("relevant_npcs", JSONArray(context.relevantNpcs.map { JSONObject(it) }))
        put("npc_knowledge", JSONArray(context.npcKnowledge.map { JSONObject(it) }))
        put("missions", JSONArray(context.missions.map { JSONObject(it) }))
        put("world_pressures", JSONArray(context.worldPressures.map { JSONObject(it) }))
        put("canon_constraints", JSONArray(context.canonConstraints.map { JSONObject(it) }))
        put("recent_chronicle", JSONArray(context.recentChronicle.map { JSONObject(it) }))
        put("retrieved_long_term_memory", JSONArray(context.retrievedLongTermMemory.map { JSONObject(it) }))
        put("player_skills", JSONArray(context.playerSkills.map { JSONObject(it) }))
        put("player_techniques", JSONArray(context.playerTechniques.map { JSONObject(it) }))
        put("player_organizations", JSONArray(context.playerOrganizations.map { JSONObject(it) }))
        put("active_world_events", JSONArray(context.activeWorldEvents.map { JSONObject(it) }))
        put("npc_memories", JSONArray(context.npcMemories.map { JSONObject(it) }))
        put("campaign_truth", JSONArray(context.campaignTruth.map { JSONObject(it) }))
        put("player_state", JSONObject(context.playerState))
        put("context_meta", JSONObject(context.contextMeta))
        put("visibility_envelope", JSONObject().apply {
            put("campaign_uid", context.visibilityEnvelope.campaignUid)
            put("audience_kind_uid", context.visibilityEnvelope.audience.audienceKindUid)
            put("purpose_uid", context.visibilityEnvelope.purpose.purposeUid)
            put("maximum_disclosure", context.visibilityEnvelope.maximumDisclosure.name)
            put("authority_uid", context.visibilityEnvelope.authorityUid)
            put("projection_version_uid", context.visibilityEnvelope.projectionVersionUid)
        })
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
