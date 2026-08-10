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

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class FinancialPersistenceTest {
    private lateinit var f: File
    @Before fun setUp(){f=File.createTempFile("finance-",".db");f.delete()}
    @After fun tearDown(){f.delete()}
    private fun db()=SQLiteDatabase.openOrCreateDatabase(f,null)
    private fun owner(uid:String,kind:String="CHARACTER")=OwnershipOwnerRef(kind,uid)
    private fun fail(block:()->Unit){var failed=false;try{block()}catch(_:Throwable){failed=true};assertTrue(failed)}
    private fun setup(d:SQLiteDatabase,c:String="C",holders:List<OwnershipOwnerRef> = listOf(owner("A"),owner("B"),owner("ORG","ORGANIZATION"))):FinancialStore{
        CurrentSchema.ensure(d,c);val refs=OwnershipReferenceRegistry(d,c);holders.forEach{refs.registerOwner(it,"finance-test-holder")}
        val s=FinancialStore(d,c)
        if(scalar(d,"SELECT COUNT(*) FROM currency_definitions WHERE currency_uid='CUR'")==0L)s.registerCurrency(CurrencyDefinition("CUR","credits","Credits",1,"test-currency"))
        holders.forEachIndexed{i,h->s.openAccount(FinancialAccount(c,"ACC-${h.ownerUid}",h,FINANCIAL_ACCOUNT_TYPE_DEFAULT,"CUR",i.toLong(),"test-account"))}
        return s
    }

    @Test fun exactLedgerTransferRebuildAndStableHistory(){db().use{d->
        val s=setup(d);s.creditExternal("OPEN","ACC-A",100,10,"opening","seed",commandUid="CMD-OPEN");s.transfer("T1","ACC-A","ACC-B",40,20,"pay","transfer",sourceEventUid="EV-1",commandUid="CMD-T1")
        assertEquals(60L,s.balance("ACC-A"));assertEquals(40L,s.balance("ACC-B"));assertEquals(60L,s.reconcile("ACC-A"));assertEquals(40L,s.reconcile("ACC-B"));assertEquals(2L,s.historyCount())
        d.execSQL("DELETE FROM financial_account_balances WHERE campaign_id='C' AND account_uid='ACC-B'");assertEquals(40L,s.rebuildBalance("ACC-B"));assertEquals(40L,s.reconcile("ACC-B"))
        val recent=s.recentTransactions("ACC-A",limit=10);assertEquals(2,recent.size);assertEquals("T1",recent.first().financialTransactionUid);checks(d)
    }}

    @Test fun idempotencyImmutabilityReversalAndDirectSqlBoundary(){db().use{d->
        val s=setup(d);val first=s.creditExternal("OPEN","ACC-A",100,1,"opening","seed",commandUid="CMD");assertFalse(first.idempotentReplay)
        val replay=s.creditExternal("OPEN","ACC-A",100,1,"opening","seed",commandUid="CMD");assertTrue(replay.idempotentReplay);assertEquals(100L,s.balance("ACC-A"));assertEquals(1L,s.historyCount())
        fail{s.creditExternal("OTHER","ACC-A",1,2,"dup command","x",commandUid="CMD")}
        s.debitExternal("D","ACC-A",30,3,"expense","x");assertEquals(70L,s.balance("ACC-A"));s.reverse("D","R",4,"correction","x");assertEquals(100L,s.balance("ACC-A"));fail{s.reverse("D","R2",5,"double reversal","x")}
        fail{d.execSQL("UPDATE financial_ledger_transactions SET amount_minor=1 WHERE campaign_id='C' AND financial_transaction_uid='D'")};fail{d.execSQL("DELETE FROM financial_ledger_transactions WHERE campaign_id='C' AND financial_transaction_uid='D'")}
        fail{d.execSQL("INSERT INTO financial_ledger_transactions(campaign_id,financial_transaction_uid,from_account_uid,to_account_uid,currency_uid,amount_minor,transaction_type_uid,flow_kind,reason,effective_order,provenance) VALUES('C','RAW','ACC-A',NULL,'CUR',101,'RPGOS-FIN-TYPE:EXTERNAL_DEBIT','SINK','raw overspend',6,'raw')")}
        assertEquals(100L,s.balance("ACC-A"));checks(d)
    }}

    @Test fun invalidAmountsCurrencyReferencesCampaignAndLifecycleFailClosed(){db().use{d->
        val s=setup(d);fail{s.creditExternal("Z","ACC-A",0,1,"zero","x")};fail{s.creditExternal("N","ACC-A",-1,1,"neg","x")}
        val refs=OwnershipReferenceRegistry(d,"C");fail{FinancialStore(d,"C").openAccount(FinancialAccount("C","GHOST",owner("NOPE"),FINANCIAL_ACCOUNT_TYPE_DEFAULT,"CUR",1,"x"))}
        s.registerCurrency(CurrencyDefinition("ALT","alt","Alt",1,"alt"));refs.registerOwner(owner("C"),"c");s.openAccount(FinancialAccount("C","ACC-C",owner("C"),FINANCIAL_ACCOUNT_TYPE_DEFAULT,"ALT",1,"x"));fail{s.transfer("FX","ACC-A","ACC-C",1,2,"implicit exchange","x")}
        s.creditExternal("A-FUND","ACC-A",10,2,"fund","x");fail{s.closeAccount("ACC-A",3)};fail{refs.retireOwner(owner("A"),"active-finance")}
        val dRefs=OwnershipReferenceRegistry(d,"D");dRefs.registerOwner(owner("A"),"d");val ds=FinancialStore(d,"D");ds.openAccount(FinancialAccount("D","ACC-A",owner("A"),FINANCIAL_ACCOUNT_TYPE_DEFAULT,"CUR",1,"d"));assertEquals(0L,ds.balance("ACC-A"));assertEquals(10L,s.balance("ACC-A"));checks(d)
    }}

    @Test fun outerDomainTransactionRollbackDoesNotHalfCommitFinance(){db().use{d->
        val s=setup(d);s.creditExternal("OPEN","ACC-A",100,1,"seed","x");val before=s.historyCount()
        d.beginTransaction();try{s.transfer("PURCHASE-PAY","ACC-A","ACC-B",50,2,"purchase payment","x");throw IllegalStateException("simulated ownership/inventory failure")}catch(_:Throwable){}finally{d.endTransaction()}
        assertEquals(before,s.historyCount());assertEquals(100L,s.balance("ACC-A"));assertEquals(0L,s.balance("ACC-B"));checks(d)
    }}

    @Test fun migrationIsConservativeIdempotentAndScaleHistoryIsUnbounded(){db().use{d->
        d.execSQL("CREATE TABLE character_finances(entity_uid TEXT,ryo INTEGER,debt INTEGER,property_value INTEGER,investment_value INTEGER)");d.execSQL("INSERT INTO character_finances VALUES('P',777,5,9,11)")
        d.execSQL("CREATE TABLE financial_transactions(id TEXT,amount INTEGER,reason TEXT)");d.execSQL("INSERT INTO financial_transactions VALUES('OLD',50,'legacy opaque')")
        CurrentSchema.ensure(d,"C");CurrentSchema.ensure(d,"C");assertEquals(0L,scalar(d,"SELECT COUNT(*) FROM financial_ledger_transactions"));assertEquals(1L,scalar(d,"SELECT COUNT(*) FROM financial_transactions"));assertEquals(777L,scalar(d,"SELECT ryo FROM character_finances WHERE entity_uid='P'"));assertEquals(1L,scalar(d,"SELECT COUNT(*) FROM rpgos_schema_migrations WHERE migration_id='$PHASE13_MIGRATION_ID'"))
        val refs=OwnershipReferenceRegistry(d,"C");refs.registerOwner(owner("P"),"p");val s=FinancialStore(d,"C");s.registerCurrency(CurrencyDefinition("CUR","credits","Credits",1,"test"));s.openAccount(FinancialAccount("C","ACC-P",owner("P"),FINANCIAL_ACCOUNT_TYPE_DEFAULT,"CUR",0,"test"))
        for(i in 0..1000)s.creditExternal("TX-$i","ACC-P",1,(i+1).toLong(),"reward","scale",commandUid="CMD-$i")
        assertEquals(1001L,s.historyCount());assertEquals(1001L,s.balance("ACC-P"));assertEquals(50,s.recentTransactions("ACC-P",limit=50).size);assertEquals(1001L,s.reconcile("ACC-P"));checks(d)
    };SQLiteDatabase.openDatabase(f.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{d->val s=FinancialStore(d,"C");assertEquals(1001L,s.historyCount());assertEquals(1001L,s.reconcile("ACC-P"))}}

    @Test fun genericStatePatchCannotWriteLegacyOrCanonicalFinance(){val core=File.createTempFile("core-",".db");try{SQLiteDatabase.openOrCreateDatabase(core,null).use{c->val r=SourceOfTruthRegistry(c);assertFalse(r.canWrite("financial_transactions"));assertFalse(r.canWrite("financial_ledger_transactions"));assertFalse(r.canWrite("financial_accounts"));assertFalse(r.canWrite("financial_account_balances"))}}finally{core.delete()}}

    private fun scalar(d:SQLiteDatabase,sql:String):Long=d.rawQuery(sql,null).use{c->c.moveToFirst();c.getLong(0)}
    private fun checks(d:SQLiteDatabase){d.rawQuery("PRAGMA integrity_check",null).use{c->c.moveToFirst();assertEquals("ok",c.getString(0))};d.rawQuery("PRAGMA foreign_key_check",null).use{c->assertFalse(c.moveToFirst())}}
}
