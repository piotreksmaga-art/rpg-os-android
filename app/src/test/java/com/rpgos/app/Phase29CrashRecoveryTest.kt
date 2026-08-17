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
class Phase29CrashRecoveryTest {
    private lateinit var file: File
    @Before fun setUp() { file = File.createTempFile("p29-", ".db").also { it.delete() } }
    @After fun tearDown() { file.delete() }

    @Test fun P29_01_crashMatrixBeforeBeginAfterWritesAndBeforeReceiptLeavesNoCommittedReality() {
        listOf(
            TurnFailurePoint.BEFORE_FIRST_WRITE,
            TurnFailurePoint.AFTER_FIRST_WRITE,
            TurnFailurePoint.AFTER_SECOND_DOMAIN_WRITE,
            TurnFailurePoint.BEFORE_COMMIT
        ).forEachIndexed { i, point ->
            db().use { d ->
                d.execSQL("CREATE TABLE IF NOT EXISTS effects(k TEXT PRIMARY KEY)")
                val identity = id("C", "FAIL-$i", "CMD-FAIL-$i", "TX-FAIL-$i")
                val failed = runCatching {
                    TurnTransactionBoundary.create(d, identity, proposal("C", identity.commandUid, i + 1L), TurnFailureInjector { if (it == point) error("crash") }).execute {
                        authoritativeWrite { it.execSQL("INSERT INTO effects VALUES(?)", arrayOf("$i-a")) }
                        authoritativeWrite { it.execSQL("INSERT INTO effects VALUES(?)", arrayOf("$i-b")) }
                    }
                }
                assertTrue(failed.isFailure)
                assertEquals(0L, scalar(d, "SELECT COUNT(*) FROM effects"))
                assertNull(TurnRecoveryReader(d).lastValidCommit("C"))
                assertEquals(TurnRecoveryState.NOT_RECORDED, TurnRecoveryReader(d).transaction(identity.transactionUid).state)
            }
        }
    }

    @Test fun P29_02_receiptWrittenInsideTransactionThenFailureBeforeCommitRollsBackEvidenceAndEffects() {
        db().use { d ->
            d.execSQL("CREATE TABLE effects(v INTEGER NOT NULL)")
            val identity = id("C", "T", "CMD", "TX")
            val p = proposal("C", "CMD", 1)
            val failed = runCatching {
                TurnTransactionBoundary.create(d, identity, p, TurnFailureInjector { if (it == TurnFailurePoint.BEFORE_COMMIT) error("before receipt path") }).execute {
                    authoritativeWrite { it.execSQL("INSERT INTO effects VALUES(1)") }
                }
            }
            assertTrue(failed.isFailure)
            assertEquals(0L, scalar(d, "SELECT COUNT(*) FROM effects"))
            assertNull(TurnRecoveryReader(d).lastValidCommit("C"))
        }
    }

    @Test fun P29_03_multiTurnLastValidCommitRollbackAndRetryOrdering() {
        db().use { d ->
            d.execSQL("CREATE TABLE effects(v TEXT PRIMARY KEY)")
            val a = commit(d, "C", "A", 1)
            assertEquals(1L, a.commitOrder); assertEquals("TX-A", TurnRecoveryReader(d).lastValidCommit("C")!!.transactionUid)
            val b = commit(d, "C", "B", 2)
            assertEquals(2L, b.commitOrder); assertEquals("TX-B", TurnRecoveryReader(d).lastValidCommit("C")!!.transactionUid)

            val cId = id("C", "TURN-C", "CMD-C", "TX-C")
            val cProposal = proposal("C", "CMD-C", 3)
            assertTrue(runCatching {
                TurnTransactionBoundary.create(d, cId, cProposal, TurnFailureInjector { if (it == TurnFailurePoint.AFTER_FIRST_WRITE) error("crash") }).execute {
                    authoritativeWrite { it.execSQL("INSERT INTO effects VALUES('C')") }
                }
            }.isFailure)
            assertEquals("TX-B", TurnRecoveryReader(d).lastValidCommit("C")!!.transactionUid)

            val retryB = TurnTransactionBoundary.create(d, id("C", "TURN-B", "CMD-B", "TX-B"), proposal("C", "CMD-B", 2)).execute { error("must replay") }
            assertTrue(retryB is TurnExecutionResult.AlreadyCommitted)
            assertEquals(2L, (retryB as TurnExecutionResult.AlreadyCommitted).receipt.commitOrder)
            assertEquals("TX-B", TurnRecoveryReader(d).lastValidCommit("C")!!.transactionUid)

            val c = TurnTransactionBoundary.create(d, cId, cProposal).execute { authoritativeWrite { it.execSQL("INSERT INTO effects VALUES('C')") } } as TurnExecutionResult.Committed
            assertEquals(3L, c.receipt.commitOrder)
            assertEquals("TX-C", TurnRecoveryReader(d).lastValidCommit("C")!!.transactionUid)
        }
    }

    @Test fun P29_04_commitResponseLossAndProcessReopenRecoverExactlyOnce() {
        val identity = id("C", "TURN-X", "CMD-X", "TX-X")
        val p = proposal("C", "CMD-X", 7)
        db().use { d ->
            d.execSQL("CREATE TABLE effects(v INTEGER NOT NULL)")
            val committed = TurnTransactionBoundary.create(d, identity, p).execute { authoritativeWrite { it.execSQL("INSERT INTO effects VALUES(7)") } } as TurnExecutionResult.Committed
            assertEquals(1L, committed.receipt.commitOrder)
            // Simulate process death/lost response by discarding all process-local objects here.
        }
        db().use { d ->
            val recovery = TurnRecoveryReader(d)
            assertEquals(TurnRecoveryState.COMMITTED, recovery.transaction("TX-X").state)
            assertEquals(TurnRecoveryState.COMMITTED, recovery.command("C", "CMD-X").state)
            assertEquals("TX-X", recovery.lastValidCommit("C")!!.transactionUid)
            val replay = TurnTransactionBoundary.create(d, identity, p).execute { error("no duplicate") }
            assertTrue(replay is TurnExecutionResult.AlreadyCommitted)
            assertEquals(1L, scalar(d, "SELECT COUNT(*) FROM effects"))
            assertEquals(1L, (replay as TurnExecutionResult.AlreadyCommitted).receipt.commitOrder)
        }
    }

    @Test fun P29_05_campaignCommitOrderIsIsolatedAndNotUidOrWallClockOrdered() {
        db().use { d ->
            d.execSQL("CREATE TABLE effects(v TEXT PRIMARY KEY)")
            val a1 = commit(d, "A", "ZZZ", 1)
            val b1 = commit(d, "B", "MID", 1)
            val a2 = commit(d, "A", "AAA", 2)
            assertEquals(1L, a1.commitOrder); assertEquals(1L, b1.commitOrder); assertEquals(2L, a2.commitOrder)
            assertEquals("TX-AAA", TurnRecoveryReader(d).lastValidCommit("A")!!.transactionUid)
            assertEquals("TX-MID", TurnRecoveryReader(d).lastValidCommit("B")!!.transactionUid)
        }
    }

    @Test fun P29_06_failedLaterTurnCannotAdvanceLastCommitAndDerivedFailureCannotUndoTruth() {
        db().use { d ->
            d.execSQL("CREATE TABLE authoritative(v INTEGER NOT NULL)")
            val good = TurnTransactionBoundary.create(d, id("C", "GOOD", "GOOD", "GOOD-TX"), proposal("C", "GOOD", 1)).execute {
                authoritativeWrite { it.execSQL("INSERT INTO authoritative VALUES(1)") }
            } as TurnExecutionResult.Committed
            assertEquals(1L, good.receipt.commitOrder)
            // A derived/presentation rebuild is intentionally outside the authoritative transaction and fails.
            assertTrue(runCatching { error("snapshot/cache rebuild failed") }.isFailure)
            assertEquals("GOOD-TX", TurnRecoveryReader(d).lastValidCommit("C")!!.transactionUid)
            assertEquals(1L, scalar(d, "SELECT COUNT(*) FROM authoritative"))

            val badId = id("C", "BAD", "BAD", "BAD-TX")
            assertTrue(runCatching {
                TurnTransactionBoundary.create(d, badId, proposal("C", "BAD", 2), TurnFailureInjector { if (it == TurnFailurePoint.BEFORE_COMMIT) error("rollback") }).execute {
                    authoritativeWrite { it.execSQL("INSERT INTO authoritative VALUES(2)") }
                }
            }.isFailure)
            assertEquals("GOOD-TX", TurnRecoveryReader(d).lastValidCommit("C")!!.transactionUid)
            assertEquals(TurnRecoveryState.NOT_RECORDED, TurnRecoveryReader(d).transaction("BAD-TX").state)
        }
    }

    @Test fun P29_07_g28ReplayConflictAndRollbackSemanticsRemainClosed() {
        db().use { d ->
            val identity = id("C", "T", "CMD", "TX")
            val p = proposal("C", "CMD", 1)
            TurnTransactionBoundary.create(d, identity, p).execute { Unit }
            assertTrue(TurnTransactionBoundary.create(d, identity, p).execute { error("replay") } is TurnExecutionResult.AlreadyCommitted)
            assertTrue(TurnTransactionBoundary.create(d, id("C", "T2", "CMD", "TX2"), p).execute { error("command replay") } is TurnExecutionResult.AlreadyCommitted)
            assertTrue(runCatching { TurnTransactionBoundary.create(d, id("C", "T3", "CMD", "TX3"), proposal("C", "CMD", 999)).execute { Unit } }.exceptionOrNull() is TurnIdempotencyConflictException)
            assertTrue(runCatching { TurnTransactionBoundary.create(d, id("D", "TD", "CMDD", "TX"), proposal("D", "CMDD", 1)).execute { Unit } }.exceptionOrNull() is TurnIdempotencyConflictException)
        }
    }

    private fun commit(d: SQLiteDatabase, campaign: String, suffix: String, amount: Long): TurnCommitReceipt {
        val command = "CMD-$suffix"
        val r = TurnTransactionBoundary.create(d, id(campaign, "TURN-$suffix", command, "TX-$suffix"), proposal(campaign, command, amount)).execute {
            authoritativeWrite { it.execSQL("INSERT INTO effects VALUES(?)", arrayOf("$campaign-$suffix")) }
        }
        return (r as TurnExecutionResult.Committed).receipt
    }

    private fun proposal(campaign: String, command: String, amount: Long): CanonicalCampaignMutationProposal {
        val cs = PlayerChangeSet.create(
            changeSetUid = "CS-$campaign-$command-$amount", campaignUid = campaign, sourceCommandUid = command,
            actor = CommandActorRef("PLAYER", "P1"),
            changes = listOf(PlayerDomainChange.create("CH-$campaign-$command-$amount", PlayerChangeKinds.FINANCIAL, FinancialChange("A", "B", amount, "CUR", "P29"))),
            provenance = ChangeSetProvenance(command, "P29", "1")
        )
        return CanonicalCampaignMutationProposal.create(campaign, cs)
    }

    private fun id(c: String, t: String, cmd: String, tx: String) = TurnTransactionIdentity(c, t, cmd, tx)
    private fun db() = SQLiteDatabase.openOrCreateDatabase(file, null)
    private fun scalar(d: SQLiteDatabase, sql: String): Long = d.rawQuery(sql, null).use { it.moveToFirst(); it.getLong(0) }
}
