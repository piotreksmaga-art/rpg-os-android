package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Phase62NpcTravelCatalogTest {
    @get:Rule val folder=TemporaryFolder()
    private val actor=DomainRef("NPC","N")
    private val origin=DomainRef("LOCATION","LOC-A")

    private fun campaignFile():File=File(folder.root,"campaign.db")
    private fun worldFile():File=File(folder.root,"world.db")

    private fun setup(active:Int=1,security:Double=0.7) {
        SQLiteDatabase.openOrCreateDatabase(campaignFile(),null).use { db->
            db.execSQL("CREATE TABLE entity_positions(entity_uid TEXT PRIMARY KEY,location_uid TEXT)")
            db.execSQL("INSERT INTO entity_positions VALUES('N','LOC-A')")
            db.execSQL("""CREATE TABLE trade_routes_v2(
                trade_route_uid TEXT PRIMARY KEY,from_region_uid TEXT NOT NULL,to_region_uid TEXT NOT NULL,
                route_type TEXT NOT NULL,trade_volume REAL NOT NULL DEFAULT 0,security_level REAL NOT NULL DEFAULT 0.5,
                disruption_level REAL NOT NULL DEFAULT 0,tariff_rate REAL NOT NULL DEFAULT 0,active INTEGER NOT NULL DEFAULT 1)""")
            db.execSQL("INSERT INTO trade_routes_v2 VALUES('EDGE-1','R1','R2','ROAD',100,?,0.1,0.0,?)",arrayOf<Any?>(security,active))
        }
        SQLiteDatabase.openOrCreateDatabase(worldFile(),null).use { db->
            db.execSQL("CREATE TABLE map_locations_v2(location_uid TEXT PRIMARY KEY,name TEXT,location_type TEXT,region_uid TEXT,description TEXT)")
            db.execSQL("INSERT INTO map_locations_v2 VALUES('LOC-A','A','VILLAGE','R1','')")
            db.execSQL("INSERT INTO map_locations_v2 VALUES('LOC-SAME','Same','SITE','R1','')")
            db.execSQL("INSERT INTO map_locations_v2 VALUES('LOC-B','B','VILLAGE','R2','')")
            db.execSQL("INSERT INTO map_locations_v2 VALUES('LOC-C','C','SITE','R2','')")
        }
    }

    private fun catalog()=SqliteNpcTravelRouteCatalog(
        currentCampaign={"C1"},
        openCampaignDb={SQLiteDatabase.openDatabase(campaignFile().absolutePath,null,SQLiteDatabase.OPEN_READONLY)},
        openWorldDb={SQLiteDatabase.openDatabase(worldFile().absolutePath,null,SQLiteDatabase.OPEN_READONLY)}
    )

    @Test fun explicitActiveRegionEdgeProjectsOnlyDestinationsInTargetRegion() {
        setup()
        val routes=catalog().routes("C1",actor,origin)
        assertEquals(setOf("LOC-B","LOC-C"),routes.map{it.destination.uid}.toSet())
        assertTrue(routes.none{it.destination.uid=="LOC-SAME"})
        assertTrue(routes.all{
            it.origin==origin && it.duration.milliseconds==SqliteNpcTravelRouteCatalog.REGION_EDGE_DURATION_MS &&
                it.eligibility==NpcActivityEligibility.CONSCIOUS_SELF && it.mechanicsOwnerUid==NpcTravelMechanics.OWNER
        })
    }

    @Test fun actorScopeCampaignScopeOriginAndActiveFlagFailClosed() {
        setup(active=0)
        assertTrue(catalog().routes("C1",actor,origin).isEmpty())

        campaignFile().delete();worldFile().delete();setup(active=1)
        assertTrue(catalog().routes("C2",actor,origin).isEmpty())
        assertTrue(catalog().routes("C1",DomainRef("NPC","OTHER"),origin).isEmpty())
        assertTrue(catalog().routes("C1",actor,DomainRef("LOCATION","LOC-SAME")).isEmpty())
    }

    @Test fun routeMetadataChangeInvalidatesProjectedContractIdentity() {
        setup(security=0.7)
        val first=catalog().routes("C1",actor,origin).map{it.routeUid to it.fingerprint}.toSet()
        SQLiteDatabase.openDatabase(campaignFile().absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE trade_routes_v2 SET security_level=0.2 WHERE trade_route_uid='EDGE-1'")
        }
        val second=catalog().routes("C1",actor,origin).map{it.routeUid to it.fingerprint}.toSet()
        assertNotEquals(first,second)
    }

    @Test fun absentOrWrongLegacySchemaDoesNotInventRoutes() {
        SQLiteDatabase.openOrCreateDatabase(campaignFile(),null).use { db->
            db.execSQL("CREATE TABLE entity_positions(entity_uid TEXT PRIMARY KEY,location_uid TEXT)")
            db.execSQL("INSERT INTO entity_positions VALUES('N','LOC-A')")
            db.execSQL("CREATE TABLE trade_routes_v2(trade_route_uid TEXT PRIMARY KEY)")
        }
        SQLiteDatabase.openOrCreateDatabase(worldFile(),null).use { db->
            db.execSQL("CREATE TABLE map_locations_v2(location_uid TEXT PRIMARY KEY)")
        }
        assertTrue(catalog().routes("C1",actor,origin).isEmpty())
    }
}
