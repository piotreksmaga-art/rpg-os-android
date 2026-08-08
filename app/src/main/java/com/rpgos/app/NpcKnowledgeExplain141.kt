package com.rpgos.app

/** Optional read capability for inference stores. Existing write-only stores remain compatible. */
interface NpcInferenceQueryStore141 : NpcInferenceStore141 {
    suspend fun inferenceForBelief(
        campaignUid: EntityUid,
        holderUid: EntityUid,
        resultingBeliefUid: EntityUid
    ): NpcInferenceLedgerEntry141?
}

/**
 * High-level, read-only explanation of why one NPC believes a statement.
 * Combines direct provenance lineage, multi-premise inference metadata and belief timeline/retractions.
 */
class NpcKnowledgeExplain141(
    private val repository: UnifiedCampaignRepository,
    private val campaignUid: EntityUid,
    private val retractionStore: NpcBeliefRetractionStore141,
    private val inferenceQueryStore: NpcInferenceQueryStore141? = null,
    private val lineageResolver: KnowledgeLineageResolver141 = KnowledgeLineageResolver141(repository, campaignUid)
) {
    data class Explanation(
        val holderUid: EntityUid,
        val belief: CampaignTruth,
        val status: NpcBeliefTimeline141.Status,
        val provenanceChain: List<CampaignTruth>,
        val terminalSourceUid: EntityUid?,
        val inferencePremiseUids: List<EntityUid>,
        val retraction: NpcBeliefRetraction141?,
        val replacementTruth: CampaignTruth?,
        val learnedTurn: Long?,
        val endedTurn: Long?,
        val cycleDetected: Boolean,
        val lineageTruncated: Boolean
    ) {
        val isCurrent: Boolean get() = status == NpcBeliefTimeline141.Status.ACTIVE
    }

    suspend fun explain(
        holderUid: EntityUid,
        beliefUid: EntityUid,
        atTurnId: Long? = null,
        maxDepth: Int = 32
    ): Explanation {
        val turn = atTurnId ?: repository.currentTurnId(campaignUid)
        val belief = findBelief(holderUid, beliefUid, turn)
        val timeline = NpcBeliefTimeline141(repository, campaignUid, retractionStore).query(
            holderUid = holderUid,
            subjectUid = belief.subjectUid,
            predicate = belief.predicate,
            atTurnId = turn,
            limit = 5_000
        )
        val entry = requireNotNull(timeline.entries.firstOrNull { it.belief.uid == beliefUid }) {
            "BELIEF ${beliefUid.value} nie istnieje na osi wiedzy NPC ${holderUid.value} w turze $turn."
        }

        val lineage = lineageResolver.resolve(belief, maxDepth)
        val inference = if (belief.provenance.type == ProvenanceType.NPC_INFERENCE) {
            inferenceQueryStore?.inferenceForBelief(campaignUid, holderUid, beliefUid)
        } else null

        return Explanation(
            holderUid = holderUid,
            belief = belief,
            status = entry.status,
            provenanceChain = lineage.chain,
            terminalSourceUid = lineage.terminalSourceUid,
            inferencePremiseUids = inference?.premiseTruthUids.orEmpty(),
            retraction = entry.retraction,
            replacementTruth = entry.replacementTruth,
            learnedTurn = entry.learnedTurn,
            endedTurn = entry.endedTurn,
            cycleDetected = lineage.cycleDetected,
            lineageTruncated = lineage.truncated
        )
    }

    private suspend fun findBelief(holderUid: EntityUid, beliefUid: EntityUid, turn: Long): CampaignTruth {
        val beliefs = repository.getBeliefs(
            campaignUid = campaignUid,
            holderUid = holderUid,
            subjectUid = null,
            atTurnId = null,
            limit = 5_000
        )
        return requireNotNull(beliefs.firstOrNull {
            it.uid == beliefUid && it.kind == TruthKind.BELIEF && it.holderUid == holderUid &&
                (it.validFromTurn ?: Long.MIN_VALUE) <= turn
        }) {
            "BELIEF ${beliefUid.value} nie należy do NPC ${holderUid.value} albo nie istniał jeszcze w turze $turn."
        }
    }
}
