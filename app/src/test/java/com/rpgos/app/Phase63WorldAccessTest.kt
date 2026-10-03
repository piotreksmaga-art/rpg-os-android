package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase63WorldAccessTest {
    private val scope=WorldResolutionScope("C",HistoryGenerationUid("G"),2,"PC",VisibilityPurposeKinds.GAMEPLAY_NARRATION,mapOf("SOURCE" to "V1"))
    private val element=CampaignWorldElement(DomainRef("ACTOR","A"),"Osoba","LOCAL_PERSON","START",setOf("TALK"),"LOCAL_SITE",WorldEvidenceClassification.CAMPAIGN_FACT)
    @Test fun callerCannotEscalatePrincipalHistoryOrderPurposeOrSource() {
        val gate=WorldResolutionReadAuthority(scope,{true})
        assertEquals(listOf(element),gate.project(scope,listOf(element)))
        for(foreign in listOf(scope.copy(campaignUid="FOREIGN"),scope.copy(principalUid="OTHER"),scope.copy(asOfCommittedOrder=3),
            scope.copy(historyGenerationUid=HistoryGenerationUid("OLD")),scope.copy(purposeUid=VisibilityPurposeKinds.INTERNAL_SIMULATION),scope.copy(sourceVersions=mapOf("SOURCE" to "V2"))))
            assertTrue(gate.project(foreign,listOf(element)).isEmpty())
        assertTrue(gate.project(scope,listOf(element.copy(audienceScopeUid="HIDDEN"))).isEmpty())
    }
    @Test fun concurrentUndoInvalidatesTheEntireProjection() {
        var current=true
        val gate=WorldResolutionReadAuthority(scope,{current})
        assertTrue(gate.accepts(scope));current=false
        assertFalse(gate.accepts(scope));assertTrue(gate.project(scope,listOf(element)).isEmpty())
    }
}
