package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase32AppendOnlySqlRejectionTest {
    private lateinit var file: File

    @Before
    fun setUp() {
        file = File.createTempFile("g32-append-only-", ".db").also { it.delete() }
    }

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun receiptEventAndCausalRowsRejectUpdateAndDeleteWithoutChangingCommittedEvidence() {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            Phase32ProductionReadyTestFixture.setup(db, "C1")

            commitEventTurn(db, "1")
            commitEventTurn(db, "2")
            val event1 = eventUid(db, "TX-G32-APPEND-1")
            val event2 = eventUid(db, "TX-G32-APPEND-2")

            val causalIdentity = TurnTransactionIdentity(
                "C1",
                "TURN-G32-APPEND-C",
                "CMD-G32-APPEND-C",
                "TX-G32-APPEND-C"
            )
            val relation = CanonicalCausalRelationIntent(
                relationIntentUid = "REL-G32-APPEND",
                relationClass = CausalRelationClass.PROVENANCE,
                relationKindUid = CausalRelationKinds.PROVENANCE_OF,
                sourceEventUid = event1,
                targetEventUid = event2,
                provenanceEventUids = listOf(event1)
            )
            assertTrue(
                TurnTransactionBoundary.create(
                    db,
                    causalIdentity,
                    GroupATransactionTestFixtures.admittedFinancialProposal(
                        campaignUid = "C1",
                        commandUid = "CMD-G32-APPEND-C",
                        amountMinor = 1L
                    ),
                    causalRelationIntents = listOf(relation)
                ).commit() is TurnExecutionResult.Committed
            )

            val before = mapOf(
                "receipt" to tableDump(db, "turn_transaction_receipts"),
                "event" to tableDump(db, "canonical_gameplay_events"),
                "causal" to tableDump(db, "canonical_causal_relations")
            )
            assertTrue(before.getValue("receipt").isNotEmpty())
            assertTrue(before.getValue("event").isNotEmpty())
            assertTrue(before.getValue("causal").isNotEmpty())

            assertRejected {
                db.execSQL(
                    "UPDATE turn_transaction_receipts SET result_fingerprint=result_fingerprint||'X' WHERE transaction_uid='TX-G32-APPEND-1'"
                )
            }
            assertRejected {
                db.execSQL("DELETE FROM turn_transaction_receipts WHERE transaction_uid='TX-G32-APPEND-1'")
            }
            assertRejected {
                db.execSQL(
                    "UPDATE canonical_gameplay_events SET semantic_fingerprint=semantic_fingerprint||'X' WHERE event_uid=?",
                    arrayOf(event1)
                )
            }
            assertRejected {
                db.execSQL("DELETE FROM canonical_gameplay_events WHERE event_uid=?", arrayOf(event1))
            }
            assertRejected {
                db.execSQL(
                    "UPDATE canonical_causal_relations SET semantic_fingerprint=semantic_fingerprint||'X' WHERE campaign_uid='C1' AND relation_intent_uid='REL-G32-APPEND'"
                )
            }
            assertRejected {
                db.execSQL(
                    "DELETE FROM canonical_causal_relations WHERE campaign_uid='C1' AND relation_intent_uid='REL-G32-APPEND'"
                )
            }

            assertEquals(before.getValue("receipt"), tableDump(db, "turn_transaction_receipts"))
            assertEquals(before.getValue("event"), tableDump(db, "canonical_gameplay_events"))
            assertEquals(before.getValue("causal"), tableDump(db, "canonical_causal_relations"))
        }
    }

    private fun commitEventTurn(db: SQLiteDatabase, suffix: String) {
        val identity = TurnTransactionIdentity(
            "C1",
            "TURN-G32-APPEND-$suffix",
            "CMD-G32-APPEND-$suffix",
            "TX-G32-APPEND-$suffix"
        )
        assertTrue(
            TurnTransactionBoundary.create(
                db,
                identity,
                eventfulFinancialProposal("CMD-G32-APPEND-$suffix")
            ).commit() is TurnExecutionResult.Committed
        )
    }

    private fun eventfulFinancialProposal(commandUid: String): CanonicalCampaignMutationProposal {
        val actor = CommandActorRef("PLAYER", "P1")
        val command = PlayerCommand(
            commandUid = commandUid,
            campaignUid = "C1",
            actor = actor,
            commandKindUid = PlayerCommandKinds.TRANSFER_FUNDS,
            payload = TransferFundsCommandPayload("A", "B", 1L, "CUR"),
            provenance = CommandProvenance("G32-APPEND-ONLY-SQL"),
            requestedEffectiveOrder = 10L
        )
        val refs = setOf(
            CampaignScopedDomainRef("C1", DomainRef("PLAYER", "P1")),
            CampaignScopedDomainRef("C1", DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "A")),
            CampaignScopedDomainRef("C1", DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "B")),
            CampaignScopedDomainRef("C1", DomainRef(PlayerResolutionReferenceKinds.CURRENCY, "CUR"))
        )
        val context = PlayerResolutionContext.createUnboundGeneric("C1", actor, refs)
        val engine = PlayerDomainEngine(
            PlayerResolutionComponentRegistry.of(listOf(EventfulFinancialComponent()))
        )
        return when (val admission = CampaignMutationBoundary.resolveAndAdmit("C1", engine, command, context)) {
            is CampaignMutationAdmission.Accepted -> admission.proposal
            is CampaignMutationAdmission.Rejected -> error("canonical admission rejected: ${admission.reasonUid}")
        }
    }

    private class EventfulFinancialComponent : PlayerResolutionComponent<TransferFundsCommandPayload>(
        PlayerCommandKinds.TRANSFER_FUNDS,
        TransferFundsCommandPayload::class,
        "RPGOS-COMPONENT:G32-APPEND-ONLY-SQL",
        "1"
    ) {
        override fun resolve(
            command: PlayerCommand<TransferFundsCommandPayload>,
            context: PlayerResolutionContext
        ): PlayerResolutionComponentOutcome {
            val changeUid = "CHANGE-${command.commandUid}"
            val subject = DomainRef("PLAYER", "P1")
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
                eventIntentUid = "EVENT-${command.commandUid}",
                eventKindUid = PlayerEventIntentKinds.DOMAIN_EFFECT,
                actorRef = subject,
                targetRefs = listOf(subject),
                causalChangeUids = listOf(changeUid),
                payload = DomainEffectEventIntentPayload(subject, "RPGOS-EFFECT:G32-APPEND-ONLY")
            )
            return PlayerResolutionComponentOutcome.Resolved(
                PlayerResolutionDraft.create(changes = listOf(change), eventIntents = listOf(event))
            )
        }
    }

    private fun eventUid(db: SQLiteDatabase, transactionUid: String): String =
        db.rawQuery(
            "SELECT event_uid FROM canonical_gameplay_events WHERE campaign_uid='C1' AND transaction_uid=?",
            arrayOf(transactionUid)
        ).use { c ->
            assertTrue(c.moveToFirst())
            c.getString(0)
        }

    private fun assertRejected(block: () -> Unit) {
        assertTrue("append-only mutation unexpectedly succeeded", runCatching(block).isFailure)
    }

    private fun tableDump(db: SQLiteDatabase, table: String): List<String> =
        db.rawQuery("SELECT * FROM $table ORDER BY 1,2,3", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        (0 until c.columnCount).joinToString("\u001f") { index ->
                            when (c.getType(index)) {
                                android.database.Cursor.FIELD_TYPE_NULL -> "<NULL>"
                                android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(index).toString()
                                android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(index).toString()
                                android.database.Cursor.FIELD_TYPE_BLOB -> c.getBlob(index).joinToString("") { "%02x".format(it) }
                                else -> c.getString(index)
                            }
                        }
                    )
                }
            }
        }
}
