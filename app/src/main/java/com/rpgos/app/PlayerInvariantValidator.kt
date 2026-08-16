package com.rpgos.app

import java.util.Collections

object DurableRegressionCauseKinds {
    const val INJURY = "RPGOS-REGRESSION-CAUSE:INJURY"
    const val EVOLUTION = "RPGOS-REGRESSION-CAUSE:EVOLUTION"
    const val RESPEC = "RPGOS-REGRESSION-CAUSE:RESPEC"
    const val EXPLICIT_RULE = "RPGOS-REGRESSION-CAUSE:EXPLICIT_RULE"
    val supported: Set<String> = Collections.unmodifiableSet(linkedSetOf(INJURY, EVOLUTION, RESPEC, EXPLICIT_RULE))
}

data class DurableRegressionAuthorization(
    val authorizationUid: String,
    val campaignUid: String,
    val characterUid: String,
    val changeUid: String,
    val targetKindUid: String,
    val targetUid: String,
    val causeKindUid: String,
    val causeUid: String,
    val evidenceUid: String,
    val ruleUid: String,
    val ruleVersion: String
) {
    init {
        require(authorizationUid.isNotBlank() && campaignUid.isNotBlank() && characterUid.isNotBlank())
        require(changeUid.isNotBlank() && targetKindUid.isNotBlank() && targetUid.isNotBlank())
        require(causeKindUid in DurableRegressionCauseKinds.supported)
        require(causeUid.isNotBlank() && evidenceUid.isNotBlank())
        require(ruleUid.isNotBlank() && ruleVersion.isNotBlank())
    }
}

/** Immutable current-state validation snapshot. It is read-only evidence, not a second player state. */
class PlayerInvariantSnapshot private constructor(
    val campaignUid: String,
    authorizations: List<DurableRegressionAuthorization>
) {
    val authorizations: List<DurableRegressionAuthorization> = Collections.unmodifiableList(
        ArrayList(authorizations.sortedBy { it.authorizationUid })
    )

    val fingerprint: String = progressionFingerprint(
        "PLAYER_INVARIANT_SNAPSHOT",
        campaignUid,
        this.authorizations.joinToString(",") {
            progressionFingerprint(
                "DURABLE_REGRESSION_AUTHORIZATION",
                it.authorizationUid, it.campaignUid, it.characterUid, it.changeUid,
                it.targetKindUid, it.targetUid, it.causeKindUid, it.causeUid,
                it.evidenceUid, it.ruleUid, it.ruleVersion
            )
        }
    )

    init {
        require(campaignUid.isNotBlank())
        require(this.authorizations.all { it.campaignUid == campaignUid })
        require(this.authorizations.map { it.authorizationUid }.distinct().size == this.authorizations.size)
        require(this.authorizations.map { it.changeUid }.distinct().size == this.authorizations.size)
    }

    internal fun authorizationFor(changeUid: String): DurableRegressionAuthorization? =
        authorizations.firstOrNull { it.changeUid == changeUid }

    companion object {
        fun create(
            campaignUid: String,
            authorizations: List<DurableRegressionAuthorization> = emptyList()
        ) = PlayerInvariantSnapshot(campaignUid, authorizations)
    }
}

/** Read-only boundary for obtaining one immutable validation snapshot per resolution. */
internal fun interface PlayerInvariantSnapshotResolver {
    fun snapshotFor(campaignUid: String, characterUid: String): PlayerInvariantSnapshot

    companion object {
        fun empty(): PlayerInvariantSnapshotResolver = PlayerInvariantSnapshotResolver { campaignUid, _ ->
            PlayerInvariantSnapshot.create(campaignUid)
        }
    }
}

data class PlayerInvariantViolation(
    val violationUid: String,
    val changeUid: String?,
    val targetKindUid: String?,
    val targetUid: String?,
    val detailUid: String
)

sealed interface PlayerInvariantValidationResult {
    data object Valid : PlayerInvariantValidationResult
    data class Invalid(val violations: List<PlayerInvariantViolation>) : PlayerInvariantValidationResult
}

/**
 * Core-owned, deterministic and read-only persistent invariant validation.
 * It does not evaluate world legality, calculate progression or mutate state.
 */
object PlayerInvariantValidator {
    const val VALIDATOR_UID = "RPGOS-CORE:PLAYER_INVARIANT_VALIDATOR"
    const val VALIDATOR_VERSION = "1"

    fun validate(
        proposal: PlayerChangeSet,
        snapshot: PlayerInvariantSnapshot
    ): PlayerInvariantValidationResult {
        if (proposal.campaignUid != snapshot.campaignUid) {
            return invalid(null, null, null, "INVARIANT_SNAPSHOT_CAMPAIGN_MISMATCH")
        }
        val violations = ArrayList<PlayerInvariantViolation>()
        proposal.changes.forEach { change ->
            val regression = durableProgressionRegression(change) ?: return@forEach
            val authorization = snapshot.authorizationFor(change.changeUid)
            if (authorization == null) {
                violations += violation(change.changeUid, regression.first, regression.second, "UNEXPLAINED_DURABLE_PROGRESSION_REGRESSION")
            } else if (!authorizationMatches(proposal, change, regression, authorization)) {
                violations += violation(change.changeUid, regression.first, regression.second, "INVALID_DURABLE_REGRESSION_AUTHORIZATION")
            }
        }
        return if (violations.isEmpty()) PlayerInvariantValidationResult.Valid
        else PlayerInvariantValidationResult.Invalid(Collections.unmodifiableList(violations))
    }

    private fun durableProgressionRegression(change: PlayerDomainChange): Pair<String, String>? = when (val payload = change.payload) {
        is StatChange -> if (payload.delta.units < 0L) ProgressionTargetKinds.STAT to payload.statUid else null
        is SkillChange -> if (payload.progressDelta.units < 0L) ProgressionTargetKinds.SKILL to payload.skillUid else null
        is TechniqueChange -> if (payload.progressDelta.units < 0L) ProgressionTargetKinds.TECHNIQUE to payload.techniqueUid else null
        else -> null
    }

    private fun authorizationMatches(
        proposal: PlayerChangeSet,
        change: PlayerDomainChange,
        regression: Pair<String, String>,
        authorization: DurableRegressionAuthorization
    ): Boolean {
        val subjectUid = when (val payload = change.payload) {
            is StatChange -> payload.subject.uid
            is SkillChange -> payload.subject.uid
            is TechniqueChange -> payload.subject.uid
            else -> return false
        }
        return authorization.campaignUid == proposal.campaignUid &&
            authorization.characterUid == subjectUid &&
            authorization.changeUid == change.changeUid &&
            authorization.targetKindUid == regression.first &&
            authorization.targetUid == regression.second &&
            change.sourceRuleUid == authorization.ruleUid
    }

    private fun violation(changeUid: String?, kindUid: String?, targetUid: String?, detailUid: String) =
        PlayerInvariantViolation(
            violationUid = "RPGOS-INVARIANT-VIOLATION:" + progressionFingerprint(
                "PLAYER_INVARIANT_VIOLATION", VALIDATOR_UID, VALIDATOR_VERSION,
                changeUid ?: "<NULL>", kindUid ?: "<NULL>", targetUid ?: "<NULL>", detailUid
            ),
            changeUid = changeUid,
            targetKindUid = kindUid,
            targetUid = targetUid,
            detailUid = detailUid
        )

    private fun invalid(changeUid: String?, kindUid: String?, targetUid: String?, detailUid: String) =
        PlayerInvariantValidationResult.Invalid(listOf(violation(changeUid, kindUid, targetUid, detailUid)))
}

/**
 * Canonical Phase-22 post-resolution stage. It runs the existing PlayerDomainEngine exactly once,
 * therefore the accepted COMMAND_PRECHECK / one DRAFT_EFFECT_CHECK sequence is untouched, then
 * validates only an already structurally valid PlayerChangeSet proposal against one immutable snapshot.
 * Phase 26 may later enforce this entry point as the single mutation path; Phase 22 does not.
 */
internal fun PlayerDomainEngine.resolveWithPlayerInvariants(
    command: PlayerCommand<out PlayerCommandPayload>,
    context: PlayerResolutionContext,
    snapshotResolver: PlayerInvariantSnapshotResolver
): PlayerResolutionOutcome {
    val outcome = resolve(command, context)
    if (outcome !is PlayerResolutionOutcome.Resolved) return outcome
    val snapshot = try {
        snapshotResolver.snapshotFor(outcome.proposal.campaignUid, outcome.proposal.actor.actorUid)
    } catch (e: PlayerDomainEngineStructuralException) {
        throw e
    } catch (e: Throwable) {
        throw PlayerDomainEngineStructuralException("PLAYER_INVARIANT_SNAPSHOT_READ_FAILED", e)
    }
    return when (val validation = PlayerInvariantValidator.validate(outcome.proposal, snapshot)) {
        PlayerInvariantValidationResult.Valid -> outcome
        is PlayerInvariantValidationResult.Invalid -> PlayerResolutionOutcome.Rejected(
            PlayerResolutionRejection.create(
                PlayerResolutionRejectionReason.DOMAIN_REJECTED,
                detailUid = validation.violations.first().detailUid
            ),
            outcome.evidence
        )
    }
}
