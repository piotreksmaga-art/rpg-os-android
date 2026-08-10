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
class InventoryContextBuilderTest{
 private lateinit var root:File
 @Before fun setUp(){root=File(System.getProperty("java.io.tmpdir"),"rpgos-inv-context-${System.nanoTime()}/saves/C.campaign");root.mkdirs();File(root,"campaign.json").writeText("{\"id\":\"C\"}")}
 @After fun tearDown(){root.parentFile?.parentFile?.deleteRecursively()}
 @Test fun authoritativeContextSeesMoreThanOneThousandInventoryEntriesWithoutLegacyLimit(){
  val saveFile=File(root,"campaign.db");val worldFile=File(root,"world.db")
  SQLiteDatabase.openOrCreateDatabase(saveFile,null).use{save->SQLiteDatabase.openOrCreateDatabase(worldFile,null).use{world->
   fun schema():String=save.rawQuery("PRAGMA table_info(character_inventory)",null).use{c->buildList{while(c.moveToNext())add((0 until c.columnCount).joinToString("|"){i->c.getString(i)?:"NULL"})}.joinToString(";")}
   fun step(name:String,block:()->Unit){try{block()}catch(t:Throwable){throw AssertionError("INVENTORY_CONTEXT_DIAGNOSTIC step=$name schema=${schema()} type=${t.javaClass.name} message=${t.message}",t)}}
   step("CurrentSchema.ensure"){CurrentSchema.ensure(save,"C")}
   step("active_player_ref"){save.execSQL("INSERT OR REPLACE INTO active_player_ref(campaign_id,player_uid,updated_at,source) VALUES('C','P',1,'test')")}
   val store=InventoryStore(save,"C");val defs=(0..1000).map{ItemDefinition("I$it","W","i$it","Item $it",storagePolicy=ItemStoragePolicy.STACKABLE,provenance="pack")}
   step("registerDefinitions"){store.registerDefinitions("W",defs)}
   step("addStack"){defs.forEach{store.addStack("P",it.itemDefinitionUid,1,"bulk")}}
   val hasLegacy=save.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='character_inventory'",null).use{it.moveToFirst()}
   if(!hasLegacy) step("createLegacy"){save.execSQL("CREATE TABLE character_inventory(entity_uid TEXT,item_name TEXT)")}
   step("insertLegacy"){save.execSQL("INSERT INTO character_inventory(entity_uid,item_name) VALUES('P','Legacy unresolved')")}
   val bundle=stepResult("ContextBuilder.build",schema()){ContextBuilder(save,world).build("look",1)}
   assertEquals(1002,bundle.playerInventory.size);assertTrue(bundle.playerInventory.any{it["item_definition_uid"]=="I1000"});assertTrue(bundle.playerInventory.any{it["canonical"]==false&&it["item_name"]=="Legacy unresolved"})
  }}
 }
 private fun <T> stepResult(name:String,schema:String,block:()->T):T=try{block()}catch(t:Throwable){throw AssertionError("INVENTORY_CONTEXT_DIAGNOSTIC step=$name schema=$schema type=${t.javaClass.name} message=${t.message}",t)}
}
