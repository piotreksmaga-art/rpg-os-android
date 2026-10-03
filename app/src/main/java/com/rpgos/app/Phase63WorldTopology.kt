package com.rpgos.app

import java.util.PriorityQueue

internal object WorldTopologyAnchor {
    fun canonical(ref:DomainRef):DomainRef {
        require(ref.kindUid in setOf("PLACE","LOCATION"))
        return DomainRef("LOCATION",ref.uid)
    }
    fun same(a:DomainRef,b:DomainRef)=a.kindUid in setOf("PLACE","LOCATION") && b.kindUid in setOf("PLACE","LOCATION") && a.uid==b.uid
}

fun interface WorldTopologyEdgeReadPort {
    /** Infrastructure may read canonical edges; this port is never exposed to AI. */
    fun from(campaignUid:String,origin:DomainRef):List<WorldTopologyEdge>
}

interface WorldTopologyAuthorizationPort {
    fun current(scope:WorldResolutionScope):Boolean
    /** Includes Phase37 route knowledge, Phase38 disclosure, current access and capability. */
    fun permitted(scope:WorldResolutionScope,edge:WorldTopologyEdge,at:WorldTimeTick):Boolean
}

fun interface WorldTopologyContainmentPort {
    /** Return only an authorized canonical/registered parent; absence remains unknown. */
    fun parent(scope:WorldResolutionScope,element:DomainRef):DomainRef?
    companion object { val NONE=WorldTopologyContainmentPort { _,_->null } }
}

/** Exact, deterministic routing over authorized edges only. Containment is not an edge.
 * Exhaustion is UNKNOWN, never a proof of nonexistence or a fabricated direct connection. */
class AuthorizedWorldTopology(
    private val edges:WorldTopologyEdgeReadPort,
    private val authorization:WorldTopologyAuthorizationPort,
    private val maximumVisited:Int=1024,
    private val pathPermitted:(WorldTravelPlan)->Boolean = {true},
    private val containmentRead:WorldTopologyContainmentPort=WorldTopologyContainmentPort.NONE
):WorldTopologyPort {
    init { require(maximumVisited in 1..4096) }
    override fun containment(scope:WorldResolutionScope,element:DomainRef):List<DomainRef> {
        if(!authorization.current(scope))return emptyList()
        val result=mutableListOf(element)
        var current=element
        repeat(64) {
            if(!authorization.current(scope))return emptyList()
            val parent=containmentRead.parent(scope,current)?:return result.toList()
            require(parent.kindUid in setOf("PLACE","LOCATION") && result.none { it.uid==parent.uid }) { "P63:CONTAINMENT_CYCLE" }
            result+=parent;current=parent
        }
        throw IllegalArgumentException("P63:CONTAINMENT_READ_BUDGET")
    }
    override fun legalEdges(scope:WorldResolutionScope,origin:DomainRef,at:WorldTimeTick):List<WorldTopologyEdge> {
        require(origin.kindUid in setOf("PLACE","LOCATION"))
        if(!authorization.current(scope))return emptyList()
        val other=DomainRef(if(origin.kindUid=="PLACE")"LOCATION" else "PLACE",origin.uid)
        val supplied=edges.from(scope.campaignUid,origin)+edges.from(scope.campaignUid,other)
        require(supplied.groupBy { it.uid }.values.all { rows->rows.distinct().size==1 }) { "P63:EDGE_IDENTITY_CONFLICT" }
        val read=supplied.distinctBy { it.uid }
        require(read.size<=512) { "P63:EDGE_READ_BUDGET" }
        require(read.all { WorldTopologyAnchor.same(it.origin,origin) }) { "P63:EDGE_READ_INTEGRITY" }
        return read.filter { edge->edge.validFrom<=at && (edge.validThrough==null || at<edge.validThrough) &&
            authorization.permitted(scope,edge,at) }.sortedBy { it.uid }
    }
    override fun travel(scope:WorldResolutionScope,origin:DomainRef,destination:DomainRef,at:WorldTimeTick):WorldResolutionResult {
        return search(scope,origin,setOf(destination),at)
    }
    /** One authorized graph traversal, not one traversal per category candidate. */
    fun closest(scope:WorldResolutionScope,origin:DomainRef,destinations:Set<DomainRef>,at:WorldTimeTick):WorldResolutionResult {
        require(destinations.isNotEmpty() && destinations.size<=512)
        return search(scope,origin,destinations,at)
    }
    private fun search(scope:WorldResolutionScope,origin:DomainRef,destinations:Set<DomainRef>,at:WorldTimeTick):WorldResolutionResult {
        if(!authorization.current(scope))return WorldResolutionResult.Unavailable("P63:STALE_RESOLUTION_SCOPE")
        val start=WorldTopologyAnchor.canonical(origin)
        val targets=destinations.sortedWith(compareBy<DomainRef> { it.uid }.thenBy { it.kindUid }).associateBy { WorldTopologyAnchor.canonical(it) }
        if(start in targets)return WorldResolutionResult.Clarification("P63:ALREADY_AT_DESTINATION")
        data class Step(val node:DomainRef,val elapsed:Long,val path:List<WorldTopologyEdge>,val key:String,val costs:Map<String,Long>)
        val frontier=PriorityQueue(compareBy<Step> { it.elapsed }.thenBy { it.node.uid }.thenBy { it.key })
        val first=Step(start,0,emptyList(),"",emptyMap())
        frontier+=first
        val settled=mutableSetOf<DomainRef>()
        val labels=mutableMapOf(start to mutableListOf(first))
        var labelCount=1
        fun dominates(a:Step,b:Step)=a.elapsed<=b.elapsed && (a.costs.keys+b.costs.keys).all { (a.costs[it]?:0)<=(b.costs[it]?:0) } &&
            (a.elapsed<b.elapsed || a.costs!=b.costs || a.key<=b.key)
        while(frontier.isNotEmpty()) {
            if(!authorization.current(scope))return WorldResolutionResult.Unavailable("P63:STALE_RESOLUTION_SCOPE")
            val current=frontier.remove()
            if(current !in labels[current.node].orEmpty())continue
            if(current.node !in settled && settled.size>=maximumVisited)return WorldResolutionResult.Unavailable("P63:ROUTE_SEARCH_BUDGET")
            settled+=current.node
            targets[current.node]?.let { destination->return WorldResolutionResult.Journey(destination,WorldTravelPlan(origin,destination,current.path)) }
            val reached=runCatching { at+ActionDuration(current.elapsed) }.getOrNull()
                ?:return WorldResolutionResult.Unavailable("P63:ROUTE_TIME_OVERFLOW")
            legalEdges(scope,current.node,reached).forEach { edge->
                if(current.path.size<1024 && current.path.none { WorldTopologyAnchor.same(it.origin,edge.destination) }) {
                    val elapsed=runCatching { Math.addExact(current.elapsed,edge.duration.milliseconds) }.getOrNull()
                        ?:return@forEach
                    val arrival=runCatching { at+ActionDuration(elapsed) }.getOrNull()?:return@forEach
                    val key=current.key+"/${edge.uid}"
                    val plan=WorldTravelPlan(origin,edge.destination,current.path+edge)
                    val costs=runCatching { plan.resourceCosts }.getOrNull()?:return@forEach
                    if(!pathPermitted(plan))return@forEach
                    val next=Step(WorldTopologyAnchor.canonical(edge.destination),elapsed,plan.edges,key,costs)
                    val previous=labels.getOrPut(next.node) { mutableListOf() }
                    if((edge.validThrough==null || arrival<edge.validThrough) && previous.none { dominates(it,next) }) {
                        if(labelCount>=maximumVisited*16)
                            return WorldResolutionResult.Unavailable("P63:ROUTE_FRONTIER_BUDGET")
                        previous.removeAll { dominates(next,it) }
                        previous+=next;labelCount++
                        frontier+=next
                    }
                }
            }
        }
        return WorldResolutionResult.Unavailable("P63:KNOWN_ROUTE_REQUIRED")
    }
}
