package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Phase19CanonicalRegressionTest {
    private val actor = CommandActorRef("PLAYER", "P1")
    private val binding = WorldPackRuleBinding("WORLD-A", "1")

    @Before fun resetProbe() {
        Probe.calls = 0
    }

    @Test fun P19_P17_REGRESSION_playerChangeSetRemainsDeterministicTransientProposal() {
        val result = boundEngine(AllowProvider()).resolve(command("P17"), boundContext()) as PlayerResolutionOutcome.Resolved
        val proposal = result.proposal
        val encoded = PlayerChangeSetCodec.encode(proposal)
        val decoded = PlayerChangeSetCodec.decode(encoded)
        assertEquals(proposal, decoded)
        assertEquals(encoded, PlayerChangeSetCodec.encode(decoded))
        assertEquals(PlayerChangeSetCodec.fingerprint(proposal), PlayerChangeSetCodec.fingerprint(decoded))
        assertEquals("CMD-P19-P17", proposal.sourceCommandUid)
        assertEquals("C1", proposal.campaignUid)
    }

    @Test fun P19_P18_REGRESSION_referenceValidationAndGenericOrchestrationRemainIntact() {
        val invalid = command("P18-BAD").copy(
            payload = TrainCommandPayload(DomainRef("STAT", "UNKNOWN"), 10L, "METHOD")
        )
        val rejected = PlayerDomainEngine(
            PlayerResolutionComponentRegistry.of(listOf(TrainComponent())),
            worldRuleRegistry = WorldRuleProviderRegistry.of(listOf(CountingProvider())),
            worldPackAuthority = WorldPackAuthoritySnapshot.single("C1", binding)
        ).resolve(invalid, boundContext()) as PlayerResolutionOutcome.Rejected
        assertEquals(PlayerResolutionRejectionReason.UNKNOWN_REFERENCE, rejected.rejection.reason)
        assertEquals(0, Probe.calls)

        val generic = PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(TrainComponent())))
        val genericResult = generic.resolve(command("P18-GENERIC"), unboundContext())
        assertTrue(genericResult is PlayerResolutionOutcome.Resolved)
        assertTrue((genericResult as PlayerResolutionOutcome.Resolved).evidence.worldRuleDecisions.isEmpty())
    }

    private fun boundEngine(provider: WorldRuleProvider) = PlayerDomainEngine(
        PlayerResolutionComponentRegistry.of(listOf(TrainComponent())),
        worldRuleRegistry = WorldRuleProviderRegistry.of(listOf(provider)),
        worldPackAuthority = WorldPackAuthoritySnapshot.single("C1", binding)
    )

    private fun command(suffix: String) = PlayerCommand(
        commandUid = "CMD-P19-$suffix",
        campaignUid = "C1",
        actor = actor,
        commandKindUid = PlayerCommandKinds.TRAIN,
        payload = TrainCommandPayload(DomainRef("STAT", "STR"), 10L, "METHOD"),
        provenance = CommandProvenance("P19-CANONICAL")
    )

    private fun boundContext() = PlayerResolutionContext.create(
        "C1",
        actor,
        knownReferences(),
        worldRuleMode = WorldRuleMode.Bound(binding)
    )

    private fun unboundContext() = PlayerResolutionContext.createUnboundGeneric(
        "C1",
        actor,
        knownReferences()
    )

    private fun knownReferences() = setOf(
        CampaignScopedDomainRef("C1", DomainRef("PLAYER", "P1")),
        CampaignScopedDomainRef("C1", DomainRef("STAT", "STR"))
    )

    private object Probe {
        var calls: Int = 0
    }

    private class AllowProvider : WorldRuleProvider("P19-REGRESSION", "1", "WORLD-A", "1") {
        override fun evaluate(request: WorldRuleRequest) = WorldRuleDecision.Allowed.create("P19-REGRESSION-RULE")
    }

    private class CountingProvider : WorldRuleProvider("P19-REGRESSION-COUNT", "1", "WORLD-A", "1") {
        override fun evaluate(request: WorldRuleRequest): WorldRuleDecision {
            Probe.calls++
            return WorldRuleDecision.Allowed.create("P19-REGRESSION-RULE")
        }
    }

    private class TrainComponent : PlayerResolutionComponent<TrainCommandPayload>(
        PlayerCommandKinds.TRAIN,
        TrainCommandPayload::class,
        "P19-CANONICAL-REGRESSION-TRAIN",
        "1"
    ) {
        override fun resolve(
            command: PlayerCommand<TrainCommandPayload>,
            context: PlayerResolutionContext
        ) = PlayerResolutionComponentOutcome.Resolved(
            PlayerResolutionDraft.create(
                changes = listOf(
                    PlayerDomainChange.create(
                        "P19-REGRESSION-CHANGE",
                        PlayerChangeKinds.STAT,
                        StatChange(DomainRef("PLAYER", "P1"), "STR", ExactLongDelta.of(1L))
                    )
                )
            )
        )
    }
}
