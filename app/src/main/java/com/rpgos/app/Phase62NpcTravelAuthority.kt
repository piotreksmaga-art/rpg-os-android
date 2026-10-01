package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import java.util.Collections

/** R1 administrative mechanics-definition authority. Route rows describe explicit edges only.
 * They never imply actor position, knowledge or completed travel. */
internal object Phase62TravelRouteSchema {
    const val ROUTES="rpgos_travel_route_definitions"
    const val COSTS="rpgos_travel_route_costs"
    const val MIGRATION="RPGOS-62.1-NPC-TRAVEL-ROUTES"

    val tables=setOf(ROUTES,COSTS)

    fun ensureReady(db:SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS $ROUTES(
            campaign_uid TEXT NOT NULL,
            route_uid TEXT NOT NULL,
            route_version INTEGER NOT NULL CHECK(route_version>0),
            origin_kind_uid TEXT NOT NULL CHECK(origin_kind_uid IN ('PLACE','LOCATION')),
            origin_uid TEXT NOT NULL,
            destination_kind_uid TEXT NOT NULL CHECK(destination_kind_uid IN ('PLACE','LOCATION')),
            destination_uid TEXT NOT NULL,
            duration_ms INTEGER NOT NULL CHECK(duration_ms>0),
            timing_rule_uid TEXT NOT NULL,
            mechanics_owner_uid TEXT NOT NULL,
            capability_uid TEXT NOT NULL,
            eligibility_uid TEXT NOT NULL CHECK(eligibility_uid IN ('CONSCIOUS_SELF','MATERIALIZED_CAPABILITY')),
            active INTEGER NOT NULL DEFAULT 1 CHECK(active IN (0,1)),
            provenance_uid TEXT NOT NULL,
            PRIMARY KEY(campaign_uid,route_uid,route_version),
            CHECK(origin_kind_uid!=destination_kind_uid OR origin_uid!=destination_uid)
        )""".trimIndent())
        db.execSQL("""CREATE TABLE IF NOT EXISTS $COSTS(
            campaign_uid TEXT NOT NULL,
            route_uid TEXT NOT NULL,
            route_version INTEGER NOT NULL,
            resource_uid TEXT NOT NULL,
            cost_units INTEGER NOT NULL CHECK(cost_units>0),
            PRIMARY KEY(campaign_uid,route_uid,route_version,resource_uid),
            FOREIGN KEY(campaign_uid,route_uid,route_version)
              REFERENCES $ROUTES(campaign_uid,route_uid,route_version) ON DELETE CASCADE
        )""".trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_rpgos_travel_routes_origin ON $ROUTES(campaign_uid,origin_kind_uid,origin_uid,active)")
        db.execSQL("INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes) VALUES(?,strftime('%s','now'),?)",
            arrayOf(MIGRATION,"Explicit world-owned NPC travel edges and costs; no inferred adjacency or arrival authority"))
    }

    fun isReady(db:SQLiteDatabase)=tables.all{table->
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",arrayOf(table)).use{it.moveToFirst()}
    }
}

/** Read-only production adapter over the explicit route definition authority.
 * Legacy travel_profiles describes actor speed and trade_routes_v2 describes economy; neither is
 * silently promoted into a physical route. */
internal class SqliteNpcTravelRoutePort(private val db:SQLiteDatabase):NpcTravelRoutePort {
    override fun routes(campaignUid:String,origin:DomainRef):List<NpcTravelRouteContract> {
        npcUid(campaignUid)
        if(origin.kindUid !in setOf("PLACE","LOCATION") || !Phase62TravelRouteSchema.isReady(db))return emptyList()
        val rows=mutableListOf<NpcTravelRouteContract>()
        db.rawQuery("""SELECT route_uid,route_version,destination_kind_uid,destination_uid,duration_ms,
            timing_rule_uid,mechanics_owner_uid,capability_uid,eligibility_uid
            FROM ${Phase62TravelRouteSchema.ROUTES}
            WHERE campaign_uid=? AND origin_kind_uid=? AND origin_uid=? AND active=1
            ORDER BY route_uid,route_version""".trimIndent(),
            arrayOf(campaignUid,origin.kindUid,origin.uid)
        ).use{cursor->while(cursor.moveToNext()){
            val routeUid=cursor.getString(0);val version=cursor.getInt(1)
            val costs=linkedMapOf<String,Long>()
            db.rawQuery("""SELECT resource_uid,cost_units FROM ${Phase62TravelRouteSchema.COSTS}
                WHERE campaign_uid=? AND route_uid=? AND route_version=? ORDER BY resource_uid""".trimIndent(),
                arrayOf(campaignUid,routeUid,version.toString())
            ).use{costCursor->while(costCursor.moveToNext())costs[costCursor.getString(0)]=costCursor.getLong(1)}
            rows+=NpcTravelRouteContract(
                campaignUid=campaignUid,routeUid=routeUid,version=version,origin=origin,
                destination=DomainRef(cursor.getString(2),cursor.getString(3)),
                duration=ActionDuration(cursor.getLong(4)),timingRuleUid=cursor.getString(5),
                mechanicsOwnerUid=cursor.getString(6),capabilityUid=cursor.getString(7),
                resourceCosts=Collections.unmodifiableMap(costs),
                eligibility=NpcActivityEligibility.valueOf(cursor.getString(8))
            )
        }}
        require(rows.size<=1024){"P62:TRAVEL_ROUTE_BUDGET"}
        return rows
    }
}
