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

    @Test fun exactShareSemanticsRejectInvalidPrecisionAndOverflow(){
        assertEquals(OwnershipShare.ofFraction(1,3),OwnershipShare.ofFraction(2,6))
        assertEquals(1L,OwnershipShare.ofFraction(1,3).numerator);assertEquals(3L,OwnershipShare.ofFraction(1,3).denominator)
        assertEquals(OwnershipShare.full(),OwnershipShare.ofFraction(5,5))
        fail{OwnershipShare.ofFraction(0,1)};fail{OwnershipShare.ofFraction(-1,2)};fail{OwnershipShare.ofFraction(2,1)};fail{OwnershipShare.ofFraction(1,0)}
        fail{OwnershipShare.ofFraction(1,7)}
        fail{OwnershipShare.ofUnits(Long.MAX_VALUE)}
        fail{OwnershipShare.full().add(OwnershipShare.ofFraction(1,2))}
    }

    @Test fun temporalHistoryFullTransferStableIdentityGenericOwnersAndAssets(){db().use{d->
        CurrentSchema.ensure(d,"C");val s=OwnershipStore(d,"C");val x=asset("PROPERTY-X","PROPERTY")
        val original=rec(uid="R-A",who="A",what=x);s.acquire(original)
        val r=s.fullTransfer("OP-1",owner("A"),owner("ORG-9","ORGANIZATION"),x,"TITLE",20,"EV-SALE","sale")
        assertEquals("R-A",r.closedSource.ownershipRecordUid);assertEquals(20L,r.closedSource.validUntil);assertEquals(OwnershipRecordStatus.CLOSED,r.closedSource.status)
        assertEquals("ORG-9",s.currentOwnership(x,"TITLE").single().owner.ownerUid)
        assertEquals("A",s.ownershipAt(x,19,"TITLE").single().owner.ownerUid)
        assertEquals("ORG-9",s.ownershipAt(x,20,"TITLE").single().owner.ownerUid)
        assertEquals(2,s.history(x,"TITLE").size)
        assertEquals("EV-SALE",r.destinationSuccessor.sourceEventUid);assertEquals("EV-SALE",r.closedSource.closedByEventUid)
        val retry=s.fullTransfer("OP-1",owner("A"),owner("ORG-9","ORGANIZATION"),x,"TITLE",20,"EV-SALE","sale")
        assertEquals(r.destinationSuccessor.ownershipRecordUid,retry.destinationSuccessor.ownershipRecordUid);assertEquals(2,s.history(x,"TITLE").size)
        fail{s.acquire(rec(uid="R-DUP",who="ORG-9",what=x,from=25))}
        fail{s.acquire(original)}
    }}

    @Test fun coOwnershipPartialTransferConservesExactlyAndDbRejectsOverAllocation(){db().use{d->
        val s=OwnershipStore(d,"C");val x=asset("BIZ")
        s.acquire(rec(uid="A60",who="A",what=x,share=OwnershipShare.ofFraction(3,5)))
        s.acquire(rec(uid="B40",who="B",what=x,share=OwnershipShare.ofFraction(2,5)))
        assertEquals(OWNERSHIP_SHARE_SCALE,s.currentOwnership(x,"TITLE").sumOf{it.share.units})
        val moved=s.transferShare("OP-SHARE",owner("A"),owner("B"),x,"TITLE",OwnershipShare.ofFraction(1,5),20,"EV-SHARE","share-sale")
        val current=s.currentOwnership(x,"TITLE").associate{it.owner.ownerUid to it.share}
        assertEquals(OwnershipShare.ofFraction(2,5),current.getValue("A"));assertEquals(OwnershipShare.ofFraction(3,5),current.getValue("B"))
        assertEquals(OWNERSHIP_SHARE_SCALE,current.values.sumOf{it.units});assertEquals(4,s.history(x,"TITLE").size)
        assertNotNull(moved.sourceSuccessor)
        fail{s.acquire(rec(uid="C20",who="C",what=x,share=OwnershipShare.ofFraction(1,5),from=20))}
        fail{s.transferShare("TOO-MUCH",owner("A"),owner("C"),x,"TITLE",OwnershipShare.ofFraction(3,5),30,"EV-BAD","bad")}
        assertEquals(OWNERSHIP_SHARE_SCALE,s.currentOwnership(x,"TITLE").sumOf{it.share.units})
    }}

    @Test fun itemInstanceOwnershipIsIndependentFromPossessionEquipmentTheftLoanAndLoss(){db().use{d->
        CurrentSchema.ensure(d,"C")
        val inv=InventoryStore(d,"C")
        inv.registerDefinitions("W",listOf(ItemDefinition("D","W","relic","Relic",storagePolicy=ItemStoragePolicy.UNIQUE_INSTANCE,provenance="pack")))
        inv.createInstance(ItemInstance("C","X","D",provenance="instance"));inv.addUnique("A","X","held")
        val own=OwnershipStore(d,"C");val x=asset("X",OWNERSHIP_ASSET_KIND_ITEM_INSTANCE)
        own.acquire(rec(uid="TITLE-X",who="A",what=x))
        inv.transferUnique("A","B","X","theft-or-loan")
        assertEquals("A",own.currentOwnership(x,"TITLE").single().owner.ownerUid)
        assertEquals("X",inv.typedUnique("B").single().first.itemInstanceUid)
        val eq=EquipmentStore(d,"C")
        eq.registerSlots("W",listOf(EquipmentSlotDefinition("S","W","hand","Hand",provenance="pack")))
        eq.registerCompatibilityRules("W",listOf(EquipmentCompatibilityRule("R","W","D",listOf("S"),provenance="pack")))
        eq.equip("B","X","R",listOf("S"),"E","borrowed");assertEquals("A",own.currentOwnership(x,"TITLE").single().owner.ownerUid)
        eq.unequip("B","E");inv.removeUnique("B","X");assertEquals("A",own.currentOwnership(x,"TITLE").single().owner.ownerUid)
        fail{own.acquire(rec(uid="MISSING",who="A",what=asset("NOPE",OWNERSHIP_ASSET_KIND_ITEM_INSTANCE)))}
    }}

    @Test fun ownershipTransferDoesNotMovePhysicalPossession(){db().use{d->
        CurrentSchema.ensure(d,"C")
        val inv=InventoryStore(d,"C")
        inv.registerDefinitions("W",listOf(ItemDefinition("D","W","title-only","Title Only",storagePolicy=ItemStoragePolicy.UNIQUE_INSTANCE,provenance="pack")))
        inv.createInstance(ItemInstance("C","X","D",provenance="instance"));inv.addUnique("A","X","held")
        val own=OwnershipStore(d,"C");val x=asset("X",OWNERSHIP_ASSET_KIND_ITEM_INSTANCE)
        own.acquire(rec(uid="TITLE-X",who="A",what=x))
        own.fullTransfer("TITLE-TRANSFER",owner("A"),owner("B"),x,"TITLE",20,"EV-TITLE","legal-title-only")
        assertEquals("B",own.currentOwnership(x,"TITLE").single().owner.ownerUid)
        assertEquals("X",inv.typedUnique("A").single().first.itemInstanceUid)
        assertTrue(inv.typedUnique("B").isEmpty())
    }}

    @Test fun migrationNeverSynthesizesLegacyOwnershipCampaignsAreIsolatedAndScaleIsUnboundedByReaders(){
        db().use{d->
            d.execSQL("CREATE TABLE character_inventory(entity_uid TEXT,item_name TEXT)");d.execSQL("INSERT INTO character_inventory VALUES('P','Same')")
            d.execSQL("CREATE TABLE character_techniques(entity_uid TEXT,technique_uid TEXT,is_equipped INTEGER)");d.execSQL("INSERT INTO character_techniques VALUES('P','T',1)")
            CurrentSchema.ensure(d,"C");CurrentSchema.ensure(d,"C")
            assertEquals(0,scalar(d,"SELECT COUNT(*) FROM ownership_records"));assertEquals(1,scalar(d,"SELECT COUNT(*) FROM rpgos_schema_migrations WHERE migration_id='$PHASE12_MIGRATION_ID'"))
            val c=OwnershipStore(d,"C");for(i in 0..1000)c.acquire(rec(uid="R$i",who="P",what=asset("A$i")))
            assertEquals(1001,c.ownershipByOwner(owner("P")).size)
            val dStore=OwnershipStore(d,"D");dStore.acquire(rec(c="D",uid="R0",who="P",what=asset("A0")))
            assertEquals(1,dStore.ownershipByOwner(owner("P")).size);assertEquals(1001,c.ownershipByOwner(owner("P")).size)
            c.registerLegacyMapping("PROVEN-LEGACY","R0",1,"explicit-map");fail{c.registerLegacyMapping("PROVEN-LEGACY","R0",1,"dup")}
            d.rawQuery("PRAGMA integrity_check",null).use{q->q.moveToFirst();assertEquals("ok",q.getString(0))}
            d.rawQuery("PRAGMA foreign_key_check",null).use{q->assertFalse(q.moveToFirst())}
        }
        SQLiteDatabase.openDatabase(f.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{d->
            assertEquals(1001,OwnershipStore(d,"C").ownershipByOwner(owner("P")).size)
        }
    }

    @Test fun authoritativeSqlBoundaryRejectsMalformedSharesOverlapIllegalUpdatesAndDeletes(){db().use{d->
        val s=OwnershipStore(d,"C");val x=asset("X");s.acquire(rec(uid="A",who="A",what=x,share=OwnershipShare.ofFraction(3,5)))
        fail{d.execSQL("INSERT INTO ownership_records(campaign_id,ownership_record_uid,owner_kind_uid,owner_uid,asset_kind_uid,asset_uid,ownership_type_uid,share_units,valid_from_order,record_version,record_status,provenance) VALUES('C','B','CHARACTER','B','ASSET','X','TITLE',1800000000.5,10,1,'ACTIVE','bad')")}
        fail{d.execSQL("INSERT INTO ownership_records(campaign_id,ownership_record_uid,owner_kind_uid,owner_uid,asset_kind_uid,asset_uid,ownership_type_uid,share_units,valid_from_order,record_version,record_status,provenance) VALUES('C','B','CHARACTER','B','ASSET','X','TITLE',${OWNERSHIP_SHARE_SCALE/2},10,1,'ACTIVE','bad')")}
        fail{d.execSQL("INSERT INTO ownership_records(campaign_id,ownership_record_uid,owner_kind_uid,owner_uid,asset_kind_uid,asset_uid,ownership_type_uid,share_units,valid_from_order,record_version,record_status,provenance) VALUES('C','A2','CHARACTER','A','ASSET','X','TITLE',${OWNERSHIP_SHARE_SCALE/5},11,1,'ACTIVE','overlap')")}
        fail{d.execSQL("UPDATE ownership_records SET owner_uid='HACK' WHERE campaign_id='C' AND ownership_record_uid='A'")}
        fail{d.execSQL("DELETE FROM ownership_records WHERE campaign_id='C' AND ownership_record_uid='A'")}
        s.close("CLOSE",owner("A"),x,"TITLE",20,"EV-CLOSE","close")
        fail{d.execSQL("UPDATE ownership_records SET valid_until_order=30,record_version=record_version+1,record_status='CLOSED',closed_by_event_uid='EV2',closure_provenance='again' WHERE campaign_id='C' AND ownership_record_uid='A'")}
        assertEquals(1,s.history(x,"TITLE").size);assertTrue(s.currentOwnership(x,"TITLE").isEmpty())
    }}

    private fun scalar(d:SQLiteDatabase,q:String)=d.rawQuery(q,null).use{c->c.moveToFirst();c.getInt(0)}
}
