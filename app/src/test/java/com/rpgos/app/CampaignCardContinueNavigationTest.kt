package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CampaignCardContinueNavigationTest {
    @Test
    fun activeCampaignUsesContinueNavigationWhenAvailable() {
        val calls = mutableListOf<String>()

        handleCampaignCardAction(
            active = true,
            dirName = "Naruto_Default",
            activateCampaign = { calls += "activate:$it" },
            continueCampaign = { calls += "continue:$it" }
        )

        assertEquals(listOf("continue:Naruto_Default"), calls)
    }

    @Test
    fun inactiveCampaignOnlyActivates() {
        val calls = mutableListOf<String>()

        handleCampaignCardAction(
            active = false,
            dirName = "Naruto_Default",
            activateCampaign = { calls += "activate:$it" },
            continueCampaign = { calls += "continue:$it" }
        )

        assertEquals(listOf("activate:Naruto_Default"), calls)
    }

    @Test
    fun activeCampaignWithoutNavigatorFallsBackToActivation() {
        val calls = mutableListOf<String>()

        handleCampaignCardAction(
            active = true,
            dirName = "Naruto_Default",
            activateCampaign = { calls += "activate:$it" },
            continueCampaign = null
        )

        assertEquals(listOf("activate:Naruto_Default"), calls)
    }
}
