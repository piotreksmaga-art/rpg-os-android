package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase62NpcTravelContractTest {
    private val origin=DomainRef("PLACE","VILLAGE-A")
    private val destination=DomainRef("PLACE","VILLAGE-B")
    private val route=NpcTravelRouteContract(
        routeUid="ROUTE-A-B",
        version=1,
        origin=origin,
        destination=destination,
        duration=ActionDuration(120_000),
        timingRuleUid="WORLD:ROAD_TRAVEL_V1",
        resourceCosts=mapOf("STAMINA" to 3)
    )

    @Test fun routeIdentityIsStableAndRegisteredLookupIsOriginScoped() {
        val copy=route.copy()
        assertEquals(route.fingerprint,copy.fingerprint)
        val reverse=route.copy(routeUid="ROUTE-B-A",origin=destination,destination=origin)
        val port=NpcTravelRoutePort.registered(listOf(reverse,route))
        assertEquals(listOf(route),port.routes("C1",origin))
        assertEquals(listOf(reverse),port.routes("C1",destination))
    }

    @Test fun routeCannotPretendOriginEqualsDestinationOrHaveZeroDuration() {
        assertThrows(IllegalArgumentException::class.java) {
            route.copy(destination=origin)
        }
        assertThrows(IllegalArgumentException::class.java) {
            route.copy(duration=ActionDuration(0))
        }
    }

    @Test fun arrivalPayloadUsesCanonicalSpatialOwnerShapeWithoutFakeMovementDelta() {
        val actor=DomainRef("NPC","NPC-1")
        val payload=NpcTravelAffordances.arrivalPayload(actor,route)
        assertEquals(actor,payload.subject)
        assertEquals(destination,payload.destinationLocation)
        assertEquals(0L,payload.deltaXMillimetres)
        assertEquals(0L,payload.deltaYMillimetres)

        val owner=NpcTravelAffordances.ownerContract(route)
        assertEquals(NpcActionProcess.OWNER,owner.lifecycleOwnerUid)
        assertEquals("RPGOS-P50:SPATIAL",owner.resultOwnerUid)
        assertEquals(setOf(PlayerChangeKinds.SPATIAL),owner.allowedCanonicalChangeKindUids)
        assertEquals(NpcActivityResultPolicy.DOMAIN_RESULT_EVIDENCE,owner.resultPolicy)
    }

    @Test fun duplicateRouteRevisionIsRejectedAndRegistryCopiesCostMap() {
        assertThrows(IllegalArgumentException::class.java) {
            NpcTravelRoutePort.registered(listOf(route,route))
        }
        val mutable=mutableMapOf("STAMINA" to 3L)
        val source=route.copy(resourceCosts=mutable)
        val port=NpcTravelRoutePort.registered(listOf(source))
        mutable["STAMINA"]=99
        assertEquals(3L,port.routes("C1",origin).single().resourceCosts["STAMINA"])
    }
}
