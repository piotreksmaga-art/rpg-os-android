package com.rpgos.app

import java.util.UUID

/**
 * Produces durable NPC BELIEF proposals only from truths that are present in the
 * holder-scoped NpcKnowledgeAccessPolicy141.View.
 *
 * The engine is deliberately deterministic and conservative: every premise must
 * be accessible to the NPC, confidence can only decrease, and the inference
 * keeps an explicit premise list for later lineage/audit.
 */
class NpcInferenceEngine141(
    private val uidFactory: () -> EntityUid = { EntityUid("BELIEF-${UUID.randomUUID()}") }
) {
    data class Request(
        val view: NpcKnowledgeAccessPolicy141.View,
        val subjectUid: EntityUid?,
        val predicate: String,
        val value: String,
        val premiseTruthUids: List<EntityUid>,
        val confidenceMultiplier: Double = 0.70,
        val validUntilTurn: Long? = null
    ) {
        init {
            require(predicate.isNotBlank()) { "predicate nie może być pusty." }
            require(premiseTruthUids.isNotEmpty()) { "Inference wymaga co najmniej jednej przesłanki." }
            require(premiseTruthUids.distinct().size == premiseTruthUids.size) {
                "Inference nie może zawierać zduplikowanych przesłanek."
            }
            require(confidenceMultiplier in 0.0..1.0) {
                "confidenceMultiplier musi mieścić się w 0..1."
            }
            require(validUntilTurn == null || validUntilTurn >= view.atTurnId) {
                "validUntilTurn nie może być wcześniejszy niż tura inference."
            }
        }
    }

    data class Result(
        val belief: CampaignTruth,
        val premiseTruths: List<CampaignTruth>
    )

    fun infer(request: Request): Result {
        val premiseByUid = request.view.accessibleTruths.associateBy { it.uid }
        val premises = request.premiseTruthUids.map { uid ->
            require(request.view.canAccess(uid)) {
                "NPC ${request.view.holderUid.value} nie ma dostępu do przesłanki ${uid.value}."
            }
            requireNotNull(premiseByUid[uid]) {
                "Dostępna przesłanka ${uid.value} nie istnieje w view."
            }
        }

        val weakestPremiseConfidence = premises.minOf { it.provenance.confidence }
        val confidence = (weakestPremiseConfidence * request.confidenceMultiplier)
            .coerceIn(0.0, 1.0)

        val belief = CampaignTruth(
            uid = uidFactory(),
            kind = TruthKind.BELIEF,
            subjectUid = request.subjectUid,
            predicate = request.predicate,
            value = request.value,
            holderUid = request.view.holderUid,
            validFromTurn = request.view.atTurnId,
            validUntilTurn = request.validUntilTurn,
            provenance = ProvenanceRecord(
                type = ProvenanceType.NPC_INFERENCE,
                sourceUid = premises.first().uid,
                turnId = request.view.atTurnId,
                confidence = confidence,
                verified = false
            )
        )

        return Result(belief = belief, premiseTruths = premises)
    }
}

data class NpcInferenceLedgerEntry141(
    val inferenceUid: EntityUid,
    val campaignUid: EntityUid,
    val holderUid: EntityUid,
    val resultingBeliefUid: EntityUid,
    val premiseTruthUids: List<EntityUid>,
    val turnId: Long,
    val confidence: Double
) {
    init {
        require(premiseTruthUids.isNotEmpty()) { "Inference ledger wymaga przesłanek." }
        require(confidence in 0.0..1.0) { "confidence musi mieścić się w 0..1." }
    }
}

interface NpcInferenceStore141 {
    suspend fun appendInference(record: NpcInferenceLedgerEntry141)
}

/** Persists a non-duplicate inference together with an auditable premise set. */
class NpcInferencePromoter141(
    private val repository: UnifiedCampaignRepository,
    private val campaignUid: EntityUid,
    private val inferenceStore: NpcInferenceStore141,
    private val engine: NpcInferenceEngine141 = NpcInferenceEngine141(),
    private val ledgerUidFactory: () -> EntityUid = { EntityUid("INFER-${UUID.randomUUID()}") }
) {
    data class PromotionResult(
        val createdBelief: CampaignTruth?,
        val duplicate: Boolean
    )

    suspend fun promote(request: NpcInferenceEngine141.Request): PromotionResult {
        val result = engine.infer(request)
        val candidate = result.belief

        val duplicate = repository.getBeliefs(
            campaignUid = campaignUid,
            holderUid = request.view.holderUid,
            subjectUid = candidate.subjectUid,
            atTurnId = request.view.atTurnId,
            limit = 200
        ).any {
            it.predicate == candidate.predicate &&
                it.value == candidate.value &&
                (it.validFromTurn == null || it.validFromTurn <= request.view.atTurnId) &&
                (it.validUntilTurn == null || it.validUntilTurn >= request.view.atTurnId)
        }

        if (duplicate) return PromotionResult(createdBelief = null, duplicate = true)

        repository.writeTruth(candidate)
        inferenceStore.appendInference(
            NpcInferenceLedgerEntry141(
                inferenceUid = ledgerUidFactory(),
                campaignUid = campaignUid,
                holderUid = request.view.holderUid,
                resultingBeliefUid = candidate.uid,
                premiseTruthUids = result.premiseTruths.map { it.uid },
                turnId = request.view.atTurnId,
                confidence = candidate.provenance.confidence
            )
        )
        return PromotionResult(createdBelief = candidate, duplicate = false)
    }
}
