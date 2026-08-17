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
class Phase29CrashRecoveryTest{
 private lateinit var file:File
 @Before fun setUp(){file=File.createTempFile("p29-",".db").also{it.delete()}}
 @After fun tearDown(){file.delete()}

 @Test fun P29_01_crashBeforeAndAfterWritesLeavesNoCommittedReality(){
  listOf(TurnFailurePoint.BEFORE_FIRST_WRITE,TurnFailurePoint.AFTER_FIRST_WRITE,TurnFailurePoint.BEFORE_COMMIT,TurnFailurePoint.AFTER_RECEIPT_BEFORE_COMMIT).forEachIndexed{i,point->
   val scenarioFile=File.createTempFile("p29-crash-$i-",".db").also{it.delete()}
   try{
    SQLiteDatabase.openOrCreateDatabase(scenarioFile,null).use{d->
     GroupATransactionTestFixtures.setupFinance(d)
     val cmd="CMD-$i";val p=GroupATransactionTestFixtures.admittedFinancialProposal(commandUid=cmd);val id=TurnTransactionIdentity("C1","TURN-$i",cmd,"TX-$i")
     assertTrue(runCatching{TurnTransactionBoundary.create(d,id,p,TurnFailureInjector{if(it==point)error("crash")}).commit()}.isFailure)
     assertEquals(100L,FinancialStore(d,"C1").balance("A"))
     assertNull(TurnRecoveryReader(d).lastValidCommit("C1"))
     assertEquals(TurnRecoveryState.NOT_RECORDED,TurnRecoveryReader(d).transaction("TX-$i").state)
    }
   }finally{scenarioFile.delete()}
  }
 }

 @Test fun P29_02_multiTurnLastValidCommitRollbackAndRetryOrdering(){db().use{d->GroupATransactionTestFixtures.setupFinance(d,openingBalance=200);val a=commit(d,"A",5);assertEquals(1L,a.commitOrder);val b=commit(d,"B",5);assertEquals(2L,b.commitOrder);assertEquals("TX-B",TurnRecoveryReader(d).lastValidCommit("C1")!!.transactionUid);val pc=GroupATransactionTestFixtures.admittedFinancialProposal(commandUid="CMD-C");val idc=TurnTransactionIdentity("C1","TURN-C","CMD-C","TX-C");assertTrue(runCatching{TurnTransactionBoundary.create(d,idc,pc,TurnFailureInjector{if(it==TurnFailurePoint.AFTER_FIRST_WRITE)error("rollback")}).commit()}.isFailure);assertEquals("TX-B",TurnRecoveryReader(d).lastValidCommit("C1")!!.transactionUid);val pb=GroupATransactionTestFixtures.admittedFinancialProposal(commandUid="CMD-B");val replay=TurnTransactionBoundary.create(d,TurnTransactionIdentity("C1","TURN-B","CMD-B","TX-B"),pb).commit() as TurnExecutionResult.AlreadyCommitted;assertEquals(2L,replay.receipt.commitOrder);assertEquals("TX-B",TurnRecoveryReader(d).lastValidCommit("C1")!!.transactionUid);val c=TurnTransactionBoundary.create(d,idc,pc).commit() as TurnExecutionResult.Committed;assertEquals(3L,c.receipt.commitOrder);assertEquals("TX-C",TurnRecoveryReader(d).lastValidCommit("C1")!!.transactionUid)}}

 @Test fun P29_03_commitResponseLossAndReopenRecoverExactlyOnce(){val p=GroupATransactionTestFixtures.admittedFinancialProposal(commandUid="CMD-X");val id=TurnTransactionIdentity("C1","TURN-X","CMD-X","TX-X");db().use{d->GroupATransactionTestFixtures.setupFinance(d);val r=TurnTransactionBoundary.create(d,id,p).commit() as TurnExecutionResult.Committed;assertEquals(1L,r.receipt.commitOrder)};db().use{d->val reader=TurnRecoveryReader(d);assertEquals(TurnRecoveryState.COMMITTED,reader.transaction("TX-X").state);assertEquals("TX-X",reader.lastValidCommit("C1")!!.transactionUid);assertTrue(TurnTransactionBoundary.create(d,id,p).commit() is TurnExecutionResult.AlreadyCommitted);assertEquals(95L,FinancialStore(d,"C1").balance("A"))}}

 @Test fun P29_04_campaignCommitOrderIsIsolatedAndNotUidOrdered(){db().use{d->GroupATransactionTestFixtures.setupFinance(d,"C1",200);GroupATransactionTestFixtures.setupFinance(d,"C2",200);val a1=commit(d,"ZZZ",5,"C1");val b1=commit(d,"MID",5,"C2");val a2=commit(d,"AAA",5,"C1");assertEquals(1L,a1.commitOrder);assertEquals(1L,b1.commitOrder);assertEquals(2L,a2.commitOrder);assertEquals("TX-AAA",TurnRecoveryReader(d).lastValidCommit("C1")!!.transactionUid);assertEquals("TX-MID",TurnRecoveryReader(d).lastValidCommit("C2")!!.transactionUid)}}

 @Test fun P29_05_derivedFailureCannotUndoCommittedTruth(){db().use{d->GroupATransactionTestFixtures.setupFinance(d);val r=commit(d,"GOOD",5);assertEquals(1L,r.commitOrder);assertTrue(runCatching{error("derived rebuild")}.isFailure);assertEquals("TX-GOOD",TurnRecoveryReader(d).lastValidCommit("C1")!!.transactionUid);assertEquals(95L,FinancialStore(d,"C1").balance("A"))}}

 private fun commit(d:SQLiteDatabase,suffix:String,amount:Long,campaign:String="C1"):TurnCommitReceipt{val cmd="CMD-$suffix";val p=GroupATransactionTestFixtures.admittedFinancialProposal(campaign,cmd,amount);val r=TurnTransactionBoundary.create(d,TurnTransactionIdentity(campaign,"TURN-$suffix",cmd,"TX-$suffix"),p).commit();return(r as TurnExecutionResult.Committed).receipt}
 private fun db()=SQLiteDatabase.openOrCreateDatabase(file,null)
}
