package com.rpgos.app

import android.content.Context
import org.json.JSONException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LlamaCppGgufIntegrationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication().applicationContext
        context.getSharedPreferences("rpgos_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("rpgos_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun localCodecAcceptsBielikReplyEnvelopeWithoutWeakeningTheTypedCandidate() {
        val raw="""{"reply":"{\"status\":\"Q\",\"question\":\"Gdzie rozpoczynasz grę?\",\"missing_categories\":[\"lokacja\"]}"}"""

        val candidate=LocalCompactAiJsonCodec().decodeCharacterCreation(raw)

        assertEquals(
            CharacterCreationGmCandidate.NeedsPlayerChoice("Gdzie rozpoczynasz grę?",listOf("lokacja")),
            candidate,
        )
    }

    @Test
    fun localCodecRecoversMalformedBielikQuestionButKeepsMalformedDraftFailClosed() {
        val malformedQuestion="""{"reply":"{\"status\":\"Q\",\"q\":\"WhatisthemaximumvalueofHEALTH?\",\"m\":[\[\"HEALTH\"]}"}"""
        val malformedDraft="""{"reply":"{\"status\":\"R\",\"n\":\"Smagi\",\"st\":[\[}"}"""

        assertEquals(
            CharacterCreationGmCandidate.NeedsPlayerChoice(
                "Jaką płeć, pochodzenie, talent i najważniejsze umiejętności ma mieć Twoja postać?",
                emptyList(),
            ),
            LocalCompactAiJsonCodec().decodeCharacterCreation(malformedQuestion),
        )
        assertThrows(JSONException::class.java) {
            LocalCompactAiJsonCodec().decodeCharacterCreation(malformedDraft)
        }
    }

    @Test
    fun localCodecMakesUnreadableButValidSmallModelQuestionPlayerFacing() {
        val raw="""{"reply":"{\"status\":\"Q\",\"q\":\"Whatisthenameofthelocationwherethestorytakesplace?\",\"m\":[]}"}"""

        assertEquals(
            CharacterCreationGmCandidate.NeedsPlayerChoice(
                "Jaką płeć, pochodzenie, talent i najważniejsze umiejętności ma mieć Twoja postać?",
                emptyList(),
            ),
            LocalCompactAiJsonCodec().decodeCharacterCreation(raw),
        )
    }

    @Test
    fun historicalXclipseProfile_isTheGgufDefaultAndSupportsEveryEngineWorkload() {
        val profile = BielikLocalModelProfiles.USER_GGUF
        val settings = LocalRecommendedSettings.forProfile(profile)

        assertEquals(AiWorkload.entries.toSet(), profile.supportedWorkloads)
        assertEquals(LocalRuntimeEngine.LLAMA_CPP, settings.runtimeEngine)
        assertEquals(LocalRuntimeBackend.GPU, settings.backend)
        assertEquals(8_192, settings.contextUnits)
        assertEquals(99, settings.gpuLayers)
        assertEquals(64, settings.prefillBatchUnits)
        assertEquals(64, settings.microBatchUnits)
        assertEquals(LocalKvCacheType.F16, settings.kvKeyType)
        assertEquals(LocalKvCacheType.F16, settings.kvValueType)
        assertEquals("LOCAL:LLAMA_CPP_VULKAN", settings.localProviderUid())
    }

    @Test
    fun userManagedGguf_hasNoMemoryThermalContextOrGpuLayerAdmissionLimit() {
        val profile = BielikLocalModelProfiles.USER_GGUF
        val settings = LocalRecommendedSettings.forProfile(profile).copy(
            contextUnits = 131_072,
            gpuLayers = -1,
            recommended = false,
        )
        val runtime = LocalRuntimeCapabilities(
            runtimeUid = "LLAMA_CPP_VULKAN",
            supportedFormats = setOf(LocalArtifactFormat.GGUF),
            supportedBackends = setOf(LocalRuntimeBackend.AUTO, LocalRuntimeBackend.CPU, LocalRuntimeBackend.GPU),
            supportsContextTuning = true,
            supportsKvTuning = true,
            supportsThreads = true,
            supportsBatchPrefill = true,
            supportsCancellation = true,
            supportsStreaming = true,
        )
        val deliberatelyConstrainedDevice = LocalDeviceCapabilities(
            availableMemoryBytes = 0,
            totalMemoryBytes = 1,
            thermalState = LocalThermalState.CRITICAL,
            availableBackends = setOf(LocalRuntimeBackend.GPU),
            recommendedSafetyMarginBytes = Long.MAX_VALUE,
        )

        val result = LocalModelAdmissionController().evaluate(
            profile,
            settings,
            runtime,
            deliberatelyConstrainedDevice,
        )

        assertTrue(result is LocalAdmissionResult.Admitted)
        assertEquals("USER_MANAGED_GGUF_PROFILE", (result as LocalAdmissionResult.Admitted).reasonUid)
        assertEquals(LocalRuntimeBackend.GPU, result.selectedBackend)
    }

    @Test
    fun manualGgufSettings_roundTripAndBecomeTheSameProviderChoiceUsedByBothAiRoles() {
        val profile = BielikLocalModelProfiles.USER_GGUF
        val manual = LocalRecommendedSettings.forProfile(profile).copy(
            contextUnits = 32_768,
            backend = LocalRuntimeBackend.GPU,
            threads = 6,
            prefillBatchUnits = 96,
            microBatchUnits = 48,
            gpuLayers = -1,
            kvKeyType = LocalKvCacheType.Q8_0,
            kvValueType = LocalKvCacheType.Q4_0,
            temperature = 0.72f,
            topK = 55,
            topP = 0.88f,
            repeatPenalty = 1.17f,
            flashAttention = true,
            memoryMap = false,
            recommended = false,
        )
        val selection = AiModelSelection(manual.localProviderUid(), profile.modelUid)
        val configuration = AiSystemConfiguration(
            gameMaster = AiRoleAssignment(AiRole.GAME_MASTER, AiAssignmentKind.PINNED, selection),
            director = AiRoleAssignment(AiRole.DIRECTOR_SCENARIST, AiAssignmentKind.PINNED, selection),
            localModelSettings = manual,
        )

        val original = AppSettings(context).load()
        AppSettings(context).save(original.copy(ai = configuration))
        val restored = AppSettings(context).load().ai
        val state = AiProviderCenterStateFactory.initial(
            restored,
            artifactInstalled = true,
            openRouter = CloudConnectionStatus("OPENROUTER", CloudAuthState.DISCONNECTED),
            profile = profile,
        )

        assertEquals(manual, restored.localModelSettings)
        assertEquals(selection, restored.gameMaster.pinned)
        assertEquals(selection, restored.director.pinned)
        assertEquals(selection, state.modelOptions.single().selection)
    }
}
