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
class OwnershipConcurrencyTest {
    private lateinit var f:File
    @Before fun setUp(){f=File.createTempFile("ownership-race-",".db");f.delete()}
    @After fun tearDown(){f.delete()}
    private fun owner(uid:String)=OwnershipOwnerRef("CHARACTER",uid)
    private val asset=OwnedAssetRef("ASSET","X")
    private fun seed(){SQLiteDatabase.openOrCreateDatabase(f,null).use{d->OwnershipStore(d,"C").acquire(OwnershipRecord("C","ROOT",owner("A"),asset,"TITLE",OwnershipShare.full(),10,sourceEventUid="SEED",provenance="seed"))}}
    private data class Race(val successes:Int,val failures:Int)
    private fun race(a:(OwnershipStore)->Unit,b:(OwnershipStore)->Unit):Race{
        val d1=SQLiteDatabase.openDatabase(f.absolutePath,null,SQLiteDatabase.OPEN_READWRITE)
        val d2=SQLiteDatabase.openDatabase(f.absolutePath,null,SQLiteDatabase.OPEN_READWRITE)
        val s1=OwnershipStore(d1,"C");val s2=OwnershipStore(d2,"C")
        val ready=CountDownLatch(2);val go=CountDownLatch(1);val ok=AtomicInteger();val bad=AtomicInteger();val pool=Executors.newFixedThreadPool(2)
        fun submit(store:OwnershipStore,op:(OwnershipStore)->Unit)=pool.submit{ready.countDown();go.await();try{op(store);ok.incrementAndGet()}catch(_:Throwable){bad.incrementAndGet()}}
        val f1=submit(s1,a);val f2=submit(s2,b);assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();f1.get(15,TimeUnit.SECONDS);f2.get(15,TimeUnit.SECONDS)
        pool.shutdownNow();d1.close();d2.close();return Race(ok.get(),bad.get())
    }

    @Test fun competingFullTransfersHaveExactlyOneWinner(){seed();val r=race(
        {it.fullTransfer("A-B",owner("A"),owner("B"),asset,"TITLE",20,"EV-AB","ab")},
        {it.fullTransfer("A-C",owner("A"),owner("C"),asset,"TITLE",20,"EV-AC","ac")}
    );assertEquals(1,r.successes);assertEquals(1,r.failures);SQLiteDatabase.openDatabase(f.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{d->val c=OwnershipStore(d,"C").currentOwnership(asset,"TITLE");assertEquals(1,c.size);assertTrue(c.single().owner.ownerUid in setOf("B","C"));checks(d)}}

    @Test fun concurrentSixtyPercentTransfersCannotOverAllocate(){seed();val sixty=OwnershipShare.ofFraction(3,5);val r=race(
        {it.transferShare("A-B",owner("A"),owner("B"),asset,"TITLE",sixty,20,"EV-AB","ab")},
        {it.transferShare("A-C",owner("A"),owner("C"),asset,"TITLE",sixty,20,"EV-AC","ac")}
    );assertEquals(1,r.successes);assertEquals(1,r.failures);SQLiteDatabase.openDatabase(f.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{d->val c=OwnershipStore(d,"C").currentOwnership(asset,"TITLE");assertEquals(OWNERSHIP_SHARE_SCALE,c.sumOf{it.share.units});assertEquals(2,c.size);checks(d)}}

    @Test fun transferVersusCloseAndTemporalOverlapRaceSerialize(){seed();val r=race(
        {it.fullTransfer("TRANSFER",owner("A"),owner("B"),asset,"TITLE",20,"EV-T","transfer")},
        {it.close("CLOSE",owner("A"),asset,"TITLE",20,"EV-C","close")}
    );assertEquals(1,r.successes);assertEquals(1,r.failures);SQLiteDatabase.openDatabase(f.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{d->val s=OwnershipStore(d,"C");assertTrue(s.currentOwnership(asset,"TITLE").size<=1);checks(d)}}

    @Test fun concurrentIndependentSixtyPercentAcquisitionsCannotBothCommit(){
        SQLiteDatabase.openOrCreateDatabase(f,null).use{CurrentSchema.ensure(it,"C")}
        val r=race(
            {it.acquire(OwnershipRecord("C","R-B",owner("B"),asset,"TITLE",OwnershipShare.ofFraction(3,5),10,sourceEventUid="B",provenance="b"))},
            {it.acquire(OwnershipRecord("C","R-C",owner("C"),asset,"TITLE",OwnershipShare.ofFraction(3,5),10,sourceEventUid="C",provenance="c"))}
        );assertEquals(1,r.successes);assertEquals(1,r.failures);SQLiteDatabase.openDatabase(f.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{d->val c=OwnershipStore(d,"C").currentOwnership(asset,"TITLE");assertEquals(1,c.size);assertEquals(OwnershipShare.ofFraction(3,5),c.single().share);checks(d)}
    }

    private fun checks(d:SQLiteDatabase){d.rawQuery("PRAGMA integrity_check",null).use{c->c.moveToFirst();assertEquals("ok",c.getString(0))};d.rawQuery("PRAGMA foreign_key_check",null).use{c->assertFalse(c.moveToFirst())}}
}
