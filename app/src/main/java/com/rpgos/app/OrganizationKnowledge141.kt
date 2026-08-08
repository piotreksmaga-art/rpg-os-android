package com.rpgos.app

import java.util.UUID

/** Time-bounded authoritative membership used to decide whether an NPC may receive organization knowledge. */
data class OrganizationMembership141(
    val membershipUid: EntityUid,
    val npcUid: EntityUid,
    val organizationUid: EntityUid,
    val clearance: Int = 0,
    val validFromTurn: Long,
    val validUntilTurn: Long? = null
) {
    init {
        require(clearance >= 0) { "clearance nie może być ujemny." }
        require(validFromTurn >= 0L) { "validFromTurn nie może być ujemny." }
        require(validUntilTurn == null || validUntilTurn >= validFromTurn) {
            "validUntilTurn nie może być wcześniejszy niż validFromTurn."
        }
    }

    fun isActiveAt(turnId: Long): Boolean =
        turnId >= validFromTurn && (validUntilTurn == null || turnId <= validUntilTurn)
}

/** A FACT deliberately published to members of one organization. */
data class OrganizationFactPublication141(
    val publicationUid: EntityUid,
    val organizationUid: EntityUid,
    val truthUid: EntityUid,
    val subjectUid: EntityUid,
    val predicate: String,
    val minimumClearance: Int = 0,
    val validFromTurn: Long,
    val validUntilTurn: Long? = null
) {
    init {
        require(predicate.isNotBlank()) { "predicate publikacji nie może być pusty." }
        require(minimumClearance >= 0) { "minimumClearance nie może być ujemny." }
        require(validFromTurn >= 0L) { "validFromTurn nie może być ujemny." }
        require(validUntilTurn == null || validUntilTurn >= validFromTurn) {
            "validUntilTurn nie może być wcześniejszy niż validFromTurn."
        }
    }

    fun isActiveAt(turnId: Long): Boolean =
        turnId >= validFromTurn && (validUntilTurn == null || turnId <= validUntilTurn)
}

class OrganizationKnowledgeGrantResolver141 {
    enum class DenialReason {
        WRONG_ORGANIZATION,
        MEMBERSHIP_NOT_ACTIVE,
        PUBLICATION_NOT_ACTIVE,
        INSUFFICIENT_CLEARANCE
    }

    data class DeniedPublication(
        val publication: OrganizationFactPublication141,
        val reason: DenialReason
    )

    data class Result(
        val grants: List<NpcKnowledgeAccessPolicy141.Grant>,
        val denied: List<DeniedPublication>
    )

    fun resolve(
        holderUid: EntityUid,
        turnId: Long,
        memberships: List<OrganizationMembership141>,
        publications: List<OrganizationFactPublication141>
    ): Result {
        require(turnId >= 0L) { "turnId nie może być ujemny." }
        val activeMemberships = memberships
            .asSequence()
            .filter { it.npcUid == holderUid && it.isActiveAt(turnId) }
            .groupBy { it.organizationUid }
            .mapValues { (_, values) -> values.maxOf { it.clearance } }

        val grants = mutableListOf<NpcKnowledgeAccessPolicy141.Grant>()
        val denied = mutableListOf<DeniedPublication>()

        publications.distinctBy { it.publicationUid }.forEach { publication ->
            val clearance = activeMemberships[publication.organizationUid]
            val reason = when {
                memberships.none { it.npcUid == holderUid && it.organizationUid == publication.organizationUid } ->
                    DenialReason.WRONG_ORGANIZATION
                clearance == null -> DenialReason.MEMBERSHIP_NOT_ACTIVE
                !publication.isActiveAt(turnId) -> DenialReason.PUBLICATION_NOT_ACTIVE
                clearance < publication.minimumClearance -> DenialReason.INSUFFICIENT_CLEARANCE
                else -> null
            }

            if (reason != null) {
                denied += DeniedPublication(publication, reason)
            } else {
                grants += NpcKnowledgeAccessPolicy141.Grant(
                    holderUid = holderUid,
                    truthUid = publication.truthUid,
                    subjectUid = publication.subjectUid,
                    predicate = publication.predicate,
                    kind = NpcKnowledgeAccessPolicy141.GrantKind.ORGANIZATION_FACT,
                    grantedByUid = publication.organizationUid,
                    validFromTurn = turnId,
                    validUntilTurn = turnId
                )
            }
        }

        return Result(grants = grants, denied = denied)
    }
}

data class OrganizationKnowledgeTransmission141(
    val transmissionUid: EntityUid,
    val campaignUid: EntityUid,
    val organizationUid: EntityUid,
    val membershipUid: EntityUid,
    val publicationUid: EntityUid,
    val sourceTruthUid: EntityUid,
    val receiverUid: EntityUid,
    val resultingBeliefUid: EntityUid,
    val turnId: Long,
    val confidence: Double
) {
    init {
        require(turnId >= 0L) { "turnId nie może być ujemny." }
        require(confidence in 0.0..1.0) { "confidence musi być w zakresie 0..1." }
    }
}

interface OrganizationKnowledgeStore141 {
    suspend fun appendOrganizationKnowledge(record: OrganizationKnowledgeTransmission141)
}

/**
 * Converts an already-authorized ORGANIZATION_FACT into durable holder-scoped knowledge.
 * The source FACT remains immutable. The dedicated organization ledger preserves membership
 * and publication provenance without pretending that the organization itself is an NPC sender.
 */
class OrganizationBeliefPromoter141(
    private val repository: UnifiedCampaignRepository,
    private val campaignUid: EntityUid,
    private val organizationStore: OrganizationKnowledgeStore141,
    private val beliefUidFactory: () -> EntityUid = { EntityUid("BELIEF-${UUID.randomUUID()}") },
    private val transmissionUidFactory: () -> EntityUid = { EntityUid("ORGKNOW-${UUID.randomUUID()}") }
) {
    suspend fun promote(
        holderUid: EntityUid,
        turnId: Long,
        membership: OrganizationMembership141,
        publication: OrganizationFactPublication141,
        grant: NpcKnowledgeAccessPolicy141.Grant,
        confidenceMultiplier: Double = 0.90
    ): CampaignTruth? {
        require(turnId >= 0L) { "turnId nie może być ujemny." }
        require(confidenceMultiplier in 0.0..1.0) { "confidenceMultiplier musi być w zakresie 0..1." }
        require(membership.npcUid == holderUid) { "Membership należy do innego NPC." }
        require(membership.organizationUid == publication.organizationUid) { "Membership i publikacja dotyczą różnych organizacji." }
        require(membership.isActiveAt(turnId)) { "Membership nie jest aktywne w tej turze." }
        require(publication.isActiveAt(turnId)) { "Publikacja nie jest aktywna w tej turze." }
        require(membership.clearance >= publication.minimumClearance) { "NPC nie ma wymaganego clearance." }
        require(grant.kind == NpcKnowledgeAccessPolicy141.GrantKind.ORGANIZATION_FACT) {
            "Promocja organizacyjna wymaga ORGANIZATION_FACT grant."
        }
        require(grant.holderUid == holderUid && grant.grantedByUid == publication.organizationUid) {
            "Grant nie został wydany temu holderowi przez tę organizację."
        }
        require(grant.truthUid == publication.truthUid && grant.subjectUid == publication.subjectUid &&
            grant.predicate == publication.predicate) { "Grant nie odpowiada publikacji organizacyjnej." }
        require(grant.validFromTurn == null || turnId >= grant.validFromTurn) { "Grant nie jest jeszcze aktywny." }
        require(grant.validUntilTurn == null || turnId <= grant.validUntilTurn) { "Grant wygasł." }

        val source = repository.getTruth(
            campaignUid = campaignUid,
            subjectUid = publication.subjectUid,
            predicate = publication.predicate,
            atTurnId = turnId
        ).firstOrNull { it.uid == publication.truthUid }
            ?: error("Nie znaleziono źródłowego FACT ${publication.truthUid.value}.")
        require(source.kind == TruthKind.FACT) { "Organizacja może publikować tylko FACT jako źródło tej ścieżki." }

        val existing = repository.getBeliefs(
            campaignUid = campaignUid,
            holderUid = holderUid,
            subjectUid = source.subjectUid,
            atTurnId = turnId,
            limit = 200
        ).firstOrNull {
            it.predicate == source.predicate && it.value == source.value &&
                (it.validFromTurn == null || it.validFromTurn <= turnId) &&
                (it.validUntilTurn == null || it.validUntilTurn >= turnId)
        }
        if (existing != null) return null

        val confidence = (source.provenance.confidence * confidenceMultiplier).coerceIn(0.0, 1.0)
        val belief = CampaignTruth(
            uid = beliefUidFactory(),
            kind = TruthKind.BELIEF,
            subjectUid = source.subjectUid,
            predicate = source.predicate,
            value = source.value,
            holderUid = holderUid,
            validFromTurn = turnId,
            validUntilTurn = source.validUntilTurn,
            provenance = ProvenanceRecord(
                type = ProvenanceType.NPC_REPORT,
                sourceUid = source.uid,
                turnId = turnId,
                confidence = confidence,
                canonStatus = source.provenance.canonStatus,
                verified = false
            )
        )

        repository.inTransaction {
            writeTruth(belief)
            organizationStore.appendOrganizationKnowledge(
                OrganizationKnowledgeTransmission141(
                    transmissionUid = transmissionUidFactory(),
                    campaignUid = campaignUid,
                    organizationUid = publication.organizationUid,
                    membershipUid = membership.membershipUid,
                    publicationUid = publication.publicationUid,
                    sourceTruthUid = source.uid,
                    receiverUid = holderUid,
                    resultingBeliefUid = belief.uid,
                    turnId = turnId,
                    confidence = confidence
                )
            )
        }
        return belief
    }
}
