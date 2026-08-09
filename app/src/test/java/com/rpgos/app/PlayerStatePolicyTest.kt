package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerStatePolicyTest {
    @Test
    fun activePlayerRefRequiresCampaignAndPlayer() {
        val ref = ActivePlayerRef("naruto-default", "CHAR-PLAYER-000001")
        PlayerStatePolicy.validate(ref)
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankPlayerUidIsRejected() {
        PlayerStatePolicy.validate(ActivePlayerRef("naruto-default", ""))
    }

    @Test
    fun legacyCurrentResourcesAreRuntime() {
        assertEquals(PlayerStateClass.RUNTIME, PlayerStatePolicy.classifyLegacyField("current_chakra"))
        assertEquals(PlayerStateClass.RUNTIME, PlayerStatePolicy.classifyLegacyField("fatigue"))
    }

    @Test
    fun legacyEffectiveValuesAreDerived() {
        assertEquals(PlayerStateClass.DERIVED, PlayerStatePolicy.classifyLegacyField("effective_strength"))
        assertEquals(PlayerStateClass.DERIVED, PlayerStatePolicy.classifyLegacyField("net_worth"))
    }

    @Test
    fun ordinaryDurableFieldsDefaultToPersistent() {
        assertEquals(PlayerStateClass.PERSISTENT, PlayerStatePolicy.classifyLegacyField("strength"))
        assertEquals(PlayerStateClass.PERSISTENT, PlayerStatePolicy.classifyLegacyField("rank_title"))
    }
}
