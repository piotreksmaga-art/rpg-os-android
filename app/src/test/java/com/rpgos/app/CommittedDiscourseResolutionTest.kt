package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class CommittedDiscourseResolutionTest {
    private fun reference(kind:IntentReferenceKind=IntentReferenceKind.DISCOURSE,base:String="ACTOR",category:String="GUARD")=
        IntentReference("R",kind,"tego samego rozmówcę","TARGET",semanticTypeHints=setOf(base),descriptorHints=mapOf(
            "shape" to "ROLE","world_base_kind" to base,"category" to category,"affordances" to "QUERY"))
    private fun element(uid:String="N1",category:String="GUARD",base:String="ACTOR",anchor:String="MARKET")=
        CampaignWorldElement(DomainRef(base,uid),"lokalny rozmówca",category,anchor,setOf("TALK"),"LOCAL_SITE",WorldEvidenceClassification.CAMPAIGN_FACT)

    @Test fun sameReferentUsesAuthorizedIdentityDespiteChangedVerbAndKeepsItAfterReconstruction() {
        val a=element();val b=element("N2")
        repeat(2){
            val result=UniversalWorldMaterializationResolver().resolve("C",reference(),emptyList(),"MARKET",listOf(b,a),null,setOf(a.element))
                as UniversalWorldReferenceResolution.Existing
            assertEquals(a.element,result.element.element)
        }
    }
    @Test fun unknownOrRemovedDiscourseNeverCreatesAnInstanceOrCallsExternalEvidence() {
        var calls=0
        val resolver=UniversalWorldMaterializationResolver(WorldEvidenceProviderPort{calls++;emptyList()})
        for(known in listOf(emptyList(),listOf(element("OTHER")))) {
            val result=resolver.resolve("C",reference(),emptyList(),"MARKET",known,null,setOf(element().element))
            assertEquals(UniversalWorldReferenceResolution.Unresolved("EXISTING_DISCOURSE_REFERENT_REQUIRED"),result)
        }
        assertEquals(0,calls)
    }
    @Test fun hiddenRemoteAndWrongKindCannotBecomeConversationalIdentity() {
        val variants=listOf(element().copy(audienceScopeUid="HIDDEN"),element(anchor="OTHER_PLACE"),element(base="OBJECT"))
        for(value in variants)assertTrue(UniversalWorldMaterializationResolver().resolve("C",reference(),emptyList(),"MARKET",
            listOf(value),null,setOf(value.element)) is UniversalWorldReferenceResolution.Unresolved)
    }
    @Test fun multipleInterlocutorsRemainAmbiguousAndRoleDoesNotPickAnArbitraryNpc() {
        val entries=listOf(element(),element("N2"))
        val resolver=UniversalWorldMaterializationResolver()
        assertEquals(UniversalWorldReferenceResolution.Rejected("REFERENCE_AMBIGUOUS"),resolver.resolve("C",reference(),emptyList(),"MARKET",entries,null,entries.map{it.element}.toSet()))
        assertEquals(UniversalWorldReferenceResolution.Rejected("REFERENCE_AMBIGUOUS"),resolver.resolve("C",reference(IntentReferenceKind.DESCRIPTIVE),emptyList(),"MARKET",entries,null))
        val single=resolver.resolve("C",reference(IntentReferenceKind.DESCRIPTIVE),emptyList(),"MARKET",entries.take(1),null) as UniversalWorldReferenceResolution.Existing
        assertEquals(entries.first(),single.element)
    }
    @Test fun ruleIsNotGuardOrWorldPackSpecificAndDeicticCannotMaterializeEither() {
        for((base,category) in listOf("ACTOR" to "LIBRARIAN","OBJECT" to "TOOL","PLACE" to "WORKSHOP")) {
            val e=element(base=base,category=category)
            val r=reference(base=base,category=category)
            assertTrue(UniversalWorldMaterializationResolver().resolve("C",r,emptyList(),"MARKET",listOf(e),null,setOf(e.element)) is UniversalWorldReferenceResolution.Existing)
            assertTrue(UniversalWorldMaterializationResolver().resolve("C",r.copy(kind=IntentReferenceKind.DEICTIC),emptyList(),"MARKET",emptyList(),null) is UniversalWorldReferenceResolution.Unresolved)
        }
    }
}
