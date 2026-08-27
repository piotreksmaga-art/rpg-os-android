package com.rpgos.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSettingsFrontendContractTest {
    @Test
    fun mainSettingsExposeTheSharedAiProviderCenterBeforeCampaignCreation() {
        val source = mainActivitySource()
        val settings = source.substringAfter("@Composable private fun SettingsScreen")
            .substringBefore("@Composable private fun SystemSettingsScreen")

        assertTrue(settings.contains("mutableStateOf(SettingsSection.AI)"))
        assertTrue(settings.contains("SettingsSection.AI->AiProviderCenterScreen(vm)"))
        assertTrue(source.contains("AI(\"Modele AI\")"))
        assertEquals(1, Regex("private fun AiProviderCenterScreen").findAll(source).count())
    }

    private fun mainActivitySource(): String {
        val candidates = listOf(
            File("src/main/java/com/rpgos/app/MainActivity.kt"),
            File("app/src/main/java/com/rpgos/app/MainActivity.kt")
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("MainActivity.kt not found from ${File(".").absolutePath}")
    }
}
