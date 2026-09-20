package com.rpgos.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Phase61To62ActivityContractDeviceSmokeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun typedLifecycleRequiresCanonicalDomainEvidenceOnAndroidRuntime() {
        assertTrue(context.packageName.contains("rpgos"))

        val owner = NpcActivityOwnerContract(
            contractUid = "P62:DEVICE_TRAVEL",
            version = 1,
            lifecycleOwnerUid = "P62:NPC_EXECUTION",
            resultOwnerUid = "RPGOS-P50:SPATIAL",
            evidenceKindUid = "P62:TRAVEL_ARRIVAL_RECEIPT",
            resultPolicy = NpcActivityResultPolicy.DOMAIN_RESULT_EVIDENCE,
            allowedCanonicalChangeKindUids = setOf(PlayerChangeKinds.SPATIAL)
        )
        val attempt = NpcActivityAttemptIdentity(
            campaignUid = "DEVICE-CAMPAIGN",
            historyGenerationUid = "DEVICE-HISTORY",
            actor = DomainRef("NPC", "DEVICE-NPC"),
            planUid = "DEVICE-PLAN",
            optionUid = "DEVICE-OPTION",
            capabilityUid = "WORLD:CUSTOM_TRAVEL",
            authorizedAt = WorldTimeTick(0),
            dueAt = WorldTimeTick(60_000),
            authorizationFingerprint = phase60Hash("DEVICE-AUTH"),
            ownerContractFingerprint = owner.fingerprint
        )
        val authorized = NpcActivityLifecycleRules.authorize(attempt, owner)
        val started = NpcActivityLifecycleRules.transition(
            authorized,
            NpcActivityLifecycleStage.STARTED,
            attempt.authorizedAt
        )
        val resolution = NpcActivityResolutionEvidence(
            attemptFingerprint = attempt.fingerprint,
            ownerUid = owner.resultOwnerUid,
            evidenceKindUid = owner.evidenceKindUid,
            resolutionUid = "DEVICE-RESOLUTION",
            resolutionKind = NpcActivityResolutionKind.SUCCEEDED,
            resolvedAt = attempt.dueAt,
            canonicalEvidence = listOf(
                NpcActivityCanonicalEvidence("DEVICE-SPATIAL-CHANGE", PlayerChangeKinds.SPATIAL)
            ),
            sourceFingerprint = phase60Hash("DEVICE-RESOLUTION")
        )
        val completed = NpcActivityLifecycleRules.transition(
            started,
            NpcActivityLifecycleStage.COMPLETED,
            attempt.dueAt,
            resolution
        )

        assertEquals(NpcActivityLifecycleStage.COMPLETED, completed.stage)
        assertTrue(completed.provesFullDomainSuccess())
    }
}
