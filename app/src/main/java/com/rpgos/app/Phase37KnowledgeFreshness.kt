package com.rpgos.app

/**
 * Pure epistemic freshness helper. It never refreshes or mutates KnowledgeState when world truth
 * changes; Phase 39 remains the future owner of temporal/historical resolution.
 */
object KnowledgeFreshness {
    fun isOutdated(state: KnowledgeState, currentObservedOrder: Long): Boolean {
        require(currentObservedOrder >= 0L)
        val observed = state.quality.sourceObservedOrder ?: return true
        return observed < currentObservedOrder || state.epistemicState == KnowledgeEpistemicState.OUTDATED
    }
}
