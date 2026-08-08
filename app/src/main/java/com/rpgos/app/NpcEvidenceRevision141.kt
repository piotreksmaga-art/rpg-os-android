package com.rpgos.app

import java.util.UUID

/**
 * Append-only belief revision. A retraction never deletes the old BELIEF; it records
 * that the holder stopped treating it as effective after stronger contradictory evidence.
 */
data class NpcBeliefRetraction141(
    val retractionUid: EntityUid,
    val campaignUid: EntityUid,
    val holderUid: EntityUid,
    val retractedBeliefUid: EntityUid,
    val replacementTruthUid: EntityUid,
    val turnId: Long,
    val reason: String
) {
    init {
        require(turnId >= 0L) { "turnId nie może być ujemny." }
        require(reason.isNotBlank()) { "reason nie może być pusty." }
        require(retractedBeliefUid != replacementTruthUid) { "Belief nie może wycofać samego siebie." }
    }
}

interface NpcBeliefRetractionStore141 {
    suspend fun appendRetraction(record: NpcBeliefRetraction141)
    suspend fun retractionsForHolder(
        campaignUid: EntityUid,
        holderUid: EntityUid,
        beforeOrAtTurn: Long
    ): List<NpcBeliefRetraction141>
}

class NpcEvidenceRevision141(
    private val campaignUid: EntityUid,
    private val store: NpcBeliefRetractionStore141,
    private val uidFactory: () -> EntityUid = { EntityUid("RETRACT-${UUID.randomUUID()}") }
) {
    data class Result(
        val created: List<NpcBeliefRetraction141>,
        val unchangedBeliefUids: List<EntityUid>
    )

    suspend fun revise(
        view: NpcKnowledgeAccessPolicy141.View,
        replacementTruthUid: EntityUid,
        reason: String
    ): Result {
        require(reason.isNotBlank()) { "reason nie może być pusty." }
        view.requireAccess(replacementTruthUid)
        val replacement = requireNotNull(view.accessibleTruths.firstOrNull { it.uid == replacementTruthUid })

        val existingRetractions = store.retractionsForHolder(campaignUid, view.holderUid, view.atTurnId)
            .mapTo(hashSetOf()) { it.retractedBeliefUid }

        val candidates = view.beliefs.filter { belief ->
            belief.uid !in existingRetractions &&
                belief.subjectUid == replacement.subjectUid &&
                belief.predicate == replacement.predicate &&
                belief.value != replacement.value
        }

        val created = mutableListOf<NpcBeliefRetraction141>()
        val unchanged = mutableListOf<EntityUid>()
        candidates.forEach { belief ->
            if (!replacementIsStrongEnough(replacement, belief)) {
                unchanged += belief.uid
            } else {
                val record = NpcBeliefRetraction141(
                    retractionUid = uidFactory(),
                    campaignUid = campaignUid,
                    holderUid = view.holderUid,
                    retractedBeliefUid = belief.uid,
                    replacementTruthUid = replacement.uid,
                    turnId = view.atTurnId,
                    reason = reason
                )
                store.appendRetraction(record)
                created += record
            }
        }
        return Result(created, unchanged)
    }

    private fun replacementIsStrongEnough(replacement: CampaignTruth, oldBelief: CampaignTruth): Boolean {
        val replacementRank = rank(replacement.provenance.type)
        val oldRank = rank(oldBelief.provenance.type)
        if (replacementRank != oldRank) return replacementRank > oldRank
        if (replacement.provenance.confidence != oldBelief.provenance.confidence) {
            return replacement.provenance.confidence > oldBelief.provenance.confidence
        }
        val replacementTurn = replacement.provenance.turnId ?: replacement.validFromTurn ?: Long.MIN_VALUE
        val oldTurn = oldBelief.provenance.turnId ?: oldBelief.validFromTurn ?: Long.MIN_VALUE
        return replacementTurn > oldTurn
    }

    private fun rank(type: ProvenanceType): Int = when (type) {
        ProvenanceType.NPC_OBSERVATION -> 500
        ProvenanceType.NPC_RESEARCH -> 450
        ProvenanceType.NPC_REPORT -> 350
        ProvenanceType.NPC_INFERENCE -> 250
        ProvenanceType.PLAYER_CLAIM -> 200
        else -> 300
    }
}
