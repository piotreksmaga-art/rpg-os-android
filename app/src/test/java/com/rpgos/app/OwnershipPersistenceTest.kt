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
class OwnershipPersistenceTest {
    private lateinit var f:File
    @Before fun setUp(){f=File.createTempFile("ownership-",".db");f.delete()}
    @After fun tearDown(){f.delete()}
    private fun db()=SQLiteDatabase.openOrCreateDatabase(f,null)
    private fun owner(uid:String,kind:String="CHARACTER")=OwnershipOwnerRef(kind,uid)
    private fun asset(uid:String,kind:String="ASSET")=OwnedAssetRef(kind,uid)
    private fun rec(c:String="C",uid:String,who:String,what:OwnedAssetRef,share:OwnershipShare=OwnershipShare.full(),from:Long=10L,type:String="TITLE")=
        OwnershipRecord(c,uid,owner(who),what,type,share,from,sourceEventUid="seed-$uid",provenance="seed")
    private fun fail(block:()->Unit){var failed=false;try{block()}catch(_:Throwable){failed=true};assertTrue(failed)}
    private fun refs(d:SQLiteDatabase,c:String,owners:List<OwnershipOwnerRef>,assets:List<OwnedAssetRef>){
        CurrentSchema.ensure(d,c)
        val r=OwnershipReferenceRegistry(d,c)
        assets.map{it.assetKindUid}.filter{it!=OWNERSHIP_ASSET_KIND_ITEM_INSTANCE}.distinct().forEach{r.registerAssetKind(it,"test-kind")}
        owners.distinct().forEach{r.registerOwner(it,"test-owner")}
        assets.filter{it.assetKindUid!=OWNERSHIP_ASSET_KIND_ITEM_INSTANCE}.distinct().forEach{r.registerAsset(it,"test-asset")}
    }

    @Test fun exactShareSemanticsRejectInvalidPrecisionAndOverflow(){
        assertEquals(OwnershipShare.ofFraction(1,3),OwnershipShare.ofFraction(2,6))
        assertEquals(1L,OwnershipShare.ofFraction(1,3).numerator);assertEquals(3L,OwnershipShare.ofFraction(1,3).denominator)
        assertEquals(OwnershipShare.full(),OwnershipShare.ofFraction(5,5))
        fail{OwnershipShare.ofFraction(0,1)};fail{OwnershipShare.ofFraction(-1,2)};fail{OwnershipShare.ofFraction(2,1)};fail{OwnershipShare.ofFraction(1,0)}
        fail{OwnershipShare.ofFraction(1,7)};fail{OwnershipShare.ofUnits(Long.MAX_VALUE)};fail{OwnershipShare.full().add(OwnershipShare.ofFraction(1,2))}
    }

    @Test fun ownerReferenceIntegrityRejectsUnresolvedCrossCampaignUnknownAndActiveRetirement(){db().use{d->
        CurrentSchema.ensure(d,"C");val s=OwnershipStore(d,"C");val x=asset("X");refs(d,"C",listOf(owner("A"),owner("ORG","ORGANIZATION")),listOf(x))
        s.acquire(rec(uid="R",who="A",what=x))
        fail{s.fullTransfer("BAD",owner("A"),owner("MISSING"),x,"TITLE",20,"E","bad")}
        fail{s.acquire(OwnershipRecord("C","U",owner("X","UNREGISTERED_KIND"),x,"TITLE",OwnershipShare.full(),30,sourceEventUid="U",provenance="u"))}
        val reg=OwnershipReferenceRegistry(d,"C");fail{reg.retireOwner(owner("A"),"retire-active")}
        val other=OwnershipReferenceRegistry(d,"D");other.registerOwner(owner("A"),"other-campaign")
        fail{OwnershipStore(d,"D").acquire(OwnershipRecord("D","CROSS",owner("ORG","ORGANIZATION"),x,"TITLE",OwnershipShare.full(),10,sourceEventUid="X",provenance="x"))}
        assertEquals("A",s.currentOwnership(x,"TITLE").single().owner.ownerUid)
        s.close("CLOSE",owner("A"),x,"TITLE",20,"EC","close");reg.retireOwner(owner("A"),"retired")
        fail{s.acquire(OwnershipRecord("C","AFTER",owner("A"),x,"TITLE",OwnershipShare.full(),30,sourceEventUid="A",provenance="after"))}
    }}

    @Test fun genericAssetReferenceIntegrityRejectsUnknownMissingCrossCampaignAndPreservesKindIdentity(){db().use{d->
        CurrentSchema.ensure(d,"C");val reg=OwnershipReferenceRegistry(d,"C");reg.registerOwner(owner("A"),"a")
        reg.registerAssetKind("PROPERTY","property-kind");reg.registerAsset(asset("SAME","PROPERTY"),"property")
        val s=OwnershipStore(d,"C");s.acquire(OwnershipRecord("C","P",owner("A"),asset("SAME","PROPERTY"),"TITLE",OwnershipShare.full(),10,sourceEventUid="P",provenance="p"))
        fail{s.acquire(OwnershipRecord("C","MISS",owner("A"),asset("NOPE","PROPERTY"),"TITLE",OwnershipShare.full(),10,sourceEventUid="M",provenance="m"))}
        fail{s.acquire(OwnershipRecord("C","UNK",owner("A"),asset("X","BUSINESS_UNREGISTERED"),"TITLE",OwnershipShare.full(),10,sourceEventUid="U",provenance="u"))}
        val regD=OwnershipReferenceRegistry(d,"D");regD.registerOwner(owner("A"),"a-d");regD.registerAssetKind("PROPERTY_D","kind-d");regD.registerAsset(asset("SAME","PROPERTY_D"),"asset-d")
        fail{OwnershipStore(d,"D").acquire(OwnershipRecord("D","WRONG",owner("A"),asset("SAME","PROPERTY"),"TITLE",OwnershipShare.full(),10,sourceEventUid="W",provenance="w"))}
        reg.registerAssetKind("PROPERTY_ALT","alt-kind");reg.registerAsset(asset("SAME","PROPERTY_ALT"),"alt")
        s.acquire(OwnershipRecord("C","ALT",owner("A"),asset("SAME","PROPERTY_ALT"),"TITLE",OwnershipShare.full(),10,sourceEventUid="ALT",provenance="alt"))
        assertEquals(1,s.currentOwnership(asset("SAME","PROPERTY"),"TITLE").size);assertEquals(1,s.currentOwnership(asset("SAME","PROPERTY_ALT"),"TITLE").size)
        fail{reg.retireAsset(asset("SAME","PROPERTY"),"retire-active")}
    }}

    @Test fun temporalHistoryFullTransferStableIdentityGenericOwnersAndAssets(){db().use{d->
        CurrentSchema.ensure(d,"C");val s=OwnershipStore(d,"C");val x=asset("PROPERTY-X","PROPERTY")
        refs(d,"C",listOf(owner("A"),owner("ORG-9","ORGANIZATION")),listOf(x));val original=rec(uid="R-A",who="A",what=x);s.acquire(original)
        val r=s.fullTransfer("OP-1",owner("A"),owner("ORG-9","ORGANIZATION"),x,"TITLE",20,"EV-SALE","sale")
        assertEquals("R-A",r.closedSource.ownershipRecordUid);assertEquals(20L,r.closedSource.validUntil);assertEquals(OwnershipRecordStatus.CLOSED,r.closedSource.status)
        assertEquals("ORG-9",s.currentOwnership(x,"TITLE").single().owner.ownerUid);assertEquals("A",s.ownershipAt(x,19,"TITLE").single().owner.ownerUid);assertEquals("ORG-9",s.ownershipAt(x,20,"TITLE").single().owner.ownerUid);assertEquals(2,s.history(x,"TITLE").size)
        val retry=s.fullTransfer("OP-1",owner("A"),owner("ORG-9","ORGANIZATION"),x,"TITLE",20,"EV-SALE","sale");assertEquals(r.destinationSuccessor.ownershipRecordUid,retry.destinationSuccessor.ownershipRecordUid)
        fail{s.acquire(rec(uid="R-DUP",who="ORG-9",what=x,from=25))};fail{s.acquire(original)}
    }}

    @Test fun coOwnershipPartialTransferConservesExactlyAndDbRejectsOverAllocation(){db().use{d->
        val s=OwnershipStore(d,"C");val x=asset("BIZ");refs(d,"C",listOf(owner("A"),owner("B"),owner("C")),listOf(x))
        s.acquire(rec(uid="A60",who="A",what=x,share=OwnershipShare.ofFraction(3,5)));s.acquire(rec(uid="B40",who="B",what=x,share=OwnershipShare.ofFraction(2,5)))
        val moved=s.transferShare("OP-SHARE",owner("A"),owner("B"),x,"TITLE",OwnershipShare.ofFraction(1,5),20,"EV-SHARE","share-sale")
        val current=s.currentOwnership(x,"TITLE").associate{it.owner.ownerUid to it.share};assertEquals(OwnershipShare.ofFraction(2,5),current.getValue("A"));assertEquals(OwnershipShare.ofFraction(3,5),current.getValue("B"));assertEquals(OWNERSHIP_SHARE_SCALE,current.values.sumOf{it.units});assertNotNull(moved.sourceSuccessor)
        fail{s.acquire(rec(uid="C20",who="C",what=x,share=OwnershipShare.ofFraction(1,5),from=20))};fail{s.transferShare("TOO-MUCH",owner("A"),owner("C"),x,"TITLE",OwnershipShare.ofFraction(3,5),30,"EV-BAD","bad")}
    }}

    @Test fun itemInstanceOwnershipIsIndependentFromPossessionEquipmentTheftLoanAndLoss(){db().use{d->
        CurrentSchema.ensure(d,"C");val inv=InventoryStore(d,"C");inv.registerDefinitions("W",listOf(ItemDefinition("D","W","relic","Relic",storagePolicy=ItemStoragePolicy.UNIQUE_INSTANCE,provenance="pack")))
        inv.createInstance(ItemInstance("C","X","D",provenance="instance"));inv.addUnique("A","X","held");refs(d,"C",listOf(owner("A"),owner("B")),emptyList())
        val own=OwnershipStore(d,"C");val x=asset("X",OWNERSHIP_ASSET_KIND_ITEM_INSTANCE);own.acquire(rec(uid="TITLE-X",who="A",what=x));inv.transferUnique("A","B","X","theft-or-loan")
        assertEquals("A",own.currentOwnership(x,"TITLE").single().owner.ownerUid)
        val eq=EquipmentStore(d,"C");eq.registerSlots("W",listOf(EquipmentSlotDefinition("S","W","hand","Hand",provenance="pack")));eq.registerCompatibilityRules("W",listOf(EquipmentCompatibilityRule("R","W","D",listOf("S"),provenance="pack")))
        eq.equip("B","X","R",listOf("S"),"E","borrowed");assertEquals("A",own.currentOwnership(x,"TITLE").single().owner.ownerUid);eq.unequip("B","E");inv.removeUnique("B","X");assertEquals("A",own.currentOwnership(x,"TITLE").single().owner.ownerUid)
        fail{own.acquire(rec(uid="MISSING",who="A",what=asset("NOPE",OWNERSHIP_ASSET_KIND_ITEM_INSTANCE)))}
        fail{d.execSQL("DELETE FROM item_instances WHERE campaign_id='C' AND item_instance_uid='X'")}
    }}

    @Test fun ownershipTransferDoesNotMovePhysicalPossession(){db().use{d->
        CurrentSchema.ensure(d,"C");val inv=InventoryStore(d,"C");inv.registerDefinitions("W",listOf(ItemDefinition("D","W","title-only","Title Only",storagePolicy=ItemStoragePolicy.UNIQUE_INSTANCE,provenance="pack")))
        inv.createInstance(ItemInstance("C","X","D",provenance="instance"));inv.addUnique("A","X","held");refs(d,"C",listOf(owner("A"),owner("B")),emptyList())
        val own=OwnershipStore(d,"C");val x=asset("X",OWNERSHIP_ASSET_KIND_ITEM_INSTANCE);own.acquire(rec(uid="TITLE-X",who="A",what=x));own.fullTransfer("TITLE-TRANSFER",owner("A"),owner("B"),x,"TITLE",20,"EV-TITLE","legal-title-only")
        assertEquals("B",own.currentOwnership(x,"TITLE").single().owner.ownerUid);assertEquals("X",inv.typedUnique("A").single().first.itemInstanceUid);assertTrue(inv.typedUnique("B").isEmpty())
    }}

    @Test fun migrationNeverSynthesizesLegacyOwnershipCampaignsAreIsolatedAndScaleIsUnboundedByReaders(){db().use{d->
        d.execSQL("CREATE TABLE character_inventory(entity_uid TEXT,item_name TEXT)");d.execSQL("INSERT INTO character_inventory VALUES('P','Same')");d.execSQL("CREATE TABLE character_techniques(entity_uid TEXT,technique_uid TEXT,is_equipped INTEGER)");d.execSQL("INSERT INTO character_techniques VALUES('P','T',1)")
        CurrentSchema.ensure(d,"C");CurrentSchema.ensure(d,"C");assertEquals(0,scalar(d,"SELECT COUNT(*) FROM ownership_records"));assertEquals(1,scalar(d,"SELECT COUNT(*) FROM rpgos_schema_migrations WHERE migration_id='$PHASE12_MIGRATION_ID'"))
        val assets=(0..1000).map{asset("A$it")};refs(d,"C",listOf(owner("P")),assets);val c=OwnershipStore(d,"C");for(i in 0..1000)c.acquire(rec(uid="R$i",who="P",what=assets[i]));assertEquals(1001,c.ownershipByOwner(owner("P")).size)
        refs(d,"D",listOf(owner("P")),listOf(asset("A0")));val dStore=OwnershipStore(d,"D");dStore.acquire(rec(c="D",uid="R0",who="P",what=asset("A0")));assertEquals(1,dStore.ownershipByOwner(owner("P")).size);assertEquals(1001,c.ownershipByOwner(owner("P")).size)
        c.registerLegacyMapping("PROVEN-LEGACY","R0",1,"explicit-map");fail{c.registerLegacyMapping("PROVEN-LEGACY","R0",1,"dup")}
        d.rawQuery("PRAGMA integrity_check",null).use{q->q.moveToFirst();assertEquals("ok",q.getString(0))};d.rawQuery("PRAGMA foreign_key_check",null).use{q->assertFalse(q.moveToFirst())}
    };SQLiteDatabase.openDatabase(f.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{d->assertEquals(1001,OwnershipStore(d,"C").ownershipByOwner(owner("P")).size)}}

    @Test fun authoritativeSqlBoundaryRejectsMalformedSharesOverlapIllegalUpdatesDeletesAndUnresolvedRefs(){db().use{d->
        val s=OwnershipStore(d,"C");val x=asset("X");refs(d,"C",listOf(owner("A"),owner("B")),listOf(x));s.acquire(rec(uid="A",who="A",what=x,share=OwnershipShare.ofFraction(3,5)))
        fail{d.execSQL("INSERT INTO ownership_records(campaign_id,ownership_record_uid,owner_kind_uid,owner_uid,asset_kind_uid,asset_uid,ownership_type_uid,share_units,valid_from_order,record_version,record_status,provenance) VALUES('C','NOOWNER','CHARACTER','NOPE','ASSET','X','TITLE',1,10,1,'ACTIVE','bad')")}
        fail{d.execSQL("INSERT INTO ownership_records(campaign_id,ownership_record_uid,owner_kind_uid,owner_uid,asset_kind_uid,asset_uid,ownership_type_uid,share_units,valid_from_order,record_version,record_status,provenance) VALUES('C','NOASSET','CHARACTER','B','ASSET','NOPE','TITLE',1,10,1,'ACTIVE','bad')")}
        fail{d.execSQL("INSERT INTO ownership_records(campaign_id,ownership_record_uid,owner_kind_uid,owner_uid,asset_kind_uid,asset_uid,ownership_type_uid,share_units,valid_from_order,record_version,record_status,provenance) VALUES('C','B','CHARACTER','B','ASSET','X','TITLE',1800000000.5,10,1,'ACTIVE','bad')")}
        fail{d.execSQL("INSERT INTO ownership_records(campaign_id,ownership_record_uid,owner_kind_uid,owner_uid,asset_kind_uid,asset_uid,ownership_type_uid,share_units,valid_from_order,record_version,record_status,provenance) VALUES('C','B','CHARACTER','B','ASSET','X','TITLE',${OWNERSHIP_SHARE_SCALE/2},10,1,'ACTIVE','bad')")}
        fail{d.execSQL("UPDATE ownership_records SET owner_uid='HACK' WHERE campaign_id='C' AND ownership_record_uid='A'")};fail{d.execSQL("DELETE FROM ownership_records WHERE campaign_id='C' AND ownership_record_uid='A'")}
        s.close("CLOSE",owner("A"),x,"TITLE",20,"EV-CLOSE","close");fail{d.execSQL("UPDATE ownership_records SET valid_until_order=30,record_version=record_version+1,record_status='CLOSED',closed_by_event_uid='EV2',closure_provenance='again' WHERE campaign_id='C' AND ownership_record_uid='A'")}
    }}

    private fun scalar(d:SQLiteDatabase,q:String)=d.rawQuery(q,null).use{c->c.moveToFirst();c.getInt(0)}
}
