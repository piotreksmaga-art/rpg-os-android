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
class EquipmentPersistenceTest {
    private lateinit var f: File
    @Before fun setUp(){ f=File.createTempFile("equip-",".db"); f.delete() }
    @After fun tearDown(){ f.delete() }
    private fun db()=SQLiteDatabase.openOrCreateDatabase(f,null)
    private fun fail(block:()->Unit){ var failed=false;try{block()}catch(_:Throwable){failed=true};assertTrue(failed) }
    private fun item(uid:String,pack:String="W")=ItemDefinition(uid,pack,uid.lowercase(),uid,storagePolicy=ItemStoragePolicy.UNIQUE_INSTANCE,provenance="pack")
    private fun slot(uid:String,pack:String="W",capacity:Int=1,group:String?=null)=EquipmentSlotDefinition(uid,pack,uid.lowercase(),uid,capacity=capacity,exclusiveGroupUid=group,provenance="pack")
    private fun seed(d:SQLiteDatabase,campaign:String="C",character:String="P",uids:List<String> = listOf("X")): Pair<InventoryStore,EquipmentStore> {
        CurrentSchema.ensure(d,campaign)
        val inventory=InventoryStore(d,campaign)
        inventory.registerDefinitions("W",uids.map{item("D$it")})
        uids.forEach{ inventory.createInstance(ItemInstance(campaign,it,"D$it",provenance="instance")); inventory.addUnique(character,it,"possessed") }
        return inventory to EquipmentStore(d,campaign)
    }

    @Test fun slotDefinitionsWorldPackIdentityScaleMigrationAndNoLegacySynthesis(){ db().use{d->
        d.execSQL("CREATE TABLE character_inventory(entity_uid TEXT,item_name TEXT)")
        d.execSQL("INSERT INTO character_inventory VALUES('P','legacy')")
        d.execSQL("CREATE TABLE character_techniques(entity_uid TEXT,technique_uid TEXT,is_equipped INTEGER)")
        d.execSQL("INSERT INTO character_techniques VALUES('P','T',1)")
        CurrentSchema.ensure(d,"C");CurrentSchema.ensure(d,"C")
        val e=EquipmentStore(d,"C")
        val slots=(0..1004).map{slot("S$it")}
        e.registerSlots("W",slots)
        assertEquals(1005,e.slotDefinitions().size)
        fail{e.registerSlots("W",listOf(slot("S0")))}
        fail{e.registerSlots("OTHER",listOf(slot("BAD","W")))}
        assertEquals(0,scalar(d,"SELECT COUNT(*) FROM player_equipment"))
        assertEquals(1,scalar(d,"SELECT COUNT(*) FROM rpgos_schema_migrations WHERE migration_id='$PHASE11_MIGRATION_ID'"))
        d.rawQuery("PRAGMA integrity_check",null).use{c->c.moveToFirst();assertEquals("ok",c.getString(0))}
        d.rawQuery("PRAGMA foreign_key_check",null).use{c->assertFalse(c.moveToFirst())}
    }}

    @Test fun possessedExactInstanceCompatibilityMultiSlotConflictsAndUnequipAreAtomic(){ db().use{d->
        val (inv,e)=seed(d,uids=listOf("X","Y","Z"))
        e.registerSlots("W",listOf(slot("A",group="G"),slot("B"),slot("C",group="G")))
        e.registerCompatibilityRules("W",listOf(
            EquipmentCompatibilityRule("RX","W","DX",listOf("B","A"),provenance="r"),
            EquipmentCompatibilityRule("RY","W","DY",listOf("B"),provenance="r"),
            EquipmentCompatibilityRule("RZ","W","DZ",listOf("C"),provenance="r")
        ))
        fail{e.equip("P","missing","RX",listOf("A","B"),"E0","x")}
        inv.createInstance(ItemInstance("C","U","DX",provenance="u"))
        fail{e.equip("P","U","RX",listOf("A","B"),"EU","x")}
        fail{e.equip("P","X","RX",listOf("A","C"),"EW","x")}
        e.equip("P","Y","RY",listOf("B"),"EY","y")
        fail{e.equip("P","X","RX",listOf("A","B"),"EX","x")}
        assertEquals(listOf("B"),e.equipment("P").single().occupiedSlotUids)
        e.unequip("P","EY")
        val x=e.equip("P","X","RX",listOf("A","B"),"EX","x")
        assertEquals(listOf("A","B"),x.occupiedSlotUids)
        fail{e.equip("P","X","RX",listOf("B","A"),"EX2","dup")}
        fail{e.equip("P","Z","RZ",listOf("C"),"EZ","conflict")}
        assertEquals(1,e.equipment("P").size)
        assertEquals(3,inv.typedUnique("P").size)
        e.unequip("P","EX")
        assertTrue(e.equipment("P").isEmpty())
        assertEquals(3,inv.typedUnique("P").size)
    }}

    @Test fun sameDefinitionDifferentInstancesPlayerAndCampaignIsolation(){ db().use{d->
        CurrentSchema.ensure(d,"A")
        val ia=InventoryStore(d,"A");ia.registerDefinitions("W",listOf(item("D")))
        listOf("X","Y").forEach{ia.createInstance(ItemInstance("A",it,"D",provenance="i"))}
        ia.addUnique("P","X","p");ia.addUnique("Q","Y","q")
        val ea=EquipmentStore(d,"A");ea.registerSlots("W",listOf(slot("S",capacity=2)));ea.registerCompatibilityRules("W",listOf(EquipmentCompatibilityRule("R","W","D",listOf("S"),provenance="r")))
        ea.equip("P","X","R",listOf("S"),"EX","p")
        ea.equip("Q","Y","R",listOf("S"),"EY","q")
        assertEquals("X",ea.equipment("P").single().itemInstance.itemInstanceUid)
        assertEquals("Y",ea.equipment("Q").single().itemInstance.itemInstanceUid)
        val ib=InventoryStore(d,"B");ib.createInstance(ItemInstance("B","X","D",provenance="b"));ib.addUnique("P","X","b")
        val eb=EquipmentStore(d,"B");eb.equip("P","X","R",listOf("S"),"EB","b")
        assertEquals("X",eb.equipment("P").single().itemInstance.itemInstanceUid)
        assertEquals(1,ea.equipment("P").size)
    }}

    @Test fun equippedInventoryTransferAndRemovalFailLoudUntilExplicitUnequip(){ db().use{d->
        val (inv,e)=seed(d,uids=listOf("X"))
        e.registerSlots("W",listOf(slot("S")));e.registerCompatibilityRules("W",listOf(EquipmentCompatibilityRule("R","W","DX",listOf("S"),provenance="r")))
        e.equip("P","X","R",listOf("S"),"E","equip")
        fail{inv.removeUnique("P","X")}
        fail{inv.transferUnique("P","Q","X","transfer")}
        assertEquals("P",inv.typedUnique("P").single().first.characterUid)
        assertEquals(1,e.equipment("P").size)
        e.unequip("P","E")
        inv.transferUnique("P","Q","X","transfer")
        assertTrue(inv.typedUnique("P").isEmpty());assertEquals("X",inv.typedUnique("Q").single().first.itemInstanceUid)
    }}

    @Test fun stalePossessionPrecheckCannotCommitEquipmentAfterTransfer(){ db().use{d->
        val (inv,e)=seed(d,uids=listOf("X"))
        e.registerSlots("W",listOf(slot("S")))
        e.registerCompatibilityRules("W",listOf(EquipmentCompatibilityRule("R","W","DX",listOf("S"),provenance="r")))
        assertEquals("X",inv.typedUnique("P").single().first.itemInstanceUid)
        inv.transferUnique("P","Q","X","race-winner")
        fail {
            d.execSQL("INSERT INTO player_equipment(campaign_id,character_uid,equipment_entry_uid,item_instance_uid,compatibility_rule_uid,loadout_uid,entry_version,provenance) VALUES('C','P','STALE','X','R','$DEFAULT_EQUIPMENT_LOADOUT_UID',1,'stale-precheck')")
        }
        assertTrue(e.equipment("P").isEmpty())
        assertEquals("X",inv.typedUnique("Q").single().first.itemInstanceUid)
        assertEquals(0,scalar(d,"SELECT COUNT(*) FROM player_equipment WHERE campaign_id='C' AND item_instance_uid='X'"))
    }}

    @Test fun staleCapacityAndExclusivePrechecksCannotCommitSecondWinnerAndRollbackMultiSlot(){ db().use{d->
        val (_,e)=seed(d,uids=listOf("X","Y","Z"))
        e.registerSlots("W",listOf(slot("S",capacity=1),slot("A",group="G"),slot("B"),slot("C",group="G")))
        e.registerCompatibilityRules("W",listOf(
            EquipmentCompatibilityRule("RX","W","DX",listOf("S"),provenance="r"),
            EquipmentCompatibilityRule("RY","W","DY",listOf("S"),provenance="r"),
            EquipmentCompatibilityRule("RZ","W","DZ",listOf("A","B"),provenance="r")
        ))
        assertEquals(0,scalar(d,"SELECT COUNT(*) FROM player_equipment_slots WHERE campaign_id='C' AND character_uid='P' AND slot_uid='S'"))
        e.equip("P","Y","RY",listOf("S"),"EY","winner")
        fail {
            d.beginTransaction()
            try {
                d.execSQL("INSERT INTO player_equipment VALUES('C','P','EX','X','RX','$DEFAULT_EQUIPMENT_LOADOUT_UID',1,'stale-capacity')")
                d.execSQL("INSERT INTO player_equipment_slots VALUES('C','P','EX','S')")
                d.setTransactionSuccessful()
            } finally { d.endTransaction() }
        }
        assertEquals(1,scalar(d,"SELECT COUNT(*) FROM player_equipment"))
        assertEquals(1,scalar(d,"SELECT COUNT(*) FROM player_equipment_slots WHERE slot_uid='S'"))
        e.unequip("P","EY")
        e.equip("P","Z","RZ",listOf("A","B"),"EZ","multi")
        assertEquals(listOf("A","B"),e.equipment("P").single().occupiedSlotUids)
        d.rawQuery("PRAGMA integrity_check",null).use{c->c.moveToFirst();assertEquals("ok",c.getString(0))}
        d.rawQuery("PRAGMA foreign_key_check",null).use{c->assertFalse(c.moveToFirst())}
    }}

    @Test fun reopenPreservesExactEntrySlotsAndNoAuthoritativeTruncation() {
        db().use { d ->
            CurrentSchema.ensure(d,"C")
            val inv=InventoryStore(d,"C")
            inv.registerDefinitions("W",listOf(item("D")))
            val e=EquipmentStore(d,"C")
            e.registerSlots("W",listOf(slot("S",capacity=1100)))
            e.registerCompatibilityRules("W",listOf(EquipmentCompatibilityRule("R","W","D",listOf("S"),provenance="r")))
            for(i in 0..1000){
                val uid="I$i"
                inv.createInstance(ItemInstance("C",uid,"D",provenance="i"))
                inv.addUnique("P",uid,"p")
                e.equip("P",uid,"R",listOf("S"),"E$i","e")
            }
            assertEquals(1001,e.equipment("P").size)
        }
        SQLiteDatabase.openDatabase(f.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use { d ->
            CurrentSchema.ensure(d,"C")
            val e=EquipmentStore(d,"C")
            assertEquals(1001,e.equipment("P").size)
            assertEquals(listOf("S"),e.equipment("P").first().occupiedSlotUids)
        }
    }

    private fun scalar(d:SQLiteDatabase,q:String)=d.rawQuery(q,null).use{c->c.moveToFirst();c.getInt(0)}
}
