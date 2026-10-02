package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase60PlayerDeclaredDurationTest {
    @Test fun explicitTimeIsIndependentOfVerbAndLanguage() {
        for(text in listOf("Przez 1 minutę rozglądam się.","Przez jedną minutę rozmawiam.","I listen for one minute."))
            assertEquals(60000L,Phase60PlayerDeclaredDuration.read(text)!!.milliseconds)
        assertEquals(2000L,Phase60PlayerDeclaredDuration.read("Działam przez 2 sekundy.")!!.milliseconds)
        assertEquals(10000L,Phase60PlayerDeclaredDuration.read("Przez 10 sekund pytam strażnika: Jak masz na imię?")!!.milliseconds)
        assertNull(Phase60PlayerDeclaredDuration.read("Pytam strażnika: Czekać przez 10 sekund?"))
    }
    @Test fun absentAmbiguousNegatedAndOverflowTimeRemainUnresolved() {
        for(text in listOf("Rozglądam się.","Przez 0 minut czekam.","Nie przez 1 minutę.","Czy przez 1 minutę?",
            "Przez 1 minutę robię A, przez 2 minuty B.","Przez 1 minutę albo 2 minuty czekam.","Czekam przez 999999999999999999999 godzin."))
            assertNull(text,Phase60PlayerDeclaredDuration.read(text))
    }
}
