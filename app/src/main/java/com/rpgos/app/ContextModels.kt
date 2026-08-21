package com.rpgos.app

data class ContextBundle(
    val playerStatus: Map<String, Any?>,
    val scene: Map<String, Any?>,
    val time: Map<String, Any?>,
    val activeThreads: List<Map<String, Any?>>,
    val relevantNpcs: List<Map<String, Any?>>,
    val npcKnowledge: List<Map<String, Any?>>,
    val missions: List<Map<String, Any?>>,
    val worldPressures: List<Map<String, Any?>>,
    val canonConstraints: List<Map<String, Any?>>,
    val recentChronicle: List<Map<String, Any?>>,
    val retrievedLongTermMemory: List<Map<String, Any?>>,
    val playerSkills: List<Map<String, Any?>> = emptyList(),
    val playerTechniques: List<Map<String, Any?>> = emptyList(),
    val playerInventory: List<Map<String, Any?>> = emptyList(),
    val playerOrganizations: List<Map<String, Any?>> = emptyList(),
    val activeWorldEvents: List<Map<String, Any?>> = emptyList(),
    val npcMemories: List<Map<String, Any?>> = emptyList(),
    val campaignTruth: List<Map<String, Any?>> = emptyList(),
    val canonDivergences: List<CanonDivergenceRecord> = emptyList(),
    val playerState: Map<String, Any?> = emptyMap(),
    val contextMeta: Map<String, Any?> = emptyMap(),
    val visibilityEnvelope: VisibilityProjectionEnvelope
) {
    fun reduceDisclosureTo(level: DisclosureLevel): ContextBundle = ContextBundleDisclosureProjector.reduce(this, level)

    fun requireNotEscalatedFrom(upstream: VisibilityProjectionEnvelope) {
        require(upstream.campaignUid == visibilityEnvelope.campaignUid && upstream.audience == visibilityEnvelope.audience && upstream.purpose == visibilityEnvelope.purpose) {
            "RPGOS-VISIBILITY:PROJECTION_IDENTITY_CHANGED"
        }
        if (!upstream.maximumDisclosure.canReduceTo(visibilityEnvelope.maximumDisclosure)) throw VisibilityAuthorityFailure.Escalation()
    }
}

/** Actual payload reduction. It never merely relabels FULL bytes as a lower disclosure. */
object ContextBundleDisclosureProjector {
    fun reduce(source: ContextBundle, level: DisclosureLevel): ContextBundle {
        if (!source.visibilityEnvelope.maximumDisclosure.canReduceTo(level)) throw VisibilityAuthorityFailure.Escalation()
        if (level == source.visibilityEnvelope.maximumDisclosure) return source
        val env = source.visibilityEnvelope.reduceTo(level)
        if (level == DisclosureLevel.DENY) return ContextBundle(
            playerStatus=emptyMap(),scene=emptyMap(),time=emptyMap(),activeThreads=emptyList(),relevantNpcs=emptyList(),npcKnowledge=emptyList(),
            missions=emptyList(),worldPressures=emptyList(),canonConstraints=emptyList(),recentChronicle=emptyList(),retrievedLongTermMemory=emptyList(),visibilityEnvelope=env,
            contextMeta=mapOf("disclosure_reduced" to true,"maximum_disclosure" to level.name)
        )
        if (level.rank <= DisclosureLevel.DISCLOSE_REDACTED.rank) return source.copy(
            playerStatus=source.playerStatus.filterKeys { it in setOf("chapter","player_input") },
            scene=source.scene.filterKeys { it in setOf("query") },
            activeThreads=emptyList(), npcKnowledge=emptyList(), canonConstraints=emptyList(), retrievedLongTermMemory=emptyList(),
            playerSkills=emptyList(),playerTechniques=emptyList(),playerInventory=emptyList(),playerOrganizations=emptyList(),npcMemories=emptyList(),
            campaignTruth=emptyList(),canonDivergences=emptyList(),playerState=emptyMap(),
            contextMeta=source.contextMeta.filterKeys { it in setOf("engine","schema","campaign_id","chapter","audience_kind_uid","purpose_uid") } + ("disclosure_reduced" to true),
            visibilityEnvelope=env
        )
        if (level.rank <= DisclosureLevel.DISCLOSE_PARTIAL.rank) return source.copy(
            canonConstraints=emptyList(),retrievedLongTermMemory=emptyList(),npcMemories=emptyList(),campaignTruth=emptyList(),canonDivergences=emptyList(),
            playerState=emptyMap(),contextMeta=source.contextMeta + ("disclosure_reduced" to true),visibilityEnvelope=env
        )
        if (level.rank <= DisclosureLevel.DETAILED.rank) return source.copy(
            activeThreads=source.activeThreads.map { it.filterKeys { key -> key in setOf("thread_uid","title","status") } },
            npcKnowledge=source.npcKnowledge.map { it.filterKeys { key -> key in setOf("subject_uid","predicate","epistemic_state") } },
            canonConstraints=emptyList(),retrievedLongTermMemory=emptyList(),npcMemories=emptyList(),campaignTruth=emptyList(),canonDivergences=emptyList(),playerState=emptyMap(),
            recentChronicle=source.recentChronicle.map { it.filterKeys { key -> key in setOf("chapter","title") } },
            contextMeta=source.contextMeta.filterKeys { it !in setOf("campaign_truth_state","player_state_state") } + ("disclosure_reduced" to true),visibilityEnvelope=env
        )
        return source.copy(visibilityEnvelope=env, contextMeta=source.contextMeta + ("disclosure_reduced" to true))
    }
}

data class PatchOperation(val op:String,val table:String,val key:Map<String,Any?>,val values:Map<String,Any?>)
data class StatePatch(val transactionId:String,val operations:List<PatchOperation>,val chapterManifest:Map<String,Any?> = emptyMap(),val requiresValidation:Boolean = true)
data class PatchResult(val success:Boolean,val appliedOperations:Int,val message:String)
