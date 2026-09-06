package com.rpgos.app

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Request identity stays on the host. Small models generate advice, never identity or facts. */
internal object LocalDirectorCodec {
    const val MAX_PAYLOAD_BYTES = 1000

    fun encode(request: AiDirectorRequest): String {
        val root = JSONObject().put("v", "RPGOS_DIRECTOR_LOCAL_1")
            .put("reply", "JSON: title, summary, kind. Po polsku: jedna przyszła propozycja, nie fakt ani zmiana świata. Bez dodatkowych pól.")
            .put("kinds", JSONArray(DirectorCandidateKind.entries.map { it.name }))
        val segments = JSONArray()
        root.put("context", segments)
        // Budget the serialized bytes, including JSON escaping. No characters/4 estimate and
        // no global UID manifest (which previously consumed ~6000 tokens on a 2048 model).
        for (segment in request.context.strategicSummarySegments) {
            segments.put(segment)
            if (root.toString().toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES) {
                segments.remove(segments.length() - 1)
                // Preserve complete segments; do not truncate a negation or evidence clause.
            }
        }
        return root.toString()
    }

    fun decode(payload: String, request: AiDirectorRequest): DirectorBundle {
        val root = JSONObject(payload)
        require(root.keys().asSequence().toSet() == setOf("title", "summary", "kind"))
        val title = root.get("title") as String
        val summary = root.get("summary") as String
        val kind = DirectorCandidateKind.valueOf(root.getString("kind"))
        val identity = MessageDigest.getInstance("SHA-256")
            .digest("${request.requestUid}|$payload".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return DirectorBundle(
            bundleUid = "LOCAL-DIRECTOR:$identity", jobUid = request.jobUid,
            campaignUid = request.context.campaignUid, triggerUid = request.trigger.triggerUid,
            contextVersion = request.context.contextVersion,
            asOfCommittedOrder = request.context.asOfCommittedOrder,
            providerUid = "LOCAL", modelUid = "LOCAL",
            candidates = listOf(DirectorCandidate(
                "LOCAL-ADVICE:$identity", kind, title, summary,
                emptyList(), "NEXT_TURNS", emptySet(), "PHASE65_DIRECTOR"
            )), createdAgainstFingerprint = request.context.contextVersion
        )
    }
}
