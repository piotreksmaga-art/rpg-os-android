package com.rpgos.app

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
    private val seal: Any
) {
    init {
        require(seal === CANONICAL_PROPOSAL_SEAL) { "RPGOS-MUTATION-GATE:FORGED_CANONICAL_PROPOSAL" }
        require(authorityClass == MutationAuthorityClass.GAMEPLAY_AUTHORITATIVE)
        require(campaignUid == playerChangeSet.campaignUid)
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
        context: PlayerResolutionContext
    ): CampaignMutationAdmission {
        require(expectedCampaignUid.isNotBlank())
        if (command.campaignUid != expectedCampaignUid || context.campaignUid != expectedCampaignUid) {
            return CampaignMutationAdmission.Rejected(CAMPAIGN_MISMATCH)
        }
        return when (val resolution = engine.resolve(command, context)) {
            is PlayerResolutionOutcome.Rejected -> CampaignMutationAdmission.Rejected(NOT_RESOLVED)
            is PlayerResolutionOutcome.Resolved -> {
                if (resolution.proposal.campaignUid != expectedCampaignUid) {
                    CampaignMutationAdmission.Rejected(CAMPAIGN_MISMATCH)
                } else {
                    CampaignMutationAdmission.Accepted(
                        CanonicalCampaignMutationProposal(
                            expectedCampaignUid,
                            resolution.proposal,
                            MutationAuthorityClass.GAMEPLAY_AUTHORITATIVE,
                            CANONICAL_PROPOSAL_SEAL
                        )
                    )
                }
            }
        }
    }
}
