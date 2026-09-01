package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterCreationLocalBudgetTest {
    @Test fun execuTorchCharacterOutputStopsAtFirstCompleteJsonObject(){
        val expected="""{"s":"Q","q":"Jaki styl walki wybierasz?","m":["styl"]}"""
        val raw="wstęp $expected<|im_end|> nieużywany ogon"

        assertEquals(expected,ExecuTorchInferenceService.bielikStructuredOutput(raw))
        assertEquals(expected,ExecuTorchInferenceService.completeJsonObjectOrNull(raw))
        assertEquals(null,ExecuTorchInferenceService.completeJsonObjectOrNull("prefix {\"s\":\"Q\""))
    }

    @Test fun largeWorldPackCatalogIsProjectedIntoMobileContextWithoutDroppingLegalFamilies(){
        val complete=listOf(
            option(CharacterCreationDefinitionKind.STAT,"STAT-STRENGTH","Siła"),
            option(CharacterCreationDefinitionKind.RESOURCE,"RESOURCE-HEALTH","Zdrowie"),
            option(CharacterCreationDefinitionKind.TALENT,"TALENT-BASE","Talent"),
            option(CharacterCreationDefinitionKind.POTENTIAL,"POTENTIAL-BASE","Potencjał",dimension="POWER")
        )
        val skills=(1..120).map{index->option(CharacterCreationDefinitionKind.SKILL,"SKILL-$index",if(index==77)"Kontrola ognia" else "Umiejętność $index")}
        val techniques=(1..120).map{index->option(CharacterCreationDefinitionKind.TECHNIQUE,"TECHNIQUE-$index",if(index==91)"Kula ognia" else "Technika $index")}
        val choices=listOf(
            option(CharacterCreationDefinitionKind.ORIGIN,"ORIGIN-1","Wioska Liścia"),
            option(CharacterCreationDefinitionKind.INNATE_FEATURE,"INNATE-1","Silna wola"),
            option(CharacterCreationDefinitionKind.STARTING_LOCATION,"LOCATION-1","Konoha")
        )
        val conversation=listOf(CharacterCreationConversationEntry(CharacterCreationConversationRole.PLAYER,"Chcę kontrolować ogień i używać kuli ognia."))

        val projected=CharacterCreationCatalog("C",complete+skills+techniques+choices).projectForAi(conversation)

        assertTrue(projected.estimatedInputUnits(conversation)<=900)
        assertTrue(complete.map{it.kind}.all{kind->projected.options.any{it.kind==kind}})
        assertTrue(projected.options.any{it.definitionUid=="SKILL-77"})
        assertTrue(projected.options.any{it.definitionUid=="TECHNIQUE-91"})
        assertEquals(CharacterCreationDefinitionKind.entries.toSet(),projected.options.map{it.kind}.toSet())
    }

    @Test fun multiTurnConversationRemainsInsideTheRealBielikMobileLimit(){
        val catalog=CharacterCreationCatalog("C",listOf(
            option(CharacterCreationDefinitionKind.STAT,"STAT","Statystyka"),
            option(CharacterCreationDefinitionKind.RESOURCE,"RESOURCE","Zasób"),
            option(CharacterCreationDefinitionKind.TALENT,"TALENT","Talent"),
            option(CharacterCreationDefinitionKind.POTENTIAL,"POTENTIAL","Potencjał",dimension="POWER"),
            option(CharacterCreationDefinitionKind.SKILL,"SKILL","Umiejętność"),
            option(CharacterCreationDefinitionKind.TECHNIQUE,"TECHNIQUE","Technika"),
            option(CharacterCreationDefinitionKind.STARTING_LOCATION,"LOCATION","Lokacja")
        ))
        val full=(0 until 128).map{index->CharacterCreationConversationEntry(
            if(index%2==0)CharacterCreationConversationRole.PLAYER else CharacterCreationConversationRole.GAME_MASTER,
            "wiadomość-$index "+"x".repeat(1_000)
        )}
        val projectedConversation=full.projectForAi()
        val projectedCatalog=catalog.projectForAi(projectedConversation)
        assertEquals("wiadomość-0",projectedConversation.first().text.substringBefore(' '))
        assertEquals("wiadomość-127",projectedConversation.last().text.substringBefore(' '))
        assertTrue(projectedCatalog.estimatedInputUnits(projectedConversation)<=900)
        assertTrue(projectedCatalog.estimatedInputUnits(projectedConversation)<BielikLocalModelProfiles.BIELIK_1_5B_V3_EXECUTORCH.maximumContextUnits)
    }

    @Test fun realMobileCapabilityRoutesAutoAndPinnedAndExplainsOversizeRejection(){
        val profile=BielikLocalModelProfiles.BIELIK_1_5B_V3_EXECUTORCH
        val provider=DeterministicAiProvider(
            AiCapabilityContract("LOCAL-TEST","LOCAL:ANDROID_EXECUTORCH_1_3",profile.modelUid,profile.supportedWorkloads,
                maximumContextUnits=profile.maximumContextUnits,providerKind=AiProviderKind.LOCAL),
            intentFunction={error("unused")},proposalFunction={error("unused")},narrativeFunction={error("unused")}
        )
        val registry=AiProviderRegistry.fromCompositionRoot(listOf(provider))
        val ready=AiAvailabilityPort{AiProviderAvailability(
            AiModelSelection(provider.capabilities.providerUid,provider.capabilities.modelUid),AiAvailabilityState.READY,"READY"
        )}
        fun router(gm:AiRoleAssignment)=RoleAwareModelRouter(registry,listOf(gm,AiRoleAssignment(AiRole.DIRECTOR_SCENARIST)),AiPrivacyPolicy(),ready)
        assertTrue(router(AiRoleAssignment(AiRole.GAME_MASTER)).route(AiRole.GAME_MASTER,AiWorkload.CHARACTER_CREATION,1_250) is AiRouteResult.Selected)
        val selection=AiModelSelection(provider.capabilities.providerUid,provider.capabilities.modelUid)
        assertTrue(router(AiRoleAssignment(AiRole.GAME_MASTER,AiAssignmentKind.PINNED,selection)).route(AiRole.GAME_MASTER,AiWorkload.CHARACTER_CREATION,1_250) is AiRouteResult.Selected)
        val rejected=router(AiRoleAssignment(AiRole.GAME_MASTER)).route(AiRole.GAME_MASTER,AiWorkload.CHARACTER_CREATION,2_049) as AiRouteResult.Unavailable
        assertTrue(rejected.reasonUids.any{"CONTEXT_LIMIT_EXCEEDED:required=2049:maximum=2048" in it})
    }

    @Test fun potentialProjectionKeepsOneMaximumChoicePerDomainAndCompactWirePayload(){
        val fixed=listOf(
            *(1..8).map{option(CharacterCreationDefinitionKind.STAT,"STAT-$it","Statystyka $it")}.toTypedArray(),
            *(1..4).map{option(CharacterCreationDefinitionKind.RESOURCE,"RESOURCE-$it","Zasób $it")}.toTypedArray(),
            *(1..4).map{option(CharacterCreationDefinitionKind.TALENT,"DOMAIN-$it","Talent $it")}.toTypedArray()
        )
        val potentials=(1..4).flatMap{domain->listOf("GROWTH","MAXIMUM","ADAPTATION","INNOVATION","EVOLUTION").map{dimension->
            option(CharacterCreationDefinitionKind.POTENTIAL,"DOMAIN-$domain","Potencjał $domain / $dimension",dimension)
        }}
        val choices=listOf(
            option(CharacterCreationDefinitionKind.SKILL,"SKILL-1","Kontrola chakry"),
            option(CharacterCreationDefinitionKind.TECHNIQUE,"TECHNIQUE-1","Technika klonów"),
            option(CharacterCreationDefinitionKind.ORIGIN,"ORIGIN-1","Wioska Liścia"),
            option(CharacterCreationDefinitionKind.INNATE_FEATURE,"INNATE-1","Silna wola"),
            option(CharacterCreationDefinitionKind.STARTING_LOCATION,"LOCATION-1","Konoha")
        )
        val conversation=listOf(CharacterCreationConversationEntry(CharacterCreationConversationRole.PLAYER,"Chcę być zwiadowcą z Konohy."))
        val projected=CharacterCreationCatalog("C",fixed+potentials+choices).projectForAi(conversation)
        val projectedPotentials=projected.options.filter{it.kind==CharacterCreationDefinitionKind.POTENTIAL}
        assertEquals(1,projectedPotentials.size)
        assertTrue(projectedPotentials.all{it.dimensionUid=="MAXIMUM"})
        assertTrue(projected.estimatedInputUnits(conversation)<=900)
    }

    @Test fun inflectedKonohaOutranksIncidentalNarutoLocationMatch(){
        val konoha=option(CharacterCreationDefinitionKind.STARTING_LOCATION,"LOC-KONOHA","Konoha")
        val bridge=option(CharacterCreationDefinitionKind.STARTING_LOCATION,"LOC-NARUTO-BRIDGE","Great Naruto Bridge")

        val projected=CharacterCreationCatalog("C",listOf(konoha,bridge)).projectForAi(listOf(
            CharacterCreationConversationEntry(CharacterCreationConversationRole.PLAYER,"Uczeń z Konohy w epoce Naruto")
        ))

        assertEquals("LOC-KONOHA",projected.options.first().definitionUid)
    }

    @Test fun explicitAcademyCloneAndSkillAliasesOutrankBroadSemanticOrdering(){
        val catalog=CharacterCreationCatalog("C",listOf(
            option(CharacterCreationDefinitionKind.SKILL,"SK-CHAKRA-SCALPEL","Chakra Scalpel"),
            option(CharacterCreationDefinitionKind.SKILL,"SK-CHAKRA-CONTROL","Chakra Control"),
            option(CharacterCreationDefinitionKind.SKILL,"SK-STEALTH","Stealth"),
            option(CharacterCreationDefinitionKind.SKILL,"SK-TAIJUTSU","Taijutsu"),
            option(CharacterCreationDefinitionKind.TECHNIQUE,"TECH-SCALPEL","Chakra Scalpel"),
            option(CharacterCreationDefinitionKind.TECHNIQUE,"TECH-CLONE","Basic Clone Technique"),
            option(CharacterCreationDefinitionKind.STARTING_LOCATION,"LOC-BRIDGE","Great Naruto Bridge"),
            option(CharacterCreationDefinitionKind.STARTING_LOCATION,"LOC-ACADEMY","Konoha Ninja Academy")
        ))
        val conversation=listOf(CharacterCreationConversationEntry(
            CharacterCreationConversationRole.PLAYER,
            "Uczeń Akademii Konohy w epoce Naruto: kontrola chakry, klony, skradanie i walka wręcz."
        ))
        val misleadingSemanticOrder=listOf(
            semanticWorldPackRecordUid(catalog.options.first{it.definitionUid=="TECH-SCALPEL"}),
            semanticWorldPackRecordUid(catalog.options.first{it.definitionUid=="LOC-BRIDGE"})
        )

        val projected=catalog.projectForAi(conversation,5_000,misleadingSemanticOrder,8)

        assertEquals("TECH-CLONE",projected.options.first{it.kind==CharacterCreationDefinitionKind.TECHNIQUE}.definitionUid)
        assertEquals("LOC-ACADEMY",projected.options.first{it.kind==CharacterCreationDefinitionKind.STARTING_LOCATION}.definitionUid)
        assertEquals(
            listOf("SK-CHAKRA-CONTROL","SK-STEALTH","SK-TAIJUTSU"),
            projected.options.filter{it.kind==CharacterCreationDefinitionKind.SKILL}.take(3).map{it.definitionUid}.toSet().sorted()
        )
    }

    @Test fun catalogQuestionsAreAnsweredFromLegalProjectedNamesWithoutGenerativeGuessing(){
        val catalog=CharacterCreationCatalog("C",listOf(
            option(CharacterCreationDefinitionKind.ORIGIN,"ORIGIN-UCHIHA","Klan Uchiha"),
            option(CharacterCreationDefinitionKind.ORIGIN,"ORIGIN-NARA","Klan Nara"),
            option(CharacterCreationDefinitionKind.INNATE_FEATURE,"KG-SHARINGAN","Sharingan"),
            option(CharacterCreationDefinitionKind.TECHNIQUE,"TECH-CLONE","Technika Klonów")
        ))

        val answer=catalog.answerCatalogQuestion("Jakie klany i kekkei genkai są dostępne?")

        assertTrue(answer!!.contains("Klan Uchiha"))
        assertTrue(answer.contains("Klan Nara"))
        assertTrue(answer.contains("Sharingan"))
        assertTrue(!answer.contains("Technika Klonów"))
        assertEquals(null,catalog.answerCatalogQuestion("Chcę być uczniem Akademii."))
    }

    @Test fun lockedDraftSectionsSurviveManualChangesAndRerollsWhileUnlockedSectionsChange(){
        fun value(uid:String)=CharacterCreationValueChoice(uid,1.0)
        fun draft(name:String,origin:String,skill:String,location:String)=PlayerCharacterCreationDraft(
            creationUid="CREATION-1",campaignUid="C",playerUid="PLAYER-1",displayName=name,genderUid="MALE",
            identityChoices=mapOf("ROLE" to "ACADEMY_STUDENT"),stats=listOf(value("STAT")),resources=listOf(value("RES")),
            talents=listOf(value("TAL")),potentials=listOf(CharacterCreationValueChoice("POT",50.0,"MAXIMUM")),
            skills=listOf(value(skill)),techniques=listOf(value("TECH")),originUids=listOf(origin),startingLocationUid=location
        )
        val previous=draft("Smagi","KONOHA","STEALTH","ACADEMY")
        val proposed=draft("Inne imię","NARA","CHAKRA","FOREST").copy(creationUid="NEW-CREATION",playerUid="NEW-PLAYER")

        val merged=proposed.preserveLockedSections(previous,setOf(
            CharacterCreationDraftSection.IDENTITY,CharacterCreationDraftSection.SKILLS
        ))

        assertEquals("CREATION-1",merged.creationUid)
        assertEquals("PLAYER-1",merged.playerUid)
        assertEquals("Smagi",merged.displayName)
        assertEquals("STEALTH",merged.skills.single().definitionUid)
        assertEquals(listOf("NARA"),merged.originUids)
        assertEquals("FOREST",merged.startingLocationUid)
    }

    @Test fun manualTextTargetsOnlyMentionedDraftSectionsWhileRerollCanTargetEveryUnlockedSection(){
        assertEquals(
            setOf(CharacterCreationDraftSection.TECHNIQUES),
            "Zmień tylko technikę na Bunshin no Jutsu".characterCreationRequestedSections()
        )
        assertEquals(
            setOf(CharacterCreationDraftSection.ORIGIN,CharacterCreationDraftSection.INNATE_FEATURES),
            "Chcę pochodzić z klanu Uchiha i mieć Sharingan".characterCreationRequestedSections()
        )
        assertEquals(CharacterCreationDraftSection.entries.toSet(),"Zmień wszystko od nowa".characterCreationRequestedSections())
        assertTrue("Przerzuć odblokowane elementy".isCharacterCreationReroll())
    }

    @Test fun exactLegalManualEditBypassesGenerationAndPreservesLockedIdentity(){
        fun value(uid:String)=CharacterCreationValueChoice(uid,10.0)
        val draft=PlayerCharacterCreationDraft(
            creationUid="CREATION-1",campaignUid="C",playerUid="PLAYER-1",displayName="Smagi",genderUid="MALE",
            identityChoices=mapOf("AGE" to "12","ROLE" to "ACADEMY_STUDENT"),
            stats=listOf(value("STAT")),resources=listOf(value("RES")),talents=listOf(value("TAL")),
            potentials=listOf(CharacterCreationValueChoice("POT",50.0,"MAXIMUM")),skills=listOf(value("STEALTH")),
            techniques=listOf(value("OLD-TECH")),originUids=listOf("KONOHA"),startingLocationUid="ACADEMY"
        )
        val catalog=CharacterCreationCatalog("C",listOf(
            option(CharacterCreationDefinitionKind.TECHNIQUE,"OLD-TECH","Substitution Technique"),
            option(CharacterCreationDefinitionKind.TECHNIQUE,"ACADEMY-CLONE","Academy Clone Technique")
        ))

        val edited=draft.applyExplicitLegalEdit(
            "Zmień tylko technikę na Academy Clone Technique",catalog,setOf(CharacterCreationDraftSection.IDENTITY)
        )!!

        assertEquals("Smagi",edited.displayName)
        assertEquals(mapOf("AGE" to "12","ROLE" to "ACADEMY_STUDENT"),edited.identityChoices)
        assertEquals("ACADEMY-CLONE",edited.techniques.single().definitionUid)
        assertEquals(draft.skills,edited.skills)
        assertEquals(draft.originUids,edited.originUids)
    }

    @Test fun canonicalRandomAllNeverAsksAgainAndCoreMaterializesCompleteNonMinimumDraft(){
        val catalog=CharacterCreationCatalog("C",listOf(
            option(CharacterCreationDefinitionKind.STAT,"STAT-POWER","Power"),
            option(CharacterCreationDefinitionKind.STAT,"STAT-AGILITY","Agility"),
            option(CharacterCreationDefinitionKind.RESOURCE,"RES-HEALTH","Health"),
            option(CharacterCreationDefinitionKind.TALENT,"TAL-PHYSICAL","Physical"),
            option(CharacterCreationDefinitionKind.POTENTIAL,"TAL-PHYSICAL","Physical potential","MAXIMUM"),
            option(CharacterCreationDefinitionKind.SKILL,"SK-TAIJUTSU","Taijutsu"),
            option(CharacterCreationDefinitionKind.TECHNIQUE,"TECH-CLONE","Academy Clone"),
            option(CharacterCreationDefinitionKind.ORIGIN,"VIL-KONOHA","Konohagakure"),
            option(CharacterCreationDefinitionKind.INNATE_FEATURE,"KG-EYES","Inherited eyes"),
            option(CharacterCreationDefinitionKind.STARTING_LOCATION,"LOC-ACADEMY","Academy")
        ))
        val request=AiCharacterCreationRequest(
            "REQ","C",catalog,listOf(CharacterCreationConversationEntry(
                CharacterCreationConversationRole.PLAYER,
                "Jestem Smagi, mam 12 lat i jestem zwykłym uczniem Akademii w epoce Naruto. Wylosuj dla mnie wszystko."
            ))
        )
        val codec=CanonicalAiJsonCodec()

        val wire=org.json.JSONObject(codec.encodeCharacterCreation(request))
        assertEquals("RANDOM",wire.getString("interaction_mode"))
        assertTrue(wire.getJSONArray("requirements").toString().contains("without asking another question"))

        val candidate=codec.decodeCharacterCreation(
            """{"state":"NEEDS_PLAYER_CHOICE","question":"Jaką płeć ma Smagi?","missing_category_uids":["GENDER"]}""",
            request
        ) as CharacterCreationGmCandidate.ReadyForConfirmation

        assertEquals("Smagi",candidate.draft.displayName)
        assertEquals("12",candidate.draft.identityChoices["AGE"])
        assertEquals("ACADEMY_STUDENT",candidate.draft.identityChoices["ROLE"])
        assertEquals("NARUTO",candidate.draft.identityChoices["ERA"])
        assertTrue(candidate.draft.genderUid!="UNSPECIFIED")
        assertEquals(setOf("STAT-POWER","STAT-AGILITY"),candidate.draft.stats.map{it.definitionUid}.toSet())
        assertTrue(candidate.draft.stats.all{it.value in 15.0..40.0})
        assertEquals(100.0,candidate.draft.resources.single().value,0.0)
        assertTrue(candidate.draft.skills.single().value in 5.0..30.0)
        assertTrue(candidate.draft.techniques.single().value in 5.0..30.0)
        assertTrue(candidate.draft.innateFeatureUids.isEmpty())
    }

    @Test fun canonicalRandomReadyTreatsModelIdsAndLocalizedGenderAsNonAuthoritativeHints(){
        val catalog=CharacterCreationCatalog("C",listOf(
            option(CharacterCreationDefinitionKind.STAT,"STAT-POWER","Power"),
            option(CharacterCreationDefinitionKind.RESOURCE,"RES-HEALTH","Health"),
            option(CharacterCreationDefinitionKind.TALENT,"TAL-PHYSICAL","Physical"),
            option(CharacterCreationDefinitionKind.POTENTIAL,"TAL-PHYSICAL","Physical potential","MAXIMUM"),
            option(CharacterCreationDefinitionKind.SKILL,"SK-TAIJUTSU","Taijutsu"),
            option(CharacterCreationDefinitionKind.TECHNIQUE,"TECH-CLONE","Academy Clone"),
            option(CharacterCreationDefinitionKind.ORIGIN,"VIL-KONOHA","Konohagakure"),
            option(CharacterCreationDefinitionKind.INNATE_FEATURE,"KG-EYES","Inherited eyes"),
            option(CharacterCreationDefinitionKind.STARTING_LOCATION,"VIL-KONOHA","Konohagakure")
        ))
        val request=AiCharacterCreationRequest("REQ","C",catalog,listOf(CharacterCreationConversationEntry(
            CharacterCreationConversationRole.PLAYER,
            "Jestem Smagi, mam 12 lat. Chcę być uczniem Akademii w epoce Naruto. Wylosuj pozostałe elementy."
        )))
        val payload="""{"state":"READY_FOR_CONFIRMATION","question":null,"missing_category_uids":[],"draft":{"creation_uid":"REQ","campaign_uid":"C","player_uid":"","display_name":"Smagi","gender_uid":"MĘSKA","identity_choices":{"AGE":"12 lat","ROLE":"Uczeń Akademii","ERA":"Początek ery Naruto"},"stats":[],"resources":[],"talents":[],"potentials":[],"skills":[{"definition_uid":"SK-TAIJUTSU","value":99}],"techniques":[{"definition_uid":"TECH-CLONE","value":99}],"origin_uids":["VIL-KONOHA"],"innate_feature_uids":["KG-EYES"],"starting_location_uid":"VIL-KONOHA","starting_x_millimetres":0,"starting_y_millimetres":0},"player_facing_summary":"Szablon czeka na potwierdzenie."}"""

        val first=CanonicalAiJsonCodec().decodeCharacterCreation(payload,request) as CharacterCreationGmCandidate.ReadyForConfirmation
        val second=CanonicalAiJsonCodec().decodeCharacterCreation(payload,request) as CharacterCreationGmCandidate.ReadyForConfirmation

        assertEquals(first.draft.creationUid,second.draft.creationUid)
        assertEquals(first.draft.playerUid,second.draft.playerUid)
        assertTrue(first.draft.playerUid.startsWith("PLAYER:"))
        assertTrue(first.draft.genderUid in setOf("MALE","FEMALE","NON_BINARY"))
        assertEquals("12",first.draft.identityChoices["AGE"])
        assertEquals("ACADEMY_STUDENT",first.draft.identityChoices["ROLE"])
        assertEquals("NARUTO",first.draft.identityChoices["ERA"])
        assertTrue(first.draft.skills.single().value<99.0)
        assertTrue(first.draft.techniques.single().value<99.0)
    }

    private fun option(kind:CharacterCreationDefinitionKind,uid:String,name:String,dimension:String?=null)=
        CharacterCreationDefinitionOption(kind,uid,name,minimumValue=0.0,maximumValue=100.0,dimensionUid=dimension)
}
