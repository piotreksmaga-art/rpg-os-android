package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerChangeSetReleaseBlockerCompatibilityTest {
    @Test fun financialLedgerWithoutCausalRefsRemainsAValidStandaloneProposal() {
        val ledger = PlayerLedgerIntent.create(
            ledgerIntentUid = "LED-STANDALONE",
            ledgerKindUid = PlayerLedgerIntentKinds.FINANCIAL_TRANSFER,
            causalChangeUids = emptyList(),
            payload = FinancialTransferLedgerIntentPayload("A", "B", 1L, "CUR", "TRANSFER")
        )
        val proposal = PlayerChangeSet.create(
            changeSetUid = "CS-STANDALONE-LEDGER",
            campaignUid = "C1",
            sourceCommandUid = "CMD-1",
            actor = CommandActorRef("PLAYER", "P1"),
            changes = emptyList(),
            ledgerIntents = listOf(ledger),
            provenance = ChangeSetProvenance("CMD-1", "RPGOS-RESOLVER:TEST", "1")
        )
        val encoded = PlayerChangeSetCodec.encode(proposal)
        assertEquals(proposal, PlayerChangeSetCodec.decode(encoded))
    }
}
