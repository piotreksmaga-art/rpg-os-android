package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase30EventStoreTest {
    private lateinit var file: File

    @Before fun setUp() { file = File.createTempFile("p30-", ".db").also { it.delete() } }
    @After fun tearDown() { file.delete() }

    @Test fun P30_A_effectEventReceiptCommitAtomically() = db().use { d ->
        GroupATransactionTestFixtures.setupFinance(d)
        val proposal = eventfulProposal("CMD-A")
        val identity = id("CMD-A", "TX-A")
        assertTrue(TurnTransactionBoundary.create(d, identity, proposal).commit() is TurnExecutionResult.Committed)
        assertEquals(95L, FinancialStore(d, "C1").balance("A"))
        assertEquals(1L, eventCount(d, "TX-A"))
        assertEquals(1L, receiptCount(d))
    }

    @Test fun P30_B_failureBeforeEventAppendRollsBackEffectsAndReceipt() = db().use { d ->
        GroupATransactionTestFixtures.setupFinance(d)
        val proposal = eventfulProposal("CMD-B")
        val tx = TurnTransactionBoundary.create(d, id("CMD-B", "TX-B"), proposal,
            TurnFailureInjector { if (it == TurnFailurePoint.BEFORE_EVENT_APPEND) error("fail-before-event") })
        assertTrue(runCatching { tx.commit() }.isFailure)
        assertEquals(100L, FinancialStore(d, "C1").balance("A"))
        assertEquals(0L, eventCount(d, "TX-B"))
        assertEquals(0L, receiptCount(d))
    }

    @Test fun P30_C_failureAfterEventAppendRollsBackEverything() = db().use { d ->
        GroupATransactionTestFixtures.setupFinance(d)
        val proposal = eventfulProposal("CMD-C")
        val tx = TurnTransactionBoundary.create(d, id("CMD-C", "TX-C"), proposal,
            TurnFailureInjector { if (it == TurnFailurePoint.AFTER_EVENT_APPEND) error("fail-after-event") })
        assertTrue(runCatching { tx.commit() }.isFailure)
        assertEquals(100L, FinancialStore(d, "C1").balance("A"))
        assertEquals(0L, eventCount(d, "TX-C"))
        assertEquals(0L, receiptCount(d))
    }

    @Test fun P30_D_domainFailureLeavesNoEvent() = db().use { d ->
        GroupATransactionTestFixtures.setupFinance(d)
        val proposal = eventfulProposal("CMD-D", amountMinor = 500L)
        assertTrue(runCatching { TurnTransactionBoundary.create(d, id("CMD-D", "TX-D"), proposal).commit() }.isFailure)
        assertEquals(100L, FinancialStore(d, "C1").balance("A"))
        assertEquals(0L, eventCount(d, "TX-D"))
        assertEquals(0L, receiptCount(d))
    }

    @Test fun P30_E_lostResponseRetryDoesNotDuplicateEffectEventOrReceipt() = db().use { d ->
        GroupATransactionTestFixtures.setupFinance(d)
        val proposal = eventfulProposal("CMD-E")
        val identity = id("CMD-E", "TX-E")
        assertTrue(TurnTransactionBoundary.create(d, identity, proposal).commit() is TurnExecutionResult.Committed)
        assertTrue(TurnTransactionBoundary.create(d, identity, proposal).commit() is TurnExecutionResult.AlreadyCommitted)
        assertEquals(95L, FinancialStore(d, "C1").balance("A"))
        assertEquals(1L, eventCount(d, "TX-E"))
        assertEquals(1L, receiptCount(d))
    }

    @Test fun P30_F_reusedIdentityChangedEventSemanticsFailsClosed() = db().use { d ->
        GroupATransactionTestFixtures.setupFinance(d)
        val identity = id("CMD-F", "TX-F")
        TurnTransactionBoundary.create(d, identity, eventfulProposal("CMD-F", effectKind = "RPGOS-EFFECT:TRANSFER")).commit()
        val failure = runCatching {
            TurnTransactionBoundary.create(d, identity, eventfulProposal("CMD-F", effectKind = "RPGOS-EFFECT:OTHER")).commit()
        }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(95L, FinancialStore(d, "C1").balance("A"))
        assertEquals(1L, eventCount(d, "TX-F"))
        assertEquals(1L, receiptCount(d))
    }

    @Test fun P30_G_crossCampaignEventProposalRejected() = db().use { d ->
        val proposal = eventfulProposal("CMD-G", campaignUid = "C1")
        assertTrue(runCatching {
            TurnTransactionBoundary.create(d, TurnTransactionIdentity("C2", "TURN-G", "CMD-G", "TX-G"), proposal)
        }.isFailure)
        assertFalse(d.inTransaction())
    }

    @Test fun P30_H_eventStoreIsAppendOnlyAndCannotOverwriteDomainAuthority() = db().use { d ->
        GroupATransactionTestFixtures.setupFinance(d)
        val proposal = eventfulProposal("CMD-H")
        TurnTransactionBoundary.create(d, id("CMD-H", "TX-H"), proposal).commit()
        assertTrue(runCatching { d.execSQL("UPDATE canonical_gameplay_events SET effect_kind_uid='FORGED' WHERE campaign_uid='C1'") }.isFailure)
        assertTrue(runCatching { d.execSQL("DELETE FROM canonical_gameplay_events WHERE campaign_uid='C1'") }.isFailure)
        assertEquals(95L, FinancialStore(d, "C1").balance("A"))
    }

    @Test fun P30_I_activationDoesNotSynthesizeLegacyEvents() = db().use { d ->
        GroupATransactionTestFixtures.setupFinance(d)
        d.execSQL("CREATE TABLE IF NOT EXISTS chapter_events(id INTEGER PRIMARY KEY, campaign_id TEXT, event_type TEXT, description TEXT)")
        d.execSQL("INSERT INTO chapter_events(campaign_id,event_type,description) VALUES('C1','LEGACY','old narrative')")
        val proposal = GroupATransactionTestFixtures.admittedFinancialProposal(commandUid = "CMD-I")
        TurnTransactionBoundary.create(d, id("CMD-I", "TX-I"), proposal)
        assertEquals(0L, d.rawQuery("SELECT COUNT(*) FROM canonical_gameplay_events WHERE campaign_uid='C1'", null).use { it.moveToFirst(); it.getLong(0) })
        assertEquals("UNKNOWN_NOT_RECORDED", d.rawQuery("SELECT legacy_event_history_status FROM campaign_intelligence_activation WHERE campaign_uid='C1'", null).use { it.moveToFirst(); it.getString(0) })
    }

    @Test fun P30_J_reopenPreservesCommittedEvents() {
        val proposal = eventfulProposal("CMD-J")
        val identity = id("CMD-J", "TX-J")
        db().use { d ->
            GroupATransactionTestFixtures.setupFinance(d)
            TurnTransactionBoundary.create(d, identity, proposal).commit()
            assertEquals(1L, eventCount(d, "TX-J"))
        }
        db().use { d ->
            assertTrue(TurnTransactionBoundary.create(d, identity, proposal).commit() is TurnExecutionResult.AlreadyCommitted)
            assertEquals(1L, eventCount(d, "TX-J"))
        }
    }

    @Test fun P30_L_oldWriterCannotMutateActivatedCampaignWithoutWriterHandshake() = db().use { d ->
        GroupATransactionTestFixtures.setupFinance(d)
        val proposal = GroupATransactionTestFixtures.admittedFinancialProposal(commandUid = "CMD-L")
        TurnTransactionBoundary.create(d, id("CMD-L", "TX-L"), proposal)
        d.beginTransaction()
        try {
            d.execSQL("INSERT INTO rpgos_gameplay_mutation_context(campaign_uid,capability_kind) VALUES('C1','TURN')")
            val failure = runCatching {
                d.execSQL("UPDATE financial_ledger_transactions SET provenance='OLD-WRITER' WHERE campaign_id='C1'")
            }.exceptionOrNull()
            assertNotNull(failure)
        } finally {
            d.endTransaction()
        }
    }

    private fun eventfulProposal(
        commandUid: String,
        campaignUid: String = "C1",
        amountMinor: Long = 5L,
        effectKind: String = "RPGOS-EFFECT:TRANSFER"
    ): CanonicalCampaignMutationProposal {
        val actor = CommandActorRef("PLAYER", "P1")
        val command = PlayerCommand(
            commandUid = commandUid,
            campaignUid = campaignUid,
            actor = actor,
            commandKindUid = PlayerCommandKinds.TRANSFER_FUNDS,
            payload = TransferFundsCommandPayload("A", "B", amountMinor, "CUR"),
            provenance = CommandProvenance("PHASE30-TEST"),
            requestedEffectiveOrder = 10L
        )
        val refs = setOf(
            CampaignScopedDomainRef(campaignUid, DomainRef("PLAYER", "P1")),
            CampaignScopedDomainRef(campaignUid, DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "A")),
            CampaignScopedDomainRef(campaignUid, DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "B")),
            CampaignScopedDomainRef(campaignUid, DomainRef(PlayerResolutionReferenceKinds.CURRENCY, "CUR"))
        )
        val context = PlayerResolutionContext.createUnboundGeneric(campaignUid, actor, refs)
        val engine = PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(EventfulFinancialComponent(effectKind))))
        return when (val admission = CampaignMutationBoundary.resolveAndAdmit(campaignUid, engine, command, context)) {
            is CampaignMutationAdmission.Accepted -> admission.proposal
            is CampaignMutationAdmission.Rejected -> error("canonical admission rejected: ${admission.reasonUid}")
        }
    }

    private class EventfulFinancialComponent(private val effectKind: String) : PlayerResolutionComponent<TransferFundsCommandPayload>(
        PlayerCommandKinds.TRANSFER_FUNDS,
        TransferFundsCommandPayload::class,
        "RPGOS-COMPONENT:PHASE30-EVENTFUL-FINANCIAL",
        "1"
    ) {
        override fun resolve(command: PlayerCommand<TransferFundsCommandPayload>, context: PlayerResolutionContext): PlayerResolutionComponentOutcome {
            val changeUid = "CHANGE-${command.commandUid}-1"
            val change = PlayerDomainChange.create(
                changeUid,
                PlayerChangeKinds.FINANCIAL,
                FinancialChange(command.payload.fromAccountUid, command.payload.toAccountUid, command.payload.amountMinor, command.payload.currencyUid, "RPGOS-FIN-TYPE:TRANSFER")
            )
            val subject = DomainRef("PLAYER", "P1")
            val event = PlayerEventIntent.create(
                eventIntentUid = "EVENT-INTENT-${command.commandUid}",
                eventKindUid = PlayerEventIntentKinds.DOMAIN_EFFECT,
                actorRef = subject,
                targetRefs = listOf(subject),
                causalChangeUids = listOf(changeUid),
                payload = DomainEffectEventIntentPayload(effectKind, subject)
            )
            return PlayerResolutionComponentOutcome.Resolved(PlayerResolutionDraft.create(changes = listOf(change), eventIntents = listOf(event)))
        }
    }

    private fun id(command: String, tx: String) = TurnTransactionIdentity("C1", "TURN-$command", command, tx)
    private fun db() = SQLiteDatabase.openOrCreateDatabase(file, null)
    private fun receiptCount(d: SQLiteDatabase) = d.rawQuery("SELECT COUNT(*) FROM turn_transaction_receipts", null).use { it.moveToFirst(); it.getLong(0) }
    private fun eventCount(d: SQLiteDatabase, tx: String) = d.rawQuery("SELECT COUNT(*) FROM canonical_gameplay_events WHERE campaign_uid=? AND transaction_uid=?", arrayOf("C1", tx)).use { it.moveToFirst(); it.getLong(0) }
}
