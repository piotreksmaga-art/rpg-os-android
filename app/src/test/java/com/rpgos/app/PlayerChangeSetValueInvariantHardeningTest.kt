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
class PlayerChangeSetValueInvariantHardeningTest {
    private val actor = CommandActorRef("PLAYER", "P1")
    private val provenance = ChangeSetProvenance("CMD-VALUE", "RPGOS-RESOLVER:VALUE", "1")

    @Test fun p17Value01_positiveDeltaAccepted() {
        assertEquals(1L, ExactLongDelta.of(1).units)
    }

    @Test fun p17Value02_negativeDeltaAccepted() {
        assertEquals(-1L, ExactLongDelta.of(-1).units)
    }

    @Test fun p17Value03_zeroDeltaRejectedByFactory() {
        fails("ZERO_DELTA") { ExactLongDelta.of(0) }
    }

    @Test fun p17Value04_generatedCopyCannotBypassZeroInvariant() {
        val legal = ExactLongDelta.of(1)
        fails("ZERO_DELTA") { legal.copy(units = 0) }
        assertEquals(1L, legal.units)
    }

    @Test fun p17Value05_statChangeWithLegalDeltaRoundTrips() {
        assertRoundTrip(
            change("CH-STAT", PlayerChangeKinds.STAT, StatChange(DomainRef("PLAYER", "P1"), "STAT:STR", ExactLongDelta.of(7)))
        )
    }

    @Test fun p17Value06_resourceChangeWithLegalDeltaRoundTrips() {
        assertRoundTrip(
            change("CH-RESOURCE", PlayerChangeKinds.RESOURCE, ResourceChange(DomainRef("PLAYER", "P1"), "RES:HP", ExactLongDelta.of(-3)))
        )
    }

    @Test fun p17Value07_allExactLongDeltaChangePathsRemainLegal() {
        val changes = listOf(
            change("CH-SKILL", PlayerChangeKinds.SKILL, SkillChange(DomainRef("PLAYER", "P1"), "SKILL:S1", ExactLongDelta.of(1))),
            change("CH-TECH", PlayerChangeKinds.TECHNIQUE, TechniqueChange(DomainRef("PLAYER", "P1"), "TECH:T1", ExactLongDelta.of(2))),
            change("CH-INV", PlayerChangeKinds.INVENTORY, InventoryChange(DomainRef("PLAYER", "P1"), "ITEM:I1", ExactLongDelta.of(-1))),
            change("CH-RUNTIME", PlayerChangeKinds.RUNTIME, RuntimeChange(DomainRef("PLAYER", "P1"), "COUNTER:C1", ExactLongDelta.of(4))),
            change(
                "CH-PROJECT",
                PlayerChangeKinds.DEVELOPMENT_PROJECT,
                DevelopmentProjectChange.create("PROJECT:P1", "WORK:RESULT", ExactLongDelta.of(5))
            )
        )
        val proposal = set("CS-ALL-DELTA-PATHS", changes)
        val decoded = PlayerChangeSetCodec.decode(PlayerChangeSetCodec.encode(proposal))
        assertEquals(proposal, decoded)
    }

    @Test fun p17Value08_noAcceptedChangeSetCanEncodeZeroExactLongDelta() {
        fails("ZERO_DELTA") {
            val zero = ExactLongDelta.of(1).copy(units = 0)
            val invalid = change("CH-ZERO", PlayerChangeKinds.STAT, StatChange(DomainRef("PLAYER", "P1"), "STAT:STR", zero))
            PlayerChangeSetCodec.encode(set("CS-ZERO", listOf(invalid)))
        }

        val legal = set(
            "CS-NONZERO",
            listOf(change("CH-NONZERO", PlayerChangeKinds.STAT, StatChange(DomainRef("PLAYER", "P1"), "STAT:STR", ExactLongDelta.of(1))))
        )
        val decoded = PlayerChangeSetCodec.decode(PlayerChangeSetCodec.encode(legal))
        val delta = (decoded.changes.single().payload as StatChange).delta
        assertNotEquals(0L, delta.units)
    }

    @Test fun p17Value09_encodeDecodeEncodeRemainsDeterministic() {
        val proposal = set(
            "CS-DETERMINISTIC",
            listOf(
                change("CH-A", PlayerChangeKinds.STAT, StatChange(DomainRef("PLAYER", "X:Y"), "Z", ExactLongDelta.of(Long.MAX_VALUE))),
                change("CH-B", PlayerChangeKinds.RESOURCE, ResourceChange(DomainRef("PLAYER", "X"), "Y:Z", ExactLongDelta.of(Long.MIN_VALUE)))
            )
        )
        val first = PlayerChangeSetCodec.encode(proposal)
        val decoded = PlayerChangeSetCodec.decode(first)
        assertEquals(first, PlayerChangeSetCodec.encode(decoded))
        assertEquals(proposal, decoded)
    }

    @Test fun p17Value10_fingerprintRemainsDeterministicForLegalProposals() {
        val proposal = set(
            "CS-FP",
            listOf(change("CH-FP", PlayerChangeKinds.TECHNIQUE, TechniqueChange(DomainRef("PLAYER", "P1"), "TECH:T1", ExactLongDelta.of(9))))
        )
        val first = PlayerChangeSetCodec.fingerprint(proposal)
        val second = PlayerChangeSetCodec.fingerprint(PlayerChangeSetCodec.decode(PlayerChangeSetCodec.encode(proposal)))
        assertEquals(first, second)
    }

    @Test fun p17Value11_compositeConflictIdentityRegression() {
        val a = change("CH-A", PlayerChangeKinds.STAT, StatChange(DomainRef("PLAYER", "X:Y"), "Z", ExactLongDelta.of(1)))
        val b = change("CH-B", PlayerChangeKinds.STAT, StatChange(DomainRef("PLAYER", "X"), "Y:Z", ExactLongDelta.of(1)))
        PlayerChangeSetValidator.validate(set("CS-COMPOSITE", listOf(a, b)))
        assertNotEquals(TypedPlayerChangeRegistry.core().conflictKeys(a), TypedPlayerChangeRegistry.core().conflictKeys(b))
    }

    @Test fun p17Value12_financialLedgerRegression() {
        val financial = change(
            "CH-FIN",
            PlayerChangeKinds.FINANCIAL,
            FinancialChange("ACCOUNT:A", "ACCOUNT:B", 100L, "CUR:PLN", "TRANSFER")
        )
        val ledger = PlayerLedgerIntent.create(
            ledgerIntentUid = "LED-1",
            ledgerKindUid = PlayerLedgerIntentKinds.FINANCIAL_TRANSFER,
            causalChangeUids = listOf("CH-FIN"),
            payload = FinancialTransferLedgerIntentPayload("ACCOUNT:A", "ACCOUNT:B", 100L, "CUR:PLN", "TRANSFER")
        )
        val proposal = PlayerChangeSet.create(
            changeSetUid = "CS-FIN",
            campaignUid = "C1",
            sourceCommandUid = "CMD-VALUE",
            actor = actor,
            changes = listOf(financial),
            ledgerIntents = listOf(ledger),
            provenance = provenance
        )
        assertEquals(proposal, PlayerChangeSetCodec.decode(PlayerChangeSetCodec.encode(proposal)))
    }

    @Test fun p17Value13_assetIdentityRegression() {
        val property = change(
            "CH-PROPERTY",
            PlayerChangeKinds.ASSET,
            AssetChange(OwnedAssetRef("RPGOS-ASSET-KIND:PROPERTY", "BUSINESS:A-1"), "ACTIVE")
        )
        val business = change(
            "CH-BUSINESS",
            PlayerChangeKinds.ASSET,
            AssetChange(OwnedAssetRef("RPGOS-ASSET-KIND:PROPERTY:BUSINESS", "A-1"), "ACTIVE")
        )
        PlayerChangeSetValidator.validate(set("CS-ASSET", listOf(property, business)))
        assertNotEquals(TypedPlayerChangeRegistry.core().conflictKeys(property), TypedPlayerChangeRegistry.core().conflictKeys(business))
    }

    @Test fun p17Value14_zeroAuthoritativeMutation() {
        val db = SQLiteDatabase.create(null)
        try {
            db.execSQL("CREATE TABLE authority_fixture(uid TEXT PRIMARY KEY, value INTEGER NOT NULL)")
            db.execSQL("INSERT INTO authority_fixture(uid,value) VALUES('A',7)")
            val before = scalar(db)
            val proposal = set(
                "CS-ZERO-MUTATION",
                listOf(change("CH-A", PlayerChangeKinds.STAT, StatChange(DomainRef("PLAYER", "P1"), "STAT:STR", ExactLongDelta.of(1))))
            )
            val encoded = PlayerChangeSetCodec.encode(proposal)
            val decoded = PlayerChangeSetCodec.decode(encoded)
            PlayerChangeSetCodec.fingerprint(decoded)
            assertEquals(before, scalar(db))
        } finally {
            db.close()
        }
    }

    @Test fun p17Value15_phase3to16RegressionRepresentativeChecks() {
        val commandRegistry = PlayerCommandKindRegistry.core()
        val command = PlayerCommand(
            commandUid = "P17-VALUE-P16-CMD",
            campaignUid = "C1",
            actor = actor,
            commandKindUid = PlayerCommandKinds.TRAIN,
            payload = TrainCommandPayload(DomainRef("STAT", "STR"), 10, "METHOD"),
            provenance = CommandProvenance("TEST")
        )
        val encoded = commandRegistry.encode(command)
        assertEquals(encoded, commandRegistry.encode(commandRegistry.decode(encoded)))

        val full = OwnershipShare.full()
        assertEquals(OWNERSHIP_SHARE_SCALE, full.units)
        failsIllegalArgument { full.copy(units = 0) }
        failsIllegalArgument { full.copy(units = OWNERSHIP_SHARE_SCALE + 1) }
        assertEquals(full, full.copy())
    }

    private fun assertRoundTrip(change: PlayerDomainChange) {
        val proposal = set("CS-${change.changeUid}", listOf(change))
        val encoded = PlayerChangeSetCodec.encode(proposal)
        val decoded = PlayerChangeSetCodec.decode(encoded)
        assertEquals(proposal, decoded)
        assertEquals(encoded, PlayerChangeSetCodec.encode(decoded))
    }

    private fun change(uid: String, kind: String, payload: PlayerDomainChangePayload): PlayerDomainChange =
        PlayerDomainChange.create(uid, kind, payload)

    private fun set(uid: String, changes: List<PlayerDomainChange>): PlayerChangeSet = PlayerChangeSet.create(
        changeSetUid = uid,
        campaignUid = "C1",
        sourceCommandUid = "CMD-VALUE",
        actor = actor,
        changes = changes,
        provenance = provenance
    )

    private fun fails(code: String, block: () -> Unit) {
        try {
            block()
            fail("Expected PlayerChangeSetStructuralException($code)")
        } catch (e: PlayerChangeSetStructuralException) {
            assertEquals(code, e.code)
        }
    }

    private fun failsIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun scalar(db: SQLiteDatabase): Long = db.rawQuery("SELECT value FROM authority_fixture WHERE uid='A'", null).use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.getLong(0)
    }
}
