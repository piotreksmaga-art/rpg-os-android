package com.rpgos.app

/**
 * Resolves the persistent provenance chain of a FACT/BELIEF without adding a second lineage store.
 * Each propagated BELIEF already points to the previous CampaignTruth through provenance.sourceUid.
 */
class KnowledgeLineageResolver141(
    private val repository: UnifiedCampaignRepository,
    private val campaignUid: EntityUid
) {
    data class Result(
        val chain: List<CampaignTruth>,
        val terminalSourceUid: EntityUid?,
        val cycleDetected: Boolean,
        val truncated: Boolean
    )

    suspend fun resolve(
        truth: CampaignTruth,
        maxDepth: Int = 32
    ): Result {
        require(maxDepth in 1..256) { "maxDepth musi należeć do 1..256." }

        val chain = mutableListOf<CampaignTruth>()
        val visited = linkedSetOf<EntityUid>()
        var current = truth
        var terminalSourceUid: EntityUid? = null
        var cycleDetected = false
        var truncated = false

        repeat(maxDepth) {
            if (!visited.add(current.uid)) {
                cycleDetected = true
                return@repeat
            }
            chain += current

            val sourceUid = current.provenance.sourceUid
            if (sourceUid == null) return Result(chain, null, cycleDetected = false, truncated = false)

            // Propagated knowledge preserves subject + predicate. Query at the moment the
            // child was created so an older source can still be found if it later expired.
            val subject = current.subjectUid
            if (subject == null) {
                terminalSourceUid = sourceUid
                return Result(chain, terminalSourceUid, cycleDetected = false, truncated = false)
            }

            val sourceTurn = current.validFromTurn ?: current.provenance.turnId ?: 0L
            val candidates = repository.getTruth(
                campaignUid = campaignUid,
                subjectUid = subject,
                predicate = current.predicate,
                atTurnId = sourceTurn
            )
            val parent = candidates.firstOrNull { it.uid == sourceUid }
            if (parent == null) {
                terminalSourceUid = sourceUid
                return Result(chain, terminalSourceUid, cycleDetected = false, truncated = false)
            }

            if (parent.uid in visited) {
                chain += parent
                cycleDetected = true
                return Result(chain, parent.uid, cycleDetected = true, truncated = false)
            }
            current = parent
        }

        truncated = true
        terminalSourceUid = current.provenance.sourceUid
        return Result(chain, terminalSourceUid, cycleDetected, truncated)
    }
}
