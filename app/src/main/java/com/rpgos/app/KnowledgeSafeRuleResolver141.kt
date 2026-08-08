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
            require(belief.sourceType in ALLOWED_BELIEF_SOURCES) {
                "BELIEF #${index + 1} ma niedozwolone źródło ${belief.sourceType}. Wymagany jawny kanał wiedzy NPC."
            }
            require(!belief.sourceId.isNullOrBlank()) {
                "BELIEF #${index + 1} nie ma sourceId; wiedza NPC musi wskazywać swoje źródło."
            }
        }
        return result
    }

    companion object {
        private val ALLOWED_BELIEF_SOURCES = setOf(
            ProvenanceType.NPC_OBSERVATION,
            ProvenanceType.NPC_REPORT,
            ProvenanceType.NPC_INFERENCE
        )
    }
}
