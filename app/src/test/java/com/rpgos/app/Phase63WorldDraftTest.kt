package com.rpgos.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Phase63WorldDraftTest {
    private val request=AiWorldDraftRequest("REQ",WorldResolutionScope("C",HistoryGenerationUid("G"),4,"P","WORLD_DRAFT",mapOf("CORE" to "1")),
        WorldDraftPurpose.ELEMENT,"Szukam miejsca do ćwiczeń.","miejsce do ćwiczeń","Współczesność",WorldElementBaseKind.PLACE,null,null)
    private fun answer()=JSONObject().put("request_uid",request.requestUid).put("request_fingerprint",request.fingerprint)
        .put("state","DRAFT").put("display_name","Miejsce ćwiczeń").put("base_kind","PLACE").put("category_uid","TRAINING_SITE")
        .put("affordance_uids",JSONArray(listOf("TRAIN"))).put("topology_class_uid","LOCAL_SITE")
        .put("description","Propozycja miejsca ćwiczeń, nie ustalenie jego istnienia.").put("question",JSONObject.NULL)
    @Test fun candidateIsCorrelatedAndCarriesNoAuthority() {
        assertEquals(WorldDraftState.DRAFT,WorldDraftCodec.decode(answer().toString(),request).state)
        val wire=JSONObject(WorldDraftCodec.encode(request))
        assertEquals(request.fingerprint,wire.getString("request_fingerprint"))
        assertFalse(wire.has("seed"));assertFalse(wire.has("canonical_state"))
        assertTrue(runCatching { WorldDraftCodec.decode(answer().put("stats",JSONObject()).toString(),request) }.isFailure)
        assertTrue(runCatching { WorldDraftCodec.decode(answer().put("element_uid","INVENTED").toString(),request) }.isFailure)
    }
    @Test fun staleIdentityAndChangedTopologyAreRejected() {
        assertTrue(runCatching { WorldDraftCodec.decode(answer().toString(),request.copy(scope=request.scope.copy(historyGenerationUid=HistoryGenerationUid("NEXT")))) }.isFailure)
        val sea=request.copy(topologyClassUid="SEA")
        assertTrue(runCatching { WorldDraftCodec.decode(answer().put("request_fingerprint",sea.fingerprint).toString(),sea) }.isFailure)
        assertTrue(runCatching { WorldDraftCodec.decode(answer().put("base_kind","ACTOR").toString(),request) }.isFailure)
    }
    @Test fun clarificationCannotTurnIntoAnInventedGenericElement() {
        val reference=IntentReference("R",IntentReferenceKind.DESCRIPTIVE,"dziwne miejsce","TARGET")
        val shape=WorldReferenceShape(WorldReferenceShapeKind.CATEGORY,WorldElementBaseKind.PLACE,null,emptySet(),null)
        val candidate=WorldDraftCandidate(WorldDraftState.CLARIFICATION,null,null,null,emptySet(),null,null,"Jakie miejsce masz na myśli?")
        val interpreted=requireNotNull(worldDraftInterpretationHint(reference,shape,candidate))
        assertEquals(IntentReferenceState.UNRESOLVED,interpreted.state)
        assertNull(interpreted.resolvedProjectedRef)
        assertTrue(interpreted.candidateProjectedRefs.isEmpty())
        assertEquals("P63:WORLD_DRAFT_REQUIRES_CLARIFICATION",interpreted.descriptorHints["world_resolution_reason"])
        assertNotNull(Phase63WorldMessages.explanation(interpreted.descriptorHints.values.toList()))
        assertNull(worldDraftInterpretationHint(reference,shape,candidate.copy(state=WorldDraftState.UNKNOWN,question=null)))
    }
    @Test fun sameTransportAndCancellationAreUsed() {
        var calls=0
        val capabilities=AiCapabilityContract("CONTRACT","TEST","M",setOf(AiWorkload.WORLD_DRAFT),maximumContextUnits=2048)
        val provider=TransportAiProviderAdapter(capabilities,AiStructuredTransport { wire,_ ->
            calls++;assertEquals(AiWorkload.WORLD_DRAFT,wire.workload)
            AiProviderResult.Success(AiTransportResponse(wire.requestUid,answer().toString(),"TRACE"),"TEST","M","TRACE")
        },CanonicalAiJsonCodec())
        assertTrue(provider.draftWorld(request) is AiProviderResult.Success)
        assertTrue(provider.draftWorld(request,AiCancellationSignal { true }) is AiProviderResult.Failure)
        assertEquals(1,calls)
        assertEquals("object",OpenRouterStructuredOutputSchema.schema(AiWorkload.WORLD_DRAFT).getString("type"))
    }
    @Test fun nativeBootstrapUsesExistingTransportAndNeverOverwritesPlayerPremise() {
        var calls=0;var current=true
        val capabilities=AiCapabilityContract("BOOT","TEST","M",setOf(AiWorkload.WORLD_DRAFT),maximumContextUnits=2048)
        val provider=TransportAiProviderAdapter(capabilities,AiStructuredTransport { wire,_ ->
            calls++
            val input=JSONObject(wire.payload)
            assertEquals("BOOTSTRAP",input.getString("purpose"))
            val value=JSONObject().put("request_uid",input.getString("request_uid")).put("request_fingerprint",input.getString("request_fingerprint"))
                .put("state","DRAFT").put("display_name","Nie wolno podmienić nazwy gracza").put("base_kind","PLACE").put("category_uid","MOUNTAIN_SETTLEMENT")
                .put("affordance_uids",JSONArray()).put("topology_class_uid","LOCAL_SITE").put("description",JSONObject.NULL).put("question",JSONObject.NULL)
            AiProviderResult.Success(AiTransportResponse(wire.requestUid,value.toString(),"TRACE"),"TEST","M","TRACE")
        },CanonicalAiJsonCodec())
        val route=AiModelRoutePort { _,_,_->AiRouteResult.Selected(provider,false,"PINNED") }
        val spec=NativeWorldCreationSpec("Świat","Górska wioska bez magii.","Era","Mój start")
        val result=NativeWorldBootstrapInterpretation(route).prepare(spec,request.scope) { current }
        assertEquals(spec.copy(startingCategory="MOUNTAIN_SETTLEMENT"),result);assertEquals(1,calls)
        current=false
        assertEquals(spec,NativeWorldBootstrapInterpretation(route).prepare(spec,request.scope) { current });assertEquals(1,calls)
        assertEquals(spec,NativeWorldBootstrapInterpretation(AiModelRoutePort { _,_,_->AiRouteResult.Unavailable(listOf("HOST_ABSENT")) }).prepare(spec,request.scope) { true })
    }
}
