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
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase31CausalGraphTest {
    private lateinit var file: File

    @Before fun setUp() { file = File.createTempFile("p31-", ".db").also { it.delete() } }
    @After fun tearDown() { file.delete() }

    @Test fun P31_A_emptyPlanIsLegalAndSameTransactionDoesNotImplyCause() = db().use { d ->
        GroupATransactionTestFixtures.setupFinance(d)
        val proposal = eventfulProposal("CMD-A")
        val identity = id("CMD-A", "TX-A")
        assertTrue(TurnTransactionBoundary.create(d, identity, proposal).commit() is TurnExecutionResult.Committed)
        assertEquals(95L, FinancialStore(d, "C1").balance("A"))
        assertEquals(2L, eventCount(d, "C1", "TX-A"))
        assertEquals(0L, causalCount(d, "C1", "TX-A"))
        assertEquals(1L, receiptCount(d, "C1"))
    }

    @Test fun P31_B_typedRelationClassesRemainDistinctAndExplicitCauseIsAccepted() = db().use { d ->
        GroupATransactionTestFixtures.setupFinance(d)
        val identity = id("CMD-B", "TX-B")
        val proposal = eventfulProposal("CMD-B")
        val endpoints = endpoints(identity)
        val relations = listOf(
            rel("R1", CausalRelationClass.CAUSAL, CausalRelationKinds.CAUSES, endpoints.first, endpoints.second, evidence = listOf(endpoints.first)),
            rel("R2", CausalRelationClass.PROVENANCE, CausalRelationKinds.PROVENANCE_OF, endpoints.first, endpoints.second),
            rel("R3", CausalRelationClass.EVIDENCE, CausalRelationKinds.EVIDENCE_FOR, endpoints.first, endpoints.second),
            rel("R4", CausalRelationClass.TEMPORAL, CausalRelationKinds.BEFORE, endpoints.first, endpoints.second),
            rel("R5", CausalRelationClass.NARRATIVE, CausalRelationKinds.NARRATIVE_ASSOCIATION, endpoints.first, endpoints.second),
            rel("R6", CausalRelationClass.DERIVED, CausalRelationKinds.DERIVED_FROM, endpoints.first, endpoints.second),
            rel("R7", CausalRelationClass.RETRIEVAL, CausalRelationKinds.RETRIEVED_WITH, endpoints.first, endpoints.second)
        )
        TurnTransactionBoundary.create(d, identity, proposal, causalRelationIntents = relations).commit()
        assertEquals(7L, causalCount(d, "C1", "TX-B"))
        val stored = storedClasses(d, "C1", "TX-B")
        assertEquals(setOf("CAUSAL", "PROVENANCE", "EVIDENCE", "TEMPORAL", "NARRATIVE", "DERIVED", "RETRIEVAL"), stored)
        assertEquals(1L, relationKindCount(d, "C1", "TX-B", CausalRelationKinds.CAUSES))
        assertEquals(1L, relationKindCount(d, "C1", "TX-B", CausalRelationKinds.BEFORE))
        assertEquals(1L, relationKindCount(d, "C1", "TX-B", CausalRelationKinds.NARRATIVE_ASSOCIATION))
        assertEquals(1L, relationKindCount(d, "C1", "TX-B", CausalRelationKinds.RETRIEVED_WITH))
    }

    @Test fun P31_C_strongCauseRequiresEvidenceAndClassKindMismatchFailsBeforeCommit() = db().use { d ->
        GroupATransactionTestFixtures.setupFinance(d)
        val identity = id("CMD-C", "TX-C")
        val proposal = eventfulProposal("CMD-C")
        val e = endpoints(identity)
        val noEvidence = rel("R-C1", CausalRelationClass.CAUSAL, CausalRelationKinds.CAUSES, e.first, e.second)
        assertTrue(runCatching { TurnTransactionBoundary.create(d, identity, proposal, causalRelationIntents = listOf(noEvidence)).commit() }.isFailure)
        assertEquals(100L, FinancialStore(d, "C1").balance("A"))
        assertEquals(0L, eventCount(d, "C1", "TX-C"))
        assertEquals(0L, causalCount(d, "C1", "TX-C"))
        assertEquals(0L, receiptCount(d, "C1"))

        val identity2 = id("CMD-C2", "TX-C2")
        val p2 = eventfulProposal("CMD-C2")
        val e2 = endpoints(identity2)
        val forgedPromotion = rel("R-C2", CausalRelationClass.CAUSAL, CausalRelationKinds.BEFORE, e2.first, e2.second, evidence = listOf(e2.first))
        assertTrue(runCatching { TurnTransactionBoundary.create(d, identity2, p2, causalRelationIntents = listOf(forgedPromotion)).commit() }.isFailure)
        assertEquals(100L, FinancialStore(d, "C1").balance("A"))
        assertEquals(0L, eventCount(d, "C1", "TX-C2"))
        assertEquals(0L, causalCount(d, "C1", "TX-C2"))
        assertEquals(0L, receiptCount(d, "C1"))
    }

    @Test fun P31_D_rollbackBeforeCausalAppendRemovesDomainEventsEdgesReceipt() = db().use { d ->
        GroupATransactionTestFixtures.setupFinance(d)
        val identity = id("CMD-D", "TX-D")
        val proposal = eventfulProposal("CMD-D")
        val relations = temporalPlan(identity)
        val tx = TurnTransactionBoundary.create(
            d, identity, proposal,
            failureInjector = TurnFailureInjector { if (it == TurnFailurePoint.BEFORE_CAUSAL_APPEND) error("stop-before-causal") },
            causalRelationIntents = relations
        )
        assertTrue(runCatching { tx.commit() }.isFailure)
        assertZeroCommitted(d, "C1", "TX-D", 100L)
    }

    @Test fun P31_E_rollbackAfterCausalAppendRemovesDomainEventsEdgesReceipt() = db().use { d ->
        GroupATransactionTestFixtures.setupFinance(d)
        val identity = id("CMD-E", "TX-E")
        val proposal = eventfulProposal("CMD-E")
        val tx = TurnTransactionBoundary.create(
            d, identity, proposal,
            failureInjector = TurnFailureInjector { if (it == TurnFailurePoint.AFTER_CAUSAL_APPEND) error("stop-after-causal") },
            causalRelationIntents = temporalPlan(identity)
        )
        assertTrue(runCatching { tx.commit() }.isFailure)
        assertZeroCommitted(d, "C1", "TX-E", 100L)
    }

    @Test fun P31_F_retryDoesNotDuplicateAndRequiredPlanCannotDisappear() = db().use { d ->
        GroupATransactionTestFixtures.setupFinance(d)
        val identity = id("CMD-F", "TX-F")
        val proposal = eventfulProposal("CMD-F")
        val relations = temporalPlan(identity)
        assertTrue(TurnTransactionBoundary.create(d, identity, proposal, causalRelationIntents = relations).commit() is TurnExecutionResult.Committed)
        assertTrue(TurnTransactionBoundary.create(d, identity, proposal, causalRelationIntents = relations).commit() is TurnExecutionResult.AlreadyCommitted)
        assertEquals(95L, FinancialStore(d, "C1").balance("A"))
        assertEquals(2L, eventCount(d, "C1", "TX-F"))
        assertEquals(1L, causalCount(d, "C1", "TX-F"))
        assertEquals(1L, receiptCount(d, "C1"))

        val missingPlanFailure = runCatching {
            TurnTransactionBoundary.create(d, identity, proposal, causalRelationIntents = emptyList()).commit()
        }.exceptionOrNull()
        assertNotNull(missingPlanFailure)
        assertEquals(1L, causalCount(d, "C1", "TX-F"))
        assertEquals(1L, receiptCount(d, "C1"))
    }

    @Test fun P31_G_changedCausalSemanticsUnderSameIdentityFailsClosed() = db().use { d ->
        GroupATransactionTestFixtures.setupFinance(d)
        val identity = id("CMD-G", "TX-G")
        val proposal = eventfulProposal("CMD-G")
        val e = endpoints(identity)
        val temporal = listOf(rel("R-G", CausalRelationClass.TEMPORAL, CausalRelationKinds.BEFORE, e.first, e.second))
        val narrative = listOf(rel("R-G", CausalRelationClass.NARRATIVE, CausalRelationKinds.NARRATIVE_ASSOCIATION, e.first, e.second))
        TurnTransactionBoundary.create(d, identity, proposal, causalRelationIntents = temporal).commit()
        assertTrue(runCatching { TurnTransactionBoundary.create(d, identity, proposal, causalRelationIntents = narrative).commit() }.isFailure)
        assertEquals(95L, FinancialStore(d, "C1").balance("A"))
        assertEquals(1L, causalCount(d, "C1", "TX-G"))
        assertEquals(CausalRelationKinds.BEFORE, onlyRelationKind(d, "C1", "TX-G"))
        assertEquals(1L, receiptCount(d, "C1"))
    }

    @Test fun P31_H_canonicalEquivalentOrderingHasSameTransactionSemantics() = db().use { d ->
        GroupATransactionTestFixtures.setupFinance(d)
        val identity = id("CMD-H", "TX-H")
        val proposal = eventfulProposal("CMD-H")
        val e = endpoints(identity)
        val a = rel("A", CausalRelationClass.PROVENANCE, CausalRelationKinds.PROVENANCE_OF, e.first, e.second, provenance = listOf(e.second, e.first))
        val b = rel("B", CausalRelationClass.EVIDENCE, CausalRelationKinds.EVIDENCE_FOR, e.second, e.first, evidence = listOf(e.second, e.first))
        assertTrue(TurnTransactionBoundary.create(d, identity, proposal, causalRelationIntents = listOf(a, b)).commit() is TurnExecutionResult.Committed)
        val aEquivalent = a.copy(provenanceEventUids = listOf(e.first, e.second))
        val bEquivalent = b.copy(evidenceEventUids = listOf(e.first, e.second))
        assertTrue(TurnTransactionBoundary.create(d, identity, proposal, causalRelationIntents = listOf(bEquivalent, aEquivalent)).commit() is TurnExecutionResult.AlreadyCommitted)
        assertEquals(2L, causalCount(d, "C1", "TX-H"))
        assertEquals(1L, receiptCount(d, "C1"))
    }

    @Test fun P31_I_danglingEndpointFailsClosedWithFullRollback() = db().use { d ->
        GroupATransactionTestFixtures.setupFinance(d)
        val identity = id("CMD-I", "TX-I")
        val proposal = eventfulProposal("CMD-I")
        val e = endpoints(identity)
        val dangling = rel("R-I", CausalRelationClass.TEMPORAL, CausalRelationKinds.BEFORE, "RPGOS-EVENT:DOES-NOT-EXIST", e.second)
        assertTrue(runCatching { TurnTransactionBoundary.create(d, identity, proposal, causalRelationIntents = listOf(dangling)).commit() }.isFailure)
        assertZeroCommitted(d, "C1", "TX-I", 100L)
    }

    @Test fun P31_J_crossCampaignEndpointFailsClosed() = db().use { d ->
        GroupATransactionTestFixtures.setupFinance(d, "C1")
        GroupATransactionTestFixtures.setupFinance(d, "C2")
        val foreignIdentity = TurnTransactionIdentity("C2", "TURN-FOREIGN", "CMD-FOREIGN", "TX-FOREIGN")
        val foreignProposal = eventfulProposal("CMD-FOREIGN", "C2")
        TurnTransactionBoundary.create(d, foreignIdentity, foreignProposal).commit()
        val foreignEvent = endpoints(foreignIdentity, "C2").first

        val identity = id("CMD-J", "TX-J")
        val proposal = eventfulProposal("CMD-J")
        val localTarget = endpoints(identity).second
        val cross = rel("R-J", CausalRelationClass.TEMPORAL, CausalRelationKinds.BEFORE, foreignEvent, localTarget)
        assertTrue(runCatching { TurnTransactionBoundary.create(d, identity, proposal, causalRelationIntents = listOf(cross)).commit() }.isFailure)
        assertZeroCommitted(d, "C1", "TX-J", 100L)
        assertEquals(2L, eventCount(d, "C2", "TX-FOREIGN"))
    }

    @Test fun P31_K_supersessionIsAppendOnlyCorrectionNotDestructiveRewrite() = db().use { d ->
        GroupATransactionTestFixtures.setupFinance(d)
        val id1 = id("CMD-K1", "TX-K1")
        val p1 = eventfulProposal("CMD-K1")
        TurnTransactionBoundary.create(d, id1, p1, causalRelationIntents = temporalPlan(id1)).commit()
        val originalUid = onlyRelationUid(d, "C1", "TX-K1")

        val id2 = id("CMD-K2", "TX-K2")
        val p2 = eventfulProposal("CMD-K2")
        val e2 = endpoints(id2)
        val correction = rel("R-K2", CausalRelationClass.TEMPORAL, CausalRelationKinds.BEFORE, e2.second, e2.first, supersedes = originalUid)
        TurnTransactionBoundary.create(d, id2, p2, causalRelationIntents = listOf(correction)).commit()
        assertEquals(2L, totalCausalCount(d, "C1"))
        assertEquals(originalUid, supersedesUid(d, "C1", "TX-K2"))
        assertTrue(runCatching { d.execSQL("UPDATE canonical_causal_relations SET relation_kind_uid='FORGED' WHERE campaign_uid='C1'") }.isFailure)
        assertTrue(runCatching { d.execSQL("DELETE FROM canonical_causal_relations WHERE campaign_uid='C1'") }.isFailure)
        assertEquals(2L, totalCausalCount(d, "C1"))
    }

    @Test fun P31_L_processReopenPreservesDeterministicPlanAndRetry() {
        val identity = id("CMD-L", "TX-L")
        val proposal = eventfulProposal("CMD-L")
        val relations = temporalPlan(identity)
        db().use { d ->
            GroupATransactionTestFixtures.setupFinance(d)
            assertTrue(TurnTransactionBoundary.create(d, identity, proposal, causalRelationIntents = relations).commit() is TurnExecutionResult.Committed)
            assertEquals(1L, causalCount(d, "C1", "TX-L"))
        }
        db().use { d ->
            assertTrue(TurnTransactionBoundary.create(d, identity, proposal, causalRelationIntents = relations).commit() is TurnExecutionResult.AlreadyCommitted)
            assertEquals(1L, causalCount(d, "C1", "TX-L"))
            assertEquals(1L, receiptCount(d, "C1"))
        }
    }

    @Test fun P31_M_consequenceLinksAreNotAutomaticallyPromoted() = db().use { d ->
        GroupATransactionTestFixtures.setupFinance(d)
        val existed = tableExists(d, "consequence_links")
        if (!existed) {
            d.execSQL("CREATE TABLE consequence_links(legacy_relation_uid TEXT PRIMARY KEY, note TEXT)")
            d.execSQL("INSERT INTO consequence_links VALUES('LEGACY-REL-1','association only')")
        }
        val identity = id("CMD-M", "TX-M")
        TurnTransactionBoundary.create(d, identity, eventfulProposal("CMD-M")).commit()
        assertEquals(0L, causalCount(d, "C1", "TX-M"))
        assertEquals(0L, totalCausalCount(d, "C1"))
    }

    @Test fun P31_N_temporalNarrativeRetrievalCannotMasqueradeAsCauses() = db().use { d ->
        GroupATransactionTestFixtures.setupFinance(d)
        val identity = id("CMD-N", "TX-N")
        val proposal = eventfulProposal("CMD-N")
        val e = endpoints(identity)
        val nonCausal = listOf(
            rel("T", CausalRelationClass.TEMPORAL, CausalRelationKinds.BEFORE, e.first, e.second),
            rel("N", CausalRelationClass.NARRATIVE, CausalRelationKinds.NARRATIVE_ASSOCIATION, e.first, e.second),
            rel("R", CausalRelationClass.RETRIEVAL, CausalRelationKinds.RETRIEVED_WITH, e.first, e.second)
        )
        TurnTransactionBoundary.create(d, identity, proposal, causalRelationIntents = nonCausal).commit()
        assertEquals(3L, causalCount(d, "C1", "TX-N"))
        assertEquals(0L, relationKindCount(d, "C1", "TX-N", CausalRelationKinds.CAUSES))
        assertEquals(setOf("TEMPORAL", "NARRATIVE", "RETRIEVAL"), storedClasses(d, "C1", "TX-N"))
    }

    private fun temporalPlan(identity: TurnTransactionIdentity, campaignUid: String = "C1"): List<CanonicalCausalRelationIntent> {
        val e = endpoints(identity, campaignUid)
        return listOf(rel("REL-${identity.commandUid}", CausalRelationClass.TEMPORAL, CausalRelationKinds.BEFORE, e.first, e.second))
    }

    private fun rel(
        uid: String,
        klass: CausalRelationClass,
        kind: String,
        source: String,
        target: String,
        evidence: List<String> = emptyList(),
        provenance: List<String> = emptyList(),
        supersedes: String? = null
    ) = CanonicalCausalRelationIntent(uid, klass, kind, source, target, evidence, provenance, supersedes)

    private fun endpoints(identity: TurnTransactionIdentity, campaignUid: String = "C1") =
        eventUid(campaignUid, identity.transactionUid, identity.commandUid, "EVENT-INTENT-${identity.commandUid}-A") to
            eventUid(campaignUid, identity.transactionUid, identity.commandUid, "EVENT-INTENT-${identity.commandUid}-B")

    private fun eventUid(campaign: String, tx: String, command: String, intent: String) =
        "RPGOS-EVENT:" + sha256("$campaign|$tx|$command|$intent")

    private fun eventfulProposal(commandUid: String, campaignUid: String = "C1"): CanonicalCampaignMutationProposal {
        val actor = CommandActorRef("PLAYER", "P1")
        val command = PlayerCommand(
            commandUid = commandUid,
            campaignUid = campaignUid,
            actor = actor,
            commandKindUid = PlayerCommandKinds.TRANSFER_FUNDS,
            payload = TransferFundsCommandPayload("A", "B", 5L, "CUR"),
            provenance = CommandProvenance("PHASE31-TEST"),
            requestedEffectiveOrder = 10L
        )
        val refs = setOf(
            CampaignScopedDomainRef(campaignUid, DomainRef("PLAYER", "P1")),
            CampaignScopedDomainRef(campaignUid, DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "A")),
            CampaignScopedDomainRef(campaignUid, DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "B")),
            CampaignScopedDomainRef(campaignUid, DomainRef(PlayerResolutionReferenceKinds.CURRENCY, "CUR"))
        )
        val context = PlayerResolutionContext.createUnboundGeneric(campaignUid, actor, refs)
        val engine = PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(TwoEventFinancialComponent())))
        return when (val admission = CampaignMutationBoundary.resolveAndAdmit(campaignUid, engine, command, context)) {
            is CampaignMutationAdmission.Accepted -> admission.proposal
            is CampaignMutationAdmission.Rejected -> error("canonical admission rejected: ${admission.reasonUid}")
        }
    }

    private class TwoEventFinancialComponent : PlayerResolutionComponent<TransferFundsCommandPayload>(
        PlayerCommandKinds.TRANSFER_FUNDS,
        TransferFundsCommandPayload::class,
        "RPGOS-COMPONENT:PHASE31-TWO-EVENT-FINANCIAL",
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
            fun event(suffix: String, effect: String) = PlayerEventIntent.create(
                eventIntentUid = "EVENT-INTENT-${command.commandUid}-$suffix",
                eventKindUid = PlayerEventIntentKinds.DOMAIN_EFFECT,
                actorRef = subject,
                targetRefs = listOf(subject),
                causalChangeUids = listOf(changeUid),
                payload = DomainEffectEventIntentPayload(subject, effect)
            )
            return PlayerResolutionComponentOutcome.Resolved(
                PlayerResolutionDraft.create(
                    changes = listOf(change),
                    eventIntents = listOf(event("A", "RPGOS-EFFECT:TRANSFER-A"), event("B", "RPGOS-EFFECT:TRANSFER-B"))
                )
            )
        }
    }

    private fun assertZeroCommitted(d: SQLiteDatabase, campaign: String, tx: String, expectedBalance: Long) {
        assertEquals(expectedBalance, FinancialStore(d, campaign).balance("A"))
        assertEquals(0L, eventCount(d, campaign, tx))
        assertEquals(0L, causalCount(d, campaign, tx))
        assertEquals(0L, receiptCount(d, campaign))
    }

    private fun id(command: String, tx: String) = TurnTransactionIdentity("C1", "TURN-$command", command, tx)
    private fun db() = SQLiteDatabase.openOrCreateDatabase(file, null)
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun eventCount(d: SQLiteDatabase, campaign: String, tx: String) = d.rawQuery("SELECT COUNT(*) FROM canonical_gameplay_events WHERE campaign_uid=? AND transaction_uid=?", arrayOf(campaign, tx)).use { it.moveToFirst(); it.getLong(0) }
    private fun causalCount(d: SQLiteDatabase, campaign: String, tx: String) = d.rawQuery("SELECT COUNT(*) FROM canonical_causal_relations WHERE campaign_uid=? AND transaction_uid=?", arrayOf(campaign, tx)).use { it.moveToFirst(); it.getLong(0) }
    private fun totalCausalCount(d: SQLiteDatabase, campaign: String) = d.rawQuery("SELECT COUNT(*) FROM canonical_causal_relations WHERE campaign_uid=?", arrayOf(campaign)).use { it.moveToFirst(); it.getLong(0) }
    private fun receiptCount(d: SQLiteDatabase, campaign: String) = d.rawQuery("SELECT COUNT(*) FROM turn_transaction_receipts WHERE campaign_uid=?", arrayOf(campaign)).use { it.moveToFirst(); it.getLong(0) }
    private fun relationKindCount(d: SQLiteDatabase, campaign: String, tx: String, kind: String) = d.rawQuery("SELECT COUNT(*) FROM canonical_causal_relations WHERE campaign_uid=? AND transaction_uid=? AND relation_kind_uid=?", arrayOf(campaign, tx, kind)).use { it.moveToFirst(); it.getLong(0) }
    private fun storedClasses(d: SQLiteDatabase, campaign: String, tx: String): Set<String> = d.rawQuery("SELECT relation_class_uid FROM canonical_causal_relations WHERE campaign_uid=? AND transaction_uid=?", arrayOf(campaign, tx)).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
    private fun onlyRelationKind(d: SQLiteDatabase, campaign: String, tx: String) = d.rawQuery("SELECT relation_kind_uid FROM canonical_causal_relations WHERE campaign_uid=? AND transaction_uid=?", arrayOf(campaign, tx)).use { it.moveToFirst(); it.getString(0) }
    private fun onlyRelationUid(d: SQLiteDatabase, campaign: String, tx: String) = d.rawQuery("SELECT relation_uid FROM canonical_causal_relations WHERE campaign_uid=? AND transaction_uid=?", arrayOf(campaign, tx)).use { it.moveToFirst(); it.getString(0) }
    private fun supersedesUid(d: SQLiteDatabase, campaign: String, tx: String) = d.rawQuery("SELECT supersedes_relation_uid FROM canonical_causal_relations WHERE campaign_uid=? AND transaction_uid=?", arrayOf(campaign, tx)).use { it.moveToFirst(); it.getString(0) }
    private fun tableExists(d: SQLiteDatabase, name: String) = d.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1", arrayOf(name)).use { it.moveToFirst() }
}
