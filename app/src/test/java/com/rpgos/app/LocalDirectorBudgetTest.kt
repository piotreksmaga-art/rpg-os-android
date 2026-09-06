package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28])
class LocalDirectorBudgetTest {
    private fun request() = AiDirectorRequest("request", "job",
        DirectorTrigger("trigger", "campaign", DirectorTriggerKind.CADENCE, 10),
        DirectorContextEnvelope("campaign", "version", 10,
            (1..2000).map { "RECORD-$it" }.toSet(),
            listOf("Gracz odpoczywa.", "Nie atakuje.".repeat(2000), "Spokojny poranek."),
            setOf("tajny sekret"), "projection"))

    @Test fun completePromptAndOutputFitMobileContextWithoutUidManifest() {
        val payload = LocalCompactAiJsonCodec().encodeDirector(request())
        assertTrue(payload.toByteArray().size <= LocalDirectorCodec.MAX_PAYLOAD_BYTES)
        assertFalse(payload.contains("RECORD-"))
        assertTrue(payload.contains("Gracz odpoczywa."))
        val prompt = ExecuTorchInferenceService.bielikChatPrompt(payload)
        // Byte upper bound is deliberately conservative for byte-fallback tokenization.
        assertTrue(prompt.toByteArray().size + 512 + 32 < 2048)
    }

    @Test fun identityComesFromRequestAndCoreStillRejectsHiddenContent() {
        val request = request()
        val result = LocalCompactAiJsonCodec().decodeDirector(
            """{"title":"Tempo","summary":"tajny sekret","kind":"PACING_HINT"}""", request)
        assertEquals(request.jobUid, result.jobUid)
        assertEquals(request.context.campaignUid, result.campaignUid)
        assertTrue(result.candidates.single().supportingProjectedRecordUids.isEmpty())
        assertTrue(DirectorBundleValidator().validate(result, request, "version")
            .reasonUids.contains("DIRECTOR_HIDDEN_LEAKAGE"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun modelCannotSmuggleMutationOrRequestIdentity() {
        LocalDirectorCodec.decode("""{"title":"x","summary":"y","kind":"PACING_HINT","campaign_uid":"other"}""", request())
    }
}
