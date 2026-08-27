package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ProductionCharacterPanelV2ReadSourceTest {
    @Test fun productionAdapterReadsCanonicalStoresAndIgnoresUnavailableOptionalStores(){
        SQLiteDatabase.create(null).use{db->
            db.execSQL("CREATE TABLE campaign_truth_records(campaign_id TEXT,subject_uid TEXT,predicate TEXT,object_value TEXT,active INTEGER)")
            db.execSQL("CREATE TABLE stat_definitions(stat_uid TEXT,stat_key TEXT)")
            db.execSQL("CREATE TABLE player_stats(campaign_id TEXT,character_uid TEXT,stat_uid TEXT,base_value REAL)")
            db.execSQL("CREATE TABLE talent_profile_entries(campaign_id TEXT,character_uid TEXT,domain_uid TEXT,base_value REAL,provenance TEXT)")
            db.execSQL("INSERT INTO campaign_truth_records VALUES('C1','P1','RPGOS:PLAYER_IDENTITY:NAME','Ari',1)")
            db.execSQL("INSERT INTO stat_definitions VALUES('S1','courage')")
            db.execSQL("INSERT INTO player_stats VALUES('C1','P1','S1',12)")
            db.execSQL("INSERT INTO talent_profile_entries VALUES('C1','P1','TACTICS',8,'bootstrap')")

            val snapshot=CharacterPanelSnapshotV2Builder.build(ProductionCharacterPanelV2ReadSource(db),"C1","P1")
            assertEquals(CharacterPanelIdentityV2("NAME","Ari"),snapshot.identity.single())
            assertEquals(CharacterPanelExactValueV2("S1",12,"courage"),snapshot.stats.single())
            assertEquals("TACTICS",snapshot.talent.single().domainUid)
            assertEquals("8.0",snapshot.talent.single().canonicalValue)
            assertNull(snapshot.talent.single().evidenceUid)
            assertNull(snapshot.potential.singleOrNull())
        }
    }
}
