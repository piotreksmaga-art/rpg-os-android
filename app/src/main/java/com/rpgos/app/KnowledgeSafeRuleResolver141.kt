package com.rpgos.app

/**
 * Enforces the NPC-knowledge contract on resolved turns.
 * New BELIEF writes must have an explicit information path and a durable source id.
 */
class KnowledgeSafeRuleResolver141(
    private val delegate: GameMasterRuleResolver
) : GameMasterRuleResolver {
    override suspend fun resolve(
        request: GameMasterTurnRequest,
        context: GameMasterContext,
        proposal: GameMasterProposal
    ): GameMasterTurnResult {
        val result = delegate.resolve(request, context, proposal)
        result.truthWrites.filter { it.kind == TruthKind.BELIEF }.forEachIndexed { index, belief ->
            require(!belief.holderId.isNullOrBlank()) {
                "BELIEF #${index + 1} nie ma holderId."
            }
            require(!belief.sourceId.isNullOrBlank()) {
                "BELIEF #${index + 1} nie ma sourceId; wiedza NPC musi wskazywać swoje źródło."
            }
            val channel = requireNotNull(belief.knowledgeChannel) {
                "BELIEF #${index + 1} nie ma knowledgeChannel."
            }
            val expectedProvenance = when (channel) {
                KnowledgeChannel141.OBSERVATION -> ProvenanceType.NPC_OBSERVATION
                KnowledgeChannel141.REPORT -> ProvenanceType.NPC_REPORT
                KnowledgeChannel141.RESEARCH -> ProvenanceType.NPC_RESEARCH
                KnowledgeChannel141.INFERENCE -> ProvenanceType.NPC_INFERENCE
                KnowledgeChannel141.ORGANIZATION -> ProvenanceType.ORGANIZATION_REPORT
            }
            require(belief.sourceType == expectedProvenance) {
                "BELIEF #${index + 1}: kanał $channel wymaga provenance $expectedProvenance, otrzymano ${belief.sourceType}."
            }
            if (channel == KnowledgeChannel141.REPORT) {
                require(!belief.sourceNpcId.isNullOrBlank()) {
                    "BELIEF #${index + 1} z kanału REPORT nie ma sourceNpcId."
                }
            }
            if (channel == KnowledgeChannel141.ORGANIZATION) {
                require(belief.sourceNpcId.isNullOrBlank()) {
                    "BELIEF #${index + 1} z kanału ORGANIZATION nie może mieć sourceNpcId."
                }
            }
        }
        return result
    }
}
