package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Test

class Phase20FactorCanonicalizationRegressionTest {
    private val engine = ProgressionEngine()

    @Test
    fun P20_C4_001_factorPermutationWithSamePrimaryKeysHasStableIdentityChain() {
        val f1 = ProgressionCalculationFactor(
            ProgressionFactorKinds.QUALITY,
            "E",
            ProgressionScaledValue.fromDouble(1.0),
            ProgressionScaledValue.fromDouble(1.5)
        )
        val f2 = ProgressionCalculationFactor(
            ProgressionFactorKinds.QUALITY,
            "E",
            ProgressionScaledValue.fromDouble(2.0),
            ProgressionScaledValue.fromDouble(2.0)
        )
        val f3 = ProgressionCalculationFactor(
            ProgressionFactorKinds.OUTCOME,
            "Z",
            ProgressionScaledValue.fromDouble(1.0),
            ProgressionScaledValue.fromDouble(1.0)
        )

        val a = engine.evaluate(input(listOf(f1, f2, f3)))
        val b = engine.evaluate(input(listOf(f2, f1, f3)))
        val c = engine.evaluate(input(listOf(f3, f2, f1)))

        assertEquivalentDeterministicResult(a, b)
        assertEquivalentDeterministicResult(a, c)
    }

    private fun assertEquivalentDeterministicResult(expected: ProgressionResult, actual: ProgressionResult) {
        assertEquals(expected.grants.single().grantUnits, actual.grants.single().grantUnits)
        assertEquals(expected.inputFingerprint, actual.inputFingerprint)
        assertEquals(expected.progressionUid, actual.progressionUid)
        assertEquals(
            expected.computationRecords.single().computationFingerprint,
            actual.computationRecords.single().computationFingerprint
        )
        assertEquals(
            expected.computationRecords.single().computationUid,
            actual.computationRecords.single().computationUid
        )
        assertEquals(expected.grants.single().grantUid, actual.grants.single().grantUid)
        assertEquals(expected.grants.single().causalChangeUid, actual.grants.single().causalChangeUid)
        assertEquals(expected.ledgerIntents.single().ledgerIntentUid, actual.ledgerIntents.single().ledgerIntentUid)
        assertEquals(expected.resultFingerprint, actual.resultFingerprint)
        assertEquals(expected, actual)
    }

    private fun input(factors: List<ProgressionCalculationFactor>): ProgressionEvaluationInput =
        ProgressionEvaluationInput.create(
            campaignUid = "C1",
            characterUid = "P1",
            sourceTypeUid = "RPGOS-SOURCE:TRAIN_COMMAND",
            sourceChannelUid = ProgressionSourceChannels.TRAINING,
            stimulusUid = "STIMULUS-P20-C4-001",
            sourceCommandUid = "CMD-P20-C4-001",
            commandKindUid = PlayerCommandKinds.TRAIN,
            commandFingerprint = "COMMAND-FINGERPRINT-P20-C4-001",
            targetKindUid = ProgressionTargetKinds.STAT,
            targetUid = "STR",
            targetValueEvidence = ProgressionTargetValueEvidence(
                "CURRENT-STR-P20-C4-001",
                "10",
                "RPGOS-VALUE:EXACT",
                "1"
            ),
            progressSemanticsUid = "RPGOS-PROGRESS:EXACT_UNITS",
            progressSemanticsVersion = "1",
            effortUnits = 10L,
            methodUid = "METHOD-P20-C4-001",
            calculationFactors = factors,
            worldPackBindingIdentity = progressionWorldPackBindingIdentity(null),
            progressionPolicyUid = "RPGOS-PROGRESSION-POLICY:TEST",
            progressionPolicyVersion = "1",
            progressionEngineUid = ProgressionEngine.ENGINE_UID,
            progressionEngineVersion = ProgressionEngine.ENGINE_VERSION,
            dependencyVersions = mapOf("RPGOS-DEPENDENCY:TEST" to "1")
        )
}
