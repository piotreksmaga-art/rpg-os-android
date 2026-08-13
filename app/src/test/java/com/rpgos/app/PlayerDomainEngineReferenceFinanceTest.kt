package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerDomainEngineReferenceFinanceTest {
    private val actor = CommandActorRef("PLAYER", "P1")
    private val source = DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "ACCOUNT:A")
    private val destination = DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "ACCOUNT:B")
    private val currency = DomainRef(PlayerResolutionReferenceKinds.CURRENCY, "CUR:PLN")

    @Test fun p18RefHotfix01_validKnownFinancialReferencesAccept() {
        assertTrue(resolve(command(), context(setOf(source, destination, currency))) is PlayerResolutionOutcome.Resolved)
    }

    @Test fun p18RefHotfix02_unknownSourceAccountRejectsUnknownReference() {
        assertRejected(context(setOf(destination, currency)), PlayerResolutionRejectionReason.UNKNOWN_REFERENCE, source)
    }

    @Test fun p18RefHotfix03_unknownDestinationAccountRejectsUnknownReference() {
        assertRejected(context(setOf(source, currency)), PlayerResolutionRejectionReason.UNKNOWN_REFERENCE, destination)
    }

    @Test fun p18RefHotfix04_unknownCurrencyRejectsUnknownReference() {
        assertRejected(context(setOf(source, destination)), PlayerResolutionRejectionReason.UNKNOWN_REFERENCE, currency)
    }

    @Test fun p18RefHotfix05_sourceAccountWrongCampaignRejects() {
        assertRejected(context(setOf(destination, currency), setOf(source)), PlayerResolutionRejectionReason.WRONG_CAMPAIGN_REFERENCE, source)
    }

    @Test fun p18RefHotfix06_destinationAccountWrongCampaignRejects() {
        assertRejected(context(setOf(source, currency), setOf(destination)), PlayerResolutionRejectionReason.WRONG_CAMPAIGN_REFERENCE, destination)
    }

    @Test fun p18RefHotfix07_currencyWrongCampaignRejects() {
        assertRejected(context(setOf(source, destination), setOf(currency)), PlayerResolutionRejectionReason.WRONG_CAMPAIGN_REFERENCE, currency)
    }

    @Test fun p18RefHotfix08_commandReferencesValidatedBeforeComponentBody() {
        val engine = PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(ThrowIfInvokedFinanceComponent())))
        val result = engine.resolve(command(), context(setOf(destination, currency)))
        assertTrue(result is PlayerResolutionOutcome.Rejected)
        assertEquals(PlayerResolutionRejectionReason.UNKNOWN_REFERENCE, (result as PlayerResolutionOutcome.Rejected).rejection.reason)
    }

    @Test fun p18RefHotfix09_componentIntroducedDraftFinancialReferenceValidated() {
        val ghost = DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "ACCOUNT:GHOST-DRAFT")
        assertTyped(resolve(command(), context(setOf(source, destination, currency)), FinanceComponent(draftFrom = ghost.uid)), PlayerResolutionRejectionReason.UNKNOWN_REFERENCE, ghost)
    }

    @Test fun p18RefHotfix10_knownCommandDraftSubstitutesUnknownAccountRejects() {
        val ghost = DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "ACCOUNT:GHOST-TO")
        assertTyped(resolve(command(), context(setOf(source, destination, currency)), FinanceComponent(draftTo = ghost.uid)), PlayerResolutionRejectionReason.UNKNOWN_REFERENCE, ghost)
    }

    @Test fun p18RefHotfix11_knownCommandDraftSubstitutesWrongCampaignCurrencyRejects() {
        val ghost = DomainRef(PlayerResolutionReferenceKinds.CURRENCY, "CUR:GHOST")
        val ctx = context(setOf(source, destination, currency), setOf(ghost))
        assertTyped(resolve(command(), ctx, FinanceComponent(draftCurrency = ghost.uid)), PlayerResolutionRejectionReason.WRONG_CAMPAIGN_REFERENCE, ghost)
    }

    @Test fun p18RefHotfix12_sameUidWrongReferenceKindDoesNotSatisfyLookup() {
        val wrongKind = DomainRef(PlayerResolutionReferenceKinds.CURRENCY, source.uid)
        assertTyped(resolve(command(), context(setOf(destination, currency, wrongKind))), PlayerResolutionRejectionReason.UNKNOWN_REFERENCE, source)
    }

    @Test fun p18RefHotfix13_duplicateDraftReferencesRemainDeterministic() {
        val engine = engine(FinanceComponent())
        val ctx = context(setOf(source, destination, currency))
        val first = engine.resolve(command(), ctx)
        val second = engine.resolve(command(), ctx)
        assertEquals(first, second)
        val a = (first as PlayerResolutionOutcome.Resolved).proposal
        val b = (second as PlayerResolutionOutcome.Resolved).proposal
        assertEquals(PlayerChangeSetCodec.fingerprint(a), PlayerChangeSetCodec.fingerprint(b))
    }

    @Test fun p18RefHotfix14_referenceFailureIsTypedNotStructural() {
        val result = resolve(command(), context(setOf(destination, currency)))
        assertTrue(result is PlayerResolutionOutcome.Rejected)
        assertEquals(PlayerResolutionRejectionReason.UNKNOWN_REFERENCE, (result as PlayerResolutionOutcome.Rejected).rejection.reason)
    }

    @Test fun p18RefHotfix16_exactFinancialLedgerTermsStillMatch() {
        val proposal = (resolve(command(), context(setOf(source, destination, currency))) as PlayerResolutionOutcome.Resolved).proposal
        val change = proposal.changes.single().payload as FinancialChange
        val ledger = proposal.ledgerIntents.single().payload as FinancialTransferLedgerIntentPayload
        assertEquals(change.fromAccountUid, ledger.fromAccountUid)
        assertEquals(change.toAccountUid, ledger.toAccountUid)
        assertEquals(change.amountMinor, ledger.amountMinor)
        assertEquals(change.currencyUid, ledger.currencyUid)
        assertEquals(change.transactionTypeUid, ledger.transactionTypeUid)
        PlayerChangeSetValidator.validate(proposal)
    }

    @Test fun p18RefHotfix17_financialCausalUniquenessStillPasses() {
        val proposal = (resolve(command(), context(setOf(source, destination, currency))) as PlayerResolutionOutcome.Resolved).proposal
        assertEquals(listOf(proposal.changes.single().changeUid), proposal.ledgerIntents.single().causalChangeUids)
        PlayerChangeSetValidator.validate(proposal)
    }

    private fun command() = PlayerCommand(
        commandUid = "CMD-REF-FIN", campaignUid = "C1", actor = actor,
        commandKindUid = PlayerCommandKinds.TRANSFER_FUNDS,
        payload = TransferFundsCommandPayload(source.uid, destination.uid, 125, currency.uid),
        provenance = CommandProvenance("TEST")
    )

    private fun context(current: Set<DomainRef>, other: Set<DomainRef> = emptySet()) = PlayerResolutionContext.create(
        campaignUid = "C1", actor = actor,
        knownReferences = current.map { CampaignScopedDomainRef("C1", it) }.toSet() + other.map { CampaignScopedDomainRef("C2", it) }
    )

    private fun resolve(command: PlayerCommand<TransferFundsCommandPayload>, context: PlayerResolutionContext, component: PlayerResolutionComponent<TransferFundsCommandPayload> = FinanceComponent()) =
        engine(component).resolve(command, context)

    private fun engine(component: PlayerResolutionComponent<TransferFundsCommandPayload>) =
        PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(component)))

    private fun assertRejected(context: PlayerResolutionContext, reason: PlayerResolutionRejectionReason, ref: DomainRef) =
        assertTyped(resolve(command(), context), reason, ref)

    private fun assertTyped(result: PlayerResolutionOutcome, reason: PlayerResolutionRejectionReason, ref: DomainRef) {
        assertTrue(result is PlayerResolutionOutcome.Rejected)
        result as PlayerResolutionOutcome.Rejected
        assertEquals(reason, result.rejection.reason)
        assertEquals(listOf(ref), result.rejection.relatedRefs)
    }

    private class FinanceComponent(
        private val draftFrom: String? = null,
        private val draftTo: String? = null,
        private val draftCurrency: String? = null
    ) : PlayerResolutionComponent<TransferFundsCommandPayload>(PlayerCommandKinds.TRANSFER_FUNDS, TransferFundsCommandPayload::class, "RPGOS-COMPONENT:REF-FINANCE", "1") {
        override fun resolve(command: PlayerCommand<TransferFundsCommandPayload>, context: PlayerResolutionContext): PlayerResolutionComponentOutcome {
            val p = command.payload
            val from = draftFrom ?: p.fromAccountUid
            val to = draftTo ?: p.toAccountUid
            val cur = draftCurrency ?: p.currencyUid
            val change = PlayerDomainChange.create("CH-REF-FIN", PlayerChangeKinds.FINANCIAL, FinancialChange(from, to, p.amountMinor, cur, "TRANSFER"))
            val ledger = PlayerLedgerIntent.create("LED-REF-FIN", PlayerLedgerIntentKinds.FINANCIAL_TRANSFER, listOf(change.changeUid), FinancialTransferLedgerIntentPayload(from, to, p.amountMinor, cur, "TRANSFER"))
            return PlayerResolutionComponentOutcome.Resolved(PlayerResolutionDraft.create(changes = listOf(change), ledgerIntents = listOf(ledger)))
        }
    }

    private class ThrowIfInvokedFinanceComponent : PlayerResolutionComponent<TransferFundsCommandPayload>(PlayerCommandKinds.TRANSFER_FUNDS, TransferFundsCommandPayload::class, "RPGOS-COMPONENT:REF-NOT-INVOKED", "1") {
        override fun resolve(command: PlayerCommand<TransferFundsCommandPayload>, context: PlayerResolutionContext): PlayerResolutionComponentOutcome =
            throw AssertionError("command reference rejection must happen before component invocation")
    }
}