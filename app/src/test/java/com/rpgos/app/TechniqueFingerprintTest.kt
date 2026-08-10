package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TechniqueFingerprintTest {
    @Test
    fun requirementPhaseChangesFingerprintAndRequirementOrderDoesNot() {
        val player = PlayerTechnique("C", "P", "T", 25.0, provenance = "test")

        fun requirement(phase: TechniqueRequirementPhase) = TechniqueSkillRequirement(
            skillUid = "S",
            requirementPhase = phase,
            masteryBasis = TechniqueSkillMasteryBasis.BASE,
            minimumMastery = 10.0,
            requirementVersion = 1L,
            provenance = "pack"
        )

        fun fingerprint(requirements: List<TechniqueSkillRequirement>): String {
            val definition = TechniqueDefinition(
                techniqueUid = "T",
                worldPackUid = "W",
                key = "t",
                displayName = "Technique T",
                category = "generic",
                skillRequirements = requirements,
                provenance = "pack"
            )
            return DerivedValueResolver().resolve(
                DerivedResolutionRequest(
                    campaignId = "C",
                    characterUid = "P",
                    resolutionEpoch = 0L,
                    statDefinitions = emptyList(),
                    resourceDefinitions = emptyList(),
                    playerStats = emptyList(),
                    playerResources = emptyList(),
                    modifiers = emptyList(),
                    techniqueDefinitions = listOf(definition),
                    playerTechniques = listOf(player)
                )
            ).inputFingerprint
        }

        val acquisition = requirement(TechniqueRequirementPhase.ACQUISITION)
        val execution = requirement(TechniqueRequirementPhase.EXECUTION)
        val both = requirement(TechniqueRequirementPhase.BOTH)

        val acquisitionFingerprint = fingerprint(listOf(acquisition))
        val executionFingerprint = fingerprint(listOf(execution))
        val bothFingerprint = fingerprint(listOf(both))

        assertNotEquals(acquisitionFingerprint, executionFingerprint)
        assertNotEquals(acquisitionFingerprint, bothFingerprint)
        assertNotEquals(executionFingerprint, bothFingerprint)
        assertEquals(
            fingerprint(listOf(acquisition, execution)),
            fingerprint(listOf(execution, acquisition))
        )
    }
}
