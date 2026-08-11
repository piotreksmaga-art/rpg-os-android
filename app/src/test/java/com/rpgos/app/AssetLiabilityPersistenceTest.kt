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
class AssetLiabilityPersistenceTest {
 private lateinit var f:File
 @Before fun setUp(){f=File.createTempFile("p14-",".db");f.delete()}
 @After fun tearDown(){f.delete()}
 private fun db()=SQLiteDatabase.openOrCreateDatabase(f,null)
 private fun p(uid:String)=OwnershipOwnerRef("CHARACTER",uid)
 private fun fail(block:()->Unit){var x=false;try{block()}catch(_:Throwable){x=true};assertTrue(x)}
 private fun setup(d:SQLiteDatabase,campaign:String="C"):AssetLiabilityStore{CurrentSchema.ensure(d,campaign);val refs=OwnershipReferenceRegistry(d,campaign);listOf("A","B").forEach{runCatching{refs.registerOwner(p(it),"p14-test")}};val fs=FinancialStore(d,campaign);if(scalar(d,"SELECT COUNT(*) FROM currency_definitions WHERE currency_uid='CUR'")==0L)fs.registerCurrency(CurrencyDefinition("CUR","cur","Currency",1,"p14"));listOf("A","B").forEach{if(scalar(d,"SELECT COUNT(*) FROM financial_accounts WHERE campaign_id='$campaign' AND account_uid='ACC-$it'")==0L)fs.openAccount(FinancialAccount(campaign,"ACC-$it",p(it),FINANCIAL_ACCOUNT_TYPE_DEFAULT,"CUR",0,"p14"))};return AssetLiabilityStore(d,campaign)}

 @Test fun genericAssetOwnershipValuationObligationAndDerivedNetWorthStaySeparate(){db().use{d->val s=setup(d);val asset=AssetRecord("C","HOUSE-1",ASSET_KIND_PROPERTY,1,"deed evidence");s.createAsset(asset);s.recordValuation(AssetValuation("C","VAL-1",asset.ref,"CUR",1000,ValuationType.APPRAISAL,2,"appraisal"))
  assertEquals(0,OwnershipStore(d,"C").currentOwnership(asset.ref).size)
  OwnershipStore(d,"C").acquire(OwnershipRecord("C","OWN-1",p("A"),asset.ref,OWNERSHIP_TYPE_ECONOMIC,OwnershipShare.full(),1,sourceEventUid="EV-DEED",provenance="explicit title"))
  val fs=FinancialStore(d,"C");fs.creditExternal("SEED-A","ACC-A",200,2,"cash","p14")
  val debt=ObligationRecord("C","OBL-1","RPGOS-OBLIGATION-TYPE:DEBT",ObligationClass.DEBT,p("A"),p("B"),3,"contract",currencyUid="CUR",principalMinor=300,sourceContractUid="CONTRACT-1");s.createObligation(debt,"STATUS-ACTIVE")
  val a4=s.netWorth(p("A"),"CUR",4);assertEquals(1000L,a4.assetsMinor);assertEquals(200L,a4.cashMinor);assertEquals(300L,a4.liabilitiesMinor);assertEquals(900L,a4.netWorthMinor)
  val b4=s.netWorth(p("B"),"CUR",4);assertEquals(300L,b4.receivablesMinor);assertEquals(300L,b4.netWorthMinor)
  fs.transfer("PAY-1","ACC-A","ACC-B",100,5,"debt payment","p14");s.settle(ObligationSettlement("C","SET-1","OBL-1",SettlementKind.PAYMENT,5,"payment evidence",100,"PAY-1"));assertEquals(200L,s.outstandingMinor("OBL-1"))
  fail{s.settle(ObligationSettlement("C","SET-BAD","OBL-1",SettlementKind.PAYMENT,6,"forged",50,"NO-TX"))}
  s.settle(ObligationSettlement("C","SET-2","OBL-1",SettlementKind.FORGIVENESS,7,"written forgiveness",200));s.changeObligationStatus("OBL-1","STATUS-SETTLED",ObligationStatus.SETTLED,8,"contract closure");assertEquals(0L,s.outstandingMinor("OBL-1"));assertEquals(ObligationStatus.SETTLED,s.currentStatus("OBL-1"))
  val a9=s.netWorth(p("A"),"CUR",9);assertEquals(1100L,a9.netWorthMinor)
  assertEquals(0L,scalar(d,"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name LIKE '%net_worth%'"));checks(d)
 }}

 @Test fun linkedReceivableAssetAndBeneficiaryObligationContributeExactlyOnce(){db().use{d->val s=setup(d);val r=AssetRecord("C","CLAIM-1",ASSET_KIND_RECEIVABLE,1,"claim document");s.createAsset(r);s.recordValuation(AssetValuation("C","CLAIM-VAL",r.ref,"CUR",100,ValuationType.FACE,2,"face value"));OwnershipStore(d,"C").acquire(OwnershipRecord("C","OWN-CLAIM",p("B"),r.ref,OWNERSHIP_TYPE_ECONOMIC,OwnershipShare.full(),2,provenance="claim ownership"));s.createObligation(ObligationRecord("C","CLAIM-OBL","RPGOS-OBLIGATION-TYPE:DEBT",ObligationClass.DEBT,p("A"),p("B"),3,"same economic claim",currencyUid="CUR",principalMinor=100,asset=r.ref,sourceContractUid="CLAIM-CONTRACT"),"CLAIM-ACTIVE");val b=s.netWorth(p("B"),"CUR",4);assertEquals(0L,b.assetsMinor);assertEquals(100L,b.receivablesMinor);assertEquals(100L,b.netWorthMinor);assertEquals(0,b.missingValuationCount);checks(d)}}

 @Test fun independentReceivableAssetContributesExactlyOnce(){db().use{d->val s=setup(d);val r=AssetRecord("C","CLAIM-INDEPENDENT",ASSET_KIND_RECEIVABLE,1,"independent claim");s.createAsset(r);s.recordValuation(AssetValuation("C","CLAIM-INDEPENDENT-V",r.ref,"CUR",100,ValuationType.FACE,2,"face value"));OwnershipStore(d,"C").acquire(OwnershipRecord("C","OWN-INDEPENDENT",p("B"),r.ref,OWNERSHIP_TYPE_ECONOMIC,OwnershipShare.full(),2,provenance="claim ownership"));val b=s.netWorth(p("B"),"CUR",3);assertEquals(100L,b.assetsMinor);assertEquals(0L,b.receivablesMinor);assertEquals(100L,b.netWorthMinor);checks(d)}}

 @Test fun receivableAssetAndUnrelatedObligationRemainTwoClaims(){db().use{d->val s=setup(d);val r=AssetRecord("C","CLAIM-A",ASSET_KIND_RECEIVABLE,1,"claim A");s.createAsset(r);s.recordValuation(AssetValuation("C","CLAIM-A-V",r.ref,"CUR",100,ValuationType.FACE,2,"face value"));OwnershipStore(d,"C").acquire(OwnershipRecord("C","OWN-A",p("B"),r.ref,OWNERSHIP_TYPE_ECONOMIC,OwnershipShare.full(),2,provenance="claim ownership"));s.createObligation(ObligationRecord("C","CLAIM-B","RPGOS-OBLIGATION-TYPE:DEBT",ObligationClass.DEBT,p("A"),p("B"),3,"unrelated claim B",currencyUid="CUR",principalMinor=100,sourceContractUid="OTHER-CONTRACT"),"CLAIM-B-ACTIVE");val b=s.netWorth(p("B"),"CUR",4);assertEquals(100L,b.assetsMinor);assertEquals(100L,b.receivablesMinor);assertEquals(200L,b.netWorthMinor);checks(d)}}

 @Test fun stableUidReplayRequiresCompleteImmutablePayloadAndReturnsCanonicalFact(){db().use{d->val s=setup(d);val a1=AssetRecord("C","REPLAY-ASSET",ASSET_KIND_PROPERTY,1,"asset",sourceEventUid="ASSET-EV",metadataJson="{\"x\":1}");val a2=AssetRecord("C","REPLAY-ASSET-2",ASSET_KIND_PROPERTY,1,"asset2");s.createAsset(a1);s.createAsset(a2)
  val v=AssetValuation("C","REPLAY-V",a1.ref,"CUR",100,ValuationType.APPRAISAL,2,"valuation",validUntilOrder=20,sourceEventUid="VAL-EV",confidencePpm=900000,version=2);assertEquals(v,s.recordValuation(v));assertEquals(v,s.recordValuation(v.copy()));assertEquals(1L,scalar(d,"SELECT COUNT(*) FROM asset_valuations WHERE valuation_uid='REPLAY-V'"));fail{s.recordValuation(v.copy(amountMinor=101))};fail{s.recordValuation(v.copy(currencyUid="OTHER"))};fail{s.recordValuation(v.copy(valuationType=ValuationType.BOOK))};fail{s.recordValuation(v.copy(effectiveOrder=3))};fail{s.recordValuation(v.copy(sourceEventUid="OTHER-EV"))}
  val o=ObligationRecord("C","REPLAY-O","RPGOS-OBLIGATION-TYPE:DEBT",ObligationClass.DEBT,p("A"),p("B"),3,"obligation",currencyUid="CUR",principalMinor=100,asset=a1.ref,dueOrder=9,validUntilOrder=30,sourceEventUid="OBL-EV",sourceContractUid="CONTRACT",version=2,metadataJson="{\"m\":1}");assertEquals(o,s.createObligation(o,"REPLAY-O-ACTIVE"));assertEquals(o,s.createObligation(o.copy(),"REPLAY-O-ACTIVE"));assertEquals(1L,scalar(d,"SELECT COUNT(*) FROM obligation_records WHERE obligation_uid='REPLAY-O'"));fail{s.createObligation(o.copy(principalMinor=200),"REPLAY-O-ACTIVE")};fail{s.createObligation(o.copy(currencyUid="OTHER"),"REPLAY-O-ACTIVE")};fail{s.createObligation(o.copy(asset=a2.ref),"REPLAY-O-ACTIVE")};fail{s.createObligation(o.copy(dueOrder=10),"REPLAY-O-ACTIVE")};fail{s.createObligation(o.copy(sourceContractUid="OTHER"),"REPLAY-O-ACTIVE")};fail{s.createObligation(o,"OTHER-INITIAL-STATUS")};assertEquals(100L,s.outstandingMinor("REPLAY-O"))
  val set=ObligationSettlement("C","REPLAY-S","REPLAY-O",SettlementKind.FORGIVENESS,4,"settlement",10,sourceEventUid="SET-EV");assertEquals(set,s.settle(set));assertEquals(set,s.settle(set.copy()));assertEquals(1L,scalar(d,"SELECT COUNT(*) FROM obligation_settlements WHERE settlement_uid='REPLAY-S'"));fail{s.settle(set.copy(amountMinor=11))};fail{s.settle(set.copy(kind=SettlementKind.WRITE_OFF))};fail{s.settle(set.copy(financialTransactionUid="TX-X"))};fail{s.settle(set.copy(sourceEventUid="SET-EV-2"))}
  s.changeObligationStatus("REPLAY-O","REPLAY-STATUS",ObligationStatus.DEFAULTED,5,"status","STATUS-EV");s.changeObligationStatus("REPLAY-O","REPLAY-STATUS",ObligationStatus.DEFAULTED,5,"status","STATUS-EV");fail{s.changeObligationStatus("REPLAY-O","REPLAY-STATUS",ObligationStatus.CANCELLED,5,"status","STATUS-EV")};checks(d)
 }}

 @Test fun legacyAggregatesAndOtherDomainsNeverSynthesizeCanonicalAssetsOrLiabilities(){db().use{d->d.execSQL("CREATE TABLE character_finances(entity_uid TEXT,debt INTEGER,property_value INTEGER,investment_value INTEGER)");d.execSQL("INSERT INTO character_finances VALUES('A',50,900,800)");d.execSQL("CREATE TABLE inventory(entity_uid TEXT,item_name TEXT)");d.execSQL("INSERT INTO inventory VALUES('A','House Key')");CurrentSchema.ensure(d,"C");assertEquals(0L,scalar(d,"SELECT COUNT(*) FROM asset_records"));assertEquals(0L,scalar(d,"SELECT COUNT(*) FROM obligation_records"));assertEquals(50L,scalar(d,"SELECT debt FROM character_finances WHERE entity_uid='A'"));assertEquals(900L,scalar(d,"SELECT property_value FROM character_finances WHERE entity_uid='A'"));checks(d)}}

 @Test fun historyIsAppendPreservedReferencesAreGuardedAndStatePatchBlocked(){db().use{d->val s=setup(d);val a=AssetRecord("C","CO-1",ASSET_KIND_COMPANY,1,"charter");s.createAsset(a);s.recordValuation(AssetValuation("C","V1",a.ref,"CUR",500,ValuationType.BOOK,2,"books"));fail{d.execSQL("UPDATE asset_valuations SET amount_minor=1 WHERE valuation_uid='V1'")};fail{d.execSQL("DELETE FROM asset_valuations WHERE valuation_uid='V1'")};fail{d.execSQL("DELETE FROM asset_records WHERE asset_uid='CO-1'")}
  fail{s.createObligation(ObligationRecord("C","BAD","RPGOS-OBLIGATION-TYPE:DEBT",ObligationClass.DEBT,p("A"),OwnershipOwnerRef("CHARACTER","GHOST"),3,"bad",currencyUid="CUR",principalMinor=1),"BAD-S")}
  val r=SourceOfTruthRegistry(d);listOf("asset_records","asset_valuations","obligation_records","obligation_status_history","obligation_settlements","asset_encumbrances").forEach{assertFalse(r.canWrite(it))};checks(d)
 }}

 @Test fun exactFractionalOwnershipScaleHistoryAndReopen(){db().use{d->val s=setup(d);val a=AssetRecord("C","SHARE-CO",ASSET_KIND_COMPANY,1,"company");s.createAsset(a);OwnershipStore(d,"C").acquire(OwnershipRecord("C","OWN-HALF",p("A"),a.ref,OWNERSHIP_TYPE_ECONOMIC,OwnershipShare.ofFraction(1,2),1,provenance="half stake"));for(i in 0..1000)s.recordValuation(AssetValuation("C","V-$i",a.ref,"CUR",2000L+i,ValuationType.MARKET,(i+2).toLong(),"market-$i"));assertEquals(1001L,scalar(d,"SELECT COUNT(*) FROM asset_valuations WHERE campaign_id='C'"));assertEquals(1500L,s.netWorth(p("A"),"CUR",1002).assetsMinor);checks(d)};SQLiteDatabase.openDatabase(f.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{d->CurrentSchema.ensure(d,"C");val s=AssetLiabilityStore(d,"C");assertEquals(1001L,scalar(d,"SELECT COUNT(*) FROM asset_valuations WHERE campaign_id='C'"));assertEquals(1500L,s.netWorth(p("A"),"CUR",1002).assetsMinor);checks(d)}}

 @Test fun campaignIdentityIsolatedWithSameStableUids(){db().use{d->val a=setup(d,"C");a.createAsset(AssetRecord("C","SAME",ASSET_KIND_PROPERTY,1,"c"));val b=setup(d,"D");b.createAsset(AssetRecord("D","SAME",ASSET_KIND_PROPERTY,1,"d"));assertEquals(1L,a.assetCount());assertEquals(1L,b.assetCount());assertEquals(2L,scalar(d,"SELECT COUNT(*) FROM asset_records WHERE asset_uid='SAME'"));checks(d)}}

 private fun scalar(d:SQLiteDatabase,sql:String):Long=d.rawQuery(sql,null).use{c->c.moveToFirst();c.getLong(0)}
 private fun checks(d:SQLiteDatabase){d.rawQuery("PRAGMA integrity_check",null).use{c->c.moveToFirst();assertEquals("ok",c.getString(0))};d.rawQuery("PRAGMA foreign_key_check",null).use{c->assertFalse(c.moveToFirst())}}
}
