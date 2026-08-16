package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase22PlayerInvariantValidatorTest {
    private val subject = DomainRef("PLAYER", "P1")

    @Test fun P22_01_unexplainedPermanentStatRegressionRejects() {
        val result = PlayerInvariantValidator.validate(
            proposal(statChange("C-STAT", -1L, "RULE")), PlayerInvariantSnapshot.create("C1")
        )
        assertInvalid(result, "UNEXPLAINED_DURABLE_PROGRESSION_REGRESSION")
    }

    @Test fun P22_02_unexplainedSkillAndTechniqueRegressionReject() {
        val changes = listOf(
            PlayerDomainChange.create("C-SKILL", PlayerChangeKinds.SKILL, SkillChange(subject, "SK", ExactLongDelta.of(-2L)), "RULE"),
            PlayerDomainChange.create("C-TECH", PlayerChangeKinds.TECHNIQUE, TechniqueChange(subject, "TECH", ExactLongDelta.of(-3L)), "RULE")
        )
        val result = PlayerInvariantValidator.validate(proposal(changes), PlayerInvariantSnapshot.create("C1"))
        assertTrue(result is PlayerInvariantValidationResult.Invalid)
        assertEquals(2, (result as PlayerInvariantValidationResult.Invalid).violations.size)
    }

    @Test fun P22_03_typedInjuryAuthorizationAllowsDurableRegression() {
        val change = statChange("C-INJURY", -5L, "RULE-INJURY")
        val authorization = DurableRegressionAuthorization(
            authorizationUid = "AUTH-1", campaignUid = "C1", characterUid = "P1", changeUid = "C-INJURY",
            targetKindUid = ProgressionTargetKinds.STAT, targetUid = "STR",
            causeKindUid = DurableRegressionCauseKinds.INJURY, causeUid = "INJURY-77",
            evidenceUid = "EVIDENCE-77", ruleUid = "RULE-INJURY", ruleVersion = "1"
        )
        assertEquals(
            PlayerInvariantValidationResult.Valid,
            PlayerInvariantValidator.validate(proposal(change), PlayerInvariantSnapshot.create("C1", listOf(authorization)))
        )
    }

    @Test fun P22_04_resourceConsumptionInventoryRemovalAndEquipmentRemovalRemainLegal() {
        val changes = listOf(
            PlayerDomainChange.create("C-RES", PlayerChangeKinds.RESOURCE, ResourceChange(subject, "CHAKRA", ExactLongDelta.of(-10L))),
            PlayerDomainChange.create("C-INV", PlayerChangeKinds.INVENTORY, InventoryChange(subject, "ITEM-1", ExactLongDelta.of(-1L))),
            PlayerDomainChange.create("C-EQ", PlayerChangeKinds.EQUIPMENT, EquipmentChange(subject, "HAND", EquipmentOperation.UNEQUIP, null))
        )
        assertEquals(PlayerInvariantValidationResult.Valid,
            PlayerInvariantValidator.validate(proposal(changes), PlayerInvariantSnapshot.create("C1")))
    }

    @Test fun P22_05_positiveProgressAndRuntimeDerivedLikeDecreaseAreNotRetrogressionViolations() {
        val changes = listOf(
            statChange("C-UP", 1L, "RULE"),
            PlayerDomainChange.create("C-RUNTIME", PlayerChangeKinds.RUNTIME,
                RuntimeChange(subject, "TEMPORARY_IMPAIRMENT", ExactLongDelta.of(-1L)))
        )
        assertEquals(PlayerInvariantValidationResult.Valid,
            PlayerInvariantValidator.validate(proposal(changes), PlayerInvariantSnapshot.create("C1")))
    }

    @Test fun P22_06_wrongCampaignAndMismatchedAuthorizationReject() {
        val change = statChange("C-STAT", -1L, "RULE")
        assertInvalid(PlayerInvariantValidator.validate(proposal(change), PlayerInvariantSnapshot.create("OTHER")),
            "INVARIANT_SNAPSHOT_CAMPAIGN_MISMATCH")
        val bad = DurableRegressionAuthorization(
            "AUTH", "C1", "OTHER-PLAYER", "C-STAT", ProgressionTargetKinds.STAT, "STR",
            DurableRegressionCauseKinds.RESPEC, "CAUSE", "E", "RULE", "1"
        )
        assertInvalid(PlayerInvariantValidator.validate(proposal(change), PlayerInvariantSnapshot.create("C1", listOf(bad))),
            "INVALID_DURABLE_REGRESSION_AUTHORIZATION")
    }

    @Test fun P22_07_validatorHasNoWriterCapability() {
        val forbidden = listOf("SQLite", "Database", "Store", "Repository", "Dao", "Transaction", "Writer")
        PlayerInvariantValidator::class.java.declaredFields.forEach { field ->
            forbidden.forEach { token -> assertFalse(field.type.name.contains(token, ignoreCase = true)) }
        }
        assertTrue(PlayerInvariantValidator::class.java.declaredMethods.any { it.name == "validate" })
    }

    private fun statChange(uid: String, delta: Long, rule: String) = PlayerDomainChange.create(
        uid, PlayerChangeKinds.STAT, StatChange(subject, "STR", ExactLongDelta.of(delta)), rule
    )

    private fun proposal(change: PlayerDomainChange) = proposal(listOf(change))
    private fun proposal(changes: List<PlayerDomainChange>) = PlayerChangeSet.create(
        changeSetUid = "CS-" + changes.joinToString("-") { it.changeUid },
        campaignUid = "C1", sourceCommandUid = "CMD", actor = CommandActorRef("PLAYER", "P1"),
        changes = changes, provenance = ChangeSetProvenance("CMD", "TEST", "1")
    )

    private fun assertInvalid(result: PlayerInvariantValidationResult, detail: String) {
        assertTrue(result is PlayerInvariantValidationResult.Invalid)
        assertEquals(detail, (result as PlayerInvariantValidationResult.Invalid).violations.first().detailUid)
    }
}
