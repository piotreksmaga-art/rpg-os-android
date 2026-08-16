package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase21ProgressionPolicyTest {
    @Test fun P21_01_diminishingReturnsIsDeterministicAndEvidenceDriven() {
        val a = Phase21DiminishingReturnsPolicy.factor("REP-E", 3L, 7L)
        val b = Phase21DiminishingReturnsPolicy.factor("REP-E", 3L, 7L)
        val later = Phase21DiminishingReturnsPolicy.factor("REP-E2", 10L, 7L)
        assertEquals(a, b)
        assertTrue(a.appliedFactor.scaledUnits < ProgressionNumericPolicy.SCALE)
        assertTrue(later.appliedFactor.scaledUnits < a.appliedFactor.scaledUnits)
        assertEquals(Phase21DiminishingReturnsPolicy.POLICY_VERSION, "1")
    }

    @Test fun P21_02_allTypedEvidenceKindsRemainPermutationInvariantInPhase20Engine() {
        val f1 = Phase21ProgressionFactorEvidence(
            Phase21ProgressionFactorKinds.NOVELTY, "E", ProgressionScaledValue.fromDouble(2.0),
            ProgressionScaledValue.fromDouble(1.2), "POLICY-N", "1"
        ).asCalculationFactor()
        val f2 = Phase21ProgressionFactorEvidence(
            Phase21ProgressionFactorKinds.ADAPTATION, "E", ProgressionScaledValue.fromDouble(4.0),
            ProgressionScaledValue.fromDouble(0.8), "POLICY-A", "1"
        ).asCalculationFactor()
        val f3 = Phase21ProgressionFactorEvidence(
            Phase21ProgressionFactorKinds.REPETITION, "E", ProgressionScaledValue.fromDouble(3.0),
            ProgressionScaledValue.fromDouble(0.9), "POLICY-R", "1"
        ).asCalculationFactor()
        val a = ProgressionEngine().evaluate(input(listOf(f1, f2, f3)))
        val b = ProgressionEngine().evaluate(input(listOf(f3, f1, f2)))
        assertEquals(a, b)
        assertEquals(a.inputFingerprint, b.inputFingerprint)
        assertEquals(a.resultFingerprint, b.resultFingerprint)
        assertEquals(a.grants.single().grantUid, b.grants.single().grantUid)
    }

    @Test fun P21_03_passiveHookRepeatedBuildIsPureAndDeterministic() {
        val evidence = listOf(
            Phase21DiminishingReturnsPolicy.factor("DR-E", 2L, 8L),
            Phase21ProgressionFactorEvidence(
                Phase21ProgressionFactorKinds.ENVIRONMENT, "ENV-E",
                ProgressionScaledValue.one(), ProgressionScaledValue.fromDouble(1.1), "ENV-POLICY", "1"
            )
        )
        val input = PassiveProgressionHookInput.create(
            campaignUid = "C1", characterUid = "P1", sourceEventUid = "EVENT-1", causeUid = "CAUSE-1",
            sourceTypeUid = "RPGOS-SOURCE:RESOLVED_EXTERNAL_CAUSE",
            sourceChannelUid = ProgressionSourceChannels.PRACTICE,
            subject = DomainRef("PLAYER", "P1"), targetKindUid = ProgressionTargetKinds.STAT,
            targetUid = "STR", targetValueEvidence = ProgressionTargetValueEvidence("CURRENT", "10", "EXACT", "1"),
            progressSemanticsUid = "EXACT_UNITS", progressSemanticsVersion = "1", effortUnits = 12L,
            methodUid = "PASSIVE-METHOD", factorEvidence = evidence,
            evidenceRefs = listOf(DomainRef("ENVIRONMENT", "ROOM-A"), DomainRef("EVENT", "EVENT-1")),
            progressionPolicyUid = "P21-PASSIVE-POLICY", progressionPolicyVersion = "1"
        )
        val a = PassiveProgressionHook.evaluate(input)
        val b = PassiveProgressionHook.evaluate(input)
        assertEquals(a.stimulusUid, b.stimulusUid)
        assertEquals(a.calculationFactors, b.calculationFactors)
        assertEquals(a.evidenceRefs, b.evidenceRefs)
        assertEquals(a.dependencyVersions, b.dependencyVersions)
        assertEquals(input.inputFingerprint, input.inputFingerprint)
    }

    @Test fun P21_04_hookSurfaceHasNoClockRandomDatabaseWriterOrMutableMemory() {
        val forbidden = listOf("Clock", "Random", "Database", "SQLite", "Repository", "Store", "Dao", "Transaction")
        PassiveProgressionHook::class.java.declaredFields.forEach { field ->
            forbidden.forEach { token -> assertFalse(field.type.name.contains(token, ignoreCase = true)) }
        }
        Phase21DiminishingReturnsPolicy::class.java.declaredFields.forEach { field ->
            forbidden.forEach { token -> assertFalse(field.type.name.contains(token, ignoreCase = true)) }
        }
    }

    @Test fun P21_05_hookRejectsCrossCharacterSubjectAndCoversImpactVocabulary() {
        assertTrue(Phase21ProgressionFactorKinds.supported.containsAll(listOf(
            Phase21ProgressionFactorKinds.FATIGUE_IMPACT,
            Phase21ProgressionFactorKinds.INJURY_IMPACT,
            Phase21ProgressionFactorKinds.ENVIRONMENT
        )))
        try {
            PassiveProgressionHookInput.create(
                campaignUid = "C1", characterUid = "P1", sourceEventUid = "E", causeUid = "CAUSE",
                sourceTypeUid = "SRC", sourceChannelUid = ProgressionSourceChannels.PRACTICE,
                subject = DomainRef("PLAYER", "P2"), targetKindUid = ProgressionTargetKinds.STAT, targetUid = "STR",
                targetValueEvidence = ProgressionTargetValueEvidence("CURRENT", "1", "EXACT", "1"),
                progressSemanticsUid = "EXACT", progressSemanticsVersion = "1", effortUnits = 1L,
                progressionPolicyUid = "POLICY", progressionPolicyVersion = "1"
            )
            throw AssertionError("cross-character subject must reject")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun input(factors: List<ProgressionCalculationFactor>) = ProgressionEvaluationInput.create(
        campaignUid = "C1", characterUid = "P1", sourceTypeUid = "SRC",
        sourceChannelUid = ProgressionSourceChannels.PRACTICE, stimulusUid = "STIM",
        sourceCommandUid = "CMD", commandKindUid = PlayerCommandKinds.TRAIN,
        commandFingerprint = "COMMAND-FP", targetKindUid = ProgressionTargetKinds.STAT, targetUid = "STR",
        targetValueEvidence = ProgressionTargetValueEvidence("CURRENT", "10", "EXACT", "1"),
        progressSemanticsUid = "EXACT_UNITS", progressSemanticsVersion = "1", effortUnits = 10L,
        calculationFactors = factors, worldPackBindingIdentity = progressionWorldPackBindingIdentity(null),
        progressionPolicyUid = "P21", progressionPolicyVersion = "1",
        progressionEngineUid = ProgressionEngine.ENGINE_UID, progressionEngineVersion = ProgressionEngine.ENGINE_VERSION
    )
}
