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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class FinancialConcurrencyTest {
    private lateinit var f:File
    @Before fun setUp(){f=File.createTempFile("finance-race-",".db");f.delete()}
    @After fun tearDown(){f.delete()}
    private fun owner(uid:String)=OwnershipOwnerRef("CHARACTER",uid)
    private fun seed(balance:Long=100L){SQLiteDatabase.openOrCreateDatabase(f,null).use{d->
        CurrentSchema.ensure(d,"C");val r=OwnershipReferenceRegistry(d,"C");listOf("A","B","C").forEach{r.registerOwner(owner(it),"race")};val s=FinancialStore(d,"C");s.registerCurrency(CurrencyDefinition("CUR","cur","Currency",1,"race"));listOf("A","B","C").forEach{s.openAccount(FinancialAccount("C","ACC-$it",owner(it),FINANCIAL_ACCOUNT_TYPE_DEFAULT,"CUR",0,"race"))};if(balance>0)s.creditExternal("SEED","ACC-A",balance,1,"seed","race",commandUid="SEED")
    }}
    private data class Race(val ok:Int,val bad:Int)
    private fun race(a:(FinancialStore)->Unit,b:(FinancialStore)->Unit):Race{
        val d1=SQLiteDatabase.openDatabase(f.absolutePath,null,SQLiteDatabase.OPEN_READWRITE);val d2=SQLiteDatabase.openDatabase(f.absolutePath,null,SQLiteDatabase.OPEN_READWRITE);val s1=FinancialStore(d1,"C");val s2=FinancialStore(d2,"C")
        val ready=CountDownLatch(2);val go=CountDownLatch(1);val ok=AtomicInteger();val bad=AtomicInteger();val pool=Executors.newFixedThreadPool(2)
        fun submit(s:FinancialStore,op:(FinancialStore)->Unit)=pool.submit{ready.countDown();go.await();try{op(s);ok.incrementAndGet()}catch(_:Throwable){bad.incrementAndGet()}}
        val x=submit(s1,a);val y=submit(s2,b);assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();x.get(15,TimeUnit.SECONDS);y.get(15,TimeUnit.SECONDS);pool.shutdownNow();d1.close();d2.close();return Race(ok.get(),bad.get())
    }

    @Test fun finRace01And02CompetingDebitsAndTransfersCannotDoubleSpend(){seed();val r=race(
        {it.transfer("AB","ACC-A","ACC-B",80,2,"ab","race")},
        {it.transfer("AC","ACC-A","ACC-C",80,2,"ac","race")}
    );assertEquals(1,r.ok);assertEquals(1,r.bad);checkDb{d,s->assertEquals(20L,s.balance("ACC-A"));assertEquals(80L,s.balance("ACC-B")+s.balance("ACC-C"));assertEquals(2L,s.historyCount());checks(d)}}

    @Test fun finRace03StaleBalanceReadCannotAuthorizeSecondSpend(){seed();SQLiteDatabase.openDatabase(f.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{d->assertEquals(100L,FinancialStore(d,"C").balance("ACC-A"))};val r=race(
        {it.debitExternal("D1","ACC-A",70,2,"expense","race")},
        {it.debitExternal("D2","ACC-A",70,2,"expense","race")}
    );assertEquals(1,r.ok);assertEquals(1,r.bad);checkDb{d,s->assertEquals(30L,s.balance("ACC-A"));assertEquals(2L,s.historyCount());checks(d)}}

    @Test fun finRace04DuplicateTransactionAndCommandRaceAppliesOnce(){seed();val r=race(
        {it.transfer("SAME","ACC-A","ACC-B",25,2,"same","race",commandUid="CMD-SAME")},
        {it.transfer("SAME","ACC-A","ACC-B",25,2,"same","race",commandUid="CMD-SAME")}
    );assertEquals(2,r.ok);assertEquals(0,r.bad);checkDb{d,s->assertEquals(75L,s.balance("ACC-A"));assertEquals(25L,s.balance("ACC-B"));assertEquals(2L,s.historyCount());checks(d)}}

    @Test fun finRace05AccountCloseVersusCreditHasOneCoherentWinner(){seed(0);val r=race(
        {it.creditExternal("CREDIT","ACC-B",10,2,"income","race")},
        {it.closeAccount("ACC-B",2)}
    );assertEquals(1,r.ok);assertEquals(1,r.bad);checkDb{d,s->val balance=runCatching{s.balance("ACC-B")}.getOrElse{0L};assertTrue(balance==0L||balance==10L);checks(d)}}

    @Test fun finRace06ConcurrentCreditsCannotOverflow(){seed(0);SQLiteDatabase.openDatabase(f.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{d->FinancialStore(d,"C").creditExternal("BIG","ACC-B",Long.MAX_VALUE-5,1,"big","race")};val r=race(
        {it.creditExternal("X","ACC-B",4,2,"x","race")},
        {it.creditExternal("Y","ACC-B",4,2,"y","race")}
    );assertEquals(1,r.ok);assertEquals(1,r.bad);checkDb{d,s->assertEquals(Long.MAX_VALUE-1,s.balance("ACC-B"));assertEquals(Long.MAX_VALUE-1,s.reconcile("ACC-B"));checks(d)}}

    private fun checkDb(block:(SQLiteDatabase,FinancialStore)->Unit){SQLiteDatabase.openDatabase(f.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{d->block(d,FinancialStore(d,"C"))}}
    private fun checks(d:SQLiteDatabase){d.rawQuery("PRAGMA integrity_check",null).use{c->c.moveToFirst();assertEquals("ok",c.getString(0))};d.rawQuery("PRAGMA foreign_key_check",null).use{c->assertFalse(c.moveToFirst())}}
}
