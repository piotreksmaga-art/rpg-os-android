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
class Phase14ProductionRoutingTest {
 private lateinit var context:Context;private lateinit var root:File
 @Before fun setUp(){context=RuntimeEnvironment.getApplication();context.getSharedPreferences("rpgos_selection",Context.MODE_PRIVATE).edit().clear().commit();root=File(context.filesDir,"rpgos");root.deleteRecursively();root.mkdirs()}
 @After fun tearDown(){context.getSharedPreferences("rpgos_selection",Context.MODE_PRIVATE).edit().clear().commit();root.deleteRecursively()}

 @Test fun bootstrapRoutesBundledCampaignThroughV14(){LocalGameStore(context).bootstrap();assertV14(File(root,"saves/${ActiveCampaignRef.DEFAULT_DIRECTORY}/campaign.db"))}
 @Test fun campaignSwitchRoutesV13ThroughV14(){create(ActiveCampaignRef.DEFAULT_DIRECTORY,ActiveCampaignRef.DEFAULT_CAMPAIGN_ID);val other=create("Other.campaign","other");LocalGameStore(context).setActiveCampaign("Other.campaign");assertV14(other)}
 @Test fun restoreRoutesV13ThroughV14WithoutLegacyAssetLiabilitySynthesis(){val active=create(ActiveCampaignRef.DEFAULT_DIRECTORY,ActiveCampaignRef.DEFAULT_CAMPAIGN_ID);val backup=File(active.parentFile,"backups/v13.db").apply{parentFile?.mkdirs()};v13(backup,ActiveCampaignRef.DEFAULT_CAMPAIGN_ID);SQLiteDatabase.openDatabase(backup.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{d->d.execSQL("CREATE TABLE IF NOT EXISTS character_finances(entity_uid TEXT,debt INTEGER,property_value INTEGER,investment_value INTEGER)");d.execSQL("INSERT INTO character_finances VALUES('P',7,900,800)")};val safety=LocalGameStore(context).restoreBackup(backup.absolutePath);assertTrue(File(safety).isFile);assertV14(active);SQLiteDatabase.openDatabase(active.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{d->assertEquals(0L,n(d,"SELECT COUNT(*) FROM asset_records"));assertEquals(0L,n(d,"SELECT COUNT(*) FROM obligation_records"));assertEquals(7L,n(d,"SELECT debt FROM character_finances WHERE entity_uid='P'"));checks(d)}}
 @Test fun repeatedEnsureIsIdempotentAndDoesNotRecreateAuthority(){val f=create(ActiveCampaignRef.DEFAULT_DIRECTORY,ActiveCampaignRef.DEFAULT_CAMPAIGN_ID);SQLiteDatabase.openDatabase(f.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{d->CurrentSchema.ensure(d,ActiveCampaignRef.DEFAULT_CAMPAIGN_ID);CurrentSchema.ensure(d,ActiveCampaignRef.DEFAULT_CAMPAIGN_ID);assertEquals(1L,n(d,"SELECT COUNT(*) FROM rpgos_schema_migrations WHERE migration_id='$PHASE14_MIGRATION_ID'"));assertEquals(0L,n(d,"SELECT COUNT(*) FROM asset_records"));assertEquals(0L,n(d,"SELECT COUNT(*) FROM obligation_records"));checks(d)}}

 private fun create(dir:String,id:String):File{val d=File(root,"saves/$dir").apply{mkdirs()};File(d,"campaign.json").writeText("{\"id\":\"$id\"}");return File(d,"campaign.db").also{v13(it,id)}}
 private fun v13(f:File,id:String){f.parentFile?.mkdirs();SQLiteDatabase.openOrCreateDatabase(f,null).use{MigrationManager().ensureV13ContractGuards(it,id)}}
 private fun assertV14(f:File){SQLiteDatabase.openDatabase(f.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{d->assertEquals(1L,n(d,"SELECT COUNT(*) FROM rpgos_schema_migrations WHERE migration_id='$PHASE14_MIGRATION_ID'"));assertEquals(8L,n(d,"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('asset_kind_definitions','asset_records','asset_valuations','obligation_type_definitions','obligation_records','obligation_status_history','obligation_settlements','asset_encumbrances')"));assertTrue(n(d,"SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name LIKE 'trg_p14_%'")>=15);checks(d)}}
 private fun n(d:SQLiteDatabase,sql:String)=d.rawQuery(sql,null).use{c->c.moveToFirst();c.getLong(0)}
 private fun checks(d:SQLiteDatabase){d.rawQuery("PRAGMA integrity_check",null).use{c->c.moveToFirst();assertEquals("ok",c.getString(0))};listOf("asset_records","asset_valuations","obligation_records","obligation_status_history","obligation_settlements","asset_encumbrances").forEach{t->d.rawQuery("PRAGMA foreign_key_check($t)",null).use{c->assertFalse("Phase14 FK violation in $t",c.moveToFirst())}}}
}
