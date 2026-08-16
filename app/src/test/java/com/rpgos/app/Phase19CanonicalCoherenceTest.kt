package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class Phase19CanonicalCoherenceTest {
    private val actor = CommandActorRef("PLAYER", "P1")
    private val a1 = WorldPackRuleBinding("WORLD-A", "1")
    private val b1 = WorldPackRuleBinding("WORLD-B", "1")

    @Before fun resetProbe() {
        Probe.seen.clear()
        Probe.calls = 0
    }

    @Test fun P19_COHERENCE_01_oneCoherentAuthorityObservationThroughoutResolution() {
        val backing = AtomicReference(a1)
        val reads = AtomicInteger()
        val resolver = WorldPackAuthorityResolver {
            reads.incrementAndGet()
            val observed = backing.get()
            backing.set(b1)
            observed
        }
        val result = engine(resolver, listOf(RecordingProvider(a1)))
            .resolve(command("COH01"), context("C1", a1))
        assertTrue(result is PlayerResolutionOutcome.Resolved)
        assertEquals(1, reads.get())
        assertEquals(listOf(a1, a1), Probe.seen)
        assertEquals(b1, backing.get())
    }

    @Test fun P19_COHERENCE_02_legalAuthorityChangeVisibleOnNextResolution() {
        val backing = AtomicReference(a1)
        val reads = AtomicInteger()
        val resolver = WorldPackAuthorityResolver {
            reads.incrementAndGet()
            backing.get()
        }
        val engine = engine(
            resolver,
            listOf(RecordingProvider(a1), RecordingProvider(b1))
        )
        assertTrue(engine.resolve(command("COH02-A"), context("C1", a1)) is PlayerResolutionOutcome.Resolved)
        assertEquals(listOf(a1, a1), Probe.seen)
        Probe.seen.clear()
        backing.set(b1)
        assertTrue(engine.resolve(command("COH02-B"), context("C1", b1)) is PlayerResolutionOutcome.Resolved)
        assertEquals(2, reads.get())
        assertEquals(listOf(b1, b1), Probe.seen)
    }

    @Test fun P19_COHERENCE_03_mixedCampaignAndWorldPackObservationRejected() {
        structural("WORLD_RULE_BINDING_AUTHORITY_MISMATCH") {
            engine(
                WorldPackAuthorityResolver { a1 },
                listOf(CountingProvider(b1))
            ).resolve(command("COH03"), context("C1", b1))
        }
        assertEquals(0, Probe.calls)
    }

    @Test fun P19_COHERENCE_04_precheckAndEffectCheckUseExactSameBinding() {
        val result = engine(
            WorldPackAuthorityResolver { a1 },
            listOf(RecordingProvider(a1))
        ).resolve(command("COH04"), context("C1", a1))
        assertTrue(result is PlayerResolutionOutcome.Resolved)
        assertEquals(listOf(a1, a1), Probe.seen)
        val records = (result as PlayerResolutionOutcome.Resolved).evidence.worldRuleDecisions
        assertEquals(listOf(WorldRuleEvaluationStage.COMMAND_PRECHECK, WorldRuleEvaluationStage.DRAFT_EFFECT_CHECK), records.map { it.stage })
        assertTrue(records.all { it.worldPackUid == a1.worldPackUid && it.worldPackVersion == a1.worldPackVersion })
    }

    @Test fun P19_COHERENCE_05_exactlyOneAuthorityObservationPerResolution() {
        val reads = AtomicInteger()
        val resolver = WorldPackAuthorityResolver {
            reads.incrementAndGet()
            a1
        }
        val result = engine(resolver, listOf(RecordingProvider(a1)))
            .resolve(command("COH05"), context("C1", a1))
        assertTrue(result is PlayerResolutionOutcome.Resolved)
        assertEquals(1, reads.get())
    }

    private fun engine(
        authority: WorldPackAuthorityResolver,
        providers: List<WorldRuleProvider>
    ) = PlayerDomainEngine(
        PlayerResolutionComponentRegistry.of(listOf(TrainComponent())),
        worldRuleRegistry = WorldRuleProviderRegistry.of(providers),
        worldPackAuthority = authority
    )

    private fun command(suffix: String) = PlayerCommand(
        commandUid = "CMD-P19-$suffix",
        campaignUid = "C1",
        actor = actor,
        commandKindUid = PlayerCommandKinds.TRAIN,
        payload = TrainCommandPayload(DomainRef("STAT", "STR"), 10L, "METHOD"),
        provenance = CommandProvenance("P19-CANONICAL")
    )

    private fun context(campaign: String, binding: WorldPackRuleBinding) = PlayerResolutionContext.create(
        campaign,
        actor,
        setOf(
            CampaignScopedDomainRef(campaign, DomainRef("PLAYER", "P1")),
            CampaignScopedDomainRef(campaign, DomainRef("STAT", "STR"))
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
        val seen = mutableListOf<WorldPackRuleBinding>()
        var calls: Int = 0
    }

    private class RecordingProvider(
        binding: WorldPackRuleBinding
    ) : WorldRuleProvider("P19-REC-${binding.worldPackUid}", "1", binding.worldPackUid, binding.worldPackVersion) {
        override fun evaluate(request: WorldRuleRequest): WorldRuleDecision {
            Probe.seen += request.worldPack
            return WorldRuleDecision.Allowed.create("P19-CANONICAL-RULE")
        }
    }

    private class CountingProvider(
        binding: WorldPackRuleBinding
    ) : WorldRuleProvider("P19-COUNT-${binding.worldPackUid}", "1", binding.worldPackUid, binding.worldPackVersion) {
        override fun evaluate(request: WorldRuleRequest): WorldRuleDecision {
            Probe.calls++
            return WorldRuleDecision.Allowed.create("P19-CANONICAL-RULE")
        }
    }

    private class TrainComponent : PlayerResolutionComponent<TrainCommandPayload>(
        PlayerCommandKinds.TRAIN,
        TrainCommandPayload::class,
        "P19-CANONICAL-COHERENCE-TRAIN",
        "1"
    ) {
        override fun resolve(
            command: PlayerCommand<TrainCommandPayload>,
            context: PlayerResolutionContext
        ) = PlayerResolutionComponentOutcome.Resolved(
            PlayerResolutionDraft.create(
                changes = listOf(
                    PlayerDomainChange.create(
                        "P19-COHERENCE-CHANGE",
                        PlayerChangeKinds.STAT,
                        StatChange(DomainRef("PLAYER", "P1"), "STR", ExactLongDelta.of(1L))
                    )
                )
            )
        )
    }
}
