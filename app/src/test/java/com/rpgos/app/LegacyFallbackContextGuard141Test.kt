package com.rpgos.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyFallbackContextGuard141Test {
    @Test
    fun backendRejectsFallbackContextBeforeNetworkOrConfiguredUrlHandling() = runBlocking {
        val failure = runCatching {
            BackendClient("").sendTurn("Kontynuuj", 1, fallbackContext())
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("LEGACY_FALLBACK_CONTEXT_REJECTED"))
    }

    @Test
    fun safeDemoRejectsFallbackContextInsteadOfInventingNarrative() {
        val failure = runCatching {
            SafeDemoGameMaster().respond("Kontynuuj", fallbackContext(), 1)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("LEGACY_FALLBACK_CONTEXT_REJECTED"))
    }

    @Test
    fun nonFallbackContextStillAllowsConfiguredLegacyFallbackBehavior() = runBlocking {
        val context = authoritativeContext()
        val noBackend = BackendClient("").sendTurn("Kontynuuj", 1, context)
        assertTrue(noBackend.narration.contains("Backend nie jest skonfigurowany"))

        val demo = SafeDemoGameMaster().respond("Kontynuuj", context, 1)
        assertTrue(demo.narration.contains("[TRYB AWARYJNY]"))
        assertFalse(demo.narration.isBlank())
    }

    private fun fallbackContext(): ContextBundle =
        authoritativeContext().copy(playerStatus = mapOf("fallback" to true))

    private fun authoritativeContext(): ContextBundle = ContextBundle(
        playerStatus = mapOf("chapter" to 1),
        scene = emptyMap(),
        time = emptyMap(),
        activeThreads = emptyList(),
        relevantNpcs = emptyList(),
        npcKnowledge = emptyList(),
        missions = emptyList(),
        worldPressures = emptyList(),
        canonConstraints = emptyList(),
        recentChronicle = emptyList(),
        retrievedLongTermMemory = emptyList()
    )
}
