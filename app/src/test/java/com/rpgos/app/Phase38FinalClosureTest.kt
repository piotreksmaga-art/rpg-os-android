package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Phase38FinalClosureTest {
    private val authority = VisibilityAuthorityService()
    private val campaign = "C1"
    private val player = VisibilityAudienceFactory.player(campaign)

    private fun env(purpose:String, level:DisclosureLevel=DisclosureLevel.DISCLOSE_FULL):VisibilityProjectionEnvelope =
        authority.envelope(player,PurposeContext(campaign,purpose)).reduceTo(level)

    private fun bundle(purpose:String, secret:String="SECRET"):ContextBundle = ContextBundle(
        playerStatus=emptyMap(), scene=mapOf("world_presentation" to "generic disclosed world"), time=emptyMap(),
        activeThreads=emptyList(), relevantNpcs=emptyList(), npcKnowledge=emptyList(), missions=emptyList(),
        worldPressures=emptyList(), canonConstraints=emptyList(), recentChronicle=emptyList(), retrievedLongTermMemory=emptyList(),
        campaignTruth=listOf(mapOf("gm_only" to secret)),
        visibilityEnvelope=env(purpose)
    )

    @Test fun sceneCharacterAndLocationPromptsCannotMineHiddenContext(){
        val scene=VisualPromptBuilder().buildScenePrompt("look",bundle(VisibilityPurposeKinds.SCENE_VISUALIZATION,"SCENE_SECRET"))
        val character=VisualPromptBuilder().buildCharacterPrompt("A",listOf("visible"),emptyList(),"public note",bundle(VisibilityPurposeKinds.CHARACTER_VISUALIZATION,"MEM_PRIVATE"))
        val location=VisualPromptBuilder().buildLocationPrompt("L","public place","era",bundle(VisibilityPurposeKinds.LOCATION_VISUALIZATION,"LOCATION_SECRET"))
        assertFalse(scene.contains("SCENE_SECRET"));assertFalse(character.contains("MEM_PRIVATE"));assertFalse(location.contains("LOCATION_SECRET"))
    }

    @Test fun visualAuthorizationRejectsMissingWrongPurposeWrongCampaignDenyAndSubstitution(){
        val prompt="authorized prompt"
        val scene=Phase38VisualAuthorization.authorize(env(VisibilityPurposeKinds.SCENE_VISUALIZATION),VisibilityPurposeKinds.SCENE_VISUALIZATION,"SCENE","S",prompt,requestUid="REQ-1")
        scene.requireRequest(campaign,VisibilityPurposeKinds.SCENE_VISUALIZATION,prompt)
        assertTrue(runCatching{scene.requireRequest("C2",VisibilityPurposeKinds.SCENE_VISUALIZATION,prompt)}.isFailure)
        assertTrue(runCatching{scene.requireRequest(campaign,VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION,prompt)}.isFailure)
        assertTrue(runCatching{scene.requireRequest(campaign,VisibilityPurposeKinds.SCENE_VISUALIZATION,"substituted")}.isFailure)
        assertTrue(runCatching{Phase38VisualAuthorization.authorize(env(VisibilityPurposeKinds.GAMEPLAY_NARRATION),VisibilityPurposeKinds.SCENE_VISUALIZATION,"SCENE","S",prompt)}.isFailure)
        val denied=authority.envelope(player,PurposeContext(campaign,VisibilityPurposeKinds.SCENE_VISUALIZATION)).reduceTo(DisclosureLevel.DENY)
        assertTrue(runCatching{Phase38VisualAuthorization.authorize(denied,VisibilityPurposeKinds.SCENE_VISUALIZATION,"SCENE","S",prompt)}.isFailure)
        assertTrue(runCatching{Phase38VisualAuthorization.authorize(env(VisibilityPurposeKinds.SCENE_VISUALIZATION,DisclosureLevel.DISCLOSE_PARTIAL),VisibilityPurposeKinds.SCENE_VISUALIZATION,"SCENE","S",prompt,payloadDisclosure=DisclosureLevel.DISCLOSE_FULL)}.isFailure)
    }

    @Test fun sceneEnvelopeCannotBeReusedForEditAndEditCannotGainHiddenActor(){
        val instruction="brighten the disclosed foreground"
        val sourceBytes="source-image-v1".toByteArray()
        val sourceDigest=Phase38VisualAuthorization.digestBytes(sourceBytes)
        val edit=Phase38VisualAuthorization.authorize(
            env(VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION),VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION,
            "VISUAL","V1",instruction,VisualInputOrigins.USER_STANDALONE,requestUid="EDIT-1",
            sourceVisualUid="V1",sourceImageSha256=sourceDigest
        )
        val valid=VisualSemanticRequest(
            campaign,AudienceKinds.PLAYER,"HUMAN_PLAYER",VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION,
            "VISUAL","V1","EDIT-1",VisualRequestKinds.EDIT,instruction,
            sourceVisualUid="V1",sourceImageSha256=sourceDigest
        )
        edit.requireRequest(valid)
        assertTrue(runCatching{Phase38VisualAuthorization.authorize(env(VisibilityPurposeKinds.SCENE_VISUALIZATION),VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION,"VISUAL","V1",instruction,sourceVisualUid="V1",sourceImageSha256=sourceDigest)}.isFailure)
        assertTrue(runCatching{edit.requireRequest(valid.copy(sourceVisualUid="V2"))}.isFailure)
        assertTrue(runCatching{edit.requireRequest(valid.copy(sourceImageSha256=Phase38VisualAuthorization.digestBytes("source-image-v2".toByteArray())))}.isFailure)
        assertTrue(runCatching{edit.requireRequest(valid.copy(promptOrInstruction="darken the disclosed foreground"))}.isFailure)
        val editClient=source("app/src/main/java/com/rpgos/app/ImageEditBackendClient.kt")
        assertFalse(editClient.contains("CampaignTruth"));assertFalse(editClient.contains("gm_summary"));assertFalse(editClient.contains("npc_memories"))
    }

    @Test fun imageRequestsStructurallyRequireAuthorization(){
        assertTrue(ImageGenerationRequest::class.java.declaredConstructors.any { ctor -> ctor.parameterTypes.any { t -> t == Phase38VisualAuthorization::class.java } })
        assertTrue(ImageEditRequest::class.java.declaredConstructors.any { ctor -> ctor.parameterTypes.any { t -> t == Phase38VisualAuthorization::class.java } })
    }

    @Test fun backendAndLocalUseSameVisualAuthoritySemantics(){
        val backend=source("backend/app.py")
        listOf(
            VisibilityAuthorityService.AUTHORITY_UID,
            VisibilityAuthorityService.PROJECTION_VERSION_UID,
            "SCENE_VISUALIZATION","CHARACTER_VISUALIZATION","LOCATION_VISUALIZATION","IMAGE_EDIT_VISUALIZATION",
            "VISUAL_PAYLOAD_SUBSTITUTION","VISUAL_DISCLOSURE_ESCALATION","PROJECTION_DENIED","CROSS_CAMPAIGN_PROJECTION"
        ).forEach { assertTrue("backend missing $it",backend.contains(it)) }
        assertTrue(backend.contains("_require_visual_projection(req.visibility_envelope"))
        assertTrue(backend.contains("_require_visual_projection(visual_envelope"))
    }

    @Test fun playerAndPlayerCharacterAndTwoPcKnowledgeRemainIsolated(){
        val holderA=KnowledgeHolderRef(KnowledgeHolderKinds.PLAYER_CHARACTER,"PC-A",campaign)
        val holderB=KnowledgeHolderRef(KnowledgeHolderKinds.PLAYER_CHARACTER,"PC-B",campaign)
        val fixtureA=Phase38TrustedTestAuthority.playerCharacter(campaign,"PC-A")
        val fixtureB=Phase38TrustedTestAuthority.playerCharacter(campaign,"PC-B")
        val pcA=fixtureA.audience
        val pcB=fixtureB.audience
        val reasoning=PurposeContext(campaign,VisibilityPurposeKinds.WORLD_ACTOR_REASONING)
        val subA=VisibilitySubjectRef(campaign,VisibilitySubjectKinds.PHASE37_HOLDER_KNOWLEDGE,"PC-A",holder=holderA)
        val subB=VisibilitySubjectRef(campaign,VisibilitySubjectKinds.PHASE37_HOLDER_KNOWLEDGE,"PC-B",holder=holderB)
        assertNotEquals(AudienceKinds.PLAYER,pcA.audienceKindUid)
        assertEquals(DisclosureLevel.DISCLOSE_FULL,authority.decide(VisibilityRequest(pcA,reasoning,subA),fixtureA.trusted).level)
        assertEquals(DisclosureLevel.DENY,authority.decide(VisibilityRequest(pcA,reasoning,subB),fixtureA.trusted).level)
        assertEquals(DisclosureLevel.DISCLOSE_FULL,authority.decide(VisibilityRequest(pcB,reasoning,subB),fixtureB.trusted).level)
        assertEquals(DisclosureLevel.DENY,authority.decide(VisibilityRequest(pcB,reasoning,subA),fixtureB.trusted).level)
        assertEquals(DisclosureLevel.DENY,authority.decide(VisibilityRequest(player,reasoning,subA)).level)
        val c2Holder=KnowledgeHolderRef(KnowledgeHolderKinds.PLAYER_CHARACTER,"PC-A","C2")
        assertTrue(runCatching{VisibilityRequest(pcA,reasoning,VisibilitySubjectRef("C2",VisibilitySubjectKinds.PHASE37_HOLDER_KNOWLEDGE,"PC-A",holder=c2Holder))}.isFailure)
    }

    @Test fun diagnosticVisibilityDoesNotBecomePlayerVisibilityAndStrategicDisclosureDoesNotAcquireKnowledge(){
        val diagnosticFixture=Phase38TrustedTestAuthority.diagnostic(campaign)
        val diagnostic=diagnosticFixture.audience
        val diagPurpose=PurposeContext(campaign,VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)
        val truth=VisibilitySubjectRef(campaign,VisibilitySubjectKinds.CAMPAIGN_TRUTH,"T")
        val diagnosticRequest=VisibilityRequest(diagnostic,diagPurpose,truth)
        assertEquals(DisclosureLevel.DENY,authority.decide(diagnosticRequest).level)
        assertEquals(DisclosureLevel.DISCLOSE_FULL,authority.decide(diagnosticRequest,diagnosticFixture.trusted).level)
        val playerRequest=VisibilityRequest(player,PurposeContext(campaign,VisibilityPurposeKinds.PLAYER_UI),truth)
        assertEquals(DisclosureLevel.DENY,authority.decide(playerRequest).level)
        assertEquals(DisclosureLevel.DENY,authority.decide(playerRequest,diagnosticFixture.trusted).level)
    }

    @Test fun conservativeRelationshipPoliticsEconomyOrganizationAreNotImplicitlyPublic(){
        val ui=PurposeContext(campaign,VisibilityPurposeKinds.PLAYER_UI)
        listOf(VisibilitySubjectKinds.RELATIONSHIP_DATA,VisibilitySubjectKinds.POLITICS_DATA,VisibilitySubjectKinds.ECONOMY_DATA,VisibilitySubjectKinds.ORGANIZATION_DATA).forEach{
            assertEquals(DisclosureLevel.DENY,authority.decide(VisibilityRequest(player,ui,VisibilitySubjectRef(campaign,it,"X"))).level)
        }
        val diagnosticFixture=Phase38TrustedTestAuthority.diagnostic(campaign)
        val diagnostic=diagnosticFixture.audience;val dp=PurposeContext(campaign,VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)
        val politics=VisibilityRequest(diagnostic,dp,VisibilitySubjectRef(campaign,VisibilitySubjectKinds.POLITICS_DATA,"X"))
        assertEquals(DisclosureLevel.DENY,authority.decide(politics).level)
        assertEquals(DisclosureLevel.DISCLOSE_FULL,authority.decide(politics,diagnosticFixture.trusted).level)
    }

    @Test fun corruptionNoDataDeniedNotDisclosedUnknownAreDistinctContracts(){
        assertNotEquals(ProjectionDataState.NO_DATA,ProjectionDataState.DENIED)
        assertNotEquals(ProjectionDataState.DENIED,ProjectionDataState.NOT_DISCLOSED)
        assertNotEquals(ProjectionDataState.NOT_DISCLOSED,ProjectionDataState.UNKNOWN)
        assertNotEquals(ProjectionDataState.UNKNOWN,ProjectionDataState.CORRUPTION)
        val denied=authority.project(VisibilityRequest(player,PurposeContext(campaign,VisibilityPurposeKinds.PLAYER_UI),VisibilitySubjectRef(campaign,VisibilitySubjectKinds.CAMPAIGN_TRUTH,"T"))){"SECRET"}
        assertEquals(ProjectionDataState.DENIED,denied.dataState)
        val unknownAudience=AudienceContext(campaign,"UNKNOWN",VisibilityPrincipalRef("X","1"))
        val unknown=authority.project(VisibilityRequest(unknownAudience,PurposeContext(campaign,VisibilityPurposeKinds.PLAYER_UI),VisibilitySubjectRef(campaign,VisibilitySubjectKinds.PUBLIC_WORLD_EVENT,"E"))){"x"}
        assertEquals(ProjectionDataState.UNKNOWN,unknown.dataState)
        val noData=authority.projectList(VisibilityRequest(player,PurposeContext(campaign,VisibilityPurposeKinds.PLAYER_UI),VisibilitySubjectRef(campaign,VisibilitySubjectKinds.PUBLIC_WORLD_EVENT,"E"))){emptyList<String>()}
        assertEquals(ProjectionDataState.NO_DATA,noData.dataState)
    }

    @Test fun internallyInconsistentProtectedBindingFailsClosed(){
        val holder=KnowledgeHolderRef(KnowledgeHolderKinds.PLAYER_CHARACTER,"PC",campaign)
        val pc=AudienceContext(campaign,AudienceKinds.PLAYER_CHARACTER,VisibilityPrincipalRef("ENTITY","PC"),listOf(holder))
        val wrong=KnowledgeHolderRef(KnowledgeHolderKinds.PLAYER_CHARACTER,"OTHER",campaign)
        val request=VisibilityRequest(pc,PurposeContext(campaign,VisibilityPurposeKinds.WORLD_ACTOR_REASONING),VisibilitySubjectRef(campaign,VisibilitySubjectKinds.PHASE37_HOLDER_KNOWLEDGE,"PC",holder=wrong))
        val projection=authority.project(request){listOf("should not execute")}
        assertEquals(DisclosureLevel.DENY,projection.decision.level);assertNull(projection.value)
    }

    @Test fun universalityMatrixUsesOneCoreContract(){
        val p=PurposeContext(campaign,VisibilityPurposeKinds.WORLD_ACTOR_REASONING)
        listOf("ORDINARY_CHARACTER","ORGANIZATION_GENERAL","NON_HUMAN","SHARED_COLLECTIVE","TECH_OBSERVER","SUPERNATURAL_OBSERVER").forEach{kind->
            val a=AudienceContext(campaign,AudienceKinds.WORLD_ACTOR,VisibilityPrincipalRef(kind,"ID"))
            assertEquals(DisclosureLevel.DISCLOSE_FULL,authority.decide(VisibilityRequest(a,p,VisibilitySubjectRef(campaign,VisibilitySubjectKinds.PUBLIC_WORLD_EVENT,"E"))).level)
        }
        val strategy=VisibilityAudienceFactory.player(campaign)
        assertEquals(AudienceKinds.PLAYER,strategy.audienceKindUid)
        val characterRpg=AudienceContext(campaign,AudienceKinds.PLAYER_CHARACTER,VisibilityPrincipalRef("ENTITY","PC"))
        assertEquals(AudienceKinds.PLAYER_CHARACTER,characterRpg.audienceKindUid)
    }

    @Test fun modifiedPhase38CoreContainsNoWorldSpecificAuthorityBranches(){
        val paths=listOf(
            "app/src/main/java/com/rpgos/app/Phase38Visibility.kt",
            "app/src/main/java/com/rpgos/app/Phase38VisibilityConsumerInventory.kt",
            "app/src/main/java/com/rpgos/app/Phase38VisualAuthorization.kt",
            "app/src/main/java/com/rpgos/app/VisualPromptBuilder.kt"
        )
        val banned=listOf("naruto","bleach","witcher","shinobi","ninja","hokage","chakra","reiatsu","wizard","dragon","jedi")
        paths.forEach{path->val code=source(path).lowercase();banned.forEach{assertFalse("$path contains world lock-in $it",code.contains(it))}}
    }

    private fun repoRoot():File{
        var f=File(System.getProperty("user.dir")).canonicalFile
        repeat(8){if(File(f,"app/src/main/java").isDirectory)return f;f=f.parentFile?:return@repeat}
        error("repo root not found")
    }
    private fun source(path:String)=File(repoRoot(),path).readText()
}
