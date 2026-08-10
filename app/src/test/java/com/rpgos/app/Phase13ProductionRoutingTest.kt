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
class Phase13ProductionRoutingTest{
 private lateinit var context:Context;private lateinit var root:File
 @Before fun setUp(){context=RuntimeEnvironment.getApplication();context.getSharedPreferences("rpgos_selection",Context.MODE_PRIVATE).edit().clear().commit();root=File(context.filesDir,"rpgos");root.deleteRecursively();root.mkdirs()}
 @After fun tearDown(){context.getSharedPreferences("rpgos_selection",Context.MODE_PRIVATE).edit().clear().commit();root.deleteRecursively()}
 @Test fun bootstrapRoutesBundledCampaignThroughV13(){val s=LocalGameStore(context);s.bootstrap();assertV13(File(root,"saves/${ActiveCampaignRef.DEFAULT_DIRECTORY}/campaign.db"))}
 @Test fun campaignSwitchRoutesV12ThroughV13(){create(ActiveCampaignRef.DEFAULT_DIRECTORY,ActiveCampaignRef.DEFAULT_CAMPAIGN_ID);val other=create("Other.campaign","other");LocalGameStore(context).setActiveCampaign("Other.campaign");assertV13(other)}
 @Test fun restoreRoutesV12ThroughV13WithoutSyntheticLedger(){val active=create(ActiveCampaignRef.DEFAULT_DIRECTORY,ActiveCampaignRef.DEFAULT_CAMPAIGN_ID);val b=File(active.parentFile,"backups/old.db").apply{parentFile?.mkdirs()};v12(b,ActiveCampaignRef.DEFAULT_CAMPAIGN_ID);SQLiteDatabase.openDatabase(b.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{d->d.execSQL("CREATE TABLE IF NOT EXISTS character_finances(entity_uid TEXT,ryo INTEGER)");d.execSQL("INSERT INTO character_finances VALUES('P',777)");d.execSQL("CREATE TABLE IF NOT EXISTS financial_transactions(id TEXT,amount INTEGER,reason TEXT)");d.execSQL("INSERT INTO financial_transactions VALUES('OLD',5,'opaque')")};val safety=LocalGameStore(context).restoreBackup(b.absolutePath);assertTrue(File(safety).isFile);assertV13(active);SQLiteDatabase.openDatabase(active.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{d->d.rawQuery("SELECT COUNT(*) FROM financial_ledger_transactions",null).use{c->c.moveToFirst();assertEquals(0,c.getInt(0))};d.rawQuery("SELECT ryo FROM character_finances WHERE entity_uid='P'",null).use{c->c.moveToFirst();assertEquals(777,c.getInt(0))}}}
 private fun create(dir:String,id:String):File{val d=File(root,"saves/$dir").apply{mkdirs()};File(d,"campaign.json").writeText("{\"id\":\"$id\"}");return File(d,"campaign.db").also{v12(it,id)}}
 private fun v12(f:File,id:String){f.parentFile?.mkdirs();SQLiteDatabase.openOrCreateDatabase(f,null).use{MigrationManager().ensureV12(it,id)}}
 private fun assertV13(f:File){SQLiteDatabase.openDatabase(f.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{d->
  listOf(PHASE13_MIGRATION_ID,PHASE13_BALANCE_GUARD_MIGRATION_ID).forEach{id->d.rawQuery("SELECT COUNT(*) FROM rpgos_schema_migrations WHERE migration_id=?",arrayOf(id)).use{c->c.moveToFirst();assertEquals(1,c.getInt(0))}}
  d.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('currency_definitions','financial_transaction_type_definitions','financial_accounts','financial_ledger_transactions','financial_account_balances','legacy_financial_evidence')",null).use{c->c.moveToFirst();assertEquals(6,c.getInt(0))}
  d.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN ('trg_fin_transaction_balance_guard','trg_fin_transaction_apply_balance','trg_fin_transaction_reference_guard','trg_fin_transaction_immutable_guard')",null).use{c->c.moveToFirst();assertEquals(4,c.getInt(0))}
  d.rawQuery("PRAGMA integrity_check",null).use{c->c.moveToFirst();assertEquals("ok",c.getString(0))}
  listOf("financial_accounts","financial_ledger_transactions","financial_account_balances","legacy_financial_evidence").forEach{table->d.rawQuery("PRAGMA foreign_key_check($table)",null).use{c->assertFalse("Phase 13 FK violation in $table",c.moveToFirst())}}
 }}
}
