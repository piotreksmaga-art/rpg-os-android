package com.rpgos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class InventoryBackupRestoreTest{
 private lateinit var context:Context;private lateinit var root:File;private lateinit var dbFile:File
 @Before fun setUp(){context=RuntimeEnvironment.getApplication();context.getSharedPreferences("rpgos_selection",Context.MODE_PRIVATE).edit().clear().commit();root=File(context.filesDir,"rpgos");root.deleteRecursively();val dir=File(root,"saves/${ActiveCampaignRef.DEFAULT_DIRECTORY}").apply{mkdirs()};File(dir,"campaign.json").writeText("{\"id\":\"${ActiveCampaignRef.DEFAULT_CAMPAIGN_ID}\"}");dbFile=File(dir,"campaign.db")}
 @After fun tearDown(){context.getSharedPreferences("rpgos_selection",Context.MODE_PRIVATE).edit().clear().commit();root.deleteRecursively()}
 @Test fun backupRestorePreservesTypedInventoryMappingAndLegacyBytes(){
  SQLiteDatabase.openOrCreateDatabase(dbFile,null).use{d->d.execSQL("CREATE TABLE character_inventory(entity_uid TEXT,item_name TEXT,quantity INTEGER)");d.execSQL("INSERT INTO character_inventory VALUES('P','Legacy',2)");CurrentSchema.ensure(d,ActiveCampaignRef.DEFAULT_CAMPAIGN_ID);val s=InventoryStore(d,ActiveCampaignRef.DEFAULT_CAMPAIGN_ID);s.registerDefinitions("W",listOf(ItemDefinition("D","W","d","Definition",storagePolicy=ItemStoragePolicy.STACKABLE,provenance="pack")));s.addStack("P","D",5,"typed");val e=s.legacyEvidence("P").single();s.registerLegacyMappings("W",listOf(LegacyInventoryMapping(ActiveCampaignRef.DEFAULT_CAMPAIGN_ID,"P",e.evidenceUid,"D",worldPackUid="W",mappingVersion=3,provenance="explicit")))}
  val backup=BackupManager(context).createBackup("phase10")
  SQLiteDatabase.openDatabase(dbFile.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{d->d.delete("player_inventory_stacks",null,null);d.delete("legacy_inventory_mappings",null,null);d.delete("character_inventory",null,null)}
  val safety=LocalGameStore(context).restoreBackup(backup.absolutePath);assertTrue(File(safety).isFile)
  SQLiteDatabase.openDatabase(dbFile.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{d->val s=InventoryStore(d,ActiveCampaignRef.DEFAULT_CAMPAIGN_ID);assertEquals(5L,s.typedStacks("P").single().quantity);assertTrue(s.reconciled("P").unresolvedLegacy.isEmpty());d.rawQuery("SELECT item_name,quantity FROM character_inventory WHERE entity_uid='P'",null).use{c->assertTrue(c.moveToFirst());assertEquals("Legacy",c.getString(0));assertEquals(2,c.getInt(1))};d.rawQuery("SELECT mapping_version,provenance FROM legacy_inventory_mappings",null).use{c->assertTrue(c.moveToFirst());assertEquals(3,c.getInt(0));assertEquals("explicit",c.getString(1))}}
 }
}
