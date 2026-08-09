package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class StatResourceContractTest {
    @Test
    fun arbitraryWorldPackDefinitionsAreAcceptedWithoutCoreSpecificKeys() {
        val stat = StatDefinition(
            statUid = "STAT-WORLD-X-FOCUS",
            key = "astral_focus",
            category = "mind",
            minValue = 0.0,
            worldPackUid = "WORLD-X"
        )
        val resource = ResourceDefinition(
            resourceUid = "RES-WORLD-X-FLUX",
            key = "astral_flux",
            category = "energy",
            minValue = 0.0,
            maxRuleUid = "RULE-WORLD-X-FLUX-MAX",
            regenerationRuleUid = "RULE-WORLD-X-FLUX-REGEN",
            worldPackUid = "WORLD-X"
        )

        assertEquals("astral_focus", stat.key)
        assertEquals("astral_flux", resource.key)
    }

    @Test
    fun invalidBoundsAndNonFiniteValuesAreRejected() {
        expectIllegalArgument {
            StatDefinition("S", "focus", "mind", minValue = 10.0, maxValue = 1.0, worldPackUid = "W")
        }
        expectIllegalArgument {
            PlayerStat("C", "P", "S", Double.NaN)
        }
        expectIllegalArgument {
            PlayerResource("C", "P", "R", 1.0, version = 0)
        }
    }

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
