package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class InventoryLegacyScaleTest{
 private lateinit var f:File
 @Before fun setUp(){f=File.createTempFile("inv-legacy-scale-",".db");f.delete()};@After fun tearDown(){f.delete()}
 @Test fun moreThanOneThousandLegacyRowsRemainUnresolvedAndPlayerScoped(){SQLiteDatabase.openOrCreateDatabase(f,null).use{d->d.execSQL("CREATE TABLE character_inventory(entity_uid TEXT,item_name TEXT,custom_note TEXT)");d.beginTransaction();try{(0..1004).forEach{d.execSQL("INSERT INTO character_inventory VALUES(?,?,?)",arrayOf("P","Legacy-$it","note-$it"))};d.execSQL("INSERT INTO character_inventory VALUES('Q','Other','q')");d.setTransactionSuccessful()}finally{d.endTransaction()};CurrentSchema.ensure(d,"C");val s=InventoryStore(d,"C");assertEquals(1005,s.legacyEvidence("P").size);assertEquals(1005,s.reconciled("P").unresolvedLegacy.size);assertEquals(1,s.legacyEvidence("Q").size);assertEquals(1006,d.rawQuery("SELECT COUNT(*) FROM character_inventory",null).use{c->c.moveToFirst();c.getInt(0)})}}
}
