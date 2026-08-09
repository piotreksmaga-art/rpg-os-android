package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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

    @Test(expected = IllegalArgumentException::class)
    fun blankCampaignIdIsRejected() {
        PlayerStatePolicy.validate(ActivePlayerRef("", "CHAR-PLAYER-000001"))
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

    @Test
    fun contextProjectionKeepsStateLayersSeparate() {
        val persistent = mapOf<String, Any?>("strength" to 12L)
        val derived = mapOf<String, Any?>("effective_strength" to 14L)
        val runtime = mapOf<String, Any?>("current_chakra" to 80L)
        val snapshot = PlayerStateSnapshot(
            activePlayer = ActivePlayerRef("naruto-default", "CHAR-PLAYER-000001"),
            persistent = persistent,
            derived = derived,
            runtime = runtime
        )

        val projection = snapshot.toContextMap()
        val active = projection["active_player"] as Map<*, *>
        assertEquals("naruto-default", active["campaign_id"])
        assertEquals("CHAR-PLAYER-000001", active["player_uid"])
        assertSame(persistent, projection["persistent"])
        assertSame(derived, projection["derived"])
        assertSame(runtime, projection["runtime"])
    }
}
