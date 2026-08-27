package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AiProviderCenterAvailabilityTest {
    @Test
    fun installedModelWithReadyRuntimeAndAdmittedProfile_isReadyForCharacterCreator() {
        val initial = AiProviderCenterStateFactory.initial(
            AiSystemConfiguration(),
            artifactInstalled = true,
            openRouter = CloudConnectionStatus("OPENROUTER", CloudAuthState.DISCONNECTED),
        )

        val ready = initial.reconcileLocalAvailability(
            artifactInstalled = true,
            runtimeAvailable = true,
            admission = LocalAdmissionResult.Admitted(LocalRuntimeBackend.CPU, 1_024, "RESOURCE_PROFILE_ADMITTED"),
        )

        assertEquals(AiAvailabilityState.READY, ready.modelOptions.single().availability)
        assertEquals("LOCAL_MODEL_READY", ready.modelOptions.single().reasonUid)
    }

    @Test
    fun rejectedMemoryProfile_remainsUnavailableEvenWhenTheArtifactExists() {
        val initial = AiProviderCenterStateFactory.initial(
            AiSystemConfiguration(),
            artifactInstalled = true,
            openRouter = CloudConnectionStatus("OPENROUTER", CloudAuthState.DISCONNECTED),
        )

        val rejected = initial.reconcileLocalAvailability(
            artifactInstalled = true,
            runtimeAvailable = true,
            admission = LocalAdmissionResult.Rejected(listOf("UNSAFE_MEMORY_PROFILE"), null),
        )

        assertEquals(AiAvailabilityState.UNAVAILABLE, rejected.modelOptions.single().availability)
        assertEquals("LOCAL_ADMISSION:UNSAFE_MEMORY_PROFILE", rejected.modelOptions.single().reasonUid)
    }
}
