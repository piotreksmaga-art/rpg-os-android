package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Test

class Phase30CrossCampaignEventReferenceTest {
    @Test
    fun P30_G_crossCampaignEventTargetIsRejectedByCanonicalResolution() {
        val campaignUid = "C1"
        val actor = CommandActorRef("PLAYER", "P1")
        val command = PlayerCommand(
            commandUid = "CMD-CROSS-EVENT",
            campaignUid = campaignUid,
            actor = actor,
            commandKindUid = PlayerCommandKinds.TRANSFER_FUNDS,
            payload = TransferFundsCommandPayload("A", "B", 5L, "CUR"),
            provenance = CommandProvenance("PHASE30-CROSS-CAMPAIGN-TEST"),
            requestedEffectiveOrder = 10L
        )
        val foreignTarget = DomainRef("NPC", "N-FOREIGN")
        val context = PlayerResolutionContext.createUnboundGeneric(
            campaignUid = campaignUid,
            actor = actor,
            knownReferences = setOf(
                CampaignScopedDomainRef(campaignUid, DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "A")),
                CampaignScopedDomainRef(campaignUid, DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "B")),
                CampaignScopedDomainRef(campaignUid, DomainRef(PlayerResolutionReferenceKinds.CURRENCY, "CUR")),
                CampaignScopedDomainRef("C2", foreignTarget)
            )
        )
        val engine = PlayerDomainEngine(
            PlayerResolutionComponentRegistry.of(listOf(ForeignEventTargetComponent()))
        )

        val admission = CampaignMutationBoundary.resolveAndAdmit(campaignUid, engine, command, context)

        assertEquals(
            CampaignMutationAdmission.Rejected(CampaignMutationBoundary.NOT_RESOLVED),
            admission
        )
    }

    private class ForeignEventTargetComponent : PlayerResolutionComponent<TransferFundsCommandPayload>(
        PlayerCommandKinds.TRANSFER_FUNDS,
        TransferFundsCommandPayload::class,
        "RPGOS-COMPONENT:PHASE30-CROSS-CAMPAIGN-EVENT",
        "1"
    ) {
        override fun resolve(
            command: PlayerCommand<TransferFundsCommandPayload>,
            context: PlayerResolutionContext
        ): PlayerResolutionComponentOutcome {
            val foreignTarget = DomainRef("NPC", "N-FOREIGN")
            val changeUid = "CHANGE-${command.commandUid}-1"
            val change = PlayerDomainChange.create(
                changeUid,
                PlayerChangeKinds.FINANCIAL,
                FinancialChange(
                    command.payload.fromAccountUid,
                    command.payload.toAccountUid,
                    command.payload.amountMinor,
                    command.payload.currencyUid,
                    "RPGOS-FIN-TYPE:TRANSFER"
                )
            )
            val event = PlayerEventIntent.create(
                eventIntentUid = "EVENT-INTENT-${command.commandUid}",
                eventKindUid = PlayerEventIntentKinds.DOMAIN_EFFECT,
                actorRef = null,
                targetRefs = listOf(foreignTarget),
                causalChangeUids = listOf(changeUid),
                payload = DomainEffectEventIntentPayload(
                    subject = foreignTarget,
                    effectKindUid = "RPGOS-EFFECT:TRANSFER"
                )
            )
            return PlayerResolutionComponentOutcome.Resolved(
                PlayerResolutionDraft.create(changes = listOf(change), eventIntents = listOf(event))
            )
        }
    }
}
