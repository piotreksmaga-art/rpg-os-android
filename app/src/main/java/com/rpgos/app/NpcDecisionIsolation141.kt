package com.rpgos.app

/**
 * Typed boundary for any AI/local-agent decision executed on behalf of a single NPC.
 *
 * The caller may know the whole campaign, but the decision gateway never receives GameMasterContext.
 * It receives only the holder-scoped view produced by NpcKnowledgeAccessPolicy141 plus explicitly
 * supplied non-secret local state (identity/goals/resources/visible scene).
 */
data class NpcDecisionRequest141(
    val npcUid: EntityUid,
    val turnId: Long,
    val purpose: String,
    val localState: Map<String, String> = emptyMap(),
    val grants: List<NpcKnowledgeAccessPolicy141.Grant> = emptyList(),
    val observer: ScenePerceptionGrantResolver141.Observer? = null,
    val sceneFacts: List<ScenePerceptionGrantResolver141.CandidateFact> = emptyList()
) {
    init {
        require(turnId >= 0L) { "turnId nie może być ujemny." }
        require(purpose.isNotBlank()) { "purpose nie może być pusty." }
        require(observer == null || observer.npcUid == npcUid) {
            "Observer perception musi należeć do NPC ${npcUid.value}."
        }
        require(observer != null || sceneFacts.isEmpty()) {
            "sceneFacts wymagają observer perception."
        }
    }
}

data class NpcDecisionContext141(
    val npcUid: EntityUid,
    val turnId: Long,
    val purpose: String,
    val localState: Map<String, String>,
    val knowledge: NpcKnowledgeAccessPolicy141.View,
    val perceptionDenied: List<ScenePerceptionGrantResolver141.DeniedCandidate> = emptyList()
) {
    init {
        require(knowledge.holderUid == npcUid) {
            "Widok wiedzy należy do ${knowledge.holderUid.value}, a nie do ${npcUid.value}."
        }
        require(knowledge.atTurnId == turnId) {
            "Widok wiedzy pochodzi z tury ${knowledge.atTurnId}, oczekiwano $turnId."
        }
    }
}

data class NpcDecisionProposal141(
    val intention: String,
    val dialogue: String? = null,
    val referencedTruthUids: Set<EntityUid> = emptySet(),
    val diagnostics: List<String> = emptyList()
) {
    init {
        require(intention.isNotBlank()) { "NPC decision intention nie może być puste." }
    }
}

/**
 * Deliberately cannot accept GameMasterContext. Implementations may be AI-backed or deterministic,
 * but every decision must cross this holder-scoped interface.
 */
fun interface NpcDecisionGateway141 {
    suspend fun decide(context: NpcDecisionContext141): NpcDecisionProposal141
}

/**
 * Builds an isolated context, resolves one-turn scene perception, invokes the NPC gateway and
 * validates that every explicitly referenced durable truth was actually visible to that NPC at
 * the decision turn.
 */
class NpcDecisionService141(
    repository: UnifiedCampaignRepository,
    campaignUid: EntityUid,
    private val gateway: NpcDecisionGateway141
) {
    private val accessPolicy = NpcKnowledgeAccessPolicy141(repository, campaignUid)
    private val perceptionResolver = ScenePerceptionGrantResolver141()

    suspend fun decide(request: NpcDecisionRequest141): NpcDecisionProposal141 {
        val perception = request.observer?.let { observer ->
            perceptionResolver.resolve(
                observer = observer,
                turnId = request.turnId,
                candidates = request.sceneFacts
            )
        } ?: ScenePerceptionGrantResolver141.Result(emptyList(), emptyList())

        val knowledge = accessPolicy.buildView(
            holderUid = request.npcUid,
            atTurnId = request.turnId,
            grants = request.grants + perception.grants
        )
        val context = NpcDecisionContext141(
            npcUid = request.npcUid,
            turnId = request.turnId,
            purpose = request.purpose,
            localState = request.localState.toMap(),
            knowledge = knowledge,
            perceptionDenied = perception.denied
        )

        val proposal = gateway.decide(context)
        proposal.referencedTruthUids.forEach(knowledge::requireAccess)
        return proposal
    }
}
