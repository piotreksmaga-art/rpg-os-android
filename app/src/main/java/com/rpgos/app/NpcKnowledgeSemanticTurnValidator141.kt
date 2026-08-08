package com.rpgos.app

/**
 * Adds semantic checks for lifecycle references that target truths created by the same turn.
 *
 * The base validator already checks that truthKey references exist. This layer additionally
 * verifies what those keys mean before persistence allocates durable UIDs: lifecycle outputs
 * that are required to be NPC beliefs must point to BELIEF writes owned by the expected NPC.
 * Durable UIDs from older turns remain covered by the durable ledger/integrity layer.
 */
class NpcKnowledgeSemanticTurnValidator141(
    private val delegate: GameMasterTurnValidator
) : GameMasterTurnValidator {

    override suspend fun validate(
        request: GameMasterTurnRequest,
        context: GameMasterContext,
        result: GameMasterTurnResult
    ): GameMasterValidationReport {
        val base = delegate.validate(request, context, result)
        val issues = base.issues.toMutableList()
        val keyedTruths = result.truthWrites.mapNotNull { truth ->
            truth.truthKey?.let { it to truth }
        }.toMap()

        fun truthFor(ref: TruthRef141): TruthWrite? = ref.truthKey?.let(keyedTruths::get)

        fun requireBeliefForHolder(ref: TruthRef141, holderId: String, label: String) {
            val truth = truthFor(ref) ?: return
            if (truth.kind != TruthKind.BELIEF) {
                issues += error(
                    "NPC_KNOWLEDGE_REF_NOT_BELIEF",
                    "$label wskazuje truthKey=${ref.truthKey}, który jest ${truth.kind}, a wymagany jest BELIEF."
                )
                return
            }
            if (truth.holderId != holderId) {
                issues += error(
                    "NPC_KNOWLEDGE_HOLDER_MISMATCH",
                    "$label wskazuje BELIEF holder=${truth.holderId}, oczekiwano holder=$holderId."
                )
            }
        }

        result.npcKnowledgeWrites.retractions.forEach { write ->
            requireBeliefForHolder(write.retractedBelief, write.holderId, "Retrakcja")
        }

        result.npcKnowledgeWrites.inferences.forEach { write ->
            requireBeliefForHolder(write.resultingBelief, write.holderId, "Inference result")
        }

        result.npcKnowledgeWrites.organizationTransmissions.forEach { write ->
            requireBeliefForHolder(write.resultingBelief, write.receiverId, "Organization knowledge result")
        }

        result.npcKnowledgeWrites.resolutions.forEach { write ->
            write.competingBeliefs.forEach {
                requireBeliefForHolder(it, write.holderId, "Resolution competing belief")
            }
            write.winner?.let {
                requireBeliefForHolder(it, write.holderId, "Resolution winner")
            }
            write.supersededBeliefs.forEach {
                requireBeliefForHolder(it, write.holderId, "Resolution superseded belief")
            }
        }

        return GameMasterValidationReport(issues)
    }

    private fun error(code: String, message: String) =
        GameMasterValidationIssue(code, message, ValidationSeverity.ERROR)
}
