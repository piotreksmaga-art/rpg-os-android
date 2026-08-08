package com.rpgos.app

import java.util.UUID

enum class KnowledgeChannel141 {
    OBSERVED,
    TOLD,
    REPORTED,
    RESEARCHED,
    INFERRED
}

data class KnowledgeTransferRequest141(
    val holderUid: EntityUid,
    val subjectUid: EntityUid?,
    val predicate: String,
    val value: String,
    val channel: KnowledgeChannel141,
    val sourceUid: EntityUid?,
    val sourceTurn: Long,
    val confidence: Double,
    val asFact: Boolean = false
)

/**
 * Deterministic knowledge gate for NPCs.
 * No NPC knowledge is created without an explicit acquisition channel and provenance.
 */
class GameMasterKnowledgeTransfer141(
    private val campaignUid: EntityUid
) {
    fun buildTruth(request: KnowledgeTransferRequest141): CampaignTruth {
        require(request.predicate.isNotBlank()) { "predicate nie może być pusty." }
        require(request.value.isNotBlank()) { "value nie może być puste." }
        require(request.sourceTurn >= 0L) { "sourceTurn nie może być ujemny." }
        require(request.confidence in 0.0..1.0) { "confidence musi należeć do 0..1." }

        when (request.channel) {
            KnowledgeChannel141.OBSERVED,
            KnowledgeChannel141.RESEARCHED,
            KnowledgeChannel141.INFERRED -> Unit
            KnowledgeChannel141.TOLD,
            KnowledgeChannel141.REPORTED -> require(request.sourceUid != null) {
                "Kanał ${request.channel} wymaga jawnego źródła informacji."
            }
        }

        val provenanceType = when (request.channel) {
            KnowledgeChannel141.OBSERVED -> ProvenanceType.DIRECT_OBSERVATION
            KnowledgeChannel141.TOLD -> ProvenanceType.NPC_REPORT
            KnowledgeChannel141.REPORTED -> ProvenanceType.NPC_REPORT
            KnowledgeChannel141.RESEARCHED -> ProvenanceType.RESEARCH_RESULT
            KnowledgeChannel141.INFERRED -> ProvenanceType.NPC_INFERENCE
        }

        val kind = if (request.asFact && request.channel in setOf(
                KnowledgeChannel141.OBSERVED,
                KnowledgeChannel141.RESEARCHED
            )
        ) TruthKind.FACT else TruthKind.BELIEF

        return CampaignTruth(
            uid = EntityUid("TRUTH-${UUID.randomUUID()}"),
            campaignUid = campaignUid,
            kind = kind,
            holderUid = if (kind == TruthKind.BELIEF) request.holderUid else null,
            subjectUid = request.subjectUid,
            predicate = request.predicate,
            value = request.value,
            validFromTurn = request.sourceTurn,
            validUntilTurn = null,
            provenance = Provenance(
                type = provenanceType,
                sourceUid = request.sourceUid,
                turnId = request.sourceTurn,
                confidence = request.confidence
            )
        )
    }
}
