package com.rpgos.app

import java.util.Collections

enum class MutationAuthorityClass {
    GAMEPLAY_AUTHORITATIVE,
    ADMINISTRATIVE_MIGRATION_INSTALL_RECOVERY,
    DERIVED_CACHE_PRESENTATION,
    APPEND_ONLY_COMMIT_EVIDENCE
}

private val CANONICAL_PROPOSAL_SEAL = Any()

sealed interface CampaignMutationAdmission {
    data class Accepted(val proposal: CanonicalCampaignMutationProposal) : CampaignMutationAdmission
    data class Rejected(val reasonUid: String) : CampaignMutationAdmission
}

/** Opaque proof that PlayerDomainEngine.resolve() completed the canonical legality pipeline. */
class CanonicalCampaignMutationProposal internal constructor(
    val campaignUid: String,
    val playerChangeSet: PlayerChangeSet,
    val authorityClass: MutationAuthorityClass,
    causalRelationIntents: List<CanonicalCausalRelationIntent>,
    private val seal: Any
) {
    val causalRelationIntents: List<CanonicalCausalRelationIntent> =
        Collections.unmodifiableList(ArrayList(causalRelationIntents))

    init {
        require(seal === CANONICAL_PROPOSAL_SEAL) { "RPGOS-MUTATION-GATE:FORGED_CANONICAL_PROPOSAL" }
        require(authorityClass == MutationAuthorityClass.GAMEPLAY_AUTHORITATIVE)
        require(campaignUid == playerChangeSet.campaignUid)
        CampaignCausalPlanValidator.validate(this.causalRelationIntents)
    }
    internal fun isCanonical(): Boolean = seal === CANONICAL_PROPOSAL_SEAL
}

class AdministrativeMutationCapability private constructor(
    val operationUid: String,
    val authorityClass: MutationAuthorityClass
) {
    init { require(operationUid.isNotBlank()) }
    internal companion object {
        fun create(operationUid: String) = AdministrativeMutationCapability(
            operationUid, MutationAuthorityClass.ADMINISTRATIVE_MIGRATION_INSTALL_RECOVERY
        )
    }
}

internal object AdministrativeMutationCapabilities {
    fun forMigration(operationUid: String) = AdministrativeMutationCapability.create(operationUid)
    fun forInstall(operationUid: String) = AdministrativeMutationCapability.create(operationUid)
    fun forRecovery(operationUid: String) = AdministrativeMutationCapability.create(operationUid)
}

/** Pure proposal-level validator; endpoint existence and DAG checks are repeated against the DB inside TurnTransaction. */
internal object CampaignCausalPlanValidator {
    fun validate(intents: List<CanonicalCausalRelationIntent>) {
        val ids = intents.map { it.relationIntentUid }
        require(ids.distinct().size == ids.size) { "RPGOS-CAUSAL-GRAPH:DUPLICATE_RELATION_INTENT" }
        intents.forEach { intent ->
            require(CausalRelationKinds.expectedClass(intent.relationKindUid) == intent.relationClass) {
                "RPGOS-CAUSAL-GRAPH:RELATION_CLASS_KIND_MISMATCH"
            }
            if (CausalRelationKinds.isAcyclicDependency(intent.relationKindUid)) {
                require(intent.sourceEventUid != intent.targetEventUid) { "RPGOS-CAUSAL-GRAPH:SELF_EDGE_FORBIDDEN" }
            }
            if (intent.relationClass == CausalRelationClass.CAUSAL) {
                val proof = (intent.evidenceEventUids + intent.provenanceEventUids).distinct()
                require(proof.isNotEmpty()) { "RPGOS-CAUSAL-GRAPH:CAUSAL_RELATION_REQUIRES_EXPLICIT_EVIDENCE_OR_PROVENANCE" }
                val endpoints = setOf(intent.sourceEventUid, intent.targetEventUid)
                require(proof.all { it in endpoints }) { "RPGOS-CAUSAL-GRAPH:CAUSAL_PROOF_NOT_BOUND_TO_RELATION_ENDPOINT" }
            }
        }
    }
}

object CampaignMutationBoundary {
    const val NOT_RESOLVED = "RPGOS-MUTATION-ADMISSION:NOT_RESOLVED"
    const val CAMPAIGN_MISMATCH = "RPGOS-MUTATION-ADMISSION:CAMPAIGN_MISMATCH"

    /**
     * Only production admission API. It invokes the canonical PlayerDomainEngine itself; callers
     * cannot hand this boundary a manually constructed Resolved outcome.
     */
    fun <P : PlayerCommandPayload> resolveAndAdmit(
        expectedCampaignUid: String,
        engine: PlayerDomainEngine,
        command: PlayerCommand<P>,
        context: PlayerResolutionContext,
        onResolutionRejected: ((PlayerResolutionRejection)->Unit)? = null
    ): CampaignMutationAdmission {
        require(expectedCampaignUid.isNotBlank())
        if (command.campaignUid != expectedCampaignUid || context.campaignUid != expectedCampaignUid) {
            return CampaignMutationAdmission.Rejected(CAMPAIGN_MISMATCH)
        }
        return when (val resolution = engine.resolve(command, context)) {
            is PlayerResolutionOutcome.Rejected -> {
                onResolutionRejected?.invoke(resolution.rejection)
                CampaignMutationAdmission.Rejected(NOT_RESOLVED)
            }
            is PlayerResolutionOutcome.Resolved -> {
                if (resolution.proposal.campaignUid != expectedCampaignUid) {
                    CampaignMutationAdmission.Rejected(CAMPAIGN_MISMATCH)
                } else {
                    val canonRejection = CanonDivergenceAdmissionValidator.rejectionReason(
                        resolution.proposal,
                        resolution.evidence
                    )
                    if (canonRejection != null) {
                        CampaignMutationAdmission.Rejected(canonRejection)
                    } else {
                        CampaignMutationAdmission.Accepted(
                            CanonicalCampaignMutationProposal(
                                expectedCampaignUid,
                                resolution.proposal,
                                MutationAuthorityClass.GAMEPLAY_AUTHORITATIVE,
                                emptyList(),
                                CANONICAL_PROPOSAL_SEAL
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * Attaches a deterministic typed causal plan to an already admitted proposal. This does not
     * provide a second commit rail: CampaignRepository.commitTurn still consumes exactly one
     * canonical proposal and TurnTransaction remains the sole durable gameplay mutation boundary.
     */
    fun withValidatedCausalPlan(
        proposal: CanonicalCampaignMutationProposal,
        causalRelationIntents: List<CanonicalCausalRelationIntent>
    ): CanonicalCampaignMutationProposal {
        require(proposal.isCanonical()) { "RPGOS-MUTATION-GATE:FORGED_CANONICAL_PROPOSAL" }
        CampaignCausalPlanValidator.validate(causalRelationIntents)
        return CanonicalCampaignMutationProposal(
            proposal.campaignUid,
            proposal.playerChangeSet,
            proposal.authorityClass,
            causalRelationIntents,
            CANONICAL_PROPOSAL_SEAL
        )
    }
}
