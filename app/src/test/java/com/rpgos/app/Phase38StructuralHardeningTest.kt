package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase38StructuralHardeningTest {
    private val c="C1"
    @Test fun untrustedPrivilegedAudienceCannotSelfAuthorize(){
        val a=AudienceContext(c,AudienceKinds.DEVELOPER_DIAGNOSTIC,VisibilityPrincipalRef("RUNTIME","x"))
        val p=PurposeContext(c,VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)
        val r=VisibilityRequest(a,p,VisibilitySubjectRef(c,VisibilitySubjectKinds.CAMPAIGN_TRUTH,"T"))
        assertEquals(DisclosureLevel.DENY,VisibilityAuthorityService().decide(r).level)
    }
    @Test fun callerSuppliedHolderMappingIsNotAuthority(){
        val forged=KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER,"B",c)
        val a=AudienceContext(c,AudienceKinds.WORLD_ACTOR,VisibilityPrincipalRef("ENTITY","A"),listOf(forged))
        val p=PurposeContext(c,VisibilityPurposeKinds.WORLD_ACTOR_REASONING)
        val r=VisibilityRequest(a,p,VisibilitySubjectRef(c,VisibilitySubjectKinds.PHASE37_HOLDER_KNOWLEDGE,"B",holder=forged))
        assertEquals(DisclosureLevel.DENY,VisibilityAuthorityService().decide(r).level)
    }
    @Test fun pcBDoesNotReceivePcAState(){
        val a=AudienceContext(c,AudienceKinds.PLAYER_CHARACTER,VisibilityPrincipalRef("ENTITY","B"))
        val trusted=Phase38RuntimeAuthority.application(a,controlledSubjectUids=setOf("B"))!!
        val p=PurposeContext(c,VisibilityPurposeKinds.PLAYER_UI)
        val r=VisibilityRequest(a,p,VisibilitySubjectRef(c,VisibilitySubjectKinds.PLAYER_STATE,"A"))
        assertEquals(DisclosureLevel.DENY,VisibilityAuthorityService().decide(r,trusted).level)
    }
    @Test fun worldActorReasoningNeverGetsPlayerStateByDefault(){
        val a=AudienceContext(c,AudienceKinds.WORLD_ACTOR,VisibilityPrincipalRef("ENTITY","A"));val t=Phase38RuntimeAuthority.application(a)!!
        val r=VisibilityRequest(a,PurposeContext(c,VisibilityPurposeKinds.WORLD_ACTOR_REASONING),VisibilitySubjectRef(c,VisibilitySubjectKinds.PLAYER_STATE,"PC"))
        assertEquals(DisclosureLevel.DENY,VisibilityAuthorityService().decide(r,t).level)
    }
    @Test fun lowerDisclosureActuallyRemovesSecretPayload(){
        val a=VisibilityAudienceFactory.player(c);val e=VisibilityAuthorityService().envelope(a,PurposeContext(c,VisibilityPurposeKinds.GAMEPLAY_NARRATION))
        val b=ContextBundle(mapOf("chapter" to 1,"secret" to "S"),mapOf("query" to "q","secret" to "S"),emptyMap(),emptyList(),emptyList(),listOf(mapOf("secret" to "S")),emptyList(),emptyList(),listOf(mapOf("secret" to "S")),emptyList(),listOf(mapOf("secret" to "S")),campaignTruth=listOf(mapOf("secret" to "S")),playerState=mapOf("secret" to "S"),visibilityEnvelope=e)
        val reduced=b.reduceDisclosureTo(DisclosureLevel.DISCLOSE_REDACTED)
        assertFalse(reduced.toString().contains("secret=S"));assertEquals(DisclosureLevel.DISCLOSE_REDACTED,reduced.visibilityEnvelope.maximumDisclosure)
    }
    @Test fun typedDeniedAndNoDataAreDistinct(){
        assertNotEquals(ProtectedReadResult.Deny("x").stateUid,ProtectedReadResult.NoData.stateUid)
    }
    @Test fun visualAuthorizationBindsEditSourceAndImageDigest(){
        val a=VisibilityAudienceFactory.player(c);val e=VisibilityAuthorityService().envelope(a,PurposeContext(c,VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION))
        val digest=Phase38VisualAuthorization.digest("bytes")
        val auth=Phase38VisualAuthorization.authorize(e,VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION,"VISUAL","V1","edit",sourceVisualUid="V1",sourceImageSha256=digest,requestUid="R1")
        val ok=VisualSemanticRequest(c,AudienceKinds.PLAYER,"HUMAN_PLAYER",VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION,"VISUAL","V1","R1",VisualRequestKinds.EDIT,"edit",sourceVisualUid="V1",sourceImageSha256=digest)
        auth.requireRequest(ok)
        assertTrue(runCatching{auth.requireRequest(ok.copy(sourceVisualUid="V2"))}.isFailure)
    }
}
