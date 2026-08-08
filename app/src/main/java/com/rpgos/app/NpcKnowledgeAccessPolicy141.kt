package com.rpgos.app

/**
 * Builds the only durable campaign-truth view an NPC decision/dialogue layer is allowed to see.
 *
 * The policy is intentionally fail-closed:
 * - a holder always receives only its own active BELIEF records,
 * - objective FACT records require an explicit, time-valid access grant,
 * - NARRATIVE is never knowledge,
 * - a grant never makes another NPC's BELIEF visible.
 *
 * Scene perception and organization systems are expected to create short-lived grants rather than
 * handing the NPC unrestricted repository access.
 */
class NpcKnowledgeAccessPolicy141(
    private val repository: UnifiedCampaignRepository,
    private val campaignUid: EntityUid
) {
    enum class GrantKind {
        OBSERVABLE_FACT,
        ORGANIZATION_FACT
    }

    data class Grant(
        val holderUid: EntityUid,
        val truthUid: EntityUid,
        val subjectUid: EntityUid,
        val predicate: String,
        val kind: GrantKind,
        val grantedByUid: EntityUid? = null,
        val validFromTurn: Long? = null,
        val validUntilTurn: Long? = null
    ) {
        init {
            require(predicate.isNotBlank()) { "Grant predicate nie może być pusty." }
            require(validFromTurn == null || validUntilTurn == null || validUntilTurn >= validFromTurn) {
                "Grant validUntilTurn nie może być wcześniejszy niż validFromTurn."
            }
        }
    }

    enum class DenialReason {
        WRONG_HOLDER,
        NOT_YET_VALID,
        EXPIRED,
        SOURCE_NOT_FOUND,
        SOURCE_NOT_FACT
    }

    data class DeniedGrant(
        val grant: Grant,
        val reason: DenialReason
    )

    data class View(
        val holderUid: EntityUid,
        val atTurnId: Long,
        val beliefs: List<CampaignTruth>,
        val observableFacts: List<CampaignTruth>,
        val organizationFacts: List<CampaignTruth>,
        val deniedGrants: List<DeniedGrant>
    ) {
        val accessibleTruths: List<CampaignTruth> =
            (beliefs + observableFacts + organizationFacts).distinctBy { it.uid }

        private val accessibleIds = accessibleTruths.mapTo(hashSetOf()) { it.uid }

        fun canAccess(truthUid: EntityUid): Boolean = truthUid in accessibleIds

        fun requireAccess(truthUid: EntityUid) {
            require(canAccess(truthUid)) {
                "NPC ${holderUid.value} nie ma dostępu do wiedzy ${truthUid.value} w turze $atTurnId."
            }
        }
    }

    suspend fun buildView(
        holderUid: EntityUid,
        atTurnId: Long? = null,
        grants: List<Grant> = emptyList(),
        beliefLimit: Int = 100
    ): View {
        require(beliefLimit in 1..1_000) { "beliefLimit musi należeć do 1..1000." }
        val turn = atTurnId ?: repository.currentTurnId(campaignUid)

        val beliefs = repository.getBeliefs(
            campaignUid = campaignUid,
            holderUid = holderUid,
            atTurnId = turn,
            limit = beliefLimit
        ).asSequence()
            .filter { it.kind == TruthKind.BELIEF }
            .filter { it.holderUid == holderUid }
            .filter { isTruthActive(it, turn) }
            .distinctBy { it.uid }
            .toList()

        val observable = mutableListOf<CampaignTruth>()
        val organization = mutableListOf<CampaignTruth>()
        val denied = mutableListOf<DeniedGrant>()

        grants.forEach { grant ->
            val denial = validateGrantEnvelope(grant, holderUid, turn)
            if (denial != null) {
                denied += DeniedGrant(grant, denial)
                return@forEach
            }

            val source = repository.getTruth(
                campaignUid = campaignUid,
                subjectUid = grant.subjectUid,
                predicate = grant.predicate,
                atTurnId = turn
            ).firstOrNull { it.uid == grant.truthUid && isTruthActive(it, turn) }

            if (source == null) {
                denied += DeniedGrant(grant, DenialReason.SOURCE_NOT_FOUND)
                return@forEach
            }
            if (source.kind != TruthKind.FACT) {
                denied += DeniedGrant(grant, DenialReason.SOURCE_NOT_FACT)
                return@forEach
            }

            when (grant.kind) {
                GrantKind.OBSERVABLE_FACT -> observable += source
                GrantKind.ORGANIZATION_FACT -> organization += source
            }
        }

        return View(
            holderUid = holderUid,
            atTurnId = turn,
            beliefs = beliefs,
            observableFacts = observable.distinctBy { it.uid },
            organizationFacts = organization.distinctBy { it.uid },
            deniedGrants = denied
        )
    }

    private fun validateGrantEnvelope(
        grant: Grant,
        holderUid: EntityUid,
        turn: Long
    ): DenialReason? = when {
        grant.holderUid != holderUid -> DenialReason.WRONG_HOLDER
        grant.validFromTurn != null && turn < grant.validFromTurn -> DenialReason.NOT_YET_VALID
        grant.validUntilTurn != null && turn > grant.validUntilTurn -> DenialReason.EXPIRED
        else -> null
    }

    private fun isTruthActive(truth: CampaignTruth, turn: Long): Boolean =
        (truth.validFromTurn == null || truth.validFromTurn <= turn) &&
            (truth.validUntilTurn == null || truth.validUntilTurn >= turn)
}
