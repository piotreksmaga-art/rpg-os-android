package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class PristineCampaignStartupProfileTest {
    @Test fun newCampaignDefaultsAreConsistentIdempotentAndDoNotRewriteAPlayedSave() {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE campaign_calendar(id INTEGER,absolute_day INTEGER,year_number INTEGER,year_label TEXT,era_key TEXT,era_name TEXT,canon_anchor_event_uid TEXT,updated_chapter INTEGER)")
            db.execSQL("INSERT INTO campaign_calendar VALUES(1,0,-40,'Przed Konoha','warring_states','Warring States','OLD',0)")
            db.execSQL("CREATE TABLE world_clock(id INTEGER,campaign_day INTEGER,campaign_year INTEGER,era TEXT,updated_chapter INTEGER)")
            db.execSQL("INSERT INTO world_clock VALUES(1,0,0,'Warring States',0)")
            db.execSQL("CREATE TABLE timeline_events(status TEXT)")
            db.execSQL("INSERT INTO timeline_events VALUES('planned')")
            db.execSQL("CREATE TABLE turn_transaction_receipts(campaign_uid TEXT,commit_order INTEGER)")
            val profile=PristineCampaignStartupProfile("naruto","naruto","Era Naruto","Początek ery Naruto")
            assertTrue(profile.apply(db,"C1"));assertTrue(profile.apply(db,"C1"))
            fun text(query:String)=db.rawQuery(query,null).use{it.moveToFirst();it.getString(0)}
            assertEquals("naruto",text("SELECT era_key FROM campaign_calendar"))
            assertEquals("Era Naruto",text("SELECT era FROM world_clock"))
            assertEquals("cancelled",text("SELECT status FROM timeline_events"))
            db.execSQL("UPDATE campaign_calendar SET era_key='custom'")
            db.execSQL("INSERT INTO turn_transaction_receipts VALUES('C1',1)")
            assertFalse(profile.apply(db,"C1"))
            assertEquals("custom",text("SELECT era_key FROM campaign_calendar"))
        }
    }
    @Test fun defaultsAreOptInToTheWorldPackNotHardcodedForOtherWorlds() {
        val payload="""{"schema_version":1,"profiles":[{"worldpack_uid":"naruto","era_key":"naruto","era_name":"Era Naruto","year_label":"Początek ery Naruto"}]}"""
        assertNotNull(PristineCampaignStartupProfile.fromJson(payload,"naruto"))
        assertNull(PristineCampaignStartupProfile.fromJson(payload,"other-world"))
    }
}
