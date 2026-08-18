package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Work023CanonicalForgeabilitySemanticTest {

    @Test
    fun fakeCanonicalProposalSealFailsClosedWithCanonicalForgeryError() {
        val real = admittedProposal("CMD-FORGE-PROPOSAL")
        val failure = runCatching {
            CanonicalCampaignMutationProposal(
                campaignUid = real.campaignUid,
                playerChangeSet = real.playerChangeSet,
                authorityClass = MutationAuthorityClass.GAMEPLAY_AUTHORITATIVE,
                causalRelationIntents = emptyList(),
                seal = Any()
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message.orEmpty().contains("RPGOS-MUTATION-GATE:FORGED_CANONICAL_PROPOSAL"))
    }

    @Test
    fun fakeTurnTransactionCapabilityCannotAuthoritativelyCommit() {
        val file = File.createTempFile("work023-forge-", ".db").also { it.delete() }
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                GroupATransactionTestFixtures.setupFinance(db)
                TurnTransactionReceiptSchema.ensureReady(db)
                GameplayMutationDatabaseGuards.ensureInstalled(db)
                val proposal = admittedProposal("CMD-FORGE-TX")

                val failure = runCatching {
                    TurnTransaction(
                        db = db,
                        identity = TurnTransactionIdentity("C1", "TURN-FORGE-TX", "CMD-FORGE-TX", "TX-FORGE-TX"),
                        proposal = proposal,
                        failureInjector = TurnFailureInjector.NONE,
                        seal = Any()
                    )
                }.exceptionOrNull()

                assertTrue(failure is IllegalArgumentException)
                assertTrue(failure!!.message.orEmpty().contains("RPGOS-TURN-TRANSACTION:FORGED_CAPABILITY"))
                assertTrue(FinancialStore(db, "C1").balance("A") == 100L)
                assertTrue(db.rawQuery("SELECT COUNT(*) FROM turn_transaction_receipts", null).use { c -> c.moveToFirst(); c.getLong(0) } == 0L)
            }
        } finally {
            file.delete()
        }
    }

    private fun admittedProposal(commandUid: String): CanonicalCampaignMutationProposal {
        val actor = CommandActorRef("PLAYER", "P1")
        val command = PlayerCommand(
            commandUid = commandUid,
            campaignUid = "C1",
            actor = actor,
            commandKindUid = PlayerCommandKinds.TRANSFER_FUNDS,
            payload = TransferFundsCommandPayload("A", "B", 5, "CUR"),
            provenance = CommandProvenance("WORK-023-FORGE"),
            requestedEffectiveOrder = 10
        )
        val refs = setOf(
            CampaignScopedDomainRef("C1", DomainRef("PLAYER", "P1")),
            CampaignScopedDomainRef("C1", DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "A")),
            CampaignScopedDomainRef("C1", DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "B")),
            CampaignScopedDomainRef("C1", DomainRef(PlayerResolutionReferenceKinds.CURRENCY, "CUR"))
        )
        val context = PlayerResolutionContext.createUnboundGeneric("C1", actor, refs)
        val engine = PlayerDomainEngine(
            PlayerResolutionComponentRegistry.of(listOf(FinancialComponent()))
        )
        return when (val admission = CampaignMutationBoundary.resolveAndAdmit("C1", engine, command, context)) {
            is CampaignMutationAdmission.Accepted -> admission.proposal
            is CampaignMutationAdmission.Rejected -> error("forgeability setup admission rejected: ${admission.reasonUid}")
        }
    }

    private class FinancialComponent : PlayerResolutionComponent<TransferFundsCommandPayload>(
        PlayerCommandKinds.TRANSFER_FUNDS,
        TransferFundsCommandPayload::class,
        "RPGOS-COMPONENT:WORK-023-FORGE",
        "1"
    ) {
        override fun resolve(
            command: PlayerCommand<TransferFundsCommandPayload>,
            context: PlayerResolutionContext
        ): PlayerResolutionComponentOutcome = PlayerResolutionComponentOutcome.Resolved(
            PlayerResolutionDraft.create(
                changes = listOf(
                    PlayerDomainChange.create(
                        "CH-FORGE-FIN",
                        PlayerChangeKinds.FINANCIAL,
                        FinancialChange("A", "B", 5, "CUR", "RPGOS-FIN-TYPE:TRANSFER")
                    )
                )
            )
        )
    }
}
