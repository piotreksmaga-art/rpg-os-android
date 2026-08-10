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
class Phase10ProductionRoutingTest{
 private lateinit var context:Context;private lateinit var root:File
 @Before fun setUp(){context=RuntimeEnvironment.getApplication();context.getSharedPreferences("rpgos_selection",Context.MODE_PRIVATE).edit().clear().commit();root=File(context.filesDir,"rpgos");root.deleteRecursively();root.mkdirs()}
 @After fun tearDown(){context.getSharedPreferences("rpgos_selection",Context.MODE_PRIVATE).edit().clear().commit();root.deleteRecursively()}
 @Test fun bootstrapRoutesBundledCampaignThroughV10(){val s=LocalGameStore(context);s.bootstrap();assertV10(File(root,"saves/${ActiveCampaignRef.DEFAULT_DIRECTORY}/campaign.db"))}
 @Test fun campaignSwitchRoutesV9ThroughV10(){create(ActiveCampaignRef.DEFAULT_DIRECTORY,ActiveCampaignRef.DEFAULT_CAMPAIGN_ID);val other=create("Other.campaign","other");LocalGameStore(context).setActiveCampaign("Other.campaign");assertV10(other)}
 @Test fun restoreRoutesV9ThroughV10(){val active=create(ActiveCampaignRef.DEFAULT_DIRECTORY,ActiveCampaignRef.DEFAULT_CAMPAIGN_ID);val b=File(active.parentFile,"backups/old.db").apply{parentFile?.mkdirs()};v9(b,ActiveCampaignRef.DEFAULT_CAMPAIGN_ID);val safety=LocalGameStore(context).restoreBackup(b.absolutePath);assertTrue(File(safety).isFile);assertV10(active)}
 private fun create(dir:String,id:String):File{val d=File(root,"saves/$dir").apply{mkdirs()};File(d,"campaign.json").writeText("{\"id\":\"$id\"}");return File(d,"campaign.db").also{v9(it,id)}}
 private fun v9(f:File,id:String){f.parentFile?.mkdirs();SQLiteDatabase.openOrCreateDatabase(f,null).use{MigrationManager().ensureV9RequirementHotfix(it,id)}}
 private fun assertV10(f:File){SQLiteDatabase.openDatabase(f.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{d->d.rawQuery("SELECT COUNT(*) FROM rpgos_schema_migrations WHERE migration_id=?",arrayOf(PHASE10_MIGRATION_ID)).use{c->c.moveToFirst();assertEquals(1,c.getInt(0))};d.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='item_definitions_v2'",null).use{c->c.moveToFirst();assertEquals(1,c.getInt(0))}}}
}
