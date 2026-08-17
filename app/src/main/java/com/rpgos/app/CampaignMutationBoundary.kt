package com.rpgos.app

/**
 * Phase 26 authority classification for persisted writes.
 *
 * This is a capability classification, not a second rules engine. Gameplay rules remain owned by
 * PlayerDomainEngine / the relevant domain engines. This boundary only admits already-resolved
 * proposals into the commit program.
 */
enum class MutationAuthorityClass {
    GAMEPLAY_AUTHORITATIVE,
    ADMINISTRATIVE_MIGRATION_INSTALL_RECOVERY,
    DERIVED_CACHE_PRESENTATION,
    APPEND_ONLY_COMMIT_EVIDENCE
}

sealed interface CampaignMutationAdmission {
    data class Accepted internal constructor(val proposal: CanonicalCampaignMutationProposal) : CampaignMutationAdmission
    data class Rejected(val reasonUid: String) : CampaignMutationAdmission
}

/**
 * Opaque envelope proving that the proposal crossed the canonical Phase-26 admission boundary.
 * Construction is intentionally internal so callers cannot present an arbitrary change set as an
 * admitted gameplay mutation.
 */
class CanonicalCampaignMutationProposal internal constructor(
    val campaignUid: String,
    val playerChangeSet: PlayerChangeSet,
    val authorityClass: MutationAuthorityClass = MutationAuthorityClass.GAMEPLAY_AUTHORITATIVE
)

/** Explicitly non-gameplay capability used by migration/install/recovery code paths. */
class AdministrativeMutationCapability internal constructor(
    val operationUid: String,
    val authorityClass: MutationAuthorityClass = MutationAuthorityClass.ADMINISTRATIVE_MIGRATION_INSTALL_RECOVERY
) {
    init { require(operationUid.isNotBlank()) }
}

internal object AdministrativeMutationCapabilities {
    fun forMigration(operationUid: String): AdministrativeMutationCapability =
        AdministrativeMutationCapability(operationUid)

    fun forInstall(operationUid: String): AdministrativeMutationCapability =
        AdministrativeMutationCapability(operationUid)

    fun forRecovery(operationUid: String): AdministrativeMutationCapability =
        AdministrativeMutationCapability(operationUid)
}

/**
 * Canonical Phase-26 gameplay mutation admission boundary.
 *
 * Only a successful canonical PlayerDomainEngine outcome can be admitted. Rejected/unresolved AI
 * output and raw StatePatch payloads never become gameplay mutation capability here.
 */
object CampaignMutationBoundary {
    const val NOT_RESOLVED = "RPGOS-MUTATION-ADMISSION:NOT_RESOLVED"
    const val CAMPAIGN_MISMATCH = "RPGOS-MUTATION-ADMISSION:CAMPAIGN_MISMATCH"

    fun admitPlayerProposal(
        expectedCampaignUid: String,
        resolution: PlayerResolutionOutcome
    ): CampaignMutationAdmission {
        require(expectedCampaignUid.isNotBlank())
        val resolved = resolution as? PlayerResolutionOutcome.Resolved
            ?: return CampaignMutationAdmission.Rejected(NOT_RESOLVED)
        val proposal = resolved.proposal
        if (proposal.campaignUid != expectedCampaignUid) {
            return CampaignMutationAdmission.Rejected(CAMPAIGN_MISMATCH)
        }
        // PlayerChangeSet instances are structurally validated by their factory. Phase-22 invariant
        // validation is mandatory inside PlayerDomainEngine.resolve(), before Resolved is returned.
        return CampaignMutationAdmission.Accepted(
            CanonicalCampaignMutationProposal(expectedCampaignUid, proposal)
        )
    }
}
