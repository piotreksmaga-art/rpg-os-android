package com.rpgos.app

/**
 * Converts FACTs that were actually granted by scene perception into durable holder-scoped BELIEFs.
 *
 * The promoter is deliberately conservative:
 * - only one-turn OBSERVABLE_FACT grants for the same holder/turn are accepted,
 * - the source truth must still be an active FACT,
 * - an identical active BELIEF is not written again,
 * - confidence never exceeds the observed FACT confidence,
 * - a changed observed value creates a new BELIEF while leaving immutable history intact.
 */
class ObservationBeliefPromoter141(
    private val repository: UnifiedCampaignRepository,
    private val campaignUid: EntityUid,
    private val knowledgeStore: KnowledgeTransmissionStore141,
    private val propagation: NpcKnowledgePropagation141 = NpcKnowledgePropagation141(),
    private val transmissionFactory: KnowledgeTransmissionFactory141 = KnowledgeTransmissionFactory141()
) {
    data class PromotionResult(
        val createdBeliefs: List<CampaignTruth>,
        val skippedTruthUids: Set<EntityUid>
    )

    suspend fun promote(
        holderUid: EntityUid,
        turnId: Long,
        grants: List<NpcKnowledgeAccessPolicy141.Grant>
    ): PromotionResult {
        require(turnId >= 0L) { "turnId nie może być ujemny." }

        val created = mutableListOf<CampaignTruth>()
        val skipped = linkedSetOf<EntityUid>()

        grants.distinctBy { it.truthUid }.forEach { grant ->
            require(grant.holderUid == holderUid) { "Grant obserwacji należy do innego NPC." }
            require(grant.kind == NpcKnowledgeAccessPolicy141.GrantKind.OBSERVABLE_FACT) {
                "Tylko OBSERVABLE_FACT może zostać promowany jako obserwacja."
            }
            require(grant.validFromTurn == turnId && grant.validUntilTurn == turnId) {
                "Grant obserwacji musi być ograniczony dokładnie do tury $turnId."
            }

            val source = repository.getTruth(
                campaignUid = campaignUid,
                subjectUid = grant.subjectUid,
                predicate = grant.predicate,
                atTurnId = turnId
            ).firstOrNull { it.uid == grant.truthUid }
                ?: error("Źródłowy FACT ${grant.truthUid.value} nie istnieje w turze $turnId.")

            require(source.kind == TruthKind.FACT) { "OBSERVATION może promować wyłącznie FACT." }

            val duplicate = repository.getBeliefs(
                campaignUid = campaignUid,
                holderUid = holderUid,
                subjectUid = source.subjectUid,
                atTurnId = turnId,
                limit = 1_000
            ).any { existing ->
                existing.predicate == source.predicate &&
                    existing.value == source.value &&
                    existing.validFromTurn?.let { it <= turnId } != false &&
                    existing.validUntilTurn?.let { it >= turnId } != false
            }

            if (duplicate) {
                skipped += source.uid
                return@forEach
            }

            val request = KnowledgePropagationRequest141(
                receiverUid = holderUid,
                sourceTruth = source,
                channel = KnowledgeChannel141.OBSERVATION,
                turnId = turnId,
                confidenceMultiplier = 1.0
            )
            val belief = propagation.propagate(request)
            require(belief.provenance.confidence <= source.provenance.confidence) {
                "Promocja obserwacji nie może zwiększyć confidence ponad źródłowy FACT."
            }

            repository.writeTruth(belief)
            knowledgeStore.appendKnowledgeTransmission(
                transmissionFactory.from(
                    campaignUid = campaignUid,
                    request = request,
                    resultingBelief = belief
                )
            )
            created += belief
        }

        return PromotionResult(
            createdBeliefs = created,
            skippedTruthUids = skipped
        )
    }
}
