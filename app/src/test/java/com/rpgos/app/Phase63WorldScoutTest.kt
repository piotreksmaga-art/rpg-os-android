package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase63WorldScoutTest {
    private val shape=WorldReferenceShape(WorldReferenceShapeKind.NAMED_INSTANCE,WorldElementBaseKind.PLACE,"REGION",emptySet(),"REGION")
    private fun request(phrase:String)=WorldEvidenceRequest("C",phrase,shape,"X".repeat(300),20)
    @Test fun noConsentSendsNothingAndTurnBudgetIsShared() {
        var consent=false;var now=0L;val budgets=mutableListOf<Long>()
        val provider=TurnBudgetedWorldScout({consent},{r,budget->
            assertTrue(r.phrase.length<=256);assertTrue(r.worldContextHint!!.length<=160);assertEquals(5,r.maximumCandidates)
            budgets+=budget;now+=3_000_000_000;emptyList()
        },{now})
        val turn=provider.forTurn()
        turn.candidates(request("A"));assertTrue(budgets.isEmpty())
        consent=true
        turn.candidates(request("A"));turn.candidates(request("a"));turn.candidates(request("B"));turn.candidates(request("C"))
        assertEquals(listOf(5000L,2000L),budgets)
        provider.forTurn().candidates(request("A"));assertEquals(5000L,budgets.last())
    }
    @Test fun failureDoesNotRetryTheReferenceOrTurnEvidenceIntoTruth() {
        var count=0
        val turn=TurnBudgetedWorldScout({true},{_,_->count++;error("offline")}).forTurn()
        assertTrue(turn.candidates(request("Morze")).isEmpty())
        assertTrue(turn.candidates(request("morze")).isEmpty())
        assertEquals(1,count)
    }
}
