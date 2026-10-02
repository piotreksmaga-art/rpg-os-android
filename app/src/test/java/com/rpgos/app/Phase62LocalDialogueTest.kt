package com.rpgos.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class Phase62LocalDialogueTest {
    private fun request():NpcDialogueRequest {
        val actor=DomainRef("NPC","N1")
        val brain=NpcBrainOwner.initialize("C1",actor,"SEED")
        val scope=NpcDecisionScope(TemporalScope("C1","G1",1,"HASH"),actor,brain.revision,WorldTimeTick(0),0,"P1")
        val trigger=NpcTrigger("T",NpcTriggerKind.SELF_REFLECTION,WorldTimeTick(0),
            NpcCauseRef(NpcCauseKind.INTRINSIC_MOTIVATION,brain.motivations.first().uid))
        val record=NpcKnownRecord("MEM",KnowledgeEpistemicState.BELIEVED,"Podobno most jest zamknięty","ACQ",1)
        return NpcDialogueRequest("REQ",NpcDecisionContextEnvelope(scope,trigger,brain,listOf(record),emptyList(),2048),"Czy most jest zamknięty?")
    }
    @Test fun localWireKeepsHolderBeliefsAndBindsOnlyAuthorizedSources() {
        val request=request();val codec=LocalCompactAiJsonCodec()
        val wire=JSONObject(codec.encodeNpcDialogue(request))
        assertEquals("RPGOS_NPC_DIALOGUE_LOCAL_1",wire.getString("v"))
        assertEquals(request.receivedMessage,wire.getString("received_message"))
        assertEquals("BELIEVED",wire.getJSONArray("records").getJSONObject(0).getString("kind"))
        assertFalse(wire.has("request_uid"));assertFalse(wire.has("context_fingerprint"))
        val answer=codec.decodeNpcDialogue("""{"t":"Podobno jest zamknięty, ale nie mam potwierdzenia.","r":[0]}""",request)
        assertEquals(request.requestUid,answer.requestUid);assertEquals(request.fingerprint,answer.contextFingerprint)
        assertEquals(setOf("MEM"),answer.supportingRecordUids)
        assertTrue(codec.decodeNpcDialogue("""{"t":"Nie wiem.","r":[]}""",request).supportingRecordUids.isEmpty())
        listOf("[1]","[-1]","[0.5]","[0,0]","[\"MEM\"]").forEach{sources->
            assertTrue(sources,runCatching{codec.decodeNpcDialogue("""{"t":"Nie wiem.","r":$sources}""",request)}.isFailure)
        }
        assertTrue(runCatching{codec.decodeNpcDialogue("""{"t":"Nie wiem.","r":[],"health":100}""",request)}.isFailure)
        assertTrue(runCatching{codec.decodeNpcDialogue("""{"t":"P62:TECHNICAL","r":[]}""",request)}.isFailure)
    }
    @Test fun transportUsesCompactDialogueAndSeedPreservesTheAnswer() {
        val request=request();var seen:AiTransportRequest?=null
        val adapter=TransportAiProviderAdapter(AiCapabilityContract("C","LOCAL","M",setOf(AiWorkload.NPC_DIALOGUE),
            maximumContextUnits=2048,providerKind=AiProviderKind.LOCAL),AiStructuredTransport{r,_->
            seen=r;AiProviderResult.Success(AiTransportResponse(r.requestUid,"""{"t":"Nie wiem.","r":[]}""","TRACE"),"LOCAL","M","TRACE")
        },LocalCompactAiJsonCodec())
        val answer=adapter.speakNpc(request) as AiProviderResult.Success
        assertEquals("Nie wiem.",answer.value.text)
        val wire=requireNotNull(seen).payload
        assertEquals("{\"t\":\"",ExecuTorchInferenceService.structuredSeed(wire))
        val prompt=ExecuTorchInferenceService.bielikChatPrompt(wire)
        assertTrue(prompt.contains("BELIEVED"));assertTrue(prompt.contains(request.receivedMessage));assertTrue(prompt.endsWith("{\"t\":\""))
        assertTrue(prompt.contains("Nie przepisuj otrzymanych słów"));assertFalse(prompt.contains("request_uid"))
        val normalized=ExecuTorchInferenceService.normalizeStructuredOutput(wire,"Nie wiem.\",\"r\":[]}")
        assertEquals("Nie wiem.",LocalCompactAiJsonCodec().decodeNpcDialogue(normalized,request).text)
    }
}
