package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class InventoryTransferAndAmbiguityTest{
 private fun fail(block:()->Unit){var x=false;try{block()}catch(_:Throwable){x=true};assertTrue(x)}
 @Test fun stackAndUniqueTransfersAreAtomicPossessionOnly(){val f=File.createTempFile("inv-transfer-",".db");f.delete();try{SQLiteDatabase.openOrCreateDatabase(f,null).use{d->CurrentSchema.ensure(d,"C");val s=InventoryStore(d,"C");s.registerDefinitions("W",listOf(ItemDefinition("STACK","W","stack","Stack",storagePolicy=ItemStoragePolicy.STACKABLE,provenance="p"),ItemDefinition("UNIQUE","W","unique","Unique",storagePolicy=ItemStoragePolicy.UNIQUE_INSTANCE,provenance="p")));s.addStack("A","STACK",10,"a");s.transferStack("A","B","STACK",3,"move");assertEquals(7L,s.typedStacks("A").single().quantity);assertEquals(3L,s.typedStacks("B").single().quantity);fail{s.transferStack("A","B","STACK",99,"bad")};assertEquals(7L,s.typedStacks("A").single().quantity);assertEquals(3L,s.typedStacks("B").single().quantity);s.createInstance(ItemInstance("C","X","UNIQUE",provenance="x"));s.addUnique("A","X","a");s.transferUnique("A","B","X","move");assertTrue(s.typedUnique("A").isEmpty());assertEquals("X",s.typedUnique("B").single().first.itemInstanceUid);assertEquals(0,d.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('player_equipment','ownership_records_v2')",null).use{c->c.moveToFirst();c.getInt(0)})}}finally{f.delete()}}
 @Test fun duplicateNameRowsRemainAmbiguousAndCannotBecomeQuantity(){val f=File.createTempFile("inv-dup-legacy-",".db");f.delete();try{SQLiteDatabase.openOrCreateDatabase(f,null).use{d->d.execSQL("CREATE TABLE character_inventory(entity_uid TEXT,item_name TEXT)");d.execSQL("INSERT INTO character_inventory VALUES('P','Same')");d.execSQL("INSERT INTO character_inventory VALUES('P','Same')");CurrentSchema.ensure(d,"C");val s=InventoryStore(d,"C");s.registerDefinitions("W",listOf(ItemDefinition("D","W","d","Same",storagePolicy=ItemStoragePolicy.STACKABLE,provenance="p")));val e=s.legacyEvidence("P").single();assertEquals(2L,e.rowCount);fail{s.registerLegacyMappings("W",listOf(LegacyInventoryMapping("C","P",e.evidenceUid,"D",worldPackUid="W",provenance="map")))};assertEquals(1,s.reconciled("P").unresolvedLegacy.size);assertTrue(s.typedStacks("P").isEmpty())}}finally{f.delete()}}
}
