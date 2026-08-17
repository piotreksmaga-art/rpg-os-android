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
class Phase28TurnIdempotencyTest {
    private lateinit var file: File

    @Before fun setUp() { file = File.createTempFile("p28-", ".db").also { it.delete() } }
    @After fun tearDown() { file.delete() }

    @Test fun P28_01_sameCommittedTransactionRetriesAsAlreadyCommittedWithoutRepeatingEffects() {
        db().use { d ->
            d.execSQL("CREATE TABLE effects(v INTEGER NOT NULL)")
            val p = proposal("C1", "CMD-1", 1L)
            val identity = id("C1", "TURN-1", "CMD-1", "TX-1")
            val first = TurnTransactionBoundary.create(d, identity, p).execute {
                authoritativeWrite { it.execSQL("INSERT INTO effects VALUES(1)") }
            }
            assertTrue(first is TurnExecutionResult.Committed)
            val retry = TurnTransactionBoundary.create(d, identity, p).execute {
                error("committed retry must not execute mechanics")
            }
            assertTrue(retry is TurnExecutionResult.AlreadyCommitted)
            assertEquals(1L, scalar(d, "SELECT COUNT(*) FROM effects"))
            assertEquals(1L, receiptCount(d))
        }
    }

    @Test fun P28_02_sameCommandSameSemanticsWithNewTransactionUidDoesNotRepeatEffects() {
        db().use { d ->
            d.execSQL("CREATE TABLE effects(v INTEGER NOT NULL)")
            val p = proposal("C1", "CMD-2", 2L)
            TurnTransactionBoundary.create(d, id("C1", "TURN-2", "CMD-2", "TX-2A"), p).execute {
                authoritativeWrite { it.execSQL("INSERT INTO effects VALUES(1)") }
            }
            val retry = TurnTransactionBoundary.create(d, id("C1", "TURN-2B", "CMD-2", "TX-2B"), p).execute {
                authoritativeWrite { it.execSQL("INSERT INTO effects VALUES(2)") }
            }
            assertTrue(retry is TurnExecutionResult.AlreadyCommitted)
            assertEquals(1L, scalar(d, "SELECT COUNT(*) FROM effects"))
        }
    }

    @Test fun P28_03_retryAfterRollbackIsAllowedAndFailedTurnLeavesNoCommittedDedupeState() {
        db().use { d ->
            d.execSQL("CREATE TABLE effects(v INTEGER NOT NULL)")
            val p = proposal("C1", "CMD-3", 3L)
            val identity = id("C1", "TURN-3", "CMD-3", "TX-3")
            val failing = TurnTransactionBoundary.create(
                d, identity, p,
                TurnFailureInjector { if (it == TurnFailurePoint.BEFORE_COMMIT) error("injected") }
            )
            assertTrue(runCatching {
                failing.execute { authoritativeWrite { it.execSQL("INSERT INTO effects VALUES(1)") } }
            }.isFailure)
            assertEquals(0L, scalar(d, "SELECT COUNT(*) FROM effects"))
            assertEquals(0L, receiptCount(d))

            val retry = TurnTransactionBoundary.create(d, identity, p).execute {
                authoritativeWrite { it.execSQL("INSERT INTO effects VALUES(1)") }
            }
            assertTrue(retry is TurnExecutionResult.Committed)
            assertEquals(1L, scalar(d, "SELECT COUNT(*) FROM effects"))
            assertEquals(1L, receiptCount(d))
        }
    }

    @Test fun P28_04_sameCommandUidWithDifferentSemanticFingerprintFailsClosed() {
        db().use { d ->
            val original = proposal("C1", "CMD-4", 4L)
            TurnTransactionBoundary.create(d, id("C1", "TURN-4", "CMD-4", "TX-4"), original).execute { Unit }
            val changed = proposal("C1", "CMD-4", 999L)
            val failure = runCatching {
                TurnTransactionBoundary.create(d, id("C1", "TURN-4B", "CMD-4", "TX-4B"), changed).execute { Unit }
            }.exceptionOrNull()
            assertTrue(failure is TurnIdempotencyConflictException)
            assertEquals(TurnTransactionReceiptStore.COMMAND_SEMANTIC_FINGERPRINT_MISMATCH, (failure as TurnIdempotencyConflictException).code)
            assertEquals(1L, receiptCount(d))
        }
    }

    @Test fun P28_05_transactionUidCannotBeReusedAcrossCampaigns() {
        db().use { d ->
            val p1 = proposal("C1", "CMD-5A", 5L)
            TurnTransactionBoundary.create(d, id("C1", "TURN-5A", "CMD-5A", "TX-GLOBAL"), p1).execute { Unit }
            val p2 = proposal("C2", "CMD-5B", 5L)
            val failure = runCatching {
                TurnTransactionBoundary.create(d, id("C2", "TURN-5B", "CMD-5B", "TX-GLOBAL"), p2).execute { Unit }
            }.exceptionOrNull()
            assertTrue(failure is TurnIdempotencyConflictException)
            assertEquals(TurnTransactionReceiptStore.CROSS_CAMPAIGN_TRANSACTION_UID, (failure as TurnIdempotencyConflictException).code)
        }
    }

    @Test fun P28_06_duplicateInventoryRewardIsAppliedExactlyOnce() {
        db().use { d ->
            val inventory = InventoryStore(d, "C1")
            inventory.registerDefinitions(
                "WP",
                listOf(ItemDefinition("ITEM-1", "WP", "item-1", "Item One", storagePolicy = ItemStoragePolicy.STACKABLE, provenance = "P28"))
            )
            val p = proposal("C1", "CMD-INV", 6L)
            val identity = id("C1", "TURN-INV", "CMD-INV", "TX-INV")
            TurnTransactionBoundary.create(d, identity, p).execute {
                inventoryStore().addStack("P1", "ITEM-1", 1L, "P28-REWARD")
            }
            val retry = TurnTransactionBoundary.create(d, identity, p).execute {
                inventoryStore().addStack("P1", "ITEM-1", 1L, "P28-REWARD")
            }
            assertTrue(retry is TurnExecutionResult.AlreadyCommitted)
            assertEquals(1L, InventoryStore(d, "C1").typedStacks("P1").single().quantity)
        }
    }

    @Test fun P28_07_duplicateFinancialEffectIsAppliedExactlyOnce() {
        db().use { d ->
            CurrentSchema.ensure(d, "C1")
            val accountOwner = OwnershipOwnerRef("CHARACTER", "P1")
            OwnershipReferenceRegistry(d, "C1").registerOwner(accountOwner, "P28-finance-holder")
            val finance = FinancialStore(d, "C1")
            finance.registerCurrency(CurrencyDefinition("CUR", "coin", "Coin", 1L, "P28"))
            finance.registerTransactionType("P28-CREDIT", FinancialFlowKind.SOURCE, "P28")
            finance.openAccount(FinancialAccount("C1", "A", accountOwner, FINANCIAL_ACCOUNT_TYPE_DEFAULT, "CUR", 1L, "P28"))
            val p = proposal("C1", "CMD-FIN", 7L)
            val identity = id("C1", "TURN-FIN", "CMD-FIN", "TX-FIN")
            TurnTransactionBoundary.create(d, identity, p).execute {
                financialStore().creditExternal("FIN-OP", "A", 10L, 2L, "reward", "P28", commandUid = "CMD-FIN", transactionTypeUid = "P28-CREDIT")
            }
            val retry = TurnTransactionBoundary.create(d, identity, p).execute {
                financialStore().creditExternal("FIN-OP", "A", 10L, 2L, "reward", "P28", commandUid = "CMD-FIN", transactionTypeUid = "P28-CREDIT")
            }
            assertTrue(retry is TurnExecutionResult.AlreadyCommitted)
            assertEquals(10L, FinancialStore(d, "C1").balance("A"))
        }
    }

    @Test fun P28_08_duplicateOwnershipTransferIsAppliedExactlyOnce() {
        db().use { d ->
            CurrentSchema.ensure(d, "C1")
            val from = OwnershipOwnerRef("CHARACTER", "P1")
            val to = OwnershipOwnerRef("CHARACTER", "P2")
            val asset = OwnedAssetRef("ASSET", "A1")
            val refs = OwnershipReferenceRegistry(d, "C1")
            refs.registerAssetKind("ASSET", "P28-asset-kind")
            refs.registerOwner(from, "P28-owner-from")
            refs.registerOwner(to, "P28-owner-to")
            refs.registerAsset(asset, "P28-asset")
            val store = OwnershipStore(d, "C1")
            store.acquire(OwnershipRecord("C1", "OWN-START", from, asset, "OWNER", OwnershipShare.full(), 1L, sourceEventUid = "E0", provenance = "P28"))
            val p = proposal("C1", "CMD-OWN", 8L)
            val identity = id("C1", "TURN-OWN", "CMD-OWN", "TX-OWN")
            TurnTransactionBoundary.create(d, identity, p).execute {
                ownershipStore().fullTransfer("OWN-OP", from, to, asset, "OWNER", 2L, "E1", "P28")
            }
            val retry = TurnTransactionBoundary.create(d, identity, p).execute {
                ownershipStore().fullTransfer("OWN-OP", from, to, asset, "OWNER", 2L, "E1", "P28")
            }
            assertTrue(retry is TurnExecutionResult.AlreadyCommitted)
            val current = OwnershipStore(d, "C1").currentOwnership(asset)
            assertEquals(1, current.size)
            assertEquals(to, current.single().owner)
        }
    }

    @Test fun P28_09_idempotencySurvivesDatabaseReopenProcessRecreationSimulation() {
        val p = proposal("C1", "CMD-RESTART", 9L)
        val identity = id("C1", "TURN-RESTART", "CMD-RESTART", "TX-RESTART")
        db().use { d ->
            d.execSQL("CREATE TABLE effects(v INTEGER NOT NULL)")
            TurnTransactionBoundary.create(d, identity, p).execute {
                authoritativeWrite { it.execSQL("INSERT INTO effects VALUES(1)") }
            }
        }
        db().use { d ->
            val replay = TurnTransactionBoundary.create(d, identity, p).execute {
                authoritativeWrite { it.execSQL("INSERT INTO effects VALUES(2)") }
            }
            assertTrue(replay is TurnExecutionResult.AlreadyCommitted)
            assertEquals(1L, scalar(d, "SELECT COUNT(*) FROM effects"))
            assertEquals(1L, receiptCount(d))
        }
    }

    @Test fun P28_10_receiptFingerprintIsStableForCommittedReplay() {
        db().use { d ->
            val p = proposal("C1", "CMD-10", 10L)
            val identity = id("C1", "TURN-10", "CMD-10", "TX-10")
            val first = TurnTransactionBoundary.create(d, identity, p).execute { "result" } as TurnExecutionResult.Committed
            val replay = TurnTransactionBoundary.create(d, identity, p).execute { error("no") } as TurnExecutionResult.AlreadyCommitted
            assertEquals(first.receipt, replay.receipt)
            assertTrue(first.receipt.resultFingerprint.isNotBlank())
            assertEquals(PlayerChangeSetCodec.fingerprint(p.playerChangeSet), first.receipt.semanticFingerprint)
        }
    }

    private fun proposal(campaign: String, command: String, amount: Long): CanonicalCampaignMutationProposal {
        val changeSet = PlayerChangeSet.create(
            changeSetUid = "CS-$campaign-$command-$amount",
            campaignUid = campaign,
            sourceCommandUid = command,
            actor = CommandActorRef("PLAYER", "P1"),
            changes = listOf(
                PlayerDomainChange.create(
                    "CHANGE-$campaign-$command-$amount",
                    PlayerChangeKinds.FINANCIAL,
                    FinancialChange("ACCOUNT-A", "ACCOUNT-B", amount, "CUR", "P28-TEST")
                )
            ),
            provenance = ChangeSetProvenance(command, "P28-TEST", "1")
        )
        return CanonicalCampaignMutationProposal.create(campaign, changeSet)
    }

    private fun id(campaign: String, turn: String, command: String, transaction: String) =
        TurnTransactionIdentity(campaign, turn, command, transaction)

    private fun db() = SQLiteDatabase.openOrCreateDatabase(file, null)
    private fun receiptCount(db: SQLiteDatabase) = scalar(db, "SELECT COUNT(*) FROM turn_transaction_receipts")
    private fun scalar(db: SQLiteDatabase, sql: String): Long = db.rawQuery(sql, null).use { it.moveToFirst(); it.getLong(0) }
}
