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
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class InventoryLegacyAssetPreflightTest {
 private lateinit var context:Context;private lateinit var dbFile:File
 @Before fun setUp(){context=RuntimeEnvironment.getApplication();dbFile=File.createTempFile("inventory-legacy-asset-",".db");dbFile.delete()}
 @After fun tearDown(){dbFile.delete()}
 @Test fun bundledCampaignCharacterInventoryPragmaAndCompatibilityReadAreReal(){extract();SQLiteDatabase.openDatabase(dbFile.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{db->val cols=mutableListOf<String>();db.rawQuery("PRAGMA table_info(character_inventory)",null).use{c->val i=c.getColumnIndex("name");while(c.moveToNext())cols+=c.getString(i)};assertTrue("actual=$cols",cols.any{it.equals("entity_uid",true)});assertTrue("actual=$cols",cols.any{it.equals("item_name",true)});db.rawQuery("PRAGMA index_list(character_inventory)",null).use{while(it.moveToNext()){} };db.rawQuery("PRAGMA foreign_key_list(character_inventory)",null).use{while(it.moveToNext()){} };CurrentSchema.ensure(db,ActiveCampaignRef.DEFAULT_CAMPAIGN_ID);ActivePlayerStore(db,ActiveCampaignRef.DEFAULT_CAMPAIGN_ID).active()?.playerUid?.let{InventoryStore(db,ActiveCampaignRef.DEFAULT_CAMPAIGN_ID).legacyEvidence(it)};db.rawQuery("PRAGMA integrity_check",null).use{c->assertTrue(c.moveToFirst());assertEquals("ok",c.getString(0))}}}
 private fun extract(){context.assets.open("Naruto_Default.campaign.zip").use{input->ZipInputStream(input).use{zip->var e=zip.nextEntry;var found=false;while(e!=null){if(!e.isDirectory&&e.name.endsWith("campaign.db")){FileOutputStream(dbFile).use{zip.copyTo(it)};found=true;zip.closeEntry();break};zip.closeEntry();e=zip.nextEntry};assertTrue(found)}}}
}
