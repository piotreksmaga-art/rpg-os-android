package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase63ObservationTimingTest {
    private val look=IntentNode("N",IntentForm.DIRECT_ACTION,SemanticAction(semanticFamilyUid="LOOK",rawPhrase="Oglądam grupę mieszkańców."))
    @Test fun ordinaryInspectionHasCoreTimingWithoutInventingKnowledge() {
        val metadata=Phase63ObservationTiming.metadata(look,"INTERACTION")
        val effect=VerifiedMechanicsCommandEffect("E","N","UNIVERSAL_ACTION","INTERACTION",DomainRef("PLAYER","P"),1,
            metadata,"PROOF",phase63Hash("input"),phase63Hash("output"))
        val timing=Phase60DomainTiming.accepted(listOf(effect)).getValue("N")
        assertEquals(30_000L,timing.duration.milliseconds)
        assertEquals(Phase63ObservationTiming.RULE,timing.ruleUid)
        assertFalse(metadata.keys.any { it.contains("knowledge") || it.contains("observation_evidence") })
        assertEquals(30_000L,Phase60DomainTiming.effectOffset(effect,timing.duration))
    }
    @Test fun explicitDurationFutureNegationAndOtherEffectsDoNotAcquireThisRule() {
        assertTrue(Phase63ObservationTiming.metadata(look.copy(semanticAction=look.semanticAction.copy(rawPhrase="Przez 2 minuty oglądam grupę.")),"INTERACTION").isEmpty())
        assertTrue(Phase63ObservationTiming.metadata(look.copy(modality=IntentModality.PLAN_FUTURE),"INTERACTION").isEmpty())
        assertTrue(Phase63ObservationTiming.metadata(look.copy(polarity=IntentPolarity.NEGATED),"INTERACTION").isEmpty())
        assertTrue(Phase63ObservationTiming.metadata(look,"DAMAGE").isEmpty())
        assertTrue(Phase63ObservationTiming.metadata(look.copy(semanticAction=look.semanticAction.copy(semanticFamilyUid="SEARCH")),"INTERACTION").isEmpty())
    }
}
