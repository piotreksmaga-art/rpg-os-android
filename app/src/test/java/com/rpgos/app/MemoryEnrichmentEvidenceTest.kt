package com.rpgos.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MemoryEnrichmentEvidenceTest {
    private val generation=HistoryGenerationUid("HGEN-C1-0")
    private val holder=KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER,"P1","C1")
    private val leaves=listOf(MemorySourceLeafRef("EVENT","E1",1,1,"FP"))
    private val manifest=EpisodeManifest(MemoryArtifactIdentity("C1",generation,"EP","REV",
        MemoryArtifactKind.EPISODE_MANIFEST,leaves,memoryLeafFingerprint(leaves),"RULE",1,1,1,1),
        listOf("E1"),1,1,emptyList(),emptyList(),"RULE",1)
    private val record=NpcKnownRecord("R1",KnowledgeEpistemicState.BELIEVED,"Podobno most jest zamknięty.",
        "ACQ1",1,sourceCommittedOrder=1,sourceEventUid="E1")
    private fun context()=MemoryEnrichmentContext("C1",generation,holder,listOf(record))
    private fun request()=MemoryEnrichmentRequest("REQ",manifest,"pl")

    @Test fun wireContainsActualScopedTextAndKeepsBeliefEpistemics() {
        val wire=JSONObject(encodeMemoryEnrichmentRequest(request().copy(authorizedContext=context())))
        val evidence=wire.getJSONArray("authorized_evidence").getJSONObject(0)
        assertEquals(record.projectedText,evidence.getString("text"))
        assertEquals("BELIEVED",evidence.getString("epistemic_state"))
        assertEquals("E1",evidence.getString("event_uid"))
        assertFalse(wire.toString().contains("hidden"))
    }
    @Test fun foreignEventCampaignGenerationAndFutureStateAreRejected() {
        listOf(context().copy(campaignUid="C2",holder=holder.copy(campaignUid="C2")),
            context().copy(historyGenerationUid=HistoryGenerationUid("OLD")),
            context().copy(records=listOf(record.copy(sourceEventUid="OTHER"))),
            context().copy(records=listOf(record.copy(sourceCommittedOrder=2)))).forEach { invalid ->
            assertTrue(runCatching{request().copy(authorizedContext=invalid)}.isFailure)
        }
    }
    @Test fun metadataAloneNeverInvokesModelOrRouting() {
        val port=DynamicMemoryEnrichmentPort(AiModelRoutePort{_,_,_->error("Metadata cannot be sent to AI")}){null}
        assertEquals("MEMORY_ENRICHMENT_NO_AUTHORIZED_EVIDENCE",
            (port.enrich(request(),AiCancellationSignal.NONE) as MemoryEnrichmentResult.Failure).reasonUid)
    }
}
