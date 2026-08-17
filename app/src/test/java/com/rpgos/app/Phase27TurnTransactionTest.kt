package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Phase27TurnTransactionTest {
 private lateinit var file:File
 @Before fun setup(){file=File.createTempFile("p27-",".db").also{it.delete()}}
 @After fun tearDown(){file.delete()}
 @Test fun P27_01_failureAfterFirstWriteRollsBack(){db().use{d->d.execSQL("CREATE TABLE t(k TEXT PRIMARY KEY,v INTEGER)");val tx=TurnTransaction(d,id(),TurnFailureInjector{if(it==TurnFailurePoint.AFTER_FIRST_WRITE)error("boom")});runCatching{tx.execute{authoritativeWrite{it.execSQL("INSERT INTO t VALUES('a',1)")}}};assertEquals(0,scalar(d,"SELECT COUNT(*) FROM t"));assertEquals(TurnTransactionState.ROLLED_BACK,tx.state)}}
 @Test fun P27_02_failureAfterSecondDomainWriteRollsBackBoth(){db().use{d->d.execSQL("CREATE TABLE a(v INTEGER)");d.execSQL("CREATE TABLE b(v INTEGER)");val tx=TurnTransaction(d,id(),TurnFailureInjector{if(it==TurnFailurePoint.AFTER_SECOND_DOMAIN_WRITE)error("boom")});runCatching{tx.execute{authoritativeWrite{it.execSQL("INSERT INTO a VALUES(1)")};authoritativeWrite{it.execSQL("INSERT INTO b VALUES(2)")}}};assertEquals(0,scalar(d,"SELECT COUNT(*) FROM a"));assertEquals(0,scalar(d,"SELECT COUNT(*) FROM b"))}}
 @Test fun P27_03_nestedOuterTransactionRejected(){db().use{d->d.beginTransaction();try{val tx=TurnTransaction(d,id());assertTrue(runCatching{tx.execute{}}.isFailure)}finally{d.endTransaction()}}}
 @Test fun P27_04_successCommitsExactlyOnce(){db().use{d->d.execSQL("CREATE TABLE t(v INTEGER)");val tx=TurnTransaction(d,id());tx.execute{authoritativeWrite{it.execSQL("INSERT INTO t VALUES(1)")}};assertEquals(1,scalar(d,"SELECT COUNT(*) FROM t"));assertEquals(TurnTransactionState.COMMITTED,tx.state);assertTrue(runCatching{tx.execute{}}.isFailure)}}
 @Test fun P27_05_crossCampaignRejectedBeforeBegin(){db().use{d->val proposal=proposal("C1","CMD");assertTrue(runCatching{TurnTransactionBoundary.create(d,TurnTransactionIdentity("C2","TURN","CMD","TX"),proposal)}.isFailure);assertFalse(d.inTransaction())}}
 @Test fun P27_06_commandMismatchRejectedBeforeBegin(){db().use{d->val proposal=proposal("C1","CMD");assertTrue(runCatching{TurnTransactionBoundary.create(d,TurnTransactionIdentity("C1","TURN","OTHER","TX"),proposal)}.isFailure);assertFalse(d.inTransaction())}}
 private fun proposal(c:String,cmd:String):CanonicalCampaignMutationProposal{val cs=PlayerChangeSet.create(changeSetUid="CS",campaignUid=c,sourceCommandUid=cmd,actor=CommandActorRef("PLAYER","P1"),changes=listOf(PlayerDomainChange.create("P27-FINANCIAL",PlayerChangeKinds.FINANCIAL,FinancialChange("ACCOUNT-A","ACCOUNT-B",1L,"CUR","P27-TEST"))),provenance=ChangeSetProvenance(cmd,"TEST","1"));return CanonicalCampaignMutationProposal.create(c,cs)}
 private fun id()=TurnTransactionIdentity("C1","TURN","CMD","TX")
 private fun db()=SQLiteDatabase.openOrCreateDatabase(file,null)
 private fun scalar(d:SQLiteDatabase,q:String)=d.rawQuery(q,null).use{it.moveToFirst();it.getLong(0)}
}
