package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase63WorldTopologyTest {
    private val scope=WorldResolutionScope("C",HistoryGenerationUid("GEN"),1,"PLAYER:P","TRAVEL",emptyMap())
    private val a=DomainRef("PLACE","A");private val b=DomainRef("PLACE","B");private val c=DomainRef("PLACE","C")
    private val at=WorldTimeTick(1000)
    private fun edge(uid:String,from:DomainRef,to:DomainRef,duration:Long)=WorldTopologyEdge(uid,1,from,to,
        ActionDuration(duration),mapOf("STAMINA" to 1L),emptySet(),WorldTimeTick(0),null,"RULE:1")
    private fun topology(rows:List<WorldTopologyEdge>,hidden:Set<String> = emptySet(),current:Boolean=true)=AuthorizedWorldTopology(
        WorldTopologyEdgeReadPort { campaign,origin->if(campaign=="C")rows.filter { it.origin==origin } else emptyList() },
        object:WorldTopologyAuthorizationPort {
            override fun current(scope:WorldResolutionScope)=current && scope==this@Phase63WorldTopologyTest.scope
            override fun permitted(scope:WorldResolutionScope,edge:WorldTopologyEdge,at:WorldTimeTick)=edge.uid !in hidden
        })
    @Test fun routeUsesKnownConnectionsAndNotContainment() {
        assertEquals(WorldResolutionResult.Unavailable("P63:KNOWN_ROUTE_REQUIRED"),topology(emptyList()).travel(scope,a,b,at))
        val graph=topology(listOf(edge("DIRECT",a,c,30),edge("AB",a,b,10),edge("BC",b,c,10)))
        val result=graph.travel(scope,a,c,at) as WorldResolutionResult.Journey
        assertEquals(listOf("AB","BC"),result.plan.edges.map { it.uid })
        assertEquals(ActionDuration(20),result.plan.duration)
        assertEquals(mapOf("STAMINA" to 2L),result.plan.resourceCosts)
        assertEquals(WorldResolutionResult.Unavailable("P63:KNOWN_ROUTE_REQUIRED"),graph.travel(scope,c,a,at))
    }
    @Test fun explicitContainmentDoesNotAuthorizeMovementOrRevealAStaleParent() {
        var valid=true
        val graph=AuthorizedWorldTopology(WorldTopologyEdgeReadPort { _,_->emptyList() },
            object:WorldTopologyAuthorizationPort {
                override fun current(scope:WorldResolutionScope)=valid && scope==this@Phase63WorldTopologyTest.scope
                override fun permitted(scope:WorldResolutionScope,edge:WorldTopologyEdge,at:WorldTimeTick)=true
            },containmentRead=WorldTopologyContainmentPort { _,ref->if(ref==a)b else null })
        assertEquals(listOf(a,b),graph.containment(scope,a))
        assertEquals(WorldResolutionResult.Unavailable("P63:KNOWN_ROUTE_REQUIRED"),graph.travel(scope,a,b,at))
        valid=false;assertTrue(graph.containment(scope,a).isEmpty())
    }
    @Test fun hiddenEdgesStaleAndCrossCampaignAreNotDisclosed() {
        val graph=topology(listOf(edge("HIDDEN",a,b,10)),setOf("HIDDEN"))
        assertTrue(graph.legalEdges(scope,a,at).isEmpty())
        assertEquals(WorldResolutionResult.Unavailable("P63:KNOWN_ROUTE_REQUIRED"),graph.travel(scope,a,b,at))
        assertEquals(WorldResolutionResult.Unavailable("P63:STALE_RESOLUTION_SCOPE"),graph.travel(scope.copy(campaignUid="OTHER"),a,b,at))
        assertEquals(WorldResolutionResult.Unavailable("P63:STALE_RESOLUTION_SCOPE"),topology(emptyList(),current=false).travel(scope,a,b,at))
    }
    @Test fun tieAndInputOrderAreStableAndDoNotAllowExpiredArrival() {
        val rows=listOf(edge("Z",a,b,10),edge("A",a,b,10))
        assertEquals(topology(rows).travel(scope,a,b,at),topology(rows.reversed()).travel(scope,a,b,at))
        assertEquals("A",(topology(rows).travel(scope,a,b,at) as WorldResolutionResult.Journey).plan.edges.single().uid)
        val expired=edge("EXP",a,b,10).copy(validThrough=WorldTimeTick(1005))
        assertEquals(WorldResolutionResult.Unavailable("P63:KNOWN_ROUTE_REQUIRED"),topology(listOf(expired)).travel(scope,a,b,at))
    }
    @Test fun lodOnlySelectsDetailAndDoesNotEraseNamedIdentity() {
        assertEquals(WorldSimulationLod.LOD3_INDIVIDUAL,WorldLodPolicy.level(WorldLodInterest(a,activePlayer=true)))
        assertEquals(WorldSimulationLod.LOD2_FEATURED,WorldLodPolicy.level(WorldLodInterest(a,featured=true)))
        assertEquals(WorldSimulationLod.LOD1_UNIT,WorldLodPolicy.level(WorldLodInterest(a,formation=true)))
        assertEquals(WorldSimulationLod.LOD0_AGGREGATE,WorldLodPolicy.level(WorldLodInterest(a)))
    }
    @Test fun laterArrivalRemainsLegalWhenAnOnwardConnectionOpensLater() {
        val d=DomainRef("PLACE","D")
        val rows=listOf(edge("FAST",a,b,1).copy(resourceCosts=emptyMap()),
            edge("AC",a,c,2).copy(resourceCosts=emptyMap()),
            edge("CB",c,b,2).copy(resourceCosts=emptyMap()),
            edge("BD",b,d,1).copy(resourceCosts=emptyMap(),validFrom=WorldTimeTick(1003)))
        val result=topology(rows).travel(scope,a,d,at) as WorldResolutionResult.Journey
        assertEquals(listOf("AC","CB","BD"),result.plan.edges.map { it.uid })
        assertEquals(ActionDuration(5),result.plan.duration)
        assertEquals(result,topology(rows.reversed()).travel(scope,a,d,at))
    }
    @Test fun resourceConstrainedRoutingKeepsSlowerFeasibleAlternativesAndPlaceAliases() {
        val d=DomainRef("LOCATION","D")
        val rows=listOf(edge("AB",a,b,1).copy(resourceCosts=mapOf("STAMINA" to 9L)),
            edge("AC",a,c,2).copy(resourceCosts=mapOf("STAMINA" to 2L)),
            edge("CB",c,b,2).copy(resourceCosts=mapOf("STAMINA" to 2L)),
            edge("BD",b,d,1).copy(resourceCosts=mapOf("STAMINA" to 2L)))
        val graph=AuthorizedWorldTopology(WorldTopologyEdgeReadPort { _,origin->rows.filter { it.origin==origin } },
            object:WorldTopologyAuthorizationPort {
                override fun current(scope:WorldResolutionScope)=scope==this@Phase63WorldTopologyTest.scope
                override fun permitted(scope:WorldResolutionScope,edge:WorldTopologyEdge,at:WorldTimeTick)=true
            },pathPermitted={it.resourceCosts.values.sum()<=10})
        val result=graph.travel(scope,DomainRef("LOCATION",a.uid),DomainRef("PLACE",d.uid),at) as WorldResolutionResult.Journey
        assertEquals(listOf("AC","CB","BD"),result.plan.edges.map { it.uid })
        assertEquals(6L,result.plan.resourceCosts["STAMINA"])
        assertEquals(rows.last().fingerprint,result.plan.edges.last().fingerprint)
    }
}
