package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase63ConversationTimingTest {
    @Test fun playerMessagesDoNotClaimAnUnknownDestinationDoesNotExist() {
        val message=Phase63WorldMessages.explanation(listOf("NATURAL_OR_REMOTE_TOPOLOGY_UNRESOLVED"))!!
        assertTrue(message.contains("drogi"));assertFalse(message.contains("nie istnieje"))
        assertNull(Phase63WorldMessages.explanation(listOf("SOME_OTHER_DIAGNOSTIC")))
    }
    @Test fun deliveredSpeechHasARegisteredDurationAndNeverUsesHostLatency() {
        val metadata=NpcConversationTiming.metadata("Dzień dobry, czy mogę poćwiczyć?","Powiedz, co chcesz ćwiczyć.")
        assertEquals("30000",metadata["p60_core_duration_ms"])
        assertEquals(metadata,NpcConversationTiming.metadata("Dzień dobry, czy mogę poćwiczyć?","Powiedz, co chcesz ćwiczyć."))
        val effect=VerifiedMechanicsCommandEffect("E","N1","UNIVERSAL_ACTION","NARRATIVE_EVENT",DomainRef("ACTOR","N"),1,
            metadata,"P62:DIALOGUE:proof",phase63Hash("input"),phase63Hash("output"))
        val accepted=Phase60DomainTiming.accepted(listOf(effect)).getValue("N1")
        assertEquals(30_000L,accepted.duration.milliseconds)
        assertTrue(Phase60TimingPolicy.resolve(ActionTimingEvidence(ActionTimeMeaning.IN_WORLD,authoritative=accepted,
            estimatedMinimum=ActionDuration(30_000),estimatedMaximum=ActionDuration(120_000))) is ActionTimingDecision.Accepted)
        assertEquals(30_000L,Phase60DomainTiming.effectOffset(effect,accepted.duration))
    }
}
