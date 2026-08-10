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
   CurrentSchema.ensure(save,"C");save.execSQL("INSERT OR REPLACE INTO active_player_ref(campaign_id,player_uid,updated_at) VALUES('C','P',1)")
   val store=InventoryStore(save,"C");val defs=(0..1000).map{ItemDefinition("I$it","W","i$it","Item $it",storagePolicy=ItemStoragePolicy.STACKABLE,provenance="pack")};store.registerDefinitions("W",defs);defs.forEach{store.addStack("P",it.itemDefinitionUid,1,"bulk")}
   val hasLegacy=save.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='character_inventory'",null).use{it.moveToFirst()}
   if(!hasLegacy) save.execSQL("CREATE TABLE character_inventory(entity_uid TEXT,item_name TEXT)")
   save.execSQL("INSERT INTO character_inventory(entity_uid,item_name) VALUES('P','Legacy unresolved')")
   val bundle=ContextBuilder(save,world).build("look",1);assertEquals(1002,bundle.playerInventory.size);assertTrue(bundle.playerInventory.any{it["item_definition_uid"]=="I1000"});assertTrue(bundle.playerInventory.any{it["canonical"]==false&&it["item_name"]=="Legacy unresolved"})
  }}
 }
}
