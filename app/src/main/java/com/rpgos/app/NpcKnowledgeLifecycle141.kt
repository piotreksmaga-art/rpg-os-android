package com.rpgos.app

import java.util.UUID

/**
 * Resolves contradictory holder-scoped BELIEF records without deleting history.
 * A conflict exists when the same holder/subject/predicate has different values.
 * Resolution is deterministic: direct observation outranks organization/report,
 * which outrank inference; within the same tier confidence and recency decide.
 */
class NpcKnowledgeLifecycle141(
    private val resolutionUidFactory: () -> EntityUid = { EntityUid("KRES-${UUID.randomUUID()}") }
) {
    enum class ResolutionReason {
        STRONGER_PROVENANCE,
        HIGHER_CONFIDENCE,
        NEWER_EVIDENCE,
        UNRESOLVED_TIE
    }

    data class Conflict(
        val holderUid: EntityUid,
        val subjectUid: EntityUid?,
        val predicate: String,
        val competingBeliefs: List<CampaignTruth>
    )

    data class Resolution(
        val resolutionUid: EntityUid,
        val conflict: Conflict,
        val winner: CampaignTruth?,
        val supersededBeliefUids: List<EntityUid>,
        val reason: ResolutionReason,
        val turnId: Long
    )

    data class Result(
        val effectiveBeliefs: List<CampaignTruth>,
        val resolutions: List<Resolution>,
        val unresolvedConflicts: List<Conflict>
    )

    fun resolve(holderUid: EntityUid, turnId: Long, beliefs: List<CampaignTruth>): Result {
        require(turnId >= 0L) { "turnId nie może być ujemny." }
        val active = beliefs.filter {
            it.kind == TruthKind.BELIEF && it.holderUid == holderUid &&
                (it.validFromTurn == null || it.validFromTurn <= turnId) &&
                (it.validUntilTurn == null || it.validUntilTurn >= turnId)
        }

        val effective = mutableListOf<CampaignTruth>()
        val resolutions = mutableListOf<Resolution>()
        val unresolved = mutableListOf<Conflict>()

        active.groupBy { Pair(it.subjectUid, it.predicate) }.values.forEach { group ->
            val values = group.groupBy { it.value }
            if (values.size <= 1) {
                effective += group.maxWithOrNull(preferenceComparator()) ?: return@forEach
                return@forEach
            }

            val conflict = Conflict(holderUid, group.first().subjectUid, group.first().predicate, group)
            val ordered = group.sortedWith(preferenceComparator().reversed())
            val first = ordered[0]
            val second = ordered[1]
            val comparison = compareBeliefs(first, second)
            if (comparison == 0) {
                unresolved += conflict
                effective += ordered
                resolutions += Resolution(
                    resolutionUidFactory(), conflict, null, emptyList(), ResolutionReason.UNRESOLVED_TIE, turnId
                )
            } else {
                val reason = resolutionReason(first, second)
                effective += first
                resolutions += Resolution(
                    resolutionUidFactory(),
                    conflict,
                    first,
                    ordered.drop(1).map { it.uid },
                    reason,
                    turnId
                )
            }
        }

        return Result(effective.distinctBy { it.uid }, resolutions, unresolved)
    }

    private fun provenanceRank(type: ProvenanceType): Int = when (type) {
        ProvenanceType.NPC_OBSERVATION -> 500
        ProvenanceType.NPC_RESEARCH -> 450
        ProvenanceType.NPC_REPORT -> 350
        ProvenanceType.NPC_INFERENCE -> 250
        ProvenanceType.PLAYER_CLAIM -> 200
        else -> 300
    }

    private fun compareBeliefs(a: CampaignTruth, b: CampaignTruth): Int {
        val rank = provenanceRank(a.provenance.type).compareTo(provenanceRank(b.provenance.type))
        if (rank != 0) return rank
        val confidence = a.provenance.confidence.compareTo(b.provenance.confidence)
        if (confidence != 0) return confidence
        val aTurn = a.provenance.turnId ?: a.validFromTurn ?: Long.MIN_VALUE
        val bTurn = b.provenance.turnId ?: b.validFromTurn ?: Long.MIN_VALUE
        return aTurn.compareTo(bTurn)
    }

    private fun preferenceComparator(): Comparator<CampaignTruth> = Comparator(::compareBeliefs)

    private fun resolutionReason(winner: CampaignTruth, loser: CampaignTruth): ResolutionReason = when {
        provenanceRank(winner.provenance.type) != provenanceRank(loser.provenance.type) -> ResolutionReason.STRONGER_PROVENANCE
        winner.provenance.confidence != loser.provenance.confidence -> ResolutionReason.HIGHER_CONFIDENCE
        else -> ResolutionReason.NEWER_EVIDENCE
    }
}

/** Append-only audit store; superseded beliefs remain in immutable history. */
interface NpcKnowledgeResolutionStore141 {
    suspend fun appendResolution(record: NpcKnowledgeLifecycle141.Resolution)
}
