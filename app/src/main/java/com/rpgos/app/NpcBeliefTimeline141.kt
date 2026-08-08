package com.rpgos.app

/**
 * Read-only reconstruction of one NPC's belief history. It never mutates campaign truth.
 * The timeline combines durable BELIEF records with append-only retractions so callers can
 * answer: what the NPC believed, when it changed, and what evidence replaced it.
 */
class NpcBeliefTimeline141(
    private val repository: UnifiedCampaignRepository,
    private val campaignUid: EntityUid,
    private val retractionStore: NpcBeliefRetractionStore141
) {
    enum class Status {
        ACTIVE,
        RETRACTED,
        EXPIRED
    }

    data class Entry(
        val belief: CampaignTruth,
        val status: Status,
        val learnedTurn: Long?,
        val endedTurn: Long?,
        val retraction: NpcBeliefRetraction141? = null,
        val replacementTruth: CampaignTruth? = null
    )

    data class Result(
        val holderUid: EntityUid,
        val subjectUid: EntityUid?,
        val predicate: String?,
        val atTurnId: Long,
        val entries: List<Entry>
    ) {
        val activeBeliefs: List<CampaignTruth> = entries.filter { it.status == Status.ACTIVE }.map { it.belief }
        val retractedBeliefs: List<CampaignTruth> = entries.filter { it.status == Status.RETRACTED }.map { it.belief }
    }

    suspend fun query(
        holderUid: EntityUid,
        subjectUid: EntityUid? = null,
        predicate: String? = null,
        atTurnId: Long? = null,
        limit: Int = 500
    ): Result {
        require(limit in 1..5_000) { "limit musi należeć do 1..5000." }
        require(predicate == null || predicate.isNotBlank()) { "predicate nie może być pusty." }
        val turn = atTurnId ?: repository.currentTurnId(campaignUid)

        val beliefs = repository.getBeliefs(
            campaignUid = campaignUid,
            holderUid = holderUid,
            subjectUid = subjectUid,
            atTurnId = null,
            limit = limit
        ).asSequence()
            .filter { it.kind == TruthKind.BELIEF && it.holderUid == holderUid }
            .filter { subjectUid == null || it.subjectUid == subjectUid }
            .filter { predicate == null || it.predicate == predicate }
            .filter { (it.validFromTurn ?: Long.MIN_VALUE) <= turn }
            .distinctBy { it.uid }
            .toList()

        val retractions = retractionStore.retractionsForHolder(campaignUid, holderUid, turn)
            .associateBy { it.retractedBeliefUid }

        val entries = beliefs.map { belief ->
            val retraction = retractions[belief.uid]
            val replacement = retraction?.let { r ->
                repository.getTruth(
                    campaignUid = campaignUid,
                    subjectUid = requireNotNull(belief.subjectUid) {
                        "Retracted BELIEF ${belief.uid.value} bez subjectUid nie może odtworzyć replacement truth."
                    },
                    predicate = belief.predicate,
                    atTurnId = r.turnId
                ).firstOrNull { it.uid == r.replacementTruthUid }
            }

            val expiredAt = belief.validUntilTurn?.takeIf { it < turn }
            val status = when {
                retraction != null && retraction.turnId <= turn -> Status.RETRACTED
                expiredAt != null -> Status.EXPIRED
                else -> Status.ACTIVE
            }
            val endedTurn = when (status) {
                Status.RETRACTED -> retraction?.turnId
                Status.EXPIRED -> belief.validUntilTurn
                Status.ACTIVE -> null
            }

            Entry(
                belief = belief,
                status = status,
                learnedTurn = belief.provenance.turnId ?: belief.validFromTurn,
                endedTurn = endedTurn,
                retraction = retraction,
                replacementTruth = replacement
            )
        }.sortedWith(
            compareBy<Entry> { it.learnedTurn ?: Long.MIN_VALUE }
                .thenBy { it.belief.uid.value }
        )

        return Result(holderUid, subjectUid, predicate, turn, entries)
    }
}
