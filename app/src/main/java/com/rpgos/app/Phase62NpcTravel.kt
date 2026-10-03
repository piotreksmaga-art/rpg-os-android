package com.rpgos.app

import java.util.Collections
import kotlinx.serialization.json.*

/** A versioned, campaign-owned route, not a record of departure or arrival.
 * COMPLETION_ONLY_V1 settles costs with arrival; partial/incremental travel is not implied. */
data class NpcTravelRouteContract(
    val campaignUid:String,
    val routeUid:String,
    val version:Int,
    val origin:DomainRef,
    val destination:DomainRef,
    val duration:ActionDuration,
    val timingRuleUid:String,
    val mechanicsOwnerUid:String="UNIVERSAL_MOVEMENT",
    val capabilityUid:String="TRAVEL",
    val resourceCosts:Map<String,Long> = emptyMap(),
    val eligibility:NpcActivityEligibility=NpcActivityEligibility.MATERIALIZED_CAPABILITY,
    val requiredCapabilities:Set<String> = emptySet()
) {
    init {
        listOf(campaignUid,routeUid,timingRuleUid,mechanicsOwnerUid,capabilityUid,origin.uid,destination.uid).forEach(::npcUid)
        require(version>0){"P62:TRAVEL_ROUTE_VERSION"}
        require(origin.kindUid in setOf("PLACE","LOCATION") && destination.kindUid in setOf("PLACE","LOCATION")){"P62:TRAVEL_ROUTE_LOCATION_KIND"}
        require(origin!=destination){"P62:TRAVEL_ROUTE_SAME_LOCATION"}
        require(duration.milliseconds>0){"P62:TRAVEL_ROUTE_DURATION"}
        require(resourceCosts.size<=16 && resourceCosts.values.all{it>=0}){"P62:TRAVEL_ROUTE_COST"}
        resourceCosts.keys.forEach(::npcUid)
        require(requiredCapabilities.none(String::isBlank))
    }
    val fingerprint:String get()=phase60Hash(buildJsonObject {
        put("contract", if(requiredCapabilities.isEmpty())"P62:TRAVEL_ROUTE:2" else "P63:TRAVEL_ROUTE:3");put("campaign",campaignUid);put("route",routeUid);put("version",version)
        put("origin",NpcBrainCodec.ref(origin));put("destination",NpcBrainCodec.ref(destination))
        put("duration_ms",duration.milliseconds);put("rule",timingRuleUid);put("owner",mechanicsOwnerUid)
        put("capability",capabilityUid);put("eligibility",eligibility.name);put("settlement","COMPLETION_ONLY_V1")
        put("costs",buildJsonObject{resourceCosts.toSortedMap().forEach{(uid,cost)->put(uid,cost)}})
        if(requiredCapabilities.isNotEmpty())put("required_capabilities",JsonArray(requiredCapabilities.sorted().map(::JsonPrimitive)))
    }.toString())
}

fun interface NpcTravelRoutePort {
    /** Catalog read is actor-scoped so production route authority can apply current access rules
     * without leaking a global route list into the decision layer. */
    fun routes(campaignUid:String,actor:DomainRef,origin:DomainRef):List<NpcTravelRouteContract>

    companion object {
        val NONE=NpcTravelRoutePort{_,_,_->emptyList()}
        fun registered(routes:List<NpcTravelRouteContract>):NpcTravelRoutePort {
            require(routes.size<=1024){"P62:TRAVEL_ROUTE_BUDGET"}
            require(routes.map{Triple(it.campaignUid,it.routeUid,it.version)}.distinct().size==routes.size){"P62:DUPLICATE_TRAVEL_ROUTE"}
            val snapshot=routes.map{it.copy(resourceCosts=Collections.unmodifiableMap(LinkedHashMap(it.resourceCosts)))}
            return NpcTravelRoutePort { campaignUid,actor,origin ->
                npcUid(campaignUid);npcUid(actor.kindUid);npcUid(actor.uid)
                snapshot.filter{it.campaignUid==campaignUid && it.origin==origin}.sortedWith(compareBy<NpcTravelRouteContract>{it.routeUid}.thenBy{it.version})
            }
        }
    }
}

/** Only current origin, executable travel and holder-authorized destination knowledge create
 * an option. Route existence alone grants neither capability nor knowledge of its destination. */
internal object NpcTravelAffordances {
    const val EFFECT_KIND="LOCATION_TRANSITION"

    fun options(
        brain:NpcBrainState,
        records:List<NpcKnownRecord>,
        actor:MechanicalActorView?,
        routes:NpcTravelRoutePort,
        goal:NpcGoal?=brain.goals.filter{it.lifecycle==NpcGoalLifecycle.ACTIVE}
            .sortedWith(compareByDescending<NpcGoal>{it.priority.basisPoints}.thenBy{it.uid}).firstOrNull()
    ):List<NpcActionOption> {
        if(actor==null || actor.campaignUid!=brain.campaignUid || actor.actor!=brain.actor ||
            goal==null || goal !in brain.goals || goal.lifecycle!=NpcGoalLifecycle.ACTIVE)return emptyList()
        val origin=actor.locationRef?:return emptyList()
        val candidates=routes.routes(brain.campaignUid,brain.actor,origin)
        require(candidates.size<=1024){"P62:TRAVEL_ROUTE_BUDGET"}
        val knownBySubject=records.flatMap{record->record.subjectRefs.map{it to record.uid}}.groupBy({it.first},{it.second})
        return candidates.asSequence().mapNotNull { route ->
            if(!NpcTravelMechanics.available(actor,route))return@mapNotNull null
            val evidence=knownBySubject[route.destination].orEmpty().distinct().sorted().take(32).toSet()
            if(evidence.isEmpty())return@mapNotNull null
            NpcActionOption(
                uid="P62:TRAVEL_OPTION:${phase60Hash("${brain.actor}|${goal.uid}|${route.fingerprint}").take(32)}",
                capabilityUid=route.capabilityUid,target=route.destination,timing=NpcTravelMechanics.timing(route),goalUid=goal.uid,
                traitPreferences=emptyList(),supportingRecordUids=evidence,
                resourceCosts=route.resourceCosts.toMap(),parameters=NpcTravelMechanics.parameters(route),
                mechanicsOwnerUid=route.mechanicsOwnerUid,mechanicalEffectKindUid=EFFECT_KIND,
                worldResult=NpcWorldResultContract(route.mechanicsOwnerUid,route.fingerprint,
                    listOf(NpcWorldResultCriterion(NpcWorldResultKind.LOCATION_REACHED,brain.actor,route.destination.uid)))
            )
        }.take(8).toList()
    }

    /** Payload shape only: a caller must still obtain fresh mechanics and a successful commit. */
    fun arrivalPayload(actor:DomainRef,route:NpcTravelRouteContract)=SpatialChange(
        subject=actor,deltaXMillimetres=0,deltaYMillimetres=0,destinationLocation=route.destination
    )

    fun ownerContract(route:NpcTravelRouteContract)=NpcActivityOwnerContract(
        contractUid="P62:TRAVEL_RESULT:${route.routeUid}",version=route.version,lifecycleOwnerUid=NpcActionProcess.OWNER,
        resultOwnerUid="RPGOS-P50:SPATIAL",evidenceKindUid="P62:TRAVEL_ARRIVAL_RECEIPT",
        resultPolicy=NpcActivityResultPolicy.DOMAIN_RESULT_EVIDENCE,allowedCanonicalChangeKindUids=setOf(PlayerChangeKinds.SPATIAL)
    )
}
