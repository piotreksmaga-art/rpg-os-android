package com.rpgos.app

import org.json.JSONObject
import java.util.Locale

/**
 * Resolves organization publications only from durable authorization records.
 *
 * The model may reference membership/publication UIDs, but receiver, organization,
 * clearance, source FACT and temporal validity are all resolved from campaign.db.
 */
class OrganizationKnowledgeRuleResolver141(
    private val delegate: GameMasterRuleResolver,
    private val repository: UnifiedCampaignRepository,
    private val campaignUid: EntityUid,
    private val authorizationStore: OrganizationKnowledgeAuthorizationStore141
) : GameMasterRuleResolver {

    override suspend fun resolve(
        request: GameMasterTurnRequest,
        context: GameMasterContext,
        proposal: GameMasterProposal
    ): GameMasterTurnResult {
        require(request.campaignId == campaignUid.value) { "Organization resolver działa dla innej kampanii." }
        require(context.campaignId == campaignUid.value) { "Organization resolver otrzymał kontekst innej kampanii." }

        val organizationActions = proposal.proposedActions.withIndex()
            .filter { normalizeAction(it.value.actionType) == ACTION }
        val delegatedProposal = proposal.copy(
            proposedActions = proposal.proposedActions.filterNot {
                normalizeAction(it.actionType) == ACTION
            }
        )
        val base = delegate.resolve(request, context, delegatedProposal)
        if (organizationActions.isEmpty()) return base

        val transferTurn = repository.currentTurnId(campaignUid) + 1L
        val truthWrites = mutableListOf<TruthWrite>()
        val organizationWrites = mutableListOf<OrganizationKnowledgeWrite141>()

        organizationActions.forEach { indexed ->
            val action = indexed.value
            val params = parseParams(action, indexed.index)
            val membershipUid = EntityUid(required(params, "membership_id", action, indexed.index))
            val publicationUid = EntityUid(required(params, "publication_id", action, indexed.index))

            val membership = authorizationStore.membershipByUid(
                campaignUid = campaignUid,
                membershipUid = membershipUid,
                atTurnId = transferTurn
            ) ?: error("Brak aktywnego membership ${membershipUid.value} w turze $transferTurn.")

            val publication = authorizationStore.publicationByUid(
                campaignUid = campaignUid,
                publicationUid = publicationUid,
                atTurnId = transferTurn
            ) ?: error("Brak aktywnej publication ${publicationUid.value} w turze $transferTurn.")

            action.actorId?.takeIf { it.isNotBlank() }?.let { claimedReceiver ->
                require(claimedReceiver == membership.npcUid.value) {
                    "ORGANIZATION_KNOWLEDGE_PROPAGATE actorId=$claimedReceiver nie odpowiada holderowi membership ${membership.npcUid.value}."
                }
            }
            params.optString("receiver_id").trim().takeIf { it.isNotEmpty() }?.let { claimedReceiver ->
                require(claimedReceiver == membership.npcUid.value) {
                    "ORGANIZATION_KNOWLEDGE_PROPAGATE receiver_id=$claimedReceiver nie odpowiada holderowi membership ${membership.npcUid.value}."
                }
            }

            require(membership.organizationUid == publication.organizationUid) {
                "Membership ${membershipUid.value} i publication ${publicationUid.value} dotyczą różnych organizacji."
            }

            val grantResult = OrganizationKnowledgeGrantResolver141().resolve(
                holderUid = membership.npcUid,
                turnId = transferTurn,
                memberships = listOf(membership),
                publications = listOf(publication)
            )
            require(grantResult.denied.isEmpty() && grantResult.grants.size == 1) {
                val denial = grantResult.denied.firstOrNull()?.reason?.name ?: "UNKNOWN"
                "Organizacja odrzuciła publication ${publicationUid.value} dla ${membership.npcUid.value}: $denial."
            }

            val source = repository.getTruth(
                campaignUid = campaignUid,
                subjectUid = publication.subjectUid,
                predicate = publication.predicate,
                atTurnId = transferTurn
            ).singleOrNull { it.uid == publication.truthUid }
                ?: error("Nie znaleziono opublikowanego source FACT ${publication.truthUid.value} w turze $transferTurn.")
            require(source.kind == TruthKind.FACT) {
                "Organization publication ${publicationUid.value} wskazuje ${source.kind}, a wymagany jest FACT."
            }

            val propagated = NpcKnowledgePropagation141().propagate(
                KnowledgePropagationRequest141(
                    receiverUid = membership.npcUid,
                    sourceTruth = source,
                    channel = KnowledgeChannel141.ORGANIZATION,
                    turnId = transferTurn,
                    sourceNpcUid = null,
                    confidenceMultiplier = 1.0
                )
            )
            val truthKey = "org-knowledge-${indexed.index + 1}"
            truthWrites += TruthWrite(
                kind = TruthKind.BELIEF,
                subjectId = propagated.subjectUid?.value,
                predicate = propagated.predicate,
                value = propagated.value,
                holderId = propagated.holderUid?.value,
                confidence = propagated.provenance.confidence,
                sourceType = propagated.provenance.type,
                sourceId = propagated.provenance.sourceUid?.value,
                validFromTurn = propagated.validFromTurn,
                validUntilTurn = propagated.validUntilTurn,
                knowledgeChannel = KnowledgeChannel141.ORGANIZATION,
                sourceNpcId = null,
                truthKey = truthKey
            )
            organizationWrites += OrganizationKnowledgeWrite141(
                organizationId = membership.organizationUid.value,
                membershipId = membership.membershipUid.value,
                publicationId = publication.publicationUid.value,
                sourceTruth = TruthRef141(durableUid = source.uid.value),
                receiverId = membership.npcUid.value,
                resultingBelief = TruthRef141(truthKey = truthKey),
                confidence = propagated.provenance.confidence
            )
        }

        return base.copy(
            truthWrites = base.truthWrites + truthWrites,
            npcKnowledgeWrites = base.npcKnowledgeWrites.copy(
                organizationTransmissions =
                    base.npcKnowledgeWrites.organizationTransmissions + organizationWrites
            )
        )
    }

    private fun parseParams(action: ProposedWorldAction, index: Int): JSONObject =
        runCatching { JSONObject(action.parametersJson) }.getOrElse {
            error("Akcja #${index + 1} ${action.actionType} ma niepoprawny parametersJson: ${it.message}")
        }

    private fun required(
        params: JSONObject,
        key: String,
        action: ProposedWorldAction,
        index: Int
    ): String = params.optString(key).trim().takeIf { it.isNotEmpty() }
        ?: error("Akcja #${index + 1} ${action.actionType} wymaga '$key'.")

    private fun normalizeAction(raw: String): String =
        raw.trim().uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]+"), "_").trim('_')

    private companion object {
        const val ACTION = "ORGANIZATION_KNOWLEDGE_PROPAGATE"
    }
}
