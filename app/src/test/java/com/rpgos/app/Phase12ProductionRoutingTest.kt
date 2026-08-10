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
class Phase12ProductionRoutingTest{
 private lateinit var context:Context;private lateinit var root:File
 @Before fun setUp(){context=RuntimeEnvironment.getApplication();context.getSharedPreferences("rpgos_selection",Context.MODE_PRIVATE).edit().clear().commit();root=File(context.filesDir,"rpgos");root.deleteRecursively();root.mkdirs()}
 @After fun tearDown(){context.getSharedPreferences("rpgos_selection",Context.MODE_PRIVATE).edit().clear().commit();root.deleteRecursively()}
 @Test fun bootstrapRoutesBundledCampaignThroughV12(){val s=LocalGameStore(context);s.bootstrap();assertV12(File(root,"saves/${ActiveCampaignRef.DEFAULT_DIRECTORY}/campaign.db"))}
 @Test fun campaignSwitchRoutesV11ThroughV12(){create(ActiveCampaignRef.DEFAULT_DIRECTORY,ActiveCampaignRef.DEFAULT_CAMPAIGN_ID);val other=create("Other.campaign","other");LocalGameStore(context).setActiveCampaign("Other.campaign");assertV12(other)}
 @Test fun restoreRoutesV11ThroughV12WithoutSyntheticOwnership(){val active=create(ActiveCampaignRef.DEFAULT_DIRECTORY,ActiveCampaignRef.DEFAULT_CAMPAIGN_ID);val b=File(active.parentFile,"backups/old.db").apply{parentFile?.mkdirs()};v11(b,ActiveCampaignRef.DEFAULT_CAMPAIGN_ID);SQLiteDatabase.openDatabase(b.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{d->d.execSQL("CREATE TABLE IF NOT EXISTS character_inventory(entity_uid TEXT,item_name TEXT)");d.execSQL("INSERT INTO character_inventory VALUES('P','legacy')")};val safety=LocalGameStore(context).restoreBackup(b.absolutePath);assertTrue(File(safety).isFile);assertV12(active);SQLiteDatabase.openDatabase(active.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{d->d.rawQuery("SELECT COUNT(*) FROM ownership_records",null).use{c->c.moveToFirst();assertEquals(0,c.getInt(0))}}}
 private fun create(dir:String,id:String):File{val d=File(root,"saves/$dir").apply{mkdirs()};File(d,"campaign.json").writeText("{\"id\":\"$id\"}");return File(d,"campaign.db").also{v11(it,id)}}
 private fun v11(f:File,id:String){f.parentFile?.mkdirs();SQLiteDatabase.openOrCreateDatabase(f,null).use{MigrationManager().ensureV11(it,id)}}
 private fun assertV12(f:File){SQLiteDatabase.openDatabase(f.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{d->d.rawQuery("SELECT COUNT(*) FROM rpgos_schema_migrations WHERE migration_id=?",arrayOf(PHASE12_MIGRATION_ID)).use{c->c.moveToFirst();assertEquals(1,c.getInt(0))};d.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('ownership_records','ownership_operations','legacy_ownership_mappings')",null).use{c->c.moveToFirst();assertEquals(3,c.getInt(0))};d.rawQuery("PRAGMA integrity_check",null).use{c->c.moveToFirst();assertEquals("ok",c.getString(0))}}}
}
