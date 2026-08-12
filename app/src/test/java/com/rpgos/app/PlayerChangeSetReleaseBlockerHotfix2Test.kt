package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerChangeSetReleaseBlockerHotfix2Test {
    private val actor = CommandActorRef("PLAYER", "P1")
    private val subject = DomainRef("PLAYER", "P1")
    private val provenance = ChangeSetProvenance("CMD-HOTFIX2", "RPGOS-RESOLVER:HOTFIX2", "1")

    private fun financialChange(
        uid: String = "CH-FIN",
        from: String = "A",
        to: String = "B",
        amount: Long = 100L,
        currency: String = "CUR",
        transactionType: String = "TRANSFER"
    ): PlayerDomainChange = PlayerDomainChange.create(
        uid,
        PlayerChangeKinds.FINANCIAL,
        FinancialChange(from, to, amount, currency, transactionType)
    )

    private fun nonFinancialChange(uid: String = "CH-NONFIN"): PlayerDomainChange = PlayerDomainChange.create(
        uid,
        PlayerChangeKinds.STAT,
        StatChange(subject, "STAT:STR", ExactLongDelta.of(1))
    )

    private fun ledger(
        uid: String,
        causal: List<String> = listOf("CH-FIN"),
        from: String = "A",
        to: String = "B",
        amount: Long = 100L,
        currency: String = "CUR",
        transactionType: String = "TRANSFER"
    ): PlayerLedgerIntent = PlayerLedgerIntent.create(
        ledgerIntentUid = uid,
        ledgerKindUid = PlayerLedgerIntentKinds.FINANCIAL_TRANSFER,
        causalChangeUids = causal,
        payload = FinancialTransferLedgerIntentPayload(from, to, amount, currency, transactionType)
    )

    private fun set(
        uid: String = "CS-HOTFIX2",
        changes: List<PlayerDomainChange> = emptyList(),
        ledgers: List<PlayerLedgerIntent> = emptyList()
    ): PlayerChangeSet = PlayerChangeSet.create(
        changeSetUid = uid,
        campaignUid = "C1",
        sourceCommandUid = "CMD-HOTFIX2",
        actor = actor,
        changes = changes,
        ledgerIntents = ledgers,
        provenance = provenance
    )

    @Test fun p17Hotfix2_01_sameFinancialCauseAcrossTwoLedgersRejected() {
        val fin = financialChange()
        val led1 = ledger("LED-1")
        val led2 = ledger("LED-2")
        fails("DUPLICATE_FINANCIAL_LEDGER_CAUSAL_CHANGE") {
            set(changes = listOf(fin), ledgers = listOf(led1, led2))
        }
    }

    @Test fun p17Hotfix2_02_singleMatchingCausalLedgerAccepted() {
        val proposal = set(changes = listOf(financialChange()), ledgers = listOf(ledger("LED-1")))
        PlayerChangeSetValidator.validate(proposal)
        assertEquals(proposal, PlayerChangeSetCodec.decode(PlayerChangeSetCodec.encode(proposal)))
    }

    @Test fun p17Hotfix2_03_twoIndependentFinancialChangesWithSeparateLedgersAccepted() {
        val fin1 = financialChange("CH-FIN-1", "A", "B", 100L)
        val fin2 = financialChange("CH-FIN-2", "C", "D", 200L)
        val led1 = ledger("LED-1", listOf("CH-FIN-1"), "A", "B", 100L)
        val led2 = ledger("LED-2", listOf("CH-FIN-2"), "C", "D", 200L)
        val proposal = set(changes = listOf(fin1, fin2), ledgers = listOf(led1, led2))
        PlayerChangeSetValidator.validate(proposal)
        assertEquals(2, proposal.ledgerIntents.size)
    }

    @Test fun p17Hotfix2_04_standaloneLedgerRemainsAccepted() {
        val proposal = set(ledgers = listOf(ledger("LED-STANDALONE", causal = emptyList())))
        PlayerChangeSetValidator.validate(proposal)
        assertEquals(proposal, PlayerChangeSetCodec.decode(PlayerChangeSetCodec.encode(proposal)))
    }

    @Test fun p17Hotfix2_05_mixedCausalRefsOccupyFinancialCause() {
        val fin = financialChange()
        val nonFin = nonFinancialChange()
        val led1 = ledger("LED-1", causal = listOf("CH-FIN", "CH-NONFIN"))
        val led2 = ledger("LED-2", causal = listOf("CH-FIN"))
        fails("DUPLICATE_FINANCIAL_LEDGER_CAUSAL_CHANGE") {
            set(changes = listOf(fin, nonFin), ledgers = listOf(led1, led2))
        }
    }

    @Test fun p17Hotfix2_06_multipleFinancialCausesRemainFailClosedByExistingConflictRules() {
        val fin1 = financialChange("CH-FIN-1")
        val fin2 = financialChange("CH-FIN-2")
        val led1 = ledger("LED-1", causal = listOf("CH-FIN-1", "CH-FIN-2"))
        val led2 = ledger("LED-2", causal = listOf("CH-FIN-2"))
        fails("CONFLICTING_CHANGE_TARGET") {
            set(changes = listOf(fin1, fin2), ledgers = listOf(led1, led2))
        }
    }

    @Test fun p17Hotfix2_07_termMismatchStillWinsBeforeDuplicateGuard() {
        val fin = financialChange()
        val led1 = ledger("LED-1")
        val led2 = ledger("LED-2", amount = 999L)
        fails("FINANCIAL_LEDGER_TERMS_MISMATCH") {
            set(changes = listOf(fin), ledgers = listOf(led1, led2))
        }
    }

    @Test fun p17Hotfix2_08_nonFinancialOnlyCausalRefsStillRejected() {
        val nonFin = nonFinancialChange()
        fails("FINANCIAL_LEDGER_CAUSAL_CHANGE_REQUIRED") {
            set(changes = listOf(nonFin), ledgers = listOf(ledger("LED-1", causal = listOf("CH-NONFIN"))))
        }
    }

    @Test fun p17Hotfix2_09_danglingCausalRefStillRejected() {
        fails("INVALID_LEDGER_INTENT") {
            set(changes = listOf(financialChange()), ledgers = listOf(ledger("LED-1", causal = listOf("MISSING"))))
        }
    }

    @Test fun p17Hotfix2_10_canonicalRoundTripRemainsByteDeterministic() {
        val proposal = set(changes = listOf(financialChange()), ledgers = listOf(ledger("LED-1")))
        val encoded = PlayerChangeSetCodec.encode(proposal)
        val decoded = PlayerChangeSetCodec.decode(encoded)
        assertEquals(encoded, PlayerChangeSetCodec.encode(decoded))
    }

    @Test fun p17Hotfix2_11_fingerprintRemainsDeterministic() {
        val proposal = set(changes = listOf(financialChange()), ledgers = listOf(ledger("LED-1")))
        val encoded = PlayerChangeSetCodec.encode(proposal)
        val decoded = PlayerChangeSetCodec.decode(encoded)
        assertEquals(PlayerChangeSetCodec.fingerprint(proposal), PlayerChangeSetCodec.fingerprint(decoded))
    }

    @Test fun p17Hotfix2_12_zeroAuthoritativeDbMutationAcrossContractOperations() {
        val db = SQLiteDatabase.create(null)
        try {
            db.execSQL("CREATE TABLE authority_fixture(uid TEXT PRIMARY KEY, value INTEGER NOT NULL)")
            db.execSQL("INSERT INTO authority_fixture(uid,value) VALUES('A',7)")
            val before = scalar(db)
            val proposal = set(changes = listOf(financialChange()), ledgers = listOf(ledger("LED-1")))
            PlayerChangeSetValidator.validate(proposal)
            val encoded = PlayerChangeSetCodec.encode(proposal)
            val decoded = PlayerChangeSetCodec.decode(encoded)
            PlayerChangeSetCodec.fingerprint(decoded)
            assertEquals(before, scalar(db))
        } finally {
            db.close()
        }
    }

    private fun scalar(db: SQLiteDatabase): Long {
        db.rawQuery("SELECT value FROM authority_fixture WHERE uid='A'", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            return cursor.getLong(0)
        }
    }

    private fun fails(code: String, block: () -> Unit) {
        try {
            block()
            fail("Expected PlayerChangeSetStructuralException($code)")
        } catch (e: PlayerChangeSetStructuralException) {
            assertEquals(code, e.code)
        }
    }
}
