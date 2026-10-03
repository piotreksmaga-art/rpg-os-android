package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase63ReferenceSetsTest {
    private val skeleton=CampaignWorldSkeleton.legacy("C",CampaignRuleSource(CampaignRuleSourceKind.CAMPAIGN_NATIVE,"C","1"),
        "Era",DomainRef("PLACE","START")).copy(latentRules=CoreLatentWorldRules.initial())
    private fun document(count:Int)=IntentDocument(campaignUid="C",actor=CommandActorRef("PLAYER","PC"),rawInput="Oglądam $count narzędzia",
        meaningState=MeaningState.UNDERSTOOD,nodes=listOf(IntentNode("N",IntentForm.DIRECT_ACTION,SemanticAction("LOOK",rawPhrase="Oglądam"),
            listOf(IntentParticipant("TARGET",referenceUid="R")))),references=listOf(IntentReference("R",IntentReferenceKind.SET,"narzędzia","TARGET",
            setOf("OBJECT"),mapOf("category" to "TOOL","affordances" to "LOOK","quantity" to count.toString()))),
        provenance=IntentInterpretationProvenance(IntentInterpretationSource.AI_PROVIDER,"TEST","1","HASH"))
    @Test fun setExpandsTargetsNotActionsAndOrdinalsHaveStableDistinctIdentities() {
        val input=document(3);val expanded=WorldReferenceSetExpansion.expand(input)
        assertEquals(input.rawInput,expanded.rawInput);assertEquals(1,expanded.nodes.size)
        assertEquals(3,expanded.nodes.single().participants.size)
        assertEquals(listOf("1","2","3"),expanded.references.map { it.descriptorHints["ordinal"] })
        val resolver=UniversalWorldMaterializationResolver()
        fun resolve(ref:IntentReference)=(resolver.resolve("C",ref,expanded.nodes,"START",emptyList(),null,skeleton=skeleton) as UniversalWorldReferenceResolution.Latent).draft.element
        val first=expanded.references.associate { it.referenceUid to resolve(it) }
        assertEquals(3,first.values.distinct().size)
        assertEquals(first,expanded.references.reversed().associate { it.referenceUid to resolve(it) })
        assertEquals(expanded,WorldReferenceSetExpansion.expand(expanded))
    }
    @Test fun unexpandedSetsNeverSilentlySelectOneExistingOrLatentElement() {
        val ref=document(3).references.single()
        assertTrue(UniversalWorldMaterializationResolver().resolve("C",ref,document(3).nodes,"START",emptyList(),null,skeleton=skeleton) is UniversalWorldReferenceResolution.Unresolved)
        assertNull(resolveExistingDescriptorCandidates(ref,document(3).nodes,listOf(DomainRef("OBJECT","ONE"))))
        val excessive=WorldReferenceSetExpansion.expand(document(17))
        assertNull(excessive.references.single().resolvedProjectedRef)
        assertEquals("P63:SET_SELECTION_REQUIRES_CLARIFICATION",excessive.references.single().descriptorHints["world_resolution_reason"])
    }
    @Test fun aGroupConditionIsNotRewrittenIntoSeveralUnrelatedPredicates() {
        val doc=document(2).let { it.copy(nodes=listOf(it.nodes.single().copy(conditions=listOf(IntentCondition("WHEN","GROUP_READY",listOf("R")))))) }
        val expanded=WorldReferenceSetExpansion.expand(doc)
        assertEquals(1,expanded.references.size);assertEquals(doc.nodes,expanded.nodes)
        assertEquals("P63:SET_SELECTION_REQUIRES_CLARIFICATION",expanded.references.single().descriptorHints["world_resolution_reason"])
    }
}
