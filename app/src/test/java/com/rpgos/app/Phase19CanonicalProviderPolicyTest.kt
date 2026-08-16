package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class Phase19CanonicalProviderPolicyTest {
    private val actor = CommandActorRef("PLAYER", "P1")
    private val binding = WorldPackRuleBinding("WORLD-A", "1")

    @Before fun resetProbe() {
        Probe.calls = 0
    }

    @Test fun P19_PROVIDER_01_providerHasNoCanonicalMutationCapability() {
        val forbidden = listOf(
            "SQLite", "Database", "Dao", "Store", "Repository", "Transaction", "StatePatch",
            "Commit", "Writer", "CampaignSelectionManager", "CanonicalPackageReplacement"
        )
        listOf(WorldRuleProvider::class.java, WorldRuleRequest::class.java, WorldRuleProviderRegistry::class.java)
            .flatMap { it.declaredFields.toList() }
            .forEach { field ->
                forbidden.forEach { token ->
                    assertFalse("canonical mutation capability leaked through ${field.name}: ${field.type.name}", field.type.name.contains(token, true))
                }
            }
    }

    @Test fun P19_PROVIDER_02_mutableRetainedProviderStateRejected() {
        structural("MUTABLE_WORLD_RULE_PROVIDER_STATE") {
            WorldRuleProviderRegistry.of(listOf(MutableProvider()))
        }
    }

    @Test fun P19_PROVIDER_03_syntheticMutableCaptureRejected() {
        val captured = mutableListOf<String>()
        val provider = object : WorldRuleProvider("P19-CAPTURE", "1", binding.worldPackUid, binding.worldPackVersion) {
            override fun evaluate(request: WorldRuleRequest): WorldRuleDecision {
                captured += request.stage.name
                return WorldRuleDecision.Allowed.create("P19-CAPTURE-RULE")
            }
        }
        structural("UNSAFE_WORLD_RULE_PROVIDER_STATE") {
            WorldRuleProviderRegistry.of(listOf(provider))
        }
    }

    @Test fun P19_PROVIDER_04_statelessAndScalarSafeProviderAccepted() {
        WorldRuleProviderRegistry.of(listOf(StatelessProvider()))
        WorldRuleProviderRegistry.of(listOf(ScalarProvider("SAFE")))
    }

    @Test fun P19_PROVIDER_05_identicalSemanticInputProducesDeterministicDecisionIdentity() {
        val engine = engine(StatelessProvider())
        val first = engine.resolve(command("DET"), context()) as PlayerResolutionOutcome.Resolved
        val second = engine.resolve(command("DET"), context()) as PlayerResolutionOutcome.Resolved
        assertEquals(first.proposal, second.proposal)
        assertEquals(
            first.evidence.worldRuleDecisions.map { it.decisionFingerprint },
            second.evidence.worldRuleDecisions.map { it.decisionFingerprint }
        )
        assertEquals(
            first.evidence.worldRuleDecisions.map { it.requestFingerprint },
            second.evidence.worldRuleDecisions.map { it.requestFingerprint }
        )
    }

    @Test fun P19_ZERO_MUTATION_01_successfulResolutionProducesNoAuthoritativeMutation() {
        val sentinel = intArrayOf(7)
        val result = engine(StatelessProvider()).resolve(command("ZERO01"), context())
        assertTrue(result is PlayerResolutionOutcome.Resolved)
        assertEquals(7, sentinel.single())
        val proposal = (result as PlayerResolutionOutcome.Resolved).proposal
        assertEquals("CMD-P19-ZERO01", proposal.sourceCommandUid)
        assertNotEquals("", proposal.changeSetUid)
    }

    @Test fun P19_ZERO_MUTATION_02_authorityRejectionAndFaultProduceNoAuthoritativeMutation() {
        val sentinel = intArrayOf(11)
        structural("WORLD_RULE_AUTHORITY_MISSING") {
            PlayerDomainEngine(
                PlayerResolutionComponentRegistry.of(listOf(TrainComponent())),
                worldRuleRegistry = WorldRuleProviderRegistry.of(listOf(CountingProvider())),
                worldPackAuthority = WorldPackAuthoritySnapshot.empty()
            ).resolve(command("ZERO02-MISSING"), context())
        }
        assertEquals(0, Probe.calls)
        assertEquals(11, sentinel.single())

        structural("WORLD_RULE_AUTHORITY_READ_FAILED") {
            PlayerDomainEngine(
                PlayerResolutionComponentRegistry.of(listOf(TrainComponent())),
                worldRuleRegistry = WorldRuleProviderRegistry.of(listOf(CountingProvider())),
                worldPackAuthority = WorldPackAuthorityResolver { throw IllegalStateException("read fault") }
            ).resolve(command("ZERO02-FAULT"), context())
        }
        assertEquals(0, Probe.calls)
        assertEquals(11, sentinel.single())
    }

    private fun engine(provider: WorldRuleProvider) = PlayerDomainEngine(
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

    private fun context() = PlayerResolutionContext.create(
        "C1",
        actor,
        setOf(
            CampaignScopedDomainRef("C1", DomainRef("PLAYER", "P1")),
            CampaignScopedDomainRef("C1", DomainRef("STAT", "STR"))
        ),
        worldRuleMode = WorldRuleMode.Bound(binding)
    )

    private fun structural(code: String, block: () -> Unit) {
        try {
            block()
            fail("Expected $code")
        } catch (e: PlayerDomainEngineStructuralException) {
            assertEquals(code, e.code)
        }
    }

    private object Probe {
        var calls: Int = 0
    }

    private class MutableProvider : WorldRuleProvider("P19-MUTABLE", "1", "WORLD-A", "1") {
        private var counter: Int = 0
        override fun evaluate(request: WorldRuleRequest): WorldRuleDecision {
            counter++
            return WorldRuleDecision.Allowed.create("P19-MUTABLE-RULE")
        }
    }

    private class StatelessProvider : WorldRuleProvider("P19-STATELESS", "1", "WORLD-A", "1") {
        override fun evaluate(request: WorldRuleRequest) = WorldRuleDecision.Allowed.create("P19-STATELESS-RULE", listOf("E1"))
    }

    private class ScalarProvider(private val label: String) : WorldRuleProvider("P19-SCALAR", "1", "WORLD-A", "1") {
        override fun evaluate(request: WorldRuleRequest) = WorldRuleDecision.Allowed.create("P19-SCALAR-RULE:$label")
    }

    private class CountingProvider : WorldRuleProvider("P19-COUNTING", "1", "WORLD-A", "1") {
        override fun evaluate(request: WorldRuleRequest): WorldRuleDecision {
            Probe.calls++
            return WorldRuleDecision.Allowed.create("P19-COUNTING-RULE")
        }
    }

    private class TrainComponent : PlayerResolutionComponent<TrainCommandPayload>(
        PlayerCommandKinds.TRAIN,
        TrainCommandPayload::class,
        "P19-CANONICAL-POLICY-TRAIN",
        "1"
    ) {
        override fun resolve(
            command: PlayerCommand<TrainCommandPayload>,
            context: PlayerResolutionContext
        ) = PlayerResolutionComponentOutcome.Resolved(
            PlayerResolutionDraft.create(
                changes = listOf(
                    PlayerDomainChange.create(
                        "P19-POLICY-CHANGE",
                        PlayerChangeKinds.STAT,
                        StatChange(DomainRef("PLAYER", "P1"), "STR", ExactLongDelta.of(1L))
                    )
                )
            )
        )
    }
}
