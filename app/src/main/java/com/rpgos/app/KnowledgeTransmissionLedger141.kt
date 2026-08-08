package com.rpgos.app

import java.util.UUID

data class KnowledgeTransmission141(
    val transmissionUid: EntityUid,
    val campaignUid: EntityUid,
    val sourceTruthUid: EntityUid,
    val sourceNpcUid: EntityUid?,
    val receiverUid: EntityUid,
    val resultingBeliefUid: EntityUid,
    val channel: KnowledgeChannel141,
    val turnId: Long,
    val confidence: Double
) {
    init {
        require(turnId >= 0L) { "turnId nie może być ujemny." }
        require(confidence in 0.0..1.0) { "confidence musi być w zakresie 0..1." }
        require(channel != KnowledgeChannel141.REPORT || sourceNpcUid != null) {
            "REPORT wymaga sourceNpcUid."
        }
    }
}

/** Optional capability so test/future repositories do not have to implement ledger storage. */
interface KnowledgeTransmissionStore141 {
    suspend fun appendKnowledgeTransmission(record: KnowledgeTransmission141)

    suspend fun knowledgeTransmissionsForReceiver(
        campaignUid: EntityUid,
        receiverUid: EntityUid,
        beforeOrAtTurn: Long? = null,
        limit: Int = 100
    ): List<KnowledgeTransmission141>
}

class KnowledgeTransmissionFactory141(
    private val uidFactory: () -> EntityUid = { EntityUid("KNOW-${UUID.randomUUID()}") }
) {
    fun from(
        campaignUid: EntityUid,
        request: KnowledgePropagationRequest141,
        resultingBelief: CampaignTruth
    ): KnowledgeTransmission141 = KnowledgeTransmission141(
        transmissionUid = uidFactory(),
        campaignUid = campaignUid,
        sourceTruthUid = request.sourceTruth.uid,
        sourceNpcUid = request.sourceNpcUid,
        receiverUid = request.receiverUid,
        resultingBeliefUid = resultingBelief.uid,
        channel = request.channel,
        turnId = request.turnId,
        confidence = resultingBelief.provenance.confidence
    )
}
