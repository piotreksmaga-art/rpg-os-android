package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

/**
 * Read-only production adapter for the legacy campaign/world route data.
 *
 * trade_routes_v2 is explicit connectivity evidence between regions. We never infer a route
 * merely because two locations exist in the same database. The actor must also still be
 * canonically located at origin; destination disclosure remains the responsibility of Phase37/38
 * because NpcTravelAffordances only exposes routes whose destination is already known.
 *
 * One active region edge uses the deliberately conservative Core timing rule
 * REGION_EDGE_DURATION_MS. Existing legacy data does not contain distance or duration, therefore
 * this adapter does not pretend to know either. World Packs can replace this port with richer
 * versioned contracts without changing Phase62.
 */
internal class SqliteNpcTravelRouteCatalog(
    private val currentCampaign:()->String,
    private val openCampaignDb:()->SQLiteDatabase,
    private val openWorldDb:()->SQLiteDatabase
):NpcTravelRoutePort {
    companion object {
        const val REGION_EDGE_DURATION_MS=86_400_000L
        const val TIMING_POLICY_UID="P62:LEGACY_REGION_EDGE_DAY_V1"
        private val TRADE_COLUMNS=setOf(
            "trade_route_uid","from_region_uid","to_region_uid","route_type","trade_volume",
            "security_level","disruption_level","tariff_rate","active"
        )
        private val LOCATION_COLUMNS=setOf("location_uid","region_uid")
    }

    override fun routes(campaignUid:String,actor:DomainRef,origin:DomainRef):List<NpcTravelRouteContract> {
        npcUid(campaignUid);npcUid(actor.kindUid);npcUid(actor.uid)
        if(campaignUid!=currentCampaign() || origin.kindUid !in setOf("PLACE","LOCATION"))return emptyList()
        return openCampaignDb().use { campaignDb ->
            if(!hasColumns(campaignDb,"trade_routes_v2",TRADE_COLUMNS) ||
                !tableExists(campaignDb,"entity_positions"))return@use emptyList()
            val actualOrigin=campaignDb.rawQuery(
                "SELECT location_uid FROM entity_positions WHERE entity_uid=? LIMIT 1",arrayOf(actor.uid)
            ).use { c->if(!c.moveToFirst()||c.isNull(0))null else c.getString(0) }
            if(actualOrigin!=origin.uid)return@use emptyList()

            openWorldDb().use { worldDb ->
                if(!hasColumns(worldDb,"map_locations_v2",LOCATION_COLUMNS))return@use emptyList()
                val originRegion=worldDb.rawQuery(
                    "SELECT region_uid FROM map_locations_v2 WHERE location_uid=? LIMIT 1",arrayOf(origin.uid)
                ).use { c->if(!c.moveToFirst()||c.isNull(0))null else c.getString(0)?.takeIf(String::isNotBlank) }
                    ?:return@use emptyList()

                data class Edge(
                    val uid:String,val toRegion:String,val type:String,val volume:String,
                    val security:String,val disruption:String,val tariff:String
                )
                val edges=mutableListOf<Edge>()
                campaignDb.rawQuery(
                    "SELECT trade_route_uid,to_region_uid,route_type,trade_volume,security_level,disruption_level,tariff_rate " +
                        "FROM trade_routes_v2 WHERE from_region_uid=? AND active=1 ORDER BY trade_route_uid LIMIT 64",
                    arrayOf(originRegion)
                ).use { c->
                    while(c.moveToNext()) {
                        val uid=c.getString(0)?.takeIf(String::isNotBlank)?:continue
                        val to=c.getString(1)?.takeIf(String::isNotBlank)?:continue
                        val type=c.getString(2)?.takeIf(String::isNotBlank)?:continue
                        edges+=Edge(uid,to,type,c.getString(3)?:"",c.getString(4)?:"",c.getString(5)?:"",c.getString(6)?:"")
                    }
                }

                buildList {
                    for(edge in edges) {
                        val destinations=mutableListOf<String>()
                        worldDb.rawQuery(
                            "SELECT location_uid FROM map_locations_v2 WHERE region_uid=? ORDER BY location_uid LIMIT 32",
                            arrayOf(edge.toRegion)
                        ).use { c->while(c.moveToNext())c.getString(0)?.takeIf(String::isNotBlank)?.let(destinations::add) }
                        val edgeFingerprint=phase60Hash(listOf(
                            edge.uid,originRegion,edge.toRegion,edge.type,edge.volume,edge.security,edge.disruption,edge.tariff
                        ).joinToString("|"))
                        for(destinationUid in destinations) {
                            if(size>=128)break
                            val destination=DomainRef("LOCATION",destinationUid)
                            if(destination.uid==origin.uid)continue
                            val routeUid="P62:CATALOG:${phase60Hash("$edgeFingerprint|${origin.uid}|$destinationUid").take(32)}"
                            add(NpcTravelRouteContract(
                                campaignUid=campaignUid,
                                routeUid=routeUid,
                                version=1,
                                origin=origin,
                                destination=destination,
                                duration=ActionDuration(REGION_EDGE_DURATION_MS),
                                timingRuleUid="$TIMING_POLICY_UID:${phase60Hash(edge.type).take(16)}",
                                mechanicsOwnerUid=NpcTravelMechanics.OWNER,
                                capabilityUid="TRAVEL",
                                resourceCosts=emptyMap(),
                                eligibility=NpcActivityEligibility.CONSCIOUS_SELF
                            ))
                        }
                        if(size>=128)break
                    }
                }
            }
        }
    }

    private fun tableExists(db:SQLiteDatabase,name:String)=db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",arrayOf(name)
    ).use{it.moveToFirst()}

    private fun hasColumns(db:SQLiteDatabase,table:String,required:Set<String>):Boolean {
        if(!tableExists(db,table))return false
        val columns=linkedSetOf<String>()
        db.rawQuery("PRAGMA table_info($table)",null).use{c->while(c.moveToNext())columns+=c.getString(1)}
        return columns.containsAll(required)
    }
}
