package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase62NpcActivityLifecycleContractTest {
    private val owner = NpcActivityOwnerContract(
        contractUid = "P62:TRAVEL_RESULT",
        version = 1,
        lifecycleOwnerUid = "P62:NPC_EXECUTION",
        resultOwnerUid = "RPGOS-P50:SPATIAL",
        evidenceKindUid = "P62:TRAVEL_ARRIVAL_RECEIPT",
        resultPolicy = NpcActivityResultPolicy.DOMAIN_RESULT_EVIDENCE,
        allowedCanonicalChangeKindUids = setOf(PlayerChangeKinds.SPATIAL)
    )
    private val attempt = NpcActivityAttemptIdentity(
        campaignUid = "C1",
        historyGenerationUid = "G1",
        actor = DomainRef("NPC", "N1"),
        planUid = "PLAN1",
        optionUid = "OPTION1",
        capabilityUid = "TRAVEL",
        authorizedAt = WorldTimeTick(1_000),
        dueAt = WorldTimeTick(61_000),
        authorizationFingerprint = phase60Hash("AUTH"),
        ownerContractFingerprint = owner.fingerprint
    )

    private fun resolution(
        kind: NpcActivityResolutionKind = NpcActivityResolutionKind.SUCCEEDED,
        ownerUid: String = owner.resultOwnerUid,
        evidenceKindUid: String = owner.evidenceKindUid,
        changeKindUid: String = PlayerChangeKinds.SPATIAL,
        attemptFingerprint: String = attempt.fingerprint,
        at: WorldTimeTick = attempt.dueAt
    ) = NpcActivityResolutionEvidence(
        attemptFingerprint = attemptFingerprint,
        ownerUid = ownerUid,
        evidenceKindUid = evidenceKindUid,
        resolutionUid = "RESOLUTION1",
        resolutionKind = kind,
        resolvedAt = at,
        canonicalEvidence = if (kind in setOf(
                NpcActivityResolutionKind.SUCCEEDED,
                NpcActivityResolutionKind.PARTIAL
            )
        ) listOf(NpcActivityCanonicalEvidence("CHANGE1", changeKindUid)) else emptyList(),
        sourceFingerprint = phase60Hash("RESOLUTION")
    )

    @Test
    fun elapsedTimeAndModelIntentCannotCompleteAnActivityWithoutDomainEvidence() {
        val authorized = NpcActivityLifecycleRules.authorize(attempt, owner)
        val started = NpcActivityLifecycleRules.transition(
            authorized,
            NpcActivityLifecycleStage.STARTED,
            attempt.authorizedAt
        )
        assertThrows(IllegalArgumentException::class.java) {
            NpcActivityLifecycleRules.transition(
                started,
                NpcActivityLifecycleStage.COMPLETED,
                attempt.dueAt
            )
        }
        assertFalse(started.provesFullDomainSuccess())
    }

    @Test
    fun canonicalOwnerEvidenceCompletesTheExactAttempt() {
        val authorized = NpcActivityLifecycleRules.authorize(attempt, owner)
        val started = NpcActivityLifecycleRules.transition(
            authorized,
            NpcActivityLifecycleStage.STARTED,
            attempt.authorizedAt
        )
        val completed = NpcActivityLifecycleRules.transition(
            started,
            NpcActivityLifecycleStage.COMPLETED,
            attempt.dueAt,
            resolution()
        )
        assertTrue(completed.provesFullDomainSuccess())
        assertEquals(NpcActivityLifecycleStage.COMPLETED, completed.stage)
        assertEquals(attempt.fingerprint, completed.resolution!!.attemptFingerprint)
    }

    @Test
    fun wrongOwnerWrongEvidenceKindStaleAttemptAndWrongChangeKindAreRejected() {
        val started = NpcActivityLifecycleRules.transition(
            NpcActivityLifecycleRules.authorize(attempt, owner),
            NpcActivityLifecycleStage.STARTED,
            attempt.authorizedAt
        )
        listOf(
            resolution(ownerUid = "OTHER_OWNER"),
            resolution(evidenceKindUid = "OTHER_EVIDENCE"),
            resolution(attemptFingerprint = phase60Hash("STALE")),
            resolution(changeKindUid = PlayerChangeKinds.SKILL)
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                NpcActivityLifecycleRules.transition(
                    started,
                    NpcActivityLifecycleStage.COMPLETED,
                    attempt.dueAt,
                    invalid
                )
            }
        }
    }

    @Test
    fun interruptionAndCancellationNeverCarryPrecomputedFutureEffects() {
        assertThrows(IllegalArgumentException::class.java) {
            NpcActivityResolutionEvidence(
                attemptFingerprint = attempt.fingerprint,
                ownerUid = owner.resultOwnerUid,
                evidenceKindUid = owner.evidenceKindUid,
                resolutionUid = "INTERRUPTED",
                resolutionKind = NpcActivityResolutionKind.INTERRUPTED,
                resolvedAt = WorldTimeTick(2_000),
                verifiedEffectUids = listOf("FUTURE_EFFECT"),
                sourceFingerprint = phase60Hash("INTERRUPTED")
            )
        }
        val started = NpcActivityLifecycleRules.transition(
            NpcActivityLifecycleRules.authorize(attempt, owner),
            NpcActivityLifecycleStage.STARTED,
            attempt.authorizedAt
        )
        val interruptedEvidence = NpcActivityResolutionEvidence(
            attemptFingerprint = attempt.fingerprint,
            ownerUid = owner.resultOwnerUid,
            evidenceKindUid = owner.evidenceKindUid,
            resolutionUid = "INTERRUPTED",
            resolutionKind = NpcActivityResolutionKind.INTERRUPTED,
            resolvedAt = WorldTimeTick(2_000),
            sourceFingerprint = phase60Hash("INTERRUPTED")
        )
        val interrupted = NpcActivityLifecycleRules.transition(
            started,
            NpcActivityLifecycleStage.INTERRUPTED,
            WorldTimeTick(2_000),
            interruptedEvidence
        )
        assertEquals(NpcActivityLifecycleStage.INTERRUPTED, interrupted.stage)
        assertFalse(interrupted.provesFullDomainSuccess())
    }

    @Test
    fun terminalAttemptCannotBeReopenedOrReusedAfterHistoryGenerationChanges() {
        val started = NpcActivityLifecycleRules.transition(
            NpcActivityLifecycleRules.authorize(attempt, owner),
            NpcActivityLifecycleStage.STARTED,
            attempt.authorizedAt
        )
        val completed = NpcActivityLifecycleRules.transition(
            started,
            NpcActivityLifecycleStage.COMPLETED,
            attempt.dueAt,
            resolution()
        )
        assertThrows(IllegalArgumentException::class.java) {
            NpcActivityLifecycleRules.transition(
                completed,
                NpcActivityLifecycleStage.IN_PROGRESS,
                WorldTimeTick(62_000)
            )
        }
        val otherHistory = attempt.copy(historyGenerationUid = "G2")
        assertNotEquals(attempt.fingerprint, otherHistory.fingerprint)
        assertThrows(IllegalArgumentException::class.java) {
            NpcActivityLifecycleSnapshot(
                otherHistory,
                owner,
                NpcActivityLifecycleStage.COMPLETED,
                attempt.dueAt,
                resolution()
            )
        }
    }

    @Test
    fun partialOutcomeIsNotSilentlyPromotedToFullGoalSuccess() {
        val started = NpcActivityLifecycleRules.transition(
            NpcActivityLifecycleRules.authorize(attempt, owner),
            NpcActivityLifecycleStage.STARTED,
            attempt.authorizedAt
        )
        val partial = NpcActivityLifecycleRules.transition(
            started,
            NpcActivityLifecycleStage.COMPLETED,
            attempt.dueAt,
            resolution(NpcActivityResolutionKind.PARTIAL)
        )
        assertEquals(NpcActivityResolutionKind.PARTIAL, partial.resolution!!.resolutionKind)
        assertFalse(partial.provesFullDomainSuccess())
    }

    @Test
    fun registryIsImmutableWorldNeutralAndRejectsDuplicateOwnershipKeys() {
        val changes = mutableSetOf(PlayerChangeKinds.SPATIAL)
        val source = owner.copy(
            contractUid = "WORLD:CUSTOM_TRAVEL",
            allowedCanonicalChangeKindUids = changes
        )
        val registry = NpcActivityOwnerContractPort.registered(listOf(source))
        changes += PlayerChangeKinds.SKILL
        val stored = registry.contract("C1", source.contractUid, source.version)
        assertEquals(setOf(PlayerChangeKinds.SPATIAL), stored!!.allowedCanonicalChangeKindUids)
        assertNull(registry.contract("C1", "UNKNOWN", 1))
        assertThrows(IllegalArgumentException::class.java) {
            NpcActivityOwnerContractPort.registered(listOf(source, source))
        }
    }

    @Test
    fun attemptOnlyCompletionStillNeedsTypedLeafEvidence() {
        val attemptOwner = NpcActivityOwnerContract(
            contractUid = "P62:REGISTERED_EFFORT",
            version = 1,
            lifecycleOwnerUid = "P62:NPC_EXECUTION",
            resultOwnerUid = NpcActivityMechanics.OWNER,
            evidenceKindUid = "P62:REGISTERED_ACTIVITY_RECEIPT",
            resultPolicy = NpcActivityResultPolicy.ATTEMPT_EVIDENCE
        )
        val effortAttempt = attempt.copy(
            capabilityUid = "WAIT",
            ownerContractFingerprint = attemptOwner.fingerprint
        )
        val started = NpcActivityLifecycleRules.transition(
            NpcActivityLifecycleRules.authorize(effortAttempt, attemptOwner),
            NpcActivityLifecycleStage.STARTED,
            effortAttempt.authorizedAt
        )
        val noLeaf = NpcActivityResolutionEvidence(
            effortAttempt.fingerprint,
            attemptOwner.resultOwnerUid,
            attemptOwner.evidenceKindUid,
            "EFFORT_RESULT",
            NpcActivityResolutionKind.SUCCEEDED,
            effortAttempt.dueAt,
            sourceFingerprint = phase60Hash("EFFORT_RESULT")
        )
        assertThrows(IllegalArgumentException::class.java) {
            NpcActivityLifecycleRules.transition(
                started,
                NpcActivityLifecycleStage.COMPLETED,
                effortAttempt.dueAt,
                noLeaf
            )
        }
        val withLeaf = noLeaf.copy(verifiedEffectUids = listOf("EFFORT_EFFECT"))
        assertTrue(
            NpcActivityLifecycleRules.transition(
                started,
                NpcActivityLifecycleStage.COMPLETED,
                effortAttempt.dueAt,
                withLeaf
            ).provesFullDomainSuccess()
        )
    }
}
