package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase22PlayerInvariantValidatorTest {
    private val subject = DomainRef("PLAYER", "P1")
    private val actor = CommandActorRef("PLAYER", "P1")

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

    @Test fun P22_08_snapshotFingerprintIsDeterministicAndOrderIndependent() {
        val a = DurableRegressionAuthorization(
            "AUTH-A", "C1", "P1", "CHANGE-A", ProgressionTargetKinds.STAT, "STR",
            DurableRegressionCauseKinds.INJURY, "CAUSE-A", "E-A", "RULE-A", "1"
        )
        val b = DurableRegressionAuthorization(
            "AUTH-B", "C1", "P1", "CHANGE-B", ProgressionTargetKinds.SKILL, "SK",
            DurableRegressionCauseKinds.RESPEC, "CAUSE-B", "E-B", "RULE-B", "1"
        )
        val left = PlayerInvariantSnapshot.create("C1", listOf(a, b))
        val right = PlayerInvariantSnapshot.create("C1", listOf(b, a))
        assertEquals(left.fingerprint, right.fingerprint)
        assertEquals(left.authorizations, right.authorizations)
    }

    @Test fun P22_09_canonicalResolveRejectsUnexplainedAndAcceptsTypedCause() {
        val command = trainCommand()
        val context = trainContext()

        val rejected = PlayerDomainEngine(
            PlayerResolutionComponentRegistry.of(listOf(NegativeStatComponent()))
        ).resolve(command, context)
        assertTrue(rejected is PlayerResolutionOutcome.Rejected)
        assertEquals(PlayerResolutionRejectionReason.DOMAIN_REJECTED, (rejected as PlayerResolutionOutcome.Rejected).rejection.reason)
        assertEquals("UNEXPLAINED_DURABLE_PROGRESSION_REGRESSION", rejected.rejection.detailUid)

        val authorization = DurableRegressionAuthorization(
            "AUTH-ENGINE", "C1", "P1", "C-ENGINE", ProgressionTargetKinds.STAT, "STR",
            DurableRegressionCauseKinds.INJURY, "INJURY-ENGINE", "E-ENGINE", "RULE-INJURY", "1"
        )
        val accepted = PlayerDomainEngine(
            componentRegistry = PlayerResolutionComponentRegistry.of(listOf(NegativeStatComponent())),
            invariantSnapshotResolver = PlayerInvariantSnapshotResolver { campaignUid, _ ->
                PlayerInvariantSnapshot.create(campaignUid, listOf(authorization))
            }
        ).resolve(command, context)
        assertTrue(accepted is PlayerResolutionOutcome.Resolved)
        assertEquals(-1L, ((accepted as PlayerResolutionOutcome.Resolved).proposal.changes.single().payload as StatChange).delta.units)
    }

    @Test fun P22_10_canonicalResolveKeepsLegalNegativeResourceChange() {
        val command = PlayerCommand(
            commandUid = "CMD-RESOURCE", campaignUid = "C1", actor = actor,
            commandKindUid = PlayerCommandKinds.USE_RESOURCE_ACTION,
            payload = UseResourceActionCommandPayload(DomainRef("RESOURCE", "CHAKRA"), 5L, "ACTION"),
            provenance = CommandProvenance("P22-TEST")
        )
        val context = PlayerResolutionContext.createUnboundGeneric(
            "C1", actor, setOf(
                CampaignScopedDomainRef("C1", DomainRef("PLAYER", "P1")),
                CampaignScopedDomainRef("C1", DomainRef("RESOURCE", "CHAKRA"))
            )
        )
        val result = PlayerDomainEngine(
            PlayerResolutionComponentRegistry.of(listOf(NegativeResourceComponent()))
        ).resolve(command, context)
        assertTrue(result is PlayerResolutionOutcome.Resolved)
        assertEquals(-5L, ((result as PlayerResolutionOutcome.Resolved).proposal.changes.single().payload as ResourceChange).delta.units)
    }

    private fun trainCommand() = PlayerCommand(
        commandUid = "CMD-P22", campaignUid = "C1", actor = actor, commandKindUid = PlayerCommandKinds.TRAIN,
        payload = TrainCommandPayload(DomainRef("STAT", "STR"), 1L, "METHOD"),
        provenance = CommandProvenance("P22-TEST")
    )

    private fun trainContext() = PlayerResolutionContext.createUnboundGeneric(
        "C1", actor, setOf(
            CampaignScopedDomainRef("C1", DomainRef("PLAYER", "P1")),
            CampaignScopedDomainRef("C1", DomainRef("STAT", "STR"))
        )
    )

    private fun statChange(uid: String, delta: Long, rule: String) = PlayerDomainChange.create(
        uid, PlayerChangeKinds.STAT, StatChange(subject, "STR", ExactLongDelta.of(delta)), rule
    )

    private fun proposal(change: PlayerDomainChange) = proposal(listOf(change))
    private fun proposal(changes: List<PlayerDomainChange>) = PlayerChangeSet.create(
        changeSetUid = "CS-" + changes.joinToString("-") { it.changeUid },
        campaignUid = "C1", sourceCommandUid = "CMD", actor = actor,
        changes = changes, provenance = ChangeSetProvenance("CMD", "TEST", "1")
    )

    private fun assertInvalid(result: PlayerInvariantValidationResult, detail: String) {
        assertTrue(result is PlayerInvariantValidationResult.Invalid)
        assertEquals(detail, (result as PlayerInvariantValidationResult.Invalid).violations.first().detailUid)
    }

    private class NegativeStatComponent : PlayerResolutionComponent<TrainCommandPayload>(
        PlayerCommandKinds.TRAIN, TrainCommandPayload::class, "RPGOS-COMPONENT:P22-NEGATIVE-STAT", "1"
    ) {
        override fun resolve(
            command: PlayerCommand<TrainCommandPayload>,
            context: PlayerResolutionContext
        ) = PlayerResolutionComponentOutcome.Resolved(
            PlayerResolutionDraft.create(
                changes = listOf(
                    PlayerDomainChange.create(
                        "C-ENGINE", PlayerChangeKinds.STAT,
                        StatChange(DomainRef("PLAYER", command.actor.actorUid), "STR", ExactLongDelta.of(-1L)),
                        "RULE-INJURY"
                    )
                )
            )
        )
    }

    private class NegativeResourceComponent : PlayerResolutionComponent<UseResourceActionCommandPayload>(
        PlayerCommandKinds.USE_RESOURCE_ACTION, UseResourceActionCommandPayload::class,
        "RPGOS-COMPONENT:P22-NEGATIVE-RESOURCE", "1"
    ) {
        override fun resolve(
            command: PlayerCommand<UseResourceActionCommandPayload>,
            context: PlayerResolutionContext
        ) = PlayerResolutionComponentOutcome.Resolved(
            PlayerResolutionDraft.create(
                changes = listOf(
                    PlayerDomainChange.create(
                        "C-RESOURCE", PlayerChangeKinds.RESOURCE,
                        ResourceChange(DomainRef("PLAYER", command.actor.actorUid), "CHAKRA", ExactLongDelta.of(-5L))
                    )
                )
            )
        )
    }
}
