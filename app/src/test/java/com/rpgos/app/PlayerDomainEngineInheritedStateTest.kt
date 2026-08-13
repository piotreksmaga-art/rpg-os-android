package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PlayerDomainEngineInheritedStateTest {
    @Test fun inheritedWriterStateMustBeRejectedBeforeResolution() {
        val authority = WritableAuthorityFixture(7L)
        val component = InheritedWriterComponent(authority)

        try {
            PlayerResolutionComponentRegistry.of(listOf(component))
            fail("inherited writable capability must be rejected")
        } catch (e: PlayerDomainEngineStructuralException) {
            assertEquals("UNSAFE_RESOLUTION_COMPONENT_STATE", e.code)
        }

        assertEquals(7L, authority.value)
        assertFalse(component.resolveInvoked)
    }

    @Test fun inheritedImmutableScalarConfigurationIsAccepted() {
        val registry = PlayerResolutionComponentRegistry.of(listOf(InheritedImmutableConfigComponent(3L)))
        assertTrue(PlayerCommandKinds.TRAIN in registry.commandKindUids)
    }

    private class WritableAuthorityFixture(initialValue: Long) {
        var value: Long = initialValue
            private set

        fun write(newValue: Long) {
            value = newValue
        }
    }

    private abstract class WriterBackedBaseComponent(
        protected val authority: WritableAuthorityFixture
    ) : PlayerResolutionComponent<TrainCommandPayload>(
        PlayerCommandKinds.TRAIN,
        TrainCommandPayload::class,
        "RPGOS-COMPONENT:INHERITED-WRITER",
        "1"
    )

    private class InheritedWriterComponent(
        authority: WritableAuthorityFixture
    ) : WriterBackedBaseComponent(authority) {
        var resolveInvoked: Boolean = false
            private set

        override fun resolve(
            command: PlayerCommand<TrainCommandPayload>,
            context: PlayerResolutionContext
        ): PlayerResolutionComponentOutcome {
            resolveInvoked = true
            authority.write(99L)
            throw AssertionError("unsupported component must never execute")
        }
    }

    private abstract class ImmutableConfigBaseComponent(
        protected val configuredDelta: Long
    ) : PlayerResolutionComponent<TrainCommandPayload>(
        PlayerCommandKinds.TRAIN,
        TrainCommandPayload::class,
        "RPGOS-COMPONENT:INHERITED-IMMUTABLE",
        "1"
    )

    private class InheritedImmutableConfigComponent(
        configuredDelta: Long
    ) : ImmutableConfigBaseComponent(configuredDelta) {
        override fun resolve(
            command: PlayerCommand<TrainCommandPayload>,
            context: PlayerResolutionContext
        ): PlayerResolutionComponentOutcome =
            PlayerResolutionComponentOutcome.Resolved(PlayerResolutionDraft.create())
    }
}
