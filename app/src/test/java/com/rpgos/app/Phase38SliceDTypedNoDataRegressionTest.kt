package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase38SliceDTypedNoDataRegressionTest {
    @Test
    fun noSignalRemainsTypedNoDataThroughDisclosureProjection() {
        val campaign = "C1"
        val fixture = Phase38TrustedTestAuthority.playerCharacter(campaign, "PC-A")
        val context = PerceptionContext(
            campaignUid = campaign,
            trustedObserver = fixture.trusted,
            capabilities = emptyList(),
            rules = PerceptionWorldRules(
                rulesUid = "RULES",
                compatibleChannelsBySignalKind = mapOf("SIGNAL" to setOf("CHANNEL"))
            )
        )
        val resolver = PerceptionResolver()
        val perception = resolver.evaluate(PerceptionRequest(context, null))
        val recognition = resolver.recognize(context, perception)
        val interpretation = resolver.interpret(context, perception)
        val policy = DisclosurePolicy(
            campaignUid = campaign,
            policyUid = "POLICY",
            maximumLevel = DisclosureLevel.DISCLOSE_FULL,
            properties = mapOf(
                "presence" to PropertyDisclosureRule(
                    "presence",
                    mapOf(DisclosureLevel.DISCLOSE_EXISTENCE to DisclosureValueProjection.Keep)
                )
            )
        )

        val projection = DisclosureResolver().resolve(
            perception,
            recognition,
            interpretation,
            policy,
            DisclosureLevel.DISCLOSE_FULL
        )

        assertEquals(PerceptionResultState.NO_DATA, perception.state)
        assertEquals(ProjectionDataState.NO_DATA, projection.decision.dataState)
        assertEquals(DisclosureLevel.DENY, projection.decision.level)
        assertTrue(projection.payload.isEmpty())
        assertEquals("NO_SIGNAL", projection.subject.subjectUid)
    }
}
