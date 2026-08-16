package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class Phase20ProgressionEngineTest {
    private val actor = CommandActorRef("PLAYER", "P1")
    private val binding = WorldPackRuleBinding("WORLD-A", "1")
    private val engine = ProgressionEngine()

    @Before fun resetProbe() {
        Probe.precheckCalls = 0
        Probe.effectCalls = 0
        Probe.sawProgression = false
        Probe.sawPinnedWorldPack = false
    }

    @Test fun P20_01_identicalSemanticInputHasStableResultGrantLedgerAndFingerprints() {
        val input = input()
        val a = engine.evaluate(input)
        val b = engine.evaluate(input())
        assertEquals(a, b)
        assertEquals(a.progressionUid, b.progressionUid)
        assertEquals(a.inputFingerprint, b.inputFingerprint)
        assertEquals(a.resultFingerprint, b.resultFingerprint)
        assertEquals(a.grants.single().grantUid, b.grants.single().grantUid)
        assertEquals(a.ledgerIntents.single().ledgerIntentUid, b.ledgerIntents.single().ledgerIntentUid)
        assertEquals(a.computationRecords.single().computationFingerprint, b.computationRecords.single().computationFingerprint)
    }

    @Test fun P20_02_numericPolicyIsVersionedFixedPointAndRoundsHalfUpDeterministically() {
        assertEquals("RPGOS-PROGRESSION-NUMERIC:FIXED_1E6_HALF_UP", ProgressionNumericPolicy.POLICY_UID)
        assertEquals("1", ProgressionNumericPolicy.POLICY_VERSION)
        assertEquals(1_500_000L, ProgressionScaledValue.fromDouble(1.5).scaledUnits)
        val factor = ProgressionCalculationFactor(
            ProgressionFactorKinds.QUALITY, "QUALITY-EVIDENCE", ProgressionScaledValue.fromDouble(1.5),
            ProgressionScaledValue.fromDouble(1.5)
        )
        val result = engine.evaluate(input(effortUnits = 1L, calculationFactors = listOf(factor)))
        assertEquals(2L, result.grants.single().grantUnits)
    }

    @Test fun P20_03_numericPolicyRejectsNanInfinityNegativeUnderflowAndOverflow() {
        progressionFails("NON_FINITE_PROGRESSION_NUMERIC_VALUE") { ProgressionScaledValue.fromDouble(Double.NaN) }
        progressionFails("NON_FINITE_PROGRESSION_NUMERIC_VALUE") { ProgressionScaledValue.fromDouble(Double.POSITIVE_INFINITY) }
        progressionFails("NEGATIVE_PROGRESSION_NUMERIC_VALUE") { ProgressionScaledValue.fromDouble(-1.0) }
        progressionFails("PROGRESSION_NUMERIC_UNDERFLOW") { ProgressionScaledValue.fromDouble(1e-20) }
        progressionFails("PROGRESSION_NUMERIC_OVERFLOW") { ProgressionScaledValue.fromDouble(Double.MAX_VALUE) }
        val x2 = ProgressionCalculationFactor(
            ProgressionFactorKinds.QUALITY, "X2", ProgressionScaledValue.fromDouble(2.0), ProgressionScaledValue.fromDouble(2.0)
        )
        progressionFails("PROGRESSION_GRANT_OVERFLOW") { engine.evaluate(input(effortUnits = Long.MAX_VALUE, calculationFactors = listOf(x2))) }
    }

    @Test fun P20_04_zeroResultCreatesEvidenceButNoGrantLedgerOrZeroDelta() {
        val zero = ProgressionCalculationFactor(
            ProgressionFactorKinds.OUTCOME, "ZERO-OUTCOME", ProgressionScaledValue.one(), ProgressionScaledValue.zero()
        )
        val result = engine.evaluate(input(effortUnits = 10L, calculationFactors = listOf(zero)))
        assertTrue(result.grants.isEmpty())
        assertTrue(result.ledgerIntents.isEmpty())
        assertEquals(1, result.computationRecords.size)
        assertEquals(0L, result.computationRecords.single().finalGrantUnits)
        try {
            ExactLongDelta.of(0L)
            fail("zero durable delta must remain illegal")
        } catch (_: PlayerChangeSetStructuralException) {
        }
    }

    @Test fun P20_05_highTalentAndPotentialWithoutPositiveActivityCreateNoGain() {
        val talent = ProgressionProfileModifierEvidence.fromTalent(
            TalentEntry("C1", "P1", "DOMAIN-A", 999.0, 1, "TEST"),
            ProgressionScaledValue.fromDouble(10.0)
        )
        val potential = ProgressionProfileModifierEvidence.fromPotential(
            PotentialEntry("C1", "P1", "DOMAIN-A", "DIM-A", 999.0, 1, "TEST"),
            ProgressionScaledValue.fromDouble(10.0)
        )
        val result = engine.evaluate(input(effortUnits = 0L, talentEvidence = talent, potentialEvidence = potential))
        assertTrue(result.grants.isEmpty())
        assertTrue(result.ledgerIntents.isEmpty())
    }

    @Test fun P20_06_modifierScopeIsFailClosed() {
        val wrongCampaignTalent = ProgressionProfileModifierEvidence.fromTalent(
            TalentEntry("OTHER", "P1", "DOMAIN-A", 2.0, 1, "TEST"), ProgressionScaledValue.one()
        )
        progressionFails("PROGRESSION_MODIFIER_CAMPAIGN_MISMATCH") {
            engine.evaluate(input(talentEvidence = wrongCampaignTalent))
        }
        val wrongCharacterPotential = ProgressionProfileModifierEvidence.fromPotential(
            PotentialEntry("C1", "P2", "DOMAIN-A", "DIM-A", 2.0, 1, "TEST"), ProgressionScaledValue.one()
        )
        progressionFails("PROGRESSION_MODIFIER_CHARACTER_MISMATCH") {
            engine.evaluate(input(potentialEvidence = wrongCharacterPotential))
        }
    }

    @Test fun P20_07_engineSurfaceHasNoWriterDatabaseTransactionOrMutableStateCapability() {
        val forbidden = listOf("SQLite", "Database", "Store", "Repository", "Transaction", "StatePatch", "MutableState", "Dao")
        ProgressionEngine::class.java.declaredFields.forEach { field ->
            forbidden.forEach { token ->
                assertFalse("ProgressionEngine retained forbidden ${field.type.name}", field.type.name.contains(token, ignoreCase = true))
            }
        }
        val evaluate = ProgressionEngine::class.java.methods.single { it.name == "evaluate" }
        assertEquals(1, evaluate.parameterTypes.size)
        assertEquals(ProgressionEvaluationInput::class.java, evaluate.parameterTypes.single())
        forbidden.forEach { token -> assertFalse(evaluate.returnType.name.contains(token, ignoreCase = true)) }
    }

    @Test fun P20_08_integratedGrantUsesExistingTypedChangeAndMatchingTypedLedgerIntent() {
        val outcome = resolved(unboundEngine(ProgressionTrainComponent()).resolve(command(), unboundContext()))
        val change = outcome.proposal.changes.single()
        val ledger = outcome.proposal.ledgerIntents.single()
        val payload = ledger.payload as ProgressionLedgerIntentPayload
        assertEquals(PlayerChangeKinds.STAT, change.changeKindUid)
        assertTrue(change.payload is StatChange)
        assertEquals(10L, (change.payload as StatChange).delta.units)
        assertEquals(PlayerLedgerIntentKinds.PROGRESSION, ledger.ledgerKindUid)
        assertEquals(listOf(change.changeUid), ledger.causalChangeUids)
        assertEquals(change.changeUid, outcome.proposal.ledgerIntents.single().causalChangeUids.single())
        assertEquals("P1", payload.characterUid)
        assertEquals("STR", payload.targetUid)
        assertEquals(10L, payload.finalGrantUnits)
        assertEquals("RPGOS-PROGRESSION-POLICY:TEST", change.sourceRuleUid)
    }

    @Test fun P20_09_progressionProposalCodecRoundTripAndFingerprintRemainCanonical() {
        val proposal = resolved(unboundEngine(ProgressionTrainComponent()).resolve(command(), unboundContext())).proposal
        val encoded = PlayerChangeSetCodec.encode(proposal)
        val decoded = PlayerChangeSetCodec.decode(encoded)
        assertEquals(proposal, decoded)
        assertEquals(encoded, PlayerChangeSetCodec.encode(decoded))
        assertEquals(PlayerChangeSetCodec.fingerprint(proposal), PlayerChangeSetCodec.fingerprint(decoded))
        assertTrue(decoded.ledgerIntents.single().payload is ProgressionLedgerIntentPayload)
    }

    @Test fun P20_10_unknownAndWrongCampaignProgressionReferencesRejectBeforeProposal() {
        val unknownTarget = unboundEngine(ProgressionTrainComponent(targetUid = "MISSING")).resolve(command(), unboundContext()) as PlayerResolutionOutcome.Rejected
        assertEquals(PlayerResolutionRejectionReason.UNKNOWN_REFERENCE, unknownTarget.rejection.reason)

        val refs = setOf(
            CampaignScopedDomainRef("OTHER", DomainRef("PLAYER", "P1")),
            CampaignScopedDomainRef("C1", DomainRef("STAT", "STR"))
        )
        val wrongCampaign = unboundEngine(ProgressionTrainComponent()).resolve(
            command(), PlayerResolutionContext.createUnboundGeneric("C1", actor, refs)
        ) as PlayerResolutionOutcome.Rejected
        assertEquals(PlayerResolutionRejectionReason.WRONG_CAMPAIGN_REFERENCE, wrongCampaign.rejection.reason)
    }

    @Test fun P20_11_unknownProgressionDomainRejectsAndCustomWorldPackDomainSurvivesUnchanged() {
        val component = ProgressionTrainComponent(
            progressionDomainUid = "CUSTOM-DOMAIN",
            progressionDomainWorldPackUid = "WORLD-A",
            expectedWorldPackUid = "WORLD-A",
            expectedWorldPackVersion = "1"
        )
        val missing = boundEngine(component, AllowProvider()).resolve(command(), boundContext(includeDomain = false)) as PlayerResolutionOutcome.Rejected
        assertEquals(PlayerResolutionRejectionReason.UNKNOWN_REFERENCE, missing.rejection.reason)

        val accepted = resolved(boundEngine(component, AllowProvider()).resolve(command(), boundContext(includeDomain = true))).proposal
        val payload = accepted.ledgerIntents.single().payload as ProgressionLedgerIntentPayload
        assertEquals("CUSTOM-DOMAIN", payload.progressionDomainUid)
        assertEquals("WORLD-A", payload.worldPackUid)
        assertEquals("1", payload.worldPackVersion)
    }

    @Test fun P20_12_worldPackMismatchFailsClosedBeforeFinalProposal() {
        val component = ProgressionTrainComponent(
            progressionDomainUid = "CUSTOM-DOMAIN",
            progressionDomainWorldPackUid = "WORLD-A",
            expectedWorldPackUid = "WORLD-B",
            expectedWorldPackVersion = "1"
        )
        engineFails("PROGRESSION_WORLD_PACK_MISMATCH") {
            boundEngine(component, AllowProvider()).resolve(command(), boundContext(includeDomain = true))
        }
    }

    @Test fun P20_13_progressionEffectsReachExactlyOneFinalWorldRuleEffectCheckAndCanBeRejected() {
        val result = boundEngine(ProgressionTrainComponent(), RejectProgressionProvider()).resolve(command(), boundContext())
        assertTrue(result is PlayerResolutionOutcome.Rejected)
        assertEquals(PlayerResolutionRejectionReason.WORLD_RULE_REJECTED, (result as PlayerResolutionOutcome.Rejected).rejection.reason)
        assertEquals(1, Probe.precheckCalls)
        assertEquals(1, Probe.effectCalls)
        assertTrue(Probe.sawProgression)
        assertTrue(Probe.sawPinnedWorldPack)
        assertEquals(2, result.evidence.worldRuleDecisions.size)
        assertEquals(WorldRuleEvaluationStage.COMMAND_PRECHECK, result.evidence.worldRuleDecisions[0].stage)
        assertEquals(WorldRuleEvaluationStage.DRAFT_EFFECT_CHECK, result.evidence.worldRuleDecisions[1].stage)
    }

    @Test fun P20_14_progressionGeneratedReferenceIsIncludedInAugmentedClosure() {
        val component = ProgressionTrainComponent(targetKindUid = ProgressionTargetKinds.SKILL, targetUid = "SKILL-A")
        val missing = unboundEngine(component).resolve(command(), unboundContext()) as PlayerResolutionOutcome.Rejected
        assertEquals(PlayerResolutionRejectionReason.UNKNOWN_REFERENCE, missing.rejection.reason)

        val context = PlayerResolutionContext.createUnboundGeneric(
            "C1", actor, baseRefs() + CampaignScopedDomainRef("C1", DomainRef("SKILL", "SKILL-A"))
        )
        val proposal = resolved(unboundEngine(component).resolve(command(), context)).proposal
        assertTrue(proposal.changes.single().payload is SkillChange)
        assertEquals("SKILL-A", (proposal.changes.single().payload as SkillChange).skillUid)
    }

    @Test fun P20_15_techniqueGrantMapsToExistingTechniqueChange() {
        val component = ProgressionTrainComponent(targetKindUid = ProgressionTargetKinds.TECHNIQUE, targetUid = "TECH-A")
        val context = PlayerResolutionContext.createUnboundGeneric(
            "C1", actor, baseRefs() + CampaignScopedDomainRef("C1", DomainRef("TECHNIQUE", "TECH-A"))
        )
        val proposal = resolved(unboundEngine(component).resolve(command(), context)).proposal
        assertTrue(proposal.changes.single().payload is TechniqueChange)
        assertEquals(10L, (proposal.changes.single().payload as TechniqueChange).progressDelta.units)
    }

    @Test fun P20_16_duplicateStimulusIdentityFailsClosed() {
        engineFails("DUPLICATE_PROGRESSION_STIMULUS_UID") {
            unboundEngine(DuplicateStimulusComponent()).resolve(command(), unboundContext())
        }
    }

    @Test fun P20_17_legacyEvidenceIsNotReinterpretedOrAutoConverted() {
        val legacy = LegacyProgressionEvidence(
            "LEGACY-E1", "C1", "P1", "xp", "17.5", "LEGACY", "SAVE-1", 1, "IMPORT"
        )
        ProgressionProfilePolicy.validate(legacy)
        val before = legacy.copy()
        engine.evaluate(input())
        assertEquals(before, legacy)
        assertEquals("17.5", legacy.rawValue)
    }

    private fun input(
        effortUnits: Long = 10L,
        calculationFactors: List<ProgressionCalculationFactor> = emptyList(),
        talentEvidence: ProgressionProfileModifierEvidence? = null,
        potentialEvidence: ProgressionProfileModifierEvidence? = null
    ): ProgressionEvaluationInput = ProgressionEvaluationInput.create(
        campaignUid = "C1",
        characterUid = "P1",
        sourceTypeUid = "RPGOS-SOURCE:TRAIN_COMMAND",
        sourceChannelUid = ProgressionSourceChannels.TRAINING,
        stimulusUid = "STIMULUS-1",
        sourceCommandUid = "CMD-P20",
        commandKindUid = PlayerCommandKinds.TRAIN,
        commandFingerprint = "COMMAND-FINGERPRINT",
        targetKindUid = ProgressionTargetKinds.STAT,
        targetUid = "STR",
        targetValueEvidence = ProgressionTargetValueEvidence("CURRENT-STR", "10", "RPGOS-VALUE:EXACT", "1"),
        progressSemanticsUid = "RPGOS-PROGRESS:EXACT_UNITS",
        progressSemanticsVersion = "1",
        effortUnits = effortUnits,
        methodUid = "METHOD",
        calculationFactors = calculationFactors,
        talentEvidence = talentEvidence,
        potentialEvidence = potentialEvidence,
        worldPackBindingIdentity = progressionWorldPackBindingIdentity(null),
        progressionPolicyUid = "RPGOS-PROGRESSION-POLICY:TEST",
        progressionPolicyVersion = "1",
        progressionEngineUid = ProgressionEngine.ENGINE_UID,
        progressionEngineVersion = ProgressionEngine.ENGINE_VERSION,
        dependencyVersions = mapOf("RPGOS-DEPENDENCY:TEST" to "1")
    )

    private fun command() = PlayerCommand(
        commandUid = "CMD-P20",
        campaignUid = "C1",
        actor = actor,
        commandKindUid = PlayerCommandKinds.TRAIN,
        payload = TrainCommandPayload(DomainRef("STAT", "STR"), 10L, "METHOD"),
        provenance = CommandProvenance("P20-TEST")
    )

    private fun unboundEngine(component: PlayerResolutionComponent<TrainCommandPayload>) = PlayerDomainEngine(
        PlayerResolutionComponentRegistry.of(listOf(component))
    )

    private fun boundEngine(component: PlayerResolutionComponent<TrainCommandPayload>, provider: WorldRuleProvider) = PlayerDomainEngine(
        PlayerResolutionComponentRegistry.of(listOf(component)),
        worldRuleRegistry = WorldRuleProviderRegistry.of(listOf(provider)),
        worldPackAuthority = WorldPackAuthoritySnapshot.single("C1", binding)
    )

    private fun unboundContext() = PlayerResolutionContext.createUnboundGeneric("C1", actor, baseRefs())

    private fun boundContext(includeDomain: Boolean = false): PlayerResolutionContext {
        val refs = if (includeDomain) {
            baseRefs() + CampaignScopedDomainRef("C1", DomainRef(PlayerResolutionReferenceKinds.PROGRESSION_DOMAIN, "CUSTOM-DOMAIN"))
        } else baseRefs()
        return PlayerResolutionContext.create("C1", actor, refs, worldRuleMode = WorldRuleMode.Bound(binding))
    }

    private fun baseRefs() = setOf(
        CampaignScopedDomainRef("C1", DomainRef("PLAYER", "P1")),
        CampaignScopedDomainRef("C1", DomainRef("STAT", "STR"))
    )

    private fun resolved(outcome: PlayerResolutionOutcome): PlayerResolutionOutcome.Resolved {
        assertTrue("expected resolved but got $outcome", outcome is PlayerResolutionOutcome.Resolved)
        return outcome as PlayerResolutionOutcome.Resolved
    }

    private fun progressionFails(expected: String, block: () -> Unit) {
        try {
            block()
            fail("expected progression failure $expected")
        } catch (e: ProgressionStructuralException) {
            assertEquals(expected, e.code)
        }
    }

    private fun engineFails(expected: String, block: () -> Unit) {
        try {
            block()
            fail("expected engine failure $expected")
        } catch (e: PlayerDomainEngineStructuralException) {
            assertTrue("expected $expected but was ${e.code}", e.code.contains(expected))
        }
    }

    private class ProgressionTrainComponent(
        private val targetKindUid: String = ProgressionTargetKinds.STAT,
        private val targetUid: String = "STR",
        private val progressionDomainUid: String? = null,
        private val progressionDomainWorldPackUid: String? = null,
        private val expectedWorldPackUid: String? = null,
        private val expectedWorldPackVersion: String? = null
    ) : PlayerResolutionComponent<TrainCommandPayload>(
        PlayerCommandKinds.TRAIN,
        TrainCommandPayload::class,
        "RPGOS-COMPONENT:P20-TRAIN",
        "1"
    ) {
        override fun resolve(
            command: PlayerCommand<TrainCommandPayload>,
            context: PlayerResolutionContext
        ) = PlayerResolutionComponentOutcome.Resolved(
            PlayerResolutionDraft.create(
                progressionStimuli = listOf(stimulus(command))
            )
        )

        private fun stimulus(command: PlayerCommand<TrainCommandPayload>) = ProgressionStimulus.create(
            stimulusUid = "RPGOS-STIMULUS:${command.commandUid}:TRAIN",
            sourceTypeUid = "RPGOS-SOURCE:TRAIN_COMMAND",
            sourceChannelUid = ProgressionSourceChannels.TRAINING,
            subject = DomainRef("PLAYER", command.actor.actorUid),
            targetKindUid = targetKindUid,
            targetUid = targetUid,
            progressionDomainUid = progressionDomainUid,
            progressionDomainWorldPackUid = progressionDomainWorldPackUid,
            targetValueEvidence = ProgressionTargetValueEvidence(
                "RPGOS-CURRENT:${command.actor.actorUid}:$targetKindUid:$targetUid", "10", "RPGOS-VALUE:EXACT", "1"
            ),
            progressSemanticsUid = "RPGOS-PROGRESS:EXACT_UNITS",
            progressSemanticsVersion = "1",
            effortUnits = command.payload.effortUnits,
            methodUid = command.payload.methodUid,
            progressionPolicyUid = "RPGOS-PROGRESSION-POLICY:TEST",
            progressionPolicyVersion = "1",
            expectedWorldPackUid = expectedWorldPackUid,
            expectedWorldPackVersion = expectedWorldPackVersion
        )
    }

    private class DuplicateStimulusComponent : PlayerResolutionComponent<TrainCommandPayload>(
        PlayerCommandKinds.TRAIN,
        TrainCommandPayload::class,
        "RPGOS-COMPONENT:P20-DUPLICATE-STIMULUS",
        "1"
    ) {
        override fun resolve(command: PlayerCommand<TrainCommandPayload>, context: PlayerResolutionContext): PlayerResolutionComponentOutcome {
            val stimulus = ProgressionStimulus.create(
                stimulusUid = "DUPLICATE-STIMULUS",
                sourceTypeUid = "RPGOS-SOURCE:TRAIN_COMMAND",
                sourceChannelUid = ProgressionSourceChannels.TRAINING,
                subject = DomainRef("PLAYER", command.actor.actorUid),
                targetKindUid = ProgressionTargetKinds.STAT,
                targetUid = "STR",
                targetValueEvidence = ProgressionTargetValueEvidence("CURRENT", "10", "RPGOS-VALUE:EXACT", "1"),
                progressSemanticsUid = "RPGOS-PROGRESS:EXACT_UNITS",
                progressSemanticsVersion = "1",
                effortUnits = command.payload.effortUnits,
                methodUid = command.payload.methodUid,
                progressionPolicyUid = "RPGOS-PROGRESSION-POLICY:TEST",
                progressionPolicyVersion = "1"
            )
            return PlayerResolutionComponentOutcome.Resolved(
                PlayerResolutionDraft.create(progressionStimuli = listOf(stimulus, stimulus))
            )
        }
    }

    private class AllowProvider : WorldRuleProvider("P20-ALLOW", "1", "WORLD-A", "1") {
        override fun evaluate(request: WorldRuleRequest) = WorldRuleDecision.Allowed.create("P20-ALLOW-RULE")
    }

    private class RejectProgressionProvider : WorldRuleProvider("P20-REJECT-PROGRESSION", "1", "WORLD-A", "1") {
        override fun evaluate(request: WorldRuleRequest): WorldRuleDecision {
            return when (request.stage) {
                WorldRuleEvaluationStage.COMMAND_PRECHECK -> {
                    Probe.precheckCalls++
                    WorldRuleDecision.Allowed.create("P20-PRECHECK")
                }
                WorldRuleEvaluationStage.DRAFT_EFFECT_CHECK -> {
                    Probe.effectCalls++
                    val effects = request.effects ?: error("effects required")
                    val progression = effects.ledgerIntents.firstOrNull { it.ledgerKindUid == PlayerLedgerIntentKinds.PROGRESSION }
                    Probe.sawProgression = progression != null
                    val payload = progression?.payload as? ProgressionLedgerIntentPayload
                    Probe.sawPinnedWorldPack = payload?.worldPackUid == request.worldPack.worldPackUid &&
                        payload.worldPackVersion == request.worldPack.worldPackVersion
                    if (progression != null) {
                        WorldRuleDecision.Rejected.create("P20-EFFECT", "P20-PROGRESSION-EFFECT-REJECTED")
                    } else {
                        WorldRuleDecision.Allowed.create("P20-EFFECT")
                    }
                }
            }
        }
    }

    private object Probe {
        var precheckCalls = 0
        var effectCalls = 0
        var sawProgression = false
        var sawPinnedWorldPack = false
    }
}
