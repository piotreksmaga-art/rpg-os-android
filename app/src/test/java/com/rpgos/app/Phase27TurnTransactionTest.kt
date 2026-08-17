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
class Phase27TurnTransactionTest{
 private lateinit var file:File
 @Before fun setup(){file=File.createTempFile("p27-",".db").also{it.delete()}}
 @After fun tearDown(){file.delete()}

 @Test fun P27_01_failureAfterFirstWriteRollsBack(){db().use{d->GroupATransactionTestFixtures.setupFinance(d);val p=GroupATransactionTestFixtures.admittedFinancialProposal(commandUid="CMD-A");val tx=TurnTransactionBoundary.create(d,id("CMD-A","TX-A"),p,TurnFailureInjector{if(it==TurnFailurePoint.AFTER_FIRST_WRITE)error("boom")});assertTrue(runCatching{tx.commit()}.isFailure);assertEquals(100L,FinancialStore(d,"C1").balance("A"));assertEquals(0L,receiptCount(d));assertEquals(TurnTransactionState.ROLLED_BACK,tx.state)}}

 @Test fun P27_02_failureAfterSecondDomainWriteRollsBackBoth(){db().use{d->GroupATransactionTestFixtures.setupFinance(d);val p=GroupATransactionTestFixtures.admittedFinancialProposal(commandUid="CMD-B",changeCount=2);val tx=TurnTransactionBoundary.create(d,id("CMD-B","TX-B"),p,TurnFailureInjector{if(it==TurnFailurePoint.AFTER_SECOND_DOMAIN_WRITE)error("boom")});assertTrue(runCatching{tx.commit()}.isFailure);assertEquals(100L,FinancialStore(d,"C1").balance("A"));assertEquals(0L,receiptCount(d))}}

 @Test fun P27_03_nestedOuterTransactionRejected(){db().use{d->GroupATransactionTestFixtures.setupFinance(d);val p=GroupATransactionTestFixtures.admittedFinancialProposal(commandUid="CMD-C");val tx=TurnTransactionBoundary.create(d,id("CMD-C","TX-C"),p);d.beginTransaction();try{assertTrue(runCatching{tx.commit()}.isFailure)}finally{d.endTransaction()}}}

 @Test fun P27_04_successCommitsExactlyOnce(){db().use{d->GroupATransactionTestFixtures.setupFinance(d);val p=GroupATransactionTestFixtures.admittedFinancialProposal(commandUid="CMD-D");val tx=TurnTransactionBoundary.create(d,id("CMD-D","TX-D"),p);assertTrue(tx.commit() is TurnExecutionResult.Committed);assertEquals(95L,FinancialStore(d,"C1").balance("A"));assertEquals(5L,FinancialStore(d,"C1").balance("B"));assertEquals(1L,receiptCount(d));assertTrue(runCatching{tx.commit()}.isFailure)}}

 @Test fun P27_05_crossCampaignRejectedBeforeBegin(){db().use{d->val p=GroupATransactionTestFixtures.admittedFinancialProposal(commandUid="CMD-E");assertTrue(runCatching{TurnTransactionBoundary.create(d,TurnTransactionIdentity("C2","TURN","CMD-E","TX"),p)}.isFailure);assertFalse(d.inTransaction())}}
 @Test fun P27_06_commandMismatchRejectedBeforeBegin(){db().use{d->val p=GroupATransactionTestFixtures.admittedFinancialProposal(commandUid="CMD-F");assertTrue(runCatching{TurnTransactionBoundary.create(d,TurnTransactionIdentity("C1","TURN","OTHER","TX"),p)}.isFailure);assertFalse(d.inTransaction())}}

 private fun id(command:String,tx:String)=TurnTransactionIdentity("C1","TURN-$command",command,tx)
 private fun db()=SQLiteDatabase.openOrCreateDatabase(file,null)
 private fun receiptCount(d:SQLiteDatabase)=d.rawQuery("SELECT COUNT(*) FROM turn_transaction_receipts",null).use{it.moveToFirst();it.getLong(0)}
}
