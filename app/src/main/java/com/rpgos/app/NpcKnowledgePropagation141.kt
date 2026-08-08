package com.rpgos.app

import java.util.UUID

/** Explicit information paths between NPC knowledge states. */
enum class KnowledgeChannel141 {
    OBSERVATION,
    REPORT,
    INFERENCE
}

data class KnowledgePropagationRequest141(
    val receiverUid: EntityUid,
    val sourceTruth: CampaignTruth,
    val channel: KnowledgeChannel141,
    val turnId: Long,
    val sourceNpcUid: EntityUid? = null,
    val confidenceMultiplier: Double = 1.0
) {
    init {
        require(turnId >= 0L) { "turnId nie może być ujemny." }
        require(confidenceMultiplier in 0.0..1.0) { "confidenceMultiplier musi być w zakresie 0..1." }
    }
}

/**
 * Converts accessible information into holder-scoped BELIEF records.
 * It never promotes information into global FACT and never mutates source truth.
 */
class NpcKnowledgePropagation141(
    private val uidFactory: () -> EntityUid = { EntityUid("BELIEF-${UUID.randomUUID()}") }
) {
    fun propagate(request: KnowledgePropagationRequest141): CampaignTruth {
        val source = request.sourceTruth
        require(source.kind != TruthKind.NARRATIVE) {
            "NARRATIVE nie może być bezpośrednim źródłem wiedzy NPC."
        }
        require(source.validFromTurn == null || source.validFromTurn <= request.turnId) {
            "Nie można propagować wiedzy z przyszłości."
        }
        require(source.validUntilTurn == null || source.validUntilTurn >= request.turnId) {
            "Źródło wiedzy nie obowiązuje już w tej turze."
        }

        val provenanceType: ProvenanceType
        val channelConfidence: Double
        when (request.channel) {
            KnowledgeChannel141.OBSERVATION -> {
                require(source.kind == TruthKind.FACT) {
                    "OBSERVATION wymaga obserwowalnego FACT jako źródła."
                }
                provenanceType = ProvenanceType.NPC_OBSERVATION
                channelConfidence = 1.0
            }

            KnowledgeChannel141.REPORT -> {
                val sourceNpc = requireNotNull(request.sourceNpcUid) {
                    "REPORT wymaga sourceNpcUid."
                }
                if (source.kind == TruthKind.BELIEF) {
                    require(source.holderUid == sourceNpc) {
                        "NPC może raportować BELIEF tylko wtedy, gdy sam jest jego holderem."
                    }
                }
                provenanceType = ProvenanceType.NPC_REPORT
                channelConfidence = 0.85
            }

            KnowledgeChannel141.INFERENCE -> {
                provenanceType = ProvenanceType.NPC_INFERENCE
                channelConfidence = 0.70
            }
        }

        val confidence = (
            source.provenance.confidence * channelConfidence * request.confidenceMultiplier
        ).coerceIn(0.0, 1.0)

        return CampaignTruth(
            uid = uidFactory(),
            kind = TruthKind.BELIEF,
            subjectUid = source.subjectUid,
            predicate = source.predicate,
            value = source.value,
            holderUid = request.receiverUid,
            validFromTurn = request.turnId,
            validUntilTurn = source.validUntilTurn,
            provenance = ProvenanceRecord(
                type = provenanceType,
                sourceUid = source.uid,
                turnId = request.turnId,
                confidence = confidence,
                canonStatus = source.provenance.canonStatus,
                verified = false
            )
        )
    }
}
