package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase62NpcCommittedSpeechNarrationTest {
    private val message="Dzień dobry, mój zeszyt jest niebieski. Czy zapamiętasz jego kolor?"
    private fun context(facts:List<CommittedNarrativeFact>)=CommittedNarrationContext(
        "C","T","CMD","TX",4,"P38",emptyMap(),facts,emptyList(),emptySet(),emptySet(),"PLAYER_DECISION_POINT","FINGERPRINT")
    private val fact get()=CommittedNarrativeFact("SPEECH",CommittedNarrativeFactKind.NARRATIVE_COLOR,"PLAYER",
        NpcCommunicationMemory.PLAYER_UTTERANCE,message,4)
    private fun render(text:String)=RenderedNarrative(text,"PLAYER_DECISION_POINT",4,emptyList(),false)
    @Test fun committedPlayerQuoteIsNotAnotherDecisionAndEveryQuoteStyleWorks() {
        for((open,close) in listOf("„" to "”","“" to "”","\"" to "\"","«" to "»"))
            assertTrue(NarrativeValidator().validate(render("Mówisz do bibliotekarza: $open$message$close."),context(listOf(fact)),message).accepted)
    }
    @Test fun privateInputInventedQuoteUnquotedFirstPersonAndNewFutureDecisionRemainRejected() {
        for((text,facts) in listOf(
            "Mówisz: „$message”" to emptyList(),
            "Mówisz: „Chcę zaatakować bibliotekarza.”" to listOf(fact),
            message to listOf(fact),
            "Mówisz: „$message” Następnie pójdę na poligon." to listOf(fact),
            "Mówisz: „mój zeszyt jest niebieski.”" to listOf(fact)))
            assertTrue(NarrativeValidator().validate(render(text),context(facts),message).reasonUids.contains("NARRATIVE_INVENTED_PLAYER_VOLITION"))
    }
    @Test fun saidEvidenceCannotBecomeAWorldFact() {
        val claim=NarrativeSemanticClaim("C",NarrativeClaimKind.FACT,fact.factUid,fact.predicateUid,fact.valueCanonical)
        assertTrue(NarrativeValidator().validate(render("Powtarzasz wypowiedź.").copy(claims=listOf(claim)),context(listOf(fact))).reasonUids
            .any{it.startsWith("NARRATIVE_FACT_WITHOUT_FACT_SUPPORT")})
    }
}
