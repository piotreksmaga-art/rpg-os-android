package com.rpgos.app

/**
 * R1 travel contract. It describes a route the canonical world has made available to an NPC.
 * It is not proof that travel started or that the actor arrived.
 */
data class NpcTravelRouteContract(
    val routeUid:String,
    val version:Int,
    val origin:DomainRef,
    val destination:DomainRef,
    val duration:ActionDuration,
    val timingRuleUid:String,
    val mechanicsOwnerUid:String="UNIVERSAL_MOVEMENT",
    val capabilityUid:String="TRAVEL",
    val resourceCosts:Map<String,Long> = emptyMap()
) {
    init {
        npcUid(routeUid);npcUid(timingRuleUid);npcUid(mechanicsOwnerUid);npcUid(capabilityUid)
        require(version>0){"P62:TRAVEL_ROUTE_VERSION"}
        require(origin.kindUid in setOf("PLACE","LOCATION") && destination.kindUid in setOf("PLACE","LOCATION")){"P62:TRAVEL_ROUTE_LOCATION_KIND"}
        require(origin!=destination){"P62:TRAVEL_ROUTE_SAME_LOCATION"}
        require(duration.milliseconds>0){"P62:TRAVEL_ROUTE_DURATION"}
        require(resourceCosts.size<=16 && resourceCosts.values.all{it>=0}){"P62:TRAVEL_ROUTE_COST"}
        resourceCosts.keys.forEach(::npcUid)
    }
    val fingerprint:String get()=phase60Hash("P62:TRAVEL_ROUTE:1|"+listOf(
        routeUid,version.toString(),origin.kindUid,origin.uid,destination.kindUid,destination.uid,
        duration.milliseconds.toString(),timingRuleUid,mechanicsOwnerUid,capabilityUid,
        resourceCosts.toSortedMap().entries.joinToString(","){"${it.key}=${it.value}"}
    ).joinToString("|"))
}

fun interface NpcTravelRoutePort {
    fun routes(campaignUid:String,origin:DomainRef):List<NpcTravelRouteContract>

    companion object {
        val NONE=NpcTravelRoutePort{_,_->emptyList()}

        fun registered(routes:List<NpcTravelRouteContract>):NpcTravelRoutePort {
            require(routes.size<=1024){"P62:TRAVEL_ROUTE_BUDGET"}
            require(routes.map{it.routeUid to it.version}.distinct().size==routes.size){"P62:DUPLICATE_TRAVEL_ROUTE"}
            val snapshot=routes.map{it.copy(resourceCosts=it.resourceCosts.toMap())}
            return NpcTravelRoutePort { campaignUid,origin ->
                npcUid(campaignUid)
                snapshot.filter{it.origin==origin}.sortedWith(compareBy<NpcTravelRouteContract>{it.routeUid}.thenBy{it.version})
            }
        }
    }
}

/**
 * Projects only routes that start at the actor's current canonical location and whose destination
 * is present in holder-authorized knowledge. The option is still only an intention candidate.
 */
internal object NpcTravelAffordances {
    const val EFFECT_KIND="LOCATION_TRANSITION"

    fun options(
        brain:NpcBrainState,
        records:List<NpcKnownRecord>,
        actor:MechanicalActorView?,
        routes:NpcTravelRoutePort,
        goal:NpcGoal?=brain.goals.firstOrNull{it.lifecycle==NpcGoalLifecycle.ACTIVE}
    ):List<NpcActionOption> {
        if(actor==null || actor.campaignUid!=brain.campaignUid || actor.actor!=brain.actor)return emptyList()
        val origin=actor.locationRef?:return emptyList()
        val knownBySubject=records.flatMap{record->record.subjectRefs.map{it to record.uid}}.groupBy({it.first},{it.second})
        return routes.routes(brain.campaignUid,origin).mapNotNull { route ->
            if(route.origin!=origin)return@mapNotNull null
            val evidence=knownBySubject[route.destination].orEmpty().toSet()
            if(evidence.isEmpty())return@mapNotNull null
            NpcActionOption(
                uid="P62:TRAVEL_OPTION:${phase60Hash("${brain.actor}|${route.fingerprint}").take(32)}",
                capabilityUid=route.capabilityUid,
                target=route.destination,
                timing=AcceptedActionTiming(route.duration,route.timingRuleUid,route.version),
                goalUid=goal?.uid,
                traitPreferences=emptyList(),
                supportingRecordUids=evidence,
                resourceCosts=route.resourceCosts,
                parameters=mapOf(
                    "route_uid" to route.routeUid,
                    "route_version" to route.version.toString(),
                    "route_fingerprint" to route.fingerprint,
                    "destination_kind_uid" to route.destination.kindUid,
                    "destination_uid" to route.destination.uid
                ),
                mechanicsOwnerUid=route.mechanicsOwnerUid,
                mechanicalEffectKindUid=EFFECT_KIND
            )
        }.take(8)
    }

    /** The ordinary Phase50 materializer remains the owner of the actual location mutation. */
    fun arrivalPayload(actor:DomainRef,route:NpcTravelRouteContract)=SpatialChange(
        subject=actor,
        deltaXMillimetres=0,
        deltaYMillimetres=0,
        destinationLocation=route.destination
    )

    fun ownerContract(route:NpcTravelRouteContract)=NpcActivityOwnerContract(
        contractUid="P62:TRAVEL_RESULT:${route.routeUid}",
        version=route.version,
        lifecycleOwnerUid=NpcActionProcess.OWNER,
        resultOwnerUid="RPGOS-P50:SPATIAL",
        evidenceKindUid="P62:TRAVEL_ARRIVAL_RECEIPT",
        resultPolicy=NpcActivityResultPolicy.DOMAIN_RESULT_EVIDENCE,
        allowedCanonicalChangeKindUids=setOf(PlayerChangeKinds.SPATIAL)
    )
}
