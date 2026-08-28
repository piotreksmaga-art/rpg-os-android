package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterCreationLocalBudgetTest {
    @Test fun largeWorldPackCatalogIsProjectedIntoMobileContextWithoutDroppingCompleteFamilies(){
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
        assertTrue(projected.options.containsAll(complete))
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
        assertTrue(router(AiRoleAssignment(AiRole.GAME_MASTER)).route(AiRole.GAME_MASTER,AiWorkload.CHARACTER_CREATION,900) is AiRouteResult.Selected)
        val selection=AiModelSelection(provider.capabilities.providerUid,provider.capabilities.modelUid)
        assertTrue(router(AiRoleAssignment(AiRole.GAME_MASTER,AiAssignmentKind.PINNED,selection)).route(AiRole.GAME_MASTER,AiWorkload.CHARACTER_CREATION,900) is AiRouteResult.Selected)
        val rejected=router(AiRoleAssignment(AiRole.GAME_MASTER)).route(AiRole.GAME_MASTER,AiWorkload.CHARACTER_CREATION,2_049) as AiRouteResult.Unavailable
        assertTrue(rejected.reasonUids.any{"CONTEXT_LIMIT_EXCEEDED:required=2049:maximum=2048" in it})
    }

    private fun option(kind:CharacterCreationDefinitionKind,uid:String,name:String,dimension:String?=null)=
        CharacterCreationDefinitionOption(kind,uid,name,minimumValue=0.0,maximumValue=100.0,dimensionUid=dimension)
}
