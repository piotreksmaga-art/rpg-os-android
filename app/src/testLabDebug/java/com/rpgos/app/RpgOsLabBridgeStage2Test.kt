package com.rpgos.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
class RpgOsLabBridgeStage2Test {
    @Test
    fun `stage three contract preserves stage two and exposes Codex plus Director controls`() {
        assertEquals(3, RpgOsLabBridgeContract.stage)
        assertEquals("RPGOS_LAB_V1", RpgOsLabBridgeContract.protocol)

        assertTrue("GET_PIPELINE_SNAPSHOT" in RpgOsLabBridgeContract.readCommands)
        assertTrue("GET_RUNTIME_STATE" in RpgOsLabBridgeContract.readCommands)
        assertTrue("GET_MECHANICAL_STATE" in RpgOsLabBridgeContract.readCommands)
        assertTrue("GET_RECOVERY_STATE" in RpgOsLabBridgeContract.readCommands)
        assertTrue("GET_DIRECTOR_STATE" in RpgOsLabBridgeContract.readCommands)
        assertTrue("GET_LAST_AI_EXCHANGE" in RpgOsLabBridgeContract.readCommands)
        assertTrue("EXPORT_LAB_FIXTURE" in RpgOsLabBridgeContract.readCommands)
        assertTrue("RUN_ACTION_SEQUENCE" in RpgOsLabBridgeContract.productionPathCommands)
        assertTrue("RUN_COMBAT_SCENARIO" in RpgOsLabBridgeContract.productionPathCommands)
        assertTrue("RECOVER_PENDING_NARRATION" in RpgOsLabBridgeContract.productionPathCommands)
        assertTrue("LOAD_LAB_FIXTURE" in RpgOsLabBridgeContract.labAdminCommands)
        assertTrue("GET_CODEX_PROVIDER_STATE" in RpgOsLabBridgeContract.readCommands)
        assertTrue("GET_DIRECTOR_GUIDANCE" in RpgOsLabBridgeContract.readCommands)
        assertTrue("REGISTER_CODEX_HOST" in RpgOsLabBridgeContract.labAdminCommands)
        assertTrue("CLAIM_AI_REQUEST" in RpgOsLabBridgeContract.labAdminCommands)
        assertTrue("COMPLETE_AI_REQUEST" in RpgOsLabBridgeContract.labAdminCommands)
        assertTrue("RUN_DIRECTOR_NOW" in RpgOsLabBridgeContract.labAdminCommands)

        assertTrue(RpgOsLabBridgeContract.productionPathCommands.intersect(RpgOsLabBridgeContract.readCommands).isEmpty())
        assertTrue(RpgOsLabBridgeContract.productionPathCommands.intersect(RpgOsLabBridgeContract.labAdminCommands).isEmpty())
        assertTrue(RpgOsLabBridgeContract.readCommands.intersect(RpgOsLabBridgeContract.labAdminCommands).isEmpty())
        assertEquals(
            RpgOsLabBridgeContract.productionPathCommands.size +
                RpgOsLabBridgeContract.readCommands.size +
                RpgOsLabBridgeContract.labAdminCommands.size,
            RpgOsLabBridgeContract.allCommands.size
        )
    }

    @Test
    fun `contract does not expose direct canonical storage mutation`() {
        val forbiddenFragments = listOf("SQL", "INSERT", "UPDATE_ROW", "DELETE_ROW", "PATCH_STATE", "WRITE_DATABASE")
        RpgOsLabBridgeContract.allCommands.forEach { command ->
            assertFalse("Forbidden bridge command: $command", forbiddenFragments.any(command::contains))
        }
    }

    @Test
    fun `action sequence preserves ordered nonblank player inputs`() {
        val actions = labActionSequence(
            JSONObject().put("actions", JSONArray(listOf("  Idę na poligon.  ", "Rozglądam się.")))
        )

        assertEquals(listOf("Idę na poligon.", "Rozglądam się."), actions)
    }

    @Test
    fun `action sequence rejects missing empty blank and oversized scenarios`() {
        assertThrows(IllegalArgumentException::class.java) { labActionSequence(JSONObject()) }
        assertThrows(IllegalArgumentException::class.java) {
            labActionSequence(JSONObject().put("actions", JSONArray()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            labActionSequence(JSONObject().put("actions", JSONArray(listOf("ok", "   "))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            labActionSequence(JSONObject().put("actions", JSONArray((1..101).map { "turn-$it" })))
        }
    }
}
