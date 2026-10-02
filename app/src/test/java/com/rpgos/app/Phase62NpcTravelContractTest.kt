package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase62NpcTravelContractTest {
    private val origin=DomainRef("PLACE","VILLAGE-A")
    private val destination=DomainRef("PLACE","VILLAGE-B")
    private val catalogActor=DomainRef("NPC","CATALOG-NPC")
    private val route=NpcTravelRouteContract(
        campaignUid="C1",routeUid="ROUTE-A-B",version=1,origin=origin,destination=destination,
        duration=ActionDuration(120_000),timingRuleUid="WORLD:ROAD_TRAVEL_V1",resourceCosts=mapOf("STAMINA" to 3)
    )

    @Test fun routeIdentityIsStableAndRegisteredLookupIsOriginScoped() {
        assertEquals(route.fingerprint,route.copy().fingerprint)
        val reverse=route.copy(routeUid="ROUTE-B-A",origin=destination,destination=origin)
        val port=NpcTravelRoutePort.registered(listOf(reverse,route))
        assertEquals(listOf(route),port.routes("C1",catalogActor,origin))
        assertEquals(listOf(reverse),port.routes("C1",catalogActor,destination))
        assertTrue(port.routes("C2",catalogActor,origin).isEmpty())
    }

    @Test fun routeCannotPretendOriginEqualsDestinationOrHaveZeroDuration() {
        assertThrows(IllegalArgumentException::class.java) { route.copy(destination=origin) }
        assertThrows(IllegalArgumentException::class.java) { route.copy(duration=ActionDuration(0)) }
    }

    @Test fun arrivalPayloadUsesCanonicalSpatialOwnerShapeWithoutFakeMovementDelta() {
        val actor=DomainRef("NPC","NPC-1")
        val payload=NpcTravelAffordances.arrivalPayload(actor,route)
        assertEquals(actor,payload.subject);assertEquals(destination,payload.destinationLocation)
        assertEquals(0L,payload.deltaXMillimetres);assertEquals(0L,payload.deltaYMillimetres)
        val owner=NpcTravelAffordances.ownerContract(route)
        assertEquals(NpcActionProcess.OWNER,owner.lifecycleOwnerUid)
        assertEquals("RPGOS-P50:SPATIAL",owner.resultOwnerUid)
        assertEquals(setOf(PlayerChangeKinds.SPATIAL),owner.allowedCanonicalChangeKindUids)
        assertEquals(NpcActivityResultPolicy.DOMAIN_RESULT_EVIDENCE,owner.resultPolicy)
    }

    @Test fun duplicateRouteRevisionIsRejectedAndRegistryCopiesCostMap() {
        assertThrows(IllegalArgumentException::class.java) { NpcTravelRoutePort.registered(listOf(route,route)) }
        val mutable=mutableMapOf("STAMINA" to 3L)
        val port=NpcTravelRoutePort.registered(listOf(route.copy(resourceCosts=mutable)))
        mutable["STAMINA"]=99
        assertEquals(3L,port.routes("C1",catalogActor,origin).single().resourceCosts["STAMINA"])
    }

    @Test fun travelAffordanceRequiresCurrentOriginAndAuthorizedDestinationKnowledge() {
        val actorRef=DomainRef("NPC","NPC-1")
        val base=NpcBrainOwner.initialize("C1",actorRef,"seed")
        val brain=base.copy(goals=listOf(NpcGoal("G",base.motivations.first().uid,"Dotrzeć do celu",NpcWeight(5000),
            NpcGoalLifecycle.ACTIVE,NpcCauseRef(NpcCauseKind.INTRINSIC_MOTIVATION,base.motivations.first().uid))))
        val known=NpcKnownRecord("DEST-KNOWLEDGE",KnowledgeEpistemicState.KNOWN,"Znam drogę do celu.","ACQ",1,setOf(destination))
        val mechanical=MechanicalActorView("C1",actorRef,MechanicalActorKind.NPC,1,MechanicalStateMaterialization.FULL,
            emptyMap(),listOf(MechanicalResource("STAMINA",10,10)),setOf("TRAVEL"),locationRef=origin,generationProvenanceUid="GEN")
        val port=NpcTravelRoutePort.registered(listOf(route))
        val offered=NpcTravelAffordances.options(brain,listOf(known),mechanical,port)
        assertEquals(1,offered.size);assertEquals(destination,offered.single().target)
        assertEquals(NpcTravelAffordances.EFFECT_KIND,offered.single().mechanicalEffectKindUid)
        assertTrue(NpcTravelAffordances.options(brain,emptyList(),mechanical,port).isEmpty())
        assertTrue(NpcTravelAffordances.options(brain,listOf(known),mechanical.copy(locationRef=destination),port).isEmpty())
        assertTrue(NpcTravelAffordances.options(brain,listOf(known),mechanical.copy(campaignUid="C2"),port).isEmpty())
        assertTrue(NpcTravelAffordances.options(brain,listOf(known),mechanical.copy(executableAbilityUids=emptySet()),port).isEmpty())
        assertTrue(NpcTravelAffordances.options(brain,listOf(known),mechanical.copy(resources=emptyList()),port).isEmpty())
        assertTrue(NpcTravelAffordances.options(brain,listOf(known),mechanical.copy(kind=MechanicalActorKind.ACTIVE_PLAYER),port).isEmpty())
    }

    @Test fun sameRouteIdentityInDifferentCampaignsDoesNotCollide() {
        val other=route.copy(campaignUid="C2")
        val port=NpcTravelRoutePort.registered(listOf(route,other))
        assertEquals(listOf(route),port.routes("C1",catalogActor,origin));assertEquals(listOf(other),port.routes("C2",catalogActor,origin))
        assertNotEquals(route.fingerprint,other.fingerprint)
    }

    @Test fun registryDoesNotExposeMutableCostsAndEligibilityIsPartOfIdentity() {
        val stored=NpcTravelRoutePort.registered(listOf(route)).routes("C1",catalogActor,origin).single()
        assertThrows(UnsupportedOperationException::class.java) { (stored.resourceCosts as MutableMap<String,Long>)["STAMINA"]=99L }
        assertEquals(route.fingerprint,stored.fingerprint)
        assertNotEquals(route.fingerprint,route.copy(eligibility=NpcActivityEligibility.CONSCIOUS_SELF).fingerprint)
    }
}
