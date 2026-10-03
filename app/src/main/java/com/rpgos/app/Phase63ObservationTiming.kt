package com.rpgos.app

/** The neutral Core's ordinary visual inspection rule. This is a domain duration, not
 * inference latency or a model's precision claim. It grants no perception/knowledge: those
 * still require their own Phase38 admission and Phase37 acquisition evidence. */
internal object Phase63ObservationTiming {
    const val RULE="P63:VISUAL_INSPECTION_MS_V1"
    const val DURATION_MS=30_000L
    fun metadata(node:IntentNode,effectKind:String):Map<String,String> {
        if(effectKind!="INTERACTION" || node.semanticAction.semanticFamilyUid!="LOOK" ||
            node.modality!=IntentModality.ATTEMPT_NOW || node.polarity!=IntentPolarity.AFFIRMATIVE ||
            Phase60PlayerDeclaredDuration.read(node.semanticAction.rawPhrase)!=null)return emptyMap()
        return mapOf("p60_core_timing_rule" to RULE,"p60_core_timing_version" to "1",
            "p60_core_duration_ms" to DURATION_MS.toString(),"p60_core_effect_at_ms" to DURATION_MS.toString())
    }
}
