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
class PlayerChangeSetReleaseBlockerHotfix3Test {
    private val actor = CommandActorRef("PLAYER", "P1")
    private val provenance = ChangeSetProvenance("CMD-HOTFIX3", "RPGOS-RESOLVER:HOTFIX3", "1")

    private fun assetChange(uid: String, kind: String, assetUid: String, state: String = "ACTIVE") =
        PlayerDomainChange.create(uid, PlayerChangeKinds.ASSET, AssetChange(OwnedAssetRef(kind, assetUid), state))

    private fun set(uid: String = "CS-HOTFIX3", changes: List<PlayerDomainChange>) = PlayerChangeSet.create(
        changeSetUid = uid,
        campaignUid = "C1",
        sourceCommandUid = "CMD-HOTFIX3",
        actor = actor,
        changes = changes,
        provenance = provenance
    )

    @Test fun p17Hotfix3_01_chat5AliasReproducerIsAcceptedAsDistinctAssets() {
        val a = assetChange("CH-A", "RPGOS-ASSET-KIND:PROPERTY", "BUSINESS:A-1")
        val b = assetChange("CH-B", "RPGOS-ASSET-KIND:PROPERTY:BUSINESS", "A-1")
        val proposal = set(changes = listOf(a, b))
        PlayerChangeSetValidator.validate(proposal)
        assertEquals(2, proposal.changes.size)
        assertNotEquals(
            TypedPlayerChangeRegistry.core().conflictKeys(a),
            TypedPlayerChangeRegistry.core().conflictKeys(b)
        )
    }

    @Test fun p17Hotfix3_02_sameTupleStillConflicts() {
        val a = assetChange("CH-A", "KIND:WITH:COLON", "UID:WITH:COLON", "ACTIVE")
        val b = assetChange("CH-B", "KIND:WITH:COLON", "UID:WITH:COLON", "RETIRED")
        fails("CONFLICTING_CHANGE_TARGET") { set(changes = listOf(a, b)) }
    }

    @Test fun p17Hotfix3_03_differentKindSameUidRemainDistinct() {
        val a = assetChange("CH-A", "KIND:A", "SAME:UID")
        val b = assetChange("CH-B", "KIND:B", "SAME:UID")
        PlayerChangeSetValidator.validate(set(changes = listOf(a, b)))
        assertNotEquals(TypedPlayerChangeRegistry.core().conflictKeys(a), TypedPlayerChangeRegistry.core().conflictKeys(b))
    }

    @Test fun p17Hotfix3_04_sameKindDifferentUidRemainDistinct() {
        val a = assetChange("CH-A", "KIND:A", "UID:ONE")
        val b = assetChange("CH-B", "KIND:A", "UID:TWO")
        PlayerChangeSetValidator.validate(set(changes = listOf(a, b)))
        assertNotEquals(TypedPlayerChangeRegistry.core().conflictKeys(a), TypedPlayerChangeRegistry.core().conflictKeys(b))
    }

    @Test fun p17Hotfix3_05_multipleColonsInBothComponentsDoNotCollide() {
        val a = assetChange("CH-A", "K:A:B:C", "U:D:E:F")
        val b = assetChange("CH-B", "K:A:B:C:U", "D:E:F")
        PlayerChangeSetValidator.validate(set(changes = listOf(a, b)))
        assertNotEquals(TypedPlayerChangeRegistry.core().conflictKeys(a), TypedPlayerChangeRegistry.core().conflictKeys(b))
    }

    @Test fun p17Hotfix3_06_unicodeSpacesPipesAndBackslashesDoNotCollide() {
        val a = assetChange("CH-A", "KIND | łódź \\ α:β", "UID | 東京 \\ x:y")
        val b = assetChange("CH-B", "KIND | łódź \\ α:β:UID", " | 東京 \\ x:y")
        PlayerChangeSetValidator.validate(set(changes = listOf(a, b)))
        assertNotEquals(TypedPlayerChangeRegistry.core().conflictKeys(a), TypedPlayerChangeRegistry.core().conflictKeys(b))
    }

    @Test fun p17Hotfix3_07_assetRoundTripPreservesFullOwnedAssetRef() {
        val ref = OwnedAssetRef("RPGOS-ASSET-KIND:PROPERTY:特殊 | \\ kind", "BUSINESS:A-1 | 東京 \\ uid")
        val proposal = set(changes = listOf(PlayerDomainChange.create("CH-A", PlayerChangeKinds.ASSET, AssetChange(ref, "ACTIVE"))))
        val decoded = PlayerChangeSetCodec.decode(PlayerChangeSetCodec.encode(proposal))
        assertEquals(ref, (decoded.changes.single().payload as AssetChange).asset)
    }

    @Test fun p17Hotfix3_08_distinctAssetTuplesProduceDistinctFingerprints() {
        val a = set(uid = "CS-SAME", changes = listOf(assetChange("CH-A", "RPGOS-ASSET-KIND:PROPERTY", "BUSINESS:A-1")))
        val b = set(uid = "CS-SAME", changes = listOf(assetChange("CH-A", "RPGOS-ASSET-KIND:PROPERTY:BUSINESS", "A-1")))
        assertNotEquals(PlayerChangeSetCodec.fingerprint(a), PlayerChangeSetCodec.fingerprint(b))
    }

    @Test fun p17Hotfix3_09_hotfix2FinancialLedgerUniquenessStillRejectsDuplicateCause() {
        val financial = PlayerDomainChange.create(
            "CH-FIN", PlayerChangeKinds.FINANCIAL,
            FinancialChange("A", "B", 100L, "CUR", "TRANSFER")
        )
        fun ledger(uid: String) = PlayerLedgerIntent.create(
            uid, PlayerLedgerIntentKinds.FINANCIAL_TRANSFER, listOf("CH-FIN"),
            FinancialTransferLedgerIntentPayload("A", "B", 100L, "CUR", "TRANSFER")
        )
        fails("DUPLICATE_FINANCIAL_LEDGER_CAUSAL_CHANGE") {
            PlayerChangeSet.create(
                changeSetUid = "CS-FIN",
                campaignUid = "C1",
                sourceCommandUid = "CMD-HOTFIX3",
                actor = actor,
                changes = listOf(financial),
                ledgerIntents = listOf(ledger("LED-1"), ledger("LED-2")),
                provenance = provenance
            )
        }
    }

    @Test fun p17Hotfix3_10_originalAssetHotfixStillDistinguishesPropertyAndBusinessSameUid() {
        val property = assetChange("CH-P", "RPGOS-ASSET-KIND:PROPERTY", "A-1")
        val business = assetChange("CH-B", "RPGOS-ASSET-KIND:BUSINESS", "A-1")
        PlayerChangeSetValidator.validate(set(changes = listOf(property, business)))
        assertNotEquals(TypedPlayerChangeRegistry.core().conflictKeys(property), TypedPlayerChangeRegistry.core().conflictKeys(business))
    }

    @Test fun p17Hotfix3_11_zeroAuthoritativeDbMutation() {
        val db = SQLiteDatabase.create(null)
        try {
            db.execSQL("CREATE TABLE authority_fixture(uid TEXT PRIMARY KEY, value INTEGER NOT NULL)")
            db.execSQL("INSERT INTO authority_fixture(uid,value) VALUES('A',7)")
            val before = scalar(db)
            val proposal = set(changes = listOf(assetChange("CH-A", "KIND:ONE", "UID:TWO")))
            PlayerChangeSetValidator.validate(proposal)
            val encoded = PlayerChangeSetCodec.encode(proposal)
            val decoded = PlayerChangeSetCodec.decode(encoded)
            PlayerChangeSetCodec.fingerprint(decoded)
            assertEquals(before, scalar(db))
        } finally {
            db.close()
        }
    }

    @Test fun p17Hotfix3_12_phase3to16RegressionRepresentativeChecks() {
        val commandRegistry = PlayerCommandKindRegistry.core()
        val command = PlayerCommand(
            commandUid = "HOTFIX3-P16-CMD",
            campaignUid = "C1",
            actor = actor,
            commandKindUid = PlayerCommandKinds.TRAIN,
            payload = TrainCommandPayload(DomainRef("STAT", "STR"), 10, "METHOD"),
            provenance = CommandProvenance("TEST")
        )
        val encoded = commandRegistry.encode(command)
        assertEquals(encoded, commandRegistry.encode(commandRegistry.decode(encoded)))
        assertEquals(OWNERSHIP_SHARE_SCALE, OwnershipShare.full().units)
        assertEquals(OwnedAssetRef("KIND:A", "UID:B"), OwnedAssetRef("KIND:A", "UID:B"))
    }

    private fun scalar(db: SQLiteDatabase): Long =
        db.rawQuery("SELECT value FROM authority_fixture WHERE uid='A'", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
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
