package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase63WorldOwnerAdaptersTest {
    private val skeleton=CampaignWorldSkeleton.legacy("C",CampaignRuleSource(CampaignRuleSourceKind.CAMPAIGN_NATIVE,"C","1"),"Era",DomainRef("PLACE","START"))
        .copy(latentRules=CoreLatentWorldRules.initial())
    private val scope=WorldResolutionScope("C",HistoryGenerationUid("G"),0,"PC",VisibilityPurposeKinds.GAMEPLAY_NARRATION,mapOf("C" to "1"))
    private val slot=LatentWorldSlot("START","TOOL",WorldElementBaseKind.OBJECT)
    private val draft=WorldElementDraft("C",slot.ref(skeleton),"Narzędzie",WorldElementBaseKind.OBJECT,"TOOL","START",setOf("USE"),"LOCAL_SITE",
        WorldEvidenceClassification.GENERATED_PLAUSIBLE,emptyList(),null,null,null)
    @Test fun latentOwnerBindsIdentitySourceAndSlotAndPreservesExistingElement() {
        val owner=RegisteredLatentWorldResolver(draft,emptyList()) { it==scope }
        assertEquals(WorldResolutionResult.Candidate(draft,slot),owner.resolve(scope,skeleton,slot))
        assertTrue(owner.resolve(scope,skeleton,slot.copy(ordinal=1)) is WorldResolutionResult.Contradicted)
        assertTrue(owner.resolve(scope.copy(sourceVersions=mapOf("C" to "2")),skeleton,slot) is WorldResolutionResult.Unavailable)
        val existing=CampaignWorldElement(draft.element,"Ustalona nazwa","TOOL","START",setOf("USE"),"LOCAL_SITE",WorldEvidenceClassification.CAMPAIGN_FACT)
        assertEquals(WorldResolutionResult.Existing(existing),RegisteredLatentWorldResolver(draft,listOf(existing)) { true }.resolve(scope,skeleton,slot))
    }
    @Test fun materializationDelegatesOnlyAdmittedDraftsToRegisteredOwner() {
        var called=0;var current=true
        val owner=CapturedWorldMaterializationPort(scope,listOf(draft),{current}) {
            called++;listOf(WorldSimulationChange("C",scope.historyGenerationUid,0,skeleton))
        }
        val registry=WorldComponentOwnerRegistry(mapOf("WORLD_SIMULATION" to owner))
        assertEquals(1,registry.requireOwner("WORLD_SIMULATION").prepare(scope,listOf(draft)).size)
        assertEquals(1,called)
        assertThrows(IllegalArgumentException::class.java) { owner.prepare(scope,listOf(draft.copy(displayName="Inny draft"))) }
        current=false
        assertThrows(IllegalArgumentException::class.java) { owner.prepare(scope,listOf(draft)) }
        assertEquals(1,called)
        assertThrows(IllegalStateException::class.java) { registry.requireOwner("AI_MAGIC") }
    }
}
