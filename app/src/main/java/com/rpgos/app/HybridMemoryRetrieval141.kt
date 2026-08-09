package com.rpgos.app

/**
 * Provider-neutral candidate boundary for hybrid long-campaign memory retrieval.
 *
 * Implementations may use a local ANN index, a backend embedding service or any
 * future index. They are never trusted as truth filters: candidates are only a
 * recall optimization and must pass the same campaign/turn/provenance gates as
 * repository-derived rows inside GameMasterRetriever141.
 */
data class HybridMemoryCandidate141(
    val memory: DurableMemoryRecord,
    val similarity: Double
) {
    init {
        require(similarity in 0.0..1.0) { "similarity musi mieścić się w zakresie 0..1." }
    }
}

fun interface HybridMemoryCandidateProvider141 {
    suspend fun candidates(
        campaignUid: EntityUid,
        query: String,
        atTurnId: Long,
        limit: Int
    ): List<HybridMemoryCandidate141>
}

/** Safe default: hybrid retrieval is optional and lexical retrieval remains usable. */
object NoOpHybridMemoryCandidateProvider141 : HybridMemoryCandidateProvider141 {
    override suspend fun candidates(
        campaignUid: EntityUid,
        query: String,
        atTurnId: Long,
        limit: Int
    ): List<HybridMemoryCandidate141> = emptyList()
}
