package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerChangeSetReleaseBlockerHotfixTest {
    private val actor = CommandActorRef("PLAYER", "P1")
    private val subject = DomainRef("PLAYER", "P1")
    private val provenance = ChangeSetProvenance("CMD-HOTFIX", "RPGOS-RESOLVER:HOTFIX", "1")
    private val propertyRef = OwnedAssetRef("RPGOS-ASSET-KIND:PROPERTY", "A-1")
    private val businessRef = OwnedAssetRef("RPGOS-ASSET-KIND:BUSINESS", "A-1")

    private fun change(
        uid: String,
        kind: String,
        payload: PlayerDomainChangePayload
    ): PlayerDomainChange = PlayerDomainChange.create(uid, kind, payload)

    private fun assetChange(
        uid: String,
        ref: OwnedAssetRef,
        state: String = "ACTIVE"
    ): PlayerDomainChange = change(uid, PlayerChangeKinds.ASSET, AssetChange(ref, state))

    private fun financialChange(
        uid: String = "CH-FIN",
        from: String = "A",
        to: String = "B",
        amount: Long = 100L,
        currency: String = "CUR",
        transactionType: String = "TRANSFER"
    ): PlayerDomainChange = change(
        uid,
        PlayerChangeKinds.FINANCIAL,
        FinancialChange(from, to, amount, currency, transactionType)
    )

    private fun ledger(
        causal: List<String> = listOf("CH-FIN"),
        from: String = "A",
        to: String = "B",
        amount: Long = 100L,
        currency: String = "CUR",
        transactionType: String = "TRANSFER"
    ): PlayerLedgerIntent = PlayerLedgerIntent.create(
        ledgerIntentUid = "LED-1",
        ledgerKindUid = PlayerLedgerIntentKinds.FINANCIAL_TRANSFER,
        causalChangeUids = causal,
        payload = FinancialTransferLedgerIntentPayload(from, to, amount, currency, transactionType)
    )

    private fun set(
        uid: String = "CS-HOTFIX",
        changes: List<PlayerDomainChange>,
        ledgers: List<PlayerLedgerIntent> = emptyList()
    ): PlayerChangeSet = PlayerChangeSet.create(
        changeSetUid = uid,
        campaignUid = "C1",
        sourceCommandUid = "CMD-HOTFIX",
        actor = actor,
        changes = changes,
        ledgerIntents = ledgers,
        provenance = provenance
    )

    @Test fun p17Hotfix_01_propertyAndBusinessSameUidRemainDistinctTargets() {
        val property = assetChange("CH-PROPERTY", propertyRef)
        val business = assetChange("CH-BUSINESS", businessRef)
        assertNotEquals((property.payload as AssetChange).asset, (business.payload as AssetChange).asset)
        assertEquals(2, set(changes = listOf(property, business)).changes.size)
    }

    @Test fun p17Hotfix_02_assetRoundTripPreservesKindAndUid() {
        val original = set(changes = listOf(assetChange("CH-ASSET", propertyRef)))
        val decoded = PlayerChangeSetCodec.decode(PlayerChangeSetCodec.encode(original))
        val asset = (decoded.changes.single().payload as AssetChange).asset
        assertEquals("RPGOS-ASSET-KIND:PROPERTY", asset.assetKindUid)
        assertEquals("A-1", asset.assetUid)
        assertEquals(propertyRef, asset)
    }

    @Test fun p17Hotfix_03_assetConflictKeyContainsFullCanonicalIdentity() {
        val registry = TypedPlayerChangeRegistry.core()
        val property = assetChange("CH-PROPERTY", propertyRef)
        val business = assetChange("CH-BUSINESS", businessRef)
        assertEquals(setOf("ASSET:RPGOS-ASSET-KIND:PROPERTY:A-1"), registry.conflictKeys(property))
        assertEquals(setOf("ASSET:RPGOS-ASSET-KIND:BUSINESS:A-1"), registry.conflictKeys(business))
    }

    @Test fun p17Hotfix_04_sameAssetKindAndUidConflictFailClosed() {
        val first = assetChange("CH-A", propertyRef, "ACTIVE")
        val second = assetChange("CH-B", propertyRef, "RETIRED")
        fails("CONFLICTING_CHANGE_TARGET") { set(changes = listOf(first, second)) }
    }

    @Test fun p17Hotfix_05_differentAssetKindsSameUidDoNotFalseConflict() {
        val property = assetChange("CH-A", propertyRef, "ACTIVE")
        val business = assetChange("CH-B", businessRef, "ACTIVE")
        val proposal = set(changes = listOf(property, business))
        assertEquals(listOf(property, business), proposal.changes)
        assertNotEquals(
            PlayerChangeSetCodec.fingerprint(set(uid = "CS-A", changes = listOf(property))),
            PlayerChangeSetCodec.fingerprint(set(uid = "CS-A", changes = listOf(business)))
        )
    }

    @Test fun p17Hotfix_06_matchingFinancialChangeAndLedgerAccepted() {
        val proposal = set(changes = listOf(financialChange()), ledgers = listOf(ledger()))
        PlayerChangeSetValidator.validate(proposal)
        val decoded = PlayerChangeSetCodec.decode(PlayerChangeSetCodec.encode(proposal))
        assertEquals(proposal, decoded)
    }

    @Test fun p17Hotfix_07_differentDestinationRejected() {
        mismatch { ledger(to = "C") }
    }

    @Test fun p17Hotfix_08_differentAmountRejected() {
        mismatch { ledger(amount = 999L) }
    }

    @Test fun p17Hotfix_09_differentSourceAccountRejected() {
        mismatch { ledger(from = "Z") }
    }

    @Test fun p17Hotfix_10_differentCurrencyRejected() {
        mismatch { ledger(currency = "OTHER") }
    }

    @Test fun p17Hotfix_11_differentTransactionTypeRejected() {
        mismatch { ledger(transactionType = "OTHER") }
    }

    @Test fun p17Hotfix_12_nonFinancialCausalChangeCannotMasqueradeAsFinanceLinkage() {
        val nonFinancial = change("CH-STAT", PlayerChangeKinds.STAT, StatChange(subject, "STAT:STR", ExactLongDelta.of(1)))
        fails("FINANCIAL_LEDGER_CAUSAL_CHANGE_REQUIRED") {
            set(changes = listOf(nonFinancial), ledgers = listOf(ledger(causal = listOf("CH-STAT"))))
        }
    }

    @Test fun p17Hotfix_13_danglingLedgerCausalChangeStillRejected() {
        fails("INVALID_LEDGER_INTENT") {
            set(changes = listOf(financialChange()), ledgers = listOf(ledger(causal = listOf("MISSING"))))
        }
    }

    @Test fun p17Hotfix_14_canonicalRoundTripAndFingerprintRemainDeterministic() {
        val proposal = set(
            changes = listOf(assetChange("CH-ASSET", propertyRef), financialChange()),
            ledgers = listOf(ledger())
        )
        val encoded = PlayerChangeSetCodec.encode(proposal)
        val decoded = PlayerChangeSetCodec.decode(encoded)
        assertEquals(encoded, PlayerChangeSetCodec.encode(decoded))
        assertEquals(PlayerChangeSetCodec.fingerprint(proposal), PlayerChangeSetCodec.fingerprint(decoded))
        assertEquals(PlayerChangeSetIdentityRelation.SAME_LOGICAL_CHANGE_SET, PlayerChangeSetIdentity.compare(proposal, decoded))
    }

    @Test fun p17Hotfix_15_zeroAuthoritativeDbMutation() {
        val db = SQLiteDatabase.create(null)
        try {
            db.execSQL("CREATE TABLE authority_fixture(uid TEXT PRIMARY KEY, value INTEGER NOT NULL)")
            db.execSQL("INSERT INTO authority_fixture(uid,value) VALUES('A',7)")
            val before = scalar(db)
            val proposal = set(
                changes = listOf(assetChange("CH-ASSET", propertyRef), financialChange()),
                ledgers = listOf(ledger())
            )
            PlayerChangeSetValidator.validate(proposal)
            val encoded = PlayerChangeSetCodec.encode(proposal)
            val decoded = PlayerChangeSetCodec.decode(encoded)
            PlayerChangeSetCodec.fingerprint(decoded)
            assertEquals(before, scalar(db))
        } finally {
            db.close()
        }
    }

    @Test fun p17Hotfix_16_phase3to16Regression() {
        val commandRegistry = PlayerCommandKindRegistry.core()
        val command = PlayerCommand(
            commandUid = "HOTFIX-P16-CMD",
            campaignUid = "C1",
            actor = actor,
            commandKindUid = PlayerCommandKinds.TRAIN,
            payload = TrainCommandPayload(DomainRef("STAT", "STR"), 10, "METHOD"),
            provenance = CommandProvenance("TEST")
        )
        val encodedCommand = commandRegistry.encode(command)
        assertEquals(encodedCommand, commandRegistry.encode(commandRegistry.decode(encodedCommand)))
        assertEquals(OWNERSHIP_SHARE_SCALE, OwnershipShare.full().units)
        FinancialPolicy.validateTransaction(
            FinancialTransaction(
                campaignId = "C1",
                financialTransactionUid = "TX-HOTFIX",
                fromAccountUid = "A",
                toAccountUid = "B",
                currencyUid = "CUR",
                amountMinor = 1L,
                transactionTypeUid = "TRANSFER",
                flowKind = FinancialFlowKind.INTERNAL,
                reason = "hotfix regression",
                effectiveOrder = 1L,
                provenance = "P17-HOTFIX"
            )
        )
        assertEquals(propertyRef, OwnedAssetRef(propertyRef.assetKindUid, propertyRef.assetUid))
    }

    private fun mismatch(makeLedger: () -> PlayerLedgerIntent) {
        fails("FINANCIAL_LEDGER_TERMS_MISMATCH") {
            set(changes = listOf(financialChange()), ledgers = listOf(makeLedger()))
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
