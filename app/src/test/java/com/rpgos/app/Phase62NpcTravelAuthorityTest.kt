package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test

class Phase62NpcTravelAuthorityTest {
    private val origin=DomainRef("LOCATION","A")

    private fun db():SQLiteDatabase=SQLiteDatabase.create(null).also{database->
        GroupATransactionTestFixtures.setupFinance(database,"C1",100)
    }

    private fun insertRoute(db:SQLiteDatabase,campaign:String="C1",uid:String="R1",active:Int=1,
                            eligibility:String="MATERIALIZED_CAPABILITY") {
        withAdministrativeMutationAuthority(db,campaign) {
            db.execSQL("INSERT INTO "+Phase62TravelRouteSchema.ROUTES+"("+
                "campaign_uid,route_uid,route_version,origin_kind_uid,origin_uid,destination_kind_uid,destination_uid,"+
                "duration_ms,timing_rule_uid,mechanics_owner_uid,capability_uid,eligibility_uid,active,provenance_uid) "+
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(campaign,uid,1,"LOCATION","A","LOCATION","B",120000,"WORLD:ROAD",
                    NpcTravelMechanics.OWNER,"WORLD:WALK",eligibility,active,"TEST:WORLD-PACK"))
            db.execSQL("INSERT INTO "+Phase62TravelRouteSchema.COSTS+
                "(campaign_uid,route_uid,route_version,resource_uid,cost_units) VALUES(?,?,?,?,?)",
                arrayOf<Any?>(campaign,uid,1,"STAMINA",3))
        }
    }

    @Test fun bootstrapClassifiesAndGuardsTravelDefinitionsAsAdministrativeMechanicsAuthority()=db().use{database->
        assertTrue(Phase62TravelRouteSchema.isReady(database))
        assertEquals("TRAVEL_ROUTE_DEFINITIONS",
            RuntimeTruthLayerRegistry.requireClassifiedTable(Phase62TravelRouteSchema.ROUTES).uid)
        assertTrue(RuntimeTruthLayerRegistry.requireClassifiedTable(Phase62TravelRouteSchema.ROUTES).isMechanicsDefinitionAuthority)
        GameplayRuntimeBootstrap.requireReady(database,"C1")
        assertThrows(Exception::class.java) {
            database.execSQL("INSERT INTO "+Phase62TravelRouteSchema.ROUTES+
                "(campaign_uid,route_uid,route_version,origin_kind_uid,origin_uid,destination_kind_uid,destination_uid,"+
                "duration_ms,timing_rule_uid,mechanics_owner_uid,capability_uid,eligibility_uid,active,provenance_uid) "+
                "VALUES('C1','ILLEGAL',1,'LOCATION','A','LOCATION','B',1,'R','UNIVERSAL_MOVEMENT','WALK','CONSCIOUS_SELF',1,'X')")
        }
    }

    @Test fun productionCatalogReadsOnlyExplicitActiveCampaignRoutesAndCosts()=db().use{database->
        insertRoute(database)
        insertRoute(database,uid="OFF",active=0)
        val port=SqliteNpcTravelRoutePort(database)
        val routes=port.routes("C1",origin)
        assertEquals(1,routes.size)
        val route=routes.single()
        assertEquals("R1",route.routeUid)
        assertEquals(DomainRef("LOCATION","B"),route.destination)
        assertEquals(120000,route.duration.milliseconds)
        assertEquals(mapOf("STAMINA" to 3L),route.resourceCosts)
        assertEquals(NpcActivityEligibility.MATERIALIZED_CAPABILITY,route.eligibility)
        assertTrue(port.routes("C2",origin).isEmpty())
    }

    @Test fun legacySpeedAndTradeRowsAreNeverPromotedIntoPhysicalRoutes()=db().use{database->
        withAdministrativeMutationAuthority(database,"C1") {
            if(!tableExistsForTravelTest(database,"travel_profiles"))
                database.execSQL("CREATE TABLE travel_profiles(entity_uid TEXT PRIMARY KEY,base_walk_km_per_day REAL)")
            if(!tableExistsForTravelTest(database,"trade_routes_v2"))
                database.execSQL("CREATE TABLE trade_routes_v2(trade_route_uid TEXT PRIMARY KEY,from_region_uid TEXT,to_region_uid TEXT,active INTEGER)")
            database.execSQL("INSERT OR REPLACE INTO travel_profiles(entity_uid,base_walk_km_per_day) VALUES('N',35)")
            database.execSQL("INSERT OR REPLACE INTO trade_routes_v2(trade_route_uid,from_region_uid,to_region_uid,active) VALUES('T','A','B',1)")
        }
        assertTrue(SqliteNpcTravelRoutePort(database).routes("C1",origin).isEmpty())
    }

    private fun tableExistsForTravelTest(db:SQLiteDatabase,name:String)=db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",arrayOf(name)
    ).use{it.moveToFirst()}
}
