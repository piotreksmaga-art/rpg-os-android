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
    fun localCodecNormalizesObservedScalarMissingCategoryForQuestionOnlyReply() {
        val raw="""{"s":"Q","q":"Czy chcesz rozpocząć naukę w Akademii w Konosze?","m":"academy"}"""

        assertEquals(
            CharacterCreationGmCandidate.NeedsPlayerChoice(
                "Czy chcesz rozpocząć naukę w Akademii w Konosze?",
                listOf("academy"),
            ),
            LocalCompactAiJsonCodec().decodeCharacterCreation(raw),
        )
    }

    @Test
    fun execuTorchCharacterPromptMovesTaskAfterDataAndSeedsOneJsonObject() {
        val payload="""{"v":"RPGOS_CC_LOCAL_1","c":"C","x":[],"d":{},"reply":"Return s=Q or s=R"}"""
        val prompt=ExecuTorchInferenceService.bielikChatPrompt(payload)

        assertTrue(prompt.contains("DANE JSON:"))
        assertTrue(prompt.contains("ZADANIE:"))
        assertTrue(prompt.contains("Return s=Q or s=R"))
        assertTrue(!prompt.contains("\"reply\":\"Return s=Q or s=R\""))
        assertTrue(prompt.endsWith("{\"s\":\"R\",\"n\":\""))
        assertEquals("{\"s\":\"R\",\"n\":\"",ExecuTorchInferenceService.characterCreationSeed(payload))
        assertEquals("{\"s\":\"Q\",\"q\":\"",ExecuTorchInferenceService.characterCreationSeed(
            """{"v":"RPGOS_CC_LOCAL_1","mode":"CATALOG_QUESTION"}"""
        ))
        assertEquals("{\"s\":\"Q\"}",ExecuTorchInferenceService.seedCharacterCreationJson("\"s\":\"Q\"}"))
        assertEquals("{\"s\":\"Q\"}",ExecuTorchInferenceService.seedCharacterCreationJson("Q\"}"))
        assertEquals("{\"s\":\"Q\"}",ExecuTorchInferenceService.seedCharacterCreationJson("\"Q\"}"))
    }

    @Test
    fun compactLocalDraftIsMaterializedOnlyFromTheAuthoritativeCatalog() {
        fun option(kind:CharacterCreationDefinitionKind,uid:String,name:String,dimension:String?=null)=
            CharacterCreationDefinitionOption(kind,uid,name,0.0,100.0,dimension)
        val full=CharacterCreationCatalog("C",listOf(
            option(CharacterCreationDefinitionKind.STAT,"STAT-POWER","Power"),
            option(CharacterCreationDefinitionKind.STAT,"STAT-AGILITY","Agility"),
            option(CharacterCreationDefinitionKind.RESOURCE,"RES-CHAKRA","Chakra"),
            option(CharacterCreationDefinitionKind.TALENT,"DOMAIN-NINJA","Ninja"),
            option(CharacterCreationDefinitionKind.POTENTIAL,"DOMAIN-NINJA","Ninja maximum","MAXIMUM"),
            option(CharacterCreationDefinitionKind.SKILL,"SK-STEALTH","Skradanie"),
            option(CharacterCreationDefinitionKind.SKILL,"SK-OTHER","Inna umiejętność"),
            option(CharacterCreationDefinitionKind.TECHNIQUE,"TECH-CLONE","Technika klonów"),
            option(CharacterCreationDefinitionKind.ORIGIN,"VIL-KONOHA","Konohagakure"),
            option(CharacterCreationDefinitionKind.INNATE_FEATURE,"KG-EYES","Dziedziczne oczy"),
            option(CharacterCreationDefinitionKind.STARTING_LOCATION,"LOC-ACADEMY","Akademia Konohy"),
        ))
        val projected=CharacterCreationCatalog("C",listOf(
            full.options.first{it.definitionUid=="STAT-POWER"},full.options.first{it.definitionUid=="RES-CHAKRA"},
            full.options.first{it.definitionUid=="DOMAIN-NINJA"&&it.kind==CharacterCreationDefinitionKind.TALENT},
            full.options.first{it.kind==CharacterCreationDefinitionKind.POTENTIAL},full.options.first{it.definitionUid=="SK-STEALTH"},
            full.options.first{it.definitionUid=="TECH-CLONE"},full.options.first{it.definitionUid=="VIL-KONOHA"},
            full.options.first{it.definitionUid=="LOC-ACADEMY"},
        ))
        val request=AiCharacterCreationRequest(
            "REQ","C",projected,listOf(CharacterCreationConversationEntry(
                CharacterCreationConversationRole.PLAYER,"Smagi. Chlopiec. 12 lat. Z Konohy. Uczen Akademii. Klony i skradanie."
            )),authorityCatalog=full
        )

        val result=LocalCompactAiJsonCodec().decodeCharacterCreation(
            """{"s":"R","n":"Smagi","g":"MALE","i":{"AGE":"12","ROLE":"ACADEMY_STUDENT"},"pick":["SK-STEALTH","TECH-CLONE","VIL-KONOHA","LOC-ACADEMY"],"sum":"Smagi rozpoczyna naukę w Akademii Konohy."}""",
            request
        ) as CharacterCreationGmCandidate.ReadyForConfirmation

        assertEquals(setOf("STAT-POWER","STAT-AGILITY"),result.draft.stats.map{it.definitionUid}.toSet())
        assertTrue(result.draft.stats.all{it.value>0.0})
        assertEquals(listOf("SK-STEALTH"),result.draft.skills.map{it.definitionUid})
        assertEquals(listOf("TECH-CLONE"),result.draft.techniques.map{it.definitionUid})
        assertEquals(listOf("VIL-KONOHA"),result.draft.originUids)
        assertEquals("LOC-ACADEMY",result.draft.startingLocationUid)
        assertTrue(result.draft.innateFeatureUids.isEmpty())
    }

    @Test
    fun explicitLatestAcademyRequestOverridesBadModelRoleAndEarlierCatalogQuestion() {
        fun option(kind:CharacterCreationDefinitionKind,uid:String,name:String,dimension:String?=null)=
            CharacterCreationDefinitionOption(kind,uid,name,0.0,100.0,dimension)
        val catalog=CharacterCreationCatalog("C",listOf(
            option(CharacterCreationDefinitionKind.STAT,"STAT","Siła"),option(CharacterCreationDefinitionKind.RESOURCE,"RES","Chakra"),
            option(CharacterCreationDefinitionKind.TALENT,"TAL","Ninja"),option(CharacterCreationDefinitionKind.POTENTIAL,"POT","Potencjał","MAXIMUM"),
            option(CharacterCreationDefinitionKind.SKILL,"SK-CHAKRA","Kontrola chakry"),
            option(CharacterCreationDefinitionKind.TECHNIQUE,"TECH-CLONE","Technika klonów"),
            option(CharacterCreationDefinitionKind.ORIGIN,"VIL-KONOHA","Konohagakure"),
            option(CharacterCreationDefinitionKind.INNATE_FEATURE,"KG-SHARINGAN","Sharingan"),
            option(CharacterCreationDefinitionKind.STARTING_LOCATION,"LOC-ACADEMY","Akademia Konohy"),
        ))
        val request=AiCharacterCreationRequest("REQ","C",catalog,listOf(
            CharacterCreationConversationEntry(CharacterCreationConversationRole.PLAYER,"Jakie klany i kekkei genkai są dostępne?"),
            CharacterCreationConversationEntry(CharacterCreationConversationRole.GAME_MASTER,"Lista legalnych opcji."),
            CharacterCreationConversationEntry(CharacterCreationConversationRole.PLAYER,"Jestem Smagi, chłopiec 12 lat z Konohy. Chcę być uczniem Akademii w epoce Naruto."),
        ),authorityCatalog=catalog)

        val result=LocalCompactAiJsonCodec().decodeCharacterCreation(
            """{"s":"R","n":"Smagi","g":"FEMALE","i":{"ROLE":"chlopiec"},"pick":["1"],"sum":"Nieaktualne podsumowanie."}""",
            request
        ) as CharacterCreationGmCandidate.ReadyForConfirmation

        assertEquals("ACADEMY_STUDENT",result.draft.identityChoices["ROLE"])
        assertEquals("MALE",result.draft.genderUid)
        assertEquals("NARUTO",result.draft.identityChoices["ERA"])
        assertTrue(result.draft.innateFeatureUids.isEmpty())
        assertTrue(result.playerFacingSummary.contains("uczeń Akademii"))
        assertTrue(result.playerFacingSummary.contains("Kontrola chakry"))
        assertTrue(!result.playerFacingSummary.contains("Nieaktualne"))
    }

    @Test
    fun tokenCappedCompactDraftDropsMalformedTailAndMaterializesOnlyCoreLegalChoices() {
        fun option(kind:CharacterCreationDefinitionKind,uid:String,name:String,dimension:String?=null)=
            CharacterCreationDefinitionOption(kind,uid,name,0.0,100.0,dimension)
        val catalog=CharacterCreationCatalog("C",listOf(
            option(CharacterCreationDefinitionKind.STAT,"STAT","Siła"),option(CharacterCreationDefinitionKind.RESOURCE,"RES","Chakra"),
            option(CharacterCreationDefinitionKind.TALENT,"TAL","Ninja"),option(CharacterCreationDefinitionKind.POTENTIAL,"POT","Potencjał","MAXIMUM"),
            option(CharacterCreationDefinitionKind.SKILL,"SK-CHAKRA","Kontrola chakry"),
            option(CharacterCreationDefinitionKind.TECHNIQUE,"TECH-CLONE","Technika klonów"),
            option(CharacterCreationDefinitionKind.ORIGIN,"VIL-KONOHA","Konohagakure"),
            option(CharacterCreationDefinitionKind.STARTING_LOCATION,"LOC-ACADEMY","Akademia Konohy"),
        ))
        val request=AiCharacterCreationRequest("REQ","C",catalog,listOf(
            CharacterCreationConversationEntry(CharacterCreationConversationRole.PLAYER,"Jestem Smagi, chłopiec 12 lat z Konohy, uczeń Akademii w epoce Naruto."),
        ))
        val observedPartial="""{"s":"R","n":"Smagi","g":"MALE","i":{"AGE":"12","ROLE":"chlopiec"},"pick":["111111111111111111111111"""

        val result=LocalCompactAiJsonCodec().decodeCharacterCreation(observedPartial,request) as CharacterCreationGmCandidate.ReadyForConfirmation

        assertEquals("Smagi",result.draft.displayName)
        assertEquals("ACADEMY_STUDENT",result.draft.identityChoices["ROLE"])
        assertEquals(listOf("SK-CHAKRA"),result.draft.skills.map{it.definitionUid})
        assertEquals(listOf("TECH-CLONE"),result.draft.techniques.map{it.definitionUid})
        assertTrue(result.playerFacingSummary.contains("Akademia Konohy"))
    }

    @Test
    fun unavailableRequestedSkillIsReportedInsteadOfSilentlySubstituted() {
        fun option(kind:CharacterCreationDefinitionKind,uid:String,name:String,dimension:String?=null)=
            CharacterCreationDefinitionOption(kind,uid,name,0.0,100.0,dimension)
        val catalog=CharacterCreationCatalog("C",listOf(
            option(CharacterCreationDefinitionKind.STAT,"STAT","Siła"),option(CharacterCreationDefinitionKind.RESOURCE,"RES","Chakra"),
            option(CharacterCreationDefinitionKind.TALENT,"TAL","Ninja"),option(CharacterCreationDefinitionKind.POTENTIAL,"POT","Potencjał","MAXIMUM"),
            option(CharacterCreationDefinitionKind.SKILL,"SK-STEALTH","Skradanie"),option(CharacterCreationDefinitionKind.SKILL,"SK-TAI","Taijutsu"),
            option(CharacterCreationDefinitionKind.SKILL,"SK-KEN","Kenjutsu"),option(CharacterCreationDefinitionKind.TECHNIQUE,"TECH-CLONE","Academy Clone Technique"),
            option(CharacterCreationDefinitionKind.STARTING_LOCATION,"LOC-KONOHA","Konohagakure"),
        ))
        val request=AiCharacterCreationRequest("REQ","C",catalog,listOf(
            CharacterCreationConversationEntry(CharacterCreationConversationRole.PLAYER,"Jestem Smagi: kontrola chakry, skradanie i walka wręcz, technika klonów."),
        ))

        val result=LocalCompactAiJsonCodec().decodeCharacterCreation(
            """{"s":"R","n":"Smagi","g":"MALE","i":{},"pick":["1"],"sum":"Szkic."}""",request
        ) as CharacterCreationGmCandidate.ReadyForConfirmation

        assertEquals(setOf("SK-STEALTH","SK-TAI"),result.draft.skills.map{it.definitionUid}.toSet())
        assertTrue("SK-KEN" !in result.draft.skills.map{it.definitionUid})
        assertTrue(result.playerFacingSummary.contains("Brak legalnej definicji"))
        assertTrue(result.playerFacingSummary.contains("kontrola chakry"))
    }

    @Test
    fun randomCharacterCommandUsesAStableAuditableCoreSeed() {
        fun option(kind:CharacterCreationDefinitionKind,uid:String,dimension:String?=null)=
            CharacterCreationDefinitionOption(kind,uid,uid,0.0,100.0,dimension)
        val catalog=CharacterCreationCatalog("C",listOf(
            option(CharacterCreationDefinitionKind.STAT,"STAT"),option(CharacterCreationDefinitionKind.RESOURCE,"RES"),
            option(CharacterCreationDefinitionKind.TALENT,"TAL"),option(CharacterCreationDefinitionKind.POTENTIAL,"POT","MAXIMUM"),
            option(CharacterCreationDefinitionKind.SKILL,"SK-1"),option(CharacterCreationDefinitionKind.SKILL,"SK-2"),
            option(CharacterCreationDefinitionKind.TECHNIQUE,"TECH-1"),option(CharacterCreationDefinitionKind.TECHNIQUE,"TECH-2"),
            option(CharacterCreationDefinitionKind.ORIGIN,"ORIGIN-1"),option(CharacterCreationDefinitionKind.INNATE_FEATURE,"KG-1"),
            option(CharacterCreationDefinitionKind.STARTING_LOCATION,"LOC-1"),
        ))
        val request=AiCharacterCreationRequest("REQ","C",catalog,listOf(
            CharacterCreationConversationEntry(CharacterCreationConversationRole.PLAYER,"Jestem Smagi, wylosuj dla mnie wszystko")
        ),authorityCatalog=catalog)
        val payload="""{"s":"R","n":"Smagi","g":"UNSPECIFIED","i":{},"pick":[],"sum":"Losowy szablon Smagiego czeka na potwierdzenie."}"""
        val first=LocalCompactAiJsonCodec().decodeCharacterCreation(payload,request) as CharacterCreationGmCandidate.ReadyForConfirmation
        val second=LocalCompactAiJsonCodec().decodeCharacterCreation(payload,request) as CharacterCreationGmCandidate.ReadyForConfirmation

        assertEquals(first.draft.skills,second.draft.skills)
        assertEquals(first.draft.techniques,second.draft.techniques)
        assertEquals(first.draft.identityChoices["RANDOM_SEED"],second.draft.identityChoices["RANDOM_SEED"])
        assertTrue(first.draft.stats.single().value>0.0)
        assertEquals(100.0,first.draft.resources.single().value,0.0)
        assertTrue(first.draft.talents.single().value>0.0)
        assertTrue(first.draft.potentials.single().value in 0.0..100.0)
        assertTrue(first.draft.innateFeatureUids.isNotEmpty())
    }

    @Test
    fun randomRemainingChoicesCannotOverrideExplicitOriginAndStartingPlace() {
        fun option(kind:CharacterCreationDefinitionKind,uid:String,name:String,dimension:String?=null)=
            CharacterCreationDefinitionOption(kind,uid,name,0.0,100.0,dimension)
        val catalog=CharacterCreationCatalog("C",listOf(
            option(CharacterCreationDefinitionKind.STAT,"STAT","Stat"),
            option(CharacterCreationDefinitionKind.RESOURCE,"RES","Resource"),
            option(CharacterCreationDefinitionKind.TALENT,"TAL","Talent"),
            option(CharacterCreationDefinitionKind.POTENTIAL,"POT","Potential","MAXIMUM"),
            option(CharacterCreationDefinitionKind.SKILL,"SK-GEN","Genjutsu"),
            option(CharacterCreationDefinitionKind.TECHNIQUE,"TECH-HYDRA","Hydrification Technique"),
            option(CharacterCreationDefinitionKind.ORIGIN,"ORIGIN-AME","Amegakure"),
            option(CharacterCreationDefinitionKind.ORIGIN,"ORIGIN-KONOHA","Konohagakure"),
            option(CharacterCreationDefinitionKind.STARTING_LOCATION,"LOC-SHIKKOTSU","Shikkotsu Forest"),
            option(CharacterCreationDefinitionKind.STARTING_LOCATION,"LOC-ACADEMY","Konoha Ninja Academy"),
            option(CharacterCreationDefinitionKind.STARTING_LOCATION,"LOC-BRIDGE","Great Naruto Bridge"),
        ))
        val text="Mam na imię Smagi. Chcę być uczniem Akademii w Konohie na początku ery Naruto. Wylosuj pozostałe cechy."
        val request=AiCharacterCreationRequest("REQ","C",catalog,listOf(
            CharacterCreationConversationEntry(CharacterCreationConversationRole.PLAYER,text)
        ),authorityCatalog=catalog)

        val result=LocalCompactAiJsonCodec().decodeCharacterCreation(
            """{"s":"R","n":"Smagi","g":"MALE","i":{},"pick":["ORIGIN-AME","LOC-SHIKKOTSU"],"sum":"Losowy."}""",
            request
        ) as CharacterCreationGmCandidate.ReadyForConfirmation

        assertEquals(listOf("ORIGIN-KONOHA"),result.draft.originUids)
        assertEquals("LOC-ACADEMY",result.draft.startingLocationUid)
        assertEquals("Smagi",result.draft.displayName)
        assertEquals("ACADEMY_STUDENT",result.draft.identityChoices["ROLE"])
        assertEquals("NARUTO",result.draft.identityChoices["ERA"])
    }

    @Test
    fun randomCharacterWithoutANameNeverUsesTheCommandAsTheCharacterName() {
        fun option(kind:CharacterCreationDefinitionKind,uid:String,dimension:String?=null)=
            CharacterCreationDefinitionOption(kind,uid,uid,0.0,100.0,dimension)
        val catalog=CharacterCreationCatalog("C",listOf(
            option(CharacterCreationDefinitionKind.STAT,"STAT"),option(CharacterCreationDefinitionKind.RESOURCE,"RES"),
            option(CharacterCreationDefinitionKind.TALENT,"TAL"),option(CharacterCreationDefinitionKind.POTENTIAL,"POT","MAXIMUM"),
            option(CharacterCreationDefinitionKind.SKILL,"SK"),option(CharacterCreationDefinitionKind.TECHNIQUE,"TECH"),
            option(CharacterCreationDefinitionKind.STARTING_LOCATION,"LOC"),
        ))
        val request=AiCharacterCreationRequest("REQ","C",catalog,listOf(
            CharacterCreationConversationEntry(CharacterCreationConversationRole.PLAYER,"Wylosuj dla mnie wszystko")
        ))

        val result=LocalCompactAiJsonCodec().decodeCharacterCreation(
            """{"s":"R","n":"","g":"UNSPECIFIED","i":{},"pick":[],"sum":"Losowy szablon czeka na potwierdzenie."}""",
            request
        ) as CharacterCreationGmCandidate.ReadyForConfirmation

        assertEquals("Bohater",result.draft.displayName)
    }

    @Test
    fun portableCpuProfile_isTheGgufDefaultAndSupportsEveryEngineWorkload() {
        val profile = BielikLocalModelProfiles.USER_GGUF
        val settings = LocalRecommendedSettings.forProfile(profile)

        assertEquals(AiWorkload.entries.toSet(), profile.supportedWorkloads)
        assertEquals(LocalRuntimeEngine.LLAMA_CPP, settings.runtimeEngine)
        assertEquals(LocalRuntimeBackend.CPU, settings.backend)
        assertEquals(8_192, settings.contextUnits)
        assertEquals(0, settings.gpuLayers)
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
            backend = LocalRuntimeBackend.GPU,
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
