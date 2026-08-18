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
@Config(sdk=[34])
class Phase28TurnIdempotencyTest{
 private lateinit var file:File
 @Before fun setUp(){file=File.createTempFile("p28-",".db").also{it.delete()}}
 @After fun tearDown(){file.delete()}

 @Test fun P28_01_committedRetryDoesNotRepeatAuthoritativeEffect(){db().use{d->GroupATransactionTestFixtures.setupFinance(d);val p=GroupATransactionTestFixtures.admittedFinancialProposal(commandUid="CMD-1");val id=id("CMD-1","TX-1");assertTrue(TurnTransactionBoundary.create(d,id,p).commit() is TurnExecutionResult.Committed);assertEquals(95L,FinancialStore(d,"C1").balance("A"));val retry=TurnTransactionBoundary.create(d,id,p).commit();assertTrue(retry is TurnExecutionResult.AlreadyCommitted);assertEquals(95L,FinancialStore(d,"C1").balance("A"));assertEquals(1L,receiptCount(d))}}

 @Test fun P28_02_sameCommandSameSemanticsNewTransactionIsReplay(){db().use{d->GroupATransactionTestFixtures.setupFinance(d);val p=GroupATransactionTestFixtures.admittedFinancialProposal(commandUid="CMD-2");val first=TurnTransactionBoundary.create(d,id("CMD-2","TX-2A"),p).commit() as TurnExecutionResult.Committed;val retry=TurnTransactionBoundary.create(d,TurnTransactionIdentity("C1","TURN-2B","CMD-2","TX-2B"),p).commit() as TurnExecutionResult.AlreadyCommitted;assertEquals(first.receipt,retry.receipt);assertEquals("TX-2A",retry.receipt.transactionUid);assertEquals(95L,FinancialStore(d,"C1").balance("A"));assertEquals(1L,receiptCount(d));assertEquals(1L,eventCount(d,"TX-2A"));assertEquals(0L,eventCount(d,"TX-2B"));assertEquals(0L,causalCount(d,"TX-2B"))}}

 @Test fun P28_03_rollbackLeavesNoDedupeAndRetryMayCommit(){db().use{d->GroupATransactionTestFixtures.setupFinance(d);val p=GroupATransactionTestFixtures.admittedFinancialProposal(commandUid="CMD-3");val id=id("CMD-3","TX-3");assertTrue(runCatching{TurnTransactionBoundary.create(d,id,p,TurnFailureInjector{if(it==TurnFailurePoint.AFTER_FIRST_WRITE)error("fail")}).commit()}.isFailure);assertEquals(100L,FinancialStore(d,"C1").balance("A"));assertEquals(0L,receiptCount(d));assertTrue(TurnTransactionBoundary.create(d,id,p).commit() is TurnExecutionResult.Committed);assertEquals(95L,FinancialStore(d,"C1").balance("A"));assertEquals(1L,receiptCount(d))}}

 @Test fun P28_04_sameCommandChangedSemanticsFailsClosed(){db().use{d->GroupATransactionTestFixtures.setupFinance(d);val original=GroupATransactionTestFixtures.admittedFinancialProposal(commandUid="CMD-4",amountMinor=5);TurnTransactionBoundary.create(d,id("CMD-4","TX-4A"),original).commit();val changed=GroupATransactionTestFixtures.admittedFinancialProposal(commandUid="CMD-4",amountMinor=6);val f=runCatching{TurnTransactionBoundary.create(d,TurnTransactionIdentity("C1","TURN-X","CMD-4","TX-4B"),changed).commit()}.exceptionOrNull();assertTrue(f is TurnIdempotencyConflictException);assertEquals(95L,FinancialStore(d,"C1").balance("A"))}}

 @Test fun P28_05_sameTransactionChangedSemanticsFailsClosed(){db().use{d->GroupATransactionTestFixtures.setupFinance(d);val original=GroupATransactionTestFixtures.admittedFinancialProposal(commandUid="CMD-5",amountMinor=5);TurnTransactionBoundary.create(d,id("CMD-5","TX-5"),original).commit();val changed=GroupATransactionTestFixtures.admittedFinancialProposal(commandUid="CMD-5",amountMinor=7);assertTrue(runCatching{TurnTransactionBoundary.create(d,id("CMD-5","TX-5"),changed).commit()}.exceptionOrNull() is TurnIdempotencyConflictException)}}

 @Test fun P28_06_crossCampaignTransactionUidFailsClosed(){db().use{d->GroupATransactionTestFixtures.setupFinance(d,"C1");GameplayRuntimeBootstrap.initialize(d,"C2");val p1=GroupATransactionTestFixtures.admittedFinancialProposal("C1","CMD-A");TurnTransactionBoundary.create(d,TurnTransactionIdentity("C1","TA","CMD-A","TX-GLOBAL"),p1).commit();val p2=GroupATransactionTestFixtures.admittedFinancialProposal("C2","CMD-B");val f=runCatching{TurnTransactionBoundary.create(d,TurnTransactionIdentity("C2","TB","CMD-B","TX-GLOBAL"),p2).commit()}.exceptionOrNull();assertTrue(f is TurnIdempotencyConflictException)}}

 @Test fun P28_07_processReopenRetainsIdempotency(){val p=GroupATransactionTestFixtures.admittedFinancialProposal(commandUid="CMD-R");val identity=id("CMD-R","TX-R");db().use{d->GroupATransactionTestFixtures.setupFinance(d);TurnTransactionBoundary.create(d,identity,p).commit()};db().use{d->val replay=TurnTransactionBoundary.create(d,identity,p).commit();assertTrue(replay is TurnExecutionResult.AlreadyCommitted);assertEquals(95L,FinancialStore(d,"C1").balance("A"));assertEquals(1L,receiptCount(d))}}

 @Test fun P28_08_receiptFingerprintAndOrderStableOnReplay(){db().use{d->GroupATransactionTestFixtures.setupFinance(d);val p=GroupATransactionTestFixtures.admittedFinancialProposal(commandUid="CMD-FP");val identity=id("CMD-FP","TX-FP");val first=TurnTransactionBoundary.create(d,identity,p).commit() as TurnExecutionResult.Committed;val replay=TurnTransactionBoundary.create(d,identity,p).commit() as TurnExecutionResult.AlreadyCommitted;assertEquals(first.receipt,replay.receipt);assertEquals(PlayerChangeSetCodec.fingerprint(p.playerChangeSet),first.receipt.semanticFingerprint);assertEquals(1L,first.receipt.commitOrder)}}

 private fun id(command:String,tx:String)=TurnTransactionIdentity("C1","TURN-$command",command,tx)
 private fun db()=SQLiteDatabase.openOrCreateDatabase(file,null)
 private fun receiptCount(d:SQLiteDatabase)=d.rawQuery("SELECT COUNT(*) FROM turn_transaction_receipts",null).use{it.moveToFirst();it.getLong(0)}
 private fun eventCount(d:SQLiteDatabase,tx:String)=d.rawQuery("SELECT COUNT(*) FROM canonical_gameplay_events WHERE transaction_uid=?",arrayOf(tx)).use{it.moveToFirst();it.getLong(0)}
 private fun causalCount(d:SQLiteDatabase,tx:String)=d.rawQuery("SELECT COUNT(*) FROM canonical_causal_relations WHERE transaction_uid=?",arrayOf(tx)).use{it.moveToFirst();it.getLong(0)}
}
